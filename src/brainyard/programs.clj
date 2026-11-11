(ns brainyard.programs
  "Measuring and tuning a predictor against this project (RFC-021) — brainyard's
  `agent/common/programs.clj`. The half that knows the project's directories;
  `brainyard.evaluate` and `brainyard.optimize` know none of them.

      <project-dir>/.brainyard/programs/<id>.edn                    params (RFC-005)
      <project-dir>/.brainyard/programs/<id>/datasets/<name>.edn    a dataset
      <project-dir>/.brainyard/programs/<id>/evals/<ts>.edn         an eval report
      <project-dir>/.brainyard/programs/<id>/proposals/<ts>/…       a proposal

  `<id>.edn` is the file RFC-005 already reads on every call, so compiling does
  not introduce a new mechanism: it WRITES the file the runtime has always read.
  That is also why nothing here applies a proposal. A params file silently
  changes the system message of every later turn, so accepting one is reachable
  only from the CLI (`by programs accept`), never as a tool — an agent that
  could accept its own proposal would be choosing its own future instructions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.config :as config]
            [brainyard.evaluate :as ev]
            [brainyard.llm :as llm]
            [brainyard.optimize :as opt]
            [brainyard.predictions :as predictions]
            [brainyard.secrets :as secrets]
            [brainyard.predictor :as predictor]
            [brainyard.signature :as sig]))

(declare render-review)

;; --- paths ------------------------------------------------------------------------

(def ^:private name-re #"[A-Za-z0-9_-][A-Za-z0-9_.-]*")

(defn- check-name! [what s]
  (when-not (and (string? s) (re-matches name-re s))
    (throw (ex-info (str "invalid " what ": " (pr-str s)) {:name s})))
  s)

(defn- check-id! [id]
  (when-not (and (string? id) (predictor/valid-id? id))
    (throw (ex-info (str "invalid predictor id: " (pr-str id)) {:id id})))
  id)

(defn programs-root
  "`<project-dir>/.brainyard/programs`, absolute."
  [config]
  (io/file (config/canonical-dir (or (:project-dir config) (:working-dir config) "."))
           ".brainyard" "programs"))

(defn params-file-for [config id] (io/file (programs-root config) (str (check-id! id) ".edn")))
(defn dataset-file [config id nm] (io/file (programs-root config) (check-id! id) "datasets" (str (check-name! "dataset name" nm) ".edn")))
(defn evals-dir [config id] (io/file (programs-root config) (check-id! id) "evals"))
(defn proposals-dir [config id] (io/file (programs-root config) (check-id! id) "proposals"))

(defn list-datasets [config id]
  (let [d (io/file (programs-root config) (check-id! id) "datasets")]
    (if-not (.isDirectory d)
      []
      (->> (.listFiles d)
           (map #(.getName %))
           (filter #(str/ends-with? % ".edn"))
           (mapv #(subs % 0 (- (count %) 4)))
           sort vec))))

(defn- write-edn! [f x]
  (.mkdirs (.getParentFile f))
  (spit f (pr-str x))
  (.getPath f))

(defn- read-edn [f]
  (when (.isFile (io/file f))
    (try (edn/read-string (slurp f)) (catch Throwable _ nil))))

;; --- datasets ---------------------------------------------------------------------

(defn load-dataset
  "A recorded dataset. Its tool-call labels are read as `name`/`args`
  whatever they were recorded as: a dataset from before the change scored
  every tool-call example wrong against replies in the new shape
  (byj-gly.233)."
  [config id nm]
  (let [ds (or (read-edn (dataset-file config id nm))
               (throw (ex-info (str "no dataset " nm " for " id) {:id id :dataset nm})))
        fix (fn [ex] (cond-> ex (get-in ex [:labels :tool-calls]) (update-in [:labels :tool-calls] sig/repair-tool-calls)))]
    (cond-> ds (sequential? (:examples ds)) (update :examples #(mapv fix %)))))

(defn records->examples
  "Prediction records as examples, with what was dropped and why — brainyard's
  `records->examples`. An errored or duplicate record is not a training example:
  the first did not answer the question asked, and the second would weight one
  question twice.

  **`:skip-clipped` defaults to FALSE**, which it did not. The guard existed
  because the PREDICTION LOG truncated at 4000 characters, so a record carrying
  the marker misrepresented what the model had actually been shown. That log is
  gone and the event log clips nothing (RFC-026), so the only thing still
  writing that marker is `agent/clip-field` trimming iteration history for the
  context budget — and a record carrying THAT is faithful: the model saw exactly
  that input and produced exactly that output. Rejecting it discards a true
  example.

  Measured on the first real pool, 156 records: 80 examples under the old
  clipping log, 106 with the guard on, 154 with it off — a val split of 35
  rather than 16, against the twelve brainyard measured its null at.

  Set it true when reading records from a source that DOES truncate — an old
  `predictions.edn`, or a trajectory — where the marker means the record and
  the model disagree about what was seen.

  The labels are the model's OWN outputs, so a dataset built this way is
  **silver**, not gold — `:label-source :prediction-log` records that, and
  nothing downstream is allowed to forget it.

  **Every example is redacted** (`brainyard.secrets`), and there is no flag for
  it. A prediction record holds whatever the turn was working on — brainyard
  found recalled memory in one verbatim — and an example is not where that
  stops: an example becomes a demo, and a demo is pasted into the system
  message of every later turn, which reaches the model provider. The log itself
  is the raw copy, and it is off until a project asks for it (RFC-021)."
  [records {:keys [skip-clipped max-examples] :or {skip-clipped false}}]
  (loop [[r & more] records, out [], seen #{}, skipped {:error 0 :invalid 0 :clipped 0 :duplicate 0}]
    (cond
      (or (nil? r) (and max-examples (>= (count out) max-examples)))
      {:examples out :skipped skipped}

      (:error r) (recur more out seen (update skipped :error inc))
      (not (seq (:outputs r))) (recur more out seen (update skipped :invalid inc))
      (and skip-clipped (predictions/clipped? (select-keys r [:inputs :outputs :reasoning])))
      (recur more out seen (update skipped :clipped inc))
      (contains? seen (:inputs r)) (recur more out seen (update skipped :duplicate inc))

      :else
      (recur more
             (conj out (secrets/redact
                        (ev/example (:inputs r) (:outputs r)
                                    {:session (:session r) :ts (:ts r)})))
             (conj seen (:inputs r))
             skipped))))

(defn build-dataset
  "Build `<id>/datasets/<name>.edn` from the prediction log. Returns a summary;
  throws when nothing survives, because an empty dataset written to disk reads
  later as a measurement rather than as a missing log."
  [config id nm {:keys [session-id] :as opts}]
  (check-id! id)
  (check-name! "dataset name" nm)
  (let [all (predictions/records config id)
        sessions (if (str/blank? (str session-id))
                   (vec (distinct (keep :session all)))
                   [session-id])
        records (if (str/blank? (str session-id))
                  all
                  (vec (filter #(= (str session-id) (str (:session %))) all)))
        {:keys [examples skipped]} (records->examples records opts)]
    (when (empty? examples)
      (throw (ex-info (str "No usable prediction records for " id
                           (if (false? (:event-log? config))
                             " — the event log is off (:event-log? false), and the records come from it"
                             " — nothing has been recorded for it yet"))
                      {:sessions (count sessions) :records (count records) :skipped skipped})))
    (let [ds {:v 1 :predictor-id id :created (System/currentTimeMillis)
              :label-source :prediction-log
              :sessions (vec sessions)
              :examples examples}]
      {:path (write-edn! (dataset-file config id nm) ds)
       ;; said out loud: a dataset that quietly differs from the log it came
       ;; from is a dataset someone will one day be surprised by
       :redacted (secrets/redacted? examples)
       :examples (count examples)
       :records (count records)
       :sessions (count sessions)
       :skipped skipped
       :dataset-hash (ev/dataset-hash examples)})))

;; --- metrics ----------------------------------------------------------------------

(def metrics
  "The metrics a command may name. brainyard's registry is `exact-match`,
  `schema-valid` and a memory-specific graph F1 whose subsystem is not ported
  (RFC-020); `set-f1:<field>` is parameterized here so a set-valued output is
  reachable by name at all, and `set-f1:<field>:<key>` compares the items by
  one key of each — `set-f1:tool-calls:tool-name` — rather than whole
  (divergence `set-f1-is-nameable`, byj-gly.125)."
  {"exact-match" {:doc "Every labelled field equals the output (case- and space-normalized)."
                  :make (fn [_] (ev/exact-match))}
   "schema-valid" {:doc "The outputs were schema-valid."
                   :make (fn [_] (ev/schema-valid))}
   "segment-f1" {:doc "Pairwise same-task F1 of a segmentation's :tasks against the labelled ones."
                 :make (fn [_] (ev/segment-f1))}
   "set-f1" {:doc "F1 over a set-valued field; name it as `set-f1:<field>`, or `set-f1:<field>:<key>` to compare its items by one key (set-f1:tool-calls:tool-name)."
             :make (fn [arg]
                     (let [[field k] (str/split (str arg) #":" 2)]
                       (cond
                         (str/blank? field) (throw (ex-info "set-f1 needs a field: set-f1:<field>" {}))
                         (str/blank? k) (ev/set-f1 (keyword field))
                         :else (let [k (keyword k)]
                                 (ev/set-f1 (keyword field)
                                            #(let [v (get % k)] (if (string? v) (str/lower-case (str/trim v)) v)))))))}
   "channel-choice" {:doc "Did it take the labelled channel: called a tool (non-empty :tool-calls) or answered; `channel-choice:<field>` for another field."
                     :make (fn [arg] (if (str/blank? (str arg)) (ev/channel-choice) (ev/channel-choice (keyword arg))))}})

(defn resolve-metric
  "A metric by name, `<name>` or `<name>:<arg>`."
  [s]
  (let [[nm arg] (str/split (str s) #":" 2)
        m (get metrics nm)]
    (when-not m
      (throw (ex-info (str "unknown metric " (pr-str s) " — " (str/join ", " (sort (keys metrics))))
                      {:metric s})))
    ((:make m) arg)))

;; --- running a predictor as a program -----------------------------------------------

(defn predictor-program
  "A program (RFC-021) that runs one predictor. The trace comes back with the
  result instead of through a dynamic var, so the caller owns it and a parallel
  evaluation needs no binding conveyance."
  [p opts]
  (fn [inputs]
    (let [trace (atom [])
          on-event (fn [e d] (when (= :predictor/call e) (swap! trace conj d)))
          result (predictor/run p inputs (assoc opts :on-event on-event))]
      {:result result :trace @trace})))

(defn- lm-label [lm] (when lm (str (name (:provider lm)) "/" (:model lm))))

(defn- resolve-eval-lm
  "The model an evaluation runs on: an explicit `:lm` label, else a `:tier`
  through the table, else the ambient LM. An unresolvable hint THROWS here
  rather than degrading as it does on the hot path (RFC-005): a measurement
  that quietly ran on another model is worse than one that did not run."
  [{:keys [lm tier]} config ambient]
  (cond
    ;; an already-resolved LM: the caller's ambient model, passed through
    (map? lm) lm

    (not (str/blank? (str lm)))
    (or (llm/parse-lm-label lm) (throw (ex-info (str "Unresolvable :lm " lm) {:lm lm})))

    (not (str/blank? (str tier)))
    (or (llm/tier-lm (:agent-lm-tiers config) (llm/coerce-tier tier))
        (throw (ex-info (str "Tier " tier " has no model in :agent-lm-tiers") {:tier tier})))

    :else ambient))

;; --- eval -------------------------------------------------------------------------

(defn summarize-report
  "A report without its per-example rows, plus the rows worth reading: the five
  worst, and — when it was repeated — the five least stable. brainyard's
  `summarize-report`."
  [report]
  (cond-> (-> report
              (dissoc :per-example)
              (assoc :worst (->> (:per-example report)
                                 (sort-by :score)
                                 (take 5)
                                 (mapv #(select-keys % [:index :score :error :error-class])))))
    (> (:repeats report 1) 1)
    (assoc :least-stable (->> (:per-example report)
                              (sort-by #(- (:spread % 0)))
                              (take 5)
                              (mapv #(select-keys % [:index :score :scores :spread]))))))

(defn- used-lm
  "The model a predictor's calls run on under `run-opts`, resolved as each call
  resolves it: a params file's :lm or a :tier beats the ambient `lm`."
  [p run-opts lm]
  (or (predictor/resolve-lm p (:params (predictor/resolve-params p run-opts)) run-opts) lm))

(defn eval-predictor
  "Measure a predictor on a dataset and write the report. Returns
  `{:report-path :summary}`."
  [config id dataset-name metric-name {:keys [split repeats parallel max-calls max-tokens] :as opts}]
  (let [p (predictor/load-predictor id (predictor/default-roots
                                        (or (:project-dir config) (:working-dir config) ".") "predictors"))
        metric (resolve-metric metric-name)
        ds (load-dataset config id dataset-name)
        examples (ev/select-split (:examples ds) split)
        _ (when (empty? examples)
            (throw (ex-info (str "split " (or split "all") " is empty") {:dataset dataset-name})))
        lm (resolve-eval-lm opts config (:lm opts))
        ;; an evaluation must not feed the log the next dataset is built from
        run-opts {:lm lm :lm-tiers (:agent-lm-tiers config)
                  :params-roots [(.getPath (programs-root config))]}
        report (ev/evaluate (predictor-program p run-opts) examples metric
                            :repeats (or repeats 1) :parallel (or parallel 1)
                            :max-calls max-calls :max-tokens max-tokens)
        ts (System/currentTimeMillis)
        ;; the model the calls ran on: a params file's :lm or a :tier beats the
        ;; ambient LM (predictor/resolve-lm), so stamping the ambient one said
        ;; :fixed of a run that sampled freely, and the reverse (byj-gly.229)
        used (used-lm p run-opts lm)
        free? (llm/samples-freely? used)
        full (merge report
                    {:predictor-id id :dataset dataset-name :dataset-hash (:dataset report)
                     :label-source (:label-source ds)
                     :split (name (or split :all))
                     :metric metric-name
                     :model (lm-label used)
                     ;; whether this score could be reproduced. A provider that
                     ;; takes no temperature moves between identical passes, and
                     ;; one pass of it cannot be told from a real difference
                     ;; (`byj-gly.126`); `evaluate` cannot say so itself, since
                     ;; it is handed a program and never sees the LM (RFC-021).
                     :sampling (if free? :free :fixed)
                     :ts ts}
                    (when (and free? (< (long (or repeats 1)) 2))
                      {:sampling-warning
                       (str "single pass on " (lm-label used) ", which takes no temperature — "
                            "this score includes the sampler's own movement; use :repeats 2+")}))]
    {:report-path (write-edn! (io/file (evals-dir config id) (str ts ".edn")) full)
     :summary (summarize-report (dissoc full :dataset))}))

;; --- compile ----------------------------------------------------------------------

(defn- candidate-row
  "Evaluate one leaderboard row."
  [cname program examples metric opts extra]
  (merge {:candidate cname}
         (select-keys (ev/evaluate program examples metric
                                   :repeats (:repeats opts 1) :parallel (:parallel opts 1)
                                   :max-calls (:max-calls opts) :max-tokens (:max-tokens opts))
                      ev/leaderboard-row-keys)
         extra))

(defn compile-predictor
  "Propose demos for a predictor and write the proposal for review. Never
  applies anything.

  The train split bootstraps, the val split selects, the test split is never
  touched. Three rows are always scored so every dossier answers the same
  question — zero-shot (the student as it ships), the teacher (a reference that
  cannot win, being a different model), and the proposal — and `select-best`
  gives the proposal the win only if it beats zero-shot past the margin — tested
  per example, paired, not per pass.
  A proposal whose winner is zero-shot is a RESULT, not a failure."
  [config id dataset-name {:keys [optimizer metric max-bootstrapped seed threshold
                                  teacher-baseline? repeats parallel max-calls max-tokens
                                  trials]
                           :or {optimizer "bootstrap-few-shot" metric "exact-match"
                                max-bootstrapped 4 seed 0 threshold 1.0 teacher-baseline? true
                                trials 4}
                           :as opts}]
  (let [roots (predictor/default-roots (or (:project-dir config) (:working-dir config) ".") "predictors")
        p (predictor/load-predictor id roots)
        metric-fn (resolve-metric metric)
        ds (load-dataset config id dataset-name)
        {:keys [train val]} (ev/split (:examples ds))
        _ (when (empty? train) (throw (ex-info "Dataset train split is empty — add examples" {:dataset dataset-name})))
        _ (when (empty? val) (throw (ex-info "Dataset val split is empty — selection needs a valset" {:dataset dataset-name})))
        student-lm (resolve-eval-lm opts config (:lm opts))
        teacher-lm (or (resolve-eval-lm {:lm (:teacher-lm opts) :tier (:teacher-tier opts)} config nil)
                       (llm/tier-lm (:agent-lm-tiers config) :deep)
                       student-lm)
        teacher-is-student? (= (lm-label teacher-lm) (lm-label student-lm))
        ;; the prompt as it ships today, laid UNDER every candidate — without it
        ;; a zero-shot row would be measured against instructions that will
        ;; never be sent
        base (select-keys (:params (predictor/resolve-params
                                    p {:params-roots [(.getPath (programs-root config))]}))
                          [:instructions :field-descs])
        prog-for (fn [lm params]
                   (predictor-program p {:lm lm :lm-tiers (:agent-lm-tiers config)
                                         :params {id params}}))
        opt-opts {:max-bootstrapped max-bootstrapped :predictor-id id :seed seed
                  :threshold threshold :max-calls max-calls :max-tokens max-tokens}
        one (fn [{:keys [params report]}] {:candidates [{:params params}] :report report})
        {:keys [candidates report]}
        (case optimizer
          "labeled-few-shot" (one (opt/labeled-few-shot id train :k max-bootstrapped :seed seed))
          "bootstrap-few-shot" (one (apply opt/bootstrap-few-shot (prog-for teacher-lm base) train metric-fn
                                           (mapcat identity opt-opts)))
          "bootstrap-random-search" (apply opt/bootstrap-random-search (prog-for teacher-lm base) train metric-fn
                                           (mapcat identity (assoc opt-opts :trials trials)))
          (throw (ex-info (str "unknown optimizer " optimizer " — labeled-few-shot,"
                               " bootstrap-few-shot, bootstrap-random-search")
                          {:optimizer optimizer})))
        ;; one candidate keeps the name it always had, so a single-proposal
        ;; dossier reads exactly as before; a search names its trial
        labelled (if (= 1 (count candidates))
                   [[:proposal (merge base (get-in (first candidates) [:params id]))]]
                   (mapv (fn [c] [(keyword (str "proposal-" (:trial c)))
                                  (merge base (get-in c [:params id]))])
                         candidates))
        ;; BUDGET-SHARED: a search must not cost N times a single proposal, so
        ;; the call and token caps are divided across the rows rather than
        ;; applied to each. Without this, `:max-calls` would mean something
        ;; different depending on which optimizer was asked for.
        n-rows (+ 1 (if teacher-baseline? 1 0) (count labelled))
        share (fn [v] (when v (max 1 (quot v n-rows))))
        row-opts (cond-> opts
                   max-calls (assoc :max-calls (share max-calls))
                   max-tokens (assoc :max-tokens (share max-tokens)))
        rows (cond-> [(candidate-row :zero-shot (prog-for student-lm base) val metric-fn row-opts {})]
               teacher-baseline?
               (conj (candidate-row :teacher (prog-for teacher-lm base) val metric-fn row-opts {:reference? true}))
               :always
               (into (mapv (fn [[label params]]
                             (candidate-row label (prog-for student-lm params) val metric-fn row-opts {}))
                           labelled)))
        [best leaderboard] (ev/select-best rows)
        no-winner? (contains? #{:zero-shot nil} (:candidate best))
        candidate (or (get (into {} labelled) (:candidate best))
                      (second (first labelled)))
        ts (System/currentTimeMillis)
        winner (if no-winner? base candidate)
        pid-params (cond-> winner
                     (seq (:demos winner))
                     (assoc :version ts
                            :compiled-by {:optimizer optimizer :metric metric
                                          :dataset dataset-name
                                          :dataset-hash (ev/dataset-hash (:examples ds))
                                          :score (:score best)
                                          :lm (lm-label student-lm)
                                          :teacher (lm-label teacher-lm)}))
        _ (when-let [problems (predictor/validate-params pid-params)]
            (throw (ex-info (str "Optimizer produced invalid params: " (str/join "; " problems))
                            {:problems problems})))
        zero-score (:score (first (remove :reference? leaderboard)))
        teacher-score (:score (first (filter :reference? leaderboard)))
        full-report (merge report
                           {:best (:candidate best) :best-score (:score best)
                            :zero-shot-score zero-score :teacher-score teacher-score
                            :leaderboard leaderboard
                            :valset (ev/dataset-hash val)
                            :repeats (or repeats 1)
                            :meta {:proposal-id (str ts) :optimizer optimizer :metric metric
                                   :dataset dataset-name :label-source (:label-source ds)
                                   :student (lm-label student-lm) :teacher (lm-label teacher-lm)
                                   :teacher-is-student? teacher-is-student?
                                   :zero-shot-score zero-score
                                   :no-op? no-winner?
                                   :candidate-file? (boolean (and no-winner? (seq (:demos candidate))))}})
        dir (io/file (proposals-dir config id) (str ts))]
    ;; again on the way out, not because the demos are unredacted — they came
    ;; through records->examples — but because this is the file that becomes
    ;; every later prompt, and it should not depend on one upstream call to be
    ;; safe. An optimizer that proposes an INSTRUCTION rather than demos
    ;; (`byj-gly.121`) reaches this line and not the other one.
    (write-edn! (io/file dir "params.edn") (secrets/redact pid-params))
    (when (and no-winner? (seq (:demos candidate)))
      (write-edn! (io/file dir "candidate.edn") (secrets/redact candidate)))
    (write-edn! (io/file dir "report.edn") full-report)
    (write-edn! (io/file dir "status.edn") {:status :pending :ts ts})
    (spit (io/file dir "REVIEW.md") (render-review config p id pid-params full-report))
    {:proposal-id (str ts)
     :review (.getPath (io/file dir "REVIEW.md"))
     :best (some-> (:candidate best) name)
     :best-score (:score best)
     :zero-shot-score zero-score
     :teacher-score teacher-score
     :demos (count (:demos pid-params))
     :no-op no-winner?
     :calls (reduce + (keep :calls leaderboard))
     :stopped (some-> (some :stopped leaderboard) name)}))

;; --- the dossier -------------------------------------------------------------------

(defn- fmt [x] (if (number? x) (format "%.3f" (double x)) "—"))

(defn render-review
  "REVIEW.md — what a person reads before accepting. The demo section is the
  LITERAL system-prompt text the demos become, not a summary of them: the thing
  being accepted is words in a prompt, so the dossier shows the words."
  [_config p id params report]
  (let [m (:meta report)
        row (fn [r]
              (let [vs (:vs-baseline r)]
                (format "| %s | %s | %s | %s | %s | %s |"
                        (name (:candidate r))
                        (fmt (:score r))
                        (if-let [sd (:stddev r)] (str "±" (fmt sd)) "—")
                        (cond (:reference? r) "reference (cannot win)"
                              (nil? vs) "baseline"
                              (:better? vs) (format "✅ %+.3f (> %s)" (double (:diff vs)) (fmt (:margin vs)))
                              (:tested? vs) (format "✗ %+.3f (needs > %s)" (double (:diff vs)) (fmt (:margin vs)))
                              :else (format "%+.3f (untested — one pass)" (double (:diff vs))))
                        (str (:attempted r) "/" (:n r))
                        (or (some-> (:stopped r) name) "—"))))]
    (str "# Proposal " (:proposal-id m) " — " id "\n\n"
         "**" (if (:no-op? m) "No change proposed" "Demos proposed") "** — "
         (if (:no-op? m)
           "zero-shot won; accepting installs the current instructions unchanged."
           (str (count (:demos params)) " demo(s) beat zero-shot on the val split."))
         "\n\n"
         "| | |\n|---|---|\n"
         "| optimizer | `" (:optimizer m) "` |\n"
         "| metric | `" (:metric m) "` |\n"
         "| dataset | `" (:dataset m) "` (" (name (or (:label-source m) :unknown)) ") |\n"
         "| student | `" (:student m) "` |\n"
         "| teacher | `" (:teacher m) "`"
         (if (:teacher-is-student? m) " ⚠ teacher is the student" "") " |\n"
         "| repeats | " (:repeats report 1) " |\n\n"
         (when-let [scores (:scores report)]
           (str "Teacher passed " (:passed report) "/" (:attempted report)
                " training examples (threshold " (:threshold report) ").\n"
                "Teacher scores, in trainset order: "
                (str/join " " (map #(if (nil? %) "—" (fmt %)) scores)) "\n\n"))
         "## Leaderboard\n\n"
         "| candidate | score | sd | vs zero-shot | examples | stopped |\n"
         "|---|---|---|---|---|---|\n"
         (str/join "\n" (map row (:leaderboard report))) "\n\n"
         (let [tested (keep #(get-in % [:vs-baseline :tested?]) (:leaderboard report))
               paired? (some #(get-in % [:vs-baseline :paired?]) (:leaderboard report))]
           (cond
             (and (seq tested) (not-any? true? tested))
             (str "⚠ No difference here was tested: there was no spread to weigh"
                  " — a single pass per candidate, or identical scores. The"
                  " ranking above is arithmetic. brainyard measured ~0.05"
                  " run-to-run noise on a 12-example val set; a deterministic"
                  " model has none at all, which is not the same as a real"
                  " gain.\n\n")
             paired?
             (str "Differences are tested per EXAMPLE, paired: both candidates"
                  " ran the same " (:n (first (keep :vs-baseline (:leaderboard report))))
                  " val examples, and a candidate wins only if the per-example"
                  " differences are centred past the margin. A higher mean is"
                  " not enough.\n\n")
             :else
             (str "Differences are tested with Welch's t over " (:repeats report 1)
                  " passes of the per-pass means; a candidate wins only past"
                  " the margin.\n\n")))
         (when (:instructions params)
           (str "## Instructions\n\n```\n" (:instructions params) "\n```\n\n"))
         (let [demos (predictor/prepare-demos params)]
           (if (empty? demos)
             "## Demos\n\nNone — this proposal changes no demos.\n\n"
             (str "## Demos — exact system-prompt text (" (count demos) ")\n\n```\n"
                  (sig/render-demos (:signature p) demos)
                  "\n```\n\n")))
         "## Review\n\n"
         "1. Do the demos show the behaviour you want, in the words the model will read?\n"
         "2. Is the win larger than the noise, or is it one lucky pass?\n"
         "3. Are the labels honest — silver labels are the model's own past outputs.\n\n"
         "```\nby programs accept " id " " (:proposal-id m) "\nby programs reject " id " " (:proposal-id m) "\n```\n")))

;; --- proposals ---------------------------------------------------------------------

(defn list-proposals
  "A predictor's proposals, newest first."
  [config id]
  (let [d (proposals-dir config id)]
    (if-not (.isDirectory d)
      []
      (->> (.listFiles d)
           (filter #(.isDirectory %))
           (keep (fn [dir]
                   (let [report (read-edn (io/file dir "report.edn"))
                         status (read-edn (io/file dir "status.edn"))]
                     (when report
                       {:proposal-id (.getName dir)
                        :status (name (:status status :pending))
                        :optimizer (get-in report [:meta :optimizer])
                        :best (some-> (:best report) name)
                        :best-score (:best-score report)
                        :zero-shot-score (:zero-shot-score report)
                        :no-op (get-in report [:meta :no-op?])
                        :review (.getPath (io/file dir "REVIEW.md"))}))))
           (sort-by :proposal-id)
           reverse vec))))

(defn accept-proposal!
  "Install a proposal's params as `<id>.edn`, keeping the replaced file as
  `previous.edn`. **Not reachable from any tool** — only `by programs accept`.

  Redacts on the way in (`byj-gly.58`): this is the file every later turn's
  system message is built from."
  [config id proposal-id]
  (let [dir (io/file (proposals-dir config id) (str proposal-id))
        status (or (read-edn (io/file dir "status.edn"))
                   (throw (ex-info (str "no proposal " proposal-id " for " id) {:id id})))
        params (or (read-edn (io/file dir "params.edn"))
                   (throw (ex-info "proposal has no params.edn" {:id id})))]
    (when-not (= :pending (:status status))
      (throw (ex-info (str "Proposal " proposal-id " is " (name (:status status))) {:status (:status status)})))
    (when-let [problems (predictor/validate-params params)]
      (throw (ex-info (str "proposal params are invalid: " (str/join "; " problems)) {:problems problems})))
    (let [target (params-file-for config id)
          previous (when (.isFile target)
                     (write-edn! (io/file dir "previous.edn") (read-edn target)))]
      ;; the last gate before this becomes every later prompt. The proposal's
      ;; own params.edn was redacted when written, but it is a file on disk
      ;; between then and now, and a person may have edited it — which is the
      ;; whole point of a proposal being reviewed.
      (write-edn! target (secrets/redact params))
      (write-edn! (io/file dir "status.edn") (assoc status :status :accepted :decided (System/currentTimeMillis)))
      {:params-file (.getPath target) :previous previous})))

(defn reject-proposal!
  "Mark a proposal rejected. The files stay — a rejected proposal is a record."
  [config id proposal-id]
  (let [f (io/file (proposals-dir config id) (str proposal-id) "status.edn")
        status (or (read-edn f) (throw (ex-info (str "no proposal " proposal-id " for " id) {:id id})))]
    (write-edn! f (assoc status :status :rejected :decided (System/currentTimeMillis)))
    {:status "rejected"}))

;; --- commands ----------------------------------------------------------------------

(defn- err [e] {:error (ex-message e)})

(defn commands
  "`program$*`. Accept and reject are deliberately absent (RFC-021)."
  [config]
  (when (:programs? config true)
    [{:name "program$list" :kind :command
      :description (str "List predictors with their params source, datasets and the metrics available."
                        " Start here before evaluating or compiling anything.")
      :input-schema [:map]
      :output-schema [:map {:desc ":params is where a predictor's params come from, or \"none\"; :strategy and :params are absent for one that does not load"}
                [:predictors [:vector [:map [:id :string] [:datasets [:vector :string]]
                                       [:strategy {:optional true} :string]
                                       [:params {:optional true} :string]]]]
                [:metrics [:map-of :string :string]]]
      :effects #{:fs/read}
      :fn (fn [_ ctx]
            (let [config (:config ctx)
                  roots (predictor/default-roots (or (:project-dir config) (:working-dir config) ".") "predictors")]
              {:predictors (vec (for [id (predictor/list-ids roots)]
                                  (let [p (try (predictor/load-predictor id roots) (catch Throwable _ nil))]
                                    (cond-> {:id id :datasets (list-datasets config id)}
                                      p (assoc :strategy (name (:strategy p :predict))
                                               :params (let [src (:source (predictor/resolve-params
                                                                          p {:params-roots [(.getPath (programs-root config))]}))]
                                                         (if src (str src) "none")))))))
               :metrics (into (sorted-map) (update-vals metrics :doc))}))}

     {:name "program$build-dataset" :kind :command
      :description (str "Build a dataset for a predictor from this project's prediction log."
                        " The labels are the model's OWN past outputs — silver, not gold — so say so"
                        " when reporting anything measured on it. Built from the event log.")
      :input-schema [:map [:predictor-id [:string {:desc "The predictor's id."}]]
                          [:name [:string {:desc "Dataset name."}]]
                          [:session-id {:optional true} [:string {:desc "One session; default every session."}]]
                          [:max-examples {:optional true} :int]]
      :output-schema [:or {:desc ":examples, :records and :sessions are counts; :skipped counts the dropped records by reason"}
                [:map [:path :string] [:redacted :boolean] [:examples :int] [:records :int] [:sessions :int]
                 [:skipped [:map-of :keyword :int]] [:dataset-hash :string]]
                [:map [:error :string]
                 [:records {:optional true} :int] [:sessions {:optional true} :int]
                 [:skipped {:optional true} [:map-of :keyword :int]]]]
      :effects #{:fs/read :fs/write}
      :fn (fn [{:keys [predictor-id name session-id max-examples]} ctx]
            (try (build-dataset (:config ctx) predictor-id name
                                {:session-id session-id :max-examples max-examples})
                 (catch Throwable e (merge (err e) (select-keys (ex-data e) [:records :sessions :skipped])))))}

     {:name "program$eval" :kind :command
      :description (str "Measure a predictor on a dataset with a named metric; writes a report."
                        " Use :repeats 3 or more for anything you intend to COMPARE — a single pass"
                        " cannot tell a real difference from run-to-run noise, and never report a"
                        " score without its spread.")
      :input-schema [:map [:predictor-id :string]
                          [:dataset :string]
                          [:metric {:optional true} [:string {:desc "Default exact-match; program$list has the names."}]]
                          [:split {:optional true} [:string {:desc "all|train|val|test (default all)."}]]
                          [:repeats {:optional true} [:int {:desc "Passes over the set (default 1)."}]]
                          [:parallel {:optional true} :int]
                          [:lm {:optional true} [:string {:desc "provider/model to measure."}]]
                          [:tier {:optional true} [:string {:desc "light|standard|deep."}]]
                          [:max-calls {:optional true} [:int {:desc "Spend cap."}]]
                          [:max-tokens {:optional true} [:int {:desc "Spend cap."}]]]
      :output-schema [:or
                [:map [:report-path :string]
                 [:summary [:map
                          [:score number?] [:stddev [:maybe number?]] [:stderr [:maybe number?]]
                          [:ci95 [:maybe [:tuple number? number?]]]
                          [:repeats :int] [:completed-repeats :int] [:repeat-scores [:vector number?]]
                          [:n :int] [:attempted :int] [:errors :int] [:stopped [:maybe :keyword]]
                          [:calls :int] [:tokens [:map [:in :int] [:out :int]]] [:elapsed-ms :int]
                          [:example-scores [:vector number?]]
                          [:predictor-id :string] [:dataset-hash :string] [:label-source [:maybe :keyword]]
                          [:split :string] [:metric :string] [:model [:maybe :string]]
                          [:sampling [:enum :free :fixed]] [:ts :int]
                          [:sampling-warning {:optional true} :string]
                          [:worst [:vector [:map [:index :int] [:score number?]
                                            [:error {:optional true} :string]
                                            [:error-class {:optional true} :keyword]]]]
                          [:least-stable {:optional true}
                           [:vector [:map [:index :int] [:score number?] [:scores [:vector number?]]
                                     [:spread number?]]]]]]]
                [:map [:error :string]]]
      :effects #{:fs/read :fs/write :net/http}
      :fn (fn [{:keys [predictor-id dataset metric] :as a} ctx]
            (try (eval-predictor (:config ctx) predictor-id dataset (or metric "exact-match")
                                 (-> (select-keys a [:split :repeats :parallel :lm :tier :max-calls :max-tokens])
                                     (assoc :lm (or (:lm a) (:lm ctx)))))
                 (catch Throwable e (err e))))}

     {:name "program$compile" :kind :command
      :description (str "Propose demos for a predictor from a dataset and write a PROPOSAL for a person"
                        " to review. Nothing is applied: a params file changes every later turn's prompt,"
                        " so only `by programs accept` installs one. A proposal whose winner is zero-shot"
                        " is a result, not a failure.")
      :input-schema [:map [:predictor-id :string]
                          [:dataset :string]
                          [:optimizer {:optional true} [:string {:desc "labeled-few-shot|bootstrap-few-shot (default bootstrap-few-shot)."}]]
                          [:metric {:optional true} :string]
                          [:max-bootstrapped {:optional true} [:int {:desc "Demos to keep (default 4)."}]]
                          [:repeats {:optional true} :int]
                          [:parallel {:optional true} :int]
                          [:lm {:optional true} [:string {:desc "The student."}]]
                          [:tier {:optional true} :string]
                          [:teacher-lm {:optional true} [:string {:desc "The teacher; default the :deep tier."}]]
                          [:teacher-tier {:optional true} :string]
                          [:max-calls {:optional true} :int]
                          [:max-tokens {:optional true} :int]]
      :output-schema [:or {:desc ":review is the REVIEW.md path; :no-op true means zero-shot won"}
                [:map [:proposal-id :string] [:review :string] [:best [:maybe :string]]
                 [:best-score [:maybe number?]] [:zero-shot-score [:maybe number?]]
                 [:teacher-score [:maybe number?]] [:demos :int] [:no-op :boolean]
                 [:calls :int] [:stopped [:maybe :string]]]
                [:map [:error :string]]]
      :effects #{:fs/read :fs/write :net/http}
      :fn (fn [{:keys [predictor-id dataset] :as a} ctx]
            (try (compile-predictor (:config ctx) predictor-id dataset
                                    (-> (select-keys a [:optimizer :metric :max-bootstrapped :repeats :parallel
                                                        :lm :tier :teacher-lm :teacher-tier :max-calls :max-tokens])
                                        (update :optimizer #(or % "bootstrap-few-shot"))
                                        (update :metric #(or % "exact-match"))
                                        (update :max-bootstrapped #(or % 4))
                                        (assoc :lm (or (:lm a) (:lm ctx)))))
                 (catch Throwable e (err e))))}

     {:name "program$proposals" :kind :command
      :description "List a predictor's proposals and their review status. Accepting one is a person's job."
      :input-schema [:map [:predictor-id :string]]
      :output-schema [:or {:desc "newest first"}
                [:map [:proposals [:vector [:map [:proposal-id :string] [:status :string]
                                            [:optimizer [:maybe :string]] [:best [:maybe :string]]
                                            [:best-score [:maybe number?]] [:zero-shot-score [:maybe number?]]
                                            [:no-op [:maybe :boolean]] [:review :string]]]]]
                [:map [:error :string]]]
      :effects #{:fs/read}
      :fn (fn [{:keys [predictor-id]} ctx]
            (try {:proposals (list-proposals (:config ctx) predictor-id)}
                 (catch Throwable e (err e))))}]))
