;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.emacs
  "byj and an Emacs the person uses (RFC-067, byj-gly.459): which Emacs byj
  reaches, and the one way it calls into it.

  - **Which Emacs** is `:emacs-server`: `\"project\"` (the default) is the
    Terminal view's daemon, `byj-<project tag>` (RFC-028, byj-gly.451); any
    other value is a server name or a socket path, as `emacsclient -s` takes
    — `\"server\"` is the person's own default server, never reached unless
    they name it.
  - **A call** is one fixed form: `byj.el` loaded when its version differs,
    then `(byj-call 'OP ARGS OUT)`. The arguments and the answer are JSON
    files of byj's, so no text a model wrote is ever part of the elisp Emacs
    evaluates; `OP` is one of `ops`. Bounded by `terminal/*editor-timeout-ms*`:
    Emacs evaluates on its one thread, and a busy one must not hold byj.
  - **What Emacs does** is `resources/brainyard/emacs/byj.el`, a project's
    `.brainyard/emacs/byj.el` over it (RFC-001), written to
    `~/.brainyard/emacs/byj-<hash>.el` to be loaded, since a built binary
    cannot hand Emacs a resource path.

  Every call byj makes into Emacs goes this way: the tools (`commands`),
  `@here` (`context!`), a clicked file (`open-file!`) and the revert after
  the agent's tools (`after-tools!`). The Terminal view's emacs entry is how
  the project's daemon is started."
  (:require [brainyard.tool-words :as tool-words]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.effect :as fx]
            [brainyard.config :as config]
            [brainyard.json :as json]
            [brainyard.permission :as permission]
            [brainyard.terminal :as terminal]
            [ebb.core :as m]))

(def ^:dynamic *emacsclient*
  "The emacsclient byj runs: a test's stand-in in its place."
  "emacsclient")

(def ops
  "What byj calls in byj.el, by name: its `byj-ops`."
  #{"status" "context" "read" "show" "open" "revert" "eval"
    "org-outline" "org-tangle-targets" "org-tangle" "org-execute"
    "note" "notes-clear" "edit" "hello" "xref" "diagnostics" "describe" "turn" "habits" "suggest" "features" "places"})

(def max-chars
  "The most text one answer carries; byj.el clips to the same."
  32000)

(defn- project-dir [config]
  (str (or (:project-dir config) (:working-dir config) ".")))

(defn abs-path
  "`path` as Emacs must be given it: absolute, a relative one against the
  session's working directory — Emacs would resolve it against its own."
  [config path]
  (let [f (io/file (str path))]
    (.getPath (if (.isAbsolute f) f (io/file (str (or (:working-dir config) (:project-dir config) ".")) (str path))))))

;; --- which Emacs ------------------------------------------------------------------

(defn server
  "The Emacs server `config` names (`:emacs-server`): the project's own
  daemon, `byj-<tag>`, for `\"project\"` or nothing; else the name or path
  given."
  [config]
  (let [s (str/trim (str (or (:emacs-server config) "project")))]
    (if (or (str/blank? s) (= "project" s))
      (str "byj-" (terminal/project-tag (project-dir config)))
      s)))

(defn project-server?
  "Whether `config` reaches the project's own daemon, not one the person named."
  [config]
  (let [s (str/trim (str (or (:emacs-server config) "project")))]
    (or (str/blank? s) (= "project" s))))

(defonce ^:private uid-cache (atom nil))

(defn- uid []
  (or @uid-cache
      (reset! uid-cache (try (let [r (fx/sh {:out :string :err :string} "id" "-u")]
                               (when (zero? (:exit r)) (str/trim (:out r))))
                             (catch Throwable _ nil)))))

(defn socket-files
  "Where Emacs would have made server `name`'s socket, as emacsclient looks:
  the path itself when `name` is one; else under `$XDG_RUNTIME_DIR/emacs/`,
  then `$TMPDIR/emacs<uid>/` and `/tmp/emacs<uid>/`. `env` is
  `System/getenv` unless given."
  [name & [{:keys [env] :or {env #(System/getenv %)} :as opts}]]
  (if (str/includes? name "/")
    [(io/file name)]
    (let [u (or (:uid opts) (uid))
          tmp (or (env "TMPDIR") "/tmp")]
      (vec (distinct
            (concat (when-let [x (env "XDG_RUNTIME_DIR")] [(io/file x "emacs" name)])
                    (when u [(io/file tmp (str "emacs" u) name)
                             (io/file "/tmp" (str "emacs" u) name)])))))))

(defn reachable?
  "Whether the server `config` names is up, from its socket file alone — no
  process, so it can be asked each turn."
  [config & [opts]]
  (boolean (some #(.exists ^java.io.File %) (socket-files (server config) opts))))

(defonce ^:private installed-cache (atom {}))

(defn installed?
  "Whether emacsclient is on the PATH, asked once a run for each program:
  the registry is built every turn."
  []
  (let [p *emacsclient*
        v (get @installed-cache p)]
    (if (nil? v) (get (swap! installed-cache assoc p (terminal/installed? p)) p) v)))

;; --- byj.el -----------------------------------------------------------------------

(defn el-source
  "byj.el's text: the project's `.brainyard/emacs/byj.el`, else the shipped one."
  [config]
  (let [own (io/file (project-dir config) ".brainyard" "emacs" "byj.el")]
    (slurp (if (.isFile own) own (io/resource "brainyard/emacs/byj.el")))))

(defn el-file
  "`{:file :version}`: byj.el written where Emacs can load it,
  `<home>/.brainyard/emacs/byj-<version>.el`, once per version. `home` is
  the person's unless given."
  [config & [{:keys [home]}]]
  (let [text (el-source config)
        version (format "%08x" (bit-and (hash text) 0xffffffff))
        f (io/file (or home (System/getProperty "user.home")) ".brainyard" "emacs" (str "byj-" version ".el"))]
    (when-not (and (.isFile f) (= text (slurp f)))
      (io/make-parents f)
      (spit f text))
    {:file (.getPath f) :version version}))

;; --- a call -----------------------------------------------------------------------

(defn form
  "The elisp byj runs for `op`: byj.el loaded unless this `version` is,
  then `byj-call` on the two files. Only byj's own paths and `op`, one of
  `ops`, are in it."
  [{:keys [file version]} op args-file out-file]
  (let [lit terminal/string-literal]
    (str "(progn (unless (equal (bound-and-true-p byj-el-loaded) " (lit version) ")"
         " (load " (lit file) " nil t) (setq byj-el-loaded " (lit version) "))"
         " (byj-call '" op " " (lit args-file) " " (lit out-file) "))")))

(defn call!
  "Run byj.el's `op` with `args` in the Emacs `config` names: `{:ok value}`
  — the op's answer, keys as keywords — or `{:error why}`. Each call
  carries the project directory and session id, so byj.el knows which
  session asked. `timeout-ms` bounds it (`terminal/*editor-timeout-ms*`
  unless given); `home` as `el-file`."
  [config op args & [{:keys [timeout-ms home]}]]
  (if-not (contains? ops (str op))
    {:error (str "no op " op)}
    (let [dir (project-dir config)
          client *emacsclient*
          el (el-file config {:home home})
          in (java.io.File/createTempFile "byj-emacs-args-" ".json")
          out (java.io.File/createTempFile "byj-emacs-out-" ".json")]
      (try
        (spit in (json/write-str (assoc (or args {}) :_byj {:project dir :session (some-> (:session-id config) str)})))
        (spit out "")
        (let [r (terminal/run-bounded dir [client "-s" (server config) "--eval" (form el (str op) (.getPath in) (.getPath out))]
                                      timeout-ms)
              text (slurp out)]
          (cond
            (not (zero? (:exit r)))
            {:error (str "could not ask Emacs (" (server config) "): "
                         (let [why (str/trim (str (:err r) (:out r)))] (if (str/blank? why) (str "exit " (:exit r)) why))
                         (when (str/includes? (str (:err r)) "timed out")
                           " — Emacs may still be busy with it"))}
            (str/blank? text) {:error "Emacs said nothing"}
            :else (let [a (try (json/read-str text) (catch Throwable _ nil))]
                    (cond (not (map? a)) {:error "Emacs said what is not a JSON object"}
                          (contains? a :error) {:error (str (:error a))}
                          :else {:ok (:ok a)}))))
        (finally (.delete in) (.delete out))))))

;; --- what the session asks of it --------------------------------------------------

(defn context!
  "What the person is looking at, in `terminal/context!`'s shape —
  `{:program \"emacs\" :context {…}}` or `{:error}` — or nil when the Emacs
  `config` names is not up, so `@here` can ask another editor."
  [config & [opts]]
  (when (reachable? config opts)
    (let [r (call! config "context" {} opts)]
      (if (:error r) r {:program "emacs" :context (:ok r)}))))

(defn places!
  "What `@visible` and `@trail` read (RFC-067 phase 10): with `:visible`,
  each window on the person's frame — its file, the lines it shows, the
  cursor's line, the one they are in; with `:trail`, the files they visited
  most recently. `{:visible […] :trail […]}`, `{:error}`, or nil when the
  Emacs `config` names is not up."
  [config which & [opts]]
  (when (reachable? config opts)
    (let [r (call! config "places" (select-keys which [:visible :trail]) opts)]
      (if (:error r) r (:ok r)))))

(defn open-file!
  "Open `path` at `line` and `col` in the Emacs `config` names, the screen
  left to byj: what to say, or nil when it is not up and the caller opens it
  its own way."
  [config path line col & [opts]]
  (when (reachable? config opts)
    (let [r (call! config "open" (cond-> {:file (str path)} line (assoc :line line) col (assoc :column col)) opts)]
      (if (:error r)
        (str "could not open " path " in Emacs: " (:error r))
        (str "opened " path (when line (str ":" line)) " in Emacs")))))

(defn after-tools!
  "Revert, in the Emacs `config` names, each buffer whose file the agent's
  tools changed and that holds no unsaved edits — off the caller's thread,
  never failing it."
  [config]
  (fx/run-detached (m/sp (try (m/? (fx/task-of #(when (reachable? config) (call! config "revert" {}))))
                              (catch Throwable _ nil)))
                   :emacs-after-tools))

(defn register!
  "Tell the Emacs `config` names which session this is, for the project, so
byj.el's commands ask it (RFC-067 phase 4): `byj-call` remembers who called,
and `hello` is a call that does nothing else. Off the caller's thread, never
failing it; nothing while Emacs is down, without a session id, or with
`:emacs-tools?` off."
  [config & [opts]]
  (when (and (:session-id config) (:emacs-tools? config true))
    (fx/run-detached (m/sp (try (m/? (fx/task-of #(when (reachable? config opts) (call! config "hello" {} opts))))
                                (catch Throwable _ nil)))
                     :emacs-register)))

(defn turn-args
  "What `turn!` tells Emacs of event `e`, a turn's start or end, or nil for
any other: the state, the turn and its question's first line at its start;
how long it took at its end, how many files differ from its start (kept for a
review, phase 8), and how it ended when that was not by answering."
  [e data]
  (case e
    :session.turn/start {:state "busy" :turn (:turn data)
                         :question (let [q (first (str/split-lines (str (:question data))))]
                                     (if (< 60 (count q)) (str (subs q 0 60) "…") q))}
    :session.turn/end (cond-> {:state "idle" :elapsed-ms (:elapsed-ms data) :files (or (:files-changed data) 0)}
                        ;; answered, as the activity feed reads it
                        (not (contains? #{nil :answer :answer-channel :goal-achieved} (:terminated-by data)))
                        (assoc :ended (name (:terminated-by data))))
    nil))

(defn turn!
  "Tell the Emacs `config` names that a turn of this session started or ended
(RFC-067 phase 7), for its mode line and, at the end, the echo area. Each is
stamped with when it was sent, so one that arrives after a later one is
ignored. Off the caller's thread, never failing it; nothing for another
event, while Emacs is down, without a session id, or with `:emacs-tools?`
off."
  [config e data & [opts]]
  (when-let [args (and (:session-id config) (:emacs-tools? config true) (turn-args e data))]
    (let [args (assoc args :at (System/currentTimeMillis))]
      (fx/run-detached (m/sp (try (m/? (fx/task-of #(when (reachable? config opts) (call! config "turn" args opts))))
                                  (catch Throwable _ nil)))
                       :emacs-turn))))

(defn install-el!
  "byj.el written where the person's init can `require` it,
`<home>/.brainyard/emacs/byj.el` — the project's own when it has one, as
`el-source` — before byj has ever called that Emacs: its path."
  [config & [{:keys [home]}]]
  (let [f (io/file (or home (System/getProperty "user.home")) ".brainyard" "emacs" "byj.el")]
    (io/make-parents f)
    (spit f (el-source config))
    (.getPath f)))

(defn install-el-text
  "What `byj emacs install-el` says: where byj.el is, and the init lines
that load it and bind its commands."
  [path]
  (let [dir (.getParent (io/file path))]
    (str "byj.el written to " path "\n\n"
         "In your init:\n\n"
         "  (add-to-list 'load-path " (terminal/string-literal dir) ")\n"
         "  (require 'byj)\n"
         "  (global-set-key (kbd \"C-c b\") 'byj-command-map)\n\n"
         "C-c b a asks the project's live session, e explains the region or the defun,\n"
         "c checks your work and h asks for the next hint when it coaches, n shows its\n"
         "notes and N clears them, x explains the newest error, r reviews what the last\n"
         "turn changed, ? asks how to do something in Emacs, o lets byj see which\n"
         "commands you use (names only, never what you type) for tips, d fixes the\n"
         "diagnostic at point, w works on the region, u reviews your unsaved edits, t\n"
         "hands over the TODO on the line, and l shows the session's checklist, where\n"
         "C-c C-c ticks a step. Run this again\n"
         "after an upgrade for the newer byj.el; an Emacs byj calls is given byj's own\n"
         "copy whatever it required.")))

(defn sandboxed?
  "Whether this byj runs under `--sandbox`'s seatbelt (RFC-012): an Emacs it
  did not start is outside it, so what Emacs runs is not held by it."
  []
  (= "1" (System/getenv "BY_SANDBOX_CHILD")))

;; --- Emacs lessons (phase 9, D11) --------------------------------------------------

(defn- parse-lesson
  "An org lesson's title, summary and steps — each heading's title and the
  commands its :COMMANDS: property names — with its text."
  [name text]
  (let [lines (str/split-lines text)
        kw (fn [k] (some #(when-let [[_ v] (re-matches (re-pattern (str "(?i)#\\+" k ":\\s*(.*)")) %)] (str/trim v)) lines))
        steps (loop [ls lines acc []]
                (if-let [[l & more] (seq ls)]
                  (if-let [[_ h] (re-matches #"\*+\s+(.*)" l)]
                    (recur more (conj acc {:title (str/trim h) :commands []}))
                    (if-let [[_ cs] (and (seq acc) (re-matches #"\s*:COMMANDS:\s*(.*)" l))]
                      (recur more (update-in acc [(dec (count acc)) :commands] into (remove str/blank? (str/split (str/trim cs) #"\s+"))))
                      (recur more acc)))
                  acc))]
    {:name name :title (or (kw "title") name) :summary (kw "summary") :steps steps :text text}))

(defn lessons
  "The Emacs lessons there are, by name: the shipped ones
  (`resources/brainyard/emacs/lessons/`, listed by its `index.edn`), the
  person's `<home>/.brainyard/emacs/lessons/*.org` over them, and the
  project's `.brainyard/emacs/lessons/*.org` over both, a file's name its
  lesson's. Ordered as shipped, then the others by name."
  [config & [{:keys [home]}]]
  (let [shipped (try (edn/read-string (slurp (io/resource "brainyard/emacs/lessons/index.edn"))) (catch Throwable _ []))
        from-dir (fn [d] (into {} (for [f (or (.listFiles (io/file d)) [])
                                        :when (and (.isFile f) (str/ends-with? (.getName f) ".org"))]
                                    [(str/replace (.getName f) #"\.org$" "") (slurp f)])))
        texts (merge (into {} (for [n shipped :let [r (io/resource (str "brainyard/emacs/lessons/" n ".org"))] :when r] [n (slurp r)]))
                     (from-dir (io/file (or home (System/getProperty "user.home")) ".brainyard" "emacs" "lessons"))
                     (from-dir (io/file (project-dir config) ".brainyard" "emacs" "lessons")))
        order (concat (filter texts shipped) (sort (remove (set shipped) (keys texts))))]
    (mapv #(parse-lesson % (texts %)) order)))

;; --- the agent's tools ------------------------------------------------------------

(def ^:private err [:map [:error :string]])

(defn tangle-problem
  "Why tangling to `targets` is refused, or nil: a target outside the
  directories tools may write (`permission/allowed-dirs`, read from `ctx` as
  the path gate reads it), or one that holds a login or a key
  (`:denied-paths`). Emacs writes the targets, outside every gate, so they
  are checked here before it is asked (RFC-067 phase 2)."
  [config ctx targets]
  (let [ctx (if (:config ctx) ctx (assoc ctx :config config))
        wd (str (or (:working-dir config) (:project-dir config) "."))
        dirs (permission/allowed-dirs ctx wd)
        outside (remove (fn [t] (some #(permission/under? % t) dirs)) targets)
        denied (permission/denied-path (get-in ctx [:config :denied-paths] (get-in config/config-items [:denied-paths :default]))
                                       targets (System/getenv "HOME"))]
    (cond
      (seq outside) (str "it would write outside the directories tools may write: " (str/join ", " outside)
                         " (allowed: " (str/join ", " dirs) "); nothing was tangled")
      denied (str "it would write where a login or a key is kept (" denied ", :denied-paths); nothing was tangled"))))

(defn- answer [r] (if (:error r) {:error (:error r)} (:ok r)))

(defn commands
  "The `emacs$*` tools, the session's own agent's (behind `:emacs-tools?`,
  where emacsclient is): `emacs$status` always, the rest while the Emacs
  `config` names is up. `emacs$eval` and `emacs$org-execute` run code as the
  person, so they carry `:editor/eval` and `:editor/execute`, which
  `:tool-ask-effects` asks about by default, and are refused under
  `--sandbox`. `emacs$show` and `emacs$note` change only what is on the
  screen (`:editor/display`); `emacs$edit` a buffer, unsaved and undoable
  (`:editor/edit`); `emacs$org-tangle` writes files (`:fs/write`), each
  checked first (`tangle-problem`). A tool naming a file declares it in
  `:path-params`, so the path gate checks it as it checks read-file's, and
  hands it to Emacs absolute (`abs-path`)."
  [config & [opts]]
  (let [up? (reachable? config opts)
        call (fn [op args] (answer (call! config op args opts)))]
    (cond->
     [{:name "emacs$status" :kind :command
       :description (tool-words/description "emacs$status")
       :input-schema [:map]
       :output-schema [:map-of :keyword :any]
       :effects #{}
       :fn (fn [_ _ctx]
             (let [s (server config)]
               (if-not (reachable? config opts)
                 {:server s :up false
                  :how (if (project-server? config)
                         (tool-words/says "emacs/commands")
                         (str "no Emacs server " s " is running: in that Emacs, M-x server-start"))}
                 (let [r (call! config "status" {} opts)]
                   (if (:error r) {:server s :up true :error (:error r)} (merge {:server s :up true} (:ok r)))))))}]
      up?
      (into
       [{:name "emacs$context" :kind :command
         :description (tool-words/description "emacs$context")
         :input-schema [:map]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :fn (fn [_ _ctx] (call "context" {}))}
        {:name "emacs$read" :kind :command
         :description (tool-words/description "emacs$read" {:max-chars max-chars})
         :input-schema [:map
                        [:file {:optional true} [:string {:desc (tool-words/description "emacs$read :file")}]]
                        [:buffer {:optional true} [:string {:desc (tool-words/description "emacs$read :buffer")}]]
                        [:start {:optional true} [:int {:desc (tool-words/description "emacs$read :start")}]]
                        [:end {:optional true} [:int {:desc (tool-words/description "emacs$read :end")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file buffer start end]} _ctx]
               (if (and (str/blank? (str file)) (str/blank? (str buffer)))
                 {:error "give :file or :buffer"}
                 (call "read" (cond-> {} file (assoc :file (abs-path config file)) buffer (assoc :buffer buffer)
                                      start (assoc :start start) end (assoc :end end)))))}
        {:name "emacs$show" :kind :command
         :description (tool-words/description "emacs$show")
         :input-schema [:map
                        [:file [:string {:desc (tool-words/description "emacs$show :file")}]]
                        [:line {:optional true} [:int {:desc (tool-words/description "emacs$show :line")}]]
                        [:end {:optional true} [:int {:desc (tool-words/description "emacs$show :end")}]]
                        [:message {:optional true} [:string {:desc (tool-words/description "emacs$show :message")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/display}
         :path-params [:file]
         :fn (fn [{:keys [file line end message]} _ctx]
               (call "show" (cond-> {:file (abs-path config file)} line (assoc :line line) end (assoc :end end) message (assoc :message message))))}
        {:name "emacs$eval" :kind :command
         :description (tool-words/description "emacs$eval")
         :input-schema [:map [:code [:string {:desc (tool-words/description "emacs$eval :code")}]]]
         :output-schema [:or [:map [:value :string]] err]
         :effects #{:editor/eval}
         :fn (fn [{:keys [code]} _ctx]
                (cond (sandboxed?) {:error "refused under --sandbox: an Emacs byj did not start runs outside the sandbox"}
                      (str/blank? (str code)) {:error "give :code"}
                      :else (call "eval" {:code code})))}
        {:name "emacs$note" :kind :command
         :description (tool-words/description "emacs$note")
         :input-schema [:map
                        [:notes {:optional true}
                         [:vector [:map
                                   [:file [:string {:desc (tool-words/description "emacs$note :notes :file")}]]
                                   [:line [:int {:desc (tool-words/description "emacs$note :notes :line")}]]
                                   [:text [:string {:desc (tool-words/description "emacs$note :notes :text")}]]
                                   [:kind {:optional true} [:string {:desc (tool-words/description "emacs$note :notes :kind")}]]]]]
                        [:clear {:optional true} [:boolean {:desc (tool-words/description "emacs$note :clear")}]]
                        [:file {:optional true} [:string {:desc (tool-words/description "emacs$note :file")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/display}
         :fn (fn [{:keys [notes clear file]} _ctx]
               (cond
                 clear (call "notes-clear" (cond-> {} file (assoc :file (abs-path config file))))
                 (empty? notes) {:error "give :notes, or :clear"}
                 :else (call "note" {:notes (mapv #(update % :file (fn [f] (abs-path config f))) notes)})))}
        {:name "emacs$edit" :kind :command
         :description (tool-words/description "emacs$edit")
         :input-schema [:map
                        [:file [:string {:desc (tool-words/description "emacs$edit :file")}]]
                        [:old [:string {:desc (tool-words/description "emacs$edit :old")}]]
                        [:new [:string {:desc (tool-words/description "emacs$edit :new")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/edit}
         :path-params [:file]
         :fn (fn [{:keys [file old new]} _ctx]
               (if (str/blank? (str old))
                 {:error "give :old, the text to replace"}
                 (call "edit" {:file (abs-path config file) :old old :new (str new)})))}
        {:name "emacs$xref" :kind :command
         :description (tool-words/description "emacs$xref")
         :input-schema [:map
                        [:file [:string {:desc (tool-words/description "emacs$xref :file")}]]
                        [:identifier {:optional true} [:string {:desc (tool-words/description "emacs$xref :identifier")}]]
                        [:line {:optional true} [:int {:desc (tool-words/description "emacs$xref :line")}]]
                        [:column {:optional true} [:int {:desc (tool-words/description "emacs$xref :column")}]]
                        [:references {:optional true} [:boolean {:desc (tool-words/description "emacs$xref :references")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file identifier line column references]} _ctx]
               (if (and (str/blank? (str identifier)) (nil? line))
                 {:error "give :identifier, or :line (and :column)"}
                 (answer (call! config "xref"
                                (cond-> {:file (abs-path config file)}
                                  (not (str/blank? (str identifier))) (assoc :identifier identifier)
                                  line (assoc :line line) column (assoc :column column)
                                  references (assoc :references true))
                                ;; references may search the project
                                (assoc opts :timeout-ms (:emacs-execute-timeout-ms config 60000))))))}
        {:name "emacs$diagnostics" :kind :command
         :description (tool-words/description "emacs$diagnostics")
         :input-schema [:map [:file [:string {:desc (tool-words/description "emacs$diagnostics :file")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file]} _ctx] (call "diagnostics" {:file (abs-path config file)}))}
        {:name "emacs$describe" :kind :command
         :description (tool-words/description "emacs$describe")
         :input-schema [:map
                        [:file {:optional true} [:string {:desc (tool-words/description "emacs$describe :file")}]]
                        [:key {:optional true} [:string {:desc (tool-words/description "emacs$describe :key")}]]
                        [:command {:optional true} [:string {:desc (tool-words/description "emacs$describe :command")}]]
                        [:prefix {:optional true} [:string {:desc (tool-words/description "emacs$describe :prefix")}]]
                        [:apropos {:optional true} [:string {:desc (tool-words/description "emacs$describe :apropos")}]]
                        [:modes {:optional true} [:boolean {:desc (tool-words/description "emacs$describe :modes")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file key command prefix modes] words :apropos} _ctx]
               (let [given (cond-> {}
                             (not (str/blank? (str key))) (assoc :key key)
                             (not (str/blank? (str command))) (assoc :command command)
                             (not (str/blank? (str prefix))) (assoc :prefix prefix)
                             (not (str/blank? (str words))) (assoc :apropos words)
                             modes (assoc :modes true))]
                 (if (not= 1 (count given))
                   {:error "give one of :key, :command, :prefix, :apropos or :modes"}
                   (call "describe" (cond-> given file (assoc :file (abs-path config file)))))))}
        {:name "emacs$habits" :kind :command
         :description (tool-words/description "emacs$habits")
         :input-schema [:map
                        [:tip {:optional true} [:string {:desc (tool-words/description "emacs$habits :tip")}]]
                        [:why {:optional true} [:string {:desc (tool-words/description "emacs$habits :why")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :fn (fn [{:keys [tip why]} _ctx]
               (call "habits" (cond-> {} (not (str/blank? (str tip))) (assoc :tip tip :why (str why)))))}
        {:name "emacs$suggest" :kind :command
         :description (tool-words/description "emacs$suggest")
         :input-schema [:map
                        [:command [:string {:desc (tool-words/description "emacs$suggest :command")}]]
                        [:why {:optional true} [:string {:desc (tool-words/description "emacs$suggest :why")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/display}
         :fn (fn [{:keys [command why]} _ctx]
               (if (str/blank? (str command))
                 {:error "give :command"}
                 (call "suggest" (cond-> {:command command} why (assoc :why why)))))}
        {:name "emacs$features" :kind :command
         :description (tool-words/description "emacs$features")
         :input-schema [:map
                        [:features {:optional true} [:vector [:string {:desc (tool-words/description "emacs$features :features")}]]]
                        [:packages {:optional true} [:vector [:string {:desc (tool-words/description "emacs$features :packages")}]]]
                        [:programs {:optional true} [:vector [:string {:desc (tool-words/description "emacs$features :programs")}]]]
                        [:file {:optional true} [:string {:desc (tool-words/description "emacs$features :file")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [features packages programs file]} _ctx]
               (call "features" (cond-> {:features (vec features) :packages (vec packages) :programs (vec programs)}
                                  file (assoc :file (abs-path config file)))))}
        {:name "emacs$lessons" :kind :command
         :description (tool-words/description "emacs$lessons")
         :input-schema [:map [:name {:optional true} [:string {:desc (tool-words/description "emacs$lessons :name")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :fn (fn [{:keys [name]} _ctx]
               (let [ls (lessons config opts)]
                 (if (str/blank? (str name))
                   {:lessons (mapv #(select-keys % [:name :title :summary]) ls)}
                   (or (some #(when (= name (:name %)) (select-keys % [:name :title :summary :steps :text])) ls)
                       {:error (str "no lesson " name "; there are " (str/join ", " (map :name ls)))}))))}
        {:name "emacs$org-outline" :kind :command
         :description (tool-words/description "emacs$org-outline")
         :input-schema [:map [:file [:string {:desc (tool-words/description "emacs$org-outline :file")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file]} _ctx] (call "org-outline" {:file (abs-path config file)}))}
        {:name "emacs$org-tangle" :kind :command
         :description (tool-words/description "emacs$org-tangle")
         :input-schema [:map [:file [:string {:desc (tool-words/description "emacs$org-tangle :file")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:fs/write}
         :path-params [:file]
         :fn (fn [{:keys [file]} ctx]
               (let [f (abs-path config file)
                     t (call! config "org-tangle-targets" {:file f} opts)]
                 (if (:error t)
                   {:error (:error t)}
                   (let [targets (vec (get-in t [:ok :targets]))]
                     (cond
                       (empty? targets) {:error (str f " has no block that tangles: none has a :tangle header")}
                       :else (if-let [why (tangle-problem config ctx targets)]
                               {:error (str "refused: " why)}
                               (call "org-tangle" {:file f :expect targets})))))))}
        {:name "emacs$org-execute" :kind :command
         :description (tool-words/description "emacs$org-execute")
         :input-schema [:map
                        [:file [:string {:desc (tool-words/description "emacs$org-execute :file")}]]
                        [:name {:optional true} [:string {:desc (tool-words/description "emacs$org-execute :name")}]]
                        [:line {:optional true} [:int {:desc (tool-words/description "emacs$org-execute :line")}]]
                        [:save {:optional true} [:boolean {:desc (tool-words/description "emacs$org-execute :save")}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/execute :process}
         :path-params [:file]
         :fn (fn [{:keys [file name line save]} _ctx]
               (cond (sandboxed?) {:error "refused under --sandbox: an Emacs byj did not start runs outside the sandbox"}
                     (and (str/blank? (str name)) (nil? line)) {:error "give :name or :line"}
                     :else (answer (call! config "org-execute"
                                          (cond-> {:file (abs-path config file)}
                                            (not (str/blank? (str name))) (assoc :name name)
                                            line (assoc :line line)
                                            save (assoc :save true))
                                          (assoc opts :timeout-ms (:emacs-execute-timeout-ms config 60000))))))}]))))
