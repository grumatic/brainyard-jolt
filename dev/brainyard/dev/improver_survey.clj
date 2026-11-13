;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.improver-survey
  "Would an improver agent have anything to work from? (byj-ng7.6, RFC-071
  \"Where a better loop comes from\")

      jolt -A:dev -m brainyard.dev.improver-survey DIR…

  An improver reads a project's trajectory log for repeated failure and
  writes candidate loops; `byj loops propose` stages one only when it wins on
  the project's task set. So before one is built, two things are counted per
  project, from what is already on disk — no model is called:

  - **its input**: the live root turns (not the mock provider's, not a
    subagent's), and among them each failure an improver would look for —
    a turn ended other than by an answer, ended at `max-iterations`, with a
    model error, with failed or refused calls, or failing again and again
    (three or more iterations whose calls failed). A pattern it could act on
    is one that recurs: three turns or more of the same failure;
  - **its proof**: how many kept turns could be tasks today
    (`loop-tasks/turn-tasks` with no machine check: a turn the person
    rewound or committed), and whether the project names a
    `:check-command` that would make every kept turn one. RFC-021's sign test
    needs six tasks moving the same way before a lead is a result.

  Projects are printed as A, B, C… in the order given, with their counts
  only: the labels go in a public RFC, the paths do not."
  (:require [clojure.string :as str]
            [brainyard.changes :as changes]
            [brainyard.config :as config]
            [brainyard.loop-tasks :as loop-tasks]
            [brainyard.trajectory :as trajectory]))

(defn- failed? [t] (or (:error? t) (str/starts-with? (str (:result t)) "{:error")))
(defn- refused? [t] (and (str/starts-with? (str (:result t)) "{:error") (str/includes? (str (:result t)) "refused")))

(defn turn-failures
  "The failures one record shows, as a set of keywords."
  [r]
  (let [its (:iterations r)
        tools (mapcat :tools its)
        failing-its (count (filter #(some failed? (:tools %)) its))]
    (cond-> #{}
      (not= "answer-channel" (some-> (:terminated-by r) name)) (conj :unfinished)
      (= "max-iterations" (some-> (:terminated-by r) name)) (conj :at-cap)
      (some #(= "llm-error" (:channel %)) its) (conj :model-error)
      (some failed? tools) (conj :failed-call)
      (some refused? tools) (conj :refused-call)
      (>= failing-its 3) (conj :failing-again))))

(defn survey
  "One project's counts: `{:turns :sessions :failures {kind n} :recurring
  [kind…] :kept-turns :tasks-now :check-command? :ended {how n}}`, `:ended`
  how the unfinished turns ended."
  [dir]
  (let [cfg {:project-dir dir :working-dir dir}
        sessions (trajectory/sessions cfg)
        live (for [s sessions
                   r (trajectory/read-trajectories cfg s)
                   :when (and (not (:owner r)) (not= :mock (:provider r)))]
               r)
        fails (frequencies (mapcat turn-failures live))
        root (changes/root cfg)
        tt (when root (try (loop-tasks/turn-tasks cfg root nil []) (catch Throwable _ nil)))
        project-config (try (:config (config/load-config {:working-dir dir})) (catch Throwable _ {}))]
    {:turns (count live)
     :sessions (count (distinct (map :session live)))
     :failures (into (sorted-map) fails)
     :recurring (vec (sort (for [[k n] fails :when (>= n 3)] k)))
     ;; a turn whose checkpoint is kept: a task, or dropped for a reason other
     ;; than having none
     :kept-turns (+ (count (:tasks tt)) (count (remove #(#{:not-kept :no-session} (:reason %)) (:dropped tt))))
     :ended (into (sorted-map) (frequencies (keep #(when (not= "answer-channel" (some-> (:terminated-by %) name))
                                                     (name (or (:terminated-by %) :none)))
                                                  live)))
     :tasks-now (count (:tasks tt))
     :check-command? (boolean (not-empty (str (:check-command project-config))))}))

(defn -main [& dirs]
  (let [rows (map-indexed (fn [i d] [(str (char (+ 65 i))) (survey d)]) dirs)]
    (println "project  turns  sessions  unfinished  at-cap  model-error  failed-call  refused-call  failing-again  recurring  tasks-now/kept  check-command")
    (doseq [[l {:keys [turns sessions failures recurring kept-turns tasks-now check-command?]}] rows]
      (println (format "%-8s %5d  %8d  %10d  %6d  %11d  %11d  %12d  %13d  %-9s  %5d/%-5d  %s"
                       l turns sessions (failures :unfinished 0) (failures :at-cap 0) (failures :model-error 0)
                       (failures :failed-call 0) (failures :refused-call 0) (failures :failing-again 0)
                       (if (seq recurring) (str/join "," (map name recurring)) "-")
                       tasks-now kept-turns (if check-command? "yes" "no"))))
    (doseq [[l {:keys [ended]}] rows :when (seq ended)]
      (println (str "  " l " unfinished: " (str/join ", " (for [[k n] ended] (str n " " k))))))
    (let [total (reduce + (map (comp :turns second) rows))]
      (println (str "\n" (count rows) " project(s), " total " live turn(s); "
                    (count (filter (fn [[_ s]] (and (seq (:recurring s)) (>= (:tasks-now s) 6))) rows))
                    " with a recurring failure AND six tasks to prove a candidate on")))))
