# RFC-016 — Session persistence and resume

**Status:** implemented (`byj-gly.9`); sessions kept in the person's home
since `byj-7sp` ("Sessions in the person's home", at the end; divergence
`sessions-are-kept-in-the-home`).

## Purpose

Specifies how a `byj run` session survives the process: what is written,
what comes back, and who may open a session. brainyard sources:
`components/agent-tui-persist` (`paths`, `snapshots`, `restore`, `lock`,
`rotate`, `eviction`), `agent/core/session.clj`; docs `tui/persistence.md`.

## Scope

**This layer decides** what a session's durable state is and how it is stored.

**It must not know** what a turn means — it stores what the session loop
(RFC-004) already keeps.

**It hands** restored memory back to `session/init`, and a session id to the
CLI.

## Model

### One directory per session

```
~/.brainyard/sessions/<project-slug>/
  project.edn       {:path "/abs/project"}: whose sessions these are
  <session-id>/
    session.edn     the snapshot
    lock.edn        {:pid … :at …}, only while a process holds it
```

The folder is the project's, keyed by its path (`projects/project-slug`, as
the registry's), in the person's home (`persist/sessions-home`); a worktree,
another path, is a project of its own. byj session path says where; a
program reads it there (`persist/location`). The id is the run's
`:session-id` (RFC-014): milliseconds plus 8 hex, so it sorts by age, and the
turn transcripts a compaction spills land under
`~/.brainyard/transcripts/<project-slug>/<session-id>/`. Resuming reuses the id, so a
session keeps one directory for its whole life. brainyard's ids are
`agt-<millis>-<rand>` in a project-scoped `sessions/` directory — the same
shape, split across `meta.edn`, `session.edn` and an append-only
`messages.log`, because its sessions carry far more (scrollback, sandbox
state, per-tab streams).

### What is written

```clojure
{:schema-version 1 :id "1789707779351-160d3f61"
 :created-at 1789707779351 :updated-at 1789707801022
 :working-dir "/…" :project-dir "/…"
 :turn-count 2 :usage {:input-tokens 0 :output-tokens 0 :llm-calls 2}
 :last-question "and now?"
 :previous-turns [{:question … :answer … :terminated-by … :tool-trail […]}]
 :conversation-title "list-the-project-files"
 :model "anthropic/claude-sonnet-5"
 :next-user-prompt "what is in notes.txt?"
 :transcript [[:you "what is here?"]
              [:thought "Listing it first, to see what is here."]
              [:tool {:tool-name "list-dir" :tool-args {:path "."} :tool-result "{:path \".\" …}"}]
              [:answer "two files"] [:recap "recap: …"] …]}
```

`:transcript` is the session's own record of what was said (RFC-004, what
`/capture` writes), its newest `persist/transcript-entries` (500) entries.
A turn's tool calls are in it as data rather than text: `end-turn` records
each one, before the answer as they happened, keeping its name, its arguments
and the first 400 characters of its result. Arguments are clipped to the same
length, and the raw value a tool returned is never copied, since it need not
print. The whole result is in the trajectory (RFC-025). Each iteration that
called something is behind its thought, a `:thought` entry, as the renderer
drew it live; an answering iteration's thought is not kept, since it was not
shown (`byj-gly.280.4`). The
snapshot is rewritten after every turn and an entry can be a whole answer, so
it keeps a screenful and then some rather than the whole window. A snapshot
written before this key existed restores with an empty transcript.

**A session is saved when it starts** (byj-y6a), its snapshot `:turn-count 0`, not
only after its first turn: a client lists it (ACP's `session/list`) and loads it
after its process stopped, before it has run a turn. One with nothing to bring
back — no turn, no model switched to, no name — is removed when it ends
(`close`), so a run started and quit leaves nothing; one a stopped process left
is kept a day for its client to open again, then removed when byj lists or
prunes (`persist/prune-empty!`), never a one-shot `ask`'s record and never one a
live process holds. `--resume` without an id never picks one.

`:next-user-prompt` is the last turn's suggestion of what to ask next, trimmed,
so a resume offers it again (`byj-gly.280.4`). A save with no turn since the
resume (a resume, then `/quit`) keeps the one it resumed with, and a turn that
offered none clears it (`persist-test/a-suggestion-outlives-a-save-with-no-turn`).

`:recaps` keeps each turn's recap (`session/recap`) by the turn's number,
`{N {:recap :next-step}}`, the newest `persist/recaps-kept` (200). A client
loading the session shows each one under its turn (`byj-s5h`). A resume
restores them, so its next save keeps them too, and a fork keeps only those of
the turns it keeps.

**The directories are absolute.** A snapshot is read by a later process, which
may start anywhere; `:working-dir "."` would restore to that process's cwd
rather than the session's. `persist/save!` canonicalises both as it writes
(RFC-013).

That is the session's whole durable state. **Nothing live is written** — no
tool registry, no hooks, no permission channel, no loop tree, no LM config.
They are rebuilt from the project on resume, which is also why a snapshot can
never contain something unprintable. brainyard learned this the hard way: its
close-time handler forgets to strip `:config`, so a snapshot can embed
`#object[…]` and become unreadable, which is why its readers are so tolerant.

**`:history-context` is not written.** It is rendered from `:previous-turns`
(RFC-004), so restoring it would create a second source of truth that could
disagree with the turns it came from.

### When it is written

After every turn, and again at close. The turn's answer is recorded BEFORE
the turn's snapshot is written, and a recap writes the snapshot again once it
is recorded, so a session killed at the next prompt resumes with its last
answer on the screen. Synchronously, because a session's turns
are seconds apart and a background writer would add a race for nothing.

**Atomically**: a sibling temp file, then a rename. A crash mid-write leaves
the previous snapshot rather than half of a new one.

**A failure never takes the session down.** `save!` returns nil and the session
reports it once and carries on; losing a snapshot is recoverable, losing the
session in front of the user is not.

### Resume

```bash
byj --sessions              # list, newest first
byj run -r <id>             # continue that session
byj run --resume-latest     # continue the newest
byj run --resume-run        # reopen every session the last full-screen run had open
byj run --no-persist        # save nothing
```

**A full-screen run is resumed as the set it was** (`byj-gly.178`). While the
run is on, the sessions it has open, in order, and the one last in front are
recorded in `last-run.edn` in the project's sessions folder, rewritten when either changes,
so a run that crashes still leaves its set. `--resume-run` reopens each of them
under its own id, with its history, and puts the same one in front. A session
another process has open is started fresh in its place, and says so. A session
closed with `/quit` has left the set. At exit, a run that saved several
sessions adds `all of them, as they were: byj run --resume-run` under its
resume lines. The full-screen session list also offers saved sessions that are
not open (RFC-028). brainyard resumes one session per `--resume`, so this is
the port's (divergence `a-run-resumes-as-a-set`). The inline session is one
session, and `--resume-run` there says so.

A snapshot's `:last-question` is its last turn's question. It had been read
from a key the session memory never has, and so was always nil. A summary of
an older snapshot takes the question from its turns.

**At exit the run says how to get back** (`byj-gly.252`), as brainyard's
`format-resume-hint` does: `Session saved.` — or `N sessions saved.` when the
session list held several — and one `byj run --resume <id>` line per session
written to disk that holds a turn, beside the first thing asked in it, the
commands lined up — `run`, since `byj`'s bare command is the one-shot `ask`
where brainyard's `by` opens the session. Every session is listed, since closing the run closes them
all and an id is not guessable; a run that said nothing prints nothing
(`main-test/the-run-says-how-to-resume-what-it-saved`).

Restoring sets `:previous-turns`, `:turn-count`, `:usage` and `:transcript`,
then re-renders `:history-context` from the turns. It emits
`:session/resumed {:id :transcript :turn-count :usage :next-user-prompt}` before every note about
the resume — the resume line, a kept or lost model — so a front end can put the
conversation back on the screen with them under it, rather than show them alone
first. Those notes are said and **not recorded**: they describe this resume, and
recorded, each came back with the next one, two `↻ resumed` lines and more
(byj-gly.286, `persist-test/a-resumes-notes-are-said-not-kept`). The
full-screen renderer shows each entry as it was first shown (RFC-028). The
inline front end prints it as `/capture` writes it (`session/transcript-text`),
on a terminal only: into a pipe or a CI log, the new turns are the output, not
the old ones again. brainyard gates its inline `:resume-tail` on a tty for the
same reason. The model is told nothing explicitly — it
sees the restored history in its prompt, which is the same thing it would have
seen had the session never stopped. The user gets one line:

```
↻ resumed 1789707779351-160d3f61 · 1 turn(s) · 1 LLM call(s) · last active just now
```

**A snapshot that cannot be read starts a fresh session** with an error line,
and the file is left on disk: it is the only copy of whatever went wrong. That
covers unreadable EDN, a non-map, a `:schema-version` this build does not
write, and a readable file that is not a snapshot's shape. That is checked
against `persist/snapshot-schema` (byj-gly.204), with the problems named, e.g.
`` `turn-count` should be an integer``. Resuming such a file would fail later,
further from the cause. The keys added after version 1 are optional, so an
older snapshot still resumes; all six snapshots in this repository did when
the schema was written. brainyard has no version key at all and relies on tolerance; a version
lets an old file be recognised rather than half-read.

### One live process per session

`lock.edn` holds `{:pid … :at …}`. A session is held when the lock names a
**different, live** pid, and resuming it is refused:

```
error: session <id> is open in another running process (pid 4242)
```

jolt has no `ProcessHandle`, so liveness is `kill -0 <pid>`: exit 0 means
alive, and **"operation not permitted" also means alive** — that is another
user's process, not a stale lock. Only "no such process" frees the session,
which is positive evidence rather than an assumption. A clean close removes the
lock; a crash leaves one, and the next run takes it over once the pid is gone.

Every live session is locked, a fresh one as well as a resumed one, and so is
each session started beside the first in the session list. The lock is what
says a session is live, which the session channel relies on (RFC-031,
`byj-gly.253.2`).

### Pruning

At startup, `prune!` removes the sessions of a project that is gone past
`:session-ttl-days` (`prune-gone!`, "Sessions in the person's home"), and
session, transcript and task (RFC-019) directories of this project that are
all of:
past `:session-ttl-days` (14), outside the most recent `:keep-sessions` (20)
**in their own tree**, not this run's, and not held by a live process. It costs
one directory listing, and `:prune-sessions? false` switches it off.

Two guards brainyard does not have: it never deletes a session another process
holds — brainyard's pruner writes a lock and then ignores it — and
`delete-tree!` refuses anything that is not a direct child of the root it was
given, so a malformed id cannot reach into the project.

#### A finished run is not a session

Under `sessions/` there are two kinds of directory, and the rule above suits
only one. A directory with a `session.edn` is a conversation `--resume` can
continue. A directory without one is what a **one-shot `ask`** leaves: its
trajectory (RFC-025), and nothing that could be resumed.

The age rule lets the second kind pile up without limit. Nothing younger than
the TTL is ever pruned, so `:keep-sessions` never bites and a busy day leaves a
directory per turn — 615 of them in one afternoon here (`byj-gly.89`).

So they are budgeted apart. A resumable session keeps the rule above. A
finished run's record is bounded by **count alone**, `:keep-ask-records` (200),
with no TTL — a run that ended has nothing to wait for.

Counting them together would be a worse bug than the one it fixes: a session
from yesterday deleted because twenty newer asks happened. `resumable?` is what
tells them apart, and the lock is still checked before either budget.

The bound is count and age, not bytes: at most 20 sessions and 20 transcript
trees survive. brainyard also enforces a 50 MiB budget per session, which
matters more there (scrollback) than here.

## API

`brainyard.persist`: `state-dir`, `sessions-dir`, `session-dir`, `session-file`,
`lock-file`, `transcripts-dir`, `tasks-dir`, `project-of`, `location`, `own-pid`, `alive?`, `read-session`, `save!`, `summaries`,
`latest-id`, `holder`, `lock!`, `unlock!`, `restore`, `describe`,
`schema-version`, `fork!`, `delete!`.

**A fork** (`persist/fork!`, `byj-g5n.12`, the port's own): a new session
whose snapshot is another's as it stands, with `:forked-from` naming it —
kept across its saves — and times of its own. Nothing else is copied: not the
lock, the channel, the trajectory or the transcripts. The original is not
touched, so the two go on apart, each resumable. Opened through the SDK
(RFC-053: `session/new` with `_meta.brainyard.forkFrom`), and by RFC-069's
`/fork` and `byj turns fork`, which may cut it before a turn
(`:before-turn`, `persist/cut-before`): the history and transcript without
that turn and after, `:turn-count` one less than it, and
`:forked-before-turn` beside `:forked-from`. So a cut lands on a turn, each
`:previous-turns` entry carries the `:turn` it was since RFC-069 (a
`/compact` summary its `:compacted`); one saved before is counted back from
the newest.

**Notes for the next turn** (`persist/note!`, `take-notes!`, RFC-069,
`byj-gly.488`): `notes.edn` in a session's directory holds lines for its
next turn — what was done to its files from outside it — which
`session/begin-turn` takes, leaving none. Left only for a session there is,
saved or held by a live process; a directory holding only notes is never
made, so a note is not a session in `summaries`.

**A delete** (`persist/delete!`, `byj-g5n.14`, the port's own): a saved
session removed at someone's word — its directory and its transcript tree —
where `prune!` removes by age. A session another live process holds is
refused, since its next save would write it back. Reached through ACP's
`session/delete` (RFC-053); the CLI has no command for it.

**Where a session is kept, as a protocol** (`persist/location`,
`byj-7sp.1`): `{:session-id :dir :project-dir :exists}`. `byj session list
--json` gives each row's `dir` and `project-dir`, and `byj session path ID
[--json]` one session's — its directory, exit 1 when there is none — so a
program built on byj reads a session where byj says it is rather than
building `.brainyard/sessions/<id>` itself, and keeps working when the
place moves (the revision below). Both are byj's contract
(`contract/session/list.json`, `contract/session/path.json`).

Config (RFC-013): `:persist-session?` (default true), `:resume` (an id, set by
the CLI), `:session-id`, `:prune-sessions?` (true), `:keep-sessions` (20),
`:keep-ask-records` (200),
`:session-ttl-days` (14). As schema keys they take `BY_KEEP_SESSIONS`,
`BY_SESSION_TTL_DAYS` and so on.

### What a resume line says

`:turn-count` counts every turn a session ran; `:previous-turns` is what it
still remembers, and `/clear` makes the two differ. The line names both when
they do — `3 turn(s) (1 remembered)` — because naming only the first greets
someone with three and then shows them one (`byj-gly.75`).

## Invariants

| invariant | enforced by |
|---|---|
| A session resumed where it is kept goes on in the folder its snapshot says it worked in, when that is still there and not the project's own (a workflow stage's or team member's worktree), and is saved so; a session keeps the session leading it across saves | `acp-test/a-session-loaded-where-it-is-kept-works-where-it-worked`, `acp-test/saved-sessions-are-listed-for-an-editor-to-import` |
| At exit, each saved session with a turn is named with the command that resumes it, and nothing is said for one without. | `main-test/the-run-says-how-to-resume-what-it-saved` |
| A resume hands the front end what was said, the last answer included even when the session never reached `/quit`, before its resume line, with each tool iteration's thought before its calls and the last next-user-prompt; the snapshot keeps only the newest entries. | `persist-test/a-resume-says-again-what-was-said` |
| The inline front end prints a resumed session's history on a terminal and not into a pipe, and passes every event on. | `main-test/an-inline-resume-prints-what-was-said-on-a-terminal-only` |
| A resume line names what the session remembers whenever that differs from what it ran. | `persist-test/the-resume-line-does-not-promise-turns-that-are-gone` |
| Each turn's recap is kept by its turn, the newest `recaps-kept`; a resume restores them and a fork keeps those of the turns it keeps. | `persist-test/each-turns-recap-is-kept-by-its-turn`, `session-test/a-long-turn-ends-with-a-recap` |
| A model `/model` switched to is saved with the session, at once, and a resume asks it again through the `:lm-box`; a session that never switched resumes on the run's own. | `persist-test/a-switched-model-is-resumed` |
| A snapshot holds the turns, counts and usage, keeps its creation time across saves, and never stores the rendered history. | `persist-test/a-snapshot-is-the-session-and-nothing-live` |
| A session is saved when it starts, and one with nothing to bring back is removed when it ends; one a stopped process left is kept a day, then removed — never an `ask`'s record, one a live process holds, or this run's own; `--resume` never picks one. | `persist-test/a-session-is-saved-when-it-starts-and-leaves-nothing-when-it-ends-with-no-turn`, `persist-test/a-session-that-never-ran-a-turn-is-kept-a-day-then-removed`, `acp-test/saved-sessions-are-listed-for-an-editor-to-import` |
| The directories it stores are absolute and canonical, whatever the caller passed. | `persist-test/a-stored-directory-is-absolute` |
| A fork is a new id whose turns go on from the original's history and whose snapshot names it; the original's snapshot is unchanged; a session that is not there is no fork. | `sdk-test/a-session-is-forked-and-the-original-left-as-it-was`, `acp-test/a-fork-is-a-new-session-from-a-saved-one` |
| A fork cut before turn K holds what came before K and names the turn; one that cannot be cut there is refused (RFC-069). | `persist-test/a-fork-is-cut-before-a-turn` |
| A note is left only for a session there is, handed out oldest first and once, and is not a session (RFC-069). | `persist-test/a-note-is-left-for-a-session-s-next-turn` |
| A delete removes the session's directory and its transcript tree; one another live process holds is refused and kept. | `acp-test/a-saved-session-is-deleted` |
| A missing, unreadable, or wrong-version snapshot is a reported problem, never an exception, and the file is left alone. | `persist-test/a-bad-snapshot-is-reported-not-thrown` |
| A snapshot of the wrong shape is a problem naming what is wrong; what `save!` writes always has the shape. | `persist-test/a-bad-snapshot-is-reported-not-thrown`, `persist-test/what-save-writes-is-a-snapshot` |
| Sessions list newest first; a broken one is listed with its problem and is never "latest". | `persist-test/listing-and-latest` |
| A run's set comes back in order with the one in front, only what is still saved; a front that is gone falls back to the first; the record is not a session; a snapshot's last question is its last turn's. | `persist-test/a-run-remembers-the-set-it-had-open` |
| A live foreign pid holds a session; our own lock does not block us; a dead pid is stale; unlocking removes the file. | `persist-test/one-live-process-per-session` |
| A session survives a restart: the turns come back, the count continues, the history is re-derived, and an unreadable snapshot starts fresh rather than refusing. | `persist-test/a-session-survives-a-restart` |
| `:persist-session? false` writes nothing at all. | `persist-test/persistence-can-be-switched-off` |
| Pruning removes only what is past the TTL, outside the keep count, not this run's and not held by a live process — whole tree, both trees. | `persist-test/pruning-removes-only-what-it-may` |
| **A finished run's record is bounded by count, with no TTL** — which is what the age rule could not do. | `persist-test/a-finished-runs-record-is-bounded-by-count-not-by-age` |
| **A resumable session is never pruned to make room for records**, and a held one is left alone whichever budget it falls under. | `persist-test/a-resumable-session-is-never-pruned-to-make-room-for-records`, `persist-test/a-record-held-by-a-live-process-is-left-alone` |
| A `session.edn` is what makes a directory resumable; transcripts keep the rule they had. | `persist-test/what-makes-a-directory-resumable-is-the-snapshot`, `persist-test/transcripts-keep-the-rule-they-had` |
| Pruning never deletes anything that is not a direct child of its root. | `persist-test/pruning-refuses-to-escape-its-root` |
| The sessions' home is a home given, the `brainyard.sessions-home` property, `BY_SESSIONS_HOME`, then `$HOME`; a test run's is its own, carried to every process it starts, and `test-run/run` fails a run that left a test's project folder in the person's. | `persist-test/the-sessions-home-is-the-runs-own-in-a-test`, `effect-test/a-process-started-keeps-off-the-persons-stores-as-this-one-does` |
| A session is kept in `<home>/.brainyard/sessions/<project-slug>/`, which says its project (`project.edn`); a worktree is a project folder of its own. | `persist-test/a-session-is-kept-in-the-persons-home` |
| A session kept before is moved, with its transcripts, the first time its project is touched, and the old folders go; one an older byj holds stays, is listed and read where it is, and `migrate!` says it left it. Only into the person's own home: a test run's never moves a project's on its own. | `persist-test/a-session-kept-before-is-moved-the-first-time-its-project-is-touched`, `persist-test/a-session-an-older-byj-holds-stays-and-is-listed-until-it-is-free`, `persist-test/a-test-run-never-moves-a-projects-sessions-on-its-own` |
| A gone project's sessions are kept until `:session-ttl-days`, no keep count saving them, then removed with their transcripts, never a held one; its folders go with its last; a project that is there keeps its rules. | `persist-test/a-gone-projects-sessions-are-kept-until-the-ttl-then-go` |
| Every path to a session's folder, transcripts and tasks is built by `persist/state-dir`, no other namespace. | `persist-test/only-persist-says-where-a-session-is-kept` |
| A session says where it is kept: the list's `dir` and `project-dir`, `byj session path`, exit 1 for none; both shapes are byj's contract. | `sdk-test/a-session-says-where-it-is-kept`, `contract-test/a-projects-sessions-are-byjs-contract` |

## Known gaps

- **No size budget.** Pruning counts directories and ages them; a single
  session's transcripts can still be large, since a spill holds whole tool
  results. brainyard caps a session at 50 MiB.
- **No rotation on `/clear`.** brainyard mints a new session id and links the
  pair; `/clear` here empties the history in place, so the cleared turns are
  overwritten in the snapshot at the next turn.
- **History demotion is not batched**, so `history-context` re-renders every
  turn and the cached prefix after it is invalidated each time (RFC-015).
  brainyard demotes in batches of 10 precisely to keep that prefix byte-stable.
- **`ask` is not persisted** — a one-shot question has no session to continue.
- **No `--resume` picker.** brainyard offers an interactive menu; here
  `--sessions` lists and `-r <id>` chooses.

## Sessions in the person's home

Decided 2026-11-25 and built (`byj-7sp`): sessions moved to the person's
home, a worktree keyed by its own path, a gone project's sessions kept until
`:session-ttl-days`, and another project's sessions closed to an agent by
default. What follows is why, what it cost, and how it is laid out.

### Why move them

A session is kept in the project it was opened in. That is brainyard's choice,
made on purpose (`agent/core/config.clj`: `"sessions" :project-only` — a
session describes work in one codebase, "rather than a user-global junk drawer
shared across every repo"), and byj's today. It costs four things:

1. **A worktree takes its sessions with it.** A session opened in a worktree —
   an editor's "new session in a new worktree", a fork into one (`fork!`
   `:into`) — is kept in the worktree's `.brainyard/`, and
   `worktree/remove!` runs `git worktree remove --force`: the worktree's
   sessions, their turns, usage and trajectories, go with it.
2. **The project folder holds a person's records.** A snapshot keeps what was
   asked, the tools' output and files' contents. Only `.gitignore` keeps it
   out of a commit; nothing keeps it out of a container build context, an
   archive, a synced folder or a shared mount.
3. **A project made again starts with nothing.** A folder deleted and cloned
   again at the same path has lost every conversation held in it.
4. **There is no one place for all of a person's sessions**, which
   brainyard's `by sessions list --all-projects` reads project by project
   (`byj-6og`).

Claude Code and Codex both keep sessions in the person's home, keyed by
directory: Claude Code under `~/.claude/projects/<cwd, every non-alphanumeric
→ ->/<uuid>.jsonl` with the live ones in `~/.claude/sessions/<pid>.json`
(RFC-033), Codex under `~/.codex/sessions/<y>/<m>/<d>/` with the cwd in each
rollout (RFC-049).

### What it costs

- **A divergence from brainyard**, in a home `by` shares: `~/.brainyard/` is
  both programs', and `by` keeps its sessions in the project.
- **Records outlive their projects.** On one developer's machine, Claude
  Code's home held 209 project folders, 142 of them for directories under the
  system's temporary directory (tests and one-off runs) and 30 for worktrees.
  Pruning has to reach a folder whose project is gone.
- **Checkpoints stay in the project.** A turn's snapshot is a git ref in the
  project's repository (RFC-069), so a session found again in a project made
  again resumes its conversation but has no turn to diff, rewind or fork
  before. Claude Code keeps its file backups in its home; byj does not.
- **Every project's sessions are readable from any project**: `~/.brainyard`
  is in `:allowed-dirs` by default (RFC-012).
- **A protocol change**: RFC-070 names `.brainyard/sessions/<id>/` among what a
  session leaves on disk, `contract/session/frames.edn` gives a trajectory's
  `:path` under it, and programs built on byj read it there.

### Layout

```
~/.brainyard/sessions/<project-slug>/
  project.edn               {:path "/abs/project"}: whose sessions these are
  last-run.edn              the full-screen run's set, per project as now
  <session-id>/             session.edn lock.edn meta.edn notes.edn coach.edn
                            trajectory.edn ask.sock — as now
~/.brainyard/transcripts/<project-slug>/<session-id>/   (RFC-014)
~/.brainyard/tasks/<project-slug>/<task-id>/            (RFC-019)
```

- **The key is the project's path**, as the registry's: `projects/project-slug`
  of the canonical project directory (its name and a hash of its path). A
  project made again at the same path finds its sessions; a project moved or
  renamed does not, as in Claude Code.
- **A tree of its own, beside the registry, never inside it.**
  `~/.brainyard/projects/<slug>/` is removed whole by `byj projects
  prune|remove` and by `by`; sessions there would go with a registry entry.
- **Per project, as now.** Listing (`summaries`, `--sessions`, ACP
  `session/list`), the live sessions (`channel/sessions`), `--resume-latest`,
  `last-run.edn`, the pruning budgets, memory search and analytics each read
  one project's folder, as they read `.brainyard/sessions` today; a flat
  folder of every project's sessions would break all of them.
- **Transcripts and tasks move with sessions**: they are pruned and deleted
  with a session by its id.
- **The root** is the `brainyard.sessions-home` property, then
  `BY_SESSIONS_HOME`, then `$HOME`, as `projects/projects-root` resolves the
  registry's, and carried to every process `fx/start` starts
  (`fx/carried-env`, RFC-009), so a test run keeps every session it makes in
  a home of its own.
- **The socket** is `<session-dir>/ask.sock` as now. Past the platform's limit
  (103 bytes on macOS) it falls back to the temporary directory, as RFC-031
  says; a long project name makes that the rule rather than the exception.

### Worktrees

**A worktree is keyed by its own path**, as Claude Code keys one: its
sessions are a project folder of their own, listed with the worktree as an
editor groups them, and they survive the worktree's removal — the folder is
then a gone project's, pruned as one. A team member and a workflow stage are
kept where they are now, under the project that started them
(`BY_PROJECT_DIR`), their `:working-dir` saying the worktree.
`deps/session-places` looks in the project's folder and each of its
worktrees' folders under the home, rather than in each worktree's
`.brainyard/`; the SDK's fork into a worktree writes into the worktree's
folder; `resume-working-dir` is unchanged.

The alternative, the repository's main checkout as the key (one list per
repository), was not taken: an editor lists a worktree's sessions as the
worktree's, and the one list would have mixed them.

### Pruning

As now, per project: `:keep-sessions`, `:session-ttl-days`,
`:keep-ask-records`, never a held session. **A project that is gone** — its
`project.edn` path no longer there, a worktree removed, a temporary directory
deleted — keeps its sessions until `:session-ttl-days`, the keep count no
longer protecting them: nothing will open them in place, and the TTL is the
time a person has to find one again. The folder goes with its last session.
A held session is never removed. A project's prune reaches every gone
project's folder, so they are pruned though their project never runs again.

### Migration

A session not found under the home is looked for in
`<project>/.brainyard/sessions/`, and moved, with its transcripts and tasks,
the first time it is opened (resume, load, fork, delete). Listing reads both
until the old folder is empty, which is then removed. `byj session migrate
[--all]` moves a project's, or every registered project's, at once. A live
session is never moved.

### Another project's sessions

**An agent cannot reach another project's sessions by default.**
`~/.brainyard` stays in `:allowed-dirs` (RFC-012) — its skills, memory and
registry are the person's, for every project — but the path gate refuses a
path under `~/.brainyard/sessions/`, `transcripts/` or `tasks/` outside the
running project's own folder (and its worktrees' when it is a repository's
main checkout), as it refuses `:denied-paths`. A person who wants it open
says so in config (`:session-dirs-shared? true`). Reading one's own sessions
is unchanged: `session$*` tools, memory search and analytics read the
project's folder through `persist`, not through the gate.

### Where programs find a session

Before anything moves, the location becomes a protocol rather than a path a
program builds:

- `byj session list --json` gives each session's `dir` and `project-dir`;
- `byj session path ID [--json]` gives one session's directory;
- `meta.edn` names its `:project-dir`;
- the contract's `:path` placeholder becomes `<sessions>/<sid>/trajectory.edn`,
  `<sessions>` the session's project folder, whatever its root.

Shipped first (`byj-7sp.1`), with the location unchanged, so a program
reading the folder could move to the command before the folder moved; the
contract writes the folder `<sessions>`.

### What changes in byj

- `persist`: the one root (`sessions-dir`, the transcripts' and tasks' dirs);
  every path built elsewhere goes through it — `trajectory` (`log-file`,
  `read-entries`, `sessions`), `children/saved`, `sim/records-since`,
  `context`'s transcript path.
- `channel`: `meta.edn`'s `:project-dir`; `sessions` and `find-session` read
  the project's folder under the home.
- `deps`: `session-places`, as the worktree question decides.
- ACP and the SDK: `session/list`, `session/load`, `session/delete` and the
  fork into a worktree find the project's folder from the cwd given.
- The CLI: `byj session list|path|migrate`, `--sessions`, the help.
- The test runner: `brainyard.sessions-home` set to the run's own;
  `own-sessions` watches the person's home for a project folder under this
  repository or the temporary directory, as `person-registered` watches the
  registry (provenance P-66); tests that build a session's path read it
  through `persist`.
- `permission`: the path gate's refusal of other projects' session folders,
  and `:session-dirs-shared?`.
- Docs: this RFC, RFC-025, RFC-031, RFC-070, RFC-074, RFC-012 (allowed dirs),
  `contract/`, the CLI help, a divergence entry in `docs/divergences.md` and
  `.edn`.

### What it promises

- A session is kept under the person's home, in its project's folder, and
  nothing of it under the project's `.brainyard/` once migrated.
- A worktree removed leaves its sessions, listed under its own path until
  pruned.
- A project made again at its path lists the sessions it had.
- No test writes a session in the person's home.
- A session whose project is gone is kept until `:session-ttl-days`, then
  pruned; a held session never is.
- An agent's tool cannot read or write another project's sessions,
  transcripts or tasks unless config shares them.
- A session found in the old place is moved when opened, and listed until it is.
- `byj session list --json` and `byj session path` say where a session is.

### Steps

1. The protocol (`byj-7sp.1`, built): `dir` and `project-dir` in `byj session list --json`, `byj
   session path`, `:project-dir` in `meta.edn`; the location unchanged.
2. One root (`byj-7sp.2`, built): every session path built through `persist`.
3. The root's resolution and the test runner's home and guard (`byj-7sp.3`, built: `persist/sessions-home`).
4. The move (`byj-7sp.4`, built): the layout, transcripts and tasks with it, reading both places,
   migration.
5. Pruning a gone project's sessions (`byj-7sp.5`, built: `persist/prune-gone!`).
6. The path gate closing other projects' sessions to an agent (`byj-7sp.6`, built: RFC-012).
7. The docs, the contract recorded again, the divergence (`byj-7sp.7`).
