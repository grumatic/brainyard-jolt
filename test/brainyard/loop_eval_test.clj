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
  (let [{:keys [dir config]} (fixture "verdicts" 6)
        refs-before (refs dir)
        work (fresh-dir "verdicts-work")]
    (testing "worse by construction: it passes no check where the loop in force passes every one"
      (let [cand (io/file dir "cand.edn")
            _ (spit cand (pr-str does-nothing))
            {:keys [file report]} (le/evaluate! config {:lm mock :candidate (.getPath cand) :work work})]
        (is (= :not-better (:verdict report)))
        (is (= [1.0 0.0] [(get-in report [:primary :base]) (get-in report [:primary :candidate])]))
        (is (= 12 (count (:runs report))) "six tasks, each arm once")
        (is (.isFile (io/file file)) "the report is written")
        (is (= (:verdict report) (:verdict (edn/read-string (slurp file)))))
        (is (not (le/wins? report)))))
    (testing "equal: the same procedure ties, and a tie keeps the loop in force"
      (let [{:keys [report]} (le/evaluate! config {:lm mock :candidate (with-doc writes-done "same") :repeats 2 :work work})]
        (is (= :not-better (:verdict report)))
        (is (= 1.0 (get-in report [:primary :candidate]) (get-in report [:primary :base])))
        (is (= 24 (count (:runs report))))))
    (testing "better: a candidate that does the task beats a loop in force that does not"
      (project-loops/write-loop! dir "react" does-nothing {:action :accept})
      (let [{:keys [report]} (le/evaluate! config {:lm mock :candidate writes-done :work work})]
        (is (= :better (:verdict report)))
        (is (le/wins? report))
        (is (get-in report [:primary :compare :paired?]) "per task, paired")))
    (testing "the same lead on five tasks is not a result: the loop in force is kept"
      (let [{:keys [dir config]} (fixture "verdicts-five" 5)]
        (project-loops/write-loop! dir "react" does-nothing {:action :accept})
        (is (= :not-better (:verdict (:report (le/evaluate! config {:lm mock :candidate writes-done})))))))
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

;; --- byj loops propose (byj-ng7.5) ---------------------------------------------------

(defn- same-steps? [tree like] (= (drop 2 tree) (drop 2 like)))

(deftest a-candidate-is-proposed-only-when-it-wins
  (let [{:keys [dir config]} (fixture "propose" 6)
        ls #(map :id ((requiring-resolve 'brainyard.proposals/list-all) config "loop"))]
    (testing "worse: the report is written, nothing staged, and it says why"
      (let [r (le/propose! config {:lm mock :candidate does-nothing})]
        (is (str/includes? (:not-staged r) "did not win (not-better)"))
        (is (.isFile (io/file (:file r))))
        (is (empty? (ls)))))
    (testing "better: staged with its report and why, for a person to accept"
      (project-loops/write-loop! dir "react" does-nothing {:action :accept})
      (let [{:keys [staged file]} (le/propose! config {:lm mock :candidate writes-done})
            p ((requiring-resolve 'brainyard.loop-proposals/read-proposal) config (:id staged))]
        (is (= [(str "loop/" (:id staged))] (ls)))
        (is (= :better (get-in p [:report :verdict])))
        (is (= file (get-in p [:report :eval-file])) "the eval's own file, with every run")
        (is (nil? (get-in p [:report :runs])))
        (is (str/starts-with? (:why p) "byj loops eval: better — checks passed 1.000 against 0.000"))
        (is (same-steps? (edn/read-string (slurp (project-loops/project-loop-file dir "react"))) does-nothing)
            "staged only: the loop in force is unchanged")
        (is (nil? (:error ((requiring-resolve 'brainyard.loop-proposals/accept!) config (:id staged)))))
        (is (same-steps? (edn/read-string (slurp (project-loops/project-loop-file dir "react"))) writes-done))))
    (testing "a stopped evaluation stages nothing"
      (let [r (le/propose! config {:lm mock :candidate does-nothing :max-cost 0.1
                                   :run-turn (fn [_] {:usage {:cost 1.0}})})]
        (is (str/includes? (:not-staged r) "decided nothing"))))))

(deftest the-bundled-loop-is-proposed-back-when-a-projects-own-no-longer-beats-it
  (let [run-turn (fn [writes?] (fn [{:keys [config tree]}]
                                 (when (writes? tree) (spit (io/file (:project-dir config) "done.txt") "done\n"))
                                 {:iterations 1}))
        bundled? #(not (or (same-steps? % writes-done) (same-steps? % does-nothing)))]
    (let [{:keys [config]} (fixture "remove-none" 1)]
      (project-loops/write-loop! (:project-dir config) "react" nil {:action :revert})
      (is (str/includes? (:error (le/propose! config {:lm mock :remove? true})) "no react loop of its own")))
    (testing "the project's loop does the task and the bundled one does not: it is kept"
      (let [{:keys [config]} (fixture "remove-kept" 3)
            r (le/propose! config {:lm mock :remove? true :run-turn (run-turn #(same-steps? % writes-done))})]
        (is (str/includes? (:not-staged r) "beats the bundled one past the margin: it is kept"))))
    (testing "a tie: the project's loop no longer beats the bundled one, so its removal is proposed"
      (let [{:keys [dir config]} (fixture "remove-tie" 3)
            r (le/propose! config {:lm mock :remove? true :run-turn (run-turn #(or (bundled? %) (same-steps? % writes-done)))})
            lp (requiring-resolve 'brainyard.loop-proposals/read-proposal)
            p (lp config (:id (:staged r)))]
        (is (= :revert (:action p)))
        (is (nil? (:tree p)) "a revert to the bundled loop")
        (is (str/starts-with? (:why p) "The bundled react loop is not worse past the margin than the project's own"))
        (is (nil? (:error ((requiring-resolve 'brainyard.loop-proposals/accept!) config (:id (:staged r))))))
        (is (nil? (project-loops/project-loop-file dir "react")))))))

;; --- a run in a byj of its own (byj-ng7.14) ------------------------------------------

(defn- stand-in
  "A stand-in for the child byj: it does the task in the directory after -C
  and prints what `byj ask --json` prints — or, `silent?`, nothing."
  [dir silent?]
  (let [f (io/file dir (if silent? "silent.sh" "byj.sh"))]
    (spit f (str "#!/bin/sh\n"
                 "# Copyright (c) 2026 Grumatic, Inc. and byj contributors\n"
                 "# SPDX-License-Identifier: MIT\n"
                 "d=''; while [ $# -gt 0 ]; do case \"$1\" in -C) d=\"$2\"; shift;; esac; shift; done\n"
                 (if silent?
                   "echo 'no json here' >&2; exit 3\n"
                   (str "printf 'done\\n' > \"$d/done.txt\"\n"
                        "echo 'a banner line'\n"
                        "echo '{\"answer\":\"done\",\"iterations\":2,\"terminated-by\":\"answer-channel\","
                        "\"usage\":{\"cost\":0.25,\"input-tokens\":3,\"output-tokens\":4},\"trajectory\":[]}'\n"))))
    (.setExecutable f true)
    (.getPath f)))

(deftest a-run-can-be-a-byj-of-its-own
  (is (= ["byj" "ask" "--json" "-C" "/c" "--loop" "/c/.brainyard/eval-loop.edn" "-p" "mock" "-m" "m1" "--sandbox" "-q" "-n is a question"]
         (le/child-argv ["byj"] {:lm {:provider :mock :model "m1"} :dir "/c" :loop-file "/c/.brainyard/eval-loop.edn"
                                 :question "-n is a question" :sandbox? true}))
      "the question after -q, so one that starts with a dash is not a flag")
  (is (= {:allowed-dirs ["/c"] :permission-mode :ask-each-time}
         (select-keys (le/child-config (assoc (le/clone-config {:permission-mode :auto-approve} "/c")
                                              :lm-fn (fn []) :not-a-key 1))
                      [:allowed-dirs :permission-mode :lm-fn :not-a-key]))
      "the schema's keys, as they read back: the clone's containment goes with it")
  (let [{:keys [config]} (fixture "child" 2)
        work (fresh-dir "child-work")
        script (stand-in work false)
        {:keys [report]} (le/evaluate! config {:lm mock :candidate does-nothing :work work :keep? true
                                               :run-turn (le/process-run-turn [script] false)
                                               :containment {:on? false :how :child}})]
    (is (= 4 (count (:runs report))))
    (is (every? #(= 1.0 (:score %)) (:runs report)) "the child did the task in the clone it was given")
    (is (every? #(= 2 (:iterations %)) (:runs report)) "and its answer was read back past the banner")
    (is (< 0.99 (:spent report) 1.01))
    (let [clone (first (filter #(.isDirectory %) (.listFiles (io/file work))))
          cfg (edn/read-string (slurp (io/file clone ".brainyard" "config.edn")))]
      (is (= [(.getCanonicalPath clone)] (get-in cfg [:agent :config :allowed-dirs]))
          "the child's project config is the clone's containment")
      (is (.isFile (io/file clone ".brainyard" "eval-loop.edn"))))
    (is (some #(str/includes? % "a byj of its own in its clone, NOT sandboxed") (le/summary-lines {:file "f" :report report}))))
  (testing "a child that prints no answer is the run's error, and scores what its checks say"
    (let [{:keys [config]} (fixture "child-silent" 1)
          work (fresh-dir "child-silent-work")
          {:keys [report]} (le/evaluate! config {:lm mock :candidate does-nothing
                                                 :run-turn (le/process-run-turn [(stand-in work true)] false)})]
      (is (every? #(str/includes? (str (:error %)) "the child byj printed no answer (exit 3): no json here") (:runs report)))
      (is (every? #(= 0.0 (:score %)) (:runs report)))))
  (is (= :in-process (get-in (le/containment nil) [:sandbox :how])) "no byj to run: in process, and the report says so"))
