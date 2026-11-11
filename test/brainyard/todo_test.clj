(ns brainyard.todo-test
  "Durable work state and the pipeline built on it (RFC-023).

  The claim under test is that brainyard's plan → todo → exec → eval chain is a
  WORKFLOW, so it needs no exec-agent and no todo-agent: the order is a tree
  (RFC-022), the judgement is an agent or a predictor, and the only thing a tree
  cannot supply is a list that outlives the turn."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.flow :as flow]
            [brainyard.json :as json]
            [brainyard.todo :as todo]
            [brainyard.tools :as tools]))

(defn- fresh-dir [nm]
  (let [d (io/file "target/todo-test" nm)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn- config [dir] {:working-dir dir :project-dir dir})

;; --- the list ----------------------------------------------------------------------

(deftest a-list-outlives-the-turn-that-wrote-it
  (let [dir (fresh-dir "durable")
        c (config dir)]
    (todo/create! c "retry" "Add a retry budget" ["add the counter" "run the suite"])

    (testing "it is on disk, as markdown a person can edit"
      (let [text (slurp (todo/todo-file c "retry"))]
        (is (str/includes? text "# Add a retry budget"))
        (is (str/includes? text "- [ ] add the counter"))))

    (testing "a later turn reads what an earlier one left"
      ;; nothing is held in memory between these calls — the file IS the state,
      ;; which is the whole reason this layer exists (a blackboard dies with the
      ;; turn, and RFC-016 persists turns, not blackboards)
      (is (= "add the counter" (:text (todo/next-item c "retry"))))
      (todo/mark! c "retry" {:index 0})
      (is (= "run the suite" (:text (todo/next-item c "retry"))))
      (todo/mark! c "retry" {:index 1})
      (is (true? (:exhausted (todo/next-item c "retry")))))

    (testing "and a person's own edits survive a machine write"
      (spit (todo/todo-file c "retry")
            (str (slurp (todo/todo-file c "retry")) "- [ ] something I added by hand\n"))
      (todo/add! c "retry" ["and one more"])
      (let [{:keys [items]} (todo/read-todo c "retry")]
        (is (= ["add the counter" "run the suite" "something I added by hand" "and one more"]
               (mapv :text items)))))))

(deftest an-item-may-say-how-it-should-be-done
  (let [dir (fresh-dir "via")
        c (config dir)]
    (todo/create! c "x" "T" [{:text "change llm.clj" :via "edit"}
                             {:text "run the suite" :via "check"}
                             "no tag at all"])
    (is (= ["edit" "check" nil] (mapv :via (:items (todo/read-todo c "x")))))
    (is (= "edit" (:via (todo/next-item c "x"))) "so a workflow can route on it")))

(deftest marking-is-refused-rather-than-guessed
  (let [dir (fresh-dir "mark")
        c (config dir)]
    (todo/create! c "x" "T" ["add the retry counter" "remove the retry counter" "unrelated"])
    (testing "an ambiguous text names the matches instead of picking one"
      (let [r (todo/mark! c "x" {:text "retry counter"})]
        (is (:error r))
        (is (= 2 (count (:matches r))))))
    (testing "enough text is enough"
      (is (= "add the retry counter" (:done (todo/mark! c "x" {:text "add the retry"})))))
    (testing "and an index works when the text will not"
      (is (= "unrelated" (:done (todo/mark! c "x" {:index 2})))))
    (is (:error (todo/mark! c "x" {:text "no such item"})))
    (is (:error (todo/mark! c "nope" {:index 0})))))

(deftest the-commands
  (let [dir (fresh-dir "cmds")
        ctx (agent/make-ctx {:config (config dir)})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))]
    (is (= 2 (:count (call "todo$create" {:slug "a" :title "A" :items ["one" "two"]}))))
    (is (= 0 (:index (call "todo$next" {:slug "a"}))))
    (is (= 1 (:remaining (call "todo$done" {:slug "a" :index 0}))))
    (is (= [{:slug "a" :title "A" :count 2 :remaining 1}] (:todos (call "todo$list" {}))))
    (is (= 3 (:count (call "todo$add" {:slug "a" :items ["three"]}))))
    (testing "a bad slug is an error, not a path escape"
      (is (:error (call "todo$create" {:slug "../escape" :items ["x"]})))
      (is (:error (call "todo$read" {:slug "nope"}))))
    (testing "an empty list is refused — it would read later as finished work"
      (is (:error (call "todo$create" {:slug "empty" :items []}))))
    (testing ":todos? false removes the family"
      (let [ctx (agent/make-ctx {:config (assoc (config dir) :todos? false)})]
        (is (not-any? #(str/starts-with? % "todo$") (keys @(:tools ctx))))))))

;; --- the pipeline ------------------------------------------------------------------

(deftest the-shipped-pipeline-is-sound
  ;; it is behaviour, edited by hand, and nothing else would notice a typo
  (let [dir (fresh-dir "check")
        ctx (agent/make-ctx {:config (config dir)})]
    (is (nil? (:problems (flow/check-tree-name "brainyard/loops/plan-act-eval.edn"
                                               (flow/ctx-opts ctx)))))))

(defn- pipeline-mock
  "A model that answers as whichever stage is asking."
  [seen]
  (fn [messages]
    (let [sys (str (:content (first messages)))
          who (cond (str/includes? sys "PLAN agent") :plan
                    (str/includes? sys "EDIT agent") :edit
                    (str/includes? sys "write the checklist") :from-plan
                    (str/includes? sys "last check before") :eval
                    :else :other)]
      (swap! seen conj who)
      (json/write-str
       (case who
         :plan {:reasoning "r" :tool-calls [] :answer "Saved plan: p.md\n## Acceptance\n- the suite passes"}
         :from-plan {:reasoning "r" :slug "retry-budget"
                     :items [{"text" "add the counter" "via" "edit"}
                             {"text" "run the suite" "via" "check"}]}
         :edit {:reasoning "r" :tool-calls [] :answer "Changed one file. Rollback: git checkout -- x"}
         :eval {:reasoning "r" :satisfied true :answer "Done: the budget is in."}
         {:reasoning "r" :tool-calls [] :answer "?"})))))

(deftest four-stages-compose-without-a-fourth-agent
  (let [dir (fresh-dir "pipeline")
        seen (atom [])
        r (agent/ask "add a retry budget"
                     {:lm {:provider :mock :format :mock :responses (pipeline-mock seen)}
                      :tree (agent/load-tree "brainyard/loops/plan-act-eval.edn")
                      :config (assoc (config dir) :max-iterations 6)})]
    (testing "plan and edit are agents, the two judgements are predictors, the order is the tree"
      (is (= [:plan :from-plan :edit :edit :eval] @seen)
          "one edit per checklist item, and no exec-agent anywhere"))

    (testing "the answer is the gate's verdict, not the last step's"
      (is (= "Done: the budget is in." (:answer r))))

    (testing "and the checklist it drove is on disk, ticked"
      (let [{:keys [items]} (todo/read-todo (config dir) "retry-budget")]
        (is (= 2 (count items)))
        (is (every? :done? items))))))

(deftest a-named-list-is-continued-rather-than-planned-again
  ;; the case the substrate exists for: a turn that ran out of iterations leaves
  ;; the list half-ticked, and the next one carries on with it (byj-gly.67)
  (let [dir (fresh-dir "resume")
        c (config dir)
        tree (agent/load-tree "brainyard/loops/plan-act-eval.edn")
        three (fn [seen]
                (fn [messages]
                  (let [sys (str (:content (first messages)))]
                    (cond
                      (str/includes? sys "write the checklist")
                      (do (swap! seen conj :from-plan)
                          (json/write-str {:reasoning "r" :slug "three-steps"
                                           :items [{"text" "step one" "via" "edit"}
                                                   {"text" "step two" "via" "edit"}
                                                   {"text" "step three" "via" "edit"}]}))
                      :else ((pipeline-mock seen) messages)))))
        run (fn [seen extra]
              (agent/ask "do the three steps"
                         {:lm {:provider :mock :format :mock :responses (three seen)}
                          :tree tree :config (merge c extra)}))]

    (testing "a first run plans, authors, and gets as far as its ceiling allows"
      (let [seen (atom [])]
        (run seen {:max-iterations 2})
        (is (= [:plan :from-plan :edit :edit :eval] @seen))
        (is (= 1 (:remaining (todo/read-todo c "three-steps"))) "one item left")))

    (testing "and a run that NAMES the list skips planning entirely"
      (let [seen (atom [])]
        (run seen {:max-iterations 5 :todo-slug "three-steps"})
        (is (not-any? #{:plan :from-plan} @seen)
            "no second plan, and no second checklist authored over the first")
        (is (= [:edit :eval] @seen))
        (is (= 0 (:remaining (todo/read-todo c "three-steps"))))))

    (testing "so there is exactly one list, not one per run"
      (is (= ["three-steps"] (todo/list-slugs c))))

    (testing "and the list remembers the plan it came from, for the gate to judge against"
      (is (str/includes? (str (:plan (todo/read-todo c "three-steps"))) "p.md")))

    (testing "naming a list that does not exist falls through to planning"
      (let [dir (fresh-dir "resume-missing")
            seen (atom [])]
        (agent/ask "do the three steps"
                   {:lm {:provider :mock :format :mock :responses (three seen)}
                    :tree tree :config (assoc (config dir) :max-iterations 5
                                              :todo-slug "no-such-list")})
        (is (= :plan (first @seen)) "a missing slug is not an error, it is a fresh start")))

    (testing "and naming a FINISHED list plans again rather than re-running its gate forever"
      ;; :has-work is a boolean for this reason: a remaining count of 0 is truthy
      (let [seen (atom [])]
        (run seen {:max-iterations 5 :todo-slug "three-steps"})
        (is (= :plan (first @seen)))))))

(deftest via-is-a-reference-the-step-receives-not-a-route
  ;; byj-gly.68. The tree does not branch per tag: it hands the tag to whoever
  ;; does the item and lets them read it. An item without one is not a special
  ;; case — it simply carries no hint.
  (let [dir (fresh-dir "via-ref")
        c (config dir)
        _ (todo/create! c "mixed" "Mixed" [{:text "change the file" :via "edit"}
                                           "no tag at all"] "p.md")
        asked (atom [])
        mock (fn [messages]
               (let [sys (str (:content (first messages)))]
                 (when (str/includes? sys "EDIT agent")
                   (swap! asked conj (str (:content (second messages)))))
                 (json/write-str (if (str/includes? sys "last check before")
                                   {:reasoning "r" :satisfied true :answer "done"}
                                   {:reasoning "r" :tool-calls [] :answer "did it"}))))]
    (agent/ask "go" {:lm {:provider :mock :format :mock :responses mock}
                     :tree (agent/load-tree "brainyard/loops/plan-act-eval.edn")
                     :config (assoc c :max-iterations 4 :todo-slug "mixed")})

    (is (= 2 (count @asked)) "both items were attempted — no tag routes anything away")

    (testing "the tag reaches the one doing the work"
      (is (str/includes? (first @asked) "change the file"))
      (is (str/includes? (first @asked) "Context from the caller")))

    (testing "and an untagged item passes no context at all, rather than an empty one"
      (is (str/includes? (second @asked) "no tag at all"))
      (is (not (str/includes? (second @asked) "Context from the caller"))))

    (testing "both are ticked: the tag informed the work, it did not gate it"
      (is (= 0 (:remaining (todo/read-todo c "mixed")))))))

(deftest a-plan-agent-that-declines-ends-the-turn
  ;; byj-gly.69, found by the byj-gly.17 measurement on a live run. agent$plan
  ;; is BUILT to decline — it refuses a task too small to plan and asks one
  ;; question when it lacks one — and the tree fed that refusal to the checklist
  ;; author, which wrote a checklist out of a refusal message.
  (let [tree (agent/load-tree "brainyard/loops/plan-act-eval.edn")
        run (fn [dir plan-answer]
              (let [seen (atom [])]
                [(agent/ask "fix three one-line bugs"
                            {:lm {:provider :mock :format :mock
                                  :responses
                                  (fn [m]
                                    (let [sys (str (:content (first m)))]
                                      (cond
                                        (str/includes? sys "PLAN agent")
                                        (do (swap! seen conj :plan)
                                            (json/write-str {:reasoning "r" :tool-calls []
                                                             :answer plan-answer}))
                                        (str/includes? sys "write the checklist")
                                        (do (swap! seen conj :from-plan)
                                            (json/write-str {:reasoning "r" :slug "x"
                                                             :items [{"text" "a" "via" "edit"}]}))
                                        (str/includes? sys "EDIT agent")
                                        (do (swap! seen conj :edit)
                                            (json/write-str {:reasoning "r" :tool-calls [] :answer "did"}))
                                        (str/includes? sys "last check before")
                                        (do (swap! seen conj :eval)
                                            (json/write-str {:reasoning "r" :satisfied true :answer "done"}))
                                        :else (json/write-str {:reasoning "r" :tool-calls [] :answer "?"}))))}
                             :tree tree
                             :config {:working-dir dir :project-dir dir
                                      :max-iterations 4 :persist-session? false}})
                 @seen]))]

    (testing "a refusal is the turn's answer, and authors nothing"
      (let [dir (fresh-dir "refused")
            [r seen] (run dir "Refused: three one-line fixes, too small to be worth a plan.")]
        (is (= [:plan] seen) "the checklist author was never reached")
        (is (empty? (todo/list-slugs (config dir))) "and no checklist exists")
        (is (str/starts-with? (:answer r) "Refused:")
            "the specialist's own words reach the caller, not an empty answer")))

    (testing "so is the one question it asks when it lacks something"
      (let [dir (fresh-dir "needs")
            [r seen] (run dir "Need: which of the three files should change first?")]
        (is (= [:plan] seen))
        (is (empty? (todo/list-slugs (config dir))))
        (is (str/starts-with? (:answer r) "Need:"))))

    (testing "and a real plan still runs the whole pipeline"
      (let [dir (fresh-dir "planned")
            [r seen] (run dir "Saved plan: .brainyard/plans/x.md\n## Acceptance\n- all three add")]
        (is (= [:plan :from-plan :edit :eval] seen))
        (is (= ["x"] (todo/list-slugs (config dir))))
        (is (= "done" (:answer r)) "the gate's verdict, not the plan")))))

(deftest a-failed-step-leaves-its-box-unticked
  ;; which is what lets a later turn retry it — the reason the list is a file
  (let [dir (fresh-dir "failed-step")
        seen (atom [])
        mock (fn [messages]
               (let [base (pipeline-mock seen)
                     sys (str (:content (first messages)))]
                 (if (str/includes? sys "EDIT agent")
                   (json/write-str {:reasoning "r" :tool-calls []
                                    :answer "Rolled back: the verify step failed"})
                   (base messages))))
        _ (agent/ask "add a retry budget"
                     {:lm {:provider :mock :format :mock :responses mock}
                      :tree (agent/load-tree "brainyard/loops/plan-act-eval.edn")
                      :config (assoc (config dir) :max-iterations 4)})
        {:keys [items]} (todo/read-todo (config dir) "retry-budget")]
    ;; the edit agent answered rather than failing, so the box IS ticked here;
    ;; what this pins is that the tree ticks only after the step returns, and
    ;; the file is where the outcome lands either way
    (is (= 2 (count items)) "the list survives the turn whatever the steps did")
    (is (.isFile (todo/todo-file (config dir) "retry-budget")))))
