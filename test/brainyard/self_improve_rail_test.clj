;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.self-improve-rail-test
  "The left rail's Self-improvement section (byj-gly.425): distillation's two
  toggles, sent to the session as the /config a person would type, and the
  proposals waiting — over a config and a proposal list of the test's own."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.proposals :as proposals]
            [brainyard.self-improve-rail :as rail]))

(defn- drawn [items] (mapv :label items))

(defn- read! [{:keys [refresh! state]}]
  (refresh!)
  (loop [n 0]
    (when (and (nil? (:proposals @state)) (nil? (:error @state)) (< n 150))
      (Thread/sleep 20)
      (recur (inc n)))))

(def pending
  [{:id "tool/count-lines" :kind :tool :name "count-lines" :score 0.82 :summary "counts lines"}
   {:id "skill/list-twice" :kind :skill :name "list-twice" :score 0.7 :summary "distillation"}])

(deftest the-toggles-say-whats-on-and-the-proposals-what-waits
  (let [read {:settings {:enable-tool-distillation true :enable-skill-distillation false} :proposals pending}]
    (is (= ["[x] tool distillation" "[ ] skill distillation" "▸ Proposals"] (drawn (rail/rows read false)))
        "the toggles flush; the proposals folded under their heading")
    (is (= "2" (:hint (nth (rail/rows read false) 2))) "the heading counts them")
    (is (= ["[x] tool distillation" "[ ] skill distillation" "▾ Proposals" "  ◇ tool/count-lines" "  ◇ skill/list-twice"]
           (drawn (rail/rows read true))) "unfolded"))
  (let [rs (rail/rows {:settings {:enable-tool-distillation true} :proposals pending} true)]
    (is (= [["on" {:color :green}] ["off" {:dim true}]] (mapv (juxt :hint :hint-style) (take 2 rs))) "on in green, off dim")
    (is (= "0.82" (:hint (nth rs 3))) "a proposal's score"))
  (is (= ["[ ] tool distillation" "[ ] skill distillation" "▾ Proposals" "  none waiting"]
         (drawn (rail/rows {:settings {} :proposals []} true))))
  (is (= "0" (:hint (nth (rail/rows {:settings {} :proposals []} false) 2))))
  (is (= "  reading…" (last (drawn (rail/rows {} true))))))

(deftest a-toggle-is-the-config-a-person-would-type
  (let [sent (atom [])
        shown (atom nil)]
    (with-redefs [proposals/list-all (fn [_] pending)
                  proposals/read-one (fn [_ id] {:id id :body (str "the draft of " id)})]
      (let [mv (rail/views {} {:read-config (constantly {:enable-tool-distillation false :enable-skill-distillation true})
                               :send! (fn [line quiet?] (swap! sent conj [line quiet?]))
                               :show (fn [t x] (reset! shown [t x]))})
            view (:view mv)]
        (is (= "Self-improvement" (:title view)))
        (read! mv)
        (is (= ["[ ] tool distillation" "[x] skill distillation"] (take 2 (drawn ((:items view))))))
        (testing "Enter on a toggle sends /config, shown in the transcript, and the row flips at once"
          ((:act view) (first ((:items view))))
          (is (= [["/config enable-tool-distillation true" false]] @sent))
          (is (= "[x] tool distillation" (first (drawn ((:items view))))))
          ((:act view) (second ((:items view))))
          (is (= ["/config enable-skill-distillation false" false] (last @sent))))
        (testing "Enter on the heading unfolds the proposals"
          ((:act view) (nth ((:items view)) 2))
          (is (= "▾ Proposals" (:label (nth ((:items view)) 2)))))
        (testing "Enter on a proposal shows its draft and how to decide it; nothing is accepted"
          ((:act view) (nth ((:items view)) 3))
          (loop [n 0] (when (and (nil? @shown) (< n 100)) (Thread/sleep 20) (recur (inc n))))
          (is (= "proposal — tool/count-lines" (first @shown)))
          (is (str/starts-with? (second @shown) "the draft of tool/count-lines"))
          (is (str/ends-with? (second @shown) "/proposals accept tool/count-lines · /proposals reject tool/count-lines"))
          (is (= 2 (count @sent)) "no line was sent for it"))
        (is (= "skill/list-twice" ((:put view) (nth ((:items view)) 4))) "@ mentions a proposal by its id")
        (is (nil? ((:put view) (first ((:items view))))) "a toggle has nothing to mention")))))
