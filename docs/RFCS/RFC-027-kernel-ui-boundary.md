# RFC-027 — The kernel/UI boundary

**Status:** decided (`byj-gly.93`). The boundary exists and is crossed by
function values; nothing crosses it as bytes. A daemon is deferred, not
rejected — §"When to reopen this" says on what evidence.

## Purpose

Decides how a user interface reaches the agent kernel, before `byj-gly.20`
(full-screen TUI) starts and commits the answer by accident. brainyard sources:
`bases/agent-tui`, `components/ask-channel`, `components/web-share`; docs
`tui/architecture.md`, `specs/tui.md` CR-TUI-20, `session-channel.md`,
`web-sharing.md`.

## Scope

**This layer decides** what a UI is allowed to assume about the kernel, and
whether a UI may live in another process.

**It must not know** what any UI renders. The kernel has no opinion about
terminals, browsers or panes; RFC-026 already separates *what an event is* from
*how it is shown*, and this RFC extends that separation to *where it is shown*.

**It hands** the UI the seams in the table below and nothing else.

## The boundary already exists

It is `session/run`'s arguments (`main.clj`), and a UI is a different set of
values passed to them:

| seam | direction | shape | RFC |
|---|---|---|---|
| `:on-event` | kernel → UI | `(fn [event data])` — one door, catalogued | RFC-026 |
| (`:on-event`) | kernel → UI | streamed reply fields are the event `:agent.llm/chunk {:field :text}` on the one door, not a seam of their own (an `:on-chunk` fn is a predictor's option, `predictor/run`, below the session) | RFC-006, RFC-026 |
| `:emit` | kernel → UI | `(fn [kind text])` — what the turn says, `kind` `:answer`, `:info`, `:error`… | RFC-004 |
| `:read-input` | UI → kernel | `(fn [prompt] -> line-or-nil)` | RFC-004 |
| `:cancellers` | UI → kernel | an atom; `session/cancel-turn!` | RFC-009 |
| `:permission-fn` | kernel → UI → kernel | `(fn [req] -> {:allowed true} \| {:denied true :reason …})` | RFC-012 |
| `:present` | kernel → UI | `(fn [kind data] -> shown?)` — a command's report as data, for a UI with a view of its own (`/config`'s editor, `/model`'s picker, `/tools`, a direct call's `:tool-call`) — and data a UI keeps rather than shows, the tool rows it completes from (`:tool-rows`); a UI without one returns nil and the text is emitted | RFC-004, RFC-028 |
| `:hand-terminal` | kernel → UI | `(fn [argv env])` — run a command a person asked for with the terminal, and take the screen back after: `/login`'s CLI (`claude auth login`). The full-screen renderer passes `tui/hand-over!`, an inline session runs the command on the terminal as it is, and a headless one passes none, so the command is said instead (byj-gly.311) | RFC-006, RFC-028 |

**The `:permission-fn` row is the one that decides everything.** It is not a feed, it is a
call: a request goes out, the calling fiber blocks, and an answer comes back.
The port has no timeout on it by design (divergence `permission-prompt-cli`),
so a UI that stops answering does not degrade the turn, it stops it. Any design
that treats the boundary as "stream events at a viewer" handles the feeds and
breaks the call — the one in front of every `bash` call.

## Decision

**A UI runs in the kernel's process and crosses the boundary by calling it.**
No protocol, no serialization, no daemon. `byj-gly.20` builds a renderer that
supplies the seams.

This is brainyard's answer, and brainyard reached it the expensive way. It
*built* the two-process split — `by-host` daemon and `by-ui` orchestrator over
a unix-socket control protocol with per-pane FIFOs — shipped it, and retired it
in May 2026:

> A control protocol (Unix-socket EDN frames, host/host-callbacks/control
> client+server) MUST exist for a `by-host`↔`by-ui` daemon split. — **Retired**
> … no shipping consumer. (`docs/specs/tui.md` CR-TUI-20)

> One process. One renderer. No daemon. … That split bought detach/re-attach
> survival at the cost of substantial complexity, and the as-built design that
> won is a single in-process renderer. (`docs/tui/architecture.md`)

`bases/agent-tui-ui/` was deleted from their tree. This is not brainyard being
right by default under AGENTS.md's porting rule; it is brainyard being right by
experiment, having paid for the alternative.

## Measurements

`dev/brainyard/dev/ui_boundary_spike.clj`, on this machine. Re-run it rather
than quoting the numbers.

### What jolt can build

| capability | result |
|---|---|
| `java.net.UnixDomainSocketAddress/of` | unavailable — no dependency provides it |
| `java.net.StandardProtocolFamily/UNIX` | unavailable — no dependency provides it |
| `ServerSocketChannel/open` with a protocol family | unavailable, same cause |
| `java.net.ServerSocket` on `AF_INET` | **works** — binds, accepts, round-trips |

**brainyard's `--serve` cannot be ported through `java.nio`.** Its channel is
AF_UNIX at `<session-dir>/ask.sock`, mode `0600`, where the security model *is*
the filesystem permission. Through `java.nio` on jolt it would become a TCP
port on loopback, which any local process may connect to. That would be a
redesign with a new threat model — token authentication, an explicit
`127.0.0.1` bind — not a port. *Later, in RFC-031:* the socket CAN be reached
through libc with `jolt.ffi`. It binds at `0600`, and it answers across processes
in 5.6 ms at the median, so the session channel is brainyard's own
(`byj-gly.253.1`).

An HTTP server exists only as an external library (`jolt-lang/ring-chez-adapter`
— HTTP/1.1, SSE and a websocket *server*), plaintext only, and not a dependency
here. jolt has **no websocket client**, so the transport cannot be chosen before
the client is: a terminal UI written in Clojure could not speak WS at all, and a
browser cannot speak line-delimited JSON-RPC over TCP.

### What a byte boundary costs

2000 round trips over one connection, against the call it would replace:

| route | per event |
|---|---|
| `:on-event`, in-process call | **0.03 µs** |
| TCP loopback, 4096-byte buffered reads | **199.48 µs** |
| TCP loopback, one byte per `.read` | 310.60 µs |

The ratio is ~6,600×. Both numbers are small in absolute terms and **neither is
the reason for this decision** — a turn emits events at human speed, not in
tight loops. The third row is worth keeping for a different reason: the gap
between it and the second is read strategy, not transport, so a protocol
written the obvious way pays 56% more per line than the same protocol written
with a buffer. Whoever reopens this should not re-measure that from scratch.

### A daemon in jolt cannot be shut down while idle

| how | result |
|---|---|
| `Thread/interrupt` on the accepting thread | **still blocked** after 405 ms |
| `.close` on the `ServerSocket` | **still blocked** after 406 ms |
| a self-connect, with a `stopped` flag | released |

jolt's `Thread.interrupt` is cooperative and does not reach a thread inside a
blocking syscall, which is exactly where `accept(2)` sits — and closing the
socket underneath it does not release it either. The only route out is to give
the loop the one thing it is waiting for: a connection, with a flag it checks
after `accept` returns. That is the shape of jolt's own `jolt.nrepl` accept
loop, which checks its `stopped` atom only *after* `c-accept` returns.

Two further jolt facts any daemon inherits: a thread does not keep the process
alive after main returns (`.setDaemon` is accepted and ignored), so main must
park explicitly; and there is no TLS on the server side.

## What this decision is not

It is **not** a claim that a UI must be a terminal. An in-process renderer can
paint a terminal, and `--web` (`byj-gly.22`) shares that terminal through ttyd,
which ships terminal bytes and needs no protocol at all. brainyard's web story
is the same one, and its browser product builds on ttyd's websocket rather than
on a structured API.

It is **not** a rejection of a daemon. It is a decision not to build one
without a consumer, having twice reverted work in this repository for that exact
reason.

## When to reopen this

Any one of these is sufficient, and none of them is "a daemon would be tidier":

1. **A second client exists** — an editor plugin, a browser UI, or a second
   terminal that must attach to a session it did not start.
2. **Detach and re-attach is actually wanted** — a turn that must survive its
   terminal closing. Note that RFC-019 background tasks and RFC-016 session
   resume already cover much of what people want this for.
3. **ttyd proves insufficient** for sharing, in a way an in-process renderer
   cannot fix.

The first browser client, through the share sidecar (RFC-043), did not
reopen it: its browser reaches a session through an out-of-process sidecar that speaks to
`ask.sock` as any other client does, so the kernel still has no HTTP server and
no websocket.

**The first programmatic client did reopen it, on its first condition — and
the answer was stdio, not a daemon** (RFC-053, `byj-g5n`). An application
embedding byj's agent through the SDK, or an ACP editor, is a second client
that must answer the sixth seam: a permission prompt is the client's to
answer. `byj acp` is a front end in the kernel's own process, as this RFC
asks, whose seams are values that write JSON-RPC to its stdout and read the
answers from its stdin. So bytes do cross the boundary now, but over a pipe
the client owns, which defuses every constraint below: no port and no
authentication (nothing else can reach the pipe), no accept loop, and no hung
turn when the client goes, since its going is the pipe's end, which closes
every session (`acp-test/stdout-is-the-protocol-alone-and-the-client-going-away-ends-everything`).
A process is still one client's; a session another program started is
reached over its channel (RFC-031), and a daemon is still not built.

Whoever reopens it inherits these constraints, already measured: TCP not
AF_UNIX, so authentication is the daemon's own problem; no websocket client, so
the transport follows the client; an accept loop that needs a self-connect to
stop; and `permission-fn`'s missing timeout, which becomes a hung turn the
moment the answering client can disconnect.

## Sharing a session: `--web` (`byj-gly.22`)

This is the sharing story this RFC left to ttyd, built as brainyard's
`components/web-share` (`docs/web-sharing.md`) with its flags and
`BY_WEB_*` variables. `brainyard.web-share` is a launcher: no kernel or
renderer change, no HTTP server here. ttyd runs `byj run` in a real
PTY and bridges it to the browser over its WebSocket.
- **`run --web`:** ttyd runs a **fresh session for each viewer**. It starts
  its command once per connection: two viewers connected at once had two
  `byj run` processes (measured 2026-10-09). brainyard's
  `docs/web-sharing.md` calls this one shared session; its code does the
  same as this. The banner says so. The launching terminal shows ttyd's log,
  and Ctrl-C stops it.
- **`run --web-tmux`:** the **one shared session**. It runs in a private
  tmux server (`-L by-web-<id>`), which every viewer and the local `tmux
  attach` the banner prints drive, one live pane. The launcher stays a dashboard until the
  session ends or it is stopped, then stops ttyd and the server.
- **The child** is this program again (`BY_WEB_SELF`, else the `byj`
  binary it was started from, else `jolt -m brainyard.main` from a
  checkout), with the same arguments but the `--web*` flags. It carries
  `BY_WEB_CHILD=1`, so it runs the session rather than sharing again.
- **Security, as brainyard's:**
  - Basic auth is always on, with a password generated and printed when
    none is given.
  - ttyd's origin check (`-O`) is always on.
  - It binds 127.0.0.1 unless `--web-bind` says otherwise, with a warning
    past localhost; the banner suggests an ssh tunnel instead.
  - Viewers may type, unless `--web-readonly` (decided with the person,
    2026-10-09).
- **ttyd** is an `/init` requirement for the feature, with its install
  hint. Checked live, 2026-10-09:
  - Tier 1 answers 401 without credentials and 200 with them, and a
    WebSocket client carrying the auth token, an `Origin` and Basic auth
    receives the running full-screen session. Driven through it with the
    `byj` binary on claude-code/haiku, a typed question was answered in
    the shared screen (`⏺ SHARED OVER THE WEB`, $0.0024).
  - Without those, ttyd refuses the connection.
  - Tier 2 serves the tmux session, and when the session quits it leaves no
    ttyd, no tmux server and no listening port.

## Invariants

| invariant | enforced by |
|---|---|
| The CLI ends quietly with 141 (128 + SIGPIPE) when the pipe it writes to closes — `byj … \| head` — as `ls` or `git` would, rather than with jolt's report of an unhandled IOException; any other failure is reported as before (byj-gly.368). | `main-test/a-closed-pipe-is-told-from-any-other-failure`; checked on the binary: `eval task-score --json \| head -c 100` exits 141, nothing on stderr |
| `--web` builds ttyd's command from its options — the origin checked, auth always on, writable unless read-only — a flag over its `BY_WEB_*` variable over the default; the child is this program with the same arguments but the web flags, and does not share again; the banner warns past localhost. | `web-share-test/the-ttyd-command-is-what-the-options-say`, `web-share-test/auth-is-always-on`, `web-share-test/a-flag-beats-its-variable-which-beats-the-default`, `web-share-test/the-child-runs-the-session-and-does-not-share-again`, `web-share-test/the-banner-says-how-to-reach-it-and-warns-past-localhost` |
| The kernel reaches a UI only through the seams in the table, and knows nothing else about it. | nothing automated — `session/run`'s argument list is the whole contract |
| A permission request is a call returning `{:allowed true}` or `{:denied … :reason …}`, remembered per matching pattern. | `permission-test/the-ask-channel` |
| It blocks until answered — there is no timeout. | **nothing enforces this**: it is the absence of a timeout, not the presence of a guard. Recorded in divergence `permission-prompt-cli`; a daemon would have to add one and change the divergence |
| Every kernel-to-UI event goes through one door. | `events-test/nothing-reaches-the-bus-except-through-here` |
| jolt's `java.nio` has no unix domain socket; through libc it has one (RFC-031), which is how brainyard's `ask.sock` is ported. | `dev/brainyard/dev/ui_boundary_spike.clj`, `dev/brainyard/dev/handoff_spike.clj` (spikes, not tests — they open sockets) |

## Known gaps

- No full-screen renderer yet — `byj-gly.20`, unblocked by this RFC.
- None open for a programmatic client: `byj acp` serves one on stdio
  (RFC-053), and reaches a session it did not start over that session's
  channel (RFC-031, the `live` extension).
