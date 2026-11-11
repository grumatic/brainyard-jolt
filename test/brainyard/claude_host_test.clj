(ns brainyard.claude-host-test
  "Running Claude Code sessions from the agent page (byj-gly.287.3): what Enter
  on a row plans, and the tmux sessions it starts — on a tmux server of the
  test's own, with a stand-in for Claude Code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent-host :as ah]
            [brainyard.claude-host :as host]
            [brainyard.claude-sessions :as cs]
            [brainyard.effect :as fx]
            [brainyard.json :as json]))

(def ^:private opts
  {:tmux? true :running #{"live-here"} :project-dir "/p" :claude "claude" :new-id (constantly "new-1")})

(deftest enter-on-a-row-plans-start-and-attach
  (testing "new: started with the id it is given, then attached"
    (is (= {:id "new-1"
            :start ["tmux" "new-session" "-d" "-s" "byj-cc-new-1" "-c" "/p" "claude" "--session-id" "new-1"]
            :attach ["tmux" "attach-session" "-t" "byj-cc-new-1"]}
           (host/plan {:state :claude-new} opts))))
  (testing "saved: resumed in a tmux session of its own, then attached"
    (is (= ["claude" "--resume" "old-2"] (subvec (:start (host/plan {:state :claude-saved :session-id "old-2"} opts)) 7))))
  (testing "live and running here: attached, nothing started"
    (is (= {:id "live-here" :start nil :attach ["tmux" "attach-session" "-t" "byj-cc-live-here"]}
           (host/plan {:state :claude-live :session-id "live-here"} opts))))
  (testing "live in another terminal: refused, with why"
    (is (str/includes? (:refuse (host/plan {:state :claude-live :session-id "elsewhere"} opts)) "another terminal")))
  (testing "no tmux: Claude Code itself gets the terminal"
    (is (= {:id "old-2" :attach ["claude" "--resume" "old-2"] :foreground? true}
           (host/plan {:state :claude-saved :session-id "old-2"} (assoc opts :tmux? false)))))
  (testing "a command with its own arguments"
    (is (= ["npx" "claude" "--resume" "x"] (:attach (host/plan {:state :claude-saved :session-id "x"}
                                                               (assoc opts :tmux? false :claude "npx claude"))))))
  (testing "nested in tmux, the inner session's status line says how to detach it: the outer prefix twice (byj-on2)"
    (let [p (host/plan {:state :claude-live :session-id "live-here"} (assoc opts :nested-prefix "C-a"))]
      (is (= ["tmux" "set-option" "-t" "byj-cc-live-here" "status-right-length" "90" ";"
              "set-option" "-t" "byj-cc-live-here" "status-right" "detach: C-a C-a d  (nested — C-a d detaches your outer tmux)"]
             (:hint p)))
      (is (nil? (:hint (host/plan {:state :claude-live :session-id "live-here"} opts))) "not nested: nothing to say"))))

(deftest a-session-running-here-is-listed-before-claude-code-records-it
  (is (= {:live [{:id "a" :title "x" :status "busy"} {:id "n" :title nil :status "running"} {:id "s" :title "old" :status "running"}]
          :saved [{:id "t" :title "other"}]}
         (host/with-running {:live [{:id "a" :title "x" :status "busy"}]
                             :saved [{:id "s" :title "old"} {:id "t" :title "other"}]}
                            #{"a" "n" "s"}))
      "a resumed one leaves the saved, a new one appears, one already live is not doubled"))

(deftest a-session-runs-in-tmux-and-is-left-running
  (when (host/tmux?)
    (let [label (str "byj-test-" (random-uuid))
          dir (.getCanonicalPath (doto (io/file "target/claude-host-test") .mkdirs))
          fake (doto (io/file dir "fake-claude") (spit "#!/bin/sh\nsleep 30\n") (.setExecutable true))
          handed (atom nil)]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (let [p (host/plan {:state :claude-new} {:tmux? true :running (host/running) :project-dir dir
                                                   :claude (.getPath fake) :new-id (constantly "abc-123")})
                said (host/run-plan! p (fn [argv env] (reset! handed [argv env])))]
            (is (nil? said))
            (testing "its tmux session runs, named for its id, and is listed"
              (is (= #{"abc-123"} (host/running))))
            (testing "the terminal is handed to the attach, outside any tmux it was run in"
              (is (= ["tmux" "-L" label "attach-session" "-t" "byj-cc-abc-123"] (first @handed)))
              (is (not (contains? (second @handed) "TMUX"))))
            (testing "nested, the hint is put on the session's own status line before the attach"
              (host/run-plan! (host/plan {:state :claude-live :session-id "abc-123"}
                                         {:tmux? true :running (host/running) :project-dir dir :nested-prefix "C-b"})
                              (fn [_ _] nil))
              (let [{:keys [out]} (fx/sh {:out :string :err :string} "tmux" "-L" label "show-option" "-t" "byj-cc-abc-123" "-v" "status-right")]
                (is (= (host/detach-hint "C-b") (str/trim out)))))
            (testing "running again, Enter attaches without starting another"
              (is (nil? (:start (host/plan {:state :claude-live :session-id "abc-123"}
                                           {:tmux? true :running (host/running) :project-dir dir}))))))
          (finally
            (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

;; --- driving a session (agent$claude, byj-gly.290.2, RFC-039) ---------------------------

(deftest a-session-is-started-with-its-brief-last-and-the-parents-variables-unset
  (let [argv (host/start-argv {:id "s-1" :dir "/w" :claude "npx claude" :model "haiku"
                               :permission-mode "acceptEdits" :settings "/s.json" :args ["--effort" "low"]
                               :prompt "line one\nline two"}
                              ["CLAUDECODE" "CLAUDE_CODE_SESSION_ID"])]
    (is (= ["tmux" "new-session" "-d" "-s" "byj-cc-s-1" "-x" "200" "-y" "50" "-c" "/w"
            "env" "-u" "CLAUDECODE" "-u" "CLAUDE_CODE_SESSION_ID"
            ;; a pane takes the tmux server's environment: told not to prompt
            "GCM_INTERACTIVE=never" "GIT_SSH_COMMAND=ssh -o BatchMode=yes" "GIT_TERMINAL_PROMPT=0"
            "SSH_ASKPASS_REQUIRE=never"
            "npx" "claude" "--session-id" "s-1" "--model" "haiku" "--permission-mode" "acceptEdits"
            "--settings" "/s.json" "--effort" "low" "--" "line one\nline two"]
           argv)
        "the brief whole, as the last argument, past `--` so no option takes it")
    (is (= ["claude" "--resume" "s-1"] (subvec (host/start-argv {:id "s-1" :dir "/w" :resume? true} []) 16))
        "resumed, no brief")))

(deftest the-trust-dialog-is-read-from-the-screen
  (is (host/trust-dialog? " Accessing workspace:\n ❯ No, exit\n   Yes, I trust this folder\n"))
  (is (not (host/cursor-on-yes? " ❯ No, exit\n   Yes, I trust this folder")) "it opens on No")
  (is (host/cursor-on-yes? "   No, exit\n ❯ Yes, I trust this folder"))
  (is (host/cursor-on-yes? "   1. No, exit\n ❯ 2. Yes, I trust this folder") "numbered or not")
  (is (not (host/trust-dialog? "❯ Try \"create a util\""))))

(deftest a-turn-is-settled-only-when-idle-and-the-transcript-has-the-reply
  (is (host/settled? {:status :idle :ended? true}))
  (is (not (host/settled? {:status :idle :ended? false})) "idle before the transcript holds the reply")
  (is (not (host/settled? {:status :busy :ended? true})) "the reply to the turn before"))

;; byj-gly.290.10: Claude Code continued a session under a new id in the
;; running process — `continued-in` in the old transcript, the rest in the new
;; one — and agent$claude, reading the old, waited for a reply never written there
(deftest a-session-continued-under-a-new-id-is-read-where-it-went-on
  (let [base (doto (io/file "target/claude-host-test" (str "continued-" (System/nanoTime))) .mkdirs)
        home (.getCanonicalPath (doto (io/file base "home") .mkdirs))
        dir (.getCanonicalPath (doto (io/file base "project") .mkdirs))
        pdir (doto (io/file home "projects" (cs/project-key dir)) .mkdirs)
        line #(str (json/write-str %) "\n")
        write! (fn [id & ms] (spit (io/file pdir (str id ".jsonl")) (apply str (map line ms)) :append true))
        reply (fn [stop text tokens] {:type "assistant" :message {:id (str "m-" text) :model "claude-haiku-4-5" :stop_reason stop
                                                                    :content [{:type "text" :text text}]
                                                                    :usage {:input_tokens tokens :output_tokens 1}}})]
    (.mkdirs (io/file home "sessions"))
    ;; the first process's record, which stops changing once the session goes
    ;; on elsewhere: live, it still said idle eleven days on
    (spit (io/file home "sessions" "4242.json") (json/write-str {:pid 4242 :sessionId "old-1" :cwd dir :status "idle"}))
    (write! "old-1" {:type "user" :message {:content "first task"}} (reply "end_turn" "first done" 100))
    (testing "before: its own transcript"
      (let [o (host/observe "old-1" {:home home :dir dir})]
        (is (= ["first task" "first done" true nil] ((juxt :prompt :answer :ended? :transcript-id) o)))))
    (write! "old-1" {:type "continued-in" :sessionId "old-1" :continuedInSessionId "new-2"})
    (testing "continued, with nothing written under the new id yet: no turn to call settled"
      (let [o (host/observe "old-1" {:home home :dir dir})]
        (is (= "new-2" (:transcript-id o)))
        (is (not (host/settled? o)) "not the old file's finished turn")))
    ;; the process that runs on, a claude bg-spare, with a record of its own
    (spit (io/file home "sessions" "5151.json") (json/write-str {:pid 5151 :sessionId "new-2" :cwd dir :status "busy"}))
    (write! "new-2" {:type "user" :message {:content "the task typed next"}} (reply "tool_use" "working" 50))
    (testing "the turn typed next is read from the new transcript, its status from the new record"
      (let [o (host/observe "old-1" {:home home :dir dir})]
        (is (= ["the task typed next" false :busy] ((juxt :prompt :ended? :status) o)))
        (is (= "busy" (:status (host/record "old-1" home))) "what type-line and agent$claude read too")
        (is (not (host/settled? o)))))
    (spit (io/file home "sessions" "5151.json") (json/write-str {:pid 5151 :sessionId "new-2" :cwd dir :status "idle"}))
    (write! "new-2" (reply "end_turn" "next done" 30))
    (testing "and it settles there, its usage the whole chain's"
      (let [o (host/observe "old-1" {:home home :dir dir})]
        (is (host/settled? o))
        (is (= "next done" (:answer o)))
        (is (= 180 (get-in o [:usage "claude-haiku-4-5" :fresh])) "100 + 50 + 30, so a cost measured across the switch holds")))))

(defn- run [task] (let [r (fx/run!! task 15000)] (or (:ok r) r)))

(deftest a-session-is-briefed-guided-and-watched-to-the-end-of-each-turn
  ;; a stand-in for Claude Code (test/fixtures/fake-claude) on a tmux server of
  ;; the test's own: the trust dialog, a turn that idles before its reply is
  ;; written, a permission dialog, and leaving
  (when (host/tmux?)
    (let [label (str "byj-test-" (random-uuid))
          base (doto (io/file "target/claude-host-test" label) .mkdirs)
          home (.getCanonicalPath (doto (io/file base "home") .mkdirs))
          dir (.getCanonicalPath (doto (io/file base "project") .mkdirs))
          fake (.getCanonicalPath (io/file "test/fixtures/fake-claude"))
          id "drive-1"
          opts {:home home :dir dir}
          settled #(run (host/await-settled id (assoc opts :poll-ms 50 :settle-ms 300)))]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (is (= {:id id} (host/start! {:id id :dir dir :claude (str fake " --fake-home " home " --fake-trust")
                                        :model "haiku" :permission-mode "acceptEdits" :prompt "first brief"})))
          (testing "past the trust dialog, which opened on No, by Down until Yes and only then Enter"
            (is (= {:id id :trust-dialog? true} (run (host/ready id {:home home :timeout-ms 10000})))))
          (testing "the brief's turn, settled only once the reply is written — after the record said idle"
            (is (= [:done "first brief" "echo: first brief"] ((juxt :settled :prompt :answer) (settled)))))
          (testing "a second start of the same session is refused"
            (is (:error (host/start! {:id id :dir dir :claude fake}))))
          (testing "a line typed, a dash first; another refused while that turn runs"
            (is (:sent (run (host/type-line id "-x then the guidance" opts))))
            (is (re-find #"busy|waiting" (:error (run (host/type-line id "too soon" opts)))))
            (is (= ["-x then the guidance" "echo: -x then the guidance"] ((juxt :prompt :answer) (settled)))))
          (testing "more than one line is refused: a paste is data to the model"
            (is (:error (run (host/type-line id "one\ntwo" opts)))))
          (testing "a permission dialog stops the wait at once, with the screen; No ends the turn"
            (is (:sent (run (host/type-line id "please ASK first" opts))))
            (let [o (settled)]
              (is (= :waiting (:settled o)))
              (is (str/includes? (:screen o) "Do you want to proceed?")))
            (is (= {:chosen 3} (run (host/choose id 3 opts))))
            (is (= [:done "please ASK first" nil] ((juxt :settled :prompt :answer) (settled)))))
          (testing "leaving: the session is gone"
            (is (= {:sent true :enters 1 :gone? true} (run (host/type-line id "/exit" opts))))
            (is (= :gone (:settled (settled)))))
          (finally
            (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest a-turn-too-short-to-see-busy-has-still-started
  ;; byj-gly.419: a turn whose record says busy for less than type-line's poll
  ;; read as never started — on six loaded shards, the stand-in's 0.3 s busy
  ;; was missed three times and say answered "did not start a turn after 3
  ;; Enters" for a line the session had taken. The transcript says it did.
  (when (host/tmux?)
    (let [label (str "byj-test-" (random-uuid))
          base (doto (io/file "target/claude-host-test" label) .mkdirs)
          home (.getCanonicalPath (doto (io/file base "home") .mkdirs))
          dir (.getCanonicalPath (doto (io/file base "project") .mkdirs))
          fake (.getCanonicalPath (io/file "test/fixtures/fake-claude"))
          id "short-1"
          ;; a stand-in never busy waits out the whole busy window on every
          ;; line: 300 ms of it, not a real session's 2 s (byj-gly.442)
          opts {:home home :dir dir :busy-ms 300}
          settled #(run (host/await-settled id (assoc opts :poll-ms 50 :settle-ms 300)))
          prompts #(vec (for [l (str/split-lines (slurp (host/transcript id dir home)))
                              :let [m (json/read-str l)]
                              :when (and (= "user" (:type m)) (string? (get-in m [:message :content])))]
                          (get-in m [:message :content])))]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (host/start! {:id id :dir dir :claude (str fake " --fake-home " home " --fake-busy 0") :prompt "brief"})
          (run (host/ready id {:home home :timeout-ms 10000}))
          (settled)
          (testing "a line whose turn no poll saw busy is sent, on the first Enter"
            (is (= {:sent true :enters 1} (run (host/type-line id "a short one" opts))))
            (is (= [:done "a short one"] ((juxt :settled :prompt) (settled)))))
          (testing "and taken once: no Enter after it reached the session"
            (is (= ["brief" "a short one"] (prompts))))
          (testing "a repeated line is told apart from the last by the transcript growing"
            (is (= {:sent true :enters 1} (run (host/type-line id "a short one" opts))))
            (settled)
            (is (= ["brief" "a short one" "a short one"] (prompts))))
          (finally
            (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest the-mode-asked-for-is-the-mode-it-gets-or-the-nearest
  ;; byj-gly.290.9: with haiku, --permission-mode auto started Claude Code in
  ;; manual mode, which asks before every edit; shift+tab cycles manual →
  ;; accept edits → plan → manual (measured, 2.1.292)
  (is (= "auto" (host/mode-of "  ⏵⏵ auto mode on (shift+tab to cycle)")))
  (is (= "acceptEdits" (host/mode-of "  ⏵⏵ accept edits on (shift+tab to cycle) · ← for agents")))
  (is (= "manual" (host/mode-of "  ⏸ manual mode on · ← for agents")))
  (is (nil? (host/mode-of " Do you want to proceed?")) "a dialog over the footer")
  (let [cycle ["manual" "acceptEdits" "plan"]
        at (atom 0)
        presses (atom 0)
        footer {"manual" "⏸ manual mode on" "acceptEdits" "⏵⏵ accept edits on" "plan" "⏸ plan mode on" "auto" "⏵⏵ auto mode on"}
        drive (fn [start want]
                (reset! at (.indexOf cycle start)) (reset! presses 0)
                (with-redefs [host/pane (fn [_] (footer (nth cycle @at)))
                              host/keys! (fn [_ k] (when (= "BTab" k) (swap! presses inc) (swap! at #(mod (inc %) 3))))]
                  (:ok (fx/run!! (host/ensure-mode "s" want "acceptEdits") 10000))))]
    (is (= {:mode "acceptEdits" :wanted "auto"} (drive "manual" "auto")) "auto not offered: accept edits, not manual")
    (is (= 1 @presses))
    (is (= {:mode "acceptEdits"} (drive "acceptEdits" "acceptEdits")) "what was asked for, no key pressed")
    (is (zero? @presses))
    (is (= {:mode "manual" :wanted "plan"} (drive "manual" "plan")) "only auto is moved off")
    (with-redefs [host/pane (fn [_] "⏵⏵ auto mode on")]
      (is (= {:mode "auto"} (:ok (fx/run!! (host/ensure-mode "s" "auto" "acceptEdits") 10000)))))))

(deftest every-tmux-command-reaches-one-server
  ;; byj-gly.306: inside a tmux that is not the default, the start went to the
  ;; server TMUX names and the attach, which drops TMUX, to the default one —
  ;; so every tmux command of this layer runs in attach-env
  (let [calls (atom [])
        marker {"MARK" "attach-env"}]
    ;; the environment is agent-host's, which claude-host's tmux commands share (RFC-049)
    (with-redefs [ah/attach-env (constantly marker)
                  fx/sh (fn [opts & argv] (swap! calls conj [(:env opts) (vec argv)]) {:exit 0 :out "" :err ""})]
      (let [p (host/plan {:state :claude-new} {:tmux? true :running #{} :project-dir "/p" :claude "claude"
                                               :new-id (constantly "n-1")})
            handed (atom nil)]
        (host/run-plan! p (fn [argv env] (reset! handed env)))
        (host/running)
        (host/start! {:id "n-2" :dir "/p"})
        (host/pane "n-2")
        (is (= marker @handed) "the attach")
        (is (seq @calls))
        (is (every? #(= marker (first %)) @calls)
            (str "the start, the listing, a start! and a pane read: " (pr-str (map second (remove #(= marker (first %)) @calls)))))))))

(deftest only-this-projects-running-sessions-are-listed
  ;; byj-gly.310: every byj-cc-* tmux session was listed on every project's
  ;; page; a session is this project's by the directory tmux started it in
  (when (host/tmux?)
    (let [label (str "byj-test-" (random-uuid))
          base (.getCanonicalPath (doto (io/file "target/claude-host-test/projects") .mkdirs))
          mine (.getCanonicalPath (doto (io/file base "mine") .mkdirs))
          wt (.getCanonicalPath (doto (io/file mine ".brainyard/worktrees/bd-x") .mkdirs))
          other (.getCanonicalPath (doto (io/file base "mine-too-not") .mkdirs))]
      (binding [ah/*tmux* ["tmux" "-L" label]]
        (try
          (doseq [[id dir] [["in-project" mine] ["in-worktree" wt] ["elsewhere" other]]]
            (fx/sh {:out :string :err :string} "tmux" "-L" label "new-session" "-d" "-s" (host/tmux-name id) "-c" dir "sleep 30"))
          (testing "the project's own and its worktrees', not another directory's — not even one whose name starts the same"
            (is (= #{"in-project" "in-worktree"} (host/running-in mine))))
          (testing "and only those are added to its list"
            (is (= ["in-project" "in-worktree"]
                   (map :id (:live (host/with-running {:live [] :saved []} (host/running-in mine)))))))
          (is (= #{"in-project" "in-worktree" "elsewhere"} (host/running)) "running still knows them all")
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" label "kill-server")))))))

(deftest a-tmux-that-takes-no-prefix-is-no-outer-prefix
  ;; a machine's own session takes none, so what is typed on
  ;; its page reaches the program, and byj says no nested hint there
  (is (= "C-b" (ah/taking-prefix "C-b\n")))
  (is (= "C-a" (ah/taking-prefix "C-a")))
  (is (nil? (ah/taking-prefix "None\n")))
  (is (nil? (ah/taking-prefix "")))
  (let [label (str "byj-prefix-" (random-uuid))
        tmux (fn [& args] (apply fx/sh {:out :string :err :string} "tmux" "-L" label args))]
    (try
      (tmux "new-session" "-d" "-s" "machine" "sh")
      (tmux "new-session" "-d" "-s" "inner" "sh")
      (tmux "set-option" "-t" "machine" "prefix" "None")
      (is (nil? (ah/taking-prefix (:out (tmux "display-message" "-p" "-t" "machine" "#{prefix}"))))
          "the machine's own session takes none")
      (is (= "C-b" (ah/taking-prefix (:out (tmux "display-message" "-p" "-t" "inner" "#{prefix}"))))
          "a session byj starts beside it keeps the server's")
      (finally (tmux "kill-server")))))
