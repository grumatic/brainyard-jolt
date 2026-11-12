;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.json
  "JSON in and out, plus the lenient reply parser ported from brainyard's
  `clj-llm.core.llm/parse-json-response`."
  (:require [jolt.time]                 ; java.time shim data.json needs at load
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn write-str
  "`x` as JSON, with text outside ASCII written as itself, in UTF-8.

  Not escaped (byj-gly.152): jolt's strings are indexed by codepoint, so a char
  can be above 0xFFFF, and data.json escapes a char as `\\u` and its hex —
  five digits for 😀, `\\u1f600`, which is not JSON and which a reader takes
  as ὠ and a literal 0. Every provider reads UTF-8, and it is shorter. Control
  characters and U+2028/2029 are still escaped, and those are four digits.
  (provenance P-20)"
  [x]
  (json/write-str x :escape-unicode false))

(def ^:private escape-or-surrogates
  ;; an escaped backslash first, so the `u` after `\\\\` is text, not an escape;
  ;; then a surrogate pair; then a surrogate left without its other half
  #"(\\\\)|\\u([dD][89abAB][0-9a-fA-F]{2})\\u([dD][c-fC-F][0-9a-fA-F]{2})|\\u([dD][89a-fA-F][0-9a-fA-F]{2})")

(defn- join-surrogates
  "`s` with every `\\uD83D\\uDE00` surrogate-pair escape replaced by the
  character it spells, and a surrogate escape with no partner by U+FFFD.

  A pair is how standard JSON writes a character outside the BMP, and a provider
  may write one; data.json on jolt reads each half as a char of its own, and a
  lone surrogate is out of range for one, so the reply failed to parse
  (byj-gly.152). The character itself is valid inside a JSON string."
  [s]
  (if-not (str/includes? s "\\u")
    s
    (str/replace s escape-or-surrogates
                 (fn [[m backslash hi lo lone]]
                   (cond
                     backslash m
                     hi (str (char (+ 0x10000
                                      (bit-shift-left (- (Integer/parseInt hi 16) 0xD800) 10)
                                      (- (Integer/parseInt lo 16) 0xDC00))))
                     lone "\uFFFD"
                     :else m)))))

(defn pretty-str
  "`x` as indented JSON, for a person to read — `write-str`'s escaping, laid
  out by data.json's own pretty-printer."
  [x]
  (str/trimr (with-out-str (json/pprint x :escape-unicode false))))

(defn read-str [s] (json/read-str (join-surrogates (str s)) :key-fn keyword))

(defn find-last-json-object
  "The last top-level balanced `{...}` in text, string- and escape-aware, or nil."
  [text]
  (let [n (count text)]
    (loop [i 0, start nil, depth 0, in-str? false, escape? false, last-obj nil]
      (if (>= i n)
        last-obj
        (let [c (.charAt text i)]
          (cond
            escape? (recur (inc i) start depth in-str? false last-obj)
            in-str? (cond
                      (= \\ c) (recur (inc i) start depth true true last-obj)
                      (= \" c) (recur (inc i) start depth false false last-obj)
                      :else (recur (inc i) start depth true false last-obj))
            (= \" c) (recur (inc i) start depth true false last-obj)
            (= \{ c) (recur (inc i) (or start i) (inc depth) false false last-obj)
            (= \} c) (let [d (dec depth)]
                       (if (and (zero? d) start)
                         (recur (inc i) nil 0 false false (subs text start (inc i)))
                         (recur (inc i) start d false false last-obj)))
            :else (recur (inc i) start depth false false last-obj)))))))

(defn parse-json-response
  "Parse a model reply that should be one JSON object. Strips markdown fences;
  falls back to the last balanced object when the model wrapped it in prose.
  Throws ex-info with :raw-text (and :no-json-envelope? for pure prose)."
  [text]
  (let [cleaned (-> (str text)
                    str/trim
                    (str/replace #"^```(?:json)?\s*\n?" "")
                    (str/replace #"\n?```\s*$" "")
                    str/trim)]
    (try
      (let [v (read-str cleaned)]
        (if (map? v) v (throw (ex-info "not an object" {}))))
      (catch Throwable e
        (if-let [extracted (find-last-json-object cleaned)]
          (try
            (read-str extracted)
            (catch Throwable _
              (throw (ex-info (str "JSON parse failed: " (ex-message e)
                                   "\nLLM response: " (subs cleaned 0 (min (count cleaned) 200)))
                              {:raw-text cleaned}))))
          (throw (ex-info "LLM response was plain text with no JSON object."
                          {:raw-text cleaned :no-json-envelope? true})))))))
