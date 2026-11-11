# RFC-003 — The ReAct loop

**Status:** partial.

## Purpose

Specifies one agent turn: brainyard's `react-agent`, which is the CoAct loop
(`components/agent/.../common/coact_agent.clj`, `coact-loop-subtree`) with the
code channel off. The shape is `resources/brainyard/loops/react.edn`; the steps
are the `:react/*` fns in `brainyard.agent/registry`.

## Scope

**This layer decides** how one question becomes one answer: when to call the
model, how to route what it returns, when to stop.

**It must not know** how the model is called (RFC-006), what the prompt says
(RFC-005), or that it may be nested in a session (RFC-004).

**It hands** `{:answer :terminated-by :goal-achieved :next-user-prompt
:iterations :trajectory :usage}` to its caller (`agent/turn-result`).

## Model

```
init → hook ask/pre → budget
     → [repeat max-iterations until terminated?
          inc-iter → rebudget (every nth) → hook iteration/pre
          [think-act | repair]                  ← llm-guard
          recover-native-tool-calls
          strip-unbound-tools
          resolve-channel-conflicts
          [answer-non-blank? → stamp-answer     ← router, path A
           | has-tool-calls? → tool-dispatch    ← path C (path B, code, is off)
           | repair]                            ← path D, no action
          accumulate → hook iteration/post
       | loop-fallback]                         ← loop-guard
→ ensure-answer → hook ask/post
```

**One LLM call per iteration, DSPy-style.** Each call is the predictor the
think-act node names (`react/think-act`, RFC-005): a system message and a user
message, and a JSON reply `{reasoning, tool-calls, answer, goal-achieved,
next-user-prompt}`. Native tool-use APIs are not used, as in brainyard.

**History is printed data.** The `iterations` input is `(pr-str iterations)` —
the last `:max-history-iterations` records, each
`{:iteration :thought :channel "tool"|"none" :tool-results [{:tool-name
:tool-args :tool-result}] :notices?}`. Answer and failed-call iterations go to
`:trajectory-iterations` only.

### The thought (`byj-gly.280.2`, 2026-09-30)

A reply's `reasoning` is the iteration's `:thought`, and it has two readers:
- the model, which is shown it again in the next iteration's history;
- the person watching the turn, who sees it streamed.

brainyard's description is written for the model alone. It asks for "brief
reasoning leading to the answer … state the decision and why".

In 387 thoughts from 71 real sessions, 47% of tool-channel thoughts restated
what the reader already had. They opened "The user wants…", "No history
yet…" or "The previous iteration…". Answer-channel thoughts restated 52% of
the time, mostly with "the result is already in, so I can answer".

ReAct's own thoughts (Yao et al. 2022) track progress, pull the fact out of an
observation and handle an exception: what, how, why. So `react/think-act`
names its own description (`:reasoning {:desc {:resource
"brainyard/prompts/think-act-thought.md"}}`, RFC-005). It asks for what the
last result told the model that is new, what it does next and why, without
restating the question or earlier iterations.

Measured with `brainyard.dev.tool-reuse-eval` (`THOUGHT` names each arm's
wording as a params record, so neither depends on which one ships). Scored
by `dev/brainyard/dev/thought_spike.clj`, whose restating patterns and echo
tests are listed in its docstring. Two runs of four pairs, two turns each,
per cell:

| model | wording | turns correct | tool: restates | tool: words | answer: restates | answer: words |
|---|---|---:|---:|---:|---:|---:|
| haiku | brainyard's | 8/8 | 57% | 44 | 88% | 42 |
| haiku | first draft | 8/8 | 26% | 29 | 88% | 31 |
| haiku | **shipped** | 7/8 | **0%** | 30 | 81% | 30 |
| sonnet | brainyard's | 8/8 | 0% | 25 | 56% | 22 |
| sonnet | first draft | 8/8 | 5% | 22 | 63% | 20 |
| sonnet | **shipped** | 8/8 | 5% | 23 | 56% | 22 |

- **The tool channel moves.** haiku's thoughts stopped restating and now say
  what happened and what follows, e.g. "The previous call failed because it
  used `path` instead of `dir`… Calling it again with the correct parameter
  name." sonnet's were already that.
- **The answer channel does not.** Neither telling the model "do not announce
  that you are answering" (the first draft) nor "this is not shown; write only
  what the answer leaves out, or the single word 'answering'" (shipped)
  changed it. The thought before an answer is where the model decides to stop,
  and it justifies that decision whatever it is asked. So the transcript
  hides an answering thought unless it adds to the answer (RFC-028,
  `byj-gly.280.3`).
- **The one wrong turn** (haiku, shipped wording) reported the 0 its own
  `tool$run` body returned. Its thought read as brainyard's would, "…returning
  a result of 0. No further tool calls are needed", so the error was the
  body's, not the thought's.
- The first draft differed only in its end, which read: "…earlier
  iterations, and do not announce that you are answering. When answering, say
  only what the answer itself does not, such as what you could not verify or
  what you assumed; if there is nothing, a few words." Every rate here is a
  small sample.

### st-memory

Same key names as brainyard's `st-memory` where the concept exists:
`:question :history-context :user-context :turn-number` (in),
`:iterations :trajectory-iterations :iteration-count :answer :terminated
:terminated-by :tool-calls :goal-achieved :next-user-prompt :last-reasoning
:last-channel :last-tool-results :last-error :consecutive-llm-failures
:no-action-streak :pending-notices :repaired-iter :usage`, plus
`:attempted-tool-calls` (the port's; below) and `:usage-calls` — one record per
predictor call (predictor, model, tokens, cache split, elapsed, cost, and the
prompt's zones with what each cost), which `end-turn` folds into the session so
`/usage` can table what its totals are made of (RFC-004). Windowed at
`agent/max-usage-calls`: a session must not grow a record per call forever.

### Native tool calls

A model behind claude-code may call a listed tool natively, be told it does not
exist, and answer so (provenance P-12, RFC-006). `recover-native-tool-calls`
runs after think-act: when the reply has no `tool-calls`, the attempts that name
bound tools become the tool calls, the answer and `goal-achieved` are cleared,
and a `TOOL CHANNEL` notice tells the model how tools are called
(`:agent.tools/recovered`). The iteration's reasoning, written while the model
believed the refusal ("the bash tool is not bound"), becomes
`recovered-thought`, in the iteration's record and in the event, so a front
end replaces the thought it showed above a call that then ran (byj-gly.339). A reply that lists tool calls itself recovered on
its own and keeps them. Told a tool is missing, the model goes looking for it
in the same call — `list-tools`, `get-tool-info`, `search` after a refused
`agent$init` — and those are refused too; they answer nothing it needs once
the tool is called, so a batch that calls any other tool leaves out the
node's `:lookups` (those three in `loops/react.edn`), said in the event's
`:dropped`, and a batch of lookups alone is kept. An attempt whose arguments
came wrapped as one JSON string under `args` is unwrapped, alone or beside
a `name` that is the tool's own (the whole call wrapped, byj-gly.395), unless
the tool takes an `args` input (`unwrapped-args`, byj-gly.352). Not in brainyard
(divergence `native-tool-calls-recovered`).

### Termination

| `:terminated-by` | when |
|---|---|
| `:answer-channel` | a non-blank answer (quote-only strings count as blank) |
| `:llm-error` | `:max-retries-on-llm-failure` (3) consecutive failed or unusable calls, or one fatal error (usage cap, missing CLI) |
| `:none-channel-loop-guard` | `:max-retries-on-llm-no-action` (3) consecutive replies with neither tool calls nor answer |
| `:max-iterations-exhausted` | the repeat ran out; the answer carries the latest thought |
| `:loop-error` | the repeat itself failed (loop-fallback) |
| — | the turn Task was cancelled: `ask-task` fails with the cancellation; in a session the cancellable scope records `:on-cancel` (`:cancelled`, RFC-004) |

## Protocol

### Repair

`repair` runs at most once per iteration (`:repaired-iter`), whether reached from
the llm-guard or the router's path D.

- **Unusable reply** (`:kind :malformed` or `:empty`): queue a `FORMAT ERROR`
  notice the model sees next iteration.
- **Failed call** (network, provider error, refusal): record `:last-channel
  :llm-error` and **no notice**. The model produced nothing; replaying a
  provider's error text into every later prompt bloats it, and a refusal's
  text can trip the same check again (provenance P-5).
- **No action**: queue a `NO ACTION` nudge.

### Channel conflicts

An answer in the same reply as tool calls is discarded and the tools run: the
answer was written before their output existed (brainyard
`coact-resolve-channel-conflicts-action`). A tool call naming an unbound tool
is dropped with an `UNBOUND TOOL` notice and does not count as an action.

### Tool dispatch

At most `:max-tool-calls` per iteration, through `fx/bounded` — brainyard's
replacement for its `pmap`: input order kept, a failure cancels the siblings,
one canceller for the batch. The action returns that Task (RFC-002). Bound 1
(**in order**) by default: models emit ordered write → update → read batches
that race when parallel (provenance P-2, divergence `parallel-tools-off`);
`:parallel-tools? true` bounds by the batch size. Results are printed strings,
capped at `:max-output-chars`.

### A turn is a Task

`agent/ask-task` returns the turn as a Task (brainyard `run-bt-task`);
`agent/ask` is `run-value!!` over it. Cancelling the Task interrupts the
in-flight LLM call (an HTTP read within one 250 ms slice) or tool, kills its
subprocesses (`fx/process-task`: the bash tool, the claude CLI), and runs
nothing further.

### The answer gate

A last look before the turn ends — brainyard's hybrid refine gate
(`coact_agent.clj`'s answer path), in the router's answer path rather than an
outer loop, so a refused answer is simply the same loop going round again with
the reason in front of it. Nothing is re-initialised; the trajectory, the tool
results and the iteration history all survive.

**It is off.** `:max-refinements` defaults to 0, as brainyard's does, and at 0
the decision is `:accept` before anything is asked — no model call, no prompt
cost, the answer path exactly as it was. §"What is not known about it" says why
that default is kept rather than merely inherited.

When a budget is set, one decision picks the branch — brainyard's order:

| when | branch | costs |
|---|---|---|
| the budget is spent | **accept** | nothing |
| the model reported `goal-achieved` false | **refine itself** | nothing — it already said so |
| otherwise | **confirm against the evidence** | one call |

The middle row is the point of a hybrid rather than an evaluation: buying a
second opinion to hear what the model has already volunteered is waste. An
omitted `goal-achieved` coerces to `false`, so it lands there too, which is
right — a model that did not claim to be done has not claimed to be done.

**The check is grounding before quality.** `react/evaluate-answer` asks whether
the answer states data no tool returned, not whether it could be better
written; the verdicts are brainyard's `COMPLETE`, `HALLUCINATED` and
`INCOMPLETE`, and the two refusals get different wording because they need
different fixes. It **accepts when it cannot judge**: no tool results means no
ground truth, so a general-knowledge answer is never evaluated, and an
unrecognised verdict or a failed call accepts too. A broken critic must not cost
a turn its answer.

A refusal clears `:answer` — the router is a fallback on a non-blank answer, so
leaving it would stamp it next pass — and sends the reason through the notice
channel a failed tool call already uses (divergence
`refusal-rides-the-notice-channel`).

### What is measured, and what is not

**brainyard never measured this.** Its `:max-refinements 0` came in with an
upstream import and was never revisited; the signature sat unreferenced for
months before being wired; the commit that wired it verified that the code path
executes, not that answers improved; and brainyard's own project config now
carries `:max-refinements 0` where a test comment still records it as 2.

The half that has to be true before the rest can matter has now been measured
here — **can the evaluator tell a grounded answer from an invented one?**
`dev/brainyard/dev/answer_gate_eval.clj`
(`REPS=3 jolt -A:dev -m brainyard.dev.answer-gate-eval sonnet`), `claude-code`,
2026-09-20. Twelve fixed cases judged directly, no turn in the way, so one call
is one measurement.

| | rate | |
|---|---|---|
| caught an invented answer | **18/18** | fabricated numbers, files, results, and a flat contradiction |
| left a grounded one alone | **12/18** | 6 refusals, every one `INCOMPLETE` |

Identical in all three reps — the judgement is stable, not a draw.

**The six refusals are the interesting half, and they are defensible.** They are
exactly two cases, repeated: an answer that says what it could NOT establish and
names what would settle it. The evaluator's own instruction defines `INCOMPLETE`
as *leaving the question half-answered when the record shows the rest was
reachable*, and both answers say in as many words that the rest is reachable. It
followed its instruction. What the gate does with such an answer is send the
turn back to go and read the file — which is the better answer, bought with an
extra pass.

That was a component test on constructed cases: it says the evaluator *can*
discriminate, not that the gate improves ordinary work. The second experiment
asked that. `dev/brainyard/dev/gate_turns_eval.clj`
(`REPS=2 jolt -A:dev -m brainyard.dev.gate-turns-eval sonnet`), same day: eight
questions about this repository with exact answers read from the source
beforehand, each run through a real turn with the gate off and on.

| | correct | calls per turn |
|---|---|---|
| gate off | **16/16** | 3.1 |
| gate on | **16/16** | 4.8 (**+55%**) |

**The gate is not inert — it refused 4 of the 18 answers it looked at.** It
corrected none of them, because there was nothing to correct: in sixteen turns
with tools to hand, the model stated nothing the tools did not support. Not one
confidently wrong answer in either arm.

All four refusals were the same question, both reps, and the objection was
*sound*: asked how many built-in tools there are, the model answered **6**
having grepped the definition rather than counted the entries, and the gate
said so — *"the answer says 6, but no tool result gives a count… read the index
and count its entries."* The count was right. It was also, strictly, a claim no
tool had returned.

So **the gate enforces grounding, not correctness**, and on this workload
grounding and correctness never came apart. It bought a 55% call surcharge to
re-derive an answer that was already right.

**The default stays 0**, now for a measured reason rather than an inherited
one. What the measurement does not cover: sixteen turns is a small sample, and
these were questions whose answers a tool could reach directly. A workload
where the model is more tempted to guess — questions about absent code, or
about a system it cannot inspect — could have a base rate that is not zero, and
that is the case in which this gate would earn its call.

## Invariants

| invariant | enforced by |
|---|---|
| Tool results reach the next prompt as printed data; the loop ends on a non-blank answer. | `agent-test/tool-then-answer` |
| The answer gate is inert at `:max-refinements` 0: no model call, no prompt cost. | `agent-test/the-answer-gate-is-inert-until-a-project-asks-for-it` |
| A self-reported failure and an ungrounded answer are both handled without buying an evaluation. | `agent-test/the-two-refusals-that-cost-nothing` |
| An answer the tools do not support is refused, and the reason returns as an instruction. | `agent-test/an-answer-the-tools-do-not-support-is-refused` |
| A critic that cannot judge accepts, and the gate cannot refuse forever. | `agent-test/a-critic-that-cannot-judge-does-not-cost-a-turn-its-answer` |
| A malformed reply is repaired with a notice and the loop continues. | `agent-test/malformed-reply-is-repaired` |
| Three consecutive failures abort with `:llm-error`. | `agent-test/repeated-llm-failure-aborts` |
| Three consecutive no-action replies abort with `:none-channel-loop-guard`. | `agent-test/no-action-guard` |
| An unbound tool call is dropped, visibly. | `agent-test/unbound-tool-is-dropped` |
| An answer given after rejected native tool calls is replaced by those calls, and its reasoning by `recovered-thought` where it is shown and kept; a reply with its own tool calls keeps them; the model's lookups are left out beside a call to anything else and kept alone; arguments wrapped as one JSON string are unwrapped, unless the tool takes `args`. | `agent-test/native-tool-calls-recovered`, `tui-test/a-recovered-call-replaces-the-thought-written-under-the-refusal` |
| An answer beside tool calls is discarded. | `agent-test/premature-answer-is-discarded` |
| A tool call blocked by an `:agent.tool-use/pre` hook ends the turn (`:tool-blocked-by-hook`) with the hook's answer, and no further LLM call. | `hooks-test/a-blocked-tool-call-ends-the-turn` |
| The iteration ceiling yields `:max-iterations-exhausted` with progress. | `agent-test/iteration-limit` |
| think-act's thought is asked for as `prompts/think-act-thought.md` words it, for the person watching; a judge's reasoning keeps brainyard's. | `predictor-test/the-loops-thought-is-for-the-person-watching` |
| Failed calls never reach the model-visible history; every call attempt is counted. | `agent-test/provider-failures-stay-out-of-the-prompt` |
| Cancelling a turn interrupts its LLM call and kills its bash tool and claude CLI children. | `cancellation-test/cancelling-a-turn-interrupts-the-llm-call`, `…-kills-its-bash-tool`, `…-kills-the-claude-cli` (each checks the child was running first) |

## Known gaps

- Code channel (CoAct path B): not ported, by decision — divergence
  `tool-only-signature`. One-off composition over the tool channel is
  `tool$run` — `byj-gly.11`.
- LLM summarization of a compacted span (RFC-014 tier 2) — `byj-gly.34`. The
  budget and rule-based elision are built (RFC-014).
- **The gate is measured and off** (above): its evaluator discriminates, it
  fires on about a fifth of the answers it sees, and on ordinary work it
  corrected nothing while costing 55% more calls.
- The gate judges one answer against this turn's tool results. brainyard's
  `eval-agent` — a different thing sharing the word — judges finished work
  against a plan's acceptance criteria; that is RFC-023's `todo/eval`.
- `context-briefing` and `recalled-memory` inputs of brainyard's ThinkActCode are not sent: `context-briefing` belonged to the code channel, and `recalled-memory` is a push this port deliberately does not make — recall is a tool (RFC-020).
