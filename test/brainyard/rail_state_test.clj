(ns brainyard.rail-state-test
  "The rails kept across restarts (byj-gly.457): one file per project, in a
  project of the test's own."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.rail-state :as rs]))

(defn- fresh-dir [name]
  (let [d (io/file "target/rail-state-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getPath d)))

(deftest the-frame-and-the-folds-outlast-the-store
  (let [dir (fresh-dir "kept")
        s (rs/store dir)
        kept (rs/keeper s atom)]
    (is (nil? (rs/frame s)) "nothing kept yet")
    (is (not (.exists (rs/file dir))) "and nothing written by reading")
    (rs/save-frame! s {:left {:open? true :view 0 :cols 30} :right {:open? false :view 1}})
    (let [issues (kept :issues/unfolded #{})
          open? (kept :self-improve/open? false)]
      (swap! issues conj "byj-gly")
      (reset! open? true))
    (testing "a run after it reads what this one left"
      (let [s2 (rs/store dir)
            kept2 (rs/keeper s2 atom)]
        (is (= {:left {:open? true :view 0 :cols 30} :right {:open? false :view 1}} (rs/frame s2)))
        (is (= #{"byj-gly"} @(kept2 :issues/unfolded #{})))
        (is (true? @(kept2 :self-improve/open? false)))
        (is (= #{} @(kept2 :memory/unfolded #{})) "what was never kept is its initial value")))
    (testing "one store for the window: a second session's fold leaves the first's"
      (let [a (kept :files/unfolded #{})
            b (kept :source/folded #{})]
        (swap! a conj "src")
        (swap! b conj :graph)
        (is (= #{"src"} (get-in @(:state (rs/store dir)) [:folds :files/unfolded])))
        (is (= #{:graph} (get-in @(:state (rs/store dir)) [:folds :source/folded])))))))

(deftest a-file-that-cannot-be-read-is-no-state
  (let [dir (fresh-dir "broken")]
    (io/make-parents (rs/file dir))
    (spit (rs/file dir) "{:frame {:left")
    (is (nil? (rs/frame (rs/store dir))) "unreadable: nothing kept")
    (spit (rs/file dir) "[1 2]")
    (is (nil? (rs/frame (rs/store dir))) "not a map: nothing kept")
    (spit (rs/file dir) (pr-str {:folds {:issues/unfolded [1 2] :self-improve/open? "yes"}}))
    (let [kept (rs/keeper (rs/store dir) atom)]
      (is (= #{} @(kept :issues/unfolded #{})) "a kept value of the wrong kind is the initial one")
      (is (false? @(kept :self-improve/open? false))))))
