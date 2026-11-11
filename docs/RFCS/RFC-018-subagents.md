# RFC-018 — Subagents

**Status:** implemented — delegation (`byj-gly.16`), live instances
(`byj-gly.38`) and the parent trail (`byj-gly.39`). Work tiers and background
dispatch are not ported (gaps below).

## Purpose

Specifies what a named specialist agent is, how the loop reaches one, and what
a delegated turn may and may not see. brainyard sources:
`agent/core/tool.clj` (`defagent`, `do-call-tool--agent`, the three guards),
`agent/core/agent.clj` (`setup-agent`, `run-agent`, the instance registry),
`common/agent_roster.clj` (the roster and its prompt block),
`common/user_agents.clj` (declarative user agents), `common/explore_agent.clj`;
doc `design/agent-lifecycle-management.md`.

## Scope

**This layer decides** what an agent is, which of them exist, what a child turn
inherits, and when delegation is refused.

**It must not know** what any agent is for, or how a turn runs — it hands a
question to RFC-004's loop and gets an answer back.

**It hands** registry entries to the tool layer (RFC-007), a listing to the
session-context zone (RFC-003), and a child `ctx` to the loop.

## Model

### An agent is a spec, not a function

brainyard's agents are `defagent` forms compiled into the image, plus
*user agents* that are pure data. This port keeps only the data half: an agent
is a map, so the roster changes without a rebuild (RFC-001).

```clojure
{:name "explore"
 :description "Read-only investigator…"     ; what the PARENT model reads
 :instruction "You are an EXPLORE agent…"   ; what the CHILD model reads
 :tool-context "Prefer grep before read-file."   ; appended to the instruction
 :tools ["read-file" "grep" "list-dir" "search"] ; allow globs; nil = inherit
 :deny-tools ["agent$*"]
 :max-iterations 12

An agent may also name **its own loop** (`:loop`, a tree beside `agent.edn`)
and carry its own `predictors/` directory, in which case the spec is the whole
runnable workflow — RFC-022.
 :config {:tool-authoring? false}           ; config overrides for the child
 :lm {:model "claude-haiku-4-5-20251001"}}  ; a cheaper model for the specialist
```

Only `:name` and `:instruction` are required. `:name` matches
`^[a-z][a-z0-9-]*$` — brainyard's regex for user agents, and the same error
string.

### Where agents come from, most local first

| source | shape |
|---|---|
| `<project-dir>/.brainyard/agents/<name>/` | `agent.edn` + `instruction.md` + `tool-context.md` |
| `~/.brainyard/agents/<name>/` | the same |
| the built-in roster (`resources/brainyard/agents/`) | `index.edn` naming `<name>.edn`, whose `:instruction-resource` points at a prompt |

The directory form is brainyard's `user_agents.clj` layout minus its
`user$agent/` level: prose lives in markdown, where it is editable, and
`agent.edn` stays a readable header. A sidecar wins over an inline key, exactly
as a user tool's `.clj` body does (RFC-010).

**A name defined twice keeps the most local.** Unlike a skill, the shadowed one
does not stay reachable under another id: two agents with one name are a
mistake to report, not a pair to disambiguate. `load-agents` returns them under
`:shadowed` and the loop emits `:agents/shadowed`.

A built-in agent is therefore overridable: dropping `.brainyard/agents/explore/`
into a project replaces the shipped one entirely.

### Dispatch is a tool call

An agent registers as `agent$<name>`, `:kind :agent`, so it is listed, gated
and traced like everything else — `list-tools :type "agent"` is the RFC-007 gap
this closes, and the permission gates (RFC-012) already match `agent$*` globs
because a subagent dispatch is an ordinary tool call.

```clojure
{"name" "agent$explore"
 "args" {"question" "where is the retry budget enforced?"
         "agent-context" "the loop lives under src/brainyard/"}}
```

It returns the child's answer, what it cost, and a handle:

```clojure
{:agent "explore" :answer "…" :iterations 4 :terminated-by :answer-channel
 :usage {:input-tokens 9182 :output-tokens 412 :llm-calls 4}
 :subagent-id "explore/amber-otter-42" :askable? true :ask-hint "…"}
```

brainyard's ids are the bare `explore-agent` (and `user$agent$<name>`); ours are
`agent$<name>` so that discovery's family split — the segment before the first
`$` — groups the roster (divergence `agent-tool-ids`).

The roster costs the prompt **one line per agent**, as skills do (RFC-017) and
for the same reason: an agent's instruction is written for the agent, not for
its caller. Measured on this repository, the shipped roster of one plus the
substrate text is 3,611 chars (~900 tokens) — 22,048 against 18,437 with
`:enable-subagent-calls false`. Most of that is the substrate, which is paid
once however many agents there are: twelve agents whose instructions total
91,188 characters cost 3,867. Agents are therefore kept out of the
`### Agent Tools` full-spec block, and `question`/`agent-context` are explained
once in the substrate rather than per agent.

The layer as a whole costs more than the roster, and it is worth seeing the
split before leaving it all on:

| | session-context |
|---|---:|
| `:enable-subagent-calls false` | 18,437 |
| roster, substrate and the five `agent-registry$*`/lifecycle commands | 22,048 (**+3,611**) |
| plus the five `meta-agent$*` authoring commands (`:agent-authoring? true`) | 24,893 (**+2,845**) |

Authoring is the expensive half — five full tool specs and a page of guidance —
and it is the half a given session is least likely to use, so it is **off by
default** (divergence `authoring-off-by-default`). Delegation, the roster and
the instance commands are on: those are what the layer is for.

### A dispatch leaves the agent alive

brainyard dispatches every subagent with `:auto-close? false`, and the reason is
the whole economics of delegation: the expensive part of a subagent is the
context it built, so throwing it away at the first answer means the next
question pays for it again. The result therefore carries a handle —
`:subagent-id`, `:askable? true` and an `:ask-hint` naming the two commands —
and the instance stays in a registry with its own previous turns.

```
agent$explore          → "…" + :subagent-id "explore/amber-otter-42"
agent-registry$ask     → the SAME instance, which still has its turns
agent-registry$list    → what you have, with :status, :answers, :idle-ms
agent-registry$detail  → the above plus :last-answer and :turns
agent-registry$close   → it, and everything it dispatched, transitively
```

**How long an instance lasts depends on who owns the registry.** A session
builds one ctx and reuses it, so an instance outlives the turn that dispatched
it — brainyard's case, since its agents always run in a session. A one-shot
`ask` builds the registry with the ctx and drops both when it returns, so the
instance is that turn's. The hint says which (`:subagent-scope`,
`:askable-until`): telling a model it can follow up later, in a run where there
is no later, is how a promise like that stops being worth reading. It reached a
user once — a live `ask` ended with "you can ask it a follow-up question" about
an instance that no longer existed (`byj-gly.51`).

An instance id is brainyard's `<agent>/<colour>-<animal>-<n>`, from the same
word lists, and resolves from its bare suffix or with a leading colon, because
a model that carried `amber-otter-42` out of a result should not be punished for
it. The **number is a counter, not a random street number** as brainyard's is:
`rand` cannot promise uniqueness on a jolt worker thread — five freshly created
`blk` threads minted the same colour, animal and number, so two instances shared
one id and an ask meant for an evicted instance reached a live one (provenance
P-13).

**An instance here is data**: the spec, its turns and a few counters. Its `ctx`
is rebuilt per ask from the same config, which costs a roster and tool-registry
re-read — what every turn pays anyway. brainyard retains the live agent object,
with its sandbox, memory manager and BT, which is why its close path has so much
more to do and why its snapshots can embed an unprintable `#object[…]`
(divergence `subagent-instances-are-data`). It also means an instance's turns
could be persisted with the session (RFC-016); they are not yet.

**The cap is on live instances.** At `:max-subagents-per-session` (8), a new
dispatch evicts the least recently asked **idle** instance and names it in
`:evicted-subagents`; a `:running` one is never evicted, so the cap may be
briefly exceeded rather than a turn being pulled out from under itself. That is
brainyard's `evict-subagents-to-cap!` exactly.

**`:keep? false`** runs a child turn and registers nothing — brainyard's
`:auto-close? true`. One caller uses it: a skill whose frontmatter says
`dispatch: agent` (RFC-017), which runs its procedure in an agent built from the
skill itself. The turn is still an instance's, dispatched by its caller: it
carries `:agent-instance-id` and `:agent-owner` as a kept one does, so its
trajectory record is a child's. Without them it was recorded as a top-level
turn, the skill's instruction as if the person had asked it (byj-gly.376).

### Writing one mid-turn

`meta-agent$create` / `$validate` / `$list` / `$read` / `$delete` are
brainyard's `meta-agent$*` with this port's authoring shape (RFC-010, RFC-011).
They are **off by default** — `:agent-authoring? true` offers them — for the
reason the table above gives: they are the layer's expensive half and the half
a session is least likely to use. brainyard registers them always.
A created agent is written as the same three files a hand-written one has, is
**callable in the same turn** — the registry entry is added live, as a user
tool's is — and is named in the roster listing from the next turn, since the
prompt's zones are fixed once a turn starts. Redefining a name bumps `:version`
and keeps `:created`.

**What the model may write is prose and nothing else** — `name`, `description`,
`instruction`, `tool-context`. That is brainyard's rule ("you NEVER bind
tools"), and here it is also a fence: an agent's `:tools` is an allow list its
child inherits *instead of* its caller's, so a model that could write one could
hand a subagent a palette wider than its own. `:config` and `:lm` are withheld
for the same reason — they would let a turn choose its own model, or its own
permission mode (divergence `authored-agents-are-prose-only`).

`meta-agent$validate` with a `sample` question runs the **unsaved** spec once,
writing nothing and keeping no instance: brainyard's dry run, which forks its
registry and restores it in a `finally`.

### How hard the work is

A dispatch may name a **work tier** — brainyard's `:work-tier`, described to the
model as a shape of work and never as a model:

| tier | what it is for |
|---|---|
| `light` | a lookup, a status read, a mechanical transform |
| `standard` | ordinary work with a clear success test — the default |
| `deep` | synthesis across sources, judgement under ambiguity, or prose a person will act on |

The tier is resolved into a model through `:agent-lm-tiers` (RFC-005), clamped
into the agent's own window, and **consumed**: it never reaches the child, because
a specialist that knew its own tier would be invited to hedge.

An agent states its window in its own file — `:tier`, `:min-tier`, `:max-tier` —
and `:agent-tier-map` retunes by name for one you cannot edit. brainyard keeps
this table in config alone, keyed by defagent type, because its specialists are
compiled in (divergence `agent-tier-in-the-spec`). The shipped `explore` agent
declares brainyard's own entry for it: `:light` by default, up to `:deep`.

**A request outside the window is clamped, not refused.** Failing the dispatch
would turn a cost question into an outage; obeying it would make the cap
decorative. An inverted window widens to `:min` rather than pinning everything
to it. An agent that pins its own `:lm` outranks the tier, as an explicit
`:lm-config` does in brainyard.

`:agent.subagent/tier` reports the choice **even when the table is empty** —
`{:agent :requested-tier :tier :clamped :model :applied}` — so "why is this
still on the session model" has an answer rather than a silence.

### Who may drive an instance

You may ask and close only what you dispatched — `:owner` is the caller's own
instance id, or `"root"` — except that the root may ask any subagent of its
session, as brainyard's `authorize-ask` lets a root caller ask its own subtree.
A person's typed call runs as the root, so the subagent whose transcript is on
screen is theirs to ask, whoever dispatched it (`byj-gly.344`, RFC-028).
brainyard's fences are longer because it has several roots and shared
sessions; here they reduce to ownership and that root rule, plus brainyard's
two state rules: you cannot ask yourself, and a `:running` instance is busy
rather than broken — for a model, which is told to poll. A person's typed
ask (`:person-call?`) of a running instance waits for its turn instead: a
child parked as a background task works while the root is idle, so the line
typed in its view would otherwise be refused the moment it was sent
(`byj-gly.345`; `:agent.subagent/waiting` says so, `:subagent-ask-wait-ms`,
600 s, bounds it, and an instance gone meanwhile is said to have ended). Closing
stays the dispatcher's (`agents-test/only-the-dispatcher-may-ask-or-close`,
`agents-test/a-persons-ask-waits-for-a-running-instance`).

```
Not owned by you (owner: root). You may only ask subagents you dispatched.
You cannot ask yourself.
Instance explore/amber-otter-42 is running; cannot ask until idle. Poll agent-registry$detail.
```

### A child inherits nothing it was not handed

This is the whole point of delegating: the parent spends a few hundred tokens
on the answer instead of tens of thousands on the search that produced it.

| | shared with the child |
|---|---|
| the question, `:agent-context` | **yes** — they are the only channel |
| tool registry | a **fresh** one, built from the agent's `:tools` / `:deny-tools` |
| config | the parent's, with the agent's `:config` over it |
| `:session-id`, `:working-dir`, `:project-dir` | yes, so spills and sandboxes stay in one tree (RFC-014, RFC-016) |
| `:permission-fn`, hooks, `:on-event` | yes — a child may not escape the gate that binds its parent |
| st-memory, iterations, trajectory | **no** — fresh per dispatch |
| `:history-context`, the parent's transcript | **no** — the only history a child sees is its OWN previous turns, on a later ask |
| usage | rolled **up**: the child's tokens are added to the parent's `:usage` and counted in `:subagent-calls` |

brainyard additionally hands down the caller's last `:parent-trail-k` (3) turns.
That is not ported — see the gaps.

### A credential belongs to a provider, not to a conversation

An agent may name its own `:lm`. When it changes only the `:model`, it keeps
its caller's credentials — that is what merging always did and is right.

When it names a different **`:provider`**, it does not. `(merge (:lm ctx)
(:lm agent))` handed a child on `:openai` the caller's Anthropic key *and*
`https://api.anthropic.com/v1`: a failure that reads as a provider outage
rather than a config error, and a credential travelling to whatever endpoint
the agent named (`byj-gly.91`).

So a cross-provider child resolves its own credentials, for its own provider,
from **this run's environment** — `.env` included, through the `:env-fn` the
CLI resolved, by the same `create-lm` the CLI used. What carries over is what
belongs to the conversation rather than the provider: streaming, caching,
temperature. The agent's own `:lm` still wins over all of it, so a spec may pin
a key explicitly.

With no key for that provider anywhere, the child gets **none** — which fails
honestly, where the caller's key fails confusingly.

That is also the answer to whether an agent can carry credentials of its own
(`byj-gly.90`): **by provider, from the environment**. An agent cannot hold a
key nobody else on the machine has, and needs no file of its own to use a
provider its caller is not using. The same threading fixes two quieter cases —
a work tier and a predictor's `:lm` both resolved keys from the bare process
environment, so a key found only in a `.env` was invisible to them.

### The parent trail

A child may be handed the caller's last `:parent-trail-k` turns, rendered into
its `:user-context` after the turn block:

```
## Parent Trail (last 3 turns from caller)
[Turn 1 · summary]
  Q: …
  A: …
```

Three things follow brainyard exactly. The turns are the **newest k, oldest
first** (`take-last`). They are rendered by the **same formatter as a turn's
own history**, because a child that has learned to read `[Turn n]` should not
have to learn a second shape for the same thing. And they are **numbered from 1
within the trail**, not by the caller's real turn numbers — telling a child it
is looking at turns 7 to 9 invites it to ask about turn 6, which it cannot see.

**It is off by default, and the agent is what turns it on**
(divergence `parent-trail-off-by-default`). brainyard shows every child three
turns; here `:parent-trail-k` defaults to 0, because most delegation is a
question that stands on its own and the caller's conversation is a cost paid on
every dispatch to serve the few cases that are not. brainyard's own budget
policy says as much more quietly: the trail is the section it sheds first under
pressure.

`agents/trail-k` therefore reads the **agent's own spec first** —
`:parent-trail-k`, or the same key in its `:config` — then the caller's config,
then 0. brainyard resolves it on the caller alone, which is coherent while
everyone gets a trail and leaves nobody a way to ask for one at 0. So a
specialist that needs three turns asks for them:

```clojure
{:name "reviewer" :instruction "…" :parent-trail-k 3}
```

a project can still turn it on everywhere as brainyard has it, and an agent in
such a project can opt back out with 0.

A caller with no turns behind it contributes no block rather than an empty one.

This is the one place a child deliberately sees the parent's conversation, and
it sits against the section above rather than replacing it: `child-opts` still
withholds the caller's rendered `:history-context`, the trail is a bounded and
labelled quotation of it, and `:agent-context` remains the route for what the
caller means to say on purpose.

The raw turns reach the dispatch because the session tree copies
`:previous-turns` inward beside the rendered `:history-context` — one `:in`
entry in `session.edn`. A dispatch has to cut its own `k`, and cannot re-cut a
string someone else made.

### Three guards, and where they live

brainyard carries depth and the call chain in dynamic vars
(`proto/*call-depth*`, `*call-chain*`). Here they are **config keys on the child
ctx**, because ebb conveys bindings per fiber and at run time (RFC-009), and a
guard that depends on which thread ran a node is not a guard.

| guard | key | default | refusal |
|---|---|---|---|
| kill switch | `:enable-subagent-calls` | `true` | the tools are never registered |
| depth | `:max-agent-call-depth` | 3 | `Agent call depth limit reached (1/1). Cannot call 'agent$explore'.` |
| cycle | — | — | `Circular agent call detected: explore -> plan. Chain: explore -> plan` |
| budget | `:subagent-max-turns` | 32 | `Subagent turn budget exhausted (32/32 child turns this session).` |

Every refusal is an ordinary `{:error-message …}` tool result, so the turn
continues and the model can answer without the specialist.

**The cycle guard pushes the target, not the caller.** brainyard pushes the
*caller's instance id* (`:explore-agent/lime-mole-8966`) and tests the *target's
registry id* (`:explore-agent`), so a cycle is caught only when the chain holds
a bare keyword; `a → b → a` through instances is not detected. Ours pushes the
name it dispatches, so `a → b → a` is refused at the third hop (divergence
`subagent-cycle-guard`).

**The turn budget is this port's own.** `:max-subagents-per-session` is
brainyard's cap on live instances and evicts rather than refuses, so nothing in
brainyard bounds what delegation *spends* — a loop that dispatches, evicts and
dispatches again runs until the iteration ceiling stops it. `:subagent-max-turns`
(32) counts every child turn, dispatch and follow-up ask alike, because both are
a whole turn of a model's time and bounding only the first would bound nothing
(divergence `subagent-turn-budget`). The counter is an atom created once per
session and passed down; a bare `ask` gets a fresh one, so a one-shot question is
bounded too.

### Cancellation

The tool's `:fn` runs on `blk` and waits on the child's `ask-task` with
`fx/run-value!!`, which cancels the task it is waiting on when its thread is
interrupted (RFC-009). So a cancelled parent turn stops the child structurally —
its in-flight LLM call and any subprocess tree it started — and the
`InterruptedException` propagates as a cancellation rather than a tool failure.
Nothing survives the parent: there are no detached children to orphan, which is
the one simplification the missing instance registry buys.

## Protocol

```
tool-dispatch (RFC-004)
  └─ call-tool "agent$explore"            hooks + permission gate (RFC-011/012)
      └─ agents/dispatch
          ├─ refuse?  depth / cycle / turn budget  → {:error-message …}
          ├─ evict idle instances to the cap       → :evicted-subagents
          ├─ register the instance                 → :agent.instance/created
          └─ run-instance-turn!
              ├─ child ctx: fresh registry, +1 depth, chain+name, its OWN turns
              ├─ fx/run-value!! (agent/ask-task question child-opts)
              └─ append the turn, roll usage up, status back to :idle

  └─ call-tool "agent-registry$ask"       … the same run-instance-turn!, after
                                            the ownership, busy, depth and
                                            budget checks
```

Events: `:agent.instance/created` `{:id :agent :owner}`,
`:agent.instance/closed` `{:id :reason}` (`:closed`, `:evicted` or `:cascade`),
`:agent.subagent/dispatch` `{:agent :id :depth :chain :question}`,
`:agent.subagent/ask` `{:id :agent :question}`,
`:agent.subagent/result` `{:id :agent :answer :iterations :terminated-by :usage}` (the id the dispatch or ask named, so a front end can tell which child ended),
`:agent.subagent/refused` `{:agent :reason}`, `:agents/shadowed` `{:name :kept :dropped}`.

**A child turn's own events say whose they are.** The child runs with its
caller's `:on-event`, wrapped so every map it emits carries `:subagent-id` and
`:subagent` (the agent's name). The innermost child wins: a grandchild's events
pass through its parent's wrapper on the way out and keep the grandchild's id.
The dispatch, ask and result are the CALLER's acts and are not tagged by the
child they concern. This is what lets a front end show a child's work apart
from its caller's (RFC-028) rather than as though the caller had done it.

## API

`brainyard.agents`: `roots`, `parse-agent`, `load-agents`, `builtin-agents`,
`visible?`, `refusal`, `dispatch`, `describe`, `agent-tool`, `commands`,
`agents-and-tools`, `persona-config` (what a spec makes of a config: its
instruction, tool globs, own `:config`, iteration cap and predictors — shared
by a child turn and a headless session started `--agent`, RFC-031),
`loop-path` (where a spec's own `:loop` is), `child-lm`, for tiers `tier-entry`, `clamp-tier`, `resolve-work-tier`,
for instances `instance-id`, `caller-id`, `resolve-id`,
`instance-summary`, `claim!`, `close-instance!`, and for authoring `write-dir`,
`persist!`, `authoring-tools`.

`brainyard.history` (shared with RFC-004's session loop, which is where it used
to live): `clip`, `turn-summary`, `format-previous-turns`, `append`. A session's
turns and an instance's turns are the same thing at different scopes, and
rendering them differently would mean a model reading two formats for one idea.

Config (RFC-013): `:subagents?` (true — offer the roster at all),
`:enable-subagent-calls` (true), `:max-agent-call-depth` (3),
`:max-subagents-per-session` (8 live instances), `:subagent-max-turns` (32),
`:agent-roots` (nil = the three above), `:builtin-agents?` (true),
`:subagent-max-iterations` (12), `:max-listed-agents` (20),
`:agent-tier-map` ({}), `:agent-lm-tiers` (RFC-005),
`:agent-authoring?` (**false**), and set by the
kernel rather than by a user: `:agent-depth`, `:agent-chain`,
`:agent-instruction`, `:agent-instance-id`, `:agent-owner` (who dispatched the
instance: another instance, or "root" — recorded on each of its turns in the
session's trajectory, so the agent page can rebuild the tree, byj-gly.298.1),
`:visible-tools`, `:hidden-tools`.

## Invariants

| invariant | enforced by |
|---|---|
| A dispatch that keeps nothing (`:keep? false`, a `dispatch: agent` skill) still runs as an instance its caller owns, and leaves nothing to ask. | `agents-test/a-dispatch-that-keeps-nothing-is-still-its-callers-child` |
| A child's turn records who dispatched it — "root", or the instance that did, at any depth — and the root's own turn records none. | `agents-test/a-childs-turn-says-who-dispatched-it` |
| **A child on another provider gets that provider's credentials, never its caller's**, and none at all rather than the wrong ones. | `agents-test/an-agent-on-another-provider-does-not-get-the-callers-key`, `agents-test/a-key-for-the-childs-provider-is-taken-from-the-runs-environment` |
| A child that changes only its model keeps the caller's credentials, and may pin one itself. | `agents-test/an-agent-that-only-changes-model-keeps-the-callers-credentials`, `agents-test/an-agent-may-still-pin-a-credential-itself` |
| **A child is shown nothing of the caller unless its own spec asks**; when it does, the newest `k` turns, oldest first. | `agents-test/a-child-is-shown-nothing-of-the-caller-unless-it-asks` |
| Every child turn's `:agent.subagent/result` names the instance its dispatch or ask named. | `agents-test/an-instance-stays-alive-and-remembers` |
| A child turn's own events carry its `:subagent-id`, its caller's do not, and the dispatch and result carry the question and the answer. | `agents-test/delegation-runs-a-child-turn` |
| The trail reads like the history it is, numbered from 1 within itself, and a turn without one says nothing about one. | `agents-test/the-trail-reads-like-the-history-it-is`, `agents-test/the-trail-reaches-the-childs-prompt-and-nothing-elses` |
| A dispatch carries it, while the caller's rendered history is still withheld. | `agents-test/a-dispatch-carries-the-trail-into-the-childs-opts` |
| An agent is `:name` + `:instruction`; sidecar prose wins over an inline key; a bad name or a blank instruction is reported, not thrown. | `agents-test/an-agent-is-a-spec` |
| Roots run most local first and a duplicate name keeps the most local, reporting the one it dropped. | `agents-test/roots-and-precedence` |
| An agent is a `:kind :agent` registry entry that `list-tools :type "agent"` finds and `get-tool-info` describes. | `agents-test/an-agent-is-a-tool` |
| A dispatch runs a full child turn and returns only its answer, iterations and usage. | `agents-test/delegation-runs-a-child-turn` |
| The child sees the agent's tools and no others; `:deny-tools` removes what `:tools` allowed. | `agents-test/an-agent-narrows-its-tools` |
| The child gets none of the parent's history or st-memory, and the parent gets none of the child's trajectory. | `agents-test/a-child-turn-is-isolated` |
| The child's usage rolls up into the parent's, with a dispatch count. | `agents-test/usage-rolls-up` |
| Depth, cycle and budget each refuse with their message as a tool result, and the turn continues. | `agents-test/the-three-guards` |
| A dispatch is an ordinary tool call, so a `agent$*` deny glob refuses it before any child turn starts. | `agents-test/a-dispatch-is-an-ordinary-tool-call` |
| A tier is clamped into the agent's window, an inverted window widens, an unparseable request is not a request, and config retunes an agent's entry. | `agents-test/a-tier-is-clamped-into-the-agents-window` |
| A dispatch runs at its tier's model, reports the choice even when the table is empty, and never forwards the tier to the child. | `agents-test/a-dispatch-runs-at-its-tier` |
| `:enable-subagent-calls false` registers no `agent$*` tool and puts no roster in the prompt. | `agents-test/the-kill-switch` |
| The roster costs one line per agent in the prompt, whatever the instructions weigh. | `agents-test/the-prompt-cost-is-bounded` |
| The shipped roster is explore, plan, edit, claude and init — no bead-keeper. This repository's own `beads` is a project agent under `.brainyard/agents/`, with its instruction beside it, holding its shell to `bd` and refusing to invent a tracker. | `agents-test/the-shipped-roster`, `agents-test/this-repositorys-bead-keeper` |
| Cancelling the parent turn cancels the child's LLM call. | `cancellation-test/a-cancelled-turn-stops-its-subagent` |
| A dispatch returns a handle; asking it again runs the same instance, which sees its own previous turns and nobody else's. | `agents-test/an-instance-stays-alive-and-remembers` |
| The hint claims the lifetime the run actually has: a session's instance outlives the turn, a one-shot `ask`'s does not, and a child inherits the registry it was handed. | `agents-test/a-hint-does-not-promise-what-the-run-cannot-keep` |
| At the cap a dispatch evicts the least recently used idle instance, names it, and the evicted id stops resolving. | `agents-test/the-cap-evicts-the-least-recently-used` |
| Only the dispatcher may ask or close an instance; a running one is busy; an id resolves from its bare suffix. | `agents-test/only-the-dispatcher-may-ask-or-close` |
| Closing an instance closes what it dispatched, transitively, and nothing else. | `agents-test/closing-an-instance-closes-what-it-dispatched` |
| An agent defined mid-turn is written as three files and is dispatchable in that same turn. | `agents-test/an-agent-can-be-defined-mid-turn` |
| A definition — checked against `agents/agent-schema`, malli, each rule with its own message — reports every problem and writes nothing; a dry run keeps no files and no instance; redefining versions it; delete removes the files and the tool, and refuses a built-in. | `agents-test/defining-an-agent-is-checked-and-versioned` |
| Claiming an instance for a turn is atomic, so two parallel tool calls cannot both run one instance. | `agents-test/only-the-dispatcher-may-ask-or-close` |

## Known gaps

- **Instances do not survive the process.** They live in an atom for the life of
  the session; a resumed session (RFC-016) comes back with none. brainyard's do
  not survive either, but its snapshot at least records that they existed.
- **No cross-root or cross-session asks.** brainyard has several roots and
  sessions that share an agent, with fences for each (`agent-registry$ask`
  root-to-root, `:session-id` filtering, A2A peers as instances). Here there is
  one root, so the fences reduce to ownership.
- **The trail is bounded by construction, not by budget pressure.** brainyard
  drops it first when the prompt is over budget — a named `:bump-parent-trail`
  at priority 55, shedding the oldest turn per pass and re-rendering the header
  with the new count. This port's budget is tier-based elision over iteration
  records (RFC-014); there is no priority list to slot into, and building one
  to hold a single entry would be the wrong shape. So the trail is bounded when
  it is made — `k` turns, each clipped by `:max-question-chars` and
  `:max-answer-chars` — and never shrinks after that. Under real pressure that
  is a weaker guarantee than brainyard's, and it costs less here than it would
  there: the trail is off unless an agent asked for it.
- **A subagent's own calls never detach.** A root's dispatch of an agent does:
  it is an ordinary tool call, so past `:fast-eval-timeout-ms` it becomes a
  background task and the caller gets the marker (RFC-019). Inside a subagent
  nothing detaches, because its turn would end before it could collect the
  result — brainyard's rule and ours.
- **The shipped roster is five agents**: `explore`, `plan`, `edit`, `claude`
  and `init`, each costing the prompt one line (575 characters for the two
  added in `byj-gly.41`, which is the roster block working as designed).
  `claude` (RFC-039) and `init` (RFC-040) are loops of their own, whose
  `claude$*` and `init$*` commands are registered only for their turns.
  **`beads` is not shipped** (`byj-gly.316`). The bead-keeper was a
  port-only addition — brainyard has no such agent — and served this
  repository's workflow: every project's prompt carried its line, and its
  "delegate the bookkeeping" invited a call even where there is no Beads
  workspace. It is this repository's project agent now,
  `.brainyard/agents/beads/` (`agent.edn`, `instruction.md`), kept in git by a
  `.gitignore` exception for `.brainyard/agents/`. Any project can keep one
  the same way.

  What made `plan` and `edit` portable is that each one's VALUE is its
  discipline, and the discipline needs no new mechanism: `plan`'s three phases
  (can I plan, write it, grade it) and `edit`'s probe → apply once → verify →
  roll back. brainyard runs the latter through `edit$apply`, a single atomic
  command, but its instruction already carries the inline pipeline for when that
  is unbound, and `update-file`'s `:expect-count` preserves the match-count
  guard the pipeline rests on. Both drop their dossier machinery, which exists
  to feed agents this port does not have (divergences
  `edit-agent-has-no-transaction`, `plan-agent-has-no-dossier`).

  **`exec` is deliberately not ported.** It drives a todo list item by item, and
  its pre-flight refuses unless it is handed a todo-agent dossier whose
  post-flight passed. With no `todo-agent` (`byj-gly.17`) every turn would
  GATHER: it would be an agent that structurally cannot work, which is worse
  than an absent one. `eval` needs the refine gate (`byj-gly.12`), `research`
  needs web search, and `router` needs its decision table. Those remain
  `byj-gly.41`.
- An agent's `:config` can hold its shell to a set of programs —
  this repository's `beads` carries `:bash-allow-commands ["bd"]` and the
  shipped `edit` its own list. The shipped `explore` carries none — a list of
  the programs that read (cd, git, rg, fd, find, tree, ls, wc, curl, bd) was
  never complete and refused lines that only read — only
  `:bash-deny-commands` refusing curl's sending and writing flags and
  `bd create`/`update`/`close` (byj-gly.355, byj-gly.356, divergence
  `explore-shell-is-held-to-reading`). An agent's
  `:bash-deny-commands` is added to the session's, never in its place, and
  its `:bash-allow-commands` narrows the session's, never widens it: with a
  session list, only what both allow (`agents/allowed-commands`, a glob on
  either side matching the other's entry), and when they have nothing in
  common the agent has no shell — `bash` hidden, since an empty list would be
  no limit (byj-gly.357; `agents/persona-config`,
  `agents-test/the-shipped-roster`); enforced by the command gate
  (RFC-012) rather than by its instruction alone. What that cannot do is stop a
  listed program's own escapes; the sandbox is the boundary.
