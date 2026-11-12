;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.trajectory-export-test
  "Recorded turns as training data (RFC-025), and the redactor that decides what
  leaves the machine with them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [brainyard.secrets :as secrets]
            [brainyard.tools :as tools]
            [brainyard.trajectory :as tr]
            [brainyard.trajectory-export :as ex]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

(defn- with-turns
  "A project whose session `s1` recorded `records`."
  [n records]
  (let [d (fresh-dir n)
        c {:working-dir (.getPath d) :project-dir (.getPath d) :session-id "s1"}]
    (doseq [r records] (tr/append! (tr/log-file c) r))
    c))

(def ^:private a-turn
  {:v 3 :session "s1" :question "what is in deps.edn"
   :success true :terminated-by :answer-channel :total-iterations 2
   :iterations [{:n 1 :channel "tool" :thought "I should look"
                 :tools [{:name "read-file" :args {:path "deps.edn"} :result "{:deps {}}"}]}
                {:n 2 :channel "answer" :answer "a deps file"}]})

;; --- the projection -----------------------------------------------------------------------

(deftest a-turn-becomes-the-shape-a-fine-tune-eats
  (let [{:keys [messages]} (ex/record->example 0 a-turn)]
    (is (= ["user" "assistant" "tool" "assistant"] (mapv :role messages)))

    (testing "the question is the user message"
      (is (= "what is in deps.edn" (:content (first messages)))))

    (testing "a tool iteration is an assistant tool_call plus its tool reply"
      (let [[call reply] [(nth messages 1) (nth messages 2)]]
        (is (= "I should look" (:content call)) "the thought is the assistant's content here")
        (is (= "read-file" (get-in call [:tool_calls 0 :function :name])))
        (is (= {:path "deps.edn"} (json/read-str (get-in call [:tool_calls 0 :function :arguments])))
            "arguments are a JSON STRING, as the format wants")
        (is (= (get-in call [:tool_calls 0 :id]) (:tool_call_id reply)) "the reply names its call")
        (is (= "{:deps {}}" (:content reply)))))

    (testing "the answer is the assistant's, WITHOUT the deliberation"
      ;; an example whose assistant message is thinking teaches a model to
      ;; answer with its thinking
      (is (= {:role "assistant" :content "a deps file"} (last messages))))))

(deftest an-answer-is-not-learned-twice
  (testing "a v3 record already carries its answer in a terminal iteration"
    (is (= 1 (count (filter #(= "a deps file" (:content %))
                            (:messages (ex/record->example 0 a-turn)))))))

  (testing "a v2 record kept it at the top, so it is appended"
    (let [v2 {:v 2 :question "q" :answer "from v2"
              :iterations [{:n 1 :channel "tool" :tools [{:name "t" :result "r"}]}]}
          ms (:messages (ex/record->example 0 v2))]
      (is (= "from v2" (:content (last ms))))
      (is (= "assistant" (:role (last ms)))))))

(deftest call-ids-are-unique-across-turns
  ;; they are built from the record's POSITION, because v3 has no :turn — two
  ;; records reusing one id would collide in the same file
  (let [ids (fn [i] (->> (:messages (ex/record->example i a-turn))
                         (keep #(get-in % [:tool_calls 0 :id]))))]
    (is (not= (ids 0) (ids 1)))
    (is (= (ids 0) (ids 0)) "…and stable for one position"))

  (testing "two iterations in one turn do not collide either"
    (let [two {:question "q" :iterations [{:n 1 :channel "tool" :tools [{:name "a"}]}
                                          {:n 2 :channel "tool" :tools [{:name "b"}]}]}
          ids (->> (:messages (ex/record->example 0 two)) (keep #(get-in % [:tool_calls 0 :id])))]
      (is (= 2 (count (set ids)))))))

;; --- what leaves the machine ---------------------------------------------------------------

(deftest secrets-are-redacted-on-the-way-out
  (let [leaky (assoc a-turn :question "deploy with sk-abcdefghij0123456789 please")
        c (with-turns "export-redact" [leaky])
        r (ex/export! c {:sessions ["s1"]})
        text (slurp (:path r))]
    (is (true? (:redacted r)) "and it says it redacted something")
    (is (str/includes? text "[REDACTED]"))
    (is (not (str/includes? text "sk-abcdefghij0123456789")))
    (is (str/includes? text "deploy with") "only the secret goes")

    (testing "the raw log still holds what the turn said — this is the boundary,
              not the recording"
      (is (str/includes? (slurp (tr/log-file c)) "sk-abcdefghij0123456789")))

    (testing "and it can be turned off for a file that stays here"
      (let [r2 (ex/export! c {:sessions ["s1"] :redact false})]
        (is (false? (:redacted r2)))
        (is (str/includes? (slurp (:path r2)) "sk-abcdefghij0123456789"))))))

(deftest the-lossless-format-is-not-the-leaky-one
  ;; if edn were exempt from redaction, the one format that keeps everything
  ;; would be the one that keeps the secrets
  (let [c (with-turns "export-edn-redact"
            [(assoc a-turn :question "token: ghp_abcdefghijklmnopqrstuvwxyz01")])
        r (ex/export! c {:sessions ["s1"] :format "edn"})]
    (is (str/includes? (slurp (:path r)) "[REDACTED]"))
    (is (not (str/includes? (slurp (:path r)) "ghp_abcdefghijklmnopqrstuvwxyz01")))))

;; --- the file -------------------------------------------------------------------------------

(deftest an-export-is-one-line-per-turn
  (let [c (with-turns "export-lines" [a-turn (assoc a-turn :question "second")])
        r (ex/export! c {:sessions ["s1"]})
        lines (str/split-lines (slurp (:path r)))]
    (is (= 2 (:turns r)))
    (is (= 2 (:examples r)))
    (is (= 2 (count lines)))
    (is (every? #(seq (:messages (json/read-str %))) lines))
    (is (str/ends-with? (:path r) ".jsonl"))

    (testing "edn writes the records themselves, one per line"
      (let [r2 (ex/export! c {:sessions ["s1"] :format "edn"})]
        (is (str/ends-with? (:path r2) ".edn"))
        (is (= [3 3] (mapv #(:v (read-string %)) (str/split-lines (slurp (:path r2))))))))))

(deftest a-turn-with-nothing-in-it-is-not-an-example
  (let [empty-turn {:v 3 :question "" :iterations []}
        silent-turn {:v 3 :question "asked but nothing happened" :iterations []}
        c (with-turns "export-empty" [a-turn empty-turn silent-turn])
        r (ex/export! c {:sessions ["s1"]})]
    (is (= 3 (:turns r)) "all three were recorded")
    (is (= 1 (:examples r)) "one is worth learning from")
    (testing "a question with nothing after it is the case that looks fine and is not"
      (is (false? (ex/learnable? silent-turn)))
      (is (false? (ex/learnable? empty-turn)))
      (is (true? (ex/learnable? a-turn))))

    (testing "the lossless format keeps them, being a copy of the record"
      (is (= 3 (:examples (ex/export! c {:sessions ["s1"] :format "edn"})))))))

(deftest an-export-that-cannot-be-written-is-reported
  (let [c (with-turns "export-unwritable" [a-turn])
        blocker (io/file (:project-dir c) ".brainyard" "exports")]
    (spit blocker "not a directory")
    (is (str/includes? (:error (ex/export! c {:sessions ["s1"]})) "could not write"))))

(deftest a-format-nobody-has-says-which-exist
  (let [c (with-turns "export-badfmt" [a-turn])]
    (is (str/includes? (:error (ex/export! c {:sessions ["s1"] :format "parquet"}))
                       "openai-jsonl"))))

(deftest nothing-recorded-is-said-plainly
  (let [d (fresh-dir "export-none")
        c {:working-dir (.getPath d) :project-dir (.getPath d)}]
    (is (str/includes? (:error (ex/export! c {:sessions []})) "nothing to export"))))

;; --- through the registry ---------------------------------------------------------------------

(deftest the-command-appears-only-when-there-is-something-to-export
  (let [d (fresh-dir "export-cmd-absent")
        ctx (agent/make-ctx {:config {:working-dir (.getPath d) :project-dir (.getPath d)}
                             :tools (vec tools/builtin-tools)})]
    (is (nil? (get @(:tools ctx) "trajectory$export"))))

  (let [c (with-turns "export-cmd" [a-turn])
        ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})
        cmd (get @(:tools ctx) "trajectory$export")]
    (is (some? cmd))
    (is (= #{:fs/write :trajectory/export} (:effects cmd))
        "so --ask-effects trajectory/export puts a person in front of it")
    (let [r (tools/call-tool cmd {} ctx)]
      (is (= 1 (:examples r)))
      (is (= ["s1"] (:sessions r)) "every session with a trajectory, when none is named"))))

;; --- the shared redactor (byj-gly.77) ------------------------------------------------------------

(deftest refusing-and-redacting-do-not-use-the-same-list
  ;; brainyard carries two pattern lists and says why nowhere. The reason is
  ;; that being wrong costs opposite amounts: a false positive when refusing
  ;; blocks work, a false positive when redacting costs one [REDACTED].
  (testing "a shaped token is both"
    (is (seq (secrets/tokens-in "sk-abcdefghij0123456789")))
    (is (= "[REDACTED]" (secrets/redact-str "sk-abcdefghij0123456789"))))

  (testing "an Anthropic or OpenAI project key is a shaped token too, hyphens and all (byj-gly.146)"
    (doseq [k ["sk-ant-api03-Ab_cD-efGHijklMNopqrSTuvwxYZ0123456789-abcdef"
               "sk-proj-Ab_cD-efGHijklMNopqrSTuvwxYZ0123456789"]]
      (is (seq (secrets/tokens-in (str "key: " k))) k)
      (is (= "key: [REDACTED]" (secrets/redact-str (str "key: " k))) k)))

  (testing "a heuristic match is redacted but is NOT grounds for a refusal"
    (let [prose "password: correcthorsebatterystaple"]
      (is (empty? (secrets/tokens-in prose)) "a note about a password is not a token")
      (is (str/includes? (secrets/redact-str prose) "[REDACTED]"))))

  (testing "ordinary text is neither"
    (is (empty? (secrets/tokens-in "a perfectly ordinary value")))
    (is (= "a perfectly ordinary value" (secrets/redact-str "a perfectly ordinary value"))))

  (testing "redaction walks every string leaf, whatever key it sits under"
    (is (= {:a [{:b "[REDACTED]"}]}
           (secrets/redact {:a [{:b "AKIAIOSFODNN7EXAMPLE"}]})))
    (is (= 7 (:n (secrets/redact {:n 7}))) "a non-string is untouched")))

;; --- a range of turns (RFC-043 recordings, byj-1wv.5.1) ---------------------------------------

(deftest only-the-turns-asked-for-are-exported
  (let [turn (fn [q] (assoc a-turn :question q))
        c (with-turns "export-range" [(turn "one") (turn "two") (turn "three") (turn "four")])
        _ (tr/append! (tr/log-file c) (tr/note-record {:session "s1" :text "a note" :from {:user "u1" :role :coach}}))
        questions (fn [r] (mapv #(:question (read-string %)) (str/split-lines (slurp (:path r)))))]
    (testing "inclusive, 1-based, counted among the session's turns (a note is not one)"
      (let [r (ex/export! c {:sessions ["s1"] :format "edn" :turns [2 3]})]
        (is (= ["two" "three"] (questions r)))
        (is (= 2 (:turns r)))))

    (testing "one turn, and a range that runs past the end keeps what there is"
      (is (= ["four"] (questions (ex/export! c {:sessions ["s1"] :format "edn" :turns [4 4]}))))
      (is (= ["three" "four"] (questions (ex/export! c {:sessions ["s1"] :format "edn" :turns [3 99]})))))

    (testing "a range with no turns in it says so"
      (is (str/includes? (:error (ex/export! c {:sessions ["s1"] :turns [7 9]})) "no turns 7-9")))

    (testing "a range that is not one is refused, naming the shape"
      (doseq [bad [[3 2] [0 2] [1] "1-2" [1.5 2]]]
        (is (str/includes? (str (:error (ex/export! c {:sessions ["s1"] :turns bad}))) "[from to]")
            (pr-str bad))))

    (testing "through the command"
      (let [ctx (agent/make-ctx {:config c :tools (vec tools/builtin-tools)})
            r (tools/call-tool (get @(:tools ctx) "trajectory$export") {:turns [1 2] :format "edn"} ctx)]
        (is (= ["one" "two"] (questions r)))))))
