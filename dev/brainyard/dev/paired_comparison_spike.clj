(ns brainyard.dev.paired-comparison-spike
  "Does the paired test actually refuse the proposal that the per-pass test
  crowned? (byj-gly.57, divergence `comparison-is-paired-over-examples`)

  The first real compile — opus teacher, ollama/qwen2.5-coder:1.5b-instruct student,
  35 val examples — put `proposal-0` at 0.2571 against a zero-shot 0.2286 and
  gave it the win. Both rows had `:stderr` 0.0, because ollama at temperature 0
  is deterministic, so Welch's t over the per-pass means had no spread left to
  weigh and the margin collapsed to 0.0. 0.0286 over 35 examples is ONE example.

  That report predates `:example-scores`, so it cannot be re-judged from disk:
  a pass mean cannot be unpaired back into items. This re-runs the same two
  candidates on the same val split and asks the paired question of real
  answers rather than of a fixture.

  It needs only the STUDENT — the teacher row is a reference that cannot win,
  and re-scoring it would spend opus quota to learn nothing. ollama is local
  and free, so this is a spike that can be run again.

      jolt -A:dev -e \"(require 'brainyard.dev.paired-comparison-spike)\\
                       (brainyard.dev.paired-comparison-spike/-main)\"

  It runs 2 repeats, as the compile did — with one, `:stderr` is nil and the
  per-pass fallback reports the SINGLE-PASS case rather than the zero-variance
  one that crowned the proposal. A run is 140 calls to a local 1.5b model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [brainyard.config :as config]
            [brainyard.evaluate :as ev]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]
            [brainyard.programs :as programs]))

(def ^:private predictor-id "react/think-act")
(def ^:private dataset-name "opus-full")
(def ^:private proposal-id "1790167828645")
(def ^:private student-label
  "The label the compile recorded, resolved the way `compile-predictor` does.
  A hand-built `{:provider :ollama :model \"…\"}` is NOT the same model: it
  carries no `:base-url` and no `:format`, so every call fails and the run
  reports a clean 0.0 on both candidates in a few seconds."
  "ollama/qwen2.5-coder:1.5b-instruct")
(def ^:private metric-name "set-f1:tool-calls")

(defn- proposal-params
  "The winner the compile wrote. `params.edn` is what a person would install,
  so it is the candidate exactly as it would ship."
  [cfg]
  (let [f (io/file (programs/proposals-dir cfg predictor-id) proposal-id "params.edn")]
    (when-not (.isFile f)
      (throw (ex-info (str "no proposal at " (.getPath f) " — this spike reproduces one specific compile")
                      {:proposal proposal-id})))
    (edn/read-string (slurp f))))

(defn run
  "Re-score zero-shot and the proposal on the val split, paired."
  [& {:keys [repeats] :or {repeats 2}}]
  (let [cfg (config/load-config {})
        student (or (llm/parse-lm-label student-label)
                    (throw (ex-info (str "unresolvable " student-label) {})))
        p (predictor/load-predictor
           predictor-id (predictor/default-roots (or (:project-dir cfg) ".") "predictors"))
        metric (programs/resolve-metric metric-name)
        ds (programs/load-dataset cfg predictor-id dataset-name)
        {:keys [val]} (ev/split (:examples ds))
        ;; the same base every candidate is laid over in `compile-predictor`
        base (select-keys (:params (predictor/resolve-params
                                    p {:params-roots [(.getPath (programs/programs-root cfg))]}))
                          [:instructions :field-descs])
        prog (fn [params] (programs/predictor-program
                           p {:lm student :lm-tiers (:agent-lm-tiers cfg)
                              :params {predictor-id params}}))
        row (fn [label params]
              (println (str "  scoring " (name label) " on " (count val) " examples…"))
              (let [r (merge {:candidate label}
                             (select-keys (ev/evaluate (prog params) val metric :repeats repeats)
                                          ev/leaderboard-row-keys))]
                (println (str "    score " (:score r) ", " (:calls r) " calls, "
                              (:errors r) " errors"))
                ;; a run where every call failed scores a clean 0.0 and reads
                ;; like a measurement; it is not one
                (when (pos? (:errors r 0))
                  (println "    WARNING: errored calls score 0 — this is not a measurement"))
                r))
        zero (row :zero-shot base)
        prop (row :proposal-0 (proposal-params cfg))
        paired (ev/compare-scores prop zero)
        ;; what the code did before the fix: the same two rows, per-pass
        welch (ev/compare-scores (dissoc prop :example-scores) (dissoc zero :example-scores))
        diffs (mapv - (:example-scores prop) (:example-scores zero))]
    {:n (count val)
     :zero-shot (:score zero)
     :proposal-0 (:score prop)
     ;; the proposal ERRORS more than the baseline it beat: its demos make the
     ;; prompt long enough that a 1.5b model fails on it more often. An errored
     ;; call scores 0 (RFC-021), so that cost is already inside the score —
     ;; which makes the 0.0286 "gain" even harder to believe
     :errors {:zero-shot (:errors zero) :proposal-0 (:errors prop)}
     :examples-better (count (filter pos? diffs))
     :examples-worse (count (filter neg? diffs))
     :examples-same (count (filter zero? diffs))
     :paired paired
     :per-pass welch}))

(defn -main [& _]
  (println "paired vs per-pass, on the compile that crowned a proposal (byj-gly.57)")
  (let [r (run)]
    (pp/pprint r)
    (println)
    (println (if (:better? (:paired r))
               "PAIRED SAYS BETTER — the proposal survives a test of the examples."
               "PAIRED SAYS NO — the per-pass margin crowned what the examples do not support."))))
