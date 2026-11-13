# RFC-070 — Standing alone

**Status:** partial. byj's half of a repository split (`byj-ahf`): byj is
to be published as open source in a repository of its own, from one
curated snapshot of this tree, and the programs built on it stay where they
are. This RFC says what byj promises such a program and what byj's tree must
be before it is published. The plan of the split itself, and what moves
where, is not byj's and is kept with the programs that move.

## Purpose

byj is a coding agent that other programs are built on: a service that
shares its sessions, an evaluation that scores its runs, an application on
the Agent SDK. Each of them needs something of byj, and byj must not need
anything of them, or it cannot be published, versioned or changed alone.
So the dependency runs one way, and what flows along it is named.

## Scope

**This RFC decides** how a program may reach byj, what byj exports for it
to test against, what byj's tree may name of any program built on it, and
what must be true of the tree before it is public.

**It must not know** what any such program does. Each of byj's layers keeps
its own RFC for the protocol it offers: the session channel (RFC-031), the
share sidecar (RFC-043), ACP and the SDK (RFC-053), the trajectory
(RFC-025).

## Model

1. **A program reaches byj as a process, a socket or a file.** It runs
   `byj` and reads its `--json` outputs; it speaks to a session over
   `ask.sock` (RFC-031) or to `byj acp` (RFC-053); it reads what a session
   leaves on disk (`.brainyard/sessions/<id>/`, RFC-025, RFC-016). byj
   keeps no extension registry and loads no other program's code: a
   feature that is a program's own is that program's, and byj names it
   nowhere.

2. **byj exports its contract** as golden files in `contract/` (its
   `README.md` lists them): the session socket's `:status` reply, the
   frames a turn that reads a file streams to a `:subscribe` and its `:ask`
   reply; the trajectory and `meta.edn` that turn leaves; `byj agent
   --json`; the price table. `contract-test` holds each to what byj gives
   now, what varies between two runs written `<varies>`, `<sid>`, `<dir>`
   and `<socket>`, and a prompt's words `<prompt>` — their shape is the
   contract, their wording byj's to change. `BYJ_CONTRACT_RECORD=1`
   records them again, and the diff is the contract changing. A program
   keeps a copy and tests what it reads against its copy, never against
   byj's tree. ACP is not in `contract/`: its contract is the ACP
   specification, which `dev/acp-interop.sh` holds `byj acp` to with the
   official client, and the SDK's tests drive it.

3. **byj's tree reads nothing of another program's.** byj's tree is the
   paths `dev/byj-tree.edn` lists; whatever it does not list is not byj's.
   Its sources, tests and dev namespaces name no path into a program built
   on it; its documents and scripts name such a program only by the
   protocol it speaks. A file added outside the listed paths that is byj's
   is added to the list.

4. **Text from samizdat** (GPL-3.0-or-later since 2026-08-22, samizdat
   `065bf4d`) is not in byj's tree. Everything this repository took from
   it appeared after that change, so no EPL copy can be relied on; byj's
   build depends on nothing of samizdat's. The one copied file,
   `dev/brainyard/dev/nrepl_eval.clj`, was written anew on jolt-lang/nrepl's
   own client (`nrepl.core/new-session`, EPL-2.0); Tool Lisp's
   `indent-repair` was found to be its own (samizdat's calls parinferish;
   this one lexes lines itself), and the first commit's `bt`, `agent`,
   `llm` and `tools` shared nothing beyond idioms; the prose shared with
   it — about 500 words of AGENTS.md, the RFC index's, divergences',
   provenance's, RFC heads' — was said again in this project's words.
   `build:byj` packs only `src` and `resources`, so no released binary
   ever held samizdat's code.

   **The check.** `dev/samizdat-check.sh [DIR]` clones samizdat once into
   `target/samizdat-check` and reads it at `23bba55`, `046a4b8` and
   `9411f00`; `brainyard.dev.samizdat-check` fails on any run of eight words
   or more shared with it that does not lie inside a run listed in
   `dev/samizdat-check.edn`, which holds the runs reviewed and accepted,
   each with why: Clojure idioms, namespace requires, RFC table heads, FFI
   declarations, CI boilerplate, message shapes, a dependency's coordinate,
   quoted error text. Over the whole checkout an accepted run that no longer
   occurs is named as a warning, to be taken off; over a given tree (an
   export) it is not, since the tree holds part of the checkout. `bench/`
   (Terminal-Bench, Apache-2.0) and what `bd setup` writes are not read.

   **It is run by hand, only when wanted** (decided 2026-11-13). byj's
   source has been public since 2026-11-11, and what it once shared with
   samizdat was said again in its own words (above); nothing runs the
   check on its own — not CI, not `jolt test:byj` (it needs the network),
   not a release, and not reading samizdat again. The script and its list
   of reviewed runs stay, for a person who wants the answer.

5. **The export gate** (`dev/byj-export.sh [REF] [DIR]`, `byj-ahf.2`) lays
   byj's tree out alone — `git archive` of the paths `dev/byj-tree.edn`
   lists at REF (HEAD), into a repository of its own of one commit, so no
   path resolves to the checkout it came from and no test of it leaves
   anything there; `deps.edn` loses the marked block of tasks that are not
   byj's — and there builds it (`jolt build:byj`), runs its suite (`jolt
   test:byj`) and loads each namespace on its own (`dev/cold-load.sh`); it
   no longer runs the samizdat check (above). Before that it refuses a tree
   that holds `share/` or this repository's tracker and agents' metadata,
   names an internal identifier (accounts, hosts, people's handles, private
   repositories; the list is the gate script's, outside byj's tree, with the
   paths excused until their beads close), or holds a secret gitleaks finds.
   It runs on every push to main
   (`.github/workflows/byj-export.yml`, which is not in byj's tree).

6. **Before byj's tree is public:** a root LICENSE (MIT, Grumatic, Inc., as
   the Agent SDK's) and a NOTICE naming the licences of what a byj binary
   carries — jolt's runtime and what it embeds, each library `deps.edn`
   resolves, at its revision — and of what is used but not distributed; no internal identifier, secret or tracker
   metadata in it; and documents for public contributors. Those are kept
   apart from the repository's own, which are its maintainers', in
   `dev/byj-public/` — the LICENSE and NOTICE, a README, AGENTS.md,
   CONTRIBUTING with the contribution policy (MIT inbound with a DCO
   sign-off, one maintainer's approval with CI green, agent-written changes
   said in the pull request and never credited in commits), SECURITY (GitHub
   private reports), the Contributor Covenant 2.1, a CHANGELOG started
   afresh, issue and pull request templates, and the public CI; this repository has no root licence, since it is not all
   byj's — and the export gate refuses a tree without the two, and lays them
   over byj's tree, so its search and gitleaks read what
   will be published. This repository's `CHANGELOG-latest.md` is not in the
   tree: the public documents carry the public repository's own, its first
   release's notes, v0.3.17.

## Invariants

| invariant | enforced by |
|---|---|
| byj's exported contract (`contract/`) is what byj gives now | `contract-test/the-session-socket-and-the-trajectory-are-byjs-contract`, `contract-test/the-agent-listing-is-byjs-contract`, `contract-test/the-price-table-is-byjs-contract` |
| byj's source and resources name no program built on it, and it offers no extension point for one | `split-test/byjs-source-names-none-of-shares-namespaces`, `split-test/byjs-resources-ship-none-of-shares-agents-or-their-words` |
| byj's sources, tests and dev namespaces name no path into another program's tree | `split-test/byjs-tree-reads-nothing-of-shares` |
| byj's tree is `dev/byj-tree.edn`'s paths, each there; its documents and scripts name another program only by the protocol it speaks — not its RFCs, binaries or tree — but for the paths `:until` excuses, each until its bead closes | `split-test/byjs-documents-and-scripts-name-no-program-built-on-it` |
| byj's source, tests and resources name another program only by the protocol it speaks, but for the paths `:until` excuses until their beads and `:named` names for good, each with why | `split-test/byjs-code-names-no-program-built-on-it` |
| Every test byj's RFCs, divergences and provenance cite, by namespace and name, is a test or helper of that namespace (a `*` for the name, the namespace) | `divergences-test/every-test-a-document-cites-is-there` |
| byj's tree, laid out alone, builds, passes its suite and loads each namespace on its own | `dev/byj-export.sh`, on every push to main (`.github/workflows/byj-export.yml`) |
| byj's tree carries its LICENSE and a NOTICE that names every library `deps.edn` pins, at its revision | `dev/byj-export.sh`, on every push to main; what those libraries depend on in turn is read by a person |
| byj's tree names no internal identifier and holds no secret, and no tracker or agents' metadata is in it | `dev/byj-export.sh`'s search and gitleaks (required in CI), on every push to main; a person read the key-shaped fixtures (`trajectory_export_test.clj`'s are an alphabet run) |
| byj's source holds no run of eight words or more of samizdat's text beyond the reviewed runs | nothing runs it on its own (decided 2026-11-13): `dev/samizdat-check.sh`, by hand, when wanted |
| An accepted run that no longer occurs is taken off the list | nothing enforces it: `dev/samizdat-check.sh` over the whole checkout names one as a warning |

## Known gaps

- Nothing yet shows that a person outside the team can build, test and send
  a change from the public documents alone, and the public CI
  (`dev/byj-public/.github/workflows/ci.yml`) has not run: both wait for the
  public repository (`byj-ahf.13`).
