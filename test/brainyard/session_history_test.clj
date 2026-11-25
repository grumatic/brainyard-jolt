;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.session-history-test
  "A session's whole history from what it left on disk (byj-lie): the
  trajectory's records numbered as `byj turns list` numbers turns, a turn
  only the turn refs or the snapshot know of put in its place."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.persist]
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
        commit! (fn [t msg & parent] (str/trim (:out (apply sh! "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid"
                                                            "commit-tree" t "-m" msg (when parent ["-p" (first parent)])))))
        ;; a turn kept as byj keeps it: its end, whose parent is its start
        ref! (fn [n q & [after-tree]]
               (let [before (commit! tree (str "byj " id " turn " n ", before"))
                     c (commit! (or after-tree tree) (str "byj " id " turn " n ": " q) before)]
                 (sh! "git" "update-ref" (str "refs/byj/turns/" id "/" n) c)
                 {:before before :after c}))
        changed (do (spit (io/file dir "notes.md") "hi\n")
                    (sh! "git" "add" "notes.md")
                    (str/trim (:out (sh! "git" "write-tree"))))
        f (io/file (brainyard.persist/state-dir {:project-dir dir} "sessions") id "trajectory.edn")
        kept-1 (ref! 1 "list the notes")
        _ (ref! 2 "a turn cancelled")
        _ (ref! 3 "read them" changed)]
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
      (is (= {:turn 2 :question "a turn cancelled" :steps []} (dissoc (second ts) :checkpoint)) "a turn only its ref knows: its question")
      (is (= "they say hi" (:answer (nth ts 2))) "the session's own record, not its child's")
      (testing "each turn's checkpoint, what a rewind goes back to, and how many files it changed (byj-d8c.1)"
        (is (= {:ref (str "refs/byj/turns/" id "/1") :before (:before kept-1) :after (:after kept-1) :files-changed 0}
               (:checkpoint (first ts))))
        (is (= 1 (:files-changed (:checkpoint (nth ts 2)))))))
    (sh! "git" "update-ref" "-d" (str "refs/byj/turns/" id "/3"))
    (is (nil? (:checkpoint (nth (sh/turns {:project-dir dir :working-dir dir} id) 2)))
        "a turn whose ref is gone has none, and is still there")
    (is (= [] (sh/turns {:project-dir dir :working-dir dir} "1794746057066-ffffffff")) "a session that left nothing")))

(deftest a-subagents-turn-is-put-under-the-call-that-ran-it
  ;; byj-cux: what a loaded session replays of its subagents, as a live turn
  ;; sends it (byj-wmm)
  (let [dir (fresh-dir "children")
        id "1794746057066-0000beef"
        f (io/file (brainyard.persist/state-dir {:project-dir dir} "sessions") id "trajectory.edn")
        rec (fn [m] (str (pr-str (merge {:v 3 :success true} m)) "\n"))]
    (io/make-parents f)
    (spit f (str
             ;; a grandchild, then its caller the child, then the session's turn: each written as it ends
             (rec {:question "deeper" :agent "explore/blue-fox-2" :owner "explore/red-cat-1" :turn 1
                   :iterations [{:n 1 :channel "tool" :tools [{:name "read-file" :args {:path "b.md"} :result "B"}]}]})
             (rec {:question "read a.md" :agent "explore/red-cat-1" :owner "root" :turn 1
                   :iterations [{:n 1 :channel "tool" :tools [{:name "read-file" :args {:path "a.md"} :result "A"}
                                                             {:name "agent$explore" :args {:question "deeper"}
                                                              :result "{:answer \"B\" :subagent-id \"explore/blue-fox-2\"}"}]}]})
             (rec {:question "look" :turn 1
                   :iterations [{:n 1 :channel "tool" :tools [{:name "list-dir" :args {:path "."} :result "a.md"}
                                                             {:name "agent$explore" :args {:question "read a.md"}
                                                              :result "{:answer \"A\" :subagent-id \"explore/red-cat-1\"}"}]}
                                {:n 2 :channel "answer" :answer "done"}]})
             (rec {:question "again" :turn 2
                   :iterations [{:n 1 :channel "tool" :tools [{:name "agent-registry$ask" :args {:id "explore/red-cat-1" :question "and?"}
                                                             :result "{:answer \"nothing\"}"}]}]})
             (rec {:question "and?" :agent "explore/red-cat-1" :owner "root" :turn 2
                   :iterations [{:n 1 :channel "tool" :tools [{:name "list-dir" :args {:path "."} :result "a.md b.md"}]}]})))
    (let [[t1 t2] (sh/turns {:project-dir dir :working-dir dir} id)
          [ls dispatch] (:tools (first (:steps t1)))
          child (first (:children dispatch))
          [read-a deeper] (:tools (first (:steps child)))]
      (is (= ["look" "again"] [(:question t1) (:question t2)]) "the session's own turns only")
      (is (nil? (:children ls)) "a call that ran no subagent")
      (is (= "explore/red-cat-1" (:agent child)) "the dispatch, by the instance its result names")
      (is (= "a.md" (get-in read-a [:args :path])))
      (is (= "explore/blue-fox-2" (:agent (first (:children deeper)))) "and the child's child under the child's call")
      (testing "a later turn's ask of the same instance holds that turn's record"
        (let [ask (first (:tools (first (:steps t2))))]
          (is (= ["list-dir"] (map :name (:tools (first (:steps (first (:children ask)))))))))))))

