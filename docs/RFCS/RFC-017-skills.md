# RFC-017 — Skills

**Status:** loading implemented (`byj-gly.15`), `dispatch: agent` implemented
(`byj-gly.43`), writing and self-improvement (distillation, refinement, staged
proposals) implemented (`byj-gly.246`), built-in skills shipped in the
binary implemented (`byj-kuk`).

## Purpose

Specifies how a named procedure written as `SKILL.md` — by a person, or by an
agent that learned it — reaches the model without costing the prompt. brainyard
sources: `agent/common/skills.clj` (roots, parsing, ranking,
`register-dynamic-skill!`, `skills$write`), `common/agent_roster.clj`
(`skill-substrate-protocol`), `common/skill_agent.clj`, `common/skill_distill.clj`,
`common/skill_refine.clj`, `common/skill_distill/{proposals,signatures}.clj`;
docs `design/skills.md`, `design/self-improve-design.md`.

## Scope

**This layer decides** where skills come from, what a skill is, what the
model sees before and after it asks for one, how a skill is written, and how
a draft the agent proposed waits for a person before it is one.

**It must not know** what any skill says.

**It hands** registry entries to the loop (RFC-010), a listing to the
session-context zone, and the self-improvement leaves to the session tree
(RFC-004), which decides when they run.

## Model

### A skill is a directory

```
<root>/<name>/SKILL.md
```

The directory name is the skill's name. `SKILL.md` is markdown with optional
frontmatter:

```markdown
---
name: lint-markdown
description: Lint markdown files and fix what it reports.
tags: markdown, lint
---

# Lint Markdown
1. Run scripts/lint.sh
2. Fix what it reports
```

**Nothing is required.** The title falls back from `title` to `name` to the
first `#` heading to the directory name; the description falls back to the
first non-heading line. A file with no frontmatter loads (this machine has
one), and so does one whose frontmatter never closes — its whole text becomes
the body. A directory without `SKILL.md` is skipped rather than reported.

### Roots, most local first

| root | backend | scope |
|---|---|---|
| `<project-dir>/.brainyard/skills` | brainyard | project |
| `<project-dir>/.claude/skills` | claude | project |
| `<project-dir>/.agents/skills` | agents | project |
| `~/.brainyard/skills` | brainyard | user |
| `~/.claude/skills` | claude | user |
| `~/.agents/skills` | agents | user |
| `resources/brainyard/skills` (embedded in the binary) | builtin | builtin |

brainyard's backend order, and its reason for reading the other two at all: a
skill written for another harness is still a procedure. brainyard applies that
only to `$HOME`; here it applies to the project too, where a repository already
keeps the procedures its other harnesses use — this one's `bd` workflow lives in
`.claude/skills/beads/` and was invisible until it did (divergence
`project-scope-harness-skills`). `:skill-roots` replaces the list — a shared
team library, or one project's skills in a test. `~` is `$HOME` unless the
`brainyard.skills-home` property names another: the test runner sets it to an
empty directory, so the suite never lists the person's own skills, which every
prompt names and a test sized to a context window overflowed by (byj-gly.467).

**A name defined twice** keeps the most local as `skill$<name>`; the others
stay reachable as `skill$<backend>$<name>`, so nothing silently disappears. One
backend now spans two scopes, so a second shadowed skill of the same backend
takes `skill$<backend>$<scope>$<name>` rather than overwriting the first.

Skills are re-read on every call, so editing one takes effect on the next turn
without a reload.

### Built-in skills: shipped in the binary (`byj-kuk`)

byj ships procedures of its own, as it ships its agent roster (RFC-018
`builtin-agents`); brainyard ships none (divergence `builtin-skills`). They
are the last root, always — after `:skill-roots` too, as the built-in roster
follows `:agent-roots` — so a project or user skill of the same name shadows
one, which stays reachable as `skill$builtin$<name>` and is named under
`skills$find`'s `:also-in` as `builtin`. `:builtin-skills? false` drops them.

```
resources/brainyard/skills/index.edn          ["skill-authoring" …]
resources/brainyard/skills/<name>/SKILL.md
resources/brainyard/skills/<name>/resources/… (only the files the index names)
```

- **An index**, because a built binary cannot list a resource directory (as
  `agents/index.edn` and `tools/index.edn`). An entry is the skill's name, or
  `{:name n :resources ["file" …]}`. An entry whose SKILL.md is missing is
  skipped, as a directory without one is.
- **Typed and scoped `builtin`**: `skills$list` and `skills$read` take
  `:type builtin` and `:scope builtin`. Its `:path` is
  `builtin:brainyard/skills/<name>/SKILL.md` — a resource, never a path on
  disk a tool could open.
- **Resources come with the call.** A skill on disk names its `resources/`
  files and the model reads them; an embedded one cannot be read with
  `read-file`, so calling a built-in skill returns the files its index entry
  names under `:resources`, file name to text, read through `io/resource`.
  They are meant to be small.
- **No scripts.** An embedded file is not on disk for bash to run. The other
  way, writing a skill's scripts to a cache directory before bash runs them,
  costs a cache to keep in step with the binary, an executable written
  outside the project, and a staleness rule, for a need no built-in skill
  has yet. A built-in skill whose procedure needs a script names a command
  that is on the machine, or is not a built-in skill. The index has no
  `:scripts` key, and `skills-test/the-shipped-built-in-skills-load-and-carry-no-scripts`
  fails on one.
- **Read-only.** See "Writing a skill": no write reaches one. A person who
  wants a built-in skill changed copies its SKILL.md into the project's
  `.brainyard/skills/<name>/`, where it shadows the shipped one.

The tests pass `:builtin-skills? false` wherever they count what is listed
(the fixture configs of `skills-test` and `self-improve-test`), as
`agents-test` passes `:builtin-agents? false`, rather than the runner turning
them off for every test: the shipped skills are what every session lists, and
the suite's sessions list them too — `contract/agent/agents.json` counts the
tools each agent is given, the built-in skills among them.

### Progressive disclosure — the point

The prompt carries **one line per skill**: its id and what it is for —
its title, when the title says something its name does not, else the first
sentence of its description, clipped to `:skill-line-chars` (80)
(`skills/listing-text`, byj-gly.336). The body
arrives only when the model calls `skill$<name>`, which returns the whole
`SKILL.md` as the tool result. The model then follows it in its own iterations;
nothing runs it.

This is not a stylistic choice, it is the difference between usable and not.
Measured on this machine's 48 skills:

| arrangement | session-context |
|---|---:|
| no skills | 15,133 chars |
| skills rendered as full tool specs | 73,205 chars (**+14,500 tokens**) |
| skills as one line each (shipped) | 18,437 chars (**+826 tokens**) |

The first attempt here did render them as full specs, because that is what
every other tool does. brainyard avoids it by keeping dynamic skills off the
agent roster; this port keeps them out of the `### Agent Tools` block for the
same reason. Beyond `:max-listed-skills` (40) the list ends with
`…and N more — skills$find searches them all`.

**Why not the title alone** (byj-gly.336). A Claude Code or agents SKILL.md
has only `name` and `description`, so its title falls back to its name: 51 of
this machine's 55 skills were listed as `skill$x — x`, and the model saw that
something called x existed, not when to reach for it. With the description's
first sentence the 55 lines take 4,730 characters instead of 2,048 — about
+670 tokens before `:max-listed-skills` cuts the list at 40, against the
+14,500 of full specs.

Measured with an evaluation's skill-worth machinery: the ledger skill
renamed to the opaque `acct-proc`, the same three tasks, claude-code/haiku,
k 2, the binary before and after this change (2026-10-10):

| listing | got the procedure | tools a trial | input tokens | cost a trial |
|---|---|---|---|---|
| `skill$acct-proc — acct-proc` | 6/6 | ~7 | 102K | $0.0065 |
| `skill$acct-proc — Record an expense in, or close a month of, the team's…` | 6/6 | ~4 | 68K | $0.0041 |

Both found it — it was the only skill listed — but under its name alone the
model went on listing the workspace and reading the script as if unsure the
skill applied; told what it was for, it followed it and stopped. Twelve
trials: a direction, not a rate.

### Finding and reading

`skills$find` ranks with brainyard's weights — a token in the name is worth
most (100 exact / 40 prefix / 25 substring), then tags, title, and a flat 6 for
a description hit. Tokens under 3 characters are dropped and a score under 10
is not a match, so an empty result means "you have no such skill", not "search
failed". One difference: a description in which every token starts a word
scores 10, a match by itself — brainyard's 6 never reached 10, so a skill with
only a name and a description, as Claude Code's and agents' are, was never
found by what it is for (divergence `skill-description-matches`, byj-gly.375).

| command | args | returns |
|---|---|---|
| `skills$list` | `type?` (`brainyard`/`claude`/`agents`), `scope?` (`project`/`user`) | `{:result [{:name :title :description :type :scope :path :id}] :count}`, most local first |
| `skills$find` | `query`, `type?`, `limit?` | `{:result [… :score :also-in?] :count}`, ranked; one hit per name, the most local, `:also-in` naming the backends the name is also installed in |
| `skills$read` | `skill-name`, `type?`, `scope?` | the skill — `:name :title :description :content :path :type :scope :size` — for peeking at one you are not committing to |

These are brainyard's shapes and argument names (`common/skills.clj`,
byj-gly.202, `skills-test/a-skill-is-a-tool-whose-body-arrives-only-when-called`).
Each entry also carries the `:id` that calls it (byj-gly.200), which
brainyard's does not: divergence `skills-results-carry-their-tool-id`.
| `skill$<name>` | `question?` | `{:skill :type :path :content :loaded true}`, or `{:skill :answer :dispatched true}` for a skill that runs itself |

A loaded skill is `:kind :skill` in the registry, so `list-tools :type "skill"`
finds them — the gap RFC-007 recorded.

### A skill that runs itself

`dispatch: agent` in the frontmatter is brainyard's opt-out from the default:
instead of the procedure being read into the calling turn, the call runs a child
turn whose instruction is the procedure, and returns its **answer**
(RFC-018). It is for a procedure better executed in an isolated context than
followed inline — a long mechanical one whose intermediate steps the caller does
not need.

The `question` is the task, and a blank one is refused: there is nothing to run
without it. The child leaves no instance behind (brainyard's `:auto-close? true`
on this path alone), because a procedure someone asked for is not a colleague to
keep on hand, and registering one would evict an instance that is. `:dispatch` is
read from the fresh read on every call, as brainyard does, so flipping the
frontmatter takes effect on the next call rather than the next reload.

With `:enable-subagent-calls false` the call falls back to handing over the
procedure with a note saying why.

The prompt's `### Skills` section carries brainyard's substrate protocol,
trimmed: discover, load, follow, and the rule that a skill is preferred over
hand-rolling the same steps but is not worth searching for on every turn.

### Writing a skill

`skills$write` is brainyard's one write command, with `op` one of `create`,
`update` or `remove`:

| op | needs | does | returns |
|---|---|---|---|
| `create` | `skill-name`, `content` | writes `content` as `SKILL.md` — the whole file, frontmatter included; nothing is generated — plus `scripts` (a `.sh` made executable) and `resources`; refuses a name that exists | the parsed skill `:name :title :description [:tags] …` with `:type :scope :path :created :id` |
| `update` | `skill-name` | replaces `SKILL.md` when `content` is given, and writes extras; no `scope` finds the project's before the user's | the same with `:updated` |
| `remove` | `skill-name` | deletes the skill's directory, only ever a direct child of a skills root | `{:deleted :type :scope}` |

A name is cleaned, not refused (`sanitize-skill-name`: `"  Deploy Helper! "` →
`deploy-helper`). Only brainyard's own backend is written — `<project>/.brainyard/skills`
by default, `~/.brainyard/skills` with `scope user`. The other harnesses' roots
are read, never written. An extra's name is reduced to its own file name, so it
cannot land outside its skill.

**A built-in skill is never written.** `update` and `remove` of a name that is
only a built-in skill, `type builtin` and `scope builtin` are refused with what
to do instead: copy the SKILL.md into `<project>/.brainyard/skills/<name>/`
by hand. `create` refuses a built-in skill's name as well: a skill of the
model's own would shadow a shipped one silently, and shadowing one is a
person's choice. Once the copy is there it is the project's, to update and
remove like any other. With `:builtin-skills? false` there is nothing to
refuse.

The registry is refreshed before the result returns (`refresh!`, brainyard's
`reload-skills!`), so the skill is callable in the turn that wrote it, and
precedence is worked out again. `skills$reload` does the same for a `SKILL.md`
written another way. brainyard binds the write half to its skill-agent and
reloads at the end of the turn. Here it is the agent's own, behind
`:skill-authoring?` and the permission layer (divergence
`skills-write-is-the-agents-own`).

### Searching, installing, importing and syncing (`byj-gly.248`, `byj-gly.254`)

brainyard's four other write-side commands, with its names, on the same terms as
`skills$write`: the agent's own behind `:skill-authoring?` — never the
everyday roster, as brainyard keeps them on its skill-agent — gated by their
effects, and the registry refreshed before a write returns, so what it brought
is callable at once.

| command | args | does |
|---|---|---|
| `skills$search` | `query`, `owner?` | `npx --yes skills find <query> [--owner O]`, 15 s: what COULD be installed, where `skills$find` searches what is. The CLI's text, and its packages read out as `{:package :installs}` — each `:package` goes to `skills$install` as it is; `:effects #{:process :net/http}` |
| `skills$install` | `package`, `agent?` | `npx --yes skills add <owner/repo[@skill]> -g -y [-a <agents>]`, 30 s; the CLI keeps a global skill in `~/.agents/skills` and links it into the agents it finds, or those named; `:effects #{:process :net/http :fs/write :tools/define}` |
| `skills$import` | `path`, `name?`, `scope?` | a local `SKILL.md` — the agentskills.io open standard — or the directory holding one, created as a brainyard skill; named by `name`, else its frontmatter, else its directory; refused without a frontmatter description |
| `skills$sync` | — | `npx --yes skills update -g -y`: every global skill to its latest version |

**The CLI as it is, not as brainyard calls it** (divergence
`skills-cli-as-it-is`). brainyard passes `--target claude|agents` to `find`,
`add` and `update`. The `skills` CLI that `npx` fetches today has no such
option: installing is scoped by `-g`/`-p` and linked into agents with `-a`,
updating is by scope, and `find` takes a query and `--owner` — it read
`--target agents` as more of the query and searched for "agents". brainyard
runs the same unpinned `npx skills`, so its commands carry the same fault. The
first cut here copied brainyard's flags and was fixed before anything depended
on them.

Both CLI calls run as a process-task (RFC-009): the arguments are spread, never
a shell line; a package must read `owner/repo` or `owner/repo@skill`, an agent a
bare name or `*`, an owner a GitHub name — none can be an option to npx; the
timeout or a cancelled turn kills the tree; a non-zero exit is an `:error` with
what the CLI said, where brainyard returns it as a result; and `--yes` lets npx
fetch the CLI on first use with no one to answer its prompt. They write the
agents root, which the port otherwise only reads.

### Self-improvement: the agent proposes, a person accepts

brainyard's loop, in two halves. Both are **off by default**, and neither
writes a live skill:

**Off the turn** (`byj-gly.247`), as brainyard's `run-off-turn!`
(`common/background.clj`): every judge — distillation, refinement and tool
distillation (RFC-010) — runs as a background task (`tasks/start!`, job type
`:self-improve`, owner `"self-improve"`), so its model call never holds up the
next prompt. One at a time per kind and key (the session for distillation, the
skill for refinement): while one runs, the candidates it would have been sent
keep growing rather than being dropped. The person sees it in `/task`; no turn
harvests it and the model's `task$list` leaves it out. When the session ends,
`:session/drain-self-improve` gives the judges still running
`:self-improve-drain-ms` (3 s, brainyard's grace) before `close` cancels them,
and says what it cancelled; the session-end flush then runs where it is, since
nothing after it would wait for it.

- **Distillation** (`:enable-skill-distillation`) writes a NEW skill from
  successful work. A turn is a candidate only when it answered
  (`trajectory/succeeded-by`) in at least two tool calls
  (`self-improve/worth-scoring?`, which costs nothing). The candidates not
  yet judged are one **window**, judged in one call by `session/distill-skill`
  when it is due. A procedure spread over several turns is visible only this
  way. The window is due:
  - as soon as it shows a procedure (`self-improve/auto-signal`, checked
    after every turn at no cost). Either the same procedure shape — the tools
    a turn called, in order, with a run of one tool counted once — appears in
    `:repeats` (2) candidate turns and is at least `:min-shape` (2) tools
    long, or one candidate turn took `:long-steps` (8) steps. The thresholds
    are the tree node's `:auto` opts;
  - and at the latest every `:skill-distill-judge-window` (12) turns. With 1,
    each candidate turn is judged as it ends, which is brainyard's
    `:per-turn`.

  Whatever is left is judged when the session ends, and an empty window is
  never sent. Each verdict's `:trigger` says what brought it on: `:cadence`,
  `:repeated-procedure`, `:long-procedure` or `:session-end`. A draft is
  staged when the judge says it is reusable, scores it at least
  `:skill-distill-threshold` (0.7), names it `^[a-z][a-z0-9-]*$` — and not
  after a built-in skill, whose name `skills$write` would refuse at accept
  (`:builtin-name`) — and writes it.
- **Refinement** (`:enable-skill-refinement`) revises an EXISTING skill. It
  judges a `skill$` call that itself failed, and every skill loaded by a turn
  that did not succeed; a cancelled turn is not a failure.
  `session/refine-skill` is asked whether the SKILL.md was at fault, as
  opposed to the environment, the caller, or a one-off. Only a brainyard
  skill is judged (divergence `refinement-judges-only-writable-skills`) — a
  built-in skill never is.

The two predictors are brainyard's `SkillDistillationBatch` and
`SkillRefinement`, word for word. Its single-turn `SkillDistillation` is not
needed: a window of one turn is still a window, and brainyard's two modes
are one here (divergence `one-distiller-for-every-window`). Judging as soon as
a procedure shows is this port's (divergence `distillation-auto-mode`). The session
tree runs them after the recap (RFC-004), so when they run is data.

A draft is a **proposal**: `<project>/.brainyard/skills/proposals/<name>/`
holds `SKILL.md` and a `proposal.edn` —
`{:name :kind :score :rationale :session :turn :turns :source-question :evidence :created-ts}`.
The skills loader passes over it, because it has no `SKILL.md` of its own. The
session says so in an `:info` line as it stages one (divergence
`a-staged-proposal-is-announced`), and every verdict, staged or not, is a
`:session.skill/judged` event with its cost.

A skill proposal waits beside every other kind of self-improvement — user
tools (RFC-010), predictor params (RFC-021) and loops (RFC-071) — behind one gate,
`brainyard.proposals` (`byj-gly.249.1`, divergence `one-proposals-gate`). Its id
is `skill/<name>`. The model may **see** what was proposed — `proposal$list`
(`{:proposals […]}`, newest first, optionally one `kind`) and `proposal$read`
(the draft as `:body`, with the metadata). Only the person may **decide**, with
the session's `/proposals`:

| command | does |
|---|---|
| `/proposals [KIND]` | what is pending, every kind or one, newest first |
| `/proposals show ID` | the draft; for a skill refinement, the failure behind it |
| `/proposals accept ID [user]` | a skill distillation → `create` (in the project unless `user`), a refinement → `update`; the proposal is dropped only when the write succeeded, and the tools and the prompt's listings are built again |
| `/proposals reject ID` | drops it |

A loop's accept differs in one way: it is in force from the **next session**,
not the next turn, as a session expands its tree when it starts, and the
session says so (RFC-071).

An `ID` is `KIND/NAME`, or a bare name only one kind has. brainyard's
`skill-proposal$accept`/`$reject` are commands, bound only on its skill-agent.
Here there is no tool for either, by the rule that keeps `program$accept` from
the model (RFC-021): a gate the proposer can open is no gate.

## API

`brainyard.skills`: `roots`, `parse-frontmatter`, `parse-skill`,
`builtin-index`, `builtin-skills` (the shipped skills, from an index
resource), `builtin-on?`, `list-skills`, `resolve-names`, `find-skills`, `describe`, `listing-text`,
`skill-agent-instruction`, `skill-tool`, `skills-and-tools`, `commands`;
writing: `sanitize-skill-name`, `skills-dir`, `create-skill!`, `update-skill!`,
`remove-skill!`, `builtin-skill` (the built-in skill a write refuses),
`refresh!`, `write!`, `authoring-commands`.

`brainyard.self-improve`: `worth-scoring?`, `succeeded?`, `turn-steps`,
`trajectory-text`, `candidate`, `window-text`, `existing-skills-text`,
`distill-outcome` (with the built-in skills' names, `builtin-skill-names`),
`refine-outcome`, `valid-name?`, `refine-triggers`,
`failure-evidence`, `proposals-dir`, `write-proposal!`, `read-proposal`,
`procedure-shape`, `auto-signal`, `list-proposals`, `accept-proposal!`, `reject-proposal!`.
`brainyard.proposals` (the gate every kind shares): `kinds`, `list-all`, `resolve-id`,
`read-one`, `accept!`, `reject!`, `commands`
(list and read only).
The session leaves are `brainyard.session`'s: `:session/note-skill-candidate`,
`distill-window?`/`distill-leftover?`/`distill-window`,
`refine?`/`refine`.
`skill-tool` and `skills-and-tools` take the loop's `run-turn` so a dispatching
skill can start a child turn (RFC-018).

Config (RFC-013): `:skills?` (default true), `:skill-roots` (default: the six
disk roots above), `:builtin-skills?` (true), `:max-listed-skills` (40), `:skill-authoring?` (true); the `:skills`
family, brainyard's keys and defaults: `:enable-skill-distillation` (false),
`:skill-distill-judge-window` (12),
`:skill-distill-threshold` (0.7), `:enable-skill-refinement` (false).

## Invariants

| invariant | enforced by |
|---|---|
| A skill is found by its description when every word searched for starts one of its words; one word of two, or a word inside another, is not a match. | `skills-test/a-skill-is-found-by-what-its-description-says-it-is-for` |
| A SKILL.md imported from a path is a brainyard skill callable at once; one without a frontmatter description is refused. | `skills-test/a-skill-is-imported-from-a-path` |
| search, install and sync run the skills CLI's command lines as it is; search reads the packages out; a package, an agent and an owner are never an option to npx; a failed CLI is an error; they declare :process and :net/http. | `skills-test/a-package-is-found-installed-and-synced-through-npx-skills` |
| Frontmatter, a bare heading, and unterminated frontmatter all parse; nothing is required. | `skills-test/parsing-a-skill-md` |
| brainyard's own SKILL.md loads with its description and body byte for byte. | `skills-test/brainyards-own-skill-file-loads-unchanged` |
| A directory without SKILL.md is skipped; a duplicate name keeps the local id and the other stays reachable. | `skills-test/roots-and-precedence` |
| Every skill the index names loads from resources, typed and scoped builtin, with no path on disk and no scripts; an entry may name resources, and one without a SKILL.md is skipped. | `skills-test/the-shipped-built-in-skills-load-and-carry-no-scripts` |
| A built-in skill is listed after every root and found by `skills$list`, `skills$find` and `skill$<name>`, byte for byte; a project skill of its name shadows it, `skill$builtin$<name>` still being the built-in one; `:builtin-skills? false` hides it; its resources come with the call. | `skills-test/a-built-in-skill-is-listed-last-and-a-project-one-shadows-it` |
| No write reaches a built-in skill: create, update and remove refuse it and say how a person changes it, a distillation named after one is not staged, and refinement never judges one; a person's copy in the project is the project's to change. | `skills-test/a-built-in-skill-is-read-only` |
| The user roots are under `brainyard.skills-home` when it is set, never `$HOME`; the suite sets it to an empty directory. | `skills-test/the-user-roots-follow-the-skills-home` |
| A project's `.claude/skills` and `.agents/skills` are read, below its own `.brainyard/skills`, and two scopes of one backend never collide into one id. | `skills-test/a-project-may-keep-skills-where-its-other-harnesses-do` |
| Search ranks names over descriptions, drops short tokens, and returns empty rather than weak matches. | `skills-test/finding-a-skill` |
| A skill is a `:kind :skill` registry entry; its description says what calling it does; calling it returns the whole body; the prompt names it but never carries its body. | `skills-test/a-skill-is-a-tool-whose-body-arrives-only-when-called` |
| A skill's line says what it is for: a title that says more than the name, else the description's first sentence clipped with a mark, else the title. | `skills-test/a-skills-line-says-what-it-is-for` |
| `dispatch: agent` runs the procedure in a child turn and returns its answer, needs a question, and registers no instance. | `skills-test/a-skill-may-run-itself-in-an-agent` |
| With subagents off, such a skill hands over its procedure with a note instead of failing. | `skills-test/a-dispatching-skill-falls-back-when-subagents-are-off` |
| 50 skills cost a few thousand prompt characters, not their descriptions, and all 50 stay callable. | `skills-test/the-prompt-cost-is-bounded` |
| A name is cleaned, not refused. | `skills-test/a-skill-name-is-cleaned-not-refused` |
| `skills$write` creates (refusing what exists, another harness's type, and a missing field), updates and removes a brainyard skill; the change is callable in the same turn; an extra cannot be written outside its skill; `skills$reload` registers a SKILL.md written another way. | `skills-test/skills-write-creates-updates-and-removes-a-skill` |
| `:skill-authoring? false` removes the write half and the proposal tools, not the read half. | `skills-test/skill-authoring-can-be-switched-off` |
| Only a turn that answered in two or more steps is a distillation candidate, and nothing about the others reaches a model. | `self-improve-test/only-a-successful-multi-step-turn-is-a-candidate` |
| A verdict stages only a confident, reusable, well-named, non-empty draft (brainyard's matrix at 0.7); a refinement only a revision of a document at fault. | `self-improve-test/a-verdict-stages-only-a-confident-well-named-draft` |
| A proposal is not a skill until accepted; accepting creates or updates by kind and drops the staging only on success; rejecting drops it. | `self-improve-test/a-proposal-is-staged-and-goes-live-only-when-accepted` |
| Refinement judges a failed skill call always, and a loaded skill when the turn failed — not cancelled — and only a brainyard skill. | `self-improve-test/a-failed-turn-names-the-skills-it-used` |
| With the gates off (the default) no judge is called. | `self-improve-test/distillation-is-off-until-asked-for` |
| A window of one judges each candidate turn as it ends, stages the draft, says so, reports the verdict as an event, and counts the call. | `self-improve-test/a-window-of-one-judges-each-turn-as-it-ends` |
| At cadence one call judges a window; what is left is judged at session end. | `self-improve-test/a-window-waits-at-most-n-turns-and-the-rest-is-flushed` |
| A procedure is seen in a repeated shape of two or more tools, or in one long turn, with the node's thresholds; a repeated single tool, different procedures and short turns are none. | `self-improve-test/a-procedure-is-seen-in-the-window` |
| The window is judged as soon as a procedure repeats, and otherwise waits for the cadence or the session's end; the check costs no model call. | `self-improve-test/a-window-is-judged-as-soon-as-a-procedure-repeats`, `self-improve-test/a-window-waits-while-nothing-repeats` |
| A failed turn that loaded a skill stages its revision without touching the live skill. | `self-improve-test/a-failed-turn-that-used-a-skill-stages-its-revision` |
| A judge runs off the turn — the next prompt does not wait for it — one at a time per kind and key, as a task the person sees and the model's task$list leaves out; the session's end waits `:self-improve-drain-ms` for it and says what it cancelled. | `self-improve-test/a-judge-runs-off-the-turn`, `self-improve-test/a-judge-that-outruns-the-grace-is-cancelled-and-said-to-be` |
| The model may list and read proposals and never accept or reject one; `/proposals` lists, shows, accepts and rejects. | `self-improve-test/a-person-accepts-a-proposal-and-the-model-cannot` |

## Known gaps

- **No Live Artifacts.** brainyard also pins a loaded skill so it survives into
  later turns; here it is in the transcript, which compaction may elide
  (RFC-014). Calling it again re-reads it.
