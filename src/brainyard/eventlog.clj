;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.eventlog
  "The event log (RFC-026) — brainyard's mulog file publisher and its
  `log$*` commands, in the small.

  Every event the kernel puts on the bus (RFC-026), written down so a later run
  can be asked what happened in an earlier one:

      <project-dir>/.brainyard/logs/events.log

  One `pr-str` map per line, the same discipline as the prediction and
  trajectory logs — brainyard writes pretty-printed EDN paragraphs split on
  blank lines, which cannot be read a line at a time and cannot survive a torn
  tail (divergence `event-log-is-line-oriented`).

  It sits OUTSIDE `sessions/` on purpose. Session directories are pruned
  (`:prune-sessions?`, RFC-016), and a log whose whole job is to outlive the run
  that wrote it must not be swept away with the session it describes. Rotation
  is what bounds it instead.

  **Not everything on the bus is written.** An event declaring `:log? false` in
  the catalogue is live-only: `:agent.llm/chunk` is one event per streamed
  fragment, and `:agent.llm/request` / `:agent.llm/response` carry whole prompts
  and replies. Those belong to the prediction log, which is off until a project
  turns it on (RFC-021), and putting them here would quietly make this log the
  thing that one is deliberately not."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [brainyard.events :as events]))

(def filename "events.log")
(defn log-file
  "The event log for this project, or nil when it is off.

  Off with `:event-log? false`, and off when the run was told to leave nothing
  behind: `:persist-session?` gates this for the reason it gates the trajectory
  (RFC-025) — a payload carries the question, the tool arguments and the
  results, so this is a record of the conversation whatever else it is."
  [{:keys [event-log? persist-session? project-dir]}]
  (when (and (not (false? event-log?))
             (not (false? persist-session?))
             project-dir)
    (io/file project-dir ".brainyard" "logs" filename)))

;; --- rotation --------------------------------------------------------------------

(defn- backup [f n]
  ;; the file's OWN name rather than the `filename` def, so rotation does not
  ;; depend on there being exactly one log
  (io/file (.getParentFile f) (str (.getName f) "." n)))

(defn rotate!
  "Move `f` aside and shuffle the backups down, keeping `keep` of them.

  Size-based, checked before a write rather than on a timer: nothing here runs
  in the background, and a log that is only bounded while the agent is idle is
  not bounded."
  [f keep]
  (try
    (let [oldest (backup f keep)]
      (when (.isFile oldest) (.delete oldest))
      (doseq [n (range (dec keep) 0 -1)]
        (let [src (backup f n)]
          (when (.isFile src) (.renameTo src (backup f (inc n))))))
      (.renameTo f (backup f 1)))
    (catch Throwable _ nil)))

(defn- rotate-if-needed! [f limit keep]
  (when (and (pos? keep) (.isFile f) (>= (.length f) limit))
    (rotate! f keep)))

;; --- the record -------------------------------------------------------------------

(defn record
  "One event as a log line. `context` is what the sink knows about the run —
  its session and which turn is in flight.

  **Lossless.** Nothing is clipped and no payload is trimmed to a subset of its
  keys. This log is the one source of truth for what happened, and a dataset is
  derived from it (RFC-021), so a record that dropped `:inputs` or cut a prompt
  at 2000 characters would be a record nothing could be rebuilt from. Both of
  those it used to do.

  brainyard attaches the same context through mulog's global context, updated
  per turn; here the sink learns the turn from the stream itself, because
  `:session.turn/start` already carries it and a second channel for the same
  fact is a second thing to get wrong."
  [context event data]
  (cond-> {:ts (System/currentTimeMillis)
           :event event
           :level (or (events/level event) :trace)
           :data data}
    (:session context) (assoc :session (str (:session context)))
    (:turn context) (assoc :turn (:turn context))
    (:agent context) (assoc :agent (str (:agent context)))))

(defn logged?
  "Is `event` written down? Everything is, except a redundant REPRESENTATION of
  something already recorded whole.

  `:agent.llm/chunk` is the only one: a streamed fragment, one event per piece,
  of a reply that `:agent.llm/response` records entire. Excluding it loses no
  information and keeps the file from being mostly the same reply twice.
  Anything the catalogue does not mention is written — the point of a log is to
  hold what nobody thought to ask for."
  [event]
  (not (false? (get-in events/catalogue [event :log?] true))))

(defn- appender
  "The shared half of both sinks: make the directory, rotate when the file has
  grown past `limit`, append one `pr-str` line. Never throws, for the reason
  none of the three logs do — losing a line must not cost the turn that
  produced it."
  [f limit keep]
  (fn [line]
    (try
      (when-let [parent (.getParentFile f)]
        (when-not (.exists parent) (.mkdirs parent)))
      (rotate-if-needed! f limit keep)
      (spit f (str line "\n") :append true)
      (catch Throwable _ nil))))

(defn- with-turn
  "Both sinks learn the turn from the stream itself: `:session.turn/start`
  already carries it and a second channel for the same fact is a second thing
  to get wrong."
  [config write]
  (let [turn (atom nil)
        agent-id (:agent-instance-id config)]
    (fn [event data]
      (when (= :session.turn/start event)
        (reset! turn (:turn data)))
      (write {:session (:session-id config) :turn @turn :agent agent-id}
             event data))))

(defn sink
  "An `:on-event` handler appending to the project log, or nil when it is off."
  [config]
  (when-let [f (log-file config)]
    (let [write (appender f
                          (or (:event-log-max-bytes config) (* 50 1024 1024))
                          (or (:event-log-keep config) 3))]
      (with-turn config
        (fn [context event data]
          (when (logged? event)
            (write (pr-str (record context event data)))))))))

;; --- reading it back ----------------------------------------------------------------

(defn- files-newest-first [f keep]
  (->> (cons f (map #(backup f %) (range 1 (inc (or keep 3)))))
       (filter #(.isFile %))))

(defn log-files
  "The project's event log and its rotations, newest first — those that exist.
  What a reader making its own pass over the whole log reads (the eval
  adapters, byj-gly.288.1), rather than `read-events`' parse of every line."
  [{:keys [project-dir event-log-keep]}]
  (files-newest-first (io/file (or project-dir ".") ".brainyard" "logs" filename) event-log-keep))

(defn- lines-newest-first [f]
  (reverse (remove str/blank? (str/split-lines (slurp f)))))

(def conversation-keys
  "Payload keys holding what was SAID or SENT, dropped from what the read-back
  commands return.

  The file keeps them — a dataset is derived from it, and a call is replayed
  from it. What this bounds is what the AGENT can pull back through `log$events`
  and `log$search`, which are tools a turn can call. `byj-gly.85` made this
  argument when the fix was to stop writing them; writing them is now the point,
  so the promise moves to the read.

  Deliberately NOT here: the question, tool arguments and tool results.
  `byj-gly.85` kept those too — the trajectory and the session snapshot already
  hold them under the same gate, so withholding them here buys nothing."
  ;; and the whole-conversation payloads the session's own events carry — a
  ;; resumed transcript, a subagent's answer, a recap (byj-gly.233)
  #{:inputs :outputs :reasoning :messages :content :body :stdin :args :response
    :transcript :answer :recap})

(defn trim-for-read
  "A record with its conversation keys removed, and a note of which."
  [r]
  (let [d (:data r)]
    (if-not (map? d)
      r
      (let [dropped (filter (set (keys d)) conversation-keys)]
        (cond-> (assoc r :data (apply dissoc d conversation-keys))
          (seq dropped) (assoc :withheld (vec (sort dropped))))))))

(defn read-events
  "Events from the log, NEWEST FIRST, at most `:limit`.

  Newest first because every question anyone asks a log is about the recent
  past, and reading oldest-first would mean holding a whole rotation in memory
  to answer it. Unreadable lines are skipped: the file is appended to by a live
  process, so a reader can catch a half-written tail.

  Filters: `:event` (exact), `:level`, `:session`, `:since` (epoch ms), and
  `:match` — every word of which must appear in the printed record.

  Records are TRIMMED before they are filtered (`trim-for-read`), unless
  `:full?`. Trimming first is what stops `:match` being a probe: matching on the
  whole record and then hiding it would still let a caller confirm that a phrase
  appears in a prompt it is not allowed to read."
  [{:keys [project-dir event-log-keep] :as config} {:keys [event level session since match limit full?]}]
  (let [f (io/file (or project-dir ".") ".brainyard" "logs" filename)
        limit (or limit 50)
        words (->> (str/split (str/lower-case (str match)) #"\s+")
                   (remove str/blank?))
        prepare (if full? identity trim-for-read)
        keeps? (fn [r]
                 (and (or (nil? event) (= event (:event r)))
                      (or (nil? level) (= level (:level r)))
                      (or (nil? session) (= (str session) (:session r)))
                      (or (nil? since) (>= (or (:ts r) 0) since))
                      (or (empty? words)
                          (let [t (str/lower-case (pr-str r))]
                            (every? #(str/includes? t %) words)))))]
    (if-not (.isFile f)
      []
      (into []
            (comp (mapcat lines-newest-first)
                  (keep #(try (edn/read-string %) (catch Throwable _ nil)))
                  ;; trim BEFORE filtering, so `:match` cannot be used to
                  ;; confirm a phrase in a prompt the caller may not read
                  (map prepare)
                  (filter keeps?)
                  (take limit))
            (files-newest-first f event-log-keep)))))

;; --- what the model may ask ----------------------------------------------------------

(defn commands
  "`log$*`. brainyard also has `log$turns`; here a turn already has a better
  record of its own — `trajectory.edn` (RFC-025) holds every iteration, and
  `memory$history` searches what was asked and answered. A third answer to the
  same question would be the worst of the three."
  [config]
  (when (log-file config)
    [{:name "log$events" :kind :command
      :description (str "What this project's agent DID in earlier runs, newest first."
                        " Reach for it when something used to work, when a tool or hook is"
                        " not behaving as its definition says, or when you need to know"
                        " whether an earlier run hit the same problem. For what a turn"
                        " concluded, prefer memory$history; this is the machinery underneath.")
      :input-schema [:map [:event {:optional true} [:string {:desc "Exact event, e.g. tools/user-tool-skipped. Omit for all."}]]
                          [:level {:optional true} [:string {:desc "problem | trace | quiet — problem is the one worth asking for."}]]
                          [:session {:optional true} [:string {:desc "Only this session's events."}]]
                          [:limit {:optional true} [:int {:desc "Events to return (default 50)."}]]]
      :output-schema [:or {:desc "newest first; :withheld names the conversation keys dropped from :data"}
                [:map [:events [:vector [:map [:ts :int] [:event :keyword] [:level :keyword]
                                          [:session {:optional true} :string]
                                          [:turn {:optional true} :any]
                                          [:agent {:optional true} :string]
                                          [:data :any]
                                          [:withheld {:optional true} [:vector :keyword]]]]]
                 [:count :int]]
                [:map [:error-message :string]]]
      :effects #{:fs/read}
      :fn (fn [{:keys [event level session limit]} ctx]
            ;; a filter nobody can satisfy returns nothing, and nothing reads
            ;; exactly like "all clear" — a live run asked for :level "error",
            ;; got 0 events, and reported no problems (`byj-gly.84`)
            (let [kw #(when-not (str/blank? (str %)) (keyword (str/replace (str %) #"^:" "")))
                  lv (kw level)
                  ev (kw event)]
              (cond
                (and lv (not (contains? events/levels lv)))
                {:error-message (str lv " is not a level. They are: "
                                     (str/join ", " (sort (map name events/levels)))
                                     " — `problem` is the one worth asking for.")}

                (and ev (not (contains? events/catalogue ev)))
                {:error-message (str ev " is not an event this agent emits."
                                     (let [near (->> (keys events/catalogue)
                                                     (filter #(str/includes? (str %) (name ev)))
                                                     sort (take 5))]
                                       (if (seq near)
                                         (str " Did you mean: " (str/join ", " near) "?")
                                         (str " There are " (count events/catalogue)
                                              "; omit :event, or use log$search for a phrase."))))}

                :else
                (let [es (read-events (:config ctx)
                                      {:event ev :level lv
                                       :session (when-not (str/blank? (str session)) session)
                                       :limit limit})]
                  {:events es :count (count es)}))))}

     {:name "log$search" :kind :command
      :description (str "Search the event log for a phrase — a file path, a tool name, an error."
                        " Every word of 3+ characters must appear in the same event.")
      :input-schema [:map [:query [:string {:desc "Words that must all appear."}]]
                          [:limit {:optional true} [:int {:desc "Events to return (default 20)."}]]]
      :output-schema [:or {:desc "newest first; :withheld names the conversation keys dropped from :data"}
                [:map [:events [:vector [:map [:ts :int] [:event :keyword] [:level :keyword]
                                          [:session {:optional true} :string]
                                          [:turn {:optional true} :any]
                                          [:agent {:optional true} :string]
                                          [:data :any]
                                          [:withheld {:optional true} [:vector :keyword]]]]]
                 [:count :int]]
                [:map [:error :string]]]
      :effects #{:fs/read}
      :fn (fn [{:keys [query limit]} ctx]
            (if (str/blank? (str query))
              {:error "query is required"}
              (let [es (read-events (:config ctx) {:match query :limit (or limit 20)})]
                {:events es :count (count es)})))}]))
