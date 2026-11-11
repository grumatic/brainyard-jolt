(ns brainyard.signature
  "DSPy-style signatures and prompt assembly, ported from brainyard's
  `clj-llm.core.signature` / `prompt` / `schema`.

  A signature is plain data, and each field a malli map entry — its key, its
  `:desc`, and its schema — as brainyard's `defsignature` fields are malli
  (RFC-030, byj-gly.203):
    {:instructions \"...\"
     :inputs  [[:question {:desc \"...\"} :string] ...]              ; declared order
     :outputs [[:verdict {:desc \"...\"} [:enum \"A\" \"B\"]] ...]
     :chain-of-thought? true                  ; false for a :predict predictor
     :reasoning-desc \"...\"}                  ; optional: what `reasoning` asks for

  From that one schema come the `(type)` in the prompt's field list, the JSON
  Schema the model and the provider are given, the default for a field the
  reply left out, and the check a reply is held to.

  One call = two messages. System: input fields → output fields (CoT
  prepends `reasoning`) → JSON schema → format line → demos → instructions →
  the context zones. User: `name: value` lines in declared order, then a
  reminder of the output field order."
  (:require [clojure.string :as str]
            ;; before malli: malli.transform builds a DateTimeFormatter at
            ;; load, and brainyard.json is what installs java.time
            [brainyard.json :as json]
            [malli.core :as m]
            [malli.transform :as mt]
            [brainyard.schema :as schema]))

(def reasoning-field-desc
  (str "Brief reasoning leading to the answer: at most 3 short sentences (~60 words). "
       "Plain prose only; no headings, no bullet lists, no code blocks. "
       "State the decision and why; do not restate the inputs, narrate what you "
       "are about to do, or explain the output format."))

(defn- reasoning-field-of [desc]
  ;; brainyard's CoT augments the schema with this description too
  [:reasoning {:desc desc} [:string {:description desc}]])

(def reasoning-field (reasoning-field-of reasoning-field-desc))

(defn field
  "A field as `[k props form]`: `[k form]` is a field with no properties."
  [[k a b]]
  (if (map? a) [k a b] [k {} a]))

(defn- label
  "The `(type)` the prompt's field list shows: what a model needs to know to
  write the value, not the schema itself."
  [form]
  (let [s (m/deref-all (schema/schema form))]
    (case (m/type s)
      (:string :enum :re) "string"
      :boolean "bool"
      (:vector :sequential :set :tuple) "list"
      (:map :map-of) "object"
      (:int) "int"
      (:double) "number"
      "string")))

(defn- indexed-fields [fields]
  (str/join "\n" (map-indexed (fn [i f]
                                (let [[k {:keys [desc]} form] (field f)]
                                  (str (inc i) ". `" (name k) "` (" (label form) ")"
                                       (when desc (str ": " desc)))))
                              fields)))

(defn output-json-schema
  "The reply's JSON Schema, written from the output fields' malli schemas: the
  same text in the prompt and at the provider, and never apart from the check
  a reply is held to."
  [outputs]
  (let [fs (map field outputs)]
    {:type "object"
     :properties (into {} (map (fn [[k _ form]] [(name k) (schema/json-schema form)])) fs)
     :required (mapv (comp name first) fs)
     :additionalProperties false}))

(defn strict-eligible?
  "True when a JSON Schema meets OpenAI's strict structured-output rules: every
  object sets `additionalProperties false` and lists EVERY one of its
  `properties` in `required` — strict mode forbids an optional property.
  Recurses through `properties`, `items`, `anyOf`/`allOf` and
  `definitions`/`defs`. brainyard's `clj-llm.core.schema/strict-eligible?`.

  It exists to decide a flag, not to reject a schema: a schema that fails here
  is still sent, with `strict:false`, because OpenAI 400s on a strict request
  whose schema has an optional property. `react/think-act` is the case in
  point — `args` is an open map, since the arguments belong to whichever
  tool is being called.

  Property and `required` names may be strings or keywords in this codebase
  (predictor specs write strings, `output-json-schema` writes strings, a
  hand-built schema may write keywords), so both are normalised through
  `name`."
  [schema]
  (letfn [(names [xs] (into #{} (map name) (or xs [])))
          (ok? [s]
            (cond
              (not (map? s)) true
              (= "object" (:type s))
              (let [req (names (:required s))]
                (and (false? (:additionalProperties s))
                     (every? req (names (keys (:properties s))))
                     (every? ok? (vals (:properties s)))))
              (map? (:items s)) (ok? (:items s))
              (:anyOf s) (every? ok? (:anyOf s))
              (:allOf s) (every? ok? (:allOf s))
              :else true))]
    (boolean
     (and (ok? schema)
          (every? ok? (vals (or (:definitions schema) (:defs schema) {})))))))

(defn- skeleton [schema]
  (case (:type schema)
    "object" (if-let [props (:properties schema)]
               (into {} (map (fn [[k v]] [k (skeleton v)])) props)
               {"<key>" "<value>"})
    "array" [(skeleton (:items schema))]
    "boolean" "<boolean>"
    "integer" "<integer>"
    "number" "<number>"
    "<string>"))

(defn json-schema-instruction [schema]
  (str "## Response format\n"
       "Reply with a single JSON object that IS your answer. The JSON Schema below "
       "describes that object's shape: the top level of your reply is the object "
       "itself, never the schema.\n\n"
       "JSON Schema:\n" (json/write-str schema) "\n\n"
       "Shape of your reply — each <placeholder> stands for a value of that type "
       "(numbers and booleans unquoted):\n"
       (json/write-str (skeleton schema)) "\n\n"
       "A field you have nothing for stays empty: an empty string is written \"\" "
       "(not \"\\\"\\\"\"), an empty array []. "
       "Output only the JSON object: no text before or after it, no markdown fences. "
       "Use exactly the field names shown."))

(defn cot-outputs
  "The outputs as the model is asked for them: `reasoning` first unless the
  signature is a plain predict (:chain-of-thought? false), described by the
  signature's own `:reasoning-desc` when it has one (byj-gly.280.1) — the
  reasoning of a judge and the thought a person watches are not written for
  the same reader."
  [signature]
  (if (false? (:chain-of-thought? signature))
    (vec (:outputs signature))
    (into [(if-let [d (not-empty (:reasoning-desc signature))] (reasoning-field-of d) reasoning-field)]
          (:outputs signature))))

(defn- input-line [k v] (str (name k) ": " (str v)))

(defn render-demos
  "Demonstrations as one system-message part, or nil — brainyard's
  prompt/render-demos. Inputs render with the same `name: value` lines, in the
  same order, as the live user message; the output JSON puts `reasoning` first
  (when chain-of-thought) then the declared outputs, dropping any key the
  schema would reject."
  [signature demos]
  (when (seq demos)
    (let [cot? (not (false? (:chain-of-thought? signature)))]
      (str "Here are examples of inputs and the JSON object to respond with:\n\n"
           (str/join
            "\n\n"
            (map-indexed
             (fn [i {:keys [inputs outputs reasoning]}]
               (let [pairs (cond-> []
                             (and cot? reasoning) (conj ["reasoning" reasoning])
                             true (into (for [[k] (:outputs signature)
                                              :when (contains? outputs k)]
                                          [(name k) (get outputs k)])))]
                 (str "Example " (inc i) "\n"
                      "Input:\n"
                      (str/join "\n" (for [[k] (:inputs signature)
                                           :when (contains? inputs k)]
                                       (input-line k (get inputs k))))
                      "\nOutput:\n"
                      (json/write-str (apply array-map (mapcat identity pairs))))))
             demos))))))

(defn system-parts
  "The system message as ordered `[[label text] ...]`: the signature preamble,
  then one part per context block. The parts ARE the prompt-cache zones
  (RFC-015) — carried to the provider rather than located again inside the
  joined string, which brainyard flags as fragile."
  ([signature context-blocks] (system-parts signature context-blocks nil))
  ([signature context-blocks demos]
   (let [outputs (cot-outputs signature)
         demo-text (render-demos signature demos)
         preamble (str/join "\n\n"
                            (concat
                             (when (seq (:inputs signature))
                               [(str "Your input fields are:\n" (indexed-fields (:inputs signature)))])
                             [(str "Your output fields are:\n" (indexed-fields outputs))
                              (json-schema-instruction (output-json-schema outputs))
                              (if (false? (:chain-of-thought? signature))
                                "Respond with a JSON object containing the output fields."
                                "Respond with a JSON object containing all output fields.")]
                             (when demo-text [demo-text])
                             [(str "In adhering to this structure, your objective is:\n" (:instructions signature))]))]
     (into [["preamble" preamble]]
           (for [[title text] context-blocks
                 :when (not (str/blank? text))]
             [title (str "## " title "\n" text)])))))

(defn system-message
  "The system message; `context-blocks` is [[title text] ...] appended as
  `## title` sections (brainyard's :stable-keys)."
  ([signature context-blocks] (system-message signature context-blocks nil))
  ([signature context-blocks demos]
   (str/join "\n\n" (map second (system-parts signature context-blocks demos)))))

(defn user-message [signature inputs]
  (str (str/join "\n" (for [[k] (:inputs signature)
                            :when (contains? inputs k)]
                        (input-line k (get inputs k))))
       "\n\nRespond with a JSON object containing the output fields, starting with the field "
       (str/join ", then " (map #(str "`" (name (first %)) "`") (cot-outputs signature)))
       "."))

(defn build-messages
  "opts: :demos — [{:inputs :outputs :reasoning?}] rendered into the system message."
  ([signature inputs context-blocks] (build-messages signature inputs context-blocks nil))
  ([signature inputs context-blocks {:keys [demos]}]
   (let [parts (system-parts signature context-blocks demos)]
     [{:role "system" :content (str/join "\n\n" (map second parts)) :parts parts}
      {:role "user" :content (user-message signature inputs)}])))

;; --- output repair -------------------------------------------------------------

(defn- default-for
  "What a field the reply left out is taken to be: the empty value of its
  type. A field whose schema has no empty value — an enum — has none, and a
  reply without it does not match. A nullable one (`[:maybe …]`) is null: a
  model answering `null` there said what the schema allows, and was refused
  as a field left out (byj-g5n.10)."
  [form]
  (case (m/type (m/deref-all (schema/schema form)))
    :string "" :boolean false (:vector :sequential) []
    :maybe nil
    ::none))

(defn- escaped-twice
  "A string field whose line breaks the model escaped twice — `\\\\n` in its
  JSON, so what decodes is a backslash and an `n` — with them decoded. haiku
  answered in one line of `…:\\n\\n1. Check…`, drawn as it came (byj-gly.194).
  Only a string with no real line break and a paragraph's worth of escaped ones
  — `\\n\\n`, or three — is taken for this: an answer that mentions `\"\\n\"` in
  code is left as it is."
  [s]
  (let [n (count (re-seq #"\\n" s))]
    (if (and (pos? n) (not (str/includes? s "\n"))
             (or (str/includes? s "\\n\\n") (>= n 3)))
      (-> s (str/replace "\\n" "\n") (str/replace "\\t" "\t") (str/replace "\\\"" "\""))
      s)))

(defn- json-object [v]
  (if (and (string? v) (str/starts-with? (str/trim v) "{"))
    (let [r (try (json/read-str v) (catch Throwable _ nil))] (if (map? r) r v))
    v))

(def ^:private output-transformer
  "How a reply's values are read before they are checked — what the reply
  repair always did, now per schema type at any depth:
  - a string field given anything else takes its JSON text, and one whose line
    breaks were escaped twice has them decoded;
  - a boolean from `\"true\"`/`\"false\"`, any case;
  - a string enum's member whatever its case — `\"hallucinated\"` is
    `\"HALLUCINATED\"` (byj-gly.216; the answer gate upper-cased it before);
  - a list given as JSON text is parsed, and a lone object is a list of one;
  - an object given as JSON text is parsed — a tool call's args, stringified,
    as a model writing a native tool call sends them."
  (mt/transformer
   {:name :reply
    :decoders {:string (fn [v] (cond (nil? v) v (string? v) (escaped-twice v) :else (json/write-str v)))
               ;; the spellings the tool-arg reader takes too (byj-gly.230)
               :boolean (fn [v] (if (string? v)
                                  (case (str/lower-case (str/trim v)) ("true" "yes" "1") true ("false" "no" "0") false v)
                                  v))
               :vector (fn [v] (cond (sequential? v) (vec v)
                                     (and (string? v) (str/starts-with? (str/trim v) "["))
                                     (let [r (try (json/read-str v) (catch Throwable _ nil))]
                                       (if (sequential? r) (vec r) v))
                                     (map? v) [v]
                                     :else v))
               :map-of (fn [v] (json-object v))
               :map (fn [v] (json-object v))
               :enum {:compile
                      (fn [schema _]
                        (let [members (m/children schema)]
                          (when (every? string? members)
                            (let [by-case (into {} (map (juxt str/lower-case identity)) members)]
                              (fn [v] (if (string? v) (get by-case (str/lower-case (str/trim v)) v) v))))))}}}))

(defn- call-keys
  "A tool call written with brainyard's keys, `tool-name`/`tool-args`, as the
  `name`/`args` the schema asks for — a model trained on the other protocol, or
  reading older history, still gets its call made."
  [c]
  (if-not (map? c)
    c
    (let [c (if (and (not (contains? c :name)) (contains? c :tool-name))
              (-> c (dissoc :tool-name :tool-args) (assoc :name (:tool-name c) :args (:tool-args c)))
              c)
          ;; the other spellings of a call's arguments — OpenAI's `arguments`
          ;; above all, which `name` invites (byj-gly.230)
          args (if (contains? c :args) (:args c) (some #(get c %) [:arguments :parameters :input]))]
      ;; a call is its name and its args: an `id` or the like beside them made
      ;; the whole reply malformed against the closed schema, so the rest is
      ;; dropped before the check (the schema sent out stays closed)
      ;; `"args": null` is a call with no arguments (byj-gly.215)
      {:name (:name c) :args (if (nil? args) {} args)})))

(defn repair-tool-calls
  "Tool calls written in any shape a reply may use — brainyard's
  `tool-name`/`tool-args` included — as `{:name :args}`: for what is read back
  besides a reply, such as a recorded dataset's labels or a params file's
  demos, written before calls were `name`/`args` (byj-gly.233)."
  [calls]
  (if (sequential? calls) (mapv call-keys calls) calls))

(defn lift-flattened-tool-call
  "A model that splices a single tool call's keys to the top level
  (`name`/`args`, or `tool-name`/`tool-args`) gets them lifted back into
  `tool-calls`, as brainyard's `schema/lift-flattened-collection` does; and a
  call written with `tool-name`/`tool-args` is read as `name`/`args`."
  [out]
  (let [n (or (:name out) (:tool-name out))
        out (if (and (empty? (:tool-calls out)) (string? n) (not (str/blank? n)))
              (-> out
                  (dissoc :name :args :tool-name :tool-args)
                  (assoc :tool-calls [{:name n :args (or (:args out) (:tool-args out) {})}]))
              out)]
    (cond-> out
      (sequential? (:tool-calls out)) (update :tool-calls #(mapv call-keys %)))))

(defn output-schema
  "The malli schema a reply is held to: every output field, reasoning first
  for a chain-of-thought signature."
  [signature]
  (into [:map] (map (fn [f] (let [[k _ form] (field f)] [k form]))) (cot-outputs signature)))

(defn normalize-outputs
  "A parsed reply as the signature's outputs: repaired, defaulted, decoded and
  checked. Only declared keys survive. A reply that still does not match its
  schema throws `:kind :malformed` with `:problems` — the loop tells the model
  what was wrong, as for a reply that was not JSON (RFC-003 repair)."
  [signature parsed]
  (let [calls? (some #(= :tool-calls (first %)) (:outputs signature))
        ;; only a signature with tool calls: another may have a :name output
        ;; of its own
        parsed (cond-> parsed calls? lift-flattened-tool-call)
        out (into {}
                  (for [f (cot-outputs signature)
                        :let [[k _ form] (field f)
                              ;; a null is the field left out: `"answer": null` is how
                              ;; a model says it is not answering (byj-gly.215)
                              v (if (some? (get parsed k)) (get parsed k) (default-for form))]
                        :when (not= ::none v)]
                    [k (m/decode (schema/schema form) v output-transformer)]))
        ;; again once decoded: calls sent as JSON text were a string before
        out (cond-> out calls? lift-flattened-tool-call)]
    (if-let [ps (schema/explain-problems (output-schema signature) out)]
      (throw (ex-info (str "the reply does not match its schema: " (str/join "; " ps))
                      {:kind :malformed :problems ps}))
      out)))
