# Changelog

What changed for someone running `byj`, release by release. Versions are the
git tags (semver; RFC-029). A release's notes are its section here, copied
into `CHANGELOG-latest.md` when it is published (RFC-029 "Releasing").

## Unreleased

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
