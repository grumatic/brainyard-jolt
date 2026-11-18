# RFC-074 — Project registry

**Status:** implemented 2026-11-16 (`byj-k29`): brainyard's user-scope
project registry (`components/agent/.../core/projects.clj`, its
`docs/design/project-registry.md`) and its `by projects` command, ported so
that `by` and byj keep one registry on a machine that runs both.

## Purpose

A project's own state is in `<project>/.brainyard/`, which travels with the
repository. What the *person* knows about their projects — which there are,
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
its origin (RFC-075).

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
- **Missing is listed, not dropped.** A record whose directory is not there
  now — deleted, moved (a moved project is a new slug), or on a volume not
  mounted — is listed `(missing)`. It is removed only when a person asks.

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
- `list-projects [root]` — the records, the last opened first, each with `:missing?`.
- `project-path-for-slug [root slug]`.
- `prune-projects! [root]` — every missing record removed; the records removed.
- `remove-project! [root slug]` — one record removed; it, or nil.
- `refresh-index! [root]`, `parse-origin-url [text]`, `table [rows offset]`.

The CLI, brainyard's `by projects`:

```
byj projects [list] [--json]         every project, the last opened first
byj projects path SLUG               its directory; exit 1 when not registered
byj projects add [DIR] [--json]      register DIR (the working directory) without a session
byj projects prune [--yes] [--json]  forget every missing project; asks first
byj projects remove SLUG [--yes] [--json]   forget one, there or not; asks first
```

prune and remove ask on the terminal, as brainyard's do; `--yes` answers
for a script, and with nothing to answer nothing is removed. Neither ever
touches a project, only its folder in the registry.

Config: `:register-project?` (boolean, true).

## Invariants

| Invariant | Enforced by |
|---|---|
| The slug is brainyard's: SHA-256 over the canonical path's UTF-8, its first 4 bytes | `projects-test/the-slug-is-brainyards-byte-for-byte` |
| A registration keeps `:created-at` and keys it does not know; the index is the records' | `projects-test/a-registration-keeps-created-at-and-what-it-does-not-know` |
| A record `by` wrote is read, and kept, as byj's own | `projects-test/a-record-by-wrote-is-read-as-byjs` |
| A missing project is listed, and removed only by prune or remove | `projects-test/a-missing-project-is-listed-and-only-pruned-when-asked` |
| A slug read off disk never aims the delete outside the registry | `projects-test/a-slug-off-disk-never-aims-the-delete-elsewhere` |
| `byj projects` lists, adds and forgets; prune asks, and removes nothing unanswered | `projects-test/byj-projects-lists-adds-and-forgets` |
| A question registers its project, and a run with `:register-project?` false does not | `projects-test/a-question-registers-its-project-and-a-clones-does-not` |
| No test registers in the person's registry | `test-run/run` sets `brainyard.projects-home` to the run's own, and the suite's shards `BY_PROJECTS_HOME`, which a byj a test starts inherits; a byj started by a test run in one image (`test-serial:byj`, `jolt -M:test NS`) is not covered, as P-55's guard is not, and nothing checks |

## Divergences

`--json` gives an instant as ISO-8601 (divergence
`projects-json-gives-iso-instants`).

## Known gaps

- brainyard's `by sessions list --all-projects`, every registered project's
  sessions, is not ported (`byj-6og`).
