;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.codex-sessions
  "This project's Codex sessions, as claude-sessions reads Claude Code's
  (byj-gly.430.2, RFC-049): the ones running now and the ones saved, and
  where a session's newest turn stands.

  Read from what Codex leaves in its home — `$CODEX_HOME`, else `~/.codex`
  (Orca, for one, sets its own) — not a published interface (measured: Codex
  CLI 0.163.0), so everything here is read defensively: a file or a line that
  does not parse is skipped, a session with no title is shown by its id, and
  nothing here throws. Nothing here writes to the home.

  - saved: `sessions/YYYY/MM/DD/rollout-<time>-<thread>.jsonl`, one JSON line
    each `{timestamp type payload}`; its first, `session_meta`, names the
    thread (`id`) and the directory it runs in (`cwd`). Its title is the
    thread's name in `session_index.jsonl` (`{id thread_name updated_at}`, the
    newest line for an id winning), else its first question. A cold rollout
    (`.jsonl.zst`) names no directory without being read, so none is listed.
  - live: Codex holds `thread-writer-locks/<thread>.lock` exclusively while a
    session runs, from start-up to exit; a non-blocking shared `flock` is
    refused then. A session that has not been asked anything yet has no
    rollout, so no directory, and is not listed until it has.

  Mechanism only: what the page shows, and how, is the renderer's (RFC-028)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.worktree :as worktree]
            [jolt.ffi :as ffi]))

(defn codex-home
  "Codex's home: `home` when given (a test's fixture), else the JVM property
  `brainyard.codex-home` — the test runner points it at a directory of its own,
  so no test reads the person's Codex sessions or limits — else
  `$CODEX_HOME`, else `~/.codex`."
  [home]
  (io/file (or home (System/getProperty "brainyard.codex-home") (System/getenv "CODEX_HOME")
               (str (System/getProperty "user.home") "/.codex"))))

;; --- live: the thread writer's lock -------------------------------------------------

(ffi/defcfn ^:private c-open "open" [:string :int] :int)
(ffi/defcfn ^:private c-flock "flock" [:int :int] :int)
(ffi/defcfn ^:private c-close "close" [:int] :int)

(def ^:private lock-sh 1)
(def ^:private lock-nb 4)
(def ^:private lock-un 8)

(defn live?
  "Is Codex writing thread `id` now? Its writer holds
  `thread-writer-locks/<id>.lock` exclusively: a non-blocking shared lock is
  refused while it does, and taken (and let go at once) when it does not. No
  lock file: not live."
  [home id]
  (let [fd (c-open (str (io/file (codex-home home) "thread-writer-locks" (str id ".lock"))) 0)]
    (if (neg? fd)
      false
      (try
        (let [r (c-flock fd (bit-or lock-sh lock-nb))]
          (when (zero? r) (c-flock fd lock-un))
          (neg? r))
        (finally (c-close fd))))))

;; --- reading rollouts ----------------------------------------------------------------

(def head-bytes
  "How much of a rollout's start is read for its first question: Codex's
  instructions come first, tens of KB of them."
  262144)

(defn- read-range [^java.io.File f start len]
  (with-open [in (java.io.FileInputStream. f)]
    (when (pos? start) (.skip in start))
    (let [b (byte-array len)]
      (loop [off 0]
        (let [r (if (< off len) (.read in b off (- len off)) -1)]
          (if (pos? r) (recur (+ off r)) (String. b 0 off "UTF-8")))))))

(defn- json-lines [s]
  (keep (fn [l] (when (str/starts-with? l "{")
                  (try (let [m (json/read-str l)] (when (map? m) m)) (catch Throwable _ nil))))
        (str/split-lines s)))

(defn- one-line [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- item-text
  "A completed item's text: its content's `text` parts, joined."
  [item]
  (str/join (keep :text (:content item))))

(defn message
  "The person's (`:user`) or the agent's (`:agent`) message a rollout line
  carries, or nil: an `item_completed` `UserMessage`/`AgentMessage` (Codex
  0.163 on), or the `user_message`/`agent_message` event before it."
  [{:keys [type payload]}]
  (when (= "event_msg" type)
    (case (:type payload)
      "item_completed" (case (get-in payload [:item :type])
                         "UserMessage" [:user (item-text (:item payload))]
                         "AgentMessage" [:agent (item-text (:item payload))]
                         nil)
      "user_message" [:user (str (:message payload))]
      "agent_message" [:agent (str (:message payload))]
      nil)))

(defn read-meta
  "A rollout's `session_meta`: `{:id :cwd}`, and for a subagent's thread
  `:parent` (the thread that spawned it), `:role` (the agent it runs as) and
  `:path` (its task, `/root/<name>`, which the spawn's output names) —
  measured, 0.163: `parent_thread_id`, `source.subagent.thread_spawn`'s
  `agent_role` and `agent_path` — or nil."
  [^java.io.File f]
  (let [m (first (json-lines (read-range f 0 (min (.length f) head-bytes))))
        p (:payload m)]
    (when (and (= "session_meta" (:type m)) (:id p))
      (cond-> {:id (str (:id p)) :cwd (some-> (:cwd p) str)}
        (:parent_thread_id p) (assoc :parent (str (:parent_thread_id p))
                                     :role (some-> (get-in p [:source :subagent :thread_spawn :agent_role]) str)
                                     :path (some-> (or (get-in p [:source :subagent :thread_spawn :agent_path]) (:agent_path p)) str))))))

(defn- first-question [^java.io.File f]
  (some (fn [l] (let [[who text] (message l)] (when (and (= :user who) (not (str/blank? text))) text)))
        (json-lines (read-range f 0 (min (.length f) head-bytes)))))

(defn thread-names
  "`session_index.jsonl` as thread id → name, the newest line for an id
  winning."
  [home]
  (let [f (io/file (codex-home home) "session_index.jsonl")]
    (if-not (.isFile f)
      {}
      (reduce (fn [m l] (if (and (:id l) (not (str/blank? (:thread_name l))))
                          (assoc m (str (:id l)) (str (:thread_name l)))
                          m))
              {} (json-lines (slurp f))))))

(defn rollout-file
  "Thread `id`'s rollout — its name ends `-<id>.jsonl` — or nil before Codex
  has written one, as it has not until the session is first asked."
  [home id]
  (let [suffix (str "-" id ".jsonl")]
    (->> (file-seq (io/file (codex-home home) "sessions"))
         (filter (fn [^java.io.File f] (and (.isFile f) (str/ends-with? (.getName f) suffix))))
         first)))

(defn rollouts
  "Every rollout file in the home, cold ones left out."
  [home]
  (->> (file-seq (io/file (codex-home home) "sessions"))
       (filter (fn [^java.io.File f] (and (.isFile f) (str/starts-with? (.getName f) "rollout-")
                                          (str/ends-with? (.getName f) ".jsonl"))))
       vec))

(defonce ^:private meta-cache
  ;; path → {:id :cwd}: a rollout's first line never changes
  (atom {}))

(defonce ^:private title-cache
  ;; path → [modified bytes question]
  (atom {}))

(defn- meta-of [^java.io.File f]
  (let [k (.getPath f)]
    (if-let [m (get @meta-cache k)]
      m
      (let [m (try (read-meta f) (catch Throwable _ nil))]
        (when m (swap! meta-cache assoc k m))
        m))))

(defn- question-of [^java.io.File f]
  (let [k (.getPath f) m (.lastModified f) b (.length f)
        [m0 b0 q] (get @title-cache k)]
    (if (and (= m m0) (= b b0))
      q
      (let [q (try (first-question f) (catch Throwable _ nil))]
        (swap! title-cache assoc k [m b q])
        q))))

(defn- canonical [dir] (str (or (config/canonical-dir dir) dir)))

(defn- project-rollouts
  "The rollouts of `dirs` (the project and its worktrees): `[file meta]`. A
  subagent's thread is its parent's, not a session of its own (`subagents`)."
  [home dirs]
  (let [ours (into #{} (map canonical) dirs)]
    (keep (fn [f] (when-let [m (meta-of f)]
                    (when (and (:cwd m) (not (:parent m)) (ours (canonical (:cwd m)))) [f m])))
          (rollouts home))))

(defn subagents
  "The rollouts of the subagents thread `id` spawned: `[file meta]`, `meta`
  with its `:role`."
  [home id]
  (keep (fn [f] (when-let [m (meta-of f)] (when (= id (:parent m)) [f m])))
        (rollouts home)))

(defn saved
  "This project's saved Codex sessions, newest first: `{:id :title
  :updated-at :bytes :file}`, and `:dir` for one run in another of `dirs` (a
  worktree) than the project."
  [project-dir & [home dirs]]
  (let [here (canonical project-dir)
        names (thread-names home)]
    (->> (project-rollouts home (or (seq dirs) [project-dir]))
         (map (fn [[^java.io.File f {:keys [id cwd]}]]
                (let [d (canonical cwd)]
                  (cond-> {:id id
                           :title (some-> (or (get names id) (question-of f)) (one-line 120))
                           :updated-at (.lastModified f) :bytes (.length f) :file (.getPath f)}
                    (not= d here) (assoc :dir d)))))
         (sort-by :updated-at >)
         vec)))

(defn live
  "This project's Codex sessions running now: the saved ones whose writer
  holds their lock. `live-fn` (home id → bool) is `live?` unless a test gives
  its own."
  [project-dir & [home dirs live-fn]]
  (let [live-fn (or live-fn live?)]
    (filterv #(live-fn home (:id %)) (saved project-dir home dirs))))

(defn sessions
  "This project's Codex sessions — in it or any of its git worktrees
  (`worktree/dirs`, unless `dirs` says) — as `{:live […] :saved […]}`, a
  session running now listed live and not saved again."
  [project-dir & {:keys [home dirs] live-fn :live?}]
  (let [dirs (or dirs (worktree/dirs project-dir))
        sv (saved project-dir home dirs)
        lv (filterv #((or live-fn live?) home (:id %)) sv)
        live-ids (set (map :id lv))]
    {:live lv :saved (vec (remove #(live-ids (:id %)) sv))}))

;; --- where the newest turn stands -------------------------------------------------------

(def first-read-bytes
  "How much of a rollout `last-turn` reads the first time it sees it — its
  end; after that, only what was added."
  (* 8 1024 1024))

(defonce ^:private turn-cache
  ;; path → state with :offset: where a rollout was read to, and its newest
  ;; turn as of there
  (atom {}))

(defn- turn-step
  "The newest turn after rollout line `l`: a `task_started` opens one — `:busy?`
  until it ends — the person's message is its `:prompt` and the agent's newest
  its `:answer`; `task_complete` ends it (its `last_agent_message` the answer
  when none was seen), `turn_aborted` too; `token_count` is the thread's
  running total."
  [st {:keys [type payload] :as l}]
  (let [[who text] (message l)]
    (cond
      (= :user who) (if (and (:prompt st) (not (:ended? st)) (= text (:prompt st)))
                      st                                      ; the same message, written both ways
                      (assoc st :prompt text :answer nil :ended? false :aborted? false :busy? true))
      (= :agent who) (cond-> st (not (str/blank? text)) (assoc :answer text))
      (not= "event_msg" type) st
      :else
      (case (:type payload)
        "task_started" (assoc st :prompt nil :answer nil :ended? false :aborted? false :busy? true)
        "task_complete" (cond-> (assoc st :ended? true :busy? false)
                          (and (nil? (:answer st)) (:last_agent_message payload))
                          (assoc :answer (str (:last_agent_message payload))))
        "turn_aborted" (assoc st :ended? true :aborted? true :busy? false)
        "token_count" (if-let [u (get-in payload [:info :total_token_usage])]
                        (assoc st :usage (select-keys u [:input_tokens :cached_input_tokens
                                                         :output_tokens :reasoning_output_tokens]))
                        st)
        st))))

(def ^:private turn-needles
  ["\"task_started\"" "\"task_complete\"" "\"turn_aborted\"" "\"token_count\""
   "\"UserMessage\"" "\"AgentMessage\"" "\"user_message\"" "\"agent_message\""])

(defn last-turn
  "Where the newest turn of rollout `f` stands: `{:prompt :answer :ended?
  :busy?}`, `:aborted?` when it was interrupted, and `:usage` (the thread's running
  token totals) once Codex has counted; nil when there is no file. Each file
  is read from where the last call stopped, complete lines only, the first
  read its last `first-read-bytes`."
  [^java.io.File f]
  (when (and f (.isFile f))
    (let [k (.getPath f)
          n (.length f)
          prev (get @turn-cache k)
          prev (when (and prev (<= (:offset prev) n)) prev)   ; shorter: rewritten
          start (or (:offset prev) (max 0 (- n first-read-bytes)))
          st (or (some-> prev (dissoc :offset)) {:prompt nil :answer nil :ended? false :busy? false})]
      (if (= start n)
        st
        (let [^bytes b (with-open [in (java.io.FileInputStream. f)]
                         (when (pos? start) (.skip in start))
                         (let [b (byte-array (- n start))]
                           (loop [off 0]
                             (let [r (if (< off (alength b)) (.read in b off (- (alength b) off)) -1)]
                               (if (pos? r) (recur (+ off r)) b)))))
              end (loop [i (dec (alength b))] (cond (neg? i) -1 (= 10 (aget b i)) i :else (recur (dec i))))]
          (if (neg? end)
            st
            (let [ls (cond->> (str/split-lines (String. b 0 (inc end) "UTF-8"))
                       (and (nil? prev) (pos? start)) rest)       ; the first read starts mid-line
                  st (reduce turn-step st
                             (keep (fn [l] (when (and (str/starts-with? l "{") (some #(str/includes? l %) turn-needles))
                                             (try (let [m (json/read-str l)] (when (map? m) m)) (catch Throwable _ nil))))
                                   ls))]
              (swap! turn-cache assoc k (assoc st :offset (+ start end 1)))
              st)))))))
