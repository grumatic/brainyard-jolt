(ns brainyard.terminal
  "The right rail's Terminal view (byj-gly.412): programs beside the session —
  a shell, an editor, htop, lazygit — each in a detached tmux session of its
  own, in the project directory, that outlives byj. Enter starts one and hands
  it the terminal (`ctrl-b d` comes back); a later byj finds it again by its
  tmux session's name, `byj-term-<project>-<name>`, so nothing is saved.
  Without tmux, Enter runs the program in the foreground, as `$EDITOR` runs.

  The tmux settings are agent-host's (`agent-host/*tmux*`, `attach-env`,
  the nested-tmux hint), as Claude Code and Codex sessions run the same way
  (RFC-033, RFC-049).
  The catalogue is data (`resources/brainyard/terminal.edn`, RFC-001).
  Mechanism only: what is offered is the catalogue's, when it runs the
  person's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [brainyard.agent-host :as ah]
            [brainyard.claude-host :as host]
            [brainyard.effect :as fx]
            [brainyard.json :as json]
            [ebb.core :as m]))

;; --- the catalogue -----------------------------------------------------------------

(defn- read-entries [src]
  (try (some-> src slurp edn/read-string vec) (catch Throwable _ nil)))

(defn catalogue
  "The programs offered, in order: the shipped list, then the person's
  (`~/.brainyard/terminal.edn`) and the project's over it, an entry replacing
  one of its name in place and a new one going last; `:hidden true` takes one
  away."
  [project-dir & [{:keys [home]}]]
  (let [layers [(read-entries (io/resource "brainyard/terminal.edn"))
                (read-entries (let [f (io/file (or home (System/getProperty "user.home")) ".brainyard" "terminal.edn")]
                                (when (.isFile f) f)))
                (read-entries (let [f (io/file (str project-dir) ".brainyard" "terminal.edn")]
                                (when (.isFile f) f)))]
        merged (reduce (fn [acc entry]
                         (if-let [i (first (keep-indexed (fn [i e] (when (= (:name e) (:name entry)) i)) acc))]
                           (assoc acc i (merge (nth acc i) entry))
                           (conj acc entry)))
                       [] (apply concat layers))]
    (vec (remove :hidden merged))))

(def ^:private var-re #"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")

(defn expand
  "`argv` with each `${VAR}` and `${VAR:-default}` filled from `env-fn`."
  [argv env-fn]
  (mapv (fn [a] (str/replace (str a) var-re
                             (fn [[_ v default]] (let [x (env-fn v)] (if (str/blank? (str x)) (str default) (str x))))))
        argv))

(defn installed?
  "Whether `program` is on the PATH, or a path that can run."
  [program]
  (try (zero? (:exit (fx/sh {:out :string :err :string} "sh" "-c" (str "command -v '" (str/replace (str program) "'" "") "'"))))
       (catch Throwable _ false)))

;; --- the tmux sessions -------------------------------------------------------------

(defn project-tag
  "What names this project's sessions: its directory's name, made safe for
  tmux, and a short hash of its whole path, so two projects of one name differ."
  [project-dir]
  (let [canon (try (.getCanonicalPath (io/file (str project-dir))) (catch Throwable _ (str project-dir)))
        base (str/replace (.getName (io/file canon)) #"[^A-Za-z0-9_-]" "-")]
    (str base "-" (format "%04x" (bit-and (hash canon) 0xffff)))))

(defn string-literal
  "`s` as a double-quoted string literal, `\\` and `\"` escaped: what elisp,
  Lua and JSON all read back as `s`."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn vars
  "What a catalogue entry's `${VAR}` reads (`expand`): `extra` first, then
  `BYJ_PROJECT` — this project's tag (`project-tag`), which names what is
  the project's own, as the emacs entry's daemon (byj-gly.451) — then the
  environment. `${VAR_STRING}` is `${VAR}` as a string literal
  (`string-literal`), for an argv that is code: a path put into elisp
  (byj-gly.453)."
  [project-dir & [extra]]
  (letfn [(lookup [v]
            (cond (contains? extra v) (get extra v)
                  (= "BYJ_PROJECT" v) (project-tag project-dir)
                  (and (str/ends-with? v "_STRING") (< 7 (count v)))
                  (some-> (lookup (subs v 0 (- (count v) 7))) string-literal)
                  :else (System/getenv v)))]
    lookup))

(defn session-name [project-dir program]
  (str "byj-term-" (project-tag project-dir) "-" (str/replace (str program) #"[^A-Za-z0-9_-]" "-")))

(defn running
  "This project's programs running in tmux now, `{name {:since epoch-s}}` —
  none when tmux is absent or has no server."
  [project-dir]
  (try
    (let [prefix (str (session-name project-dir "") "")
          {:keys [exit out]} (apply fx/sh {:out :string :err :string :env (host/attach-env)}
                                    (conj ah/*tmux* "list-sessions" "-F" (ah/tmux-format "#{session_name}" "#{session_created}")))]
      (if (zero? exit)
        (into {} (keep (fn [l] (let [[n created] (ah/tmux-fields l 2)]
                                 (when (str/starts-with? n prefix)
                                   [(subs n (count prefix)) {:since (some-> created str/trim parse-long)}]))))
              (str/split-lines (str out)))
        {}))
    (catch Throwable _ {})))

(defn start!
  "Start `entry` in a detached tmux session in `project-dir`: nil, or what
  went wrong."
  [project-dir entry]
  (let [argv (expand (:command entry) (vars project-dir))
        r (apply fx/sh {:out :string :err :string :env (host/attach-env)}
                 (into (conj ah/*tmux* "new-session" "-d" "-s" (session-name project-dir (:name entry))
                             "-c" (str project-dir))
                       argv))]
    (when-not (zero? (:exit r))
      (str "could not start " (:name entry) ": " (str/trim (str (:err r) (:out r)))))))

(defn stop!
  "Stop `program`'s tmux session: nil, or what went wrong."
  [project-dir program]
  (let [r (apply fx/sh {:out :string :err :string :env (host/attach-env)}
                 (conj ah/*tmux* "kill-session" "-t" (session-name project-dir program)))]
    (when-not (zero? (:exit r)) (str "could not stop " program ": " (str/trim (str (:err r) (:out r)))))))

(def ^:dynamic *editor-timeout-ms*
  "The longest an entry's call into its running program may take
  (`:open-file`, `:after-tools`, `:context`): an editor busy or stuck must
  not hold byj, nor a tool call, with it (byj-gly.453)."
  5000)

(defn run-bounded
  "Run `argv` in `project-dir`, in claude-host's environment, to its end or
  `timeout-ms` (`*editor-timeout-ms*` unless given), whichever is first:
  `jolt.process`'s result map, or `{:exit -1 :err \"timed out\"}` with the
  process tree killed. `brainyard.emacs` calls Emacs through it (RFC-067)."
  [project-dir argv & [timeout-ms]]
  (let [bound (or timeout-ms *editor-timeout-ms*)
        r (fx/run!! (apply fx/process-task {:out :string :err :string :dir (str project-dir) :env (host/attach-env)} argv)
                    bound)]
    (cond (contains? r :ok) (:ok r)
          (:timeout r) {:exit -1 :out "" :err (str "timed out after " bound " ms")}
          :else {:exit -1 :out "" :err (str (or (some-> (:err r) ex-message) "interrupted"))})))

(defn- running-with
  "The catalogue entries that have `k` and run now, in catalogue order."
  [project-dir k opts]
  (let [entries (filter k (catalogue project-dir opts))]
    (when (seq entries)
      (let [live (running project-dir)]
        (filter #(contains? live (:name %)) entries)))))

(defn open-file!
  "Open file `path` at `line` and `col` in a program of this project's that is
  running and says how (`:open-file`, an argv with `${FILE}`, `${LINE}` and
  `${COLUMN}`, run to its end and handed no terminal) — the emacs entry's
  `emacsclient -n`, which shows it in the frame in its tmux session, the
  screen left to byj (byj-gly.451). What to say, or nil when no such program
  runs and the caller opens it its own way. Takes `{:home}` as `catalogue`."
  [project-dir path line col & [opts]]
  (when-let [entry (first (running-with project-dir :open-file opts))]
    (let [argv (expand (:open-file entry)
                       (vars project-dir {"FILE" (str path) "LINE" (some-> line str) "COLUMN" (some-> col str)}))
          r (run-bounded project-dir argv)]
      (if (zero? (:exit r))
        (str "opened " path (when line (str ":" line)) " in " (:name entry))
        (str "could not open " path " in " (:name entry) ": " (str/trim (str (:err r) (:out r))))))))

(defn after-tools
  "Run the `:after-tools` argv of each program of this project's that runs
  and has one: what follows the agent's tools having run, which may have
  written files — the emacs entry reverts the buffers whose files changed
  and that hold no unsaved edits, so its frame never shows what the agent
  replaced (byj-gly.453). The names it ran for, and what failed, as
  `{:ran [name …] :failed {name why}}`. Takes `{:home}` as `catalogue`."
  [project-dir & [opts]]
  (reduce (fn [acc entry]
            (let [r (run-bounded project-dir (expand (:after-tools entry) (vars project-dir)))]
              (if (zero? (:exit r))
                (update acc :ran conj (:name entry))
                (assoc-in acc [:failed (:name entry)] (str/trim (str (:err r) (:out r)))))))
          {:ran [] :failed {}}
          (running-with project-dir :after-tools opts)))

(defn after-tools!
  "`after-tools`, off the caller's thread — the session's, which must not
  wait on an editor — and never failing it."
  [project-dir]
  (fx/run-detached (m/sp (try (m/? (fx/task-of #(after-tools project-dir)))
                              (catch Throwable _ nil)))
                   :terminal-after-tools))

(def max-context-chars
  "The most of a selected region `context!` returns: its start, when there is
  more."
  16000)

(defn context!
  "What the person is looking at in a program of this project's that runs and
  says how (`:context`, an argv that writes a JSON object to `${OUT}`, a file
  of byj's): the first such, as `{:program name :context {…}}` — the emacs
  entry's file, buffer, line, column, whether it has unsaved edits, and the
  selected region's text, clipped to `max-context-chars` — or `{:error}`;
  nil when none runs (byj-gly.453). Takes `{:home}` as `catalogue`."
  [project-dir & [opts]]
  (when-let [entry (first (running-with project-dir :context opts))]
    (let [out (java.io.File/createTempFile "byj-context-" ".json")]
      (try
        (let [r (run-bounded project-dir (expand (:context entry) (vars project-dir {"OUT" (.getPath out)})))
              text (when (zero? (:exit r)) (slurp out))]
          (cond
            (not (zero? (:exit r)))
            {:error (str "could not ask " (:name entry) ": " (str/trim (str (:err r) (:out r))))}
            (str/blank? text) {:error (str (:name entry) " said nothing")}
            :else
            (let [c (try (json/read-str text) (catch Throwable _ nil))]
              (if-not (map? c)
                {:error (str (:name entry) " said what is not a JSON object")}
                {:program (:name entry)
                 :context (cond-> c
                            (and (string? (:region c)) (> (count (:region c)) max-context-chars))
                            (update :region #(str (subs % 0 max-context-chars) "…")))}))))
        (finally (.delete out))))))

(def ^:dynamic *outer-tmux*
  "The tmux byj itself runs inside, as argv: plain `tmux`, which reaches it
  through `TMUX` — where `ah/*tmux*` runs with `TMUX` taken away. A test
  binds a server of its own (byj-gly.452)."
  ["tmux"])

(defn- outer [& args]
  (apply fx/sh {:out :string :err :string} (into *outer-tmux* args)))

(defn split-pane
  "The pane showing `program` beside byj — found by the `@byj-term` option
  `split!` sets on it, among the panes of the session `pane` is in — or nil."
  [project-dir program pane]
  (let [n (session-name project-dir program)
        ;; parted by a space, never a tab: tmux 3.2 and 3.3 print a tab in
        ;; a format as _ (byj-gly.450); neither field holds a space
        r (outer "list-panes" "-s" "-t" pane "-F" "#{pane_id} #{@byj-term}")]
    (when (zero? (:exit r))
      (some (fn [l] (let [[id tag] (str/split l #" " 2)] (when (= n (str/trim (str tag))) id)))
            (str/split-lines (str (:out r)))))))

(defn split!
  "Show `entry`'s running tmux session in a pane split beside `pane` — byj's
  own, in the tmux byj runs inside — attached from there, so the screen
  beside it stays byj's (byj-gly.452). The pane already showing it is
  selected instead of a second. The pane ends when its attach does; the
  program runs on in its session. The entry's `:split` (:h, the default, or
  :v) and `:split-size` (tmux's `-l`) say how. `{:pane id :new? bool}`, or
  `{:error}`."
  [project-dir entry pane]
  (let [n (session-name project-dir (:name entry))]
    (if-let [id (split-pane project-dir (:name entry) pane)]
      (do (outer "select-window" "-t" id)
          (outer "select-pane" "-t" id)
          {:pane id :new? false})
      (let [r (apply outer (concat ["split-window" (if (= :v (:split entry)) "-v" "-h")
                                    "-P" "-F" "#{pane_id}" "-t" pane "-c" (str project-dir)]
                                   (when-let [l (:split-size entry)] ["-l" (str l)])
                                   ;; attached from inside tmux, so TMUX goes,
                                   ;; as for every attach of this layer
                                   ["env" "-u" "TMUX"] ah/*tmux* ["attach-session" "-t" n]))]
        (if (zero? (:exit r))
          (let [id (str/trim (str (:out r)))]
            (outer "set-option" "-p" "-t" id "@byj-term" n)
            {:pane id :new? true})
          {:error (str "could not open " (:name entry) " beside byj: " (str/trim (str (:err r) (:out r))))})))))

(defn attach-plan
  "How to open `program` now: `{:argv :env :hint}` — the attach, in
  claude-host's environment, with the nested-tmux hint on its status line
  when this runs inside tmux — or, with no tmux, the program itself in the
  foreground: `{:argv :foreground? true}`. Run inside tmux, from `pane`, it
  is also `:split pane`: open it beside byj (`split!`), the attach being what
  is left when that fails — unless the entry says `:open :attach`
  (byj-gly.452)."
  [project-dir entry tmux? nested-prefix & [pane]]
  (if tmux?
    (let [n (session-name project-dir (:name entry))]
      (cond-> {:argv (conj ah/*tmux* "attach-session" "-t" n) :env (host/attach-env)}
        nested-prefix (assoc :hint (host/hint-argv n nested-prefix))
        (and pane (not= :attach (:open entry))) (assoc :split pane)))
    {:argv (expand (:command entry) (vars project-dir)) :foreground? true}))

;; --- driving one: what terminal$send and terminal$capture do (byj-gly.417) ---------

(def max-capture-chars
  "The most of a screen terminal$capture returns: the newest, when there is more."
  16000)

(defn send!
  "Type into `program`'s tmux session: `text` as it is (`send-keys -l`), then
  Enter unless `enter?` is false; or `keys`, tmux's names for keys (`C-c`,
  `Up`, `q`), in order. nil, or what went wrong."
  [project-dir program {:keys [text enter? keys]}]
  (let [n (session-name project-dir program)
        run (fn [& args] (apply fx/sh {:out :string :err :string :env (host/attach-env)}
                                (into (conj ah/*tmux* "send-keys" "-t" n) args)))
        results (cond
                  (seq keys) [(apply run keys)]
                  :else (cond-> [(run "-l" (str text))]
                          (not (false? enter?)) (conj (run "Enter"))))
        bad (first (remove #(zero? (:exit %)) results))]
    (when bad (str "could not send to " program ": " (str/trim (str (:err bad) (:out bad)))))))

(defn capture!
  "What `program`'s screen shows — or, with `lines`, that many lines back
  from its bottom, scrollback included — as `{:screen}`, its newest
  `max-capture-chars`, trailing blank lines dropped; or `{:error}`."
  [project-dir program & [{:keys [lines]}]]
  (let [n (session-name project-dir program)
        r (apply fx/sh {:out :string :err :string :env (host/attach-env)}
                 (cond-> (conj ah/*tmux* "capture-pane" "-p" "-t" n)
                   (and lines (pos? lines)) (into ["-S" (str "-" lines)])))]
    (if (zero? (:exit r))
      (let [screen (str/replace (str (:out r)) #"\s+$" "")]
        {:screen (if (> (count screen) max-capture-chars)
                   (str "…" (subs screen (- (count screen) max-capture-chars)))
                   screen)})
      {:error (str "could not read " program ": " (str/trim (str (:err r) (:out r))))})))

(defonce ^:private tmux-cache (atom nil))

(defn tmux-present?
  "Whether tmux is on the PATH, asked once a run: the tools are offered only
  where they can work, and the registry is built every turn."
  []
  (let [v @tmux-cache]
    (if (nil? v) (reset! tmux-cache (host/tmux?)) v)))

;; --- the agent's tools (byj-gly.417) -----------------------------------------------

(def ^:private err [:map [:error :string]])

(defn commands
  "`terminal$list`, `terminal$start`, `terminal$stop`, `terminal$send` and
  `terminal$capture`: the Terminal view's programs, for the session's own
  agent (registered behind `:terminal-tools?`, where tmux is). They reach this
  project's programs alone — tmux sessions named `byj-term-<project>-…` —
  never another tmux session. `terminal$send` types into a program, which in
  a shell is running commands, so it carries `:terminal/input`, which
  `:tool-ask-effects` asks about by default."
  [config]
  (let [dir (or (:project-dir config) (:working-dir config) ".")]
    [{:name "terminal$list" :kind :command
      :description (str "The terminal programs of this project: each one the Terminal view offers (a shell, editors, htop, the event log, the project's own)"
                        " with whether it is installed and whether it is running in its tmux session, for how long; and any of this project's"
                        " programs running that the catalogue does not name. A running one outlives this session.")
      :input-schema [:map]
      :output-schema [:map [:programs [:vector [:map [:name :string] [:running :boolean] [:installed :boolean]
                                                  [:uptime-s {:optional true} [:maybe :int]]
                                                  [:command {:optional true} [:maybe :string]]
                                                  [:description {:optional true} [:maybe :string]]]]]]
      :effects #{}
      :fn (fn [_ _ctx]
            (let [entries (catalogue dir)
                  live (running dir)
                  now (quot (System/currentTimeMillis) 1000)
                  row (fn [{:keys [name command description]}]
                        (let [r (get live name)]
                          (cond-> {:name name :running (boolean r)
                                   :installed (if command (installed? (first (expand command (vars dir)))) true)}
                            r (assoc :uptime-s (some->> (:since r) (- now)))
                            command (assoc :command (str/join " " command))
                            description (assoc :description description))))]
              {:programs (vec (concat (map row entries)
                                      (map #(row {:name %}) (remove (set (map :name entries)) (keys live)))))}))}
     {:name "terminal$start" :kind :command
      :description (str "Start one of terminal$list's programs that is not running, by name, in a tmux session of its own in the project directory."
                        " It keeps running after this session ends until terminal$stop stops it. Use terminal$send to type into it and"
                        " terminal$capture to read its screen. Only what the catalogue names (.brainyard/terminal.edn adds more).")
      :input-schema [:map [:name [:string {:desc "The program's name, as terminal$list gives it."}]]]
      :output-schema [:or [:map [:started :string]] err]
      :effects #{:process}
      :fn (fn [{:keys [name]} _ctx]
            (let [entry (first (filter #(= name (:name %)) (catalogue dir)))]
              (cond
                (nil? entry) {:error (str "no program " name " in the catalogue — terminal$list names them")}
                (contains? (running dir) name) {:error (str name " is already running")}
                (not (installed? (first (expand (:command entry) (vars dir)))))
                {:error (str name " is not installed")}
                :else (if-let [problem (start! dir entry)] {:error problem} {:started name}))))}
     {:name "terminal$stop" :kind :command
      :description "Stop a running terminal program of this project, by name: its tmux session ends, and whatever runs in it with it."
      :input-schema [:map [:name [:string {:desc "The program's name."}]]]
      :output-schema [:or [:map [:stopped :string]] err]
      :effects #{:process}
      :fn (fn [{:keys [name]} _ctx]
            (if-not (contains? (running dir) name)
              {:error (str name " is not running")}
              (if-let [problem (stop! dir name)] {:error problem} {:stopped name})))}
     {:name "terminal$send" :kind :command
      :description (str "Type into a running terminal program: :text as it is, then Enter unless :enter is false — in a shell, a command"
                        " it runs; or :keys, tmux's names for keys in order (C-c to interrupt, Up, Escape, q). Read what it did with"
                        " terminal$capture.")
      :input-schema [:map
                     [:name [:string {:desc "The program's name."}]]
                     [:text {:optional true} [:string {:desc "Typed as it is."}]]
                     [:enter {:optional true} [:boolean {:desc "Press Enter after :text (default true)."}]]
                     [:keys {:optional true} [:vector [:string {:desc "A tmux key name: C-c, Enter, Up, Escape, q."}]]]]
      :output-schema [:or [:map [:sent :string]] err]
      :effects #{:process :terminal/input}
      :fn (fn [{:keys [name text enter keys]} _ctx]
            (cond
              (not (contains? (running dir) name)) {:error (str name " is not running — terminal$start starts it")}
              (and (str/blank? (str text)) (empty? keys)) {:error "give :text or :keys"}
              :else (if-let [problem (send! dir name {:text text :enter? enter :keys keys})]
                      {:error problem}
                      {:sent name})))}
     {:name "terminal$capture" :kind :command
      :description (str "What a running terminal program's screen shows now; with :lines, that many lines back from the bottom,"
                        " scrollback included. The newest part, when there is more than fits.")
      :input-schema [:map
                     [:name [:string {:desc "The program's name."}]]
                     [:lines {:optional true} [:int {:desc "How many lines back to read, scrollback included."}]]]
      :output-schema [:or [:map [:screen :string]] err]
      :effects #{}
      :fn (fn [{:keys [name lines]} _ctx]
            (if-not (contains? (running dir) name)
              {:error (str name " is not running")}
              (capture! dir name {:lines lines})))}
     {:name "terminal$context" :kind :command
      :description (str "What the person is looking at in their editor, when one of this project's terminal programs that can say runs"
                        " (an entry of .brainyard/terminal.edn with :context; for Emacs, emacs$context): the file and buffer, the line and"
                        " column of the cursor, whether it has unsaved edits, and the text they selected, if any. Ask when they say"
                        " \"this\", \"here\" or \"the selection\" about code they are looking at.")
      :input-schema [:map]
      :output-schema [:or [:map [:program :string] [:context [:map-of :keyword :any]]] err]
      :effects #{}
      :fn (fn [_ _ctx]
            (or (context! dir)
                {:error "no terminal program of this project is running that can say what it shows — for Emacs, emacs$context"}))}]))

;; --- the view ----------------------------------------------------------------------

(defn- uptime [since now-s]
  (when since
    (let [s (max 0 (- now-s since))]
      (cond (< s 3600) (str (quot s 60) "m")
            (< s 86400) (format "%dh%02dm" (quot s 3600) (quot (mod s 3600) 60))
            :else (str (quot s 86400) "d")))))

(defn rows
  "The view's rows for a read `s` (`{:entries :running :installed :tmux?}`):
  Running — what runs now, with its uptime — then Start, the catalogue, a
  program not installed dimmed."
  [{:keys [entries running installed tmux?]} now-s]
  (let [by-name (into {} (map (juxt :name identity)) entries)
        live (sort (keys running))]
    (vec (concat
          (when (seq live)
            (cons {:label "▾ Running" :hint (str (count live)) :section :running}
                  (for [n live]
                    {:label (str "  ● " n) :hint (uptime (:since (get running n)) now-s)
                     :entry (or (by-name n) {:name n}) :running? true :style {:color :green}})))
          [{:label (str "▾ Start" (when-not tmux? " · no tmux: runs in the foreground")) :section :start}]
          (for [e entries
                :let [ok? (contains? installed (:name e))]]
            {:label (str "  " (if ok? "○ " "· ") (:name e))
             :hint (if ok? (:description e) "not installed")
             :entry e :installed? ok?
             :style (when-not ok? {:dim true})})))))

(defn views
  "The Terminal view for `project-dir`: `{:view v :refresh! (fn [])}`. `hand!`
  runs an argv with the terminal (`tui/hand-over!`, (fn [argv env])), `say!`
  puts a line on the screen. Enter on a program starts it when it is not
  running and opens it — beside byj when byj runs inside tmux (`split!`,
  byj-gly.452), else with the terminal; ctrl+x on a running one, pressed
  twice, stops it. `pane` says byj's own tmux pane, or nil outside tmux;
  `prefix` the prefix of the tmux byj runs inside (`host/outer-prefix`), or
  nil — both read from the process's environment unless given, so a test
  run inside tmux sees only what it injects (byj-gly.455). `cell` as for
  `project-views/views`."
  [project-dir & [{:keys [cell hand! say! pane prefix]
                   :or {cell atom
                        pane #(when (System/getenv "TMUX") (System/getenv "TMUX_PANE"))
                        prefix host/outer-prefix}}]]
  (let [state (cell {:reading? true})
        armed (atom nil)
        read (fn []
               (let [entries (catalogue project-dir)
                     tmux? (host/tmux?)]
                 {:entries entries
                  :tmux? tmux?
                  :running (if tmux? (running project-dir) {})
                  :installed (into #{} (keep #(when (installed? (first (expand (:command %) (vars project-dir)))) (:name %)))
                                   entries)}))
        refresh! (fn []
                   (fx/run-detached
                    (m/sp (try (reset! state (m/? (fx/task-of read)))
                               (catch Throwable e
                                 (when-not (fx/cancelled? e)
                                   (reset! state {:error (or (ex-message e) (str e))})))))
                    :terminal))
        say (fn [s] (when (and say! s) (say! s)))]
    {:refresh! refresh!
     :state state
     :view {:id "terminal" :title "Terminal"
            :items (fn [] (let [s @state]
                            (cond (:error s) [{:label (str "  could not read: " (:error s))}]
                                  (nil? (:entries s)) [{:label "  reading…"}]
                                  :else (rows s (quot (System/currentTimeMillis) 1000)))))
            :act (fn [{:keys [entry running? installed?]}]
                   (when (and entry hand!)
                     (let [s @state]
                       (if (and (not running?) (not installed?))
                         (say (str (:name entry) " is not installed"))
                         (let [problem (when (and (:tmux? s) (not running?)) (start! project-dir entry))]
                           (if problem
                             (say problem)
                             (let [prefix (prefix)
                                   {:keys [argv env hint split]} (attach-plan project-dir entry (:tmux? s) prefix (pane))]
                               (when hint (apply fx/sh {:out :string :err :string :env (host/attach-env)} hint))
                               (if-let [{:keys [error new?]} (when split (split! project-dir entry split))]
                                 (if error
                                   (do (say error) (hand! argv env))
                                   ;; short: the footer is the centre's width,
                                   ;; and the split has just halved it
                                   (say (str (:name entry) (if new? " opened beside" " beside, selected")
                                             (when prefix (str " · " prefix " o switches · "
                                                               prefix " " prefix " d closes")))))
                                 (hand! argv env))
                               (refresh!))))))))
            ;; ctrl+x on a running program: once to arm, again to stop it
            :on-key (fn [{:keys [entry running?]} e]
                      (when (and running? (= :ctrl (:type e)) (= \x (:ch e)))
                        (if (= (:name entry) @armed)
                          (do (reset! armed nil)
                              (say (or (stop! project-dir (:name entry)) (str "stopped " (:name entry))))
                              (refresh!))
                          (do (reset! armed (:name entry))
                              (say (str "ctrl+x again stops " (:name entry)))))
                        true))
            :on-show refresh!}}))
