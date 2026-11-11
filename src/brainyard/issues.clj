(ns brainyard.issues
  "The left rail's Issues section (byj-gly.402): the project's open issues,
  from beads, else from GitHub, each epic a folded heading and its issues
  drawn as a dependency graph in lanes, with their status and priority.

  Which tracker is the project's: `.beads/` in the project directory is
  beads, read with `bd list --json`; else a github.com remote in its own
  `.git/config` is GitHub, read with `gh issue list --json`, which has no
  dependencies to draw and takes a priority from a `P0`..`P4` or
  `priority:*` label; else there is none, and the section is left out. Only
  the project directory itself is looked in: bd walks up from wherever it
  runs, and a test's project under `target/` would have read this
  repository's own issues (as memories once were, provenance P-40).

  Mechanism only (RFC-001): when it is read is the caller's (`refresh!`), and
  a read is a `process-task` off the render loop, a newer one cancelling an
  older, as `brainyard.project-views` does for the right rail."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.project-views :as pv]
            [ebb.core :as m]))

;; --- which tracker -------------------------------------------------------------------

(defn tracker
  "The project's tracker, `:beads`, `:github` or nil, from what is in `dir`
  itself."
  [dir]
  (let [d (io/file (str dir))
        git-config (io/file d ".git" "config")]
    (cond
      (.isDirectory (io/file d ".beads")) :beads
      (and (.isFile git-config) (re-find #"url\s*=\s*\S*github\.com[:/]" (slurp git-config))) :github)))

(defn beads-found?
  "Whether bd finds a beads database for `dir` as bd itself looks: `BEADS_DIR`
  (`beads-dir`, the environment's unless given), else a `.beads/` in `dir` or
  a directory above it. What the Memory section asks before running bd, whose
  store a project may share with the repository it sits in (byj-gly.458)."
  [dir & [{:keys [beads-dir] :or {beads-dir (System/getenv "BEADS_DIR")}}]]
  (boolean (or (not (str/blank? beads-dir))
               (loop [d (.getAbsoluteFile (io/file (str dir)))]
                 (cond (nil? d) false
                       (.isDirectory (io/file d ".beads")) true
                       :else (recur (.getParentFile d)))))))

(def beads-unavailable-rows
  "What the Issues and Memory sections say where there are no beads to read:
  that, and that /init sets them up — its `init$beads` runs `bd init`, and its
  check says how to install bd where there is none (byj-gly.458)."
  [{:label "  beads not available" :style {:dim true}}
   {:label "  /init sets it up" :style {:dim true}}])

;; --- reading ---------------------------------------------------------------------

(def beads-statuses
  "What `bd list` is asked for: the work not done."
  "open,in_progress,blocked")

(defn parse-beads
  "`bd list --json` as `[{:id :title :status :priority :type :parent :blocked-by}]`:
  `:status` a keyword, `:blocked-by` the ids it depends on by a `blocks`
  dependency, whatever their state."
  [out]
  (vec (for [i (json/read-str (str out))
             :when (:id i)]
         {:id (:id i)
          :title (str (:title i))
          :status (keyword (str/replace (str (:status i)) "_" "-"))
          :priority (:priority i)
          :type (some-> (:issue_type i) keyword)
          :parent (:parent i)
          :blocked-by (vec (for [d (:dependencies i)
                                 :when (= "blocks" (:type d))]
                             (:depends_on_id d)))})))

(defn label-priority
  "A priority from GitHub labels: `P0`..`P4`, or `priority:` (or `priority/`,
  `priority-`) followed by a digit or critical, high, medium, low. nil when
  none says."
  [labels]
  (first (keep (fn [l]
                 (let [l (str/lower-case (str l))]
                   (or (some-> (re-matches #"p([0-4])" l) second parse-long)
                       (when-let [[_ v] (re-matches #"priority[:/ -]\s*(\S+)" l)]
                         (or (parse-long v) ({"critical" 0 "urgent" 0 "high" 1 "medium" 2 "low" 3} v))))))
               labels)))

(defn parse-github
  "`gh issue list --json number,title,labels` as the same shape as
  `parse-beads`: `#N` for an id, every one open, none blocked."
  [out]
  (vec (for [i (json/read-str (str out))]
         {:id (str "#" (:number i))
          :number (:number i)
          :title (str (:title i))
          :status :open
          :priority (label-priority (map :name (:labels i)))
          :blocked-by []})))

(defn- run
  "A Task of `cmd`'s output in `dir`; it throws with what the command said
  when it fails."
  [dir & cmd]
  (m/sp (let [r (m/? (apply fx/process-task {:dir (str dir) :out :string :err :string :in ""} cmd))]
          (if (zero? (:exit r))
            (str (:out r))
            (throw (ex-info (str (first cmd) ": " (str/trim (str (or (not-empty (:err r)) (:out r)))))
                            {:exit (:exit r)}))))))

(defn read-task
  "A Task of `dir`'s open issues from its tracker `kind`."
  [dir kind]
  (case kind
    :beads (m/sp (parse-beads (m/? (run dir "bd" "list" "--status" beads-statuses "--json" "--limit" "0"))))
    :github (m/sp (parse-github (m/? (run dir "gh" "issue" "list" "--state" "open" "--limit" "200"
                                          "--json" "number,title,labels"))))))

(defn show-task
  "A Task of what the tracker says about `issue`, for the transcript."
  [dir kind issue]
  (case kind
    :beads (run dir "bd" "show" (:id issue))
    :github (run dir "gh" "issue" "view" (str (:number issue)))))

;; --- the rows ----------------------------------------------------------------------

(def ^:private status-rank {:in-progress 0 :blocked 1 :open 2})

(defn- rank [i] [(status-rank (:status i) 3) (or (:priority i) 5) (:id i)])

(defn graph-order
  "`issues` ordered so an issue comes before the ones that block it, as a
  commit comes before its parents, and otherwise by status (in progress,
  blocked, open), priority and id. Edges are `:blocked-by` among `issues`
  only."
  [issues]
  (let [ids (set (map :id issues))
        by-id (into {} (map (juxt :id identity)) issues)
        blockers (fn [i] (filter ids (:blocked-by i)))
        ;; how many issues here wait on each one
        waiting (frequencies (mapcat blockers issues))]
    (loop [waiting waiting
           ready (sort-by rank (remove #(pos? (get waiting (:id %) 0)) issues))
           out []]
      (if-let [i (first ready)]
        (let [freed (for [b (blockers i) :when (= 1 (get waiting b))] (by-id b))
              waiting (reduce #(update %1 %2 dec) waiting (blockers i))]
          (recur waiting (sort-by rank (concat (rest ready) freed)) (conj out i)))
        ;; a cycle leaves some never ready: they go last, as they are
        (let [placed (set (map :id out))]
          (into out (remove #(placed (:id %)) issues)))))))

(def priority-style
  "How a priority badge is drawn: the urgent ones red, P2 yellow, P4 dim."
  {0 {:color :red :bold true} 1 {:color :red} 2 {:color :yellow} 4 {:dim true}})

(defn- issue-row
  "One issue as a rail row: the lanes, its status where the lanes put the
  node, its priority, its title. A blocker that is open but not drawn here
  (another epic's) is named in the hint, `⊣ id` in red."
  [i cells lane open-ids drawn-ids]
  (let [blocked? (or (= :blocked (:status i)) (some open-ids (:blocked-by i)))
        mark (cond (= :in-progress (:status i)) "◐" blocked? "●" :else "○")
        badge (when (:priority i) (str "P" (:priority i)))
        elsewhere (first (remove drawn-ids (filter open-ids (:blocked-by i))))
        ;; the lanes draw a node as ●: its status goes there instead
        cells (assoc-in (vec cells) [(* 2 lane) 0] mark)
        segments (vec (concat [["  " nil]]
                              (map-indexed (fn [k [t c]]
                                             [t (cond (and (= k (* 2 lane)) blocked?) {:color :red}
                                                      c {:color c})])
                                           cells)
                              (when badge [[badge (priority-style (:priority i))] [" " nil]])
                              [[(:title i) nil]]))]
    {:label (apply str (map first segments)) :segments segments
     :hint (when elsewhere (str "⊣ " elsewhere))
     :hint-style {:color :red}
     :issue i}))

(defn group-rows
  "The rows of one group's `issues`: drawn as a graph in lanes, each with
  the lanes before it (`project-views/lanes`)."
  [issues open-ids]
  (let [ordered (graph-order issues)
        drawn (set (map :id ordered))
        laid (pv/lanes (mapv (fn [i] {:hash (:id i) :parents (vec (filter drawn (:blocked-by i)))}) ordered))]
    (mapv (fn [i {:keys [cells lane]}] (issue-row i cells lane open-ids drawn)) ordered laid)))

(defn groups
  "`issues` in groups: one per epic with open issues under it, `{:epic issue
  :issues […]}`, the most urgent first, then the issues with no epic here,
  `{:issues […]}`. An epic heads its group and is not a row in another."
  [issues]
  (let [ids (set (map :id issues))
        epic-ids (set (keep #(when (ids (:parent %)) (:parent %)) issues))
        by-id (into {} (map (juxt :id identity)) issues)
        under (group-by #(when (epic-ids (:parent %)) (:parent %)) (remove #(epic-ids (:id %)) issues))]
    (vec (concat (for [e (sort-by rank (map by-id epic-ids))
                       :when (seq (get under (:id e)))]
                   {:epic e :issues (get under (:id e))})
                 (when-let [loose (seq (get under nil))]
                   [{:issues (vec loose)}])))))

(defn rows
  "The section's rows for `issues`, the epics in `open` unfolded: an epic is a
  heading with its count; a tracker with no epics (GitHub) is one graph."
  [issues open]
  (let [open-ids (set (map :id issues))
        gs (groups issues)]
    (if (and (= 1 (count gs)) (nil? (:epic (first gs))))
      (group-rows (:issues (first gs)) open-ids)
      (vec (mapcat (fn [{:keys [epic issues]}]
                     (let [k (or (:id epic) ::loose)
                           open? (contains? open k)]
                       (cons {:label (str (if open? "▾ " "▸ ")
                                          (if epic (str (when (:priority epic) (str "P" (:priority epic) " ")) (:title epic)) "no epic"))
                              :hint (str (count issues)) :group k :issue epic}
                             (when open? (group-rows issues open-ids)))))
                   gs)))))

;; --- the view ----------------------------------------------------------------------

(defn views
  "The Issues view for project `dir` and the `refresh!` that rereads it:
  `{:view v :refresh! (fn []) :state cell}`. Enter on an epic folds or
  unfolds it, on an issue calls `show` with what the tracker says of it,
  read off the loop thread; `@` mentions its id. `cell` and `kept` as for
  `project-views/views`.

  A project with no tracker has the section all the same, as beads, saying
  beads are not available and /init sets them up (`beads-unavailable-rows`,
  byj-gly.458); each refresh looks again, so once /init has made `.beads/`
  the section reads them."
  [dir & [{:keys [cell kept show kind] :or {cell atom}}]]
  (let [kind-now #(or kind (tracker dir))
        title-kind (or (kind-now) :beads)]
    (let [state (cell {:reading? true})
          unfolded ((or kept (fn [_ x] (cell x))) :issues/unfolded #{})
          running (atom nil)
          refresh! (fn []
                     (some-> @running (apply []))
                     (reset! running
                             (fx/run-detached
                              (m/sp (try (if-let [kind (kind-now)]
                                           (reset! state {:issues (m/? (read-task dir kind))})
                                           (reset! state {:unavailable true}))
                                         (catch Throwable e
                                           (when-not (fx/cancelled? e)
                                             (reset! state {:error (or (ex-message e) (str e))})))))
                              :issues)))]
      {:refresh! refresh!
       :state state
       :view {:id "issues" :title (str "Issues · " (name title-kind))
              :items (fn [] (let [s @state]
                              (cond (:unavailable s) beads-unavailable-rows
                                    (:error s) [{:label (str "  could not read: " (:error s))}]
                                    (nil? (:issues s)) [{:label "  reading…"}]
                                    (empty? (:issues s)) [{:label "  no open issues" :style {:dim true}}]
                                    :else (rows (:issues s) @unfolded))))
              :act (fn [{:keys [group issue]}]
                     (cond group (swap! unfolded #(if (contains? % group) (disj % group) (conj % group)))
                           (and issue show (kind-now))
                           (fx/run-detached
                            (m/sp (show (str (name (kind-now)) " — " (:id issue))
                                        (try (m/? (show-task dir (kind-now) issue))
                                             (catch Throwable e
                                               (if (fx/cancelled? e) (throw e) (str (ex-message e)))))))
                            :issues-show)))
              :put (fn [{:keys [issue]}] (when issue (:id issue)))
              :on-show refresh!}})))
