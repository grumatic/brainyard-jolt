;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.binding-conveyance-spike
  "byj-gly.117 — does jolt 0.8.10's `via` binding change reach the runtime?

  `ebb-bindings-test` proves the SUBSTRATE changed: four assertions that passed
  on jolt 0.8.8 fail on 0.8.10, and one of them is `m/via m/cpu`, not `blk`. What
  it cannot say is whether anything this repo actually runs depends on the
  guarantee that went away. That was answered by reading code, which found
  `fx/task-of` immune (it snapshots the caller's frame with `with-bindings`) and
  every other lexical `via` body free of dynamic reads — and then stopped, one
  step short of the only site whose body is arbitrary CALLER code:

      tasks.clj:283   (m/via m/blk (binding [*in-task?* true] (thunk)))

  This drives the real `tasks/detachable` rather than a reconstruction, because
  the interesting thing is not that a raw `via` drops bindings — the substrate
  test already says so — but that ONE FUNCTION DOES BOTH:

      (if-not (detachable? ctx tool-name)
        (fx/task-of thunk)                                   ; captures the frame
        ... (m/via m/blk (binding [*in-task?* true] (thunk)))) ; does not

  A caller binds a var and calls a tool. Whether the binding survives depends on
  `detachable?` — on config, on whether a subagent is asking, on the tool's name
  — none of which the caller chose or can see. That asymmetry is the finding
  worth confirming or killing.

  It is a spike, not a test: it ASSERTS NOTHING and prints what it observes.
  A test would either turn the suite red on 0.8.10 (as ebb-bindings-test already
  does) or pin the broken behaviour as correct, and neither is wanted while
  byj-gly.117 is undecided.

  jolt -A:dev -e \"(require 'brainyard.dev.binding-conveyance-spike) (brainyard.dev.binding-conveyance-spike/main)\""
  (:require [brainyard.effect :as fx]
            [brainyard.tasks :as tasks]
            [brainyard.toolbox :as toolbox]
            [brainyard.hooks :as hooks]
            [ebb.core :as m]))

(def ^:dynamic *probe* :root)

(defn- base-config [extra]
  (merge {:working-dir "." :project-dir "." :tasks? true :fast-eval-timeout-ms 30000}
         extra))

(defn- observe
  "Bind *probe* to :bound, run `thunk` through tasks/detachable under `config`,
  and report what the thunk saw. Runs from a fiber, as RFC-009 rule 6 requires."
  [label config thunk]
  (let [ctx {:config (base-config config) :task-registry (atom {})}
        detachable? (tasks/detachable? ctx "bash")
        seen (binding [*probe* :bound]
               (fx/run-value!! (tasks/detachable ctx "bash" thunk)))]
    {:label label :detachable? detachable? :seen seen}))

(defn- row [{:keys [label detachable? seen expected]}]
  (println (format "  %-34s detachable=%-5s saw %-8s %s"
                   label (str detachable?) (str seen)
                   (cond (= seen expected) "as before"
                         (= seen :root) "<-- BINDING LOST"
                         :else "<-- unexpected"))))

(defn probe-var []
  (println)
  (println "1. A caller's own dynamic var, across tasks/detachable")
  (println "   Both rows run the SAME thunk under the SAME binding. Only the")
  (println "   branch differs, and the caller does not choose the branch.")
  (let [thunk (fn [] *probe*)
        ;; not detachable: falls to fx/task-of, which re-installs the frame
        a (observe "tasks? false -> fx/task-of" {:tasks? false} thunk)
        ;; detachable: the raw (m/via m/blk ...) at tasks.clj:283
        b (observe "detachable -> raw via blk" {} thunk)]
    (row (assoc a :expected :bound))
    (row (assoc b :expected :bound))
    {:task-of (:seen a) :raw-via (:seen b)}))

(defn- through-task
  "Bind `var-fn`'s var to `v` outside, read it inside the thunk, the way
  run-composite and hooks/invoke do: bind around the call, read after."
  [bind-fn read-fn]
  (let [ctx {:config (base-config {}) :task-registry (atom {})}]
    (bind-fn #(fx/run-value!! (tasks/detachable ctx "bash" read-fn)))))

(defn depth-guards []
  (println)
  (println "2. The two recursion guards a lost binding would weaken")
  (println "   Each is bound OUTSIDE the call and read INSIDE the thunk, which is")
  (println "   how run-composite and hooks/invoke use them.")
  (let [tb (through-task #(binding [toolbox/*depth* 7] (%)) (fn [] toolbox/*depth*))
        hk (through-task #(binding [hooks/*depth* 3] (%)) (fn [] hooks/*depth*))]
    (println (format "  %-34s bound 7, thunk saw %s %s" "toolbox/*depth*" tb
                     (if (= 7 tb) "" "<-- NESTING CAP COUNTS FROM THE WRONG PLACE")))
    (println (format "  %-34s bound 3, thunk saw %s %s" "hooks/*depth*" hk
                     (if (= 3 hk) "" "<-- RE-ENTRANCY GUARD RESET")))
    {:toolbox tb :hooks hk}))

(defn substrate []
  (println)
  (println "3. The substrate itself, for the record")
  (let [blk (binding [*probe* :bound] (fx/run-value!! (m/via m/blk *probe*)))
        cpu (binding [*probe* :bound] (fx/run-value!! (m/via m/cpu *probe*)))
        sp (binding [*probe* :bound] (fx/run-value!! (m/sp *probe*)))
        task-of (binding [*probe* :bound] (fx/run-value!! (fx/task-of (fn [] *probe*))))]
    (doseq [[k v] [["m/via m/blk" blk] ["m/via m/cpu" cpu]
                   ["m/sp (fiber)" sp] ["fx/task-of" task-of]]]
      (println (format "  %-34s saw %s %s" k v (if (= :bound v) "" "<-- BINDING LOST"))))
    {:blk blk :cpu cpu :sp sp :task-of task-of}))

(defn main []
  (println "byj-gly.117 — binding conveyance through the REAL task path")
  (println "jolt" (System/getProperty "jolt.version" "(unknown)"))
  (let [s (substrate)
        p (probe-var)
        d (depth-guards)]
    (println)
    (println "VERDICT")
    (if (= :bound (:raw-via p))
      (println "  tasks/detachable conveys on both branches — no live symptom here,")
      (println "  tasks/detachable CONVEYS ON ONE BRANCH AND NOT THE OTHER."))
    (when (not= :bound (:raw-via p))
      (println "  A caller's binding survives a non-detachable call and is lost on a")
      (println "  detachable one. The caller picks neither.")
      (println (format "  Guards: toolbox/*depth* %s, hooks/*depth* %s"
                       (if (= 7 (:toolbox d)) "intact" "RESET")
                       (if (= 3 (:hooks d)) "intact" "RESET"))))
    {:substrate s :probe p :depth d}))
