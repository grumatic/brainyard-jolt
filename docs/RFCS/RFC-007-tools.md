# RFC-007 — Tools

**Status:** partial.

## Purpose

Specifies the agent's capability surface on the machine: what a tool is, how a
call is validated and dispatched, and the six built-ins. brainyard sources:
`agent/core/tool.clj` (`deftool`, `call-tool`, `bind-tools`),
`agent/common/tools.clj` (the built-ins),
`agent/core/context/formatters.clj` (`format-agent-tools`); discovery from
`agent/common/tools.clj` (`bootstrap-tools`) and `common/coact_agent.clj`
(`coact-tools-discovery`).

## Scope

**This layer decides** how a named call with arguments becomes a result, and
never throws doing it.

**It must not know** which loop called it or why.

**It hands** a result value (a map, usually) to `tool-dispatch`, which prints it
into the iteration record (RFC-003); and a markdown listing to the
session-context zone.

## Model

```clojure
{:name "read-file"
 :description "Read file content. With offset/limit, returns numbered lines."
 :input-schema [:map [:path [:string {:desc "…"}]]
                     [:offset {:optional true} [:int {:desc "1-based first line"}]]]
 :output-schema [:or [:map [:path :string] [:size :int] [:content :string] …] [:map [:error :string]]]
 :effects #{:fs/read}
 :fn (fn [args ctx] result)}
```

`:output-schema` — brainyard's name (`byj-gly.238`) — is a malli schema for
every built-in tool (RFC-030). What is shown is the line written from it
(`schema/output-text`): `{:path string, :size int, :content string, …} or
{:error string}`. That line is what the listing, `get-tool-info`, `list-tools
:detail` and `tool$read` show, so they show what they did before. A result the
schema does not describe emits `:tools/output-mismatch` and is returned all
the same. A command defined in code writes its `:output-schema` as data
(`byj-gly.199`); `tool$run` and `tool$test`, whose results are arbitrary, keep
prose.

The six built-ins are **specs in `resources/brainyard/tools/`** (RFC-010): data
with `:impl` naming the fn in `brainyard.tools/impls`; `builtin-tools` loads
them in `index.edn` order. The tool listing (`format-tools`) prints each tool's
inputs and its `Outputs:` line — result shapes were the cause of both wrong
answers in the Tool Lisp authoring eval. User tools and the `tool$*`
commands are RFC-010.

`read-file`, `write-file` and `update-file` read and write text through an fs,
`{:read (fn [file] text-or-nil) :write (fn [file text] nil-or-why)}`:
`disk-fs` for every session. `file-tools` gives the three over another —
`byj acp`'s client editor (RFC-053, `byj-gly.464`) — and `read-file*`,
`write-file*`, `update-file*` take the fs first. A caller's tool named as a
primitive replaces it in its place (`sdk/base`), so the listing never has two
of a name.

A tool's arguments are a malli schema (RFC-030): its `:input-schema`, as
brainyard's are (`byj-gly.237`). The listing shows each entry with its JSON
Schema type (`schema/inputs`). `call-tool`:

1. normalizes args — string or keyword keys, or `[{:name :value}]` pairs;
2. decodes them with `schema/tool-transformer` — brainyard's
   `llm-args-transformer` (`"2"` → 2, `"True"`/`"yes"` → true, `":a"` → `:a`,
   an enum member in either form), after the port's own rules on each
   top-level argument (a number printed where a string is wanted, a lone
   string → `[string]` for an array, an object parsed from JSON text, a whole
   float taken as an integer); a null is the argument left out;
3. reports every problem in one `{:error-message "Invalid tool args for X: …"}`,
   a missing one as ``missing required `k` ``, any other by its path
   (`` `items.0.n` should be an integer``);
4. fires the hooks around the call (RFC-011): `:agent.tool-use/rejected` for
   invalid args, the gated `:agent.tool-use/pre` — which may block, replace the
   result or modify the args — and `:agent.tool-use/post`;
5. calls `:fn`; an exception becomes `{:error-message "X failed: …"}` — except a
   cancellation, which propagates (RFC-009 rule 7).

Relative paths resolve against `(:working-dir config)`.

### What the prompt says about a tool

The `### Agent Tools` block carries **one line per tool** — its id, the first
line of its description, and its result shape. Arguments come from
`get-tool-info` when the model needs them, which is the progressive disclosure
skills already use (RFC-017).

Measured on this repository: at full specs the block was 18,199 of 32,737
system-prompt characters — **56% of every prompt** — and compacting it takes the
whole prompt to 23,163. brainyard has this switch (`:compact-agent-tools`) and
keeps it **off for ReAct**: "its roster is its menu", since a CoAct agent also
has a sandbox function directory to look a tool up in. This port is its ReAct
agent and compacts anyway, because discovery is on and `get-tool-info` is the
directory (divergence `compact-tool-listing`). With `:tool-discovery? false`
the block goes back to full specs, since then there is no way to reach a schema
at all.

**Past the primitives, a family is a line** (`:tool-listing-by-family?`,
default true; byj-gly.188). The primitives and the user's own tools keep a line
each; every other family — `config$`, `task$`, each connected MCP server
(`mcp$linear`) — is ONE line: what the family is for
(`resources/brainyard/prompts/tool-families.edn`, a server's name for an MCP
family), how many tools, and their ids in full, at most twelve, then `… and N
more`, followed by how to get the rest (`get-tool-info` for one tool's
arguments, `list-tools {:family …}` for a family's descriptions —
`discovery/format-tools-by-family`). Measured on this repository with no MCP
server connected: the block went from 18,702 to 4,534 characters and the zone
from 26,030 to 11,862; a connected server of sixty-odd tools is one line
instead of sixty. The ids are written out whole because a model calls what it
reads; the family's purpose is said because an id list did not say that
`tool$` is how to write tools in Tool Lisp.

**The result shape stays.** Omitting it cost both wrong answers in the Tool Lisp
authoring eval (RFC-010, "Authoring evidence"): a model composing one tool onto
another has to know what comes back, and cannot learn it by calling the tool
first. Dropping arguments saved 39% of the zone; dropping results too would have
saved 52%, and that 13% is not for sale.

What survives is also carrying more than it looks: `read-file`'s one line is
"Read file content. With offset/limit, returns numbered lines" — in a live check
the model called `read-file {:path … :offset 40 :limit 16}` correctly on the
first attempt, without needing `get-tool-info` at all.

### Discovery

brainyard's bootstrap tools, `brainyard.discovery`, are primitive specs whose
impls read the live registry from `ctx` (`:tools`, RFC-010). Every registered
tool is also listed in the prompt, but the listing is fixed when a turn starts,
so discovery is how the model finds a tool created during the turn — and how it
re-reads a schema without the whole listing. On by default
(`:tool-discovery?`); `make-ctx` adds them after the base tools.

| tool | args | result |
|---|---|---|
| `list-tools` | `family?` `pattern?` `type?` `grouped?` `detail?` | no args or `type` alone: a **summary** `{:total :families [{:family :count :tools [id…] :more?}] :next}` — each family, its count and at most six ids, and a `:next` saying how to narrow (divergence `list-tools-summarises`). A family is the id before its first `$` (`user$tool$x` → `user`), an MCP server's own (`mcp$linear$…` → `mcp$linear`), or `_ungrouped`. `family`, `pattern` (case-insensitive regex over id and the whole description), `grouped false` or `detail true`: a flat `[{:id :type :description}]`, the description's first line — cut to 100 characters past twenty hits — with `:input-schema :output-schema` under `detail`. Sorted by id. Without `detail`, a flat listing always fits in one result: descriptions are cut to 50 characters and then dropped, leaving `{:total :tools {family [id…]} :note}` — 191 tools flat were 31,000 characters, spilled and paged back in four calls (byj-gly.191). With `detail`, the whole listing when it fits, else the same ids-by-family with a note to narrow it by `family` or `pattern` — every tool's params were 171,000 characters (byj-gly.192). |
| `get-tool-info` | `tool-id` | `{:id :type :description :input-schema :output-schema :effects}`, plus `:calls` for a user tool; `{:error-message "tool(ID:x) is not defined in system"}` |
| `search` | `query` | whitespace-split, tokens under 3 chars dropped, every token a case-insensitive substring. `{:project-files [path] :config-files [path] :tools [{:id :type :description}]}`: the first 500 files under the working directory (sorted walk, `.git` skipped; divergence `search-walks-all-files`), 50 kept; tools by id or description. An empty section is absent; no usable token → `{}`. |

`:type` is brainyard's: `"command"` for the `tool$*` commands, `"tool"` for
primitives and user tools.

The prompt carries brainyard's text for them: the react overlay's "To reach a
tool beyond the bound set, discover it with `list-tools` / `get-tool-info`…", the
tool-only overview's sentence, `### Bootstrap Tools (always bound)` in
agent-core, the instructions' "discover real ones with `list-tools` and
`get-tool-info`" (brainyard's say `(search \"keyword\")`, code-channel syntax
this port has no channel for — `byj-gly.242`), and `### Discovery (other registered tools)` (`prompts/tool-discovery.md`) —
the latter without the MCP pattern example and the skill and agent types, which
do not exist here yet.

| tool | args | result |
|---|---|---|
| `bash` | `command`, `timeout?` (ms, 120000) | `{:status "completed"\|"failed"\|"timeout" :exit-code :output}` — stdout+stderr merged, stdin closed, read line by line as printed (`fx/streaming-process`) so the timeout or a cancelled turn kills the process tree, output capped at 30000; a timeout says the command and every process it started were stopped, and to pass a larger `:timeout` (`tools/timed-out`, byj-gly.315.11), then what it printed before it was stopped, its last 30000 characters (byj-gly.363: a login's code, printed at once, had come back as nothing); each line is also said as it is printed, `:agent.tool/output` with the call's place in the batch (`:tool-call-index`), live only, which the TUI draws under the running call and `-v` prints (byj-gly.364); the command leads a process group of its own, so what it leaves running in the background (a server started with `&`) is named in `:left-running` and the output, kept, and stopped when the session ends or this process exits (`tools/stop-background!`, byj-gly.315.16) |
| `read-file` | `path`, `offset?`, `limit?`, `column?` | `{:path :size :total-lines :content}` — `:total-lines` whole or paged, since a Tool Lisp body cannot tell which it got (byj-gly.243) — numbered lines with offset/limit; any read that would pass `:max-output-chars` — a whole file, a range, one enormous line — comes back as a page within it, with `:total-lines :next-offset :next-column :note`, and a line longer than a page is read on in pieces with `:column`, so no read of a file is cut and spilled. A page is measured as printed, where a newline, tab or quote is two characters (byj-gly.218) (byj-gly.190, `agent-test/read-file-pages-a-file-too-big-for-one-result`) |
| `write-file` | `path`, `content`, `append?` | `{:path :chars}`; creates parent dirs |
| `update-file` | `path`, `pattern`, `replacement`, `all?`, `expect-count?` | `{:path :replaced}`; literal match; refuses missing or ambiguous patterns |
| `grep` | `pattern`, `path?`, `max-results?` (50), `include-exts?` | `{:matches ["path:line:text"] :total :truncated}` (`grep -rnHIE`, skipping `.git` and `.brainyard` unless `path` aims inside it) |
| `list-dir` | `path?` | `{:path :entries}`, directories suffixed `/` |

## Invariants

| invariant | enforced by |
|---|---|
| The prompt lists the primitives a line each with their result shapes, every other family on one line with its purpose and full ids (an MCP server named as itself, capped), how to get the rest; `:tool-listing-by-family? false` restores a line per tool. | `discovery-test/the-prompt-lists-a-family-a-line` |
| A failed call is knowable as failed: `tools/failed?` names the three shapes this layer produces, so a reader does not pattern-match result text. | `analytics-test/the-error-rate-is-carried-not-pattern-matched` |
| Each built-in's contract above, including timeout kill — nothing left running, and the result saying so — what a command leaves in the background named and stopped, and ambiguous-pattern refusal. | `agent-test/tool-calls` |
| What a session's commands left running in the background ends with the session, and the session says so as it closes. | `session-test/what-a-sessions-commands-left-running-ends-with-it` |
| Invalid args return an error map naming every problem; a tool never throws into the loop — except a cancellation. | `agent-test/tool-calls` (missing `path`); `call-tool`'s try. |
| A cancelled turn kills a running bash tool's children. | `cancellation-test/a-cancelled-turn-kills-its-bash-tool` |
| `list-tools` with no arguments summarises (a few ids a family, no descriptions, how to narrow); `family`, `pattern`, `grouped false` and `detail` list flat; an MCP server is a family of its own. | `discovery-test/list-tools`, `discovery-test/an-mcp-servers-tools-are-a-family-of-their-own` |
| A flat listing, with `detail` or without, fits in `:max-output-chars` and never leaves a tool out. | `discovery-test/a-flat-listing-fits-in-one-result` |
| Tool visibility: `:visible-tools` / `:hidden-tools` globs decide what a turn's registry holds, and hiding wins and is inherited by every turn below (RFC-018). | `agents-test/an-agent-narrows-its-tools` |
| `get-tool-info` shows a user tool's calls and effects; an unknown id is an error value. | `discovery-test/get-tool-info` |
| `search` ANDs tokens of 3+ chars case-insensitively over file paths (top-level included, `.git` not) and tools. | `discovery-test/search` |
| A tool created during a turn is found by `list-tools` in that turn; the prompt carries the discovery text only when discovery is on. | `discovery-test/the-loop-offers-discovery` |
| The listing is one line per tool, keeps every declared result shape, and returns to full specs when discovery is off. | `discovery-test/the-tool-listing-is-one-line-each` |

## Known gaps

- `bash` commands are read by the command gate (RFC-012): its program must be in
  `:bash-allow-commands` when one is set, and the paths it names are checked.
- `search` has no memory section, and will not: an external store (RFC-020)
  returns text, and text cannot be told apart from a polite miss.
- MCP tools do not exist yet — `byj-gly.14`. Type `skill` is RFC-017, type
  `agent` is RFC-018.
- No MCP tools — `byj-gly.14`. Background tasks are RFC-019: a call that outruns
  `:fast-eval-timeout-ms` detaches into one on its own.
- brainyard's `fetch-url`, `web-search`, `time$*`, `get-user-feedback` are not ported.
