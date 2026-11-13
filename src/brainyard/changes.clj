;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.changes
  "What a turn changed, kept as git keeps things, for the person to review
  with git's own tools (RFC-067 phase 8, D10; byj-gly.476, byj-gly.481).

  As a turn begins and as it ends, the project's git work tree is made into a
  commit object — every file `git add -A` would take, tracked or not, minus
  what `.gitignore` names — through an index of byj's own, so HEAD, the
  person's index, their stash and the work tree are never touched. A turn
  whose end differs from its start keeps the end as the ref
  `refs/byj/turns/<session>/<turn>`, its parent the start: `git show REF`,
  `git diff REF^ REF`, magit's `magit-diff-range` and vc's diff all show the
  turn's change, whichever tool made it — a write-file, a bash `sed`, a
  formatter, an org tangle.

  - `begin-turn!` and `end-turn!` are the session's, at a turn's start and
    end (`session/begin-turn`, `session/end-turn`).
  - Off outside a git work tree, without a session id, or with `:changes?`
    false. The last `:changes-turns` refs of the repository are kept,
    newest by commit time; an older one is deleted and git's gc takes its
    objects.
  - What the person changes while a turn runs is in that turn's change: the
    snapshots see the work tree, not who wrote it.
  - A `.brainyard/` directory is left out, at any depth, whether git ignores
    it or not: it is byj's own state (logs, sessions, todos), written every
    turn. What a turn writes there is not in its change. So are the files
    Emacs leaves beside one it visits: `.#F` (the lock of a buffer with
    unsaved edits), `#F#` (an auto-save) and `F~` (a backup).
  - An edit made in an Emacs buffer and not saved (`emacs$edit`) is not on
    disk, so not in the turn's change until it is saved.

  Mechanism only (RFC-001): what a review asks of the agent is byj.el's
  `byj-questions`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.clock :as clock]
            [brainyard.effect :as fx]
            [brainyard.persist :as persist]))

(defonce ^:private current
  ;; session id → the turn running in it, between begin-turn! and end-turn!:
  ;; {:turn :root :before :question}
  (atom {}))

(def ^:private ref-prefix "refs/byj/turns/")

(def ^:private ident
  ;; who a snapshot's commit names: byj, never the person's identity, and
  ;; never a reason for commit-tree to fail where none is configured
  {"GIT_AUTHOR_NAME" "byj" "GIT_AUTHOR_EMAIL" "byj@localhost"
   "GIT_COMMITTER_NAME" "byj" "GIT_COMMITTER_EMAIL" "byj@localhost"})

(def ^:private excluded
  ;; never a turn's change, ignored by git or not: byj's own state under
  ;; .brainyard/ (logs, sessions, todos), and what Emacs leaves beside a
  ;; file — the lock of a buffer with unsaved edits (.#F, a symlink), an
  ;; auto-save (#F#), a backup (F~) — which a review would otherwise show as
  ;; the turn's
  [":(exclude,glob)**/.brainyard/**" ":(exclude,glob)**/.#*"
   ":(exclude,glob)**/#*#" ":(exclude,glob)**/*~"])

(defn- on? [config]
  (and (:session-id config) (not (false? (:changes? config)))))

(defn- git*
  "git `args` in `dir`, with `env` over the process's environment:
  `{:exit :out :err}`, `:exit` nil when git could not be run."
  [dir env & args]
  (try (let [r (apply fx/sh (cond-> {:dir (str dir) :out :string :err :string}
                              env (assoc :env (merge (into {} (System/getenv)) env)))
                      "git" args)]
         {:exit (:exit r) :out (str (:out r)) :err (str (:err r))})
       (catch Throwable e {:exit nil :out "" :err (str (ex-message e))})))

(defn- git
  "git `args` in `dir`, with `env` over the process's environment:
  `{:ok out}` (untrimmed) or `{:error}`."
  [dir env & args]
  (let [{:keys [exit out err]} (apply git* dir env args)]
    (if (= 0 exit) {:ok out} {:error (str/trim (str err out))})))

(defn- line [r] (some-> (:ok r) str/trim not-empty))

(defn root
  "The top of the git work tree `config`'s project is in, or nil — nil too
  for the one the system property `brainyard.changes-skip-root` (or, in a
  process a test started, the environment's `BY_CHANGES_SKIP_ROOT`) names,
  which the test runner sets to this repository: a test that runs a turn
  without a project of its own must not keep it here (provenance P-55)."
  [config]
  (let [r (line (git (or (:project-dir config) (:working-dir config) ".") nil "rev-parse" "--show-toplevel"))]
    (when-not (and r (some #{r} [(System/getProperty "brainyard.changes-skip-root") (System/getenv "BY_CHANGES_SKIP_ROOT")])) r)))

(defn- safe [id] (str/replace (str id) #"[^A-Za-z0-9._-]" "_"))

(defn ref-name
  "The ref session `id`'s turn `turn` is kept under."
  [id turn]
  (str ref-prefix (safe id) "/" turn))

(defn snapshot!
  "The work tree at `root` as a commit object with message `msg` and parent
  `parent` (none when nil): its sha, or nil. Made through a copy of the
  person's index — so unchanged files cost a stat, not a read — that is
  thrown away after; nothing the person has is changed.

  The copy keeps the index's own time. git trusts an entry's stat only when
  the file is older than the index that holds it, and compares times to the
  second unless built with USE_NSEC (Apple's git is not): a copy stamped
  with the time it was made would pass a same-size edit, made in the second
  the person's index was written, as unchanged (provenance P-63). Its name
  is unique across processes: jolt's `System/nanoTime` counts from the
  process's start, so two byj processes begun together would share one."
  [root msg & [parent]]
  (let [idx (io/file (System/getProperty "java.io.tmpdir") (str "byj-snapshot-" (random-uuid) ".index"))]
    (try
      (when-let [real (some-> (line (git root nil "rev-parse" "--path-format=absolute" "--git-path" "index")) io/file)]
        (when (.isFile real)
          (io/copy real idx)
          (.setLastModified idx (.lastModified real))))
      (let [env {"GIT_INDEX_FILE" (.getPath idx)}]
        ;; --ignore-errors: a file git cannot take — a nested repository
        ;; with no commit is one — is left out, not the whole snapshot
        (when-not (some->> (:error (apply git root env "add" "-A" "--ignore-errors" "--" ":/" excluded))
                           (re-find #"(?m)^fatal:"))
          (when-let [tree (line (git root env "write-tree"))]
            (line (apply git root ident "commit-tree" "--no-gpg-sign" "-m" msg
                         (concat (when parent ["-p" parent]) [tree]))))))
      (finally
        (.delete idx)
        (.delete (io/file (str (.getPath idx) ".lock")))))))

(defn- changed-files [root before after]
  (->> (str/split (or (:ok (git root nil "diff" "--name-only" "--no-renames" "-z" before after)) "") #"\u0000")
       (remove str/blank?) vec))

(defn- turn-of [refname] (some-> (re-find #"/(\d+)$" refname) second parse-long))

(defn turns
  "Session `id`'s turns still kept, ascending: every turn whose snapshots
  were made, whether it changed files or not."
  [config id]
  (if-let [r (root config)]
    (->> (str/split-lines (or (:ok (git r nil "for-each-ref" "--format=%(refname)" (str ref-prefix (safe id) "/"))) ""))
         (keep turn-of) sort vec)
    []))

(defn- commits
  "`{:before :after}` of `ref` in the repository at `root`, or nil."
  [root ref]
  (when-let [after (line (git root nil "rev-parse" "--verify" "-q" (str ref "^{commit}")))]
    (when-let [before (line (git root nil "rev-parse" "--verify" "-q" (str after "^")))]
      {:before before :after after})))

(defn- changed?
  "Whether the turn kept as `ref` changed files: its two trees differ."
  [root ref]
  (let [[a b] (some-> (:ok (git root nil "rev-parse" (str ref "^{tree}") (str ref "^^{tree}"))) str/split-lines)]
    (and a b (not= a b))))

(defn rows
  "Session `id`'s kept turns, ascending, as the turn list shows them:
  `{:turn :ref :before :after :files :subject :at}` — `:files` the paths it
  changed, `:at` its end in epoch seconds."
  [config id]
  (if-let [r (root config)]
    (->> (str/split-lines (or (:ok (git r nil "for-each-ref" "--format=%(refname)%09%(objectname)%09%(committerdate:unix)%09%(subject)"
                                     (str ref-prefix (safe id) "/"))) ""))
         (keep (fn [l]
                 (let [[ref after at subject] (str/split l #"\t" 4)]
                   (when-let [n (some-> ref turn-of)]
                     (when-let [before (line (git r nil "rev-parse" "--verify" "-q" (str after "^")))]
                       {:turn n :ref ref :before before :after after
                        :files (changed-files r before after)
                        :at (some-> at parse-long)
                        ;; the commit's subject is "byj ID turn N: QUESTION"
                        :subject (or (second (re-find #"^byj \S+ turn \d+(?:, before)?: ?(.*)$" (str subject))) "")})))))
         (sort-by :turn) vec)
    []))

(defn row-line
  "One of `rows` as a line: its turn, how many files it changed, when it
  ended (this machine's time) and its question, cut short."
  [{:keys [turn files at subject]}]
  (format "%4d  %-9s %s  %s" turn
          (case (count files) 0 "-" 1 "1 file" (str (count files) " files"))
          (subs (clock/stamp (* 1000 (or at 0)) (or (clock/offset-seconds) 0) false) 0 19)
          (let [q (str subject)] (if (> (count q) 70) (str (subs q 0 69) "…") q))))

(defn- prune!
  "Keep the newest `keep-n` refs under `prefix` (the turns', unless given)
  of the repository at `root`."
  [root keep-n & [prefix]]
  ;; newest by commit time, which is to the second: a tie goes to the later
  ;; turn, so a session's turns ended in one second are kept in order
  (let [refs (->> (str/split-lines (or (:ok (git root nil "for-each-ref" "--format=%(committerdate:unix) %(refname)" (or prefix ref-prefix))) ""))
                  (keep #(when-let [[_ t r n] (re-matches #"(\d+) (\S+/(\d+))" %)]
                           {:t (parse-long t) :ref r :n (parse-long n)}))
                  (sort-by (juxt (comp - :t) (comp - :n))))]
    (doseq [{r :ref} (drop keep-n refs)]
      (git root nil "update-ref" "-d" r))))

;; --- who is mid-turn in a work tree (RFC-069, byj-gly.487) --------------------
;;
;; A turn running marks its work tree, `byj/running/<session>` under the work
;; tree's own git directory (a linked worktree has its own, and another work
;; tree's turn writes other files), with this process's pid: what a rewind
;; asks before it writes files that turn may be writing too. A marker whose
;; process is gone is stale, and removed by whoever finds it.

(def ^:private pid (delay (persist/own-pid)))

(defn- running-dir [root]
  (some-> (line (git root nil "rev-parse" "--path-format=absolute" "--git-path" "byj/running")) io/file))

(defn- mark-running! [root id n]
  (when-let [d (running-dir root)]
    (try (.mkdirs d)
         (spit (io/file d (safe id)) (pr-str {:session id :turn n :pid @pid :at (System/currentTimeMillis)}))
         (catch Throwable _ nil))))

(defn- unmark-running! [root id]
  (some-> (running-dir root) (io/file (safe id)) .delete))

(defn running
  "The turns running in the work tree at `root` now: `[{:session :turn :pid
  :at} …]`, another process's sessions' and this one's — but not session
  `id`'s own in this process, which is the one asking."
  [root & [id]]
  (when-let [d (running-dir root)]
    (vec (for [f (or (.listFiles d) [])
               :let [m (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
               :when (map? m)
               :when (if (= (str (:pid m)) (str @pid))
                       ;; this process's: running only between begin-turn!
                       ;; and end-turn!, and the asker's own is not in its way
                       (and (not= (str (:session m)) (str id)) (contains? @current (str (:session m))))
                       (or (persist/alive? (:pid m)) (do (.delete f) false)))]
           m))))

(defn- busy-error [root id]
  (when-let [[{:keys [session turn] p :pid} :as all] (seq (running root id))]
    {:error (str "session " session " is in the middle of turn " turn " in this work tree"
                 (when (not= (str p) (str @pid)) (str " (process " p ")"))
                 (when (next all) (str ", and " (dec (count all)) " more"))
                 ": it may be writing these files, so nothing was changed — try again when its turn ends")
     :running all}))

(defn begin-turn!
  "Turn `n` of the session `config` names begins: the work tree as it is
  now is the turn's start, and the work tree is marked as having a turn
  running (`running`). Its sha, or nil when nothing is kept."
  [config n & [question]]
  (when (on? config)
    (when-let [r (root config)]
      (let [id (str (:session-id config))]
        (when-let [sha (snapshot! r (str "byj " id " turn " n ", before"))]
          (swap! current assoc id {:turn n :root r :before sha :question question})
          (mark-running! r id n)
          sha)))))

(defn end-turn!
  "The running turn ends: the work tree as it is now is kept as the turn's
  ref, its parent the turn's start, whether anything changed or not — a
  turn that changed nothing still has a start to go back to (RFC-069). The
  number of files that differ, nil when nothing is kept."
  [config]
  (when (on? config)
    (let [id (str (:session-id config))
          {:keys [turn root before question]} (get @current id)]
      (swap! current dissoc id)
      (when root (unmark-running! root id))
      (when before
        (let [first-line (some-> question str str/split-lines first str/trim not-empty)
              msg (str "byj " id " turn " turn (when first-line (str ": " first-line)))]
          (when-let [after (snapshot! root msg before)]
            (git root nil "update-ref" "-m" "byj turn" (ref-name id turn) after)
            (prune! root (max 1 (or (:changes-turns config) 200)))
            (count (changed-files root before after))))))))

(defn- no-git [config]
  {:error (str (or (:project-dir config) (:working-dir config) ".")
               " is not in a git work tree: a turn's change is kept as git snapshots")})

(defn diff
  "Turn `turn` of session `id` (its last that changed files when nil):
  `{:turn :ref :before :after :root :files :diff}` — `:files` relative to
  `:root`, `:diff` git's — or `{:error}`."
  [config id & [turn]]
  (if-let [r (root config)]
    (let [turn (or turn (last (filter #(changed? r (ref-name id %)) (turns config id))))
          ref (when turn (ref-name id turn))
          {:keys [before after]} (when ref (commits r ref))]
      (cond
        (nil? turn) {:error (str "session " id " has no turn that changed files in " r)}
        (not after) {:error (str "turn " turn " of session " id " is not kept: the repository keeps its newest :changes-turns")}
        :else (let [files (changed-files r before after)]
                (if (empty? files)
                  {:error (str "turn " turn " of session " id " changed no files")}
                  {:turn turn :ref ref :before before :after after :root r
                   :files files
                   :diff (or (:ok (git r nil "diff" "--no-color" "--no-ext-diff" before after)) "")}))))
    (no-git config)))

;; --- going back (RFC-069) ----------------------------------------------------
;;
;; Each of these first snapshots the work tree as it is and keeps it as
;; refs/byj/rewinds/<session>/<n>, so what it replaces is never lost — the
;; person's own work since the turn included — and `undo-rewind!` puts it
;; back. The work tree is then made the target by git's own binary diff from
;; that snapshot, applied in one go: what differs is written, a file the
;; target lacks is removed, one it has is made. HEAD, the person's index and
;; their stash are not touched, as with the snapshots; nor is anything the
;; snapshots leave out (.gitignore's, .brainyard/, Emacs's lock, auto-save
;; and backup files).

(def ^:private rewind-prefix "refs/byj/rewinds/")

(defn- rewind-numbers [root id]
  (->> (str/split-lines (or (:ok (git root nil "for-each-ref" "--format=%(refname)" (str rewind-prefix (safe id) "/"))) ""))
       (keep turn-of) sort vec))

(defn- apply-tree!
  "The work tree at `root`, which is the commit `from`, made the tree-ish
  `to`: `{:files}` the paths it changed, or `{:error}` with nothing changed
  (git apply is all or nothing)."
  [root from to]
  (let [files (changed-files root from to)]
    (if (empty? files)
      {:files []}
      (let [patch (io/file (System/getProperty "java.io.tmpdir") (str "byj-rewind-" (random-uuid) ".patch"))]
        (try
          ;; git writes the patch itself: a file's bytes never pass through a
          ;; string, so one that is not UTF-8 comes back as it was
          (let [d (git root nil "diff" "--binary" "--no-color" "--no-ext-diff" "--no-renames" (str "--output=" (.getPath patch)) from to)
                a (when (:ok d) (git root nil "apply" "--whitespace=nowarn" (.getPath patch)))]
            (cond
              (:error d) {:error (:error d)}
              (:error a) {:error (str "git could not put the files back, and changed none: " (:error a))}
              :else {:files files}))
          (finally (.delete patch)))))))

(defn- restore!
  "The work tree of session `id`'s repository `root` made `target`, what it
  was first kept as the session's next rewind ref. `{:files :saved :from}` —
  `:saved` the ref, nil when nothing changed — or `{:error}`, `:running`
  too when another turn is running in the work tree (`running`)."
  [config root id target label]
  (or (busy-error root id)
      (if-let [now (snapshot! root (str "byj " id " " label))]
        (let [{:keys [files error]} (apply-tree! root now target)]
          (cond
            error {:error error}
            (empty? files) {:files [] :from now}
            :else (let [ref (str rewind-prefix (safe id) "/" (inc (or (last (rewind-numbers root id)) 0)))]
                    (git root nil "update-ref" "-m" "byj rewind" ref now)
                    (prune! root (max 1 (or (:changes-turns config) 200)) rewind-prefix)
                    {:files files :saved ref :from now})))
        {:error "the work tree could not be snapshotted, so nothing was changed"})))

(defn rewind!
  "Session `id`'s work tree put back as it was when turn `turn` began — or
  ended, with `end?` — whatever came after, the person's work included,
  kept first as a rewind ref. Its message names the session's last turn
  then (\"rewind to turn K's start, after turn N\"), so which turns it took
  back is known exactly, not from commit times to the second (byj-ng7.10).
  `{:turn :end? :files :saved}` or `{:error}`."
  [config id turn & [end?]]
  (if-let [r (root config)]
    (if-let [{:keys [before after]} (commits r (ref-name id turn))]
      (-> (restore! config r id (if end? after before)
                    (str "rewind to turn " turn (if end? "'s end" "'s start")
                         (when-let [n (last (turns config id))] (str ", after turn " n))))
          (merge {:turn turn :end? (boolean end?)}))
      {:error (str "turn " turn " of session " id " is not kept: byj turns list " id " says which are")})
    (no-git config)))

(defn undo-turn!
  "Turn `turn` of session `id` taken back alone: its change undone on the
  work tree as it is now, by git's three-way merge (merge-tree), so what
  came after it stays. Where something later changed the same lines the
  file is left with conflict markers, named in `:conflicts`.
  `{:turn :files :conflicts :saved}` or `{:error}`."
  [config id turn]
  (if-let [r (root config)]
    (if-let [{:keys [before after]} (commits r (ref-name id turn))]
      (if (empty? (changed-files r before after))
        {:error (str "turn " turn " of session " id " changed no files: there is nothing to undo")}
        (if-let [now (snapshot! r (str "byj " id " undo turn " turn))]
          ;; the turn's end as the base, the work tree as ours, the turn's
          ;; start as theirs: what the turn did, and only that, is undone
          (let [{:keys [exit out err]} (git* r nil "merge-tree" "--write-tree" "--name-only" "--no-messages"
                                             (str "--merge-base=" after) now before)
                [tree & conflicts] (str/split-lines out)]
            (if (and (#{0 1} exit) (re-matches #"[0-9a-f]{40,64}" (str tree)))
              (-> (restore! config r id tree (str "undo turn " turn))
                  (merge {:turn turn :conflicts (vec (distinct (remove str/blank? conflicts)))}))
              {:error (str "git could not merge the turn's undoing (git 2.40 or later is needed): " (str/trim (str err out)))}))
          {:error "the work tree could not be snapshotted, so nothing was changed"}))
      {:error (str "turn " turn " of session " id " is not kept: byj turns list " id " says which are")})
    (no-git config)))

(defn rewinds
  "Session `id`'s rewind refs still kept, ascending by number."
  [config id]
  (if-let [r (root config)] (rewind-numbers r id) []))

(defn undo-rewind!
  "Session `id`'s rewind `n` taken back — its last when nil, a rewind, an
  undone turn or an undo alike: the work tree put back as it was just
  before it. That is itself kept first, as the newest rewind, so taking the
  last back twice puts it back again. `{:files :saved :undid}` or
  `{:error}`."
  [config id & [n]]
  (if-let [r (root config)]
    (if-let [n (or n (last (rewind-numbers r id)))]
      (let [ref (str rewind-prefix (safe id) "/" n)]
        (if (line (git r nil "rev-parse" "--verify" "-q" (str ref "^{commit}")))
          (-> (restore! config r id ref (str "undo rewind " n)) (assoc :undid ref))
          {:error (str "session " id " has no rewind " n ": the repository keeps its newest :changes-turns")}))
      {:error (str "session " id " has no rewind to take back")})
    (no-git config)))

