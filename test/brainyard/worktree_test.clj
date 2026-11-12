;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.worktree-test
  "A project's git worktrees (byj-gly.290.2): one added on a branch of its own,
  and every one listed from git's own files — and the Claude Code sessions
  working in them listed with the project's (RFC-033)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.claude-sessions :as cs]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.worktree :as worktree]))

(defn- fresh-dir [name]
  (let [d (io/file "target/worktree-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- git! [dir & args] (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args))

(defn- repo! [name]
  (let [dir (fresh-dir name)]
    (spit (io/file dir "README.md") "x\n")
    (git! dir "init" "-q")
    (git! dir "add" ".")
    (git! dir "-c" "user.name=t" "-c" "user.email=t@example.invalid" "commit" "-qm" "init")
    dir))

(deftest a-projects-worktrees-are-added-and-listed
  (let [project (repo! "project")
        inside (io/file project ".brainyard" "wt" "a")
        beside (str project "-b")]
    (doseq [f (reverse (file-seq (io/file beside)))] (.delete f))
    (testing "added on a branch of its own"
      (is (= {:dir (.getCanonicalPath (io/file project ".brainyard/wt/a")) :branch "bd/a"}
             (worktree/add! project inside "bd/a")))
      (is (= beside (:dir (worktree/add! project beside "bd/b"))))
      (is (:error (worktree/add! project (str beside "-again") "bd/b")) "a branch that exists is an error"))
    (testing "listed from any of them, the main one first"
      (let [all [project (.getCanonicalPath inside) beside]]
        (is (= all (worktree/dirs project)))
        (is (= all (worktree/dirs beside)))))
    (testing "a directory in no repository is its own only one"
      (let [d (fresh-dir "plain")]
        (is (= [d] (worktree/dirs d)))))
    (testing "a worktree whose directory is gone is left out"
      (doseq [f (reverse (file-seq (io/file beside)))] (.delete f))
      (is (= [project (.getCanonicalPath inside)] (worktree/dirs project))))))

(defn- line [m] (str (json/write-str m) "\n"))

(deftest sessions-in-a-projects-worktrees-are-its-own
  ;; byj-gly.290.2: Claude Code keeps a worktree's sessions under the
  ;; worktree's own directory name, so the agent page did not see them
  (let [project (repo! "listed")
        wt (:dir (worktree/add! project (io/file project ".brainyard" "wt" "x") "bd/x"))
        home (fresh-dir "home")
        save! (fn [dir id title] (let [d (io/file home "projects" (cs/project-key dir))]
                                   (.mkdirs d)
                                   (spit (io/file d (str id ".jsonl")) (line {:type "ai-title" :aiTitle title}))))]
    (save! project "p-1" "in the project")
    (save! wt "w-1" "in the worktree")
    (.mkdirs (io/file home "sessions"))
    (spit (io/file home "sessions" "7.json") (json/write-str {:pid 7 :sessionId "w-2" :cwd wt :status "busy" :updatedAt 1}))
    (let [{:keys [live saved]} (cs/sessions project :home home :alive? #{7})]
      (is (= #{["p-1" nil] ["w-1" wt]} (set (map (juxt :id :dir) saved))) "saved: each names the worktree it is in")
      (is (= [["w-2" wt "busy"]] (map (juxt :id :dir :status) live)) "live in a worktree"))
    (is (= ["p-1"] (map :id (:saved (cs/sessions project :home home :alive? #{} :dirs [project]))))
        "`:dirs` says where to look")))

(deftest what-a-session-changed-is-read-where-it-works
  ;; byj-gly.326: any session — brainyard's or Claude Code's, in the main
  ;; checkout or a worktree — before its work is taken
  (let [project (repo! "diff-project")
        wt (.getCanonicalPath (io/file project ".brainyard" "worktrees" "bd-x"))]
    ;; as a project /init prepared keeps it: its worktrees are not its files
    (spit (io/file project ".git" "info" "exclude") ".brainyard/\n" :append true)
    (worktree/add! project wt "bd/x")
    (spit (io/file wt "a.js") "a\n")
    (git! wt "add" "a.js")
    (git! wt "-c" "user.name=t" "-c" "user.email=t@example.invalid" "commit" "-qm" "a")
    (spit (io/file wt "README.md") "x\ny\n")
    (spit (io/file project "README.md") "changed\n")
    (spit (io/file project "new.txt") "n\n")
    (testing "a worktree: its commits and what is not committed, since it left the main branch"
      (let [d (worktree/session-diff wt)
            ls (worktree/diff-lines d)]
        (is (= [:worktree "bd/x" 1] ((juxt :kind :branch :commits) d)))
        (is (= ["diff"] (take 1 (:diff-args d))))
        (is (re-find #"worktree .* · bd/x, 1 commit\(s\) past \S+ · 2 files changed" (first ls)))
        (is (some #(str/starts-with? % "  a.js") ls))
        (is (some #(str/starts-with? % "  README.md") ls))
        (is (= "  ctrl+g again: the whole diff in $PAGER" (last ls)))))
    (testing "the main checkout: what is not committed, and what is untracked"
      (let [d (worktree/session-diff project)]
        (is (= [:main ["diff" "HEAD"] 1] ((juxt :kind :diff-args :untracked) d)))
        (is (re-find #"main checkout .* uncommitted · 1 file changed.* · 1 untracked" (first (worktree/diff-lines d))))))
    (testing "a subdirectory is its checkout's"
      (is (= :main (:kind (worktree/session-diff (doto (io/file project "sub") .mkdirs))))))
    (testing "nothing changed, a directory gone, and none in git, each said"
      (is (str/ends-with? (first (worktree/diff-lines (worktree/session-diff (repo! "clean")))) "nothing changed"))
      (is (= ["no diff: its directory is gone"] (worktree/diff-lines (worktree/session-diff (str project "/gone")))))
      (is (= ["no diff: not in a git repository"] (worktree/diff-lines (worktree/session-diff (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "no-git-" (System/nanoTime))) .mkdirs)))))))
    (testing "the pager runs git in the session's directory, quoted"
      (let [argv (worktree/pager-argv {:dir "/a b/it's" :diff-args ["diff" "HEAD"]})]
        (is (= ["sh" "-c"] (take 2 argv)))
        (is (str/includes? (last argv) "git -C '/a b/it'\\''s' -c color.ui=always diff HEAD | ${PAGER:-less -R}"))))))

