;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.compaction-tier-spike
  "Can compaction run on the light model? (byj-gly.378, RFC-005 \"The light
  tier\".) `session/compact` and `react/compact` on haiku and on sonnet, over
  inputs rebuilt from real recorded work, scored by what LATER work needed —
  not by reading the summary.

      jolt -A:dev -m brainyard.dev.compaction-tier-spike [N] [PROJECT-DIR…]

  N samples of each predictor (default 6), from the saved sessions under each
  project's .brainyard/sessions (default: this one and ~/Projects/byj-sim/*).

  - session/compact: a saved session with six or more earlier turns; the turns
    before the last three are compressed, as `/compact` does
    (`history/format-previous-turns`), and the last three are what came later.
  - react/compact: a recorded turn with seven or more iterations; the records
    before the last three (`:keep-verbatim-iterations`) are compressed, as tier
    2 does (`context/model-view`), and the last three and the answer are what
    came later.

  What later work needed is the exact identifiers — paths, file names, issue
  ids, hashes, numbers of three digits or more — that occur in the compressed
  part AND again in what came later: the things a summary that lost them would
  have made the agent look up again. A summary's score is the share of those
  it kept, verbatim. Every row is two real model calls on the claude CLI's
  subscription. Results: target/compaction-tier/results.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [brainyard.context :as context]
            [brainyard.history :as history]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]
            [brainyard.trajectory :as trajectory]))

(def ^:private id-re
  #"(?:[\w.-]+/)+[\w.-]+|\b[\w-]+\.(?:clj|cljs|edn|md|py|js|ts|tsx|json|sh|go|txt|toml|yml|yaml|html|css)\b|\b[a-z]+-[a-z0-9]+(?:\.\d+)+\b|\b[0-9a-f]{7,40}\b|\b\d{3,}\b")

(defn identifiers [s] (set (re-seq id-re (str s))))

(defn- read-edn [f] (try (edn/read-string {:default (fn [_ v] v)} (slurp f)) (catch Throwable _ nil)))

(defn- session-dirs [projects]
  (for [p projects
        :let [root (io/file p ".brainyard" "sessions")]
        :when (.isDirectory root)
        d (.listFiles root)
        :when (.isDirectory d)]
    [p d]))

(defn session-samples
  "Saved sessions with six or more earlier turns: `{:id :history :later}`."
  [projects]
  (for [[_ d] (session-dirs projects)
        :let [s (read-edn (io/file d "session.edn"))
              turns (vec (or (:previous-turns s) (get-in s [:st-memory :previous-turns])))]
        :when (>= (count turns) 6)
        :let [older (subvec turns 0 (- (count turns) 3))
              later (subvec turns (- (count turns) 3))]]
    {:id (.getName d)
     :history (history/format-previous-turns older {:full-depth (count older)})
     :later (pr-str (mapv #(select-keys % [:question :answer :tool-trail]) later))}))

(defn- record-of [it]
  (cond-> {:iteration (:n it) :channel (:channel it) :thought (:thought it)}
    (seq (:tools it)) (assoc :tool-results (mapv (fn [t] {:tool-name (:name t) :tool-args (:args t) :tool-result (:result t)})
                                                 (:tools it)))))

(def min-iterations
  "A turn long enough that tier 2 would have something to compress: four
  records or more before the verbatim three. Ten left one turn in the
  recorded work; seven leaves enough to measure."
  7)

(defn react-samples
  "Recorded turns with `min-iterations` or more iterations, one per session:
  `{:id :question :records :later}`."
  [projects]
  (for [[p d] (session-dirs projects)
        :let [turns (trajectory/read-trajectories {:project-dir p} (.getName d))
              t (last (sort-by #(count (:iterations %)) (filter #(>= (count (:iterations %)) min-iterations) turns)))]
        :when t
        :let [recs (mapv record-of (:iterations t))
              n (- (count recs) 3)]]
    {:id (str (.getName d) "#" (:turn t))
     :question (str (:question t))
     :records (pr-str (context/model-view (subvec recs 0 n)))
     :later (str (pr-str (context/model-view (subvec recs n))) "\n" (:answer t))}))

(defn- run-on [p inputs model]
  (let [lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
        t0 (System/currentTimeMillis)
        r (try (predictor/run p inputs {:lm lm}) (catch Throwable e {:error (ex-message e)}))]
    {:model model :ms (- (System/currentTimeMillis) t0)
     :cost (get-in r [:usage :cost]) :error (:error r)
     ;; the text the loop would keep: the prose and, since byj-gly.383, the
     ;; identifiers kept apart from it
     :summary (str (context/summary-text (:outputs r)))}))

(defn- score [needed summary compressed]
  (let [kept (set/intersection needed (identifiers summary))]
    {:kept (count kept) :lost (vec (sort (set/difference needed kept)))
     :recall (if (seq needed) (double (/ (count kept) (count needed))) 1.0)
     :words (count (str/split (str/trim summary) #"\s+"))
     ;; tier 2 keeps a summary only when it is smaller than the span
     ;; (context/summarize-span): a bigger one is thrown away
     :smaller? (< (context/estimate-tokens (pr-str (context/summary-record summary)))
                  (context/estimate-tokens compressed))}))

(defn- measure [p-id samples input-of compressed-of n]
  (let [p (predictor/load-predictor p-id [])]
    ;; one of each input — simulated runs repeat — and the ones with the most
    ;; at stake first
    (vec (for [s (->> samples
                      (map #(assoc % ::needed (set/intersection (identifiers (compressed-of %)) (identifiers (:later %)))))
                      (filter #(seq (::needed %)))
                      (reduce (fn [acc x] (if (some #(= (compressed-of %) (compressed-of x)) acc) acc (conj acc x))) [])
                      (sort-by #(- (count (::needed %))))
                      (take n))
               :let [needed (::needed s)]]
           {:predictor p-id :id (:id s) :needed (count needed)
            :runs (mapv (fn [m] (let [r (run-on p (input-of s) m)] (merge r (score needed (:summary r) (compressed-of s)))))
                        ["haiku" "sonnet"])}))))

(defn -main [& args]
  (let [[n projects] (if (some->> (first args) (re-matches #"\d+")) [(parse-long (first args)) (rest args)] [6 args])
        projects (if (seq projects)
                   projects
                   (cons "." (map str (filter #(.isDirectory (io/file % ".brainyard" "sessions"))
                                              (.listFiles (io/file (System/getProperty "user.home") "Projects" "byj-sim"))))))
        only (some-> (System/getenv "PREDICTORS") (str/split #",") set)
        want? #(or (nil? only) (contains? only %))
        rows (into (if (want? "session/compact")
                     (measure "session/compact" (session-samples projects) #(select-keys % [:history]) :history n)
                     [])
                   (when (want? "react/compact")
                     (measure "react/compact" (react-samples projects) #(select-keys % [:question :records]) :records n)))]
    (io/make-parents "target/compaction-tier/results.edn")
    (spit "target/compaction-tier/results.edn" (with-out-str (pp/pprint rows)))
    (doseq [{:keys [predictor id needed runs]} rows]
      (println (str "\n=== " predictor " · " id " · " needed " identifiers needed later"))
      (doseq [{:keys [model recall kept lost words error smaller?]} runs]
        (println (format "  %-6s kept %2d/%-2d (%.0f%%)  %3d words%s%s%s" model kept needed (* 100 recall) words
                         (if smaller? "" "  NOT SMALLER — the loop would drop it")
                         (if (seq lost) (str "  lost " (str/join " " (take 6 lost))) "")
                         (if error (str "  ERROR " error) "")))))
    (println "\n=== mean recall and cost per call")
    (doseq [[p rs] (group-by :predictor rows)
            m ["haiku" "sonnet"]
            :let [xs (keep #(first (filter (fn [r] (= m (:model r))) (:runs %))) rs)]]
      (println (format "%-16s %-6s recall %.0f%%  smaller than the span %d/%d" p m
                       (* 100 (/ (reduce + (map :recall xs)) (max 1 (count xs))))
                       (count (filter :smaller? xs)) (count xs))))
    (shutdown-agents)
    (System/exit 0)))
