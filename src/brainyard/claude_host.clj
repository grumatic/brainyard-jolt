;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.claude-host
  "Running this project's Claude Code sessions from the agent page
  (byj-gly.287.3, RFC-033): each in a detached tmux session of its own, named
  for its Claude Code session id (`byj-cc-<uuid>`), opened by handing the
  terminal to `tmux attach` and left with tmux's own `ctrl-b d` — Claude Code
  keeps working, and is listed live, after byj quits too. Without tmux it
  runs in the foreground until it exits, as `$EDITOR` does.

  A new session is given its id (`claude --session-id <uuid>`), so its tmux
  session is named for it from the start and the agent page can tell it from
  one running in another terminal.

  `plan` decides what Enter on a row does and runs nothing; `run-plan!` does it.

  agent$claude (RFC-039, byj-gly.290.2) drives such a session as well, with
  what is below `start!`: start one with a brief, get it past the folder-trust
  dialog, type a line to it, see where its turn stands, and wait until the turn
  is over. Mechanism only — what to say to it, and when, is the agent's loop.
  How each works is what the spike measured (RFC-039 \"What was measured\").

  The tmux side — the command, its environment, starting, stopping, the
  screen, keys, and the type-and-confirm and await-settled loops — is
  `agent-host`'s, shared with Codex's host (RFC-049, byj-gly.430.3); what is
  here is Claude Code's adapter to it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ebb.core :as m]
            [brainyard.agent-host :as ah]
            [brainyard.claude-sessions :as cs]
            [brainyard.effect :as fx]
            [brainyard.json :as json]))

(def prefix
  "What this run's tmux sessions for Claude Code are named with."
  "byj-cc-")

(defn tmux-name [id] (str prefix id))

(defn attach-env "`agent-host/attach-env`." [] (ah/attach-env))
(defn outer-prefix "`agent-host/outer-prefix`." [] (ah/outer-prefix))
(defn detach-hint "`agent-host/detach-hint`." [prefix] (ah/detach-hint prefix))
(defn hint-argv "`agent-host/hint-argv`." [name prefix] (ah/hint-argv name prefix))
(defn tmux? "`agent-host/tmux?`." [] (ah/tmux?))

(defn running
  "The Claude Code session ids running in this run's tmux sessions now — none
  when tmux is absent or has no server."
  []
  (ah/running prefix))

(defn running-in
  "The running ones (`running`) started in `project-dir` or under it, by the
  directory tmux started each in (`agent-host/running-in`). Another project's
  running sessions are no business of this one's page (byj-gly.310)."
  [project-dir]
  (ah/running-in prefix project-dir))

(defn with-running
  "`sessions` (`claude-sessions/sessions`) with every session in `running`
  listed live — before Claude Code has written its own record of it, as it has
  not while it starts, it would otherwise be nowhere, or saved. `running` is
  this project's (`running-in`), not every one tmux has (byj-gly.310)."
  [{:keys [live saved] :as sessions} running]
  (let [live-ids (set (map :id live))
        extra (remove live-ids (sort running))
        titles (into {} (map (juxt :id :title)) saved)]
    (assoc sessions
           :live (into (vec live) (for [id extra] {:id id :title (titles id) :status "running"}))
           :saved (vec (remove #(contains? (set extra) (:id %)) saved)))))

(defn- claude-argv [claude flag id]
  (conj (vec (str/split (str/trim (or claude "claude")) #"\s+")) flag id))

(defn plan
  "What Enter on a Claude Code `row` (`tui/claude-session-rows`) does:
  `{:start argv? :attach argv :id :hint argv?}`, or `{:refuse why}`. `tmux?`
  says whether tmux is there, `running` is `running`'s set, `new-id` makes a
  new session's id, and `nested-prefix` is the prefix of the tmux this runs
  inside (`outer-prefix`), nil outside one. With tmux a session is started
  when it is not running here, then attached — nested, with `hint-argv` on
  its status line first, since nothing else on screen says how to leave it;
  without, Claude Code itself is handed the terminal."
  [{:keys [state session-id]} {:keys [tmux? running project-dir claude new-id nested-prefix]}]
  (let [new? (= :claude-new state)
        id (if new? (new-id) session-id)
        flag (if new? "--session-id" "--resume")
        start (when-not (contains? running id)
                (into (conj ah/*tmux* "new-session" "-d" "-s" (tmux-name id) "-c" (str project-dir))
                      (claude-argv claude flag id)))]
    (cond
      (and (= :claude-live state) (not (contains? running id)))
      {:refuse "it runs in another terminal — open it there; only a session started from here can be opened here"}

      tmux? (cond-> {:id id :start start :attach (conj ah/*tmux* "attach-session" "-t" (tmux-name id))}
              nested-prefix (assoc :hint (hint-argv (tmux-name id) nested-prefix)))

      :else {:id id :attach (claude-argv claude flag id) :foreground? true})))

(defn run-plan!
  "Do what `plan` decided (`agent-host/run-plan!`): start the tmux session,
  then hand the terminal to the attach (or to Claude Code, without tmux) with
  `hand!` (fn [argv env]). Returns what to say on the page, or nil."
  [plan hand!]
  (ah/run-plan! plan hand!))

;; --- driving a session (agent$claude, RFC-039) -------------------------------------------

(defn- sh [& argv] (apply ah/sh argv))

(defn inherited
  "The Claude Code variables this process carries when it runs inside a Claude
  Code session itself (`CLAUDECODE`, `CLAUDE_CODE_SESSION_ID`, its messaging
  socket …): a child given them takes itself for that session's child, so each
  is unset for it (13 of them, measured)."
  []
  (vec (sort (filter #(or (str/starts-with? % "CLAUDE") (= % "CLAUDECODE")) (keys (System/getenv))))))

(defn start-argv
  "The tmux command that starts session `id` in `dir`: Claude Code — `claude`,
  split on spaces as `plan` does — given its id (`--session-id`, or `--resume`
  with `resume?`), `model`, `permission-mode` and `settings` when given, then
  `args` as they are, then `--` and `prompt`, the brief, as its last argument: a prompt
  given there arrives whole and as the person's own words, where a paste does
  not (RFC-039). `unset` names the variables the child must not inherit."
  [{:keys [id dir claude resume? model permission-mode settings args prompt]} unset]
  (-> (into (ah/new-session-argv (tmux-name id) dir)
            ;; a pane takes the tmux server's environment, not ours: the
            ;; session is told not to prompt here (byj-gly.295)
            (concat ["env"] (mapcat #(vector "-u" %) unset)
                    (for [[k v] (sort (fx/noninteractive {}))] (str k "=" v))))
      (into (str/split (str/trim (or claude "claude")) #"\s+"))
      (into [(if resume? "--resume" "--session-id") id])
      (cond-> model (into ["--model" model])
              permission-mode (into ["--permission-mode" permission-mode])
              settings (into ["--settings" settings]))
      (into args)
      ;; `--` first: an option that takes several values (`--add-dir`) would
      ;; otherwise take the brief for one of them — it did, live (byj-gly.290.6)
      (cond-> (not (str/blank? prompt)) (into ["--" prompt]))))

(defn start!
  "Start session `opts` (`start-argv`) in a detached tmux session of its own:
  `{:id}`, or `{:error}` — one by that name already running is an error, not
  a second start."
  [{:keys [id] :as opts}]
  (if (contains? (running) id)
    {:error (str "session " id " is already running")}
    (let [r (apply sh (start-argv opts (inherited)))]
      (if (zero? (:exit r)) {:id id} {:error (str "could not start it: " (str/trim (str (:err r) (:out r))))}))))

(defn stop!
  "Close session `id`'s tmux session: Claude Code is hung up on and exits, and
  its session is saved, to be resumed. agent$claude stops one to start it again
  with other settings — the rights to publish (byj-gly.307) — since Claude Code
  reads its `--settings` when it starts. `{:stopped id}`, or `{:error}`."
  [id]
  (let [r (ah/kill! (tmux-name id))]
    (if (:stopped r) {:stopped id} r)))

(declare record-of)

(defn target
  "The tmux target that reaches session `id`'s screen, or nil when nothing
  does: this run's tmux session for it (`byj-cc-<id>`), else the pane Claude
  Code's live record names — `\"tmux\": \"work:@8.%8\"` for a session started
  in any tmux — while tmux still has that pane (byj-gly.429). A session
  outside tmux, in a terminal of its own, has none: nothing can type into it."
  [id & [home]]
  (if (contains? (running) id)
    (tmux-name id)
    (when-let [t (some-> (record-of id home) :tmux str not-empty)]
      (let [pane (or (second (re-find #"\.(%\d+)$" t)) t)]
        (when (ah/pane-exists? pane) pane)))))

(defn pane
  "What session `id`'s screen shows now, or nil when nothing reaches it
  (`target`)."
  [id & [home]]
  (some-> (target id home) ah/capture))

(defn- keys!
  "Send `ks` to session `id`'s screen; `id` may be `[id home]`, for a session
  found under a `home` of its own."
  [id & ks]
  (let [[id home] (if (vector? id) id [id nil])]
    (if-let [t (target id home)]
      (apply ah/send-keys! t ks)
      {:exit 1 :err (str "nothing reaches session " id)})))

(defn trust-dialog?
  "Does `screen` show Claude Code's folder-trust dialog?"
  [screen]
  (str/includes? (str screen) "Yes, I trust this folder"))

(defn cursor-on-yes?
  "Is the dialog's cursor on \"Yes\"? It opens on \"No, exit\", where Enter
  ends Claude Code."
  [screen]
  (boolean (re-find #"❯\s*(\d\.\s*)?Yes, I trust" (str screen))))

(declare current-turn)

(defn- record-of
  "The live record whose `sessionId` is `id`, or nil."
  [id home]
  (some (fn [^java.io.File f]
          (when (str/ends-with? (.getName f) ".json")
            (let [r (try (json/read-str (slurp f)) (catch Throwable _ nil))]
              (when (and (map? r) (= id (:sessionId r))) r))))
        (or (.listFiles (io/file (cs/claude-home home) "sessions")) [])))

(defn record
  "Claude Code's live record of session `id` (`sessions/<pid>.json` under
  `home`, `~/.claude` unless given), or nil — as there is none before it is
  past the trust dialog. A session Claude Code continued under a new id
  (`current-turn`) is run on by another process — a `claude bg-spare` —
  whose record is under the new id, while the first record stops changing:
  live, `byj-cc-562b6368`'s still said idle from 11 days before, and a typed
  line read as never starting a turn. So the record is the newest id's
  when it has one (byj-gly.290.10)."
  [id & [home]]
  (let [r (record-of id home)]
    (if-let [sid (and r (first (current-turn id (:cwd r) home)))]
      (or (when (not= sid id) (record-of sid home)) r)
      r)))

(defn- sleep [ms] (ah/sleep ms))

(defn ready
  "A Task: session `id` once it is up — `{:id :trust-dialog? bool}` — or
  `{:error}` when it exits or `timeout-ms` (20 s) passes. On the trust dialog
  it presses Down until the screen shows the cursor on \"Yes\", and only then
  Enter: a key sent as the dialog is drawn is lost, and Enter on \"No\" ends
  Claude Code (RFC-039)."
  [id & [{:keys [home timeout-ms]}]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 20000))
        late? #(> (System/currentTimeMillis) deadline)]
    (m/sp
     (loop [dialog? false]
       (let [screen (m/? (fx/task-of #(pane id home)))]
         (cond
           (nil? screen) {:error (str "Claude Code exited before it was up (session " id ")")}
           (m/? (fx/task-of #(record id home))) {:id id :trust-dialog? dialog?}
           (late?) {:error (str "Claude Code was not up within " (or timeout-ms 20000) " ms")}
           (and (trust-dialog? screen) (cursor-on-yes? screen))
           (do (m/? (fx/task-of #(keys! [id home] "Enter"))) (m/? (sleep 300)) (recur true))
           (trust-dialog? screen)
           (do (m/? (fx/task-of #(keys! [id home] "Down"))) (m/? (sleep 250)) (recur true))
           :else (do (m/? (sleep 100)) (recur dialog?))))))))

(def mode-lines
  "The permission mode a session's footer shows, by what it says."
  [["auto mode on" "auto"] ["accept edits on" "acceptEdits"] ["plan mode on" "plan"]
   ["manual mode on" "manual"] ["bypass permissions on" "bypassPermissions"]])

(defn mode-of
  "The permission mode `screen`'s footer shows, or nil when it shows none — as
  while a dialog covers it."
  [screen]
  (some (fn [[needle mode]] (when (str/includes? (str screen) needle) mode)) mode-lines))

(defn ensure-mode
  "A Task: session `id` in permission mode `want`, as far as it can be.
  Claude Code does not offer auto mode with every model — with haiku,
  `--permission-mode auto` started it in manual mode, which asks before every
  edit too (byj-gly.290.9, measured) — so when `want` is auto and the footer
  shows another mode, shift+tab is pressed until it shows `fallback`
  (acceptEdits). `{:mode m}` — with `:wanted` when it is not what was asked
  for — or `{:mode nil}` when the footer cannot be read (a dialog over it)."
  [id want fallback]
  (m/sp
   (let [read-mode (fn [] (fx/task-of #(mode-of (pane id))))]
     (loop [n 0 seen nil]
       (let [mode (m/? (read-mode))]
         (cond
           (nil? mode) (if (< n 25) (do (m/? (sleep 200)) (recur (inc n) seen)) {:mode seen})
           (= mode want) {:mode mode}
           (not= "auto" want) {:mode mode :wanted want}
           (= mode fallback) {:mode mode :wanted want}
           (< n 8) (do (m/? (fx/task-of #(keys! id "BTab"))) (m/? (sleep 400)) (recur (inc n) mode))
           :else {:mode mode :wanted want}))))))

(defn transcript
  "Session `id`'s transcript, in the directory Claude Code keeps for `dir`."
  [id dir & [home]]
  (io/file (cs/claude-home home) "projects" (cs/project-key dir) (str id ".jsonl")))

(def max-continuations
  "How many `continued-in` links `current-turn` follows: Claude Code went on
  under a new id once in a session here; a chain longer than this is a loop."
  8)

(defn current-turn
  "Session `id`'s newest turn, `[transcript-id turn]`: from its own transcript,
  or — when Claude Code continued it under a new id, in the same process,
  writing `continued-in` as the old transcript's last word on it — from the
  newest transcript that chain leads to (byj-gly.290.10). Its later turns are
  only there, and agent$claude, reading the first, waited for a reply it
  would never see. A continued transcript not written yet has no turn: nil.
  `:usage` is the whole chain's, so what a turn cost — the usage after it
  less the usage before — holds across a continuation."
  [id dir & [home]]
  (loop [sid id n 0 usage {}]
    (let [t (cs/last-turn (transcript sid dir home))
          usage (merge-with #(merge-with + %1 %2) usage (:usage t))]
      (if-let [next-id (and (< n max-continuations) (:continued-in t))]
        (recur next-id (inc n) usage)
        [sid (cond-> t (seq usage) (assoc :usage usage))]))))

(defn observe
  "Where session `id` stands now: `{:id :running? :status :prompt :answer
  :ended?}` — its live record's status (`idle`, `busy`, or `waiting` while a
  permission dialog is up, when `:screen` is what it shows), and its newest
  turn from the transcript (`claude-sessions/last-turn`). `dir` is where it was
  started, for a session with no record yet. A session Claude Code continued
  under another id is read from where it went on (`current-turn`), and says
  that id as `:transcript-id` (byj-gly.290.10)."
  [id & [{:keys [home dir]}]]
  (let [r (record id home)
        [sid t] (current-turn id (or (:cwd r) dir) home)
        status (some-> (:status r) keyword)]
    (cond-> (merge {:id id :running? (some? (target id home)) :status status} (dissoc t :continued-in))
      (not= sid id) (assoc :transcript-id sid)
      (= :waiting status) (assoc :screen (pane id home)))))

(defn settled?
  "Is the turn over by `obs` alone? The record says idle AND the transcript
  holds a reply that ended the turn after the newest prompt: the record says
  idle mid-turn now and then, and the transcript lags it by ~3 s (RFC-039)."
  [obs]
  (and (= :idle (:status obs)) (boolean (:ended? obs))))

(defn await-settled
  "A Task: session `id`'s observation once its turn is over — `:settled`
  `:done` once `settled?` has held for `settle-ms` (1500), `:waiting` at once
  when a permission dialog is up (a person's to answer, or `choose!`'s), or
  `:gone` when the session is no longer running. Polls every `poll-ms` (200),
  handing each observation to `on-observe` when given (byj-gly.299). Never
  ends otherwise: give it `fx/timeout`."
  [id & [{:keys [poll-ms settle-ms on-observe] :as opts}]]
  (ah/await-settled #(observe id opts)
                    {:settled? settled? :waiting? #(= :waiting (:status %))
                     :poll-ms poll-ms :settle-ms settle-ms :on-observe on-observe}))

(defn- transcript-mark
  "Where session `id`'s transcript stands: its newest prompt, under which id,
  and how long the file is — what `taken?` compares with."
  [id dir home]
  (let [[sid t] (current-turn id dir home)
        f (when sid (transcript sid dir home))]
    {:sid sid :prompt (:prompt t) :bytes (if (and f (.isFile f)) (.length f) 0)}))

(defn- taken?
  "Has session `id` taken `line` since `before` (`transcript-mark`)? Its
  newest prompt is the line, and the transcript has grown since — or moved on
  to another id — so a line typed twice is not read as taken by the first."
  [id dir home before line]
  (let [now (transcript-mark id dir home)]
    (and (some? (:prompt now))
         (str/includes? (str (:prompt now)) (str/trim line))
         (or (not= (:sid now) (:sid before)) (> (:bytes now) (:bytes before))))))

(defn type-line
  "A Task: type `line` to session `id` as the person would, and press Enter —
  again, up to three times, while the record does not say busy within
  `busy-ms` (2 s) and the transcript does not hold the line: a turn short
  enough to go busy and idle again between two of the record's polls has
  still started (byj-gly.419, provenance P-42).
  `{:sent true :enters n}` — with `:gone? true` when the line ended the session
  (`/exit`) — or `{:error}`. Refused unless the session is idle
  (a line typed into a running turn is queued behind it, RFC-039), and refused
  when `line` is more than one line: a paste is taken for data, not for the
  person asking, and a long one arrives cut. Longer guidance is a file the line
  points at."
  [id line & [{:keys [home busy-ms]}]]
  (m/sp
   (let [r (m/? (fx/task-of #(record id home)))]
     (cond
       (or (str/blank? line) (str/includes? line "\n"))
       {:error "type-line types one line; put anything longer in a file and send a line that points at it"}
       (not= "idle" (:status r)) {:error (str "session " id " is " (or (:status r) "not up") ", not idle")}
       :else
       (let [dir (:cwd r)
             before (m/? (fx/task-of #(transcript-mark id dir home)))]
         (m/? (ah/type-and-confirm
               ;; `--`: a line that starts with `-` is otherwise read as tmux's flags
               {:type! #(keys! [id home] "-l" "--" line)
                :enter! #(keys! [id home] "Enter")
                :started #(fx/timeout (fx/poll-until 100 ::idle
                                                     (fn [] (let [s (:status (record id home))]
                                                              (cond (#{"busy" "waiting"} s) :started
                                                                    (nil? (target id home)) :gone
                                                                    :else ::idle))))
                                      (or busy-ms 2000) nil)
                :taken? #(taken? id dir home before line)
                :pause-ms 300 :max-enters 3 :what (str "session " id)})))))))

(defn choose
  "A Task: answer the dialog on session `id`'s screen with the choice numbered
  `n` — on a permission dialog `1` is yes, `3` no, which ends the turn — and
  wait until the record no longer says `waiting` (2 s): `{:chosen n}`, or
  `{:error}`. Waiting matters: the record changes a moment after the key, and
  a wait begun before it would see the dialog still up."
  [id n & [{:keys [home]}]]
  (m/sp
   (m/? (fx/task-of #(keys! [id home] (str n))))
   (if (m/? (fx/timeout (fx/poll-until 100 ::waiting
                                       #(if (= "waiting" (:status (record id home))) ::waiting true))
                        2000 nil))
     {:chosen n}
     {:error (str "session " id " still shows its dialog after " n)})))
