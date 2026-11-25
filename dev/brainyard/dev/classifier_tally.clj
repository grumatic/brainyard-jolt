;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.classifier-tally
  "What the :auto classifier said of the commands a pattern picks out, across
  simulation runs' event logs (byj-6xh): how many it judged, by verdict, fresh
  or from the session's cache, and the reason it gave for each it did not
  allow. RFC-072 \"An attachment's call and the classifier\" was measured with

    jolt -A:dev -m brainyard.dev.classifier-tally 'export START=|package.json \\] && grep' ~/Projects/byj-sim/*

  the pattern matching the two attachments the rules and extensions plans
  attach (dev/sim/extensions/ext/work.edn and tests.edn). Reads only."
  (:require [clojure.java.io :as io]
            [brainyard.projects]
            [clojure.string :as str]))

(defn tally
  "`{:runs n :verdicts {[verdict cached?] n} :doubts [[run reason] …]}` over the
  `dirs` whose event log (`byj project path logs`) holds a command `pattern` matches."
  [pattern dirs]
  (let [re (re-pattern pattern)]
    (reduce
     (fn [acc dir]
       (let [f (brainyard.projects/runtime-path dir "logs" "events.log")
             t (when (.isFile f) (slurp f))]
         (if-not (and t (re-find re t))
           acc
           (reduce (fn [acc line]
                     (if (and (str/includes? line ":event :permission/classified") (re-find re line))
                       (let [v (keyword (second (re-find #":verdict :(\w+)" line)))
                             cached? (str/includes? line ":cached? true")]
                         (cond-> (update-in acc [:verdicts [v cached?]] (fnil inc 0))
                           (not= :allow v)
                           (update :doubts conj [(.getName (io/file dir))
                                                 (second (re-find #":reason \"((?:[^\"\\\\]|\\\\.){0,160})" line))])))
                       acc))
                   (update acc :runs inc)
                   (str/split-lines t)))))
     {:runs 0 :verdicts {} :doubts []}
     dirs)))

(defn -main [pattern & dirs]
  (let [{:keys [runs verdicts doubts]} (tally pattern dirs)]
    (println "runs:" runs)
    (doseq [[[v cached?] n] (sort-by (comp str key) verdicts)]
      (println (str "  " (name v) (if cached? " (cached)" "") ": " n)))
    (doseq [[run reason] doubts]
      (println (str "  doubt, " run ": " reason)))))
