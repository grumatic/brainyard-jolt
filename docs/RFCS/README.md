# RFCs

Each layer has one specification here. It says why the layer exists, where it
ends, which API it exposes, how it speaks to the layers beside it, and what it
promises always holds, naming for each promise the thing that keeps it.

They record design, not history. Past defects live in
`docs/provenance.md`; intentional differences from brainyard live in
`docs/divergences.md`; open work lives in beads (`bd ready`).

A number missing from the table is an RFC that is not byj's.

| RFC | Layer | Owns |
|---|---|---|
| [001](RFC-001-kernel-and-userspace.md) | Kernel and userspace | The seam between mechanism in `src/` and behaviour in `resources/` and `.brainyard/` |
| [002](RFC-002-behavior-trees.md) | Behavior trees | The node kinds, how a tree nests another, how leaves find their fns |
| [003](RFC-003-react-loop.md) | ReAct loop | One agent turn: think-act, repair, route, accumulate, terminate |
| [004](RFC-004-session-loop.md) | Session loop | `by run`: many turns, slash commands, history across turns |
| [005](RFC-005-signatures-and-predictors.md) | Signatures and predictors | What the model is asked, as data, and how params tune it |
| [006](RFC-006-provider-layer.md) | Provider | Making a call and surviving its failures |
| [007](RFC-007-tools.md) | Tools | The agent's capability surface on the machine |
| [008](RFC-008-brainyard-migration.md) | Migration | What of brainyard is not here yet, in what order, and how to port it |
| [009](RFC-009-effects.md) | Effects | ebb as the substrate, the `brainyard.effect` brick (`src/brainyard/effect.clj`), and the rules for code that parks |
| [010](RFC-010-declarative-tools.md) | Declarative tools | Primitive tool specs, user tools with Tool Lisp bodies, the live registry and `tool$*` |
| [011](RFC-011-hooks.md) | Hooks | The hook bus, the tool-call gate, built-in hooks as specs, user hooks with Tool Lisp bodies |
| [012](RFC-012-permission-and-sandbox.md) | Permission and sandbox | The tool-call gate as built-in hooks, the ask channel, and the macOS seatbelt relaunch |
| [013](RFC-013-configuration.md) | Configuration | The key schema, the layers from defaults to environment, the config.edn shape and features |
| [014](RFC-014-context-compaction.md) | Context budget and compaction | What the prompt costs, what shrinks first, and what may never shrink |
| [015](RFC-015-prompt-caching.md) | Prompt caching | Zone order by volatility, where the cache breakpoints land, and what stays byte-stable |
| [016](RFC-016-session-persistence.md) | Session persistence | What a session saves, how it resumes, and who may hold it |
| [017](RFC-017-skills.md) | Skills | SKILL.md procedures, their roots, and the progressive disclosure that keeps them affordable |
| [018](RFC-018-subagents.md) | Subagents | What an agent is, how a turn delegates one, and what a child turn may see |
| [019](RFC-019-background-tasks.md) | Background tasks | Work that outlives the call that started it, and what a turn is told meanwhile |
| [020](RFC-020-memory.md) | Memory | Durable knowledge as an external command, pulled when asked and never injected |
| [021](RFC-021-programs.md) | Programs | Datasets, measurement with its spread, and demos proposed for a person to accept |
| [022](RFC-022-authoring-workflows.md) | Authoring workflows | A leaf an agent can implement, and every problem with a loop before it runs |
| [023](RFC-023-work-that-outlives-a-turn.md) | Work that outlives a turn | A checklist on disk, and the implementation pipeline as a workflow rather than four agents |
| [024](RFC-024-mcp.md) | MCP | An MCP server's tools as this agent's tools, over stdio |
| [025](RFC-025-trajectory-log.md) | Trajectory log | What a turn writes down about itself, and what counts as having succeeded |
| [026](RFC-026-event-stream.md) | Event stream | The one door events go through, what each event is, who decides how it is shown, and the log that keeps it |
| [027](RFC-027-kernel-ui-boundary.md) | Kernel/UI boundary | The seams a UI gets, why it runs in the kernel's process, and what would have to be true to put bytes between them |
| [028](RFC-028-tui-renderer.md) | TUI renderer | What the full-screen renderer is built with, how the seams attach to a component tree, and which thread may paint |
| [029](RFC-029-versions-and-updates.md) | Versions and updates | Which version this is — git tags, a build stamp, the checkout — where it is said and recorded, and the planned update path |
| [030](RFC-030-malli-tool-schemas.md) | malli for tool schemas | Whether tool arguments and results are described, checked and coerced by malli — adopted and built: arguments and results |
| [031](RFC-031-session-channel.md) | Session channel | How one live session reaches another — brainyard's `ask.sock`, ported through libc: its ops, senders, subscribers and the headless host |
| [032](RFC-032-agent-teams.md) | Agent teams | A leader session running member sessions on one piece of work: roster, members, the board, a member's prompts and spend, resuming |
| [033](RFC-033-claude-code-sessions.md) | Claude Code sessions | The project's Claude Code sessions on the agent page: read from `~/.claude`, run in tmux, opened and left from there |
| [039](RFC-039-agent-claude.md) | agent$claude | A task, usually a bead, handed to an interactive Claude Code session in tmux, briefed, guided and watched; the work handed back with what to publish, or published by the session when the task says so |
| [040](RFC-040-init.md) | /init | A directory made ready for brainyard: the tools it relies on checked with how to install them, AGENTS.md and its CLAUDE.md import, beads set up by bd, the project's instructions in every prompt |
| [041](RFC-041-dogfood-simulation.md) | Dogfood simulation | brainyard building a mini-playground from scratch while a script plays the person: stages, checks, findings, a run compared with the last |
| [042](RFC-042-memory-hint.md) | Memory hint | Each turn told, by key and first line, the few memories its question shares rare words with; the contents stay a pull; measured offline and live |
| [043](RFC-043-share-sidecar.md) | The share sidecar | byj's side of sharing a session: the sidecar started with `--share` as a task of the session, the socket ops a note and an injected turn arrive by, the owner's approval at the terminal, sharing other sessions by reference, `/share` and `share$*`; the service behind the sidecar is not byj's |
| [049](RFC-049-codex.md) | Codex sessions | How byj reads, drives and delegates to Codex sessions, measured on Codex CLI 0.163: rollouts and their item_completed messages, liveness by the thread writer's flock through jolt.ffi, the TUI in tmux through a core shared with Claude Code, Claude Code's hooks given as session flags with their trust records, the sandbox and hook that keep a delegated session from publishing, `exec --json` and the codex provider on it, OTel, `codex queue`; built |
| [053](RFC-053-agent-sdk.md) | Agent SDK | An application driving byj from its own code: `byj acp`, the Agent Client Protocol v1 on stdio with byj's `_byj/*` extensions (client tools, hook callbacks, events, predict), `brainyard.sdk` underneath, TypeScript and Python client libraries, and app templates that are applications; implemented, and driven by hand in Zed |
| [067](RFC-067-emacs.md) | Emacs and the agent | `/emacs` (decided 2026-11-05; built: the foundation, literate org, coaching, Emacs asking byj, xref and diagnostics, and byj acp through an editor's fs; extended and built 2026-11-08 with phases 7–10: what this Emacs says about itself, each turn's change kept as git snapshots and reviewed in magit or vc, learning Emacs, setup and hand-over): which Emacs byj reaches (`:emacs-server`, the project's daemon unless the person names their own), one call path whose arguments are JSON files and never elisp, the `emacs$*` tools, literate programming in org, a coach stance kept across turns, and `byj.el`'s commands asking byj back through the session channel |
| [069](RFC-069-turn-checkpoints.md) | Turn checkpoints and rewind | The project's work tree snapshotted as git commit objects as each turn begins and ends, every turn kept as `refs/byj/turns/<session>/<turn>` (from RFC-067's phase 8); `byj turns` to list and diff them; the files put back to a turn, one turn undone alone by a three-way merge, and either taken back, what was replaced kept first as `refs/byj/rewinds/…`; a conversation taken back to a turn by a fork cut before it (decided: fork, not truncation, measured); `/rewind`, `/undo-turn`, `/fork`; implemented 2026-11-08 |
| [070](RFC-070-standing-alone.md) | Standing alone | How a program built on byj reaches it — a process, a socket or a file, never an extension registry — the contract byj exports as golden files in `contract/`, that byj's tree names no such program, samizdat's GPL text kept out (`dev/samizdat-check.sh`, by hand when wanted), and what must be true before byj's tree is published |
| [071](RFC-071-loop-proposals.md) | Loop proposals | A better loop as a proposal: a candidate run by a clone of the agent on tasks rebuilt from the project's own turns, compared pairwise with the loop in force, accepted by a person, the project's loop from the next session (designed and built 2026-11-13, evaluated live once; an improver agent decided not now; `byj-ng7`) |
| [072](RFC-072-loop-extension-slots.md) | Loop extension slots | Room a bundled loop leaves for a project: a slot expanded at load time into the trees a project attached to it, held to the slot's keys, made live as a loop proposal is (decided to build; the node kind built, `byj-9sp`) |

## The reference implementation

This repository ports brainyard (`../brainyard`, JVM Clojure) to jolt. Each
RFC names the brainyard namespaces its layer comes from. When the port and
brainyard disagree about behaviour, brainyard is right until a divergence entry
says otherwise — measure against it (`dev/brainyard/dev/replay.clj`), do not
reason about it.

## Where a layer stops

An RFC's **Scope** section settles three things:

- **The decisions it owns.** A layer that chooses *what the agent does* is
  userspace, and what it holds lives in `resources/` (or in a project's
  `.brainyard/`). A layer that only carries out *what it is asked to* is
  kernel.
- **What stays hidden from it.** Turns are invisible to a provider; the
  ReAct loop is invisible to the tree interpreter; a predictor cannot tell
  which node called it.
- **What it passes on, and to which layer.** Each value travels as named data
  over a named seam, so one can be traced from where it starts to where it
  ends.

## Terms

A seam's direction is marked `[in]` or `[out]`. A **contract** is something a
caller can count on. An **invariant** is a property the layer keeps, stated
together with what keeps it, most often a test written `ns/test-name`. An
invariant nothing checks is labelled so: unchecked, it is only a habit, and
calling it more is how it quietly stops holding.

**Status** is one of *draft* (not built), *implemented* (built and tested),
*partial* (built, with known gaps listed at the end).

## Changing an RFC

- A change to a layer's API, protocol or invariants updates its RFC **in the
  same change**.
- A new layer gets a new RFC with the next number, and a row above.
- A known gap names its bead (`byj-…`). Closing the bead removes the gap line.
