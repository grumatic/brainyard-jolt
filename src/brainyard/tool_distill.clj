;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tool-distill
  "Tool distillation, the mechanism half (byj-gly.249.2, RFC-010).

  A `tool$run` body that worked is validated, executed code. Generalised —
  its literals lifted into arguments, named and described — it is a user
  tool, and unlike a SKILL.md draft it can be PROVED before a person sees it:
  run again on the arguments that reproduce what it did, it must give back
  what it gave then.

  brainyard has no tool distiller; its self-improvement writes skills only
  (divergence `tool-distillation`). This is the same shape as skill
  distillation (`brainyard.self-improve`): a check that costs nothing picks
  candidates, a judge drafts (`predictors/session/distill-tool.edn`), and
  what it drafts is a PROPOSAL, staged under
  `<project>/.brainyard/tools/proposals/<name>/` for a person to accept at
  `/proposals` (`brainyard.proposals`, kind `:tool`).

  WHEN it runs is the session tree's (loops/session.edn); this namespace knows
  how to find candidates, tell when they are worth a judge, render them for
  one, decide what a verdict means, replay a draft, and keep a proposal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [brainyard.history :as history]
            [brainyard.schema :as schema]
            [brainyard.self-improve :as self-improve]
            [brainyard.tool-lisp :as tl]
            [brainyard.toolbox :as toolbox]
            [brainyard.tools :as tools]))

;; --- shapes and candidates ----------------------------------------------------------

(defn- reader-param?
  "Whether `x` is a parameter the reader made up for a `#(…)` literal,
  `p__12#` (or `p2__13#`). Its number counts every one read in the process, so
  the same body read twice named its `%` apart, and no two `#(…)` bodies were
  ever of one shape (byj-gly.426)."
  [x]
  (and (symbol? x) (boolean (re-matches #"p\d*__\d+#" (name x)))))

(defn body-shape
  "A body's form with every string and number literal replaced by a
  placeholder, and each `#(…)` parameter by its order in the body: two bodies of one
  shape did the same thing to different data — `(read-file {:path
  \"docs/a\"})` and `(read-file {:path \"src/b\"})` are one shape. nil when
  the body does not read."
  [body]
  (try
    (let [form (tl/read-body body)
          ;; each made-up parameter by the order it first appears: the same
          ;; structure gets the same names, %1 and %2 still told apart
          params (into {} (map-indexed (fn [i p] [p (symbol (str "p" (inc i) "#"))]))
                       (distinct (filter reader-param? (tree-seq coll? seq form))))]
      (walk/postwalk (fn [x] (cond (string? x) ::string
                                   (number? x) ::number
                                   :else (get params x x)))
                     form))
    (catch Throwable _ nil)))

(defn- run-failed?
  [{:keys [tool-error? tool-result]}]
  (or tool-error?
      (str/starts-with? (str/triml (str tool-result)) "{:error")))

(defn run-calls
  "Every `tool$run` a turn made that came back with a value rather than an
  error: `{:body :args :result :full-chars}`."
  [turn]
  (for [{:keys [tool-results]} (:trajectory-iterations turn)
        {:keys [tool-name tool-args tool-result full-chars] :as r} tool-results
        :when (and (= "tool$run" (str tool-name)) (not (run-failed? r)))
        :let [body (or (:body tool-args) (get tool-args "body"))]
        :when (string? body)]
    {:body body
     :args (or (:args tool-args) (get tool-args "args") {})
     :result (str tool-result)
     :full-chars full-chars}))

(defn candidates
  "The runs of a turn worth a judge: only a turn that answered (a body that
  ran in a turn that failed is no evidence it was right), each with its shape
  and the turn it came from."
  [turn-number turn]
  (when (self-improve/succeeded? turn)
    (vec (for [r (run-calls turn)
               :let [shape (body-shape (:body r))]
               :when shape]
           (assoc r :turn-number turn-number :question (str (:question turn)) :shape shape)))))

(defn tool-calls-in
  "How many distinct tools a body calls, by the registry `reg`."
  [reg body]
  (try (count (tl/tool-refs (tl/read-body body) (toolbox/lookup reg)))
       (catch Throwable _ 0)))

(defn auto-signal
  "Are these candidates worth a judge now? Costs nothing; returns why, or nil:

  - `:repeated-body` — `repeats` (2) runs of one shape: done twice, it will be
    done again;
  - `:multi-tool-body` — a run that called `multi-tools` (2) or more distinct
    tools: a composition in its own right.

  The thresholds are the tree node's opts."
  [reg cands {:keys [repeats multi-tools] :or {repeats 2 multi-tools 2}}]
  (cond
    (some (fn [[_ n]] (>= n repeats)) (frequencies (map :shape cands))) :repeated-body
    (some #(>= (tool-calls-in reg (:body %)) multi-tools) cands) :multi-tool-body))

(defn candidates-text
  "The candidates as the judge reads them: each run's question, its args, its
  body whole, and the start of what it returned, numbered so the judge can
  give replay arguments per run."
  ([cands] (candidates-text cands 16000))
  ([cands total-chars]
   (let [per (max 1500 (quot total-chars (max 1 (count cands))))]
     (str/join "\n\n"
               (map-indexed
                (fn [i {:keys [turn-number question body args result]}]
                  (let [t (str "=== run " (inc i) " (turn " turn-number ") — " (history/clip question 160) " ===\n"
                               "args: " (pr-str args) "\n"
                               "body:\n" body "\n"
                               "returned: " (history/clip result 600))]
                    (if (> (count t) per) (str (subs t 0 per) "\n…[run truncated]") t)))
                cands)))))

(defn existing-tools-text
  "Every user tool there is, one `name — description` line each: what a
  distiller must not duplicate."
  [reg]
  (let [ts (sort-by :name (filter #(= :composite (:kind %)) (vals @reg)))]
    (if (seq ts)
      (str/join "\n" (for [t ts] (str (toolbox/user-tool-name (:name t)) " — " (history/clip (:description t) 160))))
      "(none)")))

;; --- verdicts -----------------------------------------------------------------------

(defn- score-of [x]
  (cond (number? x) (double x)
        (string? x) (try (Double/parseDouble (str/trim x)) (catch Throwable _ nil))
        :else nil))

(defn- read-edn-text
  "A field the judge wrote as EDN text — a schema, the replay args — or the
  value itself when it came as data."
  [x]
  (if (string? x)
    (let [s (str/trim x)]
      (when-not (str/blank? s) (try (edn/read-string s) (catch Throwable _ ::unreadable))))
    x))

(defn draft
  "A judge's verdict as a tool record — what `toolbox/compile-tool` reads —
  with the replay args per run: `{:record :replay-args}`, or nil when a
  field does not read."
  [{:keys [proposed-name description input-schema output-schema body replay-args]}]
  (let [in (read-edn-text input-schema)
        ;; a schema, or nothing: prose is refused (`toolbox/output-schema-problem`)
        out (when-not (and (string? output-schema) (str/blank? output-schema))
              (if (some? output-schema) (or (schema/read-output-schema output-schema) ::unreadable) nil))
        replay (read-edn-text replay-args)]
    (when-not (some #{::unreadable} [in out replay])
      {:record (cond-> {:name (str proposed-name) :description (str description) :body (str body)}
                 in (assoc :input-schema in)
                 out (assoc :output-schema out))
       :replay-args (if (sequential? replay) (vec replay) [])})))

(defn outcome
  "What a verdict comes to at `threshold`, before any replay: only
  `:draft-ok` goes on to be proved."
  [threshold {:keys [reusable score proposed-name body] :as verdict}]
  (let [score (score-of score)]
    (cond (nil? score) :no-score
          (not (true? reusable)) :not-reusable
          (< score threshold) :below-threshold
          (not (self-improve/valid-name? proposed-name)) :invalid-name
          (str/blank? (str body)) :empty-body
          ;; its own outcome, not :unreadable-draft: prose reads, it just
          ;; cannot be checked (byj-gly.263)
          (toolbox/output-schema-problem (:output-schema verdict)) :prose-output-schema
          (nil? (draft verdict)) :unreadable-draft
          :else :draft-ok)))

;; --- proving a draft ----------------------------------------------------------------

(def read-only-effects
  "What a replay may do: read. A draft that could write, run a process or
  call out is staged unproved rather than run on the judge's behalf."
  #{:fs/read})

(defn- same-result?
  "Does a replay's result match what the run returned? Whole, or — for a
  result the prompt cut, which records its `full-chars` — the same size and
  the same start."
  [replayed {:keys [result full-chars]}]
  (let [got (if (string? replayed) replayed (pr-str replayed))]
    (if full-chars
      (and (= full-chars (count got))
           (let [n (min 500 (count result))] (= (subs result 0 n) (subs got 0 (min n (count got))))))
      (= result got))))

(defn prove
  "Compile a draft against the live registry `reg` and, when it only reads,
  run it with each replay's args under `ctx` — with no permission channel, so
  a call that would ask a person is refused rather than asked — and compare
  with what the source run returned. Returns

    {:status :invalid   :problems […]}                — it does not compile
    {:status :unproved  :effects #{…}}                 — it could do more than read
    {:status :mismatch  :runs [{:run :matched? :got}]} — it does not reproduce a run
    {:status :no-replay}                               — the judge gave no args to replay
    {:status :verified  :runs […]}                     — it reproduces every run it was given

  and, whatever the status, `:tool` when it compiled."
  [reg ctx record replay-args sources]
  (let [{:keys [tool problems]} (toolbox/compile-tool reg record)]
    (if problems
      {:status :invalid :problems problems}
      (let [fx (toolbox/effects (atom (assoc @reg (:name tool) tool)) (:name tool))
            rctx (dissoc ctx :permission-fn)]
        (cond
          (not (every? read-only-effects fx)) {:status :unproved :effects fx :tool tool}
          (empty? replay-args) {:status :no-replay :tool tool}
          :else
          (let [runs (vec (for [{:keys [run args]} replay-args
                                :let [src (nth sources (dec (or run 1)) nil)]
                                :when src]
                            (let [got (tools/call-tool tool (or args {}) rctx)]
                              {:run run :matched? (same-result? got src)
                               :got (history/clip (if (string? got) got (pr-str got)) 300)})))]
            (cond (empty? runs) {:status :no-replay :tool tool}
                  (every? :matched? runs) {:status :verified :runs runs :tool tool}
                  :else {:status :mismatch :runs runs :tool tool})))))))

(defn stage?
  "Is a proved draft staged? A verified one, and one that could only be
  proved by doing more than reading — marked unproved for the person. One
  that does not compile, or does not reproduce what it was drafted from, is
  not."
  [{:keys [status]}]
  (contains? #{:verified :unproved} status))

;; --- proposals ----------------------------------------------------------------------

(defn proposals-dir
  "`<project>/.brainyard/tools/proposals`. It sits among the user tools but
  is none: the loader reads `<name>.edn` files, and passes over a directory."
  [config]
  (io/file (toolbox/tools-dir (:project-dir config)) "proposals"))

(defn write-proposal!
  "Stage a proved draft: `tool.edn` (what `persist!` writes, less the body),
  `body.clj`, and `proposal.edn` — why, from which runs, and what the replay
  found. A proposal of the same name is replaced."
  [config {:keys [record] :as proposal}]
  (let [nm (:name record)]
    (if-not (self-improve/valid-name? nm)
      {:error (str "invalid proposal name: " (pr-str nm))}
      (try
        (let [dir (io/file (proposals-dir config) nm)]
          (.mkdirs dir)
          (spit (io/file dir "body.clj") (tl/format-body (:body record)))
          (spit (io/file dir "tool.edn") (with-out-str (pprint/pprint (dissoc record :body))))
          (spit (io/file dir "proposal.edn")
                (pr-str (-> proposal
                            (dissoc :record)
                            ;; the compiled tool holds a fn: what is kept is the verdict
                            (update :proof dissoc :tool)
                            (assoc :created-ts (System/currentTimeMillis)))))
          {:name nm :path (.getPath dir)})
        (catch Throwable e {:error (str "could not stage proposal " nm ": " (ex-message e))})))))

(defn read-proposal
  [config nm]
  (when (self-improve/valid-name? nm)
    (let [dir (io/file (proposals-dir config) nm)
          f #(let [x (io/file dir %)] (when (.isFile x) (slurp x)))]
      (when-let [meta (f "proposal.edn")]
        (merge (try (edn/read-string meta) (catch Throwable _ {}))
               {:name nm :path (.getPath dir)
                :record (assoc (try (edn/read-string (f "tool.edn")) (catch Throwable _ {})) :body (f "body.clj"))})))))

(defn list-proposals
  "Every staged tool proposal, newest first."
  [config]
  (let [root (proposals-dir config)]
    (->> (when (.isDirectory root) (.listFiles root))
         (filter #(.isDirectory %))
         (keep #(read-proposal config (.getName %)))
         (sort-by #(- (or (:created-ts %) 0)))
         vec)))

(defn- drop-proposal! [config nm]
  (let [root (proposals-dir config)
        dir (io/file root (str nm))]
    ;; only a direct child of the staging root is ever deleted
    (when (and (self-improve/valid-name? nm) (.isDirectory dir)
               (= (.getCanonicalPath root) (.getCanonicalPath (.getParentFile (.getCanonicalFile dir)))))
      (doseq [x (reverse (file-seq dir))] (.delete x))
      true)))

(defn accept-proposal!
  "Make a proposal a user tool: written where `tool$create` writes, callable
  as `user$tool$<name>` once the session's tools are built again — which
  loads it, checking it against everything then bound. It was compiled and
  replayed when staged. A user tool of that name already there is not
  replaced: that is `tool$create`'s decision, with the old one in view."
  [config nm]
  (let [p (read-proposal config nm)
        dir (toolbox/tools-dir (:project-dir config))]
    (cond
      (nil? p) {:error (str "no staged tool proposal named '" nm "'")}
      (.isFile (io/file dir (str nm ".edn"))) {:error (str "a user tool named '" nm "' exists — reject this, or remove that one first")}
      :else (let [path (toolbox/persist! dir (:record p))]
              (drop-proposal! config nm)
              {:accepted true :name nm :path path}))))

(defn reject-proposal!
  [config nm]
  (if (drop-proposal! config nm)
    {:rejected nm}
    {:error (str "no staged tool proposal named '" nm "'")}))

(defn proposal-text
  "A staged proposal as a person reviews it: what it does, what it takes and
  gives, whether it was proved and on what, and the body."
  [{:keys [record rationale proof sources]}]
  (let [{:keys [description input-schema output-schema body]} record]
    (str description "\n\n"
         "input-schema: " (pr-str input-schema) "\n"
         "output-schema: " (if (string? output-schema) output-schema (pr-str output-schema)) "\n"
         "proof: " (case (:status proof)
                     :verified (str "replayed " (count (:runs proof)) " run(s) — each gave back what it gave then")
                     :unproved (str "not run: it could " (str/join ", " (map name (:effects proof)))
                                    " — review the body before accepting")
                     (pr-str proof))
         (when (seq rationale) (str "\nwhy: " rationale))
         (when (seq sources) (str "\nfrom: turn " (str/join ", " (distinct (map :turn-number sources)))))
         "\n\n" body)))
