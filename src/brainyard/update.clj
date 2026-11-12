;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.update
  "Updating byj to the latest release (RFC-029, byj-gly.181.3).

  Where \"latest\" comes from was decided in byj-gly.181.2: the newest GitHub
  Release of `:update-source` (`owner/name`), read through the Releases API —
  since byj-gly.181.10 the public tap's copy of each release, read with no
  token; a token is still sent when there is one, and a private source needs
  it. A binary downloads its platform's asset and
  `SHA256SUMS` the same way, checks the one against the other, keeps itself as
  `<name>.prev` and puts the new one in its place with one rename; `rollback!`
  swaps them back. byj alone is updated: a program built on byj — the share
  sidecar among them — is its own to update (RFC-070, byj-ahf.7). The
  install has an origin read from the running binary's path — a Homebrew
  Cellar, a plain binary, a checkout — and the installer that put it there
  owns it, so a Cellar's is brought up by brew (`update-homebrew!`,
  byj-gly.181.12), a binary by `update-pair!` against the release's
  SHA256SUMS. A checkout refuses a dirty tree,
  fetches the tag and checks it
  out. Nothing here installs unless asked: `byj update` is the only caller
  that does. With `:update-check?` on, a run also looks once a day for a newer
  release (`startup-check!`) and says so — never more (byj-gly.181.7).

  brainyard has no update command — its install script is run again — so all of
  this is the port's own (divergence `byj-updates-itself`).

  Mechanism only: every call that leaves the process — the HTTP GET, the
  shell — is a parameter, so each branch is testable without a network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]))

;; --- versions ----------------------------------------------------------------------

(defn parse-version
  "`v0.2.0`, or a build past a tag (`v0.2.0-3-gabc1234`, `-dirty`), as
  `{:nums [0 2 0] :past n}`; nil for `dev` or anything not from a tag."
  [s]
  (when-let [[_ a b c past] (re-matches #"v(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?(?:-dirty)?" (str s))]
    {:nums [(parse-long a) (parse-long b) (parse-long c)] :past (if past (parse-long past) 0)}))

(defn newer?
  "Is release `latest` newer than the running `current`? A build past a tag is
  at least that tag, so it is never offered the tag it came from; `dev`, or a
  version that does not read, is offered any release."
  [latest current]
  (let [l (parse-version latest)
        c (parse-version current)]
    (boolean (and l (or (nil? c) (pos? (compare (:nums l) (:nums c))))))))

;; --- the token -----------------------------------------------------------------------

(defn token
  "A GitHub token, needed for a private repo and used for any (a higher rate
  limit): `GH_TOKEN`, then `GITHUB_TOKEN`, then what
  `gh auth token` prints. `env` is a map, `sh` runs a command and returns
  `{:exit :out}`. Never printed or logged, and sent only to api.github.com."
  [env sh]
  (or (not-empty (str/trim (str (get env "GH_TOKEN"))))
      (not-empty (str/trim (str (get env "GITHUB_TOKEN"))))
      (try (let [r (sh "gh" "auth" "token")]
             (when (zero? (:exit r)) (not-empty (str/trim (str (:out r))))))
           (catch Throwable _ nil))))

;; --- the latest release -----------------------------------------------------------

(def ^:private api "https://api.github.com")

(defn- api-get
  "GET `path` from the Releases API with `get` (jolt.http-client's shape),
  the token if there is one, and `accept`."
  [get path tok & {:keys [accept as]}]
  (get (str api path)
       (cond-> {:headers (cond-> {"Accept" (or accept "application/vnd.github+json")
                                  "User-Agent" "byj"}
                           tok (assoc "Authorization" (str "Bearer " tok)))
                :socket-timeout 30000 :conn-timeout 10000 :throw-exceptions false}
         as (assoc :as as))))

(defn latest-release
  "The newest release of `repo` (`owner/name`) as `{:tag :version :notes :url
  :assets {name id}}`, or `{:error :reason}` saying why not — :no-token,
  :no-access, :no-release or :failed. A private repo answers 404 both when it
  cannot be seen and when it has no release yet, so a 404 asks for the repo
  itself to tell the two apart."
  [repo tok get]
  (let [r (try (api-get get (str "/repos/" repo "/releases/latest") tok)
               (catch Throwable e {:status -1 :body (ex-message e)}))]
    (cond
      (= 200 (:status r))
      (let [{:keys [tag_name body html_url assets]} (json/read-str (:body r))]
        {:tag tag_name :version tag_name :notes body :url html_url
         :assets (into {} (map (juxt :name :id)) assets)
         :sizes (into {} (map (juxt :name :size)) assets)})

      (= 404 (:status r))
      (let [seen (try (:status (api-get get (str "/repos/" repo) tok)) (catch Throwable _ -1))]
        (cond
          (= 200 seen) {:error (str repo " has no release yet") :reason :no-release}
          (nil? tok) {:error (str "no GitHub token to read " repo " (a private repo) — set GH_TOKEN, "
                                  "or GH_TOKEN=$(gh auth token --user <account that can see it>)")
                      :reason :no-token}
          :else {:error (str "this token cannot see " repo " — use one for an account that can: "
                             "GH_TOKEN=$(gh auth token --user <account>)")
                 :reason :no-access}))

      :else {:error (str "GitHub answered " (:status r) " for " repo "'s latest release")
             :reason :failed})))

;; --- a binary --------------------------------------------------------------------------

(defn platform
  "This machine as a release asset names it — `macos-arm64`, `linux-amd64` —
  from `uname -s` and `uname -m`, as dev/release-stage.sh does."
  [os arch]
  (let [os (case (str/trim (str os)) "Darwin" "macos" "Linux" "linux" nil)
        arch (case (str/trim (str arch)) ("x86_64" "amd64") "amd64" ("arm64" "aarch64") "arm64" nil)]
    (when (and os arch) (str os "-" arch))))

(defn asset-name
  "The release asset for `version` (`v0.2.0`) on `platform`: byj's, or
  another `bin`'s, named as dev/release-stage.sh names them."
  ([version platform] (asset-name "byj" version platform))
  ([bin version platform]
   (str bin "-" (str/replace (str version) #"^v" "") "-" platform)))

(defn parse-sums
  "`SHA256SUMS` text as `{name sha256}`."
  [text]
  (into {} (keep #(when-let [[_ sha name] (re-matches #"([0-9a-f]{64})\s+\*?(\S+)" (str/trim %))] [name sha]))
        (str/split-lines (str text))))

(defn- hex [^bytes d] (apply str (map #(format "%02x" (bit-and % 0xff)) d)))

(defn sha256-hex [^bytes bs]
  (hex (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)))

(defn sha256-file
  "The SHA-256 of file `f`: what `shasum -a 256` or `sha256sum` says, as
  dev/release-stage.sh wrote it, else one digest over the whole file.
  MessageDigest here is jolt-crypto's, pinned at 44da69b through
  ring-chez-adapter, and it cannot be fed a stream: `update(bytes, off, len)`
  hashes the whole array (jolt-lang/crypto#12, still so at its HEAD), and each
  `update` cost more than the last — 1 MB in 64 KB reads took 277 ms, 4 MB
  2.9 s, the 59 MB v0.2.0 binary over ten minutes (fixed at jolt-crypto's HEAD,
  not in the pin; byj-gly.181.3). One `update` of all of it is quick."
  [f sh]
  (or (some (fn [cmd]
              (try (let [r (apply sh (concat cmd [(str f)]))]
                     (when (zero? (:exit r)) (second (re-find #"^([0-9a-f]{64})" (str (:out r))))))
                   (catch Throwable _ nil)))
            [["shasum" "-a" "256"] ["sha256sum"]])
      (let [out (java.io.ByteArrayOutputStream.)]
        (with-open [in (io/input-stream f)] (io/copy in out :buffer-size 65536))
        (sha256-hex (.toByteArray out)))))

(defn download-asset
  "The bytes of asset `id` of `repo`, through the API — a private repo's only
  way, since its browser URL needs a session. For small assets (SHA256SUMS);
  a binary is streamed to a file (`download-asset-to!`)."
  [repo id tok get]
  (let [r (api-get get (str "/repos/" repo "/releases/assets/" id) tok
                   :accept "application/octet-stream" :as :byte-array)]
    (if (= 200 (:status r))
      {:bytes (:body r)}
      {:error (str "downloading asset " id " of " repo ": GitHub answered " (:status r))})))

(def ^:private copy-buffer
  "What a download is copied in. io/copy's 1 KB default took the 59 MB v0.2.0
  binary 78 s where `gh` took 33 s; 64 KB took 63 s (byj-gly.181.3)."
  65536)

(defn- curl-to!
  "Asset `id` of a public `repo` into file `f` by curl, `{:file}`, or nil when
  there is no curl to ask. jolt's HTTP client holds a body whole however it is
  read, and a 165 MB binary ran a Share machine's byj (Rosetta, a 2 GB heap)
  out of memory (provenance P-47); curl writes it as it comes."
  [repo id f sh]
  (when (and sh (try (zero? (:exit (sh "sh" "-c" "command -v curl"))) (catch Throwable _ false)))
    (let [r (sh "curl" "-fsSL" "--retry" "2" "-H" "Accept: application/octet-stream" "-H" "User-Agent: byj"
                "-o" (str f) (str api "/repos/" repo "/releases/assets/" id))]
      (if (zero? (:exit r))
        {:file f}
        {:error (str "downloading asset " id " of " repo ": " (str/trim (str (:err r))))}))))

(defn download-asset-to!
  "Stream asset `id` of `repo` into file `f` rather than into memory — a binary
  is tens of megabytes. `{:file}` or `{:error}`. A public repo's (no token) is
  fetched by curl where there is one (`curl-to!`); a token is never put on a
  command line, so a private repo's is fetched here."
  [repo id tok get f & [sh]]
  (or (when-not tok (curl-to! repo id f sh))
      (let [r (api-get get (str "/repos/" repo "/releases/assets/" id) tok
                       :accept "application/octet-stream" :as :stream)]
        (if (= 200 (:status r))
          (do (with-open [in (:body r) out (io/output-stream f)] (io/copy in out :buffer-size copy-buffer))
              {:file f})
          {:error (str "downloading asset " id " of " repo ": GitHub answered " (:status r))}))))

(defn emulator?
  "Whether `path` is an emulator running the program rather than the program:
  Rosetta's or qemu's, as /proc/<pid>/exe names them for an amd64 binary run
  on arm64 Linux (a Share machine under Docker on Apple silicon)."
  [path]
  (let [base (.getName (io/file (str path)))]
    (boolean (or (= "rosetta" base) (str/starts-with? base "qemu-")))))

(defn- emulated-path
  "The program an emulator runs as process `pid`: its argv[0], from
  /proc/<pid>/cmdline, found on the PATH when it names no directory."
  [pid sh]
  (let [r (sh "sh" "-c" (str "a=$(tr '\\000' '\\n' < /proc/$0/cmdline | head -n 1); "
                             "case \"$a\" in */*) printf '%s\\n' \"$a\" ;; ?*) command -v \"$a\" ;; esac")
              (str pid))]
    (when (zero? (:exit r)) (not-empty (str/trim (str (:out r)))))))

(defn executable-path
  "The file this process was started from, or nil. jolt has no ProcessHandle,
  so the OS is asked: Linux's /proc/<pid>/exe, macOS's `lsof` (its first `txt`
  file is the executable). Under an emulator /proc/<pid>/exe is the emulator —
  `byj update` once set out to replace /run/rosetta/rosetta — so the program
  is its argv[0] instead (`emulated-path`)."
  [pid sh]
  (try
    (or (let [r (sh "readlink" (str "/proc/" pid "/exe"))
              exe (when (zero? (:exit r)) (not-empty (str/trim (str (:out r)))))]
          (if (and exe (emulator? exe)) (emulated-path pid sh) exe))
        (let [r (sh "lsof" "-a" "-p" (str pid) "-d" "txt" "-Fn")]
          (when (zero? (:exit r))
            (some #(when (str/starts-with? % "n/") (subs % 1)) (str/split-lines (str (:out r)))))))
    (catch Throwable _ nil)))

(defn homebrew-formula
  "The Homebrew formula whose keg holds the binary at `path` — `byj` for
  `/opt/homebrew/Cellar/byj/0.3.0/bin/byj` — else nil. Homebrew owns what is
  in its Cellar: a file replaced there is one `brew upgrade` and `brew
  uninstall` no longer account for, so such a binary is updated by brew, not
  by itself (byj-gly.181.5)."
  [path]
  (second (re-find #"/Cellar/([^/]+)/[^/]+/bin/[^/]+$" (str path))))

(defn install-binary!
  "Put `new` — a file already written beside the binary at `path` — in its
  place: made executable, the current binary kept as `.prev`, then one rename,
  atomic on one filesystem, so there is never a moment with no binary at `path`
  and the running process keeps the file it has open. With nothing at `path`
  yet there is no `.prev`.
  Returns `{:installed :previous}`."
  [path new sh]
  (let [f (io/file path)
        new (io/file new)
        prev (io/file (str path ".prev"))
        had? (.isFile f)]
    (sh "chmod" "755" (.getPath new))
    (when had?
      (io/copy f prev)
      (sh "chmod" "755" (.getPath prev)))
    (when-not (.renameTo new f)
      (throw (ex-info (str "could not replace " path) {:path path})))
    {:installed (.getPath f) :previous (when had? (.getPath prev))}))

(defn rollback!
  "Swap the binary at `path` with its `.prev`, as `install-binary!` left it: the
  one before becomes current, and the current one `.prev`, so a second rollback
  undoes the first. `{:error}` when there is no `.prev`."
  [path]
  (let [f (io/file path)
        prev (io/file (str path ".prev"))
        tmp (io/file (str path ".swap"))]
    (if-not (.isFile prev)
      {:error (str "no " (.getPath prev) " to roll back to")}
      (do (when-not (and (.renameTo f tmp) (.renameTo prev f) (.renameTo tmp prev))
            (throw (ex-info (str "could not swap " path " with " (.getPath prev)) {:path path})))
          {:installed (.getPath f) :previous (.getPath prev)}))))

(defn- fetch-sums
  "`release`'s `SHA256SUMS` as `{:sums {name sha256}}`, or `{:error}`."
  [{:keys [repo tok get]} release]
  (if-let [id (get-in release [:assets "SHA256SUMS"])]
    (let [r (download-asset repo id tok get)]
      (if (:error r)
        r
        {:sums (parse-sums (String. ^bytes (:bytes r) "UTF-8"))}))
    {:error (str (:tag release) " has no SHA256SUMS to check its assets against")}))

(defn- fetch-asset!
  "Download `release`'s asset `name` into file `new` and check it against
  `sums`: `{:file :sha256}`, or `{:error}` with `new` deleted — when the
  release has no such asset, SHA256SUMS does not list it, the download fails
  or the checksum does not match."
  [{:keys [repo tok get sh]} release sums name new]
  (let [id (get-in release [:assets name])
        want (clojure.core/get sums name)]
    (cond
      (nil? id) {:error (str (:tag release) " has no " name " — no build for this platform in that release")}
      (nil? want) {:error (str "SHA256SUMS does not list " name)}
      :else
      (let [{:keys [error]} (try (download-asset-to! repo id tok get new sh)
                                 (catch Throwable e {:error (str "downloading " name ": " (ex-message e))}))]
        (cond
          error (do (io/delete-file new true) {:error error})
          (not= want (sha256-file new sh))
          (do (io/delete-file new true)
              {:error (str name "'s checksum does not match SHA256SUMS — nothing was installed")})
          :else {:file new :sha256 want})))))

(defn update-binary!
  "Download `release`'s asset for `platform` and its `SHA256SUMS`, check the one
  against the other, and install it at `path`. `{:error}` before anything on
  disk changes when the asset is missing, a download fails or the checksum does
  not match."
  [{:keys [sh] :as ctx} release platform path]
  (let [name (asset-name (:version release) platform)
        sums (fetch-sums ctx release)]
    (if (:error sums)
      sums
      (let [r (fetch-asset! ctx release (:sums sums) name (io/file (str path ".new")))]
        (if (:error r)
          r
          (assoc (install-binary! path (:file r) sh) :asset name :sha256 (:sha256 r)))))))

;; --- where byj is installed (byj-gly.181.11) ----------------------------------------

(defn real-path
  "`path` with its symlinks resolved — `/opt/homebrew/bin/byj` is a link into
  the Cellar — or as given when it cannot be; nil for nil."
  [path]
  (when path
    (try (.getCanonicalPath (io/file (str path))) (catch Throwable _ (str path)))))

(defn install-origin
  "Where the file at `path` was installed from, read from its path alone,
  symlinks resolved — the installer that put it there owns it:
  `{:origin :homebrew :formula \"byj\" :path}` for a Cellar keg, which is
  Homebrew's to replace (`homebrew-formula`); `{:origin :binary :path}` for
  any other file — dev/install.sh's, or `byj update`'s own. nil for no path."
  [path]
  (when-let [real (real-path path)]
    (if-let [f (homebrew-formula real)]
      {:origin :homebrew :formula f :path real}
      {:origin :binary :path real})))

(defn on-path
  "The executable `cmd` names: itself when it has a `/`, else the first on
  `path-env` (`:`-separated), as share/resolve-binary finds the sidecar. nil
  when there is none."
  [cmd path-env]
  (let [cmd (str cmd)]
    (if (str/includes? cmd "/")
      (let [f (io/file cmd)] (when (and (.isFile f) (.canExecute f)) (.getAbsolutePath f)))
      (some (fn [d]
              (let [f (io/file d cmd)]
                (when (and (not (str/blank? d)) (.isFile f) (.canExecute f)) (.getAbsolutePath f))))
            (str/split (str path-env) #":")))))

(defn plan
  "What `byj update` would do to bring this install to `release`: its one
  row, `{:component :path :current :to :action}`, the action `:keep`
  (already at it, or past it), `:replace` (the binary swapped, the old kept
  as `.prev`) or `:checkout` (the tree brought to the tag). `current` is
  byj's version, `exe` its file, `checkout` the tree it runs from. A
  program built on byj is not in it: it updates itself (byj-ahf.7)."
  [{:keys [release current checkout exe]}]
  (let [to (:version release)
        row (fn [path action] {:component "byj" :path (str path) :current current :to to :action action})]
    (if checkout
      [(row checkout (if (newer? to current) :checkout :keep))]
      [(row exe (if (newer? to current) :replace :keep))])))

(defn nothing-to-do?
  "Does the plan leave the install as it is — every row kept?"
  [rows]
  (every? #(= :keep (:action %)) rows))

(defn plan-lines
  "The plan as the lines `byj update` prints before it asks: the component,
  where it is, from what to what, and what will be done."
  [rows]
  (let [w (apply max 0 (map #(count (:path %)) rows))
        pad (fn [s n] (str s (apply str (repeat (- n (count s)) " "))))]
    (mapv (fn [{:keys [component path current to action]}]
            (str "  " (pad component 10) (pad path (+ w 2))
                 (or current "missing") " → " to "  "
                 (case action
                   :keep "up to date"
                   :replace "replace (sha256 checked, the old one kept as .prev)"
                   :checkout "git checkout the tag")))
          rows)))

(defn update-pair!
  "Do the plan's `:replace` rows for `platform`: each asset downloaded
  beside its path as `.new` and checked against the release's `SHA256SUMS`,
  and only when every one checks out are they put in place, one rename each —
  so a failed download or checksum leaves nothing changed. `{:installed
  [{:component :asset :sha256 :installed :previous}]}`, or `{:error}`."
  [{:keys [sh] :as ctx} release platform rows]
  (let [todo (filter #(= :replace (:action %)) rows)]
    (if (empty? todo)
      {:installed []}
      (let [sums (fetch-sums ctx release)]
        (if (:error sums)
          sums
          (let [staged (reduce (fn [acc row]
                                 (let [name (asset-name (:component row) (:version release) platform)
                                       new (io/file (str (:path row) ".new"))
                                       r (fetch-asset! ctx release (:sums sums) name new)]
                                   (if (:error r)
                                     (reduced (assoc acc :error (:error r)))
                                     (update acc :staged conj (assoc row :asset name :new new :sha256 (:sha256 r))))))
                               {:staged []} todo)]
            (if (:error staged)
              (do (doseq [s (:staged staged)] (io/delete-file (:new s) true))
                  {:error (:error staged)})
              {:installed (mapv (fn [s]
                                  (merge (select-keys s [:component :asset :sha256])
                                         (install-binary! (:path s) (:new s) sh)))
                                (:staged staged))})))))))

;; --- the other copies on PATH (byj-gly.181.13) ----------------------------------------

(defn copies
  "Every executable `cmd` on `path-env`, in PATH order, each real file once —
  `/opt/homebrew/bin/byj` and the keg it links to are one — with its origin:
  `{:path :real :origin :formula}`."
  [cmd path-env]
  (->> (str/split (str path-env) #":")
       (remove str/blank?)
       (keep (fn [d]
               (let [f (io/file d (str cmd))]
                 (when (and (.isFile f) (.canExecute f)) (.getAbsolutePath f)))))
       (map (fn [p] (assoc (install-origin p) :path p :real (real-path p))))
       (reduce (fn [acc c] (if (some #(= (:real %) (:real c)) acc) acc (conj acc c))) [])))

(defn- way-with
  "How a copy that is not the one that runs is updated or removed — by its
  owner, never here."
  [{:keys [origin formula path]}]
  (if (= :homebrew origin)
    (str "`brew upgrade " formula "` updates it, `brew uninstall " formula "` removes it")
    (str "`" path " update` updates it, `rm -f " path "` removes it")))

(defn install-lines
  "What is installed, for `byj doctor` and after `byj update` (byj-gly.181.13):
  for byj, the copy that runs by name — the first on
  `path-env` — with its version and origin, then every other copy, shadowed,
  with how it is updated or removed; and the running byj, `running` (its real
  path; nil from a checkout), said when it is not the one that runs by name.
  Two installs can coexist, one in ~/.local/bin and one in a Cellar, and only
  PATH decides which runs — the other went stale with nothing said
  (2026-10-14). `version-of` is a fn of the command and a path to what it says
  its version is, or nil. Each line is `{:text :note?}`, `:note?` on the ones
  worth saying after an update. Nothing is updated or removed: the copy's
  owner does."
  [{:keys [running path-env version-of]}]
  (vec (mapcat
        (fn [cmd]
          (let [[first-c & others] (copies cmd path-env)
                desc (fn [c] (str (or (version-of cmd (:path c)) "version unknown") " at " (:path c)
                                  (when (= :homebrew (:origin c)) " (Homebrew)")))
                not-this? (and first-c (= "byj" cmd) running (not= running (:real first-c)))]
            (concat
             [{:text (if first-c
                       (str "- " cmd ": " (desc first-c)
                            (when not-this? (str " — not this one, " running ", which runs only by its path")))
                       (str "- " cmd ": not on PATH"))
               :note? (boolean not-this?)}]
             (for [c others]
               {:text (str "- " cmd ": " (desc c) ", shadowed by " (:path first-c) " — " (way-with c))
                :note? true}))))
        ["byj"])))

;; --- a Homebrew install (byj-gly.181.12) --------------------------------------------

(defn tap-formula
  "The formula `name` as `brew` is asked for it, qualified by the tap
  `:update-source` names: `grumatic/homebrew-tap` and `byj` give
  `grumatic/tap/byj`; a source that is not a tap gives `byj` alone."
  [source name]
  (if-let [[_ owner tap] (re-matches #"([^/]+)/homebrew-([^/]+)" (str source))]
    (str owner "/" tap "/" name)
    name))

(defn brew-plan
  "What `brew` is asked on a Homebrew install of byj: `:upgrade` byj's
  formula, qualified by the tap `source` names."
  [source origin]
  {:upgrade [(tap-formula source (:formula origin))]})

(defn brew-commands
  "The commands a Homebrew install is brought up with: `brew update`, so the
  tap's new formulae are seen, then `brew upgrade` and `brew install` as
  `brew-plan` says."
  [{:keys [upgrade install]}]
  (cond-> [["brew" "update" "--quiet"]]
    (seq upgrade) (conj (into ["brew" "upgrade"] upgrade))
    (seq install) (conj (into ["brew" "install"] install))))

(defn update-homebrew!
  "Run `brew-commands` for `plan` with `sh`, stopping at the first that fails:
  `{:ran [cmd ...] :out [text ...]}` or `{:error}`. There is no rollback: brew
  keeps no previous keg to switch back to (decided 2026-10-14)."
  [plan sh]
  (reduce (fn [acc cmd]
            (let [r (try (apply sh cmd) (catch Throwable e {:exit -1 :err (ex-message e)}))]
              (if (zero? (:exit r))
                (-> acc (update :ran conj cmd) (update :out conj (str (:out r))))
                (reduced {:error (str (str/join " " cmd) " failed"
                                      (when (seq (str (:err r))) (str ": " (str/trim (str (:err r))))))
                          :ran (:ran acc) :out (:out acc)}))))
          {:ran [] :out []} (brew-commands plan)))

;; --- a checkout ------------------------------------------------------------------------

(defn update-checkout!
  "Bring the checkout at `dir` to `tag`: refused when its tree has changes, else
  the tags fetched from origin and `tag` checked out. The binary is not rebuilt
  here — `jolt build:byj` does that, and says so in the result."
  [dir tag sh]
  (let [git #(apply sh {:dir (str dir)} "git" %&)
        status (git "status" "--porcelain")]
    (cond
      (not (zero? (:exit status))) {:error (str dir " is not a git checkout")}
      (seq (str/trim (str (:out status)))) {:error (str dir " has uncommitted changes — commit or stash them first")}
      :else
      (let [f (git "fetch" "--tags" "origin")]
        (if-not (zero? (:exit f))
          {:error (str "git fetch --tags origin failed: " (str/trim (str (:err f))))}
          (let [c (git "checkout" "--quiet" tag)]
            (if (zero? (:exit c))
              {:checked-out tag :dir (str dir)}
              {:error (str "git checkout " tag " failed: " (str/trim (str (:err c))))})))))))

;; --- the daily check (byj-gly.181.7) --------------------------------------------------

(def check-interval-ms
  "How often a run with `:update-check?` on asks GitHub: once a day."
  (* 24 60 60 1000))

(def check-timeout-ms
  "How long the background check is given before it is dropped."
  20000)

(defn check-file
  "Where the last check is kept: `~/.brainyard/update-check.edn`."
  [home]
  (io/file home ".brainyard" "update-check.edn"))

(defn read-check [f] (try (edn/read-string (slurp f)) (catch Throwable _ nil)))

(defn check-due?
  "Is a check due — none yet, or the last over `check-interval-ms` ago? A
  failed one counts: it is tried again tomorrow, not every run."
  [cache now]
  (not (and (number? (:checked-at cache)) (< (- now (:checked-at cache)) check-interval-ms))))

(defn check-notice
  "The one line a run says when the last check found a newer release, else nil."
  [cache current]
  (when (and (:latest cache) (newer? (:latest cache) current))
    (str "byj " (:latest cache) " is out (this is " current ") — `byj update` installs it")))

(defn refresh-check!
  "Ask for `repo`'s latest release and keep the answer in `f`:
  `{:checked-at :repo :latest?}`, or `:error` saying why there is none."
  [f repo tok get now]
  (let [rel (latest-release repo tok get)
        m (cond-> {:checked-at now :repo repo}
            (:version rel) (assoc :latest (:version rel))
            (:error rel) (assoc :error (:error rel)))]
    (io/make-parents f)
    (spit f (pr-str m))
    m))

(defn startup-check!
  "`:update-check?` at startup (RFC-029, byj-gly.181.7): the line to say at
  the end of this run, from the LAST check — so nothing waits on the network
  — and, when a check is due, one started in the background, its answer kept
  for the next run (`refresh-check!`). A check of another repo than
  `:update-source` is not trusted. `opts` carry what leaves the process:
  `:home :now :current :env :sh :get`."
  [config {:keys [home now current env sh get]}]
  (let [f (check-file home)
        repo (:update-source config)
        cache (let [c (read-check f)] (when (= repo (:repo c)) c))]
    (when (check-due? cache now)
      (fx/run-detached (fx/timeout (fx/task-of #(refresh-check! f repo (token env sh) get now)) check-timeout-ms)
                       "update-check"))
    (check-notice cache current)))

(defn shell
  "The `sh` the functions above take, over `fx/sh`: an optional options map
  first (`:dir`), then the command."
  [& args]
  (let [[opts cmd] (if (map? (first args)) [(first args) (rest args)] [{} args])]
    (apply fx/sh (merge {:out :string :err :string} opts) cmd)))
