(ns brainyard.dev.tool-lisp-spike
  "A spike for byj-gly.28 / RFC-010: can a model with NO prior knowledge of Tool
  Lisp define working composite tools through `tool$define`, knowing the
  language only from that tool's description?

  Deliberately minimal and synchronous — enough to validate and run bodies the
  way RFC-010 proposes, not the implementation.

      jolt -A:dev -m brainyard.dev.tool-lisp-spike haiku sonnet   ; live, claude CLI
      (brainyard.dev.tool-lisp-spike/self-check)                  ; offline

  Results go to target/tool-lisp-eval/: one EDN record per (model, task) and a
  summary table.

  SUPERSEDED for measurement by brainyard.dev.tool-authoring-eval, which runs
  the same tasks on the shipped brainyard.tool-lisp / brainyard.toolbox. Kept
  because RFC-010's first evidence table came from it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.tools :as tools]))

;; ============================================================================
;; The language
;; ============================================================================

(def special-forms '#{fn let if when cond do and or -> ->>})

(def forbidden
  '#{def defn defmacro loop recur eval load-string read-string atom swap! reset!
     slurp spit future pmap binding letfn try catch throw new set! var quote})

(def ops
  {'get get 'get-in get-in 'assoc assoc 'assoc-in assoc-in 'dissoc dissoc 'update update
   'merge merge 'select-keys select-keys 'keys (comp vec keys) 'vals (comp vec vals)
   'count count 'first first 'second second 'last last 'rest (comp vec rest)
   'take (comp vec take) 'drop (comp vec drop) 'conj conj 'into into 'vec vec 'set set
   'concat (comp vec concat) 'distinct (comp vec distinct) 'sort (comp vec sort)
   'sort-by (comp vec sort-by) 'reverse (comp vec reverse) 'frequencies frequencies
   'group-by group-by 'empty? empty? 'contains? contains? 'range (comp vec range)
   'map (comp vec map) 'mapv mapv 'filter (comp vec filter) 'remove (comp vec remove)
   'keep (comp vec keep) 'mapcat (comp vec mapcat) 'reduce reduce 'some some 'every? every?
   'str str 'subs subs 'format format 'pr-str pr-str 'name name 'keyword keyword
   'parse-long parse-long 'clojure.string/split str/split
   'clojure.string/split-lines str/split-lines 'clojure.string/join str/join
   'clojure.string/upper-case str/upper-case 'clojure.string/lower-case str/lower-case
   'clojure.string/trim str/trim 'clojure.string/blank? str/blank?
   'clojure.string/includes? str/includes? 'clojure.string/starts-with? str/starts-with?
   'clojure.string/ends-with? str/ends-with? 'clojure.string/replace str/replace
   're-pattern re-pattern 're-find re-find 're-seq (comp vec re-seq) 're-matches re-matches
   '+ + '- - '* * '/ / 'inc inc 'dec dec 'min min 'max max 'quot quot 'rem rem
   '= = 'not= not= '< < '> > '<= <= '>= >= 'not not 'nil? nil? 'some? some?
   'string? string? 'number? number? 'map? map? 'coll? coll? 'identity identity
   'json/read json/read-str 'json/write json/write-str})

(def reference
  "What the model is told — the ONLY place it learns the language."
  "Define a new composite tool. Its body is written in Tool Lisp, a small Clojure subset that is INTERPRETED (not compiled) and may call other tools.

Args:
- id: \"user/<kebab-name>\"
- description: one line
- params: object of param name -> {\"type\": \"string\"|\"integer\"|\"boolean\"|\"array\", \"desc\": \"...\", \"optional\": true?}
- body: a string holding ONE form: (fn [args] ...) where args is the map of params (keyword keys). Destructuring is allowed: (fn [{:keys [path n]}] ...)

Tool Lisp:
- Special forms: fn (anonymous only) let if when cond do and or -> ->>. Destructuring with {:keys [...] :as x} and [a b] in fn params and let.
- Call another tool with a map: (read-file {:path p}) or (user/count-lines {:path p}). A tool returns its result map; a failed tool returns {:error-message \"...\"}.
- Allowed functions ONLY: get get-in assoc assoc-in dissoc update merge select-keys keys vals count first second last rest take drop conj into vec set concat distinct sort sort-by reverse frequencies group-by empty? contains? range map mapv filter remove keep mapcat reduce some every? str subs format pr-str name keyword parse-long clojure.string/split clojure.string/split-lines clojure.string/join clojure.string/upper-case clojure.string/lower-case clojure.string/trim clojure.string/blank? clojure.string/includes? clojure.string/starts-with? clojure.string/ends-with? clojure.string/replace re-pattern re-find re-seq re-matches + - * / inc dec min max quot rem = not= < > <= >= not nil? some? string? number? map? coll? identity json/read json/write
- Keywords and maps work as functions: (:path args), (map :name xs).
- Literals: strings, numbers, keywords, vectors, maps, sets, nil, true, false. NO regex literals: write (re-pattern \"\\\\s+\").
- json/read parses a JSON string into a map with KEYWORD keys; json/write does the reverse.
- NOT allowed: def, defn, loop, recur, named/recursive fns, eval, atoms, try/catch, Java interop, any function not listed above.
The body is validated when you call tool$define; errors list every problem. Use tool$test to run a defined tool.")

;; ============================================================================
;; Validation — every symbol resolves, nothing forbidden, tool calls check out
;; ============================================================================

(defn- pattern-locals
  "Symbols a binding pattern introduces."
  [pat]
  (cond
    (symbol? pat) (if (= '& pat) #{} #{pat})
    (vector? pat) (reduce into #{} (map pattern-locals pat))
    (map? pat) (into (set (concat (:keys pat) (when (:as pat) [(:as pat)])))
                     (mapcat pattern-locals (remove keyword? (keys pat))))
    :else #{}))

(defn validate
  "A vector of problems for `form` given `tool-specs` ({id {:params …}}), or nil."
  [form tool-specs]
  (let [problems (atom [])
        problem! #(swap! problems conj %)]
    (letfn [(walk [f locals]
              (cond
                (symbol? f)
                (when-not (or (contains? locals f) (contains? ops f) (contains? tool-specs (str f))
                              (contains? special-forms f))
                  (problem! (cond
                              (contains? forbidden f) (str "`" f "` is not allowed in Tool Lisp")
                              (str/starts-with? (name f) ".") (str "`" f "`: Java interop is not allowed")
                              :else (str "unknown symbol `" f "` (not a local, an allowed function, or a tool)"))))

                (seq? f)
                (let [[head & args] f]
                  (cond
                    (= 'fn head)
                    (let [[params & body] (if (symbol? (first args))
                                            (do (problem! "named fns are not allowed") (rest args))
                                            args)]
                      (if-not (vector? params)
                        (problem! "fn needs a parameter vector")
                        (doseq [b body] (walk b (into locals (pattern-locals params))))))

                    (= 'let head)
                    (let [[bindings & body] args]
                      (if-not (and (vector? bindings) (even? (count bindings)))
                        (problem! "let needs a vector of binding pairs")
                        (let [ls (reduce (fn [ls [pat v]] (walk v ls) (into ls (pattern-locals pat)))
                                         locals (partition 2 bindings))]
                          (doseq [b body] (walk b ls)))))

                    (contains? '#{-> ->>} head)
                    (doseq [a args] (walk a locals))

                    (and (symbol? head) (contains? tool-specs (str head)))
                    (let [spec (get tool-specs (str head))
                          arg (first args)]
                      (when (map? arg)
                        (let [known (set (map (comp name first) (:params spec)))
                              given (set (map name (filter keyword? (keys arg))))]
                          (doseq [k (remove known given)]
                            (problem! (str "tool " head " has no param `" k "` (params: " (str/join ", " (sort known)) ")")))
                          (doseq [[k {:keys [optional]}] (:params spec)
                                  :when (and (not optional) (not (contains? given (name k))))]
                            (problem! (str "tool " head " call is missing required param `" (name k) "`")))))
                      (doseq [a args] (walk a locals)))

                    :else
                    (do (when (symbol? head) (walk head locals))
                        (when-not (or (symbol? head) (keyword? head) (seq? head) (map? head))
                          (problem! (str "cannot call " (pr-str head))))
                        (doseq [a args] (walk a locals)))))

                (map? f) (doseq [[k v] f] (walk k locals) (walk v locals))
                (coll? f) (doseq [x f] (walk x locals))
                :else nil))]
      (cond
        (not (and (seq? form) (= 'fn (first form))))
        (problem! "body must be a single (fn [args] ...) form")
        :else (walk form #{})))
    (not-empty @problems)))

;; ============================================================================
;; Evaluation — an interpreter, never host eval
;; ============================================================================

(def ^:dynamic *fuel* nil)

(defn- burn! []
  (when (neg? (swap! *fuel* dec))
    (throw (ex-info "Tool Lisp: evaluation budget exhausted" {:kind :fuel}))))

(defn- bind [pat v]
  (cond
    (symbol? pat) {pat v}
    (vector? pat) (let [[fixed [_ rest-sym]] (split-with #(not= '& %) pat)]
                    (merge (apply merge {} (map-indexed (fn [i p] (bind p (nth (vec v) i nil))) fixed))
                           (when rest-sym {rest-sym (vec (drop (count fixed) v))})))
    (map? pat) (merge (into {} (for [k (:keys pat)] [k (get v (keyword (name k)) (get-in pat [:or k]))]))
                      (when (:as pat) {(:as pat) v})
                      (apply merge {} (for [[p k] pat :when (not (keyword? p))] (bind p (get v k)))))
    :else {}))

(declare ev)

(defn- thread [form args last?]
  (reduce (fn [acc step]
            (if (seq? step)
              (if last? (concat step [acc]) (concat [(first step) acc] (rest step)))
              (list step acc)))
          form args))

(defn ev [form env call-tool]
  (burn!)
  (cond
    (symbol? form) (cond (contains? env form) (get env form)
                         (contains? ops form) (get ops form)
                         :else (fn [& args] (call-tool (str form) (if (map? (first args)) (first args) (apply hash-map args)))))
    (vector? form) (mapv #(ev % env call-tool) form)
    (map? form) (into {} (map (fn [[k v]] [(ev k env call-tool) (ev v env call-tool)])) form)
    (set? form) (set (map #(ev % env call-tool) form))
    (seq? form)
    (let [[head & args] form]
      (case head
        fn (let [[params & body] args]
             (fn [& xs] (let [env' (merge env (bind params (vec xs)))]
                          (reduce (fn [_ b] (ev b env' call-tool)) nil body))))
        let (let [[bindings & body] args
                  env' (reduce (fn [e [p v]] (merge e (bind p (ev v e call-tool)))) env (partition 2 bindings))]
              (reduce (fn [_ b] (ev b env' call-tool)) nil body))
        if (if (ev (first args) env call-tool) (ev (second args) env call-tool) (ev (nth args 2 nil) env call-tool))
        when (when (ev (first args) env call-tool) (reduce (fn [_ b] (ev b env call-tool)) nil (rest args)))
        cond (loop [[t e & more] args] (cond (nil? t) nil (ev t env call-tool) (ev e env call-tool) :else (recur more)))
        do (reduce (fn [_ b] (ev b env call-tool)) nil args)
        and (reduce (fn [_ a] (let [v (ev a env call-tool)] (if v v (reduced v)))) true args)
        or (reduce (fn [_ a] (let [v (ev a env call-tool)] (if v (reduced v) v))) nil args)
        -> (ev (thread (first args) (rest args) false) env call-tool)
        ->> (ev (thread (first args) (rest args) true) env call-tool)
        (let [f (ev head env call-tool)
              xs (mapv #(ev % env call-tool) args)]
          (apply f xs))))
    :else form))

;; ============================================================================
;; tool$define / tool$test over a registry of composites
;; ============================================================================

(defn- builtin-specs []
  (into {} (map (juxt :name identity)) tools/builtin-tools))

(defn parse-params
  "The model's params object → spec :params, or a problem string."
  [params]
  (cond
    (map? params) (vec (for [[k v] params]
                         [(keyword (name k)) {:type (or (:type v) (get v "type") "string")
                                              :desc (or (:desc v) (:description v))
                                              :optional (boolean (:optional v))}]))
    (sequential? params) (vec (for [p params :let [n (or (:name p) (get p "name"))]]
                                [(keyword n) {:type (or (:type p) "string") :desc (:desc p)
                                              :optional (boolean (:optional p))}]))
    :else "params must be an object of name -> {type, desc, optional}"))

(defn make-registry
  "An atom {id spec} of composites, plus fns to call any tool by id."
  [working-dir]
  (let [composites (atom {})
        ctx {:config {:working-dir working-dir}}]
    (letfn [(specs [] (merge (builtin-specs) @composites))
            (call [id args]
              (if-let [c (get @composites id)]
                (run-composite c args)
                (if-let [t (get (builtin-specs) id)]
                  (tools/call-tool t args ctx)
                  {:error-message (str "no tool " id)})))
            (run-composite [{:keys [form]} args]
              (binding [*fuel* (atom 100000)]
                (try ((ev form {} call) (tools/normalize-args args))
                     (catch Throwable e {:error-message (str "Tool Lisp runtime error: " (ex-message e))}))))]
      {:composites composites :specs specs :call call})))

(defn define!
  "Validate and register. Returns {:ok …} or {:error-message …} with every problem."
  [{:keys [composites specs]} {:keys [id description params body]}]
  (let [params (parse-params params)
        form (try (if (string? body) (edn/read-string body) body)
                  (catch Throwable e {::read-error (ex-message e)}))
        problems (cond-> []
                   (not (and (string? id) (re-matches #"user/[a-z][a-z0-9-]*" id)))
                   (conj "id must look like user/<kebab-name>")
                   (string? params) (conj params)
                   (::read-error form)
                   (conj (str "body does not read as EDN: " (::read-error form)
                              (when (str/includes? (str body) "#\"") " — regex literals are not allowed; use (re-pattern \"...\")")))
                   (and (not (::read-error form)) (not (string? params)))
                   (into (validate form (assoc (specs) id {:params params}))))]
    (if (seq problems)
      {:error-message (str "Invalid tool definition:\n- " (str/join "\n- " problems))}
      (do (swap! composites assoc id {:name id :description description :params params :form form})
          {:ok true :id id}))))

(defn authoring-tools [registry]
  [{:name "tool$define"
    :description reference
    :params [[:id {:type "string"}] [:description {:type "string"}]
             [:params {:type "object"}] [:body {:type "string"}]]
    :fn (fn [args _] (define! registry args))}
   {:name "tool$test"
    :description "Run a tool you defined with tool$define: {\"id\": \"user/...\", \"args\": {...}}. Returns its result."
    :params [[:id {:type "string"}] [:args {:type "object" :optional true}]]
    :fn (fn [{:keys [id args]} _] ((:call registry) id (or args {})))}])

;; ============================================================================
;; Offline self-check
;; ============================================================================

(defn self-check
  "The three brainyard bodies, converted, must validate and run."
  []
  (let [dir "target/tool-lisp-eval/self-check"
        _ (.mkdirs (io/file dir))
        _ (spit (io/file dir "a.txt") "one two\nthree\n")
        reg (make-registry dir)
        defs [{:id "user/count-lines" :params {:path {:type "string"}}
               :body "(fn [args] (let [path (:path args) res (read-file {:path path}) content (:content res)] (if content {:path path :lines (count (clojure.string/split-lines content))} {:error (str \"Could not read file: \" path) :path path})))"}
              {:id "user/count-words" :params {:path {:type "string"}}
               :body "(fn [args] (let [content (or (:content (read-file {:path (:path args)})) \"\") words (remove clojure.string/blank? (clojure.string/split content (re-pattern \"\\\\s+\")))] {:path (:path args) :word-count (count words)}))"}
              {:id "user/file-stats" :params {:paths {:type "array"}}
               :body "(fn [{:keys [paths]}] (mapv (fn [p] (merge (user/count-lines {:path p}) (user/count-words {:path p}))) paths))"}]]
    {:defined (mapv #(define! reg %) defs)
     :run ((:call reg) "user/file-stats" {:paths ["a.txt"]})
     :rejects [(define! reg {:id "user/bad" :params {} :body "(fn [a] (def x 1) (slurp \"/etc/passwd\") (.toString a) (read-file {:pth 1}))"})
               (define! reg {:id "user/re" :params {} :body "(fn [a] (re-find #\"x\" \"x\"))"})]}))

;; ============================================================================
;; The experiment
;; ============================================================================

(def tasks-resource "brainyard/dev/tool_lisp_tasks.edn")

(defn- setup-fixtures! [dir fixtures]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (.mkdirs (io/file dir))
  (doseq [[path content] fixtures]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(defn- check-case [call id {:keys [args expect]}]
  (let [got (call id args)
        got-sel (if (map? got) (select-keys got (keys expect)) got)]
    {:args args :expect expect :got got :pass (= expect got-sel)}))

(defn run-task
  "Run one task against one model through the real ReAct loop."
  [model {:keys [id tool-id prompt fixtures pre cases]}]
  (let [dir (str "target/tool-lisp-eval/" model "/" (name id))
        _ (setup-fixtures! dir fixtures)
        reg (make-registry dir)
        _ (doseq [p pre] (define! reg p))
        attempts (atom [])
        tests (atom 0)
        lm (llm/create-lm :claude-code #(System/getenv %) {:model model})
        t0 (System/currentTimeMillis)
        result (try
                 (agent/ask prompt
                            {:lm lm
                             :tools (into tools/builtin-tools (authoring-tools reg))
                             :config {:working-dir dir :max-iterations 8}
                             :on-event (fn [e d]
                                         (when (= e :agent.tools/results)
                                           (doseq [{:keys [tool-name tool-args tool-result]} (:results d)]
                                             (case tool-name
                                               "tool$define" (swap! attempts conj {:args tool-args :result tool-result})
                                               "tool$test" (swap! tests inc)
                                               nil))))})
                 (catch Throwable e {:error (ex-message e)}))
        defined? (contains? @(:composites reg) tool-id)
        checks (when defined? (mapv #(check-case (:call reg) tool-id %) cases))]
    {:model model :task id :tool-id tool-id
     :defined defined?
     :define-attempts (count @attempts)
     :first-attempt-valid (boolean (some-> (first @attempts) :result (str/includes? ":ok true")))
     :errors (vec (keep #(when (str/includes? (str (:result %)) "Invalid tool definition") (:result %)) @attempts))
     :self-tests @tests
     :cases-passed (count (filter :pass checks)) :cases (count cases)
     :checks checks
     :body (some-> @(:composites reg) (get tool-id) :form pr-str)
     :answer (:answer result)
     :terminated-by (:terminated-by result) :iterations (:iterations result)
     :llm-calls (get-in result [:usage :llm-calls])
     :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn -main [& models]
  (let [tasks (edn/read-string (slurp (io/resource tasks-resource)))
        out (io/file "target/tool-lisp-eval/results.edn")
        only (some-> (System/getenv "TASKS") (str/split #",") set)]
    (.mkdirs (.getParentFile out))
    (doseq [model (or (seq models) ["haiku"])
            rep (range (or (some-> (System/getenv "REPS") parse-long) 1))
            task tasks
            :when (or (nil? only) (contains? only (name (:id task))))]
      (let [r (assoc (run-task model task) :rep rep)]
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "r%d %-7s %-18s defined=%-5s attempts=%d first-valid=%-5s cases=%d/%d tests=%d calls=%s %.0fs"
                         rep model (name (:task r)) (:defined r) (:define-attempts r) (:first-attempt-valid r)
                         (:cases-passed r) (:cases r) (:self-tests r) (:llm-calls r) (/ (:elapsed-ms r) 1000.0)))
        (flush)))
    (System/exit 0)))
