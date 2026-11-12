;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.interrupt-spike
  "Does a cancel racing a `via blk` thunk's end leave the pool thread
  interrupted? (byj-gly.346)

  ebb's thunk cancels by reading the evaluating thread under the process's
  lock and interrupting it OUTSIDE the lock; the evaluation, done, clears the
  flag under the lock only when it finds itself already cancelled. So a
  cancel that took the lock first, and interrupts after the evaluation has
  cleared and let the thread go, leaves the flag on an idle pool thread —
  for whatever runs there next. Run with

    jolt -A:test -e '(load-file \"dev/brainyard/dev/interrupt_spike.clj\")'

  It races `rounds` tasks — through `fx/via-blk`, the kernel's runner that
  interrupts under the lock, or with SPIKE_VIA=ebb through ebb's own `via` —
  and probes the pool after each: the probe thunk reports and clears its
  thread's flag. Any stale flag is a count above zero. Measured 2026-10-14:
  ebb 18 of 30 000 and 6 of 5 000; fx/via-blk 0 of 30 000."
  (:require [brainyard.effect :as fx]
            [ebb.core :as m]))

(defn- spin
  "Busy for up to `ns` nanoseconds: work that never consumes the flag, so an
  interrupt landing during it stays set until the evaluation's own clear."
  [ns]
  (let [end (+ (System/nanoTime) ns)]
    (while (< (System/nanoTime) end))))

(defn race-once
  "A thunk busy for a random while, cancelled from here after another random
  while: some cancels land as the evaluation ends. Then the pool is probed at
  once — a cached pool hands the thread just freed to the next thunk — and
  the probe reports and clears its thread's flag."
  []
  (let [p (promise)
        cancel (fx/run (if (= "ebb" (System/getenv "SPIKE_VIA"))
                         (m/via m/blk (spin (rand-int 100000)) :done)
                         (fx/via-blk (spin (rand-int 100000)) :done))
                       #(deliver p %) #(deliver p %))]
    (spin (rand-int 100000))
    (cancel)
    (deref p 1000 :late)
    (fx/run-value!! (m/via m/blk (Thread/interrupted)))))

(defn sanity
  "jolt's interrupt protocol as this spike relies on it: a flag set on a busy
  thread is read, and cleared, by Thread/interrupted on that thread."
  []
  (let [p (promise)
        t (Thread. (fn [] (spin 20000000) (deliver p [(Thread/interrupted) (Thread/interrupted)])))]
    (.start t)
    (Thread/sleep 2)
    (.interrupt t)
    (deref p 2000 :late)))

(let [rounds (or (some-> (System/getenv "SPIKE_ROUNDS") parse-long) 20000)]
  (println (str "sanity — a busy thread's flag, read then read again: " (pr-str (sanity))))
  (let [stale (count (filter true? (repeatedly rounds race-once)))]
    (println (str rounds " cancels racing a thunk's end (" (if (= "ebb" (System/getenv "SPIKE_VIA")) "ebb's via" "fx/via-blk")
                  "); stale interrupt flags found on the pool: " stale))
    (System/exit (if (pos? stale) 1 0))))
