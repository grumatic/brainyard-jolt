;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.sandbox
  "The macOS seatbelt sandbox (RFC-012) — brainyard's `components/os-sandbox`:
  build a seatbelt profile and the `sandbox-exec` argv, and relaunch the CLI
  under it, so the agent's writes are contained whatever a tool decides to do.

  The permission gate refuses a call; this contains the process that makes it.
  Nothing here runs unless `--sandbox` (or BY_SANDBOX) is given."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]))

(def sandbox-exec
  "brainyard pins the path rather than resolving it on PATH."
  "/usr/bin/sandbox-exec")

(defn macos? [] (str/starts-with? (str/lower-case (str (System/getProperty "os.name"))) "mac"))

(defn job-container?
  "Whether this process runs in a container made for one job and thrown away
  after it — a grading service's jobs, which say so with
  BYJ_JOB_CONTAINER=1 (`env-fn` a test's). There the container contains a
  task's code as seatbelt does a person's machine: nothing of the person's
  is in it to reach, and it is gone with the job."
  [& [env-fn]]
  (= "1" ((or env-fn #(System/getenv %)) "BYJ_JOB_CONTAINER")))

(defn available?
  "{:ok? true} or {:ok? false :reason …} — brainyard's `available?`."
  []
  (cond
    (not (macos?)) {:ok? false :reason "--sandbox is macOS-only (uses sandbox-exec / seatbelt)."}
    (not (.canExecute (io/file sandbox-exec))) {:ok? false :reason (str sandbox-exec " not found or not executable; cannot sandbox.")}
    :else {:ok? true}))

(defn- sb-str
  "An SBPL string literal: escape backslashes, then quotes."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- strip-slash [p]
  (let [p (str p)]
    (if (and (> (count p) 1) (str/ends-with? p "/")) (subs p 0 (dec (count p))) p)))

(defn write-roots
  "The directories the child may write to, in brainyard's order."
  [{:keys [home cwd project-dir tmpdir extra-writes]}]
  (->> (concat [tmpdir "/private/var/folders" "/var/folders" "/tmp" "/private/tmp"
                (when home (str home "/.brainyard"))
                (when home (str home "/Library/Caches"))
                cwd project-dir]
               extra-writes)
       (remove nil?)
       (map strip-slash)
       distinct
       vec))

(defn profile
  "The seatbelt profile: deny by default, read anywhere, write only under
  `write-roots`, network unless `:network?` is false — brainyard's
  `build-profile-string`, line for line; and, not brainyard's, files matching
  `:extra-write-regexes` (a benchmark's Claude Code keeps `~/.claude.json`
  beside the home directory, which is no root to open)."
  [{:keys [network?] :or {network? true} :as opts}]
  (str/join "\n"
            (concat ["(version 1)"
                     "(deny default)"
                     "(allow process-exec*)"
                     "(allow process-fork)"
                     "(allow signal (target self))"
                     "(allow file-read*)"
                     "(allow file-read-metadata)"
                     "(allow file-write* (subpath \"/dev\"))"
                     "(allow file-ioctl (subpath \"/dev\"))"
                     "(allow sysctl-read)"
                     "(allow mach-lookup)"
                     "(allow ipc-posix-sem*)"]
                    (when network? ["(allow network*)"])
                    (for [root (write-roots opts)]
                      (str "(allow file-write* (subpath " (sb-str root) "))"))
                    ;; files beside a root rather than under one — a CLI's
                    ;; own state file and its lock and temp siblings
                    (for [re (:extra-write-regexes opts)]
                      (str "(allow file-write* (regex " (sb-str re) "))")))))

(defn argv
  "The `sandbox-exec` command: a generated profile with `-p`, or a custom
  profile file with `-f` and brainyard's four `-D` parameters."
  [{:keys [profile-file home cwd project-dir tmpdir] :as opts} child-argv]
  (vec (concat [sandbox-exec]
               (if profile-file
                 (concat ["-f" profile-file]
                         (mapcat (fn [[k v]] (when v ["-D" (str k "=" v)]))
                                 [["HOME" home] ["CWD" cwd] ["PROJECT_DIR" project-dir] ["TMPDIR" tmpdir]]))
                 ["-p" (profile opts)])
               child-argv)))

(defn parse-allow-writes
  "brainyard's `parse-allow-writes`: a string or a vector, comma-separated, `~`
  expanded, relatives resolved against `cwd`."
  [v cwd]
  (let [home (System/getenv "HOME")]
    (->> (if (sequential? v) v [v])
         (mapcat #(str/split (str %) #","))
         (map str/trim)
         (remove str/blank?)
         (map (fn [p] (cond (= "~" p) home
                            (str/starts-with? p "~/") (str home (subs p 1))
                            (str/starts-with? p "/") p
                            :else (.getAbsolutePath (io/file cwd p)))))
         distinct
         vec)))

(defn self-argv
  "How to relaunch this CLI: BY_SANDBOX_SELF (whitespace-split), else the
  binary on PATH. jolt has no \"current executable\" API, so a script run needs
  BY_SANDBOX_SELF (brainyard resolves it from native-image)."
  [env-fn]
  (or (some-> (env-fn "BY_SANDBOX_SELF") str/trim not-empty (str/split #"\s+") vec)
      (let [r (fx/run-value!! (fx/process-task {:out :string :err :string} "which" "byj"))]
        (when (and (zero? (:exit r)) (not (str/blank? (:out r))))
          [(str/trim (:out r))]))))

(defn banner [{:keys [profile-file network?] :or {network? true} :as opts}]
  (str "🛡  brainyard-jolt sandboxed session (sandbox-exec)\n"
       (if profile-file
         (str "   Profile: " profile-file " (custom)\n")
         (str "   Writes:  " (str/join ", " (write-roots opts)) "\n"))
       "   Network: " (if network? "allowed (LLM calls work)" "DENIED") "\n"
       "   Press Ctrl-C to stop."))

(defn relaunch!
  "Run `child-argv` under sandbox-exec, inheriting this process's streams, and
  return its exit code. BY_SANDBOX_CHILD=1 in the child stops it sandboxing
  itself again."
  [opts child-argv]
  (let [pb (doto (java.lang.ProcessBuilder. ^java.util.List (argv opts child-argv))
             (.inheritIO))]
    (doto (.environment pb) (.put "BY_SANDBOX_CHILD" "1"))
    (let [p (.start pb)]
      (.waitFor p))))
