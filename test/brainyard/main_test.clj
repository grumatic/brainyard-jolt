(ns brainyard.main-test
  "The CLI's own wiring — the decisions `-main` makes before a turn exists.

  Mostly this is where the run's session id is settled, because everything
  downstream is named after it: the event sinks, the agent's spill directory
  and the session snapshot all have to agree on which run this is."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.config :as config]
            [brainyard.main :as main]
            [brainyard.changes :as changes]
            [brainyard.effect]
            [brainyard.agent-reach :as agent-reach]
            [brainyard.persist :as persist]
            [brainyard.tui :as tui]
            [brainyard.rail-state :as rail-state]
            [brainyard.eventlog :as eventlog]
            [brainyard.json :as json]
            [jolt.process :as p]))

;; --- the run's session id (byj-gly.76) ----------------------------------------------

(deftest completions-name-what-exists
  (testing "the curated models are offered first, then each provider's default, once each"
    (let [{:keys [items from]} (main/model-completions "/model anth")]
      (is (= ["anthropic/claude-opus-5-5" "Anthropic Claude Opus 5.5 (flagship)"] (first items)))
      (is (= 1 (count (filter #(= "anthropic/claude-sonnet-5" (first %)) items)))
          "the default is also curated, and listed once")
      (is (= 7 from) "the replacement starts after the command"))
    (is (= [["mock/mock" "mock"]] (:items (main/model-completions "/model mock")))
        "a provider with nothing curated still offers its default")
    (is (nil? (main/model-completions "/model zzz")))
    (is (nil? (main/model-completions "/model anthropic/claude-sonnet-5"))
        "a label typed in full is offered nothing, so Enter sends it")
    (is (nil? (main/model-completions "not a command"))))

  (testing "an @token names a file, and the walk skips what a menu should not show"
    (let [dir (str "target/main-test/paths-" (System/currentTimeMillis))]
      (.mkdirs (java.io.File. (str dir "/src")))
      (.mkdirs (java.io.File. (str dir "/target")))
      (.mkdirs (java.io.File. (str dir "/.git")))
      (spit (str dir "/src/one.clj") "")
      (spit (str dir "/src/two.clj") "")
      (spit (str dir "/target/built.clj") "")
      (spit (str dir "/.git/config") "")
      (let [{:keys [items from]} (main/path-completions dir "read @src/")]
        (is (= [["@src/one.clj" ""] ["@src/two.clj" ""]] items))
        (is (= 5 from)))
      (is (nil? (main/path-completions dir "read @target/")) "build output is not a file you name")
      (is (nil? (main/path-completions dir "read @.git/")) "nor is a hidden directory")
      (is (nil? (main/path-completions dir "nothing to complete")))
      (testing "@here, what the person is looking at in their editor, first (byj-gly.453)"
        (is (= ["@here" "what you are looking at in emacs"] (first (:items (main/path-completions dir "explain @he")))))
        (is (= "@here" (ffirst (:items (main/path-completions dir "explain @")))) "before the files")
        (is (not-any? #(= "@here" (first %)) (:items (main/path-completions dir "read @src/"))))))))

(deftest a-run-always-has-a-session-id-before-anything-captures-it
  ;; The defect: the id was generated inside agent/ask, which runs long after
  ;; -main has built its :on-event sinks from this config. The prediction log
  ;; returned nil without an id and was silently a no-op for every run that did
  ;; not hand-pin one. That log is gone, but the id still has to be here: every
  ;; record carries :session, and a dataset groups by it, so a run without one
  ;; writes records no session can be told apart from another's.
  (testing "an ask gets one, and it is the one the sink stamps records with"
    (let [c (main/run-config {} {:project-dir "/tmp/x"} :ask)]
      (is (some? (:session-id c)))
      (is (= (str (:session-id c))
             (:session (eventlog/record {:session (:session-id c)} :predictor/call {})))
          "the sink built from this config stamps what it writes")))

  (testing "two runs of the same project do not share one"
    (is (not= (:session-id (main/run-config {} {} :ask))
              (:session-id (main/run-config {} {} :ask)))))

  (testing "a pinned id is still honoured"
    (is (= "pinned" (:session-id (main/run-config {} {:session-id "pinned"} :ask)))))

  (testing "a session does too, and it is not marked as resuming anything"
    (let [c (main/run-config {} {} :run)]
      (is (some? (:session-id c)))
      (is (nil? (:resume c))))))

(deftest resuming-settles-on-the-resumed-id-not-a-fresh-one
  ;; The second half of the same defect: resumed-base used to assoc the id in
  ;; AFTER the sinks had closed over the old config, so a resumed run wrote its
  ;; records to whichever directory the sink had captured first.
  (testing "--resume names the session, and says so"
    (let [c (main/run-config {:resume "abc-123"} {:session-id "fresh"} :run)]
      (is (= "abc-123" (:session-id c)))
      (is (= "abc-123" (:resume c)))))

  (testing "a pinned id does not outrank the session actually being resumed"
    (is (= "abc-123" (:session-id (main/run-config {:resume "abc-123"}
                                                   {:session-id "pinned"} :run)))))

  (testing "--resume is a session flag: an ask ignores it rather than adopting its id"
    (let [c (main/run-config {:resume "abc-123"} {} :ask)]
      (is (not= "abc-123" (:session-id c)))
      (is (nil? (:resume c))))))

(deftest resume-latest-takes-the-newest-saved-session
  (let [dir (io/file "target/main-test-resume")
        _ (do (when (.isDirectory dir) (run! io/delete-file (reverse (file-seq dir))))
              (.mkdirs dir))
        config {:project-dir (.getPath dir)}
        ;; a real snapshot, so latest-id ranks it rather than skipping it as
        ;; unreadable — it sorts on the snapshot's :updated-at, not file mtime
        write-session (fn [id updated-at]
                        (persist/save! config id {:turn-count 1 :question "q"
                                                  :previous-turns [] :usage {}})
                        (let [f (persist/session-file config id)]
                          (spit f (pr-str (assoc (persist/read-session config id)
                                                 :updated-at updated-at)))
                          f))]
    (testing "with nothing saved it starts fresh rather than failing"
      (let [c (main/run-config {:resume-latest true} config :run)]
        (is (some? (:session-id c)))
        (is (nil? (:resume c)) "nothing was resumed")))

    (testing "with sessions saved it continues the most recently updated"
      (write-session "older" 1000)
      (let [f (write-session "newer" 2000)
            c (main/run-config {:resume-latest true} config :run)]
        (is (.isFile f))
        (is (= "newer" (:session-id c)))
        (is (= "newer" (:resume c)))))))

(deftest an-inline-resume-prints-what-was-said-on-a-terminal-only
  ;; byj-gly.135: the inline front end showed a resumed session's one-line
  ;; notice and nothing of the conversation it resumed
  (let [transcript [[:you "what is here?"]
                    [:tool {:tool-name "list-dir" :tool-args {:path "."} :tool-result "{:entries [\"notes.txt\"]}"}]
                    [:answer "one file"]]
        printed (atom [])
        passed (atom [])
        bus (fn [e d] (swap! passed conj e))]
    (testing "on a terminal, the history is printed as /capture writes it"
      ((main/replaying-resume bus true #(swap! printed conj %)) :session/resumed {:transcript transcript})
      (is (= ["› what is here?\n⏺ list-dir {:path \".\"} → {:entries [\"notes.txt\"]}\none file"] @printed)))
    (testing "and every event still reaches the bus"
      (is (= [:session/resumed] @passed)))
    (testing "into a pipe it is not printed — the old turns are not the output"
      (reset! printed [])
      ((main/replaying-resume bus false #(swap! printed conj %)) :session/resumed {:transcript transcript})
      (is (empty? @printed)))
    (testing "nothing else prints anything"
      ((main/replaying-resume bus true #(swap! printed conj %)) :session.turn/start {:turn 1})
      (is (empty? @printed)))))

(deftest the-event-log-is-written-whoever-is-watching
  ;; byj-gly.141: the full-screen session replaced the bus with the renderer's
  ;; handler, and wrote no event log at all. The bus now takes its viewer as an
  ;; argument, and the sinks stand behind whichever one it is.
  (let [dir (.getPath (doto (io/file "target/main-test" (str "bus-" (System/currentTimeMillis))) .mkdirs))
        config {:project-dir dir :working-dir dir :session-id "s1"}
        seen (atom [])
        bus (main/event-bus config (fn [e _] (swap! seen conj e)))]
    (bus :session.turn/start {:turn 1 :question "hi"})
    (testing "the viewer sees the event"
      (is (= [:session.turn/start] @seen)))
    (testing "and the event log has it, as it would under the tracer"
      (let [f (eventlog/log-file config)]
        (is (.isFile f))
        (is (clojure.string/includes? (slurp f) ":session.turn/start"))))
    (testing "a bus with no viewer still writes the log"
      ((main/event-bus config nil) :session.turn/end {:terminated-by :answer})
      (is (clojure.string/includes? (slurp (eventlog/log-file config)) ":session.turn/end")))))

(deftest the-run-says-how-to-resume-what-it-saved
  ;; brainyard's format-resume-hint (byj-gly.252)
  (let [turn (fn [q] {:question q})]
    (testing "one session saved: its first question and the command"
      (is (= "Session saved.\n  count the lines  byj run --resume 179-ab"
             (main/resume-hint [{:session-id "179-ab" :persisted? true :turn-count 2
                                 :previous-turns [(turn "count the lines") (turn "again")]}]))))
    (testing "several: each, the commands lined up, a long label cut"
      (let [[head & lines] (clojure.string/split-lines
                           (main/resume-hint [{:session-id "a" :persisted? true :turn-count 1 :previous-turns [(turn "one")]}
                                              {:session-id "b" :persisted? true :turn-count 1
                                               :previous-turns [(turn "a first question that goes on well past forty characters")]}]))
            [rows [all]] (split-with #(clojure.string/includes? % "--resume ") lines)]
        (is (= "2 sessions saved." head))
        (is (clojure.string/starts-with? (first rows) "  one "))
        (is (clojure.string/includes? (second rows) "well past…"))
        (is (= 1 (count (distinct (map #(clojure.string/index-of % "byj run --resume") rows)))) "one column")
        (is (= ["a" "b"] (map #(last (clojure.string/split % #" ")) rows)))
        (is (= "  all of them, as they were: byj run --resume-run" all)
            "and the set, which comes back together (byj-gly.178)")))
    (testing "nothing is said for a session with no turn, one not written to disk, or none at all"
      (is (nil? (main/resume-hint [{:session-id "c" :persisted? true :turn-count 0}])))
      (is (nil? (main/resume-hint [{:session-id "d" :persisted? false :turn-count 3}])))
      (is (nil? (main/resume-hint [nil]))))))

(deftest a-run-asks-the-configured-model-not-whichever-key-is-set
  (testing "nothing said: claude-code's sonnet, whatever API keys the environment holds (byj-gly.366)"
    (is (= [:claude-code "sonnet"] (main/run-lm-choice nil nil {:default-lm (:default-lm config/defaults)}))
        "the key's own default")
    (is (= [:claude-code "sonnet"] (main/run-lm-choice nil nil {}))
        "an unset key is the fallback, not the first provider with a key")
    (is (= [:claude-code "haiku"] (main/run-lm-choice nil nil {:default-lm "claude-code/haiku"})) "haiku when a config says so"))
  (testing ":default-lm names one, and -m replaces its model"
    (is (= [:anthropic "claude-sonnet-5"] (main/run-lm-choice nil nil {:default-lm "anthropic/claude-sonnet-5"})))
    (is (= [:claude-code "sonnet"] (main/run-lm-choice nil "sonnet" {:default-lm "claude-code/haiku"}))))
  (testing "-p wins, with -m or that provider's own default"
    (is (= [:openai nil] (main/run-lm-choice :openai nil {:default-lm "claude-code/haiku"})))
    (is (= [:openai "gpt-5"] (main/run-lm-choice :openai "gpt-5" {:default-lm "claude-code/haiku"}))))
  (testing "a label that is not provider/model is reported and the fallback used"
    (is (= [:claude-code "sonnet"] (main/run-lm-choice nil nil {:default-lm "bogus"})))))

;; --- a headless session ends with its starter (byj-1wv.42) ---------------------------

(deftest a-served-session-closes-when-its-starter-is-gone
  (testing "the watch asks while the starter lives and calls on-gone once it is gone"
    (let [alive (atom true)
          asked (atom [])
          gone (promise)
          stop (main/watch-parent "4242" #(deliver gone :gone)
                                  :ms 10
                                  :alive? (fn [pid] (swap! asked conj pid) @alive))]
      (try
        (Thread/sleep 60)
        (is (not (realized? gone)) "a live starter leaves the session alone")
        (is (seq @asked))
        (is (every? #{"4242"} @asked) "it asks after the pid it was given")
        (reset! alive false)
        (is (= :gone (deref gone 2000 :timeout)))
        (let [n (count @asked)]
          (Thread/sleep 60)
          (is (= n (count @asked)) "the watch stops once it has fired"))
        (finally (stop)))))
  (testing "an on-gone that throws still stops the watch"
    (let [calls (atom 0)
          stop (main/watch-parent "4242" #(do (swap! calls inc) (throw (ex-info "broken pipe" {})))
                                  :ms 10 :alive? (constantly false))]
      (try (Thread/sleep 80)
           (is (= 1 @calls))
           (finally (stop)))))
  (testing "no pid, or not a pid, starts no watch"
    (is (nil? (main/watch-parent nil #(throw (ex-info "fired" {})))))
    (is (nil? (main/watch-parent "" #(throw (ex-info "fired" {})))))
    (is (nil? (main/watch-parent "abc" #(throw (ex-info "fired" {})))))
    (is (nil? (main/watch-parent "0" #(throw (ex-info "fired" {}))))))
  (testing "a real pid that has ended reads as gone"
    (let [gone (promise)
          stop (main/watch-parent "999999" #(deliver gone :gone) :ms 10)]
      (try (is (= :gone (deref gone 5000 :timeout)))
           (finally (some-> stop (apply [])))))))

(deftest a-closed-pipe-is-told-from-any-other-failure
  ;; byj-gly.368: `byj … | head` printed jolt's unhandled IOException
  (let [epipe (java.io.IOException. "display-string: failed on #<binary output port stdout>: broken pipe")]
    (is (main/broken-pipe? epipe))
    (is (main/broken-pipe? (ex-info "while printing the report" {} epipe)) "through its cause")
    (is (not (main/broken-pipe? (java.io.IOException. "No such file or directory"))))
    (is (not (main/broken-pipe? (ex-info "broken pipe, said a message" {}))) "only an IOException says so")))

(deftest byjs-agents-are-told-apart-by-what-they-reach
  ;; as a headless session started as each would run them (byj-1wv.47)
  (let [dir (.getPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "by-reach-" (System/nanoTime))) .mkdirs))
        config {:project-dir dir :working-dir dir :permission-mode :auto-approve :default-lm "claude-code/sonnet"}
        r #(agent-reach/agent-reach config %)]
    (is (= :runs (:level (r "explore")))
        "explore's shell has no allow list: it runs what it is told and reaches the network (byj-1wv.115.39)")
    (is (get-in (r "explore") [:shell :any]) "explore's shell has no allow-list either; its deny patterns are a tripwire")
    (is (:reads-anywhere (r "explore")))
    (is (= :writes (:level (r "plan"))))
    (is (get-in (r "plan") [:shell :any]) "plan's shell has no allow-list")
    (is (= :writes (:level (r "edit"))))
    (is (seq (:hands-off (r "claude"))) "claude hands work to Claude Code")
    (is (= :every-tool (:level (r "default"))))
    (is (= :auto-approve (:permission-mode (r "explore"))))
    (is (nil? (r "no-such-agent")))))

(deftest byj-agent-takes-the-one-by-name
  ;; byj-gly.441: `byj agent --name NAME`, where it was a bare word
  (let [dir (.getPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "by-agent-name-" (System/nanoTime))) .mkdirs))
        config {:project-dir dir :working-dir dir :default-lm "claude-code/sonnet"}
        run (fn [& args]
              (let [err (java.io.StringWriter.)
                    code (atom nil)
                    out (binding [*err* err]
                          (with-out-str (reset! code (#'main/agent-command (main/parse-args (vec args)) config))))]
                {:code @code :out out :err (str err)}))]
    (testing "by --name, in full"
      (let [{:keys [code out]} (run "agent" "--name" "explore" "--json")]
        (is (= 0 code))
        (is (= "explore" (:name (json/read-str out))))))
    (testing "a bare word is refused with the flag said, and nothing listed"
      (let [{:keys [code out err]} (run "agent" "explore")]
        (is (= 2 code))
        (is (str/includes? err "byj agent --name explore"))
        (is (str/blank? out))))
    (testing "an unknown name"
      (is (= 1 (:code (run "agent" "--name" "no-such-agent")))))))

(deftest each-session-in-a-window-logs-as-itself
  ;; byj-gly.387: a session started in the window after the first logged its
  ;; events under the FIRST session's id — the bus was built from the run's config
  (let [dir (.getCanonicalPath (doto (io/file "target/main-test/bus-per-session") .mkdirs))
        built-for (atom [])
        base {:config {:working-dir dir :project-dir dir :session-id "first-1" :persist-session? false
                       :max-iterations 2}
              :lm {:provider :mock :format :mock :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}
              :event-bus-for (fn [cfg viewer] (swap! built-for conj (:session-id cfg)) viewer)}
        {:keys [renderer session-fn session-id]} (#'main/tui-session {} base (atom :auto) false {})]
    ((:end-input! renderer))
    (session-fn)
    (is (not= "first-1" session-id) "a second session has an id of its own")
    (is (= [session-id] @built-for) "and its event bus was built for that id, not the first session's")))

(deftest a-sessions-rails-are-the-runs-kept-ones
  ;; byj-gly.457: the store the run reads hands each session's renderer the
  ;; rails as they were left and its views their folds, and keeps what changes
  (let [dir (.getCanonicalPath (doto (io/file "target/main-test/kept-rails") .mkdirs))
        f (rail-state/file dir)
        _ (io/make-parents f)
        _ (spit f (pr-str {:frame {:left {:open? true :view 0 :cols 30} :right {:open? false :view 0}}
                           :folds {:stack/folded #{"Project"}}}))
        store (rail-state/store dir)
        base {:config {:working-dir dir :project-dir dir :session-id "kept-1" :persist-session? false
                       :max-iterations 2}
              :lm {:provider :mock :format :mock :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"ok\"}")}}
        {:keys [renderer]} (#'main/tui-session {:rails-store store} base (atom :auto) false {})
        c (:cells renderer)
        stack (first (get-in c [:rail-views :left]))
        heading (first ((:items stack)))]
    (is (= "▸ Project" (:label heading)) "Project folded, as it was left")
    (tui/resize! c {:w 200 :h 30})
    (is (true? (get-in @(:rails c) [:left :open?])) "the left rail open, as it was left")
    (is (= 30 (get-in @(:rails c) [:left :cols])))
    ((:act stack) heading)
    (swap! (:rails c) assoc-in [:left :cols] 34)
    (is (= {:frame {:left {:open? true :view 0 :cols 34} :right {:open? false :view 0}}
            :folds {:stack/folded #{}}}
           (edn/read-string (slurp f)))
        "what changed is kept in the file")
    ((:end-input! renderer))))

;; --- where the command stands (byj-gly.420) -----------------------------------------

(deftest options-stand-before-the-command-as-after-it
  (let [cmd (fn [& args] (select-keys (main/parse-args (vec args)) [:command :questions :working-dir :provider :model]))]
    (testing "after it, as always"
      (is (= {:command :turns :questions ["list"] :working-dir "/tmp/x"} (cmd "turns" "list" "-C" "/tmp/x"))))
    (testing "before it, as git -C DIR status: once this asked the default model \"eval exam\""
      (is (= {:command :turns :questions ["list"] :working-dir "/tmp/x"} (cmd "-C" "/tmp/x" "turns" "list")))
      (is (= {:command :run :questions [] :provider :mock :model "mock"} (cmd "-p" "mock" "-m" "mock" "run"))))
    (testing "an option's value is never taken for the command"
      (is (= {:command :ask :questions ["hi"] :model "run"} (cmd "-m" "run" "hi"))))
    (testing "a question is still a question"
      (is (= {:command :ask :questions ["explain" "run"] :working-dir "/tmp/x"} (cmd "-C" "/tmp/x" "explain" "run")))
      (is (= {:command :ask :questions ["what" "is" "this"]} (cmd "what" "is" "this")))
      (is (= {:command :ask :questions ["turns"]} (cmd "ask" "turns")) "ask named, the next word is its question")
      (is (= {:command :ask :questions ["eval" "exam"]} (cmd "eval" "exam")) "Share's commands are not byj's (RFC-070 D4)"))
    (testing "nothing but options is ask, as before"
      (is (= :ask (:command (main/parse-args ["-V"]))))
      (is (= :ask (:command (main/parse-args [])))))))

(deftest session-and-agent-are-singular-and-answer-to-their-old-names
  ;; byj-gly.435: `session` and `agent`; `sessions` (brainyard's) and
  ;; `agents` still reach them, for scripts and a byj-share asking by the old name
  (let [cmd (fn [& args] (select-keys (main/parse-args (vec args)) [:command :questions]))]
    (is (= {:command :session :questions ["list"]} (cmd "session" "list")))
    (is (= {:command :session :questions ["list"]} (cmd "sessions" "list")))
    (is (= {:command :agent :questions ["explore"]} (cmd "agent" "explore")))
    (is (= {:command :agent :questions ["explore"]} (cmd "agents" "explore")))
    (is (= {:command :ask :questions ["what" "sessions"]} (cmd "what" "sessions")) "only as the command")))

(deftest the-help-is-the-commands-wherever-the-options-stand
  ;; byj-gly.436: the help read its command off the raw args, stopping at the
  ;; first option, so `byj -p mock session -h` printed the top page
  (let [help #(with-out-str (apply #'main/main* %&))]
    (is (str/starts-with? (help "session" "-h") "NAME:\n byj session - "))
    (is (str/starts-with? (help "-p" "mock" "session" "-h") "NAME:\n byj session - ") "an option and its value before it")
    (is (str/starts-with? (help "-C" "/tmp/x" "sessions" "list" "-h") "NAME:\n byj session list - ") "and a subcommand, by the old name")
    (is (str/starts-with? (help "-p" "mock" "-h") "NAME:\n byj - ") "no command: the top page")
    (is (str/starts-with? (help "what" "is" "this" "-h") "NAME:\n byj - ") "a question names no command")))

(defn- run-main
  "`byj args…` in this process: `{:exit :out :err}`, the exit the code
  `main/*exit*` was handed, nil for none (byj-gly.442: these ran byj as a
  process, seven seconds of the serial suite)."
  [& args]
  (let [err (java.io.StringWriter.)
        code (atom nil)
        out (with-out-str
              (binding [*err* err
                        main/*exit* (fn [c] (throw (ex-info "exit" {::exit c})))]
                (try (apply #'main/main* args)
                     (catch clojure.lang.ExceptionInfo e
                       (if-let [c (::exit (ex-data e))] (reset! code c) (throw e))))))]
    {:exit @code :out out :err (str err)}))

(deftest an-unknown-option-stops-every-command
  ;; byj-gly.437: the check stood after the commands that need no session,
  ;; so `byj --bogus agent` listed the agents and said nothing of --bogus; a
  ;; regression lists agents or sessions, never asks a model
  (doseq [args [["--bogus" "agent"] ["session" "list" "--bogus"]]]
    (let [{:keys [exit out err]} (apply run-main args)]
      (testing (str/join " " args)
        (is (= 2 exit))
        (is (str/starts-with? err "unknown option --bogus"))
        (is (str/includes? err (str "byj " (first (remove #(str/starts-with? % "-") args)))) "with the command's help")
        (is (str/blank? out) "and nothing of the command's own")))))

(deftest a-measurement-that-cannot-be-had-is-said-in-a-line
  ;; byj-gly.440: a model or a dataset programs eval cannot have was an
  ;; unhandled exception and its trace. On the mock provider, outside this
  ;; repository, so it spends nothing and leaves nothing here
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "byj-programs-" (System/nanoTime)))
        ds (io/file dir ".brainyard" "programs" "react" "think-act" "datasets" "one.edn")
        byj #(apply run-main "-p" "mock" "-C" (str dir) "programs" %&)]
    (io/make-parents ds)
    (spit ds (pr-str {:examples [{:inputs {:question "q"} :labels {:answer "a"}}]}))
    (doseq [[args said] [[["eval" "react/think-act" "nosuch"] "programs eval: no dataset nosuch for react/think-act"]
                         [["eval" "react/think-act" "one" "--lm" "nosuch/x"] "programs eval: Unresolvable :lm nosuch/x"]
                         [["compile" "react/think-act" "nosuch"] "programs compile: no dataset nosuch"]]]
      (let [{:keys [exit err]} (apply byj args)]
        (testing (str/join " " args)
          (is (= 1 exit))
          (is (str/includes? err said) err)
          (is (not (str/includes? err "Unhandled exception"))))))))

(deftest byj-turns-lists-a-session-s-turns-and-diffs-one
  ;; RFC-069: a scratch repository outside this one, two turns made through
  ;; brainyard.changes as the session makes them, read back by the CLI
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-turns-" (System/nanoTime))) .mkdirs))
        sh #(apply brainyard.effect/sh {:dir dir :out :string :err :string} %&)
        cfg {:project-dir dir :session-id "s-cli"}]
    (sh "git" "init" "-q")
    (spit (io/file dir "a") "a\n")
    (sh "git" "add" "a")
    (sh "git" "-c" "commit.gpgSign=false" "-c" "user.name=x" "-c" "user.email=x@example.invalid" "commit" "-qm" "i")
    (changes/begin-turn! cfg 1 "make b")
    (spit (io/file dir "b") "b\n")
    (changes/end-turn! cfg)
    (changes/begin-turn! cfg 2 "just talk")
    (changes/end-turn! cfg)
    (let [{:keys [exit out]} (run-main "-C" dir "turns" "list" "s-cli")
          [l1 l2] (str/split-lines out)]
      (is (= 0 exit))
      (is (re-matches #"\s+1  1 file\s+\S+  make b" l1) l1)
      (is (re-matches #"\s+2  -\s+\S+  just talk" l2) "a turn that changed nothing is listed too"))
    (let [{:keys [exit out]} (run-main "-C" dir "turns" "list" "s-cli" "--json")]
      (is (= 0 exit))
      (is (= [["b"] []] (mapv :files (json/read-str out)))))
    (let [{:keys [exit out]} (run-main "-C" dir "turns" "diff" "s-cli")]
      (is (= 0 exit))
      (is (str/includes? out "+++ b/b\n@@ -0,0 +1 @@\n+b\n") "the last turn that changed files"))
    (is (= (run-main "-C" dir "turns" "diff" "s-cli") (run-main "-C" dir "emacs" "changes" "s-cli")) "byj emacs changes says the same")
    (let [{:keys [exit err]} (run-main "-C" dir "turns" "diff" "s-cli" "2")]
      (is (= 1 exit))
      (is (str/includes? err "changed no files")))
    (testing "the files put back, and taken back"
      (let [{:keys [exit out]} (run-main "-C" dir "turns" "rewind" "s-cli" "1")]
        (is (= 0 exit))
        (is (str/starts-with? out "1 file put back: b\nwhat was there is kept as refs/byj/rewinds/s-cli/1"))
        (is (not (.exists (io/file dir "b")))))
      (is (= 0 (:exit (run-main "-C" dir "turns" "undo" "s-cli"))))
      (is (.exists (io/file dir "b")))
      (is (= ["b"] (:files (json/read-str (:out (run-main "-C" dir "turns" "undo-turn" "s-cli" "1" "--json"))))))
      (is (not (.exists (io/file dir "b"))))
      (is (= 2 (:exit (run-main "-C" dir "turns" "rewind" "s-cli"))) "no turn named"))
    (testing "a fork cut before turn 2, the files put back to its start"
      (persist/save! {:working-dir dir :project-dir dir} "s-cli"
                     {:turn-count 2 :previous-turns [{:question "make b" :answer "made" :turn 1} {:question "just talk" :answer "ok" :turn 2}]})
      (spit (io/file dir "b") "b\n")
      (let [{:keys [exit out]} (run-main "-C" dir "turns" "fork" "s-cli" "2" "--json")
            {:keys [session-id before-turn files]} (json/read-str out)]
        (is (= 0 exit))
        (is (= 2 before-turn))
        (is (= [] files) "turn 2 changed nothing: the files are as it found them")
        (is (= ["make b"] (map :question (:previous-turns (persist/read-session {:project-dir dir} session-id))))))
      (is (= 1 (:exit (run-main "-C" dir "turns" "fork" "s-cli" "5"))) "not one of its turns"))
    (testing "the session is told at its next turn, by a note left with it (byj-gly.488)"
      (let [c {:working-dir dir :project-dir dir}]
        (is (= 0 (:exit (run-main "-C" dir "turns" "rewind" "s-cli" "1"))))
        (is (= ["/rewind 1: the person put the work tree back as it was when turn 1 began. Changed on disk: b. What was written to those files after that is no longer there: read a file again before relying on what it held. (Done from outside this session, with byj turns rewind.)"]
               (persist/take-notes! c "s-cli")))
        (let [{:keys [session-id]} (json/read-str (:out (run-main "-C" dir "turns" "fork" "s-cli" "2" "keep-files" "--json")))]
          (is (re-find #"^This session was forked from session s-cli before its turn 2, keeping the files as they were"
                       (first (persist/take-notes! c session-id))))
          (is (nil? (persist/take-notes! c "s-cli")) "the files did not move: the session forked from has nothing to be told"))))
    (testing "not while a turn runs in the work tree (byj-gly.487): pid 1 is a live process, not this one"
      (let [d (io/file (str/trim (:out (sh "git" "rev-parse" "--path-format=absolute" "--git-path" "byj/running"))))]
        (.mkdirs d)
        (spit (io/file d "s-other") (pr-str {:session "s-other" :turn 3 :pid 1 :at 0}))
        (let [{:keys [exit err]} (run-main "-C" dir "turns" "undo" "s-cli")]
          (is (= 1 exit))
          (is (str/includes? err "session s-other is in the middle of turn 3 in this work tree (process 1)")))
        (.delete (io/file d "s-other"))))
    (is (= 2 (:exit (run-main "-C" dir "turns" "diff"))) "no session named")
    (is (= 2 (:exit (run-main "-C" dir "turns"))) "no subcommand")))

