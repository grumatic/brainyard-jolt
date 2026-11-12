;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.agent-host
  "The tmux side of running another agent CLI's sessions, shared by Claude
  Code's host (`claude-host`) and Codex's (`codex-host`), an adapter each
  (RFC-049 decision 5, byj-gly.430.3; RFC-033, RFC-039).

  Each session runs in a detached tmux session of its own, named with the
  adapter's prefix and the session's id, opened by `tmux attach` and left with
  `ctrl-b d`. What is here knows nothing of any CLI: the tmux command and the
  environment it runs in, a prefix's sessions, starting and stopping one, its
  screen and keys, and the two loops every driver runs — typing a line and
  confirming the session took it, and polling until a turn is over. What a
  CLI's screen and files say — ready, busy, a dialog, a turn's end — is the
  adapter's, handed in as functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ebb.core :as m]
            [brainyard.effect :as fx]))

(def ^:dynamic *tmux*
  "The tmux command, as argv — a test binds `[\"tmux\" \"-L\" label]`, a server
  of its own, so it never touches the person's."
  ["tmux"])

(def field-sep
  "What parts the fields of a tmux `-F` format. Not a tab: tmux 3.2 and 3.3
  print a tab in a format as `_` (Ubuntu 22.04, Debian bookworm — the Share
  machine image's base; 3.6 keeps it, measured 2026-11-04), so a line split
  on tabs was one field and every session read as none there
  (byj-gly.450). tmux takes no `:` in a session name; a field that may hold
  one, a path, goes last. Share's own reads part theirs the same way
  (`screen.FieldSep`)."
  ":|:")

(defn tmux-format
  "The `-F` format of tmux `fields` (`\"#{session_name}\"` …), parted by
  `field-sep`."
  [& fields]
  (str/join field-sep fields))

(defn tmux-fields
  "A line of `tmux-format`'s output as its `n` fields, the last taking the
  rest of the line."
  [line n]
  (str/split (str line) #":\|:" n))

(defn attach-env
  "The environment every tmux command of this layer runs in: this one, less
  `TMUX`. Run inside tmux itself, an attach refuses to nest; and a start, a
  listing and an attach must all reach ONE server — with `TMUX` set, a start
  went to the server it names and the attach, without it, to the default one,
  which had no such session (byj-gly.306). Nested, the outer tmux has
  `ctrl-b`; `ctrl-b ctrl-b d` reaches the inner one."
  []
  (dissoc (into {} (System/getenv)) "TMUX"))

(defn taking-prefix
  "The prefix a tmux takes, as `#{prefix}` shows it, or nil when it takes
  none: \"None\", as a Share machine's own session sets it, so that what is
  typed on its page reaches the program inside."
  [shown]
  (let [p (str/trim (str shown))]
    (when-not (or (str/blank? p) (= "None" p)) p)))

(defn outer-prefix
  "The prefix of the tmux session this process runs inside — `C-b` unless
  remapped, the session's own where it has one — or nil outside one, or
  where that session takes none (`taking-prefix`). Nested, that prefix never reaches a session attached
  from here: the outer tmux takes it, so `ctrl-b d` detaches the OUTER
  session, the person's own; pressed twice it is sent on, and `ctrl-b
  ctrl-b d` detaches the inner one (byj-on2). Read with the outer `TMUX`
  still set, on purpose."
  []
  (when (System/getenv "TMUX")
    (let [{:keys [exit out]} (try (fx/sh {:out :string :err :string} "tmux" "display-message" "-p" "#{prefix}")
                                  (catch Throwable _ {:exit 1}))]
      (when (zero? exit) (taking-prefix out)))))

(defn detach-hint
  "What the inner session's status line says when it is attached from inside
  another tmux whose prefix is `prefix`."
  [prefix]
  (str "detach: " prefix " " prefix " d  (nested — " prefix " d detaches your outer tmux)"))

(defn hint-argv
  "The tmux command that puts `detach-hint` on session `name`'s status line:
  a session option, so the person's own sessions are untouched."
  [name prefix]
  (into *tmux* ["set-option" "-t" name "status-right-length" "90" ";"
                "set-option" "-t" name "status-right" (detach-hint prefix)]))

(defn tmux?
  "Is tmux on the PATH?"
  []
  (try (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c" "command -v tmux")))
       (catch Throwable _ false)))

(defn sh
  "A tmux command (or any), run in `attach-env`."
  [& argv]
  (apply fx/sh {:out :string :err :string :env (attach-env)} argv))

(defn canonical [p] (try (.getCanonicalPath (io/file (str p))) (catch Throwable _ (str p))))

(defn running
  "The ids of the tmux sessions named `<prefix><id>` running now — none when
  tmux is absent or has no server."
  [prefix]
  (try
    (let [{:keys [exit out]} (apply sh (conj *tmux* "list-sessions" "-F" "#{session_name}"))]
      (if (zero? exit)
        (into #{} (keep #(when (str/starts-with? % prefix) (subs % (count prefix)))) (str/split-lines (str out)))
        #{}))
    (catch Throwable _ #{})))

(defn running-in
  "The running ones (`running`) started in `project-dir` or under it — its
  worktrees are under `.brainyard/worktrees` — by the directory tmux started
  each in (`#{session_path}`). Another project's running sessions are no
  business of this one's page (byj-gly.310)."
  [prefix project-dir]
  (try
    (let [root (canonical project-dir)
          {:keys [exit out]} (apply sh (conj *tmux* "list-sessions" "-F" (tmux-format "#{session_name}" "#{session_path}")))]
      (if (zero? exit)
        (into #{} (keep (fn [l]
                          (let [[n p] (tmux-fields l 2)
                                p (canonical p)]
                            (when (and (str/starts-with? (str n) prefix)
                                       (or (= p root) (str/starts-with? p (str root "/"))))
                              (subs n (count prefix))))))
              (str/split-lines (str out)))
        #{}))
    (catch Throwable _ #{})))

(defn new-session-argv
  "The tmux command that starts a detached session `name` in `dir`, `cols` ×
  `rows` (200 × 50), running what is appended to it."
  [name dir & [{:keys [cols rows]}]]
  (conj *tmux* "new-session" "-d" "-s" name "-x" (str (or cols 200)) "-y" (str (or rows 50)) "-c" (str dir)))

(defn kill!
  "Close tmux session `name`: `{:stopped name}`, or `{:error}`."
  [name]
  (let [r (apply sh (conj *tmux* "kill-session" "-t" name))]
    (if (zero? (:exit r)) {:stopped name} {:error (str "could not stop it: " (str/trim (str (:err r) (:out r))))})))

(defn rename!
  "Rename tmux session `from` to `to`: true when it was."
  [from to]
  (zero? (:exit (apply sh (conj *tmux* "rename-session" "-t" from to)))))

(defn pane-exists?
  "Does tmux still have pane (or session) `target`?"
  [target]
  (let [r (apply sh (conj *tmux* "display-message" "-p" "-t" target "#{pane_id}"))]
    (and (zero? (:exit r)) (not (str/blank? (str (:out r)))))))

(defn capture
  "What `target`'s screen shows now, or nil when tmux cannot reach it."
  [target]
  (let [r (apply sh (conj *tmux* "capture-pane" "-p" "-t" target))]
    (when (zero? (:exit r)) (str (:out r)))))

(defn send-keys!
  "Send `ks` to `target`."
  [target & ks]
  (apply sh (into (conj *tmux* "send-keys" "-t" target) ks)))

(defn sleep [ms] (m/sleep ms))

;; --- opening one from the agent page ------------------------------------------------------

(defn run-plan!
  "Do what a host's `plan` decided — `{:refuse why}` or `{:start argv? :attach
  argv :hint argv? :foreground? bool}`: start the tmux session, then hand the
  terminal to the attach (or to the CLI itself, without tmux) with `hand!`
  (fn [argv env]), which returns when it is left. Returns what to say on the
  page, or nil."
  [{:keys [refuse start attach hint foreground?]} hand!]
  (cond
    refuse refuse
    :else
    ;; spread, never a vector (provenance P-1)
    (let [started (when start (apply sh start))]
      (if (and started (not (zero? (:exit started))))
        (str "could not start it: " (str/trim (str (:err started) (:out started))))
        (do (when hint (apply sh hint))
            (hand! attach (when-not foreground? (attach-env)))
            nil)))))

;; --- the two loops ------------------------------------------------------------------------

(defn type-and-confirm
  "A Task: type a line and see the session take it. `type!` sends the line's
  keys, then after `pause-ms` `enter!` presses Enter; `started` (a Task of the
  session's state for up to `wait-ms`: `:started`, `:gone`, or nil) is waited
  on, and when it says nothing, `taken?` (a 0-ary fn) is asked whether the
  line arrived anyway — a turn too short for `started` to catch. Enter is
  pressed again, up to `max-enters` times. `{:sent true :enters n}`, with
  `:gone? true` when the session ended, or `{:error}`."
  [{:keys [type! enter! started taken? pause-ms max-enters what]}]
  (m/sp
   (m/? (fx/task-of type!))
   (m/? (sleep (or pause-ms 300)))
   (loop [n 1]
     (m/? (fx/task-of enter!))
     (let [s (m/? (started))]
       (cond (= :gone s) {:sent true :enters n :gone? true}
             s {:sent true :enters n}
             ;; its start was missed, but the line arrived: another Enter
             ;; would be pressed into a session that took it
             (and taken? (m/? (fx/task-of taken?))) {:sent true :enters n}
             (< n (or max-enters 3)) (recur (inc n))
             :else {:error (str (or what "the session") " did not start a turn after " n " Enters")})))))

(defn await-settled
  "A Task: the observation once a turn is over — `:settled :done` once
  `settled?` has held for `settle-ms` (1500), `:waiting` at once when
  `waiting?` says a person is asked, `:gone` when `(:running? obs)` is false.
  `observe` (0-ary) is polled every `poll-ms` (200), each observation handed to
  `on-observe` when given. Never ends otherwise: give it `fx/timeout`."
  [observe {:keys [settled? waiting? poll-ms settle-ms on-observe]}]
  (let [since (atom nil)
        settle-ms (or settle-ms 1500)]
    (fx/poll-until (or poll-ms 200) ::pending
                   (fn []
                     (let [o (observe)
                           now (System/currentTimeMillis)
                           _ (when on-observe (try (on-observe o) (catch Throwable _ nil)))]
                       (cond
                         (not (:running? o)) (assoc o :settled :gone)
                         (waiting? o) (assoc o :settled :waiting)
                         (not (settled? o)) (do (reset! since nil) ::pending)
                         (nil? @since) (do (reset! since now) ::pending)
                         (>= (- now @since) settle-ms) (assoc o :settled :done)
                         :else ::pending))))))
