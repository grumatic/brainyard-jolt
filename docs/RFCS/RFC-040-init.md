# RFC-040 — /init: preparing a project for brainyard

**Status:** built. Designed 2026-10-08. Epic `byj-gly.309`. Built: the
check (`.2`), the writes (`.3`), beads (`.4`), the agent (`.5`), the entry points (`.6`), AGENTS.md in
the prompt (`.1`), and checked live (`.7`). Onboarding, epic `byj-gly.337`:
what to do next (`.1`), starter issues (`.2`), example prompts from the
survey (`.3`), `byj init`'s next steps (`.4`), and checked live (`.5`).

## Purpose

Decides how a directory becomes a project brainyard works well in, and how a
person learns what their machine lacks for it. This means:

- checking the tools brainyard relies on, and saying how to install any that
  are missing;
- writing the instruction file every agent reads (`AGENTS.md`, with a
  `CLAUDE.md` that imports it);
- running beads' own setup;
- loading the project's instructions into brainyard's own prompt.

Decided with the user on 2026-10-08:

- **The name is `/init`**, not `/setup`. This differs from brainyard's `/init`
  (below), so it gets a divergence entry.
- **AGENTS.md is loaded into the prompt**, as brainyard loads it.
- **Built-in skills shipped in the binary are not part of this.** The beads
  skill is bd's to write (`byj-gly.309.8`, decided against).

## Where it comes from

brainyard has the parts but no command that sets up a project:

- **Its `/init` is something else.** It authors `.brainyard/BRAINYARD.md`
  (`common/init_agent.clj`, `common/init.clj`;
  `docs/design/init-agent-design.md`). It seeds that file once from CLAUDE.md
  and AGENTS.md, which it treats as read-only, and each write is a
  transaction: a snapshot, a diff, then apply.
- **`by config`'s bootstrap wizard**
  (`bases/agent-tui/.../bootstrap.clj`;
  `docs/design/bootstrapping-design.md`) detects, then falls back through
  model choices, then hands off. Two of its rules are kept here:
  - "Detection is a pure read" (§2.3).
  - "No surprise installs: name the binary, its source and its size before
    installing" (§2.6).
- **`components/env-detect` `executables.clj`** gives each executable a name,
  a path, a version (read with its own flag) and whether it is available.
- **brainyard loads CLAUDE.md and AGENTS.md into every turn.**
  `:reference-artifact-paths` (default `["CLAUDE.md" "AGENTS.md"]`) feeds
  `reference-artifact-descriptors` in `core/config.clj`. The files go into
  the prompt's Live Artifacts:
  - pinned and read fresh each turn;
  - deduplicated by file identity, so a symlinked pair is loaded once;
  - truncated at `:live-artifact-max-chars` (4000).
  The port budgets them as instructions instead, 32 KiB across them
  (byj-gly.312, below).

The port has none of these today. It does not read AGENTS.md or CLAUDE.md,
it has no `/init` or `/doctor`, and nothing checks for bd, codex, gh or claude.

This repository is the worked example of what `/init` produces. `bd init`
wrote most of it (commit `96dc2ef`): `.beads/`, the git hooks through
`core.hooksPath`, the managed blocks in AGENTS.md, `.agents/skills/beads`,
`.claude/settings.json` (`bd prime` at session start), `.codex/hooks.json`
with `config.toml`, and a `.gitignore` block. By hand there are an AGENTS.md
whose top part is project policy, a CLAUDE.md that is `@AGENTS.md`, and
`.brainyard/` listed in `.gitignore`.

## Scope

**This layer decides** what a project needs for brainyard and in what order
it is set up: which tools are required and which optional, what each is for,
and how each is installed. It also decides what an AGENTS.md and a CLAUDE.md
start as, and how they are changed without losing what a person wrote. It
decides how the project's instructions reach the prompt.

**It must not know:**

- what bd writes. bd owns `.beads/`, its hooks, its managed blocks, its skill
  and its `.claude` and `.codex` hook files. `/init` calls bd and never copies
  bd's files.
- how to install anything by itself. It names the command and does not run
  it without the person.
- anything about one particular project. A project's own policy is the
  person's text, or a model's draft that the person keeps.

**It hands** a report to the person: what was created, what was already
there, and a table of the tools. It hands a section of the prompt to every
turn, the project's instructions (`byj-gly.309.1`).

## Model

Three layers, following RFC-001:

| Layer | What | Where |
|---|---|---|
| Mechanism | `init$check`, `init$scaffold`, `init$beads`, `init$install`: reading the machine and the project, writing a file from a template, running bd, returning an install command | `src/brainyard/init.clj` |
| Behaviour | the order, what to do from the person's words and what was found, the AGENTS.md draft | `agents/init.edn`, `loops/init.edn`, predictors `init/plan` and `init/agents-md` |
| Data | the tools and how to install them; the starting files | `init/requirements.edn`; `init/templates/` with an `index.edn`, since a built binary cannot list a resource directory (RFC-010's reason for `tools/index.edn`) |

### Checking (`init$check`, `byj-gly.309.2`)

The check is read-only. It runs nothing that changes anything, needs no
network and needs no model.

**Tools** are read from `requirements.edn`. Each entry has the command, how
its version is read (`tmux -V` and `dolt version`, since they do not take
`--version`), whether it is required, what it is for, and an install hint for
each platform with brew first:

| Tool | For | Required |
|---|---|---|
| git | worktrees, everything | yes |
| bd | beads, the task tracker | yes |
| tmux | Claude Code sessions, agent$claude (RFC-033, RFC-039) | for those |
| claude | agent$claude, the claude-code provider | for those |
| gh | pull requests | for publishing |
| codex | Codex sessions and bd's codex setup | no |

An entry may say how to ask a tool whether it is signed in (`:signed-in`,
an argv that exits 0 when it is). The share sidecar is not among them: it is
another program's, which says itself how it is installed and signed in
(`byj-ahf.7`); `/share` says the sidecar's login.

**The project** is checked for:

- whether it is a git repository;
- whether AGENTS.md exists;
- whether CLAUDE.md exists, and whether it imports AGENTS.md;
- whether `.beads/` exists;
- what `bd setup claude --check` and `bd setup codex --check` say;
- which skills are found (`skills/roots`);
- whether `.gitignore` lists `.brainyard/`.

It also reports, for the plan to decide from (found by the simulation,
RFC-041):

- the build files the project has (`build-files`);
- whether AGENTS.md's project section has been drafted, is still the
  placeholder, or is absent;
- which of `/init`'s files git has not committed. The `.claude/skills` link is
  made after bd's own commit, and was left untracked with nothing said
  (byj-gly.315.8).

`init/plan` drafts only when there is code to describe. A draft of an empty
repository said so and then stayed after the code came (byj-gly.315.10); the
placeholder now waits for a later `/init`. With no prefix given, it makes a
short one from the directory's name, since a dated directory made every
bead's id `playground-20261009-…` (byj-gly.315.9).

The result is data plus a text report. A tool's version is the first
version-like token of its first line, read with a 5 s limit
(`version-timeout-ms`): a tool that hangs is reported with no version, not
waited on. bd's check counts as needing attention when bd exits non-zero or
says stale, missing or not installed. jolt is not in the list: a built
binary does not need it.

### Writing (`init$scaffold`, `byj-gly.309.3`)

Each write is planned before it is made. A dry run lists the writes. A
snapshot is taken first, under `.brainyard/init/snapshots/` (brainyard's
init-agent discipline). Writes are idempotent, so a second run changes
nothing.

- **AGENTS.md:**
  - When it is absent, it is written from the template. The template carries
    the "canonical instruction file" header and generic rules such as
    non-interactive shell flags.
  - When it exists, only a block between `<!-- BEGIN BRAINYARD -->` and
    `<!-- END BRAINYARD -->` is added or replaced. The person's text and bd's
    blocks are left as they are.
- **CLAUDE.md:** written as `@AGENTS.md` when it is absent. When it exists,
  the import is added if it is missing.
- **`.gitignore`:** `.brainyard/` is added if it is not there.
- **Skills for Claude Code** (`byj-gly.309.9`): each skill under
  `.agents/skills` that has nothing of its name under `.claude/skills` gets a
  relative link there (`../../.agents/skills/<name>`), as this repository
  has. A real directory, a file or a link already there is left alone.
  brainyard and Codex read `.agents/skills`, Claude Code reads
  `.claude/skills`, and bd writes its skill to the first only. The scaffold
  links what is there when it runs, and `init$beads` links what bd has just
  written.
- **The project's own section**, agent$init's draft, goes in a block of its
  own, `BRAINYARD:project`, placed before brainyard's block. A later run
  replaces it and never adds a second one.

The manifest names each file's kind of write: `:block`, `:import` or
`:lines`. Only blocks whose name starts with `BRAINYARD` are brainyard's;
bd's blocks are never read or written here. A dry run (`:dry-run`) returns
the plan and writes nothing.

### Beads (`init$beads`, `byj-gly.309.4`)

`init$beads` runs bd and copies nothing of bd's:

- `bd init --non-interactive --init-if-missing`, when there is no `.beads/`,
  with the prefix given or bd's default, the directory name;
- then `bd setup claude`, and `bd setup codex` when codex is installed, each
  only when `bd setup <recipe> --check` says it is not in place.

Seen with bd 1.3.0 in scratch repositories (2026-10-08):

- **`bd init` sets up everything itself**: `.beads/`, the git hooks, its
  AGENTS.md blocks, `.agents/skills/beads`, `.claude/settings.json`,
  `.codex/` and `.cursor/` (even with no codex installed), and a `.gitignore`
  block. So after an init the setup checks pass, and the setups are not run.
- **It commits its own files**, in a commit of its own ("bd init: initialize
  beads issue tracking"). Those include AGENTS.md and CLAUDE.md, and never
  anything else: a change to another file was left uncommitted. The result
  lists the commits bd made.
- **CLAUDE.md:** given one that is only `@AGENTS.md`, bd leaves it alone.
  Given one that does not import AGENTS.md, or none, bd writes its block into
  it.

Hence the order: **the scaffold runs before beads**. CLAUDE.md then already
imports AGENTS.md, and AGENTS.md already has this project's header, which bd
appends to. Any bd block still found in a CLAUDE.md that imports AGENTS.md
is removed, so CLAUDE.md stays the import (this repository's policy,
AGENTS.md "Agent Instructions"). A project's own CLAUDE.md, one that does not
import, keeps bd's block. With no bd, nothing runs and the result says so.

### The loop (`agent$init`, `byj-gly.309.5`)

1. `init$check`.
2. Predictor `init/plan` chooses from the report and the person's words
   (`/init`, `/init check`, `/init without beads`, …). The choices are:
   check only, set up beads, write the files, draft AGENTS.md.
3. `init$scaffold`.
4. `init$beads`, after the scaffold (above).
5. Predictor `init/agents-md`, when the person asked for it or AGENTS.md is
   new. It works from `init$survey`: the top-level entries, CI workflows,
   recent commits, and the head of the README and build files
   (`survey-files`, 3000 characters each). From these it writes the project's
   own section, as Claude Code's `/init` does, with only commands and rules
   the survey shows. The draft goes in its own block (`BRAINYARD:project`),
   so a later run can update it and the person's own text is never touched.
   It runs before the scaffold, which writes it.
6. `init$install`, when the person named tools to install.
7. The report: what was written, what bd did and what was installed, then
   `init$check` again. A request that only checks gets the check alone.

Each `init$*` step after the plan is `:on-error :success`, so one that fails
leaves its words in the report and the rest still run. A plan that fails
leaves every choice false, and the turn reports the check.

**Installing asks.** Following RFC-039's rule (`byj-gly.307`), asking is kept
for what only a person can decide. A change to their machine is one of those,
which is brainyard's "no surprise installs". `init$install` asks every time,
through the turn's permission channel (RFC-012), naming the tool and the
command. On a yes it runs the command with `HOMEBREW_NO_AUTO_UPDATE=1`, with
a 10-minute limit. It only runs a package manager that asks nothing
(`runnable?`: `brew install` or `npm install`, with no shell operators).
`sudo`, an installer piped into a shell (Claude Code's), and `xcode-select`
are given for the person to run. A tool already installed is left alone.
Run without a person to ask (headless, or `byj init`), it only lists
the commands.

### What to do next (`init$next`, `byj-gly.337.1`)

/init in a new, empty directory (2026-10-12) answered with a report alone —
what it wrote, the tools, the check — and left the person with an empty
tracker, a placeholder project section, and nothing to type. The answer now
ends with what to do next, and the session offers the first of it as the
suggested next prompt.

- **Data:** `init/next-steps.edn`, one entry a step: `:text`, an optional
  `:command` (`{files}` names /init's uncommitted files) and example
  `:prompts`, an optional `:suggest`, and `:when`, the facts that must all
  hold. The facts (`next-facts`) come from `project-status` after /init's
  writes: `:git`/`:no-git`, `:code`/`:no-code` (build files, as the plan's
  draft decision reads them), `:draft-pending`, `:uncommitted`, `:beads`.
- **Shipped steps:** commit /init's files; /init again when there is code
  under a placeholder section; what to build, for an empty project; questions
  and tasks, for one with code; beads' `bd ready` / `work on <id>`; `/help`
  and `/init check`.
- **Mechanism:** `init$next` renders the matching entries as a single-spaced
  list after the check, and returns the first `:suggest` as `:prompt`. The
  tree writes it to `:next-user-prompt`, which the turn already exports. No
  model: the same project gets the same steps.
- **This project's own examples** (`byj-gly.337.3`): when /init drafts the
  project section, `init/agents-md` also returns three requests for this
  project, one a line (understanding it, running its tests or build by the
  commands it found, a small improvement). The tree hands them to `init$next`
  as `examples`, and an entry marked `:examples?` (the one for a project with
  code) shows them in place of its generic prompts, the first as the
  suggestion. A model's numbering and quotes are taken off; three at most.
  Without a draft, the generic prompts stay.
- **Reaching the input line:** a call the person makes (a typed
  `:agent$init`, `direct-call*`, ctx `:person-call?`) gets the child's
  `:next-user-prompt` in the agent envelope, and the session emits
  `:session/suggestion`; a model's call never does. In `/init`'s turn the
  model makes the call, so the child's suggestion is kept beside the turn and
  offered at its end (RFC-004), the renderer showing it as it shows a turn's
  (RFC-028).

### Starter issues (`init$onboard`, `byj-gly.337.2`)

`bd ready` is the first thing anyone runs in a beads project, and after /init
it answered "No open issues". So on the setup that brings beads in, /init
files a "Get started with brainyard" epic of what to do first.

- **Data:** `init/onboarding.edn`: the `:label` every starter issue carries
  (`brainyard-onboarding`), the `:epic`, the `:issues` (each with `:when`
  facts, as next-steps' are, and `:after`, the issues of the file that must be
  done first), and `:tool`, a template filed once for each optional or
  feature tool init$check finds missing (`{name}`, `{for}`, `{install}`).
- **Shipped issues:** commit /init's files (while uncommitted); write the
  project's own rules in AGENTS.md; say what the project is for and what to
  do first, told to brainyard in the session the person is already in (an
  agent working on it asks them, writes it down as the project's goal, and
  never sends them to start a session); and, in a project with no code yet,
  run /init again once there is code — after the goal.
- **Decision:** `init/plan`'s `onboard`: true when beads is set up now for
  the first time or the person asks for a guide; false for "without guide",
  a check, or no beads.
- **Mechanism:** `init$onboard` runs `bd create` (epic, then children with
  `--parent`) and `bd dep add`, after `init$beads`. It files nothing when any
  issue with the label exists, closed or not, so a later /init never files
  them again, nor without bd or `.beads/`.
- **Next steps:** `init$next` adds the fact `:onboarding` when open starter
  issues exist (`onboarding-open?`), and the entry it shows suggests
  `run bd ready and show me the starter issues` as the next prompt.

### Onboarding checked live (`byj-gly.337.5`, 2026-10-12)

`dev/sim/onboarding/plan.edn` (RFC-041) plays a person new to brainyard on
claude-code/haiku: `/init` in an empty directory, a tiny Node project built
by a turn, then `/init` again. All three stages passed, for $0.01:

- **Empty directory:** the starter epic and its issues filed, `bd ready`
  listing them, the draft issue waiting on the first task, AGENTS.md's "New
  here?" line; the next steps named the uncommitted `.claude/skills/`, the
  starter issues, and what to build.
- **With code:** the project section drafted from the survey — `npm test`,
  `index.js`, CommonJS, nothing invented — and the example requests were the
  project's own ("Walk me through index.js and what add(a, b) does", "Run npm
  test and report any failing tests", "Add a test in test/ covering add(a, b)
  with negative numbers"). The starter issues were not filed again.

The first runs hung at the second stage: a session that ran only `/init`
holds no turn and names no id at `/quit`, so the runner started no session
for the next stage (`byj-gly.340`, fixed in `sim/run!`).

### Entry points (`byj-gly.309.6`)

- **`/init [words]`** in a session is a turn (RFC-004 "a command that
  stands for an agent", byj-gly.350; until then a direct call). Its entry,
  `resources/brainyard/commands/init.edn`, says what it is for, how the
  session's agent drives `agent$init` — ask it, read its report, call again
  when the next step waits on something, never ask for an install the
  person did not, end with its report — and its `:default-request`, the
  whole setup said as a request, for a plain `/init`, so the agent is never
  asked nothing and `init/plan` reads either one the same way. The turn's
  question is that template in a session with no turns yet, and otherwise
  `session/rephrase-command`'s, written from the turns so far: `/init` after
  a turn that installed bd asks for the check again with that said. An
  install the agent asks for still asks the person: the child keeps the
  session's permission prompt whoever dispatched it. agent$init's suggestion
  reaches the input line at the turn's end. It is listed in
  `session/commands`, so it completes and is in `/help`. `/init check`
  reports and changes nothing.
- **`byj init [DIR] [--check | --install] [--prefix P] [--no-guide]`** is routed
  early, as `update` is (`main/parse-args`, `init-command`), and needs no
  model. DIR is created when absent; without one, it is the project.
  - Without flags it writes the starting files, sets up beads, and reports the
    check.
  - `--check` only reports.
  - `--install` is the person's yes, given in advance, to install each
    missing tool a package manager can install.
  - It files the starter issues on the run that brings beads in, as /init's
    plan decides, unless `--no-guide`, and ends with the next steps, as
    `/init` does — `--check` too (byj-gly.337.4).
  - The AGENTS.md draft needs a model, so the report points to `/init` in a
    session when there is code to draft from.
  - It exits 1 when a required tool is still missing.
  - Its help is a line in `cli/help.edn`.
  - Seen on a new directory that was not a repository: `bd init` ran
    `git init` itself, then committed its files.
- **Divergence `init-sets-up-the-project`** (`docs/divergences.md` and
  `.edn`): brainyard's `/init` authors BRAINYARD.md, while the port's sets up
  the project with AGENTS.md as the one instruction file.

### Project instructions in the prompt (`byj-gly.309.1`)

brainyard's `:reference-artifact-paths` is ported as
`:project-instruction-paths`, renamed to match `:user-instruction-paths`
below, with its two files in the other order: `["AGENTS.md" "CLAUDE.md"]`.
AGENTS.md comes first because it is the one instruction file, and CLAUDE.md's
import of it is then not shown again (`brainyard.instructions`, byj-gly.313):

- relative paths resolve against the project directory, then the working
  directory;
- a file is loaded once even when it is reached two ways. A CLAUDE.md's
  `@path` import lines are followed one level: the imported file is loaded
  right after it, and the import line is not shown. A CLAUDE.md that is only
  the import shows nothing of its own;
- each file is read afresh each turn;
- the files share one budget, `:project-instructions-max-chars` (32,768
  characters), spent in their order (byj-gly.312):
  - a file past what is left is cut at the last Markdown heading before the
    limit, so whole sections are kept — when that keeps at least half the
    limit, else at the last line end — with a note saying where the rest is;
  - a file after the budget is spent is named, with where to read it.

**Where it goes, measured against brainyard.** brainyard's
`coact-system-zones` puts BRAINYARD.md (`:project-instructions`) in
`:session-context`, above the cross-turn cache breakpoint ("one cache miss
per file edit, not per turn"). Reference files such as CLAUDE.md and
AGENTS.md go under `## Live Artifacts` in `:user-context`, the per-turn tail
with no breakpoint of its own. Here AGENTS.md takes BRAINYARD.md's place, so
it goes where BRAINYARD.md goes: `## Project Instructions` in
`agent/session-context`, after `## Instructions` and before
`## Answer Format`, which is brainyard's order (`:instruction`,
`:project-instructions`, …, `:footer`). The session-context is built once a
turn, so the section is byte-stable across a turn's iterations (RFC-015).
This is recorded with divergence `init-sets-up-the-project`.

**How a file is quoted** (byj-gly.314, read in the actual prompt). Each file
is quoted whole between `<instructions source="AGENTS.md">` and
`</instructions>` tags. The file's own headings, `# Agent Instructions` and
`## Git`, had sat at or above the section's `## Project Instructions /
### AGENTS.md`, so the structure read as siblings, and nothing marked where
the project's text ended. A closing tag in a file's text is escaped.

- Outside fenced code, a line that is only an HTML comment (bd's
  `<!-- BEGIN BEADS … hash:… -->`, /init's `<!-- BEGIN BRAINYARD -->`) is
  dropped, and so is a comment spanning lines. They are markers for tools,
  not words for a model; the files keep them.
- An import line of a file that is quoted too becomes a note where it was:
  "(AGENTS.md is quoted above, not repeated here.)". Before, this
  repository's CLAUDE.md said AGENTS.md was "imported below", with the import
  gone and AGENTS.md above it.
- A file left with nothing of its own is not quoted.
- Each section says how it ranks: the agent's own instructions — its answer
  channel, its tools, permission and safety rules — stand over the project's,
  and the project's over the person's.

The section titles, their introductions and the import note are words the
model reads, so they are a prompt resource, `prompts/instructions.edn`.

**The person's own instructions** (byj-gly.313). brainyard reads
`~/.brainyard/BRAINYARD.md` for every project (`load-brainyard-instructions`)
and renders it as `## User Instructions`, after the project's. The port reads
`:user-instruction-paths`: by default `~/.brainyard/AGENTS.md` alone.
AGENTS.md replaces BRAINYARD.md at user scope as it does in a project, so
`~/.brainyard/BRAINYARD.md` is not read; a brainyard user moves its text to
`~/.brainyard/AGENTS.md`, or adds the old file to the list. A file another
tool keeps for itself,
`~/.claude/CLAUDE.md` or `~/.codex/AGENTS.md`, is not read by default:
brainyard only seeds BRAINYARD.md from them, and what is in one is often
that tool's own (this machine's `~/.claude/CLAUDE.md` is about
`clj-nrepl-eval` and Claude Code's shell). Adding it to the list shares it.
They have a budget of their own, `:user-instructions-max-chars` (8192), so a
large project AGENTS.md never pushes them out; brainyard does not cap them
at all. A file already shown as the project's is not shown again, and
relative paths are against home.

**The budget** (byj-gly.312). brainyard caps CLAUDE.md and AGENTS.md at its
`:live-artifact-max-chars`, 4000 characters, because to it they are
reference notes beside its own instruction file, BRAINYARD.md, which it does
not cap. Here they are the instruction file, so they are budgeted as one. At
4000, this repository's AGENTS.md (20,601 characters) reached a turn as its
first 4000: the intro and part of the standing rule, without the porting,
jolt, beads, build and git rules — including the one against AI attribution.
The budget is Codex's for the same files, `project_doc_max_bytes` (32 KiB, a
total across them), so a project written for Codex fits unchanged, and a
lean AGENTS.md is carried whole. Past that, a file is better split than
carried: brainyard's own AGENTS.md is 73,711 characters.

What it costs, measured: this repository's whole AGENTS.md is a 20,900-character
section, about 5,000 input tokens more a call than at 4000. It sits in the
session-stable zone, so a call mostly reads it from the cache: about $0.0005
on haiku 4.5 and $0.001 on opus 5.5, and a cache write of about $0.006 and
$0.025. That is about 2.5% of a 200K window. `:live-artifact-max-chars` is
not ported: nothing here is a reference note.

### Checked live (`byj-gly.309.7`, 2026-10-08)

The runs used haiku through the claude-code provider, with bd 1.3.0.

1. **`/init` in a session on a new Python project** (git, README, Makefile,
   pyproject, no instruction files). It took 15 s and 2 model calls
   ($0.0016). AGENTS.md, CLAUDE.md and `.gitignore` were created, bd init ran
   and committed its own files, and the check came back all green except
   codex, which is optional.
   - The draft matched the repository: the `make test` and `make lint`
     commands, the layout, and the Python version, with nothing invented.
   - Two fixes came from this run:
     - a plain `/init` had sent an empty question, which agent dispatch
       refuses, so `/init` now sends the whole line;
     - a fresh AGENTS.md had two "## This project" headings, the template's
       and the draft's, so the template's placeholder is now the project
       block itself, and a run with no draft never puts the placeholder over
       a draft.
2. **`/init` again, on the same project.** It took 1 model call ($0.0006),
   reported every file and beads already in place, and wrote nothing.
   Checksums were the same before and after, and there was no new commit.
3. **This repository, as a check and a dry run.** The check reports bd's own
   section of AGENTS.md as stale. The dry run would only append brainyard's
   block to AGENTS.md, and the existing text is kept as it is. Nothing was
   written.

`byj init` on a new directory that was not a repository: `bd init` ran
`git init` itself.

In a fresh project, Claude Code did not see bd's skill, which bd writes to
`.agents/skills` only, so the skills are now linked (`byj-gly.309.9`). Run
again on that project, `byj init` added the link
`.claude/skills/beads` and changed nothing else.

## API

- `brainyard.init`: `commands`, which are `init$check`, `init$scaffold`,
  `init$beads`, `init$survey`, `init$install`, `init$next` and
  `init$onboard`; and `next-steps`, `next-facts`, `next-text`, `next-step`,
  `onboarding`, `onboard`, `onboarding-open?`.
- Event `:session/suggestion` `{:next-user-prompt}`: a suggestion from a
  call the person made between turns.
- Also in `brainyard.init`: `check`, `tool-status`, `project-status`,
  `report-text`, `on-path`, `version-of`, `platform`, `requirements`,
  `imports-agents-md?`, `ignores-brainyard?`, `templates`, `marked-blocks`,
  `set-block`, `project-block`, `skill-links`, `plan-writes`, `apply-writes!`, `scaffold`,
  `beads`, `remove-beads-blocks`, `survey`, `survey-files`, `install`.
- `brainyard.instructions`: `files`, `user-files`, `section`, `shown-text`, `cut`,
  `default-paths`, `default-user-paths`, `default-budget`,
  `default-user-budget`.
- Config: `:project-instruction-paths` (brainyard's
  `:reference-artifact-paths`), `:project-instructions-max-chars`, `:user-instruction-paths`,
  `:user-instructions-max-chars`, and `:init-tools?`, which turns on the `init$*`
  commands for `agent$init` only, as `:claude-tools?` does for agent$claude.
- Behaviour:
  - `agents/init.edn`, `prompts/agents/init.md` and `loops/init.edn`;
  - `predictors/init/plan.edn` and `predictors/init/agents-md.edn`;
  - `prompts/instructions.edn`: the instruction sections' titles, their
    introductions and the import note;
  - `init/requirements.edn`, `init/next-steps.edn` and `init/onboarding.edn`;
  - `init/templates/{index.edn, AGENTS.md, CLAUDE.md}` (built).
- Entry points: `/init` (`session/init-line`) and `byj init`
  (`main/init-command`).

## Invariants

All of them are planned. Nothing enforces them until their beads are built.

| Invariant | Enforced by |
|---|---|
| The check changes nothing and needs no network or model: not a file in the project is written, and bd is asked only to check. A missing tool is reported with its install hint, never installed. | `init-test/the-check-only-reads` |
| The check reports the build files there are, whether the project section is drafted, a placeholder or absent, and which of /init's files are uncommitted. | `init-test/the-check-says-what-the-planner-decides-from` |
| A tool is found on PATH by a scan, its version read with its own flag; a missing one carries the install hints for the platform; every requirement names its command, need, purpose and a macOS hint. | `init-test/a-tool-is-found-with-its-version-or-said-how-to-install` |
| A tool whose entry says how to ask (`:signed-in`) is said signed in or not; an optional one missing is never a failure; none of byj's requirements names a program built on byj (`byj-ahf.7`). | `init-test/a-tool-that-says-how-to-ask-is-checked-signed-in` |
| A dry run writes nothing; a new project gets every file; a second run writes nothing and takes no snapshot. | `init-test/a-second-run-changes-nothing` |
| The person's text in AGENTS.md and CLAUDE.md, and bd's blocks, are never changed. Only brainyard's blocks, the import line and missing `.gitignore` lines are written, after a snapshot. An edit inside brainyard's block is rewritten, and one outside it stays. The project's section is replaced, never added twice. | `init-test/only-the-brainyard-block-is-written` |
| Each skill under `.agents/skills` without an entry of its name under `.claude/skills` is linked there; a real one is left; a second run links nothing. bd's skill is linked once bd has written it. | `init-test/skills-are-linked-for-claude-code`, `init-test/beads-is-set-up-by-bd` |
| bd's files come from bd: init only without `.beads/`, a setup only where bd's check asks for it, and a second run only checks. bd's block in a CLAUDE.md that imports AGENTS.md is removed, and bd's commit is reported. With no bd, nothing runs. | `init-test/beads-is-set-up-by-bd` |
| Nothing is installed without a yes from the person, every time; headless runs and a no only give the command; only a package manager that asks nothing is run; a tool already installed, an unknown one, or one only the person can install is only described. | `init-test/installing-asks` |
| `/init` drafts the project's section from the survey, writes the files with it, and answers with what it did and the check again; a request that only checks writes nothing and answers with the check. | `init-test/init-sets-up-a-project-and-reports-what-it-did` |
| The loop checks clean. | `init-test/the-init-loop-is-sound` |
| agent$init is its own loop, with the `init$*` commands only. | `agents-test/the-shipped-roster` |
| Every turn's prompt carries the project's AGENTS.md once, however it is reached — after the instruction, before the answer format — and, at the default budget, this repository's whole; a CLAUDE.md that is only the import shows nothing; `[]` turns it off. | `agent-test/the-projects-instructions-are-in-the-prompt` |
| The person's own instruction files follow the project's under `## User Instructions`, in their order, within a budget of their own; one already shown as the project's is not shown again; the default is `~/.brainyard/AGENTS.md` alone; `[]` turns them off. AGENTS.md comes before CLAUDE.md. | `agent-test/the-persons-own-instructions-follow-the-projects`, `agent-test/the-projects-instructions-are-in-the-prompt` |
| Each file is quoted whole between `<instructions source=…>` tags, a closing tag in its text escaped. HTML comment lines are dropped, even spanning lines, but not inside fenced code. An import of a quoted file becomes a note where it was. A file with nothing of its own is not quoted. The ranking is said. | `agent-test/instruction-files-are-quoted-for-a-model-to-read`, `agent-test/the-projects-instructions-are-in-the-prompt` |
| The files share one budget, in order: a file that fits is whole; one past it is cut at the last heading before the limit when that keeps half of it, else at a line end, with where the rest is; one after the budget is spent is named with where to read it. | `agent-test/instruction-files-share-one-budget-cut-at-a-heading` |
| `/init WORDS` is a turn asking the session's agent to drive agent$init with the words, and a plain `/init` the default request, never nothing; the agent calls agent$init in the turn and answers with its report; `/init check` writes nothing; agent$init's suggestion is offered at the turn's end. It is listed among the commands. | `session-test/init-is-a-turn-that-drives-agent-init`, `session-test/init-turn-can-call-agent-init-again` |
| /init's answer ends with what to do next, the entries of `init/next-steps.edn` whose facts all hold, in order: an empty repository just set up is told to commit /init's files (named) and what to build; code under a placeholder section, /init again; beads, `bd ready`. The first suggestion is the turn's `:next-user-prompt`; a check-only /init gets the steps too. When /init drafted the project section, the draft's three examples for this project replace the generic ones, the first suggested. | `init-test/next-steps-follow-what-the-project-has`, `init-test/init-sets-up-a-project-and-reports-what-it-did` |
| On the setup that brings beads in, /init files the starter epic and its issues from `init/onboarding.edn`, each labelled, children of the epic, the draft issue after the first task in a project with no code, one issue per missing optional tool; a project with any issue of the label gets none, nor one without `.beads/`. The next steps then point at them and suggest `bd ready`. The tree runs `init$onboard` when the plan says `onboard`, and says what it did first. | `init-test/starter-issues-are-filed-once`, `init-test/the-loop-files-starter-issues-when-the-plan-says` |
| A person's call of an agent (a typed `:agent$…`) hands its suggestion to the session, which offers it in the input line; a model's call of an agent is never given one in its tool result — in a turn a command became (`/init`), the subagent's suggestion is offered at the turn's end instead. | `session-test/init-is-a-turn-that-drives-agent-init`, `agents-test/delegation-runs-a-child-turn`, `tui-test/the-models-next-question-is-offered-in-the-input` |
| `byj init` ends with the next steps, `--check` too; the AGENTS.md template's brainyard block says where a newcomer starts. | `init-test/byj-init-says-what-to-do-next` |
| `byj init` has a help page. | nothing enforces it: `cli-help-test/every-dispatched-subcommand-has-help` covers only subcommands with subcommands of their own |

## Not in scope

- **Built-in skills shipped in the binary.** Skills are found only on disk
  (RFC-017). The beads skill is bd's, and `port-brainyard` belongs to this
  repository. If brainyard ever ships a skill of its own, that needs an
  `index.edn` under `resources/brainyard/skills` and a built-in root
  (`byj-gly.309.8`, decided against).
- **BRAINYARD.md.** brainyard's per-project instruction file is not ported.
  AGENTS.md takes its place here (the divergence above).
- **User-scope setup**, such as `~/.brainyard/config.edn` or a model and its
  credentials. That is brainyard's `by config` wizard, not this.

## Known gaps

None open. Not built, by decision: built-in skills shipped in the binary
(`byj-gly.309.8`; "Not in scope").
