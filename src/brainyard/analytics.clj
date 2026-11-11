(ns brainyard.analytics
  "What a session's turns say about how the agent is doing (RFC-025) —
  brainyard's `components/analytics` and `agent/common/analytics_commands.clj`.

  Pull, not push: it reads `trajectory.edn` when asked. brainyard removed its
  per-turn push pipeline for the same reason — scoring every turn as it
  finishes costs work nobody asked for, and the records are already on disk.

  **The measurements are facts; the score is a judgement.** Iterations,
  latency, cache hit rate, success rate and dollars are what happened. Turning
  them into a number out of 100 needs thresholds, and those live in
  `resources/brainyard/analytics.edn` because they are opinions about what good
  means for a particular agent doing particular work (RFC-001). They are
  brainyard's opinions and are not validated here: the score is a consistent
  way to compare this project's runs against each other, not a verdict.

  Two of brainyard's six metrics are absent rather than faked. **PQS** scores
  prompt quality and needs a scorer that is not ported; **waste detection** is
  another subsystem. Their weight is shared out over the rest, which is
  brainyard's own rule for a metric with no data.

  The tool error rate is real rather than pattern-matched: a record carries
  `:error?` per call because the tool layer knew at the time (`byj-gly.87`).
  Deriving it from result text would have been a guess dressed as a
  measurement, which is the one thing a metric must not be."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.trajectory :as trajectory]))

(def bundled (delay (edn/read-string (slurp (io/resource "brainyard/analytics.edn")))))

(defn settings
  "The thresholds in force: bundled, with a project's `.brainyard/analytics.edn`
  merged over it. Never throws — an unreadable override leaves the defaults."
  [{:keys [project-dir]}]
  (let [f (io/file (or project-dir ".") ".brainyard" "analytics.edn")
        extra (when (.isFile f)
                (try (let [m (edn/read-string (slurp f))] (when (map? m) m))
                     (catch Throwable _ nil)))]
    (merge-with #(if (and (map? %1) (map? %2)) (merge %1 %2) %2) @bundled extra)))

;; --- small statistics --------------------------------------------------------------

(defn pct
  "The `p`th percentile of `xs` (0–1), nearest-rank. Nil for no data."
  [p xs]
  (when-let [v (seq (sort (remove nil? xs)))]
    (nth (vec v) (min (dec (count v))
                      (int (* p (count v)))))))

(defn- mean [xs]
  (when-let [v (seq (remove nil? xs))] (/ (double (reduce + v)) (count v))))

(defn- ratio [a b] (when (and a b (pos? b)) (/ (double a) (double b))))

(defn- clamp [x] (max 0.0 (min 100.0 (double x))))

(defn- scale
  "100 at `good`, 0 at `bad`, linear between — the shape every sub-score here
  uses. Works in either direction, so a lower-is-better measure needs no
  special case."
  [x good bad]
  (when (and x (not= good bad))
    (clamp (* 100.0 (/ (- (double bad) (double x)) (- (double bad) (double good)))))))

(defn- jaccard
  "Word overlap of two questions, 0–1. brainyard's measure for 'the same
  question again'."
  [a b]
  (let [w #(set (remove str/blank? (str/split (str/lower-case (str %)) #"\W+")))
        x (w a) y (w b)]
    (if (or (empty? x) (empty? y))
      0.0
      (/ (double (count (clojure.set/intersection x y)))
         (count (clojure.set/union x y))))))

;; --- the metrics -------------------------------------------------------------------

(defn outcome
  "Did the turns answer? brainyard's OGA. Trailing failures are counted
  separately because a session that ended badly is worse than one that had a
  bad patch in the middle and recovered."
  [records _]
  (when (seq records)
    (let [n (count records)
          ok (count (filter :success records))
          trailing (count (take-while (complement :success) (reverse records)))
          runs (->> (partition-by :success records)
                    (remove #(:success (first %)))
                    (map count))]
      {:turns n
       :succeeded ok
       :success-rate (ratio ok n)
       :failed (- n ok)
       :trailing-failures trailing
       :max-consecutive-failures (if (seq runs) (apply max runs) 0)
       :terminated-by (frequencies (map :terminated-by records))
       ;; a trailing failure costs 10 apiece: the session is still broken
       :score (clamp (- (* 100.0 (ratio ok n)) (* 10 trailing)))})))

(defn iteration
  "How many passes a turn needed. brainyard's ICE."
  [records {:keys [iteration]}]
  (when (seq records)
    (let [its (map :total-iterations records)
          one-shot (count (filter #(<= (or % 0) 2) its))
          avg (mean its)]
      {:avg avg
       :p50 (pct 0.5 its)
       :p90 (pct 0.9 its)
       :one-shot-rate (ratio one-shot (count records))
       :score (some-> (scale avg (:target-avg iteration) (:max-avg iteration))
                      (+ (* (:one-shot-bonus iteration) (ratio one-shot (count records))))
                      clamp)})))

(defn latency
  "How long turns took, and how fast the model wrote. brainyard's LT.

  `:slowest` is named by POSITION, not by a turn number: a v3 record has no
  `:turn` (RFC-025), and brainyard's own analytics reads one anyway and gets
  nil back."
  [records {:keys [latency]}]
  (let [ms (keep :duration-ms records)]
    (when (seq ms)
      (let [out-tokens (reduce + 0 (keep #(get-in % [:usage :out]) records))
            total-s (/ (double (reduce + ms)) 1000.0)
            tps (when (pos? total-s) (/ (double out-tokens) total-s))
            p90 (pct 0.9 ms)]
        {:total-ms (reduce + ms)
         :p50-ms (pct 0.5 ms)
         :p90-ms p90
         :output-tokens-per-sec tps
         :slowest (->> (map-indexed (fn [i r] {:at i :duration-ms (:duration-ms r)
                                               :question (:question r)}) records)
                       (filter :duration-ms)
                       (sort-by (comp - :duration-ms))
                       (take 3) vec)
         :score (mean (remove nil? [(scale tps 0 (:target-tps latency))
                                    (scale p90 0 (* 2 (:target-p90-ms latency)))]))}))))

(defn cache
  "How much of the input was served from cache (RFC-015).

  **Three buckets, not two.** `:in` here is the TOTAL input — `llm.clj` sums
  Anthropic's `input_tokens`, `cache_read_input_tokens` and
  `cache_creation_input_tokens` into one number (RFC-006) — so cached, WRITTEN
  and genuinely fresh tokens all live inside it. brainyard's `:in` is the fresh
  count alone, which is why its metric reads `read / (read + in)` and this one
  cannot: ported verbatim, that formula silently counts every cache write as a
  fresh token (`byj-gly.94`).

  **A write is left out of the rate, as brainyard leaves it out.** A cache write
  is not a miss; it is this turn paying up front so a later one reads cheaply.
  Counting it against the rate means the better the cache is primed, the worse
  the number looks — and the score punishes exactly the behaviour it exists to
  reward. Measured on a real claude-code turn: 42,701 read, 17,219 written, 8
  genuinely fresh — a 71% hit rate before this, 99.98% after."
  [records _]
  (let [sum (fn [k] (reduce + 0 (keep #(get-in % [:usage k]) records)))
        read (sum :cache-read)
        write (sum :cache-write)
        in (sum :in)
        ;; never negative: a provider that reports its counts some other way
        ;; must not turn into a nonsense rate
        fresh (max 0 (- in read write))]
    (when (pos? in)
      {:cached-input read
       :written-input write
       :fresh-input fresh
       :hit-rate (ratio read (+ read fresh))})))

(defn cost
  "What the session spent, and what it bought. brainyard's TCE.

  **Reports nothing rather than a wrong number when a turn could not be
  priced.** `:partial?` on a record means its dollars are an understatement
  (RFC-025), and averaging a real spend against a fabricated zero is exactly
  what that flag exists to prevent."
  [records {c :cost}]
  (let [priced (remove :cost-partial? records)
        dollars (keep :cost priced)]
    (when (seq dollars)
      (let [total (reduce + dollars)
            wins (count (filter :success priced))
            hit (:hit-rate (cache records nil))
            out (reduce + 0 (keep #(get-in % [:usage :out]) priced))
            all (+ out (reduce + 0 (keep #(get-in % [:usage :in]) priced)))
            share (ratio out all)]
        (cond-> {:total-cost total
                 :priced-turns (count dollars)
                 :cost-per-successful-turn (ratio total wins)
                 :cache-hit-rate hit
                 :output-share share
                 :score (mean (remove nil? [(scale hit (:cache-hit-target c) 0)
                                            (scale share (:output-share-target c) 0)]))}
          (< (count dollars) (count records))
          (assoc :unpriced-turns (- (count records) (count dollars))))))))

(defn tools
  "What the turns called, and how much of it failed. brainyard's TUR.

  The error rate is real rather than pattern-matched: a record carries
  `:error?` because the tool layer knew (`byj-gly.87`). Records written before
  that simply have no failures to count, which understates rather than
  inventing — the safer direction for a number nobody can go back and check."
  [records {t :tools}]
  (let [calls (for [r records, i (:iterations r), c (:tools i)]
                {:name (:name c) :args (:args c) :error? (:error? c)})]
    (when (seq calls)
      (let [per-turn (ratio (count calls) (count records))
            repeats (- (count calls) (count (distinct (map #(dissoc % :error?) calls))))
            failed (filter :error? calls)
            err-rate (ratio (count failed) (count calls))]
        {:total-calls (count calls)
         :calls-per-turn per-turn
         :unique-tools (count (distinct (map :name calls)))
         :top (->> (frequencies (map :name calls))
                   (sort-by (comp - val)) (take 5)
                   (mapv (fn [[k v]] {:tool k :calls v})))
         :failed-calls (count failed)
         :error-rate err-rate
         ;; which ones, because "12% of calls failed" is a fact and "read-file
         ;; failed 8 times" is something to go and fix
         :failing (->> (frequencies (map :name failed))
                       (sort-by (comp - val)) (take 5)
                       (mapv (fn [[k v]] {:tool k :failures v})))
         :redundant-calls repeats
         :score (clamp (- (or (scale per-turn 1.0 (* 2 (:target-calls-per-turn t))) 100.0)
                          (* (:redundant-penalty t) repeats)
                          (* (:error-penalty t) (* 100.0 (or err-rate 0.0)))))}))))

(defn rework
  "Questions the person had to ask again. The honest half of brainyard's PQS
  adjustments: it scores prompt QUALITY with a scorer this port does not have,
  but the part that needs no scorer — the same question coming back — is the
  part that says the agent did not answer it."
  [records {r :rework}]
  (when (> (count records) 1)
    (let [qs (mapv :question records)
          pairs (for [i (range (count qs)), j (range (inc i) (count qs))
                      :let [s (jaccard (nth qs i) (nth qs j))]
                      :when (>= s (:similar r))]
                  {:at j :like i :similarity s
                   :after-failure? (not (:success (nth records i)))})
          storms (->> (map-indexed vector qs)
                      (partition (:storm-run r) 1)
                      (filter (fn [win]
                                (every? #(>= (jaccard (second (first win)) (second %)) (:storm r))
                                        (rest win))))
                      (mapv (comp first first)))]
      {:repeated (vec pairs)
       :re-asked-after-a-failure (count (filter :after-failure? pairs))
       :storms storms})))

;; --- the score ----------------------------------------------------------------------

(defn health
  "One number, and the letter brainyard would give it.

  A metric with no data contributes nothing and its weight is shared over the
  rest — which is why this works at all with PQS absent."
  [scores {:keys [weights grades fail-grade]}]
  (let [have (into {} (for [[k w] weights
                            :let [s (get scores k)]
                            :when (number? s)]
                        [k [w s]]))
        total-w (reduce + 0.0 (map first (vals have)))]
    (when (pos? total-w)
      (let [score (/ (reduce + 0.0 (map (fn [[w s]] (* w s)) (vals have))) total-w)]
        {:score (Math/round score)
         :grade (or (some (fn [[at g]] (when (>= score at) g)) grades) fail-grade)
         :from (vec (sort (keys have)))
         :missing (vec (sort (remove (set (keys have)) (keys weights))))}))))

(defn analyze
  "Every metric over `records`, and the score they add up to."
  [records config]
  (let [s (settings config)
        m {:outcome (outcome records s)
           :iteration (iteration records s)
           :latency (latency records s)
           :cache (cache records s)
           :cost (cost records s)
           :tools (tools records s)
           :rework (rework records s)}]
    (assoc m
           :turns (count records)
           :health (health {:oga (:score (:outcome m))
                            :ice (:score (:iteration m))
                            :lt (:score (:latency m))
                            :tce (:score (:cost m))
                            :tur (:score (:tools m))}
                           s))))

;; --- the command ---------------------------------------------------------------------

(def returns
  "`session$analytics`'s result, as `analyze` builds it. A metric with no data
  is nil; `:latency`, `:cache`, `:tools` and `:rework` are there only with
  `detail: full`."
  (let [score [:maybe :double]]
    [:or {:desc "scores are 0–100; a metric with no data is nil; latency, cache, tools and rework only with detail full"}
     [:map
      [:turns :int]
      [:sessions [:vector :string]]
      [:health [:maybe [:map [:score :int] [:grade :string]
                        [:from [:vector :keyword]] [:missing [:vector :keyword]]]]]
      [:outcome [:maybe [:map [:turns :int] [:succeeded :int] [:success-rate :double] [:failed :int]
                         [:trailing-failures :int] [:max-consecutive-failures :int]
                         [:terminated-by [:map-of [:maybe :keyword] :int]] [:score :double]]]]
      [:iteration [:maybe [:map [:avg [:maybe :double]] [:p50 [:maybe :int]] [:p90 [:maybe :int]]
                           [:one-shot-rate :double] [:score score]]]]
      [:cost [:maybe [:map [:total-cost :double] [:priced-turns :int]
                      [:cost-per-successful-turn [:maybe :double]] [:cache-hit-rate [:maybe :double]]
                      [:output-share [:maybe :double]] [:score score]
                      [:unpriced-turns {:optional true} :int]]]]
      [:latency {:optional true}
       [:maybe [:map [:total-ms :int] [:p50-ms :int] [:p90-ms :int]
                [:output-tokens-per-sec [:maybe :double]]
                [:slowest [:vector [:map [:at :int] [:duration-ms :int] [:question [:maybe :string]]]]]
                [:score score]]]]
      [:cache {:optional true}
       [:maybe [:map [:cached-input :int] [:written-input :int] [:fresh-input :int]
                [:hit-rate [:maybe :double]]]]]
      [:tools {:optional true}
       [:maybe [:map [:total-calls :int] [:calls-per-turn :double] [:unique-tools :int]
                [:top [:vector [:map [:tool [:maybe :string]] [:calls :int]]]]
                [:failed-calls :int] [:error-rate :double]
                [:failing [:vector [:map [:tool [:maybe :string]] [:failures :int]]]]
                [:redundant-calls :int] [:score :double]]]]
      [:rework {:optional true}
       [:maybe [:map [:repeated [:vector [:map [:at :int] [:like :int] [:similarity :double]
                                          [:after-failure? :boolean]]]]
                [:re-asked-after-a-failure :int] [:storms [:vector :int]]]]]]
     [:map [:error-message :string]]]))

(defn commands
  "`session$analytics`. Offered only when some session has a trajectory."
  [config]
  (when (seq (trajectory/sessions config))
    [{:name "session$analytics" :kind :command
      :description (str "How this project's agent has been doing: success rate, iterations per turn,"
                        " latency, cache hit rate, tool use and what it cost, over the recorded"
                        " turns. Reach for it when asked whether something is working well or"
                        " where time or money is going — not to find out WHAT happened, which is"
                        " log$search and memory$history.")
      :input-schema [:map [:session-id {:optional true} [:string {:desc "One session. Omit for all of them."}]]
                          [:detail {:optional true} [:string {:desc "summary (default) for the headline numbers, full for every metric."}]]]
      :output-schema returns
      :effects #{:fs/read}
      :fn (fn [{:keys [session-id detail]} ctx]
            (let [c (:config ctx)
                  ss (if (str/blank? (str session-id)) (trajectory/sessions c) [(str session-id)])
                  records (vec (mapcat #(trajectory/read-trajectories c %) ss))]
              (if (empty? records)
                {:error-message (str "no turns recorded"
                                     (when-not (str/blank? (str session-id))
                                       (str " for session " session-id)))}
                (let [a (analyze records c)]
                  (assoc (if (= "full" (str detail))
                           a
                           (select-keys a [:turns :health :outcome :cost :iteration]))
                         :sessions (vec ss))))))}]))
