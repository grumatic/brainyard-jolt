# RFC-008 — Migrating the rest of brainyard

**Status:** draft. Tracked by epic `byj-gly`.

## Purpose

Specifies what of brainyard (`../brainyard`) is not in this repository yet, the
order to port it in, and the procedure every port follows. The inventory is a
snapshot (2026-09-17) of brainyard's `components/`, `bases/` and `docs/`; beads
is the live state.

## Scope

**This RFC decides** order and method.

**It must not** restate each subsystem's design — that is brainyard's
`docs/` until the port writes its own RFC.

## The porting procedure

Every subsystem port, in this order:

1. **Claim the bead** (`bd update <id> --claim`). Read brainyard's source *and*
   its design doc (listed below) before writing anything.
2. **Decide the halves** (RFC-001). What is mechanism goes in `src/brainyard/`;
   what is behaviour — when it runs, what it says, thresholds — goes in a tree,
   a predictor spec, a prompt resource or a params file.
3. **Write or extend the RFC first** for any new layer, API or invariant.
4. **Port faithfully.** Keep brainyard's key names, event names and defaults.
   Every intentional difference gets an entry in `docs/divergences.md` +
   `docs/divergences.edn` in the same change.
5. **Replace JVM dependencies with jolt-native ones**, never with a JVM shim
   hunt: see the substitution table.
6. **Measure against brainyard**, not memory of it. For anything a model sees,
   compare the request (`dev/brainyard/dev/replay.clj`); for behaviour, run
   `by ask` and `byj ask` on the same input.
7. **Tests pin the invariants** the RFC names; `dev/cold-load.sh` passes.
8. **Close the bead with `--reason`**, and file beads for what was deferred.

## Substitutions

| brainyard (JVM) | jolt |
|---|---|
| `java.net.http` / clj-http-native | `jolt-lang/http-client` (already used) |
| `ProcessBuilder`, `clojure.java.shell` | `jolt.process` — spread command vectors (provenance P-1) |
| cheshire / data.json | `clojure.data.json` after `jolt.time` (`brainyard.json`) |
| malli | plain field specs checked by hand (as `brainyard.predictor`, `brainyard.tools`); revisit if malli loads under jolt — samizdat runs malli 0.17 |
| selmer | pre-rendered resources; samizdat runs selmer on jolt if templating is needed |
| missionary | ebb, behind `brainyard.effect` — decided in RFC-009 (`byj-gly.1`), brick `byj-gly.26` |
| core.async | not on the runtime path (no cancellation); only memory capture's fan-in may use jolt's core.async (RFC-009) |
| sqlite-jdbc | `jolt-lang/db` (jdbc.core over system libsqlite3) |
| SCI | not ported: agent-written code is Tool Lisp, interpreted (RFC-010); the code channel is replaced by `tool$run` — `byj-gly.11` |
| mulog | `:on-event`, declared and levelled, written to `.brainyard/logs/events.log` with `log$events` / `log$search` to read it back (RFC-026), plus an append-only record per TURN (RFC-025) |
| aws-api | the aws CLI as a subprocess — jolt has no SigV4 (divergence `bedrock-via-cli`) |
| GraalVM native image | `jolt build` (`jolt build:byj`) |

## Inventory and order

Order is by dependency first, then by how much each unblocks. S/M/L is porting
size on jolt.

### Wave 0 — decide

| bead | subsystem | brainyard | design docs | size |
|---|---|---|---|---|
| `byj-gly.1` | Effect / concurrency substrate — **decided: ebb** (RFC-009) | `components/effect` (missionary) | `design/functional-effect-system.md` | done |
| `byj-gly.26` | `brainyard.effect` brick on ebb — **done** | `components/effect` | RFC-009 | done |
| `byj-gly.27` | ~~Adopt effects in the loop; turn cancellation~~ **done** — the tree ticks as a Task, tool dispatch is bounded, and a cancelled turn kills its subprocesses (RFC-009) | `agent/core/{agent,runtime}.clj`, `clj-llm/core/llm.clj` | RFC-009 "Migration" | done |

### Wave 1 — small and independent

| bead | subsystem | brainyard | design docs | size |
|---|---|---|---|---|
| `byj-gly.2`, `byj-gly.32` | Permission gate (tool, effect and path) + seatbelt sandbox — **done** (RFC-012) | `agent/common/tool_permission.clj`, `components/os-sandbox` | `sandboxing.md`, `specs/permission.md` | done |
| `byj-gly.3` | Hooks — **done** (RFC-011) | `agent/core/hooks.clj`, `common/user_hooks.clj` | `design/event-bus-and-reactor.md` | done |
| `byj-gly.4` | Tool discovery — **done** (RFC-007) | `agent/core/tool.clj`, `common/{tools,tool_agent}.clj` | `core/tool.md`, `specs/tool-system.md` | done |
| `byj-gly.5` | Prompt caching — **done** (RFC-015) | `clj-llm/core/llm.clj`, `common/cache.clj` | `design/prompt-cache-arrangement.md` | done | S |
| `byj-gly.6` | Layered config — **done** (RFC-013) | `agent/core/{config,feature,env_files}.clj` | `core/config.md`, `specs/configuration.md` | done |
| `byj-gly.25` | Git-root project dir — **done** (RFC-013) | `agent/core/config.clj` project-dir | `core/config.md` | done |
| `byj-gly.24` | ~~OpenAI json_schema~~ **done** — `response_format json_schema`, `strict` auto-detected from the schema (RFC-006) | `clj-llm/core/llm.clj` build-openai-body | — | done |

### Wave 2 — the loop grows

| bead | subsystem | brainyard | design docs | size |
|---|---|---|---|---|
| `byj-gly.7` | ~~Context compaction / rebudget~~ **done for tiers 0 and 1** — a cap and deterministic elision to spill files, measured at -57% of raw at a 32K window (RFC-014); tier 2 summarisation deferred with its A/B (`byj-gly.34`) | `common/context_compaction.clj`, `core/context_budget.clj` | `design/compaction.md`, `reference/CONTEXT-MANAGEMENT.md` | done |
| `byj-gly.9` | Session persistence / resume — **done** (RFC-016) | `components/agent-tui-persist`, `core/session.clj` | `tui/persistence.md`, `tui/pause-resume.md` | M |
| `byj-gly.10` | ~~Programs: eval + compile~~ **done** — datasets, measurement with its spread, two optimizers, proposals a person accepts (RFC-021); search deferred with brainyard's null result (`byj-gly.57`) | `common/{programs,predictions,predictor_agent}.clj`, `clj-llm/core/{evaluate,optimize}.clj` | `design/dspy-programming-model-proposal.md`, `design/predictor-agent-design.md` | M |
| `byj-gly.23` | ~~Predictor `:lm` / `:tier`~~ **done** — the resolver and the tier table (RFC-005) | `clj-llm/core/predictor.clj` resolve-lm, `core/config.clj` resolve-tier-lm | — | S |
| `byj-gly.12` | ~~Answer eval / refine gate~~ **done, and shipped off** — brainyard never measured its own gate, so the default is a decision here rather than an inheritance (RFC-003) | `common/{evaluation,eval_agent}.clj` | `design/eval-agent-design.md` | done |
| `byj-gly.18` | ~~Trajectory / telemetry log~~ **done** — one record per turn (RFC-025); export `byj-gly.77`, analytics `byj-gly.78`, cost `byj-gly.79` | `components/{mulog,analytics}`, `common/trajectory.clj` | `design/observability.md` | M |

### Wave 3 — needs the effect brick (`byj-gly.26`)

| bead | subsystem | brainyard | design docs | size |
|---|---|---|---|---|
| `byj-gly.8` | ~~Streaming (SSE)~~ **done for the CLI provider** — jolt's http client buffers a response, so SSE is out; `stream-json` over a subprocess is in (RFC-006) | `clj-llm/core/{sse,sse_flow,http_flow}.clj` | `session-channel.md` | M |
| `byj-gly.11` | ~~CoAct code channel~~ **done, differently** — the channel is not ported; `tool$run` composes tools in one-off Tool Lisp instead, measured against multi-step tasks (RFC-010) | `components/clj-sandbox`, `common/code_eval.clj` | `reference/SANDBOX.md`, `design/dual-action-channel-discussion.md` | done |
| `byj-gly.13` | ~~Background tasks~~ **done** — `task$run` and auto-detach (RFC-019) | `agent/task/*`, `core/tool.clj` fast-eval | `core/task.md`, `specs/task-manager.md` | M–L |
| `byj-gly.14` | ~~MCP client~~ **done for stdio** — a server's tools become `mcp$<server>$<tool>` (RFC-024); HTTP/OAuth not ported, reachable through `mcp-remote` | `agent/mcp/*` | `design/mcp-agent-design.md` | M |
| `byj-gly.16` | ~~Subagents / roster~~ **done** — agents as data, one-shot delegation, three guards (RFC-018) | `common/{agent_roster,user_agents}.clj`, `core/tool.clj` | `design/agent-lifecycle-management.md` | M |

### Wave 4 — larger surfaces

| bead | subsystem | brainyard | design docs | size |
|---|---|---|---|---|
| `byj-gly.15` | ~~Skills~~ **done for loading** (RFC-017) — four roots, most local first; listed one line each, because rendering 48 as tool specs cost 14.5k tokens of every session. Writing (`skills$write`) and self-improvement — distillation, refinement, staged proposals — ported by `byj-gly.246` | `common/{skills,skill_agent,skill_distill,skill_refine}.clj` | `design/skills.md`, `design/self-improve-design.md` | done |
| `byj-gly.17` | ~~Todo / plan / exec~~ **done as RFC-023** — the chain is a workflow tree, so no exec-agent and no todo-agent; the only new mechanism is a checklist that outlives its turn | `common/{todo,plan,exec,workflow}.clj` | `design/{todo,plan,exec}-agent-design.md` | done |
| `byj-gly.19` | ~~Memory L1/L2/L3~~ **done, differently** — an external store behind three commands (RFC-020); the subsystem is not ported | `components/memory` | `core/memory.md`, `design/memory-agent-design.md` | L |
| `byj-gly.20` | ~~TUI~~ **done for Mode A** — a glimmer component tree supplying RFC-027's seams, and what `jolt run:byj` runs wherever a terminal allows (`--inline` opts out). Live blocks, an input queue, command completion and markdown are in (`byj-gly.100`); no tmux Mode B (`byj-gly.98`) | `bases/agent-tui` | `tui/architecture.md`, `specs/tui.md` | L |
| `byj-gly.21` | Bedrock — **done** (RFC-006); no `cachePoint` on the Converse path (`byj-gly.37`) | `clj-llm/core/bedrock.clj` | `design/login-auth-methods.md` | done |
| `byj-gly.22` | ~~Web share~~ **done** — `run --web` and `--web-tmux` through ttyd, brainyard's flags, auth always on (RFC-027) | `components/web-share` | `web-sharing.md` | done |
| `byj-gly.290` | ~~Delegating to Claude Code~~ **done, differently** — `agent$claude` drives an interactive Claude Code in tmux, in auto mode, on the agent page, its work handed back with what to publish, or published by the session when the task says so (RFC-039; divergence `claude-agent-in-tmux`) | `common/acp_agent.clj` (`:claude-code`) | `design/acp-agent-management.md` | done |
| `byj-gly.309` | ~~`/init`~~ **done, differently** — the project made ready for brainyard: tools checked with install hints, AGENTS.md and CLAUDE.md, beads by bd, AGENTS.md in the prompt (`:reference-artifact-paths`); differs from brainyard's BRAINYARD.md `/init` (RFC-040) | `common/init_agent.clj`, `common/init.clj`, `agent-tui/bootstrap.clj`, `components/env-detect`, `core/config.clj` (`reference-artifact-descriptors`) | `design/init-agent-design.md`, `design/bootstrapping-design.md` | done |

| `byj-gly.348` | ~~A2A~~ **not byj's** — A2A is spoken by a program outside byj, which serves byj's agents by asking a headless session over its channel (RFC-031) for each A2A context and hands remote agents' skills to byj as an MCP server's tools (RFC-024); nothing in the kernel (RFC-070) | `components/a2a`, `a2a-server`, `a2a-client`, `common/a2a_serve.clj`, `common/a2a.clj` | `design/a2a-design.md` | done |

Not scheduled: ACP remote agents (brainyard's `acp-agent`, byj driving
another agent over ACP), RAG/research/explore agents, chat gateways (Slack,
Telegram), OAuth login. File a bead before starting any. The other direction
of ACP — byj as an ACP agent, for an application's SDK or an editor — is not
brainyard's and is the port's own: RFC-053, epic `byj-g5n` (divergence
`an-agent-sdk`).

## Invariants

| invariant | enforced by |
|---|---|
| Every row above has a bead, and every bead under `byj-gly` has a row. | Not enforced. Check with `bd children byj-gly` when editing this RFC. |
| An intentional difference from brainyard is recorded. | `divergences-test` checks the record is consistent; nothing detects an unrecorded one. |
