;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.workflow
  "/workflow (RFC-076): the `workflow$*` commands agent$workflow's loop
  (`brainyard/loops/workflow.edn`) and its turns call to keep a project's
  workflows — the named graphs of stages `.brainyard/deps.edn` holds under
  `:workflows` (RFC-075, byj-dls) — and to run one.

  Keeping them: `workflow$list` and `workflow$show` read them; `workflow$save`
  and `workflow$remove` change one, held to what `byj deps write` holds a
  spec to, shown to the person and written through `deps/write!` only on
  their yes, as `profile$edit` writes the profile.

  Running one (`byj-edw.2`): `workflow$start` opens a run — kept as
  `.brainyard/workflow-runs/<n>.edn` — and gives its first stage's brief;
  each stage is a ReAct turn the tree includes; `workflow$check` says what
  the turn left and whether what the stage hands on is ready
  (`deps/run-give-row`); the master role's judgement is the predictor
  `workflow/judge`'s; `workflow$settle` hands on to the next stage, has the
  stage go again, or stops the run, under the profile's limits; and
  `workflow$report` is the answer. The order is the tree's.

  Where a stage works (`byj-edw.3`): in this turn, in the project — or,
  for a run in a worktree of its own and for a role whose `:session` is
  `:own`, in a headless session of its own (`teams/start-session!`), in
  a worktree of its own when the role says `:worktree`, asked its brief
  over the session channel by `workflow$delegate`. A stage whose role is
  `:upstream` works where the stage handing to it did. Each stage's session
  is bound to its role in `deps-local.edn`, and each handoff kept there.

  Mechanism only (RFC-001). Every word a model or the person reads is
  `resources/brainyard/workflow/words.edn`'s.

  brainyard has no counterpart; the workbench authors and runs these
  workflows from its own UI, and this is byj's own way to, from a session."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.channel :as channel]
            [brainyard.deps :as deps]
            [brainyard.drive :as drive]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.profile :as profile]
            [brainyard.tasks :as tasks]
            [brainyard.teams :as teams]
            [brainyard.which :as which]
            [brainyard.worktree :as worktree]))

(def words-resource "brainyard/workflow/words.edn")

(defn words [] (edn/read-string (slurp (io/resource words-resource))))

(defn- fill
  "`s` with each `{name}` of `vars` filled in one pass, so a value filled in
  — a stage's answer, which may hold braces of its own — is never filled
  again."
  [s vars]
  (str/replace (str s) #"\{([A-Za-z-]+)\}"
               (fn [[m k]] (let [k (keyword k)] (if (contains? vars k) (str (get vars k)) m)))))

(defn- dir-of [config args]
  (or (not-empty (str (:dir args))) (:project-dir config) (:working-dir config) "."))

(defn- id-name [k] (if (keyword? k) (name k) (str k)))

(defn- given
  "`v` when a tool's caller gave it: not nil, not a blank string."
  [v]
  (when-not (or (nil? v) (and (string? v) (str/blank? v))) v))

(defn- ask-person
  "Ask the person through the turn's permission channel (RFC-012), as
  profile$edit does: nil when they allow it, else why not."
  [ctx request]
  (if-let [pf (:permission-fn ctx)]
    (let [r (try (pf request) (catch Throwable e {:denied true :reason (ex-message e)}))]
      (when-not (:allowed r) (or (not-empty (str (:reason r))) "declined")))
    "there is no person to ask in this run"))

;; --- a workflow, in words ---------------------------------------------------------

(defn order
  "The stages of workflow `w` in the order a run works them: each after
  every stage that links to it, ties by name — the order `byj deps write`
  keeps them in. Stages a cycle would hold back come last, by name."
  [{:keys [stages links]}]
  (let [ids (sort-by id-name (keys stages))
        known (set ids)
        preds (reduce (fn [m {:keys [from to]}] (update m to (fnil conj #{}) from)) {} links)]
    (loop [done [] left ids]
      (if (empty? left)
        done
        (if-let [s (first (filter (fn [s] (every? (set done) (filter known (preds s)))) left))]
          (recur (conj done s) (remove #{s} left))
          (into done left))))))

(defn give-words
  "Give `g` of a link, in words: a `:bead` give, deprecated, as the issue
  it is (`deps/bead-as-issue`)."
  [w g]
  (let [gw (:gives w)
        g (deps/bead-as-issue g)]
    (case (:kind g)
      :file (fill (:file gw) {:paths (str/join ", " (:paths g))})
      :issue (if (:id g) (fill (:issue gw) {:id (:id g)}) (fill (:issues gw) {:parent (:parent g)}))
      :branch (:branch gw)
      :note (:note gw)
      (id-name (:kind g)))))

(defn gives-words [w gives]
  (if (seq gives) (str/join ", " (map #(give-words w %) gives)) (:no-gives w)))

(defn role-words
  "Role `r` of `spec`: its id, its title and what it does."
  [spec r]
  (let [{:keys [title does]} (get-in spec [:roles r])]
    (str (id-name r) (when (given title) (str " (" title ")")) (when (given does) (str " — " does)))))

(defn describe
  "Workflow `id` of `spec`, `wf`, as lines: its title and purpose, its
  stages in a run's order, each stage's role, each link's gives, and its
  master."
  [w spec id wf]
  (let [{:keys [title does master stages links]} wf]
    (vec (concat
          [(str (id-name id) (when (given title) (str " — " title)) (when (given does) (str ": " does)))
           (str "  runs: " (str/join " → " (map id-name (order wf))))]
          (for [s (order wf)
                :let [st (get stages s) r (deps/stage-role s st)]]
            (str "  stage " (id-name s) (when (given (:title st)) (str " (" (:title st) ")"))
                 ": role " (role-words spec r)
                 (when (given (:does st)) (str "; here: " (:does st)))))
          (for [{:keys [from to gives]} links]
            (str "  link " (id-name from) " → " (id-name to) ": gives " (gives-words w gives)))
          [(str "  master: " (if master (role-words spec master) "none"))]))))

(defn- workflow-errors
  "The errors `byj deps write` would refuse `spec`'s workflows with: a
  stage or master playing no role, a link naming no stage, a cycle."
  [dir spec]
  (deps/structural-errors dir (select-keys spec [:roles :workflows]) []))

;; --- reading ------------------------------------------------------------------------

(declare runs run-line)

(defn list-workflows
  "`workflow$list`: each of the project's workflows, its stages in order
  and its master; the roles they can play; and what stands in the way of
  writing the spec. `{:text}`."
  [config args]
  (let [dir (dir-of config args)
        w (words)
        {spec :value probs :problems} (deps/read-spec dir)
        ws (sort-by (comp id-name key) (:workflows spec))
        errs (workflow-errors dir spec)
        all-runs (runs dir)]
    {:text (str/join
            "\n"
            (concat
             (if (empty? ws)
               [(:none w)]
               (cons "Workflows (.brainyard/deps.edn):"
                     (for [[id wf] ws]
                       (str "\n" (id-name id)
                            (when (given (:title wf)) (str " — " (:title wf)))
                            (when (given (:does wf)) (str ": " (:does wf)))
                            "\n  " (str/join " → " (map id-name (order wf)))
                            (when (:master wf) (str "; master " (id-name (:master wf))))
                            (when-let [r (first (filter #(= id (:workflow %)) all-runs))]
                              (str "\n  last run " (run-line r)))))))
             [""
              (if (seq (:roles spec))
                (str "Roles: " (str/join "; " (for [r (sort-by id-name (keys (:roles spec)))] (role-words spec r))))
                (:roles-none w))]
             (when (seq errs) (cons "\nErrors:" (map #(str "- " (:message %)) errs)))
             (when (seq probs) (cons "\nProblems reading it:" (map #(str "- " (:key %) " " (:message %)) probs)))))}))

(defn show-workflow
  "`workflow$show`: one workflow whole — its stages, the role each plays
  and what that role does, each link's gives, its master — and the errors
  that name it. `{:text :found?}`."
  [config args]
  (let [dir (dir-of config args)
        w (words)
        {spec :value} (deps/read-spec dir)
        id (keyword (str/replace (str/trim (str (:id args))) #"^:" ""))
        wf (get-in spec [:workflows id])]
    (if-not wf
      {:found? false
       :text (str "No workflow " (id-name id) " in .brainyard/deps.edn."
                  (when-let [ws (seq (keys (:workflows spec)))]
                    (str " It has: " (str/join ", " (sort (map id-name ws))) ".")))}
      {:found? true
       :text (str/join "\n" (concat (describe w spec id wf)
                                    (for [e (workflow-errors dir spec)
                                          :when (= id (get-in e [:target :workflow]))]
                                      (str "  error: " (:message e)))
                                    (for [r (take 5 (filter #(= id (:workflow %)) (runs dir)))]
                                      (str "  run " (run-line r)))))})))

;; --- changing one -------------------------------------------------------------------

(defn- parse-value
  "A tool argument that holds data — a workflow's stages, links or roles:
  as given when the caller passed a map or a list, else read as EDN, else as
  JSON. `{:ok v}`, `{:ok nil}` when not given, or `{:error}`."
  [v]
  (cond (or (map? v) (sequential? v)) {:ok v}
        (nil? (given v)) {:ok nil}
        :else (let [s (str/trim (str v))]
                (try {:ok (edn/read-string s)}
                     (catch Throwable _
                       (try {:ok (json/read-str s)}
                            (catch Throwable e {:error (str "neither EDN nor JSON (" (ex-message e) ")")})))))))

(defn- problems-text [probs]
  (str/join "; " (map #(str (or (not-empty (str (:key %))) "it") " " (:message %)) probs)))

(defn- shown-change
  "What changes from workflow lines `before` to `after`, a line each: the
  lines gone with `- `, the lines new with `+ `."
  [before after]
  (let [b (set before) a (set after)]
    (str/join "\n" (concat (for [l before :when (not (a l))] (str "- " (str/trim l)))
                           (for [l after :when (not (b l))] (str "+ " (str/trim l)))))))

(defn- write-spec!
  "The spec `after` written through `deps/write!` once the person said yes
  to `display`: `{:written? :text}`."
  [ctx dir tool display args after done-text]
  (if-let [why (ask-person ctx {:type :tool-use :action :call :tool tool :pattern tool
                                :display display :args args :effects #{:fs/write}})]
    {:written? false :text (str "Nothing was written (" why "). What the person was asked:\n\n" display)}
    (let [r (deps/write! dir :spec after)]
      (if (:ok r)
        {:written? true
         :text (str done-text (when (:snapshot r) (str " The old file is kept as " (:snapshot r) ".")))}
        {:written? false :text (str "Nothing was written: " (:error r))}))))

(defn save-workflow
  "`workflow$save`: workflow `:id` added, or changed — the fields given
  replace its own, the rest kept. `:stages` and `:links` are its whole
  stages and links when given (EDN or JSON); `:roles` adds or changes roles
  (a map of id to `{:title :does}`), and a role a stage or the master names
  that the spec has not is added bare. A `:bead` give in its links, which
  is deprecated, is written as the `:issue` give that says the same
  (`deps/bead-as-issue`), and said. Held to what `byj deps write` holds a
  spec to; shown to the person as what changes and written only on their
  yes. `{:written? :text}`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        id-s (str/replace (str/trim (str (:id args))) #"^:" "")
        {spec :value probs :problems} (deps/read-spec dir)
        no (fn [why] {:written? false :text (str "The workflow was not saved: " why ".")})
        parsed (into {} (for [k [:stages :links :roles]] [k (parse-value (get args k))]))
        bad (some (fn [[k r]] (when (:error r) (str (name k) " is " (:error r)))) parsed)]
    (cond
      (not (re-matches #"[A-Za-z0-9_][A-Za-z0-9_.-]*" id-s))
      (no (str "\"" id-s "\" is not an id: letters, digits, _ . and -, not starting with . or -"))
      (seq probs) (no (fill (:unreadable w) {:problems (problems-text probs)}))
      bad (no bad)
      :else
      (let [id (keyword id-s)
            before (get-in spec [:workflows id])
            master (some-> (given (:master args)) str str/trim (str/replace #"^:" ""))
            raw (cond-> (or before {})
                  (given (:title args)) (assoc :title (str (:title args)))
                  (given (:does args)) (assoc :does (str (:does args)))
                  (#{"none" "-"} master) (dissoc :master)
                  (and master (not (#{"none" "-"} master))) (assoc :master master)
                  (some? (:ok (:stages parsed))) (assoc :stages (:ok (:stages parsed)))
                  (some? (:ok (:links parsed))) (assoc :links (:ok (:links parsed))))
            {wv :value wp :problems} (deps/clean-spec {:workflows {id raw}})
            {rv :value rp :problems} (deps/clean-spec {:roles (or (:ok (:roles parsed)) {})})
            cleaned (get-in wv [:workflows id])
            ;; a :bead give, deprecated, written as the issue it is (byj-5q4)
            wf (cond-> cleaned
                 (:links cleaned) (update :links (fn [ls] (mapv #(cond-> % (:gives %) (update :gives (partial mapv deps/bead-as-issue))) ls))))
            as-issue? (not= cleaned wf)
            named (distinct (concat (for [[s st] (:stages wf)] (deps/stage-role s st))
                                    (when (:master wf) [(:master wf)])))
            roles (merge-with merge (or (:roles spec) {}) (:roles rv))
            fresh (remove #(contains? roles %) named)
            roles (into roles (for [r fresh] [r {}]))
            after (-> spec (assoc-in [:workflows id] wf) (assoc :roles roles))
            errs (filter #(= id (get-in % [:target :workflow])) (workflow-errors dir after))
            added-roles (for [r (sort-by id-name (keys roles)) :when (not= (get roles r) (get-in spec [:roles r]))]
                          (str "  " (if (contains? (:roles spec) r) "role " "new role ") (role-words after r)))]
        (cond
          (seq wp) (no (problems-text wp))
          (seq rp) (no (str "roles: " (problems-text rp)))
          (empty? (:stages wf)) (no "a workflow needs at least one stage")
          (seq errs) (no (str/join "; " (map :message errs)))
          (= after spec) (no "what was asked is what it says already")
          :else
          (let [lines (describe w after id wf)
                shown (str/join "\n" (concat (if before
                                               (remove str/blank? [(shown-change (describe w spec id before) lines)])
                                               lines)
                                             added-roles
                                             (when as-issue? [(:as-issue w)])))]
            (write-spec! ctx dir "workflow$save"
                         (fill (if before (:ask-change w) (:ask-add w)) {:id id-s :shown shown})
                         {:id id-s} after
                         (str "The person said yes: workflow " id-s " is "
                              (if before "changed" "added") " in .brainyard/deps.edn.\n\n" shown "\n\n"))))))))

(defn remove-workflow
  "`workflow$remove`: workflow `:id` taken out of the spec, shown to the
  person and written only on their yes; the roles it played stay, as the
  roles of the project's other workflows and handoffs. `{:written? :text}`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        id-s (str/replace (str/trim (str (:id args))) #"^:" "")
        id (keyword id-s)
        {spec :value probs :problems} (deps/read-spec dir)
        wf (get-in spec [:workflows id])]
    (cond
      (nil? wf) {:written? false :text (str "No workflow " id-s " in .brainyard/deps.edn; nothing was removed.")}
      (seq probs) {:written? false :text (str "Nothing was removed: " (fill (:unreadable w) {:problems (problems-text probs)}))}
      :else
      (let [shown (str/join "\n" (describe w spec id wf))
            ws (dissoc (:workflows spec) id)
            after (if (seq ws) (assoc spec :workflows ws) (dissoc spec :workflows))]
        (write-spec! ctx dir "workflow$remove" (fill (:ask-remove w) {:id id-s :shown shown})
                     {:id id-s} after
                     (str "The person said yes: workflow " id-s " is removed from .brainyard/deps.edn."))))))

;; --- a run (byj-edw.2) --------------------------------------------------------------

(def ^:private sh-timeout-ms 60000)

(defn- sh
  "`argv` run in `dir`: `{:exit :out :err}`, as deps' bd and gh fns answer."
  [dir & argv]
  (let [r (fx/run!! (apply fx/process-task {:dir (str dir) :out :string :err :string} argv) sh-timeout-ms)]
    (if-let [ok (:ok r)]
      {:exit (:exit ok) :out (str (:out ok)) :err (str (:err ok))}
      {:exit -1 :out "" :err (if (:timeout r) "no answer in time" (str (:err r)))})))

(defn- git-out [dir & args]
  (let [r (apply sh dir "git" args)] (when (= 0 (:exit r)) (str/trim (:out r)))))

(defn- git-lines [dir & args]
  (vec (remove str/blank? (str/split-lines (str (apply git-out dir args))))))

(defn- now [] (str (java.time.Instant/ofEpochMilli (System/currentTimeMillis))))

(defn- num-of
  "A run's number as given: a number, or a string of one, `#` before it or not."
  [x]
  (cond (number? x) x (string? x) (parse-long (str/replace (str/trim x) #"^#" "")) :else nil))

(defn- clip [s n]
  (let [s (str/trim (str s))] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(def answer-max-chars
  "How much of a stage's answer the run keeps, and hands the next stage."
  4000)

(defn stage-attempts
  "How many turns one stage gets in a run: config's
  `:workflow-stage-attempts`, at least 1."
  [config]
  (max 1 (or (num-of (:workflow-stage-attempts config)) 3)))

(defn runs-dir [dir] (io/file (str dir) ".brainyard" "workflow-runs"))

(defn run-file [dir n] (io/file (runs-dir dir) (str n ".edn")))

(defn read-run
  "Run `n` of the project at `dir`, or nil."
  [dir n]
  (try (let [f (run-file dir n)]
         (when (.isFile f) (let [m (edn/read-string (slurp f))] (when (map? m) m))))
       (catch Throwable _ nil)))

(defn- write-run!
  "`run` written whole, through a file beside it and a rename."
  [dir run]
  (let [f (run-file dir (:n run))
        tmp (io/file (.getParentFile f) (str "." (:n run) ".edn.tmp"))]
    (io/make-parents f)
    (spit tmp (with-out-str (pp/pprint run)))
    (when-not (.renameTo tmp f) (io/copy tmp f) (.delete tmp))
    run))

(defn runs
  "Every run kept for the project at `dir`, newest first."
  [dir]
  (->> (.listFiles (runs-dir dir))
       (keep (fn [^java.io.File f]
               (when-let [[_ n] (re-matches #"(\d+)\.edn" (.getName f))] (read-run dir (parse-long n)))))
       (sort-by :n >)
       vec))

(defn- tracker-opts
  "What deps checks a bead or issue give with: bd and gh as config's
  `:bd-path` and `:gh-path` find them, and the profile's trackers."
  [config p]
  (let [bd (which/on-path "bd" (or (:bd-path config) (System/getenv "PATH")))
        gh (which/on-path "gh" (or (:gh-path config) (System/getenv "PATH")))]
    (cond-> {:issues (vec (:issues p)) :path-env (or (:bd-path config) (System/getenv "PATH"))}
      bd (assoc :bd-fn (fn [d & args] (apply sh d bd args)))
      gh (assoc :gh-fn (fn [d & args] (apply sh d gh args))))))

(defn- release-item!
  "The bead or GitHub issue `run` claimed released, when it is still in
  progress: a line for the log, or nil."
  [tl run]
  (when-let [id (and (:claimed? run) (:item run))]
    (when (and (drive/reachable? tl id) (= "in_progress" (:status (drive/show-item tl id))))
      (drive/unclaim! tl id)
      (str "↺ " id " released: the run did not finish it"))))

(defn- over?
  "Whether `run`, still marked running, has ended: it was this session's —
  a session's turns run one at a time — or its process is gone."
  [config run]
  (let [sid (not-empty (str (:session-id config)))]
    (or (and sid (= sid (str (:session run))))
        (not (persist/alive? (:pid run))))))

(defn- settle-left!
  "Each run still marked running whose session is over marked stopped, its
  claim released: a run cancelled or gone in a stage's turn."
  [config dir tl w]
  (doseq [r (runs dir) :when (and (= :running (:status r)) (over? config r))]
    (let [line (release-item! tl r)]
      (write-run! dir (cond-> (assoc r :status :stopped :stopped (get-in w [:stops :left]) :ended-at (now))
                        line (update :log (fnil conj []) line))))))

(defn- master-words [w run]
  (if-let [m (get-in run [:def :master])]
    (role-words {:roles (:roles run)} m)
    (:no-master w)))

(defn- profile-words [w p]
  (if-let [g (given (:goal p))]
    (fill (:profile w) {:goal (str/trim g)
                        :scope (str (when-let [in (seq (get-in p [:scope :in]))] (fill (:scope-in w) {:in (str/join "; " in)}))
                                    (when-let [out (seq (get-in p [:scope :out]))] (fill (:scope-out w) {:out (str/join "; " out)})))})
    ""))

(defn- out-links [run s] (filterv #(= s (:from %)) (get-in run [:def :links])))

(defn- brief-of
  "Stage `i` of `run`'s brief: the run and its work, the profile's goal and
  scope, the workflow's flow, the stage's role, what the stages linking to
  it handed it and what it hands on."
  [w p run i]
  (let [{:keys [def stages]} run
        spec {:roles (:roles run)}
        {s :stage r :role} (nth stages i)
        by-id (into {} (map (juxt :stage identity)) stages)
        handed (apply str (for [{:keys [from to gives]} (:links def)
                                :let [f (by-id from)]
                                :when (and (= s to) (= :done (:status f)))]
                            (fill (:handed w) {:from (id-name from) :role (id-name (:role f)) :gives (gives-words w gives)
                                               :answer (or (not-empty (str/trim (str (:answer f)))) (:no-progress w))})))
        outs (out-links run s)
        here (get-in def [:stages s])]
    (fill (:brief w)
          {:n (:n run) :workflow (id-name (:workflow run))
           :title (if (given (:title def)) (str " (" (:title def) ")") "")
           :work (:work-text run) :profile (profile-words w p)
           ;; a run built on a branch says so (byj-cm4.3)
           :builds (str (when-let [of (:again run)]
                          ;; run again (byj-cm4.2): the verdict and what to change, before all else
                          (str (fill (:again w) {:of of
                                                 :verdict (if-let [v (:again-verdict run)] (fill (:again-verdict w) {:verdict (id-name v)}) "")
                                                 :note (or (given (:again-note run)) (fill (:no-note w) {:of of}))})
                               "\n\n"))
                        (when-let [f (given (:from run))] (str (fill (:builds w) {:from f}) "\n\n")))
           :flow (str/join " → " (map (comp id-name :stage) stages))
           :stage (id-name s)
           ;; the brief ends the role with its own full stop
           :role (str/replace (str (role-words spec r) (when (given (:does here)) (str "; in this workflow: " (:does here))))
                              #"\.\s*$" "")
           :handed handed
           :next (if (seq outs)
                   (str/join "\n" (for [{:keys [to gives]} outs] (fill (:hands-on w) {:to (id-name to) :gives (gives-words w gives)})))
                   (:last w))
           :end (if (seq outs)
                  (fill (:end-on w) {:to (str/join " and " (map (comp id-name :to) outs))})
                  (:end-last w))})))

(defn- item-of
  "The bead or GitHub issue `work` starts with, as its tracker says it —
  `{:id :title :description :status}` — or nil when it names none."
  [tl work]
  (let [tok (first (str/split (str/trim (str work)) #"\s+"))]
    (when (and (not (str/blank? tok))
               (or (drive/github-id? tok) (re-matches #"[A-Za-z][A-Za-z0-9_]*-[A-Za-z0-9]+(?:\.\d+)*" tok))
               (drive/reachable? tl tok))
      (when-let [it (drive/show-item tl tok)]
        (assoc it :id tok)))))

;; --- where a stage works (byj-edw.3) ------------------------------------------------

(defn- place-dir
  "The directory `place` works in: its own, else the project `dir`."
  [dir place]
  (str (or (:dir place) dir)))

(defn- elsewhere? [place] (not= :here (:key place :here)))

(defn- handing-stage
  "The stage that hands stage `i` of `run` its work: of those linking to
  it and done, the last in the run's order; nil for a stage nothing hands to."
  [run i]
  (let [s (:stage (nth (:stages run) i))
        froms (set (for [{:keys [from to]} (get-in run [:def :links]) :when (= s to)] from))]
    (last (filter #(and (froms (:stage %)) (= :done (:status %))) (take i (:stages run))))))

(defn- place-of
  "Where stage `i` of `run` works, as its role's `:session` says (RFC-075):
  `:own`, a session of its own — keyed by the stage — in a new worktree
  when the role says `:worktree` (branched from where the stage handing to
  it worked), else in that stage's directory; `:upstream`, the default,
  where the stage handing to it worked, or the run's own place (`:root`)
  for a stage nothing hands to. A place is `{:key :here}` — this turn, in
  the project — or `{:key k :dir d}`, a session keyed `k` working in `d`;
  a new worktree's place says its `:branch` and `:from`, and gets its
  `:dir` when the stage's turn begins (`workflow$delegate`)."
  [dir run i]
  (let [{s :stage r :role} (nth (:stages run) i)
        role (get-in run [:roles r])
        upstream (or (:place (handing-stage run i)) (:root run) {:key :here})]
    (if (= :own (:session role))
      (if (:worktree role)
        {:key s :branch (str "workflow/" (id-name (:workflow run)) "-" (:n run) "-" (id-name s))
         :from (place-dir dir upstream)}
        {:key s :dir (place-dir dir upstream)})
      upstream)))

(defn- begin-stage
  "`run` with stage `i` begun: where it works, the HEAD it began at there,
  and its brief."
  [w p dir run i]
  (let [place (place-of dir run i)
        head (or (git-out (or (:dir place) (:from place) dir) "rev-parse" "HEAD") "")
        run (update-in run [:stages i] merge {:status :working :started-at (now) :place place :head head})]
    (assoc-in run [:stages i :brief] (brief-of w p run i))))

(defn- update-local!
  "deps-local.edn rewritten as `f` of it: what byj deps reads of the
  sessions playing each role and the handoffs made (RFC-075). Bookkeeping:
  a write that fails stops no run."
  [dir f]
  (try (let [{local :value} (deps/read-local dir)]
         (deps/write! dir :local (f local)))
       (catch Throwable _ nil)))

(defn- note-begun!
  "Stage `i` of `run` begun in session `sid`: bound to its role, and, on its
  first turn, the handoff to it from the stage handing it its work kept."
  [dir run i sid]
  (when (given sid)
    (let [{r :role k :attempts} (nth (:stages run) i)
          from (handing-stage run i)]
      (update-local! dir (fn [local]
                           (cond-> local
                             (not (some #{sid} (get-in local [:bindings r])))
                             (update-in [:bindings r] (fnil conj []) sid)
                             (and from (zero? (or k 0)))
                             (update :handed (fnil conj []) {:from (:role from) :to r :session sid :at (java.util.Date.)})))))))

(defn start-run
  "`workflow$start`: open a run of workflow `:workflow` on `:work` — words,
  or a bead id or GitHub issue (`#N`) first, which the run claims and, when
  it is done, closes as the profile's `:issue/close` says. A run kept as
  running whose session is over is first marked stopped. Stops with why when
  the project has no profile, the workflow is not there or cannot be
  written, or there is no work. `:worktree` true runs it in a worktree of
  its own, on a branch `workflow/<workflow>-<n>`, each stage in a session
  there. `:from` names the branch or commit that worktree starts from (HEAD
  when absent; byj-cm4.3), and asks for one: refused before opening when it
  is not a commit; the record keeps it as `:from`, its commit as `:base`, and
  each brief says what the run builds on. `:by-hand` yes has the run wait
  after each stage judged done, `:paused`, for the person to hand it on
  (`workflow$resume`; byj-cm4.1). `{:stop :stopped :run :brief :master
  :elsewhere}`, `:elsewhere` whether the first stage works in a session of
  its own."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        p (profile/read-profile dir)
        tl (drive/tools config args dir p)
        wid (keyword (str/replace (str/trim (str (:workflow args))) #"^:" ""))
        {spec :value} (deps/read-spec dir)
        wf (get-in spec [:workflows wid])
        work (str/trim (str (:work args)))
        errs (filter #(= wid (get-in % [:target :workflow])) (workflow-errors dir spec))
        from (given (some-> (:from args) str str/trim))
        ;; workflow$again's (byj-cm4.2): the worktree of the run it is again
        ;; of, and that run
        given-root (::root args)
        again (::again args)
        yes? (fn [v] (contains? #{true "true" "yes"} (cond-> v (string? v) (-> str/trim str/lower-case))))
        by-hand? (yes? (:by-hand args))
        ;; a branch to build on asks for a worktree of its own (byj-cm4.3)
        isolate? (or (some? from)
                     (contains? #{true "true" "yes"} (cond-> (:worktree args) (string? (:worktree args)) (-> str/trim str/lower-case))))
        stop (fn [why] {:stop true :stopped why :run "" :brief "" :master "" :elsewhere false})]
    (when p (settle-left! config dir tl w))
    (cond
      (nil? p) (stop (get-in w [:stops :no-profile]))
      (nil? wf) (stop (fill (get-in w [:stops :no-workflow])
                            {:workflow (id-name wid)
                             :known (if-let [ws (seq (:workflows spec))]
                                      (str "; it has " (str/join ", " (sort (map (comp id-name key) ws))))
                                      ": /workflow adds one")}))
      (seq errs) (stop (fill (get-in w [:stops :errors]) {:workflow (id-name wid) :errors (str/join "; " (map :message errs))}))
      (str/blank? work) (stop (get-in w [:stops :no-work]))
      (and from (nil? (git-out dir "rev-parse" "--verify" "--quiet" (str from "^{commit}"))))
      (stop (fill (get-in w [:stops :no-ref]) {:ref from}))
      :else
      (let [item (item-of tl work)
            {:keys [Kind]} (when item (get-in (drive/words) [:kinds (if (drive/github-id? (:id item)) :github :beads)]))
            n (inc (reduce max 0 (map :n (runs dir))))
            wt (when (and isolate? (not given-root))
                 (worktree/add! dir (io/file (runs-dir dir) (str n) "worktree") (str "workflow/" (id-name wid) "-" n) from))
            stages (order wf)
            role-of #(deps/stage-role % (get-in wf [:stages %]))
            roles (into {} (for [r (distinct (concat (map role-of stages) (when (:master wf) [(:master wf)])))]
                             [r (get-in spec [:roles r])]))
            head (or (git-out (or (:dir given-root) dir) "rev-parse" (str (or (when-not given-root from) "HEAD") "^{commit}")) "")
            claimed? (boolean (when (and item (not= "closed" (:status item)) (not (:error wt)))
                                (:ok? (drive/claim! tl (:id item)))))
            run {:n n :workflow wid :status :running
                 :work work
                 :work-text (if item
                              (str (fill (:item w) {:Kind Kind :id (:id item) :title (:title item)
                                                    :description (str/trim (str (:description item)))})
                                   (let [more (str/trim (subs work (count (:id item))))]
                                     (when-not (str/blank? more) (str "\n\n" more))))
                              work)
                 :item (:id item) :claimed? claimed?
                 :session (str (:session-id config)) :pid (persist/own-pid)
                 :started-at (now) :base head
                 :branch (git-out dir "rev-parse" "--abbrev-ref" "HEAD")
                 :def wf :roles roles :at 0 :failures 0 :usd 0 :log []
                 :root (cond given-root given-root
                             (:dir wt) {:key :root :dir (:dir wt) :branch (:branch wt)}
                             :else {:key :here})
                 :stages (vec (for [s stages] {:stage s :role (role-of s) :status :pending :attempts 0}))}]
        (if (:error wt)
          (stop (:error wt))
          (let [run (begin-stage w p dir (cond-> run
                                           from (assoc :from from)
                                           by-hand? (assoc :by-hand true)
                                           again (merge again))
                                 0)
                place (get-in run [:stages 0 :place])]
            (write-run! dir run)
            (when-not (elsewhere? place) (note-begun! dir run 0 (:session-id config)))
            {:stop false :stopped "" :run n :brief (get-in run [:stages 0 :brief]) :master (master-words w run)
             :elsewhere (elsewhere? place)}))))))

(defn check-stage
  "`workflow$check`: what the current stage's turn of run `:run` left —
  the work tree's uncommitted files, the commits since the stage began —
  each give of the links it hands on by, checked from that and from its
  `:answer` (`deps/run-give-row`), and what the profile expects of finished
  work. Kept with the stage. Reads the project only. `{:text}`."
  [config args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        run (when n (read-run dir n))]
    (if-not run
      {:text (str "No run " (:run args) " to check.")}
      (let [p (profile/read-profile dir)
            i (:at run)
            {s :stage r :role head :head k :attempts place :place} (nth (:stages run) i)
            ;; the stage's own work tree: the project's, or its worktree's
            pdir (place-dir dir place)
            dirty (git-lines pdir "status" "--porcelain" "--" "." ":!.beads" ":!.brainyard" ":!.claude")
            changed (when (given head)
                      (distinct (concat (git-lines pdir "diff" "--name-only" head)
                                        (git-lines pdir "ls-files" "-o" "--exclude-standard"))))
            log (when (given head) (git-lines pdir "log" (str head "..HEAD") "--format=%h %s"))
            opts (tracker-opts config p)
            ev {:changed (remove #(re-find #"^\.(brainyard|beads|claude)/" %) changed)
                :commits (count log) :answer (:answer args)}
            outs (out-links run s)
            rows (vec (for [{:keys [to gives]} outs]
                        {:to to :gives (mapv #(deps/run-give-row pdir % ev opts) gives)}))
            gw (fn [g row] (str (give-words w g) " — " (if (:ready row) "ready" "not ready") " (" (:detail row) ")"))]
        (write-run! dir (assoc-in run [:stages i :gives]
                                  (vec (for [{:keys [to gives]} rows]
                                         {:to to :ready (every? :ready gives) :gives (mapv #(select-keys % [:kind :ready :detail]) gives)}))))
        {:text (str/join "\n"
                         (remove nil?
                         (concat
                          [(fill (:check-head w) {:stage (id-name s) :role (id-name r) :n n :k (inc k) :max (stage-attempts config)})
                           (when (elsewhere? place)
                             (str "Worked in " pdir (when-let [b (or (:branch place) (git-out pdir "rev-parse" "--abbrev-ref" "HEAD"))] (str ", on " b))))
                           (str "Uncommitted: " (if (seq dirty) (str/join ", " (map #(subs % (min 3 (count %))) dirty)) "nothing"))
                           (str "Commits since the stage began: " (if (seq log) (str/join "; " log) "none"))]
                          (if (seq outs)
                            (for [[{:keys [to gives]} row] (map vector outs rows)]
                              (fill (:check-gives w) {:to (id-name to)
                                                      :gives (if (seq gives) (str/join "; " (map gw gives (:gives row))) (:no-gives w))}))
                            [(:check-last w)])
                          [(str "Expected: " (get-in w [:expect (or (profile/level p :git/commit) :ask)]))])))}))))

(defn- close-item!
  "The run's bead or issue closed, its stages done, as the profile's
  `:issue/close` says: a log line."
  [ctx tl w p run summary]
  (when-let [id (:item run)]
    (let [it (when (drive/reachable? tl id) (drive/show-item tl id))
          no (cond
               (nil? it) (str (if (drive/github-id? id) "gh" "bd") " could not read it")
               (= "closed" (:status it)) nil
               :else (case (profile/level p :issue/close)
                       :act nil
                       :never "the profile has no agent close one"
                       (ask-person ctx {:type :tool-use :action :call :tool "workflow$settle" :pattern "workflow$settle"
                                        :display (fill (:ask-close w) {:id id :title (:title it) :n (:n run)
                                                                       :workflow (id-name (:workflow run)) :summary summary})
                                        :args {:item id}})))]
      (cond
        (= "closed" (:status it)) (str "✓ " id " was closed in its turn")
        no (str "• " id " left open: " no)
        :else (let [r (drive/close! tl id (str "/workflow run #" (:n run) " of " (id-name (:workflow run)) ": " summary))]
                (if (:ok? r) (str "✓ " id " closed") (str "• " id " close failed: " (:said r))))))))

(defn settle-stage
  "`workflow$settle`: act on the master's judgement of the current stage
  of run `:run`. `done` hands on to the next stage, with its brief, or —
  the last stage done — ends the run, closing its bead or issue as the
  profile says; `retry` has the stage go again, with what was missing and
  what its turn ended saying, until it has had `stage-attempts` turns;
  `ask` and `stop` end the run with the question or why. The profile's
  `:failures` and `:usd` limits stop it too. A run that ends unfinished
  releases its claim. Every step is a line of the run's log, and what the
  stage's turn spent is said as this tool's spend.
  `{:stop :stopped :brief :stage :elsewhere}`, `:elsewhere` whether the
  next turn works in a session of its own."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        run (when n (read-run dir n))]
    (cond
      (not run)
      {:stop true :stopped (str "No run " (:run args) ".") :brief "" :stage ""}
      ;; stopped from outside its session (byj workflow stop, byj-cm4.4): the
      ;; record says so, and the run ends here, as it is
      (not= :running (:status run))
      {:stop true :stopped (str (:stopped run)) :brief "" :stage "" :elsewhere false}
      :else
      (let [p (profile/read-profile dir)
            tl (drive/tools config args dir p)
            i (:at run)
            st (nth (:stages run) i)
            sname (id-name (:stage st))
            status (keyword (str/lower-case (str/trim (str (:status args)))))
            summary (str/trim (str (:summary args)))
            answer (clip (:answer args) answer-max-chars)
            k (inc (:attempts st 0))
            cost (let [c (some-> (:stage-usage args) :cost)] (when (and (number? c) (pos? c)) c))
            spent (+ (or (some-> (:usage args) :cost) 0) (or cost 0))
            limits (:stop p)
            run (-> run (update :usd (fnil + 0) (or cost 0)) (update-in [:stages i] assoc :attempts k))
            line (fn [run l] (update run :log (fnil conj []) l))
            end (fn [run why & [log-line]]
                  (let [run (cond-> (assoc run :status (if (= why (get-in w [:stops :finished])) :done :stopped)
                                           :stopped why :ended-at (now))
                              log-line (line log-line))
                        released (when (= :stopped (:status run)) (release-item! tl run))
                        run (cond-> run released (line released))]
                    (write-run! dir run)
                    {:stop true :stopped why :brief "" :stage "" :elsewhere false}))
            usd-hit? (and (:usd limits) (>= spent (:usd limits)))
            usd-why (fill (get-in w [:stops :usd]) {:n (:usd limits)})]
        (cond->
          (case status
            :done
            (let [run (-> run
                          (update-in [:stages i] assoc :status :done :summary summary :answer answer :ended-at (now))
                          (assoc :failures 0)
                          (line (str "✓ " sname " — " summary)))]
              (if (< (inc i) (count (:stages run)))
                (cond
                  usd-hit? (end run usd-why)
                  ;; handed on by hand (byj-cm4.1): the next stage's brief kept,
                  ;; the run waiting for /workflow resume
                  (:by-hand run)
                  (let [nxt (id-name (:stage (nth (:stages run) (inc i))))
                        why (fill (get-in w [:stops :paused]) {:n n :stage nxt})
                        run (-> (begin-stage w p dir (assoc run :at (inc i)) (inc i))
                                (assoc-in [:stages (inc i) :status] :pending)
                                (assoc :status :paused :stopped why)
                                (line (str "‖ " why)))]
                    (write-run! dir run)
                    {:stop true :stopped why :brief "" :stage "" :elsewhere false})
                  :else
                  (let [run (begin-stage w p dir (assoc run :at (inc i)) (inc i))
                        place (get-in run [:stages (inc i) :place])]
                    (write-run! dir run)
                    (when-not (elsewhere? place) (note-begun! dir run (inc i) (:session-id config)))
                    {:stop false :stopped "" :brief (get-in run [:stages (inc i) :brief])
                     :stage (id-name (:stage (nth (:stages run) (inc i)))) :elsewhere (elsewhere? place)}))
                (let [closed (close-item! ctx tl w p run summary)]
                  (end (cond-> run closed (line closed)) (get-in w [:stops :finished])))))
            :retry
            (let [failures (inc (:failures run 0))
                  run (-> run (assoc :failures failures)
                          (update-in [:stages i] assoc :summary summary :answer answer)
                          (line (str "↻ " sname " — " summary)))]
              (cond
                (and (:failures limits) (>= failures (:failures limits)))
                (end run (fill (get-in w [:stops :failures]) {:n failures}))
                usd-hit? (end run usd-why)
                (>= k (stage-attempts config))
                (end run (fill (get-in w [:stops :stage-spent]) {:stage sname :k k :why summary}))
                :else
                (let [b (fill (:retry w) {:stage sname :n n :why summary :k (inc k) :max (stage-attempts config)
                                          :progress (or (not-empty answer) (:no-progress w)) :brief (:brief st)})]
                  (write-run! dir run)
                  {:stop false :stopped "" :brief b :stage sname :elsewhere (elsewhere? (:place st))})))
            :ask
            (let [q (str/trim (str (:question args)))]
              (end (update-in run [:stages i] assoc :summary summary :answer answer :question q)
                   (fill (get-in w [:stops :asks]) {:stage sname :question (if (str/blank? q) summary q)})
                   (str "? " sname " — " summary)))
            (end (update-in run [:stages i] assoc :summary summary :answer answer)
                 (fill (get-in w [:stops :cannot]) {:stage sname :why summary})
                 (str "✗ " sname " — " summary)))
          cost (assoc :usage-cost-usd cost))))))

(defn stage-timeout-ms
  "How long a stage worked in a session of its own is waited for:
  config's `:workflow-stage-timeout-ms`."
  [config]
  (or (num-of (:workflow-stage-timeout-ms config)) 3600000))

(defn- live-session [config sid]
  (when (given sid)
    (let [s (channel/find-session config sid)] (when-not (:error s) s))))

(defn delegate-stage
  "`workflow$delegate`: the current stage of run `:run` worked in the
  session its place names (`place-of`): the one it already has when it is
  live, else a headless session started for it (`teams/start-session!`),
  in a new worktree first when the place asks for one — labelled
  `workflow-<n>/<key>`, on this turn's provider and model, its permission
  prompts this session's person's. It is bound to the stage's role, asked
  the stage's `:brief` over the session channel, and waited for up to
  `stage-timeout-ms`; what it spent is this session's (`session$ask`), and
  is added to the run's `:usd` here, so the profile's `:usd` limit holds a
  run whose stages work elsewhere. `{:answer :usage}` — `:usage` empty, so
  the spend is not counted twice — the answer saying why when there is none."
  [config ctx args]
  (let [dir (dir-of config args)
        n (num-of (:run args))
        run (when n (read-run dir n))]
    (cond
      (not run)
      {:answer (str "(no run " (:run args) ")") :usage {}}
      (not= :running (:status run))
      {:answer (str "(the run was stopped: " (:stopped run) ")") :usage {}}
      :else
      (let [i (:at run)
            {:keys [place] :as st} (nth (:stages run) i)
            k (:key place)
            wt (when (and (:branch place) (not (:dir place)) (not= :root k))
                 (worktree/add! dir (io/file (runs-dir dir) (str n) (id-name k)) (:branch place) (given (:head st))))
            place (cond-> place (:dir wt) (assoc :dir (:dir wt)))
            known (get-in run [:sessions k])
            sess (or (when (live-session config (:session-id known)) known)
                     (when-not (:error wt)
                       (let [lm (:lm ctx)
                             label (str "workflow-" n "/" (id-name k))
                             r (teams/start-session! ctx {:label label :working-dir (:dir place)
                                                          :provider (some-> (:provider lm) id-name)
                                                          :model (some-> (:model lm) str)
                                                          ;; its turn may be the stage's whole work
                                                          :env {"BY_ASK_TIMEOUT_MS" (str (stage-timeout-ms config))
                                                                "BY_TEAM_TOOLS" "false"}
                                                          :task-name (str "workflow run #" n ", " (id-name k))
                                                          :who (str "the session for stage " (id-name (:stage st)))})]
                         (if (:error r) r (assoc r :dir (:dir place))))))
            why (or (:error wt) (:error sess))
            run (cond-> (assoc-in run [:stages i :place] place)
                  (and sess (not why)) (assoc-in [:sessions k] (select-keys sess [:session-id :task-id :label :dir])))]
        ;; every stage keyed alike works where this one does
        (write-run! dir (update run :stages
                                (fn [ss] (mapv #(if (and (= k (get-in % [:place :key])) (:dir place)) (assoc-in % [:place :dir] (:dir place)) %) ss))))
        (if why
          {:answer (str "(the stage could not be worked in a session of its own: " why ")") :usage {}}
          (do (note-begun! dir run i (:session-id sess))
              (let [ask (some #(when (= "session$ask" (:name %)) %) (channel/commands config))
                    t (stage-timeout-ms config)
                    r ((:fn ask) {:session (:session-id sess) :question (:brief args) :timeout-ms t} ctx)
                    cost (some-> r :usage :cost)]
                ;; session$ask counted it as this session's spend already; the
                ;; run's own :usd, which the profile's :usd limit is held to,
                ;; takes it here, as workflow$settle takes an in-turn stage's
                (when (and (number? cost) (pos? cost))
                  (when-let [now-run (read-run dir n)]
                    (write-run! dir (update now-run :usd (fnil + 0) cost))))
                {:answer (if (:error r)
                           (str "(the stage's session " (:session-id sess) " did not answer: " (:error r) ")")
                           (str (:answer r)))
                 :usage {}})))))))

(defn- close-sessions!
  "The sessions `run` started for its stages stopped, through the tasks
  they run as; their worktrees and branches stay. Lines for the report."
  [ctx run]
  (let [reg (:task-registry ctx)]
    (vec (for [[k {:keys [task-id label dir]}] (sort-by (comp id-name key) (:sessions run))]
           (do (when (and reg task-id) (try (tasks/cancel! reg task-id) (catch Throwable _ nil)))
               (str "  " label " — closed" (when dir (str "; its work is in " dir))))))))

(defn report-run
  "`workflow$report`: the answer. For a turn that managed the workflows
  (`:managing`), that turn's `:answer`, and what it spent
  (`:stage-usage`) said as this tool's spend; for run `:run`, each step's
  line, why it ended and the commits since it began; for a run that never
  opened, why not. The sessions a run started for its stages are closed,
  and its worktrees said. `{:text}`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        run (when n (read-run dir n))
        stopped (str/trim (str (:stopped args)))
        managing? (contains? #{true "true"} (:managing args))
        cost (let [c (some-> (:stage-usage args) :cost)] (when (and (number? c) (pos? c)) c))]
    (cond
      managing? (cond-> {:text (str/trim (str (:answer args)))} cost (assoc :usage-cost-usd cost))
      (not run)
      {:text (str "/workflow: " (if (str/blank? stopped) "no run was opened." stopped))}
      :else
      (let [run (if (= :running (:status run))
                  ;; the step limit ended it with a stage still to go
                  (write-run! dir (assoc run :status :stopped :ended-at (now)
                                         :stopped (if (str/blank? stopped) "the step limit was reached." stopped)))
                  run)
            closed (close-sessions! ctx run)
            ;; where its work is: the project, or the worktrees its stages worked in
            places (distinct (for [st (:stages run) :let [pl (:place st)] :when (and (:dir pl) (not= (str dir) (:dir pl)))] (:dir pl)))
            commits (when (given (:base run))
                      (distinct (mapcat #(git-lines % "log" "--format=%h %s" "-n" "20" (str (:base run) "..HEAD"))
                                        (cons (str dir) places))))]
        {:text (str (fill (:report-head w) {:n (:n run) :workflow (id-name (:workflow run))
                                            :title (if (given (get-in run [:def :title])) (str " (" (get-in run [:def :title]) ")") "")
                                            :work (clip (:work run) 200)})
                    "\n" (if (seq (:log run)) (str/join "\n" (:log run)) "nothing was done.")
                    "\n\nEnded: " (:stopped run)
                    (when (seq commits) (str "\nCommits since it began:\n" (str/join "\n" (map #(str "  " %) commits))))
                    (when (seq places)
                      (str "\nWorktrees, kept for you to merge or remove:\n"
                           (str/join "\n" (for [d places] (str "  " d (when-let [b (git-out d "rev-parse" "--abbrev-ref" "HEAD")] (str " (" b ")")))))))
                    (when (seq closed) (str "\nIts sessions:\n" (str/join "\n" closed)))
                    "\n\n" (fill (if (= :paused (:status run)) (:report-paused w) (:report-tail w)) {:n (:n run)}))}))))

(defn runs-report
  "`byj workflow runs --json` (byj-edw.4): the project's runs as data,
  newest first — or run `n` alone — each record as
  `.brainyard/workflow-runs/<n>.edn` keeps it, a keyword as its name, as
  `byj deps --json` gives its files. `{:runs [run …]}`."
  [dir & [n]]
  {:runs (mapv deps/json-value (if n (keep identity [(read-run dir n)]) (runs dir)))})

(defn- run-line [run]
  (str "#" (:n run) " " (id-name (:workflow run)) " — " (name (:status run))
       (when-let [v (:verdict run)] (str ", " (name v)))
       " — " (clip (:work run) 80)))

(defn list-runs
  "`workflow$runs`: the project's runs, newest first, `:limit` of them
  (10), of workflow `:workflow` when given, each with how it ended and the
  person's verdict. `{:text}`."
  [config args]
  (let [dir (dir-of config args)
        wid (some-> (given (:workflow args)) str str/trim (str/replace #"^:" "") keyword)
        lim (or (num-of (:limit args)) 10)
        rs (take lim (cond->> (runs dir) wid (filter #(= wid (:workflow %)))))]
    {:text (if (seq rs)
             (str/join "\n" (for [r rs]
                              (str (run-line r)
                                   (when (given (:stopped r)) (str "\n    " (:stopped r)))
                                   (when (given (:note r)) (str "\n    note: " (:note r))))))
             "No runs yet.")}))

(defn record-verdict
  "`workflow$verdict`: the person's word on run `:run` — `pass`,
  `changes` or `fail`, and a `:note` — kept with it. `{:text}`."
  [config args]
  (let [dir (dir-of config args)
        n (num-of (:run args))
        run (when n (read-run dir n))
        v (keyword (str/lower-case (str/trim (str (:verdict args)))))]
    (cond
      (nil? run) {:text (str "No run " (:run args) ".")}
      (not (#{:pass :changes :fail} v)) {:text "A verdict is pass, changes or fail."}
      :else (do (write-run! dir (cond-> (assoc run :verdict v :verdict-at (now))
                                  (given (:note args)) (assoc :note (str/trim (str (:note args))))))
                {:text (str "Kept with run #" n ": " (name v)
                            (when (given (:note args)) (str " — " (str/trim (str (:note args))))) ".")}))))

(defn again-run
  "`workflow$again` (byj-cm4.2): run `:run` — ended, done or stopped — again:
  a new run of the same workflow on the same work, as `workflow$start` opens
  one, kept as `:again N`. It works where that run did: in its worktree and
  branch when it had one still there, else in a new one when it worked
  apart (from what it built on), else here. Each stage's brief opens with
  the run it is again of, its verdict, and what to change — `:note`, else
  the verdict's note. Its issue is claimed again unless it was closed; a run
  handed on by hand is again. Refused for a run still running or waiting (`/workflow resume`).
  As `workflow$start`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        old (when n (read-run dir n))
        stop (fn [why] {:stop true :stopped why :run "" :brief "" :master "" :elsewhere false})]
    (cond
      (nil? old) (stop (str "No run " (:run args) "."))
      (#{:running :paused} (:status old)) (stop (fill (get-in w [:stops :not-ended]) {:n n}))
      :else
      (let [root (:root old)
            kept? (and (:dir root) (.isDirectory (io/file (str (:dir root)))))
            apart? (= :root (:key root))]
        (start-run config ctx
                   (cond-> {:dir (:dir args) :workflow (id-name (:workflow old)) :work (:work old)
                            ::again (cond-> {:again n}
                                      (:verdict old) (assoc :again-verdict (:verdict old))
                                      (or (given (some-> (:note args) str str/trim)) (given (:note old)))
                                      (assoc :again-note (or (given (some-> (:note args) str str/trim)) (given (:note old)))))}
                     (:by-hand old) (assoc :by-hand "yes")
                     kept? (assoc ::root root :worktree "yes")
                     (and apart? (not kept?)) (assoc :worktree "yes" :from (:from old))))))))

(defn stop-run
  "`byj workflow stop N` (byj-cm4.4): end run `:run`, running or paused,
  from outside the session running it — a client listing the runs, the
  person at a shell. It is marked stopped with why (`:why`, else the
  person's word), its claim released, and a line logged. Its session reads
  the record between steps (`workflow$settle`, `workflow$delegate`) and ends
  the run there; its report closes the sessions the run started.
  `{:ok :text}`, `:ok` false for a run that is not there or not running."
  [config args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        run (when n (read-run dir n))]
    (cond
      (nil? run) {:ok false :text (str "No run " (:run args) ".")}
      (not (#{:running :paused} (:status run)))
      {:ok false :text (fill (get-in w [:stops :not-running]) {:n n :status (id-name (:status run))})}
      :else
      (let [p (profile/read-profile dir)
            tl (drive/tools config args dir p)
            why (or (given (some-> (:why args) str str/trim)) (get-in w [:stops :by-person]))
            run (-> run
                    (assoc :status :stopped :stopped why :ended-at (now))
                    (update :log (fnil conj []) (str "■ " why)))
            released (release-item! tl run)
            run (cond-> run released (update :log conj released))]
        (write-run! dir run)
        {:ok true :text (str "Run #" n " stopped: " why (when released (str "\n" released)))}))))

(defn resume-run
  "`workflow$resume` (byj-cm4.1): go on with run `:run` from the stage it is
  at — one waiting to be handed on by hand (`:paused`), or one that stopped
  with a stage left (an ask, a limit, its session over, stopped from
  outside) — in this session. The stage is begun again (where it works, the
  HEAD there, its brief), its turns counted afresh, `:note` — what the person
  adds — put before its brief, and the run's issue claimed again. Refused
  while another live session runs it, and for a run that is done
  (`/workflow again`). `{:stop :stopped :run :brief :master :elsewhere}`, as
  `workflow$start`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        run (when n (read-run dir n))
        stop (fn [why] {:stop true :stopped why :run "" :brief "" :master "" :elsewhere false})]
    (cond
      (nil? run) (stop (str "No run " (:run args) "."))
      (and (= :running (:status run)) (not (over? config run)))
      (stop (fill (get-in w [:stops :running-elsewhere]) {:n n :session (:session run)}))
      (or (= :done (:status run)) (>= (:at run 0) (count (:stages run))))
      (stop (fill (get-in w [:stops :done-already]) {:n n}))
      :else
      (let [p (profile/read-profile dir)
            tl (drive/tools config args dir p)
            i (:at run)
            note (given (some-> (:note args) str str/trim))
            it (when-let [id (:item run)] (when (drive/reachable? tl id) (drive/show-item tl id)))
            claimed? (boolean (or (and (:claimed? run) (= "in_progress" (:status it)))
                                  (when (and it (not= "closed" (:status it))) (:ok? (drive/claim! tl (:item run))))))
            run (-> run
                    (assoc :status :running :session (str (:session-id config)) :pid (persist/own-pid) :claimed? claimed?)
                    (dissoc :stopped :ended-at)
                    (assoc-in [:stages i :attempts] 0))
            run (begin-stage w p dir run i)
            run (cond-> run
                  note (update-in [:stages i :brief] #(str (fill (:resume-note w) {:note note}) "\n\n" %)))
            sname (id-name (:stage (nth (:stages run) i)))
            run (update run :log (fnil conj []) (str "▶ " sname " — " (if note (str "handed on: " note) "handed on")))
            place (get-in run [:stages i :place])]
        (write-run! dir run)
        (when-not (elsewhere? place) (note-begun! dir run i (:session-id config)))
        {:stop false :stopped "" :run n :brief (get-in run [:stages i :brief]) :master (master-words w run)
         :elsewhere (elsewhere? place)}))))

;; --- the commands -------------------------------------------------------------------

(defn commands
  "The `workflow$*` commands (RFC-076)."
  [config]
  (let [opt (fn [k desc] [k {:optional true} [:any {:desc desc}]])
        dir (opt :dir "the project directory; this project when absent")]
    [{:name "workflow$list" :kind :command
      :description "This project's workflows (.brainyard/deps.edn's :workflows): each one's stages in the order a run works them and its master, the roles there are to play, and any error that would stop the spec being written. Reads only."
      :input-schema [:map dir]
      :output-schema [:map [:text :string]]
      :effects #{:fs/read}
      :fn (fn [args _] (list-workflows config args))}
     {:name "workflow$show" :kind :command
      :description "One workflow whole: its stages in order, the role each plays and what that role does, what each link hands the next stage, its master, and the errors naming it. Reads only."
      :input-schema [:map dir [:id [:string {:desc "the workflow's id"}]]]
      :output-schema [:map [:text :string] [:found? :any]]
      :effects #{:fs/read}
      :fn (fn [args _] (show-workflow config args))}
     {:name "workflow$save" :kind :command
      :description "Add a workflow, or change one, in .brainyard/deps.edn, as the person asked. Pass the id and only what changes: a field left out is kept; :stages and :links, when given, replace the workflow's whole stages and links. A stage plays the role of its id unless it names one; a role the stages or the master name that the project has not is added — give it a title and what it does in :roles. It is held to what byj deps write holds the file to (each stage plays a role, links name stages and do not go round), shown to the person, and written only on their yes. Read it first with workflow$show when changing one."
      :input-schema [:map dir
                     [:id [:string {:desc "the workflow's id: letters, digits, _ . -"}]]
                     [:title {:optional true} [:string {:desc "its name, for the person"}]]
                     [:does {:optional true} [:string {:desc "what it is for, a sentence"}]]
                     [:master {:optional true} [:string {:desc "the role that supervises its runs: it judges each handoff; none drops it"}]]
                     [:stages {:optional true} [:any {:desc "EDN map of stage id to {:role :title :does}, each key optional, e.g. {:design {} :fix {:role :implement}}"}]]
                     [:links {:optional true} [:any {:desc "EDN vector of {:from :to :gives}, a give one of {:kind :file :paths [\"glob\"]} {:kind :issue :id \"#42\"} {:kind :issue :tracker :beads :id \"x-1\"} {:kind :issue :tracker :beads :parent \"x\"} {:kind :branch} {:kind :note}"}]]
                     [:roles {:optional true} [:any {:desc "EDN map of role id to {:title :does}: roles to add or change"}]]]
      :output-schema [:map [:text :string] [:written? :any]]
      :effects #{:fs/read :fs/write}
      :fn (fn [args ctx] (save-workflow config ctx args))}
     {:name "workflow$remove" :kind :command
      :description "Remove a workflow from .brainyard/deps.edn, as the person asked: shown to them, and written only on their yes. The roles it played stay; its runs are kept."
      :input-schema [:map dir [:id [:string {:desc "the workflow's id"}]]]
      :output-schema [:map [:text :string] [:written? :any]]
      :effects #{:fs/read :fs/write}
      :fn (fn [args ctx] (remove-workflow config ctx args))}
     {:name "workflow$runs" :kind :command
      :description "This project's workflow runs, newest first: each one's workflow, how it ended, the person's verdict, and what it worked on. Reads only."
      :input-schema [:map dir (opt :workflow "only this workflow's runs") (opt :limit "how many; 10 when absent")]
      :output-schema [:map [:text :string]]
      :effects #{:fs/read}
      :fn (fn [args _] (list-runs config args))}
     {:name "workflow$verdict" :kind :command
      :description "Keep the person's word on how a run went — pass, changes or fail, and a note — with the run. Only what the person said."
      :input-schema [:map dir [:run [:any {:desc "the run's number"}]] [:verdict [:string {:desc "pass, changes or fail"}]]
                     (opt :note "the person's note")]
      :output-schema [:map [:text :string]]
      :effects #{:fs/read :fs/write}
      :fn (fn [args _] (record-verdict config args))}
     {:name "workflow$start" :kind :command
      :description "/workflow's run: open a run of a workflow on a piece of work, and give its first stage's brief — or why it cannot run."
      :input-schema [:map dir (opt :workflow "the workflow's id") (opt :work "what the run works on: words, or an issue's id first: its id in beads, or #N")
                     (opt :worktree "yes to run it in a worktree of its own")
                     (opt :from "a branch or commit its worktree starts from, not HEAD; asks for a worktree of its own")
                     (opt :by-hand "yes to wait after each stage for the person to hand it on (workflow$resume)")]
      :output-schema [:map [:stop :any] [:stopped :any] [:run :any] [:brief :any] [:master :any] [:elsewhere {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (start-run config ctx args))}
     {:name "workflow$again" :kind :command
      :description "/workflow's again: run an ended run again — the same workflow and work, where it worked — each stage told its verdict and what to change; or why it cannot."
      :input-schema [:map dir (opt :run "the number of the run to run again") (opt :note "what to change, in the person's words; else the verdict's note")]
      :output-schema [:map [:stop :any] [:stopped :any] [:run :any] [:brief :any] [:master :any] [:elsewhere {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (again-run config ctx args))}
     {:name "workflow$resume" :kind :command
      :description "/workflow's resume: go on with a run waiting to be handed on, or stopped with a stage left, from the stage it is at — or why it cannot."
      :input-schema [:map dir (opt :run "the run's number") (opt :note "what the person adds for the stage, else nothing")]
      :output-schema [:map [:stop :any] [:stopped :any] [:run :any] [:brief :any] [:master :any] [:elsewhere {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (resume-run config ctx args))}
     {:name "workflow$check" :kind :command
      :description "What a run's stage's turn left: uncommitted files, commits since the stage began, whether what it hands on is ready, and what the profile expects."
      :input-schema [:map dir (opt :run "the run's number") (opt :answer "the stage's answer")]
      :output-schema [:map [:text :string]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args _] (check-stage config args))}
     {:name "workflow$settle" :kind :command
      :description "Act on the master's judgement of a run's stage: hand on to the next stage, have it go again, or end the run."
      :input-schema [:map dir (opt :run "the run's number") (opt :status "done, retry, ask or stop")
                     (opt :question "for ask") (opt :summary "one line") (opt :answer "the stage's answer")
                     (opt :usage "the run's usage so far") (opt :stage-usage "what the stage's turn spent")]
      :output-schema [:map [:stop :any] [:stopped :any] [:brief :any] [:stage :any] [:elsewhere {:optional true} :any]
                      [:usage-cost-usd {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (settle-stage config ctx args))}
     {:name "workflow$report" :kind :command
      :description "/workflow's answer: a managing turn's own, or a run's — each stage's line, why it ended, and the commits since it began."
      :input-schema [:map dir (opt :run "the run's number") (opt :stopped "why it ended")
                     (opt :managing "whether the turn managed the workflows instead") (opt :answer "that turn's answer")
                     (opt :stage-usage "what that turn spent")]
      :output-schema [:map [:text :string] [:usage-cost-usd {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (report-run config ctx args))}
     {:name "workflow$delegate" :kind :command
      :description "Have a run's stage worked in a session of its own — started for it, in a worktree of its own where its role says so — asked its brief, and waited for."
      :input-schema [:map dir (opt :run "the run's number") (opt :brief "the stage's brief")]
      :output-schema [:map [:answer :any] [:usage :any]]
      :effects #{:fs/read :fs/write :process :session/send}
      :fn (fn [args ctx] (delegate-stage config ctx args))}]))
