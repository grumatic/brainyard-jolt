;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.codex-host
  "Running and driving Codex sessions in tmux (byj-gly.430.3, RFC-049
  decision 5): Codex's adapter to `agent-host`, as `claude-host` is Claude
  Code's. Each runs in a detached tmux session named `byj-cx-<thread>`.

  Codex chooses a new session's thread id itself — there is no
  `--session-id` — so a new one starts under a name of its own
  (`byj-cx-pending-<uuid>`) and is renamed for its thread once Codex takes
  that thread's writer lock, which it does at start-up, past the folder
  dialog. A resumed one (`codex resume <thread>`) is named for its thread from
  the start.

  What each step is, is what the spike measured (RFC-049 \"What was
  measured\"): ready is the writer's lock taken, never the main screen, which
  is drawn before the folder dialog; a line is typed, then Enter after a
  pause, since an Enter that comes with the line is taken into it as part of
  a paste; busy and idle are the rollout's (`codex-sessions/last-turn`);
  waiting on a person is the approval dialog on the screen. Mechanism only:
  what to tell a session, and when, is the agent's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ebb.core :as m]
            [brainyard.agent-host :as ah]
            [brainyard.codex-sessions :as cxs]
            [brainyard.effect :as fx]
            [brainyard.json :as json]))

(def prefix
  "What this run's tmux sessions for Codex are named with."
  "byj-cx-")

(def pending-prefix
  "A new session's id until Codex has chosen its thread."
  "pending-")

(defn tmux-name [id] (str prefix id))

(defn tmux? "`agent-host/tmux?`." [] (ah/tmux?))

(def ^:dynamic *os*
  "The operating system `sandbox-check` takes this to be; nil reads
  os.name. A test binds it to ask as Linux would."
  nil)

(defn sandbox-check
  "Whether Codex's own sandbox can run a command here: `{:ok true}`, or
  `{:ok false :why <what it said>}`. Only Linux is asked: Codex sandboxes
  there with bubblewrap, which needs unprivileged user namespaces — and a
  container is not given them
  (byj-1wv.62.12); macOS's Seatbelt needs nothing more. It runs
  `codex sandbox -- true`, at most 20 s."
  [codex]
  (if-not (str/includes? (str/lower-case (or *os* (System/getProperty "os.name") "")) "linux")
    {:ok true}
    (let [r (try (fx/sh {:out :string :err :string} "timeout" "20" codex "sandbox" "--" "true")
                 (catch Throwable e {:exit -1 :err (ex-message e)}))
          said (->> (str/split-lines (str (:err r) "\n" (:out r))) (map str/trim) (remove str/blank?) first)]
      (if (zero? (:exit r))
        {:ok true}
        {:ok false :why (or said (str "codex sandbox exited " (:exit r)))}))))

(defn running
  "The Codex thread ids running in this run's tmux sessions now; one still
  starting, its thread not chosen yet, is not among them."
  []
  (into #{} (remove #(str/starts-with? % pending-prefix)) (ah/running prefix)))

(defn running-in
  "The running ones started in `project-dir` or under it (byj-gly.310)."
  [project-dir]
  (into #{} (remove #(str/starts-with? % pending-prefix)) (ah/running-in prefix project-dir)))

;; --- hooks a session byj starts carries -------------------------------------------------

(def ^:private event-labels
  {:PreToolUse "pre_tool_use" :PermissionRequest "permission_request" :PostToolUse "post_tool_use"
   :UserPromptSubmit "user_prompt_submit" :SessionStart "session_start" :Stop "stop"})

(defn trust-hash
  "The hash Codex records to trust a hook (codex-rs hooks `hook_hash`,
  config `version_for_toml`): sha256 over the sorted, compact JSON of the
  event's key label, the group's matcher when it has one, and the one handler
  with its defaults filled — `async` false, unset options left out. Reproduced
  on Orca's eight hooks and the spike's (RFC-049 decision 6)."
  [event-label matcher handler]
  (let [h (merge {:type "command" :async false} handler)
        ident (cond-> {:event_name event-label :hooks [h]} matcher (assoc :matcher matcher))
        canon (fn canon [x] (cond (map? x) (into (sorted-map) (map (fn [[k v]] [(name k) (canon v)])) x)
                                  (sequential? x) (mapv canon x)
                                  :else x))
        ;; serde_json leaves `/` as it is; data.json writes `\/`
        bs (.getBytes (str/replace (json/write-str (canon ident)) "\\/" "/") "UTF-8")
        md (java.security.MessageDigest/getInstance "SHA-256")]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bs))))))

(defn- toml-str [s] (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn hook-flags
  "`-c` options that give a session hooks — `hooks` is `{:PreToolUse command
  …}` — trusted for that session only: each hook and its trust record are
  session flags (source `/<session-flags>/config.toml`), so nothing is written
  to the person's Codex home. The trust table is one inline table: a dotted
  `-c` key would split at the `.` in `config.toml` (RFC-049 decision 6)."
  [hooks & [timeout]]
  (let [timeout (or timeout 30)
        hooks (sort-by key hooks)]
    (when (seq hooks)
      (into (vec (mapcat (fn [[ev command]]
                           ["-c" (str "hooks." (name ev) "=[{hooks=[{type=\"command\",command=" (toml-str command)
                                      ",timeout=" timeout "}]}]")])
                         hooks))
            ["-c" (str "hooks.state={"
                       (str/join "," (for [[ev command] hooks
                                           :let [label (event-labels ev)]]
                                       (str (toml-str (str "/<session-flags>/config.toml:" label ":0:0"))
                                            "={trusted_hash="
                                            (toml-str (trust-hash label nil {:command command :timeout timeout})) "}")))
                       "}")]))))

;; --- starting one -------------------------------------------------------------------------

(defn inherited
  "The Codex variables this process carries when it runs inside a Codex
  session itself (`CODEX_SANDBOX…`, its thread …): a child given them takes
  itself for that session's, so each is unset for it. `CODEX_HOME` is kept:
  it says where Codex's state is (RFC-049 decision 1)."
  []
  (vec (sort (filter #(and (str/starts-with? % "CODEX_") (not= % "CODEX_HOME")) (keys (System/getenv))))))

(defn start-argv
  "The tmux command that starts Codex in session `name`, in `dir`: `codex`
  (split on spaces) — `resume <thread>` when `resume` — with `model`, the
  `hooks`' flags (`hook-flags`), `args` as they are, then `--` and `prompt`,
  the brief, as its last argument, where it arrives whole as the person's own
  words. A pane takes the tmux server's environment, not this one's, so
  `CODEX_HOME` is passed when `home` or this environment names one, and
  `unset` names the variables the child must not inherit."
  [{:keys [name dir codex home resume model hooks args prompt]} unset]
  (let [home (or home (System/getenv "CODEX_HOME"))]
    (-> (into (ah/new-session-argv name dir)
              (concat ["env"] (mapcat #(vector "-u" %) unset)
                      (when home [(str "CODEX_HOME=" home)])
                      (for [[k v] (sort (fx/noninteractive {}))] (str k "=" v))))
        (into (str/split (str/trim (or codex "codex")) #"\s+"))
        (cond-> resume (into ["resume"]))
        (cond-> model (into ["-m" model]))
        (into (hook-flags hooks))
        (into args)
        (cond-> resume (conj resume))
        (cond-> (not (str/blank? prompt)) (into ["--" prompt])))))

(defn- locks
  "The thread ids with a writer-lock file in the home."
  [home]
  (into #{} (keep #(let [n (.getName ^java.io.File %)]
                     (when (and (str/ends-with? n ".lock") (not (str/starts-with? n ".")))
                       (subs n 0 (- (count n) 5)))))
        (or (.listFiles (io/file (cxs/codex-home home) "thread-writer-locks")) [])))

(defn start!
  "Start a Codex session (`start-argv`) in a detached tmux session:
  `{:name :known}` — `:known`, the threads already in the home, for `ready`
  to tell the new one by — or `{:error}`. A resumed thread already running is
  an error, not a second start."
  [{:keys [resume home] :as opts}]
  (if (and resume (contains? (running) resume))
    {:error (str "session " resume " is already running")}
    (let [name (tmux-name (or resume (str pending-prefix (java.util.UUID/randomUUID))))
          known (locks home)
          r (apply ah/sh (start-argv (assoc opts :name name) (inherited)))]
      (if (zero? (:exit r))
        {:name name :known known}
        {:error (str "could not start it: " (str/trim (str (:err r) (:out r))))}))))

;; --- opening one from the agent page (byj-gly.430.4) -------------------------------------

(defn- codex-argv [codex & more]
  (into (vec (str/split (str/trim (or codex "codex")) #"\s+")) more))

(defn plan
  "What Enter on a Codex `row` (`tui/codex-session-rows`) does:
  `{:start argv? :attach argv :id :hint argv?}`, or `{:refuse why}`. A new
  session starts under a pending name (Codex chooses its thread) and is
  attached; a saved one is resumed (`codex resume <thread>`) in `byj-cx-
  <thread>` and attached; a live one running here is attached; one running in
  another terminal is refused. Without tmux, Codex itself is handed the
  terminal. `running` is `running`'s set, `home` the Codex home to pass, and
  `nested-prefix` the prefix of the tmux this runs inside, nil outside one."
  [{:keys [state session-id]} {:keys [tmux? running project-dir codex home nested-prefix new-id]}]
  (let [new? (= :codex-new state)
        resume (when-not new? session-id)
        name (tmux-name (or resume (str pending-prefix ((or new-id #(str (java.util.UUID/randomUUID)))))))
        start (when-not (and resume (contains? running resume))
                (start-argv {:name name :dir project-dir :codex codex :home home :resume resume} (inherited)))]
    (cond
      (and (= :codex-live state) (not (contains? running session-id)))
      {:refuse "it runs in another terminal — open it there; only a session started from here can be opened here"}

      tmux? (cond-> {:id (or resume name) :start start :attach (conj ah/*tmux* "attach-session" "-t" name)}
              nested-prefix (assoc :hint (ah/hint-argv name nested-prefix)))

      :else {:id resume :attach (if resume (codex-argv codex "resume" resume) (codex-argv codex)) :foreground? true})))

(defn run-plan!
  "Do what `plan` decided (`agent-host/run-plan!`)."
  [plan hand!]
  (ah/run-plan! plan hand!))

(defn- pane-pid [name]
  (let [r (apply ah/sh (conj ah/*tmux* "list-panes" "-t" name "-F" "#{pane_pid}"))]
    (when (zero? (:exit r)) (some-> (str/split-lines (str (:out r))) first str/trim parse-long))))

(defn- with-descendants
  "`pid` and every process under it, from `ps`."
  [pid]
  (let [r (try (fx/sh {:out :string :err :string} "ps" "-A" "-o" "pid=" "-o" "ppid=") (catch Throwable _ nil))
        kids (reduce (fn [m l] (let [[p pp] (map parse-long (remove str/blank? (str/split (str/trim l) #"\s+")))]
                                 (if (and p pp) (update m pp (fnil conj []) p) m)))
                     {} (str/split-lines (str (:out r))))]
    (loop [todo [pid] seen []]
      (if-let [[p & more] (seq todo)]
        (recur (into (vec more) (kids p)) (conj seen p))
        seen))))

(defn- held-thread
  "The thread whose writer lock the pane's process `pid`, or one under it,
  holds — by `lsof`. Codex runs as the pane's own process (`env` exec'd
  it); one under it holding the lock is found too."
  [pid]
  (when pid
    (let [r (try (fx/sh {:out :string :err :string} "lsof" "-p" (str/join "," (with-descendants pid)) "-Fn")
                 (catch Throwable _ nil))]
      (some #(second (re-find #"^n.*/thread-writer-locks/([^/]+)\.lock$" %))
            (str/split-lines (str (:out r)))))))

(defn claim-pending!
  "Rename every pending session (`byj-cx-pending-…`) whose Codex has taken a
  thread to `byj-cx-<thread>`: one opened from the agent page is attached by
  a person, not brought up by `ready`, so nothing else learns its thread.
  Returns the threads claimed."
  []
  (vec (for [id (ah/running prefix)
             :when (str/starts-with? id pending-prefix)
             :let [name (tmux-name id)
                   thread (held-thread (pane-pid name))]
             :when (and thread (not (contains? (running) thread)) (ah/rename! name (tmux-name thread)))]
         thread)))

(defn with-running
  "`sessions` (`codex-sessions/sessions`) with `:running` — the threads in this
  run's tmux sessions — and each live one's `:status`, `\"busy\"` while its
  rollout has a turn open, else `\"idle\"`."
  [{:keys [live] :as sessions} running]
  (assoc sessions
         :running running
         :live (mapv #(assoc % :status (if (:busy? (cxs/last-turn (some-> (:file %) io/file))) "busy" "idle")) live)))

(defn trust-dialog?
  "Does `screen` show Codex's folder dialog?"
  [screen]
  (str/includes? (str screen) "Trust this folder"))

(defn cursor-on-trust?
  "Is the folder dialog's cursor on \"Trust and continue\" — where it opens?"
  [screen]
  (boolean (re-find #"›\s*1\.\s*Trust and continue" (str screen))))

(defn approval-dialog?
  "Does `screen` show an approval dialog? Its cursor opens on \"Yes,
  proceed\": Enter there approves."
  [screen]
  (let [s (str screen)]
    (or (str/includes? s "Would you like to run the following command?")
        (str/includes? s "Press enter to confirm or esc to cancel"))))

(defn- sleep [ms] (ah/sleep ms))

(defn ready
  "A Task: the session started as `started` (`start!`'s) once Codex is up —
  `{:id thread :trust-dialog? bool}`, its tmux session renamed `byj-cx-<thread>`
  — or `{:error}` when it exits or `timeout-ms` (20 s) passes. Up is the
  thread's writer lock taken: a new lock for a new session, the thread's own
  for a resumed one. On the folder dialog, its cursor on \"Trust and
  continue\", Enter, once."
  [{:keys [name known]} & [{:keys [home timeout-ms resume]}]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 20000))
        late? #(> (System/currentTimeMillis) deadline)
        thread (fn [] (if resume
                        (when (cxs/live? home resume) resume)
                        (first (filter #(cxs/live? home %) (remove (set known) (locks home))))))]
    (m/sp
     (loop [dialog? false]
       (let [screen (m/? (fx/task-of #(ah/capture name)))
             id (m/? (fx/task-of thread))]
         (cond
           (nil? screen) {:error (str "Codex exited before it was up (" name ")")}
           id (do (when (not= name (tmux-name id)) (m/? (fx/task-of #(ah/rename! name (tmux-name id)))))
                  {:id id :trust-dialog? dialog?})
           (late?) {:error (str "Codex was not up within " (or timeout-ms 20000) " ms")}
           (and (not dialog?) (trust-dialog? screen) (cursor-on-trust? screen))
           (do (m/? (sleep 300)) (m/? (fx/task-of #(ah/send-keys! name "Enter"))) (recur true))
           :else (do (m/? (sleep 100)) (recur dialog?))))))))

(defn target
  "The tmux target that reaches thread `id`'s screen — this run's session for
  it — or nil: a Codex in a terminal of its own has none (`codex queue`
  reaches it, RFC-049 decision 10)."
  [id]
  (when (contains? (running) id) (tmux-name id)))

(defn pane
  "What thread `id`'s screen shows now, or nil."
  [id]
  (some-> (target id) ah/capture))

(defn- keys! [id & ks]
  (if-let [t (target id)]
    (apply ah/send-keys! t ks)
    {:exit 1 :err (str "nothing reaches session " id)}))

(defn stop!
  "Close thread `id`'s tmux session: Codex exits and its thread is saved, to
  be resumed. `{:stopped id}`, or `{:error}`."
  [id]
  (let [r (ah/kill! (tmux-name id))]
    (if (:stopped r) {:stopped id} r)))

(defn observe
  "Where thread `id` stands now: `{:id :running? :status :prompt :answer
  :ended?}` and the rest of its rollout's newest turn
  (`codex-sessions/last-turn`). `:status` is `:waiting` while an approval
  dialog is on the screen (with `:screen`), `:busy` while a turn is open,
  else `:idle` — so too before the session is first asked, when there is no
  rollout yet."
  [id & [{:keys [home]}]]
  (let [screen (pane id)
        t (cxs/last-turn (cxs/rollout-file home id))
        status (cond (approval-dialog? screen) :waiting
                     (:busy? t) :busy
                     :else :idle)]
    (cond-> (merge {:id id :running? (some? screen) :status status :prompt nil :answer nil :ended? false} t)
      (= :waiting status) (assoc :screen screen))))

(defn settled?
  "Is the turn over by `obs` alone? Idle, and the rollout says it ended."
  [obs]
  (and (= :idle (:status obs)) (boolean (:ended? obs))))

(defn await-settled
  "A Task: thread `id`'s observation once its turn is over (`agent-host/
  await-settled`): `:done`, `:waiting` at an approval dialog, `:gone` when
  the session is no longer running. Give it `fx/timeout`."
  [id & [{:keys [poll-ms settle-ms on-observe] :as opts}]]
  (ah/await-settled #(observe id opts)
                    {:settled? settled? :waiting? #(= :waiting (:status %))
                     :poll-ms poll-ms :settle-ms settle-ms :on-observe (or on-observe nil)}))

(defn- mark [id home]
  (let [f (cxs/rollout-file home id)]
    {:bytes (if f (.length ^java.io.File f) 0) :prompt (:prompt (cxs/last-turn f))}))

(defn- taken?
  "Has thread `id` taken `line` since `before` (`mark`)? Its rollout grew and
  its newest prompt is the line."
  [id home before line]
  (let [now (mark id home)]
    (and (> (:bytes now) (:bytes before))
         (str/includes? (str (:prompt now)) (str/trim line)))))

(defn type-line
  "A Task: type `line` to thread `id` as the person would, then Enter after
  200 ms — again, up to three times, while the rollout shows no turn begun
  with it within 2 s: an Enter that comes with the line is taken into it as
  part of a paste, and the line stays in the composer (measured, RFC-049).
  `{:sent true :enters n}`, `:gone? true` when the session ended, or
  `{:error}`. Refused unless the session is idle — a line typed into a
  running turn waits behind it — and when `line` is more than one line: a
  longer brief is the argument or a file the line points at."
  [id line & [{:keys [home]}]]
  (m/sp
   (let [o (m/? (fx/task-of #(observe id {:home home})))]
     (cond
       (or (str/blank? line) (str/includes? line "\n"))
       {:error "type-line types one line; put anything longer in a file and send a line that points at it"}
       (not (:running? o)) {:error (str "session " id " is not running here")}
       (not= :idle (:status o)) {:error (str "session " id " is " (name (:status o)) ", not idle")}
       :else
       (let [before (m/? (fx/task-of #(mark id home)))]
         (m/? (ah/type-and-confirm
               {:type! #(keys! id "-l" "--" line)
                :enter! #(keys! id "Enter")
                :started #(fx/timeout (fx/poll-until 100 ::idle
                                                     (fn [] (cond (nil? (target id)) :gone
                                                                  (taken? id home before line) :started
                                                                  :else ::idle)))
                                      2000 nil)
                :pause-ms 200 :max-enters 3 :what (str "session " id)})))))))

(defn choose
  "A Task: answer the approval dialog on thread `id`'s screen with `key` —
  \"y\" its own shortcut for yes, \"Escape\" no, which aborts the turn
  (measured) — and wait until the dialog is gone (2 s): `{:chosen key}`, or
  `{:error}`."
  [id key & [_opts]]
  (m/sp
   (m/? (fx/task-of #(keys! id key)))
   (if (m/? (fx/timeout (fx/poll-until 100 ::waiting #(if (approval-dialog? (pane id)) ::waiting true))
                        2000 nil))
     {:chosen key}
     {:error (str "session " id " still shows its dialog after " key)})))
