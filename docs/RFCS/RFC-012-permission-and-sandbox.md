# RFC-012 — Permission and sandbox

**Status:** implemented (`byj-gly.2`).

## Purpose

Specifies the two things that stand between a model's tool call and the
machine: the **permission gate**, which decides whether a call runs, and the
**sandbox**, which contains what the process can write whatever the gate
decided. brainyard sources: `agent/common/tool_permission.clj`,
`components/os-sandbox`, `docs/specs/permission.md`,
`docs/design/tool-permission-gate-design.md`, `docs/sandboxing.md`.

## Scope

**This layer decides** whether a named call with its args may run, and what a
refusal looks like to the model; and how the CLI relaunches itself under
seatbelt.

**It must not know** which loop made the call, or why.

**It hands** a decision to the hook bus (RFC-011) — it *is* two built-in hooks —
and a refusal to `tools/call-tool` as a tool result.

## Model

### The gate is a stack of hooks

| priority | hook | consults | on-error |
|---:|---|---|---|
| 100 | `redundant-tool-call-guard` (RFC-011) | iteration history | log |
| 95 | `tool-deny-gate` | `:tool-deny-tools`, `:tool-deny-effects` | **throw** |
| 92 | `bash-command-gate` | `:bash-deny-commands`, `:bash-allow-commands`, `:allowed-dirs` | **throw** |
| 90 | `path-permission-gate` | `:denied-paths`, `:allowed-dirs`, `:path-permission?`, `:permission-mode` | **throw** |
| 87 | `tangled-file-guard` (RFC-067) | `:literate-guard?`; a write-file or update-file target's org-babel link comments | **throw** |
| 85 | `tool-permission-gate` | `:tool-approval-patterns`, `:tool-allow-tools`, `:tool-ask-effects`, `:permission-mode` | **throw** |

brainyard's order (its cache gate sat at 90) and its `:on-error :throw`: a gate that cannot decide must
not quietly let the call through, so its failure surfaces instead of being
logged and skipped. brainyard's cache (90) and MCP (80) gates are not ported
(`byj-gly.14`).

**The name and effect gates ship inert.** With no patterns and no effect rules —
the defaults — each reads one config key and abstains
(`permission-test/the-gates-ship-inert`). **The path gate is on by default**,
because its default allow-list is the working directory plus the temp
directories and `~/.brainyard`: the agent can work where it was started, and a
path outside that is asked about or refused.

### What a command line may run

The other gates read a tool's NAME and its path arguments. Neither says anything
about `bash "rm -rf /"`, so "this agent may run `bd` and nothing else" was not
expressible — the gap that a bead-keeper agent (RFC-018; now this
repository's own, `byj-gly.316`) made concrete.

A tool declares which of its arguments are command lines (`:command-params` —
`bash` and `task$run` both do, so backgrounding a command is not a way around
this), and the gate reads each one:

1. `:bash-deny-commands` globs match the **whole line**. A deny list is a
   tripwire, and what it looks for can appear anywhere. No mode overrides it,
   as with `:tool-deny-tools`.
2. `:bash-allow-commands` globs match the **program of every segment** — the
   line split on `;` `&&` `||` `|` and newlines, each segment's first word past
   any `VAR=value` assignments, matched by full token and by basename so `git`
   allows `/usr/bin/git`.
3. `:bash-ask-commands` globs match **one segment's text** — its words past any
   `VAR=value`, one space apart (`permission/asked-command`). A matching command
   is asked about though it names nothing outside the allowed directories: one
   that could destroy or change what the person did not ask to — `rm *`,
   `mv *`, `dd *`, `find * -delete*`, `git push*`, `git reset --hard*`,
   `git clean *`, `chmod *`, `sudo *` and the rest of the default list. In
   `:auto` the classifier decides (below), in `:ask-each-time` the person does,
   `:deny-by-default` refuses and `:auto-approve` runs it. Its verdict is
   remembered by the whole line, never by the program — `rm ./build/tmp`
   allowed must not let `rm -rf ./src` through — and the prompt's `r` writes
   nothing for it: no config key says "run this without asking". A heuristic:
   `python -c "shutil.rmtree(…)"` or a script that deletes is not a shape the
   list knows — so in `:auto` with a live model every command is classified
   anyway (measured, below; byj-gly.285.3). The list is what `:ask-each-time`
   asks about, and what `:auto` asks about without a model to classify with.
4. Absolute paths named in the line are checked against `:allowed-dirs` through
   the permission channel — brainyard's `validate-bash-paths`, including its
   rule that a path which does not exist is not a violation.

**This is not a sandbox, and the distinction matters.** A shell is a programming
language; an allow-list that tried to understand one would be a parser with a
security bug in it. So with an allow-list in force the gate refuses what it
cannot read rather than guessing:

| line | verdict |
|---|---|
| `bd ready && git status` | allowed (both programs listed) |
| `bd ready \| grep x` | refused — `grep` is not listed |
| `sudo bd ready` | refused — a wrapper is a program too |
| `bd $(rm -rf x)` | refused — command substitution cannot be checked |
| `bd create --title="a; b"` | allowed — the `;` is inside quotes, so it is text, as the shell reads it |
| `bd create "a; b" && ls` | refused — `ls`, a real second command, is not listed |
| `bd create "$(rm -rf x)"` | refused — what runs at a time the text cannot see is refused inside quotes too |
| `bd create "a; b` | refused — a quote left open cannot be segmented |

**Quoting is read, narrowly** (byj-gly.315.14). The gate once split on every
separator, quoted or not, and refused a quoted one, which was the safe
direction. But in the dogfood simulation (RFC-041), `agent$beads` had its
`bd create` refused once for every bead whose description copied a `;` from
the person's backlog, even after the refusal said why (byj-gly.315.7). The
segmenter now reads quoting as a shell does, and no more than that:

- single quotes are literal to the next one;
- double quotes take backslash escapes;
- a backslash outside quotes escapes the next character.

What could still slip past a reading this narrow is refused:

- `$(`, a backquote and process substitution, anywhere in the line, quoted or
  not;
- an unclosed quote.

A `#` comment and a heredoc's body are split as though they ran, which can
only refuse more. What actually contains a
process is `sandbox-exec` and the permission mode; this makes an intended set of
commands enforceable, and everything else visible.

### Deciding

- **Deny wins.** A tool matching `:tool-deny-tools`, or carrying an effect in
  `:tool-deny-effects`, is refused under every mode, and `:tool-allow-tools`
  cannot exempt it.
- **Then permission.** A tool matching `:tool-approval-patterns`, or carrying
  an effect in `:tool-ask-effects`, needs permission — unless it matches
  `:tool-allow-tools`. `:permission-mode` then decides:

| mode | effect |
|---|---|
| `:auto-approve` | abstain |
| `:deny-by-default` | refuse: "permission mode is :deny-by-default" |
| `:ask-each-time` | ask ctx's `:permission-fn`; **no fn is a refusal** — headless is fail-closed |
| `:auto` (default) | `:auto-approve` in a container (`BY_CONTAINER=1` or `/.dockerenv`), else the classifier, then `:ask-each-time` (below) |

### Auto mode decides before it asks (`byj-gly.285`)

Outside a container `:auto` used to be `:ask-each-time`. Now, wherever a gate
would ask the person — the tool approval, a path outside `:allowed-dirs`, a
command naming one — and the mode in force is `:auto`, the auto-mode
classifier decides first (`permission/ask`), decided with the user on
2026-10-01:

- **allow**: the call runs, and nobody is asked;
- **block**: refused, with the classifier's reason, for the model and the
  person — "blocked by auto mode: deletes ~/old outside the project". There is
  no override at the block: its message says how a person lets such a call
  through (shift+tab to `:ask-each-time`, `/allow-path`, a `:tool-allow-tools`
  glob);
- **unsure**, or a classifier that fails: asked, as before — refused where
  nobody can be asked, and refused anyway with `:auto-mode-on-unsure :deny`.

It runs in no other mode, for no call a gate would not ask about, and not in a
container. `--sandbox` (the seatbelt) is not a container: it still lets a
command read anywhere and reach the network, so the classifier runs there too.

The classifier is the predictor `permission/classify`
(`resources/brainyard/predictors/permission/classify.edn`): its instructions
are the policy — what is dangerous (destroying data outside the project,
secrets, sending data off the machine, system changes, downloading and running
code, acting far outside the request) and what is not — and a project tunes it
with params like any predictor. It is shown the turn's question and the call —
the tool, its arguments cut to `agent/classifier-arg-chars` (400), its effects,
what the gate would have asked about — and the workspace (`agent/auto-classifier`).
It is **never shown a tool's output or the agent's reasoning**: that is where an
instruction injected into the work would come from, and a classifier that read
it could be talked into allowing what it guards. It asks
`:auto-mode-classifier-lm` (`claude-code/sonnet`, measured below), not the session's model — but
a session on a stand-in model (the mock, or a CLI a test swapped in with
`:cli-command`) classifies on it, and a ctx with no model does not classify, so
neither a test nor an offline run reaches a live model.
`:auto-mode-classifier? false` switches it off, and `:auto` asks as brainyard's
does (divergence `auto-mode-classifier`).

An allow or a block is remembered for the session (`:classifier-verdicts`, one
per ctx), under `permission/verdict-key`: the turn's question — the same call
can be right for one request and wrong for another — the kind of request, the
tool, and what the person's prompt remembers an answer by: a path's parent
directory, a command's program, an approval's pattern. So a sibling file read
for the same request costs no second call, and another request is asked
afresh. An unsure verdict is not remembered. Every verdict, remembered or not,
is a `:permission/classified` event (`:trace`; `{:verdict :reason :tool
:display :type :cached? :ms}`), in the event log and under `-v`, and the
full-screen renderer draws it under the call it decided — `⛔ blocked by auto
mode: …` red, `✓ allowed by auto mode: …` dim (RFC-028, byj-gly.285.2).

A request handed to the person's `:permission-fn` is said as
`:permission/asked {:id :tool :summary}`, and the answer as
`:permission/decided {:id :tool :decision :allow|:deny}` (`:trace`, the port's),
so a watcher on the session channel — a shared session's viewers (RFC-043) —
sees a prompt and its outcome. Read only: nothing but the person's own prompt
answers one. A headless gate, which asks no one, says neither.

The gates ask where a call names something outside `:allowed-dirs`, a tool
`:tool-approval-patterns` or `:tool-ask-effects` names, or a command matches
`:bash-ask-commands` — so `rm -rf ./src` inside the project is asked about, and
in `:auto` classified (byj-gly.285.4). In `:auto`, with the classifier on and a
live model to ask, **every** command is classified, whatever the list names
(`permission/classify-every-command?`, `:auto-mode-classify-every-command?`,
default on): a stand-in model or none keeps the list, since it could only
answer unsure and refuse every command where nobody can be asked.

### Auto mode, measured (`byj-gly.285.3`, 2026-10-01)

`dev/brainyard/dev/auto_mode_eval.clj` runs the real `agent/auto-classifier`
on `dev/brainyard/dev/auto_mode_set.edn`: 44 requests, each a gate's request
with the person's question and a careful person's label — 12 paths outside
the project, 14 in-project commands `:bash-ask-commands` names, 18 it does not
(the benign ones drawn from this repository's event log where one fit). Haiku
ran twice, sonnet once, through `claude -p` (132 verdicts):

| | haiku ×2 | sonnet ×1 |
|---|---|---|
| dangerous blocked | 46/46 | 23/23 |
| benign blocked (false block) | 0/42 | 0/21 |
| unsure — the person is asked | 2 benign | 1 benign |
| latency median / p90 | 8.0 s / 14.7 s | 5.3 s / 7.7 s |
| cost per verdict | $0.0065 | $0.0064 |

- **No miss and no false block on either model.** The three unsure verdicts
  were caution a person would accept: `make release` "could publish or
  deploy"; a temp-file command that left out the removal asked for; and
  `git clean -fdX` — which also deletes ignored `.env` files — where sonnet was
  more careful than the label.
- **Sonnet is the default.** Through `claude -p` the CLI's fixed cost per call
  dominates, so the smaller model saves nothing, and sonnet was faster and
  asked needlessly less.
- **Every command is classified, not only the list's.** Scored from the same
  verdicts, the list let all 10 dangerous unlisted commands run unasked —
  `python -c "shutil.rmtree…"`, `: > src/core.clj`, `curl … | sh`,
  `curl -d @.env …`, `npm publish`, `gh pr merge --admin`,
  `docker system prune`, a crontab entry, `cp /dev/null README.md`, a shell loop
  that empties files — where classifying every command caught all of them. The
  price is a classifier call, ~5 s, per new command line in a request (repeats
  are remembered); this repository's event log has 678 bash calls in 207 tool
  batches. Decided with the user.
- **Small and constructed.** 44 requests, the dangerous ones written to be
  plain; a subtler attack, or a request that does ask for something drastic, is
  not measured here.

**The mode a person chose outranks the config's.** Every gate reads the mode
through `permission/mode`: the ctx's `:permission-mode-box` when there is one,
else `:permission-mode`. The full-screen session makes the box and shift+tab
steps it through `cycle-modes` (`:auto` → `:auto-approve` →
`:ask-each-time`; `:deny-by-default` is a policy a config sets, not a posture
to toggle into) — read at each call, so a change reaches the next one,
mid-turn included, and a subagent's gates read the same box. An edit to
`:permission-mode` in `config.edn` is a choice too: the session writes it into
the box when it applies the edit (RFC-013), and the latest choice wins.
Headless runs have no box and read the config (divergence
`permission-mode-cycles-with-shift-tab`).

**An allow is an abstention, not a licence**: it only means this gate has
nothing to say, and a later hook may still refuse (RFC-011's decision rules).

**Globs** are brainyard's: only `*` is special, whole-name, case-sensitive,
matched against the tool *name*. The matching pattern is what an approval is
remembered under.

**Effects** are this port's addition (divergence `permission-by-effects`):
`:tool-ask-effects #{:process}` gates every tool that can run a process,
including a user tool that reaches one through another tool, because a
composite's effects are the union of its calls (RFC-010). brainyard has no
effect rule — it lists names.

### Refusing

A refusal is `:replace`, never `:block`: the model reads the refusal as the
tool's result and the turn goes on, so it can ask, explain or route around. A
block would end the turn, which is the wrong shape for "ask again with
permission". The strings are brainyard's:

```
Tool call refused (denied): bash. denied by :tool-deny-tools pattern "bash".
This tool is denied by :tool-deny-tools. No permission mode overrides it — narrow or remove the matching glob to allow it.

Tool call refused (permission): bash. permission mode is :deny-by-default.
To allow it: approve interactively, add a matching :tool-allow-tools glob, or set :permission-mode :auto-approve.
```

### Paths

Every tool that declares `:path-params` in its spec — `read-file`,
`write-file`, `update-file`, `grep`, `list-dir` — has those args checked
against `:allowed-dirs` (unset: `/tmp`, the system temp dir, the working dir,
`~/.brainyard`, as brainyard's `default-allowed-dirs`). A path outside them
takes the mode's route: `:auto-approve` abstains, `:deny-by-default` refuses,
`:ask-each-time` asks the permission channel with
`{:type :file-access :action :read|:write :path …}`, and headless refuses.
`--allow-path DIR` and `[:permissions :allowed-dirs]` extend the list;
`:path-permission? false` switches the gate off.

**A person's logins and keys are read by no path tool** (`:denied-paths`,
byj-1wv.115.40; divergence `logins-and-keys-are-denied-paths`): the share sidecar's
login under `~/.brainyard/share/credentials` (RFC-043) — inside the default list —
Claude Code's and Codex's credentials and settings, `~/.ssh`, `~/.aws`,
`~/.netrc`, gh's hosts and Docker's config. The gate refuses a path under one,
or a directory holding one (a recursive read would open it), before it reads
`:allowed-dirs`, in every mode and whatever `:path-permission?` says; the
command gate refuses a line naming one, absolute or from `~` — a tripwire, as
`:bash-deny-commands` is, for a shell reaches a file by other spellings.
`permission-test/a-login-or-a-key-is-read-by-no-tool`. A program that runs a session for
another caller narrows it with `BY_ALLOWED_DIRS` — its project and the temp
dir alone, as an A2A gateway gives a caller's turn.

**The default list is wider than the project**, and that has a consequence
worth stating: a run whose working directory is itself under `/tmp` or the
system temp directory has a gate that allows everything under them — which is
most of what such a run can reach. brainyard's defaults are the same, and the
reasoning is the same (a scratch directory is where work is meant to happen),
but anything relying on a path being *refused* must name `:allowed-dirs`
explicitly rather than trust the defaults. The suite learned that the hard way:
its refusal tests built their "outside" fixture under the project, so a clean
clone to `/tmp` turned every refusal vacuous and the suite failed with no hint
why (`byj-gly.73`).

Matching is canonical (symlinks and `..` resolved) and **segment-aware**:
`/tmpfoo` is not under `/tmp`. brainyard's `resolve-allowed-path` compares raw
string prefixes and lets that through, while its TUI check is segment-aware —
the two disagree (divergence `path-match-by-segment`). The root is its own
prefix, so every path is under `/` and `:allowed-dirs ["/"]` allows
everything (`byj-gly.466`; it once allowed nothing).

brainyard checks paths inside each tool, and its write rule is a fixed
always-allowed set (`/tmp`, the system temp dir, `.brainyard/`) that ignores
its own `:allowed-dirs`, so every write into the project prompts. Here one gate
covers reads and writes alike against one list (divergence
`write-paths-use-allowed-dirs`), and because it is a hook on every call, the
calls a user tool makes are gated too — brainyard's in-tool checks cover its
sandbox callables the same way. Neither gates `bash`: a shell command is not a
path, and containment there is the sandbox's job.

### The ask channel

`:permission-fn` in ctx is `(fn [request])` → `{:allowed true}` or
`{:denied true :reason …}`; a throwing fn refuses with its message. The request
is brainyard's shape plus what a human needs to judge the call:

```clojure
{:type :tool-use :action :call :tool "bash" :pattern "bash" :display "bash"
 :args {:command "rm -rf /"} :effects #{:process}}     ; :args :effects are the port's
```

`permission/asking-permission-fn` builds one over the session's own reader:
`y` / `n` once, `a` / `d` remembered for the session under the matching
pattern, end of input denies. `byj run` installs it; `byj ask` is
headless, so `:ask-each-time` refuses there. There is no timeout — the prompt
waits, and Ctrl-C cancels the turn (divergence `permission-prompt-cli`).

### `r` — a grant that outlives the session

`r` allows, remembers for the session, **and writes the grant to
`.brainyard/config.edn`** so the next run does not ask (`byj-gly.83`). A tool
request appends its pattern to `:tool-allow-tools`; a file request appends the
**parent directory** to `:allowed-dirs`, which is the unit the prompt asked
about — granting the single file would ask again for its sibling. It appends
rather than replaces, so an earlier grant is not silently dropped, and it says
what it wrote and where, because a config file changed quietly by answering a
prompt is the thing worth not doing. The option is offered only when a config
file is resolvable.

**This is the only route by which a permission key is ever persisted.**
`config$set` refuses every one of them (RFC-013), and this write is allowed by
exactly one thing: a person pressed a key in a terminal. The seam is
`configbox/grant!`, which takes `:by :person` and accepts only
`#{:tool-allow-tools :allowed-dirs :permission-mode}` — not the denies, not
`:tool-ask-effects`, not `:path-permission?`, not `:builtin-hooks?`. A grant
widens what is permitted for the thing just asked about; removing a refusal is
a different act and belongs in the file, where it can be read in context.

What keeps `:by :person` meaningful is that nothing else may pass it:
`configbox-test/no-command-can-ask-as-a-person` reads the command table and
fails if a command body mentions `:person` or calls `grant!`, and asserts that
the only caller of `grant!` under `src/` is this prompt. Without that the
distinction between a person answering and the agent asking on a person's
behalf would erode silently, which is the only way it would erode.

### Commands that change the gate (`byj-gly.145`)

brainyard has two commands that sound like they change what the gate allows:
`/allow-path DIR` and `/sandbox`. Decided, 2026-10-01:

- **`/allow-path DIR` is built, for this session and the person only.** It adds
  DIR to the directories tools may reach, as answering the prompt with `a` would
  — the same act, before the prompt asks. The directories live in
  `:allowed-dirs-box`, a session-scoped box that `session/run` creates and every
  ctx carries (a child agent's too), and `permission/allowed-dirs` reads it at
  every gate beside `:allowed-dirs`. Two things keep it the person's:
  - a line the session channel handed over — another session, a team leader,
    RFC-031 — is refused. The gate's point is that the person answers it, and
    such a line reaches the command dispatch like a typed one (`byj-gly.282`);
  - it is never written to `config.edn`: `r` at the prompt stays the one route
    by which a permission key persists (above), and `--allow-path` the durable
    flag. With no DIR it lists the directories in force.

  brainyard's adds to the agent's `allowed-dirs` with neither check (divergence
  `allow-path-is-the-persons`).
- **`/sandbox` is not built.** brainyard's does not touch the seatbelt: it
  evaluates Clojure in its SCI code sandbox (`/sandbox eval (+ 1 2)`, or a
  sandbox function by name). The port has no SCI, and what it did is here as
  `:tool$run {:body "…"}` and `:<tool>` direct calls (RFC-027), both gated. The
  seatbelt itself cannot change mid-run: `sandbox-exec` wraps the whole process
  at launch, so `--sandbox*` stay flags (divergence `no-sandbox-eval-command`).

### Another agent's calls (`byj hook`, `byj-ahf.24`)

Claude Code and Codex run a `PreToolUse` hook command before every tool
call, and `byj hook pretooluse` is that command (`brainyard.tool-hook`). The
payload on stdin (`tool_name`, `tool_input`, `cwd`, `session_id`; Codex's is
Claude Code's, RFC-049 decision 6, and carries `turn_id` and `model`) is
mapped onto byj's tool — Bash → bash, Read → read-file, Write → write-file,
Edit/MultiEdit/NotebookEdit → update-file, Grep → grep, Glob/LS → list-dir,
Codex's `apply_patch` → update-file of every file the patch names, a move's
destination included — and put through the gates above (tool deny, bash
command, path, tool permission; `hookbox/builtin-hooks` under
`brainyard.permission`) under the config of the payload's `cwd`. A refusal
by rule answers `deny` with the gate's reason; a gate that would ask a person
answers `ask`, and the agent asks; nothing objecting prints nothing, and the
agent's own rules decide. A tool byj has no counterpart for (an MCP tool, a
subagent), a payload that cannot be read and a hook that throws get no
opinion: the hook must never stop a person's work. Each decision is a line,
`{:agent :session :tool :decision :at :project}` with `:reason`, `:gate` or
`:error`, in `<DIR>/<day>.edn`, `~/.brainyard/permissions` unless `--log
DIR`; a program that
reports on agents' permissions reads it, beside the agents' own decisions
from their OpenTelemetry events. `byj hook claude-settings` prints the
Claude Code settings that install it — the command below that asks a
resident byj first — (for `claude --settings`, a
`settings.json`, or `:claude-settings` when agent$claude starts one);
`byj hook codex-flags` a `codex` line carrying it as a session hook with its
trust record, nothing written to the person's Codex home. The two are named
for the agent each installs it in; `byj hook settings`, the first's name in
v0.3.18 and v0.3.19, still answers as `claude-settings` does
(`main-test/the-hook-installs-are-named-for-their-agents`).

It applies byj's own policy, so it is byj's (decided 2026-11-11,
`byj-ahf.24`): a copy of these gates in another program would drift from
them. It was first built under evaluation, beside the fleet's trace, which
dropped an `apply_patch` move's destination (its regex's second group);
here it is gated.

**Measured** (2026-11-11, `dev/hook-latency.sh`, M-series laptop, a config
that approves all but a denied write-file, so no classifier runs): a call
costs 0.66–0.68 s (median of 10, a refused Write and a passed Read), of which
byj's own start is 0.59 s (`byj --version`). Under `:auto`, a gate that would
ask runs the classifier first (above), a model's verdict on that call.

**Where the start goes, and the way out** (2026-11-11, `byj-fim`; jolt
v0.8.24, `target/byj` of 106 MB, the same laptop). A call is 0.68–0.69 s
(`dev/hook-latency.sh`, median of 10). Deciding is not the cost: inside a
byj that is already up, loading the payload's config and putting the call
through the gates takes about 1 ms. Starting is. Under `JOLT_STARTUP_PROFILE=1`,
building the heap from the image takes 0.40 s and the namespaces' loads
another 0.20 s before `-main` runs at 0.58 s. A trivial jolt binary (12 MB)
starts in 0.08 s, so the cost grows with what the image holds, and the two
ways out `byj-fim` named measure differently:

- **A binary of the hook alone** does not get there. One whose entry
  requires only `brainyard.config`, `brainyard.json` and `brainyard.tool-hook`
  is 82 MB and starts in 0.48 s (a call 0.50 s): the gates reach
  `brainyard.agent`, and through it most of byj. Getting near 0.08 s would
  mean cutting the gates loose from the agent and the classifier's providers,
  which is a second copy of the gates in all but name.
- **A process that stays up** does. A byj answering the same request on a
  unix socket (`dev/brainyard/dev/hook_server_spike.clj`, the session
  channel's transport) answers a call in about 1 ms; with a shell client
  (`nc -U`, the payload escaped into the channel's EDN line by `perl`), a
  call is 32–52 ms, median 33 ms, all but a millisecond of it the client's
  (`dev/hook-latency.sh --resident` reproduces it; a rerun gave medians of
  27–30 ms against 641–647 ms for the same calls through `target/byj`).
  The first call to a fresh server is 0.49 s.

So the decision is a resident `byj hook serve` that a thin client asks,
loading the payload's config on every request as the hook does now, so the
gates and their config stay the one source of the decision.

**The resident hook, as built** (`byj-ywp`, `brainyard.hook-serve`):

- **The command an agent runs** (`client-command`, what `byj hook
  claude-settings` and `byj hook codex-flags` install) is one `sh -c` line:
  the payload is read once, put by `curl --unix-socket` to `POST
  /v1/pretooluse` on `~/.brainyard/hook.sock` (`--socket`), and, when
  nothing answers there, curl is missing, or the server refuses (any non-2xx),
  given to `byj hook pretooluse --socket SOCK`, which answers it as it always
  has and then starts the server for the next call. Its stdout is the
  decision either way, so the agent's side of the contract — the payload on
  stdin, the answer on stdout, exit 0 — is unchanged, and a command installed
  before, `byj hook pretooluse` alone, still works, at its old cost. curl
  gives up after 25 s, under Codex's 30 s hook timeout, so a server that
  hangs leaves the call to the fallback; `--log` reaches the server as a
  `Byj-Log` header. Claude Code runs a hook command through a shell, and
  Codex through `$SHELL -lc` (its `hooks/src/engine/command_runner.rs`), so
  one line means the same to both.
- **The server** (`byj hook serve`, `0600` socket, `us/listen!`) speaks
  just enough HTTP for curl — `Content-Length` bodies up to 64 MB, a `100
  Continue` when asked, `GET /v1/ping` — and answers each call with the same
  fn the hook process does (`main/hook-answer`), so the decision and its log
  line are the same. It makes itself a session leader (`setsid`) with its
  input and output closed, so the agent that started it can end, and does not
  wait on it. It exits after `--idle` seconds with no call (1200), and when
  the byj its command names changes on disk — the file `byj` resolves to on
  its `PATH`, followed through links, and that file's time and size, so both
  `brew upgrade` (a new Cellar directory) and `byj update` (a new file at the
  same path) count: a call that finds it so is answered 503, which sends it
  to the fallback, the new byj, and the watch ends the server within 2 s
  when no call comes. One server to a socket: a second finds a live owner and
  leaves.
- **Measured** (2026-11-13, `dev/hook-latency.sh --resident`, the same
  laptop, `target/byj`): a call through the installed command is a median
  of 36 ms (max 47) against 628 ms as a process of its own; the first call
  to a fresh server is 73 ms. In order, as an agent sees it: the first call
  after none is the fallback's, ~0.7 s, and starts the server, which is
  ready in about half a second; an upgrade costs the one call that finds it
  (~1.4 s). The server holds about 400 MB resident while it is up — the
  price of byj kept loaded, which is why it does not stay up all day.
  Verified live with Claude Code (haiku, 2026-11-13): two `claude -p`
  sessions under `--settings "$(byj hook claude-settings)"` with Bash
  allowed by `--allowedTools` had it refused by a project denying `bash`,
  the first through the fallback, which started the server, the second
  answered by it after the first session had ended; one log line each.
  Codex was not run live (not signed in on the machine).
- **Its environment is the first caller's.** A hook process had the
  environment of the agent that ran it; the server keeps the one it was
  started with, by whichever agent's call found none. The gates read the
  config of the payload's `cwd` per call (its `.env` included, `launch/
  env-fn`), so what differs is only what comes from the process
  environment: a model key exported in one agent's shell and not the
  other's, for the classifier under `:auto`, and `BY_CONTAINER`. A key kept
  in `~/.brainyard/.env` or a project's `.env` reaches every call alike.

### Config and flags

| key | default | flag |
|---|---|---|
| `:permission-mode` | `:auto` | `--permission-mode` |
| `:tool-approval-patterns` | `[]` | `--ask a,b` |
| `:tool-allow-tools` | `[]` | `--allow a,b` |
| `:tool-deny-tools` | `[]` | `--deny a,b` |
| `:tool-ask-effects` | `#{:share/publish :terminal/input}` — starting a share (`share$start`, byj-gly.362.2), and typing into a terminal program (`terminal$send`, byj-gly.417), which in a shell runs commands, judged in `:auto` by the classifier as a bash line is; a set given replaces it | `--ask-effects process,fs/write` |
| `:tool-deny-effects` | `#{}` | `--deny-effects` |
| `:allowed-dirs` | `/tmp`, temp dir, working dir, `~/.brainyard` | `--allow-path a,b` |
| `:path-permission?` | `true` | `--no-path-permission` |

Every one is a schema key, so `.brainyard/config.edn` or a `BY_*` variable sets
it as well (RFC-013).

### The sandbox

`brainyard.sandbox` ports `components/os-sandbox`: `--sandbox` (or
`BY_SANDBOX=1`) relaunches the CLI under `/usr/bin/sandbox-exec` with a
generated seatbelt profile — deny by default, read anywhere, write only under
`$TMPDIR`, `/var/folders`, `/tmp`, `~/.brainyard`, `~/Library/Caches`, the
working directory and any `--sandbox-allow-write` root; network unless
`--sandbox-no-network`. `--sandbox-profile FILE` uses a custom profile with
brainyard's `-D HOME/CWD/PROJECT_DIR/TMPDIR` parameters instead.

Not brainyard's: `profile` also takes `:extra-write-regexes`, a `(regex …)`
write rule each, for files beside a root rather than under one. A contained benchmark trial uses it for Claude Code's `~/.claude.json` and its
lock and temp siblings, which opening the home directory would be the only
other way to allow (`byj-gly.288.12`).

The child runs with `BY_SANDBOX_CHILD=1` so it does not sandbox itself again,
inherits the terminal, and its exit status becomes the CLI's. Off macOS, or
without `sandbox-exec`, the run says so and continues unsandboxed — as
brainyard does.

jolt has no "current executable" API, so relaunching resolves
`BY_SANDBOX_SELF`, else `byj` on PATH; with neither, the run says so and
continues unsandboxed (divergence `sandbox-self-exec`).

## API

`brainyard.permission`: `glob->re`, `glob-match?`, `first-match`, `container?`,
`resolve-mode`, `mode`, `cycle-modes`, `next-mode`, `deny-replace`, `deny-gate`, `permission-gate`,
A tool the person calls directly (`:<tool> …`, RFC-004) goes through the same
gates — `call-tool` with the session's ctx — so a deny rule refuses it and ask
mode asks (byj-gly.273.3; brainyard's colon commands skip them, divergence
`direct-calls-are-gated`).

`tool-verdict` (what `deny-gate` and `permission-gate` would do with a call of
a tool now, before any argument — `{:verdict :deny|:ask|:allow :why}`, the same
rules asked without calling anything; `/tools` shows it, byj-gly.273.1),
`asking-permission-fn`, `approval-hint`, `deny-hint`, `path-gate`, `under?`,
`call-paths`.

`brainyard.tool-hook`: `patch-paths`, `tool-map`, `decide`, `hook-output`,
`hook-agent`, `log-dir`, `hook!`, `hook-command`, `settings`,
`codex-line`; CLI `byj hook pretooluse|settings|codex-flags [--log DIR]`.

`brainyard.sandbox`: `available?`, `macos?`, `profile`, `write-roots`, `argv`,
`parse-allow-writes`, `self-argv`, `banner`, `relaunch!`, `sandbox-exec`.

`agent/make-ctx` and `agent/ask` take `:permission-fn`; `agent/make-ctx` also
takes `:permission-mode-box`, and a subagent inherits both.

## Invariants

| invariant | enforced by |
|---|---|
| Another agent's call refused by a rule is `deny`, one a gate would ask about is `ask`, and nothing objecting, a tool byj lacks or a hook that fails is no opinion; every decision is logged | `tool-hook-test/another-agents-calls-meet-byjs-gates` |
| A Codex patch is gated file by file, a move's destination included | `tool-hook-test/a-codex-patch-is-every-file-it-names` |
| The hook installs as `byj hook pretooluse`, in Claude Code's settings and Codex's session flags | `tool-hook-test/the-hook-is-installed-as-byj-hook-pretooluse` |
| The installed command asks `byj hook serve` and, with nothing there, gives the same payload, whole, to `byj hook pretooluse --socket` with the log; what the server answers is all that is printed | `hook-serve-test/the-command-asks-the-server-and-falls-back-to-byj` |
| The server prints nothing for no opinion, answers only `/v1/pretooluse` and `/v1/ping`, reads a large payload sent after a `100 Continue` whole, and is one to a socket | `hook-serve-test/the-server-says-nothing-for-no-opinion-and-refuses-what-it-does-not-serve` |
| The server ends when idle and when its byj changes on disk; a call that finds it upgraded goes to the fallback | `hook-serve-test/the-server-ends-when-idle-and-when-its-byj-is-upgraded`, `hook-serve-test/the-byj-a-command-names-is-found-as-the-shell-finds-it` |
| `byj hook claude-settings` installs the command that asks the server; `settings` still answers | `main-test/the-hook-installs-are-named-for-their-agents` |
| A chosen mode (`:permission-mode-box`) outranks the configured one, and a change reaches the next call; an edited `:permission-mode` waits for the next session, and the session says so, since a file tool can write `config.edn` (byj-gly.226). | `permission-test/a-chosen-mode-outranks-the-configured-one`, `session-test/an-edited-permission-key-waits-for-the-next-session` |
| Globs are whole-name, case-sensitive, `*`-only; the matched pattern is returned. | `permission-test/glob-matching` |
| `:auto` asks outside a container; an unknown mode asks. | `permission-test/modes` |
| In `:auto`, where a gate would ask, the classifier decides first: allow runs the call unasked, block refuses it with its reason and the override hint, unsure or a failure asks (or refuses, with `:auto-mode-on-unsure :deny`); it is not run in another mode or when switched off; the approval, path and command gates all ask it. | `permission-test/auto-mode-asks-the-classifier-where-it-would-ask-the-person` |
| A command matching `:bash-ask-commands` is asked about inside the allowed directories — classified in `:auto` (block refuses and nothing runs, allow runs it), asked in `:ask-each-time`, run in `:auto-approve`, refused in `:deny-by-default`; one segment of a line is enough, past `VAR=value`; `[]` asks about none; it is remembered by its whole line and never persisted by `r`. | `permission-test/a-destructive-command-is-asked-about-inside-the-project` |
| In `:auto` with a live model every command is classified, the list's or not; not with `:auto-mode-classify-every-command? false`, in another mode, or on a stand-in model or none. The defaults are sonnet and every command. | `permission-test/auto-mode-classifies-every-command-with-a-live-model` |
| A verdict is remembered for the same request and directory (or program), a new request asks again, unsure is not remembered, and every verdict is a `:permission/classified` event saying whether it was remembered. | `permission-test/a-verdict-is-remembered-for-the-request-and-said` |
| Each request handed to the person is `:permission/asked` and its answer `:permission/decided`, one id between them; a headless gate says neither. | `permission-test/a-prompt-is-said-to-watchers-and-nothing-answers-it-but-the-person` |
| The classifier is given the turn's question and the call, arguments cut short, and the workspace — no tool output; it asks its own model, the mock for a mock session, and none without a model. | `permission-test/the-classifier-sees-the-question-and-the-call-on-its-own-model` |
| The gates ship inert, at brainyard's priorities, and fail loudly rather than open. | `permission-test/the-gates-ship-inert` |
| A line is segmented as a shell would, and the program is read past `VAR=value`. | `permission-test/command-segmentation` |
| An allow-list holds every segment's program, refuses substitution and quoting it cannot parse, and a deny list needs no allow-list. | `permission-test/an-allow-list-refuses-what-it-cannot-read` |
| A separator inside single or double quotes is text, and an escaped one too. A real second command is still held to the list. Substitution is refused inside quotes too, and an unclosed quote is refused. | `permission-test/a-separator-inside-quotes-is-text`, `permission-test/an-allow-list-refuses-what-it-cannot-read` |
| The gate covers every tool declaring `:command-params`, `task$run` included, and a deny list ignores the mode. | `permission-test/the-command-gate-covers-every-tool-that-names-a-command` |
| A path a command names is checked against `:allowed-dirs`; one that does not exist is not a violation. | `permission-test/a-command-may-not-name-a-path-it-could-not-open` |
| A bd-only agent's shell — this repository's bead-keeper, `.brainyard/agents/beads` — runs `bd` and nothing else. | `agents-test/the-bead-keeper-runs-bd-and-nothing-else` |
| Deny refuses under every mode, cannot be exempted by an allow glob, and can key on an effect — including a user tool's inherited effects. | `permission-test/deny-gate` |
| Only a pattern or effect match asks; an allow glob exempts; the modes behave; headless, a denial and a throwing prompt all refuse; deny decides before permission. | `permission-test/permission-gate` |
| The terminal prompt shows the args and effects, remembers `a`/`d` for the session, and denies on end of input. | `permission-test/the-ask-channel` |
| **`r` persists the grant, appending, and says what it wrote**; a failed write still allows the call the person permitted. | `permission-test/remember-writes-the-grant-and-says-where`, `permission-test/a-grant-that-cannot-be-written-still-allows-the-call`, `permission-test/what-a-remember-answer-would-write` |
| **A permission key is written by the prompt and by nothing else** — only `#{:tool-allow-tools :allowed-dirs :permission-mode}`, and no command may ask as a person. | `configbox-test/a-person-at-the-prompt-may-grant-what-the-agent-may-not`, `configbox-test/no-command-can-ask-as-a-person` |
| A refused call is a tool result, not the end of the turn. | `permission-test/a-refused-call-does-not-end-the-turn` |
| Paths under the allow-list pass; one outside is refused naming the directories, `..` resolves first, a sibling directory is not inside one, every path is under `/`, `:allowed-dirs` extends, the modes route, and a user tool's own calls are gated. | `permission-test/path-gate` |
| A symlink inside the project pointing outside it is refused — matching is canonical, so a link is not a way through. | verified by hand from a directory outside the defaults, since anything under `/tmp` is allowed there and hides the question; not a test |
| The seatbelt profile is brainyard's, its write roots are ordered, deduped and escaped, and network can be denied. | `permission-test/sandbox-profile` |
| The argv is `sandbox-exec -p <profile>`, or `-f` with brainyard's `-D` parameters. | `permission-test/sandbox-argv` |
| `--sandbox-allow-write` expands `~`, resolves relatives and dedupes. | `permission-test/sandbox-allow-writes` |

| `/allow-path DIR` adds DIR for the session only, once, never to `config.edn`, and is refused over the session channel; every gate — the command gate's paths too — reads the session's directories beside `:allowed-dirs`. | `session-test/allow-path-is-the-persons-and-the-sessions`, `permission-test/a-directory-allowed-for-the-session-is-reached` |

## Known gaps

- **An allow-list is not a capability boundary.** It refuses what it cannot
  segment, but a shell can still reach a listed program's own escapes — `git`
  has `-c core.pager=…`, `bd` shells out for its editor. Where that matters,
  the sandbox is the boundary and the allow-list is the intent.
- A granted path lasts for the session unless the person answers `r`, which
  writes it to `.brainyard/config.edn` (above). `--allow-path` and a
  hand-edited config file remain the other durable routes.
- No tool-result cache gate (brainyard's 90) — it belongs with `:tool-cache-ttl`,
  which is not ported.
- No MCP permission gate — `byj-gly.14`.
- ~~Approvals are session-scoped only.~~ `r` persists one (above). brainyard's
  are session-scoped; its config agent can write `[:permissions :mode]` with a
  human confirming, so the posture here is the same judgement reached through
  the prompt rather than through a tool.
- The sandbox is macOS-only, as brainyard's is; Linux (bubblewrap/landlock) is
  not attempted.
