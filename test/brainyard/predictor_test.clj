;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.predictor-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]))

(def tmp (str (System/getProperty "user.dir") "/target/predictor-test"))

(defn- write-edn! [path data]
  (let [f (io/file tmp path)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str data))
    (.getPath f)))

(defn- clean! []
  (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f)))

(def summarize-spec
  {:id "user/summarize"
   :strategy :predict
   :instructions "Summarize the text in one sentence."
   :inputs [[:text {:desc "Text to summarize"} :string]]
   :outputs [[:summary {:desc "One sentence"} :string]]})

(defn- mock-lm [f] {:provider :mock :format :mock :responses f})

;; --- specs --------------------------------------------------------------------------

(deftest spec-validation
  (is (nil? (predictor/validate-spec summarize-spec)))
  (let [problems (predictor/validate-spec
                  (assoc summarize-spec
                         :id "../escape"
                         :strategy :magic
                         :extra 1
                         :outputs [[:summary {:type "string" :desc "the old form"}]
                                   [:detail {:desc "d"} :no-such-type]]))]
    (is (some #(= "`id` is not a valid id" %) problems))
    (is (some #(= "`strategy` should be either :cot or :predict" %) problems))
    (is (some #(= "`extra` disallowed key" %) problems))
    (is (some #(= "`outputs.0` uses the retired {:type … :schema …} form — write [keyword {:desc …} schema]" %) problems)
        "a field is a malli map entry; the old {:type :schema} form is gone, and said so (byj-gly.203, .234)")
    (is (= 1 (count (filter #(str/starts-with? % "`outputs.0`") problems))) "one message for it, not two")
    (is (some #(= "`outputs.1` has a schema malli cannot compile" %) problems)))
  (is (= ["a predictor spec must be a map"] (predictor/validate-spec nil)))
  (testing "well-formed fields are then checked as a whole"
    (is (= ["`outputs` declares :reasoning, which is reserved — :strategy :cot adds it"]
           (predictor/validate-spec (assoc summarize-spec :outputs [[:reasoning :string]]))))
    (is (= ["`outputs` has a field declared twice"]
           (predictor/validate-spec (assoc summarize-spec :outputs [[:a :string] [:a :int]])))))
  (is (thrown-with-msg? Exception #"invalid predictor"
                        (predictor/compile-spec (dissoc summarize-spec :outputs)))))

(deftest loading
  (clean!)
  (let [roots [(str tmp "/project/predictors") (str tmp "/home/predictors")]]
    (testing "the bundled think-act predictor"
      (let [p (predictor/load-predictor "react/think-act" roots)]
        (is (= :builtin (:source p)))
        (is (= :cot (:strategy p)))
        (is (str/starts-with? (get-in p [:signature :instructions]) "You are a CoAct agent"))))
    (testing "user/ predictors come from disk, project root first"
      (write-edn! "home/predictors/user/summarize.edn" (assoc summarize-spec :description "home"))
      (write-edn! "project/predictors/user/summarize.edn" (assoc summarize-spec :description "project"))
      (is (= "project" (:description (predictor/load-predictor "user/summarize" roots)))))
    (testing "a built-in id can never be redefined from disk"
      (write-edn! "project/predictors/react/think-act.edn" (assoc summarize-spec :id "react/think-act"))
      (is (= :builtin (:source (predictor/load-predictor "react/think-act" roots))))
      (write-edn! "project/predictors/memory/other.edn" (assoc summarize-spec :id "memory/other"))
      (is (thrown-with-msg? Exception #"must use the user/ namespace"
                            (predictor/load-predictor "memory/other" roots))))
    (testing "a file must declare the id it is stored under"
      (write-edn! "project/predictors/user/liar.edn" summarize-spec)
      (is (thrown-with-msg? Exception #"declares :id"
                            (predictor/load-predictor "user/liar" roots))))))

;; --- params ----------------------------------------------------------------------------

(deftest params-resolution
  (clean!)
  (let [p (predictor/compile-spec (assoc summarize-spec :params {:instructions "default"}))
        roots [(str tmp "/project/programs") (str tmp "/home/programs")]
        resolve #(predictor/resolve-params p (merge {:params-roots roots} %))]
    (is (= {:params {:instructions "default"} :source :default} (resolve {})))
    (let [home (write-edn! "home/programs/user/summarize.edn" {:instructions "home"})]
      (is (= home (:source (resolve {})))))
    (let [project (write-edn! "project/programs/user/summarize.edn" {:field-descs {:text "project"}})]
      (testing "the first layer with a record wins WHOLE — no merge with lower layers"
        (is (= {:params {:field-descs {:text "project"}} :source project} (resolve {})))))
    (is (= :dynamic (:source (resolve {:params {"user/summarize" {:instructions "dyn"}}}))))
    (testing "an invalid params file is skipped and reported, not fatal"
      (write-edn! "project/programs/user/summarize.edn" {:instructions 42})
      (let [r (resolve {})]
        (is (str/ends-with? (:source r) "home/programs/user/summarize.edn"))
        (is (= ["`instructions` should be a string"] (:problems (first (:skipped r)))))))
    (is (thrown-with-msg? Exception #"invalid dynamic params"
                          (resolve {:params {"user/summarize" {:bogus 1}}})))))

(deftest params-shape-the-prompt
  (let [p (predictor/compile-spec summarize-spec)
        seen (atom nil)
        run #(predictor/run p {:text "long text"}
                            {:lm (mock-lm (fn [messages]
                                            (reset! seen messages)
                                            (json/write-str {:summary "short"})))
                             :params %})]
    (testing "a :predict predictor asks for no reasoning"
      (is (= {:predictor-id "user/summarize" :params-source nil
              :outputs {:summary "short"} :reasoning nil
              ;; which model the call actually ran on, after the node override
              ;; and the tier table had their say — without it a caller cannot
              ;; price the call, and a turn may span models (RFC-025)
              :model nil :provider :mock
              :usage {:input-tokens 0 :output-tokens 0}}
             (run nil)))
      (is (not (str/includes? (:content (first @seen)) "reasoning")))
      (is (str/includes? (:content (first @seen)) "Respond with a JSON object containing the output fields.")))
    (testing "instructions, field descriptions and demos all come from params"
      (run {"user/summarize" {:instructions "Be terse."
                              :field-descs {:text "The document"}
                              :demos [{:inputs {:text (apply str (repeat 3000 "x"))}
                                       :outputs {:summary "about x" :ignored 1}}]}})
      (let [system (:content (first @seen))]
        (is (str/includes? system "1. `text` (string): The document"))
        (is (str/includes? system "your objective is:\nBe terse."))
        (is (< (str/index-of system "Here are examples")
               (str/index-of system "In adhering to this structure")))
        (is (str/includes? system "Example 1\nInput:\ntext: xxx"))
        (is (str/includes? system "…[truncated]\nOutput:\n{\"summary\":\"about x\"}"))))))

(deftest a-predictor-names-its-own-reasoning
  ;; the reasoning of a judge and the thought a person watches are not written
  ;; for the same reader (byj-gly.280.1)
  (let [spec (assoc summarize-spec :id "user/summarize-cot" :strategy :cot)
        system (fn [p params]
                 (let [seen (atom nil)]
                   (predictor/run p {:text "t"}
                                  {:lm (mock-lm (fn [messages] (reset! seen messages)
                                                  (json/write-str {:reasoning "r" :summary "s"})))
                                   :params params})
                   (:content (first @seen))))
        field-line #(re-find #"1\. `reasoning` \(string\): [^\n]*" %)]
    (testing "by default, brainyard's"
      (is (= (str "1. `reasoning` (string): " brainyard.signature/reasoning-field-desc)
             (field-line (system (predictor/compile-spec spec) nil)))))
    (testing "a spec's own, as text or a resource, in the field list and the JSON schema"
      (let [p (predictor/compile-spec (assoc spec :reasoning {:desc "What is new, and why."}))
            s (system p nil)]
        (is (= "1. `reasoning` (string): What is new, and why." (field-line s)))
        (is (str/includes? s "\"description\":\"What is new, and why.\"")))
      (is (= (str/trim (slurp (io/resource "brainyard/prompts/answer-format.md")))
             (get-in (predictor/compile-spec (assoc spec :reasoning {:desc {:resource "brainyard/prompts/answer-format.md"}}))
                     [:signature :reasoning-desc]))))
    (testing "and a params record's :field-descs reaches it, highest"
      (is (= "1. `reasoning` (string): Tuned."
             (field-line (system (predictor/compile-spec (assoc spec :reasoning {:desc "Spec's."}))
                                 {"user/summarize-cot" {:field-descs {:reasoning "Tuned."}}})))))
    (testing "a :predict predictor still asks for none, and a bad one is refused"
      (is (not (str/includes? (system (predictor/compile-spec (assoc summarize-spec :reasoning {:desc "x"})) nil) "reasoning")))
      (is (seq (predictor/validate-spec (assoc spec :reasoning {:text "x"})))))))

(deftest the-loops-thought-is-for-the-person-watching
  ;; byj-gly.280.2: think-act asks for a thought a person reads; the judges
  ;; keep brainyard's reasoning, written for the model
  (let [desc #(get-in (predictor/load-predictor % []) [:signature :reasoning-desc])]
    (is (= (str/trim (slurp (io/resource "brainyard/prompts/think-act-thought.md"))) (desc "react/think-act")))
    (is (str/starts-with? (desc "react/think-act") "What is going on, for the person watching"))
    (is (nil? (desc "react/evaluate-answer")) "a judge's reasoning is brainyard's")))

;; --- in the loop ---------------------------------------------------------------------

(deftest the-loop-uses-its-declared-predictor
  (clean!)
  (let [wd (str tmp "/project")
        params-file (write-edn! "project/.brainyard/programs/react/think-act.edn"
                                {:version 7
                                 :demos [{:inputs {:question "hi" :iterations "[]"}
                                          :outputs {:tool-calls [] :answer "hello"}
                                          :reasoning "greeting"}]})
        calls (atom [])
        system (atom nil)
        r (agent/ask "q" {:lm (mock-lm (fn [[s]]
                                         (reset! system (:content s))
                                         (json/write-str {:reasoning "r" :tool-calls [] :answer "done"})))
                          :config {:working-dir wd :project-dir wd}
                          :on-event (fn [e d] (when (= :predictor/call e) (swap! calls conj d)))})]
    (is (= "done" (:answer r)))
    (testing "the params file under the working dir's .brainyard/programs applied"
      (is (str/includes? @system
                         "Example 1\nInput:\nquestion: hi\niterations: []\nOutput:\n{\"reasoning\":\"greeting\",\"tool-calls\":[],\"answer\":\"hello\"}")))
    (testing "every predictor call is traced with where its params came from"
      (is (= [["react/think-act" params-file 7]]
             (map (juxt :predictor-id :params-source :params-version) @calls))))
    (testing "the node's :zones decide the system-prompt zones, in order"
      (is (< (str/index-of @system "## agent-core")
             (str/index-of @system "## session-context")
             (str/index-of @system "## user-context"))))))

(deftest generic-predictor-node
  (clean!)
  (write-edn! "project/.brainyard/predictors/user/summarize.edn" summarize-spec)
  (let [tree [:sequence {}
              [:action {:fn :react/init}]
              [:action {:fn :predictor/run :predictor "user/summarize"
                        :in {:text :question} :out {:answer :summary}}]]
        prompt (atom nil)
        r (agent/ask "a very long question"
                     {:lm (mock-lm (fn [[_ user]]
                                     (reset! prompt (:content user))
                                     (json/write-str {:summary "short"})))
                      :tree tree
                      :config {:working-dir (str tmp "/project") :project-dir (str tmp "/project")}})]
    (is (str/starts-with? @prompt "text: a very long question"))
    (is (= "short" (:answer r)))
    (is (= 1 (get-in r [:usage :llm-calls])))))

;; --- which model a call runs on (RFC-005) --------------------------------------------

(deftest lm-resolution-order
  (let [p (predictor/compile-spec summarize-spec)
        ambient {:provider :mock :format :mock :model "ambient"}
        resolve (fn [opts] (predictor/resolve-lm p (:params-record opts)
                                                 (merge {:lm ambient} (dissoc opts :params-record))))]
    (testing "with nothing to say, the ambient LM is used — today's behaviour"
      (is (= "ambient" (:model (resolve {})))))

    (testing "a params :lm label names the model, credentials still from the environment"
      (let [lm (resolve {:params-record {:lm "anthropic/claude-haiku-4-5-20251001"}})]
        (is (= :anthropic (:provider lm)))
        (is (= "claude-haiku-4-5-20251001" (:model lm)))))

    (testing "an explicit override beats params, as the call site does in brainyard"
      (is (= "pinned" (:model (resolve {:lm-override {:provider :mock :model "pinned"}
                                        :params-record {:lm "anthropic/claude-sonnet-5"}}))))
      (is (= "sonnet-from-label" (:model (resolve {:lm-override "anthropic/sonnet-from-label"
                                                   :params-record {:lm "anthropic/other"}})))))

    (testing "a tier resolves through the table, and params :lm outranks it"
      (is (= "tiny" (:model (resolve {:lm-tiers {:light "anthropic/tiny"}
                                      :params-record {:tier :light}}))))
      (is (= "big" (:model (resolve {:lm-tiers {:light "anthropic/tiny" :deep "anthropic/big"}
                                     :params-record {:lm "anthropic/big" :tier :light}})))
          "the label is the more specific hint")
      (is (= "tiny" (:model (predictor/resolve-lm (predictor/compile-spec (assoc summarize-spec :tier :light))
                                                  nil
                                                  {:lm ambient :lm-tiers {:light "anthropic/tiny"}})))
          "a spec may declare its own tier"))

    (testing ":standard and :deep are INERT until the table is configured — brainyard's property"
      (is (= "ambient" (:model (resolve {:lm-tiers {:light nil :standard nil :deep nil}
                                         :params-record {:tier :deep}})))))

    (testing ":light with no table entry runs on the session provider's light model (byj-gly.367)"
      (let [events (atom [])
            on-event (fn [e d] (swap! events conj [e d]))
            light (fn [lm tiers] (predictor/resolve-lm p {:tier :light} {:lm lm :lm-tiers tiers :on-event on-event}))]
        (is (= {:provider :claude-code :model "haiku"}
               (select-keys (light {:provider :claude-code :model "sonnet"} {:light nil}) [:provider :model]))
            "claude-code's, not another provider's")
        (is (= {:provider :bedrock :model "global.anthropic.claude-haiku-5-5"}
               (select-keys (light {:provider :bedrock :model "us.anthropic.claude-sonnet-4-6"} nil) [:provider :model])))
        (is (= "tiny" (:model (light {:provider :claude-code :model "sonnet"} {:light "anthropic/tiny"})))
            "the table still wins")
        (is (= "ambient" (:model (light ambient nil)))
            "a provider with no light model runs it on the session's")
        (is (empty? @events) "and none of it is a problem to report")))

    (testing "a hint that cannot be honoured degrades to the next step, and says so"
      (let [events (atom [])
            lm (predictor/resolve-lm p {:lm "no-such-provider/model" :tier :light}
                                     {:lm ambient
                                      :lm-tiers {:light "anthropic/tiny"}
                                      :on-event (fn [e d] (swap! events conj [e d]))})]
        (is (= "tiny" (:model lm)) "the bad label falls through to the tier, not to an error")
        (is (= [:predictor/lm-unresolved] (map first @events)))
        (is (= "no-such-provider/model" (:lm (second (first @events)))))))

    (testing "an unmapped tier is reported too"
      (let [events (atom [])
            lm (predictor/resolve-lm p {:tier :deep}
                                     {:lm ambient :lm-tiers {:light "anthropic/tiny"}
                                      :on-event (fn [e d] (swap! events conj [e d]))})]
        (is (= "ambient" (:model lm)))
        (is (= [:predictor/tier-unresolved] (map first @events)))))))

(deftest a-params-file-can-choose-the-model
  (testing "end to end: a params record on disk moves the call to another model"
    (clean!)
    (write-edn! "programs/user/summarize.edn" {:lm "anthropic/claude-haiku-4-5-20251001"})
    (let [p (predictor/compile-spec summarize-spec)
          {:keys [params]} (predictor/resolve-params p {:params-roots [(str tmp "/programs")]})
          lm (predictor/resolve-lm p params {:lm {:provider :mock :format :mock :model "ambient"}})]
      (is (= "claude-haiku-4-5-20251001" (:model lm)))
      (is (= :anthropic (:provider lm))))
    (testing "a label carries no credential: the key comes from the environment or not at all"
      (is (nil? (:api-key (llm/parse-lm-label "anthropic/m" (constantly nil)))))
      (is (= "FROM-ENV" (:api-key (llm/parse-lm-label "anthropic/m" (constantly "FROM-ENV"))))))))

(deftest a-mock-named-by-label-answers
  ;; byj-gly.439: `mock/m` made a mock with no replies, so its first call
  ;; failed on nil where an atom was required; it answers as `-p mock` does
  (let [lm (llm/parse-lm-label "mock/m")
        r (llm/chat lm [{:role :system :content "[Turn 1] before"} {:role :user :content "question: hi"}])]
    (is (= "m" (:model lm)))
    (is (= "(mock) hi — 1 previous turn(s) in context" (:answer (json/read-str (:content r)))))))

(deftest lm-params-are-validated
  (is (nil? (predictor/validate-params {:lm "anthropic/claude-sonnet-5" :tier :deep})))
  (is (= ["`lm` should be a string"] (predictor/validate-params {:lm {:model "x"}})))
  (is (= ["`tier` should be either :light, :standard or :deep"] (predictor/validate-params {:tier :turbo})))
  (is (= ["`tier` should be either :light, :standard or :deep"]
         (predictor/validate-spec (assoc summarize-spec :tier "light")))))

(deftest a-node-may-pin-its-own-model
  (testing "a tree node naming :lm is the explicit call site (RFC-005)"
    (let [seen (atom [])
          lm-for (fn [model]
                   {:provider :mock :format :mock :model model
                    :responses (fn [_] (swap! seen conj model)
                                 (json/write-str {:reasoning "r" :tool-calls [] :answer "done"}))})
          tree [:sequence {:id :t}
                [:action {:id :react/init :fn :react/init}]
                [:action {:id :react/inc-iter :fn :react/inc-iter}]
                [:action {:id :react/think-act :fn :react/think-act
                          :predictor "react/think-act"
                          :lm (lm-for "node-pinned")
                          :zones [:agent-core]}]]
          r (agent/ask "q" {:lm (lm-for "ambient")
                            :tree tree
                            :config {:working-dir tmp :project-dir tmp}})]
      (is (= "done" (:answer r)))
      (is (= ["node-pinned"] @seen) "the node's LM ran, not the session's"))))

(deftest old-shape-demos-are-read-as-name-and-args
  ;; a params file compiled before calls were name/args taught the old shape
  ;; beside a schema asking for the new (byj-gly.233)
  (is (= [{:name "read-file" :args {:path "a"}}]
         (get-in (first (predictor/prepare-demos {:demos [{:inputs {:q "x"}
                                                           :outputs {:tool-calls [{:tool-name "read-file" :tool-args {:path "a"}}]}}]}))
                 [:outputs :tool-calls]))))

