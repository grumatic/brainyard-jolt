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

  Mechanism only (RFC-001). Every word a model or the person reads is
  `resources/brainyard/workflow/words.edn`'s.

  brainyard has no counterpart; the workbench authors and runs these
  workflows from its own UI, and this is byj's own way to, from a session."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.deps :as deps]
            [brainyard.json :as json]))

(def words-resource "brainyard/workflow/words.edn")

(defn words [] (edn/read-string (slurp (io/resource words-resource))))

(defn- fill [s vars]
  (reduce-kv (fn [s k v] (str/replace s (str "{" (name k) "}") (str v))) (str s) vars))

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

(defn list-workflows
  "`workflow$list`: each of the project's workflows, its stages in order
  and its master; the roles they can play; and what stands in the way of
  writing the spec. `{:text}`."
  [config args]
  (let [dir (dir-of config args)
        w (words)
        {spec :value probs :problems} (deps/read-spec dir)
        ws (sort-by (comp id-name key) (:workflows spec))
        errs (workflow-errors dir spec)]
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
                            (when (:master wf) (str "; master " (id-name (:master wf))))))))
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
                                      (str "  error: " (:message e)))))})))

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
      :fn (fn [args ctx] (remove-workflow config ctx args))}]))
