;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.flow
  "Checking a workflow before it runs (RFC-022).

  `bt/validate` answers one question — can this tree run at all — and answers it
  with the first class of problem it finds. A workflow being *authored* needs
  the other question: everything wrong with it, at once. A caller that fixes one
  problem per round trip pays a round trip per problem, and an agent authoring a
  tree pays a model call.

  So `check` returns EVERY problem, the shape `predictor/validate-spec` already
  uses. It lives here rather than in `brainyard.bt` because it must see
  predictors, tools, config and hook events, and the tree interpreter is not
  allowed to know any of those exist (RFC-002).

  The checks that matter most are the silent ones. A `:config/max-iteratons`
  typo reads `nil` today and the loop runs once; a misspelled zone sends no text
  and the model simply does worse. Neither fails, which is why neither is found."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [brainyard.bt :as bt]
            [brainyard.config :as config]
            [brainyard.hooks :as hooks]
            [brainyard.predictor :as predictor]
            [brainyard.schema :as schema]))

(def known-zones
  "Zones the runtime supplies: two from ctx, two the loop writes into st-memory
  each turn (`agent/zone-blocks`). A caller that adds its own passes them as
  `:zones`."
  #{:agent-core :session-context :history-context :user-context})

(defn- problem [node msg] {:node (bt/node-id node) :problem msg})

;; --- what a node refers to -------------------------------------------------------

(defn config-refs
  "Every `:config/…` keyword anywhere in a node's opts, however deeply nested —
  they appear as opt values (`:max-n :config/max-iterations`) and inside hook
  payload maps."
  [opts]
  (->> (tree-seq coll? seq opts)
       (filter #(and (keyword? %) (= "config" (namespace %))))
       set))

(defn- field-names
  "The declared field keys of a signature half, as a set."
  [fields]
  (set (map first fields)))

;; --- the checks ------------------------------------------------------------------

(defn- check-fn [node registry]
  (let [[_ opts] node]
    (for [k (keep #(get opts %) [:fn :until])
          :when (not (contains? registry k))]
      (problem node (str "unregistered fn " k)))))

(defn- check-config [node config-keys]
  (let [[_ opts] node]
    (for [r (config-refs opts)
          :let [k (keyword (name r))]
          :when (not (contains? config-keys k))]
      (problem node (str "unknown config key " r " — nothing sets it, so it reads nil")))))

(defn- check-zones [node zones]
  (let [[_ opts] node]
    (for [z (:zones opts)
          :when (not (contains? zones z))]
      (problem node (str "unknown zone " z " — it would render as nothing")))))

(defn- check-event [node]
  (let [[_ opts] node]
    (when-let [e (:event opts)]
      (when-not (hooks/known-event? e)
        [(problem node (str "unknown hook event " e " — it would never fire"))]))))

(defn- check-repeat [node]
  (let [[kind opts] node]
    (when (and (= :repeat kind) (not (:max-n opts)) (not (:until opts)))
      [(problem node "repeat has neither :max-n nor :until — it cannot terminate")])))

(defn- check-predictor [node roots]
  (let [[_ opts] node
        id (:predictor opts)]
    (when id
      (let [p (try {:p (predictor/load-predictor id roots)}
                   (catch Throwable e {:error (ex-message e)}))]
        (if-let [e (:error p)]
          [(problem node (str "predictor " id ": " e))]
          (let [sig (:signature (:p p))
                ins (field-names (:inputs sig))
                outs (conj (field-names (:outputs sig)) :reasoning)]
            (concat
             (for [[field _] (:in opts) :when (not (contains? ins field))]
               (problem node (str "predictor " id " has no input " field
                                  " — it declares " (str/join ", " (sort ins)))))
             (for [[_ field] (:out opts) :when (not (contains? outs field))]
               (problem node (str "predictor " id " has no output " field
                                  " — it declares " (str/join ", " (sort outs))))))))))))

(defn- check-tool [node tools]
  (let [[_ opts] node
        id (:tool opts)]
    (when id
      (if-let [t (get tools id)]
        (let [params (set (map :name (schema/inputs t)))
              ;; what its :output-schema says a result holds (RFC-030); a
              ;; prose one, or one that is not a map, cannot say, and
              ;; then :out goes unchecked as it always did (byj-gly.201)
              outs (schema/result-keys (schema/tool-output-schema t))]
          (concat
           (for [[arg _] (:in opts) :when (not (contains? params arg))]
             (problem node (str "tool " id " has no param " arg
                                (when (seq params)
                                  (str " — it takes " (str/join ", " (sort params)))))))
           (when outs
             (for [[_ field] (:out opts) :when (not (contains? outs field))]
               (problem node (str "tool " id " returns no key " field
                                  " — its :output-schema has " (str/join ", " (sort outs))))))))
        [(problem node (str "unknown tool " id))]))))

(defn- check-ids [tree]
  (let [ids (keep (fn [[_ opts]] (:id opts)) (bt/nodes tree))]
    (for [[id n] (frequencies ids) :when (> n 1)]
      {:node id :problem (str "duplicate node id " id " (" n " nodes) — a trace cannot name one")})))

(defn- check-kind
  "The node kind is one the interpreter dispatches on.

  Found by the `byj-gly.63` eval, by a live model doing something no test of
  mine had: asked to add a tool node it wrote `[:tool {:tool :classify …}]`,
  inventing a node kind. `check` reported the unknown tool and said nothing
  about the kind, so it passed a tree that `bt/tick` throws on — the exact
  class of defect this exists to prevent (`byj-gly.65`)."
  [[kind opts :as node]]
  (cond
    (not (contains? bt/node-kinds kind))
    [(problem node (str "unknown node kind " kind " — it must be one of "
                        (str/join ", " (sort (map str bt/node-kinds)))
                        ". A tool or predictor step is an :action naming :fn; call flow$format"))]
    (not (map? opts))
    [(problem node "node needs an opts map as its second element")]))

(defn- check-tool-is-named
  "A `:tool` names a tool by STRING. A keyword is the other half of the mistake
  above, and would otherwise be reported only as an unknown tool."
  [[_ opts :as node]]
  (when (and (:tool opts) (not (string? (:tool opts))))
    [(problem node (str "node :tool must be the tool's name as a string, not "
                        (pr-str (:tool opts))))]))

(defn- check-leaf-implements [node]
  (let [[kind opts] node]
    (when (and (contains? #{:action :condition} kind)
               (not (:fn opts)))
      [(problem node (str (name kind) " names no :fn — a leaf must say what runs"))])))


;; --- the memory chain -------------------------------------------------------------

(declare annotated?)

(defn contract
  "What a node reads and writes, as `{:reads #{} :writes #{}}`.

  A leaf's fixed half is metadata on the fn it describes — the registry holds
  VARS, so the contract travels with the fn and cannot drift onto another entry
  (`(meta #'init-action)`). Three leaves are node-determined instead, and those
  are read from the node: `:predictor/run` and `:tool/run` write what their
  `:out` names and read what their `:in` names, and `:hooks/fire` reads what its
  `:payload` names."
  [registry [_ opts]]
  (let [m (some-> (get registry (:fn opts)) meta)
        ;; a payload value may be a literal, or a :config/… key the runtime
        ;; resolves — neither is a blackboard read
        mem-keys (fn [vs] (into #{} (filter #(and (keyword? %) (not= "config" (namespace %)))) vs))]
    {:reads (into (set (:reads m))
                  (mem-keys (concat (vals (:in opts)) (vals (:payload opts)))))
     ;; `:out` for a leaf that maps a result, `:set` for one that seeds literals
     :writes (into (into (set (:writes m)) (keys (:out opts))) (keys (:set opts)))}))

(defn- annotated?
  "Has this leaf declared anything at all? An un-annotated leaf is not treated
  as writing nothing — that would make every key it provides look unwritten."
  [registry [_ opts]]
  (let [m (some-> (get registry (:fn opts)) meta)]
    (or (contains? m :reads) (contains? m :writes)
        (:in opts) (:out opts) (:set opts) (:payload opts))))

(defn- leaf? [[kind]] (contains? #{:action :condition} kind))

(defn all-writes
  "Every key any node in the tree writes — what separates a typo from an
  ordering mistake."
  [tree registry]
  (into #{} (mapcat #(:writes (contract registry %))) (filter leaf? (bt/nodes tree))))

(defn- merge-avail
  "Union two provenance maps: a key may be written by more than one node."
  [a b]
  (merge-with into a b))

(defn- intersect-avail
  "Keys present in EVERY branch, with their writers unioned — what a fallback
  can still promise once exactly one of its children has run."
  [ms]
  (if (empty? ms)
    {}
    (let [ks (reduce set/intersection (map (comp set keys) ms))]
      (into {} (for [k ks] [k (reduce into #{} (map #(get % k) ms))])))))

(defn- walk-chain
  "Abstract interpretation of the blackboard. `available` maps a key to the
  nodes that wrote it, so a problem can say who provides what and a brief can
  name a node's actual suppliers.

  Returns `[problems available-on-exit index]`, where `index` is
  `{node-id {:available <map on entry>}}` — the record `brief` reads.

  The rules follow what each node kind guarantees, and nothing more:

  - `:sequence` — a child runs only if the previous SUCCEEDED, so writes thread
    left to right and accumulate.
  - `:fallback` — a child runs only if the previous FAILED, so it cannot rely on
    a sibling's writes. Every child starts from the same set, and the set on
    exit keeps only what EVERY child writes, since only one of them ran.
  - `:repeat` — the body may run zero times, so nothing it writes is guaranteed
    afterwards. Inside, a node sees only what precedes it in the body: on the
    first iteration there is no previous iteration to have written the rest.
  - `:scope` — a barrier. The child starts from its `:in` keys alone, and the
    outer gains its `:out` keys (RFC-002).

  Being wrong in the safe direction means a missed problem, never a false one."
  [node registry available]
  (let [[kind opts & children] node]
    (case kind
      :sequence (reduce (fn [[ps avail idx] c]
                          (let [[cps cavail cidx] (walk-chain c registry avail)]
                            [(into ps cps) cavail (merge idx cidx)]))
                        [[] available {}] children)

      :fallback (let [rs (map #(walk-chain % registry available) children)]
                  [(vec (mapcat first rs))
                   (merge-avail available (intersect-avail (map second rs)))
                   (reduce merge {} (map #(nth % 2) rs))])

      (:repeat :scope)
      (let [child (first children)
            inner (if (= :scope kind)
                    (into {} (for [k (keys (:in opts))] [k #{(bt/node-id node)}]))
                    available)
            [ps _ idx] (if child (walk-chain child registry inner) [[] inner {}])]
        [ps (if (= :scope kind)
              (merge-avail available (into {} (for [k (keys (:out opts))] [k #{(bt/node-id node)}])))
              available)
         idx])

      ;; a leaf
      (let [{:keys [reads writes]} (contract registry node)
            id (bt/node-id node)
            missing (remove available reads)]
        [(vec (for [k missing]
                (problem node (str "reads " k " but nothing guarantees it here"))))
         (merge-avail available (into {} (for [k writes] [k #{id}])))
         {id {:available available}}]))))

(defn check-memory-chain
  "Checks 11a and 11b (RFC-022): a key a node reads that nothing anywhere
  writes is a TYPO; one that is written, but not by something that always runs
  first, is an ORDERING mistake. Saying which is the difference between a
  useful diagnostic and a puzzle.

  `:initial` names the keys the caller seeds before the tree runs — `:question`
  for a turn — without which every loop reports its own entry point."
  [tree registry initial]
  (let [written (all-writes tree registry)
        [problems _ _] (walk-chain tree registry (into {} (for [k initial] [k #{:initial}])))]
    (for [{:keys [node problem] :as p} problems
          :let [k (second (str/split problem #" "))]]
      (if (contains? written (keyword (subs k 1)))
        p
        {:node node :problem (str "reads " k " but nothing in this tree writes it")}))))

(defn write-observer
  "An `bt/*leaf-observer*` that records every write a leaf made which its
  contract did not declare, and the atom those land in.

  This is what stops `:writes` becoming a comment. A declaration that drifts
  from its fn would quietly weaken the memory chain — a key would look
  unwritten, or a stale one would look guaranteed — and nothing would say so.
  Bind this around the suite and an undeclared write fails a test instead."
  [registry]
  (let [violations (atom [])]
    [violations
     (fn [{:keys [fn node opts wrote]}]
       ;; a leaf with NO contract is unannotated, not in breach — a test that
       ;; registers an ad-hoc fn is not making a claim about it
       (when (annotated? registry [nil (assoc opts :fn fn)])
         (let [declared (:writes (contract registry [nil (assoc opts :fn fn)]))
               undeclared (remove declared wrote)]
           (when (seq undeclared)
             (swap! violations conj {:fn fn :node node :undeclared (vec (sort undeclared))})))))]))

;; --- the entry point --------------------------------------------------------------

(defn check
  "Every problem with `tree`, as `[{:node :problem} …]`, or nil when it is sound.

  opts:
    :registry        leaf fns by keyword (`agent/registry`)
    :tools           the tool registry as a map, for `:tool` leaves
    :predictor-roots where a `:predictor` leaf's spec is looked up
    :config-keys     extra keys a `:config/…` reference may name, beyond
                     RFC-013's schema — which since `byj-gly.64` is every key,
                     a session's included.
    :zones           zone keys the caller supplies, beyond the built-in four
    :initial         blackboard keys the caller seeds before the tree runs.
                     `agent/ask` seeds `:question`, so a turn loop passes it;
                     without it every loop reports its own entry point.

  The tree must already be expanded (`bt/expand`); an `:include` cycle throws
  there, before anything here can report it."
  [tree {:keys [registry tools predictor-roots config-keys zones initial]}]
  (let [config-keys (into (set (keys config/config-items)) config-keys)
        zones (into known-zones zones)
        tools (or tools {})
        per-node (fn [node]
                   (concat (check-kind node)
                           (check-fn node registry)
                           (check-leaf-implements node)
                           (check-tool-is-named node)
                           (check-config node config-keys)
                           (check-zones node zones)
                           (check-event node)
                           (check-repeat node)
                           (check-predictor node predictor-roots)
                           (check-tool node tools)))
        ;; the memory chain is only sound when every leaf declares a contract:
        ;; an unannotated leaf's writes are unknown, and a key it provides would
        ;; be reported as written by nothing. Say so rather than guess.
        leaves (filter leaf? (bt/nodes tree))
        silent (remove #(annotated? registry %) leaves)
        chain (if (seq silent)
                [{:node :tree
                  :problem (str (count silent) " leaf/leaves declare no :reads/:writes ("
                                (str/join ", " (sort (map #(str (or (:fn (second %))
                                                                    (bt/node-id %)))
                                                          silent)))
                                ") — the memory chain was not checked")}]
                (check-memory-chain tree registry initial))
        problems (concat (mapcat per-node (bt/nodes tree)) (check-ids tree) chain)]
    (seq (vec problems))))

(declare tree-hash)

(defn check-tree-name
  "Load a tree by name and check it. Returns `{:tree :problems :hash}`; a tree
  that cannot be loaded or expanded is itself the problem.

  `:hash` is of the SOURCE, not the expansion — it is what a patch guards
  against, and the two differ for any tree with an `:include`."
  [tree-name opts]
  (try
    (let [resolve-tree (requiring-resolve 'brainyard.agent/resolve-tree)
          tree ((requiring-resolve 'brainyard.agent/load-tree) tree-name)]
      {:tree tree :problems (check tree opts) :hash (tree-hash (resolve-tree tree-name))})
    (catch Throwable e
      {:problems [{:node :tree :problem (ex-message e)}]})))


;; --- brief, status, probe ------------------------------------------------------------

(defn node-kind
  "What implements a leaf: `:tool` and `:predictor` are data an agent can
  author, `:fn` is compiled."
  [[_ opts]]
  (cond (:tool opts) :tool
        (:predictor opts) :predictor
        :else :fn))

(defn- implements [[_ opts]]
  (or (:tool opts) (:predictor opts) (some-> (:fn opts) str)))

(defn find-node
  "The leaf with this id, or nil."
  [tree id]
  (first (filter #(and (leaf? %) (= id (bt/node-id %))) (bt/nodes tree))))

(defn- leaf-order
  "Leaves in traversal order, as `{node-id index}` — enough to tell a supplier
  from a consumer without enumerating paths."
  [tree]
  (into {} (map-indexed (fn [i n] [(bt/node-id n) i]) (filter leaf? (bt/nodes tree)))))

(defn status
  "Every leaf, what implements it, and whether it is ready — the report an
  authoring loop terminates on.

  `:missing` means it names a predictor or tool that does not exist: the node is
  specified but unimplemented, which is the state a brief is for. `:failing`
  means it exists and something else about it is wrong."
  [tree opts]
  (let [problems (or (check tree opts) [])
        by-node (group-by :node problems)
        rows (for [n (filter leaf? (bt/nodes tree))
                   :let [id (bt/node-id n)
                         ps (map :problem (get by-node id))
                         missing? (some #(re-find #"^unknown (tool|predictor)|^predictor .* not found" %) ps)]]
               (cond-> {:node id
                        :kind (node-kind n)
                        :implements (implements n)
                        :status (cond missing? "missing" (seq ps) "failing" :else "ready")}
                 (seq ps) (assoc :problems (vec ps))))
        rows (vec rows)
        by-status (frequencies (map :status rows))]
    {:nodes (count rows)
     :ready (get by-status "ready" 0)
     :missing (get by-status "missing" 0)
     :failing (get by-status "failing" 0)
     :detail rows}))

(defn brief
  "What one node must do, and nothing else — mycelium's `cell-brief`.

  The contract is derived from the node's NEIGHBOURS, which is what makes this
  a specification rather than a description: each key the node reads is listed
  with the nodes that actually write it, and each key it writes with the nodes
  downstream that read it. A write nothing reads is reported as such — either
  the node does too much, or a consumer is still missing."
  [tree id opts]
  (if-let [node (find-node tree id)]
    (let [registry (:registry opts)
          initial (into {} (for [k (:initial opts)] [k #{:initial}]))
          [_ _ index] (walk-chain tree registry initial)
          available (get-in index [id :available] {})
          {:keys [reads writes]} (contract registry node)
          order (leaf-order tree)
          pos (get order id)
          exported (set (:exported opts))
          consumers (fn [k]
                      (vec (sort (for [n (filter leaf? (bt/nodes tree))
                                       :let [nid (bt/node-id n)]
                                       :when (and (> (get order nid -1) pos)
                                                  (contains? (:reads (contract registry n)) k))]
                                   nid))))
          st (first (filter #(= id (:node %)) (:detail (status tree opts))))]
      {:node id
       :kind (:kind st)
       :implements (:implements st)
       :status (:status st)
       :problems (:problems st [])
       :reads (vec (for [k (sort reads)]
                     {:key k
                      :from (vec (sort (map str (get available k))))
                      :guaranteed (contains? available k)}))
       ;; a key the CALLER reads when the tree finishes is not unused just
       ;; because no node reads it (`agent/turn-result`)
       :writes (vec (for [k (sort writes)
                          :let [cs (consumers k)
                                out? (contains? exported k)]]
                      (cond-> {:key k :read-by cs :unused (and (empty? cs) (not out?))}
                        out? (assoc :exported true))))})
    {:error (str "no leaf " id " in this tree")}))

(defn probe
  "Run ONE node against a blackboard you supply — mycelium's `test-cell`. How a
  node is debugged without running the workflow that reaches it.

  Returns what it wrote, so a brief's `:writes` can be checked against what the
  node does rather than what it claims."
  [tree id memory ctx]
  (if-let [node (find-node tree id)]
    (let [mem (atom (or memory {}))
          before @mem
          t0 (System/currentTimeMillis)
          status (try (bt/tick (assoc ctx :st-memory mem) node)
                      (catch Throwable e {:error (ex-message e)}))
          after @mem]
      (if (map? status)
        (merge {:node id} status)
        {:node id
         :status (name status)
         :wrote (into {} (for [[k v] after :when (and (not= v (get before k)) (not (bt/record-keys k)))] [k v]))
         :elapsed-ms (- (System/currentTimeMillis) t0)}))
    {:error (str "no leaf " id " in this tree")}))


;; --- patching ----------------------------------------------------------------------

(defn tree-hash
  "A stable 12-hex-char name for a tree's content — what a patch checks it is
  editing what it read."
  [tree]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (pr-str tree) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 6 d)))))

(defn- edit-node
  "Rebuild `tree`, replacing the node whose id is `id` with `(f node)`. `f`
  returning nil removes it."
  [tree id f]
  (letfn [(walk [node]
            (if-not (vector? node)
              node
              (let [[kind opts & children] node
                    node (into [kind opts] (keep walk) children)]
                (if (= id (bt/node-id node)) (f node) node))))]
    (walk tree)))

(defn- insert-into
  "Put `new-node` among `parent`'s children, at `after` — a sibling id,
  `:first`, or `:last` (the default)."
  [[kind opts & children] after new-node]
  (let [children (vec children)
        idx (cond
              (= :first after) 0
              (or (nil? after) (= :last after)) (count children)
              :else (if-let [i (first (keep-indexed
                                       #(when (= after (bt/node-id %2)) %1) children))]
                      (inc i)
                      (count children)))]
    (into [kind opts] (concat (subvec children 0 idx) [new-node] (subvec children idx)))))

(defn apply-op
  "One structural edit. Returns the tree, or throws with what was wrong."
  [tree {:keys [op node parent after key value to] :as o}]
  (let [must-exist! (fn [id]
                      (when-not (some #(= id (bt/node-id %)) (bt/nodes tree))
                        (throw (ex-info (str "no node " id " in this tree") {:op o}))))]
    (case op
      :add-node (do (must-exist! parent)
                    (when-not (vector? (:node o))
                      (throw (ex-info "add-node needs a :node vector" {:op o})))
                    (edit-node tree parent #(insert-into % after (:node o))))
      :remove-node (do (must-exist! node) (edit-node tree node (constantly nil)))
      :set-opt (do (must-exist! node)
                   (edit-node tree node (fn [[k opts & cs]]
                                          (into [k (if (nil? value)
                                                     (dissoc opts key)
                                                     (assoc opts key value))]
                                                cs))))
      :rename-node (do (must-exist! node)
                       (edit-node tree node (fn [[k opts & cs]] (into [k (assoc opts :id to)] cs))))
      (throw (ex-info (str "unknown op " op " — add-node, remove-node, set-opt, rename-node")
                      {:op o})))))

(defn patch
  "Apply `ops` to the tree file at `path`, guarded by `expect-hash`.

  Three things make this safer than rewriting the file, and all three matter to
  an agent editing a tree it cannot see all of:

  - **The hash guard.** A patch names the tree it was written against, so an
    edit computed from a stale read is refused rather than applied to something
    else.
  - **The source is patched, not the expansion.** `:include` nodes are spliced
    at load time (RFC-002); patching the expanded tree would inline every
    included subtree into the file and silently unshare it.
  - **`check` runs before the write.** A patch that would make the tree invalid
    is refused with the problems it would have caused, so **the tree on disk is
    always runnable.**

  Returns `{:hash :applied}` or `{:error …}`; nothing is written on any error."
  [path expect-hash ops opts]
  (let [f (io/file path)]
    (if-not (.isFile f)
      {:error (str "no tree file " path)}
      (let [source (try (edn/read-string (slurp f))
                        (catch Throwable e {::unreadable (ex-message e)}))]
        (cond
          (::unreadable source) {:error (str "cannot read " path ": " (::unreadable source))}

          (and expect-hash (not= expect-hash (tree-hash source)))
          {:error (str "hash mismatch — the tree is " (tree-hash source)
                       ", not " expect-hash "; re-read it before patching")
           :hash (tree-hash source)}

          :else
          (let [patched (try (reduce apply-op source ops) (catch Throwable e {::failed (ex-message e)}))]
            (if (::failed patched)
              {:error (::failed patched)}
              (let [expanded (try {:t (bt/expand patched
                                                 (requiring-resolve 'brainyard.agent/resolve-tree))}
                                  (catch Throwable e {:error (ex-message e)}))
                    problems (if (:error expanded)
                               [{:node :tree :problem (:error expanded)}]
                               (check (:t expanded) opts))]
                (if (seq problems)
                  {:error "the patch would make this tree invalid; nothing was written"
                   :problems (vec problems)}
                  (do (spit f (with-out-str (pp/pprint patched)))
                      {:hash (tree-hash patched) :applied (count ops)}))))))))))

;; --- commands ---------------------------------------------------------------------

(defn ctx-opts
  "Check options taken from a live ctx, so a tree is checked against the
  capabilities it would actually run with: the registry that would resolve its
  leaves, the tools bound this turn, the MERGED config, and the real zones.

  Every key a tree can name lives in one schema (RFC-013), so a loop is checked
  against the same keys wherever the check is run from."
  [ctx]
  {:registry (:registry ctx)
   :tools (some-> (:tools ctx) deref)
   :predictor-roots (predictor/default-roots
                     (or (:project-dir (:config ctx)) (:working-dir (:config ctx)) ".")
                     "predictors")
   :config-keys (set (keys (:config ctx)))
   :zones (set (keys (:zones ctx)))
   ;; a turn is handed its question before the tree runs (agent/ask), and its
   ;; caller reads these back when it finishes (agent/turn-result)
   :initial #{:question}
   :exported #{:answer :terminated-by :goal-achieved :next-user-prompt
               :iteration-count :trajectory-iterations :usage}})

(defn commands
  "`flow$check` — the build/check/run surface's first verb (RFC-022)."
  [config]
  (when (:flows? config true)
    [{:name "flow$check" :kind :command
      :description (str "Check a behavior-tree loop WITHOUT running it, and report every problem at once:"
                        " unregistered leaf fns, predictors and tools that do not exist or whose :in/:out"
                        " name fields they do not have, unknown config keys, zones and hook events,"
                        " duplicate node ids, and an unbounded :repeat. Use it after editing a loop and"
                        " before running one — the errors it finds are mostly SILENT at run time"
                        " (a mistyped config key reads nil, a mistyped zone sends no text).")
      :input-schema [:map [:tree [:string {:desc "The loop: a bundled loop's name (react, plan-act-eval), a resource path, or a file path of the loop EDN."}]]]
      :output-schema [:map {:desc ":nodes and :hash are absent when the tree cannot be loaded; :hash is what flow$patch guards against"}
                [:ok :boolean]
                [:problems [:vector [:map [:node :any] [:problem :string]]]]
                [:nodes {:optional true} :int]
                [:hash {:optional true} :string]]
      :effects #{:fs/read}
      :fn (fn [{:keys [tree]} ctx]
            (let [{:keys [tree problems hash]} (check-tree-name tree (ctx-opts ctx))]
              (cond-> {:ok (empty? problems) :problems (vec problems)}
                tree (assoc :nodes (count (bt/nodes tree)) :hash hash))))}

     {:name "flow$format" :kind :command
      :description (str "The behavior-tree format: the node kinds, how a leaf names what it runs,"
                        " how the blackboard is fed, and a worked example. READ THIS BEFORE WRITING"
                        " OR EDITING A LOOP — the format is not guessable, and flow$check can tell"
                        " you what is wrong with a tree but not what a node is.")
      :input-schema [:map]
      :output-schema [:map [:format :string]]
      :effects #{}
      :fn (fn [_ _] {:format (slurp (io/resource "brainyard/prompts/loop-format.md"))})}

     {:name "flow$status" :kind :command
      :description (str "What is implemented in a loop and what is not: every leaf, what implements it,"
                        " and whether it is ready, missing (it names a tool or predictor that does not"
                        " exist) or failing. Use it to decide what to work on next, and to know when a"
                        " workflow is finished — nothing missing, nothing failing.")
      :input-schema [:map [:tree [:string {:desc "The loop: a bundled loop's name (react, plan-act-eval), a resource path, or a file path of the loop EDN."}]]]
      :output-schema [:or
                [:map [:nodes :int] [:ready :int] [:missing :int] [:failing :int]
                 [:detail [:vector [:map
                                    [:node :any]
                                    [:kind [:enum :tool :predictor :fn]]
                                    [:implements :any]
                                    [:status [:enum "ready" "missing" "failing"]]
                                    [:problems {:optional true} [:vector :string]]]]]]
                [:map [:error :string]]]
      :effects #{:fs/read}
      :fn (fn [{:keys [tree]} ctx]
            (let [opts (ctx-opts ctx)
                  {:keys [tree problems]} (check-tree-name tree opts)]
              (if-not tree {:error (:problem (first problems))} (status tree opts))))}

     {:name "flow$brief" :kind :command
      :description (str "Everything needed to implement ONE node, and nothing else: what it must read,"
                        " which nodes supply each key, what it must write, and which nodes downstream"
                        " read each. Read a brief before writing a node's tool or predictor — the"
                        " contract comes from the node's neighbours, so satisfying it is enough.")
      :input-schema [:map [:tree :string]
                          [:node [:string {:desc "The node's :id, e.g. react/think-act."}]]]
      :output-schema [:or {:desc "a key the caller reads when the loop ends is :exported, not :unused"}
                [:map
                 [:node :keyword]
                 [:kind [:enum :tool :predictor :fn]]
                 [:implements :any]
                 [:status [:enum "ready" "missing" "failing"]]
                 [:problems [:vector :string]]
                 [:reads [:vector [:map [:key :keyword] [:from [:vector :string]] [:guaranteed :boolean]]]]
                 [:writes [:vector [:map [:key :any] [:read-by [:vector :any]] [:unused :boolean]
                                    [:exported {:optional true} :boolean]]]]]
                [:map [:error :string]]]
      :effects #{:fs/read}
      :fn (fn [{:keys [tree node]} ctx]
            (let [opts (ctx-opts ctx)
                  {:keys [tree problems]} (check-tree-name tree opts)]
              (if-not tree
                {:error (:problem (first problems))}
                (brief tree (keyword (str/replace (str node) #"^:" "")) opts))))}

     {:name "flow$probe" :kind :command
      :description (str "Run ONE node against a blackboard you supply and report what it wrote."
                        " This is how a node is debugged without running the whole loop to reach it:"
                        " seed only the keys its brief says it reads, and check what comes back"
                        " against what the brief says it writes.")
      :input-schema [:map [:tree :string]
                          [:node [:string {:desc "The node's :id."}]]
                          [:memory {:optional true} [:map {:desc "Blackboard keys to seed, as a map."}]]]
      :output-schema [:or {:desc ":wrote is every blackboard key the node changed, with its new value"}
                [:map [:node :keyword] [:status [:enum "success" "failure"]]
                 [:wrote [:map-of :any :any]] [:elapsed-ms :int]]
                [:map [:node {:optional true} :keyword] [:error :string]]]
      :effects #{:fs/read :fs/write :net/http}
      :fn (fn [{:keys [tree node memory]} ctx]
            (let [opts (ctx-opts ctx)
                  {:keys [tree problems]} (check-tree-name tree opts)]
              (if-not tree
                {:error (:problem (first problems))}
                (probe tree (keyword (str/replace (str node) #"^:" ""))
                       (when (map? memory) (update-keys memory #(if (keyword? %) % (keyword (str %)))))
                       ctx))))}

     {:name "flow$patch" :kind :command
      :description (str "Edit a loop STRUCTURALLY instead of rewriting its file. Give the hash you"
                        " read (flow$check reports it) and a list of ops; the patch is refused if the"
                        " tree changed since, if an op names a node that is not there, or if the result"
                        " would not check — so the tree on disk always runs. Ops: add-node (:parent,"
                        " :after, :node), remove-node (:node), set-opt (:node :key :value, nil removes),"
                        " rename-node (:node :to).")
      :input-schema [:map [:path [:string {:desc "File path of the loop EDN (not a bundled resource)."}]]
                          [:expect-hash {:optional true} [:string {:desc "The hash you read; omit to skip the guard."}]]
                          [:ops [:vector {:desc "Ops to apply in order."} :map]]]
      :output-schema [:or {:desc "nothing is written on an error; a hash mismatch reports the tree's current :hash"}
                [:map [:hash :string] [:applied :int]]
                [:map [:error :string]
                 [:hash {:optional true} :string]
                 [:problems {:optional true} [:vector [:map [:node :any] [:problem :string]]]]]]
      :effects #{:fs/read :fs/write}
      :fn (fn [{:keys [path expect-hash ops]} ctx]
            (if-not (sequential? ops)
              {:error "ops must be a list"}
              (patch path (when-not (str/blank? (str expect-hash)) expect-hash)
                     (mapv #(update % :op (fn [o] (keyword (str/replace (str o) #"^:" "")))) ops)
                     (ctx-opts ctx))))}]))
