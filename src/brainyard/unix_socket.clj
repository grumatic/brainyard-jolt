(ns brainyard.unix-socket
  "An AF_UNIX stream socket through libc (RFC-031) — the transport of
  brainyard's `ask.sock` (`components/ask-channel`).

  jolt's `java.nio` has no AF_UNIX (RFC-027), so the socket is reached through
  `jolt.ffi`: `jolt.http.net` already declares socket, connect, send, recv and
  poll, and a server needs bind, listen and accept beside them.

  brainyard's protocol, kept as it is: one `pr-str` EDN map per line; one
  request per connection, one reply, then the connection closes. The file is
  `0600`, and that mode is the whole trust model.

  Two rules the measurements behind RFC-031 set
  (`dev/brainyard/dev/handoff_spike.clj`):

  - the accept loop `poll`s the listening socket for 100 ms at a time and
    accepts only a connection that is waiting, so it looks at its stop flag ten
    times a second — a thread inside accept(2) cannot be interrupted;
  - `sockaddr_un` is laid out for macOS/BSD and Linux only. Another platform
    has no socket here, and `supported?` says so rather than guessing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [jolt.ffi :as ffi]
            [jolt.http.net :as net]
            [jolt.io-poller :as poller]))

(ffi/defcfn ^:private c-bind   "bind"   [:int :pointer :int] :int)
(ffi/defcfn ^:private c-listen "listen" [:int :int] :int)
(ffi/defcfn ^:private c-accept "accept" [:int :pointer :pointer] :int :blocking)
(ffi/defcfn ^:private c-poll   "poll"   [:pointer :int :int] :int :blocking)
(ffi/defcfn ^:private c-chmod  "chmod"  [:pointer :int] :int)
(ffi/defcfn ^:private c-shutdown "shutdown" [:int :int] :int)
(def ^:private shut-rdwr 2)

(def ^:private af-unix 1)
(def ^:private sock-stream 1)

(defn- sh [& args]
  (try (let [r (apply fx/sh {:out :string :err :string} args)]
         (when (zero? (:exit r)) (str/trim (:out r))))
       (catch Throwable _ nil)))

(def ^:private os (delay (sh "uname" "-s")))

(defn supported?
  "Is `sockaddr_un` laid out for this platform?"
  []
  (contains? #{"Darwin" "Linux"} @os))

(defn max-path-bytes
  "The longest path `sun_path` holds, less its terminating zero: 104 bytes on
  macOS, 108 on Linux."
  []
  (if (= "Linux" @os) 107 103))

(defn- sockaddr
  "`struct sockaddr_un` for `path`, and the length to pass with it. macOS/BSD:
  a length byte, a family byte, then the path. Linux: a two-byte family, then
  the path."
  [path]
  (let [bs (.getBytes (str path) "UTF-8")
        size 112
        a (ffi/alloc size)]
    (when (> (alength bs) (max-path-bytes))
      (ffi/free a)
      (throw (ex-info (str "socket path too long (" (alength bs) " bytes): " path) {:path path})))
    (dotimes [i size] (ffi/write a :uint8 0 i))
    (if (= "Linux" @os)
      (ffi/write a :uint16 af-unix 0)
      (do (ffi/write a :uint8 size 0) (ffi/write a :uint8 af-unix 1)))
    (dotimes [i (alength bs)] (ffi/write a :uint8 (bit-and 0xff (aget bs i)) (+ 2 i)))
    [a (+ 2 (alength bs) 1)]))

(defn- file-key
  "The device and inode at `path`, or nil: what makes a socket file OURS when
  it is time to remove it — brainyard's `should-unlink?`, so a closing process
  never deletes the socket of the one that replaced it."
  [path]
  (if (= "Linux" @os)
    (sh "stat" "-c" "%d:%i" (str path))
    (sh "stat" "-f" "%d:%i" (str path))))

;; --- lines ---------------------------------------------------------------------------

(defn- read-line!
  "Bytes from `fd` up to the first newline, decoded once whole — a character
  split across two reads is still one character. nil at end of stream with
  nothing read."
  [fd]
  (loop [acc (byte-array 0)]
    (let [nl (loop [i 0] (cond (>= i (alength acc)) nil
                               (= 10 (aget acc i)) i
                               :else (recur (inc i))))]
      (if nl
        (String. (java.util.Arrays/copyOfRange acc 0 nl) "UTF-8")
        (if-let [b (net/recv-bytes fd)]
          (let [joined (byte-array (+ (alength acc) (alength b)))]
            (System/arraycopy acc 0 joined 0 (alength acc))
            (System/arraycopy b 0 joined (alength acc) (alength b))
            (recur joined))
          (when (pos? (alength acc)) (String. acc "UTF-8")))))))

(defn- write-msg! [fd msg]
  (net/send-bytes fd (.getBytes (str (pr-str msg) "\n") "UTF-8")))

(defn read-msg
  "One EDN map from a line, or nil for an empty or unreadable one."
  [line]
  (when-not (str/blank? line)
    (try (let [m (edn/read-string line)] (when (map? m) m))
         (catch Throwable _ nil))))

;; --- the client ----------------------------------------------------------------------

(defn request!
  "Send `msg` to the socket at `path` and return its one reply. Waits up to
  `timeout-ms` for it. brainyard's client: a connection that cannot be made
  throws, and one that closes without answering is
  `{:status :error :error \"no response from session\"}`."
  [path msg & [timeout-ms]]
  (let [fd (net/c-socket af-unix sock-stream 0)
        [addr len] (sockaddr path)]
    (try
      (when (neg? (net/c-connect fd addr len))
        (throw (ex-info (str "could not reach session at " path " (errno " (poller/errno) ")")
                        {:path path :errno (poller/errno)})))
      (when timeout-ms (net/set-read-timeout! fd timeout-ms))
      (write-msg! fd msg)
      (or (read-msg (read-line! fd))
          {:status :error :error "no response from session"})
      (finally (ffi/free addr) (net/close fd)))))

(defn stream!
  "Send `msg` and read the stream it opens: returns the ack, and calls
  `(on-frame m)` for each frame after it on a thread of its own until the
  server closes the stream or `stop!` is called. Returns `{:ack :stop! :done}`,
  `:done` a promise delivered when the stream has ended."
  [path msg on-frame]
  (let [fd (net/c-socket af-unix sock-stream 0)
        [addr len] (sockaddr path)]
    (when (neg? (try (net/c-connect fd addr len) (finally (ffi/free addr))))
      (net/close fd)
      (throw (ex-info (str "could not reach session at " path) {:path path})))
    (write-msg! fd msg)
    (let [stopped? (atom false)
          ;; the descriptor is closed once, by the reader, under this lock:
          ;; stop! closing it too was a second close that, once the number had
          ;; been handed to the next socket, closed that one instead — and a
          ;; reader woken by it could recv on the new owner (byj-gly.259)
          lock (Object.)
          closed? (atom false)
          done (promise)
          pending (atom (byte-array 0))
          ;; frames arrive back to back: read line by line out of one buffer
          next-line! (fn []
                       (loop []
                         (let [acc @pending
                               nl (loop [i 0] (cond (>= i (alength acc)) nil
                                                    (= 10 (aget acc i)) i
                                                    :else (recur (inc i))))]
                           (if nl
                             (do (reset! pending (java.util.Arrays/copyOfRange acc (inc nl) (alength acc)))
                                 (String. (java.util.Arrays/copyOfRange acc 0 nl) "UTF-8"))
                             (when-let [b (try (net/recv-bytes fd) (catch Throwable _ nil))]
                               (let [joined (byte-array (+ (alength acc) (alength b)))]
                                 (System/arraycopy acc 0 joined 0 (alength acc))
                                 (System/arraycopy b 0 joined (alength acc) (alength b))
                                 (reset! pending joined)
                                 (recur)))))))
          ack (read-msg (next-line!))]
      (fx/run-detached
       (fx/task-of
        (fn []
          (try
            (loop []
              (when-not @stopped?
                (when-let [line (next-line!)]
                  (when-let [m (read-msg line)] (on-frame m))
                  (recur))))
            (finally (locking lock (reset! closed? true) (net/close fd))
                     (deliver done true)))))
       ::stream-reader)
      {:ack ack :done done
       ;; shutdown, not close: it wakes the reader's recv with end-of-stream
       ;; and leaves the descriptor the reader's to close
       :stop! (fn [] (reset! stopped? true)
                (locking lock (when-not @closed? (c-shutdown fd shut-rdwr)))
                nil)})))

(defn live-owner?
  "Does a process answer at `path`? brainyard's probe before a bind: a bare
  connection, no request."
  [path]
  (let [fd (net/c-socket af-unix sock-stream 0)
        [addr len] (sockaddr path)]
    (try (not (neg? (net/c-connect fd addr len)))
         (finally (ffi/free addr) (net/close fd)))))

;; --- the server ----------------------------------------------------------------------

(defn- readable? [fd timeout-ms]
  (let [pf (ffi/alloc 8)]
    (try
      (dotimes [i 8] (ffi/write pf :uint8 0 i))
      (ffi/write pf :int fd 0)
      (ffi/write pf :short 1 4)                  ; POLLIN
      (pos? (c-poll pf 1 timeout-ms))
      (finally (ffi/free pf)))))

(defn- no-sigpipe!
  "Writing to a peer that has gone away raises SIGPIPE, whose default is to end
  the process — a session killed by a subscriber closing its terminal. On
  macOS the socket can be told not to (SO_NOSIGPIPE)."
  [fd]
  (when (= "Darwin" @os)
    (let [one (ffi/alloc 4)]
      (try (ffi/write one :int 1 0)
           (net/c-setsockopt fd 0xffff 0x1022 one 4)
           (finally (ffi/free one))))))

(defn open?
  "Is the peer at `fd` still there? A peer that closed makes the socket
  readable with nothing to read."
  [fd]
  (try (not (and (readable? fd 0) (nil? (net/recv-bytes fd))))
       (catch Throwable _ false)))

(defn stream
  "A reply that keeps the connection open: `ack` is written first, then
  `(f write! open?)` runs on the connection's own thread, writing frames with
  `write!` for as long as it likes — brainyard's `stream-response`."
  [ack f]
  (assoc ack ::stream f))

(defn- serve-connection!
  "One request, one reply, whatever happens: brainyard's `handle-connection!`,
  which never lets a failure reach the accept loop. A `stream` reply writes its
  ack and then its frames."
  [fd handler]
  (try
    (no-sigpipe! fd)
    (let [msg (read-msg (read-line! fd))
          reply (if (nil? msg)
                  {:status :error :error "empty request"}
                  (try (handler msg)
                       (catch Throwable e
                         {:status :error :error (str "server error: " (or (ex-message e) (str e)))})))]
      (if-let [f (::stream reply)]
        (do (write-msg! fd (dissoc reply ::stream))
            (f #(write-msg! fd %) #(open? fd)))
        (write-msg! fd reply)))
    (catch Throwable _ nil)
    (finally (net/close fd))))

(defn serve!
  "Listen at `path` and answer each request with `(handler msg)`, each
  connection on its own blocking task so a slow answer never holds the next
  one up. Returns `{:path :stop!}`.

  Refused, `ex-info` `{:reason :live-owner}`, when a process already answers
  at `path`; a file nothing answers at is stale and is replaced."
  [path handler]
  (when-not (supported?)
    (throw (ex-info (str "no unix sockets on " @os) {:reason :unsupported})))
  (when (.exists (io/file path))
    (if (live-owner? path)
      (throw (ex-info (str "a live process owns " path) {:reason :live-owner :path path}))
      (io/delete-file path true)))
  (.mkdirs (.getParentFile (io/file path)))
  (let [fd (net/c-socket af-unix sock-stream 0)
        [addr len] (sockaddr path)
        bound (try (c-bind fd addr len) (finally (ffi/free addr)))]
    (when (neg? bound)
      (net/close fd)
      (throw (ex-info (str "could not bind " path " (errno " (poller/errno) ")") {:path path})))
    (let [p (ffi/string->ptr (str path))]
      (try (c-chmod p 384) (finally (ffi/free p))))          ; 0600
    (c-listen fd 16)
    (let [owned (file-key path)
          stop? (atom false)
          stopped (promise)]
      (fx/run-detached
       (fx/task-of
        (fn []
          (try
            (loop []
              (when-not @stop?
                (when (readable? fd 100)
                  (let [c (c-accept fd 0 0)]
                    (when-not (neg? c)
                      (fx/run-detached (fx/task-of #(serve-connection! c handler)) ::connection))))
                (recur)))
            (finally (deliver stopped true)))))
       ::accept-loop)
      {:path path
       :stop! (fn []
                (reset! stop? true)
                (deref stopped 2000 nil)
                (net/close fd)
                (when (and owned (= owned (file-key path)))
                  (io/delete-file path true))
                nil)})))
