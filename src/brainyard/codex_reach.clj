(ns brainyard.codex-reach
  "A live Codex session as a target of the session tools (byj-gly.430.5,
  RFC-049, RFC-031), as `claude-reach` makes a Claude Code one:
  `session$list` lists this project's as `codex:<thread>`, `session$send` and
  `session$ask` hand one a task, `session$status` says how it stands.

  One running in this run's tmux (`codex-host/target`) is typed into, as a
  person would, and its answer read from its rollout once the turn is over
  (`codex-host/await-settled`). One running anywhere else — a terminal of its
  own, a tmux byj did not start — is reached with `codex queue --thread
  <thread> --message <line>`, which Codex runs as that session's next turn
  (measured, RFC-049 decision 10); its answer is read from the rollout too. A
  brief of one short line is sent as it is; a longer one is written to a file
  in the session's project and a line sent that points at it
  (`claude-reach/brief-line`).

  Mechanism only (RFC-001): when to hand a session work, and what to say, is
  the agent's."
  (:require [clojure.string :as str]
            [brainyard.claude-reach :as claude-reach]
            [brainyard.codex-host :as host]
            [brainyard.codex-sessions :as cxs]
            [brainyard.effect :as fx]
            [ebb.core :as m]))

(def prefix
  "How a Codex session is named among the session tools' targets."
  "codex:")

(defn- project [config] (str (or (:project-dir config) (:working-dir config) ".")))

(defn- thread-of [s] (subs (:session-id s) (count prefix)))

(defn- turn [home id] (cxs/last-turn (cxs/rollout-file home id)))

(defn sessions
  "This project's live Codex sessions as the session tools list them:
  `{:session-id \"codex:<thread>\" :label :live? true :kind \"codex\" :status
  :reachable? true :via}` — `:via` `:tmux` for one in this run's tmux, typed
  into, else `:queue`. `home` is Codex's (`$CODEX_HOME`, else `~/.codex`)."
  [config & [{:keys [home]}]]
  (vec (for [{:keys [id title]} (cxs/live (project config) home)]
         {:session-id (str prefix id) :label title :live? true :kind "codex"
          :status (if (:busy? (turn home id)) "busy" "idle") :reachable? true
          :via (if (host/target id) :tmux :queue)})))

(defn codex-target?
  "Whether `s` names a Codex session: `codex:<thread or prefix or name>`."
  [s]
  (str/starts-with? (str s) prefix))

(defn find-session
  "This project's live Codex session `s` names — `codex:<thread>`, a prefix of
  the thread, or its label, with or without `codex:` — or `{:error}`."
  [config s & [opts]]
  (let [q (str/trim (str/replace (str s) (re-pattern (str "^" prefix)) ""))
        all (sessions config opts)
        bare #(subs (:session-id %) (count prefix))
        hits (or (not-empty (filter #(= q (bare %)) all))
                 (not-empty (filter #(= q (:label %)) all))
                 (when (>= (count q) 4) (not-empty (filter #(str/starts-with? (bare %) q) all))))]
    (case (count hits)
      0 {:error (str "no live Codex session " (pr-str q) " in this project — session$list names them")}
      1 (first hits)
      {:error (str (pr-str q) " names " (count hits) " Codex sessions — say which: "
                   (str/join ", " (map :session-id hits)))})))

(defn queue!
  "Run `codex queue` for thread `id` with `line`: `{:exit :out :err}`.
  `CODEX_HOME` is passed when `home` names one."
  [config id line home]
  (apply fx/sh (cond-> {:out :string :err :string :in ""}
                 home (assoc :env (assoc (into {} (System/getenv)) "CODEX_HOME" (str home))))
         (concat (str/split (str/trim (str (:codex-command config "codex"))) #"\s+")
                 ["queue" "--thread" id "--message" line])))

(defn- await-answer
  "A Task: thread `id`'s answer to `line`, read from its rollout: the newest
  turn begun with the line, once it ended — `{:answer}`, `{:aborted true}`, or
  `{:gone true}` when the session ended first. Polls every 500 ms; give it
  `fx/timeout`."
  [home id line before-bytes]
  (fx/poll-until 500 ::pending
                 (fn []
                   (let [f (cxs/rollout-file home id)
                         t (cxs/last-turn f)]
                     (cond
                       (not (cxs/live? home id)) {:gone true}
                       (and f (> (.length ^java.io.File f) before-bytes)
                            (str/includes? (str (:prompt t)) (str/trim line)) (:ended? t))
                       (if (:aborted? t) {:aborted true} {:answer (str (:answer t))})
                       :else ::pending)))))

(defn ask-task
  "A Task: hand Codex session `s` (a `sessions` row) `question` and wait, at
  most `timeout-ms`, for its answer: `{:status :answered :answer}`, or
  `{:status :error :error}` — not idle within the wait, the line not taken,
  the turn aborted, the session gone, or an approval dialog it waits on. A busy
  session is waited for until it is idle. `queue!` (config id line home) runs
  `codex queue`; a test gives its own."
  [config s question timeout-ms & [{:keys [home queue!*]}]]
  (let [id (thread-of s)
        queue!* (or queue!* queue!)
        deadline (+ (System/currentTimeMillis) timeout-ms)
        left #(max 1000 (- deadline (System/currentTimeMillis)))
        err (fn [e] {:status :error :error e :session-id (:session-id s)})]
    (m/sp
     (let [idle (m/? (fx/timeout (fx/poll-until 500 ::busy #(cond (not (cxs/live? home id)) :gone
                                                                   (:busy? (turn home id)) ::busy
                                                                   :else :idle))
                                 (left) ::timeout))]
       (cond
         (= ::timeout idle) (err (str (:session-id s) " did not become idle within " timeout-ms " ms"))
         (= :gone idle) (err (str (:session-id s) " is no longer running"))
         :else
         (let [line (claude-reach/brief-line (project config) question (:session-id config))]
           (if (host/target id)
             ;; in this run's tmux: typed, as a person would
             (let [sent (m/? (host/type-line id line {:home home}))]
               (if (:error sent)
                 (err (:error sent))
                 (let [o (m/? (fx/timeout (host/await-settled id {:home home}) (left) ::timeout))]
                   (cond
                     (= ::timeout o) (err (str (:session-id s) " took the task, and had not answered within " timeout-ms " ms"))
                     (= :gone (:settled o)) (err (str (:session-id s) " ended before it answered"))
                     (= :waiting (:settled o)) (err (str (:session-id s) " waits on an approval dialog: a person answers it in its terminal"))
                     (:aborted? o) (err (str (:session-id s) " stopped the turn before it answered"))
                     :else {:status :answered :answer (str (:answer o)) :session-id (:session-id s)}))))
             ;; anywhere else: queued, Codex's own way to reach a live session
             (let [f (cxs/rollout-file home id)
                   before (if f (.length ^java.io.File f) 0)
                   q (m/? (fx/task-of #(queue!* config id line home)))]
               (if-not (zero? (:exit q))
                 (err (str "codex queue failed: " (str/trim (str (:err q) (:out q)))))
                 (let [o (m/? (fx/timeout (await-answer home id line before) (left) ::timeout))]
                   (cond
                     (= ::timeout o) (err (str (:session-id s) " was queued the task, and had not answered within " timeout-ms
                                               " ms — it may wait on an approval in its own terminal"))
                     (:gone o) (err (str (:session-id s) " ended before it answered"))
                     (:aborted o) (err (str (:session-id s) " stopped the turn before it answered"))
                     :else {:status :answered :answer (:answer o) :session-id (:session-id s)})))))))))))

(defn status
  "How Codex session `s` stands, as `session$status` says it."
  [s & [{:keys [home]}]]
  (let [id (thread-of s)]
    {:status (cond (not (cxs/live? home id)) :ended
                   (:busy? (turn home id)) :busy
                   :else :idle)
     :session-id (:session-id s) :kind "codex" :reachable? true :via (:via s)}))
