# RFC-019 — Background tasks

**Status:** implemented — `task$run`, the four inspection commands, auto-detach
(`byj-gly.13`) and harvest (`byj-gly.46`). Parking a turn on a task
(`task$wakeup`) and streamed output are not ported (gaps below).

## Purpose

Specifies what it means for work to outlive the tool call that started it: what
a task is, how one is started, watched and stopped, and what the model is told
when a call it made is still running. brainyard sources:
`agent/task/{protocol,manager,executor,commands,persist}.clj`, and the
auto-detach path in `core/tool.clj` (`call-tool-with-fast-eval`,
`adopt-tool-into-task`); docs `core/task.md`, `specs/task-manager.md`.

## Scope

**This layer decides** what a running task is, when a tool call becomes one, and
what a turn may learn about it.

**It must not know** what any job does — a job is a thunk producing a value.

**It hands** commands to the tool registry (RFC-007) and a marker string to the
loop (RFC-003) in place of a result it could not wait for.

## Model

### A task is an effect with a record attached

```clojure
{:id "task-mkq8f3x2-7"  :name "bash: make build"
 :job-type :bash        :status :running
 :created-at … :started-at … :completed-at …
 :result {:exit-code 0} :output "…" :output-lines 42
 :cancel (fn []) }
```

`:status` is brainyard's `:pending → :running → :completed | :failed |
:cancelled`. The registry is an atom in `ctx` (`:task-registry`), created per
session and passed down to subagents, exactly as the instance registry is
(RFC-018) — one session, one list of what it started.

**Ids are `task-<base36 millis><counter>`.** brainyard's are
`task-<base36 millis><3 random base36>`, minted off a strictly-increasing millis
ratchet so they sort by age. The ratchet is ported; the random tail is a counter
instead, because `rand` is not a source of identity on a jolt worker thread
(provenance P-13).

### What can run in the background

`task$run` takes brainyard's two job types and no others:

| `job-type` | takes | is |
|---|---|---|
| `"bash"` | `command`, `timeout?` | a subprocess, cancelled by destroying its tree |
| `"tool"` | `tool-id`, `tool-args?`, `timeout?` | any registered tool, called through `call-tool` — so its hooks and its permission gate still apply |

brainyard has five more job types (`:cli-client`, `:clj-sandbox-eval`,
`:clj-nrepl-eval`, `:fn`, `:a2a`) that its own commands do not expose either.

### A slow tool call detaches itself

This is the half that matters, and the model does not ask for it. It happens in
brainyard's **two stages**:

| at | what happens |
|---|---|
| `:fast-eval-timeout-ms` (30s) | the call is **adopted into a task** — an id, a row in `task$list`, a canceller — and the caller keeps waiting |
| `:auto-background-timeout-ms` (180s total) | the caller gives up on it and the model gets the marker |

**The person can end the wait sooner** (byj-gly.391), as Claude Code's ctrl+b
does; here the key is ctrl+g, since tmux takes ctrl+b.
- While a call is waited on, `detachable` keeps a release for it in the
  session's `:backgrounders`, a seam beside `:cancellers`. A front end calls
  every release, and the waits end at once.
- Released before the first deadline, the call is adopted now. Released
  between the two, the wait ends now.
- Either way the model reads the marker prefixed "The person moved TOOL to the
  background" (`moved-marker`), so it neither calls the tool again nor takes
  the move for a timeout. `:task/detached` says `:by :person`.
- A call that does not detach — a `task$*` command, a subagent's call, or
  one inside a task — is never offered.
- Two calls waited on at once are released together.
- The full screen binds ctrl+g (RFC-028). An inline session cannot: it reads
  no keys while a turn runs, and ctrl-c reaches it only as a signal
  (byj-gly.392).

Under the first deadline nothing is different: the call returns inline. Between
the two it is already a task, but still this call's answer if it lands in time,
and the task is marked harvested so the next iteration does not announce what
the caller already has. Only past the second does the model read:

```
[bash STILL RUNNING — task-id=task-mkq8f3x2-7. DO NOT re-call bash — it is
already running. Wait for it with task$wait {"task-id": "task-mkq8f3x2-7",
"timeout-ms": 60000}, peek with task$detail, or stop it with task$cancel.
Its result is NOT lost: it arrives when you wait for it.]
```

A tool call is **not** detachable when:

| | why |
|---|---|
| it is already running inside a task | a task within a task has nobody to report to |
| its caller is a subagent | a subagent's turn ends before it could harvest, orphaning the task (brainyard's rule) |
| it is a `task$*` command | `task$wait` detaching itself is a deadlock with extra steps |
| `:tasks? false` | the whole layer is off |

Because a `task$*` call never detaches, `task$wait` holds the turn for as
long as it waits. Its `timeout-ms` is clamped to `:auto-background-timeout-ms`
(180 s by default), the most any other call is waited on (byj-gly.390). A
wait asked for longer comes back at the cap with the task still running and a
note saying to park with `task$wakeup` and end the turn, to be resumed when the
task ends.

Both stages matter. A subagent dispatch takes minutes, so with only the first
one a dispatch would be handed back as a marker every time — measured live, and
the reason this is two stages rather than one (`byj-gly.50`).

**Cancelling the turn still kills the call while it is inline.** The wait and
the work share a canceller until the moment of detach, so RFC-009's guarantee —
a cancelled turn leaves no subprocess behind — holds for every call that has not
yet become a task. After detach the task outlives the turn, which is the point,
and `task$cancel` or the session's close is what ends it.

### Output arrives as it is printed

A bash task's stdout reaches its record line by line, so `task$detail` shows
what a task has printed **so far** and `:last-output-age-ms` says how long since
it last printed anything. That, not elapsed time, is what separates a working
task from a wedged one, and the guidance the model reads says so: a task can be
quiet for minutes and be perfectly healthy, so a quiet window alone is not a
reason to cancel it.

The reading is `fx/streaming-process` (RFC-006), which takes the raw
`InputStream` because every tidier reader buffers until the process exits.
Output is capped at `:max-task-output-chars` (30000) as it accumulates, and the
whole thing is written to `<project>/.brainyard/tasks/<id>/output.log` when the
task ends.

brainyard keeps a 500-line ring in memory and appends to `output.log` per line,
so its log is readable during the run and its `:truncated?` says when the tail
has outrun the cache. Here the file is written once at the end; the live view is
the record, which is capped rather than ringed.

### What finished while the model was looking elsewhere

A detached result is no use if collecting it depends on the model remembering to
ask. So at the start of every iteration the loop harvests — brainyard's
`harvest-pending-tasks!` and `resolve-tool-entries`, as one tree node:

1. **The marker is rewritten.** A `STILL RUNNING` marker in the iteration
   history whose task has since finished is replaced by the result it was
   standing in for. The model's own record stops saying "still running" about
   something that is done.
2. **Completions are announced.** Anything terminal that has not been reported
   is queued as a notice on this iteration, once — brainyard's `:harvested?`
   flag — with its result and output.
3. **What is still going is named**, with how long it has been going, so a model
   deciding whether to answer knows what it is not waiting for.

Only the owner harvests: a task carries the instance id of the turn that started
it (`"root"` for a root turn), so a subagent never collects its caller's work.
`start!` takes an `:owner` of its own for a task no turn owns: self-improvement's
judges run as `:self-improve` tasks owned by `"self-improve"`, which no turn
harvests and the model's `task$list` leaves out; `/task` shows them to the
person (RFC-017, `byj-gly.247`).

**The rewrite touches only the history the model reads.** `:trajectory` keeps
the marker, because the marker is what actually happened at that moment, and a
trajectory that edits itself is no longer evidence.

Two consequences worth stating: a task that finishes during the final iteration
would never be reported — nothing runs after it — and a task nobody waits for is
reported at most once, at the next iteration boundary. The first is what
auto-notify covers, below.

### Parking a turn on a task

`task$wakeup` is brainyard's alternative to waiting: the model answers in one
line, **stops**, and is given a fresh turn when the task finishes. It is what
makes a long task affordable in a session, where `task$wait` would hold the
whole thing open doing nothing.

A wakeup is delivered as **an input line the front end injects**, so the session
loop needs no new node: `tasks/ready-wakeup!` returns the resume turn once the
task is terminal, and the reader hands it over exactly as if it had been typed.
The turn's question IS the wakeup message, so it lands in history like any
other, carrying the note the model left itself — and the request the turn
parked from, in full (byj-gly.389, divergence `a-wakeup-carries-its-request`).
A woken turn reads earlier questions through the history, which cuts each to
500 characters. A `/team` request's closing steps — check the answers, merge,
`team$close` — were past the cut, so the woken turn finished without closing
the team. The words are `prompts/task-wakeup.edn`'s.

```
[task-complete] Background task task-mkq8f3x2-7 finished (completed).
Your note: check the build. Pick up what you parked: read its result with
task$detail if you need it, then carry on from where you stopped.

The request you parked from, in full — finish it as it asks:
build it and tell me what failed
```

A woken turn that parks again passes the original request on
(`tasks/original-request`), not its own wake-up text nested in the next.

**A wakeup whose task was already read is taken back** (byj-gly.388). When a
turn gets an ended task's result — `task$wait` until it ends, or
`task$detail` on it once it has — any wakeup still armed on that task is
removed, and `:task/wakeup-disarmed` says so. A team's lead armed one per
member, was woken by the first, waited for the second in that same turn and
finished the work; the second wakeup then started a turn with nothing to do.
A task read while still running keeps its wakeup: that turn did not yet have
what the wakeup is for.

### Parking the model does not ask for

Two things happen without the model's cooperation (`byj-gly.47`), both gated by
`:task-notify?` (default true).

**A turn that keeps polling one task is parked.** After
`:auto-park-after-polls` (2) polls of the *same still-running* task in one
turn — `task$detail` or `task$wait`, the two commands that target one task;
`task$list` is a survey and is not counted — the call is blocked and the turn
ends with an answer saying so. The first poll is always allowed, so the model
sees real liveness, and **a task that has finished is never deflected**: the
poll goes through and the model gets its result, which is what keeps a park
from ever hiding an answer.

It is a built-in hook (`task-poll-park`, RFC-011) at priority **50** — below
every gate, because it is an optimisation and must never decide before a
refusal does. For the same reason it is the one built-in hook that **fails
open**: a throw there lets the poll through rather than costing the turn, where
a gate that throws must not quietly allow (`permission-test/the-gates-ship-inert`).

**A turn that ends leaving work running arms one wakeup.** That is the gap
above — a task finishing after the last iteration. The note names every task
left running; the first to finish wakes the session, and `harvest-tasks` folds
in the rest on the turn that wakes.

**Unless the turn set its own wake signal** (`byj-gly.332`). A
`schedule$watch` set during this turn (its `:created` at or after the
turn's `:started-at`, in this session) is how the turn chose to be woken, so
no wakeup is armed beside it, and `:task/auto-arm-skipped` names the tasks
and the watches. With both armed, live, the task's wakeup came first: the
model waited out the rest of the work in that turn with `task$wait`, which
the watch was set to avoid, and the watches then brought news it already
had.

Both park through `tasks/park!`, onto the list `task$wakeup` arms: one wakeup
list, one delivery path, one budget, one refusal rule. So neither happens where
nothing can resume a parked turn — a one-shot `ask`, or a subagent whose turn
ends first — because parking a turn nothing will wake is worse than polling
(divergences `auto-park-reuses-the-wakeup`, `task-notify-arms-once-per-turn`).
A task started `:quiet?` is not armed for, and the per-iteration notice names
it neither running nor finished: one that is the machinery of other work
rather than a result, which the model has no reason to wait on (provenance
P-35: a share's sidecar was announced every iteration). A team member's process ends when its team closes,
and the asks a team's board makes are waited on through the board's own task
(RFC-032). A team run in tmux woke its leader for a turn nobody asked for when
the members stopped.

On a terminal, the inline reader reads fd 0 itself, a byte at a time, and
while nothing has been typed it asks every 60 ms whether a key is waiting
(`poll(2)`, `line-input/key-waiting?`) and, between looks, whether a wakeup is
ready. A wakeup fires when something else finishes and cannot wait for the
person to press return.

It used to poll only while `.ready` on stdin said nothing was waiting. On
jolt, `.ready` is true before anything is typed, so the prompt never polled,
and a timer's parked turn waited for the person's next line. Chez's
`input-port-ready?` does not help either: it says false with input waiting.
Measured, byj-gly.315.15. So the reader reads past every buffer, where poll
can see what is waiting. A piped session reads to the end of its pipe as
before.

Refused when there is nowhere to resume into: a one-shot `ask` has no later
turn, and a subagent's turn ends before a wakeup could fire (brainyard's rule).
`:max-wakeups-per-session` (20) bounds how many turns a session may park.

### Pausing a turn is not built (`byj-gly.145`)

brainyard's `/pause` parks a running tree at its next node boundary on the
runtime's pause condition, and `/continue` releases it; Ctrl-C still cancels a
paused run. Decided, 2026-10-01, not to build it now:
- Ctrl-C cancels a turn, and a resumed question carries on from its history;
- `ask-each-time` stops before every tool call, which is where a person most
  wants to look before the agent goes on;
- a turn that must wait parks on a task here (above), without holding a
  thread.

It can be built if a need shows: the interpreter would park on an ebb gate at a
node boundary, which is cheap on a fiber (divergence `no-pause-command`).
brainyard's other `/continue` — re-ask a question with N more iterations after
it ran out — is not built either.

### Ending

`task$cancel` cancels the effect, which destroys a bash task's process tree
(RFC-009), and the task settles `:cancelled` with `{:error "cancelled"}`.
Cancellation is requested, not awaited (RFC-009 rule 8).

**A session closes its tasks.** `session/close` cancels everything still
running, because jolt has no shutdown hook that would kill an orphaned
subprocess for us. A one-shot `ask` does the same at the end of its turn.

At startup, `.brainyard/tasks/` is pruned with the sessions (RFC-016): past
`:task-retention-days` (7) and outside the newest `:task-retention-count` (100),
which are brainyard's own numbers.

### Prompts set to a time: `schedule$*` and `/loop` (`byj-gly.325`)

`brainyard.schedule` ports brainyard's `agent/common/schedule.clj`: its
names (`schedule$add|list|remove|enable|disable|run-now`), its store
(`.brainyard/schedule/<id>/spec.edn`, `:id :title :prompt :cron :fire-at
:enabled :next-fire :last-run :last-status :created`), and its five-field
cron. It also ports Claude Code's `/loop`.

**A job is a turn of the session that made it** (divergence
`schedule-fires-into-the-session`). brainyard runs a due job as a separate
agent run on a daemon thread and writes its answer to a file. Here a job
carries `:session`, and when due its prompt is typed into that session as if
by the person, between turns. Each front end asks `schedule/ready-turn!`
whenever it is idle, in the same chain as a parked turn's wakeup and a
channel turn (`main/ready-turn`); this replaces brainyard's ticker thread.
- A job fires only while its session is open. One due longest goes first
  (run-now first).
- A recurring job advances to its next minute. A one-shot is removed once it
  fires.
- A recurring job expires after seven days (`:expires-at`, Claude Code's
  rule), and is removed then rather than run.
- Cron is evaluated in local time. jolt's java.time has neither a zone nor
  `ZoneOffset` (provenance P-10), so the offset is the shell's `date +%z`,
  kept by `brainyard.clock` and asked again every ten minutes, so a
  daylight-saving change is noticed within them; the calendar is plain
  arithmetic.
- The tools exist for a session's top-level agent only: a subagent's turn
  ends before a job could fire. `schedule$add` refuses a one-shot run, which
  has no later turn.

**`/loop [interval] <prompt>`** does what Claude Code's does:
- **An interval**, as a leading `5m` or a trailing `every 20 minutes`, is
  turned into a clean cron (`interval->cron`). An interval that does not
  divide its unit is rounded to the nearest that does, and the person is
  told. The prompt is scheduled, and runs now.
- **No interval** runs the prompt now with `prompts/loop.edn`'s self-paced
  words. The model then arms the next iteration itself, a one-shot
  `schedule$add` of the same `/loop` line after a delay it chooses (1 min to
  1 h, matched to what it waits for), or stops the loop by not arming one.
  That line comes back through `/loop`.
- `/loop` is a turn whose input is rewritten (`session/loop-request`, before
  `tool-request`); a bare `/loop` is the command that prints its usage.
  `/schedule` lists this session's jobs, and `/schedule remove ID` stops one.

**`schedule$watch`** (`byj-gly.331`) ports brainyard's `watch$add`: a probe
on a cadence, and a predicate that decides when it fires. Like a job, a
watch fires as a turn of its session, the way Claude Code's Monitor wakes its
agent; brainyard instead fired `:emit` on its hooks bus.
- **The probe** is a `:cmd`, run in the project with `sh -c` every `:every`
  (30 s by default, 5 s at least) or on a `:cron`, its output and exit kept.
  Or it is a `:path`, whose modification time is kept.
- **The predicates** are brainyard's: `:changed` (the default), `:matches`
  (`:re`), `:zero-exit`, `:nonzero-exit`, `:increased`, `:threshold` (`:cmp`
  `:value`). The first look is the baseline for `:changed` and `:increased`.
  brainyard fired on it, so a watch for a change fired at once.
- **When it fires,** `prompts/watch.edn` gives the turn its words: what was
  probed, why it fired, what it saw, the agent's own note. That turn waits in
  `:pending` and is delivered before any due job.
- **It ends:** `:once` (the default) removes it when it fires, and it lives
  `:for` (a day by default).
- **The probe never runs on the poll.** The TUI asks `ready-turn!` from its
  drawing thread, so a due watch's next look is set and its probe started in
  the background (`fx/run-detached`). The next poll delivers what it found.
- **Its `:cmd` is held** by the same command gate as a `bash` call
  (`:command-params [:cmd]`). It is checked once, when the watch is set.

**Checked live, 2026-10-09**, in an inline session:
- On the mock provider, `/loop 1m say hello` ran at once, and again by
  itself at the next minute (`you> say hello`, one earlier turn in context).
  `/schedule remove` then left none.
- On claude-code/haiku, a self-paced `/loop` that waits for a file:
  - The first check found no file and armed one `/loop` wakeup 20 min out,
    saying why ("an idle wait… 20m to 30m").
  - With the file written and that wakeup brought forward (`:schedule$run-now`),
    the wakeup came back through `/loop`, reported the file's contents, and
    armed nothing, so the loop ended with no job left.
- The first live attempt found the tools registered (`/tools` listed them)
  but missing from the prompt's tool listing: `make-ctx`'s `listed` names
  each command group, and `schedule-cmds` was not among them. Haiku said it
  had no `schedule$add`, and the loop ended at once
  (`schedule-test/the-model-is-shown-the-schedule-tools`).
- Monitoring jobs as Claude Code does (`schedule$watch`), on
  claude-code/haiku:
  - The model started two `task$run` jobs, a build (30 s, "128 tests
    passed") and a deploy (50 s, "FAILED"), set one watch on each log
    (`test -f build.log && cat build.log || echo pending`, every 10 s), and
    ended its turn.
  - Each watch woke the session when its log landed, and the model
    reported each result.
  - The task's own auto-armed wakeup (above) woke it first, at the build's
    end. In that turn it waited for the deploy with `task$wait` and read both
    logs, so the watches then brought news it already had. Since then, a turn
    that set a watch arms no wakeup of its own (`byj-gly.332`).

## Protocol

```
tool-dispatch (RFC-004)
  └─ tasks/detachable
      ├─ starts the call as an effect, waits :fast-eval-timeout-ms
      ├─ settles in time  → the result, inline, as before
      └─ does not         → register a task, return the marker

task$run    → the same registry, entered deliberately
task$wait   → waits for a terminal status, or reports still-running; at most :auto-background-timeout-ms
task$detail → status, :elapsed-ms, :result, :output (once terminal), :output-file
task$list   → what this session started
task$cancel → cancels the effect; a bash task's process tree dies with it
task$wakeup → parks this turn; the front end resumes it when the task ends
```

Events: `:task/created` `{:id :name :job-type}`, `:task/completed`
`{:id :status :elapsed-ms}`, `:task/detached` `{:id :tool}`,
`:agent.tasks/harvested` `{:completed :running}` — brainyard's
`:task/created` and `:task/completed` hooks, plus one for the moment a call
stops being a call.

## API

`brainyard.tasks`: `task-id`, `registry`, `owner-of`, `start!`, `snapshot`,
`summaries`, `wait`, `cancel!`, `cancel-all!`, `bash-task`, `detachable?`,
`detachable`, `marker`, `resolve-markers`, `harvest!`, `notice`, `took`,
`commands`, `tasks-dir`.

The loop's side is one node, `react/harvest-tasks`, and
`brainyard.agent/harvest-tasks-action` behind it.

Config (RFC-013): `:tasks?` (true), `:max-wakeups-per-session` (20),
`:fast-eval-timeout-ms` (30000),
`:auto-background-timeout-ms` (180000),
`:task-timeout-ms` (120000), `:max-task-output-chars` (30000),
`:task-retention-count` (100), `:task-retention-days` (7).

## Invariants

| invariant | enforced by |
|---|---|
| A cron names its next local minute; an interval becomes a clean cron, rounded and said; a due job is its session's next turn — the one due longest first, a cron advanced, a one-shot removed, another session's not fired, a disabled one not, an expired one removed; the tools refuse where no turn would run. | `schedule-test/a-cron-names-the-next-minute-it-fires`, `schedule-test/an-interval-is-a-clean-cron-as-claude-codes-loop-makes-it`, `schedule-test/a-due-job-is-its-sessions-next-turn`, `schedule-test/the-commands-refuse-where-no-turn-would-run` |
| A turn that set a `schedule$watch` arms no wakeup for the tasks it leaves running, and says so; a watch from an earlier turn or another session does not count. | `tasks-test/a-turn-that-set-a-watch-is-woken-by-it-not-by-its-tasks` |
| A watch fires on brainyard's predicates, its first look the baseline; an unchanged look fires nothing, a change waits for the session's next idle moment and is its turn, said in the watch's words, a one-shot then gone; the idle poll starts a due probe in the background and does not wait on it or start it twice. | `schedule-test/a-watch-is-told-what-changed-as-claude-codes-monitor` |
| The model is shown the schedule tools; a subagent has none. | `schedule-test/the-model-is-shown-the-schedule-tools` |
| `/loop` with an interval schedules the prompt for this session and runs it now, the model asked the prompt alone; with none, runs it self-paced, told the exact line to re-arm with; `/schedule` lists and removes; a bare `/loop` says how. | `session-test/loop-runs-a-prompt-now-and-again-as-claude-codes-does` |
| A parked turn runs at an idle inline prompt with nothing typed, and a line typed after it is read as before. | `line-input-test/a-parked-turn-wakes-an-idle-prompt` |
| `task$list` lists every kind of task as its `:output-schema` says — a team member's, a dispatcher's, a session-ask's, not only what `task$run` starts. | `tasks-test/every-kind-of-task-is-listed-as-its-schema-says` |
| **The second poll of one still-running task ends the turn and arms a wakeup**; the first does not, and a finished task is never deflected. | `tasks-test/polling-one-task-twice-parks-the-turn`, `tasks-test/a-finished-task-is-never-deflected` |
| Polls are counted per task and per turn, and `task$list` is not a poll. | `tasks-test/polls-are-counted-per-task-and-per-turn`, `tasks-test/task-wait-is-counted-too-but-a-survey-is-not` |
| **A run that cannot resume is not parked**, and neither a subagent's polls nor its leftovers are. | `tasks-test/a-run-that-cannot-resume-is-not-parked`, `tasks-test/a-subagent-is-not-armed-and-is-not-parked` |
| A turn that leaves work running arms **one** wakeup naming all of it, and not a second for a task already parked on. | `tasks-test/a-turn-that-leaves-tasks-running-arms-one-wakeup`, `tasks-test/a-task-already-parked-on-is-not-armed-twice` |
| A quiet task is neither armed for nor named in a wakeup. | `tasks-test/a-quiet-task-is-not-armed` |
| A quiet task is not named in the notice, running or finished, and is still marked harvested. | `tasks-test/a-quiet-task-is-not-news-to-the-model` |
| Auto-park runs below every gate and fails open, where a gate fails closed. | `permission-test/the-gates-ship-inert` |
| A task runs past the tool call that started it, and its result is retrievable afterwards. | `tasks-test/a-slow-call-detaches-and-keeps-running` |
| Under the deadline nothing changes: the call returns inline, with no task registered. | `tasks-test/a-fast-call-is-untouched` |
| A running task shows what it has printed so far, and how long since it printed it. | `tasks-test/a-task-shows-what-it-has-printed-so-far` |
| A parked turn resumes when its task ends, carrying its note, once, and is refused where nothing could resume it. | `tasks-test/a-turn-can-park-itself-on-a-task`, `tasks-test/a-one-shot-run-refuses-to-park` |
| The session runs a turn nobody typed, and it lands in history like any other, carrying the request it parked from in full. | `tasks-test/a-parked-turn-resumes-the-session`, `a-woken-turn-is-given-the-request-it-parked-from` |
| `task$wait` waits at most `:auto-background-timeout-ms`, whatever it is asked; past the cap it reports still-running and says to park with `task$wakeup`, and within it it says nothing more. | `tasks-test/a-wait-is-held-to-the-most-a-call-is-waited-on` |
| The person's release ends a waiting call's wait at once — adopted now if it was not yet, its marker saying who moved it — while the work runs on; a call that does not detach is never offered, and calls waited on together are released together. | `tasks-test/the-person-moves-a-waiting-call-to-the-background` |
| A wakeup is taken back once its task's result is read — `task$wait` to its end, or `task$detail` on it ended — and a running task's stays. | `tasks-test/a-task-already-read-does-not-wake-the-turn-again` |
| Between the two deadlines the call is a task AND still the caller's answer, and what the caller collected is not announced again. | `tasks-test/a-detached-call-is-still-waited-for` |
| Past both, the marker comes back and the turn goes on. | `tasks-test/past-both-deadlines-the-marker-comes-back` |
| A cancelled turn still kills a call that has not detached, subprocess and all. | `cancellation-test/a-cancelled-turn-kills-its-bash-tool` |
| `task$cancel` ends a running task and its process tree; the task reports `:cancelled`. | `tasks-test/cancelling-a-task-kills-its-process-tree` |
| `task$run` starts bash and tool jobs, reports unknown ids and bad args, and a tool job goes through the permission gate. | `tasks-test/task-run-starts-a-job` |
| `task$wait` returns a terminal task at once, waits for a running one, and reports still-running on timeout without cancelling it. | `tasks-test/waiting-on-a-task` |
| A task's `output.log` and `meta.edn` are on disk **before** its status goes terminal, because that status is what a waiter is watching. | `tasks-test/a-settled-task-has-its-files-before-it-has-its-status` |
| A task started inside a task, or by a subagent, does not detach. | `tasks-test/what-never-detaches` |
| Closing the session cancels what it started. | `tasks-test/a-session-closes-its-tasks` |
| Ids sort by age and are unique without relying on `rand`. | `tasks-test/task-ids` |
| Task directories are pruned at startup by their own count and age bounds. | `tasks-test/task-directories-are-pruned-at-startup` |
| A finished task's result replaces the marker it stood behind, and nothing else in the history changes. | `tasks-test/a-finished-task-replaces-its-marker` |
| A completion is announced once, and only to the owner that started it. | `tasks-test/a-completion-is-reported-once`, `tasks-test/only-the-owner-harvests` |
| A detached result reaches the model's history without it calling `task$wait`, while the trajectory keeps the marker. | `tasks-test/a-detached-result-comes-back-without-being-asked-for` |

## Known gaps

- **A wakeup does not survive the process.** brainyard's are in-process too —
  its watches and auto-notify arms are not persisted — so a parked turn is lost
  if the session ends before its task does.
- **No deflect step before the park.** brainyard's middle branch replaces a
  poll's result with advice and lets the turn continue; at its default of 2 that
  branch is unreachable anyway (poll 1 allows, poll 2 parks), so the port has
  the two outcomes that its default actually produces.
- **The log is written once, at the end.** brainyard appends to `output.log` per
  line, so a long task's output can be read from disk while it runs; here the
  live view is the record and the file arrives with the result.
- **No `task$sweep`**: retention is enforced at startup, not on demand.
- **No retry, no display modes.** A task's `:schedule` is inert in brainyard
  too, and nothing consumes it there either. Prompts set to a time are their
  own store, below.
- **Tasks do not survive the process.** brainyard's do not either — its
  `meta.edn` is a post-mortem record, not a resume point.
