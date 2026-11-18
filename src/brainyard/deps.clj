;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.deps
  "A project's dependencies, as data, checked, and written safely (byj-9dv,
  RFC-075): what other projects it uses or is affected by, and what a
  session playing one role hands to a session playing another.

  Two files under the project's `.brainyard/`:

  - `deps.edn`, the spec, committed: `{:schema-version 1 :projects {id
    project} :roles {id role} :handoffs [handoff …]}`;
  - `deps-local.edn`, this machine's: `{:bindings {role [session-id …]}
    :handed [{:from :to :session :turn :at} …]}`.

  `read-spec` and `read-local` are lenient — what does not fit the schema
  is dropped, a key at a time, and said as a problem; `report` resolves the
  projects, reads the sessions bound to each role and checks every handoff;
  `write!` is strict — it refuses a problem, a cycle or a dangling role —
  and snapshots the old file under `.brainyard/deps-snapshots/` before it
  writes the new one in a stable order and reads it back.

  Mechanism only (RFC-001): nothing here decides what a session does with a
  handoff; it reports where things stand."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [brainyard.changes :as changes]
            [brainyard.effect :as fx]
            [brainyard.init :as init]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.projects :as projects]
            [brainyard.worktree :as worktree]))

(def schema-version
  "The `:schema-version` a spec this build reads and writes has."
  1)

(def max-changes
  "At most this many upstream commits are listed for a project."
  50)

(def snapshot-retention
  "How many snapshots of each file are kept, the oldest deleted first."
  20)

(def ^:private version-timeout-ms 5000)

;; --- files ---------------------------------------------------------------------------

(defn spec-file [dir] (io/file (str dir) ".brainyard" "deps.edn"))

(defn local-file [dir] (io/file (str dir) ".brainyard" "deps-local.edn"))

(defn- file-of [dir which] (if (= :local which) (local-file dir) (spec-file dir)))

;; --- the schema ----------------------------------------------------------------------
;;
;; A schema is :string, :bool, :int, :kw (a keyword; a string is taken as
;; one, which is how JSON gives it), :inst (an instant; an ISO-8601 string
;; is taken as one), [:enum k …], [:vec s], [:map-of s] (keyword keys), or
;; [:map {:required #{k …}} [k s] …].

(def ^:private provide-schema
  [:map {:required #{:id :kind}}
   [:id :kw] [:kind [:enum :binary :files]]
   [:command :string] [:version :string] [:paths [:vec :string]]])

(def ^:private project-schema
  [:map {}
   [:name :string] [:git :string] [:path :string] [:kind [:enum :upstream :reference]]
   [:provides [:vec provide-schema]] [:affects [:vec :string]] [:seen :string]])

(def ^:private role-schema
  [:map {} [:title :string] [:does :string] [:worktree :bool]])

(def ^:private give-schema
  [:map {:required #{:kind}}
   [:kind [:enum :file :bead :branch :note]] [:paths [:vec :string]] [:id :string] [:parent :string]])

(def ^:private handoff-schema
  [:map {:required #{:from :to}} [:from :kw] [:to :kw] [:gives [:vec give-schema]]])

(def spec-schema
  [:map {}
   [:schema-version :int]
   [:projects [:map-of project-schema]]
   [:roles [:map-of role-schema]]
   [:handoffs [:vec handoff-schema]]])

(def local-schema
  [:map {}
   [:bindings [:map-of [:vec :string]]]
   [:handed [:vec [:map {:required #{:from :to}}
                   [:from :kw] [:to :kw] [:session :string] [:turn :int] [:at :inst]]]]])

(defn- id-name
  "A keyword as JSON and the problems name it: without its colon."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- where [path] (str/join "." (map id-name path)))

(defn- got [v]
  (let [s (pr-str v)] (if (> (count s) 40) (str (subs s 0 39) "…") s)))

(defn- ->kw [x]
  (cond (keyword? x) x
        (and (string? x) (not (str/blank? x))) (keyword (str/replace x #"^:" ""))))

(defn- ->inst [x]
  (cond (inst? x) x
        (string? x) (try (java.util.Date. (.toEpochMilli (java.time.Instant/parse x))) (catch Throwable _ nil))))

(defn- clean
  "`v` held to `schema`: what fits, the rest dropped, each drop conj'd onto
  `probs` (an atom) as `{:key :message}`. `::drop` when `v` itself does not
  fit."
  [schema v path probs]
  (let [bad (fn [what] (swap! probs conj {:key (where path) :message (str "should be " what "; got " (got v))}) ::drop)]
    (if (keyword? schema)
      (case schema
        :string (if (string? v) v (bad "a string"))
        :bool (if (boolean? v) v (bad "true or false"))
        :int (if (integer? v) v (bad "an integer"))
        :kw (or (->kw v) (bad "an id"))
        :inst (or (->inst v) (bad "an instant")))
      (let [[kind opts & fields] schema]
        (case kind
          :enum (let [k (->kw v) allowed (set (rest schema))]
                  (if (allowed k) k (bad (str "one of " (str/join ", " (map id-name (sort allowed)))))))
          :vec (if (sequential? v)
                 (into [] (comp (map-indexed (fn [i x] (clean opts x (conj path i) probs))) (remove #{::drop})) v)
                 (bad "a list"))
          :map-of (if (map? v)
                    (into {} (keep (fn [[k x]]
                                     (if-let [k (->kw k)]
                                       (let [c (clean opts x (conj path k) probs)] (when-not (= ::drop c) [k c]))
                                       (do (swap! probs conj {:key (where path) :message (str "an id should name each entry; got " (got k))}) nil))))
                          v)
                    (bad "a map"))
          :map (if-not (map? v)
                 (bad "a map")
                 (let [known (into {} (map (fn [[k s]] [k s])) fields)
                       m (into {} (keep (fn [[k x]]
                                          (let [k (if (string? k) (keyword k) k)]
                                            (if-let [s (get known k)]
                                              (let [c (clean s x (conj path k) probs)] (when-not (= ::drop c) [k c]))
                                              (do (swap! probs conj {:key (where (conj path k)) :message "unknown key"}) nil)))))
                             v)
                       missing (remove #(contains? m %) (sort (:required opts)))]
                   (if (seq missing)
                     (do (swap! probs conj {:key (where path) :message (str "needs " (str/join ", " (map id-name missing)) "; left out")})
                         ::drop)
                     m))))))))

(defn- drop-where
  "`xs` without the items `bad?` names a reason for, each said in `probs`
  under `path` and its index."
  [xs path probs bad?]
  (vec (keep-indexed (fn [i x]
                       (if-let [why (bad? x)]
                         (do (swap! probs conj {:key (where (conj path i)) :message (str why "; left out")}) nil)
                         x))
                     xs)))

(defn- tidy-spec
  "What the schema cannot say of a spec: a project is not named self, a
  provide's id is unique within its project, a binary names its command and
  files their paths, a file give its paths and a bead give its bead."
  [spec probs]
  (when-let [v (:schema-version spec)]
    (when-not (= schema-version v)
      (swap! probs conj {:key "schema-version" :message (str "is " v "; this build reads " schema-version)})))
  (-> spec
      (update :projects
              (fn [ps]
                (when ps
                  (into {} (keep (fn [[id p]]
                                   (if (= :self id)
                                     (do (swap! probs conj {:key (where [:projects id]) :message "self names this project; left out"}) nil)
                                     [id (cond-> p
                                           (:provides p)
                                           (update :provides
                                                   (fn [pv]
                                                     (let [seen (atom #{})]
                                                       (drop-where pv [:projects id :provides] probs
                                                                   (fn [{pid :id k :kind :as x}]
                                                                     (cond (@seen pid) (str "a second provide " (id-name pid))
                                                                           (and (= :binary k) (str/blank? (:command x))) "a binary needs its :command"
                                                                           (and (= :files k) (empty? (:paths x))) "files need their :paths"
                                                                           :else (do (swap! seen conj pid) nil))))))))]))
                        ps)))))
      (update :handoffs
              (fn [hs]
                (when hs
                  (vec (map-indexed
                        (fn [i h]
                          (cond-> h
                            (:gives h)
                            (update :gives #(drop-where % [:handoffs i :gives] probs
                                                        (fn [{k :kind :as g}]
                                                          (cond (and (= :file k) (empty? (:paths g))) "a file give needs its :paths"
                                                                (and (= :bead k) (str/blank? (or (:id g) (:parent g)))) "a bead give needs its :id or :parent"))))))
                        hs)))))
      (->> (remove (comp nil? val)) (into {}))))

(defn clean-spec
  "`m` as a spec: `{:value :problems}`, a problem `{:key :message}`."
  [m]
  (let [probs (atom [])
        v (clean spec-schema m [] probs)
        v (if (= ::drop v) {} (tidy-spec v probs))]
    {:value v :problems @probs}))

(defn clean-local
  "`m` as the local file: `{:value :problems}`."
  [m]
  (let [probs (atom [])
        v (clean local-schema m [] probs)]
    {:value (if (= ::drop v) {} v) :problems @probs}))

(defn- read-file
  "File `f` read, cleaned by `cleaner`: `{:value :problems :exists}`, a
  problem with its `:file`. An unreadable file is `{}` and one problem."
  [f cleaner]
  (let [p (.getPath ^java.io.File f)]
    (if-not (.isFile ^java.io.File f)
      {:value {} :problems [] :exists false}
      (let [m (try {:ok (edn/read-string (slurp f))} (catch Throwable e {:error (ex-message e)}))]
        (cond
          (:error m) {:value {} :exists true :problems [{:file p :key nil :message (str "unreadable EDN: " (:error m))}]}
          (nil? (:ok m)) {:value {} :exists true :problems []}
          :else (let [{:keys [value problems]} (cleaner (:ok m))]
                  {:value value :exists true :problems (mapv #(assoc % :file p) problems)}))))))

(defn read-spec [dir] (read-file (spec-file dir) clean-spec))

(defn read-local [dir] (read-file (local-file dir) clean-local))

;; --- git and processes ---------------------------------------------------------------

(defn- git [dir & args]
  (try (let [r (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args)]
         (if (zero? (:exit r)) {:ok (str (:out r))} {:error (str/trim (str (:err r) (:out r)))}))
       (catch Throwable e {:error (ex-message e)})))

(defn- git-line [dir & args] (some-> (:ok (apply git dir args)) str/trim not-empty))

(defn- run
  "`cmd` in `dir`, given up after a few seconds: `{:exit :out :err}`, or
  `{:error}` when it could not run or did not finish."
  [dir & cmd]
  (let [r (fx/run!! (apply fx/process-task {:dir (str dir) :out :string :err :string :in ""} cmd) version-timeout-ms)]
    (cond (:ok r) (let [{:keys [exit out err]} (:ok r)] {:exit exit :out (str out) :err (str err)})
          (:timeout r) {:error "no answer in time"}
          :else {:error (str (or (some-> (:err r) ex-message) "could not run"))})))

(def known-forges
  "Hosts that are a forge's own name, never a person's SSH alias for one."
  #{"github.com" "gitlab.com" "bitbucket.org" "codeberg.org" "git.sr.ht" "ssh.dev.azure.com"})

(defn- ssh-hostname*
  "The host ssh connects to for `host`, as `ssh -G` resolves it through the
  person's ssh config — `github.com` for a `Host github-work` alias — or
  nil when ssh cannot say."
  [host]
  (let [r (run "." "ssh" "-G" host)]
    (when (= 0 (:exit r))
      (some #(second (re-matches #"(?i)hostname\s+(\S+)" (str/trim %))) (str/split-lines (str (:out r)))))))

(def ^:dynamic *ssh-hostname*
  "`ssh-hostname*`, asked once a host in a process (byj-sih)."
  (memoize ssh-hostname*))

(defn- split-url
  "A git URL's `{:host :path :ssh?}`: `:ssh?` for scp-style and ssh://, whose
  host may be an alias in the person's ssh config; no `:host` for a local
  path."
  [url]
  (let [u (str/trim (str url))]
    (if-let [[_ scheme more] (re-matches #"([A-Za-z][A-Za-z0-9+.-]*)://(.*)" u)]
      (let [[hostport path] (str/split (str/replace more #"^[^@/]+@" "") #"/" 2)]
        {:host (str/replace hostport #":\d+$" "") :path (str path) :ssh? (str/includes? (str/lower-case scheme) "ssh")})
      (let [more (str/replace u #"^[^@/]+@" "")
            [host path] (str/split more #":" 2)]
        (if (and path (not (str/includes? host "/")))
          {:host host :path path :ssh? true}
          {:path u})))))

(defn git-key
  "A git URL as the repository it names, for comparing two spellings of one:
  `git@github.com:o/r.git`, `https://github.com/o/r` and
  `ssh://git@github.com/o/r.git` are all `github.com/o/r`. An SSH host is
  the one the person's ssh config resolves it to (`*ssh-hostname*`), so
  `git@github-work:o/r.git`, through `Host github-work` / `HostName
  github.com`, is `github.com/o/r` too (byj-sih)."
  [url]
  (when-not (str/blank? (str url))
    (let [{:keys [host path ssh?]} (split-url url)
          host (if (and ssh? host) (or (*ssh-hostname* host) host) host)]
      (-> (str (when host (str host "/")) path)
          (str/replace #"/+$" "") (str/replace #"\.git$" "") str/lower-case))))

(defn same-repo?
  "Do git URLs `a` and `b` name one repository: the same `git-key`, or —
  where ssh could not resolve an alias — the same owner and name on two
  hosts, one of them no known forge (byj-sih)."
  [a b]
  (let [ka (git-key a) kb (git-key b)]
    (boolean
     (and ka kb
          (or (= ka kb)
              (let [[ha pa] (str/split ka #"/" 2) [hb pb] (str/split kb #"/" 2)]
                (and pa (= pa pb) (str/includes? pa "/")
                     (not (and (known-forges ha) (known-forges hb))))))))))

(defn- canonical [f] (try (.getCanonicalPath (io/file (str f))) (catch Throwable _ (str f))))

;; --- projects ------------------------------------------------------------------------

(defn resolve-project
  "Where project `p` of the spec in `dir` is on this machine: `{:dir
  :resolved-by}` — by its `:path`, relative to `dir`, when that is a
  directory; else by the registry (`registry`, `projects/list-projects`'
  rows), a project whose origin is `p`'s `:git` — its origin as its git
  config says it now, the one its record holds only where that cannot be
  read (byj-339); else `{}`."
  [dir p registry]
  (let [by-path (when-let [path (not-empty (:path p))]
                  (let [f (io/file path) f (if (.isAbsolute f) f (io/file (str dir) path))]
                    (when (.isDirectory f) (canonical f))))
        ;; a record's origin as its folder's git config says it now: the
        ;; one registration wrote may be nil, or stale (byj-339)
        by-registry (when (and (not by-path) (not (str/blank? (:git p))))
                      (some #(when (and (not (:missing? %))
                                        (same-repo? (:git p) (or (projects/read-git-remote (str (:path %))) (:git-remote %))))
                               (canonical (:path %)))
                            (force registry)))]
    (cond by-path {:dir by-path :resolved-by :path}
          by-registry {:dir by-registry :resolved-by :registry}
          :else {})))

(defn- semver [s]
  (when-let [[_ a b c] (re-find #"(\d+)(?:\.(\d+))?(?:\.(\d+))?" (str s))]
    (mapv #(or (some-> % parse-long) 0) [a b c])))

(defn version-ok?
  "Does version text `v` (`byj 0.3.19`, `v0.3.19-100-g91fe655`) meet
  `constraint` — `>=`, `>`, `=`, `<` or `<=` and a version, several joined
  by commas, a bare version meaning `=`? nil when either cannot be read."
  [v constraint]
  (when-let [have (semver v)]
    (let [parts (remove str/blank? (map str/trim (str/split (str constraint) #",")))
          tests (for [p parts]
                  (when-let [[_ op ver] (re-matches #"(>=|<=|>|<|=)?\s*v?(\S+)" p)]
                    (when-let [want (semver ver)]
                      (let [c (compare have want)]
                        (case (or op "=") ">=" (>= c 0) ">" (pos? c) "=" (zero? c) "<" (neg? c) "<=" (<= c 0))))))]
      (when (and (seq tests) (every? some? tests)) (every? true? tests)))))

(defn- check-provide
  "Provide `pv` of a project at `pdir` as it stands: `{:id :kind :ok
  :detail}`, and `:version-failed` when only its version fails."
  [pdir {:keys [id kind command version paths]} {:keys [path-env]}]
  (let [base {:id id :kind kind}]
    (case kind
      :files (if-not pdir
               (assoc base :ok false :detail "the project is not on this machine")
               (let [missing (remove #(.exists (io/file pdir %)) paths)]
                 (if (seq missing)
                   (assoc base :ok false :detail (str "missing " (str/join ", " missing)))
                   (assoc base :ok true :detail (str/join ", " paths)))))
      :binary (let [exe (if (str/includes? command "/")
                          (let [f (io/file command) f (if (.isAbsolute f) f (io/file (str (or pdir ".")) command))]
                            (when (and (.isFile f) (.canExecute f)) (.getAbsolutePath f)))
                          (init/on-path command (or path-env (System/getenv "PATH"))))]
                (cond
                  (nil? exe) (assoc base :ok false :detail (str command " is not on PATH"))
                  (str/blank? version) (assoc base :ok true :detail exe)
                  :else
                  (let [r (run (or pdir ".") exe "--version")
                        said (str/trim (str (:out r) (when (str/blank? (:out r)) (:err r))))
                        said (first (str/split-lines said))
                        ok (when (= 0 (:exit r)) (version-ok? said version))]
                    (if ok
                      (assoc base :ok true :detail (str command " " (str/join "." (semver said))))
                      (assoc base :ok false :version-failed true
                             :detail (cond (:error r) (str command " --version: " (:error r))
                                           (not= 0 (:exit r)) (str command " --version failed")
                                           (nil? (semver said)) (str command " --version said no version: " said)
                                           :else (str command " " (str/join "." (semver said)) ", wanted " version)))))))
      (assoc base :ok false :detail "unknown kind"))))

(defn- upstream-changes
  "The commits of the project at `pdir` after `seen` that touch any of
  `paths`, newest first, `max-changes` at most: `{:changes [{:commit
  :subject :at :paths}]}`, or `{:error}` when git cannot tell."
  [pdir seen paths]
  (if (empty? paths)
    {:changes []}
    (let [r (apply git pdir "log" (str "-n" max-changes) "--format=%x00%h%x09%cI%x09%s" "--name-only"
                   (str seen "..HEAD") "--" paths)]
      (if (:error r)
        {:error (:error r)}
        {:changes (vec (for [chunk (rest (str/split (:ok r) #"\u0000"))
                             :let [[head & names] (str/split-lines chunk)
                                   [commit at subject] (str/split head #"\t" 3)]]
                         {:commit commit :at at :subject (str subject) :paths (vec (remove str/blank? names))}))}))))

(defn- project-row
  "Project `id` of the spec in `dir`, resolved and checked: the row, and
  its checks."
  [dir id p {:keys [registry] :as opts}]
  (let [{pdir :dir :keys [resolved-by]} (resolve-project dir p registry)
        remote (when pdir (git-line pdir "config" "--get" "remote.origin.url"))
        head (when pdir (git-line pdir "rev-parse" "--short" "HEAD"))
        provides (mapv #(check-provide pdir % opts) (:provides p))
        file-paths (vec (distinct (mapcat :paths (filter #(= :files (:kind %)) (:provides p)))))
        ch (when (and pdir (:seen p)) (upstream-changes pdir (:seen p) file-paths))
        t {:project id}
        check (fn [level kind msg] {:level level :kind kind :target t :message msg})]
    {:row {:id id :kind (:kind p) :dir pdir :resolvedBy resolved-by :gitRemote remote :head head
           :provides (mapv #(dissoc % :version-failed) provides)
           :changes (or (:changes ch) [])}
     :checks (concat
              (when-not pdir
                [(check :error :project-missing
                        (str (id-name id) " is not on this machine: "
                             (cond (:path p) (str "no directory " (:path p)) :else "no :path")
                             (if (:git p) (str ", and no registered project's origin is " (:git p)) "")))])
              (when (and pdir (:git p) (not (same-repo? (:git p) remote)))
                [(check :warn :remote-mismatch
                        (str (id-name id) "'s origin is " (or remote "not set") ", not " (:git p)))])
              (for [pv provides :when (not (:ok pv))]
                (check :error (if (:version-failed pv) :version :provide-missing)
                       (str (id-name id) " provides " (id-name (:id pv)) ": " (:detail pv))))
              (when (seq (:changes ch))
                [(check :warn :upstream-changed
                        (str (id-name id) " has " (count (:changes ch)) " commit(s) since " (:seen p)
                             " touching what it provides"))])
              (when (:error ch)
                [(check :warn :upstream-changed
                        (str (id-name id) ": cannot list its commits since " (:seen p) " — " (:error ch)))])
              (when-not (:seen p)
                [(check :info :never-reviewed (str (id-name id) " has never been reviewed: no :seen"))]))}))

;; --- cycles --------------------------------------------------------------------------

(defn find-cycle
  "A cycle in the graph `edges` (node → nodes), as the nodes along it with
  the first again at the end, or nil. Nodes are visited in sorted order, so
  the same graph names the same cycle."
  [edges]
  (let [state (atom {})
        found (atom nil)]
    (letfn [(visit [n stack]
              (when-not @found
                (case (get @state n)
                  :done nil
                  :open (let [s (vec stack) i (.indexOf ^java.util.List s n)]
                          (reset! found (conj (subvec s i) n)))
                  (do (swap! state assoc n :open)
                      (doseq [m (sort-by str (get edges n))] (visit m (conj stack n)))
                      (swap! state assoc n :done)))))]
      (doseq [n (sort-by str (keys edges))] (visit n []))
      @found)))

(defn- handoff-cycle [spec]
  (find-cycle (reduce (fn [g {:keys [from to]}] (update g from (fnil conj #{}) to)) {} (:handoffs spec))))

(defn- project-cycle
  "A cycle through the projects: this one's spec, then the deps.edn of each
  project it resolves, and so on, as names — `self` for this project, else
  the id the spec that reached it gives it. nil when there is none."
  [dir spec registry]
  (let [self (canonical dir)
        names (atom {self "self"})
        edges (loop [todo [[self spec]] edges {} n 0]
                (if (or (empty? todo) (> n 50))
                  edges
                  (let [[[d s] & more] todo
                        outs (for [[id p] (:projects s)
                                   :let [pd (:dir (resolve-project d p registry))]
                                   :when pd]
                               (do (swap! names #(if (contains? % pd) % (assoc % pd (id-name id)))) pd))
                        fresh (remove #(contains? edges %) (distinct outs))]
                    (recur (into (vec more) (for [o fresh :when (not= o d)] [o (:value (read-spec o))]))
                           (assoc edges d (set outs)) (inc n)))))]
    (when-let [c (find-cycle edges)]
      ;; told from this project when it is on the cycle
      (let [ring (vec (butlast c))
            i (.indexOf ^java.util.List ring self)
            ring (if (pos? i) (into (subvec ring i) (subvec ring 0 i)) ring)]
        (mapv #(get @names %) (conj ring (first ring)))))))

(defn structural-errors
  "What a spec cannot be written with: a cycle of handoffs or of projects,
  and a handoff naming a role not in `:roles`. Each `{:kind :target
  :message}`."
  [dir spec registry]
  (let [roles (set (keys (:roles spec)))]
    (concat
     (when-let [c (handoff-cycle spec)]
       [{:kind :cycle :target {:handoff [(first c) (second c)]}
         :message (str "the handoffs go round: " (str/join " → " (map id-name c)))}])
     (when-let [c (project-cycle dir spec registry)]
       [{:kind :cycle :target {:project (keyword (second c))}
         :message (str "the projects depend on each other in a cycle: " (str/join " → " c))}])
     (for [{:keys [from to]} (:handoffs spec)
           r [from to]
           :when (not (roles r))]
       {:kind :dangling :target {:handoff [from to]}
        :message (str "the handoff " (id-name from) " → " (id-name to) " names " (id-name r) ", which is not a role")}))))

;; --- sessions ------------------------------------------------------------------------

(defn- session-places
  "Where a session of the project at `dir` may be kept: the project, and
  the same place in each worktree of its repository (a session opened in a
  worktree keeps itself there)."
  [dir]
  (let [here (canonical dir)]
    (if-let [top (worktree/top here)]
      (vec (distinct (cons here (map #(canonical (worktree/within top here %)) (worktree/dirs top)))))
      [here])))

(defn- find-session [places id]
  (when (re-matches #"[A-Za-z0-9._-]+" (str id))
    (some (fn [d]
            (let [c {:project-dir d :working-dir d}]
              (when (.isFile (persist/session-file c id))
                (let [snap (persist/read-session c id)]
                  (when-not (:problem snap) {:id id :dir d :snap snap})))))
          places)))

(defn- iso [ms] (when ms (str (java.time.Instant/ofEpochMilli (long ms)))))

(defn- session-row [id s]
  (if-let [{:keys [snap]} s]
    {:id id :exists true
     :title (or (not-empty (:conversation-title snap)) (:last-question snap) (:question (peek (vec (:previous-turns snap)))))
     :turnCount (:turn-count snap 0) :updatedAt (iso (:updated-at snap))}
    {:id id :exists false :title nil :turnCount 0 :updatedAt nil}))

(defn- turn-rows
  "Session `s`'s kept turns (`changes/rows`), each's `:files` relative to
  the session's project directory, those outside it left out."
  [{:keys [id dir]}]
  (let [c {:project-dir dir :working-dir dir :session-id id}
        root (changes/root c)
        prefix (when root (let [r (canonical root)] (if (= r dir) "" (str (subs dir (inc (count r))) "/"))))]
    (if-not prefix
      []
      (mapv (fn [r] (update r :files (fn [fs] (vec (keep #(when (str/starts-with? % prefix) (subs % (count prefix))) fs)))))
            (changes/rows c id)))))

;; --- handoffs ------------------------------------------------------------------------

(defn path-glob->re
  "A path glob as a regex over a path relative to the project: `*` any run
  within one directory, `**` any run across them (`**/` none, too), `?`
  one character but `/`."
  [g]
  (let [s (str g)]
    (loop [i 0 out "^"]
      (if (>= i (count s))
        (re-pattern (str out "$"))
        (let [c (subs s i (inc i))]
          (cond
            (str/starts-with? (subs s i) "**/") (recur (+ i 3) (str out "(?:.*/)?"))
            (str/starts-with? (subs s i) "**") (recur (+ i 2) (str out ".*"))
            (= c "*") (recur (inc i) (str out "[^/]*"))
            (= c "?") (recur (inc i) (str out "[^/]"))
            (re-find #"[.\\+^$\[\](){}|-]" c) (recur (inc i) (str out "\\" c))
            :else (recur (inc i) (str out c))))))))

(defn- files-in
  "Every file under `dir` relative to it — git's tracked and untracked but
  not ignored, when it is in a work tree, else a walk that leaves out
  `.git` and `.brainyard`."
  [dir]
  (let [r (git dir "ls-files" "-co" "--exclude-standard" "-z")]
    (if (:ok r)
      (vec (remove str/blank? (str/split (:ok r) #"\u0000")))
      (let [root (io/file (str dir)) n (inc (count (.getPath root)))]
        (vec (for [f (file-seq root)
                   :let [p (.getPath ^java.io.File f)]
                   :when (and (.isFile ^java.io.File f) (> (count p) n))
                   :let [rel (subs p n)]
                   :when (not (re-find #"(^|/)\.(git|brainyard)(/|$)" rel))]
               rel))))))

(defn- bd-json [bd-fn dir & args]
  (let [r (apply bd-fn dir args)]
    (if (and (= 0 (:exit r)) (not (str/blank? (:out r))))
      (try {:ok (json/read-str (:out r))} (catch Throwable e {:error (str "bd said what is not JSON: " (ex-message e))}))
      {:error (str "bd " (first args) ": " (str/trim (str (or (not-empty (:err r)) (:out r) (:error r)))))})))

(defn- give-row
  "Give `g` of a handoff from the sessions `from` (found ones): `{:kind
  :ready :detail :items}`."
  [dir g from {:keys [bd-fn path-env files turns]}]
  (case (:kind g)
    :file (let [res (mapv path-glob->re (:paths g))
                hit? (fn [p] (some #(re-matches % p) res))
                items (vec (sort (filter hit? (force files))))
                changed (set (for [s from t (turns s) f (:files t) :when (hit? f)] f))]
            {:kind :file :items items
             :ready (boolean (and (seq items) (seq changed)))
             :detail (cond (empty? items) (str "no file matches " (str/join ", " (:paths g)))
                           (empty? from) "no session of its role to have written one"
                           (empty? changed) (str (count items) " file(s) match; no turn of its role's sessions changed one")
                           :else (str (count changed) " of them changed by its role's turns"))})
    :bead (let [bd? (or bd-fn (init/on-path "bd" (or path-env (System/getenv "PATH"))))
                bd-fn (or bd-fn (fn [d & args] (apply run d "bd" args)))]
            (if-not bd?
              {:kind :bead :ready false :items (vec (remove nil? [(or (:id g) (:parent g))])) :detail "bd is not on PATH"}
              (if-let [id (:id g)]
                (let [r (bd-json bd-fn dir "show" id "--json")
                      st (some-> (:ok r) first :status)]
                  {:kind :bead :items [id] :ready (= "closed" st)
                   :detail (cond (:error r) (:error r) st (str id " is " st) :else (str "no bead " id))})
                (let [r (bd-json bd-fn dir "list" "--parent" (:parent g) "--all" "--json" "--limit" "0")
                      kids (vec (:ok r))
                      open (remove #(= "closed" (:status %)) kids)]
                  {:kind :bead :items (mapv :id kids)
                   :ready (boolean (and (:ok r) (seq kids) (empty? open)))
                   :detail (cond (:error r) (:error r)
                                 (empty? kids) (str (:parent g) " has no children")
                                 (seq open) (str (count open) " of " (count kids) " under " (:parent g) " not closed")
                                 :else (str "all " (count kids) " under " (:parent g) " closed"))}))))
    :branch (let [ds (for [s from :let [d (worktree/session-diff (:dir s))] :when (= :worktree (:kind d))] d)
                  ahead (filter #(pos? (or (:commits %) 0)) ds)]
              {:kind :branch :items (vec (distinct (map :branch ds)))
               :ready (boolean (seq ahead))
               :detail (cond (empty? ds) "no session of its role works in a worktree"
                             (empty? ahead) (str (str/join ", " (distinct (map :branch ds))) " has no commit its base has not")
                             :else (str/join ", " (for [d ahead] (str (:branch d) " is " (:commits d) " commit(s) past " (:against d)))))})
    :note (let [done (filter #(pos? (:turn-count (:snap %) 0)) from)]
            {:kind :note :items (mapv :id done) :ready (boolean (seq done))
             :detail (if (seq done) (str (count done) " session(s) of its role have finished a turn")
                         "no session of its role has finished a turn")})))

(defn- latest-handed [local from to]
  (->> (:handed local)
       (filter #(and (= from (:from %)) (= to (:to %))))
       (sort-by #(some-> (:at %) inst-ms))
       last))

(defn- early?
  "Did session `s` of a handoff's receiving role take a turn before the
  handoff was handed — or take any while it is not?"
  [s handed turns]
  (let [n (:turn-count (:snap s) 0)]
    (cond
      (zero? n) false
      (nil? handed) true
      (and (= (:id s) (:session handed)) (:turn handed)) (> (:turn handed) 1)
      :else (let [at (some-> (:at handed) inst-ms (quot 1000))]
              (boolean (and at (some #(and (:at %) (< (:at %) at)) (turns s))))))))

;; --- the report ----------------------------------------------------------------------

(defn json-value
  "A value of either file as JSON says it: a keyword as its name, no colon;
  an instant as ISO-8601."
  [v]
  (cond (map? v) (into {} (map (fn [[k x]] [k (json-value x)])) v)
        (sequential? v) (mapv json-value v)
        (keyword? v) (id-name v)
        (inst? v) (iso (inst-ms v))
        :else v))

(defn report
  "`byj deps --json` for the project at `dir` (byj-9dv): both files as
  data, the projects resolved and their provides checked, the sessions
  bound to each role, each handoff's gives and whether they are ready, the
  checks and what reading dropped. opts: `:registry` (rows, default the
  person's), `:path-env` (PATH), `:bd-fn` (`(fn [dir & args] {:exit :out
  :err})`, default running bd)."
  [dir & [opts]]
  (let [dir (canonical dir)
        registry (or (:registry opts) (delay (projects/list-projects (projects/projects-root))))
        opts (assoc opts :registry registry)
        sf (spec-file dir) lf (local-file dir)
        {spec :value spec-probs :problems} (read-spec dir)
        {local :value local-probs :problems} (read-local dir)
        prows (for [[id p] (sort-by key (:projects spec))] (project-row dir id p opts))
        places (delay (session-places dir))
        found (memoize (fn [id] (find-session @places id)))
        turns (memoize turn-rows)
        bound (fn [role] (get-in local [:bindings role]))
        roles (for [[id _] (sort-by key (:roles spec))]
                {:id id :sessions (mapv #(session-row % (found %)) (bound id))})
        files (delay (files-in dir))
        handoffs (for [{:keys [from to gives]} (:handoffs spec)]
                   (let [from-s (keep found (bound from))
                         gs (mapv #(give-row dir % from-s (assoc opts :files files :turns turns)) gives)
                         handed (latest-handed local from to)]
                     {:row {:from from :to to :gives gs :ready (every? :ready gs)
                            :handed (when handed {:session (:session handed) :turn (:turn handed) :at (some-> (:at handed) inst-ms iso)})}
                      :handed handed
                      :to-s (keep found (bound to))}))
        checks (concat
                (for [e (structural-errors dir spec registry)] (assoc e :level :error))
                (mapcat :checks prows)
                (for [{:keys [id sessions]} roles
                      c (if (empty? sessions)
                          [{:level :warn :kind :unbound-role :message (str (id-name id) " has no session bound")}]
                          (for [s sessions :when (not (:exists s))]
                            {:level :warn :kind :session-gone
                             :message (str (id-name id) "'s session " (:id s) " no longer exists")}))]
                  (assoc c :target {:role id}))
                (for [{:keys [row handed to-s]} handoffs
                      :let [{:keys [from to gives ready]} row t {:handoff [from to]}
                            hname (str (id-name from) " → " (id-name to))]
                      c (concat
                         (for [g gives :when (not (:ready g))]
                           {:level :wait :kind :not-ready :message (str hname ": " (id-name (:kind g)) " not ready — " (:detail g))})
                         (when (and ready (not handed))
                           [{:level :info :kind :handoff-ready :message (str hname " is ready to hand over")}])
                         (for [s to-s :when (early? s handed turns)]
                           {:level :warn :kind :started-early
                            :message (str hname ": " (:id s) " took turns "
                                          (if handed "before it was handed" "while it is not handed"))}))]
                  (assoc c :target t)))]
    {:files {:spec {:path (.getPath sf) :exists (.isFile sf)}
             :local {:path (.getPath lf) :exists (.isFile lf)}}
     :spec (json-value spec)
     :local (json-value local)
     :projects (json-value (mapv :row prows))
     :roles (json-value (vec roles))
     :handoffs (json-value (mapv :row handoffs))
     :checks (json-value (mapv (fn [c] (array-map :level (:level c) :kind (:kind c) :target (:target c) :message (:message c))) checks))
     :problems (vec (for [{:keys [file key message]} (concat spec-probs local-probs)]
                      {:file file :key key :message message}))}))

;; --- writing -------------------------------------------------------------------------

(def ^:private key-order
  [:schema-version :projects :roles :handoffs :bindings :handed
   :name :id :kind :git :path :title :does :worktree :from :to
   :command :version :paths :parent :provides :affects :seen :gives :session :turn :at])

(defn- key-rank [k] (let [i (.indexOf ^java.util.List key-order k)] (if (neg? i) (count key-order) i)))

(defn ordered
  "`m` with every map in it sorted: the keys this file knows in their
  order, the rest (the ids) by name — so the same map is always written the
  same way, and a diff of two writes is what changed."
  [m]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (not (record? x)))
                     (into (sorted-map-by (fn [a b] (compare [(key-rank a) (str a)] [(key-rank b) (str b)]))) x)
                     x))
                 m))

(defn- stamp []
  (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss-SSS") (java.util.Date.)))

(defn- snapshot!
  "File `f` copied under `.brainyard/deps-snapshots/` before it is
  replaced: `{:path}`, `{:skipped? true}` when there is none yet, or
  `{:error}`."
  [dir f]
  (if-not (.isFile ^java.io.File f)
    {:skipped? true}
    (try
      (let [d (io/file (str dir) ".brainyard" "deps-snapshots")
            base (str/replace (.getName ^java.io.File f) #"\.edn$" "")
            dest (io/file d (str (stamp) "-" base ".edn"))]
        (.mkdirs d)
        (io/copy f dest)
        (doseq [x (drop-last snapshot-retention
                             (sort-by #(.getName ^java.io.File %)
                                      (filter #(str/ends-with? (.getName ^java.io.File %) (str "-" base ".edn")) (.listFiles d))))]
          (.delete ^java.io.File x))
        {:path (.getPath dest)})
      (catch Throwable e {:error (ex-message e)}))))

(defn- write-file! [f m]
  (let [tmp (io/file (.getParentFile ^java.io.File f) (str "." (.getName ^java.io.File f) ".tmp"))]
    (.mkdirs (.getParentFile ^java.io.File f))
    (spit tmp (with-out-str (pprint/pprint (ordered m))))
    (when-not (.renameTo tmp f)
      (io/copy tmp f)
      (.delete tmp))))

(defn- restore! [f snap]
  (try (if (:skipped? snap) (.delete ^java.io.File f) (io/copy (io/file (:path snap)) f))
       (catch Throwable _ nil)))

(defn write!
  "`m`, the whole new map of the spec (`which` `:spec`) or the local file
  (`:local`), as JSON gives it — ids and kinds as strings, `:at` as
  ISO-8601 — written to the project at `dir`: coerced to EDN, held to the
  schema, a spec refused a cycle or a dangling role, the old file
  snapshotted, the new one written in a stable order and read back.
  `{:ok true :file :snapshot}`, `:snapshot` nil when there was no file
  before, or `{:error :stage}` — stage `input`, `schema`, `check`,
  `snapshot`, `write` or `verify` — with `:problems` when the schema
  refused it. opts as `report`'s."
  [dir which m & [opts]]
  (let [dir (canonical dir)
        f (file-of dir which)
        registry (or (:registry opts) (delay (projects/list-projects (projects/projects-root))))]
    (if-not (map? m)
      {:error "the new file should be a JSON object" :stage :input}
      (let [m (cond-> m (and (= :spec which) (not (contains? m :schema-version)) (not (contains? m "schema-version")))
                (assoc :schema-version schema-version))
            {:keys [value problems]} (if (= :local which) (clean-local m) (clean-spec m))]
        (if (seq problems)
          {:error (str "not written: " (str/join "; " (map #(str (or (:key %) "the file") " " (:message %)) problems)))
           :stage :schema :problems (vec problems)}
          (let [errs (when (= :spec which) (seq (structural-errors dir value registry)))]
            (if errs
              {:error (str "not written: " (str/join "; " (map :message errs))) :stage :check}
              (let [snap (snapshot! dir f)]
                (if (:error snap)
                  {:error (str "could not snapshot " (.getPath ^java.io.File f) ": " (:error snap)) :stage :snapshot}
                  (let [w (try (write-file! f value) nil (catch Throwable e (ex-message e)))]
                    (if w
                      (do (restore! f snap) {:error (str "could not write " (.getPath ^java.io.File f) ": " w) :stage :write})
                      (let [back (read-file f (if (= :local which) clean-local clean-spec))]
                        (if (and (empty? (:problems back)) (= value (:value back)))
                          {:ok true :file (.getPath ^java.io.File f) :snapshot (:path snap)}
                          (do (restore! f snap)
                              {:error (str (.getPath ^java.io.File f) " did not read back as written; the old one is put back")
                               :stage :verify}))))))))))))))
