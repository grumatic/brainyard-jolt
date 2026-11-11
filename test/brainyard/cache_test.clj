(ns brainyard.cache-test
  "Prompt caching (RFC-015): where the breakpoints land, what they cost, and
  the byte-stability the whole arrangement depends on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [brainyard.cost :as cost]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]
            [brainyard.signature :as sig]))

(defn- scratch
  "A project dir of this test's own — a turn under the git root leaves a
  trajectory in the developer's repository (`byj-gly.88`)."
  []
  (let [d (clojure.java.io/file "target" "cache-scratch")] (.mkdirs d) (.getPath d)))

(defn- part [label n] [label (apply str (repeat n (subs label 0 1)))])

(def ^:private parts
  [(part "preamble" 6000) (part "agent-core" 1700)
   (part "session-context" 15000) (part "user-context" 800)])

(defn- system-of [lm ps]
  (:system (llm/anthropic-body lm [{:role "system" :content (str/join "\n\n" (map second ps)) :parts ps}
                                   {:role "user" :content "q"}])))

(deftest breakpoints-land-on-stable-zone-boundaries
  (let [blocks (system-of {:model "m"} parts)]
    (is (= 4 (count blocks)) "one block per part, in order")
    (is (= (map second parts) (map :text blocks)))
    (testing "every stable boundary is marked; the volatile tail is not"
      (is (= [{:type "ephemeral"} {:type "ephemeral"} {:type "ephemeral"} nil]
             (map :cache_control blocks))))
    (testing "a prefix below the provider's minimum would waste a slot, so it is skipped"
      (is (= [nil nil] (map :cache_control (system-of {:model "m"} [(part "preamble" 100) (part "agent-core" 200)]))))
      (is (< (count (filter :cache_control (system-of {:model "m"} [(part "preamble" 100)
                                                                   (part "agent-core" 5000)
                                                                   (part "session-context" 9000)])))
             3)))
    (testing "at most one mark per part, and never at the top level"
      (is (nil? (:cache_control (llm/anthropic-body {:model "m"} [{:role "system" :content "s" :parts parts}])))))))

(deftest ttl-marks-all-but-the-last-boundary
  (testing "Anthropic wants the longer TTLs first; the last mark is the one that churns"
    (is (= [{:type "ephemeral" :ttl "1h"} {:type "ephemeral" :ttl "1h"} {:type "ephemeral"} nil]
           (map :cache_control (system-of {:model "m" :cache-ttl "1h"} parts)))))
  (testing "and the beta header goes with it"
    (is (= "extended-cache-ttl-2025-04-11" (get (llm/anthropic-headers {:api-key "k" :cache-ttl "1h"}) "anthropic-beta")))
    (is (nil? (get (llm/anthropic-headers {:api-key "k"}) "anthropic-beta")))))

(deftest caching-off-or-unavailable-sends-what-it-always-sent
  (testing "no parts carried: a plain string system, as before"
    (is (string? (:system (llm/anthropic-body {:model "m"} [{:role "system" :content "plain"}])))))
  (testing ":prompt-cache? false: a plain string system"
    (is (string? (:system (llm/anthropic-body {:model "m" :prompt-cache? false}
                                              [{:role "system" :content "plain" :parts parts}])))))
  (testing "the body is otherwise unchanged"
    (let [body (llm/anthropic-body {:model "m" :max-tokens 99 :temperature 0.0}
                                   [{:role "system" :content "s"} {:role "user" :content "u"}])]
      (is (= {:model "m" :max_tokens 99 :system "s" :temperature 0.0
              :messages [{:role "user" :content "u"}]}
             body)))))

(deftest cache-tokens-are-accounted
  (testing "the three input categories do not overlap, so input is their sum"
    (is (= {:input-tokens 15118 :output-tokens 7 :cache {:read-tokens 13524 :write-tokens 1584}}
           (llm/anthropic-usage {:input_tokens 10 :cache_read_input_tokens 13524
                                 :cache_creation_input_tokens 1584 :output_tokens 7}))))
  (testing "a call with no cache activity reports none"
    (is (= {:input-tokens 10 :output-tokens 7}
           (llm/anthropic-usage {:input_tokens 10 :output_tokens 7})))))

(deftest openai-takes-its-own-parameters
  (let [body (fn [lm] (llm/openai-body (merge {:provider :openai :model "m"} lm) []))]
    (testing "OpenAI caches by itself; these only steer routing and retention"
      (is (= "k" (:prompt_cache_key (body {:prompt-cache-key "k"}))))
      (is (= "24h" (:prompt_cache_retention (body {:cache-ttl "1h"}))))
      (is (nil? (:prompt_cache_retention (body {:cache-ttl "5m"}))) "the default needs no parameter"))
    (testing "and they are gated: a strict OpenAI-compatible server may reject an unknown key"
      (let [other (llm/openai-body {:provider :openrouter :model "m" :prompt-cache-key "k" :cache-ttl "1h"} [])]
        (is (nil? (:prompt_cache_key other)))
        (is (nil? (:prompt_cache_retention other)))))
    (testing "the rest of the body is unchanged"
      ;; response_format is structured-output-test's subject, not caching's
      (is (= {:model "m" :messages [] :temperature 0.0 :max_tokens 99}
             (llm/openai-body {:provider :openai :model "m" :temperature 0.0 :max-tokens 99} []))))))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest the-cached-prefix-is-byte-stable-across-iterations
  (let [seen (atom [])
        replies (atom [(reply {:tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]})
                       (reply {:tool-calls [{:tool-name "list-dir" :tool-args {:path "src"}}]})
                       (reply {:answer "done"})])
        _ (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (fn [msgs] (swap! seen conj (:parts (first msgs)))
                                            (let [[x] (swap-vals! replies #(vec (rest %)))] (first x)))}
                          ;; its own project: a turn under the git root leaves a
                          ;; trajectory in the developer's repo (`byj-gly.88`)
                          :config {:working-dir "." :project-dir (scratch)}})
        stable (fn [parts] (vec (take 3 parts)))]
    (is (= 3 (count @seen)))
    (testing "the zones the breakpoints cover are identical call after call"
      (is (apply = (map stable @seen)))
      (is (= ["preamble" "agent-core" "session-context"] (map first (stable (first @seen))))))
    (testing "and they are ordered most-stable first, so each boundary is a sensible breakpoint"
      (is (= ["preamble" "agent-core" "session-context" "user-context"]
             (map first (first @seen)))))))

;; --- bedrock ---------------------------------------------------------------------

(deftest bedrock-routes-by-model-family
  (testing "an Anthropic model takes the native body, whatever profile carries it"
    (is (every? llm/bedrock-anthropic? ["anthropic.claude-opus-5"
                                        "us.anthropic.claude-haiku-4-5-20251001-v1:0"
                                        "global.anthropic.claude-haiku-4-5-20251001-v1:0"]))
    (is (not (llm/bedrock-anthropic? "openai.gpt-oss-120b-1:0")))
    (is (not (llm/bedrock-anthropic? "amazon.nova-lite-v1:0"))))
  (testing "a Nova model takes its own body too; OpenAI's on Bedrock go through Converse"
    (is (every? llm/bedrock-nova? ["amazon.nova-micro-v1:0" "us.amazon.nova-lite-v1:0" "amazon.nova-2-lite-v1:0"]))
    (is (every? llm/bedrock-native? ["us.anthropic.claude-haiku-4-5-20251001-v1:0" "amazon.nova-pro-v1:0"]))
    (is (not (llm/bedrock-native? "openai.gpt-oss-120b-1:0"))))
  (let [body (llm/bedrock-body {:model "us.anthropic.claude-haiku-4-5-20251001-v1:0" :max-tokens 16}
                               [{:role "system" :content "j" :parts parts} {:role "user" :content "q"}])]
    (testing "the native body is the Anthropic one, with Bedrock's version and no model key"
      (is (= "bedrock-2023-05-31" (:anthropic_version body)))
      (is (nil? (:model body)))
      (is (= [{:type "ephemeral"} {:type "ephemeral"} {:type "ephemeral"} nil]
             (map :cache_control (:system body)))
          "so the cache marks survive — this is the shape that carries them"))))

(deftest bedrock-nova-carries-cache-points
  ;; byj-gly.37: through invoke-model, a cachePoint on the block it closes —
  ;; measured, a second call read 11095 of 11099 input tokens from cache
  (let [msgs [{:role "system" :content "j" :parts parts} {:role "user" :content "q"}]
        body (llm/bedrock-body {:model "amazon.nova-micro-v1:0" :max-tokens 16 :cache-ttl "1h"} msgs)]
    (is (= "messages-v1" (:schemaVersion body)))
    (is (nil? (:modelId body)) "the model is invoke-model's --model-id, not the body's")
    (is (= [{:type "default"} {:type "default"} {:type "default"} nil] (map :cachePoint (:system body)))
        "the same stable-zone boundaries as Anthropic's marks, never on the volatile tail")
    (is (not-any? #(contains? (:cachePoint %) :ttl) (:system body)) "no ttl: Nova refuses one, whatever :cache-ttl says")
    (is (= (map second parts) (map :text (:system body))))
    (is (= [{:role "user" :content [{:text "q"}]}] (:messages body)))
    (is (= {:maxTokens 16} (:inferenceConfig body)))
    (testing "without parts, or with :prompt-cache? false, one plain block"
      (is (= [{:text "j"}] (:system (llm/bedrock-body {:model "amazon.nova-micro-v1:0" :prompt-cache? false} msgs))))
      (is (= [{:text "s"}] (:system (llm/bedrock-body {:model "amazon.nova-micro-v1:0"} [{:role "system" :content "s"}])))))))

(deftest bedrock-converse-for-everything-else
  (let [body (llm/bedrock-body {:model "openai.gpt-oss-120b-1:0" :max-tokens 16 :temperature 0.0}
                               [{:role "system" :content "sys" :parts parts} {:role "user" :content "q"}])]
    (is (= "openai.gpt-oss-120b-1:0" (:modelId body)))
    (is (= [{:text "sys"}] (:system body)) "no cache markers: Converse has no explicit caching for OpenAI's models")
    (is (= [{:role "user" :content [{:text "q"}]}] (:messages body)))
    (is (= {:maxTokens 16 :temperature 0.0} (:inferenceConfig body)))))

(deftest bedrock-invocation-and-usage
  (testing "invoke-model passes the body through unvalidated; converse is the uniform path"
    (is (= ["aws" "bedrock-runtime" "invoke-model" "--model-id" "us.anthropic.x"
            "--body" "fileb:///b.json" "--cli-binary-format" "raw-in-base64-out" "/o.json"
            "--region" "us-east-1"]
           (llm/bedrock-args {:model "us.anthropic.x" :region "us-east-1"} true "/b.json" "/o.json")))
    (is (= "invoke-model" (nth (llm/bedrock-args {:model "amazon.nova-micro-v1:0"} (llm/bedrock-native? "amazon.nova-micro-v1:0") "/b.json" "/o.json") 2))
        "Nova as well")
    (is (= ["aws" "bedrock-runtime" "converse" "--cli-input-json" "file:///b.json" "--output" "json"
            "--region" "eu-west-1" "--profile" "work"]
           (llm/bedrock-args {:model "openai.x" :region "eu-west-1" :profile "work"} false "/b.json" "/o.json"))))
  (testing "converse reports its own usage key names"
    (is (= {:input-tokens 82 :output-tokens 16} (llm/bedrock-converse-usage {:inputTokens 82 :outputTokens 16})))
    (is (= {:input-tokens 6549 :output-tokens 2 :cache {:read-tokens 6540 :write-tokens 0}}
           (llm/bedrock-converse-usage {:inputTokens 9 :outputTokens 2 :cacheReadInputTokens 6540}))
        "inputTokens leaves out what was read or written: the input is the three summed")
    (is (= {:input-tokens 11099 :output-tokens 8 :cache {:read-tokens 11095 :write-tokens 0}}
           (llm/bedrock-converse-usage {:inputTokens 4 :outputTokens 8 :cacheReadInputTokenCount 11095 :cacheWriteInputTokenCount 0}))
        "Nova's own key names, as measured")
    (is (= {:input-tokens 82 :output-tokens 16} (llm/bedrock-converse-usage {:inputTokens 82 :outputTokens 16 :totalTokens 98}))
        "a total that is the parts adds nothing")
    ;; byj-gly.372: aws-cli 2.25.1 dropped cacheReadInputTokens 9111 from
    ;; GPT-5.6 Sol's reply; totalTokens still counted them
    (let [u (llm/bedrock-converse-usage {:inputTokens 2 :outputTokens 5 :totalTokens 9118})]
      (is (= {:input-tokens 9113 :output-tokens 5 :unreported-input-tokens 9111} u)
          "what the total counts beyond the named parts is input the CLI did not name")
      (is (< 0.045 (:total-cost (cost/of (cost/table {}) u :bedrock "global.openai.gpt-5.6-sol")) 0.046)
          "priced at the input rate, an upper bound"))))

(deftest a-model-that-refuses-temperature-is-sent-none
  ;; byj-gly.369: Claude Opus 4.7+ and every 5.x, and GPT-5.6, answer any
  ;; temperature with a validation error, so every call failed
  (let [msgs [{:role "system" :content "s"} {:role "user" :content "hi"}]
        temp (fn [lm] (let [b (case (:as lm)
                                :openai (llm/openai-body lm msgs)
                                :anthropic (llm/anthropic-body lm msgs)
                                (llm/bedrock-body lm msgs))]
                        (or (:temperature b) (get-in b [:inferenceConfig :temperature]))))]
    (doseq [[model as] [["global.anthropic.claude-haiku-5-5" :bedrock] ["global.anthropic.claude-opus-4-7" :bedrock]
                        ["us.anthropic.claude-sonnet-5" :bedrock] ["global.openai.gpt-5.6-sol" :bedrock]
                        ["claude-opus-5-5" :anthropic] ["gpt-5.6-sol" :openai]]]
      (is (nil? (temp {:model model :temperature 0.0 :as as})) model)
      (is (llm/samples-freely? {:model model :temperature 0.0}) (str model " samples freely")))
    (doseq [[model as] [["global.anthropic.claude-haiku-4-5-20251001-v1:0" :bedrock] ["global.anthropic.claude-opus-4-6-v1" :bedrock]
                        ["openai.gpt-oss-120b-1:0" :bedrock] ["us.amazon.nova-2-lite-v1:0" :bedrock]
                        ["claude-sonnet-4-6" :anthropic] ["gpt-4.1-mini" :openai]]]
      (is (= 0.0 (temp {:model model :temperature 0.0 :as as})) model))))

(deftest a-bedrock-error-says-what-it-means
  ;; byj-gly.370: Claude Fable on Bedrock fails on an account setting only the
  ;; account's owner can change, and the error said nothing of that
  (let [err "An error occurred (ValidationException) when calling the InvokeModel operation: data retention mode 'default' is not available for this model"]
    (is (re-find #"aws_review" (llm/bedrock-hint err)))
    (is (re-find #"data-retention\.html" (llm/bedrock-hint err))))
  (is (nil? (llm/bedrock-hint "An error occurred (ThrottlingException): Too many requests")) "an error with no hint says only itself"))

(deftest a-reasoning-only-converse-reply-is-retried-not-misread
  ;; byj-gly.371: gpt-oss ended a turn with only a reasoningContent block, and
  ;; the agent reported "plain text with no JSON object"
  (let [only-reasoning {:output {:message {:content [{:reasoningContent {:reasoningText {:text "We need to call list-dir."}}}]}}
                        :usage {:inputTokens 7000 :outputTokens 72 :totalTokens 7072}}
        e (try (llm/bedrock-converse-reply only-reasoning) nil (catch Exception e e))]
    (is (= "bedrock reply had reasoning but no text" (ex-message e)))
    (is (:retryable? (ex-data e)) "another sample may answer"))
  (is (= "{\"a\":1}" (:content (llm/bedrock-converse-reply
                                {:output {:message {:content [{:reasoningContent {:reasoningText {:text "r"}}} {:text "{\"a\":1}"}]}}
                                 :usage {:inputTokens 1 :outputTokens 1}})))
      "the text block, after the reasoning"))
