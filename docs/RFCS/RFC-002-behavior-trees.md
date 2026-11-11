# RFC-002 — Behavior trees

**Status:** implemented.

## Purpose

Specifies the interpreter every loop runs on: a small behavior tree whose
structure is EDN and whose leaves name functions by keyword. It is brainyard's
`ai.brainyard.agent.core.bt` cut to the node kinds the loops use, plus two
kinds brainyard does not need because its trees are code — `:scope` and
`:include` — which let one data tree nest another.

## Scope

**This layer decides** what a node kind means and in what order children tick.

**It must not know** what a turn, a tool, a predictor or a session is. It sees
keywords, a registry and an st-memory atom.

**It hands** `:success` / `:failure` up the tree, and `ctx` (with the leaf's own
node opts under `:node`) down to leaf fns.

## Model

```
[:sequence  opts & children]   :success iff every child succeeds, left to right
[:fallback  opts & children]   first child to succeed wins; :failure if none
[:repeat    opts child]        tick child until (:until opts) holds or (:max-n opts)
                               ticks; a child :failure fails the repeat; running out
                               of ticks is :success (emits :bt.repeat/exhausted)
[:condition opts]              ((registry (:fn opts)) ctx) truthy → :success
[:action    opts]              ((registry (:fn opts)) ctx); :failure only if it
                               returns :failure; a throw is :failure too
[:scope     opts child]        tick child against a FRESH st-memory
[:include   opts]              spliced by `expand` before anything ticks
```

Every node is `[kind opts & children]` and `opts` is always a map; `:id` is
conventional and used in error events. An opts value in the `config`
namespace (`:config/max-iterations`) resolves from `(:config ctx)` at tick
time, so a tree names a budget without hard-coding it.

**A leaf sees its own node.** `tick-leaf` passes `(assoc ctx :node opts)`, so a
node can *declare* what its fn works on — `:predictor`, `:zones`, `:in`, `:out`
— instead of the fn hard-coding it (RFC-005).

**The ctx can be given things after the tree starts.** A tree runs against one
ctx for as long as it runs — a whole session, for the session tree. A
`:ctx-box` (an atom of a map) in that ctx is merged over it for every leaf and
for every `:config/…` opt, at tick time, so what the session learns between
turns (an edited config, and the tools, hooks and zones rebuilt from it —
RFC-013) is what the next leaf sees, without restarting the tree. Without a box
the ctx is used as given.

**Errors become state, not exceptions.** A throwing leaf is `:failure`, and the
error `{:node :message :data}` is written to st-memory as `:bt/error` and
emitted as `:bt.node/error`, so a sibling under a `:fallback` (the ReAct
loop's repair node) can see what went wrong. **A cancellation is not an
error**: it propagates (RFC-009 rule 7).

### The tick is an effect

brainyard's effect BT engine (`behavior-tree` `nodes_task`, design §15/§16),
ported: `tick-task` returns a Task (RFC-009). Composites are `m/sp` bodies that
`m/?` their children; every leaf runs on `blk` through `fx/task-of`, because
leaves block. An action may return a Task instead of a status — it is run and
its value read the same way (brainyard's `lift`), so leaves move onto effects
one at a time; tool dispatch already has. Cancelling the Task stops the tree
structurally: the in-flight leaf's thread is interrupted, nothing after it
runs. `tick` is the synchronous edge (`run!!`) over `tick-task`.

### `:scope` — nesting without collisions

```
[:scope {:in  {inner-key outer-key}      ; seed the fresh memory
         :out {outer-key inner-key}}     ; copy back; :* = the whole inner memory
  child]
```

The child's `:st-memory` is a new atom; the outer atom is visible as
`:parent-memory`. `:out` is applied whether the child succeeded, failed or was
cancelled, so an outer fallback can inspect a failed inner loop.

A scope with `:cancellable key` registers, while its child runs, a fn under
`key` in `(:cancellers ctx)` (an atom). Calling it cancels **only the child**:
the child's memory is merged with `:on-cancel` and the scope succeeds, so the
tree around it carries on. The child races a dataflow variable; its outcome is
reified first so a failing child cannot leave the race waiting. A real error in
the child still fails the scope. Without `:cancellers` in ctx the opt is inert. This is how the session loop
runs a complete ReAct loop per turn without either loop's `:answer` or
`:terminated` leaking into the other (RFC-004).

### `:include` — trees built from trees

`[:include {:tree "brainyard/loops/react.edn" …}]` is replaced by the named
tree, recursively, before validation. Include opts other than `:tree` are
merged over the included root's opts (so an include can re-`:id` it). A cycle
throws with the chain. Ticking an unexpanded include throws — the loader always
expands.

## API

`brainyard.bt`:

| fn | contract |
|---|---|
| `(tick-task ctx node)` | A Task settling with `:success` or `:failure`; fails for a malformed node (non-map opts, unknown kind, unregistered fn, unexpanded include) and when cancelled. |
| `(tick ctx node)` | `run!!` over `tick-task`: the status, the failure rethrown, or InterruptedException when the waiting thread is interrupted (after cancelling the tree). |
| `(expand tree load-tree)` | The tree with every `:include` spliced, via `(load-tree name)`. |
| `(fn-refs tree)` | Every `:fn` and `:until` keyword the tree names. |
| `(validate tree registry)` | `nil`, or `"unregistered fns: …"`. |

A loop is named by `brainyard.agent/resolve-tree` — what `--loop`, an agent
spec's `:loop`, an `:include` and `flow$status`'s `:tree` all go through: a
bare name (`react`, `plan-act-eval`) is the bundled
`brainyard/loops/<name>.edn`; anything else is a classpath resource, else a
file (byj-gly.374).

`ctx` keys the interpreter reads: `:registry`, `:st-memory`, `:config`,
`:on-event`, `:cancellers`, `:ctx-box`. Everything else passes through to leaves. Events it
emits: `:bt.node/error`, `:bt.repeat/exhausted`, `:bt.scope/cancelled`.

## Invariants

| invariant | enforced by |
|---|---|
| A loop's bare name is the bundled loop of that name; a resource path and a file path still work; an unknown name says how a loop is named. | `agent-test/a-loop-is-named-by-its-name-a-resource-or-a-file`. |
| Sequence, fallback, repeat, condition and action semantics as above; a throwing leaf is a failure recorded as `:bt/error`. | `agent-test/bt-semantics`. |
| A scope's child cannot see or overwrite outer keys except through `:in` / `:out`. | `session-test/scope-isolates-memory`. |
| Includes expand recursively, re-`:id` from include opts, and a cycle throws. | `session-test/include-expansion`. |
| The bundled session tree, expanded, is fully registered. | `session-test/include-expansion`. |
| An action may return a Task. | `cancellation-test/an-action-may-return-a-task` |
| Cancelling a tick interrupts the blocked leaf, runs nothing after it, and records no `:bt/error`. | `cancellation-test/cancelling-a-tick-stops-the-tree-and-is-not-an-error` |
| A `:ctx-box` is laid over the ctx each leaf and each `:config/…` opt sees. | `session-test/an-edited-config-applies-from-the-next-turn`, `session-test/a-config-edit-rebuilds-what-the-next-turn-is-shown` |
| A cancellable scope cancels only its child, applies `:on-cancel`, succeeds, and deregisters; real errors still fail it. | `cancellation-test/a-cancellable-scope-cancels-only-its-child`, `…-still-fails-for-real-errors` |

## Known gaps

- Pause/resume (brainyard's per-pause `m/dfv` beside a `Condition`,
  `check-interrupt-cancel-pause!`) is not ported; cancellation is structural
  only (divergence `turn-cancel-is-structural`).
