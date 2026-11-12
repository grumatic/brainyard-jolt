;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tool-hook
  "Another agent's tool calls through byj's gates (RFC-012 \"Another agent's
  calls\", byj-ahf.24): Claude Code and Codex run a `PreToolUse` hook command
  before every tool call, and `byj hook pretooluse` is that command. The call
  is mapped onto byj's tool (Bash → bash, Read → read-file, Write →
  write-file, Edit → update-file, Grep → grep, Glob/LS → list-dir; Codex's
  `apply_patch` → update-file of every file it names) and put through the
  gates a byj turn meets (`hookbox/builtin-hooks` under `brainyard.permission`:
  tool deny, bash command, path, tool permission) under the project's config.
  A gate's refusal by rule is `deny`; a gate that would ask a person is `ask`
  — Claude Code asks; no gate's objection is no opinion, and the agent's own
  rules decide. An unmapped tool (an MCP tool, a subagent) gets no opinion,
  and a hook that fails has none: it must not stop a person's work.

  Every decision is logged, the agent and session with it, a line in
  `<dir>/<day>.edn` (`~/.brainyard/permissions` unless `--log DIR`), which a
  program that reports on agents' permissions reads. It applies byj's own
  policy, so it is byj's command (decided on byj-ahf.24)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.codex-host :as codex-host]
            [brainyard.hookbox :as hookbox]
            [brainyard.json :as json]))

(defn patch-paths
  "The files an `apply_patch` patch adds, updates, deletes or moves to, in
  the order it names them. An earlier version took only the first group
  of a two-group match, so a `*** Move to:` destination came back nil and was
  never put through the gates."
  [patch]
  (vec (distinct (keep (fn [[_ named moved]] (or named moved))
                       (re-seq #"(?m)^\*\*\* (?:Add|Update|Delete) File: (.+?)\s*$|^\*\*\* Move to: (.+?)\s*$"
                               (str patch))))))

(def tool-map
  "Claude Code tool → byj tool, and how its input becomes the tool's args —
  a vector of them for a call that touches several files. Codex's hooks name
  its shell `Bash`, as Claude Code does."
  {"Bash" ["bash" (fn [i] {:command (:command i)})]
   "Read" ["read-file" (fn [i] {:path (:file_path i)})]
   "Write" ["write-file" (fn [i] {:path (:file_path i) :content (:content i)})]
   "Edit" ["update-file" (fn [i] {:path (:file_path i)})]
   "MultiEdit" ["update-file" (fn [i] {:path (:file_path i)})]
   "NotebookEdit" ["update-file" (fn [i] {:path (:notebook_path i)})]
   "Grep" ["grep" (fn [i] {:path (or (:path i) ".") :pattern (:pattern i)})]
   "Glob" ["list-dir" (fn [i] {:path (or (:path i) ".")})]
   "LS" ["list-dir" (fn [i] {:path (:path i)})]
   ;; Codex's edit (measured, 0.163): the whole patch as `command`, a file a
   ;; header — every file it touches is put through the gates
   "apply_patch" ["update-file" (fn [i] (vec (for [p (patch-paths (:command i))] {:path p})))]})

(defn- gates [] (sort-by (comp - :priority) (filter #(= "brainyard.permission" (some-> (:impl %) namespace)) hookbox/builtin-hooks)))

(defn decide
  "What byj's gates say about a Claude Code or Codex tool call (a `PreToolUse`
  payload: `:tool_name :tool_input :cwd`), under `config`: `{:decision
  :deny|:ask|nil :reason :tool :gate}` — nil when no gate objects, or the
  tool has no byj counterpart. A call of several files is the first
  objection to any of them."
  [config {:keys [tool_name tool_input cwd]}]
  (if-let [[tool args-of] (get tool-map tool_name)]
    (let [asked (atom nil)
          ctx (agent/make-ctx {:config (assoc config :working-dir (or cwd (:working-dir config)))
                               ;; a gate that would ask a person: the agent asks instead
                               :permission-fn (fn [req] (reset! asked req) {:denied true :reason "ask the person"})})
          args (args-of tool_input)
          ;; `some`, not a lazy `for`: a gate that would ask records it, so
          ;; none may run past the first that objects
          hit (some (fn [[args g]] (when-let [r ((:handler g) {:tool-name tool :args args} ctx)] [(:id g) r]))
                    (for [args (if (map? args) [args] args), g (gates)] [args g]))]
      (if-let [[gate r] hit]
        (if-let [req @asked]
          {:decision :ask :tool tool :gate gate
           :reason (str "its gates would ask a person about this " (name (or (:type req) :call))
                        (when-let [x (or (:command req) (:path req) (some-> (:paths req) seq vec) (:display req))]
                          (str ": " x)))}
          {:decision :deny :reason (str (:reason r)) :tool tool :gate gate})
        {:decision nil :tool tool}))
    {:decision nil :tool nil}))

(defn hook-output
  "The JSON the agent reads back from a `PreToolUse` hook, or nil for no
  opinion (the hook prints nothing and the agent's own rules apply)."
  [{:keys [decision reason]}]
  (when decision
    (json/write-str {:hookSpecificOutput {:hookEventName "PreToolUse"
                                          :permissionDecision (name decision)
                                          :permissionDecisionReason (str "brainyard: " reason)}})))

(defn hook-agent
  "Which agent sent a hook payload: Codex's carries the `turn_id` and `model`
  Claude Code's does not (measured, Codex 0.163)."
  [payload]
  (if (and (:turn_id payload) (:model payload)) :codex :claude-code))

(defn log-dir
  "Where decisions are logged: `dir`, else `~/.brainyard/permissions` under
  `home`."
  [dir home]
  (io/file (or (not-empty (str dir)) (str (io/file (str home) ".brainyard" "permissions")))))

(defn- today [] (subs (str (java.time.Instant/now)) 0 10))

(defn hook!
  "Decide a `PreToolUse` payload — Claude Code's or Codex's, which share it
  (RFC-049 decision 6) — log the decision in `dir`, and return what to
  print, or nil."
  [config payload dir]
  (let [d (try (decide config payload)
               (catch Throwable e {:decision nil :error (ex-message e)}))
        f (io/file dir (str (today) ".edn"))]
    (try (.mkdirs (.getParentFile f))
         (spit f (str (pr-str (cond-> {:agent (hook-agent payload) :session (:session_id payload) :tool (:tool_name payload)
                                       :decision (or (:decision d) :no-opinion) :at (System/currentTimeMillis)
                                       :project (:cwd payload)}
                                (:reason d) (assoc :reason (:reason d))
                                (:gate d) (assoc :gate (:gate d))
                                (:error d) (assoc :error (:error d))))
                       "\n")
               :append true)
         ;; a log that cannot be written must not stop the call either
         (catch Throwable _ nil))
    (hook-output d)))

(defn hook-command
  "The command line that runs the hook: `self` and `hook pretooluse`, with
  the log's directory when one is named."
  [self dir]
  (str/join " " (concat [self "hook" "pretooluse"] (when (seq dir) ["--log" dir]))))

(defn settings
  "The Claude Code settings that install the hook: a `PreToolUse` hook
  running `command` before every tool."
  [command]
  {:hooks {:PreToolUse [{:matcher "*" :hooks [{:type "command" :command command}]}]}})

(defn codex-line
  "`codex` with the session flags that give it the hook, trusted for that
  session alone, quoted for a shell (RFC-049 decision 6)."
  [command]
  (let [q #(str "'" (str/replace (str %) "'" "'\\''") "'")]
    (str/join " " (cons "codex" (map q (codex-host/hook-flags {:PreToolUse command}))))))
