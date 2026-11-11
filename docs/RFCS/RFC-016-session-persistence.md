# RFC-016 — Session persistence and resume

**Status:** implemented (`byj-gly.9`).

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
<project-dir>/.brainyard/sessions/<session-id>/
  session.edn     the snapshot
  lock.edn        {:pid … :at …}, only while a process holds it
```

The id is the run's `:session-id` (RFC-014): milliseconds plus 8 hex, so it
sorts by age, and the turn transcripts a compaction spills land under
`.brainyard/transcripts/<session-id>/` beside it. Resuming reuses the id, so a
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

`:next-user-prompt` is the last turn's suggestion of what to ask next, trimmed,
so a resume offers it again (`byj-gly.280.4`). A save with no turn since the
resume (a resume, then `/quit`) keeps the one it resumed with, and a turn that
offered none clears it (`persist-test/a-suggestion-outlives-a-save-with-no-turn`).

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
recorded in `.brainyard/sessions/last-run.edn`, rewritten when either changes,
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

At startup, `prune!` removes session, transcript and task (RFC-019) directories
that are all of:
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

`brainyard.persist`: `sessions-dir`, `session-dir`, `session-file`,
`lock-file`, `own-pid`, `alive?`, `read-session`, `save!`, `summaries`,
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
| At exit, each saved session with a turn is named with the command that resumes it, and nothing is said for one without. | `main-test/the-run-says-how-to-resume-what-it-saved` |
| A resume hands the front end what was said, the last answer included even when the session never reached `/quit`, before its resume line, with each tool iteration's thought before its calls and the last next-user-prompt; the snapshot keeps only the newest entries. | `persist-test/a-resume-says-again-what-was-said` |
| The inline front end prints a resumed session's history on a terminal and not into a pipe, and passes every event on. | `main-test/an-inline-resume-prints-what-was-said-on-a-terminal-only` |
| A resume line names what the session remembers whenever that differs from what it ran. | `persist-test/the-resume-line-does-not-promise-turns-that-are-gone` |
| A model `/model` switched to is saved with the session, at once, and a resume asks it again through the `:lm-box`; a session that never switched resumes on the run's own. | `persist-test/a-switched-model-is-resumed` |
| A snapshot holds the turns, counts and usage, keeps its creation time across saves, and never stores the rendered history. | `persist-test/a-snapshot-is-the-session-and-nothing-live` |
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
