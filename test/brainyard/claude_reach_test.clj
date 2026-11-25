;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

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
            [brainyard.json :as json]
            [brainyard.unix-socket]
            [jolt.http.net]))

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

;; --- a session's inbox (byj-bzo) ----------------------------------------------------------------

(defn- read-all
  "Every line a connection sends until it closes."
  [fd]
  (loop [acc ""]
    (if-let [b (jolt.http.net/recv-bytes fd)]
      (recur (str acc (String. ^bytes b "UTF-8")))
      (vec (remove str/blank? (str/split-lines acc))))))

(defn- fake-inbox!
  "A live record of a session in a terminal of its own, with an inbox — the
  socket a stand-in for Claude Code listens at, and the key beside the
  record — and what the session does with a message, `(on-message text
  record! log!)`: `record!` sets its status, `log!` appends transcript lines.
  The lines it was sent go to `got`. `{:stop!}`."
  [home project id got on-message]
  (let [pid (persist/own-pid)
        sock (str "/tmp/byj-in-" (subs (str (random-uuid)) 0 8) ".sock")
        rec (io/file home "sessions" (str pid ".json"))
        record! (fn [status]
                  (spit rec (json/write-str {:pid (parse-long pid) :sessionId id :cwd project :kind "interactive"
                                             :status status :name "in-a-terminal" :updatedAt (System/currentTimeMillis)
                                             :messagingSocketPath sock :peerProtocol 1})))
        log (host/transcript id project home)
        log! (fn [& ls] (spit log (str/join (map #(str (json/write-str %) "\n") ls)) :append true))]
    (io/make-parents rec) (io/make-parents log)
    (spit (io/file home "sessions" (str pid ".0a1b2c.key")) (json/write-str {:peerToken "the-token"}))
    (record! "idle")
    (log! {:type "user" :message {:role "user" :content "an earlier question"}}
          {:type "assistant" :message {:role "assistant" :stop_reason "end_turn" :content [{:type "text" :text "an earlier answer"}]}})
    (let [srv (brainyard.unix-socket/listen!
               sock (fn [fd]
                      (let [ls (read-all fd)]
                        (jolt.http.net/close fd)
                        (reset! got ls)
                        (on-message (some-> (second ls) json/read-str :message :content) record! log!))))]
      {:stop! (:stop! srv)})))

(defn- peer-turn!
  "What Claude Code does with a message an idle session is sent (measured,
  dev/claude-peer-spike.sh): busy, the message as an isMeta user line of
  origin peer between its head and footer, then the reply, then idle."
  [text record! log!]
  (record! "busy")
  (log! {:type "user" :isMeta true :origin {:kind "peer" :from "unknown"}
         :message {:role "user" :content (str "Another Claude session sent a message:\n" text
                                              "\n\nThis came from another Claude session — not typed by your user.")}})
  (Thread/sleep 300)
  (log! {:type "assistant" :message {:role "assistant" :stop_reason "end_turn"
                                     :content [{:type "text" :text (str "done: " (count text) " chars")}]}})
  (record! "idle"))

(deftest a-claude-code-session-with-an-inbox-is-handed-the-whole-brief-through-it
  (let [home (fresh-dir "home")
        project (fresh-dir "project")
        config {:project-dir project :working-dir project :session-id "byj-me" :claude-home home}
        id "eeee5555-0000-4000-8000-000000000005"
        got (atom nil)
        {:keys [stop!]} (fake-inbox! home project id got peer-turn!)]
    (try
      (testing "listed reachable through its inbox, though outside tmux"
        (let [s (reach/find-session config id {:home home})]
          (is (= [true :inbox] ((juxt :reachable? :via) s)))))
      (testing "a long brief goes whole, under a line saying who hands it on, and the turn's answer comes back"
        (let [task (str "Fix the status line.\n\n" (apply str (repeat 40 "It shows the wrong count. ")))
              msg (reach/inbox-message task "byj-me")
              r (fx/run-value!! (reach/ask-task config (reach/find-session config id {:home home}) task 15000 {:home home}))]
          (is (= {:status :answered :answer (str "done: " (count msg) " chars") :session-id (str "claude:" id)} r))
          (is (= [{:type "auth" :token "the-token"} {:type "user" :message {:role "user" :content msg}}]
                 (mapv json/read-str @got))
              "the token, then the message, a JSON object a line")
          (is (str/starts-with? msg "[byj] byj session byj-me of this project hands you this task:\n\nFix the status line."))
          (is (not (.exists (io/file project ".brainyard" "handovers"))) "no file to point at")))
      (testing "the transcript reads the peer's turn as the turn, its prompt the message alone"
        (let [[_ t] (host/current-turn id project home)]
          (is (str/starts-with? (:prompt t) "[byj] byj session byj-me"))
          (is (not (str/includes? (:prompt t) "Another Claude session")))
          (is (:ended? t))))
      (finally (stop!)))))

(deftest a-message-taken-into-a-running-turn-is-refused
  ;; measured: a message sent while a turn runs is absorbed into it, never a
  ;; turn of its own, and nothing answers it — the record said idle when it
  ;; was sent, and a turn began as it came
  (let [home (fresh-dir "home")
        project (fresh-dir "project")
        id "ffff6666-0000-4000-8000-000000000006"
        got (atom nil)
        {:keys [stop!]} (fake-inbox! home project id got
                                     (fn [text record! log!]
                                       (log! {:type "queue-operation" :operation "enqueue" :content text}
                                             {:type "queue-operation" :operation "remove" :reason "absorbed_mid_turn" :content text})))]
    (try
      (let [r (fx/run-value!! (host/send-message id "[byj] do the thing" {:home home}))]
        (is (str/includes? (str (:error r)) "took it into that turn") (pr-str r)))
      (testing "and one with no inbox, or not idle, is refused before anything is sent"
        (reset! got nil)
        (is (str/includes? (:error (fx/run-value!! (host/send-message "no-such-id" "x" {:home home}))) "has no inbox"))
        (spit (io/file home "sessions" (str (persist/own-pid) ".json"))
              (json/write-str {:pid (parse-long (persist/own-pid)) :sessionId id :cwd project :status "busy"
                               :messagingSocketPath "/tmp/none.sock" :peerProtocol 1}))
        (is (str/includes? (:error (fx/run-value!! (host/send-message id "x" {:home home}))) "busy, not idle"))
        (is (nil? @got)))
      (finally (stop!)))))
