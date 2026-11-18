# RFC-075 — Project dependencies

**Status:** implemented 2026-11-18 (`byj-9dv`): `byj deps --json` and
`byj deps write`, for a client's dependencies tab. byj's own; brainyard has
no counterpart.

## Purpose

A project depends on others — it uses what another builds, or what another
changes can break it — and work in it passes between sessions that play
different parts: one decides, another builds on what was decided. A client
that shows this as a graph, edits it, checks it and drafts the handoffs
needs it as data, and needs every rule about it to live in one place.

byj owns the two files that say it, as it owns `config.edn` (RFC-013
"From outside a session: `byj config`"): it reads them, checks them and
writes them, and a client reads byj's answer and hands byj a whole new
file, never writing EDN itself.

## Scope

**This layer decides** the shape of the two files; how a project they name
is found on this machine; whether what it provides is there; which of its
commits since it was last reviewed touch what it provides; which sessions
play each role; whether each thing a handoff gives is ready; and what may
be written.

**It must not know** what a session does with a handoff, or that it has a
role: no session is told its role, and nothing here starts, stops or
prompts one. Nothing of it is offered over ACP.

**It hands** `byj deps --json` its report, and `byj deps write` its answer.

## Model

Two files under the project's `.brainyard/`:

`deps.edn`, the spec, committed:

```clojure
{:schema-version 1
 :projects {:core {:name "core"
                   :git "git@example.com:o/core.git"     ; optional
                   :path "../core"                       ; optional, relative to this project
                   :kind :upstream                       ; :upstream | :reference
                   :provides [{:id :cli :kind :binary :command "core" :version ">= 0.3.19"}
                              {:id :api :kind :files :paths ["contract/api.json"]}]
                   :affects ["src/client.clj"]           ; paths here; information only
                   :seen "91fe655"}}                     ; its commit last reviewed; optional
 :roles {:design {:title "Design" :does "decisions and beads"}   ; each key optional
         :implement {:worktree true}}
 :handoffs [{:from :design :to :implement
             :gives [{:kind :file :paths ["docs/decisions/*.md"]}   ; globs
                     {:kind :bead :id "x-1"}                        ; or {:kind :bead :parent "x"}
                     {:kind :branch}
                     {:kind :note}]}]}
```

Ids are keywords. `self` names this project in a graph, so no project may
take it; a provide's `:id` is unique within its project.

`deps-local.edn`, this machine's, not committed:

```clojure
{:bindings {:design ["<session id>" …]}
 :handed [{:from :design :to :implement :session "<receiving session id>" :turn 3 :at #inst "…"}]}
```

### Reading

Lenient. What does not fit the schema is dropped, a key at a time — an
unknown key, a value of the wrong type, an item missing what it needs, a
project named `self`, a second provide of one id — and said as a problem
`{file key message}`, `key` its place (`projects.core.provides.0`). An
unreadable file reads as `{}` and one problem. An absent file is `{}`.

### A project on this machine

By its `:path` when that is a directory (`resolvedBy` `path`); else the
registry's project (RFC-074) whose origin is its `:git`, two spellings of
one repository being one (`git@host:o/r.git`, `https://host/o/r`;
`registry`); else nowhere. Its origin and head are git's. A `:files`
provide is there when every path is; a `:binary` when its command is on
PATH and, given a `:version`, its `--version`'s first version meets it —
`>=`, `>`, `=`, `<`, `<=` on semver, several joined by commas, a bare
version `=`. Its `changes` are its commits after `:seen` that touch a
`:files` path, newest first, 50 at most; none without `:seen`.

### Roles and handoffs

A role's sessions are those bound to it in `deps-local.edn`, looked for in
the project and in each worktree of its repository, where a session opened
in a worktree keeps itself. A give is ready when:

- `:file`: a file matches (`*` within a directory, `**` across them) and a
  turn of a session bound to `:from` changed one, as that turn is kept
  (RFC-069, `changes/rows`);
- `:bead`: the bead, or every child of `:parent`, is closed, as bd says;
  not ready, and said, where there is no bd;
- `:branch`: a `:from` session's worktree branch has a commit its base has
  not;
- `:note`: a `:from` session has finished a turn.

A handoff is ready when every give is. It was handed when `:handed` holds
an entry for its pair; the latest is the one shown.

### Checks

| Level | Kind | When |
|---|---|---|
| error | `cycle` | the handoffs, or the projects through each one's own `deps.edn`, go round; the message names the cycle |
| error | `dangling` | a handoff names a role not in `:roles` |
| error | `project-missing` | no directory by `:path` or the registry |
| warn | `remote-mismatch` | the directory's origin is not `:git` |
| error | `provide-missing` | a `:files` path is not there, or a `:binary` not on PATH |
| error | `version` | a `:binary`'s `--version` fails its constraint |
| warn | `upstream-changed` | `changes` is not empty, or git cannot list them |
| info | `never-reviewed` | no `:seen` |
| warn | `unbound-role` | no session bound to a role |
| warn | `session-gone` | a bound session no longer exists |
| wait | `not-ready` | a give is not ready |
| info | `handoff-ready` | a handoff is ready and not handed |
| warn | `started-early` | a `:to` session took turns while the handoff was not handed, or before it was: the receiving session's turns before the one it was handed in, another's kept turns that ended before `:at` |

### Writing

`byj deps write` takes the whole new map as JSON — the shape `spec` or
`local` has in the report — and coerces it: ids and `:kind`s to keywords,
`at` to an instant, a spec's missing `schema-version` to 1. It refuses any
problem the reading would have dropped (stage `schema`), and a spec with a
cycle or a dangling role (stage `check`); snapshots the old file under
`.brainyard/deps-snapshots/` (the newest 20 of each kept); writes the new
one through a file beside it and a rename, every map in a stable order —
the keys the file knows in their order, ids by name — so the same map is
always the same text; and reads it back, putting the old one back when it
does not read as written (stage `verify`).

## API

`brainyard.deps`:

- `spec-file [dir]`, `local-file [dir]`.
- `read-spec [dir]`, `read-local [dir]` — `{:value :problems :exists}`.
- `clean-spec [m]`, `clean-local [m]` — `{:value :problems}`.
- `resolve-project [dir p registry]` — `{:dir :resolved-by}` or `{}`.
- `version-ok? [text constraint]`, `git-key [url]`, `path-glob->re [glob]`, `find-cycle [edges]`.
- `structural-errors [dir spec registry]` — the cycles and dangling roles.
- `report [dir opts]` — `byj deps --json`'s map. opts: `:registry`,
  `:path-env`, `:bd-fn`.
- `write! [dir which m opts]` — `which` `:spec` or `:local`; `{:ok :file
  :snapshot}` or `{:error :stage}` (`input`, `schema`, `check`,
  `snapshot`, `write`, `verify`), with `:problems` for `schema`.

The CLI:

```
byj deps [-C DIR] [--json]                    the report; without --json, a summary
byj deps write [--local] [-C DIR] [--json] < NEW.json
```

`--json`'s report:

```
{files {spec {path exists} local {path exists}}
 spec  <deps.edn as JSON; {} when absent>
 local <deps-local.edn as JSON; {} when absent>
 projects [{id kind dir resolvedBy gitRemote head
            provides [{id kind ok detail}]
            changes [{commit subject at paths}]}]
 roles [{id sessions [{id exists title turnCount updatedAt}]}]
 handoffs [{from to gives [{kind ready detail items}] ready handed {session turn at}|null}]
 checks [{level kind target {project}|{role}|{handoff [from to]} message}]
 problems [{file key message}]}
```

The two files' keys are named as the EDN names them, kebab-case, keywords
as strings without the colon; what is computed is camelCase, as in `byj
config --json`. Instants are ISO-8601. `byj deps` exits 0 whatever the
checks say; `write` exits 1 when it refuses, `{error stage}` its answer.
The shape is byj's contract, `contract/deps/deps.json` (RFC-070).

## Invariants

| Invariant | Enforced by |
|---|---|
| A project is found by its path or the registry's origin; its provides, commits since `:seen`, roles' sessions (a worktree's among them), gives and checks are as the model says | `deps-test/a-projects-dependencies-are-read-resolved-and-checked` |
| What does not fit is dropped and said, a key at a time; an unreadable file is `{}` and a problem | `deps-test/what-does-not-fit-is-dropped-and-said` |
| A write is held to the schema, refuses a cycle (a handoff's, or through another project's spec) and a dangling role, keeps the old file as a snapshot, and writes a stable order | `deps-test/a-write-is-held-to-the-schema-refuses-a-cycle-and-keeps-the-old-file` |
| `byj deps --json` and `byj deps write` give and take JSON, and exit 1 on a refusal | `main-test/byj-deps-gives-the-dependencies-as-data-and-writes-them-whole` |
| The report's shape is byj's contract | `contract-test/the-dependencies-are-byjs-contract` |

## Known gaps

- `/init` writes `.brainyard/*` and `!.brainyard/profile.edn` to
  `.gitignore`, so the spec, meant to be committed, is ignored until a
  `!.brainyard/deps.edn` line is added by hand (`byj-bo8`).
