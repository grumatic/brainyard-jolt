;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.discovery-test
  "Tool discovery (RFC-007): brainyard's list-tools, get-tool-info and search
  over the live registry, and how the loop offers them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.discovery :as discovery]
            [brainyard.json :as json]
            [brainyard.schema :as schema]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/discovery-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- spit-in [dir path content]
  (let [f (io/file dir path)] (.mkdirs (.getParentFile f)) (spit f content)))

(defn- setup
  "The registry make-ctx builds — builtins, discovery, commands — plus one user
  tool, and a caller for any tool in it."
  [name]
  (let [dir (fresh-dir name)
        reg (toolbox/registry (into tools/builtin-tools discovery/discovery-tools))
        ctx {:config {:working-dir dir :project-dir dir} :tools reg}
        _ (swap! reg into (map (juxt :name identity)) (toolbox/authoring-tools reg (toolbox/tools-dir dir)))
        call (fn [n args] (tools/call-tool (get @reg n) args ctx))]
    (call "tool$create" {:name "count-lines" :description "Count lines.\nZebra line."
                         :input-schema "[:map [:path :string]]" :output-schema "[:map [:lines :int]]"
                         :body "(fn [{:keys [path]}] {:lines (count (clojure.string/split-lines (:content (read-file {:path path}))))})"})
    {:dir dir :reg reg :call call}))

(deftest list-tools
  (let [{:keys [call]} (setup "list")]
    (testing "no args: a summary — each family, its count and a few ids — and how to narrow it"
      (let [{:keys [total families next]} (call "list-tools" {})]
        (is (= 17 total))
        (is (= ["_ungrouped" "tool" "user"] (map :family families)) "ungrouped first, then by name")
        (is (= {:family "user" :count 1 :tools ["user$tool$count-lines"]}
               (first (filter #(= "user" (:family %)) families))))
        (is (every? #(<= (count (:tools %)) 6) families) "a few ids a family, never all of them")
        (is (some #(:more %) families) "and a count of the rest")
        (is (not-any? :description (mapcat :tools families)) "no descriptions: the summary is for choosing")
        (is (str/includes? next ":family"))
        (is (str/includes? next "get-tool-info"))))
    (testing "type alone narrows the summary"
      (is (= ["tool"] (map :family (:families (call "list-tools" {:type "command"}))))))
    (testing "family: that family's tools, flat, each with the first line of its description"
      (is (= [{:id "user$tool$count-lines" :type "tool" :description "Count lines."}]
             (call "list-tools" {:family "user"})))
      (is (str/includes? (:error-message (call "list-tools" {:family "nope"})) "no tool family nope")))
    (testing "a pattern is a case-insensitive regex over id and description, flat"
      (is (= [{:id "user$tool$count-lines" :type "tool" :description "Count lines."}]
             (call "list-tools" {:pattern "^USER\\$"})))
      (is (= ["user$tool$count-lines"] (map :id (call "list-tools" {:pattern "zebra"})))
          "the whole description is searched, though only its first line is returned")
      (is (= ["grep"] (map :id (call "list-tools" {:pattern "extended regex" :type "tool"})))))
    (testing "grouped false is flat; detail inlines the input schema and returns"
      (is (= 17 (count (call "list-tools" {:grouped false}))))
      (is (= {:id "read-file" :type "tool"}
             (select-keys (first (call "list-tools" {:pattern "^read-file$" :detail true})) [:id :type])))
      (is (= [:description :id :input-schema :output-schema :type]
             (sort (keys (first (call "list-tools" {:pattern "^read-file$" :detail true})))))))))

(deftest a-flat-listing-fits-in-one-result
  ;; 192 tools flat were 31,000 characters: cut, spilled, and paged back in
  ;; four calls to answer "list all tools" (byj-gly.191)
  (let [{:keys [reg]} (setup "fit")
        listing (fn [cap] (discovery/list-tools {:grouped false} {:config {:max-output-chars cap} :tools reg}))
        room #(int (* 0.85 %))]
    (testing "with room, every tool with its description"
      (is (vector? (listing 16000)))
      (is (= 17 (count (listing 16000)))))
    (testing "short of room, the descriptions get shorter before they go"
      (let [full (count (pr-str (listing 16000)))
            r (listing (int (/ full 0.85 1.1)))]
        (is (vector? r))
        (is (<= (count (pr-str r)) (room (int (/ full 0.85 1.1)))))))
    (testing "short of room even for that, every id by family, and how to get the descriptions"
      (let [r (listing 1400)]
        (is (<= (count (pr-str r)) (room 1400)))
        (is (= 17 (:total r)))
        (is (= 17 (count (mapcat val (:tools r)))) "no tool is left out")
        (is (= ["user$tool$count-lines"] (get-in r [:tools "user"])))
        (is (str/includes? (:note r) "{:family"))))
    (testing "with :detail, the whole of it when it fits, else the ids and how to narrow it"
      (let [detail (fn [cap] (discovery/list-tools {:grouped false :detail true} {:config {:max-output-chars cap} :tools reg}))]
        (is (vector? (detail 1000000)))
        (is (every? :input-schema (detail 1000000)))
        (let [r (detail 3000)]
          (is (<= (count (pr-str r)) (room 3000)))
          (is (= 17 (count (mapcat val (:tools r)))))
          (is (str/includes? (:note r) "with their parameters they do not fit")))))))

(deftest an-mcp-servers-tools-are-a-family-of-their-own
  (is (= "mcp$linear" (discovery/tool-family "mcp$linear$get_issue")))
  (is (= "mcp" (discovery/tool-family "mcp$servers")) "the discovery command stays in mcp")
  (is (= "config" (discovery/tool-family "config$read")))
  (is (= "_ungrouped" (discovery/tool-family "bash"))))

(deftest get-tool-info
  (let [{:keys [call]} (setup "info")]
    (is (= {:id "read-file" :type "tool" :effects [:fs/read]}
           (select-keys (call "get-tool-info" {:tool-id "read-file"}) [:id :type :effects])))
    (is (= [:path :offset :limit :column] (map first (rest (:input-schema (call "get-tool-info" {:tool-id "read-file"}))))))
    (testing "a user tool shows its calls and the effects they bring"
      (is (= {:type "tool" :output-schema [:map [:lines :int]] :calls ["read-file"] :effects [:fs/read]}
             (select-keys (call "get-tool-info" {:tool-id "user$tool$count-lines"}) [:type :output-schema :calls :effects]))))
    (is (= "command" (:type (call "get-tool-info" {:tool-id "tool$create"}))))
    (is (= {:error-message "tool(ID:nope) is not defined in system"} (call "get-tool-info" {:tool-id "nope"})))
    (is (str/includes? (:error-message (call "get-tool-info" {})) "missing required `tool-id`"))))

(deftest search
  (let [{:keys [dir call]} (setup "search")]
    (spit-in dir "README.md" "x")
    (spit-in dir "src/app/core.clj" "x")
    (spit-in dir "src/app/core_test.clj" "x")
    (spit-in dir ".git/refs/core" "x")
    (testing "tokens are ANDed, case-insensitive; top-level files count, .git does not"
      (is (= ["src/app/core.clj" "src/app/core_test.clj"] (:project-files (call "search" {:query "APP core"}))))
      (is (= ["README.md"] (:project-files (call "search" {:query "readme"}))))
      (is (= ["src/app/core_test.clj"] (:project-files (call "search" {:query "core test"})))))
    (testing "tools match by id or description"
      (is (= ["user$tool$count-lines"] (map :id (:tools (call "search" {:query "count zebra"}))))))
    (testing "tokens under 3 chars are dropped; nothing left means no sections"
      (is (= {} (call "search" {:query "a to"})))
      (is (= (call "search" {:query "readme"}) (call "search" {:query "readme a"}))))
    (testing "a section with no match is absent"
      (is (= #{:tools} (set (keys (call "search" {:query "extended regex"}))))))))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest the-loop-offers-discovery
  (let [dir (fresh-dir "loop")
        prompts (atom [])
        replies (atom [(reply {:tool-calls [{:tool-name "tool$create"
                                             :tool-args {:name "shout" :input-schema "[:map [:text :string]]"
                                                         :body "(fn [{:keys [text]}] {:loud (clojure.string/upper-case text)})"}}]})
                       (reply {:tool-calls [{:tool-name "list-tools" :tool-args {:pattern "shout"}}]})
                       (reply {:answer "done"})])
        lm {:provider :mock :format :mock
            :responses (fn [msgs] (swap! prompts conj msgs)
                         (let [[x] (swap-vals! replies #(vec (rest %)))] (first x)))}
        r (agent/ask "q" {:lm lm :config {:working-dir dir :project-dir dir :max-iterations 5}})
        system (:content (first (first @prompts)))]
    (testing "a tool created this turn is not listed yet, but list-tools finds it"
      (is (not (str/includes? system "user$tool$shout")))
      (is (str/includes? (:tool-result (first (:tool-results (second (:trajectory r)))))
                         "user$tool$shout")))
    (testing "the prompt carries brainyard's discovery text and lists the tools"
      (is (str/includes? system "### Discovery (other registered tools)"))
      (is (str/includes? system "### Bootstrap Tools (always bound)"))
      (is (str/includes? system "discover it with `list-tools` /\n`get-tool-info`"))
      (is (every? #(str/includes? system (str "- `" % "` —")) ["list-tools" "get-tool-info" "search"])))
    (testing ":tool-discovery? false removes the tools and the Discovery section"
      (let [{:keys [tools zones]} (agent/make-ctx {:config {:working-dir dir :project-dir dir :tool-discovery? false}})]
        (is (not (contains? @tools "list-tools")))
        (is (not (str/includes? (:session-context zones) "### Discovery")))))))

(deftest searching-skips-the-agents-own-state
  (let [{:keys [dir call]} (setup "agent-state")]
    (spit-in dir "src/a.clj" "TODO visible\n")
    (call "tool$create" {:name "noted" :description "TODO in a tool"
                         :body "(fn [_] {:todo \"TODO in a body\"})"})
    (testing "grep over the project does not match .brainyard, where the agent's own tools live"
      (is (= ["./src/a.clj:1:TODO visible"] (:matches (call "grep" {:pattern "TODO"})))))
    (testing "but a grep aimed at .brainyard still works"
      (is (seq (:matches (call "grep" {:pattern "TODO" :path ".brainyard"})))))
    (testing "search's project files skip it too"
      (is (= ["src/a.clj"] (:project-files (call "search" {:query "clj"})))))
    (testing "list-dir still shows it — it is a real entry, and hiding it would mislead"
      (is (contains? (set (:entries (call "list-dir" {:path "."}))) ".brainyard/")))))


;; --- the bound listing (RFC-003) ------------------------------------------------------

(deftest the-tool-listing-is-one-line-each
  (let [zone #(:session-context (:zones (agent/make-ctx {:config (merge {:working-dir "."} %)})))
        compact (zone {})
        verbose (zone {:compact-agent-tools false})]
    (testing "by default a tool costs a line, and its arguments come from get-tool-info"
      (is (str/includes? compact "`get-tool-info` gives a tool's arguments"))
      (is (str/includes? compact "- `read-file` — Read file content. With offset/limit, returns numbered lines.")
          "the one line is doing real work: it is where the argument names are")
      (is (not (str/includes? compact "  Inputs:")) "the argument list is what moved to pull"))

    (testing "but the RESULT shape stays: omitting it cost two wrong answers once (RFC-010)"
      (doseq [t tools/builtin-tools :when (:output-schema t)]
        (is (str/includes? compact (str "Outputs: " (schema/output-text (:output-schema t))))
            (str (:name t) " keeps its result shape")))
      (is (< (count (re-seq #"Outputs:" compact)) (count (re-seq #"Outputs:" verbose)))
          "one fewer: a prose `Outputs:` buried in tool$create's 21-line description is not a declared shape"))

    (testing "and it is smaller by enough to matter"
      (is (< (count compact) (* 0.7 (count verbose)))))

    (testing "with discovery off it goes back to full specs — there would be no way to a schema"
      (let [no-discovery (zone {:tool-discovery? false})]
        (is (str/includes? no-discovery "full specs"))
        (is (str/includes? no-discovery "  Inputs:"))))))

(deftest the-prompt-lists-a-family-a-line
  (let [zone #(:session-context (:zones (agent/make-ctx {:config (merge {:working-dir "."} %)})))
        by-family (zone {})
        by-line (zone {:tool-listing-by-family? false})]
    (testing "the primitives keep a line each, result shapes and all"
      (is (str/includes? by-family "- `read-file` — Read file content."))
      (doseq [t tools/builtin-tools :when (:output-schema t)]
        (is (str/includes? by-family (str "Outputs: " (schema/output-text (:output-schema t)))) (:name t))))
    (testing "every other family is one line: what it is for, then its ids in full"
      (is (re-find #"- `config\$…` \(6\) — read and change this project's settings[^\n]*`config\$read`" by-family))
      (is (str/includes? by-family "`tool$create`"))
      (is (str/includes? by-family "Tool Lisp") "what a family is for is said, not left to its ids")
      (is (not (str/includes? by-family "- `config$read` —")) "not a line per tool"))
    (testing "and says how to get the rest"
      (is (str/includes? by-family "get-tool-info"))
      (is (str/includes? by-family "list-tools {\"family\"")))
    (testing "it is much smaller, and the per-line listing is one setting away"
      ;; the tools' section, not the zone: the zone also holds this
      ;; repository's AGENTS.md, which grew until the whole no longer showed
      ;; the difference (byj-ctk)
      (let [tools-part #(second (re-find #"(?s)(## Tools.*?)## Instructions" %))]
        (is (< (count (tools-part by-family)) (* 0.6 (count (tools-part by-line))))))
      (is (str/includes? by-line "- `config$read` —"))))
  (testing "an MCP server is a family named as that server, its ids capped"
    (let [ts (for [i (range 30)] {:name (str "mcp$linear$tool_" i) :description "x"})
          out (discovery/format-tools-by-family ts)]
      (is (str/includes? out "`mcp$linear$…` (30) — tools of the MCP server linear"))
      (is (str/includes? out "… and 18 more"))
      (is (= 1 (count (re-seq #"mcp\$linear\$…" out)))))))

(deftest many-hits-come-back-in-shorter-lines
  (let [ctx (agent/make-ctx {:config {:working-dir "."}})
        long (apply str (repeat 30 "word "))]
    (doseq [i (range 25)]
      (swap! (:tools ctx) assoc (str "zz$t" i) {:name (str "zz$t" i) :description long :kind :command}))
    (let [hits ((:fn (get @(:tools ctx) "list-tools")) {:pattern "^zz\\$"} ctx)]
      (is (= 25 (count hits)))
      (is (every? #(<= (count (:description %)) 100) hits) "past twenty hits, a description is cut to a hundred characters")
      (is (str/ends-with? (:description (first hits)) "…")))
    (let [few ((:fn (get @(:tools ctx) "list-tools")) {:pattern "^zz\\$t1$"} ctx)]
      (is (= (str/trim long) (:description (first few))) "a few hits keep their whole first line"))))
