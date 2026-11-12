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
         (hook/settings "byj hook pretooluse")))
  (let [line (hook/codex-line "byj hook pretooluse")]
    (is (str/starts-with? line "codex '-c' 'hooks.PreToolUse=[{hooks=[{type=\"command\",command=\"byj hook pretooluse\""))
    (is (str/includes? line "hooks.state=")))
  (is (= (io/file "/h/.brainyard/permissions") (hook/log-dir nil "/h")))
  (is (= (io/file "/l") (hook/log-dir "/l" "/h"))))
