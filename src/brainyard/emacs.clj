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
  (:require [clojure.edn :as edn]
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
       :description (str "Which Emacs this session reaches and whether it is up: its server, Emacs's version, whether byj.el is loaded,"
                         " and the project's buffers with which hold unsaved edits. The other emacs$ tools are offered only while it is up.")
       :input-schema [:map]
       :output-schema [:map-of :keyword :any]
       :effects #{}
       :fn (fn [_ _ctx]
             (let [s (server config)]
               (if-not (reachable? config opts)
                 {:server s :up false
                  :how (if (project-server? config)
                         "the project's Emacs daemon is not running: start emacs from the right rail's Terminal view (ctrl+r), or set :emacs-server to the person's own Emacs server (\"server\") in ~/.brainyard/config.edn"
                         (str "no Emacs server " s " is running: in that Emacs, M-x server-start"))}
                 (let [r (call! config "status" {} opts)]
                   (if (:error r) {:server s :up true :error (:error r)} (merge {:server s :up true} (:ok r)))))))}]
      up?
      (into
       [{:name "emacs$context" :kind :command
         :description (str "What the person is looking at in Emacs: the file or buffer, the cursor's line and column, whether it has unsaved"
                           " edits, the text they selected, the major mode, the files in the other windows, and in an org file the heading"
                           " path and the src block at point (language, name, line, header arguments, body). Ask when they say \"this\","
                           " \"here\" or \"the selection\".")
         :input-schema [:map]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :fn (fn [_ _ctx] (call "context" {}))}
        {:name "emacs$read" :kind :command
         :description (str "A file's text as Emacs has it — unsaved edits included, which read-file does not see — or lines :start to :end"
                           " of it; a file no buffer visits is read from the disk and says :visited false. :modified says whether the buffer"
                           " differs from the disk. Clipped to its start past " max-chars " characters.")
         :input-schema [:map
                        [:file {:optional true} [:string {:desc "The file's path."}]]
                        [:buffer {:optional true} [:string {:desc "A buffer's name, for one visiting no file."}]]
                        [:start {:optional true} [:int {:desc "The first line, from 1."}]]
                        [:end {:optional true} [:int {:desc "The last line."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file buffer start end]} _ctx]
               (if (and (str/blank? (str file)) (str/blank? (str buffer)))
                 {:error "give :file or :buffer"}
                 (call "read" (cond-> {} file (assoc :file (abs-path config file)) buffer (assoc :buffer buffer)
                                      start (assoc :start start) end (assoc :end end)))))}
        {:name "emacs$show" :kind :command
         :description (str "Show the person a place in Emacs: visit :file at :line in the window they are in, highlight :line to :end until"
                           " the next show, and put :message in the echo area. Changes nothing but what is on their screen. Use it to point"
                           " at what you are explaining.")
         :input-schema [:map
                        [:file [:string {:desc "The file's path."}]]
                        [:line {:optional true} [:int {:desc "The first line to highlight, from 1."}]]
                        [:end {:optional true} [:int {:desc "The last line to highlight."}]]
                        [:message {:optional true} [:string {:desc "One line for the echo area."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/display}
         :path-params [:file]
         :fn (fn [{:keys [file line end message]} _ctx]
               (call "show" (cond-> {:file (abs-path config file)} line (assoc :line line) end (assoc :end end) message (assoc :message message))))}
        {:name "emacs$eval" :kind :command
         :description (str "Evaluate Emacs Lisp in the person's Emacs, every form in :code, lexically; the last value, printed. It runs as"
                           " them, with everything their Emacs reaches, and holds their Emacs while it runs, so keep it quick and do only"
                           " what was asked: try a command you are writing for them, or ask Emacs what no other emacs$ tool answers.")
         :input-schema [:map [:code [:string {:desc "Emacs Lisp, one form or several."}]]]
         :output-schema [:or [:map [:value :string]] err]
         :effects #{:editor/eval}
         :fn (fn [{:keys [code]} _ctx]
                (cond (sandboxed?) {:error "refused under --sandbox: an Emacs byj did not start runs outside the sandbox"}
                      (str/blank? (str code)) {:error "give :code"}
                      :else (call "eval" {:code code})))}
        {:name "emacs$note" :kind :command
         :description (str "Leave notes on lines in the person's Emacs, where they work: each at the end of its line, dimmed, and all of"
                           " them listed in *byj-notes*, which M-g n / M-g p (next-error) walk. :kind says what it is — issue, hint, good"
                           " or question. With :clear, take notes away instead: :file's, or all of them. Changes nothing in any file.")
         :input-schema [:map
                        [:notes {:optional true}
                         [:vector [:map
                                   [:file [:string {:desc "The file's path."}]]
                                   [:line [:int {:desc "The line, from 1."}]]
                                   [:text [:string {:desc "The note: one short sentence."}]]
                                   [:kind {:optional true} [:string {:desc "issue, hint, good or question."}]]]]]
                        [:clear {:optional true} [:boolean {:desc "Take notes away instead: :file's, or all."}]]
                        [:file {:optional true} [:string {:desc "With :clear, only this file's notes."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/display}
         :fn (fn [{:keys [notes clear file]} _ctx]
               (cond
                 clear (call "notes-clear" (cond-> {} file (assoc :file (abs-path config file))))
                 (empty? notes) {:error "give :notes, or :clear"}
                 :else (call "note" {:notes (mapv #(update % :file (fn [f] (abs-path config f))) notes)})))}
        {:name "emacs$edit" :kind :command
         :description (str "Change the person's buffer in Emacs: replace :old, which must be there exactly once, with :new, as one undo"
                           " step. The buffer is left unsaved and the new text highlighted, so they see the change, keep it by saving, or"
                           " undo it — their own unsaved edits stay as they were. Use it to show a change rather than make one behind them:"
                           " write-file and update-file write the disk, under whatever they have not saved.")
         :input-schema [:map
                        [:file [:string {:desc "The file's path."}]]
                        [:old [:string {:desc "The exact text to replace, found once in the buffer."}]]
                        [:new [:string {:desc "What replaces it."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/edit}
         :path-params [:file]
         :fn (fn [{:keys [file old new]} _ctx]
               (if (str/blank? (str old))
                 {:error "give :old, the text to replace"}
                 (call "edit" {:file (abs-path config file) :old old :new (str new)})))}
        {:name "emacs$xref" :kind :command
         :description (str "Where a name is defined, or with :references where it is used, as the person's Emacs knows it — their"
                           " LSP server through eglot or lsp-mode, elisp, or a tags table — for the language of :file. Name it by"
                           " :identifier (found on :line, or its first place in the file) or by :line and :column. Each place is"
                           " file, line, column and the text Emacs shows for it; :backend says which answered, and null with a note"
                           " when the file has none, which is not the same as nothing found. Read-only. More exact than grep where a"
                           " server runs: it knows scope, overloads and imports.")
         :input-schema [:map
                        [:file [:string {:desc "A file in the language asked about: its buffer's backend answers."}]]
                        [:identifier {:optional true} [:string {:desc "The name, as written in the file."}]]
                        [:line {:optional true} [:int {:desc "The line the name is on, from 1."}]]
                        [:column {:optional true} [:int {:desc "Its column, from 1, when no :identifier is given."}]]
                        [:references {:optional true} [:boolean {:desc "Where it is used, not where it is defined."}]]]
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
         :description (str "The errors and warnings the person's Emacs shows for :file now — flymake's (eglot's LSP diagnostics among"
                           " them) or flycheck's — for the buffer as it stands, unsaved edits included: line, column, severity, text and"
                           " checker. :pending names checkers that have not reported since the last change, so ask again after a moment;"
                           " :backend null with a note means nothing checks the file in Emacs, not that it is clean. Read-only.")
         :input-schema [:map [:file [:string {:desc "The file's path."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file]} _ctx] (call "diagnostics" {:file (abs-path config file)}))}
        {:name "emacs$describe" :kind :command
         :description (str "What the person's own Emacs says about itself, from the keymaps and modes of :file's buffer (else the one in"
                           " front of them) — their init, Doom's or Spacemacs's, evil's states — not the Emacs you remember. Give one of:"
                           " :key (\"C-c C-c\") → the command it runs there, or the keys after it when it is a prefix; :command → its keys,"
                           " whether it is interactive and its doc's first line, or that this Emacs has no such function; :prefix (\"C-c\")"
                           " → every key that continues it there and what each runs; :apropos (\"rename symbol\") → commands whose names"
                           " have all the words, bound ones first, with their keys; :modes true → the major mode and the minor modes on."
                           " Check every key and command with it before telling the person one. Read-only.")
         :input-schema [:map
                        [:file {:optional true} [:string {:desc "A file whose buffer's keymaps answer; else the buffer in front of the person."}]]
                        [:key {:optional true} [:string {:desc "A key as Emacs writes it: \"C-c C-c\", \"M-x\", \"<f5>\"."}]]
                        [:command {:optional true} [:string {:desc "A command's name."}]]
                        [:prefix {:optional true} [:string {:desc "A prefix key, as :key."}]]
                        [:apropos {:optional true} [:string {:desc "Words a command's name has, all of them."}]]
                        [:modes {:optional true} [:boolean {:desc "The modes on in the buffer."}]]]
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
         :description (str "Which commands the person uses in Emacs, as byj-observe-mode saw them — theirs to turn on (M-x"
                           " byj-observe-mode), keeping command names in their Emacs's memory, never what they type: the commands"
                           " run most with the keys they used and the keys bound; :runs, a small command run many times in a row (a"
                           " better command replaces it); :mx-with-keys, commands run through M-x that have a key; and the tips given,"
                           " each with :used-since, how often its command ran after it. :on false says it is off. With :tip (a"
                           " command) and :why, record a tip you gave instead, so a later look can tell whether it was taken up.")
         :input-schema [:map
                        [:tip {:optional true} [:string {:desc "A command you suggested, to record."}]]
                        [:why {:optional true} [:string {:desc "With :tip: what it is better for, one line."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :fn (fn [{:keys [tip why]} _ctx]
               (call "habits" (cond-> {} (not (str/blank? (str tip))) (assoc :tip tip :why (str why)))))}
        {:name "emacs$suggest" :kind :command
         :description (str "Put a command in front of the person in Emacs, in *byj-howto*: :why (what it does for them, one line), the"
                           " command, its keys in the buffer they are in — looked up there, never your word for them — its doc line,"
                           " and a button that runs it. They press the keys or the button; you do not run it. Refused for a"
                           " command their Emacs does not have.")
         :input-schema [:map
                        [:command [:string {:desc "The command's name."}]]
                        [:why {:optional true} [:string {:desc "What it does for them, one line."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{:editor/display}
         :fn (fn [{:keys [command why]} _ctx]
               (if (str/blank? (str command))
                 {:error "give :command"}
                 (call "suggest" (cond-> {:command command} why (assoc :why why)))))}
        {:name "emacs$features" :kind :command
         :description (str "What the person's Emacs has, to set it up or to say why something is missing: its version, init file and"
                           " package archives; for each of :features (\"eglot\", \"cider\") whether it is loaded or loadable, for each of"
                           " :packages whether it is installed, for each of :programs (\"clojure-lsp\") where it is on exec-path; with"
                           " :file, that buffer's major mode, the language server eglot would start for it, whether that is found and"
                           " whether eglot runs there. Read-only; ask it before suggesting an install or a setting.")
         :input-schema [:map
                        [:features {:optional true} [:vector [:string {:desc "A feature, as require names it."}]]]
                        [:packages {:optional true} [:vector [:string {:desc "A package's name."}]]]
                        [:programs {:optional true} [:vector [:string {:desc "A program's name."}]]]
                        [:file {:optional true} [:string {:desc "A file whose mode and language server to look at."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [features packages programs file]} _ctx]
               (call "features" (cond-> {:features (vec features) :packages (vec packages) :programs (vec programs)}
                                  file (assoc :file (abs-path config file)))))}
        {:name "emacs$lessons" :kind :command
         :description (str "Lessons in using Emacs itself, for coaching (/emacs coach emacs: …): without :name, each lesson's name,"
                           " title and summary; with :name, its steps — each a title and the commands that show it done — and its"
                           " text. A lesson names commands, never keys: look each key up with emacs$describe.")
         :input-schema [:map [:name {:optional true} [:string {:desc "A lesson's name."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :fn (fn [{:keys [name]} _ctx]
               (let [ls (lessons config opts)]
                 (if (str/blank? (str name))
                   {:lessons (mapv #(select-keys % [:name :title :summary]) ls)}
                   (or (some #(when (= name (:name %)) (select-keys % [:name :title :summary :steps :text])) ls)
                       {:error (str "no lesson " name "; there are " (str/join ", " (map :name ls)))}))))}
        {:name "emacs$org-outline" :kind :command
         :description (str "An org file's structure as Emacs has it, unsaved edits included: its headings (level, title, line, TODO, tags)"
                           " and src blocks — each block's name, language, first and last line, header arguments as written and :results"
                           " as in force, the absolute file it tangles to (inherited :tangle included; null for none), the noweb references"
                           " <<name>> in it, and whether it has results. Read it before editing a literate file: the org file is the source,"
                           " and the files it tangles to are output.")
         :input-schema [:map [:file [:string {:desc "The org file."}]]]
         :output-schema [:or [:map-of :keyword :any] err]
         :effects #{}
         :path-params [:file]
         :fn (fn [{:keys [file]} _ctx] (call "org-outline" {:file (abs-path config file)}))}
        {:name "emacs$org-tangle" :kind :command
         :description (str "Tangle an org file — write each src block to the file its :tangle says — through org-babel in the person's Emacs;"
                           " the files written. Every target is checked against the directories tools may write before anything is written."
                           " Refused while the org buffer holds unsaved edits, since org saves it first. Edit the org file's blocks, never"
                           " the files they tangle to, then tangle, then build or test what it wrote.")
         :input-schema [:map [:file [:string {:desc "The org file."}]]]
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
         :description (str "Run one src block of an org file through org-babel in the person's Emacs, by its :name or by a :line inside it;"
                           " its results go under it as #+RESULTS, as when the person runs it, and are returned with the value. The buffer"
                           " is left unsaved unless :save. The block runs as the person, in their Emacs, which waits while it runs: a block"
                           " that takes long holds their Emacs, and stopping it at the bound does not stop it. A language needs its babel"
                           " backend set up there (shell and emacs-lisp always).")
         :input-schema [:map
                        [:file [:string {:desc "The org file."}]]
                        [:name {:optional true} [:string {:desc "The block's #+name."}]]
                        [:line {:optional true} [:int {:desc "A line inside the block, from 1."}]]
                        [:save {:optional true} [:boolean {:desc "Save the org file after (default false); refused over unsaved edits."}]]]
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
