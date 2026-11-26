**byj v0.3.21** — what a project's runs leave is kept in your home and `.brainyard/` is the repository's, workflows run through their stages, and a live Claude Code session is handed work through its inbox. Full notes in `CHANGELOG.md`.

- **A session can be deleted softly, and a project too.** An application's
  `session/delete` with `_meta.brainyard.soft` marks the session deleted and
  keeps it: it is out of every list unless asked for
  (`_meta.brainyard.deleted`), and `_byj/session/undelete` lists it again.
  `byj project delete|undelete|unlist` does the same for a project, its
  folder in your home and its sessions kept; `byj project add` lists it
  again.
- **A deleted session takes what it started with it.** Deleting a session
  forgets the Claude Code and Codex sessions it delegated to, ending them on
  byj's tmux server; `byj session delegates forget ID` does it for one left
  behind. With `_meta.brainyard.members`, a leader's members and the stage
  sessions of the workflow runs it ran are deleted after it. Run records,
  branches and worktrees are left. A session that never ran a turn can be
  deleted too.
- **A session is saved when it starts**, not after its first turn, so an
  application lists and opens one that has not run a turn yet. One with
  nothing to bring back is removed when it ends, so a run started and quit
  leaves nothing. A session resumed goes on in the worktree it worked in, and
  a team member keeps its leader (`byj session list`, `session/list`).
- **byj runs its Claude Code and Codex sessions on a tmux server of its
  own** (`tmux -L byj`, without your `~/.tmux.conf`; `BYJ_TMUX_SOCKET` names
  another), and the rail's terminals too. `byj session delegates --json`
  lists a project's delegations with the session that started each and how
  to attach, and `byj session attach ID` resumes a saved one there.
- **More for a workflow run.** `/workflow run … by hand` waits after each
  stage for you to hand it on; `/workflow resume N` goes on with a waiting or
  stopped run; `/workflow again N [what to change]` runs an ended one again
  on the same work; `/workflow run … from BRANCH` builds on another branch
  or commit in its own worktree. `byj workflow stop N [WHY]` stops a run from
  outside the session running it, and `byj workflow remove N` removes an
  ended run's record. A stage in a session of its own writes its progress —
  tool calls, last thought, spend — to the run while it works. The master
  role can be a live session that is told how the run goes
  (`:workflow-master-tell`); the judge still decides.
- **`byj profile [--json]` and `byj profile write`** read the project's
  profile and write a new one through byj's checks, refused whole when
  anything is wrong, as `byj deps write` does. An agent running `byj
  profile write` asks you every time.
- **More for an application on byj's ACP.** `_byj/session/history` replays
  a saved session's turns read-only, a workflow stage's headless session
  included. `session/list` says where a session worked
  (`_meta.brainyard.workingDir`). A subagent's tool calls come as its own,
  under the call that started it, and a loaded session replays them. Notes
  for people reach the client, and so does a turn byj starts on its own (a
  task's wakeup, a scheduled prompt) with its question. `session/prompt` is
  answered once the turn's answer is saved, not after the recap. A
  permission request carries what you are asked about, and a lookup in
  byj's tool registry is a `search` call.
- **A team member or workflow stage in a worktree works there as in any
  project.** Its project is the worktree: it reads the `.brainyard/` its
  branch has, keeps its own sessions and log, cannot reach the main
  checkout, and its turns are the worktree's. A new worktree is given the
  ignored files `.worktreeinclude` names (gitignore syntax, as Claude Code
  reads it) — `.env` and `.brainyard/.env` when there is none — and byj says
  when your `.brainyard/` has uncommitted changes the worktree will not have.
- **`byj project` is one command.** `byj projects list|path|add|prune|remove|move`
  and `byj project path [KIND]` are now `byj project …`; `byj projects` still
  works and reaches the same command. `byj project path` takes a kind
  (`logs`, `sessions` …) for this project's folder in your home, or a slug
  for a registered project's directory.
- **A live Claude Code session is handed work through its inbox.** With
  Claude Code 2.1.224 or later, `session$send`, `session$ask` and `/handover`
  write to the session's own messaging socket instead of typing into its
  tmux pane: a session in a terminal of its own is reachable now, and a long
  brief goes whole, with no file in `.brainyard/handovers/`. It is sent only
  while the session is idle — a message sent during a turn is taken into that
  turn and goes unanswered, and byj says so. An older Claude Code is typed to
  in tmux as before. `agent$claude` keeps typing its sessions'
  guidance into their tmux pane, as your own words, and uses the inbox only
  for one with no pane: Claude Code tells the model an inbox message is a
  teammate's request, which may not have it edit its permission settings,
  CLAUDE.md or config.
- **What byj's runs leave is kept in your home, one folder per project;
  `.brainyard/` is the repository's.** A project's sessions, transcripts,
  tasks, event log, Claude Code and Codex delegation records, workflow runs,
  todos, store, `deps-local.edn` and new git worktrees are kept in
  `~/.brainyard/projects/<project>/` — the folder the project registry
  already keeps for it — so a worktree's sessions outlive the worktree and a
  project made again at its path finds them. What a project's `.brainyard/`
  held of these is moved there the first time byj runs in it, but for a
  session an older byj still holds (read where it is until it is free) and a
  git worktree (left where it is, so git and a delegated session's resume
  still find it). `byj project path [KIND] [--json]` says where (`byj
  project path logs`); `byj session path` and `byj session migrate [--all]`
  as before (RFC-016).
- **What byj makes from your sessions is kept with them, not in the
  repository.** A loop task set (`byj loops tasks build`), tool and skill
  proposals, and a program's datasets, evals and proposals are in
  `~/.brainyard/projects/<project>/`, moved from `.brainyard/` the first time.
  What you write or accept stays in `.brainyard/` and is shared: the cases
  you write for a task set (`<set>.cases.edn`), the tools and skills you
  accept, a program's params.
- **`.brainyard/` is no longer kept from git.** It holds what the project
  shares — config, profile, dependencies' spec, agents, commands, skills,
  tools, programs, loops. `/init` takes the `.brainyard/*` lines an earlier
  `/init` wrote out of `.gitignore` and writes `.brainyard/.gitignore`
  naming what is not the repository's (`.env`, handovers, and what an older
  byj left there); `byj doctor` says where a project stands. An edit a turn
  makes to `.brainyard/config.edn` is now part of the turn's change, and
  rewound with it.
- **`byj project` keeps what it holds.** `prune` keeps a missing project's
  folder while a session in it is younger than `:session-ttl-days` or open,
  and says how many sessions go; `remove` counts them and refuses while one
  is open; new `byj project move OLD-SLUG [DIR]` adopts a moved or renamed
  project's folder and its sessions at DIR. A git worktree is listed as a
  project of its own, with its main checkout's slug (`worktree-of`), and
  `byj project list` shows worktrees and the folders of unregistered runs
  only with `--all`. The sessions of a project that is gone are kept until
  `:session-ttl-days` (14), then pruned.
- **A project outside git is one project from any of its subdirectories**:
  the nearest directory above whose `.brainyard/` holds something the
  project shares (`config.edn`, `agents/` …) is its root.
- **An agent's tools cannot read another project's folder** in
  `~/.brainyard/projects` — its sessions, log, records; `:project-homes-shared?
  true` lets them.
- **A program finds a session where byj says it is.** `byj session list
  --json` gives each session's `dir` and `project-dir`, and the new `byj
  session path ID [--json]` gives one session's folder (exit 1 when there is
  none); a live session's `meta.edn` names its `:project-dir`; `byj project
  path --json` gives the project's folder and each kind's. Read through
  these rather than building `.brainyard/sessions/<id>`.
- **A `:bead` give in `.brainyard/deps.edn` is deprecated: write it as an
  issue.** A bead is an issue, and `{:kind :issue :tracker :beads :id …}`
  (or `:parent`) says the same. A `:bead` give still reads and is checked
  as before, but `byj deps`, `byj doctor` and `byj deps write` warn about
  each one (`deprecated-give`) and say what to write instead. `/workflow`
  offers only `:issue`, and writes a workflow's `:bead` gives as issues
  when it saves one, telling you so.
- **`byj ask --agent` works for an agent with a loop of its own.** `byj ask
  --agent drive`, `init` or `workflow` (and `byj --prompt` with one) had
  failed at once with "invalid loop tree: unregistered fns: :session/…";
  the agent's loop now runs as the turn's tree, as it does for a subagent.
- **`/workflow` keeps a project's workflows.** `agent$workflow` lists the
  workflows `.brainyard/deps.edn` names, shows one whole, and adds, changes
  or removes one as you ask. Each change is shown to you first, and written
  through `byj deps write`'s checks only on your yes (RFC-076).
- **`/workflow run` runs one.** `/workflow run fix byj-abc` takes the work
  — words, or a bead or GitHub issue first — through the workflow's stages,
  each a turn of its own told its role, the project's goal and scope, and
  what the last stage handed it. After each stage, what it hands on is
  checked (files changed, commits, its answer, a bead closed), and the
  workflow's master role judges it: hand on, go again (three turns a stage),
  or stop with a question for you. The profile's `:usd` and `:failures`
  limits hold. A bead or issue the run works is claimed, and closed at the
  end as the profile allows. Runs are kept in your home (`byj project path
  workflow-runs`); `/workflow` lists them and keeps your verdict on one. New config:
  `:workflow-max-steps` (30), `:workflow-stage-attempts` (3).
- **A workflow run can work apart, and a role can have its own session.**
  `/workflow run feature … in a worktree` works on a branch of its own
  (`workflow/<workflow>-<n>`), each stage in a session there. A role whose
  `:session` is `:own` gets a session of its own, in a new worktree when it
  says `:worktree`. Each session's permission prompts come to you. The
  run's sessions are bound to their roles in `deps-local.edn`, so `byj
  deps` sees them. The report closes them and says the worktrees, which are
  kept for you to merge. What those sessions spend counts toward the
  profile's `:usd` limit. New config: `:workflow-stage-timeout-ms` (an
  hour).
- **`byj workflow runs [N] [--json]`** gives the runs `/workflow` keeps, as
  data for a client: byj's contract, `contract/workflow/runs.json`. `byj
  workflow verdict N pass|changes|fail [NOTE]` keeps your word on one.
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
