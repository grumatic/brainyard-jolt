;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.worktree
  "A project's git worktrees: adding one on a branch of its own (teams,
  RFC-032; agent$claude, RFC-039) and listing the ones it has (RFC-033, whose
  agent page lists the Claude Code sessions working in any of them).

  `dirs` reads git's own files rather than running `git worktree list`: the
  agent page asks for the project's sessions every 2 s, and a subprocess each
  time would be most of what that costs. What it reads is git's documented
  layout (gitrepository-layout(5)): a worktree's `.git` is a file,
  `gitdir: <common>/worktrees/<name>`, which holds `commondir` and `gitdir`
  (the worktree's own `.git` file)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]))

(defn- git [dir & args]
  (try (let [r (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args)]
         (if (zero? (:exit r)) {:ok (str/trim (:out r))} {:error (str/trim (str (:err r) (:out r)))}))
       (catch Throwable e {:error (ex-message e)})))

(def default-includes
  "What a new worktree is given of the checkout's ignored files when the
  repository has no `.worktreeinclude`: its secrets, which a session there
  needs as the checkout's does (byj-9f8)."
  [".env" ".brainyard/.env"])

(defn include!
  "Copy into new worktree `dir` the files of `project`'s checkout that git
  ignores and the repository's `.worktreeinclude` names — gitignore's syntax,
  as Claude Code reads the same file — or, with none, `default-includes`: a
  worktree checks out only what is committed, and a `.env` never is
  (byj-9f8). Each copied with its mode. The paths copied, from the top."
  [project dir]
  (try
    (when-let [top (:ok (git project "rev-parse" "--show-toplevel"))]
      (let [own (io/file top ".worktreeinclude")
            pats (if (.isFile own)
                   own
                   (doto (java.io.File/createTempFile "byj-worktreeinclude" ".txt")
                     (spit (str (str/join "\n" default-includes) "\n"))))
            listed (git top "ls-files" "--others" "--ignored" (str "--exclude-from=" (.getPath pats)))]
        (when-not (= own pats) (.delete pats))
        (vec (for [rel (remove str/blank? (str/split-lines (str (:ok listed))))
                   :let [src (io/file top rel) dst (io/file (str dir) rel)]
                   ;; only what git ignores: a file it would track is the branch's
                   :when (and (.isFile src) (not (.exists dst))
                              (:ok (git top "check-ignore" "-q" rel)))
                   :when (do (io/make-parents dst)
                             (zero? (:exit (fx/sh {:out :string :err :string} "cp" "-p" (.getPath src) (.getPath dst)))))]
               rel))))
    (catch Throwable _ [])))

(defn unshared
  "What of `project`'s `.brainyard/` is not committed — `git status
  --porcelain` lines — which a worktree, checked out from a commit, does not
  have: a session there reads its branch's (byj-9f8). Ignored files are not
  among them."
  [project]
  ;; not through `git`, which trims: a status line opens with a space
  (let [r (try (fx/sh {:dir (str project) :out :string :err :string} "git" "status" "--porcelain" "--" ".brainyard")
               (catch Throwable _ nil))]
    (if (and r (zero? (:exit r)))
      (vec (remove str/blank? (str/split-lines (str (:out r)))))
      [])))

(defn add!
  "A git worktree of `project` at `dir`, on a new branch `branch` from `from`
  (HEAD when nil): `{:dir :branch :included :unshared}` with `dir`
  canonical — `:included` the ignored files given it (`include!`),
  `:unshared` what of the project's `.brainyard/` is not committed, so not
  there (`unshared`) — or `{:error}`. teams' `add-worktree!` before it was
  shared."
  [project dir branch & [from]]
  (let [r (git project "worktree" "add" "-b" branch (str dir) (or from "HEAD"))]
    (if (:error r)
      {:error (str "could not add a worktree on " branch ": " (:error r))}
      (cond-> {:dir (.getCanonicalPath (io/file (str dir))) :branch branch}
        true (assoc :included (include! project dir))
        (seq (unshared project)) (assoc :unshared (unshared project))))))

(defn- branch? [project b]
  (:ok (git project "rev-parse" "--verify" "-q" (str "refs/heads/" b))))

(defn open!
  "A worktree of `project` at `dir` for a session to start in (byj-z0z):
  `branch` checked out as it is when it exists and no `from` is named —
  git refuses one checked out in another worktree, and its words are the
  answer — else a new branch `branch` from `from` (HEAD when nil). A clean
  checkout: nothing uncommitted is carried. `{:dir :branch :base :created}`
  — `:base` the commit checked out, `:created` whether the branch is new —
  or `{:error}`."
  [project dir branch from]
  (let [existing? (and (nil? from) (branch? project branch))
        r (if existing?
            (let [r (git project "worktree" "add" (str dir) branch)]
              (if (:error r)
                {:error (str "could not check out " branch " in a worktree: " (:error r))}
                {:dir (.getCanonicalPath (io/file (str dir))) :branch branch}))
            (add! project dir branch from))]
    (if (:error r)
      r
      (assoc r :base (:ok (git (:dir r) "rev-parse" "HEAD")) :created (not existing?)))))

(defn within
  "`project-dir`'s place in worktree `wt-dir` of the repository whose top is
  `top`: the project may be a subdirectory of its repository."
  [top project-dir wt-dir]
  (let [top (.getCanonicalPath (io/file (str top)))
        pd (.getCanonicalPath (io/file (str project-dir)))]
    (if (str/starts-with? pd (str top "/"))
      (.getPath (io/file (str wt-dir) (subs pd (inc (count top)))))
      (str wt-dir))))

(defn top
  "The top of the git work tree `dir` is in, or nil."
  [dir]
  (:ok (git dir "rev-parse" "--show-toplevel")))

(defn remove!
  "The worktree at `dir` and its branch `branch` taken away again, what
  they held with them: for one just added that could not be made what it
  was for (byj-8yt)."
  [project dir branch]
  (git project "worktree" "remove" "--force" (str dir))
  (git project "branch" "-D" branch))

(defn- read-line* [f]
  (try (some-> (slurp f) str/trim not-empty) (catch Throwable _ nil)))

(defn- resolve-from [base path]
  (let [f (io/file path)]
    (if (.isAbsolute f) f (io/file base path))))

(defn common-dir
  "The repository's shared git directory for `dir` — a main worktree or a
  linked one — or nil when `dir` is not the top of a worktree."
  [dir]
  (let [dot-git (io/file (str dir) ".git")]
    (cond
      (.isDirectory dot-git) (.getCanonicalFile dot-git)
      (.isFile dot-git)
      (when-let [g (some->> (read-line* dot-git) (re-find #"^gitdir:\s*(.+)$") second)]
        (let [wt-git (resolve-from (io/file (str dir)) g)
              common (some->> (read-line* (io/file wt-git "commondir")) (resolve-from wt-git))]
          (some-> (or common (some-> wt-git .getParentFile .getParentFile)) .getCanonicalFile)))
      :else nil)))

(defn dirs
  "Every worktree of the repository `dir` belongs to, canonical paths, the main
  one first and `dir` itself always among them; just `[dir]` when it is not in
  a repository git can read. A worktree whose directory is gone is left out."
  [dir]
  (let [here (.getCanonicalPath (io/file (str dir)))]
    (if-let [common (common-dir dir)]
      (let [main (.getParentFile common)
            linked (for [w (sort-by #(.getName ^java.io.File %) (or (.listFiles (io/file common "worktrees")) []))
                         :let [g (some->> (read-line* (io/file w "gitdir")) (resolve-from w))]
                         :when (and g (.exists g))]
                     (.getCanonicalPath (.getParentFile (.getCanonicalFile g))))
            all (distinct (cons (.getCanonicalPath main) linked))]
        (vec (if (some #{here} all) all (cons here all))))
      [here])))

;; --- what a session changed (byj-gly.326) ---------------------------------------------------

(defn session-diff
  "What a session working in `dir` has changed, read-only: in a linked
  worktree, everything since it left the main checkout's branch — its
  commits and what is not yet committed, against their merge base; in the
  main checkout, whose history the branch shares with everyone, what is not
  yet committed, against HEAD. `{:dir :kind (:worktree|:main) :branch
  :against :commits :untracked :stat [lines] :diff-args [git args]}`, or
  `{:dir :error}`."
  [dir]
  (cond
    (not (.isDirectory (io/file (str dir))))
    {:dir (str dir) :error "its directory is gone"}

    :else
    (let [top (:ok (git dir "rev-parse" "--show-toplevel"))
          common (when top (common-dir top))]
      (if-not (and top common)
        {:dir (str dir) :error "not in a git repository"}
        (let [main (.getCanonicalPath (.getParentFile ^java.io.File common))
              linked? (not= (.getCanonicalPath (io/file top)) main)
              branch (:ok (git top "rev-parse" "--abbrev-ref" "HEAD"))
              lines #(some->> (:ok %) str/split-lines (remove str/blank?) vec)]
          (if linked?
            (let [against (:ok (git main "rev-parse" "--abbrev-ref" "HEAD"))
                  base (when against (:ok (git top "merge-base" "HEAD" against)))]
              (if-not base
                {:dir top :error (str "no common base with " (or against "the main checkout"))}
                {:dir top :kind :worktree :branch branch :against against
                 :commits (some-> (:ok (git top "rev-list" "--count" (str base "..HEAD"))) parse-long)
                 :stat (or (lines (git top "diff" "--stat" base)) [])
                 :diff-args ["diff" base]}))
            {:dir top :kind :main :branch branch
             :untracked (count (or (lines (git top "ls-files" "--others" "--exclude-standard")) []))
             :stat (or (lines (git top "diff" "--stat" "HEAD")) [])
             :diff-args ["diff" "HEAD"]}))))))

(defn diff-lines
  "`session-diff`'s answer as the agent page shows it under a row: where it
  stands, then the files, `max-files` at most."
  [{:keys [error kind branch against commits untracked stat dir]} & [max-files]]
  (let [max-files (or max-files 8)
        files (butlast stat)
        total (last stat)]
    (if error
      [(str "no diff: " error)]
      (vec (concat
            [(str (case kind
                    :worktree (str "worktree " dir " · " branch ", " (or commits 0) " commit(s) past " against)
                    (str "main checkout " dir " · " branch " · uncommitted"))
                  (if (seq stat) (str " · " (str/trim total)) " · nothing changed")
                  (when (pos? (or untracked 0)) (str " · " untracked " untracked")))]
            (map #(str "  " (str/trim %)) (take max-files files))
            (when (> (count files) max-files) [(str "  … " (- (count files) max-files) " more file(s)")])
            (when (seq stat) ["  ctrl+g again: the whole diff in $PAGER"]))))))

(defn pager-argv
  "The command that shows `session-diff`'s whole diff in the person's pager,
  coloured: `$PAGER`, else `less -R`."
  [{:keys [dir diff-args]}]
  ["sh" "-c" (str "git -C '" (str/replace (str dir) "'" "'\\''") "' -c color.ui=always "
                  (str/join " " diff-args) " | ${PAGER:-less -R}")])
