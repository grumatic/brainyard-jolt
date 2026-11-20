;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.delegates-test
  "A project's delegations for a program (byj-oer): `byj session delegates`'s
  rows and `byj session attach` — on the stand-in for Claude Code
  (test/fixtures/fake-claude) and a tmux server of the test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent-host :as ah]
            [brainyard.claude-agent :as ca]
            [brainyard.claude-host :as host]
            [brainyard.codex-agent :as cxa]
            [brainyard.delegates :as delegates]
            [brainyard.effect :as fx]))

(defn- fresh-dir [name]
  (let [d (io/file "target/delegates-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- git! [dir & args] (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args))

(defn- world [name]
  (let [base (fresh-dir name)
        project (doto (io/file base "project") .mkdirs)
        home (.getCanonicalPath (doto (io/file base "home") .mkdirs))]
    (spit (io/file project "README.md") "x\n")
    (git! project "init" "-q")
    (git! project "add" ".")
    (git! project "-c" "user.name=t" "-c" "user.email=t@example.invalid" "commit" "-qm" "init")
    {:project (.getCanonicalPath project)
     :config {:working-dir (.getCanonicalPath project) :project-dir (.getCanonicalPath project)
              :claude-tools? true :claude-home home
              :claude-command (str (.getCanonicalPath (io/file "test/fixtures/fake-claude")) " --fake-home " home)
              :claude-turn-timeout-ms 15000 :claude-settle-ms 300}}))

(defmacro ^:private on-own-tmux [& body]
  ;; a label no other process can draw (provenance P-61)
  `(let [label# (str "byj-test-" (random-uuid))]
     (binding [ah/*tmux* ["tmux" "-L" label#]]
       (try ~@body
            (finally (fx/sh {:out :string :err :string} "tmux" "-L" label# "kill-server"))))))

(deftest byj-runs-them-on-a-tmux-server-of-its-own
  (testing "not the person's default server: byj's, with no config file (byj-6e6)"
    (is (= ["tmux" "-L" ah/socket-name "-f" "/dev/null"] ah/*tmux*))
    (is (= "byj" ah/socket-name) "BYJ_TMUX_SOCKET is not set in the suite")))

(deftest a-delegation-is-listed-attached-and-started-again
  (when (host/tmux?)
    (let [{:keys [project config]} (world "claude")]
      (on-own-tmux
       (is (= [] (delegates/rows config)) "nothing delegated yet")
       (let [o (ca/open (assoc config :agent-instance-id "claude/x-1" :session-id "byj-parent-1")
                        {:session "new" :brief "write the parser" :task "the parser"})
             sid (:session-id o)
             attach ["tmux" "-L" (nth ah/*tmux* 2) "attach-session" "-t" (str "=byj-cc-" sid)]]
         (ca/await-turn config {:session-id sid})
         (testing "a row: its agent, the byj session that delegated it, live on byj's server, how to attach"
           (let [[r & more] (delegates/rows config)]
             (is (empty? more))
             (is (= {:agent "claude" :id sid :parent-session "byj-parent-1" :dir project :dir-exists? true
                     :live? true :tmux (str "byj-cc-" sid) :attach attach :task "the parser" :instance-id "claude/x-1"}
                    (select-keys r [:agent :id :parent-session :dir :dir-exists? :live? :tmux :attach :task :instance-id])))))
         (testing "attaching to a live one starts nothing"
           (is (= {:agent "claude" :id sid :tmux (str "byj-cc-" sid) :started? false :attach attach}
                  (delegates/attach config sid))))
         (testing "a saved one is started again on byj's server, as its delegation started it, then attached"
           (fx/sh {:out :string :err :string} "tmux" "-L" (nth ah/*tmux* 2) "kill-session" "-t" (host/tmux-name sid))
           (is (false? (:live? (first (delegates/rows config)))))
           (let [r (delegates/attach config sid)]
             (is (= {:agent "claude" :id sid :started? true :attach attach} (select-keys r [:agent :id :started? :attach])) (pr-str r))
             (is (true? (:live? (first (delegates/rows config)))))
             (is (str/includes? (str (:out (fx/sh {:out :string :err :string} "tmux" "-L" (nth ah/*tmux* 2) "list-panes" "-t" (str "=" (host/tmux-name sid)) "-F" "#{pane_start_command}")))
                                (str "--resume " sid))
                 "resumed, not a new session")))
         (testing "an id no delegation of the project has"
           (is (str/starts-with? (:error (delegates/attach config "no-such-id")) "no delegated session no-such-id"))))))))

(deftest a-delegation-whose-folder-is-gone-is-not-started-again
  (let [{:keys [project config]} (world "gone")
        gone (str project "/.brainyard/worktrees/bd-x")
        write! (fn [dir id r]
                 (let [f (io/file project ".brainyard" dir (str id ".edn"))]
                   (io/make-parents f)
                   (spit f (pr-str r))))]
    (write! "claude-agents" "c-1" {:session-id "c-1" :dir gone :state "stopped" :parent-session "p-1" :updated-at 2
                                   :stopped-why (str "its worktree " gone " was removed")})
    (write! "codex-agents" "x-1" {:session-id "x-1" :dir gone :state "done" :parent-session "p-1" :updated-at 1})
    (on-own-tmux
     (testing "both listed, newest first, their folders gone"
       (is (= [["claude" "c-1" false false] ["codex" "x-1" false false]]
              (mapv (juxt :agent :id :live? :dir-exists?) (delegates/rows config)))))
     (testing "and not started again: each says why"
       (let [c (delegates/attach config "c-1")
             x (delegates/attach config "x-1")]
         (is (str/includes? (:error c) "is gone — its worktree"))
         (is (= "claude" (:agent c)))
         (is (str/includes? (:error x) "is gone"))
         (is (= "codex" (:agent x)))
         (is (nil? (cxa/read-record config "c-1")) "a Claude Code record is not taken for a Codex one"))))))
