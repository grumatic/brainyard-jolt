(ns brainyard.json-test
  "JSON in and out, where the characters outside the BMP are (byj-gly.152)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]))

(deftest a-character-outside-the-bmp-survives-a-round-trip
  ;; jolt's strings are indexed by codepoint, so data.json escaped 😀 as the
  ;; five-digit \u1f600 — not JSON, and read back as ὠ followed by a 0
  (testing "it is written as itself, in UTF-8, and read back the same"
    (let [s "a 😀 é 한 \n tab\t"]
      (is (str/includes? (json/write-str {:q s}) "😀"))
      (is (not (re-find #"\\u[0-9a-f]{5}" (json/write-str {:q s}))) "never a five-digit escape")
      (is (= {:q s} (json/read-str (json/write-str {:q s}))))))
  (testing "control characters are still escaped, and a reader of plain JSON reads them"
    (is (= "{\"q\":\"a\\nb\\u0001\"}" (json/write-str {:q (str "a\nb" (char 1))})))))

(deftest a-surrogate-pair-from-a-provider-is-one-character
  ;; how standard JSON writes a character outside the BMP; a lone half threw
  ;; 'Value out of range for char' and failed the whole reply
  (is (= {:a "x 😀 y"} (json/read-str "{\"a\":\"x \\ud83d\\ude00 y\"}")))
  (is (= {:a "😀😀"} (json/read-str "{\"a\":\"\\uD83D\\uDE00\\ud83d\\ude00\"}")) "either case, back to back")
  (is (= {:a "\uFFFD z"} (json/read-str "{\"a\":\"\\ud83d z\"}")) "half a pair is the replacement character, not a failure")
  (testing "an escaped backslash before a u is text, not an escape"
    (is (= {:a "\\ud83d"} (json/read-str "{\"a\":\"\\\\ud83d\"}"))))
  (testing "and an ordinary escape is untouched"
    (is (= {:a "é"} (json/read-str "{\"a\":\"\\u00e9\"}")))))

(deftest a-model-reply-with-an-emoji-parses
  (is (= {:answer "done 👍"} (json/parse-json-response "```json\n{\"answer\":\"done \\ud83d\\udc4d\"}\n```"))))
