;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.configbox
  "Writing config (RFC-013) — brainyard's `agent/common/config.clj`, in the
  small. `brainyard.config` reads the schema and the files; this writes one key
  at a time, and can put it back.

  A write is a transaction, in brainyard's order:

      writable? → schema → secret scan → mtime → snapshot → write → re-read → revert on failure

  **What may not be written is the point of the namespace.** brainyard's
  allowlist of writable paths *includes* `[:permissions :mode]` and
  `[:permissions :allowed-dirs]`, and guards them with a rule that they never
  self-confirm under `--auto`. Here they are not writable at all, by any route
  (divergence `config-never-writes-permissions`): a config file outlives the
  session that wrote it, so an agent that can widen its own permission gate can
  widen it permanently, and the one thing a gate must not be is something the
  gated party can edit."
  (:require [brainyard.tool-words :as tool-words]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.secrets :as secrets]))

(def snapshot-retention
  "brainyard's `snapshot-retention`."
  20)

;; --- what may not be written ----------------------------------------------------------

(def per-run-keys
  "Keys that describe THIS RUN, not a policy. brainyard keeps `working-dir` out
  of its schema entirely for the same reason — \"one source of truth and no
  divergence between two notions\" — but this port's tree reads it as
  `:config/working-dir`, so it is declared and refused here instead."
  #{:working-dir :project-dir :session-id :resume})

(def grantable
  "The only permission keys a PERSON may write without opening the file, and
  then only through `grant!` — the terminal prompt's \"remember\" answer
  (RFC-012, `byj-gly.83`).

  Two allowlists and the mode. Not the denies, not `:tool-ask-effects`, not
  `:path-permission?`, not `:builtin-hooks?`: a grant widens what is permitted,
  for the one thing that was just asked about, and removing a refusal is a
  different act that belongs in the file where it can be read in context."
  #{:tool-allow-tools :allowed-dirs :permission-mode})

(defn- unwritable* [k]
  (cond
    (not (config/config-keys k))
    (str k " is not a config key. `config$read` lists what is.")

    (= :permissions (get-in config/config-items [k :family]))
    (tool-words/says "configbox/unwritable*" {:k k})

    (= :builtin-hooks? k)
    (tool-words/says "configbox/unwritable* 2" {:k k})

    (per-run-keys k)
    (tool-words/says "configbox/unwritable* 3" {:k k})))

(defn unwritable
  "Why `k` may not be written, or nil.

  With `by` = `:person` — a human answering a terminal prompt — the three
  `grantable` keys are allowed and everything else is refused exactly as
  before. **No command may pass it**: `configbox-test/no-command-can-ask-as-a-person`
  reads the command table and fails if one does, which is what keeps the
  distinction between a person and the agent asking on a person's behalf."
  ([k] (unwritable k nil))
  ([k by]
   (if (and (= :person by) (grantable k) (config/config-keys k))
     nil
     (unwritable* k))))

(defn writable? [k] (nil? (unwritable k)))

(defn writable-keys []
  (vec (sort (filter writable? config/config-keys))))

(declare shown)

(defn rows
  "This run's settings as rows — `{:key :value :default :source :doc :writable?}`,
  with `:changed?` where the value is not stock and `:why-not` where it may not
  be written — for `want` (one key, or nil for all) in `family` (or all).
  What `config$read` returns and `/config` shows."
  [c want family]
  (for [k (sort config/config-keys)
        :when (and (or (nil? want) (= want k))
                   (or (str/blank? (str family))
                       (= (keyword family) (get-in config/config-items [k :family]))))]
    (cond-> {:key k :value (shown k (get c k))
             ;; what unsetting it would give back. Without this the effective
             ;; value is the only number here, and a run outranked by a CLI flag
             ;; read the flag's value AS the default and said so to the user
             ;; (`byj-gly.86`)
             :default (shown k (get config/defaults k))
             :source (get (:config-sources c {}) k :default)
             :doc (get-in config/config-items [k :doc])
             :writable? (writable? k)}
      (not= (get c k) (get config/defaults k)) (assoc :changed? true)
      (unwritable k) (assoc :why-not (unwritable k)))))

;; --- secrets ---------------------------------------------------------------------------

(defn secrets-in
  "Shaped tokens in `x`, masked — `brainyard.secrets/tokens-in`.

  The shaped list only. A refusal has to be right: `password: …` in a note is a
  secret-shaped heuristic match and not a reason to block a config write
  (RFC-013, `brainyard.secrets`)."
  [x]
  (secrets/tokens-in x))

;; --- showing a value ------------------------------------------------------------------

(defn shown
  "A config value as it may be shown — on the screen, in a transcript, or to
  the model through `config$read`/`config$reload`/`config$set`: an MCP
  server's `:env` values masked whatever they look like (they are credentials
  by convention, and one that does not look like a token is still one), and
  every other secret-shaped string redacted. The value in force is untouched;
  this is only what is said about it (byj-gly.235)."
  [k v]
  (secrets/redact
   (if (and (= k :mcp-servers) (map? v))
     (into {} (for [[n spec] v]
                [n (cond-> spec (map? (:env spec)) (update :env #(into {} (for [[ek _] %] [ek "••••"]))))]))
     v)))

(defn change-line
  "One changed key, for a notice: `k old → new` as `shown`; a key merged by
  entry names the entries added, removed and changed rather than print them."
  [k a b]
  (if (and (= :entries (get-in config/config-items [k :merge])) (or (map? a) (nil? a)) (or (map? b) (nil? b)))
    (let [na (into {} (for [[n v] a] [(name n) v])) nb (into {} (for [[n v] b] [(name n) v]))]
      (str (name k) " "
           (str/join " " (concat (for [n (sort (keys nb)) :when (not (contains? na n))] (str "+" n))
                                 (for [n (sort (keys na)) :when (not (contains? nb n))] (str "−" n))
                                 (for [n (sort (keys nb)) :when (and (contains? na n) (not= (na n) (nb n)))] (str "~" n))))))
    (str (name k) " " (pr-str (shown k a)) " → " (pr-str (shown k b)))))

;; --- scope -----------------------------------------------------------------------------

(defn scope-file
  "The config.edn a scope names, or nil.

  **`:auto` is the project**, whenever there is one — the user's file only when
  there is not. brainyard resolves `:auto` two different ways: its READS take
  the project file when it *exists* and fall back to the user's, while its
  `write-edn-config!` takes the project *directory* when it resolves. Following
  the read rule for a write is what this originally did, and the first write to
  a project with no config.edn yet silently landed in `~/.brainyard/config.edn`
  — changing a setting for every other project on the machine, from inside one
  turn, having been asked for `:auto`.

  So the user's file is only ever written when it is asked for by name. The
  blast radius is the reason: a project write affects this repository, and a
  user write affects everything."
  [{:keys [project-dir user-dir]} scope]
  (let [user-dir (or user-dir (System/getenv "HOME"))
        pf (when project-dir (config/config-file project-dir))
        uf (when user-dir (config/config-file user-dir))]
    (case (or scope :auto)
      :project pf
      :user uf
      (or pf uf))))

(defn- resolved-scope [config scope]
  (let [f (scope-file config scope)
        pf (when (:project-dir config) (config/config-file (:project-dir config)))]
    (when f (if (and pf (= (.getPath pf) (.getPath f))) :project :user))))

(defn read-scope
  "The raw map in a scope's file — the whole file, not the bridged view, so a
  write preserves sections this layer knows nothing about."
  [config scope]
  (let [f (scope-file config scope)]
    (if-not (and f (.isFile f))
      {}
      (let [m (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
        (if (map? m) m {})))))

;; --- as data, for a client (byj-d9q) ---------------------------------------------------

(defn json-value
  "A config value as JSON says it: a keyword as its name (with its namespace,
  no colon), a set as an array, a file as its path, anything else JSON has no
  word for as its string."
  [v]
  (walk/postwalk (fn [x]
                   (cond (keyword? x) (subs (str x) 1)
                         (set? x) (vec (sort-by str x))
                         (instance? java.io.File x) (.getPath ^java.io.File x)
                         (or (nil? x) (string? x) (number? x) (boolean? x) (map? x) (sequential? x) (map-entry? x)) x
                         :else (str x)))
                 v))

(defn report
  "`byj config --json`'s settings (byj-d9q): `loaded` is `config/load-config`'s
  answer. `{:files {:user {:path :exists} :project …} :items […] :problems […]}`,
  an item a key as `rows` gives it with its family, type and enum, and
  whether a change waits for the next session; a problem what the files
  held that was dropped, a key at a time. Values as `shown` says them, in
  JSON's words."
  [{:keys [config sources issues]}]
  (let [c (assoc config :config-sources sources)
        file (fn [scope] (when-let [f (scope-file c scope)] {:path (.getPath f) :exists (.isFile f)}))]
    {:files {:user (file :user) :project (file :project)}
     :items (vec (for [{:keys [key value default source doc writable? changed? why-not]} (rows c nil nil)
                       :let [{:keys [family type enum]} (get config/config-items key)]]
                   (cond-> {:key (name key) :family (some-> family name) :type type
                            :default (json-value default) :value (json-value value)
                            :source (name source) :doc doc
                            :writable (boolean writable?) :changed (boolean changed?)
                            :nextSession (contains? config/next-session-keys key)}
                     enum (assoc :enum (json-value (vec enum)))
                     why-not (assoc :whyNot why-not))))
     :problems (vec (for [{:keys [file kind key message]} issues]
                      {:file file :kind (name kind) :key (some-> key name) :message message}))}))

(defn json-input
  "A value for key `k` given as JSON text — `true`, `200`, `[\"a\",\"b\"]`,
  `\"concise\"` — as the key's declared type, as `config/from-json` takes an
  SDK client's; text that is not JSON is taken as `config$set` takes a value,
  coerced from a string by the type (byj-d9q)."
  [k text]
  (let [parsed (try {:v (json/read-str (str text))} (catch Throwable _ nil))]
    (if parsed
      (get (config/from-json {k (:v parsed)}) k)
      (config/coerce (get-in config/config-items [k :type]) (str text)))))

;; --- snapshots ---------------------------------------------------------------------------

(defn- snapshots-dir [config scope]
  (io/file (.getParentFile (scope-file config scope)) "config-snapshots"))

(defn- stamp []
  (let [f (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss")]
    (.format f (java.util.Date.))))

(defn- slugify [s n]
  (let [s (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (if (> (count s) n) (subs s 0 n) s)))

(defn snapshots
  "Snapshots at a scope, newest first."
  [config scope]
  (let [d (snapshots-dir config scope)]
    (if-not (.isDirectory d)
      []
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (sort-by #(.getName %))
           reverse
           (mapv #(hash-map :path (.getPath %) :name (.getName %)
                            :bytes (.length %) :mtime (.lastModified %)))))))

(defn take-snapshot!
  "Copy the current file aside before it is changed. Returns `{:path …}`, or
  `{:skipped? true}` when there is no file yet — a first write has nothing to
  put back, and the revert path handles that by deleting instead."
  [config scope reason]
  (let [f (scope-file config scope)]
    (if-not (and f (.isFile f))
      {:skipped? true}
      (try
        (let [d (snapshots-dir config scope)
              _ (.mkdirs d)
              dest (io/file d (str (stamp) "-" (slugify (or reason "snapshot") 60) ".edn"))]
          (io/copy f dest)
          ;; retention, oldest first out
          (let [all (sort-by #(.getName %) (filter #(str/ends-with? (.getName %) ".edn") (.listFiles d)))
                extra (drop-last snapshot-retention all)]
            (doseq [x extra] (.delete x))
            {:path (.getPath dest) :rotated (mapv #(.getName %) extra)}))
        (catch Throwable e {:error (ex-message e)})))))

;; --- the write ----------------------------------------------------------------------------

(defn- write-edn!
  "Write `m` to `f` atomically — temp file beside it, then rename.

  brainyard `spit`s the pprinted map straight over the file. A crash mid-write
  then leaves a half-written config.edn, which every later run reads as
  unparseable and falls back to defaults on (divergence
  `config-writes-are-atomic`)."
  [f m]
  (let [tmp (io/file (.getParentFile f) (str "." (.getName f) ".tmp"))]
    (.mkdirs (.getParentFile f))
    (spit tmp (with-out-str (pprint/pprint m)))
    (when-not (.renameTo tmp f)
      (io/copy tmp f)
      (.delete tmp))
    f))

(defn- restore!
  "Put back what a snapshot held, or remove a file that had no predecessor."
  [f snap]
  (try
    (if (:skipped? snap)
      (.delete f)
      (io/copy (io/file (:path snap)) f))
    (catch Throwable _ nil)))

(defn set-key!
  "Write one key at a scope, or report why not.

  `v` is the value already coerced — `config$set` coerces from a string by the
  key's declared type, as brainyard's `agent-runtime$config` does.

  Returns `{:ok? true :path :scope :was :now :snapshot}`, or `{:ok? false
  :stage …}` where stage is one of `:writable`, `:schema`, `:secret`, `:mtime`,
  `:snapshot`, `:write`, `:verify`."
  [config scope k v {:keys [reason expected-mtime unset? by]}]
  (let [f (scope-file config scope)
        rscope (resolved-scope config scope)]
    (cond
      (nil? f)
      {:ok? false :stage :scope :error "no config directory resolvable for that scope"}

      (unwritable k by)
      {:ok? false :stage :writable :error (unwritable k by) :key k}

      (and (not unset?) (not (config/valid? k v)))
      {:ok? false :stage :schema :key k :value (shown k v)
       :error (str (config/value-problem k v) "; got " (pr-str (shown k v)))}

      (and (not unset?) (seq (secrets-in v)))
      {:ok? false :stage :secret :key k :matches (secrets-in v)
       :error (tool-words/says "configbox/set-key!")}

      (and expected-mtime (.isFile f) (not= expected-mtime (.lastModified f)))
      {:ok? false :stage :mtime :expected expected-mtime :actual (.lastModified f)
       :error "config.edn changed since you read it — read it again before writing."}

      :else
      (let [current (read-scope config scope)
            was (get-in current [:agent :config] {})
            snap (take-snapshot! config scope (or reason (name k)))]
        (if (:error snap)
          {:ok? false :stage :snapshot :error (:error snap)}
          (let [next-map (if unset?
                           (update-in current [:agent :config] dissoc k)
                           (assoc-in current [:agent :config k] v))]
            (try
              (write-edn! f next-map)
              ;; re-read what was actually written, and put the old file back if
              ;; it does not say what it was meant to (brainyard's smoke test)
              (let [{:keys [config problems]} (config/read-file f)
                    landed (contains? config k)]
                (if (if unset? landed (not= v (get config k)))
                  (do (restore! f snap)
                      {:ok? false :stage :verify :reverted? true
                       :problems problems
                       :error "the file did not read back as written; reverted."})
                  {:ok? true :path (.getPath f) :scope rscope :key k
                   ;; as shown: this reaches the screen and, through
                   ;; config$set, the model (byj-gly.235)
                   :was (shown k (get was k)) :now (when-not unset? (shown k v))
                   :unset? (boolean unset?)
                   :mtime (.lastModified f)
                   :snapshot (:path snap)}))
              (catch Throwable e
                (restore! f snap)
                {:ok? false :stage :write :reverted? true :error (ex-message e)}))))))))

(defn grant!
  "Persist a permission a PERSON just granted at the terminal (RFC-012).

  `k` must be `grantable`. An allowlist key APPENDS — a grant widens what is
  permitted by the one thing that was asked about, and replacing the list would
  silently drop every earlier grant. `:permission-mode` is set, being one value.

  This is the only route by which a permission key is ever written, and the
  only caller is the ask prompt's `remember` answer. It is not reachable from a
  tool: no command passes `:by :person`, and a test reads the command table to
  keep that true."
  [config scope k v reason]
  (if-not (grantable k)
    {:ok? false :stage :writable :error (str k " is not a grantable permission key")}
    (let [current (get-in (read-scope config scope) [:agent :config])
          next-v (if (= :permission-mode k)
                   v
                   (let [have (vec (or (get current k) []))]
                     (if (some #(= v %) have) have (conj have v))))]
      (if (and (not= :permission-mode k) (= next-v (get current k)))
        {:ok? true :already? true :key k :now next-v}
        (set-key! config scope k next-v {:reason (or reason "granted at the prompt")
                                         :by :person})))))

(defn revert!
  "Put a snapshot back. `steps` 1 is the most recent."
  [config scope {:keys [path steps]}]
  (let [f (scope-file config scope)
        available (snapshots config scope)
        src (cond path (io/file path)
                  steps (some-> (nth available (dec steps) nil) :path io/file))]
    (cond
      (nil? f) {:ok? false :error "no config directory resolvable for that scope"}
      (nil? src) {:ok? false :error (if (empty? available)
                                      "no snapshots to revert to"
                                      (str "steps must be 1.." (count available)))}
      (not (.isFile src)) {:ok? false :error (str "no such snapshot: " (.getPath src))}
      :else
      (let [before (take-snapshot! config scope "before-revert")]
        (try
          (io/copy src f)
          {:ok? true :restored-from (.getPath src) :path (.getPath f)
           :scope (resolved-scope config scope) :snapshot (:path before)}
          (catch Throwable e {:ok? false :error (ex-message e)}))))))

;; --- what the file means now --------------------------------------------------------------

(defn reload-report
  "What the files say now, against what this run is using.

  brainyard's `config$reload` diagnostic, which is the useful half of it: a key
  can be changed on disk and still not be in force, because the caller's flag or
  the environment outranks the file (RFC-013's layers). That is the whole of
  *\"I edited config.edn and nothing happened\"*, and nothing else reports it.

  This port cannot *apply* a reload — `make-ctx` builds a turn's registry, zones
  and hooks from config once, and there is no live global to swap (divergence
  `config-reload-reports-rather-than-applies`)."
  [{:keys [config sources]} fresh]
  (let [changed (for [[k v] (:config fresh)
                      ;; a per-run key cannot come from a file, so a difference
                      ;; in one says only that this run is this run
                      :when (and (config/config-keys k)
                                 (not (per-run-keys k))
                                 (not= v (get config k)))]
                  {:key k :from (shown k (get config k)) :to (shown k v)
                   :in-force? (contains? #{:default :user :project} (get sources k :default))
                   :outranked-by (when-not (contains? #{:default :user :project} (get sources k :default))
                                   (get sources k))})]
    {:changed (vec (remove :outranked-by changed))
     :shadowed (vec (filter :outranked-by changed))
     :problems (vec (:problems fresh))
     :files (vec (:files fresh))}))

;; --- commands ------------------------------------------------------------------------------

(defn commands
  "`config$*`. Reading is always offered; writing only when `:config-write?`
  is on, because a project may reasonably want its settings changed by a person
  and nobody else."
  [cfg]
  (into
   [{:name "config$read" :kind :command
     :description (tool-words/description "config$read")
     :input-schema [:map [:key {:optional true} [:string {:desc (tool-words/description "config$read :key")}]]
                         [:family {:optional true} [:string {:desc (tool-words/description "config$read :family")}]]]
     :output-schema [:or {:desc (tool-words/description "config$read result")}
               [:map
                [:keys [:vector [:map [:key :keyword] [:value :any] [:default :any]
                                 [:changed? {:optional true} :boolean]
                                 [:source :keyword] [:doc [:maybe :string]] [:writable? :boolean]
                                 [:why-not {:optional true} :string]]]]
                [:count :int]
                [:files [:vector :string]]]
               [:map [:error-message :string]]]
     :effects #{:fs/read}
     :fn (fn [{:keys [key family]} ctx]
           (let [c (:config ctx)
                 want (when-not (str/blank? (str key))
                        (keyword (str/replace (str key) #"^:" "")))
                 rows (rows c want family)]
             (if (and want (empty? rows))
               {:error-message (str want " is not a config key")}
               {:keys (vec rows) :count (count rows)
                :files (vec (keep #(let [f (scope-file c %)]
                                     (when (and f (.isFile f)) (.getPath f)))
                                  [:project :user]))})))}

    {:name "config$reload" :kind :command
     :description (tool-words/description "config$reload")
     :input-schema [:map]
     :output-schema [:map {:desc (tool-words/description "config$reload result")}
               [:changed [:vector [:map [:key :keyword] [:from :any] [:to :any]
                                   [:in-force? :boolean] [:outranked-by :nil]]]]
               [:shadowed [:vector [:map [:key :keyword] [:from :any] [:to :any]
                                    [:in-force? :boolean] [:outranked-by :keyword]]]]
               [:problems [:vector :string]]
               [:files [:vector :string]]
               [:note :string]]
     :effects #{:fs/read}
     :fn (fn [_ ctx]
           (let [c (:config ctx)
                 fresh (config/load-config {:working-dir (:working-dir c)
                                            :project-dir (:project-dir c)})]
             (assoc (reload-report {:config c :sources (:config-sources c {})} fresh)
                    :note (str "A change is in force from the next turn; this turn keeps the tools "
                               "and prompt it started with. These wait for the next session: "
                               (str/join ", " (map name (sort (remove per-run-keys config/next-session-keys))))
                               "."))))}

    {:name "config$snapshots" :kind :command
     :description (tool-words/description "config$snapshots")
     :input-schema [:map [:scope {:optional true} [:string {:desc (tool-words/description "config$snapshots :scope")}]]]
     :output-schema [:map {:desc (tool-words/description "config$snapshots result")}
               [:snapshots [:vector [:map [:path :string] [:name :string] [:bytes :int] [:mtime :int]]]]
               [:count :int]]
     :effects #{:fs/read}
     :fn (fn [{:keys [scope]} ctx]
           (let [s (snapshots (:config ctx) (some-> (not-empty (str scope)) keyword))]
             {:snapshots s :count (count s)}))}]

   (when (:config-write? cfg true)
     [{:name "config$set" :kind :command
       :description (tool-words/description "config$set")
       :input-schema [:map [:key [:string {:desc (tool-words/description "config$set :key")}]]
                           [:value [:string {:desc (tool-words/description "config$set :value")}]]
                           [:reason [:string {:desc (tool-words/description "config$set :reason")}]]
                           [:scope {:optional true} [:string {:desc (tool-words/description "config$set :scope")}]]
                           [:expected-mtime {:optional true} [:int {:desc (tool-words/description "config$set :expected-mtime")}]]]
       :output-schema [:or {:desc (tool-words/description "config$set result")}
                 [:map [:ok? :boolean] [:path :string] [:scope :keyword] [:key :keyword]
                  [:was :any] [:now :any] [:unset? :boolean] [:mtime :int]
                  [:snapshot [:maybe :string]]]
                 [:map [:ok? :boolean]
                  [:stage [:enum :scope :writable :schema :secret :mtime :snapshot :write :verify]]
                  [:error :string]
                  [:key {:optional true} :keyword] [:value {:optional true} :any]
                  [:matches {:optional true} [:vector :string]]
                  [:expected {:optional true} :int] [:actual {:optional true} :int]
                  [:reverted? {:optional true} :boolean]
                  [:problems {:optional true} [:vector :string]]]]
       :effects #{:fs/write :config/write}
       :fn (fn [{:keys [key value reason scope expected-mtime]} ctx]
             (let [k (keyword (str/replace (str key) #"^:" ""))
                   t (get-in config/config-items [k :type])
                   v (config/coerce t (str value))]
               (set-key! (:config ctx) (some-> (not-empty (str scope)) keyword) k v
                         {:reason reason :expected-mtime expected-mtime})))}

      {:name "config$unset" :kind :command
       :description (tool-words/description "config$unset")
       :input-schema [:map [:key [:string {:desc (tool-words/description "config$unset :key")}]]
                           [:reason [:string {:desc (tool-words/description "config$unset :reason")}]]
                           [:scope {:optional true} [:string {:desc (tool-words/description "config$unset :scope")}]]]
       :output-schema [:or {:desc (tool-words/description "config$unset result")}
                 [:map [:ok? :boolean] [:path :string] [:scope :keyword] [:key :keyword]
                  [:was :any] [:now :nil] [:unset? :boolean] [:mtime :int]
                  [:snapshot [:maybe :string]]]
                 [:map [:ok? :boolean]
                  [:stage [:enum :scope :writable :snapshot :write :verify]]
                  [:error :string]
                  [:key {:optional true} :keyword]
                  [:reverted? {:optional true} :boolean]
                  [:problems {:optional true} [:vector :string]]]]
       :effects #{:fs/write :config/write}
       :fn (fn [{:keys [key reason scope]} ctx]
             (set-key! (:config ctx) (some-> (not-empty (str scope)) keyword)
                       (keyword (str/replace (str key) #"^:" "")) nil
                       {:reason reason :unset? true}))}

      {:name "config$revert" :kind :command
       :description (tool-words/description "config$revert")
       :input-schema [:map [:steps {:optional true} [:int {:desc (tool-words/description "config$revert :steps")}]]
                           [:path {:optional true} [:string {:desc (tool-words/description "config$revert :path")}]]
                           [:scope {:optional true} [:string {:desc (tool-words/description "config$revert :scope")}]]]
       :output-schema [:or {:desc (tool-words/description "config$revert result")}
                 [:map [:ok? :boolean] [:restored-from :string] [:path :string]
                  [:scope :keyword] [:snapshot [:maybe :string]]]
                 [:map [:ok? :boolean] [:error :string]]]
       :effects #{:fs/write :config/write}
       :fn (fn [{:keys [steps path scope]} ctx]
             (revert! (:config ctx) (some-> (not-empty (str scope)) keyword)
                      {:steps steps :path (not-empty (str path))}))}])))
