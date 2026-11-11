# RFC-022 — Authoring a runnable agent: build, check, run

**Status:** implemented (`byj-gly.59`–`.62`): the `:tool` leaf, `check` over
eleven classes of problem, the memory chain with machine-verified leaf
contracts, `brief`/`status`/`probe`, hash-guarded `patch`, and an agent spec
that names its own loop. Whether an authoring LOOP earns its place is a
decision, not a gap (`byj-gly.63`).

## Purpose

Specifies how an agent — a person's, or the model's — goes from "I want a
workflow that does X" to a **runnable agent**: a named artifact with its own
loop, its own steps and its own tools, that can be checked before it is run and
run without a rebuild.

Prior art consulted: **mycelium** (github.com/mycelium-clj/mycelium), a Clojure
workflow framework whose premise is *"humans architect and AI agents
implement"* — cells with explicit input/output contracts, a manifest, a
compile-time validation checklist, per-cell **briefs** that scope an LLM's
context to one component, `test-cell` in isolation, `workflow-status`, and
hash-guarded `patch`. The verbs transfer almost entirely. The execution model
does not, and §"What does not transfer" says where.

## Scope

**This layer decides** how a workflow is described, what makes it valid, and
what a caller may ask about it before running it.

**It must not know** what any particular workflow is for.

**It must not add a way to run host code.** An authored step is data — Tool
Lisp (RFC-010) or a predictor (RFC-005) — never host `eval`.

**It hands** a validated tree to RFC-002, and commands to RFC-007.

## The problem, precisely

Everything a workflow is made of is already data:

| part | RFC | authorable today |
|---|---|---|
| the loop | RFC-002 tree EDN | yes — `--loop other.edn` |
| a model step | RFC-005 predictor spec | yes — a `user/…` EDN file |
| a tool | RFC-010 Tool Lisp | yes — `tool$define` |
| an agent | RFC-018 `agent.edn` | yes |
| what it costs | RFC-021 | yes |

And yet **an agent cannot build a workflow**, for one reason:

```clojure
(def registry
  {:react/init init-action
   …
   :predictor/run predictor-run-action})    ; 19 entries, every one a compiled fn
```

Every leaf names a **compiled** fn. `:predictor/run` is the only leaf
parameterized by data. So a model can write a tree, but every leaf it names
must already exist in `src/` — which means a rebuild, which means the loop is
not data in the way the rest of the system is. That is the gap, and it is one
node kind wide.

Three things are missing, in the user's words:

- **build** — a leaf an agent can implement, and a way to assemble the parts
- **check** — every problem with a composition, without running it
- **run** — run it, and run *one node* of it in isolation

## Model

### 1. An agent names its own loop — one concept, not two

mycelium has a manifest naming cells and edges. Here the agent spec (RFC-018)
already names an instruction and tools; it gains three keys and becomes the
manifest:

```clojure
;; .brainyard/agents/triage/agent.edn
{:instruction "You triage incoming reports."       ; or instruction.md
 :tools       ["bash" "read-file" "user/classify"]
 :loop        "loop.edn"        ; the tree this agent runs, resolved beside agent.edn
 :config      {:max-iterations 8}}                 ; config overrides for this agent
```

`:predictors` is **not** a key: an agent's `predictors/` directory is searched
first when it exists, so listing them would be a second place to keep in step
with the first. `:config` needed no mechanism either — `agents/child-opts`
already merged it — but building this found it being silently overwritten by a
computed `nil` when nothing else set `:max-iterations`.

No new artifact type. An agent that names its own loop **is** the runnable
workflow, `agent$triage` already dispatches to it (RFC-018), and everything
RFC-018 says about depth, cycles and turn budgets still holds.

### 2. The keystone: a leaf an agent can implement

One new node kind, mirroring `:predictor/run` exactly:

```clojure
[:action {:id   :triage/classify
          :fn   :tool/run                       ; as :predictor/run names its fn
          :tool "user$tool$classify"            ; a Tool Lisp tool (RFC-010)
          :in   {:text :question}               ; tool arg ← st-memory key
          :out  {:severity :severity}           ; st-memory key ← result key
          :on-error :failure}]                  ; :failure (default) or :success
```

`:tool` is to RFC-010 what `:predictor` is to RFC-005. With it, every leaf is
one of three things:

| leaf | what it is | who writes it |
|---|---|---|
| `:fn` | a compiled registry fn | this repository |
| `:predictor` | a signature + params, as data | a person or the model |
| `:tool` | a Tool Lisp body, as data | a person or the model |

That is what makes the framework *agentic* rather than a linter: a model can
now implement a node. It cannot reach host `eval` doing so — Tool Lisp is
interpreted and allowlisted, and a tool leaf runs through the same permission
gate (RFC-012) and effects declaration as any other tool call.

**A tool leaf is bounded like a tool call, not like a turn**: the Tool Lisp step
and tool-call budgets apply, and a cancelled turn stops it (RFC-009).

Values pass through as data. A predictor's input becomes prompt text and is
stringified; a tool takes what the blackboard holds.

**A call can go wrong three ways and only one of them carries
`:error-message`.** A gate-blocked call returns `{:hook-blocked true :reason …}`
with no error at all, and the first implementation read that as success — which
would have made the tree a way *past* the permission stack. The leaf fails on
any of `:error-message`, `:error` or `:hook-blocked`, and
`flow-test/a-tool-leaf-is-gated-like-any-other-tool-call` is what caught it.

### 3. Check: every problem, before anything runs

`bt/validate` today returns one string, for one class of problem:

```clojure
(defn validate [tree registry]
  (let [missing (remove #(contains? registry %) (fn-refs tree))] …))
```

**check** replaces it with the checklist mycelium runs at compile time, adapted
to a behaviour tree, returning *every* problem — the idiom
`predictor/validate-spec` already uses, because a caller fixing one problem at a
time pays a round trip per problem.

| # | check | why it exists |
|---|---|---|
| 0 | the node's kind is one the interpreter dispatches on | **a model invented `[:tool …]`; `bt/tick` throws on it (`byj-gly.65`)** |
| 1 | every `:fn` is registered | today's check |
| 2 | every `:predictor` loads and its spec validates | a typo is otherwise a runtime failure mid-turn |
| 3 | every `:tool` is a registered tool, and its Tool Lisp body validates | same |
| 4 | a node's `:in`/`:out` name fields the predictor or tool actually has | the commonest authoring error |
| 5 | every `:config/…` reference is a real key | **today a typo silently reads nil** |
| 6 | every `:zones` entry is a zone the context layer knows | a misspelled zone silently sends nothing |
| 7 | every hook `:event` is a real event name | a hook that never fires looks like a hook that passed |
| 8 | node `:id`s are unique | a duplicate id makes a trace ambiguous |
| 9 | `:include` has no cycle | exists in `bt/expand`, as a throw |
| 10 | `:repeat` has a resolvable `:until`/`:max-n` | an unbounded repeat is a hang |
| 11 | **the memory chain**: every st-memory key a node reads is written by a node that must precede it | §4 |
| ~~12~~ | ~~reachability~~ — **withdrawn**, see §4 | |

Checks 1–10 need nothing new, and are built. Check 11 does, and is not.

Two limits found building them. A tool's `:returns` was **prose, not a
schema**, so a tool leaf's `:in` could be checked against its params and its
`:out` could not be checked against anything. Since RFC-030 almost every tool's
result is a malli schema (its `:output-schema`, `:returns` until byj-gly.238). Now each `:out` source key is checked against the
keys that schema declares (`schema/result-keys`): a map's keys, or every key
of an `:or` whose branches are all maps. A mistyped key used to write nil,
silently; now it is named with the keys there are (byj-gly.201,
`flow-test/a-tool-leaf-is-checked-against-the-tool-it-names`). Some schemas
cannot say what a result holds: prose, a vector result (`list-tools`), or a
`:map-of`. For those, `:out` stays unchecked, because "no such key" is a claim,
and a guess would refuse a tree that is right.

And check 5 paid for itself immediately: `:config/max-turns` in the shipped
session loop named a key that was set but undeclared, because a session kept its
own defaults in a second map. Four such keys had no `BY_*` name and no
`--print-config` row, and a `config.edn` that set one was told it was unknown.
They are now in the one schema, and `check` needs no extra keys to check a
session loop (`byj-gly.64`, RFC-013 "One schema, not several").

**Check 12 is withdrawn.** "A node after an unconditional terminator" is not a
structural property of a behaviour tree: whether a later sibling runs depends on
whether the earlier one *fails*, and that is what its fn does, not what the tree
says. A check that cannot be right is worse than no check (RFC-021), so it is
gone rather than approximated.

### 4. The memory chain, and what it costs

mycelium can infer a schema chain because a cell declares
`{:input [:map …] :output [:map …]}` and data flows along edges. Here there are
no edges: leaves share **one st-memory blackboard**, and a leaf's fn reads and
writes it freely. Nothing declares what `:react/init` writes.

So check 11 requires each leaf to declare its contract. The RFC's open question
was whether that belongs in the registry map or as metadata on the fn;
**metadata won, and the deciding fact was measured**: jolt reads var metadata
and a var is callable, so the registry holds vars —

```clojure
(defn ^{:reads #{:question :iterations}
        :writes #{:answer :tool-calls :usage …}}
  think-act-action [ctx] …)

(def registry {:react/think-act #'think-act-action …})
```

`bt/tick` is unchanged, and the contract cannot drift onto another entry because
it is attached to the fn it describes.

**Three leaves are node-determined**, and reading them from the fn would be
wrong: `:predictor/run` and `:tool/run` write what their `:out` names and read
what their `:in` names, and `:hooks/fire` reads what its `:payload` names (minus
`:config/…` values, which are not blackboard reads). `flow/contract` is the
union of the fixed half and the node's. `:tool/run` also appends its call to the
turn's record, `:trajectory-iterations` (RFC-025, `byj-gly.322`). That key
is one of the engine's own `bt/record-keys`, as `:bt/error` is, so neither
the write observer nor `flow/probe` counts it as the node's write.

**The annotations are machine-verified.** `bt/*leaf-observer*` diffs st-memory
around every leaf; `flow/write-observer` reports any write a leaf did not
declare, and the suite runs under it. This is not theoretical: it immediately
caught `:predictor/run` writing `:usage` through `run-node-predictor`, which no
amount of reading the `:out` mapping would have suggested. Reads are declared
and *not* verified — a map lookup on a dereferenced blackboard cannot be
observed — so an over-declared read costs a false problem and an under-declared
one costs a missed check, but neither can break a run.

**How the chain is computed.** Abstract interpretation over the tree, wrong only
in the safe direction:

| node | what it guarantees |
|---|---|
| `:sequence` | a child runs only if the previous SUCCEEDED, so writes thread left to right |
| `:fallback` | a child runs only if the previous FAILED, so it cannot rely on a sibling; the set on exit gains only what EVERY child writes |
| `:repeat` | the body may run zero times, so nothing it writes is guaranteed after it, and inside it a node sees only what precedes it |
| `:scope` | a barrier: the child starts from `:in` alone, the outer gains `:out` |

**A typo and an ordering mistake get different messages**, because they have
different fixes: a key nothing in the tree writes *"nothing in this tree writes
it"*; a key written by something that does not always run first *"nothing
guarantees it here"*.

**An unannotated leaf makes the chain unsound**, since its writes are unknown
and a key it provides would look unwritten. `check` says so once, naming them,
and skips the chain rather than reporting problems that are not problems. A
tree built from `:predictor` and `:tool` leaves is fully annotated by
construction, so the authoring case is unaffected.

Annotations are *mechanism* (they describe a compiled fn), so they live in
`src/` beside the fn, not in the tree — RFC-001 holds.

### 5. Brief and status: the authoring loop

mycelium's `cell-brief` is the idea worth taking whole. To implement one node, a
subagent needs that node's contract and nothing else — not the whole tree, not
the repository.

```clojure
(flow$brief {:agent "triage" :node :triage/classify})
;; => {:node      :triage/classify
;;     :kind      :tool
;;     :implements "user/classify"
;;     :status    :missing
;;     :reads     {:question "the user's report, a string"}   ; from upstream :writes
;;     :writes    {:severity "one of low|high"}               ; from downstream :reads
;;     :upstream  [:triage/init]
;;     :downstream [:triage/route]
;;     :examples  [{:in {:text "disk full"} :out {:severity "high"}}]}
```

`:reads`/`:writes` are *derived from the neighbours* — what upstream provides
and what downstream needs — which is what makes a brief a contract rather than a
description. A subagent given this can write the Tool Lisp body, test it with
`tool$test` (RFC-010) or `flow$probe`, and stop.

As built, each read names **the nodes that actually write it** and whether it is
guaranteed, and each write names **the nodes downstream that read it**. A write
nothing reads is flagged `:unused` — either the node does too much, or a
consumer is still missing. Keys the *caller* reads when the tree finishes
(`agent/turn-result`) are `:exported` rather than unused, without which every
turn's `:usage` would read as dead.

```clojure
(flow$status {:agent "triage"})
;; => {:nodes 7 :ready 5 :missing 1 :failing 1
;;     :detail [{:node :triage/classify :kind :tool :status :missing}
;;              {:node :triage/route :kind :predictor :status :failing
;;               :problem "predictor user/route: output :severity not declared"}]}
```

`status` is what makes the loop terminate: an authoring agent works until
nothing is `:missing` or `:failing`, and `check` passes. That is the whole
build loop, and it needs no new control structure — it is a tree like any other.

### 6. Patch, not rewrite

An agent asked to add a node should not rewrite the tree file. mycelium's
`patch --expect-hash` with named operations is the safer shape and transfers
directly:

```clojure
(flow$patch {:agent "triage"
             :expect-hash "4e4b9111bb67"
             :ops [{:op :add-node :parent :triage/iteration :after :triage/init
                    :node [:action {:id :triage/classify :tool "user/classify"}]}
                   {:op :set-opt :node :triage/iterate :key :max-n :value 5}]})
;; => {:hash "9a1c…" :applied 2} | {:error "hash mismatch — the tree changed"}
```

Three things make this safer than rewriting the file, and all three matter to an
agent editing a tree it cannot see all of:

- **The hash guard.** A patch names the tree it was written against, so an edit
  computed from a stale read is refused rather than applied to something else.
  `check` reports the hash a patch must quote — and it is the hash of the
  **source**, which differs from the expansion's for any tree with an
  `:include`.
- **The source is patched, not the expansion.** `:include` is spliced at load
  time (RFC-002); patching the expanded tree would inline every included subtree
  into the file and silently unshare it.
- **`check` runs before the write**, the memory chain included. A node placed
  where the blackboard cannot feed it is refused with the problem it would have
  caused, so **the tree on disk is always runnable.**

Ops: `:add-node` (`:parent`, `:after` — a sibling id, `:first` or `:last`,
`:node`), `:remove-node`, `:set-opt` (a nil `:value` removes the opt),
`:rename-node`.

### 7. Run, and probe one node

Running is mostly done: `agent$triage` dispatches it (RFC-018), `by ask -a` runs
it, `:on-event` traces it. Two things are missing.

**probe** — mycelium's `test-cell`, which is how a node is debugged without
running the workflow that reaches it:

```clojure
(flow$probe {:agent "triage" :node :triage/classify
             :memory {:question "the disk is full"}})
;; => {:status :success :wrote {:severity "high"} :read [:question] :elapsed-ms 12}
```

**paths** — `bt/paths`, the tree's execution paths, so `enumerate-paths`'s
question ("can this node ever run?") is answerable, and check 12 has something
to stand on.

## Steps are seen as they run (`byj-gly.299`)

A `:tool/run` or `:predictor/run` step emits `:agent.tree/step-start`
`{:node :kind :name :args}` as it begins and `:agent.tree/step-end` `{:node :kind
:name :result :error?}` as it ends — its inputs each one short line, and as its
result the keys its node keeps (`:out`), each short — and a step may report
what it is doing between them with `:agent.tree/step-progress` `{:line}`. The
TUI draws a step as a call in the turn or in a subagent's view (RFC-028): a
workflow — agent$claude's loop, `plan-act-eval` — had shown only its question
and its answer, for minutes, and its judgements nowhere.

## API (proposed)

`brainyard.bt`: `check` (replacing `validate`), `node-index`, `paths`,
`patch`, `tree-hash`.

`brainyard.agent`, `brainyard.session`: registries hold **vars**, and every leaf
fn carries `^{:reads #{} :writes #{}}`. `tool-run-action` for the `:tool` leaf.

`brainyard.bt`: `*leaf-observer*` — bound, it reports what each leaf actually
wrote — and `node-kinds`, the interpreter's own set, so a checker cannot drift
from what `tick-task` dispatches on.

`brainyard.flow` (new): **`check`, `config-refs`, `ctx-opts`, `known-zones`,
`check-tree-name`, `contract`, `all-writes`, `check-memory-chain`,
`write-observer`, `node-kind`, `find-node`, `status`, `brief`, `probe`,
`tree-hash`, `apply-op`, `patch`, `commands`** — built. All five `flow$*` commands are built. `ctx-opts` takes the check's options from a live ctx, so a tree
is checked against the capabilities it would actually run with.

`brainyard.bt`: **`nodes`, `node-id`** (new). `validate` stays as the structural
question *can this run at all*.
Named `flow$` because `agent$<name>` already means *dispatch to that agent*
(RFC-018), so the verbs need a family of their own.

CLI: **`by flow check|status|brief|probe|patch`** (built, `--json`,
`--expect-hash`).

Config (RFC-013): `:flows?` (true).

## What does not transfer

mycelium is a **dataflow DAG of pure cells** with Malli contracts on every edge.
This is a **behaviour tree over a shared blackboard**, where a leaf's value is a
status (`:success`/`:failure`) and its effect is a mutation. Consequently:

- **No per-edge schemas.** There are no edges. The blackboard is the contract,
  which is why check 11 is the analogue and why it needs annotations mycelium
  gets for free.
- **No dispatch predicates.** A `:fallback` with a `:condition` child is how a
  tree branches; there is nothing to add.
- **No joins or fork-join parallelism.** Parallel tool dispatch is off by
  default here for a measured reason (divergence `parallel-tools-off`,
  provenance P-2). Background work is RFC-019's task registry, not a join.
- **No resilience policies per node.** Retry lives in the provider (RFC-006),
  cancellation in RFC-009. A per-node circuit breaker would be a second
  mechanism for a problem that already has one.
- **Halt/resume** is RFC-016 session persistence plus RFC-019's `task$wakeup`.

Taking any of these because mycelium has them would be adding a second way to
do something this system already does once.

## Staging

Each stage is useful alone and leaves the tree runnable.

| stage | delivers | bead |
|---|---|---|
| 1 | ~~the `:tool` leaf + `check` 1–10 + `by flow check`~~ **done** | `byj-gly.59` |
| 2 | ~~`:reads`/`:writes` annotations, the dev-mode assertion, check 11~~ **done** | `byj-gly.60` |
| 3 | ~~`brief`, `status`, `probe` and their commands~~ **done** (`paths` withdrawn) | `byj-gly.61` |
| 4 | ~~`patch` with hash guard, agent spec `:loop`/`:config`~~ **done** | `byj-gly.62` |
| 5 | an authoring loop as a tree, measured against real tasks (RFC-021) | `byj-gly.63` |

Stage 1 alone makes a workflow authorable; stage 3 makes it authorable *by an
agent working one node at a time*, which is the point.

## Invariants

| invariant | enforced by |
|---|---|
| A tool runs as a tree node, mapping `:in`/`:out`, and a failing one fails the node. | `flow-test/a-leaf-can-be-a-tool` |
| A tool leaf is gated, arg-checked and hooked exactly as a model's call is — a blocked call fails the node and writes nothing. | `flow-test/a-tool-leaf-is-gated-like-any-other-tool-call` |
| `check` reports every problem at once, including the four that are silent at run time. | `flow-test/check-reports-every-problem-at-once` |
| The loops this repository ships are sound, and their memory chain is sound. | `flow-test/the-loops-this-repository-ships-are-sound`, `flow-test/the-loops-this-repository-ships-have-a-sound-memory-chain` |
| A key nothing writes is reported as a typo; one written too late as an ordering mistake; `:fallback`, `:repeat` and `:scope` guarantee only what they can. | `flow-test/the-chain-tells-a-typo-from-an-ordering-mistake` |
| A contract is the fn's metadata plus what its node parameterizes. | `flow-test/a-contract-travels-with-the-fn-and-the-node-that-parameterizes-it` |
| **No leaf writes a key its `:writes` does not declare.** | `flow-test/a-declared-write-cannot-quietly-drift` |
| `status` distinguishes a node that is unimplemented from one that is wrong, and goes green when the tool it names exists. | `flow-test/status-says-what-is-left-to-build` |
| A brief names each read's actual suppliers and each write's actual consumers, and flags a write nothing reads. | `flow-test/a-brief-is-a-contract-taken-from-the-neighbours` |
| One node runs against a supplied blackboard and reports what it wrote; a failing one says so rather than throwing. | `flow-test/probe-runs-one-node-without-the-loop-that-reaches-it` |
| **The authoring loop closes**: status names the node, the brief specifies it, probe confirms the implementation, status goes green and the tree checks clean. | `flow-test/the-authoring-loop-closes` |
| An invented node kind is reported, not passed to an interpreter that throws on it. | `flow-test/check-reports-every-problem-at-once` |
| A patch names the tree it was written against and is refused on a stale hash. | `flow-test/a-patch-names-the-tree-it-was-written-against` |
| A patch that would make the tree invalid is refused with the problems it would have caused, and writes nothing. | `flow-test/a-patch-that-would-break-the-tree-is-refused` |
| Each op does what it says, and an op naming a node that is not there writes nothing. | `flow-test/the-ops` |
| **A patch edits the source, so an `:include` stays an include**, and the hash `check` reports is the one `patch` accepts. | `flow-test/patching-edits-the-source-not-its-expansion`, `flow-test/the-hash-check-reports-is-the-one-a-patch-guards` |
| An agent runs its own loop, its own config and its own predictors. | `flow-test/an-agent-can-bring-its-own-loop-and-predictors` |

## Stage 5 — does the surface pay for itself?

Everything an authoring loop needs now exists, and the loop itself would be a
tree like any other, so the question is not *can* it be built but *should* it.
RFC-021's lesson applies to its own successor: a measured null beats an
unmeasured feature, so this is measured (`byj-gly.63`).

**Method.** `dev/brainyard/dev/flow_authoring_eval.clj`
(`REPS=3 jolt -A:dev -m brainyard.dev.flow-authoring-eval sonnet`), jolt 0.8.8,
`claude-code` provider, 2026-09-19. The same three authoring tasks run through
the unmodified loop with `flow$*` bound and with `:flows? false`. **The prompts
never mention the commands**; the model finds them in the tool listing or it
does not.

| task | what it asks | criteria |
|---|---|---|
| `repair` | fix a loop with four planted defects, three of them silent at run time | one per defect gone from `check`'s report |
| `extend` | add a node running an existing tool, wired to the blackboard | checks clean, node present, `:in`/`:out` right |
| `build` | write a small loop from nothing | checks clean, three required elements present |

**Scoring is partial credit** — the fraction of criteria met — and deleting the
hard part to make a problem go away scores 0. A binary metric was tried first
and discarded: on the first live run one arm fixed three of four planted
defects and the other two of four, and all-or-nothing scored them both 0. A
metric that cannot separate the arms cannot decide anything.

**The verdict is a t-test** over the per-rep means, not the raw proportions,
for the reason RFC-021 recorded: with few items, run-to-run noise swamps the
effect. The same arm and task scored 2/4 and then 4/4 on two runs of `repair`,
so a difference inside the margin is reported as no difference. It is the
per-PASS form, and both arms run the same three tasks, so it could be paired
per task and should be — see `byj-gly.122`.

### The cost side, measured without a model

`flow$*` adds **2,197 prompt characters per turn** (29,143 vs 26,946 on the same
task), about 8% of a ~27k prompt. Identical across all three tasks, since it is
just the tool listing. That half of the ledger needs no live call.

### Result

Two complete reps; the third was cut short when the machine ran out of memory
at 14 of 18 runs.

| task | without | with |
|---|---|---|
| `repair` — the file exists, edit it | 0.83 | 0.92 |
| `extend` — add a node of a kind it must know | 0.25 | 0.50 |
| `build` — write one from nothing | **0.00** | 0.25 |
| rep means | 0.417, 0.417 | 0.333, 0.750 |

**The aggregate test is uninformative, and that is the first finding.**
`compare-scores` gives diff +0.125 against a margin of **2.647** — wider than
the whole 0–1 scale, because two complete reps leave df=1. The missing third rep
would not have rescued it: at df=2 the margin is still ≈0.6, five times the
observed difference. Separating a 0.125 effect at this variance needs on the
order of **68 reps**. Three tasks × three reps could never have decided this,
which is RFC-021's own lesson arriving one RFC later — *design the experiment
against the noise you already measured, not against the effect you hope for.*

What the runs do show, without needing statistics:

- **The model reaches for the commands whenever they exist** — 4 to 13 calls,
  never zero, though no prompt mentions them.
- **`build` scored 0.00 in both reps without them, and the directory was
  empty**: 12 iterations, ~1M prompt characters, `grep` after `grep`, and no
  file written. It was not authoring badly; it could not discover the format.
- **The surface is cheaper than not having it.** It adds 2,197 characters to
  each prompt and saves far more: 551k characters per task against 743k, a 26%
  reduction, because the other arm spends its budget searching.

### Decision: the authoring loop is NOT built

The measurement does not support it, and — more usefully — it points somewhere
else. Nothing here tested a loop; it tested the tools a loop would drive, and
the one reproducible gap is **discoverability of the tree format**, not the
absence of an orchestrator. A loop that drives `status` and `brief` cannot help
a model that does not know what a node looks like.

Hooks have `prompts/hook-create.md`. Tools have `tool$define` and its guidance.
**Behaviour trees had nothing** — no prompt, no skill, no example reachable from
a working directory. That gap is now closed (`byj-gly.66`), below.

### The format, written down — `byj-gly.66`

`resources/brainyard/prompts/loop-format.md` states the seven node kinds and
what each guarantees, that **every leaf names `:fn`** (there is no `[:tool …]`
node — the mistake a live model actually made), the three node-parameterized
fns with their `:in`/`:out` directions, how the blackboard and `:config/…`
resolve, and a worked tree.

It is **pulled, not pushed**. `flow$format` returns it, costing the prompt one
line until a model asks — RFC-020's rule, and the reason a 4,945-character
reference can exist at all. Two things make it findable at the moment it is
needed: the command says *read this before writing or editing a loop*, and
`check`'s `unknown node kind` problem now names `flow$format`, so **the checker
teaches exactly when the model errs**.

`flow-test/the-worked-example-in-the-format-doc-checks-clean` runs the doc's own
example through `check`. A reference that drifts from the code teaches a model
to write trees this repository rejects, which is worse than no reference because
it is believed.

**Measured before and after**, on `build` — write a loop from nothing, the task
the surface alone could not carry:

| with the surface, `build` | before the doc | after |
|---|---|---|
| score | 0.25, 0.25 | **1.00, 1.00** |
| iterations | 12, 12 | 7, 5 |
| wall clock | 365s, 186s | 36s, 27s |
| prompt characters | 839k, 625k | 247k, 173k |

Two of two, from a quarter of the criteria to all of them, in under half the
iterations and a quarter of the characters. This is the one comparison in the
RFC that a handful of runs could decide, and the reason is instructive: the
effect is **large**, where stage 5's was 0.125. An experiment can only settle a
question whose answer is bigger than its noise.

Two caveats kept for honesty. The margin here degenerates to zero because
neither arm varied at all, so "detected" means *perfectly separated on two
runs*, not a rich statistical claim; what carries weight is the size of the
effect and that its mechanism is understood. That degeneracy is the same one
that later crowned a proposal on a real compile (`byj-gly.57`).

**The code now agrees with the caveat.** Re-judging the saved run of this stage
(`target/flow-authoring-eval/results.edn`, no new model calls) shows what
changed:

| | verdict |
|---|---|
| as reported then, per rep | `{:better? true, :diff 0.75, :margin 0.0, :tested? true}` |
| now, paired over the 2 shared units | `{:better? true, :diff 0.75, :margin 0.0, :tested? false, :n 2}` |

Two units, both moving +0.75, is a two-sided sign test at p = 0.5. The
direction is unchanged and so is the conclusion — the effect is large and its
mechanism is understood, which is what this stage rested on — but it is no
longer labelled as tested. The eval now pairs over `[model rep task]` rather
than averaging each arm to a per-rep mean (`byj-gly.122`), and its summary line
says `UNTESTED` rather than "flow$* helps" when nothing weighed the direction. And the control arm (`:flows?
false`) cannot see `flow$format`, so it measures nothing about the doc — it
scored 0.25 either way, and its earlier 0.00s were noise, not a floor, which is
a correction to stage 5's write-up.

**What this says about stage 5's decision.** It stands, and is better supported:
the binding constraint was that the format was written down nowhere, and one
prompt resource lifted the task the orchestrator was supposed to rescue. A loop
driving `status` and `brief` would have been built on top of a model that could
not write a node.

**Caveats, recorded because they bound what the numbers mean.** The `extend`
task asks for a node kind documented nowhere, so it partly measures
discoverability rather than authoring, which favours the with-arm by
construction. And the run was graded by a checker that was itself wrong: it
reported an unknown tool but not an invalid node kind, so a model that wrote
`[:tool …]` was told it was one fix from correct when it was two
(`byj-gly.65`, found by this eval and fixed after it). The with-arm's `extend`
score understates what a correct checker would have given it.

## A workflow of agents is still a workflow

RFC-023 uses this layer for the thing it was not designed for and fits anyway:
brainyard's implementation chain — `plan-agent` → `todo-agent` → `exec-agent` →
`eval-agent`, four specialists handing each other dossiers — is a **tree**, because
a subagent dispatch is a tool call and `:tool/run` runs tools.

That removes two agents rather than adding a mechanism: the order becomes the
tree, the gates become `:condition` nodes, the handoff becomes a blackboard key
that `check` verifies before the run, and the judgement steps that need no tools
become predictors. Only the durable checklist is new, because a blackboard dies
with its turn.

Three leaves were added there and belong to this layer's set — `:mem/true?`,
`:mem/set` and `:mem/match?` — because a workflow over tools and agents must
branch and seed on what they wrote, and an agent's output is prose, so the last
of them branches on text. `:repeat` also hands its own opts to the `:until` predicate now,
so a loop can end on data rather than on a fn written for it.

## Open questions

- **Does a tool leaf want its own permission story?** A tool called by the model
  is gated (RFC-012); a tool called by the *tree* was authorised when the
  workflow was accepted. Treating them the same is safe and possibly noisy.
- **Should stage 5 exist at all?** RFC-021's lesson was that a measured null
  beats an unmeasured feature. An authoring loop should be built only if
  `status`-driven authoring is measurably better than a person writing the EDN.
