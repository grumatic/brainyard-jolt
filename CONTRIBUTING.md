# Contributing to byj

Thank you for looking. This file says how to build byj, how to test a
change, and how to send it. The rules a change must keep — where code goes,
how byj is ported from brainyard, what jolt does differently from the JVM —
are in [AGENTS.md](AGENTS.md), which people and coding agents both follow.

## Before you start

- **A bug:** open an issue from the bug template first, unless the fix is a
  line or two. Say what you ran, what you expected and what happened, and
  the output of `byj --version`.
- **A feature or a change of behaviour:** open an issue from the proposal
  template and agree on the shape before writing much code. byj keeps its
  behaviour as data — loop trees, predictor specs and prompts under
  `resources/` — so many changes are a tree or a prompt, not new code
  (AGENTS.md "The standing rule").
- **A difference from brainyard**, which byj is ported from: say whether it
  is intended. An intended one is recorded in `docs/divergences.md` and
  `docs/divergences.edn`; one that exists because the port is incomplete is
  an issue.
- **Finding something to work on:** the maintainers track planned work in
  [beads](https://github.com/gastownhall/beads) (`bd`), kept in this
  repository: after `bd bootstrap` in a clone, `bd ready` lists what is open
  and unblocked. You need no `bd` to contribute: an issue is how you reach
  us, and a bead that came from one points back at it (AGENTS.md "Issues").

## Set up

You need [jolt](https://github.com/jolt-lang/jolt) at the version
`dev/jolt-verified.edn` names (`deps.edn` has the floor), git, and tmux for
the tests that drive a terminal. On macOS byj loads OpenSSL from Homebrew's
`openssl@3`.

```bash
brew install jolt-lang/jolt/jolt openssl@3 tmux
git clone https://github.com/grumatic/brainyard-jolt
cd brainyard-jolt
jolt test:byj                  # the suite on the mock model, ~1 min
jolt ask:byj -p mock "hi"      # the agent loop, offline
jolt build:byj && target/byj --version
```

No test spends a model: they run on the mock provider. To try a change on a
real model, `byj` uses Claude Code's sign-in with no API key of its own
(`-p claude-code`), or a provider key from the environment or a `.env`
(`.env.example` lists them).

## Make the change

- Keep to AGENTS.md: mechanism under `src/`, behaviour in `resources/`, and
  brainyard's names and defaults unless a divergence says otherwise.
- A change to a layer's API, protocol or invariants updates its RFC under
  `docs/RFCS/` in the same change, and each invariant names the test that
  holds it.
- Add tests beside the code you change (`test/brainyard/<ns>_test.clj`). A
  new test namespace is added to `test/brainyard/test_runner.clj`.
- A test that runs a turn gives itself a temporary `:project-dir`, uses the
  mock provider, and reaches nothing outside its own directories.
- `contract/` is what programs built on byj rely on. If a change moves it,
  `BYJ_CONTRACT_RECORD=1 jolt -M:test brainyard.contract-test` records it
  again, and the change says why the contract changed.

## Check it

```bash
jolt test:byj           # always
jolt test-serial:byj    # when the change touches global state, or is large
dev/cold-load.sh        # when a namespace or a dependency is added or removed
jolt test:sdk           # when byj acp or the Agent SDK changes (needs Node and uv)
```

Say in the pull request which of these you ran, and what a reviewer should
try by hand if a test cannot cover it (a screen, a live model).

## Send it

- One change per pull request, from a branch of your fork, against `main`.
- The commit message says what changed and why, in sentences; name the RFC
  and the issue it touches. Sign each commit off (`git commit -s`; Policy,
  below).
- Fill in the pull request template.

## Policy

Decided by the maintainers on 2026-11-11.

- **Licensing.** A contribution is accepted under byj's licence, MIT
  (`LICENSE`), as it stands, and each commit says you may send it: sign it
  off (`git commit -s`), which adds a `Signed-off-by:` line with your name
  and address and certifies the [Developer Certificate of
  Origin](https://developercertificate.org). A pull request whose commits
  are not all signed off by their authors is not merged; CI checks it.
- **Review.** One maintainer's approval, with CI green — the suite, the
  namespaces loaded one by one, and the sign-offs. A maintainer answers a
  pull request or an issue within a week; that is a first answer, not a
  merge.
- **Scope.** Fixes; providers, tools and hooks; behaviour as data (trees,
  predictors, prompts); and ports of the rest of brainyard (RFC-008). A
  feature that is a program's own — a service, an evaluation, an
  application — belongs in a program built on byj, which reaches byj by its
  protocols (RFC-070 "Standing alone"); byj adds no extension registry for
  one.
- **Changes written with a coding agent** are welcome, under the same review:
  you answer for them as for your own. Say in the pull request that an agent
  helped, and which. Commits carry no AI credit: no co-author line, model
  name or session link; the sign-off is yours.
- **Vulnerabilities** are reported privately, never in an issue:
  [SECURITY.md](SECURITY.md).
- **Conduct.** The project keeps the Contributor Covenant 2.1
  ([CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)), enforced by the maintainers.
