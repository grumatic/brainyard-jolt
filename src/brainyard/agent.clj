(ns brainyard.agent
  "brainyard's ReAct agent loop on jolt.

  The loop's SHAPE is resources/brainyard/loops/react.edn (a behavior tree as
  data); this namespace provides the behaviour each leaf names — the
  `registry` — and `ask`, which ticks the tree for one question. The LLM step
  is a declarative predictor the tree node names (`:predictor`), defined in
  resources/brainyard/predictors/ and tuned by params files — see
  brainyard.predictor.

  Ported from components/agent/.../common/coact_agent.clj with the code
  channel off (brainyard's `react-agent`). Per-turn state lives in an
  `st-memory` atom with the same key names as the original, and iteration
  history is printed as Clojure data into the `iterations:` input line, as
  the original does."
  (:require [clojure.edn :as edn]
            [brainyard.clock :as clock]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agents :as agents]
            [brainyard.analytics :as analytics]
            [brainyard.bt :as bt]
            [brainyard.config :as config]
            [brainyard.configbox :as configbox]
            [brainyard.context :as context]
            [brainyard.cost :as cost]
            [brainyard.discovery :as discovery]
            [brainyard.eventlog :as eventlog]
            [brainyard.events :as events]
            [brainyard.hookbox :as hookbox]
            [brainyard.history :as history]
            [brainyard.json :as json]
            [brainyard.schema :as schema]
            [brainyard.hooks :as hooks]
            [brainyard.llm :as llm]
            [brainyard.mcp :as mcp]
            [brainyard.memory :as memory]
            [brainyard.store :as store]
            [brainyard.flow :as flow]
            [brainyard.programs :as programs]
            [brainyard.todo :as todo]
            [brainyard.proposals :as proposals]
            [brainyard.channel :as channel]
            [brainyard.claude-agent :as claude-agent]
            [brainyard.codex-agent :as codex-agent]
            [brainyard.schedule :as schedule]
            [brainyard.init :as init]
            [brainyard.instructions :as instructions]
            [brainyard.skills :as skills]
            [brainyard.teams :as teams]
            [brainyard.share-tools :as share-tools]
            [brainyard.coach :as coach]
            [brainyard.emacs :as emacs]
            [brainyard.terminal :as terminal]
            [brainyard.tool-lisp :as tl]
            [brainyard.tasks :as tasks]
            [brainyard.effect :as fx]
            [ebb.core :as m]
            [brainyard.predictor :as predictor]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]
            [brainyard.trajectory :as trajectory]
            [brainyard.trajectory-export :as trajectory-export]))

(def react-instruction
  "You are a tool-only ReAct agent: answer using the `tool-calls` channel and a
final `answer` only. There is no code-blocks channel — never emit fenced code to
execute. To reach a tool beyond the bound set, discover it with `list-tools` /
`get-tool-info` and invoke it through `tool-calls`.")

(defn- local-now
  "Local wall-clock time with offset and weekday. jolt's java.time shim only
  knows UTC, so the OS gives the offset (provenance P-10) — once every few
  minutes, not once a turn (`brainyard.clock`, byj-gly.382)."
  []
  (clock/local-now true))

(def default-config
  "The schema's declared defaults (RFC-013). The keys and their docs live in
  `brainyard.config/config-items`; a run's effective config is that, layered with
  config.edn, the caller's opts and BY_* variables."
  config/defaults)

;; make-ctx hands `ask-task` to the agent roster (RFC-018): an agent tool runs
;; one of these for its child turn.
(declare ask-task)

(defn- load-resource [path]
  (or (some-> (io/resource path) slurp)
      (throw (ex-info (str "resource not found: " path) {:path path}))))

;; --- helpers -----------------------------------------------------------------

(defn- mem [ctx] @(:st-memory ctx))

(defn- emit!
  "Put an event on the bus (RFC-026). One named door, so the catalogue of what
  the kernel emits can be checked against the source."
  [ctx event data]
  (events/emit! ctx event data))

(defn- queue-notice [m notice]
  (update m :pending-notices (fnil conj []) notice))

(defn- cap [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…[truncated " (- (count s) n) " chars]") s)))

(defn- result->str [raw]
  (cond (string? raw) raw
        (nil? raw) ""
        :else (pr-str raw)))

;; --- actions -----------------------------------------------------------------

(defn ^{:reads #{} :writes #{:iterations :trajectory-iterations :started-at :task-polls :answer :terminated :terminated-by :iteration-count :tool-calls :goal-achieved :next-user-prompt :last-reasoning :last-channel :last-tool-results :consecutive-llm-failures :no-action-streak :pending-notices :usage :usage-calls :user-context
                   :refinements :critique :answer-decision}}
  init-action [ctx]
  (swap! (:st-memory ctx) merge
         {:iterations [] :trajectory-iterations []
          :answer "" :terminated false :terminated-by nil :iteration-count 0
          :tool-calls [] :goal-achieved false :next-user-prompt ""
          :last-reasoning nil :last-channel nil :last-tool-results []
          :consecutive-llm-failures 0 :no-action-streak 0
          ;; the answer gate's turn state (RFC-003, byj-gly.12). Seeded here so
          ;; a reader does not depend on a branch having run.
          :refinements 0 :critique nil :answer-decision nil
          ;; polls of a running task, per task, this turn (RFC-019). Reset here
          ;; so the auto-park threshold is per turn, as brainyard's is.
          :task-polls {}
          ;; when this turn began, so the trajectory can say how long it took
          ;; (RFC-025). Set here rather than measured by a caller: `ask` is not
          ;; the only thing that runs a turn.
          :started-at (System/currentTimeMillis)
          :pending-notices [] :usage {:input-tokens 0 :output-tokens 0 :llm-calls 0
                                      :cache-read-tokens 0 :cache-write-tokens 0}})
  (swap! (:st-memory ctx)
         #(assoc % :user-context
                 (str "## Turn\n\n"
                      (when-let [now (local-now)] (str "- Now: " now "\n"))
                      "- Turn: " (or (:turn-number %) 1)
                      ;; a subagent's view of what its caller was doing —
                      ;; after the turn block and before anything else, where
                      ;; brainyard puts it (RFC-018)
                      (when-let [t (history/parent-trail (:parent-trail %) (:config ctx))]
                        (str "\n\n" t))
                      ;; the memories the session found this question is about
                      ;; (byj-gly.335): their keys, in this volatile zone so the
                      ;; cached prefix is untouched
                      (when-let [h (not-empty (:memory-hint %))]
                        (str "\n\n" h))
                      ;; what the person is looking at, when the question
                      ;; said @here (byj-gly.453)
                      (when-let [e (not-empty (:editor-context %))]
                        (str "\n\n" e))
                      ;; how to coach, while the session coaches (RFC-067)
                      (when-let [c (not-empty (:coach-context %))]
                        (str "\n\n" c))))))

(defn ^{:reads #{:iteration-count} :writes #{:iteration-count :tool-calls :attempted-tool-calls :last-channel :last-error :last-reasoning :last-tool-results :repaired-iter}}
  inc-iter-action [ctx]
  (swap! (:st-memory ctx)
         #(-> % (update :iteration-count inc)
              (assoc :tool-calls [] :attempted-tool-calls [] :answer "" :last-reasoning nil :last-channel nil
                     :last-tool-results [] :repaired-iter nil :last-error nil)
              (dissoc :bt/error)))
  (emit! ctx :agent.iteration/start {:iteration (:iteration-count (mem ctx))}))

(defn ^{:reads #{:iterations} :writes #{:iterations :pending-notices}}
  harvest-tasks-action
  "Fold background work back into the turn that started it (RFC-019).

  Two deliveries, as brainyard's: a STILL RUNNING marker in the history is
  rewritten with the result it stood in for, so the model's own record stops
  lying; and anything that finished — or is still going — is queued as a notice
  for this iteration. Without it a detached task is only ever seen by a model
  that remembers to `task$wait`, and the marker's own advice is what it would
  have to remember."
  [{:keys [st-memory config] :as ctx}]
  (when-let [reg (:task-registry ctx)]
    (when (seq @reg)
      (let [harvest (tasks/harvest! reg (tasks/owner-of config))
            max-chars (:max-output-chars config 16000)]
        (swap! st-memory update :iterations tasks/resolve-markers reg max-chars)
        (when-let [text (tasks/notice harvest max-chars)]
          (swap! st-memory queue-notice text)
          (emit! ctx :agent.tasks/harvested
                 {:completed (mapv :id (:completed harvest))
                  :running (mapv :id (:running harvest))}))))))

(defn- zone-blocks
  "The system-prompt zones a node declares (:zones [...]), in its order, as
  [[title text] ...]. A zone's text comes from st-memory when the loop put it
  there this turn (history-context, user-context), else from the agent's
  stable zones in ctx (agent-core, session-context) — brainyard's :stable-keys."
  [{:keys [node zones] :as ctx}]
  (for [z (:zones node)]
    [(name z) (or (get (mem ctx) z) (get zones z))]))

(defn- node-predictor
  "The predictor a leaf's node names, loaded and compiled."
  [{:keys [node config]}]
  (let [id (or (:predictor node)
               (throw (ex-info (str "node " (:id node) " names no :predictor") {:node node})))]
    (predictor/load-predictor id (:predictor-roots config))))

(def max-usage-calls
  "How many per-call usage records a memory keeps. `/usage` shows the last few
  and the summary is a running total, so the history is a window, not a ledger
  — a long session must not grow a record per call forever."
  200)

(defn current-lm
  "The model a call runs on: the session's, if one has been chosen since it
  started, else the ambient one the run was built with.

  `:lm-box` is an atom a front end owns — `/model` writes it (RFC-004) — and it
  sits between the ambient LM and a node's own `:lm`, so a node that pins a
  model still wins (RFC-005's order). Nothing here decides WHICH model; it
  decides where to look."
  [{:keys [lm lm-box]}]
  (or (some-> lm-box deref) lm))


(defn llm-dir
  "Where a model that runs as a process (`claude -p`) is started: the
  session's project, else its working directory. Inheriting this process's
  cwd had Claude Code's own environment block name the directory brainyard
  was launched from, not the one `-C` named (byj-gly.338)."
  [config]
  (not-empty (str (or (:project-dir config) (:working-dir config)))))

(defn run-node-predictor
  "Run the node's predictor on inputs, counting the call and its tokens in
  st-memory :usage (a failed call is still a call), and keeping a record of the
  call itself in :usage-calls — what `/usage` prints a table of, and the only
  place the cache split and the prompt's shape survive the turn (RFC-025)."
  [{:keys [st-memory config params on-event] :as ctx} inputs]
  (let [p (node-predictor ctx)
        lm (current-lm ctx)
        prices (cost/table config)
        zones (vec (zone-blocks ctx))
        ;; where this prompt's tokens went, by zone: the port's answer to
        ;; brainyard's per-call system/signature/user split, in the vocabulary
        ;; the prompt is actually assembled from (RFC-015)
        zone-tokens (mapv (fn [[title text]] [title (context/estimate-tokens text)]) zones)
        record! (fn [{:keys [usage model provider elapsed-ms]} c]
                  (swap! st-memory update :usage-calls
                         (fn [calls]
                           (let [calls (conj (vec calls)
                                             {:predictor-id (:predictor/id p)
                                              :model (or model (:model lm))
                                              :provider (or provider (:provider lm))
                                              :elapsed-ms elapsed-ms
                                              :input-tokens (or (:input-tokens usage) 0)
                                              :output-tokens (or (:output-tokens usage) 0)
                                              :cache-read-tokens (or (get-in usage [:cache :read-tokens]) 0)
                                              :cache-write-tokens (or (get-in usage [:cache :write-tokens]) 0)
                                              :cost (:total-cost c)
                                              :zones zone-tokens})]
                             (if (> (count calls) max-usage-calls)
                               (subvec calls (- (count calls) max-usage-calls))
                               calls)))))
        add-usage (fn [{:keys [usage model provider] :as result}]
                    ;; priced per CALL, not per turn: a node may pin its own
                    ;; model and a tier may route to another, so a turn's tokens
                    ;; can belong to two price lists (RFC-025)
                    (let [c (cost/of prices usage (or provider (:provider lm)) (or model (:model lm)))]
                      (record! result c)
                      (swap! st-memory update :usage
                             #(-> %
                                  (update :input-tokens + (or (:input-tokens usage) 0))
                                  (update :output-tokens + (or (:output-tokens usage) 0))
                                  ;; the cache split, so a turn can be costed and
                                  ;; caching can be seen working (RFC-015)
                                  (update :cache-read-tokens + (or (get-in usage [:cache :read-tokens]) 0))
                                  (update :cache-write-tokens + (or (get-in usage [:cache :write-tokens]) 0))
                                  (cost/add c)))))]
    (swap! st-memory update-in [:usage :llm-calls] (fnil inc 0))
    (let [pre {:node-id (get-in ctx [:node :id]) :predictor-id (:predictor/id p) :inputs inputs}]
      (hooks/fire! ctx :agent.dspy-action/pre pre)
      (try
        (let [t0 (System/currentTimeMillis)
              result (predictor/run p inputs
                                    {:lm lm
                                     ;; each fragment the provider streams, as an
                                     ;; event like any other (RFC-006) — but
                                     ;; labelled with the reply field it belongs
                                     ;; to, because what a provider streams is
                                     ;; JSON and what a viewer shows is prose.
                                     ;; Labelling is all this does: which field
                                     ;; is worth showing, and when, is the
                                     ;; viewer's call (byj-gly.56).
                                     :on-chunk (when on-event
                                                 (let [fields (llm/field-streamer [:reasoning :answer])]
                                                   (fn [text]
                                                     (doseq [part (fields text)]
                                                       (events/emit! on-event :agent.llm/chunk
                                                                 (assoc part :predictor-id (:predictor/id p)))))))
                                     ;; a node may pin a model for its own step —
                                     ;; the explicit call site of RFC-005's order
                                     :lm-override (:lm (:node ctx))
                                     :lm-tiers (:agent-lm-tiers config)
                                     :env-fn (:env-fn ctx)
                                     ;; the same zones the record counted, so a
                                     ;; breakdown describes the prompt that was
                                     ;; actually sent rather than a later one
                                     :zones zones
                                     :params params
                                     :params-roots (:params-roots config)
                                     ;; where a CLI model runs: the session's
                                     ;; project, not this process's cwd (byj-gly.338)
                                     :dir (llm-dir config)
                                     :on-event on-event})]
          (add-usage (assoc result :elapsed-ms (- (System/currentTimeMillis) t0)))
          (hooks/fire! ctx :agent.dspy-action/post
                       (merge pre (select-keys result [:outputs :reasoning :usage])))
          result)
        (catch Throwable e
          (add-usage (ex-data e))
          (when-not (fx/cancelled? e)
            (hooks/fire! ctx :agent.dspy-action/post (assoc pre :error (ex-message e) :usage (:usage (ex-data e)))))
          (throw e))))))

(defn ^{:reads #{:question :iterations} :writes #{:answer :attempted-tool-calls :consecutive-llm-failures :goal-achieved :last-reasoning :tool-calls :usage :usage-calls}}
  think-act-action
  "The LLM step: run the node's predictor (react/think-act in the bundled
  tree) on the question and the printed iteration history, and write its
  outputs into st-memory. Throws on a failed or unusable call, which the
  llm-guard fallback hands to `repair-action`."
  [{:keys [st-memory] :as ctx}]
  (let [{:keys [question iterations]} @st-memory
        {:keys [outputs reasoning attempted-tool-calls]} (run-node-predictor ctx {:question question
                                                                                  :iterations (pr-str (context/model-view iterations))})
        ;; the model writes {name args}; the loop, its hooks and its records
        ;; keep brainyard's :tool-name/:tool-args (divergence
        ;; `a-tool-call-is-name-and-args`)
        outputs (update outputs :tool-calls
                        #(mapv (fn [{:keys [name args]}] {:tool-name name :tool-args (or args {})}) %))]
    (swap! st-memory merge
           (select-keys outputs [:tool-calls :answer :goal-achieved :next-user-prompt])
           {:last-reasoning reasoning :consecutive-llm-failures 0
            :attempted-tool-calls (vec attempted-tool-calls)})
    (emit! ctx :agent.think/reasoning {:reasoning reasoning})))

(declare step-start! step-end!)

(defn ^{:reads #{} :writes #{:usage :usage-calls}}
  predictor-run-action
  "Generic declarative LLM step for any tree:

    [:action {:fn :predictor/run :predictor \"user/summarize\"
              :in  {:text :answer}          ; predictor input ← st-memory key
              :out {:summary :summary}      ; st-memory key ← output (or :reasoning)
              :zones [:agent-core]}]"
  [{:keys [st-memory node] :as ctx}]
  (let [m @st-memory
        inputs (into {} (for [[field k] (:in node)
                              :let [v (get m k)]
                              :when (some? v)]
                          [field (if (or (string? v) (number? v)) v (pr-str v))]))
        ;; what the TUI draws as a call, in the turn or a subagent's view
        ;; (byj-gly.299): a workflow's judgements were otherwise invisible
        _ (step-start! ctx node :predictor (:predictor node) inputs)
        {:keys [outputs reasoning]} (try (run-node-predictor ctx inputs)
                                         (catch Throwable t
                                           (step-end! ctx node :predictor (:predictor node) nil (ex-message t))
                                           (throw t)))
        outputs (assoc outputs :reasoning reasoning)]
    (step-end! ctx node :predictor (:predictor node) outputs nil)
    (swap! st-memory into (for [[k field] (:out node)] [k (get outputs field)]))))

(def ^:private step-chars
  "How much of a workflow step's input, or of what it kept, its line shows."
  120)

(defn- step-text [v]
  (let [s (if (string? v) v (pr-str v))
        line (str/trim (or (first (remove str/blank? (str/split-lines s))) ""))
        more? (or (> (count line) step-chars) (not= (str/trim s) line))]
    (str (subs line 0 (min step-chars (count line))) (when more? "…"))))

(defn- step-args
  "A step's inputs as its call shows them: each one short line — a brief or a
  report would otherwise be the whole screen."
  [args]
  (into (sorted-map) (for [[k v] args
                           ;; an empty input says nothing: `args=""` was noise
                           :when (not (and (string? v) (str/blank? v)))]
                       [k (step-text v)])))

(defn- step-result
  "What a step kept, as its call's result: the keys its node writes (`:out`),
  each short."
  [node result]
  (str/join " · " (for [[k field] (:out node)
                        :let [v (get result field)]
                        :when (and (some? v) (not (and (string? v) (str/blank? v))))]
                    (str (name k) " " (step-text v)))))

(defn- step-start! [ctx node kind name args]
  (emit! ctx :agent.tree/step-start {:node (:id node) :kind kind :name name :args (step-args args)}))

(defn- step-end! [ctx node kind name result error]
  (emit! ctx :agent.tree/step-end (cond-> {:node (:id node) :kind kind :name name
                                           :result (if error (str error) (step-result node result))}
                                    error (assoc :error true))))

(defn account-tool-cost!
  "A tool that spent money outside the turn's own model calls says so with
  `:usage-cost-usd` in its result — claude$await, the delegation's spend
  since it last said (RFC-039) — and the turn's usage counts it, as a
  call's cost is counted (`cost/add`), so a share's cost cap, the eval store
  and the agent page see what the turn cost in all (byj-1wv.17): the
  hosting turn of a delegation carried no usage, and an agent share's cap
  counted its callers' messages at nothing. Anything else in a result is
  not a cost."
  [st-memory result]
  (when-let [c (and (map? result) (:usage-cost-usd result))]
    (when (and (number? c) (pos? c))
      (swap! st-memory update :usage cost/add {:total-cost (double c) :source :tool}))))

(defn ^{:reads #{} :writes #{}}
  tool-run-action
  "Generic tool step for any tree (RFC-022) — what `:predictor/run` is to a
  predictor, this is to a tool:

    [:action {:fn :tool/run :tool \"user/classify\"
              :in  {:text :question}          ; tool arg ← st-memory key
              :out {:severity :severity}      ; st-memory key ← result key
              :on-error :failure}]            ; :failure (default) | :success

  This is the leaf an agent can implement: a Tool Lisp body is data (RFC-010),
  so a workflow gains a step without a rebuild. It runs through `call-tool`, so
  it is gated, arg-checked and hooked exactly as a model's own call is
  (RFC-011, RFC-012) — the tree is not a way around the permission stack.

  Unlike `:predictor/run`, values are passed through as they are: a predictor's
  input becomes prompt text and must be a string, while a tool takes data."
  [{:keys [st-memory tools node] :as ctx}]
  (let [m @st-memory
        tool (get @tools (:tool node))
        args (into {} (for [[arg k] (:in node)
                            :let [v (get m k)]
                            :when (some? v)]
                        [arg v]))]
    (if-not tool
      (do (emit! ctx :agent.tree/tool-missing {:node (:id node) :tool (:tool node)})
          :failure)
      (let [_ (step-start! ctx node :tool (:tool node) args)
            result (tools/call-tool tool args ctx)
            _ (account-tool-cost! st-memory result)
            ;; a call can go wrong three ways, and only one of them carries
            ;; :error-message. A gate-blocked call returns {:hook-blocked true}
            ;; with no error at all, and reading that as success would make the
            ;; tree a way PAST the permission stack (RFC-012).
            failed (or (:error-message result) (:error result)
                       (when (:hook-blocked result) (or (:reason result) "blocked")))]
        (step-end! ctx node :tool (:tool node) result failed)
        (swap! st-memory into (for [[k field] (:out node)] [k (get result field)]))
        ;; the call goes in the turn's record as a model's own does: a tree
        ;; that works through commands (agent$init, agent$claude) left an
        ;; answer and no calls, and a judge read an agent that did nothing
        ;; (byj-gly.322). The record only — `:iterations`, what a prompt
        ;; shows the model, is not this leaf's to grow — and the engine's
        ;; (`bt/record-keys`), not the node's contract
        (swap! st-memory update :trajectory-iterations
               (fn [its] (conj (vec its)
                               {:iteration (inc (count its)) :channel "tool"
                                :tool-results [{:tool-name (:tool node) :tool-args args
                                                :tool-result (result->str result)
                                                :tool-error? (boolean failed)}]})))
        (if failed
          (do (emit! ctx :agent.tree/tool-failed {:node (:id node) :tool (:tool node) :error failed})
              (if (= :success (:on-error node)) :success :failure))
          :success)))))

(defn ^{:reads #{} :writes #{}}
  mem-true?-condition
  "Generic condition on the blackboard:

    [:condition {:fn :mem/true? :key :todo-exhausted}]
    [:repeat {:until :mem/true? :key :todo-exhausted} …]

  A tree that composes tools and agents has to branch on what they WROTE, and
  every other condition here names one fixed key. This is the data-parameterized
  one, so a workflow does not need a compiled fn per loop it wants to end."
  [{:keys [node] :as ctx}]
  (boolean (get (mem ctx) (:key node))))

(defn ^{:reads #{} :writes #{}}
  mem-matches?-condition
  "Condition on the TEXT a step left behind:

    [:condition {:fn :mem/match? :key :plan :pattern \"^Saved plan:\"}]

  A workflow that composes AGENTS has to branch on what they answered, and an
  answer is prose — there is no verdict field to read. brainyard's specialists
  answer with stable prefixes for exactly this reason (`Saved plan:`, `Need:`,
  `Refused:`), and its own chain reads them out of dossier frontmatter; here the
  tree reads the answer directly (RFC-023).

  The pattern is the node's, so a tree branches on a contract an agent's
  instruction states, without a fn compiled per contract."
  [{:keys [node] :as ctx}]
  (boolean (re-find (re-pattern (str (:pattern node)))
                    (str (get (mem ctx) (:key node))))))

(defn ^{:reads #{} :writes #{}}
  mem-set-action
  "Seed blackboard keys from literals:

    [:action {:fn :mem/set :set {:step \"nothing yet\" :slug :config/todo-slug}}]

  A workflow needs somewhere for a key to come from before the node that fills
  it properly — a `:repeat` body may run zero times, so what it writes is not
  guaranteed afterwards, and `flow/check` says so (RFC-022). This is how the
  tree answers that without a compiled fn per loop."
  [{:keys [st-memory node config]}]
  ;; a `:config/…` value resolves from the run's config, as a node opt does
  ;; (`bt/resolve-opt`) — that is how a tree learns something the caller chose,
  ;; such as which checklist to resume (RFC-023)
  (swap! st-memory into (for [[k v] (:set node)]
                          [k (if (and (keyword? v) (= "config" (namespace v)))
                               (get config (keyword (name v)))
                               v)]))
  :success)

(defn ^{:reads #{:refinements :goal-achieved} :writes #{:answer-decision}}
  answer-decision-action
  "Which way the answer gate goes — brainyard's `capture-answer-meta`:

      round >= :max-refinements  → :accept       (covers the 0 default)
      goal-achieved false        → :refine-self  (the model said so; no eval call)
      otherwise                  → :needs-eval   (confirm it against the evidence)

  The middle branch is the cheap one and the reason the gate is a hybrid rather
  than an evaluation: when the model has already reported it is not done, buying
  a second opinion to hear the same thing is waste."
  [{:keys [st-memory config]}]
  (let [m @st-memory
        budget (:max-refinements config 0)
        ;; brainyard's order exactly. Only a POSITIVE self-report earns an
        ;; evaluation: an omitted `goal-achieved` coerces to false and is
        ;; treated as the model saying it is not done, which is what it is.
        decision (cond
                   (>= (:refinements m 0) budget) :accept
                   (true? (:goal-achieved m)) :needs-eval
                   :else :refine-self)]
    (swap! st-memory assoc :answer-decision decision)
    :success))

(defn ^{:reads #{:answer-decision} :writes #{}}
  accept-answer?
  "The gate is off, or its budget is spent. A gate that can refuse forever is a
  turn that never answers."
  [ctx]
  (= :accept (:answer-decision (mem ctx))))

(defn ^{:reads #{:answer-decision} :writes #{}}
  refine-self?
  [ctx]
  (= :refine-self (:answer-decision (mem ctx))))

(defn- evidence
  "What the turn actually saw — the tool results it collected. brainyard grounds
  its check on sandbox output; here the tool channel is the only ground truth
  there is (divergence `tool-only-signature`)."
  [m n]
  (->> (:iterations m)
       (mapcat :tool-results)
       (map #(str (:tool-name %) " → " (:tool-result %)))
       (str/join "\n")
       (#(cap % n))))

(defn ^{:reads #{:answer :question :iterations :refinements} :writes #{:critique :usage :usage-calls}}
  evaluate-answer-condition
  "Condition `:react/evaluate-answer` — does the answer hold up against what the
  turn actually saw? brainyard's `EvaluateAnswer`, as a declarative predictor.

  It is a GROUNDING check before it is a quality check: the question is whether
  the answer invents data no tool returned, not whether it could be better
  written.

  **Accepts when it cannot judge.** With no tool results there is no ground
  truth to check against, so a general-knowledge answer is never evaluated — and
  a failed evaluation accepts too. Both are brainyard's: a broken critic must
  not cost a turn its answer."
  [{:keys [st-memory config] :as ctx}]
  (let [m @st-memory
        ev (evidence m (:max-output-chars config 2000))]
    (if (str/blank? ev)
      true
      (let [{:keys [outputs]}
            (try (run-node-predictor ctx {:question (str (:question m))
                                          :answer (str (:answer m))
                                          :evidence ev
                                          :eval-context (str (:agent-instruction config))})
                 (catch Throwable _ {:outputs {:verdict "COMPLETE"}}))
            verdict (str/upper-case (str (:verdict outputs)))
            ;; anything unrecognised is an accept, as brainyard's is
            refused? (contains? #{"HALLUCINATED" "INCOMPLETE"} verdict)]
        (when refused?
          (swap! st-memory assoc :critique
                 (if (= "HALLUCINATED" verdict)
                   (str "Your previous answer contained data no tool returned. " (:detail outputs)
                        " Use ONLY what the tool results actually show — run them again and check before answering.")
                   (str "Your previous answer was incomplete. " (:detail outputs)
                        " Close the gap, then answer again."))))
        (emit! ctx :agent.answer/evaluated {:verdict verdict :detail (:detail outputs)})
        (not refused?)))))

(defn ^{:reads #{:critique :next-user-prompt} :writes #{:answer :goal-achieved :critique :refinements :pending-notices}}
  refine-action
  "The answer was refused: hand the reason back and let the loop try again.

  Clearing `:answer` is what makes the refusal real — the router is a
  `:fallback` on a non-blank answer, so leaving it would stamp it next pass. The
  reason rides the notice channel, the same one a failed tool call uses, so it
  reaches the next iteration's prompt through machinery that already exists.
  brainyard appends a synthetic `[evaluation]` iteration record for the same
  purpose (divergence `refusal-rides-the-notice-channel`)."
  [{:keys [st-memory] :as ctx}]
  (let [m @st-memory
        self? (= :refine-self (:answer-decision m))
        np (str (:next-user-prompt m))
        reason (cond
                 (not (str/blank? (str (:critique m)))) (str (:critique m))
                 ;; the model said it was not done and named its own next step
                 (not (str/blank? np))
                 (str "You reported the goal was not achieved. Your own next step: "
                      np ". Do it, then answer again.")
                 :else
                 (str "You reported the goal was not achieved. Find the remaining gap, "
                      "get what is missing, then answer again."))
        reason (cond-> (str/trim reason) (not (re-find #"[.!?]$" (str/trim reason))) (str "."))]
    (swap! st-memory #(-> %
                          (assoc :answer "" :goal-achieved false :critique nil)
                          (update :refinements (fnil inc 0))
                          (queue-notice (str "ANSWER REFUSED: " reason))))
    (emit! ctx :agent.answer/refused {:reason reason :self-reported self?})
    :success))

(def native-tool-call-notice
  (str "TOOL CHANNEL: your previous reply called tools directly, which fails with "
       "\"No such tool available\" — that error does NOT mean the tool is missing. "
       "Those calls were run for you from `tool-calls`; their results are above. "
       "Always call tools by listing them in `tool-calls`."))

(def recovered-thought
  "An iteration's thought once its calls are recovered: what the model wrote
  then, believing the CLI's refusal (\"the bash tool is not bound\"), was
  shown to the person before a call that then ran, and stayed in the history
  the model reads (byj-gly.339)."
  (str "Called tools natively; the model CLI refused them (\"No such tool available\"), "
       "so they were run from tool-calls instead."))

(defn unwrapped-args
  "A natively refused call's arguments as the tool takes them: an input that
  is nothing but `:args`, a string of a JSON object — the model wrapping the
  arguments it meant, `{:args \"{\\\"command\\\": \\\"git status\\\"}\"}` — is
  that object, unless the tool itself takes an `args` input (byj-gly.352).
  So is one that also names the tool, `:name \"bash\"` — the whole call
  wrapped, which slipped past the first rule once (byj-gly.395). Anything
  else is as it came."
  [tool args]
  (let [wrapped? (and (map? args) (contains? args :args)
                      (or (= #{:args} (set (keys args)))
                          (and (= #{:args :name} (set (keys args))) (= (str (:name tool)) (str (:name args))))))
        v (when wrapped? (:args args))
        takes-args? (some #(= "args" (name (:name %))) (schema/inputs tool))]
    (if (and (string? v) (not takes-args?))
      (let [parsed (try (json/read-str v) (catch Throwable _ nil))]
        (if (map? parsed) parsed args))
      args)))

(defn ^{:reads #{:tool-calls :attempted-tool-calls} :writes #{:answer :goal-achieved :last-reasoning :pending-notices :tool-calls}}
  recover-native-tool-calls-action
  "The model called tools natively inside the provider's own agent loop, was
  told they do not exist, and replied without them — often answering that the
  task is impossible (provenance P-12). When the reply has no tool calls, the
  attempts naming bound tools become the tool calls and the answer written
  from that false premise is dropped, and so is the reasoning: the
  iteration's thought becomes `recovered-thought`, said in the event so a
  front end replaces what it showed. A reply that already lists tool calls
  recovered on its own; its attempts are ignored. Not in brainyard (divergence
  `native-tool-calls-recovered`).

  Told a tool does not exist, the model goes looking for it — `list-tools`,
  `get-tool-info`, `search` after the refused `agent$init` — and those
  attempts are refused too. They answered nothing it needs once the tool is
  called, so in a batch that holds a call to any other tool, the node's
  `:lookups` are left out; a batch of lookups alone is kept. A call whose
  arguments came wrapped as one JSON string is unwrapped (`unwrapped-args`,
  byj-gly.352)."
  [{:keys [st-memory tools node] :as ctx}]
  (let [{:keys [tool-calls attempted-tool-calls]} @st-memory
        bound (vec (keep (fn [{:keys [tool-name tool-args] :as c}]
                           (when-let [t (get @tools tool-name)]
                             (assoc c :tool-args (unwrapped-args t tool-args))))
                         attempted-tool-calls))
        lookups (set (:lookups node))
        recovered (if (some #(not (lookups (:tool-name %))) bound)
                    (vec (remove #(lookups (:tool-name %)) bound))
                    bound)]
    (when (and (empty? tool-calls) (seq recovered))
      (swap! st-memory #(-> % (assoc :tool-calls recovered :answer "" :goal-achieved false
                                     :last-reasoning recovered-thought)
                            (queue-notice native-tool-call-notice)))
      (emit! ctx :agent.tools/recovered {:tool-calls recovered :thought recovered-thought
                                         :dropped (vec (for [c bound :when (not (some #{c} recovered))] (:tool-name c)))}))))

(defn ^{:reads #{:tool-calls} :writes #{:pending-notices :tool-calls}}
  strip-unbound-tools-action [{:keys [st-memory tools]}]
  (let [{:keys [tool-calls]} @st-memory
        ;; the registry is live: a user tool defined earlier in this turn is bound
        {bound true unbound false} (group-by #(contains? @tools (:tool-name %)) tool-calls)]
    (when (seq unbound)
      (swap! st-memory #(-> % (assoc :tool-calls (vec bound))
                            (queue-notice (str "UNBOUND TOOL: these tool calls name tools that do not exist and were NOT run: "
                                               (str/join ", " (map :tool-name unbound)))))))))

(defn ^{:reads #{:answer} :writes #{}}
  answer-non-blank? [ctx]
  (let [a (:answer (mem ctx))]
    (and (string? a) (not (re-matches #"[\s\"'`]*" a)))))

(defn ^{:reads #{:tool-calls} :writes #{}}
  has-tool-calls? [ctx]
  (boolean (seq (:tool-calls (mem ctx)))))

(def premature-answer-notice
  (str "CHANNEL CONFLICT: your previous reply populated `answer` together with "
       "`tool-calls`. The tools ran and the answer was DISCARDED — it was written "
       "before the tools' output existed, so any results it described were invented. "
       "Read the actual results above, then answer in a reply of its own, leaving "
       "`tool-calls` empty."))

(defn ^{:reads #{:answer :tool-calls} :writes #{:answer :goal-achieved :pending-notices}}
  resolve-channel-conflicts-action [ctx]
  (when (and (answer-non-blank? ctx) (has-tool-calls? ctx))
    (swap! (:st-memory ctx) #(-> % (assoc :answer "" :goal-achieved false)
                                 (queue-notice premature-answer-notice)))))

(defn ^{:reads #{:answer} :writes #{:last-channel :no-action-streak :terminated :terminated-by}}
  stamp-answer-action [{:keys [st-memory] :as ctx}]
  ;; An abort (llm-error / loop guard) writes its answer then routes through
  ;; here too; keep its reason.
  (swap! st-memory #(assoc % :terminated true
                           :terminated-by (or (:terminated-by %) :answer-channel)
                           :last-channel :answer :no-action-streak 0))
  (emit! ctx :agent.answer/final {:answer (:answer @st-memory)}))

(defn ^{:reads #{:tool-calls :iteration-count} :writes #{:answer :last-channel :last-tool-results :no-action-streak :subagent-calls :terminated :terminated-by :usage}}
  tool-dispatch-action
  "Run the iteration's tool calls. Returns a Task (the tree runs it): brainyard
  moved this `pmap` onto `fx/bounded`, which keeps input order, cancels the
  siblings when one fails, and gives the whole batch one canceller — so a
  cancelled turn stops every running tool. Bound 1 unless :parallel-tools?
  (divergence `parallel-tools-off`)."
  [{:keys [st-memory tools config] :as ctx}]
  (let [batch (vec (take (:max-tool-calls config) (:tool-calls @st-memory)))
        iteration (:iteration-count @st-memory)
        run (fn [{:keys [tool-name tool-args]} raw]
              ;; whether it FAILED, carried rather than re-derived: the result
              ;; becomes a string from here on, and a string cannot tell a
              ;; refusal from a grep that found the word (`byj-gly.87`)
              (cond-> {:tool-name tool-name :tool-args tool-args
                       :tool-result (result->str raw) ::raw raw}
                (tools/failed? raw) (assoc :tool-error? true)))
        ;; a call that outruns :fast-eval-timeout-ms becomes a background task
        ;; and returns a marker instead of its result (RFC-019); until then the
        ;; wait and the work share a canceller, so a cancelled turn still kills
        ;; it (RFC-009)
        ;; each call knows its place in the batch, so what it prints while it
        ;; runs is drawn under it and not under every call still to finish
        ;; (`:agent.tool/output`, byj-gly.364)
        tasks (vec (map-indexed
                    (fn [i {:keys [tool-name tool-args] :as call}]
                      (m/sp (let [t0 (System/currentTimeMillis)
                                  raw (m/? (tasks/detachable
                                            ctx tool-name
                                            #(tools/call-tool (get @tools tool-name) tool-args (assoc ctx :tool-call-index i))))]
                              (account-tool-cost! st-memory raw)
                              (assoc (run call raw) ::elapsed-ms (- (System/currentTimeMillis) t0)))))
                    batch))
        n (if (:parallel-tools? config) (max 1 (count batch)) 1)]
    (emit! ctx :agent.tools/calls {:tool-calls batch})
    (hooks/fire! ctx :agent.tool-calls/pre {:iteration iteration :calls batch})
    (m/sp
     (let [results (m/? (fx/bounded n tasks))
           blocked (some #(when (true? (:hook-blocked (::raw %))) (::raw %)) results)
           ;; how long each took is the renderer's to show (byj-gly.240), and
           ;; the event's alone: in the results a model reads again it would be
           ;; a number that differs on every run of the same work
           timed (mapv #(-> % (dissoc ::raw ::elapsed-ms) (assoc :elapsed-ms (::elapsed-ms %))) results)
           results (mapv #(dissoc % ::raw ::elapsed-ms) results)]
       (swap! st-memory assoc :last-tool-results results :last-channel :tool :no-action-streak 0)
       (emit! ctx :agent.tools/results {:results timed})
       ;; off the fiber: a user hook body may call a blocking tool (RFC-009)
       (m/? (fx/task-of #(hooks/fire! ctx :agent.tool-calls/post {:iteration iteration :calls batch :results results})))
       ;; a :agent.tool-use/pre hook blocked a call: the turn ends here, with
       ;; the hook's answer (brainyard's coact-tool-dispatch-action)
       (when blocked
         (emit! ctx :agent.tools/blocked (select-keys blocked [:reason :by]))
         (swap! st-memory assoc
                :answer (or (:answer blocked) (str "*(Tool dispatch blocked: " (:reason blocked) ")*"))
                :terminated true :terminated-by :tool-blocked-by-hook))))))

(defn- abort! [st-memory reason terminated-by]
  (swap! st-memory assoc :answer (str "Agent stopped: " reason)
         :terminated true :terminated-by terminated-by :last-channel :answer))

(defn ^{:reads #{:iteration-count :repaired-iter :last-reasoning :no-action-streak :consecutive-llm-failures} :writes #{:answer :consecutive-llm-failures :last-channel :last-error :no-action-streak :pending-notices :repaired-iter :terminated :terminated-by}}
  repair-action
  "Two jobs, as in brainyard's coact-repair-action:
   - after an LLM failure (:bt/error set): count it, abort the turn past the
     retry budget, otherwise tell the model what was wrong with its reply;
   - when a valid reply populated no channel: count the no-action streak and
     end the turn past its budget, otherwise nudge.
  Runs at most once per iteration, so the router's none-path does not
  double-count an iteration the llm-guard already repaired."
  [{:keys [st-memory config] :as ctx}]
  (let [{:keys [iteration-count repaired-iter] err :bt/error} @st-memory]
    (when-not (= repaired-iter iteration-count)
      (swap! st-memory assoc :repaired-iter iteration-count :last-channel :none)
      (if err
        (let [n (:consecutive-llm-failures (swap! st-memory update :consecutive-llm-failures inc))
              kind (get-in err [:data :kind])]
          (emit! ctx :agent.repair/llm-error (assoc err :attempt n))
          (cond
            (or (get-in err [:data :fatal?])
                (>= n (:max-retries-on-llm-failure config)))
            (abort! st-memory (:message err) :llm-error)

            ;; The model replied, but unusably: tell it what was wrong.
            (#{:malformed :empty} kind)
            (swap! st-memory queue-notice
                   (str "FORMAT ERROR: your previous reply could not be used (" (:message err) "). "
                        "Reply with ONLY the JSON object described in the response format."))

            ;; The call itself failed (network, provider error, refusal): the
            ;; model produced nothing, so there is nothing to tell it. Replaying
            ;; the provider's error text into every later prompt only bloats
            ;; the context — and a refusal's text can trip the same check again
            ;; (provenance P-5).
            :else
            (swap! st-memory assoc :last-channel :llm-error :last-error (:message err))))
        (let [n (:no-action-streak (swap! st-memory update :no-action-streak inc))]
          (emit! ctx :agent.repair/no-action {:streak n})
          (if (>= n (:max-retries-on-llm-no-action config))
            (abort! st-memory (str "no tool call or answer for " n " consecutive iterations."
                                   (when-let [r (:last-reasoning @st-memory)]
                                     (str "\n\nLast reasoning: " r)))
                    :none-channel-loop-guard)
            (swap! st-memory queue-notice
                   (str "NO ACTION: your previous reply populated neither `tool-calls` nor `answer`. "
                        "Call a tool or write the final answer."))))))))

(defn ^{:reads #{:iteration-count :last-reasoning :last-channel :last-tool-results :pending-notices :answer :last-error} :writes #{:iterations :pending-notices :trajectory-iterations}}
  accumulate-action
  "Append this iteration's record to :iterations (capped) and the uncapped
  trajectory. Answer and failed-call iterations go to the trajectory only;
  a failed call also leaves pending notices for the next real iteration."
  [{:keys [st-memory config]}]
  (let [{:keys [iteration-count last-reasoning last-channel last-tool-results pending-notices answer
                last-error]} @st-memory
        record (cond-> {:iteration iteration-count
                        :thought (cap last-reasoning (:max-thought-chars config))
                        :channel (name (or last-channel :none))}
                 (= :tool last-channel)
                 ;; past :max-output-chars a result is spilled whole and cut to
                 ;; its head and tail around a pointer to it (RFC-014 tier 0)
                 (assoc :tool-results
                        (vec (map-indexed
                              (fn [j r]
                                (let [{:keys [text spilled]}
                                      (context/cap-result config (:turn-number @st-memory 1) iteration-count
                                                          ;; two calls of one tool in an iteration spill apart
                                                          (cond-> (:tool-name r) (pos? j) (str "-" j))
                                                          (:tool-result r) (:max-output-chars config))]
                                  ;; the whole length too: a later stub says how much
                                  ;; the spill holds, not how much was shown (byj-gly.233)
                                  (cond-> (assoc r :tool-result text)
                                    spilled (assoc :spilled spilled :full-chars (count (str (:tool-result r)))))))
                              last-tool-results)))
                 (= :answer last-channel) (assoc :answer answer)
                 (= :llm-error last-channel) (assoc :error last-error))
        keep-n (:max-history-iterations config)]
    (swap! st-memory
           #(if (= :llm-error last-channel)
              (update % :trajectory-iterations conj record)
              (let [record (cond-> record
                             (seq pending-notices) (assoc :notices (str/join "\n\n" pending-notices)))]
                (cond-> (-> % (update :trajectory-iterations conj record)
                            (assoc :pending-notices []))
                  (not= :answer last-channel)
                  (update :iterations (fn [its] (vec (take-last keep-n (conj its record)))))))))))

(defn ^{:reads #{:started-at} :writes #{}}
  arm-task-notify-action
  "Arm ONE wakeup for the tasks this turn leaves running (RFC-019,
  `byj-gly.47`).

  `harvest-tasks` folds a finished task back in at an iteration boundary, which
  covers everything except the case that matters most: a task that finishes
  after the LAST iteration is never reported at all, because nothing runs
  again. This closes that — the session resumes with a fresh turn when the
  first of them lands.

  One arm per turn, not one per task, and that is the difference from
  brainyard, which arms per task and coalesces at the far end. An injected turn
  is an LLM call nobody asked for; three tasks finishing should not be three of
  them. The rest are folded in by `harvest-tasks` on the turn that wakes
  (divergence `task-notify-arms-once-per-turn`).

  Does nothing where nothing can resume a parked turn: `park!` refuses a
  one-shot run and a subagent, so a turn that cannot be woken is not promised
  it will be.

  Nor where the turn set its own wake signal: a `schedule$watch` set in this
  turn is how it chose to be woken. Armed beside it, the task's wakeup came
  first, and the model waited out the rest in that turn with `task$wait` — the
  waiting the watch was set to avoid — and the watches then brought news it
  already had (byj-gly.332). Said with `:task/auto-arm-skipped`."
  [{:keys [config st-memory] :as ctx}]
  (when (and (not (false? (:task-notify? config)))
             (:wakeups ctx)
             (:task-registry ctx))
    (let [running (remove :quiet? (tasks/running-for-owner (:task-registry ctx) (tasks/owner-of config)))]
      (when (seq running)
        (let [ids (mapv :id running)
              watched (when-let [since (some-> st-memory deref :started-at)]
                        (try (schedule/watches-since (or (:project-dir config) (:working-dir config) ".")
                                                     (some-> (:session-id config) str) since)
                             (catch Throwable _ nil)))]
          (if (seq watched)
            (emit! ctx :task/auto-arm-skipped {:task-ids ids :watches (mapv :id watched)})
            (when (tasks/park! ctx {:task-id (first ids)
                                    :note (str "left running when the turn ended: "
                                               (str/join ", " ids))})
              (emit! ctx :task/auto-armed {:task-ids ids}))))))))

(defn ^{:reads #{:question :trajectory-iterations :answer :terminated-by :usage :started-at} :writes #{}}
  store-trajectory-action
  "Append this turn to the session's trajectory (RFC-025) — brainyard's
  `coact-store-results-action`.

  A tree node rather than a hidden event sink, for the reason brainyard makes
  it one: recording is part of what a turn DOES, so a loop that should not
  record drops this node instead of rebuilding the kernel (RFC-001). It writes
  the UNCAPPED `:trajectory-iterations`, not the `:iterations` the prompt
  sees — the history is capped so the model's context stays affordable, which
  is no reason for the record to forget what happened."
  [{:keys [st-memory config lm] :as ctx}]
  (when-let [f (trajectory/log-file config)]
    (let [{:keys [question trajectory-iterations answer terminated-by usage started-at injected-by command-input]} @st-memory
          rec (trajectory/record
               {:session (:session-id config)
                ;; which turn in a tree of turns this was: a subagent's
                ;; instance id, or the one agent this port runs
                :agent (or (:agent-instance-id config) hookbox/agent-id)
                ;; and, for a subagent's turn, who dispatched it
                :owner (:agent-owner config)
                ;; a turn put in the session over its channel (RFC-043), from
                ;; the session's scope
                :injected-by injected-by
                ;; the slash command the question was rephrased from (byj-gly.350)
                :command command-input
                ;; the mode in force: a person's last choice, else the config's (permission/mode)
                :permission-mode (or (some-> (:permission-mode-box ctx) deref) (get config :permission-mode :auto))
                :question question
                :iterations trajectory-iterations
                :answer answer
                :success (trajectory/succeeded? answer terminated-by)
                :terminated-by terminated-by
                :model (:model lm)
                :provider (:provider lm)
                :usage usage
                :started-at started-at})]
      (when (trajectory/append! f rec)
        (emit! ctx :agent.trajectory/stored {:path (.getPath f)
                                             :iterations (:total-iterations rec)})))))

(defn ^{:reads #{:terminated} :writes #{}}
  terminated? [ctx] (boolean (:terminated (mem ctx))))

(defn ^{:reads #{} :writes #{}}
  mem-join-action
  "Join what earlier steps wrote into one key:

    [:action {:fn :mem/join :in {:a :answer :b :published} :out {:answer :joined}
              :sep \"\\n\\n\"}]

  The values of `:in`, in the order of its keys' names, blanks left out,
  joined with `:sep` (a blank line by default), are written to every key `:out`
  names. A tree that composes a report from two steps' words needs this, and a
  compiled fn per pair would be the alternative (RFC-039)."
  [{:keys [st-memory node]}]
  (let [m @st-memory
        text (->> (sort-by (comp name key) (:in node))
                  (map #(some-> (get m (val %)) str str/trim))
                  (remove str/blank?)
                  (str/join (or (:sep node) "\n\n")))]
    (swap! st-memory into (for [k (keys (:out node))] [k text]))
    :success))

(defn ^{:reads #{:answer} :writes #{:answer :terminated :terminated-by}}
  loop-fallback-action [{:keys [st-memory]}]
  (when (str/blank? (:answer @st-memory))
    (let [err (:bt/error @st-memory)]
      (swap! st-memory assoc :terminated true :terminated-by :loop-error
             :answer (str "*(Note: Processing encountered an error"
                          (when err (str ": " (:message err))) ". Please try again.)*")))))

(defn ^{:reads #{:terminated :iterations} :writes #{:answer :terminated :terminated-by}}
  ensure-answer-action [{:keys [st-memory]}]
  (when-not (:terminated @st-memory)
    (let [progress (->> (:iterations @st-memory)
                        (keep :thought) (remove str/blank?) last)]
      (swap! st-memory assoc
             :answer (str "*(The agent hit its iteration limit without producing a final answer. "
                          "Stopping. Latest progress:)*\n\n```\n"
                          (or progress "(no progress captured)") "\n```")
             :terminated true :terminated-by :max-iterations-exhausted
             :goal-achieved false))))

(def registry
  {:react/init #'init-action
   :react/inc-iter #'inc-iter-action
   :react/think-act #'think-act-action
   :react/repair #'repair-action
   :react/recover-native-tool-calls #'recover-native-tool-calls-action
   :react/strip-unbound-tools #'strip-unbound-tools-action
   :react/resolve-channel-conflicts #'resolve-channel-conflicts-action
   :react/answer-non-blank? #'answer-non-blank?
   :react/stamp-answer #'stamp-answer-action
   :react/has-tool-calls? #'has-tool-calls?
   :react/tool-dispatch #'tool-dispatch-action
   :react/harvest-tasks #'harvest-tasks-action
   :react/accumulate #'accumulate-action
   :react/store-trajectory #'store-trajectory-action
   :react/arm-task-notify #'arm-task-notify-action
   :react/terminated? #'terminated?
   :react/loop-fallback #'loop-fallback-action
   :react/ensure-answer #'ensure-answer-action
   :predictor/run #'predictor-run-action
   :tool/run #'tool-run-action
   :mem/true? #'mem-true?-condition
   :mem/set #'mem-set-action
   :mem/join #'mem-join-action
   :mem/match? #'mem-matches?-condition
   :react/answer-decision #'answer-decision-action
   :react/accept-answer? #'accept-answer?
   :react/refine-self? #'refine-self?
   :react/evaluate-answer #'evaluate-answer-condition
   :react/refine #'refine-action
   :hooks/fire #'hooks/fire-action
   :context/enforce #'context/enforce-action})

;; --- entry point -------------------------------------------------------------

(defn resolve-tree
  "Tree data for a name: a classpath resource if one exists, else a file. A
  bare name — no `/`, no `.` — is a bundled loop's, `react` being
  `brainyard/loops/react.edn`: as a person names a loop, and as `flow$status`
  was asked for one and said it was not found (byj-gly.374)."
  [name]
  (let [bundled (when (re-matches #"[\w-]+" (str name)) (str "brainyard/loops/" name ".edn"))]
    (edn/read-string (if-let [r (or (some-> bundled io/resource) (io/resource name))]
                       (slurp r)
                       (if (.isFile (io/file name))
                         (slurp name)
                         (throw (ex-info (str "loop tree not found: " name
                                              " — name a bundled loop (react, for brainyard/loops/react.edn),"
                                              " a resource path, or a file")
                                         {:tree name})))))))

(def react-loop "brainyard/loops/react.edn")

(defn load-tree
  "A loop tree by resource name or file path (default the bundled ReAct loop),
  with its :include nodes expanded."
  [name]
  (bt/expand (resolve-tree (or name react-loop)) resolve-tree))

(defn session-context
  "brainyard's session-context zone: system information, the bound tools,
  instructions and answer format. Leaving zones like this out of the prompt is
  what got the port refused by Opus (provenance P-6)."
  [lm config tools & [skills agent-roster]]
  (str "## System Information\n\n"
       "### Host\n"
       "- OS: " (System/getProperty "os.name") " (" (System/getProperty "os.arch") ")\n"
       (when-let [sh (System/getenv "SHELL")] (str "- Shell: " sh "\n"))
       "\n### Workspace\n"
       "- Working directory: " (.getAbsolutePath (io/file (:working-dir config))) "\n"
       "- Home: " (System/getenv "HOME") "\n"
       "- User: " (System/getProperty "user.name") "\n"
       "\n### LLM\n"
       "- Provider: " (some-> (:provider lm) name) " · Model: " (:model lm) "\n"
       "\n### Runtime\n"
       "- Iteration budget: " (:max-iterations config) " max per turn\n"
       ;; brainyard's session-rows (core/system_info.clj): asked "the
       ;; session-id of this", a model with none of this said it could not
       ;; know. Stable for the session, so above the cache breakpoint.
       "\n### Session\n"
       (let [chain (:agent-chain config)
             depth (:agent-depth config 0)]
         (str "- Agent: " (if (zero? depth) "root (depth 0)" (str (or (last chain) "?") " (depth " depth ")")) "\n"
              (when (pos? depth) (str "- Parent: " (or (last (butlast chain)) "root") "\n"))))
       "- Session: " (or (:session-id config) "<unknown>") "\n"
       "\n## Tools\n\n"
       "Every tool below is invoked via the JSON `tool-calls` channel (one-shot RPC, ReAct-style):\n"
       "```\n[{\"name\": \"<id>\",\n  \"args\": {\"<arg>\": \"<val>\"}}]\n```\n"
       (when (:tool-discovery? config)
         (str "There is no code-blocks channel for this agent — to reach a tool not bound below, discover it with\n"
              "`list-tools` / `get-tool-info` and call it through `tool-calls`.\n"))
       ;; the listing is 56% of the prompt at full specs; one line each with
       ;; `get-tool-info` for the rest is the progressive disclosure skills
       ;; already use (RFC-003). Verbose whenever discovery is off, since then
       ;; there is no way to reach a schema at all.
       (if (and (:compact-agent-tools config) (:tool-discovery? config))
         (let [saved-args? (boolean (:compact-saved-tool-args? config))]
           (str "\n### Agent Tools (bound for THIS turn — `get-tool-info` gives a tool's arguments)\n\n"
                ;; a family a line, past the primitives (byj-gly.188): a
                ;; connected MCP server alone was sixty lines on every call
                (if (:tool-listing-by-family? config true)
                  (discovery/format-tools-by-family tools :saved-args? saved-args?)
                  (tools/format-tools-compact tools :saved-args? saved-args?))
                "\n"))
         (str "\n### Agent Tools (bound for THIS turn — full specs)\n\n"
              (tools/format-tools tools) "\n"))
       (when (:tool-discovery? config)
         (str "\n### Discovery (other registered tools)\n\n"
              (load-resource "brainyard/prompts/tool-discovery.md")))
       ;; when a composite can be run at all: a word on it with the command
       ;; unbound would send the model to call what is not there, and the
       ;; heuristics did not say when it pays (byj-gly.242)
       (when (some #(= "tool$run" (:name %)) tools)
         (str "\n### Composing Tools\n\n"
              (load-resource (if (seq (:tool-lisp-extensions config))
                               "brainyard/prompts/tool-composition-extended.md"
                               "brainyard/prompts/tool-composition.md")) "\n"
              ;; tier D, where a body is about to be written: said only in
              ;; tool$create's spec, haiku never used it (byj-gly.278)
              (when (some #{:reach "reach"} (:tool-lisp-extensions config))
                (str (load-resource "brainyard/prompts/tool-composition-reach.md") "\n"))
              ;; the tools this project has saved, named where a body is about
              ;; to be written: haiku wrote the body of an accepted tool again
              ;; half the time with the tool listed above (byj-gly.251)
              (when-let [saved (and (:composition-saved-tools? config)
                                    (seq (filter #(= :composite (:kind %)) tools)))]
                (str "\n" (load-resource "brainyard/prompts/tool-composition-saved.md") "\n"
                     (str/join "\n" (for [t saved]
                                       (str "- `" (:name t) "`" (some->> (tools/args-line t) (str " "))
                                            (when-not (str/blank? (str (:description t)))
                                              (str " — " (first (str/split-lines (str (:description t)))))))))
                     "\n"))))
       (when (seq skills)
         (str "\n### Skills\n\n"
              (load-resource "brainyard/prompts/skill-substrate.md") "\n\n"
              ;; one line each: a name and what it is for is what the model
              ;; needs to know something exists and when to reach for it — the
              ;; title, or the description's first sentence where the title
              ;; is only the name (byj-gly.336). Bodies come from
              ;; skills$find / skills$read / calling it (RFC-017)
              (str/join "\n" (for [s (take (:max-listed-skills config 40) skills)]
                               (str "- `" (:id s) "` — " (skills/listing-text s (:skill-line-chars config 80)))))
              (when (> (count skills) (:max-listed-skills config 40))
                (str "\n- …and " (- (count skills) (:max-listed-skills config 40))
                     " more — `skills$find` searches them all"))
              "\n"))
       (when (seq agent-roster)
         (str "\n### Agents (delegate a whole task; you get back only its answer)\n\n"
              (load-resource "brainyard/prompts/agent-substrate.md") "\n\n"
              ;; one line each, as skills are: the roster is a menu, and an
              ;; agent's instruction is for the agent, not for its caller
              (str/join "\n" (for [a (take (:max-listed-agents config 20) agent-roster)]
                               (str "- `" (agents/tool-id (:name a)) "` — " (agents/describe a))))
              "\n"))
       ;; a subagent's persona replaces the ReAct instruction; the caller's
       ;; loop keeps its own (RFC-018)
       "\n## Instructions\n" (or (not-empty (str (:agent-instruction config))) react-instruction) "\n"
       ;; the project's CLAUDE.md and AGENTS.md, where brainyard puts its
       ;; BRAINYARD.md: after the instruction, session-stable; then the
       ;; person's own (RFC-040)
       (some->> (instructions/section config) (str "\n"))
       "\n## Answer Format\n" (load-resource "brainyard/prompts/answer-format.md")))

(def classifier-arg-chars
  "How much of each argument the auto-mode classifier is shown: a file's whole
  new content is not what decides whether writing it is safe — where it goes is."
  400)

(defn auto-classifier
  "The `:auto-classify` seam the permission gates call in `:auto`
  (`permission/ask`, byj-gly.285.1): `(fn [request ctx])` → `{:verdict
  :allow|:block|:unsure :reason}`, from the `permission/classify` predictor on
  `:auto-mode-classifier-lm`. It is shown the turn's question and the call —
  the tool, its arguments cut to `classifier-arg-chars`, its effects and what the
  gate would have asked about — and the workspace; never a tool's output or the
  agent's reasoning. A session on a stand-in model — the mock, or a CLI a test
  swapped in (`:cli-command`) — classifies on it, so an offline run or a test
  never reaches a live one. Throws what the call throws;
  the gate treats that as unsure."
  [config]
  (let [p (delay (predictor/load-predictor "permission/classify" (:predictor-roots config)))
        clip (fn [v] (if (and (string? v) (> (count v) classifier-arg-chars))
                       (str (subs v 0 classifier-arg-chars) "…[" (- (count v) classifier-arg-chars) " more chars]")
                       v))]
    (fn [request ctx]
      ;; no model of its own to ask — a bare ctx, as a test builds — no verdict
      (when-not (:lm ctx)
        (throw (ex-info "no model to classify with" {})))
      ;; the config in force now, over the one it was built with: a session
      ;; lays an edited config over its ctx without building this again
      (let [config (merge config (:config ctx))
            question (str (some-> (:st-memory ctx) deref :question))
            call (pr-str (cond-> (select-keys request [:type :action :tool :display :path :paths :command :effects])
                           (:args request) (assoc :args (into {} (map (fn [[k v]] [k (clip v)])) (:args request)))))
            workspace (str "working directory: " (get-in ctx [:config :working-dir])
                           "\nallowed without asking: "
                           (str/join ", " ((requiring-resolve 'brainyard.permission/allowed-dirs)
                                           ctx (get-in ctx [:config :working-dir] "."))))
            {:keys [outputs]} (predictor/run @p {:question question :call call :workspace workspace}
                                             {:lm (:lm ctx)
                                              ;; a stand-in model — the mock, or a
                                              ;; CLI a test swapped in — is kept,
                                              ;; so no test or offline run reaches
                                              ;; a live one through the classifier
                                              :lm-override (when-not (or (= :mock (:provider (:lm ctx)))
                                                                         (:cli-command (:lm ctx)))
                                                             (:auto-mode-classifier-lm config))
                                              :lm-tiers (:agent-lm-tiers config)
                                              :env-fn (:env-fn ctx)
                                              :params-roots (:params-roots config)
                                              :dir (llm-dir config)
                                              :on-event (:on-event ctx)})]
        {:verdict (keyword (str/lower-case (str (:verdict outputs))))
         :reason (str (:reason outputs))}))))

(defn make-ctx
  "The tick context shared by every loop over one agent: LM, tools, stable
  prompt zones, predictor params, config and the leaf registry. `:st-memory`
  is set by the caller."
  [{:keys [lm config tools registry zones params on-event permission-fn permission-mode-box allowed-dirs-box subagent-counter subagent-registry subagent-scope task-registry wakeups mcp-clients mcp-box env-fn start-session] extra-hooks :hooks}]
  (let [config (merge default-config config)
        ;; the Tool Lisp this run speaks (byj-gly.258): run-wide, as config is
        _ (tl/set-extensions! (:tool-lisp-extensions config))
        ;; every directory is absolute and canonical from here down: they are
        ;; compared, and they are stored for a later process to restore (RFC-013)
        config (update config :working-dir #(or (config/canonical-dir %) %))
        ;; .brainyard/ belongs to the project, not to wherever the agent was
        ;; started (RFC-013); a caller that passes no config gets the same walk
        config (update config :project-dir #(config/resolve-project-dir (:working-dir config) %))
        ;; one spill directory per run: sessions share a project (RFC-014)
        config (update config :session-id #(or % (context/session-id)))
        config (cond-> config
                 (nil? (:predictor-roots config))
                 (assoc :predictor-roots (predictor/default-roots (:project-dir config) "predictors"))
                 (nil? (:params-roots config))
                 (assoc :params-roots (predictor/default-roots (:project-dir config) "programs")))
        own-tools (cond-> (vec (or tools tools/builtin-tools))
                    (:tool-discovery? config) (into discovery/discovery-tools))
        ;; the run's MCP servers as they stand NOW (byj-gly.182): a box moves
        ;; while the run goes on, so its tools are added here, per build, and
        ;; never kept in :base-tools — a child adds them again from the box
        mcp-clients (merge mcp-clients (some-> mcp-box mcp/box-clients))
        base-tools (into own-tools (some-> mcp-box mcp/box-tools))
        reg (toolbox/registry base-tools)
        dir (toolbox/tools-dir (:project-dir config))
        authoring (when (:tool-authoring? config)
                    (cond->> (toolbox/authoring-tools reg dir)
                      (not (:tool-run? config)) (remove #(= "tool$run" (:name %)))))
        _ (swap! reg into (map (juxt :name identity)) authoring)
        ;; a user tool calling a tool of a server still starting is early, not
        ;; malformed: it waits, and so does a turn's start (byj-gly.300)
        {:keys [skipped waiting]} (when (:user-tools? config)
                                    (toolbox/load-user-tools! reg dir {:pending? (some-> mcp-box mcp/starting-tool?)}))
        _ (when on-event
            (doseq [s skipped] (events/emit! on-event :tools/user-tool-skipped s))
            (doseq [w waiting] (events/emit! on-event :tools/user-tool-waiting w)))
        ;; hooks after tools: a user hook's body is validated against them
        hook-reg (hooks/registry (concat (when (:builtin-hooks? config) hookbox/builtin-hooks) extra-hooks))
        hooks-dir (hookbox/hooks-dir (:project-dir config))
        ;; skills: name and description in the prompt, the body only when called
        {skill-list :skills skill-tools :tools} (when (:skills? config) (skills/skills-and-tools config (fn [q opts] (ask-task q opts))))
        skill-cmds (when (:skills? config)
                     (concat (skills/commands config)
                             ;; the write half (RFC-017)
                             (when (:skill-authoring? config)
                               (skills/authoring-commands config reg (fn [q opts] (ask-task q opts))))))
        _ (swap! reg into (map (juxt :name identity)) (concat skill-tools skill-cmds))
        ;; what self-improvement of every kind proposed, read-only: skills,
        ;; tools and predictor params wait in one place (byj-gly.249.1)
        _ (swap! reg into (map (juxt :name identity)) (proposals/commands config))
        hook-cmds (when (:hook-authoring? config) (hookbox/authoring-tools hook-reg reg hooks-dir))
        _ (swap! reg into (map (juxt :name identity)) hook-cmds)
        {hooks-skipped :skipped} (hookbox/load-user-hooks! hook-reg reg hooks-dir)
        _ (when on-event
            (doseq [s hooks-skipped] (events/emit! on-event :hooks/user-hook-skipped s)))
        ;; the agent roster (RFC-018): each entry is a tool that runs a whole
        ;; child turn. `ask-task` is injected, so brainyard.agents knows nothing
        ;; about the loop it starts
        {agent-roster :agents agent-tools :tools agent-cmds :commands :as loaded}
        (agents/agents-and-tools config (fn [q opts] (ask-task q opts)))
        _ (swap! reg into (map (juxt :name identity)) (concat agent-tools agent-cmds))
        ;; background tasks (RFC-019): the commands, and the auto-detach the
        ;; tool dispatch applies to every call
        task-cmds (when (:tasks? config true) (tasks/commands))
        _ (swap! reg into (map (juxt :name identity)) task-cmds)
;; prompts set to a time (byj-gly.325): they fire as turns of this
        ;; session, so a subagent, whose turn ends first, has none
        schedule-cmds (when (and (:schedule-tools? config true) (not (:agent-instance-id config)))
                        (schedule/commands config))
        _ (swap! reg into (map (juxt :name identity)) schedule-cmds)
        ;; the session channel's senders (RFC-031): other live sessions of this
        ;; project, asked and handed work. Not in a subagent: a child turn asks
        ;; its caller, not a peer
        session-cmds (when (and (:session-tools? config true) (not (:agent-instance-id config)))
                       (channel/commands config {:start-session start-session}))
        _ (swap! reg into (map (juxt :name identity)) session-cmds)
        ;; sharing as /share does it (RFC-043, byj-gly.362.2): a share is the
        ;; session's, so a subagent has none
        share-cmds (when (and (:share-tools? config true) (not (:agent-instance-id config)))
                     (share-tools/commands config))
        _ (swap! reg into (map (juxt :name identity)) share-cmds)
        ;; the Terminal view's programs (byj-gly.417): the session's, as a
        ;; share is, and only where tmux is to run them
        terminal-cmds (when (and (:terminal-tools? config true) (not (:agent-instance-id config))
                                 (terminal/tmux-present?))
                        (terminal/commands config))
        _ (swap! reg into (map (juxt :name identity)) terminal-cmds)
        ;; the Emacs :emacs-server names (RFC-067): the session's, as the
        ;; Terminal view's programs are, and only where emacsclient is
        emacs-cmds (when (and (:emacs-tools? config true) (not (:agent-instance-id config))
                              (emacs/installed?))
                     (emacs/commands config))
        _ (swap! reg into (map (juxt :name identity)) emacs-cmds)
        ;; the coach stance (RFC-067 phase 3): the session's, kept in its
        ;; directory, so a subagent and a session with no id have none
        coach-cmds (when (and (:coach? config true) (not (:agent-instance-id config)) (:session-id config))
                     (coach/commands config))
        _ (swap! reg into (map (juxt :name identity)) coach-cmds)
        ;; a team this session leads (RFC-032): members are live sessions of
        ;; their own. A subagent leads no team, nor does a member (it is
        ;; started with :team-tools? false)
        team-cmds (when (and (:team-tools? config true) (:session-tools? config true)
                             (not (:agent-instance-id config)))
                    (teams/commands config))
        _ (swap! reg into (map (juxt :name identity)) team-cmds)
        ;; durable memory, if this project keeps any (RFC-020): a command, not a
        ;; subsystem, and never injected — the model asks
        memory-cmds (when (:memory? config true) (memory/commands config))
        _ (swap! reg into (map (juxt :name identity)) memory-cmds)
        ;; state a Tool Lisp body keeps between calls, where it has no atoms:
        ;; tier D's, so bound only while that tier is (byj-gly.261)
        store-cmds (when (contains? @tl/extensions :reach) (store/commands config))
        _ (swap! reg into (map (juxt :name identity)) store-cmds)
        ;; what earlier runs of this project did (RFC-026). A command, like
        ;; memory: the log is never injected, the model asks.
        log-cmds (eventlog/commands config)
        _ (swap! reg into (map (juxt :name identity)) log-cmds)
        ;; reading and changing this project's settings (RFC-013). Permission
        ;; keys are not writable from here, whoever asks.
        config-cmds (configbox/commands config)
        _ (swap! reg into (map (juxt :name identity)) config-cmds)
        ;; recorded turns as a dataset (RFC-025), redacted on the way out
        export-cmds (trajectory-export/commands config)
        _ (swap! reg into (map (juxt :name identity)) export-cmds)
        ;; and what those turns say about how the agent is doing (RFC-025)
        analytics-cmds (analytics/commands config)
        _ (swap! reg into (map (juxt :name identity)) analytics-cmds)
        ;; measuring a predictor against a dataset (RFC-021). Accepting a
        ;; proposal is NOT here: it is a CLI action, because a params file
        ;; rewrites every later turn's prompt.
        program-cmds (programs/commands config)
        _ (swap! reg into (map (juxt :name identity)) program-cmds)
        ;; checking a loop before running it (RFC-022)
        flow-cmds (flow/commands config)
        _ (swap! reg into (map (juxt :name identity)) flow-cmds)
        ;; work that outlives the turn (RFC-023)
        todo-cmds (todo/commands config)
        _ (swap! reg into (map (juxt :name identity)) todo-cmds)
        ;; driving Claude Code sessions (RFC-039): agent$claude's tree calls
        ;; these, and its own config is what turns them on
        claude-cmds (when (:claude-tools? config) (claude-agent/commands config))
        _ (swap! reg into (map (juxt :name identity)) claude-cmds)
        ;; driving Codex sessions (RFC-049, byj-gly.430.6): agent$codex's config turns them on
        codex-cmds (when (:codex-tools? config) (codex-agent/commands config))
        _ (swap! reg into (map (juxt :name identity)) codex-cmds)
        ;; preparing a project (RFC-040): agent$init's config turns them on
        init-cmds (when (:init-tools? config) (init/commands config))
        _ (swap! reg into (map (juxt :name identity)) init-cmds)
        ;; MCP servers connected by the caller (RFC-024). Their tools arrive in
        ;; `:tools` like any other; this is the discovery command beside them.
        mcp-cmds (mcp/commands config mcp-clients)
        _ (swap! reg into (map (juxt :name identity)) mcp-cmds)
        _ (when on-event
            (doseq [s (:shadowed loaded)] (events/emit! on-event :agents/shadowed s))
            (doseq [p (:problems loaded)] (events/emit! on-event :agents/agent-skipped p)))
        ;; visibility LAST, over everything registered: brainyard's
        ;; :tool-use-control, as the two config keys a dispatch narrows a child
        ;; with (RFC-018). A hidden tool is gone from the registry, not merely
        ;; absent from the listing — the model cannot call what it cannot see.
        ;; An MCP server for the agents that name it alone (`:only-named?`,
        ;; RFC-024) narrows even a turn that sets neither key
        _ (when (or (seq (:visible-tools config)) (seq (:hidden-tools config))
                    (some #(true? (:only-named? %)) (vals (:mcp-servers config))))
            (swap! reg #(into {} (filter (fn [[n _]] (agents/visible? config n))) %)))
        visible (fn [ts] (filterv #(agents/visible? config (:name %)) ts))
        ;; agents stay OUT of the full-spec listing for the reason skills do
        ;; (RFC-017): the `### Agents` section names each in one line, and an
        ;; agent's own instruction is for the agent, not for its caller
        listed (visible (concat base-tools authoring hook-cmds skill-cmds agent-cmds task-cmds schedule-cmds session-cmds team-cmds memory-cmds
                                log-cmds config-cmds export-cmds analytics-cmds program-cmds flow-cmds todo-cmds claude-cmds codex-cmds mcp-cmds share-cmds
                                (sort-by :name (filter #(= :composite (:kind %)) (vals @reg)))))
        agent-roster (filterv #(agents/visible? config (agents/tool-id (:name %))) agent-roster)
        skill-list (filterv #(agents/visible? config (:id %)) skill-list)]
    {:config config
     :lm lm
     :registry (merge brainyard.agent/registry registry)
     ;; the primitives, kept so a subagent inherits the same palette its caller
     ;; had, narrowed by its own globs (RFC-018). MCP tools are NOT among them:
     ;; they come from the run's box, as it stands when a ctx is built (RFC-024).
     :base-tools own-tools
     ;; the caller's own hook entries, kept so a ctx built again from config
     ;; (a config edit, an MCP change) still has them (byj-g5n.2)
     :extra-hooks (vec extra-hooks)
     ;; the run's live MCP set; a session builds again when its version moves
     :mcp-box mcp-box
     :mcp-version (some-> mcp-box deref :version)
     ;; the servers still starting that a user tool waits for (byj-gly.300),
     ;; and those whose tools an agent's own tool list names: a built
     ;; agent's first turn went on without its GitHub tools (byj-1wv.115.46)
     :mcp-waiting (not-empty (into (into (sorted-set) (comp (mapcat :waits-for) (keep mcp/server-of)) waiting)
                                   (some->> mcp-box deref :starting seq (mcp/named-servers (:visible-tools config)))))
     ;; the servers themselves, so a child can ask what is connected
     :mcp-clients mcp-clients
     ;; one budget, and one instance registry, for the whole tree of turns
     ;; under this one (RFC-018)
     :subagent-counter (or subagent-counter (atom 0))
     :subagent-registry (or subagent-registry (atom {}))
     ;; how long an instance in that registry lasts: a session owns one across
     ;; its turns, a one-shot `ask` drops it with the ctx (RFC-018)
     :subagent-scope (or subagent-scope :turn)
     ;; what this session has running in the background (RFC-019)
     :task-registry (or task-registry (tasks/registry))
     ;; turns parked on a task, waiting for it to finish. Only a session has
     ;; one: a one-shot run has no later turn to resume into
     :wakeups wakeups
     ;; a front end's way to start another session beside this one, with a
     ;; first question (byj-gly.386): what session$start calls; nil where the
     ;; front end runs one session only
     :start-session start-session
     ;; the live tool registry (brainyard.toolbox): user tools defined during a
     ;; turn are callable at once; the listing below is fixed per turn
     :tools reg
     ;; the hook registry (brainyard.hooks), fired by tree nodes and the kernel
     :hooks hook-reg
     ;; the ask channel of the permission gate (RFC-012); nil is headless
     :permission-fn permission-fn
     ;; what :auto decides with, where a gate would ask (byj-gly.285.1)
     :auto-classify (auto-classifier config)
     ;; its allows and blocks, remembered for the ctx's life — a session's —
     ;; keyed by `permission/verdict-key` (byj-gly.285.2)
     :classifier-verdicts (atom {})
     ;; the mode a person chose in the front end, over the config's; nil when
     ;; nobody can choose (`permission/mode`)
     :permission-mode-box permission-mode-box
     ;; directories a person added for the session with /allow-path, which the
     ;; path gate reads beside :allowed-dirs (byj-gly.145); nil in a bare run
     :allowed-dirs-box allowed-dirs-box
     ;; how a NAME becomes a value in THIS run's environment, `.env` included
     ;; (RFC-013). Credentials are resolved through it rather than through the
     ;; bare process environment: a tier, a predictor's `:lm` and an agent on
     ;; another provider all need a key the CLI may only have found in a file
     ;; (`byj-gly.91`).
     :env-fn (or env-fn #(System/getenv %))
     :zones (merge {:agent-core (load-resource "brainyard/prompts/agent-core.md")
                    :session-context (session-context lm config listed skill-list agent-roster)}
                   zones)
     :params params
     :on-event on-event}))

(defn run-tree!
  "Validate tree against ctx's registry, then tick it."
  [ctx tree]
  (when-let [problem (bt/validate tree (:registry ctx))]
    (throw (ex-info (str "invalid loop tree: " problem) {})))
  (bt/tick ctx tree))

(defn turn-result
  "The public shape of one finished ReAct turn's memory."
  [m]
  {:answer (:answer m)
   :terminated-by (:terminated-by m)
   :goal-achieved (:goal-achieved m)
   ;; in a turn a slash command became, its subagent's suggestion — what
   ;; the command itself advises next — over the model's own (byj-gly.350)
   :next-user-prompt (or (not-empty (str (:command-suggestion m))) (:next-user-prompt m))
   :iterations (:iteration-count m)
   :trajectory (:trajectory-iterations m)
   :usage (:usage m)})

(defn ask-task
  "One agent turn as a Task (brainyard `run-bt-task`): cancelling it stops the
  tree structurally — the in-flight LLM call or tool is interrupted and its
  subprocesses killed. Settles with the same map `ask` returns; takes the same
  opts."
  [question {:keys [tree history-context parent-trail] :as opts}]
  (let [st-memory (atom (cond-> {:question question}
                          history-context (assoc :history-context history-context)
                          (seq parent-trail) (assoc :parent-trail (vec parent-trail))))
        ctx (assoc (make-ctx opts) :st-memory st-memory)
        tree (or tree (load-tree nil))]
    (when-let [problem (bt/validate tree (:registry ctx))]
      (throw (ex-info (str "invalid loop tree: " problem) {})))
    (m/sp
     (let [status (m/? (bt/tick-task ctx tree))]
       (assoc (turn-result @st-memory) :result status)))))

(defn ask
  "Run one agent turn.

  opts:
    :lm        LM config (see brainyard.llm)      — required
    :config    overrides of `default-config`
    :tools     tool maps (default brainyard.tools/builtin-tools)
    :tree      behavior tree data (default the bundled loops/react.edn)
    :registry  extra/overriding leaf fns
    :zones     {zone-keyword text} extra/overriding system-prompt zones
    :params    {predictor-id params} — the dynamic params layer
    :history-context  prior-conversation text for the system prompt
    :on-event  (fn [event data]) trace hook

  Returns {:answer :result :terminated-by :goal-achieved :next-user-prompt
           :iterations :trajectory :usage}."
  [question opts]
  (if (:task-registry opts)
    ;; the caller owns the registry — a session's tasks outlive its turns
    (fx/run-value!! (ask-task question opts))
    ;; a one-shot question owns whatever it detached, and nothing may outlive
    ;; it: there is no session left to cancel it, and no shutdown hook (RFC-019)
    (let [reg (tasks/registry)]
      (try (fx/run-value!! (ask-task question (assoc opts :task-registry reg)))
           (finally (tasks/cancel-all! reg))))))
