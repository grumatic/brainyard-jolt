;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.native-call-spike
  "How often a claude-code model calls tools natively — a `tool_use` the CLI
  refuses with \"No such tool available\" — and whether rewording the prompt
  makes it rarer (byj-gly.353, provenance P-12).

  The cases are real: every `:llm.cli/call` in a project's event log that was
  followed, in its session, by an `:agent.tools/recovered` — the requests that
  did produce native calls. Each is replayed as recorded (`:as-recorded`) and
  with its system prompt rewritten as the current prompt resources would say
  it (`:reworded`, `reword`), a few times each, and the native calls counted
  per replay (`llm/rejected-tool-calls`, the provider's own reader).

      (require '[brainyard.dev.native-call-spike :as s])
      (def cases (s/cases [\"/path/to/project\" …]))
      (s/measure cases {:reps 3})   ; → a row per case and arm, and the totals

  Every replay is a real call on the claude CLI's subscription: run it to
  confirm a change, a handful of times, not in a loop.

  Measured 2026-10-15 (byj-gly.353), the 7 cases of five byj-sim projects
  (five on haiku, two on sonnet, by the recorded --model), 3 replays each per arm,
  `rewrites` the candidate:

  | arm | replays with a native call | native calls | no structured output |
  |---|---|---|---|
  | as recorded | 12 / 21 | 27 | 0 |
  | reworded | 14 / 21 | 24 | 2 |

  The rewording did not make native calls rarer, and was not shipped (RFC-006
  \"No flag stops a native call\"). `rewrites` keeps the words tried, so the
  measurement can be run again or a new candidate put in their place.

  The same cases over the Messages API, on Bedrock (`measure-bedrock`,
  `global.anthropic.claude-haiku-5-5`, ap-northeast-2, 3 each): 0 of 21 with a
  `tool_use` or a call written as text, 20 parsed as a reply with
  `tool-calls`, 1 (thinking, then text) did not parse. With no `tools` field
  there is nothing to call natively (RFC-006 \"Over the Messages API\")."
  (:require [clojure.string :as str]
            [brainyard.dev.replay :as replay]
            [brainyard.eventlog :as eventlog]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [jolt.process :as p]))

(defn cases
  "The recorded claude -p calls that produced native tool calls, in the
  projects at `dirs`: for each `:agent.tools/recovered`, the newest
  `:llm.cli/call` of its session at or before it. Each `{:project :session
  :ts :args :prompt :recovered}` — `:recovered` the calls that were run."
  [dirs]
  (vec (for [dir dirs
             :let [cfg {:project-dir dir :working-dir dir}
                   calls (eventlog/read-events cfg {:event :llm.cli/call :full? true :limit 5000})
                   recs (eventlog/read-events cfg {:event :agent.tools/recovered :full? true :limit 500})]
             r recs
             :let [c (first (filter #(and (= (:session %) (:session r)) (<= (:ts %) (:ts r))) calls))]
             :when c]
         {:project (last (str/split dir #"/")) :session (:session r) :ts (:ts c)
          :args (vec (get-in c [:data :args])) :prompt (get-in c [:data :stdin])
          :recovered (mapv :tool-name (get-in r [:data :tool-calls]))})))

(defn- with-system
  "`args` with its `--system-prompt` value replaced by (f value)."
  [args f]
  (let [i (.indexOf ^java.util.List args "--system-prompt")]
    (if (neg? i) args (update args (inc i) f))))

(defn- events [out]
  (let [all (try (json/read-str out) (catch Throwable _ nil))]
    (if (sequential? all)
      all
      (keep #(try (json/read-str %) (catch Throwable _ nil)) (str/split-lines (str out))))))

(defn run-once
  "One replay of `args` with `prompt`: `{:native [tool-name …] :structured?
  :exit}` — the native calls the CLI refused, in order."
  [args prompt]
  (let [env (dissoc (into {} (System/getenv)) "CLAUDECODE")
        r @(apply p/process {:in prompt :out :string :err :string :env env} args)
        evs (events (:out r))
        res (last (filter #(= "result" (:type %)) evs))]
    {:native (mapv :tool-name (llm/rejected-tool-calls evs))
     :structured? (boolean (:structured_output res))
     :exit (:exit r)}))

(def rewrites
  "The candidate measured for byj-gly.353, old words to new: the
  structured-output directive's tool sentences (`llm/structured-output-
  directive`), the tools zone's first line (`agent.clj`), and /init's usage
  (`commands/init.edn`). A recorded request is replayed with these replaced —
  in its system prompt and its question alike. Not shipped: it did not help."
  [["You can call NO tool directly except StructuredOutput, and you have NO filesystem access: never write files, never run shell or heredocs (e.g. `cat > file <<'EOF'`), and never perform the task yourself. The tools named in the prompt DO exist: they run when you list them in `tool-calls`.\n"
    "StructuredOutput is the ONLY tool you can call, and you have NO filesystem access: never write files, never run shell or heredocs (e.g. `cat > file <<'EOF'`), and never perform the task yourself. The tools named in the prompt DO exist — bash, read-file, list-tools, agent$… and every other — but none is a tool you call: each is a name you write into an entry of the `tool-calls` field of your StructuredOutput call, and the runtime runs the entries after your reply. `tool-calls` is that field, not a tool. Calling one of them directly fails with \"No such tool available\" — that never means it is missing: put it in `tool-calls`, in this same reply.\n"]
   ["Every tool below is invoked via the JSON `tool-calls` channel (one-shot RPC, ReAct-style):\n"
    "Every tool below runs only as an entry of your reply's `tool-calls` field — none is a function or tool you call directly; the runtime runs the entries after your reply:\n"]
   ["Do it through the agent$init tool, which runs the init agent: call it with :question set to the request — the person's own words where they gave any — and read its report. Call it again only when the report says the next step waits on something you can now give it: run it with \"check\" when"
    "Do it through agent$init, which runs the init agent: put it in your `tool-calls` with :question set to the request — the person's own words where they gave any — and read its report. List it in `tool-calls` again only when the report says the next step waits on something you can now give it: ask it \"check\" when"]])

(defn reword
  "`text` with `rewrites` applied."
  [text]
  (reduce (fn [t [old new]] (str/replace t old new)) (str text) rewrites))

(defn changed?
  "Which rewrites a recorded case's words contain the old text of — a
  rewrite that finds nothing to replace measures nothing."
  [c]
  (let [text (str (some #(when (= "--system-prompt" (first %)) (second %)) (partition 2 1 (:args c))) "\n" (:prompt c))]
    (mapv (fn [[old _]] (str/includes? text old)) rewrites)))

(defn measure
  "Replay each case `:reps` times as recorded and reworded. A row per case and
  arm `{:case :arm :replays :with-native :native-calls :structured}` and, under
  `:totals`, the same per arm."
  [cases {:keys [reps arms] :or {reps 3 arms [:as-recorded :reworded]}}]
  (let [rows (vec (for [[n c] (map-indexed vector cases)
                        arm arms
                        :let [args (cond-> (:args c) (= arm :reworded) (with-system reword))
                              prompt (cond-> (:prompt c) (= arm :reworded) reword)
                              runs (vec (repeatedly reps #(run-once args prompt)))]]
                    {:case n :project (:project c) :arm arm :replays reps
                     :with-native (count (filter (comp seq :native) runs))
                     :native-calls (reduce + (map (comp count :native) runs))
                     :structured (count (filter :structured? runs))
                     :natives (mapv :native runs)}))]
    {:rows rows
     :totals (into {} (for [[arm rs] (group-by :arm rows)]
                        [arm {:replays (reduce + (map :replays rs))
                              :with-native (reduce + (map :with-native rs))
                              :native-calls (reduce + (map :native-calls rs))}]))}))

;; --- the same requests over the Anthropic Messages API, on Bedrock ------------------

(defn- without-directive
  "A recorded system prompt less the structured-output directive, which only
  the claude-code provider appends (it names the CLI's StructuredOutput tool):
  what byj's Anthropic and Bedrock providers send as the system prompt."
  [system]
  (let [i (str/index-of (str system) "\n\n=== STRUCTURED OUTPUT — MANDATORY ===\n")]
    (if i (subs system 0 i) (str system))))

(defn bedrock-once
  "One recorded case sent as byj's Bedrock provider sends it — `llm/bedrock-body`,
  the Messages body with no `tools` field, through `aws bedrock-runtime
  invoke-model` — and what came back: the content blocks' types, whether any is
  a `tool_use`, whether the text parses as byj's reply (`json/parse-json-response`)
  with `tool-calls` or `answer`, and whether it writes a tool call as text
  (`<function_calls>`, `<invoke`)."
  [c {:keys [model region]}]
  (let [lm {:provider :bedrock :model model :region region :max-tokens 4096}
        messages [{:role "system" :content (without-directive (some #(when (= "--system-prompt" (first %)) (second %))
                                                                      (partition 2 1 (:args c))))}
                  {:role "user" :content (:prompt c)}]
        dir (doto (java.io.File/createTempFile "byj-spike" "") .delete .mkdirs)
        body-file (.getPath (java.io.File. dir "body.json"))
        out-file (.getPath (java.io.File. dir "out.json"))
        _ (spit body-file (json/write-str (llm/bedrock-body lm messages)))
        r @(apply p/process {:out :string :err :string} (llm/bedrock-args lm true body-file out-file))
        resp (when (zero? (:exit r)) (try (json/read-str (slurp out-file)) (catch Throwable _ nil)))
        blocks (:content resp)
        text (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) blocks))
        parsed (try (json/parse-json-response text) (catch Throwable _ nil))]
    {:exit (:exit r) :err (when-not (zero? (:exit r)) (subs (str (:err r)) 0 (min 300 (count (str (:err r))))))
     :block-types (mapv :type blocks)
     :tool-use? (boolean (some #(= "tool_use" (:type %)) blocks))
     :reply? (boolean (and (map? parsed) (or (contains? parsed :tool-calls) (contains? parsed :answer))))
     :tool-calls (mapv #(or (:name %) (:tool-name %)) (:tool-calls parsed))
     :text-call? (boolean (re-find #"<function_calls>|<invoke\b|antml:invoke" text))
     :stop-reason (:stop_reason resp)}))

(defn measure-bedrock
  "Each case `:reps` times through Bedrock: a row per case, and the totals."
  [cases {:keys [reps] :or {reps 3} :as opts}]
  (let [rows (vec (for [[n c] (map-indexed vector cases)
                        :let [runs (vec (repeatedly reps #(bedrock-once c opts)))]]
                    {:case n :project (:project c) :runs runs}))]
    {:rows rows
     :totals (let [runs (mapcat :runs rows)]
               {:replays (count runs)
                :failed (count (remove #(zero? (:exit %)) runs))
                :tool-use (count (filter :tool-use? runs))
                :text-calls (count (filter :text-call? runs))
                :replies (count (filter :reply? runs))
                :with-tool-calls (count (filter #(seq (:tool-calls %)) runs))})}))
