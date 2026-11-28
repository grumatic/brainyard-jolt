;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.claude-reach
  "A live Claude Code session as a target of the session tools (byj-gly.429,
  RFC-031): `session$list` lists this project's, `session$send` and
  `session$ask` hand one a task, `session$status` says how it stands.

  A byj session is reached through its session channel. A Claude Code session
  is reached through its inbox when it has one (byj-bzo): Claude Code's
  cross-session messaging (2.1.224+) gives each interactive session a socket
  another process of the person's writes to, and an idle session starts a
  turn with what it is sent — the whole brief, however long — told to Claude
  as a teammate's request, never as the person's approval of anything
  (`claude-host/send-message`, measured by `dev/claude-peer-spike.sh`). A
  session with none is reached the way a person reaches it, by typing into
  its terminal — its tmux pane (`claude-host/target`), this run's
  `byj-cc-<id>` or the pane its live record names: a brief of one short line
  is typed as it is; a longer one is written to a file in the session's
  project (`.brainyard/handovers/`, the one place it reads without asking —
  the temporary directory raised a permission dialog, measured) and a line
  typed that points at it, as agent$claude guides its sessions (RFC-039: a
  paste is taken for data, a long one arrives cut). Either way the answer is
  the turn's reply, read from the session's transcript once the turn is over
  (`claude-host/await-settled`).

  A session with no inbox, outside tmux, in a terminal of its own, cannot be
  reached: nothing can type into it. It is listed, unreachable, with why, and
  a send to it is refused with what to do instead.

  Mechanism only (RFC-001): when to hand a session work, and what to say, is
  the agent's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.tool-words :as tool-words]
            [brainyard.claude-host :as host]
            [brainyard.claude-sessions :as cs]
            [brainyard.effect :as fx]
            [ebb.core :as m]))

(def prefix
  "How a Claude Code session is named among the session tools' targets."
  "claude:")

(def typed-chars
  "How long a brief may be and still be typed as it is (claude-agent's)."
  300)

(defn- project [config] (str (or (:project-dir config) (:working-dir config) ".")))

(defn sessions
  "This project's live Claude Code sessions as the session tools list them:
  `{:session-id \"claude:<id>\" :label :live? true :kind \"claude-code\"
  :status :reachable? :why}`, `:why` saying what keeps an unreachable one out
  of reach. `home` is Claude Code's (`~/.claude` unless given)."
  [config & [{:keys [home]}]]
  (vec (for [{:keys [id title status]} (cs/live (project config) home)
             :let [box (host/inbox id home)
                   t (when-not box (host/target id home))]]
         (cond-> {:session-id (str prefix id) :label title :live? true :kind "claude-code"
                  :status status :reachable? (boolean (or box t))}
           box (assoc :via :inbox)
           t (assoc :via :tmux)
           (not (or box t)) (assoc :why (tool-words/says "claude-reach/sessions"))))))

(defn claude-target?
  "Whether `s` names a Claude Code session: `claude:<id or prefix or name>`."
  [s]
  (str/starts-with? (str s) prefix))

(defn find-session
  "This project's live Claude Code session `s` names — `claude:<id>`, a prefix
  of the id, or its label, with or without `claude:` — or `{:error}`."
  [config s & [opts]]
  (let [q (str/trim (str/replace (str s) (re-pattern (str "^" prefix)) ""))
        all (sessions config opts)
        bare #(subs (:session-id %) (count prefix))
        hits (or (not-empty (filter #(= q (bare %)) all))
                 (not-empty (filter #(= q (:label %)) all))
                 (when (>= (count q) 4) (not-empty (filter #(str/starts-with? (bare %) q) all))))]
    (case (count hits)
      0 {:error (str "no live Claude Code session " (pr-str q) " in this project — session$list names them")}
      1 (first hits)
      {:error (str (pr-str q) " names " (count hits) " Claude Code sessions — say which: "
                   (str/join ", " (map :session-id hits)))})))

(defn- handover-file
  "Where a brief too long to type is written: the session's own project, so it
  may read it without being given another directory."
  [dir text]
  (let [f (io/file dir ".brainyard" "handovers" (str "handover-" (System/currentTimeMillis) ".md"))]
    (io/make-parents f)
    (spit f text)
    (.getCanonicalPath f)))

(defn inbox-message
  "What session `from` sends a Claude Code session's inbox for `question`:
  the whole of it, under a line saying who hands it on."
  [question from]
  (str "[byj] " (if from (str "byj session " from) "another session") " of this project hands you this task:\n\n"
       (str/trim (str question))))

(defn brief-line
  "The line typed to the session for `question`: the question itself when it
  is one short line, else a line pointing at the file it is written to in
  `dir`."
  [dir question from]
  (let [q (str/trim (str question))]
    (if (or (> (count q) typed-chars) (str/includes? q "\n"))
      (str "Read " (handover-file dir q) " and do the task in it — it was handed to you from "
           (if from (str "byj session " from) "another session") " of this project.")
      q)))

(defn ask-task
  "A Task: hand Claude Code session `s` (a `sessions` row) `question` and wait,
  at most `timeout-ms`, for its answer: `{:status :answered :answer}`, or
  `{:status :error :error}` — unreachable, not idle within the wait, the line
  not taken, the session gone, or a permission dialog it waits on. A busy
  session is waited for until it is idle: a line typed into a running turn is
  queued behind it, where a turn's end could not be told from the next."
  [config s question timeout-ms & [{:keys [home]}]]
  (let [id (subs (:session-id s) (count prefix))
        deadline (+ (System/currentTimeMillis) timeout-ms)
        left #(max 1000 (- deadline (System/currentTimeMillis)))
        err (fn [e] {:status :error :error e :session-id (:session-id s)})]
    (m/sp
     (if-not (:reachable? s)
       (err (tool-words/says "claude-reach/ask-task" {:session-id (:session-id s) :why (:why s)}))
       (let [idle (m/? (fx/timeout (fx/poll-until 500 ::busy #(let [st (:status (host/record id home))]
                                                                (cond (= "idle" st) :idle
                                                                      (not (host/running? id home)) :gone
                                                                      :else ::busy)))
                                   (left) ::timeout))]
         (cond
           (= ::timeout idle) (err (str (:session-id s) " did not become idle within " timeout-ms " ms"))
           (= :gone idle) (err (str (:session-id s) " is no longer running"))
           :else
           (let [dir (or (:cwd (host/record id home)) (project config))
                 sent (m/? (if (= :inbox (:via s))
                             (host/send-message id (inbox-message question (:session-id config)) {:home home})
                             (host/type-line id (brief-line dir question (:session-id config)) {:home home})))]
             (if (:error sent)
               (err (:error sent))
               (let [o (m/? (fx/timeout (host/await-settled id {:home home :dir dir}) (left) ::timeout))]
                 (cond
                   (= ::timeout o) (err (str (:session-id s) " took the task, and had not answered within " timeout-ms " ms"))
                   (= :gone (:settled o)) (err (str (:session-id s) " ended before it answered"))
                   (= :waiting (:settled o)) (err (str (:session-id s) " waits on a permission dialog: a person answers it in its terminal"))
                   :else {:status :answered :answer (str (:answer o)) :session-id (:session-id s)}))))))))))

(defn status
  "How Claude Code session `s` stands, as `session$status` says it."
  [s & [{:keys [home]}]]
  (let [id (subs (:session-id s) (count prefix))]
    {:status (keyword (or (:status (host/record id home)) "unknown"))
     :session-id (:session-id s) :kind "claude-code" :reachable? (:reachable? s)}))
