(ns brainyard.claude-agent
  "The `claude$*` commands: what agent$claude's tree calls to hand work to a
  Claude Code session and watch it (RFC-039, byj-gly.290.3). Mechanism only.
  Which session takes a task, what it is told, and whether its work is done
  are the tree's and its predictors' (`resources/brainyard/loops/claude.edn`,
  `predictors/claude/`); these commands do what they are told and report what
  they see.

  They are registered only when `:claude-tools?` is on, which agent$claude's
  own config sets, so the commands cost no other turn its prompt.

  Every delegation leaves a record, `.brainyard/claude-agents/<session-id>.edn`
  — the task, the worktree and branch, the round, the state and the last
  report — which the agent page reads (`.5`) and which lets the same agent
  instance find its session again when it is asked to continue. Guidance too
  long for one typed line is a file beside it, which Claude Code is given
  access to with `--add-dir`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.claude-host :as host]
            [brainyard.claude-sessions :as cs]
            [brainyard.cost :as cost]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.json :as json]
            [brainyard.worktree :as worktree]))

;; --- the run records ---------------------------------------------------------------------

(defn- project [config] (or (:project-dir config) (:working-dir config) "."))

(defn records-dir [config] (io/file (project config) ".brainyard" "claude-agents"))

(defn- record-file [config sid] (io/file (records-dir config) (str sid ".edn")))

(defn read-record [config sid]
  (let [f (record-file config sid)]
    (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))))

(defn records
  "Every delegation's record, newest first."
  [config]
  (->> (or (.listFiles (records-dir config)) [])
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (keep #(try (edn/read-string (slurp %)) (catch Throwable _ nil)))
       (sort-by :updated-at >)
       vec))

(defn- update-record! [config sid f & args]
  (let [file (record-file config sid)]
    (io/make-parents file)
    (let [r (assoc (apply f (or (read-record config sid) {:session-id sid}) args)
                   :updated-at (System/currentTimeMillis))]
      (spit file (pr-str r))
      r)))

(defn delegation
  "What the agent page shows of a delegation's record: `{:bead :branch :state
  :round :pr-url :verdict}`, the keys it has."
  [r]
  (not-empty (into {} (filter (comp some? val)) (select-keys r [:bead :branch :state :round :pr-url :verdict]))))

(defn with-delegations
  "`sessions` (`claude-sessions/sessions`'s `{:live :saved}`) with each one a
  delegation drove carrying it as `:delegation` (byj-gly.290.5)."
  [sessions records]
  (let [by-id (into {} (keep (fn [r] (when-let [d (delegation r)] [(:session-id r) d]))) records)
        mark (fn [ss] (mapv #(if-let [d (by-id (:id %))] (assoc % :delegation d) %) ss))]
    (-> sessions (update :live mark) (update :saved mark))))

;; --- what a session has cost -------------------------------------------------------------

(defn usage-cost
  "What `usage` (`claude-sessions/last-turn`'s, by model) cost by the price
  table, in USD; a model the table does not price adds nothing."
  [config usage]
  (let [table (cost/table config)]
    (reduce + 0.0
            (for [[model {:keys [fresh cache-read cache-write out]}] usage
                  :let [c (when (cost/rates-for table :anthropic model)
                            (:total-cost (cost/of table {:input-tokens (+ fresh cache-read cache-write)
                                                         :output-tokens out
                                                         :cache {:read-tokens cache-read :write-tokens cache-write}}
                                                  :anthropic model)))]
                  :when c]
              c))))

;; --- the commands' work ------------------------------------------------------------------

(defn- home [config] (:claude-home config))

(defn- slug [s]
  (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" "")))

(defn- run [task ms]
  ;; a command is a synchronous handler: the edge where a task is waited on
  (let [r (fx/run!! task ms)]
    (cond (contains? r :ok) (:ok r)
          (:timeout r) {:error (str "no answer within " ms " ms")}
          (:err r) {:error (str (ex-message (:err r)))}
          :else {:error "interrupted"})))

(defn- mine? [config r]
  (and (:agent-instance-id config) (= (:agent-instance-id config) (:instance-id r))))

(defn sessions
  "The sessions a task could go to, and how each stands: running here (it can
  be typed to), in another terminal (it cannot), or saved (it can be resumed);
  whether this agent instance already drives it, and for what task. `:text` is
  the same as lines, for a predictor to read."
  [config]
  (let [{:keys [live saved]} (host/with-running (cs/sessions (project config) :home (home config))
                                                (host/running-in (project config)))
        here (host/running)
        recs (into {} (map (juxt :session-id identity)) (records config))
        row (fn [where s]
              (let [r (recs (:id s))]
                (cond-> {:id (:id s) :title (:title s) :where where}
                  (= where "live") (assoc :status (:status s) :where (if (here (:id s)) "running here" "another terminal"))
                  (:dir s) (assoc :dir (:dir s))
                  r (assoc :task (:task r) :state (:state r) :branch (:branch r) :bead (:bead r))
                  (mine? config r) (assoc :mine? true))))
        rows (concat (map #(row "live" %) live) (map #(row "saved" %) (take 15 saved)))]
    {:sessions (vec rows)
     :text (if (empty? rows)
             "(no Claude Code sessions in this project)"
             (str/join "\n" (for [r rows]
                              (str "- " (:id r) " | " (:where r) (some->> (:status r) (str " "))
                                   (when (:mine? r) " | YOURS")
                                   (when (and (:state r) (not (:mine? r)))
                                     (str " | delegated earlier" (some->> (:bead r) (str " for "))
                                          ", " (:state r)))
                                   (some->> (:branch r) (str " | branch "))
                                   (some->> (:dir r) (str " | in "))
                                   " | " (or (:title r) "untitled")
                                   (some->> (:task r) (str " | was given: "))))))}))

(defn add-worktree
  "A worktree for `branch` under `.brainyard/worktrees/`, or the one already
  there for it — a delegation continued finds its worktree again."
  [config branch]
  (let [branch (str/trim (str branch))
        dir (io/file (project config) ".brainyard" "worktrees" (slug branch))]
    (cond
      (str/blank? branch) {:error "a worktree needs a branch name"}
      (worktree/common-dir dir) {:dir (.getCanonicalPath dir) :branch branch :existing true}
      :else (worktree/add! (project config) dir branch))))

(defn- guidance-file [config sid text]
  (let [d (io/file (records-dir config) sid)
        n (inc (count (filter #(str/starts-with? (.getName ^java.io.File %) "guidance-") (or (.listFiles d) []))))
        f (io/file d (str "guidance-" n ".md"))]
    (io/make-parents f)
    (spit f text)
    (.getCanonicalPath f)))

(def typed-chars
  "How long a message may be and still be typed as it is; past it, or with a
  line break, it goes in a file the typed line points at (RFC-039: a paste is
  taken for data, a long one arrives cut)."
  300)

(defn say
  "Deliver `text` to session `sid` as the person's own words: typed when it is
  one short line, else written to a file and a line typed that points at it."
  [config sid text]
  (let [text (str/trim (str text))
        line (if (or (> (count text) typed-chars) (str/includes? text "\n"))
               (str "Read " (guidance-file config sid text) " and follow it — it is from the person who gave you this task.")
               text)]
    (if (str/blank? text)
      {:error "nothing to say"}
      (let [r (run (host/type-line sid line {:home (home config)}) 15000)]
        (if (:error r) r (do (update-record! config sid assoc :state "working") {:sent true :via (if (= line text) "typed" "file")}))))))

(defn- arg-list [args]
  (cond (sequential? args) (vec (map str args))
        (str/blank? (str args)) []
        :else (str/split (str/trim (str args)) #"\s+")))

(def denied-in-session
  "What a delegated session may not run, whatever it is told: pushing and
  opening, merging or readying a pull request are done after it, by whoever
  gave agent$claude the task, from its hand-off (`handoff`, byj-gly.307).
  Claude Code's own deny rules, so no dialog is offered for them — they are
  refused. They are prefix rules, so `git -C dir push` passes them:
  `publish-guard` is what catches the rest."
  ["Bash(git push)" "Bash(git push:*)"
   "Bash(gh pr create:*)" "Bash(gh pr merge:*)" "Bash(gh pr ready:*)"])

(def denied-when-publishing
  "What a session told to publish may still not run: it pushes and opens the
  pull request, but merging is a reviewer's (byj-gly.307)."
  ["Bash(gh pr merge:*)"])

(def allowed-when-publishing
  "What a session told to publish is allowed outright: auto mode's classifier
  refused `git push … && gh pr create …` as an external write, live, though the
  task said to (byj-gly.308, provenance P-33)."
  ["Bash(git push:*)" "Bash(gh pr create:*)" "Bash(gh pr view:*)"])

(defn- read-settings
  "`:claude-settings` as a map: a JSON object given inline, or a file holding
  one; nil when unset or unreadable."
  [v]
  (let [v (str/trim (str v))]
    (when-not (str/blank? v)
      (try (let [m (json/read-str (if (str/starts-with? v "{") v (slurp v)))] (when (map? m) m))
           (catch Throwable _ nil)))))

(def publish-guard
  "A Claude Code `PreToolUse` hook, as a bash script, that denies a Bash
  command which pushes or opens, merges or readies a pull request ANYWHERE in
  its line — `git -C dir push`, `cd x && git push`, `env A=1 git push`,
  `sh -c \"git push\"`, `/usr/bin/git push`, `gh -R o/r pr create` — which `denied-in-session`'s
  prefix rules miss (byj-gly.290.7). A tripwire over the whole line, as
  `:bash-deny-commands` is (RFC-012): it may refuse a line that only mentions a
  push (`echo git push`), never the reverse. It reads the hook's JSON payload as
  text, a JSON-escaped line break as a break, and needs nothing but bash: it
  runs before every Bash call the session makes, so it must start in
  milliseconds, which a jolt process does not."
  (str/join "\n"
            ["#!/bin/bash"
             "# Written by brainyard (claude-agent/publish-guard, RFC-039): a delegated session does not publish."
             "p=$(cat)"
             "[[ \"$p\" == *'\"tool_name\":\"Bash\"'* || \"$p\" == *'\"tool_name\": \"Bash\"'* ]] || exit 0"
             "p=${p//\\\\n/ }; p=${p//\\\\t/ }"
             "b='(^|[^A-Za-z0-9_./-])'; e='([^A-Za-z0-9_-]|$)'; s='[[:space:]]+'; w='[^[:space:]]+'"
             ;; a path before the program: /usr/bin/git
             "d='([^[:space:]\"]*/)?'"
             "git=\"${b}${d}git(${s}(-C${s}${w}|-c${s}${w}|--[a-z-]+(=${w})?))*${s}push${e}\""
             "gh=\"${b}${d}gh(${s}(-R${s}${w}|--repo(=|${s})${w}))*${s}pr${s}(create|merge|ready)${e}\""
             "if [[ \"$p\" =~ $git || \"$p\" =~ $gh ]]; then"
             "  printf '%s\\n' '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"Pushing and pull requests are not this session'\"'\"'s to do: stop and report that the work is ready, with its commits; it is published after you.\"}}'"
             "fi"
             ""]))

(def default-allow
  "The commands a delegated session may run without asking, as Claude Code
  permission rules: `resources/brainyard/claude/allow.edn` (byj-gly.292)."
  (delay (edn/read-string (slurp (io/resource "brainyard/claude/allow.edn")))))

(defn allowed-in-session
  "What a delegated session is allowed: `default-allow` unless
  `:claude-allow-defaults?` is false, and the project's `:claude-allow`."
  [config]
  (vec (distinct (concat (when (:claude-allow-defaults? config true) @default-allow)
                         (:claude-allow config)))))

(def no-attribution
  "Claude Code's `attribution` setting that hides what it adds to a commit
  (the `Co-Authored-By` trailer), a pull request's description, and either's
  session link: empty strings, and `sessionUrl` false (Claude Code 2.1.294)."
  {:commit "" :pr "" :sessionUrl false})

(defn session-settings
  "The `--settings` file a delegated session starts with: `:claude-settings`,
  if any, with `allowed-in-session` added to its `permissions.allow`,
  `denied-in-session` to its `permissions.deny` — which Claude Code puts above
  any allow — and `publish-guard` to its `PreToolUse` hooks. Its path.

  Claude Code's own attribution is hidden — `no-attribution` — unless the
  settings set it or `:claude-attribution?` is on: live, a delegated session's
  commit carried a `Co-Authored-By: Claude …` trailer (byj-gly.308).

  `publish?`: for a session whose task tells it to push and open the pull
  request itself (byj-gly.307) — `allowed-when-publishing` too, only
  `denied-when-publishing`, no guard, and a
  file of its own, so a session started meanwhile without the right does not
  read this one's."
  [config & [publish?]]
  (let [guard (io/file (records-dir config) "publish-guard.sh")
        _ (io/make-parents guard)
        _ (spit guard publish-guard)
        _ (.setExecutable guard true)
        m (or (read-settings (:claude-settings config)) {})
        m (-> m
              (cond-> (and (not (contains? m :attribution)) (not (:claude-attribution? config)))
                (assoc :attribution no-attribution))
              (update-in [:permissions :allow] #(vec (distinct (concat % (allowed-in-session config)
                                                                       (when publish? allowed-when-publishing)))))
              (update-in [:permissions :deny] #(vec (distinct (concat % (if publish? denied-when-publishing denied-in-session)))))
              (cond-> (not publish?)
                (update-in [:hooks :PreToolUse]
                           #(conj (vec %) {:matcher "Bash"
                                           :hooks [{:type "command" :command (.getCanonicalPath guard)}]}))))
        f (io/file (records-dir config) (if publish? "session-settings-publish.json" "session-settings.json"))]
    (spit f (json/write-str m))
    (.getCanonicalPath f)))

(defn- flag? [v] (contains? #{true "true" "yes"} (if (string? v) (str/lower-case (str/trim v)) v)))

(defn- start-opts [config sid dir {:keys [model args publish]}]
  {:id sid :dir dir :claude (:claude-command config "claude")
   ;; the plan's, else the project's (byj-gly.320), else Claude Code's own
   :model (or (not-empty (str/trim (str model))) (not-empty (str/trim (str (:claude-model config)))))
   :permission-mode (:claude-permission-mode config "auto")
   :settings (session-settings config (flag? publish))
   ;; the guidance files are outside the worktree
   :args (into ["--add-dir" (.getCanonicalPath (doto (records-dir config) .mkdirs))] (arg-list args))})

(defn open
  "Hand `brief` to a session: `session` \"new\" starts one in `dir` (the project
  when not given; a worktree of its own on `branch` with `worktree`) with the
  brief as its first prompt; the id of one running here gives it the brief as
  the next message (it must be idle); the id of a saved one resumes it, then
  gives it the brief. `{:session-id :dir :how :where}` — `:where` in words, for
  the report — or `{:error}`.

  `publish`: the task tells the session to push and open its pull request
  itself (byj-gly.307), so it starts with the settings that let it
  (`session-settings`). One running here without them is stopped and resumed
  with them — once its turn is over: Claude Code reads its settings when it
  starts."
  [config {:keys [session dir brief task branch worktree bead publish] :as args}]
  (let [session (str/trim (str session))
        h (home config)
        new? (or (str/blank? session) (= "new" session))
        sid (if new? (str (java.util.UUID/randomUUID)) session)
        publish? (flag? publish)
        restart (when (and publish? (not new?) (not (str/blank? (str brief))) (contains? (set (host/running)) sid)
                           (not (:publish (read-record config sid))))
                  (if (= "busy" (:status (host/record sid h)))
                    {:error (str "session " sid " is working: it can be given the right to publish once its turn is over")}
                    (host/stop! sid)))
        live (set (host/running))
        saved-now #(into {} (map (juxt :id identity)) (:saved (cs/sessions (project config) :home h)))
        ;; a session just stopped is still live while its process exits, so it
        ;; is not yet among the saved: wait a moment for it (provenance P-31) —
        ;; only one with a live record, of the process still exiting; an id
        ;; known nowhere is refused at once, not after 5 s (byj-gly.442)
        saved (let [s (saved-now)]
                (if (or new? (live sid) (s sid) (nil? (host/record sid h)))
                  s
                  (let [r (run (fx/poll-until 200 ::not-yet #(let [s (saved-now)] (if (s sid) s ::not-yet))) 5000)]
                    (if (map? r) (if (:error r) s r) s))))
        wt (when (and new? worktree (not (str/blank? (str brief))))
             (add-worktree config (or (not-empty (str/trim (str branch))) (str "cc/" (subs sid 0 8)))))
        ;; a branch is the delegation's only when its worktree is on it, or
        ;; the session continued already had one
        branch (or (:branch wt) (when-not new? (:branch (read-record config sid))))
        dir (or (:dir wt)
                (not-empty (str dir))
                (when-not new? (some-> (or (saved sid) (host/record sid h)) ((some-fn :dir :cwd))))
                (.getCanonicalPath (io/file (project config))))
        want (:claude-permission-mode config "auto")
        ;; the mode the session is in, once it is up: not always the one asked for
        mode (atom nil)
        begin! (fn [how]
                 (let [baseline (usage-cost config (:usage (second (host/current-turn sid dir h))))]
                   (update-record! config sid merge
                                   {:session-id sid :instance-id (:agent-instance-id config)
                                    ;; the session that delegated it (byj-gly.298.1)
                                    :parent-session (:session-id config)
                                    :task (or task brief)
                                    :dir dir :branch branch :bead (not-empty (str/trim (str bead))) :how how :state "working"
                                    :permission-mode (or (:mode @mode) (:permission-mode (read-record config sid)))
                                    ;; whether it was started with the right to publish
                                    :publish (if (= how "continued") (boolean (:publish (read-record config sid))) publish?)
                                    ;; the spend counts from here, and what await has said of it
                                    ;; starts over too: a continued session's record kept the last
                                    ;; delegation's, and the first wait's :usage-cost-usd came out
                                    ;; as the difference (byj-1wv.17)
                                    :round 0 :baseline-cost-usd baseline :cost-usd 0.0 :started-at (System/currentTimeMillis)})
                   {:session-id sid :dir dir :how how :branch (or branch "")
                    :where (str "Claude Code session " sid " (tmux " (host/tmux-name sid) ") in " dir
                                (when branch (str ", branch " branch))
                                (when (:stopped restart) "; started again with the right to push and open its pull request")
                                (let [{m :mode w :wanted} @mode]
                                  (when (and m w) (str "; permission mode " m ", since " w " is not offered for its model"))))}))
        up (fn [] (let [r (run (host/ready sid {:home h :timeout-ms 30000}) 40000)]
                    (when-not (:error r)
                      (reset! mode (run (host/ensure-mode sid want "acceptEdits") 15000)))
                    r))
        ;; a resumed session writes its record before it says it is idle: the
        ;; brief typed in between was refused, live (byj-gly.290.6)
        idle (fn [] (let [r (run (fx/poll-until 200 ::not-yet #(if (= "idle" (:status (host/record sid h))) :idle ::not-yet))
                                 30000)]
                      (when (:error r) {:error (str "session " sid " did not become idle after it resumed")})))]
    (cond
      (str/blank? (str brief)) {:error "open needs a brief"}
      (:error restart) restart
      (:error wt) wt

      new?
      (let [s (host/start! (assoc (start-opts config sid dir args) :prompt brief))]
        (if (:error s) s (let [r (up)] (if (:error r) r (begin! "new")))))

      (live sid)
      (let [r (say config sid brief)] (if (:error r) r (begin! "continued")))

      (saved sid)
      (let [s (host/start! (assoc (start-opts config sid dir args) :resume? true))
            r (when-not (:error s) (up))]
        (cond (:error s) s
              (:error r) r
              :else (let [b (begin! "resumed") said (or (idle) (say config sid brief))]
                      (if (:error said) said b))))

      ;; running, but not here: in a terminal of its own, or a tmux pane byj
      ;; did not start. It is not resumed — a second process on one session —
      ;; and not driven: session$send hands it a task when it runs in tmux
      ;; (byj-gly.429)
      (some #(= sid (:id %)) (cs/live (project config) h))
      {:error (str "session " sid " is running outside this agent's own tmux sessions, so it is not resumed (that would be a second process on one session) "
                   "nor driven from here. session$send claude:" sid " hands it a task when it runs in tmux; else give \"new\".")}

      :else {:error (str "no session " sid " here: give \"new\", or the id of one running here or saved")})))

(defn await-turn
  "Wait until session `sid`'s turn is over, at most `timeout-ms`: where it
  stands (`host/await-settled`, `:settled` done | waiting | gone | timeout),
  the round, what the delegation has cost since it began, and whether that is
  past `max-cost-usd`. Strings where a predictor reads them."
  [config {:keys [session-id timeout-ms max-cost-usd]} & [ctx]]
  (let [h (home config)
        rec (read-record config session-id)
        dir (or (:dir rec) (project config))
        timeout-ms (or timeout-ms (:claude-turn-timeout-ms config 1800000))
        ;; what Claude Code does while it is waited on, as the step's progress
        ;; (byj-gly.299): only what it does from now on
        seen (atom (or (some-> (host/observe session-id {:home h :dir dir}) :activity last :n) 0))
        on-observe (when (:on-event ctx)
                     (fn [o]
                       (doseq [{:keys [n line]} (:activity o) :when (> n @seen)]
                         (reset! seen n)
                         (events/emit! ctx :agent.tree/step-progress {:line line}))))
        o (let [r (run (host/await-settled session-id {:home h :dir dir :on-observe on-observe
                                                        :settle-ms (:claude-settle-ms config 1500)})
                       timeout-ms)]
            (if (:error r) (assoc (host/observe session-id {:home h :dir dir}) :settled :timeout) r))
        spent (max 0.0 (- (usage-cost config (:usage o)) (or (:baseline-cost-usd rec) 0.0)))
        ;; what this wait added to the delegation's spend: the turn that
        ;; called counts it in its own usage (agent/account-tool-cost!,
        ;; byj-1wv.17), so the delegation's cost is the turn's too
        since (max 0.0 (- spent (or (:cost-usd rec) 0.0)))
        cap (or max-cost-usd (:claude-max-cost-usd config 5.0))
        rec (update-record! config session-id #(-> % (update :round (fnil inc 0))
                                                   (assoc :state (name (:settled o)) :cost-usd spent)))]
    {:settled (name (:settled o))
     :answer (or (:answer o) "")
     :prompt (or (:prompt o) "")
     :screen (or (:screen o) "")
     :round (str (:round rec))
     :cost-usd spent
     :usage-cost-usd since
     :over-budget (if (> spent cap) (format "yes: $%.2f spent, the cap is $%.2f" spent (double cap)) "no")}))

(defn choose
  "Answer the dialog on session `sid`'s screen with choice `choice` — the
  person's answer to what the session asked, carried back by its caller.
  `{:chosen :session-id :where}`, or `{:error}`."
  [config sid choice]
  (let [r (run (host/choose sid (str/trim (str choice)) {:home (home config)}) 5000)
        rec (read-record config sid)]
    (if (:error r)
      r
      (do (update-record! config sid assoc :state "working")
          (assoc r :session-id sid :branch (or (:branch rec) "")
                 :where (str "Claude Code session " sid " (tmux " (host/tmux-name sid) ") in "
                             (or (:dir rec) (project config))
                             (when-let [b (:branch rec)] (str ", branch " b))))))))

(defn- sh-in [dir & argv] (apply fx/sh {:dir (str dir) :out :string :err :string} argv))

(defn- out-of [r] (when (zero? (:exit r)) (str/trim (str (:out r)))))

(defn- err-of [r] (str/trim (str (:err r) (:out r))))

(defn- open-pr
  "The URL of `branch`'s pull request, when `gh` finds one."
  [dir gh branch]
  (try (some-> (out-of (apply sh-in dir (concat gh ["pr" "view" branch "--json" "url"]))) json/read-str :url)
       (catch Throwable _ nil)))

(defn- lines [r] (some-> (out-of r) not-empty str/split-lines))

(defn- quoted
  "`s` as one shell word, for a command the hand-off gives."
  [s]
  (let [s (str s)]
    (if (re-matches #"[A-Za-z0-9_./:@=+,-]+" s) s (str "'" (str/replace s "'" "'\\''") "'"))))

(defn- indented [xs] (str/join "\n" (map #(str "  " %) xs)))

(defn- clean-up!
  "Remove a published delegation's worktree and its local branch, the work
  being on the remote and in its pull request (byj-gly.307): its session is
  stopped first, its directory going. A worktree holding files git does not
  track is left as it is — they may be work — and the report says so. Words."
  [config sid dir branch]
  (let [root (project config)
        untracked (lines (sh-in dir "git" "ls-files" "--others" "--exclude-standard"))]
    (if (seq untracked)
      (str "Not cleaned up: the worktree has files git does not track ("
           (str/join ", " (take 5 untracked)) (when (> (count untracked) 5) ", …")
           "). Look at them, then: git -C " (quoted root) " worktree remove --force " (quoted dir)
           " && git -C " (quoted root) " branch -D " (quoted branch))
      (do (when (contains? (set (host/running)) sid)
            (host/stop! sid)
            ;; it is in the directory until it has exited
            (run (fx/poll-until 200 ::not-yet #(if (host/record sid (home config)) ::not-yet :gone)) 10000))
          (let [rm (sh-in root "git" "worktree" "remove" dir)
                del (when (zero? (:exit rm)) (sh-in root "git" "branch" "-D" branch))]
            (cond
              (not (zero? (:exit rm))) (str "Not cleaned up: git worktree remove failed — " (err-of rm))
              (not (zero? (:exit del))) (str "Removed the worktree " dir ", but not the branch: " (err-of del))
              :else (do (update-record! config sid assoc :cleaned-up true)
                        (str "Cleaned up: removed the worktree " dir " and the local branch " branch
                             " (stopped its session; it is on the remote and in the pull request)."))))))))

(defn handoff
  "What a finished delegation hands back about its branch (byj-gly.307): the
  commits past the base (the project's current branch), the files they change,
  anything left uncommitted, and then one of two things.

  - The task did not tell the session to publish (`publish` false, the
    default): nothing is pushed here and no one is asked — publishing is for
    whoever gave agent$claude the task. The commands that do it: the push, and
    the pull request with `title` and `body` (written to a file beside the
    delegation's record), or the one already open, which the push updates.
  - It did: what the session published — the push, as the remote's ref has it,
    and the pull request `gh` finds, or what is missing and the commands that
    finish it. With `cleanup`, once both are there and nothing is left
    uncommitted, the worktree and local branch are removed (`clean-up!`).

  Never pushes, never merges. `{:summary :pr-url?}`; `:summary` in words,
  whatever happened."
  [config {:keys [session-id publish cleanup title body]}]
  (let [rec (read-record config session-id)
        {:keys [dir branch]} rec
        root (project config)
        remote (:claude-git-remote config "origin")
        gh-cmd (str/trim (str (:claude-gh-command config "gh")))
        base (or (out-of (sh-in root "git" "symbolic-ref" "--short" "HEAD")) "main")
        log (when (and dir branch) (sh-in dir "git" "log" "--reverse" "--format=%h %s" (str base ".." branch)))
        commits (some-> log lines)
        n (count commits)
        ;; tracked files only: what a session's tools leave lying about (a
        ;; plugin's state directory, live) is not work left uncommitted
        dirty (when dir
                (let [r (sh-in dir "git" "status" "--porcelain" "--untracked-files=no")]
                  (when (zero? (:exit r)) (not-empty (remove str/blank? (str/split-lines (str (:out r))))))))
        publish? (flag? publish)]
    (cond
      (nil? rec) {:error (str "no delegation " session-id) :summary "No hand-off: there is no record of that session."}
      (str/blank? (str branch)) {:summary "No hand-off: the work has no branch of its own."}
      (or (nil? log) (not (zero? (:exit log)))) {:error (some-> log err-of)
                                 :summary (str "No hand-off: " branch " could not be compared with " base ".")}
      (zero? n) {:summary (str "Nothing to publish: " branch " has no commits past " base
                               (when (seq dirty) (str "; its changes are not committed, in " dir)) ".")}
      :else
      (let [url (open-pr dir (str/split gh-cmd #"\s+") branch)
            tip (out-of (sh-in dir "git" "rev-parse" branch))
            on-remote (out-of (sh-in dir "git" "rev-parse" "--verify" "-q" (str "refs/remotes/" remote "/" branch)))
            pushed? (and tip (= tip on-remote))
            title (or (not-empty (str/trim (str title))) (subs (first commits) (inc (str/index-of (first commits) " "))))
            body-file (doto (io/file (records-dir config) session-id "pr-body.md") io/make-parents)
            _ (spit body-file (or (not-empty (str body)) (str "Commits:\n\n" (str/join "\n" (map #(str "- " %) commits)) "\n")))
            push-cmd (str "git -C " (quoted dir) " push -u " (quoted remote) " " (quoted branch))
            ;; gh finds the repository from where it runs: from anywhere else,
            ;; live, the caller's own (byj-gly.308)
            pr-cmd (str "cd " (quoted dir) " && " gh-cmd " pr create --head " (quoted branch) " --base " (quoted base)
                        " --title " (quoted title) " --body-file " (quoted (.getCanonicalPath body-file)))
            ;; the session first: it works in the worktree, and left running
            ;; it outlived the directory, listed live (byj-gly.315.20). It
            ;; stays saved, to be resumed
            cleanup-cmd (str (when (contains? (set (host/running)) session-id)
                               (str "tmux kill-session -t " (quoted (host/tmux-name session-id)) "; "))
                             "git -C " (quoted root) " worktree remove " (quoted dir)
                             " && git -C " (quoted root) " branch -D " (quoted branch))
            what (str "Committed on " branch " (worktree " dir "), " n " commit" (when (> n 1) "s") " past " base ":\n"
                      (indented commits)
                      (when-let [stat (lines (sh-in dir "git" "diff" "--stat=100" (str base "..." branch)))]
                        (str "\nChanged:\n" (indented (map str/trim stat))))
                      (when (seq dirty) (str "\nNot committed (tracked files):\n" (indented dirty))))
            guide (str "To publish:\n  " push-cmd "\n"
                       (if url
                         (str "  — which updates its pull request, already open: " url)
                         (str "  " pr-cmd))
                       "\nOnce it is pushed — or merged here — to clean up (the Claude Code session first, saved and resumable):\n  "
                       cleanup-cmd)]
        (if-not publish?
          (do (update-record! config session-id merge {:published "handed-off"} (when url {:pr-url url}))
              (cond-> {:summary (str what "\n\nNot pushed: publishing is not this delegation's — "
                                     "the task did not ask for it. " guide)}
                url (assoc :pr-url url)))
          (let [done? (and url pushed?)
                state (cond
                        done? (str "Published by the session: " branch " pushed to " remote ", pull request " url ".")
                        url (str "Its pull request is " url ", but " remote "/" branch
                                 " is not at the branch's last commit. Push it:\n  " push-cmd)
                        pushed? (str "Pushed to " remote ", but no pull request was found for " branch ". Open it:\n  " pr-cmd)
                        :else (str "The task asked for the work to be pushed and a pull request opened; neither was. " guide))
                tidy (when (flag? cleanup)
                       (cond
                         (not done?) "Not cleaned up: the work is not published yet."
                         (seq dirty) "Not cleaned up: the worktree has changes that are not committed."
                         :else (clean-up! config session-id dir branch)))]
            (update-record! config session-id merge
                            {:published (cond done? "pr" pushed? "pushed" :else "no")}
                            (when url {:pr-url url}))
            (cond-> {:summary (str what "\n\n" state (when tidy (str "\n" tidy)))}
              url (assoc :pr-url url))))))))

(defn- note-bead!
  "Note how a delegation ended on its bead, when it has one and the project
  keeps beads: the verdict, branch and pull request — facts, no report."
  [config {:keys [bead verdict branch pr-url dir]}]
  (when (and bead (.isDirectory (io/file (project config) ".beads")))
    (let [text (str "Delegated work " (if (= "done" verdict) "finished" "needs a decision")
                    (when branch (str " on branch " branch))
                    (when dir (str " (worktree " dir ")"))
                    (when pr-url (str "; pull request " pr-url)) ".")
          r (apply sh-in (project config)
                   (concat (str/split (str/trim (str (:claude-bd-command config "bd"))) #"\s+") ["note" bead text]))]
      (zero? (:exit r)))))

(defn finish
  "Record how a delegation ended: its verdict and the report its caller got;
  and note it on its bead."
  [config {:keys [session-id verdict report]}]
  (if (str/blank? (str session-id))
    {:error "no session to record"}
    (let [r (update-record! config session-id assoc :verdict verdict :report report
                            :state (if (= "done" verdict) "done" "needs-person"))]
      {:recorded session-id :noted (boolean (try (note-bead! config r) (catch Throwable _ false)))})))

;; --- what a delegation leaves running (byj-gly.318) ------------------------------------------

(defn stranded
  "Why delegation record `r`'s Claude Code session, running, has nothing left
  to work for — or nil. Its worktree is gone (the caller removed it with its
  own git, not the hand-off's commands that stop the session first), or, with
  `closing-session`, the session that delegated it is closing while it still
  works (its task was cancelled, and nothing would read its report)."
  [r closing-session]
  (cond
    (and (:dir r) (not (.isDirectory (io/file (str (:dir r))))))
    (str "its worktree " (:dir r) " was removed")
    (and closing-session (= closing-session (:parent-session r)) (= "working" (:state r)))
    "the session that delegated it closed while it was still working"))

(defn processes-in
  "Processes whose working directory is `dir` or under it, by lsof — which
  still names a directory that has been deleted, on Linux with ` (deleted)`
  after it (byj-ahf.29): `[{:pid :command}]`. One call over every process,
  about 0.4 s: what a delegation left running."
  [dir]
  (let [root (str/replace (str dir) #"/+$" "")
        r (fx/sh {:out :string :err :string} "lsof" "-d" "cwd" "-Fpcn")]
    (loop [[l & more] (str/split-lines (str (:out r))) cur nil out []]
      (let [done (fn [] (if (and cur (:in? cur)) (conj out (dissoc cur :in?)) out))]
        (cond
          (nil? l) (done)
          (str/starts-with? l "p") (recur more {:pid (subs l 1)} (done))
          (str/starts-with? l "c") (recur more (assoc cur :command (subs l 1)) out)
          (str/starts-with? l "n") (let [n (str/replace (subs l 1) #" \(deleted\)$" "")]
                                     (recur more (assoc cur :in? (or (= n root) (str/starts-with? n (str root "/")))) out))
          :else (recur more cur out))))))

(defn- removed-worktree?
  "Is `r`'s directory a worktree of this project's that is gone? Only one
  under `.brainyard/worktrees`: the project itself is never swept."
  [config r]
  (let [d (str (:dir r))
        wts (str (.getCanonicalPath (io/file (project config) ".brainyard" "worktrees")) "/")]
    (and (str/starts-with? d wts) (not (.isDirectory (io/file d))))))

(defn stop-stranded!
  "Stop each of this project's running Claude Code sessions that a delegation
  drove and that is `stranded` — saved, so `--resume` takes it up again — and
  mark its record `stopped`, saying why. Not the hand-off guide's job alone:
  a caller that writes its own clean-up leaves the session in a deleted
  directory, and a cancelled delegation leaves one working that no one
  watches, stopped at a permission dialog no one answers (sim, byj-gly.317).

  Then, once for each delegation's worktree that is gone, stop what is still
  running in it: what the session started and did not stop — a server whose
  `kill %1` missed — outlived the session and the worktree, and brainyard
  tracks only its own bash calls' (byj-gly.321). The record is marked
  `:swept`. `[{:session-id :why}]`."
  [config & [{:keys [closing-session]}]]
  (if-not (.isDirectory (records-dir config))
    []
    (let [running (host/running-in (project config))
          stopped (reduce (fn [out r]
                            (let [sid (:session-id r)
                                  why (when (contains? running sid) (stranded r closing-session))]
                              (if (and why (:stopped (host/stop! sid)))
                                (do (update-record! config sid assoc :state "stopped" :stopped-why why)
                                    (conj out {:session-id sid :why why}))
                                out)))
                          []
                          (when (seq running) (records config)))]
      (reduce (fn [out r]
                (if (or (:swept r) (not (removed-worktree? config r)))
                  out
                  (let [sid (:session-id r)
                        left (processes-in (:dir r))]
                    (doseq [p left] (fx/sh {:out :string :err :string} "kill" "-TERM" (:pid p)))
                    (update-record! config sid assoc :swept true)
                    (if (seq left)
                      (conj out {:session-id sid
                                 :why (str "it left " (str/join ", " (map #(str (:command %) " (pid " (:pid %) ")") left))
                                           " running in its removed worktree " (:dir r) ", now stopped")
                                 :processes left})
                      out))))
              stopped
              (records config)))))

;; --- the commands ------------------------------------------------------------------------

(defn commands
  "The `claude$*` commands (RFC-039)."
  [config]
  [{:name "claude$sessions" :kind :command
    :description "The project's Claude Code sessions a task could go to: running here, in another terminal, or saved; which ones you already drive."
    :input-schema [:map]
    :output-schema [:map [:sessions [:vector :any]] [:text :string]]
    :effects #{:fs/read}
    :fn (fn [_ _] (sessions config))}
   {:name "claude$worktree" :kind :command
    :description "A git worktree of the project on a new branch, for a Claude Code session to work in; the existing one when the branch already has one here."
    :input-schema [:map [:branch [:string {:desc "the branch, e.g. bd/byj-123"}]]]
    :output-schema [:or [:map [:dir :string] [:branch :string]] [:map [:error :string]]]
    :effects #{:fs/write :process}
    :fn (fn [{:keys [branch]} _] (add-worktree config branch))}
   {:name "claude$open" :kind :command
    :description "Hand a brief to a Claude Code session: a new one (session \"new\"), one running here, or a saved one resumed."
    :input-schema [:map
                   [:session [:string {:desc "\"new\", or a session id from claude$sessions"}]]
                   [:brief [:string {:desc "what Claude Code is to do, standing on its own"}]]
                   [:dir {:optional true} [:string {:desc "where a new one works; the project when absent"}]]
                   [:model {:optional true} :string]
                   [:args {:optional true} [:string {:desc "more claude CLI options, space separated"}]]
                   [:task {:optional true} :string]
                   [:worktree {:optional true} [:boolean {:desc "a new one works in a git worktree of its own, on :branch"}]]
                   [:bead {:optional true} [:string {:desc "the bead the work is for, noted when it ends"}]]
                   [:publish {:optional true} [:any {:desc "the task tells it to push and open the pull request itself"}]]
                   [:branch {:optional true} :string]]
    :output-schema [:or [:map [:session-id :string] [:dir :string] [:how :string] [:where :string] [:branch :string]] [:map [:error :string]]]
    :effects #{:process :fs/write}
    :fn (fn [args _] (open config args))}
   {:name "claude$await" :kind :command
    :description "Wait until a Claude Code session's turn is over: done, waiting on a dialog, gone, or timed out; what it answered and what the delegation has cost."
    :input-schema [:map [:session-id :string]
                   [:timeout-ms {:optional true} :int]
                   [:max-cost-usd {:optional true} :double]]
    :output-schema [:map [:settled :string] [:answer :string] [:prompt :string] [:screen :string]
                    [:round :string] [:cost-usd :double] [:usage-cost-usd :double] [:over-budget :string]]
    :effects #{:fs/read :fs/write}
    :fn (fn [args ctx] (await-turn config args ctx))}
   {:name "claude$say" :kind :command
    :description "Tell a Claude Code session something as the person would: typed when one short line, else from a file it is pointed at."
    :input-schema [:map [:session-id :string] [:text :string]]
    :output-schema [:or [:map [:sent :boolean]] [:map [:error :string]]]
    :effects #{:process}
    :fn (fn [{:keys [session-id text]} _] (say config session-id text))}
   {:name "claude$choose" :kind :command
    :description "Answer the dialog a Claude Code session shows: on a permission dialog 1 is yes, 3 is no."
    :input-schema [:map [:session-id :string] [:choice :string]]
    :output-schema [:or [:map [:chosen :any] [:session-id :string] [:where :string] [:branch :string]] [:map [:error :string]]]
    :effects #{:process}
    :fn (fn [{:keys [session-id choice]} _] (choose config session-id choice))}
   {:name "claude$handoff" :kind :command
    :description "What a finished delegation hands back about its branch: its commits and changed files, and either the commands that push it and open its pull request (the default: publishing is the caller's), or — when the task told the session to publish — what it published, and with cleanup its worktree and branch removed. Never pushes or merges."
    :input-schema [:map [:session-id :string]
                   [:publish {:optional true} [:any {:desc "the task told the session to push and open the pull request"}]]
                   [:cleanup {:optional true} [:any {:desc "the task asked for the worktree and branch to be removed once published"}]]
                   [:title {:optional true} :string]
                   [:body {:optional true} :string]]
    :output-schema [:map [:summary :string] [:pr-url {:optional true} :string]]
    :effects #{:process :fs/write}
    :fn (fn [args _] (handoff config args))}
   {:name "claude$finish" :kind :command
    :description "Record how a delegation ended: its verdict and the report."
    :input-schema [:map [:session-id :string] [:verdict {:optional true} :string] [:report {:optional true} :string]]
    :output-schema [:or [:map [:recorded :string] [:noted :boolean]] [:map [:error :string]]]
    :effects #{:fs/write :process}
    :fn (fn [args _] (finish config args))}])
