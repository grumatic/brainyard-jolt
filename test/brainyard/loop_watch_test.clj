;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-watch-test
  "The turns since a loop change against the turns before (RFC-071 \"After
  adoption\", byj-ng7.16), from records written as a session writes them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.loop-proposals :as lp]
            [brainyard.loop-watch :as watch]
            [brainyard.project-loops :as project-loops]
            [brainyard.trajectory :as trajectory]))

(defn- fresh-dir [name]
  (let [d (io/file "target/loop-watch-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- turns!
  "`n` turns of session `sid`, ending `at` ms on, a second apart, each of
  `iters` iterations, ended by `how`."
  [dir sid n at iters how]
  (let [cfg {:project-dir dir :working-dir dir :session-id sid}]
    (doseq [i (range n)]
      (trajectory/append! (trajectory/log-file cfg)
                          (trajectory/record {:session sid :question (str "q" i) :answer "a"
                                              :terminated-by how :ended-at (+ at (* 1000 i))
                                              :iterations (vec (repeat iters {:reasoning "r"}))})))))

(defn- changed!
  "The project's react loop accepted now, as a person's accept does."
  [dir]
  (let [[k o & cs] (project-loops/bundled-tree "react")
        c {:project-dir dir :working-dir dir}]
    (lp/accept! c (:id (lp/stage! c {:loop "react" :tree (into [k (assoc o :doc "new")] cs)})))))

(deftest a-regression-since-a-change-is-offered-back-as-a-revert
  (let [dir (fresh-dir "regressed")
        c {:project-dir dir :working-dir dir}
        now (System/currentTimeMillis)]
    (is (= :no-change (:verdict (watch/watch c {}))))
    (turns! dir "before" 8 (- now 600000) 2 :answer-channel)
    (changed! dir)
    (turns! dir "after" 3 (+ now 60000) 9 :max-iterations)
    (is (= :too-early (:verdict (watch/watch! c {}))) "three turns since decide nothing")
    (turns! dir "after-2" 3 (+ now 120000) 9 :max-iterations)
    (let [w (watch/watch! c {})]
      (is (= :regressed (:verdict w)))
      (is (= [6 8] [(:after w) (:before w)]))
      (is (= #{:iterations :unfinished} (set (map :metric (:regressions w)))))
      (is (:staged w) "the loop before the change, staged")
      (let [p (lp/read-proposal c (:id (:staged w)))]
        (is (= :revert (:action p)))
        (is (nil? (:tree p)) "the change replaced the bundled loop, so that is what comes back")
        (is (str/starts-with? (:why p) "Since loop/react-")))
      (is (some #(str/includes? % "staged loop/") (watch/summary-lines w))))
    (is (str/includes? (:not-staged (watch/watch! c {})) "already waits") "one revert waiting is enough")
    (is (some? (project-loops/project-loop-file dir "react")) "and nothing was accepted")))

(deftest turns-that-hold-stage-nothing
  (let [dir (fresh-dir "holding")
        c {:project-dir dir :working-dir dir}
        now (System/currentTimeMillis)]
    (turns! dir "before" 8 (- now 600000) 3 :answer-channel)
    (changed! dir)
    (turns! dir "after" 6 (+ now 60000) 3 :answer-channel)
    (testing "a session begun before the change ran the old loop, though its turns end after"
      (turns! dir "straddling" 1 (- now 1000) 3 :answer-channel)
      (turns! dir "straddling" 4 (+ now 90000) 20 :max-iterations))
    (let [w (watch/watch! c {})]
      (is (= :holding (:verdict w)))
      (is (= 6 (:after w)))
      (is (nil? (:staged w)))
      (is (empty? (lp/pending c))))))

(deftest a-refusal-is-a-failed-call-that-says-so
  ;; a gate's block prints as {:hook-blocked …}, and a read of a file that
  ;; mentions refusals did not fail (byj-ng7.17)
  (let [m (watch/turn-metrics {:terminated-by :answer-channel
                               :iterations [{:tools [{:name "write-file" :error? true
                                                      :result "{:hook-blocked true, :reason \"Tool call refused (loop): …\"}"}
                                                     {:name "read-file" :result "a gate's ask is refused, headless"}]}]})]
    (is (= {:tool-errors 1 :refusals 1} (select-keys m [:tool-errors :refusals])))))

(deftest a-regression-since-an-attachment-is-offered-back-as-a-detach
  ;; RFC-072: attaching to a slot is a change to the loop every turn runs,
  ;; watched as any is, and undone by its opposite
  (let [dir (fresh-dir "attached")
        c {:project-dir dir :working-dir dir}
        now (System/currentTimeMillis)]
    (turns! dir "before" 8 (- now 600000) 2 :answer-channel)
    (lp/accept! c (:id (lp/stage-attachment! c {:slot :react/ext-after-tools :name "lint"
                                                :tree [:action {:id :lint/n :fn :mem/set :set {:ext/notices "x"}}]})))
    (turns! dir "after" 6 (+ now 60000) 9 :max-iterations)
    (let [w (watch/watch! c {})
          p (lp/read-proposal c (:id (:staged w)))]
      (is (= :regressed (:verdict w)))
      (is (= :attach (get-in w [:change :action])))
      (is (= [:attach :detach "lint" true] ((juxt :kind :action :name :revert?) p)))
      (is (str/starts-with? (:why p) "Since loop/ext-lint-")))
    (is (str/includes? (:not-staged (watch/watch! c {})) "already waits"))))

(deftest a-call-the-loops-tree-made-is-no-tool-error-of-the-model
  ;; byj-8gc: an attachment that runs the tests and fails on purpose is the
  ;; tree deciding, not the model erring
  (is (= [1 0] ((juxt :tool-errors :refusals)
                (watch/turn-metrics {:iterations [{:tools [{:name "bash" :result "{:exit-code 2}"}
                                                           {:name "bash" :result "{:exit-code 1}" :node :tests/run
                                                            :slot :react/ext-answer-check}]}]})))))
