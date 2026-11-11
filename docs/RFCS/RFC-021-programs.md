# RFC-021 — Programs: measuring and tuning a predictor

**Status:** implemented (`byj-gly.10`) for datasets, metrics, measurement with
variance, and three optimizers — the third being `bootstrap-random-search`
(`byj-gly.57`). Ensemble, `knn-few-shot` and instruction proposal are not here.

## Purpose

Specifies how a predictor's prompt stops being a guess: how a dataset is built
from what the agent did, how a configuration is measured against it, and how a
better configuration is proposed — as a file a person accepts, never applied.

brainyard sources: `clj-llm/core/{evaluate,optimize}.clj` (the generic half),
`agent/common/{programs,predictions,predictor_agent}.clj` (the project half);
docs `design/dspy-programming-model-proposal.md`,
`design/predictor-agent-design.md`.

## Scope

**This layer decides** what a score means and what a proposal contains.

**It must not know** what a predictor is for, or what a good answer looks like —
that is the metric's business, and the metric is named by the caller.

**It must not change behaviour.** Nothing here writes a params file except
`accept-proposal!`, which no tool can reach.

**It hands** five `program$*` commands to the registry (RFC-007) and a params
file to RFC-005, which has always read it.

## Model

### The compile target already existed

RFC-005 resolves `<project-dir>/.brainyard/programs/<id>.edn` on every call, and
honours `:instructions`, `:field-descs`, `:demos`, `:version` and
`:compiled-by`. So this layer introduces no new mechanism on the hot path: it
**writes the file the runtime has always read**. A proposal is that file,
staged, with the measurements that argue for it.

That is also why accepting one is not a tool. A params file silently changes
the system message of every later turn; an agent that could accept its own
proposal would be choosing its own future instructions.

| | reachable by |
|---|---|
| `program$list`, `program$build-dataset`, `program$eval`, `program$compile`, `program$proposals` | the model, and the CLI |
| `by programs accept` / `by programs reject`, and the session's `/proposals accept` / `reject` (`program/<id>/<proposal-id>`, `byj-gly.249.1`) | **a person only** |

### Three contracts

```clojure
example   {:inputs {…} :labels {…}? :meta {…}?}
program   (fn [inputs]) → {:result {:outputs … :usage …} :trace [entry …]}
metric    (fn [example result trace]) → number | boolean | nil
```

Labels are optional and describe only the final outputs — the label-efficiency
point that makes a dataset affordable at all.

A **program returns its own trace**. brainyard's arrives through a dynamic
`*trace*`, and its params through `*params*`, which is why every task in its
thread pool is wrapped in `bound-fn`. Here both are call arguments to
`predictor/run`, so parallel evaluation needs no conveyance
(divergence `evaluation-passes-params-explicitly`).

### A split is of content, not position

`content-hash` is SHA-256 over the example's inputs in sorted-key order, first
four bytes as an unsigned int; `u < 0.6` is train, `< 0.8` val, else test.
Adding an example therefore never moves an existing one into another split, so
a score recorded last week is comparable to one recorded today. `dataset-hash`
names a set of examples in 12 hex characters, so a report can say what it
measured.

### The dataset comes from what the agent did

`program$build-dataset` reads the **prediction log** — every `:predictor/call`,
appended to `<project>/.brainyard/sessions/<id>/predictions.edn`, one `pr-str`
map per line. Four kinds of record are dropped, and the counts are reported:
errored, produced nothing, **clipped** (a record whose input was truncated asks
a different question from the one its label answers), and duplicate inputs.

**A truncated input is not a corrupt record.** `:skip-clipped` defaults to
false. It defaulted to true while the prediction log truncated at 4000
characters, because a record carrying the marker then misrepresented what the
model had been shown. That log is gone and the event log clips nothing, so the
only thing still writing the marker is the agent trimming its own iteration
history for the context budget — and the model saw exactly that and answered it,
which is a faithful example. Measured on the first real pool of 156 records: 80
examples under the old clipping log, 106 with the guard on, **154** with it off,
and a val split of 35 against the twelve brainyard measured its null at. Pass
`:skip-clipped true` when reading a source that does truncate — an old
`predictions.edn`, or a trajectory.

The labels are the model's **own past outputs**, so such a dataset is *silver*.
`:label-source :prediction-log` records that and nothing downstream forgets it.

**The records come from the event log** (RFC-026), which is on unless
`:event-log? false` and holds every `:predictor/call` whole. They are read with
`:full?`, because the view a turn gets through `log$events` withholds
`:inputs`, `:outputs` and `:reasoning` — the only fields a dataset is made of.
An empty `build-dataset` says whether the log is off or merely has nothing yet,
rather than returning nothing and reading as a finding about the model.

### A score without a spread is not a result

This is the part brainyard learned the hard way, and the reason this layer is
mostly measurement rather than search. On its one real dataset
(`memory/graph-extract`, 12 val examples):

| what it measured | result |
|---|---|
| the same configuration, twice | 0.559, then 0.605 |
| two instruction wordings, 3 repeats each | diff −0.006, margin needed 0.262 |
| the teacher (Opus) vs the student (Haiku) | **0.427 vs ~0.58** — the teacher was worse |
| bootstrapped demos vs none | **0.476 vs 0.605** — demos hurt |

So: `evaluate` takes `:repeats` and reports `:repeat-scores`, `:stddev`,
`:stderr`, `:ci95` and `:example-scores`; `compare-scores` returns
`:tested? false` when there was no spread to weigh, rather than pretending; and
`select-best` gives a candidate the win only past the margin, with ties keeping
the earlier row — **demos must strictly beat no demos.**

The comparison is **paired over examples**, not over passes (divergence
`comparison-is-paired-over-examples`). Both programs run on the same dataset,
so the difference that matters is per example: `compare-scores` tests
`d_i = a_i - b_i` against the t margin on its own standard error, which removes
the between-example variance. brainyard's per-pass Welch remains the fallback
for a row with no `:example-scores`, and `:paired?` says which ran. This is not
a refinement: a deterministic student has zero pass spread, so the per-pass
margin collapsed to 0 and the first real compile here crowned a proposal one
example out of 35 ahead (`byj-gly.57`).
`dev/brainyard/dev/paired_comparison_spike.clj` re-runs that compile's two
candidates on its val split and puts both tests side by side — the saved report
predates `:example-scores` and cannot be re-judged from disk, because a pass
mean cannot be unpaired back into items. It reproduces the compile exactly
(0.2286 and 0.2571 to full precision, 70 calls and 4 and 12 errors each, the
numbers in the saved report), which is what a temperature-0 student is for:

| test | diff | margin | verdict |
|---|---|---|---|
| per-pass (brainyard's) | 0.0286 | **0.0** | better, `:tested? false` |
| paired over examples | 0.0286 | **0.0979** | not better, `:tested? true` |

Two examples better, one worse, thirty-two identical. The proposal is one
example ahead on net and the paired margin is three and a half times that. The
same run shows what the per-pass ranking could not: the proposal **errors three
times as often as the baseline it beat** (12 calls against 4), because its
demos make the prompt long enough that a 1.5b student fails on it more. An
errored call scores 0, so that cost is already inside the 0.2571.

When the per-example differences have **no spread at all** — every example
moved by the same amount — the t has no standard error to divide by, and what
is left is how many examples agreed. That is a sign test: `n` agreeing signs
are two-sided p = 2·0.5^n, which first clears 0.05 at **n = 6** (0.0156; n = 5
is 0.0625, n = 2 is 0.5). Below that the answer is a bare comparison, however
clean the separation looks. Getting this wrong is how the same overclaim moves
from the per-pass test into the paired one instead of being removed.

The underlying cause is worth carrying: the `claude-code` adapter sends no
temperature, so every call samples at the CLI's default rather than 0. That
property is unchanged here.

### What a failing call costs

| class | effect |
|---|---|
| `:malformed` / `:empty` | scores 0 — the program was wrong, and a wrong answer is a measurement |
| `:fatal?` (a usage cap) | stops the run; the rest of the dataset is not burned |
| anything else | scores 0, counted in `:errors`, the run continues |
| a metric that throws | scores 0; a broken metric must not fail a run that produced answers |

A transient failure is **not** retried here: `llm/chat` already retried it
(divergence `evaluation-does-not-re-retry`). Spend is capped in `:max-calls` and
`:max-tokens` rather than dollars, because the provider layer does not price a
call (divergence `spend-cap-is-not-dollars`). Both are checked *before* an
example starts, so `:parallel n` overshoots by at most n−1.

### A proposal

`compile-predictor` bootstraps on train, selects on val, and never touches test.
Three rows are always scored, so every dossier answers the same question:

| row | what it is |
|---|---|
| `:zero-shot` | the student exactly as it ships — **the baseline** |
| `:teacher` | the teacher, zero-shot — a **reference that cannot win**, being another model |
| `:proposal` | the student with the candidate demos |

The prompt as it ships today (`:instructions`, `:field-descs`) is laid **under**
every row; without that, demos would be selected for a prompt that will never
be sent. Written to `proposals/<ts>/`:

```
params.edn      the WINNER — so accepting does what the leaderboard says
candidate.edn   the losing candidate, when zero-shot won
report.edn      leaderboard, teacher scores, :meta
status.edn      {:status :pending|:accepted|:rejected}
REVIEW.md       the dossier
previous.edn    written on accept: the params file that was replaced
```

**A proposal whose winner is zero-shot is a result, not a failure** — it is the
outcome brainyard actually got. `REVIEW.md` shows the demos as the *literal*
system-prompt text (`signature/render-demos`), because the thing being accepted
is words in a prompt, and it warns when a single pass makes every difference
untested.

## API

`brainyard.evaluate`: `example`, `content-hash`, `split`, `select-split`,
`dataset-hash`, `exact-match`, `schema-valid`, `set-f1`, `channel-choice`,
`segment-f1`, `weighted`, `all-of`,
`score->double`, `t-critical`, `compare-scores`, `evaluate`,
`leaderboard-row-keys`, `select-best`.

`brainyard.optimize`: `shuffle-seeded`, `labeled-few-shot`, `bootstrap-pool`,
`bootstrap-few-shot`.

`brainyard.predictions`: `log-file`, `entry->record`, `sink`, `clipped?`,
`read-predictions`, `sessions`.

`brainyard.programs`: `programs-root`, `params-file-for`, `dataset-file`,
`list-datasets`, `load-dataset`, `records->examples`, `build-dataset`,
`metrics`, `resolve-metric`, `predictor-program`, `eval-predictor`,
`compile-predictor`, `render-review`, `list-proposals`, `accept-proposal!`,
`reject-proposal!`, `commands`.

`brainyard.predictor`: `bundled-ids`, `list-ids` (new — a binary has no
directory to walk).

Config (RFC-013): `:programs?` (true), `:prediction-notice-at` (150). The
records themselves are the event log's, gated by `:event-log?`.

CLI: `by programs list|eval|compile|proposals|accept|reject`.

## Invariants

| invariant | enforced by |
|---|---|
| **A secret never reaches a demo**: every example is redacted, and so are the params a proposal writes and the params accepting one installs. | `programs-test/a-secret-never-reaches-a-demo`, `programs-test/the-params-a-proposal-writes-are-redacted-too` |
| A dataset that was not changed does not claim it was. | `programs-test/a-clean-dataset-does-not-claim-to-be-redacted` |
| A split is order-insensitive and survives the dataset growing; a dataset hash names content. | `programs-test/a-split-is-content-addressed-so-it-survives-the-dataset-growing` |
| Errored, empty, clipped and duplicate records never become examples, and the counts are reported; a dataset built this way is marked silver. | `programs-test/a-dataset-is-built-from-what-the-agent-actually-did` |
| Predictions are read from the event log, which is on unless `:event-log? false`, and an empty build says so. | `programs-test/the-pool-is-read-from-the-event-log`, `programs-test/the-commands-report-a-failure-instead-of-throwing` |
| **Turning it on is enough**: a run's session id is settled before any sink captures the config, so the log writes without a hand-pinned id and a resumed run writes to the session it resumed (provenance P-16). | `main-test/a-run-always-has-a-session-id-before-anything-captures-it`, `main-test/resuming-settles-on-the-resumed-id-not-a-fresh-one` |
| An unlabelled example cannot score 1.0. | `programs-test/metrics-score-what-they-say` |
| Repeats report every pass and its spread; a difference inside the noise is reported as untested or not better. | `programs-test/a-score-without-a-spread-is-not-a-result` |
| Malformed scores 0 and continues; fatal stops the run; a spend cap stops it; a broken metric costs 0, not the run. | `programs-test/what-a-failing-call-costs-the-run` |
| A metric is named, with its arguments in the name: `set-f1:<field>` or `set-f1:<field>:<key>` (items compared by one key — `set-f1:tool-calls:tool-name`), `channel-choice[:<field>]` (tool or answer, one bit). An unknown name lists those that exist. A reply that does not decode needs no metric of its own: it is an error of class `:malformed`, scored 0 and counted (byj-gly.125). | `programs-test/a-metric-is-named-and-an-unknown-one-says-what-exists` |
| An optimizer proposes and applies nothing; the teacher runs once per example, and every teacher score is reported. | `programs-test/optimizers-propose-demos-and-apply-nothing` |
| Demos win only by beating zero-shot; `params.edn` is always the winner. | `programs-test/demos-are-proposed-only-when-they-beat-no-demos`, `programs-test/zero-shot-winning-is-a-result-not-a-failure` |
| Accepting writes the params file RFC-005 reads, keeps the replaced one, and happens once. | `programs-test/a-proposal-is-installed-by-a-person-and-the-runtime-then-reads-it` |
| **No tool can accept or reject a proposal**, and `:programs? false` removes the family. | `programs-test/accepting-a-proposal-is-not-a-tool` |

### What a demo may carry

Every example is redacted on its way out of the prediction log
(`brainyard.secrets`, RFC-025), and **there is no flag for it**. The chain is
what makes the difference: a record becomes an example, an example becomes a
demo, and a demo is pasted into the system message of every later turn — which
reaches the model provider. brainyard found recalled memory in a live run's
`:inputs` verbatim; the log is the raw copy, and it is off until a project asks
for it.

It is applied three times, and the repetition is deliberate rather than
defensive:

| where | why there |
|---|---|
| `records->examples` | the moment a record stops being a log line |
| the params a proposal writes | an optimizer proposing an **instruction** rather than demos (`byj-gly.121`) reaches this and not the above |
| `accept-proposal!` | the proposal sat on disk for a person to review, and reviewing means editing |

`build-dataset` reports `:redacted`, because a dataset that quietly differs
from the log it came from is one someone will be surprised by.

## Known gaps

- **No `ensemble`, `knn-few-shot` or instruction proposal** (`byj-gly.121`).
  `knn-few-shot` selects demos per input, which is cache-hostile (RFC-015);
  instruction proposal writes an instruction rather than demos, which the
  proposal path already anticipates.
- **`bootstrap-random-search` ships but has never selected on a real dataset.**
  brainyard built it and measured a null: run-to-run noise at ±0.05 over twelve
  examples, and a search cannot separate what an evaluation cannot. It was
  deferred until either a provider that honours temperature 0 was the one being
  measured, or a val set was large enough. The first is now true and measured —
  ollama at `temperature 0.0` returned one distinct reply in four, where the
  claude-code adapter sends no temperature at all, which was the diagnosed cause
  of the spread. What that establishes is that the *variance* objection is gone
  for that provider, **not** that a search finds anything: every test here is
  offline against a mock, and no real dataset has been searched. brainyard's
  null was on a real one.
- **No LM-judge metric.** A metric is a Clojure fn; brainyard's plan was for
  judge metrics to be programs with their own params, pinned to a different
  tier than the student.
- **There is no prediction log.** There was: a second file per session holding
  every `:predictor/call`, off by default, clipped at 4000 characters. The event
  log is lossless and already holds those records whole (RFC-026), so the second
  copy recorded the same thing less completely — the first real dataset built
  from it lost 74 of 156 records to that clipping. `build-dataset` reads the
  event log now, with `:full?`, and `brainyard.predictions` is the reading
  rather than a writer. When a predictor's pool reaches `:prediction-notice-at`
  the run says so once, through a `:notice` event. The default is **150** rather
  than a rounder number because the split is 60/20/20: brainyard measured its
  null on twelve val examples, and 150 is the first pool whose val split (~30)
  is not the size already known to fail.

  **It counts USABLE records, not calls made**, and that distinction was
  learned rather than designed. The first real pool built for this repo was 156
  records and 80 examples: 74 were dropped as clipped, because the log truncates
  at `max-string-chars` and a turn that reads files goes past it. Counting lines
  would have announced an opportunity at a val split of ~16 — almost exactly the
  size brainyard's null was measured at. `predictions/usable?` re-states
  `records->examples`' rules, and `programs-test/the-pool-count-is-what-the-
  dataset-yields` is what stops the two drifting.
- **No dataset builder but the prediction log.** brainyard also builds from
  eval-agent verdicts and skill-distill decisions; neither subsystem is ported.
- **No `predictor-agent`.** brainyard has a specialist that drives this
  lifecycle conversationally; the commands are reachable directly instead
  (`byj-gly.41` covers the roster).
- **A proposal id is a millisecond.** Two compiles in the same millisecond would
  collide on a directory name.
