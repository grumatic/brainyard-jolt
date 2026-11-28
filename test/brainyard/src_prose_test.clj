;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.src-prose-test
  "The kernel keeps no prose (byj-2e8.7, RFC-001): every word the model reads
  is a resource, so a sentence of more than a dozen words written in src is
  either one a person reads — the command line, the screen, a config key's or
  an event's documentation — or a debt with a bead. The source is read as
  data, docstrings aside, the string arguments of one `str` call read as one
  string; every definition holding such a sentence is in
  `test/fixtures/src-prose.edn` with how many and why, and a new one fails
  here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private max-words
  "A sentence longer than this is prose; a label, a heading or a short error
  is not."
  12)

(defn- forms [^java.io.File f]
  (let [r (java.io.PushbackReader. (io/reader f))]
    (loop [out []]
      (let [x (read {:eof ::eof :read-cond :allow} r)]
        (if (= ::eof x) out (recur (conj out x)))))))

(defn- docstring-at
  "The index of a defining form's docstring, if it can have one: `(ns n
  \"doc\")`, `(defn n \"doc\" …)`, and `(def n \"doc\" v)` but not `(def n
  \"value\")`."
  [form]
  (let [h (first form)]
    (when (symbol? h)
      (cond
        (= 'ns h) 2
        (#{'def 'defonce} h) (when (< 3 (count form)) 2)
        (str/starts-with? (name h) "def") 2))))

(defn- strings
  "Every string literal in `form` but docstrings; consecutive string arguments
  of a `str` call are one, as they are one sentence."
  [form]
  (cond
    (string? form) [form]
    (seq? form) (let [h (first form)
                      doc (docstring-at form)
                      xs (keep-indexed (fn [i x] (when-not (= i doc) x)) form)]
                  (cond
                    (= 'defprotocol h)
                    (mapcat (fn [x] (strings (if (and (seq? x) (string? (last x))) (butlast x) x))) xs)

                    (= 'str h)
                    (mapcat (fn [run] (if (string? (first run)) [(apply str run)] (mapcat strings run)))
                            (partition-by string? (rest xs)))

                    :else (mapcat strings xs)))
    (map? form) (mapcat strings (mapcat identity form))
    (coll? form) (mapcat strings form)
    :else nil))

(defn- longest-sentence
  "The word count of `s`'s longest sentence: split at a stop, a colon or a
  semicolon before a space, at a blank line, a list item or a heading."
  [s]
  (apply max 0 (for [sentence (str/split s #"(?<=[.!?:;])\s+|\n\s*\n|\n\s*[-*] |\n#+ ")]
                 (count (filter #(re-find #"[A-Za-z]" %) (str/split (str/trim sentence) #"\s+"))))))

(defn prose
  "`{[file definition] count}`: how many strings with a sentence of more than
  `max-words` words each top-level form of src/brainyard holds."
  []
  (frequencies
   (for [^java.io.File f (sort-by str (file-seq (io/file "src/brainyard")))
         :when (str/ends-with? (.getName f) ".clj")
         form (forms f)
         s (strings form)
         :when (< max-words (longest-sentence s))]
     [(.getName f) (str (when (and (seq? form) (symbol? (second form))) (second form)))])))

(def ^:private allowed-file "test/fixtures/src-prose.edn")

(defn- allowed [] (edn/read-string (slurp allowed-file)))

(deftest src-keeps-no-prose-the-model-reads
  (let [found (prose)
        {:keys [why entries]} (allowed)
        listed (into {} (map (fn [[file def n reason]] [[file def] [n reason]])) entries)]
    (is (< 50 (reduce + (vals found))) "the source was read and its strings found")
    (testing "every entry gives a reason the file explains"
      (is (empty? (remove (comp why second val) listed))))
    (testing "a definition holding prose is listed, and with how many strings"
      (is (empty? (for [[k n] found :when (not= n (first (listed k)))] [k n (first (listed k))]))
          (str "[file definition found listed]: a sentence of more than " max-words " words in src. "
               "Words the model reads go in resources/brainyard/prompts (or tools/descriptions); "
               "words a person reads are listed in " allowed-file " with why")))
    (testing "an entry names a definition that still holds prose"
      (is (empty? (remove found (keys listed)))
          (str "no longer holds prose: take it off " allowed-file)))))

(deftest a-sentence-split-over-a-str-call-is-one-sentence
  (is (= ["one two three four five six seven eight nine ten eleven twelve thirteen"]
         (strings '(str "one two three four five six seven " "eight nine ten eleven twelve thirteen")))))

(deftest a-docstring-is-not-prose-and-a-def-value-is
  (is (= [] (vec (strings '(defn f "a docstring of many many words, more than twelve of them, which a person reads" [] 1)))))
  (is (= ["a value"] (vec (strings '(def x "a value")))))
  (is (= ["a value"] (vec (strings '(def x "doc" "a value"))))))
