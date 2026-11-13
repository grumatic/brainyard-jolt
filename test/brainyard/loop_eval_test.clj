;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-eval-test
  "A candidate loop run as a clone of the agent on a task set and compared
  with the loop in force (RFC-071, byj-ng7.4), on the mock provider."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.loop-eval :as le]
            [brainyard.loop-tasks :as loop-tasks]
            [brainyard.project-loops :as project-loops]))

(defn- sh [dir & args] (apply fx/sh {:dir (str dir) :out :string :err :string} args))

(defn- fresh-dir [name]
  (let [d (io/file "target/loop-eval-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(def ^:private writes-done
  "A loop that does the task without a model: writes done.txt and answers."
  '[:sequence {:id :t/turn}
    [:action {:id :t/init :fn :react/init}]
    [:action {:id :t/seed :fn :mem/set :set {:p "done.txt" :c "done\n"}}]
    [:action {:id :t/write :fn :tool/run :tool "write-file" :in {:path :p :content :c}}]
    [:action {:id :t/answer :fn :mem/set :set {:answer "done"}}]])

(def ^:private does-nothing
  "Worse by construction: it answers without doing the task."
  '[:sequence {:id :t/turn}
    [:action {:id :t/init :fn :react/init}]
    [:action {:id :t/answer :fn :mem/set :set {:answer "done"}}]])

(defn- with-doc [tree doc] (let [[k o & cs] tree] (into [k (assoc o :doc doc)] cs)))

(defn- fixture
  "A repository with one commit, the loop in force `writes-done` (the
  project's own), and a task set of `n` tasks, each checked by done.txt."
  [name n]
  (let [dir (fresh-dir name)]
    (sh dir "git" "init" "-q")
    (sh dir "git" "config" "user.email" "t@example.invalid")
    (sh dir "git" "config" "user.name" "t")
    (spit (io/file dir "README") "a project\n")
    (spit (io/file dir ".gitignore") ".brainyard/\n")
    (sh dir "git" "add" "-A")
    (sh dir "git" "commit" "-q" "-m" "start")
    (let [start (str/trim (:out (sh dir "git" "rev-parse" "HEAD")))
          config {:project-dir dir :working-dir dir}
          f (loop-tasks/set-file config "default")]
      (project-loops/write-loop! dir "react" writes-done {:action :accept})
      (io/make-parents f)
      (spit f (pr-str {:version 1 :set "default" :hash "fixture"
                       :tasks (vec (for [i (range n)]
                                     {:id (str "t" i) :question (str "make done.txt, " i) :start start
                                      :checks [{:type :file :name "done" :path "done.txt" :contains "done"}]}))}))
      {:dir dir :config config :start start})))

(def ^:private mock {:provider :mock :format :mock :responses (fn [_] "{\"answer\":\"unused\"}")})

(defn- refs [dir] (:out (sh dir "git" "for-each-ref")))

(deftest a-worse-candidate-loses-and-an-equal-one-ties
  (let [{:keys [dir config]} (fixture "verdicts" 5)
        refs-before (refs dir)
        work (fresh-dir "verdicts-work")]
    (testing "worse by construction: it passes no check where the loop in force passes every one"
      (let [cand (io/file dir "cand.edn")
            _ (spit cand (pr-str does-nothing))
            {:keys [file report]} (le/evaluate! config {:lm mock :candidate (.getPath cand) :work work})]
        (is (= :not-better (:verdict report)))
        (is (= [1.0 0.0] [(get-in report [:primary :base]) (get-in report [:primary :candidate])]))
        (is (= 10 (count (:runs report))) "five tasks, each arm once")
        (is (.isFile (io/file file)) "the report is written")
        (is (= (:verdict report) (:verdict (edn/read-string (slurp file)))))
        (is (not (le/wins? report)))))
    (testing "equal: the same procedure ties, and a tie keeps the loop in force"
      (let [{:keys [report]} (le/evaluate! config {:lm mock :candidate (with-doc writes-done "same") :repeats 2 :work work})]
        (is (= :not-better (:verdict report)))
        (is (= 1.0 (get-in report [:primary :candidate]) (get-in report [:primary :base])))
        (is (= 20 (count (:runs report))))))
    (testing "better: a candidate that does the task beats a loop in force that does not"
      (project-loops/write-loop! dir "react" does-nothing {:action :accept})
      (let [{:keys [report]} (le/evaluate! config {:lm mock :candidate writes-done :work work})]
        (is (= :better (:verdict report)))
        (is (le/wins? report))
        (is (get-in report [:primary :compare :paired?]) "per task, paired")))
    (testing "the clones leave nothing in the project: no refs, no sessions, no clone"
      (is (= refs-before (refs dir)))
      (is (not (.exists (io/file dir ".brainyard" "sessions"))))
      (is (empty? (.list (io/file work))) "each clone removed after its run"))))

(deftest the-report-says-what-was-measured
  (let [{:keys [dir config]} (fixture "report" 2)
        {:keys [report]} (le/evaluate! config {:lm mock :candidate does-nothing})]
    (is (= {:set "default" :set-hash "fixture" :loop "react" :repeats 1 :tasks 2 :unscored 0 :aim :checks}
           (select-keys report [:set :set-hash :loop :repeats :tasks :unscored :aim])))
    (is (= :project (get-in report [:base :from])) "the loop in force, the project's own")
    (is (string? (get-in report [:candidate :hash])))
    (is (string? (:byj report)))
    (is (= {:provider :mock} (:model report)))
    (is (every? #(= "done" (:name (first (:checks %)))) (:runs report)))
    (is (str/includes? (str/join "\n" (le/summary-lines {:file "f" :report report})) "verdict: not-better"))
    (is (.isDirectory (io/file dir ".brainyard" "loops" "evals")))))

(deftest a-run-is-contained-in-its-clone
  (let [{:keys [dir config]} (fixture "contained" 1)
        seen (atom nil)]
    (le/evaluate! config {:lm mock :candidate writes-done
                          :run-turn (fn [{:keys [config]}] (reset! seen config) {:iterations 1})})
    (let [c @seen]
      (is (not= dir (:project-dir c)) "the clone is its project")
      (is (= [(:project-dir c)] (:allowed-dirs c)) "and its only allowed directory")
      (is (= (:project-dir c) (:working-dir c)))
      (is (some #{"git push*"} (:bash-deny-commands c)))
      (is (some #{"share$*"} (:tool-deny-tools c)))
      (is (contains? (:tool-deny-effects c) :share/publish))))
  (is (= :ask-each-time (:permission-mode (le/clone-config {:permission-mode :auto-approve} "/x")))
      "headless, a gate that would ask refuses; auto-approve would reach outside unasked"))

(deftest the-spend-cap-stops-the-evaluation-and-decides-nothing
  (let [{:keys [config]} (fixture "cap" 4)
        runs (atom 0)
        {:keys [report]} (le/evaluate! config {:lm mock :candidate writes-done :max-cost 1.0
                                               :run-turn (fn [_] (swap! runs inc) {:usage {:cost 0.4} :iterations 1})})]
    (is (= 3 @runs) "no run starts once the runs so far cost the cap")
    (is (= :incomplete (:verdict report)))
    (is (str/includes? (:stopped report) "spend cap"))
    (is (< 1.19 (:spent report) 1.21))))

(deftest a-guard-regression-rejects-whatever-the-checks-say
  (let [arm (fn [score cost iters] {:score score :example-scores [score score score] :n 3
                                    :means (merge (zipmap (keys le/default-guards) (repeat 0.0)) {:cost cost :iterations iters})})]
    (testing "more checks passed, but three times the iterations"
      (let [v (le/verdict (arm 0.0 0.0 2.0) (arm 1.0 0.0 6.0) {})]
        (is (= :guard-regressed (:verdict v)))
        (is (= [:iterations] (map :metric (:guards v))))))
    (testing "aimed at cost: not worse, cheaper, wins"
      (is (= :cheaper (:verdict (le/verdict (arm 1.0 0.10 2.0) (arm 1.0 0.08 2.0) {:aim :cost})))))
    (testing "aimed at cost: cheaper but worse past the margin, loses"
      (is (= :not-better (:verdict (le/verdict (arm 1.0 0.10 2.0) (arm 0.0 0.08 2.0) {:aim :cost})))))))

(deftest a-candidate-that-does-not-check-is-not-run
  (let [{:keys [config]} (fixture "bad" 1)
        r (le/evaluate! config {:lm mock :candidate '[:sequence {:id :x/t} [:action {:id :x/a :fn :no/such}]]})]
    (is (str/includes? (:error r) "does not check"))
    (is (str/includes? (:error (le/evaluate! config {:lm mock :candidate writes-done :tasks "none"})) "no task set none"))))

(deftest which-loop-a-candidate-is-for
  (is (= "react" (le/loop-of '[:sequence {:id :a :based-on {:loop "react"}}])))
  (is (= "react" (le/loop-of "/nowhere/candidate.edn")))
  (is (= "session" (le/loop-of "/nowhere/session.edn")) "a file named for a bundled loop"))
