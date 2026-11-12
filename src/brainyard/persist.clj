;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.persist
  "Session snapshots and resume (RFC-016) — brainyard's
  `components/agent-tui-persist` in the small.

  A session is `<project-dir>/.brainyard/sessions/<session-id>/session.edn`:
  the turns, the counts and the usage, written after every turn. Everything
  else a session holds — the tool registry, the hooks, the permission channel,
  the loop tree — is rebuilt from the project on resume, so nothing that cannot
  be printed is ever written.

  Three rules, each from a failure brainyard recorded:
  - **Write atomically.** A crash mid-write must leave the previous snapshot,
    not half of a new one.
  - **A bad snapshot never stops a run.** Unreadable EDN is reported and the
    session starts fresh; the file is left alone for inspection.
  - **One live process per session.** Two `byj run`s resuming the same
    session would overwrite each other's turns."
  (:require [brainyard.config :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.schema :as schema]
            [brainyard.version :as version]))

(def schema-version
  "Bumped when a snapshot's shape changes. brainyard has no version key and
  relies on tolerance alone; a version lets an old file be recognised rather
  than half-read."
  1)

;; --- paths ------------------------------------------------------------------------

(defn sessions-dir [config]
  (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "sessions"))

(defn session-dir [config id] (io/file (sessions-dir config) (str id)))

(defn session-file [config id] (io/file (session-dir config id) "session.edn"))

(defn lock-file [config id] (io/file (session-dir config id) "lock.edn"))

;; --- pid and liveness -------------------------------------------------------------

(defn- sh [& cmd]
  (try (apply fx/sh {:out :string :err :string} cmd) (catch Throwable _ {:exit 1})))

(defn own-pid
  "This process's pid. jolt has no `ProcessHandle`, so ask the shell."
  []
  (let [r (sh "sh" "-c" "echo $PPID")]
    (when (zero? (:exit r)) (str/trim (str (:out r))))))

(defn alive?
  "Is `pid` a live process? `kill -0` succeeds for a process we may signal, and
  fails with EPERM for one owned by another user — which is a live process, not
  a stale lock. Only \"no such process\" means gone."
  [pid]
  (boolean
   (when pid
     (let [r (sh "kill" "-0" (str pid))]
       (or (zero? (:exit r))
           (str/includes? (str/lower-case (str (:err r))) "not permitted"))))))

;; --- reading and writing ----------------------------------------------------------

(defn- write-edn!
  "Write atomically: a sibling temp file, then a rename. A crash leaves the
  previous snapshot intact."
  [^java.io.File f data]
  (.mkdirs (.getParentFile f))
  (let [tmp (io/file (.getParentFile f) (str (.getName f) "." (System/currentTimeMillis) ".tmp"))]
    (try
      (spit tmp (with-out-str (clojure.pprint/pprint data)))
      (.renameTo tmp f)
      (finally (.delete tmp)))))

(def snapshot-schema
  "What a session.edn holds, as `save!` writes it (byj-gly.204). What every
  version-1 snapshot has is required; what was added since — the transcript,
  the title, the build, the model — is optional, and typed when it is there,
  so an older snapshot still resumes and a damaged one says where."
  [:map
   [:schema-version :int]
   [:id :string]
   [:turn-count :int]
   [:previous-turns [:vector [:map
                              [:question [:maybe :string]]
                              [:answer [:maybe :string]]
                              [:terminated-by {:optional true} [:maybe :keyword]]
                              [:tool-trail {:optional true} [:vector :string]]
                              ;; the turn it was, since RFC-069; a /compact
                              ;; summary has :compacted, the last turn it covers
                              [:turn {:optional true} :int]]]]
   [:created-at {:optional true} :int]
   [:updated-at {:optional true} :int]
   [:working-dir {:optional true} [:maybe :string]]
   [:project-dir {:optional true} [:maybe :string]]
   [:usage {:optional true} [:maybe :map]]
   [:last-question {:optional true} [:maybe :string]]
   [:transcript {:optional true} [:vector [:tuple :keyword :any]]]
   [:conversation-title {:optional true} [:maybe :string]]
   [:brainyard-version {:optional true} [:maybe :string]]
   [:model {:optional true} [:maybe :string]]
   [:next-user-prompt {:optional true} [:maybe :string]]
   ;; the session this one was forked from (byj-g5n.12), and the turn it
   ;; was cut before when it was (RFC-069)
   [:forked-from {:optional true} [:maybe :string]]
   [:forked-before-turn {:optional true} [:maybe :int]]])

(defn read-session
  "A snapshot, or `{:problem …}` — never throws. An unreadable file is left on
  disk: it is the only copy of whatever went wrong. A readable one that is not
  a snapshot's shape is a problem too, named: resuming it would fail somewhere
  later, further from the cause."
  [config id]
  (let [f (session-file config id)]
    (cond
      (not (.isFile f)) {:problem (str "no session " id)}
      :else
      (let [m (try (edn/read-string (slurp f)) (catch Throwable e {::unreadable (ex-message e)}))]
        (cond
          (::unreadable m) {:problem (str (.getPath f) ": unreadable EDN — " (::unreadable m))}
          (not (map? m)) {:problem (str (.getPath f) ": expected a map")}
          (not= schema-version (:schema-version m))
          {:problem (str (.getPath f) ": snapshot version " (:schema-version m)
                         ", this build writes " schema-version)}
          :else (if-let [ps (schema/explain-problems snapshot-schema m)]
                  {:problem (str (.getPath f) ": not a session snapshot — " (str/join "; " ps))}
                  m))))))

(def transcript-entries
  "How much of the session's own transcript a snapshot keeps, newest last: what
  a resumed front end puts back on the screen. A screenful and then some — the
  full-screen renderer keeps 500 — and not the whole window `/capture` has,
  since the snapshot is rewritten after every turn and an entry can be a whole
  answer."
  500)

(defn save!
  "Write the session's snapshot. Returns the file, or nil when it could not be
  written — persistence must never take a session down with it."
  [config id {:keys [turn-count usage previous-turns question transcript conversation-title model last-result]}]
  (try
    (let [f (session-file config id)
          existing (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))]
      (write-edn! f {:schema-version schema-version
                     :id (str id)
                     :created-at (or (:created-at existing) (System/currentTimeMillis))
                     :updated-at (System/currentTimeMillis)
                     ;; absolute, so a resume from another directory restores
                     ;; the session that was saved rather than one like it
                     :working-dir (config/canonical-dir (:working-dir config))
                     :project-dir (config/canonical-dir (:project-dir config))
                     :turn-count (or turn-count 0)
                     :usage usage
                     ;; the session's memory has no :question of its own:
                     ;; its last question is its last turn's (byj-gly.178)
                     :last-question (or question (:question (peek (vec previous-turns))))
                     :previous-turns (vec previous-turns)
                     ;; what was said, so a resume can say it again (RFC-016)
                     :transcript (vec (take-last transcript-entries transcript))
                     ;; the name the last recap gave it, so a resume is named too
                     :conversation-title conversation-title
                     ;; which build wrote it (RFC-029)
                     :brainyard-version (version/version)
                     ;; the model `/model` switched to, as its label, so a
                     ;; resume asks the same one — brainyard's save-meta! of
                     ;; :model and :provider on a switch. nil: the run's own
                     :model model
                     ;; what the model thought would be asked next, so a
                     ;; resume offers it again (byj-gly.280.4) — the last
                     ;; turn's; a save with no turn since the resume keeps
                     ;; the one it resumed with
                     :next-user-prompt (if last-result
                                         (not-empty (str/trim (str (:next-user-prompt last-result))))
                                         (:next-user-prompt existing))
                     ;; kept across saves: where a fork came from
                     :forked-from (:forked-from existing)
                     :forked-before-turn (:forked-before-turn existing)})
      f)
    (catch Throwable _ nil)))

(defn- numbered
  "`previous-turns` with the turn each one was, newest last ending at turn
  `n`: its own `:turn` (RFC-069), a /compact summary's `:compacted`, or for
  an entry saved before either, counted back from the newest."
  [previous-turns n]
  (let [v (vec previous-turns) c (count v)]
    (vec (map-indexed (fn [i e] [(or (:turn e) (:compacted e) (- n (- c 1 i))) e]) v))))

(defn cut-before
  "A snapshot `snap` as it stood before turn `k` began: its history and its
  transcript without turn `k` and after, `:turn-count` k−1 — or `{:problem}`
  when `k` is not one of its turns or falls inside a /compact summary."
  [snap k]
  (let [n (:turn-count snap 0)
        turns (numbered (:previous-turns snap) n)
        compacted (some (fn [[t e]] (when (:compacted e) t)) turns)]
    (cond
      (not (<= 1 k n)) {:problem (str "the session has turns 1–" n ", not " k)}
      (and compacted (<= k compacted)) {:problem (str "turn " k " is inside the summary /compact made of turns 1–" compacted)}
      :else
      (let [kept (vec (for [[t e] turns :when (< t k)] e))
            tr (vec (:transcript snap))
            ;; a turn's transcript starts with its question, [:you …]: the
            ;; newest is turn n, so turn k's is the (n−k+1)th from the end;
            ;; one the window has let go means all of it came after
            yous (keep-indexed (fn [i [kind]] (when (= :you kind) i)) tr)
            back (- n k -1)
            cut (if (>= (count yous) back) (nth (vec yous) (- (count yous) back)) 0)]
        (assoc snap
               :previous-turns kept
               :transcript (subvec tr 0 cut)
               :turn-count (dec k)
               :last-question (:question (peek kept))
               :next-user-prompt nil)))))

(defn fork!
  "A new session `new-id` that starts where `from-id` stands — or stood
  before turn `before-turn` began (`cut-before`, RFC-069): its snapshot,
  copied, with `:forked-from` naming it and times of its own (byj-g5n.12,
  RFC-053). Nothing else is copied — not its lock, channel, trajectory or
  transcripts — and `from-id` is not touched, so the two go on apart, each
  resumable. Returns the new snapshot's file, or `{:problem …}`: the snapshot
  unreadable, the turn not one to cut before, or `new-id` already a session."
  [config from-id new-id & [{:keys [before-turn]}]]
  (let [snap (read-session config from-id)
        snap (if (and before-turn (not (:problem snap))) (cut-before snap before-turn) snap)]
    (cond
      (:problem snap) snap
      (.exists (session-dir config new-id)) {:problem (str "session " new-id " already exists")}
      :else (let [now (System/currentTimeMillis)
                  f (session-file config new-id)]
              (write-edn! f (cond-> (assoc snap :id (str new-id) :forked-from (str from-id) :created-at now :updated-at now)
                              before-turn (assoc :forked-before-turn before-turn)))
              f))))

(defn summaries
  "Every session under the project, newest first: what `--resume` lists."
  [config]
  (let [dir (sessions-dir config)]
    (->> (when (.isDirectory dir) (.listFiles dir))
         (filter #(.isDirectory %))
         (keep (fn [d]
                 (let [m (read-session config (.getName d))]
                   (if (:problem m)
                     {:id (.getName d) :problem (:problem m)}
                     (-> (select-keys m [:id :updated-at :created-at :turn-count :usage :last-question
                                         :conversation-title])
                         ;; a snapshot written before :last-question was kept
                         (update :last-question #(or % (:question (peek (vec (:previous-turns m)))))))))))
         (sort-by #(or (:updated-at %) 0) >)
         vec)))

(defn last-run-file
  "Where a full-screen run records the set it has open (byj-gly.178)."
  [config]
  (io/file (sessions-dir config) "last-run.edn"))

(defn write-last-run!
  "Record the sessions a full-screen run has open, in order, and the one in
  front — what `--resume-run` brings back."
  [config {:keys [sessions front]}]
  (write-edn! (last-run-file config) {:sessions (vec sessions) :front front :at (System/currentTimeMillis)}))

(defn read-last-run
  "The set the last full-screen run had open, `{:sessions [id …] :front id
  :at}`, keeping only sessions still saved; nil when there is none."
  [config]
  (let [f (last-run-file config)
        m (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))
        kept (vec (filter #(.isFile (io/file (session-dir config %) "session.edn")) (:sessions m)))]
    (when (seq kept)
      (assoc m :sessions kept :front (if (some #{(:front m)} kept) (:front m) (first kept))))))

(defn latest-id
  "The most recently updated session, or nil."
  [config]
  (:id (first (remove :problem (summaries config)))))

;; --- locking ----------------------------------------------------------------------

(defn holder
  "The live process holding `id`, or nil. A lock whose pid is gone is stale and
  ignored — brainyard treats an unknown owner as alive; here a dead pid is
  positive evidence the session is free."
  [config id]
  (let [f (lock-file config id)]
    (when (.isFile f)
      (let [{:keys [pid]} (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
        (when (and pid (not= (str pid) (own-pid)) (alive? pid))
          pid)))))

(defn lock!
  "Claim `id` for this process. Returns true, or false when another live
  process holds it."
  [config id]
  (if (holder config id)
    false
    (do (write-edn! (lock-file config id) {:pid (own-pid) :at (System/currentTimeMillis)})
        true)))

(defn unlock!
  "Release the lock if it is ours. A clean exit leaves no lock file."
  [config id]
  (let [f (lock-file config id)]
    (when (.isFile f)
      (let [{:keys [pid]} (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
        (when (= (str pid) (own-pid)) (.delete f))))))

;; --- notes for a session's next turn (RFC-069, byj-gly.488) ----------------------

(defn notes-file [config id] (io/file (session-dir config id) "notes.edn"))

(defn note!
  "Leave `line` for session `id`'s next turn, which `take-notes!` hands it
  whether the session is live in another process or resumed later: what
  was done to its work tree from outside it (`byj turns rewind`, a `/fork`
  that put the files back). Only for a session there is: saved, or held by
  a live process. True when left."
  [config id line]
  (when (or (.isFile (session-file config id)) (.isFile (lock-file config id)))
    (let [f (notes-file config id)
          old (try (when (.isFile f) (edn/read-string (slurp f))) (catch Throwable _ nil))]
      (write-edn! f (conj (if (vector? old) old []) (str line)))
      true)))

(defn take-notes!
  "Session `id`'s notes, oldest first, and none left behind."
  [config id]
  (let [f (notes-file config id)]
    (when (.isFile f)
      (let [notes (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
        (.delete f)
        (when (vector? notes) (filterv string? notes))))))

;; --- pruning --------------------------------------------------------------------

(defn transcripts-dir [config]
  (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "transcripts"))

(defn- delete-tree!
  "Delete `dir`, but only when it is a direct child of `root` — brainyard's
  pruner deletes by path and its own guard is the only thing between a bad id
  and someone's project."
  [^java.io.File root ^java.io.File dir]
  (let [root-path (.getCanonicalPath root)
        dir-path (.getCanonicalPath dir)]
    (when (and (.isDirectory dir)
               (= root-path (some-> (.getParentFile dir) .getCanonicalPath))
               (not= root-path dir-path))
      (doseq [f (reverse (file-seq dir))] (.delete f))
      true)))

(defn resumable?
  "Does this directory hold a session someone could continue? A `session.edn`
  is what `--resume` reads; a directory without one is the record of a run that
  ended, not a conversation waiting to go on."
  [dir]
  (.isFile (io/file dir "session.edn")))

(defn- prunable
  "Directories under `root` that may go: not the current session, not held by a
  live process, older than the TTL, and outside the most recent `keep`.
  Age comes from the snapshot when there is one, else the directory's mtime.

  `:unresumable-keep` adds a second, smaller budget for directories with no
  `session.edn` — the record a one-shot `ask` leaves (RFC-025). Those are
  bounded by COUNT ALONE, with no TTL, because the TTL is the thing that lets
  them pile up: nothing younger than 14 days is ever pruned, so a busy day
  leaves a directory per turn and `:keep-sessions` never bites (`byj-gly.89`).

  Counting a resumable session the same way would be a worse bug than the one
  it fixes — a session from yesterday deleted because twenty newer asks
  happened — which is why the two are budgeted apart rather than together."
  [config root {:keys [keep ttl-days unresumable-keep]}]
  (let [now (System/currentTimeMillis)
        ttl-ms (* ttl-days 24 60 60 1000)
        dirs (->> (when (.isDirectory root) (.listFiles root))
                  (filter #(.isDirectory %))
                  (map (fn [d]
                         (let [snap (read-session config (.getName d))]
                           {:dir d :id (.getName d)
                            :at (or (:updated-at snap) (.lastModified d))
                            :resumable? (resumable? d)})))
                  (sort-by :at >))
        recent (set (map :id (take keep dirs)))
        ;; the newest N records of finished runs, counted among themselves
        spare (when unresumable-keep
                (set (map :id (take unresumable-keep (remove :resumable? dirs)))))]
    (for [{:keys [dir id at resumable?]} dirs
          :when (and (not= id (str (:session-id config)))
                     (not (holder config id))
                     (if (and spare (not resumable?))
                       (not (contains? spare id))
                       (and (not (contains? recent id))
                            (> (- now at) ttl-ms))))]
      dir)))

(defn tasks-dir [config]
  (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "tasks"))

(defn prune!
  "Remove old session, transcript and task directories. Returns {:removed [ids]}.

  Never removes: this run's own directories, a session another live process
  holds, the most recent `:keep-sessions` (20), or anything newer than
  `:session-ttl-days` (14). brainyard's pruner honours neither the lock nor the
  current session, and runs only when asked; this runs at startup, where the
  cost is one directory listing."
  [config]
  (let [opts {:keep (:keep-sessions config 20) :ttl-days (:session-ttl-days config 14)}
        ;; the sessions root also holds what one-shot runs left behind
        session-opts (assoc opts :unresumable-keep (:keep-ask-records config 200))
        ;; tasks keep their own bounds, which are brainyard's (RFC-019)
        task-opts {:keep (:task-retention-count config 100)
                   :ttl-days (:task-retention-days config 7)}
        roots [[(sessions-dir config) session-opts]
               [(transcripts-dir config) opts]
               [(tasks-dir config) task-opts]]]
    {:removed (vec (for [[root o] roots
                         dir (prunable config root o)
                         :when (delete-tree! root dir)]
                     (.getName dir)))}))

(defn delete!
  "Delete a saved session at someone's word — its directory and its
  transcript's (byj-g5n.14) — where `prune!` deletes by age. Returns true, or
  `{:problem …}`: there is no such session, or another live process holds it,
  whose next save would write it back."
  [config id]
  (let [dir (session-dir config id)]
    (cond
      (not (resumable? dir)) {:problem (str "no session " id)}
      (holder config id) {:problem (str "session " id " is held by process " (holder config id))}
      :else (do (delete-tree! (sessions-dir config) dir)
                (delete-tree! (transcripts-dir config) (io/file (transcripts-dir config) (str id)))
                true))))

;; --- resume ---------------------------------------------------------------------

(defn restore
  "The session memory a snapshot restores: the turns, the counts, the usage and
  the transcript, the conversation's name and a switched model's label.
  `:history-context` is NOT restored — it is rendered from the
  turns, and re-deriving it means one source of truth (RFC-004). A snapshot
  written before the transcript was kept restores with none."
  [snapshot]
  (merge {:transcript []}
         (select-keys snapshot [:previous-turns :turn-count :usage :transcript :conversation-title :model])))

(defn describe
  "One line for the user: what was resumed, and how far it got.

  `:turn-count` counts every turn the session ran; `:previous-turns` holds what
  it still remembers, and `/clear` makes those differ. Saying only the first
  greets someone with `3 turn(s)` and then shows them one (`byj-gly.75`), so
  when they differ the line says both."
  [{:keys [id turn-count previous-turns usage updated-at]}]
  (str "↻ resumed " id " · " (or turn-count 0) " turn(s)"
       (let [remembered (count previous-turns)]
         (when (not= remembered (or turn-count 0))
           (str " (" remembered " remembered)")))
       (when-let [c (:llm-calls usage)] (str " · " c " LLM call(s)"))
       (when updated-at
         (let [mins (quot (- (System/currentTimeMillis) updated-at) 60000)]
           (cond (< mins 1) " · last active just now"
                 (< mins 60) (str " · last active " mins "m ago")
                 (< mins 1440) (str " · last active " (quot mins 60) "h ago")
                 :else (str " · last active " (quot mins 1440) "d ago"))))))
