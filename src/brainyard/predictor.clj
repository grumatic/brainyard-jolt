;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.predictor
  "Declarative predictors — brainyard's `clj-llm.core.predictor` (the DSPy
  `Predict` module as a value) and `agent.common.user-predictors` (a predictor
  defined as a file), on jolt.

  A signature says WHAT a transformation is; a predictor is a NAMED use of one.
  Here both are pure data. A predictor is an EDN spec:

    {:id           \"react/think-act\"        ; stable: params-file path, trace key
     :strategy     :cot                      ; :cot (reasoning first) | :predict
     :description  \"...\"
     :instructions \"...\" | {:resource \"path\"} | {:file \"path\"}
     :reasoning    {:desc \"...\" | {:resource \"path\"} | {:file \"path\"}}  ; optional, :cot
     :inputs  [[:question {:desc \"...\"} :string] ...]     ; malli map entries, in order
     :outputs [[:answer {:desc \"...\"} :string] ...]
     :params  {...}}                         ; lowest-precedence params layer

  Built-in specs are resources under brainyard/predictors/<id>.edn. A user may
  define more as files under <predictor-root>/user/<name>.edn; those ids must
  live in the `user/` namespace, and a built-in id can never be redefined from
  disk — changing a built-in's behaviour is a PARAMS file, as in brainyard.

  PARAMS are data too, resolved per call, highest precedence first:

    dynamic (:params {id params})  >  <params-root>/<id>.edn  >  spec :params

  The first layer holding a record for the id wins WHOLE (no deep merge), so a
  params file always means exactly what it says. A params record may carry:

    :instructions  replaces the spec's instructions
    :field-descs   {field desc} overrides for input/output descriptions, and
                   for :reasoning, what the CoT field asks for
    :demos         [{:inputs {…} :outputs {…} :reasoning \"…\"?}] — rendered
                   into the system message
    :max-demo-value-chars, :version, :compiled-by
    :lm            "provider/model" — which model this predictor runs on
    :tier          :light | :standard | :deep, resolved through the config's
                   tier table (inert until that table is configured)

  With no params record the prompt is byte-identical to the spec alone. A model
  choice resolves explicit override > params :lm > :tier > the ambient LM, and
  anything unresolvable degrades to the next step rather than failing the call
  (`resolve-lm`).

  Every call emits :predictor/call through :on-event — the trace an optimizer
  would turn into demos."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.json :as json]
            [brainyard.events :as events]
            [brainyard.llm :as llm]
            [brainyard.schema :as schema]
            [brainyard.signature :as sig]))

;; --- ids ---------------------------------------------------------------------

(def ^:private id-re
  "Ids double as relative file paths, so no empty, `.` or `..` segment."
  #"[A-Za-z0-9_-][A-Za-z0-9_.-]*(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*")

(defn valid-id? [id]
  (and (string? id)
       (boolean (re-matches id-re id))
       (not-any? #{"." ".."} (str/split id #"/"))))

(defn user-id? [id] (str/starts-with? (str id) "user/"))

;; --- spec validation ---------------------------------------------------------
;;
;; A spec file and a params file are data a person writes, so each is checked
;; against a malli schema and every problem is named at once (byj-gly.203).

(def tiers
  "brainyard's work tiers, cheapest first — one definition, shared with the
  subagent dispatch that clamps them (RFC-018)."
  llm/tiers)

(defn- compiles?
  "Does `form` compile as a malli schema against the shared registry?"
  [form]
  (try (schema/schema form) true (catch Throwable _ false)))

(defn- field-shape? [f]
  (and (vector? f) (keyword? (first f))
       (or (and (= 3 (count f)) (map? (second f)))
           (and (= 2 (count f)) (not (map? (second f)))))))

(def ^:private field-schema
  "One field: a malli map entry, `[k {:desc …} schema]` or `[k schema]`."
  [:and
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (if (and (vector? value) (map? (second value)) (contains? (second value) :type))
                       "uses the retired {:type … :schema …} form — write [keyword {:desc …} schema]"
                       "must be [keyword {:desc …} schema] — a malli map entry"))}
    field-shape?]
   ;; only a well-shaped field is compiled: one message for a bad one, not two
   [:fn {:error/message "has a schema malli cannot compile"}
    #(or (not (field-shape? %)) (compiles? (nth (sig/field %) 2)))]])

(def ^:private fields-schema
  [:and [:vector field-schema]
   [:fn {:error/message "has a field declared twice"}
    #(apply distinct? (cons ::none (map first %)))]])

(def ^:private params-schema
  [:map {:closed true}
   [:instructions {:optional true} :string]
   [:field-descs {:optional true} [:map-of :keyword :string]]
   [:demos {:optional true}
    [:sequential [:map [:inputs [:map {:error/message "should be a map of field → value"}]]
                  [:outputs [:map {:error/message "should be a map of field → value"}]]
                  [:reasoning {:optional true} :string]]]]
   [:max-demo-value-chars {:optional true} pos-int?]
   [:version {:optional true} :any]
   [:compiled-by {:optional true} :any]
   ;; which model to run on (RFC-005): a "provider/model" label, or a work
   ;; tier resolved through the config's table
   [:lm {:optional true} :string]
   [:tier {:optional true} (into [:enum] tiers)]])

(def ^:private spec-schema
  [:map {:closed true}
   [:id [:and :string [:fn {:error/message "is not a valid id"} valid-id?]]]
   [:strategy {:optional true} [:enum :cot :predict]]
   [:description {:optional true} :string]
   [:instructions [:or :string [:map {:closed true} [:resource :string]] [:map {:closed true} [:file :string]]]]
   ;; what the CoT `reasoning` field asks for, when not brainyard's default
   ;; (byj-gly.280.1): text, or a resource or file as :instructions is
   [:reasoning {:optional true}
    [:map {:closed true}
     [:desc [:or :string [:map {:closed true} [:resource :string]] [:map {:closed true} [:file :string]]]]]]
   [:inputs fields-schema]
   [:outputs [:and fields-schema
              [:fn {:error/message "declares :reasoning, which is reserved — :strategy :cot adds it"}
               #(not-any? #{:reasoning} (map first %))]]]
   [:params {:optional true} params-schema]
   [:tier {:optional true} (into [:enum] tiers)]])

(defn validate-params
  "Nil when a params record is well formed, else a vector of problems."
  [params]
  (schema/explain-problems params-schema params))

(defn validate-spec
  "Nil when a predictor spec is well formed, else a vector of problems."
  [spec]
  (if (map? spec)
    (schema/explain-problems spec-schema spec)
    ["a predictor spec must be a map"]))

;; --- compile -------------------------------------------------------------------

(defn- resolve-text [v base-dir]
  (cond
    (string? v) v
    (:resource v) (or (some-> (io/resource (:resource v)) slurp)
                      (throw (ex-info (str "instructions resource not found: " (:resource v)) {})))
    (:file v) (let [f (io/file (:file v))
                    f (if (or (.isAbsolute f) (nil? base-dir)) f (io/file base-dir (:file v)))]
                (if (.isFile f)
                  (slurp f)
                  (throw (ex-info (str "instructions file not found: " (.getPath f)) {}))))))

(defn compile-spec
  "The predictor VALUE for a spec. Throws on an invalid spec with every problem
  listed — the dry run that costs nothing."
  ([spec] (compile-spec spec {}))
  ([spec {:keys [source base-dir]}]
   (when-let [problems (validate-spec spec)]
     (throw (ex-info (str "invalid predictor " (pr-str (:id spec)) ": " (str/join "; " problems))
                     {:id (:id spec) :problems problems})))
   (let [strategy (:strategy spec :cot)]
     (cond-> {:predictor/id (:id spec)
              :strategy strategy
              :source (or source :inline)
              :signature {:instructions (resolve-text (:instructions spec) base-dir)
                          :inputs (:inputs spec)
                          :outputs (:outputs spec)
                          :chain-of-thought? (= :cot strategy)}}
       (get-in spec [:reasoning :desc])
       (assoc-in [:signature :reasoning-desc] (str/trim (resolve-text (get-in spec [:reasoning :desc]) base-dir)))
       (:description spec) (assoc :description (:description spec))
       ;; a predictor may declare the tier its work belongs to; params may
       ;; override it, and the table decides what that costs (RFC-005)
       (:tier spec) (assoc :tier (:tier spec))
       (:params spec) (assoc :default-params (:params spec))))))

;; --- loading ---------------------------------------------------------------------

(def bundled-root "brainyard/predictors")

(defn- bundled-resource [id] (io/resource (str bundled-root "/" id ".edn")))

(defn- read-edn-file [f what]
  (try (edn/read-string (slurp f))
       (catch Throwable e
         (throw (ex-info (str "unreadable " what " " f ": " (ex-message e)) {:file (str f)})))))

(defn default-roots
  "<project-dir>/.brainyard/<sub> then ~/.brainyard/<sub> — brainyard's
  project-then-user scopes."
  [project-dir sub]
  (vec (distinct [(.getAbsolutePath (io/file (or project-dir ".") ".brainyard" sub))
                  (.getAbsolutePath (io/file (System/getenv "HOME") ".brainyard" sub))])))

(def bundled-ids
  "Ids shipped as resources. Enumerated rather than scanned: a binary has no
  directory to walk (RFC-021 `program$list`)."
  ["react/think-act" "react/evaluate-answer" "todo/from-plan" "todo/eval"])

(defn list-ids
  "Every predictor id reachable here — bundled, plus `user/<name>` files in
  `roots`, sorted."
  [roots]
  (->> (for [root roots
             :let [d (io/file root "user")]
             :when (.isDirectory d)
             f (.listFiles d)
             :when (str/ends-with? (.getName f) ".edn")]
         (str "user/" (subs (.getName f) 0 (- (count (.getName f)) 4))))
       (into (set bundled-ids))
       sort vec))

(defn load-predictor
  "Resolve and compile a predictor by id. A built-in resource always wins; a
  file under a predictor root is consulted only for `user/` ids."
  [id predictor-roots]
  (when-not (valid-id? id)
    (throw (ex-info (str "invalid predictor id " (pr-str id)) {:id id})))
  (if-let [r (bundled-resource id)]
    (let [spec (edn/read-string (slurp r))]
      (when (not= id (:id spec))
        (throw (ex-info (str "predictor resource " id " declares :id " (pr-str (:id spec))) {:id id})))
      (compile-spec spec {:source :builtin}))
    (if-not (user-id? id)
      (throw (ex-info (str "no built-in predictor " id
                           " (predictors defined on disk must use the user/ namespace)")
                      {:id id}))
      (if-let [f (some #(let [f (io/file % (str id ".edn"))] (when (.isFile f) f)) predictor-roots)]
        (let [spec (read-edn-file f "predictor")]
          (when (not= id (:id spec))
            (throw (ex-info (str "predictor file " f " declares :id " (pr-str (:id spec))
                                 ", expected " id) {:id id :file (str f)})))
          (compile-spec spec {:source (.getPath f) :base-dir (.getParentFile f)}))
        (throw (ex-info (str "predictor " id " not found under " (str/join ", " predictor-roots))
                        {:id id}))))))

;; --- params ------------------------------------------------------------------------

(defn resolve-params
  "{:params p :source s} — s is :dynamic, a file path or :default — or {} when
  no layer has a record. An invalid params file is skipped, reported under
  :skipped, never fatal: params are a hint."
  [{id :predictor/id :keys [default-params]} {:keys [params params-roots]}]
  (or (when (contains? params id)
        (when-let [problems (validate-params (get params id))]
          (throw (ex-info (str "invalid dynamic params for " id ": " (str/join "; " problems))
                          {:id id :problems problems})))
        {:params (get params id) :source :dynamic})
      (loop [[root & more] params-roots, skipped []]
        (if (nil? root)
          (cond-> (if default-params {:params default-params :source :default} {})
            (seq skipped) (assoc :skipped skipped))
          (let [f (io/file root (str id ".edn"))]
            (if-not (.isFile f)
              (recur more skipped)
              (let [p (try (edn/read-string (slurp f)) (catch Throwable e {::unreadable (ex-message e)}))
                    problems (if (::unreadable p) [(::unreadable p)] (validate-params p))]
                (if problems
                  (recur more (conj skipped {:file (.getPath f) :problems problems}))
                  (cond-> {:params p :source (.getPath f)}
                    (seq skipped) (assoc :skipped skipped))))))))))

(defn- with-field-descs [fields descs]
  (mapv (fn [f] (let [[k props form] (sig/field f)]
                  [k (if-let [d (get descs k)] (assoc props :desc d) props) form]))
        fields))

(defn apply-params
  "The signature a predictor runs with under params."
  [signature params]
  (cond-> signature
    (:instructions params) (assoc :instructions (:instructions params))
    (seq (:field-descs params)) (-> (update :inputs with-field-descs (:field-descs params))
                                    (update :outputs with-field-descs (:field-descs params)))
    ;; `reasoning` is no declared output — :strategy :cot adds it — so a
    ;; params record reaches it here, or it would be silently dropped
    (get-in params [:field-descs :reasoning]) (assoc :reasoning-desc (get-in params [:field-descs :reasoning]))))

(def default-max-demo-value-chars 2000)

(defn prepare-demos
  "Demos from params with long input strings truncated: a demo harvested from
  a real trace teaches the shape of a call, not its bulk."
  [params]
  (let [n (or (:max-demo-value-chars params) default-max-demo-value-chars)
        cut #(if (and (string? %) (> (count %) n)) (str (subs % 0 n) "…[truncated]") %)]
    (mapv #(cond-> (update % :inputs update-vals cut)
             ;; a demo compiled before calls were name/args teaches the shape
             ;; the schema now asks for, not the one beside it (byj-gly.233)
             (get-in % [:outputs :tool-calls]) (update-in [:outputs :tool-calls] sig/repair-tool-calls))
          (:demos params))))

;; --- run -------------------------------------------------------------------------------

(defn resolve-lm
  "Which model this call runs on — brainyard's `resolve-lm`:

      explicit override  >  params :lm  >  params or spec :tier  >  the ambient LM

  brainyard's call site usually passes nothing, so its order reads
  `lm-config > :lm > :tier > the default LM`. Here every call already carries
  the session's LM, so the first step is an EXPLICIT override — a tree node
  pinning a model — and the ambient LM moves to the end, where brainyard's
  default LM sits. Otherwise a params file could never choose anything.

  A label or tier that cannot be resolved degrades to the next step rather than
  failing the call: params are a hint, and a hint that cannot be honoured must
  not cost a turn.

  `:light` is the port's own step (divergence `side-tasks-run-light`): with no
  table entry it resolves to the session provider's light model (`llm/light-lm`),
  else the ambient LM, unreported — so side tasks such as the recap stop paying
  for the session's model by default. `:standard` and `:deep` are brainyard's,
  inert until the table names them."
  [{id :predictor/id :as p} params {:keys [lm lm-override lm-tiers on-event env-fn]}]
  ;; credentials for a label or a tier come from THIS run's environment, not
  ;; the bare process one: the CLI may have found the key in a `.env`, and a
  ;; params file that names a model it cannot get a key for is a params file
  ;; that silently does nothing (`byj-gly.91`)
  (let [env (or env-fn #(System/getenv %))
        emit (fn [e d] (events/emit! on-event e d))
        from-label (fn [label]
                     (or (llm/parse-lm-label label env)
                         (do (emit :predictor/lm-unresolved {:predictor-id id :lm label}) nil)))]
    (or (cond (map? lm-override) lm-override
              (string? lm-override) (from-label lm-override))
        (when-let [label (:lm params)] (from-label label))
        (when-let [tier (or (:tier params) (:tier p))]
          (or (llm/tier-lm lm-tiers tier env)
              ;; a light side task with no table entry runs on the session
              ;; provider's light model, or on the session's own when it has
              ;; none — not a problem worth a report (byj-gly.367)
              (when (= :light (llm/coerce-tier tier))
                (or (some-> lm (llm/light-lm env)) lm))
              (do (emit :predictor/tier-unresolved {:predictor-id id :tier tier}) nil)))
        lm)))

(defn run
  "Run a predictor on inputs.

  opts:
    :lm            the ambient LM — what this call runs on unless params or an
                   override choose otherwise (`resolve-lm`)
    :lm-override   an explicit model for this call: an LM map or a
                   \"provider/model\" label. Beats params.
    :lm-tiers      {tier \"provider/model\"} — the table a params `:tier` reads
    :zones         [[title text] ...] system-prompt zones after the instructions
    :params        {id params} dynamic layer
    :params-roots  directories holding <id>.edn params files
    :on-chunk      (fn [text]) — called with each fragment as the provider
                   produces it, when the provider can stream
    :on-event      (fn [event data]); receives :predictor/call
    :dir           the directory a model that runs as a process (claude -p)
                   is started in; this process's cwd when absent

  Returns {:predictor-id :params-source :outputs :reasoning :usage}, plus
  :attempted-tool-calls when the provider saw the model call tools natively
  (llm/rejected-tool-calls) — what to do with them is the caller's. Throws
  ex-info with :kind :empty or :malformed for an unusable reply, or the LLM
  layer's error for a failed call."
  [{id :predictor/id :as p} inputs {:keys [zones on-event on-chunk] :as opts}]
  (let [{:keys [params source skipped]} (resolve-params p opts)
        lm (cond-> (resolve-lm p params opts)
             ;; the directory a process-run model starts in (byj-gly.338),
             ;; for whichever model the call resolved to
             (:dir opts) (assoc :dir (:dir opts)))
        signature (apply-params (:signature p) params)
        demos (prepare-demos params)
        messages (sig/build-messages signature inputs zones {:demos demos})
        emit (fn [e d] (events/emit! on-event e d))
        base {:predictor-id id :params-source source :params-version (:version params)
              :inputs inputs}
        t0 (System/currentTimeMillis)]
    (when (seq skipped) (emit :predictor/params-skipped {:predictor-id id :skipped skipped}))
    (emit :agent.llm/request {:messages messages :predictor-id id})
    (try
      (let [{:keys [content usage attempted-tool-calls]}
            (llm/chat lm messages
                      {:json-schema (sig/output-json-schema (sig/cot-outputs signature))
                       ;; the bus reaches the provider so the WIRE can be
                       ;; recorded — the messages here are the assembled ones,
                       ;; a layer above what actually goes out (RFC-026)
                       :on-event on-event
                       ;; text as the provider produces it, for a caller that
                       ;; wants to show it (RFC-006); the parsed reply is the same
                       :on-chunk on-chunk})
            _ (emit :agent.llm/response {:content content :usage usage :predictor-id id})
            _ (when (str/blank? content)
                (throw (ex-info "LLM returned an empty response" {:kind :empty :usage usage})))
            parsed (try (json/parse-json-response content)
                        (catch Throwable e
                          (throw (ex-info (ex-message e)
                                          (assoc (ex-data e) :kind :malformed :usage usage)))))
            out (sig/normalize-outputs signature parsed)
            result (cond-> {:predictor-id id
                            :params-source source
                            :outputs (dissoc out :reasoning)
                            :reasoning (:reasoning out)
                            ;; which model this call ACTUALLY ran on, after the
                            ;; node override and the tier table had their say —
                            ;; without it a caller cannot price the call, and a
                            ;; turn may span more than one model (RFC-025)
                            :model (:model lm)
                            :provider (:provider lm)
                            :usage usage}
                     (seq attempted-tool-calls) (assoc :attempted-tool-calls attempted-tool-calls))]
        (emit :predictor/call (assoc base
                                     :outputs (:outputs result)
                                     :reasoning (:reasoning result)
                                     :attempted-tool-calls (:attempted-tool-calls result)
                                     :usage usage
                                     :elapsed-ms (- (System/currentTimeMillis) t0)))
        result)
      (catch Throwable e
        (emit :predictor/call (assoc base :error (ex-message e)
                                     :elapsed-ms (- (System/currentTimeMillis) t0)))
        (throw e)))))
