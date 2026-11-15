# RFC-073 — Project profile: what a project is for, and how far an agent goes on its own

**Status:** proposed 2026-11-15; its three open choices decided the same
day as recommended ("Decided", `byj-71w.1`). Nothing is built. Epic
`byj-71w`.

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
;; .brainyard/profile.edn — committed; no tool writes it
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
the same one; `/init`'s `.gitignore` line for `.brainyard/` excepts it.
**No tool writes it**, in any mode, and neither do Claude Code nor Codex
through byj's hook: the tripwire that keeps a project's loop
(RFC-071, RFC-072) covers it too. An agent that could write its profile
could raise its own autonomy. It changes only through `/init` or a
proposal the person accepts at `/proposals`.

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
`byj doctor`, and `/init` rewrites it.

### Confirmed once at /init

After the check, scaffold and beads, `agent$init` runs a predictor
`init/profile`. It drafts the goal, scope, done criteria, a preset and the
goal's epic from the person's words and `init$survey`. The draft is shown
as one proposal: the block as it would read, and the preset's table. The
person's yes writes `profile.edn`, the block, and the epic with its first
beads; an edit redrafts; a no leaves the project without a profile, as
today. A project with a profile no longer gets the `:goal` and
`:git-profile` starter issues or the `:git-profile` next step.

### What the kernel holds it to

The axes that a command shows become rules beside `:permission-mode`, for
byj's tools and, through byj's hook, Claude Code's and Codex's:

| Axis | The lines it governs |
|---|---|
| `:git/commit` | `git commit` |
| `:git/push` | `git push`, `bd dolt push` |
| `:issue/create` | `bd create` |
| `:issue/close` | `bd close` |
| `:decision/local` | a write that adds a file under the decisions directory |

`:ask` asks in every mode, `:auto-approve` included; `:never` refuses;
`:act` leaves the line to the mode in force, which may still ask
(`byj-6xh` asks whether it should). `:issue/work` and `:decision/scope`
have no command to hold; they are kept by the block and the drive loop
alone, and the invariants say so.

### The drive loop

`/drive` — a bundled workflow (RFC-022) and a command — works under the
goal's epic until something stops it:

1. Pick the next ready bead under `:goal-epic` (`bd ready`, priority
   order). With `:issue/work :ask`, propose it and wait.
2. Claim it, and work it in a ReAct turn whose question is the bead.
3. Verify: the profile's `:done` commands that apply, and the work tree
   check (RFC-072's example), attached when `:git/commit` is `:act`.
4. Commit and close as the axes say; file what the work turned up as the
   `:issue/create` axis says.
5. Go on, or stop on: a question the profile says to ask; `:stop` limits
   (failures in a row, spend, beads filed this run); nothing ready.

It ends with a handoff: what was done, committed and closed, what is open,
and the one question that stopped it, if any. The person steps in at the
questions the profile names and nowhere else.

## API (proposed)

| Name | What |
|---|---|
| `.brainyard/profile.edn` | the profile; committed; written by no tool |
| `resources/brainyard/profile/presets.edn` | the presets |
| `profile/read` | the project's profile, its preset merged and floors applied; nil without one |
| `profile/render` | the AGENTS.md block for a profile |
| `profile/gate-rules` | the permission rules a profile's axes make |
| predictor `init/profile` | the draft `/init` proposes |
| `/drive`, workflow `drive` | the drive loop |

## Invariants (proposed)

| Invariant | Enforced by |
|---|---|
| No tool writes `.brainyard/profile.edn`, in any mode, nor Claude Code or Codex through byj's hook | nothing yet (`byj-71w.2`) |
| `:decision/scope` and `:git/push` are never `:act` | nothing yet (`byj-71w.2`) |
| The AGENTS.md block is the data's rendering, or `/init check` says it is not | nothing yet (`byj-71w.3`) |
| An axis at `:ask` asks in every permission mode | nothing yet (`byj-71w.5`) |
| `:issue/work` and `:decision/scope` are held by words and the drive loop, not by a gate | nothing enforces it, by design |

## Measuring it

Sim plans with one goal under each preset, on claude-code/haiku, a few runs
each, the script answering only what it is asked (`byj-71w.7`). Recorded
per preset: the stage checks, how often the person was asked, the `rule-*`
tallies (`dev/brainyard/dev/sim_tally.clj`), and spend. A preset earns its
place if it reaches the same checks with fewer interventions at a cost the
table states.

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

- Nothing is built (`byj-71w.2`–`byj-71w.6`), nor measured (`byj-71w.7`).
