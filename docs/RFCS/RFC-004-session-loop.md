# RFC-004 — The session loop

**Status:** partial.

## Purpose

Specifies `by run`: a multi-turn conversation as an OUTER behavior tree
(`resources/brainyard/loops/session.edn`) that reads input, handles slash
commands, and runs one complete ReAct loop (RFC-003) per agent turn. It is the
first nesting of loops; the same pattern is how larger loops (a feature loop
holding implementer and reviewer loops, as samizdat's design has it) would be
built.

brainyard equivalents: `agent/ask` per turn (`core/agent.clj:814`) with its
per-turn st-memory reset, the `previous-turns` chain
(`common/previous_turns.clj`, `coact_agent.clj` `format-previous-turns`), and
the TUI's slash commands (`bases/agent-tui/.../commands.clj`).

## Scope

**This layer decides** when a turn starts and ends, what counts as a command,
and what of a finished turn the next turn remembers.

**It must not know** how a turn reaches its answer, or how input arrives and
output is shown — both are functions on ctx.

**It hands** `:question`, `:history-context` and `:turn-number` into each turn's
scope, and receives the whole turn memory back as `:turn`.

## Model

```
init → [repeat max-turns until ended?
          read-input
          [eof? → end
           | blank?
           | command? → run-command
           | begin-turn
             → [scope {:in {:question :input, :history-context :history-context,
                            :turn-number :turn-count}
                       :out {:turn :*}
                       :cancellable :turn
                       :on-cancel {:answer "*(Turn cancelled.)*" :terminated-by :cancelled …}}
                  [include "brainyard/loops/react.edn"]
                | turn-failed]
             → end-turn
             → [recap? → recap | (nothing)]
             → note-skill-candidate
             → [distill-window? → distill-window | (nothing)]
             → [refine? → refine | (nothing)]
             → note-tool-candidate
             → [tool-distill? → tool-distill | (nothing)]]]
→ drain-self-improve
→ [distill-leftover? → distill-window (inline) | (nothing)]
→ close
```

**`:max-turns` (1,000,000) bounds the loop**, one iteration per line read — a
command counts as well as a question. A session that reaches it closes, and
`close` says so and that `--resume` carries on; a session ended by `/quit` or
end of input says nothing of the kind.

### I/O seam

| ctx key | contract |
|---|---|
| `:read-input` | `(fn [prompt]) → line or nil`; nil is end of input. |
| `:emit` | `(fn [kind text])`, kind ∈ `:answer :info :error`. |

The CLI binds these to stdin/stdout (queued `-q` questions first). A TUI,
a web channel or a test binds its own.

### History

After each turn `end-turn` appends `turn-summary` — `{:question :answer
:terminated-by :tool-trail}` — to `:previous-turns` (capped at
`:max-previous-turns`) and re-renders `:history-context`:

```
[Turn 1 · full]
  Q: …                (≤ :max-question-chars)
  A: …                (≤ :max-answer-chars)
  tools: read-file {:path "x"}; …   (newest :full-depth turns only, last 5 calls)
```

The inner loop renders it as the `## history-context` zone of its system
prompt (RFC-005), between session-context and user-context — brainyard's zone
order.

The rendering itself lives in `brainyard.history`, because a subagent instance
keeps its own turns in exactly this shape and renders them the same way
(RFC-018). Two scopes, one format: a model should not have to read two
conventions for one idea.

### `@here`: what the person is looking at

A question that has `@here` as a word of its own (`session/mentions?`: not
`@here.clj`, not `me@here`) is told, in its turn's user-context, what the
person is looking at in a running editor of the project's: the file or
buffer, the cursor, whether it has unsaved edits, and the selection
(byj-gly.453). The session tree's `:session/editor-context` node, after the
memory hint, reads it as the turn begins from the Emacs `:emacs-server`
names when it is up (`emacs/context!`, RFC-067), else with
`terminal/context!` from a Terminal program that says (RFC-028), and words
it from `prompts/editor-context.edn` — in an org file with the heading path
and the src block at point; the node's `:mention` names the word. It is
read for the mention, not left to the model: `emacs$context` is the same
answer as a tool, and a model does not always call it when told "this".
When nothing can be read, the turn is told to ask which file and lines, and
the person sees why on an info line. Event `:session.editor/context`
(`:trace`): `{:program :file :region-chars}` or `{:error}`. The TUI offers
`@here` first among `@` completions.

### Coaching: a stance kept across turns

While the session coaches the person toward a goal (`/emacs coach GOAL`,
`coach$set`, RFC-067), the session tree's `:session/coach-context` node, after
`@here`'s, reads the session's `coach.edn` as each turn begins and puts
`prompts/coach.edn`, filled with the goal and level, in the turn's
user-context: how to coach is told every turn, not once, so it holds across
compaction and a resume.

### The end of a turn, and the recap

`begin-turn` stamps `:turn-started-at`; `end-turn` works out
`:turn-elapsed-ms` and puts `:elapsed-ms` and `:ended-at` (the local clock,
from `date`, because jolt's `java.time` knows only UTC, provenance P-10) on
`:session.turn/end`. That is what a front end says under the answer: how long
it took, and when it finished by the person's clock.

After a long turn, the tree runs a **recap**: `:session/recap?` holds when
`:recap?` is on (the default), the turn was not cancelled, and either it ran at
least `:recap-after-ms` (60 s by default; 0 recaps every turn) or
it is every `:recap-every-nth-turns`th turn of the session — turns 3, 6, 9 … by
default; 0 turns this trigger off. The two are independent: a long turn is
recapped whatever its number.
`:session/recap`
runs the node's predictor (`session/recap`) on the rendered history and the
turn that just ended — its question, each tool call with the start of its
result, and its answer — and emits `:session.turn/recap {:turn :recap
:next-step :conversation-title :cost}`. The recap also names the whole
conversation (`conversation-title`, at most five lowercase words joined by
hyphens). `session/conversation-title` makes it that shape whatever the model
wrote, and it is kept in `:conversation-title` and in the snapshot. The recap is written for someone who looked away while the
turn ran, which is why a short turn gets none: its answer is still on the
screen.

The recap is optional by construction, a `:fallback` whose second child is an
empty `:sequence`, and best effort: a recap that fails is no recap, never a
failed turn. Its call is counted in the session's `:usage` like any other.
Which turns earn one is config, and what it says is the predictor spec and its
params, so both change without a rebuild (RFC-001).

After the recap come the **self-improvement** nodes (RFC-017), off unless
`:enable-skill-distillation` or `:enable-skill-refinement` is on.
`note-skill-candidate` keeps the turn in the distillation window when it
answered in two or more steps. `distill-window?` says when the window is due:
as soon as it shows a procedure (thresholds in the node's `:auto` opts), and
at the latest at the cadence. The distill and
refine paths each run a judge predictor and stage what it drafts as a
proposal. After the turn loop ends, `distill-leftover?` judges whatever the
window still holds. Each is optional and best effort in the same way as the
recap; each call is counted in `:usage` and reported as
`:session.skill/judged` with its cost. The judges run **off the turn**, as
background tasks, so none delays the next prompt; before `close`,
`drain-self-improve` gives those still running a short grace (RFC-017,
`byj-gly.247`).

**Tool distillation** (RFC-010, `byj-gly.249`), off unless
`:enable-tool-distillation`, comes last. `note-tool-candidate` keeps the turn's
`tool$run` bodies that worked; `tool-distill?` holds as soon as they show one
worth keeping (a shape run `:repeats` times, or a body calling `:multi-tools`
tools — the node's `:auto` opts). `tool-distill` asks `session/distill-tool` for
a user tool, proves it by replay, and stages it at `/proposals`; each verdict is
`:session.tool/judged`. It has no cadence and no session-end flush.

A run is not scored in the session: whoever scores runs reads the
trajectory after it (RFC-025; RFC-070 "Standing alone").

### A turn nobody typed

A turn's question usually comes from `read-input`. It may also come from a
**parked turn** resuming (RFC-019): the model asks to be woken when a background
task finishes, and the front end hands the wakeup over as if it had been typed.
The loop is unchanged — the resume is an ordinary turn, with an ordinary
question, landing in history like any other. What the front end owes it is a
reader that can notice a wakeup while it waits, since nothing else will.

A **request over the session channel** (RFC-031) enters the same way: another
session or a script asked this one something, and the front end takes it where
it takes a wakeup. The loop tells the channel when that turn starts and ends,
so the answer goes back to whoever asked.

### Cancelling a turn

The agent-turn scope is cancellable under `:turn` (RFC-002). `session/run`
takes a `:cancellers` atom and `(session/cancel-turn! cancellers)` cancels the
running turn — its LLM call, tools and subprocesses — and returns true, or
false when no turn runs. The cancelled turn is recorded like any other, with
the tree's `:on-cancel` values, and the session reads the next input.

The CLI binds Ctrl-C to it. jolt has no signal API, but SIGINT reaches Chez's
`keyboard-interrupt-handler`, which runs on the main thread while it waits on
the session's effect (measured); `brainyard.main/with-sigint` installs a
handler for the session's duration and restores the previous one. During a
turn Ctrl-C cancels the turn; at the prompt it exits with status 130. For
`byj ask`, Ctrl-C cancels the question being asked.

Under the full-screen renderer there is no signal to handle: the loop puts the
terminal in raw mode, so Ctrl-C arrives as a key and cancels the turn through
the same `cancel-turn!`, and Ctrl-Q ends the session (RFC-028).

### Commands

`/quit` `/exit` (end), `/clear` (empty history), `/rewind [TURN [end] |
undo [N]]`, `/undo-turn TURN` and `/fork [TURN [keep-files]]` (the files put
back to a turn, one turn taken back, a session forked before a turn —
RFC-069; a rewind is told to the next turn as console activity, and so is
what was done to the session's files from outside it, left as a note that
`begin-turn` takes), `/history`,
`/usage [N] [--breakdown]`, `/capture [PATH]`, `/task [list|ID|cancel ID]`,
`/todo [SLUG]`, `/model [N|NAME|PROVIDER/MODEL|list]`, `/mcp`,
`/tools [FILTER|NAME]` (every tool the session can call — `toolshelf/rows`,
handed to a picker through `:present :tools`; without one, a filter lists the
matches by family and a name prints its arguments, result, effects, the
permission verdict and how to call it both ways — `byj-gly.273.1`),
`/proposals [KIND|show ID|accept ID [user]|reject ID]` (what self-improvement
proposed — skills, user tools, predictor params — `byj-gly.249.1`), `/status`,
`/config [FAMILY|KEY [VALUE]|unset KEY]`, `/help`.

`/<tool> args`, naming a registered tool no command shadows, is a **turn**
(byj-gly.273.2): `command?` lets it through, and the agent turn's first leaf,
`:session/tool-request`, says brainyard's `[tool] <description>` line and
rewrites the input to what brainyard asks — `call directly as a tool: <tool>
for <args>`, or `… <tool>` alone — from `prompts/tool-request.edn`. It is a
nudge, not a forced call, and what was asked is the turn's question in
history. A tool that needs arguments and was given none stays on the command
path, which prints brainyard's `Usage:` and `Inputs:` lines instead. Anything
else starting with `/` is an `:error` emit, not a turn.

**A command that stands for an agent** (`/init`, byj-gly.350) is a **turn**
too, not a direct call: the agent the session runs is asked to do what the
command means by calling the command's subagent as a tool, as often as the
work needs, reading each report before the next call — so it can fit the
work to the conversation it is in. Which commands those are is data:
`resources/brainyard/commands/index.edn` names them and each
`commands/<name>.edn` gives its `:command`, the `:agent` it stands for, its
`:purpose`, the `:usage` the session's agent follows across iterations, a
`:default-request` for the command alone, a `:template`, and `:rephrase`
(`:predictor` or `:template`). `command?` lets such a line through when its
agent's tool (`agent$<name>`) is registered; when it is not — subagent calls
off — the command path says the command needs it, and nothing runs. The agent
turn's first leaf, `:session/command-request`, writes the turn's input: the
node's predictor, `session/rephrase-command` (`:predict`; the line, the
request — the words after the command, or its `:default-request` — the
purpose, the usage, the subagent's tool with its description and inputs, and
the history block), when the entry says `:predictor`, the session has turns
and `:command-rephrase?` (true) is on; otherwise, and whenever the predictor
fails or says nothing, the entry's template. The person is told what their
agent is asked (`/init → …`, the predictor's `shown` or the request's first
line); the line as typed is `:command-input`, which the transcript keeps as
what the person typed, `:session.turn/start` carries as `:command`, and the
turn's trajectory record keeps as `:command`. What the command's subagent
suggests the person type next is theirs, as it was from the direct call: kept
beside the turn (`:command-suggestion`, never in the model's tool result) and
offered at the turn's end over the model's own (`agent/turn-result`).

A command may also make one call itself, ahead of its turn (`:before`,
`byj-gly.468`): a vector of `{:when REGEX :call TOOL :args MAP}`, tried in
order against the command's words; the first that finds them has its
`:call` — one of the tools the command stands for — called with `:args`,
each `$n` in them the match's group n, as the person's own call, through the
gates (`session/command-before`, from `:session/command-request`, before the
rephrasing). The turn's question then ends with `defaults.edn`'s
`:before-note`: the call, its arguments and what it answered, and that the
step is done. It is for a step that is the command's and not a judgment —
`/emacs coach GOAL` and `coach stop` set and clear the coach stance this way
(RFC-067), which a model once skipped while answering as a coach. A `:when`
that does not compile, a `:call` outside the command's `:tools`, or `:args`
that is not a map is a problem of the file.
brainyard calls its init-agent directly (divergence
`slash-commands-become-turns`). `/<tool>` keeps brainyard's wording and
`/loop` its template. Seven are bundled besides `/share` (below): `/init` (RFC-040); `/claude`
(`commands/claude.edn`, byj-gly.361) — `/claude WORDS` has the session's
agent brief `agent$claude`, judge each answer with its own tools, and send
what is missing to the same instance with `agent-registry$ask`, so one Claude
Code session does the follow-ups (RFC-039), a fresh `agent$claude` only once
the instance is gone and then naming the session its report gave; it stops
when the work is done, the report asks the person, or a follow-up made no
progress, and a bare `/claude` says its usage; `/plan` and
`/edit` (`commands/plan.edn`, `commands/edit.edn`, byj-gly.360) — `/plan`
hands `agent$plan` the goal and what the conversation established and deals
with its `Saved plan:`, `Need:` and `Refused:` answers without starting the
plan, `/plan` alone planning the work the conversation is about; `/edit
WORDS` hands `agent$edit` one file at a time, each after the last succeeded,
and reports each `Rollback:` line, writing nothing itself, a bare `/edit`
saying its usage; and `/review` (`commands/review.edn`, byj-gly.354) —
alone, the uncommitted changes; with words, the commit, branch, range or
files named. `/review`'s usage has the
session's agent ask `agent$explore` to read the change with git (explore's
shell runs git since byj-gly.355) and check it against its callers, tests
and the project's conventions with `file:line` citations, then write the
review, problems first, changing nothing. `/team WORDS` (`commands/team.edn`)
stands for the `team$*` tools (RFC-032): the session's agent checks
`team$status` first, then plans the split before anyone starts, or does a
single chain of steps itself. It spawns only the members the plan needs, puts
self-contained parts on the board, waits once on the dispatcher, verifies each
answer, merges and closes; a bare `/team` says its usage. `/handover WORDS`
(`commands/handover.edn`, byj-gly.386) also stands for the `session$*`
tools. The session's agent finds the session that takes the work
(`session$list`, `session$status`), or starts a new one in this window with
`session$start` (RFC-031). With neither named nor startable, it asks the
person which. It then writes a brief that stands on its own — the goal, what
is done with exact files and results, the decisions, what is open, and how to
tell it is finished — sends it, and does no more of that work itself. A bare
`/handover` says its usage. A command another program installs among the person's own (below) is
listed and run the same way; byj ships none of another program's.
`/codex WORDS`
(`commands/codex.edn`, byj-gly.430.6) is `/claude` for Codex: the session's
agent briefs `agent$codex` (RFC-049), judges each answer and sends the
follow-ups to the same instance, so one Codex session does them; a bare
`/codex` says its usage. brainyard has no `/review`.

A project, or the person, defines its own such commands (byj-gly.351):
`.brainyard/commands/<name>.edn` in the project, over
`~/.brainyard/commands/`, over the bundled ones — as agents are found
(`session/command-roots`, `:command-roots` replacing them). An entry needs
only `:command` (a slash and a lowercase name), its `:purpose`, and what it
stands for (byj-gly.362.1): the `:agent` it drives, the `:tools` it is done
with — names or globs, `["share$*"]` — or neither, a prompt command that
the session's agent does with its own tools (`command-tool-globs`).
`:usage`, `:template` and `:rephrase` come from `commands/defaults.edn` when
it says none — `:usage` for an agent, `:tools-usage` for tools,
`:prompt-usage` for a prompt — `{agent-tool}` naming `agent$<agent>` and
`{tools}` the names given. The rephrasing predictor reads the tools the
command stands for (`tools-text`), each with what it does and takes, or that
there are none. A command whose tools match nothing registered is no turn,
and says which it needs (`command-tools`, `command-file-error`). A command
defined twice keeps the most local — a project's `/init` replaces the
bundled one — and the one hidden is reported; a file that is not a command
(an `:agent` that names none, `:tools` that are not a vector of names, both
at once, a session command's name such as `/help`, unreadable EDN) is left
out and said under `/help`. Without
`:default-request`, the command alone has nothing to ask: its usage is said
and no turn runs. The table is re-read at every line (`agent-commands`), so a
file added mid-session runs at once; `/help` lists the project's commands
with what each stands for (`commands-for`), and a front end is handed them
for completion when the session starts.

**A built-in that hands words to a file** (byj-gly.362.1). A session command
in `yielding-builtins` keeps every line its predicate takes, and hands any
other to a command file of its name, which makes it a turn as above; a file
may have that name though it is a session command's. `/share` is the one:
bare, and its verbs with what each takes (`start` references and flags,
`stop`, `pause` and `resume` at most one reference, `kick` one user, `allow`
and `refuse` nothing — `share-takes?`), run at once as before; any other
line — `/share start my claude session`, `/share make a watch link` — goes
to the bundled `commands/share.edn` (byj-gly.362.3), whose usage has the
session's agent read `share$status`, find a reference with `share$ls`, start
only what the words ask with `share$start` and report its links (RFC-043). When those tools are not registered (`:share-tools? false`), the
line says so and runs no turn.

`:<tool> …` calls a tool **directly**, without the model (byj-gly.273.3) — its
own path in the tree, `:session/direct-call?` then `:session/direct-call`, ahead
of the commands. The arguments are brainyard's kwargs (`:grep :pattern foo :path
"src dir"`, quotes keep spaces, a key bare or `:`-prefixed, each value coerced
by the argument's type) or an EDN map (`:grep {:pattern "foo"}`), and are
checked against the schema first: a bad one is brainyard's `Invalid args for
:<tool>` with every input it takes. The call goes through `tools/call-tool` with
the session's ctx — the permission gates, the argument check and the hooks see
it as they see a model's call (divergence `direct-calls-are-gated`). It runs
between turns; a line typed during one waits in the queue. The result is shown
whole — `:present :tool-call`, drawn as a model's call is, or printed — and
kept in the transcript as a tool entry. The next turns are told brainyard's
console activity: `<line> → <result>` (`✗` when it failed), cut at
`:console-activity-result-chars` (200), the newest
`:console-activity-max-entries` (10), a repeat of the newest skipped, under the
heading `prompts/console-activity.edn` gives, at the end of the history block
(`set-turns`); `:enable-console-activity` (true) turns it off, `/clear` empties
it. A `:` not followed by a name (`:)`) is a question.

Before it waits for a line, `read-input` hands a front end the tool rows
(`:present :tool-rows`, `toolshelf/rows`) for completing tool and argument
names — when the registry changed since it last did, since an MCP server's
tools arrive after its start (byj-gly.273.4).

**`/config` is brainyard's `/config` and `/feature` in one.** With nothing it
lists the families (`config/families`), how many of each family's switches are
on and how many of its settings differ from stock — `/feature`'s first level.
With a family it lists that family's settings, a switch as `●`/`○`; with a key,
that key's value, default, source, family, doc and whether it may be written.
The rows are `configbox/rows`, the same ones `config$read` returns, and a
secret-shaped value is masked. A front end with an editor is handed the rows
instead of the text, through `:present` (RFC-027): the full-screen renderer's
is a picker (RFC-028).

`/config KEY VALUE` and `/config unset KEY` write the project's config.edn
through `configbox/set-key!`, as `config$set` and `config$unset` do: typed by
the key's schema, snapshot first, read back, reverted if it did not land. It
asks as nobody in particular, so a permission key is refused here as it is to
the agent: RFC-012 lets a person grant one at the gate's prompt and nowhere
else. It needs no `:config-write?`, which only keeps writing in a person's
hands. A write is put in force at once, by the reload a turn's start does
(RFC-013), which says what changed; when a flag or the environment still
outranks the file, the command says so. An open editor is handed the new rows.

**`/status` is Claude Code's, from what the port knows.** One aligned block:
the session's name (its recap's title), id and kind (full-screen or inline,
resumed or not), cwd and project; the model, where its credentials come from —
the variable a provider reads and whether it is set, or the login it uses, and
never the credential itself (`credential-source`) — the permission mode, the
output style, the MCP servers running and stopped, and which config layers set
anything; then the turns, calls and tokens, and the jolt it runs on. brainyard's
`/status` is the agent's state, which is the turn and call counts here; there
is no account, organisation or version line, because the port has none.

**`/model` is brainyard's.** With nothing it says what this session is asking,
and a front end with a picker is handed the curated models
(`llm/curated-models`, `resources/brainyard/models.edn` — brainyard's
`get-popular-models`) with the one in use. `/model list` numbers them, `/model
N` switches to the Nth, and `/model NAME` takes `provider/model`, or a bare id
the list knows (`/model sonnet` is `claude-code/sonnet`, `/model gemma4:12b`
is ollama's).

**`:output-style` sets how much every viewer shows, from the next event.**
It is a config key, not a command — Claude Code's output style, with the three
of its styles that are a question of what is SHOWN here: `default` (the answer
and its tool calls), `concise` (the answer alone, no tool block) and
`explanatory` (each event's payload too). The ctx carries a `:display-box`
atom, as it carries `:lm-box`; `main` sets it from the style
(`config/display-level`, `:verbose` under `-v`), an edit applied at a turn's
start or by `/config output-style …` resets it, and the inline tracer and the
full-screen renderer read it per event. A problem is shown at every level
(RFC-026). brainyard's `/display-format` is gone (divergence
`output-style-is-a-config-key`). brainyard's
other commands are beads or live elsewhere: `/queue` is the full-screen
renderer's own command, since the queue is its state (RFC-028); and
`/allow-path`, `/sandbox` and `/pause` (`byj-gly.145`).

**`/compact [KEEP]` makes room in the session's history.** The turns before
the last KEEP (`:full-depth` by default — the ones the history already shows
in full) become one turn, `(turns 1–N, compacted)`, whose answer is their
summary, written by the predictor the command's node names
(`:compact-predictor "session/compact"` in `loops/session.edn`). The history
the next turn reads is rendered again and the session saved; a summary already
there is folded into the next. Fewer than two turns to fold, or a call that
fails, leaves the history as it was and says so. brainyard's `/compact
[ratio]` compacts toward a target ratio across the whole context; a turn's
own iterations are RFC-014's tiers here, so this is the session's turns alone
(divergence `compact-keeps-recent-turns`).

The last four are **doors onto what the kernel already does** (`byj-gly.107`):
`/task` reads RFC-019's registry the model reads through `task$*`, `/todo`
renders RFC-023's checklist, `/mcp` names the servers RFC-024 connected at
startup, and `/model` says what this session is asking — and switches it.

**A model switch takes effect on the next call**, without restarting the
session: the ctx carries an `:lm-box` atom, `agent/current-lm` reads it in
place of the ambient LM, and a node that pins its own `:lm` still wins
(RFC-005's order). A front end that does not supply the box gets a session that
cannot switch and says so, rather than one that silently does not. The switch
is kept with the session — its label in the snapshot, written at once, as
brainyard's `save-meta!` keeps `:model` and `:provider` — and a resume writes
it back into the box, so the resumed session asks the model it was asking, and
the footer's rule says so (RFC-016, RFC-028). A label that no longer resolves
is said, and the run's own model is kept.

**`/usage`** prints the running totals and then a table of the last `N` calls
(10 by default) — what those totals are a sum of, which is the question a
person has when a turn cost more than they expected. `--breakdown` adds, per
call, where the prompt's tokens went by zone and what the cache did with them
(divergence `usage-breakdown-by-zone`). The records come from the turn: each
predictor call appends one to `:usage-calls` (RFC-003), `end-turn` folds the
turn's into the session's, and both are windowed at `agent/max-usage-calls`.

**`/capture`** writes what was said to a file — the session's own transcript,
recorded as it emits, so it reads the same under any front end (divergence
`capture-is-the-sessions-transcript`). With no path it names one under
`.brainyard/captures/`.

They are **data**: `session/commands` is `[[command doc] …]`, `help-text` is
rendered from it, and a front end completes from the same list (RFC-028's input
line does). A command the help forgot is a command nobody finds, and two lists
drift the moment one of them is edited.

## Invariants

| invariant | enforced by |
|---|---|
| Turn 1 has no history zone; turn N sees turns 1..N-1; each turn's inner loop starts from fresh memory. | `session-test/multi-turn-history-reaches-the-nested-loop` |
| A question with `@here` as a word is told what the person is looking at in a running editor — file, cursor, unsaved or not, the selection as it is, and in an org file the heading and src block — or, with none, to ask, and the person sees why; a question without it never asks the editor. | `session-test/a-question-that-says-at-here-is-told-what-the-person-is-looking-at`, `session-test/at-here-is-a-word-of-its-own`, `main-test/completions-name-what-exists`, `emacs-test/at-here-says-where-in-an-org-file` |
| While a session coaches, every turn's user-context says the goal and how to coach; once stopped, none does. | `session-test/a-coached-session-tells-every-turn-how-to-coach` |
| Recent turns carry their tool trail into history. | `session-test/tool-trail-is-kept-for-recent-turns` |
| Commands never reach the model; `/quit` stops reading input; `/clear` empties history. | `session-test/commands` |
| A command that throws says so and is not put to the model: the tree's fallback would otherwise run it as a turn (provenance P-23). | `session-test/a-command-that-throws-is-not-put-to-the-model` |
| `/usage` tables the calls its totals are made of, and refuses an argument that is not a window rather than guessing. | `session-test/usage-tables-the-calls-the-totals-are-made-of`, `session-test/parse-usage-args-is-brainyards` |
| `/capture` writes the conversation, questions included, and names its own file when nobody says. | `session-test/capture-writes-what-was-said`, `session-test/capture-picks-its-own-file-when-nobody-says` |
| `/task`, `/todo` and `/mcp` read the kernel's own state and say plainly when there is none. | `session-test/commands-front-what-the-kernel-already-does` |
| `:<tool> …` runs the tool without the model, its args kwargs or an EDN map coerced and checked first, through `call-tool` — a deny rule refuses it and ask mode asks; the result is handed to the front end whole and kept in the transcript; the next turn is told the line and its result, a repeat once; `:)` is a question. | `session-test/a-colon-tool-is-called-directly-and-gated`, `toolshelf-test/*` |
| `/<tool> args` is a turn asking the model to call it, in brainyard's words; with a required argument missing, the usage and no turn; a built-in command keeps its name. | `session-test/a-slash-tool-asks-the-model-to-call-it` |
| A command that stands for an agent is a turn: with no turns yet its question is the command's template — the words, what it is for, how to drive its subagent — the person is told what their agent is asked, the transcript keeps the line typed and the turn's start names it; the agent calls the subagent in the turn, and may again before it answers; the subagent's suggestion reaches the turn's end and not the model. | `session-test/init-is-a-turn-that-drives-agent-init`, `session-test/init-turn-can-call-agent-init-again` |
| With turns behind it, the command's question is `session/rephrase-command`'s, written from them and the tools the command stands for; a predictor that fails, or `:command-rephrase?` off, gives the template. | `session-test/init-after-other-turns-is-rephrased-for-them` |
| A command whose subagent's tool is not registered is not a turn, and says why. | `session-test/init-without-subagents-says-why` |
| `/plan` and `/edit` are bundled and drive `agent$plan` and `agent$edit`: `/plan` alone, the work the conversation is about, its answers handled; `/edit WORDS` one file a call with the rollback lines reported; a bare `/edit` says its usage and runs no turn; both in `/help`. | `session-test/plan-and-edit-drive-their-agents` |
| `/handover` is bundled and stands for the `session$*` tools: `/handover WORDS` finds or starts the session that takes the work, sends it a brief that stands on its own and leaves the work alone; a bare `/handover` says its usage and runs no turn; listed in `/help`. | `session-test/handover-passes-the-work-on-with-a-brief` |
| `/team` is bundled and stands for the `team$*` tools: `/team WORDS` is a turn that checks `team$status`, plans before spawning, assigns self-contained parts, waits once, verifies, merges and closes; a bare `/team` says its usage and runs no turn; listed in `/help`. | `session-test/team-splits-the-work-across-a-team` |
| `/codex` is bundled and drives `agent$codex` as `/claude` drives `agent$claude`: `/codex WORDS` briefs it, judges each answer and sends the follow-ups to the same instance; a bare `/codex` says its usage and runs no turn; listed in `/help`. | `session-test/codex-drives-one-codex-session` |
| `/claude` is bundled and drives `agent$claude`: `/claude WORDS` briefs it, judges each answer and sends the follow-ups to the same instance with `agent-registry$ask`, never a fresh dispatch for the same work; a bare `/claude` says its usage and runs no turn; listed in `/help`. | `session-test/claude-drives-one-claude-code-session` |
| `/review` is bundled and drives `agent$explore`: alone, the uncommitted changes read with git; with words, what they name; nothing changed; listed in `/help`. | `session-test/review-is-a-turn-that-drives-agent-explore` |
| A command file may stand for `:tools` (names or globs) or for none, a prompt: a turn whose question carries the words, the purpose and that kind's default usage; one whose tools match nothing registered says which it needs and runs no turn; `/help` names what each stands for; `:agent` with `:tools`, or `:tools` not a vector of names, is no command. | `session-test/a-command-stands-for-tools-or-for-none`, `session-test/a-project-command-replaces-a-bundled-one-and-bad-files-are-reported` |
| `/share` runs its verbs itself and hands any other line to `commands/share.edn`, a turn with the `share$*` tools whose usage starts from `share$status`; bare `/share` is the status, as before. | `session-test/share-hands-words-it-cannot-run-to-its-command-file` |
| A project's `.brainyard/commands/<name>.edn` with `:command`, `:agent` and `:purpose` is a command that stands for that agent, filled from `defaults.edn`: a turn whose question carries the words, the purpose and how to drive the agent, listed in `/help` and given for completion. | `session-test/a-project-defines-its-own-command-for-an-agent` |
| A command's `:before` makes its call ahead of the turn whatever the model then does — the first entry whose regex finds the words, `$n` filled from its groups — and the turn's question says what it answered; words no entry finds make no call; a bad `:when`, a `:call` outside its `:tools` or a `:before` that is not a vector of maps is a problem of the file. | `session-test/a-command-makes-its-own-call-before-its-turn`, `session-test/a-commands-before-is-checked-with-its-file` |
| A command with no words and no `:default-request` is not a turn, and says its usage. | `session-test/a-project-command-without-words-or-a-request-says-how-to-use-it` |
| The most local entry for a command wins, the hidden one reported; a file with no agent, a session command's name or unreadable EDN is left out and said under `/help`. | `session-test/a-project-command-replaces-a-bundled-one-and-bad-files-are-reported` |
| What the command's subagent installs still asks the person, though the session's model made the call. | `session-test/an-install-init-is-asked-for-in-its-turn-still-asks-the-person` |
| `/tools` hands a picker every tool, with the filter or the tool named; without one it lists what a filter matches or prints one tool's spec. | `session-test/tools-lists-every-tool-and-shows-one`, `toolshelf-test/*` |
| `/status` names the session, the model and where its credentials come from (never the credential), the settings and their sources, and what was spent, in one column. | `session-test/status-says-what-this-session-is` |
| `/compact` folds the turns before the last KEEP (`:full-depth` by default) into one summary turn, the kept ones unsent; a later one folds the earlier summary in; nothing to fold says so. | `session-test/compact-summarizes-the-earlier-turns` |
| `:output-style` names a display level (`-v` outranks it), takes only its three styles, and an edit resets every viewer's level from the next command or turn; `/config` lists the families, one family, or one key in full, and masks a secret-shaped value. | `session-test/output-style-sets-how-much-is-shown`, `session-test/config-is-a-command` |
| `/config` and `/model` hand a front end with `:present` their rows instead of the text; a `/config` write is in force at once and the open editor gets the new rows; `/model N` and a bare curated id switch. | `session-test/the-config-editor-and-the-model-picker-are-handed-their-rows` |
| `/config KEY VALUE` writes the project file typed by the schema, refuses a value the key cannot take, and refuses a permission key. | `session-test/config-key-value-writes-the-project-file` |
| A session that runs out of `:max-turns` says so when it closes; one that was ended does not. | `session-test/a-session-at-its-turn-limit-says-so` |
| `/model` switches the model for later calls and refuses a label naming no provider. | `session-test/model-switches-for-the-next-turn` |
| The transcript and the per-call records are windows, not ledgers. | the transcript, `:transcript-max-lines` (10000): `session-test/the-transcript-keeps-its-configured-window`; the call records, `agent/max-usage-calls`: nothing asserts that bound |
| Every command is in `session/commands`, and the help text is that list rendered. | `session/help-text` is built from `commands`, so it cannot omit one |
| A long turn ends with a recap that reads the history and the turn just ended; the recap is counted as a call and leaves the answers alone. | `session-test/a-long-turn-ends-with-a-recap` |
| A short turn, or `:recap? false`, gets no recap, and a recap that fails costs nothing but itself. | `session-test/a-recap-is-earned-and-never-costs-a-turn` |
| Every `:recap-every-nth-turns`th turn is recapped however short, and a long turn whatever its number. | `session-test/short-turns-are-recapped-every-nth-turn` |
| `:session.turn/end` carries how long the turn took and the local time it ended. | `session-test/a-long-turn-ends-with-a-recap` |
| A turn that fails — by LLM errors or by the inner tree failing — ends that turn, never the session. | `session-test/a-failed-turn-does-not-end-the-session` |
| A cancelled turn is recorded with `:on-cancel` and the session continues without waiting out the call. | `cancellation-test/a-cancelled-turn-does-not-end-the-session` |
| Ctrl-C reaches the handler while the main thread waits on an effect. | Not a test: measured by sending SIGINT to a running `jolt` process (RFC-009 measurements). |

## Known gaps

- Persistence and resume are RFC-016: a snapshot per session under
  `.brainyard/sessions/`, `-r <id>` / `--resume-latest` / `--sessions`, and
  startup pruning. Not ported: `/clear` rotation, batched history demotion.
- History is demoted by a fixed full-depth, not brainyard's batched demotion (`append-turn` `:full-depth 10 :summary-depth 30 :demote-batch 10`), which keeps the history zone cache-stable.
- No full-screen TUI — `byj-gly.20`; no streaming output while a turn runs — `byj-gly.8`.
- Delegation is RFC-018: `agent$<name>` runs a child turn whose context is its
  own. Not ported: instances a turn can ask again (`byj-gly.38`).
