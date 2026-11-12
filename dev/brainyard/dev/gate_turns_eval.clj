;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.gate-turns-eval
  "Does the answer gate pay on ordinary turns? (byj-gly.12)

      REPS=2 jolt -A:dev -m brainyard.dev.gate-turns-eval sonnet

  `answer_gate_eval` settled that the evaluator discriminates: it caught every
  invented answer in a fixed set. What it could not say is whether invention
  happens often enough on real work for the gate to be worth its call. This
  measures that, and the number that decides it is **how often the gate fires
  at all** — a gate that never refuses cannot improve anything, however good it
  is at judging.

  Both arms run the same questions about this repository with the same tools:
  `:max-refinements 0` against `:max-refinements 2`. Each question has one
  exact answer, checked from the source before the run, so a script can grade
  it without a second model's opinion.

  The questions are a deliberate mix. Some are one grep from the answer; some
  need two steps, or ask for something whose obvious value is wrong, because a
  set of only easy questions would put both arms on the ceiling and measure
  nothing — which is how the pipeline comparison ended up able to price a
  feature but not judge it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.evaluate :as ev]
            [brainyard.llm :as llm]
            [brainyard.tools :as tools]))

(defn- has? [re] (fn [a] (boolean (re-find re (str a)))))

(def tasks
  "Each answer was read out of the source before the run. `:wrong` names a
  plausible mistake, so a confident wrong answer is visible as such rather than
  merely absent."
  [{:id :max-iterations
    :prompt "In this repository, what is the DEFAULT value of the :max-iterations config key? Answer with just the number."
    :ok? (has? #"\b20\b") :wrong (has? #"\b(100|10|5)\b")}

   {:id :node-kinds
    :prompt "How many node kinds does the behavior-tree interpreter in src/brainyard/bt.clj dispatch on? Answer with just the number."
    :ok? (has? #"\b7\b") :wrong (has? #"\b(5|6|8)\b")}

   {:id :builtin-tools
    :prompt "How many tools are in brainyard.tools/builtin-tools? Answer with just the number."
    :ok? (has? #"\b6\b") :wrong (has? #"\b(5|7|8)\b")}

   {:id :bundled-agents
    :prompt "Which agents ship in this binary's built-in roster? Answer with just their names."
    :ok? (fn [a] (every? #(str/includes? (str/lower-case (str a)) %) ["explore" "plan" "edit" "beads"]))
    :wrong (has? #"(?i)\b(research|router|todo|exec)\b")}

   {:id :max-turns
    :prompt "What value does brainyard.llm/claude-code-max-turns hold? Answer with just the number."
    ;; the obvious guesses are wrong, and the docstring around it talks about
    ;; other numbers — a model that skims rather than reads gets this wrong
    :ok? (has? #"\b4\b") :wrong (has? #"\b(30|10|2|100)\b")}

   {:id :tool-lisp-ops
    :prompt "How many function names are on the Tool Lisp allowlist (brainyard.tool-lisp/ops)? Answer with just the number."
    :ok? (has? #"\b143\b") :wrong (has? #"\b(1[0-4][0-9]|[5-9][0-9])\b")}

   {:id :divergences
    :prompt "How many entries are in docs/divergences.edn? Answer with just the number."
    :ok? (has? #"\b59\b") :wrong (has? #"\b(5[0-8]|6[0-9])\b")}

   {:id :predictors
    :prompt "How many predictor specs ship under resources/brainyard/predictors/? Answer with just the number."
    :ok? (has? #"\b4\b") :wrong (has? #"\b(1|2|3|5|6)\b")}])

(defn run-task [model refinements {:keys [id prompt ok? wrong]}]
  (let [gate-calls (atom 0)
        refusals (atom [])
        t0 (System/currentTimeMillis)
        r (try
            (agent/ask prompt
                       {:lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
                        :tools tools/builtin-tools
                        :config {:working-dir "." :project-dir "."
                                 :max-iterations 10 :persist-session? false
                                 :max-refinements refinements}
                        :on-event (fn [e d]
                                    (case e
                                      :agent.answer/evaluated (swap! gate-calls inc)
                                      :agent.answer/refused (swap! refusals conj (:reason d))
                                      nil))})
            (catch Throwable e {:error (ex-message e)}))
        a (str (:answer r))]
    {:model model :task id :refinements refinements
     :score (if (ok? a) 1.0 0.0)
     :confidently-wrong (boolean (and (not (ok? a)) (wrong a)))
     :gate-calls @gate-calls :refusals (count @refusals) :refusal-reasons (vec @refusals)
     :llm-calls (get-in r [:usage :llm-calls]) :iterations (:iterations r)
     :answer (when (> (count a) 0) (subs a 0 (min 160 (count a))))
     :error (:error r) :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn- stat [rows]
  (let [ms (->> (group-by :rep rows) (sort-by key)
                (mapv (fn [[_ rs]] (/ (reduce + (map :score rs)) (double (count rs))))))
        k (count ms)
        mean (if (zero? k) 0.0 (/ (reduce + ms) (double k)))
        sd (when (> k 1) (Math/sqrt (/ (reduce + (map #(let [d (- % mean)] (* d d)) ms)) (dec k))))]
    {:score mean :completed-repeats k :repeat-scores ms
     :stddev sd :stderr (when sd (/ sd (Math/sqrt k)))}))

(defn -main [& models]
  (let [out (io/file "target/gate-turns-eval/results.edn")
        reps (or (some-> (System/getenv "REPS") parse-long) 1)
        rows (atom [])]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["sonnet"])
            rep (range reps)
            task tasks
            refinements [0 2]]
      (let [r (assoc (run-task model refinements task) :rep rep)]
        (swap! rows conj r)
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "r%d %-15s gate=%-3s %s calls=%-3s gate-calls=%-2d refused=%d  %s"
                         rep (name (:task r)) (if (pos? refinements) "on" "off")
                         (if (= 1.0 (:score r)) "ok  " (if (:confidently-wrong r) "WRONG" "miss"))
                         (:llm-calls r) (:gate-calls r) (:refusals r)
                         (str/replace (str (:answer r)) #"\s+" " ")))
        (flush)))
    (let [rs @rows
          on (filter #(pos? (:refinements %)) rs)
          off (remove #(pos? (:refinements %)) rs)]
      (println)
      (println (format "gate on  %.3f  %s" (:score (stat on)) (pr-str (:repeat-scores (stat on)))))
      (println (format "gate off %.3f  %s" (:score (stat off)) (pr-str (:repeat-scores (stat off)))))
      (println)
      (println (format "THE NUMBER THAT DECIDES IT: the gate refused %d of %d answers it looked at"
                       (reduce + (map :refusals on)) (reduce + (map :gate-calls on))))
      (println (format "  confidently wrong — off: %d/%d, on: %d/%d"
                       (count (filter :confidently-wrong off)) (count off)
                       (count (filter :confidently-wrong on)) (count on)))
      (println (format "  cost: %.1f calls with the gate, %.1f without"
                       (/ (reduce + (keep :llm-calls on)) (double (max 1 (count on))))
                       (/ (reduce + (keep :llm-calls off)) (double (max 1 (count off))))))
      (doseq [r (filter #(pos? (:refusals %)) on)]
        (println (format "  refused [%s]: %s" (name (:task r))
                         (str/join " | " (map #(subs (str %) 0 (min 110 (count (str %))))
                                              (:refusal-reasons r))))))
      (let [c (ev/compare-scores (stat on) (stat off))]
        (println)
        (println (format "diff %+.3f, margin %s, tested? %s → %s"
                         (:diff c) (if-let [m (:margin c)] (format "%.3f" m) "—") (:tested? c)
                         (if (:better? c) "the gate helps" "NO detectable difference"))))
      (spit out (str (pr-str {:summary {:on (stat on) :off (stat off)}}) "\n") :append true))
    (System/exit 0)))
