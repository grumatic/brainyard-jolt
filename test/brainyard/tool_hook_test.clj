;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tool-hook-test
  "Another agent's tool calls through byj's gates (RFC-012, byj-ahf.24): a
  Claude Code or Codex PreToolUse payload decided, logged and answered.
  First built under evaluation, beside the fleet's trace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]
            [brainyard.tool-hook :as hook]))

(defn- fresh-dir [name]
  (let [d (io/file "target/tool-hook-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- logged [dir]
  (vec (for [f (sort-by #(.getName %) (.listFiles (io/file dir)))
             line (str/split-lines (slurp f))]
         (read-string line))))

(deftest another-agents-calls-meet-byjs-gates
  (let [project (fresh-dir "project")
        log (fresh-dir "log")
        config {:project-dir project :working-dir project :permission-mode :ask-each-time
                :tool-deny-tools ["write-file"] :bash-ask-commands ["git push*"]}
        call (fn [tool input] {:tool_name tool :tool_input input :cwd project :session_id "cc-1"})]
    (testing "a refusal by rule is deny"
      (let [d (hook/decide config (call "Write" {:file_path "a.txt" :content "x"}))]
        (is (= :deny (:decision d)))
        (is (= "tool-deny-gate" (:gate d)))
        (is (str/includes? (:reason d) ":tool-deny-tools"))))
    (testing "what would be asked of a person is ask: the agent asks"
      (let [d (hook/decide config (call "Read" {:file_path "/etc/hosts"}))]
        (is (= :ask (:decision d)))
        (is (str/includes? (:reason d) "/etc/hosts")))
      (is (= :ask (:decision (hook/decide config (call "Bash" {:command "git push origin main"}))))))
    (testing "nothing objects: no opinion, the agent's own rules decide"
      (is (nil? (:decision (hook/decide config (call "Read" {:file_path "deps.edn"})))))
      (is (nil? (hook/hook-output {:decision nil}))))
    (testing "a tool byj has no counterpart for gets no opinion"
      (is (= {:decision nil :tool nil} (hook/decide config (call "mcp__x__y" {})))))
    (testing "the hook's answer is what the agent reads, and every decision is logged"
      (let [out (json/read-str (hook/hook! config (call "Write" {:file_path "a.txt"}) log))]
        (is (= {:hookEventName "PreToolUse" :permissionDecision "deny"}
               (select-keys (:hookSpecificOutput out) [:hookEventName :permissionDecision])))
        (is (str/starts-with? (get-in out [:hookSpecificOutput :permissionDecisionReason]) "brainyard: ")))
      (is (nil? (hook/hook! config (call "Read" {:file_path "deps.edn"}) log)))
      (is (= [[:claude-code "cc-1" "Write" :deny] [:claude-code "cc-1" "Read" :no-opinion]]
             (map (juxt :agent :session :tool :decision) (logged log)))))
    (testing "a hook that fails has no opinion, and says why in the log"
      (is (nil? (hook/hook! (assoc config :tool-deny-tools 42) (call "Write" {:file_path "a.txt"}) log)))
      (is (:error (last (logged log)))))
    (testing "a Codex payload is told apart by its turn_id and model"
      (is (= :codex (hook/hook-agent {:turn_id "t" :model "m"})))
      (is (= :claude-code (hook/hook-agent {:session_id "s"}))))))

(deftest codex-is-refused-what-claude-code-is-asked-about
  ;; Codex's PreToolUse rejects an ask as unsupported and blocks only on a
  ;; deny with a reason (openai/codex hooks/src/engine/output_parser.rs;
  ;; byj-2wq), so what a gate would ask a person about is refused for it
  (let [project (fresh-dir "codex-ask")
        log (fresh-dir "codex-ask-log")
        config {:project-dir project :working-dir project :permission-mode :ask-each-time
                :tool-deny-tools ["write-file"] :bash-ask-commands ["git push*"]}
        claude {:tool_name "Bash" :tool_input {:command "git push origin main"} :cwd project :session_id "cc-1"}
        codex (assoc claude :session_id "cx-1" :turn_id "t-1" :model "gpt-5.5" :permission_mode "default")
        answer (fn [payload] (:hookSpecificOutput (json/read-str (hook/hook! config payload log))))]
    (is (= "ask" (:permissionDecision (answer claude))) "Claude Code still asks the person")
    (let [out (answer codex)]
      (is (= "deny" (:permissionDecision out)))
      (is (str/includes? (:permissionDecisionReason out) "git push origin main") "the gate's words, for the model")
      (is (str/includes? (:permissionDecisionReason out) "Codex hook cannot ask")))
    (is (= [[:claude-code :ask] [:codex :deny]] (map (juxt :agent :decision) (logged log)))
        "the log says what each agent was answered")
    (testing "what Codex is answered otherwise is as Claude Code's"
      (is (= "deny" (:permissionDecision (:hookSpecificOutput (json/read-str (hook/hook! config (assoc codex :tool_name "Write" :tool_input {:file_path "a.txt"}) log))))))
      (is (nil? (hook/hook! config (assoc codex :tool_name "Read" :tool_input {:file_path "deps.edn"}) log))))))

(deftest another-agent-writes-no-project-loop
  ;; byj-ng7.12: the gate that keeps a byj tool from making a loop live keeps
  ;; Claude Code's and Codex's edits from it too, whatever the mode
  (let [project (fresh-dir "loops")
        config {:project-dir project :working-dir project :permission-mode :auto-approve}
        decide #(hook/decide config (merge {:cwd project :session_id "s"} %))]
    (is (= :deny (:decision (decide {:tool_name "Write" :tool_input {:file_path ".brainyard/loops/react.edn" :content "[]"}}))))
    (is (= :deny (:decision (decide {:tool_name "Edit" :tool_input {:file_path (str project "/.brainyard/loops/react.edn")}}))))
    (is (= :deny (:decision (decide {:tool_name "apply_patch" :turn_id "t" :model "m"
                                     :tool_input {:command "*** Begin Patch\n*** Add File: .brainyard/loops/react.edn\n*** End Patch"}}))))
    (is (str/includes? (:reason (decide {:tool_name "Bash" :tool_input {:command "cp x.edn .brainyard/loops/react.edn"}}))
                       "project's loop"))
    (is (nil? (:decision (decide {:tool_name "Read" :tool_input {:file_path ".brainyard/loops/react.edn"}}))) "read, it is not")))

(deftest a-codex-patch-is-every-file-it-names
  (is (= ["a.clj" "b.clj" "c.md" "d.txt"]
         (hook/patch-paths "*** Begin Patch\n*** Update File: a.clj\n*** Move to: b.clj\n*** Add File: c.md \n*** Delete File: d.txt\n*** End Patch"))
      "a move's destination too: an earlier version dropped it")
  (let [project (fresh-dir "patch")
        config {:project-dir project :working-dir project :permission-mode :deny-by-default :allowed-dirs [project]}
        patch (fn [to] (str "*** Begin Patch\n*** Update File: a.clj\n*** Move to: " to "\n*** End Patch"))
        decide #(:decision (hook/decide config {:tool_name "apply_patch" :tool_input {:command (patch %)} :cwd project}))]
    (is (nil? (decide "b.clj")) "within the project: no objection")
    (is (= :deny (decide "/etc/moved.clj")) "moved out of the allowed directories: refused")))

(deftest the-hook-is-installed-as-byj-hook-pretooluse
  (is (= "byj hook pretooluse" (hook/hook-command "byj" nil)))
  (is (= "byj hook pretooluse --log /x" (hook/hook-command "byj" "/x")))
  (is (= {:hooks {:PreToolUse [{:matcher "*" :hooks [{:type "command" :command "byj hook pretooluse"}]}]}}
         (hook/claude-settings "byj hook pretooluse")))
  (let [line (hook/codex-line "byj hook pretooluse")]
    (is (str/starts-with? line "codex '-c' 'hooks.PreToolUse=[{hooks=[{type=\"command\",command=\"byj hook pretooluse\""))
    (is (str/includes? line "hooks.state=")))
  (is (= (io/file "/h/.brainyard/permissions") (hook/log-dir nil "/h")))
  (is (= (io/file "/l") (hook/log-dir "/l" "/h"))))
