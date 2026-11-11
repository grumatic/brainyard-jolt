# RFC-001 — Kernel and userspace

**Status:** implemented.

## Purpose

Specifies the one seam everything else hangs from: `src/` provides mechanism,
and everything about *what the agent does* is data that can change without a
rebuild — loop trees, predictor specs, prompts, and per-project params.

This is samizdat's standing rule, applied to brainyard. brainyard expresses its
loop as a behavior tree built by Clojure functions (`coact-behavior-tree`); here
the same tree is an EDN resource, and the functions it names are the only code.

## Scope

**This layer decides** where behaviour lives and how it is found at runtime.

**It must not know** what any particular loop, predictor or prompt says.

**It hands** resource names and file roots to the tree loader (RFC-002) and the
predictor loader (RFC-005).

### The two halves

| kernel — `src/brainyard/` | userspace — data |
|---|---|
| `effect` — tasks, policy, supervision on ebb (RFC-009) | |
| `bt` — tree interpreter | `resources/brainyard/loops/*.edn` |
| `agent`, `session` — leaf fns | the trees that wire them |
| `predictor`, `signature` — prompt assembly | `resources/brainyard/predictors/**.edn` |
| `llm` — providers | `resources/brainyard/prompts/*.md` |
| `config` — the key schema and its layers (RFC-013) | `~/.brainyard/config.edn`, `<working-dir>/.brainyard/config.edn`, `BY_*` |
| `permission`, `sandbox` — the tool gate and seatbelt (RFC-012) | `resources/brainyard/hooks/tool-*-gate.edn`; `--ask`/`--allow`/`--deny` globs and effect sets |
| `hooks`, `hookbox` — the hook bus (RFC-011) | `resources/brainyard/hooks/*.edn` built-in hooks; `.brainyard/hooks/<id>.edn` + `.clj` user hooks |
| `tools`, `discovery` — primitive capabilities | `resources/brainyard/tools/*.edn` primitive specs |
| `tool-lisp`, `toolbox` — user tools (RFC-010) | `.brainyard/tools/<name>.edn` + `.clj` user tools; `.brainyard/programs/<id>.edn` params, `.brainyard/predictors/user/*.edn` |
| `main` — CLI; `cli-help` lays out its help | a `--loop FILE` tree, a `--params FILE` record; `resources/brainyard/cli/help.edn`, the help's words in brainyard `by`'s layout: the commands, a command's subcommands or options, one subcommand |

**The test for which half a change belongs in:** *could the agent's behaviour
change this way without a rebuild?* If it is behaviour and the answer is no, it
is in the wrong place. A leaf fn is mechanism — it does one named step — and a
decision about *when* that step runs belongs in the tree.

## Model

```
resources/ (classpath; baked into the binary by :jolt/build :embed)
   brainyard/loops/*.edn ───────── agent/load-tree ─ bt/expand ─ bt/validate ─ bt/tick
   brainyard/predictors/<id>.edn ─ predictor/load-predictor ─ predictor/run
   brainyard/prompts/*.md ──────── the words a predictor or an agent reads
<working-dir>/.brainyard/ then ~/.brainyard/
   predictors/user/<name>.edn ──── user predictors (user/ ids only)
   programs/<id>.edn ───────────── params (first layer with a record wins whole)
```

**Resolution order for a tree name**: a classpath resource first, then a file
path (`agent/resolve-tree`). For a predictor id: the bundled resource, and only
for `user/` ids a file under the predictor roots. For params: dynamic, then the
params roots in order, then the spec default (RFC-005).

**Why resources are embedded.** `jolt build` links code, not data; without
`:jolt/build {:embed ["resources"]}` a built `byj` resolved its trees
relative to the working directory and failed anywhere else (samizdat hit the
same wall). The binary built by `jolt build:byj` runs from any cwd.

**Why some namespaces are named in `:include`.** `jolt build -m brainyard.main`
bakes only the namespaces `brainyard.main` reaches by `:require`; one reached
only by `requiring-resolve` — as a namespace is when a static require would
be a cycle — is left out, and
the binary's lookup dies with "Could not locate … on the source roots" while
`jolt run` and the suite, which load from source, never see it (provenance
P-43). `:jolt/build {:include […]}` names each such namespace; none needs naming
today.

## API

| fn | contract |
|---|---|
| `(agent/resolve-tree name)` | Tree data for a resource name or file path. Throws naming both places when neither exists. |
| `(agent/load-tree name)` | `resolve-tree` then `bt/expand` with `resolve-tree` as the include loader. `nil` is the bundled ReAct loop. |
| `(agent/make-ctx opts)` | The tick context: `:config :lm :registry :tools :zones :params :on-event`. Fills `:predictor-roots` and `:params-roots` from `:working-dir` when absent. |
| `(predictor/default-roots dir sub)` | `[<dir>/.brainyard/<sub> ~/.brainyard/<sub>]`, project scope first. |

## Invariants

| invariant | enforced by |
|---|---|
| A tree is validated against the registry before it runs; an unregistered fn fails fast, naming every missing fn. | `agent/run-tree!`; `agent-test/bt-semantics`. |
| Replacing the tree and registry changes the loop with no change to `src/`. | `agent-test/loop-is-data`. |
| The bundled trees and predictors resolve from a built binary run outside the repo. | Not enforced by a test. Checked by hand: `target/byj --print-predictor react/think-act` from `/tmp`. |
| Every namespace `src` reaches by `requiring-resolve` is in the built binary: `brainyard.main` reaches it by `:require`, or `:jolt/build :include` names it. | `release-test/every-lazily-resolved-namespace-is-in-the-binary`. |
| A built-in predictor is never redefined from disk. | `predictor-test/loading`. |
| The CLI's help names every subcommand `main` dispatches, and no other. | `cli-help-test/every-dispatched-subcommand-has-help`. |
| Every flag a usage line or an option in the help names is one `parse-args` accepts. | `cli-help-test/every-flag-the-help-names-is-parsed`. |
| `-h` shows the page of the command it names, by its name or an old one, wherever options stand before it: the help reads the command `parse-args` parsed, not the raw words. | `main-test/the-help-is-the-commands-wherever-the-options-stand`. |
| An option `parse-args` does not know stops every command before it runs, with exit 2 and that command's help; only `-h` comes first. | `main-test/an-unknown-option-stops-every-command`. |
| `programs eval` and `compile` say a model, tier, dataset or split they cannot have in one line and exit 1, never as an unhandled exception. | `main-test/a-measurement-that-cannot-be-had-is-said-in-a-line`. |

## Known gaps

