;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.dev.http-stream-spike
  "Measurements behind the `no-http-streaming` divergence and RFC-006
  'Streaming': can an HTTP provider receive a reply incrementally under jolt?

      jolt -A:dev -m brainyard.dev.http-stream-spike

  brainyard streams every HTTP provider over SSE
  (`clj-llm/core/{sse,sse_flow,http_flow}.clj`), and it does so on
  `java.net.http.HttpClient` — `http_flow.clj` imports it directly and
  `sse.clj` parses events off a `BufferedReader` with `.readLine`. So the
  question is not whether SSE is a good idea; it is which of the four routes to
  an incrementally-readable socket exists here.

  The server writes an SSE body in three chunks, `chunk-ms` apart. A route
  streams if the three arrivals are spaced like the writes, and buffers if they
  all land together at the end. Each row prints the arrival time of every read,
  so a buffered route is visible as three numbers that agree rather than as a
  verdict you have to trust.

  Every class below is named in full on purpose: an `:import` in the `ns` form
  makes an unimplemented JDK class look present — `HttpClient/newHttpClient`
  imported that way returns quietly, and the same call fully qualified raises
  the missing-dependency error. Do not tidy the names away.

  This exists because the claim was twice published wrong from an ad-hoc
  measurement: first that a subprocess could not stream (it was
  `clojure.java.io/reader` buffering, not jolt — see
  `fx/streaming-process`), and then that the HTTP client's buffering was the
  whole obstacle. Re-run this rather than quoting the prose."
  (:require [clojure.string :as str]
            [jolt.http-client :as http]
            [jolt.process :as p]))

(def chunk-ms 600)
(def chunks 3)

(defn- now [] (System/currentTimeMillis))

(defn- sse-server!
  "A local HTTP server that writes `chunks` SSE events `chunk-ms` apart, then
  closes. Returns [port proc]."
  []
  (let [port (+ 20000 (rand-int 20000))
        code (str "import socket,time\n"
                  "s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)\n"
                  "s.bind(('127.0.0.1'," port "));s.listen(8)\n"
                  "while True:\n"
                  "  c,_=s.accept();c.recv(4096)\n"
                  "  try:\n"
                  "    c.sendall(b'HTTP/1.0 200 OK\\r\\nContent-Type: text/event-stream\\r\\n\\r\\n')\n"
                  "    for i in range(" chunks "):\n"
                  "      c.sendall(('data: chunk-%d\\n\\n' % i).encode())\n"
                  "      time.sleep(" (/ chunk-ms 1000.0) ")\n"
                  "    c.close()\n"
                  "  except Exception: pass\n")
        proc (p/process {:out :string :err :string} "python3" "-c" code)]
    (Thread/sleep 500)
    [port proc]))

(defn- drain
  "Read `is` to EOF, one `.read` at a time, returning the ms at which each
  non-empty read landed — the shape RFC-006's table reports."
  [is t0]
  (let [buf (byte-array 4096)]
    (loop [arrivals []]
      (let [n (.read is buf)]
        (if (neg? n)
          arrivals
          (recur (cond-> arrivals (pos? n) (conj (- (now) t0)))))))))

(defn- verdict
  "Streaming shows up as a spread across the writes; buffering as arrivals that
  all land together after the last write."
  [arrivals]
  (if (empty? arrivals)
    "nothing arrived"
    (let [spread (- (apply max arrivals) (apply min arrivals))]
      (if (> spread (/ (* chunk-ms (dec chunks)) 2)) "STREAMS" "buffered"))))

(defn- row [label f]
  (let [t0 (now)
        r (try {:arrivals (f t0)} (catch Throwable e {:error (ex-message e)}))]
    (if-let [e (:error r)]
      (println (format "%-34s unavailable: %s" label (first (str/split-lines (str e)))))
      (println (format "%-34s %-9s arrivals at %s ms"
                       label (verdict (:arrivals r)) (str/join ", " (:arrivals r)))))))

(defn- socket-get
  "An HTTP/1.1 GET written by hand over `sock`, then drained."
  [sock host t0]
  (let [req (str "GET / HTTP/1.1\r\nHost: " host "\r\nConnection: close\r\n\r\n")]
    (.write (.getOutputStream sock) (.getBytes req "UTF-8"))
    (.flush (.getOutputStream sock))
    (drain (.getInputStream sock) t0)))

(defn -main [& _]
  (let [[port server] (sse-server!)
        url (str "http://127.0.0.1:" port "/")]
    (println (format "server: %d SSE events, %d ms apart, on %s\n" chunks chunk-ms url))

    ;; 1. what the providers use today (brainyard.llm)
    (row "jolt.http-client :as :stream"
         (fn [t0] (drain (:body (http/get url {:as :stream :socket-timeout 10000
                                               :throw-exceptions false}))
                         t0)))

    ;; 2. what brainyard's http_flow.clj uses. Constructing a client appears to
    ;;    work — it is sending that reaches for the implementation, so the row
    ;;    sends, exactly as http_flow does.
    (row "java.net.http.HttpClient"
         (fn [t0]
           (let [client (java.net.http.HttpClient/newHttpClient)
                 req (-> (java.net.http.HttpRequest/newBuilder)
                         (.uri (java.net.URI/create url))
                         (.GET)
                         (.build))
                 resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofInputStream))]
             (drain (.body resp) t0))))

    ;; 2b. what brainyard's sse_publisher.clj actually streams on: sendAsync
    ;;     with ofPublisher, a Flow.Publisher<List<ByteBuffer>> whose onNext
    ;;     fires per chunk. This is THE route SSE rides on over there.
    (row "java.net.http ofPublisher (Flow)"
         (fn [t0]
           (let [client (java.net.http.HttpClient/newHttpClient)
                 req (-> (java.net.http.HttpRequest/newBuilder)
                         (.uri (java.net.URI/create url)) (.GET) (.build))
                 arrivals (atom [])
                 done (promise)
                 pub (.body (.get (.sendAsync client req
                                              (java.net.http.HttpResponse$BodyHandlers/ofPublisher))))]
             (.subscribe pub
                         (reify java.util.concurrent.Flow$Subscriber
                           (onSubscribe [_ sub] (.request sub Long/MAX_VALUE))
                           (onNext [_ _item] (swap! arrivals conj (- (now) t0)))
                           (onError [_ e] (deliver done e))
                           (onComplete [_] (deliver done :ok))))
             (deref done 15000 :timeout)
             @arrivals)))

    ;; 3. the floor: HTTP written by hand, no client in the way
    (row "java.net.Socket (plaintext)"
         (fn [t0] (socket-get (java.net.Socket. "127.0.0.1" (int port)) "127.0.0.1" t0)))

    ;; 4. the same floor for an https:// provider, which is all of them
    (row "javax.net.ssl.SSLSocketFactory"
         (fn [t0] (socket-get (.createSocket (javax.net.ssl.SSLSocketFactory/getDefault)
                                             "api.anthropic.com" (int 443))
                              "api.anthropic.com" t0)))

    (println "\nA provider streams only if a route it can reach over https STREAMS.")
    (p/destroy-tree server)))
