(ns brainyard.channel-test
  "The session channel (RFC-031): the unix socket under it, brainyard's ops and
  their replies, the queue, and a session answering over its own socket."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.channel :as channel]
            [brainyard.json :as json]
            [brainyard.main]
            [brainyard.persist :as persist]
            [brainyard.tasks]
            [brainyard.session :as session]
            [brainyard.unix-socket :as us]
            [jolt.process :as p]))

(defn- fresh-dir [name]
  (let [d (io/file "target/channel-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- mode-of
  "A file's mode as `ls` shows it: GNU stat's `-c %A` first, then BSD's
  `-f %Sp` — GNU's `-f` is the file system's status, and printed that on
  Linux (byj-ahf.29)."
  [path]
  (str/trim (:out @(p/process {:out :string :err :string} "sh" "-c"
                              (str "stat -c %A " path " 2>/dev/null || stat -f %Sp " path)))))

(defn- in-background
  "`f` on its own thread, its value in a promise — a request blocks until the
  session answers, and the session is on this thread."
  [f]
  (let [out (promise)]
    (.start (Thread. #(deliver out (try (f) (catch Throwable e {:thrown (ex-message e)})))))
    out))

;; --- the socket -------------------------------------------------------------------------

(deftest a-unix-socket-answers-one-line-per-connection
  (let [path (str "/tmp/byj-channel-test-" (System/currentTimeMillis) ".sock")
        {:keys [stop!]} (us/serve! path (fn [m] (if (= :boom (:op m))
                                                  (throw (ex-info "kaput" {}))
                                                  {:status :ok :echo m})))]
    (try
      (testing "a request and its reply, a character outside ASCII intact"
        (is (= {:status :ok :echo {:op :ask :question "héllo ✓"}}
               (us/request! path {:op :ask :question "héllo ✓"} 5000))))
      (testing "the file is the trust model: 0600"
        (is (= "srw-------" (mode-of path))))
      (testing "a failure is a reply, never a dead listener"
        (is (= {:status :error :error "server error: kaput"} (us/request! path {:op :boom} 5000)))
        (is (= {:status :error :error "empty request"} (us/request! path nil 5000)))
        (is (= :ok (:status (us/request! path {:op :again} 5000)))))
      (testing "a second owner is refused while the first answers"
        (is (= :live-owner (:reason (try (us/serve! path identity) (catch Throwable e (ex-data e)))))))
      (finally (stop!)))
    (testing "stopping removes the file, and the session is then unreachable"
      (is (not (.exists (io/file path))))
      (is (thrown? Throwable (us/request! path {:op :status} 1000))))
    (testing "a file nothing answers at is stale and is replaced"
      (spit path "stale")
      (let [{:keys [stop!]} (us/serve! path (constantly {:status :ok}))]
        (try (is (= {:status :ok} (us/request! path {:op :x} 5000)))
             (finally (stop!)))))))

;; --- the channel ------------------------------------------------------------------------

(defn- open [dir & [opts]]
  (channel/open! {:project-dir dir :working-dir dir :ask-timeout-ms 5000}
                 (str "s-" (System/currentTimeMillis)) (merge {:cancel! (constantly true)} opts)))

(defn- fake-session
  "A session that takes each request as it arrives and answers it with
  `answer-of` — what the session tree does through `next-turn!`,
  `turn-started!` and `turn-ended!`."
  [ch answer-of stop?]
  (.start (Thread. #(loop []
                      (when-not @stop?
                        (if-let [{:keys [text]} (channel/next-turn! ch)]
                          (do (channel/turn-started! ch)
                              (channel/turn-ended! ch (answer-of text)))
                          (Thread/sleep 10))
                        (recur))))))

(deftest a-request-becomes-a-turn-and-its-answer-comes-back
  (let [dir (fresh-dir "ask")
        ch (open dir {:describe (constantly {:provider "mock" :model "m"})})
        stop? (atom false)]
    (try
      (is (nil? (:problem ch)))
      (testing "meta.edn names the socket and what it answers"
        (is (= {:ask-socket-path (:path ch) :frame-version channel/frame-version
                :ops [:ask :inject :status :cancel :subscribe :config :emit :mcp :rename-session]}
               (select-keys (channel/read-meta (:config ch) (:session-id ch)) [:ask-socket-path :frame-version :ops]))))
      (fake-session ch (fn [q] {:answer (str "answered: " q) :terminated-by :answer-channel :usage {:llm-calls 1}}) stop?)
      (testing ":ask waits for the turn and returns its answer, with the session's model"
        (is (= {:status :ok :answer "answered: what is 2+2" :usage {:llm-calls 1} :provider "mock" :model "m"}
               (channel/request! (:path ch) {:op :ask :question "what is 2+2"} 10000))))
      (testing ":inject as a turn is the same, awaited by default"
        (is (= {:status :ok :injected :turn :answer "answered: go"}
               (channel/request! (:path ch) {:op :inject :as :turn :text "go"} 10000))))
      (testing "a failed or cancelled turn is brainyard's no answer"
        (reset! stop? true)
        (Thread/sleep 50)
        (let [stop2 (atom false)]
          (fake-session ch (fn [_] {:answer "*(Turn cancelled.)*" :terminated-by :cancelled}) stop2)
          (is (= {:status :error :error "no answer (turn cancelled or failed)"}
                 (channel/request! (:path ch) {:op :ask :question "x"} 10000)))
          (reset! stop2 true)))
      (finally (reset! stop? true) (channel/close! ch)))))

(deftest the-queue-is-bounded-and-a-timeout-does-not-withdraw
  (let [dir (fresh-dir "queue")
        cancelled (atom 0)
        ch (open dir {:cancel! #(do (swap! cancelled inc) true)})]
    (try
      (testing "nobody takes the turn: the asker times out, and the turn stays queued"
        (is (= {:status :error :error "timed out after 150ms"}
               (channel/request! (:path ch) {:op :ask :question "later" :timeout-ms 150} 5000)))
        (is (= 1 (:pending-turns (channel/request! (:path ch) {:op :status} 5000)))))
      (testing "an unawaited inject is queued at once, up to ten"
        (is (= {:status :ok :injected :turn :queued true}
               (channel/request! (:path ch) {:op :inject :as :turn :text "t" :await? false} 5000)))
        (dotimes [_ 8] (channel/request! (:path ch) {:op :inject :as :turn :text "t" :await? false} 5000))
        (is (= {:status :error :error "input queue is full (max 10)"}
               (channel/request! (:path ch) {:op :inject :as :turn :text "t" :await? false} 5000))))
      (testing "status says idle or running, and how many wait"
        (is (= {:status :ok :state :idle :pending-turns 10 :session-id (:session-id ch) :frame-version 2}
               (select-keys (channel/request! (:path ch) {:op :status} 5000) [:status :state :pending-turns :session-id :frame-version])))
        (channel/turn-started! ch)
        (is (= :running (:state (channel/request! (:path ch) {:op :status} 5000)))))
      (testing "cancel reaches the running turn"
        (is (= {:status :ok :cancelled true} (channel/request! (:path ch) {:op :cancel} 5000)))
        (is (= 1 @cancelled)))
      (testing "what is refused, and how"
        (is (= {:status :error :error "empty question"} (channel/request! (:path ch) {:op :ask :question " "} 5000)))
        (is (= {:status :error :error "unknown op: :nope"} (channel/request! (:path ch) {:op :nope} 5000)))
        (is (str/starts-with? (:error (channel/request! (:path ch) {:op :inject :as :shoe :text "x"} 5000))
                              "unsupported :inject :as :shoe")))
      (testing "a request handed over that never became a turn has no answer"
        ;; the ten already queued are taken and dropped first
        (loop [] (when (channel/next-turn! ch) (channel/settle-unanswered! ch) (recur)))
        (let [waiting (in-background #(channel/request! (:path ch) {:op :ask :question "/help"} 5000))]
          (loop [] (when-not (channel/pending? ch) (Thread/sleep 10) (recur)))
          (is (= "/help" (:text (channel/next-turn! ch))))
          ;; the session read it, it was a command, and it is waiting again
          (channel/settle-unanswered! ch)
          (is (= {:status :error :error "no answer (turn cancelled or failed)"} (deref waiting 5000 :hung)))))
      (finally (channel/close! ch)))))

(deftest closing-answers-everyone-still-waiting
  (let [dir (fresh-dir "close")
        ch (open dir)
        waiting (in-background #(channel/request! (:path ch) {:op :ask :question "q"} 10000))]
    (loop [] (when (zero? (:pending-turns (channel/handle ch {:op :status}))) (Thread/sleep 10) (recur)))
    (channel/close! ch)
    (is (= {:status :error :error "target session closed"} (deref waiting 5000 :hung)))
    (is (not (.exists (io/file (:path ch)))) "the socket is gone")
    (testing "a second owner of one session is refused, and says so"
      (let [a (channel/open! {:project-dir dir} "same" {})
            b (channel/open! {:project-dir dir} "same" {})]
        (try (is (nil? (:problem a)))
             (is (str/includes? (:problem b) "a live process owns"))
             (finally (channel/close! a) (channel/close! b)))))))

(deftest a-session-on-a-terminal-says-its-tmux-pane
  ;; RFC-043 (byj-1wv.8.9): meta.edn names the pane an interactive session
  ;; draws in, so a share of it by reference shows its screen; none for a
  ;; session without one, and none outside tmux
  (let [dir (fresh-dir "pane")
        config {:project-dir dir}]
    (let [ch (channel/open! config "with-pane" {:tmux-pane "/tmp/tmux-501/default,%7"})]
      (try (is (= "/tmp/tmux-501/default,%7" (:tmux-pane (channel/read-meta config "with-pane"))))
           (finally (channel/close! ch))))
    (let [ch (channel/open! config "headless" {})]
      (try (is (nil? (:tmux-pane (channel/read-meta config "headless"))))
           (finally (channel/close! ch)))))
  (is (= "/tmp/tmux-501/default,%7" (brainyard.main/tmux-pane {"TMUX" "/tmp/tmux-501/default,123,0" "TMUX_PANE" "%7"})))
  (is (nil? (brainyard.main/tmux-pane {"TMUX_PANE" "%7"})) "outside tmux"))

(deftest a-long-session-dir-falls-back-to-tmpdir
  (let [config {:project-dir (str "/tmp/" (apply str (repeat 120 "d")))}
        p (channel/socket-path config "1790000000000-abcdef12")]
    (is (re-find #"/by-[0-9a-f]{16}\.sock$" p))
    (is (= p (channel/socket-path config "1790000000000-abcdef12")) "the same path both sides work out")))

;; --- a session answering over its socket ------------------------------------------------

(deftest a-session-answers-over-its-own-socket
  (let [dir (fresh-dir "session")
        ch (open dir)
        done? (atom false)
        read-input (fn [_] (loop []
                             (cond @done? nil
                                   :else (if-let [{:keys [text]} (channel/next-turn! ch)]
                                           text
                                           (do (Thread/sleep 10) (recur))))))
        marker (io/file dir "started-by-a-peer")
        said (atom [])
        asked (in-background
               (fn []
                 (let [ask #(channel/request! (:path ch) {:op :ask :question %} 20000)
                       a (ask "what is the answer?")
                       b (ask "/history")
                       c (ask (str "/mcp add evil -- touch " (.getPath marker)))
                       d (ask ":list-dir :path .")
                       e (ask "/grep TODO")]
                   (reset! done? true)
                   [a b c d e])))
        final (session/run {:lm {:provider :mock :format :mock
                                 :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "forty-two"}))}
                            :config {:working-dir dir :project-dir dir :max-iterations 3
                                     :persist-session? false :recap? false}
                            :read-input read-input
                            :emit (fn [kind text] (swap! said conj [kind text]))
                            :channel ch})
        [a b c d e] (deref asked 20000 [:hung])]
    (channel/close! ch)
    (testing "the question ran as a turn of that session, and its answer came back"
      (is (= {:status :ok :answer "forty-two"} (select-keys a [:status :answer])))
      (is (= ["what is the answer?" "call directly as a tool: grep for TODO"] (map :question (:previous-turns final)))
          "and /<tool> args is a turn too: the model's calls in it are gated"))
    (testing "a command or a direct call over the channel is refused, and says why (byj-gly.282)"
      (is (= :error (:status b)))
      (is (str/starts-with? (:error b) "not run: /history — a command or a direct tool call is the person's to type"))
      (is (str/includes? (:error c) "not run: /mcp add evil"))
      (is (str/includes? (:error d) "not run: :list-dir :path ."))
      (is (= :ok (:status e))))
    (testing "and nothing it named ran: no server was added, no process started"
      (is (not (.exists marker)))
      (is (not (str/includes? (str (some-> (io/file dir ".brainyard/config.edn") (#(when (.exists %) (slurp %))))) "evil"))))
    (testing "the person sees what was refused"
      (is (some #(str/includes? (str (second %)) "⇠ refused over the session channel: /mcp add evil") @said)))))

(deftest an-answer-is-sent-only-once-its-turn-is-saved
  ;; byj-4ps: end-turn answered the channel BEFORE it wrote the snapshot, so a
  ;; leader that settled on the answer and ended the member at once — as a
  ;; closing or crashing leader does — resumed it without its last turn
  ;; (teams-test/a-resumed-leader-brings-its-team-back failed so, now and then)
  (let [dir (fresh-dir "saved-first")
        id "1790000000900-saved01"
        config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? true
                :session-id id :recap? false}
        ch (open dir)
        done? (atom false)
        read-input (fn [_] (loop []
                             (cond @done? nil
                                   :else (if-let [{:keys [text]} (channel/next-turn! ch)]
                                           text
                                           (do (Thread/sleep 10) (recur))))))
        asked (in-background
               (fn []
                 (let [a (channel/request! (:path ch) {:op :ask :question "remember this"} 20000)
                       ;; read the instant the answer arrives: what a leader
                       ;; that ends the member now would leave behind
                       on-disk (persist/read-session config id)]
                   (reset! done? true)
                   [a on-disk])))
        _ (session/run {:lm {:provider :mock :format :mock
                             :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "noted"}))}
                        :config config
                        :read-input read-input
                        :emit (fn [_ _])
                        :channel ch})
        [a on-disk] (deref asked 20000 [:hung])]
    (channel/close! ch)
    (is (= "noted" (:answer a)))
    (is (= ["remember this"] (map :question (:previous-turns on-disk)))
        "the turn is on disk before its answer is sent")))

(deftest every-live-session-is-locked
  (let [dir (fresh-dir "lock")
        config {:project-dir dir :working-dir dir :persist-session? true :session-id "fresh-1"}
        base (#'brainyard.main/resumed-base {:config config})]
    (testing "a fresh session is locked too, not only a resumed one: the lock is what says it is live"
      (is (some? base))
      (is (.isFile (io/file dir ".brainyard/sessions/fresh-1/lock.edn"))))
    (testing "and another live process holding it refuses the run"
      (spit (io/file dir ".brainyard/sessions/fresh-1/lock.edn") (pr-str {:pid 1 :at 0}))
      (is (nil? (#'brainyard.main/resumed-base {:config config}))))))

;; --- finding sessions, and asking one ---------------------------------------------------

(defn- live-channel
  "A channel for session `id` in `dir`, locked as a live session is, answered
  by a fake session that records what it was asked."
  [dir id seen answer-of]
  (let [config {:project-dir dir :working-dir dir :ask-timeout-ms 5000}
        _ (persist/lock! config id)
        ch (channel/open! config id {:describe (constantly {:provider "mock" :model "m"})})
        stop? (atom false)]
    (.start (Thread. #(loop []
                        (when-not @stop?
                          (if-let [t (channel/next-turn! ch)]
                            (do (swap! seen conj t)
                                (channel/turn-started! ch)
                                (channel/turn-ended! ch (answer-of (:text t))))
                            (Thread/sleep 10))
                          (recur)))))
    (assoc ch :stop! #(do (reset! stop? true) (channel/close! ch) (persist/unlock! config id)))))

(deftest a-session-finds-and-asks-its-peers
  (let [dir (fresh-dir "peers")
        seen (atom [])
        answer (fn [q] (cond-> {:answer (str "done: " q) :terminated-by :answer-channel}
                         (= "priced" q) (assoc :usage {:llm-calls 1 :cost 0.25 :input-tokens 100})))
        a (live-channel dir "1790000000001-aaaa1111" seen answer)
        b (live-channel dir "1790000000002-bbbb2222" seen answer)
        leader {:project-dir dir :working-dir dir :session-id "1790000000009-leader00" :ask-timeout-ms 5000}
        tools (into {} (map (juxt :name identity)) (channel/commands leader))
        call (fn [n args & [ctx]] ((:fn (tools n)) args (or ctx {})))]
    (try
      (testing "both are listed live, newest first"
        (is (= ["1790000000002-bbbb2222" "1790000000001-aaaa1111"]
               (map :session-id (:sessions (call "session$list" {})))))
        (is (every? :live? (:sessions (call "session$list" {})))))
      (testing "a session is named by its id or a prefix of it; an ambiguous one is refused"
        (is (= "1790000000001-aaaa1111" (:session-id (channel/find-session leader "1790000000001"))))
        (is (str/includes? (:error (channel/find-session leader "17900000000" "")) "names 2 live sessions"))
        (is (= "that is this session" (:error (channel/find-session (assoc leader :session-id "1790000000001-aaaa1111")
                                                                    "1790000000001-aaaa1111")))))
      (testing "session$ask waits for the peer's answer, and the peer knows who asked"
        (is (= {:status :ok :answer "done: summarise the log"}
               (select-keys (call "session$ask" {:session "1790000000001" :question "summarise the log"}) [:status :answer])))
        (is (= {:text "summarise the log" :from "1790000000009-leader00"} (last @seen))))
      (testing "what a peer spent answering is the asker's: in its session usage, and said as an event"
        (let [session-mem (atom {:usage {:llm-calls 1 :cost 0.5}})
              events (atom [])
              ctx {:parent-memory session-mem :st-memory (atom {}) :config leader
                   :on-event (fn [e d] (swap! events conj [e d]))}]
          (call "session$ask" {:session "1790000000001" :question "priced"} ctx)
          (is (= {:llm-calls 2 :cost 0.75 :input-tokens 100} (:usage @session-mem)))
          (is (= [[:session.member/spent {:session-id "1790000000001" :usage {:llm-calls 1 :cost 0.25 :input-tokens 100}
                                          :cost 0.25}]]
                 (filter #(= :session.member/spent (first %)) @events)))))
      (testing "session$send does not wait: the answer is a background task of the sender's"
        (let [reg (brainyard.tasks/registry)
              ctx {:task-registry reg :config leader}
              {:keys [task-id session-id]} (call "session$send" {:session "1790000000002" :question "run the tests"} ctx)]
          (is (= "1790000000002-bbbb2222" session-id))
          (let [t (loop [n 0] (let [t (brainyard.tasks/snapshot reg task-id)]
                                (if (or (not= :running (:status t)) (> n 500)) t (do (Thread/sleep 10) (recur (inc n))))))]
            (is (= :completed (:status t)))
            (is (= "done: run the tests" (get-in t [:result :answer]))))))
      (testing "status and an unknown peer"
        (is (= :idle (:state (call "session$status" {:session "1790000000001"}))))
        (is (= {:status :error :error "no live session \"nobody\" — session$list names the live ones"}
               (call "session$ask" {:session "nobody" :question "x"}))))
      (testing "a session that has closed is listed as not live, and cannot be asked"
        ((:stop! a))
        (is (= ["1790000000002-bbbb2222"] (map :session-id (:sessions (call "session$list" {})))))
        (is (= [false] (map :live? (filter #(= "1790000000001-aaaa1111" (:session-id %))
                                           (:sessions (call "session$list" {:all true})))))))
      (testing "the CLI asks by prefix and prints brainyard's JSON"
        (let [out (with-out-str (#'brainyard.main/attach-command
                                 {:attach "1790000000002" :questions ["one" "two"] :json true}
                                 {:project-dir dir :working-dir dir}))
              m (json/read-str out)]
          (is (= {:success true :session-id "1790000000002-bbbb2222" :provider "mock" :model "m" :answer "done: two"}
                 (select-keys m [:success :session-id :provider :model :answer])))
          (is (= ["one" "two"] (map :question (:turns m))))))
      (testing "the CLI lists them too"
        (is (str/includes? (with-out-str (#'brainyard.main/session-command {:questions ["list"] :live true}
                                                                           {:project-dir dir :working-dir dir}))
                           "1790000000002-bbbb2222")))
      (finally ((:stop! a)) ((:stop! b))))))

;; --- the headless host -------------------------------------------------------------------

(deftest a-headless-session-is-closed-over-its-channel
  (let [dir (fresh-dir "headless")
        closed (atom false)
        config {:project-dir dir :working-dir dir}
        _ (persist/lock! config "1790000000003-cccc3333")
        h (channel/open! config "1790000000003-cccc3333" {:close! #(reset! closed true) :label "tester"})
        tui (channel/open! config "1790000000004-dddd4444" {})]
    (try
      (testing "a headless session advertises :close-session and is found by its label"
        (is (= [:ask :inject :status :cancel :subscribe :config :emit :mcp :rename-session :close-session]
               (:ops (channel/read-meta config "1790000000003-cccc3333"))))
        (is (= "1790000000003-cccc3333" (:session-id (channel/find-session config "tester")))))
      (testing ":close-session ends a headless session"
        (is (= {:status :ok :closing "1790000000003-cccc3333"}
               (channel/request! (:path h) {:op :close-session} 5000)))
        (is (true? @closed)))
      (testing "and a session a person runs is theirs to close"
        (is (= {:status :error :error "this session is not headless: a person closes it"}
               (channel/request! (:path tui) {:op :close-session} 5000))))
      (finally (channel/close! h) (channel/close! tui) (persist/unlock! config "1790000000003-cccc3333")))))

(deftest a-headless-session-takes-an-agents-persona
  (let [dir (fresh-dir "persona")
        base {:config {:project-dir dir :working-dir dir :max-iterations 20}}
        explore (session/with-persona base "explore")]
    (testing "the spec's instruction and tool globs, as a subagent of it would have"
      (is (str/starts-with? (get-in explore [:config :agent-instruction]) "You are an EXPLORE agent."))
      (is (seq (get-in explore [:config :visible-tools]))))
    (testing "an agent that does not exist is no persona"
      (is (nil? (session/with-persona base "no-such-agent"))))
    (testing "the CLI's --agent, for run and ask as for --serve (byj-g5n.2), and none leaves the base"
      (is (= (:config explore) (:config (#'brainyard.main/with-persona base {:agent "explore"}))))
      (is (= base (#'brainyard.main/with-persona base {}))))))

(deftest a-member-asks-its-leaders-person
  (let [dir (fresh-dir "permission")
        config {:project-dir dir :working-dir dir}
        asked (atom [])
        leader-id "1790000000300-leader03"
        _ (persist/lock! config leader-id)
        leader (channel/open! config leader-id
                              {:permission-fn (fn [req] (swap! asked conj req)
                                                (if (= "read-file" (:tool req))
                                                  {:allowed true}
                                                  {:denied true :reason "denied by user"}))})
        member (assoc config :session-id "1790000000301-member01")
        ask (channel/forwarding-permission-fn member leader-id "team-x/tester")]
    (try
      (testing "the leader advertises that it answers prompts"
        (is (some #{:permission} (:ops (channel/read-meta config leader-id)))))
      (testing "the leader's person answers, and sees which member is asking"
        (is (= {:allowed true} (ask {:type :tool :tool "read-file" :display "read-file notes.md" :effects #{:fs/read}})))
        (is (= {:denied true :reason "denied by user"} (ask {:type :tool :tool "bash" :display "bash rm -rf x"})))
        (is (= ["⇠ team-x/tester: read-file notes.md" "⇠ team-x/tester: bash rm -rf x"] (map :display @asked)))
        (is (= #{:fs/read} (:effects (first @asked))) "the request arrives whole"))
      (testing "a leader that cannot ask is a refusal, not a hang"
        (let [nobody (channel/open! config "1790000000302-nobody00" {})
              _ (persist/lock! config "1790000000302-nobody00")
              ask2 (channel/forwarding-permission-fn member "1790000000302-nobody00" "t")]
          (try (is (= {:denied true :reason "the leader could not be asked: this session cannot ask a person for permission"}
                      (ask2 {:tool "bash" :display "bash ls"})))
               (finally (channel/close! nobody) (persist/unlock! config "1790000000302-nobody00")))))
      (testing "and so is a leader that is gone"
        (channel/close! leader)
        (is (str/starts-with? (:reason (ask {:tool "bash" :display "bash ls"})) "no leader to ask: no live session")))
      (finally (channel/close! leader) (persist/unlock! config leader-id)))))

;; --- :subscribe --------------------------------------------------------------------------

(deftest a-subscriber-sees-the-session-as-it-happens
  (let [dir (fresh-dir "subscribe")
        ch (open dir)]
    (try
      (testing "a subscriber that stops reading loses events past the bound, never the session"
        (let [r (channel/handle ch {:op :subscribe :events ["x/ev"]})
              [_ {:keys [box]}] (first @(:subscribers ch))]
          (is (= {:status :ok :subscribed [:x/ev]} (dissoc r :brainyard.unix-socket/stream)))
          (dotimes [i 600] (channel/publish! ch :x/ev {:i i}))
          (channel/publish! ch :y/other {})
          (is (= 512 (count (:frames @box))))
          (is (= 88 (:dropped @box)))
          (reset! (:subscribers ch) {})))
      (testing "over the socket: an ack, then a frame per wanted event, anything unprintable as a string"
        (let [frames (atom [])
              {:keys [ack stop!]} (channel/subscribe! (:path ch) ["x/ev"] #(swap! frames conj %))]
          (is (= {:status :ok :subscribed [:x/ev]} ack))
          (channel/publish! ch :x/ev {:n 1 :f (fn [])})
          (channel/publish! ch :y/other {:n 2})
          (channel/publish! ch :x/ev {:n 3})
          (loop [i 0] (when (and (< (count @frames) 2) (< i 200)) (Thread/sleep 10) (recur (inc i))))
          (is (= [1 3] (map (comp :n :payload) @frames)))
          (is (every? #(= (:session-id ch) (:sid %)) @frames))
          (is (string? (get-in (first @frames) [:payload :f])))
          (testing "and going away unsubscribes"
            (stop!)
            (loop [i 0] (when (and (seq @(:subscribers ch)) (< i 200)) (Thread/sleep 10) (recur (inc i))))
            (is (empty? @(:subscribers ch))))))
      (finally (channel/close! ch)))))

(deftest a-subscriber-watches-a-real-session
  (let [dir (fresh-dir "watch")
        ch (open dir)
        frames (atom [])
        {:keys [stop!]} (channel/subscribe! (:path ch) ["session.turn/start" "session.turn/end"] #(swap! frames conj %))
        done? (atom false)
        read-input (fn [_] (loop [] (if @done? nil
                                        (if-let [{:keys [text]} (channel/next-turn! ch)] text
                                                (do (Thread/sleep 10) (recur))))))
        asked (in-background #(do (channel/request! (:path ch) {:op :ask :question "hello"} 20000)
                                  (Thread/sleep 200)
                                  (reset! done? true)))]
    (session/run {:lm {:provider :mock :format :mock
                       :responses (fn [_] (json/write-str {:reasoning "r" :tool-calls [] :answer "hi"}))}
                  :config {:working-dir dir :project-dir dir :max-iterations 3 :persist-session? false :recap? false}
                  :read-input read-input :emit (fn [_ _]) :channel ch})
    (deref asked 20000 nil)
    (stop!)
    (channel/close! ch)
    (is (= [:session.turn/start :session.turn/end] (map :event @frames)))
    (is (= "hello" (get-in (first @frames) [:payload :question])))))


;; --- brainyard's other ops ----------------------------------------------------------------

(deftest the-other-ops-reach-the-session
  (let [dir (fresh-dir "ops")
        ch (open dir)
        remembered (atom [])
        emitted (atom [])
        renamed (atom nil)]
    (try
      (testing "before a session lends its hooks, the ops that need one say so"
        (is (= {:status :error :error "no session to read the config of yet"} (channel/handle ch {:op :config}))))
      (channel/attach! ch {:config-rows (constantly [{:key :max-turns :value 50 :source :default}
                                                     {:key :ask-timeout-ms :value 9000 :source :file}])
                           :emit! (fn [e d] (swap! emitted conj [e d]))
                           :mcp-rows (constantly [{:server "linear" :state :running}])
                           :remember! (fn [slug content] (swap! remembered conj [slug content]) {:stored "ok"})
                           :rename! #(reset! renamed %)})
      (testing ":config — what differs from the defaults, or what a query matches"
        (is (= {:status :ok :overrides {:ask-timeout-ms {:value 9000 :source :file}} :total 2}
               (channel/request! (:path ch) {:op :config} 5000)))
        (is (= [:max-turns] (map :key (:matches (channel/request! (:path ch) {:op :config :query "turns"} 5000))))))
      (testing ":emit — on the session's bus"
        (is (= {:status :ok :emitted :team/hello :subscribers 0}
               (channel/request! (:path ch) {:op :emit :event :team/hello :payload {:x 1}} 5000)))
        (is (= [[:team/hello {:x 1}]] @emitted)))
      (testing ":mcp — its servers, read only"
        (is (= {:status :ok :servers [{:server "linear" :state :running}]} (channel/request! (:path ch) {:op :mcp} 5000)))
        (is (str/starts-with? (:error (channel/request! (:path ch) {:op :mcp :action :add} 5000)) "only :action :list")))
      (testing ":inject :as :memory — into its memory store"
        (is (= {:status :ok :injected :memory :slug "decision" :stored "ok"}
               (channel/request! (:path ch) {:op :inject :as :memory :slug "decision" :content "we chose sockets"} 5000)))
        (is (= [["decision" "we chose sockets"]] @remembered)))
      (testing ":rename-session — both surfaces"
        (is (= {:status :ok :label "reviewer"} (channel/request! (:path ch) {:op :rename-session :label "reviewer"} 5000)))
        (is (= "reviewer" @renamed))
        (is (= "reviewer" (:label (channel/read-meta (:config ch) (:session-id ch))))))
      (testing "an op with no counterpart here says why, not just that it is unknown"
        (is (str/starts-with? (:error (channel/request! (:path ch) {:op :fsm-status} 5000)) "not ported:"))
        (is (str/starts-with? (:error (channel/request! (:path ch) {:op :inject :as :artifact :content "x"} 5000))
                              "not ported:")))
      (finally (channel/close! ch)))))

(deftest a-headless-session-takes-an-agents-model-and-loop
  (let [dir (fresh-dir "persona-lm-loop")
        spec-dir (io/file dir ".brainyard" "agents" "fast")
        _ (.mkdirs (io/file spec-dir "predictors"))
        _ (spit (io/file spec-dir "agent.edn")
                (pr-str {:description "a quick one" :lm {:model "claude-haiku-4-5-20251001"} :loop "turn.edn"}))
        _ (spit (io/file spec-dir "instruction.md") "You are quick.")
        _ (spit (io/file spec-dir "turn.edn") (pr-str [:sequence {:id :fast/turn}]))
        base {:config {:project-dir dir :working-dir dir :max-iterations 20}
              :lm {:provider :anthropic :model "claude-sonnet-5"}}
        ids (fn [tree] (set (keep #(when (map? %) (:id %)) (tree-seq coll? seq tree))))]
    (testing "its model, by the rule a subagent of it gets — a model change keeps the caller's provider"
      (let [b (session/with-persona base "fast")]
        (is (= {:provider :anthropic :model "claude-haiku-4-5-20251001"} (select-keys (:lm b) [:provider :model])))
        (testing "and its loop as each turn's tree, inside the session loop"
          (is (contains? (ids (:tree b)) :fast/turn))
          (is (contains? (ids (:tree b)) :session/turn))
          (is (not (contains? (ids (:tree b)) :react/turn))))
        (testing "with its own predictors searched first"
          (is (= (.getPath (io/file spec-dir "predictors")) (first (get-in b [:config :predictor-roots])))))))
    (testing "-p/-m on the command line and --loop win, as flags do"
      (let [b (session/with-persona (assoc base :lm-flagged? true) "fast" {:loop "mine.edn"})]
        (is (= "claude-sonnet-5" (:model (:lm b))))
        (is (nil? (:tree b)))))))

(deftest a-session-can-start-another-where-its-front-end-can
  ;; byj-gly.386: session$start, the tool /handover starts a new session with
  (let [tool (fn [cmds] (some #(when (= "session$start" (:name %)) %) cmds))
        started (atom [])
        cmds (channel/commands {} {:start-session (fn [opts] (swap! started conj opts) {:session-id "s-2"})})]
    (testing "offered only where the front end can start a session"
      (is (nil? (tool (channel/commands {}))))
      (is (tool cmds)))
    (testing "the question is the new session's first turn, and its id comes back"
      (is (= {:session-id "s-2"} ((:fn (tool cmds)) {:question "carry on with the parser"} {})))
      (is (= [{:task "carry on with the parser"}] @started)))
    (testing "a blank question starts nothing, and a failure is said, not thrown"
      (is (:error ((:fn (tool cmds)) {:question " "} {})))
      (is (= 1 (count @started)))
      (is (= {:error "the session did not start: no room"}
             ((:fn (tool (channel/commands {} {:start-session (fn [_] (throw (ex-info "no room" {})))})))
              {:question "x"} {}))))
    (testing "a session's ctx registers it from its front end's seam"
      (let [dir (.getCanonicalPath (doto (io/file "target/channel-test/start-session") .mkdirs))
            ctx ((requiring-resolve 'brainyard.agent/make-ctx)
                 {:lm {:provider :mock :format :mock :responses (fn [_] "{}")}
                  :config {:working-dir dir :project-dir dir}
                  :start-session (fn [_] {:session-id "s-3"})})
            bare ((requiring-resolve 'brainyard.agent/make-ctx)
                  {:lm {:provider :mock :format :mock :responses (fn [_] "{}")}
                   :config {:working-dir dir :project-dir dir}})]
        (is (contains? @(:tools ctx) "session$start"))
        (is (not (contains? @(:tools bare) "session$start")))))))
