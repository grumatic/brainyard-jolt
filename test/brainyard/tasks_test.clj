(ns brainyard.tasks-test
  "Background tasks (RFC-019): what detaches, what never does, and what happens
  to a task nobody is waiting for."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.schedule :as schedule]
            [brainyard.agent :as agent]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [brainyard.persist]
            [brainyard.schema :as schema]
            [brainyard.session :as session]
            [brainyard.tasks :as tasks]
            [brainyard.tools :as tools]
            [jolt.process :as p]))

(defn- fresh-dir [name]
  (let [d (io/file "target/tasks-test" name)]
    (doseq [f (reverse (file-seq d))] (.delete f))
    (.mkdirs d)
    (.getPath d)))

(defn- alive? [marker]
  (not (str/blank? (:out (p/sh "pgrep" "-f" marker)))))

(defn- wait-until [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn- sleeper [] (str "sleep 30." (rand-int 1000000)))

(defn- reply [m] (json/write-str (merge {:reasoning "r" :tool-calls [] :answer ""} m)))

(defn- ctx-for [dir & {:as config}]
  (agent/make-ctx {:config (merge {:working-dir dir :project-dir dir} config)}))

(defn- cmd [ctx n args] (tools/call-tool (get @(:tools ctx) n) args ctx))

;; --- ids -----------------------------------------------------------------------------

(deftest task-ids
  (let [ids (repeatedly 200 tasks/task-id)]
    (is (apply distinct? ids) "unique without relying on rand (provenance P-13)")
    (is (= (sort ids) (vec ids)) "and minted in an order that sorts by age")
    (is (every? #(re-matches #"task-[a-z0-9]+-\d+" %) ids))))

;; --- auto-detach ----------------------------------------------------------------------

(deftest a-slow-call-detaches-and-keeps-running
  (let [dir (fresh-dir "detach")
        marker (sleeper)
        events (atom [])
        replies (atom [(reply {:tool-calls [{:tool-name "bash" :tool-args {:command marker}}]})
                       (reply {:answer "it is still going"})])
        reg (tasks/registry)
        r (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (fn [_] (first (first (swap-vals! replies #(vec (rest %))))))}
                          :config {:max-iterations 4 :working-dir dir :project-dir dir
                                   ;; both deadlines: the caller gives up as soon
                                   ;; as the call becomes a task (RFC-019)
                                   :fast-eval-timeout-ms 250
                                   :auto-background-timeout-ms 250}
                          :task-registry reg
                          :on-event (fn [e d] (swap! events conj [e d]))})
        result (:tool-result (first (:tool-results (first (:trajectory r)))))
        id (second (re-find #"task-id=(\S+?)\." result))]
    (testing "the model is told the call is still running, and how to collect it"
      (is (str/includes? result "STILL RUNNING"))
      (is (str/includes? result "DO NOT re-call bash"))
      (is (str/includes? result "task$wait"))
      (is (some? id)))
    (testing "the work is still running after the turn that started it answered"
      (is (= "it is still going" (:answer r)))
      (is (alive? marker) "the subprocess outlived the tool call")
      (is (= :running (:status (tasks/snapshot reg id)))))
    (testing "the loop reported the detach"
      (is (= [:task/created :task/detached]
             (mapv first (filter #(= "task" (namespace (first %))) @events)))))
    (testing "and the session's close ends it"
      (is (= 1 (tasks/cancel-all! reg)))
      (is (wait-until 3000 #(not (alive? marker))) "no subprocess outlives the session")
      (is (= :cancelled (:status (tasks/snapshot reg id)))))))

(deftest a-fast-call-is-untouched
  (let [dir (fresh-dir "fast")
        reg (tasks/registry)
        r (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (atom [(reply {:tool-calls [{:tool-name "bash" :tool-args {:command "echo quick"}}]})
                                                 (reply {:answer "done"})])}
                          :config {:max-iterations 4 :working-dir dir :project-dir dir}
                          :task-registry reg})
        result (:tool-result (first (:tool-results (first (:trajectory r)))))]
    (is (str/includes? result "quick") "the result came back inline")
    (is (not (str/includes? result "STILL RUNNING")))
    (is (empty? @reg) "and nothing was registered")))

(deftest what-never-detaches
  (let [base {:tasks? true :fast-eval-timeout-ms 30000}]
    (is (tasks/detachable? {:config base} "bash") "the ordinary case")
    (testing "not inside a task: it would have nobody to report to"
      (binding [tasks/*in-task?* true]
        (is (not (tasks/detachable? {:config base} "bash")))))
    (testing "not for a subagent: its turn ends before it could collect the result"
      (is (not (tasks/detachable? {:config (assoc base :agent-instance-id "explore/amber-otter-1")} "bash"))))
    (testing "not a task$* command: task$wait detaching itself is a deadlock"
      (is (not (tasks/detachable? {:config base} "task$wait")))
      (is (not (tasks/detachable? {:config base} "task$detail"))))
    (testing "not when the layer is off, or the deadline is zero"
      (is (not (tasks/detachable? {:config (assoc base :tasks? false)} "bash")))
      (is (not (tasks/detachable? {:config (assoc base :fast-eval-timeout-ms 0)} "bash"))))))

;; --- the commands ---------------------------------------------------------------------

(deftest a-settled-task-has-its-files-before-it-has-its-status
  ;; provenance P-17. `wait` returns the instant a status is terminal, so
  ;; publishing the status before writing the files hands a turn a completed
  ;; task whose meta.edn is not there yet. The window is why this SPINS instead
  ;; of waiting: task$wait polls every 25 ms, which was long enough to hide the
  ;; bug in every isolated run and to lose only under a loaded suite.
  (let [dir (fresh-dir "settle")
        ctx (ctx-for dir)
        reg (:task-registry ctx)]
    (dotimes [i 5]
      (let [{:keys [task-id]} (cmd ctx "task$run" {:job-type "bash" :command "echo hello"})
            deadline (+ (System/currentTimeMillis) 8000)
            status (loop []
                     (let [s (:status (tasks/snapshot reg task-id))]
                       (cond
                         (#{:completed :failed :cancelled} s) s
                         (> (System/currentTimeMillis) deadline) s
                         :else (recur))))]
        (is (= :completed status) (str "run " i ": the task settled"))
        (is (.isFile (io/file dir ".brainyard" "tasks" task-id "meta.edn"))
            (str "run " i ": meta.edn exists the moment the status does"))
        (is (.isFile (io/file dir ".brainyard" "tasks" task-id "output.log"))
            (str "run " i ": output.log exists the moment the status does"))))))

(deftest task-run-starts-a-job
  (let [dir (fresh-dir "run")
        ctx (ctx-for dir)]
    (testing "a bash job runs, and its output is kept on disk as well as in the result"
      (let [{:keys [task-id]} (cmd ctx "task$run" {:job-type "bash" :command "echo hello"})
            done (cmd ctx "task$wait" {:task-id task-id :timeout-ms 8000})]
        (is (= :completed (:status done)))
        (is (= {:exit-code 0} (:result done)))
        (is (str/includes? (:output done) "hello"))
        (is (.isFile (io/file dir ".brainyard" "tasks" task-id "output.log")))
        (is (.isFile (io/file dir ".brainyard" "tasks" task-id "meta.edn")))))

    (testing "a tool job runs any registered tool, through the same gates"
      (let [{:keys [task-id]} (cmd ctx "task$run" {:job-type "tool" :tool-id "list-dir" :tool-args {:path "."}})
            done (cmd ctx "task$wait" {:task-id task-id :timeout-ms 8000})]
        (is (= :completed (:status done)))
        (is (contains? (:result done) :entries))))

    (testing "a non-zero exit is a result, not a failure — as it is in brainyard"
      (let [{:keys [task-id]} (cmd ctx "task$run" {:job-type "bash" :command "exit 3"})
            done (cmd ctx "task$wait" {:task-id task-id :timeout-ms 8000})]
        (is (= :completed (:status done)))
        (is (= 3 (:exit-code (:result done))))))

    (testing "a job that errors IS a failure, and its own deadline kills it"
      (let [{:keys [task-id]} (cmd ctx "task$run" {:job-type "bash" :command (sleeper) :timeout 150})
            done (cmd ctx "task$wait" {:task-id task-id :timeout-ms 8000})]
        (is (= :failed (:status done)))
        (is (str/includes? (:error (:result done)) "timed out after 150 ms"))))

    (testing "the error paths"
      (is (= {:error "command is required"} (cmd ctx "task$run" {:job-type "bash"})))
      (is (str/includes? (:error (cmd ctx "task$run" {:job-type "tool" :tool-id "nope"})) "is not defined in system"))
      (is (str/includes? (:error (cmd ctx "task$run" {:job-type "wat"})) "Valid: bash tool"))
      ;; a missing required arg is caught by the tool layer before the command
      ;; body runs (RFC-007); the body's own check is for a blank one
      (is (str/includes? (:error-message (cmd ctx "task$detail" {})) "missing required `task-id`"))
      (is (= {:error "task-id is required"} (cmd ctx "task$detail" {:task-id "   "})))
      (is (= {:error "Task not found: task-nope"} (cmd ctx "task$detail" {:task-id "task-nope"})))
      (is (= {:error "Task not found: task-nope"} (cmd ctx "task$cancel" {:task-id "task-nope"}))))

    (testing "list reports what this session started"
      (let [{:keys [tasks total]} (cmd ctx "task$list" {})]
        (is (= 4 total))
        (is (= #{:completed :failed} (set (map :status tasks))))
        (is (= 3 (:total (cmd ctx "task$list" {:status "completed"}))))
        (is (= 1 (:total (cmd ctx "task$list" {:status "failed"}))))
        (is (= 0 (:total (cmd ctx "task$list" {:status "running"}))))))))

(deftest waiting-on-a-task
  (let [dir (fresh-dir "wait")
        ctx (ctx-for dir)
        marker (sleeper)
        {:keys [task-id]} (cmd ctx "task$run" {:job-type "bash" :command marker})]
    (is (wait-until 3000 #(alive? marker)) "non-vacuous: it is running")
    (testing "a timed-out wait reports it as running and does NOT cancel it"
      (let [w (cmd ctx "task$wait" {:task-id task-id :timeout-ms 150})]
        (is (= :running (:status w)))
        (is (true? (:still-running w)))
        (is (alive? marker) "waiting is not cancelling")))
    (testing "bad arguments"
      (is (str/includes? (:error-message (cmd ctx "task$wait" {:task-id task-id}))
                         "missing required `timeout-ms`"))
      (is (= {:error "timeout-ms is required and must be positive"}
             (cmd ctx "task$wait" {:task-id task-id :timeout-ms 0})))
      (is (= {:error "task-id is required"} (cmd ctx "task$wait" {:task-id " " :timeout-ms 10}))))
    (testing "a terminal task comes back at once"
      (is (= {:result (str "Cancelled: " task-id)} (cmd ctx "task$cancel" {:task-id task-id})))
      (let [w (cmd ctx "task$wait" {:task-id task-id :timeout-ms 5000})]
        (is (= :cancelled (:status w)))
        (is (nil? (:still-running w))))
      (is (str/includes? (:error (cmd ctx "task$cancel" {:task-id task-id})) "it is cancelled")
          "cancelling a finished task says what it is, rather than pretending"))))

(deftest cancelling-a-task-kills-its-process-tree
  (let [dir (fresh-dir "cancel")
        ctx (ctx-for dir)
        marker (sleeper)
        ;; a child under a child: cancelling must reach the whole tree (RFC-009)
        {:keys [task-id]} (cmd ctx "task$run" {:job-type "bash" :command (str "bash -c '" marker "'")})]
    (is (wait-until 3000 #(alive? marker)) "non-vacuous: the grandchild is running")
    (cmd ctx "task$cancel" {:task-id task-id})
    (is (wait-until 3000 #(not (alive? marker))) "the whole tree goes")
    (is (wait-until 2000 #(= :cancelled (:status (cmd ctx "task$detail" {:task-id task-id})))))
    (is (= {:error "cancelled"} (:result (cmd ctx "task$detail" {:task-id task-id}))))))

(deftest a-session-closes-its-tasks
  (let [dir (fresh-dir "session-close")
        ctx (ctx-for dir)
        marker (sleeper)
        emitted (atom [])
        _ (cmd ctx "task$run" {:job-type "bash" :command marker})]
    (is (wait-until 3000 #(alive? marker)) "non-vacuous: it is running")
    (session/close (assoc ctx :st-memory (atom {}) :emit (fn [k t] (swap! emitted conj [k t]))
                          :config (assoc (:config ctx) :persist-session? false)))
    (is (wait-until 3000 #(not (alive? marker))) "the session takes its tasks with it")
    (is (some #(str/includes? (str (second %)) "cancelled 1 background task") @emitted))))

;; --- harvest (RFC-019) ----------------------------------------------------------------

(defn- finished-registry
  "A registry holding one finished task, owned by `owner`."
  [owner]
  (let [reg (tasks/registry)]
    (swap! reg assoc "task-x1" {:id "task-x1" :name "tool: bash" :status :completed :job-type :tool
                                :owner owner :result {:exit-code 0} :output "THE-ANSWER-IS-42"
                                :started-at 0 :completed-at 1400})
    reg))

(deftest a-finished-task-replaces-its-marker
  (let [reg (finished-registry "root")
        iterations [{:iteration 1
                     :tool-results [{:tool-name "bash"
                                     :tool-result "[bash STILL RUNNING — task-id=task-x1. DO NOT re-call bash]"}]}
                    {:iteration 2 :tool-results [{:tool-name "list-dir" :tool-result "{:entries []}"}]}]
        rewritten (tasks/resolve-markers iterations reg 4000)]
    (testing "the history the model reads carries the result, not the promise of one"
      (let [text (:tool-result (first (:tool-results (first rewritten))))]
        (is (not (str/includes? text "STILL RUNNING")))
        (is (str/includes? text "THE-ANSWER-IS-42"))
        (is (str/includes? text "completed after 1s"))))
    (testing "and everything else is left alone"
      (is (= (second iterations) (second rewritten))))
    (testing "a marker whose task is still running stays as it is"
      (swap! reg assoc-in ["task-x1" :status] :running)
      (is (= iterations (tasks/resolve-markers iterations reg 4000))))))

(deftest a-completion-is-reported-once
  (let [reg (finished-registry "root")]
    (let [{:keys [completed running]} (tasks/harvest! reg "root")]
      (is (= ["task-x1"] (map :id completed)))
      (is (empty? running))
      (is (str/includes? (tasks/notice {:completed completed :running running} 4000)
                         "THE-ANSWER-IS-42")))
    (testing "a second look has nothing to say — brainyard's :harvested? flag"
      (let [h (tasks/harvest! reg "root")]
        (is (empty? (:completed h)))
        (is (nil? (tasks/notice h 4000)))))))

(deftest only-the-owner-harvests
  (let [reg (finished-registry "explore/amber-otter-1")]
    (is (empty? (:completed (tasks/harvest! reg "root")))
        "a subagent's task is not its caller's to report")
    (is (= ["task-x1"] (map :id (:completed (tasks/harvest! reg "explore/amber-otter-1")))))))

(deftest a-detached-result-comes-back-without-being-asked-for
  (let [dir (fresh-dir "harvest-loop")
        reg (tasks/registry)
        events (atom [])
        histories (atom [])
        replies (atom [(reply {:tool-calls [{:tool-name "bash" :tool-args {:command "sleep 0.3; echo THE-ANSWER-IS-42"}}]})
                       (reply {:tool-calls [{:tool-name "list-dir" :tool-args {:path "."}}]})
                       (reply {:answer "done"})])
        r (agent/ask "q"
                     {:lm {:provider :mock :format :mock
                           :responses (fn [msgs]
                                        (swap! histories conj (:content (last msgs)))
                                        ;; deterministic: once a task exists, let it finish before
                                        ;; the next iteration, rather than racing the clock
                                        (when-let [id (first (keys @reg))]
                                          (tasks/wait reg id 8000))
                                        (first (first (swap-vals! replies #(vec (rest %))))))}
                      :config {:max-iterations 5 :working-dir dir :project-dir dir
                               :fast-eval-timeout-ms 200
                               :auto-background-timeout-ms 200}
                      :task-registry reg
                      :on-event (fn [e d] (swap! events conj [e d]))})
        last-seen (last @histories)]
    (testing "the model never called task$wait, and still has the answer in its history"
      (is (= "done" (:answer r)))
      (is (not-any? #(= "task$wait" (:tool-name %))
                    (mapcat :tool-results (:trajectory r))))
      (is (str/includes? last-seen "THE-ANSWER-IS-42"))
      (is (not (str/includes? last-seen "STILL RUNNING"))))
    (testing "the trajectory keeps what actually happened at the time"
      (is (str/includes? (:tool-result (first (:tool-results (first (:trajectory r)))))
                         "STILL RUNNING")))
    (testing "and the loop reported the harvest"
      (is (seq (filter #(= :agent.tasks/harvested (first %)) @events))))))

(deftest task-directories-are-pruned-at-startup
  (let [dir (fresh-dir "prune")
        old (- (System/currentTimeMillis) (* 30 24 60 60 1000))
        mk (fn [id at]
             (let [d (io/file dir ".brainyard" "tasks" id)]
               (.mkdirs d)
               (spit (io/file d "output.log") "x")
               (.setLastModified d at)
               d))
        stale (mk "task-old-1" old)
        recent (mk "task-new-2" (System/currentTimeMillis))
        {:keys [removed]} (brainyard.persist/prune!
                           {:project-dir dir :working-dir dir
                            :task-retention-count 1 :task-retention-days 7})]
    (is (= ["task-old-1"] removed) "past the TTL and outside the keep count")
    (is (not (.exists stale)))
    (is (.exists recent) "the newest is kept whatever the age rule says")))

(deftest a-detached-call-is-still-waited-for
  (testing "brainyard's second stage: adopted at the first deadline, collected until the second"
    (let [dir (fresh-dir "two-stage")
          reg (tasks/registry)
          events (atom [])
          r (agent/ask "q" {:lm {:provider :mock :format :mock
                                 :responses (atom [(reply {:tool-calls [{:tool-name "bash"
                                                                         :tool-args {:command "sleep 0.5; echo LANDED"}}]})
                                                   (reply {:answer "done"})])}
                            :config {:max-iterations 4 :working-dir dir :project-dir dir
                                     ;; a task at 150ms, but the caller waits a full second
                                     :fast-eval-timeout-ms 150
                                     :auto-background-timeout-ms 1000}
                            :task-registry reg
                            :on-event (fn [e d] (swap! events conj [e d]))})
          result (:tool-result (first (:tool-results (first (:trajectory r)))))
          id (:id (first (tasks/summaries reg)))]
      (is (str/includes? result "LANDED") "the caller got the real result, not a marker")
      (is (not (str/includes? result "STILL RUNNING")))
      (testing "and it was a task all the same: it has an id, and could have been cancelled"
        (is (some? id))
        (is (= [:task/created :task/detached :task/completed]
               (mapv first (filter #(= "task" (namespace (first %))) @events)))))
      (testing "what the caller collected is not announced again"
        (is (true? (:harvested? (get @reg id))))
        (is (empty? (:completed (tasks/harvest! reg "root"))))))))

(deftest past-both-deadlines-the-marker-comes-back
  (let [dir (fresh-dir "two-stage-out")
        reg (tasks/registry)
        marker-cmd (sleeper)
        r (agent/ask "q" {:lm {:provider :mock :format :mock
                               :responses (atom [(reply {:tool-calls [{:tool-name "bash"
                                                                       :tool-args {:command marker-cmd}}]})
                                                 (reply {:answer "moved on"})])}
                          :config {:max-iterations 4 :working-dir dir :project-dir dir
                                   :fast-eval-timeout-ms 100
                                   :auto-background-timeout-ms 400}
                          :task-registry reg})
        result (:tool-result (first (:tool-results (first (:trajectory r)))))]
    (is (str/includes? result "STILL RUNNING"))
    (is (= "moved on" (:answer r)))
    (tasks/cancel-all! reg)))

(deftest a-task-shows-what-it-has-printed-so-far
  (let [dir (fresh-dir "streaming")
        ctx (ctx-for dir)
        marker (str "by-stream-" (rand-int 1000000))
        {:keys [task-id]} (cmd ctx "task$run"
                               {:job-type "bash"
                                :command (str "echo " marker "-one; sleep 1.2; echo " marker "-two")})]
    (testing "the first line is visible while the task is still running"
      (is (wait-until 3000 #(str/includes? (str (:output (cmd ctx "task$detail" {:task-id task-id})))
                                           (str marker "-one"))))
      (let [d (cmd ctx "task$detail" {:task-id task-id})]
        (is (= :running (:status d)) "non-vacuous: it has not finished")
        (is (not (str/includes? (str (:output d)) (str marker "-two"))) "and the second line has not been printed")
        (is (number? (:last-output-age-ms d))
            "how long since it last printed is what separates working from wedged")
        (is (< (:last-output-age-ms d) 3000))))

    (testing "and the whole output is there when it ends"
      (let [done (cmd ctx "task$wait" {:task-id task-id :timeout-ms 8000})]
        (is (= :completed (:status done)))
        (is (str/includes? (:output done) (str marker "-one")))
        (is (str/includes? (:output done) (str marker "-two")))
        (is (nil? (:last-output-age-ms done)) "a finished task is not measured for liveness")))))

;; --- parking a turn (RFC-019) ---------------------------------------------------------

(deftest a-turn-can-park-itself-on-a-task
  (let [dir (fresh-dir "wakeup")
        ws (tasks/wakeups)
        reg (tasks/registry)
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}
                             :wakeups ws :task-registry reg})
        call (fn [n args] (tools/call-tool (get @(:tools ctx) n) args ctx))
        {:keys [task-id]} (call "task$run" {:job-type "bash" :command "sleep 0.4; echo finished"})]

    (testing "parking is accepted and says what will happen"
      (let [r (call "task$wakeup" {:task-id task-id :note "check the build"})]
        (is (true? (:parked r)))
        (is (= task-id (:task-id r)))
        (is (str/includes? (:message r) "resumed when"))))

    (testing "nothing resumes while the task is running"
      (is (nil? (tasks/ready-wakeup! ws reg))))

    (testing "and when it lands, the resume is a TURN, carrying the note back"
      (is (wait-until 5000 #(some? (tasks/ready-wakeup! ws reg)))
          "…which also consumes it")
      (let [_ (call "task$wakeup" {:task-id task-id :note "second look"})
            turn (tasks/ready-wakeup! ws reg)]
        (is (str/includes? turn "[task-complete]"))
        (is (str/includes? turn task-id))
        (is (str/includes? turn "second look"))
        (is (str/includes? turn "carry on from where you stopped"))))

    (testing "a wakeup is taken once"
      (is (nil? (tasks/ready-wakeup! ws reg))))

    (testing "the guards"
      (is (str/includes? (:error (call "task$wakeup" {})) "provide either task-id"))
      (is (str/includes? (:error (call "task$wakeup" {:task-id task-id :delay-ms 5})) "not both"))
      (is (str/includes? (:error (call "task$wakeup" {:delay-ms 0})) "must be positive"))
      (is (str/includes? (:error (call "task$wakeup" {:task-id "task-nope"})) "Task not found")))

    (testing "a timer parks without a task at all"
      (call "task$wakeup" {:delay-ms 50})
      (is (wait-until 2000 #(some? (tasks/ready-wakeup! ws reg)))))

    (testing "and the budget bounds how many turns a session may park"
      (let [ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir :max-wakeups-per-session 1}
                                 :wakeups (tasks/wakeups) :task-registry reg})
            call (fn [args] (tools/call-tool (get @(:tools ctx) "task$wakeup") args ctx))]
        (is (true? (:parked (call {:delay-ms 10000}))))
        (is (str/includes? (:error (call {:delay-ms 10000})) "budget exhausted"))))))

(deftest a-one-shot-run-refuses-to-park
  (let [dir (fresh-dir "wakeup-oneshot")
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir}})]
    (is (str/includes? (:error (tools/call-tool (get @(:tools ctx) "task$wakeup") {:delay-ms 10} ctx))
                       "one-shot run")
        "there is no later turn to resume into, so it says so rather than parking forever")))

(deftest a-parked-turn-resumes-the-session
  (let [dir (fresh-dir "wakeup-session")
        ws (tasks/wakeups)
        reg (tasks/registry)
        out (atom [])
        replies (atom [;; turn 1: start something slow, park on it, stop
                       (reply {:tool-calls [{:tool-name "task$run"
                                             :tool-args {:job-type "bash" :command "sleep 0.3; echo BUILD-OK"}}]})
                       (fn [msgs]
                         ;; the id as the model would read it out of its own history,
                         ;; where the tool result is an escaped string
                         (let [id (re-find #"task-[a-z0-9]+-\d+" (str (:content (last msgs))))]
                           (reply {:tool-calls [{:tool-name "task$wakeup"
                                                 :tool-args {:task-id id :note "read the build"}}]})))
                       (reply {:answer "parked; I will pick this up when it lands"})
                       ;; turn 2: the resume
                       (reply {:tool-calls [{:tool-name "task$list" :tool-args {}}]})
                       (reply {:answer "the build finished"})])
        ;; a reader that behaves as the terminal's does: a line if one is
        ;; waiting, else a parked turn once it is ready — and, the one wakeup
        ;; taken, the end at once, where it sat out its whole wait every run
        ;; (byj-gly.442)
        lines (atom ["start the build"])
        woke? (atom false)
        read-input (fn [_]
                     (if-let [l (first @lines)]
                       (do (swap! lines rest) l)
                       (when-not @woke?
                         (loop [n 0]
                           (if-let [w (tasks/ready-wakeup! ws reg)]
                             (do (reset! woke? true) w)
                             (when (< n 100) (Thread/sleep 50) (recur (inc n))))))))
        final (session/run {:lm {:provider :mock :format :mock
                                 :responses (fn [msgs]
                                              (let [[r] (swap-vals! replies #(vec (rest %)))
                                                    r (first r)]
                                                (if (fn? r) (r msgs) r)))}
                            :config {:working-dir dir :project-dir dir :max-iterations 6
                                     :persist-session? false}
                            :wakeups ws :task-registry reg
                            :read-input read-input
                            :emit (fn [kind text] (swap! out conj [kind text]))})]
    (testing "the session ran a second turn nobody typed"
      (is (= 2 (:turn-count final)))
      (is (= ["start the build"] (take 1 (map :question (:previous-turns final)))))
      (is (str/includes? (second (map :question (:previous-turns final))) "[task-complete]")
          "the resumed turn's question IS the wakeup"))
    (testing "and it carried the note and the finished task"
      (is (str/includes? (second (map :question (:previous-turns final))) "read the build")))
    (testing "and the request it parked from, in full (byj-gly.389)"
      (is (str/ends-with? (second (map :question (:previous-turns final))) "finish it as it asks:\nstart the build")))
    (testing "the first turn stopped where it said it would"
      (is (= "parked; I will pick this up when it lands" (:answer (first (:previous-turns final)))))
      (is (= "the build finished" (:answer (second (:previous-turns final))))))))

;; --- auto-park and auto-notify (byj-gly.47) --------------------------------------------

(defn- parking-ctx
  "A ctx that can actually park: a session's wakeup list and task registry."
  [dir reg & {:as config}]
  ;; :st-memory is a TURN's, added by `ask` rather than `make-ctx` — the poll
  ;; count is per turn, so the hook needs one
  (assoc (agent/make-ctx {:config (merge {:working-dir dir :project-dir dir} config)
                          :wakeups (tasks/wakeups) :task-registry reg})
         :st-memory (atom {})))

(defn- running-task!
  "Register a task that will not finish on its own."
  [reg id]
  (swap! reg assoc id {:id id :name id :status :running :job-type :bash :owner "root"
                       :created-at 0 :started-at 0})
  id)

(deftest polling-one-task-twice-parks-the-turn
  ;; a model that starts a background task tends to poll it, and each poll costs
  ;; an iteration to be told what it already knows
  (let [dir (fresh-dir "autopark")
        reg (tasks/registry)
        id (running-task! reg "task-1")
        ctx (parking-ctx dir reg)]
    (testing "the first poll goes through, so the model sees real liveness"
      (let [r (cmd ctx "task$detail" {:task-id id})]
        (is (= :running (:status r)))
        (is (not (:hook-blocked r)))))

    (testing "the second ends the turn, and says why in the answer"
      (let [r (cmd ctx "task$detail" {:task-id id})]
        (is (true? (:hook-blocked r)))
        (is (str/includes? (:answer r) "Parked"))
        (is (str/includes? (:answer r) id))
        (is (str/includes? (:reason r) "auto-parked"))))

    (testing "and a wakeup is armed, so the turn really is resumed"
      (is (= [id] (mapv :task-id @(:wakeups ctx))))
      (swap! reg assoc-in [id :status] :completed)
      (is (str/includes? (str (tasks/ready-wakeup! (:wakeups ctx) reg)) "[task-complete]")))))

(deftest a-finished-task-is-never-deflected
  ;; the escape hatch: parking must never be able to hide an answer
  (let [dir (fresh-dir "autopark-done")
        reg (tasks/registry)
        id (running-task! reg "task-2")
        ctx (parking-ctx dir reg)]
    (cmd ctx "task$detail" {:task-id id})
    (swap! reg assoc-in [id :status] :completed)
    (dotimes [_ 4]
      (let [r (cmd ctx "task$detail" {:task-id id})]
        (is (= :completed (:status r)))
        (is (not (:hook-blocked r)))))))

(deftest polls-are-counted-per-task-and-per-turn
  (let [dir (fresh-dir "autopark-scope")
        reg (tasks/registry)
        a (running-task! reg "task-a")
        b (running-task! reg "task-b")
        ctx (parking-ctx dir reg)]
    (testing "another task's polls do not count toward this one's"
      (is (not (:hook-blocked (cmd ctx "task$detail" {:task-id a}))))
      (is (not (:hook-blocked (cmd ctx "task$detail" {:task-id b}))))
      (is (true? (:hook-blocked (cmd ctx "task$detail" {:task-id a})))))

    (testing "and a fresh turn starts the count again"
      (let [ctx2 (parking-ctx dir reg)]
        (is (not (:hook-blocked (cmd ctx2 "task$detail" {:task-id a}))))))))

(deftest a-run-that-cannot-resume-is-not-parked
  ;; parking a turn nothing will wake is worse than polling it
  (let [dir (fresh-dir "autopark-oneshot")
        reg (tasks/registry)
        id (running-task! reg "task-3")
        ;; no :wakeups — a one-shot `ask`
        ctx (assoc (agent/make-ctx {:config {:working-dir dir :project-dir dir} :task-registry reg})
                   :st-memory (atom {}))]
    (dotimes [_ 4]
      (is (not (:hook-blocked (cmd ctx "task$detail" {:task-id id})))))))

(deftest auto-park-can-be-switched-off-and-retuned
  (let [dir (fresh-dir "autopark-off")
        reg (tasks/registry)
        id (running-task! reg "task-4")]
    (testing ":task-notify? false leaves polling alone"
      (let [ctx (parking-ctx dir reg :task-notify? false)]
        (dotimes [_ 4] (is (not (:hook-blocked (cmd ctx "task$detail" {:task-id id})))))))

    (testing ":auto-park-after-polls sets the threshold"
      (let [ctx (parking-ctx dir reg :auto-park-after-polls 4)]
        (dotimes [_ 3] (is (not (:hook-blocked (cmd ctx "task$detail" {:task-id id})))))
        (is (true? (:hook-blocked (cmd ctx "task$detail" {:task-id id}))))))))

(deftest task-wait-is-counted-too-but-a-survey-is-not
  (let [dir (fresh-dir "autopark-wait")
        reg (tasks/registry)
        id (running-task! reg "task-5")
        ctx (parking-ctx dir reg)]
    (testing "task$list is a survey, not a poll of one task"
      (dotimes [_ 4] (is (not (:hook-blocked (cmd ctx "task$list" {}))))))
    (is (not (:hook-blocked (cmd ctx "task$wait" {:task-id id :timeout-ms 1}))))
    (is (true? (:hook-blocked (cmd ctx "task$wait" {:task-id id :timeout-ms 1}))))))

;; --- one arm per turn -------------------------------------------------------------------

(deftest a-turn-that-leaves-tasks-running-arms-one-wakeup
  ;; harvest folds a finished task in at an iteration boundary; a task that
  ;; finishes after the LAST iteration is never reported, because nothing runs
  (let [dir (fresh-dir "autonotify")
        reg (tasks/registry)
        _ (running-task! reg "task-x")
        _ (running-task! reg "task-y")
        ctx (parking-ctx dir reg)]
    (#'agent/arm-task-notify-action ctx)
    (testing "ONE wakeup, not one per task — an injected turn is an LLM call nobody asked for"
      (is (= 1 (count @(:wakeups ctx)))))
    (testing "and it names all of them, so the turn that wakes knows what to look at"
      (is (str/includes? (:note (first @(:wakeups ctx))) "task-x"))
      (is (str/includes? (:note (first @(:wakeups ctx))) "task-y")))))

(deftest a-turn-that-set-a-watch-is-woken-by-it-not-by-its-tasks
  ;; byj-gly.332, live: the task's wakeup came first and the model waited out
  ;; the rest with task$wait; the watches then brought news it already had
  (let [dir (fresh-dir "autonotify-watch")
        reg (tasks/registry)
        _ (running-task! reg "task-build")
        events (atom [])
        turn-start 1000000
        ctx (-> (parking-ctx dir reg :session-id "s1")
                (assoc :st-memory (atom {:started-at turn-start})
                       :on-event (fn [e d] (swap! events conj [e d]))))]
    (testing "a watch from an earlier turn is not this turn's plan: the wakeup is armed"
      (schedule/add-watch! dir {:cmd "cat old.log" :session "s1"} (dec turn-start))
      (#'agent/arm-task-notify-action ctx)
      (is (= 1 (count @(:wakeups ctx))))
      (reset! (:wakeups ctx) []))
    (testing "another session's watch is not this session's"
      (schedule/add-watch! dir {:cmd "cat theirs.log" :session "s2"} (inc turn-start))
      (#'agent/arm-task-notify-action ctx)
      (is (= 1 (count @(:wakeups ctx))))
      (reset! (:wakeups ctx) []))
    (testing "a watch this turn set is how it chose to be woken: no wakeup, and it is said"
      (let [w (schedule/add-watch! dir {:cmd "cat build.log" :session "s1"} (inc turn-start))]
        (reset! events [])
        (#'agent/arm-task-notify-action ctx)
        (is (empty? @(:wakeups ctx)))
        (is (= [[:task/auto-arm-skipped {:task-ids ["task-build"] :watches [(:id w)]}]] @events))))))

(deftest nothing-running-arms-nothing
  (let [dir (fresh-dir "autonotify-idle")
        reg (tasks/registry)
        ctx (parking-ctx dir reg)]
    (#'agent/arm-task-notify-action ctx)
    (is (empty? @(:wakeups ctx)))))

(deftest a-quiet-task-is-not-armed
  ;; a team member's process, or an ask a team's board made: the machinery of
  ;; other work, whose ending is no result (RFC-032). The tmux run woke its
  ;; leader for a turn nobody asked for when team$close stopped the members
  (let [dir (fresh-dir "autonotify-quiet")
        reg (tasks/registry)
        member (running-task! reg "task-member")
        _ (swap! reg assoc-in [member :quiet?] true)
        ctx (parking-ctx dir reg)]
    (#'agent/arm-task-notify-action ctx)
    (is (empty? @(:wakeups ctx)) "only quiet tasks running: nothing to wake for")
    (running-task! reg "task-result")
    (#'agent/arm-task-notify-action ctx)
    (is (= 1 (count @(:wakeups ctx))))
    (is (not (str/includes? (:note (first @(:wakeups ctx))) member)) "and the quiet one is not named")))

(deftest a-quiet-task-is-not-news-to-the-model
  ;; a share's sidecar runs as long as the session: the model was told every
  ;; iteration that it was "still running — task$wait for it" (RFC-043)
  (let [reg (tasks/registry)
        sidecar (running-task! reg "task-sidecar")
        _ (swap! reg assoc-in [sidecar :quiet?] true)
        work (running-task! reg "task-work")
        owner (:owner (get @reg work))
        h (tasks/harvest! reg owner)]
    (is (= [work] (mapv :id (:running h))) "only the work is said to be running")
    (is (not (str/includes? (str (tasks/notice h 1000)) sidecar)))
    (swap! reg update sidecar assoc :status :completed)
    (let [h2 (tasks/harvest! reg owner)]
      (is (empty? (:completed h2)) "nor is its ending")
      (is (:harvested? (get @reg sidecar)) "though it is marked, so it is never reported later"))))

(deftest a-task-already-parked-on-is-not-armed-twice
  (let [dir (fresh-dir "autonotify-dup")
        reg (tasks/registry)
        id (running-task! reg "task-z")
        ctx (parking-ctx dir reg)]
    (cmd ctx "task$detail" {:task-id id})
    (cmd ctx "task$detail" {:task-id id})          ; auto-parks, arming a wakeup
    (is (= 1 (count @(:wakeups ctx))))
    (#'agent/arm-task-notify-action ctx)
    (is (= 1 (count @(:wakeups ctx))) "the turn-end arm sees it is already parked on")))

(deftest a-subagent-is-not-armed-and-is-not-parked
  ;; its turn ends before a wakeup could fire — brainyard's rule, already
  ;; task$wakeup's, and now auto-park's
  (let [dir (fresh-dir "autonotify-child")
        reg (tasks/registry)
        id (running-task! reg "task-c")
        ctx (parking-ctx dir reg :agent-instance-id "explore/gray-hawk-1")]
    (swap! reg assoc-in [id :owner] "explore/gray-hawk-1")
    (dotimes [_ 4] (is (not (:hook-blocked (cmd ctx "task$detail" {:task-id id})))))
    (#'agent/arm-task-notify-action ctx)
    (is (empty? @(:wakeups ctx)))))

(deftest every-kind-of-task-is-listed-as-its-schema-says
  ;; byj-gly.301: a team member's task tripped task$list's :output-schema,
  ;; which allowed only what task$run starts
  (let [ctx (ctx-for (fresh-dir "kinds"))
        id (tasks/start! ctx {:name "team member t/a" :job-type :team-member :quiet? true
                              :task (fx/success {:done true})})
        _ (wait-until 3000 #(= :completed (:status (first (:tasks (cmd ctx "task$list" {}))))))
        listed (cmd ctx "task$list" {})]
    (is (= [[id :team-member]] (map (juxt :id :job-type) (:tasks listed))))
    (is (nil? (schema/output-problems (get @(:tools ctx) "task$list") listed)))))

(deftest a-woken-turn-is-given-the-request-it-parked-from
  ;; byj-gly.389: the history a woken turn reads cuts each earlier question to
  ;; 500 characters, and a /team request's closing steps were past the cut
  (let [long-request (str "/team add two options. " (apply str (repeat 60 "plan, assign, wait, ")) "then team$close.")
        msg (tasks/wakeup-message {:task-id "task-1" :note "the board" :request long-request} {:status :completed})]
    (is (str/starts-with? msg "[task-complete] Background task task-1 finished (completed). Your note: the board."))
    (is (str/ends-with? msg long-request) "the request, in full")
    (is (= long-request (tasks/original-request msg)) "a woken turn that parks again passes the original on")
    (is (= "a person's question" (tasks/original-request "a person's question")))
    (is (not (str/includes? (tasks/wakeup-message {:task-id "t"} {:status :failed}) "parked from"))
        "no request, nothing said of one")))

(deftest a-task-already-read-does-not-wake-the-turn-again
  ;; byj-gly.388: a team's lead armed a wakeup on each member's task, was woken
  ;; by one, waited on the other with task$wait in that turn and finished — and
  ;; the second wakeup still started a turn with nothing to do
  (let [dir (fresh-dir "wakeup-collected")
        reg (tasks/registry)
        ws (tasks/wakeups)
        events (atom [])
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir} :wakeups ws :task-registry reg
                             :on-event (fn [e d] (swap! events conj [e d]))})
        call (fn [tool args] (tools/call-tool (get @(:tools ctx) tool) args ctx))
        start (fn [cmd] (:task-id (call "task$run" {:job-type "bash" :command cmd})))
        a (start "echo A")
        b (start "sleep 0.2; echo B")]
    (is (:parked (call "task$wakeup" {:task-id a})))
    (is (:parked (call "task$wakeup" {:task-id b})))
    (is (= [a b] (mapv :task-id @ws)))
    (testing "waiting on a task until it ends takes back its wakeup"
      (is (= :completed (:status (call "task$wait" {:task-id b :timeout-ms 5000}))))
      (is (= [a] (mapv :task-id @ws)))
      (is (some #(= [:task/wakeup-disarmed {:task-id b}] %) @events)))
    (testing "reading an ended one with task$detail does too; a running one's wakeup stays"
      (let [c (start "sleep 5")]
        (is (:parked (call "task$wakeup" {:task-id c})))
        (call "task$detail" {:task-id c})
        (is (some #(= c (:task-id %)) @ws) "still running: its wakeup is still wanted")
        (call "task$cancel" {:task-id c}))
      (tasks/wait reg a 5000)
      (call "task$detail" {:task-id a})
      (is (not-any? #(= a (:task-id %)) @ws)))))

(deftest a-wait-is-held-to-the-most-a-call-is-waited-on
  ;; byj-gly.390: task$* calls never detach, so task$wait held the turn for
  ;; whatever timeout-ms the model chose
  (let [dir (fresh-dir "wait-clamped")
        reg (tasks/registry)
        ctx (agent/make-ctx {:config {:working-dir dir :project-dir dir :auto-background-timeout-ms 300}
                             :wakeups (tasks/wakeups) :task-registry reg})
        call (fn [tool args] (tools/call-tool (get @(:tools ctx) tool) args ctx))
        slow (:task-id (call "task$run" {:job-type "bash" :command "sleep 5"}))
        t0 (System/currentTimeMillis)
        r (call "task$wait" {:task-id slow :timeout-ms 60000})]
    (is (< (- (System/currentTimeMillis) t0) 3000) "not the minute asked")
    (is (true? (:still-running r)))
    (is (str/includes? (:note r) "task$wakeup") "and told how to wait longer")
    (testing "a wait asked within the cap is as asked, with nothing to say"
      (let [r (call "task$wait" {:task-id slow :timeout-ms 50})]
        (is (true? (:still-running r)))
        (is (nil? (:note r)))))
    (call "task$cancel" {:task-id slow})))

(defn- detach-run
  "`tasks/detachable` run on its own effect, its outcome in a promise."
  [ctx tool thunk]
  (let [out (promise)]
    (fx/run (tasks/detachable ctx tool thunk) #(deliver out {:ok %}) #(deliver out {:err %}))
    out))

(defn- release-all! [bg] (doseq [[_ {:keys [release]}] @bg] (release)))

(deftest the-person-moves-a-waiting-call-to-the-background
  ;; byj-gly.391: ctrl+g — a front end calls every release in :backgrounders;
  ;; the wait ends now, before the call was adopted or after
  (let [events (atom [])
        reg (tasks/registry)
        bg (atom {})
        ctx (fn [fast auto] {:config {:fast-eval-timeout-ms fast :auto-background-timeout-ms auto}
                             :task-registry reg :backgrounders bg
                             :on-event (fn [e d] (swap! events conj [e d]))})
        slow (fn [] (Thread/sleep 1200) "done")
        id-of #(second (re-find #"task-id=(\S+?)\." (str %)))]
    (testing "released before it was adopted: adopted now, and the marker says the person moved it"
      (let [out (detach-run (ctx 5000 10000) "bash" slow)]
        (is (wait-until 2000 #(= 1 (count @bg))) "the waiting call can be released")
        (release-all! bg)
        (let [r (deref out 2000 nil)
              id (id-of (:ok r))]
          (is (str/starts-with? (:ok r) "[The person moved bash to the background."))
          (is (some #(= [:task/detached {:id id :tool "bash" :by :person}] %) @events))
          (is (str/includes? (pr-str (:result (tasks/wait reg id 5000))) "done") "the work went on, and its result is the task's")
          (is (empty? @bg) "and the call is no longer waiting"))))
    (testing "released after it was adopted: the wait ends at once"
      (reset! events [])
      (let [out (detach-run (ctx 100 10000) "bash" slow)]
        (is (wait-until 2000 #(some (fn [[e d]] (and (= :task/detached e) (nil? (:by d)))) @events)) "adopted at 100 ms")
        (release-all! bg)
        (let [r (deref out 2000 nil)]
          (is (str/starts-with? (:ok r) "[The person moved bash"))
          (is (some #(= :person (:by (second %))) @events)))))
    (testing "two calls waiting are released together"
      (let [a (detach-run (ctx 5000 10000) "bash" slow)
            b (detach-run (ctx 5000 10000) "grep" slow)]
        (is (wait-until 2000 #(= 2 (count @bg))))
        (release-all! bg)
        (is (every? #(str/includes? (str (:ok (deref % 2000 nil))) "The person moved") [a b]))))
    (testing "a call that does not detach is not offered, and a fast one is untouched"
      (let [w (detach-run (ctx 5000 10000) "task$wait" (fn [] (Thread/sleep 200) "waited"))]
        (Thread/sleep 50)
        (is (empty? @bg))
        (is (= "waited" (:ok (deref w 2000 nil)))))
      (is (= "quick" (:ok (deref (detach-run (ctx 5000 10000) "bash" (fn [] "quick")) 2000 nil))))
      (is (empty? @bg)))))
