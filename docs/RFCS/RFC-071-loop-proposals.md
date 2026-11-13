# RFC-071 — Loop proposals: a better loop, proven and then accepted

**Status:** designed 2026-11-13; built the same day, and evaluated live once
("The first live evaluation"); an improver agent decided not now
(`byj-ng7.6`); promotion to the bundled loops waits on evidence across
projects (`byj-ng7.7`). Epic `byj-ng7`.

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

How it is built (`byj-ng7.4`, `brainyard.loop-eval`, `byj loops eval`):

- **A run** is one task, one arm, one repeat: `git clone -q --shared
  --no-checkout` of the project's repository and a detached checkout of the
  task's start commit, in a temporary directory; a turn there
  (`agent/ask` with the arm's expanded tree, injectable as `:run-turn`)
  under `clone-config` — the clone as `:project-dir`, `:working-dir` and the
  only `:allowed-dirs`; `:auto-approve` read as `:ask-each-time`, and with no
  permission channel a gate that would ask refuses; `share$*`, `claude$*`,
  `codex$*`, `session$send`, `agent-registry$*` and the effect
  `:share/publish` refused, and `git push*`, `byj-share *`, `gh *` as
  commands; then the task's checks (`sim/checks`) in the clone, and the
  clone removed. What the turn leaves is the clone's, so the project's
  sessions and refs are untouched.
- **Under the seatbelt** (`byj-ng7.14`): `byj loops eval` and `propose` run
  each turn in a byj of its own (`loop-eval/process-run-turn`, `containment`)
  — `byj ask --json -C CLONE --loop FILE -p -m --sandbox -q QUESTION` — with
  the clone's config written as the child's project config
  (`child-config`: every schema key that reads back as written, so the
  clone's containment goes with it) and the arm's expanded tree as its loop;
  what it prints is read back as `agent/ask`'s result. The seatbelt's write
  roots are then the child's: the clone, `~/.brainyard`, the temp dirs. The
  child is the byj the run is (`jolt.launcher`), else `BY_SANDBOX_SELF`,
  else `byj` on PATH — which may be another version than the evaluating
  one. Off macOS the child runs unsandboxed; with no byj to run, or with
  `--in-process`, the turn runs in the evaluating byj, contained by the gates
  alone. The report's `:containment` says which (`:seatbelt`, `:child`,
  `:in-process`), and so does its summary. Checked live once, 2026-11-13: the
  installed byj v0.3.19 as the child under the seatbelt, on the mock model —
  the candidate's write landed in its clone, the base's did not.
- **The plan** runs, for each repeat, each task with a machine check, base
  then candidate — alternating, so a drift in the model's service falls on
  both arms. A task with no machine check is counted as `:unscored` (a judge
  model, `byj-ng7.8`). Before each run the cost so far is held to
  `--max-cost`; once it is reached no run starts, the report says why, and
  the verdict is `:incomplete`.
- **The report**, `.brainyard/loops/evals/<loop>-<ms>.edn`, holds the set
  and its hash, the base (where it was found, its hash, its expansion's), the
  candidate's hashes, byj's version, the model, the repeats and the aim; every
  run; each arm's `arm-summary` (RFC-021's score: the mean, the per-task
  means as `:example-scores`, the guard metrics' means); the primary
  comparison, the guard regressions and the verdict — `:better`, `:cheaper`
  (`--aim cost`), `:not-better`, `:guard-regressed` or `:incomplete`.
  `loop-eval/wins?` is what `byj loops propose` stages on (`byj-ng7.5`).

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
  `:changes-turns`, and git's gc would take a start no ref holds. A pin is
  released only when a person retires its task (`byj loops tasks retire
  ID…`, `loop-tasks/retire!`, `byj-ng7.11`), which takes it out of the set
  and keeps its id in the set's `:retired`, so a rebuild leaves it out
  though its turn is still kept, and counts it dropped as `:retired`.
- **The machine's check** is the most trusted there is
  (`loop-tasks/machine-checks`, `byj-ng7.9`): `--check CMD`, as `[{:type
  :command :name "check" :run CMD}]`; else a dogfood plan's stage
  `:checks` (`--plan FILE [--stage ID]`, its last stage by default, `{{plan}}`
  filled in as the simulation fills it — `loop-tasks/plan-checks`); else what
  the set holds (`:check`, or `:plan` with its checks); else the project's
  `:check-command` (RFC-013, family `:loop`). Each is in the shape of a
  dogfood stage's `:checks`, which `sim/checks` runs with `sh -c` in a tree,
  so the clone run (`byj-ng7.4`) runs a task's check as RFC-041 runs a
  stage's. It is every rebuilt task's check, and the build says which it
  took (`:checks-from`: `:given`, `:plan`, `:set`, `:config`).
- **A judge's verdict** (`byj-ng7.8`) is asked only with `--judge LM`:
  the `loops/judge` predictor (`:predict`, the `:light` tier, or the
  `provider/model` given) reads the turn's question, its answer and the
  files it changed and says `pass`, `fail` or `unsure` with why, kept on the
  task as `:judge {:verdict :why :cost}` beside `:outcome`. It is a second
  signal and never one alone: it is asked only of a turn that is already a
  task, so it makes none. Its calls stop once they cost `--max-cost` (0.25
  USD); a rebuild with no judge keeps the verdicts a task had
  (`loop-tasks/judge-fn`). Nothing scores by it yet: the evaluation scores
  by the machine's checks.
- **The person's signal** is `:outcome` with `:by`:
  - `:failure :rewound` when a rewind ref of the session (RFC-069) took the
    turn back and still stands: "rewind to turn K's start" takes back K and
    every turn ended by then, "K's end" every turn after K ended by then,
    "undo turn K" K alone; a rewind undone by an undo that itself stands
    takes back nothing. Which turns came before it is the last turn its
    message names — "rewind to turn K's start, after turn N", written since
    `byj-ng7.10` — and, for a rewind made before, read from commit times, to
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
  `select-best` keeps the earlier row — and so does a lead the test could
  not weigh (`:tested?` false: one task, or fewer than six all moving the
  same way), which the first live check of the clone run, on one task,
  would otherwise have called better.
- **A candidate meant to cost less** is tested for non-inferiority instead:
  checks passed not worse past the margin, and cost lower.
- **Guard metrics** — tokens and cost, iterations, wall time, tool errors,
  refusals and asks — each have a bound. A regression past one rejects the
  candidate whatever the primary metric says. Built as a bound on the
  candidate's mean per run, `max(base × ratio, base + slack)`
  (`loop-eval/default-guards`: cost and tokens ×1.5; iterations ×1.5 or +1;
  wall time ×2 or +1 s; tool errors and refusals ×1.5 or +1 — an ask is a
  refusal here, headless).

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

How it is built (`byj-ng7.1`, `brainyard.project-loops`):

- **Found.** `agent/resolve-tree` and `load-tree` take the run's
  `:project-dir`, and every place a loop is named passes it: the session's
  tree and its includes, a turn's, a persona's turn loop, an agent spec's
  `:loop`, the SDK's, `flow check` and `flow$status` (RFC-002 says the
  order). Finding a loop only reads. `--print-tree` prints, above the tree,
  a `;;` line per loop in it saying where it was found — the project's file
  with its `:based-on`, or bundled.
- **`:based-on`** sits in the tree's root opts, so the file is a tree like
  any other — `flow check`, `--loop` and `:include` take it as it is.
  `:hash` is `flow/tree-hash` of the bundled loop: the first 12 hex of the
  SHA-256 of the tree **as read and printed** (`pr-str`), not of the
  resource's bytes, so a comment or a re-indent of the bundled file is not a
  new base; it is the hash `flow$status` shows. `:byj` is
  `version/version` (RFC-029).
- **Written** only by `project-loops/write-loop!`, which an accept and a
  revert call (`byj-ng7.2`) and nothing a tool reaches; the path gate
  refuses a tool's write to the file, its history or the directory, in every
  mode (RFC-012, `byj-ng7.12`), so a person's editor is the only other hand
  that writes one. A tree is written
  with its `:based-on` — the one given, else the one the tree carries, else
  the bundled loop's now — and nil removes the file, the bundled loop in
  force again. Each call appends one line to `history.edn`:
  `{:at :action :loop :hash :replaced :based-on :proposal}` — `:action`
  `:accept` or `:revert`, `:hash` of the tree written (nil: removed),
  `:replaced` of the file it replaced (nil: the bundled loop was in force).

**An accepted loop is in force from the next session.** Other proposals
apply from the next turn, but a session expands its tree, includes and all,
when it starts; replacing the procedure in the middle of a conversation is a
change the person did not see happen. It matches the rule that a mode or an
allow-list a run wrote does not govern that run (`config/applies-next-session?`).

How a proposal is staged, read and decided (`byj-ng7.2`,
`brainyard.loop-proposals`, kind `:loop` of `brainyard.proposals`):

- **Staged** by `stage!` — `byj loops propose` (`byj-ng7.5`) and a revert;
  no tool — in `.brainyard/loops/proposals/<loop>-<ms>/`: `meta.edn` (`{:loop
  :action :status :created-ts :base}`, `:base` the loop in force then: the
  project's file, else the bundled loop, and its hash), `loop.edn`,
  `report.edn` and `why.md`. Refused, nothing written, when the tree does not
  expand, when its expansion fails `flow check` with `byj flow check`'s
  options, or when it names a key of the permissions family
  (`loop-proposals/permission-keys`) anywhere in its opts.
- **Read** at `/proposals show loop/<id>`, and by the model with
  `proposal$read`: what it does, why, the report, and the diff of the
  expanded candidate against the expanded loop in force now
  (`loop-proposals/tree-diff`, a line diff of the two pretty-printed trees).
- **Accepted** by a person at `/proposals accept loop/<id>`: refused when the
  loop in force is not the one it was staged against — another was accepted,
  or byj upgraded the bundled one — since its evidence was about that loop;
  else the file it replaces is kept as the proposal's `replaced.edn`,
  `project-loops/write-loop!` writes the tree and the history line, and the
  session says it is in force from the next session. Rejected, it stays,
  marked so.
- **Reverted** by `byj loops revert [LOOP]`, which stages the loop before the
  last change in `history.edn` — that change's `replaced.edn`, or the bundled
  loop when it replaced none (the project's file removed) — as a proposal of
  `:action :revert`, accepted like any other. A revert is a change too, so
  reverting it puts back what it replaced.

### After adoption

The turns after an accept are compared with the turns before, from the
trajectory log. A regression yields a **revert proposal** — the previous
loop, from the history — which a person accepts like any other.

Built (`byj-ng7.16`, `brainyard.loop-watch`, `byj loops watch [LOOP]`): the
turns of every session whose first turn ended after the loop's last change in
`history.edn` — the sessions that ran it, since a session expands its loop
when it starts; one begun before runs the old loop to its end and counts on
neither side — against the newest 30 turns that ended before it, subagents'
turns aside. Each side's mean per turn is held to the evaluation's guards
(`loop-eval/guard-regressions`): cost, tokens, iterations, tool errors and
refusals as `default-guards` bound them, and `:unfinished`, the share of
turns that did not end with an answer (×1.5 or +0.1). A turn's wall time is
not in its record and is not watched. Fewer than 5 turns since, or none
before, is `:too-early`; a regression stages the loop before the change with
`stage-revert!` and a why naming each regressed metric — never an accept, and
nothing while a revert of that loop already waits. It runs when asked.

When byj is upgraded and the bundled loop no longer has the `:based-on`
hash, `byj doctor` says the project's loop was made from an older one and
offers to evaluate it again against the new bundled loop: an override that
no longer beats it is proposed for removal. (Built: doctor's `Loops:`
section, `project-loops/report-text`, names every loop the project holds,
and one whose base is not the bundled loop's hash — or that records none —
as to be evaluated again, naming the command that does it:
`byj loops propose --bundled LOOP` evaluates the bundled loop as the
candidate against the project's own and, when the project's does not beat
it past the margin — a tie included — stages a revert to the bundled loop
(`byj-ng7.5`).)

### Proposing

`byj loops propose CANDIDATE` (`byj-ng7.5`, `loop-eval/propose!`) is the
evaluation and the gate together: it runs `byj loops eval` and stages a
`loop/` proposal only when the candidate wins (`loop-eval/wins?`: `:better`,
or `:cheaper` under `--aim cost`); else the report is written and it says
why nothing was staged — the verdict, or an evaluation the spend cap
stopped. The proposal keeps the report without its runs, naming the eval's
file that has them, and `--why` or a line made from the verdict. A
person-written candidate needs nothing else, and an improver agent
(`byj-ng7.6`) would call the same command.

### The first live evaluation (2026-11-13, `byj-ng7.15`)

Run once, on this repository, with `target/byj` built from `b42c2e3` as both
the evaluating byj and each run's child, on Claude Code's haiku, under the
seatbelt.

- **The set.** This repository's ten kept turns made no task: none had a
  machine check, and none was rewound or held by a commit the person made
  (`no-check` 10, `not-kept` 2 more recorded). Nor would they rerun fairly —
  delegating to Claude Code sessions, merging, pushing, the full suite —
  as a clone refuses or pays for them. So the set was six cases a person
  wrote (`live15.cases.edn`), each a small change with a machine check,
  starting at HEAD: a file with a word in it; the count of RFC files; the
  `:jolt/min-version` from `deps.edn`; a last line added to CHANGELOG.md; an
  executable script that prints a word; the source files' SPDX identifier.
- **The candidate**: the bundled react with its iteration bound `:max-n 2`
  for `:config/max-iterations` — one known change, meant to cost less, so
  `--aim cost`.
- **A smoke run first**, one case, one run an arm: both passed, $0.0099,
  20 s; `:not-better`, since one task is too few for non-inferiority.
- **The run**: six tasks, `--repeats 2`, 24 runs, `--max-cost 1.00`. Verdict
  `:cheaper`: checks passed 0.833 in both arms (paired diff 0, every task the
  same in both), cost per run $0.0072 → $0.0057 (−20 %), tokens 107 k → 81 k,
  iterations 3.08 → 2.0, wall time 25 s → 18 s, tool errors 0.08 → 0,
  refusals 0.25 in both. $0.155 spent in all, 8 min 49 s.
- **What it found.**
  - The CHANGELOG case failed in every run of both arms: its wording,
    "a line reading exactly: checked — as the last line", was read with the
    dash, and both loops wrote `checked —`. A person's case is only as good
    as its words; that the two arms agreed is what the paired comparison is
    for — it cost the candidate nothing.
  - `--keep` kept each clone, then removed the directory holding them;
    fixed, and the report says where it kept them (`:kept`,
    `loop-eval-test/keep-keeps-the-clones-and-says-where`).
  - One refusal in every run of that task, in both arms, and nothing could
    say what was refused: the rows keep counts, and a clone keeps no session
    — `byj-ng7.17`.
  - `:cheaper` here says little of real work: on six one-step tasks a loop
    of two iterations is enough by construction. A set must resemble the
    project's turns for its verdict to mean anything about them, which this
    repository's kept turns, without checks, could not give. Nothing was
    proposed.

### Where a better loop comes from

From a person, first: a candidate is a file, tried with `--loop`, then
proposed. That alone makes the mechanism worth having.

From an **improver agent**, possibly: one that reads the trajectory log for
repeated failure — turns ending at `max-iterations`, repairs repeated,
answers given while a check fails, costly turns — and writes candidates.
Whether it earns its place is a decision measured first (`byj-ng7.6`), as
`byj-gly.63` decided for an authoring loop.

**Decided 2026-11-13: not now** (`byj-ng7.6`). The question the bead asked —
of an improver's candidates, how many are staged and how many a person
accepts, against candidates people write — cannot be asked yet, because
what an improver needs is not there; that much was measured without a model.

*Method.* `dev/brainyard/dev/improver_survey.clj` (`jolt -A:dev -m
brainyard.dev.improver-survey DIR…`) reads each project's trajectory log and
turn checkpoints, read only. It counts the live root turns (not the mock
provider's, not a subagent's) and each failure an improver would look for —
a turn not ended by an answer, ended at `max-iterations`, with a model
error, with failed or refused calls, failing in three iterations or more —
a pattern being three turns or more of one failure. Then what could prove a
candidate: the kept turns that are tasks today (`loop-tasks/turn-tasks`, no
machine check) and whether the project names a `:check-command`. It was run
over every project on one machine that had a trajectory log: 26, labelled
A–Z here.

*Result.* 773 live turns in 26 projects, 9 of them with 10 or more.

| | turns | at `max-iterations` | failing again | recurring (≥3) | tasks today / kept turns | `:check-command` |
|---|---|---|---|---|---|---|
| the 26 | 773 | 0 | 1 | failed calls in 4 (O, T, U, X), unfinished in 3 (Q, V, X) | 0 / 15 (R 10, U 5) | none |

- **Loop-level failure is rare.** No turn ended at the iteration cap; one
  failed in three or more iterations. What recurs is a failing call (a
  command that exits non-zero, a tool error) — a tool's or a skill's to fix
  (RFC-010, RFC-017), not a loop's. Of the 51 unfinished turns in Q, V and
  X, 23 are not failures at all — Q is brainyard's JVM log, whose
  `end_turn` (16) is an ordinary end, and X's `answer` (7) an older
  record's — 16 the provider's errors (`llm-error`), 2 a turn's `error`,
  and 10 the loop's own no-action guard
  (`none-channel-loop-guard`), 8 of them in Q, brainyard's own loop, 2 in
  X.
- **Nothing could prove a candidate.** No project has a single task today:
  turn checkpoints (RFC-069) are new, 15 kept turns in all, and none the
  person rewound or committed; no project names a check command. A
  candidate is staged only past the paired margin, which needs six tasks
  moving the same way (RFC-021's sign test) — so every candidate an improver
  wrote now would be evaluated on nothing and staged never. The one live
  evaluation (above) had to be run on cases a person wrote for the purpose.

*So:* no improver is built. A person's candidate, tried with `--loop` and
proposed, stays the way a better loop comes in. **Revisit** when the survey
shows a project with both a loop-level failure that recurs — at the cap,
failing again, the no-action guard — and six or more tasks with a machine
check: it prints that count last. Then measure what the bead asked, on that
project, against a person's candidates. Auto-acceptance stays out of scope.

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
byj loops tasks build [--name SET] [--from SESSIONS] [--check CMD | --plan FILE [--stage ID]] [--judge LM [--max-cost USD]] [--json]
byj loops tasks retire ID… [--name SET] [--json]                                 ; (built)   ; a task set from past turns (built)
byj loops eval CANDIDATE [--tasks SET] [--repeats N] [--max-cost USD] [--aim cost] [--in-process] [--keep] [--json]   ; (built)
byj loops propose CANDIDATE | --bundled [LOOP] [eval's flags] [--why TEXT]   ; eval, then stage when it wins (built)
byj loops watch [LOOP] [--json]                         ; the turns since a change against those before; a regression staged as a revert (built)
byj loops revert [LOOP] [--json]                        ; the previous loop, as a proposal (built)
/proposals loop  ·  /proposals show loop/<id>  ·  /proposals accept|reject loop/<id>   (built)
```

Files: `<project>/.brainyard/loops/<name>.edn` (`:based-on`),
`.brainyard/loops/history.edn`, `.brainyard/loops/proposals/<id>/`
(`loop.edn`, `report.edn`, why), `.brainyard/loops/tasks/<set>.edn` and
the person's `<set>.cases.edn` beside it (built; their shapes are under
"Tasks"), and the pins `refs/byj/tasks/<set>/<id>`.

Built: `(loop-watch/watch! config {:loop})` → `{:loop :change :before :after
:means :regressions :verdict :staged? :not-staged?}`; `watch`, `split-turns`,
`turn-metrics`, `last-change`, `guards`; `loop-proposals/pending`.

`(loop-eval/propose! config {…evaluate!'s opts :remove? :why})` →
`{:file :report :staged {:id :dir}}` | `{:file :report :not-staged}` |
`{:error}`.

`(loop-eval/evaluate! config {:lm :candidate :loop :tasks :repeats
:max-cost :aim :guards :run-turn :containment :work :check-opts :keep?})` → `{:file
:report}` | `{:error}`; `run-one!`, `make-clone!`, `clone-config`,
`run-metrics`, `process-run-turn`, `containment`, `child-config`,
`child-argv`, `arm-summary`, `verdict`, `guard-regressions`, `wins?`,
`default-guards`, `loop-of`, `summary-lines`; files
`.brainyard/loops/evals/<loop>-<ms>.edn`.

`(loop-proposals/stage! config {:loop :tree :action :report :why
:check-opts})` → `{:id :dir}` | `{:error :problems}`; `list-proposals`,
`read-proposal`, `accept!` → the history line with `:in-force
:next-session :said` | `{:error}`, `reject!`, `stage-revert!`, `problems`,
`permission-keys-in`, `in-force`, `tree-diff`; files
`.brainyard/loops/proposals/<id>/` (`meta.edn`, `loop.edn`, `report.edn`,
`why.md`, `replaced.edn`).

`(loop-tasks/build! config {:name :sessions :check :plan :judge})` → `{:file :set
:tasks :added :splits :hash :check :checks-from :judged :dropped}` | `{:error}`;
`loop-tasks/retire!`, `judge-fn`, `machine-checks`, `plan-checks`, `turn-tasks`, `person-cases`, `rewound-turns`, `committed?`,
`person-signal`, `read-set`, `set-file`, `cases-file`, `task-id`,
`check->checks`, `summary-lines`; `evaluate/split-of` (RFC-021).

## Invariants

| invariant | enforced by |
|---|---|
| A candidate is data: it is staged only when its expansion passes `flow check` — registered fns, bound tools, found predictors, a sound memory chain — and it runs no host `eval` | `loop-proposals-test/staging-refuses-what-may-not-be-a-loop` |
| A loop proposal carries no config key of the permissions family, and the gates run in `tools/call-tool` (`:agent.tool-use/pre`) whatever the tree, so no candidate weakens them | `loop-proposals-test/staging-refuses-what-may-not-be-a-loop` |
| No tool reaches a loop's accept; only a person, at `/proposals` | `loop-proposals-test/no-tool-reaches-a-loops-accept` |
| A proposal staged against a loop no longer in force is not accepted | `loop-proposals-test/a-proposal-made-against-another-loop-is-not-accepted` |
| A revert is a proposal: the file the last change replaced, or the bundled loop when it replaced none | `loop-proposals-test/a-revert-is-a-proposal-that-puts-back-the-loop-before`, `loop-proposals-test/byj-loops-revert-stages-a-proposal` |
| No tool writes a project's loop, its history or the directory, in any mode — `flow$patch` and another agent through byj's hook included; a shell line naming one is refused as a tripwire (RFC-012) | `permission-test/a-projects-loop-is-written-by-no-tool`, `tool-hook-test/another-agent-writes-no-project-loop` |
| An accepted loop is in force from the next session, and every accept and revert is a history line | `loop-proposals-test/a-person-accepts-a-loop-and-it-is-in-force-from-the-next-session`, `loop-proposals-test/the-session-says-an-accepted-loop-waits-for-the-next-session` |
| A project's loop is found before the bundled one, for a bare name and the bundled resource path alike | `agent-test/a-project-loop-is-found-before-the-bundled-one` |
| A project loop is written with its `:based-on`, a history line per write and none per read; doctor names one whose base is not the bundled loop | `agent-test/a-project-loop-is-written-with-its-base-and-a-history-line-per-change`, `agent-test/doctor-names-a-project-loop-whose-base-is-not-the-bundled-loop`, `session-test/the-doctor-report-says-each-part-in-order` (the whole report, its Loops section between the project and what is installed, naming `byj loops propose --bundled`) |
| A run can be a byj of its own: its argv puts the question after `-q`, its project config is the clone's containment, and its answer is read back past a banner; a child that prints none is the run's error | `loop-eval-test/a-run-can-be-a-byj-of-its-own` |
| The clone differs from the agent only in its loop, and leaves nothing in the project's sessions or refs | `loop-eval-test/a-worse-candidate-loses-and-an-equal-one-ties`, `loop-eval-test/a-run-is-contained-in-its-clone` |
| A worse candidate loses, an equal one ties and the loop in force is kept, a better one wins, per task and paired — and a lead too small to test is not a win | `loop-eval-test/a-worse-candidate-loses-and-an-equal-one-ties` |
| A guard regression rejects a candidate whatever its checks; `--aim cost` wins only not worse and cheaper | `loop-eval-test/a-guard-regression-rejects-whatever-the-checks-say` |
| No run starts once the spend cap is reached, and an evaluation it stopped decides nothing | `loop-eval-test/the-spend-cap-stops-the-evaluation-and-decides-nothing` |
| A candidate that does not check is not run; the report names the set, base, candidate, byj, model and repeats | `loop-eval-test/a-candidate-that-does-not-check-is-not-run`, `loop-eval-test/the-report-says-what-was-measured` |
| A candidate is staged only past the margin on the primary metric, or non-inferior and cheaper, with no guard regressed; a stopped evaluation stages nothing | `loop-eval-test/a-candidate-is-proposed-only-when-it-wins` |
| The bundled loop is proposed back when a project's own does not beat it past the margin, a tie included, and not when it does | `loop-eval-test/the-bundled-loop-is-proposed-back-when-a-projects-own-no-longer-beats-it` |
| The turns of sessions begun since a change are held against those before it; a regression stages a revert, never an accept and never a second; too few turns, or holding, stage nothing | `loop-watch-test/a-regression-since-a-change-is-offered-back-as-a-revert`, `loop-watch-test/turns-that-hold-stage-nothing` |
| A task is a kept turn's question, the parent of its checkpoint commit (pinned) and a check — the machine's, or the person's: rewound a failure, its change held by HEAD a success; a turn with neither is dropped, and every drop is counted with why | `loop-tasks-test/a-task-is-a-question-a-start-and-a-check`, `loop-tasks-test/a-commit-is-a-success-only-while-head-holds-the-change` |
| A task's split is RFC-021's content hash of its own question and start: adding tasks, a person's cases or a rebuild after its turn was pruned never moves one | `loop-tasks-test/a-split-never-moves-a-task` |
| `byj loops tasks build` writes the set and says what it holds and what it dropped; `--check` takes a command after `loops` and is a flag elsewhere | `loop-tasks-test/byj-loops-tasks-build-writes-the-set` |
| A set's machine check is a command given, else a plan's stage checks, else the set's own, else `:check-command`, and the build says which | `loop-tasks-test/a-sets-machine-check-is-the-most-trusted-one-there-is` |
| A retired task leaves the set, its pin is released, and a rebuild leaves it out | `loop-tasks-test/a-retired-task-leaves-the-set-and-its-pin` |
| A rewind names the session's last turn, and only the turns up to it are taken back, whatever the clock; one without is read from the times | `loop-tasks-test/a-rewind-names-the-last-turn-it-took-back` |
| A judge's verdict is kept beside a task's outcome, makes no turn a task, and stops at its spend cap | `loop-tasks-test/a-judge-is-a-second-signal-never-one-alone` |

## Known gaps

What is not built waits on a person — money spent on live models, or
evidence from more than one project — but the first:

- A run's refused and failed calls are counted, not named — `byj-ng7.17`.
- A loop proven in one project promoted to byj's bundled loops, on evidence
  across projects — `byj-ng7.7`.
