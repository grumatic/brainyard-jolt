# Agent Instructions

The canonical instruction file for this repository, read by every coding
agent: brainyard and Codex read it directly, Claude Code through CLAUDE.md's
`@AGENTS.md`. Keep project policy here, outside the marked blocks; each block
is written by a tool (`/init`, `bd setup`) and rewritten by it, so nothing
hand-written belongs inside one.

<!-- BEGIN BRAINYARD:project -->
## This project

What it is, how to build and test it, and the conventions to follow. `/init`
drafts this block from the repository; move what you want to keep out of the
block to edit it freely.
<!-- END BRAINYARD:project -->

<!-- BEGIN BRAINYARD -->
## Working here with brainyard

- `.brainyard/` holds brainyard's state for this project — sessions, logs,
  delegated work, and `config.edn` for project settings. It is git-ignored.
- Run shell commands non-interactively: an agent cannot answer a y/n prompt.
  `cp -f`, `mv -f`, `rm -f`, `apt-get -y`, `HOMEBREW_NO_AUTO_UPDATE=1` for
  brew, `ssh -o BatchMode=yes`.
- When this project has `.beads/`, beads (`bd`) is its task tracker: see bd's
  own section of this file.
- New here? `bd ready` lists what to start on, `/help` the session's
  commands, and `/init check` what the project and this machine lack, and
  how to install it. `/init` rewrites this block.
<!-- END BRAINYARD -->
