# byj

A coding agent whose loop is data. byj is
[brainyard](https://github.com/grumatic/brainyard)'s agent runtime ported to
[jolt](https://github.com/jolt-lang/jolt) — Clojure on Chez Scheme, no JVM —
following the approach of [samizdat](https://github.com/yogthos/samizdat):
**the loop is data**. The behaviour tree it runs lives in
`resources/brainyard/loops/react.edn`, its leaves name functions by keyword,
and the tree is interpreted at run time — swap it with `--loop file.edn`, no
rebuild. Every word the model reads is a resource too.

- **[Install](#installing)** with Homebrew, or build from source.
- **[Use](#usage)** it in a terminal (`byj`), for one question
  (`byj --prompt`), from an editor over ACP (`byj acp`), or from your own code
  with the Agent SDK.
- **[Contribute](CONTRIBUTING.md):** [AGENTS.md](AGENTS.md) has the rules a
  change keeps, for people and coding agents alike.

## Installing

byj is released for macOS (Apple silicon and Intel) and Linux (x86-64), and
installed with Homebrew from the public tap
[grumatic/homebrew-tap](https://github.com/grumatic/homebrew-tap), with no
GitHub account or token (RFC-029 "Homebrew"):

```bash
brew tap grumatic/tap
brew install grumatic/tap/byj
```

`byj update` brings it to the latest release the way it was installed:
`brew upgrade` for a Homebrew install, a checked swap for a downloaded
binary, the tag checked out for a checkout. `byj doctor` lists every copy on
`PATH`. From a checkout, `jolt build:byj && jolt install:byj` puts byj in
`~/.local/bin`.

## Usage

```bash
byj                                      # a session in the full-screen terminal view (byj run)
byj "explain deps.edn"                   # a session whose first turn is that
byj --prompt "How many .clj files are under src/?"   # one answer, then exit (byj ask "…")
byj run -p claude-code -m sonnet -C /some/project -v
printf 'q1\nq2\n' | byj run -p mock      # piped input works too
```

- With no `-p`, a run uses `:default-lm` from the config (`provider/model`),
  and without one `claude-code/sonnet`, which drives your Claude Code sign-in
  and needs no API key of its own. An API key in the environment does not
  choose the model by being there. The providers are openai, anthropic,
  claude-code, codex, freellm, openrouter, ollama and mock (RFC-015).
- Credentials come from the environment first, then the nearest `.env`, then
  `~/.brainyard/.env`; `.env.example` lists them.
- `-v` traces reasoning, tool calls and results to stderr. `--trace-prompts`
  also dumps the full prompts. `--json` prints the result map.
- `byj run` in a terminal is a full screen (RFC-028); `/help` lists its
  slash commands, among them `/init`, `/plan`, `/edit`, `/review`,
  `/claude`, `/codex`, `/team` and `/handover`, which hand the work to
  agents, to Claude Code or Codex, to a team of sessions or to another
  session.
  - ctrl+l and ctrl+t open a rail on either side: the project's own agents,
    skills and tools, the window's sessions, its open issues, what it
    remembers and its self-improvement on the left; its files, changes,
    commits and branch graph, a live feed of the session's activity, and
    programs — a shell, emacs, htop — each in a tmux session that outlives
    byj, on the right.
  - ctrl+g sends a tool call the turn is waiting on to the background.
  - The session's agent can hand a task to a Claude Code or Codex session
    running in tmux: `session$list` lists them beside byj's, and
    `session$send` types the task in and brings the answer back (RFC-039,
    RFC-049).
- **The Agent SDK** (RFC-053): `byj acp` is an Agent Client Protocol v1
  agent on stdio, so an ACP editor runs byj as its agent — Zed with an
  `agent_servers` entry of `type: custom` — and imports its saved threads.
  An application drives it from its own code through `sdk/typescript`
  (`@grumatic/byj-agent-sdk`) or `sdk/python` (`byj-agent-sdk`), released at
  [grumatic/brainyard-sdk](https://github.com/grumatic/brainyard-sdk): one
  prompt with `query()`, sessions that last, tools and hooks of its own,
  permission prompts, and an answer given back as data fitting a JSON
  Schema.
- **Other programs** reach a session through its socket (RFC-031), share it
  live through a share sidecar (RFC-043), and read what it leaves on disk
  (RFC-025); `contract/` is what byj promises them (RFC-070 "Standing
  alone").

## The loop

The loop is brainyard's `react-agent`, which is the CoAct loop with the code
channel turned off:

```
init → repeat(max-iterations, until terminated)
         inc-iter → [think-act | repair] → strip-unbound-tools → resolve-channel-conflicts
         → router: answer → stamp | tool-calls → dispatch | repair(no-action)
         → accumulate
     → ensure-answer
```

It keeps the original's contract:

- **One call per iteration, DSPy-style.** Each call sends a system message
  and a user message. The model replies with `{reasoning, tool-calls,
  answer, goal-achieved, next-user-prompt}` as JSON; native tool-use APIs
  are not used.
- **History is printed data.** Earlier iterations are resent as the printed
  Clojure data in the `iterations:` line, capped to the last 10.
- **The same guards:** a malformed or empty reply triggers repair, and 3 in
  a row abort with `:llm-error`; 3 iterations with no action end the turn
  with `:none-channel-loop-guard`; unbound tools are dropped; an answer
  written in the same reply as tool calls is discarded; running out of
  iterations ends with `:max-iterations-exhausted`.

### Nested loops: `run` wraps the ReAct loop

`byj run` is a second tree, `resources/brainyard/loops/session.edn`, and
each turn embeds the whole ReAct loop inside it:

```
session/init → repeat(until /quit or EOF)
  read-input → eof → end
             | blank line
             | /command → run-command          (/help lists them)
             | begin-turn
               → [scope {:in {:question :input, :history-context :history-context}
                         :out {:turn :*}}
                   [include "brainyard/loops/react.edn"]    ← the ReAct loop above
                 | turn-failed]
               → end-turn                      (fold the turn into history, print answer)
→ session/close
```

- **`:include`** splices another tree in by resource name or file path
  before the tree runs, and detects include cycles. `--print-tree` shows the
  fully expanded result.
- **`:scope`** runs its child against a fresh `st-memory`, so the inner
  loop's keys (`:answer`, `:terminated`, …) never collide with the outer
  loop's. Data crosses only through the scope's `:in` and `:out` maps.

The session keeps a chain of previous turns, after brainyard's
`append-turn` / `format-previous-turns`: the newest 3 keep their tool trail,
older ones only the question and answer. The chain is rendered into the
inner loop's system prompt as `## history-context`, which is how a second
turn can resolve "that file". A failed inner loop is caught by
`turn-failed`, so it ends the turn, not the session.

### Declarative predictors

The loop's LLM step is a **predictor**, as in brainyard's
`clj-llm.core.predictor`: a named use of a signature, defined entirely as
data. The tree node declares which predictor it runs and which system-prompt
zones it sees:

```clojure
[:action {:id :react/think-act :fn :react/think-act
          :predictor "react/think-act"
          :zones [:agent-core :session-context :history-context :user-context]}]
```

`resources/brainyard/predictors/react/think-act.edn` holds the spec: `:id`,
`:strategy` (`:cot` puts `reasoning` first, `:predict` leaves it out),
`:instructions`, and the `:inputs`/`:outputs` field specs with their JSON
schemas.

**Params** tune a predictor without editing its spec, resolved on every
call; the first layer that has a record wins whole:

| Precedence | Layer |
|---|---|
| 1 | `--params FILE` / `:params {id params}` |
| 2 | `<working-dir>/.brainyard/programs/<id>.edn`, then `~/.brainyard/programs/<id>.edn` |
| 3 | the spec's own `:params` |

A params record may carry `:instructions`, `:field-descs {field desc}`,
`:demos [{:inputs :outputs :reasoning?}]`, `:max-demo-value-chars` and
`:version`. An invalid params file is skipped and reported, never fatal, and
params never choose a model.

**User predictors** live at `.brainyard/predictors/user/<name>.edn`, project
scope first, and any tree runs one with the generic leaf:

```clojure
[:action {:fn :predictor/run :predictor "user/summarize"
          :in {:text :answer} :out {:summary :summary}}]
```

Every call emits `:predictor/call` (id, inputs, outputs, reasoning, params
source and version, timing, or the error), the trace an optimizer turns into
demos. `--print-predictor ID` shows a predictor with its resolved params and
their source.

## Layout

| Path | Role |
|---|---|
| `src/brainyard/` | The kernel: the behaviour-tree interpreter (`bt`), providers (`llm`), tools, prompt assembly (`signature`), predictors, the effect substrate on ebb (`effect`), the CLI (`main`) |
| `resources/brainyard/loops/` | The trees: the ReAct loop, the session, the agents' loops |
| `resources/brainyard/predictors/`, `prompts/` | Every predictor spec and every word the model reads |
| `resources/brainyard/tools/`, `hooks/` | Primitive tool specs and built-in hooks, as data (RFC-010, RFC-011) |
| `docs/RFCS/` | One specification per layer; [RFC-008](docs/RFCS/RFC-008-brainyard-migration.md) is the roadmap against brainyard |
| `docs/divergences.md`, `docs/provenance.md` | Intended differences from brainyard; past defects that code comments cite |
| `contract/` | What byj promises programs built on it, held by `contract-test` |
| `sdk/` | The Agent SDK's TypeScript and Python libraries (RFC-053) |
| `test/` | The suite, run by `jolt test:byj` on the mock model |
| `dev/` | The live image's namespaces, spikes and the scripts behind the suite and the release |

Some of brainyard's JVM dependencies were replaced for jolt: `java.net.http`
by `jolt-lang/http-client`, `ProcessBuilder` by `jolt.process`, malli by
plain field specs, missionary by ebb, selmer by pre-rendered prompts. Not
ported, by decision: the code channel (SCI) — agent-written code is Tool
Lisp over the tool channel, composed with `tool$run` (RFC-010). RFC-008 says
what else is ported and how, row by row.

## Notes from porting

- **Command vectors:** `jolt.process` does not flatten a command vector the
  way babashka.process does. Spread it with `apply`.
- **Parallel tools:** brainyard `pmap`s a tool batch. Models do emit ordered
  write → update → read batches, and a live run read stale content, so
  batches run in order by default (`:parallel-tools? true` restores `pmap`).
- **Port the prompt, not a summary of it:** an early version shortened
  brainyard's prompt, dropping zones and the schema's field descriptions,
  and Opus refused it again and again (`stop_reason "refusal"`).
  Brainyard's own request, replayed byte for byte through this port's
  process layer, went through. The prompt follows brainyard's zones:
  `agent-core` → `session-context` → `history-context` → `user-context`.
- **Failed calls stay out of the prompt:** an iteration whose LLM call
  failed is kept in the trajectory but not in the `iterations` the model
  sees; malformed replies still get a `FORMAT ERROR` notice, since the model
  can fix those.

## Licence

See `LICENSE`, and `NOTICE` for the licences of byj's dependencies.
