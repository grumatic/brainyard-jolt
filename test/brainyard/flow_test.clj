(ns brainyard.flow-test
  "Building and checking a workflow (RFC-022 stage 1).

  Two things are asserted here. That a leaf can be a TOOL — the node kind that
  makes a workflow authorable without a rebuild — and that `check` reports
  every problem a composition has at once, including the ones that are silent
  at run time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.agents :as agents]
            [brainyard.bt :as bt]
            [brainyard.flow :as flow]
            [brainyard.json]
            [brainyard.predictor :as predictor]
            [brainyard.session :as session]
            [brainyard.tools :as tools]))

(defn- scratch
  "A project dir of this test's own — a turn under the git root leaves a
  trajectory in the developer's repository (`byj-gly.88`)."
  []
  (let [d (clojure.java.io/file "target" "flow-scratch")] (.mkdirs d) (.getPath d)))

(defn- fresh-dir [nm]
  (let [d (io/file "target/flow-test" nm)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn- ctx-with-tool
  "A ctx whose registry holds one extra tool."
  [dir tool]
  (let [ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})]
    (swap! (:tools ctx) assoc (:name tool) tool)
    (assoc ctx :st-memory (atom {}))))

(def ^:private classify
  {:name "user$tool$classify"
   :kind :composite
   :description "Classify a report."
   :input-schema [:map [:text [:string {:desc "The report"}]]]
   :output-schema "{:severity string}"
   :effects #{}
   :fn (fn [{:keys [text]} _ctx]
         (if (str/blank? (str text))
           {:error-message "text is required"}
           {:severity (if (str/includes? (str text) "disk") "high" "low")}))})

;; --- the leaf an agent can implement ---------------------------------------------

(deftest a-leaf-can-be-a-tool
  (let [dir (fresh-dir "tool-leaf")
        ctx (ctx-with-tool dir classify)
        mem (:st-memory ctx)]
    (testing "a tool runs as a tree node, reading and writing st-memory by :in/:out"
      (reset! mem {:question "the disk is full"})
      (is (= :success (bt/tick ctx [:action {:id :t/classify :fn :tool/run
                                             :tool "user$tool$classify"
                                             :in {:text :question}
                                             :out {:severity :severity}}])))
      (is (= "high" (:severity @mem)))
      (is (= "the disk is full" (:question @mem)) "the input key is left alone"))

    (testing "the call is in the turn's record, as a model's own call is (byj-gly.322)"
      (let [[it & more] (:trajectory-iterations @mem)]
        (is (nil? more))
        (is (= "tool" (:channel it)))
        (is (= [["user$tool$classify" {:text "the disk is full"} false]]
               (map (juxt :tool-name :tool-args :tool-error?) (:tool-results it))))
        (is (str/includes? (:tool-result (first (:tool-results it))) "high")))
      (is (nil? (:iterations @mem)) "the record only: not what a prompt shows the model"))

    (testing "a failed call is recorded failed"
      (reset! mem {:question "  "})
      (bt/tick ctx [:action {:id :t/classify :fn :tool/run :tool "user$tool$classify"
                             :in {:text :question} :on-error :success}])
      (is (true? (-> @mem :trajectory-iterations first :tool-results first :tool-error?))))

    (testing "a failing tool fails the node, so a :fallback can catch it"
      (reset! mem {:question "  "})
      (is (= :failure (bt/tick ctx [:action {:id :t/classify :fn :tool/run
                                             :tool "user$tool$classify"
                                             :in {:text :question}
                                             :out {:severity :severity}}]))))

    (testing "unless the node says a failure is acceptable"
      (reset! mem {:question "  "})
      (is (= :success (bt/tick ctx [:action {:id :t/classify :fn :tool/run
                                             :tool "user$tool$classify"
                                             :in {:text :question} :on-error :success}]))))

    (testing "a tool that is not bound fails the node rather than throwing"
      (is (= :failure (bt/tick ctx [:action {:id :t/nope :fn :tool/run :tool "user$tool$ghost"}]))))

    (testing "values are passed through as data, not stringified as a predictor's are"
      (let [seen (atom nil)
            ctx (ctx-with-tool dir {:name "user$tool$echo" :kind :composite
                                    :input-schema [:map [:v :map]] :effects #{}
                                    :fn (fn [{:keys [v]} _] (reset! seen v) {:ok true})})]
        (reset! (:st-memory ctx) {:payload {:a [1 2]}})
        (bt/tick ctx [:action {:id :t/echo :fn :tool/run :tool "user$tool$echo"
                               :in {:v :payload}}])
        (is (= {:a [1 2]} @seen))))))

(deftest a-tool-leaf-is-gated-like-any-other-tool-call
  ;; the tree must not be a way around the permission stack (RFC-012)
  (let [dir (fresh-dir "gated")
        ctx (-> (agent/make-ctx {:config {:working-dir dir :project-dir dir
                                          :tool-deny-tools ["user$tool$classify"]}})
                (assoc :st-memory (atom {:question "the disk is full"})))]
    (swap! (:tools ctx) assoc (:name classify) classify)
    (is (= :failure (bt/tick ctx [:action {:id :t/classify :fn :tool/run
                                           :tool "user$tool$classify"
                                           :in {:text :question}
                                           :out {:severity :severity}}]))
        "a denied tool fails the node")
    (is (nil? (:severity @(:st-memory ctx))) "and writes nothing")))

;; --- check -------------------------------------------------------------------------

(deftest the-loops-this-repository-ships-are-sound
  ;; a regression guard on the trees themselves: they are behaviour, edited by
  ;; hand, and nothing else would notice a typo in one
  (let [roots (predictor/default-roots "." "predictors")]
    (is (nil? (flow/check (agent/load-tree agent/react-loop)
                          {:registry agent/registry :predictor-roots roots
                           ;; a turn is handed its question before the tree runs
                           :initial #{:question}}))
        "the ReAct loop")
    (is (nil? (flow/check (agent/load-tree session/session-loop)
                          {:registry (merge agent/registry session/registry)
                           :predictor-roots roots}))
        "the session loop")))

(def ^:private broken
  '[:sequence {:id :bad/turn}
    [:action {:id :bad/init :fn :react/init}]
    [:action {:id :bad/init :fn :react/nope}]
    [:action {:id :bad/hook :fn :hooks/fire :event :agent.nonexistent/pre}]
    [:action {:id :bad/think :fn :react/think-act
              :predictor "react/think-act" :zones [:agent-core :typo-zone]}]
    [:action {:id :bad/pred :fn :predictor/run :predictor "react/think-act"
              :in {:nosuchinput :question} :out {:x :nosuchoutput}}]
    [:action {:id :bad/tool :fn :tool/run :tool "user$tool$ghost" :in {:a :question}}]
    [:condition {:id :bad/cond}]
    [:repeat {:id :bad/spin} [:action {:id :bad/x :fn :react/init}]]
    [:action {:id :bad/cfg :fn :context/enforce :every-n :config/max-iteratons}]])

(defn- problems-for [node ps] (map :problem (filter #(= node (:node %)) ps)))

(deftest check-reports-every-problem-at-once
  (let [ps (flow/check broken {:registry agent/registry
                               :predictor-roots (predictor/default-roots "." "predictors")})
        has? (fn [node re] (some #(re-find re %) (problems-for node ps)))]
    (is (= 11 (count ps)) "one round trip, not eleven")

    (testing "the problems that WOULD have failed loudly"
      (is (has? :bad/init #"unregistered fn :react/nope"))
      (is (has? :bad/tool #"unknown tool"))
      (is (has? :bad/cond #"names no :fn")))

    (testing "the problems that would have been silent — the reason this exists"
      (is (has? :bad/cfg #"unknown config key :config/max-iteratons")
          "a mistyped config key reads nil and the loop quietly misbehaves")
      (is (has? :bad/think #"unknown zone :typo-zone")
          "a mistyped zone sends no text and the model merely does worse")
      (is (has? :bad/hook #"unknown hook event")
          "a hook that never fires looks like a hook that passed")
      (is (has? :bad/spin #"neither :max-n nor :until")
          "an unbounded repeat is a hang, not an error"))

    (testing "a wiring mistake names what the predictor actually declares"
      (is (has? :bad/pred #"no input :nosuchinput"))
      (is (has? :bad/pred #"no output :nosuchoutput"))
      (is (some #(str/includes? % ":question") (problems-for :bad/pred ps))))

    (testing "a duplicate id is reported once, against the id"
      (is (has? :bad/init #"duplicate node id")))

    (testing "an invented node kind is caught before the interpreter throws on it"
      ;; byj-gly.65, found by the byj-gly.63 eval: asked to add a tool node, a
      ;; live model wrote [:tool {:tool :classify …}], inventing a kind. check
      ;; reported the unknown tool and nothing else, so it passed a tree that
      ;; bt/tick throws on.
      (let [ps (flow/check '[:sequence {} [:tool {:id :x :tool :classify}]]
                           {:registry agent/registry})
            for-x (map :problem (filter #(= :x (:node %)) ps))]
        (is (some #(re-find #"unknown node kind :tool" %) for-x))
        (is (some #(re-find #"must be the tool's name as a string" %) for-x)
            "and the other half of the same mistake")
        (is (thrown-with-msg? Exception #"unknown node kind"
                              (bt/tick {:st-memory (atom {}) :registry {}} [:tool {:id :x}]))
            "non-vacuous: this is what it would have done at run time")))

    (testing "and the memory chain says plainly that it could not be checked"
      ;; an unannotated leaf's writes are unknown, so a key it provides would
      ;; look unwritten — better to say so than to report a problem that is not one
      (is (has? :tree #"declare no :reads/:writes")))))

;; --- the memory chain ----------------------------------------------------------------

(deftest the-loops-this-repository-ships-have-a-sound-memory-chain
  (testing "every key a node reads is written by something that always runs first"
    (is (empty? (flow/check-memory-chain (agent/load-tree agent/react-loop)
                                         agent/registry #{:question})))
    (is (empty? (flow/check-memory-chain (agent/load-tree session/session-loop)
                                         (merge agent/registry session/registry) #{}))))
  (testing "non-vacuously: the analysis does find a mistake in the same trees"
    ;; drop the node that seeds the blackboard and the rest of the loop is unfed
    (let [tree (agent/load-tree agent/react-loop)
          without-init (into [(first tree) (second tree)]
                             (remove #(= :react/init (:id (second %))) (drop 2 tree)))]
      (is (seq (flow/check-memory-chain without-init agent/registry #{:question}))))))

(deftest the-chain-tells-a-typo-from-an-ordering-mistake
  (let [chain #(flow/check-memory-chain % agent/registry #{})
        problems #(map :problem (chain %))]
    (testing "a key nothing writes is a typo, and says so"
      (is (some #(re-find #"nothing in this tree writes it" %)
                (problems '[:sequence {} [:action {:id :a :fn :react/init}]
                            [:action {:id :b :fn :predictor/run :in {:q :quesion}}]]))))

    (testing "a key written only by a LATER sibling is an ordering mistake, and says THAT"
      (is (some #(re-find #"nothing guarantees it here" %)
                (problems '[:sequence {} [:action {:id :a :fn :react/answer-non-blank?}]
                            [:action {:id :b :fn :react/init}]]))))

    (testing "a fallback sibling's write is not guaranteed — it ran only if the other failed"
      (is (seq (chain '[:sequence {} [:fallback {} [:action {:id :a :fn :react/init}]
                                      [:action {:id :b :fn :hooks/fire}]]
                        [:action {:id :c :fn :react/answer-non-blank?}]]))))

    (testing "unless EVERY branch writes it, in which case one of them did"
      (is (empty? (flow/check-memory-chain
                   '[:sequence {} [:fallback {} [:action {:id :a :fn :react/init}]
                                   [:action {:id :b :fn :react/ensure-answer}]]
                     [:action {:id :c :fn :react/answer-non-blank?}]]
                   agent/registry #{:terminated :iterations}))))

    (testing "a :scope is a barrier — its child starts from :in alone"
      (is (seq (chain '[:sequence {} [:action {:id :a :fn :react/init}]
                        [:scope {:in {} :out {}}
                         [:action {:id :b :fn :react/answer-non-blank?}]]]))))

    (testing "a :repeat body may run zero times, so its writes are not guaranteed after it"
      (is (seq (chain '[:sequence {} [:repeat {:max-n 3} [:action {:id :a :fn :react/init}]]
                        [:action {:id :b :fn :react/answer-non-blank?}]]))))))

(deftest a-contract-travels-with-the-fn-and-the-node-that-parameterizes-it
  (testing "the fixed half is metadata on the fn, reached through the registry's var"
    (is (contains? (:writes (flow/contract agent/registry [:action {:fn :react/init}])) :answer))
    (is (= #{:question :iterations} (:reads (flow/contract agent/registry [:action {:fn :react/think-act}])))))

  (testing "a generic leaf is parameterized by its node, so its contract is too"
    (let [c (flow/contract agent/registry
                           [:action {:fn :tool/run :tool "x" :in {:text :question} :out {:severity :severity}}])]
      (is (= #{:question} (:reads c)))
      (is (= #{:severity} (:writes c)))))

  (testing "a hook reads what its payload names, and a :config/ value is not a read"
    (is (= #{:question}
           (:reads (flow/contract agent/registry
                                  [:action {:fn :hooks/fire
                                            :payload {:input :question
                                                      :max :config/max-iterations}}]))))))

(deftest a-declared-write-cannot-quietly-drift
  ;; the mechanism that stops :writes becoming a comment: run a real turn with
  ;; the observer bound and assert no leaf wrote something it did not declare
  (let [reg (merge agent/registry session/registry)
        [violations obs] (flow/write-observer reg)
        answer (fn [_] (brainyard.json/write-str {:reasoning "r" :tool-calls [] :answer "hi"}))]
    (binding [bt/*leaf-observer* obs]
      (agent/ask "a question" {:lm {:provider :mock :format :mock :responses answer}
                               ;; its own project (`byj-gly.88`)
                               :config {:working-dir "." :max-iterations 2
                                        :project-dir (scratch)}}))
    (is (= [] @violations)
        (str "leaves wrote st-memory keys their :writes does not declare: " (pr-str @violations)))))

(deftest a-tool-leaf-is-checked-against-the-tool-it-names
  (let [dir (fresh-dir "check-tool")
        ctx (ctx-with-tool dir classify)
        check #(flow/check % (flow/ctx-opts ctx))]
    (is (nil? (check [:action {:id :t/ok :fn :tool/run :tool "user$tool$classify"
                               :in {:text :question}}])))
    (is (some #(re-find #"no param :txt" (:problem %))
              (check [:action {:id :t/typo :fn :tool/run :tool "user$tool$classify"
                               :in {:txt :question}}]))
        "a mistyped argument is silently dropped at run time")
    (testing "a prose :output-schema cannot say what a result holds, so :out goes unchecked"
      (is (nil? (check [:action {:id :t/any :fn :tool/run :tool "user$tool$classify"
                                 :in {:text :question} :out {:x :anything}}]))))
    (testing "a malli :output-schema says it, and a mistyped :out is named (byj-gly.201)"
      (let [typed (assoc classify :name "user$tool$typed"
                         :output-schema [:or [:map [:severity :string]] [:map [:error-message :string]]])
            ctx (ctx-with-tool dir typed)
            check #(flow/check % (flow/ctx-opts ctx))]
        (is (nil? (check [:action {:id :t/ok2 :fn :tool/run :tool "user$tool$typed"
                                   :in {:text :question} :out {:sev :severity :err :error-message}}]))
            "a key from any branch is one a result may have")
        (is (some #(re-find #"returns no key :sevrity — its :output-schema has :error-message, :severity" (:problem %))
                  (check [:action {:id :t/typo2 :fn :tool/run :tool "user$tool$typed"
                                   :in {:text :question} :out {:sev :sevrity}}]))
            "a mistyped result key would otherwise write nil, silently")
        (is (some #(re-find #"returns no key :contents" (:problem %))
                  (check [:action {:id :t/rf :fn :tool/run :tool "read-file"
                                   :in {:path :p} :out {:c :contents}}]))
            "a built-in tool's schema is used too")
        (is (nil? (check [:action {:id :t/lt :fn :tool/run :tool "list-tools" :out {:x :whatever}}]))
            "and one with a branch that is not a map cannot say, so says nothing")))))

(deftest an-unloadable-tree-is-itself-the-problem
  (let [r (flow/check-tree-name "no/such/tree.edn" {:registry agent/registry})]
    (is (= :tree (:node (first (:problems r)))))
    (is (str/includes? (:problem (first (:problems r))) "not found"))))

;; --- the command --------------------------------------------------------------------

(deftest flow-check-is-a-command
  (let [dir (fresh-dir "cmd")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        call (fn [args] (tools/call-tool (get @(:tools ctx) "flow$check") args ctx))]
    (testing "it checks the bundled loop and counts its nodes"
      (let [r (call {:tree agent/react-loop})]
        (is (true? (:ok r)))
        (is (= [] (:problems r)))
        (is (pos? (:nodes r)))))
    (testing "and reports a tree it cannot load rather than throwing"
      (is (false? (:ok (call {:tree "nope.edn"})))))
    (testing ":flows? false removes it"
      (let [ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir :flows? false}})]
        (is (nil? (get @(:tools ctx) "flow$check")))))))

;; --- the helpers check stands on ------------------------------------------------------

(deftest bt-helpers
  (is (= 4 (count (bt/nodes [:sequence {} [:action {}] [:fallback {} [:action {}]]]))))
  (is (= :a/b (bt/node-id [:action {:id :a/b}])))
  (is (= :action-anonymous (bt/node-id [:action {}])) "an unnamed node can still be located")
  (is (= #{:config/max-iterations :config/x}
         (flow/config-refs {:max-n :config/max-iterations
                            :payload {:deep {:k :config/x}}
                            :other :not-config}))
      "config refs are found however deeply they are nested"))

;; --- authoring one node at a time (stage 3) ---------------------------------------

(def ^:private triage
  "A workflow with one node deliberately unimplemented: `:triage/classify`
  names a tool nobody has written yet."
  '[:sequence {:id :triage/turn}
    [:action {:id :triage/init :fn :react/init}]
    [:action {:id :triage/classify :fn :tool/run :tool "user$tool$classify"
              :in {:text :question} :out {:severity :severity}}]
    [:action {:id :triage/answer :fn :predictor/run :predictor "react/think-act"
              :in {:question :severity} :out {:answer :answer}}]
    [:action {:id :triage/stamp :fn :react/stamp-answer}]])

(defn- triage-opts [ctx]
  (assoc (flow/ctx-opts ctx) :registry agent/registry))

(deftest status-says-what-is-left-to-build
  (let [dir (fresh-dir "status")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        st (flow/status triage (triage-opts ctx))]
    (is (= 4 (:nodes st)))
    (is (= 1 (:missing st)) "the tool nobody has written yet")
    (is (= 3 (:ready st)))
    (is (= 0 (:failing st)))
    (let [row (first (filter #(= :triage/classify (:node %)) (:detail st)))]
      (is (= "missing" (:status row)))
      (is (= :tool (:kind row)))
      (is (= "user$tool$classify" (:implements row))))
    (testing "and once the tool exists, nothing is missing — the loop terminates"
      (swap! (:tools ctx) assoc (:name classify) classify)
      (is (= 0 (:missing (flow/status triage (triage-opts ctx)))))
      (is (= 4 (:ready (flow/status triage (triage-opts ctx))))))))

(deftest a-brief-is-a-contract-taken-from-the-neighbours
  (let [dir (fresh-dir "brief")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        b (flow/brief triage :triage/classify (assoc (triage-opts ctx) :initial #{:question}))]
    (is (= :tool (:kind b)))
    (is (= "missing" (:status b)))

    (testing "what it must read, and which node actually supplies it"
      (let [r (first (filter #(= :question (:key %)) (:reads b)))]
        (is (true? (:guaranteed r)))
        (is (= [":initial"] (:from r)) "the caller seeds the question before the tree runs")))

    (testing "what it must write, and who downstream is waiting for it"
      (let [w (first (filter #(= :severity (:key %)) (:writes b)))]
        (is (= [:triage/answer] (:read-by w))
            "this is the whole specification: produce :severity for :triage/answer")
        (is (false? (:unused w)))))

    (testing "a key nothing reads is reported, so a node doing too much is visible"
      (let [b (flow/brief '[:sequence {} [:action {:id :a :fn :react/init}]]
                          :a (triage-opts ctx))]
        (is (some :unused (:writes b)))))

    (testing "an unknown node is an error, not an empty brief"
      (is (:error (flow/brief triage :triage/nope (triage-opts ctx)))))))

(deftest probe-runs-one-node-without-the-loop-that-reaches-it
  (let [dir (fresh-dir "probe")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})]
    (swap! (:tools ctx) assoc (:name classify) classify)
    (testing "seed only what the brief said it reads; get back what it wrote"
      (let [r (flow/probe triage :triage/classify {:question "the disk is full"} ctx)]
        (is (= "success" (:status r)))
        (is (= {:severity "high"} (:wrote r)))))

    (testing "a node that fails says so, rather than throwing"
      (let [r (flow/probe triage :triage/classify {:question "  "} ctx)]
        (is (= "failure" (:status r)))))

    (testing "and an unknown node is an error"
      (is (:error (flow/probe triage :triage/nope {} ctx))))))

(deftest the-authoring-loop-closes
  ;; the point of the stage: status says what is missing, a brief says what that
  ;; node must do, probe says whether the implementation does it, status goes green
  (let [dir (fresh-dir "loop")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        opts #(assoc (triage-opts ctx) :initial #{:question})]

    (let [st (flow/status triage (opts))
          todo (first (filter #(= "missing" (:status %)) (:detail st)))]
      (is (some? todo) "1. status names the node to work on")

      (let [b (flow/brief triage (:node todo) (opts))
            needs (set (map :key (:reads b)))
            gives (set (map :key (remove :unused (:writes b))))]
        (is (= #{:question} needs) "2. the brief says what it may read")
        (is (= #{:severity} gives) "   and what someone downstream needs from it")

        ;; 3. implement exactly that contract, and nothing else
        (swap! (:tools ctx) assoc (:name classify) classify)

        (let [r (flow/probe triage (:node todo) {(first needs) "the disk is full"} ctx)]
          (is (= "success" (:status r)) "4. probe runs it alone")
          (is (= gives (set (keys (:wrote r))))
              "   and what it wrote is exactly what the brief asked for"))

        (let [st (flow/status triage (opts))]
          (is (= 0 (:missing st)) "5. nothing is missing")
          (is (= 0 (:failing st)))
          (is (nil? (flow/check triage (opts))) "   and the whole tree checks clean"))))))

(deftest the-stage-3-commands
  (let [dir (fresh-dir "cmds")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))]
    (is (= #{"flow$check" "flow$status" "flow$brief" "flow$probe" "flow$patch" "flow$format"}
           (set (filter #(str/starts-with? % "flow$") (keys @(:tools ctx))))))
    (testing "status and brief work over the bundled loop"
      (is (= 0 (:missing (call "flow$status" {:tree agent/react-loop}))))
      (is (= :react/think-act (:node (call "flow$brief" {:tree agent/react-loop
                                                         :node "react/think-act"})))))
    (testing "a node id may be written with or without its colon"
      (is (= :react/think-act (:node (call "flow$brief" {:tree agent/react-loop
                                                         :node ":react/think-act"})))))
    (testing "probe reports what a node wrote"
      (let [r (call "flow$probe" {:tree agent/react-loop :node "react/init"})]
        (is (= "success" (:status r)))
        (is (contains? (:wrote r) :iterations))))
    (testing "and a tree that will not load is an error, not a crash"
      (is (:error (call "flow$status" {:tree "nope.edn"})))
      (is (:error (call "flow$brief" {:tree "nope.edn" :node "x"}))))))

;; --- patching (stage 4) --------------------------------------------------------------

(defn- tree-file! [dir content]
  (let [f (io/file dir "loop.edn")]
    (spit f (pr-str content))
    (.getPath f)))

(def ^:private patchable
  '[:sequence {:id :p/turn}
    [:action {:id :p/init :fn :react/init}]
    [:action {:id :p/stamp :fn :react/stamp-answer}]])

(defn- patch-opts [ctx] (assoc (flow/ctx-opts ctx) :registry agent/registry))

(deftest a-patch-names-the-tree-it-was-written-against
  (let [dir (fresh-dir "patch-hash")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        path (tree-file! dir patchable)
        h (flow/tree-hash patchable)]
    (testing "an edit computed from a stale read is refused, and says what to do"
      (let [r (flow/patch path "0000deadbeef" [] (patch-opts ctx))]
        (is (str/includes? (:error r) "hash mismatch"))
        (is (= h (:hash r)) "and hands back the hash it actually has")))

    (testing "the guard may be skipped deliberately"
      (is (:applied (flow/patch path nil [{:op :set-opt :node :p/turn :key :note :value "x"}]
                                (patch-opts ctx)))))

    (testing "and the hash changes when the tree does"
      (is (not= h (flow/tree-hash (read-string (slurp path))))))))

(deftest a-patch-that-would-break-the-tree-is-refused
  (let [dir (fresh-dir "patch-check")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        path (tree-file! dir patchable)
        before (slurp path)
        r (flow/patch path (flow/tree-hash patchable)
                      [{:op :add-node :parent :p/turn
                        :node [:action {:id :p/bad :fn :react/nope}]}]
                      (patch-opts ctx))]
    (is (str/includes? (:error r) "nothing was written"))
    (is (some #(re-find #"unregistered fn" (:problem %)) (:problems r))
        "the problems it would have caused come back with it")
    (is (= before (slurp path)) "the file on disk is untouched — a tree on disk always runs")))

(deftest the-ops
  (let [dir (fresh-dir "ops")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        ids #(mapv bt/node-id (filter #(contains? #{:action :condition} (first %))
                                      (bt/nodes (read-string (slurp %)))))
        fresh (fn [] (tree-file! dir patchable))]

    (testing "add-node places a child where it was asked to"
      (let [path (fresh)]
        (flow/patch path nil [{:op :add-node :parent :p/turn :after :p/init
                               :node [:action {:id :p/mid :fn :react/inc-iter}]}]
                    (patch-opts ctx))
        (is (= [:p/init :p/mid :p/stamp] (ids path))))
      (let [path (fresh)]
        (flow/patch path nil [{:op :add-node :parent :p/turn :after :first
                               :node [:action {:id :p/first :fn :hooks/fire
                                               :event :agent.ask/pre}]}]
                    (patch-opts ctx))
        (is (= [:p/first :p/init :p/stamp] (ids path)))))

    (testing "and a node placed where the blackboard cannot feed it is refused"
      ;; :react/inc-iter reads :iteration-count, which :p/init writes — putting
      ;; it first breaks the chain, and the patch says so instead of writing it
      (let [path (fresh)
            r (flow/patch path nil [{:op :add-node :parent :p/turn :after :first
                                     :node [:action {:id :p/early :fn :react/inc-iter}]}]
                          (patch-opts ctx))]
        (is (some #(re-find #"iteration-count" (:problem %)) (:problems r)))
        (is (= [:p/init :p/stamp] (ids path)))))

    (testing "remove-node and rename-node"
      (let [path (fresh)]
        (flow/patch path nil [{:op :remove-node :node :p/stamp}] (patch-opts ctx))
        (is (= [:p/init] (ids path))))
      (let [path (fresh)]
        (flow/patch path nil [{:op :rename-node :node :p/stamp :to :p/final}] (patch-opts ctx))
        (is (= [:p/init :p/final] (ids path)))))

    (testing "set-opt writes an opt, and a nil value removes one"
      (let [path (fresh)]
        (flow/patch path nil [{:op :set-opt :node :p/turn :key :note :value "hello"}] (patch-opts ctx))
        (is (= "hello" (:note (second (read-string (slurp path))))))
        (flow/patch path nil [{:op :set-opt :node :p/turn :key :note :value nil}] (patch-opts ctx))
        (is (nil? (:note (second (read-string (slurp path))))))))

    (testing "an op naming a node that is not there is refused, and writes nothing"
      (let [path (fresh)
            before (slurp path)]
        (is (str/includes? (:error (flow/patch path nil [{:op :remove-node :node :p/ghost}]
                                               (patch-opts ctx)))
                           "no node :p/ghost"))
        (is (= before (slurp path)))))

    (testing "an unknown op names the ops that exist"
      (is (str/includes? (:error (flow/patch (fresh) nil [{:op :frobnicate}] (patch-opts ctx)))
                         "add-node")))))

(deftest patching-edits-the-source-not-its-expansion
  ;; an :include is spliced at load time (RFC-002). Patching the expanded tree
  ;; would inline the included subtree into the file and silently unshare it.
  (let [dir (fresh-dir "include")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        inner (io/file dir "inner.edn")
        _ (spit inner (pr-str '[:action {:id :i/stamp :fn :react/stamp-answer}]))
        outer [':sequence {:id :o/turn}
               '[:action {:id :o/init :fn :react/init}]
               [:include {:tree (.getPath inner)}]]
        path (tree-file! dir outer)]
    (is (:applied (flow/patch path (flow/tree-hash outer)
                              [{:op :set-opt :node :o/turn :key :note :value "x"}]
                              (patch-opts ctx))))
    (let [written (read-string (slurp path))]
      (is (= :include (first (nth written 3))) "the include is still an include")
      (is (not (str/includes? (slurp path) ":i/stamp")) "its body was not inlined"))))

(deftest the-hash-check-reports-is-the-one-a-patch-guards
  ;; they differ for any tree with an :include: one is the source, one the expansion
  (let [dir (fresh-dir "hash-agree")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        inner (io/file dir "inner.edn")
        _ (spit inner (pr-str '[:action {:id :i/stamp :fn :react/stamp-answer}]))
        outer [':sequence {:id :o/turn}
               '[:action {:id :o/init :fn :react/init}]
               [:include {:tree (.getPath inner)}]]
        path (tree-file! dir outer)
        {:keys [hash tree]} (flow/check-tree-name path (patch-opts ctx))]
    (is (= hash (flow/tree-hash outer)) "check reports the source hash")
    (is (not= hash (flow/tree-hash tree)) "which is NOT the expansion's")
    (is (:applied (flow/patch path hash [{:op :set-opt :node :o/turn :key :n :value 1}]
                              (patch-opts ctx)))
        "so the hash check hands back is the one a patch accepts")))

(deftest patch-is-a-command-and-a-round-trip
  (let [dir (fresh-dir "patch-cmd")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))
        path (tree-file! dir patchable)]
    (testing "read the hash, patch against it, read it back — what an agent does"
      (let [h (:hash (call "flow$check" {:tree path}))
            r (call "flow$patch" {:path path :expect-hash h
                                  :ops [{:op "add-node" :parent :p/turn :after :p/init
                                         :node [:action {:id :p/mid :fn :react/inc-iter}]}]})]
        (is (= 1 (:applied r)))
        (is (= (:hash r) (:hash (call "flow$check" {:tree path}))))
        (is (true? (:ok (call "flow$check" {:tree path}))))))
    (testing "and the same patch a second time is refused, its hash now stale"
      (is (str/includes? (:error (call "flow$patch" {:path path :expect-hash "0000deadbeef" :ops []}))
                         "hash mismatch")))
    (is (str/includes? (str (:error-message (call "flow$patch" {:path path :ops "nope"}))) "`ops.0` should be an object"))))

;; --- an agent that names its own loop -------------------------------------------------

(deftest an-agent-can-bring-its-own-loop-and-predictors
  (let [dir (fresh-dir "agent-loop")
        adir (io/file dir ".brainyard" "agents" "triage")
        _ (.mkdirs (io/file adir "predictors" "user"))
        _ (spit (io/file adir "loop.edn")
                (pr-str '[:sequence {:id :tri/turn}
                          [:action {:id :tri/init :fn :react/init}]
                          [:action {:id :tri/stamp :fn :react/stamp-answer}]]))
        _ (spit (io/file adir "predictors" "user" "classify.edn")
                (pr-str {:id "user/classify" :strategy :predict
                         :instructions "Classify."
                         :inputs [[:text {:desc "t"} :string]]
                         :outputs [[:label {:desc "l"} :string]]}))
        _ (spit (io/file adir "agent.edn")
                (pr-str {:instruction "You triage." :tools ["bash"]
                         :loop "loop.edn" :config {:max-iterations 3}}))
        loaded (agents/load-agents {:agent-roots [(.getPath (io/file dir ".brainyard" "agents"))]
                                    :builtin-agents? false})
        triage (first (:agents loaded))]
    (is (empty? (:problems loaded)))
    (is (= "loop.edn" (:loop triage)))

    (testing "the child turn runs the agent's own tree, resolved beside agent.edn"
      (let [opts (#'agents/child-opts triage {:config {:working-dir dir :project-dir dir}})]
        (is (= :tri/turn (:id (second (:tree opts)))))
        (is (= 3 (:max-iterations (:config opts))) "and its own config")))

    (testing "and finds its own predictors first"
      (let [opts (#'agents/child-opts triage {:config {:working-dir dir :project-dir dir
                                                       :predictor-roots ["/elsewhere"]}})
            roots (:predictor-roots (:config opts))]
        (is (str/ends-with? (first roots) "triage/predictors"))
        (is (some? (predictor/load-predictor "user/classify" roots)))))

    (testing "a :loop that is not a string is a spec problem, reported with the others"
      (let [{:keys [problems]} (agents/parse-agent "bad" {:instruction "x" :loop 42})]
        (is (some #(str/includes? % ":loop") problems))))))

;; --- the format the doc teaches is the format the checker enforces -------------------

(deftest the-worked-example-in-the-format-doc-checks-clean
  ;; byj-gly.66. A format reference that drifts from the code teaches a model
  ;; to write trees this repository rejects — worse than no reference, because
  ;; it is believed. The doc's worked example is therefore checked here.
  (let [doc (slurp (io/resource "brainyard/prompts/loop-format.md"))
        blocks (map second (re-seq #"(?s)```clojure\n(.*?)```" doc))
        trees (for [b blocks
                    f (read-string (str "[" b "]"))
                    :when (and (vector? f)
                               (contains? #{:sequence :fallback :repeat :scope} (first f)))]
                f)
        ctx (agent/make-ctx {:config {:working-dir "." :project-dir "."}})]
    (is (seq trees) "the doc still contains a worked tree")
    (doseq [t trees]
      (is (nil? (flow/check t (assoc (flow/ctx-opts ctx) :initial #{:question})))
          (str "the documented example does not check clean: " (:id (second t)))))))

(deftest the-format-doc-is-pulled-not-pushed
  (let [dir (fresh-dir "format")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))]
    (testing "the doc is reachable on demand"
      (let [r (call "flow$format" {})]
        (is (str/includes? (:format r) "node kinds"))
        (is (str/includes? (:format r) ":tool/run"))))
    (testing "and costs the prompt one line until it is asked for"
      (let [zone (:session-context (:zones ctx))]
        (is (str/includes? zone "flow$format"))
        (is (not (str/includes? zone "seven node kinds"))
            "the doc itself is not in the prompt")))
    (testing "an invented node kind is pointed at it"
      (is (some #(str/includes? (:problem %) "flow$format")
                (flow/check '[:sequence {} [:tool {:id :x}]] {:registry agent/registry}))
          "the checker teaches at the moment the model errs"))))

(deftest a-tool-that-spent-money-says-so-and-the-turn-counts-it
  ;; byj-1wv.17: a tool's :usage-cost-usd joins the turn's usage cost — the
  ;; claude agent's delegation, whose hosting turn carried no usage — and
  ;; anything else in a result is not a cost
  (let [m (atom {:usage {:input-tokens 0 :output-tokens 0 :cost 0.25}})]
    (agent/account-tool-cost! m {:answer "x" :usage-cost-usd 0.5})
    (is (= 0.75 (get-in @m [:usage :cost])))
    (agent/account-tool-cost! m {:cost-usd 9.0 :usage-cost-usd 0})
    (agent/account-tool-cost! m {:usage-cost-usd "0.5"})
    (agent/account-tool-cost! m "not a map")
    (is (= 0.75 (get-in @m [:usage :cost])) "zero, a string, a cumulative figure under another name: not counted")
    (is (nil? (get-in @m [:usage :unpriced])))))
