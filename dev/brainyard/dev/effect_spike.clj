;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.effect-spike
  "Measurements behind byj-gly.1 (docs/RFCS/RFC-009-effects.md): what each
  candidate substrate does, on this repository's blocking paths, when a wait is
  cancelled.

      jolt -A:dev -m brainyard.dev.effect-spike

  Every row cancels its wait at CANCEL-MS and reports how long the cancel took
  to settle and what the waiter saw. The waits are the ones the ported runtime
  actually makes: a sleep, a promise, a subprocess (the claude CLI, bash tool),
  and an HTTP read (every HTTP provider), against a local server that answers
  only after SLOW-MS.

  After the run, a subprocess row also reports whether the child process
  outlived the cancel — the leak brainyard's run!! comments warn about."
  (:require [clojure.string :as str]
            [ebb.core :as m]
            [clojure.core.async]
            [jolt.http-client :as http]
            [jolt.process :as p]))

(def cancel-ms 500)
(def slow-ms 5000)

(defn- now [] (System/currentTimeMillis))

(defn- outcome [x]
  (cond
    (and (instance? Throwable x) (m/cancelled? x)) "Cancelled"
    (instance? Throwable x) (str (.getSimpleName (class x)) ": "
                                 (first (str/split-lines (str (ex-message x)))))
    :else (str "value " (pr-str x))))

(defn- measure-task
  "Run an ebb task, cancel it at cancel-ms, report how long settling took."
  [label task]
  (let [done (promise)
        t0 (now)
        cancel (task #(deliver done [:ok % (now)]) #(deliver done [:err % (now)]))]
    (Thread/sleep cancel-ms)
    (let [tc (now)]
      (cancel)
      (let [[kind v t1] (deref done 15000 [:hung nil (now)])]
        {:row label
         :settled-after-cancel-ms (- t1 tc)
         :total-ms (- t1 t0)
         :saw (if (= :hung kind) "HUNG (>15s)" (outcome v))}))))

(defn- measure-future
  "future + future-cancel. realized? is true the moment a future is cancelled,
  whether or not its body stopped, so the body records its own end."
  [label f]
  (let [t0 (now)
        body-end (promise)
        fut (future (try (f) (catch Throwable e e) (finally (deliver body-end (now)))))]
    (Thread/sleep cancel-ms)
    (let [tc (now)
          cancelled (future-cancel fut)
          end (deref body-end 15000 nil)]
      {:row label :future-cancel-returned cancelled
       :body-stopped-after-cancel-ms (if end (- end tc) "still running at 15s")
       :total-ms (- (or end (now)) t0)})))

(def ^:dynamic *probe* :root)

(defn- slow-server!
  "A local HTTP server that accepts and answers after slow-ms. Returns [port proc]."
  []
  (let [port (+ 20000 (rand-int 20000))
        code (str "import socket,time\n"
                  "s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)\n"
                  "s.bind(('127.0.0.1'," port "));s.listen(8)\n"
                  "while True:\n"
                  "  c,_=s.accept();c.recv(4096);time.sleep(" (/ slow-ms 1000.0) ")\n"
                  "  try:\n    c.sendall(b'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nok');c.close()\n"
                  "  except Exception: pass\n")
        proc (p/process {:out :string :err :string} "python3" "-c" code)]
    (Thread/sleep 400)
    [port proc]))

(defn- child-alive? [marker]
  (not (str/blank? (:out (p/sh "pgrep" "-f" marker)))))

(defn -main [& _]
  (let [[port server] (slow-server!)
        url (str "http://127.0.0.1:" port "/")
        http-get #(http/get url {:socket-timeout 10000 :conn-timeout 2000 :throw-exceptions false})
        n (rand-int 50)
        marker #(str "sleep " (+ 7 n) "." %)          ; one marker per row
        sub-cmd #(vector "bash" "-c" (marker %))
        rows
        [(measure-task "ebb sleep on the fiber" (m/sleep slow-ms :done))
         (measure-task "ebb via blk Thread/sleep" (m/via m/blk (Thread/sleep slow-ms) :done))
         (measure-task "ebb via blk promise deref" (let [pr (promise)] (m/via m/blk @pr)))
         (let [r (measure-task "ebb via blk subprocess deref"
                               (m/via m/blk @(apply p/process {:out :string :err :string} (sub-cmd 11))))]
           (Thread/sleep 200)
           (assoc r :child-alive-after (child-alive? (marker 11))))
         (let [pr (atom nil)
               r (measure-task "ebb via blk subprocess, destroy-tree on cancel"
                               (fn [s f]
                                 (let [cancel ((m/via m/blk
                                                      (let [proc (apply p/process {:out :string :err :string} (sub-cmd 22))]
                                                        (reset! pr proc)
                                                        @proc))
                                               s f)]
                                   (fn [] (some-> @pr p/destroy-tree) (cancel)))))]
           (Thread/sleep 200)
           (assoc r :child-alive-after (child-alive? (marker 22))))
         (let [r (measure-future "future-cancel subprocess deref"
                                 #(deref (apply p/process {:out :string :err :string} (sub-cmd 33))))]
           (Thread/sleep 200)
           (assoc r :child-alive-after (child-alive? (marker 33))))
         (measure-task "ebb via blk http-client recv (v0.0.8)" (m/via m/blk (http-get)))
         (measure-task "ebb timeout(via blk http recv, 500)"
                       ;; timeout, not cancel: how long until the timeout settles
                       (fn [s f] ((m/timeout (m/via m/blk (http-get)) cancel-ms :timed-out) s f)
                         (fn [])))
         (measure-future "future-cancel Thread/sleep" #(Thread/sleep slow-ms))
         (measure-future "future-cancel http-client recv" http-get)]
        composition
        {:join-two-sleeps-ms (let [t0 (now)] (m/? (m/join vector (m/sleep 300 :a) (m/sleep 300 :b))) (- (now) t0))
         :race-first-wins (m/? (m/race (m/sleep 50 :fast) (m/sleep 500 :slow)))
         :park-in-helper-fn (letfn [(helper [ms v] (m/? (m/sleep ms v)))] (m/? (m/sp (inc (helper 10 5)))))
         :bindings-cross-via-blk (binding [*probe* :bound]
                                   (m/? (m/via m/blk *probe*)))
         :binding-after-park-in-sp (binding [*probe* :bound]
                                     (m/? (m/sp (let [before *probe*]
                                                  (m/? (m/sleep 10))
                                                  [before *probe*]))))
         :core-async-thread-alts (let [c (clojure.core.async/thread (Thread/sleep 200) :done)
                                       [v port] (clojure.core.async/alts!! [c (clojure.core.async/timeout 50)])]
                                   [(if (= port c) v :timeout)
                                    :no-cancel-of-the-thread])}]
    (p/destroy-tree server)
    (doseq [r rows] (prn r))
    (prn composition)
    (System/exit 0)))
