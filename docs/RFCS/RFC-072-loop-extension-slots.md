# RFC-072 — Loop extension slots: the bundled loop extended where it says, not copied

**Status:** proposed 2026-11-13; decided 2026-11-14 to build, for
composition rather than drift ("Decided"). The node kind and its expansion
are built (`byj-9sp.2`). Attachments' files and proposals (`byj-9sp.3`) and
the ReAct loop's first slots (`byj-9sp.4`) are not. Epic `byj-9sp`.

## Purpose

Specifies how a project adds behaviour to a bundled loop — the ReAct loop
first — at points the loop names, without taking a copy of it.

Today a project changes its loop one way: it replaces it whole, with
`.brainyard/loops/react.edn` accepted at `/proposals` (RFC-071). That works,
and it costs the project the bundled loop's future. A copy is the bundled
loop as it was on the day it was made: when byj is upgraded and the bundled
loop gets better, the project keeps running the old one, and `byj doctor`
can only say so and ask for an evaluation again (RFC-071 "When byj is
upgraded"). A project that wanted one more step after its tools run now
owns all forty-three nodes of the loop around it.

An **extension slot** is a node in the bundled loop that says *here a
project may add something, and this is what it may see and change*. A
project attaches trees of its own to a slot; everything else stays the
bundled loop, and follows it through every upgrade.

## Scope

**This layer decides** what a slot is, how the trees attached to it become
part of the loop that runs, where an attachment lives and how it becomes
live, and which slots the bundled loops offer.

**It must not know** what an attachment does. It holds an attachment to the
slot's keys and to what a tree may already contain; it does not judge it.
Whether an attachment is worth having is RFC-071's evaluation, unchanged.

**It hands** to RFC-002 a node kind that `bt/expand` removes before a tree
runs, as it removes `:include`; to RFC-022 a node `flow check` checks like
any other; to RFC-071 a new kind of change to stage, evaluate, accept and
watch; to RFC-012 a directory its loop tripwire covers; to `contract/` the
slots the bundled loops offer.

## Model

### A slot

```clojure
[:extension {:id         :react/ext-after-tools
             :reads      {:last-tool-results :last-tool-results   ; inner ← outer, as :scope :in
                          :question :question}
             :writes     {:ext/notices :ext/notices}               ; outer ← inner, as :scope :out
             :on-failure :ignore}]                                 ; :ignore | :fail
```

A slot is the place and the contract both. `:reads` is what an attachment
sees of the loop's memory, `:writes` the only keys it can change there, and
`:on-failure` whether its failure is the loop's: `:ignore` for a slot that
observes or adds (a lint after a write), `:fail` for one that decides (an
answer check). A slot names no tree: what is attached to it is the
project's.

### Expanded at load time, never dispatched at run time

`bt/expand` replaces a slot before the tree runs, as it replaces `:include`:

- **Nothing attached:** a node that succeeds and does nothing, keeping the
  slot's `:id`. The loop behaves as it did without the slot.
- **Trees attached:** `[:scope {:id <slot id> :in <reads> :out <writes>}
  [:sequence {} <each attached tree, expanded>]]`, in the order the project
  declared them.
- **`:on-failure :ignore`:** that scope inside a `:fallback {:id <slot id>}`
  whose second child succeeds, so the turn goes on. The failing leaf is
  recorded as any is, by the `:bt.node/error` event; its `:bt/error` stays
  in the scope's memory, which the loop does not read.

The node that replaces a slot keeps the slot's contract under `:slot` and the
attachments' names under `:attached`, so the expansion says what it was made
of. An attachment's own `:include`s are expanded. A slot inside an
attachment is refused: an attachment is not extended in turn.

Ticking an unexpanded `:extension` throws, as an unexpanded `:include`
does.

Expanding rather than dispatching is the point of the design: everything
that already reads a loop reads the extended one with no change.

- `flow check` checks an attachment's memory chain where it sits: inside the
  scope it has the slot's `:reads` and nothing else, so a read of any other
  key is reported as it is in any loop. A write outside `:writes` needs no
  check: it lands in the scope's memory and never reaches the loop's.
- A turn record's `:loop :hash` (RFC-025) is the hash of the expansion, so
  it says which attachments a turn ran with.
- `byj loops eval` evaluates "the bundled loop with this attachment" as a
  candidate like any other (RFC-071).
- `--print-tree` and `tree-sources` name each attachment and where it was
  found.

A dispatch at run time would hide the attachment from all four.

### What an attachment may contain

An attachment is a tree, and contains what a project loop may contain
(RFC-071 "Scope"):

- registered leaf fns;
- predictors (RFC-005);
- `:tool/run` leaves over Tool Lisp tools (RFC-022);
- the `:mem/*` leaves.

A new compiled leaf stays a code change. So an extension adds behaviour as
data, and `src/` keeps only the mechanism: `expand`, the gate and the
proposal kind (RFC-001).

### Writes go to keys of the slot's own

`:scope :out` copies an inner key over the outer one. A slot that let an
attachment write a key the loop also keeps — `:pending-notices`, a list
other leaves append to — would let it replace that list rather than add to
it. So a slot's `:writes` names keys under `:ext/`, and the bundled node
after the slot folds them into the loop's own state: the slot is where the
attachment speaks, the fold is the bundled loop deciding what that means.
`expand` refuses a slot whose `:writes` leave `:ext/`.

### Where an attachment lives, and how it becomes live

```
.brainyard/loops/ext/attach.edn     {:based-on {:loop "react" :hash … :byj …}
                                     :attach {:react/ext-after-tools ["lint"]}}
.brainyard/loops/ext/lint.edn       the tree attached
```

An attachment changes the loop every turn runs, so it is held to exactly
what a project loop is held to (RFC-071):

- **No tool writes one.** The permission gate's loop tripwire
  (`permission/loop-file`, byj-ng7.12) covers `ext/` whole, as it covers a
  loop file. A tree an agent wrote for itself — an agent's own `loop.edn`
  under `.brainyard/agents/` — is never attached by naming it: an
  attachment is a file under `ext/`, put there by an accepted proposal.
- **A person makes it live.** Attaching or detaching is staged at
  `/proposals` with its evaluation, and accepted by a person. It is live
  from the next session, and the history line records it.
- **It is watched.** `byj loops watch` compares the turns since with the
  turns before, as for any loop change, and stages a revert on a
  regression.
- **It says what it was made against.** `:based-on` is the bundled loop's
  hash. After an upgrade, `doctor` names an attachment made against an
  older loop, as it does a project loop. A slot that still has the same
  contract needs nothing else.

A project that has its own `react.edn` and attachments too gets both: its
own loop, with its attachments expanded into whatever slots it kept.

A subagent that brings no loop of its own runs the project's ReAct loop
(`agents/child-opts`), and so runs its attachments, as it runs a project's
`react.edn`. A slot limited to the root turn waits for a use that needs it.

### The first slots

The bundled loops offer slots one at a time, each with a use behind it,
because every slot is a promise: its id, its keys and its place are in
`contract/`, held by `contract-test`, and a slot cannot move without
breaking the projects attached to it. Two are proposed for the ReAct loop
(RFC-003):

| slot | place | reads | kind |
|---|---|---|---|
| `:react/ext-after-tools` | after `:react/tool-dispatch` in the tool path | `:last-tool-results`, `:tool-calls`, `:question` | `:ignore`: a lint or a check after a write, told to the model next iteration |
| `:react/ext-answer-check` | on every path that accepts an answer | `:answer`, `:question`, `:iterations` | `:fail`: a project's own check of an answer; a failure sends it to refine with the slot's critique |

**An answer check must sit on every path that accepts.** The answer gate
is a fallback of three ways to accept (`:react/accept`,
`:react/self-refine`, `:react/confirm`) and a refine. A check placed in the
first alone would be passed by the third, which accepts on the judge
model's word. Placing it before the gate, in `:react/answer-path`, fails the
whole path and sends the turn to `:react/repair`, not to refine. Where it
goes is part of what `byj-9sp.4` builds and tests.

Places that are not proposed yet:

- before the model is asked (`:react/iteration`, before `:react/llm-guard`)
  costs a call per iteration for anything that asks a model;
- the start and end of a turn are what `:hooks/fire`'s `agent.ask/pre` and
  `agent.ask/post` already offer to a command.

Each waits for a use.

### What a slot is not

- **A hook.** `:hooks/fire` (RFC-011) runs a command the person configured,
  outside the tree: `flow check`, the loop hash and `loops eval` do not see
  it. A slot is tree, inside the loop, seen by all three. The two can stand
  at the same place, as `ext-after-tools` and `agent.iteration/post` nearly
  do.
- **An include.** `:include` names the tree it splices. A slot names none,
  has a default of nothing, and a contract. An include is the loop's author
  composing a loop. A slot is the loop's author leaving room for a project.

## Deciding (`byj-9sp.1`)

There are three ways to extend a bundled loop, and they trade where a
change may go against what survives an upgrade:

| way | where a change may go | after byj is upgraded |
|---|---|---|
| the whole-loop override (built, RFC-071) | anywhere | the project runs its old copy; `doctor` asks for an evaluation again |
| a patch overlay: RFC-022's ops stored against the bundled loop's hash, applied at load | anywhere a node id anchors | applies while its anchor ids still exist and the hash matches; otherwise it is refused, and the project runs the bundled loop without it |
| extension slots (this RFC) | the slots alone | follows the bundled loop while each slot keeps its contract |

Slots are worth building if the bundled loops change often enough for a
copy to fall behind, and in places other than where the slots would be. That
is measured, with no model, before anything is built:

- **Churn:** over the bundled loops' history — this repository's, and the
  history before the split it was published without — count the changes to
  `react.edn`. Each is a change a whole-loop override would have needed an
  evaluation for.
- **Breakage:** count the changes that moved, renamed or removed a node id.
  Each is a change that would have refused a patch overlay anchored there.
- **Slot reach:** count the changes that touched a proposed slot's place or
  its keys. Each is a change that would have broken an attachment.
- **Cost:** compare the expand time and the expanded hash of the bundled
  loop with empty slots against today's loop. The hash differs, since the
  expansion holds a node per slot; the turn record's `:loop :hash` says so
  once, at the version that adds them.

The decision closes with these numbers here and the spike that counts them
under `dev/`, as every decision does.

### Measured (2026-11-13)

`jolt -A:dev -m brainyard.dev.extension-slots-survey REPO…`
(`dev/brainyard/dev/extension_slots_survey.clj`) reads two histories:

- this repository's history;
- the history before the split, which a private repository keeps. Its
  release tags run from v0.1.0 (2026-09-25) to v0.3.17.

Versions with the same text are counted once.

| series | versions | steps | semantic | anchors broken | `ext-after-tools` site | `ext-answer-check` site |
|---|---|---|---|---|---|---|
| every commit, 2026-09-17 → today | 11 | 10 | 10 | 1 | 0 | 1 |
| every release, v0.1.0 → v0.3.19 (22 tags) | 2 | 1 | 1 | 0 | 0 | 0 |

- **The ReAct loop settled before byj was first released.** Nine of the ten
  semantic changes came in its first nine days (2026-09-17 → 09-25): the
  port, predictors, hooks, the budget, the answer gate, the trajectory
  record and compaction. Each of the nine added nodes. Only one moved
  one: `:react/stamp-answer`, moved into the new answer gate.
- **Across 22 releases a whole-loop override fell behind once.** That was
  v0.3.4 (2026-10-15), and the change was one option:
  `:react/recover-native-tool-calls` gained `:lookups`. A project holding a
  copy then would have been asked once to evaluate again, and would have
  missed one fix to native-call recovery.
- **A patch overlay would have survived every release.** No node id has
  been removed or moved since v0.1.0. Of the 22 ids in the first version,
  21 are where they were.
- **Neither slot's site changed across releases.** The answer check's site
  changed once in the commit history, when the answer gate was added.
  That is the change that would have needed a gate-type slot carried onto
  the new paths.
- **Two empty slots cost nothing measurable.** Expanding the session loop
  took 0.30 ms either way (200 runs). The ReAct loop's hash changes once
  (`a671dcefbfc9` → `40d15158cbd4`), and its node count goes from 43 to 45.

**What the numbers say.** The drift that slots exist to remove has been one
option change in two months of releases. By that measure the whole-loop
override costs a project almost nothing today, and a patch overlay would
have cost less. Slots would pay when:

- the bundled loop starts changing between releases. A promotion to the
  bundled loops (RFC-071 "Promotion") is the first thing that would do
  that;
- or projects want extensions that compose: two independent additions,
  each evaluated and reverted alone. A copy cannot hold that.

Neither has happened yet. No project on the machine the survey ran on
holds a loop of its own. The only files under any `.brainyard/loops/`
there are evaluation reports.

### Decided (2026-11-14): build, for composition

Slots are built, and not for drift, which the numbers show is small. They are
built for the second reason: a project's additions that compose, each one a
file of its own, attached, evaluated, watched and reverted alone, beside the
bundled loop rather than inside a copy of it. Experimenting with a project
loop one addition at a time is the use. A whole-loop override stays, for a
change no slot reaches.

A patch overlay was not chosen. It would have survived every release so far,
but it anchors on any node id, which makes every id a promise. A slot is a
promise made on purpose, at one place, with its keys, and `contract/` holds
it.

## API

A row not marked built is proposed.

| name | what it is |
|---|---|
| `[:extension {:id :reads :writes :on-failure}]` | a slot, in a bundled loop's EDN |
| `(bt/expand tree load-tree [attachments])` | built: `:include` and `:extension` both expanded; `attachments` a fn (or map) from a slot id to `[{:name :tree} …]`, in order; none, every slot empty |
| `(project-loops/attachments project-dir)` | `{slot-id [tree…]}` from `ext/attach.edn`, each tree read from `ext/` |
| `(permission/loop-file project-dir path)` | true for anything under `.brainyard/loops/ext/` as well |
| a proposal `{:kind :attach :slot :tree}` / `{:kind :detach :slot :name}` | staged, evaluated and accepted as a loop proposal |
| `contract/loops/slots.edn` | every slot the bundled loops offer, with its keys |

## Invariants

The rows naming a bead are proposed and enforced by nothing yet.

| invariant | enforced by |
|---|---|
| A loop with no attachments behaves exactly as without its slots. | `extension-test/a-slot-with-nothing-attached-is-a-node-that-does-nothing` |
| An attachment reads only its slot's `:reads` and changes no key of the loop outside its `:writes`; a slot's `:writes` are under `:ext/`. | `extension-test/an-attachment-sees-the-slots-reads-and-changes-only-its-writes`, `extension-test/flow-check-reads-an-attachment-where-it-sits`, `extension-test/a-malformed-slot-is-refused-at-expansion` |
| Attachments run in the order declared. | `extension-test/attachments-run-in-the-order-declared` |
| An attachment with `:on-failure :ignore` never fails the turn. One with `:fail` fails what holds its slot. | `extension-test/a-slot-that-ignores-failure-never-fails-the-loop`, `extension-test/a-slot-whose-failure-counts-fails-what-holds-it` |
| The expansion names what was attached, and its hash — a turn's `:loop :hash` — differs with each attachment set. | `extension-test/the-expansion-and-its-hash-say-what-was-attached` |
| An unexpanded slot does not tick; an attachment is not extended in turn. | `extension-test/an-unexpanded-slot-does-not-run`, `extension-test/an-attachment-is-expanded-but-not-extended` |
| No tool writes under `.brainyard/loops/ext/`. An attachment is live only after a person accepts it. | nothing yet (`byj-9sp.3`) |
| A slot's id, keys and place change only with `contract/`. | nothing yet (`byj-9sp.4`) |
| The answer check is on every path that accepts an answer. | nothing yet (`byj-9sp.4`) |

## Known gaps

- Attachments' files, gate, proposals, evaluation and watch, and
  `--print-tree` naming each attachment: `byj-9sp.3`.
- The ReAct loop's first two slots and their contract: `byj-9sp.4`.
