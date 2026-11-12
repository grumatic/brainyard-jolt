;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.predictions
  "Predictor calls, read back out of the event log (RFC-021).

  This used to be a log of its own — one `pr-str` map per line under
  `<project>/.brainyard/sessions/<id>/predictions.edn`, written by a sink of its
  own, clipped at 4000 characters, and off by default. All three of those are
  gone. `events.log` is lossless and holds every `:predictor/call` whole
  (RFC-026), so a second file recording the same thing less completely had
  nothing left to offer: the first real dataset built from the old one lost 74
  of 156 records to its clipping.

  What remains here is the reading, and one thing the reading is for: saying
  when a pool has grown enough to be worth compiling."
  (:require [brainyard.eventlog :as eventlog]
            [brainyard.events :as events]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def clip-marker "…[truncated ")

(defn clipped?
  "Does any string in `x` carry the truncation marker?

  The event log does not clip, so this fires only on records written by the old
  prediction log or copied from a trajectory. It is kept because those records
  are still on disk and an example whose input was truncated asks a different
  question from the one its label answers."
  [x]
  (let [hit (atom false)]
    (walk/postwalk (fn [v] (when (and (string? v) (str/includes? v clip-marker)) (reset! hit true)) v) x)
    @hit))

(defn- ->prediction
  "A `:predictor/call` log record flattened into the shape `records->examples`
  reads: the payload, plus the session and timestamp from the envelope."
  [r]
  (merge (:data r) {:session (:session r) :ts (:ts r)}))

(defn records
  "Every `:predictor/call` for `predictor-id`, oldest first.

  Read with `:full?`: the trimmed view a turn gets drops `:inputs`, `:outputs`
  and `:reasoning`, which are the only fields a dataset is made of. This is the
  dataset path, not a tool a turn can call."
  ([config] (records config nil))
  ([config predictor-id]
   (->> (eventlog/read-events config {:event :predictor/call :full? true :limit 100000})
        reverse
        (map ->prediction)
        (filter #(or (nil? predictor-id) (= predictor-id (:predictor-id %))))
        vec)))

(defn sessions
  "Session ids that produced a predictor call, newest first."
  [config]
  (->> (eventlog/read-events config {:event :predictor/call :limit 100000})
       (keep :session)
       distinct
       vec))

(defn usable?
  "Would this record survive into a dataset? The rules are
  `programs/records->examples`': not errored, and it produced outputs.
  Duplicates are a property of the set and are handled by `pool-size`.

  It used to reject a record carrying the truncation marker too, and stopped
  when `:skip-clipped` stopped defaulting to true — the marker now means the
  AGENT trimmed its iteration history for the context budget, which is a record
  of what the model actually saw rather than a corruption of it.

  **These two must not drift** — `programs-test/the-pool-count-is-what-the-
  dataset-yields` pins them to each other, and caught this."
  [r]
  (and (not (:error r))
       (seq (:outputs r))))

(defn pool-size
  "How many USABLE records exist for `predictor-id` — what `build-dataset` would
  actually yield, not how many calls were made.

  The distinction is not academic. On the first real pool built for this repo,
  156 records became 80 examples, 74 of them dropped as clipped; a notice
  counting raw calls fired at 150 while the val split was ~16, and brainyard
  measured its null at 12."
  [config predictor-id]
  (->> (records config predictor-id)
       (filter usable?)
       (map :inputs)
       distinct
       count))

(defn notice-sink
  "An `:on-event` handler that says ONCE per predictor per session when its pool
  has grown past `:prediction-notice-at`.

  It lived in the prediction log's own sink until that log was removed. It is
  its own consumer of the bus now, and also emits onto it: that cannot loop,
  because it reacts to `:predictor/call` alone and emits something else.

  **The log is read once per predictor, not once per call.** The first call
  counts the pool — which includes that call, because the event log's sink runs
  before this one in the fan-out — and later calls add one. Reading a 50 MiB
  file on every predictor call would be a real cost for a line of advice.

  A log that fills up and never mentions it is a log nobody remembers they have;
  an opportunity repeated every turn is an interruption."
  [config emit]
  (when emit
    (let [threshold (:prediction-notice-at config 150)
          counts (atom {})
          told (atom #{})]
      (fn [event data]
        (when (= :predictor/call event)
          (try
            (when-let [id (:predictor-id data)]
              (when-not (contains? @told id)
                (let [n (get (if (contains? @counts id)
                               (swap! counts update id inc)
                               (swap! counts assoc id (pool-size config id)))
                             id)]
                  (when (>= n threshold)
                    (swap! told conj id)
                    (events/emit! emit :programs/dataset-ready
                                  {:predictor-id id :records n :threshold threshold})))))
            (catch Throwable _ nil)))))))
