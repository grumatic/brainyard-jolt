;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.persist-test
  "Session snapshots and resume (RFC-016): what is written, what comes back,
  what a bad snapshot does, and who may hold a session."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]
            [brainyard.eventlog]
            [brainyard.persist :as persist]
            [brainyard.projects :as projects]
            [brainyard.schema]
            [clojure.edn]
            [jolt.process :as p]
            [brainyard.session :as session]))

(defn- fresh-dir [name]
  (let [d (io/file "target/persist-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- config [dir & {:as more}]
  (merge {:working-dir dir :project-dir dir :session-id "s1" :persist-session? true
          :max-previous-turns 100 :full-depth 3 :max-question-chars 500 :max-answer-chars 1000}
         more))

(def ^:private turns
  [{:question "what is here?" :answer "two files" :terminated-by :answer-channel
    :tool-trail ["list-dir {:path \".\"}"]}
   {:question "read one" :answer "done" :terminated-by :answer-channel :tool-trail []}])

(deftest a-snapshot-is-the-session-and-nothing-live
  (let [dir (fresh-dir "save")
        c (config dir)
        f (persist/save! c "s1" {:turn-count 2 :usage {:llm-calls 3} :previous-turns turns
                                 :question "read one"})]
    (is (= (.getPath (persist/session-file c "s1")) (.getPath f)))
    (let [snap (persist/read-session c "s1")]
      (is (= persist/schema-version (:schema-version snap)))
      (is (= {:id "s1" :turn-count 2 :usage {:llm-calls 3} :previous-turns turns}
             (select-keys snap [:id :turn-count :usage :previous-turns])))
      (is (every? snap [:created-at :updated-at]) "so a listing can sort and age them"))
    (testing "the rendered history is not stored — it is derived from the turns"
      (is (not (contains? (persist/read-session c "s1") :history-context))))
    (testing "a second save keeps the creation time and moves the update time"
      (let [before (:created-at (persist/read-session c "s1"))]
        (Thread/sleep 5)
        (persist/save! c "s1" {:turn-count 3 :previous-turns turns})
        (let [snap (persist/read-session c "s1")]
          (is (= before (:created-at snap)))
          (is (<= before (:updated-at snap)))
          (is (= 3 (:turn-count snap))))))))

(deftest a-bad-snapshot-is-reported-not-thrown
  (let [dir (fresh-dir "bad")
        c (config dir)]
    (is (str/includes? (:problem (persist/read-session c "missing")) "no session missing"))
    (testing "unreadable EDN"
      (let [f (persist/session-file c "torn")]
        (.mkdirs (.getParentFile f))
        (spit f "{:schema-version 1 :turn-count")
        (is (str/includes? (:problem (persist/read-session c "torn")) "unreadable EDN"))
        (is (.isFile f) "and the file is left alone — it is the only copy of what went wrong")))
    (testing "a snapshot from another version"
      (let [f (persist/session-file c "old")]
        (.mkdirs (.getParentFile f))
        (spit f (pr-str {:schema-version 0 :turn-count 1}))
        (is (str/includes? (:problem (persist/read-session c "old")) "snapshot version 0"))))
    (testing "a readable snapshot of the wrong shape says where (byj-gly.204)"
      (let [f (persist/session-file c "damaged")]
        (.mkdirs (.getParentFile f))
        (spit f (pr-str {:schema-version 1 :id "damaged" :turn-count "two"
                         :previous-turns [{:question "q" :answer 42}]}))
        (let [p (:problem (persist/read-session c "damaged"))]
          (is (str/includes? p "not a session snapshot"))
          (is (str/includes? p "`turn-count` should be an integer"))
          (is (str/includes? p "`previous-turns.0.answer` should be a string")))))
    (testing "and a snapshot written before a key existed still reads"
      (let [f (persist/session-file c "older")]
        (.mkdirs (.getParentFile f))
        (spit f (pr-str {:schema-version 1 :id "older" :turn-count 1 :previous-turns []}))
        (is (nil? (:problem (persist/read-session c "older"))))))))

(deftest a-tool-arg-key-that-is-no-keyword-does-not-break-the-snapshot
  ;; {"file path": 1} keywordized prints as {:file path 1}, which does not
  ;; read back: the next --resume found the snapshot unreadable (byj-gly.221)
  (let [dir (fresh-dir "odd-key")
        c (config dir)
        entry (#'session/tool-entry {:tool-name "t" :tool-args (json/read-str "{\"file path\": 1, \"ok\": {\"a b\": 2}}")
                                     :tool-result "done"})]
    (is (= {"file path" 1 :ok {"a b" 2}} (:tool-args entry)) "the odd keys are kept as strings, the rest as they were")
    (persist/save! c "s1" {:turn-count 1 :previous-turns [] :transcript [[:tool entry]]})
    (is (nil? (:problem (persist/read-session c "s1"))) "and the snapshot reads back")))

(deftest what-save-writes-is-a-snapshot
  ;; the writer and the schema are one contract: a save that could not be
  ;; resumed would only be found out on the resume
  (let [dir (fresh-dir "shape")
        c (config dir)]
    (persist/save! c "s1" {:turn-count 2 :usage {:llm-calls 3} :previous-turns turns :question "read one"
                           :transcript [[:you "what is here?"] [:tool {:tool-name "list-dir" :tool-args {:path "."}}]]
                           :conversation-title "look-around" :model "claude-code/haiku"})
    (is (nil? (:problem (persist/read-session c "s1"))))
    (is (nil? (brainyard.schema/explain-problems persist/snapshot-schema
                                                 (clojure.edn/read-string (slurp (persist/session-file c "s1"))))))))

(deftest listing-and-latest
  (let [dir (fresh-dir "list")
        c (config dir)]
    (persist/save! c "older" {:turn-count 1 :previous-turns []})
    (Thread/sleep 5)
    (persist/save! c "newer" {:turn-count 9 :previous-turns []})
    (let [rows (persist/summaries c)]
      (is (= ["newer" "older"] (map :id rows)) "newest first")
      (is (= 9 (:turn-count (first rows)))))
    (is (= "newer" (persist/latest-id c)))
    (testing "a broken snapshot is listed with its problem, not hidden or fatal"
      (let [f (persist/session-file c "broken")]
        (.mkdirs (.getParentFile f))
        (spit f "nonsense{")
        (is (some :problem (persist/summaries c)))
        (is (= "newer" (persist/latest-id c)) "and it is never the latest")))))

(deftest one-live-process-per-session
  (let [dir (fresh-dir "lock")
        c (config dir)]
    (is (true? (persist/lock! c "s1")))
    (is (nil? (persist/holder c "s1")) "our own lock does not block us")
    (is (true? (persist/lock! c "s1")) "and re-locking is fine")
    (testing "a live foreign pid holds it"
      (let [pidfile (io/file dir "child.pid")
            proc (p/process {:out :string :err :string}
                            "sh" "-c" (str "echo $$ > " (.getPath pidfile) "; sleep 30"))]
        (try
          (loop [n 0] (when (and (< n 100) (not (.isFile pidfile))) (Thread/sleep 20) (recur (inc n))))
          (let [pid (str/trim (slurp pidfile))]
            (is (persist/alive? pid) "the child is running")
            (spit (persist/lock-file c "s1") (pr-str {:pid pid :at 0}))
            (is (= pid (persist/holder c "s1")))
            (is (false? (persist/lock! c "s1"))))
          (finally (p/destroy-tree proc)))))
    (testing "a zombie — ended, its parent not waiting for it — holds nothing (byj-974)"
      ;; `sleep 0.2 &` is a child of the shell, which then becomes `sleep 30`:
      ;; nobody waits for it, so once it ends it stays a zombie
      (let [pidfile (io/file dir "zombie.pid")
            proc (p/process {:out :string :err :string}
                            "sh" "-c" (str "sleep 0.2 & echo $! > " (.getPath pidfile) "; exec sleep 30"))]
        (try
          (loop [n 0] (when (and (< n 100) (not (.isFile pidfile))) (Thread/sleep 20) (recur (inc n))))
          (Thread/sleep 700)
          (let [pid (str/trim (slurp pidfile))]
            (is (zero? (:exit @(p/process {:out :string :err :string} "kill" "-0" pid))) "kill -0 still answers for it")
            (is (not (persist/alive? pid)))
            (spit (persist/lock-file c "s1") (pr-str {:pid pid :at 0}))
            (is (nil? (persist/holder c "s1"))))
          (finally (p/destroy-tree proc)))))
    (testing "a dead pid is stale, and taking it over is allowed"
      (spit (persist/lock-file c "s1") (pr-str {:pid "999999" :at 0}))
      (is (nil? (persist/holder c "s1")))
      (is (true? (persist/lock! c "s1"))))
    (testing "unlock removes our lock, and a clean exit leaves none"
      (persist/unlock! c "s1")
      (is (not (.isFile (persist/lock-file c "s1")))))))

(deftest one-of-many-racing-processes-takes-a-free-session
  ;; byj-ku1.2: lock! once checked holder and then wrote, so two processes
  ;; that both found the session free both took it. Each racer here claims
  ;; as a live process of its own (a child's pid), all released at once.
  (let [dir (fresh-dir "lock-race")
        c (config dir)
        kids (vec (repeatedly 6 #(p/process {:out :string :err :string} "sleep" "30")))]
    (try
      (let [pids (mapv #(str (.pid (:proc %))) kids)]
        (doseq [round (range 5)]
          (let [id (str "race-" round)
                go (promise)
                racers (mapv (fn [pid] (future @go (persist/lock! c id pid))) pids)
                _ (deliver go true)
                won (mapv deref racers)]
            (is (= 1 (count (filter true? won))) (str "round " round ": " won))
            (is (contains? (set pids) (persist/holder c id)) "and the lock names the winner")
            (is (empty? (filter #(str/ends-with? (.getName %) ".stale")
                                (.listFiles (.getParentFile (persist/lock-file c id)))))
                "nothing left aside"))))
      (testing "a lock that does not parse is being written: held for a moment, then stale"
        (let [f (persist/lock-file c "torn")]
          (io/make-parents f)
          (spit f "{:pid")
          (is (false? (persist/lock! c "torn")))
          (.setLastModified f (- (System/currentTimeMillis) 60000))
          (is (true? (persist/lock! c "torn")))))
      (finally (doseq [k kids] (p/destroy-tree k))))))

(deftest a-save-that-did-not-land-says-so
  ;; byj-ku1.1: write-edn! ignored its rename's result, so save! returned the
  ;; file of a snapshot it had not written
  (let [dir (fresh-dir "save-fails")
        c (config dir)
        f (persist/session-file c "s1")]
    (testing "a snapshot whose place cannot be taken is no snapshot"
      ;; a directory, not empty, where the snapshot goes: no rename replaces it
      (.mkdirs (io/file f "in-the-way"))
      (is (nil? (persist/save! c "s1" {:turn-count 1})))
      (let [why (atom nil)]
        (persist/save! c "s1" {:turn-count 1} {:on-error #(reset! why (ex-message %))})
        (is (re-find #"could not replace" (str @why)) "and the report can say why (byj-ku1.6)"))
      (is (empty? (filter #(str/ends-with? (.getName %) ".tmp") (.listFiles (.getParentFile f))))
          "and its temp file is not left behind"))
    (testing "saves of one session at once all land, and the snapshot reads"
      (let [go (promise)
            saves (mapv (fn [n] (future @go (persist/save! c "s2" {:turn-count n}))) (range 8))]
        (deliver go true)
        (is (every? some? (map deref saves)))
        (is (contains? (set (range 8)) (:turn-count (persist/read-session c "s2"))))))))

(deftest notes-left-at-once-are-all-taken
  ;; byj-ku1.3: note! read the notes, added one and wrote them all back, so of
  ;; two notes left at once one was lost; take-notes! deleted a file it could
  ;; not read, with whatever it held
  (let [dir (fresh-dir "notes")
        c (config dir)]
    (persist/save! c "s1" {:turn-count 1})
    (testing "notes left from many writers at once are all there, in no lost order"
      (let [go (promise)
            writers (mapv (fn [w] (future @go (doseq [i (range 25)] (persist/note! c "s1" (str w "-" i)))))
                          (range 8))]
        (deliver go true)
        (run! deref writers)
        (let [taken (persist/take-notes! c "s1")]
          (is (= 200 (count taken)))
          (is (= (set (for [w (range 8) i (range 25)] (str w "-" i))) (set taken))))
        (is (nil? (persist/take-notes! c "s1")) "and none are left behind")))
    (testing "a file an older byj wrote, one vector, still reads"
      (spit (persist/notes-file c "s1") (pr-str ["older" "notes"]))
      (is (= ["older" "notes"] (persist/take-notes! c "s1"))))
    (testing "a file that does not all read is kept, and what read is handed on"
      (spit (persist/notes-file c "s1") (str (pr-str "kept") "\n\"torn\n"))
      (is (= ["kept"] (persist/take-notes! c "s1")))
      (is (= 1 (count (filter #(str/ends-with? (.getName %) ".bad")
                              (.listFiles (.getParentFile (persist/notes-file c "s1"))))))))))

(deftest a-failed-migration-says-so-and-is-tried-again
  ;; byj-ku1.4: migrate-once! stamped the project migrated before migrate!
  ;; ran and swallowed its failure: never tried again, never said
  (let [dir (fresh-dir "migrate-fails")
        c (config dir)
        calls (atom 0)
        fail? (atom true)]
    (.mkdirs (io/file dir ".brainyard" "sessions" "legacy-1"))
    (with-redefs-fn {#'brainyard.persist/auto-migrate? (constantly true)
                     #'brainyard.persist/migrate! (fn [_] (swap! calls inc)
                                                    (if @fail? (throw (ex-info "disk says no" {})) {:moved 0 :left []}))}
      (fn []
        (persist/sessions-dir c)
        (is (= 1 @calls))
        (let [log (brainyard.eventlog/log-file c)]
          (is (and log (str/includes? (slurp log) ":session/migration-failed")) "the event log says it failed")
          (is (str/includes? (slurp log) "disk says no")))
        (persist/sessions-dir c)
        (is (= 1 @calls) "not tried again on every path asked for")
        ;; a minute on: tried again, and done once it is
        (swap! @#'brainyard.persist/migration-failed update (persist/project-of c) - 120000)
        (reset! fail? false)
        (persist/sessions-dir c)
        (is (= 2 @calls))
        (persist/sessions-dir c)
        (is (= 2 @calls) "stamped once done")))))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(defn- run-session [dir inputs & {:as cfg}]
  (let [in (atom (vec inputs))
        out (atom [])
        answers (atom ["first answer" "second answer" "third answer"])]
    (session/run {:lm {:provider :mock :format :mock
                       :responses (fn [_] (let [[a] (swap-vals! answers #(vec (rest %)))]
                                            (reply {:answer (first a)})))}
                  :config (config dir cfg)
                  :read-input (fn [_] (let [[x] (swap-vals! in #(vec (rest %)))] (first x)))
                  :emit (fn [kind text] (swap! out conj [kind text]))
                  :out out})))

(deftest a-session-survives-a-restart
  (let [dir (fresh-dir "resume")
        first-run (run-session dir ["what is here?" "/quit"])]
    (is (= 1 (:turn-count first-run)))
    (testing "the turn was saved"
      (let [snap (persist/read-session (config dir) "s1")]
        (is (= 1 (:turn-count snap)))
        (is (= ["what is here?"] (map :question (:previous-turns snap))))))
    (testing "a later run resumes it: the turns come back and the count continues"
      (let [second-run (run-session dir ["and now?" "/quit"] :resume "s1")]
        (is (= 2 (:turn-count second-run)))
        (is (= ["what is here?" "and now?"] (map :question (:previous-turns second-run))))
        (testing "and the rendered history is re-derived, not restored"
          (is (str/includes? (:history-context second-run) "what is here?")))))
    (testing "resuming a session that cannot be read starts fresh and says so"
      (let [c (config dir)
            f (persist/session-file c "torn")]
        (.mkdirs (.getParentFile f))
        (spit f "{:oops")
        (let [r (run-session dir ["q" "/quit"] :resume "torn" :session-id "torn2")]
          (is (= 1 (:turn-count r)) "fresh, not refused"))))))

(deftest a-session-is-saved-when-it-starts-and-leaves-nothing-when-it-ends-with-no-turn
  ;; byj-y6a: a client lists a session before its first turn, and a run
  ;; started and quit leaves no session behind
  (let [dir (fresh-dir "saved-at-start")
        c (config dir)
        seen (atom nil)
        in (atom [(fn [] (reset! seen (persist/read-session c "s1")) "/quit")])]
    (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (reply {:answer "a"}))}
                  :config c
                  :read-input (fn [_] (let [[x] (swap-vals! in #(vec (rest %)))] (when-let [f (first x)] (f))))
                  :emit (fn [_ _])
                  :out (atom [])})
    (is (= 0 (:turn-count @seen)) "its snapshot is there before the first input")
    (is (not (.exists (persist/session-dir c "s1"))) "and gone when it ends with none")
    (testing "one that ran a turn is kept"
      (run-session dir ["what is here?" "/quit"] :session-id "s2")
      (is (= 1 (:turn-count (persist/read-session c "s2")))))
    (testing "a resume of a session that ran turns does not save over it at the start"
      (run-session dir ["/quit"] :resume "s2" :session-id "s2")
      (is (= 1 (:turn-count (persist/read-session c "s2")))))))

(deftest a-session-that-never-ran-a-turn-is-kept-a-day-then-removed
  ;; byj-y6a: what a process stopped before a session's first turn leaves —
  ;; never a one-shot ask's record, nor one held by a live process
  (let [dir (fresh-dir "prune-empty")
        c (config dir :session-id "current")
        day (* 24 60 60 1000)
        now (System/currentTimeMillis)
        stale (fn [id] (doseq [f (file-seq (persist/session-dir c id))] (.setLastModified f (- now (* 2 day)))))]
    (persist/save! c "fresh-empty" {:turn-count 0 :previous-turns []})
    (persist/save! c "old-empty" {:turn-count 0 :previous-turns []})
    (spit (persist/session-file c "old-empty") (pr-str (assoc (persist/read-session c "old-empty") :updated-at (- now (* 2 day)))))
    (persist/save! c "old-with-a-turn" {:turn-count 1 :previous-turns []})
    (spit (persist/session-file c "old-with-a-turn") (pr-str (assoc (persist/read-session c "old-with-a-turn") :updated-at (- now (* 2 day)))))
    ;; a lock and nothing else: a process killed before it saved anything
    (spit (doto (persist/lock-file c "lock-only") io/make-parents) (pr-str {:pid "999999" :at 0}))
    (stale "lock-only")
    ;; a one-shot ask's record: a trajectory, no snapshot
    (spit (doto (io/file (persist/session-dir c "ask-record") "trajectory.edn") io/make-parents) "{}")
    (stale "ask-record")
    (persist/save! c "current" {:turn-count 0 :previous-turns []})
    (spit (persist/session-file c "current") (pr-str (assoc (persist/read-session c "current") :updated-at (- now (* 2 day)))))
    (is (persist/empty-session? c "lock-only"))
    (is (not (persist/empty-session? c "ask-record")))
    (is (= #{"old-empty" "lock-only"} (set (persist/prune-empty! c))))
    (let [left (set (map #(.getName %) (.listFiles (persist/sessions-dir c))))]
      (is (contains? left "fresh-empty") "a day to be opened again")
      (is (contains? left "old-with-a-turn") "it ran a turn: prune!'s, by age")
      (is (contains? left "ask-record") "a record, not a session")
      (is (contains? left "current") "this run's own"))
    (is (= "old-with-a-turn" (persist/latest-id c)) "never an empty one, though newer: --resume has nothing to bring back from it")
    (testing "one where the person switched the model has something to bring back"
      (persist/save! c "switched" {:turn-count 0 :previous-turns [] :model "anthropic/claude-sonnet-5"})
      (is (not (persist/empty-session? c "switched"))))))

(deftest a-resumes-notes-are-said-not-kept
  ;; byj-gly.286: each resume's notes were recorded, so the next resume replayed
  ;; them — "↻ resumed" twice and more; and they were said before the history,
  ;; alone on the screen until it landed
  (let [dir (fresh-dir "resume-notes")
        _ (run-session dir ["what is here?" "/quit"])
        _ (run-session dir ["/quit"] :resume "s1")
        order (atom [])
        _ (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (reply {:answer "x"}))}
                        :config (config dir :resume "s1")
                        :read-input (fn [_] nil)
                        :emit (fn [kind text] (swap! order conj [:said kind text]))
                        :on-event (fn [e d] (when (= :session/resumed e)
                                              (swap! order conj [:resumed (count (filter #(str/starts-with? (str (second %)) "↻ resumed")
                                                                                         (:transcript d)))])))})]
    (is (= [:resumed 0] (first @order)) "the history first, and no resume note in it after two resumes")
    (is (some #(and (= :said (first %)) (str/starts-with? (str (nth % 2)) "↻ resumed")) @order)
        "the note is still said")
    (is (not-any? #(str/starts-with? (str (second %)) "↻ resumed")
                  (:transcript (persist/read-session (config dir) "s1"))))))

(deftest a-switched-model-is-resumed
  (let [dir (fresh-dir "resume-model")
        run (fn [cfg lines box]
              (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (reply {:answer "a"}))}
                            :config cfg
                            :lm-box box
                            :read-input (let [in (atom lines)]
                                          (fn [_] (let [[x] (swap-vals! in #(vec (rest %)))] (first x))))
                            :emit (fn [_ _])}))
        _ (run (config dir) ["/model anthropic/claude-sonnet-5" "/quit"] (atom nil))
        box (atom nil)]
    (testing "the switch is kept with the session, and so is the build that wrote it"
      (is (= "anthropic/claude-sonnet-5" (:model (persist/read-session (config dir) "s1"))))
      (is (re-find #"^v\d+" (str (:brainyard-version (persist/read-session (config dir) "s1"))))))
    (testing "and a resume asks it again, through the box the next call and the footer read"
      (run (config dir :resume "s1") ["/quit"] box)
      (is (= {:provider :anthropic :model "claude-sonnet-5"} (select-keys @box [:provider :model]))))
    (testing "but a model named on the command line is the later choice, and it says so (byj-gly.232)"
      (let [box (atom nil) said (atom [])]
        (session/run {:lm {:provider :mock :format :mock :model "mock-1" :responses (fn [_] (reply {:answer "a"}))}
                      :config (config dir :resume "s1") :lm-box box :lm-flagged? true
                      :read-input (fn [_] "/quit")
                      :emit (fn [_ t] (swap! said conj (str t)))})
        (is (nil? @box) "the box is not switched")
        (is (some #(str/includes? % "this session last used anthropic/claude-sonnet-5") @said)))))
  (testing "a session that never switched resumes on the run's own model"
    (let [dir (fresh-dir "resume-no-model")
          box (atom nil)]
      (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (reply {:answer "a"}))}
                    :config (config dir) :read-input (let [in (atom ["hi" "/quit"])] (fn [_] (let [[x] (swap-vals! in #(vec (rest %)))] (first x))))
                    :emit (fn [_ _])})
      (session/run {:lm {:provider :mock :format :mock :responses (fn [_] (reply {:answer "a"}))}
                    :config (config dir :resume "s1") :lm-box box
                    :read-input (fn [_] nil) :emit (fn [_ _])})
      (is (nil? @box)))))

(deftest a-suggestion-outlives-a-save-with-no-turn
  ;; byj-gly.280.4: resumed and closed without asking, the session is saved
  ;; again with no turn of its own — it keeps what it resumed with
  (let [dir (fresh-dir "suggestion")
        c (config dir)]
    (persist/save! c "s1" {:turn-count 1 :previous-turns [] :last-result {:next-user-prompt "  and then?  "}})
    (is (= "and then?" (:next-user-prompt (persist/read-session c "s1"))) "trimmed")
    (persist/save! c "s1" {:turn-count 1 :previous-turns []})
    (is (= "and then?" (:next-user-prompt (persist/read-session c "s1"))) "kept")
    (persist/save! c "s1" {:turn-count 2 :previous-turns [] :last-result {:answer "a"}})
    (is (nil? (:next-user-prompt (persist/read-session c "s1"))) "a turn that offered none clears it")))

(deftest a-resume-says-again-what-was-said
  (let [dir (fresh-dir "resume-transcript")
        ;; read the snapshot at the next prompt — after the turn, before the
        ;; close writes one again — which is all a session killed there leaves
        saved (atom nil)
        saved-prompt (atom nil)
        _ (spit (io/file dir "notes.txt") "hello")
        ;; the turn calls a tool before it answers
        first-replies (atom [(reply {:reasoning "Listing it first, to see what is here."
                                     :tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]})
                             (reply {:answer "first answer" :next-user-prompt "what is in notes.txt?"})])
        _ (session/run {:lm {:provider :mock :format :mock
                             :responses (fn [_] (let [[x] (swap-vals! first-replies #(vec (rest %)))] (first x)))}
                        :config (config dir)
                        :read-input (let [in (atom ["what is here?"])]
                                      (fn [_]
                                        (let [[x] (swap-vals! in #(vec (rest %)))]
                                          (when-not (first x)
                                            (let [snap (persist/read-session (config dir) "s1")]
                                              (reset! saved (:transcript snap))
                                              (reset! saved-prompt (:next-user-prompt snap))))
                                          (first x))))
                        :emit (fn [_ _])})
        events (atom [])
        out (atom [])
        second-run (session/run {:lm {:provider :mock :format :mock
                                      :responses (fn [_] (reply {:answer "second answer"}))}
                                 :config (config dir :resume "s1")
                                 :read-input (let [in (atom ["and now?" "/quit"])]
                                               (fn [_] (let [[x] (swap-vals! in #(vec (rest %)))] (first x))))
                                 :emit (fn [kind text] (swap! out conj [kind text]))
                                 :on-event (fn [e d] (swap! events conj [e d]))})
        resumed (some (fn [[e d]] (when (= :session/resumed e) d)) @events)]
    (testing "the snapshot keeps what was said, question and answer alike, the last answer included"
      (is (= [[:you "what is here?"] [:answer "first answer"]]
             (filter (comp #{:you :answer} first) @saved))))
    (testing "and the tool calls between them, as data a front end can draw again (byj-gly.134)"
      (is (= [:you :tool :answer] (map first (filter (comp #{:you :tool :answer} first) @saved))))
      (let [[_ call] (first (filter #(= :tool (first %)) @saved))]
        (is (= "list-dir" (:tool-name call)))
        (is (= {:path "."} (:tool-args call)))
        (is (str/includes? (:tool-result call) "notes.txt"))))
    (testing "each tool iteration's thought, before its calls; an answering one's is not kept (byj-gly.280.4)"
      (is (= [:you :thought :tool :answer] (map first (filter (comp #{:you :thought :tool :answer} first) @saved))))
      (is (= [:thought "Listing it first, to see what is here."] (first (filter #(= :thought (first %)) @saved)))))
    (testing "the model's next-user-prompt is kept, and handed back on resume (byj-gly.280.4)"
      (is (= "what is in notes.txt?" @saved-prompt))
      (is (= "what is in notes.txt?" (:next-user-prompt resumed)))
      (is (nil? (:next-user-prompt (persist/read-session (config dir) "s1")))
          "the resumed session's own turn offered none, so none is kept"))
    (testing "a resume hands it to the front end, before the line that says it resumed"
      (is (= "s1" (:id resumed)))
      (is (= [[:you "what is here?"] [:answer "first answer"]]
             (filter (comp #{:you :answer} first) (:transcript resumed))))
      (is (str/starts-with? (second (first @out)) "↻ resumed")))
    (testing "and the transcript carries on from it, so /capture has the whole conversation"
      (is (= ["what is here?" "and now?"]
             (keep (fn [[k t]] (when (= :you k) t)) (:transcript second-run))))))
  (testing "a snapshot keeps only the newest entries: it is rewritten every turn"
    (let [dir (fresh-dir "transcript-cap")
          c (config dir)]
      (persist/save! c "s1" {:turn-count 1 :previous-turns []
                             :transcript (vec (for [i (range 600)] [:info (str "line " i)]))})
      (let [kept (:transcript (persist/read-session c "s1"))]
        (is (= persist/transcript-entries (count kept)))
        (is (= [:info "line 599"] (peek kept))))))
  (testing "the conversation's name is kept, so a resume is named too"
    (let [dir (fresh-dir "title")
          c (config dir)]
      (persist/save! c "s1" {:turn-count 1 :previous-turns [] :conversation-title "fix-the-wheel"})
      (is (= "fix-the-wheel" (:conversation-title (persist/restore (persist/read-session c "s1")))))))
  (testing "a snapshot written before transcripts were kept restores with none"
    (is (= [] (:transcript (persist/restore {:previous-turns [] :turn-count 1 :usage {}}))))))

(deftest persistence-can-be-switched-off
  (let [dir (fresh-dir "off")]
    (run-session dir ["q" "/quit"] :persist-session? false)
    (is (not (.isDirectory (persist/sessions-dir (config dir)))))))

;; --- pruning ---------------------------------------------------------------------

(defn- aged-session!
  "A session whose snapshot says it was last updated `days-ago`."
  [config id days-ago]
  (persist/save! config id {:turn-count 1 :previous-turns []})
  (let [f (persist/session-file config id)
        m (assoc (persist/read-session config id)
                 :updated-at (- (System/currentTimeMillis) (* days-ago 24 60 60 1000)))]
    (spit f (pr-str m))
    id))

(defn- aged-transcript! [config id days-ago]
  (let [d (io/file (persist/transcripts-dir config) id "turn-1")]
    (.mkdirs d)
    (spit (io/file d "it1-read-file.txt") "spilled")
    (.setLastModified (io/file (persist/transcripts-dir config) id)
                      (- (System/currentTimeMillis) (* days-ago 24 60 60 1000)))
    id))

(deftest pruning-removes-only-what-it-may
  (let [dir (fresh-dir "prune")
        c (config dir :session-id "current" :keep-sessions 2 :session-ttl-days 14)]
    (aged-session! c "current" 400)
    (aged-session! c "recent" 1)
    (aged-session! c "kept-by-count" 100)    ; 2nd newest, so inside keep 2
    (aged-session! c "beyond-keep" 200)      ; 3rd, and past the TTL
    (aged-session! c "old" 300)
    (aged-session! c "locked" 365)
    (aged-transcript! c "old" 300)
    (aged-transcript! c "older" 500)
    (aged-transcript! c "current" 400)
    (let [pidfile (io/file dir "child.pid")
          proc (p/process {:out :string :err :string}
                          "sh" "-c" (str "echo $$ > " (.getPath pidfile) "; sleep 30"))]
      (try
        (loop [n 0] (when (and (< n 100) (not (.isFile pidfile))) (Thread/sleep 20) (recur (inc n))))
        (spit (persist/lock-file c "locked") (pr-str {:pid (str/trim (slurp pidfile)) :at 0}))
        (let [{:keys [removed]} (persist/prune! c)
              left (set (map #(.getName %) (.listFiles (persist/sessions-dir c))))]
          (testing "the old, unheld, out-of-keep sessions go"
            (is (contains? (set removed) "old")))
          (testing "and nothing else does"
            (is (contains? left "current") "this run's own session")
            (is (contains? left "recent") "newer than the TTL")
            (is (contains? left "kept-by-count") "inside the keep count")
            (is (contains? left "locked") "held by a live process — brainyard's pruner ignores its own lock"))
          (testing "what is past the TTL and outside the keep count does go"
            (is (= #{"old" "beyond-keep"} (set/intersection (set removed) #{"old" "beyond-keep"})))
            (is (not-any? left #{"old" "beyond-keep"})))
          (testing "transcripts are pruned by the same rules, counted in their own tree"
            (let [tleft (set (map #(.getName %) (.listFiles (persist/transcripts-dir c))))]
              (is (contains? tleft "current") "this run's own")
              (is (contains? tleft "old") "inside the keep count for THIS tree")
              (is (not (contains? tleft "older")) "beyond it, and past the TTL")))
          (testing "and the whole tree goes, not just the top file"
            (is (not (.exists (io/file (persist/transcripts-dir c) "older" "turn-1" "it1-read-file.txt"))))))
        (finally (p/destroy-tree proc))))))

(deftest pruning-refuses-to-escape-its-root
  (let [dir (fresh-dir "escape")
        c (config dir)
        outside (io/file dir "not-a-session")]
    (.mkdirs outside)
    (spit (io/file outside "keep.txt") "x")
    (aged-session! c "victim" 900)
    (with-redefs [persist/sessions-dir (constantly (io/file dir "sessions-empty"))]
      (persist/prune! (assoc c :keep-sessions 0 :session-ttl-days 0)))
    (is (.isFile (io/file outside "keep.txt")) "a sibling directory is untouched")
    (testing "a directory that is not a direct child of the root is never deleted"
      (is (.isDirectory outside)))))

(deftest a-stored-directory-is-absolute
  (let [dir (str (System/getProperty "user.dir") "/target/persist-test/abs")
        _ (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
        _ (.mkdirs (io/file dir))
        id "1789000000000-abcdef12"]
    (testing "a relative dir in config is written absolute — a resume happens in another process"
      (persist/save! {:working-dir "." :project-dir dir} id {:turn-count 1 :previous-turns []})
      (let [snap (persist/read-session {:project-dir dir} id)]
        (is (.isAbsolute (io/file (:working-dir snap))))
        (is (.isAbsolute (io/file (:project-dir snap))))
        ;; a segment, not a substring: a checkout under .brainyard/worktrees
        ;; is canonical and still holds "/." (byj-gly.294)
        (is (not-any? #{"." ".."} (str/split (:working-dir snap) #"/"))
            "and canonical: a /. tail defeats the prefix tests the gates do")))

    (testing "what was stored is where the session actually was"
      (let [snap (persist/read-session {:project-dir dir} id)]
        (is (= (.getCanonicalPath (io/file ".")) (:working-dir snap)))
        (is (= (.getCanonicalPath (io/file dir)) (:project-dir snap)))))))

(deftest the-resume-line-does-not-promise-turns-that-are-gone
  ;; byj-gly.75, found by driving `by run` through a scripted session.
  ;; :turn-count counts every turn the session ran; :previous-turns is what it
  ;; still remembers, and /clear makes those differ — so resuming greeted you
  ;; with "3 turn(s)" and /history then showed one.
  (testing "when they agree, the line says one number, as before"
    (let [l (persist/describe {:id "x" :turn-count 3 :previous-turns [1 2 3]})]
      (is (str/includes? l "3 turn(s)"))
      (is (not (str/includes? l "remembered")))))

  (testing "when a clear has made them differ, it says both"
    (let [l (persist/describe {:id "x" :turn-count 3 :previous-turns [1]})]
      (is (str/includes? l "3 turn(s)"))
      (is (str/includes? l "(1 remembered)"))))

  (testing "and a session that ran no turns says nothing extra"
    (is (not (str/includes? (persist/describe {:id "x" :turn-count 0 :previous-turns []})
                            "remembered")))))

;; --- a finished run is not a session (byj-gly.89) ----------------------------------------

(defn- ask-record!
  "What a one-shot `ask` leaves: a directory holding a trajectory and nothing
  else. No session.edn, so nothing can resume it."
  [config id days-ago]
  (let [d (io/file (persist/sessions-dir config) id)]
    (.mkdirs d)
    (spit (io/file d "trajectory.edn") (pr-str {:v 3 :question id}))
    (.setLastModified d (- (System/currentTimeMillis) (* days-ago 24 60 60 1000)))
    id))

(deftest a-finished-runs-record-is-bounded-by-count-not-by-age
  ;; the TTL is what let these pile up: nothing younger than 14 days was ever
  ;; pruned, so a busy day left a directory per turn and :keep-sessions never
  ;; bit. 615 of them in one afternoon.
  (let [dir (fresh-dir "prune-asks")
        c (assoc (config dir) :keep-ask-records 3)]
    (doseq [i (range 10)] (ask-record! c (str "ask-" i) 0))
    ;; sessions are folders; the project's record is beside the folder (byj-84c.1)
    (is (= 10 (count (filter #(.isDirectory %) (.listFiles (persist/sessions-dir c))))))

    (let [{:keys [removed]} (persist/prune! c)]
      (is (= 7 (count removed)) "all but the newest :keep-ask-records, though none is old")
      (is (= 3 (count (filter #(.isDirectory %) (.listFiles (persist/sessions-dir c)))))))))

(deftest a-resumable-session-is-never-pruned-to-make-room-for-records
  ;; counting the two together would be a worse bug than the one it fixes: a
  ;; session from yesterday deleted because twenty newer asks happened
  (let [dir (fresh-dir "prune-mixed")
        c (assoc (config dir) :keep-ask-records 2 :keep-sessions 20)]
    (aged-session! c "yesterday" 1)
    (doseq [i (range 8)] (ask-record! c (str "ask-" i) 0))

    (let [{:keys [removed]} (persist/prune! c)]
      (is (= 6 (count removed)))
      (is (not-any? #(= "yesterday" %) removed) "the session someone could resume stands")
      (is (.isFile (persist/session-file c "yesterday"))))

    (testing "and it still goes once it is genuinely old and beyond the keep count"
      (let [c2 (assoc c :keep-sessions 0)]
        (aged-session! c2 "ancient" 400)
        (is (some #(= "ancient" %) (:removed (persist/prune! c2))))))))

(deftest what-makes-a-directory-resumable-is-the-snapshot
  (let [dir (fresh-dir "prune-resumable")
        c (config dir)]
    (aged-session! c "real" 1)
    (ask-record! c "record" 1)
    (is (true? (persist/resumable? (persist/session-dir c "real"))))
    (is (false? (persist/resumable? (persist/session-dir c "record")))
        "a trajectory is a record of a run that ended, not a conversation to continue")))

(deftest a-record-held-by-a-live-process-is-left-alone
  ;; the lock is checked before either budget, as it was before
  (let [dir (fresh-dir "prune-locked")
        c (assoc (config dir) :keep-ask-records 0)]
    (ask-record! c "held" 0)
    (spit (persist/lock-file c "held") (pr-str {:pid "1" :at 0}))
    (with-redefs [persist/alive? (constantly true)
                  persist/own-pid (constantly "99999")]
      (is (empty? (:removed (persist/prune! c)))))))

(deftest transcripts-keep-the-rule-they-had
  ;; they have no session.edn either, and count-pruning them would be a change
  ;; nobody asked for — the new budget applies to the sessions root alone
  (let [dir (fresh-dir "prune-transcripts")
        c (assoc (config dir) :keep-ask-records 0 :keep-sessions 0)]
    (doseq [i (range 5)] (aged-transcript! c (str "t-" i) 1))
    (is (empty? (:removed (persist/prune! c))) "young, so the TTL still protects them")))

(deftest a-run-remembers-the-set-it-had-open
  (let [dir (str (fresh-dir "last-run"))
        config {:project-dir dir :working-dir dir :persist-session? true}]
    (is (nil? (persist/read-last-run config)) "no run yet")
    (doseq [id ["s-a" "s-b" "s-c"]]
      (persist/save! config id {:turn-count 1 :previous-turns [{:question "q" :answer "a"}]}))
    (persist/write-last-run! config {:sessions ["s-a" "s-b" "s-gone"] :front "s-b"})
    (testing "the set comes back in order, with the one in front, and only what is still saved (byj-gly.178)"
      (is (= {:sessions ["s-a" "s-b"] :front "s-b"} (select-keys (persist/read-last-run config) [:sessions :front]))))
    (testing "a front that is gone falls back to the first"
      (persist/write-last-run! config {:sessions ["s-c" "s-a"] :front "s-gone"})
      (is (= "s-c" (:front (persist/read-last-run config)))))
    (testing "a snapshot's last question is its last turn's, which the list names it by"
      (is (= "q" (:last-question (first (filter #(= "s-a" (:id %)) (persist/summaries config)))))))
    (testing "the record is not taken for a session"
      (is (every? #(not= "last-run.edn" (:id %)) (persist/summaries config))))))

(deftest a-fork-is-cut-before-a-turn
  ;; RFC-069 (byj-gly.486): the new session's history and transcript stop
  ;; before turn K; the session it came from is not touched
  (let [dir (fresh-dir "fork-cut")
        c (config dir)
        t (fn [n] {:question (str "q" n) :answer (str "a" n) :terminated-by :answer-channel :tool-trail [] :turn n})
        tr [[:you "q1"] [:answer "a1"] [:info "a command between"] [:you "q2"] [:tool {:tool-name "x"}] [:answer "a2"] [:you "q3"] [:answer "a3"]]]
    (persist/save! c "s1" {:turn-count 3 :previous-turns [(t 1) (t 2) (t 3)] :transcript tr})
    (is (string? (str (persist/fork! c "s1" "s2" {:before-turn 2}))))
    (let [s2 (persist/read-session c "s2")]
      (is (= ["q1"] (map :question (:previous-turns s2))))
      (is (= [[:you "q1"] [:answer "a1"] [:info "a command between"]] (:transcript s2)))
      (is (= 1 (:turn-count s2)) "its next turn is 2")
      (is (= "q1" (:last-question s2)))
      (is (= {:forked-from "s1" :forked-before-turn 2} (select-keys s2 [:forked-from :forked-before-turn]))))
    (is (= 3 (:turn-count (persist/read-session c "s1"))) "the session it came from is as it was")
    (testing "before turn 1 is a session with nothing said yet"
      (persist/fork! c "s1" "s3" {:before-turn 1})
      (is (= {:previous-turns [] :transcript [] :turn-count 0}
             (select-keys (persist/read-session c "s3") [:previous-turns :transcript :turn-count]))))
    (testing "entries saved before turns were numbered are counted back from the newest"
      (let [snap {:turn-count 5 :previous-turns (mapv #(dissoc (t %) :turn) [3 4 5])
                  :transcript [[:answer "a2"] [:you "q3"] [:you "q4"] [:you "q5"]]}]
        (is (= ["q3"] (map :question (:previous-turns (persist/cut-before snap 4)))))
        (is (= [[:answer "a2"] [:you "q3"]] (:transcript (persist/cut-before snap 4))))
        (is (= [] (:transcript (persist/cut-before snap 2))) "a turn the window let go: all of it came after")))
    (testing "a turn it cannot be cut before"
      (is (re-find #"turns 1–3, not 4" (:problem (persist/fork! c "s1" "s4" {:before-turn 4}))))
      (is (re-find #"inside the summary /compact made of turns 1–4"
                   (:problem (persist/cut-before {:turn-count 6 :previous-turns [{:question "(turns 1–4, compacted)" :compacted 4} (t 5) (t 6)]} 3)))))))


(deftest a-note-is-left-for-a-session-s-next-turn
  ;; RFC-069 (byj-gly.488): what was done to a session's files from outside
  ;; it, kept with the session until its next turn takes it
  (let [dir (fresh-dir "notes")
        c (config dir)]
    (is (not (persist/note! c "nobody" "x")) "no session there: nothing left")
    (is (not (.exists (persist/session-dir c "nobody"))))
    (persist/save! c "s1" {:turn-count 1 :previous-turns [] :transcript []})
    (is (persist/note! c "s1" "first"))
    (is (persist/note! c "s1" "second"))
    (is (= ["first" "second"] (persist/take-notes! c "s1")) "oldest first")
    (is (nil? (persist/take-notes! c "s1")) "and taken once")
    (testing "a session held by a live process and not saved"
      (is (persist/lock! c "s-live"))
      (is (persist/note! c "s-live" "x"))
      (is (= ["x"] (persist/take-notes! c "s-live")))
      (persist/unlock! c "s-live"))
    (is (= ["s1"] (map :id (remove :problem (persist/summaries c)))) "a note is not a session")))

;; byj-s5h: a client loading a session shows each turn's recap under it
(deftest each-turns-recap-is-kept-by-its-turn
  (let [dir (fresh-dir "recaps")
        c (config dir)
        t (fn [n] {:question (str "q" n) :answer (str "a" n) :turn n})
        recaps {1 {:recap "r1"} 3 {:recap "r3" :next-step "then 4"}}]
    (persist/save! c "s1" {:turn-count 3 :previous-turns [(t 1) (t 2) (t 3)] :recaps recaps})
    (let [snap (persist/read-session c "s1")]
      (is (nil? (:problem snap)))
      (is (= recaps (:recaps snap)))
      (is (= recaps (:recaps (persist/restore snap))) "a resume keeps them, so its next save does too"))
    (testing "a fork keeps those of the turns it keeps"
      (persist/fork! c "s1" "s2" {:before-turn 3})
      (is (= {1 {:recap "r1"}} (:recaps (persist/read-session c "s2")))))
    (testing "the newest are kept"
      (persist/save! c "s1" {:turn-count 3 :previous-turns [] :recaps (into {} (for [n (range 1 (+ 6 persist/recaps-kept))] [n {:recap (str n)}]))})
      (let [kept (:recaps (persist/read-session c "s1"))]
        (is (= persist/recaps-kept (count kept)))
        (is (= (+ 5 persist/recaps-kept) (key (last (sort-by key kept)))))))))

(deftest only-persist-says-where-a-session-is-kept
  ;; byj-7sp.2: every path to what a session leaves — its folder, its
  ;; transcripts, its tasks — is built by persist/state-dir, so the place can
  ;; move in one fn (RFC-016's revision); a namespace that builds one itself
  ;; would be left behind by the move
  (let [offenders (for [f (file-seq (io/file "src/brainyard"))
                        :when (and (.isFile f) (str/ends-with? (.getName f) ".clj")
                                   (not= "persist.clj" (.getName f)))
                        [i line] (map-indexed vector (str/split-lines (slurp f)))
                        :when (re-find #"\"\.brainyard\"\s+\"(sessions|transcripts|tasks)\"" line)]
                    (str (.getName f) ":" (inc i) " " (str/trim line)))]
    (is (empty? offenders) (str/join "\n" offenders))))

(deftest the-projects-home-is-the-runs-own-in-a-test
  ;; byj-84c.1: a project's sessions are kept in its folder of the registry,
  ;; whose home resolves as it always did — the property, BY_PROJECTS_HOME,
  ;; $HOME — and a test run's is its own, never the person's
  (let [c (config (fresh-dir "projects-home"))]
    (is (= (io/file (projects/projects-root) (projects/project-slug (persist/project-of c)))
           (persist/project-home c)))
    (is (str/starts-with? (projects/projects-root) (System/getProperty "brainyard.projects-home"))
        "the run's own")
    (is (not (str/starts-with? (.getPath (persist/project-home c))
                               (str (io/file (System/getenv "HOME") ".brainyard" "projects"))))
        "never the person's in a test")))

;; --- sessions in the person's home (RFC-016's revision, byj-7sp.4) ---------------------

(defn- legacy-session!
  "A session as a byj before the revision kept it: under the project's own
  .brainyard/, with a transcript beside it, held by `pid` when given."
  [dir id & [pid]]
  (let [d (io/file dir ".brainyard" "sessions" id)]
    (.mkdirs d)
    (spit (io/file d "session.edn")
          (pr-str {:schema-version persist/schema-version :id id :created-at 1 :updated-at 2
                   :working-dir (.getCanonicalPath (io/file dir)) :project-dir (.getCanonicalPath (io/file dir))
                   :turn-count 1 :usage {} :previous-turns [{:question "what was here?" :answer "a file"}]}))
    (when pid (spit (io/file d "lock.edn") (pr-str {:pid pid :at 1})))
    (let [t (io/file dir ".brainyard" "transcripts" id "turn-1")] (.mkdirs t) (spit (io/file t "r.edn") "x"))
    d))

(deftest a-session-is-kept-in-the-persons-home
  (let [dir (fresh-dir "home-kept")
        c (config dir)
        root (projects/projects-root)]
    (persist/save! c "1700000000001-aaaaaaaa" {:turn-count 1 :previous-turns turns})
    (testing "under <home>/projects/<the project's slug>/sessions, not the project's .brainyard/"
      (is (.isFile (persist/session-file c "1700000000001-aaaaaaaa")))
      (is (= (.getCanonicalPath (io/file root (projects/project-slug dir) "sessions" "1700000000001-aaaaaaaa"))
             (.getCanonicalPath (persist/session-dir c "1700000000001-aaaaaaaa"))))
      (is (not (.exists (io/file dir ".brainyard" "sessions")))))
    (testing "the folder holds the registry's record of it, unlisted until it is registered (S5)"
      (let [rec (first (filter #(= (persist/project-of c) (:path %)) (projects/list-projects root {:unlisted? true})))]
        (is (false? (:listed? rec)))
        (is (= (projects/project-slug dir) (:slug rec))))
      (is (not-any? #(= (persist/project-of c) (:path %)) (projects/list-projects root)) "left out of the list")
      (projects/register-project! root dir)
      (is (some #(= (persist/project-of c) (:path %)) (projects/list-projects root)) "listed once registered"))
    (testing "a worktree, another path, is a project folder of its own"
      (let [wt (fresh-dir "home-kept-worktree")]
        (is (not= (persist/sessions-dir c) (persist/sessions-dir (config wt))))
        (is (empty? (persist/summaries (config wt))))))))

(deftest a-session-kept-before-is-moved-the-first-time-its-project-is-touched
  ;; on its own only into the person's home: a test's home stands in for it
  ;; here, as auto-migrate? would refuse it (it is a home given)
  (let [dir (fresh-dir "home-moved")
        c (config dir)]
    (legacy-session! dir "1700000000002-bbbbbbbb")
    (is (= ["1700000000002-bbbbbbbb"] (map :id (with-redefs [persist/auto-migrate? (constantly true)] (persist/summaries c))))
        "listed")
    (testing "moved, with its transcripts, and the old folders gone"
      (is (.isFile (io/file (persist/sessions-dir c) "1700000000002-bbbbbbbb" "session.edn")))
      (is (.isFile (io/file (persist/transcripts-dir c) "1700000000002-bbbbbbbb" "turn-1" "r.edn")))
      (is (not (.exists (io/file dir ".brainyard" "sessions"))))
      (is (not (.exists (io/file dir ".brainyard" "transcripts")))))
    (testing "and it resumes from there"
      (is (= 1 (:turn-count (persist/read-session c "1700000000002-bbbbbbbb")))))))

(deftest a-session-an-older-byj-holds-stays-and-is-listed-until-it-is-free
  ;; pid 1 is alive and not ours: an older byj still writing where it always did
  (let [dir (fresh-dir "home-held")
        c (config dir)]
    (legacy-session! dir "1700000000003-cccccccc" "1")
    (legacy-session! dir "1700000000004-dddddddd")
    (is (= #{"1700000000003-cccccccc" "1700000000004-dddddddd"}
           (set (map :id (with-redefs [persist/auto-migrate? (constantly true)] (persist/summaries c))))))
    (is (.isDirectory (io/file dir ".brainyard" "sessions" "1700000000003-cccccccc")) "the held one stayed")
    (is (= (.getCanonicalPath (io/file dir ".brainyard" "sessions" "1700000000003-cccccccc"))
           (.getCanonicalPath (persist/session-dir c "1700000000003-cccccccc")))
        "and is read where it is")
    (is (.isFile (io/file (persist/sessions-dir c) "1700000000004-dddddddd" "session.edn")) "the free one moved")
    (testing "migrate! says what it left"
      (is (= ["sessions/1700000000003-cccccccc"] (:left (persist/migrate! c)))))))

(deftest a-test-run-never-moves-a-projects-sessions-on-its-own
  ;; byj-7sp.4: a test that touched this repository (its git root, the project
  ;; of a turn given no :project-dir) moved the developer's own sessions into
  ;; the test run's home, which the next run empties. In a home given — a
  ;; test's — they are read where they are and never moved on their own
  (let [dir (fresh-dir "home-not-moved")
        c (config dir)]
    (legacy-session! dir "1700000000005-eeeeeeee")
    (is (false? (persist/auto-migrate?)) "a test run's home is a home given")
    (is (= ["1700000000005-eeeeeeee"] (map :id (persist/summaries c))) "listed where it is")
    (is (.isFile (io/file dir ".brainyard" "sessions" "1700000000005-eeeeeeee" "session.edn")) "and left there")
    (is (= 1 (:turn-count (persist/read-session c "1700000000005-eeeeeeee"))) "and read there")))

(deftest a-gone-projects-sessions-are-kept-until-the-ttl-then-go
  ;; byj-7sp.5: a worktree removed, a temporary project deleted — its sessions
  ;; stay in the home until :session-ttl-days, no keep count saving them, and
  ;; the project's folders go with its last
  (let [gone (str (.getCanonicalPath (io/file "target/persist-test")) "/gone-" (System/nanoTime))
        gc {:project-dir gone :working-dir gone}
        sd (persist/sessions-dir gc)
        old-ms (- (System/currentTimeMillis) (* 20 24 60 60 1000))
        session! (fn [id at & [pid]]
                   (let [d (io/file sd id)]
                     (.mkdirs d)
                     (spit (io/file d "session.edn") (pr-str {:id id :updated-at at}))
                     (when pid (spit (io/file d "lock.edn") (pr-str {:pid pid :at 1})))
                     (let [t (io/file (persist/transcripts-dir gc) id)] (.mkdirs t))
                     d))
        here (config (fresh-dir "prune-gone-here"))]
    (.mkdirs sd)
    (session! "1700000000010-aaaaaaaa" old-ms)
    (session! "1700000000011-bbbbbbbb" (System/currentTimeMillis))
    (session! "1700000000012-cccccccc" old-ms "1")
    (testing "past the TTL it goes, with its transcripts; a recent one and a held one stay"
      (is (= ["1700000000010-aaaaaaaa"] (filterv #(str/starts-with? % "17000000000") (:removed (persist/prune! here)))))
      (is (not (.exists (io/file sd "1700000000010-aaaaaaaa"))))
      (is (not (.exists (io/file (persist/transcripts-dir gc) "1700000000010-aaaaaaaa"))))
      (is (.isDirectory (io/file sd "1700000000011-bbbbbbbb")))
      (is (.isDirectory (io/file sd "1700000000012-cccccccc")) "held by a live process"))
    (testing "the project's folders go with its last session"
      (doseq [id ["1700000000011-bbbbbbbb" "1700000000012-cccccccc"]]
        (let [d (io/file sd id)]
          (io/delete-file (io/file d "lock.edn") true)
          (spit (io/file d "session.edn") (pr-str {:id id :updated-at old-ms}))))
      (persist/prune! here)
      (is (not (.exists sd)))
      (is (not (.exists (persist/transcripts-dir gc))))
      (is (not (.exists (persist/project-home gc))) "never registered: its record goes with it (S5)"))
    (testing "a registered project that is gone keeps its record, listed missing, until a person forgets it"
      (let [gone2 (str gone "-registered")
            gc2 {:project-dir gone2 :working-dir gone2}
            d (io/file (persist/sessions-dir gc2) "1700000000014-eeeeeeee")]
        (.mkdirs d)
        (spit (io/file d "session.edn") (pr-str {:id "1700000000014-eeeeeeee" :updated-at old-ms}))
        (projects/register-project! (projects/projects-root) gone2)
        (persist/prune! here)
        (is (not (.exists d)))
        (is (not (.exists (persist/sessions-dir gc2))))
        (is (:missing? (first (filter #(= gone2 (:path %)) (projects/list-projects (projects/projects-root))))))))
    (testing "a project that is there keeps its sessions by the rules it had"
      (persist/save! here "1700000000013-dddddddd" {:turn-count 1 :previous-turns turns})
      (persist/prune! (config (fresh-dir "prune-gone-other")))
      (is (.isFile (persist/session-file here "1700000000013-dddddddd"))))))
