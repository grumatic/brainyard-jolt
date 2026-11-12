;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.effect-test
  "brainyard's `effect/test/ai/brainyard/effect/effect_test.clj`, ported to
  `brainyard.effect` on ebb, plus the port's own additions.

  Not ported, with the reason:
  - `a-dynamic-var-changes-value-across-a-park` and the \"bare m/via does NOT
    convey\" case characterize JVM missionary; ebb behaves differently, and
    `ebb-bindings-test` pins what it does instead.
  - `from-future-adopts-a-running-future`, `sample-lines-…`, `smoke-report-…`:
    those fns are not ported (docs/divergences.md `effect-brick-scope`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [ebb.core :as m]
            [jolt.process :as p]))

(def ^:dynamic *tag* :root)

(defn stopped?
  "Assert that cancelled work STOPPED, which is the contract (RFC-009 rule 8) —
  not that nothing landed after the canceller returned, which is a race.
  Cancellation is signalled, not awaited, so one step already in flight may
  still complete: measured over 200 supervisor cycles against a 10 ms ticker,
  8 runs saw exactly one more, none saw a second or kept going (byj-mvd)."
  [!counter what]
  (let [at-cancel @!counter]
    (Thread/sleep 60)
    (let [settled @!counter]
      (is (<= (- settled at-cancel) 1) (str what ": at most the step already in flight"))
      (Thread/sleep 150)
      (is (= settled @!counter) (str what ": and then nothing more")))))

;; --- brainyard's tests -------------------------------------------------------------

(deftest effects-are-values
  (testing "nothing runs until something runs it"
    (let [!n (atom 0)
          task (m/sp (swap! !n inc) :done)]
      (is (zero? @!n) "constructing a Task must not execute it")
      (is (= {:ok :done} (fx/run!! task 2000)))
      (is (= 1 @!n))
      (fx/run!! task 2000)
      (is (= 2 @!n) "a Task is re-runnable"))))

(deftest run-bang-bang-reports-rather-than-throws
  (testing "failure is data"
    (let [r (fx/run!! (m/sp (throw (ex-info "boom" {:x 1}))) 2000)]
      (is (nil? (:ok r)))
      (is (= "boom" (ex-message (:err r))))))
  (testing "timeout cancels on the way out"
    (let [!cancelled (atom false)
          task (fn [s f]
                 (let [inner ((m/sleep 5000 :never) s f)]
                   (fn [] (reset! !cancelled true) (inner))))]
      (is (:timeout (fx/run!! task 100)))
      (is @!cancelled "a timed-out run!! must not leak the effect"))))

(deftest cancellation-is-structural
  (testing "the canceller reaches a nested park"
    (let [!out (promise)
          cancel (fx/run (m/sp (m/? (m/sp (m/? (m/sleep 5000)))) :never)
                         #(deliver !out {:ok %})
                         #(deliver !out {:err %}))]
      (Thread/sleep 50)
      (cancel)
      (is (fx/cancelled? (:err (deref !out 2000 {:timeout true})))))))

(deftest timeout-fallback
  (is (= {:ok :fell-back}
         (fx/run!! (fx/timeout (m/sp (m/? (m/sleep 5000)) :never) 50 :fell-back) 2000))))

(deftest retry-backoff-semantics
  (testing "retries until success, reporting each wait before it happens"
    (let [!n (atom 0)
          !seen (atom [])
          task (m/sp (let [n (swap! !n inc)]
                       (if (< n 3) (throw (ex-info "flaky" {})) n)))]
      (is (= {:ok 3} (fx/run!! (fx/retry-backoff {:max-retries 5 :base-delay-ms 5
                                                  :on-retry #(swap! !seen conj (:attempt %))}
                                                 task)
                               5000)))
      (is (= [1 2] @!seen))))
  (testing "gives up and rethrows the last error"
    (is (= "always" (ex-message (:err (fx/run!! (fx/retry-backoff {:max-retries 2 :base-delay-ms 5}
                                                                  (m/sp (throw (ex-info "always" {}))))
                                                5000))))))
  (testing ":retryable? false short-circuits"
    (let [!n (atom 0)
          r (fx/run!! (fx/retry-backoff {:max-retries 5 :base-delay-ms 5 :retryable? (constantly false)}
                                        (m/sp (swap! !n inc) (throw (ex-info "permanent" {}))))
                      5000)]
      (is (= "permanent" (ex-message (:err r))))
      (is (= 1 @!n))))
  (testing ":on-retry sees the caller's dynamic frame on every attempt"
    (let [!seen (atom [])]
      (binding [*tag* :listener]
        (fx/run!! (fx/retry-backoff {:max-retries 3 :base-delay-ms 5
                                     :on-retry (fn [_] (swap! !seen conj *tag*))}
                                    (m/sp (throw (ex-info "always" {}))))
                  5000))
      (is (= [:listener :listener :listener] @!seen))))
  (testing "an in-flight backoff is cancellable"
    (let [!out (promise)
          cancel (fx/run (fx/retry-backoff {:max-retries 5 :base-delay-ms 10000}
                                           (m/sp (throw (ex-info "flaky" {}))))
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep 100)
      (cancel)
      (is (fx/cancelled? (:err (deref !out 1000 {:timeout true})))))))

(deftest bounded-preserves-input-order
  (testing "completion order is reversed; result order is not"
    (let [tasks (for [i (range 6)] (m/sp (m/? (m/sleep (* 15 (- 6 i)))) i))]
      (is (= {:ok [0 1 2 3 4 5]} (fx/run!! (fx/bounded 3 tasks) 5000)))))
  (testing "empty input"
    (is (= {:ok []} (fx/run!! (fx/bounded 3 []) 1000))))
  (testing "a failure cancels the siblings"
    (let [!finished (atom 0)
          tasks [(m/sp (m/? (m/sleep 20)) (throw (ex-info "boom" {})))
                 (m/sp (m/? (m/sleep 2000)) (swap! !finished inc))
                 (m/sp (m/? (m/sleep 2000)) (swap! !finished inc))]
          r (fx/run!! (fx/bounded 3 tasks) 3000)]
      (is (= "boom" (ex-message (:err r))))
      (Thread/sleep 100)
      (is (zero? @!finished)))))

(deftest flows-are-reusable-values
  (let [take4 #(fx/run!! (m/reduce conj [] (m/eduction (take 4) (fx/ticker 10))) 3000)]
    (is (= {:ok [0 1 2 3]} (take4)))
    (is (= {:ok [0 1 2 3]} (take4)) "a Flow is a value, not a draining channel")))

(deftest ticking-self-stops-and-is-cancellable
  (testing "runs while tick! is truthy, then completes"
    (let [!n (atom 0)]
      (is (= {:ok nil} (fx/run!! (fx/ticking 5 #(< (swap! !n inc) 4)) 3000)))
      (is (= 4 @!n))))
  (testing "work happens before the first sleep"
    (let [!n (atom 0)]
      (fx/run!! (fx/ticking 10000 (fn [] (swap! !n inc) false)) 2000)
      (is (= 1 @!n))))
  (testing "cancellable mid-sleep"
    (let [!n (atom 0)
          cancel (fx/run (fx/ticking 50 (fn [] (swap! !n inc) true)) (fn [_]) (fn [_]))]
      (Thread/sleep 120)
      (cancel)
      (stopped? !n "ticking")))
  (testing "a throwing tick! fails the task"
    (is (= "tick blew up"
           (ex-message (:err (fx/run!! (fx/ticking 5 #(throw (ex-info "tick blew up" {}))) 2000)))))))

(deftest poll-until-for-genuinely-remote-work
  (testing "asks immediately, then paces"
    (let [!n (atom 0)
          t0 (System/currentTimeMillis)
          r (fx/run!! (fx/poll-until 50 :pending
                                     (fn [] (if (< (swap! !n inc) 4) :pending {:done @!n})))
                      5000)]
      (is (= {:ok {:done 4}} r))
      (is (= 4 @!n))
      (is (>= (- (System/currentTimeMillis) t0) 140) "3 sleeps, not 4")))
  (testing "the wait is cancellable"
    (let [!n (atom 0)
          cancel (fx/run (fx/poll-until 100 :pending (fn [] (swap! !n inc) :pending)) (fn [_]) (fn [_]))]
      (Thread/sleep 250)
      (cancel)
      (stopped? !n "polling")))
  (testing "a throwing poll fails the task"
    (is (= "peer exploded"
           (ex-message (:err (fx/run!! (fx/poll-until 10 :pending #(throw (ex-info "peer exploded" {})))
                                       2000)))))))

(deftest ensure-vs-start
  (testing "ensure! leaves an incumbent alone"
    (let [!a (atom 0) !b (atom 0)]
      (is (true? (fx/ensure! ::e (fx/ticking 10 (fn [] (swap! !a inc) true)))))
      (is (false? (fx/ensure! ::e (fx/ticking 10 (fn [] (swap! !b inc) true)))))
      (Thread/sleep 60)
      (is (pos? @!a))
      (is (zero? @!b))
      (fx/stop! ::e)))
  (testing "start! replaces an incumbent"
    (let [!a (atom 0) !b (atom 0)]
      (fx/start! ::r (fx/ticking 10 (fn [] (swap! !a inc) true)))
      (Thread/sleep 50)
      (fx/start! ::r (fx/ticking 10 (fn [] (swap! !b inc) true)))
      (Thread/sleep 60)
      (let [a-then @!a]
        (Thread/sleep 60)
        (is (= a-then @!a))
        (is (pos? @!b)))
      (fx/stop! ::r)))
  (testing "a self-stopped process deregisters"
    (let [!n (atom 0)]
      (is (true? (fx/ensure! ::f (fx/ticking 5 #(< (swap! !n inc) 3)))))
      (Thread/sleep 200)
      (is (not (fx/running? ::f)))
      (is (true? (fx/ensure! ::f (fx/ticking 5 (constantly false)))))
      (fx/stop! ::f))))

(deftest supervisor-lifecycle
  (testing "start is idempotent and stop actually stops"
    (let [!ticks (atom 0)
          task (m/reduce (fn [_ _] (swap! !ticks inc)) nil (fx/ticker 10))]
      (fx/start! ::a task)
      (fx/start! ::a task)
      (is (contains? (fx/running) ::a))
      (Thread/sleep 80)
      (is (true? (fx/stop! ::a)))
      (is (not (contains? (fx/running) ::a)))
      (stopped? !ticks "the ticker")))
  (testing "stop! on an unknown label is a no-op"
    (is (nil? (fx/stop! ::never-started))))
  (testing "stop-all! clears the registry"
    (fx/start! ::b (m/sp (m/? (m/sleep 10000))))
    (fx/start! ::c (m/sp (m/? (m/sleep 10000))))
    (is (<= 2 (fx/stop-all!)))
    (is (empty? (fx/running)))))

(deftest task-of-is-the-macro-free-entry
  (testing "a thunk becomes a Task"
    (is (= {:ok 4950} (fx/run!! (fx/task-of #(reduce + (range 100))) 2000))))
  (testing "thunks joined through task-of are genuinely parallel"
    (let [slow #(do (Thread/sleep 300) :done)
          t0 (System/currentTimeMillis)
          r (fx/run!! (fx/all [(fx/task-of slow) (fx/task-of slow)]) 5000)
          el (- (System/currentTimeMillis) t0)]
      (is (= {:ok [:done :done]} r))
      ;; the claim is "parallel, not serial": two 300 ms sleeps run together.
      ;; 500 left 100 ms of headroom, which a loaded machine can eat — the bound
      ;; only has to sit below serial (~600) to mean anything (byj-mvd).
      (is (< el 560) (str "took " el "ms; serial would be ~600"))))
  (testing "a throwing thunk fails the Task"
    (is (= "boom" (ex-message (:err (fx/run!! (fx/task-of #(throw (ex-info "boom" {}))) 2000)))))))

(deftest task-of-conveys-dynamic-bindings
  (testing "matches (future …)"
    (is (= {:ok :outer} (binding [*tag* :outer] (fx/run!! (fx/task-of (fn [] *tag*)) 2000)))))
  (testing "the frame is captured at CONSTRUCTION, like future — which ebb's bare
            via does not do (it captures at run time), so task-of must"
    (let [t (binding [*tag* :at-build] (fx/task-of (fn [] *tag*)))
          bare (binding [*tag* :at-build] (m/via m/blk *tag*))]
      (is (= {:ok :at-build} (fx/run!! t 2000)))
      (is (= {:ok :root} (fx/run!! bare 2000)) "pinned so nobody simplifies task-of to a bare via")))
  (testing "conveyance survives a join"
    (is (= {:ok [:outer :outer]}
           (binding [*tag* :outer]
             (fx/run!! (fx/all [(fx/task-of (fn [] *tag*)) (fx/task-of (fn [] *tag*))]) 3000))))))

(deftest run-bang-bang-cancels-on-interrupt
  (let [!cancelled (atom false)
        forever (fn [_ _] (fn [] (reset! !cancelled true)))
        !result (atom :none)
        t (doto (Thread. (fn [] (reset! !result (fx/run!! forever))))
            (.setDaemon true) (.start))]
    (Thread/sleep 300)
    (is (false? @!cancelled) "not cancelled while still waiting")
    (.interrupt t)
    (.join t 3000)
    (is (true? @!cancelled) "the canceller must run, or the effect outlives its caller")
    (is (= {:interrupted true} @!result))))

(deftest run-bang-bang-still-cancels-on-timeout
  (let [!cancelled (atom false)
        forever (fn [_ _] (fn [] (reset! !cancelled true)))]
    (is (= {:timeout true} (fx/run!! forever 200)))
    (is (true? @!cancelled))))

(deftest run-bang-bang-normal-settle-does-not-cancel
  (let [!cancelled (atom false)
        quick (fn [success _] (success 42) (fn [] (reset! !cancelled true)))]
    (is (= {:ok 42} (fx/run!! quick)))
    (is (false? @!cancelled))))

(deftest watch-until-settles-on-the-write-not-a-poll-boundary
  (testing "an atom already satisfying pred completes without waiting"
    (is (= {:ok 9} (fx/run!! (fx/watch-until (atom 9) #(> % 2)) 2000))))
  (testing "completes when a later write satisfies pred"
    (let [!a (atom 0)]
      (future (Thread/sleep 100) (reset! !a 7))
      (is (= {:ok 7} (fx/run!! (fx/watch-until !a #(> % 2)) 3000)))))
  (testing "never settles while pred stays false"
    (is (= {:timeout true} (fx/run!! (fx/watch-until (atom 0) #(> % 100)) 400))))
  (testing "raced against a deadline, either side can win"
    (is (= {:ok :deadline}
           (fx/run!! (fx/race (fx/watch-until (atom 0) #(> % 100)) (m/sleep 200 :deadline)) 3000)))
    (let [!c (atom 0)]
      (future (Thread/sleep 50) (reset! !c 42))
      (is (= {:ok 42}
             (fx/run!! (fx/race (fx/watch-until !c #(> % 2)) (m/sleep 3000 :deadline)) 5000)))))
  (testing "completion is not quantized to a poll interval"
    (let [!a (atom 0)
          t0 (System/currentTimeMillis)]
      (future (Thread/sleep 220) (reset! !a 1))
      (fx/run!! (fx/watch-until !a pos?) 3000)
      ;; 450, not 300: a poll of 250 ms would land at 500, and 80 ms of slack
      ;; is less than a loaded machine — the suite's own shards — takes (byj-gly.333)
      (is (< (- (System/currentTimeMillis) t0) 450)))))

;; --- the port's additions ------------------------------------------------------------

(deftest timeout-without-fallback-is-nil
  (is (= {:ok nil} (fx/run!! (fx/timeout (m/sleep 5000 :never) 50) 2000))))

(deftest delayed-never-runs-when-cancelled-during-the-wait
  (let [!ran (atom false)
        cancel (fx/run (fx/delayed 200 (fx/task-of #(reset! !ran true))) (fn [_]) (fn [_]))]
    (Thread/sleep 50)
    (cancel)
    (Thread/sleep 300)
    (is (false? @!ran))))

(deftest debounce-emits-only-after-quiet
  ;; The writes are 10 ms apart and the window is 500. It was 100, and once
  ;; under six shards the writer was starved past it between 2 and 3, so 2
  ;; was rightly emitted (byj-gly.448). A writer starved past even 500 makes
  ;; an earlier value the right answer, so the gaps it actually left are
  ;; measured and only a run whose gaps all fell well inside the window is
  ;; held to 3.
  (let [window 500
        !a (atom 0)
        !at (atom [])
        writer (future (doseq [v [1 2 3]]
                         (Thread/sleep 10)
                         (reset! !a v)
                         (swap! !at conj (System/currentTimeMillis))))
        r (fx/run!! (m/reduce (fn [_ v] (reduced v)) nil
                              (fx/debounce window (m/eduction (remove zero?) (fx/watch-flow !a))))
                    5000)]
    @writer
    (if (every? #(< % (/ window 2)) (map - (rest @!at) @!at))
      (is (= {:ok 3} r) "values superseded within the quiet window are never emitted")
      (is (contains? #{1 2 3} (:ok r)) "a writer starved past the window: an earlier value is right"))))

(deftest cancelled-includes-the-interrupt-via-blk-surfaces
  (is (fx/cancelled? (InterruptedException. "x")))
  (is (not (fx/cancelled? (ex-info "other" {}))))
  (let [!out (promise)
        cancel (fx/run (fx/task-of #(Thread/sleep 5000)) #(deliver !out {:ok %}) #(deliver !out {:err %}))]
    (Thread/sleep 50)
    (cancel)
    (let [{:keys [err]} (deref !out 2000 {:timeout true})]
      (is (instance? InterruptedException err) "via blk delivers the interrupt, not Cancelled")
      (is (fx/cancelled? err)))))

(defn- alive? [marker]
  (not (str/blank? (:out (p/sh "pgrep" "-f" marker)))))

(deftest process-task-kills-what-it-started
  (testing "a completed process reports its result"
    (let [{:keys [ok]} (fx/run!! (fx/process-task {:out :string :err :string} "bash" "-c" "echo hi") 5000)]
      (is (= 0 (:exit ok)))
      (is (= "hi\n" (:out ok)))))
  (testing "cancelling destroys the process tree"
    (let [marker (str "sleep 9." (rand-int 100000))
          !out (promise)
          cancel (fx/run (fx/process-task {:out :string :err :string} "bash" "-c" marker)
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep 300)
      (is (alive? marker) "non-vacuous: the child was running")
      (cancel)
      (is (fx/cancelled? (:err (deref !out 2000 {:timeout true}))))
      (Thread/sleep 300)
      (is (not (alive? marker)) "no child outlives the cancel")))
  (testing "a timeout around it kills the child too"
    (let [marker (str "sleep 9." (rand-int 100000))]
      (is (= {:ok :timed-out}
             (fx/run!! (fx/timeout (fx/process-task {:out :string} "bash" "-c" marker) 300 :timed-out) 3000)))
      (Thread/sleep 300)
      (is (not (alive? marker))))))

(deftest supervisor-registry-is-not-raced-by-late-callbacks
  (testing "a task that settles synchronously is not left registered (brainyard registers after running)"
    (fx/start! ::sync (fx/success :done))
    (is (not (fx/running? ::sync))))
  ;; Measured: brainyard's run-then-register start! fails the case above on ebb.
  ;; The case below did not reproduce on ebb (a cancelled sleep's callback
  ;; arrives synchronously inside cancel, before the replacement registers);
  ;; it guards the ordering if that ever becomes asynchronous.
  (testing "a replaced incumbent's late cancellation does not deregister its replacement"
    (fx/start! ::swap (m/sp (m/? (m/sleep 10000))))
    (fx/start! ::swap (m/sp (m/? (m/sleep 10000))))
    (Thread/sleep 100)
    (is (fx/running? ::swap))
    (fx/stop! ::swap)))

(deftest failures-nobody-awaits-are-reported
  (let [!seen (atom [])]
    (binding [fx/*on-failure* (fn [event data] (swap! !seen conj [event (:label data)]))]
      (fx/run-detached (m/sp (throw (ex-info "lost" {}))) :flush)
      (fx/start! ::failing (m/sp (throw (ex-info "crash" {}))))
      (fx/start! ::cancelled (m/sp (m/? (m/sleep 10000))))
      (fx/stop! ::cancelled))
    (Thread/sleep 100)
    (is (= #{[::fx/detached-effect-failed :flush]
             [::fx/supervised-effect-failed ::failing]}
           (set @!seen))
        "a cancellation is not a failure")))

;; --- a worker never arrives interrupted (byj-gly.82) ---------------------------------

(deftest a-blk-thread-never-arrives-carrying-an-interrupt
  ;; The flake's mechanism: a thread with the flag already set makes its very
  ;; next subprocess wait throw `InterruptedException: Process.waitFor` before
  ;; doing anything — which is how a trivial `echo` failed under
  ;; dev/flake-hunt.sh. If a POOLED worker could carry the flag between tasks,
  ;; one cancelled task would poison every later one that shells out.
  ;;
  ;; It cannot, and this is the assertion that keeps it that way.
  (let [flags (atom [])
        threads (atom [])
        probe (fn [] (m/via m/blk
                            (swap! threads conj (.getName (Thread/currentThread)))
                            (swap! flags conj (.isInterrupted (Thread/currentThread)))
                            :ok))]
    (testing "a task that restores its own flag and returns does not pass it on"
      (let [started (promise)
            t (m/via m/blk
                     (deliver started true)
                     (try (Thread/sleep 5000) :slept
                          (catch InterruptedException _
                            ;; what fx/run!! does for its caller's own loop
                            (.interrupt (Thread/currentThread))
                            :interrupted)))
            cancel (fx/run t (fn [_]) (fn [_]))]
        @started
        (Thread/sleep 100)
        (cancel)
        (Thread/sleep 200)))

    (dotimes [_ 8] (fx/run-value!! (probe)))
    (is (not-any? true? @flags)
        (str "a pooled worker arrived interrupted — every subprocess wait after it "
             "would throw (byj-gly.82). Threads used: " (vec (distinct @threads))))

    (testing "and a subprocess wait on one of those workers still works"
      (is (zero? (:exit (fx/run-value!! (fx/process-task {:out :string} "echo" "hi"))))))))

(deftest an-interrupt-flag-makes-the-next-subprocess-wait-throw
  ;; the mechanism itself, pinned — so the explanation in byj-gly.82 and
  ;; provenance P-11 stays true rather than becoming folklore
  (.interrupt (Thread/currentThread))
  (is (true? (.isInterrupted (Thread/currentThread))))
  (is (thrown? InterruptedException @(p/process {:out :string} "echo" "hi"))
      "before the process has done anything — which is how a trivial echo failed")
  ;; leave the runner as we found it. test-runner/run clears it between tests
  ;; and would NAME this one, but a test that poisons the thread and says
  ;; nothing is the very bug being pinned here.
  (Thread/interrupted)
  (is (false? (.isInterrupted (Thread/currentThread)))))

(deftest a-subprocess-gives-back-its-pipes
  ;; jolt's process leaves a child's pipes open: every claude -p call, bash call
  ;; and prompt `date` cost two file descriptors until "too many open files"
  ;; (byj-gly.259). Counted with lsof when there is one.
  (let [lsof? (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c" "command -v lsof")))
        open-fds #(-> (fx/sh {:out :string :err :string} "sh" "-c" "lsof -p $PPID 2>/dev/null | wc -l")
                      :out str/trim parse-long)]
    (when lsof?
      (let [before (open-fds)]
        (dotimes [_ 30] (fx/sh {:in "" :out :string :err :string} "true"))
        (dotimes [_ 30] (fx/run-value!! (fx/process-task {:in "" :out :string :err :out} "true")))
        (dotimes [_ 10] (fx/run-value!! (fx/streaming-process {:in "" :err :string} (fn [_]) "echo" "x")))
        (is (<= (- (open-fds) before) 4) "70 subprocesses, and no more descriptors open than before")))))

(deftest a-subprocess-never-asks-on-the-terminal-unless-handed-it
  ;; byj-gly.295, provenance P-32: ssh asked for a key's passphrase on
  ;; /dev/tty, over the TUI, and the TUI took the keys
  (testing "git and ssh are told to fail rather than ask"
    (is (= {"PATH" "/bin" "GIT_TERMINAL_PROMPT" "0" "SSH_ASKPASS_REQUIRE" "never" "GCM_INTERACTIVE" "never"
            "GIT_SSH_COMMAND" "ssh -o BatchMode=yes"}
           (fx/noninteractive {"PATH" "/bin"})))
    (is (= "ssh -i k -o BatchMode=yes" (get (fx/noninteractive {"GIT_SSH_COMMAND" "ssh -i k"}) "GIT_SSH_COMMAND"))
        "a GIT_SSH_COMMAND of the person's own keeps its options")
    (is (= "ssh -o BatchMode=no" (get (fx/noninteractive {"GIT_SSH_COMMAND" "ssh -o BatchMode=no"}) "GIT_SSH_COMMAND"))
        "one that says BatchMode itself is left as it is")
    (is (nil? (get (fx/noninteractive {"GIT_SSH" "/w/ssh-wrap"}) "GIT_SSH_COMMAND"))
        "nor is a GIT_SSH wrapper bypassed"))
  (let [seen #(str/trim (:out (fx/sh (merge {:out :string :err :string} %) "sh" "-c" "echo $GIT_TERMINAL_PROMPT")))]
    (testing "every subprocess runs so — its own :env too"
      (is (= "0" (seen {})))
      (is (= "0" (seen {:env (into {} (System/getenv))}))))
    (testing "but not one handed the terminal: nothing is added to its environment"
      ;; its own, without the variable: a suite run from inside a brainyard
      ;; session — through its bash tool — inherits GIT_TERMINAL_PROMPT=0 from
      ;; this very rule, and a child handed the terminal rightly keeps it
      (is (= "" (seen {:interactive? true :env (dissoc (into {} (System/getenv)) "GIT_TERMINAL_PROMPT")}))))))

(deftest under-the-tui-a-child-has-no-terminal-and-is-not-left-behind
  ;; byj-gly.297: while the full-screen TUI owns the terminal, a child leads a
  ;; session of its own (setsid), so it cannot open /dev/tty; it then no longer
  ;; hears the terminal close, so what is still running is destroyed at the end
  (when @fx/no-terminal-argv
    (let [leads? (fn [opts]
                   ;; a session leader's process group is its own pid
                   (let [[pgid pid] (str/split (str/trim (:out (fx/sh (merge {:out :string :err :string} opts)
                                                                       "sh" "-c" "echo $(ps -o pgid= -p $$) $$")))
                                               #"\s+")]
                     (= pgid pid)))]
      (try
        (testing "not detached: a child stays in this process's group"
          (is (false? (leads? {}))))
        (fx/detach-children! true)
        (testing "detached: it leads a session of its own"
          (is (fx/detached?))
          (is (true? (leads? {}))))
        (testing "but a child handed the terminal keeps it"
          (is (false? (leads? {:interactive? true}))))
        (testing "stdin and stdout pass through the wrapper"
          (is (= "through\n" (:out (fx/sh {:in "through\n" :out :string :err :string} "cat")))))
        (testing "what is still running at the end is destroyed, and only that"
          (fx/destroy-owned!)
          (let [mark (str "byj-owned-" (System/nanoTime))
                _ (fx/sh {:out :string} "true")
                _ (fx/start {:out :string :err :string} "sh" "-c" (str "sleep 300; true " mark))]
            (Thread/sleep 300)
            (is (= 1 (fx/destroy-owned!)) "the finished one is not counted")
            (Thread/sleep 300)
            (is (str/blank? (:out (fx/sh {:out :string} "pgrep" "-f" mark))) "and the running one is gone")))
        (finally (fx/detach-children! false) (fx/destroy-owned!)))
      (is (false? (fx/detached?))))))

(deftest a-cancel-racing-a-thunks-end-leaves-the-pool-thread-clean
  ;; byj-gly.346 (provenance P-38): ebb's via interrupts the evaluating
  ;; thread outside the process's lock, so a cancel landing as the thunk ends
  ;; left the flag on the pool thread for whatever ran there next —
  ;; toolbox-test's store test died of it. fx/via-blk interrupts under the
  ;; lock. dev/brainyard/dev/interrupt_spike.clj measures both.
  (let [spin (fn [ns] (let [end (+ (System/nanoTime) ns)] (while (< (System/nanoTime) end))))
        race (fn []
               (let [p (promise)
                     cancel (fx/run (fx/via-blk (spin (rand-int 100000)) :done) #(deliver p %) #(deliver p %))]
                 (spin (rand-int 100000))
                 (cancel)
                 (deref p 1000 :late)
                 ;; a cached pool hands the thread just freed to the next thunk
                 (fx/run-value!! (fx/via-blk (Thread/interrupted)))))]
    (is (zero? (count (filter true? (repeatedly 5000 race)))) "no pool thread is left interrupted")))

(deftest nothing-under-src-runs-a-thunk-through-ebbs-via
  ;; every blk thunk comes through fx/via-blk, or the fix above covers nothing
  (let [offenders (for [f (file-seq (java.io.File. "src/brainyard"))
                        :when (and (.isFile f) (str/ends-with? (.getName f) ".clj"))
                        [i line] (map-indexed vector (str/split-lines (slurp f)))
                        :when (and (re-find #"\(m/via\b" line) (not (re-find #"^\s*;" line)))]
                    (str (.getName f) ":" (inc i) " " (str/trim line)))]
    (is (empty? offenders) (str/join "\n" offenders))))
