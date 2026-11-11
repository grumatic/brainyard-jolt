(ns brainyard.hookbox
  "Hooks as data (RFC-011), the way tools are (RFC-010).

  Built-in hooks are specs in resources/brainyard/hooks (listed by
  index.edn): {:id :event :priority :doc :impl :opts}, :impl naming a kernel
  fn in `impls`. User hooks are brainyard's `common/user_hooks.clj`:
  `<working-dir>/.brainyard/hooks/<id>.edn` holds {:id :event :match
  :priority :doc} and `<id>.clj` the body, laid out (`tl/format-body`) — here a Tool Lisp
  `(fn [event] ...)` instead of SCI source, so a hook body is read, validated
  against the tool registry and interpreted like a user tool's. A user hook
  on a gated event returns a decision — brainyard's v1 refuses gated user
  hooks; an interpreted, budgeted body makes it safe here (divergence
  `user-hooks-may-gate`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [brainyard.events :as events]
            [clojure.walk]
            [brainyard.effect :as fx]
            [brainyard.hooks :as hooks]
            [brainyard.literate :as literate]
            [brainyard.permission :as permission]
            [brainyard.tasks :as tasks]
            [brainyard.tool-lisp :as tl]
            [brainyard.tools :as tools]))

;; ============================================================================
;; Built-in hooks
;; ============================================================================

(defn- iter-tool-calls
  "An iteration record's tool calls [{:tool-name :tool-args :tool-result}]."
  [record]
  (when (= "tool" (:channel record)) (seq (:tool-results record))))

(defn redundant-tool-call-guard
  "brainyard's `redundant-tool-call-decision` (loop_guard_hook.clj). Over the
  last `:window` (2) iteration records with tool calls, at hook depth 0:
  1. strict — the same (tool-name, args) in each → block;
  2. idempotent — a tool in `:idempotent-tools` called at least 2 times, any
     args → block.
  The block's :answer carries the latest tool result, so the turn ends with it."
  [{:keys [tool-name args depth]} ctx {:keys [window idempotent-tools]}]
  (when (zero? (or depth 0))
    (let [window (or window 2)
          priors (some->> (:st-memory ctx) deref :iterations (filter iter-tool-calls) (take-last window) vec)
          has-call? (fn [r] (some #(and (= tool-name (:tool-name %))
                                        (= args (tools/normalize-args (:tool-args %))))
                                  (iter-tool-calls r)))
          uses (fn [] (reduce + (map (fn [r] (count (filter #(= tool-name (str (:tool-name %))) (iter-tool-calls r))))
                                     priors)))
          answer (fn [prefix] (str prefix (or (some-> (iter-tool-calls (last priors)) last :tool-result)
                                              "(no prior result captured)")
                                   "\n```"))]
      (cond
        (and (= window (count priors)) (every? has-call? priors))
        {:result :block
         :reason (str "Same tool call issued in each of the last " window " iterations.")
         :answer (answer "*(Loop guard: detected 3 consecutive identical tool calls. Stopping early. Latest tool result:)*\n\n```\n")}

        (and (= window (count priors)) (contains? (set idempotent-tools) tool-name) (>= (uses) 2))
        {:result :block
         :reason (str "Idempotent tool '" tool-name "' already called " (uses) " time(s) in the last " window
                      " iterations. Reuse the prior result from iteration history instead of re-fetching.")
         :answer (answer "*(Loop guard: an idempotent read-only tool was called repeatedly with varying args. Stopping early to break the loop. Latest tool result:)*\n\n```\n")}))))

(defn task-poll-park
  "brainyard's `deflect-decision` / auto-park (`common/auto_notify.clj`), as a
  built-in hook (RFC-019, `byj-gly.47`).

  A model that has started a background task tends to poll it. Each poll costs
  an iteration and tells it what it already knows, and the turn's ceiling is
  spent watching rather than working. After `:auto-park-after-polls` polls of
  the SAME still-running task in one turn, the call is blocked and the turn
  parks: a wakeup is armed, and the session resumes it with a fresh turn when
  the task lands (`tasks/ready-wakeup!`).

  The first poll is always allowed, so the model sees real liveness. A task
  that has finished is never deflected — the poll goes through and the model
  gets its result, which is the escape hatch that keeps a park from ever hiding
  an answer. Where nothing can resume a parked turn — a one-shot `ask`, or a
  subagent — this does nothing at all, because parking a turn nothing will wake
  is worse than polling."
  [{:keys [tool-name args depth]} ctx {:keys [poll-tools]}]
  (let [config (:config ctx)
        tid (some-> (or (:task-id args) (get args "task-id")) str not-empty)
        reg (:task-registry ctx)
        ;; the count is per TURN, so it lives in the turn's memory. A call made
        ;; outside a turn has none, and is not a turn to park.
        st (:st-memory ctx)]
    (when (and (zero? (or depth 0))
               (not (false? (:task-notify? config)))
               (contains? (set poll-tools) (str tool-name))
               tid reg st
               ;; only a still-running task is worth not polling
               (= :running (:status (get @reg tid))))
      (let [n (-> (swap! st update-in [:task-polls tid] (fnil inc 0))
                  (get-in [:task-polls tid]))
            park-at (max 1 (:auto-park-after-polls config 2))]
        (when (>= n park-at)
          ;; park through the machinery task$wakeup already uses, and only if it
          ;; takes — `park!` refuses a run with nothing to resume into
          (when (tasks/park! ctx {:task-id tid
                                  :note (str "auto-parked after " n " poll(s) of " tid)})
            {:result :block
             :reason (str "auto-parked after " n " poll(s) of " tid)
             :answer (str "Parked — " tid " is still running. I stopped polling it:"
                          " this turn ends here, and I will be given a fresh turn"
                          " when the task finishes.")}))))))

(def impls
  "Kernel fns built-in hook specs name by :impl."
  {:brainyard.hookbox/redundant-tool-call-guard redundant-tool-call-guard
   :brainyard.hookbox/task-poll-park task-poll-park
   :brainyard.permission/deny-gate permission/deny-gate
   :brainyard.permission/permission-gate permission/permission-gate
   :brainyard.permission/path-gate permission/path-gate
   :brainyard.permission/command-gate permission/command-gate
   :brainyard.literate/tangled-file-guard literate/tangled-file-guard})

(defn load-builtin
  "A built-in hook entry from resources/brainyard/hooks/<id>.edn."
  [id]
  (let [spec (edn/read-string (slurp (or (io/resource (str "brainyard/hooks/" id ".edn"))
                                         (throw (ex-info (str "no hook spec resource for " id) {:id id})))))
        f (or (get impls (:impl spec))
              (throw (ex-info (str "hook " id " names unknown :impl " (:impl spec)) {:spec spec})))
        opts (:opts spec)]
    (assoc spec :source :builtin :handler (fn [payload ctx] (f payload ctx opts)))))

(def builtin-hooks
  "The built-in hook entries, in index.edn order."
  (mapv load-builtin (edn/read-string (slurp (io/resource "brainyard/hooks/index.edn")))))

;; ============================================================================
;; User hooks
;; ============================================================================

(def id-re #"[a-z][a-z0-9-]*")

(def agent-id
  "The one agent this port runs — what `:agent-id` and `:defagent-type`
  match against until there are subagents (byj-gly.16)."
  "react-agent")

(defn hooks-dir [working-dir]
  (io/file (or working-dir ".") ".brainyard" "hooks"))

(defn- coerce-event [e]
  (cond (keyword? e) e
        (string? e) (let [s (str/replace (str/trim e) #"^:" "")] (when-not (str/blank? s) (keyword s)))
        :else nil))

(defn- coerce-match [m]
  (cond (string? m) (try (edn/read-string m) (catch Throwable _ ::unreadable))
        (map? m) (into {} (map (fn [[k v]] [(keyword (name k)) v])) m)
        :else m))

(defn build-match
  "brainyard's `build-match`: the declarative scope map → a predicate over the
  sanitized event, or a problem string."
  [m]
  (let [m (coerce-match m)]
    (cond
      (= ::unreadable m) ":match is not readable EDN"
      (or (nil? m) (and (map? m) (empty? m)))
      ":match is required (declarative scope map); use {:global true} for an unscoped hook"
      (not (map? m)) ":match must be a map"
      (true? (:global m)) (constantly true)
      (not (some #(contains? m %) [:defagent-type :agent-id :tool-name]))
      ":match must scope by :defagent-type, :agent-id, or :tool-name, or be {:global true}"
      :else (fn [event]
              (and (or (not (contains? m :defagent-type)) (= (str (:defagent-type m)) (:defagent-type event)))
                   (or (not (contains? m :agent-id)) (= (str (:agent-id m)) (:agent-id event)))
                   (or (not (contains? m :tool-name)) (= (str (:tool-name m)) (str (:tool-name event)))))))))

(defn sanitize
  "The event a user hook body receives: the payload plus :event-key,
  :agent-id and :defagent-type (brainyard's sanitized event)."
  [event payload]
  (assoc payload :event-key event :agent-id agent-id :defagent-type agent-id))

(defn- run-body [form event tool-reg ctx]
  (tl/run form event
          (fn [name args]
            (if-let [t (get @tool-reg name)]
              (tools/call-tool t args ctx)
              (throw (ex-info (str "no tool named " name) {:kind :missing-tool}))))
          (get-in ctx [:config :tool-lisp-budget])))

(defn- coerce-decision
  "A gated hook body's return value as a decision: nil allows; :result may be
  a keyword or a string (\"block\"). Anything else is passed on for
  fire-decision! to report as malformed."
  [d]
  (cond
    (nil? d) nil
    (and (map? d) (string? (:result d))) (update d :result #(keyword (str/replace % #"^:" "")))
    :else d))

(def on-error-values #{:allow :block})

(defn compile-hook
  "A user hook record → {:entry entry} or {:problems [...]}, the body validated
  against the tools in `tool-reg`. On a gated event the body's return value is
  the hook's decision; on any other it is ignored. `:on-error` says what a
  gated hook's failing body decides: :allow (default — fail open, as
  brainyard's gates do) or :block (fail closed)."
  [tool-reg {:keys [id event match body priority doc on-error]}]
  (let [ev (coerce-event event)
        matcher (build-match match)
        form (when (string? body)
               (try (tl/read-body body) (catch Throwable e {::problems (:problems (ex-data e) [(ex-message e)])})))
        problems (cond-> []
                   (not (and (string? id) (re-matches id-re id))) (conj "id must be lowercase-kebab, e.g. bash-command-log")
                   (nil? ev) (conj ":event is required")
                   (and ev (not (hooks/known-event? ev))) (conj (str ev " is not a known event — hook$events lists them"))
                   (and on-error (not (contains? on-error-values (coerce-event on-error))))
                   (conj ":on-error must be allow or block")
                   (string? matcher) (conj matcher)
                   (not (string? body)) (conj ":body must be a string holding (fn [event] ...)")
                   (::problems form) (into (::problems form))
                   (and priority (not (integer? priority))) (conj ":priority must be an integer"))
        ;; every problem at once: the body is checked whatever else is wrong
        problems (cond-> problems
                   (and form (not (::problems form)))
                   (into (tl/validate form (fn [sym] (get @tool-reg (str sym))))))]
    (if (seq problems)
      {:problems (vec problems)}
      (let [gated? (hooks/gated? ev)
            on-error (or (coerce-event on-error) :allow)]
        {:entry (cond-> {:id id :event ev :priority (or priority 0) :doc doc :source :user-hook
                         :match-spec (coerce-match match) :body body :form form
                         :match (fn [payload] (matcher (sanitize ev payload)))
                         :handler
                         (fn [payload ctx]
                           ;; brainyard's guards: not from inside another hook's
                           ;; handler, and not when user hooks are switched off
                           (when (and (<= hooks/*depth* 1)
                                      (get-in ctx [:config :enable-user-hooks] true))
                             (if-not gated?
                               (do (run-body form (sanitize ev payload) tool-reg ctx) nil)
                               (try
                                 (coerce-decision (run-body form (sanitize ev payload) tool-reg ctx))
                                 (catch Throwable e
                                   (if (or (fx/cancelled? e) (= :allow on-error))
                                     (throw e) ; fail open: the bus reports and skips it
                                     (do (events/emit! ctx :hooks/handler-failed
                                                       {:event ev :id id :source :user-hook
                                                        :error (ex-message e)})
                                         {:result :block
                                          :reason (str "hook " id " failed: " (ex-message e))})))))))}
                  (not= :allow on-error) (assoc :on-error-policy on-error))}))))

(defn- persist! [dir {:keys [id event match priority doc body on-error]}]
  (.mkdirs dir)
  (spit (io/file dir (str id ".clj")) (tl/format-body body))
  (spit (io/file dir (str id ".edn"))
        (with-out-str (clojure.pprint/pprint (cond-> {:id id :event (coerce-event event)
                                                      :match (coerce-match match) :priority (or priority 0)}
                                               doc (assoc :doc doc)
                                               on-error (assoc :on-error (coerce-event on-error))))))
  (.getPath (io/file dir (str id ".edn"))))

(defn- read-record [^java.io.File f]
  (try
    (let [m (edn/read-string (slurp f))
          sidecar (io/file (.getParentFile f) (str/replace (.getName f) #"\.edn$" ".clj"))]
      (cond-> (assoc m :file (.getPath f))
        (and (nil? (:body m)) (.isFile sidecar)) (assoc :body (slurp sidecar))))
    (catch Throwable e
      {:file (.getPath f) ::unreadable (ex-message e)})))

(defn load-user-hooks!
  "Register every user hook under `dir` into `hook-reg`, in file-name order (a
  duplicate :id — the later file wins). Returns {:loaded [ids] :skipped
  [{:file :problems}]}; a bad file never costs the others."
  [hook-reg tool-reg dir]
  (let [files (when (.isDirectory (io/file dir))
                (sort-by #(.getName %) (filter #(str/ends-with? (.getName %) ".edn") (.listFiles (io/file dir)))))]
    (reduce (fn [acc f]
              (let [r (read-record f)]
                (if (::unreadable r)
                  (update acc :skipped conj {:file (:file r) :problems [(str "unreadable: " (::unreadable r))]})
                  (let [{:keys [entry problems]} (compile-hook tool-reg r)]
                    (if problems
                      (update acc :skipped conj {:file (:file r) :problems problems})
                      (do (hooks/register! hook-reg entry)
                          (update acc :loaded conj (:id entry))))))))
            {:loaded [] :skipped []}
            files)))

;; ============================================================================
;; hook$* — brainyard's hook-agent$* commands, renamed
;; ============================================================================

(defn- user-hooks [hook-reg]
  (sort-by :id (filter #(= :user-hook (:source %)) (hooks/entries hook-reg))))

(defn- find-user-hook [hook-reg id]
  (first (filter #(= id (:id %)) (user-hooks hook-reg))))

(def ^:private definition-schema
  [:map
   [:id [:string {:desc "lowercase-kebab hook id, e.g. bash-command-log"}]]
   [:event [:string {:desc "event from hook$events, e.g. agent.tool-use/post"}]]
   [:match [:map {:desc "scope: {\"tool-name\": \"bash\"}, {\"agent-id\": \"react-agent\"} or {\"global\": true}"}]]
   [:body [:string {:desc "Tool Lisp: (fn [event] ...)"}]]
   [:doc {:optional true} [:string {:desc "one-line description"}]]
   [:priority {:optional true} [:int {:desc "higher runs first; default 0 (built-in guards are 100)"}]]
   [:on-error {:optional true} [:string {:desc "gated events only: what a failing body decides — allow (default) or block"}]]])

(defn authoring-tools
  "The hook$* commands over `hook-reg`, validating bodies against `tool-reg`
  and persisting under `dir`."
  [hook-reg tool-reg dir]
  [{:name "hook$events" :kind :command
    :description "List the agent events a hook can observe, with their payload keys."
    :input-schema [:map]
    :output-schema [:map {:desc "a hook on a gated event returns a decision"}
              [:events [:vector [:map [:event :string] [:payload-keys [:vector :keyword]]
                                 [:gated? :boolean] [:doc :string]]]]]
    :effects #{}
    :fn (fn [_ _]
          {:events (vec (for [[ev {:keys [keys gates? doc]}] (sort-by (comp str key) hooks/event-catalog)]
                          {:event (subs (str ev) 1) :payload-keys (vec (sort keys))
                           :gated? (boolean gates?) :doc doc}))})}
   {:name "hook$create" :kind :command
    :description (slurp (io/resource "brainyard/prompts/hook-create.md"))
    :input-schema definition-schema
    :output-schema [:or {:desc "the error lists every problem"}
              [:map [:id :string] [:event :string] [:persisted :string]]
              [:map [:error :string]]]
    :effects #{:fs/write :hooks/define}
    :fn (fn [args _]
          (let [{:keys [entry problems]} (compile-hook tool-reg args)]
            (if problems
              {:error (str "hook$create failed:\n- " (str/join "\n- " problems))}
              (let [path (persist! dir args)]
                (doseq [e (hooks/entries hook-reg) :when (and (= :user-hook (:source e)) (= (:id entry) (:id e)))]
                  (hooks/unregister! hook-reg (:event e) (:id e)))
                (hooks/register! hook-reg entry)
                {:id (:id entry) :event (subs (str (:event entry)) 1) :persisted path}))))}
   {:name "hook$validate" :kind :command
    :description "Check a user hook definition (same args as hook$create, all optional but body) without saving it; with `sample`, run the body on that sample event."
    :input-schema [:map [:body [:string {:desc "Tool Lisp: (fn [event] ...)"}]]
                        [:id {:optional true} :string]
                        [:event {:optional true} :string]
                        [:match {:optional true} :map]
                        [:sample {:optional true} [:map {:desc "a payload to run the body on"}]]]
    :output-schema [:map {:desc ":sample-result is what the body returned on the sample, or {:error string}"}
              [:valid :boolean] [:id-ok :boolean] [:event-ok :boolean] [:event-gated :boolean]
              [:match-ok :boolean] [:body-ok :boolean] [:collision :boolean]
              [:errors [:vector :string]]
              [:sample-result {:optional true} :any]]
    :effects #{:dynamic}
    :fn (fn [{:keys [id event match body sample] :as args} ctx]
          (let [ev (coerce-event event)
                {:keys [entry problems]} (compile-hook tool-reg (merge {:id (or id "x") :event (or event :agent.tool-use/post)
                                                                        :match (or match {:global true})}
                                                                       args))
                body-problems (when (string? body)
                                (try (some-> (tl/read-body body) (tl/validate (fn [s] (get @tool-reg (str s)))))
                                     (catch Throwable e (:problems (ex-data e) [(ex-message e)]))))]
            (cond-> {:valid (empty? problems)
                     :id-ok (boolean (and (string? id) (re-matches id-re id)))
                     :event-ok (boolean (and ev (hooks/known-event? ev)))
                     :event-gated (boolean (and ev (hooks/gated? ev)))
                     :match-ok (not (string? (build-match match)))
                     :body-ok (and (string? body) (empty? body-problems))
                     :collision (boolean (and id (find-user-hook hook-reg id)))
                     :errors (vec problems)}
              (and entry sample)
              (assoc :sample-result
                     (try (run-body (:form entry) (sanitize (:event entry) (clojure.walk/keywordize-keys sample))
                                    tool-reg ctx)
                          (catch Throwable e
                            (if (fx/cancelled? e) (throw e) {:error (str "Tool Lisp: " (ex-message e))})))))))}
   {:name "hook$list" :kind :command
    :description "List the registered hooks, built-in and user."
    :input-schema [:map]
    :output-schema [:map
              [:hooks [:vector [:map [:id :string] [:event :string] [:priority :int] [:source :keyword]
                                [:match {:optional true} [:map-of :keyword :any]]
                                [:doc {:optional true} :string]]]]]
    :effects #{}
    :fn (fn [_ _]
          {:hooks (vec (for [e (sort-by (juxt (comp str :event) (comp - :priority) :id) (hooks/entries hook-reg))]
                         (cond-> {:id (:id e) :event (subs (str (:event e)) 1) :priority (:priority e 0) :source (:source e)}
                           (:match-spec e) (assoc :match (:match-spec e))
                           (:doc e) (assoc :doc (:doc e)))))})}
   {:name "hook$read" :kind :command
    :description "Read a user hook's definition, including its Tool Lisp body."
    :input-schema [:map [:id [:string {:desc "hook id"}]]]
    :output-schema [:or
              [:map [:id :string] [:event :string] [:match [:map-of :keyword :any]] [:priority :int]
               [:body :string] [:doc {:optional true} :string] [:on-error {:optional true} :keyword]]
              [:map [:error :string]]]
    :effects #{}
    :fn (fn [{:keys [id]} _]
          (if-let [e (find-user-hook hook-reg id)]
            (cond-> {:id (:id e) :event (subs (str (:event e)) 1) :match (:match-spec e) :priority (:priority e) :body (:body e)}
              (:doc e) (assoc :doc (:doc e))
              (:on-error-policy e) (assoc :on-error (:on-error-policy e)))
            {:error (str "no user hook named \"" id "\"")}))}
   {:name "hook$delete" :kind :command
    :description "Delete a user hook: unregister it and remove its files."
    :input-schema [:map [:id [:string {:desc "hook id"}]]]
    :output-schema [:or [:map [:deleted :string]] [:map [:error :string]]]
    :effects #{:fs/write :hooks/define}
    :fn (fn [{:keys [id]} _]
          (if-let [e (find-user-hook hook-reg id)]
            (do (hooks/unregister! hook-reg (:event e) id)
                (doseq [ext [".edn" ".clj"]] (.delete (io/file dir (str id ext))))
                {:deleted id})
            {:error (str "no user hook named \"" id "\"")}))}])
