;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.init-test
  "/init (RFC-040): the check of a machine and a project, on stand-in tools on
  a PATH of the test's own and projects of the test's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.effect :as fx]
            [brainyard.flow :as flow]
            [brainyard.init :as init]
            [brainyard.trajectory :as trajectory]
            [brainyard.json :as json]))

(defn- fresh-dir [name]
  (let [d (io/file "target/init-test" name)]
    ;; rm, not File.delete over file-seq: that walks into a symlinked
    ;; directory and leaves the link behind
    (fx/sh {:out :string :err :string} "rm" "-rf" (.getPath d))
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- tool!
  "An executable `name` in `bin` that runs `script` (bash)."
  [bin name script]
  (let [f (io/file bin name)]
    (spit f (str "#!/bin/bash\n" script "\n"))
    (.setExecutable f true)
    (.getCanonicalPath f)))

(defn- listing [dir]
  ;; a turn's own trajectory under .brainyard/sessions is not a write of /init's
  (into (sorted-map) (for [f (file-seq (io/file dir))
                           :when (and (.isFile f) (not (str/includes? (.getPath f) "/.brainyard/sessions/")))]
                       [(.getPath f) (slurp f)])))

(defn- version-of
  "`init/version-of` with a bound six suites' load does not outrun: a stand-in
  script once took longer than its 5 s to start (byj-gly.432). Each one here
  answers at once, so the bound costs nothing."
  [exe argv]
  (init/version-of exe argv {:timeout-ms 60000}))

(deftest a-tool-is-found-with-its-version-or-said-how-to-install
  (let [bin (fresh-dir "bin")]
    (tool! bin "tmux" "[ \"$1\" = -V ] && echo 'tmux 3.6a' || exit 1")
    (tool! bin "bd" "echo 'bd version 1.3.0 (Homebrew)'")
    (tool! bin "jolt" "echo 'jolt v0.8.15'")
    (tool! bin "quiet" "true")
    (testing "found on the PATH given, by a scan"
      (is (= (str bin "/tmux") (init/on-path "tmux" bin)))
      (is (nil? (init/on-path "nope" bin))))
    (testing "the version is the first version-like token, read with the tool's own flag"
      (is (= "3.6a" (version-of (str bin "/tmux") ["-V"])))
      (is (= "1.3.0" (version-of (str bin "/bd") ["--version"])))
      (is (= "0.8.15" (version-of (str bin "/jolt") ["--version"])))
      (is (nil? (version-of (str bin "/quiet") ["--version"])) "one that says nothing has none"))
    (testing "a missing tool is reported with the install hints for the platform, and nothing more"
      (let [t (init/tool-status {:name "codex" :command "codex" :need :optional :for "x"
                                 :install {:macos ["brew install --cask codex"] :linux ["npm i -g @openai/codex"]}}
                                {:path bin :plat :linux})]
        (is (= {:name "codex" :command "codex" :need :optional :for "x" :available? false
                :install ["npm i -g @openai/codex"]}
               t))))
    (testing "a found one carries its path and version"
      (is (= [true "3.6a"] ((juxt :available? :version)
                            (init/tool-status {:name "tmux" :command "tmux" :version ["-V"]} {:path bin})))))
    (testing "every requirement names its command, need, purpose, and a hint for macOS"
      (doseq [r @init/requirements]
        (is (every? r [:name :command :version :need :for]) (:name r))
        (is (#{:required :feature :optional} (:need r)) (:name r))
        (is (seq (get-in r [:install :macos])) (:name r))))))

(deftest a-tool-that-says-how-to-ask-is-checked-signed-in
  ;; a requirement's :signed-in is how to ask the tool: exit 0 is signed in
  ;; (RFC-040); none of byj's own requirements names another program's
  ;; binary (byj-ahf.7)
  (let [bin (fresh-dir "bin-signed")
        req {:name "probe" :command "probe" :version ["version"] :need :optional :for "x" :signed-in ["whoami"]}]
    (tool! bin "probe" "case \"$1\" in version) echo 'probe 0.1.0';; whoami) exit 0;; esac")
    (let [s (init/tool-status req {:path bin})]
      (is (= [true "0.1.0" true] ((juxt :available? :version :signed-in?) s)))
      (is (str/includes? (init/report-text {:tools [s] :project {}}) "probe 0.1.0, signed in")))
    (tool! bin "probe" "case \"$1\" in version) echo 'probe 0.1.0';; whoami) exit 1;; esac")
    (let [s (init/tool-status req {:path bin})]
      (is (false? (:signed-in? s)))
      (is (str/includes? (init/report-text {:tools [s] :project {}}) "not signed in — probe login")))
    (testing "missing, an optional one is never a failure, and nothing is asked"
      (let [s (init/tool-status req {:path (fresh-dir "bin-none")})]
        (is (false? (:available? s)))
        (is (not (contains? s :signed-in?)))))
    (testing "byj's requirements name no program built on byj"
      (is (empty? (filter #(re-find #"^byj-" (:name %)) @init/requirements))))))

(deftest a-version-is-asked-once-per-file
  (let [bin (fresh-dir "bin-counted")
        runs (io/file bin "runs")
        script #(str "echo x >> " (.getPath runs) "\necho 'counted " % "'")
        exe (tool! bin "counted" (script "1.0.0"))
        ran #(count (str/split-lines (slurp runs)))]
    (is (= "1.0.0" (version-of exe ["--version"])))
    (is (= "1.0.0" (version-of exe ["--version"])))
    (is (= 1 (ran)) "the second ask is answered from the first (byj-gly.382)")
    (testing "an upgrade replaces the file, and the new one is asked"
      (Thread/sleep 5)
      (tool! bin "counted" (script "2.0.0"))
      (.setLastModified (io/file exe) (+ 2000 (.lastModified (io/file exe))))
      (is (= "2.0.0" (version-of exe ["--version"])))
      (is (= 2 (ran))))))

(deftest the-check-only-reads
  (let [bin (fresh-dir "bin-check")
        project (fresh-dir "project")
        bd-log (str bin "/bd.log")]
    (tool! bin "git" "exec /usr/bin/git \"$@\"")
    (tool! bin "bd" (str "echo \"$*\" >> " bd-log "\n"
                         "case \"$1 $2\" in\n"
                         "  'setup claude') echo '✓ Project hooks installed'; echo '⚠ Claude Code integration installed but stale: AGENTS.md';;\n"
                         "  'setup codex') echo '✓ ok';;\n"
                         "  *) echo 'bd version 1.3.0';;\n"
                         "esac"))
    (tool! bin "codex" "echo 'codex-cli 0.159.2'")
    (spit (io/file project "AGENTS.md") "# Agent Instructions\n")
    (spit (io/file project "CLAUDE.md") "# Project instructions\n\n@AGENTS.md\n")
    (spit (io/file project ".gitignore") "target/\n.brainyard/\n")
    (.mkdirs (io/file project ".beads"))
    (doseq [[root skill] [[".agents/skills" "beads"] [".claude/skills" "mine"]]]
      (.mkdirs (io/file project root skill))
      (spit (io/file project root skill "SKILL.md") (str "---\nname: " skill "\n---\n")))
    (.mkdirs (io/file project ".agents/skills/not-a-skill"))
    (let [before (listing project)
          r (init/check {} {:dir project :path bin})
          p (:project r)]
      (testing "the project: its instruction files, beads with bd's own word on it, its skills, .gitignore"
        (is (= [true true true true] ((juxt :agents-md? :claude-md? :claude-imports? :beads?) p)))
        (is (true? (:git? p)) "inside this repository, as git says, though it has no .git of its own")
        (is (true? (:gitignored? p)))
        (is (= {:claude false :codex true} (update-vals (:bd-setup p) :ok?)) "stale is not ok")
        (is (str/includes? (get-in p [:bd-setup :claude :said]) "installed but stale"))
        (is (= [".claude/mine" ".agents/beads"] (:skills p))))
      (testing "the tools: found, or missing with how to install — required ones listed apart"
        (is (= ["tmux" "ttyd" "claude" "gh"] (map :name (remove :available? (:tools r)))))
        (is (= [] (:missing-required r)))
        (is (str/includes? (:text r) "✗ tmux — missing"))
        (is (str/includes? (:text r) "nothing is installed by this check"))
        (is (str/includes? (:text r) "bd setup claude: needs attention")))
      (testing "and nothing was written: not a file in the project, and bd asked only to check"
        (is (= before (listing project)))
        (is (every? #(or (str/ends-with? % "--check") (= "--version" %)) (str/split-lines (slurp bd-log))))))
    (testing "a CLAUDE.md that does not import AGENTS.md, a .gitignore without .brainyard/"
      (is (false? (init/imports-agents-md? "see AGENTS.md")))
      (is (true? (init/imports-agents-md? "x\n@./AGENTS.md\n")))
      (is (false? (init/ignores-brainyard? ".brainyard-old/\n")))
      (is (true? (init/ignores-brainyard? "/.brainyard\n"))))
    (testing "a required tool missing is said so"
      (is (= ["git" "bd"] (:missing-required (init/check {} {:dir project :path (fresh-dir "empty-bin")})))))))

;; --- writing the starting files (byj-gly.309.3) ------------------------------------------

(deftest a-second-run-changes-nothing
  (let [project (fresh-dir "fresh")]
    (testing "a dry run says what it would write, and writes nothing"
      (let [r (init/scaffold {} {:dir project :dry-run true})]
        (is (= [:create :create :create] (map :action (:writes r))))
        (is (str/includes? (:text r) "AGENTS.md: would be created"))
        (is (empty? (listing project)))))
    (testing "a new project gets every file, from the templates"
      (let [r (init/scaffold {} {:dir project})]
        (is (= [["AGENTS.md" :create] ["CLAUDE.md" :create] [".gitignore" :create]]
               (map (juxt :path :action) (:writes r))))
        (is (nil? (:snapshot r)) "nothing was there to keep"))
      (is (init/imports-agents-md? (slurp (io/file project "CLAUDE.md"))))
      (is (init/ignores-brainyard? (slurp (io/file project ".gitignore"))))
      (is (= ["BRAINYARD:project" "BRAINYARD"] (map first (init/marked-blocks (slurp (io/file project "AGENTS.md")))))
          "the project's section a placeholder until a draft replaces it"))
    (testing "run again, it writes nothing"
      (let [before (listing project)
            r (init/scaffold {} {:dir project})]
        (is (every? #{:unchanged} (map :action (:writes r))))
        (is (nil? (:snapshot r)))
        (is (= before (listing project)))))))

(deftest skills-are-linked-for-claude-code
  (let [project (fresh-dir "skills-links")]
    (doseq [n ["a" "b" "c"]]
      (.mkdirs (io/file project ".agents/skills" n))
      (when-not (= n "c") (spit (io/file project ".agents/skills" n "SKILL.md") "---\n")))
    (.mkdirs (io/file project ".claude/skills/b"))
    (testing "each skill under .agents/skills without one of its name under .claude/skills is linked; a real one is left"
      (is (= [".claude/skills/a"] (map :path (init/skill-links project))) "c has no SKILL.md, b is there already")
      (let [r (init/scaffold {} {:dir project})]
        (is (str/includes? (:text r) ".claude/skills/a: created — a link to ../../.agents/skills/a, for Claude Code"))
        (is (.isFile (io/file project ".claude/skills/a/SKILL.md")))
        (is (.isDirectory (io/file project ".claude/skills/b")))))
    (testing "and again: nothing more to link"
      (is (empty? (init/skill-links project))))))

(deftest only-the-brainyard-block-is-written
  (let [project (fresh-dir "existing")
        mine "# Our rules\n\nNever deploy on Fridays.\n\n<!-- BEGIN BEADS INTEGRATION v:1 -->\nbd's own\n<!-- END BEADS INTEGRATION -->\n"
        agents #(slurp (io/file project "AGENTS.md"))]
    (spit (io/file project "AGENTS.md") mine)
    (spit (io/file project "CLAUDE.md") "Read the docs.\n")
    (spit (io/file project ".gitignore") "target/\n")
    (let [r (init/scaffold {} {:dir project})]
      (testing "the person's text and bd's block are kept as they were; brainyard's block is added"
        (is (str/starts-with? (agents) (str/trimr mine)))
        (is (= ["BRAINYARD"] (map first (init/marked-blocks (agents))))))
      (testing "the import added to a CLAUDE.md without it, and .brainyard/ to .gitignore"
        (is (= "Read the docs.\n\n@AGENTS.md\n" (slurp (io/file project "CLAUDE.md"))))
        (is (str/starts-with? (slurp (io/file project ".gitignore")) "target/\n"))
        (is (init/ignores-brainyard? (slurp (io/file project ".gitignore")))))
      (testing "what was there is kept in a snapshot first"
        (is (= mine (slurp (io/file (:snapshot r) "AGENTS.md"))))
        (is (= "Read the docs.\n" (slurp (io/file (:snapshot r) "CLAUDE.md"))))))
    (testing "an edit inside brainyard's block is rewritten; one outside it stays"
      (spit (io/file project "AGENTS.md")
            (-> (agents) (str/replace "Never deploy on Fridays." "Never deploy on Mondays.")
                (str/replace "## Working here with brainyard" "## Edited by hand")))
      (init/scaffold {} {:dir project})
      (is (str/includes? (agents) "Never deploy on Mondays."))
      (is (str/includes? (agents) "## Working here with brainyard"))
      (is (not (str/includes? (agents) "## Edited by hand"))))
    (testing "the project's own section, agent$init's draft, in a block of its own before brainyard's"
      (init/scaffold {} {:dir project :notes "## This project\n\nBuild with make."})
      (is (= ["BRAINYARD:project" "BRAINYARD"] (map first (init/marked-blocks (agents)))))
      (init/scaffold {} {:dir project :notes "## This project\n\nBuild with just."})
      (is (str/includes? (agents) "Build with just."))
      (is (not (str/includes? (agents) "Build with make.")) "replaced, not added again")
      (is (str/includes? (agents) "bd's own"))
      (testing "and a run with no draft leaves the draft, never the placeholder over it"
        (init/scaffold {} {:dir project})
        (is (str/includes? (agents) "Build with just."))
        (is (not (str/includes? (agents) "drafts this block from the repository")))))))

;; --- beads, by bd (byj-gly.309.4) --------------------------------------------------------

(defn- fake-bd
  "A stand-in for bd: `init` makes .beads/, puts its block in CLAUDE.md as bd
  does in one that does not import AGENTS.md (and here, in one that does, to
  see it removed), and commits its own files; `setup R --check` says stale
  until `setup R` has run. Every call logged."
  [bin log]
  (tool! bin "bd" (str "echo \"$*\" >> " log "\n"
                       "case \"$1\" in\n"
                       "  init) mkdir -p .beads .agents/skills/beads && echo db > .beads/metadata.json && echo '---' > .agents/skills/beads/SKILL.md\n"
                       "        printf '\\n<!-- BEGIN BEADS INTEGRATION v:1 -->\\n## Beads\\n<!-- END BEADS INTEGRATION -->\\n' >> CLAUDE.md\n"
                       "        git add .beads CLAUDE.md && git -c user.name=t -c user.email=t@x.invalid commit -qm 'bd init: initialize beads issue tracking'; echo initialized;;\n"
                       "  setup) if [ \"$3\" = --check ]; then [ -f .beads/setup-$2 ] && echo '✓ installed' || echo '⚠ installed but stale'\n"
                       "         else touch .beads/setup-$2; echo \"✓ $2 integration installed\"; fi;;\n"
                       "esac")))

(deftest beads-is-set-up-by-bd
  (let [bin (fresh-dir "bin-bd")
        log (str bin "/bd.log")
        project (fresh-dir "beads-project")
        calls #(vec (str/split-lines (slurp log)))]
    (tool! bin "git" "exec /usr/bin/git \"$@\"")
    (fake-bd bin log)
    (spit (io/file project "CLAUDE.md") "@AGENTS.md\n")
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (fx/sh {:dir project :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-q" "--allow-empty" "-m" "start")
    (testing "no bd: nothing is run, and it says why"
      (is (= "bd is not installed" (:skipped (init/beads {} {:dir project :path (fresh-dir "no-bd")}))))
      (is (not (.exists (io/file project ".beads")))))
    (let [r (init/beads {} {:dir project :path bin :prefix "demo" :codex? false})]
      (testing "bd init, non-interactive, with the prefix; then bd's Claude Code setup, as its check asked"
        (is (= [{:argv "bd init --non-interactive --init-if-missing --prefix demo" :ok? true}
                {:argv "bd setup claude" :ok? true}]
               (:ran r)))
        (is (= ["init --non-interactive --init-if-missing --prefix demo" "setup claude --check" "setup claude"] (calls))))
      (testing "the block bd put in a CLAUDE.md that imports AGENTS.md is removed"
        (is (= "@AGENTS.md\n" (slurp (io/file project "CLAUDE.md"))))
        (is (str/includes? (:text r) "bd's block removed from CLAUDE.md")))
      (testing "bd's skill linked where Claude Code reads skills (byj-gly.309.9)"
        (is (= [".claude/skills/beads"] (:links r)))
        (is (= "../../.agents/skills/beads"
               (str (java.nio.file.Files/readSymbolicLink (.toPath (io/file project ".claude/skills/beads"))))))
        (is (.isFile (io/file project ".claude/skills/beads/SKILL.md"))))
      (testing "the commit bd made of its own files is said"
        (is (= 1 (count (:commits r))))
        (is (str/includes? (:text r) "bd committed its own files"))))
    (testing "run again: bd is only asked to check — init is not run over .beads/"
      (spit log "")
      (let [r (init/beads {} {:dir project :path bin :codex? true})]
        (is (= [{:argv "bd setup codex" :ok? true}] (:ran r)) "codex installed now: its setup, which was missing")
        (is (= ["setup claude --check" "setup codex --check" "setup codex"] (calls)))
        (is (empty? (:commits r))))
      (spit log "")
      (is (str/includes? (:text (init/beads {} {:dir project :path bin :codex? true})) "already in place"))
      (is (every? #(str/ends-with? % "--check") (calls))))
    (testing "bd's blocks removed, and only those"
      (is (= "a\n\nc\n" (init/remove-beads-blocks "a\n\n<!-- BEGIN BEADS INTEGRATION v:1 -->\nb\n<!-- END BEADS INTEGRATION -->\n\nc\n"))))))

;; --- agent$init: the loop, and installing (byj-gly.309.5) --------------------------------

(deftest installing-asks
  (let [bin (fresh-dir "bin-install")
        log (str bin "/brew.log")
        asked (atom [])
        ask (fn [answer] {:permission-fn (fn [req] (swap! asked conj (:display req)) answer)})
        ;; brew's hints, on any platform: Linux's are apt's, which only a
        ;; person runs (byj-ahf.29)
        install #(with-redefs [init/platform (constantly :macos)]
                   (init/install {} %1 {:tools %2 :path bin}))]
    (tool! bin "brew" (str "echo \"$*\" >> " log "; [ \"$HOMEBREW_NO_AUTO_UPDATE\" = 1 ] || exit 9"))
    (tool! bin "git" "echo 'git version 2.50.1'")
    (testing "headless: nothing is run, and the command is given"
      (let [r (install {} "tmux")]
        (is (str/includes? (:text r) "tmux: not installed (there is no person to ask in this run) — to install it: brew install tmux"))
        (is (not (.exists (io/file log))))))
    (testing "a no: nothing is run"
      (install (ask {:denied true :reason "not now"}) "tmux")
      (is (= ["install tmux with `brew install tmux`"] @asked))
      (is (not (.exists (io/file log)))))
    (testing "a yes: installed with the package manager, asking nothing of its own"
      (let [r (install (ask {:allowed true}) ["tmux"])]
        (is (= ["tmux"] (:installed r)))
        (is (= "install tmux\n" (slurp log)))))
    (testing "one installed already, one unknown, and one only the person can run, are only said"
      (reset! asked [])
      (let [r (install (ask {:allowed true}) "git, nope claude")]
        (is (empty? @asked))
        (is (str/includes? (:text r) "git: already installed (2.50.1)"))
        (is (str/includes? (:text r) "nope: not one of the tools /init knows"))
        (is (str/includes? (:text r) "claude: run it yourself — curl -fsSL https://claude.ai/install.sh | bash"))))))

(defn- init-mock
  "init/plan answered by `plan`; the draft by a fixed section."
  [plan seen]
  (fn [messages]
    (let [sys (str (:content (first messages)))]
      (json/write-str
       (cond
         (str/includes? sys "You prepare a project directory for brainyard")
         (do (swap! seen conj :plan) (merge {:reasoning "r" :install "" :prefix "" :onboard false} plan))
         (str/includes? sys "Write the section of AGENTS.md")
         (do (swap! seen conj :draft) {:reasoning "r" :notes "## This project\n\nA demo. Test with `make test`."
                                         :examples "run make test and fix what fails\nexplain the Makefile\nadd a lint target"})
         :else {:reasoning "r" :answer "?"})))))

(defn- run-init [project question plan seen]
  (let [steps (atom [])]
    [(agent/ask question
                {:lm {:provider :mock :format :mock :responses (init-mock plan seen)}
                 :tree (agent/load-tree "brainyard/loops/init.edn")
                 :on-event (fn [e d] (when (= e :agent.tree/step-start) (swap! steps conj (:name d))))
                 :config {:project-dir project :working-dir project :init-tools? true}})
     @steps]))

(deftest init-sets-up-a-project-and-reports-what-it-did
  (let [project (fresh-dir "agent-init")
        seen (atom [])]
    (spit (io/file project "Makefile") "test:\n\techo ok\n")
    (testing "set up: the draft from the survey, the files written with it, then the check again"
      (let [[r steps] (run-init project "Prepare this project for brainyard." {:scaffold true :beads false :draft true} seen)]
        (is (= ["init$check" "init/plan" "init$survey" "init/agents-md" "init$scaffold" "init$check" "init$next"] steps))
        (is (= [:plan :draft] @seen))
        (is (str/includes? (slurp (io/file project "AGENTS.md")) "A demo. Test with `make test`."))
        (is (= 1 (count (re-seq #"## This project" (slurp (io/file project "AGENTS.md"))))) "the draft in place of the placeholder")
        (is (str/starts-with? (:answer r) "Wrote, in "))
        (is (str/includes? (:answer r) "AGENTS.md: created"))
        (is (str/includes? (:answer r) "Tools:\n") "and where things stand now")
        (is (str/includes? (:answer r) "✓ AGENTS.md"))
        (is (str/includes? (:answer r) "\n\nNext:\n- Ask about the code") "then what to do next")
        (is (str/includes? (:answer r) "  - \"explain the Makefile\"") "with the draft's examples for this project")
        (is (= "run make test and fix what fails" (:next-user-prompt r))
            "the first of them offered as the next prompt")))
    (testing "only a check: nothing written, the report is the answer"
      (reset! seen [])
      (let [before (listing project)
            [r steps] (run-init project "check" {:scaffold false :beads false :draft false} seen)]
        (is (= ["init$check" "init/plan" "init$check" "init$next"] steps))
        (is (str/starts-with? (:answer r) "Tools:\n"))
        (is (str/includes? (:answer r) "\n\nNext:\n") "the check, then what to do next")
        (testing "its commands are in the turn's record, not only its answer (byj-gly.322)"
          ;; this run's record: the last written (the set-up above wrote one too)
          (let [rec (last (sort-by :ts (for [sid (trajectory/sessions {:project-dir project})
                                             rec (trajectory/read-trajectories {:project-dir project} sid)]
                                         rec)))]
            (is (= [["tool" ["init$check"]] ["tool" ["init$check"]] ["tool" ["init$next"]] ["answer" []]]
                   (for [it (:iterations rec)] [(:channel it) (mapv :name (:tools it))])))))
        (is (= before (listing project)))))))

(deftest the-loop-files-starter-issues-when-the-plan-says
  (let [project (fresh-dir "agent-init-onboard")
        [r steps] (run-init project "Prepare this project for brainyard." {:scaffold false :beads false :draft false :onboard true} (atom []))]
    (is (= ["init$check" "init/plan" "init$onboard" "init$check" "init$next"] steps))
    ;; why, whichever comes first here: no bd on a machine without it (a CI
    ;; runner), else no .beads in the project (byj-ahf.29)
    (is (re-find #"^Starter issues not filed: (bd is not installed|beads is not set up here)\." (:answer r))
        "what it did, said first")))

(deftest the-init-loop-is-sound
  (is (nil? (:problems (flow/check-tree-name "brainyard/loops/init.edn"
                                             (flow/ctx-opts (agent/make-ctx {:config {:init-tools? true}})))))))

(deftest the-check-says-what-the-planner-decides-from
  ;; byj-gly.315.8, .10: whether there is code to describe, whether the
  ;; project section was drafted, and what /init left uncommitted
  (let [project (fresh-dir "check-facts")
        status #(init/project-status project)]
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (testing "an empty repository: no build files, no section"
      (is (= [] (:build-files (status))))
      (is (nil? (:project-section (status)))))
    (init/scaffold {} {:dir project})
    (testing "after the scaffold: the section is the placeholder, and /init's files are uncommitted"
      (is (= :placeholder (:project-section (status))))
      (is (= #{"AGENTS.md" "CLAUDE.md" ".gitignore"}
             (set (map #(str/replace % #"^\S+\s+" "") (:uncommitted (status))))))
      (is (str/includes? (init/report-text {:tools [] :project (status)}) "not drafted yet")))
    (testing "code appears; a draft replaces the placeholder; a commit leaves nothing uncommitted"
      (spit (io/file project "package.json") "{}")
      (is (= ["package.json"] (:build-files (status))))
      (is (str/includes? (init/report-text {:tools [] :project (status)}) "not drafted yet — /init drafts it from the code")
          "said for what is there: the screen pass found 'once it has code' under a code line naming package.json")
      (init/scaffold {} {:dir project :notes "## This project\n\nA demo."})
      (is (= :drafted (:project-section (status))))
      (fx/sh {:dir project :out :string :err :string} "git" "add" "-A")
      (fx/sh {:dir project :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-qm" "x")
      (is (= [] (:uncommitted (status))))
      (is (not (str/includes? (init/report-text {:tools [] :project (status)}) "left uncommitted"))))))

(deftest next-steps-follow-what-the-project-has
  ;; byj-gly.337.1: /init's answer ends with what to do next, chosen by what
  ;; the project has after /init, the first suggestion offered as the next prompt
  (let [project (fresh-dir "next-steps")
        next! #(init/next-text (init/project-status project))
        ids #(set (for [s @init/next-steps :when (every? (init/next-facts (init/project-status project)) (:when s))] (:id s)))]
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (init/scaffold {} {:dir project})
    (testing "an empty repository just set up: commit /init's files, say what to build"
      (is (= #{:commit :start-empty :help} (ids)))
      (is (str/starts-with? (:text (next!)) "Next:\n- Commit what /init wrote"))
      (is (str/includes? (:text (next!)) "`git add -- .gitignore AGENTS.md CLAUDE.md && git commit -m \"Set up brainyard\"`")
          "naming the files git has not got")
      (is (str/includes? (:text (next!)) "\n  - \"I want to build <what>.") "example prompts under it")
      (is (= "I want to build" (:prompt (next!)))))
    (testing "code under a placeholder section: /init again first"
      (spit (io/file project "package.json") "{}")
      (is (= #{:commit :draft :start-code :help} (ids)))
      (is (= "/init" (:prompt (next!)))))
    (testing "drafted, committed, beads: the code's examples and beads, nothing to commit"
      (init/scaffold {} {:dir project :notes "## This project\n\nA demo."})
      (.mkdirs (io/file project ".beads"))
      (fx/sh {:dir project :out :string :err :string} "git" "add" "AGENTS.md" "CLAUDE.md" ".gitignore" "package.json")
      (fx/sh {:dir project :out :string :err :string} "git" "-c" "user.name=t" "-c" "user.email=t@x.invalid" "commit" "-qm" "x")
      (is (= #{:start-code :beads :help} (ids)))
      (is (str/includes? (:text (next!)) "\"bd ready\" lists what is ready"))
      (is (= "explain how this project is organized, and how to build and test it" (:prompt (next!)))))
    (testing "the draft's examples for this project replace the generic ones, the first suggested (byj-gly.337.3)"
      (let [n (init/next-text (init/project-status project)
                              {:examples "1. explain how src/server.js routes requests\n- \"run npm test and fix what fails\"\n\nadd a README section on DB_FILE\nand a fourth"})]
        (is (str/includes? (:text n) "  - \"explain how src/server.js routes requests\"\n  - \"run npm test and fix what fails\"\n  - \"add a README section on DB_FILE\""))
        (is (not (str/includes? (:text n) "a fourth")) "three at most")
        (is (not (str/includes? (:text n) "review my uncommitted changes")))
        (is (= "explain how src/server.js routes requests" (:prompt n)))))
    (testing "an entry is shown only when every fact it names holds; none shown is no text"
      (is (= {:prompt "" :text ""} (init/next-text {} {:steps [{:id :x :when #{:beads} :text "x"}]}))))))

(defn- stateful-bd
  "A bd in `bin` that keeps the issues it creates, one id a line, in
  `bin`/issues: `create` prints the next id, `list` the kept ones as JSON
  (`[]` before any), every call logged to `bin`/bd.log."
  [bin]
  (tool! bin "bd" (str "d='" bin "'; echo \"$*\" >> \"$d/bd.log\"; touch \"$d/issues\"\n"
                       "case \"$1\" in\n"
                       "  create) n=$(( $(wc -l < \"$d/issues\") + 1 )); echo \"t-$n\" >> \"$d/issues\"; echo \"t-$n\";;\n"
                       "  list) printf '['; sep=''; while read -r id; do printf '%s{\"id\":\"%s\",\"status\":\"open\"}' \"$sep\" \"$id\"; sep=','; done < \"$d/issues\"; echo ']';;\n"
                       "esac")))

(deftest starter-issues-are-filed-once
  ;; byj-gly.337.2: bd ready in a project /init just set up showed nothing to do
  (let [bin (fresh-dir "bin-onboard")
        project (fresh-dir "onboard")
        calls #(vec (str/split-lines (slurp (io/file bin "bd.log"))))
        missing [{:name "codex" :for "Codex sessions" :install "`brew install --cask codex`"}]]
    (stateful-bd bin)
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (init/scaffold {} {:dir project})
    (testing "no beads: nothing filed"
      (is (= "beads is not set up here" (:skipped (init/onboard {} {:dir project :path bin :missing missing})))))
    (.mkdirs (io/file project ".beads"))
    (let [r (init/onboard {} {:dir project :path bin :missing missing})
          creates (filter #(str/starts-with? % "create") (calls))]
      (testing "an empty repository: the epic, then what to do first, the draft after the goal, a missing tool"
        (is (= ["t-1" "t-2" "t-3" "t-4" "t-5" "t-6"] (:filed r)))
        (is (= 6 (count creates)))
        (is (every? #(str/includes? % "--labels brainyard-onboarding") creates) "each labelled, so a second run can tell")
        (is (str/includes? (first creates) "--title Get started with brainyard --type epic"))
        (is (every? #(str/includes? % "--parent t-1") (rest creates)) "the rest are its children")
        (is (some #(str/includes? % "--title Commit the files /init wrote") creates) "/init's files are uncommitted")
        (is (some #(str/includes? % "--title Run /init again once there is code") creates) "no code yet")
        (is (some #(str/includes? % "--title Install codex (optional) — Codex sessions") creates))
        (is (some #(str/includes? % "--title Say what this project is for") creates))
        (is (not-any? #(str/includes? % "byj run") creates)
            "the person is already in a session: the goal issue does not send them to start one")
        (is (some #{"dep add t-5 t-4"} (calls)) "the draft waits on the goal")
        (is (str/includes? (:text r) "`bd ready` shows the ones ready to start")))
      (testing "the next steps point at them, and suggest bd ready"
        (let [n (init/next-step {} {:dir project :path bin})]
          (is (str/includes? (:text n) "Start with the starter issues /init filed"))
          (is (= "run bd ready and show me the starter issues" (:prompt n))))))
    (testing "a second run files nothing"
      (spit (io/file bin "bd.log") "")
      (let [r (init/onboard {} {:dir project :path bin :missing missing})]
        (is (= "already filed" (:skipped r)))
        (is (not-any? #(str/starts-with? % "create") (calls)))))
    (testing "a project with code: no draft issue, since /init drafted it"
      (let [bin2 (fresh-dir "bin-onboard-code")
            p2 (fresh-dir "onboard-code")]
        (stateful-bd bin2)
        (fx/sh {:dir p2 :out :string :err :string} "git" "init" "-q")
        (spit (io/file p2 "package.json") "{}")
        (.mkdirs (io/file p2 ".beads"))
        (init/onboard {} {:dir p2 :path bin2 :missing []})
        (is (not (str/includes? (slurp (io/file bin2 "bd.log")) "Run /init again")))))))

(deftest byj-init-says-what-to-do-next
  ;; byj-gly.337.4: the model-free entry point ends as /init does
  (let [project (fresh-dir "byj-init-next")
        init-command (requiring-resolve 'brainyard.main/init-command)
        out (with-out-str (init-command {:check true :questions [project]} {}))]
    (is (str/includes? out "Project "))
    (is (str/includes? out "\n\nNext:\n") "after the check")
    (is (str/includes? out "Say what you want to build") "an empty directory's steps"))
  (testing "the template's brainyard block tells a newcomer where to start"
    (is (str/includes? (:text (first (filter #(= "AGENTS.md" (:path %)) @init/templates)))
                       "New here? `bd ready` lists what to start on"))))
