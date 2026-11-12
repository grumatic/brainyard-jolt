;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.tasks
  "Background tasks (RFC-019) — brainyard's `agent/task/*` in the small.

  A task is work that outlives the tool call that started it: an effect, a
  canceller, and a record of what it is doing. Two ways in —

  - `task$run`, deliberately, for a job the model knows will be slow;
  - **auto-detach**, which the model does not ask for: a tool call that runs
    past `:fast-eval-timeout-ms` is adopted into a task and the call returns a
    marker instead of a result. This is brainyard's `call-tool-with-fast-eval`,
    and it is what keeps one slow command from spending a turn's whole budget.

  Until the moment of detach the wait and the work share a canceller, so a
  cancelled turn still kills a call that has not become a task — RFC-009's
  guarantee that a cancelled turn leaves no subprocess behind. After it, the
  task is the session's, and the session's close is what ends it.

  brainyard runs jobs on a fixed pool of four daemon threads; here they are ebb
  tasks on `blk`, which is an unbounded cached pool, so there is no queue and no
  pool size to get wrong."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.tools :as tools]
            [ebb.core :as m]))

;; --- ids ---------------------------------------------------------------------------

(defn- base36 [n] (str/lower-case (Long/toString (long n) 36)))

(def ^:private last-millis
  "brainyard's strictly-increasing millis ratchet, so ids sort by age even when
  two are minted in one millisecond."
  (atom 0))

(def ^:private counter (atom 0))

(defn task-id
  "`task-<base36 millis><counter>`. brainyard's tail is three random base36
  characters; `rand` is not a source of identity on a jolt worker thread, so the
  tail is a counter (provenance P-13)."
  []
  (let [ms (swap! last-millis #(max (inc %) (System/currentTimeMillis)))]
    (str "task-" (base36 ms) "-" (swap! counter inc))))

;; --- the registry ------------------------------------------------------------------

(defn registry [] (atom {}))

(defn tasks-dir [config]
  (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "tasks"))

(defn- terminal? [status] (contains? #{:completed :failed :cancelled} status))

(defn- cap [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "\n…[truncated " (- (count s) n) " chars]") s)))

(defn- persist!
  "Write the task's output and a post-mortem record. brainyard appends every
  line as it arrives; here the output exists only once the job is done
  (divergence `task-output-is-not-streamed`), so it is written once."
  [config {:keys [id name job-type status result output created-at completed-at]}]
  (try
    (let [d (io/file (tasks-dir config) id)]
      (.mkdirs d)
      (when-not (str/blank? (str output))
        (spit (io/file d "output.log") output))
      (spit (io/file d "meta.edn")
            (with-out-str (clojure.pprint/pprint
                           {:id id :name name :job-type job-type :status status
                            :result result :created-at created-at :completed-at completed-at})))
      (.getPath (io/file d "output.log")))
    (catch Throwable _ nil)))

(defn- finish!
  "Settle `id`. brainyard's `finalize-task!`: one place, idempotent, because a
  cancel and a completion can race.

  Two phases, because `wait` returns the instant it sees a terminal status. The
  first swap CLAIMS the task by parking the settlement under `::settling`
  without touching the status, and winning that swap is what makes this
  idempotent. The files are written while the task is still claimed, and only
  then is the settlement published — so a turn told its task is done can read
  the task's files (provenance P-17)."
  [reg config id status result output emit]
  (let [now (System/currentTimeMillis)
        settlement {:status status :result result
                    :output (cap output (:max-task-output-chars config 30000))
                    :completed-at now}
        settled? (fn [t] (or (nil? t) (terminal? (:status t)) (::settling t)))
        [old _] (swap-vals! reg update id #(if (settled? %) % (assoc % ::settling settlement)))]
    (when-not (settled? (get old id))
      (when-let [claimed (get @reg id)]
        (let [path (persist! config (merge (dissoc claimed :cancel ::settling) settlement))]
          (swap! reg update id
                 (fn [t] (when t (cond-> (merge (dissoc t ::settling) settlement)
                                   path (assoc :output-file path)))))
          (emit :task/completed {:id id :status status
                                 :elapsed-ms (- now (or (:started-at claimed) now))}))))))

(defn owner-of
  "Whose task this is: the instance id of the turn that started it, or \"root\".
  brainyard tags a task `:coact/owner-agent-id` and treats an untagged one as
  everyone's; here every task has an owner, so a subagent never harvests its
  caller's work."
  [config]
  (or (:agent-instance-id config) "root"))

(defn start!
  "Register `task` as a running task and return its id. The effect is already
  running when this returns — `cancel` is what stops it.

  brainyard's executors return either a terminal map or a `:detached` marker it
  has to poll; here the Task IS the completion signal, which is what its own
  `register-detached!` moved to.

  `owner` overrides whose task it is (`owner-of` the ctx's config): a task no
  turn owns is one no turn harvests — self-improvement's (byj-gly.247)."
  [ctx {:keys [name job-type task task-fn on-cancel owner quiet?]}]
  (let [reg (:task-registry ctx)
        config (:config ctx)
        emit (fn [ev data] (events/emit! ctx ev data))
        id (task-id)
        now (System/currentTimeMillis)
        ;; a job that produces output as it runs appends it here: the record is
        ;; the only place a turn can look while the job is still going (RFC-019)
        cap (:max-task-output-chars config 30000)
        append! (fn [line]
                  (swap! reg update id
                         (fn [t]
                           (when t
                             (let [so-far (str (:output t))
                                   text (if (>= (count so-far) cap)
                                          so-far
                                          (str so-far line "\n"))]
                               (assoc t :output text :last-output-at (System/currentTimeMillis)))))))
        task (or task (when task-fn (task-fn append!)))]
    (swap! reg assoc id {:id id :name name :job-type job-type :status :running
                         ;; a task the session does not wake for when it ends:
                         ;; one that is the machinery of other work, not a
                         ;; result — a team member's process, the asks a team's
                         ;; board makes (RFC-032)
                         :quiet? (boolean (or quiet? (:quiet-tasks? ctx)))
                         :owner (or owner (owner-of config)) :created-at now :started-at now})
    (emit :task/created {:id id :name name :job-type job-type})
    (let [cancel (fx/run task
                         (fn [v]
                           (let [m (if (map? v) v {:result v})]
                             (finish! reg config id
                                      (if (:error m) :failed :completed)
                                      (dissoc m :output) (:output m) emit)))
                         (fn [e]
                           (if (fx/cancelled? e)
                             (finish! reg config id :cancelled {:error "cancelled"} nil emit)
                             (finish! reg config id :failed
                                      {:error (or (ex-message e) (str e))} nil emit))))]
      (swap! reg assoc-in [id :cancel] (fn []
                                         (when on-cancel (try (on-cancel) (catch Throwable _ nil)))
                                         (cancel)))
      id)))

(defn snapshot
  "One task without its canceller — what a command may return. `:last-output-age-ms`
  is brainyard's liveness signal: a task printing something recently is working,
  whatever its elapsed time says."
  [reg id]
  (when-let [t (get @reg id)]
    (let [now (System/currentTimeMillis)]
      ;; ::settling is finish!'s claim, not part of a task: a snapshot taken
      ;; inside that window is the task as it still stands (provenance P-17)
      (cond-> (-> (dissoc t :cancel :last-output-at ::settling)
                  (assoc :elapsed-ms (- (or (:completed-at t) now) (or (:started-at t) now))))
        (and (:last-output-at t) (= :running (:status t)))
        (assoc :last-output-age-ms (- now (:last-output-at t)))))))

(defn summaries [reg]
  (vec (for [id (sort (keys @reg))]
         (select-keys (snapshot reg id) [:id :name :status :job-type :elapsed-ms]))))

(defn cancel!
  "Cancel a running task. brainyard's `cancel-task` acts only on a running one
  and reports nil otherwise."
  [reg id]
  (let [t (get @reg id)]
    ;; a task inside finish!'s claim is already over, whatever its status still
    ;; says — cancelling it would report a stop that did not happen
    (when (and t (= :running (:status t)) (not (::settling t)))
      ((:cancel t))
      true)))

(defn cancel-all!
  "Cancel everything still running — the session's close path. jolt has no
  shutdown hook that would kill an orphaned subprocess for us."
  [reg]
  (let [ids (for [[id t] @reg :when (and (= :running (:status t)) (not (::settling t)))] id)]
    (doseq [id ids] (cancel! reg id))
    (count ids)))

(defn wait
  "Block up to `ms` for `id` to settle. Returns the snapshot; a still-running
  task is returned as it is, NOT cancelled — brainyard's `:on-timeout :snapshot`."
  [reg id ms]
  (let [deadline (+ (System/currentTimeMillis) (max 0 ms))]
    (loop []
      (let [t (snapshot reg id)]
        (cond
          (nil? t) nil
          (terminal? (:status t)) t
          (>= (System/currentTimeMillis) deadline) t
          :else (do (Thread/sleep 25) (recur)))))))

;; --- jobs ---------------------------------------------------------------------------

(defn bash-task
  "A shell command as a Task settling with the bash tool's result shape.
  Cancelling destroys the process tree (RFC-009).

  `on-line` receives each line AS IT IS PRINTED, so a turn can see what a task
  is doing before it ends — brainyard streams the same way, into a 500-line ring
  and an `output.log` it appends to per line."
  [{:keys [command timeout working-dir]} & [on-line]]
  (m/sp
   ;; the bash tool's own opts (:err :out folds stderr in, :in "" so a command
   ;; that reads stdin cannot block forever)
   (let [opts {:dir (or working-dir ".") :in "" :out :string :err :out}
         proc (if on-line
                (fx/streaming-process opts on-line "bash" "-c" command)
                (fx/process-task opts "bash" "-c" command))
         r (m/? (fx/timeout proc (or timeout 120000) ::timeout))]
     (if (= ::timeout r)
       ;; the same words as the bash tool's: nothing of it is left running
       {:error (tools/timed-out (or timeout 120000))}
       {:exit-code (:exit r) :output (str (:out r))}))))

;; --- auto-detach --------------------------------------------------------------------

(defn marker
  "What the model reads in place of a result it could not wait for —
  brainyard's STILL RUNNING marker, trimmed to the commands this port has."
  [tool-name id]
  (str "[" tool-name " STILL RUNNING — task-id=" id ". DO NOT re-call " tool-name
       " — it is already running. Wait for it with task$wait {\"task-id\": \"" id
       "\", \"timeout-ms\": 60000}, peek at it with task$detail, or stop it with"
       " task$cancel. Its result is NOT lost: it arrives when you wait for it."
       " A long task with no output is not a stuck task.]"))

(defn moved-marker
  "What the model reads when the PERSON moved a call to the background
  (ctrl+g, byj-gly.391): the marker, said as theirs, so the model neither
  calls the tool again nor takes the move for a timeout."
  [tool-name id]
  (str "[The person moved " tool-name " to the background. " (subs (marker tool-name id) 1)))

(def ^:dynamic *in-task?*
  "True while a job runs inside a task: a task within a task has nobody to
  report to, so a tool call it makes never detaches (brainyard's `in-task?`)."
  false)

(defn detachable?
  "brainyard's `sync?` rule, inverted. A call detaches only when the layer is
  on, it is not already inside a task, its caller is not a subagent — whose turn
  would end before it could collect the result, orphaning the task — and it is
  not one of the `task$*` commands, since `task$wait` detaching itself is a
  deadlock with extra steps."
  [ctx tool-name]
  (let [config (:config ctx)]
    (and (:tasks? config true)
         (pos? (:fast-eval-timeout-ms config 30000))
         (not *in-task?*)
         (nil? (:agent-instance-id config))
         (not (str/starts-with? (str tool-name) "task$")))))

(defn detachable
  "Run `thunk` as a Task, waiting for it in brainyard's two stages.

  At `:fast-eval-timeout-ms` (30s) the call is **adopted into a task** — it gets
  an id, a row in `task$list` and a canceller — but the caller does NOT give up
  on it. It keeps waiting until `:auto-background-timeout-ms` (180s) has passed
  in total, and only then returns the marker. Most calls that overrun 30 seconds
  finish well inside three minutes, and a model that was handed a marker has to
  be told to wait for the thing it already asked for.

  A hand-written Task, because the halves need one canceller: cancelling before
  the adoption must cancel the WORK (RFC-009); after it the task is the
  session's, and `task$cancel` or the session's close is what ends it."
  [ctx tool-name thunk]
  (if-not (detachable? ctx tool-name)
    (fx/task-of thunk)
    (let [config (:config ctx)
          ;; the person's way to end the wait now (ctrl+g, byj-gly.391): a
          ;; release per waiting call in the session's :backgrounders, which a
          ;; front end calls; either stage gives way to it
          backgrounders (:backgrounders ctx)
          bg-key (str (gensym "bg"))
          ms (:fast-eval-timeout-ms config 30000)
          remaining (max 0 (- (:auto-background-timeout-ms config 180000) ms))
          reg (:task-registry ctx)
          ;; The caller's frame, snapshotted HERE so both branches of this fn
          ;; agree. The other branch is `fx/task-of`, which captures at
          ;; CONSTRUCTION; without this, whether a caller's dynamic binding
          ;; survived a tool call depended on `detachable?` — on config, on
          ;; whether a subagent was asking, on the tool's name — none of which
          ;; the caller chose or could see. Measured on jolt 0.8.10, which
          ;; stopped conveying into `via`: toolbox/*depth* and hooks/*depth*
          ;; both read 0 inside the thunk, so two recursion guards counted from
          ;; the wrong place (byj-gly.117,
          ;; dev/brainyard/dev/binding_conveyance_spike.clj). ebb promises no
          ;; conveyance, so the port installs the frame itself rather than
          ;; relying on one (RFC-009 rule 5).
          frame (get-thread-bindings)]
      (fn [s f]
        (let [p (promise)
              ;; delivered when the work ends OR the person releases the wait:
              ;; what both stages wait on, so either ends it
              woke (promise)
              detached? (atom false)
              work (fx/via-blk (with-bindings frame (binding [*in-task?* true] (thunk))))
              cancel-work (fx/run work #(do (deliver p {:ok %}) (deliver woke :done))
                                  #(do (deliver p {:err %}) (deliver woke :done)))
              _ (when backgrounders
                  (swap! backgrounders assoc bg-key {:tool tool-name :since (System/currentTimeMillis)
                                                     :release #(deliver woke :released)}))
              ;; never a blocking read: woke is undelivered while the work runs
              released? #(and (realized? woke) (= :released @woke) (not (realized? p)))
              ;; the caller collected it after all: the task is terminal and
              ;; must not be announced again by the next iteration's harvest —
              ;; brainyard's `:harvested?`
              collected (fn [id r]
                          (when reg (swap! reg update id assoc :harvested? true))
                          (if (contains? r :ok) (:ok r) (throw (:err r))))
              waiter (fx/via-blk
                       (try
                            (deref woke ms ::timeout)
                            (if (and (realized? p) (not (released?)))
                              (let [r @p] (if (contains? r :ok) (:ok r) (throw (:err r))))
                              (do
                                  (reset! detached? true)
                                  (let [id (start! ctx {:name (str "tool: " tool-name)
                                                        :job-type :tool
                                                        ;; the work is already running: adopt its
                                                        ;; promise, unwrapping what fx/run put there
                                                        :task (m/sp (let [r (m/? (fx/from-promise p))]
                                                                      (if (contains? r :ok) (:ok r) (throw (:err r)))))
                                                        :on-cancel cancel-work})]
                                    (if (released?)
                                      ;; released before it was adopted: adopted now,
                                      ;; and the turn carries on
                                      (do (events/emit! ctx :task/detached {:id id :tool tool-name :by :person})
                                          (moved-marker tool-name id))
                                      (do
                                        (events/emit! ctx :task/detached {:id id :tool tool-name})
                                        ;; stage two: it is a task now, but still this
                                        ;; call's answer if it lands in time — or until
                                        ;; the person moves it on
                                        (deref woke remaining ::timeout)
                                        (cond
                                          (released?) (do (events/emit! ctx :task/detached {:id id :tool tool-name :by :person})
                                                          (moved-marker tool-name id))
                                          (realized? p) (collected id @p)
                                          :else (marker tool-name id)))))))
                         (finally
                           (some-> backgrounders (swap! dissoc bg-key)))))
              cancel-wait (waiter s f)]
          (fn []
            (when-not @detached? (cancel-work))
            (cancel-wait)))))))

;; --- the commands -------------------------------------------------------------------

(defn- not-found [id] {:error (str "Task not found: " id)})

(def ^:private error-schema [:map [:error :string]])

(def detail-schema
  "A task as `snapshot` returns it — what `task$detail` answers and `task$wait`
  extends. `:status` and `:job-type` are keywords in the record."
  [:map
   [:id :string]
   [:name :string]
   [:status [:enum :running :completed :failed :cancelled]]
   ;; what kind of work: task$run's :bash and :tool, and whatever the kernel
   ;; starts itself — :self-improve, :team-member, :team-dispatch,
   ;; :session-ask — so an enum here goes stale with each new kind
   [:job-type [:keyword {:desc "the kind of work: :bash, :tool, :self-improve, :team-member, :team-dispatch, :session-ask"}]]
   [:owner :string]
   [:created-at :int]
   [:started-at :int]
   [:elapsed-ms :int]
   [:output {:optional true} :string]
   [:last-output-age-ms {:optional true} :int]
   [:result {:optional true} :any]
   [:completed-at {:optional true} :int]
   [:output-file {:optional true} :string]
   [:harvested? {:optional true} :boolean]
   [:quiet? {:optional true} :boolean]])

;; --- parking a turn on a task ---------------------------------------------------------

(defn wakeups
  "The session's pending wakeups. A vector, because order of parking is the
  order of resuming."
  []
  (atom []))

(def ^:private wakeup-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/task-wakeup.edn")))))

(defn- fill [template m]
  (reduce (fn [s [k v]] (str/replace s (str "{" (name k) "}") (str v))) (str template) m))

(defn- request-marker
  "The words before the request in a wake-up message: what tells a woken
  turn's own question apart from a person's."
  []
  (first (str/split (:request @wakeup-templates) #"\{request\}" 2)))

(defn original-request
  "The request a turn is working on, for a wakeup to carry: a person's
  question as it is, or — for a turn that was itself woken — the request its
  wake-up message carried, so parking again passes the original on rather
  than nesting one message in the next (byj-gly.389)."
  [question]
  (let [q (str question)
        marker (request-marker)]
    (if-let [i (str/index-of q marker)]
      (subs q (+ i (count marker)))
      q)))

(defn wakeup-message
  "What the agent is resumed WITH (`prompts/task-wakeup.edn`): the task that
  finished, the note it was parked with, and the request it parked from in
  full — a woken turn's history cuts earlier questions short, and the steps
  that finish a long request were past the cut (byj-gly.389)."
  [{:keys [task-id note request]} task]
  (let [{:keys [message] :as t} @wakeup-templates]
    (fill message {:task-id task-id
                   :status (clojure.core/name (or (:status task) :unknown))
                   :note (if (str/blank? (str note)) "" (fill (:note t) {:note note}))
                   :request (if (str/blank? (str request)) "" (fill (:request t) {:request request}))})))

(defn ready-wakeup!
  "The first wakeup whose task has finished, removed from the list, rendered as
  the turn to run — or nil. A front end calls this while it waits for input:
  a parked turn resumes because something ELSE finished, so nothing can be
  waiting on the person to type."
  [reg task-registry]
  (when reg
    (let [now (System/currentTimeMillis)
          pending @reg
          ready (first (filter (fn [{:keys [task-id wake-at]}]
                                 (if wake-at
                                   (>= now wake-at)
                                   (let [t (get @task-registry task-id)]
                                     (and t (terminal? (:status t))))))
                               pending))]
      (when ready
        (swap! reg (fn [ws] (vec (remove #(= (:id %) (:id ready)) ws))))
        (wakeup-message ready (get @task-registry (:task-id ready)))))))

(defn- collected!
  "A turn has `t`, a task's snapshot: when the task has ended, the turn has
  what a wakeup armed on it was for, and the wakeup is taken back — left, it
  started a turn with nothing to do once the task was already read
  (byj-gly.388). Returns `t`."
  [ctx t]
  (when-let [reg (:wakeups ctx)]
    (when (and t (terminal? (:status t)) (some #(= (:id t) (:task-id %)) @reg))
      (swap! reg (fn [ws] (vec (remove #(= (:id t) (:task-id %)) ws))))
      (events/emit! ctx :task/wakeup-disarmed {:task-id (:id t)})))
  t)

(defn parked-on?
  "Is a wakeup already armed for `task-id` in `reg`? Arming twice would resume
  the same turn twice for one task."
  [reg task-id]
  (boolean (and reg (some #(= task-id (:task-id %)) @reg))))

(defn- parked-request
  "The request the turn parking now is working on, for its wakeup to carry
  (byj-gly.389): an agent's :question, or in a session — whose tree nests the
  react loop in its own memory — the line, :input; a woken turn's own wake-up
  passes on the original it carried."
  [ctx]
  (some-> (:st-memory ctx) deref ((juxt :question :input)) (->> (some not-empty)) original-request not-empty))

(defn park!
  "Arm a wakeup and return it, or nil when this run cannot resume one.

  Shared by `task$wakeup`, where the model asks, and by auto-park, where the
  runtime decides (RFC-019, `byj-gly.47`). Both park the same way: brainyard
  runs a SECOND watch mechanism beside its `task$wakeup`, deliberately
  mirroring it, and a mirror is a thing that can stop matching
  (divergence `auto-park-reuses-the-wakeup`).

  Refuses for the reasons `task$wakeup` refuses: nothing to resume into, a
  subagent whose turn ends first, or the session's wakeup budget spent."
  [ctx {:keys [task-id note wake-at]}]
  (let [config (:config ctx)
        reg (:wakeups ctx)
        cap (:max-wakeups-per-session config 20)]
    (when (and reg
               (= "root" (owner-of config))
               (< (count @reg) cap)
               (not (parked-on? reg task-id)))
      (let [request (parked-request ctx)
            w (cond-> {:id (str (gensym "wakeup")) :note note :at (System/currentTimeMillis)
                       :task-id task-id}
                wake-at (assoc :wake-at wake-at)
                request (assoc :request request))]
        (swap! reg conj w)
        (events/emit! ctx :task/parked (select-keys w [:task-id :note]))
        w))))

(defn running-for-owner
  "Snapshots of the tasks `owner` still has running."
  [reg owner]
  (when reg
    (vec (for [[_ t] (sort-by key @reg)
               :when (and (= owner (:owner t)) (= :running (:status t)))]
           (snapshot reg (:id t))))))

(defn wakeup-command
  "brainyard's `task$wakeup`: park this turn and be resumed when a task ends, or
  after a timer. It needs a session to resume INTO, so a one-shot run refuses
  it (RFC-018's `:subagent-scope`), and so does a subagent, whose turn ends
  before a wakeup could fire — brainyard's rule."
  []
  {:name "task$wakeup" :kind :command
   :description (str "Park this turn and be resumed automatically when a background task finishes."
                     " Answer in one line and STOP — do not call another tool; you will be given a fresh"
                     " turn when it lands, with this conversation behind you. Use this for a long wait,"
                     " where task$wait would hold the session open doing nothing;"
                     " use task$wait for a short one.")
   :input-schema [:map [:task-id {:optional true} [:string {:desc "The task to wait for."}]]
                       [:delay-ms {:optional true} [:int {:desc "Or resume after this long, with no task."}]]
                       [:note {:optional true} [:string {:desc "A line to yourself, given back when you resume."}]]]
   :output-schema [:or [:map [:parked :boolean] [:task-id :string] [:message :string]] error-schema]
   :effects #{}
   :fn (fn [{:keys [task-id delay-ms note]} ctx]
         (let [config (:config ctx)
               reg (:wakeups ctx)
               tasks (:task-registry ctx)
               cap (:max-wakeups-per-session config 20)]
           (cond
             (nil? reg)
             {:error "nothing here will resume a parked turn — this is a one-shot run, so wait with task$wait instead"}

             (not= "root" (owner-of config))
             {:error "task$wakeup is for the top-level agent only: a subagent's turn ends before a wakeup could fire"}

             (and task-id delay-ms) {:error "provide only one of task-id or delay-ms, not both"}
             (and (nil? task-id) (nil? delay-ms)) {:error "provide either task-id (wait for a task) or delay-ms (a timer)"}
             (and delay-ms (not (pos? delay-ms))) {:error "delay-ms must be positive"}
             (and task-id (nil? (get @tasks task-id))) (not-found task-id)
             (>= (count @reg) cap)
             {:error (str "wakeup budget exhausted for this session (max " cap "); use task$wait instead")}

             :else
             (let [request (parked-request ctx)
                   w (cond-> {:id (str (gensym "wakeup")) :note note :at (System/currentTimeMillis)}
                       request (assoc :request request)
                       task-id (assoc :task-id task-id)
                       delay-ms (assoc :wake-at (+ (System/currentTimeMillis) delay-ms)
                                       :task-id (str "timer+" delay-ms "ms")))]
               (swap! reg conj w)
               (events/emit! ctx :task/parked (select-keys w [:task-id :note]))
               {:parked true :task-id (:task-id w)
                :message (if delay-ms
                           (str "Parked. You will be resumed in " delay-ms "ms.")
                           (str "Parked. You will be resumed when " task-id " finishes."
                                " Answer in one line and stop."))}))))})

(defn commands
  "brainyard's `task$*`, less the halves that need what this port does not have:
  a turn submitter (`task$wakeup`) and streamed output."
  []
  [{:name "task$run" :kind :command
    :description (str "Start a job in the background and get a task-id back. Use it for work you know will"
                      " take minutes — a build, a long test run — so the turn is not spent waiting."
                      " job-type \"bash\" takes `command`; job-type \"tool\" takes `tool-id` and `tool-args`"
                      " and runs any registered tool, gates and all. Poll with task$detail, block with task$wait,"
                      " stop with task$cancel.")
    :input-schema [:map [:job-type [:string {:desc "\"bash\" or \"tool\""}]]
                        [:command {:optional true} [:string {:desc "bash: the command line"}]]
                        [:tool-id {:optional true} [:string {:desc "tool: the tool to run"}]]
                        [:tool-args {:optional true} [:map {:desc "tool: its arguments"}]]
                        [:name {:optional true} [:string {:desc "A label for listings."}]]
                        [:timeout {:optional true} [:int {:desc "ms before the job is killed (default 120000)"}]]]
    :output-schema [:or [:map [:task-id :string] [:name :string] [:job-type [:enum "bash" "tool"]]
                      [:status [:enum "running"]]]
               error-schema]
    ;; a backgrounded command is still a command: the gate reads this arg too,
    ;; so task$run cannot be a way around it (RFC-012)
    :command-params [:command]
    :effects #{:process :dynamic}
    :fn (fn [{:keys [job-type command tool-id tool-args name timeout]} ctx]
          (let [config (:config ctx)
                timeout (or timeout (:task-timeout-ms config 120000))]
            (cond
              (not (:tasks? config true)) {:error "background tasks are disabled"}

              (= "bash" job-type)
              (if (str/blank? (str command))
                {:error "command is required"}
                (let [id (start! ctx {:name (or name (str "bash: " (cap command 60)))
                                      :job-type :bash
                                      :task-fn (fn [append!]
                                                 (bash-task {:command command :timeout timeout
                                                             :working-dir (:working-dir config)}
                                                            append!))})]
                  {:task-id id :name (:name (snapshot (:task-registry ctx) id))
                   :job-type "bash" :status "running"}))

              (= "tool" job-type)
              (if-let [tool (get @(:tools ctx) (str tool-id))]
                (let [id (start! ctx {:name (or name (str "tool: " tool-id))
                                      :job-type :tool
                                      :task (fx/timeout
                                             (fx/task-of #(binding [*in-task?* true]
                                                            (tools/call-tool tool (or tool-args {}) ctx)))
                                             timeout
                                             {:error (str tool-id " timed out after " timeout " ms")})})]
                  {:task-id id :name (:name (snapshot (:task-registry ctx) id))
                   :job-type "tool" :status "running"})
                {:error (str "tool(ID:" tool-id ") is not defined in system")})

              :else {:error (str "Unknown :job-type '" job-type "'. Valid: bash tool")})))}

   {:name "task$list" :kind :command
    :description "Every background task this session started, with its status."
    :input-schema [:map [:status {:optional true} [:string {:desc "Filter: running, completed, failed, cancelled"}]]]
    :output-schema [:map [:tasks [:vector [:map [:id :string] [:name :string]
                                     [:status [:enum :running :completed :failed :cancelled]]
                                     ;; every kind a task is, not only what task$run
                                     ;; starts: a team member, a dispatcher, a
                                     ;; session-ask are listed too (byj-gly.301)
                                     [:job-type [:keyword {:desc "the kind of work: :bash, :tool, :team-member, :team-dispatch, :session-ask"}]]
                                     [:elapsed-ms :int]]]]
               [:total :int]]
    :effects #{}
    :fn (fn [{:keys [status]} ctx]
          ;; the work the agent started: self-improvement's judges are the
          ;; person's to see (/task), not the model's (byj-gly.247)
          (let [all (filterv #(not= :self-improve (:job-type %)) (summaries (:task-registry ctx)))
                rows (if (str/blank? (str status))
                       all
                       (filterv #(= (keyword status) (:status %)) all))]
            {:tasks rows :total (count rows)}))}

   {:name "task$detail" :kind :command
    :description (str "One task: its status, how long it has been running, and whatever it has printed SO FAR."
                      " :last-output-age-ms is how long since it last printed anything — that, not elapsed time,"
                      " is what tells a working task from a wedged one. A task can be quiet for minutes and be"
                      " perfectly healthy, so do not cancel one on a quiet window alone.")
    :input-schema [:map [:task-id [:string {:desc "e.g. task-mkq8f3x2-7"}]]]
    :output-schema [:or {:desc ":output is what it has printed so far; :result, :completed-at and :output-file arrive once it has finished"}
               detail-schema error-schema]
    :effects #{}
    :fn (fn [{:keys [task-id]} ctx]
          (if (str/blank? (str task-id))
            {:error "task-id is required"}
            (or (collected! ctx (snapshot (:task-registry ctx) task-id)) (not-found task-id))))}

   {:name "task$wait" :kind :command
    :description (str "Block until a task finishes, or until `timeout-ms` passes — on timeout it is reported as still running, never cancelled."
                      " A wait is held to the most any call is waited on (:auto-background-timeout-ms, 180000 ms by default):"
                      " for longer, park with task$wakeup and end the turn.")
    :input-schema [:map [:task-id :string]
                        [:timeout-ms [:int {:desc "how long to wait before giving up on waiting; at most :auto-background-timeout-ms"}]]]
    :output-schema [:or {:desc "the task$detail shape; :still-running true when the wait timed out, and the task is not cancelled"}
               (conj detail-schema [:still-running {:optional true} :boolean] [:note {:optional true} :string])
               error-schema]
    :effects #{:dynamic}
    :fn (fn [{:keys [task-id timeout-ms]} ctx]
          ;; a task$* call never detaches (detachable?), so this is the turn
          ;; held: no longer than any other call is waited on (byj-gly.390)
          (let [cap (:auto-background-timeout-ms (:config ctx) 180000)]
            (cond
              (str/blank? (str task-id)) {:error "task-id is required"}
              (not (and (integer? timeout-ms) (pos? timeout-ms)))
              {:error "timeout-ms is required and must be positive"}
              :else
              (if-let [t (collected! ctx (wait (:task-registry ctx) task-id (min timeout-ms cap)))]
                (cond-> t
                  (= :running (:status t)) (assoc :still-running true)
                  (and (= :running (:status t)) (> timeout-ms cap))
                  (assoc :note (str "waited " cap " ms, the most a call is waited on, not the " timeout-ms " asked:"
                                    " park with task$wakeup {\"task-id\": \"" task-id "\"} and end the turn, to be resumed when it ends")))
                (not-found task-id)))))}

   {:name "task$cancel" :kind :command
    :description "Stop a running task. A bash task's whole process tree goes with it."
    :input-schema [:map [:task-id :string]]
    :output-schema [:or [:map [:result :string]] error-schema]
    :effects #{:dynamic}
    :fn (fn [{:keys [task-id]} ctx]
          (let [reg (:task-registry ctx)]
            (cond
              (str/blank? (str task-id)) {:error "task-id is required"}
              (nil? (get @reg task-id)) (not-found task-id)
              (cancel! reg task-id) {:result (str "Cancelled: " task-id)}
              :else {:error (str "Could not cancel: " task-id " (it is "
                                 (clojure.core/name (:status (snapshot reg task-id))) ")")})))}
   (wakeup-command)])

;; --- harvest ---------------------------------------------------------------------------

(defn took
  "How long, in the unit a person would use."
  [ms]
  (let [ms (or ms 0)]
    (if (< ms 1000) (str ms "ms") (str (quot ms 1000) "s"))))

(defn- rendered
  "A finished task in one line plus its output — what replaces the marker, and
  what a completion notice carries."
  [{:keys [id name status result output elapsed-ms]} max-chars]
  (str "[background] " id " (" name ") " (clojure.core/name status)
       " after " (took elapsed-ms)
       (when result (str ": " (pr-str result)))
       (when-not (str/blank? (str output))
         (str "\n" (cap output (or max-chars 4000))))))

(defn resolve-markers
  "Rewrite a STILL RUNNING marker with the result it was standing in for —
  brainyard's `resolve-tool-entries`. Only the history the MODEL reads is
  rewritten; the trajectory keeps what actually happened at the time."
  [iterations reg max-chars]
  (let [done (fn [id] (let [t (snapshot reg id)]
                        (when (and t (terminal? (:status t))) t)))]
    (mapv (fn [it]
            (if-not (:tool-results it)
              it
              (update it :tool-results
                      (fn [rs]
                        (mapv (fn [r]
                                (let [text (str (:tool-result r))]
                                  (if-let [id (and (str/includes? text "STILL RUNNING")
                                                   (second (re-find #"task-id=(\S+?)[.\s]" text)))]
                                    (if-let [t (done id)]
                                      (assoc r :tool-result (rendered t max-chars))
                                      r)
                                    r)))
                              rs)))))
          iterations)))

(defn harvest!
  "Tasks of `owner` that have finished since the last look, marked as reported
  so they are reported once — brainyard's `:harvested?` flag. Returns
  `{:completed [snapshots] :running [snapshots]}`."
  [reg owner]
  (let [ids (for [[id t] @reg
                  :when (and (= owner (:owner t)) (terminal? (:status t)) (not (:harvested? t)))]
              id)
        ;; a quiet task is the machinery of other work — a team member's
        ;; process, a share's sidecar — and neither its ending nor its running
        ;; is news to the model (RFC-019 "quiet", provenance P-35); it is still marked harvested
        completed (into [] (comp (remove #(:quiet? (get @reg %))) (map #(snapshot reg %))) (sort ids))]
    (doseq [id ids] (swap! reg assoc-in [id :harvested?] true))
    {:completed completed
     :running (vec (for [[_ t] (sort-by key @reg)
                         :when (and (= owner (:owner t)) (= :running (:status t)) (not (:quiet? t)))]
                     (snapshot reg (:id t))))}))

(defn notice
  "What the model is told about work that finished, or is still going, while it
  was doing something else. Nil when there is nothing to say."
  [{:keys [completed running]} max-chars]
  (let [lines (concat (map #(rendered % max-chars) completed)
                      (for [t running]
                        (str "[background] " (:id t) " (" (:name t) ") still running, "
                             (took (:elapsed-ms t)) " so far"
                             " — task$wait for it when you need it.")))]
    (when (seq lines) (str/join "\n" lines))))
