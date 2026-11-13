;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.trajectory-test
  "The trajectory log (RFC-025): one record per turn, saying what the turn did.

  The projection is tested directly and the node end-to-end, because the two
  fail differently — a wrong projection writes a readable record of the wrong
  thing, and a wrong node writes nothing at all."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.flow :as flow]
            [brainyard.json :as json]
            [brainyard.schema :as schema]
            [brainyard.trajectory :as tr]))

(defn- fresh-dir [n]
  (let [d (io/file "target" n)]
    (when (.isDirectory d) (run! io/delete-file (reverse (file-seq d))))
    (.mkdirs d)
    d))

(defn- replying
  "A mock LM answering each call with the next of `replies`."
  [replies]
  {:provider :mock :format :mock :model "mock-1"
   :responses (let [left (atom (vec replies))]
                (fn [_] (let [r (first @left)]
                          (swap! left #(if (next %) (vec (rest %)) %))
                          (json/write-str r))))})

;; --- the projection -------------------------------------------------------------------

(deftest an-iteration-is-renamed-for-whoever-reads-the-file
  (testing "a tool iteration"
    (is (= {:n 2 :channel "tool" :thought "checking"
            :tools [{:name "read-file" :args {:path "x"} :result "contents"}]}
           (tr/project-iteration {:iteration 2 :thought "checking" :channel "tool"
                                  :tool-results [{:tool-name "read-file"
                                                  :tool-args {:path "x"}
                                                  :tool-result "contents"}]}))))
  (testing "an answer iteration"
    (is (= {:n 3 :channel "answer" :answer "42"}
           (tr/project-iteration {:iteration 3 :channel "answer" :answer "42"}))))

  (testing "a blank thought is absent rather than present and empty"
    (is (not (contains? (tr/project-iteration {:iteration 1 :channel "none" :thought "  "})
                        :thought))))

  (testing "a channel-less iteration is \"none\", never nil"
    (is (= "none" (:channel (tr/project-iteration {:iteration 1}))))))

(deftest one-runaway-result-does-not-make-a-record-nobody-can-read
  (let [long-result (str/join (repeat 9000 "x"))
        it (tr/project-iteration {:iteration 1 :channel "tool"
                                  :thought (str/join (repeat 5000 "t"))
                                  :tool-results [{:tool-name "bash" :tool-result long-result}]})]
    (is (= (+ tr/max-thought-chars (count "…[truncated 3000 chars]")) (count (:thought it))))
    (is (str/includes? (:result (first (:tools it))) "…[truncated 5000 chars]"))
    (testing "and the record still reads back as EDN"
      (is (map? (read-string (pr-str it)))))))

(deftest a-turn-that-answered-outside-the-loop-still-has-an-answer-iteration
  ;; ensure-answer and loop-fallback write an answer into st-memory AFTER the
  ;; loop, so no iteration ever carried it. Without this the record would end
  ;; on a tool call and read as a turn that never answered.
  (let [entries (tr/with-answer-iteration [{:n 1 :channel "tool"}] "the answer")]
    (is (= 2 (count entries)))
    (is (= {:n 2 :channel "answer" :answer "the answer"} (last entries)))
    (testing "and it claims no reasoning, because there was none"
      (is (not (contains? (last entries) :thought)))))

  (testing "a turn that answered in the loop is left alone"
    (let [entries [{:n 1 :channel "answer" :answer "already"}]]
      (is (= entries (tr/with-answer-iteration entries "already")))))

  (testing "a turn with no answer at all gains nothing"
    (is (= [{:n 1 :channel "tool"}] (tr/with-answer-iteration [{:n 1 :channel "tool"}] "")))))

(deftest the-answer-is-read-the-same-way-whatever-wrote-the-record
  (testing "v3 keeps it in the terminal iteration"
    (is (= "from v3" (tr/record-answer {:v 3 :iterations [{:channel "tool"}
                                                          {:channel "answer" :answer "from v3"}]}))))
  (testing "v2 kept it at the top, and an append-only file holds both forever"
    (is (= "from v2" (tr/record-answer {:v 2 :answer "from v2" :iterations [{:channel "tool"}]})))))

(deftest a-record-says-how-the-turn-ended-and-whether-that-counts-as-success
  (let [rec (tr/record {:session "s" :question "q" :answer "a" :terminated-by :answer-channel
                        :success (tr/succeeded? "a" :answer-channel)
                        :iterations [{:iteration 1 :channel "answer" :answer "a"}]
                        :model "m" :provider :mock
                        :usage {:input-tokens 10 :output-tokens 4 :llm-calls 1}
                        :started-at 1000 :ended-at 1250})]
    (is (= 3 (:v rec)))
    (is (= {:in 10 :out 4 :calls 1} (:usage rec)))
    (is (= 250 (:duration-ms rec)))
    (is (true? (:success rec)))
    (is (= 1 (:total-iterations rec)))
    (testing "there is no :turn — a per-session counter restarts on resume, so
              two records would both claim to be turn 1; position is the order"
      (is (not (contains? rec :turn)))))

  (testing "giving up is not success, however the answer reads"
    (is (false? (tr/succeeded? "*(hit the iteration limit)*" :max-iterations-exhausted)))
    (is (false? (tr/succeeded? "*(Tool dispatch blocked: …)*" :tool-blocked-by-hook)))
    (is (false? (tr/succeeded? "partial" :cancelled)))
    (is (false? (tr/succeeded? "" :answer-channel)) "nor is an empty answer"))

  (testing "an ending nobody has classified is not a success either"
    ;; naming the successes rather than the failures: a stop reason added later
    ;; scores as a failure, which is the answer that asks to be looked at
    (is (false? (tr/succeeded? "a" :some-future-ending)))
    (is (true? (tr/succeeded? "a" :answer-channel)))))

;; --- the node -------------------------------------------------------------------------

(deftest a-turn-records-what-it-did
  (let [dir (fresh-dir "trajectory-turn")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
             :session-id "s1"}]
    (agent/ask "what is in deps.edn"
               {:lm (replying [{:reasoning "I should look" :tool-calls [{:tool-name "list-dir"
                                                                        :tool-args {:path "."}}]}
                               {:reasoning "now I know" :tool-calls [] :answer "a deps file"}])
                :config cfg})
    (let [[rec :as recs] (tr/read-trajectories cfg "s1")]
      (is (= 1 (count recs)) "one record per turn")
      (is (= "what is in deps.edn" (:question rec)))
      (is (= "a deps file" (tr/record-answer rec)))
      (is (true? (:success rec)))
      (is (= "s1" (:session rec)))
      (is (= "react-agent" (:agent rec)) "which agent's turn this was")
      (is (= "mock-1" (:model rec)))
      (is (= :mock (:provider rec)))
      (is (number? (:duration-ms rec)))
      (is (pos? (:calls (:usage rec))) "what the turn cost in calls")
      (is (= "auto" (:permission-mode rec))
          "the permission mode the turn ran under, the config's when no one chose (byj-gly.288.49.9)")

      (is (nil? (schema/explain-problems tr/record-schema rec))
          "and it is the shape readers are written against (byj-gly.204)")
      (testing "every iteration is there, in order, with its tool calls"
        (is (= 2 (:total-iterations rec)))
        (is (= ["tool" "answer"] (mapv :channel (:iterations rec))))
        (is (= "list-dir" (:name (first (:tools (first (:iterations rec)))))))
        (is (= "I should look" (:thought (first (:iterations rec)))))))))

(deftest a-second-turn-appends-rather-than-replacing
  (let [dir (fresh-dir "trajectory-append")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
             :session-id "s1"}
        ask #(agent/ask % {:lm (replying [{:reasoning "r" :tool-calls [] :answer (str "answered " %)}])
                           :config cfg})]
    (ask "first")
    (ask "second")
    (let [recs (tr/read-trajectories cfg "s1")]
      (is (= 2 (count recs)))
      (is (= ["first" "second"] (mapv :question recs)) "oldest first — position is the ordering"))))

(deftest the-record-keeps-what-the-prompt-was-made-to-forget
  ;; :iterations is capped so the model's context stays affordable
  ;; (:max-history-iterations). The record is not: a log that forgets the
  ;; middle of a long turn cannot answer what the turn did.
  (let [dir (fresh-dir "trajectory-uncapped")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
             :session-id "s1"
             :max-history-iterations 2 :max-iterations 6}]
    ;; DISTINCT calls: a built-in hook ends a turn that repeats one identically
    (agent/ask "dig"
               {:lm (replying (concat (for [p ["." "src" "test" "docs"]]
                                        {:reasoning (str "looking at " p) :tool-calls
                                         [{:tool-name "list-dir" :tool-args {:path p}}]})
                                      [{:reasoning "done" :tool-calls [] :answer "found it"}]))
                :config cfg})
    (let [rec (first (tr/read-trajectories cfg "s1"))]
      (is (= 5 (:total-iterations rec))
          "all five, though the prompt only ever saw the last two"))))

(deftest a-loop-that-should-not-record-drops-the-node
  (let [dir (fresh-dir "trajectory-off")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
             :session-id "s1" :trajectory-log? false}]
    (agent/ask "q" {:lm (replying [{:reasoning "r" :tool-calls [] :answer "a"}]) :config cfg})
    (is (empty? (tr/read-trajectories cfg "s1")))
    (is (nil? (tr/log-file cfg)))
    (testing "and it is on otherwise: a session snapshot already saves as much"
      (is (some? (tr/log-file (dissoc cfg :trajectory-log?)))))))

(deftest a-run-told-to-leave-nothing-behind-leaves-no-record-either
  ;; --no-persist means save nothing from this session, and the record is part
  ;; of what a session leaves on disk — in the session's own directory. It is
  ;; also what makes the default defensible: this is on by default BECAUSE a
  ;; snapshot already saves the same questions and answers.
  (let [dir (fresh-dir "trajectory-no-persist")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
             :session-id "s1" :persist-session? false}]
    (agent/ask "q" {:lm (replying [{:reasoning "r" :tool-calls [] :answer "a"}]) :config cfg})
    (is (nil? (tr/log-file cfg)))
    (is (not (.isDirectory (io/file dir ".brainyard" "sessions")))
        "nothing under sessions/ at all")))

(deftest a-record-that-cannot-be-written-does-not-cost-the-turn
  (let [dir (fresh-dir "trajectory-unwritable")
        ;; a FILE where the session directory has to go, so .mkdirs fails
        blocker (io/file dir ".brainyard" "sessions")]
    (.mkdirs (.getParentFile blocker))
    (spit blocker "not a directory")
    (let [cfg {:working-dir (.getPath dir) :project-dir (.getPath dir)
               :session-id "s1"}
          r (agent/ask "q" {:lm (replying [{:reasoning "r" :tool-calls [] :answer "still answered"}])
                            :config cfg})]
      (is (= "still answered" (:answer r)))
      (is (nil? (tr/append! (tr/log-file cfg) {:v 3}))))))

(deftest a-torn-line-costs-only-itself
  ;; the file is appended to by a live process, so a reader can catch a
  ;; half-written tail. Every record before it must still come back.
  (let [dir (fresh-dir "trajectory-torn")
        cfg {:project-dir (.getPath dir) :session-id "s1"}
        f (tr/log-file cfg)]
    (tr/append! f {:v 3 :question "one"})
    (tr/append! f {:v 3 :question "two"})
    (spit f "{:v 3 :question \"half" :append true)
    (is (= ["one" "two"] (mapv :question (tr/read-trajectories cfg "s1"))))))

(deftest a-record-keeps-the-permission-mode-it-ran-under
  ;; divergence `trajectory-keeps-the-permission-mode`: what Prism's Permit
  ;; reads, as Claude Code's transcript gives it on every line 
  (is (= "auto-approve" (:permission-mode (tr/record {:session "s" :question "q" :answer "a" :terminated-by :answer-channel
                                                       :permission-mode :auto-approve}))))
  (is (not (contains? (tr/record {:session "s" :question "q" :answer "a" :terminated-by :answer-channel}) :permission-mode))
      "absent when nothing said it")
  (let [dir (fresh-dir "trajectory-mode")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir) :session-id "s2"}]
    (agent/ask "hi" {:lm (replying [{:reasoning "r" :tool-calls [] :answer "hello"}])
                     :config cfg :permission-mode-box (atom :ask-each-time)})
    (is (= "ask-each-time" (:permission-mode (first (tr/read-trajectories cfg "s2"))))
        "a person's last choice outranks the config's")))

(deftest a-turn-records-which-loop-it-ran
  ;; byj-ng7.19: a harness that ran a loop of its choosing (Terminal-Bench
  ;; in its tasks' containers, `byj ask --loop FILE`) checks each trial ran it
  (let [dir (fresh-dir "trajectory-loop")
        cfg {:working-dir (.getPath dir) :project-dir (.getPath dir) :session-id "s1"}
        lm #(replying [{:reasoning "done" :tool-calls [] :answer "ok"}])
        bundled (agent/load-tree nil (.getPath dir))
        [k o & cs] (agent/resolve-tree "react")
        own (into [k (assoc o :doc "a loop of the person's choosing")] cs)
        f (io/file dir "own.edn")]
    (spit f (pr-str own))
    (agent/ask "one" {:lm (lm) :config cfg})
    (agent/ask "two" {:lm (lm) :config cfg :tree (agent/load-tree (.getPath f) (.getPath dir))})
    (let [[a b] (tr/read-trajectories cfg "s1")
          checked (flow/check-tree-name (.getPath f) {:project-dir (.getPath dir)})]
      (is (= {:hash (flow/tree-hash bundled)} (:loop a)) "the bundled loop, as it ran: expanded")
      (is (= {:hash (:expanded-hash checked)} (:loop b)) "the chosen one: the hash flow check gives its file")
      (is (not= (:loop a) (:loop b))))))
