# RFC-024 — MCP servers as tools

**Status:** implemented (`byj-gly.14`) for stdio. HTTP and OAuth are not ported,
and §"What is not here" says why that costs less than it sounds.

## Purpose

Specifies how a Model Context Protocol server's tools become this agent's
tools. brainyard sources: `agent/mcp/{client,integration,commands,permission}.clj`;
doc `design/mcp-agent-design.md`.

## Scope

**This layer decides** how a server is started, how its tools are named and
described, and what a call returns.

**It must not know** what any server does, or when a tool should be called.

**It hands** tool maps to the registry (RFC-007) and a discovery command beside
them. From the loop's point of view an MCP tool is a tool: listed, gated,
traced.

## Model

### A server is a subprocess speaking JSON-RPC

```clojure
;; .brainyard/config.edn
{:agent {:config {:mcp-servers {:everything {:command "npx"
                                             :args ["-y" "@modelcontextprotocol/server-everything"]
                                             :env {"TOKEN" "…"}}}}}}
```

Newline-delimited JSON-RPC 2.0 on stdin and stdout. The handshake is
`initialize` → `notifications/initialized` → `tools/list`, protocol version
`2024-11-05`, and each tool becomes `mcp$<server>$<tool>`.

**Servers start once per run**, in `main`, not once per turn — they are
processes, and `make-ctx` runs per turn. **A session does not wait for them**
(`start-all-in-background!`, byj-gly.186): each starts on a task of its own and
is `:starting` meanwhile, so a server whose handshake is slow — an `npx`
download, `mcp-remote`'s sign-in with a 180 s connect timeout — no longer holds
the prompt back; its tools are bound from the first turn after it is up, and a
failure is its problem, shown by `/mcp`. A one-shot `ask` still waits
(`start-all!`): its only question may need them. The exception is a server a
user tool calls (byj-gly.300): built while that server starts, the user tool
waits rather than being skipped, the ctx keeps the server in `:mcp-waiting`,
and a turn's start waits for it — up to `:mcp-wait-ms`, 30 s
(`session/await-mcp!`, `mcp/await-started`) — so the first question, a resumed
session's above all, has the tool. So is a server whose tools an agent's own
tool list names (`:visible-tools`, `mcp/named-servers`): a built agent's
first turn — on every A2A call that opens a session — went on without its
GitHub tools while the server started (byj-1wv.115.46). An agent with no
tool list waits for none. A server is `:starting` until the swap that
puts its tools in the box and moves the version, never earlier: it left after
its handshake, while its tools were still being listed, and the turn that had
waited saw no version to rebuild on (byj-gly.343, provenance P-37). A slash
command does not wait.

### Three things the transport insists on

Each was measured against the reference server
(`@modelcontextprotocol/server-everything`) before the client was written, and
each would have been a plausible-looking bug:

**Reads are raw bytes.** `clojure.java.io/reader` over a subprocess stream
delivers nothing until the process exits (RFC-006's measurement, behind
`fx/streaming-process`), so `.readLine` against a long-lived server blocks
forever. brainyard uses a `BufferedReader` and is right to on the JVM; here the
first probe hung until it was killed (divergence `mcp-reads-raw-bytes`).

**Replies are matched by `:id`.** A server interleaves notifications with
answers — the reference server sends `notifications/tools/list_changed` *before*
answering `tools/list` — so reading the next line and calling it the answer
yields a notification and reports no tools. The stub server in the tests does
the same thing, deliberately.

**One request at a time per server.** brainyard has the calling thread drain the
pipe until it sees its own id, which is safe only while calls never overlap; the
moment two do, one steals the other's reply. A lock per client costs three lines
and removes it.

**A write can fail too.** Writing to a dead child raises rather than returning,
so an implementation that handles a failed READ and not a failed WRITE handles
neither — and `connect-all!` reaches `tools/list` through the same path, so a
server dying after its handshake took the whole run with it. Both directions now
produce the same `{:error …}`, which is what lets one bad server cost the others
nothing (`byj-gly.71`, found by running this agent on this repository).

### What a call returns

MCP answers in content *blocks* — text, image, resource — with an `isError`
flag. Both are interpreted here:

| | |
|---|---|
| text blocks | joined |
| an image block | `[image image/png]` |
| a resource block | its text, else `[resource <uri>]` |
| `isError: true` | **`{:error-message …}`** |
| a JSON-RPC error | **`MCP error <code>: <message>`** |
| a server that has died | `the server stopped — stderr: <its last lines>` |

A death carries **what the server said on its way out**. It is spawned with
`:err :string`, and those last lines are the difference between *the server
stopped* and *the server stopped because `MYSERVICE_TOKEN` is missing*
(`byj-gly.72`).

Reading them needs a **bounded** wait, and only on the death path. jolt hands
`:err :string` over on deref, so derefing a live process blocks until it is not
one — but a dying server writes its reason and exits *concurrently* with the
client's next request, and refusing to wait at all loses the reason whenever the
machine is busy. That made the diagnostic a race that degraded silently under
load: on identical stress the old code reported the reason on 22 connects out
of 25, and the suite caught the missing case once in 25 runs (`byj-gly.92`,
`dev/flake-hunt.sh`). A 250 ms grace takes it to 25 of 25.

Every caller of `stderr-tail` is already reporting a death, which is what makes
waiting right there — and nowhere else — the right trade.

brainyard does neither: it hands the raw block vector to the model, never reads
`isError` — so a tool that failed is reported as a success with the failure
buried in a nested map — and keeps the JSON-RPC code and message in `ex-data`
where the model never sees them (divergences `mcp-results-are-flattened`,
`mcp-errors-carry-their-code`).

### Permission: what a server says about itself

An MCP tool is an external side effect on someone's machine, so its effects say
so. A tool declaring `readOnlyHint` carries `#{:mcp/call}`; **a tool that does
not is treated as though it writes** and carries `#{:mcp/call :mcp/write}`,
reachable by `--ask-effects mcp/write` or `--deny-effects mcp/write` through the
gate that already exists (RFC-012). A server's spec may say it instead:
`:read-only-tools` names, by the server's own names, tools that only read
whatever the server says — the npm GitHub server annotates none of its
tools — and those carry `#{:mcp/call}` too (`byj-1wv.115.44`; no test of
byj's holds it, since the one that did left with the agent builder). It is
the config's claim, written where the person reads it.

**A server for one agent.** A spec saying `:only-named? true` offers its
tools only to an agent whose `:tools` names them, by name or glob
(`mcp$kits$*`): the session's own agent, whose allow list is empty and so
reaches every other server's tools, does not reach these, and hiding still
wins (`agents/visible?`, applied to the registry itself whenever such a server
is configured: `agents-test/an-only-named-servers-tools-are-for-the-agents-that-name-them`,
`mcp-test/an-only-named-server-is-in-no-registry-but-the-agents-that-name-it`).
It is how a tool set that was once a specialist's alone, turned on by its
own config, is served from outside byj: another program's agents get their tool sets
from that program's own MCP server, named by them alone (RFC-070).

**A server may ask the person mid-call** (MCP's elicitation, `byj-ahf.20`).
byj says it can (`:elicitation {}` among its capabilities); a server that
sends `elicitation/create` while byj waits for a call's reply is answered
then and there, and its `ping` too — any other request it makes is answered
as not found, never left unanswered. The question goes to the person through
the session's `:permission-fn` — the prompt itself, never the classifier — as
`{:type :mcp/elicitation :tool :display "the MCP server `S` asks: …"}`; a yes
is `accept`, a no `decline`. Only a yes or a no is answered: a request for
fields to fill in is declined unasked, and with no one to ask (no
`:permission-fn`) the question is `cancel`led. A server that publishes what it is
given asks so, with every file it would send.

That is brainyard's posture — approval-required unless the server earns
otherwise — through this port's existing machinery rather than a second gate.
brainyard installs an MCP-specific hook because its generic gate ships inert;
ours is the same gate, so the annotation only has to reach the effects.

The read-only claim is the *server's*, and a server can lie. The effects are a
gate, not a guarantee.

### The run's live set, and `/mcp` (byj-gly.182)

Servers start into a **box** — one per run, shared by every session in it,
since a server is a process of the run's: `{:clients :tools :problems
:version}`. `start-server!`, `stop-server!` and `restart-server!` change it and
move its `:version`; `make-ctx` binds the box's tools as it stands when a ctx is
built, and keeps them out of `:base-tools` (a child adds the box again). A
session compares the box's version with the one its ctx was built at, at a
turn's or command's start (`session/refresh-mcp!`), and builds its tools and
prompt again when they differ — so a server started from one session is a tool
in every session from its next turn. A turn already running keeps what it
started with.

`/mcp` manages it, brainyard's command over this set:

| | |
|---|---|
| `/mcp` | every configured server and its state — running, died, failed, disabled, stopped — with its tool count and command |
| `/mcp NAME` | one in full: command, env keys (values masked), server info, tools, problem, the actions that apply |
| `/mcp NAME start\|stop\|restart` | for this run; `restart` is how a server that died comes back |
| `/mcp NAME enable\|disable` | `:enabled` written into the project's `:mcp-servers`, and applied: it starts or stops |
| `/mcp NAME tools` | its tools, hidden ones marked |
| `/mcp NAME hide\|show [TOOL]` | one tool, or every one of the server's (`mcp$NAME$*`), through `:hidden-tools` in the project config — hidden is gone from the registry, not just the listing |

### The catalogue, and `${VAR}` (byj-gly.183)

`/mcp find [WORDS]` searches a catalogue of servers worth offering —
`resources/brainyard/mcp/catalog.edn`, with a project's
`.brainyard/mcp/catalog.edn` over it by name (`mcp/catalog`). It is brainyard's
seed set (`create-seed-mcp-config`), adapted to a stdio-only port: a hosted
server goes through the `mcp-remote` bridge, a machine's own path is the bare
command, and the entries that need the HTTP transport are left out; the
reference servers of modelcontextprotocol/servers are added. Each hit says
whether it is already configured, ready, or which variable it still needs.

`/mcp add NAME` copies an entry's spec (`mcp/entry->spec`) into the project's
`:mcp-servers`; `/mcp add NAME [--env K=V]… -- COMMAND ARGS…` adds one of your
own. Either is applied at once: it starts when what it needs is set, and is
added `:enabled false`, naming what to set, when not. `/mcp remove NAME` stops
it and takes it out of the project config — with `--user`, out of the user
config too — along with the `:hidden-tools` globs that named it. A later layer
replaces `:mcp-servers` whole (RFC-013's merge is per key), so add, remove,
enable and disable write the full list in force, never a fragment.

**The MCP Registry** (`brainyard.mcp-registry`, byj-gly.185) is the second
place `/mcp find` looks, when `:mcp-registry?` (true) and there are words to
search for: `GET <:mcp-registry-url>/v0/servers?version=latest&search=…`.
Its `search` matches names as one string, so it is sent the longest word and
its answer narrowed to every word, as the catalogue is. A registry server
becomes a catalogue entry (`->entry`) from the first package this port can
start — npm through `npx -y pkg@version`, PyPI through `uvx pkg==version`, OCI
through `docker run -i --rm -e VAR… image` — else a hosted endpoint with no
headers of its own through `mcp-remote`; its required environment variables
become `:needs` and `${VAR}`. What it cannot start (a NuGet or MCPB package, an
endpoint that wants its own headers) is listed with the reason. The hits are
kept, so `/mcp add NAME` takes one by the short name shown — or by its full
registry name when two share one, which is also looked up if it is not among
them. **What the registry lists is third-party code**: it is added disabled,
with `:from "<registry-name>@<version>"` recorded, for a person to look at
(`/mcp NAME` shows the command and where it came from) before `/mcp NAME
enable` runs it. The picker's catalogue group is the local catalogue; the
registry is searched with words — in the picker, with the filter typed: a
pause sends `/mcp registry WORDS` quietly, and its hits come back as a group of
their own, third-party and added disabled, kept until the next search.

`${VAR}` in a spec's `:args` and `:env`, and `${VAR:-default}`, are expanded
when the server starts, from the run's environment with `.env` (`mcp/expand`,
`resolve-spec`); a variable a spec `:needs` is also passed into its
environment. So an entry names a token and config.edn never holds it — the
config writer would refuse a secret-shaped value anyway. `mcp/missing` is what
a spec needs that is not set.

`:mcp-servers` and `:mcp?` are read as a turn goes, like other keys (RFC-013):
an edit starts what was added or enabled, stops what was removed or disabled,
and restarts a running server whose spec changed (`apply-mcp-servers!`). The
full-screen renderer opens `/mcp` as a picker (RFC-028): a group per server,
its tools under it; Enter starts or stops a server or hides or shows a tool,
ctrl-r restarts.

## API

`brainyard.mcp`: `protocol-version`, `connect!`, `disconnect!`, `alive?`,
`list-tools`, `call-tool`, `flatten-content`, `safe-name?`, `tool-id`,
`schema->params`, `->tool`, `server-tools`, `connect-all!`, `disconnect-all!`,
`commands`, the live set: `box`, `box-clients`, `box-tools`,
`start-server!`, `stop-server!`, `restart-server!`, `start-all!`, and the
catalogue: `catalog`, `catalog-resource`, `entry->spec`, `expand`, `vars-of`,
`missing`, `resolve-spec`. `brainyard.mcp-registry`: `default-url`,
`search`, `parse`, `->entry`, `short-name`. Config: `:mcp-registry?`,
`:mcp-registry-url`.

`brainyard.session`: `mcp-command`, `mcp-rows`, `mcp-catalog-rows`,
`server-state`.

Config (RFC-013): `:mcp-servers` (`{}`), `:mcp?` (true). A server entry takes
`:command`, `:args`, `:env`, `:working-dir`, `:enabled`, `:timeout-ms`,
`:connect-timeout-ms`.

Command: `mcp$servers` — what is connected and what it offers.

## Invariants

| invariant | enforced by |
|---|---|
| A server's elicitation is answered by the person's prompt — yes accept, no decline, no one there cancel, fields declined unasked — and its ping meanwhile | `mcp-test/a-server-may-ask-the-person-mid-call` |
| A session's servers start in the background: it returns at once, the server `:starting`, and its tools arrive in the box. | `mcp-test/a-session-does-not-wait-for-its-servers` |
| A server's spec saying `:only-named? true` reaches only the agents whose `:tools` name its tools; hiding still wins. | `agents-test/an-only-named-servers-tools-are-for-the-agents-that-name-them`, `mcp-test/an-only-named-server-is-in-no-registry-but-the-agents-that-name-it` |
| A server is `:starting` until the swap that puts its tools in the box and moves the version (or records its failure): what waits on it then sees a version to rebuild on (byj-gly.343, provenance P-37). | `mcp-test/a-server-is-starting-until-its-tools-are-in-the-box` |
| A turn's start waits, up to `:mcp-wait-ms`, for a server still starting that a user tool calls, and the turn has the user tool; one calling a server nobody starts is still skipped (byj-gly.300). | `mcp-test/a-user-tool-waits-for-the-server-it-calls` |
| A turn's start waits, up to `:mcp-wait-ms`, for a server still starting whose tools the agent's tool list names, and the turn is shown them; with no tool list it waits for none (byj-1wv.115.46). | `mcp-test/an-agent-waits-for-the-server-whose-tools-it-names` |
| A server is started once: a start claims its name atomically, so a second start while one is in flight is refused; a stop or a disable while it starts disconnects what arrives, and a restart starts the new spec after it (byj-gly.222). | `mcp-test/a-server-that-is-starting-is-not-started-twice` |
| A config is reconciled against what the box runs (`:specs`, `:failed`, `:held`): applied twice or by two sessions it starts once; a changed spec restarts; a failed spec is not retried until it changes; one a person stopped stays stopped until its spec changes (byj-gly.223, .224). | `mcp-test/reconciling-compares-the-config-with-what-runs` |
| The run's servers end with the run: `mcp/stop-all!` in main's finally disconnects what runs and what arrives from a start in flight (byj-gly.225). | `mcp-test/the-runs-servers-end-with-it` |
| `/mcp registry WORDS` hands an open picker the registry's hits alone; the picker keeps them across refreshes, adds one by its full name, and sends nothing for one it cannot start. | `mcp-test/slash-mcp-registry-feeds-the-picker`, `tui-test/the-mcp-picker-lists-what-the-registry-has` |
| A registry server becomes a pinned npm/PyPI/OCI command or an `mcp-remote` endpoint, what it requires becomes `:needs`, and one it cannot start says why. | `mcp-test/a-registry-server-becomes-something-this-port-can-start` |
| find searches the registry after the catalogue; add takes a hit by its short name, asks for the full name when two share one, adds it disabled with `:from`, and starts nothing. | `mcp-test/slash-mcp-searches-the-registry-and-adds-from-it-disabled` |
| `${VAR}` and `${VAR:-default}` expand at start; a spec needs its `:needs` and every default-less `${VAR}`; what it needs is passed in. | `mcp-test/a-variable-is-expanded-when-a-server-starts` |
| The catalogue is a resource a project extends and overrides by name; its entries carry no machine's own path; add writes the spec, not the catalogue's words. | `mcp-test/the-catalogue-is-a-resource-a-project-can-extend` |
| find says what is ready, needed or added; add starts a ready entry, adds a needy one disabled, adds your own; remove stops it and drops its hidden-tools globs; twice or unknown is refused. | `mcp-test/slash-mcp-finds-adds-and-removes` |
| The picker offers the catalogue: Enter adds, ctrl-x removes a server and nothing else. | `tui-test/the-mcp-picker-offers-the-catalogue` |
| A server starts, stops and restarts in the run's box, moving its version; a ctx built now binds its tools and keeps them out of `:base-tools`; one that will not start is kept as its problem. | `mcp-test/a-server-starts-and-stops-while-the-run-goes-on` |
| `/mcp` lists, details, starts, stops and restarts; a stopped server's tools leave the next turn's prompt; hide/show and enable/disable write the project config, and a disabled server stops. | `mcp-test/slash-mcp-manages-the-servers-and-their-tools` |
| The picker folds a server's tools under it (→ opens, ← closes, a filter looks inside), each behind an indented bullet, and sends the row's `/mcp` line; ctrl-r restarts. | `tui-test/mcp-opens-a-picker-of-servers-and-their-tools` |
| A server's tools become `mcp$<server>$<tool>`, and a name that cannot be an id is reported rather than dropped. | `mcp-test/a-server-becomes-tools` |
| **A notification is not mistaken for a reply.** | `mcp-test/a-notification-is-not-mistaken-for-a-reply` |
| JSON Schema becomes params, with `required` deciding `:optional`. | `mcp-test/json-schema-becomes-a-malli-map` |
| Content blocks are flattened, and `isError` is an error. | `mcp-test/a-result-is-flattened-to-what-a-model-can-read` |
| A JSON-RPC error reaches the model with its code. | `mcp-test/a-protocol-error-reaches-the-model-with-its-code` |
| A tool that does not declare itself read-only is treated as writing. | `mcp-test/a-tool-that-does-not-say-it-is-read-only-is-treated-as-writing` |
| A server that will not start is reported, and costs the others nothing. | `mcp-test/a-server-that-will-not-start-does-not-cost-the-others` |
| **A failed write is an error, not a throw**, and one server dying mid-run costs the others nothing. | `mcp-test/a-write-to-a-dead-server-is-an-error-not-a-throw`, `mcp-test/one-server-dying-costs-the-others-nothing` |
| A death says what the server wrote to stderr, **including when the client notices before the OS has reaped it**. | `mcp-test/a-write-to-a-dead-server-is-an-error-not-a-throw`, `mcp-test/one-server-dying-costs-the-others-nothing` |
| MCP tools are gated and called like any other; a dead server says so rather than hanging. | `mcp-test/mcp-tools-are-tools-like-any-other`, `mcp-test/a-dead-server-is-reported-rather-than-hanging` |
| A subagent reaches the MCP tools its roster names, and no others. | `mcp-test/a-subagent-reaches-the-servers-its-roster-names` |

The tests run against a **stub server that is a real subprocess speaking the
real protocol**, awkward on purpose: it interleaves a notification, reports an
error through `isError`, and offers a tool whose name cannot be an id. The
transport is the part most likely to be wrong, so it is the part under test.
Verified separately against `@modelcontextprotocol/server-everything`: 15 tools
imported, `echo` called through the gated path.

### A subagent reaches what its roster names

`child-opts` hands a child `:base-tools`, which is whatever `main` put there,
MCP tools included — so a subagent whose `:tools` globs name `mcp$stub$echo`
gets it, calls it against the same server process, and gets nothing its globs
did not name. The clients travel with it so it can also ask what is connected;
they are processes the run owns, not the parent's conversation.

This RFC first claimed the opposite (`byj-gly.70`), from reading `child-opts`
carelessly rather than running it. `mcp-test/a-subagent-reaches-the-servers-its-roster-names`
now pins the behaviour, which is the useful part of the mistake.

## What is not here

- **HTTP and OAuth.** About 40% of brainyard's MCP code is a Streamable-HTTP
  transport with RFC 9728 challenge discovery, device/loopback OAuth, session
  ids and refresh-and-retry. It is not ported, and a remote server is still
  reachable by spawning `npx -y mcp-remote <url>` — which is what brainyard's
  own HTTP seeds do (divergence `mcp-is-stdio-only`).
- **No call proxy.** brainyard also routes calls through
  `mcp$tools :op :call`, a second way to do what the bindings already do
  (divergence `mcp-has-no-call-proxy`).
- **The registry is searched, not browsed**, and nothing is cached between
  runs. `/mcp find WORDS` asks it; the picker asks it with what is typed, once
  typing pauses (`tui/registry-pause-ms`, 700), through `/mcp registry WORDS`.
- **Resources and prompts.** `resources/*` and `prompts/*` are not reached.
  Tools are the value.
- **No automatic reconnect.** A server that dies stays dead until a person
  restarts it (`/mcp NAME restart`); its tools stay bound and say so when
  called, with its stderr, and `/mcp` lists it as `died`. brainyard is the same.
- **No `auth`.** brainyard's `/mcp NAME auth` is its HTTP transport's OAuth;
  this port is stdio only (divergence `mcp-is-stdio-only`).
- ~~Subagents do not inherit MCP tools.~~ **They do** — see below.
- **`${VAR}` reads the environment and `.env`**, not system properties as
  brainyard's also does.
