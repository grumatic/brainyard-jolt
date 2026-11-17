;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.session-history-test
  "A session's whole history from what it left on disk (byj-lie): the
  trajectory's records numbered as `byj turns list` numbers turns, a turn
  only the turn refs or the snapshot know of put in its place."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.session-history :as sh]))

(defn- fresh-dir [name]
  (let [d (io/file "target/session-history-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(deftest a-record-is-numbered-by-its-turn-or-by-the-turn-refs-question
  (let [records [{:question "first question"}
                 {:question "a subagent's" :owner "root"}
                 {:question "third   question\nwith more"}
                 {:question "fourth" :turn 4}
                 {:question "fifth" :turn 5}]
        subjects [{:turn 1 :subject "first question"} {:turn 2 :subject "cancelled"}
                  {:turn 3 :subject "third question with more"} {:turn 4 :subject "fourth"}]
        n (fn [subs] (mapv (fn [[t r]] [t (:question r)]) (sh/numbered-records records subs)))]
    (is (= [[1 "first question"] [3 "third   question\nwith more"] [4 "fourth"] [5 "fifth"]] (n subjects))
        "a record from before :turn found by its question among the turn refs; a turn with no record skipped")
    (is (= [[1 "first question"] [2 "third   question\nwith more"] [4 "fourth"] [5 "fifth"]] (n []))
        "with no turn refs, by position, as before")
    (is (= [[1 "bd ready"] [3 "bd ready"]]
           (mapv (fn [[t r]] [t (:question r)])
                 (sh/numbered-records [{:question "bd ready"} {:question "bd ready"}]
                                      [{:turn 1 :subject "bd ready"} {:turn 2 :subject "x"} {:turn 3 :subject "bd ready"}])))
        "one question asked twice is two turns, in order")))

(deftest every-turn-is-put-together-from-what-the-session-left
  (let [dir (fresh-dir "turns")
        id "1794746057066-0000abcd"
        sh! #(apply fx/sh {:dir dir :out :string :err :string} %&)
        _ (sh! "git" "init" "-q")
        tree (str/trim (:out (sh! "git" "hash-object" "-w" "-t" "tree" "/dev/null")))
        ref! (fn [n q] (let [c (str/trim (:out (sh! "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid"
                                                  "commit-tree" tree "-m" (str "byj " id " turn " n ": " q))))]
                         (sh! "git" "update-ref" (str "refs/byj/turns/" id "/" n) c)))
        f (io/file dir ".brainyard" "sessions" id "trajectory.edn")]
    (ref! 1 "list the notes") (ref! 2 "a turn cancelled") (ref! 3 "read them")
    (io/make-parents f)
    (spit f (str (pr-str {:v 3 :question "list the notes" :success true
                          :iterations [{:n 1 :channel "tool" :thought "Listing."
                                        :tools [{:name "list-dir" :args {:path "."} :result "notes.md"}]}
                                       {:n 2 :channel "answer" :answer "one file: notes.md"}]})
                 "\n"
                 (pr-str {:v 3 :question "a child's" :owner "root" :turn 3 :iterations []}) "\n"
                 (pr-str {:v 3 :question "read them" :turn 3 :success true
                          :iterations [{:n 1 :channel "answer" :answer "they say hi"}]})
                 "\n"))
    (let [ts (sh/turns {:project-dir dir :working-dir dir} id)]
      (is (= [1 2 3] (mapv :turn ts)))
      (is (= {:turn 1 :question "list the notes" :answer "one file: notes.md"
              :steps [{:thought "Listing."} {:tools [{:name "list-dir" :args {:path "."} :result "notes.md"}]}]}
             (select-keys (first ts) [:turn :question :answer :steps])))
      (is (= {:turn 2 :question "a turn cancelled" :steps []} (second ts)) "a turn only its ref knows: its question")
      (is (= "they say hi" (:answer (nth ts 2))) "the session's own record, not its child's"))
    (is (= [] (sh/turns {:project-dir dir :working-dir dir} "1794746057066-ffffffff")) "a session that left nothing")))
