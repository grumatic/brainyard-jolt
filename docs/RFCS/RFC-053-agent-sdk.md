# RFC-053 — Brainyard Agent SDK: byj driven from an application's code

**Status:** implemented, 2026-10-25, and driven by hand in Zed 1.25.3
(`byj-g5n.13`). Epic `byj-g5n`, closed. Decided (`byj-g5n.1`): the
transport, the wire, sessions per process and the client libraries'
JSON-RPC, with the spike's measurements below. Built: the kernel's part
(`.2`), `brainyard.sdk` (`.3`), `byj acp` (`.4`), byj's extensions (`.5`), the
TypeScript SDK (`.6`), the Python SDK (`.7`), versions and distribution (`.8`), a structured answer (`.10`),
live sessions (`.11`), a fork (`.12`), and ACP's `session/list`, `resume`
and `delete` for an editor's thread import (`.14`).

## Purpose

byj today is driven by a person (the full-screen renderer, `byj run`), by a
shell (`byj ask --json`), by another session (the session channel, RFC-031)
and by another agent (over A2A, through a gateway outside byj). It is not driven by an *application*:
code a developer writes, in TypeScript or Python or Clojure, that wants an
agent as one of its parts — a review bot, a support assistant, an
extraction service, a course's grader — the way the Claude Agent SDK lets an
application run Claude Code's loop.

The **Brainyard Agent SDK** is that. An application:

- opens a session in a project directory, naming the agent, provider, model
  and loop, and the project's own `.brainyard/` behaviour applies to it;
- sends prompts and streams what happens — the reply as it is written, the
  tools called and what they returned, byj's own events — then the turn's
  result with its usage and cost;
- answers the permission prompts a person would answer, or sets a mode that
  answers them;
- hands the agent tools that run in the application's own process, and hooks
  that see or decide each tool call;
- resumes a session by its id, cancels a turn, closes a session;
- calls a predictor (RFC-005) for structured output, with no turn and no
  tools.

It is also what a project template has been missing: a template carries
configuration. With the SDK it can carry an *application* — its code
calling the SDK, its agent's behaviour in `.brainyard/` beside it, tuned
without touching the code (RFC-001's rule, extended to the application).

## Scope

**This RFC decides** how an application reaches byj's agent: the transport,
the protocol and its extensions, the in-process facade they stand on, the
client libraries, and how the SDK and byj are versioned together.

**It must not** decide what the agent does: a session opened through the SDK
is an ordinary session — its tree, prompts, predictors, permission gate,
hooks, trajectory and event log are the ones every session has. No behaviour
is added to `src/` (RFC-001). It must not add a daemon, a socket or a port
to the kernel (RFC-027's decision, amended below only as far as stdio).

**It hands** turns to `session/run`'s seams (RFC-027's table) and nothing
else: an SDK front end is one more set of values passed to them, as the
full-screen renderer, the inline session and the headless host are. Its
events come from the one door (`events/emit!`, RFC-026).

## What exists, and why none of it is the SDK

| | what it gives an application | why it is not enough |
|---|---|---|
| `byj ask --json` | one answer as JSON, per process | no streaming, no permission answers (headless refuses), no tools of the application's, a process per question |
| `byj run --serve` + `ask.sock` (RFC-031) | a warm session, `:ask`, `:subscribe`, `:cancel` | EDN framing, one request per connection, nothing calls back into the client: no permission answer, no client tool, no hook; the session must be started and its socket found by scraping stdout, as an A2A gateway over the channel does |
| an A2A gateway | another agent asks byj | text in, text out; a remote agent is not an application embedding one |
| in-process `session/run` | everything | only to code running in byj's own jolt image, with no stable API |

An A2A gateway written by hand against the channel was in effect the first
SDK client, and its shape is what the SDK generalises: spawn byj, talk to it, close it when the parent goes.

## Decisions

### Settled by the spike (`byj-g5n.1`, 2026-10-25)

| decision | consequence |
|---|---|
| **The transport is the process's stdio.** The client library spawns `byj acp` and speaks to it over its stdin and stdout, as the Claude Agent SDK spawns `claude`. | No socket, no port, no authentication: the pipe is owned by the process that started byj, and nothing else can reach it. No accept loop (RFC-027's uninterruptible `accept(2)` never arises). The client going away is the pipe closing: byj reads EOF, cancels every turn and closes every session — which answers RFC-027's warning that `permission-fn`'s missing timeout "becomes a hung turn the moment the answering client can disconnect": here, it cannot disconnect without ending the process. Divergence `permission-prompt-cli` stands unchanged. |
| **The wire is the Agent Client Protocol v1**, with byj's extensions as ACP allows them: `_byj/*` methods and `_meta.brainyard` fields, advertised at `initialize`. | One server serves two kinds of client. An ACP editor (Zed, JetBrains, Neovim's ACP plugins) drives byj as it drives Claude Code or Gemini through ACP, with no byj code of its own; an SDK client uses the same connection and the extensions too. The JSON-RPC, the framing, `session/request_permission`'s options and `session/update`'s shapes are a published specification rather than a protocol of byj's own to specify and version. ACP v1 is stable; v2 is still labelled draft (2026-10) and is negotiated, not assumed. brainyard has ACP's JSON-RPC and schema in Clojure (`components/acp`, ~1,200 lines), written for its client side. |
| **Several sessions in one `byj acp` process**, each built for its own `cwd`. | An ACP editor opens a session per thread on one connection. The full-screen renderer's `tui-session` already holds several in one process, sharing the run's config, model and MCP and owning its lock, tasks, wakeups and cancellers. An ACP session differs only in that each may name another `cwd` and its own `mcpServers`, so each gets its own base — config loaded for its project, its LM, its MCP box — rather than the run's. Process-wide state is small (`permission`'s and `tasks`' id counters, caches), none of it a project's. Under `--sandbox` the seatbelt profile is fixed at launch (RFC-012), so a `cwd` outside its write roots is refused. |
| **`brainyard.sdk` is the one implementation.** An in-process facade over `session/run`, with the permission answer, the client's tools and its hooks as injected fns. `byj acp` is a thin front end that turns JSON-RPC into those calls. | Every behaviour is tested once, in-process, on the mock provider. A jolt application uses the facade directly with no protocol at all: brainyard works as a dependency of another jolt project (`{:local/root …}`, so a `:git/url` too), a mock turn answered in 0.95 s. Such an application must end with `System/exit`: idle threads of ebb's blocking pool keep a jolt process alive about 60 s after `-main` returns. |
| **The client libraries carry their own JSON-RPC**, a few hundred lines each, rather than standing on the official ACP libraries. | `@agentclientprotocol/sdk` went from 1.6 to 2.0.2 between 2026-10-01 and 2026-10-23, and 1.9 deprecates the extension handlers (`extMethod`) the SDK depends on for client tools and hooks, in favour of a newer API. byj's SDKs speak ACP v1's wire, and the official client (pinned) is what the interop check drives `byj acp` with, so conformance is measured, not assumed. |

**Measurements** (`dev/brainyard/dev/sdk_stdio_spike.clj`, M-series laptop,
jolt 0.8.23; re-run it rather than quoting these). The agent is a second jolt
process; its reader is fd 0 through libc `read(2)`, 64 KiB at a time, split
on the byte 0x0A, on ebb's blocking pool; its writer is a lock around stdout.

| what | result |
|---|---|
| spawn to the first answer | 302 ms (`jolt -A:dev -m …`); the installed `byj --version` is 0.81–0.85 s |
| 2000 sequential round trips | p50 154 µs, p90 167 µs, p99 229 µs, max 3.5 ms — RFC-027's loopback TCP was 199 µs |
| a turn writing 200 updates while the reader takes pings | 200 of 200 intact, UTF-8 kept (`✓ 한글`); pings during it p50 147 µs |
| a permission request from the turn's thread, answered by the client | routed back by id; the turn resumed and ended `end_turn` |
| a cancel sent mid-turn | the turn answered `cancelled` 1 ms later |
| 2 s with nothing written | the agent still answers: a pause is not an end |
| stdin closed with a turn parked on a request the client never answers | the parked request answered `cancelled`, the turn cancelled, stdout closed and exit 0, 112 ms after |
| the official TypeScript client (`@agentclientprotocol/sdk` 1.9.0) against an ACP-shaped agent | `initialize` (protocol 1, `_meta.brainyard` read back intact), `session/new`, `session/prompt` with `agent_message_chunk`, `tool_call`, `session/request_permission`, a `_byj/tool/call` extension request answered by the client, `tool_call_update`, and `end_turn` with `_meta.brainyard`; exit 0 on EOF |

`read(2)` returning 0 is the end of input, so no poll has to guess — the
reason divergence `a-piped-session-has-no-channel` gives does not apply to a
reader that blocks on the descriptor itself.

Alternatives measured against these, and why not:

| alternative | why not |
|---|---|
| Claude Code's `stream-json` control protocol, so the Claude Agent SDK itself could drive byj (`pathToClaudeCodeExecutable`) | Undocumented and moving: its control envelope and subtypes are not in Anthropic's documentation (2026-10), only in the SDKs' source and third-party write-ups; its tool names, options and messages are Claude Code's; and an application would believe it was running Claude Code. |
| The session channel (RFC-031) as the SDK's wire | EDN, one request per connection, and nothing calls back into the client. It would need permission, tool and hook callbacks added to a local protocol whose trust model is the socket's file mode — and the session must already be running. It stays the way to reach a session someone else started — which `byj acp`
does for its client, the `live` extension. |
| An HTTP or WebSocket server in byj | RFC-027 measured it: TCP not AF_UNIX, so authentication becomes byj's; no TLS server; no websocket client in jolt; an accept loop that needs a self-connect to stop. A hosted, remote agent is served out of process, by a program that speaks A2A. |
| A protocol of byj's own on stdio | Everything ACP gives, specified and versioned by hand, with no editor able to use it; the spike found nothing byj needs that ACP's extension points cannot carry. |

### The person's (2026-10-25: "go ahead with your recommendations")

| decision | settled |
|---|---|
| Where the client packages are published | The public repo `grumatic/brainyard-sdk`, MIT (the person's decision, 2026-10-26, `byj-ct5`): `sdk/` copied there on a release, with the npm tarball, the wheel and the source archive released beside it. Not npm or PyPI. Until then a `file:` or git dependency on this repository (`byj-g5n.8`). |
| Whether a package carries byj | It requires byj installed (Homebrew tap, RFC-029) and finds it by `byjPath`, `BYJ_BIN`, then `PATH`; no binary inside the package. |
| The first application template that does real work (`byj-g5n.9`) | An extraction service on predictors: byj's distinctive part, and runnable on `-p mock` with no credentials, where a review bot needs a forge token. |

## Model

### 1. Layers

```
application code (TS / Python)        jolt application
  └─ client library (sdk/typescript, sdk/python)
       └─ ACP client (official ACP SDK) ─┐
                                         │ stdio: JSON-RPC 2.0, one object per line
  byj acp  ◄─────────────────────────────┘
    └─ brainyard.sdk ◄───────────────── (in-process, no protocol)
         └─ session/run seams (RFC-027) — the ordinary session: tree, gate, hooks, events
```

### 2. A connection

```
client                                   byj acp
  initialize {protocolVersion 1, clientInfo, _meta.brainyard{sdk, version}}
                                     →   {protocolVersion 1, agentInfo{name "byj", version},
                                          agentCapabilities{loadSession true,
                                            promptCapabilities{…}, mcpCapabilities{…},
                                            _meta.brainyard{protocol 1, extensions [...]}}}
  session/new {cwd, mcpServers, _meta.brainyard{agent, provider, model, loop,
               config, permissionMode, tools, hooks, events, label}}
                                     →   {sessionId, modes{currentModeId, availableModes}}
  session/prompt {sessionId, prompt [{type text, text}]}
                                     ←   session/update  agent_thought_chunk …
                                     ←   session/update  tool_call {toolCallId, title, kind, rawInput}
                                     ←   session/request_permission {toolCall, options} ⇄ {outcome}
                                     ←   _byj/tool/call {sessionId, tool, args}        ⇄ {result | error}
                                     ←   session/update  tool_call_update {status completed, content}
                                     ←   session/update  agent_message_chunk …
                                     ←   _byj/event {sessionId, event, payload}      (when asked for)
                                     →   {stopReason end_turn, _meta.brainyard{answer, terminatedBy,
                                          iterations, goalAchieved, nextUserPrompt, usage, costUsd}}
  session/cancel {sessionId}             (notification; pending prompts answered cancelled)
  EOF on stdin                           every turn cancelled, every session closed, exit 0
```

Standard output carries the protocol and nothing else; anything byj would
print goes to standard error and the event log
(`<project>/.brainyard/logs/events.log`, on unless `:event-log? false`).

### 3. ACP's methods, as byj answers them

| method | byj |
|---|---|
| `initialize` | protocol 1 (2 only once negotiated support exists); `loadSession`; `sessionCapabilities` `close`, `list`, `resume`, `delete`; text and embedded-context prompts; stdio MCP servers; byj's version (RFC-029) as `agentInfo.version` |
| `session/new` | a session in `cwd` — the project dir, its `.brainyard/` read as any session reads it; `mcpServers` started as the session's (RFC-024); `_meta.brainyard` options below. Answers byj's session id (`context/session-id`) |
| `session/load` | `persist/restore` of that id (RFC-016), its history replayed as `session/update`s as ACP asks; refused while another process holds its lock |
| `session/list` | the project's saved sessions, newest first — `persist/summaries` of `cwd` (or the directory byj runs in), what `byj run --resume` lists — less an unreadable snapshot and a session that never ran a turn; each a `SessionInfo`: the title the conversation's name, else its last question, `updatedAt` ISO 8601, and `_meta.brainyard` `turnCount`, `createdAt`, `heldBy` (the pid of a live process holding it, which `session/load` would refuse). Fifty a page, `nextCursor` the next page's offset. Advertised as `sessionCapabilities.list`: an editor's thread import reads it (`byj-g5n.14`, found in Zed) |
| `session/resume` | `session/load` without the history replayed — the session opened where it stood, for a client that keeps its own copy of the thread; `sessionCapabilities.resume` |
| `session/delete` | `persist/delete!` (RFC-016): the session's directory and transcript tree removed, closed first when this connection has it open, refused while another live process holds it. The request names no project, so it is the one `session/list` listed the session in on this connection, else `_meta.brainyard.cwd`, else the directory byj runs in; `sessionCapabilities.delete` |
| `session/prompt` | one turn (`session/run`'s turn, the session tree's dispatch). A slash command or a `:<tool>` direct call is refused as the session channel refuses one (RFC-031 "A request is a question, never a command") unless the session was opened with `commands: true`, since the application, unlike a channel peer, owns the process |
| `session/cancel` | `session/cancel-turn!`; the prompt answers `stopReason cancelled` |
| `session/close` | the session ended — its turn cancelled, its lock released, its MCP stopped — and forgotten; advertised as `sessionCapabilities.close` (`byj-g5n.9`, for a service that opens a session per request) |
| `session/set_mode` | the permission mode (RFC-012): `auto`, `auto-approve`, `ask-each-time`, `deny-by-default`, through the session's `:permission-mode-box` |
| `session/update` (out) | `agent_message_chunk` from `:agent.llm/chunk` on the answer field; `agent_thought_chunk` from the reasoning field and `:agent.think/reasoning`; `tool_call` from `:agent.tools/calls` (kind from the tool's `:effects`: read, edit, execute, fetch, other); `tool_call_update` from `:agent.tool/output` and `:agent.tools/results`; `plan` from the session's checklist (RFC-023) when there is one |
| `session/request_permission` (out) | the session's `permission-fn`. Options: allow once, allow always (remembered per matching pattern, as the terminal's answer is), reject once, reject always. A request is `{toolCallId, title, rawInput}` from the gate's `{:tool :display :args :effects}` |
| `fs/read_text_file`, `fs/write_text_file` (out) | when the client advertised `clientCapabilities.fs.readTextFile` / `writeTextFile` (an editor: agent-shell, Zed), the session's `read-file`, `write-file` and `update-file` read and write a file under the session's `cwd` through it (`acp/client-fs`, byj-gly.464, RFC-067 §8): the editor answers from its buffer, unsaved edits included, and takes the write into it. The path is sent under the `cwd` as the client spelled it, not byj's canonical one (`/var/…`, not `/private/var/…`). A file outside `cwd`, a capability not advertised, and a read the client cannot answer (no such buffer or file, the client gone) are the disk's; a write the client refuses or does not answer in 30 s is the tool's error, never written to the disk behind it. The tools are the built-ins over another fs (`tools/file-tools`), in their place in the listing |
| `terminal/*` (out) | not used: byj's `bash` runs commands on the machine itself, as in every session; recorded, not planned |
| `authenticate` | answered `{}`: byj's providers are configured as for any session (`/login`, keys), and `authMethods` is empty |

### 4. byj's extensions

Built (`byj-g5n.5`). Advertised as `agentCapabilities._meta.brainyard`
`{protocol 1, extensions ["tools" "hooks" "events" "predict" "live"]}`. A client asks
for them by sending `_meta.brainyard` with its `initialize`; one that does not
is sent no `_byj/event` and no `_meta` on a prompt's answer. Session options,
client tools and hooks are the client's own requests and need no asking.

**Session options** (`session/new` and `session/load`, `_meta.brainyard`):

| key | what |
|---|---|
| `agent` | an agent spec's persona (`session/with-persona`), as `--agent` |
| `provider`, `model`, `loop` | as `-p`, `-m`, `--loop`; `byj acp`'s own `-p`/`-m`/`--agent` are the defaults |
| `config` | config overrides at the flags' layer (RFC-013), JSON coerced to the declared types (`config/from-json`) |
| `permissionMode` | the initial mode: `auto`, `ask-each-time`, `auto-approve`, `deny-by-default` |
| `tools` | client tools, below |
| `hooks` | hook callbacks, below |
| `events` | `"all"`, or the RFC-026 event names to send as `_byj/event`; none by default |
| `label` | the session's name |
| `forkFrom` | a saved session to start from: its history under a new id, it left as it was (`persist/fork!`, RFC-016; `byj-g5n.12`) |
| `sessionId` | the new session's id, chosen by the client in `<millis>-<8 hex>`, so a fork's history — replayed before `session/new` answers — is routed to it |
| `commands` | slash commands and `:<tool>` calls allowed in a prompt; false by default |

**Client tools.** `tools: [{name, description, inputSchema, effects?}]`.
Each is a tool in the session's `:base-tools` (it survives a ctx rebuild),
named as given, with the `effects` it declares (`sdk/call` when none), so the
gate, `--deny-effects` and the hooks treat it as any tool. A call is a
`_byj/tool/call {sessionId, toolCallId, tool, args}` request — `toolCallId`
the `tool_call` update's — answered `{content}` (a string or any JSON, the
tool's result), `{content, isError: true}` or a JSON-RPC error (its
failure). `inputSchema` is JSON Schema read as malli
(`schema/from-json-schema`) for the subset tools use — `object`,
`properties`, `required`, `string`, `integer`, `number`, `boolean`, `array`
with `items`, `null`, `enum`, `description`; anything else (`oneOf`, `$ref`,
`allOf`, a list of types) refuses the session, naming the keyword. A client
tool runs in the application's process, outside byj's sandbox (RFC-012).

**Hook callbacks.** `hooks: [{id, event, match?, timeoutMs?, failClosed?}]`,
`event` one of RFC-011's by brainyard's names (`hooks/event-catalog`:
`agent.tool-use/pre`, `agent.tool-use/post`, `agent.ask/pre`, …) — any other
refuses the session, listing them — and `match.tool` a glob on the payload's
`tool-name`. Each is an entry on the hook bus whose handler is a `_byj/hook
{sessionId, id, event, payload}` request, answered `{decision: "block",
reason}`, `{decision: "replace", replacement}`, `{decision: "modifyArgs",
args}` or `{}` (RFC-011's decisions; only a gated event's count). It is
waited for at most `timeoutMs` (30000); past it, or with the client gone, it
allows, unless `failClosed` — then it blocks, saying why.

**`_byj/event`** (notification out): `{sessionId, event, payload}`, the
event's name (`session.turn/start`) and its payload as JSON — keywords as
their names, keys as strings, anything else as its string (`json-safe`) —
for the events the session asked for: how an application sees what byj's
own front ends see without ACP having a word for it.

**`_byj/predict`** (request in): `{predictor | signature, inputs, provider?,
model?, cwd?, config?}` → `{outputs, reasoning, usage, costUsd,
predictorId, paramsSource}`. `predictor` is a `user/` id found under the
project's or the home's `.brainyard/predictors`; `signature` is
`{instructions, strategy?, inputs: [{name, description?, schema?}], outputs:
[…]}`, each field's `schema` JSON Schema (a string when none). No session, no
tools, no turn: `predictor/run`. A turn whose answer is wanted as data too
is `outputSchema`, below.

**Live sessions** (built, `byj-g5n.11`): a session this connection did not
start — a person's `byj run`, a headless `byj run --serve`, a team's member —
reached over its session channel (RFC-031), as `byj session` and an A2A gateway reach one. `_byj/live/list {cwd, all?}` → `{sessions: [{sessionId, live,
label, lastQuestion, pid, ops}]}` (live ones unless `all`);
`_byj/live/ask {cwd, sessionId, question, timeoutMs?}` → `{sessionId, answer,
usage, provider, model}`, the question a turn of that session's, under its
own gate and person — the channel's rule holds, a slash command is refused;
`_byj/live/attach {cwd, sessionId, events?}` → `{attachId, sessionId}`, then
a `_byj/live/event {attachId, sessionId, event, payload}` per event until
`_byj/live/detach {attachId}` or the connection ends. A session is named by
its id, a prefix or its label (`channel/find-session`). The extension is
`live`; a client library does not require it.

**A structured answer** (built, `byj-g5n.10`): `session/prompt`'s
`_meta.brainyard.outputSchema`, a JSON Schema, asks for the turn's answer as
data too. After the turn, one call of the predictor `sdk/structured-answer`
(`resources/brainyard/predictors/sdk/`, its words data a project tunes like
any predictor's) restates the question's answer as the schema's fields — an
object's properties each a field, one outside `required` nullable; any other
schema one field `value` — on the session's model (`sdk/structure-answer`).
A reply that does not match is asked for once more with what was wrong. The
answer's data is `structuredOutput`, or `structuredOutputError` says why
not; its cost is in the turn's `costUsd`. A schema this cannot read refuses
the prompt before the turn runs. The agent's loop is untouched: the turn
answers in words as every turn does, and the data is a restatement of it —
an application that wants data and no agent asks `_byj/predict`.

**The turn's result** rides in `session/prompt`'s answer, `_meta.brainyard`,
for a client that asked: `answer`, `terminatedBy`, `iterations`,
`goalAchieved`, `nextUserPrompt`, `elapsedMs`, `usage`, `costUsd`, and with an
`outputSchema` `structuredOutput` or `structuredOutputError`.
`stopReason` is `end_turn`, `cancelled` or `max_turn_requests` (the iteration
cap); a turn that failed is a JSON-RPC error, its answer in `data.detail`.

### 5. Client libraries

The TypeScript SDK first — most application code that would embed an agent — then Python, the same surface in
snake_case. Each carries its own JSON-RPC over the child's stdio (Decisions),
with no runtime dependency, and adds: finding and spawning byj, the
capability check, the extensions, and an application's vocabulary.

**TypeScript** (built, `byj-g5n.6`): `sdk/typescript`,
`@grumatic/byj-agent-sdk` 0.1.0, Node 20 or later; its README is the guide.

```ts
import { query, tool, ByjClient } from "@grumatic/byj-agent-sdk";

const lookup = tool("order_lookup", "An order by its id",
  { type: "object", properties: { id: { type: "string" } }, required: ["id"] },
  async ({ id }) => orders.get(id));

for await (const m of query({
  prompt: "Why was order 1042 refunded?",
  options: {
    cwd: "/srv/support",                    // its .brainyard/ is the agent's behaviour
    agent: "support", provider: "claude-code", model: "sonnet",
    tools: [lookup],
    permissionMode: "ask-each-time",
    canUseTool: async (req) => req.kind === "read" ? { behavior: "allow" } : { behavior: "deny" },
    hooks: [{ event: "agent.tool-use/pre", tool: "bash", handler: (p) => audit.log(p) }],
  },
})) {
  if (m.type === "text") process.stdout.write(m.text);
  if (m.type === "result") console.log(m.answer, m.costUsd);
}

const client = await ByjClient.start();
const { outputs } = await client.predict({ predictor: "user/triage", inputs: { ticket } });
```

- **`query({prompt, options})`**: byj started, a session opened, the
  prompt's messages yielded, byj ended — also when the caller stops early.
- **`ByjClient.start(clientOptions)`**: one `byj acp`, any number of
  sessions — `newSession`, `loadSession(id, options, onHistory)`, `predict`,
  `close`. A `Session` has `prompt(text)` (an async iterable of messages, one
  prompt at a time), `run(text, onMessage)` (the result), `cancel()` and
  `setPermissionMode`.
- **Messages**: `text`, `thought`, `toolCall`, `toolResult`, `permission`
  (`by` `callback` or `none`), `event`, `user` (a resumed session's), and
  `result` — last, always, with `isError` and `error` for a failed turn.
- **Options**: `SessionOptions` (`cwd`, `agent`, `provider`, `model`, `loop`,
  `config`, `permissionMode`, `canUseTool`, `tools`, `hooks`, `events`,
  `mcpServers`, `label`, `commands`); `ClientOptions` (`byjPath`, else
  `$BYJ_BIN`, else `byj`; `byjArgs`; `processCwd`; `env`; `stderr`).
- **The check at start**: protocol 1 and the four extensions at protocol 1,
  else `ByjVersionError` saying what is missing and `byj update` — a
  capability check, not a version comparison, since a checkout's version
  (`v0.3.7-55-g…`) says nothing about what it offers.
- **Not the Claude Agent SDK's surface where byj differs**: no
  `systemPrompt` (an agent spec in the project is the instruction, RFC-001),
  no `allowedTools` (the project's config and the agent's tool globs), hook
  events and permission modes by byj's names, `predict` where Claude has
  none.

With no `canUseTool` and a mode that asks, the library answers *reject once*
and says so in a `permission` message: an application that wants approvals
must say how to give them, as a headless session refuses (RFC-012).

**Python** (built, `byj-g5n.7`): `sdk/python`, `byj-agent-sdk` 0.1.0,
Python 3.10 or later, no dependency (pytest for its tests). The TypeScript
surface in snake_case on asyncio: `query(prompt, SessionOptions(...),
**client_options)`, `ByjClient.start(...)` as an async context manager,
`new_session`, `load_session`, `predict`, `Session.prompt` (an async
iterator), `run`, `cancel`, `set_permission_mode`; `@tool(name,
description, schema)`; `Hook(event, handler, tool=, timeout_ms=,
fail_closed=)` answering `Block`, `Replace`, `ModifyArgs` or None;
`can_use_tool` returning a `PermissionDecision`; messages as dataclasses
with `type` `text`, `thought`, `tool_call`, `tool_result`, `permission`,
`event`, `user`, `result`.

### 6. Versions

Built (`byj-g5n.8`). Three versions, each saying one thing:

| version | where | says |
|---|---|---|
| ACP's, 1 | `initialize`'s `protocolVersion` | the wire; v2 is negotiated, never assumed — byj answers 1 to a client asking 1 or later |
| byj's extensions', 1 | `agentCapabilities._meta.brainyard.protocol`, with `extensions` | the shapes of `_byj/*` and `_meta.brainyard`; bumped when one changes shape, a key added is not a change (as the channel's `frame-version` counts, RFC-031) |
| byj's own | `agentInfo.version` (RFC-029's stamp) | which byj; shown, never compared |

Before it starts byj, a client library asks it `byj acp -h`, once a
process: a byj older than `acp` takes the word for a question and would spend
a model call on it, where every byj answers `-h` with its help and nothing
else, so one whose help is not `acp`'s is refused unstarted — no `acp`
command, `byj update` — and one that is not there is named. Then it checks
the first two versions at `initialize` and refuses a byj that lacks what it
needs — the protocol, or one of the four extensions — with
`ByjVersionError` naming what is missing and `byj update`. It does not compare
byj's version: a checkout says `v0.3.7-55-g…`, which tells nothing about what
it offers, and a capability is what the library needs (`initialize`, both
SDKs). A library's own version (`SDK_VERSION`, 0.1.0) is sent as
`_meta.brainyard.version` at `initialize`, for byj's event log.

**Distribution** (the person's decision, 2026-10-26, `byj-ct5`; it replaces
2026-10-25's "nothing published"): the SDKs are public, MIT, at
`grumatic/brainyard-sdk`, and still developed here, in `sdk/`, where `jolt
test:sdk` tests them against this checkout's byj. `dev/sdk-publish.sh vX.Y.Z`
releases one: the four places the version is written agree, `CHANGELOG.md`
has its section and each README's install lines name it, the tests pass;
then `sdk/` as the commit has it — its files, never this repository's
history, which is internal — becomes one commit of the public repo, tagged
`vX.Y.Z`, and from that commit `npm pack`'s tarball, `uv build`'s wheel and
source archive and their `SHA256SUMS` are built and released there, then
downloaded again and checked. Without `--yes` it sends nothing. An
application installs the tarball or the wheel by the release's URL — npm
cannot install a git repository's subdirectory, and GitHub Packages asks a
token even for a public package — or the Python package from git
(`#subdirectory=python`); its lockfile keeps the URL and the hash. Not on npm
or PyPI, so no version ranges: a newer SDK is the newer release's URL. byj
comes from its release (Homebrew tap, RFC-029) or a checkout; a library
carries no byj. The public repo's CI (`sdk/.github/workflows/test.yml`, live
only there) runs both SDKs' tests against the tap's newest byj on the
scripted mock.

### 6b. A shared agent, from across the internet (built: `byj-1wv.115.14`)

The stdio client drives a local `byj acp`; an agent shared from its owner's machine is reached at an A2A endpoint of
the service that shares it. `connectShared(url,
token)` (TypeScript, `src/remote.ts`) and `connect_shared(url, token)`
(Python, `remote.py`, standard library only) read its card at
`<url>/.well-known/agent-card.json` and speak A2A v0.3: `ask` is
`message/send`, answered from the completed task (its status message, else
its artifacts) with its task id, context and cost (`ai.brainyard/cost-usd`);
`stream` is `message/stream`, the working text as it grows and then the
answer; `cancel` is `tasks/cancel`. A handle keeps its conversation's
context, so each message continues the last. A refusal is a
`SharedAgentError` of its own kind — `unauthorized`, `refused` (removed, or
may not ask), `owner-only` (on its owner's subscription), `not-live` (its machine
off), `cap`, `busy` (its machine at its cap),
`failed` — read from the endpoint's own words. It shares
nothing with the stdio client but how it answers.

## API

**`brainyard.launch`** (built, `byj-g5n.3`): what a run is assembled from,
shared by the CLI and the SDK — `env-fn` (the environment with its `.env`),
`lm-choice` and `fallback-lm` (which provider and model), `make-lm`,
`mock-lm`, and `event-bus` (the viewer, the event log and the prediction
notice). `brainyard.main` keeps its old names for them.

**`brainyard.sdk`** (built, `byj-g5n.3`):

| fn | what |
|---|---|
| `open-session opts` | a session on a thread of its own, `session/run` as every front end runs it; returns `{:id :config :state …}`. opts: `:project-dir` (required, the project itself — never the git root above it, which the CLI takes), `:provider` `:model` or `:lm`, `:agent` (`session/with-persona`), `:loop` (the session tree, as `--loop`), `:config` (overrides at the flags' layer), `:mcp-servers`, `:permission-mode`, `:permission-fn`, `:tools` (tool maps, RFC-007, in `:base-tools`; one named as a primitive replaces it in its place), `:hooks` (entries, RFC-011), `:on-event` (every event, beside the event log), `:on-said` (`(fn [kind text])`), `:resume`, `:commands?`, `:label` |
| `submit! session text` | a promise of the answer: `{:result turn-result :said […]}` once the turn ran and the session waits again; `{:no-turn true :said …}`; `{:refused reason}` at once; `{:closed true}` |
| `prompt session text` | `submit!` as a Task |
| `refusal session text` | why a line is not taken: closed, empty, a slash command or `:<tool>` direct call without `:commands?`, a full queue (10) |
| `running?`, `cancel!`, `set-mode!`, `close!` | is a prompt being worked on; cancel the running turn; the permission mode from now on; end it — turn cancelled, queued prompts answered `{:closed true}`, lock released, MCP stopped |
| `predict opts` | `predictor/run` on a `:predictor` id or an inline `:signature`, in a project, no session |

Decided while building it:

- **A prompt is answered when the session waits again**, not at the turn's
  end event: so after the snapshot, as the channel answers (provenance P-26),
  and a line that ran no turn is answered too.
- **A slash command or `:<tool>` line is refused by its first character**
  unless `:commands?`: the channel's rule (RFC-031), decided before the line
  is queued rather than by the session tree, which knows a channel and not
  an SDK client. `/<tool> args`, which the tree would take as a turn, is
  refused with them; `:commands? true` lets it through.
- **An SDK session opens no session channel**: it is reached through its
  application. The other direction — an application reaching a session it
  did not start — is the `live` extension.
- **Two sessions of one process never share an id**: the lock file names a
  process and `persist/holder` does not count its own, so `brainyard.sdk`
  keeps the ids it has open.
- **An application's instruction is an agent spec** in its project
  (`.brainyard/agents/<name>/`, `:agent`), not a string appended by the
  client: behaviour is data (RFC-001). The `instructionAppend` option this
  RFC first proposed is dropped.
- **A client tool is not asked about by default.** The gate asks for tools
  matching `:tool-approval-patterns` or `:tool-ask-effects` (RFC-012), which
  ship naming no `:sdk/call`; a project that wants its application's tools
  approved names the effect.

**`brainyard.acp`** (built, `byj-g5n.4`): `connection` (JSON-RPC over a
line-writing fn), `receive!` (one line from the client), `request!` (one to
the client, a promise of its answer), `notify!`, `handlers` and
`notification-handlers` (ACP's methods), `permission-fn`, `tool-kind`,
`end!` (the client gone: our requests answered closed, every session closed,
its in-flight requests let finish for up to 5 s), `serve!` (the process's
stdio: fd 0 read through `read(2)`, the protocol written to a copy of fd 1
made with `dup`, and fd 1 pointed at stderr with `dup2`).

**`byj acp [-p P] [-m M] [--agent NAME] [--permission-mode MODE]`**
(built): the server. The flags are defaults for a session whose
`_meta.brainyard` names none, so an editor configured with a command line
chooses the model there.

**In an ACP editor** (checked by hand in Zed 1.25.3, `byj-g5n.13`): an editor that
runs ACP agents as commands takes byj as one. In Zed's `settings.json`
(`~/.config/zed/settings.json`; Zed 1.25 wants `"type": "custom"`, and a Zed
started from the Dock may not have `~/.local/bin` on its PATH, so the command
may need byj's full path):

```json
"agent_servers": {
  "byj": { "type": "custom", "command": "byj", "args": ["acp", "-p", "claude-code", "-m", "sonnet"] }
}
```

The editor opens a session in its project (`session/new` with the
workspace's `cwd`), streams the turn, and asks its own permission UI for the
gate's prompts; its thread import lists the project's saved sessions
(`session/list`) and loads the one chosen (`session/load`). The flags are the defaults an editor cannot send in
`_meta.brainyard`.

**`-p mock -m script:FILE`** (built, `launch/mock-lm`): the mock answering
with FILE's replies — an EDN vector of `{:reasoning :tool-calls :answer …}`
maps — in turn, then echoing; how a test of another process (`byj acp`
driven by a client) scripts tool calls and permission prompts with no model.

**`dev/acp-interop.sh [--binary]`** (built): the official TypeScript client
(`@agentclientprotocol/sdk` 1.9.0, pinned) — 12 checks: initialize, a
session, a prompt with a permission request, the tool call and its result,
the answer, a mode set, exit 0 on EOF, the session listed by a new
connection and loaded with its history replayed, then resumed by a third
with none replayed, and deleted — and brainyard's own client (`../brainyard`
`components/acp-client`, on the JVM) — 5 checks — against `byj acp`. No
model; network on the first run.

Decided while building it:

- **Updates are byj's events, translated where ACP has a word.** The answer
  field's chunks are `agent_message_chunk`s, the reasoning field's
  `agent_thought_chunk`s; a batch of calls is `tool_call`s (`call-N-I`, the
  batch and its place) and their results `tool_call_update`s, completed or
  failed, the result clipped to 4,000 characters; a resumed session's
  transcript is replayed as user, agent and thought chunks before
  `session/load` answers. An answer that was not streamed is sent whole
  before the prompt is answered.
- **A permission request names the call** the gate is asking about, its
  kind from the tool's effects (`process` execute, `fs/write` edit,
  `fs/read` read, `net/*` fetch); allow-always and reject-always are
  remembered for the session by the pattern that matched, as the terminal
  remembers them.
- **Config from a client is JSON** and is coerced to the declared types
  (`config/from-json`): a set key's array is a set of keywords, a keyword
  key's string a keyword.
- **The client's going away ends everything, but answers what it asked**:
  a client that writes its last request and closes still reads the answer,
  for up to 5 s.

Planned:

- `jolt test:sdk` (`dev/sdk-test.sh`, built) runs both SDKs' tests against
  byj on a scripted mock, apart from `jolt test:byj`: 14 tests and the three templates, ~80 s.

## Threats

| threat | answer |
|---|---|
| another local process driving the agent | there is nothing to connect to: the pipe is the parent's alone |
| an application loosening the gate (`auto-approve`, `config`) | the application owns the process, as a person owns the flags they type; `--deny-effects` and the project's deny rules still apply, and the sandbox (RFC-012) still wraps byj's own tools |
| a client tool doing what the gate would refuse | it runs in the application, not in byj: byj gates the *call*, the application is responsible for what its tool does; said in the guide |
| a client that stops answering a permission prompt or a hook | the prompt waits, as it waits for a person; a hook waits at most `:sdk-hook-timeout-ms`; closing stdin ends everything |
| prompt injection through a tool result from the application | the same as any tool's result; nothing new |

## Invariants

| invariant | enforced by |
|---|---|
| A session opened through the SDK is an ordinary session: a prompt is a turn answered with its result, its events reach the viewer, it is locked while open and leaves its snapshot under its own project — never the repository above it — and closing releases the lock. | `sdk-test/an-sdk-session-is-an-ordinary-session` |
| A slash command, a `:<tool>` call or an empty line is refused without `:commands?`; with it, a command runs and is answered with what it said. | `sdk-test/a-prompt-that-is-not-a-question-is-refused` |
| A client tool is shown to the model, called as the application's fn, and gated as any tool. | `sdk-test/a-client-tool-is-an-ordinary-tool` |
| A shared agent is read from its card, asked over A2A with the token as a bearer, answered from a completed task with its context and cost, streamed as its working text then the answer, continued in its context, and each refusal is an error of its own kind. | `sdk/typescript` `test/remote.test.ts`, `sdk/python` `tests/test_remote.py` (an endpoint shaped as an A2A gateway's answers) |
| A client's hook fires for every turn. | `sdk-test/a-client-hook-survives-the-session`; across a ctx rebuild, `session-test/a-callers-hooks-survive-a-rebuild` |
| A running turn is cancelled and its prompt answered with how it ended; a session already open is refused; a prompt after close is refused. | `sdk-test/cancel-close-and-a-session-held-elsewhere` |
| `:agent` gives the session the spec's persona; an unknown agent opens nothing. | `sdk-test/an-agent-names-the-sessions-persona` |
| `predict` runs an inline signature with no session. | `sdk-test/predict-is-structured-output-with-no-turn` |
| The Python SDK, against a real `byj acp`: the TypeScript SDK's cases, a hook on the session's opening among them. | `sdk/python/tests/test_sdk.py`, run by `jolt test:sdk` |
| A public release is `sdk/` as a commit has it, its versions agreeing, built from the public repo's copy, and its files after upload are what was built. | `dev/sdk-publish.sh`'s own checks (not part of any suite) |
| A client library refuses a byj with no `acp` before starting it, so an older byj never runs as an agent and spends nothing; a byj that is not there is named. | `sdk.test.ts` and `test_sdk.py`, "a byj with no acp is refused…" |
| The TypeScript SDK, against a real `byj acp`: a query streams a turn and ends with its result; an application's tool is called after `canUseTool` allows it; with no `canUseTool` a prompt is refused and said so; a hook blocks a call; a client holds sessions — events, a cancel, a mode, predict; a session is loaded with its history. | `sdk/typescript/test/sdk.test.ts`, run by `jolt test:sdk` (not `jolt test:byj`) |

| `initialize` answers protocol 1 to a client asking 1 or later, names byj and offers `loadSession`; an unknown method is -32601 and bad JSON -32700. | `acp-test/initialize-says-what-it-is` |
| A prompt is the session's turn: its tool calls as `tool_call` and `tool_call_update`, its answer as the agent's message, ended `end_turn`; the session's modes are the permission modes. | `acp-test/a-prompt-streams-its-turn-and-ends` |
| A permission request reaches the client as `session/request_permission`, naming the call with byj's four answers, and its answer is the gate's; allow-always is remembered for the session. | `acp-test/a-prompt-is-the-clients-to-answer` |
| `session/cancel` ends the running turn, the prompt answered `cancelled`. | `acp-test/a-cancel-ends-the-turn-cancelled` |
| `session/load` replays the history before it answers; a session already open is refused. | `acp-test/a-session-is-loaded-with-its-history` |
| `session/list` gives the project's saved sessions that ran a turn, newest first, as `SessionInfo`, a page at a time; a cursor it did not give is refused; a listed session loads. | `acp-test/saved-sessions-are-listed-for-an-editor-to-import`; `dev/acp-interop.sh`'s official client |
| `session/resume` opens a saved session with nothing of its history sent, and its next turn goes on from it; one already open is refused. | `acp-test/a-saved-session-is-resumed-without-its-history`; `dev/acp-interop.sh`'s official client |
| `session/delete` removes a listed session and its transcript, closing it first when open here; one another live process holds is refused and kept; an id not in a session id's shape is refused. | `acp-test/a-saved-session-is-deleted`; `dev/acp-interop.sh`'s official client |
| Standard output carries only JSON-RPC messages, one per line; stdin's end answers what was asked, closes every session (its lock released) and exits 0. | `acp-test/stdout-is-the-protocol-alone-and-the-client-going-away-ends-everything` |
| brainyard's ACP client and the official TypeScript one drive `byj acp`. | `dev/acp-interop.sh` (not part of `jolt test:byj`) |

| With a client's fs advertised, a read under `cwd` is the client's answer, an update reads and writes through it and leaves the disk, a write goes to it, a file it cannot answer for is read from the disk, and a file outside `cwd` is never asked of it; the path is under `cwd` as the client gave it, with the session's id; a client offering no fs is asked nothing. A tool given under a primitive's name takes its place, one of the name. | `acp-test/an-editors-fs-is-what-the-file-tools-read-and-write`, `acp-test/the-editor-is-asked-in-its-own-words`, `acp-test/a-tool-given-in-a-primitives-name-takes-its-place`; `dev/acp-interop.sh`'s official client (check 13) |
| A plain ACP client is sent no `_byj/event` and no `_meta` on a prompt's answer, though its session asked for events; a client that asked at `initialize` gets the turn's result and byj's events. | `acp-test/extensions-only-when-asked` |
| A client tool's call reaches the client with its arguments and the `tool_call`'s id, gated as any tool, and its answer is the tool's result; a schema this cannot read refuses the session, naming the keyword. | `acp-test/a-client-tool-is-called-over-the-wire` |
| A client hook is asked with the payload and its block stands; one that does not answer in time fails open, or closed when it says so; an event that is no hook point is refused. | `acp-test/a-client-hook-decides` |
| `_byj/predict` runs an inline signature whose fields are JSON Schema; a field this cannot read is refused. | `acp-test/predict-over-the-wire` |
| A fork is a new session, under the id the client chose, from a saved one's history, replayed before `session/new` answers; the original is left as it was; an id not in a session id's shape is refused. | `acp-test/a-fork-is-a-new-session-from-a-saved-one`, `sdk-test/a-session-is-forked-and-the-original-left-as-it-was`; both SDKs' "a session is forked" |
| A live session the connection did not start is listed by its label, asked a question and answered, watched until detached; a session nobody runs is said so. | `acp-test/a-live-session-is-listed-asked-and-watched`; both SDKs' "a live session it did not start", against a real `byj run --serve` |
| With an `outputSchema`, the turn's answer comes back also as that schema's data, a reply that does not match asked for once more; a schema this cannot read refuses the prompt before the turn. A nullable output's `null` is null, not a field missing. | `acp-test/an-answer-is-given-back-as-the-data-asked-for`, `agent-test/a-reply-is-held-to-its-schema`; both SDKs' "an answer is given back as the data asked for" |

## Phases

| bead | phase |
|---|---|
| `byj-g5n.1` | **Spike and decision** (blocks the rest): stdin read on a thread while stdout is written and a turn runs, EOF told from a pause, latency, a client killed mid-prompt; several sessions per process; ACP v1 driven by the official TS client and brainyard's; brainyard as a jolt git dependency. Closes by settling this RFC's proposed decisions with the numbers, and `dev/brainyard/dev/sdk_stdio_spike.clj` |
| `byj-g5n.2` | **Kernel prerequisites**: a caller's hooks survive `rebuild-ctx!`; `--agent` outside `--serve`; RFC-027's seam table brought up to date |
| `byj-g5n.3` | **`brainyard.sdk`**, in process, on the mock provider |
| `byj-g5n.4` | **`byj acp`**: ACP v1's methods; interop with brainyard's and the official client; an editor checked by hand |
| `byj-g5n.5` | **Extensions**: client tools, hook callbacks, `_byj/event`, `_byj/predict`, options and the turn's result; divergence `an-agent-sdk` |
| `byj-g5n.6` | **TypeScript SDK** and `jolt test:sdk` |
| `byj-g5n.7` | **Python SDK** |
| `byj-g5n.8` | **Guide, versions and distribution** (the person's decisions above); RFC-027 and RFC-008 updated |
| `byj-g5n.9` | **App templates on the SDK**: two starters and one real application |
| `byj-g5n.10` | **A structured answer**: `outputSchema`, the answer restated as data |
| `byj-g5n.11` | **Live sessions**: a session the client did not start, listed, asked, watched |
| `byj-g5n.12` | **A fork**: a new session from a saved one's history |
| `byj-g5n.13` | **An ACP editor** driven by hand: Zed 1.25.3 — a session, a streamed turn, its permission prompt, a cancel, and its thread import |
| `byj-g5n.14` | **An editor's thread import**: `session/list`, `session/resume`, `session/delete`, found missing in Zed by `.13` |
| `byj-ct5` | **Public**: `grumatic/brainyard-sdk`, MIT; `dev/sdk-publish.sh`, public READMEs, CHANGELOG, CI there; the templates install from the release |

## Known gaps

None open.
