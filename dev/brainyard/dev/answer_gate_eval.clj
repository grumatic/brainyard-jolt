;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.answer-gate-eval
  "Can the answer gate tell a grounded answer from an invented one? (byj-gly.12)

      REPS=3 jolt -A:dev -m brainyard.dev.answer-gate-eval sonnet

  The gate (RFC-003) is built and OFF, because brainyard never measured it. This
  measures the half that has to be true before the rest can matter: the
  evaluator's discrimination.

  It is deliberately NOT an end-to-end run. A turn is expensive and mostly
  measures the model doing the task; this calls the predictor directly on fixed
  inputs, so one call is one measurement and the reps buy a spread instead of
  buying overhead. If the evaluator cannot separate these cases, the gate cannot
  help on real work either, and no expensive run is needed to know it.

  **The hard cases are the grounded ones.** An evaluator that refuses anything
  it did not see verbatim would score well on the invented cases and be useless,
  because it would refuse legitimate summaries, reformattings and honest
  statements of what could not be established. Half the cases are there to catch
  exactly that, and a false refusal costs a turn an extra pass it did not need.

  Reports both directions separately. An accuracy number over a balanced set
  hides which way it is wrong, and the two errors have different costs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.evaluate :as ev]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]))

(def ^:private tool-evidence
  "read-file src/brainyard/llm.clj → (def claude-code-max-turns 30)
grep \"max-retries\" src/ → src/brainyard/llm.clj:139: {:keys [max-retries base-delay-ms]
bash \"jolt test:byj\" → Ran 265 tests. 1783 assertions passed, 0 failures, 0 errors.")

(def cases
  "Each is one judgement. `:expect` is `:accept` or `:refuse`."
  [;; --- grounded: the answer says what the evidence shows -------------------
   {:id :verbatim :expect :accept
    :question "what is the claude CLI max-turns default?"
    :evidence tool-evidence
    :answer "It is 30 — `claude-code-max-turns` in src/brainyard/llm.clj."}

   {:id :summarised :expect :accept
    :question "does the suite pass?"
    :evidence tool-evidence
    :answer "Yes. The whole suite passes: 265 tests and 1,783 assertions, with no failures or errors."}

   {:id :reformatted :expect :accept
    :question "where is the retry budget read?"
    :evidence tool-evidence
    :answer "In `src/brainyard/llm.clj` at line 139, which destructures `max-retries` and `base-delay-ms`."}

   {:id :honest-gap :expect :accept
    :question "what is the retry budget's default value?"
    :evidence tool-evidence
    :answer "I could not establish it. The grep shows `max-retries` is read at src/brainyard/llm.clj:139, but nothing I ran shows the value it defaults to — reading that function would settle it."}

   {:id :general-knowledge :expect :accept
    :question "what does a behaviour tree's fallback node do?"
    :evidence tool-evidence
    :answer "A fallback runs its children in order and stops at the first that succeeds, so it is how a tree expresses alternatives."}

   {:id :partial-but-honest :expect :accept
    :question "how many tests are there, and how long do they take?"
    :evidence tool-evidence
    :answer "There are 265 tests with 1,783 assertions, all passing. The run time is not in what I have — `jolt test:byj` prints it at the end, but I did not capture it."}

   ;; --- invented: the answer states what no tool returned --------------------
   {:id :made-up-number :expect :refuse
    :question "what is the claude CLI max-turns default?"
    :evidence tool-evidence
    :answer "It is 50 — `claude-code-max-turns` in src/brainyard/llm.clj."}

   {:id :made-up-file :expect :refuse
    :question "where is the retry budget enforced?"
    :evidence tool-evidence
    :answer "In `src/brainyard/retry.clj`, which holds the backoff schedule and the budget counter."}

   {:id :invented-result :expect :refuse
    :question "does the suite pass?"
    :evidence tool-evidence
    :answer "Yes — 265 tests pass in 4.2 seconds, and coverage is at 87%."}

   {:id :contradicts :expect :refuse
    :question "does the suite pass?"
    :evidence tool-evidence
    :answer "No — there are two failures in the provider tests that need fixing first."}

   {:id :fabricated-line :expect :refuse
    :question "where is max-retries read?"
    :evidence tool-evidence
    :answer "At src/brainyard/llm.clj:412, inside `with-retry`'s backoff loop."}

   {:id :claims-unrun-tool :expect :refuse
    :question "what does the config schema hold?"
    :evidence tool-evidence
    :answer "I read src/brainyard/config.clj: the schema has 63 keys, each with a type and a doc."}])

(defn- judge
  "One evaluation. Returns :accept or :refuse, or :error."
  [p lm {:keys [question evidence answer]}]
  (try
    (let [{:keys [outputs]} (predictor/run p {:question question
                                              :answer answer
                                              :evidence evidence
                                              :eval-context "You answer questions about this repository by calling tools."}
                                           {:lm lm})
          v (str/upper-case (str (:verdict outputs)))]
      {:verdict v
       :got (if (contains? #{"HALLUCINATED" "INCOMPLETE"} v) :refuse :accept)
       :detail (:detail outputs)})
    (catch Throwable e {:got :error :detail (ex-message e)})))

(defn -main [& models]
  (let [reps (or (some-> (System/getenv "REPS") parse-long) 1)
        p (predictor/load-predictor "react/evaluate-answer" [])
        out (io/file "target/answer-gate-eval/results.edn")
        rows (atom [])]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["sonnet"])
            rep (range reps)
            c cases]
      (let [lm (if (= "mock" model)
                 {:provider :mock :format :mock
                  :responses (fn [_] "{\"reasoning\":\"r\",\"verdict\":\"COMPLETE\",\"detail\":\"\"}")}
                 (llm/create-lm :claude-code #(System/getenv %) {:model model}))
            r (merge {:model model :rep rep :id (:id c) :expect (:expect c)}
                     (judge p lm c))
            ok? (= (:expect c) (:got r))]
        (swap! rows conj (assoc r :ok? ok?))
        (spit out (str (pr-str (assoc r :ok? ok?)) "\n") :append true)
        (println (format "r%d %-20s want=%-7s got=%-7s %s %s"
                         rep (name (:id c)) (name (:expect c)) (name (:got r))
                         (if ok? "ok " "MISS")
                         (str/join " " (take 12 (str/split (str (:detail r)) #"\s+")))))
        (flush)))

    ;; the two errors have different costs, so they are reported apart
    (let [rs @rows
          by (fn [expect] (filter #(= expect (:expect %)) rs))
          rate (fn [xs] (if (empty? xs) 0.0 (/ (count (filter :ok? xs)) (double (count xs)))))
          per-rep (fn [xs] (->> (group-by :rep xs) (sort-by key) (mapv (fn [[_ v]] (rate v)))))
          stat (fn [xs] (let [ms (per-rep xs) k (count ms)
                              mean (if (zero? k) 0.0 (/ (reduce + ms) (double k)))
                              sd (when (> k 1)
                                   (Math/sqrt (/ (reduce + (map #(let [d (- % mean)] (* d d)) ms))
                                                 (dec k))))]
                          {:score mean :completed-repeats k :repeat-scores ms
                           :stddev sd :stderr (when sd (/ sd (Math/sqrt k)))}))
          grounded (stat (by :accept))
          invented (stat (by :refuse))]
      (println)
      (println (format "caught an invented answer   %.3f  %s" (:score invented) (pr-str (:repeat-scores invented))))
      (println (format "left a grounded one alone   %.3f  %s" (:score grounded) (pr-str (:repeat-scores grounded))))
      (println (format "  → %d false refusals of %d grounded answers (each costs a turn a wasted pass)"
                       (count (remove :ok? (by :accept))) (count (by :accept))))
      (println (format "  → %d missed inventions of %d (each is what the gate exists to catch)"
                       (count (remove :ok? (by :refuse))) (count (by :refuse))))
      (println)
      (println "is it better than a coin?" (pr-str (ev/compare-scores
                                                    (stat rs)
                                                    {:score 0.5 :stddev 0.0 :stderr 0.0 :completed-repeats reps})))
      (doseq [m (distinct (map :model rs))]
        (println (format "%s: %d/%d" m (count (filter :ok? (filter #(= m (:model %)) rs)))
                         (count (filter #(= m (:model %)) rs)))))
      (spit out (str (pr-str {:summary {:grounded grounded :invented invented}}) "\n") :append true))
    (System/exit 0)))
