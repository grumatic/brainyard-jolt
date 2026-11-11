**byj v0.3.17** — byj is published, under the MIT License, from a repository of its own; a release is byj alone; two fixes for Linux. Full notes in `CHANGELOG.md`.

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
