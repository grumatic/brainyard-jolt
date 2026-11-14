;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.sim-tally
  "Runs of simulation plans, repeated, counted together (byj-93l, RFC-041,
  RFC-072): one run is one sample, and a rule kept more often with a
  project's extensions than without is a claim about many.

      jolt -A:dev -m brainyard.dev.sim-tally RUN-DIR…

  Reads each run's `.sim/report.edn`, groups the runs by plan, and prints per
  plan: how many runs; each check's passes over the runs that reached its
  stage, per stage and in all; the notices counted (`:notice-markers`); the
  cost and seconds, mean and range. No model is called."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn read-run [dir]
  (let [f (io/file dir ".sim" "report.edn")]
    (when (.isFile f) (assoc (edn/read-string (slurp f)) :dir (str dir)))))

(defn- stage-cost [s] (or (get-in s [:metrics :cost-usd]) 0))

(defn tally
  "`{plan-id {:runs n :checks {[stage check] [pass n]} :rules {check [pass n]}
  :notices {marker n} :cost [..] :seconds [..]}}` over `runs`."
  [runs]
  (into (sorted-map)
        (for [[plan rs] (group-by #(get-in % [:plan :id]) runs)]
          [plan {:runs (count rs)
                 :checks (reduce (fn [acc [k pass?]] (update acc k (fn [[p n]] [(+ (or p 0) (if pass? 1 0)) (inc (or n 0))])))
                                 (sorted-map)
                                 (for [r rs s (:stages r) c (:checks s)]
                                   [[(name (:id s)) (:name c)] (:pass? c)]))
                 :rules (reduce (fn [acc [k pass?]] (update acc k (fn [[p n]] [(+ (or p 0) (if pass? 1 0)) (inc (or n 0))])))
                                (sorted-map)
                                (for [r rs s (:stages r) c (:checks s)] [(:name c) (:pass? c)]))
                 :stages-passed (vec (for [r rs] [(count (filter #(= :pass (:verdict %)) (:stages r))) (count (:stages r))]))
                 :notices (apply merge-with + (for [r rs s (:stages r)] (or (get-in s [:metrics :notices]) {})))
                 :cost (mapv #(reduce + (map stage-cost (:stages %))) rs)
                 :seconds (mapv #(reduce + (map (fn [s] (or (:seconds s) 0)) (:stages %))) rs)}])))

(defn- mean [xs] (if (seq xs) (/ (reduce + xs) (double (count xs))) 0.0))

(defn report
  "The tally as text: per plan, the checks by name over every stage, then
  each stage's."
  [t]
  (str/join
   "\n"
   (for [[plan {:keys [runs checks rules stages-passed notices cost seconds]}] t]
     (str "## " plan " — " runs " run(s)\n"
          "stages passed per run: " (str/join ", " (map (fn [[p n]] (str p "/" n)) stages-passed)) "\n"
          (format "cost: mean $%.3f (%s)  seconds: mean %.0f (%s)\n" (mean cost)
                  (str/join ", " (map #(format "$%.2f" (double %)) cost)) (mean seconds) (str/join ", " seconds))
          (when (seq notices) (str "notices: " (pr-str notices) "\n"))
          "\nby check, every stage:\n"
          (str/join (for [[c [p n]] rules] (format "  %-22s %3d/%-3d\n" c p n)))
          "\nby stage:\n"
          (str/join (for [[[s c] [p n]] checks] (format "  %-8s %-22s %d/%d\n" s c p n)))))))

(defn -main [& dirs]
  (let [runs (keep read-run dirs)]
    (println (report (tally runs)))))
