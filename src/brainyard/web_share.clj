;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.web-share
  "Share a session in the browser by wrapping `byj run` in ttyd
  (https://github.com/tsl0922/ttyd) — brainyard's `components/web-share`
  (`docs/web-sharing.md`), the one web feature this port has (RFC-027/028,
  byj-gly.22). ttyd runs the child in a real PTY and bridges it to the browser
  over a WebSocket, so the full-screen renderer works there unchanged: this is
  a launcher, and the kernel and renderer know nothing of it.

  Two tiers, as brainyard's:
  - `--web`: ttyd runs a fresh session FOR EACH VIEWER — it starts its
    command once per connection. brainyard's docs/web-sharing.md says the
    session is shared; measured 2026-10-09, two viewers had two sessions.
  - `--web-tmux`: one session, shared. It runs in a private tmux server
    (`-L by-web-<id>`); every viewer's ttyd connection and the launching
    terminal's own `tmux attach` drive one live pane, and the launcher stays a
    dashboard.

  Auth is always on — a password is generated when none is given — ttyd's
  origin check always set, the bind 127.0.0.1 unless asked otherwise. A
  viewer may type unless `--web-readonly` (writable by default, as brainyard's
  and as decided with the person 2026-10-09). The child carries
  `BY_WEB_CHILD=1`, so it runs the session rather than sharing again."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.init :as init]
            [jolt.process :as p]))

;; --- what the person asked: flags over BY_WEB_* over defaults ------------------------------

(def flags
  "The `--web*` flags: name → `[key takes-value?]`, brainyard's names."
  {"--web" [:web false] "--web-tmux" [:web-tmux false] "--web-port" [:web-port true]
   "--web-bind" [:web-bind true] "--web-user" [:web-user true] "--web-pass" [:web-pass true]
   "--web-readonly" [:web-readonly false] "--web-max-clients" [:web-max-clients true]
   "--web-once" [:web-once false]})

(defn strip-flags
  "`args` without the `--web*` flags and their values: what the child runs with."
  [args]
  (loop [[a & more] args out []]
    (cond (nil? a) out
          (contains? flags a) (recur (if (second (flags a)) (rest more) more) out)
          :else (recur more (conj out a)))))

(defn options
  "The share's options from parsed `opts` (`:web-port` …) and `env` (a fn of
  a variable's name), a flag over its `BY_WEB_*` variable over the default."
  [opts env]
  (let [v (fn [k var] (or (get opts k) (not-empty (env var))))
        on? (fn [k var] (or (true? (get opts k)) (= "1" (env var))))
        n (fn [x d] (cond (number? x) (long x) (string? x) (or (parse-long (str/trim x)) d) :else d))]
    {:tmux? (on? :web-tmux "BY_WEB_TMUX")
     :port (n (v :web-port "BY_WEB_PORT") 7681)
     :bind (or (v :web-bind "BY_WEB_BIND") "127.0.0.1")
     :user (v :web-user "BY_WEB_USER")
     :pass (v :web-pass "BY_WEB_PASS")
     :writable? (not (on? :web-readonly "BY_WEB_READONLY"))
     :max-clients (n (v :web-max-clients "BY_WEB_MAX_CLIENTS") 0)
     :once? (on? :web-once "BY_WEB_ONCE")}))

(defn wanted?
  "Does this run share itself: `--web`, `--web-tmux` or `BY_WEB=1` — and is it
  not already the child ttyd started?"
  [opts env]
  (and (not= "1" (env "BY_WEB_CHILD"))
       (or (:web opts) (:web-tmux opts) (= "1" (env "BY_WEB")) (= "1" (env "BY_WEB_TMUX")))))

;; --- the pieces: brainyard's, pure where they can be ------------------------------------------

(defn gen-password
  "A short random password, 12 hex characters."
  []
  (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 12))

(defn resolve-credential
  "The basic-auth credential: auth is mandatory, a password generated when none
  is given. `{:user :pass :credential \"user:pass\" :generated?}`."
  [{:keys [user pass]}]
  (let [user (or (not-empty (some-> user str/trim)) "by")
        generated? (empty? (some-> pass str/trim not-empty))
        pass (if generated? (gen-password) (str/trim pass))]
    {:user user :pass pass :credential (str user ":" pass) :generated? generated?}))

(defn build-ttyd-argv
  "The ttyd command, pure. Always `-O` (check the origin: cross-site WebSocket
  hijacking); `-W` when writable; `-m` a client cap when positive; `-o` to stop
  after the first client leaves; then `--` and the child."
  [{:keys [ttyd-path bind port credential writable? max-clients once? child-argv]
    :or {ttyd-path "ttyd" bind "127.0.0.1" port 7681 writable? true max-clients 0}}]
  (-> [ttyd-path "-i" (str bind) "-p" (str port) "-O"]
      (cond-> credential (conj "-c" credential)
              writable? (conj "-W")
              (pos? (long (or max-clients 0))) (conj "-m" (str max-clients))
              once? (conj "-o"))
      (conj "--")
      (into child-argv)))

(defn self-argv
  "How ttyd relaunches this program: `BY_WEB_SELF` (split on spaces), else
  the `byj` binary this process was started from (`exe`), else — run
  from a checkout — `jolt -m brainyard.main`. `{:argv}`."
  [env exe]
  (let [override (some-> (env "BY_WEB_SELF") str/trim not-empty)]
    {:argv (cond override (str/split override #"\s+")
                 (and exe (str/starts-with? (.getName (io/file (str exe))) "byj")) [(str exe)]
                 :else ["jolt" "-m" "brainyard.main"])}))

(defn banner
  "What the launcher prints: the URL, the credential, the mode, how to reach
  it from elsewhere, how to stop."
  [{:keys [bind port writable? tmux?]} {:keys [user pass generated?]} & [{:keys [attach]}]]
  (let [local? (contains? #{"127.0.0.1" "localhost" "::1"} bind)
        url (str "http://" bind ":" port)]
    (str/join "\n"
              (concat
               ["🌐 brainyard web session (ttyd)"
                (str "   URL:    " url)
                (str "   Auth:   " user " / " pass (when generated? "   (generated)"))
                (str "   Mode:   " (if writable? "writable" "read-only")
                     ;; ttyd starts its command once per connection: only tmux shares one
                     (if tmux? " · one session, shared (tmux)" " · a fresh session for each viewer (--web-tmux shares one)")
                     (if local? " · localhost only" (str " · bound to " bind)))]
               (if local?
                 [(str "   Remote: tunnel in — ssh -L " port ":127.0.0.1:" port " <this-host>")]
                 ["   ⚠ bound beyond localhost: put it behind TLS, use a strong password, cap the clients (--web-max-clients)"])
               (when attach [(str "   Local:  " attach)])
               ["   Ctrl-C stops sharing."]))))

;; --- running it -------------------------------------------------------------------------------

(defn- child-env []
  (assoc (into {} (System/getenv)) "BY_WEB_CHILD" "1"))

(defn serve!
  "Tier 1: ttyd in the foreground, the terminal its own, a fresh session
  inside it. Returns ttyd's exit code once it stops — Ctrl-C, or `--web-once`."
  [ttyd-argv]
  ;; the command spread, never a vector (provenance P-1)
  (let [proc (apply fx/start {:in :inherit :out :inherit :err :inherit :env (child-env)} ttyd-argv)]
    (:exit @proc)))

(defn- sh-quote [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn serve-tmux!
  "Tier 2: the session in a detached tmux server on a private socket, ttyd
  attached to it, its log in a file; the launcher prints `say` and waits until
  the session ends (the agent quits) or the launcher is stopped, then stops
  ttyd and the tmux server. `{:exit}`, or `{:error}` when tmux fails."
  [{:keys [child-argv cols rows] :or {cols 120 rows 40}} ttyd-opts say]
  (let [sock (str "by-web-" (subs (gen-password) 0 8))
        session "brainyard"
        tmux ["tmux" "-L" sock]
        r (apply fx/sh {:out :string :err :string :env (child-env)}
                 (concat tmux ["new-session" "-d" "-s" session "-x" (str cols) "-y" (str rows) "--"] child-argv))]
    (if-not (zero? (:exit r))
      {:error (str "tmux new-session failed: " (str/trim (str (:err r) (:out r))))}
      (let [attach (concat tmux ["attach" "-t" session])
            argv (build-ttyd-argv (assoc ttyd-opts :child-argv (vec attach)))
            log (str (System/getProperty "java.io.tmpdir") "/by-web-ttyd-" sock ".log")
            ttyd (fx/start {:out :string :err :string :env (child-env)}
                           "sh" "-c" (str "exec " (str/join " " (map sh-quote argv)) " >> " (sh-quote log) " 2>&1"))
            stopped (atom false)
            stop! (fn []
                    (when (compare-and-set! stopped false true)
                      (try (fx/sh {:out :string :err :string} "tmux" "-L" sock "kill-server") (catch Throwable _ nil))
                      (try (p/destroy-tree ttyd) (catch Throwable _ nil))))
            hook (Thread. ^Runnable stop!)]
        (.addShutdownHook (Runtime/getRuntime) hook)
        (println (say (str/join " " attach) log))
        (flush)
        ;; the session ending (the agent /quit) is the share ending
        (loop []
          (when (zero? (:exit (apply fx/sh {:out :string :err :string} (concat tmux ["has-session" "-t" session]))))
            (Thread/sleep 1000)
            (recur)))
        (stop!)
        (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch Throwable _ nil))
        {:exit 0}))))

(defn ttyd-hint
  "How to install ttyd, from /init's requirements when they name it."
  []
  (or (some (fn [r] (when (= "ttyd" (:command r)) (first (get (:install r) (init/platform)))))
            @init/requirements)
      "brew install ttyd (macOS) · sudo apt install ttyd (Debian) · https://github.com/tsl0922/ttyd#installation"))
