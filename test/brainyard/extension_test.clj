;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.extension-test
  "Loop extension slots (RFC-072): `[:extension …]` expanded by `bt/expand`
  with the trees attached to it, or with nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [brainyard.bt :as bt]
            [brainyard.flow :as flow]))

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
