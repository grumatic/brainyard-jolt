# RFC-014 — Context budget and compaction

**Status:** tiers 0 and 1 implemented and on (`byj-gly.7`); tier 2 implemented
and on, measured against dropping (`byj-gly.34`, `byj-gly.168`).

## Purpose

Specifies how a turn stays inside the model's context window: how the prompt is
measured, what is given up first when it does not fit, and what may never be
given up. brainyard sources: `agent/core/context_budget.clj` (`model->budget`,
`enforce`, the section policy table), `agent/common/context_compaction.clj`
(deterministic cross-turn compaction), `common/coact_agent.clj`
(`coact-init-action`, `coact-rebudget-action`, the strategies); docs
`design/context-management.md`, `design/compaction.md`.

## Scope

**This layer decides** what the prompt costs, which parts shrink, in what
order, and what the model is told about it.

**It must not know** what any zone means. A zone is text with a priority and,
sometimes, a way to shrink.

**It hands** a smaller set of zones and iteration records back to the loop
(RFC-003), which reassembles the prompt exactly as it did before.

## Why this shape — the evidence

**Our own numbers** (measured 2026-09-18 on this repo, `pr-str` of the tool
result):

| call | result |
|---|---|
| `list-dir src/brainyard` | 275 chars |
| `bash wc -l src/brainyard/*.clj` | 699 chars |
| `grep defn src` (50 matches) | 3,139 chars |
| `read-file docs/RFCS/RFC-010…md` | 18,722 chars |
| `read-file src/brainyard/agent.clj` | 27,944 chars |
| `grep the` (500 matches) | 74,337 chars — over the 64,000 cap, already truncated |

The system prompt is ~23,500 chars (~6K tokens): agent-core 1,717,
session-context 15,133, the rest signature and answer format. So **the prompt
is dominated by tool results, and the results are bimodal**: navigation costs
hundreds of characters, content costs 19–28K, a wide search hits the cap. Ten
iterations of content reads is 200–600K chars of transcript against a 23.5K
system prompt. A recorded run of the `tool$run` eval already sent 175K prompt
chars across six iterations (haiku, `json-sum`, no bash).

**What the literature supports**, for a runtime behind a closed API (no logits,
no KV access — so soft-prompt and KV-eviction methods are simply unavailable):

- Extractive selection beats perplexity-based token pruning: "extractive
  compression often outperforms all the other approaches, and enables up to 10x
  compression with minimal accuracy degradation", while "token pruning methods
  often lag behind extractive compression" — *Characterizing Prompt Compression
  Methods for Long Context Inference*, Jha et al., Es-FoMo @ ICML 2024
  ([2407.08892](https://arxiv.org/abs/2407.08892)).
- The strongest pruner went extractive for exactly this reason: LLMLingua-2
  formulates compression "as a token classification problem to guarantee the
  faithfulness of the compressed prompt to the original one" — Findings of ACL
  2024 ([2403.12968](https://arxiv.org/abs/2403.12968)).
- Task accuracy alone hides the damage; grounding and entity preservation must
  be measured separately — *Understanding and Improving Information
  Preservation in Prompt Compression*, EMNLP 2025 Findings
  ([2503.19114](https://arxiv.org/abs/2503.19114)).
- For coding agents specifically, across 176 matched settings on SWE-Bench
  Verified and Terminal-Bench 2.1: "staging rule-based elision before LLM-based
  summarization provides the strongest overall efficiency", and context
  management "becomes increasingly valuable as the context-window budget
  tightens" — *An Empirical Study of Harness Design for Coding Agents*,
  arXiv [2609.20804](https://arxiv.org/abs/2609.20804) (17 Sep 2026).
- Compaction is "taking a conversation nearing the context window limit,
  summarizing its contents, and reinitiating a new context window with the
  summary", preserving "architectural decisions, unresolved bugs, and
  implementation details while discarding redundant tool outputs"; and recall
  degrades as the window fills (*context rot*) — Anthropic, *Effective context
  engineering for AI agents*, 29 Sep 2025.

**Therefore: elision first, summarization second, and only if measured.**
brainyard reached the same conclusion from the other direction — its compaction
is deterministic and its design doc records that LLM summarization "was
considered and deliberately kept out of the hot path".

## Measured, after building tiers 0 and 1

`brainyard.dev.compaction-eval` replays turns through the tiers with no model
calls, sweeping the window — because compaction is worth nothing until the
budget tightens, and one window is half an answer. Four synthetic turns drawn
from the sizes above (2026-09-18, `:max-output-chars` 16000, keep 3 verbatim):

| window | budget | raw | after tier 0 | after tier 1 | what tier 1 did |
|---:|---:|---:|---:|---:|---|
| 200000 | 172,627 | 145,716 | 109,779 (−25%) | 109,779 | nothing — under budget |
| 128000 | 107,827 | 145,716 | 109,779 (−25%) | 109,779 | nothing |
| 64000 | 50,227 | 145,716 | 109,779 (−25%) | 109,779 | nothing |
| 32000 | 21,427 | 145,716 | 109,779 (−25%) | **62,270 (−57%)** | `elided×5`, `superseded×3`, `elided×4` |

Reading it:

- **Tier 0 is the whole saving in the common case**: the cap alone takes 25% off
  these turns, and at every window above 32K tier 1 correctly does nothing.
- **Tier 1 is what stands between a small window and failure**: at 32K it
  roughly halves the transcript again, and the re-read turn is handled entirely
  by superseded-read elision — the cheapest possible move, since the newest read
  of that path is still verbatim.
- **Navigation turns are never touched** at any window: 6,727 tokens in, 6,727
  out.

## Measured live: tier 2 against dropping

`brainyard.dev.compaction-ab` (`byj-gly.168`) runs two tasks through the
unmodified loop with `:compaction-summary?` off (elide, then drop) and on
(elide, then summarize, then drop), on `claude -p`, 2026-09-25. The tasks are
built so tier 2 has work: twelve files of ~1.6K chars each — under
`:min-elide-chars`, so tier 1 cannot shrink them — the answer needs a fact from
every file, the model has only `list-dir` and `read-file` at one call per
iteration, and the window is 12,500 tokens (2,000 for the reply, 3 records
verbatim). By the ninth file the oldest reads are over the budget. Billed tokens
are the provider's input + output, the summary calls included; a run counts as
correct only when it answers (not when it runs out of iterations) and the answer
names the expected value as a whole word.

| model | task | drop: correct · billed · calls | summary: correct · billed · calls |
|---|---|---|---|
| haiku | report-total (r1) | ✓ · 264,219 · 15 | ✓ · 295,391 · 17 |
| haiku | report-total (r2) | ✓ · 252,391 · 15 | ✓ · 282,559 · 17 |
| sonnet | report-total | ✓ · 272,711 · 16 | ✓ · 242,707 · 16 |
| haiku | largest-region | **✗ ran out of iterations, 6 re-reads** · 311,221 · 20 | ✓ · 225,723 · 17 |
| sonnet | largest-region | ✓ · 254,290 · 14 | ✓ · 244,755 · 17 |

(A first haiku round of `largest-region` is left out: its expected answer was
written by hand and wrong, and the answers were not kept to re-score.)

Reading it:

- **Tier 2 never lost the plan; dropping did once.** With its early reads
  dropped, haiku went back for six of them and ran out of iterations — the
  re-acquisition failure step 4 of the plan warns about. With a summary it
  read each file once and answered.
- **When dropping does not fail, the two cost about the same.** Over the four
  runs both arms completed, dropping billed 1,043,611 tokens and summarizing
  1,065,412 (+2%): haiku pays ~12% for its summary calls, sonnet came out 4–11%
  cheaper. Per completed task over everything: 338,708 dropping, 258,227
  summarizing (−24%), and that difference is the one failure.
- **Why dropping mostly survives**: both models carry a running tally in their
  reasoning ("Totals for r-01 to r-10 are known… harbor leads with 1297"), and
  the newest thoughts are inside the verbatim floor. Dropping costs the facts;
  the plan often survives in the thoughts. Haiku's did not.
- **Five scored pairs is a direction, not a result.** The tasks are
  constructed to need tier 2, one call per iteration is a constraint real
  turns do not have, and at the default 200K window tier 2 does not fire at
  all: it runs only where the loop would otherwise drop records.

## Model

### Measuring

`estimate-tokens` is `ceil(chars / 4)`, brainyard's estimator, with its stated
±10–20% error. No tokenizer ships with jolt; a wrong-by-15% estimate with a
safety margin is cheaper than a dependency (known gap below).

```
usable = :max-context-tokens − :max-output-tokens
budget = usable − ceil(usable × :context-budget-safety-ratio)
```

Defaults `200000`, `8192`, `0.10` → a budget near 172K. brainyard's numbers are
`128000`/`4096`/`0.10` → 111,513; ours follow the models this port actually
calls.

### The tiers

Applied in order, each until the prompt fits or the tier is exhausted.

**Tier 0 — caps (exists today).** Each tool result is capped at
`:max-output-chars`, each reasoning field at `:max-thought-chars`, history at
`:max-history-iterations` records. **A result past its cap is spilled first**
(`context/cap-result`, byj-gly.189): the whole text goes to the turn's
transcript directory, and the model reads its head (three quarters of the cap)
and its tail (a quarter) around `…[truncated: lines A–B of L are not shown here (N of M chars) — the whole
of it is <path>. Read them with read-file {:path "<path>" :offset A :limit 200} …]`
— head and tail whole lines of the spill, so the model reads on from exactly
where the head stopped (byj-gly.191: told only a character count, it paged from
line 1 and re-read what it had been shown). A spill of one enormous line is cut
by characters instead, and the marker says to read it on by `:next-column`.
So is a result whose whole lines would show less than half the budget. That
is a map dominated by one long string, e.g. a bash `:output` or a read-file
`:content`: pretty-printed, the string is one line that fits neither head nor
tail, and the model was shown only the short keys (byj-gly.217,
`context-test/a-result-that-is-one-long-string-still-shows-it`). The tail is kept because what ends a result — a
count, a summary, the error — is often what it was run for; the path because a
cut that says only how much was lost left the model to guess (a `list-tools`
over sixty skills, cut mid-EDN, was abandoned and asked again another way).
The record carries `:spilled`, the trajectory keeps it (its own 4,000-character
cut is for readability), and tier 1 eliding the result later points at that
spill rather than overwriting the whole text with the cut one. Two calls of one
tool in an iteration spill to separate files. **A spill is readable in
pages** (byj-gly.190): a result the kernel printed as one line of EDN is spilled
pretty-printed, a line an entry, so a line range reads a slice of it; and the
marker names the unshown lines and the read that fetches them, because a model given only a path
read the spill whole with `read-file`, whose result was cut and spilled again,
and then fell back to `bash cat`. `read-file` never returns more than the cap —
it pages a whole file or a range by lines, and a line longer than a page (a
spill of one printed string, minified JSON) by `:column` — so no read of a spill
can re-spill (RFC-007).
The proposal tightens the result cap from
64,000 to 16,000 chars: one result claiming 16K tokens is a sixth of a small
window, and the measurements show 28K chars is a whole source file — worth
spilling, not worth inlining.

**Tier 1 — rule-based elision.** Cheap, deterministic, no model call:

1. **Stub old content results.** The last `:keep-verbatim-iterations` (default
   3) keep their results in full. Older records keep `:tool-name`,
   `:tool-args`, an `:elided true` marker, and a result reading
   `…[elided 27944 chars — full text at .brainyard/transcripts/turn-1/it4-read-file.txt; grep it or read a line range]`.
   Reasoning, tool names and arguments are never elided — they are the plan.
2. **Drop superseded reads.** A later `read-file` of the same path makes the
   earlier body dead weight; the stub remains so the model can see it happened.
3. **Shrink the history floor.** Only then drop whole oldest records.

**A limit tier 1 cannot fix.** When the newest record alone busts the budget,
there is nothing old to elide and the report says `:over-budget? true` rather
than touching the verbatim floor. The lever there is tier 0's cap, which is why
it moved to 16,000 chars.

**Tier 2 — boundary summarization.** When elision leaves the
prompt over budget, the records older than the verbatim floor — already elided,
or too small to elide — become ONE record, written by a predictor the tree
names (`:predictor "react/compact"` on both `:context/enforce` nodes,
`resources/brainyard/predictors/react/compact.edn`, RFC-005). It runs between
elision and dropping, so a span is summarized before any of it is thrown away.
The record is brainyard's shape (`coact_agent.clj` `:collapse-iterations`):
`{:iteration 0 :channel "summary" :thought <summary> :tool-results []}`, so the
transcript shows where the span went. brainyard fills it deterministically, by
printing the span; here a model writes it, keeping every spill path it cites so
the agent can read a line range back instead of repeating the call.

The record's text is the predictor's `summary`, then, after a blank line, its
`kept`: the exact identifiers the span established, one per line, outside the
summary's 250 words (`context/summary-text`, byj-gly.383).
- **Why it is separate:** in prose alone a summary kept about a quarter of
  the identifiers the later iterations used, on either model; with them listed
  apart, about half.
- **Measured by** `dev/brainyard/dev/compaction_tier_spike.clj` on recorded
  long turns.
- **A reply without `kept`** is the prose alone.

- **Taken only when shorter** than the span it replaces; an empty or failed
  summary is no summary, and the step after it drops records as it would have
  without tier 2. A cancellation still propagates.
- **A summary already in the span is folded in** by the next one; a summary
  alone before the floor is not summarized again.
- **The summary outlives dropping** unless dropping it is what gets the prompt
  under budget: it is the whole of what it replaced, and small.
- **Its call is counted** in the turn's usage like any other.

**On, because it was measured** (`:compaction-summary?` true since
`byj-gly.168`; false turns it off). It was built off, because the literature's
repeated lesson is that the trivial baseline is rarely beaten, and turned on
only after the live A/B above: where the loop would otherwise drop records,
dropping lost the plan once and the summary never did, at about the same cost
when both finished. It changes nothing at a window the turn fits in — it fires
only where records would be dropped — so what it can cost is bounded by where
dropping was already costing the plan. The sample is small; the known gap
below says what would overturn it.

### Never compacted

The system zones (agent-core, session-context, answer format), the user's
question, pending notices, the permission and hook rules, and the most recent
`:keep-verbatim-iterations` records. The constraint layer is re-assembled from
source every turn and never routed through a summarizer — both an injection
surface and the thing whose loss is hardest to notice.

### Spill and recovery

Elided text is written to
`<project-dir>/.brainyard/transcripts/<session-id>/turn-<n>/`, and the stub
names the path, so the model can `read-file` it back. Two brainyard lessons
apply: its spill files could be evicted while the marker still promised them
("Recoverability is lost with no error and nothing downstream can detect it"),
so **nothing evicts these within a session**; and a bare re-read of a truncated
file re-truncates and loops, so the stub states the size and suggests `grep` or
a line range rather than a whole-file read.

**The session id is not decoration.** `.brainyard/` belongs to the project
(RFC-013), so every run in that project shares it, and a path keyed only by turn
and iteration collides: two runs both on turn 1 write
`turn-1/it3-read-file.txt`, and the first run's stub then resolves to the second
run's text — a wrong file that reads exactly like the right one. `make-ctx`
generates `:session-id` per run (millis plus 8 hex, sortable) unless the caller
sets it, and `context-test/spill-directories-do-not-collide-across-runs` pins
it.

The rest of `.brainyard/` is shared on purpose: user tools and hooks are project
state, so a tool one run defines is a tool the next run loads, and two runs
defining the same name is last-write-wins, as in brainyard. Spills are the
exception because they are private to one run's transcript.

### Where it runs

Mechanism in `src/brainyard/context.clj`; the decision in the tree, as two
nodes (RFC-001):

```clojure
[:action {:id :react/budget :fn :context/enforce}]                    ; after init
[:action {:id :react/rebudget :fn :context/enforce :every-n 4}]       ; inside the iteration
```

So a loop can drop compaction entirely, or budget on a different cadence,
without a rebuild. brainyard rebudgets every `:rebudget-every-n-iter` (10)
iterations; ours defaults to 4, because our results are larger relative to the
window.

### Events

`:agent.context/budgeted` `{:phase :init|:rebudget :total-tokens :budget
:section-tokens :compactions :over-budget?}` and
`:agent.compaction/pre|post` — the names RFC-011 already reserves for this
work, so hooks can observe or a user hook can log the cost.

## API (proposed)

`brainyard.context`: `estimate-tokens`, `budget`, `prompt-tokens`, `enforce`
(`[ctx mem]`, or `[ctx mem summarize]` with tier 2's `(fn [span]) → text`),
`reduce-history`, `summary-record`, `superseded?`, `spill!`, `stub`,
`enforce-action` (leaf `:context/enforce`; its node's `:predictor` is tier 2's
summarizer). `:compactions` steps are `:superseded`, `:elided`, `:summarized`
and `:dropped`, each with the number of records or results it touched.

Config keys, all in the schema (RFC-013): `:max-context-tokens`,
`:max-output-tokens`, `:context-budget-safety-ratio`,
`:enable-context-budget`, `:rebudget-every-n-iter`,
`:keep-verbatim-iterations`, `:elide-superseded-reads?`,
`:compaction-summary?`, plus the existing
`:max-output-chars`, `:max-thought-chars`, `:max-history-iterations`.

## Invariants

| invariant | enforced by |
|---|---|
| A result past `:max-output-chars` is spilled whole and cut to its head and tail around a marker naming how much and where; tier 1 eliding it points at that spill and leaves it whole. | `context-test/a-result-past-the-cap-is-spilled-whole-and-cut-at-both-ends`, `context-test/the-loop-budgets-and-says-so` |
| A spilled one-line collection is pretty-printed a line an entry and reads back equal; the model is shown whole lines of it, and the marker names exactly the lines it was not shown and the `read-file` call that starts at the first of them. | `context-test/a-spilled-collection-is-a-line-an-entry` |
| A prompt over budget comes back under it, or reports `:over-budget?` with what it tried. | `context-test/dropping-is-the-last-resort` |
| Reasoning, tool names, tool args and small navigation results are never elided. | `context-test/tier-1-elides-old-content-results` |
| The estimate and the budget arithmetic are brainyard's. | `context-test/measuring` |
| An elided result leaves a stub naming its size and file, and that file is readable for the rest of the session; the last N records stay verbatim. | `context-test/tier-1-elides-old-content-results` |
| A superseded read is stubbed first, and the newest read of a path stays verbatim. | `context-test/superseded-reads-go-first` |
| Compaction is visible: `:elided` in the transcript and an `:agent.context/budgeted` event saying what it did. | `context-test/the-loop-budgets-and-says-so` |
| Two runs in one project never share a spill path, and one run keeps a directory per turn. | `context-test/spill-directories-do-not-collide-across-runs` |
| A spill never writes over another: the name is claimed atomically (`~2`, `~3`, …), so tier 1 eliding one result cannot overwrite the file tier 0 gave another (byj-gly.219). | `context-test/a-spill-never-writes-over-another` |
| A turn that never exceeds the budget is byte-identical to one with compaction off. | `context-test/inert-under-budget` |
| Dropping whole records is the last resort, and never goes below the verbatim floor. | `context-test/dropping-is-the-last-resort` |
| Tier 2 summarizes the span older than the floor into brainyard's summary record before anything is dropped; it is taken only when shorter, a failed summary falls back to dropping, a lone summary is not re-summarized, and the summary outlives a drop that would not help. | `context-test/tier-2-summarizes-before-dropping` |
| The summary record holds the prose and, apart from it, the identifiers the predictor kept; a reply without them is the prose alone. | `context-test/a-summary-keeps-its-identifiers-apart-from-its-prose` |
| Tier 2 is on unless `:compaction-summary?` is false, and runs the node's predictor, counted as a call. | `context-test/tier-2-runs-the-nodes-predictor-unless-turned-off` |
| A loop can decline to budget at all. | `context-test/a-loop-can-decline-to-budget` |

That last one matters for cost: prompt caching keys on a byte-identical
prefix, so compaction must not perturb the prompt until it has to.

## Measurement plan

Before turning tier 2 on, and before changing any default:

1. **Offline replay — built** (`brainyard.dev.compaction-eval`, table above).
   It takes a trace file of recorded turns, so real transcripts replace the
   synthetic shapes as soon as they are dumped.
2. **Live A/B on multi-step tasks**, reusing `brainyard.dev.tool-run-eval`'s
   shape: with and without each tier, on sonnet and haiku.
3. **The metric is total billed tokens per *completed* task**, input and
   output. A pre-registered trial in the compression literature found
   aggressive compression *raising* net cost because outputs grew; context size
   alone is the wrong number.
4. **Count re-reads.** If the agent re-acquires what we elided, we moved cost
   rather than saving it — the agent-side failure the literature flags.
5. **Watch for plan loss.** Record whether the turn still ends with the answer
   it would have given: the failure that matters is forgetting the task, not
   spending tokens.

## Known gaps

- **No tokenizer.** chars/4 with a 10% margin; a real count would let the
  budget run closer to the window.
- **Tier 2's default rests on constructed tasks only** (table above): five
  scored pairs (ten runs) on two tasks, one plan loss in the dropping arm and
  none with a summary. Real recorded turns at a tight window have not been
  replayed live; a plan loss WITH a summary, or summaries costing more than the
  re-reads they save, would be the reason to turn it back off
  (`brainyard.dev.compaction-ab` re-runs the comparison).
- **The synthetic turns are shapes, not traffic.** Replaying real recorded
  transcripts is one flag away and has not been done.
- Spill directories are pruned at startup with the sessions (RFC-016): the 20
  most recent survive, older ones go once past the TTL. No size budget yet.
- **Note-taking memory is a pull, not a note** (RFC-020): nothing is captured
  automatically (`byj-gly.53`), so it does not yet relieve the window the way
  Anthropic's note-taking does. It was one of the two alternatives to
  compaction Anthropic names. The other, sub-agent isolation, is RFC-018: a
  dispatched agent gets a clean window and returns only its answer, so the
  work it does never enters its caller's history to be compacted. What that
  costs the caller is not measured here — the compaction eval has no
  delegating variant.
- **Cross-turn compaction is a command, not a tier.** A session's previous
  turns are capped by count (`:max-previous-turns`) and demoted by
  `:full-depth`, and `/compact` summarizes the older ones on request (RFC-004).
  Nothing does it when the history alone would bust the budget; brainyard's
  `context_compaction.clj` does, deterministically, by depth.
- **Nothing measures compaction harm on an agent benchmark here.** Our eval is
  four synthetic tasks; the literature has no controlled agent-benchmark
  ablation either, which is a reason for caution, not comfort.
