;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.ui-boundary-spike
  "Measurements behind RFC-027: what does it cost to put bytes between the
  kernel and a UI, and what does jolt actually let you build?

      jolt -A:dev -m brainyard.dev.ui-boundary-spike

  The port's UI boundary already exists — `session/run` takes `:read-input`,
  `:emit`, `:on-event`, `:permission-fn` and `:cancellers` as function values
  (`main.clj`). The question RFC-027 answers is whether anything should ever
  cross it as BYTES instead, which is four separate questions:

  1. Does a unix domain socket exist here? brainyard's `--serve` is AF_UNIX at
     `<session-dir>/ask.sock` mode 0600, where the security model IS the
     filesystem permission. If it does not exist, that design cannot be ported,
     only redesigned.
  2. Can jolt accept TCP connections at all? `jolt.nrepl` does, so the answer
     should be yes — but a measurement beats an inference about someone else's
     accept loop.
  3. What does a round trip cost, against the in-process call it would replace?
     A turn emits events continuously and asks permission rarely, so the two
     matter at different scales and are measured apart.
  4. Can a blocked accept loop be stopped? jolt's `Thread.interrupt` is
     cooperative and does not reach a thread inside a blocking syscall, which
     is exactly where `accept(2)` sits. A daemon that cannot be shut down
     cleanly is a daemon that leaks ports.

  Every JDK class is named in full on purpose: an `:import` makes an
  unimplemented class look present, and the same call fully qualified raises
  the missing-dependency error (the lesson from `http_stream_spike`). Do not
  tidy the names away.

  Prints raw numbers rather than verdicts, so a conclusion can be re-derived
  rather than trusted."
  (:require [clojure.string :as str]))

(def ^:private iterations 2000)

(defn- ms [^long t0] (/ (double (- (System/nanoTime) t0)) 1e6))

(defn- micros-each [total-ms n] (format "%.2f" (* 1000.0 (/ total-ms n))))

;; --- 1. unix domain sockets -------------------------------------------------------

(defn probe-unix-socket
  "Is there ANY route to an AF_UNIX socket? Three, each reported separately,
  because 'not supported' and 'not implemented' are different answers and only
  one of them can be worked around."
  []
  (println "\n1. UNIX DOMAIN SOCKETS — brainyard's --serve transport")
  (doseq [[what f]
          [["java.net.UnixDomainSocketAddress/of"
            #(java.net.UnixDomainSocketAddress/of "/tmp/by-probe.sock")]
           ["java.net.StandardProtocolFamily/UNIX"
            #(java.net.StandardProtocolFamily/valueOf "UNIX")]
           ["ServerSocketChannel/open with a protocol family"
            #(java.nio.channels.ServerSocketChannel/open
              (java.net.StandardProtocolFamily/valueOf "UNIX"))]]]
    (let [r (try {:ok (str (f))} (catch Throwable e {:err (ex-message e)}))]
      (println (format "   %-46s %s" what
                       (if (:ok r) (str "present — " (:ok r))
                           (str "unavailable — " (:err r))))))))

;; --- 2 & 3. TCP: does it work, and what does it cost? -----------------------------

(defn- buffered-line-reader
  "The same read, one syscall per buffer instead of one per byte. Measured
  beside the naive version because otherwise the transport gets blamed for the
  read strategy."
  [^java.io.InputStream in]
  (let [buf (byte-array 4096)
        pending (volatile! "")]
    (fn []
      (loop []
        (let [s @pending
              i (.indexOf ^String s "\n")]
          (if (neg? i)
            (let [n (.read in buf)]
              (when (pos? n)
                (vreset! pending (str s (String. buf 0 n)))
                (recur)))
            (do (vreset! pending (subs s (inc i)))
                (subs s 0 i))))))))

(defn- read-line-bytes
  "Read one newline-terminated line off a raw stream. Not
  `clojure.java.io/reader`: that buffers a subprocess stream until exit
  (RFC-006), and a socket protocol has to be written this way anyway."
  [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (= b -1) (when (pos? (.length sb)) (.toString sb))
          (= b 10) (.toString sb)
          :else (do (.append sb (char b)) (recur)))))))

(defn- write-line [^java.io.OutputStream out ^String s]
  (.write out (.getBytes (str s "\n")))
  (.flush out))

(defn measure-tcp
  "An echo server on loopback, then N round trips over ONE connection —
  the shape a daemon would actually have, not connect-per-message."
  []
  (println "\n2. TCP SERVER SOCKET — can jolt accept, and what does a round trip cost?")
  (let [server (try (java.net.ServerSocket. 0)
                    (catch Throwable e
                      (println "   cannot bind:" (ex-message e)) nil))]
    (when server
      (let [port (.getLocalPort server)
            _ (println (format "   bound on 127.0.0.1:%d" port))
            ;; echo server: one connection, read a line, write it back
            serving (future
                      (try
                        (let [conn (.accept server)
                              in (.getInputStream conn)
                              out (.getOutputStream conn)]
                          (loop []
                            (when-let [line (read-line-bytes in)]
                              (write-line out line)
                              (recur)))
                          (.close conn))
                        (catch Throwable _ nil)))
            client (java.net.Socket. "127.0.0.1" (int port))
            cin (.getInputStream client)
            cout (.getOutputStream client)]
        ;; warm the path before timing it
        (dotimes [_ 50] (write-line cout "warm") (read-line-bytes cin))
        (let [payload (str "{\"event\":\"agent.tool-use/pre\",\"data\":{\"tool\":\"bash\"}}")
              naive (let [t0 (System/nanoTime)]
                      (dotimes [_ iterations] (write-line cout payload) (read-line-bytes cin))
                      (ms t0))
              read-buffered (buffered-line-reader cin)
              buffered (let [t0 (System/nanoTime)]
                         (dotimes [_ iterations] (write-line cout payload) (read-buffered))
                         (ms t0))]
          (println (format "   %d round trips, one byte per read : %.1f ms total, %s us each"
                           iterations naive (micros-each naive iterations)))
          (println (format "   %d round trips, 4096-byte buffer  : %.1f ms total, %s us each"
                           iterations buffered (micros-each buffered iterations)))
          (println "   (the gap is the READ STRATEGY, not the transport — a protocol")
          (println "    written the naive way pays it on every line)")
          (.close client)
          @serving
          (.close server)
          (micros-each buffered iterations))))))

(defn measure-in-process
  "The same event, delivered the way it is delivered today: a function call."
  []
  (println "\n3. IN-PROCESS CALL — what the socket would replace")
  (let [sink (volatile! 0)
        on-event (fn [_event _data] (vswap! sink inc))
        t0 (System/nanoTime)
        _ (dotimes [_ iterations] (on-event :agent.tool-use/pre {:tool "bash"}))
        total (ms t0)]
    (println (format "   %d :on-event calls: %.3f ms total, %s us each  (delivered %d)"
                     iterations total (micros-each total iterations) @sink))
    (micros-each total iterations)))

;; --- 4. can an accept loop be stopped? --------------------------------------------

(defn measure-shutdown
  "A daemon parks in `accept(2)`. jolt's interrupt is cooperative and does not
  reach a thread inside a blocking syscall, so the question is whether closing
  the socket underneath it does."
  []
  (println "\n4. SHUTDOWN — can a thread blocked in accept(2) be released?")
  (doseq [[what release]
          [["Thread/interrupt on the accepting thread" :interrupt]
           [".close on the ServerSocket" :close]]]
    (let [server (java.net.ServerSocket. 0)
          state (atom :blocked)
          t (atom nil)
          serving (future
                    (reset! t (Thread/currentThread))
                    (try (.accept server)
                         (reset! state :accepted)
                         (catch Throwable e (reset! state (str "released — " (ex-message e))))))]
      (Thread/sleep 300)
      (let [before @state
            t0 (System/nanoTime)]
        (case release
          :interrupt (some-> ^Thread @t .interrupt)
          :close (.close server))
        (Thread/sleep 400)
        (println (format "   %-42s before: %s   after %.0f ms: %s"
                         what before (ms t0) @state)))
      (try (.close server) (catch Throwable _ nil))
      (future-cancel serving)))

  ;; Neither standard route works, so the question becomes whether the known
  ;; workaround does. jolt's OWN nrepl accept loop has this shape — it checks
  ;; its stopped flag only AFTER accept returns — so an idle jolt server is
  ;; released by giving it the one thing it is waiting for.
  (let [server (java.net.ServerSocket. 0)
        port (.getLocalPort server)
        state (atom :blocked)
        stopped (atom false)
        serving (future
                  (try (let [conn (.accept server)]
                         (reset! state (if @stopped :released-by-self-connect :accepted))
                         (.close conn))
                       (catch Throwable e (reset! state (str "threw — " (ex-message e))))))]
    (Thread/sleep 300)
    (let [t0 (System/nanoTime)]
      (reset! stopped true)
      (.close (java.net.Socket. "127.0.0.1" (int port)))
      (Thread/sleep 300)
      (println (format "   %-42s before: :blocked   after %.0f ms: %s"
                       "a self-connect, with a stopped flag" (ms t0) @state)))
    (try (.close server) (catch Throwable _ nil))
    (future-cancel serving)))

(defn -main [& _]
  (println "RFC-027 — the cost and the shape of a kernel/UI boundary made of bytes")
  (probe-unix-socket)
  (let [tcp (measure-tcp)
        inproc (measure-in-process)]
    (measure-shutdown)
    (println "\nSUMMARY")
    (println (str "   one event in-process : " inproc " us"))
    (println (str "   one event over TCP   : " tcp " us"))
    (when (and tcp inproc)
      (let [ratio (/ (Double/parseDouble tcp) (max 1e-9 (Double/parseDouble inproc)))]
        (println (format "   ratio                : %.0fx" ratio))))
    (println "\n   Read the ratio as the cost of the CHOICE, not of the transport:")
    (println "   both numbers are small, and neither is why brainyard retired its")
    (println "   daemon split. The reason was complexity, and that does not show")
    (println "   up in a benchmark — see RFC-027.")))
