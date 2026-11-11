(ns brainyard.proposals-test
  "One review gate for every self-improvement kind (byj-gly.249.1)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.programs :as programs]
            [brainyard.proposals :as proposals]
            [brainyard.self-improve :as si]))

(defn- fresh-dir [name]
  (let [d (io/file "target/proposals-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- stage-program! [config id pid]
  (let [dir (io/file (programs/proposals-dir config id) pid)]
    (.mkdirs dir)
    (spit (io/file dir "report.edn") (pr-str {:meta {:optimizer "labeled-few-shot"} :best :c1 :best-score 0.8 :zero-shot-score 0.5}))
    (spit (io/file dir "status.edn") (pr-str {:status :pending}))
    (spit (io/file dir "params.edn") (pr-str {:instructions "Answer in one line."}))
    (spit (io/file dir "REVIEW.md") "# Review\nbetter by 0.3")))

(deftest every-kind-waits-in-one-list
  (let [dir (fresh-dir "one-list")
        config {:working-dir dir :project-dir dir}]
    (si/write-proposal! config {:name "list-twice" :skill-md "---\nname: list-twice\n---\n# List twice" :score 0.9
                                :rationale "listed twice"})
    (stage-program! config "react/think-act" "1790000000000")
    (let [ps (proposals/list-all config)]
      (testing "each by KIND/NAME, a program's by its predictor id and proposal"
        (is (= #{"skill/list-twice" "program/react/think-act/1790000000000"} (set (map :id ps))))
        (is (str/includes? (:summary (first (filter #(= :program (:kind %)) ps))) "labeled-few-shot")))
      (testing "and one kind alone"
        (is (= ["skill/list-twice"] (map :id (proposals/list-all config "skill"))))))
    (testing "show reads the draft as it would go live"
      (is (str/includes? (:body (proposals/read-one config "skill/list-twice")) "# List twice"))
      (is (str/includes? (:body (proposals/read-one config "program/react/think-act/1790000000000")) "better by 0.3")))
    (testing "accepting a program's params installs them, by the same path `by programs accept` takes"
      (let [r (proposals/accept! config "program/react/think-act/1790000000000" {})]
        (is (nil? (:error r)) (pr-str r))
        (is (= {:instructions "Answer in one line."} (edn/read-string (slurp (programs/params-file-for config "react/think-act")))))
        (is (empty? (proposals/list-all config "program")) "and it is no longer pending")))
    (testing "rejecting drops a skill proposal"
      (is (= "skill/list-twice" (:id (proposals/reject! config "skill/list-twice"))))
      (is (empty? (proposals/list-all config))))))

(deftest an-id-says-which
  (let [dir (fresh-dir "ids")
        config {:working-dir dir :project-dir dir}]
    (si/write-proposal! config {:name "dup" :skill-md "# dup" :score 0.9})
    (stage-program! config "dup" "1")
    (testing "KIND/NAME, or a bare name one kind has"
      (is (= [:skill "dup"] (proposals/resolve-id config "skill/dup")))
      (is (= [:skill "dup"] (proposals/resolve-id config "dup")))
      (is (= [:program "dup/1"] (proposals/resolve-id config "program/dup/1")))
      (is (= [:program "dup/1"] (proposals/resolve-id config "dup/1"))))
    (testing "nothing, or nothing by that name, is an error that says where to look"
      (is (str/includes? (:error (proposals/resolve-id config "")) "/proposals lists them"))
      (is (str/includes? (:error (proposals/accept! config "nope" {})) "no proposal named 'nope'")))))

(deftest the-model-sees-and-cannot-decide
  (let [dir (fresh-dir "model")
        config {:working-dir dir :project-dir dir}
        cmds (proposals/commands config)]
    (si/write-proposal! config {:name "list-twice" :skill-md "# List twice" :score 0.9})
    (is (= #{"proposal$list" "proposal$read"} (set (map :name cmds))))
    (is (every? #(= #{:fs/read} (:effects %)) cmds) "reading only")
    (is (= ["skill/list-twice"] (map :id (:proposals ((:fn (first cmds)) {} nil)))))))
