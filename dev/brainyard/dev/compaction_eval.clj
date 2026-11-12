;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.compaction-eval
  "What do tiers 0 and 1 actually buy? (RFC-014 'Measurement plan', step 1.)

  Replays recorded turns through the tiers and reports tokens at each stage —
  no model calls, so it costs nothing and can run on every change.

      jolt -A:dev -m brainyard.dev.compaction-eval                  # synthetic shapes
      jolt -A:dev -m brainyard.dev.compaction-eval target/trace.edn # recorded turns

  A trace file is a sequence of {:question :iterations [...]} maps — the shape
  `agent/ask` keeps in st-memory, which `:on-event :agent.iteration/post` or a
  trajectory dump can write out."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.config :as config]
            [brainyard.context :as context]))

(defn- text [n] (apply str (repeat n "x")))

(defn- result [tool path chars]
  {:tool-name tool :tool-args (cond-> {} path (assoc :path path)) :tool-result (text chars)})

(def synthetic
  "Turn shapes drawn from the measurements in RFC-014: navigation is cheap,
  content is 19–28K chars, a wide search hits the cap."
  [{:name "read-heavy (8 file reads)"
    :iterations (vec (for [i (range 1 9)]
                       {:iteration i :thought "reading" :channel "tool"
                        :tool-results [(result "read-file" (str "src/f" i ".clj") 24000)]}))}
   {:name "re-reads one file 6 times"
    :iterations (vec (for [i (range 1 7)]
                       {:iteration i :thought "editing" :channel "tool"
                        :tool-results [(result "read-file" "src/core.clj" 24000)]}))}
   {:name "navigation only"
    :iterations (vec (for [i (range 1 9)]
                       {:iteration i :thought "looking" :channel "tool"
                        :tool-results [(result "list-dir" nil 300)]}))}
   {:name "mixed: greps, reads, a shell"
    :iterations (vec (for [i (range 1 11)]
                       {:iteration i :thought "working" :channel "tool"
                        :tool-results [(case (mod i 3)
                                         0 (result "grep" nil 16000)
                                         1 (result "read-file" (str "src/f" i ".clj") 24000)
                                         2 (result "bash" nil 700))]}))}])

(defn- cap
  "Tier 0: what the loop already does to each result as it is recorded."
  [config records]
  (mapv (fn [r]
          (update r :tool-results
                  (fn [rs] (mapv (fn [x]
                                   (let [s (str (:tool-result x))
                                         n (:max-output-chars config)]
                                     (cond-> x (> (count s) n)
                                             (assoc :tool-result (str (subs s 0 n) "\n…[truncated]")))))
                                 rs))))
        records))

(defn measure
  "Tokens after each stage for one turn, under `config`."
  [config {:keys [name iterations question]}]
  (let [dir (str "target/compaction-eval/" (str/replace (str name) #"[^a-z0-9]+" "-"))
        config (merge config {:working-dir dir :project-dir dir})
        _ (.mkdirs (io/file dir))
        ctx {:config config :zones {:system (text 23500)}}
        mem {:question (or question "q") :turn-number 1}
        raw (vec iterations)
        capped (cap config raw)
        tokens (fn [records] (context/prompt-tokens ctx (assoc mem :iterations records)))
        {:keys [records steps over-budget?]} (context/enforce ctx (assoc mem :iterations capped))]
    {:turn name
     :raw (tokens raw)
     :tier0 (tokens capped)
     :tier1 (tokens records)
     :budget (context/budget config)
     :steps steps
     :over-budget? over-budget?}))

(def windows
  "Compaction matters as the budget tightens, so the sweep is the measurement —
  one window is only ever half the answer."
  [200000 128000 64000 32000])

(defn -main [& [trace-file]]
  (let [turns (if trace-file
                (map-indexed (fn [i t] (assoc t :name (str "recorded-" i)))
                             (edn/read-string (slurp trace-file)))
                synthetic)]
    (doseq [window windows]
      (let [config (assoc config/defaults :max-context-tokens window)
            rows (mapv #(measure config %) turns)
            sum (fn [k] (reduce + (map k rows)))
            pct (fn [a b] (* 100.0 (- 1 (/ (double b) a))))]
        (println (format "\n=== window %d → budget %d tokens (cap %d chars, keep %d verbatim) ==="
                         window (context/budget config) (:max-output-chars config)
                         (:keep-verbatim-iterations config)))
        (println (format "%-32s %9s %9s %9s  %s" "turn" "raw" "tier0" "tier1" "steps"))
        (doseq [{:keys [turn raw tier0 tier1 steps over-budget?]} rows]
          (println (format "%-32s %9d %9d %9d  %s%s" turn raw tier0 tier1
                           (str/join " " (map #(str (name (:step %)) "×" (:n %)) steps))
                           (if over-budget? " STILL OVER" ""))))
        (println (format "total %d → %d (tier 0, −%.0f%%) → %d (tier 1, −%.0f%% of raw)"
                         (sum :raw) (sum :tier0) (pct (sum :raw) (sum :tier0))
                         (sum :tier1) (pct (sum :raw) (sum :tier1))))))
    (System/exit 0)))
