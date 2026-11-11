(ns brainyard.dev.sdk-stdio-spike
  "Measurements behind RFC-053: can byj be an ACP agent on its own stdio?
  (byj-g5n.1)

      jolt -A:dev -m brainyard.dev.sdk-stdio-spike            # drive: spawns serve, measures
      jolt -A:dev -m brainyard.dev.sdk-stdio-spike serve      # the agent side alone

  The SDK's transport is the process's stdin and stdout, as the Claude Agent
  SDK's is `claude`'s. Nothing in byj reads its own stdin on one thread while
  another writes stdout and a turn runs (the terminal session polls; the
  headless host reads nothing), and divergence `a-piped-session-has-no-channel`
  says a poll cannot tell the end of a pipe from a pause. So, measured across
  processes, the agent a second jolt process as in real use:

  1. A round trip: `ping` answered, 2000 times, sequentially.
  2. A turn: 200 notifications written from the turn's thread while the
     reader takes requests; in its middle the agent asks the client (a
     permission request) and parks until the answer, routed back by id.
  3. A cancel mid-turn, as a notification, reaching the turn's thread.
  4. A pause: nothing written for 2 s, and the agent still there.
  5. EOF: stdin closed, while a turn is parked on a permission request the
     client never answers — the agent must cancel it and exit.
  6. Startup: spawn to the first answer.

  The reader is fd 0 through libc `read(2)` (as `line-input` reads the
  terminal), 64 KiB at a time, split on the byte 0x0A; `read` returning 0 is
  the end of input, which no poll is asked to guess. Prints raw numbers."
  (:require [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [jolt.ffi :as ffi]))

;; ---------------------------------------------------------------------------
;; The agent side
;; ---------------------------------------------------------------------------

(ffi/defcfn ^:private c-read "read" [:int :pointer :int] :int :blocking)

(defn read-lines!
  "Read fd 0 until its end, calling `on-line` with each line (a string, the
  newline dropped). Blocks: run it on blk. Returns at EOF."
  [on-line]
  (let [cap 65536
        buf (ffi/alloc cap)
        pending (java.io.ByteArrayOutputStream.)]
    (try
      (loop []
        (let [n (c-read 0 buf cap)]
          (when (pos? n)
            (dotimes [i n]
              (let [b (ffi/read buf :uint8 i)]
                (if (= b 10)
                  (let [s (String. (.toByteArray pending) "UTF-8")]
                    (.reset pending)
                    (on-line s))
                  (.write pending (int b)))))
            (recur))))
      (finally (ffi/free buf)))))

(def ^:private out-lock (Object.))

(defn- send! [msg]
  (let [s (str (json/write-str msg) "\n")]
    (locking out-lock
      (print s)
      (flush))))

(defn serve
  "A JSON-RPC agent on stdio: `ping`, `turn {n ms ask?}`, `cancel` (a
  notification). It asks the client `permission` mid-turn when `ask?`."
  []
  (let [pending (atom {})                 ; our request id → promise
        next-id (atom 1000)
        turns (atom {})                   ; request id → canceller
        ask! (fn [method params]
               (let [id (swap! next-id inc) p (promise)]
                 (swap! pending assoc id p)
                 (send! {:jsonrpc "2.0" :id id :method method :params params})
                 p))
        turn (fn [id {:keys [n ms ask]}]
               (fx/via-blk
                (dotimes [i n]
                  (send! {:jsonrpc "2.0" :method "session/update"
                          :params {:i i :text (str "chunk " i " ✓ 한글")}})
                  (when (and ask (= i (quot n 2)))
                    (let [answer @(ask! "session/request_permission" {:tool "bash"})]
                      (send! {:jsonrpc "2.0" :method "session/update" :params {:permission answer}})))
                  (when (pos? (or ms 0)) (Thread/sleep (long ms))))
                {:stopReason "end_turn" :n n}))
        handle (fn [line]
                 (let [msg (try (json/read-str line) (catch Throwable _ nil))
                       {:keys [id method params]} msg]
                   (cond
                     (nil? msg) (binding [*out* *err*] (println "unparseable:" line))
                     ;; an answer to one of ours
                     (and id (nil? method)) (when-let [p (get @pending id)]
                                              (swap! pending dissoc id)
                                              (deliver p (or (:result msg) (:error msg))))
                     (= method "ping") (send! {:jsonrpc "2.0" :id id :result {:pong (:t params)}})
                     (= method "turn")
                     (let [cancel (fx/run (turn id params)
                                          (fn [r] (swap! turns dissoc id)
                                            (send! {:jsonrpc "2.0" :id id :result r}))
                                          (fn [e] (swap! turns dissoc id)
                                            (send! {:jsonrpc "2.0" :id id
                                                    :result {:stopReason (if (fx/cancelled? e) "cancelled" "error")
                                                             :error (ex-message e)}})))]
                       (swap! turns assoc id cancel))
                     (= method "cancel") (doseq [[_ c] @turns] (c))
                     :else (send! {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}}))))]
    (read-lines! handle)
    ;; EOF: the client is gone — cancel every turn, answer every parked ask
    (binding [*out* *err*] (println "eof: cancelling" (count @turns) "turn(s)," (count @pending) "parked ask(s)"))
    (doseq [[_ p] @pending] (deliver p {:outcome "cancelled"}))
    (doseq [[_ c] @turns] (c))
    (Thread/sleep 100)
    (binding [*out* *err*] (println "eof: exit"))))

;; ---------------------------------------------------------------------------
;; The client side
;; ---------------------------------------------------------------------------

(defn- spawn []
  (fx/start {:in :stream :out :stream :err :string}
            "jolt" "-A:dev" "-m" "brainyard.dev.sdk-stdio-spike" "serve"))

(defn- reader
  "Lines from the process's stdout, raw bytes split on 0x0A, onto `on-msg`."
  [^java.io.InputStream is on-msg]
  (fx/run-detached
   (fx/via-blk
    (let [buf (byte-array 65536) acc (java.io.ByteArrayOutputStream.)]
      (loop []
        (let [n (.read is buf)]
          (when (pos? n)
            (dotimes [i n]
              (let [b (aget buf i)]
                (if (= b 10)
                  (let [s (String. (.toByteArray acc) "UTF-8")]
                    (.reset acc)
                    (on-msg (json/read-str s)))
                  (.write acc (int b)))))
            (recur))))
      (on-msg ::eof)))))

(defn- writer [proc]
  (let [^java.io.OutputStream os (:in proc) lock (Object.)]
    (fn [msg]
      (locking lock
        (.write os (.getBytes (str (json/write-str msg) "\n") "UTF-8"))
        (.flush os)))))

(defn- pct [xs q] (let [v (vec (sort xs))] (nth v (min (dec (count v)) (int (* q (count v)))))))

(defn drive []
  (let [t0 (System/nanoTime)
        proc (spawn)
        answers (atom {})               ; id → promise
        notes (atom [])
        asks (atom [])                  ; requests the agent sent us
        eof (promise)
        answer-asks? (atom true)
        send (writer proc)
        call (fn [id method params]
               (let [p (promise)] (swap! answers assoc id p)
                 (send {:jsonrpc "2.0" :id id :method method :params params}) p))]
    (reader (:out proc)
            (fn [m]
              (cond
                (= m ::eof) (deliver eof (System/nanoTime))
                (and (:id m) (:method m)) (do (swap! asks conj m)
                                              (when @answer-asks?
                                                (send {:jsonrpc "2.0" :id (:id m)
                                                       :result {:outcome {:outcome "selected" :optionId "allow-once"}}})))
                (:id m) (when-let [p (get @answers (:id m))] (deliver p m))
                :else (swap! notes conj m))))
    ;; 6. startup
    @(call 1 "ping" {:t 0})
    (println (format "startup: spawn to first answer %.0f ms" (/ (- (System/nanoTime) t0) 1e6)))
    ;; 1. round trips
    (let [rts (vec (for [i (range 2000)]
                     (let [s (System/nanoTime)]
                       @(call (+ 10 i) "ping" {:t i})
                       (/ (- (System/nanoTime) s) 1e3))))]
      (println (format "round trip, 2000 pings: p50 %.0f µs  p90 %.0f µs  p99 %.0f µs  max %.0f µs"
                       (pct rts 0.5) (pct rts 0.9) (pct rts 0.99) (apply max rts))))
    ;; 2. a turn with a permission ask in its middle, pings interleaved
    (reset! notes [])
    (let [s (System/nanoTime)
          p (call 5000 "turn" {:n 200 :ms 1 :ask true})
          mid (vec (for [i (range 20)] (let [s (System/nanoTime)] @(call (+ 6000 i) "ping" {:t i}) (/ (- (System/nanoTime) s) 1e3))))
          r @p
          texts (keep #(get-in % [:params :text]) @notes)]
      (println (format "turn: %d updates, %d intact (UTF-8 kept), the ask answered: %s, result %s in %.0f ms; pings during it p50 %.0f µs"
                       (count texts)
                       (count (filter #(str/ends-with? % "✓ 한글") texts))
                       (pr-str (some #(get-in % [:params :permission]) @notes))
                       (get-in r [:result :stopReason])
                       (/ (- (System/nanoTime) s) 1e6) (pct mid 0.5))))
    ;; 3. a cancel mid-turn
    (let [p (call 7000 "turn" {:n 100000 :ms 1})
          _ (Thread/sleep 300)
          s (System/nanoTime)]
      (send {:jsonrpc "2.0" :method "cancel" :params {}})
      (let [r (deref p 5000 ::none)]
        (println (format "cancel: the turn answered %s %.0f ms after the cancel was sent"
                         (if (= r ::none) "NOTHING" (get-in r [:result :stopReason]))
                         (/ (- (System/nanoTime) s) 1e6)))))
    ;; 4. a pause
    (Thread/sleep 2000)
    (let [r (deref (call 8000 "ping" {:t 1}) 2000 ::none)]
      (println "after a 2 s pause the agent answers:" (not= r ::none)))
    ;; 5. EOF with a turn parked on an ask the client never answers
    (reset! answer-asks? false)
    (reset! asks [])
    (call 9000 "turn" {:n 10 :ms 0 :ask true})
    (Thread/sleep 300)
    (println "parked on an unanswered ask:" (count @asks))
    (let [s (System/nanoTime)]
      (.close ^java.io.OutputStream (:in proc))
      (let [e (deref eof 10000 nil)
            exit (deref proc 10000 ::none)]
        (println (format "EOF: stdout closed %s ms after stdin, exit %s; agent's stderr: %s"
                         (if e (format "%.0f" (/ (- e s) 1e6)) "NEVER")
                         (if (= exit ::none) "NONE (still running)" (:exit exit))
                         (pr-str (str/trim (str (:err exit))))))))
    (shutdown-agents)))

;; ---------------------------------------------------------------------------
;; ACP-shaped: what the official client sends and accepts
;; ---------------------------------------------------------------------------

(defn serve-acp
  "The least ACP v1 agent the official TypeScript client completes a prompt
  with: initialize, session/new, session/prompt answered with an
  agent_message_chunk, a session/request_permission and a `_byj/tool/call`
  extension request in its middle, then end_turn. Driven by
  `dev/sdk-acp-spike/drive.mjs` (byj-g5n.1 (c))."
  []
  (let [pending (atom {}) next-id (atom 1000)
        ask! (fn [method params]
               (let [id (swap! next-id inc) p (promise)]
                 (swap! pending assoc id p)
                 (send! {:jsonrpc "2.0" :id id :method method :params params})
                 p))
        update! (fn [sid u] (send! {:jsonrpc "2.0" :method "session/update" :params {:sessionId sid :update u}}))
        prompt (fn [id {:keys [sessionId prompt]}]
                 (fx/run-detached
                  (fx/via-blk
                   (update! sessionId {:sessionUpdate "agent_message_chunk"
                                       :content {:type "text" :text (str "you said: " (:text (first prompt)))}})
                   (update! sessionId {:sessionUpdate "tool_call" :toolCallId "c1" :title "bash ls" :kind "execute" :status "pending"
                                       :rawInput {:command "ls"}})
                   (let [perm @(ask! "session/request_permission"
                                     {:sessionId sessionId :toolCall {:toolCallId "c1" :title "bash ls"}
                                      :options [{:optionId "allow-once" :name "Allow once" :kind "allow_once"}
                                                {:optionId "reject-once" :name "Reject" :kind "reject_once"}]})
                         tool @(ask! "_byj/tool/call" {:sessionId sessionId :tool "order_lookup" :args {:id "1042"}})]
                     (update! sessionId {:sessionUpdate "tool_call_update" :toolCallId "c1" :status "completed"
                                         :content [{:type "content" :content {:type "text" :text (pr-str perm)}}]})
                     (update! sessionId {:sessionUpdate "agent_message_chunk"
                                         :content {:type "text" :text (str " | tool said: " (pr-str tool))}})
                     (send! {:jsonrpc "2.0" :id id :result {:stopReason "end_turn"
                                                            :_meta {:brainyard {:answer "done" :usage {:input 1 :output 2}}}}})))))]
    (read-lines!
     (fn [line]
       (let [{:keys [id method params] :as msg} (json/read-str line)]
         (binding [*out* *err*] (println "<-" line))
         (cond
           (and id (nil? method)) (when-let [p (get @pending id)]
                                    (swap! pending dissoc id)
                                    (deliver p (or (:result msg) (:error msg))))
           (= method "initialize")
           (send! {:jsonrpc "2.0" :id id
                   :result {:protocolVersion 1
                            :agentCapabilities {:loadSession false
                                                :promptCapabilities {:image false :audio false :embeddedContext true}
                                                :_meta {:brainyard {:protocol 1 :extensions ["tools" "hooks" "events" "predict"]}}}
                            :agentInfo {:name "byj" :version "spike"}
                            :authMethods []}})
           (= method "session/new") (send! {:jsonrpc "2.0" :id id :result {:sessionId "s-1"}})
           (= method "session/prompt") (prompt id params)
           (= method "session/cancel") nil
           :else (send! {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})))))))

(defn -main [& [mode]]
  (case mode
    "serve" (serve)
    "acp" (serve-acp)
    (drive)))
