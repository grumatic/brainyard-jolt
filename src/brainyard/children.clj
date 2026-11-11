(ns brainyard.children
  "A session's subagents, live or ended, as a tree — what the agent page lists
  under each session (byj-gly.298, RFC-028).

  A child is `{:id :agent :owner :question :answer :success? :at :live? :turns
  :children}`: `:id` its instance id (`explore/azure-whale-1`), `:agent` the
  spec it runs, `:owner` who dispatched it (another child's id, or \"root\"),
  `:question` the first thing it was asked, `:answer` the last thing it said,
  `:success?` whether that turn answered, `:at` when it last moved, `:live?`
  whether a turn of it is running now, `:turns` how many it has had.

  Two sources, one shape:

  - an OPEN session's renderer keeps a buffer per child (`tui` `:subs`), filled
    by the dispatch, ask and result events — `from-buffers`;
  - a SAVED session's children are in its `trajectory.edn`, every child turn a
    record whose `:agent` is an instance id (RFC-025) — `from-trajectory`, read
    once per change of the file (`saved`).

  `tree` nests them by `:owner` — a child whose owner is not among them (\"root\",
  none on a record older than byj-gly.298.1, or one evicted from a buffer) is at
  the top. `with-claude` puts on an agent$claude child the Claude Code session
  it drives, from the delegation records (RFC-039)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.trajectory :as trajectory]))

(defn instance-id?
  "Is `agent`, a trajectory record's `:agent`, a subagent's instance id rather
  than the session's own agent? Instance ids are `<agent>/<colour>-<animal>-<n>`."
  [agent]
  (str/includes? (str agent) "/"))

(defn- agent-of [id] (first (str/split (str id) #"/" 2)))

(def answer-chars
  "How much of a saved child's answer is kept: enough for its row. The whole
  is in the trajectory, for the view that shows the child (byj-gly.298.4);
  kept here for every saved session drawn, a page-long report each was waste."
  300)

(defn- clip [s] (let [s (str s)] (if (> (count s) answer-chars) (str (subs s 0 answer-chars) "…") s)))

(defn from-trajectory
  "The children in a session's trajectory records, one per instance, oldest
  first, each answer clipped to `answer-chars`."
  [records]
  (->> records
       (filter #(instance-id? (:agent %)))
       (group-by :agent)
       (map (fn [[id recs]]
              (let [lst (last recs)]
                (cond-> {:id id :agent (agent-of id)
                         :question (:question (first recs))
                         :answer (some-> (trajectory/record-answer lst) clip)
                         :success? (boolean (:success lst))
                         :at (:ts lst)
                         :live? false
                         :turns (count recs)}
                  (some :owner recs) (assoc :owner (some :owner recs))))))
       (sort-by :at)
       vec))

(defn from-buffers
  "The children an open session's renderer keeps (`{id {:agent :owner :question
  :answer :success? :at :live :turns}}`), oldest first."
  [subs]
  (->> subs
       (map (fn [[id b]]
              (cond-> {:id id :agent (or (:agent b) (agent-of id))
                       :question (:question b) :answer (:answer b)
                       :success? (boolean (:success? b))
                       :at (:at b) :live? (boolean (:live b))
                       :turns (or (:turns b) 1)}
                (:owner b) (assoc :owner (:owner b)))))
       (sort-by #(or (:at %) 0))
       vec))

(defn merge-live
  "An open session's children: those its trajectory recorded (`saved`, every
  turn up to now, across resumes) with those its renderer is showing (`live`)
  over them — a live one is fresher, but keeps the owner and the turns the
  record knows when it does not."
  [saved live]
  (let [by-id (into {} (map (juxt :id identity)) saved)]
    (vec (vals (reduce (fn [m c]
                         (let [s (m (:id c))]
                           (assoc m (:id c)
                                  (cond-> (merge s c)
                                    (and s (nil? (:owner c)) (:owner s)) (assoc :owner (:owner s))
                                    s (assoc :turns (max (or (:turns s) 0) (or (:turns c) 0)))
                                    (and s (nil? (:question c))) (assoc :question (:question s))))))
                       by-id live)))))

(defn tree
  "`children` nested by `:owner`: each with its own `:children`, newest first
  at every level. One whose owner is not among them is at the top."
  [children]
  (let [ids (set (map :id children))
        by-owner (group-by #(if (contains? ids (:owner %)) (:owner %) ::top) children)
        build (fn build [owner seen]
                (vec (for [c (sort-by #(or (:at %) 0) > (get by-owner owner))
                           :when (not (seen (:id c)))]
                       (assoc c :children (build (:id c) (conj seen (:id c)))))))]
    (build ::top #{})))

(defn with-claude
  "Put on each agent$claude child the Claude Code session it drives: the
  delegation record (`claude-agent/records`) whose `:instance-id` is the
  child's, and, when the session is listed, its `claude-sessions` entry —
  `:claude {:session-id :title :state :branch :pr-url :bead :live?}`."
  [children records sessions]
  (let [by-instance (into {} (keep (fn [r] (when (:instance-id r) [(:instance-id r) r]))) records)
        listed (into {} (map (juxt :id identity)) (concat (:live sessions) (:saved sessions)))
        live-ids (set (map :id (:live sessions)))]
    (letfn [(join [c]
              (let [r (by-instance (:id c))
                    s (some-> r :session-id listed)]
                (cond-> (update c :children #(mapv join %))
                  r (assoc :claude (cond-> (select-keys r [:session-id :state :branch :pr-url :bead])
                                     s (assoc :title (:title s))
                                     true (assoc :live? (contains? live-ids (:session-id r))))))))]
      (mapv join children))))

(defn claude-session-ids
  "Every Claude Code session a tree's agent$claude children drive."
  [children]
  (into #{} (keep #(get-in % [:claude :session-id])) (tree-seq #(seq (:children %)) :children {:children children})))

;; --- a saved session's, read once per change -----------------------------------------

(defonce ^:private cache
  ;; trajectory path → [modified bytes children]
  (atom {}))

(defn saved
  "A saved session's children, from its `trajectory.edn`, read again only when
  the file changed (the page asks for every saved session it draws, eight
  times a second)."
  [{:keys [project-dir] :as config} session-id]
  (let [f (io/file project-dir ".brainyard" "sessions" (str session-id) trajectory/filename)]
    (if-not (.isFile f)
      []
      (let [k (.getPath f) m (.lastModified f) b (.length f)
            [m0 b0 v] (get @cache k)]
        (if (and (= m m0) (= b b0))
          v
          (let [v (try (from-trajectory (trajectory/read-trajectories config session-id))
                       (catch Throwable _ []))]
            (swap! cache assoc k [m b v])
            v))))))
