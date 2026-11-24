# RFC-076 — Workflows

**Status:** building (`byj-edw`). Built: keeping a project's workflows
(`byj-edw.1`) and running one in the session's work tree (`byj-edw.2`).
Still to come: a run's isolation (`byj-edw.3`) and its record as a contract
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

**It hands** a stage's work to a ReAct turn (RFC-002), the master's
judgement to a predictor (RFC-005), a bead or issue to RFC-073's tracker
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
whether the request is to run a workflow — and which, on what — or to manage
them. It decides from the request, the line the person typed, and
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
   words, or a bead id or GitHub issue (`#N`) first.
   - It stops, with why, when there is no profile, no such workflow, an
     error naming it, or no work.
   - A bead or issue the work names is read for its title and description,
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
2. **A stage** is a ReAct turn in a scope of its own, asked its brief. The
   brief, in `words.edn`, says:
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
   - `:bead` and `:issue`: as a handoff's, in the tracker that holds it.

   The result is kept with the stage.
4. **`workflow/judge`**, a predictor playing the master role, says `done`,
   `retry`, `ask` or `stop`.
5. **`workflow$settle`** acts on it:
   - `done`: hands on to the next stage in `order`, with its brief. After
     the last stage it ends the run and closes its bead or issue as the
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
6. **`workflow$report`** is the answer: each line, why the run ended, and
   the commits since it began, with how to give a verdict. A run the step
   limit (`:workflow-max-steps`, 30) ended with a stage still to go is
   marked stopped. For a managing turn, the answer is that turn's own.

A stage's turn, and a managing turn, start from a fresh memory. What it
spent comes back out and is said as a tool's spend — by `workflow$settle`
for a stage, by `workflow$report` for a managing turn — so the turn's usage
counts every one, and the profile's `:usd` is held to it.

Stages that do not depend on each other are still worked one after another,
in `order`.

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
  `{:text}`.
- `start-run [config ctx args]` → `{:stop :stopped :run :brief :master}`.
- `check-stage [config args]` → `{:text}`.
- `settle-stage [config ctx args]` → `{:stop :stopped :brief :stage
  :usage-cost-usd?}`.
- `report-run [config args]` → `{:text :usage-cost-usd?}`.
- `commands [config]`: `workflow$list`, `workflow$show`, `workflow$save`,
  `workflow$remove`, `workflow$runs`, `workflow$verdict`, `workflow$start`,
  `workflow$check`, `workflow$settle`, `workflow$report`.

`brainyard.deps/run-give-row [dir give evidence opts]`: a link's give as a
run sees it. `brainyard.drive`'s `tools`, `show-item`, `claim!`, `unclaim!`,
`close!` and `reachable?` are public, for a run's bead or issue.

A run's record:

```clojure
{:n 3 :workflow :fix :status :running        ; :running | :done | :stopped
 :work "byj-abc keep it small" :work-text "…" :item "byj-abc" :claimed? true
 :session "<session id>" :pid 123 :started-at "…" :ended-at "…"
 :base "<HEAD when it opened>" :branch "main"
 :def {…the workflow…} :roles {…its roles…}
 :at 0 :failures 0 :usd 0.42
 :stages [{:stage :implement :role :implement :status :done   ; :pending | :working | :done
           :attempts 1 :head "<HEAD when it began>" :brief "…" :answer "…" :summary "…"
           :question "…" :gives [{:to :review :ready true :gives [{:kind :note :ready true :detail "…"}]}]}]
 :log ["✓ implement — …"] :stopped "Every stage is done."
 :verdict :pass :note "…" :verdict-at "…"}
```

Config:

- `:workflow-tools?` (default false) registers the commands;
  `agent$workflow`'s `:config` turns it on.
- `:workflow-max-steps` (30) caps the stage turns in a run.
- `:workflow-stage-attempts` (3) caps the turns one stage gets.

## Invariants

| Invariant | Enforced by |
|---|---|
| A run works stages each after what links to it, ties by name | `workflow-test/stages-are-worked-in-link-order` |
| The workflows, roles and errors are listed, and one is shown whole | `workflow-test/workflows-are-listed-and-shown` |
| A workflow is added on the person's yes, with the roles it names, and the others are kept | `workflow-test/a-workflow-is-added-on-the-persons-yes` |
| A change writes only what is given and only on the person's yes; what `byj deps write` would refuse is refused before they are asked; removing one keeps its roles | `workflow-test/a-workflow-is-changed-and-removed-on-the-persons-yes` |
| `agent$workflow` has every tool but itself, its commands turned on, and is waited on | `agents-test/the-shipped-roster` |
| Its loop is sound, with the ReAct loop in it once | `workflow-test/the-workflow-loop-is-sound` |
| A run opens only under a profile, on a workflow that can be written and on some work; its first brief says the work, the profile's goal and scope, the flow, the stage's role and what it hands on; a run left by a session that is over is stopped at the next start | `workflow-test/a-run-opens-or-says-why-not` |
| A stage's gives are checked from what it left — files changed, commits, its answer — and kept with it | `workflow-test/the-check-says-what-a-stage-hands-on` |
| Each stage is a turn of its own, handed what the last one said; a bead the work names is claimed, and closed when the run is done as the profile allows | `workflow-test/a-run-works-each-stage-and-hands-on` |
| A stage judged unfinished goes again with what it got to, until its turns are spent; then the run stops and releases its claim | `workflow-test/a-stage-goes-again-until-its-turns-are-spent` |
| A stage that needs the person ends the run with the question | `workflow-test/a-stage-that-needs-the-person-ends-the-run-with-the-question` |
| Any other request is one turn, asked what was asked | `workflow-test/any-other-request-is-a-turn-over-the-workflow-commands` |
| The profile's `:usd` and `:failures` end a run, a stage's spend counted | `workflow-test/the-profiles-limits-end-a-run` |

## Known gaps

- A run works in the session's own work tree, one turn after another; a
  role's `:session :own` and `:worktree` are not acted on, and a run's
  sessions are not bound to their roles in `deps-local.edn` (`byj-edw.3`).
- A run's record is not yet a contract a client reads (`byj-edw.4`).
