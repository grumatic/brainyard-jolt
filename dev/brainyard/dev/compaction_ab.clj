(ns brainyard.dev.compaction-ab
  "Does tier 2 pay? (byj-gly.168, RFC-014 'Measurement plan' steps 2–5) — the
  same multi-step tasks, run through the unmodified loop at a tight window with
  `:compaction-summary?` off (elision, then dropping) and on (elision, then a
  summary, then dropping).

      jolt -A:dev -m brainyard.dev.compaction-ab haiku sonnet
      REPS=2 TASKS=report-total jolt -A:dev -m brainyard.dev.compaction-ab haiku
      MOCK=1 jolt -A:dev -m brainyard.dev.compaction-ab     # offline: does tier 2 fire?

  The tasks are built so tier 2 has something to do. Every fact the answer
  needs is in a file too small to elide (under `:min-elide-chars`), the model
  has only `list-dir` and `read-file`, one call per iteration — so it reads the
  files one at a time, and by the last one the first are older than the
  verbatim floor and over the budget. Off, they are dropped; on, they are
  summarized. Whether the answer survives is the question.

  Per run, the RFC's metrics: correct (the expected text in the answer — plan
  loss is a wrong or missing one), billed tokens (input + output, the summary
  calls included), LLM calls, re-reads (a path read more than once), what
  compaction did, iterations, wall time. Results:
  target/compaction-ab/results.edn."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.json :as json]
            [brainyard.llm :as llm]
            [brainyard.tools :as tools]))

(defn- filler
  "Prose to make a report ~1600 chars: under the 2000 `:min-elide-chars`, so
  tier 1 leaves it alone and only tier 2 or dropping can make room."
  [i]
  (str/join "\n" (for [k (range 13)] (str "Line " k " of report " i ": routine operations, nothing to flag."))))

(def tasks
  "Fixtures are generated so the expected answers are known exactly."
  (let [totals [412 87 1290 356 73 998 145 610 222 804 59 471]
        regions ["north" "south" "east" "west" "central" "coast" "delta" "ridge" "plain" "harbor" "valley" "summit"]
        report (fn [i line] (str "Report " i "\n" (filler i) "\n" line "\n" (filler (+ 100 i))))]
    [{:id :report-total
      :prompt "Each file under reports/ has one line of the form `total: N`. What is the sum of all the totals? Read the files and answer with the number."
      :fixtures (into {} (map-indexed (fn [i t] [(format "reports/r-%02d.txt" (inc i)) (report (inc i) (str "total: " t))]) totals))
      :expect (str (reduce + totals))}
     (let [sites (mapv (fn [t r] [(+ t 7) r]) (reverse totals) regions)]
       {:id :largest-region
        :prompt "Each file under sites/ names a region and its `total: N`. Which region has the largest total? Read the files and answer with the region's name."
        :fixtures (into {} (map-indexed (fn [i [t r]] [(format "sites/s-%02d.txt" (inc i))
                                                        (report (inc i) (str "region: " r "\ntotal: " t))])
                                        sites))
        ;; derived, not written down: a hand-written "east" once scored every
        ;; run by whether the answer happened to contain "least"
        :expect (second (apply max-key first sites))})]))

(def window
  "The tight window: the system prompt is ~6K tokens, so ~5K are left for the
  question and the record, and a dozen ~1.6K-char reads do not fit."
  {:max-context-tokens 12500 :max-output-tokens 2000 :keep-verbatim-iterations 3
   :rebudget-every-n-iter 1 :max-iterations 20 :max-tool-calls 1 :tool-run? false})

(defn- setup! [dir fixtures]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))
  (doseq [[path content] fixtures]
    (let [f (io/file dir path)]
      (.mkdirs (.getParentFile f))
      (spit f content))))

(defn- mock-lm
  "Offline: read every fixture in order, then answer; a summary call gets a
  summary. It checks the plumbing — that tier 2 fires here — not a model."
  [fixtures]
  (let [paths (atom (sort (keys fixtures)))]
    {:provider :mock :format :mock
     :responses (fn [msgs]
                  (if (str/includes? (str (:content (first msgs))) "You compress")
                    (json/write-str {:summary "read the earlier reports; totals noted"})
                    (if-let [p (first @paths)]
                      (do (swap! paths rest)
                          (json/write-str {:reasoning (str "reading " p) :tool-calls [{:tool-name "read-file" :tool-args {:path p}}] :answer ""}))
                      (json/write-str {:reasoning "done" :tool-calls [] :answer "unknown"}))))}))

(defn run-task [model summary? {:keys [id prompt fixtures expect]}]
  (let [dir (str "target/compaction-ab/" model "/" (if summary? "summary" "drop") "/" (name id))
        _ (setup! dir fixtures)
        reads (atom [])
        thoughts (atom [])
        compactions (atom [])
        t0 (System/currentTimeMillis)
        r (try
            (agent/ask prompt {:lm (if (= "mock" model)
                                     (mock-lm fixtures)
                                     (llm/create-lm :claude-code #(System/getenv %) {:model model}))
                               :tools (filter #(#{"list-dir" "read-file"} (:name %)) tools/builtin-tools)
                               :config (merge window {:working-dir dir :project-dir dir :compaction-summary? summary?})
                               :on-event (fn [e d]
                                           (case e
                                             :agent.tools/calls (swap! reads into (keep #(when (= "read-file" (:tool-name %))
                                                                                           (get-in % [:tool-args :path]))
                                                                                        (:tool-calls d)))
                                             :agent.context/budgeted (swap! compactions into (:compactions d))
                                             ;; how the model carried what it had read: a running tally
                                             ;; in its reasoning survives a drop, a fact in a result does not
                                             :agent.think/reasoning (when-let [t (:reasoning d)] (swap! thoughts conj t))
                                             nil))})
            (catch Throwable e {:error (ex-message e)}))
        steps (fn [k] (reduce + 0 (keep #(when (= k (:step %)) (:n %)) @compactions)))]
    {:model model :task id :summary? summary?
     ;; the expected text as a whole word, so "east" is not found in "least",
     ;; in an answer the model gave: a run that ran out of iterations quotes
     ;; its latest thought, which can name the right region without having
     ;; finished — that is the plan loss this measures, not a success
     :correct (boolean (and (= :answer-channel (:terminated-by r))
                            (some->> (:answer r) (re-find (re-pattern (str "(?i)\\b" expect "\\b"))))))
     :billed-tokens (+ (get-in r [:usage :input-tokens] 0) (get-in r [:usage :output-tokens] 0))
     :input-tokens (get-in r [:usage :input-tokens]) :output-tokens (get-in r [:usage :output-tokens])
     :cost (get-in r [:usage :cost])
     :llm-calls (get-in r [:usage :llm-calls]) :iterations (:iterations r)
     :reads (count @reads) :re-reads (- (count @reads) (count (distinct @reads)))
     :summarized (steps :summarized) :dropped (steps :dropped) :elided (steps :elided)
     :terminated-by (:terminated-by r) :error (:error r)
     :answer (some-> (:answer r) (subs 0 (min 300 (count (:answer r)))))
     :last-thoughts (vec (take-last 3 @thoughts))
     :elapsed-ms (- (System/currentTimeMillis) t0)}))

(defn -main [& models]
  (let [mock? (= "1" (System/getenv "MOCK"))
        out (io/file (str "target/compaction-ab/results" (when mock? "-mock") ".edn"))
        only (some-> (System/getenv "TASKS") (str/split #",") set)
        reps (or (some-> (System/getenv "REPS") parse-long) 1)]
    (.mkdirs (.getParentFile out))
    (doseq [model (if mock? ["mock"] (or (seq models) ["haiku"]))
            rep (range reps)
            task tasks
            :when (or (nil? only) (contains? only (name (:id task))))
            summary? [false true]]
      (let [r (assoc (run-task model summary? task) :rep rep)]
        (spit out (str (pr-str r) "\n") :append true)
        (println (format "r%d %-6s %-14s %-7s correct=%-5s billed=%-6s calls=%-3s iters=%-3s reads=%-3s re-reads=%-2s elided=%-2s summarized=%-2s dropped=%-2s %.0fs%s"
                         rep model (name (:task r)) (if summary? "summary" "drop") (:correct r)
                         (:billed-tokens r) (:llm-calls r) (:iterations r) (:reads r) (:re-reads r)
                         (:elided r) (:summarized r) (:dropped r)
                         (/ (:elapsed-ms r) 1000.0) (if (:error r) (str " ERROR " (:error r)) "")))
        (flush)))
    (System/exit 0)))
