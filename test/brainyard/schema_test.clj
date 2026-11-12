;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.schema-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.discovery :as discovery]
            [brainyard.schema :as schema]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/schema-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- check [input-schema args] (schema/check-args {:input-schema input-schema} args))

(deftest a-tool-declares-its-arguments-as-a-malli-map
  (is (= [:map] (schema/tool-schema {})) "a tool that declares none takes an open map")
  (testing "the listing reads the schema's entries, a :desc on the value or the entry (byj-gly.237)"
    (is (= [{:name :path :type "string" :optional? false :desc "File path"}
            {:name :offset :type "integer" :optional? true :desc "1-based"}
            {:name :paths :type "array" :optional? true :desc nil}
            {:name :opts :type "object" :optional? false :desc nil}
            {:name :mode :type "\"a\"|\"b\"" :optional? true :desc nil}
            {:name :n :type "number" :optional? true :desc nil}]
           (schema/inputs {:input-schema [:map [:path [:string {:desc "File path"}]]
                                          [:offset {:optional true :desc "1-based"} :int]
                                          [:paths {:optional true} [:vector :string]]
                                          [:opts :map]
                                          [:mode {:optional true} [:enum "a" "b"]]
                                          [:n {:optional true} [:or :int :double]]]}))))
  (is (= [:map [:a {:optional true} :int] [:b {:optional true} :string]]
         (schema/all-optional [:map [:a :int] [:b {:optional true} :string]]))))

(deftest every-tool-keeps-the-coercions-models-taught-the-port
  ;; each of these was a rule of the plain check before malli (RFC-030), and the
  ;; spike found malli judging every recorded call the same way with them; they
  ;; hold for every tool's arguments now, not only those once written as :params
  (let [s [:map [:n {:optional true} :int]
           [:s {:optional true} :string]
           [:v {:optional true} [:vector :string]]
           [:o {:optional true} :map]
           [:b {:optional true} :boolean]]]
    (is (= {:args {:n 145}} (check s {:n "145"})) "a numeric string is an integer")
    (is (= {:args {:n 3}} (check s {:n 3.0})) "a whole float is an integer")
    (is (= {:args {:s "42"}} (check s {:s 42})) "a number where a string is wanted is printed")
    (is (= {:args {:v ["clj"]}} (check s {:v "clj"})) "a lone string where an array is wanted is wrapped")
    (is (= {:args {:o {:a 1}}} (check s {:o "{\"a\": 1}"})) "an object sent as JSON text is parsed")
    (is (= {:args {:o {:a 1}}} (check s {:o {:a 1}})) "and one sent as an object keeps its keys")
    (is (= {:args {:b false}} (check s {:b "false"})))
    (testing "only a whole float in range is an integer, and space round one is no matter (byj-gly.234)"
      (is (seq (:problems (check s {:n 3.7}))) "3.7 is not 3")
      (is (seq (:problems (check s {:n 1e20}))) "and 1e20 is a problem, not a throw")
      (is (= {:args {:n 4}} (check s {:n " 4 "}))))))

(deftest brainyards-own-decoding-applies-to-every-schema
  (let [t {:input-schema [:map [:k {:optional true} :keyword]
                          [:b {:optional true} :boolean]
                          [:e {:optional true} [:enum "gates" "tools"]]
                          [:ke {:optional true} [:enum :a :b]]]}]
    (is (= {:args {:k :nrepl}} (schema/check-args t {:k ":nrepl"})) "a keyword from \":a\" as well as \"a\"")
    (is (= {:args {:b true}} (schema/check-args t {:b "True"})))
    (is (= {:args {:b true}} (schema/check-args t {:b "yes"})))
    (is (= {:args {:b false}} (schema/check-args t {:b "0"})))
    (is (= {:args {:e "gates"}} (schema/check-args t {:e :gates})) "an enum member in the form that arrived")
    (is (= {:args {:ke :a}} (schema/check-args t {:ke "a"})))
    (is (seq (:problems (schema/check-args t {:b "maybe"}))) "and anything else is still wrong")))

(deftest an-or-is-not-coerced-the-ports-way
  ;; printing a number would make it match the :string branch of
  ;; map-object-arg: an :or, and anything below the arguments, decodes as
  ;; brainyard's does
  (let [t {:input-schema [:map [:opts :brainyard.schema/map-object-arg]
                          [:deep {:optional true} [:map [:s :string]]]]}]
    (is (seq (:problems (schema/check-args t {:opts 5}))))
    (is (= {:args {:opts "{:a 1}"}} (schema/check-args t {:opts "{:a 1}"})) "the tool parses the string itself")
    (is (= {:args {:opts {:a 1}}} (schema/check-args t {:opts {:a 1}})) "and a map stays a map")
    (is (seq (:problems (schema/check-args t {:opts {} :deep {:s 42}}))))))

(deftest a-null-is-the-argument-left-out
  (let [s [:map [:path :string] [:limit {:optional true} :int]]]
    (is (= {:args {:path "a"}} (check s {:path "a" :limit nil})) "an optional null is absent")
    (is (= {:problems ["missing required `path`"]} (check s {:path nil})) "a required one is missing")))

(deftest problems-say-which-argument-and-what
  (is (= #{"missing required `path`" "`limit` should be an integer"}
         (set (:problems (check [:map [:path :string] [:limit {:optional true} :int]] {:limit "x"})))))
  (is (= ["`o` should be an object"] (:problems (check [:map [:o :map]] {:o "not json"}))))
  (is (= ["`v` should be an array"] (:problems (check [:map [:v [:vector :any]]] {:v 3}))))
  (is (= ["`items.0.n` should be an integer"]
         (:problems (schema/check-args {:input-schema [:map [:items [:vector [:map [:n :int]]]]]} {:items [{:n "x"}]})))
      "a nested one by its path"))

(deftest the-registry-holds-the-shared-schemas
  (is (some? (schema/schema :brainyard.schema/tool-calls)))
  (is (some? (schema/schema :brainyard.schema/vector-object-arg)))
  (testing "and defschemas adds to it, for any schema compiled after"
    (schema/defschemas test-domain {::widget [:map [:size :int]]})
    (is (= {::widget [:map [:size :int]]} test-domain))
    (is (= {:args {:w {:size 3}}}
           (schema/check-args {:input-schema [:map [:w ::widget]]} {:w {:size "3"}})))))


;; --- :output-schema (stage 2) ---------------------------------------------------------

(deftest the-suite-records-what-it-checks
  ;; the test runner holds every tool result any test produces to its schema
  (let [before (schema/recorded-outputs)]
    (try
      (schema/record-outputs!)
      (tools/call-tool {:name "rec" :input-schema [:map] :output-schema [:map [:n :int]] :fn (fn [_ _] {:n 1})} {} {})
      (binding [schema/*record?* false]
        (tools/call-tool {:name "rec" :input-schema [:map] :output-schema [:map [:n :int]] :fn (fn [_ _] {:n "x"})} {} {}))
      (is (= [{:tool-name "rec" :problems nil}] (schema/recorded-outputs))
          "a conforming call is recorded, and a test can keep a deliberate mismatch out")
      (finally
        ;; the runner's own recording goes on with what it had
        (schema/record-outputs! before)))))

(deftest an-output-schema-is-written-as-the-line-the-model-reads
  (is (= "{:path string, :entries [string …], :n? int} or {:error string} — entries end with /"
         (schema/describe [:or {:desc "entries end with /"}
                           [:map [:path :string] [:entries [:vector :string]] [:n {:optional true} :int]]
                           [:map [:error :string]]])))
  (is (= "{:status \"completed\"|\"failed\", :exit-code int or nil}"
         (schema/describe [:map [:status [:enum "completed" "failed"]] [:exit-code [:maybe :int]]])))
  (is (= "{:ok? bool, :reverted? bool (optional)}"
         (schema/describe [:map [:ok? :boolean] [:reverted? {:optional true} :boolean]]))
      "an optional key whose name ends in ? is not given a second one")
  (is (= "{:score number}" (schema/describe [:map [:score number?]])) "a predicate by its noun")
  (is (= "{:summary {:worst [{…} …]}}" (schema/describe [:map [:summary [:map [:worst [:vector [:map [:i :int]]]]]]]))
      "a map past two levels deep is only said to be there")
  (testing "prose stays prose; a schema is read from data or from EDN text"
    (is (nil? (schema/read-output-schema "{:lines int}")))
    (is (nil? (schema/read-output-schema "the tool's own result")))
    (is (= [:map [:lines :int]] (schema/read-output-schema "[:map [:lines :int]]")))
    (is (nil? (schema/read-output-schema "[:map [:lines :no-such-type]]")) "text malli cannot compile is prose")
    (is (nil? (schema/read-output-schema ":string — the rendered report")) "prose that starts with a keyword is prose (byj-gly.231)")
    (is (nil? (schema/read-output-schema ":map of name to count")))
    (is (= :string (schema/read-output-schema " :string ")) "and one form alone is a schema")))

(def ^:private samples
  "Args for every built-in tool, one per branch of its :output-schema, so each shape
  it can return is seen. A tool added without samples fails
  `every-built-in-tool-returns-what-it-says`."
  {"bash" [{:command "echo hi"} {:command "exit 3"} {:command "sleep 2" :timeout 100}]
   "grep" [{:pattern "hello" :path "."} {:pattern "zzz-no-match-zzz" :path "."} {:pattern "hello" :path "no-such-dir"}]
   "list-dir" [{:path "."} {:path "no-such-dir"}]
   "read-file" [{:path "small.txt"} {:path "big.txt"} {:path "big.txt" :offset 2 :limit 3}
                {:path "one.txt"} {:path "one.txt" :offset 1 :column 20000} {:path "missing.txt"}]
   "update-file" [{:path "small.txt" :pattern "hello" :replacement "hullo"} {:path "missing.txt" :pattern "a" :replacement "b"}
                  {:path "small.txt" :pattern "zzz" :replacement "b"}]
   "write-file" [{:path "w.txt" :content "x"}]
   "search" [{:query "hullo"} {:query ""}]
   "get-tool-info" [{:tool-id "read-file"} {:tool-id "no-such-tool"}]
   "list-tools" [{} {:pattern "read"} {:family "no-such-family"} {:grouped false :detail true}]})

(deftest every-built-in-tool-returns-what-it-says
  ;; the prose :returns drifted from what read-file returned twice in one
  ;; week, and nothing noticed (RFC-030)
  (let [dir (fresh-dir "returns")
        _ (spit (io/file dir "small.txt") "hello\nworld")
        _ (spit (io/file dir "big.txt") (str/join "\n" (for [i (range 3000)] (str "line " i " hello"))))
        _ (spit (io/file dir "one.txt") (apply str (repeat 40000 "y")))
        events (atom [])
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir :event-log? false :max-output-chars 4000}
                             :on-event (fn [e d] (swap! events conj [e d]))})
        builtins (into tools/builtin-tools discovery/discovery-tools)]
    (testing "every built-in tool's :output-schema is a schema, with samples here"
      (is (empty? (remove schema/tool-output-schema builtins)))
      (is (= (set (map :name builtins)) (set (keys samples)))))
    (doseq [t builtins
            args (get samples (:name t))]
      (let [r (tools/call-tool (get @(:tools ctx) (:name t)) args ctx)]
        (is (nil? (schema/output-problems t r)) (str (:name t) " " (pr-str args) " → " (pr-str r)))))
    (is (empty? (filter #(= :tools/output-mismatch (first %)) @events)) "and none was reported at run time")))

(deftest a-result-unlike-its-returns-is-said-never-fatal
  (let [events (atom [])
        tool {:name "t" :input-schema [:map] :output-schema [:map [:n :int]] :fn (fn [_ _] {:n "not a number"})}]
    (is (= {:n "not a number"} (binding [schema/*record?* false]
                                 (tools/call-tool tool {} {:on-event (fn [e d] (swap! events conj [e d]))})))
        "the call's result is the tool's")
    (is (= [[:tools/output-mismatch {:tool-name "t" :problems ["`n` should be an integer"]}]] @events))))

(deftest a-returns-check-that-fails-does-not-fail-the-call
  ;; the check ran inside the tool's own try, so an :output-schema that would not
  ;; compile turned a completed call into {:error-message} (byj-gly.234)
  (let [tool {:name "t" :input-schema [:map] :output-schema [:map [:a :no-such-type]] :fn (fn [_ _] {:a 1})}]
    (is (= {:a 1} (tools/call-tool tool {} {})))))

(deftest a-user-tools-output-schema-is-checked-by-tool-test
  (let [dir (fresh-dir "user-returns")
        reg (toolbox/registry tools/builtin-tools)
        _ (swap! reg into (map (juxt :name identity)) (toolbox/authoring-tools reg (toolbox/tools-dir dir)))
        ctx {:config {:working-dir dir :project-dir dir} :tools reg}
        cmd (fn [n args] (tools/call-tool (get @reg n) args ctx))]
    (cmd "tool$create" {:name "half" :input-schema "[:map [:n :int]]" :output-schema "[:map [:half :int]]"
                        :body "(fn [{:keys [n]}] {:half (/ n 2)})"})
    (is (= "{:half int}" (schema/output-text (:output-schema (get @reg "user$tool$half")))) "listed as the line written from it")
    (is (= {:half 2} (cmd "tool$test" {:name "half" :args {:n 4}})))
    (let [r (binding [schema/*record?* false] (cmd "tool$test" {:name "half" :args {:n 3}}))]
      (is (= ["`half` should be an integer"] (:output-problems r)) (pr-str r))
      (is (some? (:result r)) "beside the result itself"))
    (testing "a prose :output-schema is refused where a tool is made (byj-gly.263)"
      (doseq [c ["tool$create" "tool$validate"]]
        (let [r (cmd c {:name "twice" :input-schema "[:map [:n :int]]"
                        :output-schema "{:twice int} or {:error-message string}"
                        :body "(fn [{:keys [n]}] {:twice (str n n)})"})]
          (is (str/includes? (str (:error r)) "output-schema is not a malli schema") (str c " " (pr-str r)))))
      (is (nil? (get @reg "user$tool$twice")) "and nothing is registered")
      (is (not (.exists (io/file (toolbox/tools-dir dir) "twice.edn"))) "or saved"))
    (testing "an [:or] with the error branch is taken, and a blank one is none"
      (is (:valid (cmd "tool$validate" {:name "twice" :output-schema "[:or [:map [:twice :string]] [:map [:error-message :string]]]"
                                        :body "(fn [_] {:twice \"x\"})"})))
      (is (:valid (cmd "tool$validate" {:name "twice" :output-schema "  " :body "(fn [_] 1)"}))))
    (testing "a tool already on disk with prose still loads, listed as written, and nothing checks it"
      (let [tdir (toolbox/tools-dir dir)]
        (spit (io/file tdir "twice.edn") (pr-str {:name "twice" :description "" :input-schema [:map [:n :int]] :output-schema "{:twice int}"}))
        (spit (io/file tdir "twice.clj") "(fn [{:keys [n]}] {:twice (str n n)})")
        (is (some #{"user$tool$twice"} (:loaded (toolbox/load-user-tools! reg tdir))))
        (is (= "{:twice int}" (schema/output-text (:output-schema (get @reg "user$tool$twice")))))
        (is (= {:twice "33"} (cmd "tool$test" {:name "twice" :args {:n 3}})))))))

;; --- signatures (byj-gly.203) -----------------------------------------------------

(deftest a-json-schema-is-written-from-a-malli-schema
  (testing "as the hand-written ones read: type first, property names strings"
    (is (= {:type "array"
            :items {:type "object"
                    :properties {"text" {:type "string"}
                                 "via" {:type "string" :enum ["edit" "check" "read" "manual"]}}
                    :required ["text"]}}
           (schema/json-schema [:vector [:map [:text :string] [:via {:optional true} [:enum "edit" "check" "read" "manual"]]]])))
    (is (= [:type :description] (keys (schema/json-schema [:string {:description "d"}])))))
  (testing "a registry reference is inlined: one flat document, no $ref"
    (let [js (schema/json-schema :brainyard.schema/tool-calls)]
      (is (= "array" (:type js)))
      (is (= ["name" "args"] (get-in js [:items :required])))
      (is (not (str/includes? (pr-str js) "$ref"))))))
