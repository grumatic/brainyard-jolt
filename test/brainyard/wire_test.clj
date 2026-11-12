;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.wire-test
  "What actually left this process (RFC-026 \"The archive\", byj-gly.119).

  Before this, `brainyard.llm` emitted nothing: the only record of a call was
  `:agent.llm/request`, which `predictor.clj` emits carrying the ASSEMBLED
  MESSAGES — a layer above the wire. So `dev/brainyard/dev/replay.clj` could
  replay brainyard's `claude -p` invocations and not this port's, which is what
  provenance P-6 cost.

  Two properties are worth a test rather than a reading. A recorded call has to
  carry enough to reproduce it — argv and stdin together, unclipped — and it has
  to carry no credential."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.eventlog :as el]
            [brainyard.llm :as llm]
            [jolt.process :as p]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

;; --- no credential reaches a log ------------------------------------------------------

(deftest a-recorded-call-carries-no-credential
  (let [redact #'llm/redact
        headers {"Authorization" "Bearer sk-secret-42"
                 "x-api-key" "sk-ant-secret"
                 "anthropic-version" "2023-06-01"
                 "anthropic-beta" "prompt-caching-2024-07-31"
                 "Content-Type" "application/json"}
        out (redact headers)]
    (testing "the credentials are gone, by name and whatever their case"
      (is (= "[redacted]" (get out "Authorization")))
      (is (= "[redacted]" (get out "x-api-key")))
      (is (not (str/includes? (pr-str out) "sk-secret-42")))
      (is (not (str/includes? (pr-str out) "sk-ant-secret"))))
    (testing "and everything that decides how a provider answers is KEPT —
              brainyard logs no headers at all, which is what makes its own
              records unreplayable (divergence `wire-headers-redacted`)"
      (is (= "2023-06-01" (get out "anthropic-version")))
      (is (= "prompt-caching-2024-07-31" (get out "anthropic-beta")))
      (is (= "application/json" (get out "Content-Type"))))
    (testing "non-vacuous: the secrets really were there to begin with"
      (is (str/includes? (pr-str headers) "sk-secret-42")))))

;; --- a recorded call is a replayable one ----------------------------------------------

(defn- stub-cli [dir]
  (let [script (io/file dir "claude")]
    (spit script (str "#!/bin/bash\ncat > /dev/null\n"
                      "printf '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                      "\"result\":\"{\\\\\"reasoning\\\\\":\\\\\"r\\\\\",\\\\\"tool-calls\\\\\":[],"
                      "\\\\\"answer\\\\\":\\\\\"ok\\\\\"}\"}\\n'\n"))
    (p/sh "chmod" "+x" (.getPath script))
    (.getAbsolutePath script)))

(deftest a-parameter-the-provider-cannot-send-is-not-in-the-lm-map
  (testing "claude -p takes no --temperature and no --max-tokens (both are
            'unknown option' on the CLI), so an LM for it reports neither —
            it used to report :temperature 0.0, which made every measurement
            through the default provider look reproducible (byj-gly.126)"
    (let [cc (llm/create-lm :claude-code (constantly nil) {})]
      (is (nil? (:temperature cc)))
      (is (nil? (:max-tokens cc)))
      (is (true? (llm/samples-freely? cc)))))
  (testing "a provider that DOES send them is untouched — non-vacuous, or the
            test above would pass on an LM builder that dropped them for everyone"
    (let [oa (llm/create-lm :openai (constantly nil) {})]
      (is (= 0.0 (:temperature oa)))
      (is (= 8192 (:max-tokens oa)))
      (is (false? (llm/samples-freely? oa)))
      (is (= 0.0 (:temperature (llm/openai-body oa [])))
          "and it reaches the body, so :temperature still means something"))))

(deftest a-gateway-given-as-its-origin-is-called-at-the-apis-path
  (testing "ANTHROPIC_BASE_URL as Claude Code takes it, an origin, keeps /v1 (byj-gly.446)"
    (let [lm #(llm/create-lm % {"ANTHROPIC_BASE_URL" %2 "OPENAI_BASE_URL" %2 "FREELLM_BASE_URL" %2} {})]
      (is (= "http://litellm:4000/v1" (:base-url (lm :anthropic "http://litellm:4000"))))
      (is (= "http://litellm:4000/v1" (:base-url (lm :anthropic "http://litellm:4000/"))))
      (is (= "https://gw.example.com/anthropic/v1" (:base-url (lm :anthropic "https://gw.example.com/anthropic/v1")))
          "a gateway under a prefix is kept as it is")
      (is (= "http://gw:8080/v1" (:base-url (lm :openai "http://gw:8080"))))
      (is (= "http://gw:8080" (:base-url (lm :freellm "http://gw:8080")))
          "a provider with no static URL has no path to inherit")
      (is (= "https://api.anthropic.com/v1" (:base-url (llm/create-lm :anthropic (constantly nil) {})))))))

(deftest a-sampling-parameter-that-cannot-be-sent-is-announced
  (testing "what the CALLER asked for is kept and said once per call, rather
            than swallowed: a run at an unknown temperature must not look like
            a run at 0"
    (let [dir (fresh-dir "wire-ignored")
          seen (atom [])
          lm (llm/create-lm :claude-code (constantly nil)
                            {:temperature 0.2 :cli-command (stub-cli dir) :timeout-ms 30000})]
      (is (= {:temperature 0.2} (:ignored-params lm)))
      (llm/chat lm [{:role "user" :content "hi"}]
                {:on-event (fn [e d] (swap! seen conj [e d]))})
      (let [ev (second (first (filter #(= :llm.cli/params-ignored (first %)) @seen)))]
        (is (some? ev) "the drop is on the event channel")
        (is (= {:temperature 0.2} (:ignored ev))))
      (testing "and no --temperature reached the argv, since the CLI rejects it"
        (let [call (second (first (filter #(= :llm.cli/call (first %)) @seen)))]
          (is (not (some #{"--temperature"} (:args call))))))))
  (testing "a default nobody asked for is dropped silently — only an explicit
            request is worth reporting"
    (is (nil? (:ignored-params (llm/create-lm :claude-code (constantly nil) {}))))))

(deftest the-cli-call-is-recorded-argv-and-stdin-together
  (let [dir (fresh-dir "wire-cli")
        seen (atom [])
        r (agent/ask "what is in deps.edn"
                     {:lm {:provider :claude-code :format :claude-code
                           :cli-command (stub-cli dir) :timeout-ms 30000}
                      :config {:working-dir (.getPath dir) :project-dir (.getPath dir)
                               :max-iterations 2}
                      :on-event (fn [e d] (swap! seen conj [e d]))})
        call (second (first (filter #(= :llm.cli/call (first %)) @seen)))]
    (is (= "ok" (:answer r)) "non-vacuous: the call really happened")
    (testing "argv and stdin are both there — either alone cannot be replayed"
      (is (vector? (:args call)))
      (is (some #{"-p"} (:args call)) "the argv is the real one, not a summary")
      (is (str/includes? (:stdin call) "what is in deps.edn")))
    (testing "and the assembled-messages event is a DIFFERENT, higher layer"
      (is (some #(= :agent.llm/request (first %)) @seen)))))

;; --- end to end, into the log ---------------------------------------------------------

(deftest a-turn-writes-its-wire-calls-to-the-log
  (let [dir (fresh-dir "wire-log")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
             :max-iterations 2 :session-id "s1"}
        sink (el/sink cfg)]
    (agent/ask "q" {:lm {:provider :claude-code :format :claude-code
                         :cli-command (stub-cli dir) :timeout-ms 30000}
                    :config cfg
                    :on-event sink})
    (let [recs (el/read-events cfg {:full? true :limit 200})
          events (set (map :event recs))]
      (testing "the wire is in the one log, which used to refuse it"
        (is (contains? events :llm.cli/call)))
      (testing "and the recorded call is whole"
        (let [call (first (filter #(= :llm.cli/call (:event %)) recs))
              longest (apply max (map count (get-in call [:data :args])))]
          (is (str/includes? (get-in call [:data :stdin]) "q"))
          (is (> longest 2000) "non-vacuous: an argv entry is longer than the old clip limit")
          (is (not (str/includes? (pr-str call) "…[truncated"))))))
    (testing "but a turn cannot read the argv back through the tools — it carries the prompt"
      (let [call (first (filter #(= :llm.cli/call (:event %)) (el/read-events cfg {:limit 200})))]
        (is (nil? (get-in call [:data :args])))
        (is (= [:args :stdin] (:withheld call)))))))
