;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.projects-test
  "The person's project registry, `~/.brainyard/projects` (RFC-074): the
  slug brainyard's `by` computes, the record it writes, and `byj projects`.
  Every registry here is in a temporary home."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.json :as json]
            [brainyard.main :as main]
            [brainyard.projects :as projects]))

(defn- temp-dir [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- root-in [home] (projects/projects-root home))

(deftest the-slug-is-brainyards-byte-for-byte
  ;; by and byj keep one folder for one project only if both name it alike:
  ;; the first 4 bytes of SHA-256 over the canonical path's UTF-8, pinned
  ;; here against shasum -a 256
  (is (= "acd543c9" (#'projects/path-hash "/srv/work/my-app")))
  (is (= "2956a2cf" (#'projects/path-hash "/srv/work/한글 app")) "UTF-8, not the platform's charset")
  (let [d (temp-dir "proj-slug")
        slug (projects/project-slug d)]
    (is (re-matches #"proj-slug-[0-9a-f-]+-[0-9a-f]{8}" slug))
    (is (= slug (projects/project-slug (str d "/."))) "the canonical path, so the same directory is one slug"))
  (testing "the basename reduced to [A-Za-z0-9._-], `root` for /"
    (is (str/starts-with? (projects/project-slug "/srv/My Cool App (v2)") "My-Cool-App-v2-"))
    (is (str/starts-with? (projects/project-slug "/") "root-"))
    (is (str/starts-with? (projects/project-slug "/srv/한글") "root-") "nothing left of the name")
    (is (nil? (projects/project-slug "  ")))))

(deftest a-registration-keeps-created-at-and-what-it-does-not-know
  (let [home (temp-dir "proj-home")
        root (root-in home)
        p (temp-dir "proj-a")
        _ (.mkdirs (io/file p ".git"))
        _ (spit (io/file p ".git" "config") "[core]\n\tbare = false\n[remote \"upstream\"]\n\turl = u\n[remote \"origin\"]\n\turl = git@example.invalid:o/r.git\n")
        a (projects/register-project! root p)
        f (io/file root (:slug a) "project.edn")]
    (is (= p (:path a)))
    (is (= (projects/project-slug p) (:slug a)))
    (is (= "git@example.invalid:o/r.git" (:git-remote a)) "origin's url, not another remote's")
    (is (inst? (:created-at a)))
    (is (= 1 (:schema-version a)))
    (testing "a newer by's key, and created-at, survive the next registration"
      (spit f (pr-str (assoc (edn/read-string (slurp f)) :future-key 7)))
      (let [b (projects/register-project! root p)]
        (is (= (:created-at a) (:created-at b)))
        (is (= 7 (:future-key b)))
        (is (<= (inst-ms (:last-opened-at a)) (inst-ms (:last-opened-at b))))))
    (testing "the index is derived from the records"
      (is (= {(:slug a) p} (:projects (edn/read-string (slurp (io/file root "index.edn")))))))
    (testing "slug -> path is read from the record"
      (is (= p (projects/project-path-for-slug root (:slug a))))
      (is (nil? (projects/project-path-for-slug root "nope-00000000")))
      (is (nil? (projects/project-path-for-slug root "../x"))))))

(deftest a-record-by-wrote-is-read-as-byjs
  ;; the shape brainyard's register-project! pprints
  (let [home (temp-dir "proj-home")
        root (root-in home)
        p (temp-dir "proj-by")
        slug (projects/project-slug p)]
    (.mkdirs (io/file root slug))
    (spit (io/file root slug "project.edn")
          (str "{:path \"" p "\",\n :slug \"" slug "\",\n :name \"x\",\n :git-remote nil,\n"
               " :created-at #inst \"2026-09-16T16:24:25.164-00:00\",\n"
               " :last-opened-at #inst \"2026-09-16T16:24:41.454-00:00\",\n :schema-version 1}\n"))
    (let [[r] (projects/list-projects root)]
      (is (= p (:path r)))
      (is (= 1789575881454 (inst-ms (:last-opened-at r))))
      (is (false? (:missing? r))))
    (is (= 1789575865164 (inst-ms (:created-at (projects/register-project! root p))))
        "registering it again keeps by's created-at")))

(deftest a-missing-project-is-listed-and-only-pruned-when-asked
  (let [home (temp-dir "proj-home")
        root (root-in home)
        keep-dir (temp-dir "proj-keep")
        gone-dir (temp-dir "proj-gone")
        k (projects/register-project! root keep-dir)
        g (projects/register-project! root gone-dir)]
    (.delete (io/file gone-dir))
    (let [rows (projects/list-projects root)]
      (is (= #{(:slug k) (:slug g)} (set (map :slug rows))) "a missing one is listed, not dropped")
      (is (= {(:slug k) false (:slug g) true} (into {} (map (juxt :slug :missing?)) rows))))
    (is (= [(:slug g)] (mapv :slug (projects/prune-projects! root))))
    (is (= [(:slug k)] (mapv :slug (projects/list-projects root))))
    (is (= {(:slug k) keep-dir} (:projects (edn/read-string (slurp (io/file root "index.edn"))))))
    (testing "remove takes one, its directory there or not; the project is untouched"
      (is (= (:slug k) (:slug (projects/remove-project! root (:slug k)))))
      (is (.isDirectory (io/file keep-dir)))
      (is (empty? (projects/list-projects root)))
      (is (nil? (projects/remove-project! root (:slug k)))))))

(deftest a-slug-off-disk-never-aims-the-delete-elsewhere
  ;; the slug is read back from a record, so it is not trusted
  (let [home (temp-dir "proj-home")
        root (root-in home)
        victim (temp-dir "proj-victim")]
    (spit (io/file victim "keep.txt") "x")
    (.mkdirs (io/file root))
    (doseq [slug [".." "../x" victim "" nil]]
      (is (false? (#'projects/delete-record-dir! root slug)) (pr-str slug)))
    (is (.isFile (io/file victim "keep.txt")))))

(defn- run-main
  "`byj args…` in this process: `{:exit :out :err}`, as main-test's."
  [in & args]
  (let [err (java.io.StringWriter.)
        code (atom nil)
        out (with-out-str
              (with-in-str (or in "")
                (binding [*err* err
                          main/*exit* (fn [c] (throw (ex-info "exit" {::exit c})))]
                  (try (apply #'main/main* args)
                       (catch clojure.lang.ExceptionInfo e
                         (if-let [c (::exit (ex-data e))] (reset! code c) (throw e)))))))]
    {:exit @code :out out :err (str err)}))

(deftest byj-projects-lists-adds-and-forgets
  (let [home (temp-dir "proj-home")
        p (temp-dir "proj-cli")
        gone (temp-dir "proj-cli-gone")
        before (System/getProperty "brainyard.projects-home")]
    (System/setProperty "brainyard.projects-home" home)
    (try
      (is (= "No registered projects.\n" (:out (run-main nil "projects"))))
      (let [{:keys [exit out]} (run-main nil "projects" "add" p)
            slug (projects/project-slug p)]
        (is (= 0 exit))
        (is (= (str slug " " p "\n") out))
        (testing "add takes the working directory when no DIR is given"
          (is (= 0 (:exit (run-main nil "-C" gone "projects" "add" "--json")))))
        (testing "list: the table, the last opened first, and --json"
          (let [lines (str/split-lines (:out (run-main nil "projects" "list")))]
            (is (re-matches #"SLUG\s+NAME\s+OPENED\s+PATH" (first lines)))
            (is (str/includes? (second lines) gone))
            (is (str/ends-with? (nth lines 2) p)))
          (let [rows (json/read-str (:out (run-main nil "projects" "list" "--json")))]
            (is (= #{p gone} (set (map :path rows))))
            (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}-00:00" (:created-at (first rows))))))
        (testing "path"
          (is (= (str p "\n") (:out (run-main nil "projects" "path" slug))))
          (is (= 1 (:exit (run-main nil "projects" "path" "nope-00000000")))))
        (testing "add refuses what is not a directory"
          (is (= 1 (:exit (run-main nil "projects" "add" (str p "/no-such"))))))
        (.delete (io/file gone))
        (testing "prune asks, and with nothing to answer removes nothing"
          (let [{:keys [out]} (run-main nil "projects" "prune")]
            (is (str/includes? out gone))
            (is (str/includes? out "Cancelled.")))
          (is (= 2 (count (projects/list-projects (root-in home))))))
        (testing "prune --yes, then remove answered y"
          (is (str/includes? (:out (run-main nil "projects" "prune" "--yes")) "Pruned 1 entry."))
          (is (str/includes? (:out (run-main "y\n" "projects" "remove" slug)) (str "Removed: " slug)))
          (is (.isDirectory (io/file p)) "the project itself is never touched")
          (is (= 1 (:exit (run-main nil "projects" "remove" slug))))))
      (is (= 2 (:exit (run-main nil "projects" "bogus"))))
      (finally
        (if before
          (System/setProperty "brainyard.projects-home" before)
          (System/clearProperty "brainyard.projects-home"))))))

(deftest a-question-registers-its-project-and-a-clones-does-not
  (let [home (temp-dir "proj-home")
        p (temp-dir "proj-ask")
        before (System/getProperty "brainyard.projects-home")]
    (System/setProperty "brainyard.projects-home" home)
    (try
      (run-main nil "ask" "-p" "mock" "-C" p "--no-persist" "hi")
      (is (= [p] (mapv :path (projects/list-projects (root-in home)))))
      (let [q (temp-dir "proj-clone")]
        (.mkdirs (io/file q ".brainyard"))
        (spit (io/file q ".brainyard" "config.edn") "{:agent {:config {:register-project? false}}}")
        (run-main nil "ask" "-p" "mock" "-C" q "--no-persist" "hi")
        (is (= [p] (mapv :path (projects/list-projects (root-in home)))) ":register-project? false"))
      (finally
        (if before
          (System/setProperty "brainyard.projects-home" before)
          (System/clearProperty "brainyard.projects-home"))))))

(defn- session-in!
  "A session kept in `slug`'s folder of `root`, last saved `at` (epoch ms),
  its snapshot and meta.edn naming project `p`."
  [root slug id at p]
  (let [d (io/file root slug "sessions" id)]
    (.mkdirs d)
    (spit (io/file d "session.edn") (pr-str {:id id :updated-at at :project-dir p :working-dir (str p "/src")}))
    (spit (io/file d "meta.edn") (pr-str {:session-id id :project-dir p}))
    d))

(deftest the-registry-keeps-a-projects-runtime
  ;; byj-84c.3: the project's folder in the registry keeps what its runs
  ;; left (RFC-016's second revision): prune keeps it while a session in it is
  ;; young, remove says what it holds, move adopts it, a worktree says whose
  (let [home (temp-dir "proj-home")
        root (root-in home)
        old-ms (- (System/currentTimeMillis) (* 20 24 60 60 1000))
        before (System/getProperty "brainyard.projects-home")]
    (System/setProperty "brainyard.projects-home" home)
    (try
      (testing "prune keeps a missing project whose sessions are young, and removes one whose are past the TTL, whole"
        (let [young (temp-dir "proj-young") stale (temp-dir "proj-stale")
              ys (:slug (projects/register-project! root young))
              ss (:slug (projects/register-project! root stale))]
          (session-in! root ys "1700000000001-aaaaaaaa" (System/currentTimeMillis) young)
          (session-in! root ss "1700000000002-bbbbbbbb" old-ms stale)
          (.delete (io/file young)) (.delete (io/file stale))
          (let [{:keys [out]} (run-main nil "projects" "prune")]
            (is (str/includes? out "and the 1 session(s) kept for them") "it says what goes with them"))
          (let [{:keys [out]} (run-main nil "projects" "prune" "--yes")]
            (is (str/includes? out "Pruned 1 entry."))
            (is (str/includes? out (str "Kept 1, whose sessions are younger than 14 days or open: " ys))))
          (is (not (.exists (io/file root ss))) "the stale one's folder, sessions and all")
          (is (.isDirectory (io/file root ys "sessions" "1700000000001-aaaaaaaa")))
          (is (= [ys] (mapv :slug (filter :missing? (projects/list-projects root)))) "listed missing still")))
      (testing "remove says how many sessions go with the project"
        (let [p (temp-dir "proj-rm")
              s (:slug (projects/register-project! root p))]
          (session-in! root s "1700000000003-cccccccc" old-ms p)
          (is (str/includes? (:out (run-main nil "projects" "remove" s)) "and the 1 session(s) kept for it?"))
          (is (.isDirectory (io/file root s)) "unanswered, nothing goes")))
      (testing "move: a moved project's folder adopted at its new path, its sessions told"
        (let [from (temp-dir "proj-from")
              s (:slug (projects/register-project! root from))
              _ (session-in! root s "1700000000004-dddddddd" old-ms from)
              to (temp-dir "proj-to")]
          (.delete (io/file from))
          ;; a run at the new path since the move registered it: its folder
          ;; holds its record alone, which the old one replaces
          (projects/register-project! root to)
          (let [{:keys [exit out]} (run-main nil "projects" "move" s to)
                ns (projects/project-slug to)]
            (is (= 0 exit) out)
            (is (str/includes? out "1 session(s) told their new place"))
            (is (not (.exists (io/file root s))))
            (is (= to (:path (edn/read-string (slurp (io/file root ns "project.edn"))))))
            (let [snap (edn/read-string (slurp (io/file root ns "sessions" "1700000000004-dddddddd" "session.edn")))
                  meta (edn/read-string (slurp (io/file root ns "sessions" "1700000000004-dddddddd" "meta.edn")))]
              (is (= to (:project-dir snap)))
              (is (= (str to "/src") (:working-dir snap)))
              (is (= to (:project-dir meta))))
            (testing "refused when the new path's folder holds what its own runs left"
              (let [other (temp-dir "proj-other")
                    os (:slug (projects/register-project! root other))]
                (session-in! root os "1700000000005-eeeeeeee" old-ms other)
                (let [{:keys [exit err]} (run-main nil "projects" "move" ns other)]
                  (is (= 1 exit))
                  (is (str/includes? err "has a folder of its own already")))
                (is (.isDirectory (io/file root ns "sessions" "1700000000004-dddddddd")) "nothing moved"))))))
      (testing "a worktree says whose it is, and the list shows worktrees and unlisted folders with --all only"
        (let [main-dir (temp-dir "proj-main")
              wt (temp-dir "proj-wt")
              gd (io/file main-dir ".git" "worktrees" "wt")]
          (.mkdirs gd)
          (spit (io/file gd "commondir") "../..")
          (spit (io/file wt ".git") (str "gitdir: " (.getPath gd)))
          (is (= main-dir (projects/main-checkout wt)))
          (is (nil? (projects/main-checkout main-dir)) "a main checkout is no one's worktree")
          (projects/register-project! root main-dir)
          (is (= (projects/project-slug main-dir) (:worktree-of (projects/register-project! root wt))))
          (projects/ensure-record! root (temp-dir "proj-unlisted"))
          (let [paths #(set (map :path (json/read-str (:out (apply run-main nil "projects" "list" "--json" %)))))]
            (is (contains? (paths []) main-dir))
            (is (not (contains? (paths []) wt)))
            (is (contains? (paths ["--all"]) wt))
            (is (= 1 (- (count (paths ["--all"])) (count (paths [])) 1)) "and the unlisted one"))))
      (finally
        (if before
          (System/setProperty "brainyard.projects-home" before)
          (System/clearProperty "brainyard.projects-home"))))))
