;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.optimize
  "Choosing demos for a predictor (RFC-021) — brainyard's
  `clj-llm/core/optimize.clj`, the two optimizers that cost little and taught
  something.

  Every optimizer has one shape:

      (optimizer …) → {:params {predictor-id params …} :report {…}}

  and **applies nothing**. What to do with a proposal is `brainyard.programs`'s
  business, and accepting one is a person's (RFC-021 'Accept is not a tool').

  `bootstrap-random-search`, `ensemble`, `knn-few-shot` and instruction
  proposal are not here — `byj-gly.57`. brainyard built the first of them and
  measured it: on its one real dataset the teacher scored BELOW the student
  (0.427 vs ~0.58), bootstrapped demos lost to no demos (0.476 vs 0.605), and
  every difference sat inside a ±0.05 noise band. A search over candidates buys
  nothing until an evaluation can separate them, so the evaluator got the work
  instead (`evaluate/compare-scores`)."
  (:require [brainyard.evaluate :as ev]))

;; --- seeded shuffle ------------------------------------------------------------------

(defn shuffle-seeded
  "A deterministic shuffle. brainyard uses `java.util.Collections/shuffle`,
  which jolt does not provide, so this is the Fisher-Yates that method
  describes, over `java.util.Random` — same algorithm, same seed discipline,
  and reproducible here (divergence `seeded-shuffle-is-fisher-yates`)."
  [coll seed]
  (let [a (object-array coll)
        rng (java.util.Random. (long seed))]
    (doseq [i (range (dec (alength a)) 0 -1)]
      (let [j (.nextInt rng (inc i))
            tmp (aget a i)]
        (aset a i (aget a j))
        (aset a j tmp)))
    (vec a)))

;; --- labeled-few-shot ------------------------------------------------------------------

(defn labeled-few-shot
  "k labelled examples as demos, straight from the dataset — brainyard's
  `labeled-few-shot`. No model is called, so this is the floor a bootstrap has
  to beat as well as the cheapest thing worth trying.

  opts: `:k` (4), `:seed` (0)."
  [predictor-id trainset & {:keys [k seed] :or {k 4 seed 0}}]
  (let [labelled (keep-indexed (fn [i ex] (when (seq (:labels ex)) [i ex])) trainset)
        picked (take k (shuffle-seeded labelled seed))
        demos (mapv (fn [[i ex]] {:inputs (:inputs ex)
                                  :outputs (:labels ex)
                                  :source {:kind :labeled :example i}})
                    picked)]
    {:params {predictor-id {:demos demos}}
     :report {:optimizer :labeled-few-shot
              :k k :seed seed
              :available (count labelled)
              :demos (count demos)}}))

;; --- bootstrap ---------------------------------------------------------------------

(defn bootstrap-pool
  "Run the teacher over the trainset and keep the traces that PASSED the metric,
  as demos — brainyard's `bootstrap-pool`.

  The teacher runs ONCE over each example and candidates are drawn from the
  resulting pool, rather than being re-run per trial as the paper does: the
  teacher is the expensive tier, and re-running it mostly reproduces the same
  trace. The cost is candidate diversity, which only a search would spend.

  opts: `:threshold` (1.0), `:max-per-predictor` (16), `:max-calls`,
  `:max-tokens`.

  Returns `{:pool {predictor-id [demo …]} :report {…}}`; `:scores` holds every
  teacher score in trainset order, `nil` where the example errored — the row
  that tells you whether the teacher is worth learning from at all."
  [teacher trainset metric & {:keys [threshold max-per-predictor]
                              :or {threshold 1.0 max-per-predictor 16}
                              :as opts}]
  (let [calls (atom 0) tokens (atom {:in 0 :out 0})
        over? (fn [] (or (and (:max-calls opts) (>= @calls (:max-calls opts)))
                         (and (:max-tokens opts)
                              (>= (+ (:in @tokens) (:out @tokens)) (:max-tokens opts)))))]
    (loop [[[i ex] & more] (map-indexed vector trainset)
           pool {} scores [] attempted 0 passed 0 errors 0 stopped nil]
      (cond
        (or (nil? i) stopped)
        {:pool pool
         :report {:optimizer :bootstrap-pool
                  :attempted attempted :passed passed :errors errors
                  :threshold threshold
                  :calls @calls :tokens @tokens
                  :scores scores :stopped stopped}}

        (and (seq pool) (every? #(>= (count %) max-per-predictor) (vals pool)))
        (recur nil pool scores attempted passed errors :full)

        (over?) (recur nil pool scores attempted passed errors :budget)

        :else
        (let [{:keys [result trace error fatal?]}
              (try (let [r (teacher (:inputs ex))] (assoc r :error nil))
                   (catch Throwable e
                     {:error (ex-message e) :fatal? (:fatal? (ex-data e)) :trace []}))]
          (swap! calls + (max 1 (count trace)))
          (swap! tokens #(reduce (fn [acc t]
                                   (-> acc (update :in + (or (get-in t [:usage :input-tokens]) 0))
                                       (update :out + (or (get-in t [:usage :output-tokens]) 0))))
                                 % trace))
          (cond
            fatal? (recur nil pool (conj scores nil) (inc attempted) passed (inc errors) :fatal)
            error (recur more pool (conj scores nil) (inc attempted) passed (inc errors) nil)
            :else
            (let [score (try (ev/score->double (metric ex result trace)) (catch Throwable _ 0.0))
                  pass? (>= score (double threshold))
                  pool' (if-not pass?
                          pool
                          (reduce (fn [p entry]
                                    (if (or (nil? (:predictor-id entry)) (:error entry))
                                      p
                                      (update p (:predictor-id entry) (fnil conj [])
                                              (cond-> {:inputs (:inputs entry)
                                                       :outputs (:outputs entry)
                                                       :source {:kind :bootstrap :example i}}
                                                (:reasoning entry) (assoc :reasoning (:reasoning entry))))))
                                  pool trace))]
              (recur more pool' (conj scores score) (inc attempted)
                     (if pass? (inc passed) passed) errors nil))))))))

(defn bootstrap-few-shot
  "Demos harvested from the teacher's own successful traces — brainyard's
  `bootstrap-few-shot`.

  opts: `:max-bootstrapped` (4), `:max-labeled` (0), `:predictor-id`, plus
  `bootstrap-pool`'s. Labelled top-ups only reach the NAMED predictor: labels
  describe the final outputs, so they say nothing about an intermediate step."
  [teacher trainset metric & {:keys [max-bootstrapped max-labeled predictor-id]
                              :or {max-bootstrapped 4 max-labeled 0}
                              :as opts}]
  (let [{:keys [pool report]} (apply bootstrap-pool teacher trainset metric
                                     (mapcat identity (assoc opts :max-per-predictor max-bootstrapped)))
        params (update-vals pool #(hash-map :demos (vec (take max-bootstrapped %))))
        used (set (keep #(get-in % [:source :example]) (get-in params [predictor-id :demos])))
        params (if (and predictor-id (pos? max-labeled))
                 (update-in params [predictor-id :demos]
                            (fn [demos]
                              (into (vec demos)
                                    (->> (map-indexed vector trainset)
                                         (remove (fn [[i _]] (contains? used i)))
                                         (filter (fn [[_ ex]] (seq (:labels ex))))
                                         (take max-labeled)
                                         (mapv (fn [[i ex]] {:inputs (:inputs ex)
                                                             :outputs (:labels ex)
                                                             :source {:kind :labeled :example i}}))))))
                 params)]
    {:params params
     :report (merge report {:optimizer :bootstrap-few-shot
                            :max-bootstrapped max-bootstrapped
                            :max-labeled max-labeled
                            :demos (update-vals params #(count (:demos %)))})}))

;; --- bootstrap-random-search -----------------------------------------------------------

(defn bootstrap-random-search
  "N seeded subsets of ONE bootstrap pool — brainyard's `bootstrap-random-search`
  (`byj-gly.57`).

  `bootstrap-few-shot` builds a pool and takes the first `max-bootstrapped`
  demos from it. Its own docstring names what that leaves behind: \"The cost is
  candidate diversity, which only a search would spend.\" This spends it.

  **The pool is built ONCE and shared across trials.** That is the budget
  decision, and it is the same one `bootstrap-pool` already makes: the teacher
  is the expensive tier, and re-running it per trial mostly reproduces the same
  traces. What varies between trials is WHICH demos are drawn, not which traces
  exist.

  **Trial 0 is the unshuffled head**, so the search always contains exactly the
  candidate `bootstrap-few-shot` would have produced. With `select-best` keeping
  the earlier row on a tie, a search can therefore never select something worse
  than the optimizer it generalises.

  **Selection is NOT here.** Every candidate becomes a leaderboard row and
  `evaluate/select-best` decides: it already refuses a winner whose evaluation
  did not complete, requires a tested margin over zero-shot, and keeps the
  earlier row on a tie. Re-implementing any of that here would be a second
  answer to a question already answered.

  opts: `:trials` (4), `:seed` (0), plus `bootstrap-few-shot`'s.

  Returns `{:candidates [{:trial :seed :params}] :report {…}}`."
  [teacher trainset metric & {:keys [trials max-bootstrapped predictor-id seed]
                              :or {trials 4 max-bootstrapped 4 seed 0}
                              :as opts}]
  (let [;; the pool is the whole budget; draw from more than one subset's worth
        ;; or every trial sees the same demos in a different order
        per-predictor (max (* trials max-bootstrapped) max-bootstrapped)
        {:keys [pool report]} (apply bootstrap-pool teacher trainset metric
                                     (mapcat identity (assoc opts :max-per-predictor per-predictor)))
        candidates
        (vec (for [t (range trials)]
               (let [trial-seed (+ (long seed) t)
                     params (update-vals pool
                                         (fn [demos]
                                           {:demos (vec (take max-bootstrapped
                                                              (if (zero? t)
                                                                demos
                                                                (shuffle-seeded demos trial-seed))))}))]
                 {:trial t :seed trial-seed :params params})))
        ;; two trials drawing the same demo set are one candidate: the pool can
        ;; be smaller than a subset, and evaluating the same thing twice spends
        ;; the shared budget to learn nothing
        distinct-candidates (vec (map first (vals (group-by #(get-in % [:params predictor-id :demos])
                                                            candidates))))]
    {:candidates distinct-candidates
     :report (merge report {:optimizer :bootstrap-random-search
                            :trials trials
                            :evaluated (count distinct-candidates)
                            :collapsed (- (count candidates) (count distinct-candidates))
                            :seed seed
                            :max-bootstrapped max-bootstrapped
                            :pool (update-vals pool count)})}))
