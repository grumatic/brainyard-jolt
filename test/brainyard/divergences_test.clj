(ns brainyard.divergences-test
  "Keeps docs/divergences.md and docs/divergences.edn honest with each other,
  pins the divergences that no other test covers, and holds every test byj's
  documents cite to being there."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.llm :as llm]
            [brainyard.predictor :as predictor]))

(def entries (edn/read-string (slurp "docs/divergences.edn")))

(def anchors
  (set (map second (re-seq #"<!-- divergence: ([a-z0-9-]+) -->" (slurp "docs/divergences.md")))))

(defn- test-var [s]
  (let [[ns-part name-part] (str/split s #"/")]
    (try (require (symbol ns-part))
         (find-var (symbol ns-part name-part))
         (catch Throwable _ nil))))

(deftest the-record-is-consistent
  (let [ids (map :id entries)]
    (is (= (count ids) (count (set ids))) "duplicate ids in divergences.edn")
    (testing "every entry is anchored in divergences.md"
      (is (empty? (remove anchors ids))))
    (testing "every anchor has an entry"
      (is (empty? (remove (set ids) anchors))))
    (doseq [{:keys [id test why brainyard]} entries]
      (testing id
        (is (string? brainyard) "names where brainyard does it")
        (is (or (string? why) (some? (and test (test-var test))))
            "names a test that exists, or says why none can")))))

;; --- pins for divergences no other test covers --------------------------------

(deftest parallel-tools-default
  (is (false? (:parallel-tools? agent/default-config))))

(deftest directive-names-no-code-channel
  (is (not (str/includes? llm/structured-output-directive "code-blocks")))
  (is (str/includes? llm/structured-output-directive "calling the StructuredOutput tool"))
  (testing "divergence native-tool-calls-recovered: the listed tools are said to exist"
    (is (not (str/includes? llm/structured-output-directive "You have NO tools")))
    (is (str/includes? llm/structured-output-directive "The tools named in the prompt DO exist"))))

;; --- every test a document names is there (byj-ahf.27) ----------------------------------------

(defn- test-names
  "What each test namespace defines, by its short name (`share-test`): its
  deftests and its helpers, read from the files under test/ rather than
  loaded."
  []
  (into {} (for [^java.io.File f (file-seq (io/file "test"))
                 :when (and (.isFile f) (str/ends-with? (.getName f) ".clj"))
                 :let [src (slurp f)
                       ns-name (second (re-find #"\(ns\s+([\w.-]+)" src))]
                 :when ns-name]
             [(last (str/split ns-name #"\."))
              (set (map second (re-seq #"\((?:deftest|defn-?|def)\s+(?:\^:[\w-]+\s+)?([^\s()\[\]]+)" src)))])))

(deftest every-test-a-document-cites-is-there
  ;; an RFC's invariant names its enforcer as `ns-test/test-name`; one that
  ;; names a test since renamed or deleted sends a reader to nothing, and
  ;; seventeen did (byj-ahf.27). `ns-test/*` is the whole namespace.
  (let [names (test-names)
        docs (concat (filter #(str/ends-with? (.getName ^java.io.File %) ".md") (.listFiles (io/file "docs/RFCS")))
                     [(io/file "docs/divergences.md") (io/file "docs/provenance.md")])
        missing (for [^java.io.File f docs
                      [_ ns-name test-name] (re-seq #"`([a-z0-9-]+-test)/([^`\s,]+)`" (slurp f))
                      :let [defined (get names ns-name)]
                      :when (or (nil? defined) (not (or (= "*" test-name) (defined test-name))))]
                  (str (.getName f) ": " ns-name "/" test-name))]
    (is (seq names) "no test namespace was read")
    (is (empty? missing) (str "a document cites a test that is not there: " (pr-str (vec (distinct missing)))))))

