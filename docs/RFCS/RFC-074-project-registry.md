# RFC-074 — Project registry

**Status:** implemented 2026-11-16 (`byj-k29`): brainyard's user-scope
project registry (`components/agent/.../core/projects.clj`, its
`docs/design/project-registry.md`) and its `by projects` command, ported so
that `by` and byj keep one registry on a machine that runs both.

## Purpose

A project's own state is in `<project>/.brainyard/`, which travels with the
repository; its sessions, a person's record of work in it, are in the
project's folder of the registry, `~/.brainyard/projects/<slug>/sessions/`
(RFC-016's second revision, `byj-84c`). What the *person* knows about their projects — which there are,
where each is, when each was last opened — has no place there, and
`~/.brainyard/` was flat. The registry gives each project a folder of its
own in user scope, `~/.brainyard/projects/<slug>/`, and `byj projects` lists
them, says where one is, adds one, and forgets one.

brainyard's `by` registers into the same folders. Unless byj names a project
exactly as `by` does and writes the same record, a person who runs both
would have two folders for one project and two lists that disagree; the
slug and the record are brainyard's, byte for byte.

## Scope

**This layer decides** a project's slug, what its record holds, when a
project is registered, and how a record is listed, read back and removed.

**It must not know** what a session does, or anything inside a project: a
registration reads the project's path and its git config's origin, and
nothing else.

**It hands** a list of records to `byj projects`, and a path for a slug;
and the records to `brainyard.deps`, which finds a project a spec names by
its origin — read again from the project's git config
(`read-git-remote`), a record's `:git-remote` being what its last
registration saw (RFC-075, `byj-339`).

## Model

```
~/.brainyard/projects/
  my-app-acd543c9/
    project.edn      ; the record: the truth for its slug -> path
  index.edn          ; slug -> path, rewritten from the records, never read as the truth
```

```clojure
{:path           "/srv/work/my-app"        ; canonical
 :slug           "my-app-acd543c9"
 :name           "my-app"
 :git-remote     "git@example.com:o/r.git" ; [remote "origin"] url, read from .git/config; nil when none
 :created-at     #inst "…"                 ; kept across registrations
 :last-opened-at #inst "…"                 ; stamped at each
 :listed?        false                     ; only on a record ensure-record! wrote; a registration drops it
 :worktree-of    "my-app-acd543c9"         ; a linked worktree's main checkout's slug (S4); none otherwise
 :schema-version 1}
```

- **The slug** is the directory's basename reduced to `[A-Za-z0-9._-]` (runs
  of anything else one `-`, no separator at either end, at most 48
  characters, `root` when nothing is left), then `-`, then the first 8 hex
  of SHA-256 over the canonical path's UTF-8. Readable in `ls`, without a
  space, the same for the same directory every time — so registering again is
  harmless — and different for two checkouts of one name.
- **slug -> path is read, never worked out**: the record names the path.
  A name made from a path by replacing its separators cannot be turned back
  into the path once a directory's name holds the separator.
- **The index is derived.** Every write rewrites it from the records, and
  nothing reads it as the truth. Each process writes only its own project's
  record, so two at once do not contend; a record is written through a file
  beside it and a rename, so a reader never sees half of one.
- **A record keeps what it does not know.** A newer `by`'s keys are merged
  through a registration, not dropped, and `:created-at` is kept.
- **One folder, one record** (RFC-016's second revision, S5). A folder a
  run keeps its sessions in has a record: one never registered — a flow, an
  evaluation, a loop evaluation's clone — is written `:listed? false`
  (`ensure-record!`), left out of `list-projects` unless asked for, and
  removed whole by the gone-project prune with its last session. A
  registration lists it.
- **Missing is listed, not dropped.** A record whose directory is not there
  now — deleted, moved (a moved project is a new slug), or on a volume not
  mounted — is listed `(missing)`. It is removed only when a person asks,
  and its folder with it, sessions and all (RFC-016's second revision): so
  `byj projects prune` keeps a folder holding a session younger than
  `:session-ttl-days` or one a live process holds (`persist/keeps-folder?`),
  and says so; `remove` says how many sessions go, and refuses while one is
  open. `by`'s prune and remove know only the record and delete the folder
  whole.
- **A moved project is adopted by name, never by guess** (S6). `byj projects
  move OLD-SLUG [DIR]` renames the old folder to DIR's slug, registers it at
  DIR keeping `:created-at`, and rewrites each session's `:project-dir` and
  `:working-dir` under the old path (`persist/rehome!`). DIR's own folder
  may hold its record alone — a run there since the move — which is
  replaced; one holding more is refused.
- **A worktree is a project of its own** (S4), keyed by its own path; its
  record says `:worktree-of` its main checkout's slug, read from its
  `.git` file and `commondir` (`main-checkout`). `byj projects list` leaves
  worktrees and `:listed? false` records out unless `--all`.

### When a project is registered

As a session (`byj`, `byj run`) or a question (`byj ask`) starts, after the
configuration is loaded, so `-C` has taken effect — brainyard's `run-tui!`
and `cmd-ask` — and by `byj projects add`. Not by a flow, a program's
evaluation, `byj init` or any command that only reads. A loop evaluation's
clones (RFC-071) are not registered: `loop-eval/clone-config` sets
`:register-project?` false, and a child byj reads it from the clone's config.
A registration that fails — a read-only home, a full disk — is the
registry's, and the run goes on.

## API

`brainyard.projects`:

- `projects-root [home]` — `<home>/.brainyard/projects`; `home` defaults to
  the `brainyard.projects-home` property, then `BY_PROJECTS_HOME`, then `$HOME`.
- `project-slug [path]`, `canonical-path [path]`, `project-user-dir [root path]`.
- `register-project! [root path]` — the record written and returned, or nil.
- `ensure-record! [root path]` — a record `:listed? false` written when the folder has none; the record, or nil.
- `list-projects [root] [root {:unlisted?}]` — the records, the last opened first, each with `:missing?`; `:listed? false` ones only with `:unlisted?`.
- `project-path-for-slug [root slug]`.
- `prune-projects! [root] [root keep?]` — every missing record removed, its folder whole, but one `keep?` says to keep; the records removed.
- `move-project! [root old-slug new-path]` — the old folder made the new path's; `{:record}` or `{:error}`.
- `main-checkout [path]` — a linked worktree's main checkout, or nil.
- `runtime-path [project kind & more]` — where the project keeps `kind` of what its runs leave (`runtime-kinds`: logs, claude-agents, codex-agents, workflow-runs, deps-snapshots, init/snapshots, captures, exports, drive/claims.edn, store.edn, todos, rails.edn, schedule, loops/proposals, loops/evals, deps-local.edn, worktrees, tools/proposals, skills/proposals; and `partial-kinds`, loops/tasks and programs, of which only what byj made moves): `<root>/<slug>/<kind>`, the one function every such path is built by (RFC-016's second revision, `byj-84c.4`); what the project's `.brainyard/` kept of it moved there the first time, into the person's own home only (`auto-migrate?`, `migrate-kind!`).
- `remove-project! [root slug]` — one record removed; it, or nil.
- `refresh-index! [root]`, `parse-origin-url [text]`, `table [rows offset]`.

The CLI, brainyard's `by projects`:

```
byj projects [list] [--all] [--json]   every project, the last opened first; --all adds worktrees and unlisted folders
byj projects path SLUG               its directory; exit 1 when not registered
byj projects add [DIR] [--json]      register DIR (the working directory) without a session
byj projects prune [--yes] [--json]  forget every missing project; asks first
byj projects remove SLUG [--yes] [--json]   forget one, there or not; asks first
byj projects move OLD-SLUG [DIR] [--json]    a moved project's folder adopted at DIR (the working directory)
```

prune and remove ask on the terminal, as brainyard's do, saying how many
sessions go with the folder; `--yes` answers for a script, and with nothing
to answer nothing is removed. Neither ever touches a project, only its folder
in the registry and what byj keeps there of its runs.

Config: `:register-project?` (boolean, true).

## Invariants

| Invariant | Enforced by |
|---|---|
| The slug is brainyard's: SHA-256 over the canonical path's UTF-8, its first 4 bytes | `projects-test/the-slug-is-brainyards-byte-for-byte` |
| A registration keeps `:created-at` and keys it does not know; the index is the records' | `projects-test/a-registration-keeps-created-at-and-what-it-does-not-know` |
| A record `by` wrote is read, and kept, as byj's own | `projects-test/a-record-by-wrote-is-read-as-byjs` |
| The registry keeps a project's runtime: prune keeps a folder with a young session and removes a stale one whole, remove says what goes, move adopts a moved project's folder and tells its sessions, a worktree says whose it is, and the list shows worktrees and unlisted folders with `--all` | `projects-test/the-registry-keeps-a-projects-runtime` |
| Every path to what a project's runs leave is built by `runtime-path`; what an older byj left in the project's `.brainyard/` is moved the first time, never what the repository shares, and never into a test run's home on its own; `byj project path` says where | `projects-test/only-the-registry-says-where-a-run-leaves-what`, `projects-test/what-a-run-left-in-the-project-is-moved-the-first-time-it-is-touched`, `projects-test/byj-project-path-says-where-a-projects-runs-leave-what` |
| Of a folder that also holds what a person decided, only what byj made moves: a loop task set but not its cases, a program's datasets, evals and proposals but not its params; tool and skill proposals move whole | `projects-test/what-byj-made-beside-what-a-person-decided-moves-and-the-decision-stays` |
| A missing project is listed, and removed only by prune or remove | `projects-test/a-missing-project-is-listed-and-only-pruned-when-asked` |
| A slug read off disk never aims the delete outside the registry | `projects-test/a-slug-off-disk-never-aims-the-delete-elsewhere` |
| `byj projects` lists, adds and forgets; prune asks, and removes nothing unanswered | `projects-test/byj-projects-lists-adds-and-forgets` |
| A folder a run keeps sessions in holds a record, unlisted until the project is registered | `persist-test/a-session-is-kept-in-the-persons-home` |
| A question registers its project, and a run with `:register-project?` false does not | `projects-test/a-question-registers-its-project-and-a-clones-does-not` |
| No test registers in the person's registry | `test-run/run` sets `brainyard.projects-home` to the run's own, and every process the run starts gets it as `BY_PROJECTS_HOME` — `fx/start`'s `carried-env`, however the run was started: shards, one image, one namespace, a live nREPL image (`byj-9og`); `test-run/run` fails a run that left a project under this repository or the temporary directory in the person's registry; `effect-test/a-process-started-keeps-off-the-persons-stores-as-this-one-does` |

## Divergences

`--json` gives an instant as ISO-8601 (divergence
`projects-json-gives-iso-instants`). A project's folder keeps what its runs
leave, where brainyard keeps only its record (divergence
`a-projects-runs-are-kept-in-its-home-folder`): a record a run writes for a
project never registered is `:listed? false`, a worktree's says
`:worktree-of`, and prune keeps a folder with a young or open session;
`byj projects move` is byj's.

## Known gaps

- brainyard's `by sessions list --all-projects`, every registered project's
  sessions, is not ported (`byj-6og`).
