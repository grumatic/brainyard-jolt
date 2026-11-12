;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.launch
  "What a run is assembled from, before any session starts: the environment
  with its `.env`, the provider and model it asks, the LM made from them, and
  the event bus every viewer shares. The CLI (`brainyard.main`) and the SDK
  (`brainyard.sdk`, RFC-053) both assemble a session's base from these, so a
  session opened by an application is built as one a person starts
  (byj-g5n.3). Mechanism only: nothing here decides what a turn does."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.json :as json]
            [brainyard.eventlog :as eventlog]
            [brainyard.llm :as llm]
            [brainyard.predictions :as predictions]))

;; --- the environment -------------------------------------------------------------

(defn parse-dotenv [text]
  (into {}
        (for [line (str/split-lines text)
              :let [line (str/trim line)
                    [_ k v] (re-matches #"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)" line)]
              :when (and k (not (str/starts-with? line "#")))]
          [k (-> v str/trim (str/replace #"^\"(.*)\"$" "$1") (str/replace #"^'(.*)'$" "$1"))])))

(defn find-dotenv
  "Nearest .env walking up from dir, then ~/.brainyard/.env (brainyard's order)."
  [dir]
  (or (loop [d (.getAbsoluteFile (io/file dir))]
        (when d
          (let [f (io/file d ".env")]
            (if (.isFile f) f (recur (.getParentFile d))))))
      (let [f (io/file (System/getenv "HOME") ".brainyard" ".env")]
        (when (.isFile f) f))))

(defn env-fn
  "Real environment first, then .env values."
  [dir]
  (let [dotenv (some-> (find-dotenv dir) slurp parse-dotenv)]
    (fn [k]
      (let [v (System/getenv k)]
        (if (str/blank? v) (get dotenv k) v)))))

;; --- the model -------------------------------------------------------------------

(def fallback-lm
  "The model a run asks when neither the command line nor `:default-lm` says:
  claude-code's sonnet, which needs no API key of its own (byj-gly.366;
  haiku before) — the same as `:default-lm`'s own default."
  "claude-code/sonnet")

(defn lm-choice
  "Which provider and model a run asks, as `[provider model]`: `provider`,
  with `model` or that provider's own default; else `:default-lm`
  (\"provider/model\"), `model` replacing its model when given; else
  `fallback-lm`. An API key in the environment no longer chooses the model by
  being there — it had made `openai/gpt-4.1-mini` every new session's model
  for anyone with an OpenAI key in a `.env`. A `:default-lm` that names no
  provider is told to `problem!` and the fallback used."
  [provider model config problem!]
  (if provider
    [provider model]
    (let [label (:default-lm config)
          parsed (or (llm/split-lm-label label)
                     (do (when-not (str/blank? (str label))
                           (problem! (str "config: :default-lm " (pr-str label)
                                          " is not provider/model — using " fallback-lm)))
                         (llm/split-lm-label fallback-lm)))
          [p m] parsed]
      [(keyword p) (or model m)])))

(defn mock-lm
  "An offline LM that answers every call, echoing the question and how much
  session history reached the prompt — enough to see the loops nest
  (`llm/mock-echo`, which a mock named by label answers with too).

  With `model` `script:FILE`, it answers with FILE's replies in turn — an EDN
  vector of reply maps (`{:reasoning :tool-calls :answer …}`, written as
  JSON) — and echoes once they run out: how a test of a process that is not
  this one (`byj acp` driven by an SDK, RFC-053) scripts tool calls and
  permission prompts without a model."
  ([] (mock-lm nil))
  ([model]
   (if-let [[_ path] (some->> model (re-matches #"script:(.+)"))]
     (let [left (atom (vec (edn/read-string (slurp path))))]
       {:provider :mock :format :mock :model model
        :responses (fn [msgs]
                     (let [[r] (first (swap-vals! left #(vec (rest %))))]
                       (if r (json/write-str r) (llm/mock-echo msgs))))})
     {:provider :mock :format :mock :model "mock" :responses llm/mock-echo})))

(defn make-lm
  "The LM for `provider` and `model`: the mock offline, else the provider's,
  with the cache keys and streaming from `config` (RFC-013), so config.edn
  and BY_* set them as well as the flags."
  [provider model config env]
  (if (= :mock provider)
    (mock-lm model)
    (llm/create-lm provider env
                   (into {:model model}
                         (remove (comp nil? val)
                                 (select-keys config [:prompt-cache? :cache-ttl :prompt-cache-key
                                                      ;; -v shows the reply as it arrives, so it
                                                      ;; implies streaming where a provider can
                                                      :stream?]))))))

;; --- the event bus ---------------------------------------------------------------

(defn event-bus
  "One stream, three consumers: `viewer` shows the turn as it happens, the event
  log writes it down so a later run can ask (RFC-026) and `replay.clj` can re-run
  a call, and the prediction notice says when a predictor's pool is big enough
  to compile from (RFC-021).

  The viewer is a parameter because there are several, and only one may run:
  the inline tracer, the full-screen renderer's handler, or an SDK client's
  stream. The full-screen session used to REPLACE this bus with the renderer's
  handler, and so wrote no event log at all (byj-gly.141).

  The prediction sink also PRODUCES one derived event (:programs/dataset-ready),
  so it needs the bus it is part of — hence the forward reference. It cannot
  loop: it reacts to :predictor/call alone and emits something else."
  [config viewer]
  (let [!bus (atom nil)
        fs (into (if viewer [viewer] [])
                 (remove nil? [(eventlog/sink config)
                               ;; AFTER eventlog/sink on purpose: it counts the
                               ;; pool out of the file the sink just wrote
                               (predictions/notice-sink
                                config
                                (fn [e d] (when-let [b @!bus] (b e d))))]))
        bus (fn [e d] (doseq [f fs] (f e d)))]
    (reset! !bus bus)
    bus))
