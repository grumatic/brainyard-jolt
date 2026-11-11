(ns brainyard.claude-reach-test
  "A live Claude Code session as a target of the session tools (byj-gly.429):
  listed, reachable when it runs in tmux — in a tmux session byj did not
  start — handed a task typed into its pane, and its answer brought back;
  one outside tmux listed unreachable and refused. On the stand-in for Claude
  Code (test/fixtures/fake-claude), a Claude Code home and a tmux server of
  the test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.channel :as channel]
            [brainyard.agent-host :as ah]
            [brainyard.claude-host :as host]
            [brainyard.claude-reach :as reach]
            [brainyard.effect :as fx]
            [brainyard.persist :as persist]
            [brainyard.tasks :as tasks]
            [brainyard.json :as json]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-claude-reach-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(def fake (.getCanonicalPath (io/file "test/fixtures/fake-claude")))

(defmacro ^:private on-own-tmux [label & body]
  `(binding [ah/*tmux* ["tmux" "-L" ~label]]
     (try ~@body
          (finally (fx/sh {:out :string :err :string} "tmux" "-L" ~label "kill-server")))))

(defn- start-outside-byj!
  "The stand-in in a tmux session named `work` — not one byj started — in
  `project`, as a person who runs Claude Code in their own tmux has it."
  [label home project id]
  (fx/sh {:out :string :err :string} "tmux" "-L" label "new-session" "-d" "-s" "work" "-x" "160" "-y" "40"
         "-c" project (str fake " --fake-home " home " --session-id " id))
  (loop [n 0]
    (when (and (nil? (host/record id home)) (< n 100)) (Thread/sleep 50) (recur (inc n)))))

(defn- record-outside-tmux!
  "A live record of a session in a terminal of its own: no tmux field."
  [home project id]
  (let [f (io/file home "sessions" "4242.json")]
    (io/make-parents f)
    (spit f (json/write-str {:pid (parse-long (persist/own-pid)) :sessionId id :cwd project :kind "interactive"
                             :status "idle" :name "in-orca" :updatedAt (System/currentTimeMillis)}))))

(deftest a-claude-code-session-in-any-tmux-is-reached-and-one-outside-is-not
  (let [label (str "byj-reach-" (random-uuid))
        home (fresh-dir "home")
        project (fresh-dir "project")
        config {:project-dir project :working-dir project :session-id "byj-me" :claude-home home}
        in-tmux "aaaa1111-0000-4000-8000-000000000001"
        outside "bbbb2222-0000-4000-8000-000000000002"]
    (on-own-tmux label
      (start-outside-byj! label home project in-tmux)
      (record-outside-tmux! home project outside)
      (testing "listed by the session tools, reachable or not, and why not"
        (let [ss (into {} (map (juxt :session-id identity)) (reach/sessions config {:home home}))]
          (is (= #{(str "claude:" in-tmux) (str "claude:" outside)} (set (keys ss))))
          (is (:reachable? (ss (str "claude:" in-tmux))) "in a tmux session byj did not start: its pane, from the record")
          (is (not (:reachable? (ss (str "claude:" outside)))))
          (is (str/includes? (:why (ss (str "claude:" outside))) "outside tmux"))))
      (testing "found by claude:<id>, a prefix, or its label"
        (is (= (str "claude:" in-tmux) (:session-id (reach/find-session config (str "claude:" in-tmux) {:home home}))))
        (is (= (str "claude:" in-tmux) (:session-id (reach/find-session config "aaaa1111" {:home home}))))
        (is (= (str "claude:" outside) (:session-id (reach/find-session config "in-orca" {:home home}))))
        (is (:error (reach/find-session config "nobody" {:home home}))))
      (testing "a short task is typed into its pane, and the turn's answer comes back"
        (let [s (reach/find-session config in-tmux {:home home})]
          (is (= {:status :answered :answer "echo: say hi" :session-id (str "claude:" in-tmux)}
                 (fx/run-value!! (reach/ask-task config s "say hi" 15000 {:home home}))))))
      (testing "a long one is a file in its project, and the typed line points at it"
        (let [s (reach/find-session config in-tmux {:home home})
              task (str "Fix the status line.\n\n" (apply str (repeat 40 "It shows the wrong count. ")))
              r (fx/run-value!! (reach/ask-task config s task 15000 {:home home}))
              [f] (seq (.listFiles (io/file project ".brainyard" "handovers")))]
          (is (= :answered (:status r)))
          (is (str/starts-with? (:answer r) (str "echo: Read " (.getCanonicalPath f) " and do the task in it")))
          (is (str/includes? (:answer r) "handed to you from byj session byj-me of this project"))
          (is (= (str/trim task) (slurp f)) "the brief, whole")))
      (testing "one outside tmux: refused, with what to do instead"
        (let [r (fx/run-value!! (reach/ask-task config (reach/find-session config outside {:home home}) "x" 5000 {:home home}))]
          (is (= :error (:status r)))
          (is (str/includes? (:error r) "cannot be reached"))
          (is (str/includes? (:error r) "agent$claude")))))))

(deftest the-session-tools-hand-a-claude-code-session-work
  (let [label (str "byj-reach-tools-" (random-uuid))
        home (fresh-dir "home")
        project (fresh-dir "project")
        config {:project-dir project :working-dir project :session-id "byj-me" :claude-home home}
        cmds (into {} (map (juxt :name identity)) (channel/commands config))
        registry (tasks/registry)
        ctx {:task-registry registry :config config}
        call (fn [n args] ((:fn (get cmds n)) args ctx))
        in-tmux "cccc3333-0000-4000-8000-000000000003"
        outside "dddd4444-0000-4000-8000-000000000004"]
    (on-own-tmux label
      (start-outside-byj! label home project in-tmux)
      (record-outside-tmux! home project outside)
      (testing "session$list names them with byj's own"
        (let [ids (set (map :session-id (:sessions (call "session$list" {}))))]
          (is (contains? ids (str "claude:" in-tmux)))
          (is (contains? ids (str "claude:" outside)))))
      (testing "session$status says how one stands"
        (is (= :idle (:status (call "session$status" {:session (str "claude:" in-tmux)})))))
      (testing "session$send: a background task whose result is the answer"
        (let [{:keys [task-id session-id] :as r} (call "session$send" {:session (str "claude:" in-tmux) :question "count the shares"})]
          (is (string? task-id) (pr-str r))
          (is (= (str "claude:" in-tmux) session-id))
          (let [done (loop [n 0]
                       (let [t (tasks/snapshot registry task-id)]
                         (if (or (#{:completed :failed} (:status t)) (> n 150)) t (do (Thread/sleep 100) (recur (inc n))))))]
            (is (= "echo: count the shares" (get-in done [:result :answer])) (pr-str done)))))
      (testing "session$send to one outside tmux is refused at once, saying why"
        (let [r (call "session$send" {:session (str "claude:" outside) :question "x"})]
          (is (nil? (:task-id r)))
          (is (str/includes? (:error r) "outside tmux"))))
      (testing "session$cancel is refused: its turn is cancelled in its own terminal"
        (is (str/includes? (:error (call "session$cancel" {:session (str "claude:" in-tmux)})) "Esc in its own terminal"))))))
