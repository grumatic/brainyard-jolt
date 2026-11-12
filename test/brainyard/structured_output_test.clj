;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.structured-output-test
  "What an OpenAI-compatible provider is told about the shape of its reply
  (RFC-006, `byj-gly.24`)."
  (:require [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]
            [brainyard.signature :as sig]
            [jolt.http-client]))

;; --- strict-eligible? ---------------------------------------------------------------

(def closed
  "Every property required, nothing else allowed — what strict mode demands."
  {:type "object"
   :properties {"a" {:type "string"} "b" {:type "integer"}}
   :required ["a" "b"]
   :additionalProperties false})

(deftest a-schema-is-strict-eligible-only-when-nothing-about-it-is-optional
  (is (sig/strict-eligible? closed))

  (testing "a property left out of :required is what strict mode forbids"
    (is (not (sig/strict-eligible? (assoc closed :required ["a"])))))

  (testing "an open object is not eligible, whether it says so or stays silent"
    (is (not (sig/strict-eligible? (assoc closed :additionalProperties {}))))
    (is (not (sig/strict-eligible? (assoc closed :additionalProperties true))))
    (is (not (sig/strict-eligible? (dissoc closed :additionalProperties)))))

  (testing "names may be strings or keywords — predictor specs write strings"
    (is (sig/strict-eligible? {:type "object"
                               :properties {:a {:type "string"}}
                               :required [:a]
                               :additionalProperties false}))
    (is (sig/strict-eligible? {:type "object"
                               :properties {:a {:type "string"}}
                               :required ["a"]
                               :additionalProperties false})))

  (testing "it recurses: one open object anywhere disqualifies the whole schema"
    (let [open {:type "object" :properties {"x" {:type "string"}} :required ["x"]}]
      (is (not (sig/strict-eligible? (assoc-in closed [:properties "a"] open))))
      (is (not (sig/strict-eligible? {:type "array" :items open})))
      (is (not (sig/strict-eligible? {:anyOf [closed open]})))
      (is (not (sig/strict-eligible? (assoc closed :definitions {"d" open}))))
      (is (sig/strict-eligible? (assoc closed :definitions {"d" closed})))))

  (testing "a non-object schema has nothing to be strict about"
    (is (sig/strict-eligible? {:type "string"}))
    (is (sig/strict-eligible? {:type "array" :items {:type "string"}}))))

(deftest the-loops-own-schema-is-not-strict-eligible
  ;; This is WHY the flag is auto-detected rather than hard-coded true, and the
  ;; reason the port sent json_object for as long as it did. `tool-args` has to
  ;; be an open map — the arguments belong to whichever tool is being called,
  ;; so their shape is not knowable when the schema is built.
  (let [p (predictor/load-predictor "react/think-act" [])
        schema (sig/output-json-schema (sig/cot-outputs (:signature p)))]
    (is (= {} (:additionalProperties (get-in schema [:properties "tool-calls" :items
                                                     :properties "args"])))
        "the open map is here, three levels down — if it ever closes, the strict
         flag below flips on its own")
    (is (not (sig/strict-eligible? schema)))
    (is (false? (llm/json-schema-strict? {:provider :openai} schema)))))

;; --- the flag -----------------------------------------------------------------------

(deftest an-explicit-strict-setting-beats-the-auto-detection
  ;; Both directions, because a provider that is OpenAI-compatible in name only
  ;; may reject `strict` outright, and someone may know their schema is fine
  ;; when this cannot see it.
  (is (true? (llm/json-schema-strict? {:json-schema-strict? true}
                                      (assoc closed :required ["a"]))))
  (is (false? (llm/json-schema-strict? {:json-schema-strict? false} closed)))
  (is (true? (llm/json-schema-strict? {} closed))
      "with nothing said, the schema decides"))

;; --- the body -----------------------------------------------------------------------

(defn- body [lm schema]
  (llm/openai-body (merge {:provider :openai :model "m"} lm) [] schema))

(deftest the-schema-reaches-the-provider-as-response-format
  (testing "a provider that takes one gets the schema, named as brainyard names it"
    (is (= {:type "json_schema"
            :json_schema {:name "response" :strict true :schema closed}}
           (:response_format (body {:supports-json-schema? true} closed)))))

  (testing "a schema with an optional property still goes, with strict off"
    (let [loose (assoc closed :required ["a"])]
      (is (= {:type "json_schema"
              :json_schema {:name "response" :strict false :schema loose}}
             (:response_format (body {:supports-json-schema? true} loose))))))

  (testing "a provider that does not take one is not sent it"
    ;; ollama and freellm are OpenAI-compatible in shape only; an unknown
    ;; parameter is a 400 there, which would be a worse failure than none.
    (is (nil? (:response_format (body {:supports-json-schema? false} closed))))
    (is (nil? (:response_format (body {} closed)))))

  (testing "and with no schema there is nothing to enforce"
    (is (nil? (:response_format (body {:supports-json-schema? true} nil))))
    (is (nil? (:response_format (llm/openai-body {:provider :openai :model "m"} []))))))

(deftest the-schema-travels-from-the-predictor-to-the-body
  ;; The two halves above meet here. `chat` takes the schema in opts and the
  ;; body builder takes it as an argument, so the connection between them is
  ;; one destructuring in `chat* :openai` — which nothing else would notice
  ;; going missing, because the request would still be well-formed without it.
  (let [sent (atom nil)
        schema {:type "object" :properties {"answer" {:type "string"}}
                :required ["answer"] :additionalProperties false}]
    (with-redefs [jolt.http-client/post
                  (fn [_url {:keys [body]}]
                    (reset! sent (json/read-str body))
                    {:status 200
                     :body (json/write-str {:choices [{:message {:content "{}"}}]
                                            :usage {:prompt_tokens 1 :completion_tokens 2}})})]
      (llm/chat {:provider :openai :format :openai :model "m" :max-retries 0
                 :base-url "https://example.invalid/v1" :api-key "k"
                 :supports-json-schema? true}
                [{:role "user" :content "q"}]
                {:json-schema schema}))
    (is (= "json_schema" (get-in @sent [:response_format :type])))
    ;; read back through JSON, since that is what the provider sees — the
    ;; reader keywordizes property names on the way in, and the point of the
    ;; assertion is that the schema travelled, not how a map prints
    (is (= (json/read-str (json/write-str schema))
           (get-in @sent [:response_format :json_schema :schema]))
        "the schema the predictor derived, not a rebuilt one")
    (is (true? (get-in @sent [:response_format :json_schema :strict])))))

(deftest the-providers-that-take-a-schema-are-brainyards
  (let [takes? #(boolean (:supports-json-schema? (get llm/providers %)))]
    (is (takes? :openai))
    (is (takes? :openrouter))
    (is (not (takes? :ollama)) "a local server, not OpenAI")
    (is (not (takes? :freellm)) "an arbitrary backend: conservative by default")))

(deftest the-body-is-otherwise-unchanged
  (is (= {:model "m" :messages [] :temperature 0.0 :max_tokens 99}
         (llm/openai-body {:provider :openai :model "m" :temperature 0.0 :max-tokens 99} []))))
