;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.init-test
  "/init (RFC-040): the check of a machine and a project, on stand-in tools on
  a PATH of the test's own and projects of the test's own."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.agent :as agent]
            [brainyard.config :as config]
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
    (spit (io/file project ".gitignore") "target/\n.brainyard/*\n!.brainyard/profile.edn\n!.brainyard/deps.edn\n")
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
      (is (nil? (init/ignores-brainyard ".brainyard-old/\n")))
      (testing "ignored whole, git cannot add the profile: not as /init writes it (byj-9ng)"
        (is (= :whole (init/ignores-brainyard "/.brainyard\n")))
        (is (= :whole (init/ignores-brainyard ".brainyard/\n!.brainyard/profile.edn\n")) "an exception under a directory ignored whole is none")
        (is (= :whole (init/ignores-brainyard ".brainyard/*\n")))
        (is (false? (init/ignores-brainyard? "/.brainyard\n"))))
      (is (= :current (init/ignores-brainyard "/.brainyard/*\n!.brainyard/profile.edn\n!/.brainyard/deps.edn\n")))
      (testing "but for the profile alone, as /init wrote it before RFC-075: the spec is ignored (byj-bo8)"
        (is (= :profile-only (init/ignores-brainyard "/.brainyard/*\n!.brainyard/profile.edn\n")))
        (is (false? (init/ignores-brainyard? ".brainyard/*\n!.brainyard/profile.edn\n")))))
    (testing "a required tool missing is said so"
      (is (= ["git" "bd"] (:missing-required (init/check {} {:dir project :path (fresh-dir "empty-bin")})))))))

;; --- writing the starting files (byj-gly.309.3) ------------------------------------------

(deftest the-gitignore-keeps-brainyard-s-state-but-not-the-profile
  ;; byj-9ng: .brainyard/ ignored whole, `git add .brainyard/profile.edn`,
  ;; /init's own next step, was refused
  (let [git #(fx/sh {:dir %1 :out :string :err :string} "git" "check-ignore" "-q" %2)
        ignored? #(zero? (:exit (git %1 %2)))]
    (testing "a new project: what is in .brainyard/ is ignored, but for the profile"
      (let [project (fresh-dir "gitignore-new")]
        (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
        (init/scaffold {} {:dir project})
        (is (str/ends-with? (slurp (io/file project ".gitignore")) "but for its profile and dependencies\n.brainyard/*\n!.brainyard/profile.edn\n!.brainyard/deps.edn\n"))
        (is (ignored? project ".brainyard/sessions/x.edn"))
        (is (ignored? project ".brainyard/config.edn"))
        (is (ignored? project ".brainyard/loops/react.edn") "a project's loop stays this machine's")
        (is (not (ignored? project ".brainyard/profile.edn")))
        (is (not (ignored? project ".brainyard/deps.edn")) "the dependencies' spec is committed (RFC-075)")
        (is (ignored? project ".brainyard/deps-local.edn") "and this machine's bindings are not")))
    (testing "an earlier /init's line becomes the one it writes now, with the exception after it, once"
      (let [project (fresh-dir "gitignore-old")]
        (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
        (spit (io/file project ".gitignore") "target/\n# brainyard's per-project state (sessions, logs, config)\n.brainyard/\n\n*.log\n")
        (init/scaffold {} {:dir project})
        (is (= "target/\n# brainyard's per-project state (sessions, logs, config)\n.brainyard/*\n!.brainyard/profile.edn\n!.brainyard/deps.edn\n\n*.log\n"
               (slurp (io/file project ".gitignore"))))
        (is (not (ignored? project ".brainyard/profile.edn")))
        (is (= :current (:gitignore (init/project-status project))))
        (is (= :unchanged (:action (first (filter #(= ".gitignore" (:path %)) (init/plan-writes project))))) "a second run writes nothing")))
    (testing "one /init wrote before RFC-075 gets the spec's exception, and says it needs it until then (byj-bo8)"
      (let [project (fresh-dir "gitignore-profile-only")]
        (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
        (spit (io/file project ".gitignore") "target/\n.brainyard/*\n!.brainyard/profile.edn\n")
        (is (= :profile-only (:gitignore (init/project-status project))))
        (is (ignored? project ".brainyard/deps.edn"))
        (let [l (some #(when (= :gitignore (:id %)) %) (init/project-checks (init/project-status project)))]
          (is (= [false "but for its profile.edn, not its deps.edn: /init adds !.brainyard/deps.edn"] ((juxt :ok :detail) l))))
        (init/scaffold {} {:dir project})
        (is (= "target/\n.brainyard/*\n!.brainyard/deps.edn\n!.brainyard/profile.edn\n" (slurp (io/file project ".gitignore"))))
        (is (not (ignored? project ".brainyard/deps.edn")))
        (is (= :current (:gitignore (init/project-status project))))))))

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
         (str/includes? sys "Draft this project's profile")
         (do (swap! seen conj :profile) {:reasoning "r" :goal "A small web workspace." :in "- server shell\n- terminal"
                                           :out "" :done "npm test passes" :preset "collaborator" :autonomy ""
                                           :issues "1. server shell\n2. terminal"})
         (str/includes? sys "Write the section of AGENTS.md")
         (do (swap! seen conj :draft) {:reasoning "r" :notes "## This project\n\nA demo. Test with `make test`."
                                         :examples "run make test and fix what fails\nexplain the Makefile\nadd a lint target"})
         :else {:reasoning "r" :answer "?"})))))

(defn- run-init [project question plan seen & [permission-fn]]
  (let [steps (atom [])]
    [(agent/ask question
                (cond-> {:lm {:provider :mock :format :mock :responses (init-mock plan seen)}
                         :tree (agent/load-tree "brainyard/loops/init.edn")
                         :on-event (fn [e d] (when (= e :agent.tree/step-start) (swap! steps conj (:name d))))
                         :config {:project-dir project :working-dir project :init-tools? true}}
                  permission-fn (assoc :permission-fn permission-fn)))
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
    (testing "/drive is offered with a profile and beads, only when the profile keeps the issues in beads or names no tracker (byj-y0u)"
      (let [f (io/file project ".brainyard" "profile.edn")
            profile! #(spit f (pr-str (merge {:profile/version 1 :preset :collaborator :goal "A demo."} %)))]
        (io/make-parents f)
        (profile! {})
        (is (contains? (ids) :drive) "no tracker named: beads, as before :issues")
        (profile! {:issues [{:tracker :beads} {:tracker :github}]})
        (is (contains? (ids) :drive))
        (is (str/includes? (:text (next!)) "/drive works through the project's ready beads") "no epic: the goal has none")
        (profile! {:issues [{:tracker :github}]})
        (is (not (contains? (ids) :drive)) "the issues are kept in GitHub: /drive would not work them")
        (.delete f)))
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

(def ^:private bd-block
  ;; bd 1.3's AGENTS.md block, the lines that set its git default
  (str "<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:0 -->\n## Agent Context Profiles\n\n"
       "- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked.\n"
       "- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close.\n"
       "<!-- END BEADS INTEGRATION -->\n"))

(deftest the-git-default-bds-block-sets-is-the-persons-to-state
  ;; byj-aww: bd's block lets agents commit only when asked, and in
  ;; simulation every run kept to it over a rule said only in the session
  ;; (RFC-040 "The Beads block's git default")
  (testing "unstated while only bd's block names a profile"
    (is (nil? (init/git-profile "# Agent Instructions\n")) "no bd block: nothing to say")
    (is (nil? (init/git-profile "- **Conservative (default)**: mine, not bd's\n")) "not in bd's block: not bd's default")
    (is (= :unstated (init/git-profile (str "# Agent Instructions\n\n" bd-block))))
    (is (= :stated (init/git-profile (str "# Agent Instructions\n\nThis project opts in to bd's Team-maintainer profile.\n\n" bd-block))))
    (is (= :stated (init/git-profile (str bd-block "\nAgents here keep the conservative profile.\n"))) "after the block counts too"))
  (let [bin (fresh-dir "bin-git-profile")
        project (fresh-dir "git-profile")]
    (stateful-bd bin)
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (init/scaffold {} {:dir project})
    (.mkdirs (io/file project ".beads"))
    (spit (io/file project "AGENTS.md") (str (slurp (io/file project "AGENTS.md")) "\n" bd-block))
    (testing "the next steps say so, with prompts that write the answer, and suggest nothing of their own"
      (let [p (init/project-status project)
            n (init/next-text p)]
        (is (= :unstated (:git-profile p)))
        (is (contains? (init/next-facts p) :git-unstated))
        (is (str/includes? (:text n) "Say whether agents here commit their own work"))
        (is (str/includes? (:text n) "opts in to bd's Team-maintainer profile"))
        (is (not (str/starts-with? (:prompt n) "write in AGENTS.md")))))
    (testing "a starter issue asks the person, once"
      (init/onboard {} {:dir project :path bin :missing []})
      (is (str/includes? (slurp (io/file bin "bd.log")) "--title Say whether agents here commit their own work --type task --priority 2")))
    (testing "stated outside the blocks: neither"
      (spit (io/file project "AGENTS.md")
            (str "# Agent Instructions\n\nThis project keeps bd's Conservative profile.\n\n" bd-block))
      (let [p (init/project-status project)]
        (is (= :stated (:git-profile p)))
        (is (not (str/includes? (:text (init/next-text p)) "Say whether agents here commit")))))))

(deftest init-writes-the-profiles-block-and-the-check-holds-it-to-the-data
  ;; byj-71w.3: the block is a rendering of .brainyard/profile.edn, never
  ;; its source; a block that differs is said, and /init writes it again
  (let [project (fresh-dir "profile-block")
        f (io/file project ".brainyard" "profile.edn")
        agents #(slurp (io/file project "AGENTS.md"))
        status #(:profile (init/project-status project))]
    (init/scaffold {} {:dir project})
    (is (nil? (status)) "no profile, no block: nothing to say")
    (is (not (str/includes? (agents) "BRAINYARD:profile")))
    (io/make-parents f)
    (spit f (pr-str {:profile/version 1 :preset :collaborator :goal "A workspace."}))
    (is (= {:block :missing :preset :collaborator :trackers []} (status)))
    (is (str/includes? (init/report-text {:tools [] :project (init/project-status project)})
                       "✗ profile: collaborator (.brainyard/profile.edn) — AGENTS.md does not show it; /init writes its block"))
    (init/scaffold {} {:dir project})
    (is (= ["BRAINYARD:project" "BRAINYARD:profile" "BRAINYARD"] (map first (init/marked-blocks (agents)))) "after the project's own section, before brainyard's")
    (is (= :current (:block (status))))
    (is (str/includes? (init/report-text {:tools [] :project (init/project-status project)})
                       "✓ profile: collaborator (.brainyard/profile.edn); AGENTS.md shows it"))
    (is (= :stated (init/git-profile (str (agents) "\n" bd-block))) "it names bd's profile, so bd's default no longer stands")
    (testing "an edit to the block is said, and rewritten from the data"
      (spit (io/file project "AGENTS.md") (str/replace (agents) "It never pushes on its own" "It pushes"))
      (is (= :stale (:block (status))))
      (init/scaffold {} {:dir project})
      (is (= :current (:block (status))))
      (is (not (str/includes? (agents) "It pushes"))))
    (testing "a change to the data is a change to the block"
      (spit f (pr-str {:profile/version 1 :preset :conservative}))
      (is (= :stale (:block (status))))
      (init/scaffold {} {:dir project})
      (is (str/includes? (agents) "keeps the Conservative profile")))
    (testing "a profile with problems is said with them"
      (spit f (pr-str {:profile/version 1 :autonomy {:git/push :act}}))
      (is (some #(str/includes? % "above its ceiling") (:problems (status)))))))

(deftest byj-profile-write-holds-the-json-to-the-schema-and-writes-the-file-and-the-block
  ;; byj-ph3: what a client's onboarding writes, the person having confirmed it
  (let [project (fresh-dir "profile-write")
        f (io/file project ".brainyard" "profile.edn")
        agents #(slurp (io/file project "AGENTS.md"))
        in (fn [s] (json/read-str s))]
    (init/scaffold {} {:dir project})
    (testing "JSON's strings become the file's keywords"
      (is (= {:profile/version 1 :preset :autopilot :goal "A RAG service." :scope {:in ["api" "index"] :out ["UI"]}
              :done ["npm test passes"] :issues [{:tracker :beads} {:tracker :github :github-repo "o/r"}]
              :autonomy {:decision/local :ask}}
             (init/profile-from-json (in "{\"preset\":\"autopilot\",\"goal\":\"A RAG service.\",\"scope\":{\"in\":[\"api\",\"index\"],\"out\":[\"UI\"]},\"done\":[\"npm test passes\"],\"issues\":[{\"tracker\":\"beads\"},{\"tracker\":\"github\",\"github-repo\":\"o/r\"}],\"autonomy\":{\"decision/local\":\"ask\"}}")))))
    (testing "written: the file, and AGENTS.md's block from it"
      (let [r (init/profile-write project (in "{\"preset\":\"collaborator\",\"goal\":\"A RAG service.\",\"issues\":[{\"tracker\":\"beads\"}]}"))]
        (is (:ok r))
        (is (= {:profile/version 1 :preset :collaborator :goal "A RAG service." :issues [{:tracker :beads}]}
               (edn/read-string (slurp f))))
        (is (str/includes? (agents) "Goal: A RAG service."))
        (is (str/includes? (agents) "Issues are tracked in beads."))
        (is (= :current (:block (:profile (init/project-status project)))))))
    (testing "refused whole, nothing written, when anything is wrong with it"
      (let [before (slurp f)]
        (doseq [bad ["{\"preset\":\"maximal\"}" "{\"goal\":\"g\",\"wings\":1}" "{\"autonomy\":{\"git/push\":\"act\"}}"
                     "{\"issues\":[{\"tracker\":\"trello\"}]}"]]
          (let [r (init/profile-write project (in bad))]
            (is (= :schema (:stage r)) bad)
            (is (seq (:problems r)) bad)))
        (is (= before (slurp f)))))))

(deftest a-drafted-profile-is-what-the-draft-says-and-no-more
  ;; byj-71w.4
  (is (= {:profile/version 1 :preset :collaborator :goal "g" :scope {:in ["a" "b"]} :done ["npm test passes"]
          :autonomy {:decision/local :act}}
         (init/draft-profile {:goal " g " :in "- a\n2. b\n" :out "" :done "npm test passes" :preset "Collaborator"
                              :autonomy "decision/local act\ngit/push sometimes\nwings act"})))
  (is (= {:profile/version 1} (init/draft-profile {:preset "maximal"})) "a preset it does not know: the default's"))

(deftest init-writes-the-profile-only-on-the-persons-yes
  ;; byj-71w.4: drafted, shown, and written with its first issues only on a yes
  (let [bin (fresh-dir "bin-profile")
        project (fresh-dir "profile-yes")
        f (io/file project ".brainyard" "profile.edn")
        asked (atom nil)
        draft {:dir project :path bin :goal "A small web workspace." :in "server shell" :done "npm test passes"
               :preset "collaborator" :issues "server shell — a page, and GET /api/health answering ok\nterminal"}
        ctx (fn [answer] {:permission-fn (fn [req] (reset! asked req) answer)})]
    (stateful-bd bin)
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (init/scaffold {} {:dir project})
    (.mkdirs (io/file project ".beads"))
    (testing "a no, or nobody to ask: nothing written, the draft shown"
      (let [r (init/write-profile {} (ctx {:denied true :reason "not yet"}) draft)]
        (is (false? (:written? r)))
        (is (str/includes? (:text r) "not yet"))
        (is (str/includes? (:text r) "Preset: collaborator.")))
      (is (str/includes? (:display @asked) "Goal: A small web workspace.") "the person sees the block")
      (is (str/includes? (:display @asked) "Issues are tracked in beads.") "and that the issues are kept in beads")
      (is (str/includes? (:display @asked) "and file its first issues in beads, in order:\n- server shell\n- terminal"))
      (is (not (re-find #"file the epic|under the epic" (:display @asked))) "the goal has no epic (byj-yq9)")
      (is (false? (:written? (init/write-profile {} {} draft))))
      (is (not (.exists f)))
      (is (not (.exists (io/file bin "bd.log"))) "nor any bead filed"))
    (testing "a yes: the profile, its block, and its first issues in order"
      (let [r (init/write-profile {} (ctx {:allowed true}) draft)
            calls (str/split-lines (slurp (io/file bin "bd.log")))
            p (init/project-status project)]
        (is (:written? r))
        (is (= [{:tracker :beads}] (:issues (edn/read-string (slurp f))))
            "the issues are kept in beads, and no epic is named (byj-yq9)")
        (is (= :collaborator (get-in p [:profile :preset])))
        (is (= :current (get-in p [:profile :block])) "AGENTS.md shows it")
        (is (not-any? #(str/includes? % "--type epic") calls) "no epic is filed")
        (is (not-any? #(str/includes? % "--parent") calls) "nor anything under one")
        (is (some #(str/starts-with? % "create --silent --title terminal --type task --priority 1") calls))
        (is (some #(= "dep add t-2 t-1" %) calls) "each after the one before")
        (is (some #(str/includes? % "--title server shell --type task --priority 1 --description a page, and GET /api/health answering ok") calls)
            "what the person said of a part is its bead's description")
        (is (str/includes? (:text r) "Its first issues, filed in beads:\n  t-1 server shell\n  t-2 terminal"))
        (is (str/includes? (:out (fx/sh {:dir project :out :string :err :string} "git" "status" "--porcelain"))
                           "A  .brainyard/profile.edn")
            "added to git though .gitignore ignores .brainyard/")
        (is (contains? (init/next-facts p) :profile))))
    (testing "with a profile, the starter issues do not ask what the project is for"
      (spit (io/file bin "issues") "")
      (spit (io/file bin "bd.log") "")
      (init/onboard {} {:dir project :path bin :missing []})
      (let [log (slurp (io/file bin "bd.log"))]
        (is (str/includes? log "Get started with brainyard"))
        (is (not (str/includes? log "Say what this project is for")))))))

;; byj-8b3: the person says what should change, the model writes the EDN,
;; and nothing is written but on the person's yes, asked every time
(deftest the-profile-changes-as-the-person-asks-only-on-their-yes
  (let [project (fresh-dir "profile-edit")
        f (io/file project ".brainyard" "profile.edn")
        before {:profile/version 1 :preset :collaborator :goal "A web workspace."
                :scope {:in ["a workspace"]} :done ["it runs"] :goal-epic "w-1"}
        asked (atom [])
        ctx (fn [answer] {:permission-fn (fn [req] (swap! asked conj req) answer)})
        edit #(init/edit-profile {} %1 (assoc %2 :dir project))]
    (testing "no profile: /init makes one, and nobody is asked"
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:goal "x"})) "/init drafts one"))
      (is (empty? @asked)))
    (fx/sh {:dir project :out :string :err :string} "git" "init" "-q")
    (init/scaffold {} {:dir project})
    (io/make-parents f)
    (spit f (pr-str before))
    (let [ask {:goal "Brainyard Apps: web apps on byj, the workbench first."
               :done "the workbench runs on byj acp\nnpm run typecheck passes"
               :out "" :autonomy ""}]
      (testing "a no, or nobody to ask: the file as it was, and the change said"
        (let [r (edit (ctx {:denied true :reason "not yet"}) ask)]
          (is (false? (:written? r)))
          (is (str/includes? (:text r) "not yet")))
        (is (false? (:written? (edit {} ask))) "headless")
        (is (= before (edn/read-string (slurp f)))))
      (let [{:keys [display every-time? tool]} (first @asked)]
        (is (= "profile$edit" tool))
        (is (true? every-time?) "asked every time, never answered from an always")
        (is (str/includes? display "Goal:\n  was: A web workspace.\n  now: Brainyard Apps"))
        (is (str/includes? display "The goal's epic (deprecated, ignored): w-1 is dropped")
            "a profile written here loses a deprecated :goal-epic (byj-yq9)")
        (is (not (str/includes? display "In scope")) "what is not given is kept, and not shown"))
      (testing "a yes: what was asked, the rest kept, and AGENTS.md's block with it"
        (let [r (edit (ctx {:allowed true}) ask)
              data (edn/read-string (slurp f))]
          (is (:written? r))
          (is (= (-> before (dissoc :goal-epic)
                     (assoc :goal (:goal ask) :done ["the workbench runs on byj acp" "npm run typecheck passes"]))
                 data))
          (is (= :current (get-in (init/project-status project) [:profile :block])))
          (is (not (str/includes? (slurp (io/file project "AGENTS.md")) "under the epic")))
          (is (str/includes? (:out (fx/sh {:dir project :out :string :err :string} "git" "status" "--porcelain"))
                             "A  .brainyard/profile.edn")))))
    (testing "the preset and an axis: each axis that moves is said, and which way"
      (reset! asked [])
      (edit (ctx {:denied true}) {:preset "autopilot" :autonomy "git/commit ask"})
      (let [d (:display (first @asked))]
        (is (str/includes? d "Preset:\n  was: collaborator\n  now: autopilot"))
        (is (str/includes? d "decision/local: ask -> act  (more on its own)"))
        (is (str/includes? d "git/commit: act -> ask  (less on its own)"))))
    (testing "what cannot be is refused before anyone is asked"
      (reset! asked [])
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:preset "yolo"})) "no preset yolo"))
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:autonomy "git/push act"})) "ceiling")
          "push is never the agent's, whoever asks")
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:autonomy "deploy act"})) "is not `axis level`"))
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:goal (:goal (edn/read-string (slurp f)))})) "what it says already"))
      (is (empty? @asked)))
    (testing "the issue trackers (byj-yq9): the whole list replaced, each said on its own line, a :goal-epic dropped"
      (spit f (pr-str before))
      (reset! asked [])
      (edit (ctx {:allowed true}) {:issues "[{:tracker :beads} {:tracker :github :github-repo \"acme/web\"}]"})
      (let [data (edn/read-string (slurp f))
            d (:display (first @asked))]
        (is (= [{:tracker :beads} {:tracker :github :github-repo "acme/web"}] (:issues data)))
        (is (not (contains? data :goal-epic)))
        (is (str/includes? d "Issue trackers:\n  was: (none)\n  now:\n    beads\n    github github-repo acme/web"))
        (is (str/includes? d "The goal's epic (deprecated, ignored): w-1 is dropped"))
        (is (str/includes? (slurp (io/file project "AGENTS.md")) "Issues are tracked in beads and GitHub Issues (acme/web).")))
      (reset! asked [])
      (edit (ctx {:allowed true}) {:issues "[{:tracker :jira :name \"ops\" :jira-site \"acme.atlassian.net\" :jira-project \"ABC\"}]"})
      (is (= [{:tracker :jira :name "ops" :jira-site "acme.atlassian.net" :jira-project "ABC"}] (:issues (edn/read-string (slurp f)))))
      (is (str/includes? (:display (first @asked)) "now:\n    jira name ops jira-site acme.atlassian.net jira-project ABC"))
      (edit (ctx {:allowed true}) {:issues "[]"})
      (is (not (contains? (edn/read-string (slurp f)) :issues)) "[] drops them"))
    (testing "issue trackers that cannot be are refused before anyone is asked"
      (spit f (pr-str before))
      (reset! asked [])
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:issues "[{:tracker :trello}]"})) "no tracker :trello; the trackers are beads, github, linear, jira"))
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:issues "[{:linear-team \"ENG\"}]"})) "names its :tracker"))
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:issues "{:tracker :beads}"})) "not a vector of trackers"))
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:issues "[{:tracker :beads"})) "issues is not EDN"))
      (is (str/includes? (:text (edit (ctx {:allowed true}) {:issues "[{:tracker :beads :epic \"w-1\"}]"})) "would not hold")
          "the goal has no epic: the schema refuses it")
      (is (empty? @asked)))
    (testing "`axis preset` drops the profile's own level"
      (spit f (pr-str (assoc before :autonomy {:git/commit :ask})))
      (edit (ctx {:allowed true}) {:autonomy "git/commit preset"})
      (is (not (contains? (edn/read-string (slurp f)) :autonomy))))))

(deftest every-session-has-profile-edit
  (is (= ["profile$edit"] (mapv :name (init/profile-commands {}))))
  (is (true? (get-in config/config-items [:profile-tools? :default]))))

(deftest the-init-loop-drafts-the-profile-when-the-person-says-what-the-project-is-for
  (let [project (fresh-dir "agent-init-profile")
        seen (atom [])
        [r steps] (run-init project "/init A small web workspace; commit as you go."
                            {:scaffold true :beads false :draft false :profile true} seen
                            (fn [_] {:allowed true}))]
    (is (= ["init$check" "init/plan" "init$scaffold" "init/profile" "init$profile" "init$check" "init$next"] steps))
    (is (= [:plan :profile] @seen))
    (is (str/includes? (:answer r) "so the profile is written as they confirmed it: .brainyard/profile.edn (collaborator)"))
    (is (str/includes? (slurp (io/file project "AGENTS.md")) "<!-- BEGIN BRAINYARD:profile -->"))
    (is (str/includes? (:answer r) "✓ profile: collaborator") "and the check after says so")))

(deftest a-draft-missing-its-preset-or-issues-is-drafted-again-and-said
  ;; byj-8ap, byj-kti: haiku's draft once came back with no preset or
  ;; issues, and was written so on the person's yes
  (testing "the loop drafts once more when the draft has no preset or issues"
    (let [project (fresh-dir "agent-init-redraft")
          drafts (atom [{:reasoning "r" :goal "A small web workspace." :in "server shell"}
                        {:reasoning "r" :goal "A small web workspace." :in "server shell" :done "npm test passes"
                         :preset "autopilot" :issues "server shell — a page"}])
          steps (atom [])
          r (agent/ask "/init A small web workspace; decide the small things."
                       {:lm {:provider :mock :format :mock
                             :responses (fn [messages]
                                          (let [sys (str (:content (first messages)))]
                                            (json/write-str
                                             (cond
                                               (str/includes? sys "You prepare a project directory for brainyard")
                                               {:reasoning "r" :install "" :prefix "" :onboard false
                                                :scaffold true :beads false :draft false :profile true}
                                               (str/includes? sys "Draft this project's profile")
                                               (let [d (first @drafts)] (swap! drafts #(if (next %) (rest %) %)) d)
                                               :else {:reasoning "r" :answer "?"}))))}
                        :tree (agent/load-tree "brainyard/loops/init.edn")
                        :on-event (fn [e d] (when (= e :agent.tree/step-start) (swap! steps conj (:name d))))
                        :permission-fn (fn [_] {:allowed true})
                        :config {:project-dir project :working-dir project :init-tools? true}})]
      (is (= ["init$check" "init/plan" "init$scaffold" "init/profile" "init/profile" "init$profile" "init$check" "init$next"] @steps))
      (is (str/includes? (:answer r) "so the profile is written as they confirmed it: .brainyard/profile.edn (autopilot)"))))
  (testing "what is still missing, the person is told before their yes"
    (let [bin (fresh-dir "bin-profile-missing")
          project (fresh-dir "profile-missing")
          asked (atom nil)]
      (stateful-bd bin)
      (init/scaffold {} {:dir project})
      (.mkdirs (io/file project ".beads"))
      (let [r (init/write-profile {} {:permission-fn (fn [req] (reset! asked (:display req)) {:allowed true})}
                                  {:dir project :path bin :goal "A small web workspace." :in "server shell"})]
        (is (str/includes? @asked "The draft has no preset: the default, conservative, stands; no first issues: none are filed, and /drive has nothing to work on. To change that, say no and run /init again saying it."))
        (is (str/includes? (:text r) "The draft had no preset")))
      (is (= [] (init/draft-missing {:preset "collaborator" :issues "a — b"} true)))
      (is (= ["no first issues: none are filed, and /drive has nothing to work on"]
             (init/draft-missing {:preset "collaborator" :issues ""} true)))
      (is (= [] (init/draft-missing {:preset "autopilot"} false)) "no beads: no issues to file"))))

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
