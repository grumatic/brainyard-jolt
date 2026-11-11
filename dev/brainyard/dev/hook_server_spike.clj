(ns brainyard.dev.hook-server-spike
  "byj-fim: what `byj hook pretooluse` costs when byj stays up and a thin
  client asks it, against what it costs as a process of its own (RFC-012
  \"Another agent's calls\").

  `serve` listens on a unix socket (`brainyard.unix-socket`, the session
  channel's transport) and answers each request exactly as `byj hook
  pretooluse` does — the config of the payload's `cwd` loaded per request,
  then `tool-hook/hook!` — so the gates stay the one source of the decision.
  A request is `{:json \"<the PreToolUse payload>\" :log DIR}`, the reply
  `{:out \"<what the hook prints>\" :ms N}`, `:ms` the time spent inside.

  Run it as its own process and time clients against it from a shell;
  `dev/hook-latency.sh --resident` does both, and without the flag times the
  same calls a process each:

    jolt -A:dev -m brainyard.dev.hook-server-spike SOCKET

  The measurements are in RFC-012 \"Another agent's calls\" (byj-fim)."
  (:require [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.tool-hook :as tool-hook]
            [brainyard.unix-socket :as us]))

(defn answer
  "What `byj hook pretooluse` would print for `json`, logging in `log`, and
  the milliseconds it took."
  [{:keys [json log]}]
  (let [t0 (System/nanoTime)
        payload (try (json/read-str (str json)) (catch Throwable _ nil))
        out (when (map? payload)
              (let [wd (or (not-empty (str (:cwd payload))) ".")
                    cfg (try (:config (config/load-config {:working-dir wd}))
                             (catch Throwable _ {:working-dir wd}))]
                (tool-hook/hook! cfg payload (tool-hook/log-dir log (System/getenv "HOME")))))]
    {:out out :ms (/ (- (System/nanoTime) t0) 1e6)}))

(defn -main [path & _]
  (us/serve! path answer)
  (println "serving" path)
  (flush)
  @(promise))
