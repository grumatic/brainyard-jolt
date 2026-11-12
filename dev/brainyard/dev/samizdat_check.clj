;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.samizdat-check
  "Text this tree shares with samizdat, which is GPL-3.0-or-later and must not
  reach byj's MIT source (byj-ahf.1). A run is a stretch of `run-words` or
  more whitespace-separated words found in both; a run is accepted when every
  one of its windows lies inside a run reviewed and listed in
  dev/samizdat-check.edn — a Clojure idiom, a table head, a dependency
  coordinate, an error text quoted — and anything else fails.

  Over the whole checkout, an accepted run that no longer occurs is named, to
  be taken off, but fails nothing: removing a file that held an idiom is no
  reason to stop a change that has nothing to do with samizdat.
  dev/samizdat-check.sh fetches samizdat at the commits it compares against
  and calls `-main`:

      jolt -A:dev -m brainyard.dev.samizdat-check ACCEPTED ROOT FILES TREE...

  FILES lists the checkout's files to read, one per line (`git ls-files`), or
  is `-` for every file under ROOT: a tree such as an export, which holds part
  of the checkout, so an accepted run missing from it is not stale."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def run-words
  "How many words in a row make a run. Eight finds a copied sentence and a
  copied form; below it, ordinary phrases match by chance."
  8)

(def ^:private kinds
  #{"clj" "cljc" "edn" "md" "sh" "yml" "yaml" "json" "txt" "go" "ts" "tsx" "py" "mjs" "js"})

(def ^:private max-bytes 600000)

(def ^:private not-walked #{".git" "target" "node_modules" "dist"})

(def ^:private bd-block ["<!-- BEGIN BEADS INTEGRATION" "END BEADS INTEGRATION -->"])

(defn- without-bd-block
  "`text` less bd's managed block, which bd writes the same into every
  repository that runs `bd setup`."
  [text]
  (let [[open close] bd-block
        from (str/index-of text open)
        to (when from (str/index-of text close from))]
    (if to
      (recur (str (subs text 0 from) (subs text (+ to (count close)))))
      text)))

(defn words [text] (vec (re-seq #"\S+" (without-bd-block text))))

(defn- window [ws i] (str/join " " (subvec ws i (+ i run-words))))

(defn windows
  "Every run of `run-words` words in `ws`, in order."
  [ws]
  (map #(window ws %) (range (max 0 (inc (- (count ws) run-words))))))

(defn- readable? [^java.io.File f]
  (and (.isFile f)
       (<= (.length f) max-bytes)
       (contains? kinds (some-> (re-find #"\.([^./]+)$" (.getName f)) second))))

(defn- walk
  "Paths under `root`, relative to it, of the files worth reading."
  [root]
  (let [base (.getPath (io/file root))]
    (for [^java.io.File f (file-seq (io/file root))
          :when (readable? f)
          :let [rel (subs (.getPath f) (inc (count base)))]
          :when (not-any? not-walked (str/split rel #"/"))]
      rel)))

(defn index
  "Each window of samizdat's text, to the first path it was seen in."
  [trees]
  (reduce (fn [idx [root rel]]
            (reduce (fn [idx w] (if (contains? idx w) idx (assoc idx w rel)))
                    idx (windows (words (slurp (io/file root rel))))))
          {}
          (for [root trees, rel (walk root)] [root rel])))

(defn runs
  "The runs of `ws` that `idx` also holds: [{:text :words :windows :seen-in}],
  where :windows are the run's windows and :seen-in a samizdat path one of
  them came from."
  [ws idx]
  (let [hits (keep-indexed (fn [i w] (when (contains? idx w) i)) (windows ws))
        groups (reduce (fn [gs i]
                         (if (and (seq gs) (= (dec i) (peek (peek gs))))
                           (conj (pop gs) (conj (peek gs) i))
                           (conj gs [i])))
                       [] hits)]
    (for [g groups
          :let [from (first g), to (+ (peek g) run-words)
                wins (mapv #(window ws %) g)]]
      {:text (str/join " " (subvec ws from to))
       :words (- to from)
       :windows wins
       :seen-in (idx (first wins))})))

(defn check
  "What `files` (paths under `root`) share with samizdat's `trees` beyond the
  `accepted` runs: {:new [{:path …run}] :stale [accepted text…] :files n}.
  Paths under one of `skip` are not read."
  [{:keys [accepted skip]} root files trees]
  (let [idx (index trees)
        ok (into #{} (mapcat (comp windows words :text)) accepted)
        files (remove (fn [p] (some #(str/starts-with? p %) skip)) files)
        found (for [p files
                    :let [f (io/file root p)]
                    :when (readable? f)
                    r (runs (words (slurp f)) idx)]
                (assoc r :path p))
        seen (into #{} (mapcat :windows) found)]
    {:files (count files)
     :new (vec (remove #(every? ok (:windows %)) found))
     :stale (vec (for [{:keys [text]} accepted
                       :when (not-any? seen (windows (words text)))]
                   text))}))

(defn- clip [s n]
  (cond-> (subs s 0 (min n (count s)))
    (< n (count s)) (str "…")))

(defn -main [accepted-file root files-arg & trees]
  (let [accepted (edn/read-string (slurp accepted-file))
        part? (= "-" files-arg)
        files (if part?
                (walk root)
                (remove str/blank? (str/split-lines (slurp files-arg))))
        {:keys [new stale] :as r} (cond-> (check accepted root files trees)
                                    part? (assoc :stale []))]
    (doseq [{:keys [path words text seen-in]} new]
      (println (str path ": " words " words also in samizdat's " seen-in))
      (println (str "    " (clip text 300))))
    (doseq [text stale]
      (println (str "warning: accepted, and no longer found (take it off): " (clip text 120))))
    (println (str "samizdat-check: " (:files r) " files, " (count new) " new run(s), "
                  (count stale) " stale entr" (if (= 1 (count stale)) "y" "ies")
                  " in " accepted-file))
    (System/exit (if (empty? new) 0 1))))
