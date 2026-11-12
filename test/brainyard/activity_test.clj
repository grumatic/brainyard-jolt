;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.activity-test
  "The right rail's Activity view (byj-gly.411): what each event becomes, a
  turn's rows under its heading, a call's ◐ settling to ✓ with its time, a
  subagent's calls indented, the cap, and what Enter does."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.activity :as activity]
            [brainyard.tui-frame :as frame]))

(defn- feed-of
  "The feed after `events`, each `[e data]`, at 22:41 UTC."
  [events]
  (reduce (fn [f [e d]] (activity/record f e d 1792449660000 0)) {:rows [] :turns {}} events))

(defn- labels [feed & [toggled]]
  (mapv #(str/trimr (:label %)) (activity/rows feed (or toggled #{}))))

(def ^:private a-turn
  [[:session.turn/start {:turn 1 :question "what does it do?"}]
   [:agent.iteration/start {:iteration 1}]
   [:agent.tools/calls {:tool-calls [{:tool-name "read-file" :tool-args {:path "greet/cli.py"}}
                                     {:tool-name "grep" :tool-args {:pattern "argparse"}}]}]])

(deftest a-turns-work-is-its-rows
  (testing "a call runs, ◐, until its result comes"
    (is (= ["▾ Turn 1 · 22:41" "  ↻ iteration 1" "  ◐ read-file greet/cli.py" "  ◐ grep argparse"]
           (labels (feed-of a-turn)))))
  (testing "then ✓ with how long it took, ✗ when it failed"
    (let [f (feed-of (conj a-turn [:agent.tools/results {:results [{:tool-name "read-file" :tool-result "import argparse" :elapsed-ms 12}
                                                                    {:tool-name "grep" :tool-error? true :tool-result "no such file" :elapsed-ms 4200}]}]))
          items (activity/rows f #{})]
      (is (= ["  ✓ read-file greet/cli.py" "  ✗ grep argparse"] (mapv #(str/trimr (:label %)) (drop 2 items))))
      (is (= ["12ms" "4.2s"] (mapv :hint (drop 2 items))))
      (is (= {:color :red} (:style (last items))))))
  (testing "a subagent's work is indented under its dispatch, and its answer says what it cost"
    (is (= ["▾ Turn 1 · 22:41" "  ⇢ agent$reviewer" "    ↻ iteration 1" "    ◐ read-file tests/test_cli.py" "  ⇠ reviewer answered"]
           (labels (feed-of [[:session.turn/start {:turn 1}]
                             [:agent.subagent/dispatch {:agent "reviewer" :id "reviewer/a-1"}]
                             [:agent.iteration/start {:iteration 1 :subagent-id "reviewer/a-1"}]
                             [:agent.tools/calls {:tool-calls [{:tool-name "read-file" :tool-args {:path "tests/test_cli.py"}}]
                                                  :subagent-id "reviewer/a-1"}]
                             [:agent.subagent/result {:agent "reviewer" :usage {:cost 0.013}}]]))))
    (is (= "$0.01" (:hint (last (activity/rows (feed-of [[:session.turn/start {:turn 1}]
                                                         [:agent.subagent/result {:agent "reviewer" :usage {:cost 0.013}}]])
                                               #{}))))))
  (testing "tasks, the classifier, retries and a block"
    (is (= ["▾ Turn 1 · 22:41" "  ⧗ bash sent to the background" "  ⧗ bash ended"
            "  ⚖ auto mode allow git status" "  ! model error, retry 2" "  ! blocked: loop guard"]
           (labels (feed-of [[:session.turn/start {:turn 1}]
                             [:task/detached {:id 3 :tool "bash" :by :person}]
                             [:task/completed {:id 3 :status :done :elapsed-ms 61000}]
                             [:permission/classified {:verdict :allow :display "git status"}]
                             [:agent.repair/llm-error {:attempt 2 :message "overloaded"}]
                             [:agent.tools/blocked {:reason "loop guard"}]])))))
  (testing "the turn's end, and its heading's duration and cost"
    (let [f (feed-of (conj a-turn [:session.turn/end {:terminated-by :answer :iterations 2 :elapsed-ms 9800 :usage {:cost 0.04}}]))]
      (is (= "  ■ answered · 2 iterations" (last (labels f))))
      (is (= "9.8s $0.04" (:hint (first (activity/rows f #{}))))))))

(deftest older-turns-fold-and-headings-are-sections
  (let [f (feed-of [[:session.turn/start {:turn 1}] [:agent.iteration/start {:iteration 1}]
                    [:session.turn/start {:turn 2}] [:agent.iteration/start {:iteration 1}]])]
    (is (= ["▸ Turn 1 · 22:41" "▾ Turn 2 · 22:41" "  ↻ iteration 1"] (labels f)) "the newest open, the older shut")
    (is (= ["▾ Turn 1 · 22:41" "  ↻ iteration 1" "▸ Turn 2 · 22:41"] (labels f #{1 2})) "each toggled")
    (is (= [0 1] (frame/section-stops {:items #(activity/rows f #{})})) "Tab goes from turn to turn")))

(deftest the-feed-keeps-its-newest-rows
  (let [f (feed-of (concat [[:session.turn/start {:turn 1}]]
                           (for [i (range 600)] [:agent.iteration/start {:iteration i}])
                           [[:session.turn/start {:turn 2}] [:agent.iteration/start {:iteration 0}]]))]
    (is (= activity/max-rows (count (:rows f))))
    (is (= 599 (:n (last (filter #(= 1 (:turn %)) (:rows f))))) "the oldest went first")
    (testing "and forgets a turn none of whose rows is kept"
      (let [g (feed-of (concat [[:session.turn/start {:turn 1}] [:agent.iteration/start {:iteration 0}]
                                [:session.turn/start {:turn 2}]]
                               (for [i (range 600)] [:agent.iteration/start {:iteration i}])))]
        (is (= [2] (keys (:turns g))))))))

(deftest the-view-follows-the-newest-and-shows-a-call
  (let [shown (atom nil)
        {:keys [view record! feed]} (activity/views {:show #(reset! shown [%1 %2])})]
    (is (= "Activity" (:title view)))
    (is (= [\f \s \a] (frame/view-keys [{:title "File"} {:title "Source"} view])) "A, beside File and Source")
    (is (str/includes? (:label (first ((:items view)))) "nothing yet"))
    (doseq [[e d] (conj a-turn [:agent.tools/results {:results [{:tool-name "read-file" :tool-result "import argparse" :elapsed-ms 12}]}])]
      (record! e d))
    (is (= 3 ((:mark view) ((:items view)))) "the mark on the newest row")
    ((:act view) (nth ((:items view)) 2))
    (is (= "read-file — greet/cli.py" (first @shown)))
    (is (str/includes? (second @shown) "import argparse"))
    (is (str/includes? (second @shown) "took: 12ms"))
    ((:act view) (first ((:items view))))
    (is (str/starts-with? (:label (first ((:items view)))) "▸ Turn 1 · ") "Enter on a heading folds the turn")
    (testing "an event it cannot read costs nothing"
      (record! :agent.tools/results {:results [nil 42]})
      (is (vector? (:rows @feed))))))
