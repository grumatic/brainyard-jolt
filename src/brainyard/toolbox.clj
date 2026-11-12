;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.toolbox
  "The tool registry and user tools (RFC-010) — brainyard's
  `agent/common/user_tools.clj`, with Tool Lisp bodies instead of SCI.

  A registry is an atom {name tool}. Primitive tools come from
  resources/brainyard/tools (brainyard.tools); composite tools are user tools:
  a Tool Lisp body over other tools, created at runtime through
  `tool$create` and callable at once as `user$tool$<name>`.

  Persistence is brainyard's def-store layout: `<working-dir>/.brainyard/tools/
  <name>.edn` holds {:name :description :input-schema :output-schema} and `<name>.clj`
  the body verbatim — :input-schema brainyard's malli [:map …] (byj-gly.237).
  brainyard's own files load too, an inline :body included.

  Static analysis, because bodies are data (RFC-010):
    - the call graph — a composite may not reach itself (no recursion);
    - argument checks on literal tool-call maps;
    - effects — a composite's :effects are the union over what it can call."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.schema :as schema]
            [brainyard.tool-lisp :as tl]
            [brainyard.tools :as tools]))

(def id-prefix "user$tool$")

(def name-re #"[a-z][a-z0-9-]*")

(defn tool-id [name] (str id-prefix name))

(defn user-tool-name
  "The bare name of a user tool id or name, or nil."
  [s]
  (let [s (str/trim (str s))
        n (if (str/starts-with? s id-prefix) (subs s (count id-prefix)) s)]
    (when (re-matches name-re n) n)))

(def max-depth
  "Composite-within-composite nesting cap at run time. The loader already
  refuses cycles; this backstops a registry changed underneath a running body."
  16)

(def ^:dynamic *depth* 0)

(defn registry
  "A registry holding `tools` (maps with :name)."
  [tools]
  (atom (tools/tools-by-name tools)))

;; ============================================================================
;; :input-schema — brainyard's malli [:map …], as data or as EDN text
;; ============================================================================

(defn- not-a-map-schema
  "Why `form` is not a `[:map …]`, said with the form it should take. Haiku
  sends a list of parameter maps, or malli with its keywords as strings
  (`[\"map\" [\"path\" \"string\"]]`), and the bare refusal let it send the
  same again: 20 refusals with tier D on, 6 off (byj-gly.279). Not
  rewritten into the schema it might have meant: said, not guessed at."
  [form]
  (str "input-schema must be a malli [:map ...] schema"
       (cond
         (and (sequential? form) (seq form) (every? map? form))
         " — one [:map …] whose entries are the arguments, not a list of parameter maps"
         (and (sequential? form) (string? (first form)))
         " — malli's names are keywords (:map, :string), not strings; send the schema as one string of EDN text"
         :else "")
       ", e.g. \"[:map [:path [:string {:desc \\\"File to count\\\"}]] [:min {:optional true} :int]]\""))

(defn read-input-schema
  "A definition's :input-schema as a malli `[:map …]` form, or a problem string.
  A tool that declares none takes no arguments it checks: `[:map]`. A file
  written before byj-gly.237 carries :params, which is said, not guessed at."
  [{:keys [input-schema params]}]
  (let [form (if (string? input-schema)
               (try (edn/read-string input-schema) (catch Throwable _ ::bad))
               input-schema)]
    (cond
      (= ::bad form) "input-schema is not readable EDN"
      (and (nil? form) (some? params))
      ":params is retired — declare the arguments as :input-schema, a malli [:map [:k [:string {:desc \"…\"}]] …]"
      (nil? form) [:map]
      (nil? (schema/map-entries form)) (not-a-map-schema form)
      :else (try (schema/schema form) form
                 (catch Throwable e (str "input-schema is not a malli schema: " (ex-message e)))))))

;; ============================================================================
;; Static analysis
;; ============================================================================

(defn lookup [reg] (fn [sym] (get @reg (str sym))))

(defn- reaches?
  "Does following composite :calls from `start` reach `target`? Returns the
  path, or nil."
  [tools start target]
  (loop [frontier [[start [start]]], seen #{}]
    (when-let [[[n path] & more] (seq frontier)]
      (cond
        (= n target) path
        (contains? seen n) (recur (vec more) seen)
        :else (recur (into (vec more) (for [c (:calls (get tools n))] [c (conj path c)]))
                     (conj seen n))))))

(defn effects
  "The static effect set of tool `name`: a primitive's declared :effects; a
  composite's own (`tool-lisp/body-effects`) and the union over everything it
  can call."
  ([reg name] (effects @reg name #{}))
  ([tools name seen]
   (let [t (get tools name)]
     (cond
       (or (nil? t) (contains? seen name)) #{}
       (= :composite (:kind t)) (reduce into (set (:effects t)) (map #(effects tools % (conj seen name)) (:calls t)))
       :else (set (:effects t))))))

;; ============================================================================
;; Composites
;; ============================================================================

(defn- run-composite [reg form budget args ctx]
  (when (>= *depth* max-depth)
    (throw (ex-info (str "user tools nested deeper than " max-depth) {:kind :depth})))
  (binding [*depth* (inc *depth*)]
    (try
      (tl/run form args
              (fn [name args]
                (if-let [t (get @reg name)]
                  ;; a result inside the body is the body's to read, not a
                  ;; model's: the prompt's cap paged read-file at 16,000
                  ;; characters, numbered its lines, and a body — which cannot
                  ;; loop to read on — answered from the first page
                  ;; (byj-gly.243). As brainyard's code channel, which calls
                  ;; tools in SCI and gets their results whole. The body's own
                  ;; value is still capped on its way back to the model.
                  (tools/call-tool t args (assoc-in ctx [:config :max-output-chars]
                                                    (get-in ctx [:config :tool-lisp-output-chars] 1000000)))
                  ;; a missing tool is a broken body (a dependency was
                  ;; deleted), not a tool failure — fail the whole call
                  (throw (ex-info (str "no tool named " name) {:kind :missing-tool}))))
              budget)
      (catch Throwable e
        (if (fx/cancelled? e)
          (throw e)
          ;; the interpreter's own errors already say "Tool Lisp: …"
          {:error-message (let [m (str (ex-message e))]
                            (if (str/starts-with? m "Tool Lisp:") m (str "Tool Lisp: " m)))})))))

(defn compile-tool
  "A user tool record → {:tool tool} or {:problems [...]}, validated against
  the registry's current tools. With `:repair? true` a body whose delimiters do
  not balance is compiled with its closers rebuilt from its indentation when
  that reads (`tool-lisp/repair-body`), and the result carries :repaired
  {:lines […]} — the tool's :body is then the repaired one (byj-gly.260)."
  [reg {:keys [name description output-schema body] :as record} & {:keys [budget repair?]}]
  (let [repaired (when repair? (tl/repair-body body))
        body (if repaired (:body repaired) body)
        n (user-tool-name name)
        id (some-> n tool-id)
        input-schema (read-input-schema record)
        form (try (tl/read-body body) (catch Throwable e {::problems (:problems (ex-data e) [(ex-message e)])}))
        problems (cond-> []
                   (nil? n) (conj "name must be lowercase-kebab, e.g. count-lines")
                   (string? input-schema) (conj input-schema)
                   (::problems form) (into (::problems form)))]
    (if (seq problems)
      {:problems problems}
      (let [known (fn [sym] (if (= (str sym) id) {:input-schema input-schema} ((lookup reg) sym)))
            problems (tl/validate form known)
            calls (set (map str (tl/tool-refs form known)))
            ;; the path from a called tool back to this one, e.g. [b a] for a → b → a
            cycle (or (when (contains? calls id) [id])
                      (some #(reaches? @reg % id) calls))]
        (cond
          problems {:problems problems}
          cycle {:problems [(str "a tool may not call itself: " (str/join " → " (cons id cycle)))]}
          :else
          {:repaired (when repaired {:lines (:lines repaired)})
           :tool {:name id :kind :composite
                  :description (or description "")
                  ;; an :output-schema that reads as a malli schema is kept as
                  ;; the form, checked against and listed as the line written
                  ;; from it; prose stays prose (RFC-030)
                  ;; …and a form malli does not know (`:number`, a judge's)
                  ;; is kept as its text: kept as a form, every listing that
                  ;; showed the tool threw (provenance P-23)
                  :output-schema (or (schema/read-output-schema output-schema)
                                     (if (or (nil? output-schema) (string? output-schema))
                                       output-schema
                                       (pr-str output-schema)))
                  :input-schema input-schema
                  :body body :form form :calls calls
                  ;; what the body does of its own — reads the clock
                  ;; (byj-gly.261) — beside what the tools it calls do
                  :effects (tl/body-effects form)
                  :fn (fn [args ctx]
                        (run-composite reg form (merge budget (get-in ctx [:config :tool-lisp-budget])) args ctx))}})))))

;; ============================================================================
;; Persistence — brainyard's def-store layout
;; ============================================================================

(defn tools-dir [working-dir]
  (io/file (or working-dir ".") ".brainyard" "tools"))

(defn persist!
  "Write a user tool as `<name>.edn` and its body as `<name>.clj` under `dir`:
  `tool$create`'s write, and a tool proposal's once accepted."
  [dir {:keys [name description output-schema body input-schema]}]
  (let [n (user-tool-name name)]
    (.mkdirs dir)
    (spit (io/file dir (str n ".clj")) (tl/format-body body))
    (spit (io/file dir (str n ".edn"))
          (with-out-str (clojure.pprint/pprint (cond-> {:name n :description (or description "") :input-schema input-schema}
                                                 output-schema (assoc :output-schema output-schema)))))
    (.getPath (io/file dir (str n ".edn")))))

(defn- read-record [^java.io.File f]
  (try
    (let [m (edn/read-string (slurp f))
          sidecar (io/file (.getParentFile f) (str/replace (.getName f) #"\.edn$" ".clj"))]
      (cond-> (assoc m :file (.getPath f))
        (and (nil? (:body m)) (.isFile sidecar)) (assoc :body (slurp sidecar))))
    (catch Throwable e
      {:file (.getPath f) ::unreadable (ex-message e)})))

(defn- names-pending
  "The tools `record`'s body calls that `pending?` says are on their way, or
  nil — none, or a body that does not read."
  [{:keys [body]} pending?]
  (when pending?
    (try (not-empty (vec (sort (map str (tl/tool-refs (tl/read-body body) (comp pending? str))))))
         (catch Throwable _ nil))))

(defn load-user-tools!
  "Compile every user tool under `dir` into `reg`. A tool may call another, so
  compilation repeats until nothing more compiles. Returns {:loaded [ids]
  :skipped [{:file :problems}] :waiting [{:file :waits-for}]} — a bad file
  never costs the others. With `:pending?`, a fn of a tool name that is true
  for a tool not registered YET (an MCP server's, while it starts), a tool
  that did not compile and calls one is `:waiting` on it, not skipped: it is
  not malformed, it is early (byj-gly.300)."
  [reg dir & [{:keys [pending?]}]]
  (let [files (when (.isDirectory (io/file dir))
                (sort-by #(.getName %) (filter #(str/ends-with? (.getName %) ".edn") (.listFiles (io/file dir)))))
        records (map read-record files)
        unreadable (filter ::unreadable records)]
    (loop [pending (remove ::unreadable records), loaded []]
      (let [results (map (fn [r] [r (compile-tool reg r)]) pending)
            ok (filter (comp :tool second) results)]
        (doseq [[_ {:keys [tool]}] ok] (swap! reg assoc (:name tool) tool))
        (if (and (seq ok) (< (count ok) (count pending)))
          (recur (map first (remove (comp :tool second) results)) (into loaded (map (comp :name :tool second) ok)))
          (let [failed (for [[r {:keys [problems]}] results :when problems] [r problems])
                ;; waiting is catching: a tool calling a waiting tool waits too
                waits (loop [waits {}]
                        (let [early (fn [n] (or (pending? n) (contains? waits n)))
                              more (into waits (keep (fn [[r]] (when-let [w (names-pending r (when pending? early))]
                                                                [(some-> (:name r) user-tool-name tool-id) w])))
                                         failed)]
                          (if (= more waits) waits (recur more))))
                failed (for [[r problems] failed] [r problems (get waits (some-> (:name r) user-tool-name tool-id))])]
            {:loaded (into loaded (map (comp :name :tool second) ok))
             :skipped (vec (concat (for [r unreadable] {:file (:file r) :problems [(str "unreadable: " (::unreadable r))]})
                                   (for [[r problems waits] failed :when (nil? waits)]
                                     {:file (:file r) :problems problems})))
             :waiting (vec (for [[r _ waits] failed :when waits]
                             {:file (:file r) :waits-for waits}))}))))))

;; ============================================================================
;; tool$* — brainyard's tool-agent$* commands, renamed
;; ============================================================================

(defn language
  "Tool Lisp as the model reads it — one text for every command that takes a
  body, so none of them sends the model to another's description for it: in
  a live run `tool$run` did, and the model looked `tool$create` up (4,400
  characters) before writing a line (byj-gly.242)."
  []
  (str (slurp (io/resource (if (seq @tl/extensions)
                             "brainyard/prompts/tool-lisp-language-extended.md"
                             "brainyard/prompts/tool-lisp-language.md")))
       ;; tier D, while it is in force (byj-gly.261)
       (when (contains? @tl/extensions :reach)
         (slurp (io/resource "brainyard/prompts/tool-lisp-reach.md")))
       "Allowed functions: " (str/join " " (tl/allowed-function-names))))

(defn- reference []
  (let [create (slurp (io/resource "brainyard/prompts/tool-lisp.md"))
        [head tail] (str/split create #"\n\n(?=The body is validated)" 2)]
    (str head "\n\n" (language) "\n\n" tail)))

(defn- run-reference []
  (str (slurp (io/resource "brainyard/prompts/tool-run.md")) "\n" (language)))

(def ^:private definition-schema
  [:map
   [:name [:string {:desc "lowercase-kebab tool name, e.g. count-lines"}]]
   [:body [:string {:desc "Tool Lisp: (fn [args] ...)"}]]
   [:description {:optional true} [:string {:desc "one-line description"}]]
   [:input-schema {:optional true} [:string {:desc "the arguments, a malli [:map ...] as EDN, e.g. \"[:map [:path [:string {:desc \\\"File to count\\\"}]] [:min {:optional true} :int]]\"; omit for none"}]]
   [:output-schema {:optional true} [:string {:desc "the result as a malli schema in EDN, e.g. \"[:map [:lines :int]]\", or \"[:or [:map [:lines :int]] [:map [:error-message :string]]]\" when it can also be an error; results are checked against it (tool$test says where one differs); prose is refused"}]]])

(defn output-schema-problem
  "Why `output-schema` cannot be a NEW user tool's, or nil: it is absent, or a
  malli schema, as data or EDN text. Prose is refused where a tool is made —
  `tool$create`, `tool$validate`, a distilled draft — because nothing checks
  prose: a distilled `{:projects [{:issues …}]} or {:error-message string}`
  missed the `{:project :error}` its own body returned (byj-gly.263). A tool
  already on disk with prose still loads (`compile-tool`). brainyard's user
  tools take no output schema at all; each registers as `[:map]`."
  [output-schema]
  (when (and (some? output-schema)
             (not (and (string? output-schema) (str/blank? output-schema)))
             (nil? (schema/read-output-schema output-schema)))
    (str "output-schema is not a malli schema — write the result's shape as EDN, e.g. \"[:map [:lines :int]]\"; "
         "a result that is one shape or an error is \"[:or [:map [:lines :int]] [:map [:error-message :string]]]\", "
         "and a part whose shape varies is :any")))

(defn- creation
  "`compile-tool` for a tool being made: its problems include a prose
  `:output-schema`, a blank one is none, and the body is laid out as it will
  be kept (`tl/format-body`), so `tool$read` shows what the file holds.
  `:repair?` as `compile-tool`'s: a body that does not balance does not read,
  so it is laid out only once its closers are rebuilt, when it is saved; the
  lines the reply lists are the model's own, as the repair left them
  (byj-gly.260)."
  [reg args & {:keys [repair?]}]
  (let [args (cond-> args
               (and (string? (:output-schema args)) (str/blank? (:output-schema args))) (dissoc :output-schema)
               (string? (:body args)) (update :body tl/format-body))
        {:keys [problems] :as r} (compile-tool reg args :repair? repair?)]
    (if-let [p (output-schema-problem (:output-schema args))]
      {:problems (conj (vec problems) p) :args args}
      (assoc r :args args))))

(defn- invalid [problems]
  {:error (str "Invalid tool definition:\n- " (str/join "\n- " problems))})

(defn- repair? [ctx] (get-in ctx [:config :tool-lisp-repair?] true))

(defn- repaired-note
  "What a reply says of a body saved with its closers rebuilt: the words are a
  prompt resource, the lines are the ones that changed, as they now read."
  [{:keys [lines]}]
  {:note (str/trim (slurp (io/resource "brainyard/prompts/tool-lisp-repaired.md")))
   :lines (mapv (fn [{:keys [line now]}] (str "line " line ": " (str/trim now))) lines)})

(defn- user-tools [reg]
  (sort-by :name (filter #(= :composite (:kind %)) (vals @reg))))

(defn authoring-tools
  "The tool$* commands over `reg`, persisting under `dir`."
  [reg dir]
  (let [describe (fn [t] {:id (:name t) :description (:description t)
                          :effects (vec (sort (effects reg (:name t))))})]
    [{:name "tool$create" :kind :command
      :description (reference)
      :input-schema definition-schema
      :output-schema [:or {:desc ":persisted is the saved file; :repaired, when the body's closers were rebuilt from its indentation; an :error lists every problem"}
                 [:map [:id :string] [:name :string] [:description :string] [:persisted :string]
                  [:effects [:vector :keyword]]
                  [:repaired {:optional true} [:map [:note :string] [:lines [:vector :string]]]]]
                 [:map [:error :string]]]
      :effects #{:fs/write :tools/define}
      :fn (fn [args ctx]
            (let [{:keys [tool problems args repaired]} (creation reg args :repair? (repair? ctx))]
              (if problems
                (invalid problems)
                ;; both schemas persisted as the forms, not the text they came
                ;; as, and the body as it runs — repaired, if it was
                (let [path (persist! dir (cond-> (assoc args :input-schema (:input-schema tool) :body (:body tool))
                                           (:output-schema args) (assoc :output-schema (:output-schema tool))))]
                  (swap! reg assoc (:name tool) tool)
                  (cond-> (assoc (describe tool) :name (user-tool-name (:name tool)) :persisted path)
                    repaired (assoc :repaired (repaired-note repaired)))))))}
     {:name "tool$validate" :kind :command
      :description "Check a user tool definition (same args as tool$create) without saving it."
      :input-schema definition-schema
      :output-schema [:or [:map [:valid :boolean] [:effects [:vector :keyword]] [:calls [:vector :string]]
                        [:repaired {:optional true} [:map [:note :string] [:lines [:vector :string]]]]]
                 [:map [:error :string]]]
      :effects #{}
      :fn (fn [args ctx]
            (let [{:keys [tool problems repaired]} (creation reg args :repair? (repair? ctx))]
              (if problems
                (invalid problems)
                (cond-> {:valid true :calls (vec (sort (:calls tool)))
                         :effects (vec (sort (effects (atom (assoc @reg (:name tool) tool)) (:name tool))))}
                  repaired (assoc :repaired (repaired-note repaired))))))}
     {:name "tool$list" :kind :command
      :description "List user tools."
      :input-schema [:map]
      :output-schema [:map [:tools [:vector [:map [:id :string] [:description :string] [:effects [:vector :keyword]]]]]]
      :effects #{}
      :fn (fn [_ _] {:tools (mapv describe (user-tools reg))})}
     {:name "tool$read" :kind :command
      :description "Read a user tool's definition, including its Tool Lisp body."
      :input-schema [:map [:name [:string {:desc "tool name or id"}]]]
      :output-schema [:or [:map [:id :string] [:name :string] [:description :string]
                        [:input-schema [:vector :any]]
                        [:output-schema {:optional true} :any] [:body :string]
                        [:calls [:vector :string]] [:effects [:vector :keyword]]]
                 [:map [:error :string]]]
      :effects #{}
      :fn (fn [{:keys [name]} _]
            (if-let [t (some->> (user-tool-name name) tool-id (get @reg))]
              (merge (describe t) (select-keys t [:input-schema :body]) (when (:output-schema t) {:output-schema (:output-schema t)})
                     {:name (user-tool-name (:name t)) :calls (vec (sort (:calls t)))})
              {:error (str "no user tool " name)}))}
     {:name "tool$delete" :kind :command
      :description "Delete a user tool and its files."
      :input-schema [:map [:name [:string {:desc "tool name or id"}]]]
      :output-schema [:or {:desc ":dependents are the tool ids still calling it"}
                 [:map [:deleted :string] [:dependents [:vector :string]]]
                 [:map [:error :string]]]
      :effects #{:fs/write :tools/define}
      :fn (fn [{:keys [name]} _]
            (let [n (user-tool-name name)
                  id (some-> n tool-id)]
              (if-not (and id (get @reg id))
                {:error (str "no user tool " name)}
                (do (swap! reg dissoc id)
                    (doseq [ext [".edn" ".clj"]] (.delete (io/file dir (str n ext))))
                    {:deleted n
                     :dependents (vec (sort (map :name (filter #(contains? (:calls %) id) (user-tools reg)))))}))))}
     {:name "tool$run" :kind :command
      :description (run-reference)
      :input-schema [:map [:body [:string {:desc "Tool Lisp: (fn [args] ...)"}]]
                          [:args {:optional true} [:map {:desc "the map passed to the body"}]]]
      :output-schema "the body's value; {:error \"Invalid Tool Lisp: ...\"} listing every problem, or {:error-message \"Tool Lisp: ...\"} when it fails while running"
      :effects #{:dynamic}
      :fn (fn [{:keys [body args]} ctx]
            (let [form (try (tl/read-body body) (catch Throwable e {::problems (:problems (ex-data e) [(ex-message e)])}))
                  problems (or (::problems form) (tl/validate form (lookup reg)))]
              (if (seq problems)
                {:error (str "Invalid Tool Lisp:\n- " (str/join "\n- " problems))}
                (run-composite reg form (get-in ctx [:config :tool-lisp-budget]) (or args {}) ctx))))}
     {:name "tool$test" :kind :command
      :description "Run a tool (usually a user tool you just defined) on sample args and return its result."
      :input-schema [:map [:name [:string {:desc "tool name or id, e.g. count-lines"}]]
                          [:args {:optional true} [:map {:desc "the tool's args"}]]]
      :output-schema "the tool's own result; or, when its :output-schema is a schema the result does not match, {:result, :output-problems [string]}"
      :effects #{:dynamic}
      :fn (fn [{:keys [name args]} ctx]
            (if-let [t (or (get @reg (str name)) (some->> (user-tool-name name) tool-id (get @reg)))]
              (let [r (tools/call-tool t (or args {}) ctx)]
                ;; the author is here to find out what is wrong: a result its
                ;; own :output-schema does not describe is said beside it (RFC-030)
                (if-let [ps (and (not (tools/failed? r)) (schema/output-problems t r))]
                  {:result r :output-problems ps}
                  r))
              {:error (str "no tool " name)}))}]))
