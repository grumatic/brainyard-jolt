(ns brainyard.teams-test
  "Agent teams (RFC-032): a leader starts members as live sessions of their
  own, hands them work over the session channel, sees where it stands, and
  closes them. The members here are real `jolt run:byj --serve` processes on
  the mock model."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.channel :as channel]
            [brainyard.tasks :as tasks]
            [brainyard.teams :as teams]
            [jolt.process :as p]))

(defn- fresh-project [name]
  (let [d (io/file "target/teams-test" name)]
    (when (.exists d) @(p/process {:out :string :err :string} "rm" "-rf" (.getPath d)))
    (.mkdirs d)
    (let [dir (.getCanonicalPath d)]
      (doseq [args [["init" "-q"] ["config" "user.email" "t@t"] ["config" "user.name" "t"]
                    ["commit" "-q" "--allow-empty" "-m" "start"]]]
        @(apply p/process {:dir dir :out :string :err :string} "git" args))
      dir)))

(defn- tool [config n] (some #(when (= n (:name %)) %) (teams/commands config)))

(defn- settled [reg id]
  (loop [n 0]
    (let [t (tasks/snapshot reg id)]
      (if (or (not= :running (:status t)) (> n 3000)) t (do (Thread/sleep 10) (recur (inc n)))))))

(deftest a-leader-runs-a-team-of-live-sessions
  (let [dir (fresh-project "team")
        leader {:project-dir dir :working-dir dir :session-id "1790000000100-leader01"
                :ask-timeout-ms 30000 :team-spawn-timeout-ms 90000 :team-close-grace-ms 10000}
        ctx {:config leader :task-registry (tasks/registry)}
        call (fn [n args] ((:fn (tool leader n)) args ctx))]
    (try
      (testing "creating a team hands the leader how to lead one"
        (let [{:keys [team protocol]} (call "team$create" {:name "Port the docs"})]
          (is (str/starts-with? team "port-the-docs-"))
          (is (str/includes? protocol "A member has **not seen this conversation**"))
          (is (str/includes? (:error (call "team$create" {})) "already leads team"))))
      (testing "a member is a live session of its own, found by its team label"
        (let [m (call "team$spawn" {:name "Checker" :provider "mock"})]
          (is (= "checker" (:name m)) (pr-str m))
          (is (re-matches #"\d{13}-[0-9a-f]{8}" (str (:session-id m))))
          (is (= [(:session-id m)] (map :session-id (filter :live? (channel/sessions leader)))))
          (is (str/includes? (:error (call "team$spawn" {:name "checker" :provider "mock"})) "already has a member"))))
      (testing "an assignment returns at once and lands as the leader's background task"
        (let [{:keys [task-id]} (call "team$assign" {:member "checker" :task "count the files"})
              t (settled (:task-registry ctx) task-id)]
          (is (= :completed (:status t)))
          (is (str/includes? (get-in t [:result :answer]) "count the files"))))
      (testing "status shows the member and what it answered"
        (let [{:keys [members assignments]} (call "team$status" {})]
          (is (= [{:name "checker" :live? true}] (map #(select-keys % [:name :live?]) members)))
          (is (= 1 (get-in (first members) [:spent :llm-calls])) "what its answers cost, per member")
          (is (= ["completed"] (map :status assignments)))
          (is (str/includes? (:answer (first assignments)) "count the files"))))
      (testing "a member in a worktree works on a branch of its own"
        (let [m (call "team$spawn" {:name "editor" :provider "mock" :worktree true})]
          (is (.isDirectory (io/file (:worktree m))) (pr-str m))
          (is (str/starts-with? (:branch m) "team/"))
          (is (str/includes? (:out @(p/process {:dir dir :out :string} "git" "branch" "--list" "team/*"))
                             (:branch m)))))
      (testing "closing the team closes every member, and leaves the worktree to merge"
        (let [r (call "team$close" {})]
          (is (= #{"checker" "editor"} (set (:closed r))))
          (is (= ["editor"] (map :name (:worktrees r))))
          (is (empty? (filter :live? (channel/sessions leader))))
          (is (nil? (teams/current-team leader)) "the team is closed")))
      (finally
        (tasks/cancel-all! (:task-registry ctx))))))

(deftest what-a-team-refuses
  (let [dir (fresh-project "refuse")
        leader {:project-dir dir :working-dir dir :session-id "1790000000200-leader02" :max-team-members 0}
        ctx {:config leader :task-registry (tasks/registry)}
        call (fn [n args] ((:fn (tool leader n)) args ctx))]
    (is (= {:error "no open team — team$create first"} (call "team$spawn" {:name "x"})))
    (is (= {:error "no open team — team$create first"} (call "team$assign" {:member "x" :task "t"})))
    (call "team$create" {})
    (is (str/includes? (:error (call "team$spawn" {:name "x"})) "(:max-team-members)"))
    (is (= {:error "no member \"x\" — team$status names them"} (call "team$assign" {:member "x" :task "t"})))
    (is (= {:error "an assignment needs a task"} (call "team$assign" {:task " "})))
    (is (= 1 (:queued (call "team$assign" {:question "t"})))
        "`question`, session$ask's word, is taken as the task")
    (is (= {:closed []} (call "team$close" {})))
    (tasks/cancel-all! (:task-registry ctx))))

(deftest a-resumed-leader-brings-its-team-back
  (let [dir (fresh-project "resume")
        leader {:project-dir dir :working-dir dir :session-id "1790000000400-leader04"
                :ask-timeout-ms 30000 :team-spawn-timeout-ms 90000 :team-close-grace-ms 10000}
        before {:config leader :task-registry (tasks/registry)}
        call (fn [ctx n args] ((:fn (tool leader n)) args ctx))]
    (call before "team$create" {})
    (let [{:keys [session-id]} (call before "team$spawn" {:name "keeper" :provider "mock"})
          {:keys [task-id]} (call before "team$assign" {:member "keeper" :task "remember this"})]
      (settled (:task-registry before) task-id)
      ;; the leader's process ends: everything it started goes with it
      (tasks/cancel-all! (:task-registry before))
      (loop [i 0] (when (and (seq (filter :live? (channel/sessions leader))) (< i 100)) (Thread/sleep 100) (recur (inc i))))
      (let [after {:config leader :task-registry (tasks/registry)}]
        (try
          (testing "the resumed leader sees its team, the member down, and what it was waiting on lost"
            (let [{:keys [members assignments note]} (call after "team$status" {})]
              (is (= [false] (map :live? members)))
              (is (= ["lost"] (map :status assignments)))
              (is (str/includes? note "team$resume"))))
          (testing "team$resume brings the member back as the same session, with its history"
            (is (= {:resumed ["keeper"] :already-live [] :failed []} (call after "team$resume" {})))
            (is (= [session-id] (map :session-id (filter :live? (channel/sessions leader)))))
            (let [{:keys [task-id]} (call after "team$assign" {:member "keeper" :task "what did I ask?"})
                  t (settled (:task-registry after) task-id)]
              (is (str/includes? (get-in t [:result :answer]) "1 previous turn(s) in context"))))
          (testing "and a second resume leaves a live member alone"
            (is (= {:resumed [] :already-live ["keeper"] :failed []} (call after "team$resume" {}))))
          (finally
            (call after "team$close" {})
            (tasks/cancel-all! (:task-registry after))))))))

(deftest the-board-hands-each-task-to-the-next-free-member
  (let [dir (fresh-project "board")
        leader {:project-dir dir :working-dir dir :session-id "1790000000500-leader05"
                :ask-timeout-ms 30000 :team-spawn-timeout-ms 90000 :team-close-grace-ms 10000}
        ctx {:config leader :task-registry (tasks/registry)}
        call (fn [n args] ((:fn (tool leader n)) args ctx))]
    (try
      (call "team$create" {})
      (call "team$spawn" {:name "one" :provider "mock"})
      (call "team$spawn" {:name "two" :provider "mock"})
      (let [queued (mapv #(call "team$assign" {:task (str "task " %)}) (range 5))
            dispatcher (:dispatcher-task-id (first queued))]
        (testing "every task goes on the board, worked by one dispatcher"
          (is (every? #(= dispatcher (:dispatcher-task-id %)) queued))
          (is (= [1 2 3 4 5] (map :queued queued))))
        (testing "the dispatcher lands once every task is answered, with every answer"
          (let [t (settled (:task-registry ctx) dispatcher)
                answers (get-in t [:result :answers])]
            (is (= :completed (:status t)))
            (is (= (set (map #(str "task " %) (range 5))) (set (map :task answers))))
            (is (every? #(str/includes? (:answer %) "task ") answers))
            (is (= #{"one" "two"} (set (map :member answers))) "both members worked")))
        (testing "the board is empty, and status shows what each did"
          (let [{:keys [queued assignments]} (call "team$status" {})]
            (is (nil? queued))
            (is (= 5 (count assignments)))
            (is (every? #(= "completed" (:status %)) assignments)))))
      (finally (call "team$close" {}) (tasks/cancel-all! (:task-registry ctx))))))
