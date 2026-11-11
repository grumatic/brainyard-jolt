# Agent Instructions

The one instruction file this repository has, for people and coding agents
alike; `CLAUDE.md` pulls it in with `@AGENTS.md`, so a rule written here is
never repeated there. How to send a change is in
[CONTRIBUTING.md](CONTRIBUTING.md), and what an agent keeps of it is below
("Sending a change").

## What this repository is

**byj** is a coding agent: [brainyard](https://github.com/grumatic/brainyard)'s
agent runtime (JVM Clojure, the `by` CLI) ported to
[jolt](https://github.com/jolt-lang/jolt), Clojure on Chez Scheme with no
JVM, and built so that **the loop is data**. What exists and what does not is
in `docs/RFCS/` (one RFC per layer) and
`docs/RFCS/RFC-008-brainyard-migration.md` (the roadmap); `design.md` is the
intent on one screen.

Programs are built on byj — services that share its sessions, evaluations
that score its runs, applications on its Agent SDK — and none of them is in
this repository. byj reaches them, and they reach byj, only by the
protocols byj offers: a process and its `--json` outputs, the session
socket, ACP, and what a session leaves on disk (RFC-070 "Standing alone").
`contract/` is what byj promises them, and `contract-test` holds byj to it.

## The standing rule: behaviour is data, `src/` is mechanism

It decides where every new piece of code goes (RFC-001).

- `src/brainyard/` is the KERNEL: the behaviour-tree interpreter, providers,
  tools, prompt assembly, the predictor runtime, the CLI. It holds mechanism
  and nothing else: no code under `src/` decides what the agent DOES.
- `resources/brainyard/loops/*.edn` (trees), `resources/brainyard/predictors/`
  (predictor specs) and `resources/brainyard/prompts/` (every word the model
  reads) are the BEHAVIOUR, loaded at run time and embedded into the binary.
- A project's `.brainyard/programs/<id>.edn` (params) and
  `.brainyard/predictors/user/*.edn` (user predictors) tune behaviour per
  project without touching the repository.

**The question to ask:** *can this change the agent's behaviour with no
rebuild?* Behaviour that only a rebuild can change has been put in the wrong
place. So a new capability is laid out this way:

1. The mechanism goes in `src/`: a small fn that takes an injected seam
   (`ctx`) and does not know when or why it is called.
2. The decision goes in a tree node, and what the node works on in the node's
   opts (`:predictor`, `:zones`, `:in`, `:out`) — a leaf fn receives its own
   node under `:node`.
3. Anything the model reads goes in a prompt resource or a predictor spec.
4. The layer's RFC names its new API and invariants.

## brainyard is the reference

Until a divergence entry says otherwise, **brainyard is right** — and "right"
is measured, not remembered.

- Read the brainyard source *and* its design doc before porting a subsystem
  (RFC-008 lists both per subsystem). Keep brainyard's names: st-memory keys,
  event names, config keys, field descriptions, defaults.
- Say where a thing came from in its docstring: "brainyard's
  `coact-repair-action`", "brainyard `clj-llm.core.predictor`".
- For anything a model sees, read what was ACTUALLY sent rather than what the
  code appears to send. Every `claude -p` call is recorded — argv and stdin,
  unclipped — to `<project>/.brainyard/logs/events.log` (on unless
  `:event-log? false`), and `dev/brainyard/dev/replay.clj` reads one back and
  re-runs it. Guessing at the cause of a model's behaviour cost a wrong fix
  once (provenance P-6).
- An intentional difference goes in `docs/divergences.md` **and**
  `docs/divergences.edn` in the same change; `divergences-test` fails
  otherwise. A difference that exists only because the port is incomplete
  is a bead, not a divergence.
- Replace JVM dependencies with jolt-native ones (RFC-008 "Substitutions").

## Jolt is not the JVM

Read jolt's README "Differences from Clojure" before assuming a JVM
behaviour. The ones that have bitten this repository:

- `jolt.process` takes the command as spread args, never a vector:
  `(apply p/process opts cmd)` (provenance P-1). `:env` replaces the whole
  environment; build it from `(into {} (System/getenv))`.
- `clojure.data.json` needs `jolt.time` loaded first — require
  `brainyard.json`, never data.json directly. `dev/cold-load.sh` loads every
  namespace in its own process to catch load-order dependence.
- The local time is asked of `date` (`brainyard.clock`, provenance P-10).
- jolt's built-in commands shadow project tasks of the same name: `jolt run`
  and `jolt build` are jolt's (provenance P-8). The tasks are named
  family:target (`run:byj`, `build:byj`, `test:byj`), which never collides.
- A release `jolt build` direct-links calls between defs, so an
  `alter-var-root` is invisible to code compiled against the var — in the
  binary only. `build:byj` builds `--no-direct-link` (provenance P-24,
  `tui-test/the-binary-runs-what-is-rebound`). Anything that works in the
  suite but not in `target/byj` is this first.
- Strings are codepoint-indexed; regexes are irregex.
- Concurrency is **ebb** (missionary on jolt), behind `brainyard.effect` — not
  core.async, not raw `future`/`pmap` (RFC-009). Code that parks follows
  RFC-009's rules: blocking calls under `via blk` with a canceller that
  releases what they started (a subprocess survives a cancel unless it is
  `destroy-tree`d); never park inside a lazy body, `mapv` included; `run!!`
  only at the edges; tests of parking code run on a fiber.

## Specs: RFCs

`docs/RFCS/` holds one specification per layer — purpose, scope (what it
decides, what it must not know, what it hands to whom), model, API,
protocol, invariants with what enforces each, known gaps. See
`docs/RFCS/README.md`.

- A change to a layer's API, protocol or invariants updates its RFC **in the
  same change**. A new layer gets a new numbered RFC and a row in the index.
- An invariant names its enforcer — a test as `ns-test/test-name`, or says
  plainly that nothing enforces it. `divergences-test` checks that every
  test a document cites is there.
- A known gap names its bead (`byj-…`), or its GitHub issue (`#n`) when
  the gap came in as one. Closing it removes the line.
- A decision (a bead of `-t decision`) closes with an RFC that records the
  measurements behind it, and a spike under `dev/` that reproduces them
  (e.g. RFC-009, `dev/brainyard/dev/effect_spike.clj`).

`docs/provenance.md` indexes past defects that code comments cite as
`(provenance P-n)`. When a guard exists because of a specific failure, add a
row and cite it. The numbers have gaps: rows about programs built on byj
were not published with it.

## Issues: beads and GitHub issues

Two trackers, each with its own job.

- **GitHub issues** are the door in. Bugs, proposals and questions from
  anyone come as GitHub issues on this repository, from its templates, and
  are answered there.
- **Beads** (`bd`, prefix `byj`) are where the work is tracked: what is
  planned, what blocks what, who has claimed it, and what an RFC's known
  gaps name. Anything left to follow up from a change is filed as a bead —
  work you chose not to do as well, with why — with `bd dep add` for real
  blockers.
- A GitHub issue taken up as work gets a bead that points at it
  (`bd create … --external-ref gh-<n>`), and the issue is linked back to the
  bead in a comment. The pull request that does it says `Fixes #<n>`; the
  bead is closed with `--reason` when it merges.
- Claim before working (`bd update <id> --claim`); close with `--reason`,
  which is what lasts: the record of what happened that the next session
  reads.
- **Beads are public.** Their Dolt data syncs to this repository's `origin`
  under `refs/dolt/data` beside the source branches (`sync.remote` in
  `.beads/config.yaml`): `bd dolt push` publishes it, `bd dolt pull` brings
  it back, and a fresh clone runs `bd bootstrap`. Nothing goes in a bead
  that could not go in a public issue, and a vulnerability never does
  ([SECURITY.md](SECURITY.md)).
- **Pull before creating, push after.** A bead's id, and a child's number
  under `--parent`, is counted in the local Dolt database, so two clones
  that create without syncing both make the same next one. Run
  `bd dolt pull` immediately before `bd create` and `bd dolt push`
  immediately after the creates and their `bd dep add`s. Any other bead
  change (a claim, a close, a comment) goes with the next create, or when
  asked. A pull that conflicts is not resolved by deleting the other side's
  beads: keep theirs, re-create yours from your saved copy
  (`bd show <id> --json`) under a new id, and fix every reference to the
  old one.
- Ids like `byj-gly.181` in RFCs, comments and commit messages name the
  tracker the project kept before it was public. The beads still open there
  were moved here under the same ids (2026-11-11); the closed ones stayed
  behind and are history. That tracker counted `byj-gly`'s children up to
  `byj-gly.488`, so a new child of `byj-gly` here would reuse a number that
  already names something else: new work is a top-level bead, or a child of
  an epic made here.
- At session start `bd prime` is run by `.claude/settings.json` (Claude
  Code) and `.codex/hooks.json` (Codex; `/hooks` shows them). If beads
  context is missing, run it. The beads skill is at
  `.agents/skills/beads/SKILL.md`.
- `bd init` pointed `core.hooksPath` at `.beads/hooks`, so nothing in
  `.git/hooks` runs. Some `bd` commands commit their own files — check
  `git log` after `bd init` or `bd setup`, and say so if one did.
- The Beads blocks at the end of this file are written by `bd setup claude`
  and `bd setup codex` and replaced whole whenever they run; what is written
  by hand inside them is lost. The policy is this section, and where the
  two disagree, it wins.

## Build & test

```bash
jolt test:byj                  # the suite, in parallel shards, ~1 min
jolt test-serial:byj           # the suite in one image, ~5 min: what catches a global-state leak (P-18)
jolt ask:byj -p mock "hi"      # the ReAct loop, offline
jolt run:byj -p mock           # the session loop, offline
jolt build:byj                 # target/byj, self-contained, stamped with its version
jolt install:byj               # target/byj as byj in ~/.local/bin (BY_INSTALL_DIR), the old one kept as .prev
jolt version:byj               # just the version stamp (RFC-029); tags are semver
jolt test:sdk                  # the Agent SDK's TypeScript and Python libraries against byj acp on a scripted mock (RFC-053)
dev/cold-load.sh               # every namespace loads on its own
dev/acp-interop.sh [--binary]  # byj acp driven by the official ACP TypeScript client; no model spent
dev/samizdat-check.sh [DIR]    # text shared with samizdat (GPL) for eight words or more, beyond the reviewed runs (RFC-070); network on the first run
dev/flake-hunt.sh N            # the suite N times, keeping the output of any run that failed
jolt -M:test brainyard.foo-test [brainyard.bar-test …]   # named namespaces, behind the suite's guards
```

Releases are the maintainers' (RFC-029 "Releasing"); `jolt release-stage:byj`
and its neighbours are theirs.

**The suite says which jolt it ran on, and warns when that is not the one it
was last verified against** — `dev/jolt-verified.edn`. `deps.edn` declares
`:jolt/min-version`, a floor and not a pin, so a `brew upgrade` can move the
runtime under the suite with nothing to say so. It is a warning and never a
failure: after an upgrade you MEANT to make, whose suite is green, re-record
the file. Never re-record to silence it.

**`jolt test:byj` is the whole suite, split across processes.** The parent
assigns namespaces to `BY_TEST_SHARDS` children (default half the cores, at
most 6) longest first, by the times in `target/test-times.edn` or
`dev/test-times.edn`; each child writes `target/test-shard-<i>.log`, and a
failing shard's log is printed whole. **`jolt test-serial:byj`** is the same
suite in one image: shards hide what one namespace does to another's
process, so run it for a change to global state and before sending
something substantial.

**One namespace is seconds**, so run what a change touches and keep the
suite for what a namespace cannot see:

```bash
jolt -M:test brainyard.tui-test                                       # ~4 s
clj-nrepl-eval -p 7899 "(brainyard.dev/test-ns 'brainyard.tui-test)"  # 1 s, no startup
```

Both keep the run behind the suite's guards. A bare `jolt -A:test -e
"(require …) (clojure.test/run-tests …)"` does not: a test that runs a turn
then keeps it in this repository's git refs (provenance P-55).

The full suite earns its minutes when no single namespace can see the
change: a kernel or shared namespace (`effect`, `agent`, `bt`, `events`,
`tools`, `config`, `llm`, `permission`, `session`, `tasks`); a new or
removed namespace or dependency (with `dev/cold-load.sh`); anything that
touches global state (provenance P-18); persistence or trajectory work.

A watchdog fails a run when no test has started or ended for
`BY_TEST_TIMEOUT_MS` (120000), naming the test it stopped in or after
(provenance P-11).

Every new test namespace goes into `test/brainyard/test_runner.clj` twice —
its `:require` list and `namespaces` — or it never runs and nothing says so;
add it to `brainyard.dev/test-order` too.

**A test that runs a turn gives itself a temporary `:project-dir`.** Without
one, `:project-dir` resolves to the git root — this repository — and every
turn leaves a trajectory there; `test-runner/run` fails the suite if
anything appears under the repository's own `.brainyard/sessions`. Nor does
a test reach the person's other stores or the network: the runner points
the memory store and the skills home at nothing, and a test that drives
`/mcp find` turns `:mcp-registry?` off (provenance P-40). A test that starts
a tmux server of its own names it with `random-uuid` (provenance P-61).

**Live models cost money or quota.** Use the mock provider for everything a
test can cover. Run a live model to establish parity with brainyard or to
confirm a fix, a few times, not in a loop — and never to search prompt
wordings for one that slips past a model's safety check.

## Developing in a live nREPL image

Keep one image running and evaluate in it, rather than paying jolt's startup
on every command. Start a server once; it writes `.nrepl-port`.

```bash
jolt -A:dev nrepl-server 7899     # dev + test paths, jolt-lang/nrepl middleware
```

Drive it with any nREPL client, or the one in this repository, which reads
code from stdin:

```bash
echo "(require 'brainyard.dev) (brainyard.dev/reload)" | NREPL_PORT=7899 jolt -A:dev -m brainyard.dev.nrepl-eval
echo "(brainyard.dev/suite)" | NREPL_PORT=7899 jolt -A:dev -m brainyard.dev.nrepl-eval
echo "(println (:content (first (brainyard.dev/prompt \"read deps.edn\"))))" | NREPL_PORT=7899 jolt -A:dev -m brainyard.dev.nrepl-eval
```

- **Reload through `brainyard.dev`**, not bare `(require … :reload)`: it
  reloads in dependency order and first clears a test namespace from jolt's
  `clojure.test` registry, which otherwise runs its tests once more per
  reload (provenance P-9). One namespace at a time; not `:reload-all`.
- `brainyard.dev` also has `prompt` (the exact messages a question produces,
  no model call), `ask` (mock or live), and `predictor` (a predictor with its
  resolved params). `brainyard.dev.replay` re-runs a recorded `claude -p`
  call from the event log.
- `brainyard.dev.tui-verify/report` drives the full-screen renderer through a
  real session and prints the frames — the TUI's live check, needing no tty
  and no tmux (RFC-028 "Verifying it live"). Read the frames: a screen that
  is unreadable still passes every assertion about it.

## Sending a change

How a change is sent, licensed and reviewed is in
[CONTRIBUTING.md](CONTRIBUTING.md). What an agent working here keeps of it:

- Commits are signed off (`git commit -s`) with the person's own configured
  identity: the sign-off is theirs, certifying the Developer Certificate of
  Origin, and an agent never signs off as itself or makes one up.
- Nothing in a commit credits an AI: no co-author line, model name or
  session link. The pull request says that an agent helped, and which.
- A vulnerability found while working goes to a private report
  ([SECURITY.md](SECURITY.md)), never to an issue, a commit message or a
  pull request.

## Shell commands that must not prompt

An agent hangs on a y/n prompt it has no way to answer, and `cp`, `mv` and
`rm` are aliased with `-i` on some machines. Pass the force flag every time
(`cp -f`, `mv -f`, `rm -f`, `rm -rf`), and likewise `apt-get -y`,
`HOMEBREW_NO_AUTO_UPDATE=1` for `brew` and `-o BatchMode=yes` for `ssh`.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:1105d646 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
