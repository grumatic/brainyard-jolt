# RFC-020 — Memory

**Status:** implemented (`byj-gly.19`, `byj-gly.53`) as an external store plus
recall over this project's own session history. brainyard's memory subsystem is
deliberately not ported.

## Purpose

Specifies how durable knowledge — what was learned in an earlier session and
written down — reaches a turn. brainyard sources: `components/memory/**`
(the L1/L2/L3 store), `agent/core/memory.clj`, `common/context_actions.clj`
(recall into the prompt); docs `core/memory.md`.

## Scope

**This layer decides** how a store is reached and what a turn may ask it.

**It must not know** what a memory is, how it is indexed, or where it lives.

**It hands** three commands to the tool registry (RFC-007).

## Model

### A store is a command, not a subsystem

brainyard's memory is SQLite with FTS5 and sqlite-vec embeddings, three layers,
a bi-temporal context graph with communities, a procedural graph, an audit
table, six schema migrations to version 2.4.0, a core.async capture sidecar, two
consolidation reducers and about twenty-two `memory$*` commands.

None of that is portable to jolt, and most of it is not this layer's business.
What an agent needs is two verbs, and those are a command away:

| config | default | given | returns |
|---|---|---|---|
| `:memory-recall-cmd` | `["bd" "memories"]` | the query | matches, as text |
| `:memory-read-cmd` | `["bd" "recall"]` | a key | one entry |
| `:memory-store-cmd` | `["bd" "remember"]` | the content (`--key` when given) | confirmation |

`bd` is the default because a project that uses beads already has a store, and
it is already populated — this repository's seven memories were written before
any of this existed. Any program that takes a query and prints text works; a
test points the three at shell scripts.

**A missing store is not an error.** If the recall command's program is not on
PATH the tools are simply not registered, the same rule the agent roster
follows: a project that keeps no memory has no memory tools, rather than tools
that fail when reached for. Each verb can also be switched off on its own with
an empty vector, and `:memory? false` removes the layer.

### The record was already being kept

brainyard captures a Q&A episode at the end of every turn into L2, and
consolidates L2 into L3 on a cadence. Writing those into an external store would
be the wrong shape twice over: `bd remember` holds seven curated entries for
this whole repository, and a transcript poured into it would drown them — for
the person who shares that store as much as for the agent.

It is also unnecessary, because **the record already exists**. A session
snapshot holds its turns (RFC-016): question, answer, how it ended, and the tool
trail. That is brainyard's L2, written since persistence landed. What was
missing was a way to read it.

`memory$history` searches it: every token of 3+ characters must appear in a
turn's question or answer, newest first, over the most recent sessions. The
**current session is excluded**, as brainyard's L2 recall excludes it — those
turns are already in the history context the model is looking at.

So capture stays automatic and costs nothing new, and the two verbs stay
separate in a way that is worth keeping straight:

| | holds | written by |
|---|---|---|
| `memory$history` | what was **asked and answered** | the session, every turn, automatically |
| `memory$recall` | what someone decided was **worth writing down** | a person, or the model calling `memory$remember` |

`memory$history` needs no store at all — the snapshots are ours — so it is
there whenever `:memory?` is on, even where `bd` is not installed.

### Nothing is injected

brainyard renders a `## Recalled Memory` block into **every turn's user
message** — up to `:recall-limit` (10) × `:memory-recall-snippet-chars` (600),
with a `:recall-mode "conditional"` that drops L2/L3 hits failing a keyword
overlap test. Recall there is a push, and the gate exists to claw back what the
push costs.

Here recall is a **pull**: `memory$recall` is a tool, and memory costs the
prompt three lines until the model decides it is worth more. That was the point
of the exercise — the default prompt is the thing being defended, not the
recall.

### What `search` does not do

RFC-007 recorded a gap: brainyard's `search` has a memory section and ours did
not. It stays that way, for a reason found by building it: brainyard's store
returns a **countable list**, so no hits means no section, while an external
store returns **text**. Asked for something it does not have, `bd` prints
`No memories matching "x"` — a perfectly good answer for `memory$recall`, and
noise in a multi-section search result that would appear on every single call.
There is no general way to tell a store's polite miss from its hit, so `search`
does not guess (divergence `search-has-no-memory-section`).

### What a person curates: `/memory` (byj-gly.327)

The model's verbs are recall and remember; a person also needs to see what
was kept and throw out what was wrong. `/memory` lists the store's memories
— `:memory-list-cmd` (`bd memories --json`), whose contract is JSON, an
object of key to content or an array of `{key, value}` — searched on every
word in key or content, the way `/tools` is. `show KEY` says one whole;
`edit KEY` hands the terminal to the person's editor with the memory in a
temporary file and stores what comes back under the same key through
`:memory-store-cmd --key`, as `memory$remember` with a key does; `delete KEY`
runs `:memory-forget-cmd` (`bd forget`). The full-screen renderer shows them
in a picker (RFC-028). Still a command, still the store's: the port never
learns where a memory lives.

### What a turn is told (byj-gly.335)

Each turn is told, by key and first line, the few memories its question
shares rare words with — the contents stay a pull. RFC-042 decides it and
records the measurements.

### Bounds

A command is run with a deadline (`:memory-timeout-ms`, 15s) and its output is
capped (`:memory-max-chars`, 4000) before the model sees it. A store that fails,
times out or is missing yields `{:error …}` on an explicit call and nothing
anywhere else: a memory lookup failing is not a turn failing.

## API

`brainyard.memory`: `default-cmds`, `cmd`, `available?`, `search-history`,
`commands`; for `/memory`, `parse-listing`, `list-memories`, `matching`,
`forget!`, `rewrite!`.

Config (RFC-013): `:memory?` (true), `:memory-recall-cmd`, `:memory-read-cmd`,
`:memory-store-cmd`, `:memory-list-cmd`, `:memory-forget-cmd`, `:memory-hint?` (RFC-042), `:memory-max-chars` (4000), `:memory-timeout-ms` (15000),
`:memory-snippet-chars` (500, the answer excerpt in a history hit).

## Invariants

| invariant | enforced by |
|---|---|
| Each verb runs its configured command with what the model gave it, and blank input is refused before the command runs. | `memory-test/a-store-is-a-command` |
| A failing store is reported, never thrown, and its output is capped. | `memory-test/a-store-is-a-command` |
| A store that is not on PATH, `:memory? false`, or an empty command each leave the corresponding tools unregistered. | `memory-test/a-project-without-a-store-has-no-memory-tools` |
| No memory's content reaches the prompt; the layer's standing cost is three lines while a store exists and nothing when it does not. A matching question's turn is told keys and first lines (RFC-042). | `memory-test/memory-is-never-injected`, `session-test/a-turn-is-told-the-memories-its-question-is-about` |
| `search` has no memory section, and `memory$recall` answers either way. | `memory-test/search-has-no-memory-section` |
| The store's command runs in the session's project, not wherever the process started — a team member runs from the checkout (byj-gly.255). | `memory-test/a-store-runs-in-the-sessions-project` |
| `/memory` lists through the store's JSON listing, searches every word in key or content, deletes with the store's forget, and an edit is stored under the same key only when it changed and is not empty; headless, edit says how instead; `:memory? false` turns it off. | `memory-test/a-person-lists-rewrites-and-deletes-through-the-store`, `session-test/memory-lists-searches-edits-and-deletes-the-projects-memories` |
| Earlier sessions' turns are searchable, newest first, excluding the current one; nothing extra is captured to make it work. | `memory-test/earlier-sessions-are-the-record-that-was-already-being-kept` |

## Known gaps

- **Nothing is consolidated.** brainyard reduces batches of L2 episodes into L3
  facts on a cadence, so old turns become summaries. Here a turn stays a turn
  until its session is pruned (RFC-016: 20 sessions, 14 days), and what becomes
  durable is whatever someone writes down.
- **No capture into the curated store.** Only `memory$remember` writes there,
  deliberately: a store shared with a person is not a transcript.
- **No tiers, no consolidation, no graph.** Whatever structure the store has is
  the store's. The port cannot promote, summarise or expire anything, and does
  not pretend to.
- **No relevance ranking.** How a query matches is the store's business —
  `bd memories` is a substring match, so one distinctive word finds more than a
  phrase, which the tool's description says.
- **The store is not scoped by project.** `bd` resolves its own workspace from
  the working directory; another store might not, and nothing here enforces it.
