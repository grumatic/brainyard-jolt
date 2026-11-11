(ns brainyard.eventlog-test
  "The event log (RFC-026): the stream written down, and read back.

  The point of it is a question a live viewer cannot answer — *did this happen
  in an earlier run?* — so the tests are mostly about what survives: rotation,
  a torn tail, and a run that was told to leave nothing behind."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.eventlog :as el]
            [brainyard.events :as events]
            [brainyard.json :as json]
            [brainyard.tools :as tools]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

(defn- cfg [d] {:working-dir (.getPath d) :project-dir (.getPath d) :session-id "s1"})

(defn- replying [replies]
  {:provider :mock :format :mock :model "mock-1"
   :responses (let [left (atom (vec replies))]
                (fn [_] (let [r (first @left)]
                          (swap! left #(if (next %) (vec (rest %)) %))
                          (json/write-str r))))})

;; --- what is written ------------------------------------------------------------------

(deftest a-run-writes-down-what-happened
  (let [d (fresh-dir "eventlog-run")
        c (cfg d)
        sink (el/sink c)]
    (sink :session.turn/start {:turn 2 :question "q"})
    (sink :tools/user-tool-skipped {:file "/p/x.edn" :problems ["bad name"]})
    (let [[newest older] (el/read-events c {})]
      (is (= :tools/user-tool-skipped (:event newest)))
      (is (= :problem (:level newest)) "the level travels with the record")
      (is (= "s1" (:session newest)))
      (is (= 2 (:turn newest)) "the turn the sink learned from the stream itself")
      (is (= "/p/x.edn" (get-in newest [:data :file])))
      (is (number? (:ts newest)))
      (is (= :session.turn/start (:event older)) "newest first"))))

(deftest the-chatty-events-are-written-too-now
  ;; they were held back because a prompt and a reply belonged to the prediction
  ;; log, which was opt-in. The log is the one source of truth now and the
  ;; dataset derives from it, so they are written — all but the streamed
  ;; fragments, which are the same reply a second time.
  (let [d (fresh-dir "eventlog-chatty")
        c (cfg d)
        sink (el/sink c)]
    (doseq [e [:agent.llm/chunk :agent.llm/request :agent.llm/response]]
      (sink e {:text "x" :messages [] :content "y"}))
    (sink :agent.iteration/start {:iteration 1})
    (is (= [:agent.iteration/start :agent.llm/response :agent.llm/request]
           (mapv :event (el/read-events c {})))
        "newest first; the chunk is the only one missing")

    (testing "and the catalogue says so, rather than the log deciding alone"
      (is (not (el/logged? :agent.llm/chunk)))
      (is (el/logged? :agent.llm/request))
      (is (el/logged? :agent.tools/calls)))

    (testing "an event nobody classified is written — a log holds what nobody
              thought to ask for"
      (is (el/logged? :made-up/event)))))

(deftest the-one-event-not-written-says-why-it-is-redundant
  ;; it used to be a class of events excluded for being too full of the model's
  ;; own text, and the entry had to say where they went instead. Nothing is
  ;; excluded for size any more, so the only permissible reason is that the
  ;; information is already recorded elsewhere — and the doc has to say so.
  (doseq [[event {:keys [log? doc]}] events/catalogue
          :when (false? log?)]
    (testing (str event)
      (is (str/includes? doc "already")
          "an entry left out must name what already holds the same thing"))))

(deftest the-log-is-bounded-and-what-rotates-is-still-readable
  (let [d (fresh-dir "eventlog-rotate")
        c (assoc (cfg d) :event-log-max-bytes 400 :event-log-keep 2)
        sink (el/sink c)]
    (dotimes [i 40] (sink :agent.iteration/start {:iteration i}))
    (let [f (el/log-file c)
          dir (.getParentFile f)
          backups (filter #(str/includes? (.getName %) ".log.") (.listFiles dir))]
      (is (< (.length f) 2000) "the live file stayed small")
      (is (seq backups) "and what left it was kept")
      (is (<= (count backups) 2) "up to :event-log-keep of them")

      (testing "a read spans the rotation, so the recent past does not vanish at a boundary"
        (let [live (count (remove str/blank? (str/split-lines (slurp f))))
              seen (mapv #(get-in % [:data :iteration]) (el/read-events c {:limit 100}))]
          (is (> (count seen) live)
              "more events than the live file alone holds — the backups were read too")
          (is (= seen (reverse (sort seen))) "still newest first across files")
          (is (= 39 (first seen)) "and the newest event really is the newest")))

      (testing "what rotated out is gone, which is what bounded means"
        (is (< (count (el/read-events c {:limit 100})) 40))))))

(deftest a-torn-line-costs-only-itself
  (let [d (fresh-dir "eventlog-torn")
        c (cfg d)
        sink (el/sink c)]
    (sink :agent.iteration/start {:iteration 1})
    (sink :agent.iteration/start {:iteration 2})
    (spit (el/log-file c) "{:ts 1 :event :half" :append true)
    (is (= [2 1] (mapv #(get-in % [:data :iteration]) (el/read-events c {}))))))

(deftest a-run-told-to-leave-nothing-behind-writes-no-log
  ;; a payload carries the question, the tool arguments and the results, so this
  ;; is a record of the conversation whatever else it is (RFC-025 makes the same
  ;; call for the trajectory)
  (let [d (fresh-dir "eventlog-no-persist")]
    (is (nil? (el/log-file (assoc (cfg d) :persist-session? false))))
    (is (nil? (el/sink (assoc (cfg d) :persist-session? false))))
    (is (nil? (el/log-file (assoc (cfg d) :event-log? false))))
    (is (some? (el/log-file (cfg d))) "on otherwise")))

(deftest the-log-outlives-the-session-it-describes
  ;; it sits outside sessions/ on purpose: those are pruned (RFC-016), and a log
  ;; swept away with the session it describes answers nothing later
  (let [d (fresh-dir "eventlog-outside")
        f (el/log-file (cfg d))]
    (is (= "logs" (.getName (.getParentFile f))))
    (is (not (str/includes? (.getPath f) "sessions")))))

;; --- through the loop, and back out through a command --------------------------------

(deftest a-later-run-can-ask-what-an-earlier-one-did
  (let [d (fresh-dir "eventlog-ask")
        c (cfg d)]
    ;; run one: a malformed user tool is rejected
    (.mkdirs (io/file d ".brainyard" "tools"))
    (spit (io/file d ".brainyard" "tools" "broken.edn")
          (pr-str {:name "broken tool" :description "malformed"}))
    (let [sink (el/sink c)]
      (agent/ask "first" {:lm (replying [{:reasoning "r" :tool-calls [] :answer "a"}])
                          :config c
                          :on-event (fn [e data] (sink e data))}))

    ;; run two: the commands are there, and they find it
    (let [ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})
          events-cmd (get @(:tools ctx) "log$events")
          search-cmd (get @(:tools ctx) "log$search")]
      (is (some? events-cmd) "log$events is registered")
      (is (some? search-cmd))

      (testing "asking for problems finds the rejected tool from the earlier run"
        (let [r (tools/call-tool events-cmd {:level "problem"} ctx)]
          (is (pos? (:count r)))
          (is (some #(= :tools/user-tool-skipped (:event %)) (:events r)))))

      (testing "and so does searching for what it said"
        (let [r (tools/call-tool search-cmd {:query "lowercase kebab"} ctx)]
          (is (pos? (:count r)))
          (is (str/includes? (pr-str (:events r)) "broken.edn"))))

      (testing "a search with no query is refused rather than returning everything"
        (is (:error (tools/call-tool search-cmd {:query "  "} ctx))))

      (testing "a :quiet event is not SHOWN, but it is written down — the level
                is a viewer's business and the log holds what happened"
        (is (pos? (:count (tools/call-tool events-cmd {:level "quiet"} ctx))))))))

(deftest a-filter-nobody-can-satisfy-says-so-rather-than-returning-nothing
  ;; byj-gly.84, found by running this agent on its own repository: it asked
  ;; for :level "error" and :level "warn" — neither is a level — got 0 events
  ;; from both, and reported that nothing had gone wrong. A query that cannot
  ;; match has to say it cannot, because 0 results is what all-clear looks like.
  (let [d (fresh-dir "eventlog-badfilter")
        c (cfg d)
        sink (el/sink c)
        _ (sink :tools/user-tool-skipped {:file "/p/x.edn" :problems ["bad"]})
        ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})
        cmd (get @(:tools ctx) "log$events")]
    (testing "a level that does not exist names the ones that do"
      (let [r (tools/call-tool cmd {:level "error"} ctx)]
        (is (nil? (:count r)))
        (is (str/includes? (:error-message r) "not a level"))
        (is (str/includes? (:error-message r) "problem"))))

    (testing "an event that does not exist suggests the near ones"
      (let [r (tools/call-tool cmd {:event "user-tool"} ctx)]
        (is (str/includes? (:error-message r) "not an event"))
        (is (str/includes? (:error-message r) "tools/user-tool-skipped"))))

    (testing "and with nothing close, it says how many there are"
      (is (str/includes? (:error-message (tools/call-tool cmd {:event "zzz/nope"} ctx))
                         "log$search")))

    (testing "the real filters still work, so the guard is not the whole answer"
      (is (= 1 (:count (tools/call-tool cmd {:level "problem"} ctx))))
      (is (= 1 (:count (tools/call-tool cmd {:event "tools/user-tool-skipped"} ctx)))))))

(deftest a-predictor-call-is-written-whole-and-read-back-trimmed
  ;; byj-gly.85 stopped :inputs/:outputs/:reasoning being WRITTEN, because the
  ;; prediction log they duplicate was opt-in and logging them here made that
  ;; promise weaker than it read. The log is the one source of truth now and a
  ;; dataset is derived from it, so they are written — and the promise moves to
  ;; the read, where it costs nothing.
  (let [d (fresh-dir "eventlog-predictor")
        c (cfg d)
        sink (el/sink c)]
    (sink :predictor/call {:predictor-id "react/think-act"
                           :inputs {:question "something private" :iterations "[…]"}
                           :outputs {:answer "also private"}
                           :reasoning "private thinking"
                           :usage {:input-tokens 27628}
                           :elapsed-ms 7600
                           :params-source :default})
    (testing "the file has everything — a dataset is built from it"
      (let [[r] (el/read-events c {:full? true})]
        (is (= "something private" (get-in r [:data :inputs :question])))
        (is (= "also private" (get-in r [:data :outputs :answer])))
        (is (= "private thinking" (get-in r [:data :reasoning])))))
    (let [r (first (el/read-events c {}))]
      (testing "what it cost and where its params came from is returned"
        (is (= "react/think-act" (get-in r [:data :predictor-id])))
        (is (= 7600 (get-in r [:data :elapsed-ms])))
        (is (= 27628 (get-in r [:data :usage :input-tokens])))
        (is (= :default (get-in r [:data :params-source]))))
      (testing "what was said is not"
        (is (not (contains? (:data r) :inputs)))
        (is (not (contains? (:data r) :outputs)))
        (is (not (contains? (:data r) :reasoning)))
        (is (not (str/includes? (pr-str r) "private")))
        (is (= [:inputs :outputs :reasoning] (:withheld r))
            "and the record says what it is not showing, rather than looking complete"))))
  (testing "trimming happens BEFORE the match, so :match cannot probe for a phrase"
    (let [d (fresh-dir "eventlog-probe")
          c (cfg d)]
      ((el/sink c) :predictor/call {:predictor-id "p" :inputs {:question "hunting for this"}})
      (is (empty? (el/read-events c {:match "hunting"})) "no hit through the trimmed view")
      (is (= 1 (count (el/read-events c {:match "hunting" :full? true}))) "but it is in the file"))))

(deftest the-commands-are-absent-when-the-log-is
  (let [d (fresh-dir "eventlog-absent")
        ctx (agent/make-ctx {:config (assoc (cfg d) :event-log? false)
                             :tools (vec tools/builtin-tools)})]
    (is (nil? (get @(:tools ctx) "log$events")))
    (is (nil? (get @(:tools ctx) "log$search")))))

(deftest a-long-payload-is-kept-whole-and-the-file-is-bounded-instead
  ;; it used to clip every string at 2000, which is why a dataset built from
  ;; this log lost 74 of 156 records. Rotation bounds the file now; the record
  ;; is bounded by nothing.
  (let [d (fresh-dir "eventlog-clip")
        c (cfg d)
        sink (el/sink c)]
    (sink :agent.tools/results {:results (str/join (repeat 9000 "x"))})
    (let [r (first (el/read-events c {:full? true}))]
      (is (= 9000 (count (get-in r [:data :results]))))
      (is (not (str/includes? (pr-str r) "…[truncated"))))))

(deftest the-log-is-lossless
  ;; it clipped every string at 2000 and trimmed :predictor/call to :log-keys,
  ;; and a second user-scoped archive existed to hold what it refused. One log
  ;; holds it all now: a dataset is derived from this file, so a record that
  ;; dropped :inputs or cut a prompt short would be a record nothing could be
  ;; rebuilt from.
  (let [d (fresh-dir "lossless")
        c (cfg d)
        sink (el/sink c)
        long-s (apply str (repeat 5000 "x"))]
    (sink :predictor/call {:predictor-id "user/double"
                           :inputs {:question long-s} :outputs {:doubled "4"}
                           :reasoning "because" :usage {:in 1 :out 2}})
    (let [[rec] (el/read-events c {:full? true})]
      (testing "the payload keeps every key — :log-keys used to drop these three"
        (is (= long-s (get-in rec [:data :inputs :question])))
        (is (= {:doubled "4"} (get-in rec [:data :outputs])))
        (is (= "because" (get-in rec [:data :reasoning]))))
      (testing "and nothing is truncated"
        (is (= 5000 (count (get-in rec [:data :inputs :question]))))
        (is (not (str/includes? (pr-str rec) "…[truncated")))))))

(deftest the-only-event-not-written-is-a-second-copy-of-one-that-is
  (is (false? (el/logged? :agent.llm/chunk))
      "a streamed fragment: :agent.llm/response already holds the whole reply")
  (is (false? (el/logged? :agent.tool/output))
      "a line a running tool printed: :agent.tools/results already holds its output (byj-gly.364)")
  (is (= #{:agent.llm/chunk :agent.tool/output}
         (set (map key (filter (fn [[_ v]] (false? (:log? v))) events/catalogue))))
      "and they are the ONLY ones — anything else excluded would be information lost")
  (testing "the events that used to be held back are written now"
    (is (el/logged? :agent.llm/request))
    (is (el/logged? :llm.cli/call))
    (is (el/logged? :predictor/call))))

(deftest whole-conversations-are-withheld-from-the-read-back
  ;; a resumed transcript, a subagent's answer and a recap are whole
  ;; conversations; log$events handed them back to the model (byj-gly.233)
  (let [r (el/trim-for-read {:event :session/resumed :data {:id "s" :transcript [[:you "q"]] :answer "a" :recap "r"}})]
    (is (= {:id "s"} (select-keys (:data r) [:id :transcript :answer :recap])))
    (is (= #{:transcript :answer :recap} (set (:withheld r))))))
