(ns brainyard.release-test
  "The release process (RFC-029, byj-gly.181.4): dev/release-stage.sh stages
  only a tagged, clean build whose binary says the version its stamp does —
  byj's alone, since programs built on byj release themselves (RFC-070) — and
  dev/release-verify.sh holds a release to its own SHA256SUMS. Both run here
  against a scratch root: a stamp, and a stand-in binary that says a version."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-release-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- scratch-root
  "A root with a stamp saying `stamped` and a binary saying `says`, and
  another program's binary beside it in target/, which is not byj's to stage."
  [name {:keys [stamped says dirty?] :or {dirty? false}}]
  (let [root (fresh-dir name)
        bin (io/file root "target" "byj")
        other (io/file root "target" "byj-other")]
    (io/make-parents (io/file root "resources" "brainyard" "build-version.edn"))
    (spit (io/file root "resources" "brainyard" "build-version.edn")
          (pr-str {:version stamped :sha "abc123def" :sha-short "abc123d" :branch "main"
                   :commit-time "2026-09-29T10:00:00+09:00" :dirty? dirty? :built-at "2026-09-29T01:00:00Z"}))
    (io/make-parents bin)
    (spit bin (str "#!/bin/sh\necho 'byj " says " (abc123d, main, 2026-09-29T10:00:00+09:00)'\n"))
    (spit other "#!/bin/sh\necho 'byj-other 9.9.9'\n")
    (fx/sh {:out :string} "chmod" "+x" (.getPath bin) (.getPath other))
    root))

(defn- run [script root & args]
  (apply fx/sh {:out :string :err :string
                :env (assoc (into {} (System/getenv))
                            "BYJ_ROOT" (.getPath root)
                            "BYJ_RELEASE_DIR" (.getPath (io/file root "release")))}
         script args))

(def ^:private stage "dev/release-stage.sh")
(def ^:private verify "dev/release-verify.sh")

(deftest only-a-tagged-clean-build-is-staged
  (doseq [[stamped why] [["dev" "was it built outside a git checkout"]
                         ["v0.2.0-dirty" "uncommitted changes"]
                         ["v0.2.0-3-gabc123d" "commits past the tag"]
                         ["0.2.0" "is not a vX.Y.Z tag"]]]
    (testing stamped
      (let [r (run stage (scratch-root "refuse" {:stamped stamped :says stamped}))]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) why) (:err r)))))
  (testing "a stamp that says the tree was dirty"
    (is (str/includes? (:err (run stage (scratch-root "dirty" {:stamped "v0.2.0" :says "v0.2.0" :dirty? true})))
                       "was dirty")))
  (testing "a binary that does not say the stamp's version — built, then re-stamped"
    (let [r (run stage (scratch-root "stale" {:stamped "v0.2.0" :says "v0.1.0-79-gf18dfed"}))]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "rebuild")))))

(deftest a-release-is-staged-with-its-checksums-and-provenance
  (let [root (scratch-root "stage" {:stamped "v0.2.0" :says "v0.2.0"})
        rel (io/file root "release")
        asset (first (filter #(str/starts-with? % "byj-0.2.0-") (seq (.list (doto rel .mkdirs)))))]
    (is (nil? asset) "nothing before")
    ;; a leftover of another version, another program's asset left by a
    ;; release they once shared, and another platform's asset of this one —
    ;; one no runner is, so it is never this machine's own (byj-ahf.29)
    (spit (io/file rel "byj-0.1.0-macos-arm64") "old")
    (spit (io/file rel "byj-0.2.0-plan9-amd64") "built on another machine")
    (spit (io/file rel "byj-other-0.2.0-linux-amd64") "another program's")
    (let [r (run stage root)
          names (set (.list rel))
          mine (first (filter #(and (str/starts-with? % "byj-0.2.0-") (not (str/ends-with? % "plan9-amd64"))) names))]
      (is (zero? (:exit r)) (:err r))
      (is (re-matches #"byj-0\.2\.0-(macos|linux)-(arm64|amd64)" (str mine)))
      (is (not-any? names ["byj-0.1.0-macos-arm64" "byj-other-0.2.0-linux-amd64"]) "another version's files, and another program's, are gone")
      (is (not-any? #(str/starts-with? % "byj-other") names) "another program's binary in target/ is not staged")
      (is (contains? names "byj-0.2.0-plan9-amd64") "this version's other platform stays")
      (is (= #{mine "byj-0.2.0-plan9-amd64"}
             (set (map #(second (str/split % #"\s+")) (str/split-lines (slurp (io/file rel "SHA256SUMS"))))))
          "SHA256SUMS covers every asset of the version, byj's alone")
      (is (str/includes? (slurp (io/file rel "BUILD-INFO.txt")) "commit: abc123def"))
      (testing "and what is staged verifies"
        (let [v (run verify root "--dir" (.getPath rel))]
          (is (zero? (:exit v)) (:err v)))))))

(deftest a-release-is-held-to-its-own-checksums
  (let [root (scratch-root "verify" {:stamped "v0.2.0" :says "v0.2.0"})
        rel (io/file root "release")
        _ (run stage root)
        asset (first (filter #(str/starts-with? % "byj-") (.list rel)))]
    (testing "an asset changed after staging"
      (spit (io/file rel asset) "tampered")
      (let [v (run verify root "--dir" (.getPath rel))]
        (is (= 1 (:exit v)))
        (is (str/includes? (:err v) "does not match"))))
    (testing "an asset SHA256SUMS names but that is not there — brainyard shipped one"
      (.delete (io/file rel asset))
      (is (str/includes? (:err (run verify root "--dir" (.getPath rel))) "missing: byj-")))
    (testing "an asset SHA256SUMS does not name"
      (let [root (scratch-root "extra" {:stamped "v0.2.0" :says "v0.2.0"})
            rel (io/file root "release")]
        (run stage root)
        (spit (io/file rel "byj-0.2.0-plan9-mips") "x")
        (is (str/includes? (:err (run verify root "--dir" (.getPath rel))) "is not in SHA256SUMS"))))))

;; --- the Homebrew formulae (byj-gly.181.5) -------------------------------------------------------

(def ^:private formula "dev/brew-formula.sh")

(defn- write-formulae
  "dev/brew-formula.sh for `tag` over a stand-in release: `assets` its `id name`
  pairs, `listed` the names SHA256SUMS lists, into a tap checkout that holds
  another program's formula. `{:exit :err :byj :other}`, each formula's text
  after."
  [tag assets listed]
  (let [dir (fresh-dir "brew")
        sums (io/file dir "sums")
        lines (io/file dir "assets")
        tap (io/file dir "tap")]
    (spit lines (str/join "\n" (for [[id n] assets] (str id " " n))))
    (spit sums (str/join "\n" (for [n listed] (str (apply str (repeat 64 (if (str/includes? n "other") "b" "a"))) "  " n))))
    (io/make-parents (io/file tap "Formula" "byj-other.rb"))
    (spit (io/file tap "Formula" "byj-other.rb") "another program's formula")
    (let [r (fx/sh {:out :string :err :string
                    :env (assoc (into {} (System/getenv))
                                "BYJ_TAP_REPO" "grumatic/homebrew-tap"
                                "BYJ_RELEASE_ASSETS" (.getPath lines)
                                "BYJ_RELEASE_SUMS" (.getPath sums))}
                   formula tag (.getPath tap))
          read #(let [f (io/file tap "Formula" %)] (when (.isFile f) (slurp f)))]
      (assoc r :byj (read "byj.rb") :other (read "byj-other.rb")))))

(deftest a-formula-fetches-each-platforms-asset-from-the-public-tap
  ;; byj-gly.181.10: the assets were fetched through the API with a token, and
  ;; only someone who could read the private repo could install
  (let [r (write-formulae "v0.3.0"
                          [[11 "byj-0.3.0-macos-arm64"] [21 "byj-other-0.3.0-macos-arm64"] [30 "SHA256SUMS"]]
                          ["byj-0.3.0-macos-arm64" "byj-other-0.3.0-macos-arm64"])]
    (is (zero? (:exit r)) (:err r))
    (testing "the asset by its browser URL on the public tap, with no token"
      (is (str/includes? (:byj r) "url \"https://github.com/grumatic/homebrew-tap/releases/download/v0.3.0/byj-0.3.0-macos-arm64\""))
      (is (not (str/includes? (:byj r) "HOMEBREW_GITHUB_API_TOKEN")))
      (is (= 1 (count (re-seq #"releases/download/" (:byj r)))) "byj's asset alone, nothing of the other program's")
      (is (str/includes? (:byj r) "homepage \"https://github.com/grumatic/homebrew-tap\"") "a page anyone can open")
      (is (str/includes? (:byj r) (str "sha256 \"" (apply str (repeat 64 "a")) "\"")))
      (is (str/includes? (:byj r) "version \"0.3.0\""))
      (is (str/includes? (:byj r) "bin.install Dir[\"*\"].first => \"byj\"")))
    (testing "byj loads Homebrew's OpenSSL on macOS (provenance P-36)"
      (is (str/includes? (:byj r) "  on_macos do\n    depends_on \"openssl@3\"")))
    (testing "a platform the release has no asset for is left out, not guessed"
      (is (str/includes? (:byj r) "on_arm do"))
      (is (not (str/includes? (:byj r) "on_intel")))
      (is (not (str/includes? (:byj r) "on_linux"))))
    (testing "another program's formula in the tap is its own, left as it was"
      (is (= "another program's formula" (:other r)))))
  (testing "every platform the release workflow builds"
    (let [names (for [p ["macos-arm64" "macos-amd64" "linux-amd64"]] (str "byj-0.3.0-" p))
          r (write-formulae "v0.3.0" (map-indexed vector names) names)]
      (is (zero? (:exit r)) (:err r))
      (is (= 3 (count (re-seq #"releases/download/" (:byj r)))))
      (is (str/includes? (:byj r) "on_linux do\n    on_intel do")))))

(deftest a-formula-is-refused-for-a-release-it-cannot-check
  (testing "an asset SHA256SUMS does not list"
    (let [r (write-formulae "v0.3.0" [[11 "byj-0.3.0-macos-arm64"]] [])]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "does not list byj-0.3.0-macos-arm64") (:err r))))
  (testing "a release with no byj"
    (let [r (write-formulae "v0.3.0" [[21 "byj-other-0.3.0-macos-arm64"]] ["byj-other-0.3.0-macos-arm64"])]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "has no byj-0.3.0-* asset") (:err r))))
  (testing "not a vX.Y.Z tag"
    (is (str/includes? (:err (write-formulae "latest" [] [])) "usage"))))

;; --- the public copy on the tap (byj-gly.181.10) -------------------------------------------------

(def ^:private fake-gh
  "A stand-in gh over directories: a repo's release `tag` is `<root>/<owner>/<name>/<tag>/`,
  its notes and title files beside the assets' directory. Every call is logged."
  (str "#!/usr/bin/env bash\n"
       "echo \"$*\" >> \"$FAKE_GH/log\"\n"
       "repo=''; dir=''; notes=''; title=''; args=(); i=0; a=(\"$@\")\n"
       "while (( i < ${#a[@]} )); do case \"${a[i]}\" in\n"
       "  --repo) repo=\"${a[i+1]}\"; i=$((i+2));; --dir) dir=\"${a[i+1]}\"; i=$((i+2));;\n"
       "  --notes-file) notes=\"${a[i+1]}\"; i=$((i+2));; --title) title=\"${a[i+1]}\"; i=$((i+2));;\n"
       "  --json|--jq) i=$((i+2));; --clobber|--latest|--latest=*) i=$((i+1));; *) args+=(\"${a[i]}\"); i=$((i+1));; esac; done\n"
       "verb=\"${args[1]}\"; tag=\"${args[2]}\"; r=\"$FAKE_GH/$repo/$tag\"\n"
       "case \"$verb\" in\n"
       "  download) [[ -d \"$r/assets\" ]] || exit 1; cp -f \"$r\"/assets/* \"$dir\"/;;\n"
       "  view) [[ -d \"$r/assets\" ]] || exit 1\n"
       "        case \"$*\" in *'--json name'*) cat \"$r/title\";; *'--json body'*) cat \"$r/notes\";;\n"
       "                       *'--json assets'*) ls \"$r/assets\";; esac;;\n"
       "  create) mkdir -p \"$r/assets\"; cp -f \"$notes\" \"$r/notes\"; printf '%s' \"$title\" > \"$r/title\"\n"
       "          for f in \"${args[@]:3}\"; do cp -f \"$f\" \"$r/assets/\"; done;;\n"
       "  upload) for f in \"${args[@]:3}\"; do cp -f \"$f\" \"$r/assets/\"; done;;\n"
       "esac\n"))

(defn- mirror-world
  "A stand-in source release of v0.3.0 — two assets, their SHA256SUMS, notes
  and a title — and a gh that serves it. `{:root :env}`."
  [name & {:keys [corrupt?]}]
  (let [root (fresh-dir name)
        bin (io/file root "bin")
        src (io/file root "grumatic" "byj-source" "v0.3.0")
        assets (io/file src "assets")]
    (.mkdirs bin) (.mkdirs assets)
    (spit (io/file bin "gh") fake-gh)
    (fx/sh {:out :string} "chmod" "+x" (.getPath (io/file bin "gh")))
    (spit (io/file assets "byj-0.3.0-macos-arm64") "byj binary")
    (spit (io/file assets "byj-0.3.0-linux-amd64") "byj for linux")
    (spit (io/file assets "SHA256SUMS")
          (:out (fx/sh {:out :string :dir (.getPath assets)} "shasum" "-a" "256" "byj-0.3.0-macos-arm64" "byj-0.3.0-linux-amd64")))
    (when corrupt? (spit (io/file assets "byj-0.3.0-macos-arm64") "tampered"))
    (spit (io/file src "notes") "## v0.3.0\n- things")
    (spit (io/file src "title") "byj v0.3.0")
    {:root root
     :env (assoc (into {} (System/getenv))
                 "PATH" (str (.getPath bin) ":" (System/getenv "PATH"))
                 "FAKE_GH" (.getPath root)
                 "BYJ_RELEASE_REPO" "grumatic/byj-source"
                 "BYJ_TAP_REPO" "grumatic/homebrew-tap")}))

(deftest a-release-is-copied-to-the-public-tap-whole-and-checked
  (let [{:keys [root env]} (mirror-world "mirror")
        mirror #(fx/sh {:out :string :err :string :env env} "dev/release-mirror.sh" "v0.3.0")
        tap (io/file root "grumatic" "homebrew-tap" "v0.3.0")
        r (mirror)]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (testing "every asset, the checksums, the notes and the title"
      (is (= #{"byj-0.3.0-macos-arm64" "byj-0.3.0-linux-amd64" "SHA256SUMS"}
             (set (.list (io/file tap "assets")))))
      (is (= "byj binary" (slurp (io/file tap "assets" "byj-0.3.0-macos-arm64"))))
      (is (= "## v0.3.0\n- things" (str/trim (slurp (io/file tap "notes")))))
      (is (= "byj v0.3.0" (slurp (io/file tap "title")))))
    (testing "and the copy is checked against its own SHA256SUMS"
      (is (str/includes? (:out r) "mirrored grumatic/byj-source's v0.3.0 to grumatic/homebrew-tap")))
    (testing "and made the tap's latest release, which is what byj update reads"
      (is (re-find #"(?m)^release create v0\.3\.0 .*--repo grumatic/homebrew-tap.*--latest$" (slurp (io/file root "log")))))
    (testing "run again, nothing is uploaded twice"
      (let [again (mirror)]
        (is (zero? (:exit again)))
        (is (str/includes? (:out again) "has v0.3.0 with every asset already"))))
    (testing "a tap release missing an asset is completed, not replaced"
      (.delete (io/file tap "assets" "byj-0.3.0-linux-amd64"))
      (let [r (mirror)]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "uploading the 1 asset(s) it lacks"))
        (is (.isFile (io/file tap "assets" "byj-0.3.0-linux-amd64"))))))
  (testing "assets that do not match their SHA256SUMS are not copied"
    (let [{:keys [root env]} (mirror-world "mirror-bad" :corrupt? true)
          r (fx/sh {:out :string :err :string :env env} "dev/release-mirror.sh" "v0.3.0")]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "do not match its SHA256SUMS — not mirrored"))
      (is (not (.exists (io/file root "grumatic" "homebrew-tap"))))))
  (testing "not a vX.Y.Z tag"
    (is (str/includes? (:err (fx/sh {:out :string :err :string} "dev/release-mirror.sh" "latest")) "usage"))))

(deftest openssl-is-found-on-any-mac-and-the-unsafe-stub-is-never-tried
  ;; jolt-crypto's :darwin candidates name only Apple silicon's Homebrew, then
  ;; /usr/lib/libcrypto.dylib, which macOS aborts the process for loading: an
  ;; Intel Mac's build died there (provenance P-36). The project's own spec
  ;; wins the :darwin key.
  (let [natives (into {} (map (juxt :name identity)) (:jolt/native (edn/read-string (slurp "deps.edn"))))]
    (doseq [lib ["crypto" "ssl"]
            :let [darwin (get-in natives [lib :darwin])]]
      (testing lib
        (is (some #(str/starts-with? % "/opt/homebrew/opt/openssl@3/") darwin) "Apple silicon's Homebrew")
        (is (some #(str/starts-with? % "/usr/local/opt/openssl@3/") darwin) "an Intel Mac's Homebrew")
        (is (not-any? #(str/starts-with? % "/usr/lib/") darwin) "never the system stub")
        (is (nil? (get-in natives [lib :linux])) "jolt-crypto's Linux candidates are kept")))))

(defn- src-requires
  "Each namespace under src/ to the namespaces its ns form requires."
  []
  (into {}
        (for [f (file-seq (io/file "src"))
              :when (str/ends-with? (.getName f) ".clj")
              :let [[_ nm & clauses] (read-string (slurp f))]]
          [nm (set (for [c clauses :when (and (seq? c) (= :require (first c)))
                         s (rest c)]
                     (if (sequential? s) (first s) s)))])))

(deftest every-lazily-resolved-namespace-is-in-the-binary
  ;; `jolt build -m brainyard.main` bakes only what brainyard.main reaches by
  ;; :require; a namespace reached only by requiring-resolve is left out unless
  ;; :jolt/build :include names it, and the binary's lookup then dies with
  ;; "Could not locate … on the source roots" — while jolt run and this suite,
  ;; which load from source, never see it (provenance P-43).
  ;; What it cannot see: only a quoted target, (requiring-resolve 'ns/fn), is
  ;; found — a computed one, (requiring-resolve sym), or a runtime
  ;; (require 'ns) gets past it — and src-requires takes a :require entry as a
  ;; symbol or a vector, so a prefix list, (:require (brainyard a b)), is read
  ;; wrong. src has none of the three; one added needs this scan taught first.
  (let [reqs (src-requires)
        reached (loop [seen #{} todo ['brainyard.main]]
                  (if-let [[n & more] (seq todo)]
                    (if (seen n) (recur seen more) (recur (conj seen n) (into (vec more) (reqs n))))
                    seen))
        included (set (map symbol (get-in (edn/read-string (slurp "deps.edn")) [:jolt/build :include])))
        lazy (set (for [f (file-seq (io/file "src"))
                        :when (str/ends-with? (.getName f) ".clj")
                        [_ n] (re-seq #"\(requiring-resolve '([a-zA-Z0-9.\-]+)/" (slurp f))]
                    (symbol n)))]
    (is (seq lazy) "the scan finds the lookups")
    (doseq [n lazy]
      (is (or (reached n) (included n))
          (str n " is reached only by requiring-resolve: add it to deps.edn :jolt/build :include")))
    (doseq [n included]
      (is (contains? reqs n) (str n " in :jolt/build :include has no file under src")))))
