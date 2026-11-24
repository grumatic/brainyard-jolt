;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.workflow-test
  "/workflow (RFC-076): a project's workflows listed, shown, added, changed
  and removed on the person's yes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.deps :as deps]
            [brainyard.effect :as fx]
            [brainyard.workflow :as workflow]))

(defn- fresh-dir [name]
  (let [d (io/file "target/workflow-test" name)]
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(def spec
  {:schema-version 1
   :roles {:ci {:title "CI" :does "Supervises runs."}
           :design {:title "Design" :does "Decides scope and design."}
           :implement {:title "Implement" :does "Implements it and commits."}
           :review {:title "Review" :does "Reviews the branch."}}
   :workflows {:feature {:title "Feature" :does "A feature from design to review." :master :ci
                         :stages {:design {} :implement {} :review {}}
                         :links [{:from :design :to :implement
                                  :gives [{:kind :file :paths ["docs/decisions/*.md"]} {:kind :note}]}
                                 {:from :implement :to :review :gives [{:kind :note}]}]}
               :fix {:title "Fix" :master :ci
                     :stages {:implement {} :review {}}
                     :links [{:from :implement :to :review :gives [{:kind :branch} {:kind :note}]}]}}})

(defn- project [name & [m]]
  (let [dir (fresh-dir name)]
    (when-not (= :none m)
      (let [f (deps/spec-file dir)]
        (io/make-parents f)
        (spit f (pr-str (or m spec)))))
    dir))

(defn- read-back [dir] (edn/read-string (slurp (deps/spec-file dir))))

(defn- person
  "A permission-fn that answers `yes?` and keeps what it was asked."
  [asked yes?]
  (fn [req] (swap! asked conj req) (if yes? {:allowed true} {:denied true :reason "the person said no"})))

(deftest stages-are-worked-in-link-order
  (is (= [:design :implement :review] (workflow/order (get-in spec [:workflows :feature]))))
  (is (= [:b :c :a] (workflow/order {:stages {:a {} :b {} :c {}} :links [{:from :c :to :a}]}))
      "each after what links to it, ties by name"))

(deftest workflows-are-listed-and-shown
  (let [dir (project "list")
        cfg {:project-dir dir}
        text (:text (workflow/list-workflows cfg {}))]
    (is (str/includes? text "feature — Feature: A feature from design to review.\n  design → implement → review; master ci"))
    (is (str/includes? text "fix — Fix\n  implement → review; master ci"))
    (is (str/includes? text "Roles: ci (CI) — Supervises runs.; design (Design)"))
    (let [{:keys [text found?]} (workflow/show-workflow cfg {:id "feature"})]
      (is found?)
      (is (str/includes? text "  stage design: role design (Design) — Decides scope and design."))
      (is (str/includes? text "  link design → implement: gives files docs/decisions/*.md, a note: the stage's answer"))
      (is (str/includes? text "  master: ci (CI) — Supervises runs.")))
    (is (str/includes? (:text (workflow/show-workflow cfg {:id "nope"})) "No workflow nope in .brainyard/deps.edn. It has: feature, fix."))
    (testing "a project without one is told how to add one"
      (is (str/includes? (:text (workflow/list-workflows {:project-dir (project "list-none" :none)} {}))
                         "This project has no workflows")))))

(deftest a-workflow-is-added-on-the-persons-yes
  (let [dir (project "add")
        asked (atom [])
        r (workflow/save-workflow {:project-dir dir} {:permission-fn (person asked true)}
                                  {:id "hotfix" :title "Hotfix" :master "ci"
                                   :stages "{:implement {} :check {:role :verify}}"
                                   :links "[{\"from\": \"implement\", \"to\": \"check\", \"gives\": [{\"kind\": \"branch\"}]}]"
                                   :roles "{:verify {:title \"Verify\" :does \"Runs the tests on the branch.\"}}"})
        after (read-back dir)]
    (is (:written? r))
    (is (= 1 (count @asked)))
    (is (= "workflow$save" (:tool (first @asked))))
    (is (str/includes? (:display (first @asked)) "/workflow: add workflow hotfix to .brainyard/deps.edn?"))
    (is (str/includes? (:display (first @asked)) "  runs: implement → check"))
    (is (str/includes? (:display (first @asked)) "  new role verify (Verify) — Runs the tests on the branch."))
    (is (= {:title "Hotfix" :master :ci :stages {:implement {} :check {:role :verify}}
            :links [{:from :implement :to :check :gives [{:kind :branch}]}]}
           (get-in after [:workflows :hotfix])))
    (is (= {:title "Verify" :does "Runs the tests on the branch."} (get-in after [:roles :verify])))
    (is (= (:feature (:workflows spec)) (get-in after [:workflows :feature])) "the others are kept")
    (testing "a role a stage names that the project has not is added bare"
      (workflow/save-workflow {:project-dir dir} {:permission-fn (person (atom []) true)}
                              {:id "docs" :stages {:write {}}})
      (is (= {} (get-in (read-back dir) [:roles :write]))))))

(deftest a-workflow-is-changed-and-removed-on-the-persons-yes
  (let [dir (project "change")
        asked (atom [])
        yes {:permission-fn (person asked true)}]
    (testing "only what is given changes, shown as what goes and what comes"
      (is (:written? (workflow/save-workflow {:project-dir dir} yes {:id "fix" :title "Small fix" :master "none"})))
      (is (str/includes? (:display (last @asked)) "/workflow: change workflow fix in .brainyard/deps.edn?"))
      (is (str/includes? (:display (last @asked)) "- fix — Fix\n- master: ci (CI) — Supervises runs.\n+ fix — Small fix\n+ master: none"))
      (let [fix (get-in (read-back dir) [:workflows :fix])]
        (is (= "Small fix" (:title fix)))
        (is (nil? (:master fix)))
        (is (= {:implement {} :review {}} (:stages fix)))))
    (testing "the person's no writes nothing"
      (let [before (slurp (deps/spec-file dir))
            r (workflow/save-workflow {:project-dir dir} {:permission-fn (person (atom []) false)} {:id "fix" :title "Other"})]
        (is (not (:written? r)))
        (is (str/includes? (:text r) "Nothing was written (the person said no)"))
        (is (= before (slurp (deps/spec-file dir))))))
    (testing "with no person to ask, nothing is written"
      (is (not (:written? (workflow/save-workflow {:project-dir dir} {} {:id "fix" :title "Other"})))))
    (testing "what byj deps write would refuse is refused before the person is asked"
      (let [n (count @asked)]
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes
                                                          {:id "fix" :links [{:from :review :to :implement} {:from :implement :to :review}]}))
                           "workflow fix's links go round"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "fix" :links "[{:from :review :to :nowhere}]"}))
                           "names nowhere, which is not a stage"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id ".x" :stages {:a {}}})) "is not an id"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "empty"})) "needs at least one stage"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "fix" :title "Small fix"}))
                           "what was asked is what it says already"))
        (is (str/includes? (:text (workflow/save-workflow {:project-dir dir} yes {:id "fix" :links "design →"}))
                           "The workflow was not saved"))
        (is (= n (count @asked)) "none of them was put to the person")))
    (testing "removing one leaves its roles"
      (is (:written? (workflow/remove-workflow {:project-dir dir} yes {:id "feature"})))
      (is (str/includes? (:display (last @asked)) "/workflow: remove workflow feature from .brainyard/deps.edn? Its runs are kept."))
      (is (= #{:fix} (set (keys (:workflows (read-back dir))))))
      (is (contains? (:roles (read-back dir)) :design))
      (is (str/includes? (:text (workflow/remove-workflow {:project-dir dir} yes {:id "feature"})) "No workflow feature")))))
