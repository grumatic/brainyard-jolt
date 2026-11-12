;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.teams
  "Agent teams (RFC-032) — the port's; brainyard has none.

  A leader session runs a team of member sessions on one piece of work. A
  member is a headless session (`jolt run:byj --serve`, RFC-031) with a role,
  and a role is an agent spec (RFC-018). The leader hands a member work over the
  session channel exactly as `session$send` does, so an assignment is a
  background task of the leader's (RFC-019): `task$wakeup` resumes the leader
  as each one lands, and several members work at once.

  A member is started as a background task of the leader's too. That is what
  makes a team end with its leader: a session cancels its tasks when it
  closes, and cancelling a process task destroys its process tree. Nothing a
  session starts outlives it.

  What this namespace knows: the roster, starting and stopping a member, and
  sending one work. HOW to lead — splitting a task, writing an assignment a
  member can act on without the conversation, checking what comes back — is
  `resources/brainyard/prompts/team-lead.md`, handed to the leader when it
  creates a team, the way a skill's procedure arrives when it is called
  (RFC-017)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.channel :as channel]
            [brainyard.effect :as fx]
            [brainyard.tasks :as tasks]
            [brainyard.version :as version]
            [brainyard.worktree :as worktree]))

;; --- the roster --------------------------------------------------------------------------

(defn teams-dir [config]
  (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "teams"))

(defn- team-file [config id] (io/file (teams-dir config) (str id) "team.edn"))

(defn read-team [config id]
  (let [f (team-file config id)]
    (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))))

(defn- write-team! [config {:keys [id] :as team}]
  (let [f (team-file config id)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str team))
    team))

(defn current-team
  "The open team this session leads, or nil."
  [config]
  (let [me (:session-id config)
        dir (teams-dir config)]
    (->> (when (.isDirectory dir) (.listFiles dir))
         (keep #(read-team config (.getName %)))
         (filter #(and (= me (:leader %)) (nil? (:closed-at %))))
         (sort-by :created-at >)
         first)))

(def ^:private roster-lock
  "The roster is read, changed and written by the leader's tools and by its
  dispatcher at once: one at a time."
  (Object.))

(defn- update-team! [config id f & args]
  (locking roster-lock
    (write-team! config (apply f (read-team config id) args))))

(defn- slug [s]
  (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9-]+" "-") (str/replace #"^-+|-+$" "")))

;; --- starting a member ----------------------------------------------------------------

(defn- sh-quote [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn member-command
  "The command that starts a member: `:team-member-command` when set, else
  `jolt run:byj` from the checkout this code runs from, or `byj run` from a
  binary."
  [config]
  (or (not-empty (:team-member-command config))
      (if (version/source-root) "jolt run:byj" "byj run")))

(defn- add-worktree!
  "A git worktree of the project for one member, on a branch of its own, so
  members editing code at once never edit the same files."
  [config team-id member]
  (let [project (or (:project-dir config) (:working-dir config))]
    (worktree/add! project (io/file (teams-dir config) (str team-id) "worktrees" member)
                   (str "team/" team-id "/" member))))

(defn- await-live
  "The member's session once it is live — found by its label — or nil after
  `timeout-ms`."
  [config label timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [s (channel/find-session config label)]
        (cond (:session-id s) s
              (> (System/currentTimeMillis) deadline) nil
              :else (do (Thread/sleep 200) (recur)))))))

(defn- launch!
  "Run member `entry` of `team` as a headless session and wait until it is
  live: a background task of the leader's, so it ends with the leader. With
  `resume`, the member's own session is continued, history and all. Returns
  the entry with its task and session ids, or `{:error}`."
  [{:keys [config] :as ctx} team {:keys [name agent provider model worktree] :as entry} & [resume]]
  (let [label (str (:id team) "/" name)
        project (or (:project-dir config) (:working-dir config))
        work-dir (or worktree (:working-dir config) project)
        cmd (str "BY_PROJECT_DIR=" (sh-quote project)
                 ;; a member leads no team of its own
                 " BY_TEAM_TOOLS=false"
                 " exec " (member-command config) " --serve"
                 " --label " (sh-quote label)
                 ;; its permission prompts go to this session's person
                 " --leader " (sh-quote (:session-id config))
                 " --working-dir " (sh-quote work-dir)
                 (when resume (str " --resume " (sh-quote resume)))
                 (when agent (str " --agent " (sh-quote agent)))
                 (when provider (str " -p " (sh-quote provider)))
                 (when model (str " -m " (sh-quote model))))
        task-id (tasks/start! ctx {:name (str "team member " label)
                                   :job-type :team-member
                                   ;; its ending is the team closing, not a result
                                   :quiet? true
                                   :task-fn (fn [append!]
                                              (tasks/bash-task {:command cmd
                                                                :timeout (* 24 3600 1000)
                                                                :working-dir (or (version/source-root) project)}
                                                               append!))})
        live (await-live config label (:team-spawn-timeout-ms config 60000))]
    (if live
      (assoc entry :label label :task-id task-id :session-id (:session-id live)
             :started-at (System/currentTimeMillis))
      (do (tasks/cancel! (:task-registry ctx) task-id)
          {:error (str "member " name " did not come up within "
                       (:team-spawn-timeout-ms config 60000) " ms — task$detail " task-id " has its output")}))))

(defn spawn!
  "Start member `name` of the open team: a headless session labelled
  `<team>/<name>`, with `agent`'s persona when given, on `provider`/`model`
  when given, in its own git worktree when `worktree?`. Returns the member's
  roster entry, or `{:error}`."
  [{:keys [config] :as ctx} {:keys [name agent provider model worktree]}]
  (let [team (current-team config)
        member (slug name)
        max-n (:max-team-members config 4)]
    (cond
      (nil? team) {:error "no open team — team$create first"}
      (str/blank? member) {:error "a member needs a name"}
      (some #(= member (:name %)) (:members team)) {:error (str "the team already has a member named " member)}
      (>= (count (remove :closed? (:members team))) max-n)
      {:error (str "the team has its " max-n " members (:max-team-members)")}
      (nil? (:task-registry ctx)) {:error "no task registry here to run a member in"}
      :else
      (let [wt (when worktree (add-worktree! config (:id team) member))]
        (if (:error wt)
          wt
          (let [entry (launch! ctx team (cond-> {:name member :agent agent :provider provider :model model}
                                          wt (assoc :worktree (:dir wt) :branch (:branch wt))))]
            (when-not (:error entry)
              (update-team! config (:id team) update :members (fnil conj []) entry))
            entry))))))

(defn resume!
  "Bring the open team's members back after its leader was resumed: they
  died with the leader's process. Each is its own session continued
  (`--resume`), under its old label, role, model and worktree. A member still
  live is left alone."
  [{:keys [config] :as ctx}]
  (if-let [team (current-team config)]
    (let [live (set (map :session-id (filter :live? (channel/sessions config))))
          gone (remove #(or (:closed? %) (live (:session-id %))) (:members team))]
      (if (nil? (:task-registry ctx))
        {:error "no task registry here to run a member in"}
        (let [results (mapv (fn [m] [m (launch! ctx team m (:session-id m))]) gone)]
          (update-team! config (:id team) update :members
                        (fn [ms] (mapv (fn [m] (or (some (fn [[old new]] (when (and (= (:name old) (:name m)) (not (:error new))) new))
                                                         results)
                                                   m))
                                       ms)))
          {:resumed (vec (for [[m r] results :when (not (:error r))] (:name m)))
           :already-live (vec (for [m (:members team) :when (and (not (:closed? m)) (live (:session-id m)))] (:name m)))
           :failed (vec (for [[m r] results :when (:error r)] {:name (:name m) :error (:error r)}))})))
    {:error "no open team"}))

;; --- work --------------------------------------------------------------------------------

(defn- member-of [team name]
  (let [n (slug name)]
    (first (filter #(and (= n (:name %)) (not (:closed? %))) (:members team)))))

(defn assign!
  "Hand member `name` of the open team `task`, without waiting: the answer is a
  background task of the leader's. Recorded on the roster."
  [{:keys [config] :as ctx} {:keys [member task]}]
  (let [team (current-team config)
        m (when team (member-of team member))]
    (cond
      (nil? team) {:error "no open team — team$create first"}
      (nil? m) {:error (str "no member " (pr-str member) " — team$status names them")}
      (str/blank? (str task)) {:error "an assignment needs a task"}
      :else
      (let [send (some #(when (= "session$send" (:name %)) %) (channel/commands config))
            r ((:fn send) {:session (:session-id m) :question task} ctx)]
        (if (:error r)
          r
          (do (update-team! config (:id team) update :assignments (fnil conj [])
                            {:member (:name m) :task-id (:task-id r) :task task
                             :at (System/currentTimeMillis)})
              {:member (:name m) :task-id (:task-id r)
               :note "task$wakeup to be resumed when it lands; team$status shows every assignment"}))))))

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- running-assignment?
  "Is member `name` working on one of this leader's assignments?"
  [team task-registry name]
  (some (fn [{:keys [member task-id]}]
          (and (= member name)
               (= :running (:status (tasks/snapshot task-registry task-id)))))
        (:assignments team)))

(defn- dispatch-loop
  "The team's board, worked by the leader's kernel rather than its model: each
  member that is up and free gets the next queued task. Ends — its result every
  answer it dispatched — once the queue is empty and those are all answered,
  or the team is closed. `dev/brainyard/dev/team_dispatch_spike.clj` measured
  why: pulling finishes 3–22% sooner than a leader assigning on each wakeup,
  and costs no leader turn per task (RFC-032, byj-gly.253.11)."
  [{:keys [config task-registry] :as ctx} team-id]
  (loop [mine []]
    (let [team (read-team config team-id)]
      (if (or (nil? team) (:closed-at team))
        {:answers (vec mine) :note "the team was closed"}
        (let [live (set (map :session-id (filter :live? (channel/sessions config))))
              free (->> (:members team)
                        (remove :closed?)
                        (filter #(live (:session-id %)))
                        (remove #(running-assignment? team task-registry (:name %))))
              handed (loop [free free queue (:queue team) handed []]
                       (if (and (seq free) (seq queue))
                         (let [{:keys [task]} (first queue)
                               ;; the leader waits on the dispatcher, not on each
                               ;; of these: none of them wakes it on its own
                               r (assign! (assoc ctx :quiet-tasks? true) {:member (:name (first free)) :task task})]
                           (update-team! config team-id update :queue #(vec (rest %)))
                           (recur (rest free) (rest queue)
                                  (cond-> handed (:task-id r) (conj (assoc r :task task)))))
                         handed))
              mine (into mine handed)
              open (filter #(= :running (:status (tasks/snapshot task-registry (:task-id %)))) mine)]
          (if (and (empty? (:queue (read-team config team-id))) (empty? open))
            {:answers (mapv (fn [{:keys [member task-id task]}]
                              (let [r (:result (tasks/snapshot task-registry task-id))]
                                (cond-> {:member member :task (clip task 120)}
                                  (:answer r) (assoc :answer (:answer r))
                                  (:error r) (assoc :error (:error r)))))
                            mine)}
            (do (Thread/sleep 200) (recur mine))))))))

(defn enqueue!
  "Put `task` on the open team's board, and make sure a dispatcher works it.
  Returns where it stands and the dispatcher's task id: `task$wakeup` on it
  resumes the leader once every queued task is answered."
  [{:keys [config task-registry] :as ctx} task]
  (let [team (current-team config)]
    (cond
      (nil? team) {:error "no open team — team$create first"}
      (str/blank? (str task)) {:error "an assignment needs a task"}
      (nil? task-registry) {:error "no task registry here to dispatch from"}
      :else
      (let [t (update-team! config (:id team) update :queue (fnil conj []) {:task task :at (System/currentTimeMillis)})
            running (some-> (:dispatcher t) (#(tasks/snapshot task-registry %)) :status (= :running))
            dispatcher (if running
                         (:dispatcher t)
                         (let [id (tasks/start! ctx {:name (str "team " (:id team) " dispatcher")
                                                     :job-type :team-dispatch
                                                     :task (fx/task-of #(dispatch-loop ctx (:id team)))})]
                           (update-team! config (:id team) assoc :dispatcher id)
                           id))]
        {:queued (count (:queue t)) :dispatcher-task-id dispatcher
         :note "the next free member takes it; task$wakeup on the dispatcher resumes you once every queued task is answered"}))))


(defn status
  "The open team: each member, live or not, and each assignment with where it
  stands and, once answered, the start of its answer."
  [{:keys [config task-registry]}]
  (if-let [team (current-team config)]
    (let [live (set (map :session-id (filter :live? (channel/sessions config))))
          ;; what each member's answers so far cost, from the answers themselves
          spent (reduce (fn [acc {:keys [member task-id]}]
                          (let [u (some-> task-registry (tasks/snapshot task-id) :result :usage)]
                            (cond-> acc
                              u (update member #(merge-with + (or % {}) (select-keys u [:llm-calls :cost]))))))
                        {} (:assignments team))]
      (cond->
       {:team (:id team)
       :members (mapv (fn [m] (cond-> (-> (select-keys m [:name :agent :model :worktree :branch :session-id])
                                          (assoc :live? (contains? live (:session-id m))))
                                (spent (:name m)) (assoc :spent (spent (:name m)))))
                      (remove :closed? (:members team)))
       :assignments (mapv (fn [{:keys [member task-id task]}]
                            (let [t (when task-registry (tasks/snapshot task-registry task-id))
                                  r (:result t)]
                              (cond-> {:member member :task-id task-id :task (clip task 120)
                                       ;; an assignment the leader's earlier process
                                       ;; was waiting on went with it
                                       :status (if t (some-> (:status t) name) "lost")}
                                (:answer r) (assoc :answer (clip (:answer r) 400))
                                (:error r) (assoc :error (:error r)))))
                          (:assignments team))}
       (seq (:queue team))
       (assoc :queued (mapv #(clip (:task %) 120) (:queue team)))

       (some #(and (not (:closed? %)) (not (live (:session-id %)))) (:members team))
       (assoc :note "a member is not live — team$resume brings the team's members back")))
    {:error "no open team — team$create first"}))

(defn close!
  "Close member `name`, or — with no name — the whole team: each member is
  asked to close over its channel, and its process task is cancelled after
  `grace-ms` if it has not. A worktree is left where it is, with its branch:
  what a member wrote is the leader's to merge."
  [{:keys [config task-registry]} {:keys [member]}]
  (if-let [team (current-team config)]
    (let [targets (if member
                    (keep #(member-of team %) [member])
                    (remove :closed? (:members team)))]
      (if (and member (empty? targets))
        {:error (str "no member " (pr-str member))}
        (do
          (doseq [m targets]
            (when-let [s (and (:session-id m) (channel/find-session config (:session-id m)))]
              (when (:ask-socket-path s)
                (try (channel/request! (:ask-socket-path s) {:op :close-session} 5000)
                     (catch Throwable _ nil)))))
          (doseq [m targets]
            (when task-registry
              (let [deadline (+ (System/currentTimeMillis) (:team-close-grace-ms config 10000))]
                (loop []
                  (let [t (tasks/snapshot task-registry (:task-id m))]
                    (when (and t (= :running (:status t)))
                      (if (> (System/currentTimeMillis) deadline)
                        (tasks/cancel! task-registry (:task-id m))
                        (do (Thread/sleep 100) (recur)))))))))
          (let [names (set (map :name targets))]
            (update-team! config (:id team)
                          (fn [t] (cond-> (update t :members (fn [ms] (mapv #(cond-> % (names (:name %)) (assoc :closed? true)) ms)))
                                    (nil? member) (assoc :closed-at (System/currentTimeMillis))))))
          (cond-> {:closed (vec (map :name targets))}
            (some :worktree targets)
            (assoc :worktrees (vec (keep #(when (:worktree %) (select-keys % [:name :worktree :branch])) targets))
                   :note "worktrees are left for you to merge (git merge <branch>) and remove (git worktree remove <dir>)")))))
    {:error "no open team"}))

;; --- the tools -----------------------------------------------------------------------------

(defn- protocol []
  (some-> (io/resource "brainyard/prompts/team-lead.md") slurp))

(defn commands
  "The leader's tools: create a team, start its members, hand them work, see
  where it stands, and close it."
  [config]
  [{:name "team$create" :kind :command
    :description "Start a team this session leads: members that work at once, each a live session of its own. Returns how to lead one — read it before starting members."
    :input-schema [:map [:name {:optional true} [:string {:desc "a short name for the team's work"}]]]
    :output-schema [:or [:map [:team :string] [:protocol :string]] [:map [:error :string]]]
    :effects #{:fs/write}
    :fn (fn [{:keys [name]} _]
          (if-let [t (current-team config)]
            {:error (str "this session already leads team " (:id t) " — team$close it first")}
            (let [id (str (or (not-empty (slug name)) "team") "-" (Long/toString (mod (System/currentTimeMillis) 1679616) 36))]
              (write-team! config {:id id :leader (:session-id config) :created-at (System/currentTimeMillis)
                                   :members [] :assignments []})
              {:team id :protocol (or (protocol) "")})))}
   {:name "team$spawn" :kind :command
    :description "Start a member of the team: a live session of its own with a role (an agent spec, agent$list names them), optionally on another model and in its own git worktree for code edits. Waits until it is up."
    :input-schema [:map
                   [:name [:string {:desc "the member's name within the team, e.g. tester"}]]
                   [:agent {:optional true} [:string {:desc "the agent spec whose persona it takes"}]]
                   [:provider {:optional true} :string]
                   [:model {:optional true} :string]
                   [:worktree {:optional true} [:boolean {:desc "its own git worktree and branch, for editing code alongside other members"}]]]
    :output-schema [:or [:map [:name :string] [:session-id [:maybe :string]] [:task-id :string]] [:map [:error :string]]]
    :effects #{:process :session/send}
    :fn (fn [args ctx] (spawn! ctx args))}
   {:name "team$assign" :kind :command
    :description (str "Hand the team a task without waiting. The task must stand on its own: the member has not seen this conversation. "
                      "With :member, that member gets it and its answer lands as a background task (task$wakeup on its id). "
                      "Without :member, it goes on the team's board and the next free member takes it — assign every part this way, "
                      "then task$wakeup once on the dispatcher, which lands when all of them are answered.")
    ;; `question` too, the word session$ask and session$send use: a model that
    ;; learned one of them reached for it here, and was refused
    :input-schema [:map [:member {:optional true} [:string {:desc "a member to give it to; leave out to put it on the board"}]]
                   [:task {:optional true} [:string {:desc "a self-contained task: goal, context, where, and what to report back"}]]
                   [:question {:optional true} [:string {:desc "the same as :task"}]]]
    :output-schema [:or [:map [:member :string] [:task-id :string]]
                    [:map [:queued :int] [:dispatcher-task-id :string]]
                    [:map [:error :string]]]
    :effects #{:session/send}
    :fn (fn [args ctx]
          (let [args (update args :task #(or (not-empty %) (:question args)))]
            (if (str/blank? (str (:member args))) (enqueue! ctx (:task args)) (assign! ctx args))))}
   {:name "team$status" :kind :command
    :description "The team: each member, whether it is up, and each assignment — running, completed or failed — with the start of its answer."
    :input-schema [:map]
    :output-schema [:or [:map [:team :string] [:members [:vector :any]] [:assignments [:vector :any]]] [:map [:error :string]]]
    :effects #{:fs/read}
    :fn (fn [_ ctx] (status ctx))}
   {:name "team$resume" :kind :command
    :description "After this session was resumed: bring the team's members back, each its own session continued, under its old role, model and worktree. Assignments that were running are lost — assign them again."
    :input-schema [:map]
    :output-schema [:or [:map [:resumed [:vector :string]]] [:map [:error :string]]]
    :effects #{:process :session/send}
    :fn (fn [_ ctx] (resume! ctx))}
   {:name "team$close" :kind :command
    :description "Close one member, or with no :member the whole team. Worktrees are left, with their branches, for merging."
    :input-schema [:map [:member {:optional true} :string]]
    :output-schema [:or [:map [:closed [:vector :string]]] [:map [:error :string]]]
    :effects #{:process :session/send}
    :fn (fn [args ctx] (close! ctx args))}])
