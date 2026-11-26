;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.projects
  "The person's projects, one folder each in user scope:
  `~/.brainyard/projects/<slug>/project.edn` — brainyard's
  `ai.brainyard.agent.core.projects` (its docs/design/project-registry.md),
  ported so that `by` and byj keep ONE registry on a machine that runs both:
  the same slug for the same directory, the same record, the same index
  (RFC-074).

  A project is registered as a session or a question starts in it, and by
  `byj project add`; nothing else writes here, and nothing is ever taken
  out but by `byj project prune` and `byj project remove`, which a person
  runs.

  - The slug is `<basename, reduced to [A-Za-z0-9._-]>-<first 8 hex of
    SHA-256 of the canonical path>`: readable in `ls`, without a space, and
    the same for the same directory every time, which is what makes
    registering again harmless. Two checkouts of one name differ by the hash.
  - slug -> path is read from the slug's `project.edn`, never worked out from
    the slug: a path cannot be recovered from a name that stands for it.
  - `index.edn`, slug -> path, is derived from the records and rewritten after
    every change; nothing reads it as the truth. Each process writes only its
    own slug's record, so two at once do not contend, and an index left stale
    is right again at the next write."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [brainyard.clock :as clock]
            [brainyard.effect :as fx]))

(def project-file
  "A project's record, the truth for its slug -> path."
  "project.edn")

(def index-file
  "slug -> path for every record: derived, never read as the truth."
  "index.edn")

(def schema-version
  "Stamped on the record and the index, brainyard's: a later shape can tell
  an old file from its own."
  1)

(def ^:private max-name-len
  "The readable half of a slug at most this long; the hash keeps it unique."
  48)

;; --- the slug -------------------------------------------------------------------

(defn canonical-path
  "`path` canonical and absolute, or nil when blank. Absolute only when it
  cannot be made canonical: a directory gone still gets the slug it had."
  [path]
  (when-let [p (some-> path str str/trim not-empty)]
    (let [f (io/file p)]
      (try (.getCanonicalPath f)
           (catch Throwable _ (.getAbsolutePath f))))))

(defn- sanitize-name
  "A basename reduced to `[A-Za-z0-9._-]`, runs of anything else one `-`, no
  separator at either end, at most `max-name-len`; `root` when nothing is
  left, as for `/`. brainyard's `sanitize-name`."
  [s]
  (let [cleaned (-> (or s "")
                    (str/replace #"[^A-Za-z0-9._-]+" "-")
                    (str/replace #"-{2,}" "-")
                    (str/replace #"^[-.]+" "")
                    (str/replace #"[-.]+$" ""))
        capped (cond-> cleaned (> (count cleaned) max-name-len) (subs 0 max-name-len))
        capped (str/replace capped #"[-.]+$" "")]
    (if (str/blank? capped) "root" capped)))

(defn- path-hash
  "The first 4 bytes of SHA-256 over the canonical path's UTF-8, as 8
  lowercase hex: brainyard's `path-hash`, byte for byte, or the two programs
  would keep two folders for one project."
  [^String canonical]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes canonical "UTF-8"))
       (take 4)
       (map #(format "%02x" (bit-and (int %) 0xff)))
       (apply str)))

(defn project-slug
  "The registry folder's name for `path`, or nil when blank. A project moved
  is a new slug, and its old record is listed as missing."
  [path]
  (when-let [cp (canonical-path path)]
    (str (sanitize-name (.getName (io/file cp))) "-" (path-hash cp))))

;; --- where ----------------------------------------------------------------------

(defn projects-root
  "`<home>/.brainyard/projects`: `home`, else the `brainyard.projects-home`
  property, else — in a process a test started — the environment's
  `BY_PROJECTS_HOME`, both the test runner's own home, so no test registers
  in the person's registry (as `changes/repo-root` skips the repository,
  provenance P-55); else $HOME. nil when there is no home. Not created."
  ([] (projects-root nil))
  ([home]
   (when-let [h (or (not-empty (str (or home "")))
                    (System/getProperty "brainyard.projects-home")
                    (not-empty (System/getenv "BY_PROJECTS_HOME"))
                    (System/getenv "HOME"))]
     (str (io/file h ".brainyard" "projects")))))

(defn project-user-dir
  "The folder of `path`'s record, `<root>/<slug>`, or nil. Not created."
  [root path]
  (when root
    (when-let [slug (project-slug path)]
      (str (io/file root slug)))))

;; --- a record -------------------------------------------------------------------

(defn- read-record
  "The map in `f`, or nil when it is not there, will not read, or is not a
  map: a broken record is left out of the list and written over at the
  project's next registration."
  [f]
  (when (.isFile (io/file f))
    (try (let [v (edn/read-string (slurp f))] (when (map? v) v))
         (catch Throwable _ nil))))

(defn- write-edn!
  "`m` to `f` through a file beside it and a rename, so a reader never sees
  half a record."
  [f m]
  (let [f (io/file f)
        tmp (io/file (.getParentFile f) (str "." (.getName f) "." (random-uuid) ".tmp"))]
    (try
      (spit tmp (with-out-str (pprint/pprint m)))
      (when-not (.renameTo tmp f)
        (io/copy tmp f))
      (finally (.delete tmp)))))

;; --- the origin, from .git/config -----------------------------------------------

(defn- git-dir
  "The project's `.git` directory, following a `gitdir:` file (a worktree, a
  submodule); nil when it has none."
  [project-path]
  (let [g (io/file project-path ".git")]
    (cond
      (.isDirectory g) g
      (.isFile g) (let [line (str/trim (slurp g))]
                    (when (str/starts-with? line "gitdir:")
                      (let [p (str/trim (subs line (count "gitdir:")))
                            f (io/file p)]
                        (if (.isAbsolute f) f (io/file project-path p)))))
      :else nil)))

(defn parse-origin-url
  "The first `url = …` of `[remote \"origin\"]` in a git config's text, or nil."
  [config-text]
  (loop [[line & more] (str/split-lines (str config-text)) in-origin? false]
    (when line
      (let [t (str/trim line)]
        (cond
          (str/starts-with? t "[") (recur more (some? (re-matches #"\[remote\s+\"origin\"\]" t)))
          (and in-origin? (re-find #"^url\s*=" t)) (some-> (subs t (inc (str/index-of t "="))) str/trim not-empty)
          :else (recur more in-origin?))))))

(defn read-git-remote
  "The project's origin URL, read from its git config rather than asked of
  git: a registration is on every session's way in, and the field is only
  information. nil on anything amiss. `brainyard.deps` reads it too, where a
  record's `:git-remote` is nil or stale (byj-339)."
  [project-path]
  (try
    (when-let [gd (git-dir project-path)]
      (let [cfg (io/file gd "config")]
        (when (.isFile cfg) (parse-origin-url (slurp cfg)))))
    (catch Throwable _ nil)))

(defn main-checkout
  "The main checkout of the repository whose linked worktree `path` is — its
  `.git` a `gitdir:` file naming `<common>/worktrees/<name>`, whose
  `commondir` names `<main>/.git` — or nil: a main checkout, a project
  outside git, a submodule. Read from the files, as the origin is."
  [path]
  (try
    (when-let [gd (git-dir path)]
      (let [cd (io/file gd "commondir")]
        (when (and (.isFile (io/file path ".git")) (.isFile cd))
          (let [common (.getCanonicalFile (io/file gd (str/trim (slurp cd))))]
            (when (= ".git" (.getName common))
              (.getParent common))))))
    (catch Throwable _ nil)))

(defn- worktree-of
  "`{:worktree-of <its main checkout's slug>}` for a linked worktree, else {}:
  a worktree is a project of its own, keyed by its own path (RFC-016's second
  revision, S4), and says whose it is."
  [cp]
  (if-let [m (main-checkout cp)] {:worktree-of (project-slug m)} {}))

;; --- the registry ---------------------------------------------------------------

(defn- opened-ms
  "`:last-opened-at` in epoch ms, 0 when absent or not an instant."
  [r]
  (try (or (some-> (:last-opened-at r) inst-ms) 0)
       (catch Throwable _ 0)))

(defn list-projects
  "Every record under `root`, the last opened first, each with `:missing?`
  true when its `:path` is no longer a directory — moved, deleted, or on a
  volume not mounted now. [] when there is no registry. A record
  `:listed? false` — a folder a run that is not registered left
  (`ensure-record!`) — only with `{:unlisted? true}`."
  ([root] (list-projects root nil))
  ([root {:keys [unlisted?]}]
   (or (when root
         (some->> (.listFiles (io/file root))
                  (filter #(.isDirectory %))
                  (keep #(read-record (io/file % project-file)))
                  (remove #(and (false? (:listed? %)) (not unlisted?)))
                  (map #(assoc % :missing? (not (.isDirectory (io/file (str (:path %)))))))
                  (sort-by opened-ms >)
                  vec))
       [])))

(defn refresh-index!
  "`index.edn` rewritten from the records: the slug -> path map, or nil when
  there is no root."
  [root]
  (when root
    (let [entries (into (sorted-map) (map (juxt :slug :path)) (list-projects root {:unlisted? true}))]
      (.mkdirs (io/file root))
      (write-edn! (io/file root index-file) {:schema-version schema-version :projects entries})
      entries)))

(defn- delete-record-dir!
  "`<root>/<slug>` and what is in it removed; true when it is gone. The slug
  was read off disk, so it is not trusted: only a bare name is taken, and
  only when the canonical folder is a direct child of the root's, so neither
  `..` nor a symlinked folder aims the delete anywhere else. Never throws: a
  prune stopped halfway would leave an index naming folders already gone."
  [root slug]
  (boolean
    (try
      (when (and (string? slug) (not (str/blank? slug)) (= slug (.getName (io/file slug))))
        (let [d (canonical-path (str (io/file root slug)))
              r (canonical-path root)]
          (when (and d r (= r (.getParent (io/file d))) (.isDirectory (io/file d)))
            (doseq [f (reverse (file-seq (io/file d)))]
              (.delete f))
            (not (.exists (io/file d))))))
      (catch Throwable _ false))))

(defn prune-projects!
  "Every record whose directory is missing removed, its folder whole, then
  the index rewritten; the records removed, as `list-projects` gave them.
  Only when a person asks: a missing directory may be a volume that comes
  back. The folder keeps what the project's runs left (RFC-016's second
  revision), so one `keep?` says to keep — a session younger than the TTL,
  or one a live process holds (`persist/keeps-folder?`) — is left, its
  record listed missing still."
  ([root] (prune-projects! root (constantly false)))
  ([root keep?]
   (if root
     (let [removed (filterv #(and (not (keep? %)) (delete-record-dir! root (:slug %)))
                            (filter :missing? (list-projects root {:unlisted? true})))]
       (when (seq removed) (refresh-index! root))
       removed)
     [])))

(defn remove-project!
  "The record of `slug` removed, whether its directory is there or not, then
  the index rewritten; the record, or nil when the slug is not registered.
  The project itself is never touched."
  [root slug]
  (when root
    (when-let [rec (first (filter #(= slug (:slug %)) (list-projects root {:unlisted? true})))]
      (when (delete-record-dir! root slug)
        (refresh-index! root)
        rec))))

(defn update-record!
  "Record `slug` rewritten with `m` merged in, a nil value taking its key out,
  then the index; the record, or nil when the slug is not registered. What a
  client marks on a project (byj-djq): `:deleted` (deleted softly, kept),
  `:listed? false` (taken off the list, its folder of the home kept)."
  [root slug m]
  (when root
    (when-let [rec (first (filter #(= slug (:slug %)) (list-projects root {:unlisted? true})))]
      (let [f (io/file root (:slug rec) project-file)
            prev (or (read-record f) {})
            next (into {} (remove (comp nil? val)) (merge prev m))]
        (write-edn! f next)
        (refresh-index! root)
        next))))

(defn register-project!
  "`path`'s record written, or brought up to date, and returned: its
  `:created-at` kept, keys this byj does not know (a newer `by`'s) kept,
  `:last-opened-at` now; then the index rewritten. nil when there is no root
  or the write fails — the registry is bookkeeping, and a read-only home
  stops no session."
  [root path]
  (when-let [dir (project-user-dir root path)]
    (try
      (let [cp (canonical-path path)
            f (io/file dir project-file)
            prev (read-record f)
            now (java.util.Date.)
            ;; added again: listed, and no longer deleted (byj-djq)
            rec (merge (dissoc (or prev {}) :listed? :worktree-of :deleted :deleted-at)
                       (worktree-of cp)
                       {:path cp
                        :slug (project-slug cp)
                        :name (.getName (io/file cp))
                        :git-remote (read-git-remote cp)
                        :created-at (or (:created-at prev) now)
                        :last-opened-at now
                        :schema-version schema-version})]
        (.mkdirs (io/file dir))
        (write-edn! f rec)
        (refresh-index! root)
        rec)
      (catch Throwable _ nil))))

(defn- under?
  "Is canonical path `p` strictly inside canonical directory `dir`?"
  [p dir]
  (and p dir (not= p dir) (str/starts-with? p (str (str/replace dir #"/+$" "") "/"))))

(defn inside-of
  "Why `dir` is no project of its own but a folder of another's (byj-661), or
  nil. `owner` is the project a session started in `dir` works in
  (`config/resolve-project-dir`: the git root above it, else the nearest
  folder whose `.brainyard/` the project shares): when that is not `dir`,
  `dir` is a folder of it, registered or not. Else, `dir` not a repository's
  root, a registered project's directory above it (a project outside git).
  A repository nested in another, a linked worktree and a submodule each
  have a `.git` of their own, and are their own. `{:path :slug :registered?
  :listed?}`: `:listed?` false for one the list leaves out or one deleted
  softly, which adding it lists again."
  [root dir owner]
  (let [cp (canonical-path dir)
        op (canonical-path owner)
        recs (list-projects root {:unlisted? true})
        said (fn [p r] {:path p :slug (or (:slug r) (project-slug p)) :registered? (boolean r)
                        :listed? (boolean (and r (not (false? (:listed? r))) (not (:deleted r))))})]
    (cond
      (and op (not= op cp)) (said op (some #(when (= op (:path %)) %) recs))
      (.exists (io/file cp ".git")) nil
      :else (when-let [r (->> recs
                              (filter #(and (not (:missing? %)) (not (:worktree-of %)) (under? cp (str (:path %)))))
                              (sort-by #(count (str (:path %))) >)
                              first)]
              (said (:path r) r)))))

(defn contained-in
  "The registered projects inside `dir` (byj-661), listed and not deleted,
  their worktrees left out: adding `dir` says them, and goes on.
  `[{:path :slug}]`."
  [root dir]
  (let [cp (canonical-path dir)]
    (->> (list-projects root)
         (filter #(and (not (:deleted %)) (not (:worktree-of %)) (under? (str (:path %)) cp)))
         (mapv #(select-keys % [:path :slug])))))

(defn ensure-record!
  "`path`'s folder holds a record: when it has none, one is written
  `:listed? false` — a folder that a run which is not registered (a flow, an
  evaluation, a loop evaluation's clone) left its sessions in, which the list
  leaves out and the gone-project prune removes whole (RFC-016's second
  revision, S5). A registration later lists it. A record already there is
  never touched. The record, or nil when there is no root or the write fails."
  [root path]
  (when-let [dir (project-user-dir root path)]
    (let [f (io/file dir project-file)]
      (or (read-record f)
          (try
            (let [cp (canonical-path path)
                  now (java.util.Date.)
                  rec {:path cp
                       :slug (project-slug cp)
                       :name (.getName (io/file cp))
                       :listed? false
                       :worktree-of (:worktree-of (worktree-of cp))
                       :created-at now
                       :last-opened-at now
                       :schema-version schema-version}]
              (.mkdirs (io/file dir))
              (write-edn! f rec)
              (refresh-index! root)
              rec)
            (catch Throwable _ nil))))))

(defn move-project!
  "The folder of `old-slug` — a project moved or renamed, its runs' records
  in it — made `new-path`'s (RFC-016's second revision, S6): renamed to
  `new-path`'s slug, and the record registered at the new path, keeping its
  `:created-at` and what it does not know. `new-path`'s own folder may be
  there holding its record alone (a run there since the move registered
  it), which the old one replaces; one holding more is refused, as is an
  unknown slug, a path that is not a directory, or the same slug.
  `{:record}` or `{:error}`. Nothing is matched by guessing: the person
  names both."
  [root old-slug new-path]
  (let [cp (canonical-path new-path)
        new-slug (some-> cp project-slug)
        old (when (and root (string? old-slug) (= old-slug (.getName (io/file old-slug))))
              (io/file root old-slug))
        to (when (and root new-slug) (io/file root new-slug))]
    (cond
      (nil? root) {:error "no home to keep a registry in"}
      (not (and old (.isFile (io/file old project-file)))) {:error (str "No such registered project: " old-slug)}
      (not (and cp (.isDirectory (io/file cp)))) {:error (str "Not a directory: " new-path)}
      (= old-slug new-slug) {:error (str old-slug " is already " cp "'s")}
      (and (.exists to) (seq (remove #(= project-file (.getName ^java.io.File %)) (.listFiles to))))
      {:error (str cp " has a folder of its own already, " new-slug ", holding what its runs left; "
                   "forget one of the two first (byj project remove)")}
      :else
      (do (when (.exists to) (delete-record-dir! root new-slug))
          (if (.renameTo old to)
            (if-let [rec (register-project! root cp)]
              {:record rec}
              {:error (str "moved to " new-slug ", but its record could not be written")})
            {:error (str "could not rename " old " to " to)})))))

;; --- what a project's runs leave -------------------------------------------------

(def runtime-kinds
  "What a project's runs leave besides its sessions, transcripts and tasks
  (`persist/kinds`), each a path under the project's folder in the registry
  (RFC-016's second revision, byj-84c.4) and under `<project>/.brainyard/`
  before it: a folder, or a file where it names one."
  ["logs" "claude-agents" "codex-agents" "workflow-runs" "deps-snapshots"
   "init/snapshots" "captures" "exports" "drive/claims.edn"
   "store.edn" "todos" "rails.edn" "schedule" "loops/proposals" "loops/evals"
   "deps-local.edn" "worktrees" "tools/proposals" "skills/proposals" "teams"])

(def partial-kinds
  "What a project's runs leave in a folder that also holds what the person
  decided, which stays in the project (byj-3p0): of each, what moves to the
  home — a loop task set, built from the project's sessions, but not the
  cases a person wrote beside it (`<set>.cases.edn`); a program's datasets,
  evals and proposals, but not its params (`<id>.edn`), which a person
  accepted."
  {"loops/tasks" (fn [^java.io.File e]
                   (and (.isFile e) (str/ends-with? (.getName e) ".edn")
                        (not (str/ends-with? (.getName e) ".cases.edn"))))
   "programs" (fn [^java.io.File e]
                (and (.isDirectory e) (contains? #{"datasets" "evals" "proposals"} (.getName e))))})

(defn auto-migrate?
  "Is what a project's `.brainyard/` kept of its runs moved on its own the
  first time it is touched? Only into the person's own home: never into a
  home the `brainyard.projects-home` property or `BY_PROJECTS_HOME` gave — a
  test run's — which would carry a real project's records (this repository's
  own, a turn's project when a test gives none) into a home the next run
  empties (byj-7sp.4)."
  []
  (boolean (and (System/getenv "HOME")
                (empty? (System/getProperty "brainyard.projects-home"))
                (empty? (System/getenv "BY_PROJECTS_HOME")))))

(defn- move-path!
  "`from` renamed to `to`, or moved by `mv` across file systems; true when
  done."
  [^java.io.File from ^java.io.File to]
  (.mkdirs (.getParentFile to))
  (or (.renameTo from to)
      (try (= 0 (:exit (fx/sh {:out :string :err :string} "mv" (.getPath from) (.getPath to))))
           (catch Throwable _ false))))

(defn- remove-empty-up!
  "`d` and each empty folder above it removed, up to `stop`, never `stop`."
  [^java.io.File d ^java.io.File stop]
  (loop [d d]
    (when (and d (not= (.getCanonicalPath d) (.getCanonicalPath stop))
               (.isDirectory d) (empty? (.listFiles d)) (.delete d))
      (recur (.getParentFile d)))))

(defn- holds-worktree?
  "Is there a linked git worktree — a folder whose `.git` is a file — at `f`
  or within `depth` folders under it?"
  [^java.io.File f depth]
  (and (.isDirectory f)
       (or (.isFile (io/file f ".git"))
           (and (pos? depth)
                (boolean (some #(holds-worktree? % (dec depth)) (or (.listFiles f) [])))))))

(defn- move-entries!
  "Each entry of folder `from` that `movable?` takes, moved under `to` where
  `to` lacks it, never one holding a worktree; a folder it does not take is
  looked into, and removed once that leaves it empty. The entries moved."
  [^java.io.File from ^java.io.File to movable?]
  (reduce + 0
          (for [^java.io.File e (or (.listFiles from) [])
                :let [t (io/file to (.getName e))]
                :when (not (holds-worktree? e 3))]
            (cond
              (movable? e) (if (and (not (.exists t)) (move-path! e t)) 1 0)
              (.isDirectory e) (let [n (move-entries! e t movable?)]
                                 (when (empty? (.listFiles e)) (.delete e))
                                 n)
              :else 0))))

(defn migrate-kind!
  "`kind` of what the project at `cp` kept in its own `.brainyard/` moved to
  `to`: a file whole when `to` is not there; a folder entry by entry, what
  `to` lacks; the rest left where it is, and the folders it leaves empty
  removed. An entry holding a git worktree is never moved (byj-84c.5): git
  keeps its path, and Claude Code and Codex keep a delegated session by the
  directory it ran in, so a worktree moved would lose both; it stays until it
  is removed, and new ones are made in the home. The entries moved."
  [cp kind ^java.io.File to]
  (let [from (io/file cp ".brainyard" kind)]
    (if-not (and (.exists from) (not= (.getCanonicalPath from) (.getCanonicalPath to)))
      0
      (let [movable? (get partial-kinds kind (constantly true))
            n (cond
                (.isFile from) (if (.exists to) 0 (if (move-path! from to) 1 0))
                (.isFile to) 0
                :else (move-entries! from to movable?))]
        (remove-empty-up! (if (.exists from) from (.getParentFile from)) (io/file cp ".brainyard"))
        n))))

(defonce ^:private migrated
  ;; [project kind] -> the legacy path's mtime when last moved from
  (atom {}))

(defn runtime-path
  "Where the project at `project` keeps `kind` of what its runs leave
  (`runtime-kinds`), then `more` under it: the one function every such path
  is built by (RFC-016's second revision, byj-84c.4) —
  `<projects-root>/<slug>/<kind>`, keyed by the project's path as the
  registry is; the project's own `.brainyard/<kind>` when there is no home.
  What the project's `.brainyard/` kept of it is moved here the first time
  it is touched, into the person's own home only (`auto-migrate?`), and the
  folder, once there, holds its record (`ensure-record!`)."
  [project kind & more]
  (let [cp (or (canonical-path (str (or project "."))) ".")
        root (projects-root)]
    (if-let [dir (project-user-dir root cp)]
      (let [d (io/file dir)
            to (io/file d kind)
            legacy (io/file cp ".brainyard" kind)
            stamp (when (.exists legacy) (.lastModified legacy))]
        (when (and stamp (auto-migrate?) (not= stamp (get @migrated [cp kind])))
          (swap! migrated assoc [cp kind] stamp)
          (try (migrate-kind! cp kind to) (catch Throwable _ nil)))
        (when (and (.isDirectory d) (not (.isFile (io/file d project-file))))
          (ensure-record! root cp))
        (apply io/file to more))
      (apply io/file cp ".brainyard" kind more))))

(defn project-path-for-slug
  "The path `slug`'s record names, or nil when it names none. Read from the
  record, never the index, which knows nothing the records do not."
  [root slug]
  (when root
    (when-let [slug (some-> slug str str/trim not-empty)]
      (when (= slug (.getName (io/file slug)))
        (:path (read-record (io/file root slug project-file)))))))

;; --- shown ----------------------------------------------------------------------

(defn- day
  "An instant as its day, `yyyy-MM-dd`, at `offset` seconds east of UTC;
  blank when it is not an instant."
  [t offset]
  (try (subs (clock/stamp (inst-ms t) (or offset 0) false) 0 10)
       (catch Throwable _ "")))

(defn table
  "The list as `by projects list` prints it: SLUG NAME OPENED PATH, OPENED
  the local day (UTC's when the OS cannot say its offset), a missing
  directory marked `(missing)`."
  ([rows] (table rows (clock/offset-seconds)))
  ([rows offset]
   (let [cells (for [r rows]
                 [(str (:slug r)) (str (:name r)) (day (:last-opened-at r) offset)
                  (str (:path r) (when (:missing? r) "  (missing)"))])
         all (cons ["SLUG" "NAME" "OPENED" "PATH"] cells)
         ws (for [i (range 3)] (reduce max (map #(count (nth % i)) all)))]
     (for [row all]
       (str/join "  " (concat (map (fn [w c] (str c (apply str (repeat (- w (count c)) " ")))) ws (take 3 row))
                              [(nth row 3)]))))))
