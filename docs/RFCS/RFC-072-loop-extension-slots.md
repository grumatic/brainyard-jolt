# RFC-072 — Loop extension slots: the bundled loop extended where it says, not copied

**Status:** proposed 2026-11-13; decided 2026-11-14 to build, for
composition rather than drift ("Decided"). The node kind and its expansion
are built (`byj-9sp.2`), and so are the ReAct loop's first two slots, in
`contract/` (`byj-9sp.4`), and a project's attachments (`byj-9sp.3`):
read from `.brainyard/loops/ext/` into every loop it runs, written by no
tool, evaluated by `byj loops attach`, staged and accepted at `/proposals`,
watched and reverted as a loop is. Epic `byj-9sp`.

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
.brainyard/loops/ext/attach.edn     {:react/ext-after-tools ["lint"]}
.brainyard/loops/ext/lint.edn       the tree attached; its root opts carry
                                    :based-on {:slot :contract :byj}
```

`project-loops/attachments` reads them for every expansion that resolves
loops for the project (`agent/expand-tree`): a turn's loop, the session's,
a subagent's, `flow check`'s, a proposal's diff. A name in `attach.edn`
with no file, or a file that does not read, stops the loop from loading
rather than running a loop that is not the one accepted.

An attachment changes the loop every turn runs, so it is held to exactly
what a project loop is held to (RFC-071):

- **No tool writes one.** The permission gate's loop tripwire
  (`permission/loop-file`, byj-ng7.12) covers `ext/` whole, as it covers a
  loop file. A tree an agent wrote for itself — an agent's own `loop.edn`
  under `.brainyard/agents/` — is never attached by naming it: an
  attachment is a file under `ext/`, put there by an accepted proposal.
- **A person makes it live.** `byj loops attach SLOT FILE` evaluates the
  ReAct loop in force with the tree attached against the loop in force
  (RFC-071's evaluation, unchanged), and stages it as `loop/ext-<name>-…`
  when it wins. `byj loops detach SLOT NAME` stages a detach, unevaluated, as
  a revert is. A person accepts either at `/proposals`. It is live from the
  next session, and the history line records it. The proposal's base is the
  hash of the ReAct loop in force with its attachments, so one staged before
  any other change to the loop or to what is attached is refused at accept.
- **It is watched.** An attach or a detach is a change to the react loop in
  `history.edn`, so `byj loops watch` compares the turns since with the
  turns before, as for any loop change. On a regression it stages the
  change's opposite: a detach of what was attached (with what it replaced
  put back), or the attach of what was detached. So does `byj loops revert`.
- **Two trees attached with one node id are refused**, as a loop with a
  duplicate id is: a trace could not name either.
- **It says what it was made against.** `:based-on` is the slot's contract
  when it was attached, not the loop's hash: the loop around a slot may
  change and the attachment with it is as good as it was. `doctor` names an
  attachment whose slot's contract has changed since, or whose slot the loop
  in force no longer has (`project-loops/attachment-report`). A slot that
  still has the same contract needs nothing else.

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

After the tools, `:react/fold-ext-notices` queues what the attachment left in
`:ext/notices` for the next iteration's prompt, beside the other notices.

**An answer check must sit on every path that accepts.** The answer gate
was a fallback of three ways to accept: the gate off, the model refining
itself, the judge model confirming. A check placed in the first alone would
have been passed by the third. Placed before the gate, in
`:react/answer-path`, its failure would fail the whole path and send the turn
to `:react/repair`, not to refine. So the gate was reshaped, keeping its
behaviour (RFC-003 "The answer gate"):

```
answer-gate  fallback
  self-refine     refine-self? → refine
  accept          answer-verdict (accept-answer? | evaluate-answer)
                  → answer-checked (‹ext-answer-check› | answer-check-spent?)
                  → stamp-answer
  refine-eval
```

There is one stamp now, and the check is before it, because the stamp makes
the answer final (`:agent.answer/final`). A failed check falls to
`refine-eval`. `refine-eval` gives `:ext/critique` as the reason, or says
that a check refused the answer when the check left none.

**A check cannot refuse forever.** `:react/answer-check-spent?` lets the
answer through once the turn has had `:max-answer-checks` refusals (default
2). Every refusal counts, the gate's too. It is a budget of its own, not
`:max-refinements`: at that key's default of 0, an answer check would never
refuse, and raising it would also turn on the judge model's call.

**What this change to the bundled loop carries.** A change to a bundled loop
carries evaluations and the dogfood simulation (RFC-071 "Promotion to the
bundled loops"). This one carries neither, on the argument that it changes
nothing a turn does:

- both slots are empty, and an empty slot is a node that succeeds and does
  nothing;
- the gate's reshaping runs the same leaves in the same order on each of its
  paths, and the gate's tests pass unchanged.

The suite is the evidence. Whether that argument is enough, or the dogfood
comparison is run anyway, is the maintainers' call before this is
released.

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
| `(project-loops/attachments project-dir)` | built: `{slot-id [{:name :tree} …]}` from `ext/attach.edn`, each tree read from `ext/<name>.edn` |
| `(agent/expand-tree tree project-dir)` | built: `bt/expand` with the project's loops and its attachments; `load-tree` and every other expansion go through it |
| `(project-loops/write-attachment! project-dir slot name tree opts)` | built: attach `tree` as `name` (nil: detach), with a history line `{:action :attach\|:detach :loop :slot :name :hash :replaced}` |
| `(permission/loop-file project-dir path)` | built: true for anything under `.brainyard/loops/ext/` as well |
| `(loop-proposals/stage-attachment! config {:slot :name :tree :report :why})` | built: an attach (a `:tree`) or a detach (none) staged; meta `{:kind :attach :action :attach\|:detach :slot :name :base}`; `accept!` and `stage-revert!` take it as a loop proposal |
| `(loop-eval/propose-attachment! config {:slot :candidate :name …})` | built: the loop with it attached evaluated, staged when it wins; `byj loops attach` |
| `contract/loops/slots.edn` | built: every slot the bundled loops offer, with its keys and its place |

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
| No tool writes under `.brainyard/loops/ext/`, in any mode, and a shell line naming it is refused. | `permission-test/a-projects-loop-is-written-by-no-tool` |
| What a project attached is in every loop it runs, `--print-tree` and `doctor` name it, and a detach leaves the bundled loop. | `extension-test/what-a-project-attached-is-in-every-loop-it-runs`, `extension-test/a-project-with-nothing-attached-runs-the-bundled-loop` |
| An attachment whose slot changed or went is named by `doctor`; one that is not there stops the loop from loading. | `extension-test/an-attachment-whose-slot-changed-is-named-by-doctor`, `extension-test/an-attachment-that-is-not-there-stops-the-loop-rather-than-run-another` |
| An attachment is staged only when it checks where it would sit, is live only after a person accepts it, and is refused at accept when the loop or its attachments changed since; a revert undoes an attach or a detach. | `loop-proposals-test/an-attachment-is-staged-read-and-accepted-as-a-loop-is` |
| `byj loops attach` stages an attachment only when the loop with it wins. | `loop-eval-test/an-attachment-is-evaluated-attached-and-staged-when-it-wins` |
| A regression since an attach is offered back as a detach. | `loop-watch-test/a-regression-since-an-attachment-is-offered-back-as-a-detach` |
| A slot's id, keys and place change only with `contract/` (`loops/slots.edn`). | `contract-test/the-slots-the-bundled-loops-offer-are-byjs-contract` |
| The answer check is on every path that accepts an answer, before the stamp, and stops refusing at `:max-answer-checks`. | `agent-test/the-answer-check-sits-before-every-stamp`, `agent-test/a-projects-answer-check-refuses-on-every-path-that-accepts` |
| What is attached after the tools is heard in the next iteration, and its failure costs the turn nothing. | `agent-test/an-attachment-after-the-tools-is-heard-next-iteration`, `agent-test/an-attachment-after-the-tools-that-fails-costs-the-turn-nothing` |

## Known gaps

None. Places for slots beyond the first two wait for a use ("The first
slots"), as does a slot limited to the root turn.
