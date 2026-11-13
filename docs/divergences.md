# Divergences from brainyard

brainyard (`../brainyard`) is the reference implementation, and this port
differs from it on purpose in the places below. This is the record of how and
why, written once, here, instead of in comments that drift.
`docs/divergences.edn` holds the same entries as data, and
`test/brainyard/divergences_test.clj` compares the two files each time the suite
runs. An entry in the data has an anchor here and an anchor here has an entry
in the data; each entry names the test holding its behaviour in place, or the
reason no test can. The idea comes from samizdat's `docs/divergences.md`.

A divergence that was never written down is invisible to that test; finding
one takes brainyard
as an oracle: `dev/brainyard/dev/replay.clj` for what a model is sent, and `by
ask` beside `byj ask` for behaviour.

A difference that exists only because the port is incomplete is **not** a
divergence — it is a bead under `byj-gly` (RFC-008). An entry here is a
difference someone decided to keep, or one that stands until its bead closes.

## React loop

<!-- divergence: parallel-tools-off -->
**Tool batches run in order.** brainyard `pmap`s the tool calls of one
iteration. Models emit ordered batches — write, then update, then read the same
file — and in a live run the read raced the write and returned stale content
(provenance P-2). `:parallel-tools? false` is the default; `true` restores
`pmap`.

<!-- divergence: tool-only-signature -->
**The signature is tool-only.** brainyard's default agent is CoAct, whose
ThinkActCode carries `code-blocks` and the `context-briefing` and
`recalled-memory` inputs. This port is brainyard's `react-agent`: outputs
`tool-calls answer goal-achieved next-user-prompt`, inputs `question
iterations`. `recalled-memory` comes back with memory (`byj-gly.19`).
`code-blocks` and `context-briefing` (the sandbox's briefing) do not: the CoAct
code channel is not ported. What it buys — composing tool calls in one
iteration — is Tool Lisp's, reached through the tool channel (user tools, and
one-off bodies with `tool$run`, `byj-gly.11`), with no second output channel,
no sandbox and no `code-eval` events. The code channel's guidance is replaced
too: the instructions say to discover tools with `list-tools` and
`get-tool-info` where brainyard's say `(search "keyword")`, code-channel syntax
here, and a `### Composing Tools` section, present only when `tool$run` is
bound, says when a composite beats a call per step (`byj-gly.242`).

<!-- divergence: instructions-pre-rendered -->
**Instructions are a rendered resource, not a template.** brainyard renders
`coact-instructions-template` with selmer per channel combination.
`resources/brainyard/prompts/react-instructions.md` is the tool-only render,
adapted where the original names tools this port lacks (`task$run`).

## Session loop

<!-- divergence: history-fixed-depth -->
**History demotes by a fixed depth.** brainyard's `append-turn` demotes turns
from full to summary to minimal in batches of 10, so the history zone stays
byte-stable between demotions for prompt caching. This port keeps the newest
`:full-depth` (3) turns' tool trail and Q/A for the rest, re-rendered every
turn. Revisit with prompt caching (RFC-015).

<!-- divergence: capture-is-the-sessions-transcript -->
**`/capture` writes the session's transcript, not a renderer's scrollback.**
brainyard's `/capture <file-path>` dumps `layout/!scrollback`, which only its
full-screen renderer has — the same command under `--inline` would capture
nothing. Here the session records what it said as it says it, so `/capture`
writes the same conversation under the full-screen renderer, a pipe or a test,
and takes an optional path (defaulting to `.brainyard/captures/`) rather than
refusing without one. What it therefore does NOT hold is the event narration a
`-v` run prints: those go to the event stream (RFC-026) and the event log, not
through `:emit`.

<!-- divergence: usage-breakdown-by-zone -->
**`/usage --breakdown` breaks a call down by prompt zone.** brainyard splits a
call into system, signature and user-message tokens, which is how its prompt is
assembled. This port's prompt is assembled from ZONES in a declared order
(RFC-015), so the breakdown names those — `agent-core`, `session-context`,
`history-context`, `user-context` — with the estimate each contributed, and adds
the call's cache split (read, written, fresh), which is the other half of where
a prompt's tokens went. Counting brainyard's three parts here would mean
inventing a division the prompt does not have.

## Predictors and prompts

<!-- divergence: directive-without-code-blocks -->
**The structured-output directive names no code channel.** brainyard appends
the same directive to every claude-code call, including its sentence about
putting code in `code-blocks`. This port has no code channel, so that sentence
is dropped. "You have NO tools and NO filesystem access" also changed — see
`native-tool-calls-recovered`.

## Hooks

<!-- divergence: hooks-per-ctx -->
**The hook registry belongs to a ctx, not the process.** brainyard registers
hooks in one process-global atom, installed at namespace load and scoped by
`:match` predicates. Here `agent/make-ctx` builds a registry per ctx — built-in
specs, user hooks, and `:hooks` entries passed in — so two agents in one
process do not share hooks, and a test installs a hook by passing it.

<!-- divergence: user-hook-bodies-tool-lisp -->
**User hook bodies are Tool Lisp, not SCI.** brainyard evaluates a hook's
Clojure source in a sandbox with a 30 s timeout. Here the body is read,
validated against the tool registry and interpreted like a user tool's, under
Tool Lisp's step and tool-call budget instead of a timeout. A brainyard hook
using host interop (e.g. `java.time.LocalDateTime/now`) is skipped at load with
its problems.

<!-- divergence: user-hooks-may-gate -->
**User hooks may gate tool calls.** brainyard refuses a user hook on a gated
event — "user-defined hooks are observer-only in v1" — because its body is SCI
code with host access deciding what the agent may do. A Tool Lisp body is
interpreted, allowlisted and budgeted, so here a user hook on
`:agent.tool-use/pre` returns a decision (allow, replace, modify-args, block)
at its priority, below the built-in guards by default, failing open unless it
declares `:on-error :block`.

<!-- divergence: session-hook-payload-is-a-value -->
**A session event carries the session as a value, and `resumed` fires.**
brainyard's `:agent.session/created`, `/resumed` and `/closed` hand a handler
the live session atom and a `:user-id`. Here `:session` is a map of what the
session is at that moment — its id, project and working directories, turn
count, usage, `:resumed-from`, title — because a user hook's body is Tool Lisp,
which takes data, and the session's state belongs to its tree. There is no
user here, so no `:user-id`. brainyard catalogues `resumed` and never fires it;
the port has `--resume`, and fires it there instead of `created`. `closed`
fires however the session ends, not only when a store deletes it.

<!-- divergence: hook-commands-renamed -->
**The user-hook commands are `hook$*`.** brainyard names them
`hook-agent$events/validate/create/list/read/delete` and binds them only into
`hook-agent`; here they are `hook$*`, like `tool$*`, and bound into the one
agent (`:hook-authoring?`). `hook$list` lists built-in hooks too.

<!-- divergence: tool-blocked-terminated-by -->
**A blocked tool call ends the turn as `:tool-blocked-by-hook`.** brainyard's
block path sets the answer and `:terminated` but no `:terminated-by`, so the
turn records `:answer`, indistinguishable from a model's answer. Here it is
`:tool-blocked-by-hook`, with an `:agent.tools/blocked` event.

<!-- divergence: permission-by-effects -->
**The permission gate can key on effects, not just names.** brainyard's gate
lists tool *names* (`:tool-approval-patterns`, `:tool-allow-tools`,
`:tool-deny-tools`). Here those work as they do there, and
`:tool-ask-effects` / `:tool-deny-effects` additionally match a tool's declared
`:effects` (RFC-010) — so `#{:process}` covers `bash` and any user tool that
reaches it, without naming either. This is what `:effects` were declared for.

<!-- divergence: path-match-by-segment -->
**A path is inside a directory by segment, not by string prefix.** brainyard's
`resolve-allowed-path` tests `starts-with?` on canonical paths, so an allowed
`/tmp` also admits `/tmpfoo`; its TUI-side `within-allowed-dir?` is
segment-aware, so the two gates disagree. Here one `under?` is used
everywhere: canonical, and `dir` itself or `dir/` prefixed.

<!-- divergence: write-paths-use-allowed-dirs -->
**Reads and writes are checked against the same `:allowed-dirs`.** brainyard
checks paths inside each tool, and `validate-write-path` allows writes only
under `/tmp`, the system temp dir and `.brainyard/` — its own `:allowed-dirs`
does not apply to writes, so every write into the project prompts. Here a
single hook (`path-permission-gate`, priority 90) checks both against
`:allowed-dirs`, whose default includes the working directory, and covers the
calls a user tool makes as well.

<!-- divergence: logins-and-keys-are-denied-paths -->
**A person's logins and keys are read by no path tool, in any mode.**
brainyard refuses no path inside `:allowed-dirs`, whose default takes
`~/.brainyard` — where the share sidecar keeps its owner's login
(`~/.brainyard/share/credentials`, RFC-043). A shared agent with nothing but its
files to read could be asked by a caller, or told by a file it reads, to
read that login, and a headless share asks no one (byj-1wv.115.40). Here `:denied-paths` — the sidecar's login, Claude Code's and Codex's credentials and
settings, `~/.ssh`, `~/.aws`, `~/.netrc`, gh's hosts, Docker's config — are
refused by the path gate before `:allowed-dirs` is read, in every mode, as is
a directory holding one, which a recursive read would open. A shell's
arguments are no paths the gate sees: an agent with a shell reads anywhere,
and says so (byj-1wv.115.39).

<!-- divergence: permission-prompt-cli -->
**The permission prompt is the session's own reader, with no timeout.**
brainyard prompts through its TUI and denies after 30 s. Here `byj run`
asks on the terminal it already reads from (`y`/`n`/`a`/`d`, remembered for the
session under the matching pattern) and waits: Ctrl-C cancels the turn, which
the port has and brainyard's prompt path does not. The request also carries
`:args` and `:effects`, which brainyard omits — a human judging `bash` needs
the command.

<!-- divergence: sandbox-self-exec -->
**The sandbox resolves its own command from `BY_SANDBOX_SELF` or PATH.**
brainyard asks native-image for the current executable and falls back to
`which by`. jolt has no equivalent API, so `--sandbox` uses `BY_SANDBOX_SELF`
(whitespace-split) or `byj` on PATH, and with neither it says so and runs
unsandboxed instead of failing.

<!-- divergence: bedrock-via-cli -->
**Bedrock goes through the aws CLI.** brainyard signs SigV4 and posts to the
Bedrock endpoint itself. jolt has no SigV4 implementation, and the CLI already
resolves profiles, SSO, regions and credential refresh, so the provider spawns
`aws bedrock-runtime` as a subprocess — the same shape the claude-code provider
already uses. The cost is a process per call and a dependency on the CLI being
installed; the benefit is that every credential arrangement the user already has
keeps working. It also picks the transport: `invoke-model` passes the body
through unvalidated, which is how `cache_control` reaches the model, where the
CLI's Converse schema would reject it.

## Configuration

<!-- divergence: config-files-merge -->
**Both config files are read, project over user.** brainyard reads one
`config.edn` — the project's if it exists, else the user's — so a project file
replaces the machine's wholesale. Here `~/.brainyard/config.edn` is the floor
and `<working-dir>/.brainyard/config.edn` is merged over it, key by key, so
setting one key in a project does not drop the rest. `:mcp-servers` merges one
level deeper, server by server and each spec's keys over the one below
(`config/merge-layer`, byj-gly.220). So a project that adds a server, or
disables a user one (`{"github" {:enabled false}}`), writes only that, and the
user file's servers and their tokens are never copied into a committed project
file.

<!-- divergence: config-file-never-throws -->
**A bad config file is reported, not thrown.** brainyard's `read-edn-config`
slurps and reads unguarded, so malformed EDN takes the process down. Here an
unreadable file, a schema key at the top level, an unknown key and a value of
the wrong type are problems on the result — the CLI prints them — and the run
carries on with the layers it could read.

<!-- divergence: config-env-from-type -->
**Environment variables are derived, not declared.** brainyard gives each key
its own `:env-fn`, hand-written with its own coercion, and a key without one
has no environment layer. Here every schema key reads `BY_` + its
SCREAMING_SNAKE name (a trailing `?` dropped) and is coerced by its declared
type — the convention brainyard follows by hand, made mechanical.

<!-- divergence: other-programs-declare-config-keys -->
**A key another program declares is not unknown.** brainyard reports every
key outside its schema; byj leaves alone those a program lists in
`~/.brainyard/config-keys/<program>.edn` — neither read nor reported — since a program built on byj may keep its own keys in the same
`[:agent :config]`, and byj names none of them (RFC-070).

## Providers

<!-- divergence: claude-cli-takes-its-settings-credential -->
**`claude -p` is given the credential kept in Claude Code's settings.**
brainyard runs it with `--setting-sources ""`, as this port does, so that
none of the person's settings — hooks, permissions, a model — reaches the
call; but then a key kept in `~/.claude/settings.json`'s `env` reaches it
neither, and Claude Code is not signed in. A program that runs Claude Code for a person may keep their API key
there, and a machine may be signed in that way, so the provider reads that `env` and
passes `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `CLAUDE_CODE_OAUTH_TOKEN`
and `ANTHROPIC_BASE_URL` alone as the call's environment, what the
environment already says winning; nothing else of the settings
(`byj-1wv.115.20`).

<!-- divergence: native-tool-calls-recovered -->
**Tools a model calls natively are recovered.** Behind `claude -p`, haiku
sometimes calls a prompt-listed tool as a native `tool_use`, gets "No such tool
available", and answers that the tool does not exist — quoting brainyard's
directive sentence "You have NO tools" as confirmation. brainyard takes that
answer. Here the provider reports the rejected calls
(`llm/rejected-tool-calls`), the ReAct loop runs them when the reply has none
(`react/recover-native-tool-calls`), and the directive says the listed tools
exist and run through `tool-calls` (provenance P-12). The lookups the model
makes for a tool it was told is missing are not replayed beside the call
itself, and arguments it wrapped as one JSON string are unwrapped
(byj-gly.352), with the tool's name beside them too (byj-gly.395).

<!-- divergence: usage-in-is-total -->
**`:usage :in` is the TOTAL input, not the fresh count.** brainyard records
Anthropic's `input_tokens` as `:in`, which already excludes cached and
newly-written tokens, so its metrics read `read / (read + in)`. This port sums
all three counts into `:input-tokens` (RFC-006) because pricing needs the whole
figure, which makes `:in` mean something else — and a metric ported across that
change without noticing counts every cache write as a fresh token. It did, for
as long as nobody ran a live turn (`byj-gly.94`). Anything reading `:in` takes
its buckets out first: `fresh = max(0, in − cache-read − cache-write)`.

<!-- divergence: no-streaming -->
**No streaming.** Every provider call waits for the whole reply; brainyard
streams whenever an `on-chunk` handler is present (`byj-gly.8`).

<!-- divergence: wire-headers-redacted -->
**A recorded call keeps its headers, minus the credentials.** brainyard logs no
headers at all (`clj-llm/core/llm.clj`, where `headers` is bound and passed to
`http/post` but never reaches a mulog event), which keeps `x-api-key` and
`Authorization` out of the log and takes `anthropic-version` and the beta
headers with them. Those decide how a provider answers, so a logged call is
then replayable in body and URL only and the rest must be reconstructed by
hand. Here the credential headers are replaced by name and everything else is
kept.

## Effects

<!-- divergence: effect-cancelled-includes-interrupt -->
**`cancelled?` also accepts `InterruptedException`.** brainyard's checks only
`missionary.Cancelled`. On jolt, cancelling a `via blk` interrupts its thread
and the waiter receives whatever the interrupted call threw — an
`InterruptedException` — never Cancelled (RFC-009 rule 2), so a cancelled
blocking call would otherwise read as a failure. Retries rethrow either.

<!-- divergence: effect-brick-scope -->
**The brick is smaller, and reports through a var.** `from-future` is not
ported (jolt futures are replaced by tasks, not adopted), nor `sample-lines`
(no consumer) nor the native-image `smoke-*` gate (`jolt build` needs none —
RFC-009). Failures nobody awaits go to `brainyard.effect/*on-failure*`
(stderr by default) instead of mulog. Added: `process-task`, because a jolt
subprocess outlives its interrupted waiter.

<!-- divergence: supervisor-registers-before-running -->
**`start!` registers before it runs, and deregisters only its own entry.**
brainyard runs the task, then registers it, and deregisters by label. A task
that settles synchronously therefore deregisters before it is registered and
stays listed as running forever — measured against ebb with brainyard's logic.
The port registers first under a unique id and removes only that id, which
also keeps a replaced incumbent's late callback from removing its replacement
(not reproduced on ebb; guarded anyway).

<!-- divergence: turn-cancel-is-structural -->
**A turn is cancelled by its canceller alone.** brainyard's `cancel-run` uses
four mechanisms — a `:cancelled?` flag checked at every node, `.close` on the
active HTTP stream, the effect canceller, a thread interrupt — plus waking a
paused turn, because it still supports a synchronous BT engine and a
non-effect stream reader. Here the tree only runs as a Task, so cancelling it
is one call: the interrupted leaf's thread, the HTTP read's 250 ms slice and
`process-task`'s `destroy-tree` cover what the other mechanisms did. There is
no pause/resume, and no per-node cancel flag.

## User tools

<!-- divergence: user-tool-bodies-interpreted -->
**User-tool bodies are interpreted Tool Lisp, not SCI-evaluated Clojure.**
brainyard evaluates a body's source in an SCI sandbox (`install-body!`) at
definition and on every session start. jolt has no SCI, and host `eval` of
agent-written code is the wrong privilege, so a body here is read with
`*read-eval*` false, validated against an allowlist, and interpreted (RFC-010).
A body using what SCI allowed and Tool Lisp does not — interop, `def`, `loop`,
atoms — is skipped at load with its problems listed.

<!-- divergence: user-tools-no-recursion -->
**A user tool may not reach itself.** brainyard binds each user tool as a
symbol other bodies call, so mutual recursion is possible. Here the call graph
is checked at definition and at load, and a cycle is refused with its path —
which, with no `loop`/`recur`, is what makes every body terminate.

<!-- divergence: tool-lisp-reach -->
**A body's parallel calls are bounded, and its state is a tool.** brainyard
evaluates a body in its SCI code sandbox, where `pmap` fans out without a bound
and an `atom` keeps state. Tool Lisp's tier D (`:reach`, off by default) gives
`pmap` over `fx/bounded` — `:parallel` at a time, at most 1000 items, every call
on the body's own step and tool-call budget, cancelled with its turn — and no
atoms: state between calls is `store$get`/`store$put`/`store$keys`, a project's
`.brainyard/store.edn`, whose effects the gates and tool distillation see.

<!-- divergence: user-tool-output-schema-declared -->
**A user tool declares its result as a malli schema.** brainyard's user tools
take no output schema; each registers as `[:map]`. Here `tool$create` takes
`output-schema`, and a distilled tool comes with one: a malli schema, persisted
as the form, listed as the line written from it and checked by `tool$test`.
Prose is refused where a tool is made, because nothing can check it — a
distilled `{:projects [{:issues …}]} or {:error-message string}` missed the
`{:project :error}` its own body returned (byj-gly.263). A tool on disk from
before, with prose, still loads.

<!-- divergence: link-open-lifts-brainyard-limits -->
**A clicked link opens a little further than brainyard's does.** The design is
brainyard's `links.clj` — detected at click time, a file must exist, a wrapped
target recovered from its entry and never by joining rows, a plain click. Three
of its limits are lifted: a file opens in `$VISUAL` before `$EDITOR` (brainyard
never reads `$VISUAL`); the column goes to an editor whose convention has one
(`--goto path:line:col` for VS Code and its forks, `path:line:col` for Sublime
and Helix — brainyard parsed it and dropped it); and a URL's second row, which
has no scheme and reads as a path, opens the one URL in the entry that holds it,
where brainyard left it inert (byj-gly.270).

<!-- divergence: agent-page-lists-claude-code-sessions -->
**The agent page lists the project's Claude Code sessions too, and runs them in
tmux.** brainyard's session list holds its own sessions. Here a Claude Code group
follows them — live ones read from `~/.claude/sessions`, saved ones from
`~/.claude/projects` — and Enter starts, resumes or opens one in a detached tmux
session of its own, `ctrl-b d` returning to the page (RFC-033, byj-gly.287).

<!-- divergence: channel-requests-are-questions -->
**A request over the session channel is a question, never a command.**
brainyard's `:ask` op enqueues the question on the session's input queue,
where a slash command is taken as typed. Here a request that would be a
command or a `:<tool>` direct call is refused with why — commands are the
person's to type — because one could add an MCP server, start a process or
change settings from another session (RFC-031, byj-gly.282).

<!-- divergence: auto-mode-classifier -->
**`:auto` asks a classifier before it asks the person.** brainyard's `:auto` is
`:auto-approve` in a container and `:ask-each-time` outside one. Here, outside a
container, a gate that would ask runs the `permission/classify` predictor first:
allow runs the call, block refuses it with a reason, unsure asks as before. It
sees the question and the call, never a tool's output (RFC-012, byj-gly.285).
`:auto-mode-classifier? false` restores brainyard's behaviour.

<!-- divergence: allow-path-is-the-persons -->
**`/allow-path` is the person's, and lasts the session.** brainyard's adds a
directory to the agent's `allowed-dirs` whoever sent the line. Here a line the
session channel handed over (another session, a team leader) is refused, and
the directory is never written to `config.edn` — the gate's prompt, answered
`r`, stays the one route by which a permission key persists (RFC-012,
byj-gly.145).

<!-- divergence: no-sandbox-eval-command -->
**No `/sandbox`.** brainyard's `/sandbox eval CODE` and `/sandbox FN ARGS`
evaluate Clojure in its SCI code sandbox. The port has no SCI; `:tool$run
{:body "…"}` and `:<tool>` direct calls do that work here, gated (RFC-012,
byj-gly.145).

<!-- divergence: no-pause-command -->
**No `/pause`, and no `/continue`.** brainyard parks a running tree at its next
node boundary and releases it with `/continue`, which also re-asks a question
that ran out of iterations. Not built: Ctrl-C cancels, ask-each-time stops
before each tool call, and a turn that waits parks on a task (RFC-019,
byj-gly.145).

<!-- divergence: direct-calls-are-gated -->
**A tool called directly is gated like a model's call.** brainyard's colon
command (`:grep :pattern foo`) calls `agent/invoke-tool`, which it documents as
a bare dispatcher: no permission gate, no hooks — the one way to run a tool
that nothing checks. Here `:<tool> …` goes through `tools/call-tool` with the
session's ctx, so `:tool-deny-tools` refuses it, ask mode asks, the path and
command gates look at its arguments, and a user hook sees it (byj-gly.273.3,
decided with the user). It also takes an EDN map for arguments kwargs cannot
say. The syntax, the idle rule and the console-activity digest are brainyard's.

<!-- divergence: tools-are-browsed -->
**Tools are browsed in a picker, each one's spec beside it.** brainyard has no
browser: a tool is found by completing its name after `/` or `:`, or through
the `list-tools` and `get-tool-info` a model calls. `/tools` lists every tool
by family with a filter, and the marked one's arguments, result, effects, what
the permission gates would do with it, and how to call it both ways — through
the model or directly (byj-gly.273.1).

<!-- divergence: byj-updates-itself -->
**byj updates itself.** brainyard has no update command: its
`bin/install.sh` is run again, and finds the latest through the public
`releases/latest` with no credentials. The port's source repo is private, but
each release is copied to the public tap (RFC-029, byj-gly.181.10), so
`byj update` reads the tap's `releases/latest` as brainyard's install does,
with no token (one is sent when there is one, and a private `:update-source`
needs it); downloads its platform's asset and `SHA256SUMS`, checks the one
against the other, keeps itself as `.prev` and swaps by one rename;
`--rollback` swaps back; a checkout checks the tag out instead
(byj-gly.181.3).

<!-- divergence: tool-commands-renamed -->
**The user-tool commands are `tool$*`, and `tool$test` exists.** brainyard names
them `tool-agent$create/list/read/delete/validate`; here they are
`tool$create/list/read/delete/validate` — shorter, and not suggesting a
subagent. brainyard's model tests a user tool by calling it
from a code block; with no code channel here, `tool$test`
runs a tool on sample args. The authoring eval's models used it to find and fix
wrong bodies before answering.

## Tools and config

<!-- divergence: tool-args-coerced-leniently -->
**Every tool's arguments also get the port's four coercions, and results are
checked at run time.** A tool declares its arguments as brainyard does, a malli
`:input-schema` [:map …], and they are decoded with brainyard's
`llm-args-transformer` rules and validated (RFC-030, `brainyard.schema`). Before
that, each top-level argument gets four coercions of the port's own, each a
rule a model taught it: a non-string printed where a string is wanted, a lone
string wrapped where an array is, an object parsed from JSON text, and a whole
float taken as an integer. An argument whose schema is an `:or`, and anything
nested, decode as brainyard's do. A result is checked against the tool's
`:output-schema` at run time: a mismatch is reported as
`:tools/output-mismatch`, and the result is returned all the same. brainyard
checks `:output-schema` only in its tests. The shared registry is passed to
malli explicitly rather than installed as its default.

<!-- divergence: search-walks-all-files -->
**`search` scans every file under the working directory except `.git`.**
brainyard lists project files with the glob `**/*`, which Java's matcher applies
only to paths containing a `/` — top-level files are never found — and which
walks into `.git`, whose objects then fill the 500-file scan. Here top-level
files are searched, `.git` is skipped, and the walk is sorted, so results are
stable.

<!-- divergence: project-root-git-walk -->
**A worktree's `.git` file marks a project root too.** brainyard walks from the
working directory to the nearest `.git` **directory** (`BY_PROJECT_DIR`
overrides), so a git worktree — whose `.git` is a file pointing elsewhere —
walks past its own root into the parent. Here either form marks the root, and
the rest matches brainyard: `.brainyard/` (tools, hooks, predictors, programs,
config.edn) and the default `:allowed-dirs` all follow the project, not the
directory the agent happened to start in.

<!-- divergence: search-skips-agent-state -->
**`grep` and `search` skip `.brainyard/`.** brainyard excludes nothing, so an
agent searching its own project matches its own tools and hooks — in the Tool
Lisp authoring eval a user tool counted 6 TODO lines in 4 files where the
project had 3 in 2, because its own persisted body matched. Here both skip that
directory unless the search is aimed inside it (`grep` with `path`
`.brainyard/…`). `list-dir` still shows it: it is a real entry, and hiding it
would mislead.

## Subagents

<!-- divergence: agent-tool-ids -->
**An agent is dispatched as `agent$<name>`.** brainyard registers a `defagent`
under its bare symbol (`explore-agent`, `plan-agent`) and a user-defined one as
`user$agent$<name>`, so the roster has no common prefix. Discovery here groups
tools by the segment before the first `$` (RFC-007), which would scatter the
roster across `_ungrouped` and `user`; one `agent$` family keeps `list-tools`
readable and lets a permission glob name the whole roster at once
(`agent$*`).

<!-- divergence: subagent-cycle-guard -->
**The cycle guard records the agent being dispatched.** brainyard pushes the
*caller's instance id* (`:explore-agent/lime-mole-8966`) onto `*call-chain*`
but tests membership with the *target's registry id* (`:explore-agent`), so the
two never compare equal and `a → b → a` is not detected — only a chain that
happens to hold a bare defagent keyword is. Here the chain holds the name being
dispatched, so a cycle is refused at the hop that closes it. The depth limit
bounds the damage either way, which is presumably why it went unnoticed.

<!-- divergence: subagent-turn-budget -->
**A session may spend only `:subagent-max-turns` (32) child turns.** brainyard
has no such bound: `:max-subagents-per-session` caps how many subagents are
*alive* and evicts the least recently asked to make room, so a loop that
dispatches, evicts and dispatches again is stopped only by the iteration
ceiling. This port keeps that cap with brainyard's meaning and adds a counter
over child turns — dispatches and follow-up asks alike, since both are a whole
turn of a model's time and bounding only the first would bound nothing. The
counter is created once per session and shared by the whole tree beneath it.

<!-- divergence: subagent-instances-are-data -->
**A live subagent instance is data, not an agent object.** brainyard's registry
holds the agent itself — its sandbox, memory manager, behavior tree and
`!state` — and `close` has to unwind all of it, which is also why a snapshot of
one can embed an unprintable `#object[…]`. Here an instance is its spec, its
previous turns and a few counters; the `ctx` for a follow-up ask is rebuilt from
the same config, which costs a roster and tool-registry re-read — what every
turn pays anyway. Closing is `dissoc`, and an instance could be written to a
session snapshot as it stands (RFC-016).

<!-- divergence: subagent-guards-in-config -->
**Depth and the call chain travel in config, not in dynamic vars.** brainyard
binds `proto/*call-depth*` and `proto/*call-chain*` around a dispatch. Bindings
here are fiber-local and conveyed at run time (RFC-009), so a guard read from a
var would depend on which thread ticked the node; `:agent-depth` and
`:agent-chain` are set on the child's config, which is the same value every
node of that turn sees.

<!-- divergence: skill-dispatch-synthesizes-an-agent -->
**A `dispatch: agent` skill runs as an agent built from the skill itself.**
brainyard hands such a skill to its registered `skill-agent` — a `defagent`
with its own instruction and tool palette — passing the SKILL.md body as
`:agent-context`, and fails with `skill-agent not registered` when that
namespace was never loaded. Here an agent is a spec (RFC-018), so the spec is
built from the skill: brainyard's own wrapper prose (`skill-system-prompt`)
plus the body as the instruction, the caller's palette for tools, and nothing
left behind afterwards. With `:enable-subagent-calls false` the call falls back
to handing over the procedure with a note, rather than failing: the procedure is
still the thing the caller wanted.

<!-- divergence: authored-agents-are-prose-only -->
**A model-authored agent may carry prose, and nothing else.** brainyard's
`meta-agent$create` takes `:instruction` and `:tool-context` and says plainly
that you never bind tools — a user agent is a persona over the inherited
palette. This port keeps that restriction and has a second reason for it: an
agent's `:tools` is an allow list its child inherits *instead of* its caller's
(RFC-018), so a model able to write one could hand a subagent a wider palette
than its own. `:config` and `:lm` are withheld too, since between them they
would let a turn pick its own model and its own permission mode. A person
editing `.brainyard/agents/<name>/agent.edn` may set all of them: the fence is
on what the model can write, not on what an agent can be. And a model's
redraft keeps what the person set: `persist!` replaces the prose and the
description and keeps every other key, so an agent whose tools were set by
something other than the model keeps them through a redraft.

<!-- divergence: authoring-off-by-default -->
**The `meta-agent$*` commands are off unless asked for.** brainyard registers
them with the rest of its command palette. Measured here, the roster, substrate
and instance commands cost the prompt 3,611 characters and the five authoring
commands a further 2,845 — five full tool specs and a page of guidance, for a
capability most sessions never use, since an agent worth keeping is usually
written once by a person and read from `.brainyard/agents/` thereafter.
`:agent-authoring? true` turns them on. Tool and hook authoring stay on
(RFC-010, RFC-011): writing a tool mid-turn is ordinary work, where minting a
new agent is a standing decision about how this project delegates.

## Background tasks

<!-- divergence: task-output-is-not-streamed -->
**A task's output is streamed to its record, but written to disk once.**
brainyard keeps a 500-line ring in memory, appends every line to `output.log` as
it arrives, and reports `:truncated?` when the tail has outrun the cache. Here
the lines reach the task's record as they are printed — `task$detail` shows them
mid-run and `:last-output-age-ms` is the liveness signal — while the file is
written when the task ends, capped at `:max-task-output-chars`. The live view is
the record; the file is the post-mortem.

This entry once claimed jolt could not stream a subprocess at all, citing both
lines of `echo one; sleep 1; echo two` arriving at 2,036 ms. That measurement
read through `clojure.java.io/reader`, which buffers; the raw `InputStream`
gives 22 ms and 1,029 ms (provenance P-14 is the sibling defect in the same
area). The claim was wrong and the streaming half is now ported.

<!-- divergence: agent-tier-in-the-spec -->
**An agent states its own work tier.** brainyard keeps `:agent-tier-map` in
config alone, keyed by defagent type — a table of about twenty entries for
specialists that are compiled into the image, so config is the only place a
person could reach them. Here the roster is data (RFC-018), so an agent declares
`:tier`, `:min-tier` and `:max-tier` in its own file, where whoever writes the
agent decides what its work costs. `:agent-tier-map` still exists and still wins,
for retuning an agent you cannot edit — a built-in, or one from a shared team
root.

<!-- divergence: skill-description-matches -->
**A skill whose description holds every word searched for is a match.**
brainyard's `find-skills` scores a hit in the name, tags or title, and a flat
6 for any word in the description, under the 10 a match needs, so a
description alone never matches. A Claude Code or agents skill has only a
name and a description, so it was never found by what it is for: on this
machine's 51 skills, 10 of 20 purpose queries found nothing — "spreadsheet"
not `xlsx`, "presentation slides" not `pptx`, "pull request" not
`create-github-pr`. Here a description in which every word of the query starts
one of its words scores 10; any other description hit is brainyard's 6. 18 of
the 20 queries then found something; the two that did not have no such skill
on this machine. Word starts, not substrings: substrings matched "word" in
"password" and "test" in "latest" (byj-gly.375).

<!-- divergence: skills-results-carry-their-tool-id -->
**A `skills$*` result names the tool id that calls each skill.** brainyard's
`skills$list` and `skills$find` entries give `:name :type :scope`, and a model
calls a skill by a tool id it has to put together itself. Here each entry also
carries its `:id` (`skill$lint-markdown`, or `skill$agents$x` for a shadowed
one) and, when shadowed, `:shadowed-by`. A model that found a skill had to
guess the id it is called by (byj-gly.200). The shapes are otherwise
brainyard's: `{:result :count}`, `:type`/`:scope` filters, `:also-in`, and
`skills$read`'s `:skill-name`.

<!-- divergence: project-scope-harness-skills -->
**A project's other harnesses' skills are read too.** brainyard looks in
`~/.claude/skills` and `~/.agents/skills` — its reasoning is that a skill
written for another harness is still a procedure — but only under `$HOME`. A
repository keeps those directories too, and the procedures in them are about
*that* repository: this one's `bd` workflow sits in `.claude/skills/beads/` and
`.agents/skills/beads/`, and the port could not see either. The same six roots
are now read, project scope before user scope, brainyard before claude before
agents. The cost is that a project's skills written for another agent are listed
for this one as well, at one line each.

<!-- divergence: bash-command-gate -->
**A command line is gated by what it runs.** brainyard checks a bash command's
absolute paths, and only for `task$run`'s bash job (`validate-bash-paths`); it
has no notion of which programs a command may run. Its containment is the
seatbelt sandbox, and an agent that should only run one CLI is held there by its
instruction. This port adds `:bash-allow-commands`, `:bash-deny-commands` and
`:bash-ask-commands` (a destructive command asked about inside the project too,
byj-gly.285.4),
checked by a gate at priority 92 over every tool that declares
`:command-params`, so `task$run` cannot be a way around it. The path half is
brainyard's, regex and all, applied to every such tool rather than to `task$run`
alone. It is not a sandbox and does not claim to be: it refuses what it cannot
segment — substitution, backquotes, a quoted separator — because a gate that
guessed would be a gate that could be walked past.

<!-- divergence: compact-tool-listing -->
**The bound tools are listed one line each.** brainyard renders the
`### Agent Tools` block compactly for CoAct (`:compact-agent-tools`, default
true) and verbosely for ReAct, whose docstring says why: "its roster is its
menu". A CoAct agent has a sandbox function directory to look a tool up in; a
ReAct agent has only the block. This port is the ReAct agent and compacts
anyway, because it does have somewhere to look — `get-tool-info` is on by
default, and the block falls back to full specs when it is not. Measured: the
block was 56% of the system prompt, and the change takes the prompt from 32,737
to 23,163 characters. brainyard's compact form prints the bare id; this one
keeps the description's first line and the declared result shape, because
without a function directory an id alone does not distinguish `write-file` from
`update-file`, and because omitting results cost two wrong answers in the Tool
Lisp authoring eval (RFC-010). Past the primitives it goes further than
brainyard does at all: every other family is one line — its purpose, its ids —
so a connected MCP server costs a line rather than sixty (`:tool-listing-by-family?`).

## Memory

<!-- divergence: memory-is-an-external-command -->
**Memory is a command, not a subsystem.** brainyard's is SQLite with FTS5 and
sqlite-vec, three layers, a bi-temporal context graph with communities, a
procedural graph, an audit table, six migrations and a capture sidecar. This
port names three commands in config — recall, read, store — and shells out,
defaulting to `bd memories` / `bd recall` / `bd remember` because a project
using beads already has a populated store. The port therefore has no tiers, no
consolidation and no ranking: whatever structure exists is the store's. A store
whose program is missing leaves the tools unregistered rather than failing when
one is called.

<!-- divergence: memory-is-never-injected -->
**A memory's contents are a pull; only its key is pushed, and only when it
matches.** brainyard renders a `## Recalled Memory` block into every turn's
user message — up to ten hits of 600 characters — and offers a
`:recall-mode "conditional"` keyword gate to claw back some of what that costs.
Here the contents are never pushed: `memory$read` and `memory$recall` are tools
the model calls. What a turn is told is the key and first line of the few
memories its question shares rare words with — none when nothing matches —
in the volatile tail of the prompt (RFC-042, `byj-gly.335`). Measured, a
weaker model that never pulled read the right memory in one call once told
its key, and a stronger one that pulled anyway was unchanged.

<!-- divergence: search-has-no-memory-section -->
**`search` has no memory section.** brainyard's `search` includes one, and
RFC-007 recorded its absence here as a gap. It stays absent, for a reason found
by building it: brainyard's store returns a countable list of hits, so an empty
one is simply no section, while an external store returns text. Asked for
something it does not have, `bd` prints `No memories matching …` — a good answer
for an explicit recall, and noise on every single search. There is no general
way to tell a store's polite miss from a hit, so `search` does not guess.

<!-- divergence: no-http-streaming -->
**Only the CLI provider streams.** brainyard streams every HTTP provider over
SSE (`clj-llm/core/{sse,sse_flow,http_flow}.clj`) and the claude CLI through
`stream-json`. Here the HTTP half is not available, and not for the reason first
published: brainyard's own client is reachable. `sse_publisher.clj` streams on
`java.net.http`, and jolt provides enough of it to send a request and read a
body — but `BodyHandlers/ofPublisher`, the one method the SSE path subscribes
to, is not implemented, and the `ofInputStream` route that is delivers the whole
body at once. Against a server writing three chunks 600 ms apart
(`dev/brainyard/dev/http_stream_spike.clj`): `jolt.http-client {:as :stream}`
buffered to 1,811 ms, `java.net.http` + `ofInputStream` to 1,812 ms, and a raw
`java.net.Socket` streamed at 3 / 607 / 1,209 ms. So the only route that does
arrive incrementally is a hand-written socket — and `javax.net.ssl.SSLSocketFactory`
is unimplemented, so it cannot reach an `https://` provider, which is all of
them. A subprocess streams, which is why the claude-code provider can. Reaching
the HTTP providers would mean running `curl` and moving auth, headers, timeouts
and retries out of the client into a command line; that trade has not been
made.

<!-- divergence: evaluation-passes-params-explicitly -->
**An evaluation hands each call its params, rather than binding them.**
brainyard's `evaluate-pass` submits one task per example to a thread pool and
wraps each in `bound-fn`, because the candidate's params and the trace both
travel in dynamic vars (`*params*`, `*trace*`) — a worker that lost them would
score the wrong prompt and look merely disappointing. Here `predictor/run`
takes `:params` and `:on-event` as call arguments (RFC-005), so a program
carries its own trace back in its return value and a candidate's params are
passed to the call that uses them. Parallel evaluation therefore needs no
conveyance at all, and the class of bug `bound-fn` exists to prevent cannot
occur.

<!-- divergence: evaluation-does-not-re-retry -->
**A transient failure is not retried twice.** brainyard's evaluator classifies
errors and retries `:transient` up to `:max-transient-retries` with linear
backoff, because its call path does not retry. Here `llm/chat` already wraps
every call in `with-retry` (RFC-006), so a transient failure that reaches the
evaluator has exhausted its retries; retrying again would multiply the two
budgets (three by three) against a provider that already said no. It is counted
as an error and scored 0.

<!-- divergence: comparison-is-paired-over-examples -->
**Two programs are compared per example, not per pass.** brainyard's
`compare-scores` runs Welch's t over the two runs' per-pass means, because a
repeat is all it keeps: `evaluate` returns `:repeat-scores` and the aggregate,
and the individual example scores are discarded. That test asks whether two
sequences of pass means differ, which carries the between-example variance —
on a small set, one easy example and four hard ones spread the pass mean far
wider than any change to the program does. Worse, a deterministic student has
no pass spread at all, so `:stderr` is 0 on both sides and the margin collapses
to 0: the first real compile here crowned a proposal that was 0.0286 ahead,
one example out of 35, over a zero-shot baseline (`byj-gly.57`).

Both programs are evaluated on THE SAME examples, so the comparison is paired.
`evaluate` keeps `:example-scores`, `leaderboard-row-keys` carries them, and
`compare-scores` tests the per-example differences `d_i = a_i - b_i` against
the t margin on their own standard error — which removes the between-example
variance entirely. It falls back to brainyard's per-pass Welch when a row has
no per-example scores, and `:paired?` says which ran.

When the differences have no spread — every example moved by the same amount —
the t has nothing to divide by, and the question becomes how many examples
agreed: a two-sided sign test, which needs six. Neither path reports
`:tested? true` without something that weighed the direction.

<!-- divergence: spend-cap-is-not-dollars -->
**Evaluation is capped in calls and tokens, not dollars.** brainyard checks
`:budget-usd` against `[:usage :cost :total-cost]` from the trace. Nothing in
this port's provider layer prices a call — `llm/chat` returns
`{:input-tokens :output-tokens}` and no cost — and the provider most likely to
run an evaluation here is `claude-code`, a subscription with no per-call price
at all. `:max-calls` and `:max-tokens` are the cap instead: both are checked
before an example starts, so with `:parallel n` the overshoot is bounded by
n−1 examples, exactly as brainyard's is.

<!-- divergence: seeded-shuffle-is-fisher-yates -->
**The seeded shuffle is written out.** brainyard uses
`java.util.Collections/shuffle` with a `java.util.Random`; jolt provides
`Random` but not `Collections/shuffle`, so `optimize/shuffle-seeded` is the
Fisher-Yates walk that method specifies, over the same `Random`. Same
algorithm, same seed discipline, reproducible here — but a given seed does not
necessarily select the same demos as brainyard's would, which matters to
nothing, because a dataset and its proposals never leave the project they were
measured in.

<!-- divergence: set-f1-is-nameable -->
**`set-f1` can be named by a command.** brainyard's named metric registry holds
`exact-match`, `schema-valid` and `graph-extract-f1`; `set-f1`, `weighted` and
`all-of` are constructors reachable only from Clojure, because they take
arguments a command has nowhere to put. `graph-extract-f1` belongs to the
memory subsystem, which is not ported (RFC-020), which would leave a
model-facing metric set of exactly two — both all-or-nothing. `set-f1:<field>`
carries its argument in the name so a set-valued output is measurable at all,
and `set-f1:<field>:<key>` the key its items are compared by, so a tool-call
set can be scored by its names rather than its whole calls. `channel-choice`,
one bit — tool or answer — is not in brainyard's registry; it is the label a
silver dataset gets right whatever the teacher's wording (byj-gly.125).

<!-- divergence: edit-agent-has-no-transaction -->
**The edit specialist has the discipline, not the transaction.** brainyard's
`edit-agent` runs on `edit$apply`: one command that probes, applies, verifies,
persists an edit record and rolls back, atomically. That command is not ported.
Its instruction already carries the **inline** pipeline for exactly the case
where `edit$apply` is not bound — probe, apply once, verify, roll back — and
that path needs only tools this port has, so it is what the ported agent
follows. The match-count guard survives intact, because `update-file` takes
`:expect-count` and refuses without writing when the count is wrong. What is
lost is the record: brainyard leaves a parsed artifact on disk for a later agent
to read, and here the same facts go in the answer, which is what this port's
callers read anyway (`byj-gly.41`).

<!-- divergence: plan-agent-has-no-dossier -->
**The plan specialist writes a plan, not a dossier.** brainyard's `plan-agent`
emits a machine-readable dossier every turn — even when it refuses — whose
purpose is the handoff to `todo-agent` and `exec-agent`, which read it back with
`plan$read-dossier`. Neither of those agents exists here (`byj-gly.17`), and a
dossier with no reader is bookkeeping that costs a turn. The plan is a plain
markdown file under `.brainyard/plans/`, and the handoff — what passed, what is
on hold, what to do next — is in the answer. `doc$*` is not ported either, so
the body is written with `write-file` rather than through the polymorphic
document store (`byj-gly.41`).

<!-- divergence: refusal-rides-the-notice-channel -->
**A refused answer comes back as a notice, not as a synthetic iteration.**
brainyard's answer gate appends an `[evaluation]` record to `:iterations` — a
whole channel of its own, with `:rejected-answer`, `:verdict` and `:feedback`
fields, a case in the iteration formatter, an entry in the iteration schema the
model reads, and a rule in the compactor to drop it from the cross-turn
briefing. It does that because its user-context zone is built once per turn and
cached, so a critique written there would not re-render mid-loop.

The notice channel here has the same property and already exists: a failed tool
call queues a notice, `accumulate` folds it into the iteration record, and the
next prompt shows it. A refusal uses that, so the gate adds no channel, no
schema field, no formatter case and no compaction rule — and what the model sees
is the shape it already knows. What is lost is the rejected answer being carried
beside the critique; the critique names what was wrong with it instead.

<!-- divergence: mcp-reads-raw-bytes -->
**An MCP server is read a byte at a time, not a line at a time.** brainyard
wraps the child's stdout in a `BufferedReader` and calls `.readLine`, which is
right on the JVM. Here it never returns: a reader over a subprocess stream
delivers nothing until the process exits (RFC-006's measurement, the reason
`fx/streaming-process` exists), and an MCP server is long-lived by definition.
The first probe of this hung until it was killed. Reads split on the byte
`0x0A`, which cannot appear inside a UTF-8 sequence, and the charset is named
rather than inherited — brainyard names neither reader's nor writer's, and takes
the platform default.

<!-- divergence: mcp-results-are-flattened -->
**A tool's content blocks are flattened and `isError` is honoured.** MCP answers
with a vector of blocks — text, image, resource — and a flag saying whether the
call failed. brainyard passes the vector to the model untouched and reads the
flag nowhere in the repository, so a tool that reported failure arrives as a
success with its error text nested inside a map the model has to unpick. Here
text is joined, an image or resource becomes a short marker, and `isError`
produces `{:error-message …}` — the shape every other failing tool already
returns.

<!-- divergence: mcp-errors-carry-their-code -->
**A protocol error reaches the model with its code.** brainyard throws
`ex-info` carrying the JSON-RPC `:code`, `:message` and `:data` in `ex-data`,
then reports only `(ex-message e)` — a constant string — so the model is told
"MCP request failed" and never which of *method not found*, *invalid params* or
*internal error* it was. Here the message is `MCP error <code>: <message>`,
which is the difference between a model retrying sensibly and retrying blindly.

<!-- divergence: mcp-capabilities-shape -->
**`sampling` is sent inside `capabilities`.** The MCP specification nests it
there, and brainyard's HTTP path does; its stdio path sends it as a sibling.
That is a bug in one of its two transports rather than a decision, so the
specified shape is used. No test can show the difference — a server that ignores
an unknown top-level key behaves the same either way — which is exactly why it
is recorded here rather than left to be rediscovered.

<!-- divergence: mcp-answers-elicitation -->
**A server may ask the person mid-call.** byj declares MCP's `elicitation`
capability and answers a server's `elicitation/create` while it waits for a
call's reply — through the session's permission prompt, never the classifier
— and its `ping` too; brainyard's client skips whatever is not its reply, so a
server that asks waits forever. A server that publishes what it is given
asks the person so before it sends anything.

<!-- divergence: mcp-is-stdio-only -->
**Only the stdio transport is ported.** brainyard also speaks Streamable HTTP,
with RFC 9728 challenge discovery, device/paste/loopback OAuth, session ids and
refresh-and-retry — about 40% of its MCP code. None of it is here, and the cost
is smaller than it looks: a remote server is reachable by spawning
`npx -y mcp-remote <url>`, which is what brainyard's own `notion` and
`google-workspace` seeds already do. What is genuinely lost is a server that
must be reached over HTTP without that shim.

<!-- divergence: mcp-has-no-call-proxy -->
**There is one way to call an MCP tool.** brainyard binds each tool as
`mcp$<server>$<tool>` *and* offers `mcp$tools :op :call` to invoke them by name
in a batch, with its own argument normalisation accepting three shapes. The
bindings already are the tools, so the proxy is a second path to the same place
— and a second place for the permission gate to have to understand, which is why
brainyard's MCP gate has to parse a batch out of the tool call. `mcp$servers`
remains, for discovery.

<!-- divergence: trajectory-success-is-an-allowlist -->
**A trajectory record counts a turn as a success only for an ending that is
known to mean it.** brainyard names the failures — a turn succeeded if its
answer is non-blank and `terminated-by` is not one of
`:max-iterations-exhausted`, `:llm-error`, `:none-channel-loop-guard`. That is
the same judgement while the list of endings is complete, and the wrong default
the moment it is not. This port stops turns for more reasons than brainyard
classified: `:cancelled`, `:usage`, `:iteration-count`, `:loop-error` and
`:tool-blocked-by-hook`, none of which are in brainyard's set and every one of
which would therefore have scored as a success.

So `trajectory/succeeded-by` names the endings that mean the turn ANSWERED —
`#{:answer-channel}`, which is what `stamp-answer` stamps — and everything else
is a failure. An ending added later is then recorded as a failure nobody
decided on, which shows up in the data and asks to be looked at, rather than as
a success nobody decided on, which does not.

<!-- divergence: event-log-is-line-oriented -->
**The event log is one record per line, in the project, and smaller.** brainyard
writes pretty-printed EDN paragraphs to a global
`~/.brainyard/logs/agent-tui-app.log` (50 MiB, three backups) and reads them
back by splitting the file on blank lines. Three changes here, each for a
reason:

- **One `pr-str` map per line**, as the prediction and trajectory logs already
  are. A paragraph format cannot be read a line at a time and cannot survive a
  torn tail — a reader racing the writer gets a half-written paragraph and loses
  the whole file behind it, where a half-written line costs one record.
- **In the project** (`.brainyard/logs/events.log`), not the home directory.
  `.brainyard/` belongs to the project here (RFC-013), and a per-project log
  answers *what happened in this repository* without a global file interleaving
  every project the agent has ever run in. It is deliberately outside
  `sessions/`, which is pruned.
- **10 MiB, three backups**, against brainyard's 50 MiB. One line per event is
  far denser than a pretty-printed paragraph, and the file is now per project
  rather than shared, so the same history costs less.

<!-- divergence: config-never-writes-permissions -->
**A permission key is never written from inside a turn.** brainyard's allowlist
of writable config paths *includes* `[:permissions :mode]` and
`[:permissions :allowed-dirs]`; they are guarded by `sensitive-prefixes`, which
only means they cannot self-confirm under `--auto` — with a human at the
terminal they are writable like anything else.

Here **no tool** writes one. A config file outlives the session that wrote it,
so an agent that can widen its own permission gate widens it for every later
run, and the one thing a gate must not be is something the gated party can edit.
`:builtin-hooks?` goes with them, since turning it off uninstalls the gates.
`config$read` says plainly which keys those are and why, so the agent can
explain a change rather than make it.

The single exception is not a tool: the terminal prompt's `r` answer calls
`configbox/grant!` with `:by :person`, which accepts
`#{:tool-allow-tools :allowed-dirs :permission-mode}` and appends to the
allowlists (RFC-012). What is allowed is allowed because a human pressed a key,
and a test reads the command table to keep any command from claiming the same.

<!-- divergence: config-auto-scope-is-the-project -->
**`:auto` means the project, whenever there is one.** brainyard resolves `:auto`
two different ways — its reads take the project file when it *exists* and fall
back to the user's, while `write-edn-config!` takes the project *directory* when
it resolves. Its `config$apply` never notices, because it passes an
already-resolved concrete scope.

This port followed the read rule for writes at first, and the first write to a
project with no `config.edn` yet silently landed in `~/.brainyard/config.edn` —
changing a setting for every project on the machine, from inside one turn,
having been asked for `:auto`. It was found by it happening. The user's file is
now written only when named: a project write affects this repository, a user
write affects everything.

<!-- divergence: config-writes-are-atomic -->
**A config write is atomic.** brainyard `spit`s the pretty-printed map straight
over `config.edn`. A crash or a full disk mid-write leaves a truncated file,
which every later run reads as unparseable EDN and silently falls back to
defaults on — including the defaults for whatever the file had been carefully
set to. Here the map is written to a temp file beside it and renamed. Comments
are lost either way: the file is re-rendered from parsed EDN, in both.

<!-- divergence: compaction-summary-is-a-model-call -->
**The span a compaction collapses is summarized by a model, not printed.**
brainyard's `:collapse-iterations` keeps the last three iterations and replaces
the rest with one `"summary"` record whose thought is the older records
printed back (`summarize-iterations-deterministic` is `format-iterations-block`)
— no model call, and the "summary" is about as long as what it replaced. Here
the record has brainyard's shape, but the `react/compact` predictor writes it,
and it is taken only when shorter than the span. It runs after elision and
before dropping, and only when those are needed (RFC-014). On by default because
the A/B in RFC-014 measured it against dropping (`byj-gly.168`); a model call
brainyard never makes, which `:compaction-summary? false` turns off.

<!-- divergence: feature-is-part-of-config -->
**`/feature` is part of `/config`, and `/config` is an editor.** brainyard has
two commands over one store: `/config` prints all of the schema's keys
alphabetically — its own comment calls that "a storage dump, not a mental
model" — and `/feature` shows the same data grouped by capability, with
`/feature <family>/<name> on|off` to flip one. Here there is one: `/config`
lists the families and what is on in each, `/config FAMILY` one family, and
the full-screen renderer opens it as an editor grouped the same way, where
Enter flips a switch and edits anything else. So every key has a family
(brainyard's features cover some keys; its `/config` shows the rest ungrouped),
and a switch is flipped with the `/config KEY VALUE` that writes any key, one
write path rather than two.

<!-- divergence: permission-mode-cycles-with-shift-tab -->
**Shift+tab cycles the permission mode, and the footer shows it.** brainyard's
permission mode is whatever `config.edn` or `--permission-mode` said at the
start; changing it means editing the file or restarting. Here the full-screen
session shows the mode in force in the footer (`▸▸ auto mode on (shift+tab to
cycle)`) and shift+tab steps it — auto, auto-approve, ask-each-time — for the
next tool call on, mid-turn included. The choice lives in a box every gate
reads (`permission/mode`), and is the session's alone: nothing is written to
the config, and the model has no way to reach it (RFC-012's rule that a
permission key is set only by a person holds).

<!-- divergence: config-reload-reports-rather-than-applies -->
**An edited config is in force from the next turn, not the next read.**
brainyard keeps a cached `!global-config` atom and swaps it, so a reload puts
the new values in force immediately for everything that reads config through
the accessor — mid-turn included. This port has no such global: `agent/make-ctx`
builds the tool registry, prompt zones and hooks from the config map. So the
session reloads the layers at the start of every turn and every slash command
(`session/refresh-config!`), rebuilds what `make-ctx` built from the changed
keys, and lays it over the session ctx through the tree's `:ctx-box` (RFC-002).
A turn already running keeps the config it started with.

Keys read only while the session is being put together — the project and
session identity, the mouse and suggestion switches, the event log, pruning,
MCP servers, prompt caching, `:max-turns` (`config/next-session-keys`) — are
reported once, as applying from the next session, rather than half-applied.

`config$reload` keeps the diagnostic, which is the useful half — `:changed`
against `:shadowed`, the latter being keys edited in the file that a CLI flag or
an environment variable still outranks. That is the whole of *"I edited
config.edn and nothing happened"*.

<!-- divergence: auto-park-reuses-the-wakeup -->
**Auto-park parks through `task$wakeup`'s machinery, not beside it.** brainyard
runs a second watch mechanism for auto-notify — `:task/completed` hooks with
their own `!armed-tasks`, `!inbox`, `!resume-armed` and `!resume-counts` —
deliberately mirroring the `add-watch` machinery behind `task$wakeup`. Its own
docstring says it produces *"the same effect as the LLM having called
`task$wakeup`, but without its cooperation"*.

Here that is literally what it does: `tasks/park!` is shared, and auto-park
calls it. One list of wakeups, one delivery path (`ready-wakeup!`), one budget
(`:max-wakeups-per-session`), one refusal rule for a run that cannot resume.
Two mechanisms that must agree are two mechanisms that can stop agreeing, and
brainyard already carries the cost of that: the same cap key is read by both
sides into separate counters, so a session can spend twice its stated budget.

<!-- divergence: task-notify-arms-once-per-turn -->
**A turn arms one wakeup, not one per task.** brainyard arms a `:task/completed`
watch per task and coalesces them at the far end, where `maybe-resume!` injects
a single turn naming whatever is in the inbox. The coalescing is what keeps
three finished tasks from becoming three turns, and it depends on a CAS guard,
a per-session inbox and a `mark-surfaced!` call from the live loop to stop the
turn announcing what it already folded in.

This port arms once, at the end of a turn that leaves work running, naming the
tasks in the note. An injected turn is an LLM call nobody asked for, so the
bound is structural rather than a guard that has to hold: the first task to
finish wakes the session, and `harvest-tasks` folds in any others on the turn
that wakes — which is the path they would have taken anyway.



<!-- divergence: parent-trail-off-by-default -->
**A subagent sees nothing of its caller's conversation unless it asks.**
brainyard's `:parent-trail-k` defaults to 3, so every child of every dispatch
is shown the caller's last three turns. Here the default is **0**.

Most delegation is a question that stands on its own — "where is the retry
budget enforced?" does not need the three turns that led to it — and the
caller's conversation is a cost paid on *every* dispatch to serve the few
cases that do. brainyard's own budget policy says the same thing more quietly:
the trail is the section it sheds first under pressure, which is an admission
that it is usually not what the child needed.

**So the agent asks, not the caller.** brainyard resolves `k` on the calling
agent alone, which is coherent while the default is 3 and everyone gets a
trail; at 0 it would leave no way to turn the trail on for the one specialist
that needs it. `agents/trail-k` therefore takes the agent's own spec first
(`:parent-trail-k`, or the same key in its `:config`), then the caller's
config, then 0 — so a specialist can ask for three turns, a project can still
turn it on everywhere as brainyard has it, and an agent in such a project can
opt back out.

`:agent-context` remains what it was: the route for what a caller means to say
on purpose, rather than what it happens to have been saying.
\n

<!-- divergence: unknown-cost-is-not-free -->
**A cost nobody can state is absent, not zero.** brainyard's `calculate-cost`
returns `{:total-cost 0.0 …}` for a model it has no rates for, and logs
`::no-pricing-found` at debug level — effectively silent. It names the hazard
itself, in the docstring of the auditor it had to write afterwards: *"A model
catalogued but unpriced still runs — it just reports a cost of 0.0, which reads
as free rather than as unknown."*

So `cost/of` returns **nil** where there are no rates, a turn counts the calls
it could not price, and `trajectory.edn` omits `:cost` entirely rather than
recording `0.0`. A turn that was partly priced carries `:cost-partial? true`
beside its number, because an understated total that looks complete is worse
than one that admits what it is missing — analytics (`byj-gly.78`) would
otherwise average a real spend against a fabricated zero.

<!-- divergence: prices-are-a-resource -->
**The price table is a file, not code.** brainyard keeps `default-pricing` as a
Clojure literal in `clj-llm/core/usage.clj`, snapshotted into a `defonce` atom
and changeable at runtime only by calling `set-pricing!`. Correcting a price
there means editing source and rebuilding.

A price is not a fact about this program. It changes when a vendor changes it,
and RFC-001 puts precisely such a thing in `resources/` — so it is
`resources/brainyard/prices.edn`, and a project overrides or extends it with
its own `.brainyard/prices.edn`, merged per provider so one model can be
corrected without restating the rest. An unreadable override leaves the bundled
table standing: a typo in a price file should cost a wrong number at worst,
never a turn.

Nested `{provider {model rates}}` rather than brainyard's `[provider model]`
keys, because this one is a file people edit and a nested map diffs and reads
better than a hundred two-element vectors. Only the providers this port can
reach are priced, and `claude-code` is deliberately absent — the CLI reports
`total_cost_usd`, which is what it actually billed.

<!-- divergence: bedrock-global-and-long-context-prices -->
**A Bedrock `global.` endpoint has its own price, and a long prompt can have
its own tier.** brainyard's `normalize-bedrock-model-id` strips the region
prefix and prices every Bedrock id of a model alike, from one row with flat
rates. AWS bills Claude 4.5 and later 10% more through a regional endpoint
(an in-region id, or a `us.`/`eu.`/`apac.` profile) than through `global.`.
Claude Haiku 5.5 bills a call whose prompt is over 100,000 tokens at five
times its rates. So `prices.edn` keeps a `global.` row beside the regional
one, found first for a `global.` id, and a row may carry a `:long-context`
tier applied per call (`cost/rates-at`). The rows were checked against AWS's
price list on 2026-10-09 (`byj-ewq`).

<!-- divergence: schedule-fires-into-the-session -->
**A scheduled prompt is a turn of the session that made it.** brainyard's
`schedule.clj` runs a due job on a daemon ticker thread, as a separate agent
run (`invoke-tool` of its agent), and writes the answer to a file under the
job. The ticker is off unless `:enable-scheduler` is set. This port keeps
brainyard's tool names, store and cron, but fires a job into its own session
(`:session`) as if the person had typed it, between turns. That is Claude
Code's `/loop` model, which `/loop` ports. The front end's idle poll is the
ticker, so there is no thread and no flag. A job runs only while its session
is open, and a recurring one expires after seven days, as Claude Code's do.
brainyard's watches (`watch$add`) are `schedule$watch` here, and fire the
same way: a turn of the session, as Claude Code's Monitor wakes its agent,
not `:emit` on a hooks bus (`byj-gly.331`). Their first look is the baseline
for `:changed`; brainyard fired on it. The event bus and reactions are not
ported (`byj-gly.329`).

<!-- divergence: models-ahead-of-brainyard -->
**`/model` lists Claude models brainyard's catalogue does not yet.**
`models.edn` is brainyard's curated `model-catalog`, in its rank order. That
catalogue stops at Opus 5, Sonnet 5, Haiku 4.5, Fable 5 and Mythos 5. The
port adds their successors, Opus 5.5, Sonnet 5.5, Haiku 5.5, Fable 5.1 and
Mythos 5.1, each just above the model it succeeds, on Anthropic and on
Bedrock. Their prices are in `prices.edn` (`byj-ewq`). A model priced but not
listed could not be chosen by its bare id, and one listed but unpriced would
report its cost as unknown (`byj-gly.323`).

<!-- divergence: answer-markdown-is-laid-out -->
**An answer's markdown is laid out, not only coloured.** brainyard's
`render.clj` `markdown-line` styles one line at a time: headings lose their
`#`, list items get a cyan bullet, a fence dims, and `*italic*` is ANSI italic.
The full-screen renderer here does the same and goes further, because it owns
its wrapping (RFC-028 rule 6). A wrapped list item hangs under its text, a
quote keeps its bar on every row, `---` is a rule, a link keeps its address
beside it, and a pipe table is laid out in aligned columns that wrap inside
themselves. brainyard leaves a table as the bars the model typed, which reads
only when nothing wraps.

Emphasis is drawn **underlined**, not italic: glimmer-tui paints bold, dim,
underline and reverse, and has no italic attribute to send. Underline is how
`man` and `less` show emphasis, and it beats leaving the asterisks in.

<!-- divergence: a-turn-is-a-column-of-bullets -->
**A turn is a column of bullets, with no header per iteration.** brainyard's
`render.clj` opens every iteration with `iteration-header`, a bold
`[+] Iteration N / M` line, and draws the calls under it. Here a call is
`⏺ name(key=value, …)` — every argument, named, as brainyard's
`format-tool-calls` shows every one as an EDN map; a value is cut past 200
characters and at its first line — with the first lines of its output under a `⎿`, and the
answer is one more `⏺` with its markdown indented beside it. Nothing numbers
the iterations. The calls already say what the turn did, and a numbered rule
above each group of them sits between a person and that. The iteration count
is still on every event, in the trajectory, and in `/usage`.

<!-- divergence: the-input-is-framed-not-under-a-status-bar -->
**The input sits between two rules, and the status bar is three rows.**
brainyard reserves the bottom rows for one status bar and the prompt
(`docs/tui/renderer.md`, `layout/format-status`). The bar carries a status
word (`idle` / `running` / `paused`), the queue, tasks, calls, tokens and cost.
Here those fields go where they are read:

- a running turn is a yellow spinner line above the input, with how long it
  has been going and what it has written;
- the usage (tokens and cost) is right-aligned just above the input;
- the input is `❯` between two rules;
- the turn, calls, tasks and queue are a footer under the input, with the keys.

There is no `idle` word: an idle session is one with no spinner line. A line
you submitted is echoed as `❯` too, so what you typed looks the same in the
transcript as it did in the field.

<!-- divergence: a-long-turn-ends-with-a-recap -->
**A long turn ends with a recap.** brainyard has no recap: a turn ends with
its answer and the status bar. Here, when a turn has run at least
`:recap-after-ms` (60 s by default), the session tree runs the
`session/recap` predictor on the history and the turn that just ended. It says
in two sentences where the work stands and what is next, and a front end shows
it under the answer. It is for whoever looked away while a long turn ran,
which a short turn does not need. It costs one call and is counted as one, and
`:recap? false` turns it off. brainyard's own "recap" is something else:
`coact-agent`'s deterministic summary of old iterations, for the model rather
than the person.

<!-- divergence: a-resume-replays-the-kernels-transcript -->
**A resume replays what the session said, not what the terminal showed.**
brainyard tees its rendered scrollback to a file (`persist_bridge.clj`
`tee-scrollback!`) and prints its tail on resume, ANSI and all. Here the
snapshot keeps the kernel's own transcript (what `/capture` writes, RFC-004)
and a resumed front end renders it again. So a resumed conversation looks the
way this renderer draws it now, at this terminal's width, rather than as bytes
laid out for whatever width the earlier terminal had. And the inline and
full-screen front ends share one record. Each turn's tool calls are in it too,
as small structured entries, so they are drawn again between the question and
the answer.

<!-- divergence: list-tools-summarises -->
**`list-tools` with no arguments is a summary, not the index.** brainyard's
answers with every tool and its description, grouped by family — 213 tools
once MCP servers are connected, and 44,616 characters for this port's 122
without any. Here it names each family, how many tools it has and at most six
of their ids, and says how to narrow: `family` lists one family with
descriptions (a new argument; an MCP server is a family of its own,
`mcp$linear`, where brainyard lumps every server under `mcp`), `pattern` finds
tools by what they do, and `get-tool-info` gives the parameters — 2,123
characters for the same 122. A flat listing carries each description's first
line; `get-tool-info` has the rest.

<!-- divergence: mcp-registry-search -->
**`/mcp find` searches the MCP Registry too.** brainyard has no search: its
servers are its seed set and whatever a person writes into `config.edn`. Here
`/mcp find WORDS` also asks `registry.modelcontextprotocol.io` (or the
`:mcp-registry-url` mirror), turns each server into a command this port can
start — or says why not — and `/mcp add` takes one, disabled, with its source
and version recorded: it is third-party code, and runs only once a person
enables it. `:mcp-registry? false` turns the search off.

<!-- divergence: mcp-catalogue-is-separate -->
**The server catalogue is offered, not merged into the config.** brainyard
deep-merges its seed servers under `config.edn` at startup, so `/mcp` lists
thirteen disabled placeholders — including `"your-token-here"` values and
`/opt/homebrew/bin/uvx` paths — and enabling one writes `{:enabled true}` over
the seed. Here `/mcp` lists what the project configured, and the catalogue is a
separate resource that `/mcp find` searches and `/mcp add` copies from, with
`${VAR}` in place of a placeholder token and `:needs` saying which variable
must be set. brainyard's HTTP+OAuth seeds (`github2`, `notion2`, native
`linear`, `api-server`) are left out or reached through `mcp-remote`, since
the port speaks stdio only; the MCP reference servers are added. And `/mcp` can
add and remove servers, which brainyard's cannot.

<!-- divergence: mcp-command-manages-the-set -->
**`/mcp` manages the run's servers and their tools, not only their
connections.** brainyard's `/mcp NAME start|stop|auth|status` connects and
disconnects, and `:enabled` is persisted by the agent's `mcp$lifecycle`. Here
`/mcp` is the person's: `start|stop|restart` for the run (restart is how a dead
server comes back), `enable|disable` written into the project config and
applied at once, `tools`, and `hide|show` of a tool or a whole server through
`:hidden-tools`. There is no `auth` (stdio only), and `:mcp-servers` edits
apply at the next turn rather than the next session. In the full-screen
renderer it is a picker, servers grouped with their tools.

<!-- divergence: compact-keeps-recent-turns -->
**`/compact` takes how many turns to keep, and compacts only the session's
turns.** brainyard's `/compact [ratio]` compacts previous turns, the agent's
context and the iterations toward a target ratio (`compact-context!`, 0.05–0.9).
Here a turn's iterations are compacted by RFC-014's tiers as the turn runs, and
the context zones are rebuilt every turn, so what grows between turns is the
history: `/compact [KEEP]` keeps the last KEEP turns whole (`:full-depth` by
default) and has a predictor write the rest as one summary turn. A number of
turns is something a person can check; a ratio of an estimated token count is
not.

<!-- divergence: output-style-is-a-config-key -->
**How much is shown is a config key, `:output-style`, not a command.**
brainyard's `/display-format quiet|normal|verbose|debug` sets it for the
session. Here it is Claude Code's shape: `:output-style` in `config.edn` (and in
`/config`'s editor, where Enter steps through its values), with the three of
Claude Code's styles that are a question of what is shown — `default`
(brainyard's normal), `concise` (quiet) and `explanatory` (debug). `-v` still
gives verbose. An edit applies from the next command or turn, and a project can
set it once rather than every session. Claude Code's styles also change what the
model writes; these change only what a viewer shows.

<!-- divergence: sessions-are-a-list -->
**Several sessions are a list, not tabs.** brainyard holds several sessions in
one TUI as tabs — `/session tabs|switch N|new|close|rename`, Ctrl-N/Ctrl-P to
move between them — and routes a live block that finishes in a background tab
into that tab's saved scrollback. This follows Claude Code instead: one session
in the foreground, the others carrying on in the background, and a list of
them that ← on an empty input line opens — grouped by what needs a person
first (a permission ask waiting, then a turn running, then waiting for a line,
then ended), each named by its recap or its first question with the last thing
it said. Enter opens one, Esc returns to the one you left, ctrl+x ends one, and
a task typed there starts a new session. Each session is a renderer and a
thread of its own, so nothing is routed between them: the screen shows
whichever renderer is in front, and brainyard's origin-session settling has
nothing to do.

<!-- divergence: a-subagent-has-its-own-buffer -->
**Each subagent has its own transcript, opened by a click.** brainyard keeps
tabs, and a root agent's subagents all write into ONE shared sub-output tab
(`main1↓`, `persist_bridge.clj` `tee-sub-output-scrollback!`) that the user
switches to. Here there are no tabs (sessions are a list instead, divergence
`sessions-are-a-list`). Each child instance
gets a buffer of its own, holding its question, its tool calls, its reply and
its answer. A click on the child's running row, or on the call that started
it, shows that buffer, and Esc returns to the root. One child's work is never
interleaved with a sibling's, and the call in the root's transcript is where
you find it again after it has finished.

<!-- divergence: a-fold-is-clicked-open -->
**A fold is a click, and the editor is ctrl-o.** brainyard collapses a long
body behind a `[*Collapsed:<id>*]` marker (`components/display-block`). While
scrolled back, Tab steps between markers and Enter toggles one. Here Tab is the
loop's focus key and never reaches a binding (RFC-028), and the transcript has
no cursor to step with. So the fold is the `… +N lines` row under a tool
result, a click opens or closes it where it is, and ctrl-o hands the fold
touched last to `$EDITOR`, as brainyard's display block does.

<!-- divergence: grapheme-width-is-assumed -->
**Grapheme width is assumed, not negotiated.** brainyard sets
`BY_GRAPHEME_WIDTH=auto` and asks the terminal with DECRQM (`CSI ? 2027 $ p`).
It caches the answer in `~/.brainyard/terminal-caps.edn`, measures tmux by
writing into a scratch pane because tmux never answers, and falls back to
per-codepoint widths on every failure. The port takes glimmer-tui's table.
Measured under tmux 3.6, nothing in that table is wrong for the glyphs a model
writes, ZWJ families included, so a probe at every start would buy nothing
there. `dev/glyph-probe.sh` asks any other terminal the same question on
demand. A terminal that disagrees is a fix for glimmer-tui, which paints for
every consumer, not for this repository.

<!-- divergence: provider-key-shapes-are-recognised -->
**Anthropic and OpenAI project keys are secret-shaped.** brainyard's shaped
lists (`config.clj` for refusing a config write, `trajectory_export.clj` for
redacting an export) both use `sk-[A-Za-z0-9]{n,}`. An Anthropic key is
`sk-ant-api03-…` and an OpenAI project key is `sk-proj-…`: the hyphen after
the prefix ends the match at three characters, so neither is recognised. A
pasted key of the port's own default provider would be written into
config.edn, shown by `/config`, and left in an export. `secrets/token-patterns`
adds both shapes.

<!-- divergence: a-drag-selects-in-the-app -->
**A drag selects in the app, and copies on release.** brainyard leaves
selection to the terminal. With mouse reporting on, a plain drag reaches the
app instead, so selection there needs the terminal's bypass modifier (Shift,
or Option in xterm.js), and copying is an explicit command. Here a plain drag
selects the transcript cells it covers, drawn reversed, and the release copies
them through brainyard's own `clipboard/copy!`. The mouse keeps its wheel and
its clicks, and selecting needs no modifier. The terminal's Shift-drag still
works as before.

<!-- divergence: a-line-break-escaped-twice-is-decoded -->
**A string output whose line breaks were escaped twice is decoded.** brainyard
takes a string field as the JSON decodes it. haiku, through `claude -p`,
answered with `\\n` in its JSON, so the answer decoded to one line holding a
backslash and an `n` where each paragraph broke, and was shown that way. Here a
string field with no real line break and a literal `\n\n`, or three `\n`, has
its `\n`, `\t` and `\"` decoded. One escaped `\n` mentioned in passing, or any
field with real line breaks, is left as it came.

<!-- divergence: a-reply-outside-its-schema-is-repaired -->
**A predictor reply that does not match its output schema is sent back.**
brainyard fills defaults, coerces, validates the reply against its malli output
schema, and on a mismatch logs `::output-validation-failed` and uses the reply
anyway. Here a mismatch throws `:kind :malformed` with the problems named. In
the ReAct loop that becomes the repair's `FORMAT ERROR`, which tells the model
what was wrong, within the same retry budget as a reply that was not JSON. A
verdict outside its enum, a checklist item without its text, or a tool call
whose args are not an object would otherwise flow on as though they were
right (byj-gly.203).

<!-- divergence: a-tool-call-is-name-and-args -->
**A model writes a tool call as `{name, args}`.** brainyard's ReAct step asks
for `{tool-name, tool-args}` (`::acs/tool-calls`). The shorter keys are what a
model sees for a call almost everywhere else, so they are what it writes
without being taught. `react/think-act`'s schema, its instructions, the
session prompt's example and the iteration history the model reads back all
say `name`/`args` (and `result`), so the model is never shown both. A reply
written the brainyard way is still read, as a repair. Inside, the loop, its
hooks, the trajectory and the permission gates keep `:tool-name`/`:tool-args`:
the reply is mapped at the boundary (`agent/think-act-action`,
`context/model-view`). The shared schemas are named in this port's namespace
(`:brainyard.schema/*`) rather than brainyard's, so a brainyard tool file
referring to brainyard's names does not resolve.

<!-- divergence: skills-write-is-the-agents-own -->
**The agent writes skills itself, and a skill it writes is callable in the same
turn.** brainyard binds `skills$write` to its skill-agent alone and reloads the
registry at the end of the turn that wrote. This port has no skill-agent to
hand the write to, and an agent is a spec (RFC-018), so `skills$write` and
`skills$reload` are the agent's own, gated by `:skill-authoring?` and by the
permission layer like `tool$create` (`:effects #{:fs/write :tools/define}`). The
write refreshes the registry before it returns, so the result carries the
`:id` the new skill is called by. The prompt's one-line listing is one of the
session's stable zones and names the skill only from the next rebuild.
`skills$search`, `skills$install`, `skills$import` and `skills$sync` are the
agent's own on the same terms (`byj-gly.248`, `byj-gly.254`); a CLI exiting
non-zero is an `:error` here, where brainyard returns its output as a result.

<!-- divergence: skills-cli-as-it-is -->
**The `skills` CLI is called as it is today.** brainyard passes
`--target claude|agents` to `npx skills find`, `add` and `update`. The CLI npx
fetches has no such option: `add` installs by scope (`-g`) and links into agents
with `-a`, `update` updates by scope, and `find` takes a query and `--owner` —
given `--target agents` it searched for "agents". Here `skills$install` takes
`agent` (`-a`) in place of `type`, `skills$sync` takes nothing and updates every
global skill, and `skills$search` makes one call with `owner` rather than one per
backend. brainyard runs the same unpinned `npx skills` and has the same fault
(RFC-017).

<!-- divergence: a-staged-proposal-is-announced -->
**A skill proposal is announced to the person as it is staged.** brainyard's
nudge attaches a notice to the next turn's iteration record, and the notice
tells the model to tell the user. Here the session says it directly, as an
`:info` line, in the turn that staged it: `skill proposal staged: <name> — …
/proposals show skill/<name>, /proposals accept skill/<name>, or /proposals
reject skill/<name>`. The person is the one who decides, so the person is told.
A notice to the model costs a turn's prompt and depends on the model passing it
on. The model can still find what is waiting with `proposal$list`, but it cannot
accept a proposal: brainyard's `skill-proposal$accept`/`$reject` are the
session's `/proposals` here.

<!-- divergence: one-proposals-gate -->
**Every kind of self-improvement is reviewed at one gate.** brainyard reviews
skill proposals with `skill-proposal$list/read/accept/reject` and its
self-improve CLI, and a compiled program's params elsewhere. Here skill
proposals, user-tool proposals, predictor params and loops (RFC-071) wait behind one
`brainyard.proposals`: the session's `/proposals`, ids `KIND/NAME`, and the
model's read-only `proposal$list` / `proposal$read` in place of
`skill-proposal$list` / `$read`. Each kind keeps its drafts where it always did
and goes live by its own path; only the list, the ids and the gate are shared
(`byj-gly.249.1`).

<!-- divergence: tool-distillation -->
**A `tool$run` body that worked can become a user tool.** brainyard's
self-improvement writes skills only: its distiller drafts `SKILL.md` prose from
trajectories, code blocks included. Here the bodies `tool$run` ran are also read
for a tool: a judge (`session/distill-tool`) generalises them, the kernel proves
the draft by replaying each run it came from, and a proved one waits at
`/proposals` as `tool/<name>` for a person to accept (RFC-010, `byj-gly.249`).
Off by default, `:enable-tool-distillation`, as brainyard's distillation is.

<!-- divergence: refinement-judges-only-writable-skills -->
**Refinement judges only brainyard skills.** brainyard judges every loaded
`skill$`. Its accept path then calls `skills$write :update`, which refuses a
`claude` or `agents` skill, so a revision of one of those is staged and paid
for but can never be accepted. Here such a skill is not judged at all: a skill
of another harness is read, never written.

<!-- divergence: one-distiller-for-every-window -->
**One distiller, and no distillation modes.** brainyard has two modes and two
signatures. `:per-turn` judges each candidate turn with `SkillDistillation`,
and `:at-cadence` judges a window with `SkillDistillationBatch`. A window of
one turn is still a window, so `session/distill-skill` is the batch signature
alone. There is no `:skill-distill-mode`: a window is judged as soon as it
shows a procedure (divergence `distillation-auto-mode`), and at the latest
every `:skill-distill-judge-window` turns. That is brainyard's
`:skill-distill-every-n-turns` (`BY_SKILL_DISTILL_EVERY_N_TURNS`), renamed
because it no longer sets how often a window is judged, only the longest a
window waits (`BY_SKILL_DISTILL_JUDGE_WINDOW`). With 1 that is brainyard's
`:per-turn`, and with brainyard's 12 it is its `:at-cadence` whenever no
procedure shows sooner. The candidate filter, the output fields and the
staging rules are unchanged. A config that still sets `:skill-distill-mode` is
reported as an unknown key.

<!-- divergence: distillation-auto-mode -->
**A window is judged as soon as it shows a procedure.** brainyard judges only
at the cadence and at session end, so a procedure done at turn 2 and again at
turn 4 waits for turn 12. Here, after every turn, the unjudged candidate turns
are checked for a procedure, and the check costs no model call:
- the same procedure shape (the tools called, in order) of two or more tools,
  repeated in two turns;
- or one turn of eight steps or more.

The window is judged when either appears, and at the cadence as before. A
repeat is the plainest evidence of what brainyard's judge looks for: that the
procedure "would plausibly REPEAT". The thresholds are the tree node's
`:auto` opts.

<!-- divergence: an-injected-turn-names-its-sender -->
**A turn that came over the session channel says so.** brainyard echoes a
side-ask's question into the session as if it had been typed. Here the front
end first says `⇠ asked over the session channel`, adding `by <session>` when
the request carries `:from` (the port's addition to the request). A person
watching a member session should be able to tell a leader's instruction from
their own typing. An injected turn's `:from` may also name a person,
`{:user :name :role}` (RFC-043), and then the line is `⇠ injected by Kim
(coach)`, with how it was allowed.

<!-- divergence: the-channel-names-its-frame-version -->
**The session channel says which version its frames are.** brainyard's
`:status` reply and `meta.edn` carry no version: every client of `ask.sock` is
built from the same repository as the session. Here both carry
`:frame-version` (`channel/frame-version`, 2), because the share sidecar
(RFC-043) is a separate Go binary released on its own and must refuse a frame
shape it does not know rather than misread it. Version 2 added the ops and
events of `a-session-is-shared-through-a-sidecar`.

<!-- divergence: a-session-is-shared-through-a-sidecar -->
**A session can be shared with people, through a sidecar.** brainyard shares a
session only as a screen (`--web`, ttyd). Here `--share link|class:CODE` starts
the `byj-share` sidecar as a background task of the session (RFC-043), which
reaches the session only through `ask.sock`. For it the channel takes
`:inject :as :note` — a note for people only, shown, recorded in the
trajectory and published as `:session.share/note`, never a turn — and an
injected turn's `:from {:user :name :role}` and `:approval :owner|:consent`,
the owner asked in the gate's overlay with two minutes to answer
(`:share-inject-approval-ms`); the gate says `:permission/asked` and
`:permission/decided` on the bus, read only; the trajectory records
`:injected-by` and evaluation counts it apart. `/share` lists who watches and
pauses, resumes, kicks or stops. None of it exists in brainyard.

<!-- divergence: an-agent-sdk -->
**An application drives byj through an SDK.** brainyard has no SDK: it is
driven by a person, by `by ask`, over its session channel and over A2A, and
its ACP code is a client of other agents (`components/acp-client`), never an
agent itself. Here an application opens sessions in process through
`brainyard.sdk` (RFC-053), or out of process through `byj acp`, an ACP v1
agent on stdio with byj's `_byj/*` extensions — client tools, hook
callbacks, byj's events, `predict`, a structured answer, sessions it did not
start, a session forked (`persist/fork!`: brainyard resumes, it never forks)
— and the client libraries over it. A session so opened is an ordinary
one: the same tree, gate, hooks, trajectory and event log.

<!-- divergence: a-piped-session-has-no-channel -->
**A session reading a pipe opens no channel.** brainyard's every session
listens. Here the terminal session opens one only when standard input is a
terminal. To take a request while waiting, the reader has to poll its input,
and a poll cannot tell the end of a pipe from a pause, so a piped session would
never end. The full-screen session always listens, and a session with no
terminal that should listen is the headless host (`jolt run:byj --serve`,
`byj-gly.253.4`).

<!-- divergence: sessions-ask-each-other -->
**A session can ask another one itself.** brainyard's sessions are asked only
by its CLI and by scripts. Here the model also has `session$list`,
`session$status`, `session$ask`, `session$send` and `session$cancel`, because a
leader has to hand work to its team members without a person typing
`by ask --attach` for it. `session$send` does not wait: its reply is a
background task of the sender's (RFC-019). The CLI names a session by its id, a
prefix of it or its label, where brainyard takes the id alone, since a prefix
is what a person reads off `session list`. A request carries `:from`, the
asking session's id.

<!-- divergence: a-headless-session-has-a-persona-and-a-name -->
**A headless session may be started as an agent, under a name.** brainyard's
`by run --serve -s <id>` hosts a session of its default agent, named by its
id. Here `jolt run:byj --serve --agent NAME --label TEXT` gives it an agent
spec's persona, the same one a subagent of that spec gets
(`agents/persona-config`), and a name `session list`, `ask --attach` and
`session$ask` all accept. A team's members are headless sessions with a role
each (`byj-gly.253.5`), and a role is exactly an agent spec. `--agent` gives
`byj run` and `byj ask` the persona too, and the SDK's sessions take one by
name (`session/with-persona`, `byj-g5n.2`): brainyard's `by` has no such flag
outside `--serve`.

<!-- divergence: agent-teams -->
**A session can lead a team.** brainyard has no teams: one process may host
several sessions, and A2A peers are the only calls from one agent to another.
Here a leader session starts member sessions (`team$spawn`: headless sessions
with a role each, run as the leader's background tasks), hands them work
(`team$assign`, over the session channel), watches it (`team$status`) and closes
them (`team$close`). Members work at the same time, each with its own turns and
tools, and a member editing code can have a git worktree of its own. How to lead
is a prompt, not code: `team$create` hands the leader `prompts/team-lead.md`.

<!-- divergence: one-session-per-process -->
**A session is its own process, so the channel does not start or switch
sessions.** brainyard's host process holds several sessions, and any session's
socket answers `:new-session`, `:resume-session` and `:switch-session` for all
of them. Here a session with no terminal is its own process, started with
`jolt run:byj --serve` (or `team$spawn`, or resumed with `--serve --resume`),
and the several sessions of one full-screen run are switched in its session
list. Those three ops answer `not ported:` with where to go instead, as do
`:fsm-status`, `:a2a` and `:inject :as :artifact`, whose subsystems this port
does not have. `:mcp` is read only: changing a server is `/mcp`'s, in the
session, where a person sees it.

<!-- divergence: a-run-resumes-as-a-set -->
**A full-screen run can be resumed as the set it was.** brainyard resumes one
session per `--resume`, and its exit hint lists a line for each tab. Here a
run also records the sessions it has open and the one in front
(`.brainyard/sessions/last-run.edn`), and `--resume-run` reopens them all,
with the same one in front. The session list also offers saved sessions to
resume. Each session is still its own, and `--resume <id>` still continues one.

<!-- divergence: the-default-model-is-config -->
**The model a run asks is a config key, `:default-lm`, and by default
`claude-code/sonnet`** (haiku until byj-gly.366). brainyard resolves a process-wide default LM from
`LLM_PROVIDER` and its providers config, and its `:lm-config` is read-only.
Here `:default-lm` is a `provider/model` label that `config.edn` or
`BY_DEFAULT_LM` sets, below `-p`/`-m`. An API key in the environment does not
pick the provider: an OpenAI key in a `.env` had made `openai/gpt-4.1-mini`
every new session's model without anyone choosing it.

<!-- divergence: update-file-takes-old-new -->
**update-file also takes `old`/`new` and `replace_all`, and a name given
twice with two values is refused** (byj-gly.394, byj-gly.395). brainyard maps
`old_string`/`new_string` (and `old-string`, `oldstring`, and the `new` forms)
onto `pattern`/`replacement` in one table for every tool
(`arg-alias->canonical`, `agent/core/tool.clj`), where the canonical name
silently wins when both come. The port declares aliases per tool spec
(`:arg-aliases`, RFC-010) and takes brainyard's for update-file. It adds
`old`/`new`, which every update-file call the logs refused had sent, and
`replace_all`. A canonical name and an alias with different values are
refused naming both, since which one the model meant is not knowable.

<!-- divergence: a-wakeup-carries-its-request -->
**A parked turn is woken with the request it parked from, in full**
(byj-gly.389). brainyard's wakeup message names the task, its status and the
note. Here it also carries the turn's own request, from
`prompts/task-wakeup.edn`. A woken turn reads earlier questions through
history cut to 500 characters, and a long request's closing steps — `/team`'s
check, merge and `team$close` — were past the cut, so the turn finished
without them. A turn that parks again passes the original on.

<!-- divergence: side-tasks-run-light -->
**A predictor's `:light` tier with no table entry runs on the session
provider's light model, and the recap and command rephrasing declare it**
(byj-gly.367). In brainyard every tier is inert until `:agent-lm-tiers` names a
model, and an unmapped tier stays on the call's LM. Here `:standard` and
`:deep` keep that, but an unmapped `:light` resolves to the model
`models.edn` marks `:light? true` for the ambient LM's provider (claude-code's
haiku), or the ambient LM when there is none — so a session on sonnet stops
paying sonnet for its recaps. `react/compact` declares it too (byj-gly.378);
`session/compact` does not. Subagent tiers are unchanged. RFC-005 "The light
tier" has the measurement.

<!-- divergence: tool-lisp-indent-repair -->
**A tool body whose delimiters do not balance has its closers rebuilt from its
indentation, and the reply says which lines changed.** brainyard's sandbox
repairs code that ends with forms left open: it appends the closers the
reader asks for, and only logs that it did. Here `tool$create` and
`tool$validate` rebuild the closers at the end of each line from how the next
line is indented. This is parinfer's indent mode, which samizdat also uses. It
fixes a closer too many, too few or in the wrong order in the middle of a body.
A body that ends open is one case of it. The repair is kept only when the
result reads, and the reply lists the lines it changed. It repaired 24 of the
26 unbalanced bodies from byj-gly.258's eval
(`dev/brainyard/dev/paren_repair_spike.clj`). `:tool-lisp-repair? false`
refuses such a body instead.

<!-- divergence: the-thought-is-for-the-person-watching -->
**The loop's thought is written for the person watching, not as reasoning for
the model.** brainyard's `reasoning` field asks every chain-of-thought
predictor for "brief reasoning leading to the answer … state the decision and
why". Here `react/think-act` asks for what the last result told the model that
is new, and what it does next and why, without restating the question or
earlier iterations (`prompts/think-act-thought.md`). The judges and the other
predictors keep brainyard's wording.
Measured by `dev/brainyard/dev/thought_spike.clj`:
- haiku's tool-channel thoughts restated what the reader already had 57% of
  the time before, and 0% after;
- task success did not move;
- sonnet was near 0% either way.

A thought that ends the turn still says "the result is in" whatever it is
asked. That is where the model decides to stop, so the transcript hides it
unless it adds to the answer (RFC-028).

<!-- divergence: claude-agent-in-tmux -->
**agent$claude drives an interactive Claude Code in tmux, not an ACP
connection.** brainyard reaches Claude Code as `acp-agent :claude-code`: an ACP
client subprocess holding one model-pinned session, asked like any subagent.
Here `agent$claude` starts or continues an interactive Claude Code session in a
tmux session of its own (`byj-cc-<id>`), types to it, reads its live record and
transcript, and judges each of its turns with a predictor. Decided with the
user on 2026-10-07 so the session is on the agent page and a person can attach
to it at any time, as with any Claude Code session there (RFC-033). What that
costs is measured in RFC-039: the brief goes in as Claude Code's argument and
guidance as one typed line, since a paste is taken for data, and a turn's end
is read from the record and the transcript together.

<!-- divergence: init-sets-up-the-project -->
**`/init` prepares the project, with AGENTS.md as its instruction file; it
does not author BRAINYARD.md.** brainyard's `/init` drives `init-agent`, which
owns `.brainyard/BRAINYARD.md` at project and user scope: it seeds the file
once from CLAUDE.md and AGENTS.md, which it treats as read-only, and maintains
it through snapshotted transactions. Here `/init` runs `agent$init` (RFC-040):
it checks the tools brainyard relies on and says how to install the missing
ones, writes AGENTS.md and a CLAUDE.md that imports it, sets up beads with bd,
and drafts the project's section of AGENTS.md. AGENTS.md is the one
instruction file, read by brainyard, Codex and Claude Code alike, so there is
no third file to keep in step with it. Decided with the user on 2026-10-08.
What is kept from brainyard: a snapshot before every write, the person's text
never rewritten, detection that only reads, and no install without a yes.
Following from it, the project's CLAUDE.md and AGENTS.md
(`:project-instruction-paths`, brainyard's `:reference-artifact-paths`
renamed beside `:user-instruction-paths`) go where brainyard
puts BRAINYARD.md, and are budgeted as instructions — not at brainyard's
4000-character cap for reference notes, but 32 KiB across them, Codex's
figure (`:project-instructions-max-chars`, byj-gly.312): `## Project Instructions` in the
session-stable zone, above the cache breakpoint — not under `## Live
Artifacts` in the per-turn tail, where brainyard shows them as reference
files beside its own instruction file (`brainyard.instructions`). AGENTS.md
is shown before CLAUDE.md, where brainyard's list has CLAUDE.md first. At user
scope, `~/.brainyard/AGENTS.md` is read as `## User Instructions` in place of
brainyard's `~/.brainyard/BRAINYARD.md`, which is not read (byj-gly.313).

<!-- divergence: login-runs-the-cli -->
**`/login` runs another CLI's own login; brainyard's says which command to
run.** brainyard's `/login claude` is detect-and-instruct: it reports whether
the `claude` CLI is signed in and prints `claude /login` for the person to run
(`common/auth.clj`, `:cli-delegate`; `docs/design/login-auth-methods.md`),
since its TUI cannot give the terminal away. Running it was its follow-up 4,
"PTY takeover". The port's full-screen renderer already hands the terminal to
an editor or a Claude Code session and takes it back (`tui/hand-over!`). So
`/login claude`, `codex`, `gh` and `bedrock` run the CLI's own login — `claude
auth login`, `codex login`, `gh auth login`, `aws sso login` — then ask it
again, through the front end's `:hand-terminal` seam (RFC-027). Headless, the
command is said, as in brainyard. Also unlike brainyard: codex, gh and aws are
targets; the API-key targets are every provider that reads a key; and MCP
OAuth servers are not targets, since this port's MCP is stdio only (RFC-024).

<!-- divergence: an-export-takes-a-turn-range -->
**`trajectory$export` can export a range of turns.** brainyard's exports
every turn record of the sessions it is given. Here `:turns [from to]`
exports only those turns of each session, 1-based and inclusive, a turn's
number being its position among the session's turn records (a note is not a
turn). The share sidecar's recordings (RFC-043) name turns
this way, and the two must count alike. Omitted, the export is
brainyard's.

<!-- divergence: slash-commands-become-turns -->
**A slash command that stands for an agent is a turn the session's agent
drives, not a direct call.** brainyard's `/init` dispatches `init-agent`
directly (`bases/agent-tui commands.clj handle-init-command`, `agent/call-tool
:init-agent`), as this port's did until byj-gly.350. Here `/init` becomes the
turn's question: the agent the session runs is asked to do what `/init` means
by calling `agent$init`, reading its report and calling again when the next
step waits on something, and it answers with the report. The question is the
command's template in a session with no turns, and otherwise rephrased for the
conversation so far by `session/rephrase-command` (RFC-004), so `/init` after
a turn that installed a tool asks for the check again with that said. Decided
with the user on 2026-10-15. What is kept: the person's words carried
verbatim, `check` writing nothing, an install asking the person every time,
and agent$init's suggestion reaching the input line.

<!-- divergence: explore-shell-is-held-to-reading -->
**explore's shell is held to reading by its instruction and a few deny
patterns.** brainyard's explore-agent has `bash` with no limit but its
instruction — find, git log, git grep, tree, rg, fd. Here explore has `bash`
too (byj-gly.355; byj-gly.16 had left it out, which this file did not
record), and like brainyard's it has no `:bash-allow-commands`: one held it to
cd, git, rg, fd, find, tree, ls, wc, curl and bd through the command gate
(RFC-012), and a list of the programs that read was never complete — every
program it lacked was a refused line that only read — so it was removed. A
session's own allow list still holds for explore. What a program writes
(`rm`, `git commit`, `find -delete`, a redirection) the gate does not stop;
the instruction forbids it, and the sandbox is the boundary. What differs
from brainyard is the deny patterns below.
brainyard's explore reaches the web through web-search and fetch-url, which
this port has no tools for; here it is `curl` (byj-gly.356), its sending and
file-writing flags refused by explore's `:bash-deny-commands`, a tripwire on
the whole line that combined short flags slip past. `bd` may read (ready, show,
list); `bd create`, `bd update` and `bd close` are refused the same way, and
other writing subcommands are not (the tripwire names three).

<!-- divergence: trajectory-keeps-the-permission-mode -->
**A trajectory record keeps the permission mode its turn ran under.**
brainyard's record (`agent/common/trajectory.clj` `build-record`) says what a
turn did and what it cost, not under which permission mode. The port adds an
optional `:permission-mode` — the mode a person last chose, else the
config's, at the turn's end — because a scorer reading the trajectory asks whether a change was made with permissions bypassed, which Claude Code's
transcript answers on every line and a byj record otherwise could not: a
session under `:auto-approve` would have read as met on no evidence. A reader
of brainyard's records loses nothing — the key is optional, and `:v` stays 3.

<!-- divergence: trajectory-keeps-the-loop-hash -->
**A trajectory record says which loop its turn ran.** brainyard's record
(`agent/common/trajectory.clj` `build-record`) does not, and could not need
to: its loop was compiled in. byj's is data a person or a harness chooses
(`--loop`, a project's own loop, RFC-071), so the port adds an optional
`:loop {:hash}`, the hash of the tree the turn ran, expanded — what
`byj flow check --json` gives a loop file as `:expanded-hash`. A harness
built on byj that ran a candidate on a benchmark, Terminal-Bench say,
checks from it that every trial ran the candidate (byj-ng7.19). A reader of
brainyard's records loses nothing — the key is optional, and `:v` stays 3.

<!-- divergence: session-and-agent-are-singular -->
**`byj session` and `byj agent`, not `sessions` and `agents`.** brainyard's
CLI lists a project's sessions with `by sessions list`
(`projects/agent-tui-app/.../main.clj`, `:command "sessions"`); this port
named its own `byj agents [NAME]` (byj-1wv.47) the same way. Both are
singular here (byj-gly.435), so a command names the thing it is about, as `run` does: `byj session list`, `byj session watch ID`,
`byj agent --name explore` (the name an option since byj-gly.441). The plural names still parse to the same command, and
`byj sessions -h` shows the page under its new name, so a script or a
brainyard habit keeps working; A program
that asks byj for its agents may ask `agent` first and fall back to
`agents`, passing the name as that byj's help shows it, so either side may
be the newer.

<!-- divergence: session-list-shows-saved-sessions -->
**`byj session list` shows saved sessions that never listened.** brainyard's
`by sessions list` lists a project's sessions that have listened on the
session channel — those with an ask socket — and so does this port's
`channel/sessions`. A session opened through the Agent SDK or `byj acp` opens
no socket (RFC-053), so a thread a program held over ACP, saved in the
project like any session, was in no list a person could ask for: `byj session
list` said "no sessions have listened here" (2026-10-29). It now lists them after those that listened, marked `saved`, newest
first, with `"saved": true` in its JSON; one that never ran a turn is left
out, as ACP's `session/list` leaves it. `--live` is unchanged, and so is
`session$list`, which names the sessions a session may ask.

