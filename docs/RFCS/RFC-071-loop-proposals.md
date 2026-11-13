# RFC-071 — Loop proposals: a better loop, proven and then accepted

**Status:** designed 2026-11-13, not built. Epic `byj-ng7`.

## Purpose

Specifies how the loop an agent runs gets better without a rebuild and
without the agent choosing its own future: a candidate loop is run by a
**clone of the agent** — the same agent with only its loop replaced — on
tasks rebuilt from the project's own past work, beside the loop in force;
when the comparison says it is better by more than chance, it is staged as a
proposal with its evidence; a person accepts it, and it is the project's loop
from the next session.

byj already improves itself in three ways, each a proposal a person accepts
at `/proposals`: a skill distilled or refined (RFC-017,
`brainyard.self-improve`), predictor params compiled (RFC-021), a user tool
distilled (RFC-010). Each changes what one step knows. None changes the
procedure — which steps run, in what order, when a turn repairs, verifies or
stops. That is the loop (RFC-002), and this RFC makes it the fourth kind.

## Scope

**This layer decides** what a candidate loop may contain, what it is
measured on and how, what evidence a proposal carries, and how an accepted
loop is found, applied and taken back.

**It must not decide** what a good loop is: the checks a task carries and the
metrics the caller names say that. Nor does it decide whether the WORK a
turn did is correct — that is the project's tests.

**It must not add a way to run host code.** A candidate is data: a tree
whose leaves are registered fns, Tool Lisp (RFC-010) or predictors
(RFC-005), as RFC-022 requires of an authored loop. A step that needs a new
compiled fn is a code change, a bead and a pull request, never a proposal.

**It must not let the proposer accept.** As RFC-021 keeps
`accept-proposal!` out of every tool's reach, no tool reaches a loop's
accept: a loop is the procedure of every later turn, so an agent that could
accept one would be choosing its own future, more widely than a params file
does.

**It hands** a project loop to `agent/resolve-tree` (RFC-002), a proposal
kind to `brainyard.proposals`, a task set and a report to whoever asks.

## What a loop proposal may change

| change | example | rebuild | a proposal |
|---|---|---|---|
| the tree's shape | a verify step before the answer; a repair retried; a router branch | no | **yes** |
| a node's opts | `max-iterations`, `:zones`, a compaction threshold, a model tier | no | **yes** |
| a step written as data | a `:tool` leaf with a Tool Lisp body; a predictor leaf | no | **yes** |
| what a step is told | instructions, demos | no | already RFC-021's |
| a new compiled leaf | a new kind of action in `src/` | yes | no — a bead |

## Model

```
 vary            check             run as a clone         judge              propose          accept           watch
 a candidate ─→ flow check     ─→ current and candidate ─→ paired, past  ─→ /proposals  ─→ a person   ─→ the turns after
 (a person, or  registered fns     on the same tasks,       the margin;      loop/<id>       from the      against the turns
  an improver   no permissions     N times, isolated        guards hold      evidence and    next session  before; a revert
  agent)        keys                                                         the tree's diff                 is a proposal too
```

### The clone

The clone is the agent with **only `:loop` replaced**: the project's config,
its model, its tools, its permission policy. Anything else that differs
between the two arms is a second variable, and the comparison would no longer
say what the loop did.

Each task runs headless, so a gate that would ask a person refuses — in both
arms alike, which keeps the comparison fair and keeps the run unattended. It
runs isolated:

- in a **clone of the repository** of its own (`git clone --shared` at the
  task's start commit). Not a worktree: a worktree shares the repository's
  refs, and a turn writes its checkpoint as `refs/byj/turns/…` (RFC-069);
- with a **project-dir of its own**, so its sessions and trajectories stay
  out of the project's log, from which the next task set is built;
- under the **seatbelt** (RFC-012) with that clone as its write root, and
  with the tools that reach outside — `git push`, a share — refused;
- under a **spend cap** per evaluation. Live models cost money (AGENTS.md);
  an evaluation runs when asked, never on a schedule.

### Tasks: the project's own work, rebuilt

A task is a question, the work tree it was asked in, and a check.

- **The question** is the trajectory record's `:question` (RFC-025).
- **The work tree** is the parent of the turn's checkpoint commit (RFC-069).
  A repository keeps only its newest `:changes-turns`, so a task set is built
  while they exist and saved by commit id.
- **The check**, in order of trust: what a machine repeats — the project's
  test or build command, a dogfood stage's check (RFC-041); then what the
  person did — a turn rewound is a failure, a turn whose change was committed
  a success; a judge model only as a second signal, never alone.

Cases a person writes stand beside the rebuilt ones. Splits are RFC-021's
content hash, so adding a task never moves another and a held-out set stays
held out.

**Built** (`byj-ng7.3`, `brainyard.loop-tasks`): `byj loops tasks build`
writes `<project>/.brainyard/loops/tasks/<set>.edn` (`default` unless
`--name`). Not in brainyard, which measures predictors on datasets and
never a loop on a project's own work.

- **Which turns:** the kept turns of `--from`'s sessions, or of every
  session with a trajectory or a kept turn. A subagent's record (it has
  `:owner`) is not a turn of the session's.
- **A turn and its record:** a record carries no turn number (RFC-025), so a
  turn's record is the one written between its start and end snapshots (a
  second's slack either side), whose question's first line is the ref's
  subject.
- **The start** is the turn ref's parent, by commit id, and is **pinned** as
  `refs/byj/tasks/<set>/<id>`: the repository prunes turn refs past
  `:changes-turns`, and git's gc would take a start no ref holds. Pins are
  not pruned.
- **The machine's check** is `--check CMD`, as `[{:type :command :name
  "check" :run CMD}]`: the shape of a dogfood stage's `:checks`, which
  `sim/checks` runs with `sh -c` in a tree, so the clone run (`byj-ng7.4`)
  runs a task's check as RFC-041 runs a stage's. The set remembers it
  (`:check`), and a rebuild without `--check` uses the set's. It is every
  rebuilt task's check.
- **The person's signal** is `:outcome` with `:by`:
  - `:failure :rewound` when a rewind ref of the session (RFC-069) took the
    turn back and still stands: "rewind to turn K's start" takes back K and
    every turn ended by then, "K's end" every turn after K ended by then,
    "undo turn K" K alone; a rewind undone by an undo that itself stands
    takes back nothing. Which turns had ended is read from commit times, to
    the second.
  - `:success :committed` when HEAD holds the turn's change: git's three-way
    merge of the change onto HEAD (`merge-tree --merge-base=<start> HEAD
    <end>`) leaves HEAD's tree as it is. More of the person's beside it
    does not count against it; the same lines changed again on HEAD since
    do.
  - Neither for a turn that changed no files: there was nothing to take
    back or commit. Both at once is no signal.
- **Kept or dropped:** a turn with a check or a signal is a task; with
  neither it is dropped. Every drop is counted with its reason — `no-check`,
  `conflicting` (rewound and committed, no check), `not-kept` (a record
  whose checkpoint is gone), `no-record` (a kept turn with no record),
  `no-question`, `truncated` (the record cut the question short),
  `duplicate` (the same question in the same tree), `start-unknown` (a
  case's start is no commit), `no-session`.
- **A person's cases** are `<set>.cases.edn` beside the set: a vector of
  `{:question :start? :checks? :check?}`, `:start` any revision (HEAD when
  none) saved as its commit id, `:check` a command; a case without a check
  of its own takes the set's.
- **The split and the id** are of the task's own content, its question and
  start: `evaluate/split-of`, RFC-021's content hash as `split` buckets it,
  and the first 12 hex characters of a SHA-256 of the same two. A rebuild
  keeps every task the set held, its turn pruned or not, and a task rebuilt
  replaces its old self, so no task ever changes split.

```clojure
{:v 1 :set "default" :built-at 1794546437896 :check "make test" :from ["<sid>"]
 :hash "5ab13852a194"                        ; evaluate/dataset-hash of the tasks
 :splits {:train 3 :val 1 :test 1}
 :dropped {:counts {:no-check 1 :not-kept 1}
           :turns [{:session "<sid>" :turn 3 :reason :no-check :question "…"} …]}
 :tasks
 [{:id "3f70ddddabd6" :split :train :question "…" :start "<sha>"
   :checks [{:type :command :name "check" :run "make test"}]
   :outcome :success :by :committed          ; the person's, when there is one
   :source :turn :from {:session "<sid>" :turn 1 :ref "refs/byj/turns/<sid>/1" :end "<sha>"}}
  {:id "…" :split :test :question "…" :start "<sha>" :checks […] :source :person :case 0}]}
```

A judge model as a second signal is not built.

### Judgement

Both arms run the same tasks `N` times. Then:

- **The primary metric** is checks passed, compared **per task** with
  RFC-021's `compare-scores` — paired, because the two arms ran the same
  tasks, which removes the difference between tasks from the noise. The
  candidate wins only past the margin; a tie keeps the loop in force, as
  `select-best` keeps the earlier row.
- **A candidate meant to cost less** is tested for non-inferiority instead:
  checks passed not worse past the margin, and cost lower.
- **Guard metrics** — tokens and cost, iterations, wall time, tool errors,
  refusals and asks — each have a bound. A regression past one rejects the
  candidate whatever the primary metric says.

The report records the task set's hash, the base loop's hash, byj's version,
the model and the repeats, so that anyone can run it again and get the same
question answered.

### Adoption

Accepting writes `<project>/.brainyard/loops/<name>.edn` with
`:based-on {:loop :hash :byj}` and a line in
`.brainyard/loops/history.edn`. The file is the project's like the rest of
`.brainyard/`, so a team reviews a loop change in its own pull request.

**`resolve-tree` looks there first** — for a bare name and for the bundled
resource path alike, since `session.edn` includes the turn's loop as
`brainyard/loops/react.edn` — and falls back to the bundled loop.

**An accepted loop is in force from the next session.** Other proposals
apply from the next turn, but a session expands its tree, includes and all,
when it starts; replacing the procedure in the middle of a conversation is a
change the person did not see happen. It matches the rule that a mode or an
allow-list a run wrote does not govern that run (`config/applies-next-session?`).

### After adoption

The turns after an accept are compared with the turns before, from the
trajectory log. A regression yields a **revert proposal** — the previous
loop, from the history — which a person accepts like any other.

When byj is upgraded and the bundled loop no longer has the `:based-on`
hash, `byj doctor` says the project's loop was made from an older one and
offers to evaluate it again against the new bundled loop: an override that
no longer beats it is proposed for removal.

### Where a better loop comes from

From a person, first: a candidate is a file, tried with `--loop`, then
proposed. That alone makes the mechanism worth having.

From an **improver agent**, possibly: one that reads the trajectory log for
repeated failure — turns ending at `max-iterations`, repairs repeated,
answers given while a check fails, costly turns — and writes candidates.
Whether it earns its place is a decision measured first (`byj-ng7.6`), as
`byj-gly.63` decided for an authoring loop.

### What samizdat does, and why this differs

samizdat changes the loop while it runs: a supervisor proposes an edit,
the loop is compiled, a dry run with effectful steps stubbed decides, and
the edit is committed or rolled back, with a held-out gate in front. Here
the evaluation is offline, on real tasks with their effects contained by a
clone rather than stubbed; a person decides; and a running session never
changes procedure. It is slower, and it keeps byj's rules: no host `eval` of
what the agent writes, no proposer holding the key, nothing a run wrote
governing that run.

## API (planned)

```
byj loops tasks build [--name SET] [--from SESSIONS] [--check CMD] [--json]   ; a task set from past turns (built)
byj loops eval CANDIDATE --tasks SET [--repeats N] [--max-cost USD]
byj loops propose CANDIDATE --tasks SET                 ; eval, then stage when it wins
byj loops revert                                        ; the previous loop, as a proposal
/proposals loop  ·  /proposals show loop/<id>  ·  /proposals accept|reject loop/<id>
```

Files: `<project>/.brainyard/loops/<name>.edn` (`:based-on`),
`.brainyard/loops/history.edn`, `.brainyard/loops/proposals/<id>/`
(`loop.edn`, `report.edn`, why), `.brainyard/loops/tasks/<set>.edn` and
the person's `<set>.cases.edn` beside it (built; their shapes are under
"Tasks"), and the pins `refs/byj/tasks/<set>/<id>`.

Built: `(loop-tasks/build! config {:name :sessions :check})` → `{:file :set
:tasks :added :splits :hash :check :dropped}` | `{:error}`;
`loop-tasks/turn-tasks`, `person-cases`, `rewound-turns`, `committed?`,
`person-signal`, `read-set`, `set-file`, `cases-file`, `task-id`,
`check->checks`, `summary-lines`; `evaluate/split-of` (RFC-021).

## Invariants

| invariant | enforced by |
|---|---|
| A candidate is data: it passes `flow check`, names only registered fns, and runs no host `eval` | nothing yet — `byj-ng7.2` |
| A loop proposal carries no config key of the permissions family, and the gates run in `tools/call-tool` (`:agent.tool-use/pre`) whatever the tree, so no candidate weakens them | nothing yet — `byj-ng7.2` |
| No tool reaches a loop's accept; only a person, at `/proposals` or the CLI | nothing yet — `byj-ng7.2` |
| An accepted loop is in force from the next session, and every accept and revert is a history line | nothing yet — `byj-ng7.2` |
| A project's loop is found before the bundled one, for a bare name and the bundled resource path alike | nothing yet — `byj-ng7.1` |
| The clone differs from the agent only in its loop, and leaves nothing in the project's sessions or refs | nothing yet — `byj-ng7.4` |
| A candidate is staged only past the margin on the primary metric, or non-inferior and cheaper, with no guard regressed | nothing yet — `byj-ng7.5` |
| A task is a kept turn's question, the parent of its checkpoint commit (pinned) and a check — the machine's, or the person's: rewound a failure, its change held by HEAD a success; a turn with neither is dropped, and every drop is counted with why | `loop-tasks-test/a-task-is-a-question-a-start-and-a-check`, `loop-tasks-test/a-commit-is-a-success-only-while-head-holds-the-change` |
| A task's split is RFC-021's content hash of its own question and start: adding tasks, a person's cases or a rebuild after its turn was pruned never moves one | `loop-tasks-test/a-split-never-moves-a-task` |
| `byj loops tasks build` writes the set and says what it holds and what it dropped; `--check` takes a command after `loops` and is a flag elsewhere | `loop-tasks-test/byj-loops-tasks-build-writes-the-set` |

## Known gaps

All of it: this RFC is a design. The order of work is the epic's:

- A project's own loop, with what it was based on — `byj-ng7.1`.
- `loop/` proposals — `byj-ng7.2`.
- A judge model as a second signal on a rebuilt task, never alone — bead to file.
- A project's own check command, and a dogfood plan's stage checks
  (RFC-041), taken as the set's machine check without `--check` — bead to
  file.
- Which turns a rewind took back is read from commit times, to the second:
  a turn ending in the second of the rewind counts as before it — bead to
  file.
- The clone run and the comparison — `byj-ng7.4`.
- `byj loops propose` — `byj-ng7.5`.
- Whether an improver agent earns its place — `byj-ng7.6` (a decision).
- A loop proven in one project promoted to byj's bundled loops, on evidence
  across projects — `byj-ng7.7`.
