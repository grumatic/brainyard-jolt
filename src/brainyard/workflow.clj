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

  Mechanism only (RFC-001). Every word a model or the person reads is
  `resources/brainyard/workflow/words.edn`'s.

  brainyard has no counterpart; the workbench authors and runs these
  workflows from its own UI, and this is byj's own way to, from a session."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [brainyard.deps :as deps]
            [brainyard.drive :as drive]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist :as persist]
            [brainyard.profile :as profile]
            [brainyard.which :as which]))

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
  "Give `g` of a link, in words."
  [w g]
  (let [gw (:gives w)]
    (case (:kind g)
      :file (fill (:file gw) {:paths (str/join ", " (:paths g))})
      :bead (if (:id g) (fill (:bead gw) {:id (:id g)}) (fill (:beads gw) {:parent (:parent g)}))
      :issue (if (:id g) (fill (:issue gw) {:id (:id g)}) (fill (:beads gw) {:parent (:parent g)}))
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
  that the spec has not is added bare. Held to what `byj deps write` holds a
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
            wf (get-in wv [:workflows id])
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
                                             added-roles))]
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

(defn- num-of [x] (cond (number? x) x (string? x) (parse-long (str/trim x)) :else nil))

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
           :flow (str/join " → " (map (comp id-name :stage) stages))
           :stage (id-name s)
           :role (str (role-words spec r) (when (given (:does here)) (str "; in this workflow: " (:does here))))
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

(defn start-run
  "`workflow$start`: open a run of workflow `:workflow` on `:work` — words,
  or a bead id or GitHub issue (`#N`) first, which the run claims and, when
  it is done, closes as the profile's `:issue/close` says. A run kept as
  running whose session is over is first marked stopped. Stops with why when
  the project has no profile, the workflow is not there or cannot be
  written, or there is no work. `{:stop :stopped :run :brief :master}`."
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
        stop (fn [why] {:stop true :stopped why :run "" :brief "" :master ""})]
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
      :else
      (let [item (item-of tl work)
            {:keys [Kind]} (when item (get-in (drive/words) [:kinds (if (drive/github-id? (:id item)) :github :beads)]))
            n (inc (reduce max 0 (map :n (runs dir))))
            stages (order wf)
            role-of #(deps/stage-role % (get-in wf [:stages %]))
            roles (into {} (for [r (distinct (concat (map role-of stages) (when (:master wf) [(:master wf)])))]
                             [r (get-in spec [:roles r])]))
            head (or (git-out dir "rev-parse" "HEAD") "")
            claimed? (boolean (when (and item (not= "closed" (:status item)))
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
                 :stages (vec (for [s stages] {:stage s :role (role-of s) :status :pending :attempts 0}))}
            run (update-in run [:stages 0] merge {:status :working :head head :started-at (now)})
            b (brief-of w p run 0)]
        (write-run! dir (assoc-in run [:stages 0 :brief] b))
        {:stop false :stopped "" :run n :brief b :master (master-words w run)}))))

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
            {s :stage r :role head :head k :attempts} (nth (:stages run) i)
            dirty (git-lines dir "status" "--porcelain" "--" "." ":!.beads" ":!.brainyard" ":!.claude")
            changed (when (given head)
                      (distinct (concat (git-lines dir "diff" "--name-only" head)
                                        (git-lines dir "ls-files" "-o" "--exclude-standard"))))
            log (when (given head) (git-lines dir "log" (str head "..HEAD") "--format=%h %s"))
            opts (tracker-opts config p)
            ev {:changed (remove #(re-find #"^\.(brainyard|beads|claude)/" %) changed)
                :commits (count log) :answer (:answer args)}
            outs (out-links run s)
            rows (vec (for [{:keys [to gives]} outs]
                        {:to to :gives (mapv #(deps/run-give-row dir % ev opts) gives)}))
            gw (fn [g row] (str (give-words w g) " — " (if (:ready row) "ready" "not ready") " (" (:detail row) ")"))]
        (write-run! dir (assoc-in run [:stages i :gives]
                                  (vec (for [{:keys [to gives]} rows]
                                         {:to to :ready (every? :ready gives) :gives (mapv #(select-keys % [:kind :ready :detail]) gives)}))))
        {:text (str/join "\n"
                         (concat
                          [(fill (:check-head w) {:stage (id-name s) :role (id-name r) :n n :k (inc k) :max (stage-attempts config)})
                           (str "Uncommitted: " (if (seq dirty) (str/join ", " (map #(subs % (min 3 (count %))) dirty)) "nothing"))
                           (str "Commits since the stage began: " (if (seq log) (str/join "; " log) "none"))]
                          (if (seq outs)
                            (for [[{:keys [to gives]} row] (map vector outs rows)]
                              (fill (:check-gives w) {:to (id-name to)
                                                      :gives (if (seq gives) (str/join "; " (map gw gives (:gives row))) (:no-gives w))}))
                            [(:check-last w)])
                          [(str "Expected: " (get-in w [:expect (or (profile/level p :git/commit) :ask)]))]))}))))

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
  `{:stop :stopped :brief :stage}`."
  [config ctx args]
  (let [dir (dir-of config args)
        w (words)
        n (num-of (:run args))
        run (when n (read-run dir n))]
    (if-not run
      {:stop true :stopped (str "No run " (:run args) ".") :brief "" :stage ""}
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
                    {:stop true :stopped why :brief "" :stage ""}))
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
                (if usd-hit?
                  (end run usd-why)
                  (let [run (-> run (assoc :at (inc i))
                                (update-in [:stages (inc i)] merge {:status :working :started-at (now)
                                                                    :head (or (git-out dir "rev-parse" "HEAD") "")}))
                        b (brief-of w p run (inc i))]
                    (write-run! dir (assoc-in run [:stages (inc i) :brief] b))
                    {:stop false :stopped "" :brief b :stage (id-name (:stage (nth (:stages run) (inc i))))}))
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
                  {:stop false :stopped "" :brief b :stage sname})))
            :ask
            (let [q (str/trim (str (:question args)))]
              (end (update-in run [:stages i] assoc :summary summary :answer answer :question q)
                   (fill (get-in w [:stops :asks]) {:stage sname :question (if (str/blank? q) summary q)})
                   (str "? " sname " — " summary)))
            (end (update-in run [:stages i] assoc :summary summary :answer answer)
                 (fill (get-in w [:stops :cannot]) {:stage sname :why summary})
                 (str "✗ " sname " — " summary)))
          cost (assoc :usage-cost-usd cost))))))

(defn report-run
  "`workflow$report`: the answer. For a turn that managed the workflows
  (`:managing`), that turn's `:answer`, and what it spent
  (`:stage-usage`) said as this tool's spend; for run `:run`, each step's
  line, why it ended and the commits since it began; for a run that never
  opened, why not. `{:text}`."
  [config args]
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
            commits (when (given (:base run)) (git-lines dir "log" "--format=%h %s" "-n" "20" (str (:base run) "..HEAD")))]
        {:text (str (fill (:report-head w) {:n (:n run) :workflow (id-name (:workflow run))
                                            :title (if (given (get-in run [:def :title])) (str " (" (get-in run [:def :title]) ")") "")
                                            :work (clip (:work run) 200)})
                    "\n" (if (seq (:log run)) (str/join "\n" (:log run)) "nothing was done.")
                    "\n\nEnded: " (:stopped run)
                    (when (seq commits) (str "\nCommits since it began:\n" (str/join "\n" (map #(str "  " %) commits))))
                    "\n\n" (fill (:report-tail w) {:n (:n run)}))}))))

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
                     [:links {:optional true} [:any {:desc "EDN vector of {:from :to :gives}, a give one of {:kind :file :paths [\"glob\"]} {:kind :bead :id \"x-1\"} {:kind :bead :parent \"x\"} {:kind :issue :id \"#42\"} {:kind :branch} {:kind :note}"}]]
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
      :input-schema [:map dir (opt :workflow "the workflow's id") (opt :work "what the run works on: words, or a bead id or #N first")]
      :output-schema [:map [:stop :any] [:stopped :any] [:run :any] [:brief :any] [:master :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (start-run config ctx args))}
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
      :output-schema [:map [:stop :any] [:stopped :any] [:brief :any] [:stage :any] [:usage-cost-usd {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args ctx] (settle-stage config ctx args))}
     {:name "workflow$report" :kind :command
      :description "/workflow's answer: a managing turn's own, or a run's — each stage's line, why it ended, and the commits since it began."
      :input-schema [:map dir (opt :run "the run's number") (opt :stopped "why it ended")
                     (opt :managing "whether the turn managed the workflows instead") (opt :answer "that turn's answer")
                     (opt :stage-usage "what that turn spent")]
      :output-schema [:map [:text :string] [:usage-cost-usd {:optional true} :any]]
      :effects #{:fs/read :fs/write :process}
      :fn (fn [args _] (report-run config args))}]))
