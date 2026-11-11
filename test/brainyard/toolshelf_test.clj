(ns brainyard.toolshelf-test
  "`/tools`' rows (byj-gly.273.1): every tool in the registry, what its info
  pane says, how a filter ranks it, and what the permission gates would do with
  a call of it now (`permission/tool-verdict`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.permission :as permission]
            [brainyard.toolshelf :as ts]))

(defn- ctx [config]
  (agent/make-ctx {:config (merge {:working-dir "." :project-dir "."} config)
                   :lm {:provider :mock :format :mock}}))

(defn- row [rows id] (first (filter #(= id (:id %)) rows)))

(deftest every-tool-is-a-row-with-its-spec
  (let [rows (ts/rows (ctx {}))
        grep (row rows "grep")]
    (is (= (count @(:tools (ctx {}))) (count rows)) "every tool in the registry")
    (is (= rows (sort-by (juxt :family :id) rows)) "by family, then id")
    (testing "a tool's row"
      (is (= ["_ungrouped" "tool" "built in"] ((juxt :family :type :source) grep)))
      (is (= [:pattern :path :max-results :include-exts] (map :name (:inputs grep))))
      (is (false? (:optional? (first (:inputs grep)))))
      (is (str/starts-with? (:outputs grep) "{:matches [string …]"))
      (is (= ["fs/read" "process"] (:effects grep)) "an effect keeps its namespace"))
    (testing "how to call it, both ways"
      (is (= "/grep <pattern>" (get-in grep [:usage :model])))
      (is (= ":grep :pattern \"…\" [:path \"…\"] [:max-results N] [:include-exts …]" (get-in grep [:usage :direct]))))
    (testing "what the pane says"
      (let [text (str/join "\n" (ts/spec-lines grep))]
        (is (str/includes? text "  pattern string — Extended regular expression"))
        (is (str/includes? text "  path string (optional) — "))
        (is (str/includes? text "Effects: fs/read, process"))
        (is (str/includes? text "Call directly:   :grep :pattern"))))))

(deftest a-filter-finds-a-name-before-a-description
  (let [rows (ts/rows (ctx {}))
        hits (map :id (ts/matches rows "grep"))]
    (is (= "grep" (first hits)))
    (is (some #{"read-file"} hits) "a description that mentions it is kept, after")
    (is (= rows (ts/matches rows "  ")) "a blank filter keeps every row")
    (is (empty? (ts/matches rows "zzqq-nothing")))))

(deftest a-tool-says-what-the-gates-would-do
  (let [verdict #(:verdict (permission/tool-verdict %1 (ctx %2)))]
    (is (= :allow (verdict "grep" {})) "no rule asks about it")
    (is (= :ask (verdict "grep" {:tool-ask-effects #{:process} :permission-mode :ask-each-time})))
    (is (= :allow (verdict "grep" {:tool-ask-effects #{:process} :permission-mode :auto-approve})))
    (is (= :deny (verdict "grep" {:tool-ask-effects #{:process} :permission-mode :deny-by-default})))
    (is (= :deny (verdict "bash" {:tool-deny-tools ["bash"]})) "a deny rule, whatever the mode")
    (is (= :allow (verdict "bash" {:tool-approval-patterns ["bash"] :tool-allow-tools ["bash"]
                                   :permission-mode :ask-each-time}))
        "an allow rule outranks the ask")
    (is (str/includes? (:why (permission/tool-verdict "bash" (ctx {:tool-deny-tools ["b*"]}))) ":tool-deny-tools \"b*\""))))

(deftest a-direct-call-is-parsed-as-brainyard-reads-it
  ;; byj-gly.273.3: brainyard's kwargs — quotes, bare or :keys, a value coerced
  ;; by the argument's type — or an EDN map; a key the tool does not take is
  ;; refused rather than passed through an open map
  (let [in (brainyard.schema/inputs (get @(:tools (ctx {})) "grep"))]
    (is (= {:id "grep" :args {:pattern "TODO" :path "src dir" :max-results 5 :include-exts ["clj" "edn"]}}
           (ts/parse-direct ":grep :pattern TODO :path \"src dir\" :max-results 5 :include-exts clj,edn" in)))
    (is (= {:id "grep" :args {:pattern "foo"}} (ts/parse-direct ":grep pattern foo" in)) "a key may be bare")
    (is (= {:id "grep" :args {:pattern "x" :max-results 3}} (ts/parse-direct ":grep {:pattern \"x\" :max-results 3}" in)))
    (is (= {:id "grep" :args {:pattern "open quote rest"}} (ts/parse-direct ":grep :pattern \"open quote rest" in)))
    (is (str/starts-with? (:error (ts/parse-direct ":grep {:pattern" in)) "the arguments do not read as an EDN map"))
    (is (= "takes no :src — it takes :pattern, :path, :max-results, :include-exts (as :key value pairs, or one EDN map)"
           (:error (ts/parse-direct ":grep src" in))) "a misplaced value is said, not ignored")
    (is (nil? (ts/parse-direct ":) hi" in)) "not a call")
    (is (nil? (ts/parse-direct "grep :pattern x" in)))))

(deftest what-the-next-turn-is-told-is-a-line
  (is (= ":grep :pattern TODO → {:total 2}" (ts/digest ":grep  :pattern   TODO" {:total 2} false 200)))
  (is (= ":x ✗ {:error \"no\"}" (ts/digest ":x" {:error "no"} true 200)))
  (is (= 20 (count (ts/digest ":grep :pattern TODO" {:matches (vec (repeat 20 "a:1:x"))} false 20))) "cut, with an ellipsis"))
