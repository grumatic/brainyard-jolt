;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.trajectory
  "The trajectory log (RFC-025) — brainyard's
  `agent/common/trajectory.clj` and `coact-store-results-action`.

  One EDN record per TURN, appended to the session's directory beside the
  prediction log (RFC-021):

      <project-dir>/.brainyard/sessions/<session-id>/trajectory.edn

  A record is what the turn *did*: the question, every iteration with its
  reasoning and tool calls, the answer, how it ended, and what it cost. That is
  the unit worth keeping. The raw `:on-event` stream is not — it is dominated by
  `:agent.llm/chunk`, one event per streamed fragment, and answers no question
  the per-turn projection does not answer better.

  **It is on by default** (`:trajectory-log?`, brainyard's
  `:enable-trajectory-recording`), unlike the prediction log. The difference is
  not carelessness: a session snapshot already saves the same questions and
  answers under the same directory (`:persist-session?`, RFC-016, also on), so
  recording this costs no exposure a project has not already accepted. What the
  prediction log adds on top is the full model INPUTS — assembled prompts,
  recalled memory — which is a decision a project makes for itself.

  `:v 3` names brainyard's record SHAPE, not this port's history: a v3 record
  here is a v3 record there, and `record-answer` keeps brainyard's v2 fallback
  so a file written by either is readable by both."
  (:require [clojure.edn :as edn]
            [brainyard.config :as config]
            [brainyard.cost :as cost]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.version :as version]))

(def version 3)
(def filename "trajectory.edn")

;; brainyard's, verbatim: a turn's iterations are kept in full, but one runaway
;; tool result must not make a record nobody can read back
(def ^:const max-field-chars 4000)
(def ^:const max-thought-chars 2000)
(def ^:const max-answer-chars 50000)

(defn trunc
  "`s` at most `n` characters, saying how much was dropped. brainyard's `trunc`."
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "…[truncated " (- (count s) n) " chars]")
      s)))

;; --- what a turn's iterations look like on the way out -----------------------------

(defn project-iteration
  "One of `st-memory`'s `:trajectory-iterations` as a trajectory entry —
  brainyard's `project-iteration`.

  The keys are renamed on purpose: what the loop carries is named for the loop
  (`:iteration`, `:tool-results`, `:tool-name`), and what is written down is
  named for a reader of the file."
  [{:keys [iteration thought channel tool-results answer error notices]}]
  (let [c (or channel "none")]
    (cond-> {:n iteration :channel c}
      (not (str/blank? thought)) (assoc :thought (trunc thought max-thought-chars))
      (not (str/blank? notices)) (assoc :notices (trunc notices max-field-chars))
      (= "answer" c) (assoc :answer (trunc answer max-answer-chars))
      (= "llm-error" c) (assoc :error (str error))
      (seq tool-results)
      (assoc :tools (mapv (fn [{:keys [tool-name tool-args tool-result tool-error? spilled node slot]}]
                            (cond-> {:name (str tool-name)}
                              ;; a tree's call (`:tool/run`): the node that
                              ;; made it, and the slot it ran in (byj-8gc)
                              node (assoc :node node)
                              slot (assoc :slot slot)
                              (some? tool-args) (assoc :args tool-args)
                              (some? tool-result) (assoc :result (trunc tool-result max-field-chars))
                              ;; where the whole of a result the prompt cut went
                              spilled (assoc :spilled spilled)
                              ;; only when it failed: a record is read far more
                              ;; often than it is written, and `:error? false`
                              ;; on every successful call is noise
                              tool-error? (assoc :error? true)))
                          tool-results)))))

(defn model-call?
  "Did the model make this call — a record's `:tools` entry, or a loop's
  `:tool-results` one? Not when a tree's `:tool/run` did, which says its
  `:node` (byj-8gc): what a model did is counted apart from what its loop
  did around it."
  [call]
  (nil? (:node call)))

(defn- answer-iteration? [it] (= "answer" (:channel it)))

(defn with-answer-iteration
  "`entries` guaranteed to end in an answer iteration when there is an answer —
  brainyard's `with-answer-iteration`.

  A turn can finish without one: `ensure-answer` and `loop-fallback` write an
  answer straight into st-memory after the loop, so no iteration ever carried
  it. The synthesized entry has no `:thought`, deliberately — there was none,
  and inventing one would teach a reader (or a fine-tune) that the agent
  deliberated when it did not."
  [entries answer]
  (if (or (str/blank? answer) (some answer-iteration? entries))
    (vec entries)
    (conj (vec entries) {:n (inc (count entries))
                         :channel "answer"
                         :answer (trunc answer max-answer-chars)})))

(defn record-answer
  "A record's final answer. Always read it this way: v3 keeps the answer in its
  terminal iteration, v2 kept it at the top, and an append-only file holds both
  forever. brainyard's `record-answer`."
  [record]
  (or (some->> (:iterations record) (filter answer-iteration?) last :answer not-empty)
      (:answer record)))

;; --- the record ---------------------------------------------------------------------

(def succeeded-by
  "The endings that mean the turn ANSWERED. `stamp-answer` stamps
  `:answer-channel`, and keeps an abort's own reason instead when there is one,
  so this is the whole set.

  brainyard names the *failures* — `#{:max-iterations-exhausted :llm-error
  :none-channel-loop-guard}` — which is the same judgement when the endings are
  known and the wrong default when they are not: this port also stops on
  `:cancelled`, `:usage`, `:iteration-count`, `:loop-error` and
  `:tool-blocked-by-hook`, and any ending added later would score as a success
  nobody decided it was. Naming the successes makes an unrecognised ending
  count as a failure, which is the answer that asks to be looked at
  (divergence `trajectory-success-is-an-allowlist`)."
  #{:answer-channel})

(defn- compact-usage
  "st-memory's usage as brainyard's compact shape. `:calls` is this port's: it
  counts LLM calls per turn and the session summary already prints them, so
  dropping the count here would lose what is already known."
  [{:keys [input-tokens output-tokens cache-read-tokens cache-write-tokens llm-calls]}]
  (cond-> {:in (or input-tokens 0) :out (or output-tokens 0)}
    (pos? (or cache-read-tokens 0)) (assoc :cache-read cache-read-tokens)
    (pos? (or cache-write-tokens 0)) (assoc :cache-write cache-write-tokens)
    (pos? (or llm-calls 0)) (assoc :calls llm-calls)))

(def record-schema
  "A record as `record` writes it (byj-gly.204): what analytics, the dataset
  export and `session$analytics` read back. Reading stays tolerant — a torn
  line is skipped, and brainyard's own v2 records are read too — so this is
  the WRITER's contract, held by the tests, not a gate on reading."
  [:map
   [:v :int]
   [:ts :int]
   [:question [:maybe :string]]
   [:success :boolean]
   [:terminated-by [:maybe :keyword]]
   [:total-iterations :int]
   [:iterations [:vector [:map
                          [:n :int]
                          [:channel :string]
                          [:thought {:optional true} :string]
                          [:notices {:optional true} :string]
                          [:answer {:optional true} [:maybe :string]]
                          [:error {:optional true} :string]
                          [:tools {:optional true}
                           [:vector [:map [:name :string]
                                     [:args {:optional true} :any]
                                     [:result {:optional true} :string]
                                     [:spilled {:optional true} :string]
                                     [:error? {:optional true} :boolean]
                                     [:node {:optional true} :keyword]
                                     [:slot {:optional true} :keyword]]]]]]]
   [:session {:optional true} :string]
   [:agent {:optional true} :string]
   ;; who injected the turn over the session channel (RFC-043): a person's
   ;; {:user :name :role}, a session's {:session}, with its :approval
   [:injected-by {:optional true} [:map-of :keyword :any]]
   ;; the slash command the question was rephrased from (byj-gly.350)
   [:command {:optional true} :string]
   [:permission-mode {:optional true} :string]
   [:model {:optional true} [:maybe :string]]
   [:provider {:optional true} [:maybe :keyword]]
   [:usage {:optional true} [:map-of :keyword :any]]
   [:cost {:optional true} number?]
   [:cost-partial? {:optional true} :boolean]
   [:jolt {:optional true} :string]
   [:brainyard-version {:optional true} :string]
   [:duration-ms {:optional true} :int]])

(defn record
  "A turn as a trajectory record — brainyard's `build-record`.

  There is no `:turn` key, and that is deliberate in the original: a per-agent
  turn counter restarts at 1 when a session resumes, so two records claimed to
  be turn 1. Position in the append-only file is the ordering."
  [{:keys [session agent owner question iterations answer success terminated-by
           model provider usage started-at ended-at injected-by command permission-mode loop-hash]}]
  (let [entries (with-answer-iteration (mapv project-iteration iterations) answer)
        ended (or ended-at (System/currentTimeMillis))]
    (cond-> {:v version
             :ts ended
             :question (trunc question max-field-chars)
             :success (boolean success)
             :terminated-by terminated-by
             :total-iterations (count entries)
             :iterations entries}
      session (assoc :session (str session))
      agent (assoc :agent (str agent))
      ;; who dispatched a subagent's turn — another instance, or "root" — so a
      ;; session's children can be put back into their tree (byj-gly.298.1)
      owner (assoc :owner (str owner))
      ;; a turn someone else put in the session (RFC-043): an intervention,
      ;; which an evaluator counts apart from the owner's own
      (some? injected-by) (assoc :injected-by injected-by)
      ;; a question that came from a slash command keeps the command as typed
      (not (str/blank? (str command))) (assoc :command (str command))
      ;; the permission mode in force when the turn ended — what a scorer
      ;; reads, as Claude Code's transcript gives it per line
      permission-mode (assoc :permission-mode (name permission-mode))
      model (assoc :model model)
      provider (assoc :provider provider)
      ;; which loop the turn ran: the hash of its tree as it ran, expanded —
      ;; what a harness that chose the loop checks (RFC-071, byj-ng7.19)
      loop-hash (assoc :loop {:hash loop-hash})
      (seq usage) (assoc :usage (compact-usage usage))
      ;; what the turn cost, when anything could say. An ABSENT :cost means
      ;; unknown; a 0.0 would mean free, and those are not the same thing
      ;; (RFC-025, divergence `unknown-cost-is-not-free`)
      (:cost (cost/summary usage)) (assoc :cost (:cost (cost/summary usage)))
      (:partial? (cost/summary usage)) (assoc :cost-partial? true)
      ;; which RUNTIME produced this turn. deps.edn declares a floor, not a pin,
      ;; so the jolt under a turn can change without anything saying so — and
      ;; once did, taking `via` binding conveyance with it (`byj-gly.117`). An
      ;; absent :jolt means the property was not set, not that it was unknown to
      ;; matter (`byj-gly.118`)
      (config/jolt-version) (assoc :jolt (config/jolt-version))
      ;; and which build of brainyard-jolt (RFC-029)
      :always (assoc :brainyard-version (version/version))
      started-at (assoc :duration-ms (- ended started-at)))))

(defn note-record
  "A note for people (`:inject :as :note`, RFC-043) as a line of the
  trajectory: not a turn — `:kind :note` marks it, and `read-trajectories`
  leaves it out — but kept beside the turns it was said between, for a review
  and for evaluation to count."
  [{:keys [session text from ts]}]
  (cond-> {:v version :kind :note :ts (or ts (System/currentTimeMillis)) :text (str text)}
    session (assoc :session (str session))
    (some? from) (assoc :from from)))

(defn note? [rec] (= :note (:kind rec)))

(defn succeeded?
  "brainyard's rule: an answer, arrived at rather than given up on."
  [answer terminated-by]
  (and (not (str/blank? answer))
       (contains? succeeded-by terminated-by)))

;; --- the file ------------------------------------------------------------------------

(defn log-file
  "The trajectory file for a run, or nil when the log is off, the run is
  anonymous, or the run was told to leave nothing behind.

  `:persist-session?` gates this too, and it has to. The record is part of what
  a session leaves on disk, in the session's own directory — so `--no-persist`
  meaning *save the session* but not *save the log of the session* would be a
  distinction nobody asked for. It is also what makes the default defensible:
  this is on by default because a snapshot already saves the same questions and
  answers, and where there is no snapshot that argument does not hold."
  [{:keys [trajectory-log? persist-session? project-dir session-id]}]
  (when (and (not (false? trajectory-log?))
             (not (false? persist-session?))
             session-id project-dir)
    (io/file project-dir ".brainyard" "sessions" (str session-id) filename)))

;; One monitor for every append in this process. A turn writes one line, so
;; contention is not a cost worth optimising — but subagents run concurrently
;; and share the parent's session directory, and two half-lines interleaved
;; make a record nobody can read back.
(defonce ^:private append-lock (Object.))

(defn append!
  "Append one record to `f`. Returns the file, or nil.

  Never throws: losing a record must not cost the turn that produced it. That
  is brainyard's posture too — its store action wraps the whole block and logs
  the failure rather than raising."
  [f rec]
  (try
    (locking append-lock
      (when-let [parent (.getParentFile f)]
        (when-not (.exists parent) (.mkdirs parent)))
      (spit f (str (pr-str rec) "\n") :append true))
    f
    (catch Throwable _ nil)))

(defn read-entries
  "Every line of a session's trajectory, oldest first — turns and notes —
  skipping unreadable lines.

  A line is skipped rather than raised on because the file is appended to by a
  live process: a read racing a write sees a half-written tail, and one torn
  line at the end must not cost the reader every record before it."
  [{:keys [project-dir]} session-id]
  (let [f (io/file project-dir ".brainyard" "sessions" (str session-id) filename)]
    (if-not (.isFile f)
      []
      (->> (str/split-lines (slurp f))
           (remove str/blank?)
           (keep #(try (edn/read-string %) (catch Throwable _ nil)))
           vec))))

(defn read-trajectories
  "A session's turn records, oldest first: its trajectory without the notes."
  [config session-id]
  (vec (remove note? (read-entries config session-id))))

(defn read-notes
  "A session's notes for people (RFC-043), oldest first."
  [config session-id]
  (filterv note? (read-entries config session-id)))

(defn sessions
  "Session ids that have a trajectory, newest-written first."
  [{:keys [project-dir]}]
  (let [d (io/file project-dir ".brainyard" "sessions")]
    (if-not (.isDirectory d)
      []
      (->> (.listFiles d)
           (filter #(.isFile (io/file % filename)))
           (sort-by #(- (.lastModified (io/file % filename))))
           (mapv #(.getName %))))))
