(ns brainyard.claude-sessions-test
  "This project's Claude Code sessions, read from a fixture `~/.claude`
  (byj-gly.287.1): saved from `projects/<key>/*.jsonl`, live from
  `sessions/<pid>.json`, and nothing that does not parse is fatal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.claude-sessions :as cs]
            [brainyard.json :as json]))

(defn- fresh-dir [name]
  (let [d (io/file "target/claude-sessions-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- line [m] (str (json/write-str m) "\n"))

(deftest a-project-path-becomes-claude-codes-directory-name
  ;; as the directories under ~/.claude/projects show: `/`, `.` and `_` all `-`
  (is (= "-Users-me-Projects-my-app-v1-2" (cs/project-key "/Users/me/Projects/my_app/v1.2")))
  ;; the canonical path, as Claude Code writes it: /tmp is /private/tmp on
  ;; macOS, and itself on Linux (byj-ahf.29)
  (is (= (str/replace (.getCanonicalPath (io/file "/tmp")) #"[^A-Za-z0-9]" "-") (cs/project-key "/tmp"))
      "the canonical path")
  (when (str/starts-with? (str (System/getProperty "os.name")) "Mac")
    (is (= "-private-tmp" (cs/project-key "/tmp")) "on macOS, as Claude Code writes it")))

(deftest a-projects-sessions-are-read-live-and-saved
  (let [home (fresh-dir "home")
        project (fresh-dir "my_project")
        other (fresh-dir "other")
        pdir (io/file home "projects" (cs/project-key project))
        sdir (io/file home "sessions")
        save! (fn [id & lines] (.mkdirs pdir) (spit (io/file pdir (str id ".jsonl")) (apply str lines)))]
    ;; a title only in the tail, past the head that is read for the question
    (save! "aaaa" (line {:type "mode" :mode "normal"})
           (line {:type "user" :message {:role "user" :content "fix the flaky test"}})
           (apply str (repeat 3000 (line {:type "assistant" :message {:content [{:type "text" :text "working on it"}]}})))
           (line {:type "ai-title" :aiTitle "first-title"})
           (line {:type "ai-title" :aiTitle "fix-flaky-io-test"}))
    ;; no title: the first real question, past what a slash command leaves
    (save! "bbbb" (line {:type "user" :message {:content "<local-command-caveat>Caveat: …</local-command-caveat>"}})
           (line {:type "user" :isMeta true :message {:content "meta"}})
           (line {:type "user" :message {:content [{:type "text" :text "  add a\n test for\tparse-date "}]}}))
    ;; nothing readable: shown by its id
    (save! "cccc" "not json\n{\"cut\": \n")
    ;; a live one, and its saved file
    (save! "dddd" (line {:type "ai-title" :aiTitle "from-the-file"}))
    (.mkdirs sdir)
    (spit (io/file sdir "100.json") (json/write-str {:pid 100 :sessionId "dddd" :cwd project :status "busy" :kind "interactive" :updatedAt 5}))
    (spit (io/file sdir "101.json") (json/write-str {:pid 101 :sessionId "eeee" :cwd project :name "named-live" :status "idle" :updatedAt 9}))
    (spit (io/file sdir "102.json") (json/write-str {:pid 102 :sessionId "ffff" :cwd other :status "busy"}))
    (spit (io/file sdir "103.json") (json/write-str {:pid 103 :sessionId "gggg" :cwd project :status "busy"}))
    (spit (io/file sdir "104.json") "{broken")
    (let [{:keys [live saved]} (cs/sessions project :home home :alive? #(contains? #{100 101 102} %))
          by-id (into {} (map (juxt :id identity)) saved)]
      (testing "live: this project's, with a live process; newest first"
        (is (= ["eeee" "dddd"] (map :id live)))
        (is (= ["named-live" "from-the-file"] (map :title live)) "a live one with no name takes its file's title")
        (is (= "busy" (:status (second live)))))
      (testing "saved: what is not live, each titled"
        (is (= #{"aaaa" "bbbb" "cccc"} (set (keys by-id))))
        (is (= "fix-flaky-io-test" (:title (by-id "aaaa"))) "the newest ai-title, from the tail")
        (is (= "add a test for parse-date" (:title (by-id "bbbb"))) "the first real question, on one line")
        (is (nil? (:title (by-id "cccc"))) "unreadable: no title, still listed")
        (is (every? (comp number? :updated-at) saved))
        (is (> (:bytes (by-id "aaaa")) cs/head-bytes))))
    (testing "a file read once is remembered until it changes"
      (let [f (io/file pdir "bbbb.jsonl")]
        (is (= "add a test for parse-date" (:title (cs/read-saved f))))
        (spit f (line {:type "ai-title" :aiTitle "renamed"}) :append true)
        (.setLastModified f (+ 2000 (.lastModified f)))
        (is (= "renamed" (:title (first (filter #(= "bbbb" (:id %)) (cs/saved project home))))))))
    (testing "no ~/.claude at all is no sessions"
      (is (= {:live [] :saved []} (cs/sessions project :home (str home "/nope")))))))

(deftest a-session-begun-with-a-paste-is-titled-by-it
  ;; byj-gly.288.41: the paste's wrapper read as a slash command's markup, so
  ;; a session whose first question was pasted was listed by its id
  (let [dir (fresh-dir "pasted")
        f (io/file dir "hhhh.jsonl")]
    (spit f (str (line {:type "user" :message {:content "<command-name>/clear</command-name>"}})
                 (line {:type "user" :message {:content "\n\n<pasted_content id=\"0e59\">\nfix the flaky\ntest\n</pasted_content id=\"0e59\">\n"}})))
    (is (= "fix the flaky test" (:title (cs/read-saved f))))
    (is (= "a\nb" (cs/unpasted "<pasted_content id=\"1\">\na\nb\n</pasted_content id=\"1\">")))
    (is (= "<command-name>/clear</command-name>" (cs/unpasted "<command-name>/clear</command-name>")) "other markup is left")))

(deftest the-newest-turn-is-read-as-the-transcript-grows
  ;; byj-gly.290.2: Claude Code writes a ~170 KB prompt_snapshot after a reply,
  ;; so a fixed tail missed the turn; read on from where the last call stopped
  (let [f (io/file (fresh-dir "turns") "t.jsonl")
        add! (fn [& ms] (spit f (apply str (map line ms)) :append true))
        reply (fn [stop & blocks] {:type "assistant" :message {:stop_reason stop :content (vec blocks)}})
        ;; the turn itself; what Claude Code did in it is the next test's
        turn #(dissoc (cs/last-turn %) :activity)]
    (is (nil? (cs/last-turn (io/file (fresh-dir "turns-none") "none.jsonl"))) "no file yet")
    (add! {:type "user" :message {:content "<command-name>/clear</command-name>"}}
          {:type "user" :message {:content "first task"}}
          (reply "tool_use" {:type "text" :text "looking"}))
    (is (= {:prompt "first task" :answer "looking" :ended? false} (turn f)) "a tool call does not end a turn")
    (add! (reply "end_turn" {:type "thinking" :thinking "…"}))
    (is (= {:prompt "first task" :answer "looking" :ended? true} (turn f))
        "ended at its thinking, before its text is written")
    (add! (reply "end_turn" {:type "text" :text "done"})
          {:type "attachment" :attachment {:type "prompt_snapshot" :text (apply str (repeat 200000 "x"))}})
    (is (= {:prompt "first task" :answer "done" :ended? true} (turn f)) "past a line bigger than any tail")
    (spit f (str (line {:type "user" :message {:content "second"}}) "{\"type\":\"assistant\",\"mess") :append true)
    (is (= {:prompt "second" :answer nil :ended? false} (turn f)) "a line half written waits")
    (spit f (str "age\":{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"two\"}]}}\n") :append true)
    (is (= {:prompt "second" :answer "two" :ended? true} (turn f)) "…and is read once whole")
    (add! {:type "user" :message {:content "third"}} {:type "user" :message {:content "[Request interrupted by user]"}})
    (is (= {:prompt "third" :answer nil :ended? true} (turn f)) "stopped is ended, and no one's question")
    (spit f (line {:type "user" :message {:content "anew"}}))
    (is (= {:prompt "anew" :answer nil :ended? false} (turn f)) "a file rewritten shorter is read anew")
    (testing "Claude Code's own end-of-turn line ends a turn that wrote no end_turn reply (byj-gly.290.10)"
      (add! (reply "tool_use" {:type "text" :text "compacting"})
            {:type "system" :subtype "compact_boundary"}
            {:type "system" :subtype "turn_duration" :durationMs 4200})
      (is (= {:prompt "anew" :answer "compacting" :ended? true} (turn f))))
    (testing "a session continued under a new id says which (byj-gly.290.10)"
      (add! {:type "continued-in" :sessionId "t" :continuedInSessionId "t2"})
      (is (= "t2" (:continued-in (turn f)))))))

(deftest a-sessions-tokens-are-counted-once-a-reply-by-model
  (let [f (io/file (fresh-dir "usage") "u.jsonl")
        u {:input_tokens 10 :cache_read_input_tokens 100 :cache_creation_input_tokens 5 :output_tokens 7}
        r (fn [id model stop b] {:type "assistant" :message {:id id :model model :stop_reason stop :content [b] :usage u}})]
    (spit f (str (line {:type "user" :message {:content "go"}})
                 ;; one reply in two lines, each repeating its usage
                 (line (r "m1" "claude-haiku-4-5" "end_turn" {:type "thinking" :thinking "…"}))
                 (line (r "m1" "claude-haiku-4-5" "end_turn" {:type "text" :text "ok"}))
                 (line {:type "user" :message {:content "again"}})
                 (line (r "m2" "claude-sonnet-5" "end_turn" {:type "text" :text "ok2"}))
                 (line (r "s1" "<synthetic>" "end_turn" {:type "text" :text "rate limited"}))))
    (is (= {"claude-haiku-4-5" {:fresh 10 :cache-read 100 :cache-write 5 :out 7}
            "claude-sonnet-5" {:fresh 10 :cache-read 100 :cache-write 5 :out 7}}
           (:usage (cs/last-turn f)))
        "across turns, a reply once however many lines, a synthetic one no model's")
    (is (= "rate limited" (:answer (cs/last-turn f))))))

(deftest what-claude-code-does-is-read-as-numbered-steps
  ;; byj-gly.299: claude$await shows Claude Code's work as it happens
  (let [f (io/file (fresh-dir "activity") "a.jsonl")
        r (fn [id & blocks] {:type "assistant" :message {:id id :stop_reason "tool_use" :content (vec blocks)}})]
    (spit f (str (line {:type "user" :message {:content "fix it"}})
                 (line (r "m1" {:type "thinking" :thinking "…"}))
                 (line (r "m1" {:type "tool_use" :name "Bash" :input {:command "python3 -m unittest\nmore"}}))
                 (line (r "m2" {:type "tool_use" :name "Edit" :input {:file_path "/w/src/mathx.py"}}))
                 (line (r "m3" {:type "text" :text "Adding clamp with tests.\nSecond line."}))))
    (is (= [{:n 1 :line "Bash(python3 -m unittest)"} {:n 2 :line "Edit(mathx.py)"} {:n 3 :line "\"Adding clamp with tests.\""}]
           (:activity (cs/last-turn f)))
        "a tool call by what it works on, text by its first line, thinking not at all")
    (spit f (apply str (for [i (range 30)] (line (r (str "x" i) {:type "tool_use" :name "Read" :input {:file_path (str "/w/f" i)}}))))
          :append true)
    (let [a (:activity (cs/last-turn f))]
      (is (= cs/activity-kept (count a)) "the newest only")
      (is (= 33 (:n (last a))) "numbered on, so a reader takes only what is new"))))
