# RFC-013 — Configuration

**Status:** implemented (`byj-gly.6`; project root `byj-gly.25`).

## Purpose

Specifies where a run's settings come from: one declared schema of the keys the
kernel reads, and one resolver over the layers that can set them — so a project
can fix a policy once instead of repeating flags, and every key can say where
its value came from. brainyard sources: `agent/core/config.clj` (schema,
`resolve-config`, `get-config`), `core/feature.clj` (features as a view over
the schema), `core/env_files.clj` and the dotenv loader; docs `core/config.md`,
`specs/configuration.md`.

## Scope

**This layer decides** what a key means, what it defaults to, and which layer
wins.

**It must not know** what any key does — the kernel reads the map it produces.

**It hands** `{:config :sources :problems :files}` to the CLI, which passes
`:config` to `agent/ask` (RFC-003) unchanged.

## Model

### The schema

`brainyard.config/config-items` is a flat map, `key → {:type :default :doc :family?}`,
covering every key the kernel reads (25 today). `config/defaults` is what
`agent/default-config` now *is*, so a key cannot be read without being
declared. `:type` is one of `integer number boolean keyword string vector set
object`; it drives coercion of strings and validation of file values, as
brainyard's does. brainyard's schema is ~180 keys for a much larger runtime;
the shape is the same.

### The layers

```
schema default  <  ~/.brainyard/config.edn  <  <project-dir>/.brainyard/config.edn
                <  caller (CLI flags, or :config passed to agent/ask)  <  BY_* environment
```

`load-config` returns the merged map, and `:sources` naming the layer each key
came from (`--print-config` prints both). Environment beats everything, as in
brainyard: a set variable wins over any persisted or in-memory layer.

brainyard reads **one** file — the project's if it exists, else the user's.
Here the user's file is the floor and the project's is merged over it
(divergence `config-files-merge`), so a machine-wide default survives a project
that sets one key.

An empty or blank environment variable is not a layer: it falls through, as
brainyard's `resolve-var` does with `:blank-as-unset?`.

### The project root

`:project-dir` is where `.brainyard/` lives — config.edn, tools, hooks,
predictors and programs — and the default `:allowed-dirs` root. Unset, it is
brainyard's walk: `BY_PROJECT_DIR`, else the nearest directory at or above the
working directory holding `.git`, else the working directory. So an agent
started in `src/deep/` still uses the project's settings and may read the whole
project. A worktree's `.git` file counts as a root here, where brainyard
requires a directory (divergence `project-root-git-walk`).

### The file

brainyard's shape, so the same file serves both:

```clojure
{:agent {:config {:max-iterations 8 :tool-run? false}}
 :permissions {:mode :ask-each-time}}
```

Keys live under `[:agent :config]`; `[:permissions :mode]` and
`[:permissions :allowed-dirs]` are bridged onto the flat `:permission-mode` and
`:allowed-dirs` keys. A persisted `false` shadows a `true` default, because the
layers merge on key presence, not truthiness.

**A bad file never stops a run** (divergence `config-file-never-throws`).
Unreadable EDN, a schema key at the top level (brainyard's
`::misplaced-config-keys`), an unknown key and a value of the wrong type are
each reported as a problem — printed by the CLI, returned by `load-config` —
and skipped. brainyard throws on unreadable EDN.

**Another program's keys are not unknown** (`byj-ahf.21`, divergence
`other-programs-declare-config-keys`). A program that keeps its own keys in the
same `[:agent :config]` declares them in
`~/.brainyard/config-keys/<program>.edn`, `{:program "…" :keys [:k …]}`, and
byj stops calling them unknown (`declared-keys`); it neither reads nor checks
them, and an unreadable declaration declares nothing. A program installs its
own declaration when it is set up, so the keys a person sets for it draw no
warning; byj names none of them.

### The environment

A key's variable is derived from its name: `BY_` + `SCREAMING_SNAKE`, with a
trailing `?` dropped — `:tool-run?` → `BY_TOOL_RUN`,
`:tool-deny-effects` → `BY_TOOL_DENY_EFFECTS`. Values are coerced by the
declared type (`"process,fs/write"` → `#{:process :fs/write}`). brainyard
writes one `:env-fn` per key by hand and derives nothing; the convention it
follows is exactly this rule (divergence `config-env-from-type`).

The CLI's `env-fn` reads `.env` files first (nearest up from the working
directory, then `~/.brainyard/.env`), so `.env` sets config keys as well as
provider credentials.

### Per-entry keys

A key whose item says `:merge :entries` is merged entry by entry across the
layers, each entry's own keys over the one below (`config/merge-layer`).
Entry names are strings, whichever form a file wrote them in. `:mcp-servers`
is the one such key (byj-gly.220). A project file's entry changes only what it
names. The `/mcp` commands edit only the project's own entries
(`session/own-servers`): add, enable/disable (just `:enabled`), and remove. A
server that lives in the user file is removed with `--user`, or disabled for
the project (`mcp-test/slash-mcp-edits-only-the-projects-own-servers`).
`:hidden-tools` stays a flat list: `/mcp hide|show` starts from the project's
own list, else the user's, and never from a flag or the environment.

### Showing a value

A config value is only ever shown through `configbox/shown`: an MCP server's
`:env` values are `••••` whatever they look like, and any other secret-shaped
string is redacted. That covers the "config changed" notice (which, for
`:mcp-servers`, names the servers added, removed or changed — `+a −b ~c` —
rather than printing their specs), `config$read`'s `:value`/`:default`,
`config$reload`'s `:from`/`:to`, and `config$set`'s `:was`/`:now`. The notice
printed a user file's token into the transcript and the session snapshot, and
the commands handed it to the model (byj-gly.235,
`configbox-test/a-config-value-is-shown-masked`).

### Named values

A key may name the values it takes (`:enum` in its schema entry, e.g.
`:output-style` → `[:default :concise :explanatory]`); `valid?` refuses any
other, so a write of one is refused at the schema step, and `/config`'s editor
steps through them on Enter rather than opening the value for editing.

A key's `:type` and `:enum` are compiled to a malli schema (`key-schema`,
byj-gly.204). `valid?` checks against it, and `value-problem` says what is
wrong in one line, e.g. `:output-style should be either :default, :concise or
:explanatory`. A config file's bad value is reported that way and so is a
refused `config$set`, where each used to report only the type it wanted
(`config-test/the-file-is-brainyards-shape`).

### Features

**Every key has a family** — `config/families`, in the order `/config` shows
them: loop, context, provider, session, tools, tasks, agents, memory, hooks,
permissions, logs, paths. `config/features` is a view over the schema, never a
second store: every boolean key is a feature, `family/name` → `{:gate :family
:doc}`, and a family's other keys are its knobs, as brainyard's `/feature`
detail lists them. `feature-on?` reads a gate from a config map (a number gates
on `pos?`, as brainyard's numeric gates do). brainyard adds family master
switches, lifecycles (`:startup`/`:live`), `:requires`/`:implies` closure and
profiles; none of that is ported. `/config` is where a person sees and flips
them (RFC-004), brainyard's `/config` and `/feature` in one.

## Writing it

`brainyard.config` reads; `brainyard.configbox` writes one key at a time —
brainyard's `agent/common/config.clj` in the small. A write is a transaction, in
brainyard's order:

    writable? → schema → secret scan → mtime → snapshot → write → re-read → revert on failure

### What may not be written is the point

| refused | why |
|---|---|
| anything in `:family :permissions` | A config file outlives the session. An agent that can widen its own gate widens it for every later run. |
| `:builtin-hooks?` | It installs the gates; turning it off uninstalls them. |
| `:working-dir`, `:project-dir`, `:session-id`, `:resume` | They describe one run. Persisting one gives two answers to where this run is. |
| an undeclared key | `config$read` lists what is. |

**No tool can write a permission key.** brainyard's allowlist *includes*
`[:permissions :mode]` and `[:permissions :allowed-dirs]` and merely stops them
self-confirming under `--auto` (divergence `config-never-writes-permissions`).
`config$read` says which keys those are and why, so the agent can explain a
change rather than make it.

There is exactly one route by which a permission key is persisted, and it is
not a tool: `configbox/grant!`, called only by the terminal prompt's `r`
answer (RFC-012, `byj-gly.83`). It takes `:by :person`, accepts only
`#{:tool-allow-tools :allowed-dirs :permission-mode}`, and appends to the
allowlists rather than replacing them. `configbox-test/no-command-can-ask-as-a-person`
reads the command table and fails if a command mentions `:person` or calls
`grant!` — the distinction between a person answering and the agent asking on
a person's behalf is only worth anything while nothing else can claim it.

Beyond the refusals: the value is coerced by its declared type and validated;
a secret-shaped value is refused with the match **masked to 8 characters**,
because echoing it would put it in the transcript and the event log; an
`:expected-mtime` that no longer matches refuses rather than clobbering another
process's write; a snapshot is taken first (retention 20) and the file is
re-read after, and a write that does not read back as written is put straight
back.

### Scope

`:project` is `<project-dir>/.brainyard/config.edn`, `:user` is
`~/.brainyard/config.edn`, and **`:auto` is the project whenever there is one**.
The user's file is written only when named, because a project write affects this
repository and a user write affects every project on the machine
(divergence `config-auto-scope-is-the-project` — this is where the port had a
real defect, found by it happening).

### An edit is in force from the next turn

There is no cached global to swap: `make-ctx` builds the registry, zones and
hooks from config. So `session/refresh-config!` reloads the layers (main's
`:reload-config`) at the start of every turn and every slash command, compares
the load with the previous LOAD — never with the ctx's config, whose derived
keys (`:allowed-dirs`, `:predictor-roots`) would read as changed every time —
and, for the keys that changed:

- a key in `config/next-session-keys` (read only while the session is put
  together: identity, mouse and suggestions, the event log, pruning, the
  scrollback window, prompt caching, `:max-turns`) is reported once per value
  as applying from the next session. So is every key of the **permissions
  family** (`config/applies-next-session?`). `config.edn` sits in the working
  directory and a file tool can write it, so a mode or an allow-list the agent
  wrote must not govern the run that wrote it. A person changes the mode mid-run
  with shift+tab (byj-gly.226,
  `session-test/an-edited-permission-key-waits-for-the-next-session`);
- `:mcp-servers` is applied to the run's servers first by `mcp/reconcile!`,
  which compares the config with what the box runs, not with what this session
  last saw. Sessions sharing the box do not each restart what another just
  started, and a server that failed is tried again once its spec changes
  (RFC-024, byj-gly.223/.224). The tools built next are the ones running;
- any other key is applied: `make-ctx` runs again on the merged config, keeping
  the session-lived registries (tasks, subagents, wakeups, MCP clients), and
  its `:config :tools :hooks :zones :base-tools` go into the ctx-box that every
  leaf reads through (RFC-002). One info line names what changed, with the old
  and new value; `:config/applied` is logged.

A turn that is running keeps the config it started with. `config$reload`
still returns the diagnostic — `:changed` against `:shadowed`, the latter being
keys edited in the file that a flag or the environment still outranks
(divergence `config-reload-reports-rather-than-applies`).

Enforced by `session-test/an-edited-config-applies-from-the-next-turn` and
`session-test/a-config-edit-rebuilds-what-the-next-turn-is-shown`.

## API

`brainyard.config`: `config-items`, `config-keys`, `next-session-keys`, `defaults`, `coerce`, `valid?`,
`config-file`, `read-file`, `env-layer`, `load-config`, `describe`, `features`,
`feature-on?`.

`brainyard.configbox`: `unwritable`, `writable?`, `writable-keys`, `grantable`,
`grant!`,
`secret-patterns`, `secrets-in`, `scope-file`, `read-scope`, `snapshots`,
`take-snapshot!`, `set-key!`, `revert!`, `reload-report`, `commands`.

`config$read` reports each key's **effective value, its `:default`, and
`:changed?`** — the effective value alone is not enough to advise an undo. A run
launched with `-n 12` read the flag's value as the default and told the person
so (`byj-gly.86`, found by running this agent on this repository).

Commands: `config$read`, `config$reload`, `config$snapshots` always;
`config$set`, `config$unset`, `config$revert` when `:config-write?` (default
true). The writes carry `:effects #{:fs/write :config/write}`, so
`--ask-effects config/write` puts a person in front of them through the gate
that already exists (RFC-012) rather than a second confirm parameter.

CLI: `--print-config` prints every key, its value and its layer, then exits.
Flags that set config keys (`-C`, `-n`, `--ask`, `--allow`, `--deny`,
`--ask-effects`, `--deny-effects`, `--permission-mode`) are the caller layer.

### Directories are absolute

`:working-dir` and `:project-dir` are canonical absolute paths from the moment
config is resolved — `config/canonical-dir`. They are not only compared (the
permission gate's `under?`, the pruner's `delete-tree!`, both of which a `/.`
tail defeats); they are **stored**, and a session snapshot that said
`:working-dir "."` restored to wherever the next process happened to start
(RFC-016). A directory that does not exist yet falls back to absolute-but-not-
canonical, which is still restorable.

`brainyard.persist` canonicalises again as it writes, so a caller that assembles
a config map by hand cannot store a relative path either.

## Invariants

| invariant | enforced by |
|---|---|
| A key another program declares under `~/.brainyard/config-keys` draws no "unknown" problem and is not read; an unreadable declaration declares nothing | `config-test/a-key-another-program-declares-is-not-called-unknown` |
| `:working-dir` and `:project-dir` are absolute and canonical wherever config is resolved, and whatever is written to a snapshot is absolute. | `persist-test/a-stored-directory-is-absolute` |
| Every key the kernel reads is declared with a type and a doc; `agent/default-config` is the schema's defaults; values are validated against their type. | `config-test/the-schema-declares-what-the-kernel-reads` |
| Strings are coerced by declared type, and an unparseable value is left alone. | `config-test/strings-are-coerced-by-type` |
| default < user < project < caller < env, and a blank variable is not a layer. | `config-test/layers` |
| The file is brainyard's shape, permissions are bridged, a persisted `false` shadows a `true` default; misplaced, unknown, wrong-typed and unreadable files are problems, not exceptions. | `config-test/the-file-is-brainyards-shape` |
| A key's variable is `BY_` + its name, coerced by type. | `config-test/environment` |
| The project root is the git root above the working dir (a worktree file counts), an explicit dir wins, and the config file, `.brainyard` roots and allowed dirs follow it. | `config-test/the-project-root-is-the-git-root` |
| Features are a view over the schema; an unknown feature is off; every key has a family, the families listed are the ones used, and every switch is a feature. | `config-test/features-are-a-view-over-the-schema` |
| A project's config.edn reaches a turn: it can switch a command off and set the iteration ceiling. | `config-test/a-project-config-file-reaches-the-loop` |
| **No tool writes a permission key**, and neither is `:builtin-hooks?` or a per-run key written. | `configbox-test/a-permission-key-is-never-written-whoever-asks`, `configbox-test/the-hooks-that-install-the-gates-are-not-writable-either`, `configbox-test/a-key-that-describes-this-run-is-not-a-setting` |
| `config$read` reports the default beside the effective value, and marks what differs. | `configbox-test/the-commands-say-where-a-setting-came-from` |
| The prompt's `r` grants three keys and no more, and no command may ask as a person. | `configbox-test/a-person-at-the-prompt-may-grant-what-the-agent-may-not`, `configbox-test/no-command-can-ask-as-a-person` |
| **`:auto` writes the project; the user's file only when named.** | `configbox-test/auto-is-the-project-never-the-users-own-config` |
| A wrong type, an unknown key and a secret-shaped value are refused, the secret masked. | `configbox-test/a-value-of-the-wrong-type-is-refused`, `configbox-test/an-unknown-key-is-refused-rather-than-written`, `configbox-test/a-secret-shaped-value-is-refused-and-not-echoed` |
| A write preserves the rest of the file, leaves no temp file, and can be undone. | `configbox-test/the-rest-of-the-file-survives-a-write`, `configbox-test/a-write-leaves-no-half-written-file`, `configbox-test/a-write-can-be-undone` |
| A file changed underneath is not clobbered. | `configbox-test/a-file-that-changed-underneath-is-not-overwritten` |
| Writing can be switched off without losing reading. | `configbox-test/writing-can-be-switched-off-without-losing-reading` |

## One schema, not several

Every key the kernel reads is declared here — **including a loop's own**. A tree
reads config as `:config/<key>` from the merged map
(`resources/brainyard/loops/session.edn` reads `:config/max-turns`), so a key a
loop introduces is a config key by this RFC's definition and belongs in the
schema.

It was not always so, and what a second map cost is worth recording, because
every one of these failures was silent (`byj-gly.64`, found by RFC-022's
checker):

- `:max-turns`, `:full-depth`, `:max-question-chars` and `:max-answer-chars`
  lived in `session/default-config`. They had **no `BY_*` name**, **no
  `--print-config` row**, and a `config.edn` that set one was told the key was
  **unknown** — while the session ran on a value from the other map.
- `:max-previous-turns` was in both, with **different defaults** (10 and 100),
  so the effective value depended on how the caller got there.
- A static checker could not tell such a key from a typo, which is what
  surfaced it.

`session/default-config` is now `(select-keys config/defaults …)` — the schema's
view of those keys, not a second source. `agent/default-config` has always been
`config/defaults`.

## Known gaps

- **Nothing writes config.** brainyard has `config$apply` (snapshot, validate,
  write, smoke-test, revert) and `agent-runtime$config` for the model, plus
  `feature$set` — `byj-gly.33`.
- **No per-agent `.env`, and deliberately so** (`byj-gly.44`, decided).
  brainyard's `core/env_files.clj` has two consumers and this port has neither:
  `env-policy`, which merges an agent's file into the environment of child
  PROCESSES it spawns — there is no `:env-vars`/`:env-allow`/`:env-deny` here,
  the bash tool sets no `:env`, and MCP's `:env` is per server and resolved in
  `main` before any agent exists (RFC-024) — and `resolve-for`, whose only
  callers there are a telegram token and some email commands.

  It is also not what it looks like: a per-agent `.env` does **not** supply an
  agent's API key even in brainyard, whose design rejects the overlay because
  it would let an agent's file outrank that same agent's config, inverting the
  precedence documented here. An agent overrides any config key and its `:lm`
  (RFC-018); carrying its own *credential* is answered by resolving one **by
  provider, from this run's environment** (`byj-gly.90`, `byj-gly.91`) — an
  agent needs no file of its own to use a provider its caller is not using, and
  cannot hold a key nobody else on the machine has.
- No feature profiles (`:minimal`, `:full`), family master switches,
  lifecycles or `requires`/`implies` closure; the port's features are flat.
- No project registry (`core/projects.clj`): `~/.brainyard/projects/…` with
  slugs and an index.
- No session layer: a session has one agent and one config, re-read at each
  turn's start rather than swapped mid-turn.
