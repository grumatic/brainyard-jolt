# RFC-069 — Turn checkpoints and rewind

**Status:** implemented 2026-11-08 ("권장 순서대로 진행"): the snapshots as a
layer of their own, every turn kept (`byj-gly.483`); the files put back to a
turn, or one turn undone (`.484`); a conversation taken back to a turn by a
fork, decided (`.485`) and built (`.486`). 2026-11-09: a rewind waits for
a turn running in the same work tree (`.487`), and a session is told what
was done to its files from outside it (`.488`). Not in brainyard, which keeps no
checkpoints; the nearest is Claude Code's `/rewind`.

## Purpose

What a session's agent did to the project is often worth taking back: a
turn that went the wrong way, a refactor that should not have touched a
file, the third attempt that was worse than the first. This layer keeps the
project's work tree as it stood when each turn began and ended, so a person
can see what one turn changed, put the files back as they were at any turn,
take back one turn alone, and start a new session from the conversation as
it stood before a turn.

It began as RFC-067's phase 8 — a turn's change, reviewed in Emacs. Nothing
of it needs Emacs, and it is a session's, so it is a layer here and RFC-067
uses it.

## Scope

**This layer decides** what is kept of the work tree at a turn's start and
end, how a turn's change and the turns are read back, and how the work tree is
made one of those states again.

**It must not know** why a turn is taken back, what the agent is told about
it beyond the words its prompt resource holds, or what a front end draws. It
knows git and the session's id, not providers, tools or renderers.

**It hands** the session (RFC-004) `begin-turn!`, `end-turn!` and the
commands' mechanism; persistence (RFC-016) the cut of a snapshot before a
turn; the CLI `byj turns`; Emacs (RFC-067) the diff its review opens; the
ReAct loop's extension slots (RFC-072) the turn's start as `:turn-start`,
which `session/begin-turn` keeps from what `begin-turn!` returns.

## Model

### Snapshots (`brainyard.changes`)

As `session/begin-turn` starts a turn and `end-turn` ends it
(`changes/begin-turn!`, `end-turn!`), the git work tree the project is in is
made into a commit object: a copy of the person's index (so an unchanged
file costs a stat), `git add -A --ignore-errors` into it, then `write-tree`
and `commit-tree --no-gpg-sign`, named byj, never the person. About 40 ms on
this repository.

The copy keeps the index's own modification time. git trusts an entry's
stat only for a file older than the index holding it, and compares times to
the second when built without `USE_NSEC`, as Apple's git is. A copy stamped
when it was made would pass a same-size edit, written in the second the
person's index was, as unchanged once the turn ended in the next second
(provenance P-63). The copy's name is a `random-uuid`, never the clock:
jolt's `System/nanoTime` counts from the process's start, so two byj
processes begun together would write one file.

- **What it takes:** every file git would add, tracked or not.
- **What it leaves out:**
  - what `.gitignore` names;
  - any `.brainyard/` directory, which is byj's own state;
  - what Emacs leaves beside a file it visits: `.#F` (the lock of a buffer with unsaved edits, a symlink), `#F#` and `F~`;
  - a file git cannot take, such as a nested repository with no commit. That file is left out, not the whole snapshot.
- **What it never touches:** HEAD, the person's index, their stash and the work tree.

**What it holds** is whatever changed the work tree during the turn:
- the agent's tools, bash included;
- a Claude Code or Codex the session drove, and a team member in the same tree;
- the person too, if they edited a file while it ran.

It does not hold:
- what came after the turn;
- an edit made in an editor's buffer and not saved when the turn ended. The snapshot is the disk's.

### Every turn is kept

The end's commit has the start's as its parent and is kept as
`refs/byj/turns/<session>/<turn>`, its message `byj <session> turn <n>:` and
the question's first line.

- **Turns that changed nothing are kept too:** such a turn is still a start
  to go back to. Its two commits share a tree, so its ref costs a commit
  object. Whether a turn changed files is read from its trees.
  (`byj-gly.483`; until then only a turn that changed files was kept.)
- **Pruning:** the repository keeps its newest `:changes-turns` (200) turn
  refs across sessions, by commit time. An older one is deleted as a turn
  ends, and git's gc takes what only it held.
- **The end event:** `:session.turn/end` carries `:files-changed`.
- **Off:** nothing is kept outside a git work tree, without a session id, or
  with `:changes?` false.

### Reading the turns

- **`changes/rows`:** a session's kept turns, each as `{:turn :ref :before
  :after :files :subject :at}`. The CLI shows them with `byj turns list
  SESSION`, and `/rewind` with no argument shows the same.
- **`changes/diff`:** git's diff between a turn's two snapshots, for the last
  turn that changed files unless a turn is named.
  - `byj turns diff SESSION [TURN] [--json]` prints it.
  - `byj emacs changes` says the same; it is the name `byj-review` asked by
    first.
  - `git show refs/byj/turns/<session>/<turn>` says the same too.

### Going back: the files

Each of the three operations below works the same way:
1. It snapshots the work tree as it is, and keeps that as
   `refs/byj/rewinds/<session>/<n>`. So what it replaces is never lost, the
   person's own work since the turn included. The snapshot's message says
   what it did — "byj ID rewind to turn K's start, after turn N" (or "K's
   end"), "byj ID undo turn K", "byj ID undo rewind M" — N the session's
   last kept turn then, so a reader knows which turns came before it
   exactly (RFC-071 reads it, `byj-ng7.10`).
2. It makes the work tree the target by git's own binary diff from that
   snapshot. git writes the diff to a file, so a file's bytes never pass
   through a string. `git apply` applies it all or nothing: what differs is
   written, a file the target lacks is removed, and one it has is made.

Untouched: HEAD, the index, the stash, and anything the snapshots leave out.

- **Rewind** (`changes/rewind!`, `/rewind TURN [end]`, `byj turns rewind`):
  the files as they were when the turn began, or ended.
- **Undo a turn** (`changes/undo-turn!`, `/undo-turn TURN`, `byj turns
  undo-turn`): that turn's change taken back alone. git's three-way merge in
  memory (`merge-tree --write-tree --merge-base`, git 2.40 or later) takes:
  - the turn's end as the base;
  - the work tree as ours;
  - the turn's start as theirs.

  What came after the turn stays. Where something later changed the same
  lines, the file is left with conflict markers and named. The index is
  never used, where `git apply -3` would have used the person's.
- **Undo a rewind** (`changes/undo-rewind!`, `/rewind undo [N]`, `byj turns
  undo`): the work tree put back as it was just before rewind N, the last by
  default. That is itself kept first, as the newest rewind, so taking the last
  back twice puts it back again. Naming N reaches further back.

A session's own `/rewind` and `/undo-turn`:
- **Refuse while background work runs**, since it may be writing those files.
- **Tell the next turn.** A line of the person's console activity is added to
  the history (RFC-004), in the words of
  `prompts/console-activity.edn`'s `:rewind`, `:undo-turn` or `:undo-rewind`.
  It names the files that changed on disk and asks the agent to read a file
  again before relying on what it held.
- **Emit `:session/rewind`** on the bus.

### Not while a turn runs in the work tree (`byj-gly.487`)

A turn running marks its work tree: `changes/begin-turn!` writes
`byj/running/<session>` under the work tree's own git directory — `{:session
:turn :pid :at}` — and `end-turn!` removes it. A linked worktree has a git
directory of its own, and another work tree's turn writes other files, so
only this work tree's turns count.

Every operation above asks `changes/running` first and, if a turn is
running, changes nothing and says whose: `session S is in the middle of
turn N in this work tree (process P)`. Counted:
- **another process's turn**, any session, the asker's own id included —
  `byj turns rewind S` while S is mid-turn in its window;
- **another session's turn in this process**, under a front end that holds
  several;
- **not the asker's own**, in its own process: a session's commands run
  between its turns.

A mark whose process is gone (`persist/alive?`, `kill -0`) is stale: a
killed session left it, and whoever finds it removes it. A cancelled turn
still ends, and removes its mark. The check is not a lock: a turn that
begins in the instant between it and the write is not seen.

### Told from outside (`byj-gly.488`)

What is done to a session's files from outside it is left as a note with
the session — `notes.edn` in its directory, `persist/note!` — and the
session's next turn takes it (`persist/take-notes!`, in
`session/begin-turn`), as a line of console activity, as its own `/rewind`
would have told it. One mechanism serves a session live in another process
and one resumed later. A note is left only for a session there is, saved or
held by a live process, and is not one in `session list`.

| done | from | the session told | words |
|---|---|---|---|
| `byj turns rewind`, `undo-turn`, `undo` | the CLI | that session | `:rewind`, `:undo-turn`, `:undo-rewind` in `:outside` |
| `/fork K`, files put back | the session forked from | itself, at once (its next turn) | `:forked-away` |
| `byj turns fork S K`, files put back | the CLI | S | `:forked-away` in `:outside` |
| `/fork K keep-files`, `byj turns fork S K keep-files` | either | the new session | `:fork-kept`: its files hold turns K and after, which its conversation does not |

### Going back: the conversation (decided, `byj-gly.485`)

A conversation is taken back to turn K by a **fork**: a new session whose
snapshot is this one's cut before K, this one untouched. `/fork [TURN
[keep-files]]` and `byj turns fork SESSION [TURN [keep-files]]` make it.
- **Snapshot:** `persist/fork!` with `:before-turn` writes it (RFC-016, `persist/cut-before`).
- **Its fields:** history and transcript without turn K and after,
  `:turn-count` K−1 (its next turn is K, under its own id),
  `:forked-from` and `:forked-before-turn`.
- **The files:** with a turn they are put back to its start, as `/rewind`
  does, so the new session's conversation and the work tree agree.
  `keep-files` leaves them.
- **Where it opens:** under the full-screen renderer the new session opens
  beside this one (`:start-session` with `:resume`). Inline, the line says
  `byj run --resume NEW`.

The other way, truncating the session in place, was measured and refused.
`dev/brainyard/dev/rewind_spike.clj` (a session of 5 turns on the mock
provider, taken back to turn 3) counts what holds turns 3–5 under the
session's id:

| store | holds of turns ≥ K | a truncation would |
|---|---|---|
| `session.edn` `:previous-turns` | 3 | rewrite |
| `session.edn` `:transcript` | 12 entries | rewrite |
| `session.edn` `:turn-count`, `:last-question` | both | rewrite, and either reuse turn numbers or leave a gap |
| trajectory (RFC-025) | 3 records | delete from an append-only file, or leave as the session's work |
| events on the bus and `events.log` (RFC-026) | 3 `:session.turn/end` | leave saying what no longer happened |
| `refs/byj/turns/<id>/≥K` | 3 refs | delete, or collide with the next turn K |
| a share's live viewers and recording (RFC-043) | everything shown | cannot be taken back |

**Why a fork:**
- **Nothing to rewrite.** A fork writes one file, the new `session.edn`,
  measured at 3.6 ms; putting 3 files back took 260 ms. Every store above
  stays true, as an append-only record should.
- **The trajectory forbids it anyway.** A trajectory record carries no turn
  number, deliberately (`trajectory/record`): position in the file is its
  order, and a truncation would make that position lie.
- **Undo is free:** the session forked from is still there, still resumable.
- **It extends what exists.** A fork already existed for the Agent SDK
  (`byj-g5n.12`); this adds the cut.

**The price:**
- **A new id:** the person works on in a new session. The window opens it
  beside the old one, and `session list` shows both.
- **A cut has to land on a turn boundary:**
  - so each history entry now carries the `:turn` it was;
  - an entry saved before that is counted back from the newest;
  - a turn inside a `/compact` summary cannot be cut before, and says so.

## API

```clojure
(changes/begin-turn! config n question) ; → start sha | nil
(changes/end-turn! config)              ; → files changed (0 when none) | nil; the turn kept as a ref
(changes/turns config id)               ; → [n …] kept, ascending
(changes/rows config id)                ; → [{:turn :ref :before :after :files :subject :at} …]
(changes/row-line row)                  ; → "   3  2 files   2026-11-08T14:03:44  question…"
(changes/diff config id & [turn])       ; → {:turn :ref :before :after :root :files :diff} | {:error}
(changes/rewind! config id turn & [end?])      ; → {:turn :end? :files :saved :from} | {:error}
(changes/undo-turn! config id turn)             ; → {:turn :files :conflicts :saved :from} | {:error}
(changes/undo-rewind! config id & [n])          ; → {:files :saved :undid} | {:error}
(changes/rewinds config id)                     ; → [n …]
(persist/cut-before snapshot k)                 ; → snapshot | {:problem}
(persist/fork! config from-id new-id {:before-turn k}) ; → file | {:problem}
(changes/running root & [id])                   ; → [{:session :turn :pid :at} …], id's own here left out
(persist/note! config id line)                  ; → true | nil (no such session)
(persist/take-notes! config id)                 ; → [line …] | nil, and none left
(session/rewind-line kind result & [extra])     ; → the console line, the prompt resource's words
(session/outside-line line sub)                 ; → that line, said to be done by byj turns SUB
```

A rewind refused for a running turn is `{:error … :running [...]}`.

Session commands: `/rewind [TURN [end] | undo [N]]`, `/undo-turn TURN`,
`/fork [TURN [keep-files]]`. CLI: `byj turns list|diff|rewind|undo-turn|undo|fork`.

## Invariants

| invariant | enforced by |
|---|---|
| A turn's change is git's diff between snapshots of the work tree at its start and end, kept as `refs/byj/turns/<session>/<turn>` with the start as parent: what bash writes is in it, what `.gitignore` names, `.brainyard/` and Emacs's lock, auto-save and backup files are not, nor the person's work from before the turn, nor what came after; a nested repository with no commit does not stop it; HEAD, the index and the stash are untouched; the repository keeps its newest `:changes-turns`; nothing outside git, without a session id or with `:changes?` false. | `changes-test/a-turns-change-is-git-s-diff-between-its-start-and-end`, `changes-test/outside-git-nothing-is-kept`, `session-test/a-turns-writes-are-kept-for-its-review` |
| A same-size edit made in the second the person's index was written is in the turn's end snapshot, however late in the next second the turn ends. | `changes-test/a-same-size-edit-in-the-second-the-index-was-written-is-kept` |
| Every turn is kept, one that changed nothing too; its diff says it changed no files, and "the last turn" means the last that changed files. | `changes-test/a-turns-change-is-git-s-diff-between-its-start-and-end` |
| `byj turns list` lists every kept turn with how many files it changed; `diff` is what `byj emacs changes` says. | `main-test/byj-turns-lists-a-session-s-turns-and-diffs-one` |
| Going back first keeps what it replaces as a rewind ref, the person's work since included, and puts back bytes exactly; a file the target lacks is removed and one it has is made; undoing a rewind puts back what it replaced, and is itself undone the same way; HEAD and the index are the person's still. | `changes-test/a-work-tree-goes-back-to-a-turn-and-a-turn-is-undone-alone` |
| Undoing one turn keeps what came after it; where something later changed the same lines, the file is left with conflict markers and named; a turn that changed nothing has nothing to undo. | `changes-test/a-work-tree-goes-back-to-a-turn-and-a-turn-is-undone-alone` |
| A session's `/rewind` lists, puts back and tells the next turn in the prompt resource's words, and emits `:session/rewind`; a turn not kept and a missing argument are errors that change nothing. | `session-test/the-person-puts-files-back-and-the-next-turn-is-told` |
| A fork cut before turn K holds the history and transcript before K and `:turn-count` K−1, names where it came from, and leaves that session untouched; a turn that is not one, or is inside a `/compact` summary, is refused; entries saved before turns were numbered are counted back. | `persist-test/a-fork-is-cut-before-a-turn` |
| `/fork K` makes that session, puts the files back to K's start, and opens it beside where a front end can. | `session-test/a-session-is-forked-before-a-turn-with-its-files`, `main-test/byj-turns-lists-a-session-s-turns-and-diffs-one` |
| A turn marks its work tree while it runs, and no longer; a rewind, an undone turn or an undone rewind changes nothing while another process's turn, or another session's, runs there, and names it; the asker's own does not hold it back; a mark whose process is gone is removed. | `changes-test/a-turn-running-in-the-work-tree-holds-a-rewind-back`, `main-test/byj-turns-lists-a-session-s-turns-and-diffs-one` |
| What `byj turns` does to a session's files is told at its next turn, saying it came from outside; `/fork K` tells the session forked from at its next turn; a fork that kept the files tells the new session at its first, once. A note is left only for a session there is, and is not a session. | `main-test/byj-turns-lists-a-session-s-turns-and-diffs-one`, `session-test/a-session-is-forked-before-a-turn-with-its-files`, `persist-test/a-note-is-left-for-a-session-s-next-turn` |
| The suite leaves no `refs/byj/` in this repository, nor do named namespaces run through the runner (`jolt -M:test NS…`) or `brainyard.dev/test-ns`. | `test-runner/run` (`own-sessions`) |

## Known gaps

None open.
