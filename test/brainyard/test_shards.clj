;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.test-shards
  "How `jolt test:byj` splits the suite across processes (byj-gly.333): pure,
  apart from the runner so a test can require it.")

(defn assign
  "`nses` across `n` shards by `times` (ns → ms; 2000 when unknown): the
  longest first, each to the shard with the least so far. Shards in order,
  empty ones dropped."
  [nses n times]
  (let [cost #(get times % 2000)]
    (->> (sort-by (comp - cost) nses)
         (reduce (fn [shards ns]
                   (let [;; the least loaded, the first of them on a tie: deterministic
                         i (first (first (sort-by (juxt (comp :ms second) first) (map-indexed vector shards))))]
                     (-> shards (update-in [i :nses] conj ns) (update-in [i :ms] + (cost ns)))))
                 (vec (repeat n {:nses [] :ms 0})))
         (remove (comp empty? :nses))
         vec)))
