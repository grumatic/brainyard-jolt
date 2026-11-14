;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.skills-test
  "Skills (RFC-017): parsing SKILL.md, the roots and their precedence, ranked
  search, and the progressive disclosure the whole design rests on."
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.discovery :as discovery]
            [brainyard.json]
            [brainyard.self-improve :as self-improve]
            [brainyard.skills :as skills]
            [brainyard.tools :as tools]))

(defn- fresh-dir [name]
  (let [d (io/file "target/skills-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- only
  "Config that sees this fixture's skills and nothing this machine happens to
  have under ~/.claude or ~/.agents, and no skills from resources unless asked
  (`:builtin-skills? true`)."
  [dir & {:as more}]
  (merge {:working-dir dir :project-dir dir
          :skill-roots [(str dir "/.brainyard/skills")]
          :builtin-skills? false}
         more))

(defn- skill! [dir name text]
  (let [f (io/file dir ".brainyard" "skills" name "SKILL.md")]
    (.mkdirs (.getParentFile f))
    (spit f text)
    f))

(def ^:private with-frontmatter
  (str "---\nname: lint-markdown\ndescription: Lint markdown files and fix what it reports.\n"
       "tags: markdown, lint\n---\n\n# Lint Markdown\n\n1. Run scripts/lint.sh\n2. Fix what it reports\n"))

(def ^:private without-frontmatter
  "# run-bash Skill\n\nExecute shell commands from the sandbox.\n\n## Details\nmore\n")

(deftest parsing-a-skill-md
  (testing "frontmatter gives the title, description and tags"
    (let [s (skills/parse-skill "lint-markdown" with-frontmatter)]
      (is (= "lint-markdown" (:name s)))
      (is (= "lint-markdown" (:title s)) "frontmatter name is a title source")
      (is (= "Lint markdown files and fix what it reports." (:description s)))
      (is (= ["markdown" "lint"] (:tags s)))
      (is (str/includes? (:content s) "scripts/lint.sh") "the content is the whole file")))
  (testing "without frontmatter: the heading is the title, the first real line the description"
    (let [s (skills/parse-skill "run-bash" without-frontmatter)]
      (is (= "run-bash Skill" (:title s)))
      (is (= "Execute shell commands from the sandbox." (:description s)))
      (is (= [] (:tags s)))))
  (testing "nothing is required, and odd frontmatter still loads"
    (let [s (skills/parse-skill "bare" "just prose\n")]
      (is (= "bare" (:title s)) "falls back to the directory name")
      (is (= "just prose" (:description s))))
    (let [s (skills/parse-skill "unterminated" "---\nname: x\n\n# Heading\nbody\n")]
      (is (str/includes? (:content s) "name: x"))
      (is (= "unterminated" (:name s)))))
  (testing "dispatch: agent is read, since brainyard runs those in a sub-agent"
    (is (= "agent" (:dispatch (skills/parse-skill "heavy" "---\ndispatch: agent\n---\n# Heavy\ndo it\n"))))))

(deftest brainyards-own-skill-file-loads-unchanged
  (let [dir (fresh-dir "brainyards")
        src (io/file "test/fixtures/skills/by-rebuild-stamp-verify-loop/SKILL.md")
        f (io/file dir ".brainyard" "skills" "by-rebuild-stamp-verify-loop" "SKILL.md")]
    (.mkdirs (.getParentFile f))
    (spit f (slurp src))
    (let [[s] (skills/list-skills (only dir))]
      (is (= "by-rebuild-stamp-verify-loop" (:name s)))
      (is (str/starts-with? (:description s) "Ship a source fix in the Brainyard Polylith workspace"))
      (is (= "brainyard/project" (:source s)))
      (is (= (slurp src) (:content s)) "byte for byte"))))

(deftest roots-and-precedence
  (let [dir (fresh-dir "roots")]
    (skill! dir "alpha" with-frontmatter)
    (.mkdirs (io/file dir ".brainyard" "skills" "no-skill-md"))
    (testing "a directory without SKILL.md is skipped, not an error"
      (is (= ["alpha"] (map :name (skills/list-skills (only dir))))))
    (testing "a name defined twice keeps the most local, and the other stays reachable"
      (let [resolved (skills/resolve-names [{:name "dup" :backend :brainyard :scope :project}
                                            {:name "dup" :backend :claude}
                                            {:name "solo" :backend :agents}])]
        (is (= #{"skill$dup" "skill$claude$dup" "skill$solo"} (set (map :id resolved))))
        (is (= {"skill$dup" false "skill$claude$dup" true "skill$solo" false}
               (into {} (map (juxt :id (comp boolean :shadowed?)) resolved)))
            "the local one keeps the plain id; the other is shadowed but reachable")))))

(deftest the-user-roots-follow-the-skills-home
  (let [home (fresh-dir "skills-home")
        proj (fresh-dir "skills-home-project")
        was (System/getProperty "brainyard.skills-home")]
    (let [f (io/file home ".claude" "skills" "from-home" "SKILL.md")]
      (.mkdirs (.getParentFile f))
      (spit f "---\nname: from-home\ndescription: A skill in the given home.\n---\n# From home\n"))
    (try
      (System/setProperty "brainyard.skills-home" (.getCanonicalPath (io/file home)))
      (let [c {:working-dir proj :project-dir proj :builtin-skills? false}
            user (filter #(= :user (:scope %)) (skills/roots c))]
        (is (seq user))
        (is (every? #(str/starts-with? (.getCanonicalPath (:dir %)) (.getCanonicalPath (io/file home))) user)
            "every user root is under the property's home, none under $HOME")
        (is (= ["from-home"] (map :name (skills/list-skills c)))
            "and what is listed is that home's, nothing of the person's"))
      (finally
        (if was (System/setProperty "brainyard.skills-home" was) (System/clearProperty "brainyard.skills-home"))))
    (testing "the suite's runner sets it to an empty home of its own"
      (when was
        (is (str/ends-with? was "test-skills-home"))))))

(deftest finding-a-skill
  (let [dir (fresh-dir "find")]
    (skill! dir "lint-markdown" with-frontmatter)
    (skill! dir "deploy" "---\nname: deploy\ndescription: Ship the service to production.\n---\n# Deploy\n")
    (let [c (only dir)]
      (is (= ["lint-markdown"] (map :name (skills/find-skills c "markdown"))))
      (is (= ["deploy"] (map :name (skills/find-skills c "deploy the service"))))
      (testing "a name match outranks a description match"
        (is (= "deploy" (:name (first (skills/find-skills c "deploy production"))))))
      (testing "short tokens are dropped and a weak match is not a match"
        (is (empty? (skills/find-skills c "a to")))
        (is (empty? (skills/find-skills c "unrelated nonsense"))))
      (testing "an empty result means there is no such skill — not an error"
        (is (= [] (vec (skills/find-skills c "quantum")))))) ))

(deftest a-skill-is-a-tool-whose-body-arrives-only-when-called
  (let [dir (fresh-dir "call")
        _ (skill! dir "lint-markdown" with-frontmatter)
        ctx (agent/make-ctx {:config (only dir)})
        reg (:tools ctx)
        call (fn [n args] (tools/call-tool (get @reg n) args ctx))]
    (testing "it is in the registry, typed as a skill, so discovery can filter on it"
      (is (contains? @reg "skill$lint-markdown"))
      (is (= "skill" (discovery/tool-type (get @reg "skill$lint-markdown"))))
      ;; type alone gives the grouped index, as brainyard does; ask flat for ids
      (is (= ["skill$lint-markdown"] (map :id (call "list-tools" {:type "skill" :grouped false})))))
    (testing "the description says what calling it does, without the body"
      (let [d (:description (get @reg "skill$lint-markdown"))]
        (is (str/includes? d "Lint markdown files"))
        (is (str/includes? d "loads this skill's procedure"))
        (is (not (str/includes? d "scripts/lint.sh")) "the steps are not in the description")))
    (testing "calling it returns the whole SKILL.md"
      (let [r (call "skill$lint-markdown" {})]
        (is (true? (:loaded r)))
        (is (str/includes? (:content r) "1. Run scripts/lint.sh"))
        (is (str/ends-with? (:path r) "lint-markdown/SKILL.md"))))
    (testing "the prompt names it but never carries its body"
      (let [system (:session-context (:zones ctx))]
        (is (str/includes? system "### Skills"))
        (is (str/includes? system "`skill$lint-markdown` — Lint markdown files and fix what it reports.")
            "what it is for, not its name again — its title is only its name (byj-gly.336)")
        (is (not (str/includes? system "1. Run scripts/lint.sh"))
            "the body is what a call is for — this is the whole point")))
    (testing "the commands list, find and read"
      (is (= 1 (:count (call "skills$list" {}))))
      (is (= ["lint-markdown"] (map :name (:result (call "skills$find" {:query "markdown"})))))
      (is (= ["skill$lint-markdown"] (map :id (:result (call "skills$find" {:query "markdown"}))))
          "a hit carries the id that calls it, as skills$list's do (byj-gly.200)")
      (is (str/includes? (:content (call "skills$read" {:skill-name "lint-markdown"})) "scripts/lint.sh"))
      (is (= {:error "query is required"} (call "skills$find" {:query "  "})))
      (is (str/includes? (:error (call "skills$read" {:skill-name "nope"})) "no skill named nope")))
    (testing "in brainyard's shapes: {:result :count}, the backend as :type and its :scope (byj-gly.202)"
      (let [[e] (:result (call "skills$list" {}))]
        (is (= {:name "lint-markdown" :type "brainyard" :scope "project" :id "skill$lint-markdown"}
               (select-keys e [:name :type :scope :id])))
        (is (str/ends-with? (:path e) "SKILL.md")))
      (is (= 1 (:count (call "skills$list" {:type "brainyard" :scope "project"}))))
      (is (= 0 (:count (call "skills$list" {:type "claude"}))) "a :type filter")
      (is (= 0 (:count (call "skills$list" {:scope "user"}))) "a :scope filter")
      (let [r (call "skills$read" {:skill-name "lint-markdown"})]
        (is (= ["brainyard" "project"] [(:type r) (:scope r)]))
        (is (pos? (:size r)))))))

(deftest the-prompt-cost-is-bounded
  (let [dir (fresh-dir "many")]
    (doseq [i (range 50)]
      (skill! dir (str "skill-" i) (str "---\nname: skill-" i "\ndescription: " (apply str (repeat 400 "x")) "\n---\n# S" i "\n")))
    (let [ctx (agent/make-ctx {:config (only dir :max-listed-skills 40)})
          system (:session-context (:zones ctx))
          without (:session-context (:zones (agent/make-ctx {:config (only dir :skills? false)})))]
      (is (str/includes? system "…and 10 more — `skills$find` searches them all"))
      (is (< (- (count system) (count without)) 6000)
          "50 skills cost a few thousand characters, not the 400-char descriptions of all of them")
      (is (= 50 (count (filter #(str/starts-with? % "skill$") (keys @(:tools ctx)))))
          "while every one of them is still callable"))))

;; --- dispatch: agent (RFC-018) ------------------------------------------------------

(def ^:private dispatching-skill
  (str "---\nname: release-notes\ndescription: Draft release notes from the log.\n"
       "dispatch: agent\n---\n\n# Release Notes\n\n1. Read the log\n2. Group by area\n"))

(defn- reply [m]
  (brainyard.json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(deftest a-skill-may-run-itself-in-an-agent
  (let [dir (fresh-dir "dispatch")
        _ (skill! dir "release-notes" dispatching-skill)
        prompts (atom [])
        replies (atom [(reply {:answer "## 0.2.0\n- the thing"})])
        lm {:provider :mock :format :mock
            :responses (fn [msgs]
                         (swap! prompts conj (:content (first msgs)))
                         (first (first (swap-vals! replies #(vec (rest %))))))}
        ctx (agent/make-ctx {:config (only dir) :lm lm})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))]
    (testing "its description says calling it runs it, rather than loading it"
      (let [d (:description (get @(:tools ctx) "skill$release-notes"))]
        (is (str/includes? d "runs in its own agent"))
        (is (not (str/includes? d "loads this skill's procedure")))))
    (testing "calling it runs a child turn and returns the ANSWER, not the procedure"
      (let [r (call "skill$release-notes" {:question "notes for 0.2.0"})]
        (is (= "## 0.2.0\n- the thing" (:answer r)))
        (is (true? (:dispatched r)))
        (is (= "release-notes" (:skill r)))
        (is (nil? (:content r)) "the body stayed in the child")
        (is (nil? (:loaded r)))))
    (testing "the child was told it is executing the skill, and given its definition"
      (let [child (first @prompts)]
        (is (str/includes? child "You are executing the skill `release-notes`."))
        (is (str/includes? child "## SKILL DEFINITION"))
        (is (str/includes? child "1. Read the log"))))
    (testing "it needs a question — there is nothing to run without one"
      (let [r (call "skill$release-notes" {})]
        (is (str/includes? (:error r) "declares `dispatch: agent`, so it needs a question"))
        (is (nil? (:answer r)))))
    (testing "it leaves no instance behind: a procedure is not a colleague to keep on hand"
      (is (empty? @(:subagent-registry ctx))))))

(deftest a-dispatching-skill-falls-back-when-subagents-are-off
  (let [dir (fresh-dir "dispatch-off")
        _ (skill! dir "release-notes" dispatching-skill)
        ctx (agent/make-ctx {:config (only dir :enable-subagent-calls false)})
        r (tools/call-tool (get @(:tools ctx) "skill$release-notes") {:question "notes"} ctx)]
    (is (true? (:loaded r)) "the procedure is handed over instead")
    (is (str/includes? (:content r) "1. Read the log"))
    (is (str/includes? (:note r) "subagents are off — follow the procedure yourself"))))

;; --- project-scope harness skills ----------------------------------------------------

(deftest a-project-may-keep-skills-where-its-other-harnesses-do
  (let [dir (fresh-dir "harness-roots")
        write! (fn [root name text]
                 (let [f (io/file dir root name "SKILL.md")]
                   (.mkdirs (.getParentFile f))
                   (spit f text)))
        ;; a name no machine-level root can be holding
        n "byj-fixture-proc"
        config {:working-dir dir :project-dir dir}]
    (write! ".claude/skills" n "---\ndescription: from the claude root.\n---\n# P\n")
    (write! ".agents/skills" n "---\ndescription: from the agents root.\n---\n# P\n")
    (testing "a skill written for another harness is found in the project, not only in $HOME"
      (let [found (filter #(= n (:name %)) (skills/resolve-names (skills/list-skills config)))]
        (is (= 2 (count found)))
        (is (= ["claude/project" "agents/project"] (map :source found))
            "brainyard's backend order, applied to the project")
        (is (= [(str "skill$" n) (str "skill$agents$" n)] (map :id found))
            "the most local keeps the plain id; the other stays reachable")
        (is (= [(str "skill$" n) (str "skill$agents$" n)]
               (map :id (filter #(= n (:name %)) (skills/find-skills config n))))
            "and find reaches both, each by the id that calls it")
        (let [find (:fn (first (filter #(= "skills$find" (:name %)) (skills/commands config))))
              hits (filter #(= n (:name %)) (:result (find {:query n} nil)))]
          (is (= [{:id (str "skill$" n) :type "claude" :also-in ["agents/project"]}]
                 (map #(select-keys % [:id :type :also-in]) hits))
              "skills$find is one hit per name, the most local, :also-in naming the rest — brainyard's"))))

    (testing "the project's own .brainyard/skills still wins"
      (write! ".brainyard/skills" n "---\ndescription: the project's own.\n---\n# P\n")
      (let [found (filter #(= n (:name %)) (skills/resolve-names (skills/list-skills config)))]
        (is (= "brainyard/project" (:source (first found))))
        (is (= (str "skill$" n) (:id (first found))))))

    (testing "two scopes of one backend do not collide into the same id"
      ;; brainyard/project wins, so both claude entries are shadowed: without the
      ;; scope in the id the second would replace the first in the registry
      (let [claude-user {:name n :backend :claude :scope :user :source "claude/user"}
            ids (map :id (skills/resolve-names
                          (conj (vec (filter #(= n (:name %)) (skills/list-skills config)))
                                claude-user)))]
        (is (= (count ids) (count (distinct ids))) "every id is distinct")
        (is (some #{(str "skill$claude$user$" n)} ids))))))

;; --- writing (brainyard's skills$write) ----------------------------------------------

(deftest a-skill-name-is-cleaned-not-refused
  (is (= "deploy-helper" (skills/sanitize-skill-name "  Deploy Helper! ")))
  (is (= "my-cool-skill" (skills/sanitize-skill-name "My Cool Skill!")))
  (is (= "a-b" (skills/sanitize-skill-name "--a -- b--")))
  (is (nil? (skills/sanitize-skill-name "!!!"))))

(deftest skills-write-creates-updates-and-removes-a-skill
  (let [dir (fresh-dir "write")
        ctx (agent/make-ctx {:config (only dir)})
        reg (:tools ctx)
        call (fn [n args] (tools/call-tool (get @reg n) args ctx))]
    (testing "it is the agent's, and declares that it writes"
      (is (contains? @reg "skills$write"))
      (is (contains? @reg "skills$reload"))
      (is (contains? (:effects (get @reg "skills$write")) :fs/write)))
    (testing "create writes the SKILL.md as given, in the project, and it is callable at once"
      (let [r (call "skills$write" {:op "create" :skill-name "Lint Markdown" :content with-frontmatter
                                    :scripts {"lint.sh" "echo lint"} :resources {"rules.txt" "MD001"}})]
        (is (= {:name "lint-markdown" :title "lint-markdown" :description "Lint markdown files and fix what it reports."
                :type "brainyard" :scope "project" :id "skill$lint-markdown"}
               (select-keys r [:name :title :description :type :scope :id])))
        (is (string? (:created r)))
        (is (= with-frontmatter (slurp (io/file dir ".brainyard/skills/lint-markdown/SKILL.md"))))
        (is (= "echo lint" (slurp (io/file dir ".brainyard/skills/lint-markdown/scripts/lint.sh"))))
        (is (.canExecute (io/file dir ".brainyard/skills/lint-markdown/scripts/lint.sh")) "a script is made executable")
        (is (= "MD001" (slurp (io/file dir ".brainyard/skills/lint-markdown/resources/rules.txt"))))
        (is (str/includes? (:content (call "skill$lint-markdown" {})) "1. Run scripts/lint.sh"))))
    (testing "create refuses what exists, what is not brainyard's, and what is missing"
      (is (str/includes? (:error (call "skills$write" {:op "create" :skill-name "lint-markdown" :content "x"}))
                         "Skill already exists: lint-markdown"))
      (is (str/includes? (:error (call "skills$write" {:op "create" :skill-name "x" :content "x" :type "claude"}))
                         "only supports :brainyard type"))
      (is (= "content is required" (:error (call "skills$write" {:op "create" :skill-name "x"}))))
      (is (= "Invalid skill name" (:error (call "skills$write" {:op "create" :skill-name "!!!" :content "x"}))))
      (is (str/includes? (:error-message (call "skills$write" {:op "rename" :skill-name "x"}))
                         "should be either \"create\", \"update\" or \"remove\""))
      (is (str/includes? (:error (skills/write! (only dir) nil nil {:op "rename" :skill-name "x"}))
                         "Unknown :op 'rename'")))
    (testing "an extra cannot be written outside its skill"
      (call "skills$write" {:op "update" :skill-name "lint-markdown" :resources {"../../escape.txt" "no"}})
      (is (not (.exists (io/file dir ".brainyard/skills/escape.txt"))))
      (is (.exists (io/file dir ".brainyard/skills/lint-markdown/resources/escape.txt"))))
    (testing "update replaces the procedure, and the next call reads the new one"
      (let [r (call "skills$write" {:op "update" :skill-name "lint-markdown"
                                    :content (str/replace with-frontmatter "Fix what it reports" "Fix it all")})]
        (is (string? (:updated r)))
        (is (str/includes? (:content (call "skill$lint-markdown" {})) "Fix it all")))
      (is (= "Brainyard skill not found: nope" (:error (call "skills$write" {:op "update" :skill-name "nope"})))))
    (testing "remove deletes it and unregisters it"
      (is (= {:deleted "lint-markdown" :type "brainyard" :scope "project"}
             (call "skills$write" {:op "remove" :skill-name "lint-markdown"})))
      (is (not (.exists (io/file dir ".brainyard/skills/lint-markdown"))))
      (is (not (contains? @reg "skill$lint-markdown"))))
    (testing "a SKILL.md written some other way is registered by skills$reload"
      (skill! dir "run-bash" without-frontmatter)
      (is (= {:registered ["skill$run-bash"] :unregistered [] :total 1}
             (select-keys (call "skills$reload" {}) [:registered :unregistered :total])))
      (is (contains? @reg "skill$run-bash")))))

(deftest skill-authoring-can-be-switched-off
  (let [dir (fresh-dir "no-authoring")
        reg (:tools (agent/make-ctx {:config (only dir :skill-authoring? false)}))]
    (is (contains? @reg "skills$find"))
    (is (not-any? #(contains? @reg %) ["skills$write" "skills$reload" "skills$install" "skills$import" "skills$sync" "skills$search"]))))

;; --- install, import, sync (byj-gly.248) ------------------------------------------------

(deftest a-skill-is-imported-from-a-path
  (let [dir (fresh-dir "import")
        ctx (agent/make-ctx {:config (only dir)})
        reg (:tools ctx)
        call (fn [n args] (tools/call-tool (get @reg n) args ctx))
        md "---\nname: Tidy Imports\ndescription: Sort and group namespace requires.\n---\n\n# Tidy imports\n1. sort them\n"]
    (.mkdirs (io/file dir "vendor/tidy"))
    (spit (io/file dir "vendor/tidy/SKILL.md") md)
    (spit (io/file dir "vendor/bare.md") "# No frontmatter\njust text\n")
    (testing "a directory holding a SKILL.md, relative to the working directory: named by its frontmatter, callable at once"
      (let [r (call "skills$import" {:path "vendor/tidy"})]
        (is (= {:name "tidy-imports" :type "brainyard" :scope "project" :id "skill$tidy-imports"}
               (select-keys r [:name :type :scope :id])))
        (is (= md (slurp (io/file dir ".brainyard/skills/tidy-imports/SKILL.md"))))
        (is (str/includes? (:content (call "skill$tidy-imports" {})) "1. sort them"))))
    (testing "the file itself, under a name given"
      (is (= "tidy" (:name (call "skills$import" {:path "vendor/tidy/SKILL.md" :name "tidy"})))))
    (testing "what the open standard asks for, or it is refused"
      (is (str/includes? (:error (call "skills$import" {:path "vendor/bare.md" :name "bare"})) "no description"))
      (is (str/includes? (:error (call "skills$import" {:path "vendor/nothing"})) "no SKILL.md"))
      (is (str/includes? (:error (call "skills$import" {:path "vendor/tidy"})) "already exists")))))

(deftest a-package-is-found-installed-and-synced-through-npx-skills
  ;; the skills CLI as it is today: find takes a query and --owner, add takes
  ;; -a for agents, update takes a scope — brainyard's --target is gone from it
  ;; (byj-gly.248, byj-gly.254)
  (let [dir (fresh-dir "install")
        ctx (agent/make-ctx {:config (only dir)})
        reg (:tools ctx)
        call (fn [n args] (tools/call-tool (get @reg n) args ctx))
        ran (atom [])
        found (str "Install with npx skills add <owner/repo@skill>\n\n"
                   "mattpocock/skills@migrate-to-shoehorn 383.8K installs\n"
                   "└ https://skills.sh/mattpocock/skills/migrate-to-shoehorn\n\n"
                   "acme/tools 12 installs\n")]
    (with-redefs [skills/run-npx! (fn [args _] (swap! ran conj (vec args)) {:result (if (= "find" (first args)) found "ok")})]
      (testing "search: the query whole, the packages read out as install takes them"
        (let [r (call "skills$search" {:query "typescript types"})]
          (is (= [{:package "mattpocock/skills@migrate-to-shoehorn" :installs "383.8K"} {:package "acme/tools" :installs "12"}]
                 (:packages r)))
          (is (str/includes? (:result r) "Install with")))
        (call "skills$search" {:query "pdf" :owner "anthropics"})
        (is (= [["find" "typescript types"] ["find" "pdf" "--owner" "anthropics"]] @ran)))
      (testing "install: add -g -y, and -a only when agents are named"
        (reset! ran [])
        (is (= "ok" (:result (call "skills$install" {:package "vercel-labs/agent-skills"}))))
        (call "skills$install" {:package "acme/skills@lint" :agent "claude-code codex"})
        (is (= [["add" "vercel-labs/agent-skills" "-g" "-y"]
                ["add" "acme/skills@lint" "-g" "-y" "-a" "claude-code" "codex"]] @ran)))
      (testing "a package is owner/repo, an agent a name, and neither ever an option to npx"
        (reset! ran [])
        (is (str/includes? (:error (call "skills$install" {:package "--registry=evil"})) "not a skill package"))
        (is (str/includes? (:error (call "skills$install" {:package "just-a-name"})) "not a skill package"))
        (is (str/includes? (:error (call "skills$install" {:package "a/b" :agent "--all"})) "not an agent name"))
        (is (str/includes? (:error (call "skills$search" {:query "x" :owner "--x"})) "not a GitHub owner"))
        (is (empty? @ran)))
      (testing "sync updates every global skill"
        (reset! ran [])
        (is (= "ok" (:result (call "skills$sync" {}))))
        (is (= [["update" "-g" "-y"]] @ran))))
    (testing "a CLI that fails is an error with what it said"
      (with-redefs [skills/run-npx! (fn [_ _] {:error "npx skills add exited 1: not found"})]
        (is (= "npx skills add exited 1: not found" (:error (call "skills$install" {:package "a/b"}))))))
    (testing "they run a process and reach the network, and say so"
      (is (every? #(contains? (:effects (get @reg "skills$install")) %) [:process :net/http :fs/write]))
      (is (= #{:process :net/http} (:effects (get @reg "skills$search"))))
      (is (= #{:fs/read :fs/write :tools/define} (:effects (get @reg "skills$import")))))))

(deftest a-skills-line-says-what-it-is-for
  ;; byj-gly.336: 51 of 55 skills on one machine listed as `skill$x — x`
  (testing "a title that says more than the name is kept"
    (is (= "Markdown linter" (skills/listing-text {:name "lint-md" :title "Markdown linter" :description "Lints."} 80))))
  (testing "a title that is only the name gives way to the description's first sentence"
    (is (= "Lint markdown files and fix what it reports."
           (skills/listing-text {:name "lint-markdown" :title "lint-markdown"
                                 :description "Lint markdown files and fix what it reports. Then commit."} 80)))
    (is (= "Record an expense" (skills/listing-text {:name "ledger" :title "Ledger" :description "Record an expense"} 80))
        "case and dashes do not make a title say more"))
  (testing "clipped, with a mark that it was"
    (let [t (skills/listing-text {:name "x" :title "x" :description (apply str (repeat 30 "word "))} 40)]
      (is (= 40 (count t)))
      (is (str/ends-with? t "…"))))
  (testing "no description: the title after all"
    (is (= "x" (skills/listing-text {:name "x" :title "x" :description ""} 80)))))

(deftest a-skill-is-found-by-what-its-description-says-it-is-for
  ;; byj-gly.375: a Claude Code or agents skill has only a name and a
  ;; description, and brainyard's flat 6 for a description hit never reached
  ;; the 10 a match needs — "release notes" did not find the changelog skill
  (let [dir (fresh-dir "find-by-description")
        _ (skill! dir "changelog" "---\nname: changelog\ndescription: Write release notes from the commits.\n---\n# Changelog\n")
        _ (skill! dir "versions" "---\nname: versions\ndescription: Bump the latest release.\n---\n# Versions\n")
        names #(set (map :name (skills/find-skills (only dir) %)))]
    (is (= #{"changelog"} (names "release notes")) "every word in the description: a match")
    (is (= #{"changelog"} (names "notes")) "one word, in it")
    (is (empty? (names "release dates")) "one word of two is not enough")
    (is (empty? (names "test")) "a word starts a word: \"test\" is not in \"latest\"")
    (is (= #{"versions"} (names "versions")) "a name still matches as before")))

;; --- built-in skills (byj-kuk) ------------------------------------------------

(def ^:private shipped
  "The shipped skill these tests stand on; any entry of the index would do."
  "skill-authoring")

(deftest the-shipped-built-in-skills-load-and-carry-no-scripts
  (let [index (clojure.edn/read-string (slurp (io/resource skills/builtin-index)))
        names (map #(if (map? %) (:name %) %) index)
        loaded (skills/builtin-skills)]
    (is (some #{shipped} names))
    (is (= names (map :name loaded)) "every entry has a SKILL.md, in the index's order")
    (doseq [s loaded]
      (is (not (str/blank? (:description s))) (str (:name s) " says what it is for"))
      (is (= [:builtin :builtin "builtin"] [(:backend s) (:scope s) (:source s)]))
      (is (str/starts-with? (:path s) "builtin:brainyard/skills/") "never a path on disk"))
    (is (not-any? #(and (map? %) (contains? % :scripts)) index)
        "no scripts: an embedded file is not on disk for bash to run (RFC-017)")
    (doseq [e index :when (map? e) r (:resources e)]
      (is (io/resource (str "brainyard/skills/" (:name e) "/resources/" r)) (str r " is shipped")))
    (testing "an index entry may name resources; one whose SKILL.md is missing is skipped"
      (let [[s & more] (skills/builtin-skills "fixtures/builtin-skills/index.edn")]
        (is (nil? more))
        (is (= "with-notes" (:name s)))
        (is (= ["fixtures/builtin-skills/with-notes/resources/notes.md"] (:resources s)))))))

(deftest a-built-in-skill-is-listed-last-and-a-project-one-shadows-it
  (let [dir (fresh-dir "builtin")
        _ (skill! dir "lint-markdown" with-frontmatter)
        ctx (agent/make-ctx {:config (only dir :builtin-skills? true)})
        reg (:tools ctx)
        call (fn [n args] (tools/call-tool (get @reg n) args ctx))
        id (str "skill$" shipped)
        text (slurp (io/resource (str "brainyard/skills/" shipped "/SKILL.md")))]
    (testing "it is listed after every root, typed and scoped builtin"
      (is (= ["lint-markdown" shipped] (map :name (skills/list-skills (only dir :builtin-skills? true)))))
      (let [e (first (filter #(= shipped (:name %)) (:result (call "skills$list" {}))))]
        (is (= {:name shipped :type "builtin" :scope "builtin" :id id}
               (select-keys e [:name :type :scope :id]))))
      (is (= [shipped] (map :name (:result (call "skills$list" {:type "builtin"})))))
      (is (= [shipped] (map :name (:result (call "skills$list" {:scope "builtin"}))))))
    (testing "skills$find and skills$read find it, and its id calls it"
      (is (= id (:id (first (:result (call "skills$find" {:query "skill authoring"}))))))
      (is (= text (:content (call "skills$read" {:skill-name shipped}))))
      (let [r (call id {})]
        (is (true? (:loaded r)))
        (is (= "builtin" (:type r)))
        (is (= text (:content r)) "byte for byte, read from resources")))
    (testing "the prompt names it in one line"
      (is (str/includes? (:session-context (:zones ctx)) (str "`" id "`"))))
    (testing "a project skill of the same name shadows it, and it stays reachable"
      (skill! dir shipped "---\nname: skill-authoring\ndescription: The project's own way.\n---\n# Ours\n")
      (let [c (only dir :builtin-skills? true)
            {:keys [tools]} (skills/skills-and-tools c)
            by-id (into {} (map (juxt :name identity)) tools)]
        (is (str/includes? (:content ((:fn (by-id id)) {} ctx)) "The project's own way."))
        (is (= text (:content ((:fn (by-id (str "skill$builtin$" shipped))) {} ctx)))
            "skill$builtin$<name> is the built-in one, not the most local of its name")
        (let [[hit] (filter #(= shipped (:name %))
                            (:result (tools/call-tool (first (filter #(= "skills$find" (:name %)) (skills/commands c)))
                                                      {:query "skill authoring"} ctx)))]
          (is (= ["builtin"] (:also-in hit)) "one hit per name, the shipped one named under :also-in"))))
    (testing ":builtin-skills? false hides it"
      (is (= ["lint-markdown" shipped] (map :name (skills/list-skills (only dir)))))
      (.delete (io/file dir ".brainyard" "skills" shipped "SKILL.md"))
      (is (= ["lint-markdown"] (map :name (skills/list-skills (only dir)))))
      (is (not (contains? @(:tools (agent/make-ctx {:config (only dir)})) id))))
    (testing "a skill's resources come with the call, read through io/resource"
      (let [orig skills/builtin-skills]
        (with-redefs [skills/builtin-skills (fn ([] (orig "fixtures/builtin-skills/index.edn")) ([i] (orig i)))]
          (let [c (only dir :builtin-skills? true)
                t (first (filter #(= "skill$with-notes" (:name %)) (:tools (skills/skills-and-tools c))))]
            (is (= {"notes.md" "The notes the fixture skill hands over.\n"} (:resources ((:fn t) {} ctx))))))))))

(deftest a-built-in-skill-is-read-only
  (let [dir (fresh-dir "builtin-write")
        c (only dir :builtin-skills? true)
        reg (atom {})
        write (fn [args] (skills/write! c reg nil args))]
    (testing "create, update and remove refuse it, and say how a person changes it"
      (doseq [[op args] [["create" {:content "---\nname: x\n---\n# x\n"}] ["update" {:content "x"}] ["remove" {}]]
              :let [e (:error (write (merge {:op op :skill-name shipped} args)))]]
        (is (str/includes? e "built-in skill") op)
        (is (str/includes? e (str ".brainyard/skills/" shipped "/SKILL.md")) op))
      (is (not (.exists (io/file dir ".brainyard" "skills" shipped))) "nothing was written")
      (is (str/includes? (:error (write {:op "update" :skill-name shipped :type "builtin"})) "read-only"))
      (is (str/includes? (:error (write {:op "remove" :skill-name shipped :scope "builtin"})) "built-in skill")))
    (testing "with the built-in skills off there is nothing to refuse"
      (is (= "skill$skill-authoring"
             (:id (skills/write! (only dir) (atom {}) nil
                                 {:op "create" :skill-name shipped :content "---\nname: skill-authoring\n---\n# Ours\n"}))))
      (testing "and the copy, once there, is the project's to update and remove"
        (is (string? (:updated (write {:op "update" :skill-name shipped :content "# Ours, again\n"}))))
        (is (= shipped (:deleted (write {:op "remove" :skill-name shipped}))))
        (is (= [:builtin] (map :backend (skills/list-skills c))) "the shipped one is back")))
    (testing "a draft named after one is never staged, and refinement never judges one"
      (is (= :builtin-name (self-improve/distill-outcome 0.7 {:reusable true :score 0.9 :proposed-name shipped
                                                               :skill-md "# x"}
                                                         (self-improve/builtin-skill-names c))))
      (is (= #{} (self-improve/builtin-skill-names (only dir))))
      (is (empty? (self-improve/refine-triggers
                   c {:terminated-by :error
                      :trajectory-iterations [{:tool-results [{:tool-name (str "skill$" shipped) :tool-error? true}]}]}))))))
