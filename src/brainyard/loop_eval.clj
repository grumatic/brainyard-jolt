;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.loop-eval
  "A candidate loop run as a clone of the agent on a task set, beside the loop
  in force, and the two compared (RFC-071 \"The clone\", \"Judgement\",
  byj-ng7.4). Not in brainyard, which measures predictors on datasets
  (RFC-021) but never a loop on the project's own work.

  The clone is the agent with only its loop replaced: the run's config, model
  and tools. Each run of a task is a turn of its own:

  - in a **clone of the repository** at the task's start commit (`git clone
    --shared`, then a detached checkout), not a worktree, which would share
    the project's refs and so its `refs/byj/turns` (RFC-069);
  - with that clone as its project and working directory, so what the turn
    leaves — sessions, logs, checkpoints — is the clone's, and the only
    directory its path gate allows (`:allowed-dirs`);
  - headless: a gate that would ask a person refuses, in both arms alike, and
    `:auto-approve` is read as `:ask-each-time`, so nothing outside the clone
    is reached unasked; what reaches beyond the machine — `git push`, a share,
    another agent — is refused outright;
  - under a spend cap for the whole evaluation: no run starts once the runs
    so far cost it, and an evaluation stopped by it decides nothing.

  The arms run the same tasks, alternately, `:repeats` times each. A task is
  scored by its machine checks (`sim/checks`), the fraction passed, averaged
  over its repeats; a task with none is counted and not scored (a judge
  model, byj-ng7.8). The primary metric is compared per task with RFC-021's
  paired `compare-scores`; a candidate `:aim :cost` is held to
  non-inferiority instead — not worse past the margin, and cheaper. Each
  guard metric — cost, tokens, iterations, wall time, tool errors, refusals
  — has a bound on the candidate's mean against the base's, and a regression
  past one rejects it whatever the primary metric says.

  Mechanism only (RFC-001): which candidate, on which tasks, with what bounds
  is the caller's. What runs a turn is injected (`:run-turn`), the agent's
  own `ask` by default."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.evaluate :as evaluate]
            [brainyard.flow :as flow]
            [brainyard.loop-proposals :as loop-proposals]
            [brainyard.loop-tasks :as loop-tasks]
            [brainyard.project-loops :as project-loops]
            [brainyard.sim :as sim]
            [brainyard.version :as version]))

;; --- what the clone may reach ------------------------------------------------------------

(def reach-out-tools
  "Tools refused in a clone's turn: what reaches past the machine or into the
  person's other sessions."
  ["share$*" "claude$*" "codex$*" "session$send" "agent-registry$*"])

(def reach-out-commands
  "Shell commands refused in a clone's turn."
  ["git push*" "byj-share *" "gh *"])

(defn clone-config
  "The run's `config` as a clone's turn in `dir` takes it: the project and the
  working dir are the clone's, and the only allowed directory; asking a
  person refuses; reaching beyond the machine is refused."
  [config dir]
  (-> config
      (assoc :project-dir dir :working-dir dir :allowed-dirs [dir]
             :persist-session? false :recap? false)
      (update :permission-mode #(if (= :auto-approve %) :ask-each-time %))
      (update :tool-deny-tools #(into (vec %) reach-out-tools))
      (update :bash-deny-commands #(into (vec %) reach-out-commands))
      (update :tool-deny-effects #(conj (set %) :share/publish))))

;; --- a clone --------------------------------------------------------------------------

(defn- git [dir & args]
  (try (let [r (apply fx/sh {:dir (str dir) :out :string :err :string} "git" args)]
         {:exit (:exit r) :out (str (:out r)) :err (str (:err r))})
       (catch Throwable e {:exit nil :out "" :err (ex-message e)})))

(defn repo-root
  "The git root of the project, or nil."
  [project-dir]
  (let [{:keys [exit out]} (git project-dir "rev-parse" "--show-toplevel")]
    (when (= 0 exit) (str/trim out))))

(defn make-clone!
  "A clone of `root` at commit `start`, in `dir`: `--shared`, so it costs no
  copy of the objects, and checked out detached. `{:dir}` or `{:error}`."
  [root start dir]
  (let [c (git "." "clone" "-q" "--shared" "--no-checkout" (str root) (str dir))]
    (if (not= 0 (:exit c))
      {:error (str "git clone: " (str/trim (:err c)))}
      (let [co (git dir "checkout" "-q" "--detach" (str start))]
        (if (not= 0 (:exit co))
          {:error (str "git checkout " start ": " (str/trim (:err co)))}
          {:dir (.getCanonicalPath (io/file dir))})))))

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (.delete x)))

;; --- one run ----------------------------------------------------------------------------

(defn- call-results [trajectory]
  (for [it trajectory r (:tool-results it)] r))

(defn- refused? [r]
  (boolean (re-find #"refused" (str (or (:error r) (:tool-result r) (:error-message r))))))

(defn- errored? [r]
  (boolean (or (:tool-error? r) (:error r) (:error-message r))))

(defn run-metrics
  "What one run of a turn cost and did, from what `agent/ask` returns:
  `{:cost :tokens :iterations :tool-errors :refusals}`."
  [{:keys [usage iterations trajectory]}]
  (let [rs (call-results trajectory)]
    {:cost (double (or (:cost usage) 0.0))
     :tokens (+ (or (:input-tokens usage) 0) (or (:output-tokens usage) 0))
     :iterations (or iterations 0)
     :tool-errors (count (filter errored? rs))
     :refusals (count (filter refused? rs))}))

(defn default-run-turn
  "The agent's own turn, on the clone: `agent/ask` with the arm's tree."
  [{:keys [lm config tree question]}]
  ((requiring-resolve 'brainyard.agent/ask) question {:lm lm :config config :tree tree}))

(defn run-one!
  "Run `task` once with `tree` in a fresh clone under `work`: `{:task :arm
  :repeat :score :checks :wall-ms …metrics}` or with `:error`. The clone is
  removed after unless `keep?`."
  [{:keys [root work lm config run-turn keep?]} task arm tree rep]
  (let [dir (io/file work (str (:id task) "-" (name arm) "-" rep))
        clone (make-clone! root (:start task) dir)
        row {:task (:id task) :arm arm :repeat rep}]
    (try
      (if-let [e (:error clone)]
        (assoc row :error e :score 0.0)
        (let [t0 (System/currentTimeMillis)
              r (try ((or run-turn default-run-turn)
                      {:lm lm :config (clone-config config (:dir clone)) :tree tree :question (:question task)})
                     (catch Throwable e {:error (ex-message e)}))
              wall (- (System/currentTimeMillis) t0)
              cs (sim/checks (:dir clone) (:checks task))
              passed (count (filter :pass? cs))]
          (merge row (run-metrics r)
                 {:wall-ms wall
                  :score (if (seq cs) (/ (double passed) (count cs)) 0.0)
                  :checks (mapv #(select-keys % [:name :pass?]) cs)}
                 (when (:error r) {:error (:error r)}))))
      (finally (when-not keep? (delete-tree! dir))))))

;; --- judgement ---------------------------------------------------------------------------

(def default-guards
  "Each guard metric's bound on the candidate's mean per run against the
  base's: a regression is a mean above `max(base × :ratio, base + :slack)`."
  {:cost {:ratio 1.5 :slack 0.0}
   :tokens {:ratio 1.5 :slack 0}
   :iterations {:ratio 1.5 :slack 1}
   :wall-ms {:ratio 2.0 :slack 1000}
   :tool-errors {:ratio 1.5 :slack 1}
   :refusals {:ratio 1.5 :slack 1}})

(defn- mean [xs] (when (seq xs) (/ (double (reduce + xs)) (count xs))))

(defn arm-summary
  "An arm's rows as RFC-021's score: `{:score :example-scores :n :runs
  :means}`, `:example-scores` a task's mean score, in `task-ids` order."
  [rows task-ids]
  (let [by-task (group-by :task rows)]
    {:score (or (mean (map :score rows)) 0.0)
     :example-scores (mapv #(or (mean (map :score (by-task %))) 0.0) task-ids)
     :n (count task-ids)
     :runs (count rows)
     :means (into {} (for [k (keys default-guards)] [k (or (mean (map #(or (get % k) 0) rows)) 0.0)]))}))

(defn guard-regressions
  "The guard metrics on which `cand` regressed past their bound against
  `base` (`arm-summary`s): `[{:metric :base :candidate :bound}]`."
  [base cand guards]
  (vec (for [[k {:keys [ratio slack]}] guards
             :let [b (get-in base [:means k] 0.0)
                   c (get-in cand [:means k] 0.0)
                   bound (max (* b ratio) (+ b slack))]
             :when (> c bound)]
         {:metric k :base b :candidate c :bound bound})))

(defn verdict
  "What the comparison says, for `base` and `cand` `arm-summary`s:
  `{:verdict :better|:cheaper|:not-better|:guard-regressed :primary
  :guards}`. `:aim :cost` asks for non-inferiority and a lower cost. A tie
  keeps the loop in force."
  [base cand {:keys [aim guards]}]
  (let [regs (guard-regressions base cand (merge default-guards guards))
        better (evaluate/compare-scores cand base)
        worse (evaluate/compare-scores base cand)
        cheaper? (< (get-in cand [:means :cost] 0.0) (get-in base [:means :cost] 0.0))]
    {:primary {:candidate (:score cand) :base (:score base) :compare better}
     :guards regs
     :verdict (cond
                (seq regs) :guard-regressed
                (= :cost aim) (if (and (not (:better? worse)) cheaper?) :cheaper :not-better)
                (:better? better) :better
                :else :not-better)}))

(defn wins?
  "Does a verdict let a candidate be staged (RFC-071 \"Judgement\")?"
  [v]
  (contains? #{:better :cheaper} (:verdict v)))

;; --- an evaluation --------------------------------------------------------------------

(defn evals-dir
  "`<project>/.brainyard/loops/evals`, where reports are kept."
  [config]
  (io/file (project-loops/loops-dir (loop-proposals/project-dir config)) "evals"))

(defn- read-candidate [candidate]
  (cond
    (vector? candidate) candidate
    (.isFile (io/file (str candidate))) (edn/read-string (slurp (io/file (str candidate))))
    :else nil))

(defn loop-of
  "Which loop a candidate is for: the `:loop` of the `:based-on` it carries,
  else its file's name when that names a bundled loop (`react.edn`), else
  react."
  [candidate]
  (let [t (try (read-candidate candidate) (catch Throwable _ nil))
        base (when (vector? t) (project-loops/tree-based-on t))
        stem (when-not (vector? candidate)
               (second (re-matches #"(.+)\.edn" (.getName (io/file (str candidate))))))]
    (or (some-> (:loop base) project-loops/loop-name)
        (when (and stem (project-loops/bundled-tree stem)) stem)
        "react")))

(defn evaluate!
  "Run `candidate` (a tree, or a file holding one) against the loop in force
  for `:loop` (react when nil) on task set `:tasks`, and write the report:
  `{:file :report}` or `{:error}`.

  opts: `:lm`, `:loop`, `:tasks` (a set name), `:repeats` (1), `:max-cost`
  (USD, none when nil), `:aim` (`:cost` for non-inferiority), `:guards`
  (over `default-guards`), `:run-turn`, `:work` (where clones go: a temp
  dir), `:check-opts` (`flow check`'s), `:keep?` (keep the clones).

  The report: `{:set :set-hash :loop :base :candidate :byj :model :repeats
  :aim :tasks :unscored :runs :arms :primary :guards :verdict :spent
  :stopped}`."
  [config {:keys [lm tasks repeats max-cost aim work check-opts] :as opts}]
  (let [ln (or (project-loops/loop-name (or (:loop opts) "react")) "react")
        cand-tree (read-candidate (:candidate opts))
        set-data (loop-tasks/read-set config (or tasks loop-tasks/default-set))
        root (repo-root (loop-proposals/project-dir config))
        problems (when cand-tree (loop-proposals/problems config cand-tree check-opts))]
    (cond
      (nil? cand-tree) {:error (str "no candidate loop: " (:candidate opts) " is not a file holding a tree")}
      problems {:error (str "the candidate does not check: " (:problem (first problems))) :problems (vec problems)}
      (nil? set-data) {:error (str "no task set " (or tasks loop-tasks/default-set)
                                   " — byj loops tasks build makes one")}
      (nil? root) {:error "the project is not a git repository: a task starts at a commit"}
      :else
      (let [scored (filterv #(seq (:checks %)) (:tasks set-data))
            unscored (- (count (:tasks set-data)) (count scored))
            repeats (max 1 (or repeats 1))
            base-tree ((requiring-resolve 'brainyard.agent/load-tree) ln (loop-proposals/project-dir config))
            cand (loop-proposals/expand config cand-tree)
            work (or work (str (doto (io/file (System/getProperty "java.io.tmpdir")
                                              (str "byj-loop-eval-" (System/currentTimeMillis) "-" (rand-int 100000)))
                                 (.mkdirs))))
            ctx {:root root :work work :lm lm :config config :run-turn (:run-turn opts) :keep? (:keep? opts)}
            plan (for [rep (range repeats) t scored arm [:base :candidate]] [t arm rep])
            {:keys [rows spent stopped]}
            (reduce (fn [{:keys [spent] :as acc} [t arm rep]]
                      (if (and max-cost (>= spent max-cost))
                        (reduced (assoc acc :stopped (str "the spend cap, $" max-cost ", was reached at $"
                                                          (format "%.4f" spent))))
                        (let [row (run-one! ctx t arm (if (= :base arm) base-tree cand) rep)]
                          (-> acc (update :rows conj row) (update :spent + (:cost row 0.0))))))
                    {:rows [] :spent 0.0 :stopped nil}
                    plan)
            ids (mapv :id scored)
            base (arm-summary (filter #(= :base (:arm %)) rows) ids)
            cand-sum (arm-summary (filter #(= :candidate (:arm %)) rows) ids)
            v (cond
                stopped {:verdict :incomplete}
                (empty? scored) {:verdict :incomplete}
                :else (verdict base cand-sum {:aim aim :guards (:guards opts)}))
            report (merge {:set (or tasks loop-tasks/default-set) :set-hash (:hash set-data)
                           :loop ln
                           :base (assoc (loop-proposals/in-force config ln) :expanded-hash (flow/tree-hash base-tree))
                           :candidate {:hash (flow/tree-hash cand-tree) :expanded-hash (flow/tree-hash cand)
                                       :file (when-not (vector? (:candidate opts)) (str (:candidate opts)))}
                           :byj (version/version) :model (select-keys lm [:provider :model])
                           :repeats repeats :aim (or aim :checks)
                           :tasks (count scored) :unscored unscored
                           :runs rows :arms {:base base :candidate cand-sum}
                           :spent spent :stopped stopped}
                          v)
            dir (evals-dir config)
            f (io/file dir (str ln "-" (System/currentTimeMillis) ".edn"))]
        (when-not (:work opts) (delete-tree! work))
        (.mkdirs dir)
        (spit f (with-out-str (pp/pprint report)))
        {:file (.getPath f) :report report}))))

(defn summary-lines
  "What `byj loops eval` prints of a report."
  [{:keys [file report]}]
  (let [{:keys [verdict primary guards arms tasks unscored repeats spent stopped]} report
        c (:compare primary)]
    (concat
     [(str "verdict: " (name verdict)
           (case verdict
             :better " — the candidate passes more checks, past the margin"
             :cheaper " — not worse past the margin, and cheaper"
             :not-better " — the loop in force is kept"
             :guard-regressed " — a guard metric regressed past its bound"
             :incomplete " — nothing is decided"
             ""))
      (str "tasks: " tasks " scored" (when (pos? (or unscored 0)) (str ", " unscored " with no machine check not scored"))
           " · repeats " repeats " · spent $" (format "%.4f" (double (or spent 0))))]
     (when stopped [(str "stopped: " stopped)])
     (when primary
       [(format "checks passed: candidate %.3f, base %.3f%s" (double (:candidate primary)) (double (:base primary))
                (if (:margin c) (format " (diff %.3f, margin %.3f%s)" (double (:diff c)) (double (:margin c))
                                        (if (:paired? c) ", paired" ""))
                    ""))])
     (for [{:keys [metric base candidate bound]} guards]
       (format "guard %s: candidate %.2f against base %.2f, bound %.2f" (name metric) candidate base bound))
     (when arms
       [(str "base means " (pr-str (:means (:base arms))))
        (str "candidate means " (pr-str (:means (:candidate arms))))])
     [(str "report: " file)])))
