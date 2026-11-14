;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.extension-test
  "Loop extension slots (RFC-072): `[:extension …]` expanded by `bt/expand`
  with the trees attached to it, or with nothing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.bt :as bt]
            [brainyard.flow :as flow]
            [brainyard.project-loops :as project-loops]))

(def slot-after
  [:extension {:id :t/after :reads {:question :question} :writes {:ext/notes :ext/notes}
               :on-failure :ignore}])

(def slot-check
  [:extension {:id :t/check :reads {:answer :answer} :writes {:ext/critique :ext/critique}
               :on-failure :fail}])

(defn- loop-with [slot]
  [:sequence {:id :t/loop}
   [:action {:id :t/before :fn :t/mark :set {:mark :before}}]
   slot
   [:action {:id :t/after-slot :fn :t/mark :set {:mark :after}}]])

(def registry
  {:t/mark (fn [{:keys [st-memory node]}] (swap! st-memory update :trail (fnil conj []) (:mark (:set node))))
   :t/note (fn [{:keys [st-memory node]}]
             (swap! st-memory assoc
                    :ext/notes (str (:question @st-memory) " " (:say node))
                    ;; a key the loop keeps: inside the slot's scope it is the attachment's own
                    :answer "overwritten by an attachment"))
   :t/fail (fn [_] :failure)
   :t/throw (fn [_] (throw (ex-info "attachment broke" {})))})

(defn- run [tree mem]
  (let [m (atom mem)]
    [(bt/tick {:st-memory m :registry registry} tree) @m]))

(defn- attach [& trees]
  (vec (map-indexed (fn [i t] {:name (str "a" i) :tree t}) trees)))

(deftest a-slot-with-nothing-attached-is-a-node-that-does-nothing
  (let [tree (bt/expand (loop-with slot-after) {})]
    (is (= [:sequence {:id :t/after :slot (dissoc (second slot-after) :id)}]
           (nth tree 3)))
    (is (= [:success {:question "q" :answer "a" :trail [:before :after]}]
           (run tree {:question "q" :answer "a"})))
    (testing "the same without an attachments fn at all"
      (is (= tree (bt/expand (loop-with slot-after) {}  nil))))))

(deftest an-attachment-sees-the-slots-reads-and-changes-only-its-writes
  (let [tree (bt/expand (loop-with slot-after) {}
                        {:t/after (attach [:action {:id :x/note :fn :t/note :say "noted"}])})
        [status mem] (run tree {:question "q" :answer "a"})]
    (is (= :success status))
    (is (= "q noted" (:ext/notes mem)))
    (is (= "a" (:answer mem)) "a key outside :writes is the attachment's own")
    (is (= [:before :after] (:trail mem)))))

(deftest what-an-attachment-spends-and-calls-is-the-turns
  ;; a scope is a fresh memory: an attachment's tool call and model cost were
  ;; left in it, out of the turn's record and usage (RFC-072)
  (let [reg (assoc registry :t/spend (fn [{:keys [st-memory]}]
                                       (swap! st-memory #(-> %
                                                             (update :usage update :cost (fnil + 0) 0.5)
                                                             (update :usage-calls (fnil conj []) {:cost 0.5})
                                                             (update :trajectory-iterations (fnil conj []) {:tool-name "bash"})))))]
    (doseq [slot [slot-after slot-check]
            :let [id (:id (second slot))
                  tree (bt/expand (loop-with slot) {} {id (attach [:action {:id :x/s :fn :t/spend}])})
                  m (atom {:question "q" :answer "a" :usage {:cost 1.0} :usage-calls [{:cost 1.0}]
                           :trajectory-iterations [{:tool-name "read-file"}]})]]
      (is (= :success (bt/tick {:st-memory m :registry reg} tree)))
      (is (= 1.5 (get-in @m [:usage :cost])) (str id))
      (is (= 2 (count (:usage-calls @m))))
      (is (= ["read-file" "bash"] (map :tool-name (:trajectory-iterations @m)))))))

(deftest attachments-run-in-the-order-declared
  (let [order (atom [])
        reg (assoc registry :t/say (fn [{:keys [node]}] (swap! order conj (:say node))))
        tree (bt/expand (loop-with slot-after) {}
                        {:t/after (attach [:action {:id :x/one :fn :t/say :say 1}]
                                          [:action {:id :x/two :fn :t/say :say 2}])})]
    (is (= :success (bt/tick {:st-memory (atom {}) :registry reg} tree)))
    (is (= [1 2] @order))))

(deftest a-slot-that-ignores-failure-never-fails-the-loop
  (doseq [leaf [[:action {:id :x/f :fn :t/fail}] [:action {:id :x/t :fn :t/throw}]]]
    (let [tree (bt/expand (loop-with slot-after) {} {:t/after (attach leaf)})
          [status mem] (run tree {:question "q"})]
      (is (= :success status) (pr-str leaf))
      (is (= [:before :after] (:trail mem)) "the loop goes on past the slot"))))

(deftest a-slot-whose-failure-counts-fails-what-holds-it
  (let [tree (bt/expand (loop-with slot-check) {} {:t/check (attach [:action {:id :x/f :fn :t/fail}])})
        [status mem] (run tree {:answer "a"})]
    (is (= :failure status))
    (is (= [:before] (:trail mem))))
  (testing "and passes when its attachments pass"
    (is (= :success (first (run (bt/expand (loop-with slot-check) {}
                                           {:t/check (attach [:action {:id :x/m :fn :t/mark :set {:mark :checked}}])})
                                {:answer "a"}))))))

(deftest the-expansion-and-its-hash-say-what-was-attached
  (let [none (bt/expand (loop-with slot-after) {})
        one (bt/expand (loop-with slot-after) {} {:t/after [{:name "lint" :tree [:action {:id :x/l :fn :t/note}]}]})
        other (bt/expand (loop-with slot-after) {} {:t/after [{:name "spell" :tree [:action {:id :x/l :fn :t/note}]}]})]
    (is (= ["lint"] (:attached (second (nth one 3)))))
    (is (= 3 (count (distinct (map flow/tree-hash [none one other])))))))

(deftest an-unexpanded-slot-does-not-run
  (is (thrown-with-msg? Exception #"unexpanded :extension :t/after"
                        (bt/tick {:st-memory (atom {}) :registry registry} (loop-with slot-after)))))

(deftest a-malformed-slot-is-refused-at-expansion
  (doseq [[opts msg] [[{:reads {} :writes {} :on-failure :ignore} #"no :id"]
                      [{:id :s :reads {} :writes {}} #":on-failure must be"]
                      [{:id :s :reads {} :writes {:answer :answer} :on-failure :fail} #"writes :answer, a key outside :ext/"]]]
    (is (thrown-with-msg? Exception msg (bt/expand [:sequence {} [:extension opts]] {})) (pr-str opts))))

(deftest an-attachment-is-expanded-but-not-extended
  (let [trees {"shared" [:action {:id :x/shared :fn :t/mark :set {:mark :shared}}]}]
    (is (= :success (first (run (bt/expand (loop-with slot-after) trees
                                           {:t/after (attach [:include {:tree "shared"}])})
                                {})))))
  (is (thrown-with-msg? Exception #"offers a slot of its own"
                        (bt/expand (loop-with slot-after) {}
                                   {:t/after (attach [:sequence {} slot-check])}))))

(defn- needs-iterations {:reads #{:iterations}} [_] nil)
(defn- mark {:writes #{:trail}} [_] nil)

(deftest flow-check-reads-an-attachment-where-it-sits
  (let [reg {:t/mark #'mark :t/needs #'needs-iterations}
        check #(flow/check (bt/expand (loop-with slot-after) {} {:t/after (attach %)})
                           {:registry reg :initial #{:question :iterations}})]
    (testing "a key the loop has but the slot does not let it read is not there"
      (is (some #(re-find #"reads :iterations" (:problem %))
                (check [:action {:id :x/n :fn :t/needs}]))))
    (testing "an expanded slot is a tree flow check takes as any other"
      (is (empty? (check [:action {:id :x/m :fn :t/mark}]))))))

;; --- a project's attachments (byj-9sp.3) --------------------------------------------

(defn- project []
  (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir") (str "slot-attachments-" (random-uuid))) .mkdirs)))

(def lint [:action {:id :lint/note :fn :mem/set :set {:ext/notices "LINT: fine"}}])

(defn- contract [slot] (get (project-loops/slots (agent/load-tree "react")) slot))

(deftest a-project-with-nothing-attached-runs-the-bundled-loop
  (let [pd (project)]
    (is (= {} (project-loops/attachments pd)))
    (is (= (agent/load-tree "react") (agent/load-tree "react" pd)))))

(deftest what-a-project-attached-is-in-every-loop-it-runs
  (let [pd (project)
        line (project-loops/write-attachment! pd :react/ext-after-tools "lint" lint
                                              {:contract (contract :react/ext-after-tools) :proposal "loop/x"})]
    (is (= {:react/ext-after-tools ["lint"]} (project-loops/attached pd)))
    (is (= {:action :attach :loop "react" :slot :react/ext-after-tools :name "lint" :replaced nil :proposal "loop/x"}
           (dissoc line :at :hash)))
    (is (= line (last (project-loops/history pd))) "a history line, as a loop's change has")
    (let [react (agent/load-tree "react" pd)
          node (first (filter #(= :react/ext-after-tools (:id (second %))) (bt/nodes react)))]
      (is (= ["lint"] (:attached (second node))))
      (is (some #(= :lint/note (:id (second %))) (bt/nodes react)))
      (is (not= (flow/tree-hash react) (flow/tree-hash (agent/load-tree "react")))))
    (testing "the session loop's turn too"
      (is (some #(= :lint/note (:id (second %))) (bt/nodes (agent/load-tree "session" pd)))))
    (testing "--print-tree names it"
      (is (some #(and (= :attachment (:from %)) (= "lint" (:name %))) (agent/tree-sources "react" pd)))
      (is (str/includes? (project-loops/source-line (last (agent/tree-sources "react" pd)))
                         "the project's .brainyard/loops/ext/lint.edn, attached to :react/ext-after-tools")))
    (testing "doctor says it is attached, to a slot as it is"
      (is (str/includes? (project-loops/report-text pd) "lint → :react/ext-after-tools: attached")))
    (testing "detached, the bundled loop again, and another line"
      (project-loops/write-attachment! pd :react/ext-after-tools "lint" nil {})
      (is (= {} (project-loops/attached pd)))
      (is (not (.exists (project-loops/attachment-file pd "lint"))))
      (is (= :detach (:action (last (project-loops/history pd)))))
      (is (= (agent/load-tree "react") (agent/load-tree "react" pd))))))

(deftest an-attachment-whose-slot-changed-is-named-by-doctor
  (let [pd (project)]
    (project-loops/write-attachment! pd :react/ext-after-tools "lint" lint {:contract {:reads {} :writes {} :on-failure :fail}})
    (is (str/includes? (project-loops/report-text pd) "made against a slot that has changed since"))
    (project-loops/write-attachment! pd :react/ext-nowhere "lost" lint {:contract {}})
    (is (str/includes? (project-loops/report-text pd) "has no slot :react/ext-nowhere"))))

(deftest an-attachment-that-is-not-there-stops-the-loop-rather-than-run-another
  (let [pd (project)]
    (io/make-parents (project-loops/attach-file pd))
    (spit (project-loops/attach-file pd) (pr-str {:react/ext-after-tools ["gone"]}))
    (is (thrown-with-msg? Exception #"there is no .brainyard/loops/ext/gone.edn" (agent/load-tree "react" pd)))
    (spit (project-loops/attach-file pd) "{:react/ext-after-tools \"lint\"}")
    (is (thrown-with-msg? Exception #"attach.edn is \{slot-id" (agent/load-tree "react" pd)))))
