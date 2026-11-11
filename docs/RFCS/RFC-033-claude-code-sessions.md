# RFC-033 — Claude Code sessions on the agent page

**Status:** built (`byj-gly.287`): reading a project's Claude Code sessions
(`.1`), listing them on the agent page in a scrollable window (`.2`), running
them in tmux (`.3`), checked live with a real Claude Code (`.4`).

## Purpose

Decides how the full-screen session list — the agent page — shows and opens the
Claude Code sessions of the project it runs in, beside brainyard's own, so the
two kinds of agent working on one project are reached from one place. Decided
with the user on 2026-10-01: each Claude Code session runs in its own detached
tmux session, tmux's own `ctrl-b d` returns to the agent page while it keeps
working, and a `+ new Claude Code session` row starts one.

## Scope

**This layer decides** which Claude Code sessions belong to a project, which are
running now and which are saved, what each is called, and how one is started,
resumed, opened and left.

**It must not know** how the agent page draws them (RFC-028), nor anything of a
Claude Code session's content beyond its title: it is another program's work.

**It reads** what Claude Code leaves under `~/.claude`, which is **not a
published interface** (seen: Claude Code 2.1.282). Everything is read
defensively: what does not parse is skipped, a session whose title cannot be
found is listed by its id, and nothing throws.

## Model

### What a project's sessions are (`brainyard.claude-sessions`, `.1`)

- **Saved:** `~/.claude/projects/<key>/<uuid>.jsonl`, one JSON object a line,
  where `<key>` is the project's canonical path with every character that is
  not a letter or a digit as `-` (`project-key`: `/`, `.` and `_` alike, and
  `/tmp` as `/private/tmp`, as the directories there show). A session's title is
  its newest `ai-title` line, else its first real question — not what a slash
  command leaves (`<command-…>`, `<local-command-…>`), not a meta line, and a
  paste unwrapped from its `<pasted_content>` (`unpasted`) — else its
  `last-prompt`, else none, and the page shows its id. Its age is the file's
  modification time.
- **Read from the ends only.** The files run to tens of MB (12–70 MB here), and
  `ai-title` lines recur through one, the newest usually far past its start —
  in 4 of 6 sessions none was in the first 64 KB. So the first question comes
  from the first `head-bytes` (64 KB) and the title from the last `tail-bytes`
  (128 KB), reached by `skip` (45 ms to the tail of a 70 MB file). What was
  read is remembered by path, modification time and size: 320 ms for this
  project's six sessions the first time, 15 ms after.
- **In its worktrees too** (`byj-gly.290.2`): Claude Code keeps a session
  under the directory it runs in, so one in a git worktree of the project is
  under the worktree's key. `sessions` reads every worktree's
  (`worktree/dirs`, from git's own files) and lists them as the project's, one
  from a worktree naming it as `:dir`; `:dirs` says where to look instead.
- **Live:** `~/.claude/sessions/<pid>.json` — `{pid sessionId cwd name status
  kind updatedAt …}` — whose process is alive (`persist/alive?`) and whose
  canonical cwd is the project's or one of its worktrees'. A live session is listed live and not saved
  again; one with no name takes its saved file's title.

### On the agent page (`.2`)

The session list (RFC-028) gains a **Claude Code** group after brainyard's own
(and a **Codex** group after it, the same way — RFC-049, `byj-gly.430.4`):
`+ new Claude Code session` first, then the live ones — `○` idle, a spinner
when busy, dimmed with "running in another terminal" when this run cannot open
it (`:claude-attachable?`) — and a **Claude Code — saved** group, each with its
size and age (`tui/claude-session-rows`). A session agent$claude drives says so after its
status — its bead, where it stands, its pull request — and one that stopped to
ask is marked `✻` (RFC-039). One an agent$claude subagent of a brainyard session drives
is listed here and under that subagent, below its session (RFC-028,
byj-gly.298.3, byj-gly.304). `run-many!` takes them as
`:claude-sessions` (main reads `claude-sessions/sessions`, at most every 2 s, as
the list is drawn eight times a second) and hands Enter on one of their rows to
`:claude-open` (`.3`); ctrl+x does not end one.

**The whole list is a window now**, brainyard's sessions too: the groups are
laid out as lines, and only the rows the screen has under the header and above
the input are drawn — `↑ N more` above, `↓ N more` below — a window that follows
the mark, moving as little as it can (`tui/list-window`), a heading coming into
view with its first row. ↑↓ move the mark, pgup/pgdn a page, the wheel three
rows.  Before, a long list ran past the screen and pushed the input line off it.

**ctrl+s shares a live Claude Code session** (RFC-043, `byj-1wv.3.14`):
`byj-share run --claude-code <id>` as a child of this process
(`share/claude-sharer`), which follows its transcript and its tmux pane and
writes nothing to Claude Code. The page says the share's links once the relay
has it open; the row reads `shared ·` while it is; ctrl+s again stops it, and
every one stops when byj does.

**ctrl+g shows what a session changed** (`byj-gly.326`). It works on any row:
a brainyard session, live or saved, or a Claude Code session. It reads the
session's directory (`worktree/session-diff`), read-only:
- **In a linked worktree:** everything since it left the main checkout's
  branch, its commits and what is not yet committed, against their merge
  base. It also counts the commits ahead.
- **In the main checkout:** what is not yet committed, against `HEAD`, and
  how many files are untracked. The branch's history there is shared with
  everyone, so it isn't the session's own.

A brainyard session's directory is its snapshot's `:working-dir`. A Claude
Code session's is its listed `:dir`, else the project's. The stat unfolds
under the row as lines of the list, so the mark still moves over rows only.
`ctrl+g` again on the same row hands the terminal to `$PAGER` (`less -R`)
with the whole diff, coloured. There is no diff renderer of our own. A
directory that is gone, or not in git, says so.

### Running them (`brainyard.claude-host`, `.3`)

Each runs in a **detached tmux session of its own, named for its Claude Code
session id** (`byj-cc-<uuid>`). A new one is given its id
(`claude --session-id <uuid>`), so it is named for it from the start; a saved one
is `claude --resume <uuid>`. Enter on a row (`plan`, which runs nothing, then
`run-plan!`):

| row | tmux | no tmux |
|---|---|---|
| `+ new Claude Code session` | start, attach | Claude Code in the foreground |
| saved | start resumed, attach | Claude Code resumed, in the foreground |
| live, running here | attach | — |
| live, in another terminal | refused, and said why on the page | refused |

Attach hands the terminal to `tmux attach-session` (`tui/hand-over!`, as
`$EDITOR` is handed it), and **tmux's own `ctrl-b d`** gives it back: the agent
page returns — straight away, with a blank screen while it repaints, not the
terminal's normal screen, which ncurses' endwin and tmux's own exit each leave
showing; the alternate screen is re-entered and cleared at both moments
(`byj-gly.305`, checked in tmux: about 0.25 s blank, then the page) — and
Claude Code keeps working — listed live, after byj quits
too. Without tmux, Claude Code has the terminal until it exits. Run inside tmux,
the attach runs without `TMUX` (`attach-env`), since tmux refuses to nest; the
outer tmux then has `ctrl-b`, and `ctrl-b ctrl-b d` reaches the inner one. So
does every other tmux command here, the start and the listing too: run inside
a tmux server that is not the default, a start with `TMUX` set had gone to that
server and the attach to the default one, which had no such session, and Enter
did nothing (`byj-gly.306`).

A session running in this run's tmux is listed live and attachable whether or
not Claude Code has written its own record yet — it has not while it starts —
and leaves the saved group (`with-running`). Only this project's are added:
those tmux started in the project directory or under it, read from
`#{session_path}` (`running-in`). Every `byj-cc-*` session had been listed on
every project's page, so a scratch directory showed four of this repository's
running sessions (`byj-gly.310`). The command is
`:claude-command` (`claude`; a command with arguments, `npx claude`, is split on
spaces). The tmux command is `agent-host/*tmux*`, which a test binds to a server
of its own (`tmux -L <label>`) so it never touches the person's: since
`byj-gly.430.3` the tmux side — the command, `attach-env`, the listing,
starting, stopping, the screen and keys — is `brainyard.agent-host`'s, shared
with Codex's host, and `claude-host` is Claude Code's adapter to it (RFC-049).

### Checked live (`.4`, 2026-10-01, Claude Code 2.1.283)

In tmux, a throwaway project, no prompt sent (so no model call): Enter on
`+ new Claude Code session` attached a real Claude Code in `byj-cc-<id>`, which
asked to trust the folder. Its own record (`~/.claude/sessions/<pid>.json`)
carried `sessionId` = the id given with `--session-id` — the same as the tmux
session's — `status "idle"` and `name "proj-9a"`. `ctrl-b d` returned to the
agent page, which listed it `○ proj-9a idle`, attachable; Enter attached it
again; `/exit` ended Claude Code, its tmux session with it, and its row. A
session that never sent a message is not saved by Claude Code, so it does not
reappear as saved.

### When Claude Code's format changes

Decided not to build a detector. What changes shows itself: a session whose
title cannot be read is listed by its id, one whose record cannot be read is
not listed live, and nothing throws — a visible, harmless degradation, which is
the signal. A check that the format is still the one known would itself be a
guess at an undocumented format, and wrong in its own way the day it changes.
What this was written against is recorded above (2.1.282, 2.1.283).

## API

`brainyard.claude-sessions`: `sessions` (`{:live [{:id :pid :title :status
:kind :updated-at}] :saved [{:id :title :updated-at :bytes}]}`, newest first),
`live`, `saved`, `read-saved`, `unpasted`, `last-turn` (RFC-039), `project-key`, `claude-home`, `head-bytes`,
`tail-bytes`. `brainyard.tui`: `claude-session-rows`, `list-window`;
`run-many!`'s `:claude-sessions`, `:claude-open`, `:claude-attachable?`;
`hand-over!`. `brainyard.claude-host`: `plan`, `run-plan!`, `running`,
`with-running`, `running-in`, `tmux?`, `tmux-name`, `attach-env`, `prefix`.
`brainyard.agent-host`: `*tmux*` and the tmux side both hosts share (RFC-049).

## Invariants

| Invariant | Enforced by |
|---|---|
| What a session changed is read where it works: a worktree since it left the main branch, its commits and uncommitted work; the main checkout's uncommitted work and untracked count; a gone directory or one outside git said; the pager run in its directory, quoted. | `worktree-test/what-a-session-changed-is-read-where-it-works` |
| ctrl+s on a live Claude Code row shares it through the relay (RFC-043), again stops it; the row says `shared ·` while it is; no other row offers it. | `tui-test/ctrl-s-shares-a-live-claude-code-session-and-the-row-says-so` |
| ctrl+g on any row unfolds what its session changed under it — a brainyard row by its session, a Claude Code row with its own directory — and the mark still moves over rows only. | `tui-test/ctrl-g-unfolds-what-a-session-changed-under-its-row` |
| A running tmux session is added to a project's list only when tmux started it in that directory or under it (a worktree) — not in one whose name merely starts the same — read as tmux 3.2 and 3.3 print a `-F` line too, a tab as `_` (`byj-gly.450`). | `claude-host-test/only-this-projects-running-sessions-are-listed`; `terminal-test/tmux-sessions-are-read-as-tmux-3-2-prints-them` |
| Every tmux command of this layer — the start, the listing, a read of the screen, a key, the attach — runs in one environment without `TMUX`, so all reach one server. | `claude-host-test/every-tmux-command-reaches-one-server` |
| A project's directory name is its canonical path with every non-alphanumeric character as `-`. | `claude-sessions-test/a-project-path-becomes-claude-codes-directory-name` |
| The Claude Code rows: the new-session row first, then the live — marked when this run cannot open it — then the saved, with size and age. | `tui-test/claude-codes-sessions-are-rows-of-their-own` |
| A list window keeps the mark in view, moving as little as it can, never past the end. | `tui-test/a-list-window-keeps-the-mark-in-view` |
| A long session list is a window: what is above and below counted, the input line on screen, the window following the mark (a heading with its first row), pgdn a page, the wheel three rows, Enter on a Claude Code row handed to `:claude-open`. | `tui-test/a-long-session-list-is-a-window` |
| Enter plans: a new session started with the id it is given and attached; a saved one resumed in a tmux session of its own; a live one running here attached with nothing started; one in another terminal refused with why; no tmux, Claude Code itself in the foreground; a command with arguments split. | `claude-host-test/enter-on-a-row-plans-start-and-attach` |
| A session started runs in its own tmux session, named for its id, is listed, and the terminal is handed to the attach outside any tmux it ran in; running, Enter attaches without starting another (a tmux server of the test's own). | `claude-host-test/a-session-runs-in-tmux-and-is-left-running` |
| A session running here is listed live before Claude Code records it, leaving the saved, never doubled. | `claude-host-test/a-session-running-here-is-listed-before-claude-code-records-it` |
| Live: this project's, with a live process, newest first, titled by its name or its file; saved: the rest, titled by the newest `ai-title` from the tail, else the first real question from the head on one line, else listed untitled; a file read once is read again only when it changes; no `~/.claude` is no sessions, and nothing malformed is fatal. | `claude-sessions-test/a-projects-sessions-are-read-live-and-saved` |
| A project's sessions include those in its git worktrees, saved and live, each naming its worktree as `:dir`. | `worktree-test/sessions-in-a-projects-worktrees-are-its-own` |
| A session whose first question was pasted is titled by the paste, unwrapped; other markup is not unwrapped. | `claude-sessions-test/a-session-begun-with-a-paste-is-titled-by-it` |
| Attached from inside another tmux, the inner session's status line says how to detach it — the outer prefix twice, read from that tmux — since `ctrl-b d` would detach the outer one; outside tmux nothing is said. | `claude-host-test/enter-on-a-row-plans-start-and-attach` ("nested in tmux"), `claude-host-test/a-session-runs-in-tmux-and-is-left-running` |

## Known gaps

- Claude Code's format is not a contract: a change to it leaves sessions
  listed by id, or not listed live, until this reader follows (decided above:
  that is the signal; no detector).
