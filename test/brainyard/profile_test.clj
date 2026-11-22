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

;; byj-71w.3: AGENTS.md shows the profile, rendered from the data
(deftest agents-md-shows-the-profile-rendered-from-the-data
  (let [p (profile/resolve-profile {:profile/version 1 :preset :collaborator :goal "A workspace."
                                    :goal-epic "mp-1" :scope {:in ["server"] :out ["auth"]}
                                    :done ["npm test passes"] :stop {:failures 3 :usd 5.0}})
        b (profile/render p)]
    (is (str/starts-with? b "<!-- BEGIN BRAINYARD:profile -->\n"))
    (is (str/ends-with? b "\n<!-- END BRAINYARD:profile -->"))
    (is (= b (profile/render p)) "the same profile, the same block")
    (is (not (str/includes? b "mp-1")) "a :goal-epic is deprecated: never said (byj-yq9)")
    (doseq [s ["Preset: collaborator." "Goal: A workspace." "In scope: server."
               "Out of scope: auth." "Done when: npm test passes." "after 3 failed attempts in a row, $5 spent"
               "- committing a bead's work" "opts in to Team-maintainer" "It never pushes on its own"]]
      (is (str/includes? b s) s))
    (testing "every axis is listed once, under its level"
      (doseq [{:keys [covers]} (:axes (profile/presets))]
        (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote covers)) b))) covers)))
    (testing "what the profile does not say, the block does not either"
      (let [bare (profile/render (profile/resolve-profile {:profile/version 1}))]
        (is (not (str/includes? bare "Goal:")))
        (is (not (str/includes? bare "It stops")))
        (is (str/includes? bare "keeps the Conservative profile"))
        (is (not (str/includes? bare "**Conservative (default)**")) "not bd's own words, which say its default")))))

;; byj-yq9: the profile says where the project's issues are kept, in one
;; tracker or several; the goal has no epic, and :goal-epic is ignored
(deftest the-profile-says-its-issue-trackers
  (let [ok {:profile/version 1 :preset :collaborator :goal "A workspace."}
        problems #(:problems (profile/resolve-profile (assoc ok :issues %)))]
    (testing "trackers and their places hold, one or several, two of a kind told apart by :name"
      (is (nil? (problems [{:tracker :beads}])))
      (is (nil? (problems [{:tracker :beads} {:tracker :github :github-repo "acme/web"}])))
      (is (nil? (problems [{:tracker :linear :linear-team "ENG"}
                           {:tracker :jira :jira-site "acme.atlassian.net" :jira-project "ABC"}])))
      (is (nil? (problems [{:tracker :github :name "web" :github-repo "acme/web"}
                           {:tracker :github :name "api" :github-repo "acme/api"}])))
      (is (nil? (problems []))))
    (testing "a tracker it does not know, none, a key it does not know, an epic, or a single map is a problem"
      (is (some #(str/starts-with? % ":issues") (problems [{:tracker :trello}])))
      (is (some #(str/starts-with? % ":issues") (problems [{:github-repo "acme/web"}])))
      (is (some #(str/starts-with? % ":issues") (problems [{:tracker :beads :board "x"}])))
      (is (some #(str/starts-with? % ":issues") (problems [{:tracker :beads :epic "mp-1"}])) "the goal has no epic")
      (is (some #(str/starts-with? % ":issues") (problems {:tracker :beads}))))
    (testing "an older profile's :goal-epic still loads, and is ignored"
      (is (nil? (:problems (profile/resolve-profile (assoc ok :goal-epic "mp-1")))))
      (is (= [] (profile/trackers {:goal-epic "mp-1"}))))
    (testing "the trackers named, in order"
      (is (= [:beads :github] (profile/trackers {:issues [{:tracker :beads} {:tracker :github}]})))
      (is (= [] (profile/trackers {}))))
    (testing "AGENTS.md's block says each tracker and its place, in one line"
      (let [line #(profile/render (profile/resolve-profile (assoc ok :issues %)))]
        (is (str/includes? (line [{:tracker :beads}]) "\nIssues are tracked in beads.\n"))
        (is (str/includes? (line [{:tracker :beads} {:tracker :github :github-repo "grumatic/brainyard-apps"}])
                           "Issues are tracked in beads and GitHub Issues (grumatic/brainyard-apps)."))
        (is (str/includes? (line [{:tracker :beads} {:tracker :linear :linear-team "ENG"}
                                  {:tracker :jira :name "ops" :jira-site "acme.atlassian.net" :jira-project "ABC"}])
                           "Issues are tracked in beads, Linear (team ENG) and Jira at acme.atlassian.net, project ABC."))
        (is (str/includes? (line [{:tracker :github}]) "Issues are tracked in GitHub Issues."))
        (is (not (str/includes? (line []) "Issues are tracked")) "none named, none said")
        (is (not (str/includes? (profile/render (profile/resolve-profile (assoc ok :goal-epic "mp-1"))) "epic"))
            "a :goal-epic is never said")))))

(deftest the-block-is-checked-against-the-data-never-read-for-it
  (let [p (profile/resolve-profile {:profile/version 1 :preset :autopilot})
        b (profile/render p)
        md #(str "# Agent Instructions\n\n" % "\n\nMine.\n")]
    (is (= b (profile/shown-block (md b))))
    (is (= :current (profile/block-state p (md b))))
    (is (= :stale (profile/block-state p (md (str/replace b "only with the person's yes" "on its own")))))
    (is (= :stale (profile/block-state (profile/resolve-profile {:profile/version 1}) (md b))) "the data changed")
    (is (= :missing (profile/block-state p "# Agent Instructions\n")))
    (is (= :orphan (profile/block-state nil (md b))))
    (is (nil? (profile/block-state nil "# Agent Instructions\n")))))
