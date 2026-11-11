(ns brainyard.schedule-test
  "Prompts set to a time (byj-gly.325): the cron, the interval /loop gives,
  and a job firing as a turn of the session that made it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.schedule :as s]))

(defn- fresh-dir [n]
  (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sched-" n "-" (System/nanoTime))) .mkdirs)))

(def ^:private fri-midnight-utc 1791504000000)   ; 2026-10-09T00:00:00Z, a Friday

(deftest a-cron-names-the-next-minute-it-fires
  (testing "fields: lists, ranges, steps; Sunday as 0 or 7"
    (is (= #{0 15 30 45} (:minute (s/parse-cron "*/15 * * * *"))))
    (is (= #{1 2 3 4 5} (:dow (s/parse-cron "0 9 * * 1-5"))))
    (is (= #{0} (:dow (s/parse-cron "0 9 * * 7"))))
    (is (nil? (s/parse-cron "0 9 * *")) "four fields")
    (is (nil? (s/parse-cron "61 * * * *")) "out of range"))
  (testing "the next minute, in local time"
    (is (= "2026-10-09T09:00:00Z" (str (java.time.Instant/ofEpochMilli
                                         (s/next-fire-after (s/parse-cron "*/15 9-17 * * 1-5") fri-midnight-utc 0)))))
    (is (= "2026-10-10T00:00:00Z" (str (java.time.Instant/ofEpochMilli
                                         (s/next-fire-after (s/parse-cron "0 9 * * *") fri-midnight-utc (* 9 3600)))))
        "09:00 at UTC+9 is midnight UTC; 09:00 today has just passed, so tomorrow's")
    (is (= "2026-10-12T09:00:00Z" (str (java.time.Instant/ofEpochMilli
                                         (s/next-fire-after (s/parse-cron "0 9 * * 1") fri-midnight-utc 0))))
        "the next Monday")))

(deftest an-interval-is-a-clean-cron-as-claude-codes-loop-makes-it
  (is (= ["*/5 * * * *" nil] ((juxt :cron :rounded) (s/interval->cron "5m"))))
  (is (= ["*/6 * * * *" "7m → 6m"] ((juxt :cron :rounded) (s/interval->cron "7m"))) "one that does not divide the hour is rounded, and said")
  (is (= ["* * * * *" nil] ((juxt :cron :rounded) (s/interval->cron "30s"))) "seconds round up to a minute")
  (is (= ["0 */2 * * *" "90m → 2h"] ((juxt :cron :rounded) (s/interval->cron "90m"))))
  (is (= ["0 */2 * * *" nil] ((juxt :cron :rounded) (s/interval->cron "2h"))))
  (is (= "0 0 * * *" (:cron (s/interval->cron "1d"))))
  (is (nil? (s/interval->cron "soon")))
  (is (= [90000 1200000 5000 nil] [(s/delay-ms "90s") (s/delay-ms "20m") (s/delay-ms 5000) (s/delay-ms "x")])))

(deftest a-due-job-is-its-sessions-next-turn
  (let [p (fresh-dir "fire")
        t0 fri-midnight-utc
        every (s/add! p {:prompt "check the build" :cron "*/10 * * * *" :session "s1"} t0)
        once (s/add! p {:prompt "/loop wait on CI" :in "15m" :session "s1"} t0)
        theirs (s/add! p {:prompt "theirs" :in "1m" :session "s2"} t0)]
    (is (= (+ t0 s/recurring-ttl-ms) (:expires-at every)) "a recurring job lives seven days")
    (is (nil? (:expires-at once)))
    (testing "nothing is due yet"
      (is (nil? (s/ready-turn! p "s1" t0))))
    (testing "a cron fires, then waits for its next minute"
      (is (= "check the build" (s/ready-turn! p "s1" (+ t0 (* 10 60000)))))
      (is (nil? (s/ready-turn! p "s1" (+ t0 (* 10 60000) 1000))))
      (is (= (+ t0 (* 20 60000)) (:next-fire (s/read-spec p (:id every))))))
    (testing "a one-shot fires once, and is gone — the job due longest first"
      (is (= "/loop wait on CI" (s/ready-turn! p "s1" (+ t0 (* 20 60000) 1))))
      (is (nil? (s/read-spec p (:id once))))
      (is (= "check the build" (s/ready-turn! p "s1" (+ t0 (* 20 60000) 2))) "then the cron that was due with it"))
    (testing "another session's job is not this one's"
      (is (nil? (s/ready-turn! p "s1" (+ t0 (* 25 60000)))))
      (is (= "theirs" (s/ready-turn! p "s2" (+ t0 (* 25 60000))))))
    (testing "run now: the next idle moment, its schedule after unchanged"
      (s/run-now! p (:id every) (+ t0 (* 21 60000)))
      (is (= "check the build" (s/ready-turn! p "s1" (+ t0 (* 21 60000)))))
      (is (= (+ t0 (* 30 60000)) (:next-fire (s/read-spec p (:id every))))))
    (testing "disabled, it does not fire; expired, it is removed"
      (s/set-enabled! p (:id every) false)
      (is (nil? (s/ready-turn! p "s1" (+ t0 (* 30 60000)))))
      (s/set-enabled! p (:id every) true (+ t0 (* 30 60000)))
      (is (nil? (s/ready-turn! p "s1" (+ t0 s/recurring-ttl-ms 1))))
      (is (nil? (s/read-spec p (:id every)))))))

(deftest jobs-are-listed-with-what-a-person-must-know
  (let [p (fresh-dir "list")]
    (is (str/starts-with? (s/listing p "s1") "No scheduled prompts or watches."))
    (s/add! p {:prompt "check the build" :cron "*/10 * * * *" :session "s1"})
    (let [l (s/listing p "s1")]
      ;; within a minute of a :x0, the next firing is "in under a minute" — the
      ;; suite ran at 09:29 once and this failed (byj-gly.37's suite run)
      (is (re-find #"check-the-build-\d+  in (\d+ min|under a minute)  cron \*/10 \* \* \* \*  · expires in (6|7) d" l))
      (is (str/includes? l "fire only while this session is open")))))

(deftest the-commands-refuse-where-no-turn-would-run
  (let [add (:fn (first (filter #(= "schedule$add" (:name %)) (s/commands {}))))
        p (fresh-dir "cmd")]
    (is (str/includes? (:error (add {:prompt "x" :in "5m"} {:config {:project-dir p}})) "one-shot run"))
    (let [r (add {:prompt "x" :in "5m"} {:config {:project-dir p :session-id "s1"} :wakeups (atom [])})]
      (is (:id r))
      (is (= "s1" (:session (s/read-spec p (:id r))))))
    (is (str/includes? (:error (add {:prompt "x" :cron "bad"} {:config {:project-dir p} :wakeups (atom [])})) "invalid :cron"))
    (is (str/includes? (:error (add {:prompt "x"} {:config {:project-dir p} :wakeups (atom [])})) "give :cron"))))

(deftest the-model-is-shown-the-schedule-tools
  ;; byj-gly.325, live: registered, listed by /tools, but left out of the
  ;; prompt's listing — Haiku said it had no schedule$add and the loop ended
  (let [ctx (agent/make-ctx {:config {:project-dir (fresh-dir "listed") :working-dir "." :compact-agent-tools true
                                      :tool-discovery? true}})
        listing (get-in ctx [:zones :session-context])]
    (is (re-find #"- `schedule\$…` \(7\) — turns of this session set to come later" (str listing)))
    (is (str/includes? (str listing) "`schedule$watch`"))
    (is (str/includes? (str listing) "`schedule$add`")))
  (testing "a subagent has none: its turn ends before a job could fire"
    (let [ctx (agent/make-ctx {:config {:project-dir (fresh-dir "sub") :working-dir "." :agent-instance-id "x/y"
                                        :compact-agent-tools true :tool-discovery? true}})]
      (is (not (str/includes? (str (get-in ctx [:zones :session-context])) "schedule$add"))))))

(deftest a-watch-is-told-what-changed-as-claude-codes-monitor
  ;; byj-gly.331: brainyard's watch$add, firing a turn of its session
  (testing "brainyard's predicates; the first look is the baseline"
    (is (not (s/predicate-met? {:op :changed} nil {:value "a"})) "a first look sets the baseline")
    (is (s/predicate-met? {:op :changed} {:value "a"} {:value "b"}))
    (is (not (s/predicate-met? {:op :changed} {:value "a"} {:value "a"})))
    (is (s/predicate-met? {:op :matches :re "ok|FAIL"} nil {:value "tests: FAIL"}))
    (is (s/predicate-met? {:op :zero-exit} nil {:value "" :exit 0}))
    (is (s/predicate-met? {:op :nonzero-exit} nil {:value "" :exit 2}))
    (is (s/predicate-met? {:op :increased} {:value "3"} {:value "5"}))
    (is (s/predicate-met? {:op :threshold :cmp :ge :value "90"} nil {:value "92"})))
  (let [p (fresh-dir "watch")
        f (io/file p "build.log")
        _ (spit f "building")
        w (s/add-watch! p {:cmd "cat build.log" :every "5s" :session "s1" :note "tell the person the result"})]
    (is (= [:watch {:type :shell :cmd "cat build.log"} {:op :changed} true 5000]
           ((juxt :kind :probe :when :once :every) w)))
    (testing "a look that changes nothing fires nothing"
      (is (nil? (:pending (s/run-watch! p (:id w)))))
      (is (= "building" (get-in (s/read-spec p (:id w)) [:last-observation :value])))
      (is (nil? (:pending (s/run-watch! p (:id w))))))
    (testing "a change waits for the session's next idle moment, then is its turn — once, and the watch is gone"
      (spit f "build ok")
      (is (some? (:pending (s/run-watch! p (:id w)))))
      (let [turn (s/ready-turn! p "s1" (System/currentTimeMillis) (fn [_] nil))]
        (is (str/starts-with? turn (str "[watch " (:id w) "] cat build.log — fired: what it saw changed.")))
        (is (str/includes? turn "Saw: build ok  [exit 0]"))
        (is (str/includes? turn "Your note: tell the person the result"))
        (is (str/includes? turn "The watch has stopped.")))
      (is (nil? (s/read-spec p (:id w))))))
  (testing "the idle poll starts a due probe in the background and does not wait on it"
    (let [p (fresh-dir "watch-bg")
          w (s/add-watch! p {:cmd "echo FAIL" :when {:op :matches :re "FAIL"} :once false :session "s1"})
          started (atom [])
          now (System/currentTimeMillis)]
      (is (nil? (s/ready-turn! p "s1" now #(swap! started conj %))) "nothing to say yet")
      (is (= 1 (count @started)) "one probe started")
      (is (number? (:probing (s/read-spec p (:id w)))))
      (is (nil? (s/ready-turn! p "s1" now #(swap! started conj %))))
      (is (= 1 (count @started)) "not again while it runs, nor before its next look")
      ((first @started))
      (let [turn (s/ready-turn! p "s1" now (fn [_] nil))]
        (is (str/includes? turn "fired: it matched /FAIL/"))
        (is (str/includes? turn "It keeps watching")))
      (is (some? (s/read-spec p (:id w))) "kept: :once false")))
  (testing "a file, and the refusals"
    (let [p (fresh-dir "watch-file")]
      (is (= {:type :file :path "out/done"} (:probe (s/add-watch! p {:path "out/done"}))))
      (is (= {:value nil :exit 1} (s/probe! p {:type :file :path "out/done"})))
      (is (str/includes? (:error (s/add-watch! p {})) "give :cmd"))
      (is (str/includes? (:error (s/add-watch! p {:cmd "x" :when {:op :matches}})) ":matches needs :re")))))

