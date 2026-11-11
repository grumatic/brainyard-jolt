(ns brainyard.bt
  "A minimal behavior-tree interpreter — the jolt counterpart of brainyard's
  `ai.brainyard.agent.core.bt`, cut down to the node types the ReAct loop uses.

  Following samizdat's lead, the tree is DATA: an EDN vector of nodes whose
  leaves name their behaviour by keyword. The keyword is resolved against a
  registry at tick time, so a tree can be loaded from a resource (or a file the
  agent itself rewrote) without recompiling anything.

  Node shapes:
    [:sequence opts & children]   success iff every child succeeds, in order
    [:fallback opts & children]   first child to succeed wins
    [:repeat   opts child]        re-tick child until (:until opts) holds or
                                  (:max-n opts) ticks; a child failure fails
    [:condition opts]             (:fn opts) keyword → predicate of ctx
    [:action    opts]             (:fn opts) keyword → fn of ctx
    [:scope    opts child]        tick child against a FRESH st-memory, so a
                                  whole loop can nest inside another without
                                  their state colliding. (:in opts) seeds it,
                                  {inner-key outer-key}; (:out opts) copies
                                  back, {outer-key inner-key}, with :* meaning
                                  the whole inner memory. The outer memory is
                                  visible to the child as :parent-memory.
    [:include  opts]              (:tree opts) names another tree — a resource
                                  path or a file — spliced in by `expand`
                                  before the tree runs. This is how the ReAct
                                  loop nests inside the session loop.

  A leaf fn receives ctx with its own node opts under :node.
  An action returns :failure to fail; any other return value is success — or
  a Task (brainyard.effect), which is run and its value read the same way. A
  thrown exception is a failure too, recorded under :bt/error in st-memory so
  a sibling repair node can see what went wrong. A CANCELLATION is not a
  failure: it propagates (RFC-009 rule 7).

  THE TICK IS AN EFFECT — brainyard's effect BT engine (behavior-tree
  `nodes_task`, design §15/§16). `tick-task` returns a Task; every composite is
  an `m/sp` and every leaf runs on `blk` via `task-of`, because leaves block
  (LLM calls, tools). Cancelling the Task stops the tree structurally: the
  in-flight leaf's thread is interrupted and nothing after it runs. `tick` is
  the synchronous edge over it.

  A cancellable scope — [:scope {:cancellable :turn :on-cancel {…}} child] —
  registers a canceller under that key in (:cancellers ctx), an atom. Calling
  it cancels only the scope's child: the child's memory is merged with
  :on-cancel and the scope succeeds, so the loop around it carries on. This
  is how Ctrl-C ends a turn without ending the session.

  `opts` values that are keywords in the :config namespace (e.g.
  :config/max-iterations) are resolved from (:config ctx) at tick time —
  through the ctx-box, like a leaf's ctx, so an edit between turns reaches a
  composite's budget too."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [ebb.core :as m]))

(defn- resolve-opt [ctx v]
  (if (and (keyword? v) (= "config" (namespace v)))
    (get (:config (if-let [b (:ctx-box ctx)] (merge ctx @b) ctx)) (keyword (name v)))
    v))

(defn- resolve-fn [ctx k]
  (or (get (:registry ctx) k)
      (throw (ex-info (str "behavior tree: no fn registered for " k) {:fn k}))))

(defn- emit!
  "Put an event on the bus (RFC-026). One named door, so the catalogue of what
  the kernel emits can be checked against the source."
  [ctx event data]
  (events/emit! ctx event data))

(declare tick-task)

(defn- sequence-task [ctx children]
  (m/sp
   (loop [[c & more] children]
     (if (nil? c)
       :success
       (if (= :success (m/? (tick-task ctx c))) (recur more) :failure)))))

(defn- fallback-task [ctx children]
  (m/sp
   (loop [[c & more] children]
     (if (nil? c)
       :failure
       (if (= :success (m/? (tick-task ctx c))) :success (recur more))))))

(defn- repeat-task [ctx {:keys [max-n until] :as opts} child]
  (let [max-n (or (resolve-opt ctx max-n) 1)
        until-fn (when until (resolve-fn ctx until))]
    (m/sp
     (loop [n 0]
       (if (>= n max-n)
         (do (emit! ctx :bt.repeat/exhausted {:id (:id opts) :n n}) :success)
         (let [status (m/? (tick-task ctx child))]
           (cond
             (not= :success status) :failure
             ;; the predicate sees the repeat's own opts, as a leaf sees its
             ;; node — so `:until` can be parameterized by data
             ;; (`{:until :mem/true? :key :done}`) instead of naming a
             ;; purpose-built fn per loop
             (and until-fn (until-fn (assoc ctx :node opts))) :success
             :else (recur (inc n)))))))))

(def ^:dynamic *leaf-observer*
  "When bound, called `(f {:fn kw :node id :opts m :wrote #{keys}})` after every leaf,
  with the st-memory keys that leaf actually changed. This is how a leaf's
  declared `:writes` is kept honest instead of decaying into a comment
  (RFC-022): the suite runs under an observer that fails on an undeclared
  write. Nil in production, and one `when` when it is."
  nil)

(def record-keys
  "Blackboard keys the engine keeps for its own record, which no leaf's
  contract names: `:bt/error`, the exception a leaf threw, and
  `:trajectory-iterations`, the turn's uncapped record, where `:tool/run`
  puts each call it makes (byj-gly.322) — whatever tool a node plugs in, so
  not the contract an author implements."
  #{:bt/error :trajectory-iterations})

(defn- observe-writes!
  "Diff st-memory around a leaf and report what changed, but for the engine's
  own `record-keys`."
  [ctx f opts before]
  (when-let [mem (:st-memory ctx)]
    (let [after @mem
          wrote (into #{} (remove record-keys)
                      (for [[k v] after :when (not= v (get before k))] k))]
      (*leaf-observer* {:fn f :node (:id opts) :opts opts :wrote wrote}))))

(defn- leaf-task [ctx kind {:keys [id] f :fn :as opts}]
  (let [;; what the ctx has been given since the tree started: a `:ctx-box` is
        ;; an atom of keys laid over the ctx for every leaf from here on, so a
        ;; leaf can hand later leaves a new config, tools or zones while the
        ;; tree runs (RFC-002). Read when the leaf is reached, which is after
        ;; the leaf before it has run
        ctx (if-let [b (:ctx-box ctx)] (merge ctx @b) ctx)
        g (resolve-fn ctx f)
        ;; a leaf sees its own node opts, so a node can declare what its fn
        ;; works on (e.g. :predictor, :zones) instead of the fn hard-coding it
        ctx (assoc ctx :node opts)
        watch? (and *leaf-observer* (:st-memory ctx))]
    (m/sp
     (try
       ;; on blk: leaves block (brainyard nodes_task leaf-task)
       (let [before (when watch? @(:st-memory ctx))
             r (m/? (fx/task-of #(g ctx)))
             ;; an action may hand back a Task instead of a status (brainyard
             ;; nodes_task `lift`), so leaves migrate to effects one at a time
             r (if (and (= kind :action) (fn? r)) (m/? r) r)
             _ (when watch? (observe-writes! ctx f opts before))]
         (if (= kind :condition)
           (if r :success :failure)
           (if (= :failure r) :failure :success)))
       (catch Throwable e
         (if (fx/cancelled? e)
           (throw e)
           (let [err {:node (or id f) :message (or (ex-message e) (str e)) :data (ex-data e)}]
             (when-let [mem (:st-memory ctx)] (swap! mem assoc :bt/error err))
             (emit! ctx :bt.node/error err)
             :failure)))))))

(defn- cancellable-child
  "Run `child` so that the canceller registered under `key` cancels it alone.
  Settles with {:status s}, {:error e} or {:cancelled true}. The child's
  outcome is reified so a failing child cannot leave the race waiting on a
  signal that never comes."
  [cancellers key child]
  (let [signal (m/dfv)
        outcome (m/sp (try {:status (m/? child)}
                           (catch Throwable e
                             (if (fx/cancelled? e) (throw e) {:error e}))))]
    (m/sp
     (swap! cancellers assoc key #(signal :cancelled))
     (try
       (m/? (m/race outcome (m/sp (m/? signal) {:cancelled true})))
       (finally
         (swap! cancellers dissoc key))))))

(defn- scope-task [ctx {:keys [in out cancellable on-cancel id]} child]
  (m/sp
   (let [outer (:st-memory ctx)
         inner (atom (into {} (for [[ik ok] in
                                    :let [v (get @outer ok)]
                                    :when (some? v)]
                                [ik v])))
         child-task (tick-task (assoc ctx :st-memory inner :parent-memory outer) child)
         cancellers (:cancellers ctx)]
     (try
       (if (and cancellable cancellers)
         (let [{:keys [status error cancelled]} (m/? (cancellable-child cancellers cancellable child-task))]
           (cond
             cancelled (do (swap! inner merge on-cancel)
                           (emit! ctx :bt.scope/cancelled {:id id :key cancellable})
                           :success)
             error (throw error)
             :else status))
         (m/? child-task))
       (finally
         ;; applied whether the child succeeded, failed or was cancelled, so an
         ;; outer fallback can inspect what the inner loop left behind
         (let [mem @inner]
           (swap! outer into (for [[ok ik] out]
                               [ok (if (= :* ik) mem (get mem ik))]))))))))

(defn tick-task
  "A Task ticking one node against ctx, settling with :success or :failure.
  Fails (rather than settling) for a malformed node — non-map opts, unknown
  kind, unregistered fn, unexpanded include — and when cancelled."
  [ctx [kind opts & children :as node]]
  (when-not (map? opts)
    (throw (ex-info (str "behavior tree: node needs an opts map: " (pr-str node)) {:node node})))
  (case kind
    :sequence  (sequence-task ctx children)
    :fallback  (fallback-task ctx children)
    :repeat    (repeat-task ctx opts (first children))
    :scope     (scope-task ctx opts (first children))
    :include   (throw (ex-info (str "behavior tree: unexpanded :include of " (:tree opts)
                                    " — run the tree through `expand` first")
                               {:node node}))
    :condition (leaf-task ctx :condition opts)
    :action    (leaf-task ctx :action opts)
    (throw (ex-info (str "behavior tree: unknown node kind " kind) {:node node}))))

(defn tick
  "Tick one node against ctx and BLOCK, returning :success or :failure — the
  synchronous edge over `tick-task`. Rethrows what the task failed with; an
  interrupt of the waiting thread cancels the tree and throws
  InterruptedException."
  [ctx node]
  (let [r (fx/run!! (tick-task ctx node))]
    (cond
      (contains? r :ok) (:ok r)
      (:interrupted r) (throw (InterruptedException. "behavior tree tick interrupted"))
      :else (throw (:err r)))))

(defn expand
  "Splice every [:include {:tree name}] with the tree `load-tree` returns for
  name, recursively. Include opts other than :tree are merged over the
  included root's opts (so an include can re-:id it). Throws on a cycle."
  ([tree load-tree] (expand tree load-tree []))
  ([[kind opts & children :as node] load-tree seen]
   (if (= :include kind)
     (let [name (:tree opts)]
       (when (some #{name} seen)
         (throw (ex-info (str "behavior tree: include cycle " (str/join " → " (conj seen name)))
                         {:cycle (conj seen name)})))
       (let [[k o & cs] (expand (load-tree name) load-tree (conj seen name))]
         (into [k (merge o (dissoc opts :tree))] cs)))
     (into [kind opts] (map #(if (vector? %) (expand % load-tree seen) %)) children))))

(def node-kinds
  "Every kind `tick-task` dispatches on. `:include` is expanded away before a
  tree runs, so a checker that sees one is looking at an unexpanded tree."
  #{:sequence :fallback :repeat :scope :include :condition :action})

(defn nodes
  "Every node in the tree, depth-first, as `[kind opts & children]` vectors —
  what a checker walks (RFC-022)."
  [tree]
  (tree-seq vector? #(filter vector? (drop 2 %)) tree))

(defn node-id
  "A node's `:id`, or a positional stand-in so a problem can still be located."
  [[kind opts]]
  (or (:id opts) (keyword (str (name kind) "-anonymous"))))

(defn fn-refs
  "Every registry keyword a tree refers to — for validating a tree against a
  registry before running it."
  [tree]
  (->> (tree-seq vector? #(filter vector? (drop 2 %)) tree)
       (mapcat (fn [[_ opts]] (keep #(get opts %) [:fn :until])))
       set))

(defn validate
  "Nil when every fn the tree names is registered, else a message. The
  structural half of a check; `brainyard.flow/check` reports every problem a
  composition has, this one only reports whether it can run at all (RFC-022)."
  [tree registry]
  (let [missing (remove #(contains? registry %) (fn-refs tree))]
    (when (seq missing)
      (str "unregistered fns: " (str/join ", " (sort (map str missing)))))))
