;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.thought-spike
  "byj-gly.280.2: what does an iteration's thought tell the person watching?

      jolt -A:dev -m brainyard.dev.thought-spike [DIR ...]

  Scores every thought in the trajectories under each DIR (default: this
  project's .brainyard/sessions), a channel at a time:

    restates  opens by restating what the reader already has: the question
              (\"The user wants…\"), the empty history (\"No history yet…\"),
              the previous iteration (\"The previous iteration…\", \"…already
              returned…\"), or that the answer is coming (\"I have enough…\",
              \"…so I can answer\")
    q-echo    most of its words are the question's
    p-echo    most of its words are the previous thought's
    a-echo    (answer channel) most of its words are in the answer below it
    words     its length

  A word is a lower-cased run of letters of four or more, so function words
  and short tokens do not count as echoes; \"most\" is ≥ 60%. The measures
  are crude on purpose — the same regexes over two sets of runs say which
  wording restates less; a hand-read sample (`SAMPLE=n`) says whether what is
  left is worth reading."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def restating
  "Openings that tell the reader what they already know."
  [#"(?i)^the user(?:'s)? (?:wants|is asking|asks|asked|requests|requested|request|question|sent|needs)"
   #"(?i)^(?:there is |there's )?no (?:prior |previous )?(?:history|iterations?|iteration history|results?)"
   #"(?i)^(?:this is the )?first iteration"
   #"(?i)^(?:the )?previous iterations?"
   #"(?i)^(?:i (?:now )?have|we have|all) (?:enough|everything|all|the (?:full|complete))"
   #"(?i)(?:so|and) (?:i|we) can (?:now )?(?:answer|respond|present|provide)"
   #"(?i)no (?:additional|further|more) (?:tool calls?|tools?|work|calls?|lookups?) (?:is |are )?needed"
   ;; the previous iteration retold: the result is in
   #"(?i)\balready\b[^.]{0,80}\b(?:returned|completed|ran|run|called|contains|retrieved|executed|resolved|done)\b"
   #"(?i)the answer is (?:complete|ready)|answering directly|can answer directly"])

(defn- words [s]
  (set (map str/lower-case (re-seq #"[A-Za-z]{4,}" (str s)))))

(defn- echo? [thought other]
  (let [t (words thought)]
    (and (seq t) (>= (/ (count (filter (words other) t)) (count t)) 3/5))))

(defn- read-turns
  "A session's trajectory file: one record per turn (RFC-025)."
  [f]
  (try (edn/read-string {:default (fn [_ v] v)} (str "[" (slurp f) "\n]"))
       (catch Throwable _ [])))

(defn thoughts
  "Every scored thought under `dirs`: {:channel :thought :restates … :words}."
  [dirs]
  (for [d dirs
        f (file-seq (io/file d))
        :when (= "trajectory.edn" (.getName f))
        t (read-turns f)
        [prev it] (partition 2 1 (cons nil (:iterations t)))
        :let [th (:thought it)]
        :when (not (str/blank? th))]
    {:channel (:channel it)
     :thought th
     :restates (boolean (some #(re-find % th) restating))
     :q-echo (echo? th (:question t))
     :p-echo (boolean (and prev (echo? th (:thought prev))))
     :a-echo (boolean (and (= "answer" (:channel it)) (echo? th (:answer it))))
     :words (count (str/split (str/trim th) #"\s+"))}))

(defn score
  "Per channel: how many, and the share of each measure."
  [ts]
  (into (sorted-map)
        (for [[ch xs] (group-by :channel ts)
              :let [n (count xs) pct #(Math/round (* 100.0 (/ (count (filter % xs)) n)))]]
          [ch {:n n :restates (pct :restates) :q-echo (pct :q-echo) :p-echo (pct :p-echo)
               :a-echo (pct :a-echo) :words (Math/round (double (/ (reduce + (map :words xs)) n)))}])))

(defn -main [& dirs]
  (let [dirs (or (seq dirs) [".brainyard/sessions"])
        ts (vec (thoughts dirs))
        n (some-> (System/getenv "SAMPLE") parse-long)]
    (println (count ts) "thoughts under" (str/join ", " dirs))
    (println (format "%-8s %5s %9s %7s %7s %7s %6s" "channel" "n" "restates%" "q-echo%" "p-echo%" "a-echo%" "words"))
    (doseq [[ch {:keys [n restates q-echo p-echo a-echo words]}] (score ts)]
      (println (format "%-8s %5d %9d %7d %7d %7d %6d" ch n restates q-echo p-echo a-echo words)))
    (when n
      (doseq [t (take n (shuffle ts))]
        (println (str "\n[" (:channel t) (when (:restates t) " restates") "] " (:thought t)))))
    (shutdown-agents)))
