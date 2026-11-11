# RFC-028 — The TUI renderer

**Status:** implemented (`byj-gly.96` decided it, `byj-gly.20` built it,
`byj-gly.100` made it the default). `brainyard.tui` is a **glimmer** component
tree over the **glimmer-tui** terminal backend, supplying RFC-027's six seams.
`jolt run:byj` runs it wherever a terminal allows and `-i`/`--inline` opts out,
as brainyard does. Mode A only — no tmux side channel (`byj-gly.98`).

## Purpose

Decides what the full-screen renderer is written *with*, before `byj-gly.20`
starts and commits the answer by accident. RFC-027 settled where a UI runs — in
the kernel's process, reached through `session/run`'s six function arguments —
and deliberately said nothing about what paints.

brainyard sources: `bases/agent-tui` (`layout.clj` alt-screen and scroll region,
`terminal.clj` raw mode, `input.clj` byte decoding, `render.clj` line builders,
`permissions.clj` the in-stream gate); docs `tui/architecture.md`,
`tui/renderer.md`, `specs/tui.md`.

## Scope

**This layer decides** what the renderer is built from, how the six seams attach
to a component tree, which thread may touch a widget, and how a terminal UI is
tested.

**It must not know** what a turn is or what an event means (RFC-026
catalogues those), or whether a tool may run (RFC-012 decides; the UI only
asks and reports). A renderer that starts deciding is userspace in the wrong
place (RFC-001).

**It hands** the CLI a renderer and the rules below. It hands the kernel
nothing: `session/run` is unchanged, and RFC-027's six arguments remain the
whole contract.

## Decision

**glimmer + glimmer-tui**, pinned in `deps.edn` (`jolt-lang/glimmer-tui`
v0.2.3, which pulls `jolt-lang/glimmer` transitively).

- **glimmer** is the portable half: reactive cells (`atom`, `cursor`,
  `reaction`), reagent-shaped components returning hiccup, and a reconciler
  (positional, or by `:key`). No dependencies, no toolkit knowledge.
- **glimmer-tui** is the terminal backend: widgets, box layout, painting
  through ncursesw, and an input loop with focus, scrolling, overlays, mouse
  and timers.

The alternative was porting brainyard's renderer as it stands — raw ANSI,
alt-screen, a DECSTBM scroll region, sticky live blocks, hand-decoded input.
That is roughly twenty files of terminal machinery to reimplement, and none of
it can be tested without a tty. The deciding property is not the widget set, it
is that **glimmer-tui paints through a swappable screen**: `attach!` points a
session at an in-memory grid, so a whole interactive UI can be mounted, framed,
typed into and clicked in `jolt test:byj` with no terminal at all. A renderer whose
invariants cannot name an enforcer is a renderer this repository cannot keep
honest.

### The six seams, as components

| seam | RFC-027 shape | in the renderer |
|---|---|---|
| `:emit` | `(fn [kind text])` | append to the transcript cell |
| `:on-event` | `(fn [event data])` | update the cell the event's view reads (RFC-026 decides what an event *is*; the component decides how it looks) |
| `:on-chunk` | `(fn [field text])` | append to the streaming reply cell |
| `:read-input` | `(fn [prompt]) → line-or-nil` | set the prompt cell, block on a promise the `:entry`'s `:on-activate` delivers |
| `:permission-fn` | `(fn [req]) → {:allowed true}` \| `{:denied true :reason …}` | a modal `:overlay`; the buttons deliver the promise |
| `:cancellers` | atom, `session/cancel-turn!` | `ctrl+c`, bound on a container |

The seam RFC-027 said decides everything is the one that costs least here. A
permission request is a call that blocks the turn with no timeout, and a modal
overlay is exactly that shape: focus is confined to it, nothing underneath can
be tabbed into, and the turn resumes when a button delivers the promise. The
spike drives it end to end against a kernel thread that blocks the way a turn
does.

### What it is

`src/brainyard/tui.clj`, and nothing global in it: `make` returns the cells, the
component to mount and the seams, so a test builds its own renderer and drives
it onto an in-memory grid. `run!` is the one edge that takes a terminal — it
starts the session on another thread, because the loop blocks the thread it is
given and two seams block the thread the kernel runs on.

```
┌ transcript ────────────────────────────┐   the tail, wrapped, windowed
│ ❯ read deps.edn                        │   :you, from read-input
│ ⏺ bash(command=cat deps.edn)           │   the live block, from :on-event
│   ⎿  four deps                         │   what the turn is DOING
│ ⏺ Heading                              │   :answer, read as markdown
│   │ (+ 1 2)                            │   a fenced block, styled as code
│ ✶ thinking… (7.1s · ↓ 1.4k tokens)     │   in flight: the turn, tasks, subagents
│ turn 1 · 2 calls · tokens 10.62K/1.37K │   the session so far, right-aligned
│ › /history  past lines                 │   only while a command is half-typed
├────────────────────────────────────────┤
│ ❯ ▊                                    │   the entry, always focusable
├───────────────────── claude-code/opus ─┤   the rule: the model in use
│   ▸▸ auto mode on (shift+tab …)  ctrl… │   footer: the permission mode, the keys
└────────────────────────────────────────┘
```

A permission ask floats over all of it as a modal overlay, and its buttons
answer in the ask channel's own letters — `brainyard.permission` still decides
what an answer MEANS, and a config is still only ever written by a person
pressing a key (RFC-012).

Four things follow brainyard's renderer rather than the line one:

- **The live block.** An iteration's tool calls appear as it makes them, with
  what each one is doing and how it came out, and the block is frozen into the
  transcript when the next iteration starts, or when the answer lands, so what
  the turn did is always above what it said. Without it an unverbose session
  shows nothing at all while it works — brainyard's sticky blocks
  (`tui/renderer.md`) exist for the same reason.

  The iteration's thought sits above its calls, dim, behind the transcript's
  bullet as a call and an answer are (`byj-gly.281`; brainyard's quiet render
  writes `● …`) (`byj-gly.280.3`): what the
  last result told the model and why these calls (RFC-003 'The thought').
  - It streams in while the reply arrives. Once the reply is parsed it becomes
    the block's, and it stays when the block freezes. It used to vanish there,
    and the next iteration's streamed reasoning grew onto the old one.
  - An iteration that answers shows no thought. That thought is where the
    model decides to stop, and it says "the result is in" whatever it is
    asked, so the answer speaks instead.
  - A subagent's thought goes into its own view.
  - `-v` and the event log still carry every thought, and the trajectory
    keeps it.

  A turn reads as a column of bullets. A call is `⏺ name(key=value, …)` — every argument, named
  (`tui/call-summary`; one value unnamed, `list-tools(false)`, hid a `:detail
  true` beside it), a value cut past 200 characters —
  its bullet
  dim while it runs, green when it returns and red when it fails, and once it
  has returned, a dim ` · 1.2s · 3.4k chars` after it: how long it took and how
  much came back, brainyard's `done, 1.2s, 3.4k chars` (`call-note`; the time
  rides on `:agent.tools/results` as `:elapsed-ms` and is kept out of the
  results a model reads again, where it would differ on every run). An
  argument of several lines — a bash script, a file's content — is `key=↓`,
  and its lines are under the call in the code colour behind a `┊`, the first
  `result-rows` of them and a fold for the rest (`arg-entries`): brainyard
  boxes it as a `Call` section, and its first line and `…` hid the script that
  ran (byj-gly.240). Then the
  first `result-rows` lines of its output under a `⎿`, each cut at the margin
  with `…` rather than wrapped (a result is often one long line, and wrapped it
  took a dozen rows), and a count of the rest. A result that is one line and
  reads back whole as a JSON or EDN map or vector — the kernel `pr-str`s a
  tool's return value, so a map arrives as one long line — is pretty-printed
  first (`tui/pretty-result`), once, as it arrives. A map reads as brainyard's
  `render-kv-body`: `name: value`, a string bare, text — `read-file`'s
  `:content`, a command's `:output` — as its own lines under its name rather
  than one `\n`-escaped string, and a collection too long for its key's line
  laid out under it. Scalars come first, collections next and text last, and
  the short ones share a row, so the three rows shown say what happened AND how
  the output starts — `status: completed · exit-code: 0`, `output:`, its first
  line. One key a row, those rows were all spent on the keys, and the output
  was never seen without a click; the tmux run found it (byj-gly.240, .241).
  One that starts like a collection but was cut short — past its cap, or the
  400 characters a resumed transcript keeps of each result — no longer reads
  back, so `reflow` breaks it by its punctuation instead: a line per element of
  the outer collection and per entry of a map inside it, strings left whole.
  Anything else, a result
  cut short by its cap or one already on several lines, is shown as it came.
  Only the renderer's copy: what the model reads is unchanged.
  An answer is one `⏺` with every row of its markdown indented beside it. There
  is no numbered header per iteration: the calls say what the turn did. An
  entry's `:margin` is outside its own `:lead`, so a list inside an answer
  hangs under its own text and the whole of it under the answer's bullet.
- **A finished turn says how it went.** Under the answer, one dim line: a word
  (rotating by turn), how long the turn took, and when it ended by the
  person's clock — `✻ Brewed for 7m 13s · done 6:23 PM`. The session reports
  `:session.turn/end` before it emits the answer, so the renderer holds the
  line in `:done` until the answer lands. A recap (`:session.turn/recap`,
  RFC-004) follows it as `※ recap: …`, wrapped under its own mark, and the
  recap's cost joins the session's total.
- **A resumed session is on the screen again.** `:session/resumed` carries
  the kernel's transcript (RFC-016), and each entry goes back the way it was
  first shown: a question as `❯`, a tool call as its `⏺ name(key=value, …)` with
  the start of its result under `⎿`, an answer as its bulleted markdown, a
  recap as `※`. The resume line lands under them, and the footer and usage
  row carry on from the resumed counts rather than from zero. A subagent's
  own transcript is not replayed: it lived in the renderer, not the kernel.
  Only the window is replayed (`resumed-entries`, built from the newest line
  back until `:max-lines` entries), in one change to the transcript, and the
  first frame lays out only what reaches the screen (rule 3). A 77-turn
  session's resume went from 1.6 s to 0.85 s, the same as with an empty
  transcript (byj-gly.250).
- **The input is a field of several lines.** glimmer-tui's only text field is
  one line, and a message of several was a row of `⏎` marks you could only edit
  at its end. `:text-area` is a widget of this renderer's. It holds real
  newlines, soft-wraps them by cells to the width it is given (`visual-rows`),
  and grows to `input-max-rows` (8) before scrolling to keep the caret in
  sight. The transcript gives up each extra row the input takes, so the rules
  and the footer stay attached to it.
  - **alt+enter** puts a line break at the caret; Enter sends.
  - **↑ and ↓** move between rows at the same column. They let go of the key
    on the first and the last row, or whenever a completion menu is up, so the
    container still walks the history and steers the menu.
  - **readline's keys** are line-wise: ctrl-a/e go to the ends of this line,
    ctrl-u and ctrl-k cut to them, and ctrl-w, alt-b and alt-f work by word.
  - **ctrl-f** passes through to find.
  - **A paste** — the terminal's own (cmd+v, bracketed) or **ctrl+v**, read
    from the system clipboard (`clipboard/read-text`: pbpaste, wl-paste,
    xclip, xsel, else tmux's buffer) — goes in at the caret, its line breaks
    as `\n`. A field whose container folds pastes into markers says
    `:pass-paste?`, and both reach the container instead: the session's
    prompt, where ctrl+v is a paste like any other. A field without it takes them itself.

  A clock-detected pasted break and a short bracketed paste are real newlines
  too; a long paste is still a marker.
- **The mouse, a paste and a non-ASCII key are decoded by glimmer-tui's reader,
  and this renderer hooks it** (byj-gly.272). It used to replace `read-event!`,
  as brainyard's own `input.clj` decodes these, for gaps reported from here —
  the wheel on stock macOS ncurses (#10), UTF-8 read as Latin-1, no mouse
  release or drag, Tab taken for the focus ring (#15), and a stalled paste split
  with no way to tell (#17). v0.2.11 closes them all, so `ui/run` is handed
  `:on-mouse` (every mouse event), `:paste-timeout-ms` (`paste-byte-ms`, 500)
  and the view's `:on-key` takes Tab and Shift+Tab. ncurses' mouse goes off and the terminal is asked
  for SGR reports and bracketed paste (`?1000h ?1006h ?2004h`, turned off again
  on the way out) — `terminal-input!`. It also unbinds every string keypad mode
  maps to KEY_MOUSE (`define_key(NULL, KEY_MOUSE)`): where terminfo spells
  kmous as `\E[<` (Ghostty's entries, Homebrew ncurses' xterm-256color), ncurses
  otherwise matched the start of each report and, with its mouse off, handed
  back one bare KEY_MOUSE for a burst and dropped the bytes, so in Ghostty a
  click, a drag and the wheel did nothing (byj-gly.268). Checked live by
  `dev/brainyard/dev/mouse_probe.clj` under both terminfos.
- **A path or a URL under a click opens** (`brainyard.links`, byj-gly.270) —
  brainyard's `links.clj`. It is detected at click time on the row clicked, not
  registered by what said it, so it works on every entry already in the
  transcript: an http(s) URL (it wins where both match), else a path with an
  optional `:LINE[:COL]` that must resolve to an existing file against the
  session's working directory. A target the wrap split is widened against its
  entry laid out where nothing wraps — each row carries its entry as metadata —
  never by joining rows, which would let the text forge a URL across the seam.
  A URL goes to `open`/`xdg-open` as argv; a file to `$VISUAL`/`$EDITOR` at its
  line and column in the editor's own convention, the terminal handed over as
  ctrl-o does (`hand-terminal!`); the footer says what opened. A row that opens
  something itself (a fold, a subagent) keeps its click. With the mouse on, URLs
  and the paths that read as a location (a `:line`, or absolute) are underlined;
  the underline is a hint, remembered per row, and the click re-checks.
  Divergence `link-open-lifts-brainyard-limits`.
- **Nothing the session starts may ask on the screen** (`byj-gly.297`,
  provenance P-32): `run!` and `run-many!` turn on `effect/detach-children!`
  while they own the terminal, so every subprocess that is not a hand-over
  (tmux attach, `$EDITOR`) has no controlling terminal — `sudo`, ssh, gpg fail
  rather than draw a prompt the raw-mode input would swallow — and destroy what
  is still running as they give the terminal back, or from a shutdown hook if
  the window is closed first (RFC-009).
- **ncurses starts from an entry whose `rep` it gets right** (`start-term!`,
  provenance P-25). macOS's ncurses is 6.0.20150808; before 20170729 ncurses
  used `rep` for characters past 255, printing one `─` and asking the terminal
  to repeat a byte. Ghostty's xterm-ghostty declares `rep`, so in Ghostty a rule
  came out one glyph long and every later frame was painted over a screen
  ncurses had wrong: short rules, a line twice, labels left behind. Where the
  loaded ncurses has the bug and TERM's entry declares `rep`, ncurses starts
  from the first of xterm-256color and xterm that exists without it, and TERM
  is put back once ncurses has read it (`terminal-input!`, and the run's
  `finally`), so what the session runs sees the real one. Driven under Ghostty's
  terminfo, the first rule went from 1 `─` to 80.
  - **SGR mouse report:** glimmer-tui decodes it and hands it to `on-mouse`,
    which claims it and turns it into the report this renderer acts on
    (`mouse-report`: SGR's button code, 32 for motion, `:release?`). A left
    press is glimmer-tui's `click!`, and a wheel notch is `wheel!` — its
    `scroll!`, which falls through to the transcript when no `:scroll`
    container is under the pointer. A notch moves `wheel-rows` (3). The loop
    hands over one event and repaints before the next, so wheel notches and
    drag motions are held `coalesce-ms` (12) for the rest of their burst and
    folded (`coalesce-reports`), a press or a release acting at once after
    what was held: a run of notches is one scroll by their sum, and a run of
    drag motions is its last position, so a spin or a drag is one repaint
    instead of one per report. That is what made the wheel and a drag lag
    behind the hand. A
    burst moves further per notch (`wheel-step`): twice from 3 notches, three
    times from 8. Scrolling stops at the transcript's first row (`max-offset`),
    by wheel and by pgup alike; it used to scroll on into an empty screen.
  - **Bracketed paste:** `ESC [ 200 ~` … `ESC [ 201 ~` is one `:paste` event
    holding the exact text, UTF-8 decoded, a byte allowed to stall
    `paste-byte-ms` (500) rather than glimmer-tui's 100. One still cut short
    carries `:cut?`, and its rest is not auto-sent (byj-gly.212). It goes into
    the line whole, with a long one folded to a marker at once. The clock that
    guesses a paste (`paste-gap-ms`) stays for terminals that do not bracket.
  - **UTF-8:** a character's bytes are one key, and a paste's are decoded too.

  **A drag selects, and the release copies.** With mouse reporting on, the
  terminal hands click-drag to the app instead of selecting. So the renderer
  selects itself: button-event tracking (`?1002h`) reports the drag, the cells
  it covers are drawn reversed over the transcript, and the release copies
  their text (`selection-text`, what the rows show, trailing spaces dropped).
  The copy goes through `brainyard.clipboard/copy!`, brainyard's clipboard
  ported. Inside tmux it uses `tmux load-buffer -w`, which also sets the
  system clipboard; a tmux without `-w` fills only its own paste buffer, and
  the footer says that rather than "copied". Over ssh it uses OSC 52, locally
  the native tool, and OSC 52 last. OSC 52's size limit refuses only an OSC 52
  copy — pbcopy, xclip and tmux take any size. The footer says what happened,
  "copied" only when the mechanism can confirm it. A press and a release on one cell is still
  a click, now on the release. A scroll clears the selection, whose cells would
  otherwise name other text. The terminal's own Shift-drag still works.

  A clickable row is underlined, as brainyard's clickable targets are — its
  text, from its first printable character, not its margin and indent
  (byj-gly.277). An
  open fold's clickable row is its `▴ click to fold` line alone; the rows it
  revealed are text, where a path or URL is a link again (byj-gly.276,
  `tui-test/a-long-result-folds-and-opens-in-an-editor`).
  `:enable-mouse false` asks for no mouse reports, and keeps the terminal's
  own click-drag selection.
- **A typed line stands on its own band.** What a person typed is drawn
  behind a dim `❯`, on a background that runs the whole row (xterm 237,
  `#3a3a3a`), with an empty row above it. A long transcript is then easy to
  scan for your own questions. The paint fills the rest of each row in that
  row's own background, so a band does not stop where the text does, and a
  wrapped line keeps both its band and its hang. A message of several lines
  keeps them, and its indentation. One longer than `paste-collapse-lines` shows
  that many and folds the rest, as the field folds a paste into a marker. The
  fold is the transcript's own (click opens it, ctrl-o hands it to the editor),
  drawn on the band because a fold is drawn `:as` the entry it folds. The model
  still reads the whole line. `you-entries` is the one shape every echo takes:
  the input line, a queued line, a resumed transcript and a child's question.
- **Long output folds, and opens in `$EDITOR`.** The rest of a result past
  its preview is a `:fold` entry, `… +N lines · click to show them`, that
  carries the whole result. A click opens it in place, up to `fold-rows` (200)
  lines, and a click on it again folds it. ctrl-o hands the whole text of the
  fold touched last (else the newest on screen) to `$VISUAL`, `$EDITOR` or
  `less` through `external-editor!`. That function calls `endwin`, runs the
  editor on the terminal, then calls `clearok` so the next frame redraws every
  cell. It blocks the loop thread while the editor runs, the one place rule 2
  is broken on purpose: the terminal belongs to the editor meanwhile, and the
  kernel's writes wait in the cells. A fold is laid out apart from its open
  self (`[id :open]` in the row cache), so opening it costs one layout.
- **`/queue` is the renderer's own command.** The input queue lives here: a
  line typed while a turn runs waits until the session next asks for one. A
  kernel command cannot see it, since every session command is a tree leaf.
  So `submit!` answers `/queue` itself, before the line could be read or
  queued behind the running turn. It lists what is waiting, numbered in the
  order it will be read, and `cancel N` or `cancel all` drops lines.
  `ui-commands` is the renderer's command table, joined to the session's for
  completion.
- **The frame: a rail each side of the centre** (byj-gly.123). Three slots —
  a left rail, the centre and a right rail. The centre is the whole screen
  described above, and always shown. Both rails start hidden, because each
  one competes with the thing being read for the same columns.
  - **Data and mechanism.** A rail holds views the caller passes to `make` as
    `:rails {:left [view…] :right [view…]}`, the way `:commands` are passed.
    A view is `{:id :title :items (fn []) :act (fn [item]) :mark (fn [items])
    :on-show (fn []) :aliases [name…]}`, and an item `{:label :hint :style}`,
    or with `:segments [[text style] …]` its label in pieces styled apart
    (byj-gly.398); `/left VIEW` and `/right VIEW` find
    it by its id, its title or an alias (`frame/view-index`). The renderer knows nothing about what a session, a
    file or a commit is (`brainyard.tui-frame`).
    - `:items` runs while the screen paints, so it only reads. Whatever reads
      files or git does so elsewhere, under `via blk`.
    - `:act` runs on the loop thread, so it starts work and does not wait.
    - `:mark` says where the mark goes when the view comes into sight, and
      `:on-show` tells the view it is coming into sight, which is when a view
      that reads starts reading (`open-rail!`).
    - Several views can be one: `frame/stack` lists them as sections, each
      under its own heading, in one list the mark moves through. A row does
      what its own view does with it, and the mark starts where the first
      view with a `:mark` puts it. It keeps following that until a key moves
      it (`:follow-mark?`, `frame/resolve-mark`): a section that reads after
      the rail opens, Project or Issues, changes how many rows come before
      the session's, and an index taken once had the mark land rows off
      (byj-gly.405). The left rail is stacked; the right rail
      switches between its views instead (byj-gly.384), and its Source view
      folds its own sections (byj-gly.397).
    - A stack's heading folds or unfolds its section, by Enter or a click,
      marked ▾ open or ▸ folded; every section starts open (byj-gly.456). A
      folded section's rows leave the list, so the mark passes over them and
      a `:mark` among them puts the mark nowhere. The fold state is a `cell`
      the caller passes (`tui/cell` in `main`), so a fold repaints the rail.
    - The rails are kept across restarts, per project, in
      `.brainyard/rails.edn` (`brainyard.rail-state`, byj-gly.457): each
      rail's open?, view and width (`frame/kept-rails`), and each view's
      folds — the stack's sections by title, Project's kinds, Issues' epics,
      Memory's sources, Files' directories, Source's sections and
      Self-improvement's proposals. Activity's turns are the session's and
      are not kept. `tui/make` takes `:rails-saved`, put back the first time
      the screen's size is known (`frame/restore-rails`: a view the side no
      longer has is its first, and a rail that would be drawn over the
      centre opens closed, since it would cover the input without the keys),
      and `:on-rails`, told what is kept whenever it changes — never for a
      move of the mark. A view takes `:kept`, `(kept k init)` → the cell its
      folds live in. One store per run serves every session in the window;
      a run that saves no session (`:persist-session? false`) or sets
      `:keep-rails? false` keeps nothing.
  - **What fills them.** `run-many!` hands each session the sessions view,
    and `main` stacks the project view above it and fills the right rail:
    - **Left, first: the project** (byj-gly.384, `brainyard.project-overview`).
      What the project itself defines for brainyard, never the built-ins or
      what `~/` holds.
      - The kinds: instructions (`AGENTS.md`, `CLAUDE.md`, its
        `config.edn`), agents, skills, tools, commands, hooks, the MCP
        servers its own config names, programs and user predictors.
      - Each kind is a heading folded with a count, and Enter unfolds it. A
        kind with nothing is left out.
      - Enter on an item opens its file, and `@` mentions it by its path
        from the project.
      - Agents and skills come from their loaders, which drop what they
        cannot read. A tool, command or hook file is listed whether it loads
        or not: checking means compiling it against the session's registry,
        and the session says at start what it skipped. A broken one is still
        there to open.
      - It reads off the loop thread, a newer read cancelling an older, when
        the rail opens and at a turn's end or after a tool batch while it is
        open. So an agent or skill made in the session shows without asking.
    - **Left, then: the window's sessions** (byj-gly.379, `sessions-rail`). One
      row a session, from the list's own `session-row` and in its groups:
      where it stands (`!` needs input, `●` working, `○` waiting, `·` ended),
      its name and its age. The session the rail is in is marked `›`, and the
      mark starts on it. Enter on another switches the centre to it, and its
      own rail opens there, marked on itself. The full list behind ← stays:
      it has room for each session's last line and its subagents.
    - **Left, then: the project's open issues** (byj-gly.402,
      `brainyard.issues`).
      - The tracker is what the project directory itself holds: `.beads/`
        is beads, read with `bd list --status open,in_progress,blocked
        --json`. Else a github.com remote in its own `.git/config` is
        GitHub, read with `gh issue list --json`, a priority taken from a
        `P0`..`P4` or `priority:*` label. Else the section is `Issues ·
        beads` saying beads are not available and /init sets them up
        (`issues/beads-unavailable-rows`, byj-gly.458): /init's `init$beads`
        runs `bd init`, and its check says how to install bd. Each refresh
        looks again, so `.beads/` made by /init is read with no restart.
      - Only the project directory is looked in, never a parent's. bd walks
        up from where it runs, and a test's project under `target/` would
        have read this repository's issues (the leak provenance P-40 closed
        for memories).
      - Each epic with open issues is a folded heading with its priority and
        count, the most urgent first; Enter unfolds it. Issues with no epic
        are under `no epic`. GitHub's issues, with no epics, are one graph.
      - Inside an epic its issues are a dependency graph in the lanes the
        branch graph uses (`project-views/lanes`, `issues/graph-order`): an
        issue above what blocks it, and otherwise in progress, then blocked,
        then by priority.
      - A row is the lanes with the issue's status where its node is: ○ open,
        ◐ in progress, ● blocked (red), blocked meaning waiting on an issue
        still open. Then a priority badge (P0 and P1 red, P2 yellow, P4 dim)
        and the title. A blocker in another epic is named in the hint, `⊣ id` in red.
      - Enter on an issue shows `bd show` (or `gh issue view`) in the
        transcript as a folded block, and `@` puts its id into the input.
      - It reads off the loop thread when the rail opens, and at a turn's end
        while it is open, as the Project section does.
    - **Left, then: what the project remembers, by source** (byj-gly.421,
      byj-gly.422, `brainyard.memory-rail`), unless `:memory?` is false.
      - **beads**, or whichever program `:memory-list-cmd` names: byj's own
        store, the one the agent reads and writes through `memory$*`
        (RFC-020), as it lists itself (`memory/list-task`, `bd memories
        --json` by default). `@` puts a memory's key into the input.
      - **Claude Code**: its auto-memory for this project,
        `~/.claude/projects/<project key>/memory/`, the key as
        `claude-sessions/project-key` makes it — a file a memory, keyed by
        its front matter's `name`, the `MEMORY.md` index left out. The
        person's own, on this machine: read-only, not recalled by byj's
        agent, and never read in a test that names no home of its own
        (provenance P-40). `@` puts the file's path into the input.
      - Not sources: the JVM brainyard's SQLite store (`~/.brainyard/memory`),
        which the port does not read (RFC-020), and Codex's memories, not
        yet (byj-gly.423).
      - One source is drawn flat under `Memory · SOURCE`; several are folded
        headings with their counts, as Issues' epics are. A source that
        fails says why on one line. A bd store with no bd on PATH, or no
        database bd would find — `BEADS_DIR`, else a `.beads/` in the
        project or a directory above it, as bd looks (`issues/beads-found?`)
        — is not run: it says beads are not available and /init sets them
        up, as Issues does, rather than bd's error (byj-gly.458).
      - Enter shows a memory whole in the transcript, as a folded block.
        Editing and deleting beads' stay `/memory`'s (byj-gly.327).
      - It reads off the loop thread when the rail opens, and when a turn
        ends while it is open, since `memory$remember` may have written.
    - **Left, last: self-improvement** (byj-gly.425,
      `brainyard.self-improve-rail`), always there, since it is where
      distillation is switched on.
      - Two toggles, `[x]`/`[ ]` tool distillation and skill distillation:
        `:enable-tool-distillation` (RFC-010) and
        `:enable-skill-distillation` (RFC-017), on in green and off dim.
        Enter on one sends `/config KEY true|false` to the session as if it
        were typed (the renderer's `send!`, queued while a turn runs), so it
        is written to the project's config.edn and put in force as `/config`
        writes anything, its lines in the transcript. The row flips at once.
      - Under them, folded under a `▸ Proposals` heading with their count
        (Enter unfolds it, as an epic under Issues), every pending proposal
        of every kind, newest first (`proposals/list-all`), with its score.
        The toggles stand flush, with no indent. Enter on a proposal shows the draft whole
        (`proposals/read-one`) with the `/proposals accept ID` and `reject
        ID` that decide it, and `@` puts its id into the input. Accepting is
        never the rail's: nothing drafted goes live unless a person types it
        (RFC-017, RFC-021).
      - It reads the config files and the proposals off the loop thread when
        the rail opens and when a turn ends, which may have staged one.
    - **Right: the project's files and its source** (byj-gly.380,
      `brainyard.project-views`): File and Source, Activity
      (`brainyard.activity`) and Terminal (`brainyard.terminal`), switched by
      F, S, A and T
      or `/right VIEW`, with the shown one in brackets in the header.
      - File: `git ls-files` with the untracked that are not ignored, as a
        tree whose directories are folded, each counting the files under it;
        Enter unfolds one.
      - Source holds three sections, each under a heading with its count that
        Enter folds and unfolds; all start open, and what is folded stays so
        for the session (byj-gly.397). `/right changes`, `commits` or `graph`
        open it.
        - Changes: `git status --porcelain`, one row a path with its state
          letters.
        - Commits: `git log`, the newest 30 on the current branch, those not
          on the upstream marked `↑`.
        - Graph: a map of the branches (byj-gly.398). From the newest 3000
          commits on every branch (`git log --all`, beads' Dolt refs left
          out), `branch-map` keeps the commits a branch or tag points at,
          merges, the commits history forks at, the newest and a root. Each
          kept commit's parents are rewritten to the nearest kept ancestor,
          so a history linear for a hundred commits still shows where its
          branches left and joined. A `/team`'s member branches show merged.
          - git's `--simplify-by-decoration` was tried first. It drops a
            merge unless both sides carry a name, so a branch merged into
            main looked like main.
          - The newest 60 kept commits are drawn in lanes byj lays out from
            the parents (`lanes`), the way lazygit and VS Code draw them. One
            row is one commit, never git's rows of drawing alone. A commit is
            `●` and a lane going on is `│`. A branch bends into its lane
            (`╮ ╯ ╭ ╰`) and crosses others (`┼`).
          - Each lane keeps the colour it was opened with, the commit its
            lane's, so a row is drawn in pieces (an item's `:segments`). A
            commit's row does what a Commits row does, `↑` included.
        - Commits are cut to 30 because the sections share one list: the
          graph below holds the older ones too.
      - Activity (byj-gly.411) is a live feed of what the session is doing,
        read from its events as they arrive (RFC-026): `main` hands each to
        `activity/record`, a pure step over the feed, and the view draws it.
        - Each turn is a section heading, `Turn N · HH:MM` with its duration
          and cost; the newest is open and older ones fold, and Tab goes
          from turn to turn.
        - Under it: ↻ iterations; ◐ a tool call running, then ✓ or ✗ with
          how long it took; ⇢ a subagent dispatched, its calls indented, and
          ⇠ its answer and cost; ⧗ a background task by what it is; ⚖ the
          auto-mode classifier's decisions; ! a retry after a model error or
          a hook's block; ■ how the turn ended.
        - It keeps the newest 500 rows; a turn none of whose rows is kept is
          forgotten. The mark follows the newest row until a key moves it
          (`:mark`, byj-gly.405). Enter on a call shows its arguments,
          duration and result in the transcript as a folded block.
        - A fault in recording an event costs that row, never the event.
          The feed is the screen's: a resumed session starts it empty.
        - brainyard's Mode B activity pane, in the rail; what needs tmux
          itself (panes that outlive the process) is byj-gly.98.
      - Terminal (byj-gly.412) lists programs beside the session — a shell,
        emacs, vim, lazygit, tig, htop, the event log — each run in a
        detached tmux session of its own, in the project directory, that
        outlives byj: brainyard's Mode B side panes, as Claude Code sessions
        already run (RFC-033, `claude-host`'s tmux settings).
        - Running lists this project's, found by name
          (`byj-term-<project>-<name>`, the project's directory name and a
          hash of its path), with how long each has run, so a later byj
          lists what an earlier one started; Start is the catalogue.
        - The catalogue is data: `resources/brainyard/terminal.edn`, with
          `~/.brainyard/terminal.edn` and the project's
          `.brainyard/terminal.edn` over it by name; `:hidden true` takes an
          entry away; `${VAR:-default}` in its argv is expanded when it
          starts. A program not on the PATH is listed, dimmed.
        - An entry reads `${BYJ_PROJECT}`, the project's tag
          (`terminal/vars`), and may say how to open a file in it while it
          runs without the terminal (`:open-file`, an argv with `${FILE}`,
          `${LINE}`, `${COLUMN}`; byj-gly.451). A `path:line` clicked in the
          transcript, and a file a rail opens, go to the first running
          program that has one (`terminal/open-file!`, the `:open-link` seam
          `main` gives the renderer), and to `$VISUAL`/`$EDITOR` as before
          when none runs. The footer says where it opened, or why it could
          not.
        - emacs is `emacsclient -t` on a daemon of the project's own,
          `byj-<tag>`, which `-a ''` starts when it is not up. The buffers
          live in the daemon: ctrl+x stops the frame's tmux session and the
          daemon stays, so the next start finds them, and a clicked file
          opens in the frame, the screen left to byj. It is the Emacs byj
          reaches unless `:emacs-server` names another, and every call byj
          makes into one — a clicked file, the revert below, `@here`, the
          `emacs$*` tools — is `brainyard.emacs`'s, through `byj.el`
          (RFC-067): a clicked file goes there first while it is up, then to
          a program with `:open-file`. The person's own Emacs server is never
          used unless `:emacs-server` names it.
        - After each batch of the agent's tools, every running program
          whose entry has `:after-tools` runs it (`terminal/after-tools!`,
          off the session's thread, from `main`'s `:agent.tools/results`;
          byj-gly.453), and the Emacs `:emacs-server` names reverts each
          buffer whose file changed on disk and that holds no unsaved edits
          (`emacs/after-tools!`, RFC-067) — a buffer the person is editing
          is left to them — so its frame never shows what the agent
          replaced, bash's writes included. `${VAR_STRING}` gives an argv that
          is code a value as a string literal (`terminal/string-literal`),
          and each call into a running program (`:open-file`,
          `:after-tools`, `:context`) is given 5 s
          (`terminal/*editor-timeout-ms*`), then its process tree is killed,
          so a busy editor holds neither byj nor a tool call.
        - Enter starts one that is not running and hands it the terminal
          (`tui/hand-over!`); `ctrl-b d` comes back, nested tmux hinted on
          its status line. ctrl+x on a running one arms, a second ctrl+x
          stops it. With no tmux, Enter runs the program in the foreground,
          and it does not outlive byj.
        - Run inside tmux, byj keeps the screen: Enter opens the program in
          a pane split from byj's own (`$TMUX_PANE`), attached from there to
          the program's session with `TMUX` taken away (`terminal/split!`,
          byj-gly.452). byj re-wraps to the narrower width, the session in
          sight beside the program. The pane carries `@byj-term`, the
          session's name, so Enter again selects it rather than opening a
          second (`terminal/split-pane`). It closes when its attach ends —
          `C-b C-b d`, or the program stopped — and the program runs on in
          its session. The footer says the outer prefix's keys. The entry's
          `:split` (`:h` or `:v`) and `:split-size` say how; `:open :attach`
          hands it the terminal as outside tmux, and so does a split that
          fails, after saying why. The outer tmux is `terminal/*outer-tmux*`,
          plain `tmux` reaching it through `TMUX`.
        - A view may take a key of its own on the marked row (`:on-key`,
          truthy when it did); ctrl+x is Terminal's.
        - The session's agent works the same programs with tools
          (byj-gly.417, `terminal/commands`, behind `:terminal-tools?`, offered
          only where tmux is and never to a subagent):
          - `terminal$list`: each program, installed or not, running or not,
            with its uptime.
          - `terminal$start NAME`: a catalogue program, detached; never one
            the catalogue does not name, nor one running.
          - `terminal$stop NAME`.
          - `terminal$send NAME`: `:text` typed as it is and Enter, or `:keys`
            by tmux's names (`C-c`, `Up`).
          - `terminal$capture NAME`: the screen, or `:lines` back with
            scrollback, its newest 16000 characters.
          - `terminal$context`: what the person is looking at in a running
            editor of this project's whose entry says how (`:context`,
            `terminal/context!`, byj-gly.453): the file, buffer, line,
            column, unsaved or not, and the selected region, its first 16000
            characters. Reading, so never asked about; with none running,
            an error that points to `emacs$context`, which is Emacs's
            (RFC-067).
          - `@here` in a question gives the turn the same answer without
            the model having to call it (RFC-004 "`@here`").
          - They reach this project's `byj-term-<project>-…` sessions alone.
            `terminal$send` carries `:terminal/input`, which the default
            `:tool-ask-effects` asks about (RFC-012): typing into a shell runs
            commands. The view rereads at a turn's end, so what the agent
            started is under Running.
      - A directory that is not a repository shows its files from a walk, and
        Source says it is not a repository.
      - Every git call is a `process-task` in one read Task. A newer read
        cancels an older one and the processes it started.
      - The views read from a reactive cell, so an answer arriving repaints
        the rail.
      - It reads when the rail comes into sight, and at a turn's end and
        after a tool batch while it is open; it never polls. The bead asked
        for "after a tool call that writes", but the renderer does not see a
        tool's effects, and a read is four git calls made only while the rail
        is open, so it reads after every batch.
      - A read cut short by the next ends quietly, and one that failed is a
        line in the rail. Reported as a detached failure, it was printed over
        the full screen (found live).
      - **Acting on a row** (byj-gly.381):
        - Enter on a file opens it in `$VISUAL`/`$EDITOR`, as a clicked path
          opens; the footer says what opened. On a directory, Enter folds or
          unfolds it.
        - Enter on a change shows its diff against HEAD; a new file git does
          not track shows as wholly added. Enter on a commit shows
          `git show --stat --patch`.
        - Each is read off the loop thread and shown in the transcript as a
          titled block (`show-block!`, `block-entries`): its first 12 lines,
          coloured as a diff, then the rest folded, a click or ctrl-o away.
        - `@` on a file or a change puts `@path` into the input, as the
          person would write it from the project directory, and gives the
          keys back. A view offers this with `:put`, and the footer says
          `@ mentions` when the one shown does.
        - **A shown diff is the screen's alone.** It goes into the
          transcript, never to the session: the model is not told of it, and
          it is not saved, so a resumed session does not have it. It is
          something a person looks at, and if the model should see a diff, it
          is asked to run one, which it then sees.
  - **Keys.**
    - ctrl+l is the left rail and ctrl+t the right. Each press goes one step:
      a closed rail opens with the keys; an open one takes the keys; the one
      that has them closes.
    - These are the ctrl-letters nothing else here takes. The field takes
      a, d, e, k, u, w and v. tmux takes ctrl+b. Readline users press ctrl+p
      and ctrl+n for history. ctrl+\ and ctrl+] decode as no key at all.
    - In a rail: ↑↓ and pgup/pgdn move its mark, Enter acts on the marked
      item, and Esc gives the keys back. The footer says so.
    - Tab and shift+Tab move the mark to the next or previous section of the
      view shown, wrapping, and never into another view (byj-gly.404,
      byj-gly.406). A section is a heading: a stack's (Project, Sessions,
      Issues) or one a view marks with `:section` (Source's Changes,
      Commits, Graph). In a view with none, File, Tab does nothing
      (`frame/section-stops`, `frame/next-section`).
    - A view is shown by its letter: the first letter of its title that no
      earlier view in the rail took, F for File and S for Source
      (`frame/view-keys`, byj-gly.406). Either case works, and the view is
      told it is in sight, so it reads. The header draws each letter
      underlined, the shown view in brackets, and the footer
      names the letters (`F/S views`).
    - The mouse moves it too: a left press on a rail's separator
      (`frame/separator-at`) takes hold of its inner edge, the drag puts the
      edge under the pointer (`frame/drag-edge`, within `frame/resize`'s
      bounds), and the release lets go without clicking. A rail drawn over
      the centre has its separator only while it is drawn.
    - A left click works the rail without the keys (byj-gly.449): on an
      item's row it marks the item and does what Enter does with it; on a
      view's name in the header it shows that view, as the view's letter
      does. The keys stay where they were — a click on a rail beside the
      centre leaves them in the input. `frame/rail-at` finds the rail and
      the column in it, `frame/click-target` the row's item or the header's
      view, from the window as it is drawn. The rule, a row past the items
      and the header beside its names do nothing, and the centre under a
      rail drawn over it never takes the click.
    - The wheel over a rail scrolls the rail, not the transcript
      (byj-gly.449): its window moves (`frame/scroll`), within its items,
      and its mark stays where it is, off the screen if the window leaves
      it; the next ↑ or ↓ brings the window back to it. A rail whose mark
      was following its view's `:mark` follows it no more, as after a key,
      or the next paint would put the window back.
    - ← and → move the rail's inner edge, the one facing the centre, two
      columns a press (byj-gly.404). On the left rail → widens it and ←
      narrows it; on the right rail the other way.
      - Before: ←→ switched the view and shift+→/← resized (byj-gly.399).
        Inside tmux on macOS the shifted arrows never arrived as keys: tmux's
        `tmux-256color` entry there has no `kRIT`, and the bytes were typed
        as text (byj-gly.403).
      - A modified key types nothing in a text field. glimmer-tui v0.2.14
        (glimmer-tui#22, byj-gly.418) decodes one as the key it is, with its
        `:mods`: ncurses' shifted codes (KEY_SRIGHT 402, …), and the escape
        run of a key ncurses had no name for — under tmux on macOS, whose
        `tmux-256color` has no `kRIT`, shift+→ is `ESC [ 1 ; 2 C`, which once
        arrived as alt+[ and the characters `1;2C` and was typed. A run it
        has no name for is dropped. A modified key matches no plain binding,
        so a field lets it go. byj's own interim, `csi-key!` (byj-gly.403),
        which swallowed such a run by its timing, is gone.
      - Any other key code in ncurses' range 257–511 that glimmer has no
        name for is let go too (`ncurses-key?`, byj-gly.400). Before this,
        the input typed shift+→ as `ƒ`. A character a person types never has
        such a code: a UTF-8 one arrives as its lead byte.
    - While a rail has the keys, the input field lets every key go and draws
      no caret (`:hold?`). Its caret still places the cursor, hidden
      (`[x y false]`, glimmer-tui v0.2.13): a terminal that draws a hidden
      cursor anyway, as Orca does, drew it where painting had ended — a
      rail's separator, the status line — and now draws it at the caret
      (byj-gly.401).
    - An ask or a picker comes first: neither rail key works while one is
      up.
    - Each rail keeps its own mark and window, beside the transcript's
      offset.
  - **Commands.** `/left` and `/right [VIEW]` open, close or switch a rail.
    They are the renderer's own commands, like `/queue`. A command that only
    changes what you see never reaches the session or the transcript; a
    line is said only when there is something to say, such as an unknown
    view.
  - **Widths.** `:size` is the centre's size and `:screen` the terminal's
    (`resize!`, `relayout!`), so everything that wraps re-wraps to the
    columns a rail leaves.
    - The centre keeps at least 60 columns, and the rails are 26 and 34 wide
      until resized (`frame/cols-of`). The width is kept in the rails' state
      for the session, through closing and opening.
      - A rail is resized (`frame/resize`) between 16 columns and as wide as
        leaves the centre its 60 beside it, the other rail included when it
        is open.
      - A rail already too wide to sit beside the centre, drawn over it, goes
        up to the screen less a column: widening it must not first shrink it
        to what fits beside.
    - Opening a rail that would squeeze the centre below 60 closes the other.
      If there is still no room, the rail draws over the centre as an
      overlay, which keeps its width.
    - An overlay never covers an ask or a picker, and stops short of the
      footer.
    - A rail drawn over the centre covers the input, so it always has the
      keys. Opening it gives them to it, even from `/left` or a switch, and
      Esc closes it as it gives them back. After a resize it is drawn only
      while it has them.
    - The footer is never wider than the centre. A box is never narrower than
      its widest row, so a footer of 114 columns left a rail beside it five
      columns (found live).
      - A tip is cut to what the left side leaves.
      - Anything else on the right stays, and the left gives way: its hints
        first, then the mode.
    - The widths were read from `dev/brainyard/dev/frame_spike.clj`'s frames
      of the real renderer:

      | columns | rails |
      |---|---|
      | 80 | either rail draws over the centre: beside it would leave 53 or 45 |
      | 120 | one rail fits beside the centre (93 or 85 left), both do not |
      | 160 | both fit, leaving 98 |

  - **The slots are always there.** The frame is four slots: the left rail,
    the centre, the right rail and an overlay. An empty slot is zero columns
    wide. If a rail appearing moved the centre to another place among its
    siblings, glimmer would remount it and the input would lose its focus.
    With both rails closed, the screen is what it was before rails, frame
    for frame.
- **ctrl+g sends a long tool call to the background** (byj-gly.391, RFC-019).
  It calls every release in `:backgrounders`, the session's and the
  renderer's one atom, as `:cancellers` is.
  - The tool calls the turn is waiting on become background tasks, and the
    turn carries on.
  - The footer says what moved.
  - The busy line offers the key once a call has waited
    `background-hint-ms` (2 s), so a quick call never flashes it.
  - With nothing waiting it does nothing.
  - It is Claude Code's ctrl+b, on another key because tmux's prefix is
    ctrl+b.
- **ctrl-f finds a line.** brainyard's find, as a mode of the input line
  rather than a read loop of its own, since a nested loop would re-implement
  paste, the mouse and ctrl-c and drift from them. ctrl-f puts `find:` in
  front of the field and keeps the draft aside. What is typed searches the
  transcript on the screen (root's or child's) as it is laid out, row by row
  and ignoring case, and the newest match is scrolled to the middle and drawn
  in reverse. Enter, ↑ and ctrl-f go to the older match and ↓ to the newer,
  and the footer counts them. Esc restores the draft and leaves the view where
  it is. The field lets ctrl-f through (`passed-through`) although glimmer-tui's
  entry binds it to forward-char, which the right arrow also does. A key the
  focused widget claims never reaches the container. The entry also claims
  ctrl-b, ctrl-u and ctrl-d, and those stay its own: ctrl-u, delete to the
  start of the line, is what a shell has taught everyone. So the transcript
  scrolls with pgup and pgdn only.
- **Grapheme widths are assumed, and measured once.** brainyard asks the
  terminal (DECRQM 2027), caches the answer, and measures tmux through a
  scratch pane. The port takes glimmer-tui's table: grapheme clusters, a
  wcwidth approximation, and two cells for a cluster carrying U+FE0F. Measured
  under tmux 3.6 with `dev/glyph-probe.sh`, which prints each glyph and reads
  the cursor position the terminal reports: all 13 glyphs agree, ZWJ families
  and skin tones included. The probe is for the terminal in front of you, and
  a glyph it shows drawn at another width is glimmer-tui's to fix, since it
  owns the painting.
- **A subagent has a transcript of its own.** A child turn's events carry
  `:subagent-id` (RFC-018). Its streamed reply, its iterations and its tool
  calls go into a buffer for that child instead of the root's transcript,
  where they read as the caller's own work. The buffer opens with the question
  (`❯`), from the dispatch or ask, and closes with the answer, from the result.
  A click on a running child's `▸` row, or on the `⏺ agent$…` or
  `agent-registry$ask` call that started it, puts its buffer on the screen, and
  the footer says which child is showing. Esc puts the root's transcript back,
  unless a permission ask is up, since Esc is the ask's own. Whatever was
  clicked hands the focus straight back to the input line, so typing still
  types. The last `max-subagent-buffers` (20) children are kept, and the one on
  screen is never the one dropped. The spinner, the counts and problems stay
  the session's.
- **What is typed in a child's view asks that child** (`byj-gly.344`). A plain
  line goes as the person's own `:agent-registry$ask {:id <child> :question
  <line>}` (`ask-line`), so the child's next turn — the same instance, its own
  turns behind it — streams into the transcript they are watching, with no
  root turn; the root's next turn is told of it through the console activity.
  A `/command` or a `:direct` call stays the root's, and the input and footer
  name the child a line goes to. Like any typed line it waits in the queue
  while a turn runs, and a child still working says it is queued. An ask that
  cannot reach the child — gone from the registry, a resumed session's —
  says why in the child's transcript and is never sent to the root instead
  (`tui-test/a-line-typed-in-a-childs-view-asks-that-child`,
  `agents-test/a-person-asks-the-subagent-on-screen`). A child parked as a
  background task runs while the root is idle, so the ask reaches it at
  once: a person's ask then waits for the child's turn to end (RFC-018,
  `byj-gly.345`) — the call block shows it running meanwhile, and
  `:agent.subagent/waiting` says why — where a model's is refused as busy.
- **A session's children, live or ended** (`brainyard.children`,
  `byj-gly.298.2`) — what the agent page lists under each session
  (`byj-gly.298.3`). One shape, `{:id :agent :owner :question :answer :success?
  :at :live? :turns}`, from two places: an open session's buffers
  (`tui/session-children`; each buffer keeps who dispatched it, its first
  question, its last answer, whether it answered, its turns) and a saved
  session's `trajectory.edn`, every child turn a record whose `:agent` is an
  instance id (`children/saved`, read again only when the file changes).
  `children/tree` nests them by `:owner` (RFC-018), newest first; one whose
  owner is not among them — "root", none on a record older than
  `byj-gly.298.1`, an evicted buffer — is at the top. `children/with-claude`
  puts on an agent$claude child the Claude Code session it drives, through the
  delegation records (RFC-039).
- **On the agent page, each session's subagents are rows under it**
  (`byj-gly.298.3`), open or saved: `tui/list-rows` follows each session row
  with its children (`tui/child-rows`) — indented by depth, depth first, at
  most `max-child-rows` (5) and `… N more` past it — and is what both the view
  and the mark use, so ↑↓ and the mark a returning session lands on count them.
  A child's row says what it is doing (`working · <question>`), how it ended
  (`answered`, `did not answer`, its turns, the start of its answer), or, for
  an agent$claude child, the Claude Code session it drives with the delegation's
  state — `✻` and counted as awaiting input when it stopped to ask. That Claude
  Code session is listed both under its child and in the Claude Code group
  (asked 2026-10-08, `byj-gly.304`: first it was listed under its child only),
  and a delegation that asks is counted once, by its session, though both rows
  say so. `main` supplies the children (`run-many!`'s `:children`): the saved
  trajectory's, the open renderer's over them (`children/merge-live`), as a
  tree, joined to the Claude Code sessions. Checked live on this repository
  (mock model, tmux): a saved session listed its four subagents under it.
- **A workflow's steps are calls** (`byj-gly.299`). `:agent.tree/step-start`
  freezes the live block and starts one with the step as its call
  (`name(input=…, …)`), `:agent.tree/step-progress` lines go under it while it
  runs — the newest `progress-rows` (4) — and `:agent.tree/step-end` puts what
  the step kept, or its error, in their place. In the turn's transcript or a
  subagent's (they are child events). So agent$claude's view says what it chose,
  that it opened the session, what Claude Code is doing while a round is
  awaited, what it judged, and what publishing did; `plan-act-eval`'s steps show
  the same way.
- **A running tool shows what it prints** (`byj-gly.364`). `:agent.tool/output`
  — bash's lines as it prints them, `{:tool-name :line :call}`, `:call` its
  place in the batch — goes under that call, the newest `progress-rows`
  (`with-output`), and gives way to the results. A line from another call of
  the batch starts its own, so a call still waiting shows nothing. A
  device-code login's code is on the screen while it waits, not after.
- **Enter on a subagent's row opens it** (`byj-gly.298.4`, `run-many!`'s
  `open-child!`); **→ on an agent$claude subagent's row opens the Claude Code
  session it drives** (`claude-of!`), attached when it runs here, resumed when
  saved (RFC-033), and the footer says so when such a row is marked — asked
  2026-10-08: Enter had gone straight to the Claude Code session, where every
  other subagent shows its view. Enter on any child, agent$claude's included:
  its session —
  opened, or resumed when saved — with the child's transcript on the screen,
  as a click on its `▸` row puts it (`tui/show-child!`); a buffer the renderer
  did not keep (a saved session's child, a turn from before a resume) is
  rebuilt from the child's trajectory records (`tui/child-entries`: each turn's
  question, each step's thought and tool calls as the live view draws them, its
  answer; `run-many!`'s `:child-records`), and says of the child what its
  record says. Esc returns to the session's own transcript. Checked live
  (tmux, mock): Enter on a saved session's `explore` child resumed the session
  and showed the child's report, `▸ explore/sapphire-panda-1 · esc to
  return`; Esc returned. The first try found two faults the tests had not: the
  list came back over the opened session (an empty note re-made it), and the
  rebuilt buffer said "did not answer" over the record's "answered".
- **Scrollback is a window.** Each transcript — the root's and each child's —
  keeps its newest `:scrollback-max-entries` (2500) entries, an entry being a
  paragraph, a code block or a tool call before it is wrapped; the oldest fall
  off the top. `main` passes it as `tui/make`'s `:max-lines`, so a change
  applies from the next session. The kernel keeps its own transcript, of
  `:transcript-max-lines` (RFC-004), which is what a resume draws again.
- **What is in flight says so.** A row for the running turn — a turning glyph,
  a rotating word while a model is waited on (so a stalled spinner is
  distinguishable from a stalled turn) or `working` while tools run, and in
  brackets how long the turn has been going, the predictor, and the tokens it
  has written — then a row each for every background task, and every
  subagent — each with how long it has been going, ticking off the loop's own
  timer. They appear above the rule, a blank row between them and the
  transcript so the spinner does not read as the transcript's last line, and
  vanish, padding and all, when nothing is running.
  brainyard keeps a sticky block per family for the same four things
  (`tui/renderer.md`); a compaction is not among them here, because it is one
  event after the fact and what a person wants from it is the number, which
  goes in the transcript.
- **The input queue.** A line typed while a turn runs is held and taken by the
  next prompt, rather than delivered into nothing. The footer counts what is
  waiting.
- **Pasted line breaks.** A newline within `paste-gap-ms` of the previous
  keystroke is pasted, not pressed: it goes into the field as a break instead
  of sending the line, so a stack trace arrives as one message. The terminal
  would say this outright if glimmer-tui asked it to (`byj-7rz`); until then
  the clock does, and the two are far apart — see the measurement below.
  Except the burst's last key: one line and the Enter that ends it, arriving
  together — `tmux send-keys "text" Enter`, a pasted command — is SENT once
  the burst has settled, as a shell takes it, instead of waiting with a
  trailing break (byj-10c, `tui-test/a-line-and-its-enter-in-one-burst-is-sent`).
- **A break you asked for, and one you pasted.** Alt+Enter puts a line break in
  the field instead of sending the line, so a message can be composed across
  lines deliberately. A PASTED break goes in as a mark, and once a paste is
  over — the loop's tick is the first moment that can be known — anything with
  more than `paste-collapse-lines` breaks folds to `[paste #N +M lines]`, which
  is expanded back at submit. A hundred-line trace becomes one row saying what
  arrived, and the model still reads the trace.
- **The conversation is named on the input's rule.** The name the last recap
  gave the conversation (`:conversation-title`, RFC-004) sits right-aligned on
  the rule over the input: `──── fix-drag-performance-and-crashes ──`. A
  resumed session is named from its snapshot. The history's place keeps the
  left, and a name is cut only where it would crowd that out.
- **`/config` is an editor and `/model` a picker.** The session hands the rows
  over (`:present`, RFC-027) and the renderer opens one picker above the input,
  taking its rows from the transcript as the menu does: a title with what the
  filter matched, the rows under their family (or provider) headings, what is
  above and below the window, and what the marked row is — its value whole when
  its row cuts it, its doc, and where its value came from or why it may not be
  written — wrapped, not cut at the edge (byj-gly.283). The detail takes the
  rows of the tallest among what is listed, so the list does not jump as the
  mark moves, and no more than the screen leaves once the list, the chrome and
  three rows of transcript are kept (`picker-detail-max-rows`, 2 to 10), the
  last line cut with `…` (`tui-test/a-marked-settings-detail-is-wrapped-not-cut`).
  It is a mode of the
  input line, as ctrl-f is: what is typed filters (every word, in the name or
  the doc), ↑↓ and pgup/pgdn move, Esc closes and gives the draft back. Enter
  answers with the line a person would have typed, so a pick means what the
  command means: a switch flips in place (`/config recap? false`, sent without
  an echo, the editor kept open and handed the new rows), any other setting is
  put in the input to edit (`/config max-iterations 20`), and a model is
  switched (`/model anthropic/claude-sonnet-5`, echoed). ctrl-r resets a
  changed setting (`/config unset KEY`); a setting that may not be written
  says why and Enter sends nothing.
- **`/mcp` is a picker too** (RFC-024): a group per server — its state, and
  its tool count while it runs — with its tools folded under it: `▸`/`▾` on
  the server row, → opens and ← closes (← on a tool closes its server and
  goes to it), each tool a line behind an indented `-` (`○` when hidden). A
  typed filter looks under closed folds too, so a search still finds a tool.
  A server can offer dozens of tools, and listed flat they buried the rest.
  What is typed is also sent to the MCP Registry once typing pauses (700 ms),
  quietly, and the hits arrive as a group of their own; the title says a
  search is running. Enter starts or stops
  a server and hides or shows a tool, ctrl-r restarts a server, each sending
  the `/mcp` line the row carries, quietly; the session hands the open picker
  its rows again after each.
- **`/tools` is a picker with an info pane** (byj-gly.273.1): every tool
  under its family, the marked one's spec — kind and source, description,
  arguments (type, optional, description), `Outputs:`, effects, what the gates
  would do with a call now, and how to call it through the model (`/grep
  <pattern>`) and directly (`:grep :pattern "…"`) — beside the list when the
  screen is `tool-pane-min-width` (110) wide or more, under it (its first ten
  lines) when not. A typed filter ranks the tools whose id holds it before
  those whose description alone does, and goes flat (`:rank-label?`). Enter
  puts `/<tool> ` in the line (the model calls it, byj-gly.273.2); tab puts
  `:<tool> :<first required> ` (a direct call, byj-gly.273.3) — Tab reaches
  the view's `:on-key` before the loop would move the focus with it. A direct call comes
  back through `:present :tool-call` and is drawn as a model's call is — the
  call line, then its result folded past its first rows (byj-gly.273.3).
  The picker keeps its full height while a filter shortens the list, and the
  pane never runs past the rows `picker-height` reserves — its `… /tools
  NAME` line inside `tool-pane-rows` — so the input line and the footer stay
  on the screen however full the transcript is (byj-gly.275, provenance P-27;
  `tui-test/a-filtered-tools-picker-keeps-the-input-on-screen`).
- **`/memory` is a picker of the project's memories** (byj-gly.327, RFC-020):
  each key with its first line beside it and the marked one whole under the
  list; `/memory QUERY` opens it with the query as its filter. Enter sends
  `/memory edit KEY` quietly — the session hands the terminal to `$VISUAL` /
  `$EDITOR`, else vi, never the pager (`links/edit-argv`, which tells a GUI
  editor to `--wait`), and stores what comes back — and ctrl-x asks `delete KEY? y/N` in
  the footer: y sends `/memory delete KEY`, any other key keeps it. The answer
  is taken where typing is, since a printable key reaches the input field
  before the view's `:on-key`; it is never typed into the filter. The session
  hands the open picker its rows again after each.
- **A permission ask rings the terminal** (byj-gly.328): when a session turns
  to Needs input, `:ask` writes `attention-sequence` once — a BEL by default,
  or an OSC 9 desktop notification naming the session (wrapped in tmux's DCS
  passthrough under `$TMUX`), both, or nothing, as `:attention-signal` says.
  It is written on the render loop's thread (`after!`), between frames, never
  inside an escape sequence a frame is writing. One ask is one ring; the next
  ask, after it is answered, is a change again. A delegation that stopped to
  ask a person (byj-gly.290.8) is counted as awaiting input on the session
  list but does not ring.
- **The permission mode is in the footer, and shift+tab cycles it.** The
  footer's left is the mode in force — `▸▸ auto mode on` (yellow),
  `▸▸ auto-approve mode on` (magenta), `‖ ask-each-time mode on` (dim),
  `✕ deny-by-default mode on` (red) — with `(shift+tab to cycle)` after all
  but ask-each-time. Shift+tab steps `permission/cycle-modes`
  (auto → auto-approve → ask-each-time) by writing the run's
  `:permission-mode-box`, which every gate reads at every call (RFC-012), so
  the change reaches the next tool call, mid-turn included. Since
  glimmer-tui#15 the view's `:on-key` is offered `:back-tab` before the loop
  would move the focus with it, and always claims it (byj-gly.272). The turn and call
  counts moved to the usage row above the input, before the tokens and cost:
  `turn 2 · 5 calls · tokens 1.12M/3.14K (last call: 250.00K/512) · $0.0123`.
  Counts carry their unit (`tui/token-count`: K and M, two decimals). The
  session's total is the bill; the last call's input is what says how full the
  context is, which the total cannot. A blank row sits over the usage row, so
  it stands apart from the transcript or the spinner above it (byj-gly.284,
  `tui-test/the-input-sits-between-two-rules-under-what-is-running`).
- **Several sessions, one in front.** `run-many!` holds several sessions, each
  a renderer (`make`) and a thread of its own, so a background turn keeps
  running and keeps writing to its own transcript; the screen mounts whichever
  renderer is in front, or the session list. ← on an empty input line opens
  the list (the footer says `· ← for agents`): a header with how many sessions
  await input, work, wait and have ended, then the sessions grouped by
  `session-state` — `:needs-input` (a permission ask is up), `:working` (a turn
  runs), `:idle`, `:ended` — each row its name (the recap's title, else its
  first question), the last thing it said and how long ago. ↑↓ select, Enter
  opens, Esc returns to the session you left, ctrl+x ends a session (or takes
  an ended one off the list), and a task typed into the list's own input line
  starts a new session in the background with that task as its first question.
  Below the open sessions, **Saved** lists sessions saved from earlier runs that
  are not open here, and Enter on one resumes it in a new tab (`byj-gly.178`).
  For resuming a whole run, `run-many!` takes `:initial` (the sessions to start
  with, `{:resume id}` each), `:front`, `:on-change` (the open set and the one
  last in front, whenever either changes — `main` records it, RFC-016) and
  `:saved`; a spawned session names its `:session-id`.
  `main`'s `tui-session` makes one: the first is the run's own (it reads `-q`
  and resumes `--resume`), every later one gets a new session id. The permission
  mode is the run's, shared; tasks, wakeups, cancellers and a /model switch are
  each session's own. A session that ends (/quit) leaves the list; the last one
  ending ends the loop, and ctrl-q ends them all (divergence
  `sessions-are-a-list`).
- **A shared session says so** (RFC-043). While anything is shared from
  this process, the footer's left ends `· sharing N · K watching`
  (`share/footer`): N the shares — this session's own, while its sidecar
  reports presence (`:session.share/presence`, over the channel's `:emit`),
  and the others this process started by reference or as a group, read
  from byj-share's registry (`share/from-here`, the entries whose `parent`
  is this process) off the render loop every `share-every-ms` (3 s), its own
  entry counted once — and K everyone watching them but the owner; or
  `· sharing N · paused` while this session's share is paused. N was this
  session's viewers alone, so two open shares nobody watched read
  `sharing · 0`, and a share started by reference never showed
  (`byj-gly.428`). A line says who joined or left the share
  (`share/changes`), and a note for people is a line, `✎ note from Kim
  (coach): …`. An injected turn's approval is the permission overlay
  relabelled `injected turn` — `Kim (coach) wants to run:`, the text, allow or
  refuse, refuse focused (`byj-1wv.11`) — through the renderer's `:approve` seam, which waits for an ask
  already up and takes its overlay down when the time runs out (an expiry,
  the sender told so). `/share` lists who watches and where; `/share
  pause|resume|kick USER` are said to the sidecar as `:session.share/control`,
  and `/share stop` ends the sidecar's task and the footer's mark.
- **The model is named on the footer's rule.** The rule between the input and
  the footer carries, right-aligned, the model the session is asking —
  `── claude-code/opus ──` — as the rule over the input carries the
  conversation's name. `main` hands the renderer the run's LM and the
  `:lm-box` `/model` writes (RFC-004), and a switch shows at once. `/model`'s
  completions offer nothing once the line names a model exactly, so Enter
  sends it rather than taking the completion it already is.
- **The model's next question is offered.** A turn's `:next-user-prompt`
  (on `:session.turn/end`) becomes the empty input line's placeholder, dim,
  as brainyard's `help_tips.clj` draws it — `↳ run the tests too  (→ to use)`,
  how to take it said beside it, the prompt cut to leave the suffix room
  (`suggestion-placeholder`). → in the empty line takes it. A new turn clears
  it, since the old suggestion is stale, and `:enable-input-suggestions false`
  offers nothing. A call the person made between turns offers its own
  (`:session/suggestion`, from `/init`'s agent$init, byj-gly.337.1). A resume offers the last one again, and draws each tool
  iteration's thought above its calls again, from the snapshot
  (`byj-gly.280.4`,
  `tui-test/a-resumed-session-shows-its-thoughts-and-its-suggestion`).
- **The footer turns through help tips.** When nothing more pressing is on —
  a flash, the picker, the menu, a find, a child's transcript, scrolling back —
  the footer's right says one of `help-tips`, the keys and commands this
  renderer has, and the loop moves on to the next every `tip-every-ms` (10 s).
  brainyard's `static-tips`, rewritten to name only what exists here;
  `tui-test/the-footer-turns-through-the-help-tips` checks every command a tip
  names is one the session has.
- **Input history.** Up walks back through the newest `max-history` (100)
  lines sent, Down forward, and past the newest is the line you were part way
  through — kept, not lost. While walking, the rule over the input says where
  you are (`── History 53/55`), and a recalled line starts with the caret at
  its start. The field lets go of Up on its first row and of Down on its last,
  which is what lets the container have them. brainyard binds Shift+arrows
  instead, since its plain ones are cursor motion.

  **The history holds what was in the field, not what was sent**: a paste is
  its `[paste #N +M lines]` marker, which still expands when it is sent again.
  It used to hold the expansion, which put the pasted lines back in the field,
  and the fold then made a new marker of them one number higher on every visit.
  The fold itself now waits for a paste the clock caught (`:pasting`), so a
  message typed over several lines with alt+enter is never folded either.

  **A resumed session's history comes back with it.** `:session/resumed`
  fills the history from the transcript's questions: oldest first, the newest
  `max-history` kept, repeats collapsed. Two things do not come back. A slash
  command is not in the kernel's transcript. And a paste returns as its text,
  since the markers the field folded pastes into belong to the process that
  folded them.
- **Completion, as a menu you steer.** The input line shows what a half-typed
  line could still become, `menu-rows` at a time, just above the input. The
  window scrolls with the mark (`scroll-menu`, `menu-window`), moving only as
  far as the mark needs, and a last line counts what is hidden above and below. While it is up, the arrows move the mark (the history when
  it is not), Enter takes the one marked instead of sending the line, and Esc
  closes it. The footer says so. A newline arriving inside a paste is still a
  line break, never a choice. Tab takes the marked one too, as a shell's does,
  and does nothing when no menu is up. It is always claimed: the only other
  focusable widget is the transcript, which ignores keys, so a Tab that moved
  the focus there once left every later key going nowhere and the screen looked
  hung. Since glimmer-tui#15 the view is offered Tab and Shift+Tab before the
  loop moves the focus; while a permission ask is up they are the ask's, moving
  between its choices.

  **What a line could become is the caller's to say.** The renderer completes
  the commands it was handed and nothing else; `:completions` is a fn from the
  line to `{:items [[text doc] …] :from index}`, and `main` composes three —
  commands, then a model for `/model`, then a path for an `@token`. Knowing
  which files exist is not a renderer's business (RFC-001), and the fn is
  called per keystroke rather than per frame, because a completer may walk a
  directory and a frame must not.
- **Tools complete too, from rows it was handed** (byj-gly.273.4). The session
  hands over `toolshelf/rows` through `:present :tool-rows` before it waits for
  a line, when the registry changed; `tool-completions` completes from them,
  merged after the caller's completer: `/gr` offers `/grep` after the commands,
  `:gr` `:grep`, and after `:grep ` the arguments not yet given — required
  first, each with its type and description — narrowed as a key is typed, and
  an enum argument's values after its key. brainyard stops completing at the
  first space (its tui-menu-design §4 TODO). Taking a completion on a `:` line
  offers what comes next at once; on any other line it is done.
- **Markdown.** A model writes markdown whether or not anyone asked it to, so
  an answer is read as markdown — while it streams as well as once it lands, so
  a reply does not change shape at the end. `markdown` splits it into entries:
  a fenced block is `:code` behind a `│` gutter, a heading is `:heading` with
  its `#`s gone, a list item keeps its bullet (`•`) or number and wraps under
  its own text, a quote is `:quote` behind a `▎` bar per level, `---` is a rule
  the width of the screen, and a pipe table is `:table`. An entry that has a
  margin carries it as `:lead` (the first row's) and `:hang` (the rest's). A
  table is laid out only when the width is known: columns padded and aligned as
  its rule says, the header bold, and a table wider than the screen wraps
  inside its columns, the widest cut down first.

  Inline, `**bold**`, `` `code` ``, `*emphasis*` (drawn underlined —
  glimmer-tui has no italic), `~~struck~~` (dim) and `[text](url)` are style
  rather than punctuation, and nest except inside code. A marker that does not
  flank a word is text: `2 * 3 * 4` is arithmetic and `snake_case` is a name.
  A link keeps its address, dim, because a terminal cannot follow it.
- **A caret where the caret belongs.** glimmer-tui starts the terminal with
  `leaveok(win, TRUE)`, so ncurses left the physical cursor wherever painting
  ended — the end of the PLACEHOLDER on an empty prompt. This namespace cleared
  the flag; since glimmer-tui v0.2.6 its screen clears it whenever a caret is
  shown (reported from here as `byj-dx1`), and the local fix is gone
  (byj-gly.271). Checked on a pty: typing `abc` leaves the cursor after it.
- **A prompt that does not look selected.** glimmer-tui marks a focused widget
  by painting it in reverse video, which is right for a button — focused
  sometimes, and the block of inverse says which one has the keys — and wrong
  for the one widget focused always, where it inverts the bottom row for the
  whole session. `:plain-entry` is the built-in entry painted through a screen
  that drops the attribute; the cursor says where the keys go, and the
  placeholder is left dim. The widget registry is open, so the rest of the
  spec — editing, the caret, the bindings — is the built-in's, unchanged.

### Rules the renderer follows

1. **One thread paints.** The kernel writes cells from its own fiber or thread;
   glimmer defers the re-render onto the loop thread through the backend's
   `:schedule` whenever `glimmer.backend/loop-running?` is set. Nothing else may
   touch a widget — timers already run on the loop thread by construction.
2. **The UI never blocks the loop thread.** The promises behind `:read-input`
   and `:permission-fn` are *delivered* by handlers and *waited on* by the
   kernel's thread. A handler that waited would freeze the terminal, including
   the keystroke that was going to answer it.
3. **The transcript is windowed, and it is one widget.** Layout walks every
   mounted node; painting is bounded by the viewport. Only a screenful of rows
   is computed, and they are painted by a single `:transcript` widget, a
   `put!` per styled run, rather than a label per row and an hbox per styled
   row. An entry's rows are laid out once per width (`rows-cache`, keyed by the
   `:id` `say!` gives it), and the transcript's component never reads `:tick`:
   the spinner turns without the transcript being laid out again (provenance
   P-19). Nothing on the way to a frame counts the rows of the whole window:
   a selection notices rows moving under it by the transcript's shape, and a
   scroll is held to the top by counting rows back only as far as it reaches
   (`scroll-offset`), so paging back lays the history out a page at a time
   (byj-gly.250). The scrollback that outlives the window belongs on disk (RFC-016),
   not in the widget tree.
4. **`ui/run` only after `usable-terminal?`.** `initscr` reports failure by
   calling `exit()`, so an unguarded start on a headless box is not an exception
   to catch, it is the process ending.
5. **Ctrl-C is a key, not a signal.** The loop puts the terminal in raw mode.
   `:quit-keys` is `#{"ctrl+q"}`; `ctrl+c` is bound on a container and calls
   `session/cancel-turn!`, replacing `main.clj`'s `with-sigint`. The container
   must always have a focusable descendant, because a key reaches ancestors only
   by bubbling up from the focused widget.
6. **Wrapping and markdown are ours.** A `:label` breaks on newlines only, and
   brainyard's renderer wraps model output and renders code blocks. The wrap
   goes through `glimmer-tui.text` (grapheme clusters, not characters) or into a
   widget of our own — the widget registry is open, and a consumer adds to it
   the same way the built-ins do.
7. **A command that only changes what you see never reaches the model.**
   `/queue`, `/left` and `/right` are answered in `submit!`, before the line
   could be read or queued; the session never hears of them.
8. **Every renderer behaviour is asserted headlessly.** `attach!` onto
   `screen/buffer-screen`, drive with `press!`/`click!`, assert on
   `screen/lines`. No test may need a tty, and `jolt test:byj` stays hermetic.

## Verifying it live

`dev/brainyard/dev/tui_verify.clj` runs a turn, a slash command and a quit
through the renderer and prints the frames:

```bash
jolt -A:dev -m brainyard.dev.tui-verify
clj-nrepl-eval -p 7899 '(brainyard.dev.tui-verify/report)'      # against a live image
```

It is the port's version of brainyard's `docs/tui/nrepl-verify.md`, which needs
two channels — nREPL to reload and inspect, `tmux send-keys` for input — because
its renderer paints the terminal it was given and nothing can read it back.
Here the nREPL is both: `attach!` points the backend at a grid, `press!` types
into it, `screen/lines` says what is on it.

What it checks that `brainyard.tui-test` cannot: the **real session loop**
drives the renderer. The tests call the seams directly, which proves the
renderer answers them; this runs `session/run` with a scripted model that calls
a tool and then answers in markdown, so the tree, the turn, tool dispatch, the
slash commands and the renderer are all live and wired as `main` wires them.

`live` runs the same drive against a real provider (`claude-code` on haiku by
default), which is the only way to see a model's own markdown, its own tool
choices and its own pauses. It spends quota: a few times, to confirm a fix,
never in a loop.

It has already earned its keep four times. The scripted run showed `$0.0000`
in the status bar on every mock turn — `cost/of` prices the mock at zero, and a
zero is *free* where an absent key is *unknown* (`cost/summary` makes the same
distinction) — and a one-line code block fenced by two rules, which is more
furniture than block. The live run showed the third: a model's inline emphasis
painted as literal asterisks (`byj-gly.101`). Fixing that showed the fourth —
the frame after a turn had no answer in it, because `:session.turn/end` is
emitted *before* the answer and the driver was waiting on the status bar. None
of them is visible in an assertion; all of them are obvious in a frame.

It also reports the runs the screen painted with a style, which is the one thing
a frame's text cannot say — `["file" {:bold true}] ["notes.txt" {:fg :green}]`
distinguishes an emphasis that was rendered from one that was dropped.

What a live haiku turn looks like through it, mid-turn and after:

```
│ ❯ read notes.txt and tell me in one sentence what it says
│
│ ⏺ read-file(path=notes.txt) · 2ms · 51 chars
│   ⎿  path: notes.txt · size: 5 · content: hello
│ ✻ thinking… (3.2s · react/think-act · ↓ 367 tokens)
│                                                   tokens 10621/367
│ ──────────────────────────────────────────────────────────────────
│ ❯
│ ──────────────────────────────────────────────────────────────────
│   turn 1 · 1 call                    ctrl-c cancel · ctrl-q quit · /help
```


## Measurements

`dev/brainyard/dev/tui_spike.clj`, on this machine (macOS, jolt 0.8.8,
glimmer-tui v0.2.3). Re-run it rather than quoting the numbers; run it *from a
terminal* to get §6, which is skipped without a tty.

### It loads, and the seams work

| probe | result |
|---|---|
| backend registered on require | `:tui` |
| `usable-terminal?` with no tty | `false` — reported, not fatal |
| a turn's worth of the boundary, headless | the kernel thread blocks on `:read-input`, is answered by typed keys, blocks on `:permission-fn`, is answered by the overlay, and finishes |
| glimmer-tui's own suite under this jolt | 100 tests, 331 assertions, green |

jolt before 0.7.24 exported the ncurses its own kernel links against, which shadowed
the backend's and left `initscr` failing on terminals that work everywhere else
(jolt#728). This repository's floor is already 0.8.3.

### A streamed reply repaints at the loop's rate, not the model's

500 `:on-chunk` writes with the loop running:

| | |
|---|---|
| cell writes | 500 |
| re-renders queued | **1** |
| cost of the writes | 0.75 ms |

A component's watcher holds a pending flag until its render runs
(`glimmer.core/make-rerender-watcher`), so chunks arriving between two ticks
collapse into one render. Streaming does not need throttling on our side.

### A frame costs the viewport, and a layout costs the transcript

One `:label` per line inside a `:scroll`, in-memory screen at 80x40:

| lines | layout ms | render ms | frame ms |
|---|---|---|---|
| 40 | 3.95 | 35.48 | 41.45 |
| 100 | 10.91 | 39.37 | 49.61 |
| 500 | 58.16 | 38.54 | 94.64 |
| 2000 | 229.14 | 36.76 | 290.30 |
| 5000 | 604.77 | 45.31 | 673.81 |

Painting is flat — a node clipped out of the viewport is cheap — and layout is
linear in *mounted* nodes whether or not they are visible. That is rule 3.

### On a real terminal, the cost is the toolkit, not the terminal

100x40 terminal, 39 labels (spike §6, under a pty):

| | |
|---|---|
| `tui/frame!` | **26.93 ms** |
| `render/render!` | 22.21 ms |
| `layout/layout` | 4.78 ms |
| `scr/put!` × 39 rows (the FFI) | 0.05 ms |
| `scr/present!` (doupdate) | 0.01 ms |

The FFI and ncurses are a rounding error; a repaint is glimmer-tui's own walk
over the visible widgets, about 0.5 ms each. So a full screen of text is a ~20 ms
repaint, the default 30 ms tick would spend most of a core redrawing during a
stream, and `:tick-ms` is a real tuning knob rather than a detail. Reported
upstream as `byj-3dw`; the renderer works within it either way.

### A paste and a person are an order of magnitude apart

Keystroke gaps under a pty, with the real loop
(`dev/brainyard/dev/paste_probe.clj`):

| | characters | the newline after them |
|---|---|---|
| pasted | 1–7 ms apart | **1–2 ms** |
| typed | 413 ms apart | **608 ms** |

`paste-gap-ms` is 40 — an order of magnitude above the paste and an order below
the typing. A person cannot press return 40 ms after a letter; a terminal
cannot help it. The same measurement is why the tests and the verification
harness pause before pressing return: a driver types faster than any terminal
pastes, so an Enter with no pause before it IS a pasted one.

### What the widget set does not do

| measured | result |
|---|---|
| a 139-character line on a 40-column screen | one row, clipped — no wrapping (rule 6) |
| pasting `"alpha\nbeta"` into an `:entry` | `:on-activate` fires once at the newline, the field holds `"alphabeta"` — bracketed paste is not decoded (`byj-gly.97`) |
| `(keys/decode 3)` | `{:type :ctrl :ch \c}` — raw mode, so ctrl-c is a key (rule 5) |
| ctrl-c with nothing focusable on screen | the container's `:on-key` never fires — keys bubble *up from the focus* (rule 5) |

## What this decision is not

It is **not** the end of the line renderer. `emit-to-terminal` stays as the
non-full-screen mode: brainyard keeps the same thing as `--inline`, and it is
what `jolt ask:byj`, a pipe, a CI log and any non-tty invocation get. Rule 4 makes
the choice automatic.

It is **not** a port of brainyard's Mode B — tmux side panes for the activity
and log streams, and a `display-popup` gate (`byj-gly.98`). Mode A is the whole
scope here.

It is **not** an adoption of glimmer beyond the terminal. `glimmer-gtk` exists
and is not a dependency; a desktop UI is not a goal, and `--web` remains ttyd
sharing a terminal (`byj-gly.22`, RFC-027).

## When to reopen this

1. **The repaint cost becomes the bottleneck in practice** and `byj-3dw` goes
   nowhere upstream. The backend contract is nine functions and a widget
   registry, so a faster backend is a replaceable part — but measure a real
   session first, because a session is mostly idle and repaints only when
   something changed.
2. **A UI the widget set cannot express.** Adding a widget is the normal
   answer (the registry is open); needing to replace layout or the input loop is
   not.
3. **glimmer-tui stops being maintained.** It is `jolt-lang`'s own, like jolt
   and the http client this repository already depends on, and both halves are
   small enough to vendor if it comes to that.

### What is said as the session closes

`close` and `drain-self-improve` say things as the session ends — the turn
limit reached, background tasks cancelled, self-improvement judges cut short.
Under the renderer they went into a transcript whose screen is cleared the
moment the loop gives the terminal back, so nobody read them. The session keeps
them in its final memory as `:closing-notices` (`session/closing-note!`), and
`main` prints them on the restored terminal before the summary line and the
resume hint (RFC-016). The plain front end prints them as they happen and does
not repeat them (`byj-gly.252`).

## Invariants

| invariant | enforced by |
|---|---|
| Enter on an agent$claude subagent's row opens its view, as any subagent's; → opens the Claude Code session it drives; the footer says what → does. | `tui-test/an-agent-claude-subagent-opens-its-view-on-enter-and-its-claude-code-session-on-right` |
| A running tool's lines are drawn under the call that printed them, the newest few, and give way to the results; a batch's other calls show none. | `tui-test/a-running-tool-shows-what-it-prints`, `agent-test/a-batch-tags-what-each-call-prints` |
| A workflow step is drawn as a call — in the turn and in a subagent's view — with its newest progress under it while it runs and what it kept in its place once it ends. | `tui-test/a-workflow-step-is-drawn-as-a-call-with-its-progress` |
| A child's transcript is rebuilt from its records — its question, its tool calls, its answer — and shown as a child's; a buffer it has is not rebuilt; with nothing recorded nothing is shown; the list then says of it what its record says. Enter on a child's row hands it to `open-child!` and, with nothing to say, leaves the list closed. | `tui-test/a-childs-transcript-is-rebuilt-from-its-record`, `tui-test/enter-on-a-child-row-opens-the-child` |
| A session's subagents are rows under it, depth first and indented, capped with the rest counted, each keeping its session; an agent$claude child names its Claude Code session and carries its delegation; children follow their session in the list, and a Claude Code session a child drives is in the Claude Code group too; a delegation that asks is counted as awaiting input once. | `tui-test/a-sessions-subagents-are-rows-under-it`, `tui-test/the-list-puts-children-under-their-session-and-a-delegated-claude-session-in-its-group-too` |
| A saved session's children come from its trajectory, one per instance with its first question and last answer; they nest by who dispatched them, newest first, an unknown owner at the top, a loop harmless; an open session's come from its buffers, a follow-up another turn; an agent$claude child carries its Claude Code session; a trajectory is read again only when it changes. | `children-test` (5 tests), `tui-test/an-open-sessions-children-are-listed-from-its-buffers` |
| What the session says as it closes is kept for the terminal the renderer gives back. | `session-test/a-session-at-its-turn-limit-says-so` |
| A turn crosses the boundary with no terminal: the kernel blocks on `read-input` and on the gate, and both are answered by keystrokes. | `tui-test/a-turn-crosses-the-boundary-with-no-terminal-at-all`, `tui-test/a-denied-gate-answers-the-turn-too` |
| A seam called after the loop is gone answers at once instead of parking — otherwise the session waits on a promise nobody can deliver. | `tui-test/the-end-of-input-is-how-a-session-closes` |
| A click on a path or URL opens it — a file at its line and column, a URL the wrap split whole, from either row — and a click on no link opens nothing; what a click would open is underlined. | `tui-test/a-path-or-a-url-under-a-click-opens`, `links-test/*` |
| A wrapped target is widened from its entry's own layout, never by joining rows. | `links-test/a-wrapped-target-is-recovered-from-its-entry-not-from-its-rows` |
| ncurses never starts from an entry with `rep` when it is older than the patch that stopped using `rep` past 255; otherwise the terminal's own entry is kept. | `tui-test/ncurses-starts-from-an-entry-whose-rep-it-gets-right` (the choice); the drawing itself only live, under Ghostty's terminfo |
| An SGR report reaches the reader as bytes whatever the terminal's kmous — `\E[<` included — and none is typed into the input. | nothing automated (it needs a tty and ncurses); `dev/brainyard/dev/mouse_probe.clj`, run under Ghostty's terminfo and the system's |
| `src` rebinds no var; one that did would need the binary's `--no-direct-link`, or the binary would not run the rebinding the suite tested. | `tui-test/the-binary-runs-what-is-rebound` |
| A renderer is given the terminal's size (`stty size`) before its session lays anything out — `run!`, and every session `run-many!` starts — so a resumed transcript is laid out once at the real width, not at 80×24 and reflowed, which looked like another session flashing past. | `tui-test/the-terminal-size-is-read-before-anything-is-laid-out` (the parse; the read itself needs a tty, checked live in tmux) |
| The auto-mode classifier's verdict is drawn under the call it decided, a second call of the same tool getting the second verdict: blocked red, allowed dim. | `tui-test/auto-modes-verdict-is-said-under-its-call` |
| glimmer-tui's reader gives this renderer a paste whole (UTF-8, CR as newline) or marked `:cut?`, a UTF-8 key as one character, Tab and Shift+Tab as the keys its `:on-key` takes, and SGR reports it acts on; a burst of wheel notches is one scroll. With a permission ask up, Tab is the ask's. | `tui-test/sequences-the-terminal-sends-are-decoded-upstream`, `tui-test/a-burst-of-mouse-reports-is-acted-on-once`, `tui-test/a-denied-gate-answers-the-turn-too` |
| ctrl-c cancels the turn and is never a signal; ctrl-q is the loop's. | `tui-test/ctrl-c-cancels-the-turn-and-is-never-a-signal` |
| Only what fits on the screen is mounted. | `tui-test/the-transcript-shows-its-tail-and-scrolls-back` (asserts the widget count, not just the text) |
| A `:problem` is shown whether or not anyone asked; the turn narrating itself needs `-v`. | `tui-test/a-problem-is-shown-whether-or-not-anyone-asked-and-a-trace-is-not` |
| Wrapping is the renderer's, and it breaks on grapheme clusters. | `tui-test/wrapping-is-ours-because-a-label-breaks-only-on-newlines` |
| An iteration's tool calls are shown as it makes them, without `-v`, and frozen into the transcript when the next starts or the answer lands — above the answer, never below it. | `tui-test/an-iteration-says-what-it-is-doing-without--v`, `tui-test/a-long-result-is-counted-and-the-answer-lands-below-it` |
| An iteration's thought is said once, dim, behind a bullet, above its calls, and stays when the block freezes; an answering iteration's is not shown; a subagent's goes to its own view; the next iteration's reasoning streams afresh. | `tui-test/an-iterations-thought-stays-above-its-calls` |
| A model call, a task and a subagent each say they are in flight, with an elapsed, and stop saying so when they end. | `tui-test/what-is-in-flight-says-so-while-it-is` |
| A line typed while a turn runs is queued, and the next prompt takes it without waiting for a person. | `tui-test/a-line-typed-while-a-turn-runs-is-queued-not-swallowed` |
| A pasted line break stays in the field; only a pressed return sends the line. | `tui-test/a-pasted-line-break-is-not-a-return` |
| A half-typed command shows what it could become, the arrows steer the menu, Enter takes what is marked without sending the line, and Esc closes it. | `tui-test/a-slash-command-completes-itself` |
| A tool's name completes after `/` (after the commands) and `:`, and after `:<tool> ` its arguments not yet given, required first, then an enum's values; taking one on a `:` line offers the next. | `tui-test/a-tool-and-its-arguments-are-completed`, `session-test/the-renderer-is-handed-the-tools-to-complete` |
| What else a line could become comes from the caller, per keystroke, and replaces the token it completes. | `tui-test/what-a-line-could-become-is-the-callers-to-say`, `main-test/completions-name-what-exists` |
| A resumed session's questions are the input history again. | `tui-test/a-resumed-session-is-on-the-screen-again` |
| The history keeps the newest 100, the rule over the input names the entry being shown, a recalled paste is the same marker on every visit, and a typed message of several lines is never folded. | `tui-test/walking-the-history`, `tui-test/a-recalled-paste-keeps-its-marker` |
| Up and Down walk the history; the line in progress survives the walk and typing leaves it. | `tui-test/up-walks-back-through-what-you-typed` |
| A paste longer than a few lines folds to a marker when it settles, and submits as the text it stands for. | `tui-test/a-long-paste-folds-to-a-marker` |
| Alt+Enter breaks a line without sending it. | `tui-test/alt-enter-is-a-break-you-asked-for` |
| The input line is not painted as a selection; its hint is muted, and the caret sits at the field whether or not the hint is showing. | `tui-test/the-prompt-is-not-a-selection` — for the renderer's half. What ncurses does with the caret is the terminal's half and no headless test can see it: the frame has to be read off a pty (the `leaveok` fix was found and checked that way) |
| An answer is rendered as the markdown it is, blocks and inline runs alike, and the emphasis reaches the screen AS emphasis — while it streams too. | `tui-test/an-answer-is-read-as-the-markdown-it-is` (asserts the painted style, not only the text), `tui-test/inline-markup-is-style-rather-than-punctuation`, `tui-test/emphasis-links-and-the-asterisks-that-are-arithmetic`, `tui-test/blocks-keep-their-own-margins`, `tui-test/a-reply-is-markdown-while-it-streams` |
| A table is laid out for the screen it is on and loses no text when it is wider than that; a wrapped row never overruns the margin. | `tui-test/a-pipe-table-is-laid-out-as-a-table`, `tui-test/a-long-word-after-others-starts-its-own-row` |
| A line with no markup stays one run. | `tui-test/inline-markup-is-style-rather-than-punctuation` — `spans` returns a single run, which is one `put!` when the transcript is painted |
| The transcript is one widget whatever its rows carry; the spinner's tick never lays it out again; an entry is laid out once per width. | `tui-test/paging-costs-the-screen-not-the-transcript`, `tui-test/an-entry-is-laid-out-once-per-width` |
| The bottom of the screen is, from the top: what is in flight, the usage right-aligned, the menu when there is one, a rule, the input, a rule, and the footer — so the input line never moves when the menu opens. | `tui-test/the-input-sits-between-two-rules-under-what-is-running` |
| A finished turn's answer is followed by how long it took and when it ended, and a recap follows that. | `tui-test/a-finished-turn-says-how-long-and-a-long-one-recaps` |
| A resumed session's transcript is shown again, each entry as it was first shown, with the resume line under it. | `tui-test/a-resumed-session-is-on-the-screen-again` |
| A resume replays only the window, the tail of what a whole replay would show, in one change; its first frame lays out only what reaches the screen, and a page back lays out a page more; the top is still the top. | `tui-test/a-long-resumed-session-costs-the-screen-not-the-history` |
| A child's work goes to its own buffer, a click on its row or its call shows it, the focus stays on the input, and Esc returns to the root. | `tui-test/a-subagent-has-a-transcript-of-its-own` |
| A long result folds behind a count; a click opens and closes it in place, and ctrl-o hands its whole text to the editor. | `tui-test/a-long-result-folds-and-opens-in-an-editor` |
| ctrl+g releases every call the turn waits on and says what moved; with nothing waiting it does nothing; the busy line offers it only for a call waited on past `background-hint-ms`. | `tui-test/ctrl-g-moves-the-calls-a-turn-waits-on-to-the-background` |
| ctrl-f finds the newest match and marks it, Enter and the arrows step between matches, and Esc restores the draft and keeps the view. | `tui-test/ctrl-f-finds-a-line-in-the-transcript` |
| The widths the renderer assumes for the glyphs a model writes are the ones tmux 3.6 measured. | `tui-test/the-glyph-widths-the-renderer-assumes` |
| How much is shown follows the run's `:display-box` as it changes: traces under verbose, payloads under debug, and no tool block, live or frozen, under quiet. | `tui-test/the-display-level-is-read-as-it-changes` |
| `/queue` is answered by the renderer at once, never queued itself, and lists and cancels what is waiting. | `tui-test/queue-is-the-renderers-own-command` |
| `/left` and `/right` change only the screen: never queued, never read by the session, never said in the transcript. | `tui-test/a-rail-command-never-reaches-the-session` |
| With both rails closed the screen is what it was before rails, frame for frame, and the centre is the whole width. | `tui-test/a-closed-rail-costs-nothing`, `tui-frame-test/the-centre-keeps-its-columns` |
| A rail beside the centre never leaves it under 60 columns; with no room it draws over the centre, which keeps its width. | `tui-frame-test/the-centre-keeps-its-columns`, `tui-test/a-rail-with-no-room-draws-over-the-centre` |
| A rail's key opens it with the keys, takes them, and closes it; Esc gives them back to the input, which holds them meanwhile. | `tui-test/a-rail-opens-takes-the-keys-and-gives-them-back` |
| While a rail has the keys the terminal cursor is hidden at the input's caret, never left where painting ended. | `tui-test/a-hidden-cursor-waits-at-the-caret` |
| ←/→ move a focused rail's inner edge, each rail its own way, between 16 columns and what leaves the centre its 60. | `tui-test/an-arrow-moves-a-rails-inner-edge`, `tui-frame-test/a-rail-is-resized-within-bounds` |
| A left press on a rail's separator takes hold of its inner edge: the drag moves it to the pointer within the same bounds, the release lets go without a click, and a press anywhere else still selects. | `tui-test/a-rails-separator-is-dragged-with-the-mouse`, `tui-frame-test/a-rails-separator-is-found-and-dragged` |
| A click on a rail's item marks it and acts as Enter does, a click on a view's name in its header shows that view, the keys stay where they were, and a click on the centre is the centre's. | `tui-test/a-click-on-a-rail-marks-and-acts-or-shows-a-view`, `tui-frame-test/a-click-finds-the-rail-and-what-it-is-on` |
| The wheel over a rail moves its window within its items and never its mark; over the centre it scrolls the transcript. | `tui-test/the-wheel-over-a-rail-scrolls-the-rail`, `tui-frame-test/the-wheel-moves-a-rails-window-and-not-its-mark` |
| `terminal$*` start, type into, read and stop this project's programs alone, refusing one the catalogue does not name or one in the wrong state; `terminal$send` asks by default and reading does not; a subagent and `:terminal-tools? false` have none. | `terminal-test/the-agent-works-a-program-through-its-tools`, `terminal-test/typing-into-a-program-asks-first` |
| Terminal starts a catalogue program in a tmux session named for the project, a later reader finds it again with its start, Enter opens it by attaching, ctrl+x twice stops it; the catalogue's layers replace by name. | `terminal-test/programs-outlive-the-view-that-started-them`, `terminal-test/the-catalogue-is-data-in-layers`, `terminal-test/the-rows-say-what-runs-and-what-can` |
| After the agent's tools, each running program with `:after-tools` runs it, in the project directory, and a failing or stuck one is given up on and named; a running editor with `:context` says what the person looks at, a long selection clipped, and what is no JSON object is an error; `${VAR_STRING}` is a string literal. | `terminal-test/after-the-tools-a-running-program-hears-of-it`, `terminal-test/a-running-editor-says-what-the-person-is-looking-at`, `terminal-test/a-var-may-be-had-as-a-string-literal` |
| Inside tmux, Enter opens a program in a pane split beside byj's and attached from it, the terminal kept; Enter again selects that pane; `:open :attach`, or a split that fails, hands the terminal over; the pane closes when the program stops. | `terminal-test/inside-tmux-a-program-opens-beside-byj` |
| A clicked file opens in a running program whose entry says how (`:open-file`), in the project directory, without the terminal, and says so or why not; with none running it is the caller's; emacs is a frame on the project's own daemon. | `terminal-test/a-file-opens-in-a-running-program-that-says-how`, `terminal-test/emacs-is-a-frame-on-the-projects-own-daemon` |
| The footer is never wider than the centre, its hbox's spacing counted; a hint takes at most half a rail's row. | `tui-test/a-footer-never-pushes-the-rail-by-its-spacing`, `tui-frame-test/a-hint-never-takes-the-row` |
| Activity draws a turn's events as its rows under the turn's heading — a call ◐ until its result, then ✓ or ✗ with its time, a subagent's calls indented — keeps the newest 500, folds older turns, and Enter on a call shows it. | `activity-test/a-turns-work-is-its-rows`, `activity-test/older-turns-fold-and-headings-are-sections`, `activity-test/the-feed-keeps-its-newest-rows`, `activity-test/the-view-follows-the-newest-and-shows-a-call` |
| A rail's mark follows its view's `:mark` as sections read, until a key moves it; Enter acts on the row it is drawn on. | `tui-test/a-rails-mark-stays-on-its-session-while-sections-read`, `tui-frame-test/a-following-mark-is-resolved-from-the-items-as-they-are` |
| Tab and shift+Tab move a focused rail's mark between the section headings of the view shown, wrapping, never into another view; a view's letter shows it and it is told; the header draws each letter underlined. | `tui-test/tab-moves-between-sections-and-a-letter-between-views`, `tui-frame-test/tab-stops-at-each-section-of-the-view-shown`, `tui-frame-test/each-view-has-a-letter` |
| A modified key glimmer decodes — shift+→, ctrl+←, a run it has no name for — types nothing into the input or the find field, nor moves the caret; the same characters typed are text. | `tui-test/an-escape-run-glimmer-did-not-know-types-nothing` |
| A key in ncurses' code range that glimmer does not name types nothing into a field; a character typed in that range still does. | `tui-test/a-key-ncurses-has-a-code-for-types-nothing` |
| A rail drawn over the centre has the keys, however it was opened, and closes as it gives them back. | `tui-test/a-rail-with-no-room-draws-over-the-centre`, `tui-test/the-left-rail-is-the-windows-sessions` |
| The footer is never wider than the centre: a tip is cut first, then the left's hints and mode give way to what is in front of you. | `tui-test/a-long-footer-never-pushes-a-rail-off-the-screen` |
| The left rail is the window's sessions in the list's groups, marked on its own; Enter on another switches to it and opens its rail there. | `tui-test/the-left-rail-is-the-windows-sessions` |
| The rails come back as a restart left them — each one's open?, view and width, and every view's folds but Activity's — put back once the screen's size is known; a rail that would be drawn over the centre comes back closed, a view a side no longer has is its first, and an unreadable file is no state. A move of the mark is not kept. | `tui-frame-test/the-rails-are-kept-as-they-were-left`, `tui-frame-test/a-stacks-folds-are-its-sections-titles`, `tui-test/the-rails-come-back-as-a-restart-left-them`, `rail-state-test/the-frame-and-the-folds-outlast-the-store`, `rail-state-test/a-file-that-cannot-be-read-is-no-state`, `main-test/a-sessions-rails-are-the-runs-kept-ones` |
| Stacked views are one list of sections under their headings; a row does what its own view does, a heading folds or unfolds its section (Enter or a click), all open at first; the mark starts where the first view with a `:mark` puts it. | `tui-frame-test/views-stack-as-sections`, `tui-test/a-stacks-heading-folds-its-section` |
| The Project section lists what the project defines, by kind — not the built-in agents nor the machine's skills or servers — folded, empty kinds left out; Enter opens an item and `@` mentions it from the project. | `project-overview-test/a-project-is-what-it-defines-and-nothing-else`, `project-overview-test/kinds-are-folded-and-an-empty-one-is-not-shown`, `project-overview-test/the-view-folds-opens-and-mentions` |
| The right rail reads files, changes and commits from git, a walk where there is no repository; a newer read cancels an older one, and a read cut short is not a failure. | `project-views-test/a-repository-is-read-and-a-directory-is-walked`, `project-views-test/the-views-read-when-shown-and-say-why-not`, `project-views-test/a-read-cut-short-by-the-next-is-not-a-failure` |
| The Issues section reads the tracker the project directory itself holds — beads, else GitHub, else none, when it says beads are not available and /init sets them up, and reads them once they are made — never a parent's; epics fold with counts, and each one's issues are a graph in lanes, an issue above its blockers, a blocked one marked. | `issues-test/the-tracker-is-what-the-project-directory-holds`, `issues-test/an-epics-issues-are-a-graph-in-lanes`, `issues-test/epics-are-folded-headings`, `issues-test/the-view-reads-folds-and-mentions`, `issues-test/bd-finds-beads-as-bd-does` |
| The Memory section lists each source's memories — byj's store, then Claude Code's for this project — last in the left rail, flat for one source and folded per source for several; Enter shows one whole, `@` mentions its key or file, a failing source says why on one line, a bd store with no beads to find says /init sets them up and runs no bd, and a test that names no home never reads the person's `~/.claude`. | `memory-rail-test/the-section-lists-shows-and-mentions`, `memory-rail-test/the-section-says-what-it-could-not-read`, `memory-rail-test/a-bd-store-with-no-beads-says-init-sets-them-up`, `memory-rail-test/no-memory-no-section`, `memory-rail-test/a-claude-code-memory-file-is-read-as-its-front-matter-says`, `memory-rail-test/each-source-is-a-folded-group`, `memory-rail-test/a-test-never-reads-the-developers-claude-memory` |
| The Self-improvement section's toggles send the `/config` a person would type, and flip their rows at once; its proposals are every pending one, Enter showing a draft with how to decide it and sending nothing, `@` its id. | `self-improve-rail-test/the-toggles-say-whats-on-and-the-proposals-what-waits`, `self-improve-rail-test/a-toggle-is-the-config-a-person-would-type` |
| Source shows changes, commits and the graph of every branch as sections whose headings fold; a graph commit acts as a commit does; a section's name finds the view. | `project-views-test/source-holds-the-changes-the-commits-and-the-graph` |
| The graph is a map of the branches — names, merges (with or without a name on each side), forks — drawn one row a commit in coloured lanes; a row in pieces is fitted to the rail's width, and a marked row is one style. | `project-views-test/the-map-keeps-what-shows-the-branches`, `project-views-test/the-graph-is-laid-out-in-lanes`, `tui-frame-test/a-row-may-come-in-pieces` |
| Enter on a file opens it by its whole path; on a change or a commit its diff is shown in the transcript, folded, and never reaches the session; `@` puts the row's path into the input and gives the keys back. | `project-views-test/a-row-opens-its-file-or-shows-its-diff`, `tui-test/a-shown-diff-is-a-folded-block-the-model-never-sees`, `tui-test/at-in-a-rail-mentions-the-marked-row` |
| A menu longer than its window scrolls with the mark, which is always on screen, and counts what is hidden above and below. | `tui-test/a-long-menu-scrolls-with-its-mark` |
| A typed line has an empty row above it, a dim mark, and a band the full width of every row it takes. | `tui-test/a-typed-line-stands-on-its-own-band` |
| A message of several lines keeps them on the band; a long one shows `paste-collapse-lines` and folds the rest, openable with a click. | `tui-test/a-message-of-many-lines-keeps-them-and-folds-a-long-one` |
| The wheel scrolls the transcript both ways, a left press clicks, a bracketed paste arrives whole and UTF-8 decoded, a typed non-ASCII character is one key, and what a click would do is underlined. | `tui-test/sequences-the-terminal-sends-are-decoded-upstream`, `tui-test/a-bracketed-paste-goes-into-the-line-whole`, `tui-test/a-mouse-report-clicks-and-scrolls`, `tui-test/the-wheel-scrolls-the-transcript`, `tui-test/what-a-click-would-do-is-underlined` |
| The mouse and the wheel go to the view on screen: a view points them at itself as it renders — not to whichever session was built last (byj-gly.207). The session's screen and the agent page (the session list) share one button handler, `pointer-mouse`: a drag selects and the release copies; on the agent page a click marks a row and a click on the marked row opens it as Enter does. | `tui-test/the-wheel-goes-to-the-session-on-screen`, `tui-test/the-agent-pages-mouse-marks-and-opens-a-row-and-copies-a-drag` |
| A direct call is drawn as a model's call is: the call line and its result under it. | `tui-test/a-direct-call-is-drawn-as-a-models-call-is` |
| `/tools` lists every tool with the marked one's spec beside it (wide) or under it (narrow); a filter finds a name before a description; enter drafts `/<tool> `, tab `:<tool> :<arg> `. | `tui-test/tools-are-browsed-with-each-ones-spec-beside-it` |
| A paste goes where typing would: at the caret, through the field's own edit (leaving a history walk), into an open picker's filter or ctrl-f's query on one line, and never dropped by the picker (byj-gly.209, .210). | `tui-test/a-paste-goes-where-typing-would` |
| A bracketed paste waits `paste-byte-ms` between bytes; one cut short anyway has its carriage returns turned and its rest is not auto-sent (byj-gly.212). | `tui-test/sequences-the-terminal-sends-are-decoded-upstream`, `tui-test/the-rest-of-a-cut-paste-is-not-sent` |
| The editor gets the terminal with every mode of ours off, and they come back after (byj-gly.208). | `tui-test/the-editor-gets-the-terminal-without-our-modes` |
| A registry search in the /mcp picker always ends — with its hits, or with why there are none (byj-gly.211). | `tui-test/a-registry-search-always-ends` |
| The input holds several lines, edited in place: rows wrap by cells, the arrows move between rows and hand the key to the history or the menu at the edges, and the field grows to its limit and scrolls past it while the transcript gives up the rows. | `tui-test/the-input-is-a-field-of-several-lines`, `tui-test/a-long-message-scrolls-inside-the-field` |
| The wheel and pgup stop at the transcript's first row, and a burst of notches is one scroll that goes further per notch. | `tui-test/scrolling-stops-at-the-top-and-a-spin-covers-ground` |
| A drag selects transcript cells, drawn reversed; the release copies what they show and the footer says how; a press and release without a drag is a click. | `tui-test/a-drag-selects-and-copies`, `clipboard-test/describe-says-copied-only-when-it-knows` |
| The OSC 52 size limit refuses only an OSC 52 copy; a tmux that fills only its own buffer does not say "copied". | `clipboard-test/a-copy-that-cannot-happen-says-why` |
| A burst Enter submits by what the burst added, so an earlier alt+enter break does not stop it; alt+enter breaks at the caret. | `tui-test/a-burst-is-judged-by-what-it-added` |
| A selection is dropped when the rows under it change (a row streams in, the view switches); a find re-reads its hits before stepping and never scrolls past the top. | `tui-test/a-selection-and-a-find-follow-the-rows` |
| A resumed parked turn sends without touching the draft or Up-history. | `tui-test/a-resumed-turn-leaves-the-draft-alone` |
| An exception from a key handler or a timer thunk is an :error line in the session, said once per distinct fault, and the loop goes on; with no renderer running it is thrown as before. | `tui-test/a-fault-in-a-handler-is-a-line-not-the-end` |
| A view that throws while it renders is the same: an :error line, once, and the loop goes on — and a re-render queued behind the broken one still runs. A paint that throws marks its frame painted, so it is retried on the next change rather than every tick. | `tui-test/a-view-that-throws-is-a-line-not-the-end`; on a real terminal, `dev/brainyard/dev/render_fault_probe.clj` |
| A finished turn's next-user-prompt is the empty input's placeholder, → takes it, and the next turn clears it. | `tui-test/the-models-next-question-is-offered-in-the-input` |
| The conversation's name from the last recap, or the snapshot, is right-aligned on the input's rule beside the history's place. | `tui-test/the-conversation-is-named-on-the-inputs-rule`, `session-test/a-conversation-title-is-a-short-name` |
| A one-line JSON or EDN map or vector result is laid out — a map as `name: value`, scalars before collections before text, text as its own lines; one cut short is broken by its punctuation; prose and a multi-line result are left as they came. | `tui-test/a-one-line-map-result-is-laid-out` |
| A finished call says how long it took and how much it returned, dimmed; a multi-line argument is `key=↓` with its first lines and a fold under the call, in the code colour. | `tui-test/a-call-says-its-time-and-size-and-shows-its-script` |
| The footer's rule names the session's model, and a `/model` switch shows at once; with no model known it is a plain rule. | `tui-test/the-model-is-named-on-the-footers-rule` |
| The footer turns through the help tips, and every command a tip names exists; a suggestion is offered as `↳ … (→ to use)`, its prompt cut before its suffix. | `tui-test/the-footer-turns-through-the-help-tips`, `tui-test/the-models-next-question-is-offered-in-the-input` |
| Sessions stand where their renderers say, are named by recap then first question, and are listed needs-input first; the list is grouped, marks a row, and Enter opens it; ← on an empty line opens the list and ← with text moves the caret. | `tui-test/several-sessions-are-a-list`, `tui-test/left-on-an-empty-line-opens-the-list` |
| Saved sessions come last under their own heading, and Enter on one resumes it. | `tui-test/the-list-offers-saved-sessions-to-resume` |
| Tab takes the marked completion and never moves the focus off the input; with no menu it does nothing. | `tui-test/tab-takes-the-completion-and-keeps-the-input` |
| `/config` opens an editor grouped by family, filtered by what is typed; Enter flips a switch quietly and keeps it open, puts another setting in the line, and refuses an unwritable one; new rows land in the open editor with the mark kept; Esc gives the draft back. `/model` opens on the model in use and switches with the typed line. | `tui-test/config-opens-an-editor-grouped-by-family`, `tui-test/model-opens-a-picker-on-the-one-in-use` |
| `/memory` lists the memories with the query as its filter, Enter sends `/memory edit KEY` quietly and keeps the picker, ctrl-x asks y/N in the footer and only y sends `/memory delete KEY` — the answer never typed into the filter; new rows land in the open picker. | `tui-test/memory-opens-a-picker-that-edits-and-asks-before-it-deletes` |
| A permission ask writes `:attention-signal`'s sequence once per ask, not again while it waits, and nothing when off; a title's control characters cannot end an OSC 9, and under tmux it is wrapped to pass through. | `tui-test/a-permission-ask-rings-the-terminal-once` |
| A program handed the terminal (`hand-over!`: `/memory edit`, `/login`, a Claude Code session) runs on the render loop's thread, which neither paints nor reads keys until it exits; a caller on another thread posts it there and waits (provenance P-34). | **nothing automated** — a headless test has no loop to stop; checked live in tmux |
| A line that is a command exactly gets no completion menu, the tools' included, so Enter runs it (provenance P-34). | `tui-test/a-tool-and-its-arguments-are-completed` |
| Token counts carry K/M units, and the usage row adds the last call's in/out once there is one. | `tui-test/token-counts-carry-their-unit`, `tui-test/the-input-sits-between-two-rules-under-what-is-running` |
| A turn's cost accumulates in the usage row above the input. | `tui-test/the-status-bar-carries-what-a-turn-cost` |
| A plain multi-line field takes a bracketed paste and ctrl+v at the caret, CJK and all, and its row stays as wide as the box; a field with `:pass-paste?` leaves them to its container. | nothing: the session's prompt, the one field drawn now, passes its pastes on (`:pass-paste?`); the plain path's one user, a report modal, left byj (`byj-ahf.26`) |
| The footer shows the permission mode in force, from shift+tab or from an edited config, and shift+tab steps the gates' box; the loop's own back-tab does not. | `tui-test/the-footer-shows-the-permission-mode-and-shift-tab-cycles-it` |
| An ask in the overlay — a permission or an injected turn — takes its own letters as keys (y, n, and the permission's a, r, d), and a letter never reaches the input. | `tui-test/a-permission-ask-takes-its-letters`, `tui-test/a-shared-session-shows-who-watches-and-asks-before-an-injected-turn` |
| A shared session's footer counts the shares from this process and, apart, who watches, and says paused; a share started by reference counts with no presence of the session's; a join, a leave and a note are lines; an injected turn's overlay has refuse focused (Enter refuses) and y allows; one nobody answers in time expires and takes the overlay down; `/share stop` clears the mark. | `tui-test/a-shared-session-shows-who-watches-and-asks-before-an-injected-turn`, `share-test/presence-is-shown-and-share-commands-reach-the-sidecar` |
| Only the loop thread touches a widget; kernel-side writes reach it through `:schedule`. | **nothing automated** — glimmer sets `loop-running?` and defers, so a breach is a race rather than an error. Headless tests render synchronously and cannot see it |
| A seam's handler never blocks; only the kernel's thread waits on the promise. | **nothing automated** — a blocking handler deadlocks the terminal, which a headless test cannot see |
| The full-screen renderer starts only on a usable terminal. | `main` checks `tui/usable-terminal?` before `run!` and falls back to the line renderer; **nothing automated**, because the failure is a process exit |
| Every renderer behaviour is asserted with no tty. | `jolt test:byj` is hermetic; `brainyard.tui-test` mounts, types, clicks and asserts on painted text |
| The toolkit loads, registers, and can answer a blocking permission call. | `dev/brainyard/dev/tui_spike.clj` (a spike, not a test — it times frames and wants a terminal for §6) |

## What requiring it costs the process

`glimmer.ratom` rebinds `clojure.core/deref`, `reset!` and `swap!` with
`alter-var-root`, so that `@` can track a reactive cell while a component
renders. Its deref has one arity, so `(deref ref timeout-ms timeout-val)` — how
every blocking wait here gives up (RFC-009) — stopped existing the moment
`brainyard.main` required `brainyard.tui`: 85 assertions and 69 tests failed in
namespaces that have never heard of the renderer (provenance P-18).

`brainyard.tui` put the arity back at load; glimmer v0.1.3 forwards both arities
itself (reported from here as `byj-mal`), and the local restoration is gone
(byj-gly.271). The general point outlives it: **a dependency that rewrites
`clojure.core` reaches the whole process**, and the only thing that caught this
was a suite that runs everything in one image — still the enforcer.

**A rebinding needs a binary built `--no-direct-link`** (provenance P-24), and
none is left: the last, glimmer-tui's `read-event!`, went with byj-gly.272. The
binary keeps the flag for another reason, measured on jolt 0.8.15 then: it is
the smaller and faster build — 67 MB against 83 MB direct-linked, a mock `ask`
0.36 s against 0.40 s, `--version` 0.26 s against 0.29 s. The renderer worked by `alter-var-root` on
vars it does not own — `read-event!`, and until glimmer-tui v0.2.8 and glimmer
v0.1.3 also `press!`,
`after!`, `every!`, the private `repaint!`, `glimmer.backend/schedule`
(byj-gly.266), `scroll!` and `clojure.core/deref` (byj-gly.271) — and a release `jolt build`
direct-links calls between defs, so code compiled against those vars never sees
the new root. `jolt run` and the suite never direct-link, so everything passed
while the shipped binary ran none of it: the mouse decoder was glimmer-tui's own
(v0.2.3 does not decode SGR), and every click was typed into the input as
`<0;91;35M…`. `tui-test/the-binary-runs-what-is-rebound` fails if a rebinding
comes back while the task lacks the flag.

## Known gaps



- The live block does not redraw in place: it grows, and freezes into the
  transcript as lines. brainyard updates a sticky block and freezes it
  (`update-live-block!` / `freeze-live-block!`). What is in flight IS redrawn
  in place (it is a component over a cell, repainted on the tick), so the gap
  left is the iteration block's history rather than the ticking.
- No todo state in a block: brainyard surfaces the checklist as an iteration
  renders; here `/todo` is how you look.
- Completion covers the session's commands, not tool or agent names, which
  brainyard's `autocomplete.clj` also offers.
- Cost appears only where the provider is priced: `cost/of` returns nothing for
  a model no table names, and the mock provider is one.
- A repaint is ~0.5 ms per visible widget — `byj-3dw` (upstream).
- `clojure.core/deref`'s blocking arity is restored locally — `byj-mal`
  (upstream).
- No tmux Mode B — `byj-gly.98`.
- Outside tmux, Enter on a Terminal program still hands it the whole
  terminal; only a byj run inside tmux keeps the session beside it
  (byj-gly.452). Drawing the program's screen inside the TUI itself is
  `byj-gly.454`.
