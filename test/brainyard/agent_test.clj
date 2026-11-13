;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.agent-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.flow :as flow]
            [brainyard.instructions :as instructions]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]
            [brainyard.project-loops :as project-loops]
            [brainyard.signature :as sig]
            [brainyard.tools :as tools]
            [brainyard.version :as version]
            [jolt.process :as p]))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(def ^:private scratch
  "A project of this test's own. Without one the working dir is `.` and
  `:project-dir` resolves to the git root, so every turn leaves a trajectory in
  the DEVELOPER'S repository — 615 of them in one afternoon, which
  `session$analytics` then read as this project's work (`byj-gly.88`).
  `test-runner/run` fails the suite if this comes back."
  (delay (let [d (io/file "target" "agent-test-scratch")] (.mkdirs d) (.getPath d))))

(defn- run [replies & {:as opts}]
  (let [events (atom [])
        lm {:provider :mock :format :mock :responses (atom (vec replies))}
        result (agent/ask "q" (merge {:lm lm
                                      :config {:max-iterations 5 :working-dir "."
                                               :project-dir @scratch}
                                      :on-event (fn [e d] (swap! events conj [e d]))}
                                     opts))]
    (assoc result :events @events :lm lm)))

(deftest a-batch-tags-what-each-call-prints
  ;; byj-gly.364: each call of a batch prints under its own place
  (let [{:keys [events answer]} (run [(reply {:tool-calls [{:tool-name "bash" :tool-args {:command "echo first"}}
                                                           {:tool-name "bash" :tool-args {:command "echo second"}}]})
                                      (reply {:answer "done"})])]
    (is (= "done" answer))
    (is (= [["first" 0] ["second" 1]]
           (keep (fn [[e d]] (when (= :agent.tool/output e) [(:line d) (:call d)])) events)))))

;; --- bt ---------------------------------------------------------------------------

(deftest bt-semantics
  (let [log (atom [])
        reg {:ok (fn [_] (swap! log conj :ok))
             :fail (fn [_] :failure)
             :boom (fn [_] (throw (ex-info "boom" {})))
             :yes (constantly true)
             :done? (fn [_] (>= (count @log) 3))}
        ctx {:registry reg :st-memory (atom {}) :config {:n 10}}]
    (is (= :success (bt/tick ctx [:sequence {} [:action {:fn :ok}] [:condition {:fn :yes}]])))
    (is (= :failure (bt/tick ctx [:sequence {} [:action {:fn :fail}] [:action {:fn :ok}]])))
    (is (= :success (bt/tick ctx [:fallback {} [:action {:fn :boom}] [:action {:fn :ok}]])))
    (is (= "boom" (:message (:bt/error @(:st-memory ctx)))))
    (reset! log [])
    (is (= :success (bt/tick ctx [:repeat {:max-n :config/n :until :done?} [:action {:fn :ok}]])))
    (is (= 3 (count @log)))
    (is (= "unregistered fns: :nope" (bt/validate [:sequence {} [:action {:fn :nope}]] reg)))
    (is (nil? (bt/validate (agent/load-tree nil) agent/registry)))))

;; --- json / signature ------------------------------------------------------------

(deftest lenient-json
  (is (= {:a 1} (json/parse-json-response "```json\n{\"a\": 1}\n```")))
  (is (= {:b "}"} (json/parse-json-response "Sure! {\"a\": 1} then {\"b\": \"}\"}")))
  (is (:no-json-envelope? (ex-data (try (json/parse-json-response "just prose")
                                        (catch Throwable e e))))))

(deftest a-line-break-escaped-twice-is-a-line-break
  ;; haiku's JSON said "…to:\\n\\n1. Check…": decoded, a backslash and an n,
  ;; and the answer was drawn in one line with them in it (byj-gly.194)
  (let [s (:signature (predictor/load-predictor "react/think-act" []))
        answer #(:answer (sig/normalize-outputs s (json/read-str %)))]
    (is (= "I would need to:\n\n1. Check \"config\"\n2. Ask"
           (answer "{\"answer\": \"I would need to:\\\\n\\\\n1. Check \\\\\\\"config\\\\\\\"\\\\n2. Ask\"}")))
    (is (= "Split on `\\n` with str/split." (answer "{\"answer\": \"Split on `\\\\n` with str/split.\"}"))
        "one escaped \\n mentioned in passing is what it says")
    (is (= "a\nb and a literal \\n\\n" (answer "{\"answer\": \"a\\nb and a literal \\\\n\\\\n\"}"))
        "nor is anything touched in an answer that has real line breaks")))

(deftest a-reply-is-held-to-its-schema
  ;; the JSON Schema said so, and nothing checked it (byj-gly.203)
  (let [sig #(:signature (predictor/load-predictor % []))
        norm (fn [id reply] (try (sig/normalize-outputs (sig id) reply)
                                 (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (testing "a value outside its enum, or a required key missing, is malformed and says why"
      (is (= {:kind :malformed :problems ["`verdict` should be either \"COMPLETE\", \"HALLUCINATED\" or \"INCOMPLETE\""]}
             (norm "react/evaluate-answer" {:reasoning "r" :verdict "MAYBE" :detail "d"})))
      (is (= ["missing required `verdict`"]
             (:problems (norm "react/evaluate-answer" {:reasoning "r" :detail "d"})))
          "an enum has no empty value to stand in for it")
      (is (= #{"missing required `items.1.text`" "`items.1.via` should be either \"edit\", \"check\", \"read\" or \"manual\""}
             (set (:problems (norm "todo/from-plan" {:reasoning "r" :slug "s" :items [{:text "a"} {:via "fly"}]}))))))
    (testing "a null is the field left out, as before the reply was checked (byj-gly.215)"
      (is (= {:reasoning "r" :tool-calls [{:name "bash" :args {}}] :answer "" :goal-achieved false :next-user-prompt ""}
             (norm "react/think-act" {:reasoning "r" :tool-calls [{:name "bash" :args nil}]
                                      :answer nil :goal-achieved nil :next-user-prompt nil})))
      (is (= [] (:tool-calls (norm "react/think-act" {:reasoning "r" :tool-calls nil :answer "a"}))))
      (testing "and a nullable field's null is null, not a field missing (byj-g5n.10)"
        (let [s {:outputs [[:scope {:desc "s"} [:maybe [:enum "suite" "filtered"]]]]}]
          (is (= {:scope nil} (dissoc (sig/normalize-outputs s {:scope nil}) :reasoning)))
          (is (= {:scope nil} (dissoc (sig/normalize-outputs s {}) :reasoning)))
          (is (= {:scope "suite"} (dissoc (sig/normalize-outputs s {:scope "suite"}) :reasoning))))))
    (testing "an enum member is read whatever its case, so the gate still refuses (byj-gly.216)"
      (is (= "HALLUCINATED" (:verdict (norm "react/evaluate-answer" {:reasoning "r" :verdict " hallucinated " :detail nil}))))
      (is (= "INCOMPLETE" (:verdict (norm "react/evaluate-answer" {:reasoning "r" :verdict "Incomplete" :detail "d"}))))
      (is (= :malformed (:kind (norm "react/evaluate-answer" {:reasoning "r" :verdict "MAYBE" :detail "d"})))
          "and a verdict that is none of them is still malformed"))
    (testing "a call's arguments by another name, and keys beside them, are not malformed (byj-gly.230)"
      (is (= [{:name "read-file" :args {:path "a"}} {:name "list-dir" :args {}}]
             (:tool-calls (norm "react/think-act" {:tool-calls [{:name "read-file" :arguments {:path "a"}}
                                                                 {:name "list-dir" :args {} :id "call_1"}]}))))
      (is (true? (:satisfied (norm "todo/eval" {:reasoning "r" :satisfied "yes" :answer "a"}))))
      (is (= "edit" (get-in (norm "todo/from-plan" {:reasoning "r" :slug "s" :items [{:text "t" :via "Edit"}]}) [:items 0 :via]))))
    (testing "what the repair always did still happens first"
      (is (= {:reasoning "" :slug "" :items []} (norm "todo/from-plan" {}))
          "a string, a boolean or a list left out is its empty value")
      (is (= {:reasoning "r" :satisfied true :answer "{\"a\":1}"}
             (norm "todo/eval" {:reasoning "r" :satisfied "TRUE" :answer {:a 1}}))
          "a boolean from its text; a non-string where a string is wanted, as JSON")
      (is (= [{:name "read-file" :args {:path "a"}}]
             (:tool-calls (norm "react/think-act" {:tool-calls [{:name "read-file" :args "{\"path\": \"a\"}"}]})))
          "tool args sent as JSON text are parsed")
      (is (= [{:name "t" :args {}}]
             (:tool-calls (norm "react/think-act" {:tool-calls "[{\"name\": \"t\", \"args\": {}}]"})))
          "a list sent as JSON text is parsed")
      (is (= [{:name "t" :args {:a 1}}]
             (:tool-calls (norm "react/think-act" {:tool-calls "[{\"tool-name\": \"t\", \"tool-args\": {\"a\": 1}}]"}))
             (:tool-calls (norm "react/think-act" {:tool-calls [{:tool-name "t" :tool-args {:a 1}}]})))
          "a call written with brainyard's tool-name/tool-args is read as name/args, however it came"))
    (testing "a malformed reply reaches the model as a FORMAT ERROR naming the problem"
      (let [replies (atom [(json/write-str {:reasoning "r" :tool-calls [{:tool-name "list-dir" :tool-args 5}] :answer ""})
                           (json/write-str {:reasoning "r" :tool-calls [] :answer "done" :goal-achieved true})])
            prompts (atom [])
            dir (str "target/agent-test/held-" (System/currentTimeMillis))
            r (agent/ask "q" {:lm {:provider :mock :format :mock
                                   :responses (fn [msgs] (swap! prompts conj (:content (last msgs)))
                                                (let [[x] (swap-vals! replies #(vec (rest %)))] (first x)))}
                              :config {:working-dir dir :project-dir dir}})]
        (is (= "done" (:answer r)))
        (is (str/includes? (second @prompts) "FORMAT ERROR"))
        (is (str/includes? (second @prompts) "`tool-calls.0.args`"))))))

(deftest output-normalization
  (let [s (:signature (predictor/load-predictor "react/think-act" []))]
    (is (= {:reasoning "" :tool-calls [{:name "bash" :args {:command "ls"}}]
            :answer "" :goal-achieved false :next-user-prompt ""}
           (sig/normalize-outputs s {:tool-name "bash" :tool-args {:command "ls"}})))
    (is (true? (:goal-achieved (sig/normalize-outputs s {:goal-achieved "true"}))))
    (let [[system user] (sig/build-messages s {:question "Q" :iterations "[]"} [["Tools" "T"]])]
      (is (str/includes? (:content system) "1. `reasoning` (string)"))
      (is (str/ends-with? (:content system) "## Tools\nT"))
      (is (str/starts-with? (:content user) "question: Q\niterations: []"))
      (is (str/includes? (:content user) "starting with the field `reasoning`, then `tool-calls`")))))

;; --- tools -----------------------------------------------------------------------

(deftest update-file-takes-the-names-models-send
  ;; byj-gly.394: 130 update-file calls in the logs were refused for sending
  ;; old/new (or Claude Code's old_string/new_string), not pattern/replacement
  (let [by-name (tools/tools-by-name tools/builtin-tools)
        dir (str (System/getProperty "user.dir") "/target/tool-test-aliases")
        ctx {:config {:working-dir dir :project-dir dir}}
        call #(tools/call-tool (by-name "update-file") %1 ctx)
        file #(slurp (io/file dir "a.txt"))]
    (.mkdirs (io/file dir))
    (spit (io/file dir "a.txt") "one two two")
    (testing "old/new, and the JSON spelling a model sends"
      (is (= {:path "a.txt" :replaced 1} (call {:path "a.txt" :old "one" :new "1"})))
      (is (= {:path "a.txt" :replaced 2} (call {"path" "a.txt" "old_string" "two" "new_string" "2" "replace_all" true})))
      (is (= "1 2 2" (file))))
    (testing "both names with one value is fine; with two it is refused, naming both"
      (is (= {:path "a.txt" :replaced 1} (call {:path "a.txt" :pattern "1" :old "1" :new "one"})))
      (let [r (call {:path "a.txt" :pattern "one" :old "2" :replacement "x"})]
        (is (str/includes? (:error-message r) "give `pattern` or `old`, not both")))
      (is (= "one 2 2" (file)) "nothing was written for the refused one"))
    (testing "a missing argument says the other names it may come by"
      (let [r (call {:path "a.txt"})]
        (is (str/includes? (:error-message r) "missing required `pattern` (or `old`, `old-string`, `old_string`, `oldstring`)"))
        (is (str/includes? (:error-message r) "missing required `replacement` (or `new`, `new-string`, `new_string`, `newstring`)"))))
    (testing "what the model reads names them"
      (is (str/includes? (pr-str (:input-schema (by-name "update-file"))) "also taken as `old`")))
    (testing "a turn records the call as the model sent it, and the edit is made"
      (spit (io/file dir "a.txt") "alpha")
      (let [r (agent/ask "q" {:lm {:provider :mock :format :mock
                                   :responses (atom [(json/write-str {:reasoning "r" :tool-calls [{:name "update-file" :args {:path "a.txt" :old "alpha" :new "beta"}}] :answer ""})
                                                     (json/write-str {:reasoning "r" :tool-calls [] :answer "done"})])}
                              :config {:working-dir dir :project-dir dir :max-iterations 3}})
            t (first (:tool-results (first (:trajectory r))))]
        (is (= "beta" (file)))
        (is (= {:path "a.txt" :old "alpha" :new "beta"} (:tool-args t)))))))

(deftest tool-calls
  (let [by-name (tools/tools-by-name tools/builtin-tools)
        dir (str (System/getProperty "user.dir") "/target/tool-test")
        ctx {:config {:working-dir dir :project-dir dir}}
        call #(tools/call-tool (by-name %1) %2 ctx)]
    (.mkdirs (io/file dir))
    (is (= {:path "a.txt" :chars 11} (call "write-file" {"path" "a.txt" "content" "hello\nworld"})))
    (is (= "hello\nworld" (:content (call "read-file" {:path "a.txt"}))))
    (is (= "2\tworld" (:content (call "read-file" {:path "a.txt" :offset "2" :limit 1}))))
    (is (= {:path "a.txt" :replaced 1} (call "update-file" {:path "a.txt" :pattern "world" :replacement "jolt"})))
    (is (= "hello\njolt" (slurp (io/file dir "a.txt"))))
    (is (:error (call "update-file" {:path "a.txt" :pattern "nope" :replacement ""})))
    (is (= {:status "completed" :exit-code 0 :output "hello\njolt"}
           (call "bash" {:command "cat a.txt"})))
    (is (= "failed" (:status (call "bash" {:command "exit 3"}))))
    (is (= "timeout" (:status (call "bash" {:command "sleep 5" :timeout 200}))))
    (testing "what it prints is said as it prints, tagged with its place in the batch (byj-gly.364)"
      (let [seen (atom [])
            r (tools/call-tool (by-name "bash") {:command "echo one; echo two"}
                               (assoc ctx :tool-call-index 1 :on-event (fn [e d] (when (= :agent.tool/output e) (swap! seen conj d)))))]
        (is (= "completed" (:status r)))
        (is (= [{:tool-name "bash" :line "one" :call 1} {:tool-name "bash" :line "two" :call 1}] @seen))))
    (testing "a command stopped by its timeout says what it printed first (byj-gly.363): a login's code came at once, then nothing"
      (let [r (call "bash" {:command "echo 'Open http://relay/device'; echo 'and enter the code ABCD-1234' >&2; sleep 5" :timeout 1500})]
        (is (= "timeout" (:status r)))
        (is (str/includes? (:output r) "command timed out after 1500 ms"))
        (is (str/includes? (:output r) "What it printed before it was stopped:\nOpen http://relay/device\nand enter the code ABCD-1234"))))
    (testing "what a command leaves running in the background is named, and stopped with its session (byj-gly.315.16)"
      (let [mark (str "byjbg" (System/nanoTime))
            r (call "bash" {:command (str "sh -c 'sleep 30; true " mark "' >/dev/null 2>&1 & echo started")})]
        (is (= "completed" (:status r)))
        (is (seq (:left-running r)) "the shell and its sleep, both in the group")
        (is (str/includes? (:output r) "still running, started by this command"))
        (is (seq (str/trim (:out (p/sh {:out :string :err :string} "pgrep" "-f" mark)))) "it is still running after the call")
        (is (pos? (tools/stop-background!)))
        (Thread/sleep 300)
        (is (str/blank? (:out (p/sh {:out :string :err :string} "pgrep" "-f" mark))) "and stopped with the session"))
      (is (not (contains? (call "bash" {:command "echo plain"}) :left-running)) "a command that leaves nothing says nothing"))
    (testing "a timed-out command and every process it started are stopped, and the result says so (byj-gly.315.11)"
      (let [mark (str "byjtimeout" (System/nanoTime))
            r (call "bash" {:command (str "sh -c 'sleep 30; true " mark "' & sleep 30; true " mark) :timeout 500})]
        (is (str/includes? (:output r) "it and every process it started were stopped"))
        (is (str/includes? (:output r) "larger :timeout"))
        (Thread/sleep 500)
        (is (str/blank? (:out (p/sh {:out :string :err :string} "pgrep" "-f" mark))) "none left running")
        (p/sh {:out :string :err :string} "pkill" "-f" mark)))
    (is (= ["a.txt:2:jolt"] (:matches (call "grep" {:pattern "jo+lt" :path "a.txt"}))))
    (is (some #{"a.txt"} (:entries (call "list-dir" {}))))
    (is (str/includes? (:error-message (call "read-file" {})) "missing required `path`"))))

;; --- the loop ----------------------------------------------------------------------

(deftest tool-then-answer
  (let [r (run [(reply {:tool-calls [{:tool-name "bash" :tool-args {:command "echo hi"}}]})
                (fn [messages]
                  ;; the tool result reaches the next prompt as printed data
                  (is (str/includes? (:content (second messages)) ":tool-result"))
                  (is (str/includes? (:content (second messages)) ":exit-code 0"))
                  (reply {:answer "done" :goal-achieved true}))])]
    (is (= "done" (:answer r)))
    (is (= :answer-channel (:terminated-by r)))
    (is (= 2 (:iterations r)))
    (is (= ["tool" "answer"] (map :channel (:trajectory r))))
    (is (true? (:goal-achieved r)))))

(deftest malformed-reply-is-repaired
  (let [r (run ["not json at all"
                (fn [messages]
                  (is (str/includes? (:content (second messages)) "FORMAT ERROR"))
                  (reply {:answer "ok"}))])]
    (is (= "ok" (:answer r)))
    (is (= :answer-channel (:terminated-by r)))))

(deftest repeated-llm-failure-aborts
  (let [r (run ["x" "y" "z"])]
    (is (= :llm-error (:terminated-by r)))
    (is (str/starts-with? (:answer r) "Agent stopped:"))
    (is (= 3 (:iterations r)))))

(deftest no-action-guard
  (let [r (run [(reply {}) (reply {}) (reply {:reasoning "stuck"})])]
    (is (= :none-channel-loop-guard (:terminated-by r)))
    (is (str/includes? (:answer r) "stuck"))))

(deftest unbound-tool-is-dropped
  (let [r (run [(reply {:tool-calls [{:tool-name "noop" :tool-args {}}]})
                (fn [messages]
                  (is (str/includes? (:content (second messages)) "UNBOUND TOOL"))
                  (reply {:answer "fine"}))])]
    (is (= "fine" (:answer r)))
    (is (= "none" (:channel (first (:trajectory r)))))))

(deftest premature-answer-is-discarded
  (let [r (run [(reply {:answer "invented" :tool-calls [{:tool-name "bash" :tool-args {:command "true"}}]})
                (fn [messages]
                  (is (str/includes? (:content (second messages)) "CHANNEL CONFLICT"))
                  (reply {:answer "real"}))])]
    (is (= "real" (:answer r)))))

(deftest iteration-limit
  (let [call (reply {:tool-calls [{:tool-name "bash" :tool-args {:command "true"}}]})
        r (run (repeat 5 call) :config {:max-iterations 2 :project-dir @scratch})]
    (is (= :max-iterations-exhausted (:terminated-by r)))
    (is (= 2 (:iterations r)))
    (is (str/includes? (:answer r) "iteration limit"))))

(deftest loop-is-data
  (testing "a custom tree and registry entry run without touching the agent code"
    (let [tree [:sequence {}
                [:action {:fn :react/init}]
                [:action {:fn :custom/answer}]]
          r (run [] :tree tree
                 :registry {:custom/answer (fn [{:keys [st-memory]}]
                                             (swap! st-memory assoc :answer "from data"
                                                    :terminated true :terminated-by :custom))})]
      (is (= "from data" (:answer r)))
      (is (= :custom (:terminated-by r))))))

(deftest provider-failures-stay-out-of-the-prompt
  (testing "the transcript's shape: fail, fail, tool, fail, fail, answer"
    (let [n (atom 0)
          refuse (fn [_] (throw (ex-info "claude CLI failed (exit 1): API Error: flagged" {})))
          r (run [refuse refuse
                  (reply {:tool-calls [{:tool-name "bash" :tool-args {:command "echo hi"}}]})
                  refuse refuse
                  (fn [messages]
                    (let [user (:content (second messages))]
                      (is (not (str/includes? user "API Error")))
                      (is (not (str/includes? user ":notices")))
                      (is (str/includes? user ":channel \"tool\"")))
                    (reply {:answer "ok"}))]
                 :config {:max-iterations 10 :project-dir @scratch})]
      (is (= "ok" (:answer r)))
      (is (= 6 (:iterations r)))
      (is (= 6 (get-in r [:usage :llm-calls])) "failed calls are counted")
      (is (= ["llm-error" "llm-error" "tool" "llm-error" "llm-error" "answer"]
             (map :channel (:trajectory r))))
      (is (= "claude CLI failed (exit 1): API Error: flagged" (:error (first (:trajectory r))))))))

(deftest claude-cli-output
  (let [so {"type" "result" "is_error" false "structured_output" {"answer" "a"}}]
    (is (= {:content "{\"answer\":\"a\"}" :source :structured-output}
           (llm/claude-cli-content [(json/read-str (json/write-str so))]))))
  (testing "an errored result is not salvaged from its assistant text"
    (is (nil? (llm/claude-cli-content
               (json/read-str (json/write-str
                               [{"type" "assistant" "message" {"content" [{"type" "text" "text" "API Error: refused"}]}}
                                {"type" "result" "is_error" true "stop_reason" "refusal" "result" "API Error: refused"}])))))))

(deftest claude-cli-signs-in-from-its-settings-credential
  ;; byj-1wv.115.20: claude -p reads none of Claude Code's settings
  ;; (--setting-sources ""), so the credential a person kept there is given
  ;; as its environment — those keys alone, none blank
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "by-claude-settings-" (System/nanoTime))) .mkdirs)]
    (is (= {} (llm/claude-settings-credentials dir)) "no settings, none")
    (spit (io/file dir "settings.json")
          (json/write-str {"env" {"ANTHROPIC_API_KEY" "test-not-a-key" "CLAUDE_CODE_OAUTH_TOKEN" "" "ANTHROPIC_MODEL" "opus"}
                           "permissions" {"allow" ["Bash"]}}))
    (is (= {"ANTHROPIC_API_KEY" "test-not-a-key"} (llm/claude-settings-credentials dir)))
    (spit (io/file dir "settings.json") "not json")
    (is (= {} (llm/claude-settings-credentials dir)) "unreadable, none")))

(deftest claude-cli-rejected-tool-calls
  ;; the shape of a captured haiku call (provenance P-12): a native tool_use the
  ;; CLI rejected, then a StructuredOutput answering that the tool is missing
  (let [events (json/read-str
                (json/write-str
                 [{"type" "assistant" "message" {"content" [{"type" "tool_use" "id" "t1" "name" "tool$create"
                                                             "input" {"name" "count-lines" "params" "{\"path\": {\"type\": \"string\"}}"}}]}}
                  {"type" "assistant" "message" {"content" [{"type" "tool_use" "id" "t2" "name" "StructuredOutput"
                                                             "input" {"tool-calls" [] "answer" "tool$create is not available"}}]}}
                  {"type" "user" "message" {"content" [{"type" "tool_result" "tool_use_id" "t1" "is_error" true
                                                        "content" "<tool_use_error>Error: No such tool available: tool$create</tool_use_error>"}]}}
                  {"type" "user" "message" {"content" [{"type" "tool_result" "tool_use_id" "t2"
                                                        "content" "Structured output provided successfully"}]}}
                  {"type" "result" "is_error" false "structured_output" {"tool-calls" [] "answer" "tool$create is not available"}}]))]
    (is (= [{:tool-name "tool$create" :tool-args {:name "count-lines" :params "{\"path\": {\"type\": \"string\"}}"}}]
           (llm/rejected-tool-calls events)))
    (is (= :structured-output (:source (llm/claude-cli-content events))) "the reply itself is unchanged"))
  (testing "a failed StructuredOutput is not a native tool call"
    (is (= [] (llm/rejected-tool-calls
               (json/read-str (json/write-str
                               [{"type" "assistant" "message" {"content" [{"type" "tool_use" "id" "t1" "name" "StructuredOutput" "input" {}}]}}
                                {"type" "user" "message" {"content" [{"type" "tool_result" "tool_use_id" "t1" "is_error" true
                                                                      "content" "Output does not match required schema"}]}}])))))))

(deftest native-tool-calls-recovered
  (testing "an answer written after a rejected native call is replaced by the calls"
    (let [prompts (atom [])
          events (atom [])
          replies (atom [{:content (reply {:reasoning "The list-dir tool is not bound in this session."
                                           :answer "list-dir is not available in this environment."
                                           :goal-achieved true})
                          :attempted-tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}
                                                 {:tool-name "StructuredOutputs" :tool-args {}}]}
                         (reply {:answer "done"})])
          r (agent/ask "q" {:lm {:provider :mock :format :mock
                                 :responses (fn [msgs] (swap! prompts conj (:content (last msgs)))
                                              (let [[x] (swap-vals! replies #(vec (rest %)))] (first x)))}
                            :on-event (fn [e d] (swap! events conj [e d]))
                            :config {:max-iterations 5 :working-dir "." :project-dir @scratch}})]
      (is (= "done" (:answer r)))
      (is (= ["tool" "answer"] (map :channel (:trajectory r))))
      (is (= ["list-dir"] (map :tool-name (:tool-results (first (:trajectory r))))))
      (testing "the reasoning written under the refusal is replaced, where it is shown and where it is kept (byj-gly.339)"
        (is (= [agent/recovered-thought] (keep #(when (= :agent.tools/recovered (first %)) (:thought (second %))) @events)))
        (is (= agent/recovered-thought (:thought (first (:trajectory r)))))
        (is (not (str/includes? (second @prompts) "not bound in this session")) "nor read again by the model"))
      (is (str/includes? (second @prompts) "TOOL CHANNEL"))
      (is (not (str/includes? (second @prompts) "UNBOUND TOOL")) "attempts naming no bound tool are dropped silently")))
  (testing "the model's lookups for a tool it was told is missing are left out beside the call itself (byj-gly.352)"
    (let [r (run [{:content (reply {:answer "list-dir is not available"})
                   :attempted-tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}
                                          {:tool-name "list-tools" :tool-args {:pattern "list"}}
                                          {:tool-name "get-tool-info" :tool-args {:tool-id "list-dir"}}
                                          {:tool-name "search" :tool-args {:query "list-dir"}}]}
                  (reply {:answer "done"})])
          recovered (some #(when (= :agent.tools/recovered (first %)) (second %)) (:events r))]
      (is (= ["list-dir"] (map :tool-name (:tool-results (first (:trajectory r))))) "the call alone runs")
      (is (= ["list-tools" "get-tool-info" "search"] (:dropped recovered)) "and the event says what was left out")))
  (testing "lookups alone are kept: they were all the model tried"
    (let [r (run [{:content (reply {:answer "nothing to call"})
                   :attempted-tool-calls [{:tool-name "list-tools" :tool-args {:pattern "git"}}]}
                  (reply {:answer "done"})])]
      (is (= ["list-tools"] (map :tool-name (:tool-results (first (:trajectory r))))))))
  (testing "arguments wrapped as one JSON string are unwrapped (byj-gly.352)"
    (let [r (run [{:content (reply {:answer "bash is not available"})
                   :attempted-tool-calls [{:tool-name "bash" :tool-args {:args "{\"command\": \"echo unwrapped\"}"}}]}
                  (reply {:answer "done"})])
          res (first (:tool-results (first (:trajectory r))))]
      (is (= {:command "echo unwrapped"} (:tool-args res)))
      (is (not (str/includes? (str (:result res)) "missing required")) "so the tool is not refused for its shape")))
  (testing "unwrapping leaves a tool that takes an args input, and a string that is no JSON object, as they came"
    (let [takes {:name "t" :input-schema [:map [:args :string]]}
          plain {:name "u" :input-schema [:map [:command :string]]}]
      (is (= {:args "{\"a\": 1}"} (agent/unwrapped-args takes {:args "{\"a\": 1}"})))
      (is (= {:args "not json"} (agent/unwrapped-args plain {:args "not json"})))
      (is (= {:args "[1, 2]"} (agent/unwrapped-args plain {:args "[1, 2]"})))
      (is (= {:command "ls" :args "{}"} (agent/unwrapped-args plain {:command "ls" :args "{}"})) "only a lone :args")
      ;; the whole call wrapped, naming its tool, as haiku sent bash once (byj-gly.395)
      (is (= {:command "ls"} (agent/unwrapped-args plain {:args "{\"command\": \"ls\"}" :name "u"})))
      (is (= {:args "{\"command\": \"ls\"}" :name "bash"}
             (agent/unwrapped-args plain {:args "{\"command\": \"ls\"}" :name "bash"}))
          "but not one naming another tool")))
  (testing "a reply that lists tool calls itself keeps them"
    (let [r (run [{:content (reply {:tool-calls [{:tool-name "list-dir" :tool-args {:path "src"}}]})
                   :attempted-tool-calls [{:tool-name "bash" :tool-args {:command "ls"}}]}
                  (reply {:answer "done"})])]
      (is (= [{:path "src"}] (map :tool-args (:tool-results (first (:trajectory r))))))
      (is (not-any? #(= :agent.tools/recovered (first %)) (:events r))))))

(deftest object-params-accept-json-strings
  (let [tool {:name "t" :input-schema [:map [:m :map]] :fn (fn [args _] (:m args))}]
    (is (= {:a 1} (tools/call-tool tool {:m "{\"a\": 1}"} {})))
    (is (= {:a 1} (tools/call-tool tool {:m {:a 1}} {})))
    (is (str/includes? (:error-message (tools/call-tool tool {:m "not json"} {})) "`m` should be an object"))))

;; --- streaming (RFC-006) --------------------------------------------------------------

(deftest the-reply-can-arrive-as-it-is-written
  (let [dir (io/file "target/stream-test")
        _ (.mkdirs dir)
        script (io/file dir "fake-claude-stream")
        ;; a CLI that emits the shapes `claude --output-format stream-json
        ;; --include-partial-messages` emits, one line at a time
        _ (spit script (str "#!/bin/bash\ncat > /dev/null\n"
                            "printf '{\"type\":\"system\",\"subtype\":\"init\"}\\n'\n"
                            "printf '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"{\\\\\"reasoning\\\\\":\\\\\"r\\\\\",\"}}}\\n'\n"
                            "sleep 0.4\n"
                            "printf '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"\\\\\"tool-calls\\\\\":[],\\\\\"answer\\\\\":\\\\\"streamed\\\\\"}\"}}}\\n'\n"
                            "printf '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"{\\\\\"reasoning\\\\\":\\\\\"r\\\\\",\\\\\"tool-calls\\\\\":[],\\\\\"answer\\\\\":\\\\\"streamed\\\\\"}\"}\\n'\n"))
        _ (p/sh "chmod" "+x" (.getPath script))
        chunks (atom [])
        t0 (System/currentTimeMillis)
        r (agent/ask "q" {:lm {:provider :claude-code :format :claude-code
                               :cli-command (.getAbsolutePath script)
                               :stream? true :timeout-ms 30000}
                          :config {:working-dir (.getPath dir) :project-dir (.getPath dir) :max-iterations 2}
                          :on-event (fn [e d]
                                      (when (= :agent.llm/chunk e)
                                        (swap! chunks conj (assoc d :at (- (System/currentTimeMillis) t0)))))})]
    (testing "the parsed answer is what it always was"
      (is (= "streamed" (:answer r))))
    (testing "and its text arrived in pieces, as it was written"
      (is (= 2 (count @chunks)))
      (is (> (:at (second @chunks)) 300)
          "the second fragment arrived after the pause, not batched with the first"))
    (testing "each piece says which field of the reply it is (byj-gly.56)"
      ;; the provider streams JSON; a viewer wants prose, and wants to know
      ;; whether it is looking at the model thinking or at its answer — the
      ;; reasoning is the only thing worth showing while the answer is empty
      (is (= [{:field :reasoning :text "r"} {:field :answer :text "streamed"}]
             (map #(select-keys % [:field :text]) @chunks))))))

(deftest a-cli-model-runs-in-the-sessions-project
  ;; byj-gly.338: `byj run -C DIR` started elsewhere ran claude -p in the
  ;; launching process's cwd, and Claude Code's own environment block named
  ;; that directory, so the model read another repository as the project
  (let [bin (doto (io/file "target/cli-dir-test/bin") .mkdirs)
        project (.getCanonicalPath (doto (io/file "target/cli-dir-test/project") .mkdirs))
        seen (io/file bin "pwd")
        script (io/file bin "fake-claude")
        _ (spit script (str "#!/bin/bash\ncat > /dev/null\npwd -P > '" (.getCanonicalPath seen) "'\n"
                            "printf '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"{\\\\\"reasoning\\\\\":\\\\\"r\\\\\",\\\\\"tool-calls\\\\\":[],\\\\\"answer\\\\\":\\\\\"ok\\\\\"}\"}\\n'\n"))
        _ (p/sh "chmod" "+x" (.getPath script))
        r (agent/ask "q" {:lm {:provider :claude-code :format :claude-code
                               :cli-command (.getAbsolutePath script) :timeout-ms 30000}
                          :config {:working-dir project :project-dir project :max-iterations 2}})]
    (is (= "ok" (:answer r)))
    (is (= project (str/trim (slurp seen))) "started in the project, not this process's cwd")
    (is (not= project (.getCanonicalPath (io/file (System/getProperty "user.dir")))) "which is elsewhere")))

(deftest a-provider-that-streams-prose-is-not-swallowed
  ;; not every reply is a structured one. A viewer that saw nothing at all
  ;; would be worse than one that saw unlabelled text, so a fragment stream
  ;; that is not JSON is labelled :raw and passed through.
  (let [f (llm/field-streamer [:reasoning :answer])]
    (is (= [{:field :raw :text "Hello, "}] (f "Hello, ")))
    (is (= [{:field :raw :text "world."}] (f "world.")))))

(deftest a-field-is-decoded-as-far-as-it-has-arrived
  (let [f (llm/field-streamer [:reasoning :answer])]
    (testing "nothing is claimed before the field's value starts"
      (is (= [] (f "{\"reason"))))
    (testing "and only the new part of it is sent each time"
      (is (= [{:field :reasoning :text "Let me"}] (f "ing\":\"Let me")))
      (is (= [{:field :reasoning :text " look"}] (f " look"))))
    (testing "escapes are prose, not backslashes — including one split mid-sequence"
      ;; a lone trailing backslash is held back until its escape is complete;
      ;; everything decodable before it is not made to wait for it
      (is (= [{:field :reasoning :text "."}] (f ".\\")))
      (is (= [{:field :reasoning :text "\n"}] (f "n"))))
    (testing "and the answer takes over from the reasoning in one fragment"
      (is (= [{:field :reasoning :text "done"} {:field :answer :text "42"}]
             (f "done\",\"answer\":\"42"))))))

(deftest streaming-is-off-unless-asked-for
  (testing "the flags change only when it is on, so the default invocation is untouched"
    (is (= ["--output-format" "json"]
           (take 2 (drop 3 (llm/claude-cli-args {} "sys" nil)))))
    (is (not-any? #{"--include-partial-messages"} (llm/claude-cli-args {} "sys" nil)))
    (is (some #{"--include-partial-messages"} (llm/claude-cli-args {} "sys" nil true)))))

;; --- the answer gate (byj-gly.12) -------------------------------------------------

(defn- gate-run
  "A turn that optionally calls a tool, then answers. `think` supplies extra
  think-step outputs per answer number; `verdicts` supplies the gate's verdict
  per gate call. Returns [result call-kinds think-prompts]."
  [cfg {:keys [tool? think verdicts tree registry] :or {think (constantly nil) verdicts {}}}]
  (let [calls (atom []) n (atom 0) g (atom 0) prompts (atom [])]
    [(agent/ask "what is in notes.txt?"
                {:lm {:provider :mock :format :mock
                      :responses
                      (fn [m]
                        (if (str/includes? (str (:content (first m))) "hallucination detector")
                          (do (swap! calls conj :gate)
                              (json/write-str (get verdicts (swap! g inc))))
                          (do (swap! calls conj :think)
                              (swap! prompts conj (str (:content (second m))))
                              ;; the counter must tick on every think call, or
                              ;; `and` short-circuits it away when tool? is false
                              (let [i (swap! n inc)]
                                (json/write-str
                                 (if (and tool? (= 1 i))
                                   {:reasoning "r" :answer ""
                                    :tool-calls [{:tool-name "bash" :tool-args {:command "echo hi"}}]}
                                   (merge {:reasoning "r" :tool-calls [] :answer (str "v" i)}
                                          (think i))))))))}
                 :config (merge {:working-dir "." :max-iterations 6 :persist-session? false} cfg)
                 :tree tree :registry registry})
     @calls @prompts]))

(def ^:private said-done (constantly {:goal-achieved true}))

(deftest the-answer-gate-is-inert-until-a-project-asks-for-it
  ;; brainyard ships the same gate at :max-refinements 0 and that default is
  ;; kept. At 0 the decision is :accept before anything is asked, so the answer
  ;; path costs exactly what it did before the gate existed.
  (let [[r calls] (gate-run {} {:think said-done})]
    (is (= [:think] calls) "no gate call at all")
    (is (= "v1" (:answer r)))
    (is (= 1 (get-in r [:usage :llm-calls])))))

(deftest the-two-refusals-that-cost-nothing
  (testing "the model said the goal was not met — believe it, and buy no second opinion"
    (let [[r calls] (gate-run {:max-refinements 2} {:think (constantly {:goal-achieved false})})]
      (is (not-any? #{:gate} calls) "no evaluation was bought to hear what it already said")
      (is (= [:think :think :think] calls) "two refusals, then the budget accepts")
      (is (= "v3" (:answer r)))))

  (testing "and an answer with no tool results is never evaluated — there is nothing to check it against"
    (let [[r calls] (gate-run {:max-refinements 2} {:think said-done})]
      (is (= [:think] calls))
      (is (= "v1" (:answer r))))))

(deftest an-answer-the-tools-do-not-support-is-refused
  (testing "a grounded answer is confirmed once and stamped"
    (let [[r calls] (gate-run {:max-refinements 2}
                              {:tool? true :think said-done
                               :verdicts {1 {:reasoning "r" :verdict "COMPLETE" :detail "ok"}}})]
      (is (= [:think :think :gate] calls))
      (is (= "v2" (:answer r)))))

  (testing "one that invents data is refused, and the reason comes back as an instruction"
    (let [[r calls prompts]
          (gate-run {:max-refinements 2}
                    {:tool? true :think said-done
                     :verdicts {1 {:reasoning "r" :verdict "HALLUCINATED"
                                   :detail "no tool read notes.txt"}
                                2 {:reasoning "r" :verdict "COMPLETE" :detail "ok"}}})]
      (is (= [:think :think :gate :think :gate] calls))
      (is (= "v3" (:answer r)) "the refused answer was not the turn's")
      (is (str/includes? (last prompts) "ANSWER REFUSED"))
      (is (str/includes? (last prompts) "no tool read notes.txt"))
      (is (str/includes? (last prompts) "data no tool returned")
          "a hallucination is named as one, not reported as vague incompleteness")))

  (testing "a critic writing its verdict in lower case is still heard (byj-gly.216)"
    ;; the enum check made "hallucinated" malformed, and the gate's catch took
    ;; that for COMPLETE — a fabricated answer passed
    (let [[r calls] (gate-run {:max-refinements 2}
                              {:tool? true :think said-done
                               :verdicts {1 {:reasoning "r" :verdict "hallucinated" :detail "invented"}
                                          2 {:reasoning "r" :verdict "complete" :detail "ok"}}})]
      (is (= [:think :think :gate :think :gate] calls))
      (is (= "v3" (:answer r)) "the refused answer was not the turn's")))

  (testing "an incomplete one gets the other wording"
    (let [[_ _ prompts]
          (gate-run {:max-refinements 2}
                    {:tool? true :think said-done
                     :verdicts {1 {:reasoning "r" :verdict "INCOMPLETE" :detail "half the file"}
                                2 {:reasoning "r" :verdict "COMPLETE" :detail "ok"}}})]
      (is (str/includes? (last prompts) "was incomplete")))))

(deftest a-critic-that-cannot-judge-does-not-cost-a-turn-its-answer
  (testing "an unrecognised verdict accepts, as brainyard's does"
    (let [[r calls] (gate-run {:max-refinements 2}
                              {:tool? true :think said-done
                               :verdicts {1 {:reasoning "r" :verdict "WEIRD" :detail ""}}})]
      (is (= [:think :think :gate] calls))
      (is (= "v2" (:answer r)))))

  (testing "and the gate cannot refuse forever"
    (let [[r calls] (gate-run {:max-refinements 1}
                              {:tool? true :think said-done
                               :verdicts {1 {:reasoning "r" :verdict "HALLUCINATED" :detail "no"}
                                          2 {:reasoning "r" :verdict "HALLUCINATED" :detail "no"}}})]
      (is (= 1 (count (filter #{:gate} calls)))
          "past the budget it does not ask — a verdict it cannot act on is not worth buying")
      (is (= :answer-channel (:terminated-by r)) "the turn ends normally"))))

;; --- the ReAct loop's slots (RFC-072, byj-9sp.4) ------------------------------------

(defn- slotted
  "The bundled ReAct loop with `attachments` expanded into its slots."
  [attachments]
  (bt/expand (agent/resolve-tree "react") agent/resolve-tree attachments))

(defn ^{:reads #{:last-tool-results :question} :writes #{:ext/notices}} ext-note-action
  "An attachment's leaf: says what the tools returned, in :ext/notices."
  [{:keys [st-memory]}]
  (swap! st-memory assoc :ext/notices
         [(str "LINT: " (count (:last-tool-results @st-memory)) " result(s) for: " (:question @st-memory))]))

(defn ^{:reads #{:answer} :writes #{:ext/critique}} ext-refuse-v-action
  "An attachment's check: refuses an answer that is v1, with a reason."
  [{:keys [st-memory]}]
  (if (= "v1" (:answer @st-memory))
    (do (swap! st-memory assoc :ext/critique "v1 is never right here.") :failure)
    :success))

(deftest the-bundled-loop-offers-its-slots-empty
  (let [react (agent/load-tree nil)]
    (is (= #{:react/ext-after-tools :react/ext-answer-check}
           (set (keep (fn [[_ o]] (when (:slot o) (:id o))) (bt/nodes react)))))
    (is (empty? (filter #(:attached (second %)) (bt/nodes react))))))

(deftest the-answer-check-sits-before-every-stamp
  ;; an answer is final once stamped (`:agent.answer/final`), so a check that
  ;; ran after a stamp, or beside one, would be passed by it
  (let [react (agent/resolve-tree "react")
        stamps (filter (fn [[k o]] (and (= :action k) (= :react/stamp-answer (:fn o)))) (bt/nodes react))
        parent-of (fn [id] (first (filter (fn [n] (some #(and (vector? %) (= id (:id (second %)))) n)) (bt/nodes react))))]
    (is (seq stamps))
    (doseq [[_ {:keys [id]}] stamps
            :let [siblings (map #(:id (second %)) (filter vector? (drop 2 (parent-of id))))]]
      (is (= [:react/answer-verdict :react/answer-checked id] siblings) (str id)))
    (is (= :react/answer-checked (:id (second (parent-of :react/ext-answer-check)))))))

(deftest an-attachment-after-the-tools-is-heard-next-iteration
  (let [[r calls prompts] (gate-run {} {:tool? true :think said-done
                                        :tree (slotted {:react/ext-after-tools
                                                        [{:name "lint" :tree [:action {:id :x/note :fn :x/note}]}]})
                                        :registry {:x/note #'ext-note-action}})]
    (is (= [:think :think] calls))
    (is (= "v2" (:answer r)))
    (is (not (str/includes? (first prompts) "LINT")))
    (is (str/includes? (second prompts) "LINT: 1 result(s) for: what is in notes.txt?"))))

(deftest an-attachment-after-the-tools-that-fails-costs-the-turn-nothing
  (let [[r calls] (gate-run {} {:tool? true :think said-done
                                :tree (slotted {:react/ext-after-tools
                                                [{:name "broken" :tree [:action {:id :x/boom :fn :x/boom}]}]})
                                :registry {:x/boom (fn [_] (throw (ex-info "lint crashed" {})))}})]
    (is (= [:think :think] calls))
    (is (= "v2" (:answer r)))))

(deftest a-projects-answer-check-refuses-on-every-path-that-accepts
  (let [check {:react/ext-answer-check [{:name "no-v1" :tree [:action {:id :x/check :fn :x/check}]}]}
        reg {:x/check #'ext-refuse-v-action}]
    (testing "the gate off: an accepted answer is refused for the check's reason"
      (let [[r calls prompts] (gate-run {} {:think said-done :tree (slotted check) :registry reg})]
        (is (= [:think :think] calls))
        (is (= "v2" (:answer r)))
        (is (str/includes? (last prompts) "ANSWER REFUSED: v1 is never right here."))))
    (testing "the gate on: an answer the evaluator confirmed is refused too"
      (let [[r calls] (gate-run {:max-refinements 2}
                                {:tool? true :think (fn [i] {:goal-achieved true :answer (if (= 2 i) "v1" (str "v" i))})
                                 :verdicts {1 {:reasoning "r" :verdict "COMPLETE" :detail "ok"}
                                            2 {:reasoning "r" :verdict "COMPLETE" :detail "ok"}}
                                 :tree (slotted check) :registry reg})]
        (is (= [:think :think :gate :think :gate] calls))
        (is (= "v3" (:answer r)))))
    (testing "and it cannot refuse forever: past :max-answer-checks the answer stands"
      (let [[r calls] (gate-run {:max-answer-checks 1}
                                {:think (constantly {:goal-achieved true :answer "v1"})
                                 :tree (slotted check) :registry reg})]
        (is (= [:think :think] calls))
        (is (= "v1" (:answer r)))
        (is (= :answer-channel (:terminated-by r)))))))

(deftest read-file-pages-a-file-too-big-for-one-result
  ;; a whole read of a spill was cut and spilled again, and the model read that
  ;; spill too (byj-gly.190)
  (let [dir (str "target/agent-test/page-" (System/currentTimeMillis))
        _ (.mkdirs (java.io.File. dir))
        _ (spit (java.io.File. dir "big.txt") (clojure.string/join "\n" (for [i (range 2000)] (str "line " i " " (apply str (repeat 30 "x"))))))
        _ (spit (java.io.File. dir "one.txt") (apply str (repeat 40000 "y")))
        ctx {:config {:working-dir dir :max-output-chars 16000}}
        read (fn [args] ((resolve 'brainyard.tools/read-file) args ctx))]
    (testing "a big file comes back as its first page, numbered, and how to read on"
      (let [r (read {:path "big.txt"})]
        (is (= 2000 (:total-lines r)))
        (is (<= (count (:content r)) 16000) "within one result, so it is never cut and spilled")
        (is (clojure.string/starts-with? (:content r) "1\tline 0 "))
        (is (= (inc (count (clojure.string/split-lines (:content r)))) (:next-offset r)))
        (is (clojure.string/includes? (:note r) (str "{:offset " (:next-offset r) " :limit 200}")))))
    (testing "a range never passes the cap either"
      (let [r (read {:path "big.txt" :offset 1 :limit 2000})]
        (is (<= (count (:content r)) 16000))
        (is (< 1 (:next-offset r) 2000))))
    (testing "a file of one enormous line — a printed data structure — is read in pieces, all of it"
      (let [pieces (loop [args {:path "one.txt"} acc []]
                     (let [r (read args)
                           acc (conj acc r)]
                       (is (<= (count (:content r)) 16000))
                       (if (and (:next-offset r) (< (count acc) 10))
                         (recur {:path "one.txt" :offset (:next-offset r) :column (:next-column r)} acc)
                         acc)))
            text (apply str (map #(-> (:content %) (clojure.string/replace #"^1\t…?" "") (clojure.string/replace #"…$" ""))
                                 pieces))]
        (is (= 3 (count pieces)))
        (is (clojure.string/includes? (:note (first pieces)) "{:offset 1 :column "))
        (is (= (apply str (repeat 40000 "y")) text) "and the pieces put back together are the line")))
    (testing "a page fits once printed, however many quotes and newlines it holds (byj-gly.218)"
      (spit (java.io.File. dir "quoted.json") (clojure.string/join "\n" (for [i (range 3000)] (str "  {\"k\": \"v" i "\", \"t\": \"a\\tb\"},"))))
      (spit (java.io.File. dir "quotes.txt") (apply str (repeat 30000 "\"")))
      (doseq [args [{:path "quoted.json"} {:path "quoted.json" :offset 5 :limit 2000} {:path "quotes.txt"}]]
        (let [r (read args)]
          (is (<= (count (pr-str r)) 16000) (str (:path args) " printed to " (count (pr-str r))))
          (is (< 10000 (count (pr-str r))) "and the page is not needlessly small"))))
    (testing "a range ended by :limit says where the file goes on (byj-gly.228)"
      (let [r (read {:path "big.txt" :offset 120 :limit 200})]
        (is (= 320 (:next-offset r)))
        (is (str/includes? (:note r) "lines 120–319 of 2000"))
        (is (nil? (:next-offset (read {:path "big.txt" :offset 1990 :limit 200}))) "and says nothing at the end")))
    (testing "a file that fits is returned whole, as before"
      (spit (java.io.File. dir "small.txt") "a\nb")
      (is (= {:path "small.txt" :size 3 :total-lines 2 :content "a\nb"} (read {:path "small.txt"}))))))

(deftest the-prompt-says-which-session-and-agent-this-is
  ;; asked "the session-id of this", a model with no such line said it could
  ;; not know — brainyard's system-info has one (session-rows)
  (let [zone (fn [config] (agent/session-context {:provider :mock :model "m"} (merge {:working-dir "." :max-iterations 5} config) []))]
    (let [z (zone {:session-id "1790-abc"})]
      (is (str/includes? z "### Session\n- Agent: root (depth 0)\n- Session: 1790-abc\n"))
      (is (not (str/includes? z "- Parent:"))))
    (let [z (zone {:session-id "1790-abc" :agent-depth 2 :agent-chain ["plan" "explore"]})]
      (is (str/includes? z "- Agent: explore (depth 2)\n- Parent: plan\n- Session: 1790-abc\n")
          "a subagent shares its caller's session, and says whose it is"))
    (is (str/includes? (zone {:session-id "x" :agent-depth 1 :agent-chain ["explore"]}) "- Parent: root\n"))))

(deftest the-persons-own-instructions-follow-the-projects
  ;; byj-gly.313: brainyard's ## User Instructions, from ~/.brainyard/AGENTS.md
  (let [d (io/file "target" "agent-test-user" (str (System/nanoTime)))
        _ (.mkdirs (io/file d "home"))
        _ (.mkdirs (io/file d "project"))
        home (.getCanonicalPath (io/file d "home"))
        dir (.getCanonicalPath (io/file d "project"))
        mine (str home "/AGENTS.md")
        theirs (str home "/more.md")
        cfg {:project-dir dir :working-dir dir :user-instruction-paths [mine theirs (str dir "/AGENTS.md")]}]
    (spit (io/file dir "AGENTS.md") "Project rule.\n")
    (spit mine "Answer briefly.\n")
    (spit theirs "Use bullet points.\n")
    (let [s (instructions/section cfg)]
      (testing "after the project's, in their order"
        (is (< (str/index-of s "## Project Instructions") (str/index-of s "## User Instructions")
               (str/index-of s "Answer briefly.") (str/index-of s "Use bullet points."))))
      (testing "a file shown as the project's is not shown again as the person's"
        (is (= 1 (count (re-seq #"Project rule\." s))))))
    (testing "a budget of their own: a large project file does not push them out"
      (spit (io/file dir "AGENTS.md") (apply str (repeat 100 "x\n")))
      (let [s (instructions/section (assoc cfg :project-instructions-max-chars 50))]
        (is (str/includes? s "Answer briefly."))
        (is (str/includes? s "for the rest)"))))
    (testing "the default: ~/.brainyard/AGENTS.md alone — it replaces brainyard's BRAINYARD.md"
      (is (= ["~/.brainyard/AGENTS.md"] instructions/default-user-paths))
      (is (= ["AGENTS.md" "CLAUDE.md"] instructions/default-paths)))
    (testing "[] turns them off"
      (is (not (str/includes? (str (instructions/section (assoc cfg :user-instruction-paths []))) "## User Instructions"))))))

(deftest instruction-files-are-quoted-for-a-model-to-read
  ;; byj-gly.314, read in the actual prompt: the files' own headings sat at the
  ;; section's level; tool markers were shown as text; the ranking was unsaid
  (let [d (io/file "target" "agent-test-quoted" (str (System/nanoTime)))
        _ (.mkdirs d)
        dir (.getCanonicalPath d)]
    (spit (io/file d "AGENTS.md")
          (str "# Rules\n\n<!-- BEGIN BRAINYARD -->\n## Ours\n<!-- END BRAINYARD -->\n"
               "<!-- a comment\nover lines -->\nkept\n\n```\n<!-- inside code, kept -->\n@AGENTS.md\n```\n"
               "a </instructions> in the text\n"))
    (let [s (instructions/section {:project-dir dir :working-dir dir :user-instruction-paths []})]
      (testing "each file quoted whole between tags, its headings inside them"
        (is (str/includes? s "<instructions source=\"AGENTS.md\">\n# Rules\n\n## Ours\nkept"))
        (is (str/ends-with? (str/trimr s) "</instructions>")))
      (testing "comment lines dropped, also one spanning lines — but not inside fenced code"
        (is (not (str/includes? s "BEGIN BRAINYARD")))
        (is (not (str/includes? s "over lines")))
        (is (str/includes? s "```\n<!-- inside code, kept -->\n@AGENTS.md\n```")))
      (testing "a closing tag in the text cannot end the quote"
        (is (str/includes? s "a &lt;/instructions> in the text"))
        (is (= 1 (count (re-seq #"</instructions>" s)))))
      (testing "how the sections rank is said: the agent's own, then the project's, then the person's"
        (is (str/includes? s "yours stand"))))
    (is (str/includes? (str (instructions/section {:project-dir dir :working-dir dir
                                                    :user-instruction-paths [(str dir "/AGENTS.md")]
                                                    :project-instruction-paths []}))
                       "conflict with the project's instructions above, which win"))))

(deftest instruction-files-share-one-budget-cut-at-a-heading
  ;; byj-gly.312: one budget across the files, in order; a cut at the last
  ;; heading before the limit when that keeps half of it
  (let [text (str "# T\n\nintro line\n\n## A\n\n" (apply str (repeat 40 "a")) "\n\n## B\n\n" (apply str (repeat 40 "b")) "\n")]
    (testing "whole when it fits"
      (is (= text (instructions/cut text 1000 "/p/AGENTS.md"))))
    (testing "at the last heading before the limit: whole sections"
      (let [c (instructions/cut text 80 "/p/AGENTS.md")]
        (is (str/starts-with? c (str "# T\n\nintro line\n\n## A\n\n" (apply str (repeat 40 "a")) "\n\n… (cut at ")))
        (is (not (str/includes? c "## B")))
        (is (str/ends-with? c "characters: read /p/AGENTS.md for the rest)"))))
    (testing "a heading that would keep under half the limit is not where it cuts: the last line end is"
      (let [long (str "# T\n\n## Only\n\n" (apply str (repeat 10 "line of text\n")))
            c (instructions/cut long 100 "/p/A.md")]
        (is (str/includes? c "## Only\n\nline of text"))
        (is (> (count (first (str/split c #"\n\n… "))) 50))))
    (testing "the files share the budget in order; one with none left is named, not shown"
      (let [d (io/file "target" "agent-test-budget" (str (System/nanoTime)))
            _ (.mkdirs d)
            dir (.getCanonicalPath d)]
        (spit (io/file d "AGENTS.md") (apply str (repeat 50 "a")))
        (spit (io/file d "CLAUDE.md") "claude rules\n")
        (let [s (instructions/section {:project-dir dir :working-dir dir :project-instructions-max-chars 50
                                       :user-instruction-paths []})]
          (is (str/includes? s (apply str (repeat 50 "a"))))
          (is (not (str/includes? s "claude rules")))
          (is (str/includes? s (str "not quoted: the instructions before it used the budget of 50 characters; read " dir "/CLAUDE.md"))))))))

(deftest the-projects-instructions-are-in-the-prompt
  ;; RFC-040 (byj-gly.309.1): CLAUDE.md and AGENTS.md, brainyard's
  ;; :project-instruction-paths, in the session-stable zone where brainyard puts
  ;; BRAINYARD.md; one reached two ways shown once; each cut at the cap
  (let [d (io/file "target" "agent-test-instructions" (str (System/nanoTime)))
        _ (.mkdirs d)
        dir (.getCanonicalPath d)
        zone (fn [config] (agent/session-context {:provider :mock :model "m"}
                                                 (merge {:working-dir dir :project-dir dir :max-iterations 5} config) []))
        prompt-of (fn [config]
                    (let [seen (atom nil)]
                      (agent/ask "hi" {:lm {:provider :mock :format :mock
                                            :responses (fn [msgs] (reset! seen msgs) (reply {:answer "ok"}))}
                                       :config (merge {:working-dir dir :project-dir dir :max-iterations 2 :persist-session? false} config)})
                      (str/join "\n" (map :content @seen))))]
    (testing "none: no section"
      (is (not (str/includes? (zone {}) "## Project Instructions"))))
    (spit (io/file d "AGENTS.md") "# Agent Instructions\n\nRun `make test` before committing.\n")
    (spit (io/file d "CLAUDE.md") "# Project instructions\n\n@AGENTS.md\n")
    (let [z (zone {})]
      (testing "AGENTS.md is shown, after the instruction and before the answer format"
        (is (str/includes? z "<instructions source=\"AGENTS.md\">\n# Agent Instructions\n\nRun `make test` before committing.\n</instructions>"))
        (is (< (str/index-of z "\n## Instructions\n") (str/index-of z "## Project Instructions") (str/index-of z "\n## Answer Format\n"))))
      (testing "AGENTS.md first, then CLAUDE.md without its import of it: shown once (byj-gly.313)"
        (is (= 1 (count (re-seq #"Run `make test`" z))))
        (is (< (str/index-of z "<instructions source=\"AGENTS.md\">") (str/index-of z "<instructions source=\"CLAUDE.md\">\n# Project instructions")))
        (is (str/includes? z "# Project instructions\n\n(AGENTS.md is quoted above, not repeated here.)\n</instructions>")
            "where the import was, a note: the file is not left saying what is not there (byj-gly.314)")
        (is (not (str/includes? z "@AGENTS.md")))))
    (testing "a CLAUDE.md that is only the import is not shown at all; AGENTS.md comes through the import"
      (spit (io/file d "CLAUDE.md") "@AGENTS.md\n")
      (let [z (zone {:project-instruction-paths ["CLAUDE.md"]})]
        (is (not (str/includes? z "source=\"CLAUDE.md\"")) "nothing of its own: not quoted")
        (is (str/includes? z "source=\"AGENTS.md\""))))
    (testing "a file past the budget is cut at a line end, saying where the rest is"
      (let [z (zone {:project-instructions-max-chars 30})]
        (is (str/includes? z "# Agent Instructions\n\n… (cut at 20 of "))
        (is (str/includes? z (str "read " dir "/AGENTS.md for the rest")))))
    (testing "the default budget carries this repository's AGENTS.md whole (byj-gly.312)"
      ;; to its last section, whichever AGENTS.md this is: the public one byj's
      ;; export lays over this repository's ends elsewhere (byj-ahf.12)
      (let [z (agent/session-context {:provider :mock :model "m"} {:working-dir "." :project-dir "." :max-iterations 5} [])
            last-section (last (re-seq #"(?m)^## .+$" (slurp "AGENTS.md")))]
        (is (some? last-section))
        (is (str/includes? z last-section) last-section)
        (is (not (str/includes? z "for the rest)")))))
    (testing "[] turns it off"
      (is (not (str/includes? (zone {:project-instruction-paths []}) "## Project Instructions"))))
    (testing "and it reaches the model, in a turn's prompt"
      (is (str/includes? (prompt-of {}) "Run `make test` before committing.")))))

(deftest a-loop-is-named-by-its-name-a-resource-or-a-file
  ;; byj-gly.374: :flow$status :tree react said "not found"
  (is (= (agent/resolve-tree "brainyard/loops/react.edn") (agent/resolve-tree "react"))
      "a bare name is the bundled loop of that name")
  (is (= (agent/resolve-tree "brainyard/loops/plan-act-eval.edn") (agent/resolve-tree "plan-act-eval")))
  (let [f (java.io.File/createTempFile "loop" ".edn")]
    (spit f (pr-str {:id :x :type :sequence :children []}))
    (is (= {:id :x :type :sequence :children []} (agent/resolve-tree (.getPath f))) "a file path still works"))
  (let [e (try (agent/resolve-tree "no-such-loop") nil (catch Exception e e))]
    (is (re-find #"not found: no-such-loop" (ex-message e)))
    (is (re-find #"name a bundled loop" (ex-message e)) "and says how a loop is named")))

;; --- a project's own loop (RFC-071, byj-ng7.1) ------------------------------------

(defn- project-dir
  "A project of the test's own, outside this repository, so nothing it writes
  under .brainyard/ lands here."
  [tag]
  (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-" tag "-" (System/nanoTime)))
                       .mkdirs)))

(def ^:private own-react
  "A loop that answers by itself: proof that a turn ran the project's tree."
  [:action {:id :project-react :fn :test/answer-from-the-project}])

(deftest a-project-loop-is-found-before-the-bundled-one
  (let [pd (project-dir "loop")
        f (io/file pd ".brainyard" "loops" "react.edn")
        bundled (agent/resolve-tree "react")]
    (testing "with no file of the project's, the bundled loop"
      (is (= bundled (agent/resolve-tree "react" pd) (agent/resolve-tree "brainyard/loops/react.edn" pd)))
      (is (= {:from :bundled :path "brainyard/loops/react.edn"} (agent/tree-source "react" pd))))
    (io/make-parents f)
    (spit f (pr-str own-react))
    (testing "with one, the project's — for a bare name and the bundled resource path alike"
      (is (= own-react (agent/resolve-tree "react" pd)))
      (is (= own-react (agent/resolve-tree "brainyard/loops/react.edn" pd)))
      (is (= :project (:from (agent/tree-source "brainyard/loops/react.edn" pd))))
      (is (= (.getPath f) (:path (agent/tree-source "react" pd)))))
    (testing "and only for that project"
      (is (= bundled (agent/resolve-tree "react")))
      (is (= bundled (agent/resolve-tree "react" (project-dir "other")))))
    (testing "so the session tree's :include of the turn's loop is the project's"
      (let [session (agent/load-tree "brainyard/loops/session.edn" pd)]
        (is (some #(= :project-react (:id (second %)))
                  (filter vector? (tree-seq vector? seq session))))
        (is (= [:bundled :project] (mapv :from (agent/tree-sources "session" pd))) "and --print-tree can say so")))
    (testing "and a turn whose config names the project runs it"
      (is (= "the project's loop answered"
             (:answer (agent/ask "q" {:lm {:provider :mock :format :mock :responses (atom [])}
                                      :config {:max-iterations 2 :working-dir pd :project-dir pd}
                                      :registry {:test/answer-from-the-project
                                                 (fn [{:keys [st-memory]}]
                                                   (swap! st-memory assoc :answer "the project's loop answered")
                                                   :success)}})))))
    (testing "finding a loop never writes a history line"
      (is (not (.exists (project-loops/history-file pd)))))))

(deftest a-project-loop-is-written-with-its-base-and-a-history-line-per-change
  (let [pd (project-dir "adopt")
        f (io/file pd ".brainyard" "loops" "react.edn")
        accepted (project-loops/write-loop! pd "react" own-react {:action :accept :proposal "loop/1"})
        written (agent/resolve-tree "react" pd)]
    (testing "an accept writes the tree with what it was based on"
      (is (= {:loop "react" :hash (project-loops/bundled-hash "react") :byj (version/version)}
             (:based-on (second written))))
      (is (= (project-loops/bundled-hash "react") (flow/tree-hash (agent/resolve-tree "react")))
          "the bundled loop's hash is flow's tree-hash of it as read")
      (is (= (assoc-in own-react [1 :based-on] (:based-on (second written))) written)))
    (testing "and one history line"
      (is (= [accepted] (project-loops/history pd)))
      (is (= {:action :accept :loop "react" :hash (flow/tree-hash written) :replaced nil :proposal "loop/1"}
             (select-keys accepted [:action :loop :hash :replaced :proposal]))))
    (agent/load-tree "session" pd)
    (agent/tree-sources "session" pd)
    (is (= 1 (count (project-loops/history pd))) "reading it writes none")
    (testing "a revert to the bundled loop removes the file and is a line too"
      (let [reverted (project-loops/write-loop! pd "brainyard/loops/react.edn" nil {:action :revert})]
        (is (not (.exists f)))
        (is (= (agent/resolve-tree "react") (agent/resolve-tree "react" pd)))
        (is (= {:action :revert :loop "react" :hash nil :replaced (flow/tree-hash written)}
               (select-keys reverted [:action :loop :hash :replaced])))
        (is (= [:accept :revert] (mapv :action (project-loops/history pd))))))
    (testing "a candidate that says what it was made from keeps saying it"
      (let [old {:loop "react" :hash "000000000000" :byj "v0.0.1"}]
        (project-loops/write-loop! pd "react" (assoc-in own-react [1 :based-on] old) {:action :accept})
        (is (= old (:based-on (second (agent/resolve-tree "react" pd)))))))
    (testing "nothing but an accept or a revert writes, and only a loop's name"
      (is (thrown? Exception (project-loops/write-loop! pd "react" own-react {})))
      (is (thrown? Exception (project-loops/write-loop! pd "some/file.edn" own-react {:action :accept})))
      (is (= 3 (count (project-loops/history pd)))))))

(deftest doctor-names-a-project-loop-whose-base-is-not-the-bundled-loop
  (let [pd (project-dir "doctor")
        dir (io/file pd ".brainyard" "loops")
        put (fn [n tree] (spit (io/file dir (str n ".edn")) (pr-str tree)))]
    (is (nil? (project-loops/report-text pd)) "a project with no loop of its own: nothing to say")
    (.mkdirs dir)
    (put "react" (assoc-in own-react [1 :based-on] (project-loops/based-on "react")))
    (put "plan-act-eval" (assoc-in own-react [1 :based-on] {:loop "plan-act-eval" :hash "000000000000" :byj "v0.0.1"}))
    (put "session" own-react)
    (put "mine" own-react)
    (spit (io/file dir "history.edn") "")
    (let [by-loop (into {} (map (juxt :loop identity)) (project-loops/overrides pd))
          text (project-loops/report-text pd)]
      (is (= #{"react" "plan-act-eval" "session" "mine"} (set (keys by-loop))) "history.edn is not a loop")
      (is (= {"react" false "plan-act-eval" true "session" true "mine" false}
             (update-vals by-loop :stale?)))
      (is (re-find #"plan-act-eval: the project's \.brainyard/loops/plan-act-eval\.edn was made from an older bundled plan-act-eval \(000000000000, byj v0\.0\.1\); the bundled one is now [0-9a-f]{12}" text))
      (is (re-find #"session: .* does not say which bundled session it was made from" text))
      (is (re-find #"react: .*made from the bundled react as it is" text))
      (is (re-find #"mine: .*byj bundles none of that name" text)))))
