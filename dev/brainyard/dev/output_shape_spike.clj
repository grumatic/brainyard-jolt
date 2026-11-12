;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.output-shape-spike
  "Does the shape of `react/think-act`'s output change what the model does?
  (byj-gly.124)

  Today the predictor returns a flat JSON object — `tool-calls`, `answer`,
  `goal-achieved`, `next-user-prompt` — with kebab-case `tool-name`/`tool-args`
  and `tool-args` an OPEN object. Nothing in this port or in brainyard has ever
  handed a provider its tools through a native `tools` parameter (RFC-003:41-44,
  RFC-006 \"messages in, text out\"). Both vendors say the native path is the
  trained one — OpenAI, that functions are \"injected into the system message in
  a syntax the model has been trained on\"; Anthropic, that its schemas are
  \"trained-in\" and that recovering structured intent from free-form text is an
  anti-pattern — but neither publishes a comparison, so the SIZE of the effect
  here is unmeasured. Provenance P-12 is the local evidence that the current
  shape is off-distribution: behind `claude -p` the model reached for native
  `tool_use` in ~1 of 4 replayed calls and broke 7 of 10 eval runs.

  This measures the half that needs no provider work: the JSON envelope itself,
  as a 2x2 over

      shape       :flat (today) | :openai (stop_reason discriminator,
                  snake_case name/arguments, `arguments` a JSON STRING)
      enforcement :prompt-only (today on ollama) | :response-format

  `arguments`-as-a-string is the load-bearing part, and it is not cosmetic:
  an open `tool-args` can never satisfy OpenAI's strict rules, so this
  predictor has always gone out with `strict:false` (signature.clj:49-55,
  byj-gly.24). A string field does satisfy them. `strict-eligibility` below
  checks that statically — it is a fact about the schemas, not a measurement,
  and it costs no call.

  Kept honest on purpose:

  - The two instruction prompts differ ONLY where the shape forces them to.
    `openai-instructions` is `react-instructions.md` with the channel names and
    the output shapes rewritten and the field-consistency rules dropped (the
    discriminator makes them unrepresentable). A shorter, better-written prompt
    would measure prompt-writing rather than shape.
  - Both candidates are asked for the same information, so `goal_achieved` and
    `next_user_prompt` survive into the OpenAI shape rather than being quietly
    dropped to make it look leaner.
  - `:repeats 1` is the DEFAULT and it is wrong on claude-code. The
    justification was ollama's: at temperature 0 it is deterministic, the
    compile behind byj-gly.57 saw `:stderr` 0.0 across two passes, so a second
    pass buys no spread and the power comes from pairing over examples. That
    reasoning does not transfer, and this spike applied it anyway. claude-code
    never receives a temperature at all — `claude_cli_args` does not pass one
    and the CLI has no flag for it (`byj-gly.126`) — so two runs of one arm
    over the same 26 examples came back 0.769 and 0.692, and for
    `openai-null-prompt` 0.731 and 0.962. A 0.231 swing is larger than any
    between-arm difference this spike has ever reported. **On claude-code pass
    `:repeats 5` or more, and read a single pass as an anecdote.**
  - The labels are SILVER: opus's own past outputs, in the flat shape. That
    makes `tool-name-f1` and `whole-call-f1` agreement-with-opus, not truth
    (the teacher scored 0.295 against them in
    `proposals/1790167828645/report.edn`). `channel-choice` is the one label
    worth leaning on: one bit, and opus either called a tool or answered.

      jolt -A:dev -e \"(require 'brainyard.dev.output-shape-spike) \\
                       (brainyard.dev.output-shape-spike/-main)\"

  A run is ~4 x |val| calls to a local 1.5b model and spends no quota."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.evaluate :as ev]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.optimize :as opt]
            [brainyard.predictor :as predictor]
            [brainyard.programs :as programs]
            [brainyard.signature :as sig]))

(def ^:private predictor-id "react/think-act")
(def ^:private dataset-name "opus-full")

(def ^:private student-label
  "The student the first compile used, resolved as `compile-predictor` does —
  a hand-built `{:provider :ollama :model \"…\"}` carries no `:base-url` and no
  `:format`, so every call fails and both arms report a clean 0.0."
  "ollama/qwen2.5-coder:1.5b-instruct")

;; --- the candidate shape ------------------------------------------------------------

(defn openai-instructions-for
  "`react-instructions.md`, changed only where the shape forces it: the channel
  names, the two output shapes, and the field-consistency rules — which the
  discriminator makes unrepresentable rather than merely discouraged. Every
  other sentence, including the tool-invention warning and the three
  heuristics, is the original's.

  `nullable?` changes only how an unused field is spelled, so the prose and the
  schema agree — a prompt that says \"leave it empty\" against a schema that
  offers `null` is testing neither."
  [nullable?]
  (str/join
   "\n"
   ["You are a CoAct agent. Each iteration, reason briefly about the next step,"
    "then declare `stop_reason` and populate the field it names:"
    ""
    "  1. \"tool_use\"  — TOOL CALL: populate `tool_calls`"
    "  2. \"end_turn\"  — FINAL ANSWER: populate `content`"
    ""
    "The iteration loop terminates on \"end_turn\". You signal completion by"
    "writing the final markdown answer."
    ""
    "NEVER invent a placeholder tool (e.g. `noop`, `none`) to fill a turn. Only call"
    "tools that actually exist — discover real ones with `(search \"keyword\")`. A `tool_calls` entry naming an unbound tool does NOT count as an"
    "action; it is treated as a wasted no-action iteration."
    ""
    "REASON-ONLY STEP: if you are not yet ready to act (still planning), that is"
    "fine — say \"tool_use\" with an EMPTY `tool_calls` and put your plan in your"
    "reasoning. Do NOT fabricate an action just to populate a channel."
    ""
    "IF you cannot finish (missing info, dead-end, need user clarification), still"
    "say \"end_turn\" and populate `content` — explain what you learned, what is"
    "missing, and what you need from the user. That becomes the final response."
    ""
    "---------------------------------------------------------------------------"
    "ACTION CHANNELS — WHEN TO USE EACH"
    "---------------------------------------------------------------------------"
    ""
    "TOOL CHANNEL — use when:"
    "  - A registered tool directly satisfies the sub-goal."
    "  - Several independent lookups can run in the same iteration (list them all)."
    ""
    "  Output shape:"
    "    stop_reason: \"tool_use\""
    (if nullable? "    content: null" "    content: \"\"")
    "    tool_calls: [{\"name\": \"...\","
    "                  \"arguments\": \"{\\\"<arg>\\\": \\\"<val>\\\"}\"}]"
    ""
    "  `arguments` is a JSON OBJECT ENCODED AS A STRING, exactly as a function"
    "  call carries it — not a nested object. Escape the inner quotes."
    ""
    "ANSWER CHANNEL — use when:"
    "  - You have enough evidence to answer, OR"
    "  - You cannot make further progress (include the reason), OR"
    "  - You need user clarification (ask in the answer)."
    ""
    "  When you say \"end_turn\", ALSO set the two self-assessment outputs:"
    "    - `goal_achieved`: true ONLY if the question is fully and accurately"
    "      answered with support from the evidence in `iterations`. Set false if you"
    "      are answering but uncertain, partial, or blocked."
    "    - `next_user_prompt`: one concise line (~12 words max) the USER could send"
    "      next to build on this answer, phrased as the user's own imperative request"
    (if nullable?
      "      (NOT a question back to them). null when no useful follow-up."
      "      (NOT a question back to them). Empty string when no useful follow-up.")
    (if nullable?
      "  Send null for both on \"tool_use\" turns, and null for `content` too."
      "  Leave both at their defaults (false / \"\") on \"tool_use\" turns.")
    ""
    "  Output shape:"
    "    stop_reason: \"end_turn\""
    "    content: \"## Markdown answer\\n- bullet\\n- caveat: ...\""
    "    goal_achieved: true   next_user_prompt: \"add error handling to the script\""
    ""
    "---------------------------------------------------------------------------"
    "EACH ITERATION — FOLLOW THESE STEPS"
    "---------------------------------------------------------------------------"
    ""
    "STEP 1 — REVIEW"
    "Read `iterations`. Each record shows your prior thought, the channel you"
    "took, and the concrete result (tool-results)."
    "Do NOT re-do work whose result is already in the history."
    ""
    "STEP 2 — REASON (captured by chain-of-thought)"
    "Briefly identify the next gap and which channel fits best. The CoT layer"
    "stores this reasoning."
    ""
    "STEP 3 — ACT"
    "Declare `stop_reason` and populate the field it names."
    ""
    "---------------------------------------------------------------------------"
    "CHANNEL DECISION HEURISTICS"
    "---------------------------------------------------------------------------"
    "1. One registered tool, no post-processing                    → \"tool_use\""
    "2. Several independent lookups                                → \"tool_use\" (multiple calls)"
    "3. Ready to answer (or cannot proceed, or need clarification) → \"end_turn\""]))

(def openai-instructions (openai-instructions-for false))
(def openai-null-instructions (openai-instructions-for true))

(def openai-react-instruction
  "`agent/react-instruction` in the candidate's field names. That def is a
  288-character blurb `session-context` embeds under `## Instructions`, and it
  names `tool-calls` and `answer` — the flat shape's fields — so the candidate
  has to override it or be told to fill fields it does not declare. Overriding
  it with the whole instruction block instead would hand the candidate 3.4k
  characters the baseline never sees; this is the same sentence, re-worded."
  (str "You are a tool-only ReAct agent: answer using the `tool_calls` channel and a\n"
       "final `content` only. There is no code-blocks channel — never emit fenced code to\n"
       "execute. To reach a tool beyond the bound set, discover it with `list-tools` /\n"
       "`get-tool-info` and invoke it through `tool_calls`."))

(defn openai-spec-for
  "The candidate. `stop_reason` is declared FIRST after `reasoning` on purpose:
  OpenAI produces keys in schema order, so the model commits to a channel
  before it generates that channel's contents.

  Every property is required and every object closes `additionalProperties`,
  which is what makes this one strict-eligible where the flat shape is not.

  `nullable?` decides how a field the chosen channel does not use is spelled.
  Strict mode requires EVERY property, so \"leave the other channel empty\" has
  to mean something; the first version said the empty string, and on the very
  first profiling run the 1.5b answered `tool_use` and then wrote a fabricated
  markdown answer into `content` anyway, ran to the 8192-token cap, and the
  truncated JSON came back `:malformed` after 64 s. OpenAI's own prescription
  for an optional field under strict is a null union, so the second variant
  gives \"not applicable\" a value the schema endorses instead of one the model
  has to invent."
  [nullable?]
  ;; fields are malli map entries since byj-gly.203; a nullable field is
  ;; [:maybe t], which malli.json-schema writes as oneOf rather than the type
  ;; array this spike was measured with (git show 3a81d04 for that form)
  (let [nullable (fn [t] (if nullable? [:maybe t] t))]
    {:id (if nullable? "react/think-act-openai-null" "react/think-act-openai")
   :strategy :cot
   :description "Reason briefly, then either call tools or write the final answer."
   :instructions (openai-instructions-for nullable?)
   :inputs
   [[:question {:desc "User question"} :string]
    [:iterations {:desc "Full iteration history (capped + truncated for context budget)"} [:vector :any]]]
   :outputs
   [[:stop_reason
     {:desc "\"tool_use\" when calling tools this iteration; \"end_turn\" when giving the final answer."}
     [:enum "tool_use" "end_turn"]]
    [:tool_calls
     {:desc (str "Tool invocations to run this iteration. " (if nullable? "null" "Empty") " unless stop_reason is \"tool_use\".")}
     [:vector [:map {:closed true} [:name :string] [:arguments :string]]]]
    [:content
     {:desc (str "Answer to the question. " (if nullable? "null" "Empty") " unless stop_reason is \"end_turn\".")}
     (nullable :string)]
    [:goal_achieved
     {:desc "Set ONLY on \"end_turn\": true iff the question is fully and accurately answered with evidence support. false signals you are answering but not yet confident — the loop may refine."}
     (nullable :boolean)]
    [:next_user_prompt
     {:desc (str "Set ONLY on \"end_turn\": a concise one-line follow-up the USER could send next to build on this answer. "
                 "Phrase it as the user's own request (imperative, ~12 words max), not a question back to the user. "
                 (if nullable? "null" "Empty string") " when no useful follow-up exists.")}
     (nullable :string)]]}))

(def openai-spec (openai-spec-for false))
(def openai-null-spec (openai-spec-for true))

;; --- back to the shape the labels are in ---------------------------------------------

(defn- parse-args
  "`arguments` as the loop would have to take it. A JSON object encoded as a
  string is the shape OpenAI's own `function.arguments` carries, and the shape
  this candidate asks for; a model that sends a bare object anyway is accepted
  rather than scored down, since `byj-gly.29` already made object tool params
  take JSON strings and the tolerance runs both ways."
  [v]
  (cond
    (map? v) {:ok? true :args v :as-declared? false}
    (string? v) (let [t (str/trim v)]
                  (if (str/blank? t)
                    {:ok? true :args {} :as-declared? true}
                    (try (let [parsed (json/read-str t)]
                           (cond-> {:ok? (map? parsed) :args (if (map? parsed) parsed {}) :as-declared? true}
                             (not (map? parsed)) (assoc :bad t)))
                         (catch Throwable e
                           {:ok? false :args {} :as-declared? true :bad t
                            :why (ex-message e)}))))
    :else {:ok? false :args {} :as-declared? false}))

(defn openai->flat
  "The candidate's outputs projected into the shape the labels are in, so one
  metric scores both arms. `stop_reason` CLAMPS: it is the discriminator, and
  clamping is the behaviour under test — `resolve-channel-conflicts-action`
  does the same job for the flat shape, with a notice and a wasted iteration.
  What the model contradicted is reported separately rather than hidden."
  [outputs]
  (let [tool? (= "tool_use" (:stop_reason outputs))
        raw (vec (:tool_calls outputs))
        parsed (mapv (fn [c] (assoc (parse-args (:arguments c)) :name (str (:name c)))) raw)]
    {:flat {:tool-calls (if tool?
                          (mapv (fn [{:keys [name args]}] {:tool-name name :tool-args args}) parsed)
                          [])
            :answer (if tool? "" (str (:content outputs)))
            :goal-achieved (if tool? false (boolean (:goal_achieved outputs)))
            :next-user-prompt (if tool? "" (str (:next_user_prompt outputs)))}
     :diagnostics {:bad-args (vec (keep (fn [c] (when (:bad c) (select-keys c [:name :bad :why]))) parsed))
                   :args-parsed (count (filter :ok? parsed))
                   :args-total (count parsed)
                   :args-as-declared (count (filter :as-declared? parsed))
                   ;; the model named a channel and then filled the other one.
                   ;; A "tool_use" with an empty `tool_calls` is NOT one — that
                   ;; is the reason-only step the instructions allow.
                   :answered-while-calling? (boolean (and tool? (seq (str/trim (str (:content outputs))))))
                   :called-while-answering? (boolean (and (not tool?) (seq raw)))
                   :unknown-stop-reason (when-not (#{"tool_use" "end_turn"} (:stop_reason outputs))
                                          (:stop_reason outputs))}}))

(defn- project
  "Both arms, in the labels' shape, plus whatever the projection noticed."
  [shape outputs]
  (if (not= :flat shape)
    (openai->flat outputs)
    {:flat (select-keys outputs [:tool-calls :answer :goal-achieved :next-user-prompt])
     :diagnostics {}}))

;; --- metrics --------------------------------------------------------------------------

(defn- channel-choice
  "One bit: did it take the channel opus took? The dataset's cheapest reliable
  label — the silver answer text is opus's prose and the silver args are opus's
  arguments, but 'called a tool' versus 'answered' is not a matter of wording."
  [labels flat]
  (if (= (empty? (:tool-calls labels)) (empty? (:tool-calls flat))) 1.0 0.0))

(def ^:private tool-name-f1 (ev/set-f1 :tool-calls :tool-name))
(def ^:private whole-call-f1 (ev/set-f1 :tool-calls))

(defn bound-tool-names
  "Every tool name a turn could actually call, from the same registry
  `session-context` lists. Label-free: whether a named tool EXISTS is a fact
  about this repository, not about what opus once chose, so it is the one
  quality measure that cannot be inflated by scoring opus against its own past
  outputs. `strip-unbound-tools-action` (agent.clj:548-556) drops these calls
  and costs the turn an iteration, so the rate is the cost of the shape."
  [lm]
  (set (keys @(:tools (agent/make-ctx {:lm lm :config {}})))))

(defn- unbound-count
  "Emitted calls naming no bound tool, and calls emitted. Aggregate rather than
  paired: an example where the model called nothing has no rate to average, and
  filling that hole with 1.0 would let silence look like precision."
  [bound flat]
  (let [names (map :tool-name (:tool-calls flat))]
    {:emitted (count names)
     :unbound (count (remove #(contains? bound (str %)) names))
     :unbound-names (vec (remove #(contains? bound (str %)) names))}))

(defn- score-row
  "Every metric for one example, from one projection."
  [ex flat]
  (let [r {:outputs flat}]
    {:channel-choice (channel-choice (:labels ex) flat)
     :tool-name-f1 (double (tool-name-f1 ex r nil))
     :whole-call-f1 (double (whole-call-f1 ex r nil))}))

(def metric-keys [:channel-choice :tool-name-f1 :whole-call-f1])

;; --- the prompt a real turn would build ------------------------------------------------

(defn zones-for
  "The two STABLE system-prompt zones the `:react/think-act` node declares
  (`react.edn:40-42`), built the way a turn builds them — `agent-core` and
  `session-context`, the latter carrying the bound tools' full specs.

  Without them the model is asked which tool to call and never told which
  tools exist: the first smoke run had the 1.5b inventing `list_files` where
  the label said `grep`, which makes a tool-name score a measure of guessing.
  `programs/predictor-program` passes no zones, so the compile that produced
  `proposals/1790167828645` had the same gap and its tool-call F1 should be
  read the same way.

  `:agent-instruction` is set per shape because `session-context` embeds
  `agent/react-instruction` under `## Instructions`, and that blurb names
  `tool-calls` and `answer`; left at its default the candidate would be told to
  fill fields it does not declare. It is swapped for `openai-react-instruction`
  — the same sentence, same length — and not for the whole instruction block,
  which would make the candidate's system prompt 3.4k characters longer than
  the baseline's and measure prompt length instead of shape.

  The other two zones the node declares — `history-context`, `user-context` —
  come from st-memory during a turn and cannot be rebuilt from a dataset row.
  The tool registry is also TODAY's, not the one the sessions ran with."
  [shape lm]
  (let [ctx (agent/make-ctx
             {:lm lm
              :config (cond-> {} (not= :flat shape) (assoc :agent-instruction openai-react-instruction))})
        z (:zones ctx)]
    [["agent-core" (:agent-core z)]
     ["session-context" (:session-context z)]]))

(defn- program
  "`programs/predictor-program`, plus the zones it does not pass."
  [p lm cfg zones]
  (fn [inputs]
    (let [trace (atom [])
          on-event (fn [e d] (when (= :predictor/call e) (swap! trace conj d)))
          result (predictor/run p inputs (cond-> {:lm lm :lm-tiers (:agent-lm-tiers cfg)
                                                  :on-event on-event}
                                           zones (assoc :zones zones)))]
      {:result result :trace @trace})))

;; --- the 2x2 -----------------------------------------------------------------------

(def arms
  "Shape crossed with enforcement. `:flat-prompt` is today's behaviour on
  ollama and is the baseline every other arm is compared against.

  `:openai-null` differs from `:openai` only in how an unused field is spelled
  — `null` rather than the empty string — which is the difference the runaway
  in the first profiling run was about."
  [{:key :flat-prompt :shape :flat :enforce? false}
   {:key :flat-schema :shape :flat :enforce? true}
   {:key :openai-prompt :shape :openai :enforce? false}
   {:key :openai-schema :shape :openai :enforce? true}
   {:key :openai-null-prompt :shape :openai-null :enforce? false}
   {:key :openai-null-schema :shape :openai-null :enforce? true}])

(defn- spec-for [shape]
  (case shape :openai openai-spec :openai-null openai-null-spec nil))

(defn- openai-shape? [shape] (not= :flat shape))

(defn strict-eligibility
  "Which shape OpenAI would accept `strict:true` for. A static fact about the
  two schemas — no call, no model, nothing to average. It is the reason the
  candidate exists: `tool-args` is an open object, and strict mode forbids one,
  so `react/think-act` has never been enforced at the API level."
  [flat-p openai-p openai-null-p]
  (into {} (for [[k p] [[:flat flat-p] [:openai openai-p] [:openai-null openai-null-p]]]
             [k (sig/strict-eligible?
                 (sig/output-json-schema (sig/cot-outputs (:signature p))))])))

(defn- mean [xs] (if (seq xs) (/ (reduce + (map double xs)) (count xs)) 0.0))

(defn- scores-for
  "One metric's per-example vector for an arm, in example order. An example
  that errored has no row and scores 0.0 — `run-one`'s rule, since a malformed
  reply is the program being wrong rather than the harness failing."
  [stash arm-key n metric-key]
  (mapv (fn [i] (double (get-in @stash [[arm-key i] :row metric-key] 0.0))) (range n)))

(defn- diagnostics-for [stash arm-key n]
  (let [rows (keep (fn [i] (get-in @stash [[arm-key i] :diagnostics])) (range n))
        sum (fn [k] (reduce + (keep k rows)))]
    {:args-parsed (sum :args-parsed)
     :args-total (sum :args-total)
     :args-as-declared (sum :args-as-declared)
     :answered-while-calling (count (filter :answered-while-calling? rows))
     :called-while-answering (count (filter :called-while-answering? rows))
     :emitted (sum :emitted)
     :unbound (sum :unbound)
     :unbound-names (vec (mapcat :unbound-names rows))
     :bad-args (vec (mapcat :bad-args rows))
     :unknown-stop-reasons (frequencies (keep :unknown-stop-reason rows))}))

(def default-cache
  "Where a finished arm is parked. A full run is ~400 calls to a local model and
  takes the best part of an hour; the first one was killed at minute 40 by
  memory pressure elsewhere on the machine and left nothing behind, so an arm
  is written the moment it finishes and a later run reuses it. Delete the file
  to force a re-score."
  "/tmp/output-shape-arms.edn")

(defn- read-cache [path]
  (try (when (.isFile (io/file path)) (edn/read-string (slurp path))) (catch Throwable _ nil)))

(defn- cache-key
  "Everything that changes what an arm measured. The MODEL is in here because
  it is the one thing a reader would never think to check: without it a run
  switched from opus to haiku silently reuses the opus rows it already has."
  [arm-key n zones? enforce? lm-label]
  [arm-key n (boolean zones?) (boolean enforce?) lm-label])

(defn run
  "Score every arm on the split, then ask the paired question of each against
  `:flat-prompt`.

  `:limit` truncates the split — for a smoke run that proves the wiring before
  spending the whole thing. `:cache` is where finished arms are parked; `false`
  disables it."
  [& {:keys [repeats limit zones? split cache lm arm-keys seed]
      :or {repeats 1 zones? true split :val+test cache default-cache seed 0}}]
  (let [cfg (config/load-config {})
        label (or lm student-label)
        base-lm (or (llm/parse-lm-label label)
                    (throw (ex-info (str "unresolvable " label) {})))
        bound (bound-tool-names base-lm)
        arms (if (seq arm-keys) (filterv #(contains? (set arm-keys) (:key %)) arms) arms)
        roots (predictor/default-roots (or (:project-dir cfg) ".") "predictors")
        flat-p (predictor/load-predictor predictor-id roots)
        openai-p (predictor/compile-spec openai-spec)
        openai-null-p (predictor/compile-spec openai-null-spec)
        ds (programs/load-dataset cfg predictor-id dataset-name)
        ;; split BEFORE the index goes on: `split` buckets by content hash, and
        ;; a `:meta` key would move examples between buckets.
        ;;
        ;; The default is val AND test. Holding test back exists to keep a
        ;; selection honest, and this comparison selects nothing on the data —
        ;; six arms fixed in advance, one baseline, no search — so the split
        ;; buys nothing here and the power at n=35 is worth more. `:split :val`
        ;; reproduces the set `proposals/1790167828645` was scored on.
        parts (ev/split (:examples ds))
        val0 (vec (case split
                    :val (:val parts)
                    :all (:examples ds)
                    (concat (:val parts) (:test parts))))
        ;; a stratified sample, not the first n: the split is ~2:1 tool-turns
        ;; to answer-turns and `take` would hand a small run whichever class
        ;; the hash happened to order first — the smoke run at limit 4 drew
        ;; 4 tool-turns and 0 answer-turns, so channel-choice had nothing to
        ;; be wrong about in one direction
        val0 (if limit
               (let [shuffled #(opt/shuffle-seeded % seed)
                     [tool answer] ((juxt filter remove) #(seq (:tool-calls (:labels %))) val0)
                     want-tool (int (Math/round (* (double limit) (/ (count tool) (max 1 (count val0))))))
                     want-answer (- limit want-tool)]
                 (vec (concat (take want-tool (shuffled (vec tool)))
                              (take want-answer (shuffled (vec answer))))))
               val0)
        val (vec (map-indexed (fn [i ex] (assoc-in ex [:meta :spike/idx] i)) val0))
        n (count val)
        gold-tool (count (filter #(seq (:tool-calls (:labels %))) val))
        stash (atom {})
        _ (println (str "val split: " n " examples (" gold-tool " tool, " (- n gold-tool) " answer)"
                        "  dataset " (ev/dataset-hash val0)))
        _ (println (str "strict-eligible?: " (pr-str (strict-eligibility flat-p openai-p openai-null-p))))
        cached (atom (or (when cache (read-cache cache)) {}))
        rows (vec
              (for [{:keys [key shape enforce?]} arms]
                (let [ck (cache-key key n zones? enforce? label)]
                  (if-let [hit (get @cached ck)]
                    (do (println (str "  " (name key) " — from cache ("
                                      (format "%.3f" (get-in hit [:means :channel-choice])) ")"))
                        hit)
                    (let [p (if-let [spec (spec-for shape)] (predictor/compile-spec spec) flat-p)
                          lm (cond-> base-lm enforce? (assoc :supports-json-schema? true))
                          metric (fn [ex result _trace]
                                   (let [{:keys [flat diagnostics]} (project shape (:outputs result))
                                         row (score-row ex flat)]
                                     (swap! stash assoc [key (get-in ex [:meta :spike/idx])]
                                            {:row row :flat flat
                                             :diagnostics (merge diagnostics (unbound-count bound flat))})
                                     (:channel-choice row)))
                          prog (program p lm cfg (when zones? (zones-for shape base-lm)))
                          _ (println (str "  scoring " (name key) " …"))
                          rep (ev/evaluate prog val metric :repeats repeats)
                          malformed (count (filter #(= :malformed (:error-class %)) (:per-example rep)))
                          scores (into {} (for [m metric-keys] [m (scores-for stash key n m)]))
                          row {:arm key
                               :shape shape
                               :enforced? enforce?
                               :parse-ok (double (/ (- n malformed) (max 1 n)))
                               :errors (:errors rep)
                               :malformed malformed
                               :calls (:calls rep)
                               :tokens (:tokens rep)
                               :elapsed-ms (:elapsed-ms rep)
                               :diagnostics (diagnostics-for stash key n)
                               :scores scores
                               :means (update-vals scores mean)}]
                      (println (str "    channel-choice " (format "%.3f" (:score rep))
                                    ", " (:errors rep) " errors (" malformed " malformed), "
                                    (:calls rep) " calls, " (quot (:elapsed-ms rep) 1000) "s"))
                      ;; park it before the next arm starts: a run that dies at
                      ;; arm five should not cost arms one to four again
                      (when cache
                        (swap! cached assoc ck row)
                        (spit cache (pr-str @cached)))
                      row)))))
        with-means rows
        baseline (first (filter #(= :flat-prompt (:arm %)) with-means))
        vs (fn [r m]
             (ev/compare-scores {:score (get-in r [:means m]) :example-scores (get-in r [:scores m])}
                                {:score (get-in baseline [:means m]) :example-scores (get-in baseline [:scores m])}))]
    {:n n
     :gold-tool gold-tool
     :dataset (ev/dataset-hash val0)
     :student label
     :repeats repeats
     :sampling (if (llm/samples-freely? base-lm) :free :fixed)
     :zones? zones?
     :split split
     :limit limit
     :bound-tools (count bound)
     :strict-eligible (strict-eligibility flat-p openai-p openai-null-p)
     :rows with-means
     :comparisons (into {} (for [r with-means :when (not= :flat-prompt (:arm r))]
                             [(:arm r) (into {} (for [m metric-keys] [m (vs r m)]))]))}))

;; --- reading it ----------------------------------------------------------------------

(defn- verdict
  "What `compare-scores` decided, in words. `:tested? false` means nothing
  could be weighed, and the honest report of that is the bare difference —
  not a win."
  [{:keys [better? diff margin tested?]}]
  (str (if (neg? diff) "-" "+") (format "%.3f" (Math/abs (double diff)))
       (cond
         (not tested?) " (untested)"
         better? (str " WINS (> " (format "%.3f" (double (or margin 0.0))) ")")
         :else (str " no (needs > " (format "%.3f" (double (or margin 0.0))) ")"))))

(defn report [{:keys [n gold-tool dataset student repeats zones? sampling strict-eligible rows comparisons]}]
  (println)
  (println (str "react/think-act output shape — " n " val examples ("
                gold-tool " tool / " (- n gold-tool) " answer), dataset " dataset))
  (println (str "student " student ", " repeats " repeat(s); baseline is flat-prompt (today)"))
  (println (str "stable zones (agent-core + session-context, tools listed): " (boolean zones?)))
  (when (= :free sampling)
    (println)
    (println (str "  !! " student " takes no temperature (byj-gly.126): identical calls differ."))
    (when (< (long (or repeats 1)) 2)
      (println (str "  !! " repeats " pass — this table includes the sampler's own movement."))
      (println "  !! Two runs of one arm here once gave 0.731 and 0.962. Use :repeats 5+.")))
  (println)
  (println (str "strict-eligible?  " (pr-str strict-eligible) "   <- static, no calls"))
  (println)
  (println (format "%-16s %7s %8s %8s %8s %7s %6s" "arm" "parse" "channel" "toolF1" "callF1" "errs" "s"))
  (doseq [r rows]
    (println (format "%-16s %7.3f %8.3f %8.3f %8.3f %7d %6d"
                     (name (:arm r)) (:parse-ok r)
                     (get-in r [:means :channel-choice])
                     (get-in r [:means :tool-name-f1])
                     (get-in r [:means :whole-call-f1])
                     (:errors r) (quot (:elapsed-ms r) 1000))))
  (println)
  (println "paired against flat-prompt, per example:")
  (doseq [[arm ms] comparisons]
    (println (str "  " (name arm)))
    (doseq [m metric-keys]
      (println (format "    %-14s %s" (name m) (verdict (get ms m))))))
  (println)
  (println "label-free rates (cannot be inflated by opus agreeing with itself):")
  (doseq [r rows]
    (let [d (:diagnostics r)]
      (println (format "  %-20s malformed %d/%d   unbound tools %d/%d   answered-while-calling %d"
                       (name (:arm r)) (:malformed r) n
                       (:unbound d 0) (:emitted d 0)
                       (:answered-while-calling d 0)))))
  (println)
  (println "projection diagnostics (openai arms):")
  (doseq [r rows :when (not= :flat (:shape r))]
    (let [d (:diagnostics r)]
      (println (str "  " (name (:arm r))
                    ": arguments parsed " (:args-parsed d) "/" (:args-total d)
                    ", sent as the declared string " (:args-as-declared d) "/" (:args-total d)
                    ", answered while calling " (:answered-while-calling d)
                    ", called while answering " (:called-while-answering d)
                    (when (seq (:unknown-stop-reasons d))
                      (str ", unknown stop_reason " (pr-str (:unknown-stop-reasons d))))))))
  (println))

(defn -main [& args]
  (let [opts (apply hash-map (map edn/read-string args))
        result (apply run (mapcat identity opts))]
    (report result)
    (let [out (io/file "/tmp" (str "output-shape-" (System/currentTimeMillis) ".edn"))]
      (spit out (with-out-str (pp/pprint (update result :rows #(mapv (fn [r] (dissoc r :scores)) %)))))
      (println (str "full result: " (.getPath out))))
    result))
