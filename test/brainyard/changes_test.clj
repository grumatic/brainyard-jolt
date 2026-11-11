(ns brainyard.changes-test
  "A turn's change kept as git snapshots (RFC-067 phase 8, byj-gly.476,
  byj-gly.481): the work tree made into commit objects as the turn begins
  and ends, through an index of byj's own, and the turn kept as
  refs/byj/turns/<session>/<turn> whose parent is its start."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.changes :as changes]
            [brainyard.effect :as fx]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-changes-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- sh [dir & args]
  (let [r (apply fx/sh {:dir dir :out :string :err :string} args)]
    (assert (zero? (:exit r)) (str args ": " (:err r)))
    (str/trim (:out r))))

(defn- repo
  "A git repository at a fresh directory with one commit, signing asked for
  and no key to sign with — a snapshot must not sign."
  [name]
  (let [dir (fresh-dir name)]
    (sh dir "git" "init" "-q")
    (sh dir "git" "config" "user.name" "Someone")
    (sh dir "git" "config" "user.email" "someone@example.invalid")
    (sh dir "git" "config" "commit.gpgSign" "true")
    (spit (io/file dir ".gitignore") "ignored/\n")
    (spit (io/file dir "a.txt") "one\ntwo\n")
    (sh dir "git" "add" "-A")
    (sh dir "git" "-c" "commit.gpgSign=false" "commit" "-q" "-m" "first")
    dir))

(deftest a-turns-change-is-git-s-diff-between-its-start-and-end
  (let [dir (repo "turn")
        cfg {:project-dir dir :working-dir dir :session-id "s-1"}
        head (sh dir "git" "rev-parse" "HEAD")
        a (io/file dir "a.txt")]
    (spit (io/file dir "mine.txt") "the person's own, uncommitted\n")
    (sh dir "sh" "-c" "mkdir -p vendored && cd vendored && git init -q")
    (sh dir "git" "add" "mine.txt")
    (is (string? (changes/begin-turn! cfg 1 "change a and b\nand say why")))
    (spit a "one\nTWO\nthree\n")
    (testing "what bash writes is seen, since the work tree is"
      (sh dir "sh" "-c" "mkdir -p sub ignored && printf 'new\\n' > sub/b.txt && printf x > ignored/big && mkdir -p .brainyard/logs && printf l > .brainyard/logs/events.log"))
    (testing "and what Emacs leaves beside a file it visits is not the turn's"
      (sh dir "sh" "-c" "ln -s someone@host.1:1 .#a.txt && printf s > '#a.txt#' && printf o > a.txt~ && printf o > sub/b.txt~"))
    (is (= 2 (changes/end-turn! cfg)) "two files changed")
    (is (= [1] (changes/turns cfg "s-1")))
    (let [{:keys [turn ref before after root files diff]} (changes/diff cfg "s-1")]
      (is (= 1 turn) "the last turn that changed files, unless one is named")
      (is (= "refs/byj/turns/s-1/1" ref))
      (is (= dir root))
      (is (= before (sh dir "git" "rev-parse" (str ref "^"))) "the turn's start is its parent")
      (is (= after (sh dir "git" "rev-parse" ref)))
      (is (= ["a.txt" "sub/b.txt"] files) "named from the repository's top; what .gitignore names is not, nor byj's own .brainyard/, nor Emacs's lock, auto-save and backup files")
      (is (str/includes? diff "-two\n+TWO\n+three\n"))
      (is (str/includes? diff "+++ b/sub/b.txt\n@@ -0,0 +1 @@\n+new\n") "a file the turn made")
      (is (not (str/includes? diff "mine.txt")) "the person's work before the turn is not the turn's")
      (is (= "byj s-1 turn 1: change a and b" (sh dir "git" "log" "-1" "--format=%s" ref)))
      (is (= "byj byj@localhost" (sh dir "git" "log" "-1" "--format=%an %ae" ref)) "never the person's identity"))
    (testing "nothing of the person's is touched: HEAD, the index, the stash"
      (is (= head (sh dir "git" "rev-parse" "HEAD")))
      (is (= #{"A  mine.txt" " M a.txt" "?? sub/" "?? .brainyard/" "?? vendored/" "?? .#a.txt" "?? #a.txt#" "?? a.txt~"}
             (set (str/split-lines (:out (fx/sh {:dir dir :out :string} "git" "status" "--porcelain")))))
          "mine.txt still staged, a.txt still not")
      (is (= ["a.txt" "sub/b.txt"] (:files (changes/diff cfg "s-1"))) "a nested repository with no commit left out, not the snapshot")
      (is (= "" (sh dir "git" "stash" "list"))))
    (testing "what came after the turn is not the turn's"
      (spit a "later\n")
      (is (not (str/includes? (:diff (changes/diff cfg "s-1" 1)) "later"))))
    (testing "a turn that changed nothing is kept too, a start to go back to, but has no change to show"
      (changes/begin-turn! cfg 2)
      (is (= 0 (changes/end-turn! cfg)))
      (is (= [1 2] (changes/turns cfg "s-1")))
      (is (re-find #"changed no files" (:error (changes/diff cfg "s-1" 2))))
      (is (= 1 (:turn (changes/diff cfg "s-1"))) "the last turn that changed files, when none is named")
      (let [[r1 r2] (changes/rows cfg "s-1")]
        (is (= {:turn 1 :files ["a.txt" "sub/b.txt"] :subject "change a and b"} (select-keys r1 [:turn :files :subject])))
        (is (= {:turn 2 :files [] :subject ""} (select-keys r2 [:turn :files :subject])))
        (is (pos-int? (:at r2)))))
    (testing "the repository keeps its newest :changes-turns turns"
      (doseq [t [3 4 5]]
        (changes/begin-turn! cfg t)
        (spit a (str "turn " t "\n"))
        (changes/end-turn! (assoc cfg :changes-turns 2)))
      (is (= [4 5] (changes/turns cfg "s-1"))))
    (testing "none without a session id, or with :changes? false"
      (is (nil? (changes/begin-turn! (dissoc cfg :session-id) 6)))
      (is (nil? (changes/begin-turn! (assoc cfg :changes? false) 6)))
      (is (nil? (changes/end-turn! (assoc cfg :changes? false)))))
    (is (re-find #"no turn that changed files" (:error (changes/diff cfg "s-none"))))))

(deftest outside-git-nothing-is-kept
  (let [dir (fresh-dir "nogit")
        cfg {:project-dir dir :working-dir dir :session-id "s-n"}]
    (is (nil? (changes/begin-turn! cfg 1)))
    (is (nil? (changes/end-turn! cfg)))
    (is (= [] (changes/turns cfg "s-n")))
    (is (re-find #"not in a git work tree" (:error (changes/diff cfg "s-n"))))))

(defn- bytes-of [f] (vec (java.nio.file.Files/readAllBytes (.toPath (io/file f)))))

(deftest a-work-tree-goes-back-to-a-turn-and-a-turn-is-undone-alone
  ;; RFC-069: rewind!, undo-rewind!, undo-turn!
  (let [dir (repo "rewind")
        cfg {:project-dir dir :working-dir dir :session-id "s-r"}
        a (io/file dir "a.txt")
        head (sh dir "git" "rev-parse" "HEAD")
        turn! (fn [n f] (changes/begin-turn! cfg n (str "turn " n)) (f) (changes/end-turn! cfg))
        bin (byte-array [(unchecked-byte 0xff) (unchecked-byte 0xfe) 0 10 (unchecked-byte 0xc3)])]
    (spit (io/file dir "staged.txt") "staged\n")
    (sh dir "git" "add" "staged.txt")
    (spit a "one\ntwo\nthree\n")
    (turn! 1 #(do (spit a "ONE\ntwo\nthree\n")
                  (spit (io/file dir "b.txt") "b\n")
                  (java.nio.file.Files/write (.toPath (io/file dir "bin.dat")) bin (make-array java.nio.file.OpenOption 0))))
    (turn! 2 #(spit a "ONE\ntwo\nTHREE\n"))
    (spit (io/file dir "mine.txt") "the person's, after the turns\n")
    (testing "back to turn 2's start: what came after it goes, the person's work too, kept first"
      (let [{:keys [files saved error]} (changes/rewind! cfg "s-r" 2)]
        (is (nil? error))
        (is (= ["a.txt" "mine.txt"] files))
        (is (= "refs/byj/rewinds/s-r/1" saved))
        (is (= "ONE\ntwo\nthree\n" (slurp a)))
        (is (not (.exists (io/file dir "mine.txt"))))
        (is (= "the person's, after the turns" (sh dir "git" "show" (str saved ":mine.txt"))) "what it replaced is in the rewind's ref")))
    (testing "and taken back"
      (let [{:keys [files undid]} (changes/undo-rewind! cfg "s-r")]
        (is (= "refs/byj/rewinds/s-r/1" undid))
        (is (= ["a.txt" "mine.txt"] files))
        (is (= "ONE\ntwo\nTHREE\n" (slurp a)))
        (is (= "the person's, after the turns\n" (slurp (io/file dir "mine.txt"))))))
    (testing "back to turn 1's start: a file the turn made is removed, bytes that are not UTF-8 come back as they were"
      (changes/rewind! cfg "s-r" 1)
      (is (= "one\ntwo\nthree\n" (slurp a)))
      (is (not (.exists (io/file dir "b.txt"))))
      (is (not (.exists (io/file dir "bin.dat"))))
      (changes/rewind! cfg "s-r" 1 true)
      (is (= (vec bin) (bytes-of (io/file dir "bin.dat"))) "turn 1's end")
      (changes/undo-rewind! cfg "s-r")
      (is (= "one\ntwo\nthree\n" (slurp a)) "the last taken back: turn 1's start again")
      (changes/undo-rewind! cfg "s-r")
      (is (= (vec bin) (bytes-of (io/file dir "bin.dat"))) "and taken back twice, its end again")
      (is (= [1 2 3 4 5 6] (changes/rewinds cfg "s-r")) "each rewind and each undo kept")
      (changes/undo-rewind! cfg "s-r" 3)
      (is (= "ONE\ntwo\nTHREE\n" (slurp a)) "a rewind named: as it was before the first rewind to turn 1")
      (is (.exists (io/file dir "mine.txt"))))
    (testing "turn 1 undone alone: turn 2's change and the person's work stay"
      (let [{:keys [files conflicts error]} (changes/undo-turn! cfg "s-r" 1)]
        (is (nil? error) error)
        (is (= [] conflicts))
        (is (= #{"a.txt" "b.txt" "bin.dat"} (set files)))
        (is (= "one\ntwo\nTHREE\n" (slurp a)))
        (is (not (.exists (io/file dir "b.txt"))))
        (is (.exists (io/file dir "mine.txt"))))
      (changes/undo-rewind! cfg "s-r"))
    (testing "where something later changed the same line, it is a conflict, marked in the file"
      (spit a "ONE!\ntwo\nTHREE\n")
      (let [{:keys [conflicts]} (changes/undo-turn! cfg "s-r" 1)]
        (is (= ["a.txt"] conflicts))
        (is (str/includes? (slurp a) "<<<<<<<")))
      (changes/undo-rewind! cfg "s-r")
      (is (= "ONE!\ntwo\nTHREE\n" (slurp a))))
    (testing "what cannot be done says so and changes nothing"
      (is (re-find #"not kept" (:error (changes/rewind! cfg "s-r" 9))))
      (is (re-find #"no rewind" (:error (changes/undo-rewind! cfg "s-none"))))
      (changes/begin-turn! cfg 3) (changes/end-turn! cfg)
      (is (re-find #"changed no files" (:error (changes/undo-turn! cfg "s-r" 3)))))
    (testing "HEAD and the index are the person's still"
      (is (= head (sh dir "git" "rev-parse" "HEAD")))
      (is (= "staged.txt" (sh dir "git" "diff" "--cached" "--name-only"))))))


(deftest a-turn-running-in-the-work-tree-holds-a-rewind-back
  ;; RFC-069, byj-gly.487: a turn marks its work tree while it runs, and a
  ;; rewind of any session there waits for it rather than racing its writes
  (let [dir (repo "running")
        cfg {:project-dir dir :working-dir dir :session-id "s-a"}
        other {:project-dir dir :working-dir dir :session-id "s-b"}
        a (io/file dir "a.txt")
        running-dir (io/file (sh dir "git" "rev-parse" "--path-format=absolute" "--git-path" "byj/running"))
        marker! (fn [session pid] (.mkdirs running-dir)
                  (spit (io/file running-dir session) (pr-str {:session session :turn 7 :pid pid :at 0})))]
    (changes/begin-turn! cfg 1 "one")
    (spit a "ONE\n")
    (changes/end-turn! cfg)
    (is (empty? (changes/running dir)) "a turn that ended leaves no mark")
    (testing "another session of this process mid-turn"
      (changes/begin-turn! other 1 "b's")
      (is (= ["s-b"] (map :session (changes/running dir "s-a"))))
      (let [r (changes/rewind! cfg "s-a" 1)]
        (is (re-find #"session s-b is in the middle of turn 1" (str (:error r))))
        (is (= "ONE\n" (slurp a)) "nothing changed")
        (is (empty? (changes/rewinds cfg "s-a")) "nor kept"))
      (is (empty? (changes/running dir "s-b")) "the asker's own turn is not in its way")
      (changes/end-turn! other)
      (is (empty? (changes/running dir "s-a"))))
    (testing "a session of another live process — pid 1 is always alive, and not ours — the asker's id or not"
      (marker! "s-a" 1)
      (let [r (changes/undo-turn! cfg "s-a" 1)]
        (is (re-find #"session s-a is in the middle of turn 7 in this work tree \(process 1\)" (str (:error r))))
        (is (= "ONE\n" (slurp a))))
      (is (:error (changes/undo-rewind! cfg "s-a")))
      (.delete (io/file running-dir "s-a")))
    (testing "a mark whose process is gone is stale: removed, and nothing held back"
      (marker! "s-gone" 999999)
      (is (empty? (changes/running dir "s-a")))
      (is (not (.exists (io/file running-dir "s-gone"))))
      (is (= ["a.txt"] (:files (changes/rewind! cfg "s-a" 1))))
      (is (= "one\ntwo\n" (slurp a))))))
