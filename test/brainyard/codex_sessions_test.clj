(ns brainyard.codex-sessions-test
  "A project's Codex sessions read from a Codex home of the test's own
  (byj-gly.430.2, RFC-049): the rollouts of the project and its worktrees,
  titled, live while their writer holds the lock, and the newest turn read as
  the rollout grows. The lines are shaped as Codex 0.163 writes them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.codex-sessions :as cx]
            [brainyard.json :as json]
            [jolt.ffi :as ffi]))

(defn- fresh-dir [nm]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-codex-sessions-" nm "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- line [type payload] (json/write-str {:timestamp "2026-10-23T07:00:00Z" :type type :payload payload}))

(defn- meta-line [id cwd]
  (line "session_meta" {:id id :cwd cwd :cli_version "0.163.0" :base_instructions {:text "THE INSTRUCTIONS"}}))

(defn- ask [text] (line "event_msg" {:type "item_completed" :item {:type "UserMessage" :content [{:type "text" :text text}]}}))
(defn- say [text] (line "event_msg" {:type "item_completed" :item {:type "AgentMessage" :content [{:type "Text" :text text}]}}))
(defn- started [] (line "event_msg" {:type "task_started" :turn_id "t"}))
(defn- complete [& [last-msg]] (line "event_msg" (cond-> {:type "task_complete"} last-msg (assoc :last_agent_message last-msg))))

(defn- rollout!
  "A rollout of thread `id` run in `cwd`, its lines after the session_meta."
  [home day id cwd ls & [modified]]
  (let [f (io/file home "sessions" "2026" "10" day (str "rollout-2026-10-" day "T07-00-00-" id ".jsonl"))]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (cons (meta-line id cwd) ls)) "\n"))
    (when modified (.setLastModified f modified))
    f))

(ffi/defcfn ^:private c-open "open" [:string :int] :int)
(ffi/defcfn ^:private c-flock "flock" [:int :int] :int)
(ffi/defcfn ^:private c-close "close" [:int] :int)

(defn- hold-lock!
  "Thread `id`'s writer lock taken exclusively, as Codex holds it while the
  session runs: the fd to close to let it go."
  [home id]
  (let [f (io/file home "thread-writer-locks" (str id ".lock"))]
    (.mkdirs (.getParentFile f))
    (spit f "")
    (let [fd (c-open (str f) 0)]
      (is (zero? (c-flock fd (bit-or 2 4))) "LOCK_EX | LOCK_NB taken")
      fd)))

(deftest a-projects-sessions-are-its-rollouts-titled
  (let [home (fresh-dir "home") project (fresh-dir "project") wt (fresh-dir "worktree") other (fresh-dir "other")]
    (rollout! home "21" "aaa" project [(started) (ask "fix the flaky test") (say "fixed") (complete)] 1000)
    (rollout! home "22" "bbb" wt [(started) (ask "write the docs") (complete "written")] 3000)
    (rollout! home "23" "ccc" project [(started)] 2000)
    (rollout! home "23" "ddd" other [(started) (ask "not ours")] 4000)
    (spit (io/file home "sessions" "2026" "10" "23" "rollout-2026-10-23T07-00-00-eee.jsonl.zst") "cold")
    (spit (io/file home "session_index.jsonl")
          (str (json/write-str {:id "aaa" :thread_name "Old name" :updated_at "x"}) "\n"
               (json/write-str {:id "aaa" :thread_name "Fix flaky test" :updated_at "y"}) "\n"))
    (let [s (cx/saved project home [project wt])]
      (is (= ["bbb" "ccc" "aaa"] (mapv :id s)) "newest first; another project's and a cold rollout left out")
      (is (= "Fix flaky test" (:title (last s))) "the index's newest name for the thread")
      (is (= "write the docs" (:title (first s))) "else its first question")
      (is (nil? (:title (second s))) "none asked yet: no title")
      (is (= wt (:dir (first s))) "a worktree's session names its directory")
      (is (nil? (:dir (last s))))
      (is (not-any? #(some-> (:title %) (str/includes? "INSTRUCTIONS")) s)))
    (testing "live while its writer holds the lock, and listed live only"
      (is (false? (cx/live? home "aaa")) "no lock file: not live")
      (let [fd (hold-lock! home "aaa")]
        (try
          (is (true? (cx/live? home "aaa")))
          (let [{:keys [live saved]} (cx/sessions project :home home :dirs [project wt])]
            (is (= ["aaa"] (mapv :id live)))
            (is (= ["bbb" "ccc"] (mapv :id saved))))
          (finally (c-close fd))))
      (is (false? (cx/live? home "aaa")) "let go: not live"))))

(deftest the-newest-turn-is-read-as-the-rollout-grows
  (let [home (fresh-dir "turns") project (fresh-dir "p")
        f (rollout! home "23" "fff" project [(started) (ask "list the files")])]
    (is (= {:prompt "list the files" :answer nil :ended? false :aborted? false :busy? true} (cx/last-turn f)))
    (spit f (str (say "I'll look.") "\n" (say "two files") "\n"
                 (line "event_msg" {:type "token_count" :info {:total_token_usage {:input_tokens 1200 :cached_input_tokens 900 :output_tokens 40}}}) "\n"
                 (complete "two files") "\n")
          :append true)
    (is (= {:prompt "list the files" :answer "two files" :ended? true :aborted? false :busy? false
            :usage {:input_tokens 1200 :cached_input_tokens 900 :output_tokens 40}}
           (cx/last-turn f))
        "read on from where it stopped")
    (testing "a new turn, interrupted"
      (spit f (str (started) "\n" (ask "now the docs") "\n" (line "event_msg" {:type "turn_aborted" :reason "interrupted"}) "\n") :append true)
      (is (= ["now the docs" nil true true false] ((juxt :prompt :answer :ended? :aborted? :busy?) (cx/last-turn f)))))
    (testing "an older Codex's events, and a message written both ways counted once"
      (spit f (str (started) "\n"
                   (line "event_msg" {:type "user_message" :message "and the tests"}) "\n" (ask "and the tests") "\n"
                   (line "event_msg" {:type "agent_message" :message "done"}) "\n" (complete) "\n")
            :append true)
      (is (= ["and the tests" "done" true] ((juxt :prompt :answer :ended?) (cx/last-turn f)))))
    (is (nil? (cx/last-turn (io/file home "nope.jsonl"))))
    (is (= (.getPath f) (some-> (cx/rollout-file home "fff") .getPath)) "found by its thread")
    (is (nil? (cx/rollout-file home "nope")))))
