(ns brainyard.dev.codex-spike
  "Measurements behind the Codex design decision (byj-gly.430.1, RFC-049): how
  byj can read a Codex session, tell whether it is live, busy, idle or waiting
  on a person, drive the interactive TUI in tmux, run it headless, keep a
  session it started from pushing, and hear its telemetry.

      jolt -A:dev -m brainyard.dev.codex-spike

  Spends Codex quota: about twelve short turns at low reasoning effort. Runs
  in a throwaway CODEX_HOME holding only a copy of the person's auth.json —
  never the person's own, whose config, hooks and trust records (Orca keeps
  its own there) it would otherwise write — on a tmux server of its own
  (`tmux -L byj-codex-spike`), in a throwaway git repository under /tmp.
  Prints one EDN map of what was observed; the tmux server is killed and the
  copy of auth.json deleted, and the rest of the directory is left for
  inspection.

  Probes, each a question RFC-049 needed answered:

  - `:home` — where Codex keeps its state: $CODEX_HOME, else ~/.codex.
  - `:exec-json` — `codex exec --json`'s events, and the rollout it writes:
    its line types, the person's and the agent's messages as
    `event_msg item_completed` (`UserMessage`, `AgentMessage`), a shell call
    as `custom_tool_call` `exec`.
  - `:resume` — `codex exec resume ID` appends to the same rollout.
  - `:sandbox-git` — under the default workspace-write sandbox `.git` is not
    writable: a commit is refused.
  - `:hook-flags` — a `PreToolUse` hook given with `-c`, its trust record
    given with `-c` too (the hash worked out here), refuses `git push` in
    Claude Code's own output format, and nothing is written to the home.
  - `:otel` — `[otel] exporter = otlp-http` sends OTLP/HTTP JSON logs to
    `/v1/logs`, which a listener of the spike's own receives (`otel-listener`,
    a few lines of python3; byj has no HTTP server, RFC-027).
  - `:trust` — a fresh folder shows Codex's folder dialog, the cursor on
    \"Trust and continue\" — drawn over the main screen about a second in,
    after \"Ask Codex to do anything\" is already on it, so ready is the
    writer's lock taken, never that text.
  - `:liveness` — the session holds `thread-writer-locks/<id>.lock`
    exclusively from start-up (before the first prompt, and before its
    rollout exists) to exit; a non-blocking shared `flock` through jolt.ffi
    is refused while it lives and taken once it has gone.
  - `:typed` — a line typed with `send-keys -l` and Enter at once: the Enter
    is taken into the paste and the line stays in the composer; a second
    Enter a second later sends it. After a 200 ms pause one Enter does.
  - `:argv-brief` and `:paste` — a 61-line brief given as the argument, or
    pasted (bracketed), arrives whole as the person's message, the
    composer showing `[Pasted Content N chars]`.
  - `:queue` — `codex queue` to a live TUI session idle at its prompt runs as
    its next turn within two seconds.
  - `:approval` — in the TUI, an escalation calls a `PermissionRequest` hook
    given as a session flag, and a `deny` from it decides without a dialog.

  What the runs found (Codex CLI 0.163.0, 2026-10-23) is RFC-049 \"What was
  measured\". By hand only: an approval dialog's cursor starts on \"Yes,
  proceed\" and Esc aborts the turn (`turn_aborted`); a queued message sent
  right after an aborted turn waited for the next turn; `exec` runs with
  approvals off whatever `approval_policy` says."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [jolt.ffi :as ffi]
            [jolt.process :as p]))

(def tmux ["tmux" "-L" "byj-codex-spike"])

;; --- an OTLP/HTTP JSON listener of the spike's own --------------------------------
;; It stood on the evaluation's OTLP receiver until that left byj (RFC-070 D4,
;; byj-ahf.25); decision 9 needs only to see the log lines Codex sends.

(def ^:private listener-py
  (str "import http.server,sys\n"
       "out=open(sys.argv[2],'a')\n"
       "class H(http.server.BaseHTTPRequestHandler):\n"
       "  def do_POST(self):\n"
       "    b=self.rfile.read(int(self.headers.get('Content-Length','0')))\n"
       "    out.write(b.decode('utf-8','replace').replace('\\n',' ')+'\\n'); out.flush()\n"
       "    self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(b'{}')\n"
       "  def log_message(self,*a): pass\n"
       "s=http.server.HTTPServer(('127.0.0.1',0),H)\n"
       "open(sys.argv[1],'w').write(str(s.server_port))\n"
       "s.serve_forever()\n"))

(defn- otel-listener
  "Start a listener on a free loopback port that appends each body POSTed to
  it to a file: `{:port :bodies-file :stop!}`."
  [dir]
  (let [port-file (io/file dir "otel-port")
        bodies (io/file dir "otel-bodies.jsonl")
        _ (io/delete-file port-file true)
        proc (fx/start {:out :string :err :string} "python3" "-c" listener-py (str port-file) (str bodies))
        port (loop [i 0]
               (cond (and (.isFile port-file) (seq (slurp port-file))) (parse-long (str/trim (slurp port-file)))
                     (< i 100) (do (Thread/sleep 50) (recur (inc i)))))]
    {:port port :bodies-file bodies
     :stop! #(try (p/destroy-tree proc) (catch Throwable _ nil))}))

(defn- otel-event-names
  "The `event.name` of every log record in the OTLP JSON bodies received."
  [bodies-file]
  (vec (for [line (when (.isFile bodies-file) (str/split-lines (slurp bodies-file)))
             :let [m (try (json/read-str line) (catch Throwable _ nil))]
             rl (:resourceLogs m) sl (:scopeLogs rl) rec (:logRecords sl)
             a (:attributes rec)
             :when (= "event.name" (:key a))]
         (get-in a [:value :stringValue]))))
(def effort ["-c" "model_reasoning_effort=\"low\""])

(defn- now [] (System/currentTimeMillis))
(defn- say [& xs] (binding [*out* *err*] (apply println "spike:" xs)))
(defn- pause [ms] (Thread/sleep (long ms)))

(defn- sh [opts & args] (apply fx/sh (merge {:out :string :err :string} opts) args))
(defn- tm [& args] (apply sh {} (concat tmux args)))

;; --- where Codex keeps its state -------------------------------------------------

(defn person-home
  "The person's Codex home: $CODEX_HOME, else ~/.codex. Orca, for one, sets
  CODEX_HOME to a home of its own, so ~/.codex may hold nothing."
  []
  (or (System/getenv "CODEX_HOME") (str (System/getProperty "user.home") "/.codex")))

(defn- scratch! []
  (let [root (str/trim (:out (sh {} "mktemp" "-d" "/tmp/byj-codex-spike.XXXXXX")))
        home (io/file root "home")
        repo (io/file root "repo")
        auth (io/file (person-home) "auth.json")]
    (when-not (.isFile auth)
      (throw (ex-info (str "no " auth ": sign in with `codex login` first") {})))
    (.mkdirs home)
    (io/copy auth (io/file home "auth.json"))
    (sh {} "chmod" "600" (str (io/file home "auth.json")))
    (.mkdirs repo)
    (doseq [argv [["git" "init" "-q"] ["git" "commit" "-q" "--allow-empty" "-m" "init"]
                  ["git" "init" "-q" "--bare" (str root "/remote.git")]
                  ["git" "remote" "add" "origin" (str root "/remote.git")]]]
      (apply sh {:dir (str repo) :env (assoc (into {} (System/getenv))
                                             "GIT_AUTHOR_NAME" "s" "GIT_AUTHOR_EMAIL" "s@s"
                                             "GIT_COMMITTER_NAME" "s" "GIT_COMMITTER_EMAIL" "s@s")}
             argv))
    {:root root :home (str home) :repo (str repo)}))

(defn- env-of [{:keys [home]}] (assoc (into {} (System/getenv)) "CODEX_HOME" home))

;; --- rollouts ----------------------------------------------------------------------

(defn- rollouts [home]
  (->> (file-seq (io/file home "sessions"))
       (filter #(and (.isFile %) (str/starts-with? (.getName %) "rollout-") (str/ends-with? (.getName %) ".jsonl")))
       (sort-by #(.lastModified %))))

(defn- rollout-of [home thread] (first (filter #(str/includes? (.getName %) thread) (rollouts home))))

(defn- lines [f]
  (if-not (and f (.isFile f))
    []
    (vec (keep #(try (json/read-str %) (catch Throwable _ nil)) (str/split-lines (slurp f))))))

(defn- kind
  "A rollout line's kind: `event_msg:task_started`, `event_msg:item_completed:UserMessage`, …"
  [{:keys [type payload]}]
  (let [k (:type payload)]
    (str type (when k (str ":" k))
         (when (= "item_completed" k) (str ":" (get-in payload [:item :type])))
         (when (and (= "response_item" type) (:name payload)) (str ":" (:name payload))))))

(defn- texts
  "The person's (`UserMessage`) or the agent's (`AgentMessage`) messages, oldest first."
  [ls item-type]
  (vec (for [{:keys [type payload]} ls
             :when (and (= "event_msg" type) (= "item_completed" (:type payload))
                        (= item-type (get-in payload [:item :type])))]
         (str/join (keep :text (get-in payload [:item :content]))))))

(defn- turns-started [ls] (count (filter #(= "event_msg:task_started" (kind %)) ls)))

;; --- liveness: the thread writer's lock, through jolt.ffi ------------------------------

(ffi/defcfn ^:private c-open "open" [:string :int] :int)
(ffi/defcfn ^:private c-flock "flock" [:int :int] :int)
(ffi/defcfn ^:private c-close "close" [:int] :int)

(defn live?
  "Is Codex writing thread `id` now? It holds `thread-writer-locks/<id>.lock`
  exclusively while it does; a non-blocking shared lock is refused then, and
  taken (and let go at once) when it does not. No lock file: not live."
  [home id]
  (let [fd (c-open (str (io/file home "thread-writer-locks" (str id ".lock"))) 0)]
    (when-not (neg? fd)
      (try
        (let [r (c-flock fd (bit-or 1 4))]          ; LOCK_SH | LOCK_NB
          (when (zero? r) (c-flock fd 8))           ; LOCK_UN
          (neg? r))
        (finally (c-close fd))))))

;; --- headless: codex exec --json ---------------------------------------------------------

(defn- exec!
  "`codex exec --json` in the repository: its events, decoded, and the thread."
  [s args prompt]
  (let [r (apply sh {:dir (:repo s) :env (env-of s) :in ""}
                 (concat ["codex" "exec" "--json"] effort args [prompt]))
        evs (vec (keep #(try (json/read-str %) (catch Throwable _ nil)) (str/split-lines (:out r))))]
    {:exit (:exit r) :events evs
     :thread (some #(when (= "thread.started" (:type %)) (:thread_id %)) evs)}))

(defn- answer [evs]
  (some->> evs (filter #(= "agent_message" (get-in % [:item :type]))) last :item :text))

(defn trust-hash
  "The hash Codex records to trust a hook (codex-rs hooks `hook_hash` and
  config `version_for_toml`): sha256 over the sorted, compact JSON of the
  event's key label, the group's matcher when it has one, and the one
  handler with its defaults filled — `async` false, unset options left out."
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

(defn- toml-str [s] (str "\"" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn hook-flags
  "`-c` options that give a session a `PreToolUse` hook running `command`,
  trusted for that session only: the hook and its trust record are both
  session flags (source `/<session-flags>/config.toml`), so nothing is written
  to the person's config. The trust table is passed whole, as an inline table:
  a dotted `-c` key splits at the `.` in `config.toml`."
  [command]
  (let [h {:command command :timeout 30}]
    ["-c" (str "hooks.PreToolUse=[{hooks=[{type=\"command\",command=" (toml-str command) ",timeout=30}]}]")
     "-c" (str "hooks.state={" (toml-str "/<session-flags>/config.toml:pre_tool_use:0:0")
               "={trusted_hash=" (toml-str (trust-hash "pre_tool_use" nil h)) "}}")]))

(defn- deny-push-hook!
  "A hook script that refuses any Bash call naming `git push`, and logs each call."
  [{:keys [root]}]
  (let [f (io/file root "deny-push.sh") log (io/file root "hook.log")]
    (spit f (str "#!/bin/sh\nin=$(cat)\nprintf '%s\\n' \"$in\" >> " (toml-str (str log)) "\n"
                 "case \"$in\" in *'git push'*)\n"
                 "  echo '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\","
                 "\"permissionDecisionReason\":\"byj spike: pushing is not this session to do\"}}';;\nesac\n"))
    (.setExecutable f true)
    {:command (str f) :log log}))

;; --- the TUI in tmux -----------------------------------------------------------------------

(defn- pane [name] (str (:out (tm "capture-pane" "-p" "-t" name))))

(defn- await-pane [name re timeout-ms]
  (let [t0 (now)]
    (loop []
      (let [p (pane name)]
        (cond (re-find re p) [p (- (now) t0)]
              (> (- (now) t0) timeout-ms) [nil (- (now) t0)]
              :else (do (pause 100) (recur)))))))

(defn- thread-lines [home thread] (lines (rollout-of home thread)))

(defn- await-turns
  "Poll thread `thread`'s rollout until `n` turns have started and the last
  is complete (or aborted): the ms it took, or nil."
  [home thread n timeout-ms]
  (let [t0 (now)]
    (loop []
      (let [ls (thread-lines home thread)
            done? (and (>= (turns-started ls) n)
                       (#{"event_msg:task_complete" "event_msg:turn_aborted"}
                        (some #(when (#{"event_msg:task_started" "event_msg:task_complete" "event_msg:turn_aborted"} (kind %))
                                 (kind %))
                              (rseq ls))))]
        (cond done? (- (now) t0)
              (> (- (now) t0) timeout-ms) nil
              :else (do (pause 250) (recur)))))))

(defn- live-thread
  "The thread a TUI just started holds the writer's lock on: Codex takes it
  at start-up, before the first prompt, so it names the session before its
  rollout exists."
  [home known timeout-ms]
  (let [t0 (now)]
    (loop []
      (let [ids (->> (or (.listFiles (io/file home "thread-writer-locks")) [])
                     (map #(.getName %))
                     (remove #(str/starts-with? % "."))
                     (map #(str/replace % #"\.lock$" ""))
                     (remove known))
            id (first (filter #(live? home %) ids))]
        (cond id id
              (> (- (now) t0) timeout-ms) nil
              :else (do (pause 200) (recur)))))))

(defn- start-ready!
  "A TUI started in `dir` and past its folder dialog: `{:thread :trust}`."
  [s name dir & [args brief]]
  (let [known (set (map #(str/replace (.getName %) #"\.lock$" "")
                        (or (.listFiles (io/file (:home s) "thread-writer-locks")) [])))]
    (apply tm "new-session" "-d" "-s" name "-x" "160" "-y" "50" "-c" (str dir) "-e" (str "CODEX_HOME=" (:home s))
           (concat ["codex"] effort args (when brief [brief])))
    ;; the main screen ("Ask Codex to do anything") is drawn first, ~0.5 s in,
    ;; and the folder dialog over it after: ready is the writer's lock taken,
    ;; which Codex does only past the dialog
    (let [t0 (now)
          trust (loop []
                  (let [p (pane name)]
                    (cond (str/includes? p "Trust this folder")
                          {:dialog? true :shown-ms (- (now) t0)
                           :cursor-on-trust? (boolean (re-find #"›\s*1\.\s*Trust and continue" p))}
                          (live-thread (:home s) known 0) {:dialog? false :ready-ms (- (now) t0)}
                          (> (- (now) t0) 20000) {:dialog? :timeout}
                          :else (do (pause 100) (recur)))))]
      (when (true? (:dialog? trust)) (pause 300) (tm "send-keys" "-t" name "Enter"))
      {:thread (live-thread (:home s) known 20000) :trust trust})))

(def long-brief
  (str/join "\n" (concat (for [i (range 1 61)] (format "line %02d of the brief: keep this line" i))
                         ["Reply with exactly: received"])))

(defn run-spike []
  (let [s (scratch!) out (atom {:home {:person (person-home) :spike (:home s)}})]
    (try
      (say "exec --json" (:root s))
      (let [{:keys [events thread]} (exec! s [] "Reply with exactly: ok")
            ls (lines (rollout-of (:home s) thread))]
        (swap! out assoc :exec-json
               {:events (mapv :type events) :answer (answer events)
                :usage (some :usage events)
                :rollout-kinds (vec (distinct (map kind ls)))
                :person (texts ls "UserMessage") :agent (texts ls "AgentMessage")
                :old-user-message-events (count (filter #(= "event_msg:user_message" (kind %)) ls))})
        (say "resume")
        (let [n (count ls)
              r (exec! s ["resume" thread] "Reply with exactly: resumed")]
          (swap! out assoc :resume {:same-thread? (= thread (:thread r)) :answer (answer (:events r))
                                    :rollout-lines [n (count (lines (rollout-of (:home s) thread)))]
                                    :rollout-files (count (filter #(str/includes? (.getName %) thread) (rollouts (:home s))))})))

      (say "sandbox and git")
      (let [before (str/trim (:out (sh {:dir (:repo s)} "git" "rev-list" "--count" "HEAD")))
            r (exec! s [] "Run this exact command: `git commit --allow-empty -m spike` and say whether it worked.")]
        (swap! out assoc :sandbox-git {:commits [before (str/trim (:out (sh {:dir (:repo s)} "git" "rev-list" "--count" "HEAD")))]
                                       :answer (answer (:events r))}))

      (say "hook as session flags")
      (let [{:keys [command log]} (deny-push-hook! s)
            r (exec! s (hook-flags command) "Run git push origin main and tell me what happened.")
            pushed (str/trim (:out (sh {} "git" "-C" (str (:root s) "/remote.git") "rev-list" "--all" "--count")))]
        (swap! out assoc :hook-flags {:hook-calls (if (.isFile log) (count (str/split-lines (slurp log))) 0)
                                      :payload-keys (when (.isFile log)
                                                      (some-> (first (str/split-lines (slurp log))) json/read-str keys sort vec))
                                      :remote-commits pushed :answer (answer (:events r))
                                      :home-config-written? (.exists (io/file (:home s) "config.toml"))}))

      (say "otel")
      (let [{:keys [port bodies-file stop!]} (otel-listener (:root s))
            _ (exec! s ["-c" (str "otel.exporter={otlp-http={endpoint=\"http://127.0.0.1:" port "/v1/logs\",protocol=\"json\"}}")
                        "-c" "otel.trace_exporter=\"none\"" "-c" "otel.metrics_exporter=\"none\""]
                     "Run ls and reply with the number of files.")
            _ (pause 2000)
            names (otel-event-names bodies-file)]
        (stop!)
        (swap! out assoc :otel {:port port :events (count names)
                                :names (vec (sort (distinct names)))}))

      (say "trust, liveness, typed")
      (let [fresh (io/file (:root s) "fresh")]
        (.mkdirs fresh)
        (sh {:dir (str fresh)} "git" "init" "-q")
        (let [{:keys [thread trust]} (start-ready! s "s1" fresh)
              home (:home s)
              turns #(turns-started (thread-lines home thread))
              type! (fn [text pause-ms] (tm "send-keys" "-t" "s1" "-l" text)
                      (when (pos? pause-ms) (pause pause-ms))
                      (tm "send-keys" "-t" "s1" "Enter"))]
          (await-pane "s1" #"Ask Codex to do anything" 20000)
          (swap! out assoc :trust trust
                 :liveness {:thread thread :live? (live? home thread)
                            :rollout-before-first-prompt? (some? (rollout-of home thread))})
          ;; a line and its Enter at once, then the same after a pause
          (type! "Reply with exactly: pong" 0)
          (let [first-enter (await-turns home thread 1 10000)
                composer (when-not first-enter
                           (some #(when (str/includes? % "pong") (str/trim %)) (str/split-lines (pane "s1"))))
                _ (when-not first-enter (pause 1000) (tm "send-keys" "-t" "s1" "Enter"))
                second-enter (when-not first-enter (await-turns home thread 1 30000))]
            (type! "Reply with exactly: ping" 200)
            (swap! out assoc :typed {:no-pause {:first-enter-ms first-enter :composer-after composer
                                                :second-enter-ms second-enter}
                                     :pause-200ms-ms (await-turns home thread (inc (turns)) 30000)}))

          (say "paste")
          (spit (io/file (:root s) "brief.txt") long-brief)
          (tm "load-buffer" (str (io/file (:root s) "brief.txt")))
          (tm "paste-buffer" "-p" "-t" "s1")
          (pause 500)
          (let [n (turns)
                shown (some #(when (str/includes? % "Pasted") (str/trim %)) (str/split-lines (pane "s1")))]
            (tm "send-keys" "-t" "s1" "Enter")
            (await-turns home thread (inc n) 60000)
            (let [m (last (texts (thread-lines home thread) "UserMessage"))]
              (swap! out assoc :paste {:shown shown :lines (count (str/split-lines m))
                                       :whole? (= m long-brief) :wrapped? (str/includes? m "<pasted")})))

          (say "queue")
          (let [n (turns)
                q (sh {:env (env-of s) :in ""} "codex" "queue" "--thread" thread "--message" "Reply with exactly: queued")
                idle-ms (await-turns home thread (inc n) 20000)]
            (when-not idle-ms (type! "Reply with exactly: typed" 200) (await-turns home thread (+ n 2) 60000))
            (swap! out assoc :queue {:exit (:exit q) :ran-while-idle-ms idle-ms
                                     :last-messages (vec (take-last 2 (texts (thread-lines home thread) "UserMessage")))}))

          (tm "kill-session" "-t" "s1")
          (pause 1500)
          (swap! out assoc-in [:liveness :live-after-exit?] (live? home thread))))

      (say "argv brief")
      (let [{:keys [thread]} (start-ready! s "s2" (io/file (:root s) "fresh") [] long-brief)]
        (await-turns (:home s) thread 1 60000)
        (let [m (first (texts (thread-lines (:home s) thread) "UserMessage"))]
          (swap! out assoc :argv-brief {:lines (some-> m str/split-lines count) :whole? (= m long-brief)}))
        (tm "kill-session" "-t" "s2"))

      (say "approval")
      ;; in the TUI (exec runs with approvals off): a PermissionRequest hook
      ;; given as a session flag with its trust record, refusing
      (let [f (io/file (:root s) "deny-permission.sh") log (io/file (:root s) "permission.log")
            _ (spit f (str "#!/bin/sh\ncat >> " (toml-str (str log)) "\necho >> " (toml-str (str log)) "\n"
                           "echo '{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\","
                           "\"decision\":{\"behavior\":\"deny\",\"message\":\"byj spike: no network\"}}}'\n"))
            _ (.setExecutable f true)
            h {:command (str f) :timeout 30}
            {:keys [thread]} (start-ready! s "s3" (io/file (:root s) "fresh")
                                           ["-c" (str "hooks.PermissionRequest=[{hooks=[{type=\"command\",command=" (toml-str (str f)) ",timeout=30}]}]")
                                            "-c" (str "hooks.state={" (toml-str "/<session-flags>/config.toml:permission_request:0:0")
                                                      "={trusted_hash=" (toml-str (trust-hash "permission_request" nil h)) "}}")])]
        (await-pane "s3" #"Ask Codex to do anything" 20000)
        (tm "send-keys" "-t" "s3" "-l" "Run curl -sI https://example.com . It needs network access, which the sandbox blocks, so request escalated permissions for it.")
        (pause 200)
        (tm "send-keys" "-t" "s3" "Enter")
        (await-turns (:home s) thread 1 90000)
        (swap! out assoc :approval {:hook-called? (and (.isFile log) (str/includes? (slurp log) "PermissionRequest"))
                                    :tool-input (when (.isFile log)
                                                  (some-> (first (str/split-lines (slurp log))) json/read-str :tool_input))
                                    :dialog-shown? (str/includes? (pane "s3") "Would you like to run")
                                    :blocked-on-screen (some #(when (str/includes? % "Blocked by hook") (str/trim %))
                                                             (str/split-lines (pane "s3")))
                                    :answer (last (texts (thread-lines (:home s) thread) "AgentMessage"))})
        (tm "kill-session" "-t" "s3"))
      @out
      (catch Throwable e
        (say "failed:" (ex-message e))
        (assoc @out :failed (ex-message e)))
      (finally
        (tm "kill-server")
        ;; the copy of the person's credentials goes; the rest stays to read
        (.delete (io/file (:home s) "auth.json"))))))

(defn -main [& _]
  (pp/pprint (run-spike))
  (shutdown-agents))
