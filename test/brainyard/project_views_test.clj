(ns brainyard.project-views-test
  "The right rail's views of a project (byj-gly.380): files, and the
  changes, commits and branch graph as sections of Source (byj-gly.397),
  read from a repository of the test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.project-views :as pv]
            [brainyard.tui-frame :as frame]))

(defn- fresh-dir [name]
  (let [d (io/file "target/project-views-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- outside-dir
  "A directory in no repository: one under `target/` is inside this one, and
  git answers for it."
  [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-project-views-" name "-" (System/currentTimeMillis)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- git! [dir & args]
  (let [r (apply fx/sh {:dir dir :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@t" args)]
    (assert (zero? (:exit r)) (str args " " (:err r)))
    (:out r)))

(deftest what-git-says-is-parsed
  (is (= [{:state "M" :path "src/a.clj"} {:state "??" :path "new.txt"} {:state "R" :path "b2.clj"}]
         (pv/parse-status " M src/a.clj\n?? new.txt\nR  b.clj -> b2.clj\n")))
  (is (= [{:short "abc1234" :hash "abc1234ffff" :subject "fix: the tabs\tand more"}]
         (pv/parse-log "abc1234\tabc1234ffff\tfix: the tabs\tand more\n")))
  (is (= [{:hash "d9ff" :short "d9e2b92" :parents ["5fff" "18ff"] :refs "HEAD -> main" :subject "Merge team/lang"}
          {:hash "18ff" :short "18d76b1" :parents [] :refs "" :subject "Add --lang\tfr"}]
         (pv/parse-graph (str "d9ff\td9e2b92\t5fff 18ff\tHEAD -> main\tMerge team/lang\n"
                              "18ff\t18d76b1\t\t\tAdd --lang\tfr\n")))))

(defn- drawn
  "The lanes of `commits` as text, a row a commit: what the rail shows."
  [commits]
  (mapv #(str (apply str (map first (:cells %))) (:short %)) (pv/lanes commits)))

(deftest the-graph-is-laid-out-in-lanes
  ;; byj-gly.398: one row a commit, as lazygit and VS Code draw it
  (let [c (fn [h & parents] {:hash h :short h :parents (vec parents)})]
    (testing "two branches merged one after the other"
      (is (= ["● dd"
              "●─╮ d9"
              "│ ● 18"
              "●─┼─╮ 5f"
              "│ │ ● 48"
              "●─┴─╯ 7e"]
             (drawn [(c "dd" "d9") (c "d9" "5f" "18") (c "18" "7e") (c "5f" "7e" "48") (c "48" "7e") (c "7e")]))))
    (testing "a branch that leaves and is not merged runs on to where it left"
      (is (= ["● tip" "│ ● main" "●─╯ base"] (drawn [(c "tip" "base") (c "main" "base") (c "base")]))))
    (testing "a line goes on past the last commit shown when its parent was not read"
      (is (= ["●─╮ m" "● │ a" "│ ● b"] (drawn [(c "m" "a" "b") (c "a" "gone") (c "b" "gone2")]))))
    (testing "each lane keeps its colour, and the commit takes its lane's"
      (let [[merge side base] (pv/lanes [(c "m" "a" "b") (c "b" "a") (c "a")])]
        (is (= :cyan (second (first (:cells merge)))))
        (is (= :magenta (second (nth (:cells merge) 2))) "the branch's lane, opened second")
        (is (= :magenta (second (nth (:cells side) 2))))
        (is (= :cyan (second (first (:cells base)))))))))

(deftest the-map-keeps-what-shows-the-branches
  ;; byj-gly.398: names, merges, forks; a line of commits between them is left
  ;; out, and a merge stays even when one side carries no name (git's
  ;; --simplify-by-decoration dropped it)
  (let [c (fn [h refs & parents] {:hash h :short h :refs refs :parents (vec parents)})
        m (pv/branch-map [(c "head" "HEAD -> main" "m2")
                          (c "m2" "" "m1")
                          (c "m1" "" "merge")
                          (c "merge" "" "x" "t2")
                          (c "t2" "team/times" "t1")
                          (c "t1" "" "fork")
                          (c "x" "" "fork")
                          (c "fork" "" "r1")
                          (c "r1" "" "root")
                          (c "root" "")])]
    (is (= [["head" ["merge"]] ["merge" ["fork" "t2"]] ["t2" ["fork"]] ["fork" ["root"]] ["root" []]]
           (mapv (juxt :hash :parents) m)))))

(deftest a-tree-folds-its-directories
  (let [paths ["README.md" "src/a.clj" "src/b/c.clj" "test/a_test.clj"]]
    (is (= ["▸ src/" "▸ test/" "  README.md"] (mapv :label (pv/tree-rows paths #{}))))
    (is (= ["2" "1"] (keep :hint (pv/tree-rows paths #{}))) "a folded directory counts the files under it")
    (is (= ["▾ src/" "  ▸ b/" "    a.clj" "▸ test/" "  README.md"] (mapv :label (pv/tree-rows paths #{"src"}))))
    (is (= "src/b" (:dir (second (pv/tree-rows paths #{"src"})))))))

(deftest a-repository-is-read-and-a-directory-is-walked
  (let [up (fresh-dir "upstream.git")
        dir (fresh-dir "repo")]
    (git! up "init" "-q" "--bare")
    (git! dir "init" "-q" "-b" "main")
    (spit (io/file dir "a.txt") "a")
    (git! dir "add" ".") (git! dir "commit" "-q" "-m" "first")
    (git! dir "remote" "add" "origin" up) (git! dir "push" "-q" "-u" "origin" "main")
    (spit (io/file dir "b.txt") "b")
    (git! dir "add" "b.txt") (git! dir "commit" "-q" "-m" "second")
    (spit (io/file dir "a.txt") "changed")
    (spit (io/file dir ".gitignore") "ignored.log\n")
    (spit (io/file dir "ignored.log") "x")
    (let [r (fx/run-value!! (pv/read-task dir))]
      (testing "files: tracked and untracked, ignored ones left out"
        (is (= [".gitignore" "a.txt" "b.txt"] (:files r))))
      (testing "changes: one row a path with its state"
        (is (= #{{:state "M" :path "a.txt"} {:state "??" :path ".gitignore"}} (set (:changes r)))))
      (testing "commits: newest first, the one not pushed is ahead"
        (is (= ["second" "first"] (mapv :subject (:commits r))))
        (is (= #{(:hash (first (:commits r)))} (:ahead r)))
        (is (:upstream? r)))))
  (testing "not a repository: its files, from a walk, and nothing else"
    (let [dir (outside-dir "plain")]
      (try
        (.mkdirs (io/file dir "sub"))
        (spit (io/file dir "sub" "a.txt") "a")
        (spit (io/file dir ".hidden") "h")
        (is (= {:repo? false :files ["sub/a.txt"]} (fx/run-value!! (pv/read-task dir))))
        (finally (fx/sh {:out :string :err :string} "rm" "-rf" dir))))))

(deftest the-views-read-when-shown-and-say-why-not
  (let [dir (outside-dir "views-plain")
        _ (spit (io/file dir "x.txt") "x")
        {:keys [views refresh! state]} (pv/views dir)
        [files source] views
        settle #(loop [n 0] (when (and (:reading? @state) (< n 100)) (Thread/sleep 50) (recur (inc n))))]
   (try
    (is (= "  reading…" (:label (first ((:items files))))) "nothing read yet")
    ((:on-show files)) (settle)
    (is (= ["  x.txt"] (mapv :label ((:items files)))))
    (is (= ["  not a git repository"] (mapv :label ((:items source)))))
    (testing "Enter on a directory unfolds it"
      (.mkdirs (io/file dir "d")) (spit (io/file dir "d" "y.txt") "y")
      (refresh!) (settle)
      ((:act files) (first ((:items files))))
      (is (= ["▾ d/" "    y.txt" "  x.txt"] (mapv :label ((:items files))))))
    (finally (fx/sh {:out :string :err :string} "rm" "-rf" dir)))))

(deftest a-row-opens-its-file-or-shows-its-diff
  (let [dir (fresh-dir "acting")
        shown (promise)
        opened (atom nil)]
    (git! dir "init" "-q" "-b" "main")
    (spit (io/file dir "a.txt") "one\n")
    (git! dir "add" ".") (git! dir "commit" "-q" "-m" "add a")
    (spit (io/file dir "a.txt") "one\ntwo\n")
    (spit (io/file dir "new.txt") "fresh\n")
    (testing "a change's diff, against HEAD; a file git does not track, as the whole of it added"
      (let [d (fx/run-value!! (pv/diff-task dir {:path "a.txt" :state "M"}))]
        (is (str/includes? d "+two")))
      (is (str/includes? (fx/run-value!! (pv/diff-task dir {:path "new.txt" :state "??"})) "+fresh")))
    (testing "a commit as git show says it"
      (let [hash (str/trim (git! dir "rev-parse" "HEAD"))]
        (is (str/includes? (fx/run-value!! (pv/show-task dir hash)) "add a"))))
    (testing "the views hand what a row does to the caller"
      (let [{:keys [views refresh! state]} (pv/views dir {:open #(reset! opened %) :show #(deliver shown [%1 %2])})
            [files source] views]
        (refresh!)
        (loop [n 0] (when (and (:reading? @state) (< n 100)) (Thread/sleep 50) (recur (inc n))))
        ((:act files) {:path "a.txt"})
        (is (= (.getCanonicalPath (io/file dir "a.txt")) @opened) "Enter on a file opens it, by its whole path")
        (is (= "@a.txt" ((:put files) {:path "a.txt"})) "@ mentions it")
        ((:act source) (first (filter #(= "a.txt" (:path %)) ((:items source)))))
        (let [[title text] (deref shown 5000 nil)]
          (is (= "git diff — a.txt" title))
          (is (str/includes? (str text) "+two")))
        (is (nil? ((:put source) (first (filter :hash ((:items source)))))) "a commit is not a path to mention")))))

(deftest source-holds-the-changes-the-commits-and-the-graph
  ;; byj-gly.397: one view of sections, each heading folding with Enter, and
  ;; the graph of every branch, merges and all
  (let [dir (fresh-dir "source")
        shown (atom [])
        commit! (fn [msg] (spit (io/file dir "f.txt") (str msg "\n") :append true)
                  (git! dir "add" ".") (git! dir "commit" "-q" "-m" msg))]
    (git! dir "init" "-q" "-b" "main")
    (commit! "initial")
    (git! dir "checkout" "-q" "-b" "team/times") (commit! "add times")
    (git! dir "checkout" "-q" "main")
    (git! dir "merge" "-q" "--no-ff" "team/times" "-m" "merge times")
    (spit (io/file dir "new.txt") "x")
    (let [{:keys [views refresh! state]} (pv/views dir {:show (fn [t _] (swap! shown conj t))})
          [_ source] views
          labels #(mapv :label ((:items source)))]
      (refresh!)
      (loop [n 0] (when (and (:reading? @state) (< n 100)) (Thread/sleep 50) (recur (inc n))))
      (testing "the sections, each a heading with its count"
        (is (= [["▾ Changes" "1"] ["▾ Commits" "3"] ["▾ Graph" "3"]]
               (for [r ((:items source)) :when (:section r)] [(:label r) (:hint r)]))))
      (testing "the graph draws the branch and its merge in lanes, its branch named"
        (let [g (rest (drop-while #(not= "▾ Graph" %) (labels)))]
          (is (re-matches #"  ●─╮ [0-9a-f]+ \(HEAD -> main\) merge times" (nth g 0)))
          (is (re-matches #"  │ ● [0-9a-f]+ \(team/times\) add times" (nth g 1)))
          (is (re-matches #"  ●─╯ [0-9a-f]+ initial" (nth g 2)))
          (is (= [["  " nil] ["●" {:color :cyan}] ["─" {:color :magenta}] ["╮" {:color :magenta}] [" " nil]]
                 (take 5 (:segments (first (filter #(and (:hash %) (:segments %)) ((:items source)))))))
              "its lanes in their colours")))
      (testing "Enter on a heading folds its section, and again unfolds it"
        ((:act source) (first (filter #(= :commits (:section %)) ((:items source)))))
        (is (= ["▾ Changes" "  new.txt" "▸ Commits" "▾ Graph"] (take 4 (labels))))
        ((:act source) (first (filter #(= :commits (:section %)) ((:items source)))))
        (is (= "▾ Commits" (nth (labels) 2))))
      (testing "a commit in the graph shows as a commit does"
        ((:act source) (first (filter #(and (:hash %) (str/includes? (:label %) "team/times")) ((:items source)))))
        (loop [n 0] (when (and (empty? @shown) (< n 100)) (Thread/sleep 50) (recur (inc n))))
        (is (= 1 (count @shown)))
        (is (re-matches #"git show — [0-9a-f]+" (first @shown))))
      (testing "the names of what it holds find it"
        (is (= 1 (frame/view-index views "commits")))
        (is (= 1 (frame/view-index views "Graph")))
        (is (= 1 (frame/view-index views "changes")))))))

(deftest a-change-is-named-from-where-the-person-is
  (is (= "a.clj" (pv/relative "/repo" "/repo/sub" "sub/a.clj")))
  (is (= "sub/a.clj" (pv/relative "/repo" "/repo" "sub/a.clj")))
  (is (= "/repo/other/b.clj" (pv/relative "/repo" "/repo/sub" "other/b.clj")) "outside it, the whole path"))

(deftest a-read-cut-short-by-the-next-is-not-a-failure
  ;; reported as a detached failure, it printed over the full screen (byj-gly.381)
  (let [dir (fresh-dir "rapid")
        failures (atom [])
        {:keys [refresh! state]} (pv/views dir)]
    (git! dir "init" "-q" "-b" "main")
    (binding [fx/*on-failure* (fn [& args] (swap! failures conj args))]
      (dotimes [_ 5] (refresh!)))
    (loop [n 0] (when (and (:reading? @state) (< n 100)) (Thread/sleep 50) (recur (inc n))))
    (is (empty? @failures))
    (is (true? (:repo? @state)) "and the last one answers")))
