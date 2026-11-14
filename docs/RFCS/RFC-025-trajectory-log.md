# RFC-025 — The trajectory log

**Status:** implemented (`byj-gly.18`) for recording and reading back. Export
and analytics are not ported; §"What is not here" names their beads.

## Purpose

Specifies what a turn writes down about itself. brainyard sources:
`agent/common/trajectory.clj`, `coact-store-results-action` in
`common/coact_agent.clj`; doc `docs/design/observability.md`.

## Scope

**This layer decides** the record's shape, where it is written, and what counts
as a turn having succeeded.

**It must not know** what any turn was for, or what reads the file afterwards.

**It hands** an append-only EDN file to whoever wants to know what the agent
did — a person, `program$build-dataset` (RFC-021), and eventually analytics.

## Model

### One record per turn, not one per event

The bead this closes asked for the `:on-event` stream persisted as an
append-only log. brainyard does something different and better, and this
follows it: the unit is the **turn**.

A raw event log is mostly `:agent.llm/chunk` — one event per streamed
fragment — and answers no question that the per-turn projection does not answer
more directly. What a reader wants is *what did this turn do*: the question,
each iteration with its reasoning and tool calls, the answer, how it ended, what
it cost. That is a record, not a stream.

```clojure
{:v 3
 :ts 1789901615247                    ; epoch ms at turn end
 :session "1789889886683-48e08677"
 :agent "react-agent"                 ; or a subagent's instance id
 :owner "root"                        ; a subagent's turn only: who dispatched it —
                                      ; another instance's id, or "root" (byj-gly.298.1)
 :question "what is in deps.edn"
 :success true
 :terminated-by :answer-channel
 :total-iterations 2
 :iterations [{:n 1 :channel "tool" :thought "I should look"
               :tools [{:name "list-dir" :args {:path "."} :result "…"}]}
              {:n 2 :channel "answer" :answer "a deps file"}]
 :model "mock-1" :provider :mock
 :usage {:in 1243 :out 287 :cache-read 900 :cache-write 300 :calls 2}
 :duration-ms 1420
 :permission-mode "auto"              ; the mode in force at the turn's end
 :loop {:hash "cdf9eecbbb3a"}         ; the tree the turn ran, expanded (byj-ng7.19)
 :jolt "v0.8.10"}                     ; the RUNTIME that produced the turn
```

**`:permission-mode` is the port's, and optional** (divergence
`trajectory-keeps-the-permission-mode`, byj-gly.288.49.9): the mode a person
last chose with shift+tab, else the config's, as the turn ended. A scorer reading the trajectory asks whether a change was made with
every call approved, as Claude Code's transcript says on every line; brainyard's record
has no such key. No `:v` bump, as for `:jolt`.

**`:loop` is the port's, and optional** (divergence
`trajectory-keeps-the-loop-hash`, byj-ng7.19): `{:hash}`, `flow/tree-hash`
of the tree the turn ran, expanded — a session's turn, the session tree; an
`ask`, its loop — the hash `byj flow check --json` gives a loop file as
`:expanded-hash`. A harness that chose the loop (`byj ask --loop FILE`) reads
from it that every trial ran that loop (RFC-071 "Promotion"). No `:v` bump.

`:in` is the **total** input — fresh, cached and newly-written tokens together
(RFC-006) — so the three buckets are `cache-read`, `cache-write` and whatever
is left. brainyard's `:in` is the fresh count alone, and a metric ported from
it verbatim counts every cache write as a fresh token
(divergence `usage-in-is-total`, `byj-gly.94`).

**`:jolt` is the runtime, and it is optional.** `deps.edn` declares
`:jolt/min-version`, which is a floor and not a pin, and the effect substrate
declares no floor at all — so the runtime under a turn can change with nothing
to say so, and once did, taking `via` binding conveyance with it
(`byj-gly.117`). A record now names the jolt that produced it, so a trajectory
read months later can be told apart from one produced on another runtime. An
**absent** `:jolt` means the `jolt.version` property was not set, not that the
runtime was unknown to matter. Adding it needs no `:v` bump: readers take keys
they know and a v3 record without it stays valid (`byj-gly.118`).

**There is no `:turn` key**, and that is deliberate in the original: a per-agent
turn counter restarts at 1 when a session resumes, so two records both claimed
to be turn 1. Position in the append-only file is the ordering.

**A turn someone else put in the session, and a note for people** (a shared
session, RFC-043; the port's). A turn injected over the session channel
carries `:injected-by {:user :name :role :approval}` (a session's
`{:session id}`). A note is a line of its own, `{:v 3 :kind :note :ts
:session :text :from}`, beside the turns it was said between and not one of
them: `read-trajectories` leaves notes out, `read-notes` reads only them, and
`read-entries` both. No `:v` bump: a reader of turns never sees a note.

**A question asked differently** (RFC-072, `byj-10y`). When the person accepts
a project's proposal to ask the question differently, `:question` is what the
turn worked on and `:asked` is what they asked, as `:command` keeps a slash
command beside the question it became. Optional, no `:v` bump.

`:provider` is recorded beside `:model` because the same model string is served
by more than one provider at different prices.

### The record keeps what the prompt was made to forget

`accumulate-action` writes each iteration to two places: `:iterations`, capped
at `:max-history-iterations` so the prompt stays affordable, and
`:trajectory-iterations`, uncapped. The record is built from the second. A log
that forgets the middle of a long turn cannot answer what the turn did, and the
reason the prompt forgets — token cost — does not apply to a file.

**A tree's own calls are in it too.** A tree that works through commands
rather than a model's tool calls, such as agent$init or agent$claude, runs
each command through `:tool/run`. That leaf appends the call to
`:trajectory-iterations` as a `"tool"` iteration, shaped like a model's
call: name, args, result, and whether it failed. It does not touch
`:iterations`, the history a prompt shows the model. Before, such a turn's
record was its answer alone, and the task score judged `/init` as an agent
that did nothing (`byj-gly.322`). The record is the engine's own
(`bt/record-keys`), not part of the node's contract.

Such a call says it was the tree's: `:node`, the id of the node that made
it, and `:slot`, the slot it ran in when an attachment made it (RFC-072).
A model's call has neither, and `trajectory/model-call?` tells the two
apart. A simulation's call count, its findings, and a loop evaluation's
and watch's tool errors and refusals are the model's. A lint attached after
every write is the loop working as made, not the model repeating itself
(`byj-8gc`).

### The answer is always read through `record-answer`

v3 keeps the answer in its terminal iteration; v2 kept it at the top. The file
is append-only, so both shapes live in it forever and
`(:answer record)` is wrong for v3 records. `record-answer` reads the last
`"answer"`-channel iteration and falls back to `:answer`.

The terminal iteration is synthesized by `with-answer-iteration` when no
iteration carried one — `ensure-answer` and `loop-fallback` write an answer into
st-memory *after* the loop. The synthesized entry has **no `:thought`**: there
was no deliberation, and inventing one would teach a reader (or a fine-tune)
that there was.

### Success is an allowlist

A turn succeeded if its answer is non-blank **and** it ended for a reason that
means it answered — `#{:answer-channel}`, which is what `stamp-answer` stamps.

brainyard names the failures instead. That is the same judgement while the list
of endings is complete, and the wrong default the moment it is not: this port
also stops on `:cancelled`, `:usage`, `:iteration-count`, `:loop-error` and
`:tool-blocked-by-hook`, every one of which is absent from brainyard's failure
set and would therefore have scored as a success (divergence
`trajectory-success-is-an-allowlist`).

### It is a node, not a hidden sink

`:react/store-trajectory` is the last action but one in
`resources/brainyard/loops/react.edn`, where brainyard puts
`coact-store-results-action`. Recording is part of what a turn *does*, so a loop
that should not record drops the line rather than rebuilding the kernel
(RFC-001). The prediction log is the other choice — an `:on-event` sink — and it
is the right one there, because it records a *predictor call*, which is not a
step in any loop.

### Writing it can never cost the turn

`append!` catches everything and returns nil. A turn that answered has already
done its work; losing the note about it must not undo that. Truncation is
brainyard's, per field: 4000 chars for a tool result or the question, 2000 for
reasoning, 50000 for the answer — one runaway `bash` result must not make a
record nobody can read back.

Reads skip unparseable lines for the mirror-image reason: the file is appended
to by a live process, so a reader can catch a half-written tail, and one torn
line must not cost the reader every record before it.

Appends take one process-wide monitor. A turn writes one line, so contention is
not worth optimising — but subagents run concurrently and share the parent's
session directory, and two half-lines interleaved make a record nobody can read.

## API

`brainyard.analytics`: `settings`, `outcome`, `iteration`, `latency`, `cache`,
`cost`, `tools`, `rework`, `health`, `analyze`, `commands`, `pct`.

`brainyard.cost`: `free`, `bundled`, `table`, `rates-for`, `strip-date`,
`normalize-bedrock`, `rates-at`, `of`, `add`, `summary`.

`brainyard.trajectory-export`: `iteration->messages`, `record->example`,
`serialize-lines`, `learnable?`, `export-file`, `export!`, `commands`, `formats`,
`turn-range-problem`, `in-turns`.
`brainyard.secrets`: `token-patterns`, `heuristic-patterns`,
`redaction-patterns`, `redact-str`, `redact`, `redacted?`, `tokens-in`.

`brainyard.trajectory`: `version`, `filename`, `max-field-chars`,
`max-thought-chars`, `max-answer-chars`, `trunc`, `project-iteration`,
`with-answer-iteration`, `record-answer`, `succeeded-by`, `succeeded?`,
`record`, `note-record`, `note?`, `log-file`, `append!`, `read-entries`,
`read-trajectories`, `read-notes`, `sessions`.

Leaf: `:react/store-trajectory` → `agent/store-trajectory-action`.

Config (RFC-013): `:trajectory-log?` (default **true**, brainyard's
`:enable-trajectory-recording`).

**On by default, where the prediction log is off.** That is not carelessness
about the same data: a session snapshot already saves the same questions and
answers under the same directory (`:persist-session?`, RFC-016, also on), so
this costs no exposure a project has not already accepted. What the prediction
log adds on top is the full model *inputs* — assembled prompts, recalled
memory — which is a decision a project makes for itself.

`:persist-session?` gates this too, and it has to, for the same reason. The
record is part of what a session leaves on disk, in the session's own
directory, so `--no-persist` meaning *save the session* but not *save the log
of the session* would be a distinction nobody asked for. It is also what keeps
the default honest: where there is no snapshot, the argument above does not
hold, and neither does the default.

## Invariants

| invariant | enforced by |
|---|---|
| What a turn leaves on disk — `trajectory.edn` and the session's `meta.edn` — is exported as `contract/trajectory/` and held to what byj writes (RFC-070 phase 2) | `contract-test/the-session-socket-and-the-trajectory-are-byjs-contract` |
| A turn appends exactly one record, with its question, answer, iterations, model, provider, usage and duration. | `trajectory-test/a-turn-records-what-it-did` |
| A turn's record says which loop it ran, by the hash `flow check` gives that loop's file expanded | `trajectory-test/a-turn-records-which-loop-it-ran` |
| Turns append; position is the ordering. | `trajectory-test/a-second-turn-appends-rather-than-replacing` |
| The record keeps every iteration, though the prompt is capped. | `trajectory-test/the-record-keeps-what-the-prompt-was-made-to-forget` |
| A `:tool/run` leaf's call is in the record, failed or not, and not in the prompt's history; agent$init's record holds its commands. | `flow-test/a-leaf-can-be-a-tool`, `init-test/init-sets-up-a-project-and-reports-what-it-did` |
| A tree's call says its `:node`, and its `:slot` when an attachment made it; a model's says neither. | `agent-test/a-call-the-loops-tree-made-says-so-in-the-record` |
| An answer written after the loop still gets a terminal iteration, and it claims no reasoning. | `trajectory-test/a-turn-that-answered-outside-the-loop-still-has-an-answer-iteration` |
| The answer reads the same from a v2 or a v3 record. | `trajectory-test/the-answer-is-read-the-same-way-whatever-wrote-the-record` |
| **An ending nobody classified is not a success.** | `trajectory-test/a-record-says-how-the-turn-ended-and-whether-that-counts-as-success` |
| One runaway field does not make an unreadable record. | `trajectory-test/one-runaway-result-does-not-make-a-record-nobody-can-read` |
| **A record that cannot be written does not cost the turn**, and one torn line costs only itself. | `trajectory-test/a-record-that-cannot-be-written-does-not-cost-the-turn`, `trajectory-test/a-torn-line-costs-only-itself` |
| A record is `trajectory/record-schema`'s shape — the writer's contract the readers rely on. Reading is not gated on it: a torn line is skipped, and brainyard's own v2 records are read too (byj-gly.204). | `trajectory-test/a-turn-records-what-it-did` |
| **A cache write is neither fresh input nor a cache miss.** | `analytics-test/a-cache-write-is-neither-fresh-nor-a-miss`, `analytics-test/the-cost-score-follows-the-corrected-hit-rate` |
| **A cached token is priced as cached**, and what the provider billed beats the table. | `cost-test/a-cached-token-is-not-charged-as-a-fresh-one`, `cost-test/what-the-provider-billed-beats-what-a-table-guesses` |
| **An unpriced call costs nil, not zero**, and the record omits a cost it cannot state. | `cost-test/an-unpriced-call-costs-nil-not-zero`, `cost-test/the-record-omits-a-cost-it-cannot-state` |
| A free provider reports 0.0 and is not confused with an unpriced one. | `cost-test/free-is-an-answer-where-unknown-is-not` |
| A model is found by its id, then its shape; a project can correct a price without a rebuild. | `cost-test/a-model-is-found-by-its-id-then-by-its-shape`, `cost-test/a-project-can-correct-a-price-without-a-rebuild` |
| A broken price file costs a number, not a turn; every bundled rate is usable. | `cost-test/a-broken-price-file-costs-a-number-not-a-turn`, `cost-test/every-bundled-price-is-a-usable-rate` |
| **A failed tool call is recorded as failed**, from what the tool layer knew — not from its result text. | `analytics-test/the-error-rate-is-carried-not-pattern-matched`, `trajectory-test/a-turn-records-what-it-did` |
| **A metric with no data is shared out, not scored zero**, and the score says which it used. | `analytics-test/a-metric-with-no-data-is-shared-out-not-counted-as-zero` |
| **An unpriced turn is not averaged in as free**, and the exclusion is reported. | `analytics-test/an-unpriced-turn-is-not-averaged-in-as-free` |
| A session that ended badly scores below one that recovered from the same rate. | `analytics-test/outcome-counts-what-answered-and-where-it-stopped` |
| A question that came back after a failure is reported as rework. | `analytics-test/rework-finds-a-question-that-came-back` |
| Every sub-score stays inside 0–100, however bad the turns. | `analytics-test/a-metric-with-no-data-is-shared-out-not-counted-as-zero` |
| A project can disagree about what good means, and a broken override leaves the defaults. | `analytics-test/a-project-can-disagree-about-what-good-means` |
| A loop that should not record drops the node; the flag turns the file off. | `trajectory-test/a-loop-that-should-not-record-drops-the-node` |
| An export is the OpenAI shape, one line per turn, and its answer carries no deliberation. | `trajectory-export-test/a-turn-becomes-the-shape-a-fine-tune-eats`, `trajectory-export-test/an-export-is-one-line-per-turn` |
| An answer is not learned twice, and two turns' call ids do not collide. | `trajectory-export-test/an-answer-is-not-learned-twice`, `trajectory-export-test/call-ids-are-unique-across-turns` |
| **Secrets are redacted on the way out, in both formats**, and the log still holds what the turn said. | `trajectory-export-test/secrets-are-redacted-on-the-way-out`, `trajectory-export-test/the-lossless-format-is-not-the-leaky-one` |
| Refusing a write and redacting an export do not use the same list. | `trajectory-export-test/refusing-and-redacting-do-not-use-the-same-list` |
| A turn with nothing after its question is not an example. | `trajectory-export-test/a-turn-with-nothing-in-it-is-not-an-example` |
| `:turns [from to]` exports only those turns, 1-based and inclusive, counted among a session's turns (notes are not turns); a range that is not one is refused. | `trajectory-export-test/only-the-turns-asked-for-are-exported` |
| **A run told to leave nothing behind leaves no record either.** | `trajectory-test/a-run-told-to-leave-nothing-behind-leaves-no-record-either`, `persist-test/persistence-can-be-switched-off` |

## Exporting it

`trajectory$export` writes `.brainyard/exports/trajectories-<ms>.{jsonl,edn}`.
`openai-jsonl` is one `{"messages": [...]}` per turn in the OpenAI tool-calling
shape; `edn` is the records themselves, lossless.

Three things the projection decides, all brainyard's:

- **The answer is the answer, not the thinking.** An answer-channel iteration
  becomes one assistant message carrying the answer and *not* the `:thought` —
  an example whose completion is deliberation teaches a model to answer with
  its deliberation.
- **The answer is not learned twice.** It is appended as its own message only
  when no answer-channel iteration carried it, which is the v2 shape.
- **A tool call's id is `call_<position>_<n>_<i>`**, where position is the
  record's place in the export rather than a field of it — v3 has no `:turn`
  (above), and reusing one would collide across sessions.

**A range of turns.** `:turns [from to]` (the command's `turns`) exports only
those turns of each session, both ends included. A turn's number is its
1-based position among the session's turn records — the ordering this file
already has, since there is no `:turn` — so a note between two turns does not
shift the numbers. It is what a recorded review (RFC-043, `byj-share record
--turns 12-30`) names. A range past the end keeps what there is; one with no
turns in it, or that is not `[from to]` with `1 <= from <= to`, is an error.

A turn with a question and nothing after it is **not** exported. The user
message is built from the question and is therefore always present, so "has
messages" is not the test; `learnable?` asks for something after it. Counting
an empty prompt with an empty completion would overstate the dataset.

### What a turn cost

`:cost` is dollars, per turn, from two sources in order:

1. **What the provider says it billed.** The `claude` CLI reports
   `total_cost_usd` per call — it knows its own caching, plan and discounts,
   and no table beats that.
2. **`resources/brainyard/prices.edn`**, USD per 1M tokens, for providers that
   report only tokens. A file rather than code, overridable per project
   (divergence `prices-are-a-resource`).

A row may name prices that depend on how a call reached the model, or on how
long its prompt was (`byj-ewq`, divergence `bedrock-global-and-long-context-prices`):
- **A Bedrock `global.` id** is looked up under its own `global.` key first.
  AWS bills Claude 4.5 and later 10% more through a regional endpoint, so
  the region-less row is the regional price and the `global.` row is
  Anthropic's.
- **A row's `:long-context` tier**, `{:over n …rates}`, replaces its rates
  for a call whose input, cache included, is over `n` (`rates-at`). Claude
  Haiku 5.5's price rises fivefold past 100,000 tokens. The tier is per
  call, so a sum of calls priced as one could cross a line no call crossed.
  `claude-agent/usage-cost` prices a Claude Code turn's summed usage that
  way, and can overstate a Haiku 5.5 turn.

Each call is priced **as it completes**, not the turn as a whole, because a
node may pin its own `:lm` and a tier may route elsewhere — a turn's tokens can
belong to two price lists. `predictor/run` therefore reports the `:model` and
`:provider` a call actually ran on, which it previously resolved and discarded.

**A cached token is not charged as a fresh one.** `:input-tokens` is the *total*
input, cache included (RFC-006), so the fresh count is what is left after the
cached ones come out:

    fresh = max(0, input − cache-read − cache-write)

Pricing `:input-tokens` whole charges full rate for every token a cache hit
just made cheap — on a 10,000-token call with 9,000 cached, $0.03 instead of
$0.003 — and it is worst on exactly the runs where caching is working best.

**An unpriced call costs nothing that can be stated.** `:cost` is absent rather
than `0.0`, and a turn that priced some of its calls carries `:cost-partial?
true` beside its number (divergence `unknown-cost-is-not-free`).

**Free is not unknown**, and keeping them apart is the point. `:mock` and
`:ollama` cost nothing because nobody is billing for them, so they report
`0.0` and a turn made of them claims nothing is missing. A provider nobody has
priced reports neither.

### Reading it back

`session$analytics` scores the recorded turns — brainyard's, pull rather than
push, because the records are already on disk and scoring every turn as it
finishes costs work nobody asked for.

**The measurements are facts; the score is a judgement.** Success rate,
iterations, latency, cache hit rate, dollars and tool calls are what happened.
Turning them into a number out of 100 needs thresholds, and those are in
`resources/brainyard/analytics.edn` because they are opinions about what good
means for a particular agent doing particular work (RFC-001). **They are
brainyard's opinions and are not validated here**: the score is a consistent
way to compare this project's runs against each other, not a verdict. A project
that disagrees edits `.brainyard/analytics.edn`.

```clojure
{:health {:score 72 :grade "B-" :from [:ice :lt :oga :tce :tur] :missing []}
 :outcome {:success-rate 0.67 :trailing-failures 0 :terminated-by {…}}
 :cost {:total-cost 0.049 :cost-per-successful-turn 0.0245 :unpriced-turns 1}
 :rework {:repeated [{:at 1 :like 0 :similarity 0.83 :after-failure? true}]}}
```

Three things it declines to do:

- **A metric with no data is shared out, not counted as zero.** Two perfect
  metrics are not 40% of a score. `:from` and `:missing` say which were used —
  which is what lets PQS be absent at all.
- **An unpriced turn is not averaged in as free.** `:cost-partial?` means a
  turn's dollars are an understatement, so those turns are excluded and the
  exclusion is reported as `:unpriced-turns`. That flag exists to stop a real
  spend being averaged against a fabricated zero.
- **A tool error rate is carried, not pattern-matched.** A record stores a
  tool result as a clipped string, so a refusal cannot be told from a `grep`
  that found the word — the outcome travels from the tool layer instead, as
  `:error?` on the call (`byj-gly.87`). Records written before that have no
  failures to count, which understates rather than invents: the safer
  direction for a number nobody can go back and check.

`:rework` is the half of brainyard's PQS that needs no scorer: the same
question coming back, by Jaccard overlap, and whether the turn before it
failed. A question re-asked after a failure is the plainest evidence the agent
did not answer it.

A slow turn is named by **position**, not by a turn number — a v3 record has no
`:turn`, and brainyard's own analytics reads one anyway and gets nil.

### Redaction is the point of the boundary

`brainyard.secrets/redact` walks every string leaf of every example, in **both
formats** — if the lossless one were exempt, the format that keeps everything
would be the one that keeps the secrets. On by default; `:redact false` is for
a file that stays on this machine.

`trajectory.edn` itself holds whatever the turn said, as brainyard's does. The
export is where a project's turns stop being a local diagnostic and become a
file that gets copied somewhere, so that is where the scrubbing belongs.

**Redaction is pattern-based, and a secret is not the only thing in there.** An
export of turns contains the turns: repository paths, branch names, internal
service names, whatever a question happened to mention. None of that is
secret-shaped and none of it is targeted. `:redacted true` says the marker is
genuinely present in at least one example — that something *was* replaced — not
that everything worth replacing was found. Read the file before it goes
anywhere.

**Two lists, not one** (`brainyard.secrets`). brainyard carries eight patterns
for refusing a config write and five for redacting an export, overlapping but
neither containing the other, and says why nowhere. The reason is that the jobs
have opposite costs when wrong: refusing on a false positive blocks work, while
redacting on one costs a `[REDACTED]` nobody minds. So `token-patterns` holds
only shaped tokens — a vendor prefix and a length — and `redaction-patterns`
adds the two heuristics (`bearer …`, `api_key=…`) that would be far too eager
to refuse on. `configbox` refuses on the first list; this redacts with both.

## What is not here

- ~~No export.~~ **It is here** — see "Exporting it" above.
- **No prompt-quality score, and no waste detection.** brainyard's PQS scores
  the question itself and needs `pqs/score-prompt`, a subsystem that is not
  ported; `waste/detect-all-waste` is another. Their weight is shared over the
  metrics that do have data — see "Reading it back" above. What needs no
  scorer, a question coming back, is reported as `:rework`.
- **No per-model breakdown.** A turn's `:cost` is one number however many
  models it spanned. brainyard's usage tracker keeps `:by-model`; here each
  call is priced as it completes and only the total is kept, which is what the
  record needs. Analytics (`byj-gly.78`) may want the split.
- **No application log.** This file records TURNS. brainyard also runs a
  structured event stream to `~/.brainyard/logs/agent-tui-app.log` — rotation,
  a per-turn global context, an SLF4J bridge — and `log$turns` / `log$events` /
  `log$search` to read it back, which is the part with no analogue here at all:
  the agent cannot ask what happened in an earlier run. `byj-gly.81`.

  The stream itself is now declared and shown (RFC-026, `byj-gly.80`) — what is
  missing is writing it down and reading it back.
- **No rotation.** Neither has it: a session directory is pruned whole
  (`:prune-sessions?`, RFC-016), and per-field truncation is what bounds a
  record.
