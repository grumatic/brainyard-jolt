;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.share-test
  "The share sidecar's kernel side (RFC-043): notes for people, injected turns
  with their sender and the owner's approval, how both are recorded and
  counted, the permission gate's read-only events, the sidecar a session
  starts with --share, and what the person sees of a share."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.channel :as channel]
            [brainyard.init :as init]
            [brainyard.json :as json]
            [brainyard.main]
            [brainyard.persist :as persist]
            [brainyard.session :as session]
            [brainyard.share :as share]
            [brainyard.share-tools :as share-tools]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
            [brainyard.permission :as permission]
            [brainyard.tools :as tools]
            [brainyard.tasks :as tasks]
            [brainyard.trajectory :as trajectory]))

(defn- own-share-home
  "The suite's byj-share home (test-runner/run sets `brainyard.share-home`),
  given here too when the namespace runs alone, so no test reads the
  person's login and the relay it names (share/relay-of, byj-1wv.134);
  the property is cleared again after, as share/share-home reads an empty
  one as unset."
  [f]
  (if (not-empty (System/getProperty "brainyard.share-home"))
    (f)
    (do (System/setProperty "brainyard.share-home"
                            (.getCanonicalPath (doto (io/file "target/test-share-home") .mkdirs)))
        (try (f) (finally (System/setProperty "brainyard.share-home" ""))))))

(clojure.test/use-fixtures :once own-share-home)

(defn- fresh-dir [name]
  (let [d (io/file "target/share-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- in-background [f]
  (let [out (promise)]
    (.start (Thread. #(deliver out (try (f) (catch Throwable e {:thrown (ex-message e)})))))
    out))

(defn- open [dir & [config]]
  (channel/open! (merge {:project-dir dir :working-dir dir :ask-timeout-ms 5000} config)
                 (str "s-" (System/currentTimeMillis)) {:cancel! (constantly true)}))

(def kim {:user "usr_kim" :name "Kim" :role :coach})

(defn- mock-lm
  "A mock model that answers `answer` and keeps every message it was sent."
  [seen answer]
  {:provider :mock :format :mock
   :responses (fn [msgs]
                (swap! seen into (map :content msgs))
                (json/write-str {:reasoning "r" :tool-calls [] :answer answer}))})

(defn- channel-reader
  "A session's input: the channel's next request, as the front ends take it
  (`main/channel-turn!`, which says who sent it), until `done?`."
  [ch done? said]
  (fn [_] (loop []
            (if @done?
              nil
              (if-let [t (#'brainyard.main/channel-turn! ch #(swap! said conj %))]
                t
                (do (Thread/sleep 10) (recur)))))))

;; --- notes ---------------------------------------------------------------------------------

(deftest a-note-is-for-people-only
  (testing "a blank note, and one before a session lends the channel its hooks, are refused"
    (let [ch (open (fresh-dir "note-early"))]
      (try
        (is (= {:status :error :error "empty note"} (channel/handle ch {:op :inject :as :note :text "  "})))
        (is (= {:status :error :error "no session to note on yet"} (channel/handle ch {:op :inject :as :note :text "hi"})))
        (finally (channel/close! ch)))))
  (let [dir (fresh-dir "note")
        ch (open dir)
        seen (atom [])
        events (atom [])
        said (atom [])
        frames (atom [])
        {:keys [stop!]} (channel/subscribe! (:path ch) ["session.share/note"] #(swap! frames conj %))
        done? (atom false)
        note "look at the failing test before the parser"
        asked (in-background
               (fn []
                 ;; the session must be up and have lent its hooks first
                 (loop [i 0] (when (and (empty? @(:hooks ch)) (< i 500)) (Thread/sleep 10) (recur (inc i))))
                 (let [n (channel/request! (:path ch) {:op :inject :as :note :text note :from kim} 10000)
                       a (channel/request! (:path ch) {:op :ask :question "hello"} 20000)]
                   (Thread/sleep 100)
                   (reset! done? true)
                   [n a])))
        final (session/run {:lm (mock-lm seen "hi")
                            :config {:working-dir dir :project-dir dir :max-iterations 3 :recap? false
                                     :session-id (:session-id ch)}
                            :read-input (channel-reader ch done? said)
                            :emit (fn [_ _])
                            :on-event (fn [e d] (swap! events conj [e d]))
                            :channel ch})
        [n a] (deref asked 20000 [:hung])
        sid (:session-id ch)]
    (stop!)
    (channel/close! ch)
    (testing "the sender is told it was taken"
      (is (= {:status :ok :injected :note} n))
      (is (= :ok (:status a))))
    (testing "it is published, with who sent it, for :subscribe clients"
      (is (= [{:text note :from kim}] (map :payload @frames)))
      (is (some #(= [:session.share/note {:text note :from kim}] %) @events)))
    (testing "it never became a turn"
      (is (= ["hello"] (map :question (:previous-turns final)))))
    (testing "and never reached the model: no message it was sent holds it"
      (is (seq @seen))
      (is (not-any? #(str/includes? (str %) "failing test before the parser") @seen)))
    (testing "it is in the trajectory as a note, beside the turns and not among them"
      (is (= [{:text note :from kim :session sid}]
             (map #(select-keys % [:text :from :session]) (trajectory/read-notes {:project-dir dir} sid))))
      (is (= ["hello"] (map :question (trajectory/read-trajectories {:project-dir dir} sid)))))))

;; --- injected turns ---------------------------------------------------------------------------

(deftest an-injected-turn-asks-its-owner
  (let [ch (open (fresh-dir "approve") {:share-inject-approval-ms 300})
        asked (atom [])
        inject (fn [m] (channel/handle ch (merge {:op :inject :as :turn :await? false :from kim} m)))]
    (try
      (testing "before a session lends an approver, an owner's approval cannot be had"
        (is (= {:status :error :error "this session has no one to approve an injected turn"}
               (inject {:text "run the tests" :approval :owner}))))
      (channel/attach! ch {:approve! (fn [{:keys [text] :as req}]
                                       (swap! asked conj req)
                                       (case text
                                         "yes" :approved
                                         "no" :rejected
                                         "late" (do (Thread/sleep (:timeout-ms req)) :expired)
                                         "gone" nil))})
      (testing "the person is asked who wants what, with the configured time to answer"
        (is (= {:status :ok :injected :turn :queued true} (inject {:text "yes" :approval :owner})))
        (is (= {:text "yes" :from kim :who "Kim (coach)" :timeout-ms 300} (first @asked))))
      (testing "the turn carries its sender and how it was allowed"
        (is (= {:text "yes" :from kim :approval :owner} (channel/next-turn! ch)))
        (is (= {:user "usr_kim" :name "Kim" :role :coach :approval :owner} (channel/injected-by ch)))
        (channel/settle-unanswered! ch))
      (testing "a refusal, no answer in time, and nobody there are each said"
        (is (= {:status :error :error "rejected by the owner"} (inject {:text "no" :approval :owner})))
        (is (= {:status :error :error "approval expired"} (inject {:text "late" :approval :owner})))
        (is (= {:status :error :error "this session has no one to approve an injected turn"}
               (inject {:text "gone" :approval :owner})))
        (is (= 0 (count @(:queue ch))) "and nothing refused was queued"))
      (testing "class consent asks no one, and still marks the turn"
        (reset! asked [])
        (is (= {:status :ok :injected :turn :queued true} (inject {:text "consented" :approval :consent})))
        (is (empty? @asked))
        (channel/next-turn! ch)
        (is (= :consent (:approval (channel/injected-by ch))))
        (channel/settle-unanswered! ch))
      (testing "a session's id is still a sender, and an :ask is not an injection"
        (inject {:text "from a peer" :from "s-peer" :approval nil})
        (is (= "s-peer" (:from (channel/next-turn! ch))))
        (is (= {:session "s-peer"} (channel/injected-by ch)))
        (channel/settle-unanswered! ch)
        (let [waiting (in-background #(channel/handle ch {:op :ask :question "q" :from "s-peer"}))]
          (loop [i 0] (when (and (empty? @(:queue ch)) (< i 200)) (Thread/sleep 10) (recur (inc i))))
          (channel/next-turn! ch)
          (is (nil? (channel/injected-by ch)))
          (channel/settle-unanswered! ch)
          (deref waiting 5000 nil)))
      (testing "what is refused before anyone is asked"
        (reset! asked [])
        (is (= {:status :error :error "empty question"} (inject {:text " " :approval :owner})))
        (is (str/starts-with? (:error (inject {:text "x" :approval :someday})) "unknown :approval"))
        (is (empty? @asked)))
      (finally (channel/close! ch)))))

(deftest an-injected-turn-is-recorded-shown-and-counted-apart
  (let [dir (fresh-dir "injected")
        ch (open dir)
        seen (atom [])
        said (atom [])
        approvals (atom 0)
        starts (atom [])
        done? (atom false)
        asked (in-background
               (fn []
                 (loop [i 0] (when (and (empty? @(:hooks ch)) (< i 500)) (Thread/sleep 10) (recur (inc i))))
                 ;; watching the bus as a share's sidecar does
                 (channel/subscribe! (:path ch) [:session.turn/start] #(swap! starts conj (:payload %)))
                 (let [r [(channel/request! (:path ch) {:op :ask :question "owner's own question"} 20000)
                          (channel/request! (:path ch) {:op :inject :as :note :text "nice" :from kim} 10000)
                          (channel/request! (:path ch) {:op :inject :as :turn :text "add a test for the edge case"
                                                        :from kim :approval :owner} 20000)]]
                   (Thread/sleep 100)
                   (reset! done? true)
                   r)))
        _ (session/run {:lm (mock-lm seen "done")
                        :config {:working-dir dir :project-dir dir :max-iterations 3 :recap? false
                                 :session-id (:session-id ch)}
                        :read-input (channel-reader ch done? said)
                        :emit (fn [_ _])
                        :approve (fn [_] (swap! approvals inc) :approved)
                        :channel ch})
        [_ _ t] (deref asked 20000 [:hung])
        sid (:session-id ch)
        records (trajectory/read-trajectories {:project-dir dir} sid)]
    (channel/close! ch)
    (testing "the owner allowed it, and it ran as an ordinary turn"
      (is (= 1 @approvals))
      (is (= {:status :ok :injected :turn :answer "done"} t)))
    (testing "the person sees who put it there"
      (is (some #(= "⇠ injected by Kim (coach) · you allowed it" %) @said)))
    (testing "the turn's start says who injected it, for a share's sidecar"
      (is (= [nil {:user "usr_kim" :name "Kim" :role :coach :approval :owner}] (map :injected-by @starts))))
    ;; how an evaluator counts it is the evaluator's, read from the trajectory
    (testing "the trajectory records who injected it, and only that turn"
      (is (= [nil {:user "usr_kim" :name "Kim" :role :coach :approval :owner}] (map :injected-by records))))))

;; --- the inline session's approval (byj-1wv.3.12) ------------------------------------------------

(deftest the-inline-approver-is-answered-at-the-prompt
  (let [said (atom [])
        {:keys [approve answer!]} (share/inline-approver #(swap! said conj %))
        ask (fn [ms] (in-background #(approve {:who "Kim (coach)" :text "run the tests" :timeout-ms ms})))]
    (testing "nothing waiting"
      (is (= "nothing is waiting for your approval" (answer! true))))
    (testing "the request is said with how to answer, and an allow approves"
      (let [r (ask 5000)]
        (loop [i 0] (when (and (empty? @said) (< i 200)) (Thread/sleep 10) (recur (inc i))))
        (is (str/includes? (first @said) "Kim (coach) wants to run: run the tests"))
        (is (str/includes? (first @said) "/share allow or /share refuse"))
        (is (= "allowed — it runs as your next turn" (answer! true)))
        (is (= :approved (deref r 2000 :hung)))))
    (testing "a refuse rejects"
      (let [r (ask 5000)]
        (Thread/sleep 100)
        (answer! false)
        (is (= :rejected (deref r 2000 :hung)))))
    (testing "nobody answering is a refusal at the deadline, and said"
      (reset! said [])
      (is (= :expired (deref (ask 150) 3000 :hung)))
      (is (some #(str/includes? % "nobody answered in time") @said))
      (is (= "nothing is waiting for your approval" (answer! true)) "and nothing is left waiting"))))

(deftest an-inline-session-asks-and-the-person-allows-with-share-allow
  (let [dir (fresh-dir "inline-approve")
        ch (open dir)
        said (atom [])
        {:keys [approve answer!]} (share/inline-approver #(swap! said conj %))
        done? (atom false)
        typed? (atom false)
        asked (in-background
               (fn []
                 (loop [i 0] (when (and (empty? @(:hooks ch)) (< i 500)) (Thread/sleep 10) (recur (inc i))))
                 (let [r (channel/request! (:path ch) {:op :inject :as :turn :text "add the missing test"
                                                       :from kim :approval :owner} 20000)]
                   (Thread/sleep 100)
                   (reset! done? true)
                   r)))
        turns (channel-reader ch done? said)
        ;; the person, at the prompt: answers once the request is said
        read-input (fn [p]
                     (if (and (not @typed?) (some #(str/includes? % "wants to run") @said))
                       (do (reset! typed? true) "/share allow")
                       (if (and (not @typed?) (not @done?))
                         (do (Thread/sleep 20) (recur p))
                         (turns p))))
        infos (atom [])]
    (session/run {:lm (mock-lm (atom []) "done")
                  :config {:working-dir dir :project-dir dir :max-iterations 3 :recap? false
                           :session-id (:session-id ch)}
                  :read-input read-input
                  :emit (fn [kind text] (when (= :info kind) (swap! infos conj text)))
                  :approve approve
                  :approve-answer answer!
                  :channel ch})
    (channel/close! ch)
    (is (= {:status :ok :injected :turn :answer "done"} (deref asked 20000 :hung)))
    (is (some #(= "allowed — it runs as your next turn" %) @infos) "/share allow answered it")))

;; --- any session shared by reference, from the agent page or /share start (RFC-043) --------------

(deftest a-session-is-shared-and-unshared-by-its-reference
  ;; byj-1wv.3.14, byj-1wv.8.4: one byj-share run per session, named by its
  ;; reference, ending with this process (--parent); the links come from the
  ;; run's ready line
  (let [dir (fresh-dir "sharer")
        bin (io/file dir "bin")
        argv-file (io/file dir "argv.txt")
        _ (.mkdirs bin)
        fake (io/file bin "byj-share")
        _ (spit fake (str "#!/bin/sh\necho \"$@\" > " (.getPath argv-file) "\n"
                          "echo '{\"share\":\"shr_x\",\"session\":\"claude:cc-1\",\"links\":{\"share\":\"http://relay/s/shr_x\",\"coach\":\"http://relay/i/inv_c\",\"spectator\":\"http://relay/i/inv_w\"}}'\n"
                          "echo 'sharing shr_x' >&2\n"
                          "exec sleep 30\n"))
        _ (.setExecutable fake true)
        notes (atom [])
        pid (persist/own-pid)
        {:keys [toggle! shared? stop-all!]} (share/sharer {:share-sidecar (.getPath fake) :share-relay-url "http://relay"}
                                                          {:log-dir dir})]
    (try
      (testing "starting says so, and the links follow once the share is open"
        (is (= "sharing live-cc — opening the share…" (toggle! "claude:cc-1" "live-cc" #(swap! notes conj %))))
        ;; the wait ends with the note; its bound survives six suites' load,
        ;; where 5 s once did not (byj-gly.433)
        (loop [i 0] (when (and (empty? @notes) (< i 600)) (Thread/sleep 50) (recur (inc i))))
        (is (= ["sharing live-cc at http://relay/s/shr_x · coach http://relay/i/inv_c · watch http://relay/i/inv_w"] @notes))
        (is (shared? "claude:cc-1"))
        (is (= (str "run --session claude:cc-1 --parent " pid " --relay http://relay --title live-cc") (str/trim (slurp argv-file)))))
      (testing "again on the same row stops it"
        (is (= "stopped sharing live-cc" (toggle! "claude:cc-1" "live-cc" identity)))
        (is (not (shared? "claude:cc-1"))))
      (testing "a byj session, with what /share start asks for"
        (toggle! "byj:1790-a" "byj:1790-a" identity {:target "class:CS101" :record? true :dir "/p"})
        (loop [i 0] (when (and (not (str/includes? (str (try (slurp argv-file) (catch Throwable _ ""))) "byj:1790-a")) (< i 600))
                      (Thread/sleep 50) (recur (inc i))))
        (is (= (str "run --session byj:1790-a --parent " pid " --dir /p --relay http://relay --title byj:1790-a --target class:CS101 --record")
               (str/trim (slurp argv-file)))))
      (testing "stop-all! ends whatever is still shared"
        (is (shared? "byj:1790-a"))
        (stop-all!)
        (Thread/sleep 200)
        (is (not (shared? "byj:1790-a"))))
      (testing "no sidecar: said plainly"
        (is (str/starts-with? ((:toggle! (share/sharer {:share-sidecar "/nonexistent/byj-share"})) "claude:cc-3" "x" identity)
                              "not sharing: /nonexistent/byj-share is not on PATH")))
      (finally (stop-all!)))))

(deftest a-terminal-or-an-agent-is-shared-with-its-options
  ;; byj-gly.362.2: the sharer passes a terminal's --no-input and
  ;; --chat=false, and an agent's --max-messages and --max-cost
  (is (= ["b" "run" "--session" "term:work" "--parent" "7" "--title" "work" "--no-input" "--chat=false"]
         (share/ref-argv {:binary "b" :ref "term:work" :parent 7 :title "work" :no-input? true :chat? false})))
  (is (= ["b" "run" "--session" "term:work" "--title" "work"]
         (share/ref-argv {:binary "b" :ref "term:work" :title "work" :chat? true}))
      "chat on is the default, and not said")
  (is (= ["b" "run" "--session" "agent:default" "--max-messages" "50" "--max-cost" "2.5"]
         (share/ref-argv {:binary "b" :ref "agent:default" :max-messages 50 :max-cost 2.5}))))

(deftest the-sessions-agent-shares-through-share-commands
  ;; byj-gly.362.2: share$status, share$ls, share$start and share$control
  ;; are the operations /share runs; share$start says the share's links once
  ;; the relay has it open, publishes, and so is asked about by default; a
  ;; subagent has none of them
  (let [dir (fresh-dir "share-cmds")
        bin (io/file dir "bin")
        argv-file (io/file dir "argv.txt")
        _ (.mkdirs bin)
        fake (io/file bin "byj-share")
        _ (spit fake (str "#!/bin/sh\n"
                          "if [ \"$1\" = ls ]; then echo \"claude:cc-9  a Claude Code session ($*)\"; exit 0; fi\n"
                          "echo \"$@\" > " (.getPath argv-file) "\n"
                          "echo '{\"share\":\"shr_t\",\"session\":\"term:work\",\"links\":{\"share\":\"http://relay/s/shr_t\",\"coach\":\"http://relay/i/inv_c\",\"spectator\":\"http://relay/i/inv_w\"}}'\n"
                          "exec sleep 30\n"))
        _ (.setExecutable fake true)
        config {:working-dir dir :project-dir dir :share-sidecar (.getPath fake) :share-relay-url "http://relay"
                :session-id "s-share-cmds"}
        ;; approved here: what the default asks is checked below
        ctx (agent/make-ctx {:config (assoc config :permission-mode :auto-approve) :lm {:provider :mock :format :mock}})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))]
    (try
      (is (every? #(contains? @(:tools ctx) %) ["share$status" "share$ls" "share$start" "share$control"]))
      (is (str/starts-with? (:status (call "share$status" {})) "not sharing"))
      (is (str/includes? (:sessions (call "share$ls" {})) (str "ls --dir " dir)) "byj-share ls, in this project")
      (let [r (:result (call "share$start" {:refs ["term:work"] :no-input true}))]
        (is (str/includes? r "sharing term:work at http://relay/s/shr_t · coach http://relay/i/inv_c · watch http://relay/i/inv_w")
            "the links, once the relay has it open")
        (is (str/includes? (slurp argv-file) "--session term:work") )
        (is (str/includes? (slurp argv-file) "--no-input")))
      (is (= "stopped sharing term:work" (:result (call "share$control" {:action "stop" :ref "term:work"}))))
      (is (:error (call "share$control" {:action "kick" :ref "term:work" :user "x"})) "kick is this session's only")
      (testing "share$start publishes, and the default config asks about it"
        (is (contains? (:effects (get @(:tools ctx) "share$start")) :share/publish))
        (let [asking (agent/make-ctx {:config (assoc config :permission-mode :ask-each-time)
                                      :lm {:provider :mock :format :mock}})]
          (is (contains? (:tool-ask-effects config/defaults) :share/publish))
          (is (= :ask (:verdict (permission/tool-verdict "share$start" asking))))
          (is (str/includes? (str (:error (tools/call-tool (get @(:tools asking) "share$start") {} asking))) "refused (permission)")
              "headless, it is refused rather than run")
          (is (= :allow (:verdict (permission/tool-verdict "share$status" asking))))))
      (testing "a subagent shares nothing: a share is the session's"
        (is (not-any? #(str/starts-with? % "share$")
                      (keys @(:tools (agent/make-ctx {:config (assoc config :agent-instance-id "explore/x-1")
                                                      :lm {:provider :mock :format :mock}}))))))
      (finally ((:stop-all! (share/process-sharer config)))))))

(deftest several-sessions-are-shared-as-one-group
  ;; RFC-043 (byj-1wv.8.5): byj-share start with this process as
  ;; the parent, and what it printed said back as lines
  (is (= ["byj-share" "start" "--json" "--parent" "42" "--group" "team t1" "--target" "link" "--record" "byj:a" "byj:b"]
         (share/group-argv {:binary "byj-share" :refs ["byj:a" "byj:b"] :parent "42" :title "team t1" :target "link" :record? true})))
  (is (= ["byj-share" "start" "--json" "--parent" "42" "claude:c"]
         (share/group-argv {:binary "byj-share" :refs ["claude:c"] :parent "42"})))
  (is (= ["sharing team/alice at http://r/s/shr_a" "sharing byj:b at http://r/s/shr_b"
          "as one group: http://r/g/grp_1" "  watch link: http://r/i/w"]
         (share/group-report (str "{\"share\":\"shr_a\",\"ref\":\"byj:a\",\"label\":\"team/alice\",\"links\":{\"share\":\"http://r/s/shr_a\"}}\n"
                                  "{\"share\":\"shr_b\",\"ref\":\"byj:b\",\"links\":{\"share\":\"http://r/s/shr_b\"}}\n"
                                  "{\"group\":\"grp_1\",\"url\":\"http://r/g/grp_1\",\"spectator\":\"http://r/i/w\"}\n")))))

(deftest the-ready-line-and-the-older-stderr-both-give-the-links
  (is (= {:share "shr_x" :page "http://r/s/shr_x" :coach nil :spectator "http://r/i/w"}
         (share/ready-in (str "noise\n{\"share\":\"shr_x\",\"links\":{\"share\":\"http://r/s/shr_x\",\"spectator\":\"http://r/i/w\"}}\n"))))
  (is (nil? (share/ready-in "sharing shr_x\n")))
  (is (= {:page "http://r/s/x" :coach nil :spectator "http://r/i/w"}
         (share/links-in "sharing x\n  page:       http://r/s/x\n  watch link: http://r/i/w\n"))))

(deftest the-registry-lists-running-shares-only
  ;; byj-share's registry (RFC-043 model 6): what /share lists of the other
  ;; shares this process started
  (let [home (fresh-dir "registry")
        active (io/file home "active")
        put (fn [share pid parent]
              (spit (io/file active (str share ".json"))
                    (json/write-str {:share share :ref (str "byj:" share) :pid pid :parent parent
                                     :links {:share (str "http://r/s/" share)} :started "2026-10-11T00:00:00Z"})))]
    (.mkdirs active)
    (put "shr_live" (parse-long (persist/own-pid)) 4242)
    (put "shr_gone" 999999 4242)
    (spit (io/file active "notes.txt") "not an entry")
    (is (= ["shr_live"] (map :share (share/active home))))
    (is (= "http://r/s/shr_live" (get-in (first (share/active home)) [:links :share])))
    (testing "from-here: the live ones this process is the parent of (byj-gly.428)"
      (put "shr_mine" (parse-long (persist/own-pid)) (parse-long (persist/own-pid)))
      (is (= ["shr_mine"] (map :share (share/from-here {:home home}))))
      (is (= ["shr_live"] (map :share (share/from-here {:home home :pid 4242})))))))

(deftest share-start-team-is-the-leader-and-its-open-members
  (let [dir (fresh-dir "team-refs")
        config {:project-dir dir :session-id "lead-1"}]
    (.mkdirs (io/file dir ".brainyard" "teams" "t1"))
    (spit (io/file dir ".brainyard" "teams" "t1" "team.edn")
          (pr-str {:id "t1" :leader "lead-1" :created-at 1
                   :members [{:name "alice" :session-id "m-a"} {:name "bob" :session-id "m-b" :closed? true}]}))
    (is (= ["byj:lead-1" "byj:m-a" "claude:cc-1"]
           (share-tools/refs config ["team" "claude:cc-1"])))
    (is (= ["x"] (share-tools/refs (assoc config :session-id "nobody") ["team" "x"]))
        "no team led: team names nobody")))

;; --- the sidecar ---------------------------------------------------------------------------------

(deftest the-sidecar-runs-as-the-sessions-task
  (let [dir (fresh-dir "sidecar")
        bin (io/file dir "bin")
        out (io/file dir "argv.txt")
        _ (.mkdirs bin)
        fake (io/file bin "byj-share")
        _ (spit fake (str "#!/bin/sh\necho \"$@\" > " (.getPath out) "\nexec sleep 30\n"))
        _ (.setExecutable fake true)
        reg (tasks/registry)
        config {:session-id "s-1" :working-dir dir :share-relay-url "https://relay.example"}
        ctx {:config config :task-registry reg}]
    (testing "a recorded session's sidecar is told to record (byj-1wv.5.6)"
      (is (= ["byj-share" "run" "--socket" "/tmp/x.sock" "--target" "link" "--session" "s-1" "--record"]
             (share/argv {:binary "byj-share" :socket "/tmp/x.sock" :target "link" :session-id "s-1" :record? true}))))
    (testing "the targets it knows"
      (is (nil? (share/target-problem "link")))
      (is (nil? (share/target-problem "class:CS101")))
      (testing "a class assignment, class:CODE/KEY, passes through as typed (RFC-043 model 19)"
        (is (nil? (share/target-problem "class:CS101/a1")))
        (is (nil? (share/target-problem "class:CS101/week-3")))
        (is (some? (share/target-problem "class:CS101/A1")))
        (is (some? (share/target-problem "class:CS101/")))
        (is (= ["byj-share" "run" "--socket" "/tmp/x.sock" "--target" "class:CS101/a1" "--session" "s-1"]
               (share/argv {:binary "byj-share" :socket "/tmp/x.sock" :target "class:CS101/a1" :session-id "s-1"}))))
      (is (some? (share/target-problem "")))
      (is (some? (share/target-problem "everyone"))))
    (testing "an ended share's id reopens it under that id (RFC-043 \"Resuming a share\", byj-1wv.12)"
      (is (nil? (share/target-problem "shr_abcdefghijklmnop")))
      (is (some? (share/target-problem "shr_short")))
      (is (= ["byj-share" "run" "--socket" "/tmp/x.sock" "--target" "link" "--session" "s-1" "--share-id" "shr_abcdefghijklmnop"]
             (share/argv {:binary "byj-share" :socket "/tmp/x.sock" :target "shr_abcdefghijklmnop" :session-id "s-1"})))
      (is (nil? (share/reopen-id "link"))))
    (testing "a sidecar that is not there is said plainly, and nothing starts"
      (let [r (share/start! ctx {:target "link" :socket "/tmp/x.sock" :path (.getPath (io/file dir "nowhere"))})]
        (is (str/includes? (:error r) "byj-share is not on PATH"))
        (is (empty? @reg))))
    (testing "it is started with its argv, as a quiet background task of the session"
      (let [r (share/start! ctx {:target "link" :socket "/tmp/x.sock" :title "Parser work" :path (.getPath bin)})]
        (is (= [(.getPath fake) "run" "--socket" "/tmp/x.sock" "--target" "link" "--session" "s-1"
                "--parent" (persist/own-pid) "--relay" "https://relay.example" "--title" "Parser work"]
               (:argv r)))
        (loop [i 0] (when (and (not (.isFile out)) (< i 300)) (Thread/sleep 10) (recur (inc i))))
        (is (= (str "run --socket /tmp/x.sock --target link --session s-1 --parent " (persist/own-pid)
                    " --relay https://relay.example --title Parser work")
               (str/trim (slurp out))))
        (is (= (:task-id r) (share/running-task ctx)))
        (is (true? (:quiet? (get @reg (:task-id r)))))
        (testing "a second is refused while it runs"
          (is (= "already sharing" (:error (share/start! ctx {:target "link" :socket "/tmp/x.sock" :path (.getPath bin)})))))
        (testing "stopping it ends its task"
          (is (true? (share/stop! ctx)))
          (tasks/wait reg (:task-id r) 5000)
          (is (nil? (share/running-task ctx))))))
    (testing "a session with nothing to reach is refused"
      (is (str/includes? (:error (share/start! ctx {:target "link" :socket nil :path (.getPath bin)})) "no channel")))
    (testing "a session given :share starts it on its own channel, says so, and ends it as it closes"
      (.delete out)
      (let [ch (open dir)
            said (atom [])
            reg (tasks/registry)
            n (atom 0)]
        (session/run {:lm (mock-lm (atom []) "x")
                      :config {:working-dir dir :project-dir dir :persist-session? false :recap? false
                               :session-id (:session-id ch) :share-sidecar (.getPath fake)}
                      :read-input (fn [_] (when (< (swap! n inc) 2)
                                            (loop [i 0] (when (and (not (.isFile out)) (< i 300)) (Thread/sleep 10) (recur (inc i))))
                                            "/share"))
                      :emit (fn [k t] (swap! said conj [k t]))
                      :task-registry reg
                      :share "link"
                      :channel ch})
        (channel/close! ch)
        (is (some #(= [:info "sharing this session (link) through the sidecar's own relay — its URL follows when the relay answers"] %) @said)
            "the relay is named before it answers: none set and no login, the sidecar's own")
        (is (str/includes? (slurp out) (str "--socket " (:path ch) " --target link --session " (:session-id ch))))
        (is (not (str/includes? (slurp out) "--relay")) "byj names no relay of its own (byj-ahf.7)")
        (is (some #(str/includes? (str (second %)) "the sidecar is starting") @said) "/share before anyone joined")
        (is (nil? (share/running-task {:task-registry reg})) "closing the session ended the sidecar")))))

(deftest a-sidecar-that-ends-at-once-says-why
  ;; byj-gly.362.4: a sidecar that ends by itself — here, logged in to
  ;; another relay — is said, with its last lines: share$start's wait returns
  ;; it rather than waiting out a URL that cannot come, the session tells the
  ;; person, and /share says it after; a share that was stopped says nothing
  (let [dir (fresh-dir "sidecar-ends")
        bin (io/file dir "bin")
        _ (.mkdirs bin)
        failing (io/file bin "byj-share")
        why "byj-share: logged in to http://127.0.0.1:8788, not https://relay.example: run byj-share login --relay https://relay.example"
        _ (spit failing (str "#!/bin/sh\necho 'connecting' \necho '" why "' >&2\nexit 1\n"))
        _ (.setExecutable failing true)
        events (atom [])
        reg (tasks/registry)
        config {:session-id "s-ends" :working-dir dir :project-dir dir :share-sidecar (.getPath failing)
                :share-relay-url "https://relay.example"}
        ctx {:config config :task-registry reg :share-box (atom nil)
             :on-event (fn [e d] (swap! events conj [e d]))}
        t0 (System/currentTimeMillis)
        r (share-tools/start ctx {:wait-ms 10000} identity)]
    (is (= (str "not sharing: the sidecar ended at once — connecting · " why) r))
    (is (< (- (System/currentTimeMillis) t0) 5000) "without waiting out the 10 s")
    (is (= [why] (keep (fn [[e d]] (when (= :session.share/ended e) (last (str/split (:why d) #" · ")))) @events)))
    (is (str/includes? (share-tools/status ctx) (str "the last share of this session ended: connecting · " why)))
    (testing "a stopped share is not one that ended by itself"
      (let [sleeper (io/file bin "byj-share-sleeps")
            _ (spit sleeper "#!/bin/sh\nexec sleep 30\n")
            _ (.setExecutable sleeper true)
            reg2 (tasks/registry)
            events2 (atom [])
            ctx2 {:config (assoc config :share-sidecar (.getPath sleeper)) :task-registry reg2 :share-box (atom nil)
                  :on-event (fn [e d] (swap! events2 conj [e d]))}
            r2 (share/start! ctx2 {:target "link" :socket "/tmp/x.sock"})]
        (Thread/sleep 200)
        (is (true? (share/stop! ctx2)))
        (tasks/wait reg2 (:task-id r2) 5000)
        (Thread/sleep 100)
        (is (not-any? #(= :session.share/ended (first %)) @events2))
        (is (nil? (share/last-ended ctx2)))))
    (testing "a session started with --share tells the person"
      (let [ch (open dir)
            said (atom [])
            n (atom 0)]
        (session/run {:lm (mock-lm (atom []) "x")
                      :config {:working-dir dir :project-dir dir :persist-session? false :recap? false
                               :session-id (:session-id ch) :share-sidecar (.getPath failing)}
                      :read-input (fn [_] (when (< (swap! n inc) 2)
                                            (loop [i 0] (when (and (not-any? #(str/starts-with? (str (second %)) "sharing ended") @said) (< i 300))
                                                          (Thread/sleep 10) (recur (inc i))))
                                            "/share"))
                      :emit (fn [k t] (swap! said conj [k t]))
                      :task-registry (tasks/registry)
                      :share "link"
                      :channel ch})
        (channel/close! ch)
        (is (some #(= [:info (str "sharing ended: connecting · " why)] %) @said))
        (is (some #(and (= :info (first %)) (str/includes? (str (second %)) (str "the last share of this session ended: connecting · " why))) @said)
            "and /share says it after")))))

(deftest the-sessions-agent-logs-byj-share-in
  ;; byj-gly.362.5: share$login runs byj-share login for this session's relay
  ;; in the background and returns its link and code as soon as they are
  ;; printed — a bash call showed nothing for 120 s; the session says when it
  ;; is done; a second call while it waits is the same login
  (let [dir (fresh-dir "share-login")
        bin (io/file dir "bin")
        _ (.mkdirs bin)
        fake (io/file bin "byj-share")
        argv-file (io/file dir "argv.txt")
        ;; the approval comes when the test says, not after a second: a bound
        ;; in milliseconds broke under six shards' load (byj-gly.407)
        approved (io/file dir "approved")
        _ (spit fake (str "#!/bin/sh\necho \"$@\" > " (.getPath argv-file) "\n"
                          "echo 'Open https://relay.example/device?code=ABCD-1234'\n"
                          "echo 'and enter the code ABCD-1234 to let this machine share sessions.'\n"
                          "while [ ! -f " (.getPath approved) " ]; do sleep 0.05; done\n"
                          "echo 'Logged in as kim@example.com on https://relay.example.'\n"))
        _ (.setExecutable fake true)
        events (atom [])
        reg (tasks/registry)
        ctx {:config {:session-id "s-login" :working-dir dir :project-dir dir :share-sidecar (.getPath fake)
                      :share-relay-url "https://relay.example"}
             :task-registry reg :share-box (atom nil)
             :on-event (fn [e d] (swap! events conj [e d]))}
        r (share-tools/login ctx 10000)
        ended #(seq (filter (fn [[e]] (= :session.share/login-ended e)) @events))]
    (is (and (:approve r) (not (ended))) "back with the code, while the approval is still to come")
    (is (= "https://relay.example" (:relay r)))
    (is (= "Open https://relay.example/device?code=ABCD-1234\nand enter the code ABCD-1234 to let this machine share sessions." (:approve r)))
    (is (str/includes? (:next r) (str "task$wait on " (:task-id r))))
    (is (= (:task-id r) (:task-id (share/login! ctx "https://relay.example"))) "a second while it waits is the same login")
    (spit approved "")
    (tasks/wait reg (:task-id r) 5000)
    (Thread/sleep 100)
    (is (= "login --relay https://relay.example" (str/trim (slurp argv-file))) "logged in to this session's relay")
    (is (= [{:ok true :why "and enter the code ABCD-1234 to let this machine share sessions. · Logged in as kim@example.com on https://relay.example."}]
           (keep (fn [[e d]] (when (= :session.share/login-ended e) (select-keys d [:ok :why]))) @events)))
    (testing "a login that fails says why"
      (let [failing (io/file bin "byj-share-fails")
            _ (spit failing "#!/bin/sh\necho 'byj-share: could not reach https://relay.example' >&2\nexit 1\n")
            _ (.setExecutable failing true)]
        (is (= {:error "byj-share login ended: byj-share: could not reach https://relay.example"}
               (share-tools/login (assoc-in (assoc ctx :task-registry (tasks/registry)) [:config :share-sidecar] (.getPath failing)) 5000)))))
    (testing "the relays side by side (byj-gly.362.6)"
      (let [home (io/file dir "home")
            cfg (assoc ctx :share-home (.getPath home))]
        (is (str/includes? (share-tools/relays cfg) "byj-share is not logged in (share$login logs it in)"))
        (.mkdirs home)
        (spit (io/file home "credentials") (json/write-str {:relay "http://127.0.0.1:8788" :device_token "t" :user {:email "kim@example.com"}}))
        (is (= (str "this session shares through https://relay.example; byj-share is logged in to http://127.0.0.1:8788 as kim@example.com"
                    " — another relay: if a share is refused, log in to https://relay.example (share$login)")
               (share-tools/relays cfg)))
        (spit (io/file home "credentials") (json/write-str {:relay "https://relay.example/" :device_token "t" :user {:email "kim@example.com"}}))
        (is (= "this session shares through https://relay.example, where byj-share is logged in as kim@example.com"
               (share-tools/relays cfg)))
        (is (str/includes? (share-tools/status cfg) "where byj-share is logged in as kim@example.com") "/share says it when nothing is shared")))))

(deftest a-session-shares-through-the-relay-byj-share-is-logged-in-to
  ;; byj-1wv.134: a class's student logged in to their class's relay ran the
  ;; command their class page gives, and the sidecar was handed
  ;; the default relay, refused, and told to log in to production
  (let [home (io/file (fresh-dir "share-relay") "home")]
    (is (nil? (share/relay-of {} (.getPath home))) "no login: none, so the sidecar dials its own default (byj-ahf.7)")
    (.mkdirs home)
    (spit (io/file home "credentials") (json/write-str {:relay "http://localhost:8787" :device_token "t" :user {:email "kim@class.test"}}))
    (is (= "http://localhost:8787" (share/relay-of {} (.getPath home))) "logged in: that relay")
    (is (= "https://relay.example" (share/relay-of {:share-relay-url "https://relay.example"} (.getPath home))) "set: what is set, whatever the login")
    (testing "a login with no token is no login"
      (spit (io/file home "credentials") (json/write-str {:relay "http://localhost:8787" :device_token ""}))
      (is (nil? (share/relay-of {} (.getPath home)))))
    (testing "the suite reads a byj-share home of its own, never the person's"
      (is (str/ends-with? (share/share-home) "target/test-share-home")))))

(deftest a-running-shares-recording-is-turned-on-and-off
  ;; byj-gly.362.7: share$record, as the owner console's checkbox — this
  ;; session's share through its bus (session-test covers /share record),
  ;; another through byj-share record on|off REF; it publishes, so it asks
  (let [dir (fresh-dir "share-record")
        bin (io/file dir "bin")
        _ (.mkdirs bin)
        fake (io/file bin "byj-share")
        _ (spit fake "#!/bin/sh\necho \"byj-share $@\"\n")
        _ (.setExecutable fake true)
        config {:working-dir dir :project-dir dir :share-sidecar (.getPath fake) :session-id "s-rec"}
        ctx (agent/make-ctx {:config (assoc config :permission-mode :auto-approve) :lm {:provider :mock :format :mock}})
        call (fn [args] (tools/call-tool (get @(:tools ctx) "share$record") args ctx))]
    (is (= {:result "byj-share record on term:work"} (call {:on true :ref "term:work"})))
    (is (= {:result "byj-share record off claude:cc-1"} (call {:on false :ref "claude:cc-1"})))
    (is (str/starts-with? (:result (call {:on true})) "not sharing") "this session, not shared: says so")
    (let [events (atom [])
          shared {:config config :share-box (atom {:share "shr_1"}) :on-event (fn [e d] (swap! events conj [e d]))}]
      (is (str/starts-with? (share-tools/record! shared true nil) "recording this share"))
      (is (str/starts-with? (share-tools/record! shared false nil) "recording stopped"))
      (is (= [{:action :record-on} {:action :record-off}] (keep (fn [[e d]] (when (= :session.share/control e) d)) @events))))
    (is (= :ask (:verdict (permission/tool-verdict "share$record" (agent/make-ctx {:config (assoc config :permission-mode :ask-each-time)
                                                                                    :lm {:provider :mock :format :mock}})))))))

(deftest the-share-flag-is-read
  ;; main refuses --share on anything but a session, with a target
  ;; share/target-problem does not know, and in a piped session
  (is (= "link" (:share (brainyard.main/parse-args ["run" "--share" "link"]))))
  (is (= "" (:share (brainyard.main/parse-args ["run" "--share"])))))

;; --- what the person sees ----------------------------------------------------------------------

(deftest presence-is-shown-and-share-commands-reach-the-sidecar
  (let [p1 {:share "shr_1" :url "https://share.example/s/1" :paused false
            :participants [{:user "usr_o" :name "Owner" :role :owner} {:user "usr_kim" :name "Kim" :role :coach}]}
        p2 (assoc p1 :participants [{:user "usr_o" :name "Owner" :role "owner"} {:user "usr_lee" :name "Lee" :role "spectator"}])]
    (testing "the footer counts the shares and, apart, everyone but the owner, and says when it is paused"
      (is (= "sharing 1 · 1 watching" (share/footer p1)))
      (is (= "sharing 1 · paused" (share/footer (assoc p1 :paused true))))
      (is (nil? (share/footer nil)))
      (is (nil? (share/footer nil [])) "nothing shared from here either"))
    (testing "the shares this process started count too, and this session's own once (byj-gly.428)"
      (let [alone (assoc p1 :participants [{:user "usr_o" :name "Owner" :role "owner"}])
            own {:share "shr_1" :ref "byj:s-1" :watching []}
            claude {:share "shr_2" :ref "claude:dd27" :watching []}
            term {:share "shr_3" :ref "term:shell" :watching ["Kim (coach)" "Lee (spectator)"]}]
        (is (= "sharing 2 · 0 watching" (share/footer alone [own claude]))
            "two shares and nobody watching: was `sharing · 0`, the viewers counted as though shares")
        (is (= "sharing 2 · 2 watching" (share/footer nil [claude term]))
            "shared by reference only, with no presence of this session's: was nothing at all")
        (is (= "sharing 3 · 3 watching" (share/footer p1 [own claude term])))
        (is (= "sharing 2 · paused" (share/footer (assoc alone :paused true) [own claude])))))
    (testing "where the share is: said when the relay first answers with a URL, or a new one after a reconnect, and never again for the same (byj-1wv.7)"
      (is (= "sharing at https://share.example/s/1 — /share says who is watching" (share/url-line nil p1)))
      (is (nil? (share/url-line p1 p2)) "the same URL again says nothing")
      (is (nil? (share/url-line nil (dissoc p1 :url))) "no URL yet, nothing to say")
      (is (= "sharing at https://share.example/s/2 — /share says who is watching"
             (share/url-line p1 (assoc p1 :url "https://share.example/s/2")))))
    (testing "who joined and who left"
      (is (= ["Kim (coach) joined the share"] (share/changes nil p1)))
      (is (= ["Lee (spectator) joined the share" "Kim (coach) left the share"] (share/changes p1 p2))))
    (testing "/share lists who watches and where"
      (is (str/includes? (share/report p1 true) "sharing at https://share.example/s/1"))
      (is (str/includes? (share/report p1 true) "Kim (coach)"))
      (is (str/starts-with? (share/report nil false) "not sharing"))
      (is (str/starts-with? (share/report (assoc p1 :recording true) true) "sharing (recorded) at") "a recorded share says so (byj-gly.362.7)"))
    (testing "in a session: presence over :emit, then /share, /share pause and kick on the bus"
      (let [dir (fresh-dir "slash")
            ch (open dir)
            events (atom [])
            infos (atom [])
            lines (atom ["/share" "/share pause" "/share kick usr_kim" "/share resume" "/share record on" "/share record off"
                         "/share stop" "/share"])
            read-input (fn [_]
                         (loop [i 0] (when (and (empty? @(:hooks ch)) (< i 500)) (Thread/sleep 10) (recur (inc i))))
                         (when (= 8 (count @lines))
                           (channel/handle ch {:op :emit :event :session.share/presence :payload p1}))
                         (let [l (first @lines)] (swap! lines rest) l))]
        (session/run {:lm (mock-lm (atom []) "x")
                      :config {:working-dir dir :project-dir dir :max-iterations 3 :recap? false :persist-session? false}
                      :read-input read-input
                      :emit (fn [k t] (when (= :info k) (swap! infos conj t)))
                      :on-event (fn [e d] (swap! events conj [e d]))
                      :channel ch})
        (channel/close! ch)
        (is (= "sharing at https://share.example/s/1 — /share says who is watching" (first @infos))
            "the presence that brought the URL said it, before anyone asked")
        (is (= 1 (count (filter #(str/ends-with? % "— /share says who is watching") @infos))) "and only once")
        (is (str/includes? (second @infos) "Kim (coach)"))
        (is (= [{:action :pause} {:action :kick :user "usr_kim"} {:action :resume}
                {:action :record-on} {:action :record-off} {:action :stop}]
               (keep (fn [[e d]] (when (= :session.share/control e) d)) @events)))
        (is (str/starts-with? (last @infos) "not sharing") "after /share stop, nothing is shared")))))

;; --- doctor -------------------------------------------------------------------------------------

(deftest an-agent-that-does-more-than-read-is-shared-once-the-person-says-so
  ;; byj-1wv.47: /share agent:NAME and share$start ask through the session's
  ;; own prompt, the question being what callers could make the agent do
  (let [dir (.getPath (doto (java.io.File. (System/getProperty "java.io.tmpdir") (str "by-share-agent-" (System/nanoTime))) .mkdirs))
        config {:project-dir dir :working-dir dir :permission-mode :auto-approve :default-lm "claude-code/sonnet"}
        asked (atom [])
        confirm (fn [answer] {:config config :permission-fn (fn [req] (swap! asked conj req) answer)})
        check #(#'brainyard.share-tools/confirm-agents %1 %2)]
    (let [a (java.io.File. dir ".brainyard/agents/reader")]
      (.mkdirs a)
      (spit (java.io.File. a "agent.edn") (pr-str {:description "reads its files" :tools ["read-file" "grep"]}))
      (spit (java.io.File. a "instruction.md") "Answer from the files."))
    (is (nil? (check (confirm {:allowed true}) ["agent:reader"])) "an agent that reads its files is not asked about")
    (is (empty? @asked))
    (is (nil? (check (confirm {:allowed true}) ["agent:explore"])) "explore is asked about: its shell reads anywhere and runs git (byj-1wv.115.39)")
    (is (re-find #"share agent:explore — callers can make it read any file on this machine" (:display (last @asked))))
    (reset! asked [])
    (is (re-find #"not confirmed" (check (confirm {:denied true}) ["agent:edit"])))
    (let [q (:display (last @asked))]
      (is (re-find #"share agent:edit — callers can make it .*write files" q))
      (is (re-find #"nothing asks first: this project runs auto-approve" q)))
    (is (nil? (check (confirm {:allowed true}) ["agent:edit"])) "yes shares it")
    (is (re-find #"no one is here to confirm" (check {:config config} ["agent:plan"])) "a headless session cannot")
    (is (some #{"--yes"} (share/group-argv {:binary "byj-share" :refs ["agent:edit" "byj:1"] :parent 1})))
    (is (not (some #{"--yes"} (share/group-argv {:binary "byj-share" :refs ["byj:1" "term:x"] :parent 1}))))))
