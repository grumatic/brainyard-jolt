# RFC-076 — Workflows

**Status:** built (`byj-edw`). Built: keeping a project's workflows
(`byj-edw.1`); running one (`byj-edw.2`); a run in a worktree of its own,
with a role's `:session` and `:worktree` acted on (`byj-edw.3`); and a
run's record as data, `byj workflow runs --json`, byj's contract
(`byj-edw.4`). byj's own; brainyard has no counterpart.

## Purpose

A project's `.brainyard/deps.edn` names its workflows (RFC-075, `byj-dls`):
graphs of stages a piece of work is taken through, each stage playing a
role, each link saying what one stage hands the next, a master role
supervising a run. byj reads, checks and writes that file. Until now only a
client — the workbench, from its own UI — authored workflows and ran them,
and only while its page was open. `/workflow` is byj's way to do both from a
session.

## Scope

**This layer decides** how a session lists, shows, adds, changes and
removes a workflow; and how a run of one takes a piece of work through its
stages — what each stage is told, when it is handed on, and when the run
stops.

**It must not know** what a workflow is for, or what a role does: that is
the spec's, as the person wrote it.

**It hands** `byj workflow runs --json` the runs as data, a client's to
show (RFC-070); a stage's work to a ReAct turn (RFC-002), the master's
judgement to a predictor (RFC-005), a stage that works in a session of its
own to RFC-032's `teams/start-session!` and RFC-031's channel, an issue to RFC-073's tracker
layer (`drive/tools` and its neighbours), and every write to RFC-075's `deps/write!`, which holds the file to
its schema and checks, snapshots the old one, and reads the new one back. It
never writes `deps.edn` any other way. The person's yes is asked through
RFC-012's permission channel.

## Model

`agent$workflow` is an agent (RFC-018) with every tool the session has but
itself. `/workflow` stands for it (RFC-004): `/workflow` alone lists the
workflows, and `/workflow WORDS` either says what to do with one or runs one
(`/workflow run fix byj-abc`). Its `:config` turns on the `workflow$*`
commands, and its instruction is `prompts/agents/workflow.md`.

Its loop, `loops/workflow.edn`, first asks the predictor `workflow/route`
whether the request is to run a workflow — and which, on what — to go on
with a run (`resume`, below), to run an ended one again (`again`, below), or
to manage them. It decides from the request, the line the person typed, and
`workflow$list`. A request to manage them is one ReAct turn, asked what was
asked, over the commands below. One ReAct scope serves both that turn and a
run's stages, so the tree holds the ReAct loop once.

### Keeping them

- **`workflow$list`** gives each workflow's title and purpose, its stages in
  a run's order, and its master. It also gives the roles there are to play,
  and the errors `byj deps write` would refuse the spec with.
- **`workflow$show`** gives one workflow whole: each stage and the role it
  plays, with what that role does; each link and what it gives; its master;
  and the errors naming it.
- **`workflow$save`** adds a workflow, or changes one.
  - The fields given replace its own; the rest are kept.
  - `:stages` and `:links`, when given, are its whole stages and links.
  - `:roles` adds or changes roles. A role named by a stage or the master
    that the project lacks is added bare.
  - Data comes as a map, as EDN, or as JSON.
  - A link gives an issue as `{:kind :issue …}`, `:tracker :beads` for one
    in beads; the command's schema offers no other kind for one. A `:bead`
    give, deprecated (RFC-075, `byj-5q4`), in what it is handed or in the
    workflow already, is written as the issue give that says the same
    (`deps/bead-as-issue`), and the person is told so with the change.
    What a give is called, in a brief or a listing, is an issue's words.
- **`workflow$remove`** takes one out. The roles it played stay.

A change is held to what `byj deps write` holds a spec to, and is refused
before the person is asked when:

- the id is not one;
- a stage or the master plays no role, a link names no stage, or the links
  go round;
- there is no stage;
- it changes nothing;
- the file has problems a write would drop.

Otherwise the person is shown the change and asked: a new workflow whole,
or a changed one's lines gone (`- `) and new (`+ `). It is written only on
their yes. With no person to ask, nothing is written.

The order a run works the stages in is `order`: each stage after every
stage that links to it, ties broken by name.

`workflow$runs` lists the runs, newest first. `workflow$verdict` keeps the
person's word on one — `pass`, `changes` or `fail`, and a note.

### A run

1. **`workflow$start`** opens a run of the workflow on its work. The work is
   words, or an issue's id first: its id in beads, or `#N` on GitHub.
   - It stops, with why, when there is no profile, no such workflow, an
     error naming it, or no work.
   - An issue the work names is read for its title and description,
     and claimed.
   - The run is kept as `.brainyard/workflow-runs/<n>.edn` (under
     `.brainyard/`, which `/init`'s `.gitignore` ignores). It holds the
     workflow and its roles as they were when it opened, so a later edit
     does not change a running run.
   - It gives the first stage's brief, and the master: the workflow's
     `:master` role in words, or, when it names none, to judge as the person
     would.
   - Before opening, a run still marked running whose session is over is
     marked stopped and its claim released. Its session is over when it was
     this session's, or its process is gone.
2. **A stage** is a ReAct turn in a scope of its own, asked its brief — or,
   where it works in a session of its own (§"Where a stage works"),
   `workflow$delegate` asks that session the brief and waits for its answer.
   The brief, in `words.edn`, says:
   - the run and its work;
   - the profile's goal and scope;
   - the flow;
   - the stage's role and what it does;
   - what each stage linking to it handed it: the give in words and that
     stage's answer;
   - what it hands on, or that it is the last stage;
   - to do its part and only its part, under the profile, ending with what
     the next stage should do — or, at the last, the run's result.
3. **`workflow$check`** says what the turn left: the uncommitted files, the
   commits since the stage began, and what the profile expects of a commit.
   It checks each give of the links the stage hands on by from the run's own
   evidence (`deps/run-give-row`):
   - `:file`: a matching file changed since the stage began;
   - `:branch`: a commit in the stage;
   - `:note`: the stage answered;
   - `:issue`: as a handoff's, in the tracker that holds it; a `:bead`
     give, deprecated, as the issue in beads it is (RFC-075).

   The result is kept with the stage.
4. **`workflow/judge`**, a predictor playing the master role, says `done`,
   `retry`, `ask` or `stop`.
5. **`workflow$settle`** acts on it:
   - `done`: hands on to the next stage in `order`, with its brief. After
     the last stage it ends the run and closes its issue as the
     profile's `:issue/close` says: on its own at `act`, on the person's yes
     at `ask`, never at `never`.
   - `retry`: has the stage go again. The brief carries what was missing,
     what the turn ended saying, and the stage's own brief. This repeats
     until the stage has had `:workflow-stage-attempts` turns (3), when the
     run stops.
   - `ask` and `stop`: end the run with the question or why.

   The profile's `:failures` (turns in a row judged unfinished) and `:usd`
   stop it too. A run that ends unfinished releases its claim. Each step is
   a line of the run's log.
   A run started to be handed on by hand (`/workflow run … by hand`; the
   route's `by-hand`, `workflow$start`'s `:by-hand`; `byj-cm4.1`) does not
   hand on by itself: a stage judged `done` that is not the last begins the
   next one — its place and brief kept with it, still `:pending` — and the
   run waits, `:paused`, its `:stopped` saying so. The run keeps its claim,
   and the next run's start leaves it as it is.

   A run stopped from outside its session (`byj workflow stop N`, below;
   `byj-cm4.4`) is marked so in its record; its session reads the record at
   `workflow$settle` and `workflow$delegate`, and ends the run there as it
   is, writing nothing over it.
6. **`workflow$report`** is the answer: each line, why the run ended, and
   the commits since it began, with how to give a verdict. A run the step
   limit (`:workflow-max-steps`, 30) ended with a stage still to go is
   marked stopped. For a managing turn, the answer is that turn's own.

A stage's turn, and a managing turn, start from a fresh memory. What it
spent comes back out and is said as a tool's spend — by `workflow$settle`
for a stage, by `workflow$report` for a managing turn — so the turn's usage
counts every one, and the profile's `:usd` is held to it. A stage worked
in a session of its own spends there: `session$ask` counts what that
session's reply says it spent as this session's, and `workflow$delegate`
adds it to the run's `:usd` itself, saying no spend of its own, so it is
counted once and the limit still holds.

Stages that do not depend on each other are still worked one after another,
in `order`.

### Going on with a run (`byj-cm4.1`)

`/workflow resume N [what to add]` (the route's `resume`, `of` and `note`)
goes on with run N from the stage it is at, through **`workflow$resume`**:
a run waiting to be handed on, or one stopped with a stage left — an ask
answered, a limit, its session over, stopped from outside. In this session,
the stage is begun again (where it works, the HEAD there, its brief), its
turns counted afresh, the person's words put before its brief, the run's
issue claimed again, and a line logged; then the run goes on as any does.
It is refused while another live session runs it (`byj workflow stop`
first), and for a run that is done (`/workflow again`). A waiting run's
report says how to hand it on.

### Running one again (`byj-cm4.2`)

`/workflow again N [what to change]` (the route's `again`, `of` and `note`)
runs an ended run — done or stopped — again through **`workflow$again`**: a
new run of the same workflow on the same work, opened as `workflow$start`
opens one and kept as `:again N`, the earlier run's verdict as
`:again-verdict` and what to change as `:again-note` (the person's words
now, else the verdict's note). It works where the earlier run did: in its
worktree and on its branch while that is there, else in a new worktree when
it worked apart (from what it built on), else here. Every stage's brief
opens with the run it is again of, its verdict and what to change. Its issue
is claimed again unless it was closed; a run handed on by hand is again. A
run still running or waiting is refused (`/workflow resume`).

### Where a stage works

A stage works in a place.

- **This turn, in the project** (`{:key :here}`). This is the default.
- **A headless session of its own** (`{:key k :dir d}`), started for it by
  `teams/start-session!`. It is a background task of this session's, so it
  ends with it. It is labelled `workflow-<n>/<key>`, runs on this turn's
  provider and model, and its permission prompts go to this session's
  person. `:ask-timeout-ms` is raised to `:workflow-stage-timeout-ms` (an
  hour) so its turn may be the stage's whole work.

The place follows the roles' `:session` (RFC-075):

- **`:upstream`**, the default: where the stage handing to it worked. That
  is the done stage linking to it, the last in `order`. A stage nothing
  hands to uses the run's own place.
- **`:own`**: a session of its own, keyed by the stage. With `:worktree` it
  is in a new worktree on `workflow/<workflow>-<n>-<stage>`, branched from
  where the stage handing to it worked, and made when its turn begins.
  Without `:worktree` it works in that stage's directory.

A run asked to work apart (`/workflow run … in a worktree`; the route's
`isolate`, `workflow$start`'s `:worktree`) has its own place in a worktree,
on `workflow/<workflow>-<n>` (key `:root`). There every stage works in a
session, since a turn here cannot change its directory.

That worktree starts from HEAD, or from a branch or commit the run is to
build on (`byj-cm4.3`): `/workflow run fix … from BRANCH`, the route's
`from`, `workflow$start`'s `:from`. Naming one asks for a worktree of its
own. One that is no commit stops the run before it opens. The record keeps
it as `:from` and its commit as `:base`, and each stage's brief says what the
run builds on and how to read it from the main checkout
(`git log HEAD..BRANCH`).

`workflow$check` reads the stage's own work tree. A session that is still
live is asked again, as a retry or an `:upstream` stage. Each stage's
session is bound to its role in `deps-local.edn`, and on a stage's first
turn the handoff to it is kept under `:handed`, so `byj deps` and the
workbench see the run's sessions. `workflow$report` closes the sessions the
run started. It says the worktrees, which are kept for the person to merge
or remove, and lists the commits in each.

## API

`brainyard.workflow`:

- `words []`: `resources/brainyard/workflow/words.edn`.
- `order [workflow]`: the stage ids in a run's order.
- `describe [words spec id workflow]`: a workflow as lines.
- `give-words [words give]`, `gives-words [words gives]`,
  `role-words [spec role]`.
- `list-workflows [config args]` → `{:text}`.
- `show-workflow [config args]` → `{:text :found?}`.
- `save-workflow [config ctx args]` → `{:written? :text}`. args: `:id`,
  `:title`, `:does`, `:master` (`none` drops it), `:stages`, `:links`,
  `:roles`.
- `remove-workflow [config ctx args]` → `{:written? :text}`.
- `runs-dir [dir]`, `run-file [dir n]`, `read-run [dir n]`, `runs [dir]`
  (newest first).
- `stage-attempts [config]`.
- `list-runs [config args]` → `{:text}`; `record-verdict [config args]` →
  `{:text}`; `stop-run [config args]` → `{:ok :text}` (`byj-cm4.4`): args
  `:run`, `:why`.
- `start-run [config ctx args]` → `{:stop :stopped :run :brief :master}`;
  args also `:from` (`byj-cm4.3`) and `:by-hand` (`byj-cm4.1`).
- `resume-run [config ctx args]` → as `start-run` (`byj-cm4.1`): args
  `:run`, `:note`.
- `again-run [config ctx args]` → as `start-run` (`byj-cm4.2`): args
  `:run`, `:note`.
- `check-stage [config args]` → `{:text}`.
- `settle-stage [config ctx args]` → `{:stop :stopped :brief :stage
  :usage-cost-usd?}`.
- `report-run [config ctx args]` → `{:text :usage-cost-usd?}`.
- `delegate-stage [config ctx args]` → `{:answer :usage}`;
  `stage-timeout-ms [config]`.
- `commands [config]`: `workflow$list`, `workflow$show`, `workflow$save`,
  `workflow$remove`, `workflow$runs`, `workflow$verdict`, `workflow$start`,
  `workflow$resume`, `workflow$again`, `workflow$check`, `workflow$settle`, `workflow$report`,
  `workflow$delegate`.

`brainyard.deps/run-give-row [dir give evidence opts]`: a link's give as a
run sees it. `brainyard.drive`'s `tools`, `show-item`, `claim!`, `unclaim!`,
`close!` and `reachable?` are public, for a run's bead or issue.

A run's record:

```clojure
{:n 3 :workflow :fix :status :running        ; :running | :paused | :done | :stopped
 :by-hand true                               ; only when it waits after each stage (byj-cm4.1)
 :again 2 :again-verdict :changes :again-note "…"  ; only for a run run again (byj-cm4.2)
 :work "byj-abc keep it small" :work-text "…" :item "byj-abc" :claimed? true
 :session "<session id>" :pid 123 :started-at "…" :ended-at "…"
 :base "<HEAD when it opened, or :from's commit>" :branch "main"
 :from "unmerged"                            ; only when it builds on one (byj-cm4.3)
 :def {…the workflow…} :roles {…its roles…}
 :root {:key :here}                          ; or {:key :root :dir "<worktree>" :branch "workflow/fix-3"}
 :sessions {:root {:session-id "…" :task-id "…" :label "workflow-3/root" :dir "…"}}
 :at 0 :failures 0 :usd 0.42
 :stages [{:stage :implement :role :implement :status :done   ; :pending | :working | :done
           :attempts 1 :head "<HEAD when it began>" :brief "…" :answer "…" :summary "…"
           :place {:key :here}               ; where it works
           :question "…" :gives [{:to :review :ready true :gives [{:kind :note :ready true :detail "…"}]}]}]
 :log ["✓ implement — …"] :stopped "Every stage is done."
 :verdict :pass :note "…" :verdict-at "…"}
```

`runs-report [dir & [n]]` → `{:runs [run …]}`: each record as data, a
keyword as its name, as `byj deps --json` gives its files.

The CLI:

```
byj workflow runs [N] [-C DIR] [--json]               the runs, newest first, or run N
byj workflow verdict N pass|changes|fail [NOTE…]      the person's word on run N, kept with it
byj workflow stop N [WHY…]                            run N, running or paused, stopped from outside its session
```

`stop` marks the run stopped with why (WHY, else "Stopped by the person."),
logs it, and releases its issue's claim; the session running it ends the run
at its next step. `--json` answers `{ok, run}` or `{error}`; a run not there
or not running exits 1.

`--json`'s runs are the records above, their keys as the file names them.
A run that is not there exits 1, and so does a verdict refused. The shape is
byj's contract, `contract/workflow/runs.json` (RFC-070). The workflows
themselves are `byj deps`'s (RFC-075).

Config:

- `:workflow-tools?` (default false) registers the commands;
  `agent$workflow`'s `:config` turns it on.
- `:workflow-max-steps` (30) caps the stage turns in a run.
- `:workflow-stage-attempts` (3) caps the turns one stage gets.
- `:workflow-stage-timeout-ms` (3600000) is how long a stage in a session of
  its own is waited for.

## Invariants

| Invariant | Enforced by |
|---|---|
| A run works stages each after what links to it, ties by name | `workflow-test/stages-are-worked-in-link-order` |
| The workflows, roles and errors are listed, and one is shown whole | `workflow-test/workflows-are-listed-and-shown` |
| A workflow is added on the person's yes, with the roles it names, and the others are kept | `workflow-test/a-workflow-is-added-on-the-persons-yes` |
| A workflow hands on an issue, never a bead: a `:bead` give is shown and written as the issue in beads it is, and said; the tools offer no `:bead` | `workflow-test/a-workflow-hands-on-an-issue-never-a-bead` |
| A change writes only what is given and only on the person's yes; what `byj deps write` would refuse is refused before they are asked; removing one keeps its roles | `workflow-test/a-workflow-is-changed-and-removed-on-the-persons-yes` |
| `agent$workflow` has every tool but itself, its commands turned on, and is waited on | `agents-test/the-shipped-roster` |
| Its loop is sound, with the ReAct loop in it once | `workflow-test/the-workflow-loop-is-sound` |
| A run opens only under a profile, on a workflow that can be written and on some work; its first brief says the work, the profile's goal and scope, the flow, the stage's role and what it hands on; a run left by a session that is over is stopped at the next start | `workflow-test/a-run-opens-or-says-why-not` |
| A stage's gives are checked from what it left — files changed, commits, its answer — and kept with it | `workflow-test/the-check-says-what-a-stage-hands-on` |
| Each stage is a turn of its own, handed what the last one said; a bead the work names is claimed, and closed when the run is done as the profile allows | `workflow-test/a-run-works-each-stage-and-hands-on` |
| A stage judged unfinished goes again with what it got to, until its turns are spent; then the run stops and releases its claim | `workflow-test/a-stage-goes-again-until-its-turns-are-spent` |
| A stage that needs the person ends the run with the question | `workflow-test/a-stage-that-needs-the-person-ends-the-run-with-the-question` |
| An ended run is run again: the same workflow and work, where it worked (its worktree while there), each stage told the verdict and what to change — the person's words now over the verdict's note; its issue claimed again unless closed; a run not ended is refused | `workflow-test/an-ended-run-is-run-again-where-it-worked` |
| A run handed on by hand waits after each stage judged done, the next brief kept, its claim held, left as it is by the next run's start; resumed, it goes on from that stage with what the person adds; a run that is done, or that another live session runs, is not resumed; one stopped with a stage left is | `workflow-test/a-run-handed-on-by-hand-waits-and-is-resumed` |
| Any other request is one turn, asked what was asked | `workflow-test/any-other-request-is-a-turn-over-the-workflow-commands` |
| The profile's `:usd` and `:failures` end a run, a stage's spend counted | `workflow-test/the-profiles-limits-end-a-run` |
| A stage works where its role's `:session` says — this turn, or a session of its own in a worktree — and a run asked to work apart works in its own worktree; the session is bound to its role | `workflow-test/a-stage-works-where-its-role-says-and-is-bound-to-it` |
| A run built on a branch works in a worktree starting there, its record keeping the branch and its commit, its briefs saying so; one that is no commit is refused before the run opens | `workflow-test/a-run-in-a-worktree-builds-on-a-branch` |
| A stage in a session of its own is asked its brief there, the same session again while it is live; the report closes it and says the worktree | `workflow-test/a-stage-in-a-session-of-its-own-is-asked-there` |
| What a stage's own session spent is the run's, toward the profile's `:usd`, and counted once | `workflow-test/a-stage-worked-elsewhere-spends-the-runs-budget` |
| `byj workflow runs --json` gives the runs as data, one or all, and `byj workflow verdict` keeps the person's word; a run not there, or a verdict refused, exits 1 | `main-test/byj-workflow-gives-the-runs-as-data-and-keeps-a-verdict` |
| `byj workflow stop` stops a running run from outside its session, its claim released; its session ends the run at its next step, writing nothing over the record | `workflow-test/a-run-stopped-from-outside-ends-at-its-next-step`, `main-test/byj-workflow-gives-the-runs-as-data-and-keeps-a-verdict` |
| The runs' shape is byj's contract | `contract-test/a-workflows-runs-are-byjs-contract` |

## Known gaps

None.
