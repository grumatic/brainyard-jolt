(ns brainyard.analytics-test
  "Session analytics (RFC-025). The measurements are facts and are tested as
  arithmetic; the score is a judgement and is tested for the properties that
  make it worth reading — that it is bounded, that a missing metric is shared
  out rather than counted as zero, and that it never reports a number it cannot
  support."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.analytics :as an]
            [brainyard.tools :as tools]
            [brainyard.trajectory :as tr]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

(def ^:private s (delay (an/settings {})))

(defn- turn
  "A trajectory record, with only what a test cares about."
  [& {:keys [q ok its ms cost partial? in out cache-read cache-write tools]
      :or {q "q" ok true its 2 ms 1000}}]
  (cond-> {:v 3 :question q :success ok :total-iterations its :duration-ms ms
           :terminated-by (if ok :answer-channel :max-iterations-exhausted)
           ;; :in is the TOTAL input, cache included (RFC-006) — a fixture that
           ;; could not express :cache-write is what let `byj-gly.94` through
           :usage (cond-> {:in (or in 1000) :out (or out 100)}
                    cache-read (assoc :cache-read cache-read)
                    cache-write (assoc :cache-write cache-write))
           :iterations (if tools
                         [{:n 1 :channel "tool" :tools tools}]
                         [{:n 1 :channel "answer" :answer "a"}])}
    cost (assoc :cost cost)
    partial? (assoc :cost-partial? true)))

;; --- the measurements are arithmetic ------------------------------------------------

(deftest outcome-counts-what-answered-and-where-it-stopped
  (let [o (an/outcome [(turn :ok true) (turn :ok false) (turn :ok true)] @s)]
    (is (= 3 (:turns o)))
    (is (= 2 (:succeeded o)))
    (is (< 0.66 (:success-rate o) 0.67))
    (is (= 0 (:trailing-failures o)) "it recovered")
    (is (= {:answer-channel 2 :max-iterations-exhausted 1} (:terminated-by o))))

  (testing "a session that ENDED badly is worse than one that had a bad patch"
    (let [recovered (an/outcome [(turn :ok false) (turn :ok true)] @s)
          ended-bad (an/outcome [(turn :ok true) (turn :ok false)] @s)]
      (is (= (:success-rate recovered) (:success-rate ended-bad)) "same rate")
      (is (> (:score recovered) (:score ended-bad)) "different score")
      (is (= 1 (:trailing-failures ended-bad)))))

  (testing "the longest run of failures is reported, not just the count"
    (is (= 2 (:max-consecutive-failures
              (an/outcome [(turn :ok false) (turn :ok false) (turn :ok true) (turn :ok false)] @s)))))

  (is (nil? (an/outcome [] @s)) "no turns, nothing to say"))

(deftest iteration-and-latency-are-percentiles-not-averages-alone
  (let [i (an/iteration (map #(turn :its %) [1 2 3 10]) @s)]
    (is (= 4.0 (:avg i)))
    (is (= 3 (:p50 i)))
    (is (= 10 (:p90 i)) "the tail is what a p90 is for")
    (is (= 0.5 (:one-shot-rate i)) "two of four answered in <= 2"))

  (let [l (an/latency (map #(turn :ms %) [100 200 300 5000]) @s)]
    (is (= 5600 (:total-ms l)))
    (is (= 300 (:p50-ms l)))
    (is (= 5000 (:p90-ms l)))
    (is (pos? (:output-tokens-per-sec l)))
    (testing "the slowest turns are named by POSITION — a v3 record has no :turn"
      (is (= 3 (:at (first (:slowest l)))))
      (is (= 5000 (:duration-ms (first (:slowest l)))))))

  (testing "a record with no duration contributes nothing rather than a zero"
    (is (nil? (an/latency [(dissoc (turn) :duration-ms)] @s)))))

(deftest cache-and-tools-count-what-is-there
  (let [c (an/cache [(turn :in 1000 :cache-read 800) (turn :in 1000 :cache-read 200)] @s)]
    (is (= 1000 (:cached-input c)))
    (is (= 1000 (:fresh-input c)))
    (is (= 0.5 (:hit-rate c))))

  (let [t (an/tools [(turn :tools [{:name "grep" :args {:q "x"}}
                                   {:name "read-file" :args {:path "a"}}])
                     (turn :tools [{:name "grep" :args {:q "x"}}])]
                    @s)]
    (is (= 3 (:total-calls t)))
    (is (= 2 (:unique-tools t)))
    (is (= 1 (:redundant-calls t)) "the identical grep, called twice")
    (is (= {:tool "grep" :calls 2} (first (:top t)))))

  (is (nil? (an/tools [(turn)] @s)) "a session with no tool calls reports none"))

(deftest a-cache-write-is-neither-fresh-nor-a-miss
  ;; `byj-gly.94`, found by self-hosting. :in is the TOTAL input, so a turn's
  ;; tokens split three ways; brainyard's :in is the fresh count alone, and its
  ;; formula ported verbatim counted every write as fresh.
  (testing "the three buckets add up to the total"
    (let [c (an/cache [(turn :in 1000 :cache-read 700 :cache-write 250)] @s)]
      (is (= 700 (:cached-input c)))
      (is (= 250 (:written-input c)))
      (is (= 50 (:fresh-input c)) "not 300 — 250 of those were written, not fresh")
      (is (= (:in (:usage (turn :in 1000)))
             (+ (:cached-input c) (:written-input c) (:fresh-input c))))))

  (testing "a write is left out of the rate, so priming the cache is not a miss"
    ;; the shape of a real first turn: almost everything read or written,
    ;; a handful of genuinely new tokens
    (let [c (an/cache [(turn :in 60000 :cache-read 42701 :cache-write 17219)] @s)]
      (is (= 80 (:fresh-input c)))
      (is (< 0.99 (:hit-rate c))
          "before the fix this read 0.71, and the cost score was marked down for it")))

  (testing "a turn that only writes has no hit rate to report, rather than a zero"
    (let [c (an/cache [(turn :in 500 :cache-read 0 :cache-write 500)] @s)]
      (is (= 0 (:fresh-input c)))
      (is (nil? (:hit-rate c)) "0/0 is unknown, not bad — and mean drops a nil")))

  (testing "counts that do not add up never make a negative bucket"
    (let [c (an/cache [(turn :in 100 :cache-read 900 :cache-write 900)] @s)]
      (is (= 0 (:fresh-input c))))))

(deftest the-cost-score-follows-the-corrected-hit-rate
  ;; the bug was not cosmetic: cache/:hit-rate feeds analytics/cost's score
  (let [primed (an/cost [(turn :in 60000 :cache-read 42701 :cache-write 17219 :cost 0.26)] @s)
        churning (an/cost [(turn :in 60000 :cache-read 0 :cache-write 0 :cost 0.26)] @s)]
    (is (< 0.99 (:cache-hit-rate primed)))
    (is (= 0.0 (:cache-hit-rate churning)))
    (is (> (:score primed) (:score churning))
        "a turn that primed its cache must not score below one that cached nothing")))

(deftest the-error-rate-is-carried-not-pattern-matched
  ;; byj-gly.87. A record stores a tool result as a clipped STRING, so a
  ;; refusal cannot be told from a grep that found the word "refused". The
  ;; outcome is carried from the tool layer, which knew at the time.
  (let [t (an/tools [(turn :tools [{:name "read-file" :args {:path "/etc/x"} :error? true}
                                   {:name "grep" :args {:q "refused"} :result "Tool call refused (path)"}
                                   {:name "grep" :args {:q "y"}}])]
                    @s)]
    (is (= 3 (:total-calls t)))
    (is (= 1 (:failed-calls t)) "the one that actually failed")
    (is (< 0.33 (:error-rate t) 0.34))
    (testing "a successful result that MENTIONS a failure is not one"
      (is (not-any? #(= "grep" (:tool %)) (:failing t))))
    (testing "and which tool is failing is the actionable half"
      (is (= [{:tool "read-file" :failures 1}] (:failing t)))))

  (testing "a clean session has a zero rate, not a missing one"
    (let [t (an/tools [(turn :tools [{:name "grep" :args {:q "x"}}])] @s)]
      (is (= 0 (:failed-calls t)))
      (is (= 0.0 (:error-rate t)))
      (is (empty? (:failing t)))))

  (testing "failures cost the tool score"
    (let [clean (an/tools [(turn :tools [{:name "grep" :args {:q "x"}}])] @s)
          broken (an/tools [(turn :tools [{:name "grep" :args {:q "x"} :error? true}])] @s)]
      (is (< (:score broken) (:score clean)))))

  (testing "an identical call is redundant whether or not it failed"
    (is (= 1 (:redundant-calls
              (an/tools [(turn :tools [{:name "grep" :args {:q "x"}}
                                       {:name "grep" :args {:q "x"} :error? true}])]
                        @s))))))

;; --- what it refuses to say ----------------------------------------------------------

(deftest an-unpriced-turn-is-not-averaged-in-as-free
  ;; :cost-partial? means a turn's dollars are an understatement (RFC-025).
  ;; Averaging a real spend against a fabricated zero is what that flag exists
  ;; to prevent, so those turns are excluded and the exclusion is reported.
  (let [c (an/cost [(turn :cost 1.0 :ok true)
                    (turn :cost 0.0 :partial? true :ok true)] @s)]
    (is (= 1.0 (:total-cost c)) "the partial turn's zero is not in the total")
    (is (= 1 (:priced-turns c)))
    (is (= 1 (:unpriced-turns c)) "and the reader is told there was one")
    (is (= 1.0 (:cost-per-successful-turn c)) "one dollar, one win — not fifty cents"))

  (testing "a session where nothing could be priced reports no cost at all"
    (is (nil? (an/cost [(turn :partial? true) (turn :partial? true)] @s))))

  (testing "and one where everything could says nothing was missing"
    (is (nil? (:unpriced-turns (an/cost [(turn :cost 1.0)] @s))))))

(deftest a-metric-with-no-data-is-shared-out-not-counted-as-zero
  ;; which is what lets PQS be absent here at all
  (let [full (an/health {:oga 100 :ice 100 :lt 100 :tce 100 :tur 100} @s)
        some (an/health {:oga 100 :ice 100} @s)
        none (an/health {} @s)]
    (is (= 100 (:score full)))
    (is (= 100 (:score some)) "two perfect metrics are not 40% of a score")
    (is (= [:ice :oga] (:from some)))
    (is (= [:lt :tce :tur] (:missing some)) "and it says which it could not use")
    (is (nil? none) "nothing to score"))

  (testing "the grade follows the number, and a bad one is not rounded up to a letter"
    (is (= "A" (:grade (an/health {:oga 95} @s))))
    (is (= "B" (:grade (an/health {:oga 77} @s))))
    (is (= "F" (:grade (an/health {:oga 10} @s)))))

  (testing "every score stays inside 0–100 however bad the inputs"
    (let [awful (an/analyze (repeat 5 (turn :ok false :its 40 :ms 600000
                                            :tools [{:name "grep" :args {}}]))
                            {})]
      (is (<= 0 (:score (:health awful)) 100))
      (doseq [k [:outcome :iteration :tools]]
        (is (<= 0 (:score (k awful)) 100) (str k))))))

(deftest rework-finds-a-question-that-came-back
  ;; the honest half of brainyard's PQS: scoring prompt quality needs a scorer
  ;; this port does not have, but the same question returning needs none — and
  ;; it is the part that says the agent did not answer it
  (let [r (an/rework [(turn :q "where is the retry budget enforced" :ok false)
                      (turn :q "where is the retry budget enforced exactly" :ok true)
                      (turn :q "something else entirely now" :ok true)]
                     @s)]
    (is (= 1 (count (:repeated r))))
    (is (= 1 (:at (first (:repeated r)))) "the second turn repeats the first")
    (is (true? (:after-failure? (first (:repeated r))))
        "and the first had failed, which is why it came back"))

  (testing "unrelated questions are not rework"
    (is (empty? (:repeated (an/rework [(turn :q "read the config")
                                       (turn :q "deploy to staging")] @s)))))

  (is (nil? (an/rework [(turn)] @s)) "one turn cannot repeat anything"))

;; --- the thresholds are a file --------------------------------------------------------

(deftest a-project-can-disagree-about-what-good-means
  ;; every threshold is a judgement, tuned by brainyard against its own agent —
  ;; a project that disagrees edits the file rather than accepting the verdict
  (let [d (fresh-dir "analytics-override")
        c {:project-dir (.getPath d)}]
    (.mkdirs (io/file d ".brainyard"))
    (spit (io/file d ".brainyard" "analytics.edn")
          (pr-str {:iteration {:target-avg 1.0 :max-avg 2.0 :one-shot-bonus 0}}))
    (let [s' (an/settings c)]
      (is (= 1.0 (get-in s' [:iteration :target-avg])) "corrected")
      (is (= 0.20 (get-in s' [:weights :tce])) "and what it said nothing about stands")
      (testing "so the same turns score differently, which is the point"
        (let [strict (:score (an/iteration [(turn :its 4)] s'))
              lenient (:score (an/iteration [(turn :its 4)] @s))]
          (is (< strict lenient))))))

  (testing "a broken override leaves the defaults standing"
    (let [d (fresh-dir "analytics-broken")]
      (.mkdirs (io/file d ".brainyard"))
      (spit (io/file d ".brainyard" "analytics.edn") "{:weights {oops")
      (is (= 0.20 (get-in (an/settings {:project-dir (.getPath d)}) [:weights :oga]))))))

;; --- through the registry --------------------------------------------------------------

(deftest the-command-reads-what-the-turns-recorded
  (let [d (fresh-dir "analytics-cmd")
        c {:working-dir (.getPath d) :project-dir (.getPath d) :session-id "s1"}]
    (testing "absent while nothing has been recorded"
      (is (nil? (get @(:tools (agent/make-ctx {:config c :tools (vec tools/builtin-tools)}))
                     "session$analytics"))))

    (doseq [r [(turn :q "first" :ok true :its 2 :cost 0.10)
               (turn :q "second" :ok false :its 9 :cost 0.40)]]
      (tr/append! (tr/log-file c) r))

    (let [ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})
          cmd (get @(:tools ctx) "session$analytics")]
      (is (some? cmd))
      (is (= #{:fs/read} (:effects cmd)) "it only reads")

      (let [r (tools/call-tool cmd {} ctx)]
        (is (= 2 (:turns r)))
        (is (= 0.5 (get-in r [:outcome :success-rate])))
        (is (< 0.49 (get-in r [:cost :total-cost]) 0.51))
        (is (string? (get-in r [:health :grade])))
        (is (nil? (:tools r)) "the summary is the headline numbers"))

      (testing "full gives every metric"
        (let [r (tools/call-tool cmd {:detail "full"} ctx)]
          (is (contains? r :latency))
          (is (contains? r :rework))))

      (testing "a session nobody recorded says so rather than scoring nothing"
        (is (:error-message (tools/call-tool cmd {:session-id "nope"} ctx)))))))
