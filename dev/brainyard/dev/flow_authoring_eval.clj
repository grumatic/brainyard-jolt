(ns brainyard.dev.flow-authoring-eval
  "Does the `flow$*` surface pay for itself? (byj-gly.63, RFC-022 stage 5)

      REPS=3 jolt -A:dev -m brainyard.dev.flow-authoring-eval sonnet
      TASKS=repair jolt -A:dev -m brainyard.dev.flow-authoring-eval sonnet haiku

  The same authoring tasks run through the unmodified loop with `flow$*` bound
  and without it (`:flows? false`). The prompts never mention the commands; the
  model finds them in the tool listing or it does not.

  Three tasks, graded structurally rather than by reading an answer:

  | task   | what it tests |
  |--------|---------------|
  | repair | a loop with four planted defects, three of them SILENT at run time |
  | extend | add a node that runs an existing tool, wired to the blackboard |
  | build  | write a small loop from nothing |

  Each task is scored by PARTIAL CREDIT — the fraction of its criteria met, one
  per planted defect or required element — and a model that deletes the hard
  part to make a problem go away scores 0. A binary metric was tried first and
  discarded: on the first live run one arm fixed three of four planted defects
  and the other two of four, and all-or-nothing scored both 0.

  The verdict is `evaluate/compare-scores` over the per-rep means, not the raw
  proportion: RFC-021 measured ±0.05 run-to-run noise on a 12-example set, and
  three tasks are far fewer, so a difference inside the Welch margin is
  reported as no difference. That is the whole point of running this rather
  than arguing about it."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.evaluate :as ev]
            [brainyard.flow :as flow]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]
            [brainyard.tools :as tools]))

;; --- the fixtures ------------------------------------------------------------------

(def ^:private broken-loop
  "Four defects. Only the unregistered fn fails loudly; a mistyped config key
  reads nil, a mistyped zone renders as nothing, and an unbounded repeat hangs."
  (str "[:sequence {:id :w/turn}\n"
       " [:action {:id :w/init :fn :react/init}]\n"
       " [:action {:id :w/budget :fn :context/enforce :every-n :config/rebudget-every-iter}]\n"
       " [:repeat {:id :w/iterate}\n"
       "  [:sequence {:id :w/iteration}\n"
       "   [:action {:id :w/inc :fn :react/inc-iter}]\n"
       "   [:action {:id :w/think :fn :react/thnk-act :predictor \"react/think-act\"\n"
       "             :zones [:agent-core :sesion-context]}]]]\n"
       " [:action {:id :w/stamp :fn :react/stamp-answer}]]\n"))

(def ^:private working-loop
  (str "[:sequence {:id :w/turn}\n"
       " [:action {:id :w/init :fn :react/init}]\n"
       " [:action {:id :w/stamp :fn :react/stamp-answer}]]\n"))

(def ^:private classify-tool
  "A user tool the `extend` task must wire in, already defined and tested.
  Written in the on-disk shape `toolbox/load-user-tools!` reads (RFC-010): a
  malli `:input-schema` and a `:body` STRING."
  (pr-str {:name "classify"
           :description "Classify a report's severity."
           :input-schema [:map [:text [:string {:desc "The report text"}]]]
           :body "(fn [args] {:severity (if (clojure.string/includes? (:text args) \"disk\") \"high\" \"low\")})"}))

;; --- grading -----------------------------------------------------------------------

(defn- read-tree [path]
  (try (edn/read-string (slurp path)) (catch Throwable _ nil)))

(defn- checks-clean?
  "The tree the model left behind loads, expands and checks with no problems.

  Checked against a ctx built from the TASK's own directory, so the fixture's
  user tools and predictors are bound exactly as they were for the model. A
  grader that checked against a bare registry would call every correct `extend`
  answer wrong, because the tool it wires in would read as unknown — which is
  what a dry run of this grader did before it was fixed."
  [dir path]
  (let [ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        {:keys [tree problems]} (flow/check-tree-name path (flow/ctx-opts ctx))]
    (and tree (empty? problems))))

(defn- node-with [tree pred]
  (first (filter #(and (vector? %) (map? (second %)) (pred (second %)))
                 (tree-seq vector? #(filter vector? (drop 2 %)) tree))))

(defn- problems-for
  "`flow/check`'s problems for the tree the model left behind, checked against
  a ctx built from the TASK's own directory so its user tools and predictors
  are bound exactly as they were for the model."
  [dir path]
  (let [ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        {:keys [tree problems]} (flow/check-tree-name path (flow/ctx-opts ctx))]
    (if tree (map :problem problems) [::unloadable])))

(defn- fraction
  "How many of `criteria` hold, as 0.0–1.0.

  Partial credit, not all-or-nothing. The first live run showed why: on the
  repair task one arm fixed three of four planted defects and the other two of
  four, and a binary metric scored both 0 and threw the whole signal away. A
  metric that cannot separate the arms cannot decide anything, which is
  RFC-021's lesson applied to its own successor."
  [criteria]
  (/ (count (filter #(try (boolean (%)) (catch Throwable _ false)) criteria))
     (double (count criteria))))

(def tasks
  [{:id :repair
    :files {"loop.edn" broken-loop}
    :prompt (str "The behavior-tree loop in loop.edn is broken in several ways. Fix loop.edn so the "
                 "loop is valid. Keep its structure and node ids; change only what is wrong. "
                 "Answer with a one-line summary of what you fixed.")
    ;; one criterion per planted defect: each is gone from check's report, and
    ;; the structure was kept rather than deleted to make a problem go away
    :graded (fn [dir]
              (let [path (str dir "/loop.edn")
                    t (read-tree path)
                    ps (problems-for dir path)
                    gone? (fn [re] #(not-any? (fn [p] (re-find re (str p))) ps))
                    kept? (some? (node-with t #(= :w/iterate (:id %))))]
                (if-not kept?
                  0.0
                  (fraction [(gone? #"unregistered fn")
                             (gone? #"unknown zone")
                             (gone? #"unknown config key")
                             (gone? #"neither :max-n nor :until")]))))}

   {:id :extend
    :files {"loop.edn" working-loop
            ".brainyard/tools/classify.edn" classify-tool}
    :prompt (str "loop.edn is a working behavior-tree loop. A tool named user$tool$classify is "
                 "available; it takes a `text` argument and returns a map with a `severity` key. "
                 "Add a node to loop.edn that runs that tool on the turn's question and puts the "
                 "severity on the blackboard under the key :severity. The loop must still be valid. "
                 "Answer with a one-line summary.")
    :graded (fn [dir]
              (let [path (str dir "/loop.edn")
                    t (read-tree path)
                    n (node-with t #(= "user$tool$classify" (:tool %)))]
                (fraction [#(empty? (problems-for dir path))
                           #(some? n)
                           #(= :question (first (vals (:in (second n)))))
                           #(contains? (set (keys (:out (second n)))) :severity)])))}

   {:id :build
    :files {}
    :prompt (str "Write a behavior-tree loop to loop.edn. It must, in order: initialise the turn, "
                 "run the predictor \"react/think-act\" with the agent-core and session-context zones, "
                 "and stamp the answer. Use the leaf fns the runtime already provides. "
                 "The loop must be valid. Answer with a one-line summary.")
    :graded (fn [dir]
              (let [path (str dir "/loop.edn")
                    t (read-tree path)]
                (fraction [#(empty? (problems-for dir path))
                           #(some? (node-with t (fn [o] (= :react/init (:fn o)))))
                           #(some? (node-with t (fn [o] (= "react/think-act" (:predictor o)))))
                           #(some? (node-with t (fn [o] (= :react/stamp-answer (:fn o)))))])))}])

;; --- running -----------------------------------------------------------------------

(defn- setup! [dir files]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (.mkdirs (io/file dir))
  (doseq [[path content] files]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(defn run-task [model flows? {:keys [id prompt files graded]}]
  (let [dir (str "target/flow-authoring-eval/" model "/" (if flows? "with" "without") "/" (name id))
        _ (setup! dir files)
        prompt-chars (atom 0)
        called (atom [])
        t0 (System/currentTimeMillis)
        r (try
            (agent/ask prompt
                       {:lm (if (= "mock" model)
                              ;; a dry run: proves the harness, the graders and
                              ;; the verdict run end to end without spending a
                              ;; live call. Every task scores 0, as it should
                              {:provider :mock :format :mock
                               :responses (fn [_] "{\"reasoning\":\"r\",\"tool-calls\":[],\"answer\":\"done\"}")}
                              (llm/create-lm :claude-code #(System/getenv %) {:model model}))
                        :tools tools/builtin-tools
                        :config {:working-dir dir :project-dir dir
                                 :max-iterations 12
                                 :flows? flows?}
                        :on-event (fn [e d]
                                    (case e
                                      :agent.llm/request
                                      (swap! prompt-chars + (reduce + (map (comp count :content) (:messages d))))
                                      :agent.tools/calls
                                      (swap! called into (map :tool-name (:tool-calls d)))
                                      nil))})
            (catch Throwable e {:error (ex-message e)}))
        score (try (double (graded dir)) (catch Throwable _ 0.0))]
    {:model model :task id :flows? flows?
     :score score
     :iterations (:iterations r) :llm-calls (get-in r [:usage :llm-calls])
     :prompt-chars @prompt-chars
     :tool-calls (count @called)
     :flow-calls (count (filter #(str/starts-with? % "flow$") @called))
     :tools-used (frequencies @called)
     :terminated-by (:terminated-by r) :error (:error r)
     :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn- unit
  "What the two arms share, and therefore what the comparison pairs over: one
  task, at one rep, under one model. `-main` drives both arms from the same
  loop over the same task list, so every score on one side has a twin on the
  other."
  [r]
  [(:model r) (:rep r) (name (:task r))])

(defn- verdict
  "Is the flows arm better, beyond the noise? PAIRED over the shared units
  (`byj-gly.122`).

  It used to average each arm to a per-rep mean and run
  `evaluate/compare-passes` over those. That carries the between-task variance,
  and with three tasks that term dominates everything else: a task both arms
  fail and a task both arms pass contribute nothing but spread. Pairing removes
  it — see RFC-021 and divergence `comparison-is-paired-over-examples`, where
  the same mistake crowned a proposal that was one example ahead.

  A unit present on only one side is DROPPED, with the pair count reported, so
  a crashed run cannot silently mispair the two arms against each other. The
  per-rep means are still reported: they are what a reader compares by eye, but
  they are no longer what the test weighs."
  [rows]
  (let [arm (fn [flows?] (into {} (map (juxt unit :score))
                               (filter #(= flows? (:flows? %)) rows)))
        a (arm true) b (arm false)
        shared (vec (sort (filter #(contains? b %) (keys a))))
        xa (mapv a shared) xb (mapv b shared)
        per-rep (fn [flows?] (->> rows (filter #(= flows? (:flows? %))) (group-by :rep)
                                  (sort-by key)
                                  (mapv (fn [[_ rs]] (/ (reduce + (map :score rs)) (double (count rs)))))))
        stat (fn [flows? xs]
               (let [ms (per-rep flows?)
                     k (count ms)
                     mean (if (empty? xs) 0.0 (/ (reduce + xs) (double (count xs))))
                     var (when (> k 1)
                           (let [m (/ (reduce + ms) (double k))]
                             (/ (reduce + (map #(let [d (- % m)] (* d d)) ms)) (dec k))))
                     sd (some-> var Math/sqrt)]
                 {:score mean :completed-repeats k :repeat-scores ms
                  :stddev sd :stderr (when sd (/ sd (Math/sqrt k)))
                  :example-scores xs}))
        with (stat true xa)
        without (stat false xb)]
    {:with with :without without
     :pairs shared
     :unpaired (vec (sort (concat (remove #(contains? b %) (keys a))
                                  (remove #(contains? a %) (keys b)))))
     :comparison (ev/compare-scores with without)}))

(defn -main [& models]
  (let [out (io/file "target/flow-authoring-eval/results.edn")
        only (some-> (System/getenv "TASKS") (str/split #",") set)
        reps (or (some-> (System/getenv "REPS") parse-long) 1)
        rows (atom [])]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["sonnet"])
            rep (range reps)
            task tasks
            :when (or (nil? only) (contains? only (name (:id task))))
            flows? [false true]]
      (let [r (assoc (run-task model flows? task) :rep rep)]
        (swap! rows conj r)
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "r%d %-7s %-7s %-8s score=%-5s iters=%s calls=%s flow=%d prompt-chars=%d %.0fs%s"
                         rep model (name (:task r)) (if flows? "with" "without")
                         (format "%.2f" (:score r)) (:iterations r) (:llm-calls r) (:flow-calls r)
                         (:prompt-chars r) (/ (:elapsed-ms r) 1000.0)
                         (if (:error r) (str " ERROR " (:error r)) "")))
        (flush)))
    (let [{:keys [with without comparison pairs unpaired] :as v} (verdict @rows)]
      (println)
      (println (format "with    %.3f  per-rep %s" (:score with) (pr-str (:repeat-scores with))))
      (println (format "without %.3f  per-rep %s" (:score without) (pr-str (:repeat-scores without))))
      (println (format "paired over %d shared units (task x rep x model)" (count pairs)))
      (when (seq unpaired)
        (println (format "  %d unit(s) ran on one arm only and were DROPPED: %s"
                         (count unpaired) (pr-str unpaired))))
      (println (format "diff %+.3f, margin %s, tested? %s → %s"
                       (:diff comparison)
                       (if-let [m (:margin comparison)] (format "%.3f" m) "—")
                       (:tested? comparison)
                       (cond
                         ;; better? without tested? is arithmetic, and saying
                         ;; "helps" for it is how a null gets written up as a
                         ;; finding (byj-gly.57)
                         (not (:tested? comparison))
                         (format "UNTESTED — %s is the raw direction, nothing weighed it"
                                 (if (:better? comparison) "flow$* ahead" "flow$* behind"))
                         (:better? comparison) "flow$* helps"
                         :else "NO detectable difference")))
      (spit out (str (pr-str {:verdict v}) "\n") :append true))
    (System/exit 0)))
