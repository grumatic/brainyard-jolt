;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.sim
  "The dogfood simulation's runner (RFC-041, byj-gly.315.2): brainyard builds a
  project from scratch while this plays the person. Mechanism only: the
  project, its stages, what the person says and what is checked after each
  are a plan, data (`dev/sim/<name>/plan.edn`).

  The person types into a real inline session in a tmux session of its own
  (`*tmux*`, a server of its own), so a run can be watched live: `tmux -L
  byj-sim attach -t <name>`. The session channel will not do: it refuses a
  command from a peer by design (RFC-031), and `/init` is a command. A line is
  typed, and the turn is over when the session shows its `you>` prompt again.
  A permission prompt is answered here — by the plan's `:answers`, else no —
  and recorded: in the pilot, a prompt took the person's next line as its
  answer (RFC-041 \"Pilot findings\").

  After each stage its checks run in the project (`checks`: `:command`,
  `:file`, the shapes of an evaluator's task verifiers), and what the stage cost is read from the
  session's trajectory: every record since it began, subagents' included.
  With `:resume?` (the default) each stage is a new process resuming the
  session, so every stage also exercises resuming (RFC-016).

  A run writes `<project>/.sim/report.edn`, `report.md`, and each stage's
  screen, `<stage>.screen.txt`; `.sim/` is kept out of the project's git.

  `:loop FILE` runs the project on a loop of the person's choosing rather than
  the bundled one (RFC-071 \"Promotion\", byj-ng7.18), and the report says
  which loop every run ran.

  A plan's `:attachments`, `{slot-id [name …]}`, attaches the trees in
  `<plan>/ext/<name>.edn` to the ReAct loop's slots before the first stage,
  as a person's accept would (RFC-072), and the report names them. A plan's
  `:notice-markers` are words to count in the notices each stage's turns
  were shown — what an attachment said, `ANSWER REFUSED` for a refusal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.agent-host :as ah]
            [brainyard.config :as config]
            [brainyard.effect :as fx]
            [brainyard.loop-proposals :as loop-proposals]
            [brainyard.project-loops :as project-loops]
            [brainyard.trajectory :as trajectory]))

;; --- tmux -----------------------------------------------------------------------------------

(def ^:dynamic *tmux*
  "The tmux command, as argv: a server of the simulation's own, so it never
  touches the person's."
  ["tmux" "-L" "byj-sim"])

(def ^:dynamic *poll-ms*
  "How often the screen and the event log are read while a stage waits: a
  second, against a live model whose turns take minutes; a test on the mock
  model binds it shorter, where each wait was most of a stage (byj-gly.442).
  Settling reads half as often."
  1000)

(defn- tmux [& args]
  (apply fx/sh {:out :string :err :string :env (dissoc (into {} (System/getenv)) "TMUX")}
         (concat *tmux* args)))

(defn- quote-sh [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn screen
  "What session `sess` has shown, its whole history, wrapped lines joined."
  [sess]
  (str (:out (tmux "capture-pane" "-p" "-J" "-S" "-" "-t" sess))))

(def ^:private ask-tail "[d]eny always >")

(defn screen-state
  "Where the session on `text` stands: `{:state :ready|:asking|:exited|:busy
  :prompts n :asking-for display}`. `:prompts` counts the `you>` lines — one
  more than before a line was typed, and the last of them bare, is a turn
  over."
  [text]
  (let [lines (mapv str/trimr (str/split-lines (str text)))
        last-line (last (remove str/blank? lines))
        prompts (count (filter #(str/starts-with? % "you>") lines))]
    (cond
      (and last-line (str/starts-with? last-line "[sim: exited")) {:state :exited :prompts prompts}
      (and last-line (str/ends-with? last-line ask-tail))
      {:state :asking :prompts prompts
       :asking-for (some #(when (str/starts-with? % "permission:") (str/trim (subs % (count "permission:"))))
                         (reverse lines))}
      (= "you>" last-line) {:state :ready :prompts prompts}
      :else {:state :busy :prompts prompts})))

(defn- sleep [ms] (fx/run-value!! (fx/delayed ms (fx/success nil))))

(defn- wait-for
  "Poll `name`'s screen until `(done? state)` or `timeout-ms`; a permission
  prompt on the way is answered by `answer` (fn [display] → \"y\"|\"n\") and
  recorded in `asked`. The last state, `:timed-out?` when it ran out."
  [sess done? timeout-ms answer asked]
  (let [until (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [st (screen-state (screen sess))]
        (cond
          (done? st) st
          (> (System/currentTimeMillis) until) (assoc st :timed-out? true)
          (= :asking (:state st))
          (let [a (answer (:asking-for st))]
            (swap! asked conj {:asking-for (:asking-for st) :answer a :at (System/currentTimeMillis)})
            (tmux "send-keys" "-t" sess a "Enter")
            (sleep 1500)
            (recur))
          :else (do (sleep *poll-ms*) (recur)))))))

(defn- type-line [sess line]
  (tmux "send-keys" "-t" sess "-l" line)
  (tmux "send-keys" "-t" sess "Enter"))

(def submit-ms
  "How long a typed line may go without starting a turn before it is taken
  not to have been submitted."
  30000)

(defn- turns-started
  "How many turns the project's sessions have started, by `events.log`: an
  inline session shows nothing while a turn runs, so the screen cannot say
  whether a line was taken (a sonnet turn said nothing for minutes)."
  [dir]
  (let [f (io/file dir ".brainyard" "logs" "events.log")]
    (if (.isFile f)
      (count (re-seq #":event :session\.turn/start" (slurp f)))
      0)))

(defn parked-pending
  "Tasks a turn in project `dir` parked — left running, to wake the session
  with a fresh turn when they end — that have not ended, by `events.log`, and
  how many turns are open (started, not ended). A turn that parks a task ends
  at once, so a prompt at rest does not mean the stage's work is done
  (byj-gly.317: mode B's ui stage was checked while Claude Code still built
  it). `{:tasks #{id…} :open-turns n}`."
  [dir]
  (let [f (io/file dir ".brainyard" "logs" "events.log")
        log (if (.isFile f) (slurp f) "")
        ids #(set (map second (re-seq % log)))]
    {:tasks (reduce disj
                    (ids #":event :task/parked, [^\n]*?:data \{:task-id \"([^\"]+)\"")
                    (ids #":event :task/completed, [^\n]*?:data \{:id \"([^\"]+)\""))
     :open-turns (- (count (re-seq #":event :session\.turn/start" log))
                    (count (re-seq #":event :session\.turn/end" log)))}))

(defn- settle
  "After a line's turn has answered: wait while a task it parked runs, and then
  for the turn that wakes on it to end, until `until` (ms). The task ids
  waited on, and whether time ran out first."
  [sess dir until answer asked]
  (loop [seen #{}]
    (let [{:keys [tasks open-turns]} (parked-pending dir)
          seen (into seen tasks)
          st (screen-state (screen sess))]
      (cond
        (= :exited (:state st)) {:parked seen}
        (and (empty? tasks) (or (empty? seen) (and (<= open-turns 0) (= :ready (:state st)))))
        {:parked seen}
        (> (System/currentTimeMillis) until) {:parked seen :timed-out? true}
        (= :asking (:state st))
        (let [a (answer (:asking-for st))]
          (swap! asked conj {:asking-for (:asking-for st) :answer a :at (System/currentTimeMillis)})
          (tmux "send-keys" "-t" sess a "Enter")
          (sleep 1500)
          (recur seen))
        :else (do (sleep (* 2 *poll-ms*)) (recur seen))))))

(defn- not-submitted?
  "Is `line`, typed into `sess`, still at its prompt after `submit-ms`, with
  no turn started in `dir` and nothing after it on the screen? A command (a
  `/` or `:` line) starts no turn, so it is taken when the screen moves."
  [sess dir line turns-before]
  (let [head (str "you> " (subs line 0 (min 40 (count line))))
        until (+ (System/currentTimeMillis) submit-ms)]
    (loop []
      (let [lines (remove str/blank? (map str/trimr (str/split-lines (screen sess))))
            at-prompt? (and (seq lines) (str/starts-with? (last lines) head))]
        (cond (not at-prompt?) false
              (> (turns-started dir) turns-before) false
              (> (System/currentTimeMillis) until) true
              :else (do (sleep *poll-ms*) (recur)))))))

(defn start-session!
  "A tmux session `name` running brainyard inline in `dir`: `cmd` from
  `brainyard-dir` (this checkout), the provider and model, `--resume` when
  given. The pane stays once the session ends, saying so, for its last words
  to be read."
  [sess {:keys [brainyard-dir dir provider model permission-mode resume extra-args]}]
  (tmux "kill-session" "-t" sess)
  (let [cmd (str "cd " (quote-sh brainyard-dir) " && "
                 ;; the terminal as a person's is: the inline reader takes a
                 ;; line past MAX_CANON itself now (byj-gly.315.12), and a
                 ;; workaround here would hide its regression
                 "jolt run:byj --inline"
                 " -p " (quote-sh provider) (when model (str " -m " (quote-sh model)))
                 " --permission-mode " (name (or permission-mode :auto))
                 " -C " (quote-sh dir)
                 (when resume (str " --resume " (quote-sh resume)))
                 (when (seq extra-args) (str " " (str/join " " (map quote-sh extra-args))))
                 "; echo \"[sim: exited $?]\"; sleep 86400")]
    (tmux "new-session" "-d" "-s" sess "-x" "200" "-y" "50" cmd)))

;; --- the project ------------------------------------------------------------------------

(defn- sh! [dir & argv] (apply fx/sh {:dir (str dir) :out :string :err :string} argv))

(defn checks
  "A stage's `:checks` on the project, in order, each `{:type :name :pass?
  :detail}`: `:command` runs `:run` with `sh -c` there, passing on `:exit`
  (0) within `:timeout-s` (600); `:file` has `:path` exist, hold
  `:contains`, or with `:absent?` not exist. The shapes of an
  evaluator's task verifiers."
  [dir cs]
  (vec (for [{:keys [type run exit timeout-s path contains absent?] :as c} cs]
         (merge {:type type :name (or (:name c) (name type))}
                (case type
                  :command (let [r (fx/run-value!! (fx/timeout (fx/process-task {:dir (str dir) :out :string :err :string} "sh" "-c" run)
                                                               (* 1000 (or timeout-s 600)) ::timeout))
                                 out (str/trim (str (:out r) (:err r)))]
                             (if (= ::timeout r)
                               {:pass? false :detail (str "timed out: " run)}
                               {:pass? (= (or exit 0) (:exit r))
                                :detail (str "exit " (:exit r) (when (seq out) (str ": " (if (> (count out) 400) (str "…" (subs out (- (count out) 400))) out))))}))
                  :file (let [f (io/file dir path)]
                          (cond absent? {:pass? (not (.exists f)) :detail (str path (if (.exists f) " exists" " is absent"))}
                                (not (.isFile f)) {:pass? false :detail (str path " is missing")}
                                contains {:pass? (str/includes? (slurp f) contains)
                                          :detail (str path (if (str/includes? (slurp f) contains) " contains " " lacks ") (pr-str contains))}
                                :else {:pass? true :detail (str path " exists")}))
                  {:pass? false :detail (str "no check of type " type)})))))

(defn write-project-config!
  "Write `config` (schema keys, `{:claude-model \"sonnet\"}`) as project
  `dir`'s `.brainyard/config.edn`, where a project's config lives: under
  `[:agent :config]` — at the top level it is ignored as misplaced, and a run
  of Claude Code meant for Sonnet went on Opus (byj-gly.320)."
  [dir config]
  (let [f (io/file dir ".brainyard" "config.edn")]
    (io/make-parents f)
    (spit f (with-out-str (pp/pprint {:agent {:config config}})))))

(defn write-project-files!
  "Copy the plan's `:project-files`, `{\"path in the project\" \"path from the
  plan's directory\"}`, into project `dir` before its first stage: what its
  person had there already, such as the user tools and predictors an
  attachment calls (byj-10y). A source that is not there is thrown on,
  before anything runs."
  [dir plan]
  (doseq [[to from] (:project-files plan)
          :let [src (io/file (:plan-dir plan) from)
                dst (io/file dir to)]]
    (when-not (.isFile src) (throw (ex-info (str "no project file " src) {:file (str src)})))
    (io/make-parents dst)
    (io/copy src dst)))

(defn prepare-project!
  "A fresh project at `dir`: a git repository with one empty commit, and
  `.sim/` kept out of it."
  [dir]
  (let [d (io/file dir)]
    (when (and (.exists d) (seq (remove #(= ".sim" (.getName ^java.io.File %)) (.listFiles d))))
      (throw (ex-info (str dir " is not empty: a simulation starts from nothing") {:dir dir})))
    (.mkdirs (io/file d ".sim"))
    (sh! d "git" "init" "-q")
    (sh! d "git" "commit" "-q" "--allow-empty" "-m" "An empty project for the brainyard simulation")
    (spit (io/file d ".git" "info" "exclude") ".sim/\n" :append true)
    (.getCanonicalPath d)))

;; --- which loop the project runs (byj-ng7.18) -----------------------------------------------

(defn loop-candidate
  "The loop in `file` as `{:name :tree :file}`, its name what `byj loops eval`
  takes it for (`loop-eval/loop-of`: its `:based-on`, else its file's name,
  else react); thrown when it is no tree, or is one `byj loops eval` would not
  run — checked against `dir`, the project it will run in."
  [file dir]
  (let [f (io/file file)
        _ (when-not (.isFile f) (throw (ex-info (str "no loop file " file) {:file file})))
        tree (try (edn/read-string (slurp f)) (catch Throwable e (throw (ex-info (str file ": " (ex-message e)) {:file file}))))
        ln ((requiring-resolve 'brainyard.loop-eval/loop-of) (.getPath f))
        ps (loop-proposals/problems {:project-dir (str dir) :working-dir (str dir)} tree nil)]
    (when (seq ps)
      (throw (ex-info (str file " is not a loop byj would run: "
                           (str/join "; " (map #(str (pr-str (:node %)) " " (:problem %)) ps)))
                      {:file file :problems ps})))
    {:name ln :tree tree :file (.getCanonicalPath f)}))

(defn install-loop!
  "Make `candidate` (`loop-candidate`) project `dir`'s loop of its name, as a
  person's accept does (`project-loops/write-loop!`), so the project's
  history says it."
  [dir {:keys [name tree]}]
  (project-loops/write-loop! dir name tree {:action :accept}))

(defn loop-in-force
  "Which loop `ln` project `dir` runs: `{:name :from :hash}`, `:from`
  `:project` or `:bundled` (`loop-proposals/in-force`), and `:file` the one
  it was installed from."
  [dir ln & [file]]
  (cond-> (assoc (loop-proposals/in-force {:project-dir (str dir)} ln) :name ln)
    file (assoc :file file)))

(defn- loops-differ
  "The two runs' loops, `[a b]`, when they are not the same; nil when they
  are, or a run is too old to say."
  [ra rb]
  (let [[a b] [(:loop ra) (:loop rb)]]
    (when (and a b (not= (select-keys a [:name :hash]) (select-keys b [:name :hash])))
      [a b])))

(defn- loop-label [{:keys [name from hash file]}]
  (str name ", " (clojure.core/name from) " " hash (when file (str " (" file ")"))))

(defn plan-attachments
  "The plan's `:attachments` as `[{:slot :name :tree :file}]`, each read from
  `<plan>/ext/<name>.edn` and checked where it would sit in a project at
  `dir` (`loop-proposals/attachment-problems`); thrown otherwise, before
  anything is made. A plan with `:project-files` is checked against a scratch
  project holding them, since an attachment may call a tool of theirs
  (byj-10y)."
  [plan dir]
  (let [dir (if (seq (:project-files plan))
              (doto (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "sim-check-" (random-uuid)))
                                         .mkdirs))
                (write-project-files! plan))
              dir)]
   (vec (for [[slot names] (:attachments plan)
             n names
             :let [f (io/file (:plan-dir plan) "ext" (str n ".edn"))
                   _ (when-not (.isFile f) (throw (ex-info (str "no attachment file " f) {:file (str f)})))
                   tree (edn/read-string (slurp f))
                   ps (loop-proposals/attachment-problems {:project-dir (str dir) :working-dir (str dir)} slot n tree nil)]]
         (if (seq ps)
           (throw (ex-info (str f " cannot be attached to " slot ": "
                                (str/join "; " (map #(str (pr-str (:node %)) " " (:problem %)) ps)))
                           {:file (str f) :problems ps}))
           {:slot slot :name n :tree tree :file (.getCanonicalPath f)})))))

(defn install-attachments!
  "Attach each of `as` (`plan-attachments`) in project `dir`, in order, as a
  person's accept does (`project-loops/write-attachment!`), so the project's
  history says it. `[{:slot :name :hash}]`."
  [dir as]
  (vec (for [{:keys [slot name tree]} as]
         (let [contract (get (project-loops/slots ((requiring-resolve 'brainyard.agent/load-tree) "react" (str dir))) slot)]
           (select-keys (project-loops/write-attachment! (str dir) slot name tree {:contract contract})
                        [:slot :name :hash])))))

;; --- what a stage cost -------------------------------------------------------------------

(defn- failed-call?
  "A call the record marks failed (`:error?`: a gate's block, which prints as
  `{:hook-blocked …}`, included — byj-ng7.18), or whose result reads as one."
  [{:keys [result error?]}]
  (let [s (str result)]
    (boolean (or error? (str/starts-with? s "{:error") (re-find #":exit-code [1-9]" s) (re-find #":status \"timeout\"" s)))))

(defn records-since
  "Every trajectory record in project `dir` written at or after `since` (ms),
  across its sessions — a stage's root turns and its subagents' alike."
  [dir since]
  (let [sessions (or (.listFiles (io/file dir ".brainyard" "sessions")) [])]
    (vec (sort-by :ts (for [s sessions
                            :when (.isDirectory ^java.io.File s)
                            r (try (trajectory/read-trajectories {:project-dir dir} (.getName ^java.io.File s))
                                   (catch Throwable _ []))
                            :when (>= (or (:ts r) 0) since)]
                        r)))))

(defn delegation-costs
  "What each Claude Code session agent$claude drove in project `dir` has cost
  so far, by its delegation record: `{session-id usd}`."
  [dir]
  (into {} (for [f (or (.listFiles (io/file dir ".brainyard" "claude-agents")) [])
                 :when (str/ends-with? (.getName ^java.io.File f) ".edn")
                 :let [r (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
                 :when (and (map? r) (:session-id r))]
             [(:session-id r) (double (or (:cost-usd r) 0))])))

(defn delegated-cost
  "What Claude Code spent between two `delegation-costs` snapshots: each
  session's rise. A delegation started in an earlier stage, or in a failed
  attempt of this one, and still at work counts here for what it spent here;
  counting by when each started missed it (byj-gly.319)."
  [before after]
  (double (reduce + 0.0 (for [[sid usd] after] (max 0.0 (- usd (get before sid 0.0)))))))

(defn notice-counts
  "How many iterations of `records` were shown a notice holding each of
  `markers`: `{marker n}`."
  [records markers]
  (into {} (for [mk markers]
             [mk (count (for [r records it (:iterations r)
                              :when (str/includes? (str (:notices it)) mk)]
                          it))])))

(defn stage-metrics
  "What `records` say a stage did: turns, iterations, calls, failed calls (the
  first few, as they read), cost and tokens."
  [records]
  (let [all (mapcat (fn [r] (mapcat :tools (:iterations r))) records)
        ;; the model's calls; a tree's own (`:tool/run`, an attachment's) are
        ;; counted apart (byj-8gc)
        calls (filter trajectory/model-call? all)
        tree (remove trajectory/model-call? all)
        failed (filter failed-call? calls)
        cmd #(let [c (get-in % [:args :command] (:args %))] (subs (str c) 0 (min 100 (count (str c)))))]
    {:records (count records)
     :turns (count (remove #(str/includes? (str (:agent %)) "/") records))
     :subagents (vec (distinct (keep #(when (str/includes? (str (:agent %)) "/") (first (str/split (str (:agent %)) #"/"))) records)))
     :iterations (reduce + (map #(or (:total-iterations %) 0) records))
     :calls (count calls)
     :tree-calls (count tree)
     :tree-calls-by (into (sorted-map) (frequencies (map #(str (or (:slot %) (:node %))) tree)))
     :failed-calls (count failed)
     :failed (vec (for [c (take 5 failed)]
                    {:tool (:name c) :call (cmd c) :result (subs (str (:result c)) 0 (min 200 (count (str (:result c)))))}))
     :cost-usd (double (reduce + 0.0 (map #(double (or (:cost %) 0)) records)))
     :tokens-in (reduce + (map #(or (get-in % [:usage :in]) 0) records))
     :tokens-out (reduce + (map #(or (get-in % [:usage :out]) 0) records))
     :unsuccessful (count (remove :success records))}))

;; --- findings: the rules (byj-gly.315.3) -------------------------------------------------

(def default-rules
  "What counts as a finding: `resources/brainyard/sim/rules.edn`."
  (delay (edn/read-string (slurp (io/resource "brainyard/sim/rules.edn")))))

(defn rules-for
  "The default rules with the plan's own `:rules` added — one with a default's
  `:id` replacing it."
  [plan]
  (let [own (:rules plan)
        ids (set (map :id own))]
    (vec (concat (remove #(ids (:id %)) @default-rules) own))))

(defn listening-in
  "Processes listening on a TCP port whose working directory is `dir` or
  under it: `[{:pid :command :port}]`. Read with lsof; none when it is absent."
  [dir]
  (let [root (.getCanonicalPath (io/file dir))
        r (fx/sh {:out :string :err :string} "lsof" "-nP" "-iTCP" "-sTCP:LISTEN" "-Fpcn")
        procs (loop [[l & more] (str/split-lines (str (:out r))) cur nil out []]
                (cond
                  (nil? l) (if cur (conj out cur) out)
                  (str/starts-with? l "p") (recur more {:pid (subs l 1)} (if cur (conj out cur) out))
                  (str/starts-with? l "c") (recur more (assoc cur :command (subs l 1)) out)
                  (str/starts-with? l "n") (recur more (update cur :ports (fnil conj []) (last (str/split (subs l 1) #":"))) out)
                  :else (recur more cur out)))]
    ;; every listener's working directory in one call: one call per process
    ;; took 3.6 s on a machine with a few dozen listeners
    (let [procs (distinct procs)
          cwds (when (seq procs)
                 (loop [[l & more] (str/split-lines
                                    (str (:out (fx/sh {:out :string :err :string} "lsof" "-a" "-d" "cwd" "-Fpn"
                                                      "-p" (str/join "," (map :pid procs))))))
                        pid nil out {}]
                   (cond (nil? l) out
                         (str/starts-with? l "p") (recur more (subs l 1) out)
                         ;; a deleted directory, on Linux, with ` (deleted)` after it (byj-ahf.29)
                         (str/starts-with? l "n") (recur more pid (assoc out pid (str/replace (subs l 1) #" \(deleted\)$" "")))
                         :else (recur more pid out))))]
      (vec (for [p procs
                 :let [cwd (get cwds (:pid p))]
                 :when (and cwd (or (= cwd root) (str/starts-with? cwd (str root "/"))))]
             {:pid (:pid p) :command (:command p) :port (str/join "," (distinct (:ports p)))})))))

(defn claude-sessions-in
  "Claude Code sessions agent$claude runs in tmux (the person's server, where
  claude-host starts them: `byj-cc-<id>`) whose directory is under `dir` but
  is not `dir` itself — its worktrees: `[{:session :path}]`. After a stage,
  one whose worktree is gone outlived its work (mode B, byj-gly.315.5)."
  [dir]
  (let [root (.getCanonicalPath (io/file dir))
        r (fx/sh {:out :string :err :string :env (dissoc (into {} (System/getenv)) "TMUX")}
                 "tmux" "list-sessions" "-F" (ah/tmux-format "#{session_name}" "#{session_path}"))]
    (vec (for [l (str/split-lines (str (:out r)))
               :let [[n p] (ah/tmux-fields l 2)]
               :when (and (str/starts-with? (str n) "byj-cc-") p
                          (str/starts-with? p (str root "/")))]
           {:session n :path p}))))

(defn- calls-of
  "The model's calls in `records`: a finding is about what it did, and a
  tree's call repeated every iteration — an attachment's lint — is the tree
  working as made (byj-8gc)."
  [records]
  (filter trajectory/model-call? (mapcat (fn [r] (mapcat :tools (:iterations r))) records)))

(defn- refused?
  "A call a gate refused: an error that says so. The word anywhere in a result
  is not one — mode B's successful agent$claude answers counted (byj-gly.315.5)."
  [c]
  (let [s (str (:result c))]
    (boolean (and (str/starts-with? s "{:error") (str/includes? s "refused")))))

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(def detectors
  "Detector → (fn [rule ctx]) → nil, or the detail of a hit. `ctx`: `:stage`
  (its result so far: `:verdict :checks :turns :prompts :metrics`), `:records`
  (its trajectory records), `:answer` (its last answer), `:dir`, `:t0`,
  `:plan`."
  {:claimed-but-failed
   (fn [rule {:keys [stage answer]}]
     (when (and (= :fail (:verdict stage)) (re-find (re-pattern (:pattern rule)) (str answer)))
       (str "failed " (str/join ", " (map :name (remove :pass? (:checks stage))))
            " — while the answer says: " (clip (first (remove str/blank? (str/split-lines (str answer)))) 160))))
   :stuck-turn
   (fn [_ {:keys [stage]}]
     (when-let [t (seq (filter #(#{:timed-out :not-submitted} (:ended %)) (:turns stage)))]
       (str/join "; " (map #(str (name (:ended %)) ": " (:line %)) t))))
   :iteration-limit
   (fn [_ {:keys [records]}]
     (when-let [r (seq (filter #(= :max-iterations-exhausted (:terminated-by %)) records))]
       (str (count r) " turn(s) — " (str/join ", " (distinct (map :agent r))))))
   :repeated-call
   (fn [rule {:keys [records]}]
     (when-let [rep (seq (filter #(>= (val %) (:at-least rule 3))
                                 (frequencies (map (juxt :name :args) (calls-of records)))))]
       (str/join "; " (for [[[n a] k] rep] (str k "× " n " " (clip (get a :command a) 100))))))
   :refused-calls
   (fn [rule {:keys [records]}]
     (let [r (filter refused? (calls-of records))]
       (when (>= (count r) (:at-least rule 2))
         (str (count r) " refused — first: " (clip (get-in (first r) [:args :command] (:args (first r))) 100)))))
   :failed-calls
   (fn [rule {:keys [records]}]
     (let [f (remove refused? (filter failed-call? (calls-of records)))]
       (when (>= (count f) (:at-least rule 3))
         (str (count f) " failed — first: " (clip (get-in (first f) [:args :command] (:args (first f))) 100)))))
   :permission-prompt
   (fn [_ {:keys [stage]}]
     (when (seq (:prompts stage))
       (str/join "; " (map #(str (:asking-for %) " → " (:answer %)) (:prompts stage)))))
   :server-left-running
   (fn [rule {:keys [dir]}]
     (when-let [ps (seq (listening-in dir))]
       (when (:stop? rule) (doseq [p ps] (fx/sh {:out :string :err :string} "kill" (:pid p))))
       (str/join "; " (map #(str (:command %) " (pid " (:pid %) ", port " (:port %) ")"
                                 (when (:stop? rule) " — stopped"))
                           ps))))
   ;; not a stage's rule: run-plan! checks it once the session has ended
   :outlived-session (fn [_ _] nil)
   :path-outside
   (fn [rule {:keys [records dir]}]
     (let [root (.getCanonicalPath (io/file dir))
           allowed (cons root (:allow rule))
           outside (for [c (calls-of records)
                         :let [p (str (get-in c [:args :path]))]
                         :when (and (str/starts-with? p "/")
                                    (not-any? #(or (= p %) (str/starts-with? p (str % "/"))) allowed))]
                     (str (:name c) " " p))]
       (when (seq outside) (str/join "; " (distinct outside)))))
   :attribution
   (fn [rule {:keys [dir t0]}]
     (let [r (fx/sh {:dir (str dir) :out :string :err :string} "git" "log" (str "--since=@" (quot t0 1000)) "--format=%h %B%x00")
           hits (filter #(re-find (re-pattern (:pattern rule)) %) (str/split (str (:out r)) #"\u0000"))]
       (when (seq hits) (str/join "; " (map #(clip (str/trim %) 80) hits)))))
   :sweeping-add
   (fn [_ {:keys [records]}]
     (let [hits (filter #(re-find #"git\s+add\s+(-A|--all|\.)(\s|$|;|&)" (str (get-in % [:args :command]))) (calls-of records))]
       (when (seq hits) (str/join "; " (map #(clip (get-in % [:args :command]) 100) hits)))))
   :asks-the-person
   (fn [_ {:keys [answer]}]
     (let [a (str/trim (str answer))]
       (when (str/ends-with? a "?") (clip (last (str/split-lines a)) 160))))
   :stage-cost
   (fn [rule {:keys [stage plan]}]
     (let [cap (or (:max-usd rule) (:stage-cap-usd plan 2.0))
           c (get-in stage [:metrics :cost-usd])]
       (when (> c cap) (format "$%.2f, past $%.2f" c (double cap)))))})

(defn findings
  "Every rule's hit on a stage: `[{:rule :severity :doc :detail}]`."
  [rules ctx]
  (vec (for [r rules
             :let [f (get detectors (:detector r))
                   detail (when f (try (f r ctx) (catch Throwable e (str "the rule failed: " (ex-message e)))))]
             :when detail]
         {:rule (:id r) :severity (:severity r :warn) :doc (:doc r) :detail detail})))

;; --- comparing runs ----------------------------------------------------------------------

(defn previous-report
  "The latest earlier run of plan `plan-id` under `projects-dir` — the newest
  `<plan-id>-*/.sim/report.edn` other than `dir`'s — read, or nil."
  [projects-dir plan-id dir]
  (let [here (.getCanonicalPath (io/file dir))
        candidates (for [d (or (.listFiles (io/file projects-dir)) [])
                         :when (and (.isDirectory ^java.io.File d)
                                    (str/starts-with? (.getName ^java.io.File d) (str plan-id "-"))
                                    (not= here (.getCanonicalPath ^java.io.File d)))
                         :let [f (io/file d ".sim" "report.edn")]
                         :when (.isFile f)
                         :let [r (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
                         ;; the plan's own runs: playground2-claude-… starts
                         ;; with playground2- too, and was taken for one
                         :when (= plan-id (get-in r [:plan :id]))]
                     [(.lastModified f) r])]
    (second (last (sort-by first candidates)))))

(defn compare-runs
  "Each stage of `cur` beside the same stage of `prev`: `{:against dir :stages
  [{:id :was :now :regressions [...]}]}`. A regression: pass to fail, more
  failed calls, a cost up by half or more, a defect it did not have."
  [prev cur]
  (let [by-id (into {} (map (juxt :id identity)) (:stages prev))
        m #(get-in %1 [:metrics %2])
        defects #(set (map :rule (filter (comp #{:defect} :severity) (:findings %))))]
    {:against (:dir prev)
     :loops (loops-differ prev cur)
     :stages (vec (for [s (:stages cur)
                        :let [p (by-id (:id s))]]
                    (if-not p
                      {:id (:id s) :new? true}
                      {:id (:id s)
                       :was {:verdict (:verdict p) :calls (m p :calls) :failed (m p :failed-calls) :cost (m p :cost-usd) :seconds (:seconds p)}
                       :now {:verdict (:verdict s) :calls (m s :calls) :failed (m s :failed-calls) :cost (m s :cost-usd) :seconds (:seconds s)}
                       :regressions (vec (remove nil?
                                                 [(when (and (= :pass (:verdict p)) (= :fail (:verdict s))) "pass → fail")
                                                  (when (> (m s :failed-calls) (m p :failed-calls))
                                                    (str "failed calls " (m p :failed-calls) " → " (m s :failed-calls)))
                                                  (when (and (pos? (m p :cost-usd)) (>= (m s :cost-usd) (* 1.5 (m p :cost-usd))))
                                                    (format "cost $%.2f → $%.2f" (m p :cost-usd) (m s :cost-usd)))
                                                  (when-let [n (seq (remove (defects p) (defects s)))]
                                                    (str "new defects: " (str/join ", " (map name n))))]))})))}))

;; --- comparing two plans: mode A against mode B ---------------------------------------------

(defn- side
  "What a stage `s` of a run came to, for `compare-modes`; nil when the run has
  no such stage."
  [s]
  (when s
    (let [m (:metrics s)
          n #(count (filter (comp #{%} :severity) (:findings s)))]
      {:verdict (:verdict s)
       :checks [(count (filter :pass? (:checks s))) (count (:checks s))]
       :calls (or (:calls m) 0) :failed (or (:failed-calls m) 0)
       :findings [(n :defect) (n :warn) (n :info)]
       :seconds (or (:seconds s) 0)
       :cost (double (or (:cost-usd m) 0))
       :brainyard-cost (double (or (:brainyard-cost-usd m) (:cost-usd m) 0))
       :claude-code-cost (double (or (:claude-code-cost-usd m) 0))})))

(defn compare-modes
  "Two runs side by side, stage by stage — two plans over the same stages and
  checks, as mode A (brainyard's root agent builds) and mode B (it hands each
  bead to agent$claude) are: `{:a dir :b dir :stages [{:id :a {…} :b {…}}]
  :totals {:a {…} :b {…}}}`, each side what `side` reads. The stages are A's
  order, then any only B has."
  [ra rb]
  (let [ids (distinct (concat (map :id (:stages ra)) (map :id (:stages rb))))
        by #(into {} (map (juxt :id identity)) (:stages %))
        [sa sb] [(by ra) (by rb)]
        total (fn [sides]
                (let [ss (remove nil? sides)
                      sum #(reduce + 0 (map % ss))]
                  {:stages (count ss) :passed (count (filter (comp #{:pass} :verdict) ss))
                   :checks [(sum (comp first :checks)) (sum (comp second :checks))]
                   :calls (sum :calls) :failed (sum :failed)
                   :findings (vec (for [i (range 3)] (sum #(nth (:findings %) i))))
                   :seconds (sum :seconds)
                   :cost (double (sum :cost)) :brainyard-cost (double (sum :brainyard-cost))
                   :claude-code-cost (double (sum :claude-code-cost))}))
        rows (vec (for [id ids] {:id id :a (side (sa id)) :b (side (sb id))}))]
    {:a (:dir ra) :b (:dir rb)
     :plans [(get-in ra [:plan :id]) (get-in rb [:plan :id])]
     :loops (loops-differ ra rb)
     :stages rows
     :totals {:a (total (map :a rows)) :b (total (map :b rows))}}))

(defn compare-modes-md
  "`compare-modes`' result as markdown."
  [{:keys [a b plans stages totals loops]}]
  (let [cell (fn [x]
               (if-not x
                 "— | | | | |"
                 (format "%s | %d/%d | %d (%d failed) | %s | %d s | $%.2f%s"
                         (name (:verdict x)) (first (:checks x)) (second (:checks x)) (:calls x) (:failed x)
                         (str/join "/" (:findings x)) (:seconds x) (:cost x)
                         (if (pos? (:claude-code-cost x))
                           (format " ($%.2f + CC $%.2f)" (:brainyard-cost x) (:claude-code-cost x))
                           ""))))
        hdr "Verdict | Checks | Calls | Findings d/w/i | Time | Cost"]
    (str "# Simulation: " (first plans) " against " (second plans) "\n\n"
         "- A: `" a "`\n- B: `" b "`\n"
         (when-let [[la lb] loops] (str "- A's loop: " (loop-label la) "\n- B's loop: " (loop-label lb) "\n"))
         "\n"
         "| Stage | A " (str/replace hdr " | " " | A ") " | B " (str/replace hdr " | " " | B ") " |\n"
         "|" (str/join (repeat 13 "---|")) "\n"
         (str/join (for [{:keys [id a b]} stages] (str "| " (name id) " | " (cell a) " | " (cell b) " |\n")))
         (str/join (for [[k t] [["A" (:a totals)] ["B" (:b totals)]]]
                     (format "\n%s: %d/%d stages passed, checks %d/%d, %d calls (%d failed), findings %s, %d s, $%.2f (brainyard $%.2f, Claude Code $%.2f)"
                             k (:passed t) (:stages t) (first (:checks t)) (second (:checks t)) (:calls t) (:failed t)
                             (str/join "/" (:findings t)) (:seconds t) (:cost t) (:brainyard-cost t) (:claude-code-cost t))))
         "\n")))

(defn- read-run [dir]
  (let [f (io/file dir ".sim" "report.edn")]
    (when-not (.isFile f) (throw (ex-info (str dir " has no run (.sim/report.edn)") {:dir dir})))
    (edn/read-string (slurp f))))

;; --- a plan --------------------------------------------------------------------------------

(defn load-plan
  "A plan file, its `{{plan}}` (the plan's own directory: its check scripts)
  filled in."
  [file]
  (let [f (io/file file)
        dir (.getCanonicalPath (.getParentFile (.getCanonicalFile f)))]
    (assoc (edn/read-string (str/replace (slurp f) "{{plan}}" dir)) :plan-dir dir :plan-file (.getCanonicalPath f))))

(defn- answer-for
  "The plan's answer to a permission prompt asking about `display`: the first
  `:answers` entry whose `:match` it contains, else `:default-answer`, else no."
  [plan display]
  (or (some (fn [{:keys [match answer]}] (when (str/includes? (str display) match) answer)) (:answers plan))
      (:default-answer plan "n")))

(defn- resume-id [text]
  (last (map second (re-seq #"byj run --resume (\S+)" (str text)))))

(defn- verdict [checks] (if (every? :pass? checks) :pass :fail))

(defn- name* [k] (if (keyword? k) (name k) (str k)))

;; --- a run ---------------------------------------------------------------------------------

(defn run-stage!
  "Play stage `stage`: type its lines into session `name`, each once the turn
  before is over, then run its checks. The stage's result."
  [plan {:keys [dir spent-before]} sess stage t0]
  (let [asked (atom [])
        timeout-ms (* 1000 (or (:timeout-s stage) (:timeout-s plan 1800)))
        answer #(answer-for plan %)
        turns (vec (for [line (:lines stage)]
                     (let [before (:prompts (screen-state (screen sess)))
                           turns-before (turns-started dir)
                           started (System/currentTimeMillis)]
                       (type-line sess line)
                       ;; a line that never left the prompt: no turn will come
                       (if (not-submitted? sess dir line turns-before)
                         {:line (subs line 0 (min 80 (count line)))
                          :seconds (quot (- (System/currentTimeMillis) started) 1000)
                          :ended :not-submitted}
                       (let [st (wait-for sess #(or (#{:exited} (:state %))
                                                    (and (= :ready (:state %)) (> (:prompts %) before)))
                                          timeout-ms answer asked)]
                         ;; the turn may have parked a task: the work goes on
                         (let [settled (when-not (or (:timed-out? st) (= :exited (:state st)))
                                         (settle sess dir (+ started timeout-ms) answer asked))
                               timed-out? (or (:timed-out? st) (:timed-out? settled))]
                           (when timed-out?
                             ;; a turn past its time is a finding; stop it, and wait for the prompt
                             (tmux "send-keys" "-t" sess "C-c")
                             (wait-for sess #(= :ready (:state %)) 60000 answer asked))
                           (cond-> {:line (subs line 0 (min 80 (count line)))
                                    :seconds (quot (- (System/currentTimeMillis) started) 1000)
                                    :ended (cond timed-out? :timed-out (= :exited (:state st)) :exited :else :answered)}
                             (seq (:parked settled)) (assoc :parked (vec (sort (:parked settled)))))))))))
        checks (checks dir (:checks stage))
        records (records-since dir t0)
        ;; what Claude Code spent on the stage's delegations, from agent$claude's
        ;; records: the trajectory holds only brainyard's own calls (byj-gly.315.5)
        delegated (delegated-cost (or spent-before {}) (delegation-costs dir))
        metrics (let [m (stage-metrics records)]
                  (cond-> (assoc m :brainyard-cost-usd (:cost-usd m) :claude-code-cost-usd delegated
                                 :cost-usd (+ (:cost-usd m) delegated))
                    (seq (:notice-markers plan)) (assoc :notices (notice-counts records (:notice-markers plan)))))
        result {:id (:id stage) :verdict (verdict checks) :turns turns :checks checks
                :prompts @asked :metrics metrics
                :seconds (quot (- (System/currentTimeMillis) t0) 1000)}
        ;; its last answer: the last root turn's — a subagent's is its caller's to judge
        answer (some-> (last (remove #(str/includes? (str (:agent %)) "/") records)) trajectory/record-answer)]
    (assoc result
           :answer (clip answer 600)
           :findings (findings (rules-for plan) {:stage result :records records :answer answer
                                                 :dir dir :t0 t0 :plan plan}))))

(defn comparison-md
  "`compare-runs`' result as markdown, under `heading`: the loops when they
  differ, then each stage, was → now, and its regressions."
  [{:keys [against loops stages]} heading]
  (str "\n## " heading "\n\n`" against "`\n\n"
       (when-let [[a b] loops] (str "Loops: was " (loop-label a) "; now " (loop-label b) "\n\n"))
       "| Stage | Verdict | Calls | Failed | Cost | Time | Regressions |\n|---|---|---|---|---|---|---|\n"
       (str/join (for [{:keys [id was now regressions new?]} stages]
                   (if new?
                     (str "| " (name id) " | new | | | | | |\n")
                     (format "| %s | %s → %s | %d → %d | %d → %d | $%.2f → $%.2f | %d → %d s | %s |\n"
                             (name id) (name (:verdict was)) (name (:verdict now)) (:calls was) (:calls now)
                             (:failed was) (:failed now) (double (:cost was)) (double (:cost now))
                             (:seconds was) (:seconds now)
                             (if (seq regressions) (str/join "; " regressions) "none")))))))

(defn- report-md [{:keys [plan dir stages started provider model comparison] :as report}]
  (let [m #(get-in %1 [:metrics %2])]
    (str "# Simulation: " (:id plan) "\n\n"
         "- Project: `" dir "`\n- Model: " provider (when model (str "/" model)) "\n- Started: " started "\n"
         (when-let [l (:loop report)] (str "- Loop: " (loop-label l) "\n"))
         (when (seq (:attachments report))
           (str "- Attached: " (str/join ", " (for [{:keys [slot name hash]} (:attachments report)]
                                                (str name " → " slot " (" hash ")")))
                "\n"))
         "\n"
         "| Stage | Verdict | Time | Turns | Calls | Failed calls | Prompts | Cost | Findings |\n|---|---|---|---|---|---|---|---|---|\n"
         (str/join (for [s stages
                         :let [fs (:findings s)
                               n #(count (filter (comp #{%} :severity) fs))]]
                     (format "| %s | %s | %d s | %d (+%s) | %d | %d | %d | $%.2f | %s |\n"
                             (name (:id s)) (name (:verdict s)) (:seconds s) (m s :turns)
                             (str/join "," (m s :subagents)) (m s :calls) (m s :failed-calls)
                             (count (:prompts s)) (m s :cost-usd)
                             (if (seq fs) (str (n :defect) " defect, " (n :warn) " warn, " (n :info) " info") "none"))))
         (format "\nTotal cost: $%.2f\n" (double (reduce + 0.0 (map #(m % :cost-usd) stages))))
         (str/join (for [s stages]
                     (str "\n## " (name (:id s)) " — " (name (:verdict s)) "\n\n"
                          (str/join (for [c (:checks s)] (str "- " (if (:pass? c) "✓" "✗") " " (:name c) ": " (:detail c) "\n")))
                          (when (pos? (or (m s :tree-calls) 0))
                            (str "- calls the loop's tree made, not the model: " (m s :tree-calls)
                                 " (" (str/join ", " (for [[k n] (m s :tree-calls-by)] (str k " " n))) ")\n"))
                          (when-let [ns (m s :notices)]
                            (str "- notices shown: " (str/join ", " (for [[mk n] ns] (str "\"" mk "\" " n))) "\n"))
                          (str/join (for [a (:attempts s)]
                                      (format "- an earlier attempt: %s, %d s, $%.2f — in this stage's time and cost\n"
                                              (name (:verdict a)) (or (:seconds a) 0) (double (or (get-in a [:metrics :cost-usd]) 0)))))
                          (when (seq (:findings s))
                            (str "\nFindings:\n"
                                 (str/join (for [f (sort-by #(case (:severity %) :defect 0 :warn 1 2) (:findings s))]
                                             (str "- **" (name (:severity f)) "** `" (name (:rule f)) "` — " (:detail f) "\n")))
                                 "\n"))
                          (str/join (for [p (:prompts s)] (str "- permission prompt: `" (:asking-for p) "` → " (:answer p) "\n")))
                          (str/join (for [f (m s :failed)] (str "- failed call: `" (:call f) "` → " (:result f) "\n")))
                          (str/join (for [t (:turns s) :when (not= :answered (:ended t))]
                                      (str "- turn " (name (:ended t)) ": " (:line t) "\n"))))))
         (when comparison (comparison-md comparison "Compared with the last run")))))

(defn- with-comparison
  "`report` compared with the last run of its plan beside it: the runs of a
  plan live in one directory."
  [report]
  (if-let [prev (previous-report (.getParent (io/file (:dir report))) (get-in report [:plan :id]) (:dir report))]
    (assoc report :comparison (compare-runs prev report))
    report))

(defn- write-report! [dir report]
  (spit (io/file dir ".sim" "report.edn") (with-out-str (pp/pprint report)))
  (spit (io/file dir ".sim" "report.md") (report-md report)))

(defn with-attempt
  "Stage `result`, played again after `earlier` failed: `earlier`'s own
  attempts and itself under `:attempts`, and its cost and time added to the
  stage's — that work was paid for, though its result was not kept."
  [result earlier]
  (let [m (:metrics earlier)
        add #(+ (double (or %1 0)) (double (or %2 0)))]
    (-> result
        (assoc :attempts (conj (vec (:attempts earlier))
                               (select-keys earlier [:verdict :seconds :metrics :checks :findings])))
        (update :seconds + (or (:seconds earlier) 0))
        (update-in [:metrics :cost-usd] add (:cost-usd m))
        (update-in [:metrics :brainyard-cost-usd] add (:brainyard-cost-usd m (:cost-usd m)))
        (update-in [:metrics :claude-code-cost-usd] add (:claude-code-cost-usd m)))))

(defn run-plan!
  "Run `plan` (`load-plan`) in a fresh project: each stage in turn, until one
  fails its checks (unless `:keep-going?`) or the run's cost passes
  `:cap-usd`. `opts`: `:dir` (default `<:projects-dir>/<plan id>-<yyyyMMdd-HHmm>`),
  `:stages` (ids to run, in the plan's order), `:provider`, `:model`,
  `:resume?`, `:brainyard-dir` (this checkout, where `jolt run:byj` runs),
  `:loop` (a loop file the project runs instead of the bundled one: made its
  loop before the first stage, as a person's accept makes one; byj-ng7.18).
  The report, also written to `<dir>/.sim/`, says which loop ran (`:loop`).

  `:continue` (a run's directory) goes on with a run that stopped
  (byj-gly.315.17): the stages its report passed are kept; the one that
  failed has its checks run again first — a check fixed since may pass it
  without a turn — else is played again; the rest follow, in the same
  session, resumed."
  [plan & [{:keys [dir stages provider model keep-going?] :as opts}]]
  (when (and (:continue opts) (:loop opts))
    (throw (ex-info "a continued run keeps the loop it began with: --loop is for a new run" {:loop (:loop opts)})))
  (let [prior (when-let [c (:continue opts)]
                (let [f (io/file c ".sim" "report.edn")]
                  (when-not (.isFile f) (throw (ex-info (str c " has no run to continue (.sim/report.edn)") {:dir c})))
                  (assoc (edn/read-string (slurp f)) :dir (.getCanonicalPath (io/file c)))))
        kept (vec (take-while #(= :pass (:verdict %)) (:stages prior)))
        failed-id (some #(when (not= :pass (:verdict %)) (:id %)) (:stages prior))
        resume? (get opts :resume? (:resume? plan true))
        provider (or provider (:provider plan "claude-code"))
        model (or model (:model plan))
        ;; the local time, as the shell has it: jolt's java.time is UTC only
        ;; (provenance P-10, byj-gly.315.13)
        stamp (str/trim (:out (fx/sh {:out :string :err :string} "date" "+%Y%m%d-%H%M")))
        target (or dir (str (str/replace (str (:projects-dir plan "~/Projects/byj-sim")) #"^~" (System/getenv "HOME"))
                            "/" (:id plan) "-" stamp))
        ;; checked before the project is made, against the project it will
        ;; run in, so a loop byj would not run leaves nothing behind
        cand (when-let [f (:loop opts)] (loop-candidate f target))
        attach (when-not prior (plan-attachments plan target))
        dir (if prior
              (:dir prior)
              (cond-> (prepare-project! target)
                ;; the project's own config, as its person would have set it:
                ;; which model Claude Code runs on, say (byj-gly.320)
                (:project-config plan) (doto (write-project-config! (:project-config plan)))
                (:project-files plan) (doto (write-project-files! plan))
                cand (doto (install-loop! cand))))
        attached (if prior (:attachments prior) (when (seq attach) (install-attachments! dir attach)))
        sess (str "sim-" (str/replace (.getName (io/file dir)) #"[^A-Za-z0-9-]" "-"))
        session-opts {:brainyard-dir (or (:brainyard-dir opts) (System/getProperty "user.dir"))
                      :dir dir :provider provider :model model
                      :permission-mode (:permission-mode plan :auto) :extra-args (:extra-args plan)}
        todo (cond prior (remove #(some #{(:id %)} (map :id kept)) (:stages plan))
                   (seq stages) (filter #(some #{(name* (:id %))} (map name* stages)) (:stages plan))
                   :else (:stages plan))
        base (cond-> {:plan (select-keys plan [:id :plan-file]) :dir dir :provider provider :model model
                      :started (or (:started prior) (str (java.time.Instant/now)))
                      :tmux (str (str/join " " *tmux*) " attach -t " sess)}
               prior (assoc :continued (str (java.time.Instant/now)))
               (seq attached) (assoc :attachments attached)
               true (assoc :loop (if prior
                                   (:loop prior)
                                   (loop-in-force dir (or (:name cand) "react") (:file cand)))))
        first-session (some :session (reverse (:stages prior)))]
    (start-session! sess (cond-> session-opts first-session (assoc :resume first-session)))
    (wait-for sess #(#{:ready :exited} (:state %)) 120000 (constantly "n") (atom []))
    (loop [[stage & more] todo done kept session-id first-session]
      (if-not stage
        (let [report (with-comparison (assoc base :stages done))]
          (when resume? (tmux "kill-session" "-t" sess))
          (write-report! dir report)
          report)
        (let [t0 (System/currentTimeMillis)
              spent-before (delegation-costs dir)
              ;; the session was started resumed for the first stage of a continued run;
              ;; after a stage it was quit, so the next one needs a session — a
              ;; fresh one when there is no id to resume: a session that ran only
              ;; commands (/init) holds no turn and says none at /quit, and its
              ;; lines went to the exited pane until the stage timed out (byj-gly.340)
              _ (when (and resume? (not (and prior (= (count done) (count kept))))
                           (or session-id (seq done)))
                  (start-session! sess (cond-> session-opts session-id (assoc :resume session-id)))
                  (wait-for sess #(#{:ready :exited} (:state %)) 120000 (constantly "n") (atom [])))
              rechecked (when (and failed-id (= failed-id (:id stage)))
                          (let [checks (checks dir (:checks stage))]
                            (when (every? :pass? checks)
                              ;; the work was the failed attempt's: its turns, cost
                              ;; and time stay; a claim the checks now bear out is
                              ;; no longer a finding
                              (-> (or (some #(when (= failed-id (:id %)) %) (:stages prior))
                                      {:id (:id stage) :turns [] :prompts [] :metrics (stage-metrics []) :seconds 0})
                                  (assoc :verdict :pass :rechecked true :checks checks)
                                  (update :findings #(vec (remove (comp #{:claimed-but-failed} :rule) %)))))))
              ;; a failed stage of a continued run, played again: its failed
              ;; attempt stays in the report, and in what the stage cost (byj-gly.319)
              earlier (when (and failed-id (= failed-id (:id stage)) (not rechecked))
                        (some #(when (= failed-id (:id %)) %) (:stages prior)))
              result (or rechecked
                         (cond-> (run-stage! plan {:dir dir :spent-before spent-before} sess stage t0)
                           earlier (with-attempt earlier)))
              text (screen sess)
              _ (spit (io/file dir ".sim" (str (name* (:id stage)) ".screen.txt")) text)
              sid (if resume?
                    (do (type-line sess "/quit")
                        (wait-for sess #(= :exited (:state %)) 60000 (constantly "n") (atom []))
                        (or (resume-id (screen sess)) session-id))
                    session-id)
              ;; once the session has ended, nothing it started should be
              ;; listening: what is, outlived it (byj-gly.315.16)
              result (let [left (when resume? (listening-in dir))
                           ;; a Claude Code session left in a worktree the stage removed
                           cc (when resume? (filter #(not (.isDirectory (io/file (:path %)))) (claude-sessions-in dir)))
                           rule (some #(when (= :outlived-session (:id %)) %) (rules-for plan))]
                       (doseq [p left] (fx/sh {:out :string :err :string} "kill" (:pid p)))
                       (doseq [c cc] (fx/sh {:out :string :err :string :env (dissoc (into {} (System/getenv)) "TMUX")}
                                            "tmux" "kill-session" "-t" (:session c)))
                       (if (and rule (or (seq left) (seq cc)))
                         (update result :findings conj
                                 {:rule :outlived-session :severity (:severity rule :defect) :doc (:doc rule)
                                  :detail (str/join "; " (concat (map #(str (:command %) " (pid " (:pid %) ", port " (:port %) ") — stopped") left)
                                                                 (map #(str "Claude Code session " (:session %) " in removed " (:path %) " — stopped") cc)))})
                         result))
              done (conj done (assoc result :session sid))
              spent (reduce + (map #(get-in % [:metrics :cost-usd]) done))
              stop? (or (and (= :fail (:verdict result)) (not (or keep-going? (:keep-going? plan))))
                        (> spent (:cap-usd plan 10.0)))]
          (write-report! dir (assoc base :stages done))
          (if stop?
            (do (tmux "kill-session" "-t" sess)
                (let [report (with-comparison (assoc base :stages done :stopped (if (= :fail (:verdict result)) :stage-failed :cap-reached)))]
                  (write-report! dir report)
                  report))
            (recur more done sid)))))))

(defn -main
  "`jolt sim:byj compare RUN-A RUN-B` (exit 1 when B regressed against A), or
  `jolt sim:byj PLAN [--dir DIR | --continue DIR] [--stages a,b] [-p PROVIDER]
  [-m MODEL] [--loop FILE] [--keep-going] [--no-resume]`."
  [& args]
  (when (= "compare" (first args))
    (let [[_ da db] args]
      (when-not (and da db) (println "usage: jolt sim:byj compare RUN-A RUN-B") (System/exit 2))
      (let [[ra rb] [(read-run da) (read-run db)]
            c (compare-runs ra rb)]
        (println (compare-modes-md (compare-modes ra rb)))
        (println (comparison-md c "B against A, by RFC-041's regressions"))
        (shutdown-agents)
        ;; a regression fails the comparison: what a promotion's harm check
        ;; reads (RFC-071 "Promotion", byj-ng7.18)
        (System/exit (if (some (comp seq :regressions) (:stages c)) 1 0)))))
  (let [[plan-file & more] args
        opts (loop [[a b & r :as xs] more o {}]
               (case a
                 nil o
                 "--dir" (recur r (assoc o :dir b))
                 "--stages" (recur r (assoc o :stages (str/split b #",")))
                 ("-p" "--provider") (recur r (assoc o :provider b))
                 ("-m" "--model") (recur r (assoc o :model b))
                 "--keep-going" (recur (rest xs) (assoc o :keep-going? true))
                 "--no-resume" (recur (rest xs) (assoc o :resume? false))
                 "--continue" (recur r (assoc o :continue b))
                 "--loop" (recur r (assoc o :loop b))
                 (do (println "unknown option" a) (System/exit 2))))]
    (when-not plan-file (println "usage: jolt sim:byj PLAN [--dir DIR | --continue DIR] [--stages a,b] [-p P] [-m M] [--loop FILE] [--keep-going] [--no-resume]") (System/exit 2))
    (let [report (run-plan! (load-plan plan-file) opts)]
      (println (slurp (io/file (:dir report) ".sim" "report.md")))
      (shutdown-agents)
      (System/exit (if (every? #(= :pass (:verdict %)) (:stages report)) 0 1)))))
