;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.test-shards-test
  (:require [clojure.test :refer [deftest is testing]]
            [brainyard.test-shards :as shards]))

(deftest the-suite-is-split-by-measured-time-longest-first
  ;; byj-gly.333: a shard is as slow as its namespaces; the longest go first,
  ;; each to the shard with the least so far
  (let [times {'a 50 'b 40 'c 30 'd 20 'e 10}
        out (shards/assign '[a b c d e] 2 times)]
    (is (= [['a 'd 'e] ['b 'c]] (map :nses out)) "a tie goes to the first shard")
    (is (= [80 70] (map :ms out))))
  (testing "a namespace never measured counts 2000 ms"
    (is (= [{:nses ['new] :ms 2000}] (shards/assign '[new] 1 {}))))
  (testing "more shards than namespaces: the empty ones are dropped"
    (is (= 2 (count (shards/assign '[a b] 6 {'a 1 'b 2}))))))
