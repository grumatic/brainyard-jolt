# RFC-073 — Project profile: what a project is for, and how far an agent goes on its own

**Status:** proposed 2026-11-15; its three open choices decided the same
day as recommended ("Decided", `byj-71w.1`). Built: the profile as data,
its presets, and the tripwire that keeps every tool from writing it
(`byj-71w.2`); AGENTS.md's block rendered from it, written by `/init` and
held to it by `/init check` and `/doctor` (`byj-71w.3`); the permission
gate holding commits, pushes, beads filed and closed, and decision records
to it (`byj-71w.5`); `/init` drafting it from the person's words and writing
it on their yes (`byj-71w.4`); `/drive` working through the goal's beads
under it (`byj-71w.6`); measured in simulation per preset (`byj-71w.7`).
Epic `byj-71w`. Changed by the model as the person asks, on their yes,
with `profile$edit` (`byj-8b3`, "Changed as the person asks").

## Purpose

Specifies the **project profile**: the project's goal, its scope, when it is
done, and how far an agent may go without asking — whether it files issues,
works on them, commits, closes them, and decides things. A person picks a
preset at `/init`, confirms a drafted profile once, and from then on an
agent drives the project's details toward the goal, asking only where the
profile says to.

Today none of this has a place. `/init` files a starter issue asking what
the project is for (`:goal`, RFC-040), and another asking whether agents
commit their own work (`:git-profile`, `byj-aww`). What the person answers
lands wherever the agent working on the issue puts it, and how far an agent
goes is whatever bd's block in AGENTS.md says — its Conservative profile,
unless the project names another.

## Where it comes from

Three measurements, all on claude-code/haiku with the plans in
`dev/sim/extensions/` (RFC-072 "Measured in a simulation", RFC-040):

- **Agents keep what AGENTS.md says over what a person says in a session.**
  With the person stating in the session that a bead's work ends committed,
  all 10 runs cited bd's conservative default and committed 11 of 20
  stages; with the same rules written in AGENTS.md and Team-maintainer
  named, none cited it and 18 of 20 closed (`byj-93l`, `byj-aww`).
- **A sentence in every prompt does not move that.** A line telling the
  model to write a standing rule into AGENTS.md wrote none in 3 runs
  (`byj-gby`).
- **What an agent overlooks is caught mechanically, not by asking it.** The
  end-of-turn work tree check (`docs/examples/loops/work-tree.edn`) caught
  installed dependencies left untracked and the one run in five that
  slipped; refusing answers did not overturn AGENTS.md (RFC-072).

So the profile is **data** the person confirms, **shown in AGENTS.md** where
every agent reads it, and **held by the kernel** where a rule can be
checked — never only words in a prompt.

## Scope

**This layer decides** what a profile holds, its presets, where it is kept
and who may change it, how it is shown to agents, which of its axes the
kernel enforces, and the loop that drives a project's ready work under it.

**It must not know** how a bead's work is done (the ReAct loop, RFC-003),
how a tool call is gated (RFC-012, which it hands rules to), or what a
person's goal means.

**It hands** to RFC-040 a step in `/init` and a block in AGENTS.md; to
RFC-012 rules beside `:permission-mode` and a file its tripwire covers; to
RFC-022 a bundled workflow; to RFC-072 the work tree check, attached when
an agent commits on its own.

## Model

### The profile

```clojure
;; .brainyard/profile.edn — committed; written only on the person's yes
{:profile/version 1
 :preset   :collaborator
 :goal     "A small web workspace with a Terminal section first."
 :scope    {:in  ["server shell" "terminal over a WebSocket" "file tree and editor"]
            :out ["authentication" "deployment"]}
 :done     ["npm test passes" "every bead under the goal's epic is closed"]
 :goal-epic "mp-3kd"                     ; the epic /drive works under
 :autonomy {:issue/create   :act         ; overrides of the preset, if any
            :decision/local :ask}
 :stop     {:failures 3 :usd 5.0 :issues-per-run 10}}
```

Each **axis** of `:autonomy` is `:act` (do it, and say so at handoff),
`:ask` (propose it and wait for the person), or `:never`.

| Axis | What it covers |
|---|---|
| `:issue/create` | filing beads for work inside the scope, and for what the work turned up |
| `:issue/work` | choosing a ready bead and working on it without being told which |
| `:git/commit` | committing a bead's work |
| `:issue/close` | closing a bead whose work is done and checked |
| `:decision/local` | a decision that changes no scope, stack or goal, recorded in `docs/decisions/` |
| `:decision/scope` | anything that changes the goal, the scope, the stack, or the done criteria |
| `:git/push` | publishing; never above `:ask` ("Decided") |

Two axes have a **floor** no preset or override goes under:
`:decision/scope` and `:git/push` are `:ask` at most. Installing a tool and
deleting what is not the agent's own stay as RFC-040 and RFC-012 ask today.

### Presets

| Axis | `:conservative` | `:collaborator` | `:autopilot` |
|---|---|---|---|
| `:issue/create` | ask | act | act |
| `:issue/work` | ask | act | act |
| `:git/commit` | ask | act | act |
| `:issue/close` | ask | act | act |
| `:decision/local` | ask | ask | act |
| `:decision/scope` | ask | ask | ask |
| `:git/push` | ask | ask | ask |
| bd's profile named in AGENTS.md | Conservative | Team-maintainer, never pushes | Team-maintainer, never pushes |

The presets live in `resources/brainyard/profile/presets.edn`: behaviour, so
data (RFC-001). A profile names one and overrides what it wants.

### Where it is kept, and who changes it

`.brainyard/profile.edn` is committed, so every clone and every agent has
the same one. `/init`'s `.gitignore` ignores what is in `.brainyard/` but
for it and the dependencies' spec (`.brainyard/*`, `!.brainyard/profile.edn`,
`!.brainyard/deps.edn`; RFC-040, `byj-9ng`, RFC-075), and
`/init` adds it to git, with `-f` for a `.gitignore` an earlier `/init`
wrote, which ignored the directory whole.
**No tool writes it on its own**, in any mode, and neither do Claude Code
nor Codex through byj's hook: the path gate refuses a write to it, first of
all and in every mode, as for a project's loop (RFC-071, RFC-072;
`permission/profile-file`), and the command gate a line removing or moving
the directory that holds it whole, `rm -rf .brainyard` (RFC-012,
`permission/removed-guarded-dirs`, `byj-cst`). A shell line that may change
the file (`permission/named-profile-files`: a redirection into it, `sed -i`,
`cp`, a script given its name) is asked of the person **every time**
(`:every-time?`), before anything else the gate asks, in every mode but
`:deny-by-default`, where it is refused, as it is headless and from Codex,
whose hook cannot ask; Claude Code asks the person (`byj-bdd`). Every turn
ends in a checkpoint, so a change made so is a step back away. Reading it is
not refused: read-file, nor a shell line that only reads it (`cat`, `grep`,
`sed -n`, `jq`, git recording or reading it as it is), which a model reaches
for as often as read-file and was refused for. An agent that could write its profile
could raise its own autonomy. It changes only on the person's yes: through
`/init` ("Confirmed once at /init"), or through `profile$edit` when the
person asks for a change ("Changed as the person asks").

### Where agents read it

`/init` renders the profile into AGENTS.md as a marked block:

```markdown
<!-- BEGIN BRAINYARD:profile -->
## This project's profile
Goal: … In scope: … Out of scope: … Done when: …
An agent here, without asking: files beads for work in scope, works on
ready beads, commits a bead's work and closes the bead.
It asks before: a decision (docs/decisions/), anything that changes the
goal, scope, stack or done criteria, and every push.
bd's Agent Context Profiles: this project opts in to Team-maintainer, and
never pushes.
<!-- END BRAINYARD:profile -->
```

The block is what the measurements say agents keep to, and Claude Code and
Codex read it too. It names bd's profile, so `init/git-profile` reads
`:stated` and bd's conservative default no longer wins. The block is a
rendering and never the source: a block that differs from the data's
rendering — an agent may edit AGENTS.md — is reported by `/init check` and
`/doctor` (which prints the check), and `/init` rewrites it. The block goes
after the project's own section and before brainyard's block. Its words are
data, `resources/brainyard/profile/block.edn`; what each axis covers is
`presets.edn`'s. A block with no profile file is `:orphan`: the check says
so, and `/init` leaves it for the person.

### Confirmed once at /init

`init/plan` chooses `profile` when the person's words to `/init` say what
the project is for or what to build, its scope, how to tell it is done, or
how much agents should do on their own, or ask for a profile; a bare `/init`
drafts none. After the check, scaffold and beads, `agent$init` then runs a
predictor, `init/profile`: from the person's words and `init$survey` (when
the draft ran) it drafts the goal, scope, done criteria, a preset, any axis
the person set apart from it, the goal's epic and its first issues, in
order (`byj-71w.4`). A draft with no preset it knows, no epic or no
first issues is a reply that slipped, not what the person said: the tree
drafts once more (`:init/draft-whole?`), and what is still missing then
`init$profile`'s question says — the default preset standing, no epic or
issues filed — so a yes is to what will be written (`init/draft-missing`,
`byj-kti`).

`init$profile` shows the draft to the person through the turn's permission
channel, as `init$install` asks (RFC-040): the block as AGENTS.md would show
it, and the epic and issues it would file. It asks every time, in every
mode; headless, it writes nothing and shows the draft. On a yes it files the
epic and its issues with bd, each after the one before, names the epic as
`:goal-epic`, writes `profile.edn`, adds it to git with `-f`, and writes
AGENTS.md's block. On a no it writes nothing and shows the draft; to change
it, the person runs `/init` again saying what should differ. `init$profile`
and `profile$edit` ("Changed as the person asks") are the profile's only
writers, and each writes only what the person said yes to; no model turn of
`agent$init` calls a tool (its loop is the tree), and the tripwire keeps
every other tool from the file.

### Changed as the person asks

A person who wants the goal, the scope, the done criteria, the preset, an
axis or the goal's epic changed says so in the session, and the model
calls `profile$edit` with only what changes (`byj-8b3`). Before it, the
refusal told the model to have the person change the file, and editing
EDN by hand is more than most people can do: a session that asked for the
project's renaming was handed a line of EDN to change itself.

`profile$edit` reads the profile as the file holds it and sets each field
given over it: the goal, the in-scope, out-of-scope and done lists whole,
the goal's epic, the preset, and `axis level` lines over the profile's own
levels (`axis preset` drops one). A blank field is kept, so a model's
empty arguments change nothing. A preset, axis or level it does not know,
a profile that would not hold — an axis above its ceiling, `:git/push
:act` included — or a change to nothing is refused before anyone is
asked. Otherwise the person is shown each field that changes, old then
new, and, where the preset or an axis changed, each axis whose level moves
once the presets are merged, and which way: `git/commit: ask -> act (more
on its own)`. On their yes it writes the file, adds it to git as `/init`
does, and rewrites AGENTS.md's block; on a no, or with no one to ask, it
writes nothing. A project with no profile is told that `/init` drafts one.

The yes is asked every time, in every mode, and never remembered (RFC-012
`:every-time?`), as `init$profile`'s now is: an `a` given to `init$profile`
once let a later `/init` write a profile the person had not seen. That
yes is the guard: the model proposes the change the person asked for, and
the person sees exactly what would change, including any axis that would
let an agent do more on its own. AGENTS.md's block and the tool's
description say to call it only for a change the person asked for. Config
`:profile-tools?` (true) registers it; false leaves the profile to `/init`
and the person.

With a profile, the starter issues leave out asking what the project is for
(`:goal`, `:when #{:no-profile}`), and since the block names bd's profile,
`git-profile` reads `:stated` and the `:git-profile` issue and next step do
not appear.

### What the kernel holds it to

The axes that a command shows are held by the permission gate, for byj's
tools and, through byj's hook, Claude Code's and Codex's (`byj-71w.5`). What
each governs is data, `presets.edn`'s `:gates`: command globs read as
`:bash-ask-commands` are (a segment's words), and path globs read against
the path relative to the project.

| Axis | What it governs |
|---|---|
| `:git/commit` | `git commit`, `git -C … commit`, `git -c … commit` |
| `:git/push` | `git push` (and with `-C`, `-c`), `bd dolt push`, `bd sync` |
| `:issue/create` | `bd create`, `bd q`, `bd new` |
| `:issue/close` | `bd close`, `bd done` |
| `:decision/local` | a write under `docs/decisions/` |

The command gate checks them after its tripwires and before
`:bash-ask-commands`; the path gate after the loop and profile tripwires.
The profile is read at every call that might meet it, so the person's change
reaches the next call.

- `:never` refuses, in every mode, without asking.
- `:ask` asks the person, in every mode, `:auto-approve` included, and
  never the auto-mode classifier: the profile says the person decides.
  Where nobody can be asked (headless) or the mode is `:deny-by-default`, it
  refuses. Through the hook, Claude Code asks; Codex, whose hook cannot ask,
  is refused, as for any ask of byj's gates (RFC-049).
- `:act` leaves the line to the rest of the gate, but for the `:auto`
  classifier: a line made only of commands of axes at `:act` and the
  companions those axes name (`:gates` `:with` — the `git add` before a
  commit, the `tail` after it), with no redirection but `2>&1`, is not
  classified (`permission/profile-lets-line?`, `byj-yvx`). The classifier
  cannot see the profile; in round 5 it was unsure of a commit autopilot's
  profile allows and asked the person. The deny list,
  `:bash-ask-commands`, the tripwires and the paths outside the allowed
  directories still hold such a line, and a line with anything else in it
  is classified whole.

`:issue/work` and `:decision/scope` have no command to hold; they are kept
by the block and the drive loop alone, and the invariants say so. A glob
errs toward holding: `git -C x log --grep commit y` matches
`git -C * commit *`, and is asked about.

### The drive loop

`/drive` (`commands/drive.edn`) stands for `agent$drive`, whose loop is
`brainyard/loops/drive.edn` (`byj-71w.6`). It repeats a step, at most
`:drive-max-steps` (20) times, until one says to stop:

1. **`drive$next`** stops the run when the project has no profile or no
   beads, when a `:stop` limit is reached — failures in a row, the run's
   spend, beads filed since it began (`bd list --created-after`) — or when
   nothing is ready under `:goal-epic` (`bd ready --parent`), passing over
   a bead labelled `human` (step 5). Otherwise it
   takes the first ready bead that is not an epic; with `:issue/work :ask`
   the person is asked first, through the turn's permission channel, as
   `init$install` asks, and a no stops the run. It claims the bead,
   records the claim (below), and writes the brief (`resources/brainyard/drive/words.edn`). A bead the
   last step left unfinished is worked again, with what it lacked, what
   its last turn ended saying, and which of its turns this is: each turn
   starts from a fresh memory, and a retry told only what was missing
   redid what the last turn had done and ran out of iterations where it
   had (`byj-dw0`).
2. **The bead's turn** is the bundled ReAct loop, `:include`d in a `:scope`
   of its own and asked the brief, with every tool the session has but
   `agent$drive` (the agent's `:deny-tools`), under the same permission gate
   and the profile's holds. Its instruction (`prompts/agents/drive.md`)
   says to keep to AGENTS.md's profile, ask where it says to, and not start
   another bead. The turn commits and closes as the profile lets it, as
   any agent here does; `/drive` itself never commits.
3. **`drive$check`** reads what the turn left: the uncommitted files (beads',
   brainyard's and Claude Code's state aside), the commits since the step
   began and whether one names the bead, the bead's status, and what the
   profile expects of a finished bead (`words.edn` `:expect`).
4. **The predictor `drive/judge`** reads the brief, the answer and the
   check, and says `done`, `retry`, `ask` (with the question) or `stop`.
   The done criteria are prose; the turn runs the checks the project's
   instructions name, and the judge reads what it says it ran.
5. **`drive$settle`** acts on it: `done` closes the bead as `:issue/close`
   says — on its own at `:act`, after the person's yes at `:ask`, never at
   `:never` — and clears the failures; `retry` counts one and works the
   bead again next; `ask` and `stop` end the run with the question or why.
   A bead gets at most `:drive-bead-attempts` (3) turns in a run: a `retry`
   after its last sets it aside for the person instead — its claim
   released (`bd unclaim`), labelled `human`, bd's own mark for a bead
   that needs a person (`bd human list`), and a comment saying what its
   last turn got to — and the run goes on to the next ready bead. The
   label keeps later runs off it too, until the person removes it
   (`byj-dw0`: with no `:stop` in a profile, one run gave two beads four
   turns each, an hour, until the person cancelled it).
   It reports the turn's spend as a tool's (`:usage-cost-usd`), so the
   run's usage counts every bead's turn.

**`drive$report`** answers with a line per step, why the run stopped, and
what is still open under the epic.

**A claim is released when its run ends** (`byj-2pv`). `bd ready` leaves a
claimed bead out, so a bead a run claimed and did not settle was passed
over by every later run while it was still open — one cancelled mid-bead
was, twice in one session, until the person released it by hand. Each
claim is recorded in `.brainyard/drive/claims.edn` (bead -> session,
process, run) until `drive$settle` settles it. `drive$report` releases
(`bd unclaim`) what its own run still holds — a question, a stop or a
limit ended it — and a run's first step releases what an earlier run
left: one of this session, whose turns run one at a time, or one whose
process is gone. Another live session's claim is left; its run may still
be working. Each release is a line of the log. bd releases only its own
actor's claim, so a bead someone has taken since is left to them. The person steps in at the questions
the profile names and nowhere else. With a profile and beads, `/init`'s
next steps offer `/drive` first (`init/next-steps.edn` `:drive`).

## API (proposed)

| Name | What |
|---|---|
| `.brainyard/profile.edn` | the profile; committed; written only by `init$profile` and `profile$edit`, on the person's yes |
| `resources/brainyard/profile/presets.edn` | the presets |
| `profile/presets` | `presets.edn`: the axes, levels, ceilings, presets and the default preset (built) |
| `profile/read-profile` | the project's profile resolved, with `:file`; nil without one (built) |
| `profile/resolve-profile` | a profile's preset merged under it, its ceilings applied, and `:problems`; a profile that does not match the schema keeps its goal, scope and done criteria to show, its autonomy the default preset's whole, and no stop limits (built) |
| `profile/level` | the level a profile sets an axis to (built) |
| `profile/profile-schema` | the malli form a profile is checked against, closed (built) |
| `profile/render` | the AGENTS.md block for a resolved profile, marker to marker, in `block.edn`'s words; the same profile renders the same block (built) |
| `profile/block-state` | `:current`, `:stale`, `:missing` or `:orphan`: AGENTS.md's block against the data's rendering, never read for what it says (built) |
| `profile/bd-profile` | the bd profile a profile amounts to: `:team-maintainer` when `:git/commit` is `:act`, else `:conservative` (built) |
| `profile/shown-block`, `profile/block-words`, `profile/block-name` | the block AGENTS.md holds; `block.edn`; `BRAINYARD:profile` (built) |
| `init/profile-status` | a project's profile and its block's state, in `project-status` as `:profile`, a line of the check (built) |
| `presets.edn` `:gates` | what each axis governs: command globs and path globs (built) |
| `permission/project-profile` | the project's profile as the gate reads it, at every call (built) |
| `permission/profile-lets-line?`; `presets.edn` `:gates` `:with` | whether a line is one the profile lets the agent run on its own, so not classified; each axis's companions (built) |
| `permission/profile-command-rule`, `permission/profile-path-rule` | where a profile holds a command line or a write: `{:axis :level …}` for an axis not at `:act` (built) |
| predictor `init/profile` | the draft `/init` shows: goal, in, out, done, preset, autonomy, epic, issues (built) |
| `init$profile`, `init/write-profile` | the draft shown to the person and, on a yes, written with its epic and issues (built) |
| `init/draft-profile` | a draft as `profile.edn` holds it: blanks left out, unknown presets and axis levels dropped (built) |
| `profile$edit`, `init/edit-profile`, `init/profile-commands`; config `:profile-tools?` (true) | the profile changed as the person asks, shown and written on their yes, asked every time (built) |
| `init/profile-edit`, `init/profile-changes` | what the fields given make of a profile, or why not; what changes, as the person reads it (built) |
| `/drive`, `agent$drive`, `brainyard/loops/drive.edn` | the drive loop (built) |
| `drive$next`, `drive$check`, `drive$settle`, `drive$report` (`brainyard.drive`) | its mechanism, registered by `:drive-tools?`, which `agent$drive`'s config sets (built) |
| predictor `drive/judge`; `resources/brainyard/drive/words.edn` | the judgement of a bead's turn; every word the loop puts together (built) |
| config `:drive-max-steps` (20), `:bd-path` | a run's step cap; where bd is looked up (built) |
| config `:drive-bead-attempts` (3), `drive/bead-attempts` | the turns one bead gets in a run before it is set aside (built) |
| `.brainyard/drive/claims.edn`, `drive/claims-file` | the beads /drive has claimed and not settled, so one a run never settled is released (built) |
| `agents/person-words-for`, `:person-words` | the line the person typed, verbatim, in the memory of the agent its command stands for; read by `init/profile` as `typed` (built) |
| agent spec `:attended?` | an agent that asks the person as it works: its call is waited on, never detached into a task (`tasks/detachable?`); agent$init and agent$drive (built) |

## Invariants (proposed)

| Invariant | Enforced by |
|---|---|
| No tool writes `.brainyard/profile.edn`, in any mode, nor Claude Code or Codex through byj's hook | `permission-test/a-projects-profile-is-written-by-no-tool`, `tool-hook-test/another-agent-writes-no-project-profile` |
| A shell line that only reads the profile runs; one that may change it is asked of the person every time, and refused headless, in `:deny-by-default` and from Codex | `permission-test/a-shell-line-reads-the-profile-and-asks-to-change-it`, `tool-hook-test/another-agent-writes-no-project-profile` |
| The person's line reaches the agent its command stands for word for word, and only that agent | `agents-test/the-person-s-typed-line-reaches-the-agent-their-command-stands-for` |
| An attended agent's call never detaches | `tasks-test/what-never-detaches`, `agents-test/the-shipped-roster` |
| A git command that records or reads the profile as it is passes the tripwire; a write does not; the person's yes at a hold is told to the model | `permission-test/a-projects-profile-is-written-by-no-tool`, `permission-test/the-gate-follows-the-projects-profile` |
| The profile is written only on the person's yes, with the epic and issues they were shown; a no or nobody to ask writes and files nothing | `init-test/init-writes-the-profile-only-on-the-persons-yes` |
| `profile$edit` changes only what was given, shows each change and every axis that moves, writes the profile and AGENTS.md's block only on the person's yes, asked every time; a no, nobody to ask, an unknown preset or axis, an axis above its ceiling or no change writes nothing | `init-test/the-profile-changes-as-the-person-asks-only-on-their-yes`, `init-test/every-session-has-profile-edit` |
| `/init` drafts a profile when the person says what the project is for, and its answer and check say what was written | `init-test/the-init-loop-drafts-the-profile-when-the-person-says-what-the-project-is-for` |
| A draft with no preset, epic or first issues is drafted once more, and what it still lacks is said in the question before the person's yes | `init-test/a-draft-missing-its-preset-or-epic-is-drafted-again-and-said` |
| `:decision/scope` and `:git/push` are never `:act` | `profile-test/no-profile-raises-an-axis-above-its-ceiling` |
| A profile that does not match grants only the default preset | `profile-test/a-profile-that-does-not-match-grants-only-the-default` |
| Every preset sets every axis, and the presets are the three decided | `profile-test/the-presets-are-data-and-name-every-axis` |
| The AGENTS.md block is the data's rendering, or `/init check` says it is not, and `/init` writes it again | `profile-test/the-block-is-checked-against-the-data-never-read-for-it`, `init-test/init-writes-the-profiles-block-and-the-check-holds-it-to-the-data` |
| The block names every axis once under its level, and names bd's profile, so bd's default no longer stands | `profile-test/agents-md-shows-the-profile-rendered-from-the-data`, `init-test/init-writes-the-profiles-block-and-the-check-holds-it-to-the-data` |
| An axis at `:ask` asks the person in every mode, `:auto-approve` included, never the classifier; refused headless and in `:deny-by-default`; `:never` refuses unasked; `:act` holds nothing | `permission-test/the-gate-follows-the-projects-profile` |
| A line made only of `:act` axes' commands and their companions, with no redirection, is not put to the `:auto` classifier; anything else in it, and every other check of the gate, still applies | `permission-test/a-line-the-profile-lets-run-is-not-classified` |
| Through byj's hook, Claude Code asks what the profile asks about, and Codex is refused it | `tool-hook-test/another-agent-is-held-to-the-projects-profile` |
| `/drive` works only under a profile and beads, only the goal epic's ready beads, asks the person first at `:issue/work :ask`, and stops at the profile's limits | `drive-test/the-next-bead-or-why-the-run-stops` |
| `drive$check` names installed dependencies among the uncommitted, and stops naming them once `.gitignore` does | `drive-test/installed-dependencies-are-said-as-such` |
| A bead is closed by `/drive` only as `:issue/close` says, and a retry, a question or a stop is a line of the run's log | `drive-test/settling-a-bead-as-the-profile-says` |
| A retry's brief carries what the last turn ended saying and which of the bead's turns it is | `drive-test/a-retry-goes-on-from-where-the-last-turn-got-to`, `drive-test/drive-sets-aside-a-bead-it-cannot-finish-and-goes-on` |
| A bead a run claimed and did not settle is released when the run ends, or by the next run's first step when it was cancelled or its process died; another live session's is left | `drive-test/a-claim-a-run-never-settled-is-released`, `drive-test/a-run-that-ends-on-a-question-releases-its-bead`, `drive-test/drive-works-the-goals-beads-until-nothing-is-ready` |
| A bead judged unfinished after its `:drive-bead-attempts` turns is set aside — unclaimed, labelled `human`, a comment saying how far it got — and the run goes on; a bead so labelled is not driven | `drive-test/a-bead-whose-turns-are-spent-is-set-aside-for-the-person`, `drive-test/drive-sets-aside-a-bead-it-cannot-finish-and-goes-on` |
| The drive loop works each ready bead in a turn of its own until nothing is ready, and its answer says each outcome and why it stopped | `drive-test/drive-works-the-goals-beads-until-nothing-is-ready`, `drive-test/the-drive-loop-is-sound` |
| `:issue/work` and `:decision/scope` are held by words and the drive loop, not by a gate | nothing enforces it, by design |

## Measuring it

Sim plans with one goal under each preset, on claude-code/haiku, a few runs
each, the script answering only what it is asked (`byj-71w.7`). Recorded
per preset: the stage checks, how often the person was asked, the `rule-*`
tallies (`dev/brainyard/dev/sim_tally.clj`), and spend. A preset earns its
place if it reaches the same checks with fewer interventions at a cost the
table states.

### Measured (`byj-71w.7`, 2026-11-15)

`dev/sim/profile/profile-{collaborator,autopilot,conservative}.edn`: the
mini-playground (RFC-041) set up by one `/init` line that says what the
project is for, its four parts in order, what is out of scope, when it is
done, and — the only difference between the plans — how far agents go on
their own; the profile confirmed at the prompt; `/init`'s files committed as
its first next step says; then `/drive` alone. The project starts with
AGENTS.md's two rules (decisions in `docs/decisions/`, a CHANGELOG line per
noticeable change). One run per preset a round, one at a time (two at once
share port 3000), about $2 in all.

Each of the first four rounds found something that kept a run from the
goal, fixed before the next:

| Round | What stopped the runs | Fixed by |
|---|---|---|
| 1 | Every profile came out conservative, and collaborator's first issues were `/init`'s own setup steps: the session's agent put the person's line in its own words when it called agent$init, and dropped their issues, done criteria and preset sentence | the line the person typed reaches the agent its command stands for verbatim (`agents/person-words-for`, `:person-words`), and `init/profile` reads it first |
| 1 | Under `:ask`, a bead's turn wrote "should I commit?" in its answer instead of running `git commit`, which is what puts it to the person; drive/judge read that as `ask` and the run stopped | `prompts/agents/drive.md` and the brief: run the command, byj asks then; judge: a question about committing or closing is no reason to stop |
| 2 | The first issues were titles alone, so the person's specs (`{"ok":true}`, the frames) never reached the work; the plan never committed `/init`'s files, so the staged profile slipped into a bead's commit and AGENTS.md left every tree dirty; conservative's turns still asked in their answers | issues drafted as "title — what it must do", the rest the bead's description; a commit stage in the plans; the block's `:ask` words say that running the command is how the person is asked |
| 3 | The session's call to agent$drive detached into a background task after 180 s (RFC-019), its turn parked, and the commit it then asked about was refused with nobody asked | `:attended?` agents (agent$drive, agent$init) are waited on, never detached (`tasks/detachable?`) |
| 4 | The profile's tripwire refused `git add .brainyard/profile.edn`, so `/init`'s staged profile could not be committed; conservative's turn took a commit the person allowed for one made without them, and stopped to ask | git commands that record or read the file as it is pass the tripwire (`permission/named-profile-files`); the person's yes at a profile hold is told to the model as a notice |

Round 5, on the code above:

| Preset | init | commit | `/drive`: beads committed and closed | asked during `/drive` | checks failed | spend, all stages |
|---|---|---|---|---|---|---|
| collaborator | pass | pass | 4 of 4, each in its own turn | 0 | none | $0.25 |
| autopilot | pass | pass | 4 of 4, each in its own turn | 1 (a commit the classifier was unsure of) | none | $0.27 |
| conservative | pass | pass | 4 of 4 closed, 1 of 4 committed | 9 (the work picked 4, closes 3, a commit, an install) | `term`, `rule-committed` | $0.21 |

Every run asked once at `/init` (the profile) and kept AGENTS.md's rules:
four CHANGELOG lines in collaborator's and autopilot's, three in
conservative's, no decision record needed. `/drive` took 11–12 minutes.

What it says:

- **The profile is what the run keeps to.** With the preset reaching the
  profile, collaborator and autopilot drove the goal to every check with
  the person asked once (the profile), and once more for autopilot's
  classifier; conservative asked at every step it governs, as it says.
- **Collaborator and autopilot did not differ here**: no bead needed a
  local decision, so autopilot's one extra freedom was never used.
- **Conservative left the commits behind.** Its first bead's turn ran
  `git commit` and the person was asked; the next three ended done with
  their work uncommitted, which words.edn's `:expect` for `:git/commit :ask`
  then accepted, and were closed after the person's yes. The expectation
  now says a finished bead's turn runs `git commit` and byj asks the
  person, its work left uncommitted only when they said no, and drive/judge
  calls such a turn a retry with what is missing (`byj-ctw`). Confirmed in
  one more conservative run: all four beads committed and closed, each
  with the person's yes, 12 prompts in `/drive` (the work picked 4, commits
  5, closes 3), every check but `rule-committed`, which an untracked
  `node_modules/` failed — no `.gitignore` named it. `drive$check` now
  names installed dependencies among the uncommitted (`words.edn`
  `:dependency-dirs`, as `work-tree.edn` does) and drive/judge has the turn
  list them in `.gitignore` (`byj-3py`); one more conservative run passed
  every stage, `node_modules/` in `.gitignore`, four beads committed and
  closed, 13 prompts in `/drive`, $0.30.
- **The classifier still met an `:act` line.** It cannot see the profile
  and was unsure of a commit autopilot's profile lets the agent make; such
  a line is no longer classified (`byj-yvx`, below). Confirmed in one
  more autopilot run on that code: every stage passed, four beads
  committed and closed, and `/drive` asked the person nothing; the one
  commit line the classifier still saw began with a `cd`, which no axis
  names as a companion, so it was classified whole, and allowed.
- One run per preset is a first measure, not a rate; three more each
  follow.

### Three runs each (`byj-8ap`, 2026-11-15)

The same three plans on the code above (9a11b7a), three rounds of one run
per preset, one at a time; $2.16 in all.

| Preset | runs passing every stage | asked during `/drive`, per run | checks failed | spend per run |
|---|---|---|---|---|
| collaborator | 3 of 3 | 0, 0, 1 | none | $0.24–0.25 |
| autopilot | 2 of 3 | 1, 1, — | the third run's every `/drive` check | $0.26, $0.30, $0.10 |
| conservative | 2 of 3 | 13, 13, 10 | `term`, in the third run | $0.27, $0.29, $0.20 |

Every run asked once at `/init`, the profile. In the eight whose `/drive`
worked the goal, all four beads were committed and closed and AGENTS.md's
CHANGELOG rule held (three or four lines each). Conservative's prompts are
what it says: each bead's start, each commit, each close. The rest:

- **Autopilot asked once, for `npm install`**, in two runs — no axis names
  installing a dependency, so the classifier weighs it. Collaborator's one
  was a `pkill` of a server the turn had started, which the script refused.
- **Autopilot's third run wrote a profile with no preset, epic or
  issues.** haiku's JSON for `init/profile` closed `in` with `</in>` and
  wrote every later field inside that string in tags of its own
  (`<preset>autopilot</preset>`, …); the JSON parsed, the missing fields
  were taken as empty, the profile fell back to the default preset, and
  with no epic `/drive` worked `/init`'s onboarding beads instead. A reply
  that does so now has those fields lifted out (`signature/lift-tagged-fields`,
  RFC-005; `agent-test/fields-written-in-tags-inside-another-are-lifted-out`):
  the recorded reply reads as `autopilot`, its epic and four issues. A
  draft that still lacks a preset, an epic or its issues is drafted once
  more, and what it lacks then is said before the person's yes (`byj-kti`,
  "Confirmed once at /init").
- **Conservative's third run failed `term` on its own code**: the turn
  spawned `$SHELL -i` over pipes rather than node-pty, which the person's
  line named, so the check's `echo …\r` was never a line; its own tests
  sent `\n` and passed. A miss of the model's, which no check of byj's
  sees.

## Decided (`byj-71w.1`, 2026-11-15)

The three choices the draft left to the person, decided as recommended:

1. **The source is a committed `.brainyard/profile.edn`**, which AGENTS.md
   shows. A tool cannot write it and the kernel reads data, not prose; an
   AGENTS.md block alone would be one file, but one any agent can edit, and
   the measurements above say an agent keeps to what that file says.
2. **The presets are `:conservative`, `:collaborator` and `:autopilot`**, as
   the table in "Presets" sets them.
3. **No preset lets an agent push.** `:git/push` is `:ask` at most, a floor
   as `:decision/scope` is.

## Known gaps

None open.
