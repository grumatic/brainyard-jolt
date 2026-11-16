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
  `byj projects add`; nothing else writes here, and nothing is ever taken
  out but by `byj projects prune` and `byj projects remove`, which a person
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
            [brainyard.clock :as clock]))

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

(defn- read-git-remote
  "The project's origin URL, read from its git config rather than asked of
  git: a registration is on every session's way in, and the field is only
  information. nil on anything amiss."
  [project-path]
  (try
    (when-let [gd (git-dir project-path)]
      (let [cfg (io/file gd "config")]
        (when (.isFile cfg) (parse-origin-url (slurp cfg)))))
    (catch Throwable _ nil)))

;; --- the registry ---------------------------------------------------------------

(defn- opened-ms
  "`:last-opened-at` in epoch ms, 0 when absent or not an instant."
  [r]
  (try (or (some-> (:last-opened-at r) inst-ms) 0)
       (catch Throwable _ 0)))

(defn list-projects
  "Every record under `root`, the last opened first, each with `:missing?`
  true when its `:path` is no longer a directory — moved, deleted, or on a
  volume not mounted now. [] when there is no registry."
  [root]
  (or (when root
        (some->> (.listFiles (io/file root))
                 (filter #(.isDirectory %))
                 (keep #(read-record (io/file % project-file)))
                 (map #(assoc % :missing? (not (.isDirectory (io/file (str (:path %)))))))
                 (sort-by opened-ms >)
                 vec))
      []))

(defn refresh-index!
  "`index.edn` rewritten from the records: the slug -> path map, or nil when
  there is no root."
  [root]
  (when root
    (let [entries (into (sorted-map) (map (juxt :slug :path)) (list-projects root))]
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
  "Every record whose directory is missing removed, then the index rewritten;
  the records removed, as `list-projects` gave them. Only when a person asks:
  a missing directory may be a volume that comes back."
  [root]
  (if root
    (let [removed (filterv #(delete-record-dir! root (:slug %)) (filter :missing? (list-projects root)))]
      (when (seq removed) (refresh-index! root))
      removed)
    []))

(defn remove-project!
  "The record of `slug` removed, whether its directory is there or not, then
  the index rewritten; the record, or nil when the slug is not registered.
  The project itself is never touched."
  [root slug]
  (when root
    (when-let [rec (first (filter #(= slug (:slug %)) (list-projects root)))]
      (when (delete-record-dir! root slug)
        (refresh-index! root)
        rec))))

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
            rec (merge (or prev {})
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
