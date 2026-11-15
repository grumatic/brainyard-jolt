;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.profile-test
  "A project's profile (RFC-073): the presets as data, a profile resolved
  against its preset, the ceilings no profile goes above, and what a profile
  that does not match grants."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.profile :as profile]))

(defn- fresh-dir [name]
  (let [d (io/file "target/profile-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(deftest the-presets-are-data-and-name-every-axis
  (let [{:keys [axes levels ceilings presets default-preset]} (profile/presets)
        ids (set (map :id axes))]
    (is (= #{:conservative :collaborator :autopilot} (set (keys presets))) "decided in byj-71w.1")
    (is (contains? presets default-preset))
    (doseq [[n p] presets]
      (is (= ids (set (keys (:autonomy p)))) (str n " sets every axis"))
      (is (every? (set levels) (vals (:autonomy p))) (str n " uses only the levels")))
    (is (= {:decision/scope :ask :git/push :ask} ceilings) "decided in byj-71w.1: never an agent's own")
    (is (every? :covers axes) "each axis says what it covers")))

(deftest a-profile-is-its-preset-with-its-own-overrides
  (let [p (profile/resolve-profile {:profile/version 1 :preset :collaborator
                                    :goal "a workspace" :autonomy {:decision/local :act}})]
    (is (= :collaborator (:preset p)))
    (is (= :act (profile/level p :git/commit)) "from the preset")
    (is (= :act (profile/level p :decision/local)) "the project's own")
    (is (= "a workspace" (:goal p)))
    (is (nil? (:problems p))))
  (is (= :conservative (:preset (profile/resolve-profile {:profile/version 1})))
      "no preset named: the default"))

;; byj-71w.1: scope decisions and push are the person's in every preset
(deftest no-profile-raises-an-axis-above-its-ceiling
  (let [p (profile/resolve-profile {:profile/version 1 :preset :autopilot
                                    :autonomy {:git/push :act :decision/scope :act}})]
    (is (= :ask (profile/level p :git/push)))
    (is (= :ask (profile/level p :decision/scope)))
    (is (= 2 (count (:problems p))))
    (is (str/includes? (first (:problems p)) "above its ceiling")))
  (is (= :never (profile/level (profile/resolve-profile {:profile/version 1 :autonomy {:git/push :never}}) :git/push))
      "below the ceiling is the project's to choose"))

(deftest a-profile-that-does-not-match-grants-only-the-default
  (let [p (profile/resolve-profile {:profile/version 1 :preset :autopilot :goal "g" :colour "blue"
                                    :autonomy {:git/commit :act} :stop {:usd 5.0}})]
    (is (= :conservative (:preset p)) "not the preset it named")
    (is (= :ask (profile/level p :git/commit)) "nor its overrides")
    (is (nil? (:stop p)) "nor its stop limits")
    (is (= "g" (:goal p)) "what it says of the goal is kept, to show")
    (is (some #(str/includes? % ":colour") (:problems p)) "a misspelled key is a problem, not ignored"))
  (is (some #(str/includes? % "missing") (:problems (profile/resolve-profile {:preset :autopilot})))
      "a profile says its version")
  (is (seq (:problems (profile/resolve-profile {:profile/version 1 :autonomy {:git/commit :maybe}})))))

(deftest a-project-without-a-profile-has-none
  (let [dir (fresh-dir "none")]
    (is (nil? (profile/read-profile dir)))
    (is (nil? (profile/read-profile nil)))
    (is (nil? (profile/level nil :git/commit)))))

(deftest the-profile-is-read-from-the-project
  (let [dir (fresh-dir "read")
        f (profile/profile-file dir)]
    (io/make-parents f)
    (spit f (pr-str {:profile/version 1 :preset :collaborator :goal "g"}))
    (let [p (profile/read-profile dir)]
      (is (= :act (profile/level p :issue/close)))
      (is (= (.getPath f) (:file p))))
    (testing "a file that is not EDN reads as the default preset, with why"
      (spit f "{:profile/version 1 :preset")
      (let [p (profile/read-profile dir)]
        (is (= :conservative (:preset p)))
        (is (str/starts-with? (first (:problems p)) "it is not EDN"))
        (is (= 1 (count (:problems p))) "and not that an empty profile lacks a version")))))
