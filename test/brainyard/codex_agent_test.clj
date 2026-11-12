;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.codex-agent-test
  "agent$codex (byj-gly.430.6, RFC-049): its loop over the codex$* commands —
  a new Codex session in a worktree of its own, briefed, guided, judged and
  handed back — its commands registered for it alone, its tree sound, and a
  hand-off that commits what the session changed (it cannot, in Codex's
  sandbox) and says how to publish it. On the stand-in for Codex
  (test/fixtures/fake-codex), with a Codex home and a tmux server of the
  test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.agent-host :as ah]
            [brainyard.agents :as agents]
            [brainyard.claude-agent :as claude-agent]
            [brainyard.codex-agent :as cxa]
            [brainyard.codex-host :as host]
            [brainyard.effect :as fx]
            [brainyard.flow :as flow]
            [brainyard.json :as json]))

(defn- fresh-dir [name]
  (let [d (io/file "target/codex-agent-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- git! [dir & args] (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args))

(defn- world
  "A git project with an identity of its own, a Codex home, and the config
  that points agent$codex at the stand-in."
  [name]
  (let [base (fresh-dir name)
        project (doto (io/file base "project") .mkdirs)
        home (.getCanonicalPath (doto (io/file base "home") .mkdirs))]
    (spit (io/file project "README.md") "x\n")
    (git! project "init" "-q")
    (git! project "config" "user.name" "t")
    (git! project "config" "user.email" "t@example.invalid")
    (git! project "add" ".")
    (git! project "commit" "-qm" "init")
    {:home home
     :project (.getCanonicalPath project)
     :config {:working-dir (.getCanonicalPath project) :project-dir (.getCanonicalPath project)
              :codex-tools? true :codex-home home
              :codex-command (.getCanonicalPath (io/file "test/fixtures/fake-codex"))
              :codex-turn-timeout-ms 15000
              ;; the stand-in settles at once: codex-host-test's own 300 ms (byj-gly.442)
              :codex-settle-ms 300}}))

(defmacro ^:private on-own-tmux [& body]
  `(let [label# (str "byj-cx-agent-" (random-uuid))]
     (binding [ah/*tmux* ["tmux" "-L" label#]]
       (try ~@body
            (finally (fx/sh {:out :string :err :string} "tmux" "-L" label# "kill-server"))))))

(defn- loop-mock
  "The predictors, answered by which one asks: plan starts a new session in a
  worktree; progress guides the first turn and accepts the second."
  [seen]
  (fn [messages]
    (let [sys (str (:content (first messages)))
          user (str (:content (last messages)))]
      (json/write-str
       (cond
         (str/includes? sys "Decide which session takes it")
         (do (swap! seen conj [:plan (str/includes? user "no Codex sessions")])
             {:reasoning "r" :session "new" :model "" :args "" :worktree true :branch "bd/byj-9"
              :brief "Fix the parser for bead byj-9." :done-when "the parser test passes" :choice "" :bead "byj-9"})
         (str/includes? sys "Write the pull request")
         (do (swap! seen conj [:pr]) {:title "Fix the parser (byj-9)" :body "Fixed."})
         (str/includes? sys "Its turn has just ended")
         (let [n (count (filter #(= :progress (first %)) @seen))]
           (swap! seen conj [:progress (second (re-find #"echo: ([^\n]+)" user))])
           (if (zero? n)
             {:reasoning "r" :verdict "guide" :guidance "Now run the parser test." :report "guided"}
             {:reasoning "r" :verdict "done" :guidance "" :report "Done: the parser test passes, on bd/byj-9."}))
         :else {:reasoning "r" :answer "?"})))))

(deftest the-loop-briefs-guides-and-hands-back
  (when (ah/tmux?)
    (let [{:keys [project config]} (world "loop")
          seen (atom [])]
      (on-own-tmux
       (let [r (agent/ask "Fix bead byj-9"
                          {:lm {:provider :mock :format :mock :responses (loop-mock seen)}
                           :tree (agent/load-tree "brainyard/loops/codex.edn")
                           :config (assoc config :codex-max-rounds 4 :session-id "loop-1")})
             rec (first (cxa/records config))
             wt (.getCanonicalPath (io/file project ".brainyard" "worktrees" "bd-byj-9"))]
         (testing "planned against the sessions there were, then judged each turn on what it answered"
           (is (= [[:plan true] [:progress "Fix the parser for bead byj-9."] [:progress "Now run the parser test."] [:pr]]
                  @seen)))
         (testing "the answer is Codex's last reply, then the report, then the hand-off"
           (is (re-find #"^echo: Now run the parser test\.\n\nDone: the parser test passes, on bd/byj-9\.\n\nNothing to publish: bd/byj-9 has no commits past "
                        (:answer r))
               (:answer r)))
         (testing "its record says how it ended, in its worktree on its branch"
           (is (= ["done" "done" "bd/byj-9" wt 2 "loop-1" "byj-9"]
                  ((juxt :state :verdict :branch :dir :round :parent-session :bead) rec)))))))))

(deftest the-hand-off-commits-what-the-session-changed-and-says-how-to-publish-it
  (let [{:keys [project config]} (world "handoff")
        wt (:dir (claude-agent/add-worktree config "bd/byj-7"))]
    (git! wt "config" "user.name" "t")
    (git! wt "config" "user.email" "t@example.invalid")
    (.mkdirs (cxa/records-dir config))
    (spit (io/file (cxa/records-dir config) "t-1.edn")
          (pr-str {:session-id "t-1" :dir wt :branch "bd/byj-7" :task "Add the parser" :updated-at 1}))
    (spit (io/file wt "parser.txt") "parsed\n")
    (let [{:keys [summary committed?]} (cxa/handoff config {:session-id "t-1" :title "Add the parser (byj-7)"})]
      (is (true? committed?))
      (is (str/includes? summary "Committed the session's changes (it cannot commit in Codex's sandbox): Add the parser (byj-7)"))
      (is (str/includes? summary "1 commit past "))
      (is (str/includes? summary "parser.txt"))
      (is (str/includes? summary (str "git -C " wt " push -u origin bd/byj-7")))
      (is (str/includes? summary "Not pushed: a Codex session does not publish."))
      (is (= "Add the parser (byj-7)" (str/trim (:out (git! wt "log" "-1" "--format=%s"))))))
    (is (str/includes? (:summary (cxa/handoff config {:session-id "t-1"})) "1 commit past ")
        "asked again with nothing new: nothing more committed")
    (is (= "No hand-off: there is no record of that session." (:summary (cxa/handoff config {:session-id "nope"}))))))

(deftest agent-codex-runs-its-own-loop-with-its-commands
  (let [{:keys [config]} (world "spec")
        spec (first (filter #(= "codex" (:name %)) (:agents (agents/load-agents (dissoc config :codex-tools?)))))
        opts (#'agents/child-opts spec {:config (dissoc config :codex-tools?)})
        ctx (agent/make-ctx (select-keys opts [:config]))]
    (is (= :cx/turn (:id (second (:tree opts)))) "its tree")
    (is (true? (:codex-tools? (:config opts))) "its config turns the commands on")
    (is (= #{"codex$sessions" "codex$open" "codex$await" "codex$say" "codex$choose" "codex$handoff" "codex$finish"}
           (set (filter #(str/starts-with? % "codex$") (keys @(:tools ctx))))))
    (is (not-any? #(str/starts-with? % "codex$") (keys @(:tools (agent/make-ctx {:config (dissoc config :codex-tools?)}))))
        "no other turn has them")))

(deftest the-loop-is-sound
  (let [{:keys [config]} (world "sound")]
    (is (nil? (:problems (flow/check-tree-name "brainyard/loops/codex.edn"
                                               (flow/ctx-opts (agent/make-ctx {:config config}))))))))

(deftest a-dialog-is-answered-with-the-keys-codex-takes
  (is (= {"1" "y" "2" "p" "3" "Escape"} cxa/choice-keys))
  (is (str/includes? (:error (cxa/choose {} "t" "9")) "1, 2 or 3")))

(defn- codex-without-sandbox
  "The stand-in for Codex, but whose sandbox cannot start, as bubblewrap's
  cannot in a container (byj-1wv.62.12): `codex sandbox` fails as
  Codex 0.163 does there; anything else is the stand-in, its arguments logged."
  [base]
  (let [f (io/file base "codex-no-sandbox")
        log (io/file base "codex-args.log")]
    (spit f (str "#!/bin/bash\n"
                 "if [ \"$1\" = sandbox ]; then echo 'bwrap: No permissions to create a new namespace, likely because the kernel does not allow non-privileged user namespaces.' >&2; exit 1; fi\n"
                 "echo \"$@\" >> '" (.getCanonicalPath log) "'\n"
                 "exec '" (.getCanonicalPath (io/file "test/fixtures/fake-codex")) "' \"$@\"\n"))
    (.setExecutable f true)
    [(.getCanonicalPath f) log]))

(deftest where-codexs-sandbox-cannot-start-the-session-has-the-publish-guard-alone
  ;; RFC-049 decision 7 where its sandbox is not there to be had (byj-1wv.62.12)
  (on-own-tmux
   (let [{:keys [project config]} (world "no-sandbox")
         [codex log] (codex-without-sandbox (.getParent (io/file project)))
         config (assoc config :codex-command codex)]
     (binding [host/*os* "Linux"]
       (testing "with :codex-require-sandbox?, nothing is opened, and it says why"
         (let [r (cxa/open (assoc config :codex-require-sandbox? true) {:session "new" :brief "Fix the parser."})]
           (is (str/includes? (str (:error r)) "Codex's sandbox cannot start here (bwrap: No permissions to create a new namespace"))
           (is (not (.exists log)) "no session was started")))
       (testing "else the session runs unsandboxed, the guard its hook, and the delegation says so"
         (let [r (cxa/open config {:session "new" :brief "Fix the parser."})]
           (is (= "danger-full-access" (:sandbox r)) (pr-str r))
           (is (= cxa/guard-alone-note (:note r)))
           (is (str/includes? (slurp log) "-s danger-full-access"))
           (is (not (str/includes? (slurp log) "workspace-write")))
           (is (= "danger-full-access" (:sandbox (cxa/read-record config (:session-id r)))))))))))

(deftest where-codexs-sandbox-starts-the-session-is-in-it
  (on-own-tmux
   (let [{:keys [project config]} (world "with-sandbox")
         [_ log] (codex-without-sandbox (.getParent (io/file project)))
         ok (doto (io/file (.getParent (io/file project)) "codex-with-sandbox")
              (spit (str "#!/bin/bash\n[ \"$1\" = sandbox ] && exit 0\necho \"$@\" >> '" (.getCanonicalPath log) "'\n"
                         "exec '" (.getCanonicalPath (io/file "test/fixtures/fake-codex")) "' \"$@\"\n"))
              (.setExecutable true))
         r (binding [host/*os* "Linux"]
             (cxa/open (assoc config :codex-command (.getCanonicalPath ok)) {:session "new" :brief "Fix the parser."}))]
     (is (= "workspace-write" (:sandbox r)) (pr-str r))
     (is (nil? (:note r)))
     (is (str/includes? (slurp log) "-s workspace-write")))))
