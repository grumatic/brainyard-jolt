# RFC-030 — malli for tool schemas

**Status:** implemented — **adopted** (`byj-gly.196`, 2026-09-26). Stage 1,
arguments (`byj-gly.197`), and stage 2, results (`byj-gly.198`), are built,
and the commands defined in code carry schemas too (`byj-gly.199`). Stage 3
(`byj-gly.237`) retired `:params`: every tool declares its arguments as
brainyard's malli `:input-schema`. Stage 4 (`byj-gly.238`) renamed the result
key `:returns` to brainyard's `:output-schema`.

## Purpose

Decides whether a tool's arguments and result are described, checked and
coerced by malli, as brainyard's are, instead of by this port's own plain
spec (`:params`) and a line of prose (`:returns`).

brainyard sources: `components/agent/src/ai/brainyard/agent/core/tool.clj`
(`:input-schema`, `:output-schema`, `malli-map-entries`),
`core/context/formatters.clj` (the prompt's Inputs/Outputs lines rendered from
them), `common/tools.clj` (`list-tools :detail` inlines both), and
`skills_test.clj` L402 (command outputs checked against `:output-schema` — in
tests, not at run time).

## What was there before

- **`:params`** — `[[:path {:type "string" :optional true :desc "…"}] …]`,
  checked by `tools/check-args`: presence, and a coercion per type (`integer`
  from a numeric string or a whole float, `boolean` from `"true"`/`"false"`,
  anything printed where a `string` is wanted, a lone string wrapped where an
  `array` is, an `object` parsed from a JSON string). Nested shapes are not
  checked. Gone since stage 3.
- **`:returns`** — a string, e.g. `"{:path string, :size int, :content string}
  …; or {:error string}"`. Only the model reads it: the listing, `get-tool-info`,
  `list-tools :detail`, `tool$read`. Nothing checks a result against it, and it
  drifted by hand twice in one week (`read-file`, byj-gly.190 and .191).

## Measurements

`dev/brainyard/dev/malli_spike.clj`, run as
`jolt -A:dev:malli -m brainyard.dev.malli-spike` (the `:malli` alias carries
the dependency for the spike only). malli 0.20.2, jolt 0.8.10, an M-series
laptop, 2026-09-26.

**It runs on jolt.** `m/validate`, `m/explain` + `me/humanize`, `m/decode` and
`m/coerce` with `mt/string-transformer`, `:default` and
`strip-extra-keys`, `:or`, optional keys, nested `[:vector [:map …]]`,
`:map-of`, `:enum`, `m/form`/`m/properties`/`m/entries`, and
`malli.json-schema/transform` all behave as on the JVM. One condition:
`malli.transform` builds a `DateTimeFormatter` at load, so `jolt.time` must be
loaded first — as `clojure.data.json` already requires (`brainyard.json` does
it).

| | without malli | with malli | cost |
|---|---|---|---|
| requiring malli core/error/transform, in a process holding the port | — | 37–48 ms (json-schema 7–21 ms more) | |
| `jolt -e "(require 'brainyard.main)"`, warm, 4 runs | ~480 ms | ~505 ms | +25–35 ms |
| `jolt build` binary size | 66.1 MB | 75.1 MB | **+9.0 MB (+13.6%)** |
| binary `--version`, warm, 4 runs | ~395 ms | ~455 ms | **+50–60 ms** |
| one argument check, compiled once per tool | ~1.0 µs | ~2.1 µs | +1 µs |

The binary variant is `dev-malli/brainyard/dev/malli_main.clj` (brainyard.main
with malli required), built with `jolt -A:malli build -m brainyard.dev.malli-main`;
a `-A:dev` build of the plain main is byte-for-byte the plain size, so the 9 MB
is malli.

**Fidelity.** Every registered tool's `:params` converts mechanically to a
malli `[:map …]` (`params->schema`): **114 of 114**. The three coercions
`check-args` has that `string-transformer` lacks — print a non-string, wrap a
lone string, parse a JSON object — and whole floats to `:int` are one small
transformer (`tool-transformer`). Judged by both checks, the tool calls models
actually wrote — the raw replies in `events.log` and the predictor outputs in
`.brainyard/sessions` — plus eleven synthetic calls for each coercion rule:

- **252 calls, 17 distinct tools, 4 rejected: 252 the same verdict and, when
  accepted, the same coerced args. 0 different.**
- The messages say the same thing: ``missing required `reason` `` from
  `check-args`, ``` `reason` missing required key``` from malli.

What the corpus does not reach: 97 of the 114 tools were never called in it,
and no MCP tool is registered in the spike (their calls, eight tools, are
skipped). Parity there rests on the conversion being mechanical.

## What malli would add

1. **Nested and constrained arguments.** `[:vector [:map …]]`, `:enum`,
   `{:min 1}` — checked, with every problem named, where `:params` cannot say
   them. Done in stage 3.
2. **A checkable `:returns`.** A test that runs each primitive and validates its
   result, and `tool$test` validating a user tool's, so the prose and the
   result cannot drift apart unnoticed. A mismatch at run time is logged, never
   fatal: a working call must not fail on its description.
3. **brainyard's own files, natively.** Their `:input-schema`/`:output-schema`
   are read as they are, and the malli→`:params` conversion goes. Done in
   stage 3, which retired the divergence `user-tool-files-params`.
4. **JSON Schema for free,** should native tool definitions or an MCP server's
   `inputSchema` need comparing.

## Decision

**Adopt malli**, as a `:deps` entry at the tag measured, in two stages:

1. **Arguments** (`byj-gly.197`): `:params` stays the spec format a tool file
   is written in and is compiled to a malli schema at load (so no tool file
   changes), or a file may give `:input-schema` directly; `check-args` becomes
   decode with `tool-transformer` + a validator compiled once per tool, its
   `me/humanize` messages joined into the one `:error-message` as now.
2. **Results** (`byj-gly.198`): `:returns` may be a schema, rendered to the same
   one line the model reads today; the conformance test; `tool$test`.

The price is the binary's 9 MB and 50–60 ms of start. Against it: every call
is checked as it is today (measured), and results become checkable, which
nothing is today.

**Not adopted** if 9 MB or 60 ms matter more than that — the alternative is
growing `:params` into a nested spec of our own and writing a validator for
`:returns`: less to load, and a second schema language to keep.

## Stage 1 — arguments (built, `byj-gly.197`)

`brainyard.schema`, required by `brainyard.tools`:

- **The registry** — brainyard's `schema-registry`: a `defonce` atom seeded
  with `m/default-schemas`, `register!`, `defschemas`. Passed to malli
  explicitly (`schema/schema`), never installed as malli's default registry,
  so loading it changes nothing process-wide (provenance P-18). It holds the
  shared schemas — the counterpart of brainyard's
  `ai.brainyard.agent.common.schema` domain, in this port's own namespace
  (`:brainyard.schema/question`, `answer`, `tool-name`, `tool-args`, `tool-call(s)`,
  `map-object-arg`, `vector-object-arg`, `agent-id`, …). A brainyard tool file
  that refers to brainyard's names does not resolve here. A tool call is
  `{name args}`, which is what `react/think-act` asks a model for: divergence
  `a-tool-call-is-name-and-args`.
- **`tool-schema`** — a tool's `:input-schema`, or an open `[:map]` for one
  that declares none. (Stage 1 compiled `:params` to it with `params->schema`;
  stage 3 removed both.)
- **`tool-transformer`** — brainyard's `llm-args-transformer` (its `:keyword`,
  widened `:boolean` and `:enum` decoders, then `mt/string-transformer`) for
  every schema. The port's four coercions run before it (`lenient-fns`), on the
  top-level arguments of every tool, by the argument's type. They do not run
  on an `:or` or on anything nested: printing a number made it match the
  `:string` branch of brainyard's `map-object-arg`.
- **`check-args`** — a checker compiled once per schema form (`memoize`):
  nulls dropped (a null is the argument left out), decode, validate, and on
  failure `me/humanize` flattened to ``missing required `k` `` or
  `` `path` <message> ``.
- The toolbox keeps a user tool's `:input-schema` (refusing one malli cannot
  compile), checks against it, and persists it.

The one change a caller can see: a boolean arrives from `"yes"`/`"no"`/`"1"`/
`"0"`/`"True"` as well, as in brainyard, and a non-map for an `object`
param — once passed through — is refused.

## Stage 2 — results (built, `byj-gly.198`)

Written here with the key's name since stage 4, `:output-schema`; stage 2
called it `:returns`.

- **`read-output-schema`** — an `:output-schema` as a malli form: data in a
  spec file, or EDN text from `tool$create`. Text that is not a schema malli
  compiles — `"{:lines int}"` reads as a map — is prose, as every result
  description once was; a tool being made may not have it (byj-gly.263,
  RFC-010).
- **`describe`** — an output schema as the one line the model reads:
  `{:path string, :total-lines? int} or {:error string} — <top-level :desc>`.
  An optional key ends in `?`, or says `(optional)` when its name already
  ends in one (`:changed?`); an enum is `"a"|"b"`; `[:maybe x]` is `x or nil`;
  a predicate is its noun (`number?` → `number`, since `:score number?` read
  as an optional key); a registry reference is described by what it names;
  and a map more than two maps deep is `{…}`, because spelt out,
  `session$analytics` was one line of ~1,500 characters. The check still
  holds the result to all of it.
- **`output-text`** — what every place that shows a tool's result calls:
  prose as it is, a schema as its line, written once per form. The tool keeps
  its `:output-schema` as the form; nothing is converted at load, and nothing
  that shows it can drift from the schema.
- **At run time** `call-tool` checks a tool's own result, not the layer's
  exception wrapper, against `tool-output-schema` (its `:output-schema` when
  that is a form). On a mismatch it emits
  `:tools/output-mismatch {:tool-name :problems}` at level `:problem` and
  returns the result all the same: the call worked. brainyard checks
  `:output-schema` in its tests only (divergence `tool-args-coerced-leniently`).
- **`tool$test`** returns `{:result :output-problems}` when the result is not
  what the tool's schema says, and the result alone otherwise.
- All nine built-in tools carry a schema, and each has samples in
  `every-built-in-tool-returns-what-it-says` for every branch of it.

## Commands defined in code (`byj-gly.199`)

A command written in code — `config$*`, `agent-registry$*`, `task$*`,
`todo$*`, `tool$*`, … — writes its `:output-schema` as the schema itself, as
data, like a spec file. The places that show it — the two listing formatters,
`list-tools :detail`, `get-tool-info` and `tool$read` — call
`schema/output-text`.

`tool$run` and `tool$test` return whatever the body or the tool returned,
and keep a prose `:output-schema`. The other 112 registered tools carry a schema.
Written from the code, not the prose, they found a good deal the prose never
said: error branches (`config$read`, `log$events`, `session$analytics`, …),
keys (`flow$check`'s `:hash`, `tool$create`'s `:description`), keywords
where it said strings (`task$*`'s `:status`), and a promised `:usage` that
`agent-registry$detail` never returned.

The one-line-per-tool listing (`:tool-listing-by-family? false`) shows each
tool's result line (`Returns:` then, `Outputs:` since byj-gly.239), so it grew from 25,508 to 31,319 characters. The
default family listing shows none, and is unchanged at 12,499.

**The suite is the conformance check.** `test-runner/run` switches on
`schema/record-outputs!`, and `call-tool` notes every result it checks. The
run fails, naming the tool and its problems, if any test produced a result
its tool's schema does not describe. This works as the `.brainyard/sessions`
guard does: the suite already calls these commands with real arguments in
real state, which bespoke samples would only repeat. A test whose mismatch is
the point binds `schema/*record?*` false.

## Stage 3 — every tool an `:input-schema` (`byj-gly.237`)

`:params` is gone, with no compatibility path. Every tool declares its
arguments as brainyard does:

```clojure
:input-schema [:map [:path [:string {:desc "File path"}]]
                    [:limit {:optional true} [:int {:desc "Maximum number of lines"}]]]
```

The ten primitive spec files, the ~100 commands defined in code, MCP tools and
user tools all use it. A `:desc` sits on the value's schema, as brainyard
writes it, or on the entry.

- **The listing** (`tools/format-tools`) reads `schema/inputs`, the schema's
  entries as `{:name :type :optional? :desc}`. The type is the JSON Schema name
  brainyard's `format-agent-tools` shows (`string`, `integer`, `array`,
  `object`, …); an enum is its members, `"a"|"b"`. For every tool written from
  `:params`, the prompt is byte for byte what it was, except that a `list`
  argument now says `array`.
- **`get-tool-info` and `list-tools :detail`** return `:input-schema`, as
  brainyard does, where they returned `:params`.
- **Tool Lisp's call check** (`tool-lisp/validate`) and **flow's `:in` check**
  read the same entries.
- **MCP**: `mcp/input-schema` is brainyard's `mcp-json-schema->input-schema`:
  enum, the primitives, arrays by their items, objects by their properties
  with each level's `:required`, anything exotic `:any`.
- **User tools**: `tool$create` takes `input-schema`, a malli `[:map …]` as EDN
  text, and persists it. A file that still carries `:params` is skipped at
  load with a message naming the fix, not loaded with no argument check.
- **The coercions** that stage 1 kept for `:params` tools apply to every tool
  (divergence `tool-args-coerced-leniently`).
- **Messages**: a wrong kind where a collection is wanted says "should be an
  array" or "should be an object" for every schema, not malli's "invalid type".
- Arguments the conversion left as `:any` got their real type where the tool's
  code said it: `flow$patch`'s `ops` is `[:vector :map]`, `todo$*`'s `items`
  `[:vector [:or :string :map]]`, and `grep`'s `include-exts` `[:vector :string]`.

## Stage 4 — `:output-schema` (`byj-gly.238`)

A tool definition's result key is brainyard's name, `:output-schema`, where it
was `:returns`, with no backward compatibility: spec files, commands in code,
MCP tools, user tools. `tool$create` takes `output-schema` and persists it
under that key, as the form — a malli schema, since byj-gly.263 refuses prose
for a tool being made (RFC-010); `tool$test` says `:output-problems`. `:returns-schema`, the
converted copy stage 2 kept beside a line, is gone: the tool holds the form,
and `output-text` writes the line when it is shown. The functions follow the
key (`read-output-schema`, `tool-output-schema`, `output-text`,
`output-problems`, `record-outputs!`).

What a model reads followed (`byj-gly.239`):

- the listing labels a tool's result line `Outputs:`, brainyard's label in
  `format-agent-tools`, where it said `Returns:`. The line itself is the same;
- `get-tool-info`, `list-tools :detail` and `tool$read` return the tool's
  `:output-schema` itself — the form, or an older user tool's prose — beside its
  `:input-schema`, as brainyard's `get-tool-info` does, where they returned the
  line as `:returns`. A `list-tools :detail` too large for one result still
  falls back to the ids by family;
- the event is `:tools/output-mismatch`, where it was `:tools/returns-mismatch`.

## Predictor signatures (`byj-gly.203`)

The same schemas describe what a model is asked for. A predictor spec's
fields are malli map entries, and the old `{:type … :schema …}` form is gone.
The JSON Schema in the prompt and at the provider is written from them
(`schema/json-schema`), and a reply is decoded and checked against them. A
spec or params file is checked against a malli schema. RFC-005 has the details.

## Invariants

| Invariant | Enforced by |
|---|---|
| malli judged every recorded tool call as the plain check did (before stage 1) | `brainyard.dev.malli-spike` at `0e183b9` (a spike, not the suite) |
| Every tool declares a malli `:input-schema`; the listing reads its entries, their JSON Schema type and `:desc` | `schema-test/a-tool-declares-its-arguments-as-a-malli-map` |
| Every tool's arguments keep the port's coercions | `schema-test/every-tool-keeps-the-coercions-models-taught-the-port` |
| Every schema decodes with brainyard's `llm-args-transformer` rules | `schema-test/brainyards-own-decoding-applies-to-every-schema` |
| An `:or`, and anything nested, is not coerced the port's way | `schema-test/an-or-is-not-coerced-the-ports-way` |
| An MCP tool's JSON Schema becomes brainyard's malli map | `mcp-test/json-schema-becomes-a-malli-map` |
| A null is the argument left out | `schema-test/a-null-is-the-argument-left-out` |
| A problem names its argument by path | `schema-test/problems-say-which-argument-and-what` |
| The shared schemas resolve; `defschemas` adds to the registry | `schema-test/the-registry-holds-the-shared-schemas` |
| A user tool's `:input-schema` is checked nested, persisted and reloaded | `toolbox-test/a-brainyard-input-schema-is-checked-as-it-is` |
| Every built-in tool's `:output-schema` is a schema, and every result of every branch conforms | `schema-test/every-built-in-tool-returns-what-it-says` |
| An `:output-schema` is shown as the line written from it; prose stays prose | `schema-test/an-output-schema-is-written-as-the-line-the-model-reads` |
| A result unlike its schema is reported and returned unchanged | `schema-test/a-result-unlike-its-returns-is-said-never-fatal` |
| `tool$test` reports a user tool's result against its schema | `schema-test/a-user-tools-output-schema-is-checked-by-tool-test` |
| No test produces a result its tool's `:output-schema` does not describe | `test-runner/run` (the recorder), and `schema-test/the-suite-records-what-it-checks` |
