;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.codex-host-test
  "Running and driving Codex sessions in tmux (byj-gly.430.3, RFC-049): the
  argv a session starts with, the hooks it carries and their trust records,
  and — on a tmux server of the test's own, with a stand-in for Codex
  (test/fixtures/fake-codex) and a Codex home of the test's own — a session
  started past its folder dialog, renamed for its thread, typed to, settled,
  stopped at an approval dialog, and closed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent-host :as ah]
            [brainyard.codex-host :as host]
            [brainyard.codex-sessions :as cxs]
            [brainyard.effect :as fx]))

(defn- fresh-dir [nm]
  (let [d (io/file "target/codex-host-test" (str nm "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(deftest a-session-starts-with-its-brief-last-and-its-home-named
  (let [argv (host/start-argv {:name "byj-cx-pending-1" :dir "/p" :home "/h" :model "gpt-x"
                               :args ["-s" "workspace-write"] :prompt "-not a flag"}
                              ["CODEX_SANDBOX"])
        tail (drop-while #(not= "codex" %) argv)]
    (is (= ["tmux" "new-session" "-d" "-s" "byj-cx-pending-1" "-x" "200" "-y" "50" "-c" "/p" "env" "-u" "CODEX_SANDBOX" "CODEX_HOME=/h"]
           (take 15 argv))
        "a pane takes the tmux server's environment: the home is passed")
    (is (= ["codex" "-m" "gpt-x" "-s" "workspace-write" "--" "-not a flag"] tail)
        "the brief last, after --, as the person's own words")
    (is (= ["codex" "resume" "-m" "gpt-x" "019f-1"]
           (drop-while #(not= "codex" %) (host/start-argv {:name "byj-cx-019f-1" :dir "/p" :resume "019f-1" :model "gpt-x"} [])))
        "resumed: `resume`, its options, then the thread")))

(deftest a-sessions-hooks-are-session-flags-with-their-trust-records
  (testing "the hash Codex 0.163 recorded for a hook it trusted (measured, RFC-049)"
    (is (= "sha256:6e1ff2217c6f213b523ffc80afcd063d79983116eb2e9321e36f1de64787cb32"
           (host/trust-hash "pre_tool_use" nil
                            {:command "HOOK_LOG=/private/tmp/claude-501/-Users-xroger88-Projects-Grumatic-brainyard-jolt/dd2740d8-9453-4a5a-9b91-ef606726cb6c/scratchpad/codex-spike/hooklog/hooks.jsonl /private/tmp/claude-501/-Users-xroger88-Projects-Grumatic-brainyard-jolt/dd2740d8-9453-4a5a-9b91-ef606726cb6c/scratchpad/codex-spike/hook.py"
                             :timeout 10}))))
  (let [flags (host/hook-flags {:PreToolUse "/bin/guard pre" :PermissionRequest "/bin/guard \"perm\""})]
    (is (= ["-c" "hooks.PermissionRequest=[{hooks=[{type=\"command\",command=\"/bin/guard \\\"perm\\\"\",timeout=30}]}]"
            "-c" "hooks.PreToolUse=[{hooks=[{type=\"command\",command=\"/bin/guard pre\",timeout=30}]}]"]
           (take 4 flags)))
    (is (str/starts-with? (last flags) "hooks.state={\"/<session-flags>/config.toml:permission_request:0:0\"={trusted_hash=\"sha256:")
        "the trust records as one inline table: a dotted key would split at config.toml's dot")
    (is (str/includes? (last flags) (host/trust-hash "pre_tool_use" nil {:command "/bin/guard pre" :timeout 30}))))
  (is (nil? (host/hook-flags {}))))

(deftest a-session-is-driven-in-tmux
  (when (ah/tmux?)
    (let [label (str "byj-cx-test-" (random-uuid))
          home (fresh-dir "home") dir (fresh-dir "project")
          fake (.getCanonicalPath (io/file "test/fixtures/fake-codex"))]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (let [started (host/start! {:dir dir :home home :codex fake :args ["--fake-trust"]})
                up (:ok (fx/run!! (host/ready started {:home home}) 30000))
                id (:id up)]
            (testing "past the folder dialog drawn over the main screen, renamed for its thread"
              (is (str/starts-with? (:name started) "byj-cx-pending-"))
              (is (true? (:trust-dialog? up)) (pr-str up))
              (is (some? id))
              (is (= #{id} (host/running)) "listed by its thread, not by its pending name")
              (is (= #{id} (host/running-in dir)))
              (is (cxs/live? home id)))
            (testing "idle before it is asked, with no rollout yet"
              (is (= :idle (:status (host/observe id {:home home}))))
              (is (nil? (cxs/rollout-file home id))))
            (testing "a line typed, taken, and the turn settled from the rollout"
              (is (:sent (:ok (fx/run!! (host/type-line id "list the files" {:home home}) 15000))))
              (let [o (:ok (fx/run!! (host/await-settled id {:home home :settle-ms 300}) 15000))]
                (is (= :done (:settled o)))
                (is (= ["list the files" "echo: list the files"] [(:prompt o) (:answer o)]))))
            (testing "more than one line is refused"
              (is (:error (:ok (fx/run!! (host/type-line id "a\nb" {:home home}) 5000)))))
            (testing "an approval dialog is waiting; Escape aborts the turn"
              (fx/run!! (host/type-line id "ASK for network" {:home home}) 15000)
              (let [o (:ok (fx/run!! (host/await-settled id {:home home}) 15000))]
                (is (= :waiting (:settled o)))
                (is (host/approval-dialog? (:screen o))))
              (is (= {:chosen "Escape"} (:ok (fx/run!! (host/choose id "Escape") 10000))))
              (let [o (:ok (fx/run!! (host/await-settled id {:home home :settle-ms 300}) 15000))]
                (is (= [:done true] [(:settled o) (:aborted? o)]))))
            (testing "closed: not running, not live"
              (is (= {:stopped id} (host/stop! id)))
              (Thread/sleep 1500)
              (is (empty? (host/running)))
              (is (false? (cxs/live? home id)))))
          (finally
            (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(def ^:private plan-opts {:tmux? true :running #{"t-live"} :project-dir "/p" :codex "codex" :home "/h"
                          :new-id (constantly "n1")})

(deftest enter-on-a-codex-row-plans-start-and-attach
  ;; byj-gly.430.4: as Claude Code's rows, but Codex chooses a new session's thread
  (testing "new: started under a pending name, then attached"
    (let [p (host/plan {:state :codex-new} plan-opts)]
      (is (= "byj-cx-pending-n1" (:id p)))
      (is (= ["tmux" "new-session" "-d" "-s" "byj-cx-pending-n1"] (take 5 (:start p))))
      (is (= ["codex"] (vec (drop-while #(not= "codex" %) (:start p)))))
      (is (= ["tmux" "attach-session" "-t" "byj-cx-pending-n1"] (:attach p)))))
  (testing "saved: resumed in a tmux session named for its thread"
    (let [p (host/plan {:state :codex-saved :session-id "t-old"} plan-opts)]
      (is (= ["codex" "resume" "t-old"] (vec (drop-while #(not= "codex" %) (:start p)))))
      (is (= ["tmux" "attach-session" "-t" "byj-cx-t-old"] (:attach p)))))
  (testing "live and running here: attached, nothing started"
    (is (= {:id "t-live" :start nil :attach ["tmux" "attach-session" "-t" "byj-cx-t-live"]}
           (host/plan {:state :codex-live :session-id "t-live"} plan-opts))))
  (testing "live in another terminal: refused"
    (is (str/includes? (:refuse (host/plan {:state :codex-live :session-id "elsewhere"} plan-opts)) "another terminal")))
  (testing "no tmux: Codex itself gets the terminal"
    (is (= {:id "t-old" :attach ["codex" "resume" "t-old"] :foreground? true}
           (host/plan {:state :codex-saved :session-id "t-old"} (assoc plan-opts :tmux? false))))
    (is (= ["codex"] (:attach (host/plan {:state :codex-new} (assoc plan-opts :tmux? false)))))))

(deftest a-session-opened-from-the-page-is-claimed-for-its-thread
  ;; byj-gly.430.4: one a person attached to is not brought up by `ready`, so
  ;; claim-pending! finds its thread by the writer lock its Codex holds
  (when (ah/tmux?)
    (let [label (str "byj-cx-claim-" (random-uuid))
          home (fresh-dir "home") dir (fresh-dir "project")
          fake (.getCanonicalPath (io/file "test/fixtures/fake-codex"))]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (let [p (host/plan {:state :codex-new} {:tmux? true :running #{} :project-dir dir :codex fake :home home
                                                  :new-id (constantly "page-1")})
                _ (apply ah/sh (:start p))
                thread (loop [n 0]
                         (or (first (filter #(cxs/live? home %)
                                            (map #(str/replace (.getName %) #"\.lock$" "")
                                                 (remove #(str/starts-with? (.getName %) ".")
                                                         (or (.listFiles (io/file home "thread-writer-locks")) [])))))
                             (when (< n 50) (Thread/sleep 100) (recur (inc n)))))]
            (is (some? thread))
            (is (empty? (host/running)) "pending: not listed by a thread yet")
            (is (= [thread] (host/claim-pending!)))
            (is (= #{thread} (host/running)))
            (is (empty? (host/claim-pending!)) "nothing left to claim"))
          (finally
            (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))


(deftest codexs-sandbox-is-asked-whether-it-can-start-only-on-linux
  ;; byj-1wv.62.12: bubblewrap needs user namespaces a container is not given
  (let [dir (doto (io/file "target/codex-host-test/sandbox") .mkdirs)
        script (fn [name body] (doto (io/file dir name) (spit (str "#!/bin/bash\n" body "\n")) (.setExecutable true)))
        ok (script "codex-ok" "[ \"$1\" = sandbox ] && [ \"$3\" = true ] && exit 0; exit 2")
        refused (script "codex-refused" "echo 'bwrap: No permissions to create a new namespace' >&2; exit 1")]
    (binding [host/*os* "Mac OS X"]
      (is (= {:ok true} (host/sandbox-check (.getCanonicalPath refused))) "macOS's Seatbelt is not asked"))
    (binding [host/*os* "Linux"]
      (is (= {:ok true} (host/sandbox-check (.getCanonicalPath ok))))
      (is (= {:ok false :why "bwrap: No permissions to create a new namespace"}
             (host/sandbox-check (.getCanonicalPath refused))))
      (is (false? (:ok (host/sandbox-check "/nonexistent/codex"))) "a CLI that is not there cannot sandbox"))))
