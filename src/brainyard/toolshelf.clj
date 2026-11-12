;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.toolshelf
  "Every tool a session can call, as a person browses them — `/tools`
  (byj-gly.273.1). brainyard has no browser: its tools are found by name
  completion after `/` and `:`, and by the `list-tools` / `get-tool-info` a
  model calls (divergence `tools-are-browsed`).

  A row is what the picker lists and its info pane shows: the id and family
  (`discovery/tool-family`), what kind of tool it is and where it came from,
  its description, its arguments (`schema/inputs`) and result
  (`schema/output-text`), its effects, what the permission gates would do with
  a call of it now (`permission/tool-verdict`), and how to call it both ways —
  `/<tool>` asks the model, `:<tool>` calls it directly (byj-gly.273.2, .3).

  Mechanism only: what is on screen, and how, is the renderer's."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [brainyard.discovery :as discovery]
            [brainyard.permission :as permission]
            [brainyard.schema :as schema]
            [brainyard.toolbox :as toolbox]))

(defn- source
  "Where a tool came from, in a person's words."
  [t]
  (cond
    (:mcp t) (str "MCP server " (get-in t [:mcp :server]))
    (= :composite (:kind t)) (str "user tool" (some->> (:file t) (str " · ")))
    (= :command (:kind t)) "a command defined in code"
    (= :skill (:kind t)) "a skill (SKILL.md)"
    (= :agent (:kind t)) "an agent"
    :else "built in"))

(defn- placeholder
  "An argument as a usage line writes it: `<pattern>`."
  [{:keys [name]}]
  (str "<" (clojure.core/name name) ">"))

(defn usage
  "How to call a tool both ways, from its arguments: through the model,
  `/grep <what to find>`, and directly, `:grep :pattern \"…\" [:path \"…\"]` —
  brainyard's slash and colon forms. A required argument is written plainly, an
  optional one in brackets."
  [{:keys [id inputs]}]
  (let [req (remove :optional? inputs)
        opt (filter :optional? inputs)
        kv (fn [{:keys [name type] :as in}]
             (str ":" (clojure.core/name name) " "
                  (case type "string" "\"…\"" "integer" "N" "number" "N" "boolean" "true" "…")))]
    {:model (str/trim (str "/" id " " (str/join " " (map placeholder (or (seq req) (take 1 opt))))))
     :direct (str/trim (str ":" id " " (str/join " " (concat (map kv req)
                                                             (map #(str "[" (kv %) "]") opt)))))}))

(defn row
  "`tool` as the picker lists it."
  [tool ctx]
  (let [id (:name tool)
        r {:id id
           :family (discovery/tool-family id)
           :type (discovery/tool-type tool)
           :source (source tool)
           :description (str (:description tool))
           :inputs (schema/inputs tool)
           :outputs (some-> (:output-schema tool) schema/output-text)
           ;; `fs/read`, not `read`: the namespace is half the effect
           :effects (vec (sort (map #(subs (str %) 1) (or (some-> (:tools ctx) (toolbox/effects id)) #{}))))
           :gate (permission/tool-verdict id ctx)}]
    (assoc r :usage (usage r))))

(defn rows
  "Every tool in the session's registry, by family then id."
  [ctx]
  (->> (vals @(:tools ctx))
       (map #(row % ctx))
       (sort-by (juxt :family :id))
       vec))

(defn matches
  "`rows` a filter `q` keeps, those whose id holds it before those whose
  description alone does — brainyard dropped description matching from its
  menu for dragging unrelated commands in, so a name match leads. Case is
  ignored; a blank `q` keeps every row as it is."
  [rows q]
  (let [q (str/lower-case (str/trim (str q)))]
    (if (str/blank? q)
      rows
      (let [in? #(str/includes? (str/lower-case (str %1)) q)]
        (vec (concat (filter #(in? (:id %)) rows)
                     (filter #(and (not (in? (:id %))) (in? (:description %))) rows)))))))

(defn spec-lines
  "A row as lines of text: what the info pane shows, and what `/tools NAME`
  prints where there is no pane."
  [{:keys [id type source description inputs outputs effects gate usage]}]
  (concat
   [(str id "  (" type " · " source ")")]
   (when (seq description) (cons "" (str/split-lines description)))
   [""]
   (if (seq inputs)
     (cons "Arguments:"
           (for [{:keys [name type optional? desc]} inputs]
             (str "  " (clojure.core/name name) " " type (when optional? " (optional)")
                  (when desc (str " — " desc)))))
     ["Arguments: none"])
   (when outputs ["" (str "Outputs: " outputs)])
   [""
    (str "Effects: " (if (seq effects) (str/join ", " effects) "none"))
    (str "Permission: " (name (:verdict gate)) " — " (:why gate)
         (when (not= :deny (:verdict gate)) "; paths and commands are checked at the call"))
    ""
    (str "Ask the model:   " (:model usage))
    (str "Call directly:   " (:direct usage))]))

;; --- a direct call: `:<tool> …` (byj-gly.273.3) ------------------------------------

(defn tokenize
  "A direct call's argument text as tokens — brainyard's `tokenize-args`:
  split on whitespace; double quotes keep spaces and are dropped; a quote left
  open takes the rest of the line."
  [s]
  (loop [cs (seq (str s)), cur nil, quoted? false, out []]
    (if-let [c (first cs)]
      (cond
        (= c \") (recur (rest cs) (or cur "") (not quoted?) out)
        (and (not quoted?) (Character/isWhitespace c)) (recur (rest cs) nil false (cond-> out cur (conj cur)))
        :else (recur (rest cs) (str cur c) quoted? out))
      (cond-> out cur (conj cur)))))

(defn- kwargs
  "Tokens as brainyard's `tokens->kwargs` reads them: key, value, key, value —
  a key bare or `:`-prefixed; a key with no value is \"\"."
  [tokens]
  (into {} (for [[k v] (partition-all 2 tokens)]
             [(keyword (str/replace (str k) #"^:" "")) (or v "")])))

(defn- coerce
  "A string value as the argument's type wants it — brainyard's
  `coerce-kwargs`: an integer, a number, a boolean, or an array (EDN when it
  reads as a vector, else split on commas). Left a string when it does not read,
  so the check says what is wrong with it."
  [type v]
  (if-not (string? v)
    v
    (case type
      "integer" (or (parse-long (str/trim v)) v)
      "number" (or (parse-double (str/trim v)) v)
      "boolean" (case (str/lower-case (str/trim v)) "true" true "false" false v)
      "array" (let [x (when (str/starts-with? (str/trim v) "[")
                        (try (edn/read-string v) (catch Throwable _ nil)))]
                (if (vector? x) x (mapv str/trim (str/split v #","))))
      v)))

(defn parse-direct
  "A `:<tool> …` line as `{:id :args}`: `:grep :pattern foo :path \"src dir\"`
  (brainyard's kwargs, each value coerced by the argument's type), or
  `:grep {:pattern \"foo\"}` (an EDN map, for what kwargs cannot say — the
  port's own). `{:id :error}` when the map does not read. `inputs` are the
  tool's (`schema/inputs`); nil when the line is not a direct call at all."
  [line inputs]
  (when-let [[_ id rest] (re-matches #"(?s):([A-Za-z_][\w$.\-]*)(?:\s+(.*))?" (str/trim (str line)))]
    (let [rest (str/trim (str rest))
          types (into {} (map (juxt :name :type)) inputs)
          parsed (if (str/starts-with? rest "{")
                   (let [m (try (edn/read-string rest) (catch Throwable e {::bad (ex-message e)}))]
                     (if (and (map? m) (not (::bad m)))
                       {:id id :args (into {} (map (fn [[k v]] [(keyword (name k)) v])) m)}
                       {:id id :error (str "the arguments do not read as an EDN map: " (or (::bad m) (pr-str m)))}))
                   {:id id :args (into {} (map (fn [[k v]] [k (coerce (types k) v)])) (kwargs (tokenize rest)))})
          ;; a key the tool does not take is refused, not passed through an open
          ;; map: `:list-dir src` read `src` as a key and listed `.` — a
          ;; misplaced value or a typo is said, with what the tool does take
          unknown (when (and (:args parsed) (seq inputs)) (remove (set (keys types)) (keys (:args parsed))))]
      (if (seq unknown)
        {:id id :error (str "takes no " (str/join ", " (map #(str ":" (name %)) unknown))
                            " — it takes " (str/join ", " (map #(str ":" (name (:name %))) inputs))
                            " (as :key value pairs, or one EDN map)")}
        parsed))))

(defn digest
  "What the next turn is told about a direct call — brainyard's console
  activity: the line as typed, then `→ ` and the result (`✗ ` when it failed),
  whitespace run together and cut at `chars`."
  [line result error? chars]
  (let [flat #(str/trim (str/replace (str %) #"\s+" " "))
        text (str (flat line) (if error? " ✗ " " → ") (flat (if (string? result) result (pr-str result))))]
    (if (> (count text) chars) (str (subs text 0 (max 0 (dec chars))) "…") text)))
