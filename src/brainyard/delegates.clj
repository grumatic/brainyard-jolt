;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.delegates
  "A project's delegations — the Claude Code and Codex sessions its byj
  sessions handed work to (agent$claude, agent$codex: RFC-039, RFC-049) — for a
  program that reaches byj as a process (RFC-070): `byj session delegates` and
  `byj session attach` (byj-oer). The workbench shows each under the byj session
  that delegated it and opens it in a terminal.

  Read from what the delegations leave: their records
  (`~/.brainyard/projects/<slug>/claude-agents/`, `~/.brainyard/projects/<slug>/codex-agents/`) and the sessions
  running on byj's tmux server (`agent-host/*tmux*`, byj-6e6). Attaching runs
  nothing here but a saved session started again, as its delegation started
  it (`claude-agent/reopen`, `codex-agent/reopen`); the program runs the attach
  command it is given, in a terminal of its own."
  (:require [clojure.java.io :as io]
            [brainyard.agent-host :as ah]
            [brainyard.claude-agent :as claude-agent]
            [brainyard.claude-host :as claude-host]
            [brainyard.codex-agent :as codex-agent]
            [brainyard.codex-host :as codex-host]))

(def fields
  "What a row keeps of a delegation's record."
  [:parent-session :instance-id :task :state :verdict :round :bead :branch :how :permission-mode
   :sandbox :publish :published :report :cost-usd :started-at :updated-at :stopped-why])

(defn attach-argv
  "The tmux command that attaches a terminal to session `tmux-name` on byj's
  server: `=` names it exactly, as tmux would otherwise take a prefix of it."
  [tmux-name]
  (conj ah/*tmux* "attach-session" "-t" (str "=" tmux-name)))

(defn- row [agent tmux-name running r]
  (let [id (str (:session-id r))
        dir (:dir r)
        t (tmux-name id)]
    (merge (into {} (filter (comp some? val)) (select-keys r fields))
           {:agent agent :id id :dir dir
            :dir-exists? (boolean (and dir (.isDirectory (io/file (str dir)))))
            :live? (contains? running id) :tmux t :attach (attach-argv t)})))

(defn rows
  "Every delegation of the project, newest first: `{:agent \"claude\"|\"codex\"
  :id :parent-session :dir :dir-exists? :live? :tmux :attach …}` and the rest of
  its record (`fields`). `:live?` — running on byj's tmux server, attached with
  `:attach`; else saved, to be started again by `attach`."
  [config]
  (let [cr (set (claude-host/running))
        xr (set (codex-host/running))]
    (->> (concat (map #(row "claude" claude-host/tmux-name cr %) (claude-agent/records config))
                 (map #(row "codex" codex-host/tmux-name xr %) (codex-agent/records config)))
         (sort-by #(- (or (:updated-at %) 0)))
         vec)))

(defn forget!
  "Delegation `id` forgotten (byj-wb6): ended on byj's tmux server if it runs
  there — Claude Code or Codex exits, its session saved where it keeps it —
  and its record removed, so no client lists it again. Claude Code's or
  Codex's own session, and a branch or worktree it made, are left.
  `{:agent :id :stopped?}`, or `{:error}`."
  [config id]
  (let [id (str id)
        [agent running stop! delete!]
        (cond (claude-agent/read-record config id) ["claude" claude-host/running claude-host/stop! claude-agent/delete-record!]
              (codex-agent/read-record config id) ["codex" codex-host/running codex-host/stop! codex-agent/delete-record!])]
    (if-not agent
      {:error (str "no delegated session " id " in this project — `byj session delegates` lists them")}
      (let [live? (contains? (set (running)) id)
            r (when live? (stop! id))]
        (if (:error r)
          (assoc r :agent agent :id id)
          (do (delete! config id)
              {:agent agent :id id :stopped? live?}))))))

(defn forget-session!
  "Every delegation byj session `session-id` made, forgotten (`forget!`): what
  deleting the session does (byj-wb6), so none is left orphaned. The rows
  forgotten, each `{:agent :id :stopped?}` or `{:error}`."
  [config session-id]
  (->> (rows config)
       (filter #(= (str session-id) (str (:parent-session %))))
       (mapv #(forget! config (:id %)))))

(defn attach
  "Delegation `id` running on byj's tmux server and the command that attaches
  to it: `{:agent :id :tmux :attach :started?}` — `:started?` when it was saved
  and has just been started again — or `{:error}`."
  [config id]
  (let [id (str id)
        [agent reopen] (cond (claude-agent/read-record config id) ["claude" claude-agent/reopen]
                             (codex-agent/read-record config id) ["codex" codex-agent/reopen])]
    (if-not agent
      {:error (str "no delegated session " id " in this project — `byj session delegates` lists them")}
      (let [r (reopen config id)]
        (if (:error r)
          (assoc r :agent agent :id id)
          {:agent agent :id id :tmux (:tmux r) :started? (:started? r) :attach (attach-argv (:tmux r))})))))
