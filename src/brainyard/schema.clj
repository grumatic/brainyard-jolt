;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.schema
  "malli for tool schemas (RFC-030): a registry of shared schemas, and a tool's
  arguments checked and coerced against a malli schema compiled once.

  The registry is brainyard's `clj-llm.core.schema-registry` — a `defonce`
  atom seeded with malli's defaults, `register!` and `defschemas` — with one
  difference: it is passed to malli explicitly rather than installed as malli's
  default registry, so loading this namespace changes nothing process-wide
  (a process-wide rebinding broke 85 assertions once, provenance P-18).

  A tool's arguments are its malli `:input-schema`, as in brainyard, checked
  nested and all (byj-gly.237). They are decoded with `tool-transformer`,
  brainyard's rules plus malli's string-transformer, after the four rules
  models taught this port (`lenient-fns`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ;; malli.transform builds a DateTimeFormatter at load: java.time
            ;; must be there first, as for data.json (brainyard.json loads it)
            [brainyard.json :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.registry :as mr]
            [malli.transform :as mt]))

;; --- the registry -------------------------------------------------------------

;; `defonce`: brainyard's note on this atom applies — a plain `def` made
;; reloading the namespace drop every schema registered process-wide
(defonce ^:private registry* (atom (m/default-schemas)))

(def registry
  "The registry every schema here is compiled against."
  (mr/mutable-registry registry*))

(defn register!
  "Register `schema-map` ({qualified-keyword schema}) in the shared registry."
  [schema-map]
  (swap! registry* merge schema-map))

(defmacro defschemas
  "brainyard's `defschemas`: register the schemas and def `sym` to the map."
  [sym schema-map]
  `(do (register! ~schema-map)
       (def ~sym ~schema-map)))

(defn schema
  "`form` as a malli schema against the shared registry."
  [form]
  (m/schema form {:registry registry}))

;; The shared schemas a tool or a predictor may refer to by name — the
;; counterpart of brainyard's `ai.brainyard.agent.common.schema/domain`, named
;; in this port's own namespace. A tool call is `{name args}` where brainyard's
;; is `{tool-name tool-args}`: what a model writes for a call to anything else
;; (divergence `a-tool-call-is-name-and-args`). The `-object-arg` pair is a
;; field a model may send either as data or as a JSON/EDN string, which the
;; tool parses itself.
(defschemas common
  {::question [:string {:desc "User question"}]
   ::answer [:string {:desc "Answer to the question"}]
   ::instruction [:string {:desc "Instruction to help produce an objective"}]
   ::tool-context [:string {:desc "Context information for using tools"}]
   ::tool-name [:string {:desc "tool name"}]
   ::tool-args [:map-of {:desc "Tool arguments as a JSON object, e.g. {\"url\": \"…\", \"n\": 3}. Use {} for no arguments."}
                :any :any]
   ;; closed, as a provider's strict structured output wants it
   ::tool-call [:map {:closed true :desc "One tool invocation"}
                [:name ::tool-name]
                [:args ::tool-args]]
   ::tool-calls [:vector {:desc "Tool invocations to run this iteration. Empty when not calling tools (using another channel or answering)."}
                 ::tool-call]
   ::map-object-arg [:or [:string] [:map]]
   ::vector-object-arg [:or [:string] [:vector :any]]
   ::agent-id [:string {:desc "an agent identifier (e.g., 'xyz$abc', 'xyz$abc/extension')"}]})

;; --- a tool's arguments ---------------------------------------------------------------
;;
;; A tool declares its arguments as brainyard does, a malli `:input-schema`:
;; `[:map [:path [:string {:desc "…"}]] [:limit {:optional true} [:int {:desc "…"}]]]`
;; (byj-gly.237). A `:desc` may sit on the value's schema, as brainyard writes
;; it, or on the entry.

(defn tool-schema
  "The malli form a tool's arguments are checked against: its `:input-schema`,
  or an open `[:map]` for a tool that declares none."
  [tool]
  (or (:input-schema tool) [:map]))

(defn map-entries
  "A `[:map …]` form's entries as `[k props schema]`, props nil when absent; nil
  for any other form."
  [form]
  (when (and (vector? form) (= :map (first form)))
    (for [e (rest form)
          :when (vector? e)
          :let [[k a b] e]]
      (if (map? a) [k a b] [k nil a]))))

(defn all-optional
  "A `[:map …]` form with every entry optional — a check of a definition that
  may be incomplete."
  [form]
  (into [:map] (for [[k props s] (map-entries form)] [k (assoc props :optional true) s])))

(defn- type-name
  "An argument's type as the tool listing names it — the JSON Schema type
  brainyard's listing shows (`format-agent-tools`), an enum as its members."
  [s]
  (case (m/type s)
    (:string :keyword :uuid) "string"
    (:int :pos-int :nat-int) "integer"
    :double "number"
    :boolean "boolean"
    (:vector :sequential :set :tuple) "array"
    (:map :map-of) "object"
    :enum (str/join "|" (map pr-str (m/children s)))
    (:maybe :malli.core/schema) (type-name (if (= :maybe (m/type s)) (first (m/children s)) (m/deref s)))
    :or (let [ts (distinct (map type-name (m/children s)))]
          (cond (= #{"integer" "number"} (set ts)) "number"
                (= 1 (count ts)) (first ts)
                :else (str/join " or " ts)))
    "any"))

(defn inputs
  "A tool's arguments as the listing shows them: `[{:name :type :optional?
  :desc}]`, in the schema's order."
  [tool]
  (vec (for [[k props s] (map-entries (tool-schema tool))
             :let [c (schema s)]]
         {:name k
          :type (type-name c)
          :optional? (boolean (:optional props))
          :desc (or (:desc props) (:desc (m/properties c)))})))

;; --- coercion -------------------------------------------------------------------------

;; The port's own coercions, each a rule a model taught it, for an argument of
;; the tool — a top-level entry, where every one of them was met:
;; - anything but a string, where a string is wanted, printed (`pr-str`);
;; - a lone string, where an array is wanted, wrapped;
;; - an object that arrived as a JSON string, parsed — always so in the native
;;   tool calls `llm/rejected-tool-calls` recovers;
;; - a whole float, where an integer is wanted, taken (a JSON 3.0).
;; Not inside an `:or`, where printing a map would pick the string branch
;; (`::map-object-arg`), nor deeper, where brainyard's own decoding applies.

(defn- as-string [v] (if (or (nil? v) (string? v)) v (pr-str v)))
(defn- as-vector [v] (if (string? v) [v] v))
(defn- as-map [v]
  (if (string? v)
    (let [m (try (json/read-str v) (catch Throwable _ nil))]
      (if (map? m) m v))
    v))
(defn- as-int
  ;; a whole float in range, or an integer's text with space around it — never
  ;; 3.7 truncated to 3, nor 1e20 thrown out of check-args (byj-gly.234)
  [v]
  (cond
    (and (number? v) (not (integer? v)) (== v (Math/floor (double v)))
         (< (Math/abs (double v)) 9.0E18))
    (long v)
    (string? v) (let [t (str/trim v)]
                  (or (when (re-matches #"-?\d{1,18}" t) (parse-long t)) v))
    :else v))

(defn- lenient-fns
  "`{k coerce}` for the arguments of a compiled `[:map …]` schema."
  [s]
  (when (= :map (m/type s))
    (into {} (keep (fn [[k _ c]]
                     (when-let [f (case (m/type c)
                                    :string as-string
                                    (:vector :sequential) as-vector
                                    (:map :map-of) as-map
                                    :int as-int
                                    nil)]
                       [k f])))
          (m/children s))))

(def tool-transformer
  "How a model's tool-call args are decoded before they are checked:
  brainyard's `llm-args-transformer` (agent/core/tool.clj) — a keyword from
  `\"a\"` or `\":a\"`, a boolean from `\"True\"`, `\"yes\"` or `\"1\"` as well as
  `\"true\"`, an enum member in whichever form arrived — then malli's
  string-transformer, so `\"145\"` is an :int. The port's own coercions run
  before it, on the arguments (`lenient-fns`)."
  (mt/transformer
   {:name :llm-keyword
    :decoders
    {:keyword (fn [v] (if (string? v) (keyword (if (str/starts-with? v ":") (subs v 1) v)) v))
     :boolean (fn [v] (if (string? v)
                        (case (str/lower-case v)
                          ("true" "yes" "1") true
                          ("false" "no" "0") false
                          v)
                        v))
     :enum {:compile
            (fn [schema _]
              (let [members (set (m/children schema))
                    strip (fn [v] (if (str/starts-with? v ":") (subs v 1) v))]
                (cond
                  (every? string? members)
                  (fn [v] (let [s (cond (keyword? v) (name v) (string? v) (strip v) :else v)]
                            (if (contains? members s) s v)))
                  (every? keyword? members)
                  (fn [v] (if (string? v)
                            (let [k (keyword (strip v))] (if (contains? members k) k v))
                            v))
                  :else nil)))}}}
   (mt/string-transformer)))

;; --- the check -------------------------------------------------------------------------

(defn- message
  "One humanized problem as the check has always said it: a missing key as
  ``missing required `k` ``, anything else as `` `k` <what malli says> ``, a
  nested key by its path."
  [path msg]
  (let [k (str/join "." (map #(if (keyword? %) (name %) (str %)) path))]
    (if (= "missing required key" msg)
      (str "missing required `" k "`")
      (str "`" k "` " msg))))

(def ^:private errors
  "malli's messages, but a value of the wrong kind where a collection is
  wanted says which kind: malli's own \"invalid type\" tells a model nothing
  about what it should have sent."
  (assoc me/default-errors
         :malli.core/invalid-type
         {:error/fn (fn [{:keys [schema]} _]
                      (case (m/type schema)
                        (:vector :sequential :set) "should be an array"
                        (:map :map-of) "should be an object"
                        "invalid type"))}))

(defn- humanize [explanation] (me/humanize explanation {:errors errors}))

(defn- problems [humanized path]
  (cond
    (map? humanized) (mapcat (fn [[k v]] (problems v (conj path k))) humanized)
    (and (sequential? humanized) (every? string? humanized)) (map #(message path %) humanized)
    (sequential? humanized) (mapcat (fn [i v] (when v (problems v (conj path i)))) (range) humanized)
    (string? humanized) [(message path humanized)]
    :else []))

(defn- compile-checker [form]
  (let [s (schema form)
        lenient (lenient-fns s)
        decode (comp (m/decoder s tool-transformer)
                     (fn [args] (reduce-kv (fn [m k f] (if (contains? m k) (update m k f) m)) args lenient)))
        valid? (m/validator s)
        explain (m/explainer s)]
    (fn [args]
      ;; a null the model sent for an argument is the argument left out: a
      ;; required one is missing, an optional one absent, as they always were
      (let [v (decode (into {} (remove (comp nil? val)) args))]
        (if (valid? v)
          {:args v}
          {:problems (vec (problems (humanize (explain v)) []))})))))

(def ^:private checker
  "A checker per schema form, compiled once: a schema is compiled on its first
  call, not on every one."
  (memoize compile-checker))

(defn check-args
  "`{:args coerced}` or `{:problems [...]}`: `args` decoded with
  `tool-transformer` and validated against the tool's schema."
  [tool args]
  ;; never a throw: a value no decoder can take is a problem to report, since
  ;; call-tool promises the model an :error-message, not an exception
  (try ((checker (tool-schema tool)) args)
       (catch Throwable e {:problems [(str "the arguments could not be read: " (or (ex-message e) e))]})))

;; --- :output-schema (RFC-030 stage 2) ------------------------------------------------

(defn read-output-schema
  "A tool's `:output-schema` as a malli form, or nil when it is prose. A form
  is given as data (a primitive's spec file) or as EDN text (a `tool$create`
  argument); text that does not read as a schema malli compiles — `\"{:lines
  int}\"` reads as a map — is prose, as every result description was once."
  [returns]
  (let [form (cond (or (vector? returns) (keyword? returns)) returns
                   ;; the WHOLE text must be one form: reading only the first
                   ;; took ":string — the rendered report" for :string and lost
                   ;; the prose (byj-gly.231)
                   (string? returns) (let [s (str/trim returns)]
                                       (when (or (str/starts-with? s "[") (str/starts-with? s ":"))
                                         (let [forms (try (edn/read-string (str "[" s "\n]")) (catch Throwable _ nil))]
                                           (when (= 1 (count forms)) (first forms))))))]
    (when (and (or (vector? form) (keyword? form))
               (try (schema form) true (catch Throwable _ false)))
      form)))

(declare describe)

(def ^:private ^:dynamic *map-depth*
  "How many maps deep `describe` is: past `max-map-depth` a map is `{…}`."
  0)

(def ^:private max-map-depth
  "Deeper than this, the line says a map is there and not what is in it: the
  check holds the result to all of it, and the model sees the rest in the
  result itself — spelt out, session$analytics' metrics made one line of
  ~1,500 characters (byj-gly.199)."
  2)

(defn- describe-entries
  "An optional key ends in `?` — unless its name already does (`:changed?`),
  where a second one would read as a typo: that key says `(optional)` after
  its type instead."
  [entries]
  (str "{" (str/join ", " (for [[k props s] entries
                                :let [opt (:optional props)
                                      q? (str/ends-with? (name k) "?")]]
                            (str k (when (and opt (not q?)) "?") " " (describe s)
                                 (when (and opt q?) " (optional)"))))
       "}"))

(defn describe
  "An output schema as the one line the model reads — what a tool's result
  description was written as by hand, now written from the schema, so the two
  cannot differ:
  `{:path string, :size int, :total-lines? int} or {:error string}`. An
  optional key ends in `?`; a schema's `:desc`, at the top, follows a dash."
  [form]
  (let [s (schema form)
        props (m/properties s)
        text (case (m/type s)
               :map (if (>= *map-depth* max-map-depth)
                      "{…}"
                      (binding [*map-depth* (inc *map-depth*)] (describe-entries (m/children s))))
               (:vector :sequential :set) (str "[" (describe (first (m/children s))) " …]")
               :map-of (let [[k v] (m/children s)] (str "{" (describe k) " " (describe v) "}"))
               :or (str/join " or " (map describe (m/children s)))
               :maybe (str (describe (first (m/children s))) " or nil")
               :enum (str/join "|" (map pr-str (m/children s)))
               :tuple (str "[" (str/join " " (map describe (m/children s))) "]")
               :malli.core/schema (describe (m/deref s))
               :string "string" :int "int" :double "number" :boolean "bool" :keyword "keyword"
               :any "any" :nil "nil"
               ;; a predicate schema — number?, int?, string? — by its noun:
               ;; `:score number?` read as an optional key
               (let [t (m/type s)
                     n (if (keyword? t) (name t) (str t))]
                 (if (str/ends-with? n "?") (subs n 0 (dec (count n))) n)))]
    (if-let [d (and (:desc props) (#{:or :map :vector} (m/type s)) (:desc props))]
      (str text " — " d)
      text)))

(defn tool-output-schema
  "The schema a tool's results are held to: its `:output-schema` when that is
  a malli form (byj-gly.238). A prose one — `tool$run`'s, a user tool's —
  has none."
  [tool]
  (let [o (:output-schema tool)]
    (when (and (or (vector? o) (keyword? o)) (try (schema o) true (catch Throwable _ false)))
      o)))

(def ^:private described (memoize (fn [form] (describe form))))

(defn output-text
  "An `:output-schema` as the model reads it: prose as it is, a schema as its
  line — written once per form, since every prompt shows every tool's."
  [output-schema]
  (cond (nil? output-schema) nil
        (string? output-schema) output-schema
        ;; a form malli does not know is shown as written: one tool's bad schema
        ;; must not take the whole listing down (provenance P-23)
        :else (try (described output-schema) (catch Throwable _ (pr-str output-schema)))))

(def ^:private output-explainer
  (memoize (fn [form] (m/explainer (schema form)))))

(defn output-problems
  "What is wrong with `result` as the tool's `:output-schema` says it — the
  same messages as for arguments — or nil when it conforms, or when the tool
  has no schema to say."
  [tool result]
  (when-let [form (tool-output-schema tool)]
    (when-let [e ((output-explainer form) result)]
      (vec (problems (humanize e) [])))))

;; --- recording (the suite as a conformance check) ---------------------------------

;; nil unless something asked to record: the test runner does, so that every
;; command any test calls is held to its :output-schema — the suite already calls
;; them with real args in real state, which bespoke samples would only repeat
(defonce ^:private recorder (atom nil))

(defn record-outputs!
  "Start recording every check of a result against an `:output-schema` — or,
  given what `recorded-outputs` said earlier, put that back (nil stops)."
  ([] (reset! recorder []))
  ([recorded] (reset! recorder recorded)))

(defn recorded-outputs
  "What was recorded since `record-outputs!`: `[{:tool-name :problems}]`,
  `:problems` nil for a result that conformed. nil when not recording."
  []
  @recorder)

(def ^:dynamic *record?*
  "Bound false by a test whose mismatch is the point of it."
  true)

(defn note-output!
  "Record one checked result, when recording."
  [tool-name problems]
  (when (and *record?* @recorder)
    (swap! recorder #(when % (conj % {:tool-name tool-name :problems problems})))))

(defn result-keys
  "The keys a result of `form` may have, when the schema says for certain: a
  map's own keys, or every key of an `:or` whose branches are all maps. nil
  when it cannot say — a vector, a `:map-of`, anything else — since \"no such
  key\" is a claim, and a guess would refuse a tree that is right."
  [form]
  (when form
    (letfn [(ks [s]
              (let [s (m/deref-all s)]
                (case (m/type s)
                  :map (set (map first (m/children s)))
                  :or (let [kss (map ks (m/children s))]
                        (when (every? some? kss) (reduce into #{} kss)))
                  nil)))]
      (ks (schema form)))))

;; --- signatures (byj-gly.203) ----------------------------------------------------------

(defn explain-problems
  "What is wrong with `value` as `form` says it, in the messages every check
  here gives — ``missing required `k` ``, `` `path` <what malli says> `` — or
  nil when it conforms."
  [form value]
  (when-let [e (m/explain (schema form) value)]
    (vec (problems (humanize e) []))))

(defn- inline-refs
  "`s` with every registry reference replaced by what it names, all the way
  down: a JSON Schema sent to a model or a provider is one flat document, not
  `$ref`s into definitions the port would have to ship alongside it."
  [s]
  (m/walk s (m/schema-walker (fn [s] (if (= :malli.core/schema (m/type s)) (inline-refs (m/deref s)) s)))))

(defn json-schema
  "`form` as the JSON Schema a model and a provider read — `malli.json-schema`,
  registry references inlined, `type` first as every hand-written schema had
  it, so a prompt written from a schema reads as it did."
  [form]
  (letfn [(tidy [js]
            (cond
              (map? js) (let [js (into (empty js)
                                       (map (fn [[k v]]
                                              [k (case k
                                                   ;; property names are the reply's own keys:
                                                   ;; strings, as a hand-written schema had them
                                                   :properties (into {} (map (fn [[p s]] [(name p) (tidy s)])) v)
                                                   :required (mapv name v)
                                                   (tidy v))]))
                                       js)]
                          (if (contains? js :type) (into (array-map :type (:type js)) (dissoc js :type)) js))
              (vector? js) (mapv tidy js)
              :else js))]
    (tidy (mjs/transform (inline-refs (schema form))))))

;; --- JSON Schema in -------------------------------------------------------------------

(def ^:private json-schema-supported
  #{:type :properties :required :items :enum :description :title :default :additionalProperties :$schema})

(defn from-json-schema
  "A JSON Schema — an SDK client's tool or signature field (RFC-053) — as the
  malli form this port's tools are described with (RFC-030), for the subset
  tools use: `object` with `properties` and `required`, `string` (an `enum`
  as `[:enum …]`), `integer`, `number`, `boolean`, `array` with `items`,
  `null`, and no `type` as `:any`; `description` as the entry's `:desc`.
  Anything else — `oneOf`, `$ref`, `allOf`, a list of types — throws, naming
  it, rather than describing a tool's arguments as something they are not.
  Keys may be keywords (as `brainyard.json` reads them) or strings."
  [js]
  (let [js (into {} (map (fn [[k v]] [(keyword (name k)) v])) (or js {}))
        unknown (remove json-schema-supported (keys js))
        desc (:description js)
        props (cond-> {} desc (assoc :desc desc))
        with-props (fn [form] (if (seq props) (if (keyword? form) [form props] (into [(first form) props] (rest form))) form))]
    (when (seq unknown)
      (throw (ex-info (str "JSON Schema keyword(s) not supported here: " (str/join ", " (map name unknown)))
                      {:unsupported (mapv name unknown)})))
    (if-let [e (:enum js)]
      (with-props (into [:enum] e))
      (case (some-> (:type js) name)
        "object" (let [required (set (map name (:required js)))
                       entries (for [[k v] (:properties js)
                                     :let [v (into {} (map (fn [[a b]] [(keyword (name a)) b])) v)
                                           opt? (not (contains? required (name k)))
                                           eprops (cond-> {}
                                                    opt? (assoc :optional true)
                                                    (:description v) (assoc :desc (:description v)))
                                           ;; the description is the entry's, as a tool's own are
                                           sub (from-json-schema (dissoc v :description))]]
                                 (if (seq eprops) [(keyword (name k)) eprops sub] [(keyword (name k)) sub]))]
                   (with-props (into [:map] entries)))
        "string" (with-props :string)
        "integer" (with-props :int)
        "number" (with-props [:or :int :double])
        "boolean" (with-props :boolean)
        "array" (with-props [:vector (if (:items js) (from-json-schema (:items js)) :any)])
        "null" (with-props :nil)
        nil (with-props :any)
        (throw (ex-info (str "JSON Schema type not supported here: " (pr-str (:type js))) {:type (:type js)}))))))
