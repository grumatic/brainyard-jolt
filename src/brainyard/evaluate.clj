;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.evaluate
  "Datasets, metrics and measurement over predictors (RFC-021) — brainyard's
  `clj-llm/core/evaluate.clj`. The generic half: it knows a predictor and a
  dataset, and nothing about this project's directories.

  Three contracts, which everything else here is built from:

      example   {:inputs {…} :labels {…}? :meta {…}?}
      program   (fn [inputs]) → {:result {:outputs … :usage …} :trace [entry …]}
      metric    (fn [example result trace]) → number | boolean | nil

  brainyard's program returns only the result and the trace arrives through the
  dynamic `*trace*`; its params arrive through the dynamic `*params*`. Both are
  call ARGUMENTS here (`predictor/run` takes `:params` and `:on-event`), so a
  program carries its own trace back and parallel evaluation needs no binding
  conveyance — the `bound-fn` that wraps every task over there has nothing to
  do here (divergence `evaluation-passes-params-explicitly`).

  The reason this namespace is mostly variance machinery rather than optimizers
  is brainyard's own measurement: on `memory/graph-extract`, one configuration
  scored 0.559 and 0.605 on two passes of the same 12 examples, and every
  instruction and demo difference it measured fell inside that band. A score
  without a spread is not a result, so `evaluate` takes `:repeats` and
  `compare-scores` refuses to call a difference real below the margin — tested
  over the per-example differences, since both candidates ran the same
  examples."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]))

;; --- examples ---------------------------------------------------------------------

(defn example
  "brainyard's `example`. Labels are optional and describe only the FINAL
  outputs — the label-efficiency point that makes a dataset affordable."
  ([inputs] (example inputs nil nil))
  ([inputs labels] (example inputs labels nil))
  ([inputs labels meta]
   (cond-> {:inputs inputs}
     (seq labels) (assoc :labels labels)
     (seq meta) (assoc :meta meta))))

;; --- splits ------------------------------------------------------------------------

(defn- sha256 [^String s]
  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn content-hash
  "brainyard's `content-hash`: SHA-256 over the example's inputs in sorted-key
  order, first 4 bytes big-endian as an unsigned long. The hash is of CONTENT,
  so a split is order-insensitive — adding an example never moves an existing
  one into another split, which is what makes a recorded score comparable to
  the next one."
  [ex]
  (let [b (sha256 (pr-str (into (sorted-map) (:inputs ex))))]
    (reduce (fn [acc i] (+ (* acc 256) (bit-and (nth b i) 0xff))) 0 (range 4))))

(def ^:private hash-space 4294967296.0)

(defn split
  "{:train [] :val [] :test []} by content hash — brainyard's `split`."
  ([examples] (split examples {}))
  ([examples {:keys [train val] :or {train 0.6 val 0.2}}]
   (let [bucket (fn [ex]
                  (let [u (/ (content-hash ex) hash-space)]
                    (cond (< u train) :train
                          (< u (+ train val)) :val
                          :else :test)))]
     (merge {:train [] :val [] :test []}
            (update-vals (group-by bucket examples) vec)))))

(defn dataset-hash
  "A stable 12-hex-char name for a set of examples, so a score can say what it
  was measured on — brainyard's `dataset-hash`. Order-insensitive."
  [examples]
  (let [digest (sha256 (str/join (sort (map (comp str content-hash) examples))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 6 digest)))))

(defn select-split
  "The named split of `examples`, or all of them. brainyard's `select-split`."
  [examples s]
  (let [s (some-> s name)]
    (cond
      (or (nil? s) (= "all" s)) (vec examples)
      (contains? #{"train" "val" "test"} s) (get (split examples) (keyword s))
      :else (throw (ex-info (str "Unknown split " s " — all|train|val|test") {:split s})))))

;; --- metrics ----------------------------------------------------------------------

(defn- norm [v] (if (string? v) (str/lower-case (str/trim v)) v))

(defn score->double
  "brainyard's `score->double`: true/false/nil → 1.0/0.0/0.0, a number as-is,
  anything else 0.0."
  [s]
  (cond (number? s) (double s)
        (true? s) 1.0
        :else 0.0))

(defn- together-pairs
  "The pairs of indices a grouping puts in one group — any one, for an index
  in several. Each group is a map whose `:turns` are `{:index n}`."
  [groups]
  (set (for [g groups
             :let [is (sort (distinct (keep #(let [i (:index %)] (if (string? i) (parse-long i) i)) (:turns g))))]
             [a & more] (take-while seq (iterate rest is))
             b more]
         [a b])))

(defn segment-f1
  "Pairwise same-group F1 between the labelled `:tasks` and the produced ones:
  of the pairs of turns the label puts in one task, how many the output does
  too (a segmentation's agreement, as a metric). Two groupings with no
  pair together agree fully; an output with no tasks scores 0."
  []
  (fn [{:keys [labels]} result _trace]
    (let [out (get-in result [:outputs :tasks])]
      (if-not (sequential? out)
        0.0
        (let [t (together-pairs (:tasks labels)) p (together-pairs out)
              both (count (filter t p))
              prec (if (seq p) (/ (double both) (count p)) (if (seq t) 0.0 1.0))
              rec (if (seq t) (/ (double both) (count t)) (if (seq p) 0.0 1.0))]
          (if (zero? (+ prec rec)) 0.0 (/ (* 2 prec rec) (+ prec rec))))))))

(defn exact-match
  "Every labelled field equals the output, strings normalized. An example with
  NO labels scores 0 — brainyard's guard, and the reason a silver dataset built
  without labels cannot quietly score 1.0 everywhere."
  ([] (exact-match nil))
  ([fields]
   (fn [{:keys [labels]} result _trace]
     (let [ks (or (seq fields) (seq (keys labels)))]
       (if-not ks
         0.0
         (if (every? #(= (norm (get labels %)) (norm (get-in result [:outputs %]))) ks) 1.0 0.0))))))

(defn schema-valid
  "The outputs were schema-valid. A result with no `:valid?` counts as valid:
  the predictor already coerced and dropped undeclared keys (RFC-005)."
  []
  (fn [_example result _trace] (if (false? (:valid? result)) 0.0 1.0)))

(defn channel-choice
  "One bit: did the output take the channel the label took — a non-empty
  `field` (default `:tool-calls`: called a tool) or an empty one (answered)?
  The cheapest reliable label in a silver dataset: the teacher's answer text
  and arguments are its own wording, whether it called a tool is not
  (byj-gly.124's spike, `dev/brainyard/dev/output_shape_spike.clj`)."
  ([] (channel-choice :tool-calls))
  ([field]
   (fn [{:keys [labels]} result _trace]
     (if (= (empty? (get labels field)) (empty? (get-in result [:outputs field]))) 1.0 0.0))))

(defn set-f1
  "F1 between a labelled set field and the produced one, items projected by
  `key-fn`. Both empty is 1.0 — an example whose right answer is 'nothing'
  is answered correctly by nothing."
  ([field] (set-f1 field norm))
  ([field key-fn]
   (fn [{:keys [labels]} result _trace]
     (let [gold (set (map key-fn (get labels field)))
           pred (set (map key-fn (get-in result [:outputs field])))
           tp (count (filter gold pred))]
       (cond (and (empty? gold) (empty? pred)) 1.0
             (or (empty? gold) (empty? pred)) 0.0
             (zero? tp) 0.0
             :else (let [p (/ (double tp) (count pred))
                         r (/ (double tp) (count gold))]
                     (/ (* 2 p r) (+ p r))))))))

(defn weighted
  "Weighted mean of `[[metric weight] …]`."
  [metric-weights]
  (fn [ex result trace]
    (let [total (reduce + (map second metric-weights))]
      (if (zero? total)
        0.0
        (/ (reduce + (map (fn [[m w]] (* w (score->double (m ex result trace)))) metric-weights))
           (double total))))))

(defn all-of
  "1.0 only when every metric scores above 0.5."
  [& metrics]
  (fn [ex result trace]
    (if (every? #(> (score->double (% ex result trace)) 0.5) metrics) 1.0 0.0)))

;; --- variance ---------------------------------------------------------------------

(def ^:private t-crit-95
  "Two-sided 95% t critical values for df 1..30; 1.96 past that."
  [12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262 2.228
   2.201 2.179 2.160 2.145 2.131 2.120 2.110 2.101 2.093 2.086
   2.080 2.074 2.069 2.064 2.060 2.056 2.052 2.048 2.045 2.042])

(defn t-critical
  "brainyard's `t-critical`. A fractional df floors — the conservative side."
  [df]
  (let [d (long (Math/floor (double df)))]
    (cond (< d 1) (first t-crit-95)
          (<= d 30) (nth t-crit-95 (dec d))
          :else 1.96)))

(def ^:private agreeing-signs-for-95
  "How many examples must all move the same way before that alone is a result.

  When the per-example differences have NO spread, every example moved by the
  same amount and the t-test has no standard error to divide by. What is left
  is the consistency of the direction, and the instrument for that is a sign
  test: `n` agreeing signs are two-sided p = 2·0.5^n, which first clears 0.05
  at n = 6 (0.0156). n = 5 is 0.0625 and n = 2 is 0.5 — a perfectly clean
  separation on two items is not a result, however clean it looks."
  6)

(defn- mean [xs] (when (seq xs) (/ (reduce + (map double xs)) (count xs))))

(defn- variance
  "Sample variance, or nil for fewer than two values."
  [xs]
  (when (> (count xs) 1)
    (let [m (mean xs)]
      (/ (reduce + (map #(let [d (- (double %) m)] (* d d)) xs)) (dec (count xs))))))

(defn- compare-passes
  [diff a b]
  (let [sa (:stderr a) sb (:stderr b)
        ka (or (:completed-repeats a) 0) kb (or (:completed-repeats b) 0)]
    (if (or (nil? sa) (nil? sb))
      {:better? (pos? diff) :diff diff :margin nil :tested? false :paired? false}
      (let [va (* sa sa) vb (* sb sb) v (+ va vb)]
        (if (zero? v)
          {:better? (pos? diff) :diff diff :margin 0.0 :tested? false :paired? false}
          (let [df (/ (* v v) (+ (if (> ka 1) (/ (* va va) (dec ka)) 0)
                                 (if (> kb 1) (/ (* vb vb) (dec kb)) 0)))
                margin (* (t-critical df) (Math/sqrt v))]
            {:better? (> diff margin) :diff diff :margin margin :tested? true :paired? false}))))))

;; --- running one example ------------------------------------------------------------

(defn- error-class
  "How a failed call counts. `llm/chat` already retried what it could
  (`with-retry`), so a transient failure that reaches here has exhausted its
  retries and is NOT retried again — brainyard retries in the evaluator because
  its call path does not (divergence `evaluation-does-not-re-retry`)."
  [e]
  (let [d (ex-data e)]
    (cond (:fatal? d) :fatal
          (contains? #{:empty :malformed} (:kind d)) :malformed
          (:retryable? d) :transient
          :else :error)))

(defn- trace-usage [trace]
  (reduce (fn [acc t] (-> acc
                          (update :in + (or (get-in t [:usage :input-tokens]) 0))
                          (update :out + (or (get-in t [:usage :output-tokens]) 0))))
          {:in 0 :out 0} trace))

(defn- run-one
  "One example, once. Never throws: a failure is a scored row."
  [program metric ex idx]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [{:keys [result trace]} (program (:inputs ex))
            score (try (score->double (metric ex result trace))
                       (catch Throwable e
                         ;; a metric that throws scores 0 and says so, rather
                         ;; than failing a run that otherwise produced answers
                         {::metric-error (ex-message e)}))]
        (cond-> {:index idx
                 :score (if (map? score) 0.0 score)
                 :outputs (:outputs result)
                 :calls (count trace)
                 :tokens (trace-usage trace)
                 :elapsed-ms (- (System/currentTimeMillis) t0)}
          (map? score) (assoc :metric-error (::metric-error score))))
      (catch Throwable e
        (let [cls (error-class e)]
          {:index idx
           ;; a malformed reply is the program being wrong, so it scores 0
           ;; rather than counting as the harness failing
           :score 0.0
           :error (ex-message e)
           :error-class cls
           :calls 1
           :tokens {:in 0 :out 0}
           :elapsed-ms (- (System/currentTimeMillis) t0)})))))

;; --- one pass ----------------------------------------------------------------------

(defn- over-budget?
  [{:keys [calls tokens]} {:keys [max-calls max-tokens]}]
  (or (and max-calls (>= @calls max-calls))
      (and max-tokens (>= (+ (:in @tokens) (:out @tokens)) max-tokens))))

(defn- evaluate-pass
  "One pass over `examples`. Budgets are checked BEFORE an example starts, so
  with `:parallel n` the overshoot is bounded by n-1 examples — brainyard's
  `may-start?`."
  [program examples metric {:keys [parallel] :or {parallel 1} :as opts}]
  (let [t0 (System/currentTimeMillis)
        spent {:calls (atom 0) :tokens (atom {:in 0 :out 0})}
        stopped (atom nil)
        record! (fn [r]
                  (swap! (:calls spent) + (:calls r 0))
                  (swap! (:tokens spent) #(merge-with + % (:tokens r {:in 0 :out 0})))
                  (when (= :fatal (:error-class r)) (reset! stopped :fatal))
                  r)
        one (fn [idx ex]
              (cond
                @stopped nil
                (over-budget? spent opts) (do (compare-and-set! stopped nil :budget) nil)
                :else (record! (run-one program metric ex idx))))
        rows (if (<= (long parallel) 1)
               (vec (keep-indexed (fn [i ex] (one i ex)) examples))
               (->> (map-indexed (fn [i ex] (fx/task-of #(one i ex))) examples)
                    (fx/bounded (long parallel))
                    fx/run-value!!
                    (remove nil?)
                    vec))
        rows (vec (sort-by :index rows))
        scores (map :score rows)]
    {:score (or (mean scores) 0.0)
     :n (count examples)
     :attempted (count rows)
     :errors (count (filter :error rows))
     :stopped @stopped
     :calls @(:calls spent)
     :tokens @(:tokens spent)
     :dataset (dataset-hash examples)
     :elapsed-ms (- (System/currentTimeMillis) t0)
     :per-example rows}))

;; --- evaluate ----------------------------------------------------------------------

(defn evaluate
  "Measure `program` on `examples` with `metric`.

  opts:
    :repeats    passes over the whole set (default 1). Two or more give the
                spread without which a comparison is not a comparison.
    :parallel   examples in flight at once (default 1)
    :max-calls  / :max-tokens — the spend cap, SHARED across every pass

  Returns the per-pass means and their spread:

      {:score :repeats :completed-repeats :repeat-scores :stddev :stderr :ci95
       :n :attempted :errors :stopped :calls :tokens :dataset :elapsed-ms
       :per-example [{:index :score :scores :spread :outputs :error :error-class
                      :calls :tokens} …]}

  `:stopped` is nil, `:budget` or `:fatal`. A pass cut short by the budget is
  left out of `:score` when any complete pass exists — half a pass is not a
  score of the dataset.

  brainyard caps in dollars (`:budget-usd`); the provider layer here does not
  price a call, and the CLI provider is a subscription with no per-call price
  at all, so the cap is calls and tokens (divergence `spend-cap-is-not-dollars`)."
  [program examples metric & {:keys [repeats] :or {repeats 1} :as opts}]
  (let [t0 (System/currentTimeMillis)
        reps (max 1 (long repeats))
        passes (loop [i 0, acc [], budget opts]
                 (if (or (>= i reps) (some-> (peek acc) :stopped))
                   acc
                   (let [p (evaluate-pass program examples metric budget)
                         used-calls (reduce + (map :calls acc))
                         used-tok (reduce + (map #(+ (:in (:tokens %)) (:out (:tokens %))) acc))]
                     (recur (inc i) (conj acc p)
                            (cond-> budget
                              (:max-calls opts) (assoc :max-calls (- (:max-calls opts) used-calls (:calls p)))
                              (:max-tokens opts) (assoc :max-tokens (- (:max-tokens opts) used-tok
                                                                       (+ (:in (:tokens p)) (:out (:tokens p))))))))))
        complete (filterv #(and (nil? (:stopped %)) (= (:attempted %) (:n %))) passes)
        scoring (if (seq complete) complete passes)
        means (mapv :score scoring)
        k (count complete)
        sd (some-> (variance means) Math/sqrt)
        se (when (and sd (pos? k)) (/ sd (Math/sqrt k)))
        score (or (mean means) 0.0)
        by-index (fn [idx]
                   (let [rs (keep #(first (filter (fn [r] (= idx (:index r))) (:per-example %))) passes)
                         ss (mapv :score rs)
                         last-r (last rs)]
                     (cond-> {:index idx
                              :score (or (mean ss) 0.0)
                              :scores ss
                              :spread (if (seq ss) (- (apply max ss) (apply min ss)) 0.0)
                              :errors (count (filter :error rs))
                              :calls (reduce + (map :calls rs))
                              :tokens (reduce (partial merge-with +) {:in 0 :out 0} (map :tokens rs))}
                       (:outputs last-r) (assoc :outputs (:outputs last-r))
                       (:error last-r) (assoc :error (:error last-r)
                                              :error-class (:error-class last-r)))))]
    {:score score
     :repeats reps
     :completed-repeats k
     :repeat-scores means
     :stddev sd
     :stderr se
     :ci95 (when (and se (> k 1))
             (let [m (* (t-critical (dec k)) se)] [(- score m) (+ score m)]))
     :n (count examples)
     :attempted (if (seq complete) (count examples) (:attempted (last passes) 0))
     :errors (reduce + (map :errors passes))
     :stopped (some :stopped passes)
     :calls (reduce + (map :calls passes))
     :tokens (reduce (partial merge-with +) {:in 0 :out 0} (map :tokens passes))
     :dataset (dataset-hash examples)
     :elapsed-ms (- (System/currentTimeMillis) t0)
     :per-example (mapv by-index (range (count examples)))
     :example-scores (mapv :score (mapv by-index (range (count examples))))}))

;; --- leaderboards -------------------------------------------------------------------

(def leaderboard-row-keys
  "What a candidate row keeps from its evaluation."
  [:score :attempted :n :calls :tokens :stopped :errors
   :repeats :completed-repeats :repeat-scores :stddev :stderr :ci95
   :example-scores])

(defn compare-scores
  "Is `a` better than `b`, beyond the noise? — brainyard's `compare-scores`.

      {:better? bool :diff d :margin m :tested? bool :paired? bool :n k}

  Two programs are evaluated on THE SAME examples, so the comparison is paired:
  when both sides carry `:example-scores`, this takes the per-example difference
  d_i = a_i - b_i and tests whether mean(d) clears the t margin on its own
  standard error. Pairing removes the between-example variance, which is what
  dominates on a small set — five examples where one is easy and four are hard
  spread the per-pass mean so wide that Welch cannot see a change that every
  example agrees on (byj-gly.57).

  Without per-example scores it falls back to `compare-passes`, Welch's t over
  the two runs' per-pass means, which is what brainyard does and all it can do:
  a repeat's mean is the only number it keeps. `:paired?` says which ran.

  `:tested?` false means nothing could be weighed — a single pass per side,
  identical scores, or too few examples agreeing for their agreement alone to
  mean anything — and the answer is then a bare comparison. This is the
  function that stops a 0.05 difference on twelve examples from being reported
  as an improvement."
  [a b]
  (let [diff (- (double (or (:score a) 0)) (double (or (:score b) 0)))
        xa (:example-scores a) xb (:example-scores b)]
    (if (and (seq xa) (seq xb) (= (count xa) (count xb)))
      (let [ds (mapv - xa xb)
            n (count ds)
            md (double (or (mean ds) 0.0))
            sd (some-> (variance ds) Math/sqrt)]
        (cond
          (< n 2) {:better? (pos? diff) :diff diff :margin nil :tested? false :paired? true :n n}
          ;; no spread in the DIFFERENCES — every example moved by the same
          ;; amount. The t has nothing to divide by, so the question becomes
          ;; how many examples agreed (`agreeing-signs-for-95`)
          (or (nil? sd) (zero? (double sd)))
          {:better? (pos? md) :diff diff :margin 0.0
           :tested? (and (not (zero? md)) (>= n agreeing-signs-for-95))
           :paired? true :n n}
          :else
          (let [se (/ (double sd) (Math/sqrt n))
                margin (* (t-critical (dec n)) se)]
            {:better? (> md margin) :diff diff :margin margin :tested? true :paired? true :n n})))
      (compare-passes diff a b))))

(defn select-best
  "The winning row and the annotated leaderboard — brainyard's `select-best`.

  The first non-reference row is the BASELINE (zero-shot). A candidate wins only
  by beating it past `compare-scores`' margin — paired over the examples both
  ran — and a tie keeps the earlier row, so demos must STRICTLY beat no demos. A `:reference?` row (the teacher, a different
  model) is scored for context and can never win.

  Returns `[best rows]`; `best` is nil when nothing completed."
  [leaderboard]
  (let [complete? (fn [r] (and (nil? (:stopped r)) (= (:attempted r) (:n r))))
        base (first (remove :reference? leaderboard))
        rows (mapv (fn [r]
                     (if (and base (not (:reference? r)) (not= r base)
                              (complete? r) (complete? base))
                       (assoc r :vs-baseline (compare-scores r base))
                       r))
                   leaderboard)
        winners (filter #(get-in % [:vs-baseline :better?]) rows)]
    [(cond
       (seq winners) (reduce (fn [b c] (if (> (:score c) (:score b)) c b)) winners)
       (and base (complete? base)) base
       :else nil)
     rows]))
