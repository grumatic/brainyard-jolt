(ns brainyard.dev.nrepl-eval
  "A command-line nREPL client: code evaluated in a running image, with what
  it prints and returns shown as it arrives. The fallback for `clj-nrepl-eval`,
  which is a babashka script; this needs nothing but jolt.

      jolt -A:dev nrepl-server 7899                      # start one image, then:
      NREPL_PORT=7899 CODE='(+ 1 2)' jolt -A:dev -m brainyard.dev.nrepl-eval
      echo \"(require 'brainyard.dev) (brainyard.dev/suite)\" \\
        | NREPL_PORT=7899 jolt -A:dev -m brainyard.dev.nrepl-eval

  The code is CODE, or stdin when CODE is unset, so a form holding `!` never
  passes through the shell. Exits 1 when the code threw or the server stopped
  answering. Built on jolt-lang/nrepl's client (`nrepl.core`)."
  (:require [nrepl.core :as nrepl]
            [nrepl.transport :as transport]))

(def ^:private reply-wait-secs
  "How long one reply may take before the server is given up on: longer than
  the serial suite."
  1800)

(defn- session-of
  "A fresh session on `conn`, so the code runs with an *ns* of its own; nil
  when the server opens none, and the code runs without one."
  [conn]
  (try (nrepl/new-session conn) (catch Exception _ nil)))

(defn- show!
  "Print what one reply carries: output, error output, a value, an exception.
  True when it reports an exception."
  [{out "out" err "err" ex "ex" :as reply}]
  (when out (print out) (flush))
  (when err (binding [*out* *err*] (print err) (flush)))
  (when (contains? reply "value") (println "=>" (get reply "value")))
  (when ex (println "EX" ex (get reply "root-ex")))
  (some? ex))

(defn eval-and-print!
  "Evaluate `code` in the nREPL on `port`, printing each of its replies as it
  comes, until the server reports the eval finished. True when it finished
  and nothing threw."
  [port code]
  (let [conn (nrepl/connect "127.0.0.1" port {:recv-timeout-secs reply-wait-secs})
        id (str "nrepl-eval-" (System/currentTimeMillis))]
    (try
      (let [session (session-of conn)]
        (transport/send conn (merge {"op" "eval" "id" id "code" code}
                                    (when session {"session" session})))
        (loop [threw? false]
          (let [reply (transport/recv conn)]
            (cond
              (nil? reply)
              (binding [*out* *err*]
                (println "nrepl-eval: the server closed or went" reply-wait-secs "s without a reply")
                false)

              (not= id (get reply "id")) (recur threw?)

              :else
              (let [threw? (or (show! reply) threw?)]
                (if (some #{"done"} (get reply "status"))
                  (not threw?)
                  (recur threw?)))))))
      (finally
        (flush)
        (nrepl/close conn)))))

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "NREPL_PORT") "7899"))
        code (or (System/getenv "CODE") (slurp *in*))]
    (System/exit (if (eval-and-print! port code) 0 1))))
