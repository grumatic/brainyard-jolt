;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.init
  "The `init$*` commands: what `/init` calls to make a directory a project
  brainyard works well in (RFC-040, byj-gly.309). Mechanism only. What to set
  up, in what order, and what AGENTS.md says for this project are agent$init's
  tree and predictors (`resources/brainyard/loops/init.edn`,
  `predictors/init/`); the tools brainyard relies on and how each is installed
  are data (`resources/brainyard/init/requirements.edn`).

  `check` only reads — brainyard's \"detection is a pure read\"
  (`docs/design/bootstrapping-design.md` §2.3): it looks on PATH, asks each
  tool found for its version, and looks at the project's files. It installs
  nothing, writes nothing, and needs no network or model. The reference is
  brainyard's `env-detect` `executables.clj` (`detect-executables`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.skills :as skills]))

;; --- the machine ---------------------------------------------------------------------------

(def requirements
  "The tools brainyard relies on: `resources/brainyard/init/requirements.edn`."
  (delay (edn/read-string (slurp (io/resource "brainyard/init/requirements.edn")))))

(defn platform
  "`:macos`, `:linux` or `:other` — which install hints apply."
  []
  (let [os (str/lower-case (str (System/getProperty "os.name")))]
    (cond (str/includes? os "mac") :macos
          (str/includes? os "linux") :linux
          :else :other)))

(defn on-path
  "The absolute path of executable `cmd` on `path` (default this process's
  PATH), or nil: a scan of the directories, running nothing."
  ([cmd] (on-path cmd (System/getenv "PATH")))
  ([cmd path]
   (some (fn [d]
           (let [f (io/file d cmd)]
             (when (and (not (str/blank? d)) (.isFile f) (.canExecute f)) (.getAbsolutePath f))))
         (str/split (str path) #":"))))

(def version-timeout-ms
  "How long a tool is given to say its version: one that hangs (asking for a
  login, say) is reported without one rather than holding up the check."
  5000)

(defonce ^:private versions (atom {}))

(defn version-of
  "What `exe` says its version is, run with `argv`: the first version-like
  token of its first line (`2.1.294`, `v0.8.15` → `0.8.15`, `3.6a`), else that
  line; nil when it says nothing in time.

  Asked once per file: kept by the executable, the arguments and the file's
  modification time, so an upgrade — which replaces the file — is asked again.
  /init, /doctor and the update check ask about the same seven tools over and
  over, a process each (byj-gly.382). A tool that said nothing is asked again.
  `timeout-ms` is how long it is given (`version-timeout-ms` unless said)."
  [exe argv & [{:keys [timeout-ms]}]]
  (let [k [exe argv (try (.lastModified (io/file (str exe))) (catch Throwable _ 0))]]
    (or (get @versions k)
        (let [r (fx/run!! (apply fx/process-task {:out :string :err :string} exe argv) (or timeout-ms version-timeout-ms))
              out (when-let [ok (:ok r)] (str (:out ok) "\n" (:err ok)))
              line (some->> (str/split-lines (str out)) (map str/trim) (remove str/blank?) first)
              v (when line
                  (or (second (re-find #"(?:^|[\s(])v?(\d+(?:\.\d+)+[0-9A-Za-z.+-]*)" line)) line))]
          (when v (swap! versions assoc k v))
          v))))

(defn signed-in?
  "Whether `exe` says it is signed in: `argv` (a requirement's `:signed-in`)
  exits 0 in time. A tool that hangs, asking for a login, is not."
  [exe argv]
  (let [r (fx/run!! (apply fx/process-task {:out :string :err :string :in ""} exe argv) version-timeout-ms)]
    (boolean (some-> (:ok r) :exit zero?))))

(defn tool-status
  "Where one requirement stands on this machine: `{:name :command :need :for
  :path :version :available? :install}`, `:install` the hints for `plat`, and
  `:signed-in?` for a tool whose requirement says how to ask (`:signed-in`)."
  [req & [{:keys [path plat]}]]
  (let [exe (on-path (:command req) (or path (System/getenv "PATH")))]
    (cond-> {:name (:name req) :command (:command req) :need (:need req) :for (:for req)
             :available? (boolean exe)
             :install (vec (get (:install req) (or plat (platform))))}
      exe (assoc :path exe :version (version-of exe (:version req)))
      (and exe (:signed-in req)) (assoc :signed-in? (signed-in? exe (:signed-in req)))
      (:url req) (assoc :url (:url req)))))

;; --- the project ---------------------------------------------------------------------------

(defn- slurp-if [f] (when (.isFile (io/file f)) (slurp f)))

(defn imports-agents-md?
  "Does `text`, a CLAUDE.md, import AGENTS.md (`@AGENTS.md` on a line of its own)?"
  [text]
  (boolean (re-find #"(?m)^\s*@\.?/?AGENTS\.md\s*$" (str text))))

(defn ignores-brainyard?
  "Does `text`, a .gitignore, ignore the `.brainyard/` directory?"
  [text]
  (boolean (re-find #"(?m)^\s*/?\.brainyard/?\s*$" (str text))))

(defn- bd-check
  "What `bd setup <recipe> --check` says in `dir`: `{:ok? :said}`."
  [bd dir recipe]
  (let [r (fx/run!! (fx/process-task {:dir (str dir) :out :string :err :string} bd "setup" recipe "--check")
                    version-timeout-ms)
        ok (:ok r)
        said (some->> (str (:out ok) "\n" (:err ok)) str/split-lines (map str/trim) (remove str/blank?) (str/join " "))]
    {:ok? (boolean (and ok (zero? (:exit ok)) (not (re-find #"(?i)stale|missing|not installed" (str said)))))
     :said (or said (if (:timeout r) "no answer in time" ""))}))

(def build-files
  "The files that say a project has code to describe, and how it is built."
  ["package.json" "deps.edn" "bb.edn" "project.clj" "pyproject.toml" "setup.py" "requirements.txt"
   "Cargo.toml" "go.mod" "Makefile" "justfile" "pom.xml" "build.gradle" "build.gradle.kts" "Gemfile"])

(def ^:private placeholder-mark
  ;; a phrase of the templates' project block, gone once a draft replaces it
  "drafts this block from the repository")

(defn- project-section
  "Where AGENTS.md's project section stands: `:drafted`, `:placeholder` (the
  template's, never drafted), or nil when it has none."
  [agents-md]
  (let [t (str agents-md)
        b (str/index-of t "<!-- BEGIN BRAINYARD:project -->")
        e (when b (str/index-of t "<!-- END BRAINYARD:project -->" b))]
    (when (and b e)
      (if (str/includes? (subs t b e) placeholder-mark) :placeholder :drafted))))

(def ^:private init-paths
  ;; what /init writes in a project, for saying which of it is uncommitted
  ["AGENTS.md" "CLAUDE.md" ".gitignore" ".claude/skills"])

(defn project-status
  "What project `dir` has for brainyard: `{:dir :git? :agents-md? :claude-md?
  :claude-imports? :beads? :bd-setup :skills :gitignored?}`. `:bd-setup` is
  bd's own word on its Claude Code and Codex setup, when bd and `.beads/` are
  there; `:skills` the project's own (its `.brainyard`, `.claude` and
  `.agents` skills roots); `:build-files` those of `build-files` it has;
  `:project-section` where AGENTS.md's drafted section stands; `:uncommitted`
  what of /init's files git has not committed (byj-gly.315.8, .10)."
  [dir & [{:keys [bd codex?]}]]
  (let [d (io/file dir)
        claude-md (slurp-if (io/file d "CLAUDE.md"))
        beads? (.isDirectory (io/file d ".beads"))]
    {:dir (.getCanonicalPath d)
     :git? (or (.exists (io/file d ".git"))
               (boolean (some-> (on-path "git")
                                (#(fx/sh {:dir (str d) :out :string :err :string} % "rev-parse" "--git-dir"))
                                :exit zero?)))
     :agents-md? (.isFile (io/file d "AGENTS.md"))
     :claude-md? (some? claude-md)
     :claude-imports? (imports-agents-md? claude-md)
     :beads? beads?
     :bd-setup (when (and bd beads?)
                 (cond-> {:claude (bd-check bd d "claude")}
                   codex? (assoc :codex (bd-check bd d "codex"))))
     :skills (vec (for [root (skills/roots {:project-dir (.getCanonicalPath d)})
                        :when (= :project (:scope root))
                        s (sort-by #(.getName ^java.io.File %) (or (.listFiles ^java.io.File (:dir root)) []))
                        :when (.isFile (io/file s "SKILL.md"))]
                    (str (.getName (.getParentFile ^java.io.File (:dir root))) "/" (.getName ^java.io.File s))))
     :gitignored? (ignores-brainyard? (slurp-if (io/file d ".gitignore")))
     :build-files (vec (filter #(.isFile (io/file d %)) build-files))
     :project-section (project-section (slurp-if (io/file d "AGENTS.md")))
     :uncommitted (let [r (fx/sh {:dir (str d) :out :string :err :string}
                                 "sh" "-c" (str "git status --porcelain -- " (str/join " " init-paths) " 2>/dev/null"))]
                    (vec (remove str/blank? (map #(str/trim (str %)) (str/split-lines (str (:out r)))))))}))

;; --- the report ----------------------------------------------------------------------------

(defn- need-label [need] (case need :required "required" :feature "for a feature" "optional"))

(defn report-text
  "The check in words: the tools, each missing one with how to install it,
  then the project. A Markdown list, single-spaced, so that it reads the same
  printed and drawn by the full-screen renderer, which had dropped its
  indentation and run its spacing together (the sim's screen pass,
  byj-gly.315.5)."
  [{:keys [tools project]}]
  (let [missing (remove :available? tools)
        mark #(if % "✓" "✗")
        p project]
    (str "Tools:\n"
         (str/join "\n" (for [t tools]
                          (str "- " (mark (:available? t)) " " (:name t)
                               (if (:available? t) (str " " (or (:version t) "(version unknown)")) " — missing")
                               (when (contains? t :signed-in?)
                                 (if (:signed-in? t) ", signed in" (str ", not signed in — " (:command t) " login")))
                               " (" (need-label (:need t)) ": " (:for t) ")")))
         (when (seq missing)
           (str "\n\nTo install what is missing (nothing is installed by this check):\n"
                (str/join "\n" (for [t missing]
                                 (str "- " (:name t) ": "
                                      (if (seq (:install t)) (str/join ", or " (map #(str "`" % "`") (:install t))) "no install hint for this platform")
                                      (when (:url t) (str " — " (:url t))))))))
         "\n\nProject " (:dir p) ":\n"
         (str/join "\n"
                   (map #(str "- " %)
                        (remove nil?
                        [(str (mark (:git? p)) " a git repository")
                         (str (mark (:agents-md? p)) " AGENTS.md")
                         (str (mark (and (:claude-md? p) (:claude-imports? p))) " CLAUDE.md"
                              (cond (not (:claude-md? p)) " — missing"
                                    (not (:claude-imports? p)) " — does not import AGENTS.md (@AGENTS.md)"
                                    :else " imports AGENTS.md"))
                         (str (mark (:beads? p)) " beads (.beads/)"
                              (when-let [s (:bd-setup p)]
                                (str/join "" (for [[k v] (sort-by key s)]
                                               (str "; bd setup " (name k) ": " (if (:ok? v) "ok" (str "needs attention — " (:said v))))))))
                         (str (mark (:gitignored? p)) " .brainyard/ in .gitignore")
                         (str "code: " (if (seq (:build-files p)) (str/join ", " (:build-files p)) "no build files yet"))
                         (str "AGENTS.md's project section: "
                              (case (:project-section p)
                                :drafted "drafted"
                                ;; said for what is there: under a code line naming
                                ;; package.json, \"once it has code\" contradicted it
                                :placeholder (if (seq (:build-files p))
                                               "not drafted yet — /init drafts it from the code"
                                               "not drafted yet — /init drafts it once the project has code")
                                "none"))
                         (when (seq (:uncommitted p))
                           (str "left uncommitted: " (str/join ", " (map #(str/replace % #"^\S+\s+" "") (:uncommitted p)))))
                         (str "skills: " (if (seq (:skills p)) (str/join ", " (:skills p)) "none in the project"))]))))))

(defn check
  "Where this machine and project `dir` stand for brainyard (`init$check`):
  `{:tools [...] :project {...} :missing-required [...] :text}`. Reads only."
  [config & [{:keys [dir path]}]]
  (let [dir (or (not-empty (str dir)) (:project-dir config) (:working-dir config) ".")
        tools (mapv #(tool-status % {:path path}) @requirements)
        by-name (into {} (map (juxt :name identity)) tools)
        project (project-status dir {:bd (get-in by-name ["bd" :path])
                                     :codex? (get-in by-name ["codex" :available?])})
        r {:tools tools :project project
           :missing-required (vec (keep #(when (and (= :required (:need %)) (not (:available? %))) (:name %)) tools))}]
    (assoc r :text (report-text r))))

;; --- writing: the starting files, never overwriting (byj-gly.309.3) ------------------------

(def templates
  "What /init writes, and how: `resources/brainyard/init/templates/index.edn`,
  each entry with its template's text under `:text`."
  (delay (vec (for [e (edn/read-string (slurp (io/resource "brainyard/init/templates/index.edn")))]
                (cond-> e
                  (:template e) (assoc :text (slurp (io/resource (str "brainyard/init/templates/" (:template e))))))))))

(defn- begin-marker [block-name] (str "<!-- BEGIN " block-name " -->"))
(defn- end-marker [block-name] (str "<!-- END " block-name " -->"))

(defn marked-blocks
  "The blocks `text` marks, in order: `[[name block] …]`, each block from its
  `<!-- BEGIN BRAINYARD… -->` to its `<!-- END … -->` line, both included.
  Only brainyard's: bd's blocks are bd's."
  [text]
  (let [text (str text)]
    (loop [from 0 out []]
      (if-let [i (str/index-of text "<!-- BEGIN BRAINYARD" from)]
        (let [close (str/index-of text " -->" i)
              block-name (subs text (+ i (count "<!-- BEGIN ")) close)
              end (str/index-of text (end-marker block-name) close)]
          (if end
            (let [stop (+ end (count (end-marker block-name)))]
              (recur stop (conj out [block-name (subs text i stop)])))
            out))
        out))))

(defn set-block
  "`text` with block `block-name` replaced by `block`, or added: before
  `before` (a marker) when the text has it, else at the end. Nothing else in
  the text changes."
  [text block-name block & [before]]
  (let [text (str text)
        b (str/index-of text (begin-marker block-name))
        e (when b (str/index-of text (end-marker block-name) b))]
    (cond
      (and b e) (str (subs text 0 b) block (subs text (+ e (count (end-marker block-name)))))
      (and before (str/index-of text before))
      (let [i (str/index-of text before)] (str (subs text 0 i) block "\n\n" (subs text i)))
      :else (str (str/trimr text) (when-not (str/blank? text) "\n\n") block "\n"))))

(def project-block
  "The block agent$init's draft of the project's own section goes in, before
  brainyard's: a later /init can rewrite it, and the person's text is never
  touched (RFC-040)."
  "BRAINYARD:project")

(defn- notes-block [notes]
  (str (begin-marker project-block) "\n" (str/trim (str notes)) "\n" (end-marker project-block)))

(defn- has-line? [text line]
  (let [norm #(-> (str/trim %) (str/replace #"^/" "") (str/replace #"/$" ""))]
    (boolean (some #(= (norm line) (norm %)) (str/split-lines (str text))))))

(defn- planned
  "What one manifest entry would make of `current` (nil: no such file)."
  [{:keys [kind text line lines header]} current notes]
  (case kind
    :block (let [with-notes #(if (str/blank? (str notes)) %
                                 (set-block % project-block (notes-block notes) (begin-marker "BRAINYARD")))]
             (with-notes (if (nil? current)
                           text
                           ;; the project's block only ever from a draft: the
                           ;; template's placeholder never goes over one
                           (reduce (fn [t [n b]] (set-block t n b)) current
                                   (remove #(= project-block (first %)) (marked-blocks text))))))
    :import (cond (nil? current) text
                  (has-line? current line) current
                  :else (str (str/trimr current) "\n\n" line "\n"))
    :lines (let [missing (remove #(has-line? current %) lines)]
             (if (empty? missing)
               current
               (str (when-not (str/blank? (str current)) (str (str/trimr current) "\n\n"))
                    (when header (str header "\n"))
                    (str/join "\n" missing) "\n")))))

(defn skill-links
  "The links Claude Code needs to see the project's `.agents/skills`: one
  `{:path \".claude/skills/<name>\" :kind :link :link-to
  \"../../.agents/skills/<name>\" :action :create}` for each skill there with
  no entry of that name under `.claude/skills` — a real directory, a file or a
  link already there is left (byj-gly.309.9). brainyard and Codex read
  `.agents/skills`; Claude Code reads `.claude/skills`, and bd writes its skill
  to the first only."
  [dir]
  (vec (for [s (sort-by #(.getName ^java.io.File %) (or (.listFiles (io/file dir ".agents" "skills")) []))
             :when (.isFile (io/file s "SKILL.md"))
             :let [n (.getName ^java.io.File s)
                   target (io/file dir ".claude" "skills" n)]
             :when (not (or (.exists target)
                            (java.nio.file.Files/isSymbolicLink (.toPath target))))]
         {:path (str ".claude/skills/" n) :kind :link :link-to (str "../../.agents/skills/" n) :action :create})))

(defn- link! [dir {:keys [path link-to]}]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (fx/sh {:dir (str (.getParentFile f)) :out :string :err :string} "ln" "-s" link-to (.getName f))))

(defn plan-writes
  "What /init would write in project `dir`: one `{:path :action :before :after}`
  per manifest entry, `:action` `:create`, `:update` or `:unchanged`. Writes
  nothing. `notes`, agent$init's draft of the project's section of AGENTS.md,
  goes in its own block (`project-block`)."
  [dir & [{:keys [notes]}]]
  (into (vec (for [e @templates
             :let [f (io/file dir (:path e))
                   current (slurp-if f)
                   after (planned e current (when (= "AGENTS.md" (:path e)) notes))]]
         {:path (:path e) :kind (:kind e) :before current :after after
          :action (cond (nil? current) :create (= current after) :unchanged :else :update)}))
        (skill-links dir)))

(defn apply-writes!
  "Make the writes `plan` lists in `dir`: each file changed is first copied to
  `.brainyard/init/snapshots/<ms>/`, as brainyard's init-agent snapshots
  before every write. `{:writes [{:path :action}] :snapshot}`."
  [dir plan]
  (let [changes (filter #(#{:create :update} (:action %)) plan)
        snap (io/file dir ".brainyard" "init" "snapshots" (str (System/currentTimeMillis)))]
    (doseq [{:keys [path action before after kind] :as w} changes]
      (if (= :link kind)
        (link! dir w)
        (do (when (= :update action)
              (let [s (io/file snap path)] (io/make-parents s) (spit s before)))
            (let [f (io/file dir path)] (io/make-parents f) (spit f after)))))
    (cond-> {:writes (mapv #(select-keys % [:path :action :link-to]) plan)}
      (some #(= :update (:action %)) changes) (assoc :snapshot (.getCanonicalPath snap)))))

(defn- writes-text [writes dry-run?]
  (str/join "\n" (for [{:keys [path action]} writes]
                   (str "  " path ": "
                        (case action
                          :create (str (if dry-run? "would be created" "created")
                                       (when-let [to (some #(when (= path (:path %)) (:link-to %)) writes)]
                                         (str " — a link to " to ", for Claude Code")))
                          :update (if dry-run? "would be updated (only brainyard's part)" "updated (only brainyard's part)")
                          "already in place")))))

(defn scaffold
  "Write the starting files into project `dir` (`init$scaffold`): AGENTS.md,
  CLAUDE.md importing it, `.brainyard/` in .gitignore — each created when
  absent; when present, only brainyard's marked blocks, the import line or the
  missing lines are written, after a snapshot. A second run writes nothing.
  `dry-run` plans and writes nothing. `{:writes :snapshot? :text}`."
  [config & [{:keys [dir dry-run notes]}]]
  (let [dir (or (not-empty (str dir)) (:project-dir config) (:working-dir config) ".")
        plan (plan-writes dir {:notes notes})
        dry? (contains? #{true "true"} dry-run)
        r (if dry? {:writes (mapv #(select-keys % [:path :action :link-to]) plan)} (apply-writes! dir plan))]
    (assoc r :text (str (if dry? "Would write, in " "Wrote, in ") (.getCanonicalPath (io/file dir)) ":\n"
                        (writes-text (:writes r) dry?)
                        (when (:snapshot r) (str "\nWhat was there before is in " (:snapshot r) "."))))))

;; --- beads: bd's own setup (byj-gly.309.4) -------------------------------------------------

(def bd-timeout-ms
  "How long one bd command is given: an init creates a Dolt database."
  120000)

(defn- bd! [bd dir & args]
  (let [r (fx/run!! (apply fx/process-task {:dir (str dir) :out :string :err :string} bd args) bd-timeout-ms)
        ok (:ok r)]
    {:argv (str/join " " (cons "bd" args))
     :ok? (boolean (and ok (zero? (:exit ok))))
     :out (str (:out ok))
     :said (str/trim (str (:out ok) (when-not (str/blank? (:err ok)) (str "\n" (:err ok)))
                          (when (:timeout r) "no answer in time")))}))

(defn remove-beads-blocks
  "`text` without bd's marked blocks (`<!-- BEGIN BEADS … -->` to `<!-- END
  BEADS … -->`), and the blank lines they leave."
  [text]
  (loop [t (str text)]
    (if-let [b (str/index-of t "<!-- BEGIN BEADS")]
      (let [e (str/index-of t "<!-- END BEADS" b)
            stop (when e (some-> (str/index-of t "-->" e) (+ 3)))]
        (if stop
          (let [before (str/trimr (subs t 0 b))
                after (str/replace (subs t stop) #"^\s+" "")]
            (recur (cond (str/blank? after) (str before "\n")
                         (str/blank? before) after
                         :else (str before "\n\n" after))))
          t))
      t)))

(defn- head [dir]
  (let [r (fx/sh {:dir (str dir) :out :string :err :string} "git" "rev-parse" "-q" "--verify" "HEAD")]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- commits-since [dir from]
  (let [r (fx/sh {:dir (str dir) :out :string :err :string} "git" "log" "--format=%h %s" (if from (str from "..HEAD") "HEAD"))]
    (if (zero? (:exit r)) (vec (remove str/blank? (str/split-lines (:out r)))) [])))

(defn beads
  "Set up beads in project `dir` with bd itself (`init$beads`): `bd init`
  when there is no `.beads/` (`--non-interactive --init-if-missing`, the
  prefix `prefix` or bd's default, the directory name), then `bd setup
  claude` — and `codex` when codex is installed — each only when its
  `--check` says it is not in place. bd writes its own files (`.beads/`, git
  hooks, its AGENTS.md blocks, the beads skill, `.claude` and `.codex` hooks);
  nothing of bd's is copied here. A CLAUDE.md that imports AGENTS.md is kept
  to the import: any block bd put in it is removed (RFC-040). bd commits its
  own files; the commits it made are listed. `{:ran :commits :text}`, or
  `{:skipped}` when there is no bd."
  [config & [{:keys [dir prefix codex? path]}]]
  (let [dir (or (not-empty (str dir)) (:project-dir config) (:working-dir config) ".")
        path (or path (System/getenv "PATH"))
        bd (on-path "bd" path)
        codex? (if (some? codex?) codex? (boolean (on-path "codex" path)))]
    (if-not bd
      {:skipped "bd is not installed" :text "Beads not set up: bd is not installed (init$check says how to install it)."}
      (let [from (head dir)
            init (when-not (.isDirectory (io/file dir ".beads"))
                   (apply bd! bd dir "init" "--non-interactive" "--init-if-missing"
                          (when-not (str/blank? (str prefix)) ["--prefix" (str/trim (str prefix))])))
            setups (when (or (nil? init) (:ok? init))
                     (vec (for [recipe (cond-> ["claude"] codex? (conj "codex"))
                                :when (not (:ok? (bd-check bd dir recipe)))]
                            (bd! bd dir "setup" recipe))))
            claude-md (io/file dir "CLAUDE.md")
            stripped (when-let [t (slurp-if claude-md)]
                       (when (and (imports-agents-md? t) (str/includes? t "<!-- BEGIN BEADS"))
                         (spit claude-md (remove-beads-blocks t))
                         true))
            ran (vec (remove nil? (cons init setups)))
            commits (commits-since dir from)
            ;; bd's skill is written now, after the scaffold's links
            links (skill-links dir)
            _ (doseq [l links] (link! dir l))]
        {:ran (mapv #(select-keys % [:argv :ok?]) ran)
         :commits commits
         :links (mapv :path links)
         :text (str "Beads, set up by bd:\n"
                    (if (seq ran)
                      (str/join "\n" (for [r ran] (str "  " (if (:ok? r) "✓ " "✗ ") (:argv r)
                                                       (when-not (:ok? r) (str " — " (:said r))))))
                      "  already in place (.beads/, and bd's setup checks pass)")
                    (when stripped "\n  bd's block removed from CLAUDE.md, which stays the AGENTS.md import")
                    (str/join "" (for [l links] (str "\n  linked " (:path l) " to " (:link-to l) ", for Claude Code")))
                    (when (seq commits)
                      (str "\nbd committed its own files:\n" (str/join "\n" (map #(str "  " %) commits)))))}))))

;; --- what the AGENTS.md draft is written from (byj-gly.309.5) ----------------------------

(def survey-files
  "The files a project's build, test and conventions are read from, when
  present, for agent$init's draft of its AGENTS.md section."
  ["README.md" "README" "AGENTS.md" "CLAUDE.md" "CONTRIBUTING.md"
   "package.json" "deps.edn" "bb.edn" "project.clj" "pyproject.toml" "setup.py" "requirements.txt"
   "Cargo.toml" "go.mod" "Makefile" "justfile" "pom.xml" "build.gradle" "build.gradle.kts" "Gemfile"])

(def survey-chars
  "How much of each surveyed file is kept: enough for its commands and
  conventions, not a whole manual."
  3000)

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "\n…") s)))

(defn survey
  "What a project shows of itself (`init$survey`): its top-level entries, its
  CI workflows, its last commits, and the head of each of `survey-files` it
  has. Reads only. `{:text}`."
  [config & [{:keys [dir]}]]
  (let [dir (io/file (or (not-empty (str dir)) (:project-dir config) (:working-dir config) "."))
        entries (->> (or (.listFiles dir) [])
                     (remove #(#{".git" ".brainyard"} (.getName ^java.io.File %)))
                     (sort-by #(.getName ^java.io.File %))
                     (map #(str (.getName ^java.io.File %) (when (.isDirectory ^java.io.File %) "/")))
                     (take 80))
        workflows (sort (map #(.getName ^java.io.File %) (or (.listFiles (io/file dir ".github" "workflows")) [])))
        log (let [r (fx/sh {:dir (str dir) :out :string :err :string} "git" "log" "-8" "--format=%s")]
              (when (zero? (:exit r)) (str/trim (:out r))))]
    {:text (str "Top level of " (.getCanonicalPath dir) ":\n" (str/join "  " entries)
                (when (seq workflows) (str "\n\nCI workflows (.github/workflows): " (str/join ", " workflows)))
                (when-not (str/blank? log) (str "\n\nRecent commits:\n" log))
                (str/join "" (for [f survey-files
                                   :let [t (slurp-if (io/file dir f))]
                                   :when t]
                               (str "\n\n--- " f " ---\n" (clip t survey-chars)))))}))

;; --- installing: only with a yes (byj-gly.309.5) --------------------------------------------

(def install-timeout-ms
  "How long one install is given."
  600000)

(defn- runnable?
  "Is `cmd`, an install hint, one this process can run unattended — a package
  manager that asks nothing? `sudo`, an installer piped into a shell, or a
  GUI (`xcode-select`) is the person's to run."
  [cmd]
  (boolean (re-matches #"(brew|npm) install [^|;&<>`$]+" (str/trim (str cmd)))))

(defn- ask-person
  "Ask the person through the turn's own channel (RFC-012): nil when they
  allow it, else why not."
  [ctx request]
  (if-let [pf (:permission-fn ctx)]
    (let [r (try (pf request) (catch Throwable e {:denied true :reason (ex-message e)}))]
      (when-not (:allowed r) (or (not-empty (str (:reason r))) "declined")))
    "there is no person to ask in this run"))

(defn install
  "Install the tools named in `tools` (`init$install`) — each only after the
  person says yes, every time: brainyard's \"no surprise installs\"
  (`bootstrapping-design.md` §2.6). The command is the first install hint for
  this platform, and only one a package manager runs unattended
  (`runnable?`); any other is given for the person to run. A tool already
  installed is left. Headless, nothing is run: the commands are listed.
  `{:text :installed}`."
  [config ctx & [{:keys [tools path]}]]
  (let [names (->> (if (sequential? tools) tools (str/split (str tools) #"[\s,]+"))
                   (map str/trim) (remove str/blank?) distinct)
        by-name (into {} (map (juxt :name identity)) @requirements)
        lines (for [n names
                    :let [req (by-name n)
                          st (when req (tool-status req {:path path}))
                          cmd (first (:install st))]]
                (cond
                  (nil? req) [n nil (str n ": not one of the tools /init knows")]
                  (:available? st) [n nil (str n ": already installed (" (:version st) ")")]
                  (nil? cmd) [n nil (str n ": no install command for this platform" (when (:url req) (str " — see " (:url req))))]
                  (not (runnable? cmd)) [n nil (str n ": run it yourself — " (str/join ", or " (:install st)))]
                  :else
                  (if-let [no (ask-person ctx {:type :tool-use :action :call :tool "init$install" :pattern "init$install"
                                               :display (str "install " n " with `" cmd "`")
                                               :args {:tool n :command cmd} :effects #{:process :network}})]
                    [n nil (str n ": not installed (" no ") — to install it: " cmd)]
                    (let [r (fx/run!! (fx/process-task {:out :string :err :string
                                                        :env (cond-> (assoc (into {} (System/getenv)) "HOMEBREW_NO_AUTO_UPDATE" "1")
                                                              path (assoc "PATH" path))}
                                                       "/bin/sh" "-c" cmd)
                                      install-timeout-ms)
                          ok (:ok r)]
                      (if (and ok (zero? (:exit ok)))
                        [n true (str n ": installed with `" cmd "`")]
                        [n nil (str n ": `" cmd "` failed — "
                                    (clip (str/trim (str (:err ok) (:out ok) (when (:timeout r) "no answer in time"))) 600))])))))]
    {:installed (vec (keep (fn [[n ok]] (when ok n)) lines))
     :text (if (seq lines)
             (str "Installing:\n" (str/join "\n" (map #(str "  " (nth % 2)) lines)))
             "")}))

;; --- what to do next (byj-gly.337.1) --------------------------------------------------------

(def next-steps
  "What /init's answer ends with: `resources/brainyard/init/next-steps.edn`."
  (delay (edn/read-string (slurp (io/resource "brainyard/init/next-steps.edn")))))

(defn next-facts
  "The facts next-steps' `:when` names, of `project-status`'s `p`."
  [p]
  (let [code? (boolean (seq (:build-files p)))]
    (cond-> #{(if (:git? p) :git :no-git) (if code? :code :no-code)}
      (and code? (= :placeholder (:project-section p))) (conj :draft-pending)
      (seq (:uncommitted p)) (conj :uncommitted)
      (:beads? p) (conj :beads)
      (:onboarding? p) (conj :onboarding))))

(defn- uncommitted-files [p]
  ;; `git status --porcelain` lines: the path after the status
  (str/join " " (map #(str/replace % #"^\S+\s+" "") (:uncommitted p))))

(defn- example-lines
  "The draft's example prompts (`init/agents-md`'s `examples`), one a line,
  each without the numbering or quotes a model may add anyway."
  [examples]
  (->> (str/split-lines (str examples))
       (map #(-> % str/trim (str/replace #"^(?:[-*•]|\d+[.)])\s*" "") (str/replace #"^[\"“](.*)[\"”]$" "$1") str/trim))
       (remove str/blank?)
       (take 3)
       vec))

(defn next-text
  "What to do next in project status `p`, from `steps` (default `next-steps`):
  `{:text :prompt}` — the entries whose `:when` facts all hold, in order, as a
  single-spaced Markdown list (`report-text`'s reason), and the first `:suggest`
  among them. An entry marked `:examples?` takes `examples`, the draft's
  prompts for this project, in place of its own, the first of them as its
  suggestion (byj-gly.337.3)."
  [p & [{:keys [steps examples]}]]
  (let [facts (next-facts p)
        ex (example-lines examples)
        shown (for [e (or steps @next-steps)
                    :when (every? facts (:when e))]
                (cond-> e
                  (and (:examples? e) (seq ex)) (assoc :prompts ex :suggest (first ex))))]
    {:prompt (or (some :suggest shown) "")
     :text (if (empty? shown)
             ""
             (str "Next:\n"
                  (str/join "\n" (for [{:keys [text command prompts]} shown]
                                   (str "- " text
                                        (when command (str " `" (str/replace command "{files}" (uncommitted-files p)) "`"))
                                        (str/join "" (for [q prompts] (str "\n  - \"" q "\""))))))))}))

;; --- starter issues (byj-gly.337.2) ---------------------------------------------------------

(def onboarding
  "The starter issues /init files: `resources/brainyard/init/onboarding.edn`."
  (delay (edn/read-string (slurp (io/resource "brainyard/init/onboarding.edn")))))

(defn- labelled
  "The issues in project `dir` labelled `label`, `status` a bd status list
  (all of them when nil): `[{:id :status}]`, nil when bd cannot say."
  [bd dir label status]
  (let [r (apply bd! bd dir "list" "--label" label "--json" "--brief"
                 (if status ["--status" status] ["--all"]))]
    (when (:ok? r)
      (try (vec (for [i (json/read-str (str/trim (:out r)))
                      :when (map? i)]
                  (select-keys i [:id :status])))
           (catch Throwable _ nil)))))

(defn- fill [s vars]
  (reduce (fn [s [k v]] (str/replace s (str "{" (name k) "}") (str v))) (str s) vars))

(defn onboard
  "File the starter issues in project `dir` with bd (`init$onboard`), from
  `onboarding` (default the shipped file): its epic, then each issue whose
  `:when` facts hold, as the epic's children, each labelled `:label`, then
  the `:after` dependencies; and one `:tool` issue for each tool `missing`
  names (`{:name :for :install}`, else init$check's missing optional and
  feature tools). Nothing is filed when the project has any issue with that
  label already, closed or not, nor without bd or `.beads/`.
  `{:filed [ids] :text}`, or `{:skipped :text}`."
  [config & [{:keys [dir path missing data]}]]
  (let [dir (or (not-empty (str dir)) (:project-dir config) (:working-dir config) ".")
        path (or path (System/getenv "PATH"))
        bd (on-path "bd" path)
        {:keys [label epic issues tool]} (or data @onboarding)
        skip (fn [why] {:skipped why :text (str "Starter issues not filed: " why ".")})]
    (cond
      (not bd) (skip "bd is not installed")
      (not (.isDirectory (io/file dir ".beads"))) (skip "beads is not set up here")
      :else
      (let [there (labelled bd dir label nil)]
        (cond
          (nil? there) (skip "bd could not list this project's issues")
          (seq there) {:skipped "already filed"
                       :text (str "Starter issues: filed before (" (count there) ", label " label "); `bd ready` shows the open ones.")}
          :else
          (let [facts (next-facts (project-status dir))
                missing (or missing
                            (for [t (map #(tool-status % {:path path}) @requirements)
                                  :when (and (not (:available? t)) (#{:optional :feature} (:need t)))]
                              {:name (:name t) :for (:for t)
                               :install (if (seq (:install t)) (str/join ", or " (map #(str "`" % "`") (:install t))) (or (:url t) "see its site"))}))
                create! (fn [{:keys [title type priority description]} & extra]
                          (let [r (apply bd! bd dir "create" "--silent" "--title" title "--type" (or type "task")
                                         "--priority" (str (or priority 2)) "--labels" label
                                         "--description" (str description) extra)]
                            (when (:ok? r) (last (remove str/blank? (map str/trim (str/split-lines (:out r))))))))
                epic-id (create! epic)]
            (if-not epic-id
              (skip "bd create failed")
              (let [shown (filter #(every? facts (:when %)) issues)
                    ids (into {} (for [i shown
                                       :let [id (create! i "--parent" epic-id)]
                                       :when id]
                                   [(:id i) id]))
                    _ (doseq [i shown, a (:after i)
                              :when (and (ids (:id i)) (ids a))]
                        (bd! bd dir "dep" "add" (ids (:id i)) (ids a)))
                    tools (vec (keep #(create! (update-vals (select-keys tool [:title :description]) (fn [s] (fill s %)))
                                               "--type" (or (:type tool) "chore") "--priority" (str (or (:priority tool) 4))
                                               "--parent" epic-id)
                                     missing))
                    filed (into [epic-id] (concat (vals ids) tools))]
                {:filed filed
                 :text (str "Starter issues, filed in beads (label " label "):\n  " epic-id " " (:title epic)
                            (str/join "" (for [i shown :when (ids (:id i))] (str "\n  " (ids (:id i)) " " (:title i))))
                            (str/join "" (for [[id t] (map vector tools missing)] (str "\n  " id " " (fill (:title tool) t))))
                            "\n`bd ready` shows the ones ready to start.")}))))))))

(defn onboarding-open?
  "Whether project `dir` has open starter issues (the label `onboarding`
  gives them)."
  [dir & [{:keys [path]}]]
  (let [bd (on-path "bd" (or path (System/getenv "PATH")))]
    (boolean (and bd (.isDirectory (io/file dir ".beads"))
                  (seq (labelled bd dir (:label @onboarding) "open,in_progress"))))))

(defn next-step
  "What to do next in project `dir` as it stands now (`init$next`): with
  `:onboarding` among the facts when it has open starter issues."
  [config & [{:keys [dir path examples]}]]
  (let [dir (or (not-empty (str dir)) (:project-dir config) (:working-dir config) ".")]
    (next-text (cond-> (project-status dir)
                 (onboarding-open? dir {:path path}) (assoc :onboarding? true))
               {:examples examples})))

;; --- the commands --------------------------------------------------------------------------

(defn commands
  "The `init$*` commands (RFC-040)."
  [config]
  [{:name "init$check" :kind :command
    :description "Where this machine and a project stand for brainyard: each tool it relies on (git, bd, tmux, claude, gh, codex) — found, its version, or how to install it — and the project's AGENTS.md, CLAUDE.md import, beads setup, skills and .gitignore. Reads only; installs nothing."
    :input-schema [:map [:dir {:optional true} [:string {:desc "the project directory; this project when absent"}]]]
    :output-schema [:map [:text :string] [:tools :any] [:project :any] [:missing-required :any]]
    :effects #{:fs/read :process}
    :fn (fn [args _] (check config args))}
   {:name "init$scaffold" :kind :command
    :description "Write a project's starting files: AGENTS.md, a CLAUDE.md that imports it, .brainyard/ in .gitignore. Creates what is absent; in what exists, writes only brainyard's marked blocks, the import line or the missing lines, after a snapshot — never the person's text. A second run writes nothing."
    :input-schema [:map
                   [:dir {:optional true} [:string {:desc "the project directory; this project when absent"}]]
                   [:dry-run {:optional true} [:any {:desc "true: say what would be written, write nothing"}]]
                   [:notes {:optional true} [:string {:desc "the project's own section of AGENTS.md (what it is, how to build and test it, its conventions), in its own block"}]]]
    :output-schema [:map [:text :string] [:writes :any] [:snapshot {:optional true} :string]]
    :effects #{:fs/read :fs/write}
    :fn (fn [args _] (scaffold config args))}
   {:name "init$beads" :kind :command
    :description "Set up beads in a project with bd itself: bd init when there is no .beads/, then bd setup claude (and codex, when installed) where bd's own check says it is missing. Run it after init$scaffold, so CLAUDE.md already imports AGENTS.md. Says what bd ran and any commit bd made of its own files."
    :input-schema [:map
                   [:dir {:optional true} [:string {:desc "the project directory; this project when absent"}]]
                   [:prefix {:optional true} [:string {:desc "the issue prefix; bd's default (the directory name) when absent"}]]]
    :output-schema [:map [:text :string] [:ran {:optional true} :any] [:commits {:optional true} :any] [:skipped {:optional true} :string]]
    :effects #{:fs/write :process}
    :fn (fn [args _] (beads config args))}
   {:name "init$survey" :kind :command
    :description "What a project shows of itself, for drafting its AGENTS.md section: top-level entries, CI workflows, recent commits, and the head of its README and build files. Reads only."
    :input-schema [:map [:dir {:optional true} [:string {:desc "the project directory; this project when absent"}]]]
    :output-schema [:map [:text :string]]
    :effects #{:fs/read :process}
    :fn (fn [args _] (survey config args))}
   {:name "init$next" :kind :command
    :description "What to do next in a project brainyard was just set up in: committing /init's files, drafting AGENTS.md once there is code, example prompts to start with, beads. `prompt` is the one to suggest first. Reads only."
    :input-schema [:map
                   [:dir {:optional true} [:string {:desc "the project directory; this project when absent"}]]
                   [:examples {:optional true} [:string {:desc "example requests for this project, one a line, shown in place of the generic ones"}]]]
    :output-schema [:map [:text :string] [:prompt :string]]
    :effects #{:fs/read :process}
    :fn (fn [args _] (next-step config args))}
   {:name "init$onboard" :kind :command
    :description "File a project's starter issues in beads with bd: a 'Get started with brainyard' epic with what to do first (commit /init's files, write the project's rules in AGENTS.md, say what the project is for, draft AGENTS.md once there is code) and one issue per missing optional tool. Run after init$beads. Files nothing when they were filed before."
    :input-schema [:map [:dir {:optional true} [:string {:desc "the project directory; this project when absent"}]]]
    :output-schema [:map [:text :string] [:filed {:optional true} :any] [:skipped {:optional true} :string]]
    :effects #{:fs/write :process}
    :fn (fn [args _] (onboard config args))}
   {:name "init$install" :kind :command
    :description "Install tools brainyard relies on, by name (codex, tmux, gh, …): each only after the person says yes, with a package manager that asks nothing (brew, npm); any other install is given for the person to run. Headless, only lists the commands."
    :input-schema [:map [:tools [:any {:desc "the tools to install, by name: a list, or names separated by commas or spaces"}]]]
    :output-schema [:map [:text :string] [:installed :any]]
    :effects #{:process :network}
    :fn (fn [args ctx] (install config ctx args))}])
