# RFC-067 — Emacs and the agent: `/emacs`

**Status:** decided 2026-11-05 ("권장안대로 진행": the recommendations below
as written). Phase 1, the foundation (`byj-gly.459`), phase 2, literate
programming in org (`.460`), phase 3, coaching (`.461`), phase 4, Emacs
asking byj (`.462`), phase 5, what Emacs knows (`.463`), and ACP's other
direction (`.464`) are built. Extended 2026-11-08 ("권장안대로 진행", D9–D13):
phase 7, what this Emacs says about itself (`byj-gly.471`), and phase 8, the
agent's changes reviewed in Emacs (`.476`), phase 9, learning Emacs (`.477`), and
phase 10, Emacs set up and work handed over from where it is (`.478`), are built.
Not in brainyard, which has no Emacs layer.

## Purpose

Decides how byj's agent works *with* an Emacs the person is using — not
beside it. byj can already open an Emacs frame (RFC-028's Terminal view, on a
daemon of the project's own), open a clicked file in it, revert the buffers
its tools wrote, and tell a turn where the cursor is (`@here`,
`terminal$context`). Each of those is one fixed call. This layer makes Emacs
a surface the agent reads and acts on, and the person's way back to the
agent, for three uses the person named (2026-11-05):

1. **Literate programming in org.** An org file is the source: prose and
   named src blocks, tangled to the files the build reads. The agent edits the
   block, tangles, runs the block that tests it, and reads the results, as a
   person working in org does.
2. **Emacs's own abilities, shared.** The project open in both, the agent
   using what the person's Emacs already knows — the buffers as they are,
   unsaved edits included, xref and the diagnostics of their LSP, org-babel,
   and elisp for anything else — and making Emacs do something new for the
   person (a command, a snippet, a `.dir-locals.el`) by trying it there.
3. **Coaching.** The person writes the code; the agent shows where to look,
   hints by degrees, checks what they wrote in the buffer as it stands, and
   leaves its findings as notes on the lines, writing the code only when asked.

And, from the coaching tour (2026-11-08), two more:

4. **Emacs itself, learned.** Emacs does a great deal and a beginner finds
   little of it. A model names keys from memory — vanilla's, Doom's,
   Spacemacs's — and this person's Emacs is none of them. So the agent asks
   their Emacs what a key runs and where a command is bound before saying
   either, watches (when they let it) which commands they use, and teaches
   one better habit at a time, with lessons that are data.
5. **The agent's work, met in the editor.** What a turn changed comes back to
   Emacs to be reviewed as a diff, a hunk reverted or commented on and sent
   back; the session's state shows in the mode line; and work is handed over
   from where it is — a diagnostic, a region, a TODO.

## Scope

**This layer decides** which Emacs byj reaches, how a call into it is made
and bounded, the `emacs$*` tools and what each may change, the `/emacs`
command, the coach stance's state, and the elisp byj loads (`byj.el`).

**It must not know** what the agent does with Emacs: when to tangle, how to
hint, what a review says. That is the `/emacs` command's usage, the tools'
descriptions and the coach prompt — resources (RFC-001). Nor how a program
runs in tmux (RFC-028's Terminal view), nor how a channel turn is queued
(RFC-031).

**It hands** tools to the registry (RFC-007), a prompt section to the turn's
user-context (RFC-004, as `@here` does), its effects to the permission gate
(RFC-012), and the person's questions from Emacs to the session channel
(RFC-031).

## What exists (byj-gly.451, .453)

| Piece | Where | What it does |
|---|---|---|
| The project's daemon | `terminal.edn`'s emacs entry | `emacsclient -t -a '' -s byj-${BYJ_PROJECT}` in a tmux session; the buffers outlive the frame |
| `:open-file` | `terminal/open-file!` | a clicked `path:line` opens in the running frame with `emacsclient -n` |
| `:after-tools` | `terminal/after-tools!` | reverts each buffer whose file the tools changed and that holds no unsaved edits |
| `:context` | `terminal/context!`, `terminal$context` | file, buffer, line, column, modified, the region — JSON to a file of byj's |
| `@here` | `:session/editor-context`, `prompts/editor-context.edn` | the same, put in the turn's user-context when the question mentions it |
| `byj acp` | RFC-053, `acp.clj` | an ACP agent; the person drives it from Emacs with agent-shell today |

Each call is an elisp form written into `terminal.edn`, its one input passed as
a string literal (`${OUT_STRING}`). That does not scale to a dozen operations
that take what a model wrote, so this RFC replaces it with one call path.

## Decisions

The person took every recommendation as written (2026-11-05).

| # | Question | Recommendation | Why |
|---|---|---|---|
| D1 | Which Emacs? | `:emacs-server`, default `"project"` — the Terminal view's daemon, `byj-<tag>`. Any other string is a server name or socket path (`emacsclient -s`); `"server"` is the person's own default server. | The person's own Emacs (here, an EXWM session) is where they look and where org, CIDER and their LSP are set up — the uses above want it. But it is their whole session: byj must never reach it unasked, as RFC-028 promises today. So reaching it is a setting the person makes once, in `~/.brainyard/config.edn`. |
| D2 | One Emacs for every path | Every call byj makes into Emacs — a clicked file, the revert after the tools, `@here`, the tools — goes through `brainyard.emacs` to the server `:emacs-server` names. `terminal.edn`'s emacs entry keeps its `:command` (how the project's daemon is started) and `:open-file` (for when byj cannot see that daemon's socket), and loses its `:after-tools` and `:context`. | Two paths would disagree about what "here" is. Built so rather than as a `${BYJ_EMACS_SERVER}` in `terminal.edn`, as first proposed: the Terminal view knows no config, and the entry's own calls were two of the three that needed one. |
| D3 | How a call is made | One fixed form: load `byj.el` if its version differs, then `(byj-call OP ARGS-FILE OUT-FILE)`. Arguments and answer are JSON files. | No text a model wrote is ever part of an elisp form, so there is nothing to escape and nothing to inject. `OP` is looked up in `byj.el`'s own table; an unknown one is an error. |
| D4 | Where the elisp lives | `resources/brainyard/emacs/byj.el`, a project's `.brainyard/emacs/byj.el` over it; written to `~/.brainyard/emacs/byj-<version>.el` to be loaded, since a built binary cannot hand Emacs a resource path. | What Emacs does is data, changeable without a rebuild (RFC-001). One file the person can also `require` (phase 4). |
| D5 | When the tools are offered | The root session's alone (as `terminal$*`), behind `:emacs-tools?` (true), while the server's socket file exists — checked each turn without a process. | A subagent reports to its caller and has no person in front of an Emacs. A tool that cannot work is not offered. |
| D6 | What is asked | `:editor/eval` and `:editor/execute` join `:tool-ask-effects`'s default; under `--sandbox` they are refused. `:editor/display` and `:editor/edit` are not asked. | Elisp and org blocks run as the person, with everything their Emacs can reach. An Emacs byj did not start runs outside the seatbelt (RFC-012), so the sandbox cannot hold what it runs. A highlight, a note or an unsaved, undoable buffer edit changes nothing on disk. |
| D7 | Coaching is not Emacs's | `coach$set` and a `:session/coach-context` node, usable without Emacs; `/emacs coach` uses Emacs to show and annotate. The stance is a file in the session's directory, not st-memory. | The stance — hint, do not write — is the agent's; Emacs is where it is best shown. A `/coach` command for the TUI alone is then one resource file. A tool runs inside the turn's scope and cannot write the session's memory, and a file outlives compaction and is where a resume looks, with no change to the snapshot's format. |
| D8 | Emacs → byj | `byj.el`'s commands send turns with `byj ask --attach` (RFC-031), asynchronously; ACP is the other way in (`.464`). | The channel already turns a question into a turn of a live session, shown in its transcript, and refuses slash commands. Nothing new on byj's side. |

The second round (phases 7–10), taken as recommended on 2026-11-08:

| # | Question | Recommendation | Why |
|---|---|---|---|
| D9 | What may byj learn of how the person uses Emacs? | `byj-observe-mode`, a minor mode the person turns on; off by default. It keeps command names, their keys and the buffer's major mode in a ring in Emacs's memory — never text typed (a run of one command is a count), never written to disk. `emacs$habits` reads a summary of it. | A habit is seen only by watching, and watching a person's editor is theirs to allow. Names and counts are enough to see `C-n` pressed twelve times; what they type is not needed and not taken. Kept in memory, it ends with their Emacs. |
| D10 | Where does a turn's change come from for review? | git: the project's work tree snapshotted as a turn begins and ends, as commit objects made through an index of byj's own, and every turn kept as the ref `refs/byj/turns/<session>/<turn>`, whose parent is its start — RFC-069's layer since `byj-gly.483`. magit or vc shows it. (Changed by `byj-gly.481`, from copies of the files byj's own tools wrote, kept in the session's directory.) | `git diff` alone mixes the turn's change with the person's uncommitted work and with earlier turns; a turn's two snapshots separate them. Snapshots see the work tree, so what bash, a formatter or a tangle wrote is in it, which the copies made by the tools that write could not see (`byj-gly.479`). magit and vc already revert, stage and commit a hunk, so byj does not have to. |
| D11 | Where do Emacs lessons live? | `resources/brainyard/emacs/lessons/*.org`, shipped; a project's `.brainyard/emacs/lessons/` and the person's `~/.brainyard/emacs/lessons/` over them, by file name. | What is taught is behaviour (RFC-001), and an org file is what an Emacs learner reads anyway. |
| D12 | A tool's permission ask answered in Emacs? | Not now (`byj-gly.475`, deferred). | It needs an answer path on the session channel that does not exist; agent-shell already answers asks through ACP's `session/request_permission`. |
| D13 | Where is this recorded? | In this RFC, as phases 7–10. | The same layer, deciding the same things; a new layer would get a new RFC. |

## Model

### 1. A call into Emacs

```
emacsclient -s SERVER --eval
  "(progn (unless (equal (bound-and-true-p byj-el-version) \"V\")
            (load \"~/.brainyard/emacs/byj-V.el\" nil t))
          (byj-call 'OP \"/tmp/byj-args-….json\" \"/tmp/byj-out-….json\"))"
```

- `emacs/call!` writes the arguments, runs the form bounded by
  `*editor-timeout-ms*` (5 s; `emacs$org-execute` has its own, D6's
  `:emacs-execute-timeout-ms`), reads the answer and deletes both files.
- `byj-call` parses the arguments (`json-parse-buffer`), runs `OP` inside
  `condition-case`, and writes `{"ok": …}` or `{"error": "…"}`. An elisp
  error is an answer, never an Emacs backtrace.
- Each call carries the project directory and session id; `byj.el` keeps the
  last per project, which is how phase 4 knows where to send.
- Emacs evaluates on its one thread. A call that takes long holds the
  person's Emacs; killing `emacsclient` at the bound does not stop it. So
  every operation but `org-execute` and `eval` must be quick by construction,
  and those two say so in their descriptions.

### 2. The tools

| Tool | Does | Effects | Phase |
|---|---|---|---|
| `emacs$status` | which Emacs, its version, `byj.el`'s, the project's buffers and which hold unsaved edits | — | 1 |
| `emacs$context` | `terminal$context`'s answer, plus the major mode, the visible windows' files, and in org the heading path and the src block at point | — | 1 |
| `emacs$read` | a buffer's text as Emacs has it — unsaved edits included — or lines a–b of it | — | 1 |
| `emacs$show` | visit `file:line` in the selected window, highlight lines a–b until the next show, a message in the echo area | `:editor/display` | 1 |
| `emacs$eval` | elisp, read from the arguments file and evaluated; the value printed | `:editor/eval` | 1 |
| `emacs$org-outline` | headings, src blocks (name, language, line, `:tangle` target, noweb references, results or not) | — | 2 |
| `emacs$org-execute` | one block, by name or line, through org-babel; results into `#+RESULTS`, unsaved unless `:save`, which is refused over unsaved edits | `:editor/execute` `:process` | 2 |
| `emacs$org-tangle` | a file's blocks tangled; targets checked against the write roots first, and again in Emacs that they have not changed; refused while the org buffer holds unsaved edits; the files written | `:fs/write` | 2 |
| `emacs$note` | notes on lines — overlays in the margin and a `*byj-notes*` list in compilation-mode, which `next-error` walks; clear | `:editor/display` | 3 |
| `emacs$edit` | an exact replacement in a buffer as one undo step, left unsaved and highlighted | `:editor/edit` | 3 |
| `coach$set` | the coach stance on with a goal and a level, or off | — | 3 |
| `emacs$xref` | definitions or references, through xref (eglot or lsp-mode where set up) | — | 5 |
| `emacs$diagnostics` | flymake's or flycheck's diagnostics for a buffer | — | 5 |
| `emacs$describe` | what this Emacs says about itself, in a buffer: a key's command, a command's keys and doc line, the modes on, the keys after a prefix, commands found by words | — | 7 |
| `emacs$habits` | `byj-observe-mode`'s summary: commands by count, runs a better command replaces, `M-x` calls of bound commands | — | 9 |
| `emacs$features` | features loaded or loadable, packages installed, programs on `exec-path`, the mode's eglot server, Emacs's version and init file | — | 10 |

A buffer with unsaved edits is never written under: `:after-tools` already
skips reverting one, and the `/emacs` usage sends a change to such a buffer
through `emacs$edit`, so the person's edits and the agent's meet in one buffer
the person saves.

### 3. `/emacs`

`resources/brainyard/commands/emacs.edn`, a command for tools (RFC-004
"Commands that stand for an agent", the `:tools` kind) over `emacs$*`,
`coach$set` and `terminal$context`, rephrased as other commands are. Its
`:usage` holds the working rules — read the buffer as Emacs has it, show
before explaining, the literate rule, never write under unsaved edits.

| Line | The turn |
|---|---|
| `/emacs` | `:default-request`: where Emacs stands — which server, whether it answers, the file in front of the person, the org files with blocks — and what `/emacs` can do here |
| `/emacs tangle docs/build.org` | the blocks tangled, the build or tests run, what changed |
| `/emacs run the block at point` | `emacs$context` for the block, `emacs$org-execute`, the results read back |
| `/emacs explain @here` | the code in front of the person, explained, the lines shown as they are discussed |
| `/emacs review my unsaved changes` | `emacs$read` against the disk, findings as notes |
| `/emacs make C-c t run this file's tests` | elisp drafted, tried with `emacs$eval` (asked), saved where the person says |
| `/emacs coach GOAL` · `/emacs coach stop` | coaching on, toward GOAL; off |
| `/emacs how do I rename this symbol` | the command found with `emacs$describe`'s `:apropos`, its key here given, the person left to press it |

The tools are offered on every turn while Emacs answers (D5), so `@here` and a
plain question can use them; `/emacs` is how a person frames work around them.

### 4. Literate programming in org (phase 2)

- **The org file is the source.** A tangled file is output: the agent edits
  the block and tangles, never the target. A built-in hook spec (RFC-011)
  refuses `write-file` and `update-file` on a file whose org-babel link
  comments (`:comments link`) name the org file it came from, and says which
  block to edit. A target tangled without link comments is not recognised; the
  project's AGENTS.md says so when that matters.
- **Read the structure, not the text.** `emacs$org-outline` reads the buffer
  with org-element, so an unsaved block, a noweb reference `<<name>>` and a
  header argument inherited from a property drawer are what the agent sees.
- **Run what tests it.** `emacs$org-execute` writes results where org puts
  them and returns them with the block's value. byj asked the person first
  (`:editor/execute`), so org's own `org-confirm-babel-evaluate` is bound
  off for the run. A language needs its babel backend in the person's Emacs
  (shell and elisp always; Clojure through CIDER).
- **Clojure on jolt, measured** (`dev/ob-clojure-spike.sh`, 2026-11-05):
  ob-clojure through CIDER runs against `jolt -A:dev nrepl-server` — connect
  in ~90 ms, a block in ~25 ms, `:results value` and `:results output` both,
  this repository's namespaces included. Two things to know: a `:results
  value` block is wrapped in one form, compiled whole, so a `require` and a
  call into what it loads go in separate blocks (as on the JVM); and an org
  file outside the project CIDER connected has no session, for which sesman
  prompts. So this repository's own code can be written in org.
- **Tangling writes outside byj.** Emacs writes the targets, so the tool asks
  for them first (`org-babel-tangle-collect-blocks`, op
  `org-tangle-targets`) and refuses when any is outside the directories the
  path gate allows, or holds a login or a key (`emacs/tangle-problem`); the
  tangle itself (`org-tangle`) refuses when the targets have changed since.
  `:after-tools` then reverts them in Emacs.
- **Nothing the person has not saved is saved.** Org saves the buffer before
  tangling (`org-babel-pre-tangle-hook`), so a tangle is refused while it
  holds unsaved edits, and so is `emacs$org-execute`'s `:save`. Every org op
  first reverts a buffer holding no edits whose file changed on disk — byj's
  own write a moment before, whose revert runs off byj's thread — and
  refuses one with edits on both sides.
- **Paths are byj's.** A tool naming a file declares it in `:path-params`,
  so the path gate checks it as it checks `read-file`'s (phase 1's
  `emacs$read` and `emacs$show` too), and hands it to Emacs absolute,
  against the session's working directory (`emacs/abs-path`).

### 5. Coaching (phase 3)

`coach$set {goal level}` writes `{:goal :level :slug :started-at}` to
`<session dir>/coach.edn` (`brainyard.coach`); `{stop true}` deletes it. A
session with no id keeps nothing and is refused. While it is set, the session
tree's `:session/coach-context` node, after `@here`'s, fills `prompts/coach.edn`
with the goal, the level's words and the checklist's name and puts it in each
turn's user-context — as `@here` adds the editor. `/emacs coach GOAL` and
`/emacs coach stop` make that call themselves, ahead of the turn (the
command's `:before`, RFC-004, `byj-gly.468`): a model once answered as a coach
without calling `coach$set` and kept no stance, so starting and stopping it is
the command's and not the model's — `beginner` when the words say so
(beginner, new to, 초보, 처음), else `coach$set`'s default. Because the call is
made before `:session/coach-context` runs, the turn that starts coaching
already has the coaching section; the turn is told what the call answered. The
prompt, not `src/`, says how to coach:

- The goal's steps become the `/todo` checklist (RFC-023), so progress
  outlives the conversation and compaction.
- Hints by degrees: a question that points; where to look (`emacs$show`); the
  shape of the change; the code itself, through `emacs$edit`, only when the
  person asks for it.
- A check reads the buffer as Emacs has it, runs the tests, and leaves what it
  found as notes on the lines (`emacs$note`), so the person reads them where
  they work and `next-error` walks them.
- The agent writes the person's code only when they ask.

`level` (`beginner`, `practitioner` — the default) chooses the words a hint
is given (`prompts/coach.edn`'s `:levels`). The checklist is named `coach-` and
the goal's first words (`coach/slug`). A turn from Emacs (`byj-check`, phase 4)
is an ordinary question; the stance comes from the file, not from the words.

`emacs$note` puts each note at the end of its line as an overlay (dimmed, its
kind and text) and lists every note in `*byj-notes*` in compilation-mode, made
`next-error-last-buffer`, so `M-g n` walks them; `:clear` takes a file's, or
all, away. `emacs$edit` replaces text found exactly once in a buffer, between
two undo boundaries, so one undo takes it back and only it; the buffer is left
unsaved and the new text highlighted as `emacs$show` highlights. The person's
own unsaved edits stay; a buffer whose file changed on disk and holds no edits
is reverted first, as the org ops do. Like `terminal$*`, the `emacs$*` and
`coach$set` tools are not in the prompt's full listing: a turn finds them
with `list-tools` and `get-tool-info`, and `/emacs` and the coach prompt name
the ones they use.

### 6. Emacs asks byj (phase 4)

`byj.el`, loadable by the person as well as by byj (`byj emacs install-el`
writes it to `~/.brainyard/emacs/byj.el` — the project's own when it has one —
and prints the init lines that `require` it and bind `byj-command-map`):

| Command | Sends |
|---|---|
| `byj-ask` | a question from the minibuffer |
| `byj-explain` | "explain this @here" for the region, or the defun at point |
| `byj-check`, `byj-hint` | a coaching check, the next hint |
| `byj-notes`, `byj-notes-clear` | the `*byj-notes*` list; clearing it (local, no turn) |

Each runs `byj -C DIR ask --attach SESSION --json -t SECS -q Q` as an async
process: Emacs never waits. The answer is shown in `*byj*` and appears in the
session's transcript, as every channel turn does (RFC-031). The session is the
one byj last called this Emacs from for the project — `byj-call` remembers it
from each call's `_byj` (a call without a session id leaves it), and a session
calls `hello` (`emacs/register!`) as its channel opens, so a live session is
known before it has done anything in Emacs; the deepest registered project
holding the buffer's directory wins. Else `byj session list --live --json`,
asking when there are several. An answer that the remembered session is not
live forgets it and asks a live one, once. `byj.el` defines
`byj-command-map` (a ask, e explain, c check, h hint, n notes, N clear) and
binds no global key. The channel refuses slash commands, so `/emacs coach`
starts from the session; the stance then holds for Emacs's turns. What each
command asks is `byj-questions`, byj.el's data, the file and line put in.

`@here` reads the Emacs `:emacs-server` names. A question sent from another
Emacs would be told about the wrong one, so `byj.el` adds `@here` only when
byj has called it for the project, and otherwise the question carries the
file and line alone (and `byj-explain` the text, always).

### 7. What Emacs knows (phase 5)

`emacs$xref` and `emacs$diagnostics` read what the person's Emacs already
knows about the code, read-only and not asked about:

- **`emacs$xref`**: definitions, or with `:references` uses, through the
  buffer's xref backend — eglot's or lsp-mode's server, elisp, a tags table.
  The name is given as `:identifier` (found on `:line`, or its first place in
  the file as a symbol) or as `:line` and `:column`; point is put there and the
  backend's identifier at point used, so every backend is asked the same way.
  Each place is file, line, column (from 1) and the text xref shows; a place
  known only by a marker (elisp's) is read from the marker. The answer names
  the backend and the name asked, never a backend's own token. No backend —
  including etags without a tags table, which would ask the person for one —
  is `:backend nil` and a note, not an empty list. elisp's and etags'
  references are a search of the project's files alone (`:scope`), never the
  load-path's, and are refused outside a project rather than prompting for
  one. Bounded by `:emacs-execute-timeout-ms`: a search may take seconds.
- **`emacs$diagnostics`**: flymake's (eglot's diagnostics come through it)
  or flycheck's for the buffer as it stands: line, column, severity (error,
  warning, note), text, checker; flymake's running checkers and those that
  have not reported yet (`:pending`). Flymake on but never run in the buffer
  (it runs when Emacs is idle there) is started, and the answer says so.
  Neither is `:backend nil` and a note: nothing checks the file in Emacs,
  which is not the same as clean.

Both cap their lists at `byj-max-items` (200), with `:total`. `/emacs`'s
usage asks them before grep, and falls back to grep and the build when
`:backend` is nil.

### 8. ACP, the other way in (`.464`, built)

agent-shell drives `byj acp` from Emacs. An ACP client may advertise
`fs.readTextFile` and `fs.writeTextFile`; then the session's `read-file`,
`write-file` and `update-file` read and write a file under the session's `cwd`
through `fs/read_text_file` and `fs/write_text_file` (`acp/client-fs`, over
`tools/file-tools`), so the editor answers from its buffer, unsaved edits
included, and takes a write into it — what `emacs$read` and `emacs$edit` give
a TUI session. The path goes under the `cwd` as the client spelled it. A file
outside `cwd`, and a read the client cannot answer, are the disk's; a write it
refuses is the tool's error, never written behind it. RFC-053 §3 has the
method.

### 9. What this Emacs says about itself (phase 7, `byj-gly.471`)

- **`emacs$describe`**, read-only and not asked about, in a buffer (`:file`,
  else the one in front of the person) so that its modes' keymaps answer:
  `:key` → the command it runs there; `:command` → its keys (`where-is`),
  its doc's first line, whether it is interactive; `:modes` → the major mode
  and the minor modes on; `:prefix` → the keys that continue it there and
  what each runs (which-key's view); `:apropos` → commands matching the
  words, with their keys. Each answer uses the person's keymaps, evil's
  states included. The `/emacs` usage: name no key you have not checked with
  it.
- **`byj-explain-error`** (`C-c b x`): the newest error — `*Backtrace*`, the
  last error in `*Messages*`, `*Warnings*`, a failed compilation buffer —
  sent as a question (`byj-questions`' `explain-error`), its text included.
- **The session in the mode line.** byj sends a `turn` op as a turn starts
  and ends (`emacs/turn!`, off the session's thread, like `:after-tools`):
  `byj⏳3` while turn 3 runs and `byj✓3` after it, in the mode line of a
  buffer under the project (an element of `global-mode-string`, empty
  elsewhere; `byj-mode-line` nil hides it), the question in its help-echo;
  the end is said in the echo area (`byj-turn-message`) — done, or how it
  ended otherwise (cancelled, out of iterations), and the key that asks
  byj. Each is stamped when sent, and one older than the last told is
  ignored: the two are sent detached and may cross. Nothing is sent without
  a session id, with `:emacs-tools?` off, or while Emacs is down. The end
  also says how many files differ between the turn's snapshots (phase 8):
  `byj✓3 ✎2`, and the key that reviews them.

### 10. The agent's change, reviewed in Emacs (phase 8, `byj-gly.476`, `.481`)

A turn's change is kept as git keeps things (D10): the project's work tree
snapshotted as each turn begins and ends, every turn kept as
`refs/byj/turns/<session>/<turn>` with its start as parent. That is RFC-069's
layer now (`brainyard.changes`, `byj-gly.483`), which also puts the files back
to a turn, takes back one turn alone and forks a conversation before a turn;
what is snapshotted and what is left out (`.gitignore`'s, `.brainyard/`, and
the lock, auto-save and backup files Emacs leaves beside a file it visits) is
said there. What Emacs adds:

- **What it holds, for Emacs.** An edit made in an Emacs buffer
  (`emacs$edit`) that is not saved when the turn ends is not in the turn's
  change: the snapshot is the disk's.
- **The diff.** `byj emacs changes SESSION [TURN] [--json]`, what `byj turns
  diff` says: git's diff between the turn's two snapshots, the last turn that
  changed files unless TURN is given; `--json` adds `:ref`, `:before`,
  `:after` and `:root` for byj.el.
- **In Emacs.** `byj-review` (`C-c b r`, a prefix asks which turn) asks for
  the session byj last called from (else a live one) and opens the range in
  magit (`magit-diff-range`), or in vc's diff (`vc-diff-internal`, diff-mode)
  when there is no magit or `byj-review-with-magit` is nil. Their own keys
  revert, stage and commit a hunk. The buffer gets the minor mode
  `byj-review-mode`, whose keys are clear of both: `C-c ;` says something
  about the line at point, with its file and hunk header; `C-c '` sends what
  was said, as the next turn of that session (`byj-questions`' `review`). The
  question names the `git diff` that shows the turn and asks the agent to
  read the files as they are now, since the person may have reverted some
  of it.

### 11. Learning Emacs (phase 9, `byj-gly.477`)

- **Observing** (D9). `byj-observe-mode` (`C-c b o`), global, off until the
  person turns it on, adds to `post-command-hook`: each command's name, the
  key it ran by only when that key is its own binding there (`byj--own-key`
  — so a self-inserting command, or text typed into a prompt, never leaves
  a key), the major mode, and whether `M-x` ran it; a run of one command is
  one entry with a count; at most `byj-observe-size` (3000) entries, in
  memory. Turning it off forgets them.
- **Habits.** `emacs$habits` summarises them: the commands run most (the
  keys used, the keys bound, how often through `M-x`); `:runs`, a command run
  `byj-run-length` (6) or more times in a row (`next-line` twelve times:
  `goto-line`, isearch); `:mx-with-keys`, commands run through `M-x` that
  have a key; and the tips given. `:tip` and `:why` record a tip, kept in
  Emacs's memory, and `:used-since` says how often its command ran after it.
  `/emacs tips` gives one tip at a time, saying first whether the last was
  taken up.
- **Lessons** (D11). The coach stance gains `:subject` — `code` (phase 3's,
  the default, and what a stance kept before subjects is) or `emacs`, whose
  section is `prompts/coach.edn`'s `:emacs-context`. `/emacs coach emacs:
  GOAL` (the command's `:before`, beginner when the words say so) sets it.
  `emacs$lessons` lists the lessons and reads one: an org file with
  `#+title:`, `#+summary:` and a heading a step, its `:COMMANDS:` property
  the commands that show it done — never keys, which `emacs$describe` looks
  up in the person's Emacs. Seven ship (help, moving, searching, editing,
  buffers and windows, files and projects, org), in `index.edn`'s order; the
  person's `~/.brainyard/emacs/lessons/` and the project's
  `.brainyard/emacs/lessons/` replace one by file name or add their own. A
  step is checked by `emacs$habits`, where a code check runs tests.
- **How.** `/emacs how WORDS` and `byj-how` (`C-c b ?`): the command found
  with `emacs$describe`'s `:apropos`, put in front of the person with
  `emacs$suggest` (`:editor/display`) — `*byj-howto*` with the command, its
  keys looked up in the buffer they are in, its doc line, and a button that
  runs it there — which they press, or press its keys.

### 12. Set up, and handed over from where it is (phase 10, `byj-gly.478`)

- **`emacs$features`** (read-only) and `/emacs setup LANG`: Emacs's version,
  init file and package archives; for features, whether loaded or loadable;
  for packages, whether installed; for programs, where on `exec-path`; for a
  file, its mode, the server `eglot-server-programs` names for it, whether
  that is found and whether eglot runs there. So the agent says why xref or
  diagnostics have no backend — the package missing, the language server
  not on `exec-path` — before suggesting anything; a setting is tried with
  `emacs$eval` (asked) only when the person agrees, an install is theirs,
  and a setting is saved only where they say — a file their init loads,
  such as `~/.emacs.d/byj-suggested.el`, never their init unasked (the
  `/emacs` usage).
- **From where it is.** byj.el's commands, each a question in
  `byj-questions`: `byj-fix-diagnostic` (`C-c b d`, flymake's or flycheck's
  diagnostic at point, fixed through `emacs$edit`); `byj-do-region` (`w`,
  the region and what to do with it, the change back through `emacs$edit`,
  one undo); `byj-review-unsaved` (`u`, notes, nothing changed);
  `byj-todo-at-point` (`t`, a TODO or FIXME comment, or an org TODO
  heading). `@visible` (each window on the person's frame: its file, the
  lines shown, the cursor, the one they are in) and `@trail` (the files they
  visited most recently, at their cursors) join `@here` in
  `:session/editor-context`, read through byj.el's `places` op
  (`emacs/places!`, `session/places-text`); Emacs's alone, said so when it
  is not up.
- **The checklist.** The session's `/todo` and coaching checklists are
  RFC-023's markdown files under `.brainyard/todos/`, already a list of
  checkboxes, so `byj-todo` (`l`) opens the newest — a prefix picks — beside
  the person's work with `auto-revert-mode`, rather than an org copy that
  would be a second source of the same list (as first planned). In it,
  `C-c C-c` (`byj-todo-toggle`) ticks the step at point, saves, and tells
  byj as a turn (`todo-done`); unticking saves alone.

## API

Built (`byj-gly.459`). `brainyard.emacs`:

- `server` (config → the name or path `emacsclient -s` is given),
  `project-server?`, `socket-files` (where emacsclient would look:
  `$XDG_RUNTIME_DIR/emacs/`, `$TMPDIR/emacs<uid>/`, `/tmp/emacs<uid>/`, or
  the path itself), `reachable?` (a socket file exists — no process, so it is
  asked each turn), `installed?` (emacsclient on the PATH, once a run).
- `el-source`, `el-file` (`{:file :version}`: byj.el written under
  `<home>/.brainyard/emacs/` once a version), `form` (the one elisp form),
  `call!` (`[config op args & [{:timeout-ms :home}]]` → `{:ok value}` or
  `{:error why}`; `op` one of `ops`), `*emacsclient*` (a test's stand-in).
- `context!`, `open-file!`, `after-tools!`: what `@here`, a clicked file and
  the revert after the tools ask, each nil or a no-op while Emacs is down.
- `commands` (the `emacs$*` tools), `sandboxed?`.

`terminal/run-bounded` is public, with an optional bound. byj.el's ops:
`status`, `context`, `read`, `show`, `open`, `revert`, `eval`. Config:
`:emacs-server` (string, `"project"`), `:emacs-tools?` (boolean, true), both
under `[:agent :config]` in a `config.edn`; `:editor/eval` is in
`:tool-ask-effects`'s default.

Built (`byj-gly.460`): byj.el's `org-outline`, `org-tangle-targets`,
`org-tangle`, `org-execute`; `emacs/abs-path`, `emacs/tangle-problem`; the
tools `emacs$org-outline`, `emacs$org-tangle`, `emacs$org-execute`.
`brainyard.literate`: `tangled-from` (the org file a file's link comments
name) and `tangled-file-guard`, the built-in hook
`resources/brainyard/hooks/tangled-file-guard.edn` (priority 87, `:opts
{:tools #{"write-file" "update-file"}}`). Config: `:emacs-execute-timeout-ms`
(60000), `:literate-guard?` (true); `:editor/execute` joins
`:tool-ask-effects`'s default.

Built (`byj-gly.461`): byj.el's `note`, `notes-clear`, `edit`; the tools
`emacs$note` (`:editor/display`) and `emacs$edit` (`:editor/edit`, not asked
about); `brainyard.coach` — `file`, `stance`, `slug`, `set-stance!`,
`context-text`, `commands` (`coach$set`, behind `:coach?`, a root session
with an id alone); `session/coach-context`, the node `:session/coach-context`,
`prompts/coach.edn`.

Built (`byj-gly.462`): byj.el's `hello` op and the person's commands
`byj-ask`, `byj-explain`, `byj-check`, `byj-hint`, `byj-notes`,
`byj-notes-clear`, `byj-command-map`, with `byj-program`,
`byj-answer-timeout` and `byj-questions`; `emacs/register!` (a live session's
`hello`, when its channel opens), `emacs/install-el!`,
`emacs/install-el-text`; `byj emacs install-el`.

Built (`byj-gly.463`): byj.el's `xref` and `diagnostics` ops,
`byj-max-items`; the tools `emacs$xref` and `emacs$diagnostics`, neither with
effects, both with `:path-params [:file]`.

Built (`byj-gly.471`): byj.el's `describe` and `turn` ops, `byj-mode-line`,
`byj-turn-message`, `byj-explain-error` (`x` in `byj-command-map`),
`byj-error-lines` and the `explain-error` question; the tool
`emacs$describe` (no effects, `:path-params [:file]`, one of `:key`,
`:command`, `:prefix`, `:apropos`, `:modes` at a time); `emacs/turn-args`,
`emacs/turn!`, called from the TUI session's event sink for every event.

Built (`byj-gly.476`, reworked by `.481`): `brainyard.changes` — `root`,
`ref-name`, `snapshot!`, `turns`, `begin-turn!`, `end-turn!`, `diff`; `byj
emacs changes SESSION [TURN] [--json]`; `:files-changed` on
`:session.turn/end`; byj.el's `byj-review` (`r` in `byj-command-map`),
`byj-review-with-magit`, the minor mode `byj-review-mode`,
`byj-review-comment`, `byj-review-send` and the `review` question. Config:
`:changes?` (true), `:changes-turns` (50). The changeset hook,
`:changes-max-bytes` and the copies in the session's directory went with
`.481`.

Built (`byj-gly.477`): byj.el's `habits` and `suggest` ops,
`byj-observe-mode`, `byj-observe-size`, `byj-run-length`, `byj-how`
(`?` and `o` in `byj-command-map`) and the `how` question; the tools
`emacs$habits` (no effects), `emacs$suggest` (`:editor/display`) and
`emacs$lessons` (no effects); `emacs/lessons`; the lessons in
`resources/brainyard/emacs/lessons/`; `coach/subjects`, `coach$set`'s
`:subject`, `prompts/coach.edn`'s `:emacs-context`; `/emacs`'s `:before`
for `coach emacs:`, and its rules for tips and how.

Built (`byj-gly.478`): byj.el's `features` and `places` ops,
`byj-trail-size`, `byj-fix-diagnostic`, `byj-do-region`,
`byj-review-unsaved`, `byj-todo-at-point`, `byj-todo`, `byj-todo-mode`,
`byj-todo-toggle` (`d`, `w`, `u`, `t`, `l` in `byj-command-map`) and their
questions; the tool `emacs$features` (no effects, `:path-params [:file]`);
`emacs/places!`; `session/places-text` and `@visible`, `@trail` in
`session/editor-context`, worded by `prompts/editor-context.edn`; `/emacs`'s
rule for setup.

## Threats

| Threat | Answer |
|---|---|
| Code run in the person's Emacs runs as them, with everything their Emacs reaches. | `:editor/eval` and `:editor/execute` are asked by default (D6); refused under `--sandbox`, whose seatbelt does not hold an Emacs byj did not start. |
| A model's text turned into elisp. | Never spliced into a form: JSON files (D3). `emacs$eval`'s code is the one thing evaluated, and it is asked. |
| A stuck or busy Emacs holding byj. | Every call bounded; the bound's error says Emacs may still be busy. |
| The wrong Emacs. | Never the person's own unless `:emacs-server` names it; `emacs$status` says which. |
| Tangling writing where byj may not. | Targets checked against the write roots before tangling. |
| The agent's write under the person's unsaved edits. | Not reverted (`:after-tools`); changes to such a buffer go through `emacs$edit`. |
| A test reaching the person's Emacs. | Tests use a stand-in `emacsclient`; the one real-Emacs test runs a daemon of its own name in a temporary directory and kills it, and is skipped where Emacs is not installed. |

## Invariants

| Invariant | Enforced by |
|---|---|
| No argument a tool was given appears in the elisp form `emacsclient` runs; arguments and answers are files, with the session that asked; an op not byj's is refused before Emacs is asked; what goes wrong — an elisp error, no answer, no JSON object, emacsclient failing — is an answer. | `emacs-test/a-call-puts-no-argument-into-the-elisp-it-runs` |
| Without `:emacs-server`, byj reaches only the project's daemon, never `server`; byj.el is written under the home given, a project's replacing the shipped one. | `emacs-test/which-emacs-is-the-projects-unless-named`, `emacs-test/a-call-puts-no-argument-into-the-elisp-it-runs` |
| While Emacs is down only `emacs$status` is offered, saying how to start it, and `@here` and a click go elsewhere; while it is up the rest are, reading never asked about, `emacs$show` changing only the screen, `emacs$eval` carrying `:editor/eval`, which is asked about by default. | `emacs-test/the-tools-are-offered-while-emacs-is-up`, `emacs-test/which-emacs-is-the-projects-unless-named` |
| A real Emacs answers each op: status names the project's buffers and which hold unsaved edits; read gives a buffer as Emacs has it, unsaved edits included, and a file no buffer visits from the disk; show's place is context's; revert leaves a buffer with edits alone. | `emacs-test/a-real-emacs-answers-each-op` (skipped where Emacs is not installed) |
| `emacs$eval` and `emacs$org-execute` are refused under `--sandbox`. | nothing yet: `emacs/sandboxed?` reads `BY_SANDBOX_CHILD`, which no test sets |
| `emacs$org-execute` is asked about by default (`:editor/execute`); every tool naming a file has it in `:path-params`; a relative path reaches Emacs absolute, the working directory's. | `emacs-test/the-tools-are-offered-while-emacs-is-up`, `emacs-test/which-emacs-is-the-projects-unless-named`, `emacs-test/a-file-goes-to-emacs-absolute-and-a-tangle-is-checked-first` |
| A tangle with a target outside the allowed directories, or where a login or key is kept, writes nothing and never asks Emacs to tangle. | `emacs-test/a-file-goes-to-emacs-absolute-and-a-tangle-is-checked-first`, `emacs-test/a-real-emacs-answers-each-op` |
| A real Emacs outlines an org file (inherited `:tangle` resolved, noweb references), runs a block (value and results, the buffer left unsaved), tangles with link comments, and refuses to tangle or `:save` over unsaved edits, saving nothing the person had not. | `emacs-test/a-real-emacs-answers-each-op` |
| A write-file or update-file to a file tangled with link comments is refused, naming the org file and `emacs$org-tangle`; reading it, a file with a link but no block end, an org file, a tangle whose org file is gone, and `:literate-guard? false` are not held. | `emacs-test/a-tangled-file-is-written-in-its-org-file`, `hooks-test` (the built-in list) |
| `emacs$note` puts notes at their lines and lists them for `next-error`, a note one line; `:clear` takes a file's away. `emacs$edit` is one undo step, leaves the buffer unsaved and the person's own edits as they were, and refuses text not found exactly once. | `emacs-test/a-real-emacs-answers-each-op`, `emacs-test/the-tools-are-offered-while-emacs-is-up` |
| The coach stance is the session's file: refused without a session id, practitioner unless asked, stopped by deleting it; while set, every turn's user-context has the goal, the level's words and the checklist's name, and none once stopped. | `emacs-test/coaching-is-kept-in-the-session-until-stopped`, `session-test/a-coached-session-tells-every-turn-how-to-coach` |
| `/emacs coach GOAL` sets the stance before its turn whatever the model does — beginner when the words say so — and that turn already has the coaching section; `/emacs coach stop` clears it. | `session-test/a-command-makes-its-own-call-before-its-turn` |
| byj.el's commands ask the session that called this Emacs for the project, as `byj -C DIR ask --attach SESSION --json -t SECS -q Q`, and show the answer in `*byj*`; a byj with no session leaves the one remembered; a live session registers as its channel opens; with none remembered, the one live session listed is asked; one not live is forgotten and a live one asked once; `@here` only in an Emacs byj has called; `byj emacs install-el` writes the project's byj.el, or the shipped one, where an init can require it. | `emacs-test/a-real-emacs-answers-each-op` (skipped where Emacs is not installed), `emacs-test/byj-el-is-installable-for-an-init` |
| `emacs$xref` answers through the buffer's backend and names it — elisp's definition from its marker, by name or by line and column; references a search of the project alone, refused with no project rather than prompting; no backend is `:backend nil` and a note. `emacs$diagnostics` gives flymake's diagnostics with their checker and severity, and `:backend nil` and a note where nothing checks the file. Neither has effects; both name the file in `:path-params`. | `emacs-test/a-real-emacs-answers-each-op`, `emacs-test/the-tools-are-offered-while-emacs-is-up` |
| eglot's definitions, references and diagnostics come through the same answers. | nothing in the suite (no language server is installed there); checked live against a stand-in LSP server, 2026-11-05 |
| `byj acp` reads and writes files under `cwd` through a client's advertised fs, in the client's spelling of the path, and the disk otherwise. | `acp-test/an-editors-fs-is-what-the-file-tools-read-and-write`, `acp-test/the-editor-is-asked-in-its-own-words` |
| `emacs$describe` answers from the keymaps and modes of the buffer asked about: a mode's own key, a key bound to nothing said so, a command's keys and whether this Emacs has it, a prefix's keys, apropos by every word with keys, the minor modes on; it has no effects and asks one thing at a time. | `emacs-test/a-real-emacs-answers-each-op`, `emacs-test/the-tools-are-offered-while-emacs-is-up` |
| A turn's start and end reach Emacs's mode line for the project's buffers alone, the end in the echo area with how it ended; one sent before the last told is ignored; nothing is sent without a session id or with `:emacs-tools?` off. | `emacs-test/a-turn-is-told-to-emacs`, `emacs-test/a-real-emacs-answers-each-op` |
| `byj-explain-error` asks about a backtrace before the end of `*Messages*`, its text in the question, which asks for keys to be checked with `emacs$describe`. | `emacs-test/a-real-emacs-answers-each-op` |
| A turn's change, which `byj-review` shows, is RFC-069's: git's diff between the snapshots of the work tree at the turn's start and end. | RFC-069's invariants |
| A snapshot touches nothing of the person's (HEAD, the index, the stash, the work tree), never signs and never names them. | `changes-test/a-turns-change-is-git-s-diff-between-its-start-and-end` |
| No test leaves a turn ref in this repository. | `test-runner/run` (`own-sessions`) |
| `byj-review` opens the session's last changed turn in magit's `magit-diff-range` when there is magit, else vc's diff, with `byj-review-mode` over it; what was said goes back to that session as one turn, once. | `emacs-test/a-real-emacs-answers-each-op` |
| `byj-observe-mode` keeps nothing until turned on and forgets all when turned off; a key is kept only when it is the command's own binding, never for typing; `emacs$habits` reports runs, bound commands run through M-x and tips with their use since, never typing as a habit. | `emacs-test/a-real-emacs-answers-each-op` |
| `emacs$suggest` shows a command with its keys looked up in the person's Emacs and a button, and refuses a command it does not have. | `emacs-test/a-real-emacs-answers-each-op`, `emacs-test/the-tools-are-offered-while-emacs-is-up` |
| The lessons ship in order, each step naming commands and no lesson a key; the person's and the project's replace or add by file name; every command a shipped lesson names exists in `emacs -Q` with org loaded. | `emacs-test/emacs-lessons-are-data-a-project-and-a-person-may-add-to`; the last checked by hand (2026-11-08), not in the suite |
| `emacs$features` says what is loadable, installed and on `exec-path`, and a file's mode and eglot server. `@visible` and `@trail` tell the turn the person's windows and recent files, or that Emacs could not say; nothing without the mention. | `emacs-test/a-real-emacs-answers-each-op`, `emacs-test/at-visible-and-at-trail-say-where-the-person-has-been` |
| byj.el's hand-over commands ask with the region's lines and text, the unsaved buffer (refused with none), the TODO line; `byj-todo` opens the checklist file itself, kept in step with the disk, and a tick there is saved and told. | `emacs-test/a-real-emacs-answers-each-op` |
| A stance's subject is code unless emacs; an Emacs stance gives the Emacs section; `/emacs coach emacs: GOAL` sets it before the turn. | `emacs-test/coaching-is-kept-in-the-session-until-stopped`, `session-test/a-command-makes-its-own-call-before-its-turn` |
| The stance survives a resume and compaction. | by construction — a file in the session's directory, read each turn, never in the history; no test resumes a coached session |

## Phases

1. **The foundation** (`byj-gly.459`, built): `:emacs-server`,
   `brainyard.emacs`, `byj.el`, `emacs$status/context/read/show/eval`,
   `/emacs`, `@here`'s org lines.
2. **Literate org** (`.460`, built): `emacs$org-outline/execute/tangle`, the
   tangled-file guard, the CIDER-on-jolt spike.
3. **Coaching** (`.461`, built): `emacs$note`, `emacs$edit`, `coach$set`,
   `:session/coach-context`, `prompts/coach.edn`.
4. **Emacs asks byj** (`.462`, built): `byj.el`'s commands, `byj emacs
   install-el`.
5. **What Emacs knows** (`.463`, built): `emacs$xref`, `emacs$diagnostics`.
6. **ACP's fs** (`.464`, built): `byj acp`'s file tools through a client
   editor's `fs/read_text_file` and `fs/write_text_file`.
7. **What this Emacs says about itself** (`.471`, built): `emacs$describe`,
   `byj-explain-error`, the session in the mode line.
8. **The agent's change, reviewed** (`.476`, built; `.481` made it git
   snapshots shown by magit or vc): `byj-review`, `byj-review-send`.
9. **Learning Emacs** (`.477`, built): `byj-observe-mode`, `emacs$habits`,
   `/emacs tips`, lessons, `/emacs how`.
10. **Set up, and handed over** (`.478`, built): `emacs$features`, `/emacs setup`,
    the hand-over commands, `@visible`, `@trail`, the checklist in org.

Phases 2–5 each wait on 1 alone, so they can be done in any order after it;
8–10 each wait on 7.

## Known gaps

- Detangling — a change made in a tangled file carried back into its org
  file (`org-babel-detangle`) — is not offered: the guard sends the agent to
  the org file instead; decided not now (`byj-gly.465`).
- A Terminal program drawn inside the TUI without tmux (`byj-gly.454`): an
  Emacs frame still needs tmux or the whole terminal.
- A tool's permission ask answered in Emacs (D12, `byj-gly.475`, deferred).
