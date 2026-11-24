# Changelog

What changed for someone running `byj`, release by release. Versions are the
git tags (semver; RFC-029). A release's notes are its section here, copied
into `CHANGELOG-latest.md` when it is published (RFC-029 "Releasing").

## Unreleased

- **`/workflow` keeps a project's workflows.** `agent$workflow` lists the
  workflows `.brainyard/deps.edn` names, shows one whole, and adds, changes
  or removes one as you ask. Each change is shown to you first, and written
  through `byj deps write`'s checks only on your yes (RFC-076).
- **`byj doctor` says byj-share and Codex.** What is installed lists
  byj-share's copies on PATH after byj's, each with its version and how its
  owner updates a shadowed one. When codex is installed, a Codex section says
  its sign-in (`codex login status`: ChatGPT or an API key, never the key) and
  its plan's windows as Codex last reported them, as Claude Code's are said.
  `byj doctor --json` gives both: `installed` names each copy's command, and
  a new `codex` field (byj-rys).
- **An ACP client gets each turn's recap and the next prompt.** The recap
  the full-screen UI shows under a turn (`recap: …`) is sent when it is
  written, as a notice marked `recap` with its turn, and a loaded session
  replays each turn's recap after its answer: the snapshot keeps them by turn
  (`:recaps`, the newest 200). `session/load` answers the next prompt the
  last turn suggested, `_meta.brainyard.nextUserPrompt`, and a direct tool
  call whose result suggests one says it in its prompt's answer (byj-s5h).
- **`/quit` and `/exit` no longer stall an ACP or SDK session.** Typed in a
  session an application opened, they ended it under the application, and
  every prompt sent after waited for ever. The application ends its
  sessions, so they are refused there; and a session that ends any other way
  refuses what is sent after it (byj-6sd).
- **`session/prompt`'s answer says the model the session asks now**
  (`_meta.brainyard.lm`), a command's too: a client shows the model `/model
  NAME` switched to (byj-11o).
- **`/drive` works a profile's GitHub issues as well as its beads.** It
  goes through the trackers in the order the profile's `:issues` lists
  them. From GitHub it takes the oldest open issue that no one is
  assigned and that is not labelled `human`, in the profile's
  `:github-repo` or else the origin's. It claims the issue by assigning it
  to gh's user, works and checks it, and closes it with `gh issue close`
  as the profile allows. After its last turn without finishing, it
  unassigns the issue, labels it `human` and comments on it. Each
  issue's id is `#N`. A profile naming only Linear or Jira still stops
  the run. Set `:gh-path` to look gh up somewhere other than PATH
  (byj-m6f).
- **`/init`'s check marks the profile ✗ when the project has not set up a
  tracker the profile names.** That is beads with no `.beads/`, or GitHub
  with no `:github-repo` and no github.com remote. Linear and Jira are not
  checked, since the profile holds no credential for them (byj-5im).
- **`/init`'s check and `byj doctor` report a project's dependencies.**
  When `.brainyard/deps.edn` is there, a line says what reading it
  dropped and the errors and warnings of its projects: one not on this
  machine, a provide missing or at the wrong version, an origin that
  differs, commits since `:seen`. It is ✗ on a problem or an error. Roles,
  sessions and handoffs stay with `byj deps` (byj-g6o).
- **`/drive` no longer works beads in a project whose profile keeps its
  issues elsewhere.** When the profile's `:issues` name GitHub, Linear or
  Jira and not beads, the run stops and names those trackers. A profile
  that names no tracker is still driven through beads. `/init` offers
  `/drive` only when the profile keeps its issues in beads, and both now
  say "the project's ready beads", since the goal no longer has an epic
  (byj-y0u).
- **A new shipped agent, `agent$general-purpose`**, for a whole task that
  no specialist fits: research, analysis, writing, a change across files, a
  chain of tool calls. It is brainyard's `react-agent` under that name: the
  ReAct loop with every tool its caller has except itself. Its instruction
  tells it to break the goal into ordered steps (a `todo$` checklist for a
  long task) and to pick a tool for each step, whether the shell, files,
  MCP servers, skills or the other agents. It checks every result, and on
  an error or a surprise finds the cause and revises the plan until the
  goal is met. It answers with the outcome, how that was checked, and what
  is left (byj-63b).
- **A handoff or workflow link in `deps.edn` can give an issue**, not only a
  bead: `{:kind :issue :id "#42"}` is ready when the issue is closed in the
  tracker of the profile's `:issues` that holds it — GitHub through `gh` in
  the profile's `:github-repo`, beads through `bd`; a `KEY-123` in Linear or
  Jira is said as not checked yet, and `:tracker` names one outright.
  `{:kind :bead …}` keeps its meaning (byj-ykq, #3).
- **The profile says where the project's issues are kept, and the goal has
  no epic** (RFC-073, byj-yq9): `:issues [{:tracker :beads} {:tracker
  :github :github-repo "owner/name"} …]`, one tracker or several of
  `:beads`, `:github`, `:linear` and `:jira`, each with its place
  (`:github-repo`, `:linear-team`, `:jira-site`, `:jira-project`) and a
  `:name` to tell two of one kind apart. `/init` writes it (beads, and
  GitHub with a github.com remote) and files its first issues under no epic,
  `profile$edit` replaces it whole, and AGENTS.md's block says it in one
  line. `:goal-epic` is deprecated: an older profile still loads, nothing
  reads it, `/drive` works the project's ready beads, and `profile$edit`
  drops it when it writes.
- **The right rail is ctrl+r**, no longer ctrl+t; `/right` still opens and
  closes it (byj-0x0).
- **A session `byj acp` holds can listen, and be shared** (RFC-053,
  byj-p2q): `session/new`/`session/load` with `_meta.brainyard.channel`
  open its session channel, so another session, a script or a share's
  sidecar puts turns in; the client is told each such turn (the question
  with `injectedBy`, its answer, `_byj/turn` started/ended) and asked to
  allow a share viewer's turn that needs the owner (`session/request_permission`).
  `_byj/share {sessionId args}` runs `/share` for it — start, stop, pause,
  record — the sidecar a background task of the session. A workbench shares
  the sessions it runs this way.
- **A turn's iteration ceiling is 100**, brainyard's: `max-iterations` was
  20 and `subagent-max-iterations` 12. A setting, an agent's own
  `:max-iterations` and `-n N` still set it (byj-d8n).
- **`byj deps`** (RFC-075): a project's dependencies — the projects it uses
  or is affected by, and what a session playing one role hands to one
  playing another — kept in `.brainyard/deps.edn` (committed) and
  `deps-local.edn` (this machine's sessions bound to each role, and what was
  handed). `byj deps --json` reads both, finds each project by its path or
  the registry's origin (an SSH host alias, `git@github-work:o/r.git`, as
  the host `ssh -G` resolves it to), checks what it provides (files there, a binary on
  PATH at a version), lists its commits since it was last reviewed, says
  which sessions play each role and whether each handoff's files, beads,
  branch and note are ready, with every check as `{level kind target
  message}`. `byj deps write [--local]` takes the whole new file as JSON on
  stdin, refuses a schema problem, a cycle or a handoff naming no role,
  snapshots the old file and writes the new one in a stable order. Its
  shape is byj's contract, `contract/deps/deps.json`.
- `/init`'s `.gitignore` excepts `.brainyard/deps.edn` beside the profile,
  so the dependencies' spec can be committed; one an earlier `/init` wrote
  gets the line from the next, and `/doctor` and `/init`'s check mark it ✗
  until then.
- **A session's first question is `-q TEXT`**: `byj -q "explain
  deps.edn"` where it was `byj "explain deps.edn"`; each `-q` is a turn of
  its own, in order. A first word that names no command is now refused as an
  unknown command — on stderr with how to ask instead, exit 2; with
  `--json`, `{"error": "unknown command WORD"}`, exit 1 — where it was run
  as a question: a typo, or a client probing a command an older byj lacks,
  became a model's turn and a saved session. `byj run WORD…` is refused
  too. `byj ask QUESTION…`, `--prompt`, `-q` and `--attach` are as they
  were.

## v0.3.20 — 2026-11-18

- **A project's profile** (RFC-073): what the project is for — its goal,
  scope and done criteria — and how far an agent goes on its own: filing,
  working and closing beads, committing, deciding, and pushing, each at
  `:never`, `:ask` or `:act`, a push and a change of scope never past
  `:ask`; from one of three presets (conservative, collaborator, autopilot).
  It is `.brainyard/profile.edn`, committed, and no tool writes it:
  - `/init` drafts it when you say what the project is for, shows it with
    the epic and first issues it would file, and writes it only on your yes;
  - AGENTS.md shows it in a `BRAINYARD:profile` block, which `/init check`
    and `/doctor` keep in step with the file;
  - `profile$edit` changes it as you ask, showing each field old and new,
    and writes only on your yes, asked every time;
  - the permission gate holds it: an axis at `:never` refuses what it
    covers, one at `:ask` asks you in every mode, one at `:act` leaves it to
    the rest of the gate; a command a profile lets the agent run on its own
    is not put to the auto-mode classifier;
  - a shell line that only reads the profile runs, one that may change it
    asks you, and `rm -rf .brainyard` while it holds a profile or a loop is
    refused.
- **`/drive` works through the project's goal under its profile**: it takes
  the next ready bead under the goal's epic, works it in a turn of its own,
  checks the work tree and the bead, and closes it as the profile says —
  asking you where the profile says to ask, and stopping at its limits. A
  bead gets at most `:drive-bead-attempts` turns in a run (3), then is set
  aside for you, labelled `human`; a bead a cancelled run left claimed is
  released by the next run. `/init`'s next steps offer it once there is a
  profile.
- **`/init`'s `.gitignore` keeps the profile**: `.brainyard/*` then
  `!.brainyard/profile.edn`, and an earlier `/init`'s `.brainyard/` is
  turned into that. It names bd's conservative git default (agents commit
  only when asked) as yours to state, with prompts that write either answer.
- **`byj projects`**: the projects byj and brainyard's `by` have run in, one
  registry on a machine that runs both (`~/.brainyard/projects/`). A session
  registers its project as it starts; `byj projects list|path|add|prune|remove`
  inspect it, `prune` and `remove` asking first.
- **For an application on byj's ACP** (RFC-053, RFC-069):
  - `session/load` replays every turn from the first, not only the last 500
    lines of the transcript, each update naming its turn, a turn's first its
    checkpoint (what a rewind of it goes back to), and each tool call its
    start and duration; a live turn's updates and `session/prompt`'s result
    name the turn, the files it changed and its checkpoint;
  - `_byj/turns/list`, `diff`, `rewind`, `undoTurn` and `undoRewind` take a
    session's turns back by method, answering with what changed on disk; a
    turn a later change conflicts with in every file is said to be kept back,
    not already taken back;
  - `session/new` forks before a turn (`forkBeforeTurn`), and opens a fork
    or a new session in a git worktree of its own (`worktree`), the
    project's work tree left as it was;
  - what a session says beside its answer while it answers — a turn waiting
    for an MCP server, the servers changed, a command's words — reaches the
    client as a notice, marked apart from the answer;
  - `session/new`, `load` and `resume` say the provider and model the
    session runs on, and `session/list` the model a session chose.
- **`byj doctor --json`** and **`byj config`**, for a client that shows them
  in parts: the doctor report as data (the tools, the project's checks, what
  is installed, the account and its windows); every setting with its type,
  default, value, source and whether it may be written, and the providers,
  models and permission modes to choose from. `byj config set KEY VALUE
  [--scope project|user]` and `byj config unset KEY` write one as a
  session's `/config` does — never a permission key, the schema and a secret
  scan first, a snapshot kept. Both shapes are byj's contract
  (`contract/doctor/`, `contract/config/`).
- **A session built while one of its MCP servers finishes starting gets the
  server's tools** from its next turn. It could run every turn without them,
  saying nothing, when the server finished in the moment the session's tools
  were read (#1).
- **A session keeps its own turns to take back.** A session used in the last
  `:changes-days` (30) keeps its newest `:changes-turns` turns however many
  other sessions end, so a long session's first turns can still be rewound,
  undone, forked before or diffed while it is open. Deleting a session over
  ACP deletes its turn refs.
- **A trajectory record names the session's turn** (`:turn`), as `byj turns
  list` numbers it, and a subagent's the turn it ran in; each tool call
  keeps its start and duration. `trajectory$export`'s `:turns` range and
  `session$analytics` name turns the same way.
- **Listing a directory that holds a login or a key is no longer refused**:
  `ls ~/.brainyard`, `stat`, `find` with nothing that opens a file, and
  `list-dir`. Reading what is in it still is.
- **One skill ships in byj**, `skill-authoring`, read-only; a project's or
  your own skill of the same name takes its place, and
  `:builtin-skills? false` drops it.
- **Sharing a session with a recording records it**: `/share start --record`
  started the sidecar unrecorded.
- **byj starts faster on macOS** (0.56 s against 0.76 s), built with jolt
  v0.8.27.

- **A project can add to the ReAct loop without copying it** (RFC-072).
  The loop now has slots, each empty unless a project fills it:
  - after the tools run: what it finds reaches the next iteration as a
    notice;
  - before an answer is final: it can refuse the answer, with its reason,
    up to `:max-answer-checks` times in a turn.

  `byj loops attach SLOT FILE` evaluates the loop with the tree attached,
  and stages it at `/proposals` when it wins. `byj loops detach SLOT NAME`
  stages taking one off. A person accepts either. Attachments live under
  `.brainyard/loops/ext/`, which no tool writes. `byj loops watch` and
  `byj loops revert` treat an attach as they do any loop change, and
  `doctor` names an attachment whose slot has changed. Both slots read
  `:turn-start`, the work tree's snapshot as the turn began, so an
  attachment can judge only what the turn changed and not what it found.
- **A third slot, at the start of a turn** (`:react/ext-question-check`).
  An attachment there can add context for the question: what a search of
  the project, an index or the web found, carried in every call of the
  turn. It can also propose the question asked differently. You are asked
  whether to use it, and only a yes changes the question. The trajectory
  keeps what you asked as `:asked`.
- **A call a loop's tree made says so in the turn's record**: `:node`,
  and `:slot` when a slot's attachment made it. `byj loops eval` and
  `byj loops watch` count tool errors and refusals from the model's calls
  only, so an attachment's check that fails on purpose is not a
  regression.

- **A command with `//` in it is no longer refused as naming a login or a
  key.** jq's `a // b` and Python's `7 // 2` were read as the root
  directory, which holds them all. A refusal now says which word in the
  command reached the login or key.
- **A turn's checkpoint keeps a same-size edit made in the second the
  person's index was written.** It could be left out when the turn ended in
  the next second, so a rewind did not put the file back and the turn's
  diff did not show it (RFC-069).
- **A turn's record says which loop it ran**: `:loop {:hash}` in
  `trajectory.edn`, the hash of the tree as it ran, and `byj flow check
  --json` gives a loop file's as `:expanded-hash` — so a harness running
  byj on a loop of its choosing (`byj ask --loop FILE`) can show every
  trial ran it.
- **What a change to a bundled loop must carry** is written down (RFC-071
  "Promotion to the bundled loops", CONTRIBUTING.md): evaluations in three
  projects or more, every one of them, and the dogfood simulation showing no
  harm.
- **A loop evaluation names its failed calls**: each run's row keeps the tool
  and the first line of what each failed call said (the first five), and
  `byj loops eval` prints them with alike ones together, per arm.
- **A refusal is a call that failed and says so**, in `byj loops eval` and
  `byj loops watch`: a read of a file that mentions refusals was counted as
  one, and `loops watch` missed a gate's block.
- **`byj loops eval --keep` keeps the clones**: it kept each, then removed
  the directory that held them. The report says where they are.
- **`byj loops watch`** compares the turns run since a project's loop last
  changed with the turns before, and when they got worse — costlier, longer,
  more errors or refusals, more turns left unfinished — stages the loop
  before as a revert at `/proposals`. It never accepts one.
- **`byj loops tasks build --judge LM`** asks a judge model, on the light
  tier unless one is named, whether each task's turn did what was asked, and
  keeps its verdict beside the task as a second signal — never the only one.
  It spends at most `--max-cost` (0.25 USD).
- **A rewind says the last turn it came after** ("rewind to turn K's start,
  after turn N"), so a loop's task set knows exactly which turns it took
  back, not from commit times to the second.
- **`byj loops tasks retire ID…`** takes tasks out of a set and releases the
  refs that kept their start commits; a rebuild does not bring them back.
- **`byj loops eval` runs each turn in a byj of its own under the macOS
  seatbelt**, its clone the place it may write, so a shell command in a
  candidate's turn cannot reach the project by a path the gates miss;
  unsandboxed off macOS, and in the evaluating byj with `--in-process`. The
  report says which. A lead on too few tasks to test is no longer a win.
- **A loop's task set finds its own check.** `byj loops tasks build` takes,
  when given no `--check`, a dogfood plan's stage checks (`--plan FILE
  [--stage ID]`), else the set's own, else the project's new
  `:check-command` config key, and says which it took.
- **`byj loops propose CANDIDATE`** evaluates a candidate loop and stages it
  at `/proposals` only when it wins; otherwise it says why and stages
  nothing. `byj loops propose --bundled` evaluates byj's own loop against a
  project's: when the project's no longer beats it, its removal is proposed —
  what `byj doctor` now names once an upgrade changed the loop it was made
  from.
- **`byj loops eval CANDIDATE`** runs a candidate loop against the loop in
  force on a task set (`byj loops tasks build`): each run a headless turn in
  a clone of the repository at the task's start, so nothing of the
  project's is touched; scored by the task's checks, compared per task, with
  guards on cost, iterations, time, tool errors and refusals, and a
  `--max-cost` after which no run starts. The report is written under
  `.brainyard/loops/evals/`. A live model costs money: it runs when asked.
- **A better loop is a proposal at `/proposals`** (`loop/…`): staged with its
  evidence and why, shown as a diff of the expanded tree against the loop in
  force, and made the project's loop by a person's accept — from the next
  session, which the session says. A candidate that fails `flow check`, or
  names a permissions setting, is not staged; one proposed against a loop
  that has since changed is not accepted. `byj loops revert [LOOP]` stages
  the loop before the last change as a proposal of its own.
- **No tool writes a project's loop.** A file in `.brainyard/loops/` is the
  project's loop from the next session, so writing one, its `history.edn` or
  the directory is refused for every tool in every mode, `flow$patch`
  included, and for Claude Code and Codex through byj's hook; a shell command
  that names one is refused too. Reading one is not. Write a candidate loop
  to another file and try it with `--loop`.
- **`flow$patch` meets the path gate**: the file it patches is checked
  against the allowed directories, as `write-file`'s is.
- **A workflow's predictor step is drawn behind `◇`**, a tool's behind `⏺`:
  `agent$claude`'s view showed `claude/plan(…)` and `claude$open(…)` alike,
  so its choice of session and brief read as a tool the model had called.
  `claude/plan` is a predictor, a model call the loop makes; `claude$open`
  is a tool.
- **Under Codex, byj's hook refuses what it would ask a person about**,
  where before it answered "ask", which Codex rejects as unsupported, so the
  call ran as if byj had said nothing. Codex's hook cannot ask, and its
  default mode runs a sandboxed command unasked, so byj refuses it and says
  why, for the model to ask you; allow it in byj's config to let it run.
  Claude Code is still asked.
- **byj's hook for Claude Code and Codex answers in tens of milliseconds**:
  the command `byj hook claude-settings` and `byj hook codex-flags` install
  now asks `byj hook serve`, a byj kept up on a unix socket
  (`~/.brainyard/hook.sock`), with curl, so a tool call no longer pays byj's
  start — 36 ms against 0.63 s, measured. The first call finds none,
  is answered as before and starts it; it exits after 20 minutes with no
  call, and when byj is upgraded. A command installed before still works, at
  its old cost: print the settings again to get the new one.
- **`byj hook claude-settings`** is the new name of `byj hook settings`, which
  prints the Claude Code settings that install byj's gates as its PreToolUse
  hook: named for the agent, as `byj hook codex-flags` is. `byj hook
  settings` still answers, for a script written against v0.3.18 or v0.3.19.

## v0.3.19 — 2026-11-12

- **byj for arm64 Linux** (Graviton, Ampere, a Raspberry Pi 4 or 5 on a
  64-bit OS, a Linux container on an Apple-silicon Mac): a release now has
  `byj-<version>-linux-arm64`, and Homebrew on such a machine installs it.
  jolt publishes no arm64 Linux build, so the release builds jolt from its
  source first, as jolt's own release builds its x86_64 one; byj's suite
  runs on it nightly.

## v0.3.18 — 2026-11-12

- **`byj` alone starts a session.** `run` is now the default command:
  `byj` opens one, and `byj "explain deps.edn"` opens one whose first turn
  is that, as `claude "…"` does. One question answered and done is
  `byj --prompt "…"` (or `byj run --prompt`), as `claude -p` is; `-p` is
  still `--provider`. `byj ask "…"` is unchanged, so a script that runs
  `byj "…"` for one answer should say `byj ask` or `--prompt`.
  `byj --attach SESSION "…"` still asks that session.
- **`claude$await` shows a reply that came before the wait began.** A
  delegated Claude Code session that answered between `claude$say` and
  `claude$await` had its reply returned but never streamed as progress;
  each wait now picks up where the last one stopped.
- **Every source file names its licence**: the two-line SPDX header,
  `Copyright (c) 2026 Grumatic, Inc. and byj contributors` and
  `SPDX-License-Identifier: MIT`. `LICENSE` and `NOTICE` name the
  contributors too, since a contribution signed off under the DCO stays its
  author's.

## v0.3.17 — 2026-11-11

- **byj is published**, under the MIT License, from a repository of its
  own. Releases before it were made while byj was developed in a private
  one; their notes were not carried over, and the tap keeps their binaries.
- **byj is released alone** (RFC-029): a release is byj and nothing else,
  and on the tap it is always the latest release, which `byj update` reads,
  so a byj installed before updates as it did.
- **A bash call that leaves something running no longer ends your shell on
  Ubuntu** (provenance P-62): stopping what it left now names the process
  group in a way Ubuntu 22.04's `kill` reads as that group, not its own.
- **What a delegated session leaves running in a worktree that is removed is
  stopped on Linux too**: a deleted directory reads as `<path> (deleted)`
  there, and byj now sees it.
