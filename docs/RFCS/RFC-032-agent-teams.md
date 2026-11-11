# RFC-032 — Agent teams

**Status:** built (`byj-gly.253`): teams (`.5`), a member's prompts to its
leader (`.7`), its spend in the leader's usage (`.8`), resuming a team
(`.10`), the board (`.11`); what a run in tmux found (`byj-gly.256`).

## Purpose

Decides how a leader session runs a team of member sessions on one piece of
work, with each member at work at the same time. brainyard has no teams: one
host process may hold several sessions, and A2A peers are its only
agent-to-agent calls (divergence `agent-teams`), so everything here is the
port's. It is built on the session channel (RFC-031), which is how one live
session reaches another.

## Scope

**This layer decides** who is on a team and how a member is started, stopped
and brought back; how work is handed out; what a member may ask its leader;
and what a member's work costs the leader.

**It must not know** how to lead — how to split the work, what a good
assignment says, when to check a member's claim. That is a prompt,
`resources/brainyard/prompts/team-lead.md`, handed to the leader when it
creates a team, as a skill's procedure arrives when it is called (RFC-017,
RFC-001). Nor does it know the transport: a member is asked over RFC-031's
channel like any other session.

**It hands** members to RFC-031 as headless sessions (`jolt run:byj --serve`)
and to RFC-019 as background tasks of the leader's. An assignment is RFC-031's
`session$send`.

## Model

- **A team** is opened by `team$create`: its roster is
  `.brainyard/teams/<id>/team.edn`, and the reply carries `team-lead.md`.
  A session leads one open team at a time.
- **A member** is started by
  `team$spawn {:name :agent? :provider? :model? :worktree?}`. It is a headless
  session (RFC-031) labelled `<team>/<name>`, with the persona of its agent
  spec (RFC-018): its instruction, tools, predictors, and its own model and loop
  unless `-p`/`-m` are given. It runs as a **background task of the leader's**,
  so it shows in `/task`. When the leader's session closes, the session cancels
  its tasks and the member goes with it: nothing a session starts outlives it.
  `:worktree true` gives the member a git worktree on its own branch,
  `team/<team>/<name>`, so members editing code at once never share files.
  `team$spawn` waits until the member is live (`:team-spawn-timeout-ms`, 60 s).
- **Handing out work.** `team$assign {:member :task}` is `session$send` to
  that member, recorded on the roster, so `task$wakeup` resumes the leader as
  its answer lands. `:question`, the `session$*` word, is taken as `:task`.
- **The board.** `team$assign {:task}` with no `:member` puts the task on the
  team's queue. A **dispatcher** hands each queued task to the next member
  that is up and free. The dispatcher is a background task of the leader's
  kernel, not a model turn. Its task ends once the queue is empty and
  everything it dispatched is answered, and its result is every answer, so one
  `task$wakeup` waits for the whole batch. Members need no tools of their own.
  `byj-gly.253.11` decided this on a simulation of the three ways a leader
  could hand out work (`dev/brainyard/dev/team_dispatch_spike.clj`, 500 seeded
  trials, task durations log-normal around 60 s, a leader turn of `L` s):

  | members / tasks | L | upfront (round-robin, one turn) | wakeup (assign the next on each answer) | **board** | ideal |
  |---|---|---|---|---|---|
  | 2 / 6 | 15 | 335 | 335 | **304** | 257 |
  | 3 / 12 | 15 | 483 | 465 | **420** | 350 |
  | 4 / 16 | 30 | 539 | 549 | **459** | 356 |
  | 4 / 40 | 30 | 1128 | 1217 | **949** | 827 |

  Pulling finishes 3–22% sooner than assigning on each wakeup, more as a
  leader turn gets slower, and saves one leader turn per task after the first
  round. Upfront assignment is slowest whenever durations vary: a member that
  draws the long tasks finishes last.
- **Seeing where it stands.** `team$status` lists each member, whether it is
  up and what its answers cost (`:spent`), each assignment with its state and
  the start of its answer, and what is still on the board.
- **Closing.** `team$close {:member?}` asks each member to close (RFC-031's
  `:close-session`), stops any that has not closed after
  `:team-close-grace-ms` (10 s), and leaves worktrees and branches for the
  leader to merge.
- **A member asks its leader's person.** A member is started
  `--leader <leader's id>`, and its `:permission-fn` forwards each prompt to
  the leader's channel as `{:op :permission :from :from-label :request}`
  (`channel/forwarding-permission-fn`). There the leader's own gate asks, with
  `⇠ <member>:` in front, and the answer goes back. If the leader is gone,
  cannot ask, or doesn't answer within `:team-permission-timeout-ms`
  (5 min), the prompt is refused. Only a full-screen leader answers: a terminal
  leader's reader and a forwarded prompt would read one stdin at once, so it
  advertises no `:permission`, and its members' prompts refuse.
- **What a member spends is the leader's.** Each answer's usage is added to the
  leader's session usage through RFC-031's `channel/spent!`, so `/usage` and
  the footer count the team.
- **Quiet tasks.** A member's process, and each ask the board makes, is
  started `:quiet?` (RFC-019): the end-of-turn wakeup is not armed for it. A
  member's ending is the team closing, not a result. And the leader waits on the
  dispatcher, so an ask that finished first would otherwise wake it with
  partial answers.
- **A team is resumed with its leader.** Members die with the leader's
  process, but their sessions are saved and the roster stays open. A resumed
  leader tells its person so, and `team$resume` starts each member again
  with `--resume <its own session>`: the same session, with its history, label,
  role, model and worktree. A member still live is left alone. An assignment
  the old process was waiting on is `lost` in `team$status`, to be assigned
  again.
- **Limits.** `:max-team-members` (4). A member is started with
  `BY_TEAM_TOOLS=false`, so a member leads no team, and a subagent has no team
  tools. A member runs `:team-member-command`, by default `jolt run:byj` from a
  checkout or `byj run` from a binary.

### Observed in tmux

A haiku leader and two haiku `explore` members ran on a scratch repository,
with `jolt sessions:byj watch` in a pane per member. The run showed three problems,
now fixed:
- The leader wrote `question`, the `session$*` word, in `team$assign`, which
  now takes it.
- It polled the dispatcher after `task$wakeup` instead of ending its turn, and
  `team-lead.md` now tells it to stop.
- It was woken again when the members stopped, which quiet tasks now prevent.

The run after the fixes took two leader turns (set up and park; then resume,
report and close), showed no warnings, and left no member process.

## API

`brainyard.teams`: `commands` (the `team$*` tools: `team$create`, `$spawn`,
`$assign`, `$status`, `$resume`, `$close`), `spawn!`, `resume!`, `assign!`,
`enqueue!`, `status`, `close!`, `current-team`, `read-team`, `teams-dir`,
`member-command`. From RFC-031: `channel/forwarding-permission-fn`,
`channel/spent!`. From RFC-018: `agents/persona-config`, `agents/child-lm`,
`agents/loop-path`.

`/team WORDS` (`commands/team.edn`, RFC-004) asks for a team in words: a
turn whose usage walks `prompts/team-lead.md`'s steps with these tools.

Config (RFC-013): `:team-tools?` (true), `:max-team-members` (4),
`:team-member-command` (nil: `jolt run:byj` or `byj run`),
`:team-spawn-timeout-ms` (60000), `:team-close-grace-ms` (10000),
`:team-permission-timeout-ms` (300000).

## Invariants

| invariant | enforced by |
|---|---|
| A team hands its leader the protocol; a member is a live session found by its team label; an assignment returns at once and lands as the leader's completed task; status shows members, answers and what each spent; a member in a worktree has its own branch; closing stops every member and leaves the worktree. Members are real `--serve` processes on the mock model. | `teams-test/a-leader-runs-a-team-of-live-sessions` |
| Tasks on the board go to the next free member; one dispatcher works them all and lands once every one is answered, with the answers; both members worked. | `teams-test/the-board-hands-each-task-to-the-next-free-member` |
| A resumed leader sees its member down and what it was waiting on lost; `team$resume` brings the member back as the same session with its history, and leaves a live one alone. | `teams-test/a-resumed-leader-brings-its-team-back` |
| A team refuses work before it is created, a member past `:max-team-members`, an assignment to no member and an empty one; `question` is taken as the task. | `teams-test/what-a-team-refuses` |
| A member's prompt reaches its leader's gate with the member's name on it; allow and deny come back; a leader that cannot ask, or is gone, is a refusal and not a hang. | `channel-test/a-member-asks-its-leaders-person` |
| A quiet task — a member, a board's ask — is neither armed for nor named in a wakeup. | `tasks-test/a-quiet-task-is-not-armed` |
| A member takes its spec's instruction, tools, predictors, model and loop; `-p`/`-m` and `--loop` win. | `channel-test/a-headless-session-takes-an-agents-persona`, `channel-test/a-headless-session-takes-an-agents-model-and-loop` |
| Pulling from a board beats assigning on each wakeup, and upfront assignment, for varied task durations. | `dev/brainyard/dev/team_dispatch_spike.clj` (a simulation, not a test) |

## Known gaps

None open.
