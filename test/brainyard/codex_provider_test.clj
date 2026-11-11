(ns brainyard.codex-provider-test
  "The codex provider (byj-gly.430.9, RFC-049): `codex exec` as a model, as
  `claude -p` is the claude-code provider — the system prompt as
  developer_instructions, the reply's shape as --output-schema, the
  conversation on stdin, the person's config, rules, AGENTS.md and shell off,
  nothing persisted; the reply its last agent message, its tokens reported
  with cached reads, priced as OpenAI's API would bill the model. On a
  stand-in for the CLI that logs what it was given."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.cost :as cost]
            [brainyard.json :as json]
            [brainyard.llm :as llm]))

(defn- fresh-dir [nm]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-codex-provider-" nm "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- stub!
  "A `codex` that writes its argv a line each to `argv`, its stdin to `stdin`,
  the directory it ran in to `pwd` and the --output-schema file's content to
  `schema`, then prints `events`."
  [dir events]
  (let [f (io/file dir "codex")
        out (io/file dir "events.jsonl")]
    (spit out (str (str/join "\n" (map json/write-str events)) "\n"))
    (spit f (str "#!/bin/bash\n"
                 "printf '%s\\n' \"$@\" > " dir "/argv\n"
                 "pwd -P > " dir "/pwd\n"
                 "cat > " dir "/stdin\n"
                 "prev=''; for a in \"$@\"; do [ \"$prev\" = --output-schema ] && cp \"$a\" " dir "/schema; prev=$a; done\n"
                 "cat " out "\n"))
    (.setExecutable f true)
    (.getCanonicalPath f)))

(defn- events [answer]
  [{:type "thread.started" :thread_id "t-1"}
   {:type "turn.started"}
   {:type "item.completed" :item {:id "item_0" :type "agent_message" :text answer}}
   {:type "turn.completed" :usage {:input_tokens 14000 :cached_input_tokens 9000 :output_tokens 40 :reasoning_output_tokens 10}}])

(deftest a-turn-is-answered-by-codex-exec
  (let [dir (fresh-dir "turn")
        project (fresh-dir "project")
        cli (stub! dir (events (json/write-str {:reasoning "it is simple" :tool-calls [] :answer "four"})))
        seen (atom [])
        r (agent/ask "what is 2+2"
                     {:lm (llm/create-lm :codex (constantly nil) {:cli-command cli :model "gpt-5.4" :timeout-ms 30000})
                      :on-event (fn [e d] (swap! seen conj [e d]))
                      :config {:working-dir project :project-dir project :max-iterations 2}})
        argv (str/split-lines (slurp (io/file dir "argv")))]
    (is (= "four" (:answer r)))
    (testing "codex exec as a model, not an agent"
      (is (= ["exec" "--json" "--ephemeral" "--skip-git-repo-check" "--ignore-user-config" "--ignore-rules"
              "-s" "read-only" "--disable" "shell_tool" "--disable" "unified_exec" "-c" "project_doc_max_bytes=0"]
             (take 14 argv)))
      (is (= ["-m" "gpt-5.4"] (take 2 (drop 14 argv))))
      (is (some #(str/starts-with? % "developer_instructions=\"") argv) "the system prompt")
      (is (= "-" (last argv)) "the conversation on stdin")
      (is (= project (str/trim (slurp (io/file dir "pwd")))) "run in the session's project")
      (is (str/includes? (slurp (io/file dir "stdin")) "what is 2+2"))
      (is (not (some #{"--output-schema"} argv))
          "the turn's schema has an open property, which Codex's strict output schema refuses: it stays in the prompt"))
    (testing "its tokens reported, cached reads apart, and the call recorded"
      (let [{:keys [usage]} (second (first (filter #(= :llm.cli/result (first %)) @seen)))]
        (is (= {:input-tokens 14000 :output-tokens 40 :cache {:read-tokens 9000 :write-tokens 0}} usage)))
      (is (some #(= :llm.cli/call (first %)) @seen)))
    (testing "priced as OpenAI's API would bill its model"
      (is (some? (cost/rates-for (cost/table {}) :codex "gpt-5.4")))
      (is (nil? (cost/rates-for (cost/table {}) :codex "gpt-6-luna")) "an unpriced model stays unknown"))))

(deftest a-strict-schema-is-the-replys-shape
  (let [dir (fresh-dir "schema")
        cli (stub! dir (events "{\"n\":391}"))
        schema {:type "object" :properties {:n {:type "integer"}} :required ["n"] :additionalProperties false}
        r (llm/chat (llm/create-lm :codex (constantly nil) {:cli-command cli :timeout-ms 30000})
                    [{:role "user" :content "17*23"}] {:json-schema schema})]
    (is (= "{\"n\":391}" (:content r)))
    (is (= schema (json/read-str (slurp (io/file dir "schema")))) "passed as --output-schema")))

(deftest a-reply-with-no-message-fails-with-what-codex-said
  (let [dir (fresh-dir "fail")
        cli (stub! dir [{:type "thread.started" :thread_id "t-2"}
                        {:type "turn.failed" :error {:message "You've hit your usage limit."}}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage limit"
                          (llm/chat (llm/create-lm :codex (constantly nil) {:cli-command cli :timeout-ms 30000})
                                    [{:role "system" :content "s"} {:role "user" :content "u"}]))))
    (testing "an API error's JSON body is read for its message"
      (let [dir (fresh-dir "api")
            cli (stub! dir [{:type "error" :message (json/write-str {:type "error" :error {:message "Invalid schema for response_format"} :status 400})}])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exit 0\): Invalid schema for response_format$"
                              (llm/chat (llm/create-lm :codex (constantly nil) {:cli-command cli :timeout-ms 30000})
                                        [{:role "user" :content "u"}]))))))

(deftest no-model-means-codexs-own-default
  (let [args (llm/codex-cli-args {} "sys" nil)]
    (is (not (some #{"-m"} args)))
    (is (not (some #{"--output-schema"} args)))
    (is (= "developer_instructions=\"sys\"" (some #(when (str/starts-with? % "developer_instructions=") %) args))))
  (is (= "developer_instructions=\"a/b \\\"q\\\"\\nline\""
         (some #(when (str/starts-with? % "developer_instructions=") %) (llm/codex-cli-args {} "a/b \"q\"\nline" nil)))
      "a TOML basic string: JSON's escapes, but / left as it is"))
