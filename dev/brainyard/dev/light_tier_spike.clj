;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.light-tier-spike
  "Can the side tasks run on a light model? (byj-gly.367, RFC-005 'The light
  tier'.) Recorded `claude -p` calls of each side-task predictor — the exact
  argv and stdin from the event logs — run again on haiku and on sonnet, side
  by side, with what each cost.

      jolt -A:dev -m brainyard.dev.light-tier-spike [N] [LOG-DIR…]

  N calls per predictor (default 5), from the event logs under each LOG-DIR's
  .brainyard/logs (default: this project), newest first, one per session so a
  long session does not fill the sample. Every row is two real model calls on
  the claude CLI's subscription. Results: target/light-tier/results.edn; the
  outputs are printed for a person to read — whether a recap kept its facts is
  read, not counted."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.dev.replay :as replay]
            [brainyard.json :as json]
            [jolt.process :as p]))

(def predictors
  "Each side task by the first words of its instructions, which its system
  prompt carries."
  [["session/recap" "You write the recap a person reads"]
   ["session/rephrase-command" "A person typed a slash command in a coding session"]
   ["session/compact" "You compress the earlier part of a coding session"]
   ["react/compact" "You compress the older part of an agent"]])

(defn- which [call]
  (let [sys (str (replay/arg call "--system-prompt") (:prompt call))]
    (some (fn [[id words]] (when (str/includes? sys words) id)) predictors)))

(defn- on-model [args model]
  (let [args (vec args) i (.indexOf args "--model")]
    (if (neg? i) (into args ["--model" model]) (assoc args (inc i) model))))

(defn- run-on [call model]
  (let [env (dissoc (into {} (System/getenv)) "CLAUDECODE")
        t0 (System/currentTimeMillis)
        r @(apply p/process {:in (:prompt call) :out :string :err :string :env env} (on-model (:args call) model))
        res (last (filter #(= "result" (:type %))
                          (let [all (try (json/read-str (:out r)) (catch Throwable _ nil))]
                            (if (sequential? all) all [all]))))]
    {:model model
     :exit (:exit r)
     :ms (- (System/currentTimeMillis) t0)
     :cost (:total_cost_usd res)
     :output (or (:structured_output res) (:result res))}))

(defn- one-per-session [calls]
  (reduce (fn [acc c] (if (some #(= (:session %) (:session c)) acc) acc (conj acc c))) [] calls))

(defn sample
  "Up to n recorded calls of each predictor: one per session, taken from each
  log in turn, so one busy project does not fill the sample."
  [n dirs]
  (let [logs (mapv #(replay/calls {:project-dir % :working-dir %}) dirs)]
    (into {}
          (for [[id _] predictors]
            (let [per-log (mapv #(one-per-session (filter (fn [c] (= id (which c))) %)) logs)
                  turns (apply max 0 (map count per-log))]
              [id (->> (range turns)
                       (mapcat (fn [i] (keep #(get % i) per-log)))
                       (take n)
                       vec)])))))

(defn -main [& args]
  (let [[n dirs] (if (some->> (first args) (re-matches #"\d+"))
                   [(parse-long (first args)) (rest args)]
                   [5 args])
        dirs (if (seq dirs) dirs ["."])
        picked (sample n dirs)
        rows (vec (for [[id calls] picked, c calls]
                    {:predictor id :session (:session c) :recorded-on (:model c)
                     :runs (mapv #(run-on c %) ["haiku" "sonnet"])}))]
    (io/make-parents "target/light-tier/results.edn")
    (spit "target/light-tier/results.edn" (with-out-str (pp/pprint rows)))
    (doseq [{:keys [predictor session recorded-on runs]} rows]
      (println (str "\n=== " predictor " · " session " (recorded on " recorded-on ")"))
      (doseq [{:keys [model exit ms cost output]} runs]
        (println (format "--- %s  exit %s  %.1fs  $%.4f" model exit (/ ms 1000.0) (double (or cost 0))))
        (println (if (string? output) output (with-out-str (pp/pprint output))))))
    (println "\n=== cost per call, mean")
    (doseq [[id rs] (group-by :predictor rows)
            m ["haiku" "sonnet"]
            :let [cs (keep #(:cost (first (filter (fn [r] (= m (:model r))) (:runs %)))) rs)]
            :when (seq cs)]
      (println (format "%-26s %-7s $%.4f over %d" id m (/ (reduce + cs) (count cs)) (count cs))))
    (shutdown-agents)))
