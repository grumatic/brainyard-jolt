(ns brainyard.effect
  "Functional effects as values — brainyard's `ai.brainyard.effect` brick
  (interface + core prim/policy/flows/supervisor), ported onto ebb, the jolt
  port of missionary. RFC-009.

  An effect is a Task — `(fn [success failure]) → canceller`, exactly one value
  or an error — or a Flow of many values over time. Nothing runs until
  something runs it, and running returns a canceller.

  WHAT IS NOT HERE, as in brainyard: the coroutine macros (`sp`, `ap`, `?`,
  `?>`, `?<`, `amb`, `via`). A macro cannot be re-exported without becoming a
  second thing to keep in sync; require `[ebb.core :as m]` for those, and use
  this namespace for running, policy, lifecycle and supervision.

  Differences from brainyard's brick (docs/divergences.md):
  - `cancelled?` also accepts `InterruptedException`, because `via blk`
    delivers what the interrupted thunk threw, never Cancelled (RFC-009 rule 2).
  - `process-task` is new: jolt subprocesses outlive an interrupted waiter, so
    cancelling one must destroy its process tree (RFC-009 rule 1).
  - `from-future`, `sample-lines` and the native-image `smoke-*` gate are not
    ported. Failures are reported through `*on-failure*`, not mulog.

  Rules for code that parks are RFC-009's: blocking calls under `via blk`, never
  park inside a lazy body (`mapv` included), `run!!` only at the edges."
  (:require [clojure.string :as str]
            [ebb.core :as m]
            [jolt.process :as p]))

(def ^:dynamic *on-failure*
  "(fn [event data]) for failures nobody awaits — a detached effect, a
  supervised process. brainyard logs these through mulog; the default here
  prints to stderr."
  (fn [event data]
    (binding [*out* *err*]
      (println (str "[brainyard.effect] " event) (pr-str (update data :exception #(some-> % ex-message)))))))

;; ============================================================================
;; Cancellation
;; ============================================================================

(defn cancelled?
  "True when `e` is a cancellation: ebb's Cancelled value, or the
  `InterruptedException` a `via blk` thunk surfaces when its thread is
  interrupted by a cancel (RFC-009 rule 2). brainyard's version checks only
  `missionary.Cancelled`; on jolt the interrupt is the more common of the two."
  [e]
  (or (m/cancelled? e)
      (instance? InterruptedException e)))

;; ============================================================================
;; Running
;; ============================================================================

(defn run
  "Run `task`, calling `success` or `failure` when it settles. Returns the
  canceller, a 0-arg fn."
  [task success failure]
  (task success failure))

(defn run!!
  "Run `task` and BLOCK until it settles. Returns `{:ok v}` or `{:err e}`;
  with `timeout-ms`, `{:timeout true}` once elapsed; `{:interrupted true}` if
  the waiting thread is interrupted. The task is cancelled on the way out of
  BOTH non-settling exits, so neither leaks a running effect.

  Never throws. The ONLY sanctioned place a thread blocks on an effect — for
  the edges (`-main`, a test, a synchronous handler). Inside an effect use
  `m/?`: a `run!!` there pins a thread and severs the cancellation chain."
  ([task] (run!! task nil))
  ([task timeout-ms]
   (let [p (promise)
         cancel (run task #(deliver p {:ok %}) #(deliver p {:err %}))
         r (try
             (if timeout-ms (deref p timeout-ms {:timeout true}) (deref p))
             (catch InterruptedException _
               ;; brainyard: an interrupt used to escape past `cancel`, leaving
               ;; the effect running with nobody holding its canceller. Restore
               ;; the flag so the caller's own loop still sees it.
               (.interrupt (Thread/currentThread))
               {:interrupted true}))]
     (when (or (:timeout r) (:interrupted r)) (cancel))
     r)))

(defn run-value!!
  "`run!!` for a blocking call site that must look like a plain call: returns
  the task's value, throws what it failed with, and turns an interrupt of the
  waiting thread (a cancelled turn reaching this leaf) into
  InterruptedException — after `run!!` has already cancelled the task. The
  shape brainyard's `retry-with-backoff` writes inline; without the
  `:interrupted` branch it would `(throw nil)` on every cancelled call."
  ([task] (run-value!! task nil))
  ([task timeout-ms]
   (let [r (run!! task timeout-ms)]
     (cond
       (contains? r :ok) (:ok r)
       (:interrupted r) (throw (InterruptedException. "interrupted while waiting on an effect"))
       (:timeout r) (throw (ex-info "effect timed out" {:timeout-ms timeout-ms}))
       :else (throw (:err r))))))

(defn run-detached
  "Fire `task` and return its canceller, reporting failure to `*on-failure*`
  rather than dropping it — a Task whose failure callback drops the error is
  how an effect system grows silent holes."
  ([task] (run-detached task nil))
  ([task label]
   (let [report *on-failure*]
     (run task
          (fn [_] nil)
          (fn [e] (report ::detached-effect-failed {:label label :exception e}))))))

;; ============================================================================
;; Entering the effect world
;; ============================================================================

;; --- a thunk on blk, cancelled under its lock ----------------------------------
;;
;; ebb's `via` (impl/thunk.clj, missionary's Thunk.java) cancels by reading the
;; evaluating thread under the process's lock and interrupting it OUTSIDE the
;; lock; the evaluation, done, clears the flag under the lock only when it
;; finds itself already cancelled. A cancel that took the lock first and
;; interrupts after the evaluation has cleared and let the thread go leaves
;; the flag on an idle pool thread, for whatever runs there next — which then
;; dies of an InterruptedException nobody sent it: 18 of 30 000 cancels racing
;; a thunk's end, dev/brainyard/dev/interrupt_spike.clj (byj-gly.346,
;; provenance P-38). Here the interrupt is delivered under the lock, so it is
;; ordered against the evaluation's own clear: either the evaluation marked
;; itself done first and nobody is interrupted, or the flag is set before the
;; evaluation clears it. Everything under src that runs a thunk on blk comes
;; through `via-blk`, never `m/via m/blk` (effect-test guards it), until ebb
;; delivers the interrupt under its lock upstream (byj-gly.347).

(deftype BlkProcess [thunk success failure ^:unsynchronized-mutable thread]
  clojure.lang.IFn
  ;; (ps) — cancel: interrupt the evaluating thread under the lock, or
  ;; pre-mark as cancelled so the evaluation interrupts itself as it starts
  (invoke [this]
    (locking this
      (let [t (.-thread this)]
        (when-not (identical? t this)
          (set! (.-thread this) this)
          (when (some? t) (.interrupt ^Thread t)))))
    nil))

(defn- evaluate-blk [^BlkProcess ps]
  (locking ps
    (let [t (Thread/currentThread)]
      (if (identical? (.-thread ps) ps)
        (.interrupt t)
        (set! (.-thread ps) t))))
  (let [[x cont] (try [((.-thunk ps)) (.-success ps)]
                      (catch Throwable e [e (.-failure ps)]))]
    ;; cancelled: clear the flag, so the pool thread goes back clean — the
    ;; interrupt, delivered under this lock, is already set or never will be;
    ;; otherwise mark done, so a later cancel interrupts nobody
    (locking ps
      (if (identical? (.-thread ps) ps)
        (Thread/interrupted)
        (set! (.-thread ps) ps)))
    (cont x)))

(defn on-blk
  "A Task evaluating `thunk` on ebb's blk pool and completing with its result:
  `m/via m/blk`, with the cancel's interrupt delivered under the process's
  lock (above). Cancellation interrupts the evaluating thread, as ebb's does."
  [thunk]
  (fn [s f]
    (let [ps (->BlkProcess thunk s f nil)]
      (.execute ^java.util.concurrent.Executor m/blk (fn [] (evaluate-blk ps)))
      ps)))

(defmacro via-blk
  "ebb's `via` on `blk`, through `on-blk`: the one way code under src runs a
  thunk on the blocking pool."
  [& body]
  `(on-blk (fn [] ~@body)))

(defn task-of
  "Lift a thunk into a Task evaluated on `blk`, with the caller's dynamic
  bindings captured at CONSTRUCTION — the drop-in shape of `(future …)`.

  ebb conveys bindings at RUN time, so a Task built under a binding and run
  outside it would see root (`ebb-bindings-test/conveyance-is-captured-at-run-time`).
  brainyard's contract is construction-time capture, like `future`, so the
  frame is snapshotted here and installed around the thunk."
  [f]
  (let [frame (get-thread-bindings)]
    (via-blk (with-bindings frame (f)))))

(defn success
  "A Task that immediately succeeds with `v`."
  [v]
  (fn [s _] (s v) (fn [])))

(defn failure
  "A Task that immediately fails with `e`."
  [e]
  (fn [_ f] (f e) (fn [])))

(defn from-promise
  "Adopt a promise as a Task. Cancelling abandons the wait; it cannot cancel
  whoever was going to deliver. Where the producer is ours, use `m/dfv`."
  [p]
  (via-blk (deref p)))

(def noninteractive-env
  "What every subprocess runs with unless it is handed the terminal: git and
  ssh fail at once rather than ask. A child of this process that asks opens
  /dev/tty itself — ssh for a key's passphrase did, drawn over the TUI, whose
  raw-mode input took the keys, so the passphrase went to the model as a turn
  (byj-gly.295, provenance P-32)."
  {"GIT_TERMINAL_PROMPT" "0"
   "SSH_ASKPASS_REQUIRE" "never"
   "GCM_INTERACTIVE" "never"})

(defn noninteractive
  "`env` with `noninteractive-env` over it, and git's ssh in batch mode: `-o
  BatchMode=yes` on a `GIT_SSH_COMMAND` already set, or one of its own — unless
  `GIT_SSH` names a wrapper, which `GIT_SSH_COMMAND` would bypass."
  [env]
  (let [cmd (get env "GIT_SSH_COMMAND")]
    (cond-> (merge env noninteractive-env)
      (and cmd (not (str/includes? cmd "BatchMode"))) (assoc "GIT_SSH_COMMAND" (str cmd " -o BatchMode=yes"))
      (and (nil? cmd) (nil? (get env "GIT_SSH"))) (assoc "GIT_SSH_COMMAND" "ssh -o BatchMode=yes"))))

(defonce ^:private detached
  ;; true while the full-screen TUI owns the terminal (`detach-children!`)
  (atom false))

(defonce ^:private owned
  ;; the processes started while detached that may still be running
  (atom #{}))

(def no-terminal-argv
  "What a child is started through to have no controlling terminal: setsid(1)
  where there is one, else perl's POSIX::setsid — macOS has no setsid(1) and
  ships /usr/bin/perl (RFC-009 \"Taking the terminal away altogether\",
  byj-gly.296). nil where neither is found: children keep the terminal."
  (delay
   (cond
     (.canExecute (java.io.File. "/usr/bin/setsid")) ["/usr/bin/setsid" "-w"]
     (.canExecute (java.io.File. "/usr/bin/perl"))
     ["/usr/bin/perl" "-e" "use POSIX qw(setsid); setsid() >= 0 or die \"setsid: $!\\n\"; exec {$ARGV[0]} @ARGV or die \"exec $ARGV[0]: $!\\n\"" "--"]
     :else nil)))

(declare destroy-owned!)

(defonce ^:private exit-hook
  ;; a window closed under the TUI kills this process with SIGHUP and runs no
  ;; `finally`; a detached child does not hear the hangup, and was left running
  ;; (measured, byj-gly.297). jolt runs shutdown hooks on SIGHUP, SIGTERM and
  ;; SIGINT (measured), so the owned trees are destroyed there too.
  (delay (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (destroy-owned!))))))

(defn detach-children!
  "While `on?`, every child `start` begins that is not handed the terminal has
  no controlling terminal, so nothing it runs — sudo, gpg's pinentry, anything
  that opens /dev/tty — can ask over the full-screen TUI, which sets this while
  it owns the terminal (byj-gly.297, provenance P-32). Such a child no longer
  hears the terminal's interrupt or hangup, so it is remembered until it ends:
  `destroy-owned!`, which the TUI calls as it gives the terminal back, and a
  shutdown hook calls if this process is ended first."
  [on?]
  (when on? @exit-hook)
  (reset! detached (boolean on?)))

(defn detached? [] @detached)

(defn destroy-owned!
  "Destroy the process tree of every child started while detached that is
  still running — what the TUI does as it gives the terminal back, so that
  closing it leaves nothing running that the terminal's hangup would have
  ended. Returns how many it destroyed."
  []
  (let [[procs _] (reset-vals! owned #{})
        live (filter #(try (p/alive? %) (catch Throwable _ false)) procs)]
    (doseq [proc live] (try (p/destroy-tree proc) (catch Throwable _ nil)))
    (count live)))

(defn start
  "Start a subprocess, owning its stdin: the only way the port starts one.

  It runs with `noninteractive` over its environment — `:env`, or this
  process's — unless it is handed the terminal: `:in :inherit` (the TUI's
  hand-over: tmux attach, `$EDITOR`), or `:interactive? true`. While the TUI
  owns the terminal (`detach-children!`), such a child is also started with no
  controlling terminal, and remembered until it ends.

  A string `:in`, or none, is not handed to jolt: the process starts with a
  pipe, the text is written to it, and the pipe is closed here, at once,
  exactly once — the one place stdin is closed. `:stream` and `:inherit` are
  passed through, for a caller that owns stdin. Its stdout and stderr are
  jolt's to close, which it does as of 0.8.15, the floor (jolt-lang/jolt#1175; before,
  every subprocess kept both open — byj-gly.259, provenance P-21, and the
  `release!` that closed them here went with byj-gly.262)."
  [opts & cmd]
  (let [handed? (or (= :inherit (:in opts)) (:interactive? opts))
        opts (cond-> (dissoc opts :interactive?)
               (not handed?) (assoc :env (noninteractive (or (:env opts) (into {} (System/getenv))))))
        detach? (and @detached (not handed?) @no-terminal-argv)
        cmd (if detach? (concat @no-terminal-argv cmd) cmd)
        in (:in opts)
        own? (or (nil? in) (string? in))
        proc (apply p/process (cond-> opts own? (dissoc :in)) cmd)]
    (when detach?
      (swap! owned (fn [ps] (conj (into #{} (filter #(try (p/alive? %) (catch Throwable _ false))) ps) proc))))
    (when own?
      (let [^java.io.OutputStream os (:in proc)]
        (try (when (seq in) (.write os (.getBytes ^String in "UTF-8")) (.flush os))
             (finally (try (.close os) (catch Throwable _ nil))))))
    proc))

(defn sh
  "Run a subprocess to its end and return `jolt.process`'s result map — the
  one way the port runs a short command it waits for. `opts` are `jolt.process` opts; the command is spread (provenance P-1).
  Blocks: an edge, or a fn already off the fiber; code that parks uses
  `process-task`."
  [opts & cmd]
  (let [proc (apply start opts cmd)]
    @proc))

(defn- unless-cancelled
  "`r`, the process's result, unless its task was cancelled: then the cancel,
  as the `InterruptedException` a cancelled `via-blk` thunk surfaces. The
  canceller kills the tree and then interrupts the wait, and from jolt 0.8.21
  the wait sees the process gone before it sees the interrupt and returns its
  exit (143) — so a cancelled task would report a result (provenance P-41)."
  [state r what]
  (if (:cancelled? @state)
    (throw (InterruptedException. (str what " cancelled")))
    r))

(defn streaming-process
  "Run a subprocess, calling `on-line` with each line of stdout AS IT ARRIVES.
  Settles with `jolt.process`'s result map, `:out` holding the whole output.
  Cancelling destroys the process tree, as `process-task` does.

  Read from the raw `InputStream`, one byte buffer at a time. Both readers that
  look tidier over-buffer and deliver nothing until the process exits, measured
  on `printf one; sleep 1; printf two`:

    (.read is byte-array)                     one at 22 ms,  two at 1029 ms
    (line-seq (io/reader is))                 both at 2036 ms
    (.read (InputStreamReader. is) chars)     both at  628 ms

  Lines are split on the BYTE `\n` and decoded per line, so a multi-byte
  character straddling two reads is never cut in half — `0x0A` cannot appear
  inside a UTF-8 sequence."
  [opts on-line & cmd]
  (fn [s f]
    (let [state (atom {:cancelled? false :proc nil})
          waiter (via-blk
                        (let [proc (apply start (assoc opts :out :stream) cmd)
                              {:keys [cancelled?]} (swap! state assoc :proc proc)]
                          (when cancelled?
                            (p/destroy-tree proc)
                            (throw (InterruptedException. "streaming-process cancelled before start")))
                          (try
                          (let [is (:out proc)
                                buf (byte-array 8192)
                                whole (StringBuilder.)
                                line (java.io.ByteArrayOutputStream.)
                                emit-line (fn []
                                            (let [text (String. (.toByteArray line) "UTF-8")]
                                              (.reset line)
                                              (.append whole text)
                                              (.append whole "\n")
                                              (try (on-line text) (catch Throwable _ nil))))]
                            (loop []
                              (let [n (.read is buf)]
                                (when (pos? n)
                                  (dotimes [i n]
                                    (let [b (aget buf i)]
                                      (if (= 10 b) (emit-line) (.write line (int b)))))
                                  (recur))))
                            ;; a last line with no newline is still a line
                            (when (pos? (.size line))
                              (let [text (String. (.toByteArray line) "UTF-8")]
                                (.append whole text)
                                (try (on-line text) (catch Throwable _ nil))))
                            (unless-cancelled state (assoc @proc :out (str whole)) "streaming-process")))))
          cancel (waiter s f)]
      (fn []
        (let [{:keys [proc]} (swap! state assoc :cancelled? true)]
          (some-> proc p/destroy-tree))
        (cancel)))))

(defn process-task
  "Run a subprocess as a Task of `jolt.process`'s result map (`:exit :out :err`).
  Cancelling it destroys the process tree.

  Not in brainyard: on jolt, interrupting the thread waiting on a subprocess
  returns at once but leaves the child running (RFC-009 measurements), so the
  canceller must kill what the task started. A cancel that lands before the
  process has started is honoured when it starts. `opts` are `jolt.process`
  opts; the command is spread, never a vector (provenance P-1)."
  [opts & cmd]
  (fn [s f]
    (let [state (atom {:cancelled? false :proc nil})
          waiter (via-blk
                        (let [proc (apply start opts cmd)
                              {:keys [cancelled?]} (swap! state assoc :proc proc)]
                          (when cancelled?
                            (p/destroy-tree proc)
                            (throw (InterruptedException. "process-task cancelled before start")))
                          (unless-cancelled state @proc "process-task")))
          cancel (waiter s f)]
      (fn []
        (let [{:keys [proc]} (swap! state assoc :cancelled? true)]
          (some-> proc p/destroy-tree))
        (cancel)))))

;; ============================================================================
;; Policy: Task -> Task
;; ============================================================================

(defn timeout
  "Complete with nil — or `fallback` — after `ms`, cancelling `task`.
  brainyard's docstring says the one-arity form fails with Cancelled; ebb's
  `timeout` (a port of missionary's) completes with nil instead
  (`effect-test/timeout-without-fallback-is-nil`)."
  ([task ms] (m/timeout task ms))
  ([task ms fallback] (m/timeout task ms fallback)))

(defn- attempt
  "Run `task`, reifying its outcome as `{:v …}` or `{:e …}` — a retry loop
  cannot `catch` across a park inside `loop`/`recur`. Cancellation is rethrown,
  never retried."
  [task]
  (m/sp (try {:v (m/? task)}
             (catch Throwable e
               (if (cancelled? e) (throw e) {:e e})))))

(defn- backoff-ms
  "Exponential backoff with up to 50% jitter, floored by any retry-after."
  [attempt-n base-ms retry-after-ms]
  (let [base (* base-ms (long (Math/pow 2 attempt-n)))
        jitter (long (* base (rand 0.5)))]
    (max (+ base jitter) (long (or retry-after-ms 0)))))

(defn retry-backoff
  "Retry `task` with exponential backoff. Returns a Task.

  opts:
    :max-retries    — default 3
    :base-delay-ms  — default 1000
    :retryable?     — (fn [e] bool), default `(constantly true)`
    :retry-after-ms — (fn [e] ms|nil), a floor on the delay
    :on-retry       — (fn [{:attempt :max :delay-ms :error}]) fired BEFORE the
                      sleep; throwing from it cannot fail the retry
    :max-retries-fn — (fn [e] n) overriding :max-retries per error

  The sleep is `m/sleep`, so an in-flight backoff is cancellable. `on-retry`
  runs under the caller's frame captured at construction, as in brainyard —
  ebb would convey it anyway (RFC-009 rule 5), but the contract does not
  depend on that."
  [{:keys [max-retries base-delay-ms retryable? retry-after-ms on-retry max-retries-fn]
    :or {max-retries 3 base-delay-ms 1000 retryable? (constantly true)}}
   task]
  (let [frame (get-thread-bindings)]
    (m/sp
     (loop [n 0]
       (let [{:keys [v e]} (m/? (attempt task))]
         (if (nil? e)
           v
           (let [max-n (if max-retries-fn (max-retries-fn e) max-retries)]
             (if-not (and (retryable? e) (< n max-n))
               (throw e)
               (let [delay (backoff-ms n base-delay-ms (when retry-after-ms (retry-after-ms e)))]
                 (when on-retry
                   (try (with-bindings frame
                          (on-retry {:attempt (inc n) :max max-n :delay-ms delay :error e}))
                        (catch Throwable _ nil)))
                 (m/? (m/sleep delay))
                 (recur (inc n)))))))))))

(defn bounded
  "Run `tasks` in parallel, at most `n` at a time, preserving INPUT ORDER in the
  result vector. Returns a Task. brainyard's replacement for `pmap`: the bound
  is `n`, a failure cancels the siblings, and the fan-out has one canceller."
  [n tasks]
  (let [tasks (vec tasks)]
    (if (empty? tasks)
      (success [])
      (m/sp
       (let [by-idx (m/? (m/reduce conj {}
                                   (m/ap (let [[i t] (m/?> n (m/seed (map-indexed vector tasks)))]
                                           [i (m/? t)]))))]
         (mapv by-idx (range (count tasks))))))))

(defn all
  "Run every task in parallel, unbounded; a failure cancels the siblings."
  [tasks]
  (apply m/join vector tasks))

(defn race
  "First task to settle wins; the rest are cancelled."
  [& tasks]
  (apply m/race tasks))

(defn delayed
  "Run `task` after `ms`. Cancelling during the wait means it never runs."
  [ms task]
  (m/sp (m/? (m/sleep ms))
        (m/? task)))

;; ============================================================================
;; Flows
;; ============================================================================

(defn ticking
  "A Task that calls `tick!` on `blk`, sleeps `ms`, and repeats while `tick!`
  returns truthy; completes with nil once it returns falsey. Work before the
  first sleep, so a ticker paints immediately."
  [ms tick!]
  (m/sp (loop []
          (when (m/? (task-of tick!))
            (m/? (m/sleep ms))
            (recur)))))

(defn ticker
  "A discrete Flow emitting 0, 1, 2, … every `ms`, forever. Pull-driven, so a
  slow consumer cannot accumulate stale ticks."
  [ms]
  (m/ap (loop [i 0]
          (m/? (m/sleep ms))
          (m/amb i (recur (inc i))))))

(defn poll-until
  "A Task that calls `f` on `blk` every `ms` while it returns `pending`, and
  completes with the first other value. Asks immediately, then paces."
  [ms pending f]
  (m/sp (loop []
          (let [r (m/? (task-of f))]
            (if (= r pending)
              (do (m/? (m/sleep ms)) (recur))
              r)))))

(defn watch-flow
  "A continuous Flow of `@!atom` (or `(f @!atom)`), emitting on change."
  ([!atom] (m/watch !atom))
  ([!atom f] (m/latest f (m/watch !atom))))

(defn watch-until
  "A Task completing with the first value of `!atom` satisfying `pred`, settling
  on the write rather than a poll boundary. Checks the current value first.
  NEVER completes while `pred` stays false — race it or give it a timeout."
  [!atom pred]
  (m/reduce (fn [_ v] (if (pred v) (reduced v) nil)) nil (m/watch !atom)))

(defn debounce
  "Emit a value from `flow` only after `ms` with no newer value. `?<` switches
  to the newest value, cancelling the superseded one's sleep, whose
  cancellation emits nothing."
  [ms flow]
  (m/ap (let [x (m/?< flow)]
          (try (m/? (m/sleep ms x))
               (catch Throwable e
                 (if (m/cancelled? e) (m/amb) (throw e)))))))

;; ============================================================================
;; Supervision
;; ============================================================================

;; label -> {:cancel (fn []) :started-at ms}
(defonce ^:private !processes (atom {}))

(declare stop!)

(defn start!
  "Run `task` under `label`, cancelling any incumbent first — idempotent.
  Returns the canceller. A process that ends deregisters itself; a failure that
  is not a cancellation goes to `*on-failure*`."
  [label task]
  (stop! label)
  (let [report *on-failure*
        ;; deregister only our own entry: a replacement started under the same
        ;; label must not be removed when this one's cancellation settles
        !self (atom nil)
        deregister! (fn [] (swap! !processes
                                  (fn [ps] (if (= @!self (get-in ps [label :id]))
                                             (dissoc ps label)
                                             ps))))
        id (gensym "process")]
    (reset! !self id)
    (swap! !processes assoc label {:id id :cancel (fn []) :started-at (System/currentTimeMillis)})
    (let [cancel (run task
                      (fn [_] (deregister!))
                      (fn [e]
                        (deregister!)
                        (when-not (cancelled? e)
                          (report ::supervised-effect-failed {:label label :exception e}))))]
      (swap! !processes (fn [ps] (if (= id (get-in ps [label :id]))
                                   (assoc-in ps [label :cancel] cancel)
                                   ps)))
      cancel)))

(defn running?
  "Is a process registered under `label`?"
  [label]
  (contains? @!processes label))

(defn ensure!
  "Start `task` under `label` only if nothing runs there. Returns true when it
  started one. `start!` replaces; `ensure!` leaves the incumbent alone."
  [label task]
  (if (running? label)
    false
    (do (start! label task) true)))

(defn stop!
  "Cancel the process under `label`, if any. Returns true when something was
  cancelled. Idempotent.

  Cancellation is requested, not awaited (RFC-009 rule 8): this returns once
  the label is deregistered and the canceller has been called, and a step
  already in flight may still complete. Measured: 4% of stop! calls against a
  10 ms ticker saw one more tick, none saw a second."
  [label]
  (when-let [{:keys [cancel]} (get @!processes label)]
    (swap! !processes dissoc label)
    (try (cancel)
         (catch Throwable t
           (*on-failure* ::supervised-cancel-failed {:label label :exception t})))
    true))

(defn stop-all!
  "Cancel every supervised process — the exit path's single call. Returns the
  number cancelled. Requests cancellation without awaiting it (rule 8)."
  []
  (let [labels (keys @!processes)]
    (doseq [l labels] (stop! l))
    (count labels)))

(defn running
  "Labels currently supervised, with uptime."
  []
  (let [now (System/currentTimeMillis)]
    (into {} (map (fn [[l {:keys [started-at]}]] [l {:uptime-ms (- now started-at)}]))
          @!processes)))
