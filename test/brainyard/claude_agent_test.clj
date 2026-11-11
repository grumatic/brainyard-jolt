(ns brainyard.claude-agent-test
  "agent$claude (RFC-039, byj-gly.290.3): the claude$* commands, and the loop
  that drives a Claude Code session with them — on a stand-in for Claude Code
  (test/fixtures/fake-claude) and a tmux server of the test's own, with the
  two predictors answered by a mock model."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.agents :as agents]
            [brainyard.claude-agent :as ca]
            [brainyard.agent-host :as ah]
            [brainyard.claude-host :as host]
            [brainyard.claude-sessions :as cs]
            [brainyard.effect :as fx]
            [brainyard.flow :as flow]
            [brainyard.json :as json]
            [brainyard.trajectory :as trajectory]))

(defn- fresh-dir [name]
  (let [d (io/file "target/claude-agent-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- git! [dir & args] (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args))

(defn- world
  "A project that is a git repository, a Claude Code home, and the config that
  points agent$claude at the stand-in."
  [name]
  (let [base (fresh-dir name)
        project (doto (io/file base "project") .mkdirs)
        home (.getCanonicalPath (doto (io/file base "home") .mkdirs))]
    (spit (io/file project "README.md") "x\n")
    (git! project "init" "-q")
    (git! project "add" ".")
    (git! project "-c" "user.name=t" "-c" "user.email=t@example.invalid" "commit" "-qm" "init")
    {:home home
     :project (.getCanonicalPath project)
     :config {:working-dir (.getCanonicalPath project) :project-dir (.getCanonicalPath project)
              :claude-tools? true :claude-home home
              :claude-command (str (.getCanonicalPath (io/file "test/fixtures/fake-claude")) " --fake-home " home)
              :claude-turn-timeout-ms 15000
              ;; the stand-in settles at once: claude-host-test's own 300 ms,
              ;; not a real session's 1.5 s on every turn awaited (byj-gly.442)
              :claude-settle-ms 300}}))

(defmacro ^:private on-own-tmux [& body]
  ;; a label no other process can draw: claude-host-test's, named by the same
  ;; millisecond in another shard, was once this one's server (provenance P-61)
  `(let [label# (str "byj-test-" (random-uuid))]
     (binding [ah/*tmux* ["tmux" "-L" label#]]
       (try ~@body
            (finally (fx/sh {:out :string :err :string} "tmux" "-L" label# "kill-server"))))))

(defn- prompts
  "What the session was told, oldest first, from its transcript."
  [home dir sid]
  (let [f (host/transcript sid dir home)]
    (if-not (.isFile f)
      []
      (vec (for [l (str/split-lines (slurp f))
                 :let [m (json/read-str l)]
                 :when (and (= "user" (:type m)) (string? (get-in m [:message :content])))]
             (get-in m [:message :content]))))))

(deftest the-commands-start-a-session-in-a-worktree-and-guide-it
  (when (host/tmux?)
    (let [{:keys [home project config]} (world "commands")]
      (on-own-tmux
       (testing "nothing yet"
         (is (= "(no Claude Code sessions in this project)" (:text (ca/sessions config)))))
       (let [o (ca/open (assoc config :agent-instance-id "claude/x-1")
                        {:session "new" :worktree true :branch "bd/t-1" :brief "write the parser" :task "the parser"})
             sid (:session-id o)
             wt (.getCanonicalPath (io/file project ".brainyard" "worktrees" "bd-t-1"))]
         (testing "a new session, in a worktree of its own on the branch, briefed by its argument"
           (is (= ["new" wt] ((juxt :how :dir) o)))
           (is (= "auto" (:permission-mode (ca/read-record config sid))) "in the mode asked for, auto by default")
           (is (str/includes? (:where o) "branch bd/t-1"))
           (is (= "bd/t-1" (str/trim (:out (git! wt "branch" "--show-current"))))))
         (testing "its turn awaited: settled, its answer, the round, nothing spent past the cap"
           (let [a (ca/await-turn config {:session-id sid})]
             (is (= {:settled "done" :answer "echo: write the parser" :prompt "write the parser" :round "1" :over-budget "no"}
                    (select-keys a [:settled :answer :prompt :round :over-budget])))
             (is (and (number? (:usage-cost-usd a)) (<= 0.0 (:usage-cost-usd a) (:cost-usd a)))
                 "what this wait added to the spend, for the calling turn's usage (byj-1wv.17)")))
         (testing "listed as this instance's own, with its task and branch — and not another's"
           (let [line (:text (ca/sessions (assoc config :agent-instance-id "claude/x-1")))]
             (is (str/includes? line (str sid " | running here idle | YOURS | branch bd/t-1")))
             (is (str/includes? line "was given: the parser")))
           (is (not (str/includes? (:text (ca/sessions (assoc config :agent-instance-id "claude/other"))) "YOURS"))))
         (testing "one short line is typed; anything longer is a file it is pointed at"
           (is (= {:sent true :via "typed"} (ca/say config sid "now the tests")))
           (ca/await-turn config {:session-id sid})
           (is (= {:sent true :via "file"} (ca/say config sid "step one\nstep two")))
           (ca/await-turn config {:session-id sid})
           (let [[_ typed pointer] (prompts home wt sid)
                 path (second (re-find #"^Read (\S+) and follow it" pointer))]
             (is (= "now the tests" typed))
             (is (= "step one\nstep two" (slurp path)) "the file holds the whole message")
             (is (str/starts-with? path (.getCanonicalPath (ca/records-dir config))))))
         (testing "a delegation's cost is past its cap when it is"
           (is (str/starts-with? (:over-budget (ca/await-turn (assoc config :claude-max-cost-usd -1.0) {:session-id sid})) "yes")))
         (testing "the same branch again finds its worktree rather than failing"
           (is (= {:dir wt :branch "bd/t-1" :existing true} (ca/add-worktree config "bd/t-1"))))
         (testing "stopped, then continued: the saved session is resumed and given the brief once it is idle"
           (fx/sh {:out :string :err :string} "tmux" "-L" (nth ah/*tmux* 2) "kill-session" "-t" (host/tmux-name sid))
           (let [o (ca/open (assoc config :agent-instance-id "claude/x-1") {:session sid :brief "carry on"})]
             (is (= ["resumed" wt] ((juxt :how :dir) o)) (pr-str o))
             (is (= 0.0 (:cost-usd (ca/read-record config sid)))
                 "a new delegation's spend starts over, so its first wait's :usage-cost-usd is its own (byj-1wv.17)")
             (is (= "echo: carry on" (:answer (ca/await-turn config {:session-id sid}))))))
         (testing "told to publish, a session running without the right is started again with it (byj-gly.307)"
           (is (false? (:publish (ca/read-record config sid))))
           (let [o (ca/open (assoc config :agent-instance-id "claude/x-1") {:session sid :brief "push it" :publish true})]
             (is (= "resumed" (:how o)) (pr-str o))
             (is (str/includes? (:where o) "started again with the right to push"))
             (is (true? (:publish (ca/read-record config sid))))
             (is (= "echo: push it" (:answer (ca/await-turn config {:session-id sid})))))
           (testing "and once it has it, continued as it is"
             (is (= "continued" (:how (ca/open (assoc config :agent-instance-id "claude/x-1") {:session sid :brief "and again" :publish true}))))
             (ca/await-turn config {:session-id sid})
             (is (true? (:publish (ca/read-record config sid))))))
         (testing "how it ended is recorded"
           (ca/finish config {:session-id sid :verdict "done" :report "parsed"})
           (is (= ["done" "done" "parsed" "claude/x-1"] ((juxt :state :verdict :report :instance-id) (ca/read-record config sid))))))
       (testing "a session that is not here is refused, with what to give instead"
         (is (str/includes? (:error (ca/open config {:session "nope" :brief "x"})) "give \"new\"")))))))

(deftest a-reply-before-the-wait-is-still-streamed
  ;; byj-gly.482: await-turn counted progress from where the session stood
  ;; when the wait began, so a reply that landed between claude$say (or the
  ;; brief) and the wait — under the suite's load — was never streamed. It
  ;; counts from where the session stood when it was spoken to.
  (when (host/tmux?)
    (let [{:keys [config]} (world "early-reply")]
      (on-own-tmux
       (let [sid (:session-id (ca/open config {:session "new" :brief "write the parser"}))
             replied (fn [answer]
                       (fx/run!! (fx/poll-until 100 ::not-yet #(if (= answer (:answer (host/observe sid {:home (:claude-home config)})))
                                                                 :yes ::not-yet))
                                 15000))
             streamed (fn []
                        (let [lines (atom [])]
                          (ca/await-turn config {:session-id sid}
                                         {:on-event (fn [e d] (when (= :agent.tree/step-progress e) (swap! lines conj (:line d))))})
                          @lines))]
         (testing "a new session's reply to its brief, landed before the wait"
           (is (= {:ok :yes} (replied "echo: write the parser")))
           (is (some #(str/includes? % "echo: write the parser") (streamed))))
         (testing "a reply to what was said, landed before the wait"
           (is (= {:sent true :via "typed"} (ca/say config sid "now the tests")))
           (is (= {:ok :yes} (replied "echo: now the tests")))
           (let [lines (streamed)]
             (is (some #(str/includes? % "echo: now the tests") lines))
             (is (not-any? #(str/includes? % "write the parser") lines) "what an earlier wait streamed is not streamed again")))
         (testing "a wait with nothing said since streams nothing"
           (is (empty? (streamed)))))))))

(defn- loop-mock
  "The two predictors, answered by which one is asking: plan starts a new
  session in a worktree; progress guides the first turn and accepts the
  second."
  [seen]
  (fn [messages]
    (let [sys (str (:content (first messages)))
          user (str (:content (last messages)))]
      (json/write-str
       (cond
         (str/includes? sys "Decide which session takes it")
         (do (swap! seen conj [:plan (str/includes? user "no Claude Code sessions")])
             {:reasoning "r" :session "new" :model "haiku" :args "" :worktree true :branch "bd/byj-9"
              :brief "Fix the parser for bead byj-9." :done-when "the parser test passes"})
         (str/includes? sys "Write the pull request")
         (do (swap! seen conj [:pr]) {:title "Fix the parser (byj-9)" :body "Fixed."})
         (str/includes? sys "Its turn has just ended")
         (let [n (count (filter #(= :progress (first %)) @seen))]
           (swap! seen conj [:progress (second (re-find #"echo: ([^\n]+)" user))])
           (if (zero? n)
             {:reasoning "r" :verdict "guide" :guidance "Now run the parser test." :report "guided"}
             {:reasoning "r" :verdict "done" :guidance "" :report "Done: the parser test passes, on bd/byj-9."}))
         :else {:reasoning "r" :answer "?"})))))

(deftest the-loop-briefs-guides-and-reports
  (when (host/tmux?)
    (let [{:keys [home project config]} (world "loop")
          seen (atom [])]
      (on-own-tmux
       (let [events (atom [])
             r (agent/ask "Fix bead byj-9"
                          {:lm {:provider :mock :format :mock :responses (loop-mock seen)}
                           :tree (agent/load-tree "brainyard/loops/claude.edn")
                           :on-event (fn [e d] (when (= "agent.tree" (namespace e)) (swap! events conj [e d])))
                           :config (assoc config :claude-max-rounds 4 :session-id "loop-1")})
             rec (first (ca/records config))
             wt (.getCanonicalPath (io/file project ".brainyard" "worktrees" "bd-byj-9"))]
         (testing "planned against the sessions there were, then judged each turn on what it answered"
           (is (= [[:plan true]
                   [:progress "Fix the parser for bead byj-9."]
                   [:progress "Now run the parser test."]
                   [:pr]]
                  @seen)))
         (testing "the answer is Claude Code's last reply, then the report, then the hand-off: here, nothing to publish"
           (is (re-find #"^echo: Now run the parser test\.\n\nDone: the parser test passes, on bd/byj-9\.\n\nNothing to publish: bd/byj-9 has no commits past "
                        (:answer r))))
         (testing "the session was briefed by its argument and guided by a typed line, in its worktree"
           (is (= ["Fix the parser for bead byj-9." "Now run the parser test."]
                  (prompts home wt (:session-id rec)))))
         (testing "its record says how it ended"
           (is (= ["done" "done" "bd/byj-9" wt 2] ((juxt :state :verdict :branch :dir :round) rec))))
         (testing "every step says it started and what it kept, and Claude Code's work streams while it is awaited (byj-gly.299)"
           (let [starts (map (comp :name second) (filter #(= :agent.tree/step-start (first %)) @events))
                 ends (filter #(= :agent.tree/step-end (first %)) @events)
                 progress (map (comp :line second) (filter #(= :agent.tree/step-progress (first %)) @events))]
             (is (= ["claude$sessions" "claude/plan" "claude$open" "claude$await" "claude/progress" "claude$say"
                     "claude$await" "claude/progress" "claude/pr" "claude$handoff" "claude$finish"]
                    starts))
             (is (= (count starts) (count ends)) "each one ended")
             (is (some #(str/includes? (str (:result (second %))) "verdict guide") ends) "a judgement says its verdict")
             (is (some #(= "\"echo: Now run the parser test.\"" %) progress)
                 "Claude Code's reply arrived as progress while its turn was awaited")))
         (testing "and which session delegated it, whose trajectory holds the turn (byj-gly.298.1)"
           (is (= "loop-1" (:parent-session rec)))
           (is (= [true "Fix bead byj-9"]
                  ((juxt :success :question) (last (trajectory/read-trajectories {:project-dir project} "loop-1")))))))))))

(deftest a-task-that-says-publish-has-the-session-publish
  ;; byj-gly.307: the plan says publish, so the session starts with the right
  ;; to, and the loop writes no pull request of its own: it hands back what was
  ;; published
  (when (host/tmux?)
    (let [{:keys [config]} (world "loop-publish")
          steps (atom [])
          r (on-own-tmux
             (agent/ask "Fix bead byj-9, push it and open a PR"
                        {:lm {:provider :mock :format :mock
                              :responses (fn [messages]
                                           (json/write-str
                                            (if (str/includes? (str (:content (first messages))) "Decide which session takes it")
                                              {:reasoning "r" :session "new" :model "" :args "" :worktree true :branch "bd/byj-9"
                                               :brief "Fix it, push it, open the PR." :done-when "a PR URL" :publish true :cleanup false}
                                              {:reasoning "r" :verdict "done" :guidance "" :report "Done: PR opened."})))}
                         :tree (agent/load-tree "brainyard/loops/claude.edn")
                         :on-event (fn [e d] (when (= e :agent.tree/step-start) (swap! steps conj (:name d))))
                         :config (assoc config :claude-max-rounds 2)}))]
      (is (= ["claude$sessions" "claude/plan" "claude$open" "claude$await" "claude/progress" "claude$handoff" "claude$finish"]
             @steps))
      (is (true? (:publish (first (ca/records config)))) "started with the right to publish")
      (is (str/starts-with? (:answer r) "echo: Fix it, push it, open the PR.\n\nDone: PR opened.\n\nNothing to publish: bd/byj-9 has no commits past ")
          (pr-str (:answer r))))))

(deftest a-delegation-that-cannot-start-says-why
  (let [{:keys [config]} (world "unreached")
        r (agent/ask "Fix bead byj-9"
                     {:lm {:provider :mock :format :mock
                           :responses (fn [_] (json/write-str {:reasoning "r" :session "gone-1" :model "" :args ""
                                                               :worktree false :branch "" :brief "x" :done-when "y"}))}
                      :tree (agent/load-tree "brainyard/loops/claude.edn")
                      :config config})]
    (is (str/includes? (:answer r) "no session gone-1 here"))))

(deftest agent-claude-runs-its-own-loop-with-its-commands
  (let [{:keys [config]} (world "spec")
        spec (first (filter #(= "claude" (:name %)) (:agents (agents/load-agents (dissoc config :claude-tools?)))))
        opts (#'agents/child-opts spec {:config (dissoc config :claude-tools?)})
        ctx (agent/make-ctx (select-keys opts [:config]))]
    (is (= :cc/turn (:id (second (:tree opts)))) "its tree")
    (is (true? (:claude-tools? (:config opts))) "its config turns the commands on")
    (is (= #{"claude$sessions" "claude$worktree" "claude$open" "claude$await" "claude$say" "claude$choose" "claude$handoff" "claude$finish"}
           (set (filter #(str/starts-with? % "claude$") (keys @(:tools ctx)))))
        "and they are what it can call")
    (is (not-any? #(str/starts-with? % "claude$") (keys @(:tools (agent/make-ctx {:config (dissoc config :claude-tools?)}))))
        "no other turn has them")))

(deftest a-dialog-goes-to-the-person-and-their-answer-to-the-dialog
  ;; the escalation round trip: the session waits on a permission dialog, the
  ;; delegation stops and asks; asked again with the person's answer, the same
  ;; instance finds its session waiting and answers the dialog, not a brief
  (when (host/tmux?)
    (let [{:keys [config]} (world "dialog")
          config (assoc config :agent-instance-id "claude/dlg-1")
          plans (atom [])
          mock (fn [messages]
                 (let [sys (str (:content (first messages))) user (str (:content (last messages)))]
                   (json/write-str
                    (if (str/includes? sys "Decide which session takes it")
                      (let [mine (second (re-find #"- (\S+) \| running here waiting \| YOURS" user))]
                        (swap! plans conj (if mine :continue :new))
                        (if mine
                          {:reasoning "r" :session mine :model "" :args "" :worktree false :branch ""
                           :brief "" :done-when "it ran" :choice "1"}
                          {:reasoning "r" :session "new" :model "" :args "" :worktree false :branch ""
                           :brief "please ASK first" :done-when "it ran" :choice ""}))
                      (cond
                        (str/includes? user "settled: waiting")
                        {:reasoning "r" :verdict "ask-person" :guidance "" :report "It asks to run a command. Allow it?"}
                        :else {:reasoning "r" :verdict "done" :guidance "" :report "It ran."})))))
          ask #(agent/ask % {:lm {:provider :mock :format :mock :responses mock}
                             :tree (agent/load-tree "brainyard/loops/claude.edn") :config config})]
      (on-own-tmux
       (is (str/ends-with? (:answer (ask "run the thing")) "It asks to run a command. Allow it?") "the report, after whatever the session said")
       (is (= "needs-person" (:state (first (ca/records config)))))
       (is (str/ends-with? (:answer (ask "yes, allow it")) "It ran."))
       (is (= [:new :continue] @plans))
       (is (= "done" (:state (first (ca/records config)))))))))

(deftest the-loop-is-sound
  ;; behaviour, edited by hand: a key a node reads that nothing writes, or a
  ;; command output that is not declared, is caught here
  (let [{:keys [config]} (world "sound")]
    (is (nil? (:problems (flow/check-tree-name "brainyard/loops/claude.edn"
                                               (flow/ctx-opts (agent/make-ctx {:config config}))))))))

;; --- publishing: ask, push, the pull request, the bead (byj-gly.290.4) -----------------

(defn- published-world
  "A project with an origin to push to, a delegation on branch bd/p-1 in its
  own worktree, and stand-ins for gh and bd that log their calls."
  [name]
  (let [{:keys [project config] :as w} (world name)
        origin (str project "-origin.git")
        gh-log (str project "-gh.log")
        bd-log (str project "-bd.log")]
    (doseq [f (reverse (file-seq (io/file origin)))] (.delete f))
    (doseq [f [gh-log (str gh-log ".prs") bd-log]] (io/delete-file f true))
    (git! project "init" "-q" "--bare" origin)
    (git! project "remote" "add" "origin" origin)
    (let [config (assoc config
                        :claude-gh-command (str (.getCanonicalPath (io/file "test/fixtures/fake-gh")) " " gh-log)
                        :claude-bd-command (str (.getCanonicalPath (io/file "test/fixtures/fake-bd")) " " bd-log))
          wt (:dir (ca/add-worktree config "bd/p-1"))]
      (.mkdirs (ca/records-dir config))
      (spit (io/file (ca/records-dir config) "s-1.edn")
            (pr-str {:session-id "s-1" :dir wt :branch "bd/p-1" :bead "byj-7" :updated-at 1}))
      (assoc w :config config :origin origin :wt wt :gh-log gh-log :bd-log bd-log))))

(defn- commit! [wt file text]
  (spit (io/file wt file) text)
  (git! wt "add" file)
  (git! wt "-c" "user.name=t" "-c" "user.email=t@example.invalid" "commit" "-qm" (str "add " file)))

(deftest finished-work-is-handed-back-with-what-to-publish
  ;; byj-gly.307: no one is asked; publishing is the caller's, from the hand-off
  (let [{:keys [config origin wt gh-log]} (published-world "handoff")
        on-origin? #(zero? (:exit (git! origin "rev-parse" "--verify" "-q" "refs/heads/bd/p-1")))
        handoff #(ca/handoff config (merge {:session-id "s-1" :title "Fix the parser (byj-7)" :body "It parses."} %))]
    (testing "nothing to publish: no commits past the base"
      (is (re-find #"^Nothing to publish: bd/p-1 has no commits past " (:summary (handoff {})))))
    ;; what a tool leaves untracked is not work left uncommitted
    (.mkdirs (io/file wt ".omc"))
    (spit (io/file wt ".omc" "state.json") "{}")
    (commit! wt "parser.txt" "fixed\n")
    (let [{:keys [summary]} (handoff {})
          body-file (.getCanonicalPath (io/file (ca/records-dir config) "s-1" "pr-body.md"))]
      (testing "what was committed: the commits past the base and the files they change"
        (is (re-find #"^Committed on bd/p-1 \(worktree \S+\), 1 commit past \S+:\n  [0-9a-f]+ add parser\.txt\nChanged:\n  parser\.txt " summary))
        (is (not (str/includes? summary "Not committed")) "an untracked directory is not uncommitted work"))
      (testing "and how to publish it, for the caller: nothing pushed, nothing opened, no one asked"
        (is (str/includes? summary "Not pushed: publishing is not this delegation's"))
        (is (str/includes? summary (str "git -C " wt " push -u origin bd/p-1")))
        (is (str/includes? summary (str "cd " wt " && ")) "gh run where it finds the repository")
        (is (str/includes? summary (str "pr create --head bd/p-1 --base ")))
        (is (str/includes? summary (str "--title 'Fix the parser (byj-7)' --body-file " body-file)))
        (is (= "It parses." (slurp body-file)) "the description in a file")
        (is (re-find #"worktree remove \S+ && git -C \S+ branch -D bd/p-1" summary) "and how to clean up after")
        (is (not (on-origin?)))
        (is (not-any? #(str/includes? % "pr create") (str/split-lines (slurp gh-log))))
        (is (= "handed-off" (:published (ca/read-record config "s-1"))))))
    (testing "uncommitted work in tracked files is named"
      (spit (io/file wt "parser.txt") "changed again\n")
      (is (str/includes? (:summary (handoff {})) "Not committed (tracked files):\n   M parser.txt"))
      (git! wt "checkout" "--" "parser.txt"))
    (testing "the task told the session to publish, and it did not: says so, with the commands; nothing cleaned up"
      (let [{:keys [summary]} (handoff {:publish true :cleanup true})]
        (is (str/includes? summary "The task asked for the work to be pushed and a pull request opened; neither was."))
        (is (str/includes? summary "Not cleaned up: the work is not published yet."))
        (is (.isDirectory (io/file wt)))))
    ;; the session publishes, as its brief tells it to
    (git! wt "push" "-q" "-u" "origin" "bd/p-1")
    (testing "pushed, but no pull request: says how to open it"
      (is (str/includes? (:summary (handoff {:publish true})) "Pushed to origin, but no pull request was found for bd/p-1. Open it:")))
    (fx/sh {:dir wt :out :string} (.getCanonicalPath (io/file "test/fixtures/fake-gh")) gh-log "pr" "create" "--head" "bd/p-1")
    (testing "published by the session: the pull request found; files git does not track keep the worktree"
      (let [r (handoff {:publish true :cleanup true})]
        (is (= "https://example.invalid/pull/1" (:pr-url r)))
        (is (str/includes? (:summary r) "Published by the session: bd/p-1 pushed to origin, pull request https://example.invalid/pull/1."))
        (is (str/includes? (:summary r) "Not cleaned up: the worktree has files git does not track (.omc/state.json)"))
        (is (.isDirectory (io/file wt)))))
    (testing "and with nothing left in it, the worktree and the local branch removed"
      (doseq [f (reverse (file-seq (io/file wt ".omc")))] (.delete f))
      (let [r (handoff {:publish true :cleanup true})]
        (is (str/includes? (:summary r) (str "Cleaned up: removed the worktree " wt " and the local branch bd/p-1")))
        (is (not (.exists (io/file wt))))
        (is (not (zero? (:exit (git! (:project-dir config) "rev-parse" "--verify" "-q" "refs/heads/bd/p-1")))))
        (is (on-origin?) "the branch is still on the remote")
        (is (= ["pr" "https://example.invalid/pull/1" true] ((juxt :published :pr-url :cleaned-up) (ca/read-record config "s-1"))))))
    (testing "never merged"
      (is (not-any? #(str/includes? % "merge") (str/split-lines (slurp gh-log)))))))

(deftest how-it-ended-is-noted-on-its-bead
  (let [{:keys [config project bd-log]} (published-world "note")]
    (testing "a project with no beads is not noted"
      (is (false? (:noted (ca/finish config {:session-id "s-1" :verdict "done" :report "r"})))))
    (.mkdirs (io/file project ".beads"))
    (spit (io/file (ca/records-dir config) "s-1.edn")
          (pr-str (assoc (ca/read-record config "s-1") :pr-url "https://example.invalid/pull/3")))
    (is (true? (:noted (ca/finish config {:session-id "s-1" :verdict "done" :report "the long report"}))))
    (let [[bead text] (str/split (str/trim (slurp bd-log)) #"\t")]
      (is (= "byj-7" bead))
      (is (str/starts-with? text "Delegated work finished on branch bd/p-1"))
      (is (str/ends-with? text "; pull request https://example.invalid/pull/3."))
      (is (not (str/includes? text "the long report")) "facts, not the report"))))

(deftest a-delegated-session-may-not-push-or-open-a-pull-request
  (let [{:keys [config]} (world "settings")
        read #(json/read-str (slurp (ca/session-settings %)))]
    (is (= ca/denied-in-session (get-in (read config) [:permissions :deny])))
    (is (= [{:matcher "Bash" :hooks [{:type "command" :command (.getCanonicalPath (io/file (ca/records-dir config) "publish-guard.sh"))}]}]
           (get-in (read config) [:hooks :PreToolUse]))
        "and the guard that reads the whole line")
    (testing "the project's own settings kept, the rules added to its own"
      (let [m (read (assoc config :claude-settings "{\"permissions\":{\"deny\":[\"Bash(rm:*)\"],\"allow\":[\"Bash(make:*)\"]},\"model\":\"x\"}"))]
        (is (= "x" (:model m)))
        (is (= (cons "Bash(rm:*)" ca/denied-in-session) (get-in m [:permissions :deny])))
        (is (= (cons "Bash(make:*)" @ca/default-allow) (get-in m [:permissions :allow])))))
    (testing "read-only and local commands allowed without asking (byj-gly.292)"
      (let [allow (set (get-in (read config) [:permissions :allow]))]
        (is (every? allow ["Bash(git status:*)" "Bash(bd show:*)" "Bash(git commit:*)"]))
        (is (not-any? #(re-find #"Bash\((find|sed|xargs|env|sh|bash|rm|mv|git (push|checkout|reset|clean|stash|rebase))\b" %) allow)
            "nothing that writes beyond the worktree, runs what follows it, or destroys work")
        (is (not-any? allow ca/denied-in-session) "and nothing it denies")))
    (testing "Claude Code's attribution hidden, unless the settings or the project say otherwise (byj-gly.308)"
      (is (= {:commit "" :pr "" :sessionUrl false} (:attribution (read config))))
      (is (nil? (:attribution (read (assoc config :claude-attribution? true)))))
      (is (= {:commit "x"} (:attribution (read (assoc config :claude-settings "{\"attribution\":{\"commit\":\"x\"}}"))))))
    (testing "a session told to publish may push and open its pull request, never merge, in a file of its own (byj-gly.307)"
      (let [f (ca/session-settings config true)
            m (json/read-str (slurp f))]
        (is (= ca/denied-when-publishing (get-in m [:permissions :deny])))
        (is (every? (set (get-in m [:permissions :allow])) ca/allowed-when-publishing)
            "pushing and opening it allowed outright: auto mode refused them otherwise (byj-gly.308)")
        (is (not-any? (set (get-in (read config) [:permissions :allow])) ca/allowed-when-publishing))
        (is (nil? (get-in m [:hooks :PreToolUse])) "no guard")
        (is (not= f (ca/session-settings config)))
        (is (= ca/denied-in-session (get-in (read config) [:permissions :deny])) "the other's is untouched")))
    (testing "a project adds its own, or drops the shipped ones"
      (is (some #{"Bash(python3 -m unittest:*)"}
                (get-in (read (assoc config :claude-allow ["Bash(python3 -m unittest:*)"])) [:permissions :allow])))
      (is (= ["Bash(make:*)"]
             (get-in (read (assoc config :claude-allow ["Bash(make:*)"] :claude-allow-defaults? false)) [:permissions :allow]))))))

;; --- the agent page, and after a restart (byj-gly.290.5) ---------------------------------

(deftest a-delegation-is-shown-with-its-session-and-found-again-after-a-restart
  (let [{:keys [config]} (world "page")
        rec {:session-id "s-9" :instance-id "claude/before-1" :bead "byj-9" :branch "bd/byj-9"
             :state "needs-person" :round 3 :task "fix the parser" :updated-at 5 :report "long"}]
    (testing "a session a delegation drove carries what the page shows of it"
      (is (= {:live [{:id "s-9" :delegation {:bead "byj-9" :branch "bd/byj-9" :state "needs-person" :round 3}}
                     {:id "other"}]
              :saved [{:id "old"}]}
             (ca/with-delegations {:live [{:id "s-9"} {:id "other"}] :saved [{:id "old"}]} [rec]))))
    (testing "after a restart the instance is new: the session is not YOURS, but says whose work it was"
      (.mkdirs (ca/records-dir config))
      (spit (io/file (ca/records-dir config) "s-9.edn") (pr-str rec))
      (let [h (:claude-home config)
            d (io/file h "projects" (cs/project-key (:project-dir config)))]
        (.mkdirs d)
        (spit (io/file d "s-9.jsonl") (str (json/write-str {:type "ai-title" :aiTitle "parser"}) "\n")))
      (let [line (:text (ca/sessions (assoc config :agent-instance-id "claude/after-2")))]
        (is (str/includes? line "s-9 | saved | delegated earlier for byj-9, needs-person | branch bd/byj-9"))
        (is (not (str/includes? line "YOURS")))))))

(deftest the-guard-refuses-a-push-wherever-it-is-in-the-line
  ;; byj-gly.290.7: the deny rules are prefix rules, so `git -C dir push` and
  ;; the like passed them; the PreToolUse guard reads the whole line
  (let [{:keys [config]} (world "guard")
        _ (ca/session-settings config)
        guard (.getCanonicalPath (io/file (ca/records-dir config) "publish-guard.sh"))
        denied? (fn [payload]
                  (let [r (fx/sh {:in (json/write-str payload) :out :string :err :string} guard)]
                    (is (zero? (:exit r)) (str "the guard runs: " (:err r)))
                    (when-not (str/blank? (:out r))
                      (= "deny" (get-in (json/read-str (:out r)) [:hookSpecificOutput :permissionDecision])))))
        bash (fn [c] {:session_id "x" :tool_name "Bash" :tool_input {:command c}})]
    (doseq [c ["git push" "git -C /x/y push origin b" "cd a && git push" "env A=1 git push -u origin x"
               "sh -c \"git push\"" "/usr/bin/git push" "echo hi\ngit push" "git -c user.name=x push"
               "git --no-pager push" "gh pr create --fill" "gh -R o/r pr merge 3" "gh --repo o/r pr ready 2"]]
      (is (true? (denied? (bash c))) (str "refused: " c)))
    (doseq [c ["git commit -m \"push the fix\"" "git log --grep=push" "git push-docs" "gh pr view 3"
               "git status" "mygit push"]]
      (is (not (denied? (bash c))) (str "let through: " c)))
    (is (true? (denied? (bash "echo git push"))) "a tripwire: a line that only mentions a push is refused too")
    (is (not (denied? {:tool_name "Read" :tool_input {:file_path "git push"}})) "only Bash is read")))

(deftest the-hand-off-stops-the-session-before-its-worktree-goes
  ;; byj-gly.315.20, sim mode B: the caller removed the worktree as the
  ;; hand-off said, and the Claude Code session in it stayed up
  (when (host/tmux?)
    (let [{:keys [config wt]} (published-world "handoff-stop")]
      (spit (io/file wt "x.txt") "x\n")
      (git! wt "add" "x.txt")
      (git! wt "-c" "user.name=t" "-c" "user.email=t@example.invalid" "commit" "-qm" "x")
      (on-own-tmux
       (testing "no session running: the clean-up is the worktree and the branch"
         (is (not (str/includes? (:summary (ca/handoff config {:session-id "s-1"})) "kill-session"))))
       (fx/sh {:out :string :err :string} "tmux" "-L" (nth ah/*tmux* 2) "new-session" "-d" "-s" (host/tmux-name "s-1") "sleep 30")
       (testing "its session running: stopped first, then the worktree"
         (let [summary (:summary (ca/handoff config {:session-id "s-1"}))]
           (is (re-find #"tmux kill-session -t byj-cc-s-1; git -C \S+ worktree remove" summary))
           (is (str/includes? summary "the Claude Code session first, saved and resumable"))))))))

(deftest the-sessions-a-delegation-strands-are-stopped
  ;; byj-gly.318, sim plan2 mode B: the caller removed the worktree with its
  ;; own git, not the hand-off's commands; a cancelled delegation's session
  ;; kept working, unwatched
  (when (host/tmux?)
    (let [{:keys [config project]} (world "stranded")
          wt (doto (io/file project ".brainyard" "worktrees" "bd-x") .mkdirs)
          rec! (fn [sid m] (let [f (io/file (ca/records-dir config) (str sid ".edn"))]
                             (io/make-parents f)
                             (spit f (pr-str (merge {:session-id sid :updated-at 1} m)))))
          start! (fn [sid dir] (fx/sh {:out :string :err :string} "tmux" "-L" (nth ah/*tmux* 2) "new-session" "-d"
                                      "-s" (host/tmux-name sid) "-c" (str dir) "sleep 60"))]
      (on-own-tmux
       (start! "s-gone" wt)
       (start! "s-working" project)
       (start! "s-done" project)
       (start! "s-other" project)
       (rec! "s-gone" {:dir (.getCanonicalPath wt) :state "done" :parent-session "p-1"})
       (rec! "s-working" {:dir project :state "working" :parent-session "p-1"})
       (rec! "s-done" {:dir project :state "done" :parent-session "p-1"})
       (rec! "s-other" {:dir project :state "working" :parent-session "p-2"})
       (testing "its worktree still there: nothing is stranded"
         (is (= [] (ca/stop-stranded! config))))
       (fx/sh {:out :string :err :string} "rm" "-rf" (str wt))
       (testing "a turn's end: the session whose worktree went is stopped, saved"
         (is (= ["s-gone"] (map :session-id (ca/stop-stranded! config))))
         (is (not (contains? (host/running) "s-gone")))
         (is (= "stopped" (:state (ca/read-record config "s-gone"))))
         (is (str/includes? (:stopped-why (ca/read-record config "s-gone")) "was removed")))
       (testing "the delegating session closing: the one it left working is stopped; one done, or another's, is not"
         (is (= ["s-working"] (map :session-id (ca/stop-stranded! config {:closing-session "p-1"}))))
         (is (= #{"s-done" "s-other"} (host/running))))))))

(deftest a-session-starts-on-the-projects-claude-model
  ;; byj-gly.320: unset, a session ran on the account's default (opus) in sim
  ;; mode B, so the two modes compared two models as well as two harnesses
  (let [opts #(:model (#'ca/start-opts %1 "s-1" "." {:model %2}))]
    (is (nil? (opts {} "")) "neither: Claude Code's own default")
    (is (= "sonnet" (opts {:claude-model "sonnet"} "")) "the project's, when the plan names none")
    (is (= "opus" (opts {:claude-model "sonnet"} "opus")) "the plan's, when it names one")))

(deftest what-a-session-leaves-in-its-removed-worktree-is-stopped
  ;; byj-gly.321, sim plan2-claude-sonnet: Claude Code's `node server.js &
  ;; … kill %1` missed, and the server outlived the session and its worktree
  (let [{:keys [config project]} (world "left-in-worktree")
        wt (.getCanonicalPath (doto (io/file project ".brainyard" "worktrees" "bd-y") .mkdirs))
        f (io/file (ca/records-dir config) "s-y.edn")
        _ (io/make-parents f)
        _ (spit f (pr-str {:session-id "s-y" :dir wt :state "done" :updated-at 1}))
        ;; started and not waited for, as Claude Code's `node server.js &` was
        _ (fx/start {:dir wt :out :string :err :string} "sleep" "61")
        pid (fn [] (some #(when (= "sleep" (:command %)) (:pid %)) (ca/processes-in wt)))
        ;; the test's own child lingers as a zombie once killed: not alive
        alive? (fn [p] (let [st (str/trim (str (:out (fx/sh {:out :string :err :string} "ps" "-o" "stat=" "-p" (str p)))))]
                         (and (seq st) (not (str/starts-with? st "Z")))))]
    (try
      (Thread/sleep 300)
      (let [p (pid)]
        (is (some? p) "found by its working directory")
        (testing "its worktree still there: left alone"
          (is (= [] (ca/stop-stranded! config)))
          (is (alive? p)))
        (fx/sh {:out :string :err :string} "rm" "-rf" wt)
        (testing "its worktree removed: stopped, and said"
          (let [[e & more] (ca/stop-stranded! config)]
            (is (nil? more))
            (is (= "s-y" (:session-id e)))
            (is (= [p] (map :pid (:processes e))))
            (is (str/includes? (:why e) "removed worktree")))
          (Thread/sleep 300)
          (is (not (alive? p)))
          (is (:swept (ca/read-record config "s-y"))))
        (testing "swept once"
          (is (= [] (ca/stop-stranded! config)))))
      (finally
        (some->> (pid) (fx/sh {:out :string :err :string} "kill"))))))

