# RFC-009 — Effects and concurrency

**Status:** implemented. The substrate is decided (`byj-gly.1`), the
`brainyard.effect` brick is built (`byj-gly.26`), and the loop runs on it
(`byj-gly.27`).

## Purpose

Decides what replaces missionary and core.async in this port, and specifies the
effect layer every concurrent subsystem sits on: streaming, background tasks,
subagents, MCP, the code channel, and cancellation of a running turn.

brainyard sources: `components/effect` (`ai.brainyard.effect.interface`, core
`prim`, `policy`, `flows`, `supervisor`, `smoke`) and
`docs/design/functional-effect-system.md` ("an effect is a **value**,
composition is by **function**, and cancellation is **structural**", L14-16).

## Decision

**ebb** (`jlt-commons/ebb`, missionary ported to jolt) is the substrate, behind a
`brainyard.effect` brick that ports brainyard's `ai.brainyard.effect.interface`
function for function.

- **Not core.async.** It has no cancellation model: a `thread` that lost an
  `alts!!` keeps running (measured). brainyard itself keeps core.async only for
  memory capture's decoupled fan-in (design §6, L370-391), which is where it may
  return with `byj-gly.19`, under the same rule: *a channel where producers and
  consumers must be decoupled; an effect value where work is composed, cancelled
  or retried as a unit.*
- **Not bare futures.** `future-cancel` interrupts, but a future is not a value:
  it cannot be timed out, raced, joined, bounded or retried by composition, and
  it cancels nothing it started (the subprocess row below).

Why ebb:

| requirement | evidence |
|---|---|
| Covers brainyard's surface | brainyard calls 21 missionary operators in 9 files — `sp ? via blk cpu sleep timeout join race dfv ap amb ?> ?< seed reduce eduction watch latest` and `Cancelled` — every one present in ebb, which ports *every* public var of missionary. |
| Is missionary, not a lookalike | ebb runs missionary's own test namespaces: 281 tests, 196 from missionary (ebb README). Its divergences are enumerated in `doc/conformance.md`. |
| Cancellation reaches this port's waits | Measured below. |
| Survives `jolt build` | The spike built into a 29 MB binary and reproduced the interpreted table row for row, run from `/tmp`. brainyard needed a native-image class-init carve-out and a `by effect-smoke` gate for missionary (design §7); jolt needs neither. |
| Cheap | Requiring `ebb.core` adds ~10 ms to a warm start (0.10 s vs 0.09 s). |
| Proven on jolt | samizdat adopted it for its turn path (its RFC-013). |

## Measurements

`dev/brainyard/dev/effect_spike.clj` (`jolt -A:dev -m
brainyard.dev.effect-spike`), jolt 0.8.8, ebb v0.1.3, http-client v0.0.8,
2026-09-17. Each wait is cancelled at 500 ms; the slow server answers at 5 s.

| wait | settled after cancel | the waiter saw | notes |
|---|---|---|---|
| ebb `sleep` on the fiber | 0 ms | Cancelled | |
| `via blk` `Thread/sleep` | 0 ms | InterruptedException | |
| `via blk` promise deref | 0 ms | InterruptedException | |
| `via blk` subprocess deref | 0–13 ms | InterruptedException | **child still running** |
| `via blk` subprocess, canceller calls `destroy-tree` | 1–12 ms | InterruptedException | child gone |
| `future-cancel` subprocess deref | 3–4 ms | — | **child still running** |
| `via blk` http-client recv | 250 ms | InterruptedException ("read interrupted") | one read slice |
| `timeout (via blk http recv) 500` | 250 ms past the deadline | the fallback value | the parent waits for the child's slice |
| `future-cancel` http-client recv | 249 ms | — | same slice |
| `join` of two 300 ms sleeps | 306 ms total | `[:a :b]` | parallel |
| `race` 50 ms vs 500 ms | — | the 50 ms value | |
| `?` inside a helper fn called from `sp` | — | works | ebb parks at any depth |
| dynamic binding read inside `via blk` / `via cpu` | — | **root** since jolt 0.8.10 | rule 5, `ebb-bindings-test/via-does-not-convey` |
| dynamic binding read after a park in `sp` | — | the caller's binding, before and after | rule 5, pinned by test |
| dynamic binding read inside a thunk handed to `task-of` or `tasks/detachable` | — | the caller's binding, on either branch | rule 5, `ebb-bindings-test/the-port-installs-the-frame-itself` |
| 200 fibers on 14 carriers, each bound to its own value, interleaved parks | — | every fiber its own value; none resumed on another thread | bindings are fiber-local |
| task built under a binding, run outside it | — | the root value | conveyance is captured at run time |
| park inside `mapv` in `sp` | — | RuntimeException "a fiber cannot leave the CPU while its carrier holds a counted lock" | see rule 3 |
| core.async `thread` losing `alts!!` to `timeout` | — | `:timeout` | the thread is not cancelled |

## Model

A **Task** is `(fn [success failure]) → canceller`; a **Flow** is missionary's
`(fn [notifier terminator]) → iterator`. Nothing runs until something runs it.
Coroutine macros (`sp`, `ap`, `?`, `?>`, `?<`, `amb`, `via`) are used from
`ebb.core` directly — as brainyard requires `missionary.core` directly for them,
because a macro cannot be re-exported without becoming a second thing to keep in
sync (`interface.clj:23-29`). Everything else goes through `brainyard.effect`.

### The brick: `brainyard.effect`

Ports `ai.brainyard.effect.interface`, keeping its names and contracts:

| group | fns | brainyard consumers today |
|---|---|---|
| running | `run`, `run!!` (`{:ok}`/`{:err}`/`{:timeout}`/`{:interrupted}`, cancels on the non-settling exits, never throws), `run-value!!` (the value, or throws — InterruptedException for an interrupted wait; brainyard writes this inline in `retry-with-backoff`), `run-detached` | agent, llm, task commands |
| entering | `task-of` (thunk on `blk`, conveying bindings), `success`, `failure`, `from-promise` | bt, coact, executor, nodes_task |
| policy | `timeout`, `retry-backoff`, `bounded` (replaces `pmap`), `all`, `race`, `delayed`, `cancelled?` | llm, coact, sandbox, task commands |
| flows | `ticking`, `poll-until`, `watch-until`, `watch-flow`, `debounce` | TUI session, executor, task commands |
| supervision | `start!`, `ensure!`, `running?`, `stop!`, `stop-all!`, `running` | task manager, TUI session |

Also `ticker` (a discrete Flow of 0, 1, 2 …).

Plus one addition brainyard's JVM did not need: **`process-task`** — run a
subprocess as a Task whose canceller also `destroy-tree`s it (rule 1); a cancel
that lands before the process starts is honoured when it starts.

And **`start` / `sh`** (`byj-gly.259`, provenance P-21): jolt's `process`
used to leave a child's three pipes open after it exited — every subprocess cost
two file descriptors, and a session running `claude -p` for each model call
reached the per-process limit. `start` is the one way a process starts: it owns
stdin — writes a string `:in` itself and closes the pipe at once, exactly once.
Stdout and stderr are jolt's to close, which it does as of 0.8.15, the
`:jolt/min-version` floor (jolt-lang/jolt#1175: `InputStreamReader.close` did
not close the stream it wrapped, so the `slurp` behind `:out :string` never
closed the pipe). Until then `release!` closed them; it went with
`byj-gly.262`. `sh` runs a short command to its end. No namespace but this one
runs `jolt.process` (`effect-ratchet-test`).

**`start` never lets a child ask on the terminal** (`byj-gly.295`, provenance
P-32): it runs with `noninteractive` over its environment — `GIT_TERMINAL_PROMPT=0`,
`SSH_ASKPASS_REQUIRE=never`, `GCM_INTERACTIVE=never`, and git's ssh in batch mode
(`-o BatchMode=yes`, appended to a `GIT_SSH_COMMAND` of the person's own, none
over a `GIT_SSH` wrapper) — unless it is handed the terminal: `:in :inherit`,
the TUI's hand-over (tmux attach, `$EDITOR`), or `:interactive? true`. A child
that asks opens `/dev/tty` itself, past its pipes: ssh asking for a key's
passphrase drew over the TUI, whose raw-mode input took the keys, and the
passphrase went to the model as a turn. Checked live in a tmux pane: through
`start`, `git ls-remote` of a private HTTPS repository with no credential helper
failed at once ("terminal prompts disabled"); handed the terminal, it asked
for a username on it. Tools that open `/dev/tty` without such a switch (`sudo`,
gpg's pinentry) are not covered: no controlling terminal at all is
measured below (`byj-gly.296`) and built for the TUI (`byj-gly.297`).

**Taking the terminal away altogether** (`byj-gly.296`, measured 2026-10-08 by
`dev/brainyard/dev/no_tty_spike.clj`, macOS, in a tmux pane). macOS has no
`setsid(1)`; the candidate is to start a child through `/usr/bin/perl -e
'setsid(); exec …'`, so it leads a session of its own with no controlling
terminal and stays the same process.

| | plain | through `setsid` |
|---|---|---|
| the child opens `/dev/tty` | opens | **cannot** (`ps` shows no terminal, `??`) |
| `sudo -k true` | asks on the terminal | fails in 53 ms: "a terminal is required to read the password" |
| stdin as a string, stdout captured | — | intact |
| a cancelled `process-task` over a tree of 3 | 0 left | 0 left (`destroy-tree` still reaches it) |
| a spawn of `true`, median of 30 | 4 ms | 19 ms |
| Ctrl-C on brainyard's terminal, a tree of 3 running | 0 left | **3 left**: in a session of their own, the terminal's interrupt does not reach them |

So the wrapper closes what the environment cannot (`sudo`, gpg's pinentry,
anything that opens `/dev/tty`), for 15 ms a spawn — and costs what the
terminal used to do for free: Ctrl-C, or a closed window's hangup, no longer
reaches the children; only brainyard's own cancel does. In the full-screen TUI
that changes nothing for Ctrl-C (raw mode makes it a key, and a turn's cancel
already destroys its trees), but in an inline run, or when the window closes,
a long child — a dev server the model started — would be left running.
(Incidentally: jolt itself did not exit on Ctrl-C in either case.)

**Decided** with the user, 2026-10-08: wrap only **while the full-screen TUI
owns the terminal** — the one place a prompt breaks the screen, and where raw
mode already turns Ctrl-C into a key — and kill the trees brainyard still owns
when it exits, so a closed window leaves nothing running. Inline runs keep the
terminal: a prompt there is an ordinary question.

**Built** (`byj-gly.297`): `detach-children!` is the switch, set by `tui/run!`
and `tui/run-many!` from `start-term!` to `restore-term!`. While it is on,
`start` begins every child that is not a hand-over through `no-terminal-argv`
— `setsid -w` where there is one, else perl — and remembers it; as the TUI
gives the terminal back it turns the switch off and `destroy-owned!` destroys
whatever of that is still running. Checked live in a tmux pane: under
`tui/run!`, a session's `sudo -k true` failed at once ("a terminal is required
to read the password") and nothing was drawn on the screen. A window closed under the
TUI ends this process with SIGHUP and runs no `finally`; the first build left a
detached `sleep` running after it (measured). jolt runs shutdown hooks on
SIGHUP, SIGTERM and SIGINT (measured), so `detach-children!` installs one, once,
that calls `destroy-owned!`: closed again, nothing was left. Only SIGKILL, which
runs nothing at all, leaves a detached child behind.

A descriptor is closed exactly once, by its owner. Closing one twice closes
whatever the number was handed to in between: `unix-socket/stream!` closed its
socket from `stop!` and again from its reader, and once pipes stopped leaking,
the freed number went to the next socket, which the second close ended
(provenance P-22). `stop!` now shuts the socket down and the reader closes it.
An MCP server's pipes are released after it has exited, off the caller:
closing them while jolt's own reader of its stderr still ran blocked
`disconnect!`.

Measured live (`jolt run:byj -p mock`, 20 turns): 24 → 159 descriptors before,
12 → 12 after.

Failures nobody awaits (a detached effect, a supervised process) go to
`brainyard.effect/*on-failure*` — `(fn [event data])`, stderr by default —
instead of mulog. `start!` registers a process before running it and removes
only its own entry when it ends (divergence `supervisor-registers-before-running`).

Not ported: `from-future` (jolt futures are replaced, not adopted), `smoke-*`
(no native-image gate to pass), `sample-lines` (no consumer). See divergence
`effect-brick-scope`.

## Rules for code that parks

These hold for `src/` and for any leaf fn; they come from the measurements above
and ebb's `doc/adr/001-fiber-affinity.md`.

1. **A blocking host call goes under `via blk`, and its canceller releases what
   it started.** Interrupting the waiter does not stop a subprocess: the child
   outlived every cancel that did not `destroy-tree` it. Subprocesses go through
   `process-task`; an HTTP call's cancel costs one 250 ms read slice
   (http-client ≥ v0.0.8 — do not pin an older one). Under `src/` the form is
   `fx/via-blk` (or `task-of`), never `m/via m/blk`: ebb's `via` delivers a
   cancel's interrupt outside the process's lock, so one landing as the thunk
   ends stays on the idle pool thread for whatever runs there next
   (byj-gly.346, provenance P-38); `via-blk` delivers it under the lock,
   until ebb does (byj-gly.347).
2. **`via blk` delivers what the thunk produced.** A cancelled blocking call
   surfaces as `InterruptedException` (or whatever the call threw), not
   Cancelled. Treat both as cancellation: `cancelled?` accepts either.
3. **Never park inside a lazy body** — `map`, `for`, `filter`, `keep`, `mapcat`,
   `lazy-seq`, and also `mapv`/`filterv`, which jolt realizes lazily under a
   counted lock. jolt 0.8.8 throws; jolt 0.8.3 hung (samizdat RFC-013). To park
   while looping, use `loop/recur`, `doseq`, `reduce`, `run!`, or a
   transducer given to `into`.
4. **`run!!` only at the edges** — `-main`, a test, a synchronous handler.
   Inside an effect use `?`; a `run!!` there pins a thread and severs the
   cancellation chain.
5. **Dynamic bindings are fiber-local, and the port does not rely on `via`
   conveying them.** A binding in force when a task *runs* is visible inside its
   `sp` body after any park, and in every `sp` branch of a `join`; fibers
   sharing carrier threads never see each other's values; a binding made inside
   blocking work does not leak out. Those hold.

   **`via blk` and `via cpu` do not convey, as of jolt 0.8.10.** They did on
   0.8.8, `ebb-bindings-test` pinned that, and the upgrade broke four of its
   assertions (`byj-gly.117`). Nothing promised otherwise: ebb implements no
   conveyance at all — no `get-thread-bindings`, no `with-bindings` anywhere in
   its source — declares no `:jolt/min-version`, and pins jolt **0.8.2** in its
   CI, so every version this repo has ever run was an untested combination. The
   old rule said "code may rely on this, because an upgrade that changes it
   fails the suite"; the upgrade came, the suite failed, and relying on it was
   the mistake.

   **So the port installs the frame itself, at every boundary that takes caller
   code.** `brainyard.effect/task-of` snapshots `get-thread-bindings` at
   construction, and so does `tasks/detachable`'s detachable branch — which for
   a while did not, so one function conveyed on one branch and dropped on the
   other depending on `detachable?`, a predicate its caller neither chose nor
   could see. Measured through the real path: `toolbox/*depth*` and
   `hooks/*depth*` both read 0 inside a detachable tool, counting two recursion
   guards from the wrong place. `ebb-bindings-test/the-port-installs-the-frame-itself`
   pins it now, and `dev/brainyard/dev/binding_conveyance_spike.clj` reproduces
   the original failure and shows the fix holding.

   A new `via` whose body runs caller code must do the same. Reading a dynamic
   var into a local *before* entering the effect world also works, and is what
   `*on-failure*` does at `effect.clj:105` and `:384`.
6. **Tests of code that parks run it on a fiber** (`(m/? (m/sp …))`): on a plain
   thread a park is a block and nothing asserts, which is how samizdat's first
   live run died on a `mapv` its suite never exercised.
7. **Cancelled is not an error.** A catch on a turn path must let
   cancellation propagate rather than record it as `:bt/error` (RFC-002).
8. **Cancellation is requested, not awaited.** A canceller — `(cancel)`,
   `stop!`, `stop-all!` — returns once cancellation is *signalled*; a step
   already in flight may still complete. Measured on the supervisor: over 200
   start/stop cycles against a 10 ms ticker, 8 runs (4%) saw exactly one more
   tick after `stop!` returned, and none kept ticking. So a test asserts that
   work **stops**, never that nothing lands after the call — the latter is a
   4%-per-run flake (`byj-mvd`), and making the canceller await settlement
   would let a task that ignores cancellation hang the exit path.

## Migration of this repository

Done in `byj-gly.27`:

| before | now | brainyard |
|---|---|---|
| `llm/with-retry` — `Thread/sleep` backoff loop | `retry-backoff` over `task-of`, through `run-value!!` | `llm.clj` `retry-with-backoff` |
| tool dispatch — `pmap` when `:parallel-tools?` | the action returns a Task over `bounded` | coact → `fx/bounded` |
| `tools/bash`, `tools/grep`, claude-code — `deref` with timeout, `destroy-tree` on timeout only | `process-task` (+ `timeout`); a cancelled turn kills the children | executor `task-of`, `claude_code` |
| `bt/tick` — `:cancelled?` checked per node, never set | `bt/tick-task`: the tree is a Task; `tick` is its edge | behavior-tree `nodes_task`, agent `run-bt-task` |
| `agent/ask` — synchronous | `agent/ask-task`; `ask` is its edge | `agent.clj` `fx/run` + `set-bt-canceller!` |
| session loop — no way to stop a running turn | a `:cancellable :turn` scope; `session/cancel-turn!`; Ctrl-C through Chez's `keyboard-interrupt-handler` | `runtime/cancel-run`, TUI cancel |

Two guards the migration needed:

- `tools/call-tool` catches `Throwable` to turn tool failures into error maps; it
  now rethrows a cancellation, which it would otherwise have swallowed into a
  "bash failed: interrupted" result (rule 7).
- `llm/post-json` wraps HTTP exceptions as retryable network errors; it now
  rethrows a cancellation, which would otherwise have entered the retry path as
  a provider failure (rule 2).

## Invariants

| invariant | enforced by |
|---|---|
| Dynamic bindings are fiber-local, survive parks, reach `via blk`/`via cpu` bodies, do not leak out of them, and are captured at run time. | `ebb-bindings-test` (6 tests; stable over 10 repeated runs) |
| Cancelled work stops; a step already in flight may finish first (rule 8). | `effect-test/ticking-self-stops-and-is-cancellable` and the supervisor and `poll-until` cancellation tests, through the namespace's `stopped?` |
| brainyard's brick contracts hold: effects are values; `run!!` reports and cancels on timeout and interrupt; cancellation reaches nested parks; `retry-backoff` retries, gives up, short-circuits, conveys the caller's frame to `on-retry`, and is cancellable mid-backoff; `bounded` keeps input order and cancels siblings on failure; flows are reusable; `ticking` and `poll-until` pace and cancel; `ensure!` vs `start!`; `watch-until` settles on the write. | `effect-test` — brainyard's tests, ported |
| `task-of` captures bindings at construction, which a bare ebb `via` does not. | `effect-test/task-of-conveys-dynamic-bindings` |
| A subprocess run through `sh`, `process-task` or `streaming-process` leaves no descriptor open once it ends — jolt's doing since the 0.8.15 floor (jolt#1175, byj-gly.262), `start`'s for stdin. | `effect-test/a-subprocess-gives-back-its-pipes` (70 runs, counted with lsof) |
| No namespace outside `brainyard.effect` runs `jolt.process` directly, bar two that release by hand, named with why. | `effect-ratchet-test/no-subprocess-outside-the-brick` |
| While the TUI owns the terminal a child leads a session of its own — none handed the terminal — with stdin and stdout intact; what is still running at the end is destroyed, and only that. | `effect-test/under-the-tui-a-child-has-no-terminal-and-is-not-left-behind` |
| A subprocess is told not to ask on the terminal — git and ssh fail rather than prompt — unless it is handed the terminal; the person's own `GIT_SSH_COMMAND` keeps its options, a `GIT_SSH` wrapper is not bypassed. | `effect-test/a-subprocess-never-asks-on-the-terminal-unless-handed-it` |
| A cancelled or timed-out `process-task` leaves no child process, and reports the cancel even when its wait saw the killed process exit before it saw the interrupt, as it does from jolt 0.8.21 (provenance P-41). `streaming-process` does the same. | `effect-test/process-task-kills-what-it-started` (checks the child was running first) |
| A cancelled blocking call counts as cancellation. | `effect-test/cancelled-includes-the-interrupt-via-blk-surfaces` |
| A cancel racing a blk thunk's end leaves no pool thread interrupted, and no blk thunk under `src/` bypasses `via-blk`. | `effect-test/a-cancel-racing-a-thunks-end-leaves-the-pool-thread-clean` (5 000 races; `dev/brainyard/dev/interrupt_spike.clj` measured ebb's `via` at 18 of 30 000 and 39 of 30 000, `via-blk` at 0), `effect-test/nothing-under-src-runs-a-thunk-through-ebbs-via` |
| The supervisor never lists a finished process, and a cancellation is not reported as a failure. | `effect-test/supervisor-registry-is-not-raced-by-late-callbacks`, `effect-test/failures-nobody-awaits-are-reported` |
| No park inside a lazy body under `src/` (`mapv`/`filterv` count as lazy). | `effect-ratchet-test/no-park-inside-a-lazy-body`; its detector is itself tested (`detectors-are-not-vacuous`) |
| No raw `future`/`pmap`/`future-call` under `src/` outside the brick; the allowlist only shrinks. | `effect-ratchet-test/no-raw-future-or-pmap-outside-the-brick` — allowlist now empty |
| The loop's cancellation reaches LLM calls, tools and subprocesses, and a session survives a cancelled turn. | `cancellation-test` (RFC-002/003/004/006/007 name the individual tests) |

## Known gaps


- Pause (brainyard's per-pause `m/dfv` beside a `Condition`) is not specified.
- Streaming (`byj-gly.8`) should follow brainyard's §18 pushed-body flow, including its finding that `m/subscribe` drops the last value of an eagerly completing publisher.
