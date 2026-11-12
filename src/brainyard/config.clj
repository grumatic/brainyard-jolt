;; Copyright (c) 2026 Grumatic, Inc. and byj contributors
;; SPDX-License-Identifier: MIT

(ns brainyard.config
  "Layered configuration (RFC-013) — brainyard's `agent/core/config.clj` in the
  small: a declared schema of the keys this port has, and one resolver over the
  layers, lowest first:

      schema default  <  ~/.brainyard/config.edn  <  <project-dir>/.brainyard/config.edn
      <  caller (CLI flags, or opts passed to `agent/ask`)  <  BY_* environment

  The project dir is brainyard's: BY_PROJECT_DIR, else the git root above the
  working dir, else the working dir.

  brainyard reads ONE file — the project's if it exists, else the user's; here
  the user's is the floor and the project's is layered over it (divergence
  `config-files-merge`).

  The file is brainyard's shape: keys live under `[:agent :config]`, with
  `[:permissions :mode]` and `[:permissions :allowed-dirs]` bridged onto flat
  keys. Unknown keys, schema keys at the top level and an unreadable file are
  reported as problems and skipped, never thrown (divergence
  `config-file-never-throws`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [brainyard.schema :as schema]))

;; ============================================================================
;; The schema
;; ============================================================================

(defn- env-name
  "The BY_* variable a key reads, derived from its name: brainyard writes one
  `:env-fn` per key by hand, but its convention is exactly this."
  [k]
  (str "BY_" (-> (name k) (str/replace #"\?$" "") (str/replace #"[-/]" "_") str/upper-case)))

(def config-items
  "key → {:type :default|:default-fn :doc :family}. `:type` drives coercion of
  strings from the environment and validation of values read from a file."
  {:max-iterations {:family :loop :type "integer" :default 20 :doc "Iteration ceiling for one turn."}
   ;; tier 0 (RFC-014): 64000 chars is ~16k tokens for ONE result — a sixth of a
   ;; small window; measured, a whole source file is ~28000 chars
   :max-output-chars {:family :context :type "integer" :default 16000 :doc "Cap on a tool result in the prompt."}
   :max-thought-chars {:family :context :type "integer" :default 2000 :doc "Cap on a recorded reasoning field."}
   :max-history-iterations {:family :context :type "integer" :default 10 :doc "Iteration records kept in the prompt."}
   ;; context budget and compaction (RFC-014)
   :max-context-tokens {:family :context :type "integer" :default 200000 :doc "The model's context window, for the prompt budget."}
   :max-output-tokens {:family :context :type "integer" :default 8192 :doc "Room reserved for the reply when budgeting."}
   :context-budget-safety-ratio {:family :context :type "number" :default 0.10 :doc "Margin held back, since tokens are estimated as chars/4."}
   :enable-context-budget {:type "boolean" :default true :family :context
                           :doc "Measure the prompt and compact the history when it exceeds the budget."}
   :rebudget-every-n-iter {:family :context :type "integer" :default 4 :doc "Measure again every nth iteration."}
   :keep-verbatim-iterations {:family :context :type "integer" :default 3 :doc "Most recent iteration records never elided or dropped."}
   :stream? {:type "boolean" :default false :family :provider
             :doc "Read the reply as it is generated, emitting :agent.llm/chunk. Only the claude-code provider can: jolt's http client buffers a whole response before handing it over (RFC-006)."}
   :default-lm {:type "string" :default "claude-code/sonnet" :family :provider
                :doc "The model a run asks unless -p/-m say otherwise, as provider/model (e.g. claude-code/haiku, anthropic/claude-sonnet-5, openai/gpt-4.1-mini). Sonnet through the Claude Code CLI unless set (byj-gly.366; haiku before). An API key in the environment does not choose it."}
   :prompt-cache? {:type "boolean" :default true :family :provider
                   :doc "Mark the stable prompt zones for provider caching (anthropic; RFC-015)."}
   :cache-ttl {:family :provider :type "string" :default "5m" :doc "Cache lifetime for the marked zones: 5m, or 1h (a 2x write premium)."}
   :prompt-cache-key {:family :provider :type "string" :default nil :doc "Routing key for OpenAI's automatic prefix cache."}
   :persist-session? {:type "boolean" :default true :family :session
                      :doc "Save the session after every turn under .brainyard/sessions (RFC-016)."}
   :prune-sessions? {:type "boolean" :default true :family :session
                     :doc "At startup, remove session and transcript directories past the keep/TTL limits (RFC-016)."}
   ;; the session channel (RFC-031): brainyard's keys and defaults
   :ask-channel-enabled? {:type "boolean" :default true :family :session
                          :doc "Listen at <session-dir>/ask.sock (0600) so another session or a script can ask this one something; a request becomes a turn when the session is waiting for input."}
   :session-tools? {:type "boolean" :default true :family :session
                    :doc "Offer session$list, session$status, session$ask, session$send and session$cancel: this project's other live sessions, asked over their session channel (RFC-031)."}
   :team-tools? {:type "boolean" :default true :family :session
                 :doc "Offer team$create, team$spawn, team$assign, team$status and team$close: a team of live member sessions this one leads (RFC-032). A member is started with it off."}
   :max-team-members {:type "integer" :default 4 :family :session
                      :doc "The most members a team runs at once. Each costs model turns of its own."}
   :team-member-command {:type "string" :default nil :family :session
                         :doc "The command that starts a member session (--serve and its flags are appended). Default: `jolt run:byj` from a checkout, `byj run` from a binary."}
   :team-spawn-timeout-ms {:type "integer" :default 60000 :family :session
                           :doc "How long team$spawn waits for a member to come up."}
   :team-permission-timeout-ms {:type "integer" :default 300000 :family :session
                                :doc "How long a member waits for its leader's person to answer a forwarded permission prompt before it counts as a refusal."}
   :team-close-grace-ms {:type "integer" :default 10000 :family :session
                         :doc "How long team$close waits for a member to close itself before stopping its process."}
   ;; the share sidecar (RFC-043): the port's
   :share-sidecar {:type "string" :default "byj-share" :family :session
                   :doc "The share sidecar a session started with --share runs: a command found on PATH, or a path to one (RFC-043)."}
   :share-relay-url {:type "string" :default nil :family :session
                     :doc "The relay the share sidecar connects out to, handed to it as its relay (RFC-043); unset, the one byj-share is logged in to, else the sidecar's own default."}
   :share-inject-approval-ms {:type "integer" :default 120000 :family :session
                              :doc "How long the person has to allow a turn a coach injected into a shared session; no answer is a refusal (RFC-043)."}
   :share-record? {:type "boolean" :default false :family :session
                   :doc "A session started with --share link is recorded: what it shares is kept and uploaded for review when the share ends, and everyone watching is told. Never a class share (RFC-043)."}
   :ask-timeout-ms {:type "integer" :default 120000 :family :session
                    :doc "The longest a request over the session channel waits for its turn to end; a request that times out still runs."}
   :command-rephrase? {:type "boolean" :default true :family :session
                       :doc "A slash command a file defines (/init, /share WORDS, .brainyard/commands/) becomes a turn whose request is rephrased for this session's agent in the light of the turns so far (the session/rephrase-command predictor); off, its fixed template is used. With no turns yet the template is used either way."}
   :terminal-tools? {:type "boolean" :default true :family :session
                     :doc "The session's agent works the Terminal view's programs: terminal$list, terminal$start, terminal$stop, terminal$send and terminal$capture (byj-gly.417), and terminal$context, what the person is looking at in a running editor (byj-gly.453). Offered where tmux is; this project's programs alone. terminal$send types into one, so :tool-ask-effects asks before it by default. Never a subagent's."}
   :emacs-tools? {:type "boolean" :default true :family :session
                  :doc "The session's agent works with Emacs (RFC-067, byj-gly.459): emacs$status where emacsclient is, and while the Emacs :emacs-server names is up emacs$context, emacs$read (a buffer as Emacs has it, unsaved edits included), emacs$show, emacs$eval, emacs$note (notes on lines, listed for next-error), emacs$edit (a change in a buffer, one undo step, unsaved) and the org tools emacs$org-outline, emacs$org-tangle and emacs$org-execute. emacs$eval and emacs$org-execute run code as the person, so :tool-ask-effects asks before them by default (:editor/eval, :editor/execute). Never a subagent's."}
   :emacs-server {:type "string" :default "project" :family :session
                  :doc "Which Emacs byj reaches — the tools, @here, a clicked file, the revert after the agent's tools (RFC-067): \"project\", the project's own daemon that the Terminal view's emacs starts (byj-<tag>); or a server name or socket path, as emacsclient -s takes — \"server\" is your own Emacs's default server (M-x server-start). Yours is never reached unless named here."}
   :coach? {:type "boolean" :default true :family :session
            :doc "The session's agent can coach the person toward a goal (/emacs coach, RFC-067): coach$set keeps the goal in the session's directory, and while it is set every turn is told it and how to coach — they write the code, the agent hints by degrees and checks. Never a subagent's."}
   :emacs-execute-timeout-ms {:type "integer" :default 60000 :family :session
                              :doc "The longest emacs$org-execute waits for a block to run in the person's Emacs (RFC-067). Emacs waits while it runs, and stopping at the bound does not stop it there."}
   :literate-guard? {:type "boolean" :default true :family :tools
                     :doc "Refuse write-file and update-file on a file org-babel tangled with link comments (:comments link), naming the org file to edit instead: in a literate project the org file is the source (RFC-067, the tangled-file-guard hook). Off, such a file is written like any other, and the next tangle overwrites it."}
   :changes? {:type "boolean" :default true :family :session
              :doc "Keep each turn's change as git keeps things: the project's work tree snapshotted as the turn begins and ends (commit objects, through an index of byj's own: HEAD, the index, the stash and the work tree untouched), every turn kept as refs/byj/turns/<session>/<turn> whose parent is its start — what a write-file, a bash command or a tangle changed alike. byj turns lists and diffs them, byj-review shows one in magit or vc (RFC-069). Nothing outside a git work tree."}
   :changes-turns {:type "integer" :default 200 :family :session
                   :doc "How many turn refs (:changes?) a repository keeps, the newest by commit time, across its sessions; an older one is deleted as a turn ends, and git's gc takes what only it held. A turn that changed nothing shares its trees with the turn before, so its ref costs a commit object (RFC-069)."}
   :share-tools? {:type "boolean" :default true :family :session
                  :doc "The session's agent can share: share$status, share$ls, share$start and share$control, the operations /share runs (RFC-043, byj-gly.362.2). share$start publishes what it shares, so :tool-ask-effects asks before it by default. Never a subagent's."}
   :recap? {:type "boolean" :default true :family :session
            :doc "After a long turn, write a two-line recap of where the work stands and what is next (the session/recap predictor)."}
   :recap-after-ms {:family :session :type "integer" :default 60000
                    :doc "How long a turn must run before it earns a recap. A short turn is its own recap; 0 recaps every turn."}
   :recap-every-nth-turns {:family :session :type "integer" :default 3
                           :doc "Recap every Nth turn of the session (turn N, 2N, …) however short it was, as well as any turn longer than :recap-after-ms; 0 turns it off."}
   :enable-input-suggestions {:type "boolean" :default true :family :session
                              :doc "Offer the model's suggested next question (next-user-prompt) in the empty input line; → takes it."}
   :enable-console-activity {:type "boolean" :default true :family :session
                             :doc "Tell the next turn about each tool you call directly (`:<tool> …`): the line and the start of its result, so the model knows what you just looked at (brainyard's console activity)."}
   :console-activity-max-entries {:type "integer" :default 10 :family :session
                                  :doc "How many direct calls the next turn is told about, newest kept."}
   :console-activity-result-chars {:type "integer" :default 200 :family :session
                                   :doc "How long each direct call's line and result may be, as the next turn reads it."}
   :update-check? {:type "boolean" :default false :family :session
                   :doc "Once a day, look for a newer release of :update-source in the background and say so as a run ends; nothing is installed (byj update does that). Kept in ~/.brainyard/update-check.edn (RFC-029)."}
   :update-source {:type "string" :default "grumatic/homebrew-tap" :family :session
                   :doc "The GitHub repo (owner/name) whose newest release `byj update` installs: the public tap's copy of each release, read with no token (byj-gly.181.10). A private repo needs one: GH_TOKEN, GITHUB_TOKEN, or `gh auth token` (RFC-029)."}
   :enable-mouse {:type "boolean" :default true :family :session
                  :doc "Mouse reporting in the full-screen renderer: the wheel scrolls and a click opens a fold or a subagent. Off keeps the terminal's own click-drag text selection."}
   :keep-rails? {:type "boolean" :default true :family :session
                 :doc "The full-screen renderer keeps its rails across restarts, per project, in .brainyard/rails.edn: each rail's open or closed, its view and width, and what its sections fold (byj-gly.457). Never in a run that saves no session (:persist-session? false)."}
   :attention-signal {:family :session :type "keyword" :default :bell
                      :enum [:bell :osc9 :both :off]
                      :doc "What the full-screen renderer writes to the terminal when a session turns to Needs input (a permission ask opens), once per ask: bell (BEL, which a terminal or tmux marks on a background tab or window), osc9 (a desktop notification naming the session — iTerm2, WezTerm, kitty, Ghostty, Windows Terminal), both, or off (byj-gly.328)."}
   :output-style {:family :session :type "keyword" :default :default
                  :enum [:default :concise :explanatory]
                  :doc "How much of a turn is shown: default (the answer and the tools it called), concise (the answer, no tool block), explanatory (everything, each event's payload included). -v still shows the turn narrating itself."}
   :transcript-max-lines {:family :session :type "integer" :default 10000
                          :doc "Lines of the session's own transcript kept — what /capture writes and a resumed session draws again. The oldest go first; the turns themselves are in the snapshot and the trajectory."}
   :scrollback-max-entries {:family :session :type "integer" :default 2500
                            :doc "Entries the full-screen renderer keeps to scroll back through, per transcript (the root's and each subagent's). The oldest fall off the top."}
   :keep-sessions {:family :session :type "integer" :default 20 :doc "Most recent sessions always kept, whatever their age."}
   :session-ttl-days {:family :session :type "integer" :default 14 :doc "Sessions older than this may be pruned once beyond the keep count."}
   :keep-ask-records {:family :logs :type "integer" :default 200
                      :doc "Records of finished one-shot runs kept under .brainyard/sessions — bounded by count alone, since a run that ended cannot be resumed and the TTL is what lets them pile up (RFC-016)."}
   :resume {:family :session :type "string" :default nil :doc "Session id to continue; the CLI sets it from --resume."}
   :session-id {:family :session :type "string" :default nil
                :doc "Names this run's spill directory under .brainyard/transcripts. Generated per run when unset; two runs in one project must not share it."}
   :min-elide-chars {:family :context :type "integer" :default 2000 :doc "Tool results smaller than this are left alone: the stub would cost as much."}
   :elide-superseded-reads? {:type "boolean" :default true :family :context
                             :doc "Elide a file read whose path a later read supersedes."}
   :compaction-summary? {:type "boolean" :default true :family :context
                         :doc "Tier 2 (RFC-014): when eliding is not enough, summarize the iterations older than the verbatim floor into one record (the react/compact predictor) before dropping any. A model call, made only where records would otherwise be dropped."}
   :max-tool-calls {:family :loop :type "integer" :default 30 :doc "Tool calls dispatched per iteration."}
   :max-retries-on-llm-failure {:family :loop :type "integer" :default 3 :doc "Consecutive provider failures before the turn aborts."}
   :max-retries-on-llm-no-action {:family :loop :type "integer" :default 3 :doc "Consecutive no-action replies before the turn aborts."}
   ;; a session's own shape. These are read from a tree as `:config/…` like any
   ;; other key (`resources/brainyard/loops/session.edn` reads :config/max-turns),
   ;; so they belong here and not in a second map (byj-gly.64).
   :max-turns {:type "integer" :default 1000000 :family :session
               :doc "Turns a session will run before it stops."}
   :max-previous-turns {:type "integer" :default 10 :family :session
                        :doc "Turns of history the session keeps (by run)."}
   :full-depth {:type "integer" :default 3 :family :session
                :doc "Newest N turns keep their full tool trail; older ones are summarised."}
   :max-question-chars {:type "integer" :default 500 :family :session
                        :doc "Cap on a remembered turn's question."}
   :max-answer-chars {:type "integer" :default 1000 :family :session
                      :doc "Cap on a remembered turn's answer."}
   :parallel-tools? {:type "boolean" :default false :family :tools
                     :doc "Run an iteration's tool calls concurrently; ordered batches race (provenance P-2)."}
   :tool-lisp-output-chars {:family :tools :type "integer" :default 1000000 :doc "Cap on a tool result inside a Tool Lisp body, which no model reads: :max-output-chars is for the prompt."}
   :tool-lisp-budget {:family :tools :type "object" :default nil :doc "Overrides for Tool Lisp's {:steps :tool-calls} budget."}
   :user-tools? {:type "boolean" :default true :family :tools :doc "Load user tools from .brainyard/tools."}
   :tool-authoring? {:type "boolean" :default true :family :tools :doc "Offer the tool$* commands."}
   :tool-run? {:type "boolean" :default true :family :tools :doc "Offer tool$run, one-off Tool Lisp composition."}
   :compact-saved-tool-args? {:type "boolean" :default false :family :tools :doc "In the compact tool listing, a user tool's line carries its arguments (byj-gly.251)."}
   :composition-saved-tools? {:type "boolean" :default false :family :tools :doc "Composing Tools names the project's user tools, with their arguments, as the thing to call before writing a tool$run body (byj-gly.251)."}
   :tool-lisp-repair? {:type "boolean" :default true :family :tools :doc "tool$create and tool$validate rebuild the closers of a body that does not balance from its indentation, when that reads, and say so (byj-gly.260). Off: such a body is refused."}
   :tool-lisp-extensions {:type "vector" :default [:vocabulary :forms :control] :family :tools
                          :doc "Tool Lisp extensions in force (byj-gly.258, RFC-010): vocabulary (fnil, compare, key/val, clojure.set/walk/edn, data/diff …), forms (for, doseq, case, cond-> …), control (loop/recur, try/catch/throw, letfn). All three by default; [] is the base language. reach (bounded pmap, now/now-ms, and the store$get/put/keys tools for state between calls — byj-gly.261) is off by default: measured to help where it is wanted (byj-gly.278), and to cost haiku creates and time on the tasks that do not want it (byj-gly.279, byj-gly.293). Add :reach to take it."}
   :tool-discovery? {:type "boolean" :default true :family :tools :doc "Offer list-tools, get-tool-info and search."}
   :tool-listing-by-family? {:type "boolean" :default true :family :tools
                             :doc "In the prompt, list the primitives and your own tools a line each and every other family (config$, task$, each MCP server …) as one line of names; get-tool-info and list-tools :family give the rest. Off: a line per tool. Needs :compact-agent-tools."}
   :compact-agent-tools {:type "boolean" :default true :family :tools
                         :doc "Render the bound tools as one line each, with get-tool-info for arguments. Ignored when :tool-discovery? is false."}
   :skill-roots {:family :paths :type "vector" :default nil
                 :doc "Directories to load skills from, replacing the defaults (project, ~/.brainyard, ~/.claude, ~/.agents)."}
   :max-listed-skills {:family :tools :type "integer" :default 40 :doc "Skills named in the prompt; the rest are found with skills$find."}
   :skill-line-chars {:family :tools :type "integer" :default 80 :doc "How much of a skill's description its one line in the prompt carries, when its title is only its name — the first sentence, clipped (byj-gly.336)."}
   :skills? {:type "boolean" :default true :family :tools
             :doc "Load SKILL.md procedures from .brainyard/skills, ~/.claude/skills and friends (RFC-017)."}
   :skill-authoring? {:type "boolean" :default true :family :tools
                      :doc "Offer skills$write and skills$reload (RFC-017)."}
   ;; self-improvement (RFC-017): brainyard's keys and defaults, off until asked for
   :enable-skill-distillation {:type "boolean" :default false :family :skills
                               :doc "After successful multi-step turns, have a model propose a new skill from what the agent did; proposals are staged under .brainyard/skills/proposals for a person to accept."}
   :skill-distill-judge-window {:type "integer" :default 12 :family :skills
                                :doc "The most turns a distillation window waits: it is judged as soon as it shows a procedure (a tool sequence repeated, or one long one), and at the latest every N turns; 1 judges each candidate turn as it ends. Whatever is left is judged when the session ends."}
   :skill-distill-threshold {:type "number" :default 0.7 :family :skills
                             :doc "The score (0..1) a distilled skill must reach to be staged."}
   :self-improve-drain-ms {:type "integer" :default 3000 :family :skills
                           :doc "How long the session's end waits for self-improvement judges still running before it cancels them — brainyard's await-background-jobs! grace. The call is paid for; its result is worth a short wait."}
   :enable-skill-refinement {:type "boolean" :default false :family :skills
                             :doc "When a turn that loaded a skill fails, or a skill that runs itself fails, have a model judge whether the SKILL.md was at fault and stage a revision."}
   ;; tool distillation (RFC-010, byj-gly.249): the port's own, off until asked for
   :enable-tool-distillation {:type "boolean" :default false :family :tools
                              :doc "When a tool$run body repeats or composes tools in turns that answered, have a model generalise it into a user tool, prove it by replaying what it did, and stage it at /proposals for a person to accept."}
   :tool-distill-threshold {:type "number" :default 0.7 :family :tools
                            :doc "The score (0..1) a distilled tool must reach to be proved and staged."}
   ;; which model a predictor or a tier runs on (RFC-005)
   :agent-lm-tiers {:family :agents :type "object"
                    :default {:light nil :standard nil :deep nil}
                    :doc "Work tier → \"provider/model\" label. Ships all-nil: :standard and :deep are inert until configured, and an unset :light runs side tasks (recap, command rephrasing) on the session provider's light model."}
   :agent-tier-map {:family :agents :type "object" :default {}
                    :doc "Agent name → {:default :min :max} work tier, over what the agent declares."}
   ;; subagents (RFC-018)
   :subagents? {:type "boolean" :default true :family :agents
                :doc "Offer the agent roster from .brainyard/agents and the built-in one."}
   ;; brainyard's own gate key; the feature above is its `:agents/subagents`
   :enable-subagent-calls {:family :agents :type "boolean" :default true
                           :doc "Kill switch for agent-to-agent invocation: no agent$* tool is registered."}
   :max-agent-call-depth {:family :agents :type "integer" :default 3 :doc "Max nesting depth for subagent (agent-call) recursion."}
   :max-subagents-per-session {:family :agents :type "integer" :default 8 :doc "Live subagent instances; at the cap, the least recently asked idle one is evicted."}
   :subagent-max-turns {:family :agents :type "integer" :default 32 :doc "Child turns (dispatches and follow-up asks) one session may spend."}
   :subagent-max-iterations {:family :agents :type "integer" :default 12 :doc "Iteration ceiling for a child turn, unless its agent sets one."}
   :agent-roots {:family :paths :type "vector" :default nil
                 :doc "Directories to load agents from, replacing the defaults (project, ~/.brainyard)."}
   :builtin-agents? {:family :agents :type "boolean" :default true :doc "Include the agent roster shipped in resources."}
   :command-roots {:family :paths :type "vector" :default nil
                   :doc "Directories to load the slash commands that stand for an agent from, replacing the defaults (.brainyard/commands in the project, ~/.brainyard/commands)."}
   :max-listed-agents {:family :agents :type "integer" :default 20 :doc "Agents named in the prompt."}
   :agent-authoring? {:type "boolean" :default false :family :agents
                      :doc "Offer the meta-agent$* commands, so the model can define an agent. Off by default: they cost ~710 tokens of prompt that most sessions never use."}
   ;; set by the kernel on a child turn, never by a person
   :agent-depth {:family :agents :type "integer" :default 0 :doc "How many agents deep this turn is (set by a dispatch)."}
   :agent-chain {:family :agents :type "vector" :default [] :doc "The agents dispatched above this turn (set by a dispatch)."}
   :agent-instruction {:family :agents :type "string" :default nil :doc "Replaces the ReAct instruction block for a subagent's turn."}
   :agent-instance-id {:family :agents :type "string" :default nil :doc "The instance this turn IS, when it is a subagent (set by a dispatch)."}
   :agent-owner {:family :agents :type "string" :default nil :doc "Who dispatched this subagent turn: another instance's id, or \"root\" (set by a dispatch; recorded in its trajectory, byj-gly.298.1)."}
   :visible-tools {:family :tools :type "vector" :default [] :doc "Tool-name globs a turn may see. Empty means everything."}
   :mcp-registry? {:family :tools :type "boolean" :default true
                   :doc "Let /mcp find also search the MCP Registry (network); what it lists is third-party code, so /mcp add adds one from there disabled."}
   :mcp-registry-url {:family :tools :type "string" :default "https://registry.modelcontextprotocol.io"
                      :doc "The MCP Registry /mcp find searches — the official one, or a mirror or subregistry speaking its API."}
   :hidden-tools {:family :tools :type "vector" :default [] :doc "Tool-name globs hidden from a turn, and from every turn below it."}
   ;; durable memory, held outside the port (RFC-020)
   :memory? {:type "boolean" :default true :family :memory
             :doc "Offer memory$* when the store's command is on PATH. Nothing is ever injected into the prompt."}
   :memory-recall-cmd {:family :memory :type "vector" :default ["bd" "memories"] :doc "Command that searches memory: it is given the query."}
   :memory-read-cmd {:family :memory :type "vector" :default ["bd" "recall"] :doc "Command that reads one memory: it is given the key."}
   :memory-store-cmd {:family :memory :type "vector" :default ["bd" "remember"] :doc "Command that writes a memory: it is given the content."}
   :memory-hint? {:family :memory :type "boolean" :default true
                  :doc "Tell each turn which of the project's memories its question is about: their keys and first lines, matched on words with no model call, in the turn's own section of the prompt — never their contents, which stay memory$read's. The thresholds are the session tree's :session/memory-hint node (byj-gly.335, RFC-042)."}
   :memory-list-cmd {:family :memory :type "vector" :default ["bd" "memories" "--json"] :doc "Command that lists every memory for /memory, printing JSON: an object of key to content, or an array of {key, value} (byj-gly.327)."}
   :memory-forget-cmd {:family :memory :type "vector" :default ["bd" "forget"] :doc "Command that deletes one memory for /memory: it is given the key (byj-gly.327)."}
   :memory-max-chars {:family :memory :type "integer" :default 4000 :doc "Cap on what a memory command returns to the model."}
   :memory-snippet-chars {:family :memory :type "integer" :default 1000 :doc "Cap on the memory half of `search`."}
   :memory-timeout-ms {:family :memory :type "integer" :default 15000 :doc "Deadline for a memory command."}
   ;; the answer gate (RFC-003, byj-gly.12). OFF at 0, as brainyard ships it:
   ;; at 0 the gate costs no model call and the answer path is unchanged.
   :max-refinements {:type "integer" :default 0 :family :loop
                     :doc "How many times a turn may have its answer refused and try again. 0 disables the gate entirely."}
   ;; MCP servers as tools (RFC-024)
   :mcp? {:type "boolean" :default true :family :tools
          :doc "Offer mcp$servers when any MCP server is connected."}
   :mcp-servers {:family :tools :type "object" :default {} :merge :entries
                 :doc "MCP servers to start, {name {:command :args :env :enabled}}; :only-named? true offers a server's tools only to an agent whose :tools names them. stdio only; a remote server is reachable through `npx -y mcp-remote <url>`. Merged per server across the config files: a project entry changes only what it names, e.g. {\"github\" {:enabled false}}."}
   :mcp-wait-ms {:type "integer" :default 30000 :family :tools
                 :doc "How long a turn's start waits for an MCP server still starting when a user tool calls one of its tools; past it, the turn goes on without that user tool."}
   ;; work that outlives the turn (RFC-023)
   :todo-slug {:type "string" :default nil :family :tools
               :doc "The checklist a run continues instead of planning a new one (loops/plan-act-eval.edn)."}
   :todos? {:type "boolean" :default true :family :tools
            :doc "Offer todo$* — a checklist under .brainyard/todos that survives the turn that wrote it."}
   ;; checking a loop before running it (RFC-022)
   :flows? {:type "boolean" :default true :family :tools
            :doc "Offer flow$check — report every problem with a behavior-tree loop without running it."}
   ;; measuring and tuning predictors (RFC-021)
   :programs? {:type "boolean" :default true :family :tools
               :doc "Offer program$* — measure a predictor on a dataset and propose demos. Accepting a proposal stays a CLI action."}
   :parent-trail-k {:type "integer" :default 0 :family :agents
                    :doc "Turns of the CALLER's conversation a subagent is shown. 0 (the default) hands it nothing but its question and :agent-context; an agent that needs the trail asks for it in its own spec (RFC-018)."}
   :task-notify? {:type "boolean" :default true :family :tasks
                  :doc "Park a turn that keeps polling a running task, and tell the agent when a task it left running finishes (RFC-019)."}
   :auto-park-after-polls {:family :tasks :type "integer" :default 2
                           :doc "Polls of one still-running task tolerated in a turn before it is parked. The first is always allowed."}
   :config-write? {:type "boolean" :default true :family :tools
                   :doc "Offer config$set/unset/revert — changing settings from inside a turn. Permission keys are never writable either way (RFC-013)."}
   :event-log? {:type "boolean" :default true :family :logs
                :doc "Append every event to <project>/.brainyard/logs/events.log — the one record of what happened, written whole: no payload trimmed, no string clipped, so a dataset can be derived from it (RFC-026, RFC-021). Only :agent.llm/chunk is left out, being a second copy of a reply already recorded."}
   :event-log-max-bytes {:family :logs :type "integer" :default 52428800 :doc "Rotate the event log past this size. 50 MiB rather than 10 because the records are now whole prompts and replies — at the old limit a day of real work would roll the log, and with it anything derived from it."}
   :event-log-keep {:family :logs :type "integer" :default 3 :doc "Rotated event logs kept beside the live one."}
   :trajectory-log? {:type "boolean" :default true :family :logs
                     :doc "Append one record per turn to .brainyard/sessions/<id>/trajectory.edn: what the turn did, and what it cost (RFC-025)."}
   :prediction-notice-at {:family :loop :type "integer" :default 150 :doc "Say that a predictor has enough prediction records to compile, once per session, when the pool across sessions reaches this. 150 because the split is 60/20/20: brainyard measured its null on TWELVE val examples, 150 is the first pool whose val split (~30) is not the size already known to fail (RFC-021, byj-gly.57)."}
   ;; background tasks (RFC-019)
   :tasks? {:type "boolean" :default true :family :tasks
            :doc "Offer task$* and let a slow tool call detach into a background task."}
   :fast-eval-timeout-ms {:family :tools :type "integer" :default 30000
                          :doc "How long a tool call may run inline before it becomes a background task. 0 disables detaching."}
   :auto-background-timeout-ms {:family :tasks :type "integer" :default 180000
                                :doc "Total time a call may run before the model is handed a marker. Past :fast-eval-timeout-ms it is already a task; this is when the caller stops waiting for it."}
   :task-timeout-ms {:family :tasks :type "integer" :default 120000 :doc "Default deadline for a task$run job."}
   :max-wakeups-per-session {:family :tasks :type "integer" :default 20 :doc "Turns one session may park on a task."}
   :max-task-output-chars {:family :tasks :type "integer" :default 30000 :doc "Cap on a task's captured output."}
   :task-retention-count {:family :tasks :type "integer" :default 100 :doc "Task directories kept, whatever their age."}
   :task-retention-days {:family :tasks :type "integer" :default 7 :doc "Task directories older than this may be pruned."}
   :builtin-hooks? {:type "boolean" :default true :family :hooks :doc "Install the built-in hooks, including the loop guard and the permission gates."}
   :hook-authoring? {:type "boolean" :default true :family :hooks :doc "Offer the hook$* commands."}
   :enable-user-hooks {:type "boolean" :default true :family :hooks :doc "Kill-switch for firing user hooks; the files stay on disk."}
   :permission-mode {:type "keyword" :default :auto :family :permissions
                     :doc ":auto-approve | :ask-each-time | :deny-by-default | :auto (approve in a container; else, where a gate would ask, the auto-mode classifier decides and asks only when unsure)."}
   :tool-approval-patterns {:type "vector" :default [] :family :permissions :doc "Tool-name globs needing permission."}
   :claude-command {:type "string" :default "claude" :family :tools
                    :doc "The Claude Code CLI the agent page starts and resumes Claude Code sessions with (byj-gly.287.3, RFC-033)."}
   :codex-command {:type "string" :default "codex" :family :tools
                   :doc "The Codex CLI the agent page starts and resumes Codex sessions with (byj-gly.430.4, RFC-049)."}
   :claude-tools? {:type "boolean" :default false :family :agents
                   :doc "Register the claude$* commands, which drive Claude Code sessions (RFC-039). agent$claude's own config turns them on for its turn; off elsewhere, so no other turn pays for them."}
   :project-instruction-paths {:type "vector" :default ["AGENTS.md" "CLAUDE.md"] :family :context
                               :doc "The project's instruction files put in every turn's prompt under ## Project Instructions, in this order — brainyard's :reference-artifact-paths, renamed to match :user-instruction-paths; AGENTS.md first here, where brainyard lists CLAUDE.md first. Resolved against the project dir then the working dir; one reached two ways (a CLAUDE.md that is @AGENTS.md, a symlink) is shown once. [] turns it off (RFC-040)."}
   :user-instruction-paths {:type "vector" :default ["~/.brainyard/AGENTS.md"] :family :context
                            :doc "The person's own instruction files, for every project, under ## User Instructions after the project's — ~/.brainyard/AGENTS.md, which replaces brainyard's ~/.brainyard/BRAINYARD.md. Add ~/.claude/CLAUDE.md or ~/.codex/AGENTS.md to share another tool's; relative paths are against home. [] turns it off (RFC-040)."}
   :user-instructions-max-chars {:type "integer" :default 8192 :family :context
                                 :doc "How many characters of the person's own instruction files a prompt carries: a budget of their own, so a project's never pushes them out."}
   :project-instructions-max-chars {:type "integer" :default 32768 :family :context
                                    :doc "How many characters of the project's instruction files (:project-instruction-paths) a prompt carries, across all of them, in order — Codex's 32 KiB. A file past what is left is cut at its last heading before the limit, with where the rest is (RFC-040)."}
   :init-tools? {:type "boolean" :default false :family :agents
                 :doc "Register the init$* commands, which check and prepare a project for brainyard (RFC-040). agent$init's own config turns them on for its turn; off elsewhere."}
   :claude-home {:type "string" :default nil :family :agents
                 :doc "Where Claude Code keeps its sessions; ~/.claude when unset. A test points it at a fixture."}
   :claude-handover-timeout-ms {:type "integer" :default 1800000 :family :agents
                                :doc "How long session$send waits for a live Claude Code session to take a task handed to it and answer — idle first, then its turn (byj-gly.429). session$ask waits :ask-timeout-ms."}
   :codex-tools? {:type "boolean" :default false :family :agents
                  :doc "Register the codex$* commands, which drive Codex sessions (RFC-049). agent$codex's own config turns them on for its turn; off elsewhere."}
   :codex-model {:type "string" :default nil :family :agents
                 :doc "The model agent$codex starts a Codex session with when its plan names none; Codex's own default when unset."}
   :codex-require-sandbox? {:type "boolean" :default false :family :agents
                            :doc "Refuse to delegate to Codex (agent$codex) where Codex's own sandbox cannot start — Linux without unprivileged user namespaces, a container such as a Share machine — rather than run the session unsandboxed with the publish guard alone between it and a push, which it does by default and says (RFC-049 decision 7, byj-1wv.62.12)."}
   :codex-max-rounds {:type "integer" :default 8 :family :agents
                      :doc "How many of a Codex session's turns agent$codex waits out and judges before it stops (byj-gly.430.6)."}
   :codex-max-cost-usd {:type "number" :default 5.0 :family :agents
                        :doc "What one agent$codex delegation may spend, priced from prices.edn's :openai rates, before it stops to ask a person; a model the table does not price is never over (byj-gly.430.7)."}
   :codex-turn-timeout-ms {:type "integer" :default 1800000 :family :agents
                           :doc "How long agent$codex waits for one of a Codex session's turns to end."}
   :codex-settle-ms {:type "integer" :default 1500 :family :agents
                     :doc "How long a Codex session agent$codex waits on must stay idle before its turn counts as over."}
   :codex-home {:type "string" :default nil :family :agents
                :doc "Where Codex keeps its state; $CODEX_HOME, else ~/.codex, when unset (RFC-049). A test points it at a fixture."}
   :codex-handover-timeout-ms {:type "integer" :default 1800000 :family :agents
                               :doc "How long session$send waits for a live Codex session to take a task handed to it and answer — idle first, then its turn (byj-gly.430.5)."}
   :claude-permission-mode {:type "string" :default "auto" :family :agents
                            :doc "The --permission-mode agent$claude starts a Claude Code session with (RFC-039). auto, so its classifier passes routine commands that acceptEdits sent to the person one dialog at a time (byj-gly.290.9); pushing stays out of the session either way, by its deny rules and publish guard, unless the task tells it to publish (byj-gly.307)."}
   :claude-usage {:type "string" :default "auto" :family :agents
                  :doc "Whether /usage and the status line show the Claude Code subscription's windows (session, weekly, per model), read from Claude Code's own OAuth token, read-only, at api.anthropic.com/api/oauth/usage — not a public interface, so any failure reads as unknown (byj-gly.324). auto: when the session runs on the claude-code provider; on; off. /doctor shows them whatever this says."}
   :claude-model {:type "string" :default nil :family :agents
                  :doc "The model a Claude Code session agent$claude starts is given when its plan names none (claude/plan's :model), e.g. \"sonnet\". Unset: Claude Code's own default, which is the account's (byj-gly.320)."}
   :claude-settings {:type "string" :default nil :family :agents
                     :doc "A --settings file or JSON agent$claude starts a Claude Code session with, e.g. one that sets byj's gates as its PreToolUse hook (byj hook claude-settings, RFC-012)."}
   :claude-max-rounds {:type "integer" :default 8 :family :agents
                       :doc "How many of a Claude Code session's turns agent$claude watches and guides before it reports back (RFC-039)."}
   :claude-turn-timeout-ms {:type "integer" :default 1800000 :family :agents
                            :doc "How long agent$claude waits for one turn of a Claude Code session before it reports it still running."}
   :claude-settle-ms {:type "integer" :default 1500 :family :agents
                      :doc "How long a Claude Code session agent$claude waits on must stay idle before its turn counts as over."}
   :claude-allow {:type "vector" :default [] :family :agents
                  :doc "More commands a delegated Claude Code session may run without asking, as Claude Code permission rules — the project's test command, say: [\"Bash(python3 -m unittest:*)\"] (byj-gly.292, RFC-039)."}
   :claude-allow-defaults? {:type "boolean" :default true :family :agents
                            :doc "Allow a delegated session the shipped read-only and local commands (resources/brainyard/claude/allow.edn). Off: only :claude-allow."}
   :claude-attribution? {:type "boolean" :default false :family :agents
                         :doc "Let a delegated Claude Code session add its own attribution to commits and pull requests (a Co-Authored-By trailer, a generated-with line). Off: its settings hide it (claude-agent/no-attribution, byj-gly.308), unless :claude-settings sets attribution itself."}
   :claude-git-remote {:type "string" :default "origin" :family :agents
                       :doc "The remote a finished delegation's branch is pushed to: in the commands claude$handoff gives its caller, and the remote-tracking ref it checks when the session published (RFC-039, byj-gly.307)."}
   :claude-gh-command {:type "string" :default "gh" :family :agents
                       :doc "The GitHub CLI claude$handoff finds a delegation's pull request with, and names in the command that opens it."}
   :claude-bd-command {:type "string" :default "bd" :family :agents
                       :doc "The beads CLI a delegation's outcome is noted on its bead with (claude$finish)."}
   :claude-max-cost-usd {:type "number" :default 5.0 :family :agents
                         :doc "What a delegation to Claude Code may cost, priced from its transcript, before agent$claude stops guiding it and asks."}
   :auto-mode-classifier? {:type "boolean" :default true :family :permissions
                           :doc "In :auto outside a container, where a gate would ask, a classifier (predictor permission/classify) decides first: allow runs the call, block refuses it with a reason, unsure asks. Off: :auto asks, as brainyard's does."}
   :auto-mode-classifier-lm {:type "string" :default "claude-code/sonnet" :family :permissions
                             :doc "The model the auto-mode classifier asks, as provider/model. Sonnet: measured faster than haiku through claude -p (median 5.3 s against 8.0 s), the same cost per verdict, fewer needless prompts (byj-gly.285.3)."}
   :auto-mode-classify-every-command? {:type "boolean" :default true :family :permissions
                                       :doc "In :auto, classify every command a tool runs, not only those :bash-ask-commands names — measured: the list missed every dangerous command it does not name (python -c, : > file, curl | sh, npm publish), the classifier none (byj-gly.285.3). Costs a classifier call per new command line in a request. Off: only the list's commands."}
   :auto-mode-on-unsure {:type "keyword" :default :ask :family :permissions
                         :doc "What :auto does when the classifier is unsure or fails: :ask the person (headless: refuse), or :deny."}
   :tool-allow-tools {:type "vector" :default [] :family :permissions :doc "Tool-name globs exempt from the approval patterns."}
   :tool-deny-tools {:type "vector" :default [] :family :permissions :doc "Tool-name globs always refused."}
   :tool-ask-effects {:type "set" :default #{:share/publish :terminal/input :editor/eval :editor/execute} :family :permissions
                      :doc "Effects needing permission, e.g. #{:process}. The default asks before a share is started (:share/publish: share$start, byj-gly.362.2), before typing into a terminal program (:terminal/input: terminal$send, byj-gly.417, which in a shell runs commands) and before code is run in the person's Emacs (:editor/eval: emacs$eval; :editor/execute: emacs$org-execute, RFC-067); a set given here replaces it, so keep those in it to go on being asked."}
   :bash-ask-commands {:type "vector" :family :permissions
                       :default ["rm *" "rmdir *" "unlink *" "mv *" "dd *" "shred *" "truncate *" "mkfs*"
                                 "find * -delete*" "find * -exec rm *" "xargs rm*"
                                 "git push*" "git reset --hard*" "git clean *" "git checkout -- *" "git restore *"
                                 "git branch -D *" "git stash drop*" "git stash clear*"
                                 "chmod *" "chown *" "sudo *" "kill *" "killall *" "pkill *"
                                 "byj-share start*" "byj-share exec*" "byj-share record*" "byj-share run*"]
                       :doc "Command shapes that could destroy or change things, as globs over one command of a line (its words, past VAR=value): a matching command is asked about even inside the allowed directories — in :auto, the classifier decides (byj-gly.285.4). [] asks about none."}
   :bash-allow-commands {:type "vector" :default [] :family :permissions
                         :doc "Programs a command line may run, as globs. Empty means anything. Not a sandbox: a line that cannot be segmented is refused."}
   :bash-deny-commands {:type "vector" :default [] :family :permissions
                        :doc "Globs refused anywhere in a command line, whatever the mode."}
   :tool-deny-effects {:type "set" :default #{} :family :permissions :doc "Effects always refused."}
   :working-dir {:family :paths :type "string" :default "." :doc "Directory tools resolve relative paths against."}
   :project-dir {:family :paths :type "string" :default nil
                 :doc "Root of the project: where .brainyard/ lives. Unset means the git root above the working dir, else the working dir (BY_PROJECT_DIR overrides)."}
   :allowed-dirs {:type "vector" :default nil :family :permissions
                  :doc "Directories a tool may read and write, beyond the working dir. Unset means /tmp, the system temp dir, the working dir and ~/.brainyard. The system temp dir is $TMPDIR — on macOS a per-user /var/folders/…/T/, where programs put temporary files rather than /tmp."}
   :denied-paths {:type "vector" :family :permissions
                  :default ["~/.brainyard/share/credentials" "~/.brainyard/.env" "~/.claude/.credentials.json" "~/.claude/settings.json"
                            "~/.claude/settings.local.json" "~/.codex/auth.json" "~/.config/byj-exam" "~/.ssh" "~/.aws"
                            "~/.netrc" "~/.config/gh/hosts.yml" "~/.docker/config.json"]
                  :doc "Files and directories no path tool reads or writes, in any mode, nor names a directory holding: a person's logins and keys (byj-1wv.115.40), the connections the agent builder keeps among them (byj-1wv.115.44). ~ is the home. A shell's arguments are not paths the gate sees."}
   :path-permission? {:type "boolean" :default true :family :permissions
                      :doc "Check the paths a tool is given against :allowed-dirs, asking or refusing when they fall outside."}
   :predictor-roots {:family :paths :type "vector" :default nil :doc "Directories searched for predictor specs; derived from the working dir when unset."}
   :params-roots {:family :paths :type "vector" :default nil :doc "Directories searched for predictor params; derived from the working dir when unset."}})

(def config-keys (set (keys config-items)))

(def defaults
  "Every key's declared default — what `agent/default-config` used to hold."
  (into {} (for [[k {:keys [default]}] config-items] [k default])))

;; ============================================================================
;; Coercion and validation
;; ============================================================================

(defn coerce
  "A string (from the environment) as the declared type; the string itself when
  it will not parse, as brainyard's `coerce-value` does."
  [type v]
  (if-not (string? v)
    v
    (let [s (str/trim v)]
      (case type
        "integer" (or (parse-long s) v)
        "number" (or (parse-double s) v)
        "boolean" (cond (contains? #{"true" "yes" "1"} (str/lower-case s)) true
                        (contains? #{"false" "no" "0"} (str/lower-case s)) false
                        :else v)
        "keyword" (keyword (str/replace s #"^:" ""))
        "vector" (vec (remove str/blank? (map str/trim (str/split s #","))))
        "set" (set (map #(keyword (str/replace % #"^:" ""))
                        (remove str/blank? (map str/trim (str/split s #",")))))
        "object" (try (edn/read-string s) (catch Throwable _ v))
        v))))

(defn from-json
  "Overrides that arrived as JSON — an SDK client's (RFC-053) — as the
  declared types: a keyword key's string a keyword, a set key's array a set
  of keywords, a string where a number or a boolean is declared coerced as
  the environment's is. A key nobody declares is kept, for `load-config` to
  drop as it drops any."
  [m]
  (into {} (for [[k v] m
                 :let [k (keyword (str/replace (name k) #"^:" ""))
                       type (get-in config-items [k :type])]]
             [k (cond
                  (and (= "set" type) (sequential? v)) (set (map #(if (string? %) (keyword (str/replace % #"^:" "")) %) v))
                  (and (= "keyword" type) (string? v)) (keyword (str/replace v #"^:" ""))
                  :else (coerce type v))])))

(defn key-schema
  "The malli schema a key's value is held to, from its declared `:type` and,
  for a key that names its values, its `:enum` (byj-gly.204). An unknown type
  accepts anything, as brainyard's `valid-config-value?` does."
  [k]
  (let [{:keys [type enum]} (get config-items k)]
    (if enum
      (into [:enum] enum)
      (case type
        "integer" :int
        "number" number?
        "boolean" :boolean
        "keyword" :keyword
        "string" :string
        "vector" [:maybe [:sequential :any]]
        "set" [:or [:set :any] [:sequential :any]]
        "object" [:maybe :map]
        :any))))

(def ^:private validator
  (memoize (fn [k] (m/validator (key-schema k)))))

(defn valid?
  "Is `v` a value the key's schema accepts?"
  [k v]
  ((validator k) v))

(defn value-problem
  "What is wrong with `v` for key `k`, as one line — `:output-style should be
  either :default, :concise or :explanatory` — or nil when it is right."
  [k v]
  (when-let [ps (schema/explain-problems [:map [k (key-schema k)]] {k v})]
    (str/replace (first ps) (str "`" (name k) "`") (str k))))

(def output-styles
  "What each `:output-style` shows, as the display level every viewer reads
  (`:display-box`, RFC-004): default the answer and its tool calls, concise the
  answer alone, explanatory every event's payload too."
  {:default :normal :concise :quiet :explanatory :debug})

(defn display-level
  "The display level a run starts at: `-v`'s narrating turn when asked for,
  else what `:output-style` names."
  [config verbose?]
  (if verbose? :verbose (get output-styles (:output-style config) :normal)))

;; ============================================================================
;; Files
;; ============================================================================

(defn find-git-root
  "The nearest directory at or above `dir` holding `.git` — brainyard's
  `find-git-root`, which requires a `.git` DIRECTORY; a worktree's `.git` file
  counts here too (divergence `project-root-git-walk`)."
  [dir]
  (loop [d (.getAbsoluteFile (io/file (or dir ".")))]
    (when d
      (if (.exists (io/file d ".git")) (.getPath d) (recur (.getParentFile d))))))

(defn canonical-dir
  "A directory as an absolute, canonical path — no `.`, no `..`, no symlink.

  Every directory this layer hands on is one of these, because they are
  compared and they are STORED. A session snapshot that said `:working-dir \".\"`
  restored to wherever the next process happened to be started (RFC-016), and a
  path ending `/.` defeats the prefix tests the permission gate and the pruner
  do (`under?`, `delete-tree!`). Nil stays nil; a path that cannot be
  canonicalised — a directory that does not exist yet — falls back to absolute,
  which is still restorable."
  [d]
  (when (and d (not (str/blank? (str d))))
    (let [f (io/file (str d))]
      (try (.getCanonicalPath f) (catch Throwable _ (.getAbsolutePath f))))))

(defn resolve-project-dir
  "brainyard's `resolve-project-dir`: an explicit project dir, else the git root
  above the working dir, else the working dir itself. Always canonical."
  [working-dir explicit]
  (canonical-dir (or explicit (find-git-root working-dir) (or working-dir "."))))

(defn default-allowed-dirs
  "brainyard's `default-allowed-dirs`: /tmp, the system temp dir, the project
  dir and the user's ~/.brainyard."
  [project-dir]
  (->> ["/tmp" (System/getProperty "java.io.tmpdir") (or project-dir ".")
        (some-> (System/getenv "HOME") (str "/.brainyard"))]
       (remove nil?) distinct vec))

(defn config-file [dir] (io/file dir ".brainyard" "config.edn"))

(defn- bridge
  "brainyard's `bridge-permissions-section`: the file's `[:permissions …]`
  section onto flat schema keys."
  [m]
  (cond-> (get-in m [:agent :config] {})
    (contains? (:permissions m) :mode) (assoc :permission-mode (get-in m [:permissions :mode]))
    (contains? (:permissions m) :allowed-dirs) (assoc :allowed-dirs (get-in m [:permissions :allowed-dirs]))))

(defn declared-keys
  "The keys other programs say they keep in config.edn's `[:agent :config]`
  (byj-ahf.21): each `<user-dir>/.brainyard/config-keys/<program>.edn`, a map
  whose `:keys` names them, as a program that reads the same files installs
  it (Share's `byj-share setup`). byj neither reads nor checks those keys; it
  only stops calling them unknown. An unreadable declaration declares nothing."
  [user-dir]
  (let [d (when user-dir (io/file (str user-dir) ".brainyard" "config-keys"))]
    (into #{} (for [f (sort-by #(.getName ^java.io.File %) (or (some-> d .listFiles) []))
                    :when (str/ends-with? (.getName ^java.io.File f) ".edn")
                    :let [m (try (edn/read-string (slurp f)) (catch Throwable _ nil))]
                    k (when (map? m) (:keys m))
                    :when (keyword? k)]
                k))))

(defn read-file
  "One config.edn as {:config {k v} :problems [...]} — never throws. Unknown
  keys, schema keys at the top level (brainyard's `::misplaced-config-keys`)
  and values of the wrong type are reported and dropped; a key another
  program declares (`others`, `declared-keys`) is dropped unreported."
  [^java.io.File f & [others]]
  (if-not (.isFile f)
    {:config {} :problems []}
    (let [path (.getPath f)
          m (try (edn/read-string (slurp f)) (catch Throwable e {::unreadable (ex-message e)}))]
      (cond
        (::unreadable m) {:config {} :problems [(str path ": unreadable EDN — " (::unreadable m))]}
        (not (map? m)) {:config {} :problems [(str path ": expected a map")]}
        :else
        (let [declared (bridge m)
              misplaced (sort (filter config-keys (keys m)))
              unknown (sort (remove #(or (config-keys %) (contains? others %)) (keys declared)))
              bad (sort (for [[k v] declared :when (and (config-keys k) (not (valid? k v)))] k))]
          {:config (into {} (for [[k v] declared :when (and (config-keys k) (valid? k v))] [k v]))
           :problems (cond-> []
                       (seq misplaced) (conj (str path ": " (str/join ", " misplaced)
                                                  " belong under [:agent :config] — ignored"))
                       (seq unknown) (conj (str path ": unknown keys ignored: " (str/join ", " unknown)))
                       (seq bad) (conj (str path ": wrong value, ignored: "
                                            (str/join "; " (for [k bad] (value-problem k (get declared k)))))))})))))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn env-layer
  "Schema keys set in the environment, coerced by their declared type.
  `env-fn` is (fn [name]) → string or nil — the CLI's, so a .env value counts."
  [env-fn]
  (into {} (for [k (sort config-keys)
                 :let [v (some-> (env-fn (env-name k)) str/trim not-empty)]
                 :when v]
             [k (coerce (get-in config-items [k :type]) v)])))

(def next-session-keys
  "Keys only read when a run STARTS, so a change to one waits for the next
  session: what the front end, the event bus, startup pruning, the model's cache settings and the session loop's own bounds were
  built from, and what names the run itself. Every other key is read as a turn
  goes, and a change applies from the next turn (RFC-013)."
  #{:working-dir :project-dir :session-id :resume
    :enable-mouse :enable-input-suggestions :keep-rails?
    :event-log? :event-log-keep :event-log-max-bytes
    :prune-sessions? :keep-sessions :session-ttl-days :keep-ask-records
    :scrollback-max-entries
    :prompt-cache? :prompt-cache-key :cache-ttl
    :max-turns})

(defn applies-next-session?
  "Does an edit to `k` in a config file wait for the next session? A key read
  only when a run starts (`next-session-keys`) — and every key of the
  permissions family: `config.edn` sits in the working directory, a file tool
  can write it, and a mode or an allow-list the agent wrote must not govern the
  run that wrote it (byj-gly.226). A person changes the mode mid-run with
  shift+tab."
  [k]
  (boolean (or (contains? next-session-keys k)
               (= :permissions (get-in config-items [k :family])))))

(defn- entries
  "A map-valued key's value with its entry names as strings: one file may
  write {\"github\" …} and another {:github …}, and they name one server."
  [m]
  (into {} (for [[k v] m] [(if (keyword? k) (name k) (str k)) v])))

(defn merge-layer
  "`m` layered over `acc`: a key replaces the one below it, except a key
  whose item says `:merge :entries`, which merges entry by entry and each
  entry's own keys over the one below. `:mcp-servers` is one: a project that
  adds a server, or disables one, must not have to restate — and copy into a
  committed file — every server, token and all, the user file holds
  (byj-gly.220)."
  [acc m]
  (reduce-kv (fn [acc k v]
               (if (and (= :entries (get-in config-items [k :merge])) (map? v) (map? (get acc k)))
                 (assoc acc k (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                                          (entries (get acc k)) (entries v)))
                 (assoc acc k v)))
             acc m))

(defn load-config
  "The effective config, and where each key came from.

  opts: :working-dir, :overrides (the caller's — CLI flags), :env-fn,
        :user-dir (default $HOME), :project-dir (default: the git root above
        the working dir, else the working dir).

  → {:config {k v} :sources {k :default|:user|:project|:caller|:env}
     :problems [string] :files [path]}"
  [{:keys [working-dir overrides env-fn user-dir project-dir]
    :or {working-dir "." env-fn (fn [_] nil)}}]
  (let [user-dir (or user-dir (System/getenv "HOME"))
        user-file (when user-dir (config-file user-dir))
        project-dir (resolve-project-dir working-dir (or (:project-dir overrides) project-dir))
        project-file (config-file project-dir)
        others (declared-keys user-dir)
        user (if user-file (read-file user-file others) {:config {} :problems []})
        ;; the project's own file is not read twice when it is the user's
        project (if (and user-file (= (.getCanonicalPath user-file) (.getCanonicalPath project-file)))
                  {:config {} :problems []}
                  (read-file project-file others))
        env (env-layer env-fn)
        overrides (select-keys overrides config-keys)
        ;; the run's working dir IS the default for the key, so :allowed-dirs
        ;; and the tools resolve against the directory the caller meant
        layers [[:default (assoc defaults
                                 :working-dir (canonical-dir working-dir)
                                 :project-dir project-dir)]
                [:user (:config user)] [:project (:config project)]
                [:caller overrides] [:env env]]]
    {:config (let [c (reduce merge-layer {} (map second layers))
                   ;; lazy defaults, as brainyard's :default-fn keys are
                   c (assoc c :project-dir (resolve-project-dir (:working-dir c) (:project-dir c)))]
               (cond-> c (nil? (:allowed-dirs c))
                       (assoc :allowed-dirs (default-allowed-dirs (:project-dir c)))))
     :sources (reduce (fn [acc [label m]] (into acc (for [k (keys m)] [k label]))) {} layers)
     :problems (vec (concat (:problems user) (:problems project)))
     :files (vec (keep (fn [[f r]] (when (and f (.isFile f) (seq (:config r))) (.getPath f)))
                       [[user-file user] [project-file project]]))}))

;; ============================================================================
;; Features — brainyard's `feature.clj` in the small
;; ============================================================================

(def families
  "Every key's family, in the order `/config` shows them: what the agent does
  first, then what it reaches, then where and how it keeps things."
  [:loop :context :provider :session :tools :skills :tasks :agents :memory :hooks
   :permissions :logs :paths])

(def features
  "A feature is a named capability with a boolean gate key, grouped by family —
  brainyard's `/feature` view. Each is a view over the schema, never a second
  store (brainyard's rule); a family's other keys are its knobs."
  (into {} (for [[k {:keys [family doc type]}] config-items :when (and family (= "boolean" type))]
             [(keyword (name family) (str/replace (name k) #"\?$" "")) {:gate k :family family :doc doc}])))

(defn feature-on?
  "Is the feature on in this config? An unknown feature is off."
  [config id]
  (boolean (when-let [gate (get-in features [id :gate])]
             (let [v (get config gate)]
               (if (number? v) (pos? v) (boolean v))))))

(defn describe
  "Every key with its value and the layer it came from, for `--print-config`."
  [{:keys [config sources]}]
  (vec (for [k (sort config-keys)]
         {:key k :value (get config k) :source (get sources k :default)
          :doc (get-in config-items [k :doc])})))

(defn jolt-version
  "The jolt this process is running on, or nil when the property is absent.

  One reader, because two things record it and a second copy would drift: the
  trajectory (RFC-025), so a turn says which runtime produced it, and the test
  runner, which warns when the runtime is not the one the suite was last
  verified against (`byj-gly.118`).

  `deps.edn` declares `:jolt/min-version`, which is a FLOOR and not a pin, and
  `ebb` — the effect substrate — declares no floor at all and pins jolt 0.8.2 in
  its own CI. So the runtime can move under this repository with nothing to
  notice: a `brew upgrade` from 0.8.8 to 0.8.10 silently stopped `via` conveying
  dynamic bindings, and it was caught by luck rather than by a mechanism
  (`byj-gly.117`)."
  []
  (System/getProperty "jolt.version"))
