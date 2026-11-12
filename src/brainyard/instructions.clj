;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.instructions
  "The project's own instructions in every turn's prompt (RFC-040,
  byj-gly.309.1): its AGENTS.md and CLAUDE.md, as brainyard seeds them —
  `:project-instruction-paths` (default `[\"AGENTS.md\" \"CLAUDE.md\"]`;
  brainyard's `:reference-artifact-paths`, renamed beside
  `:user-instruction-paths`), resolved against the project directory then the working directory, a file
  reached two ways loaded once — brainyard's `core/config.clj`
  `reference-artifact-descriptors` — within one budget across them,
  `:project-instructions-max-chars` (`default-budget`, 32 KiB).

  Where they go is not brainyard's: it renders them under `## Live Artifacts`
  in the per-turn tail, and its own BRAINYARD.md as `## Project Instructions`
  in the session-stable zone. Here AGENTS.md is the instruction file that
  BRAINYARD.md is there (divergence `init-sets-up-the-project`), so they go
  where BRAINYARD.md goes: `agent/session-context`, above the cache breakpoint
  (RFC-015), read afresh each turn.

  A CLAUDE.md that imports AGENTS.md (`@AGENTS.md` on a line of its own, as
  Claude Code reads it) has the import followed one level: the imported file
  is loaded if it is not already, and the import line is not shown. A file
  left with nothing but blank lines is not shown at all."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-paths
  "The project's instruction files, in the order they are shown: AGENTS.md
  first — the one instruction file, read by brainyard, Codex and Claude Code
  through CLAUDE.md's import — then CLAUDE.md, whose import of it is then not
  shown again. brainyard's `:reference-artifact-paths` lists them the other
  way round, CLAUDE.md first (byj-gly.313)."
  ["AGENTS.md" "CLAUDE.md"])

(def default-user-paths
  "The person's own instruction files, read for every project:
  `~/.brainyard/AGENTS.md`. brainyard's is `~/.brainyard/BRAINYARD.md`
  (`load-brainyard-instructions`, rendered as `## User Instructions`); AGENTS.md
  replaces it here at user scope as in a project, so it is not read. A file
  another tool keeps for itself — `~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`,
  which brainyard only seeds BRAINYARD.md from — is the person's to add
  (byj-gly.313)."
  ["~/.brainyard/AGENTS.md"])

(def default-user-budget
  "How many characters of the person's own instruction files a prompt
  carries: a budget of their own, so a large project AGENTS.md never pushes
  out a person's preferences, which brainyard does not cap at all."
  8192)

(def default-budget
  "How many characters of the project's instruction files a prompt carries,
  across all of them: Codex's `project_doc_max_bytes` (32 KiB). brainyard's
  4000-character cap is for reference notes beside its own instruction file,
  which it does not cap; here these files are the instructions (byj-gly.312)."
  32768)

(defn- canonical [^java.io.File f] (try (.getCanonicalPath f) (catch Throwable _ nil)))

(defn- resolve-path
  "`p` as an existing file: absolute or `~/` as it is; relative against the
  project directory, then the working directory."
  [config p]
  (let [p (str p)
        home (System/getenv "HOME")
        p (if (and home (str/starts-with? p "~/")) (str home (subs p 1)) p)
        f (io/file p)
        candidates (if (.isAbsolute f)
                     [f]
                     (keep #(when % (io/file % p)) [(:project-dir config) (:working-dir config)]))]
    (some #(when (.isFile ^java.io.File %) %) candidates)))

(def ^:private import-line #"^\s*@(\S+)\s*$")

(defn- imports
  "The files `text`, in `dir`, imports — `@path` lines — that exist."
  [dir text]
  (vec (for [l (str/split-lines (str text))
             :let [[_ p] (re-matches import-line l)]
             :when p
             :let [f (io/file dir p)]
             :when (.isFile f)]
         f)))

(defn- load-files
  "The files at `paths` (resolved by `resolve-path` against `config`), in
  order, each once and none in `skip`: `[{:path :file :text}]`, `:path`
  canonical. An import is placed right after the file importing it."
  [config paths skip]
  (let [skip (set skip)]
    (loop [todo (keep #(resolve-path config %) paths) seen skip out []]
      (if-let [f (first todo)]
        (let [c (canonical f)]
          (if (or (nil? c) (seen c))
            (recur (rest todo) seen out)
            (let [text (try (slurp f) (catch Throwable _ nil))
                  ;; one level: what an import imports is not followed
                  imported (when (str/ends-with? c "CLAUDE.md") (imports (.getParentFile (io/file c)) text))]
              (recur (concat imported (rest todo)) (conj seen c) (conj out {:path c :file f :text text})))))
        out))))

(defn files
  "The project's instruction files (`:project-instruction-paths`), in order,
  each once: `[{:path :file :text}]`."
  [config]
  (load-files config (get config :project-instruction-paths default-paths) nil))

(defn user-files
  "The person's own instruction files, for every project
  (`:user-instruction-paths`, `default-user-paths`), in order, each once and
  none already among the project's."
  [config]
  ;; relative to home, never to the project: these are the person's
  (load-files {:project-dir (System/getenv "HOME")}
              (get config :user-instruction-paths default-user-paths)
              (map :path (files config))))

(def ^:private wording
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/instructions.edn")))))

(def ^:private fence #"^\s*(```|~~~).*")

(def ^:private comment-line #"^\s*<!--.*-->\s*$")

(defn shown-text
  "A file's text as the prompt quotes it, and whether anything of its own is
  left: `{:text :own?}`. Outside fenced code, a line that is only an HTML
  comment — bd's `<!-- BEGIN BEADS … -->`, /init's `<!-- BEGIN BRAINYARD -->`
  — is dropped, and so is a comment spanning lines; an import line of a file
  that is quoted too becomes a note saying where (`:import-note`, `where-of`
  giving \"above\" or \"below\"). `:own?` is false when nothing but such notes
  is left: a CLAUDE.md that is only `@AGENTS.md` is not quoted at all
  (byj-gly.314)."
  [{:keys [path text]} where-of label]
  (let [dir (.getParentFile (io/file path))
        note (:import-note @wording)]
    (loop [[l & more :as ls] (str/split-lines (str text)) in-fence? false in-comment? false out [] own? false]
      (if (empty? ls)
        {:text (str/trim (str/join "\n" out)) :own? own?}
        (cond
          in-comment? (recur more in-fence? (not (str/includes? l "-->")) out own?)
          (re-matches fence l) (recur more (not in-fence?) false (conj out l) true)
          in-fence? (recur more true false (conj out l) true)
          (re-matches comment-line l) (recur more false false out own?)
          (and (re-find #"^\s*<!--" l) (not (str/includes? l "-->"))) (recur more false true out own?)
          :else
          (let [[_ p] (re-matches import-line l)
                target (when p (canonical (io/file dir p)))
                where (when target (where-of target))]
            (if where
              (recur more false false (conj out (-> note (str/replace "{file}" (label target)) (str/replace "{where}" where))) own?)
              (recur more false false (conj out l) (or own? (not (str/blank? l)))))))))))

(defn- quoted
  "`text` between `<instructions source=…>` tags — the file kept whole, its
  own headings no longer read as the prompt's (byj-gly.314). A closing tag in
  the text is escaped, so it cannot end the quote early."
  [source text]
  (str "<instructions source=\"" source "\">\n"
       (str/replace text "</instructions>" "&lt;/instructions>")
       "\n</instructions>"))

(defn- last-heading
  "Where the last Markdown heading line in `text` begins — the index of the
  line break before it — or nil."
  [text]
  (loop [from 0 at nil]
    (if-let [i (str/index-of text "\n#" from)]
      (recur (inc i) (if (re-find #"^\n#{1,6} " (subs text i)) i at))
      at)))

(defn cut
  "`text` within `limit` characters: whole when it fits; else up to the last
  Markdown heading that begins before the limit — whole sections, not half a
  sentence — when that keeps at least half the limit, else up to the last
  line end; then where the rest is. Half: one long section early in a file
  would otherwise cost the rest of the budget for nothing."
  [text limit path]
  (if (<= (count text) limit)
    text
    (let [head (subs text 0 (max 0 limit))
          h (last-heading head)
          nl (str/last-index-of head "\n")
          stop (cond (and h (>= h (quot limit 2))) h
                     nl nl
                     :else (count head))
          kept (str/trimr (subs text 0 stop))]
      (str kept (when-not (str/blank? kept) "\n\n")
           "… (cut at " (count kept) " of " (count text) " characters: read " path " for the rest)"))))

(defn- render
  "One `## <title>` section of `fs` within `budget`, spent in order, or nil
  when none has anything of its own. Each file is quoted (`quoted`); one past
  what is left is cut (`cut`), and one with nothing left is named with where
  to read it."
  [{:keys [title intro]} fs budget label]
  (let [index (into {} (map-indexed (fn [i f] [(:path f) i])) fs)
        parts (loop [[f & more] fs left budget out []]
                (if-not f
                  out
                  (let [i (index (:path f))
                        where-of (fn [target] (when-let [j (index target)] (if (< j i) "above" "below")))
                        {:keys [text own?]} (shown-text f where-of label)]
                    (if-not own?
                      (recur more left out)
                      (let [body (if (pos? left)
                                   (cut text left (:path f))
                                   (str "… (not quoted: the instructions before it used the budget of " budget
                                        " characters; read " (:path f) ")"))]
                        (recur more (- left (count text)) (conj out (quoted (label (:path f)) body))))))))]
    (when (seq parts)
      (str "## " title "\n\n" intro "\n\n" (str/join "\n\n" parts) "\n"))))

(defn section
  "The prompt's instructions from files: `## Project Instructions` — the
  project's, within `:project-instructions-max-chars` (`default-budget`) —
  then `## User Instructions` — the person's, within
  `:user-instructions-max-chars` (`default-user-budget`) — brainyard's order
  (`:project-instructions`, …, `:user-instructions`). nil when neither has
  anything in it."
  [config]
  (let [project (some-> (or (:project-dir config) (:working-dir config)) io/file canonical)
        home (some-> (System/getenv "HOME") io/file canonical)
        label (fn [p] (cond (and project (str/starts-with? p (str project "/"))) (subs p (inc (count project)))
                            (and home (str/starts-with? p (str home "/"))) (str "~/" (subs p (inc (count home))))
                            :else p))
        parts (remove nil?
                      [(render (:project @wording) (files config)
                               (get config :project-instructions-max-chars default-budget) label)
                       (render (:user @wording) (user-files config)
                               (get config :user-instructions-max-chars default-user-budget) label)])]
    (when (seq parts) (str/join "\n" parts))))
