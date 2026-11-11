# RFC-010 — Declarative tools

**Status:** implemented (`byj-gly.28`).

## Purpose

Specifies tools as data: **primitive** tools, whose spec is a resource and whose
body is a kernel function, and **user tools** (composites), whose body is Tool
Lisp — an interpreted, allowlisted subset of Clojure that calls other tools.
User tools are brainyard's (`agent/common/user_tools.clj`): defined at runtime
with `tool$create`, persisted under `.brainyard/tools/`, callable as
`user$tool$<name>`. What changes is the body: brainyard re-evaluates Clojure
source in SCI; here nothing a body contains reaches host `eval`.

## Why

- **SCI does not run on jolt** (RFC-008 "Substitutions").
- **Host `eval` of agent-written code is the wrong privilege.** samizdat came
  to the same view about the thing its agent changes most: its procedure
  conditions are kept as patterns rather than `:when` forms, because its gates
  would evaluate those on the host, and code the agent edits that often should
  not run with the host's rights (`samizdat/procedure.clj`).
- **Bodies as data can be analysed without running them:** the call graph, the
  arguments of every literal tool call, and the effects a tool can have — what a
  permission gate needs before a tool runs (RFC-012, which gates on them).
- **Models already write this language.** Every brainyard user tool on this
  machine used only special forms, pure core and string functions, locals and
  one tool call; the authoring eval below measures the rest.

## Scope

**This layer decides** what a tool is, how a user tool's body is read, checked
and evaluated, and what a user tool may call.

**It must not know** which loop dispatches a tool or why.

**It hands** a live registry `{name tool}` to the loop (RFC-003: dropping
unbound calls, dispatch) and a listing with each `:output-schema` to the session-context
zone (RFC-005).

## Model

### Two kinds of tool

| kind | spec | body |
|---|---|---|
| primitive | `resources/brainyard/tools/<name>.edn` (listed in `index.edn`) | `:impl` names a kernel fn in `brainyard.tools/impls` (or `brainyard.discovery/impls`) |
| user tool (composite) | `<working-dir>/.brainyard/tools/<name>.edn` | Tool Lisp: `<name>.clj` beside it, or an inline `:body` |

```clojure
;; primitive
{:name "read-file" :description "…" :input-schema [:map [:path [:string {:desc "…"}]] …]
 :output-schema [:or [:map [:path :string] [:size :int] [:content :string] …] [:map [:error :string]]]
 :effects #{:fs/read}
 :impl :brainyard.tools/read-file}

;; user tool — count-lines.edn, with count-lines.clj holding the body
{:name "count-lines" :description "…"
 :input-schema [:map [:path :string]]
 :output-schema [:map [:lines :int]]}
```

A spec may also declare `:arg-aliases {alias canonical}`: other names a call
may use for an argument (byj-gly.394).
- **Where it applies:** `tools/apply-aliases` renames them in `call-tool`,
  before the schema is checked, so the tool's function sees only its own
  names.
- **Conflicts:** an alias given beside its canonical name with a different
  value is refused, naming both; with the same value it is accepted.
- **Missing arguments:** the problem names the aliases too ("missing required
  `pattern` (or `old`, `old_string`)").
- **The record:** a call is recorded as the model sent it.
- **The case behind it:** `update-file` takes `old`/`old_string` for `pattern`,
  `new`/`new_string` for `replacement` and `replace_all` for `all?`, and
  brainyard's `old-string`/`oldstring` spellings. Every call of it the logs
  refused had sent `old`/`new`, the names of the tools models are trained on
  (divergence `update-file-takes-old-new`). `agent-registry$ask` takes
  `subagent-id` for `id`, the name the dispatch result gives it
  (byj-gly.395).

A user tool's `:output-schema` is a malli schema, given to `tool$create` as EDN
text (`"[:map [:lines :int]]"`, RFC-030) and persisted as the form. A result
that can also be an error is an `[:or]` with `[:map [:error-message :string]]`,
and a part whose shape varies is `:any`. Prose is refused where a tool is made
— `tool$create`, `tool$validate`, a distilled draft — with the problem saying
what to write (`toolbox/output-schema-problem`, byj-gly.263): nothing checks
prose, and a distilled line of it missed a branch its own body returned. A tool
already on disk with prose still loads, shown as written. A schema is listed as
the line written from it, and `tool$test` reports a result it does not describe
as `{:result :output-problems}`. brainyard's user tools take no output schema;
each registers as `[:map]`.

The command tools (`tool$*`) are kind `:command`, with declared effects.

### The registry

`brainyard.agent/make-ctx` builds an atom `{name tool}`: the primitives (with the discovery tools,
RFC-007), the
`tool$*` commands (`:tool-authoring?`, default on), and the user tools
loaded from `.brainyard/tools` (`:user-tools?`, default on). It is **live**: a
tool created during a turn is callable in the turn's next iteration. The tool
listing in the system prompt is fixed when the turn starts, so a new tool is
listed from the next turn.

Loading is brainyard-compatible: an inline `:body` or a `<name>.clj` sidecar, and
brainyard's malli `:input-schema` (`[:map [:path [:string {:desc …}]]]`), which
the arguments are checked against as written (`byj-gly.237`). A file that still
carries the retired `:params` is skipped with a message saying so. A user tool may call another, so loading repeats until nothing more
compiles; a file that never compiles, or does not read, is skipped and reported
(`:tools/user-tool-skipped`), never fatal. One that calls a tool of an MCP
server still starting — or calls one that does — is not malformed but early:
it is held as `:waiting` (`:tools/user-tool-waiting`), and a session's turn
waits for that server before it starts (RFC-024, byj-gly.300). brainyard's
three existing tool files load and run unchanged.

A body is written to disk laid out (`tl/format-body`, byj-gly.264): a model
writes one line, and a person reads the file. Clojure's layout to 80 columns —
`fn` with its arguments, `let` a binding to a line, `if`/`when` with the test,
`cond` in pairs, a map an entry to a line, a call's arguments under its first —
with `#(…)`, `'x` and `@x` written as they were. The text is kept as it came
when laying it out would lose something (a comment, `#_`, metadata) or the
layout does not read back as the same form. `tool$create` lays the body out
before it registers the tool, so `tool$read` returns what the file holds; a
proposal's `body.clj` and a user hook's `<id>.clj` are laid out the same way.
jolt's `pprint/code-dispatch` did not do this: it had no code table
(jolt-lang/jolt#1177). As of jolt 0.8.15 it has one, and it was tried in place
of the layout half (`byj-gly.265`, 2026-09-30) on this test's bodies and a
tier D body. It is kept out:
- its output did not read back as the same form for the two bodies with
  `#(…)`, and read-back equality is the guard that lets a layout be kept;
- it puts a `let`'s bindings on one line and commas between a map's entries;
- it breaks a call after its head (`(mapv` alone, its arguments under it)
  rather than keeping the first argument beside the head and the rest under it.

### Tool Lisp

A body is ONE form, `(fn [args] …)`, read by `brainyard.tool-lisp/read-body` with
the Clojure reader and `*read-eval*` false. `validate` resolves every symbol
against exactly four things:

1. **special forms** — `fn`/`fn*` (anonymous; `#(…)` reads as `fn*`), `let`,
   `if`, `when`, `when-not`, `if-let`, `when-let`, `cond`, `do`, `and`, `or`,
   `->`, `->>`, `some->`; destructuring with `{:keys … :as …}` and `[a b & more]`;
2. **the allowlist** — `brainyard.tool-lisp/ops`: collections, EAGER sequence
   functions (they return vectors, so nothing lazy escapes and no tool call runs
   during a later realization), strings, regex, numbers, predicates,
   `json/read` and `json/write`;
3. **locals**;
4. **tools** in the registry, called by name with a params map:
   `(read-file {:path p})`, `(user$tool$count-lines {:path p})`.

Anything else is a problem, and every problem is reported at once:
`def`/`defn`, `eval`, atoms, I/O functions, Java interop (`.method`,
`Class/static`), an unknown symbol, a tool-call map naming a param the tool
lacks or omitting a required one. In the base language (`:tool-lisp-extensions
[]`) `loop`/`recur`, named fns and `try` are problems too.

The reference the model reads is `resources/brainyard/prompts/tool-lisp.md` plus
the allowlist generated from `ops`, so the documentation cannot drift from the
language.

### Extensions (`byj-gly.258`)

Three tiers widen the base language. They are on by default. Config
`:tool-lisp-extensions` (default `[:vocabulary :forms :control]`, `[]` for the
base language) names the tiers in force for a run: `agent/make-ctx` sets
`tool-lisp/extensions`, and the validator, the interpreter and the allowlist all
read it.

- **A, vocabulary** (`tool-lisp/vocabulary-ops`): pure functions only. These are
  `fnil`, `compare`, `keep-indexed`, `some-fn`/`every-pred`, `peek`/`pop`/`subvec`,
  `split-at`/`split-with`, `partition-by`, `dedupe`, `update-vals`/`update-keys`,
  `find`/`key`/`val`, `tree-seq`, `run!`, `clojure.set/*` and `clojure.walk/*`.
  They also include `clojure.edn/read-string` (strict: no reader tags),
  `clojure.data/diff`, and `ex-message`/`ex-data`.
- **B, forms**: `for` (eager, returning a vector), `doseq`, `dotimes`, `case`
  (list constants too), `condp`, `if-not`, `if-some`/`when-some`, `when-first`,
  `cond->`/`cond->>`, `as->` and `some->>`. `for` and `dotimes` are bounded by the
  generator cap.
- **C, control**:
  - `loop`/`recur`, and `recur` inside a fn;
  - a named fn that calls itself;
  - `letfn`;
  - `throw` of `ex-info`;
  - `try`/`catch`/`finally`, where a catch names `Exception`, `Throwable`,
    `RuntimeException`, `ExceptionInfo` or `:default`.

  A catch never catches a cancelled turn (an interrupt, `fx/cancelled?`) or an
  exhausted budget. Every iteration still counts against the step budget.
- **D, reach** (`byj-gly.261`), **off by default**: `:reach` in
  `:tool-lisp-extensions` takes it.
  - `(pmap f coll & colls)` is `fx/bounded` over one task per item. It runs
    `:parallel` at a time (the body budget's, default 4) and takes at most
    `max-parallel-items` (1000) items. Results come back in input order, as a
    vector. Each call runs with the body's bindings, so its steps, tool calls
    and nesting depth come out of the body's budgets. A failure cancels its
    siblings and is thrown, so a catch can take it (never the budget). A
    cancelled turn cancels every call still running.
  - `(now)`, an ISO-8601 UTC string, and `(now-ms)` are the clock. A body that
    names either carries `:clock/read` (`tool-lisp/body-effects`) in its
    composite's `:effects`, and `toolbox/effects` adds that to what the tools it
    calls do. So `tool-distill/prove` stages a draft that reads the clock
    unproved rather than replaying it.
  - State between calls is not an atom but the `store$get`, `store$put` and
    `store$keys` tools (`brainyard.store`). They hold a project's
    `.brainyard/store.edn`, a string key to EDN data, written atomically, one
    writer at a time, capped at 1,000,000 characters. Their effects are
    `:fs/read :store/read` and `:fs/write :store/write`, so the gates see a
    write and a replay is refused. They are bound only while the tier is.

  While it is on, the model reads `tool-lisp-reach.md` after the extended
  language (the `tool$*` specs), and `tool-composition-reach.md` in Composing
  Tools, where a body is about to be written. It is off by default: measured,
  it helps where it is wanted, and on the tasks that do not want it it costs
  haiku creates and time (`byj-gly.279`, below; why is `byj-gly.293`). A model
  call from a body is `byj-gly.10`.

**Tier D, measured (`byj-gly.278`, 2026-09-30).** The same eval ran on three
tasks that want reach (`dev/brainyard/dev/tool_lisp_reach_tasks.edn`):
- `visit-count` counts calls across calls and sessions (the store);
- `stamp` returns a timestamp (the clock);
- `line-counts` reads 24 files (`pmap`).

Each can be done without reach — a counter file, `bash date`, one read after
another — so the two conditions compare the same work. Tiers A–C were on in
both. Per task: cases passed, `tool$create` calls, LLM calls, seconds.

| model | reach | visit-count | stamp | line-counts | solved | seconds |
|---|---|---|---|---|---|---|
| haiku r0 | off | 4/4 · 3 · 7 · 121 | 1/1 · 2 · 4 · 110 | 1/1 · 2 · 6 · 87 | 3/3 | 318 |
| haiku r1 | off | 0/4 · 4 · 8 · 186 | 1/1 · 2 · 5 · 131 | 1/1 · 1 · 7 · 93 | 2/3 | 410 |
| haiku | on | 4/4 · 1 · 4 · 64 | 1/1 · 2 · 7 · 67 | 1/1 · 4 · 8 · 142 | 3/3 | 273 |
| sonnet | off | 4/4 · 1 · 4 · 31 | 1/1 · 1 · 6 · 65 | 1/1 · 1 · 4 · 34 | 3/3 | 130 |
| sonnet | on | 4/4 · 1 · 7 · 41 | 1/1 · 1 · 5 · 35 | 1/1 · 1 · 4 · 31 | 3/3 | 107 |

- **Offered, each model used each part where it fit**: the store for
  `visit-count`, the clock for `stamp`, `pmap` for `line-counts` — 6 of 6 kept
  bodies.
- **The store and the clock made haiku's work shorter.** `visit-count` took 1
  create and 64 s against 3–4 creates, 121–186 s and one miss, and `stamp` took
  67 s against 110–131 s. `pmap` did not: haiku took 4 creates and a refused
  body to write it, against 1–2 without. Sonnet's runs are all one create
  either way; with reach it was 107 s against 130 s.
- **Mentioned only in the `tool$*` specs, reach went unused.** The first haiku
  runs with reach on had the tier in `tool$create`'s spec and the store tools
  listed by name, but not in Composing Tools. No body used any part of it, and
  the runs took 3–5 creates and 542 s for 3/3
  (`target/tool-authoring-eval/results-reach-unmentioned.edn`). That is why
  `tool-composition-reach.md` exists.
- **Small.** One repetition with reach on per model, two off for haiku. The
  events log was not kept for these runs, so what haiku's refused `pmap` body
  was is not recorded.

**Tier D on the tasks that do not want it (`byj-gly.279`, 2026-10-07).** The
same eval ran on the 8 tier A–C tasks (`dev/brainyard/dev/tool_lisp_ext_tasks.edn`)
with `EXT=vocabulary,forms,control` and with `,reach` added, haiku ×2 and
sonnet ×1: 48 runs, the two conditions running at the same time so they shared
whatever load there was. Per condition, summed over the 8 tasks:

| model | reach | solved | creates | first create valid | refused bodies | out of iterations | LLM calls | seconds |
|---|---|---|---|---|---|---|---|---|
| haiku r0 | off | 8/8 | 13 | 5 | 0 | 0 | 49 | 764 |
| haiku r1 | off | 8/8 | 13 | 6 | 0 | 0 | 42 | 1090 |
| haiku r0 | on | 8/8 | 18 | 5 | 0 | 1 | 45 | 1037 |
| haiku r1 | on | 8/8 | 24 | 2 | 1 | 1 | 46 | 1123 |
| sonnet | off | 8/8 | 9 | 7 | 0 | 0 | 27 | 191 |
| sonnet | on | 8/8 | 9 | 7 | 0 | 0 | 26 | 188 |

- **Sonnet: no cost.** The same creates, the same first-time validity, 188 s
  against 191 s.
- **Haiku: worse on creates, first-time validity, refusals and time, not on
  solved.** 42 creates against 26, a valid first create on 7 tasks of 16
  against 11, one refused body and two runs that spent `:max-iterations`
  (each had already persisted a tool that passed) against none, and 2160 s
  against 1854 s. The worst tasks were `word-top3` (3 and 6 creates against 1
  and 1) and `paragraphs` once (6 against 3–4); `paragraphs` on its other run
  took 1 create and 72 s, the best of the four.
- **No body used reach.** `:reach-used` is empty on all 24 runs with it on, so
  the cost is the prompt, not the language: `tool-lisp-reach.md` and
  `tool-composition-reach.md` add 1108 characters, and the store tools join
  the listing.
- **What went wrong was the schema, not the body.** With reach on, `tool$create`
  rejected 20 of haiku's definitions, 16 for an `:input-schema` that is not a
  malli `[:map …]` string — passed as a JSON array, or a list of `{:name …}`
  maps; with it off, 6, all for the same. Nothing in the reach text shows a schema, so why the
  text moves haiku there is not known; the runs kept no events log, and each
  repetition overwrote the last one's directory (`byj-gly.293`).

- **The refusal now says what to write.** It was the bare "input-schema must
  be a malli [:map ...] schema"; it now gives an example and names the two
  shapes haiku sent (`toolbox-test/a-schema-that-is-not-a-map-is-refused-with-what-to-write`).
  A check the next day (2026-10-08) — haiku, reach on, the four tasks it
  struggled with most, ×2 — solved 8/8 with 10 creates and **no** schema
  rejection, so the new refusal was never shown and the check confirms
  nothing about it. Those runs also took 32–55 s against the 63–282 s of the
  day before, so load or the model differed between the days, and the on/off
  gap above is weaker evidence than its numbers look
  (`results-ext-reach-on-fix.edn`, `byj-gly.293`).

Decision: **`:reach` stays off by default.** The bead's bar was that solved,
refusals, creates and time do not get worse, and for haiku three of the four
did. Results: `target/tool-authoring-eval/results-ext-reach-{off,on}.edn`.

With any tier in force, the model reads
`tool-lisp-language-extended.md` and `tool-composition-extended.md` in place of
the base prompts.

**Why on by default: measured.** `dev/brainyard/dev/tool_authoring_eval.clj`
ran eight authoring tasks (`dev/brainyard/dev/tool_lisp_ext_tasks.edn`)
through a live turn, once for each language. A task is solved when the tool the
model created returns the expected value on held-out args. "refused" counts
`tool$create` calls whose body the validator rejected. The eval ran on
2026-09-28, with one repetition for each model and a second for haiku:

| model | language | solved | refused | creates | tool calls | minutes |
|---|---|---|---|---|---|---|
| haiku | base | 6/8 | 11 | 34 | 55 | 38 |
| haiku | extended | 6/8 | 6 | 18 | 39 | 21 |
| sonnet | base | 8/8 | 1 | 9 | 34 | 4 |
| sonnet | extended | 8/8 | 0 | 8 | 31 | 3 |
| opus | base | 8/8 | 0 | 8 | 24 | 3 |
| opus | extended | 8/8 | 0 | 8 | 25 | 3 |

Both haiku repetitions together (16 runs per language):
- base: 12/16 solved, 21 refused, 58 creates, 107 calls, ≈73 min;
- extended: 13/16 solved, 13 refused, 33 creates, 77 calls, ≈40 min.

- **Sonnet and opus solve everything in either language.** They used the
  extensions unprompted: tier A in 3 of 8 bodies, B in 1 and C in 3. In the
  extended language they paid no refusals.
- **The extensions help haiku most, and in cost more than in solves.**
  - Refusals fall by ~40%, creates by ~43%, and wall time by ~45%.
  - Every base-language refusal was a form the base language lacks: a named fn
    or an unknown helper written for recursion, `clojure.edn/read-string`.
  - The extended runs had no refusals of that kind. What remained were
    unbalanced delimiters (`byj-gly.260`) and malformed input schemas.
- **By task:**
  - `edn-diff` needs an EDN reader and was solved 0/2 in the base language, 2/2
    extended.
  - `lint-plan` went from 0/2 to 2/2.
  - `safe-sum` went the other way, 2/2 to 0/2. Haiku assumed `parse-double`
    throws on junk and wrapped it in `try`. Both composition prompts now say
    that it returns nil.
- **Nothing was lost.** No task a model solved in the base language needed the
  base language's refusals, and in the extended language no model wrote a body
  that looped until its budget ran out.

### Repair (`byj-gly.260`)

A model indents a body the way it means it and then miscounts the closers at
the end of a line. So when a body's delimiters do not balance, `tool$create`
and `tool$validate` rebuild its closers from its indentation, the way
parinfer's indent mode does. samizdat's `samizdat.lisp/indent-repair` takes the
same approach (`tool-lisp/indent-repair`, gated by `tool-lisp/repair-body`):

- Each line's trailing run of closers is dropped. A line indented at column x
  closes every form opened at column x or to its right, at the end of the line
  of code before it. The last line closes whatever is still open.
- A repair changes only the closers at the end of a line. Some problems are
  not a matter of indentation, and those bodies are still refused:
  - a wrong closer in the middle of a line;
  - a line that starts by closing a form and then goes on;
  - a string left open;
  - a rebuilt body that does not read as one form.
- The repaired body is the one saved and run. The reply carries `:repaired
  {:note :lines}`: the note is `prompts/tool-lisp-repaired.md`, and the lines
  are the ones that changed, as they now read. brainyard's sandbox appends
  missing closers only at the end of the code, and does it silently
  (divergence `tool-lisp-indent-repair`).
- It is on by default. With `:tool-lisp-repair? false` such a body is refused.
  `tool$run` and loading from disk do not repair.

Measured by `dev/brainyard/dev/paren_repair_spike.clj` on every unbalanced
`tool$create` body from byj-gly.258's eval
(`dev/brainyard/dev/unbalanced_bodies.edn`: 26 bodies, 25 by haiku). Each
repaired body was created over its task's fixtures and run on the task's cases:

| unbalanced | repaired | valid once repaired | pass every case |
|---|---|---|---|
| 26 | 24 | 17 | 14 |

- **Refused (2):** sonnet's one-line body with a wrong closer in the middle of
  the line, and a body whose strings were escaped twice (`\".md\"`), which
  still does not read once rebuilt.
- **Repaired, one line each (24).** Of these:
  - 7 were refused for something else as well: a malformed input schema or
    tool name.
  - Of the 17 valid ones, 3 fail their cases, each for its own logic, checked
    by hand. Two use a regex that matches a literal backslash (`#"\\."`), so
    every path came out as "none". The third was cut off before its `letfn` had
    a body.
  - No repair re-parented code.

### Evaluation

`brainyard.tool-lisp/run` interprets the form — there is no `eval` path, and a
ratchet test pins it. A user tool's `:fn` runs the interpreter, calling other
tools through `tools/call-tool` (param coercion, cancellation rethrown).

- **Bounded.** A step budget (200000) and a tool-call cap (128) per call,
  overridable by `:tool-lisp-budget` in config; `range`/`repeat` may produce at
  most 1000000 elements, because the step budget meters interpretation, not work
  inside an allowed function; user tools may nest at most 16 deep.
- **Whole results.** A tool called from a body runs with `:max-output-chars`
  set to `:tool-lisp-output-chars` (1,000,000): that cap is the prompt's, and
  inside a body no model reads the result — only the body's value comes back,
  capped as any result is. At the prompt's 16,000, `read-file` handed a body
  the first page of a large file, numbered, and a body cannot loop to read on
  (`byj-gly.243`). brainyard's code channel likewise gets a tool's result
  whole (`toolbox-test/a-body-reads-a-file-whole`).
- **Cancellable.** A tool dispatch runs on a `blk` thread (RFC-009); the
  interpreter checks for an interrupt at every step, and a primitive it called
  (`bash` as `process-task`) dies with a cancelled turn.
- **Typed strings.** String functions reject non-strings and name the value
  (jolt's `clojure.string` accepts a map where the JVM would throw).
- **Errors.** A failing tool returns its error map to the body, which may test
  it. A runtime error in the body — budget, type, a call to a tool that no longer
  exists — fails the whole call as `{:error-message "Tool Lisp: …"}`.

### Static analysis

At `tool$create` and at load, from the form alone:

- **calls** — `tool-refs`: every tool the body names, locals excluded;
- **no recursion** — a tool may not reach itself through its calls; the message
  names the cycle (`user$tool$words → user$tool$stamp → user$tool$words`);
- **effects** — a primitive's or command's declared `:effects`; a user tool's
  are the union over its calls (`#{:fs/read :fs/write}`); `tool$test`
  declares `:dynamic`.

## API

`brainyard.tool-lisp`: `read-body`, `validate`, `tool-refs`, `run`, `ops`,
`allowed-function-names`.

`brainyard.toolbox`: `registry`, `compile-tool`, `load-user-tools!`,
`authoring-tools`, `effects`, `tools-dir`, `normalize-params`, `malli->params`.

The commands — brainyard's `tool-agent$*`, renamed `tool$*`, with `tool$test` and
`tool$run` added:

| command | args | returns |
|---|---|---|
| `tool$create` | `name body` + `description input-schema output-schema` | `{:id :name :persisted :effects}` or `{:error "Invalid tool definition:\n- …"}` |
| `tool$validate` | as create | `{:valid true :calls :effects}` or `{:error …}` — nothing saved |
| `tool$list` | — | `{:tools [{:id :description :effects}]}` |
| `tool$read` | `name` | `{:id :name :description :input-schema :output-schema :body :calls :effects}` |
| `tool$delete` | `name` | `{:deleted :dependents}` — tools still calling it |
| `tool$test` | `name args` | the tool's own result |
| `tool$run` | `body args?` | the body's value; `{:error "Invalid Tool Lisp:\n- …"}` or `{:error-message "Tool Lisp: …"}` |

### One-off composition: `tool$run`

brainyard's CoAct agent composes tool calls in a code block; this port has no
code channel, by decision (divergence `tool-only-signature`). `tool$run` is its
replacement over the tool channel: a Tool Lisp `(fn [args] …)` read, validated
against the live registry (every problem at once) and run once with `args`
(default `{}`), exactly as a user tool's body runs — Tool Lisp's budget, the
nesting limit, cancellation, and the tool hooks on every call it makes, so a
gate applies inside it (RFC-011). Nothing is persisted. `:effects #{:dynamic}`.
Bound when `:tool-run?` (default on).

**Measured** (`brainyard.dev.tool-run-eval`, 2026-09-17, one run per cell): four
multi-step tasks over generated files — the largest of 8 docs by lines, the sum
of a JSON field over 10 files, how many of 12 notes mention "urgent" in any
case, the longest line across 6 logs — through the unmodified loop, `tool$run`
bound or not, prompts never naming it. Totals over the four tasks:

| tools | model | `tool$run` | correct | iterations | prompt chars | tool calls | `tool$run` calls |
|---|---|---|---:|---:|---:|---:|---:|
| with `bash` | sonnet | without | 4/4 | 8 | 190,525 | 4 | — |
| | | with | 4/4 | 9 | 219,281 | 5 | 1 |
| | haiku | without | 4/4 | 11 | 263,304 | 8 | — |
| | | with | 4/4 | 11 | 271,640 | 9 | 1 |
| no `bash` | sonnet | without | 4/4 | 11 | 277,342 | 28 | — |
| | | with | 4/4 | 11 | 264,966 | 7 | 3 |
| | haiku | without | 4/4 | 15 | 404,486 | 21 | — |
| | | with | 4/4 | 13 | 349,392 | 9 | 4 |

- **With `bash`, the shell is the composition channel.** Both models answered
  most tasks with one `bash` pipeline (`wc -l`, `jq`/`awk`, `grep -il`) and
  mostly ignored `tool$run`; each used it once, for the JSON sum, at the cost of
  an extra iteration. What `tool$run` adds there is its listing (~1,100 chars a
  call).
- **Without `bash`, `tool$run` is what the models reach for.** Tool calls fall
  from 28 to 7 (sonnet) and 21 to 9 (haiku), prompt characters by 4% and 14% —
  the file contents stop passing through the prompt (the longest-line task:
  86K → 72K) — and haiku saves two iterations. Without it, haiku composed the
  JSON sum by writing a user tool with `tool$create` and running it with
  `tool$test`: the need is real, and `tool$run` is the direct form of it.
- Correctness was equal everywhere; one run per cell, so differences of an
  iteration are within noise, while the tool-call counts are not.

**Decision:** on by default. It costs little while `bash` is bound, and it is
the composition path when `bash` is not — which is what a permission gate that
denies or asks for `bash` (RFC-012) will make common. No measured gap calls
for scratch values across iterations, so none is filed.

**Guiding the model to it** (`byj-gly.242`, 2026-09-27). Re-measured on today's
loop, the models used `tool$run` in none of 16 cells, `bash` or not: they did
each step by hand — up to 12 `read-file` calls in one turn. The prompt never
said when it pays. The instructions' heuristics have no row for dependent steps
or post-processing — brainyard's code channel had that job, and nothing
replaced it — and `tool$run`'s description sent the model to `tool$create`'s
for the language: in a live run the model looked that up, 4,400 characters,
before writing a line. Three changes, all in resources:

- `### Composing Tools` (`prompts/tool-composition.md`), in the prompt only when
  `tool$run` is bound: what stays inside a body, when a composite beats a call
  per step, and when it does not (a single call; what one shell command does).
- `tool$run`'s description is self-sufficient (`prompts/tool-run.md`): when to
  use it, a worked example — which a test runs — and the whole language, now
  `prompts/tool-lisp-language.md`, shared with `tool$create`.
- The instructions' `(search "keyword")`, code-channel syntax, says
  `list-tools` and `get-tool-info`.

`ONLY_WITH=1 LABEL=before|after jolt -A:dev -m brainyard.dev.tool-run-eval sonnet
haiku`, with and without `bash`, the cells with `tool$run` bound, one run each:

| tools | model | prompts | correct | iterations | prompt chars | tool calls | `tool$run` calls |
|---|---|---|---:|---:|---:|---:|---:|
| with `bash` | sonnet | before | 4/4 | 10 | 222,538 | 6 | 0 |
| | | after | 4/4 | 8 | 180,649 | 4 | 0 |
| | haiku | before | 4/4 | 13 | 289,146 | 20 | 0 |
| | | after | 4/4 | 15 | 355,851 | 12 | 3 |
| no `bash` | sonnet | before | 4/4 | 13 | 297,955 | 23 | 0 |
| | | after | 4/4 | 13 | 304,153 | 10 | 3 |
| | haiku | before | 4/4 | 10 | 235,788 | 18 | 0 |
| | | after | 4/4 | 11 | 263,753 | 17 | 2 |

- **Adoption moved:** `tool$run` in 8 of 16 cells, from none, and tool calls
  fell from 67 to 43. sonnet with `bash` still answers every task with one
  pipeline, which is what the section tells it to do.
- **Prompt characters and iterations did not.** The section adds ~800
  characters to every request (the longer description only when
  `get-tool-info` fetches it), and the fixtures are
  small — twelve JSON files of a line each cost little to read one by one — so
  what a body keeps out of the prompt here does not pay for the words
  describing it. Most `tool$run` turns also listed the directory with a call of
  its own before composing, an iteration the example (which lists inside the
  body) was meant to save.
- Correctness 4/4 everywhere. One run per cell: an iteration either way is
  noise; the tool-call counts are not.

**At scale** (`byj-gly.243`, 2026-09-27): the fixtures 20 times the size
(`SCALE=20`: a 1,200-line log, 880-line docs), without `bash`, where `tool$run` is
the composition path, two runs a cell:

| model | run | correct | iterations | prompt chars | tool calls | `tool$run` calls | composing turns that listed first |
|---|---|---:|---:|---:|---:|---:|---:|
| sonnet | before | 8/8 | 29 | 713,621 | 31 | 10 | 4 of 5 |
| | after | 8/8 | 26 | 666,513 | 23 | 6 | 4 of 6 |
| haiku | before | 6/8 | 24 | 592,988 | 27 | 9 | 2 of 5 |
| | after | 7/8 | 23 | 566,362 | 25 | 6 | 2 of 5 |

- **At this size the models compose unprompted** — `tool$run` in 10 of 16 cells
  before any change here — and **it was wrong because the kernel was.** Inside
  a body `read-file` paged at the prompt's cap: haiku's longest-line bodies
  answered from line 251 of 1,201, both runs. Bodies now read whole (above):
  longest-line 0/2 → 2/2.
- A body that relied on `:total-lines`, which only a paged read carried, read 0
  for every file once reads were whole, and named the wrong document — the one
  miss after. `read-file` now returns it on every read; three re-runs of that
  cell: 3/3.
- **The listing-first habit did not move.** A line in the Composing Tools
  section saying to find inside the body too changed nothing (4/5 → 4/6, 2/5 →
  2/5) and was taken out: ~250 characters on every request for no measured
  effect. The example in `tool$run`'s description, which lists inside its body,
  says so in a line of its own. sonnet also looked `tool$run` up with
  `get-tool-info` before its first call in 6 of 8 cells: the section says the
  language is in the description, and it goes to read it (`byj-gly.244`).

**The section carries the language's essentials** (`byj-gly.244`,
2026-09-27). What a model needs to write a first body without looking anything
up went into `### Composing Tools`: a tool is a function of its argument map,
the functions to reach for, how `max-key` is called, `json/read` and
`parse-long` for text, what is refused and what to use instead (Java interop,
`read-string`, `case`, `loop`), and one example body a test runs
(`toolbox-test/the-composing-sections-example-runs`). The whole function list
stays one `get-tool-info` away. The section is ~2,000 characters on every
request where it was ~800. The harness now also counts lookups of `tool$run`
and refused bodies. `SCALE=20 NO_BASH=1 REPS=2`:

| model | section | correct | iterations | prompt chars | tool calls | `tool$run` calls | lookups | refused bodies |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sonnet | pointer to the description | 8/8 | 26 | 638,725 | 19 | 8 | 3 | 2 |
| | essentials, first cut | 8/8 | 21 | 509,615 | 15 | 6 | 2 | 0 |
| | essentials | 8/8 | 19 | 448,116 | 11 | 7 | 0 | 1 |
| haiku | pointer to the description | 8/8 | 30 | 975,579 | 55 | 7 | 0 | 5 |
| | essentials, first cut | 7/8 | 25 | 626,213 | 25 | 11 | 0 | 6 |
| | essentials | 8/8 | 23 | 559,873 | 13 | 9 | 0 | 1 |

- **Refused bodies were the larger cost, not lookups.** haiku never looked
  `tool$run` up; it wrote a body, was refused (`read-string`,
  `Double/parseDouble`, `case`), and tried again, up to three times a turn.
  The first cut named functions to use but not what to use for JSON, numbers
  or branching, and refusals did not fall. Naming the refused forms with their
  replacements did: 5 → 1.
- The first cut listed `max-key` without its call, and haiku wrote
  `(max-key second pairs)` — the one wrong answer. The section now shows
  `(apply max-key second pairs)`.
- Against the pointer: iterations 26 → 19 and 30 → 23, prompt characters down
  30% and 43% despite the longer section, correctness unchanged. Two runs a
  cell; one of haiku's baseline cells alone was 354,000 characters, so the
  prompt figures are the softest.
- **On small inputs too** (`byj-gly.245`, `SCALE=1`, the same cells): the
  ~1,200 characters the essentials add to every request were the worry, where
  a body saves less. They pay there as well, because the saving is iterations,
  not file contents — every lookup and every refused body is a whole request:

  | model | section | correct | iterations | prompt chars | tool calls | lookups | refused bodies |
  |---|---|---:|---:|---:|---:|---:|---:|
  | sonnet | ~800 chars, pointer | 8/8 | 29 | 706,032 | 23 | 5 | 1 |
  | | ~2,000 chars, essentials | 8/8 | 19 | 448,565 | 11 | 0 | 1 |
  | haiku | ~800 chars, pointer | 8/8 | 31 | 790,705 | 60 | 0 | 4 |
  | | ~2,000 chars, essentials | 8/8 | 20 | 472,122 | 10 | 0 | 0 |

  The pointer section is `07a291f`'s, swapped in for the run and restored. The
  essentials stay in the section rather than moving into `tool$run`'s
  description, where reaching them costs the lookup this removes.

## Tool distillation: a `tool$run` body that worked becomes a proposal

(`byj-gly.249`, `brainyard.tool-distill`.) A body that ran is validated,
executed code. Generalised — its literals lifted into arguments, named,
described — it is a user tool, and unlike a skill draft it can be **proved**
before a person sees it. brainyard has no tool distiller (divergence
`tool-distillation`); the shape is skill distillation's (RFC-017): a check that
costs nothing, a judge, a staged proposal, a person's decision at `/proposals`.

- **Candidates** — every `tool$run` in a turn that answered, whose result was a
  value and not an error or a refused body (`candidates`). A body's **shape**
  is its form with every string and number literal a placeholder
  (`body-shape`): `(list-dir {:path "docs"})` and `(list-dir {:path "src"})` are
  one shape. A `#(…)` literal's parameters, which the reader names `p__N#`
  with N counting every one read in the process, are numbered by where they
  first appear in the body instead; until they were, no two `#(…)` bodies
  were ever one shape, and none was distilled (byj-gly.426).
- **When** — `auto-signal`, free: `:repeated-body`, two runs of one shape; or
  `:multi-tool-body`, a run that called two or more distinct tools. Thresholds
  are the tree node's opts.
- **Proof** (`prove`) — the draft is compiled against the live registry
  (`toolbox/compile-tool`: the language, both schemas, no self-reference). If
  everything it can reach only reads (`:fs/read`), it is run again with the
  judge's replay arguments for each source run and must give back what that
  run gave — whole, or for a result the prompt cut, the same size and start.
  The replay has no permission channel, so a call that would ask a person is
  refused, not asked. Verified, or unproved because it could write, run a
  process or call out, it is staged; one that does not compile or does not
  reproduce its run is not.
- **Staging** — `<project>/.brainyard/tools/proposals/<name>/`: `tool.edn`,
  `body.clj` and `proposal.edn` (score, rationale, sources, proof). The user
  tool loader reads `<name>.edn` files and passes over the directory.
- **The judge** — `predictors/session/distill-tool.edn`, run by
  `:session/tool-distill` (RFC-004) with the candidate runs numbered, the user
  tools there are, and the Tool Lisp reference. It returns the tool — name,
  description, `input-schema`, `output-schema`, a body with the literals that
  differed read from `args` — and `replay-args`, one `{:run N :args {...}}` per
  run it generalised. Its `output-schema` must be a malli schema: prose is
  outcome `:prose-output-schema` and is not proved. `outcome` decides at
  `:tool-distill-threshold` (0.7) whether the draft is proved at all; `:enable-tool-distillation` (off) gates
  the whole of it. The person is told as it is staged, with whether it was
  replayed (`tool-distill-test/a-body-run-twice-becomes-a-proved-proposal`).
- **Accepting** — `/proposals accept tool/<name>` writes it where `tool$create`
  writes (`toolbox/persist!`), and the rebuilt session loads it as
  `user$tool$<name>`, compiling it against everything then bound. A user tool
  of that name is never replaced from here.

### Is a distilled tool used? (`byj-gly.249.4`, 2026-09-27)

`brainyard.dev.tool-reuse-eval`: one session per task pair (line counts, a JSON
sum, the largest file, files mentioning a word), `bash` not bound, tool
distillation on. Turn A does the job on one input, asked to use `tool$run`; the
staged tool is accepted through `/proposals`, as a person would; turn B asks for
the same job on another input, plainly. Two runs a pair, sonnet and haiku, 16
sessions a row:

| model | prompt | tool staged after one run, accepted | turn B called it, when accepted | turn B correct |
|---|---|---:|---:|---:|
| sonnet | as shipped | 6/8 | 6/6 | 8/8 |
| | with a "saved tool first" line | 3/8 | 3/3 | 8/8 |
| haiku | as shipped | 3/8 | 2/3 | 8/8 |
| | with a "saved tool first" line | 4/8 | 1/4 | 7/8 |

- **Once accepted, sonnet uses the tool every time** (9 of 9) — including in
  the case that looked like a miss in a live run, which one session could not
  tell apart from noise.
- **haiku reuses about half the time** (3 of 7) and writes the body again
  otherwise. A line in Composing Tools saying to look for a saved tool first did
  not change that, and was not kept: ~200 characters on every request for
  nothing measured (`byj-gly.251`).
- **After a single run, a tool is staged about half the time** (16 of 32), and
  the rate swung from 6/8 to 3/8 for sonnet with nothing in the judge changed:
  one run is weak evidence, and the judge weighs it differently each time. The
  second run — a repeated shape — is the stronger signal, and in most sessions
  the judge after turn B staged what the judge after turn A did not.
- Turn B was right 31 of 32 times, with a tool or without. Two runs a cell
  throughout; every rate here is a small sample.

### Re-measured: haiku's misses were a bug (`byj-gly.251`, 2026-09-28)

`STAGE=fixed` takes the judge out. Between the turns, the pair's tool is
accepted as a person would accept it. Each pair has one tool the judge staged
in an earlier run, with the name and description it wrote. So every session
tests turn B, where the judged mode tested it in only half its sessions. The
`live` column checks the accept's own reply, not the command that was sent.

The first run found json-sum's tool reused 0 of 8 times, in every prompt
variant, and it was not haiku's choice:
- The judge had written `:output-schema :number`, which is not a malli
  schema.
- `/proposals accept` wrote the tool, then threw while rebuilding the
  listing.
- A command that fails falls through the session tree to an agent turn. So
  haiku was asked `/proposals accept …` as a question, and answered that there
  was nothing to accept (provenance P-23).

byj-gly.249.4's run accepted `sum-json-field` twice with the same `:number`,
and haiku never called it. The bug accounts for those misses.

With the bug fixed, haiku, two runs a cell. Line-counts, largest and urgent
come from the first run; json-sum was re-run:

| prompt | turn B called the accepted tool |
|---|---:|
| as shipped | 6/8 |
| `:composition-saved-tools?` (C): Composing Tools names the saved tools, with their arguments | 5/8 |
| `:compact-saved-tool-args?` (L): the tool's listing line carries its arguments | 6/8 |
| both | 8/8 |

- Once the tool is in force, haiku reuses it about as sonnet does. Turn B was
  correct in all 8 json-sum re-run sessions; in the first run's other three
  pairs, the only wrong answers were both json-sum.
- L+C's 8/8 against 6/8 is two sessions, and C alone did no better. Both are
  kept, off, as config keys for a larger measurement to settle.
- `NO_A` runs turn B in a session that never ran turn A. With the bug still
  in, json-sum was not reused there either, so the history of turn A was not
  the cause.

## Converting brainyard's user tools

Nothing to convert: `load-user-tools!` reads brainyard's files as they are
(`toolbox-test/brainyards-own-tool-files-load-and-run` runs copies of all three).
A body that uses SCI-only features — host interop, `def`, recursion — is skipped
at load with its problems listed, and stays unported until rewritten.

## Authoring evidence

Before building (`byj-gly.28`), the question was whether a model with **no
prior knowledge** of Tool Lisp can define working composites, knowing the
language only from `tool$define`'s description.
`dev/brainyard/dev/tool_lisp_spike.clj` answers it with a minimal validator and
interpreter, `tool$define` and `tool$test` added to the real ReAct loop, and
ten tasks (`dev/brainyard/dev/tool_lisp_tasks.edn`) — from brainyard's three
existing tools through composition over composites, multi-tool pipelines, JSON,
and a literal-substring count that punishes regex habits. Every task's reference
solution passes first (harness check). 2026-09-17, `claude -p`, 2 repetitions:

| | sonnet | haiku |
|---|---|---|
| runs | 20 | 20 |
| valid on the first `tool$define` | **20/20** | 14/17 |
| defined, at most one repair | 20/20 | **17/17** |
| every fixture case passes | **20/20** | 15/17 |
| provider failures (never reached Tool Lisp) | 0 | 3 |

**No definition was rejected for a Tool Lisp reason** — no unknown symbol,
forbidden form or bad tool argument in 37 submissions. The misses:

- **Reader errors (3 rejections, each repaired next attempt):** `#(...)` — an
  ordinary Clojure fn literal EDN cannot read — and two unbalanced bodies from
  writing long one-line code inside a JSON string.
- **Result shapes (2 wrong answers):** a body assumed `grep` returns `:output`
  (it returns `:matches`); another lower-cased the whole `read-file` result
  instead of its `:content` — which jolt's `clojure.string/lower-case` accepted
  silently where the JVM would throw, so the model's own `tool$test` looked
  right. Tool listings show inputs, not outputs.
- **Provider (3 runs):** haiku, inside `claude -p`'s inner agent loop, sometimes
  invoked `tool$define` as a *native* tool, got "No such tool available", and
  answered that the tool did not exist — our loop saw one well-formed answer.
  Confirmed from the CLI's raw events; a `claude-code` provider issue, not Tool
  Lisp (`byj-gly.29`).

### What changed because of it (all built)

1. **Bodies are read with the Clojure reader, `*read-eval*` false,** not EDN:
   `#(...)` and `#"..."` read, `#=` is refused (`toolbox-test/reading`).
2. **Every tool's `:output-schema` is shown** — primitive specs carry one, the tool
   listing prints it, `tool$create` asks for one.
3. **String functions check their argument types** and name what they got
   (`toolbox-test/runtime-guards`).
4. **Unbalanced bodies are reported with the line and column** of the innermost
   unclosed form (`toolbox-test/reading`). samizdat's repair is not ported.

### On the shipped implementation

Re-measured on what shipped (`brainyard.dev.tool-authoring-eval`, the same 10
tasks, one run each, 2026-09-17): the unmodified loop with the default registry,
so the model sees brainyard's command names (then `tool-agent$create`/`$test`,
since renamed `tool$*`) and `user$tool$<name>` ids, and
each tool is reloaded from `.brainyard/tools/` before its cases are checked.

| model  | runs | attempted | first definition valid | all cases pass | provider failures |
|--------|-----:|----------:|-----------------------:|---------------:|------------------:|
| sonnet |   10 |        10 |                     10 |             10 |                 0 |
| haiku  |   10 |         3 |                      3 |              3 |                 7 |

**Every attempted definition was valid on its first submission** — no reader
error, no rejection, no reload skip — and every one computed the expected
results. The four fixes above removed the spike's misses.

- sonnet `todo-report` first scored `{:count 6 :files 4}`: its body greps `.`,
  which in the eval directory also held the persisted `todo-report.{clj,edn}`.
  In a clean copy of the fixtures it returns the expected `{:count 3 :files 2}`;
  the harness now checks cases in a clean copy. `grep` and `search` now skip
  `.brainyard/` (divergence `search-skips-agent-state`), so the same body would
  answer correctly today.
- haiku's provider failures rose from 3/20 in the spike to 7/10: the same
  native-tool_use failure (`byj-gly.29`) — answers say `tool-agent$create` is
  "not available". Not a Tool Lisp result.

After the provider fix (`byj-gly.29`, provenance P-12: the directive says listed
tools exist, and rejected native calls are recovered), haiku was re-run once
with the commands renamed `tool$*`:

| model | runs | attempted | first definition valid | all cases pass | provider failures | recovered iterations |
|-------|-----:|----------:|-----------------------:|---------------:|------------------:|---------------------:|
| haiku |   10 |        10 |                      8 |             10 |                 0 |                    0 |

No iteration needed recovery, so in this run the directive wording alone
removed the failure; recovery is the backstop, pinned by
`agent-test/native-tool-calls-recovered`. The two definitions not valid first
time were unbalanced bodies (`count-words`, `todo-report`), each fixed from the
line-and-column message.

## Alternatives considered

**A step pipeline** — `:steps [{:as :r :tool "read-file" :args {:path [:arg :path]}} …]`.
Graph-shaped and trivially analysable, but every real body needs a transform
between steps (`split-lines`, `count`, a conditional), so it needs an expression
language anyway — and then the pipeline is `let` with extra syntax.

**Host `eval` with an OS sandbox** (samizdat's cells). Right for code the
*repository* ships; wrong for the object the agent writes most.

## Invariants

| invariant | enforced by |
|---|---|
| A spec's `:arg-aliases` are renamed before its schema is checked; an alias and its canonical name with different values are refused, naming both; a missing argument's problem names its aliases; a turn records the call as sent. | `agent-test/update-file-takes-the-names-models-send`, `agents-test/ask-takes-the-name-the-dispatch-result-gives` |
| A `tool$run` body has a shape its literals do not change, nor the reader's numbering of a `#(…)` literal's parameters; only a run that worked, in a turn that answered, is a candidate; a judge is called when a shape repeats or a body composes tools. | `tool-distill-test/a-body-has-a-shape`, `tool-distill-test/only-a-run-that-worked-in-a-turn-that-answered-is-a-candidate`, `tool-distill-test/a-judge-is-called-when-a-body-repeats-or-composes` |
| A draft is staged only when it compiles and, if it only reads, reproduces its runs; one that could do more is staged unproved and never run for the judge. | `tool-distill-test/a-draft-is-proved-before-it-is-staged` |
| Off by default; on, a body run twice in one shape is judged once, proved against both runs and staged, the person told; a draft that does not reproduce its runs is not staged. | `tool-distill-test/tool-distillation-is-off-until-asked-for`, `tool-distill-test/a-body-run-twice-becomes-a-proved-proposal`, `tool-distill-test/a-draft-that-does-not-reproduce-its-runs-is-not-staged` |
| A tool proposal waits at /proposals with its proof, becomes a user tool when accepted, and never replaces one. | `tool-distill-test/a-proposal-waits-at-the-gate-and-becomes-a-user-tool` |
| No user-tool body reaches host `eval`. | `toolbox-test/bodies-never-reach-host-eval` (no `eval`/`load-string`/`load-file` in either namespace) |
| `#(…)` and `#"…"` read; `#=` is refused; unbalanced bodies name the position. | `toolbox-test/reading` |
| A body that does not balance has the closers at the end of its lines rebuilt from its indentation. A line that already closes right is unchanged. A wrong closer in the middle of a line, a line that closes and goes on, an open string, or a rebuild that does not read stays refused. `tool$create` saves and runs the repaired body and lists the lines it changed; `:tool-lisp-repair? false` refuses. | `toolbox-test/an-unbalanced-body-is-repaired-from-its-indentation` |
| Every problem is reported at once: forbidden forms, interop, unknown symbols, bad tool-call params (the base language). | `toolbox-test/validation` |
| With `:tool-lisp-extensions []` the base language refuses what the tiers add, and each tier alone adds only its own. | `toolbox-test/the-base-language-refuses-what-the-tiers-add` |
| The extended vocabulary, forms and control evaluate as Clojure. `try` keeps going past a failure, and `throw` stops with a reason. A catch does not swallow an exhausted budget. | `toolbox-test/extended-forms-evaluate-as-clojure` |
| Special forms, destructuring and tool calls evaluate as in Clojure. | `toolbox-test/evaluation` |
| String functions reject non-strings; step, generator and tool-call budgets hold; an interrupt stops a body. | `toolbox-test/runtime-guards` |
| brainyard's three tool files load and return brainyard's results. | `toolbox-test/brainyards-own-tool-files-load-and-run` |
| A created tool is persisted, callable at once, listed with its `:output-schema`, and reloads. | `toolbox-test/create-persist-reload` |
| A body on disk is laid out to 80 columns, reads back as the same form, and is kept as written when it holds a comment, `#_` or metadata | `toolbox-test/a-body-is-kept-laid-out`, `toolbox-test/create-persist-reload` |
| An `:input-schema` that is not a `[:map …]` is refused with an example of one, and says when it is a list of parameter maps or malli with its keywords as strings; it is never rewritten into a schema. | `toolbox-test/a-schema-that-is-not-a-map-is-refused-with-what-to-write` |
| A tool being made has a malli `:output-schema` or none: `tool$create`, `tool$validate` and a distilled draft refuse prose; one on disk still loads. | `schema-test/a-user-tools-output-schema-is-checked-by-tool-test`, `tool-distill-test/a-verdict-becomes-a-draft` |
| Composites compose; effects are the union; no tool reaches itself; a deleted dependency fails its dependents as a value. | `toolbox-test/composition-effects-and-cycles` |
| Loading resolves dependency order and skips bad files. | `toolbox-test/loading-resolves-dependency-order-and-skips-bad-files` |
| A turn can define a tool and call it; the next turn lists it. | `toolbox-test/a-turn-defines-a-tool-then-uses-it` |
| A user tool calling a tool of an MCP server still starting, directly or through another user tool, waits rather than being skipped as malformed. | `mcp-test/a-user-tool-waits-for-the-server-it-calls` |
| A cancelled turn stops a user tool and kills its children. | `toolbox-test/a-cancelled-turn-stops-a-composite-and-its-children` |
| An `:output-schema` that malli does not know (`:number`) is kept as its text, is shown as written, and holds no result to it. | `toolbox-test/a-schema-malli-does-not-know-is-kept-as-text` |
| Off by default: a user tool is listed by name and description. `:compact-saved-tool-args?` puts its arguments on its line, and `:composition-saved-tools?` names the saved tools in Composing Tools, after the word on `tool$run`. | `config-test/saved-tools-are-named-with-their-arguments` |
| Tier D: `pmap` is refused without `:reach`. With it, `pmap` keeps input order, runs `:parallel` at a time, spends the body's tool-call budget, and throws a failure a catch can take (but not the budget). It is capped at 1000 items, and an interrupt cancels the calls still running. | `toolbox-test/reach-is-its-own-tier`, `toolbox-test/pmap-runs-a-bodys-calls-a-few-at-a-time` |
| With tier D on, Composing Tools says so, and not with it off. | `toolbox-test/the-store-is-bound-with-its-tier` |
| A body that reads the clock carries `:clock/read`, as does every tool that calls it, and a draft that reads it is staged unproved. | `toolbox-test/a-body-that-reads-the-clock-says-so` |
| The store keeps EDN data under string keys on disk, forgets a key put with no value, refuses what is not EDN, declares its effects, and takes concurrent puts from a `pmap` whole. It is bound, and read about, only with `:reach`. | `toolbox-test/a-store-keeps-state-between-calls`, `toolbox-test/the-store-is-bound-with-its-tier` |
| `tool$run` runs a body once over the registry with its args, reports every problem at once, returns runtime failures as values, applies tool hooks to nested calls, persists nothing, and goes with `:tool-run? false`. | `toolbox-test/tool-run-composes-once` |

## Known gaps

- Tier D is off by default: on the tasks that do not want it, its prompt costs
  haiku creates and time, for a reason not yet found — `byj-gly.293`.
- Predictor calls in bodies (`(predict "user/summarize" {…})`), which would make
  user tools DSPy programs over tools and predictors — `byj-gly.10`.
- User-scope tools (`~/.brainyard/tools`) are not loaded; project scope only, as in brainyard.
