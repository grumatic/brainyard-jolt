(ns brainyard.version
  "Which version of brainyard-jolt this is (RFC-029) — brainyard's
  `build-info` / `app-version` / `app-version-long`.

  Versions are git tags, semver `vMAJOR.MINOR.PATCH`, from v0.1.0. A build
  stamps `resources/brainyard/build-version.edn` from git (`jolt version:byj`, run by
  `jolt build:byj`), and the binary embeds it. Code loaded from a checkout asks
  git there instead — the checkout moves on after a build and a stamp left in
  `resources/` would name the build, not the code — so `jolt run:byj` names its
  commit too. With neither, it is \"dev\"."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]))

(def stamp-resource "brainyard/build-version.edn")

(defn- git-in
  "What `git args…` prints in `dir`, trimmed, or nil when it fails."
  [dir & args]
  (try
    (let [r (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args)]
      (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))
    (catch Throwable _ nil)))

(defn source-root
  "The checkout this code was loaded from, or nil when it is not a directory on
  disk — a binary's resources are embedded, not files."
  []
  (try
    (when-let [url (io/resource "brainyard/loops/session.edn")]
      (when (= "file" (.getProtocol url))
        ;; <root>/resources/brainyard/loops/session.edn
        (some-> (io/file (.getPath url)) .getParentFile .getParentFile .getParentFile .getParentFile)))
    (catch Throwable _ nil)))

(defn from-git
  "The build info git gives for `dir`, the same keys the stamp has — when
  `dir` is the top of its own repository: a checkout vendored inside another
  one had git walk up and report the parent's version (byj-gly.232). Only
  version tags count (`v1.2.3`), not any tag the repository carries."
  [dir]
  (when-let [v (when (= (some-> (git-in dir "rev-parse" "--show-toplevel") io/file .getCanonicalPath)
                        (.getCanonicalPath (io/file (str dir))))
                 (git-in dir "describe" "--tags" "--match" "v[0-9]*" "--always" "--dirty"))]
    (let [sha (git-in dir "rev-parse" "HEAD")]
      {:version v
       :sha (or sha "unknown")
       :sha-short (if sha (subs sha 0 (min 7 (count sha))) "unknown")
       :branch (or (git-in dir "rev-parse" "--abbrev-ref" "HEAD") "unknown")
       :commit-time (or (git-in dir "show" "-s" "--format=%cI" "HEAD") "unknown")
       :dirty? (str/ends-with? v "-dirty")})))

(defn read-info
  "Build info: git in `root` when the code runs from a checkout, else the
  stamp, else dev. `stamp` is the stamp's text or nil; `root` a checkout or nil
  — injectable, so every branch is testable without a build."
  [stamp root]
  (or (some-> root from-git (assoc :from-git? true))
      (try (some-> stamp edn/read-string (#(when (map? %) %))) (catch Throwable _ nil))
      {:version "dev"}))

(def build-info
  "This build's provenance: `:version` `:sha` `:sha-short` `:branch`
  `:commit-time` `:dirty?`, and `:built-at` for a stamped build."
  (delay (read-info (some-> (io/resource stamp-resource) slurp) (source-root))))

(defn version
  "The version string: `v0.1.0`, `v0.1.0-3-gabc1234-dirty` between tags, or dev."
  []
  (:version @build-info "dev"))

(defn version-long
  "The version and where it came from: `v0.1.0 (76225d1, main, 2026-09-25T…)`,
  what `byj --version` prints. Plain `version` when there is no git."
  ([] (version-long @build-info))
  ([{:keys [version sha-short branch commit-time from-git?]}]
   (if (and sha-short (not= "unknown" sha-short))
     (str version " (" (str/join ", " (remove #(or (nil? %) (= "unknown" %)) [sha-short branch commit-time]))
          (when from-git? ", from the checkout") ")")
     (str version))))
