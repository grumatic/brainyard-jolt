;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.codex-agent
  "The codex$* commands agent$codex drives a Codex session with (byj-gly.430.6,
  RFC-049), as agent$claude's claude$* drive Claude Code (RFC-039): find the
  sessions a task could go to, open one (new, in a git worktree of its own on
  a branch; or one running here; or a saved one, resumed) with the brief, wait
  out its turn, say the next thing, answer a dialog, hand the work back, and
  record how it ended. Mechanism only — which session, what to say and when
  to stop are the loop's (brainyard/loops/codex.edn) and its predictors'.

  A delegated Codex session cannot publish (RFC-049 decision 7): it runs in
  Codex's workspace-write sandbox, which has no network and keeps `.git` read
  only — measured, even with the git directory given as `--add-dir` — and
  carries claude-agent's `publish-guard` as a `PreToolUse` hook given as a
  session flag with its trust record (`codex-host/hook-flags`), which refuses a
  push or a pull request anywhere in a command line. Where Codex's sandbox
  cannot start — Linux without unprivileged user namespaces, a container
  (byj-1wv.62.12) — the session runs unsandboxed
  with the guard alone between it and a push, and says so; unless
  `:codex-require-sandbox?`, when it is not opened at all. Since it cannot commit
  either, `handoff` commits what it changed in its worktree, then hands the
  branch back with the commands that publish it, for whoever gave agent$codex
  the task.

  The record of each delegation is `.brainyard/codex-agents/<thread>.edn`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.claude-agent :as claude-agent]
            [brainyard.codex-host :as host]
            [brainyard.codex-sessions :as cxs]
            [brainyard.codex-usage :as usage]
            [brainyard.effect :as fx]))

;; --- the records -----------------------------------------------------------------------------

(defn- project [config] (or (:project-dir config) (:working-dir config) "."))

(defn records-dir [config] (io/file (project config) ".brainyard" "codex-agents"))

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

;; --- the commands' work ------------------------------------------------------------------------

(defn- home [config] (:codex-home config))

(defn- flag? [v] (contains? #{true "true" "yes"} (if (string? v) (str/lower-case (str/trim v)) v)))

(defn- run [task ms]
  ;; a command is a synchronous handler: the edge where a task is waited on
  (let [r (fx/run!! task ms)]
    (cond (contains? r :ok) (:ok r)
          (:timeout r) {:error (str "no answer within " ms " ms")}
          (:err r) {:error (str (ex-message (:err r)))}
          :else {:error "interrupted"})))

(defn- cost-now
  "What session `sid` has cost so far, by its rollout's running totals; nil
  when its model is not priced or it has counted nothing (byj-gly.430.7)."
  [config sid]
  (usage/usage-cost config (usage/session-usage (cxs/rollout-file (home config) sid))))

(defn- mine? [config r]
  (and (:agent-instance-id config) (= (:agent-instance-id config) (:instance-id r))))

(defn sessions
  "The Codex sessions a task could go to, and how each stands — running here
  (it can be typed to), in another terminal (it cannot be driven), or saved
  (it can be resumed) — whether this instance drives it, and for what task.
  `:text` is the same as lines, for a predictor to read."
  [config]
  (let [{:keys [live saved]} (cxs/sessions (project config) :home (home config))
        here (host/running)
        recs (into {} (map (juxt :session-id identity)) (records config))
        row (fn [where s]
              (let [r (recs (:id s))]
                (cond-> {:id (:id s) :title (:title s) :where where}
                  (= where "live") (assoc :where (if (here (:id s)) "running here" "another terminal"))
                  (:dir s) (assoc :dir (:dir s))
                  r (assoc :task (:task r) :state (:state r) :branch (:branch r) :bead (:bead r))
                  (mine? config r) (assoc :mine? true))))
        rows (concat (map #(row "live" %) live) (map #(row "saved" %) (take 15 saved)))]
    {:sessions (vec rows)
     :text (if (empty? rows)
             "(no Codex sessions in this project)"
             (str/join "\n" (for [r rows]
                              (str "- " (:id r) " | " (:where r)
                                   (when (:mine? r) " | YOURS")
                                   (when (and (:state r) (not (:mine? r)))
                                     (str " | delegated earlier" (some->> (:bead r) (str " for ")) ", " (:state r)))
                                   (some->> (:branch r) (str " | branch "))
                                   (some->> (:dir r) (str " | in "))
                                   " | " (or (:title r) "untitled")
                                   (some->> (:task r) (str " | was given: "))))))}))

(defn- guidance-file [config sid text]
  (let [d (io/file (records-dir config) sid)
        n (inc (count (filter #(str/starts-with? (.getName ^java.io.File %) "guidance-") (or (.listFiles d) []))))
        f (io/file d (str "guidance-" n ".md"))]
    (io/make-parents f)
    (spit f text)
    (.getCanonicalPath f)))

(defn say
  "Deliver `text` to session `sid` as the person's own words: typed when it is
  one short line, else written to a file and a line typed that points at it."
  [config sid text]
  (let [text (str/trim (str text))
        line (if (or (> (count text) claude-agent/typed-chars) (str/includes? text "\n"))
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

(defn guard-file
  "claude-agent's `publish-guard`, written beside the records: the
  `PreToolUse` hook a delegated session carries. Codex hands a hook Claude
  Code's payload (RFC-049 decision 6), so the same script reads it."
  [config]
  (let [f (io/file (records-dir config) "publish-guard.sh")]
    (io/make-parents f)
    (spit f claude-agent/publish-guard)
    (.setExecutable f true)
    (.getCanonicalPath f)))

(def guard-alone-note
  "What a delegation says when its session runs without Codex's sandbox."
  (str "Codex's sandbox cannot start here, so this session's commands run unsandboxed (-s danger-full-access): "
       "the publish guard, its PreToolUse hook, is all that stands between it and a push or a pull request."))

(defn- sandbox
  "The sandbox a delegated session starts in (RFC-049 decision 7): Codex's
  workspace-write where it can start; where it cannot, unsandboxed with the
  publish guard alone — `{:mode :note}` — or, with `:codex-require-sandbox?`,
  `{:error}` saying why."
  [config]
  (let [c (host/sandbox-check (:codex-command config "codex"))]
    (cond (:ok c) {:mode "workspace-write"}
          (:codex-require-sandbox? config)
          {:error (str "Codex's sandbox cannot start here (" (:why c) ") and :codex-require-sandbox? is on: "
                       "no Codex session is opened")}
          :else {:mode "danger-full-access" :note guard-alone-note :why (:why c)})))

(defn- start-opts
  "How a delegated session starts: in `mode` — the workspace-write sandbox,
  which keeps it off the network and out of `.git`, or none where that cannot
  start — the publish guard as its `PreToolUse` hook, the plan's model (else
  the project's), and the plan's options."
  [config dir mode {:keys [model args]}]
  {:dir dir :home (home config) :codex (:codex-command config "codex")
   :model (or (not-empty (str/trim (str model))) (not-empty (str/trim (str (:codex-model config)))))
   :hooks {:PreToolUse (guard-file config)}
   :args (into ["-s" mode] (arg-list args))})

(defn- where [sid dir branch]
  (str "Codex session " sid " (tmux " (host/tmux-name sid) ") in " dir (when branch (str ", branch " branch))))

(defn open
  "Hand `brief` to a session: `session` \"new\" starts one in a worktree of
  its own on `branch` with `worktree`, else in the project, with the brief as
  its first prompt; the thread of one running here gives it the brief as its
  next line (once idle); the thread of a saved one resumes it, then gives it
  the brief. `{:session-id :dir :how :where :branch}` or `{:error}`."
  [config {:keys [session brief task branch worktree bead] :as args}]
  (let [session (str/trim (str session))
        h (home config)
        new? (or (str/blank? session) (= "new" session))
        wt (when (and new? (flag? worktree) (not (str/blank? (str brief))))
             (claude-agent/add-worktree config (or (not-empty (str/trim (str branch)))
                                                   (str "cx/" (subs (str (java.util.UUID/randomUUID)) 0 8)))))
        saved (into {} (map (juxt :id identity)) (:saved (cxs/sessions (project config) :home h)))
        here (host/running)
        begin! (fn [sid dir branch how & [sb]]
                 (update-record! config sid merge
                                 (cond-> {:session-id sid :instance-id (:agent-instance-id config)
                                          :parent-session (:session-id config)
                                          :task (or task brief) :dir dir :branch branch
                                          :bead (not-empty (str/trim (str bead))) :how how :state "working"
                                          :round 0 :started-at (System/currentTimeMillis)
                                          ;; the spend counts from here
                                          :baseline-cost-usd (or (cost-now config sid) 0.0) :cost-usd 0.0}
                                   sb (assoc :sandbox (:mode sb))))
                 (cond-> {:session-id sid :dir dir :how how :branch (or branch "") :where (where sid dir branch)}
                   sb (assoc :sandbox (:mode sb))
                   (:note sb) (assoc :note (:note sb))))
        up (fn [started & [resume]]
             (if (:error started) started (run (host/ready started {:home h :resume resume :timeout-ms 30000}) 40000)))]
    (cond
      (str/blank? (str brief)) {:error "open needs a brief"}
      (:error wt) wt

      new?
      (let [sb (sandbox config)
            dir (or (:dir wt) (.getCanonicalPath (io/file (project config))))
            r (if (:error sb) sb (up (host/start! (assoc (start-opts config dir (:mode sb) args) :prompt brief))))]
        (if (:error r) r (begin! (:id r) dir (:branch wt) "new" sb)))

      (here session)
      (let [r (say config session brief)
            rec (read-record config session)]
        (if (:error r) r (begin! session (or (:dir rec) (project config)) (:branch rec) "continued")))

      (saved session)
      (let [rec (read-record config session)
            sb (sandbox config)
            dir (or (:dir rec) (:dir (saved session)) (.getCanonicalPath (io/file (project config))))
            r (if (:error sb) sb (up (host/start! (assoc (start-opts config dir (:mode sb) args) :resume session)) session))
            said (when-not (:error r)
                   (run (fx/poll-until 200 ::not-yet #(if (= :idle (:status (host/observe session {:home h}))) :idle ::not-yet))
                        30000)
                   (say config session brief))]
        (cond (:error r) r
              (:error said) said
              :else (begin! session dir (:branch rec) "resumed" sb)))

      :else {:error (str "no Codex session " session " running here or saved: give \"new\" — "
                         "one in another terminal is not driven from here (session$send codex:" session " hands it a task)")})))

(defn await-turn
  "Wait until session `sid`'s turn is over, at most `timeout-ms`: how it
  settled (`host/await-settled`: done | waiting | gone, or timeout), its
  answer, the screen when a dialog is up, the round, and whether what the
  delegation has cost since it began is past `:codex-max-cost-usd` — \"no\"
  when its model is not priced (byj-gly.430.7). Strings where a predictor
  reads them."
  [config {:keys [session-id timeout-ms]}]
  (let [h (home config)
        timeout-ms (or timeout-ms (:codex-turn-timeout-ms config 1800000))
        o (let [r (run (host/await-settled session-id {:home h :settle-ms (:codex-settle-ms config 1500)}) timeout-ms)]
            (if (:error r) (assoc (host/observe session-id {:home h}) :settled :timeout) r))
        now (cost-now config session-id)
        spent (when now (max 0.0 (- now (or (:baseline-cost-usd (read-record config session-id)) 0.0))))
        cap (:codex-max-cost-usd config 5.0)
        rec (update-record! config session-id #(cond-> (-> % (update :round (fnil inc 0)) (assoc :state (name (:settled o))))
                                                   spent (assoc :cost-usd spent)))]
    {:settled (name (:settled o))
     :answer (or (:answer o) "")
     :prompt (or (:prompt o) "")
     :screen (or (:screen o) "")
     :round (str (:round rec))
     :over-budget (if (and spent (> spent cap)) (format "yes: $%.2f spent, the cap is $%.2f" spent (double cap)) "no")}))

(def choice-keys
  "An approval dialog's choices, by the number a person gives, as the keys
  Codex's dialog takes: 1 yes, 2 yes and don't ask again, 3 no (Escape, which
  aborts the turn — measured, RFC-049)."
  {"1" "y" "2" "p" "3" "Escape"})

(defn choose
  "Answer the dialog on session `sid`'s screen with choice `choice` (1, 2 or
  3). `{:chosen :session-id :where :branch}`, or `{:error}`."
  [config sid choice]
  (if-let [k (choice-keys (str/trim (str choice)))]
    (let [r (run (host/choose sid k) 5000)
          rec (read-record config sid)]
      (if (:error r)
        r
        (do (update-record! config sid assoc :state "working")
            {:chosen choice :session-id sid :branch (or (:branch rec) "")
             :where (where sid (or (:dir rec) (project config)) (:branch rec))})))
    {:error (str "a choice is 1, 2 or 3, not " (pr-str choice))}))

(defn- sh-in [dir & argv] (apply fx/sh {:dir (str dir) :out :string :err :string} argv))

(defn- out-of [r] (when (zero? (:exit r)) (str/trim (str (:out r)))))

(defn- lines [r] (some-> (out-of r) not-empty str/split-lines))

(defn- quoted [s]
  (let [s (str s)]
    (if (re-matches #"[A-Za-z0-9_./:@=+,-]+" s) s (str "'" (str/replace s "'" "'\\''") "'"))))

(defn- indented [xs] (str/join "\n" (map #(str "  " %) xs)))

(defn handoff
  "What a finished delegation hands back about its branch: since the session
  could not commit, what it changed in its worktree is committed here first,
  with `title` (else the task's first line) as the message; then the commits
  past the base (the project's current branch), the files they change, and
  the commands that publish it and clean up after — never run here.
  `{:summary :committed?}`, `:summary` in words."
  [config {:keys [session-id title body]}]
  (let [rec (read-record config session-id)
        {:keys [dir branch]} rec
        root (project config)
        remote (:claude-git-remote config "origin")
        gh-cmd (str/trim (str (:claude-gh-command config "gh")))
        base (or (out-of (sh-in root "git" "symbolic-ref" "--short" "HEAD")) "main")]
    (cond
      (nil? rec) {:error (str "no delegation " session-id) :summary "No hand-off: there is no record of that session."}
      (str/blank? (str branch)) {:summary "No hand-off: the work has no branch of its own."}
      :else
      (let [message (or (not-empty (str/trim (str title)))
                        (first (str/split-lines (str/trim (str (:task rec)))))
                        "Work done in a Codex session")
            changed (lines (sh-in dir "git" "status" "--porcelain"))
            committed (when (seq changed)
                        (let [a (sh-in dir "git" "add" "-A")
                              c (when (zero? (:exit a)) (sh-in dir "git" "commit" "-q" "-m" message))]
                          (and c (zero? (:exit c)))))
            commits (lines (sh-in dir "git" "log" "--reverse" "--format=%h %s" (str base ".." branch)))
            n (count commits)]
        (if (zero? n)
          {:summary (str "Nothing to publish: " branch " has no commits past " base
                         (when (and (seq changed) (not committed)) (str "; its changes could not be committed, in " dir)) ".")}
          (let [body-file (doto (io/file (records-dir config) session-id "pr-body.md") io/make-parents)
                _ (spit body-file (or (not-empty (str body)) (str "Commits:\n\n" (str/join "\n" (map #(str "- " %) commits)) "\n")))
                push-cmd (str "git -C " (quoted dir) " push -u " (quoted remote) " " (quoted branch))
                pr-cmd (str "cd " (quoted dir) " && " gh-cmd " pr create --head " (quoted branch) " --base " (quoted base)
                            " --title " (quoted (subs (first commits) (inc (str/index-of (first commits) " "))))
                            " --body-file " (quoted (.getCanonicalPath body-file)))
                cleanup-cmd (str (when (contains? (host/running) session-id)
                                   (str "tmux kill-session -t " (quoted (host/tmux-name session-id)) "; "))
                                 "git -C " (quoted root) " worktree remove " (quoted dir)
                                 " && git -C " (quoted root) " branch -D " (quoted branch))]
            (update-record! config session-id assoc :published "handed-off")
            {:committed? (boolean committed)
             :summary (str (when committed (str "Committed the session's changes (it cannot commit in Codex's sandbox): " message "\n"))
                           "On " branch " (worktree " dir "), " n " commit" (when (> n 1) "s") " past " base ":\n"
                           (indented commits)
                           (when-let [stat (lines (sh-in dir "git" "diff" "--stat=100" (str base "..." branch)))]
                             (str "\nChanged:\n" (indented (map str/trim stat))))
                           "\n\nNot pushed: a Codex session does not publish. To publish:\n  " push-cmd "\n  " pr-cmd
                           "\nOnce it is pushed — or merged here — to clean up (the Codex session first, saved and resumable):\n  "
                           cleanup-cmd)}))))))

(defn finish
  "Record how a delegation ended: its verdict and the report its caller got."
  [config {:keys [session-id verdict report]}]
  (if (str/blank? (str session-id))
    {:error "no session to record"}
    (do (update-record! config session-id assoc :verdict verdict :report report
                        :state (if (= "done" verdict) "done" "needs-person"))
        {:recorded session-id})))

;; --- the commands ----------------------------------------------------------------------------

(defn commands
  "The codex$* commands (RFC-049), registered under `:codex-tools?` —
  agent$codex's own config turns it on."
  [config]
  [{:name "codex$sessions" :kind :command
    :description "The project's Codex sessions a task could go to: running here, in another terminal, or saved; which ones you already drive."
    :input-schema [:map]
    :output-schema [:map [:sessions [:vector :any]] [:text :string]]
    :effects #{:fs/read}
    :fn (fn [_ _] (sessions config))}
   {:name "codex$open" :kind :command
    :description "Hand a brief to a Codex session: a new one (session \"new\"), one running here, or a saved one resumed."
    :input-schema [:map
                   [:session [:string {:desc "\"new\", or a thread id from codex$sessions"}]]
                   [:brief [:string {:desc "what Codex is to do, standing on its own"}]]
                   [:model {:optional true} :string]
                   [:args {:optional true} [:string {:desc "more codex CLI options, space separated"}]]
                   [:task {:optional true} :string]
                   [:worktree {:optional true} [:any {:desc "a new one works in a git worktree of its own, on :branch"}]]
                   [:bead {:optional true} :string]
                   [:branch {:optional true} :string]]
    :output-schema [:or [:map [:session-id :string] [:dir :string] [:how :string] [:where :string] [:branch :string]] [:map [:error :string]]]
    :effects #{:process :fs/write}
    :fn (fn [args _] (open config args))}
   {:name "codex$await" :kind :command
    :description "Wait until a Codex session's turn is over: done, waiting on an approval dialog, gone, or timed out; what it answered."
    :input-schema [:map [:session-id :string] [:timeout-ms {:optional true} :int]]
    :output-schema [:map [:settled :string] [:answer :string] [:prompt :string] [:screen :string]
                    [:round :string] [:over-budget :string]]
    :effects #{:fs/read :fs/write}
    :fn (fn [args _] (await-turn config args))}
   {:name "codex$say" :kind :command
    :description "Tell a Codex session something as the person would: typed when one short line, else from a file it is pointed at."
    :input-schema [:map [:session-id :string] [:text :string]]
    :output-schema [:or [:map [:sent :boolean]] [:map [:error :string]]]
    :effects #{:process}
    :fn (fn [{:keys [session-id text]} _] (say config session-id text))}
   {:name "codex$choose" :kind :command
    :description "Answer the approval dialog a Codex session shows: 1 yes, 2 yes and don't ask again, 3 no (which ends its turn)."
    :input-schema [:map [:session-id :string] [:choice :string]]
    :output-schema [:or [:map [:chosen :any] [:session-id :string] [:where :string] [:branch :string]] [:map [:error :string]]]
    :effects #{:process}
    :fn (fn [{:keys [session-id choice]} _] (choose config session-id choice))}
   {:name "codex$handoff" :kind :command
    :description "Hand back a finished delegation's branch: what the session changed committed for it (it cannot commit in Codex's sandbox), its commits and changed files, and the commands that push it and open its pull request. Never pushes or merges."
    :input-schema [:map [:session-id :string] [:title {:optional true} :string] [:body {:optional true} :string]]
    :output-schema [:map [:summary :string] [:committed? {:optional true} :boolean]]
    :effects #{:process :fs/write}
    :fn (fn [args _] (handoff config args))}
   {:name "codex$finish" :kind :command
    :description "Record how a delegation ended: its verdict and the report."
    :input-schema [:map [:session-id :string] [:verdict {:optional true} :string] [:report {:optional true} :string]]
    :output-schema [:or [:map [:recorded :string]] [:map [:error :string]]]
    :effects #{:fs/write}
    :fn (fn [args _] (finish config args))}])
