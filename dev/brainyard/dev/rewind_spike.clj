(ns brainyard.dev.rewind-spike
  "Measurements behind byj-gly.485 (docs/RFCS/RFC-069-turn-checkpoints.md):
  taking a session's conversation back to turn K — truncate the session in
  place, or fork a new one cut before K?

      jolt -A:dev -m brainyard.dev.rewind-spike [TURNS] [K]

  A session of TURNS turns (5) on the mock provider, in a scratch git
  project with persistence and the trajectory on, each turn writing a file.
  Then, for K (3), what the session's stores hold of turn K and after —
  everything a truncation in place must rewrite, or leave saying something
  that no longer happened — and what a fork costs: the files it writes and
  how long `persist/fork!` (cut before K) and `changes/rewind!` (the files
  back to K's start) take. Spends nothing; leaves only the scratch project."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.changes :as changes]
            [brainyard.context :as context]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.session :as session]
            [brainyard.trajectory :as trajectory]))

(defn- ms [f] (let [t0 (System/nanoTime) r (f)] [r (/ (- (System/nanoTime) t0) 1e6)]))

(defn- run-session! [dir id turns]
  (let [queue (atom (vec (for [n (range 1 (inc turns))] (str "turn " n ": write f" n))))
        replies (atom (vec (mapcat (fn [n] [(json/write-str {:reasoning "r" :answer ""
                                                             :tool-calls [{:tool-name "write-file"
                                                                           :tool-args {:path (str dir "/f" n ".txt") :content (str n "\n")}}]})
                                            (json/write-str {:reasoning "r" :tool-calls [] :answer (str "wrote f" n)})])
                                   (range 1 (inc turns)))))
        events (atom [])]
    (session/run {:lm {:provider :mock :format :mock
                       :responses (fn [_] (let [r (first @replies)] (swap! replies #(vec (rest %))) r))}
                  :config {:working-dir dir :project-dir dir :session-id id :persist-session? true
                           :max-iterations 4 :memory-hint? false :recap? false :codex-home (str dir "/no-codex")}
                  :read-input (fn [_] (let [[l] @queue] (swap! queue #(vec (rest %))) l))
                  :emit (fn [_ _])
                  :on-event (fn [e d] (swap! events conj [e d]))})
    @events))

(defn -main [& [turns k]]
  (let [turns (or (some-> turns parse-long) 5)
        k (or (some-> k parse-long) 3)
        dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-rewind-spike-" (System/nanoTime))) .mkdirs))
        id "s-spike"
        cfg {:working-dir dir :project-dir dir :session-id id}]
    (fx/sh {:dir dir :out :string :err :string} "git" "init" "-q")
    (let [events (run-session! dir id turns)
          snap (persist/read-session cfg id)
          after? #(>= % k)
          rows [["session.edn :previous-turns" (count (filter #(after? (:turn %)) (:previous-turns snap)))]
                ["session.edn :transcript entries" (let [tr (:transcript snap)
                                                          cut (:transcript (persist/cut-before snap k))]
                                                      (- (count tr) (count cut)))]
                ["session.edn :turn-count, :last-question" "both"]
                ;; no turn number in a record, deliberately: position in
                ;; the append-only file is its order (trajectory/record)
                ["trajectory records (append-only, by position)" (max 0 (- (count (trajectory/read-trajectories cfg id)) (dec k)))]
                ["events on the bus, :session.turn/end" (- (count (filter #(= :session.turn/end (first %)) events)) (dec k))]
                ["turn refs refs/byj/turns/<id>/≥K" (count (filter after? (changes/turns cfg id)))]]
          nid (context/session-id)
          [fork-r fork-ms] (ms #(persist/fork! cfg id nid {:before-turn k}))
          [rew-r rew-ms] (ms #(changes/rewind! cfg id k))]
      (println (str "a session of " turns " turns, taken back to turn " k " (" dir ")\n"))
      (println "what holds turn K and after, under the session's id — a truncation's to rewrite:")
      (doseq [[what n] rows] (println (format "  %-46s %s" what n)))
      (println "\na fork:")
      (println (format "  %-46s %s" "files written" (str "1 (" (.getName (io/file (str fork-r))) " of " nid ")")))
      (println (format "  %-46s %.1f ms" "persist/fork! cut before K" fork-ms))
      (println (format "  %-46s %.1f ms (%d files)" "changes/rewind! to K's start" rew-ms (count (:files rew-r))))
      (println (format "  %-46s %s" "the session forked from" (str "untouched: turn-count " (:turn-count (persist/read-session cfg id)))))
      (println (format "  %-46s %s" "the fork" (str "turn-count " (:turn-count (persist/read-session cfg nid))
                                                     ", its next turn " k ", refs under its own id"))))
    (shutdown-agents)))
