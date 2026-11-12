;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.replay
  "Reproduce a `claude -p` call this port actually made.

  Every such call is recorded in the event log as `:llm.cli/call` — the exact
  argv and the exact stdin, unclipped (RFC-026). Replaying one answers
  the question that costs the most to get wrong: *what did we send*, as opposed
  to what we believe we send. Guessing at the cause of a model's behaviour cost
  a wrong fix once — an Opus refusal was blamed on the CoT reasoning field when
  the cause was an abbreviated prompt (`docs/provenance.md` P-6).

      (require '[brainyard.dev.replay :as replay])
      (def call (first (replay/calls)))            ; newest first
      (replay/replay (:args call) (:prompt call))  ; → {:stop-reason … :structured? …}
      (replay/sections (replay/arg call \"--system-prompt\"))

  `calls` returns `[]` when the log is absent or holds no such call, because
  that is the ordinary case and not a failure.

  This used to read brainyard's own mulog log as well, and `ab` swapped flags
  and content between the two to separate *what we send* from *how we send it*.
  That went when the port stopped needing brainyard as an oracle for its own
  requests: it records them itself now. `git log` has the swap test if the
  comparison is ever wanted again.

  Every replay is a real model call on the claude CLI's subscription. Use it to
  confirm a fix, never to search prompt wordings for one that slips past a
  safety check."
  (:require [clojure.string :as str]
            [brainyard.eventlog :as eventlog]
            [brainyard.json :as json]
            [jolt.process :as p]))

(defn calls
  "Every `:llm.cli/call` in this project's event log, NEWEST FIRST, as
  `{:args :prompt :model :provider :ts :session :turn}`.

  Read with `:full?`, because `read-events` trims the conversation keys for the
  tools a turn can call and argv carries the whole system prompt — the thing
  being replayed. A replay is a human at a REPL, not a turn.

  `:prompt` is the record's `:stdin`: the log's name for the same bytes."
  ([] (calls {:project-dir "." :working-dir "."}))
  ([config]
   (->> (eventlog/read-events config {:event :llm.cli/call :full? true :limit 500})
        (mapv (fn [r]
                (let [d (:data r)]
                  {:args (vec (:args d)) :prompt (:stdin d)
                   :model (:model d) :provider (:provider d)
                   :ts (:ts r) :session (:session r) :turn (:turn r)}))))))

(defn arg
  "The value following `flag` in a call's argv, or nil."
  [call flag]
  (let [args (vec (:args call))
        i (.indexOf args flag)]
    (when (<= 0 i (- (count args) 2)) (nth args (inc i)))))

(defn- events [out]
  (let [all (try (json/read-str out) (catch Throwable _ nil))]
    (if (sequential? all)
      all
      (keep #(try (json/read-str %) (catch Throwable _ nil)) (str/split-lines (str out))))))

(defn replay
  "Run a claude argv with `prompt` on stdin, CLAUDECODE removed as the provider
  does — it is set when the host itself runs under Claude Code, and it changes
  how the nested CLI behaves. Returns the result event's verdict."
  [args prompt]
  (let [env (dissoc (into {} (System/getenv)) "CLAUDECODE")
        r @(apply p/process {:in prompt :out :string :err :string :env env} args)
        res (last (filter #(= "result" (:type %)) (events (:out r))))]
    {:exit (:exit r)
     :stop-reason (:stop_reason res)
     :terminal-reason (:terminal_reason res)
     :structured? (boolean (:structured_output res))
     :result (some-> (:result res) (subs 0 (min 300 (count (:result res)))))}))

(defn sections
  "The markdown headers of a system prompt, in order — the quickest way to see
  which zones one prompt has and another lacks."
  [text]
  (vec (keep #(re-matches #"^#{1,3} .*|^Your (?:input|output) fields are:|^In adhering.*|^=== .*" %)
             (str/split-lines (str text)))))
