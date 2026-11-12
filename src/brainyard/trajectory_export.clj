;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.trajectory-export
  "Recorded turns as training data (RFC-025) — brainyard's
  `agent/common/trajectory_export.clj`.

  `trajectory.edn` is what the agent DID; this is that turned into examples of
  what it should do:

      <project-dir>/.brainyard/exports/trajectories-<ms>.{jsonl,edn}

  Two formats. `openai-jsonl` is one `{\"messages\": [...]}` per turn in the
  OpenAI tool-calling shape, ready to fine-tune on; `edn` is the records
  themselves, one per line, lossless — for anyone who would rather project them
  their own way.

  **Redaction is on by default** (`brainyard.secrets`), and it is the reason
  this file exists rather than someone writing a `jq` line: an export is the
  moment a project's turns stop being a local diagnostic and become a file that
  gets copied somewhere. `trajectory.edn` itself holds whatever the turn said,
  as brainyard's does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.json :as json]
            [brainyard.secrets :as secrets]
            [brainyard.trajectory :as trajectory]))

;; --- one turn as messages ---------------------------------------------------------------

(defn- call-id
  "`call_<turn>_<n>_<i>`. `turn` is the record's POSITION in the export, not a
  field of it — v3 has no `:turn` on purpose (RFC-025), and reusing one would
  collide across sessions."
  [turn n i]
  (str "call_" turn "_" n "_" i))

(defn iteration->messages
  "One trajectory iteration as OpenAI chat messages — brainyard's
  `iteration->messages`."
  [turn {:keys [n channel thought answer tools]}]
  (cond
    (= "answer" channel)
    ;; the thought is DROPPED here, deliberately: an example whose assistant
    ;; message is deliberation teaches a model to answer with its thinking
    [{:role "assistant" :content (str answer)}]

    (seq tools)
    (into [{:role "assistant"
            :content (when-not (str/blank? (str thought)) (str thought))
            :tool_calls (vec (map-indexed
                              (fn [i {:keys [name args]}]
                                {:id (call-id turn n i)
                                 :type "function"
                                 :function {:name (str name)
                                            :arguments (json/write-str (or args {}))}})
                              tools))}]
          (map-indexed (fn [i {:keys [result]}]
                         {:role "tool" :tool_call_id (call-id turn n i)
                          :content (str result)})
                       tools))

    (not (str/blank? (str thought)))
    [{:role "assistant" :content (str thought)}]

    :else []))

(defn record->example
  "A trajectory record as `{:messages [...]}` — brainyard's `record->example`.

  The answer is appended as its own assistant message ONLY when no
  answer-channel iteration carried it, which is the v2 shape: appending it for
  a v3 record would train on the answer twice."
  [turn record]
  (let [its (:iterations record)
        messages (into [{:role "user" :content (str (:question record))}]
                       (mapcat #(iteration->messages turn %) its))
        has-answer? (some #(= "answer" (:channel %)) its)
        answer (trajectory/record-answer record)]
    {:messages (cond-> messages
                 (and (not has-answer?) (not (str/blank? (str answer))))
                 (conj {:role "assistant" :content (str answer)}))}))

;; --- the file ----------------------------------------------------------------------------

(def formats #{"openai-jsonl" "edn"})

(defn- extension [fmt] (if (= "edn" fmt) "edn" "jsonl"))

(defn serialize-lines
  "The lines to write, redacted unless `redact?` is false.

  Redaction happens HERE, over both formats, rather than in either projection:
  the edn form is lossless by definition, so if it were exempt the one format
  that keeps everything would be the one that keeps the secrets too."
  [fmt records redact?]
  (let [examples (map-indexed (fn [i r] (if (= "edn" fmt) r (record->example i r))) records)
        examples (if redact? (map secrets/redact examples) examples)]
    {:lines (mapv (fn [x] (if (= "edn" fmt) (pr-str x) (json/write-str x))) examples)
     :redacted (boolean (and redact? (some secrets/redacted? examples)))}))

(defn export-file [config fmt]
  (io/file (or (:project-dir config) ".") ".brainyard" "exports"
           (str "trajectories-" (System/currentTimeMillis) "." (extension fmt))))

(defn learnable?
  "Is this record worth an example? It needs a question to answer and something
  that happened after it.

  Not merely \"has messages\": the user message is built from the question and
  is always there, so a turn that recorded nothing still produces a one-message
  example — an empty prompt with an empty completion, counted in the total."
  [record]
  (and (not (str/blank? (str (:question record))))
       (> (count (:messages (record->example 0 record))) 1)))

(defn turn-range-problem
  "Why `turns` is not a range of turns, or nil when it is one (or absent).
  A range is `[from to]`, both 1-based and inclusive, `from` <= `to`."
  [turns]
  (when (some? turns)
    (let [[from to] (when (sequential? turns) turns)]
      (when-not (and (sequential? turns) (= 2 (count turns))
                     (integer? from) (integer? to) (<= 1 from to))
        (str "turns is [from to], two whole numbers from 1 with from <= to, not " (pr-str turns))))))

(defn in-turns
  "The records of ONE session whose turn number is in `[from to]`, inclusive.
  A turn's number is its 1-based position among the session's turn records:
  v3 has no `:turn` (RFC-025), position is the ordering, and notes are not
  turns. `nil` turns keeps them all."
  [records turns]
  (if-let [[from to] turns]
    (into [] (keep-indexed (fn [i r] (when (<= from (inc i) to) r))) records)
    (vec records)))

(defn export!
  "Write the sessions' turns as `fmt`. Returns `{:path :format :sessions :turns
  :examples :redacted}` or `{:error …}`.

  `:turns [from to]` exports only those turns of each session — inclusive,
  1-based, counted within the session (`in-turns`); a recording of turns 12
  to 30 (RFC-043) reads the same numbers the session showed.

  A turn with nothing to learn from is dropped rather than exported empty;
  counting it would overstate the dataset."
  [config {:keys [sessions format redact path turns]}]
  (let [fmt (or (not-empty (str format)) "openai-jsonl")]
    (cond
      (not (formats fmt))
      {:error (str fmt " is not a format. They are: " (str/join ", " (sort formats)))}

      (turn-range-problem turns)
      {:error (turn-range-problem turns)}

      (empty? sessions)
      {:error "no session has a trajectory yet — nothing to export"}

      :else
      (let [records (vec (mapcat #(in-turns (trajectory/read-trajectories config %) turns) sessions))
            usable (if (= "edn" fmt) records (filterv learnable? records))]
        (if (empty? usable)
          {:error (if (and turns (empty? records))
                    (str "no turns " (first turns) "-" (second turns) " in those sessions")
                    "those sessions have no turns worth exporting")}
          (let [{:keys [lines redacted]} (serialize-lines fmt usable (not (false? redact)))
                f (if (not-empty (str path)) (io/file (str path)) (export-file config fmt))]
            (try
              (.mkdirs (.getParentFile f))
              (spit f (str (str/join "\n" lines) "\n"))
              {:path (.getPath f) :format fmt :sessions (vec sessions)
               :turns (count records) :examples (count lines) :redacted redacted}
              (catch Throwable e {:error (str "could not write " (.getPath f) ": " (ex-message e))}))))))))

;; --- the command ---------------------------------------------------------------------------

(defn commands
  "`trajectory$export`. Offered only when there is something to export."
  [config]
  (when (seq (trajectory/sessions config))
    [{:name "trajectory$export" :kind :command
      :description (str "Write this project's recorded turns out as a dataset — every turn's"
                        " question, tool calls and answer, in the OpenAI fine-tuning shape."
                        " Secrets are redacted unless you turn that off. Use it when someone"
                        " asks for training data or wants the agent's history in one file;"
                        " to ASK what happened, prefer log$search or memory$history.")
      :input-schema [:map [:session-id {:optional true} [:string {:desc "One session. Omit for all of them."}]]
                          [:format {:optional true} [:string {:desc "openai-jsonl (default) or edn (the raw records)."}]]
                          [:redact {:optional true} [:boolean {:desc "Default true. Only pass false for a file that stays on this machine."}]]
                          [:turns {:optional true} [:vector {:desc "[from to]: only these turns of each session, 1-based and inclusive. Omit for every turn."} :int]]]
      :output-schema [:or {:desc "the file written; :turns were read, :examples written"}
               [:map [:path :string] [:format [:enum "openai-jsonl" "edn"]] [:sessions [:vector :string]]
                     [:turns :int] [:examples :int] [:redacted :boolean]]
               [:map [:error :string]]]
      :effects #{:fs/write :trajectory/export}
      :fn (fn [{:keys [session-id format redact turns]} ctx]
            (let [c (:config ctx)
                  ss (if (str/blank? (str session-id))
                       (trajectory/sessions c)
                       [(str session-id)])]
              (export! c {:sessions ss :format format :redact redact :turns turns})))}]))
