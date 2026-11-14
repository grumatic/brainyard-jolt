# RFC-041 — Dogfood simulation

**Status:** built. Designed 2026-10-09. Epic `byj-gly.315`. Done: the pilot
(`.1`), whose findings are `.7`–`.11`, the runner (`.2`), the rules and
comparison (`.3`), the full run in mode A (`.4`), and the run again after
fixes (`.6`), and mode B with the screen pass (`.5`).

## Purpose

Decides how this repository finds its defects by using itself. brainyard
builds a small web app from scratch in a fresh directory while a script plays
the person, and what goes wrong becomes beads. The run is repeatable, so a run
after fixes is compared with the one before.

Unit tests check what a layer promises. They do not check what a person meets
when brainyard works on a project that is not this one. Several defects this
week were found only that way: a plain `/init` sending an empty question, other
projects' Claude Code sessions on the agent page, and a 20K AGENTS.md cut to
its intro. The simulation makes finding them routine.

## Scope

**This layer decides** the project the simulation builds, how its stages are
given to brainyard, what is checked after each, what is recorded and which
patterns count as findings.

**It must not know** how brainyard does the work. It reads what any run
leaves: the session's trajectory, `events.log`, the project's files and the
checks' verdicts.

**It hands** a report per run, and the beads triaged from it.

## The project: a mini-playground

The playground-apps workspace app, simplified. Its real Terminal section runs
`by run --web-tmux` in tmux behind ttyd and an auth proxy, about 4,000 lines
tied to the `by` binary. The simulation builds that repository's own simpler
design instead (`docs/design/workspace-terminal-plan.md`, option A): Node 20,
`http` + `ws` + `node-pty`, xterm.js, and `node --test`. The repository has no
tests to reuse, so every stage brings its own check.

| Stage | Asked for | Checked by |
|---|---|---|
| 0 | `/init`, then the backlog filed as beads | AGENTS.md has the project's section; `bd list` has the stages |
| 1 | A server shell with sections, and `GET /api/health` | `curl` health is ok; the page has `data-section="terminal"` |
| 2 | A shell over a WebSocket | a node test echoes a marker within 3 s; the shell is gone after close |
| 3 | Resize, and replay on reconnect | `stty size` is `30 100`; a marker is replayed after reconnecting |
| 4 | The xterm.js Terminal section | Playwright types `echo hi-pw` and sees it |
| 5 | A file tree and an editor confined to the project | PUT then GET round-trips; `../..` is refused with 400 |
| 6 | (optional) chat over SSE, an echo stub | `curl -N` gets `data:` lines and `event: done` |

It is chosen for what it puts in brainyard's way: a stack that is not
Clojure, a native dependency to build, a server and its ports, end-to-end
checks, and work across turns and sessions.

## How a stage is given

The person is a script typing into a real session. The session channel
refuses a command or a direct tool call from a peer, by design (RFC-031,
`byj-gly.282`), so `--serve` with `ask --attach` cannot type `/init`. Each
stage pipes its lines into `jolt run:byj --inline -C <dir> --resume <id>`,
which resumes the same session, and its checks run between stages. So every
stage also exercises resuming (RFC-016).

The project is fresh, outside this repository, at
`~/Projects/byj-sim/playground-<date>`, and kept. Runs use
`--permission-mode auto`. A permission prompt in a scripted run is itself a
finding, since its answer would be the script's next line.

- **Mode A:** brainyard's root agent works each stage.
- **Mode B:** each stage's bead is handed to `agent$claude` (RFC-039).
- **The screen pass:** at stages 0 and 5, the same project in the full-screen
  renderer under tmux, read with `brainyard.dev.tui-verify`.

The models: sonnet for the stages, and the predictors on their own tiers. The
caps: $2 a stage, about $10 a full run.

## What is recorded, and what is a finding

Per stage: the checks' verdicts; turns, iterations, tool calls and errors,
permission prompts and denials, compaction, prompt size, cost and time. A
run is not scored here: its trajectories are what an evaluator reads after
it (RFC-070 "Standing alone").

A finding is any of these:

- a check that fails while the answer claims success;
- the same tool call repeated;
- a turn that reached its iteration limit, or hung;
- a write outside the project;
- one of the project's AGENTS.md rules broken;
- a server left running;
- a question the stage already answered;
- a permission prompt.

The rules are data. A run writes `<project>/.sim/report.md` and `.edn`, and
is compared with the one before, stage by stage. Each real defect is filed as
a bead labelled `sim`.

## The runner (`byj-gly.315.2`)

`jolt sim:byj PLAN [--dir D] [--stages a,b] [-p P -m M] [--loop FILE]
[--keep-going] [--no-resume]`, `brainyard.sim`. Mechanism only: the project, its stages,
what the person types and what is checked are the plan, data
(`dev/sim/playground/plan.edn`, its check scripts beside it, `{{plan}}` in a
check).

- **It types into a real session in tmux.** It uses a server of its own
  (`*tmux*`, `tmux -L byj-sim`), so a run can be watched with
  `tmux -L byj-sim attach -t sim-<project>`. A line is typed with
  `send-keys -l`. The turn is over when the screen shows one more `you>`
  line than before and the last line is the bare prompt (`screen-state`).
- **A permission prompt is answered by the plan**, `:answers` by what the
  prompt names, else `:default-answer` ("n"), and recorded. It never takes a
  person's line, as it did in the pilot.
- **A line that never left the prompt** is recorded as `:not-submitted`,
  rather than waited on until the stage's timeout. That means after 30 s it
  is still at the prompt with nothing after it, and the project's
  `events.log` has no new `:session.turn/start`. The screen alone cannot say
  whether a line was taken: an inline session shows nothing while a turn
  runs, and a sonnet turn said nothing for minutes, so the first version
  stopped a stage that was working.
- **Each stage is a new process resuming the session**, when `:resume?` (the
  default). The session id is read from the closing line, so every stage also
  exercises resuming.
- **After each stage** it runs the stage's checks in the project
  (`eval-bench/verify`'s `:command` and `:file`), and reads what the stage
  did from every trajectory record since it began, subagents' included
  (`stage-metrics`): turns, subagents, iterations, calls, failed calls (the
  first few, as they read), cost, tokens. Calls and failed calls are the
  model's, and so are the calls a finding reads; the calls the loop's tree
  made (`:tool/run`, an attachment's) are counted apart, `:tree-calls` and
  by slot or node `:tree-calls-by` (`byj-8gc`, RFC-025). A run before that
  counted both as the model's, so it can show more calls than one since on
  the same work.
- **A run can be on a loop of the person's choosing** (`--loop FILE`,
  `byj-ng7.18`), for RFC-071's promotion check: the loop in a file, which
  must be one `byj loops eval` would run and is named as it names it
  (`loop-eval/loop-of`), is made the new project's loop before its first
  stage, as a person's accept makes one (`project-loops/write-loop!`, a line
  in its `history.edn`). Checked before the project is made, so a loop byj
  would not run leaves nothing behind; a continued run keeps the loop it
  began with. Every report says which loop ran — `:loop {:name :from :hash
  :file?}`, `:from` `:bundled` or `:project` — and a comparison of two runs
  on different loops names both.
- **A plan can fill the ReAct loop's slots** (RFC-072): its `:attachments`,
  `{slot-id [name …]}`, attach the trees in `<plan>/ext/<name>.edn` before
  the first stage, as a person's accept would (`project-loops/write-attachment!`),
  each checked first where it would sit (`loop-proposals/attachment-problems`),
  so one that would not be staged leaves nothing behind. The report names
  them (`:attachments [{:slot :name :hash}]`). A plan's `:notice-markers`
  are words counted per stage in the notices its turns were shown
  (`:notices {marker n}`): what an attachment said, and `ANSWER REFUSED`
  for a refusal. `dev/sim/extensions/plan.edn` is the playground built so.
- **A failed call** is one the trajectory marks failed (`:error?`), or whose
  result reads as one: a gate's block, which prints as `{:hook-blocked …}`,
  was not counted before `byj-ng7.18`, so a run since can show more failed
  calls than one before it on the same work.
- **A run stops** at a stage that fails its checks, unless `:keep-going?`,
  or past `:cap-usd`.
- **It writes** `<project>/.sim/report.edn`, `report.md` and each stage's
  screen, `<stage>.screen.txt`, after every stage. `.sim/` is kept out of the
  project's git (`.git/info/exclude`).
- **A project starts from nothing**, at `~/Projects/byj-sim/<plan>-<local
  date-time>`: a directory that is not empty is refused.
- **A stopped run is continued** with `--continue DIR` (`byj-gly.315.17`).
  The stages its report passed are kept. The failed one has its checks run
  again first: a check fixed since can pass it without a turn, recorded as
  `:rechecked`. Otherwise it is played again. The rest follow in the same
  session, resumed, and the report says when it was continued.

Found by building it: a typed line past 1024 characters was lost in an inline
session (`byj-gly.315.12`). The terminal's canonical mode drops what is past
MAX_CANON, and the line never completes: no turn, no error. The inline reader
now reads out of canonical mode itself (`brainyard.line-input`), and the
runner leaves the pane as a person's terminal is, so it would see a
regression. The playground plan's backlog line is about 1,350 characters.

**First live run** (2026-10-09, stages `init` and `server`, claude-code/sonnet,
`~/Projects/byj-sim/playground-20261009-0801`):

| Stage | Verdict | Time | Turns | Calls | Failed calls | Cost |
|---|---|---|---|---|---|---|
| init | pass | 200 s | 1, plus agent$init and agent$beads | 25 | 6 | $0.42 |
| server | pass | 77 s | 1 | 3 | 0 | $0.18 |

Four of init's failed calls were the quoted-`;` refusal, now worded clearly
(`.7`), once for each bead whose description copied the backlog's
semicolons: clearer words were not enough (`byj-gly.315.14`).

**After `.12` and `.14`** (stage init again,
`~/Projects/byj-sim/playground-20261009-0846`):

| | Before | After |
|---|---|---|
| Calls | 25 | 16 |
| Failed calls | 6 | 1 |
| Time | 200 s | 154 s |
| Cost | $0.42 | $0.31 |

The 1,350-character backlog line went through brainyard's own inline reader,
with no workaround in the runner. Every `bd create` ran on its first try.
What is left is agent$beads piping `bd prime` into `head`, which its
allow-list of `bd` refuses, in every run.

## Findings, and comparing runs (`byj-gly.315.3`)

**A finding is a rule's hit on a stage.** The rules are data,
`resources/brainyard/sim/rules.edn`; a plan adds its own `:rules`, and one
with a default's `:id` replaces it. Each names a detector `brainyard.sim`
provides, its parameters, and a severity: `:defect`, `:warn` or `:info`.

| Rule | Severity | A hit is |
|---|---|---|
| `claimed-but-failed` | defect | a check failed while the stage's last answer claims success |
| `stuck-turn` | defect | a turn ran out of time, or a typed line never started one |
| `iteration-limit` | defect | a turn ended out of iterations |
| `attribution` | defect | a commit made in the stage carries an AI attribution |
| `repeated-call` | warn | the same tool and arguments three times or more |
| `refused-calls` | warn | two or more calls refused by a gate |
| `permission-prompt` | warn | a prompt in a scripted run |
| `server-left-running` | info | a process whose working directory is in the project still listening as the stage's turns end |
| `outlived-session` | defect | such a process still listening once the session has ended — stopped once recorded |
| `path-outside` | warn | a file tool given a path outside the project and the temp directories |
| `sweeping-add` | warn | `git add -A`, `--all` or `.` |
| `stage-cost` | warn | a stage past its cap (`:stage-cap-usd`, else $2) |
| `failed-calls` | info | three or more other failed calls |
| `asks-the-person` | info | the stage's last answer ends with a question |

**What a rule reads:**

- the stage's checks, turns and prompts;
- its trajectory records;
- its last answer, which is the last root turn's (a subagent's answer is its
  caller's to judge);
- the project directory and when the stage began.

The findings go in `report.edn`, and in `report.md` per stage and as counts
in the table.

**A run is compared with the last of its plan** in the same directory,
`<projects-dir>/<plan>-*/.sim/report.edn`, stage by stage. The comparison is
of verdict, calls, failed calls, cost and time. A regression is one of:

- pass to fail;
- more failed calls;
- a cost up by half or more;
- a defect the last run did not have.

A report written before RFC-070 may hold the evaluation store's scores
(`--score`, since removed with the evaluation); they are not shown.

## The full run, mode A (`byj-gly.315.4`, 2026-10-09)

The first full run (`playground-20261009-0958`) stopped at stage `ui`, on a
defect of the check: it searched the page's HTML for `data-status`, and the
badge was made by `term.js`. The rule `claimed-but-failed` fired, rightly,
given the check. The check now reads the page and every script it loads.
That run also found a server the agent had started in the background still
listening after the stage. Probed, such a process outlives the session too
(`byj-gly.315.16`). And it found that a stopped run cannot be continued
(`byj-gly.315.17`).

The second (`playground-20261009-1009`), compared with the first:

| Stage | Verdict | Time | Calls | Failed calls | Cost | Findings |
|---|---|---|---|---|---|---|
| init | pass | 72 s | 2 | 0 | $0.16 | none |
| server | pass | 62 s | 3 | 0 | $0.16 | none |
| term | pass | 236 s | 6 | 1 | $0.25 | none |
| resize | pass | 93 s | 5 | 0 | $0.22 | none |
| ui | pass (was fail) | 96 s | 4 | 1 | $0.25 | none |
| files | pass | 114 s | 4 | 0 | $0.32 | none |

That is $1.37 and about 11 minutes, with no regressions. Without agent$beads
(`byj-gly.316`), the root agent files the five beads in one bash script: 2
calls where the bead-keeper took 16. Each `term` stage loses 120 s to one
timed-out call: `npm install` then a test hanging on node-pty's helper,
which ships without its execute bit, before the agent adds a `chmod`. That
is node-pty's packaging, not brainyard.

## Again after fixes (`byj-gly.315.6`, 2026-10-09)

The run `playground-20261009-1054` came after the fixes: `.8`–`.10`, `.12`,
`.14`–`.17`. It confirmed the `/init` ones live. The beads prefix came out as
`playground-h68`, not `playground-20261009-…`. AGENTS.md's project section
stayed the placeholder, rather than a draft of an empty repository.

`files` failed. The backlog said only that PUT "writes a file", and this
run's agent read the body as JSON `{"content": …}` where the check sent raw
text. The rule `claimed-but-failed` fired as it should, given the check. The
backlog now says the body is the file's text, as it is, and the check takes
either. `jolt sim:byj --continue` then re-checked the stage and passed it with
no turn: the run's first use of `.17`, at $0.

Two rules changed with it:

- `server-left-running` is now `:info` and stops nothing: the agent leaving
  a server up as its turn ends is a habit to note.
- `outlived-session`, a defect, checks once the session has quit. What
  `.16` promises is that nothing the session started is still listening
  then. `sim-test/what-a-stage-starts-does-not-outlive-its-session` shows it
  end to end.

Its first hit was a false one: the check script's own server. A failing
check exited at once and skipped its cleanup, so the check now stops its
servers however it exits.

## Mode B, and the screen pass (`byj-gly.315.5`, 2026-10-09)

**Mode B** (`dev/sim/playground/plan-claude.edn`) has the same stages and
checks, but each bead goes to `agent$claude`. The person asks for the
branch to be merged into main, since the checks run there, and for the
worktree and branch to be removed. Stages `init`, `server` and `term` ran in
`playground-claude-20261009-1120`:

| Stage | Verdict | Time | brainyard | Claude Code | Cost, A |
|---|---|---|---|---|---|
| init | pass | 62 s | $0.13 | — | $0.15 |
| server | pass | 150 s | $0.20 | $0.40 | $0.18 |
| term | pass | 163 s | $0.15 | $0.45 | $0.30 |

Both delegations built and committed on their branches, and the root agent
merged and removed them. That cost about twice mode A ($1.34 against
$0.64). It took no longer: `term` took 163 s against 313, since Claude Code
did not hit node-pty's hang.

It found:

- **The runner counted only brainyard's cost.** Claude Code's is in
  agent$claude's records, and is now added (`delegated-cost`): a stage's
  `:cost-usd` is both, with `:brainyard-cost-usd` and
  `:claude-code-cost-usd` beside it.
- **`refused-calls` matched the word.** An answer that said "refused"
  counted, so a refusal is now an error that says so.
- **Both Claude Code sessions stayed up in tmux** after their worktrees were
  removed (`byj-gly.315.20`). The runner now finds a Claude Code session left
  in a removed worktree, counts it as `outlived-session`, and stops it.

**The screen pass** ran the full-screen renderer in tmux, on the finished
project, with the mock provider. It looked at the start, `/init check`, the
agent page and `/login`:

- The check report lost its layout in the renderer: indentation dropped,
  spacing run together. It is a Markdown list now.
- Its project-section line said "once the project has code" under a code
  line naming package.json. It now says "drafts it from the code".
- `/init`'s call block shows the person the agent's dispatch envelope
  (`byj-gly.315.18`).
- A session's row on the agent page previews the last line of its last
  answer (`byj-gly.315.19`).
- `/login` read cleanly.

## The second plan: Chat and a modern UI shell (`byj-gly.317`, 2026-10-09)

`dev/sim/playground/plan2.edn` (mode A) and `plan2-claude.edn` (mode B)
play the first plan's six stages, then two more:

- `chat`: a Chat section. `POST /api/chat` answers a `text/event-stream`
  that ends with `event: done`, from a backend whose default echoes the
  message. The page reads the stream.
- `shell`: a modern UI shell. A menu bar (`role="menubar"`), and one section
  shown at a time, chosen by the URL hash. Terminal tabs, each with its own
  `/term?id=`, and a `+` to open another. Editor tabs for the open files. A
  theme toggle remembered in `localStorage`, and a layout for a narrow
  screen.

No browser is driven. `checks.cjs chat` streams a message and reads its
echo. `checks.cjs shell` reads the hooks the spec names from the page and
its scripts, and opens two terminals to check they do not cross. The spec
says the tests must not rely on port 3000, since a person's own server may
hold it.

`jolt sim:byj compare RUN-A RUN-B` sets two runs side by side (`compare-modes`),
then B against A by the regressions above (`compare-runs`, `comparison-md`),
and exits 1 when B regressed on any stage.

**Both modes, 2026-10-09** (`playground2-20261009-1220`, A, and
`playground2-claude-20261009-1220`, B; sonnet, run side by side):

| | A: brainyard builds | B: Claude Code builds |
|---|---|---|
| Stages passed | 8/8 | 8/8 |
| Checks | 16/16 | 16/16 |
| Calls (failed) | 65 (6) | 57 (2) |
| Findings d/w/i | 0/0/1 | 7/2/0 |
| Time | 925 s | 2236 s |
| Cost | $2.17 | $6.39 ($2.21 brainyard + $4.18 Claude Code), at least $7.18 with what `byj-gly.319` drops |
| The project | 24 tests; 500 lines of app, 302 of tests; 9 commits | 61 tests; 1705 lines of app, 1049 of tests; 29 commits |

B costs about three times as much and takes about two and a half times as
long. It passes the same checks, with a project three times the size and
more than twice the tests. Claude Code wrote its tests first, ran them
repeatedly to find a flaky one, and loaded the page in a browser itself.
B's seven defects are all one: `outlived-session` (`byj-gly.318`).

**Is it the model or the harness?** Mode B's sessions ran on the account's
Claude Code default, Opus, so the runs above compare two models as well as
two harnesses. Two more runs completed the 2×2 (`byj-gly.320`):
`plan2.edn -m opus` (`playground2-20261009-1426`) and
`plan2-claude-sonnet.edn`, Claude Code on Sonnet through `:claude-model`
(`playground2-claude-sonnet-20261009-1432`).

| Builder · model | Cost | Time | Tests | App lines | CSS lines | Commits |
|---|---|---|---|---|---|---|
| brainyard · Sonnet | $2.17 | 925 s | 24 | 500 | 38 | 9 |
| brainyard · Opus | $6.73 | 1435 s | 46 | 1055 | 73 | 9 |
| Claude Code · Sonnet | $3.61 | 1632 s | 46 | 789 | 70 | 16 |
| Claude Code · Opus | $6.39+ | 2236 s | 61 | 1705 | 290 | 29 |

All four passed 8/8 stages and every check. Desktop and narrow screenshots
of each Terminal section, taken with headless Chrome, rank the looks:

1. Claude Code on Opus is the most finished: underlined menu tabs,
   closable terminal tabs, a status dot, an icon for the theme.
2. brainyard on Opus is close behind, with the same dark theme, closable
   tabs and icon toggle.
3. Claude Code on Sonnet is dark and styled, but has a text "Theme"
   button, no title and no close on tabs.
4. brainyard on Sonnet has the browser's own light buttons.

At narrow width, both Opus builds drop the theme toggle, and three of the
four let the terminal run past the right edge.

So the model decides most of it. Opus doubled what each harness built,
tests and code alike, and moved brainyard's UI from the plainest to second.
The harness adds less: on Sonnet, Claude Code's build is styled where
brainyard's is not, and has twice the tests, for $1.44 more. No design skill
was used: Claude Code was offered `frontend-design`, and none of the
sessions called it. Per stage, brainyard on Opus costs about what Claude
Code on Opus does ($6.73 against at least $6.39), because the root's own
turns are on Opus too. In mode B the root only hands off and merges, so the
cheapest UI-quality mix is a Sonnet root with Claude Code on Opus.

The Sonnet run of Claude Code also left a server running in a worktree.
Brainyard does not track processes that Claude Code starts
(`byj-gly.321`).

What the two runs found:

- **The checks were too literal**, four times. Each was the check's own
  fault: an app that met the spec failed it.
  - Hooks set from a script (`tab.dataset.termId` for `data-term-id`).
  - An echo streamed a word to each `data:` line.
  - Scripts that the page loads only through an ES-module `import`.

  `checks.cjs` now accepts each of these.
- **A turn that parks a task ended its stage.** In B's `ui`, the root parked
  agent$claude's task, so the runner checked the stage while Claude Code was
  still building. Its `/quit` then cancelled the task. The runner now waits
  on the event log (`parked-pending`). B's `shell` stage parked too, and was
  waited for.
- **Claude Code sessions outlive their worktrees** when the root writes its
  own clean-up command instead of the guide's. A cancelled delegation also
  leaves its session working, unwatched, and it stopped at a permission
  dialog no one would answer. Both are now stopped by mechanism
  (`byj-gly.318`, RFC-039).
- **A run was compared with the other plan's.** `playground2-claude-…`
  starts with `playground2-`. `previous-report` now matches the plan id.
- **A failed stage passed on re-check had its cost zeroed.** It now keeps its
  first attempt's cost. A stage played again keeps its failed attempt under
  `:attempts`, adding its cost and time. Claude Code's spend is now each
  delegation record's rise across the stage, so a delegation started earlier
  counts for what it spends here (`byj-gly.319`).
- **A delegate filed work of its own.** The Claude Code session for
  `playground-3po` filed `playground-cm5`, a duplicate of `s6q`. The root
  noticed and left it alone.

## Plan

| Bead | What |
|---|---|
| `byj-gly.315.1` | The pilot by hand: stages 0–2 in mode A, logs read, findings filed |
| `.2` | The runner: stages as data (`dev/sim/playground/stages.edn`), `dev/brainyard/dev/sim.clj` as `jolt sim:byj` |
| `.3` | The anomaly rules, the report, and comparison between runs |
| `.4` | A full run in mode A, its findings filed |
| `.5` | Mode B and the screen pass |
| `.6` | Again after fixes, compared |

## Pilot findings (`byj-gly.315.1`, 2026-10-09)

Stages 0–2 were driven by hand in `~/Projects/byj-sim/playground-20261009`,
in mode A on claude-code/sonnet with `--permission-mode auto`. There was one
session (`1791496666073-06f7ff06`), resumed for each stage.

| Stage | Wall | Turns (records) | Model calls | Cost | Checks |
|---|---|---|---|---|---|
| 0 `/init` + backlog | 2:47 | root, agent$init, agent$beads | 16 | $0.36 | AGENTS.md drafted; 5 beads chained |
| 1 server shell | 1:16 | root | 5 | $0.19 | `npm test` 3/3, health 200 `{"ok":true}`, 4 sections, 404, bead closed, clean commit |
| 2 PTY over WebSocket | 4:51 | root | 10 | $0.35 | `npm test` 4/4, nothing left running, bead closed, clean commit |

All three stages passed their checks, and every answer was honest about what
it had not tested. What went wrong was the harness's:

- `byj-gly.315.7`: the bash gate splits on a `;` or line break inside quotes,
  and names a word of the quoted text as the refused program. Two `bd create`
  calls were refused, and every description was rewritten.
- `byj-gly.315.8`: `/init` leaves `.claude/skills/<name>` untracked and does
  not say so.
- `byj-gly.315.9`: a dated directory name became a long beads prefix,
  `playground-20261009-…`.
- `byj-gly.315.10`: the AGENTS.md section drafted for an empty repository
  says there is nothing yet, and is never redrafted once there is code.
- `byj-gly.315.11`: a bash timeout does not say the command's processes were
  stopped. They were, and a probe left none. The agent ran `pkill`, which
  `:bash-ask-commands` put to the person.

Not this repository's: node-pty's prebuilt `spawn-helper` lacked its execute
bit (`posix_spawnp failed`), so a test hung for the full 120 s timeout. The
agent found the cause and added a `postinstall` chmod.

**What the runner (`.2`) has to handle,** learned here:

- A permission prompt in a scripted run takes the script's next line as its
  answer. `pkill *` is in `:bash-ask-commands`, so it asks even in auto mode;
  here it consumed `/quit`, which counted as a no. The runner keeps its own
  answer for each prompt, refusing by default, and records every prompt as a
  finding. It must never let a prompt read a person's line.
- The session id comes from the first stage's closing line (`byj run
  --resume <id>`). Each later stage resumes it in a new process; resuming
  worked every time.
- A stage's cost is the sum of every trajectory record since the stage
  began: the root turn's record does not include its subagents'. A call is
  `{:name :args :result}` under an iteration's `:tools`, and its result is a
  string; a failure is a result that starts with `{:error`, has a non-zero
  `:exit-code`, or has `:status "timeout"`.
- The checks belong to the stage, not to the agent's report. Running them
  agreed with every report here, which is what they are for.

## A stage after one that ran only commands (`byj-gly.340`, 2026-10-12)

`resume-hint` names a session at `/quit` only when it holds a turn, and one
that ran only `/init` holds none, so the runner had no id to resume and
started no session for the next stage: its lines went to the exited pane until
the stage timed out (the onboarding plan's `code` stage, 960 s, three runs).
With no id to resume, the next stage now gets a fresh session. Nothing but a
plan whose first stage is commands alone (`dev/sim/onboarding/plan.edn`)
exercises it.

## Invariants

| Invariant | Enforced by |
|---|---|
| The screen says where the session stands: ready, busy, asking (and for what), exited. | `sim-test/the-screen-says-where-the-session-stands` |
| Each rule finds what it names, and a quiet stage has none. A server left listening in the project is found and stopped. A plan's rule replaces the default with its id. | `sim-test/each-rule-finds-what-it-names` |
| A run is compared with the last, stage by stage: pass to fail, more failed calls, cost up by half, new defects; a stage new to the plan is said so. A second run of a plan finds the first beside it. | `sim-test/a-run-is-compared-with-the-last`, `sim-test/the-second-run-of-a-plan-is-compared-with-the-first` |
| Two runs are compared stage by stage, A's stages first: verdict, checks passed, calls and failed calls, findings by severity, time, and cost split into brainyard's and Claude Code's, then totals. | `sim-test/two-modes-are-compared-stage-by-stage` |
| What Claude Code spent in a stage is each delegation's rise in cost across it, one started earlier included. A stage played again keeps its failed attempts, their cost and time added to its own. | `sim-test/what-claude-code-spent-is-counted`, `sim-test/a-stage-played-again-keeps-its-failed-attempt` |
| A turn that parks a task does not end its stage: the runner waits while the task runs, and then for the turn that wakes on it. | `sim-test/a-parked-task-keeps-the-stage-open` |
| The last run a run is compared with is one of the same plan, not of a plan whose id begins the same. A failed stage passed on re-check keeps its first attempt's turns, cost and time. | `sim-test/the-last-run-is-one-of-the-same-plan`, `sim-test/a-stopped-run-is-continued` |
| What a stage's command leaves running is recorded when its turns end and stopped by the session's end: nothing outlives the session. | `sim-test/what-a-stage-starts-does-not-outlive-its-session` |
| A run stopped at a failed stage is continued: the passed stages kept, the failed one re-checked (passing without a turn when its checks pass now), the report marked continued; a directory with no run is refused. | `sim-test/a-stopped-run-is-continued` |
| A call the loop's tree made is counted apart from the model's, and no finding reads it as the model's. | `sim-test/a-call-the-loops-tree-made-is-counted-apart-from-the-models` |
| A plan's attachments are checked, attached before the first stage, and their notices counted per stage. | `sim-test/a-plans-attachments-are-checked-installed-and-their-notices-counted` |
| A run on a chosen loop runs it: the loop made the project's own as an accept makes one, the report and its markdown naming the loop of every run. A loop byj would not run is refused before the project is made, and `--loop` with `--continue`. Two runs on different loops say so in their comparison, and a regression fails `compare`. | `sim-test/a-run-on-a-chosen-loop-runs-it-and-says-so`, `sim-test/a-loop-byj-would-not-run-is-refused-before-the-project-is-made`, `sim-test/two-runs-on-different-loops-say-so-and-a-regression-fails-the-comparison` |
| A plan is played stage by stage. Each stage's checks decide its verdict, and `{{plan}}` reaches the plan's scripts. A permission prompt is answered by the plan and recorded. Each stage resumes the one session. A line past 1024 characters is taken. The report and each stage's screen are written and kept out of the project's git. A project that is not empty is refused. | `sim-test/a-plan-is-played-stage-by-stage-and-reported` |

## Known gaps

None open. Every finding the simulation has filed is fixed
(`byj-gly.315.7`–`.20`, `.318`, `.319`). Running it again is how the next
ones are found.
