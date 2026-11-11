(ns brainyard.context
  "Context budget and compaction, tiers 0 and 1 (RFC-014) — brainyard's
  `agent/core/context_budget.clj` in the small, with the reduction aimed where
  this port's prompt actually grows: tool results.

  Measured here, a result is either navigation (a listing, a `wc -l`: hundreds
  of chars) or content (a file read: 19–28K chars, a wide grep past the cap).
  So tier 1 elides OLD CONTENT results — the text only, never the reasoning,
  the tool name or its arguments, which are the plan — and spills the text to a
  file the model can read back.

  Tiers 0 and 1 are deterministic: no model call. Tier 2 summarizes the old
  span through a predictor the tree names, between elision and dropping — on
  by default since it was measured against dropping (RFC-014, byj-gly.168),
  and off with `:compaction-summary?` false.

  Compaction invalidates the prompt cache from the first changed zone onward
  (RFC-015), which is why `enforce` leaves a turn under budget untouched."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.events :as events]))

;; --- measuring --------------------------------------------------------------------

(defn estimate-tokens
  "brainyard's estimator: `ceil(chars / 4)`, ±10–20% on English. The safety
  ratio in `budget` covers the error."
  [text]
  (long (Math/ceil (/ (count (str text)) 4.0))))

(defn budget
  "usable = window − reply; budget = usable − margin."
  [{:keys [max-context-tokens max-output-tokens context-budget-safety-ratio]
    :or {max-context-tokens 200000 max-output-tokens 8192 context-budget-safety-ratio 0.10}}]
  (let [usable (max 0 (- max-context-tokens (max 0 max-output-tokens)))]
    (long (- usable (Math/ceil (* usable context-budget-safety-ratio))))))

(defn prompt-tokens
  "What the next call will cost: the stable zones, the turn's own text, and the
  printed iteration history — the input the loop builds (RFC-003)."
  [{:keys [zones] :as _ctx} mem]
  (+ (reduce + (map estimate-tokens (vals zones)))
     (estimate-tokens (:question mem))
     (estimate-tokens (:history-context mem))
     (estimate-tokens (:user-context mem))
     (estimate-tokens (pr-str (:iterations mem)))))

;; --- spilling ---------------------------------------------------------------------

(defn session-id
  "A name for this run's spill directory. Sortable, and unique per run: two
  agents in one project share `.brainyard/`, and a shared directory made one
  run's stub point at another run's text — a wrong file the model cannot tell
  from the right one."
  []
  (str (System/currentTimeMillis) "-" (subs (str (random-uuid)) 0 8)))

(defn transcript-dir
  "<project-dir>/.brainyard/transcripts/<session-id>/turn-<n>."
  [config turn]
  (io/file (or (:project-dir config) (:working-dir config) ".")
           ".brainyard" "transcripts"
           (or (:session-id config) "no-session")
           (str "turn-" (or turn 1))))

(defn spill!
  "Write an elided result to the turn's transcript directory and return its
  path. Nothing evicts these within a session: brainyard's markers once
  promised files that had been evicted, and nothing downstream could tell."
  [config turn iteration tool-name text]
  (try
    (let [dir (transcript-dir config turn)
          base (str "it" iteration "-" (str/replace (str tool-name) #"[^a-zA-Z0-9._-]" "_"))
          _ (.mkdirs dir)
          ;; never over another spill: tier 1 names a result by its tool alone
          ;; and tier 0 suffixes the j-th, so eliding one result could write
          ;; over the file another result's marker points at (byj-gly.219).
          ;; The name is claimed atomically; `~` cannot collide with `-j`.
          ;; a loop, not (first (filter …)) over a range: a chunked lazy seq
          ;; ran createNewFile 32 names at a time and left 31 empty files
          f (loop [k 1]
              (when (< k 1000)
                (let [f (io/file dir (str base (when (> k 1) (str "~" k)) ".txt"))]
                  (if (.createNewFile f) f (recur (inc k))))))]
      (when f
        (spit f text)
        (.getPath f)))
    (catch Throwable _ nil)))

(defn stub
  "What the model reads in place of the text: the size, where it went, and how
  to get it back without re-reading the whole thing (a bare re-read of a
  truncated file re-truncates and loops — brainyard's playbook rule 1)."
  [chars path]
  (str "…[elided " chars " chars"
       (when path (str " — full text at " path "; grep it or read a line range"))
       "]"))

(defn model-view
  "Iteration records as a model reads them: each tool call's keys as the
  reply schema names them — `name`, `args`, `result` — not the loop's own
  `:tool-name`/`:tool-args`/`:tool-result`. A history that said `tool-name`
  beside a schema asking for `name` would teach the model both
  (divergence `a-tool-call-is-name-and-args`)."
  [records]
  (mapv (fn [r]
          (cond-> r
            (seq (:tool-results r))
            (update :tool-results
                    (fn [rs] (mapv #(-> %
                                        (dissoc :tool-name :tool-args :tool-result)
                                        (cond-> (contains? % :tool-name) (assoc :name (:tool-name %))
                                                (contains? % :tool-args) (assoc :args (:tool-args %))
                                                (contains? % :tool-result) (assoc :result (:tool-result %))))
                                   rs)))))
        records))

(defn- by-lines
  "`s` as the spill file holds it: a result that is ONE line of EDN — a map or a
  vector, as the kernel prints every result — pretty-printed, so each entry is
  a line and `read-file`'s offset and limit, and `grep`, work on it. A result
  that is text already, or that does not read back, is kept as it is. A spill
  of one 29,000-character line could only be read whole, and reading it whole
  was cut and spilled again (byj-gly.190)."
  [s]
  (or (when (and (not (str/includes? s "\n")) (#{\{ \[} (first (str/triml s))))
        (try (let [forms (edn/read-string (str "[" s "]"))]
               (when (and (= 1 (count forms)) (coll? (first forms)))
                 (with-out-str (pprint/pprint (first forms)))))
             (catch Throwable _ nil)))
      s))

(defn- whole-lines
  "As many of `lines` from the front as fit in `n` characters."
  [lines n]
  (loop [ls lines used 0 out []]
    (let [l (first ls)]
      (if (and l (<= (+ used (count l) 1) n))
        (recur (rest ls) (+ used (count l) 1) (conj out l))
        out))))

(defn cap-result
  "Tier 0 for one result (byj-gly.189): `text` whole when it fits in `n`
  characters; else its head and its tail around a marker that says how much
  was cut and where the whole of it went — spilled first, so what the model
  cannot see is a `read-file` or `grep` away rather than gone. The tail is kept
  because what ends a result — a count, a summary, a closing error — is often
  what it was run for.

  When the spill has lines, the head and tail are whole lines of it and the
  marker names the lines it hides and the `read-file` call that reads them: a
  marker that said only how many characters were cut had the model page the
  spill from line 1, re-reading what it had just been shown (byj-gly.191).
  One enormous line is cut by characters.

  Returns `{:text}`, with `:spilled` the path when it spilled."
  [config turn iteration tool-name text n]
  (let [s (str text)
        total (count s)]
    (if (<= total n)
      {:text s}
      (let [spilled (by-lines s)
            path (spill! config turn iteration tool-name spilled)
            lines (vec (str/split-lines spilled))
            tail-n (quot n 4)
            head-n (- n tail-n)
            head (whole-lines lines head-n)
            tail (when (seq head)
                   (reverse (whole-lines (rseq (subvec lines (count head))) tail-n)))
            from (inc (count head))
            to (- (count lines) (count tail))
            shown (+ (count (str/join "\n" head)) (count (str/join "\n" tail)))
            ;; whole lines only when they show a fair share of the budget: a
            ;; map whose one long string is a single line of the spill — a
            ;; bash :output, a read-file :content — fits neither head nor
            ;; tail, and the model was shown its short keys and none of it
            ;; (byj-gly.217). Then it is cut by characters, as before.
            by-lines? (and path (seq head) (<= from to) (>= shown (quot n 2)))]
        (cond-> {:text (if by-lines?
                         (str (str/join "\n" head)
                              "\n…[truncated: lines " from "–" to " of " (count lines) " are not shown here ("
                              (- (count spilled) shown) " of " (count spilled) " chars) — the whole of it is " path
                              ". Read them with read-file {:path \"" path "\" :offset " from " :limit 200}"
                              " and on from each page's :next-offset, or grep it]\n"
                              (str/join "\n" tail))
                         (str (subs s 0 head-n)
                              "\n…[truncated " (- total n) " of " total " chars"
                              (when path
                                (str " — the whole of it is " path ", "
                                     (if (= 1 (count lines))
                                       (str "one line of " (count spilled) " chars.")
                                       (str (count lines) " lines."))
                                     " Read it with read-file {:path \"" path "\"}, and on from each page's"
                                     " :next-offset and :next-column, or grep it"))
                              "]\n"
                              (subs s (- total tail-n))))}
          path (assoc :spilled path))))))

;; --- tier 1 -----------------------------------------------------------------------

(def ^:private read-tools #{"read-file"})

(def elided-key
  "The marker on an elided result. A plain keyword, because it is printed into
  the prompt: the model should see which results were shortened."
  :elided)

(defn superseded?
  "Is this call's path read again by a later record? Then its body is dead
  weight — the newer read is the current truth."
  [records i result]
  (and (contains? read-tools (:tool-name result))
       (when-let [path (get-in result [:tool-args :path])]
         (boolean (some (fn [r]
                          (some #(and (contains? read-tools (:tool-name %))
                                      (= path (get-in % [:tool-args :path])))
                                (:tool-results r)))
                        (drop (inc i) records))))))

(defn- elide-result [config turn iteration result]
  (let [text (str (:tool-result result))
        ;; a result tier 0 already spilled points at its WHOLE text; spilling
        ;; again would overwrite it with the cut one
        path (or (:spilled result) (spill! config turn iteration (:tool-name result) text))]
    (assoc result :tool-result (stub (or (:full-chars result) (count text)) path) elided-key true)))

(defn- elidable?
  "A content result that is still verbatim. Navigation results are left alone:
  they are small, and eliding them costs a stub as long as the result."
  [config result]
  (and (not (elided-key result))
       (> (count (str (:tool-result result))) (:min-elide-chars config 2000))))

(defn- elide-records
  "Stub content results in `records`, oldest first, stopping as soon as `under?`
  says the prompt fits. `pick` chooses which results of a record may go."
  [config turn records under? pick]
  (loop [out (vec records), i 0]
    (if (or (>= i (count out)) (under? out))
      out
      (let [record (nth out i)
            results (:tool-results record)
            targets (keep-indexed (fn [j r] (when (pick out i j r) j)) results)]
        (if (empty? targets)
          (recur out (inc i))
          (recur (reduce (fn [acc j]
                           (update-in acc [i :tool-results j]
                                      #(elide-result config turn (:iteration record) %)))
                         out targets)
                 (inc i)))))))

;; --- tier 2 -----------------------------------------------------------------------

(defn summary-record
  "brainyard's synthetic record for a collapsed span (`coact_agent.clj`
  `:collapse-iterations`): iteration 0, channel \"summary\", the text as its
  thought — so the transcript shows where the span went, and anything that
  reads a record's thought reads the summary."
  [text]
  {:iteration 0 :channel "summary" :thought text :tool-results []})

(defn- summarize-span
  "Tier 2: the records older than the verbatim floor as ONE summary record, via
  `summarize` — `(fn [span]) → text or nil`. Taken only when the summary is
  shorter than what it replaces; a lone summary is left as it is, since
  summarizing a summary alone buys nothing. Returns the records, changed or not."
  [records keep-n summarize]
  (let [n (- (count records) keep-n)
        span (when (pos? n) (subvec records 0 n))]
    (if (or (empty? span)
            (and (= 1 (count span)) (= "summary" (:channel (first span)))))
      records
      (let [text (summarize span)
            rec (when-not (str/blank? (str text)) (summary-record (str/trim (str text))))]
        (if (and rec (< (estimate-tokens (pr-str rec)) (estimate-tokens (pr-str span))))
          (into [rec] (subvec records n))
          records)))))

(defn reduce-history
  "The iteration records brought under budget, each step only as far as it
  must go: superseded reads, then old content results (tier 1), then — when
  `summarize` is given — the span older than the verbatim floor as one summary
  record (tier 2), and only then whole oldest records. The last
  `:keep-verbatim-iterations` are never elided, summarized or dropped.

  Returns {:records :steps [{:step :superseded|:elided|:summarized|:dropped :n}]}."
  ([config turn records under?] (reduce-history config turn records under? nil))
  ([config turn records under? summarize]
  (let [keep-n (:keep-verbatim-iterations config 3)
        recent? (fn [rs i] (>= i (- (count rs) keep-n)))
        count-elided (fn [rs] (count (for [r rs, x (:tool-results r) :when (elided-key x)] x)))
        steps (atom [])
        note! (fn [step n] (when (pos? n) (swap! steps conj {:step step :n n})))
        before-elided (count-elided records)
        ;; 1. a read whose path is read again later
        superseded (if (:elide-superseded-reads? config true)
                     (elide-records config turn records under?
                                    (fn [rs i j r] (and (not (recent? rs i))
                                                        (elidable? config r)
                                                        (superseded? rs i r))))
                     (vec records))
        _ (note! :superseded (- (count-elided superseded) before-elided))
        mid (count-elided superseded)
        ;; 2. any old content result
        elided (elide-records config turn superseded under?
                              (fn [rs i _ r] (and (not (recent? rs i)) (elidable? config r))))
        _ (note! :elided (- (count-elided elided) mid))
        ;; tier 2: what is older than the floor, as one record, when a
        ;; summarizer is given and elision was not enough
        summarized (if (and summarize (not (under? elided)))
                     (summarize-span elided keep-n summarize)
                     elided)
        _ (when-not (identical? summarized elided)
            (note! :summarized (- (count elided) (dec (count summarized)))))
        ;; 3. whole records, oldest first, down to the verbatim floor
        dropped (loop [rs summarized]
                  (cond
                    (or (under? rs) (<= (count rs) keep-n)) rs
                    ;; the summary is the whole of the span it replaced, and
                    ;; small: it goes only when going gets the prompt under —
                    ;; and while it stays, the records after it still go, down
                    ;; to the floor; stopping at it left the prompt over budget
                    ;; whenever the summarizer had failed (byj-gly.227)
                    (and (= "summary" (:channel (first rs))) (not (under? (vec (rest rs)))))
                    (if (> (count rs) (inc keep-n))
                      (recur (into [(first rs)] (drop 2 rs)))
                      rs)
                    :else (recur (vec (rest rs)))))
        _ (note! :dropped (- (count summarized) (count dropped)))]
    {:records dropped :steps @steps})))

;; --- the leaf ---------------------------------------------------------------------

(defn enforce
  "Bring the prompt under budget — tier 1, then tier 2 when `summarize` is
  given — and report what it took.

  Returns {:before :after :budget :over-budget? :steps :records}. When the
  prompt already fits, `:records` is the input, untouched — a turn under budget
  must build a byte-identical prompt, or prompt caching pays for the
  difference."
  ([ctx mem] (enforce ctx mem nil))
  ([ctx mem summarize]
  (let [config (:config ctx)
        b (budget config)
        fixed (- (prompt-tokens ctx mem) (estimate-tokens (pr-str (:iterations mem))))
        cost (fn [records] (+ fixed (estimate-tokens (pr-str records))))
        before (cost (:iterations mem))]
    (if (<= before b)
      {:before before :after before :budget b :over-budget? false :steps [] :records (:iterations mem)}
      (let [{:keys [records steps]} (reduce-history config (:turn-number mem 1) (:iterations mem)
                                                    #(<= (cost %) b) summarize)
            after (cost records)]
        {:before before :after after :budget b :over-budget? (> after b) :steps steps :records records})))))

(defn summary-text
  "A tier-2 summary's text, from the predictor's outputs: its `:summary`, then
  the identifiers it `:kept` apart from the prose's word cap (byj-gly.383),
  after a blank line. Nil when both are blank."
  [{:keys [summary kept]}]
  (let [s (str/trim (str summary)) k (str/trim (str kept))]
    (not-empty (str/join "\n\n" (remove str/blank? [s k])))))

(defn- node-summarizer
  "Tier 2's summarizer, when the config turns it on and the node names the
  predictor that does it (`:predictor \"react/compact\"`, RFC-005): the span,
  printed as the loop prints it, and the question it was working on, in; one
  summary out. A failed call is no summary — the step after it drops records,
  as it would have without tier 2 — but a cancellation is not a failure."
  [{:keys [config node st-memory] :as ctx}]
  (when (and (:compaction-summary? config) (:predictor node))
    (fn [span]
      (try
        ;; resolved when called: brainyard.agent requires this namespace
        (let [run (requiring-resolve 'brainyard.agent/run-node-predictor)
              {:keys [outputs]} (run ctx {:question (str (:question @st-memory))
                                          :records (pr-str (model-view span))})]
          (summary-text outputs))
        (catch Throwable e
          (when (fx/cancelled? e) (throw e))
          nil)))))

(defn ^{:reads #{:iterations :iteration-count} :writes #{:iterations}}
  enforce-action
  "Leaf `:context/enforce` — the tree decides when the prompt is measured:

    [:action {:id :react/budget :fn :context/enforce}]                  ; at init
    [:action {:id :react/rebudget :fn :context/enforce :every-n 4}]     ; per iteration

  `:every-n` runs it on every nth iteration (brainyard's rebudget cadence), as
  a number or a `:config/…` key. `:enable-context-budget` false makes it a
  no-op."
  [{:keys [st-memory config node on-event] :as ctx}]
  (let [mem @st-memory
        every-n (let [v (:every-n node)]
                  (if (and (keyword? v) (= "config" (namespace v)))
                    (get config (keyword (name v)))
                    v))
        iter (:iteration-count mem 0)]
    (when (and (:enable-context-budget config true)
               (or (nil? every-n) (zero? (mod (max 1 iter) (max 1 every-n)))))
      (let [{:keys [records steps before after budget over-budget?] :as report}
            (enforce ctx mem (node-summarizer ctx))]
        (when (seq steps)
          (swap! st-memory assoc :iterations records))
        (when on-event
          (events/emit! on-event :agent.context/budgeted
                    {:phase (if every-n :rebudget :init) :iteration iter
                     :total-tokens before :after-tokens after :budget budget
                     :compactions steps :over-budget? over-budget?}))
        report))))
