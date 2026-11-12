;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.hook-serve
  "`byj hook serve`: byj kept up to answer Claude Code's and Codex's
  `PreToolUse` calls, so a call no longer pays byj's start (RFC-012 \"Another
  agent's calls\", byj-ywp; measured in byj-fim: 0.68 s a call as a process
  of its own, ~1 ms inside a byj that is up).

  - **The server** listens on a `0600` unix socket (`~/.brainyard/hook.sock`)
    and speaks just enough HTTP for `curl --unix-socket`: `POST
    /v1/pretooluse` with the payload as its body answers what `byj hook
    pretooluse` would print, by the same `answer` fn — the payload's
    project's config loaded per request, then `tool-hook/hook!` — so the
    gates stay the one source of the decision and the log is the same.
    `GET /v1/ping` says it is there. It exits after `idle-ms` with no
    request, and when the byj its command names changes on disk (an upgrade,
    `byj update` or `brew upgrade`): a request that finds it so is answered
    503, which sends the client to the fallback, the new byj.
  - **The client** is the command the agent runs (`client-command`): the
    payload read once, put to the socket by curl, and, when nothing answers
    there or curl is missing, given to `byj hook pretooluse --socket SOCK`,
    which answers it as it always has and then starts the server for the
    next call (`start!`). The agent sees the same command contract either
    way: the payload on stdin, the decision on stdout, exit 0."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.unix-socket :as us]
            [jolt.ffi :as ffi]
            [jolt.http.net :as net]))

(def default-idle-ms
  "How long the server waits for a request before it exits: long enough for
  a person's pause between turns, short enough that an image of byj does not
  stay up all day for nothing."
  (* 20 60 1000))

(def ^:private watch-ms 2000)

(def ^:private max-head-bytes (* 64 1024))
(def ^:private max-body-bytes (* 64 1024 1024))

(defn default-socket
  "The server's socket under `home`: one per person, whichever agent asks."
  [home]
  (str (io/file (str home) ".brainyard" "hook.sock")))

;; --- the client: the command the agent runs -------------------------------------------

(defn- sq
  "`s` quoted for a POSIX shell."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn client-command
  "The `PreToolUse` command that asks the server at `socket` and falls back
  to `self hook pretooluse` — which starts the server — when nothing answers
  there: one `sh -c` line, so it means the same to an agent that runs it
  through a shell (Claude Code) and to one that splits it into words. The
  payload is read once and given to whichever answers. curl gives up after
  25 s, under Codex's 30 s hook timeout, so a server that hangs still leaves
  the call to the fallback; `Expect:` keeps curl from waiting on a `100
  Continue` before a large payload. `log` is where decisions are logged, as
  `--log` gives it, sent to the server as `Byj-Log`."
  [self log socket]
  (let [log (not-empty (str log))
        script (str "p=$(cat); "
                    "printf %s \"$p\" | curl -sf --connect-timeout 1 --max-time 25 -H 'Expect:' "
                    (when log (str "-H " (sq (str "Byj-Log: " log)) " "))
                    "--unix-socket " (sq socket) " --data-binary @- http://byj/v1/pretooluse 2>/dev/null"
                    " || printf %s \"$p\" | " (sq self) " hook pretooluse --socket " (sq socket)
                    (when log (str " --log " (sq log))))]
    (str "sh -c " (sq script))))

;; --- the byj the command names -----------------------------------------------------------

(defn resolve-self
  "The file `self` runs: itself when it names a directory, else the first
  executable of that name on `path-env` (a `PATH`), as the shell finds it."
  [self path-env]
  (let [self (str self)]
    (if (str/includes? self "/")
      (let [f (io/file self)] (when (.isFile f) f))
      (some (fn [dir]
              (let [f (io/file dir self)]
                (when (and (not (str/blank? dir)) (.isFile f) (.canExecute f)) f)))
            (str/split (str path-env) #":")))))

(defn fingerprint
  "What changes when `self` is upgraded: the file it resolves to, followed
  through links (Homebrew moves its link to the new version's directory),
  and that file's time and size (`byj update` puts a new file at the same
  path). nil when `self` resolves to nothing."
  [self path-env]
  (when-let [f (resolve-self self path-env)]
    (let [c (io/file (.getCanonicalPath f))]
      [(str c) (.lastModified c) (.length c)])))

;; --- HTTP, as much as curl needs ---------------------------------------------------------

(defn- index-of-blank-line
  "Where `\\r\\n\\r\\n` starts in `bs`, or nil."
  [^bytes bs]
  (loop [i 0]
    (cond (> (+ i 4) (alength bs)) nil
          (and (= 13 (aget bs i)) (= 10 (aget bs (+ i 1))) (= 13 (aget bs (+ i 2))) (= 10 (aget bs (+ i 3)))) i
          :else (recur (inc i)))))

(defn- join-bytes [^bytes a ^bytes b]
  (let [j (byte-array (+ (alength a) (alength b)))]
    (System/arraycopy a 0 j 0 (alength a))
    (System/arraycopy b 0 j (alength a) (alength b))
    j))

(defn parse-head
  "A request's head — its request line and headers, as text — as
  `{:method :path :headers}`, header names lower-cased; nil when it is not
  one."
  [head]
  (let [[line & hs] (str/split (str head) #"\r\n")
        [method path] (str/split (str line) #" ")]
    (when (and (not (str/blank? method)) (str/starts-with? (str path) "/"))
      {:method method
       :path (first (str/split path #"\?"))
       :headers (into {} (keep (fn [h]
                                 (when-let [i (str/index-of h ":")]
                                   [(str/lower-case (str/trim (subs h 0 i))) (str/trim (subs h (inc i)))])))
                      hs)})))

(defn- write-response! [fd status body]
  (let [reason ({200 "OK" 400 "Bad Request" 404 "Not Found" 411 "Length Required"
                 413 "Payload Too Large" 503 "Service Unavailable"} status "Error")
        b (.getBytes (str body) "UTF-8")]
    (net/send-bytes fd (.getBytes (str "HTTP/1.1 " status " " reason "\r\n"
                                       "Content-Type: application/json\r\n"
                                       "Content-Length: " (alength b) "\r\n"
                                       "Connection: close\r\n\r\n")
                                  "UTF-8"))
    (when (pos? (alength b)) (net/send-bytes fd b))))

(defn- read-request
  "One request from `fd`: `{:method :path :headers :body}`, or `{:status N}`
  for one to refuse, or nil when the peer sent nothing. A `100-continue` is
  answered before the body is read."
  [fd]
  (loop [acc (byte-array 0)]
    (if-let [i (index-of-blank-line acc)]
      (if-let [req (parse-head (String. (java.util.Arrays/copyOfRange acc 0 i) "UTF-8"))]
        (let [n (some-> (get-in req [:headers "content-length"]) parse-long)
              rest-bs (java.util.Arrays/copyOfRange acc (+ i 4) (alength acc))]
          (cond
            (= "GET" (:method req)) (assoc req :body "")
            (nil? n) {:status 411}
            (> n max-body-bytes) {:status 413}
            :else
            (do (when (and (< (alength rest-bs) n)
                           (= "100-continue" (some-> (get-in req [:headers "expect"]) str/lower-case)))
                  (net/send-bytes fd (.getBytes "HTTP/1.1 100 Continue\r\n\r\n" "UTF-8")))
                (loop [body rest-bs]
                  (if (>= (alength body) n)
                    (assoc req :body (String. (java.util.Arrays/copyOfRange body 0 n) "UTF-8"))
                    (if-let [b (net/recv-bytes fd)]
                      (recur (join-bytes body b))
                      {:status 400}))))))
        {:status 400})
      (cond (> (alength acc) max-head-bytes) {:status 413}
            :else (if-let [b (net/recv-bytes fd)]
                    (recur (join-bytes acc b))
                    (when (pos? (alength acc)) {:status 400}))))))

;; --- the server ----------------------------------------------------------------------------

(ffi/defcfn ^:private c-setsid "setsid" [] :int)

(defn detach-session!
  "Make this process the leader of a session of its own, so the terminal,
  or the agent, that started it can end without ending it."
  []
  (try (c-setsid) (catch Throwable _ nil))
  nil)

(defn serve!
  "Answer `PreToolUse` calls at `socket` with `(answer payload-json log)` —
  what the hook prints, or nil — until idle for `idle-ms` or `self` changes
  on disk (`fingerprint`, checked every `watch-ms`, against `path-env`).
  Returns `{:stop! :done :why}`: `:done` a promise delivered with why it
  stopped (`:idle`, `:upgraded`, `:stopped`). Refused as `us/listen!` is
  when a server already answers there. `:fingerprint` replaces how `self` is
  looked at, for a test."
  [{:keys [socket answer idle-ms self path-env] :as opts}]
  (let [fp (or (:fingerprint opts) #(fingerprint self path-env))
        idle-ms (or idle-ms default-idle-ms)
        every (or (:watch-ms opts) watch-ms)
        born (fp)
        last-at (atom (System/currentTimeMillis))
        inflight (atom 0)
        stale? (atom false)
        done (promise)
        listener (atom nil)
        stop! (fn [why]
                (when-not (realized? done)
                  (when-let [l @listener] ((:stop! l)))
                  (deliver done why))
                nil)
        on-connection
        (fn [fd]
          (try
            (net/set-read-timeout! fd 10000)
            (let [req (read-request fd)]
              (reset! last-at (System/currentTimeMillis))
              (cond
                (nil? req) nil
                (:status req) (write-response! fd (:status req) "")
                (and (= "GET" (:method req)) (= "/v1/ping" (:path req))) (write-response! fd 200 "byj hook serve\n")
                (and (= "POST" (:method req)) (= "/v1/pretooluse" (:path req)))
                (if (or @stale? (not= born (fp)))
                  ;; the client's fallback is the new byj, which starts a
                  ;; server of its own once this one has gone
                  (do (reset! stale? true) (write-response! fd 503 ""))
                  (do (swap! inflight inc)
                      (try (let [out (answer (:body req) (get-in req [:headers "byj-log"]))]
                             (write-response! fd 200 (if out (str out "\n") "")))
                           (finally (swap! inflight dec)
                                    (reset! last-at (System/currentTimeMillis))))))
                :else (write-response! fd 404 "")))
            (catch Throwable _ nil)
            (finally (net/close fd))))]
    (reset! listener (us/listen! socket on-connection))
    (fx/run-detached
     (fx/task-of
      (fn []
        (loop []
          (when-not (realized? done)
            (Thread/sleep (long every))
            (when (and (not @stale?) (not= born (fp))) (reset! stale? true))
            (when (zero? @inflight)
              (cond @stale? (stop! :upgraded)
                    (> (- (System/currentTimeMillis) @last-at) idle-ms) (stop! :idle)))
            (recur)))))
     ::watch)
    {:stop! #(stop! :stopped) :done done}))

(defn start!
  "Start `self hook serve --socket SOCKET` on its own, its input and output
  closed, so the hook call that starts it returns at once and the agent does
  not wait on the server's output; nothing when one already answers there."
  [self socket]
  (when (and (us/supported?) (not (try (us/live-owner? socket) (catch Throwable _ false))))
    (try (fx/sh {:out :string :err :string} "sh" "-c"
                "nohup \"$0\" hook serve --socket \"$1\" </dev/null >/dev/null 2>&1 &"
                (str self) (str socket))
         (catch Throwable _ nil)))
  nil)
