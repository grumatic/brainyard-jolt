# RFC-031 — The session channel

**Status:** built (`byj-gly.253`): the transport decided (`.1`), the channel
(`.2`), its senders (`.3`), the headless host (`.4`), what an asker is charged
(`.8`), `:subscribe` (`.9`) and brainyard's other ops (`.12`). Agent teams,
which are built on it, are RFC-032.

## Purpose

Decides how one live session reaches another: asks it something, hands it a
task, reads its status, cancels its turn. Agent teams (RFC-032) are built on
it: a leader reaches its members, and they reach it, this way. brainyard
sources:
`components/ask-channel` (`core/{protocol,server,client}.clj`),
`bases/agent-tui/core.clj` (`start-ask-listener!`, `ask-handle-fn`,
`inject-side-ask!`), `components/agent-tui-persist/core/{paths,lock}.clj`;
docs `session-channel.md`, `design/ask-attach-channel.md`,
`design/session-channel-extensions.md`.

## Scope

**This layer decides** how a request reaches a live session, and what it may
ask; who owns a session; how an answer comes back.

**It must not know** what becomes of a request inside a turn: a request that
changes the session becomes an ordinary turn, through the owner's own queue
(RFC-004).

**It hands** turns to the session loop through the seam a background task's
wakeup already uses (RFC-004 "A turn nobody typed", RFC-019).

## The decision: brainyard's `ask.sock`, reached through libc

brainyard gives each live session `<session-dir>/ask.sock`: an AF_UNIX stream
socket at mode `0600`. Its security model is the filesystem permission. Each
connection carries one EDN request line and gets one EDN reply line. RFC-027
recorded that jolt's `java.nio` has no AF_UNIX, and concluded that design
"cannot be ported". It can: `jolt.ffi` reaches libc, and `jolt.http.net`
already calls `socket`, `connect`, `send`, `recv` and `poll` through it. A server
needs three more declarations: `bind`, `listen` and `accept`.

Three candidates were measured by `dev/brainyard/dev/handoff_spike.clj`, on an
M-series laptop on 2026-09-27, with the owner in a second jolt process as in
real use:

| | file mailbox (`inbox/`, `outbox/`, rename) | AF_UNIX via libc |
|---|---|---|
| round trip, p50 / p90 | 254 / 258 ms polled at 250 ms (the TUI's tick); 53 / 58 ms at 50 ms | **5.6 / 11.5 ms** |
| 4 senders × 50 at once | 200 of 200, each exactly once, nothing left behind | (connections are independent) |
| idle cost | 0.1 % CPU at 250 ms, 0.3 % at 50 ms | a `poll(2)` with a 100 ms timeout between accepts |
| streaming (`:subscribe`) | no | yes |
| shutting down | nothing to stop | an idle accept loop stopped in 100.6 ms |
| file mode | directory permissions | `srw-------` |
| brainyard's design | a redesign, and a divergence | the design itself |

Loopback TCP, the third candidate, was measured by RFC-027: about a 200 µs round trip,
authentication needed, and an `accept(2)` nothing can interrupt.

**The socket wins.** The mailbox works: it is exactly-once and costs almost
nothing while idle. But its only advantage was that it needed no new native
surface, and a round trip bounded by a poll interval is a floor every
exchange pays. It would also be a divergence from a design that
turned out to be portable, and the port's rule is that brainyard is right until
there is a reason to differ (AGENTS.md). The socket keeps brainyard's name,
path, mode, framing and trust model, and streaming comes with it, which
`:subscribe` uses (`byj-gly.253.9`).

Two findings shape how it is built:

- **An accept loop that can stop.** RFC-027 found that a thread blocked in
  `accept(2)` cannot be interrupted. Here the loop `poll`s the listening socket
  with a 100 ms timeout and calls `accept` only when a connection is waiting,
  so it looks at its stop flag at least ten times a second. It never sits in a
  syscall it cannot leave.
- **`sockaddr_un` is laid out per platform.** macOS/BSD has a length byte, a
  family byte and a 104-byte path; Linux has a two-byte family and a 108-byte
  path. Another platform gets no channel and says so, rather than a guessed
  layout. A path over the limit falls back to
  `<tmpdir>/by-<sha256(session-id) first 16 hex>.sock`, brainyard's rule.

## Model

Kept as brainyard has it:

- **One owner.** A live session is the process holding its lock (RFC-016),
  and it alone binds the socket. Before binding it connects to the path: an
  answer means a live owner, and the bind is refused. No answer means a stale
  file, which is removed. Only a socket whose device and inode are still the
  ones it bound is unlinked, so a closing process never deletes its
  successor's.
- **Every session is locked while live**, a fresh one as well as a resumed
  one (`main/resumed-base`). The lock, not the socket, is what says a session
  is live.
- **Framing:** one `pr-str` EDN map per line. One request per connection;
  one reply, or, for a streaming op, frames until either side closes.
- **Ops:** each reply and error is brainyard's.

  | op | request | reply |
  |---|---|---|
  | `:ask` | `{:question :timeout-ms}` | `{:status :ok :answer :usage :provider :model}` |
  | `:inject` | `{:as :turn :text :await? :from? :approval?}` | `{:status :ok :injected :turn :answer}`, or with `:await? false` `{… :queued true}` at once |
  | `:inject` | `{:as :note :text :from?}` | `{:status :ok :injected :note}` — for people only (RFC-043, below) |
  | `:status` | — | `{:status :ok :state :idle\|:running :pending-turns :session-id :pid :frame-version :provider :model}` |
  | `:cancel` | — | `{:status :ok :cancelled bool}` (the running turn, whoever started it) |
  | `:config` | `{:query?}` | `{:status :ok :overrides {k {:value :source}} :total}` (what differs from the defaults), or with `:query` `{:matches [{:key :value :source :doc}]}`; values redacted |
  | `:emit` | `{:event :payload}` | `{:status :ok :emitted :subscribers}` — on the session's own bus: its hooks, subscribers and event log see it |
  | `:mcp` | `{:action :list}` | `{:status :ok :servers […]}`, as `/mcp` shows them; changing a server is `/mcp`'s |
  | `:inject` | `{:as :memory :slug :content}` | `{:status :ok :injected :memory :slug :stored}`, through the session's memory store (RFC-020) |
  | `:rename-session` | `{:label}` | `{:status :ok :label}` — both surfaces: the name `session list` shows and the conversation's title |
  | `:subscribe` | `{:events [kw…]?}` | `{:status :ok :subscribed [kw…]\|:all}`, then a frame `{:event :sid :payload}` per event until the client goes away |

  The errors are `empty question`, `timed out after <ms>ms`,
  `input queue is full (max 10)`, `no answer (turn cancelled or failed)`,
  `target session closed`, `unknown op: <op>`, `empty request` and
  `server error: <msg>`. `meta.edn` records `:ask-socket-path` and `:ops`, so a
  client reads the path rather than rebuilding it — and `:tmux-pane`
  (`[SOCKET,]PANE`) for an interactive session in tmux, the terminal it draws
  in, so a share of it by reference shows its screen (RFC-043;
  `channel/open!`'s `:tmux-pane`, `main/tmux-pane`; never a headless
  session's).
- **Frame version** (the port's, divergence
  `the-channel-names-its-frame-version`): `:status` and `meta.edn` carry
  `:frame-version` (`channel/frame-version`, 2), bumped when a frame's shape
  changes — a key added to a reply is not a change. A client released apart
  from the session, the share sidecar (RFC-043), refuses a version it does not
  know. Version 2 is RFC-043's (divergence `a-session-is-shared-through-a-sidecar`):
  - `:inject :as :note {:text :from}` → `{:status :ok :injected :note}`; a
    blank one is `empty note`, and before a session attaches `no session to
    note on yet`. A note is for people only: the session lends `:note!`, which
    records it in the trajectory (`:kind :note`, RFC-025) and says
    `:session.share/note {:text :from}` — the renderer's line, a subscriber's
    frame. It is never a turn, never in the session's memory, its history or a
    model's messages.
  - An injected turn's `:from` is a session's id, or a person
    `{:user :name :role}`. `:approval :owner` asks the session's person before
    anything is queued, through the `:approve!` the session lends only where
    there is a person (the full-screen renderer's overlay): `rejected by the
    owner`, `approval expired` after `:share-inject-approval-ms` (120000), and
    `this session has no one to approve an injected turn` headless or inline.
    `:approval :consent` asks no one, and marks the turn all the same; any
    other is `unknown :approval`. An allowed turn is an ordinary turn, through
    the same gate and auto mode, shown `⇠ injected by Kim (coach) · you allowed
    it`, and recorded with `:injected-by` (`channel/injected-by`).
  - The permission gate says `:permission/asked {:id :tool :summary}` when it
    hands a request to the person and `:permission/decided {:id :tool
    :decision :allow|:deny}` when they answer, so `:subscribe` sees prompts
    read only: nothing on the channel answers one.
  - The sidecar's own events reach the bus by `:emit`:
    `:session.share/presence {:share :url :participants [{:user :name :role}]
    :paused}`, which the footer and `/share` show; `/share` says
    `:session.share/control {:action :pause|:resume|:kick|:stop :user?}` for
    the sidecar to act on.
- **Concurrency:** a request that changes the session is queued in the owner's
  FIFO (at most 10) and runs as an ordinary turn when the session is idle. It
  is never an interruption. The reply is written on the same connection. A
  timed-out ask is not cancelled.
- **What a session lends its channel.** `:config`, `:emit`, `:mcp`,
  `:inject :as :memory`, `:inject :as :note`, an injected turn's approval and
  `:rename-session` need the session itself, which the channel cannot require.
  So `session/run` lends it hooks (`attach!`): its config rows redacted, its
  event bus, its MCP rows, its memory store, its title, its notes and — only
  given `:approve` by a front end with a person at it — its approval. Before a session attaches, these answer that there is none yet.
- **What is not ported**, and says so rather than `unknown op`: `:fsm-status`
  (no state machines here, a session is a tree), `:a2a` (A2A is not ported),
  `:inject :as :artifact` (no live artifacts), and the multi-session host's
  `:new-session`, `:resume-session` and `:switch-session`. A session here is its own
  process, started with `--serve` or `team$spawn`, and switching is the full-screen
  renderer's session list (divergence `one-session-per-process`).
- **Subscribers** (brainyard's `:subscribe`): `session/run` publishes every
  event the session emits to its channel. Each subscriber has its own queue of
  at most 512 unsent frames, and past that events are dropped for it: a slow
  subscriber never holds the session up. A payload is written as data a reader
  can take back, anything else as its string (`edn-safe`). The connection stays
  open: the transport's `stream` reply writes the ack and then frames from the
  connection's own thread until the peer closes, which unsubscribes it. Every
  accepted socket is set `SO_NOSIGPIPE` on macOS, since writing to a vanished
  subscriber would otherwise raise SIGPIPE and end the session.
  `jolt sessions:byj watch SESSION [--events E,…]` prints the frames, one EDN line
  each.
- **Where it enters.** A front end takes the next request (`next-turn!`)
  where it takes a background task's wakeup (RFC-019): the TUI's
  `:ready-turn` poll, and the terminal session's `waiting-reader`. The session
  tells the channel when a turn starts (`begin-turn`) and ends (`end-turn`,
  which answers the request). When it is waiting again with a request handed
  over but no turn run, as with an empty line (`read-input`), that request is
  answered `no answer`.
- **A request is a question, never a command** (byj-gly.282). A request reached
  the session's dispatch like a typed line, so a peer could run a slash
  command here — `/mcp add NAME -- COMMAND` wrote a server into the project's
  config, a process no gate sees — or a `:<tool>` direct call this session's
  person would be asked to approve on the peer's behalf. Now the session tree's
  `:session/channel-command-path`, before the direct and command paths, refuses
  a line `channel/from-channel?` says was handed over and `command?` or
  `direct-call?` would take: the sender gets `not run: <line> — a command or a
  direct tool call is the person's to type…` (`channel/refuse!`), the person a
  `⇠ refused over the session channel` line, and nothing runs. A `/<tool> args`
  request is still a turn that asks the model to call the tool, whose calls
  are gated as any turn's (divergence `channel-requests-are-questions`).
- **Config:** `:ask-channel-enabled?` (true) and `:ask-timeout-ms` (120000,
  the cap on any request's wait), brainyard's keys; `:share-inject-approval-ms`
  (120000), the port's (RFC-043).
- **Trust** is the socket's `0600` and the `.brainyard` directory. It is a
  local channel, not a network API.

Port-only, each with its own divergence entry when built:

- **Model-facing senders**: `session$list`, `session$status`, `session$ask`,
  `session$send` and `session$cancel` (divergence `sessions-ask-each-other`).
  `session$start` (byj-gly.386) starts a new session beside this one with a
  first question.
  - It is offered only where the front end can start one, given as the
    `:start-session` seam (`make-ctx`, `channel/commands`). The full screen's
    `run-many!` gives it: the new session joins this window, started on the
    render loop as one from the session list is, and is not brought to the
    front.
  - A single inline or headless session has no such seam, and so no such
    tool.
  - `/handover` (RFC-004) is what uses it.
  `session$send` does not wait: the pending reply becomes a background task in
  the sender's registry (RFC-019), so `task$wakeup` wakes the sender when it
  lands. A request carries the sender's id as `:from`. A session never asks
  itself. The tools are the root session's: a subagent reports to its caller,
  not to a peer. `:session-tools?` (true) turns them off, and their
  `:session/send` effect is what `--ask-effects` can gate.
- **The CLI** keeps brainyard's names but one: `byj session list [--live] [--json]`
  (`by sessions list`; `sessions` still accepted — divergence
  `session-and-agent-are-singular`, byj-gly.435), which lists after the
  sessions that listened the saved ones that never did — an SDK's, an editor's over ACP — marked `saved` (divergence
  `session-list-shows-saved-sessions`), and `jolt ask:byj --attach SESSION [-t SECS] [--json] Q…`
  (`by ask --attach`). Each question goes in turn, stopping at the first that
  fails, and the exit code is 0 only when all were answered. The JSON is
  `{success session-id provider model answer turns error?}`. `-p`/`-m` are
  ignored with a warning. A session may be named by its id, a prefix of it, or
  its label (divergence `sessions-ask-each-other`); brainyard takes the id.
  byj.el's commands are this sender, run from the person's Emacs (RFC-067
  §6); a session tells the Emacs it reaches its id as its channel opens.
- **The headless host** is brainyard's `by run --serve`:
  `jolt run:byj --serve [--agent NAME] [--label TEXT] [--resume ID]`. It is a
  session with no terminal whose only input is its channel. It waits for a
  request, runs it as a turn and waits again, and a parked turn still resumes.
  It ends on SIGINT (a running turn is cancelled first) or on brainyard's
  `:close-session` request, which only a headless session accepts: a session a
  person runs is theirs to close. A host that starts one may name itself in
  `BYJ_PARENT_PID`, and the session then closes itself, its turn cancelled,
  within 2 s of that process being gone (`main/watch-parent`, byj-1wv.42): a
  host that is SIGKILLed or crashes cannot ask, and its sessions otherwise
  idle under init indefinitely. An environment variable rather than a flag, so
  that a newer host still starts an older byj, which rejects a flag it does
  not know. With nobody to ask, a gate that would ask
  permission refuses, as in any headless run (RFC-012). `--agent` gives it —
  and, since `byj-g5n.2`, any session: `byj run`, `byj ask` and the SDK's
  (RFC-053), through `session/with-persona` — an
  agent spec's persona (`agents/persona-config`, the same one a subagent of
  that spec gets): its instruction, tools and predictors, its own `:lm` by the
  rule a subagent gets it (`agents/child-lm`), and its own `:loop` as each
  turn's tree (`session/tree-with-turn-loop`). An explicit `-p`/`-m` or
  `--loop` wins over the spec, as flags do (`byj-gly.256`), and `--label` names it in `session list`. Both are the
  port's (divergence `a-headless-session-has-a-persona-and-a-name`). jolt has
  no signal API but SIGINT's handler, so a SIGTERM ends the process where it
  stands. Its lock then names a dead pid and its socket answers nobody, and
  both are taken over by the next owner.
- **Finding a live session:** a session that has listened has `meta.edn`, and it
  is live when its lock names a running process and its socket is there
  (`channel/sessions`, brainyard's `enriched-summaries`).
- **What an asker is charged.** When an answer lands — from `session$ask`,
  or later from `session$send` — its usage is added to the asker's *session*
  usage (the turn that asked may be over), and
  `:session.member/spent {:session-id :usage :cost}` says so for a front end
  that totals a session's cost (`channel/spent!`).
- **A prompt asked for another session** (`:permission`): a session with a
  person at its gate answers `{:op :permission :from :from-label :request}`
  with that gate, the asker's name in front. It is how an agent team's member
  asks its leader (RFC-032), and a session with no one to ask does not
  advertise it.
- **Agent teams** are RFC-032: a leader runs members, which are headless
  sessions of this layer, and hands them work with `session$send`.
- **An injected turn names its sender** on the screen, from the request's
  `:from` (divergence `an-injected-turn-names-its-sender`). brainyard shows it as
  if typed.
- **A piped session has no channel** (divergence `a-piped-session-has-no-channel`).
  The terminal session opens one only when standard input is a terminal: taking
  a request means polling the input, and a poll cannot tell the end of a pipe
  from a pause.

### A live Claude Code session as a target (byj-gly.429)

The session tools reach a live Claude Code session of the project too, named
`claude:<id>` (`brainyard.claude-reach`). It has no channel byj can use: it is
reached the way a person reaches it, by typing into its terminal.

- **Reachable** when it runs in tmux: this run's `byj-cc-<id>`, or the pane its
  live record names (`"tmux": "work:@8.%8"`, `claude-host/target`) — so one a
  person started in their own tmux is reachable too. One outside tmux, in a
  terminal of its own, is not: nothing can type into it. `session$list` lists
  both, the unreachable one with why.
- **Named** by `claude:<id>`, a prefix of the id, or its label; a name no byj
  session has is looked for among Claude Code's.
- **`session$send` and `session$ask`** wait for it to be idle (a line typed
  into a running turn is queued behind it, where its end could not be told),
  type the task — one short line as it is, a longer one written to
  `<its project>/.brainyard/handovers/` with a typed line pointing at it, as
  agent$claude guides its sessions — and its answer is the turn's reply, read
  from its transcript once the turn is over (`claude-host/await-settled`).
  `session$send`'s waits at most `:claude-handover-timeout-ms` (30 min).
  `session$status` reads its record; `session$cancel` is refused: its turn is
  cancelled with Esc in its own terminal.
- **Unreachable is refused at once**, with what to do instead: run it inside
  tmux or from byj's agent page, or hand the task to a new session
  (agent$claude).
- **Not used:** Claude Code's peer-messaging socket
  (`messagingSocketPath`). Its protocol is Claude Code's own, and a peer's
  message is information to the session that gets it, not its person's
  instruction — a handover delivered that way would not be acted on.

### A live Codex session as a target (byj-gly.430.5)

A live Codex session of the project is a target the same way, named
`codex:<thread>` (`brainyard.codex-reach`, RFC-049), with one difference:
Codex has its own way to reach a session wherever it runs, so every live one
is reachable. One in this run's tmux (`byj-cx-<thread>`) is typed into
(`codex-host/type-line`) and its answer read from its rollout once the turn
is over (`codex-host/await-settled`); one anywhere else — a terminal of its
own, a tmux byj did not start — is handed the line with `codex queue
--thread <thread> --message <line>`, which Codex runs as that session's next
turn, and its answer is the rollout's turn begun with that line, once it
ended. `session$list` says which (`:via` `:tmux` or `:queue`). A brief longer
than a line is a file the line points at (`claude-reach/brief-line`).
`session$send` waits at most `:codex-handover-timeout-ms` (30 min); an
approval dialog in its tmux, or a turn it aborted, is an error a person
answers in its terminal; `session$status` reads its rollout; `session$cancel`
is refused. A name no byj or Claude Code session has is looked for among
Codex's.

## API

`brainyard.unix-socket`: `supported?`, `max-path-bytes`, `serve!` (`{:path
:stop!}`), `request!`, `stream` (a reply that keeps the connection open),
`stream!` (its client: `{:ack :stop! :done}`), `open?`, `live-owner?`,
`read-msg`.

`brainyard.channel`: `open!` (a channel, carrying `:problem` when it could not
listen), `close!`, `handle` (one request's reply), `next-turn!`, `pending?`,
`turn-started!`, `turn-ended!`, `settle-unanswered!`, `request!`,
`socket-path`, `read-meta`, `ops`, `max-queue`; the senders' side:
`sessions`, `find-session`, `commands` (the `session$*` tools), `publish!`,
`subscribe!`, `spent!`, `forwarding-permission-fn`, `frame-version`,
`brainyard.claude-reach`: `sessions`, `find-session`, `claude-target?`,
`brief-line`, `ask-task`, `status`; `claude-host/target`;
`brainyard.codex-reach`: `sessions`, `find-session`, `codex-target?`,
`queue!`, `ask-task`, `status`;
`sender-label`, `injected-by`, `approval-ms`; `open!` also
takes `:close!` and `:label` for a headless session. `session/run` takes
`:channel`.

## Invariants

| invariant | enforced by |
|---|---|
| What a session's socket gives another program — its `:status` reply, a turn's frames and its `:ask` reply — is exported as `contract/session/` and held to what byj gives (RFC-070 phase 2) | `contract-test/the-session-socket-and-the-trajectory-are-byjs-contract` |
| A request gets one reply, a UTF-8 character intact; the file is `0600`; a failure or an empty request is a reply and the listener carries on; a second owner is refused while the first answers; stopping removes the file; a stale file is replaced. | `channel-test/a-unix-socket-answers-one-line-per-connection` |
| An interactive session's `meta.edn` names its tmux pane; one opened without a pane names none. | `channel-test/a-session-on-a-terminal-says-its-tmux-pane` |
| `:ask` and an awaited `:inject` become a turn and return its answer with the session's model; a cancelled or failed turn is `no answer`; `meta.edn` names the socket and the ops. | `channel-test/a-request-becomes-a-turn-and-its-answer-comes-back` |
| An answer is sent only once its turn is in the session's snapshot: an asker that settles on it may end the answering process at once (a team leader closing), and the turn must survive that (provenance P-26). | `channel-test/an-answer-is-sent-only-once-its-turn-is-saved` |
| A request that times out stays queued; the queue holds ten; `:status` reports idle/running and what waits; `:cancel` reaches the running turn; a blank question, an unknown op and an unsupported `:inject` are refused; a request handed over that never became a turn is `no answer`. | `channel-test/the-queue-is-bounded-and-a-timeout-does-not-withdraw` |
| Closing answers everyone waiting with `target session closed` and removes the socket; a second owner of one session gets a channel with a `:problem`. | `channel-test/closing-answers-everyone-still-waiting` |
| A socket path longer than `sun_path` falls back to the same `<tmpdir>/by-<16 hex>.sock` on both sides. | `channel-test/a-long-session-dir-falls-back-to-tmpdir` |
| A real session answers over its socket: the question is one of its turns; a slash command or a `:<tool>` call is refused with why, runs nothing — `/mcp add` adds no server — and the person is told; a `/<tool> args` request is a turn. | `channel-test/a-session-answers-over-its-own-socket` |
| Every live session is locked, a fresh one too; a session another live process holds refuses to run. | `channel-test/every-live-session-is-locked` |
| `:config`, `:emit`, `:mcp`, `:inject :as :memory` and `:rename-session` reach the session through what it lends the channel; before it does, they say there is no session yet; an op with no counterpart says why. | `channel-test/the-other-ops-reach-the-session` |
| A subscriber gets an ack and then only the events it asked for, anything unprintable as a string; one that stops reading loses events past 512 and never blocks the session; going away unsubscribes. | `channel-test/a-subscriber-sees-the-session-as-it-happens` |
| A subscriber to a real session sees its turn start and end as they happen. | `channel-test/a-subscriber-watches-a-real-session` |
| A note is acknowledged, published with its sender, recorded as a note, and is never a turn nor in any model message; a blank one or one before a session attaches is refused. | `share-test/a-note-is-for-people-only` |
| `:approval :owner` asks the person who wants what, with the configured time; a refusal, an expiry and no one to ask are each said and queue nothing; `:consent` asks no one and still marks the turn; a session's `:from` still works; an `:ask` is not an injection. | `share-test/an-injected-turn-asks-its-owner` |
| An allowed injected turn runs as an ordinary turn, is shown with its sender, and is recorded with `:injected-by`. | `share-test/an-injected-turn-is-recorded-shown-and-counted-apart` |
| The gate says each ask and its answer, with one id; a headless gate says nothing. | `permission-test/a-prompt-is-said-to-watchers-and-nothing-answers-it-but-the-person` |
| What a peer spent answering is added to the asker's session usage and said as `:session.member/spent`. | `channel-test/a-session-finds-and-asks-its-peers` |
| A headless session advertises `:close-session`, is found by its label, and ends when asked; a session a person runs refuses the request. | `channel-test/a-headless-session-is-closed-over-its-channel` |
| A headless session started with `BYJ_PARENT_PID` closes once that process is gone; no pid, or not a pid, watches nothing. | `main-test/a-served-session-closes-when-its-starter-is-gone` |
| `--agent` gives a headless session — and `run` and `ask` alike — a spec's instruction and tool globs, and an unknown agent none. | `channel-test/a-headless-session-takes-an-agents-persona` |
| …and the spec's model (a model change keeps the caller's provider) and its loop as each turn's tree, with its predictors first; `-p`/`-m` and `--loop` win. | `channel-test/a-headless-session-takes-an-agents-model-and-loop` |
| `session$start` is offered only with a front end's `:start-session`; its question is the new session's first turn and its id comes back; a blank question starts nothing; a failure is said, not thrown. | `channel-test/a-session-can-start-another-where-its-front-end-can` |
| Live sessions are listed newest first and named by id or prefix; an ambiguous name and asking oneself are refused; `session$ask` returns the peer's answer, and the peer sees who asked; `session$send` returns at once and its answer lands as a completed background task; a closed session is listed as not live; the CLI asks by prefix, prints brainyard's JSON, and lists. | `channel-test/a-session-finds-and-asks-its-peers` |
| A live Claude Code session is listed as `claude:<id>`, reachable when it runs in any tmux — by the pane its record names — and not when it runs outside, with why; it is found by id, prefix or label; a short task is typed into its pane and a long one is a file the typed line points at, and the turn's answer comes back; one outside tmux is refused with what to do instead. | `claude-reach-test/a-claude-code-session-in-any-tmux-is-reached-and-one-outside-is-not` |
| `session$list` names Claude Code sessions with byj's; `session$status` reads one; `session$send` hands one a task as a background task whose result is its answer, and refuses one outside tmux at once; `session$cancel` refuses. | `claude-reach-test/the-session-tools-hand-a-claude-code-session-work` |
| A live Codex session is listed as `codex:<thread>`, found by thread, prefix or label; one in this run's tmux is typed into and one anywhere else is queued to, each answered from its rollout; a long brief is a file the line points at; the session tools route `codex:` names to it. | `codex-reach-test/a-codex-session-in-byjs-tmux-is-typed-into-and-answers`, `a-codex-session-anywhere-else-is-queued-to` |
| The mailbox alternative delivers 200 concurrent requests exactly once, and the socket stops in ~100 ms. | `dev/brainyard/dev/handoff_spike.clj` (a spike, not a test) |

## Known gaps

None open.
