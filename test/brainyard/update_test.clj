(ns brainyard.update-test
  "Updating byj (RFC-029, byj-gly.181.3): which release is newer, how a
  private repo's answer is read, and how a binary and a checkout are brought to
  it — every call that leaves the process stubbed, except git and the file
  system, which run for real in a scratch directory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.config]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.update :as u]))

(defn- fresh-dir [name]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "byj-update-" name "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(deftest a-release-is-newer-only-by-its-tag
  (is (u/newer? "v0.3.0" "v0.2.0"))
  (is (u/newer? "v0.10.0" "v0.9.9") "numbers, not strings")
  (is (not (u/newer? "v0.2.0" "v0.2.0")))
  (is (not (u/newer? "v0.2.0" "v0.2.0-3-gabc1234-dirty")) "a build past its tag is not offered that tag")
  (is (u/newer? "v0.2.1" "v0.2.0-3-gabc1234") "but is the next one")
  (is (not (u/newer? "v0.1.9" "v0.2.0")))
  (is (u/newer? "v0.2.0" "dev") "a dev build is offered any release")
  (is (not (u/newer? "nightly" "v0.2.0")) "a tag that is not a version is never newer"))

(deftest a-token-comes-from-the-environment-then-gh
  (let [gh (fn [& _] {:exit 0 :out "gho_from_gh\n"})]
    (is (= "a" (u/token {"GH_TOKEN" "a" "GITHUB_TOKEN" "b"} gh)))
    (is (= "b" (u/token {"GH_TOKEN" " " "GITHUB_TOKEN" "b"} gh)))
    (is (= "gho_from_gh" (u/token {} gh)))
    (is (nil? (u/token {} (fn [& _] {:exit 1 :out ""}))))
    (is (nil? (u/token {} (fn [& _] (throw (ex-info "no gh" {}))))) "no gh on PATH")))

(defn- stub-get
  "A jolt.http-client `get` answering by path: `routes` maps a path to
  `[status body]`, and records the Authorization header it saw."
  [routes seen]
  (fn [url {:keys [headers as]}]
    (let [path (str/replace url #"^https://api\.github\.com" "")
          [status body] (get routes path [404 "{}"])]
      (swap! seen conj [path (get headers "Authorization")])
      {:status status :body (cond (and (= as :byte-array) (string? body)) (.getBytes body "UTF-8")
                                  (and (= as :stream) (string? body)) (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))
                                  :else body)})))

(def ^:private release-json
  (json/write-str {:tag_name "v0.3.0" :body "notes" :html_url "https://github.com/o/r/releases/tag/v0.3.0"
                   :assets [{:name "byj-0.3.0-macos-arm64" :id 11} {:name "SHA256SUMS" :id 12}]}))

(deftest the-latest-release-of-a-private-repo
  (let [seen (atom [])]
    (testing "found, with the token sent"
      (is (= {:tag "v0.3.0" :version "v0.3.0" :notes "notes" :url "https://github.com/o/r/releases/tag/v0.3.0"
              :assets {"byj-0.3.0-macos-arm64" 11 "SHA256SUMS" 12}
              :sizes {"byj-0.3.0-macos-arm64" nil "SHA256SUMS" nil}}
             (u/latest-release "o/r" "tok" (stub-get {"/repos/o/r/releases/latest" [200 release-json]} seen))))
      (is (= "Bearer tok" (second (first @seen))))))
  (testing "a 404 is told apart by asking for the repo"
    (is (= :no-release (:reason (u/latest-release "o/r" "tok" (stub-get {"/repos/o/r" [200 "{}"]} (atom []))))))
    (is (= :no-token (:reason (u/latest-release "o/r" nil (stub-get {} (atom []))))))
    (is (= :no-access (:reason (u/latest-release "o/r" "tok" (stub-get {} (atom []))))))
    (is (str/includes? (:error (u/latest-release "o/r" nil (stub-get {} (atom [])))) "gh auth token --user")))
  (testing "anything else is a failure, not an answer"
    (is (= :failed (:reason (u/latest-release "o/r" "tok" (stub-get {"/repos/o/r/releases/latest" [502 ""]} (atom []))))))
    (is (= :failed (:reason (u/latest-release "o/r" "tok" (fn [& _] (throw (ex-info "offline" {}))))))
        "a network failure")))

(deftest the-public-tap-is-read-with-no-token
  ;; byj-gly.181.10: each release is copied to the public tap, the default
  ;; source, so a binary finds the latest with no token at all
  (let [seen (atom [])
        r (u/latest-release "grumatic/homebrew-tap" nil
                            (stub-get {"/repos/grumatic/homebrew-tap/releases/latest" [200 release-json]} seen))]
    (is (= "v0.3.0" (:tag r)))
    (is (nil? (second (first @seen))) "no Authorization header sent")
    (is (= "grumatic/homebrew-tap" (:update-source brainyard.config/defaults)))))

(deftest a-binary-homebrew-installed-is-homebrews-to-update
  ;; byj update refuses one and says `brew upgrade FORMULA` (byj-gly.181.5)
  (is (= "byj" (u/homebrew-formula "/opt/homebrew/Cellar/byj/0.3.0/bin/byj")))
  (is (= "byj" (u/homebrew-formula "/usr/local/Cellar/byj/0.3.0_1/bin/byj")) "an Intel Mac's prefix, a revision")
  (is (= "byj" (u/homebrew-formula "/home/linuxbrew/.linuxbrew/Cellar/byj/0.3.0/bin/byj")))
  (is (nil? (u/homebrew-formula "/Users/me/.local/bin/byj")) "dev/install.sh's is its own to update")
  (is (nil? (u/homebrew-formula "/opt/homebrew/bin/byj")) "lsof names the file, never the link to it")
  (is (nil? (u/homebrew-formula nil))))

(deftest a-release-asset-is-named-and-checked
  (is (= "macos-arm64" (u/platform "Darwin\n" "arm64\n")))
  (is (= "linux-amd64" (u/platform "Linux" "x86_64")))
  (is (nil? (u/platform "Plan9" "mips")))
  (is (= "byj-0.3.0-linux-amd64" (u/asset-name "v0.3.0" "linux-amd64")))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (u/sha256-hex (.getBytes "abc" "UTF-8"))))
  (is (= {"a" (apply str (repeat 64 "0")) "b" (apply str (repeat 64 "1"))}
         (u/parse-sums (str (apply str (repeat 64 "0")) "  a\n" (apply str (repeat 64 "1")) " *b\n\n")))))

(defn- scratch-binary [dir content]
  (let [f (io/file dir "byj")]
    (spit f content)
    (.getPath f)))

(deftest a-binary-is-replaced-only-by-what-its-checksum-says
  (let [new "the new binary"
        sums (str (u/sha256-hex (.getBytes new "UTF-8")) "  byj-0.3.0-macos-arm64\n")
        rel (u/latest-release "o/r" "t" (stub-get {"/repos/o/r/releases/latest" [200 release-json]} (atom [])))
        ctx (fn [asset-body] {:repo "o/r" :tok "t" :sh u/shell
                              :get (stub-get {"/repos/o/r/releases/assets/11" [200 asset-body]
                                              "/repos/o/r/releases/assets/12" [200 sums]} (atom []))})]
    (testing "installed, the old one kept as .prev"
      (let [path (scratch-binary (fresh-dir "bin") "the old binary")
            r (u/update-binary! (ctx new) rel "macos-arm64" path)]
        (is (= "byj-0.3.0-macos-arm64" (:asset r)) (pr-str r))
        (is (= new (slurp path)))
        (is (= "the old binary" (slurp (str path ".prev"))))
        (is (not (.exists (io/file (str path ".new")))))
        (testing "and a rollback swaps them, a second one back again"
          (u/rollback! path)
          (is (= "the old binary" (slurp path)))
          (is (= new (slurp (str path ".prev"))))
          (u/rollback! path)
          (is (= new (slurp path))))))
    (testing "a checksum that does not match installs nothing"
      (let [path (scratch-binary (fresh-dir "bad") "the old binary")
            r (u/update-binary! (ctx "tampered in transit") rel "macos-arm64" path)]
        (is (str/includes? (str (:error r)) "does not match"))
        (is (= "the old binary" (slurp path)))
        (is (not (.exists (io/file (str path ".prev")))))
        (is (not (.exists (io/file (str path ".new")))) "and the download is not left behind")))
    (testing "a release with no build for this platform installs nothing"
      (let [path (scratch-binary (fresh-dir "plat") "the old binary")]
        (is (str/includes? (str (:error (u/update-binary! (ctx new) rel "linux-amd64" path))) "no byj-0.3.0-linux-amd64"))
        (is (= "the old binary" (slurp path)))))
    (testing "rolling back with nothing to roll back to"
      (is (str/includes? (str (:error (u/rollback! (scratch-binary (fresh-dir "none") "x")))) "no ")))))

(def ^:private other-release-json
  ;; a release whose assets include another program's beside byj's, as every
  ;; one since v0.3.0 has (until byj-ahf.9): byj update takes byj's alone
  (json/write-str {:tag_name "v0.3.0" :body "notes" :html_url "https://github.com/o/r/releases/tag/v0.3.0"
                   :assets [{:name "byj-0.3.0-macos-arm64" :id 11} {:name "SHA256SUMS" :id 12}
                            {:name "other-0.3.0-macos-arm64" :id 13}]}))

(defn- other-release []
  (u/latest-release "o/r" "t" (stub-get {"/repos/o/r/releases/latest" [200 other-release-json]} (atom []))))

(deftest an-install-has-an-origin
  ;; byj-gly.181.11: the installer that put a file there owns it, read from
  ;; the path alone with its symlinks resolved
  (let [root (fresh-dir "cellar")
        keg (io/file root "Cellar" "byj" "0.3.0" "bin")
        bin (io/file root "bin")]
    (.mkdirs keg)
    (.mkdirs bin)
    (spit (io/file keg "byj") "keg")
    (u/shell "ln" "-s" (.getPath (io/file keg "byj")) (.getPath (io/file bin "byj")))
    (testing "a Cellar keg, through the link brew makes to it"
      (is (= {:origin :homebrew :formula "byj" :path (.getCanonicalPath (io/file keg "byj"))}
             (u/install-origin (.getPath (io/file bin "byj"))))))
    (testing "any other file is a binary of its own"
      (let [own (io/file root "local" "byj")]
        (io/make-parents own)
        (spit own "own")
        (is (= {:origin :binary :path (.getCanonicalPath own)} (u/install-origin (.getPath own))))))
    (is (nil? (u/install-origin nil)))
    (testing "a command is found on PATH, or names itself by a path"
      (let [local (io/file root "local")
            probe (io/file local "probe")
            path-env (str (.getPath local) ":" (.getPath bin))]
        (spit probe "probe")
        (u/shell "chmod" "755" (.getPath probe))
        (is (= (.getAbsolutePath probe) (u/on-path "probe" path-env)))
        (is (= (.getAbsolutePath probe) (u/on-path (.getPath probe) path-env)) "a path names itself")
        (is (nil? (u/on-path "probe" (.getPath bin))) "nowhere on this PATH")))))

(deftest byj-update-plans-byj-alone
  ;; byj-ahf.7: a program built on byj updates itself; byj update's plan is
  ;; byj's one row
  (let [rel (other-release)
        actions #(mapv (juxt :component :action) %)]
    (testing "behind: replaced"
      (is (= [["byj" :replace]] (actions (u/plan {:release rel :current "v0.2.0" :exe "/me/.local/bin/byj"})))))
    (testing "at it: nothing to do"
      (let [rows (u/plan {:release rel :current "v0.3.0" :exe "/me/.local/bin/byj"})]
        (is (= [["byj" :keep]] (actions rows)))
        (is (u/nothing-to-do? rows))))
    (testing "a checkout is brought to the tag"
      (is (= [["byj" :checkout]] (actions (u/plan {:release rel :current "v0.2.0-3-gabc1234" :checkout "/me/src/byj"}))))
      (is (= [["byj" :keep]] (actions (u/plan {:release rel :current "v0.3.0-2-gabc1234" :checkout "/me/src/byj"})))
          "past the tag is not offered it"))
    (testing "the line says where, from what to what, and what is done"
      (let [[a] (u/plan-lines (u/plan {:release rel :current "v0.2.0" :exe "/me/.local/bin/byj"}))]
        (is (re-find #"^  byj +/me/.local/bin/byj +v0.2.0 → v0.3.0  replace" a) a)))))

(deftest byj-is-replaced-against-the-releases-sha256sums
  ;; the plan's rows, each asset checked against the release's SHA256SUMS
  ;; before any is put in place; another program's asset is never fetched
  (let [new-byj "the new byj"
        sums (str (u/sha256-hex (.getBytes new-byj "UTF-8")) "  byj-0.3.0-macos-arm64\n"
                  (u/sha256-hex (.getBytes "other" "UTF-8")) "  other-0.3.0-macos-arm64\n")
        rel (other-release)
        ctx (fn [byj-body seen] {:repo "o/r" :tok "t" :sh u/shell
                                 :get (stub-get {"/repos/o/r/releases/assets/11" [200 byj-body]
                                                 "/repos/o/r/releases/assets/12" [200 sums]
                                                 "/repos/o/r/releases/assets/13" [200 "other"]} seen)})
        rows (fn [byj] [{:component "byj" :path byj :action :replace}])]
    (testing "installed, the old one kept as .prev, SHA256SUMS fetched once, nothing else"
      (let [byj (scratch-binary (fresh-dir "pair") "the old byj")
            seen (atom [])
            r (u/update-pair! (ctx new-byj seen) rel "macos-arm64" (rows byj))]
        (is (= ["byj-0.3.0-macos-arm64"] (mapv :asset (:installed r))) (pr-str r))
        (is (= new-byj (slurp byj)))
        (is (= "the old byj" (slurp (str byj ".prev"))))
        (is (= 1 (count (filter #(= "/repos/o/r/releases/assets/12" (first %)) @seen))))
        (is (not-any? #(= "/repos/o/r/releases/assets/13" (first %)) @seen) "another program's asset is not fetched")
        (testing "and a rollback swaps it back"
          (is (nil? (:error (u/rollback! byj))))
          (is (= "the old byj" (slurp byj))))))
    (testing "a checksum failing installs nothing and leaves no download"
      (let [byj (scratch-binary (fresh-dir "split") "the old byj")
            r (u/update-pair! (ctx "tampered" (atom [])) rel "macos-arm64" (rows byj))]
        (is (str/includes? (str (:error r)) "does not match"))
        (is (= "the old byj" (slurp byj)))
        (is (not (.exists (io/file (str byj ".new")))))
        (is (not (.exists (io/file (str byj ".prev")))))))
    (testing "nothing to replace does nothing"
      (is (= {:installed []} (u/update-pair! (ctx new-byj (atom [])) rel "macos-arm64" [{:component "byj" :action :keep}]))))))

(deftest a-homebrew-install-is-brought-up-by-brew
  ;; byj-gly.181.12: brew owns a Cellar, so byj update runs brew for byj and
  ;; touches nothing itself; no rollback (decided 2026-10-14)
  (is (= "grumatic/tap/byj" (u/tap-formula "grumatic/homebrew-tap" "byj")))
  (is (= "byj" (u/tap-formula "owner/a-repo" "byj")) "a source that is not a tap")
  (let [origin {:origin :homebrew :formula "byj" :path "/opt/homebrew/Cellar/byj/0.2.0/bin/byj"}]
    (is (= {:upgrade ["grumatic/tap/byj"]} (u/brew-plan "grumatic/homebrew-tap" origin)))
    (is (= [["brew" "update" "--quiet"] ["brew" "upgrade" "grumatic/tap/byj"]]
           (u/brew-commands (u/brew-plan "grumatic/homebrew-tap" origin))))
    (testing "the commands run in order, and stop at the first that fails"
      (let [ran (atom [])
            sh (fn [& cmd] (swap! ran conj (vec cmd)) (if (= "upgrade" (second cmd)) {:exit 1 :err "Error: no bottle"} {:exit 0 :out "ok\n"}))
            r (u/update-homebrew! {:upgrade ["grumatic/tap/byj"]} sh)]
        (is (= [["brew" "update" "--quiet"] ["brew" "upgrade" "grumatic/tap/byj"]] @ran))
        (is (= [["brew" "update" "--quiet"]] (:ran r)))
        (is (str/includes? (str (:error r)) "brew upgrade grumatic/tap/byj failed: Error: no bottle")))
      (let [r (u/update-homebrew! {:upgrade ["grumatic/tap/byj"]} (fn [& _] {:exit 0 :out "ok\n"}))]
        (is (= 2 (count (:ran r))))
        (is (nil? (:error r)))))))

(deftest the-other-copies-on-path-are-said-and-left
  ;; byj-gly.181.13: two installs can coexist and only PATH decides which
  ;; runs; the others are named with how their owner updates or removes them
  (let [root (fresh-dir "copies")
        local (io/file root "local")
        keg (io/file root "Cellar" "byj" "0.3.1" "bin")
        brew-bin (io/file root "bin")
        exe! (fn [f] (io/make-parents f) (spit f "x") (u/shell "chmod" "755" (.getPath f)) (.getAbsolutePath f))
        local-byj (exe! (io/file local "byj"))
        keg-byj (exe! (io/file keg "byj"))
        _ (.mkdirs brew-bin)
        _ (u/shell "ln" "-s" keg-byj (.getPath (io/file brew-bin "byj")))
        brew-byj (.getAbsolutePath (io/file brew-bin "byj"))
        path-env (str (.getPath local) ":" (.getPath brew-bin))
        version-of (fn [_ p] (if (str/starts-with? p (.getPath local)) "v0.3.2" "v0.3.1"))]
    (testing "each real file once, in PATH order, with its origin"
      (is (= [local-byj brew-byj] (mapv :path (u/copies "byj" path-env))))
      (is (= [:binary :homebrew] (mapv :origin (u/copies "byj" path-env))))
      (is (= [brew-byj] (mapv :path (u/copies "byj" (str (.getPath brew-bin) ":" (.getPath keg))))) "the link and its keg are one")
      (is (empty? (u/copies "byj" (.getPath (fresh-dir "nothing"))))))
    (testing "the one that runs by name, then the shadowed, with how it is updated or removed; byj alone"
      (let [lines (u/install-lines {:running (.getCanonicalPath (io/file local-byj)) :path-env path-env :version-of version-of})]
        (is (= [(str "- byj: v0.3.2 at " local-byj)
                (str "- byj: v0.3.1 at " brew-byj " (Homebrew), shadowed by " local-byj " — `brew upgrade byj` updates it, `brew uninstall byj` removes it")]
               (mapv :text lines)))
        (is (= [false true] (mapv :note? lines)) "only the shadowed ones are notes after an update")))
    (testing "the shadowed copy is its own: byj update from it, or rm"
      (let [lines (u/install-lines {:running (.getCanonicalPath (io/file keg-byj)) :path-env (str (.getPath brew-bin) ":" (.getPath local)) :version-of version-of})]
        (is (str/includes? (:text (second lines)) (str "`" local-byj " update` updates it, `rm -f " local-byj "` removes it")))))
    (testing "the running byj that is not the one byj runs by name"
      (let [lines (u/install-lines {:running (.getCanonicalPath (io/file keg-byj)) :path-env path-env :version-of version-of})]
        (is (str/includes? (:text (first lines)) (str " — not this one, " (.getCanonicalPath (io/file keg-byj)) ", which runs only by its path")))
        (is (:note? (first lines)))))
    (testing "nothing on PATH, or from a checkout"
      (is (= [{:text "- byj: not on PATH" :note? false}]
             (u/install-lines {:running nil :path-env (.getPath (fresh-dir "bare")) :version-of version-of})))
      (is (= (str "- byj: v0.3.2 at " local-byj)
             (:text (first (u/install-lines {:running nil :path-env path-env :version-of version-of}))))
          "a checkout run names what runs by name and nothing of itself"))
    (testing "a copy that says no version"
      (is (str/includes? (:text (first (u/install-lines {:running nil :path-env path-env :version-of (constantly nil)}))) "version unknown at")))))

(deftest a-checkout-is-brought-to-the-tag-only-when-clean
  (let [origin (fresh-dir "origin")
        work (fresh-dir "work")
        git (fn [dir & args] (apply u/shell {:dir (.getPath dir)} "git" args))]
    (git origin "init" "-q" "-b" "main")
    (git origin "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "one")
    (git origin "-c" "user.name=t" "-c" "user.email=t@t" "tag" "-a" "v0.3.0" "-m" "v0.3.0")
    (git origin "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "two")
    (u/shell "git" "clone" "-q" (.getPath origin) (.getPath work))
    (testing "a tree with changes is refused"
      (spit (io/file work "scratch.txt") "unsaved")
      (is (str/includes? (str (:error (u/update-checkout! work "v0.3.0" u/shell))) "uncommitted changes"))
      (io/delete-file (io/file work "scratch.txt")))
    (testing "a clean one checks the tag out"
      (is (= "v0.3.0" (:checked-out (u/update-checkout! work "v0.3.0" u/shell))))
      (is (= "v0.3.0" (str/trim (:out (git work "describe" "--tags"))))))
    (testing "a tag that does not exist is an error, not a half-done checkout"
      (is (str/includes? (str (:error (u/update-checkout! work "v9.9.9" u/shell))) "checkout v9.9.9 failed")))))

(deftest this-process-knows-the-file-it-runs-from
  (let [p (u/executable-path (persist/own-pid) u/shell)]
    (is (some? p))
    (is (.isFile (io/file (str p))) p)))

(deftest under-an-emulator-the-program-is-its-argv0
  ;; provenance P-47: in a container under Rosetta, /proc/<pid>/exe is
  ;; /run/rosetta/rosetta, and byj update set out to replace it
  (is (u/emulator? "/run/rosetta/rosetta"))
  (is (u/emulator? "/usr/bin/qemu-x86_64"))
  (is (not (u/emulator? "/usr/local/bin/byj")))
  (let [seen (atom [])
        sh (fn [& cmd]
             (swap! seen conj cmd)
             (case (first cmd)
               "readlink" {:exit 0 :out "/run/rosetta/rosetta\n"}
               "sh" {:exit 0 :out "/usr/local/bin/byj\n"}
               {:exit 1}))]
    (is (= "/usr/local/bin/byj" (u/executable-path 42 sh)))
    (is (some #(and (= "sh" (first %)) (= "42" (last %))) @seen) "argv[0] read from that process's cmdline"))
  (is (= "/usr/local/bin/byj" (u/executable-path 42 (fn [& cmd] (if (= "readlink" (first cmd)) {:exit 0 :out "/usr/local/bin/byj\n"} {:exit 1}))))
      "no emulator: /proc/<pid>/exe as it is"))

(deftest a-public-release-is-downloaded-by-curl-and-a-private-one-never-puts-its-token-on-a-command-line
  ;; provenance P-47: jolt's client holds a body whole, and a 165 MB binary ran
  ;; a 2 GB heap out of memory
  (let [f (io/file (fresh-dir "curl") "byj.new")
        seen (atom [])
        sh (fn [& cmd] (swap! seen conj (vec cmd)) {:exit 0 :out "" :err ""})
        get (fn [& _] (throw (ex-info "the client was asked" {})))]
    (is (= {:file f} (u/download-asset-to! "o/r" 7 nil get f sh)))
    (let [curl (some #(when (= "curl" (first %)) %) @seen)]
      (is (= "https://api.github.com/repos/o/r/releases/assets/7" (last curl)))
      (is (some #{"Accept: application/octet-stream"} curl))
      (is (not-any? #(str/includes? (str %) "Authorization") curl)))
    (reset! seen [])
    (let [asked (atom nil)
          get (fn [url opts] (reset! asked [url (get-in opts [:headers "Authorization"])])
                {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes "bin"))})]
      (is (= {:file f} (u/download-asset-to! "o/r" 7 "tok" get f sh)))
      (is (= "Bearer tok" (second @asked)) "a token's download is the client's")
      (is (not-any? #(= "curl" (first %)) @seen) "curl not asked")
      (is (= "bin" (slurp f))))
    (testing "no curl: the client, as before"
      (let [get (fn [_ _] {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes "bin2"))})]
        (is (= {:file f} (u/download-asset-to! "o/r" 7 nil get f (fn [& _] {:exit 1}))))
        (is (= "bin2" (slurp f)))))))

(deftest a-file-is-hashed-whole-by-the-os-or-in-one-digest
  ;; jolt's MessageDigest hashes a whole array for update(bytes, off, len), and
  ;; slows with every update (jolt-lang/crypto#12): a file that ended mid-buffer failed its
  ;; checksum, and the 59 MB binary took over ten minutes to hash
  (let [bs (byte-array (map #(unchecked-byte (mod % 251)) (range 70000)))
        f (io/file (fresh-dir "hash") "blob")]
    (with-open [o (io/output-stream f)] (.write o bs))
    (is (= (u/sha256-hex bs) (u/sha256-file f u/shell)) "shasum or sha256sum")
    (is (= (u/sha256-hex bs) (u/sha256-file f (fn [& _] {:exit 127 :out ""})))
        "and with neither, one digest over the whole file")))

(deftest a-daily-check-says-a-newer-release-and-never-waits-for-it
  ;; :update-check? (RFC-029, byj-gly.181.7): the line comes from the LAST
  ;; check; a due check runs in the background and is kept for the next run
  (let [home (fresh-dir "check")
        f (u/check-file home)
        seen (atom [])
        get (stub-get {"/repos/o/r/releases/latest" [200 release-json]} seen)
        opts (fn [now] {:home (.getPath home) :now now :current "v0.2.0" :env {"GH_TOKEN" "tok"}
                        :sh (fn [& _] {:exit 1}) :get get})
        config {:update-source "o/r"}
        day u/check-interval-ms]
    (testing "the first run: nothing to say yet, and a check started"
      (is (nil? (u/startup-check! config (opts 1000))))
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (while (and (not (.exists f)) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50)))
      (is (= {:checked-at 1000 :repo "o/r" :latest "v0.3.0"} (u/read-check f))))
    (testing "the next run, within the day: the line, and GitHub not asked again"
      (reset! seen [])
      (is (= "byj v0.3.0 is out (this is v0.2.0) — `byj update` installs it"
             (u/startup-check! config (opts (+ 1000 (quot day 2))))))
      (Thread/sleep 200)
      (is (empty? @seen)))
    (testing "nothing said for a release that is not newer"
      (is (nil? (u/check-notice {:latest "v0.3.0"} "v0.3.0")))
      (is (nil? (u/check-notice {:latest "v0.3.0"} "v0.3.0-4-gabc1234-dirty")) "a build past the tag"))
    (testing "a day later a check is due again; a failed one waits a day as well"
      (is (u/check-due? {:checked-at 1000} (+ 1001 day)))
      (is (not (u/check-due? {:checked-at 1000 :error "no token"} (+ 1000 (quot day 2))))))
    (testing "a check of another repo is not trusted"
      (is (nil? (u/startup-check! {:update-source "other/repo"} (opts (+ 1000 (quot day 2)))))))))
