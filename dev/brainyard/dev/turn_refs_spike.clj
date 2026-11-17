;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.turn-refs-spike
  "Measurements behind byj-ayk (docs/RFCS/RFC-069-turn-checkpoints.md):
  which turn refs a repository keeps, under the rule it had — its newest
  `:changes-turns`, whoever's — and under the one it has — those, and each
  session's own newest while it was used within `:changes-days` — and
  what keeping them costs.

      jolt -A:dev -m brainyard.dev.turn-refs-spike [REPO …]

  1. A workload replayed through `changes/expired` as its turns end, no
     git: a long session of 92 turns over two days, resumed for three turns
     on day 20 and day 45, beside six short sessions of five turns a day
     for sixty days. For each rule: the day the long session's first turn
     was lost, whether it can still be taken back on day 45, and how many
     refs the repository holds at most and at the end.
  2. For each REPO given, what its byj refs hold that no branch, tag or
     remote does: the objects by type, their size on disk as they are
     (loose), and packed. Reads only."
  (:require [clojure.string :as str]
            [brainyard.changes :as changes]
            [brainyard.effect :as fx]))

(def ^:private day (* 24 60 60))

(defn- workload
  "Every turn's end as `{:session :n :t}`, in time order."
  []
  (let [long-s (concat (for [n (range 1 93)] {:session "long" :n n :t (+ (* n 1800))})
                       (for [[d from] [[20 93] [45 96]] k (range 3)]
                         {:session "long" :n (+ from k) :t (+ (* d day) (* k 600))}))
        short-s (for [d (range 60) s (range 6) n (range 1 6)]
                  {:session (str "d" d "-s" s) :n n :t (+ (* d day) 3600 (* s 3600) (* n 300))})]
    (sort-by :t (concat long-s short-s))))

(defn- replay
  "Turns ending in order, the repository pruned by `opts` after each:
  `{:lost-first-on :first-kept-on-45 :peak :end}`."
  [opts]
  (loop [[turn & more] (workload) refs [] peak 0 lost nil kept-45 nil]
    (if-not turn
      {:lost-first-on lost :first-kept-on-45 kept-45 :peak peak :end (count refs)}
      (let [r (assoc turn :ref (str (:session turn) "/" (:n turn)))
            refs (conj refs r)
            last-used (reduce (fn [m {:keys [session t]}] (update m session (fnil max 0) t)) {} refs)
            gone (changes/expired refs last-used (:t turn) opts)
            refs (vec (remove (comp gone :ref) refs))
            first? (some #(= "long/1" (:ref %)) refs)
            d (quot (:t turn) day)]
        (recur more refs (max peak (count refs))
               (or lost (when-not first? d))
               (if (and (nil? kept-45) (= "long" (:session turn)) (>= d 45)) (boolean first?) kept-45))))))

(defn- sh [dir & args]
  (str (:out (apply fx/sh {:dir dir :out :string :err :string} args))))

(defn- byj-objects
  "What `dir`'s byj refs hold beyond its branches, tags and remotes."
  [dir]
  (let [refs (remove str/blank? (str/split-lines (sh dir "git" "for-each-ref" "--format=%(refname)" "refs/byj/")))
        objs (->> (str/split-lines (apply sh dir "git" "rev-list" "--objects" (concat refs ["--not" "--branches" "--tags" "--remotes"])))
                  (map #(first (str/split % #" "))) (remove str/blank?))
        sized (->> (str/split-lines (:out (fx/sh {:dir dir :out :string :in (str/join "\n" objs)}
                                                 "git" "cat-file" "--batch-check=%(objecttype) %(objectsize:disk)")))
                   (keep #(let [[t n] (str/split % #" ")] (when n [t (parse-long n)]))))]
    {:turns (count (filter #(str/starts-with? % "refs/byj/turns/") refs))
     ;; as a gc would pack them, though without the deltas against what the
     ;; branches hold that a whole repack could also use: an upper bound
     :packed-kb (quot (or (parse-long (str/trim (str (:out (fx/sh {:dir dir :out :string :err :string :in (str/join "\n" refs)}
                                                             "sh" "-c" (str "git rev-list --objects --stdin --not --branches --tags --remotes"
                                                                            " | git pack-objects -q --stdout | wc -c")))))) 0)
                      1024)
     :by-type (into (sorted-map) (for [[t xs] (group-by first sized)] [t {:n (count xs) :disk-kb (quot (reduce + (map second xs)) 1024)}]))}))

(defn -main [& repos]
  (println "the workload: one 92-turn session over two days, resumed on day 20 and day 45; six 5-turn sessions a day for 60 days")
  (doseq [[label opts] [["the repository's newest 200 (until byj-ayk)" {:keep-n 200 :days -1}]
                        ["and each session's own newest 200 within 30 days" {:keep-n 200 :days 30}]]]
    (println (format "  %-52s %s" label (pr-str (replay opts)))))
  (doseq [dir repos]
    (println dir (pr-str (byj-objects dir)))))
