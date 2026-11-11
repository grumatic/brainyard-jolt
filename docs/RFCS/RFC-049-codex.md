# RFC-049 — Codex sessions: read, driven, delegated to

**Status:** decided (`byj-gly.430.1`, 2026-10-23), measured on Codex CLI
0.163.0 with `dev/brainyard/dev/codex_spike.clj`; built: reading
(`brainyard.codex-sessions`, `.2`), driving (`brainyard.codex-host` over
`brainyard.agent-host`, `.3`), the agent page (`.4`), handover
(`brainyard.codex-reach`, `.5`, RFC-031) delegation (`agent$codex`,
`brainyard.codex-agent`, `.6`), usage (`brainyard.codex-usage`, `.7`) and a
the codex provider (`.9`, RFC-006) and what a rollout records
(`byj-gly.288.13`); a coach's turn in a shared Codex session (`.8`) and its
evaluation are the sharing service's and the evaluator's, not byj's.

## Purpose

Decides how byj supports Codex sessions as it supports Claude Code's
(RFC-033 the agent page and `claude-sessions`, RFC-039 `claude-host` and
`agent$claude`, RFC-012's permission hook): where a Codex session
is read from, how byj tells it is live, busy, idle or waiting on a person,
how it drives the interactive TUI in tmux and runs it headless, how a session
byj starts is kept under byj's gates and from publishing, and and what its
rollout and telemetry record for whoever evaluates it. It records what was measured, because
Codex's own documentation lagged what 0.163 does — the epic was planned
expecting no tool-use hook, and there is one.

## Scope

**This RFC decides** the transport and the seams of the Codex layers; each
layer's API and invariants go in the RFC that builds it (RFC-033 and RFC-039
get Codex sections, or this RFC grows them).

**It must not know** what a delegated Codex session is asked to do (the
behaviour is the agent's, RFC-001), nor the person's Codex configuration
beyond reading it: byj writes nothing to the person's Codex home.

**It hands** `codex-sessions` (`.2`) the reading, the host (`.3`) the
driving, `agent$codex` (`.6`) the delegation and its guard, `byj hook`
(RFC-012) a session's tool calls, and a program that reads rollouts —
an evaluator, the share sidecar — the 0.163 rollout format, as measured here.

## The decision

1. **Codex's home is `$CODEX_HOME`, else `~/.codex`.** Never assumed: on this
   machine Orca sets `CODEX_HOME` to a home of its own
   (`~/Library/Application Support/orca/codex-runtime-home/home`) and
   `~/.codex` is empty. The home holds `auth.json`, `config.toml` (folder
   trust, hook trust), `hooks.json`, `sessions/YYYY/MM/DD/rollout-*.jsonl`,
   `session_index.jsonl` (`{id, thread_name, updated_at}`), `history.jsonl`,
   `thread-writer-locks/` and SQLite stores (`state_5`, `thread_history_1`,
   `queue_1`, `goals_1`, `memories_1`, `logs_2`).
2. **A session is read from its rollout** — one JSON line each
   `{timestamp, type, payload}`, appended as it runs, `exec resume` included.
   The person's message is `event_msg item_completed` with item type
   `UserMessage`, the agent's `AgentMessage` (phase `final_answer`); a turn is
   `event_msg task_started` … `task_complete` (`last_agent_message`,
   `duration_ms`) or `turn_aborted`; usage is `event_msg token_count` and
   `token_usage_record`. The older `event_msg user_message` /
   `agent_message` are not written any more. A shell call is
   `response_item custom_tool_call` named `exec` whose input is JavaScript
   calling `tools.exec_command` (code mode), with its
   `custom_tool_call_output`. The title is `session_index.jsonl`'s
   `thread_name`, else the first `UserMessage`.
3. **Live is the thread writer's lock.** Codex holds
   `thread-writer-locks/<thread>.lock` exclusively from start-up to exit; byj
   asks with a non-blocking shared `flock` through `jolt.ffi` (`open`,
   `flock(LOCK_SH|LOCK_NB)`, `close`) — refused while live, taken and let go
   at once when not; no subprocess, no lsof. A TUI takes its thread's lock
   before its first prompt, before the rollout exists: the lock names a fresh
   session, the rollout does not yet.
4. **Busy and idle come from the rollout; waiting on a person from a hook.**
   A turn has started at `task_started` and ended at `task_complete` or
   `turn_aborted`. Waiting for approval is in no rollout line: for a session
   byj started, its `PermissionRequest` hook is called as the dialog would
   open (with the command and the reason) and is the signal — and the answer;
   for a session the person started, the pane says it ("Would you like to run
   the following command?").
5. **The TUI is driven in tmux through a core shared with Claude Code, an
   adapter each.** `claude-host`'s tmux parts — session names, start and stop,
   the pane, keys, `running`, attach hints, the type-a-line-then-confirm loop
   and await-settled's polling — become a CLI-neutral core; Claude Code and
   Codex each give an adapter: the argv, what says ready, the dialogs and
   their keys, how a typed line is confirmed taken, and status. Codex's
   adapter: ready is the writer's lock taken (the main screen is drawn before
   the folder dialog is); the folder dialog's cursor is on "Trust and
   continue", Enter accepts; a line is typed, then Enter after 200 ms, taken
   when the thread's rollout gains a `task_started`, else Enter once more; a
   long brief is the argument or a bracketed paste, both arriving whole; an
   approval dialog's cursor is on "Yes, proceed", so a host never sends Enter
   into one it has not read.
6. **A session byj starts carries byj's hooks as session flags, trusted for
   that session only.** Codex 0.163 has Claude Code's hooks — `PreToolUse`,
   `PermissionRequest`, `PostToolUse`, `UserPromptSubmit`, `SessionStart`,
   `Stop`, … — with Claude Code's payload (`tool_name "Bash"`,
   `tool_input {command}`, `session_id`, `transcript_path`, `cwd`,
   `permission_mode`, `turn_id`) and Claude Code's output
   (`hookSpecificOutput.permissionDecision "deny"`, `PermissionRequest`'s
   `decision.behavior`). A hook runs only once trusted: Codex records
   `trusted_hash` under `[hooks.state."<source>:<event>:<group>:<handler>"]`,
   the sha256 of the sorted compact JSON of the event's key label, the
   group's matcher and the handler with its defaults (`async` false, unset
   options left out) — codex-rs `hook_hash`, reproduced exactly here, on
   Orca's eight hooks and the spike's. Hooks and trust records are read from
   the user layer and the session-flags layer, so byj passes both as `-c`
   options — the hook under `hooks.<Event>`, its record under `hooks.state` as
   one inline table keyed `/<session-flags>/config.toml:<event>:0:0` (a dotted
   `-c` key splits at the `.` in `config.toml`) — and writes nothing to the
   person's home. `byj hook pretooluse` answers a Codex `PreToolUse` unchanged
   (RFC-012 "Another agent's calls").
7. **A delegated session is kept from publishing by its sandbox and byj's
   hook.** Codex's default `workspace-write` sandbox has no network and keeps
   `.git` read-only: a commit fails on `.git/index.lock`, and a push to a
   network remote cannot run. A push to a writable local path can — the
   spike's did, until the `PreToolUse` hook refused it. So `agent$codex`
   keeps the sandbox, adds byj's publish guard and gates as the `PreToolUse`
   hook, answers escalations through `PermissionRequest`, and commits for the
   session itself (or widens the sandbox to the worktree's git directory,
   `.6` to decide).
   Where the sandbox cannot start — on Linux it is bubblewrap, which needs
   unprivileged user namespaces, and a container is not given them, a
   hosted machine's included ( measured on Codex 0.163: `codex sandbox --
   true` fails "No permissions to create a new namespace", and its only
   other backend, the deprecated `use_legacy_landlock`, refuses
   filesystem-restricted execution without bubblewrap) — the session starts
   with `-s danger-full-access`, the publish guard alone between it and a
   push: what the delegation says (`guard-alone-note`) and records
   (`:sandbox`), asked once a session is opened (`codex-host/sandbox-check`,
   `codex sandbox -- true`, Linux only). `:codex-require-sandbox?` refuses to
   open one there instead (byj-1wv.62.12, 2026-10-24).
8. **Headless is `codex exec --json`.** One event a line —
   `thread.started {thread_id}`, `turn.started`, `item.completed` (items
   `agent_message`, `command_execution` with the command and its output, …),
   `turn.completed {usage: input_tokens, cached_input_tokens,
   cache_write_input_tokens, output_tokens, reasoning_output_tokens}`.
   `codex exec resume ID` continues the thread in the same rollout. `exec`
   runs with approvals off whatever `approval_policy` says, and reads stdin
   when it is not a terminal: give it `""`. It is what the codex provider (`.9`) runs.
9. **OTel goes to a receiver as it is.** `-c 'otel.exporter={otlp-http=
   {endpoint="http://127.0.0.1:PORT/v1/logs",protocol="json"}}'` sends OTLP/HTTP
   JSON logs, which the spike's own listener receives: `codex.user_prompt`,
   `codex.tool_decision`, `codex.tool_result`, `codex.api_request`,
   `codex.sse_event`, `codex.turn_ttft`, `codex.conversation_starts`, ….
   Traces and metrics are separate exporters (`trace_exporter`,
   `metrics_exporter`), set to `none` by the spike.
10. **`codex queue` reaches a live session without tmux.** `codex queue
    --thread ID --message TEXT` puts a message in `queue_1.sqlite`; a TUI idle
    at its prompt ran it as its next turn within two seconds. It is how
    `session$send` (`.5`) reaches a Codex session the person runs in a
    terminal of their own, which tmux cannot type into.

## What was measured (`dev/brainyard/dev/codex_spike.clj`)

Three runs of the spike, 2026-10-23, Codex CLI 0.163.0 (model GPT-6-Luna,
effort low), each in a throwaway `CODEX_HOME` holding only a copy of
`auth.json`; the same probes were first made by hand.

| probe | found |
|---|---|
| `:exec-json` | events `thread.started`, `turn.started`, `item.completed`, `turn.completed`; usage ~14.6k input (10.9k cached), 5 output for a one-word answer; the rollout's person and agent messages are `item_completed` `UserMessage` / `AgentMessage`, and no `event_msg user_message` is written |
| `:resume` | `exec resume` keeps the thread id and appends to its rollout (16 → 29 lines, one file) |
| `:sandbox-git` | `git commit --allow-empty` fails: `.git/index.lock`, read-only file system |
| `:hook-flags` | a `PreToolUse` hook and its trust record given with `-c`: called once, payload as Claude Code's, `git push` refused before git ran, the bare remote empty, no `config.toml` written to the home |
| `:otel` | 22 events through `serve-otel!`, ten names, `codex.*` |
| `:trust` | a fresh folder's dialog about 1 s in, cursor on "Trust and continue", drawn after the main screen |
| `:liveness` | the thread's lock taken at start-up and live, no rollout yet; released at exit |
| `:typed` | Enter at once: not sent, the line left in the composer; a second Enter a second later sent it (2.2 s); after a 200 ms pause one Enter sent it (1.7 s to complete). By hand: 50 ms was enough, 0 never |
| `:argv-brief`, `:paste` | 61 lines arrive whole as the person's message, unwrapped; the composer shows `[Pasted Content 2248 chars]`. By hand: an unbracketed paste the same |
| `:queue` | run as the idle session's next turn in 1.7 s |
| `:approval` | the `PermissionRequest` hook called with the command and its reason; its `deny` decided with no dialog, the pane showing "Blocked by hook" |

By hand only: an approval dialog's cursor starts on "Yes, proceed", and Esc
aborts the turn (`turn_aborted`, reason `interrupted`); with no hook, the
rollout records only the tool call while the dialog is up; a message queued
right after an aborted turn waited for the next turn (seen once); a hook with
no trust record does not run and says nothing; the TUI asks "Hooks need
review" at start for hooks that are new or changed; `byj-share`'s converter,
fed a 0.163 rollout of about twelve turns, made three turn ends and no
messages (`byj-1wv.61`).

## Model

The layers, in the epic's order, as decided above:

- **`codex-sessions`** (`.2`, built): below.
- **The host** (`.3`, built): below.
- **The agent page** (`.4`, built): below. **Handover** (`.5`, built):
  RFC-031 "A live Codex session as a target" — typed into one in this run's
  tmux, `codex queue` for one anywhere else; checked live, a Codex in a
  person's own tmux queued to and answering.
- **`agent$codex`** (`.6`, built): below.
- **Usage** (`.7`) from `token_count`'s `rate_limits`, and what a rollout
  records for whoever evaluates it (`byj-gly.288.13`); the same gates
  through the hook.

### A project's Codex sessions (`brainyard.codex-sessions`, `.2`)

What `claude-sessions` is for Claude Code (RFC-033), read from the home of
decision 1 and never written to. A saved session is a rollout whose
`session_meta` `cwd` is the project or one of its worktrees; its title is the
thread's newest name in `session_index.jsonl`, else its first question (read
from the first 256 KB, past Codex's instructions). A live one is a saved one
whose writer holds its lock (decision 3) — so a TUI asked nothing yet, which
has no rollout and so no directory, is listed once it has been asked. A cold
rollout (`.jsonl.zst`; `local_thread_store_compression` is off by default in
0.163) names no directory without being read, so none is listed — the
`.2` bead's "listed, not opened" cannot be done for a project's list, where a
session is placed by its directory. A rollout's first line never changes, so
its meta is read once; its question again only when it changed. Where the
newest turn stands is read on from where the last read stopped, complete
lines only, the first read the file's last 8 MB: `task_started` opens a
turn, the person's message is its prompt and the agent's newest its answer
(the same message written as an event and as an item counted once),
`task_complete` ends it — its `last_agent_message` the answer when no message
was seen — and `turn_aborted` ends it aborted; `token_count`'s running total
is `:usage`.

### Driving a session (`brainyard.codex-host` over `brainyard.agent-host`, `.3`)

`agent-host` is the tmux side both hosts share, knowing no CLI: `*tmux*` (a
test binds a server of its own), `attach-env`, a prefix's running sessions
(by name, and by the directory tmux started each in), the command that starts
a detached session, kill, rename, the screen, keys, and the two loops —
`type-and-confirm` (type, pause, Enter, wait for the adapter's `started`, ask
its `taken?` when that says nothing, Enter again up to three times) and
`await-settled` (poll the adapter's observation until `settled?` holds for a
while, a person is asked, or the session is gone). `claude-host` keeps its API
and is Claude Code's adapter: its record, transcript and dialogs, unchanged
in behaviour.

`codex-host` is Codex's. Codex chooses a new session's thread itself, so a
new one starts as `byj-cx-pending-<uuid>` and `ready` renames it
`byj-cx-<thread>` once Codex takes a new thread's writer lock — past the
folder dialog, which `ready` accepts with Enter when its cursor is on "Trust
and continue", once; a resumed one (`codex resume <thread>`) is named for its
thread from the start and up when its lock is taken. A pane takes the tmux
server's environment, so `CODEX_HOME` is passed in the argv, and the
`CODEX_*` variables of a Codex this runs inside are unset. The brief is the
last argument, after `--`. `observe` reads the rollout (`last-turn`): `:busy`
while a turn is open, `:waiting` while the screen shows an approval dialog,
else `:idle` — so too before the first prompt, with no rollout yet.
`type-line` types one line, waits 200 ms, presses Enter, and takes the line
as taken when the rollout has grown and its newest prompt is the line.
`choose` answers an approval dialog with a key — `y`, or `Escape`, which
aborts the turn. `hook-flags` gives a session hooks as session flags with
their trust records (decision 6). Checked live with Codex CLI 0.163 at low
effort: started past the folder dialog, renamed, two lines each taken on the
first Enter, each settled with its answer, stopped.

### On the agent page (`.4`)

This project's Codex sessions are a group of their own after Claude Code's
(`tui/session-groups`): "Codex" — a row that starts one, offered when the
`:codex-command` (`codex`) is on the PATH, then the live ones, a busy one
spinning, one running in another terminal marked and dimmed — then "Codex —
saved, enter resumes one". `main` reads them at most every 2 s
(`codex-sessions/sessions` with `codex-host/with-running`, each live one's
status from its rollout). Enter is `codex-host/plan` and `run-plan!`: a new
one starts under a pending name and is attached; a saved one is resumed
(`codex resume <thread>`) in `byj-cx-<thread>` and attached; a live one
running here is attached; one in another terminal is refused. A session a
person opened is not brought up by `ready`, so before each read
`codex-host/claim-pending!` renames a pending session to its thread, the one
whose writer lock the pane's process, or one under it, holds (`lsof`).
ctrl+s shares a live one as `codex:<thread>` (RFC-043); ctrl+g unfolds what it changed in its directory, as a Claude Code
row's.

### Delegating to a session (`agent$codex`, `brainyard.codex-agent`, `.6`)

agent$claude for Codex: `agents/codex.edn` runs its own loop,
`loops/codex.edn` — `codex$sessions`, predictor `codex/plan` (which session,
the brief, a worktree and branch), `codex$open` (or `codex$choose` when the
task answers a dialog the session waits on), then a round per turn of
`codex$await`, predictor `codex/progress` (done, guide, ask-person) and
`codex$say`, up to `:codex-max-rounds`; finished work on a branch gets
`claude/pr`'s title and description and `codex$handoff`; `codex$finish`
records how it ended in `.brainyard/codex-agents/<thread>.edn`. `/codex`
(`commands/codex.edn`) stands for it as `/claude` does for agent$claude.

What differs from agent$claude is what the sandbox decides (decision 7). A
delegated session starts with `-s workspace-write` and claude-agent's
`publish-guard` as its `PreToolUse` hook, a session flag with its trust record
(`codex-host/hook-flags`): it has no network and cannot write `.git` — measured
again with the git common directory given as `--add-dir`, `index.lock` still
refused — and a push anywhere in a command line is refused by the hook. So it
never publishes, and never commits: `codex$handoff` commits what it changed in
its worktree, with the pull request's title as the message, then hands the
branch back with its commits, the files changed and the commands that push
it, open its pull request and clean up — never run here. There is no
`publish` choice: the task cannot tell a Codex session to push. An approval
dialog is answered with `1`, `2` or `3`, the keys `y`, `p` and Escape. Its
spend since it began is priced from its rollout (`codex-usage`, below), and
past `:codex-max-cost-usd` ($5) the turn is over budget — never for a model
the price table does not price.

### Usage and the plan's limits (`brainyard.codex-usage`, `.7`)

What `claude-usage` is for Claude Code, with no call to anything: Codex writes
both into its rollouts. A `token_count` event carries the thread's running
`total_token_usage` — input including its cached reads, output including its
reasoning — and the plan's `rate_limits` as Codex last heard them (`plan_type`,
a `primary` and a `secondary` window, each `used_percent`, `window_minutes`,
`resets_at` in epoch seconds); the model is the newest `turn_context`'s. A
session's cost is priced from prices.edn's `:openai` table, cached reads at
their own rate; a model it does not price — Codex 0.163's default,
gpt-6-luna, measured — is unknown, never guessed. The plan's limits are the
newest rollout's newest `rate_limits`, read at most every 30 s (the footer
asks every frame): `/usage` adds them under "Codex plan <type>:", drawn as
Claude Code's windows are (5-hour, Weekly, 30-day), and the footer says
`⚠ Codex <window> <n>%` at 90% or more. Measured on a free plan: one 30-day
window, 1% used. The test runner points the JVM property `brainyard.codex-home`
at an empty home of its own, so no test reads the person's sessions or
limits.

Checked live with Codex CLI 0.163: a new session in a worktree on `cx/live`
wrote a file, was told to `git push`, and the hook refused it with the remote
left empty; the hand-off committed the file as "Add hello.txt" and gave the
publishing commands.

### What a rollout records (`.288.13`, `byj-1wv.56.7`)

A rollout is what a program that evaluates Codex's sessions reads, as it
reads a Claude Code transcript; byj reads it for the agent page, `session$*`
and usage. Its OTel (decision 9) carries its permission decisions, under
`conversation.id`. Its `PreToolUse` goes through the gates `byj hook
pretooluse` holds Claude Code's to (decision 6; `byj hook codex-flags` prints
the session flags; RFC-012 "Another agent's calls"), where `apply_patch` is
`update-file` for every file the patch names, a move's destination included.

Measured on 0.163 with one `codex exec` task, which also showed where a
rollout keeps what a run needs:

- a model call's usage is its own line (`token_usage_record`);
- a command (`CommandExecution`) carries its exit code and duration;
- a patch (`FileChange`) carries each file's unified diff;
- a hook is told `permission_mode "bypassPermissions"` under `approval_policy
  never`;
- two `rg` calls the model ran together hung until the turn ended, and were
  recorded with exit code -1 after its `task_complete`.

A live trial resolved its task for $0.0036 on gpt-5.6-luna.

A subagent was measured too (`byj-1wv.56.7`): a project's
`.codex/agents/<name>.toml` is spawned as its name. The parent's rollout
records a `CollabAgentToolCall` `spawn_agent` with the agent's
`agent_role` and its thread, and the thread is a rollout of its own, whose
`session_meta` carries `parent_thread_id` and
`source.subagent.thread_spawn.agent_role` (and `agent_path`,
`/root/<task>`). `codex-sessions/read-meta` reads them.

A session uses one of two multi-agent shapes, and which one varies from
session to session on the same machine (measured 2026-10-23):

- **v1** (code mode, `multi_agent_v1__spawn_agent`) writes the spawn as a
  `CollabAgentToolCall` item naming the thread and the role.
- **v2** (the `collaboration` namespace) writes it only as a `function_call`
  `spawn_agent` (`agent_type`, `task_name`). A `SubAgentActivity` item of
  the same id, `started`, names the thread and its `agent_path`; the call's
  output names the task. `wait_agent` is written beside a
  `CollabAgentToolCall` `wait` item of the same id.

Two more things were found recording a tour of a Codex session:

- The `workspace-write` sandbox keeps `.codex` read-only, so a session
  asked to make a project agent cannot write it there.
- Typed fast into the TUI, a line is taken for a paste, and Enter loses
  what it still holds. The tour pastes, bracketed, as codex-host does. A subagent's thread is no session of its own on the agent page or in
`session$list` (`saved` leaves it out), and `subagents` finds a thread's.

### Codex as a model provider (`codex`, `.9`)

Decision 8's `exec --json` answers a predictor or a turn, as `claude -p` does
for the `claude-code` provider, on the person's Codex sign-in: `-p codex`,
`/model codex/<model>`, `:default-lm "codex/gpt-5.6-terra"`. It is a model
there, not an agent:

- nothing is persisted (`--ephemeral`);
- the person's config, rules and hooks are not loaded
  (`--ignore-user-config --ignore-rules`), and the sign-in still comes from
  `CODEX_HOME`;
- the project's AGENTS.md is not read (`project_doc_max_bytes=0`);
- the shell tools are off and the sandbox is read-only;
- byj's system prompt goes as `developer_instructions`, and the conversation
  on stdin.

The reply is the last agent message, the usage `turn.completed`'s. The
mechanism and its measurements are RFC-006 "The codex invocation". Three were
found by running it on 0.163:

- `--output-schema` goes strict, so the ReAct reply's open `args` was refused
  and stays in the prompt.
- A ChatGPT sign-in takes only `models_cache.json`'s listed models: gpt-6-luna
  (the default), gpt-5.6-terra and gpt-5.6-luna. gpt-5.4 is refused.
- A call carries about 12–21k tokens of Codex's own instructions. `ask -p
  codex "What is 17*23?"` answered 391 in 10 s, 21,447 tokens in. A
  gpt-5.6-luna predictor call took 12,526 tokens in, priced at $0.0025.

## API

| fn | contract |
|---|---|
| `codex-sessions/read-meta`, `subagents` | a rollout's `{:id :cwd}`, with `:parent` and `:role` for a subagent's thread; the threads a thread spawned |
| `codex-sessions/codex-home` | `home` when given, else `$CODEX_HOME`, else `~/.codex` |
| `codex-sessions/live?` | `[home id]`: is the thread's writer lock held — a non-blocking shared `flock` through `jolt.ffi`, refused while it is |
| `codex-sessions/message` | a rollout line's `[:user text]` or `[:agent text]` — `item_completed` `UserMessage`/`AgentMessage`, or `user_message`/`agent_message` — or nil |
| `codex-sessions/saved` | `[project-dir & [home dirs]]`: `{:id :title :updated-at :bytes :file}` newest first, `:dir` for a worktree's |
| `codex-sessions/live` | the saved ones whose writer holds the lock |
| `codex-sessions/sessions` | `[project-dir & {:keys [home dirs live?]}]`: `{:live :saved}`, in the project and its worktrees, a live one not listed saved again |
| `codex-sessions/last-turn` | `[file]`: `{:prompt :answer :ended? :busy? :aborted?}` and `:usage`, read incrementally; nil with no file |
| `codex-sessions/rollout-file` | `[home id]`: the thread's rollout, or nil before it is first asked |
| `agent-host/*tmux*`, `attach-env`, `running`, `running-in`, `new-session-argv`, `kill!`, `rename!`, `capture`, `send-keys!`, `pane-exists?` | the tmux side, CLI-neutral |
| `agent-host/field-sep`, `tmux-format`, `tmux-fields` | a `-F` format's fields parted by `:|:`, never a tab, which tmux 3.2 and 3.3 print as `_` (`byj-gly.450`); a field that may hold a `:`, a path, last |
| `agent-host/type-and-confirm` | `{:type! :enter! :started :taken? :pause-ms :max-enters}`: a Task, `{:sent true :enters n}` (`:gone?`) or `{:error}` |
| `agent-host/await-settled` | `[observe {:settled? :waiting? :poll-ms :settle-ms :on-observe}]`: a Task, the observation with `:settled` `:done`/`:waiting`/`:gone` |
| `codex-host/start-argv`, `start!` | `{:dir :home :codex :resume :model :hooks :args :prompt}` → `{:name :known}` or `{:error}` |
| `codex-host/ready` | `[started {:home :resume :timeout-ms}]`: a Task, `{:id thread :trust-dialog?}`, the tmux session renamed for the thread, or `{:error}` |
| `codex-host/running`, `running-in`, `target`, `pane`, `stop!` | by thread id; a session still pending is not listed |
| `codex-host/observe`, `settled?`, `await-settled` | the rollout's newest turn with `:status` `:idle`/`:busy`/`:waiting` |
| `codex-host/type-line`, `choose` | one line, Enter after 200 ms, confirmed from the rollout; a dialog answered with a key |
| `codex-host/plan`, `run-plan!` | Enter on a Codex row: `{:start :attach :id :hint}` or `{:refuse}`, done with `agent-host/run-plan!` |
| `codex-host/claim-pending!` | pending sessions renamed for the threads their Codex holds; the threads claimed |
| `codex-host/with-running` | `sessions` with `:running` and each live one's `:status` |
| `tui/codex-session-rows` | `[sessions now attachable? new?]`: the Codex rows, `:codex-new`/`:codex-live`/`:codex-saved` |
| `codex-usage/session-usage`, `usage-cost` | a rollout's running totals and model; their cost by the `:openai` rates, nil unpriced |
| `codex-usage/limits`, `limit-lines`, `warning` | the plan's limits Codex last reported, as lines, and a footer word at 90% |
| `codex-agent/commands` | `codex$sessions`, `codex$open`, `codex$await`, `codex$say`, `codex$choose`, `codex$handoff`, `codex$finish`, under `:codex-tools?` |
| `codex-agent/open`, `await-turn`, `say`, `choose`, `handoff`, `finish`, `records`, `read-record`, `guard-file` | what the commands do |
| `codex-host/trust-hash`, `hook-flags` | the trust hash of decision 6, and the `-c` options carrying hooks and their records |
| `llm/create-lm :codex`, `llm/codex-cli-args`, `llm/codex-usage` | the codex provider (RFC-006 "The codex invocation"): an LM answered by `codex exec`, its argv, and `turn.completed`'s usage in the provider layer's shape |

The spike's `trust-hash` and `hook-flags` are the shapes `.3` and `.6` take
into `src`.

## Invariants

| invariant | enforced by |
|---|---|
| A project's sessions are the rollouts of it and its worktrees, newest first, titled by the thread's newest indexed name else the first question, Codex's instructions never; another project's and a cold rollout are not listed. | `codex-sessions-test/a-projects-sessions-are-its-rollouts-titled` |
| A session is live exactly while its writer holds its lock, and is listed live, not saved. | `codex-sessions-test/a-projects-sessions-are-its-rollouts-titled` (a lock the test holds through `jolt.ffi`) |
| The newest turn is read on as the rollout grows: prompt, newest answer, ended or aborted, usage; an older Codex's events read as items, a message written both ways counted once. | `codex-sessions-test/the-newest-turn-is-read-as-the-rollout-grows` |
| A Codex session starts in tmux with its home passed and its brief last; it is up when its writer lock is taken, past the folder dialog drawn over the main screen, and is then listed by its thread; a line is taken, its turn settles from the rollout, an approval dialog is waiting and Escape aborts the turn; stopped, it is neither running nor live. | `codex-host-test/a-session-starts-with-its-brief-last-and-its-home-named`, `a-session-is-driven-in-tmux` (on `test/fixtures/fake-codex`) |
| Enter on a Codex row starts a new one under a pending name, resumes a saved one in a session named for its thread, attaches a live one running here and refuses one elsewhere; a pending session is claimed for its thread by the lock its process holds. | `codex-host-test/enter-on-a-codex-row-plans-start-and-attach`, `a-session-opened-from-the-page-is-claimed-for-its-thread` |
| Codex's sessions are rows of their own after Claude Code's — new (when installed), live, saved — Enter hands one to `codex-open` and ctrl+s shares a live one. | `tui-test/codexs-sessions-are-rows-of-their-own-after-claude-codes`, `enter-on-a-codex-row-hands-it-to-codex-open-and-ctrl-s-shares-a-live-one`, `ctrl-s-shares-an-open-session-of-either-kind-and-nothing-saved` |
| agent$codex's loop starts a new session in a worktree of its own, judges each turn, guides it by a typed line, and hands the branch back; its commands are its alone and its tree is sound; the hand-off commits what the session changed and says how to publish it, pushing nothing. | `codex-agent-test/the-loop-briefs-guides-and-hands-back`, `the-hand-off-commits-what-the-session-changed-and-says-how-to-publish-it`, `agent-codex-runs-its-own-loop-with-its-commands`, `the-loop-is-sound` |
| A session's totals are its rollout's newest running total, priced by the `:openai` table with cached reads at their rate and unknown for a model it does not price; the plan's limits are the newest `rate_limits`, drawn as Claude Code's, a window at 90% a footer word; a delegation past its cap is over budget, never on an unpriced model. | `codex-usage-test/a-sessions-totals-and-their-cost`, `the-plans-limits-as-codex-last-reported-them`, `a-delegation-past-its-cap-is-over-budget` |
| A delegated session starts in the `workspace-write` sandbox where it can start; where it cannot, unsandboxed with the publish guard, which the delegation says and records — or, with `:codex-require-sandbox?`, none is opened and it says why; the sandbox is asked about only on Linux, by `codex sandbox -- true`. | `codex-agent-test/where-codexs-sandbox-cannot-start-the-session-has-the-publish-guard-alone`, `where-codexs-sandbox-starts-the-session-is-in-it`; `codex-host-test/codexs-sandbox-is-asked-whether-it-can-start-only-on-linux` |
| `/codex` stands for agent$codex as `/claude` for agent$claude. | `session-test/codex-drives-one-codex-session` |
| The codex provider runs `codex exec` as a model in the session's project — the person's config, rules, AGENTS.md and shell off, nothing persisted — its reply the last agent message, its schema sent only when strict-eligible, its usage with cached reads apart, priced by the `:openai` table; no message fails with what Codex said. | `codex-provider-test/a-turn-is-answered-by-codex-exec`, `a-strict-schema-is-the-replys-shape`, `a-reply-with-no-message-fails-with-what-codex-said`, `no-model-means-codexs-own-default` |
| A session's hooks are `-c` session flags with their trust records as one inline table, the hash the one Codex 0.163 recorded. | `codex-host-test/a-sessions-hooks-are-session-flags-with-their-trust-records` |
| Claude Code's host behaves as before over the shared tmux side, every tmux command in `attach-env`. | `claude-host-test` (`every-tmux-command-reaches-one-server` redefines `agent-host/attach-env`), `claude-agent-test`, `claude-reach-test`, `terminal-test` |

The measurements of decisions 1–10 are re-run by the spike, which spends
about twelve short Codex turns; nothing else enforces them. It heard
decision 9's OTel through the evaluation's receiver until that left byj
(RFC-070); a listener of its own does now (`byj-ahf.25`).

## Known gaps

- prices.edn does not price gpt-6-luna, Codex 0.163's default model, so its sessions' cost is unknown until it does — a price OpenAI publishes, not one guessed here.
- Whether Codex honours a hook's `permissionDecision "ask"` was not measured
  (`byj-gly.430.6`). The tool names were measured (`.288.13`): `Bash` and
  `apply_patch`.
