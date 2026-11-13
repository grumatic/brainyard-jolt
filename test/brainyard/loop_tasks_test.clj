;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-tasks-test
  "Tasks rebuilt from a project's past turns (RFC-071 \"Tasks\", byj-ng7.3),
  on a fixture repository outside this one: turns made through
  brainyard.changes and their records through brainyard.trajectory, as a
  session makes them, with no model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.changes :as changes]
            [brainyard.effect :as fx]
            [brainyard.evaluate :as evaluate]
            [brainyard.json :as json]
            [brainyard.loop-tasks :as loop-tasks]
            [brainyard.main :as main]
            [brainyard.trajectory :as trajectory]))

(defn- sh [dir & args]
  (let [r (apply fx/sh {:dir dir :out :string :err :string} args)]
    (assert (zero? (:exit r)) (str args ": " (:err r)))
    (str/trim (:out r))))

(defn- repo
  "A git repository at a fresh directory with one commit."
  [name]
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-loop-tasks-" name "-" (System/nanoTime))) .mkdirs))]
    (sh dir "git" "init" "-q")
    (sh dir "git" "config" "user.name" "Someone")
    (sh dir "git" "config" "user.email" "someone@example.invalid")
    (sh dir "git" "config" "commit.gpgSign" "false")
    (spit (io/file dir "a.txt") "one\ntwo\n")
    (sh dir "git" "add" "-A")
    (sh dir "git" "commit" "-q" "-m" "first")
    dir))

(defn- turn!
  "Turn `n` of the session `cfg` names, as a session makes it: the start
  snapshot, `f` doing the turn's work, the trajectory record (unless
  `record?` is false; a subagent's too with `subagent`), the end snapshot."
  [cfg n question f & [{:keys [record? subagent]}]]
  (changes/begin-turn! cfg n question)
  (f)
  (let [log (trajectory/log-file cfg)]
    (when subagent
      (trajectory/append! log (trajectory/record {:session (:session-id cfg) :owner "root" :agent "child-1"
                                                  :question subagent :answer "x" :terminated-by :answer-channel})))
    (when-not (false? record?)
      (trajectory/append! log (trajectory/record {:session (:session-id cfg) :question question
                                                  :answer "done" :terminated-by :answer-channel}))))
  (changes/end-turn! cfg))

(defn- fixture
  "A session of five turns, each a case the build must tell apart:
  1 made b.txt, which the person committed — a success;
  2 changed a.txt, which the person rewound — a failure;
  3 changed nothing — no signal of the person's;
  4 recorded, its checkpoint pruned since — not kept;
  5 kept, with no record — no question."
  []
  (let [dir (repo "fixture")
        cfg {:project-dir dir :working-dir dir :session-id "s-1"}]
    (turn! cfg 1 "make b.txt\nwith one line" #(spit (io/file dir "b.txt") "b\n") {:subagent "look around"})
    (sh dir "git" "add" "b.txt")
    (sh dir "git" "commit" "-q" "-m" "b, from turn 1")
    (turn! cfg 2 "rewrite a.txt" #(spit (io/file dir "a.txt") "wrong\n"))
    (is (= ["a.txt"] (:files (changes/rewind! cfg "s-1" 2))) "the person takes turn 2 back")
    (turn! cfg 3 "what is in a.txt?" (fn []))
    (turn! cfg 4 "make c.txt" #(spit (io/file dir "c.txt") "c\n"))
    (sh dir "git" "update-ref" "-d" (changes/ref-name "s-1" 4))
    (turn! cfg 5 "make d.txt" #(spit (io/file dir "d.txt") "d\n") {:record? false})
    {:dir dir :cfg cfg}))

(deftest a-task-is-a-question-a-start-and-a-check
  (let [{:keys [dir cfg]} (fixture)
        r (loop-tasks/build! cfg {})
        written (loop-tasks/read-set cfg "default")
        by-turn (into {} (map (juxt (comp :turn :from) identity) (:tasks written)))]
    (testing "what the build says"
      (is (= (.getPath (io/file dir ".brainyard" "loops" "tasks" "default.edn")) (:file r)))
      (is (= 2 (:tasks r)) "the committed turn and the rewound one")
      (is (= 2 (:added r)))
      (is (= {:no-check 1 :not-kept 1 :no-record 1} (:dropped r)) "the count dropped, by why"))
    (testing "each task's question, start commit and check"
      (let [{:keys [question start checks outcome by split source from id]} (by-turn 1)]
        (is (= "make b.txt\nwith one line" question) "the trajectory record's question, whole")
        (is (= start (sh dir "git" "rev-parse" "refs/byj/turns/s-1/1^")) "the parent of the turn's checkpoint commit")
        (is (= [] checks) "no machine check was given")
        (is (= [:success :committed] [outcome by]) "the person committed its change")
        (is (= :turn source))
        (is (= {:session "s-1" :turn 1 :ref "refs/byj/turns/s-1/1"
                :end (sh dir "git" "rev-parse" "refs/byj/turns/s-1/1")} from))
        (is (= (evaluate/split-of (evaluate/example {:question question :start start})) split)
            "RFC-021's content hash, of the question and the start")
        (is (re-matches #"[0-9a-f]{12}" id))
        (is (= start (sh dir "git" "rev-parse" (str "refs/byj/tasks/default/" id)))
            "the start is pinned, so git's gc keeps it once the turn's ref is pruned"))
      (is (= [:failure :rewound] ((juxt :outcome :by) (by-turn 2))) "the person rewound it")
      (is (not-any? #(= "look around" (:question %)) (:tasks written)) "a subagent's turn is not one of the session's"))
    (testing "the dropped turns are named with why"
      (is (= #{[3 :no-check] [nil :not-kept] [5 :no-record]}
             (set (map (juxt :turn :reason) (get-in written [:dropped :turns])))))
      (is (= "make c.txt" (:question (some #(when (= :not-kept (:reason %)) %) (get-in written [:dropped :turns]))))))
    (testing "the set's own name for what it holds"
      (is (= (evaluate/dataset-hash (map loop-tasks/example (:tasks written))) (:hash written) (:hash r)))
      (is (= 2 (reduce + (vals (:splits written))))))
    (testing "a machine check makes a task of a turn the person said nothing about, and is remembered"
      (let [r2 (loop-tasks/build! cfg {:check "test -f a.txt"})
            w2 (loop-tasks/read-set cfg "default")]
        (is (= 3 (:tasks r2)))
        (is (= 1 (:added r2)) "turn 3")
        (is (= {:not-kept 1 :no-record 1} (:dropped r2)))
        (is (every? #(= [{:type :command :name "check" :run "test -f a.txt"}] (:checks %)) (:tasks w2)))
        (is (= "test -f a.txt" (:check w2)))
        (is (= "test -f a.txt" (:check (loop-tasks/build! cfg {}))) "a rebuild with none keeps the set's")))
    (testing "a turn rewound and then taken back again is no failure"
      (is (some #{"a.txt"} (:files (changes/undo-rewind! cfg "s-1"))) "the work tree as it was before the rewind")
      (is (nil? (:outcome (some #(when (= 2 (:turn (:from %))) %) (:tasks (do (loop-tasks/build! cfg {}) (loop-tasks/read-set cfg "default"))))))))))

(deftest a-split-never-moves-a-task
  ;; RFC-071: a task's split is RFC-021's content hash of its own question
  ;; and start, so tasks added — a new turn, a person's case — and a set
  ;; rebuilt after the turns it came from were pruned leave every task where
  ;; it was, and a held-out task stays held out
  (let [{:keys [dir cfg]} (fixture)
        _ (loop-tasks/build! cfg {:check "true"})
        before (loop-tasks/read-set cfg "default")
        split-of (fn [s] (into {} (map (juxt :id :split) (:tasks s))))]
    (turn! cfg 6 "make e.txt" #(spit (io/file dir "e.txt") "e\n"))
    (spit (loop-tasks/cases-file cfg "default")
          (pr-str [{:question "add a README" :check "test -f README"}
                   {:question "from nowhere" :start "no-such-rev"}
                   {:start "HEAD"}]))
    (let [r (loop-tasks/build! cfg {})
          after (loop-tasks/read-set cfg "default")]
      (is (= 2 (:added r)) "turn 6 and the person's case")
      (is (= 1 (get-in r [:dropped :start-unknown])))
      (is (= 1 (get-in r [:dropped :no-question])))
      (is (= (split-of before) (select-keys (split-of after) (keys (split-of before))))
          "no task already in the set moved")
      (let [case (some #(when (= :person (:source %)) %) (:tasks after))]
        (is (= "add a README" (:question case)))
        (is (= (sh dir "git" "rev-parse" "HEAD") (:start case)) "HEAD, saved as its commit id")
        (is (= [{:type :command :name "check" :run "test -f README"}] (:checks case)) "its own check, not the set's")))
    (testing "the turns pruned, the set still holds their tasks, and their splits"
      (doseq [n (changes/turns cfg "s-1")] (sh dir "git" "update-ref" "-d" (changes/ref-name "s-1" n)))
      (let [r (loop-tasks/build! cfg {})
            after (loop-tasks/read-set cfg "default")]
        (is (= 0 (:added r)))
        (is (= (count (:tasks after)) (:tasks r)))
        (is (= (split-of before) (select-keys (split-of after) (keys (split-of before)))))
        (is (every? #(= (:start %) (sh dir "git" "rev-parse" (str "refs/byj/tasks/default/" (:id %)))) (:tasks after))
            "every start still held by its pin")))
    (testing "the same question in the same tree is one task, wherever it came from"
      (is (= (loop-tasks/task-id {:question "q" :start "abc"}) (loop-tasks/task-id {:question "q" :start "abc"})))
      (is (not= (loop-tasks/task-id {:question "q" :start "abc"}) (loop-tasks/task-id {:question "q" :start "abd"}))))))

(deftest a-commit-is-a-success-only-while-head-holds-the-change
  (let [dir (repo "committed")
        cfg {:project-dir dir :working-dir dir :session-id "s-c"}
        row #(first (filter (fn [r] (= % (:turn r))) (changes/rows cfg "s-c")))]
    (turn! cfg 1 "edit a" #(spit (io/file dir "a.txt") "one\nTWO\n"))
    (let [{:keys [before after]} (row 1)]
      (is (not (loop-tasks/committed? dir before after)) "left in the work tree, not committed")
      (spit (io/file dir "other.txt") "the person's own\n")
      (sh dir "git" "add" "-A")
      (sh dir "git" "commit" "-q" "-m" "a and more")
      (is (loop-tasks/committed? dir before after) "committed, with more of the person's beside it")
      (spit (io/file dir "a.txt") "one\nthree\n")
      (sh dir "git" "commit" "-q" "-am" "changed again")
      (is (not (loop-tasks/committed? dir before after)) "HEAD changed the same lines since: no longer held"))))

(deftest outside-git-there-is-no-task-set
  (let [dir (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "byj-loop-tasks-nogit-" (System/nanoTime))) .mkdirs))
        r (loop-tasks/build! {:project-dir dir :working-dir dir} {})]
    (is (str/includes? (:error r) "not in a git work tree"))
    (is (not (.exists (loop-tasks/set-file {:project-dir dir} "default"))))))

(defn- run-main
  "`byj args…` in this process: `{:exit :out :err}`."
  [& args]
  (let [err (java.io.StringWriter.)
        code (atom nil)
        out (with-out-str
              (binding [*err* err
                        main/*exit* (fn [c] (throw (ex-info "exit" {::exit c})))]
                (try (apply #'main/main* args)
                     (catch clojure.lang.ExceptionInfo e
                       (if-let [c (::exit (ex-data e))] (reset! code c) (throw e))))))]
    {:exit @code :out out :err (str err)}))

(deftest byj-loops-tasks-build-writes-the-set
  (let [{:keys [dir]} (fixture)]
    (testing "--check takes a command after loops, and stays a flag elsewhere"
      (is (= "make test" (:check-cmd (main/parse-args ["loops" "tasks" "build" "--check" "make test"]))))
      (is (true? (:check (main/parse-args ["update" "--check"]))))
      (is (= ["s-1" "s-2"] (:from (main/parse-args ["loops" "tasks" "build" "--from" "s-1,s-2"])))))
    (let [{:keys [exit out]} (run-main "-C" dir "loops" "tasks" "build" "--from" "s-1")]
      (is (= 0 exit))
      (is (str/starts-with? out (str "wrote " (.getPath (io/file dir ".brainyard" "loops" "tasks" "default.edn")) ": 2 tasks (2 new) — ")))
      (is (str/includes? out "dropped 3: ")))
    (let [{:keys [exit out]} (run-main "-C" dir "loops" "tasks" "build" "--name" "held" "--check" "true" "--json")
          j (json/read-str out)]
      (is (= 0 exit))
      (is (= "held" (:set j)))
      (is (= 3 (:tasks j)))
      (is (= "true" (:check j)))
      (is (= {:not-kept 1 :no-record 1} (:dropped j)))
      (is (= 3 (count (:tasks (edn/read-string (slurp (io/file dir ".brainyard" "loops" "tasks" "held.edn"))))))
          "the file is EDN, a task a line"))
    (is (= 2 (:exit (run-main "-C" dir "loops" "tasks"))) "build is the one thing tasks does yet")
    (is (= 2 (:exit (run-main "-C" dir "loops" "nosuch"))))))

;; byj-ng7.9: the machine's check by RFC-071's order of trust — a command
;; given, a dogfood plan's stage, the set's own, then the project's
(deftest a-sets-machine-check-is-the-most-trusted-one-there-is
  (let [plan {:plan "p.edn" :stage "code" :checks [{:type :command :name "npm-test" :run "npm test"}]}
        cfg {:check-command "make check"}
        m (fn [check plan old] (select-keys (loop-tasks/machine-checks cfg check plan old) [:from :check]))]
    (is (= {:from :given :check "make test"} (m "make test" plan {:check "old"})))
    (is (= {:from :plan} (m nil plan {:check "old"})))
    (is (= {:from :set :check "old"} (m nil nil {:check "old"})))
    (is (= [{:type :command :name "npm-test" :run "npm test"}]
           (:checks (loop-tasks/machine-checks cfg nil nil {:plan plan}))) "a plan the set remembers")
    (is (= {:from :config :check "make check"} (m nil nil nil)))
    (is (nil? (loop-tasks/machine-checks {} nil nil nil)) "none: the person's signal alone"))
  (testing "a plan's stage, by id or its last"
    (let [dir (repo "plan")
          f (io/file dir "plan.edn")]
      (spit f (pr-str {:id "p" :stages [{:id :init :checks [{:type :file :name "a" :path "a.txt"}]}
                                        {:id :code :checks [{:type :command :name "t" :run "{{plan}}/check.sh"}]}
                                        {:id :empty}]}))
      (is (= "init" (:stage (loop-tasks/plan-checks (.getPath f) "init"))))
      (is (= "init" (:stage (loop-tasks/plan-checks (.getPath f) ":init"))))
      (is (= (str (.getCanonicalPath (io/file dir)) "/check.sh")
             (:run (first (:checks (loop-tasks/plan-checks (.getPath f) "code")))))
          "{{plan}} is the plan's own directory")
      (is (str/includes? (:error (loop-tasks/plan-checks (.getPath f) nil)) "stage empty") "the last stage has none")
      (is (str/includes? (:error (loop-tasks/plan-checks (.getPath f) "nope")) "no stage nope — init, code, empty"))
      (is (str/includes? (:error (loop-tasks/plan-checks (.getPath (io/file dir "none.edn")) nil)) "no plan at"))))
  (testing "built with neither, the set takes the project's :check-command and says so"
    (let [{:keys [dir cfg]} (fixture)
          r (loop-tasks/build! (assoc cfg :check-command "test -f b.txt") {:sessions ["s-1"]})]
      (is (= [:config "test -f b.txt"] [(:checks-from r) (:check r)]))
      (is (every? #(= "test -f b.txt" (:run (first (:checks %)))) (:tasks (loop-tasks/read-set cfg "default"))))
      (is (some #(str/includes? % "machine check: test -f b.txt (:check-command)") (loop-tasks/summary-lines r)))
      (let [{:keys [exit out]} (run-main "-C" dir "loops" "tasks" "build" "--name" "planned" "--plan" (str dir "/missing.edn"))]
        (is (= 1 exit))
        (is (= "" out))))))
