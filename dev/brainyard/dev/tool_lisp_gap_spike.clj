(ns brainyard.dev.tool-lisp-gap-spike
  "What would Tool Lisp need to express the tools an agent writes for itself?

      jolt -A:dev -m brainyard.dev.tool-lisp-gap-spike

  A corpus of tasks an agent meets in its own loop and would want as a tool it
  can keep: paging and retrying, walking a tree, comparing, dispatching,
  handling a failure, keeping a tally, calling a model inside a tool. Each is
  written the way a model writes Clojure: idiomatically, reaching for
  `clojure.core`, as the RFC-010 evals saw haiku and sonnet do. Every body is
  read with the Clojure reader and analyzed for its FREE symbols, knowing
  how each binding form binds (`let`, `loop`, `for`, `doseq`, `letfn`, `fn`,
  `catch` …). Each free symbol is then Tool Lisp's today (`tool-lisp/ops` and
  its special forms), a tool, or missing.

  Then four extensions are simulated, cumulatively, and the tasks each makes
  expressible are counted:

    A  vocabulary  pure functions and namespaces SCI ships by default
    B  forms       macros that desugar to what the interpreter already does
    C  control     loop/recur, try/catch/throw, named recursion — bounded by
                   the step and tool-call budgets the interpreter already has
    D  reach       capabilities that need design: calling a model, parallel
                   tool calls, the clock, state that outlives a call

  The corpus is written by hand, so it measures reach, not frequency: which
  tasks a tier makes possible at all. How often models reach for each form is
  what RFC-010's evals and the event log measured, and the refusals seen there
  are marked in `seen`. Prints raw tables.")

(def tools
  "The primitives a body calls as functions (`brainyard.tools/builtin-tools`),
  plus the two user tools the corpus assumes the agent already made."
  '#{read-file write-file update-file grep list-dir bash user$tool$summarize user$tool$fetch})

;; --- the corpus ------------------------------------------------------------------------

(def corpus
  [{:id :top-words :cat "aggregate" :why "count words in a file, the 10 most common"
    :body "(fn [{:keys [path]}]
            (->> (:content (read-file {:path path}))
                 (re-seq #\"\\w+\") (map clojure.string/lower-case) frequencies
                 (sort-by val >) (take 10) (into {})))"}
   {:id :json-rollup :cat "aggregate" :why "sum a JSON log's durations by status"
    :body "(fn [{:keys [path]}]
            (let [rows (json/read (:content (read-file {:path path})))]
              (reduce (fn [acc {:strs [status ms]}] (update acc status (fnil + 0) ms)) {} rows)))"}
   {:id :sort-two-keys :cat "aggregate" :why "files by size then name"
    :body "(fn [_] (->> (:entries (list-dir {:path \".\"}))
                      (sort (fn [a b] (compare [(:size b) (:name a)] [(:size a) (:name b)])))))"}
   {:id :page-through :cat "iterate" :why "read grep results page by page until empty"
    :body "(fn [{:keys [pattern]}]
            (loop [page 0 acc []]
              (let [hits (:matches (grep {:pattern pattern :path \".\" :offset (* page 100) :max-results 100}))]
                (if (empty? hits) acc (recur (inc page) (into acc hits))))))"}
   {:id :retry :cat "iterate" :why "retry a flaky tool three times, keep the last error"
    :body "(fn [{:keys [url]}]
            (loop [n 3]
              (let [r (user$tool$fetch {:url url})]
                (if (and (:error r) (pos? n)) (recur (dec n)) r))))"}
   {:id :walk-tree :cat "recurse" :why "every .clj file under a directory, however deep"
    :body "(fn [{:keys [dir]}]
            (letfn [(walk [d] (mapcat (fn [{:keys [name type]}]
                                        (let [p (str d \"/\" name)]
                                          (if (= type \"dir\") (walk p) (when (clojure.string/ends-with? p \".clj\") [p]))))
                                      (:entries (list-dir {:path d}))))]
              (vec (walk dir))))"}
   {:id :tree-seq-walk :cat "recurse" :why "the same, with tree-seq over nested data"
    :body "(fn [{:keys [tree]}] (->> (tree-seq map? :children tree) (keep :path) vec))"}
   {:id :set-diff :cat "compare" :why "imports one file has that another does not"
    :body "(fn [{:keys [a b]}]
            (let [imports (fn [p] (set (re-seq #\"\\(:require \\[([\\w.\\-]+)\" (:content (read-file {:path p})))))]
              {:only-a (clojure.set/difference (imports a) (imports b))
               :both (clojure.set/intersection (imports a) (imports b))}))"}
   {:id :data-diff :cat "compare" :why "what changed between two EDN configs"
    :body "(fn [{:keys [a b]}]
            (let [[x y _] (clojure.data/diff (clojure.edn/read-string (:content (read-file {:path a})))
                                            (clojure.edn/read-string (:content (read-file {:path b}))))]
              {:removed x :added y}))"
    :seen "read-string refused in RFC-010's eval"}
   {:id :dispatch-ext :cat "dispatch" :why "a linter command per file extension"
    :body "(fn [{:keys [path]}]
            (case (last (clojure.string/split path #\"\\.\"))
              \"clj\" (bash {:command (str \"clj-kondo --lint \" path)})
              \"md\" (bash {:command (str \"markdownlint \" path)})
              {:skipped path}))"
    :seen "case refused in RFC-010's eval"}
   {:id :build-request :cat "dispatch" :why "a tool-args map with optional keys"
    :body "(fn [{:keys [pattern path limit]}]
            (grep (cond-> {:pattern pattern :path (or path \".\")} limit (assoc :max-results limit))))"}
   {:id :edit-each :cat "effects" :why "apply one replacement across every matching file"
    :body "(fn [{:keys [from to files]}]
            (doseq [f files] (update-file {:path f :old from :new to}))
            {:edited (count files)})"}
   {:id :for-report :cat "effects" :why "a line per failing test, with its file"
    :body "(fn [{:keys [results]}]
            (clojure.string/join \"\\n\" (for [{:keys [file tests]} results, t tests :when (:failed t)]
                                         (format \"%s: %s\" file (:name t)))))"}
   {:id :guard-parse :cat "failure" :why "parse numbers, keep going past a bad one"
    :body "(fn [{:keys [lines]}]
            (keep (fn [l] (try (parse-double (clojure.string/trim l)) (catch Exception _ nil))) lines))"}
   {:id :fail-loudly :cat "failure" :why "stop with a reason the model can read"
    :body "(fn [{:keys [path]}]
            (let [r (read-file {:path path})]
              (when (:error r) (throw (ex-info (str \"cannot read \" path) {:path path})))
              (count (clojure.string/split-lines (:content r)))))"}
   {:id :numbers :cat "text" :why "the numbers in a report, rounded"
    :body "(fn [{:keys [text]}]
            (mapv (fn [s] (Math/round (Double/parseDouble s))) (re-seq #\"\\d+\\.\\d+\" text)))"
    :seen "Double/parseDouble refused in RFC-010's eval"}
   {:id :last-segment :cat "text" :why "the part after the last dot"
    :body "(fn [{:keys [s]}] (subs s (inc (clojure.string/last-index-of s \".\"))))"}
   {:id :template :cat "text" :why "fill a template from a map"
    :body "(fn [{:keys [tpl vars]}]
            (clojure.string/replace tpl #\"\\{\\{(\\w+)\\}\\}\" (fn [[_ k]] (str (get vars (keyword k) \"\")))))"}
   {:id :normalize-keys :cat "shape" :why "a JSON reply with keyword keys, deeply"
    :body "(fn [{:keys [json]}] (clojure.walk/keywordize-keys (json/read json)))"}
   {:id :reshape :cat "shape" :why "rename and re-shape each record"
    :body "(fn [{:keys [rows]}]
            (mapv #(-> % (clojure.set/rename-keys {:n :name}) (update-vals str)) rows))"}
   {:id :chunk-by-blank :cat "shape" :why "paragraphs of a file"
    :body "(fn [{:keys [path]}]
            (->> (clojure.string/split-lines (:content (read-file {:path path})))
                 (partition-by clojure.string/blank?) (remove #(clojure.string/blank? (first %)))
                 (mapv #(clojure.string/join \"\\n\" %))))"}
   {:id :timestamps :cat "time" :why "how long each job in a log took"
    :body "(fn [{:keys [lines]}]
            (for [[_ id start end] (keep #(re-find #\"(\\w+) (\\S+) (\\S+)\" %) lines)]
              {:id id :seconds (- (.getEpochSecond (java.time.Instant/parse end))
                                  (.getEpochSecond (java.time.Instant/parse start)))}))"}
   {:id :stamp :cat "time" :why "name an output file by today's date"
    :body "(fn [{:keys [content]}]
            (write-file {:path (str \"reports/\" (inst-ms (java.util.Date.)) \".md\") :content content}))"}
   {:id :summarize-each :cat "model" :why "summarize every changed file with a model"
    :body "(fn [{:keys [files]}]
            (into {} (map (fn [f] [f (:summary (predict \"user/summarize\" {:text (:content (read-file {:path f}))}))]) files)))"}
   {:id :classify :cat "model" :why "route each issue by a model's judgement"
    :body "(fn [{:keys [issues]}] (group-by #(:label (predict \"user/triage\" {:issue %})) issues))"}
   {:id :fan-out :cat "parallel" :why "grep five patterns at once"
    :body "(fn [{:keys [patterns]}]
            (zipmap patterns (pmap #(count (:matches (grep {:pattern % :path \".\"}))) patterns)))"}
   {:id :memo :cat "state" :why "cache an expensive lookup across calls"
    :body "(fn [{:keys [k]}]
            (let [cache (atom {})]
              (or (get @cache k) (let [v (user$tool$fetch {:url k})] (swap! cache assoc k v) v))))"}
   {:id :read-whole :cat "io" :why "a file's text, as a model habitually writes it"
    :body "(fn [{:keys [path]}] (count (slurp path)))"
    :seen "slurp refused 4x in the event log"}])

;; --- free symbols ------------------------------------------------------------------------

(defn- pattern-syms [p]
  (cond (symbol? p) (if (= '& p) #{} #{p})
        (vector? p) (reduce into #{} (map pattern-syms p))
        (map? p) (into (set (concat (map #(symbol (name %)) (concat (:keys p) (:strs p) (:syms p)))
                                    (when (:as p) [(:as p)])))
                       (mapcat pattern-syms (remove keyword? (keys p))))
        :else #{}))

(def ^:private binding-heads
  '#{let loop for doseq dotimes if-let when-let if-some when-some when-first with-open binding})

(defn free-symbols
  "Every symbol in `form` that is not bound inside it, with each binding form's
  scope honoured."
  ([form] (free-symbols form #{}))
  ([form bound]
   (cond
     (symbol? form) (if (bound form) #{} #{form})
     (seq? form)
     (let [[h & args] form]
       (cond
         (= 'quote h) #{}
         (#{'fn 'fn*} h)
         (let [args (if (symbol? (first args)) (rest args) args)
               bound (if (symbol? (second form)) (conj bound (second form)) bound)
               arities (if (vector? (first args)) [args] args)]
           (reduce into #{} (for [[params & body] arities]
                              (free-symbols (cons 'do body) (into bound (pattern-syms params))))))
         (binding-heads h)
         (let [[bv & body] args
               pairs (partition 2 bv)
               [fr b] (reduce (fn [[fr b] [pat v]]
                                (if (keyword? pat)            ; :when :while :let in for/doseq
                                  (if (= :let pat)
                                    (let [lp (partition 2 v)]
                                      [(into fr (mapcat #(free-symbols (second %) b) lp))
                                       (into b (mapcat #(pattern-syms (first %)) lp))])
                                    [(into fr (free-symbols v b)) b])
                                  [(into fr (free-symbols v b)) (into b (pattern-syms pat))]))
                              [#{} bound] pairs)]
           (into (conj fr h) (free-symbols (cons 'do body) b)))
         (= 'letfn h)
         (let [[fns & body] args
               b (into bound (map first fns))]
           (into #{'letfn} (reduce into (free-symbols (cons 'do body) b)
                                   (for [[_ params & fbody] fns]
                                     (free-symbols (cons 'do fbody) (into b (pattern-syms params)))))))
         (= 'catch h) (let [[_ e & body] args] (conj (free-symbols (cons 'do body) (conj bound e)) 'catch))
         (= 'case h) (let [[e & clauses] args
                           results (concat (take-nth 2 (rest clauses)) (when (odd? (count clauses)) [(last clauses)]))]
                       (into #{'case} (reduce into (free-symbols e bound) (map #(free-symbols % bound) results))))
         :else (reduce into #{} (map #(free-symbols % bound) form))))
     (map? form) (reduce into #{} (map #(free-symbols % bound) (mapcat identity form)))
     (coll? form) (reduce into #{} (map #(free-symbols % bound) form))
     :else #{})))

;; --- today, and the tiers ----------------------------------------------------------------

(defn- today []
  (into (set (keys @(requiring-resolve 'brainyard.tool-lisp/ops)))
        @(requiring-resolve 'brainyard.tool-lisp/special-forms)))

(def tiers
  [[:A "vocabulary" '#{fnil compare keep-indexed some-fn every-pred disj peek pop subvec vector split-at split-with
                       partition-by dedupe update-vals update-keys find key val not-every? boolean int long double
                       set? integer? tree-seq iterate
                       clojure.string/last-index-of clojure.set/union clojure.set/intersection clojure.set/difference
                       clojure.set/subset? clojure.set/superset? clojure.set/rename-keys clojure.set/map-invert
                       clojure.walk/postwalk clojure.walk/prewalk clojure.walk/keywordize-keys clojure.walk/stringify-keys
                       clojure.edn/read-string clojure.data/diff ex-message ex-data}]
   [:B "forms" '#{for doseq dotimes run! case condp cond-> cond->> as-> some->> if-not if-some when-some when-first}]
   [:C "control" '#{loop recur try catch throw ex-info letfn finally}]
   [:D "reach" '#{predict pmap inst-ms java.util.Date. atom swap! deref}]])

(defn- missing [task allowed]
  (let [form (read-string (:body task))]
    (->> (free-symbols form)
         (remove allowed)
         (remove tools)
         (remove #{'& '_})
         sort vec)))

(defn -main [& _]
  (let [base (today)]
    (println (format "%d tasks; Tool Lisp today allows %d names (functions and special forms)\n"
                     (count corpus) (count base)))
    (println "per task — what it is missing today")
    (doseq [t corpus]
      (let [m (missing t base)]
        (println (format "  %-15s %-10s %s%s" (name (:id t)) (:cat t)
                         (if (empty? m) "expressible" (str "missing " (clojure.string/join " " m)))
                         (if (:seen t) (str "   [" (:seen t) "]") "")))))
    (println "\nhow often each missing name is needed, across the corpus")
    (let [freq (frequencies (mapcat #(missing % base) corpus))]
      (doseq [[s n] (sort-by (juxt (comp - val) (comp str key)) freq)]
        (println (format "  %2d  %s" n s))))
    (println "\ntasks expressible, tiers added cumulatively")
    (println (format "  %-24s %d/%d" "today" (count (filter #(empty? (missing % base)) corpus)) (count corpus)))
    (loop [allowed base, [[id label names] & more] tiers]
      (when id
        (let [allowed (into allowed names)
              ok (filter #(empty? (missing % allowed)) corpus)]
          (println (format "  + %s %-18s %d/%d  (%s names)" (name id) label (count ok) (count corpus) (count names)))
          (recur allowed more))))
    (println "\nstill missing after every tier")
    (let [all (reduce into (today) (map #(nth % 2) tiers))]
      (doseq [t corpus :let [m (missing t all)] :when (seq m)]
        (println (format "  %-15s %s" (name (:id t)) (clojure.string/join " " m)))))))
