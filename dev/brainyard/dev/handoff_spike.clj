(ns brainyard.dev.handoff-spike
  "Measurements behind RFC-031: how does one live session reach another?
  (byj-gly.253.1)

      jolt -A:dev -m brainyard.dev.handoff-spike

  brainyard binds `<session-dir>/ask.sock`, an AF_UNIX socket at mode 0600, and
  trusts the filesystem. RFC-027 found no AF_UNIX in jolt's java.nio, so that
  design is redesigned here, not ported. Three candidates are measured:

  1. A FILE MAILBOX. The sender writes `inbox/<id>.edn` (a temp file renamed
     into place, so the owner never reads half a message); the owner picks it
     up on a poll and answers in `outbox/<id>.edn`. Measured ACROSS PROCESSES,
     which is the case that matters: the owner is a second jolt process.
     - the round trip at poll intervals of 250 ms (the TUI's existing tick)
       and 50 ms;
     - exactly-once delivery with several senders writing at once;
     - what an idle owner polling an empty inbox costs in CPU.
  2. AF_UNIX THROUGH libc. jolt.http.net already calls socket/connect/send/
     recv/poll through jolt.ffi; bind/listen/accept are three more
     declarations. Does it bind, answer, and stop cleanly?
  3. LOOPBACK TCP. RFC-027 measured it (a ~200 µs round trip, an accept that
     cannot be interrupted); not measured again here.

  Prints raw numbers rather than verdicts, so the conclusion can be re-derived
  rather than trusted. `sockaddr_un` is laid out for macOS and Linux; another
  platform is reported, not guessed at."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.http.net :as net]
            [jolt.io-poller :as poller]
            [jolt.process :as p]))

(defn- ms-since [^long t0] (/ (double (- (System/nanoTime) t0)) 1e6))

(defn- stats [xs]
  (let [v (vec (sort xs)) n (count v)
        at (fn [q] (v (min (dec n) (int (* q n)))))]
    (format "n=%d  min %.1f  p50 %.1f  p90 %.1f  max %.1f ms" n (first v) (at 0.5) (at 0.9) (peek v))))

(defn- sh [& args] (:out @(apply p/process {:out :string :err :string} args)))

;; --- 1. the file mailbox ------------------------------------------------------------

(defn- put-atomically!
  "Write `text` so a reader sees all of it or none: to a dot-file beside the
  target, then renamed. rename(2) within one directory is atomic."
  [^java.io.File target text]
  (let [tmp (io/file (.getParentFile target) (str "." (.getName target) ".tmp"))]
    (spit tmp text)
    (when-not (.renameTo tmp target)
      (throw (ex-info "rename failed" {:target (.getPath target)})))))

(defn- messages
  "The inbox's complete messages, oldest name first. A dot-file is one still
  being written."
  [^java.io.File dir]
  (->> (.listFiles dir)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")
                     (not (str/starts-with? (.getName %) "."))))
       (sort-by #(.getName %))))

(defn receiver
  "The owner's side, run in its own process: every `poll-ms`, claim each
  message by renaming it into `claimed/` (so a second reader could never take
  it too), and answer it in `outbox/`. Stops when `stop` appears."
  [root poll-ms]
  (let [root (io/file root)
        inbox (io/file root "inbox") claimed (io/file root "claimed") outbox (io/file root "outbox")
        seen (atom 0)]
    (doseq [d [inbox claimed outbox]] (.mkdirs d))
    (spit (io/file root "ready") "ok")
    (loop []
      (when-not (.exists (io/file root "stop"))
        (doseq [f (messages inbox)
                :let [mine (io/file claimed (.getName f))]
                :when (.renameTo f mine)]
          (let [{:keys [id op question]} (edn/read-string (slurp mine))]
            (swap! seen inc)
            (put-atomically! (io/file outbox (str id ".edn"))
                             (pr-str {:id id :status :ok :op op :answer (str "echo: " question)}))
            (.delete mine)))
        (Thread/sleep (long poll-ms))
        (recur)))
    (spit (io/file root "seen") (str @seen))))

(defn- ask!
  "The sender's side: drop a request in the inbox, then wait for its answer,
  looking every 5 ms. Returns the answer and the round trip in ms."
  [root id question timeout-ms]
  (let [t0 (System/nanoTime)
        reply (io/file root "outbox" (str id ".edn"))]
    (put-atomically! (io/file root "inbox" (str id ".edn"))
                     (pr-str {:id id :op :ask :question question}))
    (loop []
      (cond
        (.exists reply) (let [r (edn/read-string (slurp reply))]
                          (.delete reply)
                          (assoc r :ms (ms-since t0)))
        (> (ms-since t0) timeout-ms) {:id id :status :error :error "timed out" :ms (ms-since t0)}
        :else (do (Thread/sleep 5) (recur))))))

(defn- start-receiver!
  "A second jolt process running `receiver`, and a way to stop it."
  [root poll-ms]
  (doseq [f (reverse (file-seq (io/file root)))] (.delete f))
  (.mkdirs (io/file root))
  (let [proc (p/process {:out :string :err :string}
                        "jolt" "-A:dev" "-m" "brainyard.dev.handoff-spike" "receiver" root (str poll-ms))
        t0 (System/nanoTime)]
    (loop [] (when (and (not (.exists (io/file root "ready"))) (< (ms-since t0) 30000))
               (Thread/sleep 20) (recur)))
    {:proc proc
     :pid (str/trim (sh "sh" "-c" (str "pgrep -f 'handoff-spike receiver " root "' | head -1")))
     :stop! (fn [] (spit (io/file root "stop") "") @proc
              (parse-long (str/trim (slurp (io/file root "seen")))))}))

(defn- cpu-seconds
  "The process's CPU time so far, from ps: `[[dd-]hh:]mm:ss.cc`."
  [pid]
  (let [t (str/trim (sh "ps" "-o" "time=" "-p" (str pid)))
        [a b c] (reverse (str/split t #":"))]
    (+ (Double/parseDouble a) (* 60 (parse-long (or b "0"))) (* 3600 (parse-long (or c "0"))))))

(declare measure-concurrent measure-idle)

(defn measure-mailbox []
  (println "\n1. FILE MAILBOX — owner in a second jolt process")
  (doseq [poll [250 50]]
    (let [root (str "/tmp/byj-handoff-spike-" poll)
          {:keys [stop!]} (start-receiver! root poll)
          rtts (mapv (fn [i] (:ms (ask! root (str "m" i) (str "q" i) 5000))) (range 40))]
      (println (format "   poll %3d ms, one sender, 40 asks:  %s" poll (stats rtts)))
      (stop!)))
  (measure-concurrent)
  (measure-idle))

(defn measure-concurrent []
  (let [root "/tmp/byj-handoff-spike-concurrent"
        {:keys [stop!]} (start-receiver! root 50)
        senders 4 per 50
        ;; realised here, BEFORE the owner is stopped: a lazy seq would stop
        ;; it first and leave every sender waiting out its timeout
        answers (->> (range senders)
                     (mapv (fn [s] (future (mapv (fn [i] (ask! root (format "s%d-%03d" s i) (str s "/" i) 20000))
                                                 (range per)))))
                     (into [] (mapcat deref)))
        seen (stop!)
        ok (filter #(= :ok (:status %)) answers)
        right (filter #(= (:answer %) (str "echo: " (str/replace (subs (:id %) 1) #"-0*(\d)" "/$1"))) ok)]
    (println (format "   %d senders x %d asks at once: %d answered, %d with the right answer, owner saw %d (want %d)"
                     senders per (count ok) (count right) seen (* senders per)))
    (println (format "   leftovers: inbox %d  claimed %d  outbox %d"
                     (count (.listFiles (io/file root "inbox"))) (count (.listFiles (io/file root "claimed")))
                     (count (.listFiles (io/file root "outbox")))))))

(defn measure-idle []
  (doseq [poll [250 50]]
    (let [root (str "/tmp/byj-handoff-spike-idle-" poll)
          {:keys [pid stop!]} (start-receiver! root poll)
          _ (Thread/sleep 1000)
          c0 (cpu-seconds pid)
          _ (Thread/sleep 10000)
          c1 (cpu-seconds pid)]
      (println (format "   idle owner polling an empty inbox every %3d ms: %.2f s CPU in 10 s (%.2f%%)"
                       poll (- c1 c0) (* 10 (- c1 c0))))
      (stop!))))

;; --- 2. AF_UNIX through libc ----------------------------------------------------------

(ffi/defcfn c-bind   "bind"   [:int :pointer :int] :int)
(ffi/defcfn c-listen "listen" [:int :int] :int)
(ffi/defcfn c-accept "accept" [:int :pointer :pointer] :int :blocking)
(ffi/defcfn c-poll   "poll"   [:pointer :int :int] :int :blocking)

(def ^:private af-unix 1)
(def ^:private sock-stream 1)

(defn- sockaddr-un
  "`struct sockaddr_un` for `path`, and its length. macOS/BSD: a length byte,
  a family byte, then 104 bytes of path. Linux: a two-byte family, then 108."
  [path]
  (let [os (str/trim (sh "uname" "-s"))
        bs (.getBytes (str path) "UTF-8")
        size 110
        a (ffi/alloc size)]
    (dotimes [i size] (ffi/write a :uint8 0 i))
    (case os
      "Darwin" (do (ffi/write a :uint8 size 0) (ffi/write a :uint8 af-unix 1))
      "Linux" (ffi/write a :uint16 af-unix 0)
      (throw (ex-info (str "sockaddr_un not laid out for " os) {:os os})))
    (dotimes [i (alength bs)] (ffi/write a :uint8 (bit-and 0xff (aget bs i)) (+ 2 i)))
    [a (+ 2 (alength bs) 1)]))

(defn- readable?
  "Is `fd` readable within `timeout-ms`? What lets an accept loop look at a
  stop flag between polls instead of sitting in accept(2) forever."
  [fd timeout-ms]
  (let [pf (ffi/alloc 8)]
    (try
      (dotimes [i 8] (ffi/write pf :uint8 0 i))
      (ffi/write pf :int fd 0)
      (ffi/write pf :short 1 4)                  ; POLLIN
      (pos? (c-poll pf 1 timeout-ms))
      (finally (ffi/free pf)))))

(defn- read-line!
  "Bytes from `fd` up to the first newline, as a string."
  [fd]
  (loop [acc ""]
    (if (str/includes? acc "\n")
      (first (str/split acc #"\n"))
      (if-let [b (net/recv-bytes fd)]
        (recur (str acc (String. ^bytes b "UTF-8")))
        acc))))

(defn- serve!
  "An ask.sock-shaped server: one EDN line in, one EDN line out, per
  connection. Returns {:stop! :served}."
  [path]
  (let [fd (net/c-socket af-unix sock-stream 0)
        [addr len] (sockaddr-un path)
        _ (io/delete-file path true)
        bound (c-bind fd addr len)
        _ (ffi/free addr)
        _ (when (neg? bound) (throw (ex-info "bind failed" {:errno (poller/errno)})))
        _ (sh "chmod" "600" path)
        _ (c-listen fd 16)
        stop? (atom false)
        served (atom 0)
        worker (future
                 (loop []
                   (when-not @stop?
                     (when (readable? fd 100)
                       (let [c (c-accept fd 0 0)]
                         (when-not (neg? c)
                           (let [{:keys [question]} (edn/read-string (read-line! c))]
                             (when (= "stop" question) (spit (str path ".stop") ""))
                             (net/send-bytes c (.getBytes (str (pr-str {:status :ok :answer (str "echo: " question)}) "\n")
                                                          "UTF-8"))
                             (swap! served inc)
                             (net/close c)))))
                     (recur))))]
    {:stop! (fn [] (reset! stop? true) @worker (net/close fd) (io/delete-file path true))
     :served served}))

(defn- ask-socket! [path question]
  (let [fd (net/c-socket af-unix sock-stream 0)
        [addr len] (sockaddr-un path)]
    (try
      (when (neg? (net/c-connect fd addr len)) (throw (ex-info "connect failed" {:errno (poller/errno)})))
      (net/send-bytes fd (.getBytes (str (pr-str {:op :ask :question question}) "\n") "UTF-8"))
      (edn/read-string (read-line! fd))
      (finally (ffi/free addr) (net/close fd)))))

(defn measure-unix-socket []
  (println "\n2. AF_UNIX THROUGH libc (jolt.ffi)")
  (let [path "/tmp/byj-handoff-spike.sock"
        r (try
            (let [{:keys [stop! served]} (serve! path)
                  mode (str/trim (sh "stat" "-f" "%Sp" path))
                  first-answer (ask-socket! path "hello")
                  rtts (mapv (fn [i] (let [t0 (System/nanoTime)] (ask-socket! path (str i)) (ms-since t0))) (range 200))
                  t0 (System/nanoTime)]
              (stop!)
              {:mode mode :answer first-answer :rtts rtts :served @served
               :stop-ms (ms-since t0) :gone? (not (.exists (io/file path)))})
            (catch Throwable e {:error (str (ex-message e) " " (ex-data e))}))]
    (if-let [e (:error r)]
      (println "   FAILED:" e)
      (do (println "   bound, mode" (:mode r) "— first answer" (pr-str (:answer r)))
          (println (format "   200 asks, one connection each:  %s" (stats (:rtts r))))
          (println (format "   served %d; stopped an idle accept loop in %.1f ms; socket file removed: %s"
                           (:served r) (:stop-ms r) (:gone? r)))))))

(defn measure-unix-socket-across-processes []
  (println "   — and with the server in a second jolt process:")
  (let [path "/tmp/byj-handoff-spike-x.sock"
        _ (io/delete-file path true)
        proc (p/process {:out :string :err :string}
                        "jolt" "-A:dev" "-m" "brainyard.dev.handoff-spike" "unix-server" path)
        t0 (System/nanoTime)]
    (loop [] (when (and (not (.exists (io/file path))) (< (ms-since t0) 30000)) (Thread/sleep 20) (recur)))
    (let [rtts (mapv (fn [i] (let [t (System/nanoTime)] (ask-socket! path (str i)) (ms-since t))) (range 200))]
      (println (format "   200 asks across processes:     %s" (stats rtts)))
      (ask-socket! path "stop")
      @proc)))

(defn- serve-until-stop! [path]
  (let [{:keys [stop! served]} (serve! path)]
    ;; the client's "stop" question is answered like any, then ends the server
    (loop [] (when-not (some #(= "stop" %) [(when (.exists (io/file (str path ".stop"))) "stop")])
               (Thread/sleep 50)
               (recur)))
    (stop!)))

(defn -main [& [mode & args]]
  (case mode
    "receiver" (let [[root poll] args] (receiver root (parse-long poll)))
    "mailbox" (measure-mailbox)
    "concurrent" (measure-concurrent)
    "idle" (measure-idle)
    "unix" (do (measure-unix-socket) (measure-unix-socket-across-processes))
    "unix-server" (serve-until-stop! (first args))
    (do (measure-mailbox) (measure-unix-socket) (measure-unix-socket-across-processes)))
  (shutdown-agents))
