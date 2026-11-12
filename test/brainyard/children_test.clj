;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.children-test
  "A session's subagents as a tree (byj-gly.298.2): from a saved session's
  trajectory, from an open session's buffers, nested by owner, and an
  agent$claude child joined to its Claude Code session."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [brainyard.children :as children]))

(defn- fresh-dir [name]
  (let [d (io/file "target/children-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- turn [agent owner question answer ts & [success]]
  (cond-> {:v 3 :ts ts :agent agent :question question :success (if (nil? success) true success)
           :iterations [{:n 1 :channel "answer" :answer answer}]}
    owner (assoc :owner owner)))

(def records
  [(turn "react-agent" nil "fix the parser" "on it" 1)
   (turn "explore/azure-whale-1" "root" "where is the parser" "src/p.clj" 2)
   (turn "plan/amber-otter-2" "root" "plan the fix" "Saved plan" 3)
   (turn "explore/teal-fox-3" "plan/amber-otter-2" "read the tests" "test/p_test.clj" 4)
   (turn "plan/amber-otter-2" "root" "and the docs?" "no docs change" 5)
   (turn "beads/old-owl-4" nil "file it" "filed" 6 false)])

(deftest a-saved-sessions-children-come-from-its-trajectory
  (let [cs (children/from-trajectory records)
        by-id (into {} (map (juxt :id identity)) cs)]
    (is (= ["explore/azure-whale-1" "explore/teal-fox-3" "plan/amber-otter-2" "beads/old-owl-4"] (map :id cs))
        "one per instance, oldest last move first; the session's own turns are not children")
    (is (= {:id "plan/amber-otter-2" :agent "plan" :owner "root" :question "plan the fix"
            :answer "no docs change" :success? true :at 5 :live? false :turns 2}
           (by-id "plan/amber-otter-2"))
        "its first question, its last answer, its turns")
    (is (= [false nil] ((juxt :success? :owner) (by-id "beads/old-owl-4")))
        "a turn that did not answer; a record from before owners were recorded")
    (is (= (inc children/answer-chars)
           (count (:answer (first (children/from-trajectory [(turn "x/y-1" "root" "q" (apply str (repeat 1000 "a")) 1)])))))
        "a long answer is clipped for its row")))

(deftest children-nest-by-who-dispatched-them
  (let [t (children/tree (children/from-trajectory records))]
    (is (= ["beads/old-owl-4" "plan/amber-otter-2" "explore/azure-whale-1"] (map :id t))
        "the top: dispatched by the session, or by no one recorded; newest first")
    (is (= ["explore/teal-fox-3"] (map :id (:children (second t)))) "under the plan that dispatched it")
    (is (= [] (:children (first t)))))
  (testing "an owner not among them puts a child at the top, and a loop cannot hang it"
    (is (= ["a/x-1"] (map :id (children/tree [{:id "a/x-1" :owner "gone/y-9" :at 1}]))))
    (is (vector? (children/tree [{:id "a/x-1" :owner "b/y-2" :at 1} {:id "b/y-2" :owner "a/x-1" :at 2}])))))

(deftest an-open-sessions-children-come-from-its-buffers
  (is (= [{:id "explore/a-1" :agent "explore" :owner "root" :question "q" :answer "a" :success? true
           :at 10 :live? false :turns 1}
          {:id "plan/b-2" :agent "plan" :question "p" :answer nil :success? false :at 20 :live? true :turns 2}]
         (children/from-buffers {"plan/b-2" {:agent "plan" :question "p" :at 20 :live {:iter 1} :turns 2}
                                 "explore/a-1" {:agent "explore" :owner "root" :question "q" :answer "a"
                                                :success? true :at 10 :turns 1}}))))

(deftest an-agent-claude-child-carries-its-claude-code-session
  (let [t (children/with-claude
            (children/tree [{:id "claude/navy-stork-1" :owner "root" :at 1}
                            {:id "explore/x-2" :owner "root" :at 2}])
            [{:instance-id "claude/navy-stork-1" :session-id "cc-1" :state "needs-person" :branch "bd/byj-9"
              :bead "byj-9" :parent-session "s-1"}]
            {:live [{:id "cc-1" :title "fix the parser"}] :saved []})]
    (is (= {:session-id "cc-1" :state "needs-person" :branch "bd/byj-9" :bead "byj-9"
            :title "fix the parser" :live? true}
           (:claude (second t))))
    (is (nil? (:claude (first t))) "a child that drives none carries none")
    (is (= #{"cc-1"} (children/claude-session-ids t)) "the sessions to list under it, not in the Claude Code group")))

(deftest a-saved-sessions-children-are-read-again-only-when-its-file-changes
  (let [dir (fresh-dir "saved")
        f (io/file dir ".brainyard" "sessions" "s-1" "trajectory.edn")
        config {:project-dir dir}]
    (is (= [] (children/saved config "s-1")) "no trajectory, no children")
    (io/make-parents f)
    (spit f (apply str (map #(str (pr-str %) "\n") (take 3 records))))
    (let [first-read (children/saved config "s-1")]
      (is (= 2 (count first-read)))
      (is (identical? first-read (children/saved config "s-1")) "unchanged: not read again")
      (spit f (str (pr-str (nth records 3)) "\n") :append true)
      (is (= 3 (count (children/saved config "s-1"))) "changed: read again"))))
