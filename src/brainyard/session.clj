;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.session
  "brainyard's multi-turn `by run`, on jolt: an OUTER behavior tree
  (resources/brainyard/loops/session.edn) that reads input, handles slash
  commands, and runs one nested ReAct loop per agent turn.

  The session's st-memory holds only session state — :previous-turns, the
  rendered :history-context, running :usage — and each turn's ReAct loop runs
  in its own :scope, so the two loops share no keys. After a turn the whole
  inner memory arrives here as :turn and `end-turn` folds it into history,
  after brainyard's `previous-turns/append-turn` (recent turns keep their
  last tool calls; older ones keep only Q and A).

  I/O goes through the ctx, so tests and other front ends can drive it:
    :read-input  (fn [prompt] line-or-nil)   nil = end of input
    :emit        (fn [kind text])            kind ∈ :answer :info :error"
  (:require [clojure.edn :as edn]
            [brainyard.clock :as clock]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [brainyard.agent :as agent]
            [brainyard.agents :as agents]
            [brainyard.auth :as auth]
            [brainyard.bt :as bt]
            [brainyard.channel :as channel]
            [brainyard.llm :as llm]
            [brainyard.mcp :as mcp]
            [brainyard.secrets :as secrets]
            [brainyard.mcp-registry :as mcp-registry]
            [brainyard.todo :as todo]
            [brainyard.config :as config]
            [brainyard.configbox :as configbox]
            [brainyard.context :as context]
            [brainyard.effect :as fx]
            [brainyard.events :as events]
            [brainyard.history :as history]
            [brainyard.hooks :as hooks]
            [brainyard.links :as links]
            [brainyard.memory :as memory]
            [brainyard.permission :as permission]
            [brainyard.share-tools :as share-tools]
            [brainyard.persist :as persist]
            [brainyard.proposals :as proposals]
            [brainyard.project-loops :as project-loops]
            [brainyard.tool-distill :as tool-distill]
            [brainyard.toolbox :as toolbox]
            [brainyard.schema :as schema]
            [brainyard.toolshelf :as toolshelf]
            [brainyard.tools :as tools]
            [brainyard.claude-agent :as claude-agent]
            [brainyard.claude-usage :as claude-usage]
            [brainyard.codex-usage :as codex-usage]
            [brainyard.schedule :as schedule]
            [brainyard.init :as init]
            [brainyard.self-improve :as self-improve]
            [brainyard.share :as share]
            [brainyard.tasks :as tasks]
            [brainyard.changes :as changes]
            [brainyard.coach :as coach]
            [brainyard.emacs :as emacs]
            [brainyard.terminal :as terminal]
            [brainyard.trajectory :as trajectory]
            [brainyard.teams :as teams]
            [brainyard.update :as update]
            [brainyard.version :as version]))

(def default-config
  "The session's keys and their defaults live in `brainyard.config/config-items`
  (RFC-013) like every other key — a tree reads `:config/max-turns` from the
  merged config, so a second map here meant four real keys had no BY_* name, no
  `--print-config` row, and were reported as UNKNOWN in a config.edn that set
  them (byj-gly.64). This is the schema's view of them, not a second source."
  (select-keys config/defaults
               [:max-turns :max-previous-turns :full-depth
                :max-question-chars :max-answer-chars :persist-session?]))

(def session-loop "brainyard/loops/session.edn")

(defn- mem [ctx] @(:st-memory ctx))

(declare allow-path! share-command note! command-file-error)


(defn- record!
  "Keep a line in the session's own transcript. The renderer has one too, in
  whatever shape it paints — this is the kernel's, so `/capture` works the same
  under the full-screen renderer and a pipe.

  A window, not a ledger, of `:transcript-max-lines`: the turns themselves are
  in the session snapshot (RFC-016) and every turn is in its trajectory
  (RFC-025); this is the conversation as it was spoken, for a bug report and
  for a resumed screen."
  [ctx kind text]
  (when (some? text)
    (swap! (:st-memory ctx) update :transcript
           (fn [lines]
             ;; a tool call is kept as data, so a resumed front end can draw it
             ;; again the way it drew it live; everything else is what was said
             (let [lines (conj (vec lines) [kind (if (map? text) text (str text))])
                   cap (max 1 (get-in ctx [:config :transcript-max-lines] 10000))]
               (if (> (count lines) cap)
                 (subvec lines (- (count lines) cap))
                 lines))))))

(defn- emit [ctx kind text]
  (record! ctx kind text)
  ((:emit ctx) kind text))

(def ^:private console-words
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/console-activity.edn")))))

(def ^:private console-heading (delay (:heading @console-words)))

(defn- set-turns
  "The previous turns, and the history block a turn is given from them — with
  the tools the person called directly since (`:console-activity`, byj-gly.273.3)
  under them, brainyard's console activity."
  [m turns config]
  (let [h (history/format-previous-turns turns config)
        console (seq (:console-activity m))]
  (assoc m :previous-turns turns
           :history-context (if console
                              (str/join "\n" (concat (when (seq h) [h ""]) [@console-heading] (map #(str "- " %) console)))
                              h))))

;; --- leaves ----------------------------------------------------------------------

(def commands
  "The session's slash commands as data, so a front end can complete them and
  `help-text` can be rendered from the same list — a command the help forgot is
  a command nobody finds (`byj-gly.100`). `/exit` is `/quit`'s alias and is not
  listed, as brainyard does not list it either."
  [["/quit" "end the session (also Ctrl-D)"]
   ["/clear" "start over with empty history"]
   ["/rewind" "put the files back as they were at a turn, kept first so it can be taken back; [TURN [end] | undo [N]]"]
   ["/undo-turn" "take back what one turn changed, alone, keeping what came after; TURN"]
   ["/fork" "a new session with this one's conversation before a turn, the files put back to it too; [TURN [keep-files]]"]
   ["/history" "show the conversation so far"]
   ["/compact" "summarize the earlier turns into one, to make room; [KEEP] turns kept whole"]
   ["/usage" "token and call totals, and the Claude Code subscription's windows; [N] [--breakdown]"]
   ["/doctor" "where this machine stands: the tools, the project, Claude Code's sign-in and its subscription's use"]
   ["/loop" "run a prompt now and again: [5m] <prompt> | <prompt> every 20m — recurring; no interval — the agent paces itself"]
   ["/schedule" "the prompts set to a time for this session; [remove ID]"]
   ["/memory" "the project's memories, to search, read, edit and delete; [QUERY | show KEY | edit KEY | delete KEY]"]
   ["/capture" "write what was said to a file; [PATH]"]
   ["/task" "background work; [list | ID | cancel ID]"]
   ["/todo" "the checklist that outlives a turn; [SLUG]"]
   ["/model" "what this session asks, and a picker to change it; [N | NAME | PROVIDER/MODEL]"]
   ["/proposals" "what self-improvement proposed — skills, tools, params; [KIND | show ID | accept ID [user] | reject ID]"]
   ["/mcp" "the MCP servers: find, add, remove, start, stop, enable, hide or show their tools; [NAME [ACTION]]"]
   ["/tools" "every tool, with its arguments, effects and how to call it; [FILTER | NAME]"]
   ["/status" "this session: its name and id, the model and its credentials, the settings in force"]
   ["/config" "the settings by family, with an editor; [FAMILY | KEY [VALUE] | unset KEY]"]
   ["/allow-path" "let tools reach a directory for this session, not saved; [DIR]"]
   ["/share" "who watches this shared session, and where; [start [REF…|team] | stop | pause | resume | kick USER | record on|off [REF] | allow | refuse | WORDS]"]
   ["/init" "prepare this project for brainyard: tools, AGENTS.md, beads; [check | WORDS]"]
   ["/login" "sign in: claude, codex, gh, bedrock, or an API-key provider; [TARGET]"]
   ["/logout" "sign out of a target; TARGET"]
   ["/help" "this list"]])

(def help-text
  (str/join "\n" (for [[cmd doc] commands]
                    (str cmd (apply str (repeat (max 1 (- 10 (count cmd))) " ")) doc))))

(defn- session-view
  "The session as a hook body sees it (byj-gly.334): brainyard hands its
  handlers the session atom, a Tool Lisp body takes data — so what the session
  is now, as a value (divergence `session-hook-payload-is-a-value`)."
  [{:keys [st-memory config]}]
  (let [m @st-memory]
    (cond-> {:session-id (:session-id config)
             :project-dir (some-> (:project-dir config) str)
             :working-dir (some-> (:working-dir config) str)
             :turn-count (:turn-count m 0)
             :usage (:usage m)}
      (:resumed-from m) (assoc :resumed-from (:resumed-from m))
      (:conversation-title m) (assoc :conversation-title (:conversation-title m)))))

(defn- fire-session!
  "brainyard's `:agent.session/*` hook events (core/session.clj), through the
  session's hook registry: `created` when a session starts fresh, `resumed`
  when it starts from its snapshot, `closed` when it ends."
  [ctx event]
  (hooks/fire! ctx event {:session-id (:session-id (:config ctx)) :session (session-view ctx)}))

(declare restore-or-start!)

(defn ^{:reads #{} :writes #{:ended :history-context :previous-turns :transcript :turn-count :usage
                              :skill-distill-tally :skill-distill-candidates :console-activity :resumed-from}}
  init
  "Start empty, or from the snapshot `:resume` names (RFC-016), then tell the
  hooks which it was (byj-gly.334)."
  [{:keys [st-memory] :as ctx}]
  (restore-or-start! ctx)
  (fire-session! ctx (if (:resumed-from @st-memory) :agent.session/resumed :agent.session/created)))

(defn- restore-or-start!
  "Start empty, or from the snapshot `:resume` names (RFC-016). A snapshot that
  cannot be read is reported and the session starts fresh: losing history is
  recoverable, refusing to start is not."
  [{:keys [st-memory config] :as ctx}]
  (swap! st-memory merge
         {:previous-turns [] :history-context nil :ended false
          :turn-count 0 :usage {:input-tokens 0 :output-tokens 0 :llm-calls 0}
          ;; self-improvement's window (RFC-017): a turn is counted and, when it
          ;; is a candidate, kept, until a window is judged
          :skill-distill-tally 0 :skill-distill-candidates []
          ;; the tools the person called directly since the last turn (byj-gly.273.3)
          :console-activity []})
  (when-let [id (:resume config)]
    (let [snap (persist/read-session config id)]
      (if-let [problem (:problem snap)]
        (emit ctx :error (str "could not resume " id ": " problem " — starting fresh"))
        (do (swap! st-memory
                   (fn [m] (let [restored (persist/restore snap)]
                             (-> (merge m restored)
                                 ;; the rendered history is derived, never restored
                                 (set-turns (:previous-turns restored) config)
                                 ;; what the hooks are told it was (byj-gly.334)
                                 (assoc :resumed-from id)))))
            ;; a model /model switched to is asked again, and the box is what
            ;; the next call and the footer's rule both read
            ;; — unless this run named one with -p/-m, which is the later
            ;; choice; then say so, rather than ask another model silently
            ;; (byj-gly.232)
            ;; what was said before, for a front end to put back on the screen
            ;; — brainyard replays its scrollback's tail on resume. BEFORE
            ;; every note about this resume, so they land under the history it
            ;; resumes rather than alone on an empty screen first (byj-gly.286)
            (events/emit! ctx :session/resumed
                          (assoc (select-keys @st-memory [:transcript :turn-count :usage :conversation-title]) :id id
                                 ;; offered again in the empty input line (byj-gly.280.4)
                                 :next-user-prompt (:next-user-prompt snap)))
            ;; the notes are about THIS resume, so they are said and not
            ;; recorded: recorded, each resume's came back with the next one,
            ;; and the lines piled up — two "↻ resumed" a run (byj-gly.286)
            (let [note (fn [kind text] ((:emit ctx) kind text))]
              (when-let [label (:model @st-memory)]
                (cond
                  (:lm-flagged? ctx)
                  (when (not= label (llm/lm-label (agent/current-lm ctx)))
                    (note :info (str "keeping " (llm/lm-label (agent/current-lm ctx)) ", as the command line asked — "
                                     "this session last used " label " (/model " label " to go back to it)")))
                  :else
                  (if-let [lm (and (:lm-box ctx)
                                   (llm/parse-lm-label label (or (:env-fn ctx) #(System/getenv %))))]
                    (reset! (:lm-box ctx) lm)
                    (note :error (str "could not ask " label " again — keeping " (llm/lm-label (agent/current-lm ctx)))))))
              (note :info (persist/describe snap)))
            ;; a team it led is still open, its members gone with the process
            ;; that ran them (RFC-032)
            (when-let [t (teams/current-team config)]
              (let [n (count (remove :closed? (:members t)))]
                (when (pos? n)
                  (emit ctx :info (str "this session leads team " (:id t) " (" n " member(s), stopped with the last run)"
                                       " — ask to bring it back (team$resume) or close it (team$close)"))))))))))

(defn ^{:reads #{} :writes #{:eof? :input :tool-rows-shown}}
  read-input [ctx]
  ;; waiting again: a request the session channel handed over and that never
  ;; became a turn — a slash command, an empty line — has no answer (RFC-031)
  (some-> (:channel ctx) channel/settle-unanswered!)
  ;; the tools, to the renderer that completes their names and arguments
  ;; (byj-gly.273.4) — again only when the registry changed: an MCP server's
  ;; tools arrive after its start, and a user tool with tool$create
  (when-let [present (:present ctx)]
    (let [reg (some-> (:tools ctx) deref)
          k (hash (sort (keys reg)))]
      (when (and reg (not= k (:tool-rows-shown (mem ctx))))
        (swap! (:st-memory ctx) assoc :tool-rows-shown k)
        (present :tool-rows {:rows (toolshelf/rows ctx)}))))
  (let [line ((:read-input ctx) "you> ")]
    (swap! (:st-memory ctx) assoc :input (some-> line str/trim) :eof? (nil? line))))

(defn ^{:reads #{:eof?} :writes #{}}
  eof? [ctx] (:eof? (mem ctx)))
(defn ^{:reads #{:input} :writes #{}}
  blank? [ctx] (str/blank? (:input (mem ctx))))
(declare tool-line needs-usage? agent-command command-tools)

(defn ^{:reads #{:input} :writes #{}}
  command?
  "A `/` line the command path takes: every one but `/<tool> args` naming a
  registered tool, which is a turn (byj-gly.273.2) — unless the tool needs
  arguments and was given none, whose usage the command path prints — and a
  command that stands for an agent whose tool is there, which is a turn too
  (`command-request`, byj-gly.350)."
  [ctx]
  (let [input (:input (mem ctx))]
    (and (str/starts-with? input "/")
         ;; `/loop <prompt>` is a turn, its input rewritten (`loop-request`);
         ;; a bare /loop is the command that says how to use it (byj-gly.325)
         (not (re-find #"^/loop\s+\S" input))
         (not (when-let [[e words] (agent-command (:config ctx) input)]
                (and (command-tools ctx e) (or (seq words) (:default-request e)))))
         (let [[tool args] (tool-line ctx input)]
           (or (nil? tool) (needs-usage? tool args))))))
(defn ^{:reads #{:ended} :writes #{}}
  ended? [ctx] (:ended (mem ctx)))

(declare direct-call?)

(defn ^{:reads #{:input} :writes #{}}
  channel-command?
  "A command or a direct tool call that the session channel handed over — from
  another session, a team leader, an agent's `session$send` — rather than one
  the person typed (byj-gly.282). It reached the command dispatch like a typed
  line, so a peer could run `/mcp add NAME -- COMMAND` here, which starts a
  process no gate sees, or a `:<tool>` call this session's person would be
  asked to approve. A `/<tool> args` line is not one: that is a turn, and the
  model's calls in it are gated."
  [ctx]
  (boolean (and (some-> (:channel ctx) channel/from-channel?)
                (or (command? ctx) (direct-call? ctx)))))

(defn ^{:reads #{:input} :writes #{}}
  refuse-channel-command
  "Refuse a command the session channel handed over: the sender is told why,
  and so is the person, under the line it came as. Nothing runs."
  [ctx]
  (let [line (str/trim (str (:input (mem ctx))))
        reason (str "not run: " line " — a command or a direct tool call is the person's to type;"
                    " over the session channel, ask a question instead")]
    (channel/refuse! (:channel ctx) reason)
    (emit ctx :info (str "⇠ refused over the session channel: " line))))

(def ^:private direct-line #"^:([A-Za-z_][\w$.\-]*)(?:\s|$)")

(defn ^{:reads #{:input} :writes #{}}
  direct-call?
  "A `:<tool> …` line: a tool called directly, not a question (byj-gly.273.3).
  `:)` and the like are not — only a colon and a name."
  [ctx]
  (boolean (re-find direct-line (str (:input (mem ctx))))))

(defn ^{:reads #{} :writes #{:ended}}
  end [ctx]
  (swap! (:st-memory ctx) assoc :ended true))

(declare rebuild-ctx!)

(defn- proposals-report
  "`/proposals [KIND] | show ID | accept ID [user] | reject ID` — the one review
  gate of self-improvement (byj-gly.249.1): a skill distilled or refined
  (RFC-017), predictor params compiled (RFC-021), a user tool distilled
  (RFC-010), a candidate loop (RFC-071). The model may list and read what
  was proposed; making one live, or dropping it, is the person's, here —
  brainyard's rule that nothing it drafts goes live unreviewed, kept by not
  giving the proposer the key. What
  is accepted is in force from the next turn: the tools and the prompt's
  listings are built again — but a loop from the next session, as a session
  expands its tree when it starts."
  [{:keys [config] :as ctx} [sub arg scope]]
  (let [line (fn [{:keys [id summary score]}]
               (str "  " id (when score (format " · %.2f" (double score)))
                    (when (seq summary) (str "\n    " summary))))]
    (case sub
      "show"
      (let [p (proposals/read-one config arg)]
        (or (:error p) (str (line p) "\n\n" (:body p))))

      "accept"
      (let [r (proposals/accept! config arg {:scope scope})]
        (if-let [e (:error r)]
          e
          (do (when-let [box (:ctx-box ctx)]
                (let [now (merge ctx @box)] (rebuild-ctx! now (:config now))))
              ;; a loop is expanded when a session starts: one accepted
              ;; now is not the procedure of this session (RFC-071)
              (str (or (:said r) (str "accepted " (:id r)))
                   (if (= :next-session (:in-force r))
                     " — in force from the next session; this one keeps the loop it started with"
                     " — in force from the next turn")))))

      "reject"
      (let [r (proposals/reject! config arg)]
        (or (:error r) (str "dropped proposal `" (:id r) "`")))

      ;; nothing, `list`, or a kind: the list
      (let [kind (when-not (= "list" sub) sub)
            ps (when-not (and kind (not (contains? (proposals/kinds) (keyword kind))))
                 (proposals/list-all config kind))]
        (cond
          (nil? ps)
          (str "no kind of proposal named '" kind "' — "
               (str/join ", " (map name (keys (proposals/kinds)))) ", or show / accept / reject ID")
          (empty? ps)
          (str "nothing proposed" (when kind (str " of kind " kind))
               (when-not (or (:enable-skill-distillation config) (:enable-skill-refinement config))
                 " — skill self-improvement is off (:enable-skill-distillation, :enable-skill-refinement)"))
          :else
          (str/join "\n" (concat [(str (count ps) " proposal(s), none live until accepted:")]
                                 (map line ps)
                                 ["/proposals show ID · /proposals accept ID [user] · /proposals reject ID"])))))))

(defn- fill-words [template m]
  (reduce-kv (fn [t k v] (str/replace t (str "{" (name k) "}") (str v))) template m))

(defn- file-list [files]
  (let [n (count files)]
    (if (> n 8) (str (str/join ", " (take 8 files)) " and " (- n 8) " more") (str/join ", " files))))

(declare rewound!)

(defn- rewind-command
  "`/rewind [TURN [end] | undo [N]]` and `/undo-turn TURN` (RFC-069): the
  session's files put back, by `brainyard.changes`; what was replaced is
  kept first as a rewind ref. The next turn is told, as a line of the
  person's console activity in the prompt resource's words, and the event
  bus gets `:session/rewind`. `{:text}` or `{:error}`."
  [{:keys [st-memory config task-registry] :as ctx} cmd [a b]]
  (let [id (str (:session-id config))
        busy (when task-registry (seq (filter #(= :running (:status %)) (tasks/summaries task-registry))))
        turn (some-> a parse-long)
        usage (if (= cmd "/undo-turn")
                "usage: /undo-turn TURN — /rewind lists the turns"
                "usage: /rewind [TURN [end] | undo [N]] — with no turn, the turns kept")]
    (cond
      (str/blank? id) {:error "this session has no id, so it keeps no turns"}
      (and (= cmd "/rewind") (nil? a))
      (let [rows (changes/rows config id)]
        (if (empty? rows)
          {:text (or (:error (changes/diff config id)) "no turns kept yet: each turn is, as it ends")}
          {:text (str/join "\n" (concat (map changes/row-line rows)
                                          ["" (str "/rewind TURN puts the files back as they were when that turn began (end: as it ended); "
                                                   "/undo-turn TURN takes back that turn alone; /rewind undo takes back the last.")]))}))
      busy {:error (str "not while background work runs (" (str/join ", " (map :id busy)) "): it may be writing these files — /task cancel ID first")}
      (and (= cmd "/rewind") (= a "undo") (or (nil? b) (parse-long b)))
      (let [r (changes/undo-rewind! config id (some-> b parse-long))]
        (or (when (:error r) r)
            (rewound! ctx :undo-rewind r (str "took back " (str/replace (:undid r) #"^refs/byj/rewinds/[^/]+/" "rewind ") ": "))))
      (nil? turn) {:error usage}
      (= cmd "/undo-turn")
      (let [r (changes/undo-turn! config id turn)]
        (or (when (:error r) r)
            (rewound! ctx :undo-turn r (str "took back turn " turn "'s change: "))))
      (or (nil? b) (= b "end"))
      (let [end? (= b "end")
            r (changes/rewind! config id turn end?)]
        (or (when (:error r) r)
            (rewound! ctx :rewind r (str "put the files back as they were when turn " turn (if end? " ended" " began") ": "))))
      :else {:error usage})))

(defn rewind-line
  "The line a turn is told that the files were put back (RFC-069): the
  prompt resource's template `kind` (:rewind, :undo-turn, :undo-rewind,
  :forked-away, :fork-kept) filled from a `brainyard.changes` result and
  `extra` ({:session :from …})."
  [kind {:keys [turn end? files conflicts]} & [extra]]
  (let [words @console-words]
    (fill-words (get words kind)
                (merge {:turn turn :point (if end? "ended" "began") :files (file-list files)
                        :conflicts (if (seq conflicts) (fill-words (:conflicts words) {:files (file-list conflicts)}) "")}
                       extra))))

(defn outside-line
  "`line` said to have been done from outside the session, by `byj turns
  SUB`: what a note left by the CLI says (byj-gly.488)."
  [line sub]
  (fill-words (:outside @console-words) {:line line :sub sub}))

(defn- tell-next-turn!
  "`lines` added to the session's console activity, which its next turn is
  told."
  [{:keys [st-memory config]} lines]
  (when (seq lines)
    (swap! st-memory
           (fn [m] (let [acts (vec (take-last (:console-activity-max-entries config 10) (into (vec (:console-activity m)) lines)))]
                     (set-turns (assoc m :console-activity acts) (:previous-turns m) config))))))

(defn- rewound!
  "What `rewind-command` did, said: to the person, to the next turn (a line of
  console activity) and to the bus. `{:text}`."
  [ctx kind {:keys [turn end? files conflicts saved] :as r} lead]
  (if (empty? files)
    {:text "the files are already as they were then: nothing changed"}
    (do
      (tell-next-turn! ctx [(rewind-line kind r)])
      (events/emit! ctx :session/rewind {:kind kind :turn turn :end? (boolean end?) :files files
                                         :conflicts (vec conflicts) :saved saved})
      {:text (str lead (count files) (if (= 1 (count files)) " file" " files") " (" (file-list files) ")"
                  (when (seq conflicts) (str "\nconflict markers left in " (file-list conflicts) ": resolve them, or /rewind undo"))
                  "\nwhat was there is kept as " saved " — /rewind undo puts it back")})))

(declare snapshot!)

(defn- fork-command
  "`/fork [TURN [keep-files]]` (RFC-069): a new session made from this one's
  saved snapshot — cut before TURN, when one is named (`persist/fork!`) —
  and, with a turn, the files put back as they were when it began, so the
  new session's conversation and the work tree agree; `keep-files` leaves
  the files as they are. This session is not touched, and stays resumable.
  A front end that holds several sessions opens the new one beside it
  (`:start-session`); without one, the line says how to resume it.
  `{:text}` or `{:error}`."
  [{:keys [config task-registry] :as ctx} [a b]]
  (let [id (str (:session-id config))
        k (some-> a parse-long)
        keep-files? (= "keep-files" (if k b a))
        busy (when task-registry (seq (filter #(= :running (:status %)) (tasks/summaries task-registry))))]
    (cond
      (not (:persist-session? config)) {:error "this session is not saved (:persist-session? false), so there is no snapshot to fork"}
      (or (and a (not k) (not= a "keep-files")) (and b (not= b "keep-files")))
      {:error "usage: /fork [TURN [keep-files]] — /rewind lists the turns"}
      (and k (not keep-files?) busy)
      {:error (str "not while background work runs (" (str/join ", " (map :id busy)) "): it may be writing these files — /task cancel ID first, or /fork " k " keep-files")}
      :else
      (do (snapshot! ctx)
          (let [nid (context/session-id)
                r (persist/fork! config id nid (when k {:before-turn k}))]
            (if (:problem r)
              {:error (str "could not fork: " (:problem r))}
              (let [files (when (and k (not keep-files?)) (changes/rewind! config id k))
                    opened (when-let [f (:start-session ctx)] (f {:resume nid}))]
                ;; this session's files were put back under it: its next
                ;; turn is told; a fork that kept them tells the new one
                ;; what its files hold that it does not remember (byj-gly.488)
                (when (seq (:files files))
                  (tell-next-turn! ctx [(rewind-line :forked-away files {:session nid})]))
                (when (and k keep-files?)
                  (persist/note! config nid (rewind-line :fork-kept {:turn k} {:from id})))
                (when (seq (:files files))
                  (events/emit! ctx :session/rewind {:kind :fork :turn k :end? false :files (:files files)
                                                     :conflicts [] :saved (:saved files) :session nid}))
                {:text (str "forked as " nid (if k (str ", its conversation before turn " k) ", as this session stands")
                            (cond
                              (:error files) (str "\nthe files were not put back: " (:error files))
                              (seq (:files files)) (str "\nthe files put back as they were when turn " k " began: " (file-list (:files files))
                                                        "\nwhat was there is kept as " (:saved files) " — /rewind undo here puts it back")
                              (and k (not keep-files?)) "\nthe files were already as they were then")
                            (cond
                              (:session-id opened) "\nit is open beside this one"
                              :else (str "\nbyj run --resume " nid " continues it"
                                         (when (:error opened) (str " (" (:error opened) ")")))))})))))))

(defn- task-report
  "`/task [list | <id> | cancel <id>]` — RFC-019's registry, read from the
  prompt. Everything here is what `task$list`, `task$detail` and `task$cancel`
  return; the model has had these all along and a person had to ask it."
  [{:keys [task-registry] :as ctx} [sub arg]]
  (let [reg task-registry
        fmt-one (fn [{:keys [id name status job-type elapsed-ms]}]
                  (str "  " id " · " (clojure.core/name (or status :?))
                       " · " (or job-type "?") " · " (or name "")
                       (when elapsed-ms (str " · " (long (/ elapsed-ms 1000)) "s"))))]
    (cond
      (nil? reg) "no task registry in this session"

      (= "cancel" sub)
      (cond
        (str/blank? arg) "usage: /task cancel <id>"
        (tasks/cancel! reg arg) (str "cancelled " arg)
        :else (str "nothing running with id " arg))

      ;; a bare id is a detail, as brainyard's /task does
      (and sub (not= "list" sub))
      (if-let [t (tasks/snapshot reg sub)]
        (str/join "\n" (cond-> [(fmt-one t)]
                          (seq (str (:output t))) (conj (str "  output: "
                                                            (first (str/split-lines (str (:output t))))))
                          (:result t) (conj (str "  result: " (pr-str (:result t))))))
        (str "no task with id " sub))

      :else
      (let [all (tasks/summaries reg)]
        (if (empty? all)
          "no background tasks"
          (str/join "\n" (cons (str (count all) " task(s):") (map fmt-one all))))))))

(defn- todo-report
  "`/todo [slug]` — the checklist that outlives a turn (RFC-023). With no slug
  and exactly one list, that one; with more, their names."
  [{:keys [config]} [slug]]
  (let [slugs (todo/list-slugs config)]
    (cond
      (and (str/blank? slug) (empty? slugs)) "no checklists — the agent writes one with todo$create"
      (str/blank? slug) (if (= 1 (count slugs))
                          (todo/render (todo/read-todo config (first slugs)))
                          (str/join "\n" (cons "checklists:" (map #(str "  " %) slugs))))
      :else (if-let [t (todo/read-todo config slug)]
              (todo/render t)
              (str "no checklist " slug " — have: " (str/join ", " slugs))))))

(def ^:private lm-label llm/lm-label)

(declare refresh-config! refresh-mcp! snapshot!)

(defn- config-now
  "The config in force now: the ctx's, under whatever the ctx-box has been
  given since the tree started (RFC-002) — a `/config KEY VALUE` just applied."
  [ctx]
  (:config (merge ctx (some-> (:ctx-box ctx) deref))))

(defn- secret? [v]
  ;; a row's value arrives already redacted (configbox/shown), which is the
  ;; same fact said another way
  (boolean (or (seq (configbox/secrets-in (pr-str v))) (secrets/redacted? v))))

(defn- shown-value [v]
  (if (secret? v) "•••• (secret-shaped)" (pr-str v)))

(defn- config-rows
  "Every setting as a row for a front end to show: configbox's rows with the
  key's family and type, the families in `config/families` order, `:shown` as
  the value may be shown (a secret-shaped one masked) and `:edit` as it may be
  typed back — empty for a secret, which is never put in front of anyone."
  [config]
  (let [order (into {} (map-indexed (fn [i f] [f i]) config/families))]
    (->> (configbox/rows config nil nil)
         (map (fn [{:keys [key value] :as r}]
                (assoc r :family (get-in config/config-items [key :family])
                       :type (get-in config/config-items [key :type])
                       :enum (get-in config/config-items [key :enum])
                       :shown (shown-value value)
                       :edit (cond (secret? value) "" (nil? value) "" (string? value) value
                                   (keyword? value) (name value) :else (pr-str value)))))
         (sort-by (juxt #(order (:family %) 99) #(name (:key %))))
         vec)))

(defn- family-line
  "One family on one line: its name, how many of its switches are on, and how
  many of its settings differ from stock — brainyard's `/feature` rows."
  [fam rows]
  (let [switches (filter #(= "boolean" (:type %)) rows)]
    (str "  " fam (apply str (repeat (max 1 (- 13 (count (name fam)))) " "))
         (when (seq switches) (str (count (filter :value switches)) "/" (count switches) " on · "))
         (count rows) " settings"
         (let [n (count (filter :changed? rows))] (when (pos? n) (str " · " n " changed"))))))

(defn- family-report
  "`/config FAMILY` — every setting in it: `●`/`○` for a switch, the value
  otherwise, and where a changed one came from."
  [fam rows]
  (str/join "\n"
            (cons (str (name fam) " (" (count rows) ")")
                  (for [{:keys [key value type changed? source]} rows]
                    (str "  " (cond (not= "boolean" type) " " value "●" :else "○")
                         " " (name key) (apply str (repeat (max 1 (- 30 (count (name key)))) " "))
                         (if (= "boolean" type) (if value "on" "off") (shown-value value))
                         (when changed? (str "  (" (name source) ")")))))))

(defn- config-report
  "brainyard's `/config` and `/feature` as one: with no argument, the families
  and what is on in each (`/feature`); with a family, its settings; with a key,
  that key in full. A value shaped like a secret is masked. A front end that
  has an editor is handed the rows instead of the text (`:present`), and says
  so by returning true."
  [ctx arg]
  (let [config (config-now ctx)
        rows (config-rows config)
        arg (str/replace (str/trim (str arg)) #"^:" "")
        fam (some #(when (= arg (name %)) %) config/families)
        want (when-not (or (str/blank? arg) fam) (keyword arg))]
    (cond
      (str/blank? arg)
      (when-not (some-> (:present ctx) (apply [:config {:rows rows}]))
        (str "settings by family — /config FAMILY lists one, /config KEY shows one\n"
             (str/join "\n" (for [f config/families
                                  :let [rs (filter #(= f (:family %)) rows)]
                                  :when (seq rs)]
                              (family-line (name f) rs)))))

      fam (family-report fam (filter #(= fam (:family %)) rows))

      :else
      (if-let [{:keys [key value default source doc writable? why-not family]}
               (some #(when (= want (:key %)) %) rows)]
        (str (name key) " = " (shown-value value)
             "\n  default " (shown-value default) " · from " (name source) " · family " (name family)
             (when doc (str "\n  " doc))
             (if writable? "\n  /config " (str "\n  not writable: " why-not))
             (when writable? (str (name key) " VALUE sets it · /config unset " (name key) " resets it")))
        (str arg " is not a config key or family — /config lists the families")))))

(defn- config-set
  "`/config KEY VALUE`, and `/config unset KEY`: write one key to the project's
  config.edn, as the agent's `config$set` and `config$unset` do and through the
  same `configbox/set-key!` — typed by the key's declared type, snapshot first,
  read back after, reverted if it did not land (byj-gly.142).

  It asks as nobody in particular, not as `:person`, so a permission key is
  refused here exactly as it is to the agent. RFC-012 lets a person grant a
  permission key at the gate's prompt, about the one thing just asked, and
  nowhere else; a command typed at the prompt is not that answer, and
  `configbox-test/no-command-can-ask-as-a-person` keeps it so. It needs no
  `:config-write?`, which exists to keep writing in a person's hands — and the
  person is the one typing.

  What it writes is put in force at once, by the same reload a turn's start
  does (`refresh-config!`), which says what changed; this says what was
  written, and when a flag or the environment still outranks the file."
  [ctx k-str v-str unset?]
  (let [config (config-now ctx)
        k (keyword (str/replace (str k-str) #"^:" ""))
        v (when-not unset? (config/coerce (get-in config/config-items [k :type]) (str/trim (str v-str))))
        {:keys [ok? error path scope was now]}
        (configbox/set-key! config :project k v
                            {:reason (str "/config " (when unset? "unset ") (name k)) :unset? unset?})]
    (if-not ok?
      (emit ctx :info (str "not written: " error))
      (do (emit ctx :info (str (name k) ": " (pr-str was) " → " (if unset? "unset" (pr-str now))
                               " in " path " (" (name scope) ")"))
          (refresh-config! ctx)
  (refresh-mcp! ctx)
          (let [c (config-now ctx)
                source (get-in c [:config-sources k] :default)]
            (when (#{:caller :env} source)
              (emit ctx :info (str "  but " (name k) " is still " (shown-value (get c k)) ": "
                                   (if (= :env source) "an environment variable" "a command-line flag")
                                   " outranks the file"))))
          ;; an editor that is open shows what is now in force
          (some-> (:present ctx) (apply [:config {:rows (config-rows (config-now ctx)) :refresh? true}]))))))

(defn- tools-report
  "`/tools` (byj-gly.273.1): every tool the session can call. A front end with
  a picker gets the rows (`toolshelf/rows`) to browse, with `arg` as its first
  filter; without one, `arg` naming a tool prints its spec, and anything else
  lists the tools it matches, a line each, by family."
  [ctx arg]
  (let [rows (toolshelf/rows ctx)
        exact (first (filter #(= arg (:id %)) rows))]
    (cond
      (some-> (:present ctx) (apply [:tools {:rows rows :query (when-not exact arg) :select (:id exact)}]))
      nil

      exact (str/join "\n" (toolshelf/spec-lines exact))

      :else
      (let [hits (toolshelf/matches rows arg)]
        (if (empty? hits)
          (str "no tool matches \"" arg "\" — /tools lists them all")
          (str/join "\n" (concat
                          (for [[family rs] (sort-by key (group-by :family hits))
                                line (cons (str family " (" (count rs) ")")
                                           (for [{:keys [id description]} rs]
                                             (str "  " id " — " (first (str/split-lines (str description))))))]
                            line)
                          [(str (count hits) " tool(s) · /tools NAME shows one's arguments and how to call it")])))))))

(defn- model-report
  "brainyard's `/model`: with nothing, what this session is asking — and, where
  the front end has one, a picker of the curated models (`llm/curated-models`,
  brainyard's `get-popular-models`); with a number, that entry of the list;
  with a name, that model — `provider/model`, or a bare id the list knows
  (`/model sonnet`). The switch writes the ctx's `:lm-box`, which every later
  call reads (`agent/current-lm`), so it takes effect on the next turn without
  restarting the session."
  [{:keys [lm-box] :as ctx} [arg]]
  (let [current (agent/current-lm ctx)
        models @llm/curated-models
        n (when (and arg (re-matches #"\d+" arg)) (parse-long arg))
        label (cond n (when-let [{:keys [provider model]} (nth models (dec n) nil)]
                        (str (name provider) "/" model))
                    (or (str/blank? arg) (= "list" arg)) nil
                    :else (llm/model-label arg))]
    (cond
      (str/blank? arg)
      (when-not (and lm-box (some-> (:present ctx)
                                    (apply [:model {:current (lm-label current) :models models}])))
        (str "model " (lm-label current)
             (when (and lm-box @lm-box) " (switched this session)")
             "\n/model list shows the " (count models) " curated models · /model N or /model NAME switches"))

      (= "list" arg)
      (str/join "\n" (for [[i {:keys [provider model description]}] (map-indexed vector models)]
                       (str "  " (inc i) ". " (name provider) "/" model
                            (when description (str " — " description)))))

      (nil? lm-box)
      "this front end cannot switch models mid-session"

      (and n (nil? label)) (str "no model " n " — /model lists " (count models))

      :else
      (if-let [lm (some-> label (llm/parse-lm-label (or (:env-fn ctx) #(System/getenv %))))]
        (do (reset! lm-box lm)
            ;; kept with the session, so a resume asks it too
            (swap! (:st-memory ctx) assoc :model (lm-label lm))
            (snapshot! ctx)
            (str "model " (lm-label lm) " — from the next turn"))
        (str "not a model: " arg " — write provider/model, e.g. anthropic/claude-sonnet-5")))))

(defn- credential-source
  "Where the model's credentials come from — never the credential itself: the
  variable a provider reads, and whether it is set in this run's environment,
  or the login a provider uses instead."
  [lm env]
  (let [provider (:provider lm)
        {:keys [api-key-env auth-token-env]} (get llm/providers provider)
        set? #(not (str/blank? (str (env %))))]
    (case provider
      :claude-code "the claude CLI's own login"
      :codex "the codex CLI's own sign-in ($CODEX_HOME)"
      :bedrock "the AWS CLI's profile or SSO"
      :mock "none (the mock provider)"
      :ollama "none (a local server)"
      (cond
        (:api-key lm) (str (or api-key-env "an API key") " (in use)")
        (and api-key-env (set? api-key-env)) (str api-key-env " is set")
        (and auth-token-env (set? auth-token-env)) (str auth-token-env " is set")
        api-key-env (str api-key-env " is NOT set")
        :else "none needed"))))

(def ^:private source-names
  "The config layers, as `/status` names them, in the order they stack."
  [[:user "user (~/.brainyard/config.edn)"] [:project "project (.brainyard/config.edn)"]
   [:caller "command-line flags"] [:env "environment (BY_*)"]])

(defn status-report
  "`/status` — Claude Code's, from what this port knows: the session (its name,
  id and kind, where it runs), the model and where its credentials come from,
  the settings in force and where they came from, and what it has spent.
  brainyard's own /status is the agent's state, which is here as the turn and
  call counts."
  [{:keys [st-memory present mcp-box env-fn] :as ctx}]
  (let [m @st-memory
        config (config-now ctx)
        lm (agent/current-lm ctx)
        env (or env-fn #(System/getenv %))
        sources (set (vals (:config-sources config {})))
        ;; the run's box as it stands, not this ctx's copy of it
        mcp-clients (or (some-> mcp-box mcp/box-clients) (:mcp-clients ctx))
        mcp-up (count (filter mcp/alive? (vals mcp-clients)))
        mcp-down (- (count mcp-clients) mcp-up)
        {:keys [input-tokens output-tokens llm-calls]} (:usage m)
        rows [["Version" (version/version-long)]
              ["Session name" (or (:conversation-title m) "(not named yet — the first recap names it)")]
              ["Session ID" (str (:session-id config))]
              ["Session kind" (str (if present "full-screen" "inline") (when (:resume config) " · resumed"))]
              ["cwd" (str (:working-dir config))]
              ["Project" (str (:project-dir config))]
              nil
              ["Model" (lm-label lm)]
              ["Credentials" (credential-source lm env)]
              ["Permission mode" (name (permission/mode ctx))]
              ["Output style" (name (:output-style config :default))]
              ["MCP servers" (if (empty? mcp-clients)
                               "none configured"
                               (str mcp-up " running" (when (pos? mcp-down) (str ", " mcp-down " stopped")) " · /mcp"))]
              ["Setting sources" (let [used (keep (fn [[k label]] (when (contains? sources k) label)) source-names)]
                                   (if (seq used) (str/join ", " used) "defaults only"))]
              nil
              ["Turns" (str (:turn-count m 0) " · " (or llm-calls 0) " LLM calls · tokens in/out "
                            (or input-tokens 0) "/" (or output-tokens 0))]
              ["Runtime" (str "jolt " (or (config/jolt-version) "(unknown)"))]]
        w (inc (reduce max (map (comp count first) (remove nil? rows))))]
    (str/join "\n" (for [row rows]
                     (if-let [[label v] row]
                       (str (format (str "%-" (inc w) "s") (str label ":")) v)
                       "")))))

(defn- compact!
  "brainyard's `/compact`, for a session's own history: the turns before the
  last `keep` — `:full-depth` unless the command names a number — become ONE
  turn whose answer is their summary, written by the predictor the command's
  node names (`:compact-predictor`, session/compact). The history the next
  turn reads is rendered again from what is left, and the session is saved.

  A summary already there is folded into the next one. A failed call leaves
  the history as it was and says why; so does nothing to compact."
  [{:keys [st-memory config node] :as ctx} arg]
  (let [{:keys [previous-turns]} @st-memory
        keep-n (or (some-> arg str/trim not-empty parse-long) (:full-depth config 3))
        n (- (count previous-turns) keep-n)]
    (cond
      (neg? keep-n) "usage: /compact [KEEP] — how many recent turns to keep whole"
      (< n 2) (str "nothing to compact: " (count previous-turns) " turn(s), and the last " keep-n
                   " stay whole — /compact N keeps fewer")
      :else
      (let [older (subvec previous-turns 0 n)
            before (context/estimate-tokens (:history-context @st-memory))
            {:keys [summary error]}
            (try
              (let [{:keys [outputs]} (agent/run-node-predictor
                                       (assoc ctx :node (assoc node :predictor (:compact-predictor node)))
                                       {:history (history/format-previous-turns
                                                  older (assoc config :full-depth (count older)))})]
                (if-let [text (not-empty (str/trim (str (:summary outputs))))]
                  {:summary text}
                  {:error "the summary came back empty"}))
              (catch Throwable e
                (when (fx/cancelled? e) (throw e))
                {:error (ex-message e)}))]
        (if error
          (str "could not compact: " error " — the history is as it was")
          ;; every turn before the ones kept: an earlier summary's own turns,
          ;; and any the window had already let go, not the count of entries
          ;; in it (byj-gly.232)
          (let [covered (max n (- (:turn-count @st-memory 0) (- (count previous-turns) n)))
                turn {:question (str "(turns 1–" covered ", compacted)")
                      :answer summary
                      :terminated-by :compacted
                      :compacted covered
                      :tool-trail []}]
            (swap! st-memory set-turns (into [turn] (subvec previous-turns n)) config)
            (snapshot! ctx)
            (str "compacted " n " turns into one summary · history " before " → "
                 (context/estimate-tokens (:history-context @st-memory)) " tokens · the last "
                 keep-n " kept whole")))))))

(defn- server-specs
  "The configured servers by name — `:mcp-servers` keys may be keywords or
  strings — in name order."
  [config]
  (into (sorted-map) (for [[k spec] (:mcp-servers config)] [(name k) spec])))

(defn server-state
  "Where a configured server stands: `:running`, `:starting` (in the
  background, since the run began), `:died` (started, and its process is
  gone), `:failed` (would not start), `:disabled` (`:enabled false`), or
  `:stopped`."
  [mcp-box server-name spec]
  (let [{:keys [clients problems starting]} (some-> mcp-box deref)
        c (get clients server-name)]
    (cond
      (and c (mcp/alive? c)) :running
      (contains? starting server-name) :starting
      c :died
      (get problems server-name) :failed
      (false? (:enabled spec true)) :disabled
      :else :stopped)))

(defn- mask-env [env]
  (str/join ", " (for [[k _] (sort-by (comp str key) env)] (str (name k) "=••••"))))

(defn- hidden? [config tool-id]
  (boolean (permission/first-match (:hidden-tools config) tool-id)))

(defn- own
  "What `scope`'s own config file says for `k` — not the merged config, which
  also holds every other layer, the flags and the environment. A /mcp edit
  that wrote the merged value copied all of those into the project file, a
  user file's server tokens included (byj-gly.220)."
  [config scope k]
  (get-in (configbox/read-scope config scope) [:agent :config k]))

(defn- own-servers
  "A scope's own `:mcp-servers`, by string name."
  [config scope]
  (into {} (for [[k v] (own config scope :mcp-servers)] [(name k) v])))

(defn- set-hidden!
  "Write `:hidden-tools` with `add` in it and `drop` out of it, to the project
  config, and put it in force now."
  [ctx add drop]
  (let [config (config-now ctx)
        ;; the list the project file would replace: its own, else the user's —
        ;; never a flag's or the environment's, which are not the project's
        base (vec (or (own config :project :hidden-tools) (own config :user :hidden-tools)))
        now (vec (distinct (concat (remove (set drop) base) add)))]
    (if (= now base)
      nil
      (let [{:keys [ok? error]} (configbox/set-key! config :project :hidden-tools now
                                                    {:reason "/mcp hide|show"})]
        (if ok? (do (refresh-config! ctx) nil) (str "not written: " error))))))

(defn- set-enabled!
  "Write a server's `:enabled` into the project config's `:mcp-servers`."
  [ctx server-name on?]
  (let [config (config-now ctx)
        ;; only :enabled, over the project's own entries: servers merge per
        ;; server across the files (config/merge-layer), so a user server is
        ;; switched off here without its spec being copied in
        servers (update (own-servers config :project) server-name #(assoc (or % {}) :enabled on?))
        {:keys [ok? error]} (configbox/set-key! config :project :mcp-servers servers
                                                {:reason (str "/mcp " server-name (if on? " enable" " disable"))})]
    (when-not ok? (str "not written: " error))))

(defn- env-of [{:keys [mcp-box env-fn]}]
  (or (some-> mcp-box deref :env-fn) env-fn #(System/getenv %)))

(defn- write-servers!
  "Write `servers` as `:mcp-servers` to `scope`'s config.edn, and apply it —
  `refresh-config!` starts and stops what changed (`apply-mcp-servers!`).
  Returns an error string, or nil."
  [ctx scope servers reason]
  (let [{:keys [ok? error]} (configbox/set-key! (config-now ctx) scope :mcp-servers servers {:reason reason})]
    (if ok? (do (refresh-config! ctx) (refresh-mcp! ctx) nil) (str "not written: " error))))

(declare registry-search)

(defn- mcp-find
  "`/mcp find [WORDS]` — the catalogue entries whose name, description or tags
  hold every word, each saying whether it is already configured and whether
  what it needs is set."
  [ctx words]
  (let [config (config-now ctx)
        env (env-of ctx)
        configured (set (keys (server-specs config)))
        hit? (fn [e] (let [hay (str/lower-case (str (:name e) " " (:description e) " " (str/join " " (:tags e))))]
                       (every? #(str/includes? hay (str/lower-case %)) words)))
        hits (filter hit? (mcp/catalog (:project-dir config)))
        ;; and the MCP Registry, when there is something to search for — its
        ;; hits are kept, so `/mcp add NAME` can take one by the name shown
        ;; the registry matches its `search` against names as one string, so
        ;; it is asked for the longest word and its answer narrowed to every
        ;; word, in the name or the description, as the catalogue is
        remote (when (and (seq words) (:mcp-registry? config true))
                 (registry-search ctx words))
        local-lines (for [{:keys [name description tags] :as e} hits
                          :let [miss (mcp/missing e env)]]
                      (str "  " (cond (configured name) "✓" (seq miss) "○" :else "●") " " name
                           " — " description
                           (when (seq tags) (str " [" (str/join ", " tags) "]"))
                           (cond (configured name) " · added"
                                 (seq miss) (str " · needs " (str/join ", " miss))
                                 :else " · ready")))
        remote-lines (for [{:keys [name registry-name version description kind unsupported] :as e} (:entries remote)
                           :let [miss (mcp/missing e env)]]
                       (str "  " (cond unsupported "✗" (configured name) "✓" :else "○") " " name
                            " — " (let [d (first (str/split-lines description))]
                                    (if (> (count d) 90) (str (subs d 0 89) "…") d))
                            (when-not unsupported (str " · " kind (when version (str " " version))))
                            (cond unsupported (str " · cannot start here: " unsupported)
                                  (configured name) " · added"
                                  (seq miss) (str " · needs " (str/join ", " miss)))
                            " (" registry-name ")"))]
    (if (and (empty? hits) (empty? (:entries remote)) (not (:error remote)))
      (str "nothing matches " (str/join " " words) " — /mcp add NAME -- COMMAND ARGS adds your own")
      (str/join "\n"
                (concat
                 [(str (count hits) " in the catalogue" (when (seq words) (str " for \"" (str/join " " words) "\"")))]
                 local-lines
                 (cond
                   (:error remote) [(str "the MCP registry: " (:error remote))]
                   remote (cons (str (count (:entries remote)) " in the MCP registry — third-party code, added disabled")
                                remote-lines))
                 ["/mcp add NAME adds one to the project config"])))))

(defn- registry-search
  "The MCP Registry searched for `words` — asked for the longest word, since
  it matches names as one string, and narrowed to every word in the name or
  the description. The hits are kept for `/mcp add`. `{:entries}` or `{:error}`."
  [ctx words]
  (let [config (config-now ctx)
        hit? (fn [e] (let [hay (str/lower-case (str (:name e) " " (:registry-name e) " " (:description e)))]
                       (every? #(str/includes? hay (str/lower-case %)) words)))
        r (mcp-registry/search (:mcp-registry-url config) (apply max-key count words) 50)
        r (cond-> r (:entries r) (update :entries #(vec (take 20 (filter hit? %)))))]
    (when (:entries r) (swap! (:st-memory ctx) assoc :mcp-registry-hits (:entries r)))
    r))

(defn- registry-rows
  "The registry's hits as rows for the picker's registry group."
  [ctx entries]
  (let [configured (set (keys (server-specs (config-now ctx))))
        env (env-of ctx)]
    (vec (for [{:keys [name registry-name version description kind unsupported] :as e} entries]
           {:name name :registry-name registry-name :version version :kind kind
            :description (first (str/split-lines (str description)))
            :unsupported unsupported
            :configured? (contains? configured name)
            :missing (mcp/missing e env)}))))

(defn- parse-add
  "`NAME [--env K=V]… [-- COMMAND ARGS…]` as `{:name :env :command :args}`."
  [words]
  (let [[before after] (split-with #(not= "--" %) words)
        name (first before)
        env (into {} (for [[flag kv] (partition 2 1 (rest before))
                           :when (= "--env" flag)
                           :let [[k v] (str/split kv #"=" 2)]
                           :when (and k v)]
                       [k v]))]
    (cond-> {:name name :env env}
      (seq after) (assoc :command (second after) :args (vec (drop 2 after)))
      ;; `--` and nothing after it is a mistake, not a server with no command
      ;; (byj-gly.232)
      (and (seq after) (str/blank? (str (second after)))) (assoc :no-command? true))))

(defn- mcp-add
  "`/mcp add NAME` from the catalogue, or `/mcp add NAME -- COMMAND ARGS` of
  your own, into the project config and applied at once: it starts when what
  it needs is set, and is added disabled — saying what to set — when not."
  [{:keys [mcp-box st-memory] :as ctx} words]
  (let [config (config-now ctx)
        {:keys [name env command] :as p} (parse-add words)
        catalogued (when-not command (some #(when (= name (:name %)) %) (mcp/catalog (:project-dir config))))
        ;; from the registry: the last find's hits, by the name shown or the
        ;; registry's own; a registry name not among them is looked up
        remote (when-not (or command catalogued)
                 (let [hits (:mcp-registry-hits @st-memory)
                       by-full (filter #(= name (:registry-name %)) hits)
                       by-short (filter #(= name (:name %)) hits)]
                   (cond
                     (seq by-full) (first by-full)
                     (= 1 (count by-short)) (first by-short)
                     (> (count by-short) 1) {:ambiguous (mapv :registry-name by-short)}
                     (and (str/includes? (str name) "/") (:mcp-registry? config true))
                     (some #(when (= name (:registry-name %)) %)
                           (:entries (mcp-registry/search (:mcp-registry-url config) name 10))))))
        entry (or catalogued (when-not (:ambiguous remote) remote))
        name (if (:registry-name entry) (:name entry) name)
        spec (cond command (cond-> (select-keys p [:command :args]) (seq env) (assoc :env env))
                   entry (cond-> (mcp/entry->spec entry) (seq env) (update :env merge env)))
        miss (when spec (mcp/missing spec (env-of ctx)))]
    (cond
      (or (str/blank? (str name)) (:no-command? p)) "usage: /mcp add NAME [--env K=V]… [-- COMMAND ARGS…]"
      (:ambiguous remote) (str "several registry servers are called " name ": "
                               (str/join ", " (:ambiguous remote)) " — /mcp add with the full name")
      (:unsupported entry) (str name " cannot be started here: " (:unsupported entry))
      (not (mcp/safe-name? name)) (str (pr-str name) " cannot be a server name: letters, digits and _.+!?<>=- only")
      (contains? (server-specs config) name) (str name " is already configured — /mcp " name " shows it")
      (nil? spec) (str "no " name " in the catalogue — /mcp find lists what is, and /mcp add " name
                       " -- COMMAND ARGS adds your own")
      :else
      (let [spec (cond-> spec
                   (seq miss) (assoc :enabled false)
                   ;; third-party code: added for a person to look at, and
                   ;; started only when they enable it
                   (:registry-name entry) (assoc :enabled false
                                                 :from (str (:registry-name entry)
                                                            (when (:version entry) (str "@" (:version entry))))))
            servers (assoc (own-servers config :project) name spec)]
        (or (write-servers! ctx :project servers (str "/mcp add " name))
            (let [st (server-state mcp-box name spec)
                  n (count (get-in @mcp-box [:tools name]))]
              (str "added " name " to the project config"
                   (when (:registry-name entry) (str " from the MCP registry (" (:from spec) ")"))
                   " · "
                   (case st
                     :running (str "running · " n " tool(s)")
                     :disabled (str "disabled"
                                    (when (seq miss)
                                      (str " until " (str/join " and " miss) " "
                                           (if (= 1 (count miss)) "is" "are") " set (the environment or .env)"))
                                    (when (:registry-name entry)
                                      (str " — third-party code: /mcp " name " shows what it runs"))
                                    " — then /mcp " name " enable")
                     (str (clojure.core/name st)
                          (when-let [pr (get-in @mcp-box [:problems name])] (str ": " pr)))))))))))

(defn- mcp-remove
  "`/mcp remove NAME [--user]` — stop it and take it out of the project config
  (and, with --user, out of the user config too), with any `:hidden-tools`
  globs that named it."
  [ctx words]
  (let [config (config-now ctx)
        [name & flags] words
        user? (some #{"--user"} flags)]
    (cond
      (str/blank? (str name)) "usage: /mcp remove NAME [--user]"
      (not (contains? (server-specs config) name)) (str "no MCP server " name " configured")
      :else
      (let [without (fn [m] (into {} (remove (fn [[k _]] (= name (clojure.core/name k))) m)))
            user-servers (get-in (configbox/read-scope config :user) [:agent :config :mcp-servers])
            in-project? (contains? (own-servers config :project) name)
            in-user? (some (fn [[k _]] (= name (clojure.core/name k))) user-servers)
            err (or (when in-project?
                      (write-servers! ctx :project (without (own-servers config :project)) (str "/mcp remove " name)))
                    (when (and in-user? (not user?))
                      (str name " is in the user config, which the project does not rewrite — "
                           "/mcp remove " name " --user to take it out there, or /mcp " name " disable "
                           "to turn it off for this project"))
                    (when (and user? in-user?)
                      (let [{:keys [ok? error]} (configbox/set-key! (config-now ctx) :user :mcp-servers
                                                                    (without user-servers)
                                                                    {:reason (str "/mcp remove " name)})]
                        (if ok?
                          (do (refresh-config! ctx) (refresh-mcp! ctx) nil)
                          (str "not written to the user config: " error)))))
            hidden (filter #(str/starts-with? % (str "mcp$" name "$")) (:hidden-tools (config-now ctx)))]
        (or err
            (do (when (seq hidden) (set-hidden! ctx [] hidden))
                (str "removed " name " from the "
                     (str/join " and " (cond-> [] in-project? (conj "project config") (and user? in-user?) (conj "user config")))
                     (when (seq hidden) (str " · and " (count hidden) " :hidden-tools glob(s) for it")))))))))

(defn mcp-catalog-rows
  "The catalogue entries not yet configured, for the picker's catalogue group:
  each with what it still needs."
  [ctx]
  (let [config (config-now ctx)
        configured (set (keys (server-specs config)))
        env (env-of ctx)]
    (vec (for [e (mcp/catalog (:project-dir config))
               :when (not (configured (:name e)))]
           {:name (:name e) :description (:description e) :tags (:tags e)
            :missing (mcp/missing e env)}))))

(defn mcp-rows
  "The servers and their tools as rows for a front end's picker: each server
  with its state, command and problem, and — while it runs — its tools, each
  with whether `:hidden-tools` hides it."
  [{:keys [mcp-box] :as ctx}]
  (let [config (config-now ctx)
        box @mcp-box]
    (vec (for [[n spec] (server-specs config)]
           {:server n
            :state (server-state mcp-box n spec)
            :command (str/join " " (cons (:command spec) (:args spec)))
            :problem (get-in box [:problems n])
            :tools (vec (for [{:keys [name description]} (sort-by :name (get-in box [:tools n]))]
                          {:id name
                           :tool (subs name (count (str "mcp$" n "$")))
                           :description (first (str/split-lines (str description)))
                           :hidden? (hidden? config name)}))}))))

(defn mcp-command
  "brainyard's `/mcp`, over the run's live set (byj-gly.182):

    /mcp                       every configured server and where it stands
    /mcp NAME                  one in full: its command, env, tools, problem
    /mcp NAME start|stop|restart   for this run; its tools bound or unbound
    /mcp NAME enable|disable   persisted to the project config; starts or stops it
    /mcp NAME tools            its tools, hidden ones marked
    /mcp NAME hide|show [TOOL] a tool, or all of them, via :hidden-tools
    /mcp find [WORDS]          the catalogue, and what each entry needs
    /mcp add NAME [-- CMD ARGS]  from the catalogue, or your own
    /mcp remove NAME [--user]  stopped, and out of the config

  A start, stop or restart changes the run's box; every session builds its
  tools again at its next turn (`refresh-mcp!`), this one at once."
  [{:keys [mcp-box] :as ctx} args]
  (let [config (config-now ctx)
        specs (server-specs config)
        [server-name action tool] (remove str/blank? (str/split (str/trim (str args)) #"\s+"))
        spec (get specs server-name)
        state #(server-state mcp-box % (get specs %))
        tools-of (fn [n] (get (:tools (some-> mcp-box deref)) n))
        line (fn [n]
               (let [st (state n)
                     sp (get specs n)]
                 (str "  " (case st :running "●" :died "✗" :failed "✗" :disabled "○" "·") " " n
                      "  " (name st)
                      (when (= :running st) (str " · " (count (tools-of n)) " tool(s)"))
                      " · " (str/join " " (cons (:command sp) (:args sp))))))]
    (cond
      (= "find" server-name) (mcp-find ctx (rest (str/split (str/trim (str args)) #"\s+")))
      (= "add" server-name) (mcp-add ctx (rest (str/split (str/trim (str args)) #"\s+")))
      (= "remove" server-name) (mcp-remove ctx (rest (str/split (str/trim (str args)) #"\s+")))

      ;; the picker's own: what the registry has for what was typed, handed to
      ;; the open picker; as text, what find would say of the registry
      (= "registry" server-name)
      (let [words (rest (str/split (str/trim (str args)) #"\s+"))
            config (config-now ctx)]
        (cond
          (empty? words) "usage: /mcp registry WORDS"
          ;; the picker waits on an answer to its search: it gets one, even
          ;; when that is "off", or its title said "searching…" forever
          ;; (byj-gly.211)
          (not (:mcp-registry? config true))
          (let [off "the MCP registry is off (:mcp-registry? false)"]
            (if (some-> (:present ctx) (apply [:mcp {:registry [] :registry-error off :refresh? true}]))
              nil
              off))
          :else
          (let [r (registry-search ctx words)
                rows (registry-rows ctx (:entries r))]
            (if (some-> (:present ctx) (apply [:mcp {:registry rows :registry-error (:error r) :refresh? true}]))
              nil
              (or (some->> (:error r) (str "the MCP registry: "))
                  (str (count rows) " in the MCP registry — /mcp find " (str/join " " words) " lists them"))))))

      ;; a front end with a picker shows the servers and the catalogue, even
      ;; when nothing is configured yet — the catalogue is where to start
      (and (nil? server-name) mcp-box (:present ctx)
           ((:present ctx) :mcp {:rows (mcp-rows ctx) :catalog (mcp-catalog-rows ctx)}))
      nil


      (and (nil? server-name) (empty? specs))
      "no MCP servers configured — /mcp find lists the catalogue, /mcp add NAME adds one"

      (nil? mcp-box) "this run has no MCP registry"

      (nil? server-name)
      (if (empty? specs)
        "no MCP servers configured — add one under :mcp-servers in .brainyard/config.edn"
        (str "MCP servers\n" (str/join "\n" (map line (keys specs)))
             "\n  " (count (filter #(= :running (state %)) (keys specs))) "/" (count specs) " running"
             " · /mcp NAME [start|stop|restart|enable|disable|tools|hide|show]"))

      (nil? spec) (str "no MCP server " server-name
                       (when (seq specs) (str " — configured: " (str/join ", " (keys specs)))))

      (nil? action)
      (let [st (state server-name)
            ts (tools-of server-name)
            problem (get-in @mcp-box [:problems server-name])
            c (get-in @mcp-box [:clients server-name])]
        (str/join "\n"
                  (remove nil?
                          [(str "MCP server " server-name)
                           (str "  state:     " (name st))
                           (str "  command:   " (str/join " " (cons (:command spec) (:args spec))))
                           (when (:from spec) (str "  from:      the MCP registry, " (:from spec)))
                           (when (seq (:env spec)) (str "  env:       " (mask-env (:env spec))))
                           (when-let [si (:server-info c)] (str "  server:    " (:name si) " " (:version si)))
                           (when (= :running st) (str "  tools:     " (count ts) " · /mcp " server-name " tools"))
                           (when problem (str "  problem:   " problem))
                           (str "  actions:   /mcp " server-name " "
                                (case st :running "stop|restart" :died "restart" "start")
                                (if (false? (:enabled spec true)) "|enable" "|disable") "|tools|hide|show")])))

      (= "start" action)
      (let [_ (mcp/unhold! mcp-box server-name)
            r (mcp/start-server! mcp-box server-name spec)]
        (refresh-mcp! ctx)
        (or (:error r) (str "started " server-name " · " (:tools r) " tool(s)"
                            (when (:problem r) (str " · " (:problem r))))))

      (= "stop" action)
      ;; stopped by a person: a later config change leaves it stopped, until
      ;; its own spec changes (mcp/reconcile!)
      (let [_ (mcp/hold! mcp-box server-name spec)
            r (mcp/stop-server! mcp-box server-name)]
        (refresh-mcp! ctx)
        (or (:error r)
            (if (:stopping r)
              (str server-name " is still starting · it is stopped as soon as it is up")
              (str "stopped " server-name " · its tools are unbound"))))

      (= "restart" action)
      (let [_ (mcp/unhold! mcp-box server-name)
            r (mcp/restart-server! mcp-box server-name spec)]
        (refresh-mcp! ctx)
        (or (:error r)
            (if (:restarting r)
              (str server-name " is still starting · it is restarted as soon as it is up")
              (str "restarted " server-name " · " (:tools r) " tool(s)"))))

      (#{"enable" "disable"} action)
      (let [on? (= "enable" action)]
        (or (set-enabled! ctx server-name on?)
            (do (refresh-config! ctx)
                (refresh-mcp! ctx)
                ;; the edit is applied like any other (`apply-mcp-servers!`):
                ;; enabled starts it, disabled stops it
                (str server-name " " action "d in the project config · "
                     (name (server-state mcp-box server-name (get (server-specs (config-now ctx)) server-name)))
                     " now"))))

      (= "tools" action)
      (let [ts (tools-of server-name)]
        (if (empty? ts)
          (str server-name " is " (name (state server-name)) " — its tools are listed while it runs")
          (str/join "\n" (cons (str server-name " · " (count ts) " tool(s)")
                               (for [{:keys [name description]} (sort-by :name ts)]
                                 (str "  " (if (hidden? config name) "○ " "● ") name
                                      (when (hidden? config name) " (hidden)")
                                      (when-not (str/blank? (str description))
                                        (str " — " (first (str/split-lines (str description)))))))))))

      (#{"hide" "show"} action)
      (let [id (cond (nil? tool) (mcp/tool-id server-name "*")
                     (str/starts-with? tool "mcp$") tool
                     :else (mcp/tool-id server-name tool))
            hide? (= "hide" action)
            ;; showing the whole server drops every glob that names it
            drop (if (and (not hide?) (nil? tool))
                   (filter #(str/starts-with? % (str "mcp$" server-name "$")) (:hidden-tools config))
                   [id])]
        (or (if hide? (set-hidden! ctx [id] []) (set-hidden! ctx [] drop))
            (str (if hide? "hidden: " "shown: ") (if tool id (str "every tool of " server-name))
                 " · :hidden-tools in the project config")))

      :else (str "usage: /mcp [NAME [start|stop|restart|enable|disable|tools|hide|show [TOOL]]]"))))

(def ^:private default-usage-window
  "How many calls `/usage` tables without being asked. brainyard's default."
  10)

(defn parse-usage-args
  "brainyard's `parse-usage-args`: an optional positive N and an optional
  `--breakdown`, in any order. nil when the arguments are not that."
  [tokens]
  (let [tokens (remove str/blank? tokens)
        breakdown? (boolean (some #{"--breakdown"} tokens))
        n-token (first (remove #{"--breakdown"} tokens))]
    (if n-token
      (when-let [v (parse-long n-token)]
        (when (pos? v) {:n v :breakdown? breakdown?}))
      {:n default-usage-window :breakdown? breakdown?})))

(defn- call-row [{:keys [predictor-id model input-tokens output-tokens elapsed-ms cost]}]
  (str "  " (or predictor-id "?")
       " · " (or model "?")
       " · " input-tokens "/" output-tokens " tokens"
       (when elapsed-ms (str " · " elapsed-ms " ms"))
       ;; a call priced at nothing says nothing: cost/of returns nil for a model
       ;; no table names, and a zero would read as a broken counter rather than
       ;; a free call
       (when (pos? (or cost 0)) (format " · $%.4f" (double cost)))))

(defn- breakdown-rows
  "Where a call's prompt went, and what the cache did with it. brainyard breaks
  a call down by system/signature/user message; the port's prompt is assembled
  from ZONES (RFC-015), so that is what it can honestly count — plus the cache
  split, which is the other thing a person reading this wants to know."
  [{:keys [zones cache-read-tokens cache-write-tokens input-tokens]}]
  (concat
   (for [[title tokens] zones]
     (str "      " title ": ~" tokens " tokens"))
   [(str "      cache: " (or cache-read-tokens 0) " read, " (or cache-write-tokens 0) " written, "
         (max 0 (- (or input-tokens 0) (or cache-read-tokens 0))) " fresh")]))

(defn usage-report
  "`/usage [N] [--breakdown]`: the running totals, then the last N calls.

  The totals are the session's; the table is what those totals are made of,
  which is the question a person actually has when a turn cost more than they
  expected."
  [mem tokens]
  (if-let [{:keys [n breakdown?]} (parse-usage-args tokens)]
    (let [{:keys [usage usage-calls turn-count]} mem
          recent (vec (take-last n usage-calls))]
      (str/join
       "\n"
       (concat
        [(str turn-count " turn(s) · " (:llm-calls usage) " LLM call(s) · tokens in/out "
              (:input-tokens usage) "/" (:output-tokens usage)
              (when-let [c (:cost usage)] (when (pos? c) (format " · $%.4f" (double c))))
              (let [r (or (:cache-read-tokens usage) 0)
                    w (or (:cache-write-tokens usage) 0)]
                (when (pos? (+ r w)) (str " · cache " r " read / " w " written"))))]
        (if (seq recent)
          (cons (str "last " (count recent) " call(s):")
                (mapcat (fn [call]
                          (cons (call-row call)
                                (when breakdown? (breakdown-rows call))))
                        recent))
          ["no calls yet"]))))
    "usage: /usage [N] [--breakdown]"))

(defn- claude-usage-wanted?
  "Does `/usage` read the subscription's windows: `:claude-usage` on, or auto
  and the session on the claude-code provider (byj-gly.324)."
  [ctx]
  (let [lm (or (some-> (:lm-box ctx) deref) (:lm ctx))]
    (case (str (get-in ctx [:config :claude-usage] "auto"))
      "on" true
      "off" false
      (= "claude-code" (some-> (:provider lm) name)))))

(defn- claude-usage-now
  "The subscription's snapshot, refreshed when stale, within 20 s: a command
  is a synchronous handler, the edge where an effect is waited on."
  [ctx]
  (let [f (or (:claude-usage-fn ctx) claude-usage/usage)
        r (fx/run!! (fx/task-of #(f)) 20000)]
    (or (:ok r) {:logged-in? true :limits [] :error (if (:timeout r) "no answer in 20 s" (some-> (:err r) ex-message))})))

(defn- subscription-text [u]
  (let [{:keys [email organization plan]} (:account u)]
    (str/join "\n" (cons (str "Claude Code subscription"
                              (when (:logged-in? u)
                                (str " (" (str/join ", " (remove nil? [plan email organization])) ")"))
                              ":")
                         (claude-usage/limit-lines u)))))

(defn doctor-report
  "`/doctor`: the tools and the project (`init$check`), the project's own
  loops — one made from an older bundled loop named so (RFC-071) — what is
  installed —
  the byj that runs by name, and every other copy on PATH,
  shadowed, with how it is updated or removed (byj-gly.181.13) — then Claude
  Code's sign-in — the account, when its refresh token runs out, which is
  what forces a new login — and the subscription's windows (byj-gly.324).

  What it reads of the machine can be given in `ctx`, as a test gives it
  (byj-ng7.13): `:claude-usage-fn`, `:doctor-check-fn` (config → `{:text}`),
  `:install-lines-fn` (checkout → `[{:text}]`), `:checkout` (the source
  root, nil for none)."
  [ctx]
  (let [checked ((or (:doctor-check-fn ctx) init/check) (:config ctx))
        checkout (if (contains? ctx :checkout) (:checkout ctx) (version/source-root))
        installed ((or (:install-lines-fn ctx)
                       (fn [checkout]
                         (update/install-lines
                          {:running (when-not checkout (update/real-path (update/executable-path (persist/own-pid) update/shell)))
                           :path-env (System/getenv "PATH")
                           :version-of (fn [cmd p]
                                         (some-> (init/version-of p (if (= "byj" cmd) ["--version"] ["version"]))
                                                 (cond->> (= "byj" cmd) (str "v"))))})))
                   checkout)
        u (claude-usage-now ctx)
        {:keys [email organization plan refresh-expires-at]} (:account u)
        days (when refresh-expires-at (quot (- refresh-expires-at (System/currentTimeMillis)) 86400000))]
    (str (:text checked)
         (some->> (project-loops/report-text (:project-dir (:config ctx))) (str "\n\n"))
         "\n\nInstalled" (when checkout (str " (this run is from the checkout " checkout ")")) ":\n"
         (str/join "\n" (map :text installed))
         "\n\nClaude Code:\n"
         (if (:logged-in? u)
           (str "  signed in" (when email (str " as " email)) (when organization (str " (" organization ")"))
                (when plan (str ", plan " plan))
                (when days (str "\n  refresh token: " (if (neg? days) "expired — /login claude" (str days " day(s) left"))
                                (when (< -1 days 7) " — /login claude soon"))))
           "  not signed in — /login claude")
         "\n\n" (subscription-text u))))

(defn- capture-file
  "Where a capture goes when nobody says: beside the session it captures, named
  for the moment it was taken."
  [config]
  (let [dir (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "captures")]
    (.mkdirs dir)
    (.getPath (io/file dir (str "capture-" (System/currentTimeMillis) ".txt")))))

(defn transcript-text
  "The transcript as lines of text, as `/capture` writes it and as an inline
  front end prints a resumed session's history: a question behind `› `, a tool
  call on one line, an answer as it was, everything else behind `· `."
  [lines]
  (str/join "\n" (for [[kind line] lines]
                    (if (= :tool kind)
                      (let [{:keys [tool-name tool-args tool-result tool-error?]} line]
                        (str (if tool-error? "✗ " "⏺ ") tool-name " " (pr-str tool-args)
                             " → " (first (str/split-lines (str tool-result)))))
                      (str (case kind :you "› " :error "! " :answer "" "· ") line)))))

(defn- capture!
  "brainyard's `/capture <file-path>`: write what was said to a file, for a bug
  report or a paste into an issue. brainyard captures the RENDERER's scrollback,
  which only its full-screen mode has; this captures the session's own
  transcript, so it says the same thing under any front end — and says what it
  is not: the events a `-v` run prints are not in it."
  [{:keys [st-memory config] :as ctx} path]
  (let [path (or (when-not (str/blank? path) path) (capture-file config))
        lines (:transcript @st-memory)
        text (transcript-text lines)]
    (if (empty? lines)
      (emit ctx :info "nothing said yet — nothing to capture")
      (try
        (spit path text)
        (emit ctx :info (str "captured " (count lines) " line(s) to " path))
        (catch Throwable e
          (emit ctx :error (str "could not write " path ": " (ex-message e))))))))


(defn- tool-line
  "`[tool args]` for a `/<name> args` line naming a registered tool that no
  command shadows — a built-in command wins its name, as in brainyard — else
  nil. brainyard's `resolve-tool-command`, by id: the port's tools carry no
  aliases."
  [ctx input]
  (when (str/starts-with? (str input) "/")
    (let [[head args] (str/split (subs (str/trim (str input)) 1) #"\s+" 2)
          builtin (conj (set (map first commands)) "/exit")]
      (when-not (contains? builtin (str "/" head))
        (when-let [t (some-> (:tools ctx) deref (get head))]
          [t (str/trim (str args))])))))

(defn- needs-usage?
  "brainyard's rule: a tool called with nothing, that has arguments it cannot
  do without, is told how to call it instead of asked about."
  [tool args]
  (and (str/blank? args) (boolean (seq (remove :optional? (schema/inputs tool))))))

(defn- tool-usage
  "brainyard's `Usage:` and `Inputs:` lines for a tool (`format-inputs-schema`):
  what `/<tool>` with no arguments prints when some are required."
  [tool]
  (str/join "\n" (concat [(str "Usage: /" (:name tool) " <args> or <question>") "Inputs:"]
                          (for [{:keys [name desc optional?]} (schema/inputs tool)]
                            (str "  " name (when desc (str "  — " desc)) (when optional? "  (optional)"))))))

(def ^:private tool-request-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/tool-request.edn")))))

(defn tool-request-text
  "What `/<tool> args` asks the model, from `prompts/tool-request.edn`."
  [tool-id args]
  (let [{:keys [with-args no-args]} @tool-request-templates]
    (-> (if (str/blank? args) no-args with-args)
        (str/replace "{tool}" tool-id)
        (str/replace "{args}" (str args)))))

(defn ^{:reads #{:input} :writes #{:input}}
  tool-request
  "Before a turn: a `/<tool> args` line becomes the request brainyard sends the
  model (`tool-request-text`), with brainyard's `[tool]` line and the tool's
  description said first (byj-gly.273.2). Any other line is left as it is."
  [{:keys [st-memory] :as ctx}]
  (when-let [[tool args] (tool-line ctx (:input @st-memory))]
    (emit ctx :info (str "[" (:name tool) "] " (first (str/split-lines (str (:description tool))))))
    (swap! st-memory assoc :input (tool-request-text (:name tool) args)))
  :success)

(def ^:private loop-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/loop.edn")))))

(defn- fill [template m]
  (reduce-kv (fn [t k v] (str/replace t (str "{" (name k) "}") (str v))) template m))

(defn parse-loop
  "`/loop` line's words as `{:interval :prompt}` — Claude Code's /loop: a
  leading `5m`, else a trailing `every 5m` / `every 5 minutes`; none, a
  self-paced loop. nil when there is no prompt."
  [words]
  (let [w (str/trim (str words))
        unit {"s" "s" "sec" "s" "secs" "s" "second" "s" "seconds" "s" "m" "m" "min" "m" "mins" "m" "minute" "m"
              "minutes" "m" "h" "h" "hr" "h" "hrs" "h" "hour" "h" "hours" "h" "d" "d" "day" "d" "days" "d"}]
    (when-not (str/blank? w)
      (if-let [[_ iv rest-w] (re-matches #"(\d+[smhd])\s+([\s\S]+)" w)]
        {:interval iv :prompt (str/trim rest-w)}
        (if-let [[_ p n u] (re-matches #"([\s\S]+?)\s+every\s+(\d+)\s*([a-z]+)\s*" w)]
          (if-let [u (unit u)]
            {:interval (str n u) :prompt (str/trim p)}
            {:prompt w})
          {:prompt w})))))

(defn ^{:reads #{:input} :writes #{:input}}
  loop-request
  "Before a turn: `/loop [interval] <prompt>` (byj-gly.325), as Claude Code's
  /loop. With an interval, the prompt is scheduled to recur (`schedule$add`'s
  store, a job of this session) and runs now; with none, it runs now with
  `prompts/loop.edn`'s self-pacing words, under which the model arms each next
  iteration itself. Any other line is left as it is."
  [{:keys [st-memory config] :as ctx}]
  (let [input (str (:input @st-memory))]
    (when-let [[_ words] (re-matches #"/loop\s+([\s\S]+)" input)]
      (when-let [{:keys [interval prompt]} (parse-loop words)]
        (if-let [{:keys [cron every-text rounded]} (some-> interval schedule/interval->cron)]
          (let [r (schedule/add! (or (:project-dir config) (:working-dir config) ".")
                                 {:prompt prompt :cron cron :session (some-> (:session-id config) str)})]
            (if (:error r)
              (emit ctx :error (str "/loop: " (:error r)))
              (emit ctx :info (fill (:scheduled @loop-templates)
                                    {:every every-text :cron cron :id (:id r)
                                     :rounded (if rounded (str " (rounded: " rounded ")") "")})))
            (swap! st-memory assoc :input prompt))
          (swap! st-memory assoc :input (fill (:self-paced @loop-templates) {:prompt prompt :loop-input input}))))))
  :success)

;; --- commands that stand for an agent (byj-gly.350) -------------------------------

(def ^:private bundled-agent-commands
  "The slash commands that stand for an agent shipped in the binary:
  `resources/brainyard/commands/` (an index, and an entry each)."
  (delay (vec (for [n (edn/read-string (slurp (io/resource "brainyard/commands/index.edn")))]
                (assoc (edn/read-string (slurp (io/resource (str "brainyard/commands/" n ".edn"))))
                       :name n :source "builtin")))))

(def ^:private command-defaults
  "What an entry that says only `:command`, what it stands for and `:purpose`
  is given: `resources/brainyard/commands/defaults.edn` (byj-gly.351) — a
  `:usage` for each kind of entry (byj-gly.362.1)."
  (delay (edn/read-string (slurp (io/resource "brainyard/commands/defaults.edn")))))

(defn command-tool-globs
  "The tools a command stands for, as names or globs (byj-gly.362.1):
  `agent$<name>` for `:agent`, else its `:tools`, else none — a prompt
  command, done with the session agent's own tools."
  [e]
  (cond (:agent e) [(str "agent$" (:agent e))]
        (seq (:tools e)) (vec (:tools e))
        :else []))

(defn- command-kind [e]
  (cond (:agent e) :agent (seq (:tools e)) :tools :else :prompt))

(declare share-takes?)

(def ^:private yielding-builtins
  "The session's own commands that hand a line they cannot run themselves to
  a command file of their name, which makes it a turn (byj-gly.362.1): each
  with a predicate on the words after the command, true for the lines the
  command runs itself. `/share` runs its verbs and gives a request in words
  to `commands/share.edn`."
  {"/share" #'share-takes?})

(defn command-roots
  "Where a project's own commands that stand for an agent are, most local
  first (byj-gly.351): `.brainyard/commands/` in the project, then
  `~/.brainyard/commands/`, as agents are found (`agents/roots`).
  `:command-roots` replaces them — one test's commands."
  [config]
  (->> (if-let [explicit (seq (:command-roots config))]
         (map #(hash-map :dir (io/file %) :source "project") explicit)
         [{:dir (io/file (or (:project-dir config) (:working-dir config) ".") ".brainyard" "commands") :source "project"}
          (when-let [home (System/getenv "HOME")]
            {:dir (io/file home ".brainyard" "commands") :source "user"})])
       (remove nil?)
       (filter #(.isDirectory ^java.io.File (:dir %)))
       vec))

(defn- before-problems
  "Why a command's `:before` cannot be one (byj-gly.468): a vector of maps,
  each a `:when` regex that compiles, a `:call` among the tools the command
  stands for, and `:args` a map when given."
  [e]
  (when (contains? e :before)
    (if-not (and (vector? (:before e)) (every? map? (:before e)))
      [(str ":before must be a vector of maps, like [{:when \"^stop$\" :call \"x$y\" :args {}}], not " (pr-str (:before e)))]
      (for [b (:before e)
            p [(when-not (and (string? (:when b))
                              (try (re-pattern (:when b)) true (catch Throwable _ false)))
                 (str ":before :when must be a regex string, not " (pr-str (:when b))))
               (when-not (and (string? (:call b))
                              (some #(permission/glob-match? % (:call b)) (command-tool-globs e)))
                 (str ":before :call must be one of the command's :tools, not " (pr-str (:call b))))
               (when (and (contains? b :args) (not (map? (:args b))))
                 (str ":before :args must be a map, not " (pr-str (:args b))))]
            :when p]
        p))))

(defn- command-problems
  "Why a command file cannot be one, or nil: it must say which `:command` it
  is — a slash and a lowercase name, not one of the session's own commands —
  what it is `:purpose`d for, and at most one of the `:agent` it stands for
  and the `:tools` it is done with (names or globs); with neither it is a
  prompt command (byj-gly.362.1). The texts it gives are strings, and
  `:rephrase` is `:predictor` or `:template`."
  [e builtin]
  (not-empty
   (vec (concat
         (when-not (map? e) ["not an EDN map"])
         (when (map? e)
           (concat
            (when-not (and (string? (:command e)) (re-matches #"/[a-z][a-z0-9-]*" (:command e)))
              [(str ":command must be a slash and a lowercase name, like \"/review\", not " (pr-str (:command e)))])
            (when (contains? builtin (:command e))
              [(str (:command e) " is one of the session's own commands")])
            (when (and (contains? e :agent) (not (and (string? (:agent e)) (re-matches #"[a-z][a-z0-9-]*" (:agent e)))))
              [(str ":agent must name an agent, like \"explore\", not " (pr-str (:agent e)))])
            (when (and (contains? e :tools)
                       (not (and (vector? (:tools e)) (seq (:tools e))
                                 (every? #(and (string? %) (not (str/blank? %))) (:tools e)))))
              [(str ":tools must be a vector of tool names or globs, like [\"share$*\"], not " (pr-str (:tools e)))])
            (when (and (contains? e :agent) (contains? e :tools))
              [":agent and :tools cannot both be given: :agent \"x\" is :tools [\"agent$x\"]"])
            (when (str/blank? (str (:purpose e))) [":purpose must say what the command is for"])
            (for [k [:usage :template :default-request :purpose]
                  :when (and (contains? e k) (not (string? (get e k))))]
              (str k " must be a string"))
            (when (and (contains? e :rephrase) (not (#{:predictor :template} (:rephrase e))))
              [":rephrase must be :predictor or :template"])
            (before-problems e)))))))

(defn agent-commands
  "Every command that stands for an agent, as `{:commands {\"/cmd\" entry} :problems
  [{:file :problems}] :shadowed [{:command :dropped :kept}]}`: the project's
  `.brainyard/commands/*.edn`, over `~/.brainyard/commands/`, over the
  bundled ones (byj-gly.351). A command defined twice keeps the most local
  and reports the one it dropped; a file that is not a command is reported
  and left out. Re-read every call, so a file added or edited is in force at
  the next line. An entry is given `defaults.edn`'s `:usage`, `:template` and
  `:rephrase` when it says none."
  [config]
  (let [own (set (map first commands))
        builtin (apply disj (conj own "/exit") "/init" (keys yielding-builtins))
        from-files (for [{:keys [dir source]} (command-roots config)
                         ^java.io.File f (sort-by #(.getName ^java.io.File %) (.listFiles ^java.io.File dir))
                         :when (and (.isFile f) (str/ends-with? (.getName f) ".edn"))]
                     (let [e (try (edn/read-string (slurp f)) (catch Throwable ex {::unreadable (ex-message ex)}))]
                       (if-let [why (::unreadable e)]
                         {:file (.getPath f) :problems [(str "unreadable EDN: " why)]}
                         (if-let [ps (command-problems e builtin)]
                           {:file (.getPath f) :problems ps}
                           {:entry (merge (select-keys @command-defaults [:template :rephrase])
                                          {:usage (get @command-defaults (case (command-kind e)
                                                                           :agent :usage
                                                                           :tools :tools-usage
                                                                           :prompt :prompt-usage))}
                                          e {:name (str/replace (.getName f) #"\.edn$" "") :source source :file (.getPath f)})}))))
        entries (concat (keep :entry from-files) @bundled-agent-commands)
        [kept dropped] (reduce (fn [[kept dropped] e]
                                 (if (contains? kept (:command e))
                                   [kept (conj dropped e)]
                                   [(assoc kept (:command e) e) dropped]))
                               [{} []] entries)]
    {:commands kept
     :problems (vec (keep #(when (:problems %) %) from-files))
     :shadowed (vec (for [d dropped]
                      {:command (:command d) :dropped (or (:file d) (:source d))
                       :kept (let [k (get kept (:command d))] (or (:file k) (:source k)))}))}))

(defn agent-command
  "`[entry words]` for a line whose first word is a command a file defines —
  one that stands for an agent, for tools, or a prompt — its words after the
  command, spaces folded; else nil. A line one of the session's own commands
  runs itself is never one, though a file has its name (`yielding-builtins`,
  byj-gly.362.1)."
  [config input]
  (let [[head words] (str/split (str/trim (str input)) #"\s+" 2)]
    (when-let [e (get (:commands (agent-commands config)) head)]
      (let [words (str/trim (str/replace (str words) #"\s+" " "))]
        (when-not (when-let [takes? (get yielding-builtins head)]
                    (takes? (if (str/blank? words) [] (str/split words #" "))))
          [e words])))))

(defn commands-for
  "The session's commands as `commands` lists them, and the commands a
  project or the person defined, each with its purpose and the agent it
  drives — what /help says and a front end completes (byj-gly.351)."
  [config]
  (let [own (set (map first commands))]
    (into (vec commands)
          (for [[cmd e] (sort-by key (:commands (agent-commands config)))
                :when (not (own cmd))]
            [cmd (str (:purpose e)
                      (when-let [t (seq (command-tool-globs e))] (str " — " (str/join ", " t)))
                      (when-not (:default-request e) "; WORDS"))]))))

(defn help-text-for
  "`/help`: `commands-for`'s commands, rendered as `help-text` is, and the
  command files that were left out and why."
  [config]
  (let [{:keys [problems shadowed]} (agent-commands config)]
    (str/join "\n" (concat (for [[cmd doc] (commands-for config)]
                              (str cmd (apply str (repeat (max 1 (- 10 (count cmd))) " ")) doc))
                            (for [{:keys [file problems]} problems]
                              (str "✗ " file " is not a command: " (str/join "; " problems)))
                            (for [{:keys [command dropped kept]} shadowed]
                              (str "· " command " in " dropped " is hidden by " kept))))))

(defn command-tools
  "The registered tools a command stands for — those its names or globs match
  (`command-tool-globs`), by name: `[]` for a prompt command, which stands for
  none, and nil when it names tools and none is registered here (subagent
  calls may be off, or the family's setting) (byj-gly.362.1)."
  [ctx e]
  (let [globs (command-tool-globs e)]
    (if (empty? globs)
      []
      (not-empty (vec (for [[n t] (sort-by key (some-> (:tools ctx) deref))
                            :when (some #(permission/glob-match? % n) globs)]
                        t))))))

(defn- command-vars
  "What a command's `:usage` and `:template` may name."
  [e]
  {:purpose (:purpose e) :command (:command e) :agent-tool (str "agent$" (:agent e))
   :tools (str/join ", " (command-tool-globs e))})

(defn command-request-text
  "A command's turn input from its template: the request — the words after
  the command, or its `:default-request` — with what the command is for and
  how its agent is used, as `resources/brainyard/commands/<name>.edn` says.
  Never empty: an agent is not asked nothing (byj-gly.309.7)."
  [e words]
  (let [vars (command-vars e)]
    (fill (:template e) (assoc vars
                               :request (if (str/blank? words) (:default-request e) words)
                               :usage (fill (str (:usage e)) vars)))))

(defn- before-fill
  "`v` with each `$n` in its strings the match's group n (an unmatched group
  is \"\")."
  [v groups]
  (walk/postwalk (fn [x]
                   (if (string? x)
                     (str/replace x #"\$(\d)" (fn [[_ n]] (str (nth groups (parse-long n) nil))))
                     x))
                 v))

(defn command-before
  "The call a command's `:before` makes ahead of its turn (byj-gly.468): the
  first entry whose `:when`, a regex, finds the command's words; its `:call`,
  one of the `tools` the command stands for, called with `:args` — each `$n`
  in them the match's group n — as the person's own call, through the gates.
  A step that is the command's and not a judgment — /emacs coach starting the
  stance — then happens whatever the model does; the turn is told it was
  done. `{:call :args :result :error?}`, or nil when no entry matches."
  [ctx e words tools]
  (when-let [[b groups] (first (keep (fn [b]
                                       (when-let [m (re-find (re-pattern (:when b)) (str words))]
                                         [b (if (string? m) [m] (vec m))]))
                                     (:before e)))]
    (let [args (before-fill (or (:args b) {}) groups)
          tool (first (filter #(= (:call b) (:name %)) tools))
          result (if tool
                   (tools/call-tool tool args (assoc ctx :person-call? true))
                   {:error (str (:call b) " is not one of " (:command e) "'s tools here")})
          error? (boolean (or (:error-message result) (:error result) (:hook-blocked result)))]
      (emit ctx (if error? :error :info)
            (str (if error? "✗ " "⏺ ") (:call b) " " (pr-str args) " → " (pr-str result)))
      {:call (:call b) :args args :result result :error? error?})))

(defn- tools-text
  "The tools a command stands for as the rephrasing predictor reads them: each
  one's name, what it does, and what it takes — or that there are none, the
  agent's own being what it does the work with (byj-gly.362.1)."
  [tools]
  (if (empty? tools)
    "(none: the command names no tools — the agent does it with its own)"
    (str/join "\n\n" (for [tool tools]
                       (str/join "\n" (concat [(str (:name tool) " — " (str/trim (str (:description tool))))]
                                               (for [{:keys [name desc optional?]} (schema/inputs tool)]
                                                 (str "  :" (clojure.core/name name) (when desc (str " — " desc)) (when optional? " (optional)")))))))))

(defn ^{:reads #{:input :turn-count :history-context} :writes #{:input :command-input :usage :usage-calls}}
  command-request
  "Before a turn: a command that stands for an agent (`/init`) becomes the
  turn's input — the request the session's agent is asked, which it does by
  calling the command's subagent as a tool, as often as the work needs
  (byj-gly.350). The node's predictor (session/rephrase-command) writes it
  from the line, the command's entry, the subagent's tool and the turns so
  far, when the entry says `:rephrase :predictor`, the session has turns and
  `:command-rephrase?` is on; otherwise, or when the predictor fails, the
  entry's template does. The line as typed is kept as `:command-input`, and
  the person is told what their agent is asked. Any other line is left as it
  is."
  [{:keys [st-memory config node] :as ctx}]
  (swap! st-memory assoc :command-input nil)
  (let [input (str (:input @st-memory))]
    (when-let [[e words] (agent-command config input)]
      (when-let [tools (command-tools ctx e)]
        (let [request (if (str/blank? words) (:default-request e) words)
              ;; the command's own step, first: what the turn is then told
              before (command-before ctx e words tools)
              rephrase? (and (= :predictor (:rephrase e)) (:predictor node)
                             (not (false? (:command-rephrase? config)))
                             (pos? (:turn-count @st-memory 0)))
              predicted (when rephrase?
                          ;; the predictor runner adds to the cache counters
                          (swap! st-memory update :usage #(merge {:cache-read-tokens 0 :cache-write-tokens 0} %))
                          (try (:outputs (agent/run-node-predictor
                                          ctx {:command input :request request :purpose (:purpose e)
                                               :usage (fill (str (:usage e)) (command-vars e))
                                               :tools (tools-text tools)
                                               :history (or (:history-context @st-memory) "(no earlier turns)")}))
                               (catch Throwable ex
                                 (when (fx/cancelled? ex) (throw ex))
                                 nil)))
              question (or (some-> (:question predicted) str str/trim not-empty) (command-request-text e words))
              question (cond-> question
                         before (str "\n\n" (fill (:before-note @command-defaults)
                                                  {:command (:command e) :call (:call before)
                                                   :args (pr-str (:args before)) :result (pr-str (:result before))})))
              shown (or (some-> (:shown predicted) str str/trim not-empty)
                        (str (:command e) " → " (let [l (first (str/split-lines request))]
                                                   (if (> (count l) 100) (str (subs l 0 100) "…") l))))]
          (emit ctx :info shown)
          (swap! st-memory assoc :input question :command-input input)))))
  :success)

(defn schedule-command
  "`/schedule`: this session's scheduled prompts; `/schedule remove ID` stops one."
  [{:keys [config]} args]
  (let [project (or (:project-dir config) (:working-dir config) ".")
        sid (some-> (:session-id config) str)
        [verb id] args]
    (case verb
      nil (schedule/listing project sid)
      "remove" (if (schedule/remove! project id) (str "removed " id) (str "no schedule '" id "' — /schedule lists them"))
      (str "usage: /schedule [remove ID]"))))

(declare tool-entry)

(declare direct-call*)

(defn ^{:reads #{:input :previous-turns :console-activity} :writes #{:console-activity :previous-turns :history-context}}
  direct-call
  "Run `:<tool> …` (`direct-call*`)."
  [ctx]
  (direct-call* ctx)
  :success)

(defn- direct-call*
  "Run `:<tool> …` — brainyard's colon command, with the port's differences
  (byj-gly.273.3). The arguments are brainyard's kwargs or an EDN map
  (`toolshelf/parse-direct`), checked against the tool's schema first — a bad
  one lists what the tool takes. The call goes through `tools/call-tool` with
  the session's ctx, so the permission gates, the argument check and the hooks
  apply exactly as to a model's call; brainyard's goes round them (divergence
  `direct-calls-are-gated`). It runs between turns, as brainyard's must: a line
  typed while a turn runs waits in the queue. The result is shown whole — a
  tool block in a front end with one, the printed value in one without — and
  the next turn is told the line and the start of the result (brainyard's
  console activity, `:enable-console-activity`)."
  [{:keys [st-memory] :as ctx} & [{:keys [shown]}]]
  (refresh-config! ctx)
  (let [line (str/trim (:input @st-memory))
        id (second (re-find direct-line line))
        tool (some-> (:tools ctx) deref (get id))
        config (:config ctx)]
    (if (nil? tool)
      (emit ctx :error (str "unknown tool :" id " — /tools lists them"))
      (let [{:keys [args error]} (toolshelf/parse-direct line (schema/inputs tool))
            problems (when-not error (:problems (schema/check-args tool (tools/normalize-args args))))]
        (cond
          error (emit ctx :error (str ":" id ": " error))
          (seq problems)
          (emit ctx :error (str/join "\n" (concat [(str "Invalid args for :" id)]
                                                  (map #(str "  " %) problems)
                                                  ["Expected inputs (pass as :key value pairs, or one EDN map):"]
                                                  (for [{:keys [name type desc optional?]} (schema/inputs tool)]
                                                    (str "  :" (clojure.core/name name) " " type
                                                         (when optional? " (optional)") (when desc (str " — " desc)))))))
          :else
          (let [t0 (System/currentTimeMillis)
                ;; the person's own call: an agent's suggested next prompt
                ;; comes back to them (byj-gly.337.1)
                result (tools/call-tool tool args (assoc ctx :person-call? true))
                ms (- (System/currentTimeMillis) t0)
                error? (boolean (or (:error-message result) (:error result) (:hook-blocked result)))
                call {:tool-name id :tool-args args :tool-result result :tool-error? error?}]
            (record! ctx :tool (tool-entry call))
            (when-not (some-> (:present ctx) (apply [:tool-call (assoc call :tool-result (pr-str ((or shown identity) result)) :elapsed-ms ms)]))
              (emit ctx (if error? :error :info)
                    (str (if error? "✗ " "⏺ ") id " " (pr-str args) " · " ms " ms\n"
                         (str/trimr (with-out-str (clojure.pprint/pprint ((or shown identity) result)))))))
            (when (:enable-console-activity config true)
              (let [n (:console-activity-max-entries config 10)
                    d (toolshelf/digest line result error? (:console-activity-result-chars config 200))]
                (swap! st-memory
                       (fn [m]
                         (let [acts (vec (:console-activity m))
                               ;; an exact repeat of the newest is not news
                               acts (if (= d (peek acts)) acts (conj acts d))
                               acts (vec (take-last n acts))]
                           (set-turns (assoc m :console-activity acts) (:previous-turns m) config))))))
            (when-let [p (not-empty (str (:next-user-prompt result)))]
              (events/emit! ctx :session/suggestion {:next-user-prompt p}))
            result))))))

(declare memory-changed!)

(defn- memory-rows
  "The store's memories, filtered by `query`: `{:rows}` or `{:error}`."
  [config query]
  (let [{:keys [memories error]} (memory/list-memories config)]
    (if error {:error error} {:rows (memory/matching memories query)})))

(defn- memory-edit!
  "The memory `key` in the person's editor (`links/edit-argv`: vi when none is
  set, never the pager), handed the terminal; what comes back, when changed
  and not empty, is stored under the same key."
  [{:keys [hand-terminal env-fn] :as ctx} config key]
  (let [{:keys [memories error]} (memory/list-memories config)
        m (first (filter #(= key (:key %)) memories))
        env (or env-fn #(System/getenv %))]
    (cond
      error error
      (nil? m) (str "no memory " key " — /memory lists them")
      (nil? hand-terminal) (str "no terminal to edit in here; memory$remember with :key " key " rewrites it")
      :else
      (let [f (java.io.File/createTempFile (str "memory-" (str/replace key #"[^\w.-]" "_") "-") ".md")]
        (try
          (spit f (str (:content m) "\n"))
          (hand-terminal (links/edit-argv {"VISUAL" (env "VISUAL") "EDITOR" (env "EDITOR")} (.getPath f)) nil)
          (let [after (str/trim (slurp f))]
            (cond
              (= after (str/trim (:content m))) (str key " unchanged")
              (str/blank? after) (str key " left as it was: an empty memory is not stored — /memory delete " key " removes it")
              :else (let [r (memory/rewrite! config key after)]
                      (or (:error r) (str key " rewritten")))))
          (finally (.delete f)))))))

(defn- memory-command
  "`/memory` (byj-gly.327): the project's memories as the store lists them
  (`:memory-list-cmd`). A front end with a picker gets the rows to search,
  open and delete in; without one, the keys and their first lines. `show KEY`
  says one whole, `edit KEY` opens it in the person's editor and stores what
  comes back, `delete KEY` removes it (`:memory-forget-cmd`). The model's
  verbs, memory$recall and memory$remember, are unchanged — this is the
  person's way to curate what they find."
  [ctx [verb & more :as args]]
  (let [config (config-now ctx)
        key (str/trim (str/join " " more))
        refresh! #(let [{:keys [rows]} (memory-rows config "")]
                    (when rows (some-> (:present ctx) (apply [:memory {:rows rows :refresh? true}]))))]
    (case (if (false? (:memory? config)) ::off verb)
      ::off "memory is off here (:memory? false)"
      "show" (let [{:keys [rows error]} (memory-rows config "")]
               (or error (some #(when (= key (:key %)) (str (:key %) "\n\n" (:content %))) rows)
                   (str "no memory " key " — /memory lists them")))
      "edit" (let [said (memory-edit! ctx config key)] (memory-changed! ctx) (refresh!) said)
      "delete" (if (str/blank? key)
                 "usage: /memory delete KEY"
                 (let [r (memory/forget! config key)]
                   (memory-changed! ctx)
                   (refresh!)
                   (or (:error r) (str "deleted " key))))
      (let [query (str/join " " args)
            {all :rows error :error} (memory-rows config "")
            rows (memory/matching all query)]
        (cond
          error error
          ;; the picker gets every one, the query as its filter, to clear
          (some-> (:present ctx) (apply [:memory {:rows all :query query}])) nil
          (empty? rows) (if (str/blank? query) "no memories yet — memory$remember, or the store's own command, writes one"
                            (str "no memory matches \"" query "\""))
          :else (str/join "\n" (concat (for [{:keys [key content]} rows]
                                          (str key " — " (let [l (first (str/split-lines content))]
                                                           (if (> (count l) 100) (str (subs l 0 100) "…") l))))
                                        [(str (count rows) " memor" (if (= 1 (count rows)) "y" "ies")
                                              " · /memory show KEY · edit KEY · delete KEY")])))))))

(defn- login-command
  "`/login [target]` and `/logout target` (RFC-006, byj-gly.311): brainyard's
  `handle-login-command`. With no target, every one and where it stands. A
  CLI's own login is run with the terminal the front end hands over
  (`:hand-terminal`); with none — headless — the command to run is said."
  [{:keys [env-fn hand-terminal] :as ctx} action arg]
  (let [opts {:env (or env-fn #(System/getenv %)) :hand-terminal hand-terminal}
        arg (str/trim (str arg))]
    (cond
      (and (str/blank? arg) (= :login action)) (auth/overview opts)
      (str/blank? arg) "usage: /logout TARGET — /login lists them"
      :else (if-let [t (auth/find-target arg)]
              (if (= :login action) (auth/login! t opts) (auth/logout! t opts))
              (str "no sign-in target " arg " — /login lists them")))))

(defn- command-file-error
  "What a line a command file defines says when it is not a turn: its tools
  are not registered here (byj-gly.350, byj-gly.362.1), or it was given no
  words and has no request of its own (byj-gly.351)."
  [ctx cmd e]
  (cond
    (command-tools ctx e)
    (emit ctx :error (str "usage: " cmd " WORDS — " (when (:agent e) (str "what to ask agent$" (:agent e) ": ")) (:purpose e)))
    (:agent e)
    (emit ctx :error (str cmd " needs agent$" (:agent e) ", which is not available here:"
                          " subagent calls are off (:enable-subagent-calls), or the " (:agent e) " agent is not registered"))
    :else
    (emit ctx :error (str cmd " needs " (str/join ", " (command-tool-globs e)) ", which is not available here:"
                          " no tool registered matches — /tools lists them"))))

(defn- run-command*
  [{:keys [st-memory config] :as ctx}]
  ;; a command reads the config too — /config right after an edit shows it
  (refresh-config! ctx)
  (refresh-mcp! ctx)
  (let [[cmd] (str/split (:input @st-memory) #"\s+")
        {:keys [previous-turns usage]} @st-memory]
    (case cmd
      ("/quit" "/exit") (end ctx)
      "/clear" (do (swap! st-memory #(set-turns (dissoc % :console-activity) [] config))
                   (emit ctx :info "history cleared"))
      "/fork" (let [{:keys [text error]} (fork-command ctx (rest (str/split (str/trim (:input @st-memory)) #"\s+")))]
                (emit ctx (if error :error :info) (or error text)))
      ("/rewind" "/undo-turn") (let [{:keys [text error]} (rewind-command ctx cmd (rest (str/split (str/trim (:input @st-memory)) #"\s+")))]
                                 (emit ctx (if error :error :info) (or error text)))
      "/history" (emit ctx :info (or (history/format-previous-turns previous-turns
                                                            (assoc config :full-depth 0
                                                                   :max-answer-chars 200))
                                     "(no turns yet)"))
      "/usage" (emit ctx :info (str (usage-report @st-memory (rest (str/split (:input @st-memory) #"\s+")))
                                    (when (claude-usage-wanted? ctx)
                                      (str "\n\n" (subscription-text (claude-usage-now ctx))))
                                    ;; Codex's plan, as its rollouts last reported it (byj-gly.430.7)
                                    (when-let [l (codex-usage/limits (:codex-home config))]
                                      (str "\n\nCodex plan" (some->> (:plan l) (str " ")) ":\n"
                                           (str/join "\n" (codex-usage/limit-lines l))))))
      "/doctor" (emit ctx :info (doctor-report ctx))
      "/loop" (emit ctx :info (:usage @loop-templates))
      "/schedule" (emit ctx :info (schedule-command ctx (rest (str/split (str/trim (:input @st-memory)) #"\s+"))))
      "/memory" (some->> (memory-command ctx (remove str/blank? (rest (str/split (str/trim (:input @st-memory)) #"\s+"))))
                         (emit ctx :info))
      "/capture" (capture! ctx (second (str/split (:input @st-memory) #"\s+")))
      "/task" (emit ctx :info (task-report ctx (rest (str/split (:input @st-memory) #"\s+"))))
      "/todo" (emit ctx :info (todo-report ctx (rest (str/split (:input @st-memory) #"\s+"))))
      "/proposals" (emit ctx :info (proposals-report ctx (rest (str/split (:input @st-memory) #"\s+"))))
      "/model" (some->> (model-report ctx (rest (str/split (:input @st-memory) #"\s+"))) (emit ctx :info))
      "/mcp" (let [args (str/trim (subs (str/trim (:input @st-memory)) 4))]
               (some->> (mcp-command ctx args) (emit ctx :info))
               ;; an open picker shows what the command changed
               (when (and (seq args) (:mcp-box ctx) (not (str/starts-with? args "registry")))
                 (some-> (:present ctx) (apply [:mcp {:rows (mcp-rows ctx) :catalog (mcp-catalog-rows ctx)
                                                      :refresh? true}]))))
      "/tools" (some->> (tools-report ctx (str/trim (subs (str/trim (:input @st-memory)) 6))) (emit ctx :info))
      "/compact" (emit ctx :info (compact! ctx (second (str/split (:input @st-memory) #"\s+"))))
      "/status" (emit ctx :info (status-report ctx))
      "/allow-path" (emit ctx :info (allow-path! ctx (str/trim (subs (str/trim (:input @st-memory)) 11))))
      "/share" (if-let [[e] (agent-command config (:input @st-memory))]
                 ;; words /share hands to commands/share.edn, not a turn here
                 ;; when its tools are not registered (byj-gly.362.1)
                 (command-file-error ctx cmd e)
                 (emit ctx :info (share-command ctx (rest (str/split (str/trim (:input @st-memory)) #"\s+")))))
      "/help" (emit ctx :info (help-text-for config))
      "/login" (emit ctx :info (login-command ctx :login (subs (str/trim (:input @st-memory)) (count "/login"))))
      "/logout" (emit ctx :info (login-command ctx :logout (subs (str/trim (:input @st-memory)) (count "/logout"))))
      "/config" (let [[_ k v] (str/split (str/trim (:input @st-memory)) #"\s+" 3)]
                  (cond
                    (= "unset" k) (if (str/blank? (str v))
                                    (emit ctx :info "usage: /config unset KEY")
                                    (config-set ctx v nil true))
                    (str/blank? (str v)) (some->> (config-report ctx k) (emit ctx :info))
                    :else (config-set ctx k v false)))
      (if-let [[tool _] (tool-line ctx (:input @st-memory))]
        (emit ctx :info (tool-usage tool))
        (if-let [[e] (agent-command config (:input @st-memory))]
          (command-file-error ctx cmd e)
          (emit ctx :error (str "unknown command " cmd " — /help lists them, /tools the tools")))))))

(defn- share-ref-like?
  "An argument `/share` takes as a session reference: `team`, a `<kind>:<id>`
  reference (RFC-043), or a session id or its prefix."
  [a]
  (boolean (or (= "team" a) (re-matches #"[a-z]+:\S+" a) (re-matches #"[0-9a-f][0-9a-f-]{5,}" a))))

(defn share-takes?
  "Whether `/share` runs the words after it itself (byj-gly.362.1): none, or
  one of its verbs with what that verb takes — `start` references and its
  `--target T` and `--record`, `stop`, `pause` and `resume` at most one
  reference, `kick` one user, `record on|off` at most one reference
  (byj-gly.362.7), `allow` and `refuse` nothing. Any other line is
  a request in words, which `commands/share.edn` makes a turn."
  [[verb & args]]
  (case verb
    nil true
    "start" (loop [[a & more :as as] args]
              (cond (empty? as) true
                    (= "--target" a) (if (seq more) (recur (rest more)) false)
                    (= "--record" a) (recur more)
                    (share-ref-like? a) (recur more)
                    :else false))
    ("stop" "pause" "resume") (or (empty? args) (and (= 1 (count args)) (share-ref-like? (first args))))
    "kick" (= 1 (count args))
    "record" (and (#{"on" "off"} (first args))
                  (or (= 1 (count args)) (and (= 2 (count args)) (share-ref-like? (second args)))))
    ("allow" "refuse") (empty? args)
    false))

(defn- share-command
  "`/share` (RFC-043): with no argument, who is watching and where,
  and the other shares this process started; `start [REF…|team]` shares
  this session or the sessions named; `stop` ends sharing — the sidecar's
  task, and its process with it; `pause`, `resume` and `kick USER` are said
  to the sidecar as `:session.share/control` on this session's bus, which it
  watches. With a REF, `stop`, `pause` and `resume` act on that share
  instead. What it knows of this session's share is the sidecar's last
  `:session.share/presence`. The operations are `share-tools`', which the
  session's agent calls as `share$*` (byj-gly.362.2)."
  [ctx [sub & more]]
  (let [[user] more]
    (cond
      (nil? sub) (share-tools/status ctx)
      (= "start" sub) (let [[opts refs] (loop [[a & m :as args] more opts {} refs []]
                                          (cond
                                            (empty? args) [opts refs]
                                            (= "--target" a) (recur (rest m) (assoc opts :target (first m)) refs)
                                            (= "--record" a) (recur m (assoc opts :record? true) refs)
                                            :else (recur m opts (conj refs a))))]
                        (share-tools/start ctx (assoc opts :refs refs) #(emit ctx :info %)))
      ;; an injected turn waiting for the person where there is no overlay
      ;; (the inline session, byj-1wv.3.12)
      (#{"allow" "refuse"} sub) (if-let [f (:approve-answer ctx)]
                                  (f (= "allow" sub))
                                  "nothing is waiting for your approval")
      (and (#{"stop" "pause" "resume"} sub) user) (share-tools/other! ctx sub user)
      (#{"stop" "pause" "resume" "kick"} sub) (share-tools/control! ctx sub user)
      (and (= "record" sub) (#{"on" "off"} user)) (share-tools/record! ctx (= "on" user) (second more))
      :else "usage: /share [start [REF…|team] | stop [REF] | pause [REF] | resume [REF] | kick USER | record on|off [REF] | allow | refuse | WORDS]")))

(defn- note!
  "A note for people only (`:inject :as :note`, RFC-043): recorded in the
  trajectory beside the turns, and said as `:session.share/note` — which the
  renderer shows and the channel's subscribers see. Never a turn: nothing of it
  enters the session's memory, its history, or a model's messages."
  [{:keys [config] :as ctx} text from]
  (let [from (if (map? from) (select-keys from [:user :name :role]) from)]
    (when-let [f (trajectory/log-file config)]
      (trajectory/append! f (trajectory/note-record {:session (:session-id config) :text text :from from})))
    (events/emit! ctx :session.share/note (cond-> {:text text} (some? from) (assoc :from from)))))

(defn- allow-path!
  "`/allow-path DIR` — brainyard's: DIR is added to the directories the path
  gate lets a tool reach, for this session, as answering its prompt with `a`
  would (byj-gly.145). With no DIR, the directories in force.

  Two things make it a person's act and nothing else's (RFC-012):
  - a line the session channel handed over (another session, a team leader —
    RFC-031) is refused: the gate's point is that the person answers it;
  - it is never written to config.edn. The prompt's `r` is the one route by
    which a permission key persists, and a command does not ask as a person
    (`configbox-test/no-command-can-ask-as-a-person`).
  brainyard checks neither (divergence `allow-path-is-the-persons`)."
  [{:keys [allowed-dirs-box] :as ctx} arg]
  (let [config (config-now ctx)
        wd (:working-dir config ".")]
    (cond
      (str/blank? arg)
      (str "the directories tools may reach:\n"
           (str/join "\n" (map #(str "  " %) (permission/allowed-dirs (assoc ctx :config config) wd)))
           (when (seq (some-> allowed-dirs-box deref))
             (str "\n(" (count @allowed-dirs-box) " added with /allow-path, for this session)")))

      (some-> (:channel ctx) :in-flight deref)
      "not allowed: /allow-path came over the session channel — the path gate is the person's to widen"

      (nil? allowed-dirs-box)
      "not allowed here: this session has no place to keep it"

      :else
      (let [expanded (if (str/starts-with? arg "~") (str (System/getenv "HOME") (subs arg 1)) arg)
            f (io/file expanded)
            f (if (.isAbsolute f) f (io/file wd expanded))]
        (if-not (.isDirectory f)
          (str "not allowed: " arg " is not a directory")
          (let [d (config/canonical-dir (.getPath f))]
            (swap! allowed-dirs-box #(vec (distinct (conj (vec %) d))))
            (str "tools may reach " d " for this session — not saved; --allow-path " d
                 ", or `r` at the prompt, keeps it")))))))

(defn- config-changes
  "The keys whose value differs between `from` and `to`, as `[k old new]`."
  [from to ks]
  (for [k (sort ks) :when (not= (get from k) (get to k))] [k (get from k) (get to k)]))

(defn- rebuild-ctx!
  "Build what `agent/make-ctx` builds from `config` again — the tools, hooks and
  prompt zones — and lay it over the ctx through its `:ctx-box` (RFC-002). The
  session's own registries (tasks, subagents, wakeups) are kept; MCP tools come
  from the run's box as it stands now (byj-gly.182)."
  [{:keys [ctx-box] :as ctx} config]
  (let [rebuilt (agent/make-ctx
                 (assoc (select-keys ctx [:lm :on-event :permission-fn :permission-mode-box :allowed-dirs-box :params
                                          :mcp-clients :mcp-box :env-fn
                                          :subagent-counter :subagent-registry :subagent-scope
                                          :task-registry :wakeups :registry :start-session])
                        :config config
                        :tools (:base-tools ctx)
                        ;; the caller's hooks — an SDK client's, a test's — which a
                        ;; rebuild otherwise dropped silently (byj-g5n.2)
                        :hooks (:extra-hooks ctx)
                        ;; the caller's own zones; the stable ones are rebuilt
                        :zones (dissoc (:zones ctx) :agent-core :session-context)))]
    (swap! ctx-box merge (select-keys rebuilt [:config :tools :hooks :extra-hooks :zones :base-tools
                                               :mcp-clients :mcp-version :mcp-waiting]))))

(defn- refresh-mcp!
  "Build the ctx again when the run's MCP servers moved since it was built — a
  server started, stopped or restarted from `/mcp`, in this session or another
  (byj-gly.182) — so a turn is shown, and may call, the tools that are there."
  [{:keys [ctx-box mcp-box] :as ctx}]
  (when (and ctx-box mcp-box)
    (let [now (merge ctx @ctx-box)]
      (when (not= (:version @mcp-box) (:mcp-version now))
        (rebuild-ctx! now (:config now))
        (let [clients (mcp/box-clients mcp-box)]
          (emit ctx :info (str "MCP servers changed, in force from this turn: "
                               (count (mcp/box-tools mcp-box)) " tool(s)"
                               (when (seq clients) (str " from " (str/join ", " (sort (keys clients))))))))))))

(defn- await-mcp!
  "Before a turn, wait — up to `:mcp-wait-ms` — for the MCP servers still
  starting that a user tool calls, or whose tools the agent's own tool list
  names, so the turn has the tool rather than going on without it: a session
  starts its servers in the background and builds its tools at once, so the
  first question, a resumed session's above all, came before them
  (byj-gly.300) — and a built agent's, on every A2A call that opens a session
  (byj-1wv.115.46). `refresh-mcp!` then builds the tools again."
  [{:keys [ctx-box mcp-box] :as ctx}]
  (when (and ctx-box mcp-box)
    (let [now (merge ctx @ctx-box)
          waiting (filterv #(contains? (:starting @mcp-box) %) (:mcp-waiting now))]
      (when (seq waiting)
        (emit ctx :info (str "waiting for MCP server(s) " (str/join ", " waiting) ", whose tools this turn uses"))
        (let [left (mcp/await-started mcp-box waiting (get-in now [:config :mcp-wait-ms] 30000))]
          (when (seq left)
            (emit ctx :info (str "still starting: " (str/join ", " left)
                                 " — this turn goes on without their tools"))))))))

(defn- apply-mcp-servers!
  "An edited `:mcp-servers`, made so in the run's box: `mcp/reconcile!`
  compares the config with what the box runs, so sessions sharing the box do
  not each restart what another just started (byj-gly.223), and a server that
  failed is tried again once its spec changes (byj-gly.224). What failed is
  the server's problem, for `/mcp` to show."
  [{:keys [mcp-box]} _before after]
  (when mcp-box
    (mcp/reconcile! mcp-box (server-specs {:mcp-servers after}))))

(defn- refresh-config!
  "Read the config layers again, as the run first read them, and put what they
  now say in force from this turn: the config, and the tools, hooks and prompt
  zones `agent/make-ctx` builds from it, into the ctx's `:ctx-box`, which every
  later leaf reads over the ctx (RFC-002). brainyard swaps its cached config at
  once; here it is swapped at a turn's start, since a turn's tools and prompt
  are built once and a change mid-turn would pull them from under it.

  A key read only when the run starts (`config/next-session-keys`) keeps the
  value it started with, and the person is told once that it waits for the next
  session. What changed is said either way — a setting that silently took
  effect, or silently did not, is the thing this exists to stop."
  [{:keys [st-memory ctx-box reload-config config config-loaded] :as ctx}]
  (when (and ctx-box reload-config)
   (let [{fresh :config problems :problems} (try (reload-config) (catch Throwable e {:problems [(ex-message e)]}))]
      (doseq [p problems] (emit ctx :error (str "config: " p)))
      (when fresh
        ;; compared LOAD against LOAD: the ctx's config also holds what the run
        ;; worked out from nil (the predictor roots, the allowed dirs), which
        ;; the files do not say and which is not an edit
        (let [before (or config-loaded fresh)
              ;; the keys that name the run itself are not settings: they
              ;; differ between two loads by construction, never by an edit
              settings (remove configbox/per-run-keys config/config-keys)
              pending (config-changes before fresh (filter config/applies-next-session? settings))
              applied (config-changes before fresh (remove config/applies-next-session? settings))
              told (:config-pending-told @st-memory #{})
              new-pending (remove (fn [[k _ v]] (contains? told [k v])) pending)]
          (swap! ctx-box assoc :config-loaded fresh)
          (when (seq applied)
            (do
              ;; servers first, so the tools built below are the ones running
              (when-let [[_ before after] (first (filter #(= :mcp-servers (first %)) applied))]
                (apply-mcp-servers! ctx before after))
              ;; the edits onto the config in force: a key worked out at the
              ;; start stays worked out
              (rebuild-ctx! ctx (merge config (into {} (for [[k _ v] applied] [k v]))
                                       (select-keys fresh [:config-sources])))
              ;; how much every viewer shows follows an edited :output-style
              (when-let [[_ _ style] (first (filter #(= :output-style (first %)) applied))]
                (some-> (:display-box ctx) (reset! (get config/output-styles style :normal))))
              (events/emit! ctx :config/applied {:keys (mapv first applied)})
              (emit ctx :info (str "config changed, in force from this turn: "
                                   (str/join ", " (for [[k a b] applied] (configbox/change-line k a b)))))))
          (when (seq new-pending)
            (swap! st-memory update :config-pending-told (fnil into #{}) (map (fn [[k _ v]] [k v]) new-pending))
            (emit ctx :info (str "config changed, applies from the next session: "
                                 (str/join ", " (for [[k a b] new-pending] (configbox/change-line k a b)))))))))))

(def ^:private memory-hint-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/memory-hint.edn")))))

(defn- store-memories
  "The project's memories, listed once and kept: a listing is a process (about
  0.2 s for bd), and a turn should not pay it. Listed again after `:refresh-ms`
  — a person or another session may have written one — or once something here
  changed the store (`memory-changed!`)."
  [{:keys [memory-cache]} config refresh-ms]
  (let [now (System/currentTimeMillis)
        c (some-> memory-cache deref)]
    (if (and c (< (- now (:at c)) refresh-ms))
      (:memories c)
      (let [ms (or (:memories (memory/list-memories config)) [])]
        (some-> memory-cache (reset! {:at now :memories ms}))
        ms))))

(defn memory-changed!
  "Forget the kept listing: the next turn lists the store again."
  [ctx]
  (some-> (:memory-cache ctx) (reset! nil)))

(defn ^{:reads #{:input} :writes #{:memory-hint}}
  memory-hint
  "The memories this turn's question is about, as a hint for the turn's
  user-context zone (byj-gly.335, RFC-042): `memory/rank` over the store's
  listing, with the node's thresholds — `:min-terms`, `:min-score`,
  `:min-share`, `:max` — and each hit's key and first line, clipped to
  `:line-chars`, in the words of prompts/memory-hint.edn. Nil when
  `:memory-hint?` is off, memory is off, or nothing matches well enough.
  Every turn, not once a session: the zone is not carried into the next
  turn's history, so a hint given once is not there to be seen again."
  [{:keys [st-memory node] :as ctx}]
  (let [config (config-now ctx)
        q (:input @st-memory)
        hits (when (and (:memory-hint? config) (not (false? (:memory? config))) (not (str/blank? (str q))))
               (memory/rank (store-memories ctx config (:refresh-ms node 300000)) q
                            (select-keys node [:min-terms :min-score :min-share :max])))
        {:keys [hint item]} @memory-hint-templates
        clip (fn [s n] (let [s (str/trim (str s))] (if (> (count s) n) (str (subs s 0 n) "…") s)))]
    (swap! st-memory assoc :memory-hint
           (when (seq hits)
             (fill hint {:items (str/join "\n" (for [{:keys [key content]} hits]
                                                 (fill item {:key key :line (clip (first (str/split-lines (str content)))
                                                                                  (:line-chars node 120))})))})))
    (when (seq hits)
      (events/emit! ctx :session.memory/hinted {:keys (mapv :key hits) :matched (mapv :matched hits)}))))

(def ^:private editor-context-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/editor-context.edn")))))

(defn- fill-once
  "`template` with each `{key}` replaced from `m` in one pass: what is put in
  is never read for placeholders again — a selection of code holding
  `{line}` stays as it was. A key `m` lacks is left as it is."
  [template m]
  (str/replace (str template) #"\{([a-z-]+)\}"
               (fn [[whole k]] (if (contains? m (keyword k)) (str (get m (keyword k))) whole))))

(defn mentions?
  "Whether `question` has `mention` as a word of its own — `@here`, but not
  `@here.clj` nor `x@here` — the punctuation around it aside: `(@here)`,
  `@here,`."
  [question mention]
  (boolean (some #(= mention (-> % (str/replace #"^[(\[{'\"]+" "") (str/replace #"[,.;:!?)\]}'\"]+$" "")))
                 (str/split (str question) #"\s+"))))

(defn editor-context-text
  "The words of prompts/editor-context.edn for `answer`, what
  `terminal/context!` said: the editor's file or buffer, the cursor, unsaved
  or not, and the selection — or, for an error or no editor (nil), that it
  could not be read and why."
  [answer]
  (let [t @editor-context-templates
        {:keys [program context error]} answer
        {:keys [file buffer line column modified region org]} context
        {:keys [heading block]} org]
    (if (or error (nil? answer))
      (fill-once (:none t) {:why (or error "no editor of this project that can say is running — emacs, started from the Terminal view, can")})
      (fill-once (:context t)
                 {:program program
                  :where (if file (fill-once (:file t) {:file file}) (fill-once (:buffer t) {:buffer buffer}))
                  :line (or line "?") :column (or column "?")
                  :modified (if (true? modified) (:modified t) "")
                  ;; in an org file, where in it (RFC-067)
                  :org (str (when (seq heading) (fill-once (:org-heading t) {:heading (str/join " / " heading)}))
                            (when (map? block)
                              (fill-once (:org-block t) {:language (or (:language block) "?")
                                                         :name (if (str/blank? (str (:name block))) "" (str " `" (:name block) "`"))
                                                         :line (or (:line block) "?")})))
                  :region (if (and (string? region) (seq region))
                            (fill-once (:region t) {:text region})
                            (:no-region t))}))))

(defn places-text
  "The words of prompts/editor-context.edn for `@visible` or `@trail`
  (`mention`, the key of `answer` it reads — RFC-067 phase 10): each window
  on the person's screen, or each file they visited most recently; that it
  could not be read, and why, for an error or no Emacs (nil)."
  [mention answer]
  (let [t @editor-context-templates
        k (if (= :visible mention) :visible :trail)
        word (str "@" (name k))
        rows (get answer k)]
    (if (or (nil? answer) (:error answer) (empty? rows))
      (fill-once (:places-none t) {:mention word :why (or (:error answer) (if answer "Emacs named none" "the Emacs this session reaches is not up"))})
      (fill-once (get t k)
                 {:rows (str/join "\n" (for [r rows]
                                         (if (= :visible k)
                                           (fill-once (:visible-row t) {:where (or (:file r) (:buffer r)) :start (:start r) :end (:end r)
                                                                        :line (:line r) :selected (if (true? (:selected r)) (:selected t) "")})
                                           (fill-once (:trail-row t) {:file (:file r) :line (:line r)
                                                                      :ago (if (number? (:minutes-ago r)) (fill-once (:ago t) {:minutes (:minutes-ago r)}) "")}))))}))))

(defn ^{:reads #{:input} :writes #{:editor-context}}
  editor-context
  "What the person is looking at in their editor, for the turn's user-context,
  when the question mentions it — the node's `:mention`, `@here` (byj-gly.453):
  read as the turn begins from the Emacs `:emacs-server` names when it is up
  (`emacs/context!`, RFC-067), else from a running editor of the project's
  (`terminal/context!`), so a question about \"this\" never depends on the
  model choosing to call emacs$context. Nil without the mention. When it
  cannot be read the turn is told so, and the person too."
  [{:keys [st-memory node] :as ctx}]
  (let [config (config-now ctx)
        q (:input @st-memory)
        mention (:mention node "@here")
        ;; @visible and @trail, Emacs's alone (RFC-067 phase 10)
        which (cond-> {} (mentions? q "@visible") (assoc :visible true) (mentions? q "@trail") (assoc :trail true))
        places (when (seq which)
                 (let [a (try (emacs/places! config which) (catch Throwable e {:error (or (ex-message e) (str e))}))]
                   (str/join "\n\n" (for [k [:visible :trail] :when (k which)] (places-text k a)))))]
    (swap! st-memory assoc :editor-context
           (some->> [(when (mentions? q mention)
             (let [answer (try (or (emacs/context! config)
                                       (terminal/context! (or (:project-dir config) (:working-dir config) ".")))
                               (catch Throwable e {:error (or (ex-message e) (str e))}))]
               (when (or (nil? answer) (:error answer))
                 (emit ctx :info (str mention ": " (or (:error answer) "no editor of this project is running that can say what it shows — start emacs from the Terminal view"))))
               (events/emit! ctx :session.editor/context
                             (if (:program answer)
                               {:program (:program answer) :file (get-in answer [:context :file])
                                :region-chars (count (str (get-in answer [:context :region])))}
                               {:error (or (:error answer) "none running")}))
               (editor-context-text answer)))
                     places]
                    (remove nil?) seq (str/join "\n\n")))))

(def ^:private coach-templates
  (delay (edn/read-string (slurp (io/resource "brainyard/prompts/coach.edn")))))

(defn ^{:reads #{} :writes #{:coach-context}}
  coach-context
  "While the session coaches the person (RFC-067 phase 3, byj-gly.461): the
  goal and how to coach toward it, `prompts/coach.edn` filled in from the
  session's `coach.edn` (`coach/stance`), for the turn's user-context. Read
  as each turn begins, so the stance holds across turns, compaction and a
  resume without being in the history; nil when coaching is off."
  [{:keys [st-memory] :as ctx}]
  (swap! st-memory assoc :coach-context
         (try (coach/context-text @coach-templates (coach/stance (config-now ctx)))
              (catch Throwable _ nil))))

(defn ^{:reads #{:input :command-input :console-activity :previous-turns} :writes #{:turn :turn-count :turn-started-at :config-pending-told :injected-by :console-activity :history-context}}
  begin-turn [{:keys [st-memory] :as ctx}]
  ;; what the config files say now, in force from this turn (RFC-013)
  (refresh-config! ctx)
  (await-mcp! ctx)
  (refresh-mcp! ctx)
  (let [n (inc (:turn-count @st-memory))]
    (swap! st-memory assoc :turn-count n :turn nil :turn-started-at (System/currentTimeMillis)
           ;; who injected this turn over the session channel, if anyone: the
           ;; trajectory records it (RFC-043)
           :injected-by (some-> (:channel ctx) channel/injected-by))
    ;; the question is not emitted — a front end echoes what was typed into it —
    ;; but a capture without the questions is half a conversation
    (record! ctx :you (or (:command-input @st-memory) (:input @st-memory)))
    (some-> (:channel ctx) channel/turn-started!)
    ;; what was done to the session's files from outside it since its last
    ;; turn — byj turns from another process, a /fork that put them back —
    ;; told this turn as the person's console activity (RFC-069, byj-gly.488)
    (when-let [id (some-> (:config ctx) :session-id str not-empty)]
      (tell-next-turn! ctx (persist/take-notes! (:config ctx) id)))
    ;; the work tree as the turn finds it, git's snapshot, for a review (RFC-067)
    (changes/begin-turn! (:config ctx) n (:input @st-memory))
    ;; …and says so on the bus, so a share's sidecar attributes the turn
    (events/emit! ctx :session.turn/start
                  (cond-> {:turn n :question (:input @st-memory)}
                    (:injected-by @st-memory) (assoc :injected-by (:injected-by @st-memory))
                    ;; the command it was typed as (byj-gly.350)
                    (:command-input @st-memory) (assoc :command (:command-input @st-memory))))))

(defn- snapshot!
  "Write the session after a turn, if persistence is on. A failure is reported
  once and never stops the session."
  [{:keys [st-memory config] :as ctx}]
  (when (:persist-session? config)
    (let [id (:session-id config)]
      (when (and id (nil? (persist/save! config id @st-memory)))
        (emit ctx :error (str "could not save session " id))))))

(def ^:private tool-entry-chars
  "How much of a tool call's result, and of each argument, the transcript keeps.
  Enough for the preview a front end draws under the call — a few lines, one
  row each — and not a whole file read: the snapshot keeps the transcript and
  is rewritten every turn, and every result is in the trajectory in full."
  400)

(defn- readable
  "`x` with every keyword that would not read back from EDN — a JSON key like
  `\"file path\"`, keywordized, prints as `:file path` — as its string. The
  transcript is written into the session snapshot, and one such key made the
  whole snapshot unreadable, so the session could not be resumed
  (byj-gly.221)."
  [x]
  (walk/postwalk (fn [v]
                   (if (and (keyword? v)
                            (not= v (try (edn/read-string (pr-str v)) (catch Throwable _ ::unreadable))))
                     (subs (str v) 1)
                     v))
                 x))

(defn- tool-entry
  "A tool call as the transcript keeps it: its name, its arguments and the start
  of its result, as plain data. The raw result a tool returned is not copied — it
  need not print — and a long argument (a file being written) is clipped."
  [{:keys [tool-name tool-args tool-result tool-error?]}]
  (cond-> {:tool-name (str tool-name)
           :tool-args (readable
                       (into {} (for [[k v] tool-args]
                                  [k (cond (string? v) (history/clip v tool-entry-chars)
                                           (<= (count (pr-str v)) tool-entry-chars) v
                                           :else (history/clip (pr-str v) tool-entry-chars))])))
           :tool-result (history/clip (str tool-result) tool-entry-chars)}
    tool-error? (assoc :tool-error? true)))

(defn- local-clock
  "The local wall-clock time, as `2026-09-24T18:23:05+0900`. jolt's java.time
  shim knows only UTC, so the OS gives the offset (provenance P-10), as for
  `agent/local-now` (`brainyard.clock`). nil when it cannot be asked: a turn
  is not worth failing over a clock."
  []
  (clock/local-now false))

(defn ^{:reads #{:turn :previous-turns :turn-started-at :turn-count} :writes #{:history-context :last-result :previous-turns :transcript :turn-elapsed-ms :usage :usage-calls}}
  end-turn [{:keys [st-memory config] :as ctx}]
  (let [{:keys [turn previous-turns turn-started-at]} @st-memory
        elapsed (when turn-started-at (- (System/currentTimeMillis) turn-started-at))
        result (agent/turn-result turn)]
    (swap! st-memory assoc :turn-elapsed-ms elapsed)
    (swap! st-memory
           #(-> % (set-turns (vec (take-last (:max-previous-turns config)
                                             ;; numbered, so a fork can be cut before one (RFC-069)
                                             (conj previous-turns (assoc (history/turn-summary turn) :turn (:turn-count %)))))
                             config)
                (update :usage (partial merge-with +) (:usage turn))
                ;; the turn's per-call records come out with it: the totals above
                ;; are a sum, and `/usage` tables what they are a sum OF
                (update :usage-calls
                        (fn [calls] (vec (take-last agent/max-usage-calls
                                                    (into (vec calls) (:usage-calls turn))))))
                (assoc :last-result result)))
    ;; how long, and when it ended by the person's clock: what a front end
    ;; says under the answer. The clock is the OS's, since jolt's is UTC
    (events/emit! ctx :session.turn/end (cond-> (assoc result :elapsed-ms elapsed :ended-at (local-clock))
                                          ;; how many files differ from its start, kept for a review (RFC-067)
                                          true (assoc :files-changed (or (changes/end-turn! config) 0))))
    ;; the subscription's windows, for the status line: refreshed in the
    ;; background, at most once in the snapshot's five minutes (byj-gly.324)
    (when (claude-usage-wanted? ctx)
      (fx/run-detached (fx/task-of #((or (:claude-usage-fn ctx) claude-usage/usage))) :claude-usage))
    ;; a Claude Code session whose worktree the turn removed has nothing to
    ;; work in: stopped now, not at the session's end (byj-gly.318)
    (doseq [s (try (claude-agent/stop-stranded! config) (catch Throwable _ []))]
      (events/emit! ctx :claude/stopped s))
    ;; the turn's tool calls, before its answer as they happened: what a
    ;; resume draws again between the question and the answer (RFC-016)
    ;; each behind its thought, as the renderer drew it live (byj-gly.280.4):
    ;; only an iteration that called something shows one
    (doseq [{:keys [tool-results thought]} (:trajectory-iterations turn)
            :when (seq tool-results)]
      (when-not (str/blank? (str thought))
        (record! ctx :thought (str/trim (str thought))))
      (doseq [r tool-results]
        (record! ctx :tool (tool-entry r))))
    ;; the answer is said, and so recorded, BEFORE the snapshot is written: a
    ;; session that dies before /quit would otherwise resume without its last
    ;; answer on the screen
    (emit ctx :answer (:answer result))
    (snapshot! ctx)
    ;; the answer goes back to whoever asked over the session channel, if the
    ;; turn came from there (RFC-031) — only AFTER the snapshot: an asker that
    ;; settles on the answer may end this process at once, as a closing or
    ;; crashing team leader does, and a member answered first was resumed
    ;; without its last turn (byj-4ps)
    (some-> (:channel ctx) (channel/turn-ended! result))))

(defn- this-turn
  "The turn that just ended as the recap reads it: the question, each tool it
  called with the start of what came back, and the answer. More than the
  history keeps of a turn, because this is the one the recap is about."
  [turn]
  (str "Q: " (history/clip (:question turn) 500)
       (apply str (for [{:keys [tool-results]} (:trajectory-iterations turn)
                        {:keys [tool-name tool-args tool-result tool-error?]} tool-results]
                    (str "\n  " tool-name " " (history/clip (pr-str tool-args) 200)
                         (if tool-error? " ✗ " " → ")
                         (history/clip (str/replace (str tool-result) #"\s+" " ") 300))))
       "\nA: " (history/clip (:answer turn) 1500)))

(defn ^{:reads #{:turn :turn-elapsed-ms :turn-count} :writes #{}}
  recap?
  "Condition `:session/recap?` — is the turn that just ended worth a recap?
  It is when it ran long (`:recap-after-ms`), or when it is every
  `:recap-every-nth-turns`th turn of the session, so a run of short turns is
  still summed up now and then. A single short turn is its own recap: the answer is
  still on the screen. A cancelled one is not recapped either — the person is
  there, they pressed the key."
  [{:keys [st-memory config]}]
  (let [{:keys [turn turn-elapsed-ms turn-count]} @st-memory
        every (:recap-every-nth-turns config 3)]
    (boolean (and (:recap? config)
                  (not= :cancelled (:terminated-by turn))
                  (or (and turn-elapsed-ms
                           (>= turn-elapsed-ms (:recap-after-ms config 60000)))
                      (and (pos-int? every) turn-count
                           (zero? (mod turn-count every))))))))

(defn conversation-title
  "`s` as a conversation title: lowercase words joined by hyphens, at most
  five, nothing else — whatever the model wrote. nil when nothing is left.
  The predictor is asked for exactly this shape; this is what makes it so,
  since a name that runs across a separator line has to be short to fit."
  [s]
  (let [words (->> (str/split (str/lower-case (str s)) #"[^\p{L}\p{N}]+")
                   (remove str/blank?)
                   (take 5))]
    (when (seq words) (str/join "-" words))))

(defn ^{:reads #{:history-context :turn :turn-count} :writes #{:recap :transcript :usage :usage-calls :conversation-title}}
  recap
  "Action `:session/recap` — run the node's predictor (session/recap in the
  bundled tree) on the session so far and the turn that just ended, and hand
  what it says to the front end as `:session.turn/recap`.

  Best effort, as the answer gate is: a recap that fails is no recap, never a
  failed turn. Its call is counted in the session's usage like any other,
  because it costs like any other."
  [{:keys [st-memory] :as ctx}]
  (let [{:keys [history-context turn turn-count]} @st-memory]
    ;; the predictor runner adds to the cache counters, which a session that has
    ;; not yet seen a provider report them does not have
    (swap! st-memory update :usage #(merge {:cache-read-tokens 0 :cache-write-tokens 0} %))
    (let [spent-before (get-in @st-memory [:usage :cost] 0.0)]
     (try
      (let [{:keys [outputs]} (agent/run-node-predictor
                               ctx {:history (or history-context "(no earlier turns)")
                                    :this-turn (this-turn turn)})
            recap (str/trim (str (:recap outputs)))
            next-step (str/trim (str (:next-step outputs)))
            ;; what the whole conversation is about, for a front end to name it by
            title (conversation-title (:conversation-title outputs))]
        (when title (swap! st-memory assoc :conversation-title title))
        (when-not (str/blank? (str recap next-step))
          (swap! st-memory assoc :recap {:recap recap :next-step next-step})
          (record! ctx :recap (str "recap: " recap (when (seq next-step) (str " " next-step))))
          ;; the turn's snapshot was written before this existed
          (snapshot! ctx)
          ;; what it cost, for a front end that totals a session's spend
          ;; from the turns' own usage and would otherwise never see this call
          (events/emit! ctx :session.turn/recap
                        {:turn turn-count :recap recap :next-step next-step
                         :conversation-title (:conversation-title @st-memory)
                         :cost (- (get-in @st-memory [:usage :cost] 0.0) spent-before)})))
      (catch Throwable e
        (when (fx/cancelled? e) (throw e))
        nil)))))

;; --- self-improvement (RFC-017) ----------------------------------------------------
;;
;; brainyard runs these from end-of-turn hooks; here they are nodes of the session
;; tree, after the recap, each optional by construction. What a model drafts is
;; staged as a proposal (brainyard.self-improve), never written live.

(defn- judge-window [config] (:skill-distill-judge-window config 12))

(def ^:private self-improve-owner
  "Whose a self-improvement judge's task is: no turn's, so no turn harvests it
  as a notice, and the model's task$list leaves it out."
  "self-improve")

(defn- off-turn-busy?
  "Is a judge of `kind` for `key` still running? brainyard's single-flight: one
  at a time per kind and key, and what would have been judged waits for the
  next chance rather than being dropped."
  [ctx kind key]
  (boolean (some (fn [[_ t]] (and (= :running (:status t)) (= [kind key] (::off-turn t))))
                 (some-> (:task-registry ctx) deref))))

(defn- off-turn!
  "Run `thunk` — a judge — as a background task and return at once: brainyard's
  `run-off-turn!` (common/background.clj). The next prompt does not wait for a
  model call nobody asked for; the person sees it in /task, and the session's
  end drains it (`drain-self-improve`). Without a task registry — a ctx built
  for one call — it runs here, as it did before (byj-gly.247)."
  [ctx kind key label thunk & [job-type]]
  (if-let [reg (:task-registry ctx)]
    (let [id (tasks/start! ctx {:name label :job-type (or job-type :self-improve) :owner self-improve-owner
                                :task (fx/task-of thunk)})]
      (swap! reg (fn [m] (if (contains? m id) (assoc-in m [id ::off-turn] [kind key]) m)))
      id)
    (thunk)))

(defn- judge!
  "Run the node's predictor as the recap does — its call counted in the
  session's usage — and return its outputs with what the call cost."
  [{:keys [st-memory] :as ctx} inputs]
  (swap! st-memory update :usage #(merge {:cache-read-tokens 0 :cache-write-tokens 0} %))
  (let [before (get-in @st-memory [:usage :cost] 0.0)
        {:keys [outputs]} (agent/run-node-predictor ctx inputs)]
    [outputs (- (get-in @st-memory [:usage :cost] 0.0) before)]))

(defn- stage!
  "Stage what a judge proposed when its verdict comes to `:staged`, say so to
  the person, and report every verdict as `:session.skill/judged` — the cost
  of a call that staged nothing is still a cost."
  [{:keys [config] :as ctx} outcome proposal report]
  (let [r (when (= :staged outcome) (self-improve/write-proposal! config proposal))
        outcome (if (:error r) :error outcome)]
    (when (and r (not (:error r)))
      (emit ctx :info (str "skill proposal staged: `" (:name proposal) "`"
                           (when (= :refinement (:kind proposal)) " (a revision of that skill)")
                           (when-let [s (:score proposal)] (format " — score %.2f" (double s)))
                           ". " (:rationale proposal)
                           " It is not live until you accept it: /proposals show skill/" (:name proposal)
                           ", /proposals accept skill/" (:name proposal) ", or /proposals reject skill/" (:name proposal) ".")))
    (events/emit! ctx :session.skill/judged
                  (cond-> (assoc report :outcome outcome :kind (:kind proposal))
                    (:name proposal) (assoc :name (:name proposal))
                    (:score proposal) (assoc :score (:score proposal))
                    (:error r) (assoc :error (:error r))))
    outcome))

(defn- distill!
  "Judge one turn or a window with the node's predictor, and stage the skill
  it drafts. Best effort, as the recap is: a judge that fails is no proposal,
  never a failed turn."
  [{:keys [config st-memory] :as ctx} inputs report]
  (try
    (let [[outputs cost] (judge! ctx (assoc inputs
                                            :existing-skills (self-improve/existing-skills-text config)
                                            :project-context ""))
          threshold (double (:skill-distill-threshold config 0.7))]
      (stage! ctx (self-improve/distill-outcome threshold outputs)
              {:name (:proposed-name outputs) :kind :distillation
               :score (:score outputs) :rationale (str (:rationale outputs))
               :skill-md (:skill-md outputs) :session (:session-id config)
               :turn (:turn report) :turns (:turns report)
               :source-question (:source-question report)}
              (assoc report :cost cost :turn-count (:turn-count @st-memory))))
    (catch Throwable e
      (when (fx/cancelled? e) (throw e))
      (events/emit! ctx :session.skill/judged (assoc report :kind :distillation :outcome :error
                                                     :error (or (ex-message e) (str e))))
      nil)))

(defn ^{:reads #{:turn :turn-count} :writes #{:skill-distill-tally :skill-distill-candidates}}
  note-skill-candidate
  "Action `:session/note-skill-candidate` — the window's bookkeeping, which
  costs nothing: count the turn, and keep it as a candidate when it is worth a
  distiller's time (`self-improve/worth-scoring?`). Nothing when distillation
  is off."
  [{:keys [st-memory config]}]
  (when (:enable-skill-distillation config)
    (let [{:keys [turn turn-count]} @st-memory]
      (swap! st-memory update :skill-distill-tally (fnil inc 0))
      (when (self-improve/worth-scoring? turn)
        (swap! st-memory update :skill-distill-candidates (fnil conj [])
               (self-improve/candidate turn-count turn)))))
  :success)

(defn ^{:reads #{:skill-distill-tally :skill-distill-candidates} :writes #{}}
  distill-window?
  "Condition `:session/distill-window?` — is the window of candidates due for
  a judge? Never when it is empty: an empty window is never sent to a model.
  Otherwise as soon as `self-improve/auto-signal` sees a procedure in it, with
  the thresholds in the node's `:auto` opts — a check made after every turn
  that costs nothing — or, whatever it holds, when the tally reaches a
  multiple of `:skill-distill-judge-window` (1 judges every candidate turn as
  it ends)."
  [{:keys [st-memory config node] :as ctx}]
  (let [{:keys [skill-distill-tally skill-distill-candidates]} @st-memory
        n (judge-window config)]
    (boolean (and (:enable-skill-distillation config)
                  (seq skill-distill-candidates)
                  ;; one at a time: the window keeps growing until it is done
                  (not (off-turn-busy? ctx :distill (:session-id config)))
                  (or (self-improve/auto-signal skill-distill-candidates (:auto node))
                      (and (pos-int? n) skill-distill-tally
                           (zero? (mod skill-distill-tally n))))))))

(defn ^{:reads #{:skill-distill-candidates} :writes #{}}
  distill-leftover?
  "Condition `:session/distill-leftover?` — the session is ending with
  candidates no window has judged yet: brainyard's session-end flush."
  [{:keys [st-memory config]}]
  (boolean (and (:enable-skill-distillation config)
                (seq (:skill-distill-candidates @st-memory)))))

(defn ^{:reads #{:skill-distill-candidates} :writes #{:skill-distill-candidates :usage :usage-calls :transcript}}
  distill-window
  "Action `:session/distill-window` — judge the window of candidates with the
  node's predictor (session/distill-skill in the bundled tree), and report
  what brought it on: the node's own `:trigger` (`:session-end` for the
  flush), else the procedure `auto-signal` saw, else the cadence. The window is taken out of
  memory BEFORE the call, as brainyard does, so a failed judge does not send
  the same turns again."
  [{:keys [st-memory config node] :as ctx}]
  (let [window (vec (:skill-distill-candidates @st-memory))
        trigger (or (:trigger node)
                    (self-improve/auto-signal window (:auto node))
                    :cadence)]
    (swap! st-memory assoc :skill-distill-candidates [])
    (when (seq window)
      (let [judge #(distill! ctx {:turn-window (self-improve/window-text window)
                                  :turn-count (str (count window))}
                             {:trigger trigger :turns (mapv :turn-number window)
                              :source-question (:question (last window))})]
        ;; off the turn, unless the node says otherwise — the session-end
        ;; flush, which nothing would wait for
        (if (:inline node)
          (judge)
          (off-turn! ctx :distill (:session-id config) (str "self-improve: distill a skill from turn(s) "
                                                             (str/join "," (map :turn-number window)))
                     judge)))))
  :success)

(defn ^{:reads #{:turn :turn-count} :writes #{:tool-distill-candidates}}
  note-tool-candidate
  "Action `:session/note-tool-candidate` — keep the turn's `tool$run` bodies
  that worked, in a turn that answered, as candidates for a tool
  (`tool-distill/candidates`). Costs nothing; nothing when tool distillation
  is off."
  [{:keys [st-memory config]}]
  (when (:enable-tool-distillation config)
    (let [{:keys [turn turn-count]} @st-memory]
      (when-let [cs (seq (tool-distill/candidates turn-count turn))]
        (swap! st-memory update :tool-distill-candidates (fnil into []) cs))))
  :success)

(defn ^{:reads #{:tool-distill-candidates} :writes #{}}
  tool-distill?
  "Condition `:session/tool-distill?` — do the candidates show a body worth a
  judge (`tool-distill/auto-signal`, with the node's `:auto` thresholds)? A
  check after every turn that costs nothing; there is no cadence and no
  session-end flush, since a body that neither repeated nor composed tools is
  not yet a tool."
  [{:keys [st-memory config node tools] :as ctx}]
  (boolean (and (:enable-tool-distillation config)
                tools
                (seq (:tool-distill-candidates @st-memory))
                (not (off-turn-busy? ctx :tool-distill (:session-id config)))
                (tool-distill/auto-signal tools (:tool-distill-candidates @st-memory) (:auto node)))))

(defn- stage-tool!
  "Stage a proved draft, say so to the person, and report every verdict as
  `:session.tool/judged` — with what the proof found, whatever it found."
  [{:keys [config] :as ctx} outcome {:keys [record] :as proposal} report]
  (let [r (when (= :staged outcome) (tool-distill/write-proposal! config proposal))
        outcome (if (:error r) :error outcome)
        nm (:name record)]
    (when (and r (not (:error r)))
      (emit ctx :info (str "tool proposal staged: `" nm "`"
                           (format " — score %.2f" (double (:score proposal)))
                           (if (= :verified (get-in proposal [:proof :status]))
                             (str ", replayed " (count (get-in proposal [:proof :runs])) " run(s) exactly")
                             ", NOT replayed: it could do more than read — review the body")
                           ". " (:rationale proposal)
                           " It is not live until you accept it: /proposals show tool/" nm
                           ", /proposals accept tool/" nm ", or /proposals reject tool/" nm ".")))
    (events/emit! ctx :session.tool/judged
                  (cond-> (assoc report :outcome outcome)
                    nm (assoc :name nm)
                    (:score proposal) (assoc :score (:score proposal))
                    (get-in proposal [:proof :status]) (assoc :proof (get-in proposal [:proof :status]))
                    (:error r) (assoc :error (:error r))))
    outcome))

(defn ^{:reads #{:tool-distill-candidates :turn-count} :writes #{:tool-distill-candidates :usage :usage-calls :transcript}}
  tool-distill
  "Action `:session/tool-distill` — ask the node's predictor
  (session/distill-tool) whether the candidate runs hold a user tool, PROVE the
  draft (`tool-distill/prove`: compiled, and when it only reads, replayed
  against each run it generalised), and stage it when proved. The candidates
  are taken out of memory before the call, so a failed judge does not send
  them again. Best effort, as every judge is: a failure is no proposal, never
  a failed turn."
  [{:keys [st-memory config node tools] :as ctx}]
  (let [cands (vec (:tool-distill-candidates @st-memory))
        trigger (tool-distill/auto-signal tools cands (:auto node))
        report {:trigger trigger :turns (vec (distinct (map :turn-number cands))) :runs (count cands)}]
    (swap! st-memory assoc :tool-distill-candidates [])
    (when (seq cands)
      (off-turn! ctx :tool-distill (:session-id config)
                 (str "self-improve: distill a tool from turn(s) " (str/join "," (:turns report)))
       (fn []
      (try
        (let [[outputs cost] (judge! ctx {:runs (tool-distill/candidates-text cands)
                                          :run-count (str (count cands))
                                          :existing-tools (tool-distill/existing-tools-text tools)
                                          :tool-lisp (toolbox/language)})
              report (assoc report :cost cost)
              verdict (tool-distill/outcome (double (:tool-distill-threshold config 0.7)) outputs)]
          (if (not= :draft-ok verdict)
            (stage-tool! ctx verdict {:score (:score outputs)} report)
            (let [{:keys [record replay-args]} (tool-distill/draft outputs)
                  proof (tool-distill/prove tools ctx record replay-args cands)]
              (stage-tool! ctx (if (tool-distill/stage? proof) :staged (:status proof))
                           {:record record :score (:score outputs) :rationale (str (:rationale outputs))
                            :proof proof :session (:session-id config)
                            :sources (mapv #(select-keys % [:turn-number :question :body :args]) cands)}
                           report))))
        (catch Throwable e
          (when (fx/cancelled? e) (throw e))
          (events/emit! ctx :session.tool/judged (assoc report :outcome :error :error (or (ex-message e) (str e))))
          nil))))))
  :success)

(defn ^{:reads #{:turn} :writes #{}}
  refine?
  "Condition `:session/refine?` — refinement is on and the turn that just
  ended gives a skill reason to be judged (`self-improve/refine-triggers`)."
  [{:keys [st-memory config]}]
  (boolean (and (:enable-skill-refinement config)
                (seq (self-improve/refine-triggers config (:turn @st-memory))))))

(defn ^{:reads #{:turn :turn-count} :writes #{:usage :usage-calls :transcript}}
  refine
  "Action `:session/refine` — for each skill the turn gives reason to judge,
  ask the node's predictor (session/refine-skill in the bundled tree) whether
  its SKILL.md was at fault, and stage the revision when it was. One judge per
  skill; each is best effort."
  [{:keys [st-memory config] :as ctx}]
  (let [{:keys [turn turn-count]} @st-memory]
    (doseq [{:keys [skill args why path] :as trigger} (self-improve/refine-triggers config turn)
            :when (not (off-turn-busy? ctx :refine skill))]
      (off-turn! ctx :refine skill (str "self-improve: judge skill " skill " after turn " turn-count)
       (fn []
      (try
        (let [[outputs cost] (judge! ctx {:skill-name skill
                                          :current-skill-md (slurp path)
                                          :invocation-args (pr-str args)
                                          :failure-evidence (self-improve/failure-evidence trigger turn)})]
          (stage! ctx (self-improve/refine-outcome outputs)
                  {:name skill :kind :refinement :rationale (str (:rationale outputs))
                   :skill-md (:revised-md outputs) :session (:session-id config)
                   :turn turn-count :source-question (str (:question turn))
                   :evidence (self-improve/failure-evidence trigger turn 1500)}
                  {:trigger why :turn turn-count :cost cost}))
        (catch Throwable e
          (when (fx/cancelled? e) (throw e))
          (events/emit! ctx :session.skill/judged {:kind :refinement :name skill :trigger why
                                                   :outcome :error :error (or (ex-message e) (str e))})))))))
  :success)

(defn ^{:reads #{:turn} :writes #{:turn}}
  turn-failed
  "The nested loop failed rather than answering. The scope still copied its
  memory out, so give the turn an answer that says so and let end-turn record it."
  [{:keys [st-memory]}]
  (swap! st-memory update :turn
         #(let [err (:bt/error %)]
            (cond-> (assoc % :terminated-by (or (:terminated-by %) :loop-error))
              (str/blank? (:answer %))
              (assoc :answer (str "*(The agent loop failed"
                                  (when err (str ": " (:message err))) ".)*"))))))

(defn- closing-note!
  "Say something as the session closes, and keep it in the final memory as
  `:closing-notices`: a full-screen front end has given the terminal back by
  the time anyone could read it, and prints them there (byj-gly.252)."
  [{:keys [st-memory] :as ctx} text]
  (emit ctx :info text)
  (swap! st-memory update :closing-notices (fnil conj []) text))

(defn ^{:reads #{} :writes #{:closing-notices}}
  drain-self-improve
  "Action `:session/drain-self-improve` — the session is ending: give the
  judges still running `:self-improve-drain-ms` (brainyard's 3 s) to finish
  before `close` cancels what is left. Their model call is already paid for.
  Says what it cut short, if anything."
  [{:keys [config task-registry] :as ctx}]
  (when task-registry
    (let [running #(for [[_ t] @task-registry
                          :when (and (= :running (:status t)) (::off-turn t))]
                      t)
          deadline (+ (System/currentTimeMillis) (long (:self-improve-drain-ms config 3000)))]
      (when (seq (running))
        (loop []
          (when (and (seq (running)) (< (System/currentTimeMillis) deadline))
            (Thread/sleep 50)
            (recur)))
        (let [judges (count (running))]
          (when (pos? judges)
            (closing-note! ctx (str judges " self-improvement judge(s) did not finish in time and are cancelled")))))))
  :success)

(defn ^{:reads #{:ended} :writes #{:closing-notices :session-id :persisted?}}
  close [{:keys [config st-memory] :as ctx}]
  ;; the turn loop ran out rather than being ended: say so, or a session that
  ;; reached :max-turns just stops, with nothing to say why
  (when-not (:ended (mem ctx))
    (closing-note! ctx (str "the session reached its limit of " (:max-turns config) " turns (:max-turns)"
                            " — --resume carries on from here")))
  ;; what a parting "resume with" line needs: which session, and whether it
  ;; was written where --resume looks (byj-gly.252)
  (swap! st-memory assoc :session-id (:session-id config) :persisted? (boolean (:persist-session? config)))
  ;; nothing this session started outlives it: jolt has no shutdown hook that
  ;; would kill an orphaned subprocess for us (RFC-019)
  (when-let [reg (:task-registry ctx)]
    (let [n (tasks/cancel-all! reg)]
      (when (pos? n) (closing-note! ctx (str "cancelled " n " background task(s)")))))
  ;; …nor what its bash calls left running in the background (byj-gly.315.16)
  (let [n (tools/stop-background! (:session-id config))]
    (when (pos? n) (closing-note! ctx (str "stopped " n " process group(s) its commands left running"))))
  ;; …nor a Claude Code session it delegated to that is still working, or
  ;; whose worktree is gone: saved, to be resumed (byj-gly.318)
  (let [s (try (claude-agent/stop-stranded! config {:closing-session (:session-id config)})
               (catch Throwable _ []))]
    (let [[procs sessions] ((juxt filter remove) :processes s)]
      (when (seq sessions)
        (closing-note! ctx (str "stopped " (count sessions) " Claude Code session(s) it delegated to, saved: "
                                (str/join "; " (map #(str (:session-id %) " — " (:why %)) sessions)))))
      ;; …and what one left running in a worktree since removed (byj-gly.321)
      (when (seq procs)
        (closing-note! ctx (str "stopped what delegated Claude Code sessions left running: "
                                (str/join "; " (map :why procs)))))))
  (snapshot! ctx)
  (when (:persist-session? config)
    (persist/unlock! config (:session-id config)))
  (events/emit! ctx :session/closed (select-keys (mem ctx) [:turn-count :usage])))

(defn ^{:reads #{:input :previous-turns :turn-count :usage :console-activity} :writes #{:ended :history-context :previous-turns :console-activity}}
  run-command
  "A slash command. One that throws says so, and the input stops there: in the
  session tree a failed command node falls through to an agent turn, which put
  `/proposals accept …` to the model as a question — it answered that it could
  not accept anything (provenance P-23)."
  [ctx]
  (try (run-command* ctx)
       (catch Throwable e
         (if (fx/cancelled? e)
           (throw e)
           (do (emit ctx :error (str (first (str/split (str (:input (mem ctx))) #"\s+")) " failed: "
                                     (or (ex-message e) (str e))))
               nil)))))

(def registry
  {:session/init #'init
   :session/read-input #'read-input
   :session/eof? #'eof?
   :session/blank? #'blank?
   :session/command? #'command?
   :session/direct-call? #'direct-call?
   :session/channel-command? #'channel-command?
   :session/refuse-channel-command #'refuse-channel-command
   :session/direct-call #'direct-call
   :session/run-command #'run-command
   :session/end #'end
   :session/memory-hint #'memory-hint
   :session/editor-context #'editor-context
   :session/coach-context #'coach-context
   :session/ended? #'ended?
   :session/begin-turn #'begin-turn
   :session/loop-request #'loop-request
   :session/command-request #'command-request
   :session/tool-request #'tool-request
   :session/end-turn #'end-turn
   :session/recap? #'recap?
   :session/recap #'recap
   :session/note-skill-candidate #'note-skill-candidate
   :session/distill-window? #'distill-window?
   :session/distill-leftover? #'distill-leftover?
   :session/distill-window #'distill-window
   :session/note-tool-candidate #'note-tool-candidate
   :session/tool-distill? #'tool-distill?
   :session/tool-distill #'tool-distill
   :session/drain-self-improve #'drain-self-improve
   :session/refine? #'refine?
   :session/refine #'refine
   :session/turn-failed #'turn-failed
   :session/close #'close})

;; --- entry point -----------------------------------------------------------------

(defn run
  "Run a session until /quit or end of input.

  opts: everything `brainyard.agent/ask` takes (:lm :config :tools :registry
  :context :on-event), plus
    :tree        the session tree (default loops/session.edn, includes expanded)
    :read-input  (fn [prompt]) → line or nil
    :emit        (fn [kind text])
    :cancellers  an atom the caller holds; while a turn runs it maps :turn to
                 a fn that cancels that turn alone (`cancel-turn!`)
    :channel     a `brainyard.channel` the front end opened, or nil (RFC-031)
    :approve     (fn [{:keys [text from who timeout-ms]}]) → :approved | :rejected
                 | :expired | nil — the person's answer to an injected turn
                 (RFC-043); nil where nobody can be asked
    :approve-answer (fn [yes?]) → what to say — `/share allow|refuse`, where
                 the person answers `:approve` at the prompt (inline)
    :share       a share target (`link`, `class:CODE`, an ended share's `shr_…` id): start the sidecar

  Returns the session's final memory: :previous-turns :turn-count :usage …"
  [{:keys [tree read-input emit config registry cancellers] :as opts}]
  (let [st-memory (atom {})
        ;; what the share sidecar last said of who is watching (RFC-043):
        ;; what /share reports
        share-box (atom nil)
        ;; (`:session.share/presence` is the sidecar's, sent over the channel's
        ;; `:emit` — never this kernel's, so not in the event catalogue)
        opts (let [outer opts]
               (assoc opts :on-event (fn [e d]
                                       (case e
                                         ;; where the share is, said once the relay
                                         ;; answers: --share link's URL is what the
                                         ;; person hands out (byj-1wv.7)
                                         :session.share/presence (let [before @share-box]
                                                                   (reset! share-box d)
                                                                   (when-let [line (share/url-line before d)]
                                                                     (when emit (emit :info line))))
                                         :session.share/control (when (= :stop (some-> (:action d) keyword)) (reset! share-box nil))
                                         ;; a sidecar that ended by itself: a quiet
                                         ;; task's ending reaches nobody else, and a
                                         ;; share that never opened must say why
                                         ;; (byj-gly.362.4)
                                         :session.share/login-ended (when emit
                                                                      (emit :info (str (if (:ok d) "byj-share: " "byj-share login ended: ") (:why d))))
                                         :session.share/ended (do (reset! share-box nil)
                                                                  (when emit (emit :info (str "sharing ended: " (:why d)))))
                                         nil)
                                       (events/emit! outer e d))))
        ;; every event the session emits goes to its channel's subscribers too
        ;; (RFC-031 :subscribe), whoever else is watching
        opts (if-let [ch (:channel opts)]
               (let [outer opts]
                 ;; forwarded through the one door, to the handler the caller
                 ;; gave (events-test/nothing-reaches-the-bus-except-through-here)
                 (assoc opts :on-event (fn [e d] (channel/publish! ch e d) (events/emit! outer e d))))
               opts)
        ctx (-> (agent/make-ctx (assoc opts
                                       ;; one ctx for the whole session, so a
                                       ;; subagent instance outlives the turn
                                       ;; that dispatched it (RFC-018)
                                       :subagent-scope :session
                                       ;; a turn can park itself on a task and be
                                       ;; resumed when it lands (RFC-019)
                                       :wakeups (or (:wakeups opts) (tasks/wakeups))
                                       ;; what /allow-path adds, for this session
                                       ;; only (byj-gly.145)
                                       :allowed-dirs-box (or (:allowed-dirs-box opts) (atom []))
                                       :config (merge default-config config)
                                       :registry (merge brainyard.session/registry registry)))
                (assoc :st-memory st-memory :read-input read-input :emit emit
                       ;; what a turn's start re-reads the config through, and
                       ;; where what it finds is laid over the ctx (RFC-013)
                       :reload-config (:reload-config opts)
                       ;; the load the run started from, what the next is compared to
                       :ctx-box (atom (cond-> {} (:config-loaded opts) (assoc :config-loaded (:config-loaded opts))))
                       :cancellers (or cancellers (atom {}))
                       ;; the tool calls a turn is waiting on, each with a
                       ;; release that ends the wait now: what a front end's
                       ;; ctrl+g calls (byj-gly.391, tasks/detachable)
                       :backgrounders (or (:backgrounders opts) (atom {}))
                       ;; the store's listing, kept between turns for the
                       ;; memory hint (byj-gly.335)
                       :memory-cache (atom nil)
                       ;; the session channel, when a front end opened one:
                       ;; told when a turn starts and ends (RFC-031)
                       :channel (:channel opts)
                       :share-box share-box
                       :approve-answer (:approve-answer opts)
                       ;; the model this session is asking, once `/model` has
                       ;; chosen one: an atom rather than a rebuilt ctx, because
                       ;; the tree is already running when the choice is made
                       ;; (`agent/current-lm` reads it)
                       :lm-box (or (:lm-box opts) (atom nil))
                       ;; -p/-m on the command line: a resume keeps it (byj-gly.232)
                       :lm-flagged? (boolean (:lm-flagged? opts))
                       ;; how much a viewer shows, which :output-style sets
                       :display-box (:display-box opts)
                       ;; a front end's own view of what a command reports —
                       ;; `(present kind data)`, true when it showed it (the
                       ;; config editor, the model picker); nil prints text
                       :present (:present opts)
                       ;; the terminal, handed to a command a person runs —
                       ;; /login's CLI (RFC-027); nil headless
                       :hand-terminal (:hand-terminal opts)
                       ;; the Claude Code subscription's snapshot (byj-gly.324):
                       ;; `claude-usage/usage` unless a test gives its own
                       :claude-usage-fn (:claude-usage-fn opts)))]
    ;; what the channel's other requests need of a session (RFC-031): its
    ;; config, its event bus, its MCP servers, its memory store, its title
    (channel/attach! (:channel opts)
                     {:config-rows #(let [now (merge ctx @(:ctx-box ctx))
                                          cfg (:config now)]
                                      (secrets/redact (config/describe {:config cfg :sources (:config-sources cfg)})))
                      :emit! (fn [e d] (events/emit! ctx e d))
                      :mcp-rows #(when (:mcp-box ctx) (mcp-rows (merge ctx @(:ctx-box ctx))))
                      :remember! (fn [slug content]
                                   (if-let [t (get @(:tools ctx) "memory$remember")]
                                     ((:fn t) (cond-> {:content content} (not (str/blank? (str slug))) (assoc :key slug)) ctx)
                                     {:error "this session keeps no memory store"}))
                      :rename! (fn [label] (swap! st-memory assoc :conversation-title label))
                      ;; a note for people (RFC-043), and — only where a person
                      ;; is there to ask — an injected turn's approval
                      :note! (fn [text from] (note! ctx text from))
                      :approve! (:approve opts)})
    ;; --share (RFC-043): the sidecar, a background task of this session, so
    ;; it ends with it. A sidecar that cannot start is said, and the session
    ;; runs unshared
    (when-let [target (:share opts)]
      (let [r (share/start! ctx {:target target :socket (some-> (:channel opts) :path)
                                 :title (or (:label config) (:conversation-title @st-memory))})]
        (if (:error r)
          (when emit (emit :error (:error r)))
          (when emit (emit :info (str "sharing this session (" target ") through " (share/relay-said (:relay r))
                                      " — its URL follows when the relay answers"))))))
    ;; however the tree ends — /quit, end of input, max turns, a throw — the
    ;; session has closed (byj-gly.334)
    (try (agent/run-tree! ctx (or tree (agent/load-tree session-loop (:project-dir (:config ctx)))))
         (finally (fire-session! ctx :agent.session/closed)))
    @st-memory))

(defn tree-with-turn-loop
  "The session loop, each turn running `turn-tree` — a tree resource or file —
  where it would run the bundled ReAct loop: what an agent spec's own `:loop`
  (RFC-022) is to a session started as that agent (RFC-031), as it is to a
  subagent of it. Both are found for `project-dir`, the project's own loop
  first (RFC-071)."
  [turn-tree & [project-dir]]
  (agent/expand-tree (walk/postwalk (fn [x]
                                      (if (and (vector? x) (= :include (first x))
                                               (= agent/react-loop (:tree (second x))))
                                        (assoc-in x [1 :tree] turn-tree)
                                        x))
                                    (agent/resolve-tree session-loop project-dir))
                     project-dir))

(defn with-persona
  "The base — `session/run`'s argument map, `:config`, `:lm` and `:tree` among
  it — as agent spec `name` makes it (RFC-018), or nil when there is no such
  agent: its persona in the config, its own `:lm` — by the rule a subagent of
  it gets it (`agents/child-lm`) — unless `:lm-flagged?` says `-p`/`-m` named
  one, and its own `:loop` as each turn's tree unless `loop?` says `--loop`
  named one (byj-gly.256). A headless session's (RFC-031) first, now any
  session's: `byj run`, `byj ask` and the SDK's (byj-g5n.2, RFC-053)."
  [base name & [{:keys [loop]}]]
  (let [{:keys [agents]} (agents/load-agents (:config base))]
    (when-let [a (first (filter #(= name (:name %)) agents))]
      (cond-> (update base :config #(agents/persona-config a %))
        (and (:lm a) (not (:lm-flagged? base))) (assoc :lm (agents/child-lm a base nil))
        (and (agents/loop-path a) (not loop)) (assoc :tree (tree-with-turn-loop (agents/loop-path a) (get-in base [:config :project-dir])))))))

(defn cancel-turn!
  "Cancel the running turn, if any, through the cancellers atom given to
  `run`. Returns true when a turn was running."
  [cancellers]
  (if-let [cancel (get @cancellers :turn)]
    (do (cancel) true)
    false))
