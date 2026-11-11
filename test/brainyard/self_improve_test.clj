(ns brainyard.self-improve-test
  "Self-improvement (RFC-017): which turns are candidates, what a judge's
  verdict comes to, the staging gate between a proposal and a live skill, and
  the session tree running distillation and refinement."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent]
            [brainyard.json :as json]
            [brainyard.self-improve :as si]
            [brainyard.session :as session]
            [brainyard.skills :as skills]
            [brainyard.tasks :as tasks]))

(defn- fresh-dir [name]
  (let [d (io/file "target/self-improve-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- config-for [dir & {:as more}]
  (merge {:working-dir dir :project-dir dir
          :skill-roots [(str dir "/.brainyard/skills")]}
         more))

(defn- call [tool-name & [err?]]
  (cond-> {:tool-name tool-name :tool-args {:path "."} :tool-result "a b c"}
    err? (assoc :tool-error? true :tool-result "{:error \"boom\"}")))

(defn- turn [& {:keys [steps terminated-by answer calls]
                :or {steps 2 terminated-by :answer-channel answer "done"}}]
  {:question "list the notes"
   :answer answer
   :terminated-by terminated-by
   :trajectory-iterations
   (conj (vec (for [c (or calls (repeat steps (call "list-dir")))]
                {:iteration 1 :thought "look" :channel "tool" :tool-results [c]}))
         {:iteration 9 :channel "answer" :answer answer})})

(def ^:private skill-md
  "---\nname: list-twice\ndescription: List a directory twice.\n---\n\n# List twice\n1. list-dir\n2. list-dir again\n")

;; --- candidates and verdicts ---------------------------------------------------------

(deftest only-a-successful-multi-step-turn-is-a-candidate
  (testing "brainyard's pre-filter: success, an answer, and at least two steps"
    (is (si/worth-scoring? (turn)))
    (is (not (si/worth-scoring? (turn :steps 1))) "one step carries no procedure")
    (is (not (si/worth-scoring? (turn :steps 0))) "reasoning alone is no step")
    (is (not (si/worth-scoring? (turn :terminated-by :max-iterations-exhausted))))
    (is (not (si/worth-scoring? (turn :answer " "))) "a blank answer is not a success"))
  (testing "a turn is rendered with its question, its calls and its answer, and cut with a note"
    (let [t (si/trajectory-text (turn))]
      (is (str/includes? t "Question: list the notes"))
      (is (str/includes? t "list-dir {:path \".\"} → a b c"))
      (is (str/includes? t "Answer: done")))
    (is (str/ends-with? (si/trajectory-text (turn :steps 40) 300) "…[trajectory truncated]")))
  (testing "a window heads each turn with its number and question, and gives each at least 1500"
    (let [w (si/window-text [(si/candidate 3 (turn)) (si/candidate 5 (turn))] 100)]
      (is (str/includes? w "=== turn 3 — list the notes ==="))
      (is (str/includes? w "=== turn 5 — list the notes ===")))))

(deftest a-procedure-is-seen-in-the-window
  (let [c (fn [n shape & [steps]] {:turn-number n :shape shape :steps (or steps (count shape))})]
    (testing "a procedure shape is the tools in order, a run of one tool counted once"
      (is (= ["list-dir" "read-file"]
             (si/procedure-shape (turn :calls [(call "list-dir") (call "list-dir") (call "read-file")])))))
    (testing "the same procedure twice is a reason to judge now"
      (is (= :repeated-procedure (si/auto-signal [(c 1 ["grep" "read-file"]) (c 2 ["bash"]) (c 3 ["grep" "read-file"])] {}))))
    (testing "one long turn is a reason too"
      (is (= :long-procedure (si/auto-signal [(c 1 ["bash" "edit-file"] 8)] {}))))
    (testing "different procedures, a repeated single tool, and short turns are not"
      (is (nil? (si/auto-signal [(c 1 ["grep" "read-file"]) (c 2 ["bash" "edit-file"])] {})))
      (is (nil? (si/auto-signal [(c 1 ["list-dir"]) (c 2 ["list-dir"])] {})) "one tool is not a procedure")
      (is (nil? (si/auto-signal [] {}))))
    (testing "the thresholds are the node's"
      (is (= :repeated-procedure (si/auto-signal [(c 1 ["list-dir"]) (c 2 ["list-dir"])] {:min-shape 1})))
      (is (nil? (si/auto-signal [(c 1 ["a" "b"]) (c 2 ["a" "b"])] {:repeats 3})))
      (is (= :long-procedure (si/auto-signal [(c 1 ["a" "b"] 3)] {:long-steps 3}))))))

(deftest a-verdict-stages-only-a-confident-well-named-draft
  (let [ok {:reusable true :score 0.9 :proposed-name "list-twice" :skill-md skill-md}]
    (testing "brainyard's decision matrix at threshold 0.7"
      (is (= :staged (si/distill-outcome 0.7 ok)))
      (is (= :staged (si/distill-outcome 0.7 (assoc ok :score "0.7"))) "a score given as text still counts")
      (is (= :no-score (si/distill-outcome 0.7 (dissoc ok :score))))
      (is (= :not-reusable (si/distill-outcome 0.7 (assoc ok :reusable false))))
      (is (= :below-threshold (si/distill-outcome 0.7 (assoc ok :score 0.69))))
      (is (= :invalid-name (si/distill-outcome 0.7 (assoc ok :proposed-name "List Twice"))))
      (is (= :invalid-name (si/distill-outcome 0.7 (assoc ok :proposed-name "9-lives"))))
      (is (= :empty-skill-md (si/distill-outcome 0.7 (assoc ok :skill-md " ")))))
    (testing "a refinement is staged only when the document was at fault and a revision came with it"
      (is (= :staged (si/refine-outcome {:should-revise true :revised-md skill-md})))
      (is (= :no-revision (si/refine-outcome {:should-revise false :revised-md skill-md})))
      (is (= :empty-revised-md (si/refine-outcome {:should-revise true :revised-md ""})))
      (is (= :no-score (si/refine-outcome {}))))))

;; --- the staging gate ----------------------------------------------------------------

(deftest a-proposal-is-staged-and-goes-live-only-when-accepted
  (let [dir (fresh-dir "gate")
        config (config-for dir)
        reg (atom {})]
    (is (= "list-twice" (:name (si/write-proposal! config {:name "list-twice" :skill-md skill-md
                                                           :score 0.9 :rationale "twice"}))))
    (testing "staged, it is listed and readable, and is no skill"
      (is (= ["list-twice"] (map :name (si/list-proposals config))))
      (is (= :distillation (:kind (first (si/list-proposals config)))))
      (is (= skill-md (:skill-md (si/read-proposal config "list-twice"))))
      (is (empty? (skills/list-skills config)) "the staging root is passed over by the loader"))
    (testing "accepting a distillation creates the skill, registers it, and clears the staging"
      (let [r (si/accept-proposal! config reg nil "list-twice")]
        (is (true? (:accepted r)))
        (is (= "create" (:op r)))
        (is (= "skill$list-twice" (:id r)))
        (is (contains? @reg "skill$list-twice"))
        (is (= ["list-twice"] (map :name (skills/list-skills config))))
        (is (empty? (si/list-proposals config)))))
    (testing "a refinement accepted rewrites the skill it revises"
      (si/write-proposal! config {:name "list-twice" :kind :refinement :rationale "missing a step"
                                  :skill-md (str skill-md "3. check the result\n")})
      (let [r (si/accept-proposal! config reg nil "list-twice")]
        (is (= "update" (:op r)))
        (is (str/includes? (:content (first (skills/list-skills config))) "3. check the result"))))
    (testing "a proposal whose write fails stays staged to try again"
      (si/write-proposal! config {:name "list-twice" :skill-md skill-md})
      (is (str/includes? (:error (si/accept-proposal! config reg nil "list-twice")) "already exists"))
      (is (= ["list-twice"] (map :name (si/list-proposals config)))))
    (testing "rejecting drops it; a name nobody staged is an error"
      (is (= {:rejected "list-twice"} (si/reject-proposal! config "list-twice")))
      (is (empty? (si/list-proposals config)))
      (is (:error (si/reject-proposal! config "list-twice")))
      (is (:error (si/accept-proposal! config reg nil "nothing-here")))
      (is (:error (si/write-proposal! config {:name "../escape" :skill-md skill-md}))))))

(deftest a-failed-turn-names-the-skills-it-used
  (let [dir (fresh-dir "triggers")
        config (config-for dir)]
    (skills/create-skill! config {:skill-name "list-twice" :content skill-md})
    (testing "a successful turn that loaded a skill gives it no reason to be judged"
      (is (empty? (si/refine-triggers config (turn :calls [(call "skill$list-twice") (call "list-dir")])))))
    (testing "a failed turn names every skill it loaded"
      (is (= [{:skill "list-twice" :why :failed-turn}]
             (map #(select-keys % [:skill :why])
                  (si/refine-triggers config (turn :terminated-by :max-iterations-exhausted
                                                   :calls [(call "skill$list-twice") (call "list-dir")]))))))
    (testing "a skill call that failed is judged whatever became of the turn, once"
      (is (= [{:skill "list-twice" :why :failed-call}]
             (map #(select-keys % [:skill :why])
                  (si/refine-triggers config (turn :calls [(call "skill$list-twice" true)
                                                           (call "skill$list-twice")]))))))
    (testing "a cancelled turn is not a failure, and an unknown skill is not judged"
      (is (empty? (si/refine-triggers config (turn :terminated-by :cancelled :calls [(call "skill$list-twice")]))))
      (is (empty? (si/refine-triggers config (turn :terminated-by :loop-error :calls [(call "skill$nope")])))))
    (testing "the evidence of a failed turn says how it ended"
      (is (str/starts-with? (si/failure-evidence {:why :failed-turn} (turn :terminated-by :loop-error))
                            "The turn did NOT succeed (terminated-by loop-error)")))))

;; --- in the session ------------------------------------------------------------------

(defn- judge? [messages] (str/includes? (:content (first messages)) "worth saving as a skill"))
(defn- refiner? [messages] (str/includes? (:content (first messages)) "decide whether the SKILL.md itself should be revised"))

(defn- scripted
  "Each turn calls the tools of the next entry of `plan` in order and answers
  (by default every turn lists the directory twice); a judge proposes
  `list-twice`. Each LLM call's prompt is kept, to count the judges."
  [calls & {:keys [verdict revision plan judge-ms] :or {plan (repeat ["list-dir" "list-dir"])}}]
  (let [pending (atom nil)
        plan (atom plan)]
    (fn [messages]
      (swap! calls conj (str/join "\n" (map :content messages)))
      (cond
        (judge? messages)
        (do (when judge-ms (Thread/sleep judge-ms))
        (json/write-str (merge {:reasoning "r" :reusable true :score 0.9 :proposed-name "list-twice"
                                :rationale "Listing twice is a procedure." :skill-md skill-md}
                               verdict)))
        (refiner? messages)
        (json/write-str (merge {:reasoning "r" :should-revise true :rationale "It skipped a check."
                                :revised-md (str skill-md "3. check it\n")}
                               revision))
        :else
        (do (when (nil? @pending)
              (reset! pending (vec (first @plan)))
              (swap! plan rest))
            (if-let [t (first @pending)]
              (do (swap! pending #(vec (rest %)))
                  (json/write-str {:reasoning "r" :tool-calls [{:tool-name t :tool-args {:path "."}}] :answer ""}))
              (do (reset! pending nil)
                  (json/write-str {:reasoning "r" :tool-calls [] :answer "listed"}))))))))

(defn- run-session [dir lines llm config & {:keys [before-line task-registry]}]
  (let [queue (atom (vec lines))
        out (atom [])
        events (atom [])
        final (session/run {:lm {:provider :mock :format :mock :responses llm}
                            :task-registry task-registry
                            :config (merge (config-for dir :max-iterations 4 :persist-session? false :recap? false)
                                           config)
                            :read-input (fn [_] (let [[l] @queue]
                                                  (swap! queue #(vec (rest %)))
                                                  (when (and l before-line) (before-line l))
                                                  l))
                            :emit (fn [kind text] (swap! out conj [kind text]))
                            :on-event (fn [ev data] (swap! events conj [ev data]))})]
    {:final final :out @out :events @events}))

(defn- until
  "Wait up to `ms` for `pred`: a judge runs off the turn now (byj-gly.247), so a
  line typed right after a turn may come before it has staged anything."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (when-not (or (pred) (> (System/currentTimeMillis) deadline)) (Thread/sleep 20) (recur)))))

(defn- judged [events] (map second (filter #(= :session.skill/judged (first %)) events)))

(deftest distillation-is-off-until-asked-for
  (let [dir (fresh-dir "off")
        calls (atom [])]
    (run-session dir ["one" "two"] (scripted calls) {})
    (is (not-any? #(str/includes? % "worth saving as a skill") @calls))
    (is (empty? (si/list-proposals (config-for dir))))))

(deftest a-window-of-one-judges-each-turn-as-it-ends
  (let [dir (fresh-dir "window-of-one")
        calls (atom [])
        {:keys [out events final]} (run-session dir ["list the notes"] (scripted calls)
                                                {:enable-skill-distillation true :skill-distill-judge-window 1})]
    (is (= 1 (count (filter #(str/includes? % "worth saving as a skill") @calls))))
    (testing "the proposal is staged, not live"
      (is (= ["list-twice"] (map :name (si/list-proposals (config-for dir)))))
      (is (empty? (skills/list-skills (config-for dir)))))
    (testing "the person is told, and the verdict is an event with what it cost"
      (is (some #(and (= :info (first %)) (str/includes? (second %) "skill proposal staged: `list-twice`")) out))
      (is (= [{:kind :distillation :outcome :staged :name "list-twice" :trigger :cadence :turns [1]}]
             (map #(select-keys % [:kind :outcome :name :trigger :turns]) (judged events)))))
    (testing "the judge's call is counted in the session's usage"
      (is (= 4 (get-in final [:usage :llm-calls]))))
    (testing "the judge read the turn: its question, its calls and its answer"
      (let [prompt (first (filter #(str/includes? % "worth saving as a skill") @calls))]
        (is (str/includes? prompt "list the notes"))
        (is (str/includes? prompt "list-dir {:path \".\"}"))
        (is (str/includes? prompt "Answer: listed"))))))

(deftest a-window-waits-at-most-n-turns-and-the-rest-is-flushed
  (let [dir (fresh-dir "cadence")
        calls (atom [])
        {:keys [events]} (run-session dir ["one" "two" "three"] (scripted calls :verdict {:reusable false :score 0.1})
                                      {:enable-skill-distillation true :skill-distill-judge-window 2})]
    (testing "turns 1-2 are one window, turn 3 is flushed when the session ends"
      (is (= [[1 2] [3]] (map :turns (judged events))))
      (is (= [:cadence :session-end] (map :trigger (judged events)))))
    (testing "a verdict that is not reusable stages nothing"
      (is (= [:not-reusable :not-reusable] (map :outcome (judged events))))
      (is (empty? (si/list-proposals (config-for dir)))))))

(deftest a-failed-turn-that-used-a-skill-stages-its-revision
  (let [dir (fresh-dir "refine")
        _ (skills/create-skill! (config-for dir) {:skill-name "list-twice" :content skill-md})
        calls (atom [])
        n (atom 0)
        ;; load the skill, then run out of iterations without answering
        llm (fn [messages]
              (swap! calls conj (:content (first messages)))
              (if (refiner? messages)
                (json/write-str {:reasoning "r" :should-revise true :rationale "It skipped a check."
                                 :revised-md (str skill-md "3. check it\n")})
                (json/write-str {:reasoning "r" :answer ""
                                 :tool-calls [{:tool-name (if (= 1 (swap! n inc)) "skill$list-twice" "list-dir")
                                               :tool-args {}}]})))
        {:keys [events]} (run-session dir ["list the notes"] llm
                                      {:enable-skill-refinement true :max-iterations 2})]
    (is (= [{:kind :refinement :outcome :staged :name "list-twice" :trigger :failed-turn}]
           (map #(select-keys % [:kind :outcome :name :trigger]) (judged events))))
    (let [p (si/read-proposal (config-for dir) "list-twice")]
      (is (= :refinement (:kind p)))
      (is (str/includes? (:skill-md p) "3. check it"))
      (is (str/includes? (:evidence p) "The turn did NOT succeed")))
    (testing "the live skill is untouched until the revision is accepted"
      (is (not (str/includes? (:content (first (skills/list-skills (config-for dir)))) "3. check it"))))))

(deftest a-person-accepts-a-proposal-and-the-model-cannot
  (let [dir (fresh-dir "slash")
        calls (atom [])
        {:keys [out]} (run-session dir ["list the notes" "/proposals" "/proposals show skill/list-twice"
                                        "/proposals accept list-twice" "/proposals" "/proposals reject nothing-here"
                                        "/proposals skils"]
                                   (scripted calls)
                                   {:enable-skill-distillation true :skill-distill-judge-window 1}
                                   ;; as a person would: look once the judge has staged it
                                   :before-line (let [first-look (atom true)]
                                                  (fn [l] (when (and (= "/proposals" l) @first-look)
                                                            (reset! first-look false)
                                                            (until #(seq (si/list-proposals (config-for dir))) 5000)))))
        infos (map second (filter #(= :info (first %)) out))]
    (testing "the model may list and read what it proposed, and may not accept or reject it"
      (let [reg @(:tools (brainyard.agent/make-ctx {:config (config-for dir)}))]
        (is (contains? reg "proposal$list"))
        (is (contains? reg "proposal$read"))
        (is (not-any? #(or (str/includes? % "accept") (str/includes? % "reject")) (keys reg)))))
    (testing "/proposals lists what is staged, by kind/name, and shows the draft (byj-gly.249.1)"
      (is (some #(str/includes? % "1 proposal(s), none live until accepted:\n  skill/list-twice · 0.90\n    distillation — ") infos))
      (is (some #(str/includes? % "# List twice") infos)))
    (testing "/proposals accept makes it a skill — a bare name one kind has is enough — and the staging is gone"
      (is (some #(str/includes? % "created skill `list-twice` (project)") infos))
      (is (= ["list-twice"] (map :name (skills/list-skills (config-for dir)))))
      (is (some #(str/starts-with? % "nothing proposed") infos)))
    (testing "a name nobody staged is said to be one, and a kind that is not one"
      (is (some #(= "no proposal named 'nothing-here' — /proposals lists them" %) infos))
      (is (some #(str/starts-with? % "no kind of proposal named 'skils' — skill, tool, program") infos)))))

(deftest a-window-is-judged-as-soon-as-a-procedure-repeats
  (let [dir (fresh-dir "auto")
        calls (atom [])
        {:keys [events]} (run-session dir ["one" "two" "three" "four"]
                                      (scripted calls :verdict {:reusable false :score 0.1}
                                                :plan [["list-dir" "read-file"] ["grep" "read-file"]
                                                       ["list-dir" "read-file"] ["list-dir" "read-file"]])
                                      {:enable-skill-distillation true})]
    (testing "turn 3 repeats turn 1's procedure, so turns 1-3 are judged then, not at turn 12"
      (is (= [{:turns [1 2 3] :trigger :repeated-procedure}
              {:turns [4] :trigger :session-end}]
             (map #(select-keys % [:turns :trigger]) (judged events)))))
    (testing "the check itself costs nothing: two judges in four turns"
      (is (= 2 (count (filter #(str/includes? % "worth saving as a skill") @calls)))))))

(deftest a-window-waits-while-nothing-repeats
  (let [dir (fresh-dir "auto-quiet")
        calls (atom [])
        {:keys [events]} (run-session dir ["one" "two" "three"]
                                      (scripted calls :verdict {:reusable false :score 0.1}
                                                :plan [["list-dir" "read-file"] ["grep" "read-file"] ["bash" "list-dir"]])
                                      {:enable-skill-distillation true})]
    (is (= [{:turns [1 2 3] :trigger :session-end}]
           (map #(select-keys % [:turns :trigger]) (judged events))))))


(deftest a-judge-runs-off-the-turn
  ;; a judge's model call held up the next prompt (byj-gly.247); brainyard runs
  ;; it off the turn, one at a time, and waits for it briefly at the end
  (let [dir (fresh-dir "off-turn")
        calls (atom [])
        reg (tasks/registry)
        judging-at-next-prompt (atom nil)
        {:keys [out events]} (run-session dir ["one" "two"] (scripted calls :judge-ms 600)
                                          {:enable-skill-distillation true :skill-distill-judge-window 1
                                           :self-improve-drain-ms 5000}
                                          :task-registry reg
                                          :before-line (fn [l] (when (= "two" l)
                                                                 (reset! judging-at-next-prompt
                                                                         (some #(and (= :self-improve (:job-type %)) (= :running (:status %)))
                                                                               (vals @reg))))))]
    (testing "the next prompt is asked while the judge still runs, as a task the person can see"
      (is (true? @judging-at-next-prompt))
      (is (some #(str/starts-with? (:name %) "self-improve: distill a skill") (vals @reg))))
    (testing "one at a time: the second turn waits for the first judge, and is judged when the session ends"
      (is (= 2 (count (filter #(str/includes? % "worth saving as a skill") @calls))))
      (is (= [[1] [2]] (map :turns (judged events)))))
    (testing "the end waited for it: its proposal is staged"
      (is (= ["list-twice"] (map :name (si/list-proposals (config-for dir))))))
    (testing "the model's task$list leaves it out; it is the person's"
      (let [task-list (first (filter #(= "task$list" (:name %)) (tasks/commands)))]
        (is (empty? (:tasks ((:fn task-list) {} {:task-registry reg}))))))))

(deftest a-judge-that-outruns-the-grace-is-cancelled-and-said-to-be
  (let [dir (fresh-dir "drain")
        calls (atom [])
        {:keys [out]} (run-session dir ["one"] (scripted calls :judge-ms 3000)
                                   {:enable-skill-distillation true :skill-distill-judge-window 1
                                    :self-improve-drain-ms 100})]
    (is (some #(and (= :info (first %)) (= "1 self-improvement judge(s) did not finish in time and are cancelled" (second %))) out))
    (is (empty? (si/list-proposals (config-for dir))))))
