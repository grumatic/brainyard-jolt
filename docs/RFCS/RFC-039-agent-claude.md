# RFC-039 — agent$claude: delegating to a Claude Code session

**Status:** built (`byj-gly.290`): the transport decided and measured (`.1`),
the mechanism (`.2`), the agent (`.3`), bead-to-PR (`.4`), the agent page
(`.5`, `.8`), the push guard (`.7`), and checked live (`.6`); publishing
handed to the caller (`byj-gly.307`).

## Purpose

Decides how brainyard hands a piece of work — usually a bead — to Claude Code
and sees it through to a pull request: which Claude Code session does it, how
that session is briefed and guided, how brainyard tells that it is working,
done, or waiting on a person, and what it reports back. Decided with the user
on 2026-10-07: the session is an interactive Claude Code in tmux (RFC-033), so
it is on the agent page and can be attached at any time; it runs with
`--permission-mode acceptEdits` — changed to `auto` the same day, after the live
check (`byj-gly.290.9`, below); the person is asked before anything is pushed —
replaced on 2026-10-08 (`byj-gly.307`, below): nothing asks; the work is handed
back to the caller to publish, unless the task tells the session to.

## Scope

**This layer decides** which session takes a task (a new one, a live idle one,
a saved one resumed), with which model and options, in which worktree; what it
is told; when it is done, stuck or waiting; and what is reported.

**It must not know** how to do the task — that is Claude Code's — nor how the
agent page draws a session (RFC-028, RFC-033).

**It reads** what Claude Code leaves under `~/.claude` (RFC-033: not a
published interface) and what its tmux pane shows.

## The decision: an interactive session, driven through tmux

Four ways to reach Claude Code were weighed:

| transport | attachable live | structured | chosen |
|---|---|---|---|
| interactive Claude Code in tmux, typed into with `send-keys` | yes | via its live record and transcript | **yes** |
| `claude -p --resume <id> --output-format stream-json` per round | only between rounds | yes | fallback without tmux |
| `claude --bg` and `claude agents/attach/logs` | in Claude Code's own manager, not our tmux | partly | no |
| ACP (brainyard's `acp-agent :claude-code`) | no | yes | no — divergence, `.3` |

The live record also names a `messagingSocketPath` and `peerFeatures`
(`notify_idle`): undocumented, and not built on.

### What was measured (`dev/brainyard/dev/claude_agent_spike.clj`)

Claude Code 2.1.291–2.1.292, haiku, 2026-10-07: three runs of the spike, plus
the hand probes it was written from. A run spends about $0.05.

- **The parent's environment must not reach the child.** A brainyard running
  inside a Claude Code session carries 13 `CLAUDE*` variables
  (`CLAUDECODE`, `CLAUDE_CODE_SESSION_ID`, its messaging socket …); the child is
  started under `env -u` each of them.
- **Folder trust.** A folder Claude Code has not been told to trust shows its
  trust dialog before anything else — **with the cursor on "No, exit"**, and
  before Claude Code writes its live record, so the record cannot say it is
  there; only the pane can. A key sent as the dialog is first drawn is lost
  (the first spike run sent Down then Enter 300 ms apart, and the Enter exited
  Claude Code). What works: Down until the pane shows the cursor on "Yes, I
  trust this folder" (2 Downs, ~1 s, in each run), and only then Enter. A **git
  worktree of a trusted repository shows no dialog**, whether it is inside the
  repository (`.brainyard/wt`) or beside it — so a project already trusted
  needs nothing for its worktrees.
- **The brief goes in as Claude Code's argument.** A 61-line prompt given as
  the last argument arrives whole, as the person's own words, and was answered
  correctly.
- **Typed input: one short line.** `send-keys -l` then Enter started the turn
  every time, with one Enter; the record said busy 1–54 ms later.
- **Pasted input is not the person's.** A bracketed paste (`paste-buffer -p`)
  of four lines or more is recorded as `<pasted_content>…</pasted_content>`,
  and the model treats it as data: asked inside one to reply in three given
  lines, it called that a prompt injection and declined. An unbracketed paste
  of two lines arrived as typed text — but **an unbracketed paste of 61 lines
  arrived as its last 15, cut mid-line** (all three runs), and took a second
  Enter. So guidance is typed, one short line, and anything longer is written
  to a file the line points at: "Read .brainyard/guidance-1.md and follow it"
  was followed (it wrote the file and ran the command the file asked for).
- **The live record's status is `idle`, `busy` or `waiting`.** `waiting` is a
  permission dialog on screen (seen twice by hand: a write and a Bash command
  under `~/.claude`, which Claude Code asks about even under acceptEdits). The
  digit of a choice answers it; `3` (No) ended the turn as interrupted, back to
  `idle`. In the spike haiku would not call `git push` however it was asked,
  so the scripted probe never reached the dialog; `.4` covers the push with the
  PreToolUse gate rather than with this dialog.
- **`idle` is not "done".** The status can say idle mid-turn: a typed turn read
  idle after 790 ms, and the next message, sent then, was queued behind the
  turn still running and answered after it. And **the transcript lags the
  record**: the turn appeared in it 2.9 s after the record said idle. So a turn
  is over when the record has said idle for a while **and** the transcript
  holds a reply after the last prompt — not at the first idle.
- **acceptEdits** let Claude Code `touch` a file in the project with no dialog.
- **Observing.** `eval-claude/run` over the live transcript gave each turn's
  answer and cost mid-session ($0.045 for the run's four turns, all priced).
  It also showed that `eval-claude/prompt?` does not count a pasted prompt as
  a prompt — it begins `<pasted_content` — and folded it into the turn before
  (fixed, `byj-gly.288.41`).
- A session in a worktree keeps its transcript under the **worktree's** project
  key (`…-proj--brainyard-wt`), so RFC-033's listing, which read only the
  project's own key, did not see it (fixed in `.2`).

## Model

### Driving a session (`brainyard.claude-host`, `.2`)

Mechanism only: what to say to a session, and when, is the agent's loop (`.3`).

- **Start** (`start!`, argv by `start-argv`): Claude Code in tmux
  `byj-cc-<id>`, cwd the worktree, under `env -u` each of the parent's
  `CLAUDE*` variables (`inherited`), with `--session-id` (or `--resume`),
  `--model`, `--permission-mode`, `--settings` when given, then extra `:args`,
  then the brief as the last argument. A session already running is refused,
  not started twice.
- **Ready** (`ready`, a Task): up once Claude Code has written its live record;
  on the trust dialog, Down until the screen shows the cursor on "Yes"
  (`cursor-on-yes?`), and only then Enter; an exit or 20 s is `{:error}`.
- **Type** (`type-line`, a Task): one line, typed with `send-keys -l --` (a
  line may start with `-`), then Enter — again while the record does not say
  busy within 2 s and the transcript does not hold the line, up to three
  times. A turn can go busy and idle again between two of the record's
  100 ms polls; it has still started when the transcript's newest prompt is
  the line and the file has grown since before typing (or moved on to a new
  id), so no Enter is pressed into a session that took it (byj-gly.419,
  provenance P-42). Refused unless the record says idle, and refused for more
  than one line. A line that ends the session (`/exit`) says `:gone?` when
  the session has gone by then.
- **Observe** (`observe`): the record's status and the newest turn of the
  transcript, `claude-sessions/last-turn` — `{:prompt :answer :ended?}`, read
  incrementally: each call parses only the complete lines added since the last
  (the first read is the file's last 8 MB). A fixed tail does not work: live,
  Claude Code wrote a 174 KB `prompt_snapshot` line after the reply, and a
  128 KB tail never reached the turn — the first live run of `await-settled`
  waited out its 120 s for that reason.
- **A session continued under a new id** (`current-turn`, `byj-gly.290.10`).
  Claude Code now and then goes on with a session under a new session id in
  the running process: the old transcript's last word on it is
  `{"type":"continued-in","continuedInSessionId":…}`, and every later turn
  is in the new id's transcript, while the live record keeps the first id.
  Seen 5 times in this machine's transcripts; `byj-cc-562b6368` did it five
  minutes after it started, after `/auto-mode-setup` saved its settings.
  Reading only the first transcript, agent$claude never saw the reply to the
  task it had typed and waited for good. `observe` follows the chain (at most
  `max-continuations`, 8) and says the id it read as `:transcript-id`; a
  continued transcript not written yet has no turn, so it is never taken for
  done; `:usage` is the whole chain's, so a turn's cost holds across the
  switch. The switch moves the work to another process: the pane's
  `claude --resume` stays as the front end, a `claude bg-spare` runs the
  turns under the new id with a live record of its own, and the first record
  stops changing — live, `byj-cc-562b6368`'s still said idle from eleven days
  before, so a typed line read as never starting a turn. `record` is the
  newest id's when it has one, so `type-line`, the dialog wait and
  agent$claude's own checks read the status that moves. Checked live on that
  session, 2026-10-12: a typed line seen busy at the first Enter, and its turn
  settled from the new transcript 4.5 s later.
- **A turn's end** is a reply whose `stop_reason` ends it, an interruption, an
  API error — or `system`/`turn_duration`, which Claude Code writes once
  every turn is over (1,242 of them, one per turn, in this machine's recent
  transcripts), so a turn that ends with no `end_turn` reply, after a
  compaction or a hook, still reads ended (`byj-gly.290.10`).
- **Settled** (`settled?`, `await-settled`, a Task): idle AND the transcript
  holds a reply that ended the turn after the newest prompt, held for
  `settle-ms` (1.5 s; `await-turn` takes `:claude-settle-ms`, which tests
  of the stand-in set to 300 ms) — `:done`; `:waiting` at once when a permission dialog is
  up (with the `:screen`); `:gone` when the session is not running. It never
  ends otherwise: the caller gives it `fx/timeout`.
- **Choose** (`choose`, a Task): a numbered choice on the dialog, then wait
  until the record no longer says `waiting`, since a wait begun at once would
  see the dialog still up.

Checked live (2026-10-07, Claude Code 2.1.292, haiku, a fresh folder): ready
past the trust dialog in 1.9 s; the brief's turn settled at 7.4 s with its
answer; a typed line started a turn with one Enter and settled at 12.5 s.

### A project's worktrees (`brainyard.worktree`, `.2`)

`add!` makes one on a new branch (teams' `add-worktree!` before it was
shared); `dirs` lists them from git's own files (`.git`, `commondir`,
`worktrees/*/gitdir`), not by running git, since the agent page asks every
2 s. `claude-sessions/sessions` lists the sessions of every worktree as the
project's, each from one that is not the project naming it as `:dir`
(RFC-033).

### The agent (`agent$claude`, `.3`)

A built-in agent (`resources/brainyard/agents/claude.edn`) whose own loop is
the whole agent (RFC-022): no model turn of its own calls a tool. Its config
sets `:claude-tools?`, which is what registers the `claude$*` commands
(`brainyard.claude-agent`) — for its turn only, so no other turn pays for them.

| step | what | where |
|---|---|---|
| list | the sessions a task could go to: running here, in another terminal, saved; which this instance already drives (`YOURS`), with its task and branch | `claude$sessions` |
| plan | the session (`new`, or an id), model, extra CLI options, worktree and branch, the brief, what done looks like; a `choice` when the session it continues waits on a dialog | predictor `claude/plan` |
| start | the person's `choice` to the dialog (`claude$choose`), or the brief: a new session (in a worktree of its own on the branch, `.brainyard/worktrees/<branch>`), one running here, or a saved one resumed (`claude$open`). One running elsewhere — a terminal of its own, a tmux pane byj did not start — is neither resumed, which would be a second process on one session, nor driven: the refusal says so and names `session$send claude:<id>`, which hands it a task when it runs in tmux (RFC-031, byj-gly.429) | commands |
| watch | each turn, up to `:claude-max-rounds` (8): wait for it (`claude$await`, at most `:claude-turn-timeout-ms`, 30 min), judge it (`claude/progress`: `done`, `guide` with the next instruction, or `ask-person`), and say the guidance (`claude$say`) or stop | the loop |
| end | the verdict and report recorded (`claude$finish`); the answer is Claude Code's last reply, then the report | the loop's `:cc/compose` |

- **Its answer is what Claude Code said**, at the end of its last turn, then a
  blank line and the supervisor's report — where the work stands, what it
  did, the session and branch; `ask-person`'s report ends with the one
  question. Until 2026-10-14 the report alone was the answer, and a caller
  asking the agent a question got a summary of the reply instead of the
  reply (`byj-1wv.15`'s callers over A2A).
  Asked again — `agent-registry$ask`, the same instance — the plan finds its
  own session marked `YOURS` and continues it: a follow-up as the next message,
  or, when it is waiting on a dialog, the person's answer as the `choice`.
  `/claude` (RFC-004, `byj-gly.361`) is built on this: the session's agent
  briefs the instance, judges each answer with its own tools, and asks the
  same instance again for what is missing, so the follow-ups go to one Claude
  Code session.
- **Guidance** is typed when it is one line of at most 300 characters;
  otherwise it is a file under `.brainyard/claude-agents/<id>/`, which the
  session may read (`--add-dir`), and the typed line points at it.
- **Cost:** the turn reader counts each reply's tokens once, by model;
  `claude$await` prices them and reports what the delegation has spent since
  it began (`:cost-usd`) and since it last said (`:usage-cost-usd`), which
  the calling turn counts in its own usage (`agent/account-tool-cost!`,
  byj-1wv.17): the turn hosting a delegation had carried no usage, so a
  share of the claude agent capped its callers' cost at nothing. Against
  `:claude-max-cost-usd` ($5), and past it the verdict is
  `ask-person`.
- **The record**, `.brainyard/claude-agents/<id>.edn`: task, instance, the
  session that delegated it (`:parent-session`, byj-gly.298.1), worktree and
  branch, round, state (`working`, the last settle, `done`, `needs-person`),
  cost, verdict and report — for the agent page (`.5`).
- **Its turn is in the delegating session's trajectory**, as every child's is:
  the loop ends with `:react/store-trajectory`, so the session that delegated it
  can list it among its children (byj-gly.298.1).
- **It runs as long as the work does.** Called by a model, `agent$claude` is a
  tool call like any other, so past `:fast-eval-timeout-ms` it becomes a
  background task (RFC-019); nothing here backgrounds it.
- The child is started with `:claude-permission-mode` (`auto`, `.9`) and
  `:claude-settings` when set — for brainyard's gates as its PreToolUse hook
  (`byj hook settings`, RFC-012), merged into the settings that deny it
  pushing (`.4`) — unless the task tells it to publish (`byj-gly.307`).
- Its model is the one the plan names (`claude/plan`'s `:model`), else
  `:claude-model`, else Claude Code's own default, which is the account's.
  Left unset, the dogfood simulation's mode B ran on Opus against mode A's
  Sonnet, so the comparison measured two models as well as two harnesses
  (`byj-gly.320`).

### What it is doing, as it does it (`byj-gly.299`)

Every step of the loop is drawn as a call in agent$claude's view (RFC-022,
RFC-028), and while `claude$await` waits on a round, what Claude Code does
streams under it: the transcript reader (`claude-sessions/last-turn`) keeps its
newest steps as `:activity` — a tool call by what it works on
(`Bash(python3 -m unittest)`, `Edit(mathx.py)`), text by its first line, each
numbered — and `claude$await` passes the new ones up as step progress through
`claude-host/await-settled`'s `:on-observe`. Checked live (Claude Code 2.1.292,
haiku, bead `lc-fdp`): the plan, the open, eight lines of Claude Code's work
over the 17 s round — reading the bead, two edits, the tests, the commit, its
report — then the judgement, the PR text and the declined publish, each as it
happened.

"New" is counted from where the session stood when it was last spoken to or
last waited on, kept in the delegation's record as `:activity-mark` (the
transcript the steps are numbered in, and the newest number): `claude$say`
sets it before it types, a new session starts at 0, and each wait leaves it
where it stopped. Counting from where the session stood when the wait began
lost a reply that landed between `claude$say` and the wait, which the
suite's load made happen once (`byj-gly.482`).

### Bead to pull request (`.4`)

Decided with the user on 2026-10-07: the person is asked before anything is
pushed.

- **The session cannot push.** It starts with `--settings` (`session-settings`):
  `:claude-settings`, if any, with `denied-in-session` added to its
  `permissions.deny` — `git push`, `gh pr create`, `gh pr merge`, `gh pr
  ready`. Claude Code refuses those outright, with no dialog: checked live
  (Claude Code 2.1.292, 2026-10-07), `gh pr create --help` was refused as
  `toolDenialKind: permission-rule` and the turn ended normally. They are prefix
  rules, so `git -C dir push` passes them; a `PreToolUse` hook, `publish-guard`,
  catches the rest (`byj-gly.290.7`): a bash script that reads the whole
  command line and denies `git … push` past git's own options (`-C`, `-c`,
  `--…`), a path before the program, or `gh … pr create|merge|ready`, wherever
  in the line — after `&&`, inside `sh -c "…"`, on a later line. A tripwire, as
  `:bash-deny-commands` is: `echo git push` is refused too. Bash, not a jolt
  process, because it runs before every Bash call the session makes. Checked
  live (2026-10-07): `git -C . push --dry-run` was refused with the guard's
  reason, and Claude Code reported it and stopped.
- **Finished work on a branch of its own is handed back** — the tree's
  `:cc/hand-off`, when the verdict is `done` and the delegation has a branch
  (one is recorded only when its worktree is on it). `byj-gly.307` replaced
  what was here (below); `.4` asked the person before every push.
- **The bead is noted** by `claude$finish`, when the plan named one
  (`claude/plan`'s `bead`) and the project keeps beads: whether the work
  finished or needs a decision, the branch, the worktree and the pull request —
  facts, never the report (`bd note`, `:claude-bd-command`).

### Publishing is the caller's (`byj-gly.307`, 2026-10-08)

Decided with the user on 2026-10-08, replacing `.4`'s ask: asking for
permission is for what a person must decide, not a step of an automated
workflow. Nothing in a delegation asks to publish.

- **By default the caller publishes.** The delegated session cannot push (the
  deny rules and `publish-guard`, above). Predictor `claude/pr` writes the
  title and description — what changed, how it was checked, what is left, and
  never who or what made it — and `claude$handoff` (`handoff`) adds to the
  report: the commits past the base (the project's current branch), the files
  they change (`git diff --stat`), anything uncommitted in tracked files, and
  the commands that publish it — `git -C <worktree> push -u <remote>
  <branch>`, then `gh pr create … --body-file <records>/<id>/pr-body.md`, or,
  when the branch's pull request is already open (`gh pr view`), that the push
  updates it — and the ones that remove the worktree and branch after — the Claude Code
  session first, when it is running (`tmux kill-session`), since it works in
  the worktree. It stays saved and resumable. In the dogfood simulation's
  mode B, the caller removed the worktree as told, and the session stayed up
  in a deleted directory, listed live (`byj-gly.315.20`). The
  caller (the root agent) runs them, or asks its own person; nothing is pushed
  here and no one is asked.
- **A session left with nothing to work for is stopped** (`stop-stranded!`,
  `byj-gly.318`). Two cases count. The first is a session whose worktree is
  gone. This is checked at the end of each of the delegating session's turns:
  a caller that writes its own clean-up command instead of the hand-off's
  removes the worktree with the session still in it. The second is a session
  still `working` when the session that delegated it closes. Its task was
  cancelled, so no one would read its report, and in the simulation it
  stopped at a permission dialog that no one answered. Each is stopped saved,
  so it can be resumed. Its record is marked `stopped` with `:stopped-why`.
  The close says what it stopped, and a turn end emits `:claude/stopped`. A
  session another session delegated, or one already done, is left alone.
- **What a session left running in a worktree since removed is stopped**
  too, once for each such worktree (`processes-in`, `byj-gly.321`).
  Brainyard tracks its own bash calls' background processes, but not Claude
  Code's. In the simulation, a server whose `kill %1` missed kept listening
  after both the session and its worktree were gone. A process whose working
  directory is inside a delegation's removed worktree is found with `lsof`,
  which still names a deleted directory. It is sent TERM, and the record is
  marked `:swept`. Only a directory under `.brainyard/worktrees` counts; the
  project itself is never swept.
- **When the task tells the session to publish** — `claude/plan`'s `publish`,
  true only when the task explicitly says to push or open a pull request — the
  session starts with `session-settings`' publishing variant: `gh pr merge`
  still denied (`denied-when-publishing`), no guard, in a file of its own so a
  session started meanwhile without the right does not read it. The brief tells
  it to push, open the pull request with `gh pr create`, never merge, and give
  the URL; `claude/progress` holds it not done until the report has one. A
  session running here without the right, continued to publish, is stopped
  (`claude-host/stop!`) and resumed with it — once its turn is over: Claude
  Code reads its settings when it starts. The record keeps `:publish`.
  `claude/pr` is not run; `claude$handoff` reports what was published — the
  push, as the remote-tracking ref has it, and the pull request `gh` finds —
  or what is missing, with the commands that finish it.
- **Clean-up is agent$claude's, after.** With `claude/plan`'s `cleanup`, true
  only when the task explicitly asks, `claude$handoff` removes the worktree
  (`git worktree remove`, its session stopped first) and the local branch
  (`git branch -D`) — only once the pull request is open, the remote has the
  branch's last commit, and nothing in tracked files is uncommitted. A
  worktree holding files git does not track is left, the report naming them
  and the command that removes it anyway: they may be work.

Checked live (`byj-gly.308`, Claude Code 2.1.294, sonnet, auto mode, the
throwaway repository; four runs, $0.27 in all, nothing asked for permission):

1. **"Work on bead lc-876"** — one round, 36 s: a commit on `bd/lc-876`, and
   the hand-off with the commit, the diffstat and the commands. Two fixes from
   it: the `gh pr create` line now starts `cd <worktree> &&` — `gh` finds the
   repository from where it runs, and the caller's directory is another
   repository — and the diffstat's first line kept its indentation. **The commit
   carried `Co-Authored-By: Claude …`**: Claude Code adds it by default. A
   delegated session now starts with Claude Code's `attribution` setting
   emptied (`no-attribution`: `commit` and `pr` `""`, `sessionUrl` false)
   unless `:claude-settings` sets it or `:claude-attribution?` is on.
2. **"Push the work … open a pull request, then clean up"**, the session
   running here without the right — stopped and resumed with
   `session-settings-publish.json` (its argv showed it), but **auto mode's
   classifier refused** `git push … && gh pr create …` as "External System
   Writes" (provenance P-33). `allowed-when-publishing` now allows `git push`,
   `gh pr create` and `gh pr view` outright for such a session.
3. Again — pushed, and **PR #3 opened**, its title, description and commit
   free of attribution. But the brief had given the session the clean-up, which
   the classifier refused as destructive, and the judge stopped to ask.
   `claude/plan` now never gives the session the clean-up, and
   `claude/progress` does not hold it from done for one.
4. Again — the session continued, found the pull request open, done;
   `claude$handoff` reported it published and removed the worktree and the
   local branch, stopping the session; the branch stayed on the remote.

### On the agent page, and after a restart (`.5`)

- **Each delegated session says so on its row.** `main` reads the run records
  with the sessions, every 2 s as the list is drawn, and
  `claude-agent/with-delegations` puts each record's `{:bead :branch :state
  :round :pr-url :verdict}` on its session as `:delegation`.
  `tui/claude-session-rows` adds `tui/delegation-label` to the row — `agent$claude
  byj-9 · needs you`, `… · working`, `… · done · PR open` — and a delegation
  that stopped to ask is marked `✻` in yellow, as a session awaiting input is,
  and counted with them in the page's header (`tui/delegation-asks?`,
  `tui/session-counts`, `byj-gly.290.8`).
  Attaching is unchanged: Enter on the row, `ctrl-b d` back (RFC-033).
- **After a restart** the agent is a new instance, so no session is `YOURS`.
  Its tmux sessions are still running and listed "running here"; a saved one
  can be resumed. `claude$sessions` shows the earlier delegation on each —
  `delegated earlier for byj-9, needs-person` — and `claude/plan` continues it
  for the same bead or work rather than starting over.

Rendered (90×22, three delegated sessions and one of the person's own):

```
Claude Code
›+ new Claude Code session
 ✻ fix the parser           idle · agent$claude byj-9 · needs you                       1m
 · refactor tui             busy · agent$claude byj-12 · working                        5s
 ○ my own session           idle                                                        0s

Claude Code — saved, enter resumes one
 ↺ retry budget             2.4 MB · agent$claude bd/byj-3 · done · PR open             2h
```

### Checked live (`.6`, 2026-10-07, Claude Code 2.1.292)

A throwaway Python repository (`/tmp/byj-livecheck`, beads prefix `lc`) with a
private GitHub origin of the person's, and one bead: add
`clamp(x, lo, hi)` with tests. agent$claude ran its own tree and config as the
roster loads them, predictors on sonnet, the session on haiku as the task asked;
every push request was relayed to the person in chat.

| run | task | what happened |
|---|---|---|
| 1 | "Work on bead lc-iv7 … on model haiku" | planned a **new** session, `--model haiku`, in a **worktree** `.brainyard/worktrees/bd-lc-iv7` on `bd/lc-iv7`, bead `lc-iv7`; the brief read well (bd show first, commit on the branch, do not push, report). Claude Code stopped on a dialog to run `bd show` (acceptEdits asks for every command that is not an edit): `waiting` → `ask-person`, with the question in the answer. |
| 2 | the person's yes | the plan found its session `YOURS`, waiting, and answered the dialog with **choice** 1; the turn carried on (tests written) and stopped on the next dialog (`python3 -m unittest`). |
| 3 | yes, and an allow list | the session was stopped and **resumed** with `:claude-settings` allowing `bd show/update/close/note`, `python3 -m unittest`, `git add/commit/status/diff/log`; it finished (1 commit, suite green). `claude$publish` **asked**; on yes it pushed and **opened PR #1** — title and body clean, no attribution. The bead was **noted** with the branch and PR. |
| 4 | a follow-up in the same session | **continued** the session (`how: continued`, the brief as a file it was pointed at); lerp, its tests and the README done in one turn; asked, pushed, and **found PR #1** rather than opening another (two commits on it). |

Cost, priced from the transcript: $0.06 for runs 1–2, $0.03 for run 3; four
rounds in all. **A `guide` verdict never came up**: each of Claude Code's turns
ended either done or on a dialog, so the nudge path ran only as the
continuation's typed pointer (`claude$say`, twice, via a file), not as a
judged nudge — that path is tested against the stand-in alone.

Found and fixed on the way:

- **The brief never arrived** (run 1, first try): `--add-dir` takes several
  values, and took the brief for one; the session sat at its prompt with no
  turn. `start-argv` now puts `--` before the brief.
- **A resumed session refused its brief**: Claude Code writes its live record
  before it says `idle`; `open` now waits for idle after a resume (the stand-in
  does the same now).
- **The report said "nothing was pushed"** and the line after it said the PR was
  opened: `claude/progress` writes before publishing, and now says nothing of it.
- **"uncommitted changes were not pushed"** for a plugin's untracked `.omc/`:
  only tracked changes count now.
- **The second ask said "open a pull request"** for one already open: it now
  says it updates it.
- **`--settings` is read once.** Claude Code did not take up an allow list
  added to the file mid-session; settings for a delegation are fixed at its
  start (or resume). And under `acceptEdits` every non-edit command asks, so
  each was a round trip to the person — which `.9` answers with auto mode.

### Auto mode (`.9`, 2026-10-07, Claude Code 2.1.292)

Asked by the user after the live check: why not auto mode? `acceptEdits` had
made every routine command a dialog, and every dialog a round trip to the
person — three before a one-function change was committed. In auto mode
Claude Code's own classifier passes ordinary actions and refuses dangerous ones
(force-push, history rewrite, deleting what existed before the session,
credential leaks, merging without review). `:claude-permission-mode` is `auto`
now.

- **Pushing stays out of the session.** Auto mode's own rules count a push to the
  session's repository as ordinary; the session's deny rules and
  `publish-guard` refuse it whatever the mode (unless the task tells it to
  publish, `byj-gly.307`). Checked live in auto mode: `gh
  pr create --help` and `git -C . push --dry-run` were both refused by the
  guard.
- **Auto mode is not offered with every model.** With haiku,
  `--permission-mode auto` started Claude Code in **manual** mode — its record
  said `"permissionMode":"default"` — which asks before edits too; with sonnet
  it was auto. Shift+tab cycles manual → accept edits → plan on haiku, auto
  never among them. So `claude-host/ensure-mode` reads the mode from the
  session's footer once it is up and, when auto was asked for and is not there,
  presses shift+tab until it shows accept edits (`:wanted` in its result); the
  record keeps the mode in force, and `:where` says why it is not auto.
- **Live, on sonnet:** bead `lc-hjp` (`mean(xs)` with tests) went from brief to
  finished in one round with no dialog at all — `bd show`, the tests, the
  commit — then asked to publish. The first time, the commit had swept in a
  plugin's state (`.omc/`, from the person's Claude Code setup) with `git add`;
  the person declined, nothing was pushed, and the answer said where the commit
  waits. `claude/plan` now tells the session to stage the files it changed by
  name, never `git add -A` or `.`; run again, the commit held only the two
  files, the person said yes, and PR #2 opened with them.

### Allowed without asking (`byj-gly.292`, 2026-10-07)

Asked by the user after `.9`: a delegated session is allowed read-only and
local commands outright, so neither a model without auto mode nor auto mode's
classifier spends a step on them. The list is data,
`resources/brainyard/claude/allow.edn`: read-only `git` (status, diff, log,
show, branch, rev-parse), `ls cat head tail wc grep rg`, read-only `bd` (show,
list, ready, where, prime), and local writes — `git add`, `git commit`, `bd
note`, `bd update`. Absent on purpose: `find` and `sed` (they can write),
`xargs env sh -c bash -c` (they run what follows), `git checkout reset clean
stash rebase`, `rm`, `mv`, and test runners, which run the project's code and
differ per project. `session-settings` puts `:claude-settings`' own allows
first, then the list (`allowed-in-session`: unless `:claude-allow-defaults?`
is false), then the project's `:claude-allow`; the deny rules, which Claude
Code puts above any allow, and `publish-guard` are unchanged.

Checked live (haiku, so accept edits):

- **A chain is not let through by its first command.** `cat README.md && rm
  -f <probe>` put up a dialog (`waiting`); the probe file was still there.
- **A delegation with the list and the project's test command**
  (`:claude-allow ["Bash(python3 -m unittest:*)"]`), bead `lc-v8c`: one round,
  no dialog — `bd show`, `python3 -m unittest -v`, `git add … && git commit`,
  `git log` all passed; the commit held only the two files it changed. Asked to
  publish, the person said no; nothing was pushed.
- Claude Code passes a plain `find . -name …` by itself, as read-only, though
  `find` is not on the list.

## API

`brainyard.claude-host`: `inherited`, `start-argv`, `start!`, `ready`, `pane`,
`trust-dialog?`, `cursor-on-yes?`, `record`, `transcript`, `observe`,
`settled?`, `await-settled`, `type-line`, `choose`, `mode-of`, `mode-lines`,
`ensure-mode`, `stop!`. `brainyard.claude-sessions`:
`last-turn` (with `:usage`), `first-read-bytes`, `ended-reasons`,
`interrupted-text`, `unpasted`. `brainyard.worktree`: `add!`, `dirs`,
`common-dir`. `brainyard.claude-agent`: `commands` — `claude$sessions`,
`claude$worktree`, `claude$open`, `claude$await`, `claude$say`,
`claude$choose`, `claude$handoff`, `claude$finish` — and `sessions`,
`add-worktree`, `open`, `await-turn`, `say`, `choose`, `handoff`, `finish`,
`session-settings`, `denied-in-session`, `denied-when-publishing`,
`allowed-when-publishing`, `no-attribution`,
`default-allow`, `allowed-in-session`,
`publish-guard`, `delegation`, `with-delegations`,
`records`, `read-record`,
`records-dir`, `usage-cost`, `typed-chars`. `brainyard.agent`: the leaf
`:mem/join`. `brainyard.tui`: `delegation-label`, `delegation-asks?`,
`session-counts`. Config: `:claude-tools?`, `:claude-home`,
`:claude-permission-mode`, `:claude-model`, `:claude-settings`, `:claude-max-rounds`,
`:claude-turn-timeout-ms`, `:claude-max-cost-usd`, `:claude-git-remote`,
`:claude-gh-command`, `:claude-bd-command`, `:claude-allow`,
`:claude-allow-defaults?`, `:claude-attribution?`. Behaviour: `agents/claude.edn`,
`loops/claude.edn`, `predictors/claude/plan.edn`,
`predictors/claude/progress.edn`, `predictors/claude/pr.edn`,
`claude/allow.edn`,
`prompts/agents/claude.md`.

## Invariants

| Invariant | Enforced by |
|---|---|
| A session is started with its brief whole as the last argument, the parent's Claude Code variables unset, its model, mode, settings and extra arguments in place; resumed, with no brief. | `claude-host-test/a-session-is-started-with-its-brief-last-and-the-parents-variables-unset` |
| The trust dialog, and where its cursor is, are read from the screen. | `claude-host-test/the-trust-dialog-is-read-from-the-screen` |
| The permission mode is read from the footer; auto asked for and not offered gives accept edits, never manual; another mode asked for is left as it is. | `claude-host-test/the-mode-asked-for-is-the-mode-it-gets-or-the-nearest` |
| A typed line whose turn the record never showed busy has started when the transcript holds it since the line was typed; a line typed twice is told apart by the transcript growing. | `claude-host-test/a-turn-too-short-to-see-busy-has-still-started` |
| A turn is settled only when the record says idle and the transcript has the reply. | `claude-host-test/a-turn-is-settled-only-when-idle-and-the-transcript-has-the-reply` |
| A session Claude Code continued under a new id is read from the newest transcript of the chain, never settled on the old one's turn, its usage summed across it; `turn_duration` ends a turn. | `claude-host-test/a-session-continued-under-a-new-id-is-read-where-it-went-on`, `claude-sessions-test/the-newest-turn-is-read-as-the-transcript-grows` |
| Driven end to end (a stand-in for Claude Code, a tmux server of the test's own): past the trust dialog by Down until Yes; the brief's turn settled only once its reply is written after the idle; a second start refused; a dash-first line typed and a second refused while its turn runs; more than one line refused; a permission dialog stops the wait at once with the screen, and No ends the turn; `/exit` leaves, and the session is gone. | `claude-host-test/a-session-is-briefed-guided-and-watched-to-the-end-of-each-turn` |
| What Claude Code does is read as numbered steps — a tool call by what it works on, text by its first line, thinking not at all — the newest kept. | `claude-sessions-test/what-claude-code-does-is-read-as-numbered-steps` |
| Every step of the loop says it started and what it kept, and Claude Code's work streams while its turn is awaited — a reply that landed before the wait began included, and nothing an earlier wait streamed. | `claude-agent-test/the-loop-briefs-guides-and-reports`, `claude-agent-test/a-reply-before-the-wait-is-still-streamed` |
| The newest turn is read as the transcript grows: a tool call does not end it, its thinking does, past a line bigger than any tail, a half-written line waits, a stopped turn is ended, a file rewritten shorter is read anew. | `claude-sessions-test/the-newest-turn-is-read-as-the-transcript-grows` |
| A worktree is added on a branch of its own; every worktree is listed from any of them, the main first, a gone one left out; a directory in no repository is its own only one. | `worktree-test/a-projects-worktrees-are-added-and-listed` |
| A project's sessions include those in its worktrees, saved and live, each naming its worktree. | `worktree-test/sessions-in-a-projects-worktrees-are-its-own` |
| A session's tokens are counted once a reply, by model, across turns; a synthetic reply is no model's. | `claude-sessions-test/a-sessions-tokens-are-counted-once-a-reply-by-model` |
| The commands: a new session in a worktree of its own on its branch, briefed by its argument; its turn awaited with its answer and round; listed as this instance's own and no other's; a short line typed, a long one a file it is pointed at; past the cost cap said; a branch's worktree found again; how it ended recorded; a session not here refused. | `claude-agent-test/the-commands-start-a-session-in-a-worktree-and-guide-it` |
| The loop plans against the sessions there are, briefs, judges each turn on what it answered, guides by a typed line, and answers with the last report; the record says how it ended and which session delegated it, whose trajectory holds the turn. | `claude-agent-test/the-loop-briefs-guides-and-reports` |
| A dialog stops the delegation with a question; asked again, the same instance answers the dialog with the person's choice and carries on. | `claude-agent-test/a-dialog-goes-to-the-person-and-their-answer-to-the-dialog` |
| A delegation that cannot start answers with why. | `claude-agent-test/a-delegation-that-cannot-start-says-why` |
| agent$claude runs its own loop, and the `claude$*` commands are registered for its turn and no other. | `claude-agent-test/agent-claude-runs-its-own-loop-with-its-commands` |
| The loop checks clean. | `claude-agent-test/the-loop-is-sound` |
| Finished work is handed back, no one asked: nothing to publish said so; the commits past the base and the files they change, uncommitted tracked work named, an untracked directory not; the push and pull-request commands, its description in a file, nothing pushed or opened. Told to publish: what the session did not do, with the commands; pushed without a pull request; published, with its URL; cleaned up only once published, and not over untracked files, removing the worktree and local branch and leaving the remote's; never merged. | `claude-agent-test/finished-work-is-handed-back-with-what-to-publish` |
| A process left in a delegation's worktree is left alone while the worktree is there; once it is removed, the process is stopped and named, the record marked swept, and only once. | `claude-agent-test/what-a-session-leaves-in-its-removed-worktree-is-stopped` |
| A session starts on the model the plan names, else `:claude-model`, else Claude Code's default. | `claude-agent-test/a-session-starts-on-the-projects-claude-model` |
| A delegation's running session whose worktree is gone is stopped and its record marked; at the delegating session's close, one still working is stopped too; one done, or another session's, is not. | `claude-agent-test/the-sessions-a-delegation-strands-are-stopped` |
| The hand-off's clean-up stops the Claude Code session first when it is running, before its worktree and branch are removed. | `claude-agent-test/the-hand-off-stops-the-session-before-its-worktree-goes` |
| A task that says publish starts the session with the right to, and the loop writes no pull request of its own. | `claude-agent-test/a-task-that-says-publish-has-the-session-publish` |
| Told to publish, a session running without the right is stopped and resumed with it; one that has it is continued. | `claude-agent-test/the-commands-start-a-session-in-a-worktree-and-guide-it` |
| How a delegation ended is noted on its bead — branch and pull request, not the report — and not where the project keeps no beads. | `claude-agent-test/how-it-ended-is-noted-on-its-bead` |
| A delegated session starts with push and pull-request commands denied and the guard as its Bash hook, the project's own settings kept; read-only and local commands allowed, none that writes beyond the worktree, runs what follows it or destroys work, and none it denies; a project adds its own or drops the shipped ones. One told to publish is allowed pushing and opening its pull request, denied only merging, has no guard, and has a file of its own. Claude Code's attribution is hidden unless the settings or `:claude-attribution?` say otherwise. | `claude-agent-test/a-delegated-session-may-not-push-or-open-a-pull-request` |
| The guard refuses a push or pull request anywhere in a command line — past git's options, after `&&`, in `sh -c`, behind a path, on a later line — and lets through what only resembles one; a line that merely mentions a push is refused; only Bash is read. | `claude-agent-test/the-guard-refuses-a-push-wherever-it-is-in-the-line` |
| The loop adds the hand-off to its report. | `claude-agent-test/the-loop-briefs-guides-and-reports` |
| A session a delegation drove carries what the page shows of it; after a restart it is not the new instance's, but says whose work it was and how far it got. | `claude-agent-test/a-delegation-is-shown-with-its-session-and-found-again-after-a-restart` |
| A delegated session's row names its bead (else branch), where it stands and its pull request; a session no delegation drives says nothing of one; one that stopped to ask is counted as awaiting input. | `tui-test/a-session-agent-claude-drives-says-so-on-its-row` |

## Known gaps

None open. A model Claude Code offers no auto mode for (haiku) runs a
delegation in `acceptEdits`: the shipped allow list passes read-only and local
commands there (`byj-gly.292`), and anything else — a project's own test or
build command — asks until the project names it in `:claude-allow`.
