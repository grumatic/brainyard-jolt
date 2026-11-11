(ns brainyard.ebb-bindings-test
  "Pins how dynamic bindings behave under ebb on jolt, and what the port
  guarantees on top of it (RFC-009 rule 5).

  This file used to assert that `via blk` and `via cpu` carry the caller's
  bindings onto their OS thread, and said code could rely on it because an
  upgrade that changed it would fail here. jolt 0.8.10 changed it, this failed,
  and relying on it was the mistake (`byj-gly.117`). Nothing ever promised it:
  ebb implements no conveyance at all — no `get-thread-bindings`, no
  `with-bindings` anywhere in its source — declares no `:jolt/min-version`, and
  pins jolt 0.8.2 in its CI, so every version this repo has run was an untested
  combination.

  So the file is now in two halves.

  WHAT THE SUBSTRATE DOES, asserted as it is rather than as we would like it:
  - `sp` conveys across a park — a real async wait, an already-completed task,
    and a resume from blocking work;
  - bindings are FIBER-local: many fibers on a few carrier threads, with
    interleaved parks, never see each other's values;
  - `via blk` and `via cpu` DO NOT convey. Pinned in that direction on purpose:
    a future jolt that starts conveying again is a change we want to hear about,
    not a silent improvement;
  - conveyance, where it happens at all, is captured when a task RUNS, not when
    it is built;
  - a binding made inside blocking work does not leak out.

  WHAT THE PORT GUARANTEES, which is the half with a live consumer: every
  boundary that hands caller code to another thread installs the frame itself,
  so a caller's binding survives. `fx/task-of` has always done this;
  `tasks/detachable` did not on one of its two branches, which is how
  `byj-gly.117` got in. That test fails if someone adds another raw `via` over
  caller code, which is the whole point of keeping it here.

  Run on a fiber, as RFC-009 rule 6 requires: `m/?` from the test thread runs
  the body on a fiber (asserted in `the-body-runs-on-a-fiber`)."
  (:require [clojure.test :refer [deftest is testing]]
            [brainyard.effect :as fx]
            [brainyard.tasks :as tasks]
            [ebb.core :as m]))

(def ^:dynamic *probe* :root)

(defn- thread-name [] (.getName (Thread/currentThread)))

(deftest the-body-runs-on-a-fiber
  (let [outer (thread-name)
        inner (m/? (m/sp (thread-name)))]
    (is (not= outer inner) "m/? from a plain thread runs the sp body elsewhere")))

;; --- what the substrate does ------------------------------------------------

(deftest via-does-not-convey
  (testing "via blk runs on another OS thread and sees ROOT, not the caller's binding"
    (binding [*probe* :bound]
      (let [[fiber-thread [blk-thread seen]]
            (m/? (m/sp [(thread-name)
                        (m/? (m/via m/blk [(thread-name) *probe*]))]))]
        (is (not= fiber-thread blk-thread) "non-vacuous: the body really runs elsewhere")
        (is (= :root seen)
            "jolt 0.8.10 stopped conveying into via; 0.8.8 gave :bound here"))))
  (testing "via cpu is the same — the change is in `via`, not in `blk`"
    (binding [*probe* :bound]
      (is (= :root (m/? (m/via m/cpu *probe*))))))
  (testing "so does a via branch of a join, while its sp branch still conveys"
    (binding [*probe* :bound]
      (is (= [:bound :root]
             (m/? (m/join vector
                          (m/sp (m/? (m/sleep 5)) *probe*)
                          (m/via m/blk (Thread/sleep 5) *probe*))))))))

(deftest sp-conveys-across-a-park
  (binding [*probe* :bound]
    (testing "a real async wait"
      (is (= [:bound :bound]
             (m/? (m/sp (let [before *probe*]
                          (m/? (m/sleep 20))
                          [before *probe*]))))))
    (testing "an already-completed task (synchronous resume — the timing-dependent case in brainyard)"
      (is (= :bound (m/? (m/sp (m/? (m/sp :done)) *probe*)))))
    (testing "resuming from blocking work: the READ is in the sp body, so it still sees the binding"
      (is (= :bound (m/? (m/sp (m/? (m/via m/blk (Thread/sleep 10))) *probe*)))))))

(deftest bindings-are-fiber-local
  (let [n 200
        results (m/? (apply m/join vector
                            (for [i (range n)]
                              (m/sp (binding [*probe* i]
                                      (let [carrier (thread-name)
                                            before *probe*]
                                        (m/? (m/sleep (rand-int 20)))
                                        (let [after-sleep *probe*]
                                          (m/? (m/via m/blk (Thread/sleep (rand-int 5))))
                                          {:i i :carrier carrier
                                           :seen [before after-sleep *probe*]})))))))]
    (testing "non-vacuous: fibers really share carrier threads"
      (is (< (count (distinct (map :carrier results))) n)))
    (testing "no fiber ever saw another fiber's binding, across interleaved parks"
      (is (empty? (remove (fn [{:keys [i seen]}] (= [i i i] seen)) results))))
    (testing "negative control: an unbound read is the root value"
      (is (= :root (m/? (m/sp (m/? (m/sleep 1)) *probe*)))))
    (is (= :root *probe*) "no binding leaked to the caller")))

(deftest conveyance-is-captured-at-run-time
  (testing "a task built under a binding but run outside it sees root"
    (let [sp-task (binding [*probe* :bound] (m/sp *probe*))
          blk-task (binding [*probe* :bound] (m/via m/blk *probe*))]
      (is (= :root (m/? sp-task)))
      (is (= :root (m/? blk-task)))))
  (testing "an sp run under a DIFFERENT binding sees that one — run time, not build time"
    (let [t (binding [*probe* :built] (m/sp *probe*))]
      (is (= :running (binding [*probe* :running] (m/? t))))))
  (testing "a via sees neither: it does not convey at build time or at run time"
    (let [t (binding [*probe* :built] (m/via m/blk *probe*))]
      (is (= :root (binding [*probe* :running] (m/? t)))))))

(deftest inner-bindings-do-not-leak
  (binding [*probe* :outer]
    (is (= [:inner :outer]
           (m/? (m/sp [(m/? (m/via m/blk (binding [*probe* :inner] *probe*)))
                       *probe*]))))))

;; --- what the port guarantees on top -----------------------------------------

(deftest the-port-installs-the-frame-itself
  (testing "fx/task-of: the caller's binding reaches the thunk, though via does not convey"
    (is (= :bound (binding [*probe* :bound]
                    (fx/run-value!! (fx/task-of (fn [] *probe*)))))))
  (testing "tasks/detachable conveys on BOTH branches — byj-gly.117 is that it did not"
    (let [thunk (fn [] *probe*)
          ctx (fn [extra] {:config (merge {:working-dir "." :project-dir "."
                                           :tasks? true :fast-eval-timeout-ms 30000}
                                          extra)
                           :task-registry (atom {})})
          not-detachable (ctx {:tasks? false})
          detachable (ctx {})]
      (is (not (tasks/detachable? not-detachable "bash")) "non-vacuous: really the fx/task-of branch")
      (is (tasks/detachable? detachable "bash") "non-vacuous: really the raw-via branch")
      (is (= :bound (binding [*probe* :bound]
                      (fx/run-value!! (tasks/detachable not-detachable "bash" thunk)))))
      (is (= :bound (binding [*probe* :bound]
                      (fx/run-value!! (tasks/detachable detachable "bash" thunk))))
          "the branch a caller cannot see must not change what its binding does")))
  (testing "a recursion guard bound by the caller survives into a detachable tool"
    (let [c {:config {:working-dir "." :project-dir "." :tasks? true :fast-eval-timeout-ms 30000}
             :task-registry (atom {})}]
      ;; toolbox/*depth* and hooks/*depth* are the two that read 0 before the fix;
      ;; a local var stands in for them so this test owns its own var.
      (is (= 7 (binding [*probe* 7]
                 (fx/run-value!! (tasks/detachable c "bash" (fn [] *probe*)))))))))
