;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.self-improve
  "Self-improvement (RFC-017) — brainyard's `skill_distill.clj`,
  `skill_refine.clj` and `skill_distill/proposals.clj`, the mechanism half.

  Two ways an agent's own work becomes a skill:

  - **distillation** writes a NEW skill from turns that succeeded in several
    steps: a model reads them and drafts a SKILL.md when they show a procedure
    worth repeating;
  - **refinement** revises an EXISTING skill after a turn that used it failed,
    when a model judges the SKILL.md itself to be at fault.

  Neither writes a live skill. What they draft is a PROPOSAL, staged under
  `<project>/.brainyard/skills/proposals/<name>/` as a `SKILL.md` and a
  `proposal.edn`, for a person to accept or reject with the session's
  `/skills` — brainyard's locked rule, since a skill is behaviour the agent
  will follow from then on.

  WHEN either runs is the session tree's (loops/session.edn), and what the
  model is asked is its predictors' (session/distill-skill,
  session/refine-skill). This namespace knows how to tell a candidate turn,
  when a window of them shows a procedure, how to render one for a model, how
  to decide what a verdict means, and how to keep a proposal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.history :as history]
            [brainyard.skills :as skills]
            [brainyard.trajectory :as trajectory]))

;; --- candidates ---------------------------------------------------------------------

(def min-action-steps
  "brainyard's `min-action-steps`: a turn of fewer steps carries no procedure."
  2)

(defn turn-steps
  "How many actions a turn took: its tool calls. brainyard also counts each
  top-level statement of a code block; this port's code runs through a tool
  call (`tool$run`), which is one step here."
  [turn]
  (count (mapcat :tool-results (:trajectory-iterations turn))))

(defn succeeded?
  "Did the turn reach an answer? brainyard reads its trajectory record's
  `:success`, which is the same judgement: an ending in
  `trajectory/succeeded-by`, with an answer."
  [turn]
  (and (contains? trajectory/succeeded-by (:terminated-by turn))
       (not (str/blank? (str (:answer turn))))))

(defn worth-scoring?
  "brainyard's `worth-scoring?`, the filter that costs nothing: a turn is a
  candidate for distillation only when it succeeded, answered, and took at
  least `min-action-steps` steps. Everything else never reaches a model."
  [turn]
  (and (succeeded? turn) (>= (turn-steps turn) min-action-steps)))

(defn trajectory-text
  "brainyard's `trajectory->text`: the turn as a model reads it — the question,
  each iteration's thought and tool calls with the start of each result, and
  the answer — cut at `max-chars` with a note saying so."
  ([turn] (trajectory-text turn 8000))
  ([turn max-chars]
   (let [t (str "Question: " (history/clip (:question turn) 1000)
                (apply str
                       (for [{:keys [iteration thought tool-results]} (:trajectory-iterations turn)
                             :when (or (seq tool-results) (not (str/blank? (str thought))))]
                         (str "\n\n[" iteration "]"
                              (when-not (str/blank? (str thought))
                                (str " thought: " (history/clip (str/trim (str thought)) 400)))
                              (apply str
                                     (for [{:keys [tool-name tool-args tool-result tool-error?]} tool-results]
                                       (str "\n  " tool-name " " (history/clip (pr-str tool-args) 400)
                                            (if tool-error? " ✗ " " → ")
                                            (history/clip (str/replace (str tool-result) #"\s+" " ") 800)))))))
                "\n\nAnswer: " (history/clip (str (:answer turn)) 1500))]
     (if (> (count t) max-chars)
       (str (subs t 0 max-chars) "\n…[trajectory truncated]")
       t))))

(defn procedure-shape
  "The tools a turn called, in order, with a run of one tool counted once:
  `[\"grep\" \"read-file\" \"edit-file\"]`. Two turns with the same shape
  carried out the same procedure, whatever they carried it out on."
  [turn]
  (vec (dedupe (map :tool-name (mapcat :tool-results (:trajectory-iterations turn))))))

(defn candidate
  "What the window keeps of a candidate turn: its number, question, steps and
  procedure shape, and the turn already rendered — the turn itself holds every
  tool result whole, and a window of twelve of them is not worth keeping in
  memory."
  [turn-number turn]
  {:turn-number turn-number
   :question (str (:question turn))
   :steps (turn-steps turn)
   :shape (procedure-shape turn)
   :text (trajectory-text turn)})

(defn auto-signal
  "Should the window be judged now? Checked after every turn, costing nothing
  — brainyard's rule that a cheap filter comes before any model call. Returns
  why, or nil:

  - `:repeated-procedure` — `repeats` candidate turns (2) followed the same
    procedure shape of at least `min-shape` tools (2). A procedure done twice
    is the plainest sign that it will be done again.
  - `:long-procedure` — a candidate turn took `long-steps` steps (8) or more,
    a procedure in its own right.

  The thresholds are the tree node's opts, so they are tuned without a rebuild."
  [candidates {:keys [repeats min-shape long-steps] :or {repeats 2 min-shape 2 long-steps 8}}]
  (cond
    (some (fn [[shape n]] (and (>= (count shape) min-shape) (>= n repeats)))
          (frequencies (map :shape candidates)))
    :repeated-procedure

    (some #(>= (or (:steps %) 0) long-steps) candidates)
    :long-procedure))

(defn window-text
  "brainyard's `batch->text`: the candidates of a window, each under a
  `=== turn N — question ===` header, sharing `total-chars` but each allowed at
  least 1500."
  ([candidates] (window-text candidates 24000))
  ([candidates total-chars]
   (let [per (max 1500 (quot total-chars (max 1 (count candidates))))]
     (str/join "\n\n"
               (for [{:keys [turn-number question text]} candidates]
                 (str "=== turn " turn-number " — " (history/clip question 120) " ===\n"
                      (if (> (count text) per)
                        (str (subs text 0 per) "\n…[trajectory truncated]")
                        text)))))))

(defn existing-skills-text
  "Every skill there is, one `name — description` line each: what a
  distiller must not duplicate."
  [config]
  (let [lines (for [s (skills/resolve-names (skills/list-skills config))
                    :when (not (:shadowed? s))]
                (str (:name s) " — " (history/clip (:description s) 160)))]
    (if (seq lines) (str/join "\n" lines) "(none)")))

;; --- verdicts -----------------------------------------------------------------------

(defn valid-name?
  "brainyard's `valid-name-guard`: lowercase-kebab with a leading letter."
  [s]
  (boolean (and (string? s) (re-matches #"[a-z][a-z0-9-]*" s))))

(defn- score-of [x]
  (cond (number? x) (double x)
        (string? x) (try (Double/parseDouble (str/trim x)) (catch Throwable _ nil))
        :else nil))

(defn distill-outcome
  "brainyard's `stage-proposal!` decision, as a value: what a distiller's
  verdict comes to at `threshold`. Only `:staged` writes anything."
  [threshold {:keys [reusable score proposed-name skill-md]}]
  (let [score (score-of score)]
    (cond (nil? score) :no-score
          (not (true? reusable)) :not-reusable
          (< score threshold) :below-threshold
          (not (valid-name? proposed-name)) :invalid-name
          (str/blank? (str skill-md)) :empty-skill-md
          :else :staged)))

(defn refine-outcome
  "brainyard's `stage-refinement!` decision, as a value."
  [{:keys [should-revise revised-md]}]
  (cond (nil? should-revise) :no-score
        (not (true? should-revise)) :no-revision
        (str/blank? (str revised-md)) :empty-revised-md
        :else :staged))

;; --- refinement triggers ------------------------------------------------------------

(defn skill-calls
  "Every `skill$…` call a turn made: the tool id, the args, what came back and
  whether it failed."
  [turn]
  (for [{:keys [tool-results]} (:trajectory-iterations turn)
        {:keys [tool-name] :as r} tool-results
        :when (str/starts-with? (str tool-name) "skill$")]
    r))

(defn refine-triggers
  "The skills a turn gives reason to judge, with the evidence — brainyard's
  two triggers:

  - a skill call that itself failed (brainyard: a delegated skill returning
    `:error`), whatever became of the turn;
  - every skill the turn loaded, when the turn did not succeed. A cancelled
    turn is not a failure: the person stopped it.

  Only a brainyard skill can be revised — another harness's is read, never
  written — so one of those is not judged at all (brainyard judges it and then
  cannot accept the revision). One trigger per skill; the failed call wins."
  [config turn]
  (let [by-id (into {} (map (juxt :id identity)) (skills/resolve-names (skills/list-skills config)))
        failed-turn? (and (not (succeeded? turn)) (not= :cancelled (:terminated-by turn)))
        calls (skill-calls turn)]
    (->> (concat (for [c calls :when (:tool-error? c)] [c :failed-call])
                 (when failed-turn? (for [c calls :when (not (:tool-error? c))] [c :failed-turn])))
         (keep (fn [[{:keys [tool-name tool-args tool-result]} why]]
                 (when-let [s (by-id tool-name)]
                   (when (= :brainyard (:backend s))
                     {:skill (:name s) :id tool-name :args tool-args :why why
                      :path (:path s) :result tool-result}))))
         (reduce (fn [acc t] (if (some #(= (:skill t) (:skill %)) acc) acc (conj acc t))) [])
         vec)))

(defn failure-evidence
  "What the refiner reads about the failure: the call's error for a failed
  call; for a failed turn, brainyard's `turn-evidence` — how it ended, the
  question, the answer and the trace — capped at `max-chars`."
  ([trigger turn] (failure-evidence trigger turn 6000))
  ([{:keys [why result]} turn max-chars]
   (let [t (if (= :failed-call why)
             (str "The skill call failed: " (str result))
             (str "The turn did NOT succeed (terminated-by " (some-> (:terminated-by turn) name) ").\n"
                  "Question: " (history/clip (:question turn) 500) "\n"
                  "Final answer: " (history/clip (:answer turn) 1000) "\n\n"
                  "Trace:\n" (trajectory-text turn max-chars)))]
     (if (> (count t) max-chars) (str (subs t 0 max-chars) "\n…[evidence truncated]") t))))

;; --- proposals ----------------------------------------------------------------------

(defn proposals-dir
  "brainyard's staging root: `<project>/.brainyard/skills/proposals`. It sits
  among the skills but is none — it holds no SKILL.md of its own, so the
  skills loader passes over it."
  [config]
  (io/file (skills/skills-dir config :project) "proposals"))

(defn write-proposal!
  "brainyard's `write-proposal!`: stage `skill-md` under the proposal's name
  with its metadata beside it. A proposal of the same name is replaced."
  [config {:keys [name skill-md] :as proposal}]
  (if-not (valid-name? name)
    {:error (str "invalid proposal name: " (pr-str name))}
    (try
      (let [dir (io/file (proposals-dir config) name)]
        (.mkdirs dir)
        (spit (io/file dir "SKILL.md") skill-md)
        (spit (io/file dir "proposal.edn")
              (pr-str (-> proposal
                          (dissoc :skill-md)
                          (update :kind #(or % :distillation))
                          (assoc :created-ts (System/currentTimeMillis)))))
        {:name name :path (.getPath dir)})
      (catch Throwable e {:error (str "could not stage proposal " name ": " (ex-message e))}))))

(defn read-proposal
  "One staged proposal — its metadata and `:skill-md` — or nil."
  [config name]
  (when (valid-name? name)
    (let [dir (io/file (proposals-dir config) name)
          meta-f (io/file dir "proposal.edn")
          md-f (io/file dir "SKILL.md")]
      (when (.isFile meta-f)
        (merge (try (edn/read-string (slurp meta-f)) (catch Throwable _ {}))
               {:name name :path (.getPath dir)
                :skill-md (when (.isFile md-f) (slurp md-f))})))))

(defn list-proposals
  "Every staged proposal's metadata, newest first."
  [config]
  (let [root (proposals-dir config)]
    (->> (when (.isDirectory root) (.listFiles root))
         (filter #(.isDirectory %))
         (keep #(read-proposal config (.getName %)))
         (map #(dissoc % :skill-md))
         (sort-by #(- (or (:created-ts %) 0)))
         vec)))

(defn- drop-proposal! [config name]
  (let [root (proposals-dir config)
        dir (io/file root name)]
    ;; only a direct child of the staging root is ever deleted
    (when (and (valid-name? name) (.isDirectory dir)
               (= (.getCanonicalPath root) (.getCanonicalPath (.getParentFile (io/file (.getCanonicalPath dir))))))
      (doseq [f (reverse (file-seq dir))] (.delete f))
      true)))

(defn accept-proposal!
  "brainyard's `accept-proposal!`: a refinement UPDATES the skill it revises,
  wherever it lives; a distillation CREATES one, in the project unless
  `scope` says otherwise. The proposal is dropped only once the write
  succeeded — a failed one stays staged to try again."
  [config reg run-turn name & [scope]]
  (let [p (read-proposal config name)]
    (cond
      (nil? p) {:error (str "no staged proposal named '" name "'")}
      (str/blank? (str (:skill-md p))) {:error (str "proposal '" name "' has no SKILL.md content")}
      :else
      (let [op (if (= :refinement (:kind p)) "update" "create")
            r (skills/write! config reg run-turn
                             (cond-> {:op op :skill-name name :content (:skill-md p)}
                               (= "create" op) (assoc :scope (or scope "project"))
                               (and (= "update" op) scope) (assoc :scope scope)))]
        (if (:error r)
          r
          (do (drop-proposal! config name)
              (assoc r :accepted true :op op)))))))

(defn reject-proposal!
  [config name]
  (if (drop-proposal! config name)
    {:rejected name}
    {:error (str "no staged proposal named '" name "'")}))
