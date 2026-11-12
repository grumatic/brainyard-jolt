;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.effect-ratchet-test
  "Static ratchets over src/ for RFC-009's rules that a runtime test cannot
  catch until the one path that breaks them runs live.

  1. No park inside a lazy body. `m/?`, `?>` or `?<` inside `map`, `for`,
     `filter`, … — and `mapv`/`filterv`, which jolt realizes lazily under a
     counted lock — throws on jolt 0.8.8 (hung on 0.8.3). samizdat's first live
     run on ebb died on exactly this while its suite stayed green.
  2. No raw `future` / `pmap` / `future-call` outside `brainyard.effect`: they
     are not values, cannot be cancelled structurally, and cancel nothing they
     started. Known sites awaiting migration are allowlisted with their bead,
     and the allowlist must SHRINK — a listed site that no longer exists fails
     the test until it is removed from the list.

  Reading is syntactic: forms are read, not evaluated. A park inside a nested
  `sp`/`ap`/`cp`/`via` is not a park in the enclosing lazy body — that body only
  constructs a task — so those subtrees are skipped."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def lazy-heads
  '#{map mapv filter filterv remove keep keep-indexed map-indexed mapcat for
     lazy-seq lazy-cat iterate repeatedly take-while drop-while partition-by
     sort-by group-by})

(def park-names #{"?" "?>" "?<"})

(def task-constructors #{"sp" "ap" "cp" "via"})

(defn- sym-name [x] (when (symbol? x) (name x)))

(defn- park? [x]
  (and (symbol? x) (namespace x) (park-names (name x))))

(defn- constructs-task? [form]
  (and (seq? form) (symbol? (first form)) (namespace (first form))
       (task-constructors (name (first form)))))

(defn- contains-park?
  "Does `form` park when evaluated in place (not inside a task it constructs)?"
  [form]
  (cond
    (constructs-task? form) false
    (park? form) true
    (coll? form) (boolean (some contains-park? (if (map? form) (mapcat identity form) form)))
    :else false))

(defn lazy-parks
  "[head form] for every lazy form in `forms` whose arguments park."
  [forms]
  (let [found (atom [])]
    (letfn [(walk [form]
              (when (coll? form)
                (when (and (seq? form)
                           (contains? lazy-heads (some-> (first form) sym-name symbol))
                           (not (namespace (first form)))
                           (some contains-park? (rest form)))
                  (swap! found conj [(first form) form]))
                (doseq [x (if (map? form) (mapcat identity form) form)]
                  (walk x))))]
      (doseq [f forms] (walk f)))
    @found))

(defn raw-concurrency
  "Symbols among #{future pmap future-call} used in call position in `forms`."
  [forms]
  (let [found (atom #{})]
    (letfn [(walk [form]
              (when (coll? form)
                (when (and (seq? form)
                           (symbol? (first form))
                           (contains? '#{future pmap future-call clojure.core/future clojure.core/pmap}
                                      (first form)))
                  (swap! found conj (symbol (name (first form)))))
                (doseq [x (if (map? form) (mapcat identity form) form)]
                  (walk x))))]
      (doseq [f forms] (walk f)))
    @found))

(defn- read-forms [text]
  (binding [*read-eval* false]
    (read-string (str "[" text "\n]"))))

(defn- src-files []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(.getPath %))
       sort))

;; --- the detectors detect -------------------------------------------------------

(deftest detectors-are-not-vacuous
  (let [forms (read-forms "(defn a [xs] (mapv (fn [x] (m/? (m/sleep 1 x))) xs))
                           (defn b [xs] (for [x xs] (ebb/?> x)))
                           (defn ok1 [xs] (reduce (fn [acc x] (conj acc (m/? x))) [] xs))
                           (defn ok2 [xs] (mapv (fn [x] (m/sp (m/? (m/sleep 1 x)))) xs))
                           (defn ok3 [xs] (map inc xs))
                           (defn c [] (future 1) (pmap inc [1]))")]
    (is (= '[mapv for] (mapv first (lazy-parks forms))))
    (is (= '#{future pmap} (raw-concurrency forms)))))

;; --- the ratchets -----------------------------------------------------------------

(deftest no-park-inside-a-lazy-body
  (doseq [path (src-files)]
    (testing path
      (is (empty? (map first (lazy-parks (read-forms (slurp path)))))))))

(def raw-concurrency-allowlist
  "path → symbols still allowed there, each with the bead that removes it.
  Empty: the last site, tool dispatch's pmap, moved onto fx/bounded (byj-gly.27)."
  {})

(deftest no-raw-future-or-pmap-outside-the-brick
  (doseq [path (src-files)
          :when (not= path "src/brainyard/effect.clj")]
    (testing path
      (let [used (raw-concurrency (read-forms (slurp path)))
            allowed (get raw-concurrency-allowlist path #{})]
        (is (empty? (remove allowed used)) "use brainyard.effect (bounded, all, task-of)")
        (is (empty? (remove used allowed))
            "an allowlisted site is gone — shrink raw-concurrency-allowlist")))))

;; --- subprocesses release their pipes (byj-gly.259) --------------------------------------

(def ^:private raw-process-allowed
  "Files that start a process with `jolt.process` itself, with why. Everything
  else goes through `fx/start` (then `fx/release!`), `fx/sh`, `fx/process-task`
  or `fx/streaming-process`: jolt leaves a child's pipes open, and closes a
  string stdin late enough to close a reused descriptor (byj-gly.259)."
  {"src/brainyard/effect.clj" "the owner: start, release!, sh, process-task, streaming-process"})

(deftest no-subprocess-outside-the-brick
  (let [hits (for [f (file-seq (io/file "src/brainyard"))
                   :when (str/ends-with? (.getName f) ".clj")
                   :let [path (str "src/brainyard/" (subs (.getPath f) (count "src/brainyard/")))
                         text (slurp f)]
                   :when (re-find #"\(\s*(?:apply\s+)?p/(?:process|sh|shell)\b" text)]
               path)]
    (is (empty? (remove raw-process-allowed hits))
        "a subprocess run outside brainyard.effect leaks its pipes: use fx/sh or fx/process-task")
    (is (empty? (remove (set hits) (keys raw-process-allowed)))
        "the allowlist shrinks: a listed file that no longer runs a process comes off it")))
