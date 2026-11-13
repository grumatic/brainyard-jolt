# Changelog

What changed for someone running `byj`, release by release. Versions are the
git tags (semver; RFC-029). A release's notes are its section here, copied
into `CHANGELOG-latest.md` when it is published (RFC-029 "Releasing").

## Unreleased

- **`byj loops eval --keep` keeps the clones**: it kept each, then removed
  the directory that held them. The report says where they are.
- **`byj loops watch`** compares the turns run since a project's loop last
  changed with the turns before, and when they got worse — costlier, longer,
  more errors or refusals, more turns left unfinished — stages the loop
  before as a revert at `/proposals`. It never accepts one.
- **`byj loops tasks build --judge LM`** asks a judge model, on the light
  tier unless one is named, whether each task's turn did what was asked, and
  keeps its verdict beside the task as a second signal — never the only one.
  It spends at most `--max-cost` (0.25 USD).
- **A rewind says the last turn it came after** ("rewind to turn K's start,
  after turn N"), so a loop's task set knows exactly which turns it took
  back, not from commit times to the second.
- **`byj loops tasks retire ID…`** takes tasks out of a set and releases the
  refs that kept their start commits; a rebuild does not bring them back.
- **`byj loops eval` runs each turn in a byj of its own under the macOS
  seatbelt**, its clone the place it may write, so a shell command in a
  candidate's turn cannot reach the project by a path the gates miss;
  unsandboxed off macOS, and in the evaluating byj with `--in-process`. The
  report says which. A lead on too few tasks to test is no longer a win.
- **A loop's task set finds its own check.** `byj loops tasks build` takes,
  when given no `--check`, a dogfood plan's stage checks (`--plan FILE
  [--stage ID]`), else the set's own, else the project's new
  `:check-command` config key, and says which it took.
- **`byj loops propose CANDIDATE`** evaluates a candidate loop and stages it
  at `/proposals` only when it wins; otherwise it says why and stages
  nothing. `byj loops propose --bundled` evaluates byj's own loop against a
  project's: when the project's no longer beats it, its removal is proposed —
  what `byj doctor` now names once an upgrade changed the loop it was made
  from.
- **`byj loops eval CANDIDATE`** runs a candidate loop against the loop in
  force on a task set (`byj loops tasks build`): each run a headless turn in
  a clone of the repository at the task's start, so nothing of the
  project's is touched; scored by the task's checks, compared per task, with
  guards on cost, iterations, time, tool errors and refusals, and a
  `--max-cost` after which no run starts. The report is written under
  `.brainyard/loops/evals/`. A live model costs money: it runs when asked.
- **A better loop is a proposal at `/proposals`** (`loop/…`): staged with its
  evidence and why, shown as a diff of the expanded tree against the loop in
  force, and made the project's loop by a person's accept — from the next
  session, which the session says. A candidate that fails `flow check`, or
  names a permissions setting, is not staged; one proposed against a loop
  that has since changed is not accepted. `byj loops revert [LOOP]` stages
  the loop before the last change as a proposal of its own.
- **No tool writes a project's loop.** A file in `.brainyard/loops/` is the
  project's loop from the next session, so writing one, its `history.edn` or
  the directory is refused for every tool in every mode, `flow$patch`
  included, and for Claude Code and Codex through byj's hook; a shell command
  that names one is refused too. Reading one is not. Write a candidate loop
  to another file and try it with `--loop`.
- **`flow$patch` meets the path gate**: the file it patches is checked
  against the allowed directories, as `write-file`'s is.
- **A workflow's predictor step is drawn behind `◇`**, a tool's behind `⏺`:
  `agent$claude`'s view showed `claude/plan(…)` and `claude$open(…)` alike,
  so its choice of session and brief read as a tool the model had called.
  `claude/plan` is a predictor, a model call the loop makes; `claude$open`
  is a tool.
- **Under Codex, byj's hook refuses what it would ask a person about**,
  where before it answered "ask", which Codex rejects as unsupported, so the
  call ran as if byj had said nothing. Codex's hook cannot ask, and its
  default mode runs a sandboxed command unasked, so byj refuses it and says
  why, for the model to ask you; allow it in byj's config to let it run.
  Claude Code is still asked.
- **byj's hook for Claude Code and Codex answers in tens of milliseconds**:
  the command `byj hook claude-settings` and `byj hook codex-flags` install
  now asks `byj hook serve`, a byj kept up on a unix socket
  (`~/.brainyard/hook.sock`), with curl, so a tool call no longer pays byj's
  start — 36 ms against 0.63 s, measured. The first call finds none,
  is answered as before and starts it; it exits after 20 minutes with no
  call, and when byj is upgraded. A command installed before still works, at
  its old cost: print the settings again to get the new one.
- **`byj hook claude-settings`** is the new name of `byj hook settings`, which
  prints the Claude Code settings that install byj's gates as its PreToolUse
  hook: named for the agent, as `byj hook codex-flags` is. `byj hook
  settings` still answers, for a script written against v0.3.18 or v0.3.19.

## v0.3.19 — 2026-11-12

- **byj for arm64 Linux** (Graviton, Ampere, a Raspberry Pi 4 or 5 on a
  64-bit OS, a Linux container on an Apple-silicon Mac): a release now has
  `byj-<version>-linux-arm64`, and Homebrew on such a machine installs it.
  jolt publishes no arm64 Linux build, so the release builds jolt from its
  source first, as jolt's own release builds its x86_64 one; byj's suite
  runs on it nightly.

## v0.3.18 — 2026-11-12

- **`byj` alone starts a session.** `run` is now the default command:
  `byj` opens one, and `byj "explain deps.edn"` opens one whose first turn
  is that, as `claude "…"` does. One question answered and done is
  `byj --prompt "…"` (or `byj run --prompt`), as `claude -p` is; `-p` is
  still `--provider`. `byj ask "…"` is unchanged, so a script that runs
  `byj "…"` for one answer should say `byj ask` or `--prompt`.
  `byj --attach SESSION "…"` still asks that session.
- **`claude$await` shows a reply that came before the wait began.** A
  delegated Claude Code session that answered between `claude$say` and
  `claude$await` had its reply returned but never streamed as progress;
  each wait now picks up where the last one stopped.
- **Every source file names its licence**: the two-line SPDX header,
  `Copyright (c) 2026 Grumatic, Inc. and byj contributors` and
  `SPDX-License-Identifier: MIT`. `LICENSE` and `NOTICE` name the
  contributors too, since a contribution signed off under the DCO stays its
  author's.

## v0.3.17 — 2026-11-11

- **byj is published**, under the MIT License, from a repository of its
  own. Releases before it were made while byj was developed in a private
  one; their notes were not carried over, and the tap keeps their binaries.
- **byj is released alone** (RFC-029): a release is byj and nothing else,
  and on the tap it is always the latest release, which `byj update` reads,
  so a byj installed before updates as it did.
- **A bash call that leaves something running no longer ends your shell on
  Ubuntu** (provenance P-62): stopping what it left now names the process
  group in a way Ubuntu 22.04's `kill` reads as that group, not its own.
- **What a delegated session leaves running in a worktree that is removed is
  stopped on Linux too**: a deleted directory reads as `<path> (deleted)`
  there, and byj now sees it.
