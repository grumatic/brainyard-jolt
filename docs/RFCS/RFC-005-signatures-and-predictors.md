# RFC-005 — Signatures and predictors

**Status:** partial.

## Purpose

Specifies what the model is asked and how that can change without code:
DSPy-style **signatures** (typed input/output fields → two messages and a JSON
schema) and **predictors** — named, declarative uses of a signature whose
parameters are data resolved per call.

brainyard sources: `clj-llm/core/{signature,prompt,schema,chain_of_thought}.clj`
for signatures; `clj-llm/core/predictor.clj` (the predictor value, params
layers, demos, tracing) and `agent/common/user_predictors.clj` (a predictor as
a file) for predictors; `behavior-tree/core/dspy_action.clj` for a tree node
running one.

## Scope

**This layer decides** the exact bytes of a prompt and how a reply becomes
outputs.

**It must not know** what loop runs it or what the outputs mean. The ReAct loop
interprets `tool-calls`; this layer only guarantees it is a vector.

**It hands** `{:predictor-id :params-source :outputs :reasoning :usage}` to the
leaf that ran it, and `:predictor/call` trace events to `:on-event`.

## Model

### A predictor spec

```clojure
{:id           "react/think-act"            ; stable: params-file path and trace key
 :strategy     :cot                         ; :cot → `reasoning` first; :predict → none;
                                             ; :system-one → probabilities, no prose (below)
 :description  "…"
 :instructions {:resource "brainyard/prompts/react-instructions.md"} ; or a string, or {:file …}
 :reasoning    {:desc {:resource "…"}}      ; optional, :cot only: what `reasoning` asks for
 :inputs  [[:question {:desc "User question"} :string] …]
 :outputs [[:tool-calls {:desc "…"} [:vector [:map {:closed true} …]]] …]
 :params  {…}}                              ; lowest-precedence params layer
```

A field is a malli map entry, `[k {:desc "…"} schema]` (or `[k schema]`), as
brainyard's `defsignature` fields are malli (byj-gly.203). The one schema is
the source of everything else, so nothing is written twice:
- the `(type)` the prompt's field list shows (`string`, `bool`, `list`,
  `object`, `int`, `number`);
- the JSON Schema that is sent twice, once in the system prompt and once as
  the provider's structured-output request (`schema/json-schema`:
  `malli.json-schema` with registry references inlined and `type` first). It
  produced the seven shipped prompts byte for byte as the hand-written
  schemas did;
- the empty value a missing field defaults to;
- the check a reply is held to (below).

A schema may refer to the shared registry (RFC-030). `:reasoning` is
reserved as an output. What the `reasoning` field asks for is brainyard's
`reasoning-field-desc` unless the spec gives `:reasoning {:desc …}`, as a
string, a resource or a file, as `:instructions` is. It then goes on the
signature as `:reasoning-desc`, and into the field list and the JSON schema
alike (`byj-gly.280.1`). A judge's reasoning is written for the model, and the
loop's thought for the person watching it (`byj-gly.280`). `validate-spec` and `validate-params` check a spec or params file
against a malli schema and name every problem, e.g. ``"`outputs.0` must be
[keyword {:desc …} schema] — a malli map entry"`` or ``"`tier` should be either
:light, :standard or :deep"``.

Built-ins are resources at `brainyard/predictors/<id>.edn`. User predictors are
files at `<predictor-root>/user/<name>.edn`; a disk id outside `user/` is
refused, and a built-in id is never redefined from disk (brainyard's
`source-predictor?` guard).

### The messages

System — joined with blank lines, in DSPy order:

```
Your input fields are:  1. `question` (string): …
Your output fields are: 1. `reasoning` (string): …   (cot only)
## Response format … JSON Schema … Shape of your reply …
Respond with a JSON object containing all output fields.   (predict: "the output fields.")
Here are examples of inputs and the JSON object to respond with: …   (demos only)
In adhering to this structure, your objective is:
<instructions>
## agent-core            ← zones, in the order the NODE declares
## session-context
## history-context       (omitted when blank)
## user-context
```

User — `name: value` per input in declared order, then *"Respond with a JSON
object containing the output fields, starting with the field `reasoning`, then
…"*.

**Zones come from the tree, not the predictor.** The think-act node declares
`:zones [:agent-core :session-context :history-context :user-context]`; each
zone's text is st-memory's value for that key if the loop set one this turn,
else the agent's stable zone from ctx (brainyard's `:stable-keys`).

### Params

Resolved on every call, highest precedence first; **the first layer with a
record wins whole** — no deep merge, so a params file means exactly what it says:

1. dynamic — `:params {id params}` in ctx (`--params FILE`), brainyard's `with-params`
2. `<params-root>/<id>.edn` for each root: `<working-dir>/.brainyard/programs`, `~/.brainyard/programs`
3. the spec's `:params`

A record may carry `:instructions`, `:field-descs {field desc}` (`:reasoning`
among them, above the spec's own), `:demos
[{:inputs :outputs :reasoning?}]`, `:max-demo-value-chars` (default 2000),
`:version`, `:compiled-by`. An invalid **file** is skipped and reported
(`:predictor/params-skipped`); invalid **dynamic** params throw.

### Which model a call runs on

A params record may also choose the model, as brainyard's may:

```clojure
{:lm "anthropic/claude-haiku-4-5-20251001"   ; a provider/model label
 :tier :light}                               ; or a work tier, resolved by table
```

Resolution, highest first — `resolve-lm`:

1. an **explicit override** for this call: `:lm-override`, an LM map or a label.
   A tree node sets it by naming `:lm` in its opts.
2. params `:lm`
3. params `:tier`, else the **spec's** `:tier`, through `:agent-lm-tiers` —
   and for `:light` with no entry there, the session provider's light model
   (below)
4. the **ambient** LM the caller passed — which is the session's current model
   rather than the one it started with, when a front end gave it an `:lm-box`
   and `/model` has since written one (`agent/current-lm`, RFC-004). The box
   sits inside step 4, not beside it: a node that pins a model still wins, and
   so do params.

brainyard's order reads `call-site :lm-config > :lm > :tier > the default LM`,
and it says "the call site's `:lm-config` always wins". That works there because
its call site usually passes nothing. Here every call already carries the
session's LM, so a literal port would make params permanently inert: the first
step is an *explicit* override and the ambient LM moves to the end, where
brainyard's default LM sits.

The dispatch half — which tier a subagent runs at, and the window it is clamped
into — is RFC-018's; the vocabulary (`llm/tiers`, `llm/coerce-tier`) and the
table are shared.

**`:standard` and `:deep` are inert until configured.** `:agent-lm-tiers` ships
`{:light nil :standard nil :deep nil}`, so those tiers resolve to nothing and the
call stays on the LM it had — brainyard's property, and the reason its own
feature entry has no gate: it is off by construction.

**The light tier** is the port's own (divergence `side-tasks-run-light`,
byj-gly.367). A predictor's `:light` with no table entry runs on the light model
of the ambient LM's own provider — the one `resources/brainyard/models.edn`
marks `:light? true` (`llm/light-lm`): claude-code's haiku, anthropic's and
Bedrock's Haiku 5.5, OpenAI's gpt-5.4-mini. Never another provider's, which
would bill an account the session does not use or need a login it does not
have. A provider with none marked (mock, ollama) keeps the call on the ambient
LM, and none of this is reported: it is the default working, not a hint
failing. The side tasks that only summarise or reword a session declare it —
`session/recap` and `session/rephrase-command`, and since byj-gly.378
`react/compact` — because with sonnet the
default model (byj-gly.366) each recap cost sonnet. Measured on recorded calls
by `dev/brainyard/dev/light_tier_spike.clj` (2026-10-18, 18 claude-code calls):

| predictor | sample | haiku $/call | sonnet $/call | what haiku lost |
|---|---|---|---|---|
| `session/recap` | 5 | 0.0017 | 0.0291 | nothing: same commits, test counts, beads and what was not checked |
| `session/rephrase-command` | 3 | 0.0016 | 0.0256 | once stated as fact what sonnet made conditional ("no instance exists yet") |
| `session/compact` | 1 | 0.0014 | 0.0262 | dependency shas and a memory note sonnet kept |

The compaction predictors were measured on their own (byj-gly.378,
`dev/brainyard/dev/compaction_tier_spike.clj`, 2026-10-18, 24 claude-code
calls). The inputs were rebuilt from real recorded work: saved sessions'
earlier turns for `session/compact`, long turns' older iterations for
`react/compact`. Each summary was scored by what later work needed: the share
of exact identifiers (paths, ids, hashes, numbers) in the compressed part that
the later turns or iterations used again, kept verbatim.

| predictor | samples | haiku | sonnet | |
|---|---|---|---|---|
| `session/compact` | 6 | 63% | 75% | sonnet as good or better on every one: stays on the session's model |
| `react/compact` | 6 | 27% | 21% | haiku no worse: `:tier :light` |

`/compact` runs when a person asks, and what it loses every later turn pays
for. Tier 2 fires on its own inside a long turn, as often as the budget asks.
Both models kept only about a quarter of what later iterations used, because
250 words cannot hold more. That was the prompt's to fix, not the model's
(byj-gly.383). `react/compact` now also lists the exact identifiers it
established in a `kept` field, outside the word cap. On the same six samples
haiku kept 51% and sonnet 43%, and every summary was still smaller than the
span it replaced, so haiku stays the light model. Subagent dispatch reads the table through
`llm/tier-lm` and is unchanged — a `:light` agent such as explore stays on its
caller's model until the table names one (RFC-018). To pin the light tier, set
`:agent-lm-tiers {:light "provider/model"}`; to pin one predictor, its params
`:lm`.

**A hint that cannot be honoured degrades to the next step**, with
`:predictor/lm-unresolved` or `:predictor/tier-unresolved` on the event channel.
An unresolvable model must not cost a turn that had a working LM, and a typo
that silently pinned every call to the session model would hide the typo.

**A label carries no credential.** It names a provider and a model; the key,
base URL and region come from the environment through `create-lm`, so a params
file can choose a model without becoming a secret.

### Reply → outputs

`json/parse-json-response` (fence strip, last balanced object fallback) →
`lift-flattened-tool-call` → a missing field's empty value (none for an enum)
→ decoding per schema type at any depth. A field that is `null` counts as left out
(byj-gly.215), and a string enum takes its member whatever the case, so a
critic's `"hallucinated"` is `HALLUCINATED` (byj-gly.216). Decoding turns a non-string where a
string is wanted into JSON text, reads a boolean from its text, and parses a
list or an object sent as JSON text. Then the reply is **checked against the
output schema**. Only declared keys survive. A reply that does not match
throws `:kind :malformed` with `:problems`, e.g. `` `verdict` should be either
"COMPLETE", … `` or `` `tool-calls.0.tool-args` ``. The loop's repair tells the
model exactly that, as it does for a reply that is not JSON at all
(`agent-test/a-reply-is-held-to-its-schema`, divergence
`a-reply-outside-its-schema-is-repaired`). A string whose line breaks the model escaped twice — no
real line break, and a literal `\n\n` or three `\n` — has them decoded
(`signature/escaped-twice`, byj-gly.194, divergence
`a-line-break-escaped-twice-is-decoded`; `agent-test/a-line-break-escaped-twice-is-a-line-break`). Blank content throws `:kind :empty`; unparseable throws
`:kind :malformed` — the ReAct loop's repair distinguishes these from a failed
call (RFC-003).

### System One — `:strategy :system-one`

**Proposed, not built** — `byj-gly.114`, gated on the decision `byj-gly.113`,
whose measurements **changed this design after it was first written here**. Read
them before implementing: the obvious shape is the wrong one.

A third strategy beside `:cot` and `:predict`, answered by a decision engine
returning calibrated probabilities instead of a model returning prose:
[jlt-commons/lev](https://github.com/jlt-commons/lev), on jolt, reached as a
provider (RFC-006, `byj-gly.115`).

#### One spec carries one question

The first version of this section claimed the spec shape did not change: a
spec's `:inputs` become the state, its `:outputs` become the questions, and one
encoder pass covers them all. That is lev's own request shape, and it is the
wrong shape for us, because **lev shares one state across every question in a
call** while its own rule is to put in the state the facts *the decision* needs
and only those. Those two are compatible only when every output wants the same
facts.

Measured over 62 commands with four questions (`byj-gly.113`), accuracy on
unambiguous items:

| question | thin shared state | facts added | its own state + rubric |
|---|---|---|---|
| `destructive` | **85.1%** | 78.7% | 51.1% |
| `needs_approval` | 48.9% | 59.6% | **61.7%** |

Every question's best column is a different column. Adding the facts
`needs_approval` needed cost `destructive` six points; adding the rubric that
made `needs_approval` gateable cost it twenty-seven more, down to chance. The
extra tokens are not inert — they push the thing being judged further from the
question, and a short command is drowned by a paragraph of policy.

So a `:system-one` spec **declares exactly one output**, and `validate-spec`
refuses a second. The alternative — `:in` per output rather than per spec — is
a larger change to the signature model for no gain, since separate questions
need separate calls anyway once their states differ.

**This forfeits the batching.** "One forward pass covers all of a call's
questions" is lev's efficiency argument and it is not available to us. Two
tailored calls is ~250 ms, still far inside an LLM round trip, but the RFC
should not claim a saving the design cannot take.

#### The state carries the rule, not only the facts

`:inputs` are not restricted to the values under judgement. A field whose value
is the **decision rule** — the rubric that would be an LLM's system prompt —
belongs in the state, and is the only thing that made a policy question usable:
`needs_approval`'s confidence was *inverted* (0.834 when right, 0.843 when
wrong) until the rule was supplied, and separated (+0.059) afterwards. Nothing
else moved it; more facts did not, and splitting the call did not.

The budget says the same. A signature's instructions share lev's 192-token head
with the option texts, while the state gets the ~430–480 remaining. **Policy
belongs in the state because that is where the room is.**

The converse is the trap: a rubric in front of a *perceptual* question is
actively harmful, not merely wasted. These are RLCD-trained classifiers scoring
`[MASK]` markers, not instruction-followers.

#### Input order is part of the request

lev serializes a map with key order kept, and the keys are tokens. Reordering
two adjacent inputs — `cwd` and `target`, nothing else changed — moved a
decision from 46.8% to 59.7%, and from 54.2% to 80.0% on the slice a gate would
auto-answer. **`:inputs` is an ordered vector and its order is load-bearing**,
as `:outputs` already is. A reordering is a behaviour change and must be treated
as one.

#### A decision is verified before it serves traffic

No `:system-one` predictor reaches production unmeasured. lev's own discipline
is that the state and questions are prepared, pinned against a golden and
checked before serving; ours is `dev/brainyard/dev/system_one_verify.clj`, which
fails a decision that truncates its state, omits its checkpoint (content routing
sent a shell command to an unprepared checkpoint and returned 503), misses its
accuracy or coverage floor, or **whose confidence does not separate** — the gate
accuracy cannot see, and the one that matters most, since inverted confidence
behind a `:fallback` auto-answers its own errors.

#### The mapping

| declared output | lev question |
|---|---|
| a `:boolean` field | `noul` |
| an `[:enum …]` field | `choice`, criteria from the enum |
| an ordinal | `score` — **open**, see below |

**What does not apply is refused, not ignored.** `validate-spec` rejects a
`:system-one` spec that declares `:zones` (there is no system prompt — the state
*is* the inputs), carries demos (an encoder takes no few-shot), declares a
`tool-calls` output (a classifier cannot drive the ReAct step), or declares more
than one output. There is no `reasoning` output, which is the point of System
One. Params still apply and still matter — `:field-descs` retunes a question's
instructions from a file with no rebuild.

**`run` returns `:confidence {field p}`** beside the outputs — lev's own
confidence, `1 - normalised entropy` for `choice` and `score`, `max(p, 1-p)` for
`noul`. Nothing in this layer decides what a low one means; the gate is a tree
node (`byj-gly.116`).

**Open, decide before building:** lev's `score` answer is a float expectation
over the level legend, so both an `:int` field (the level index) and a
`:string` field (the label) round away what the model actually said. Either carry the
raw expectation beside the coerced output, or leave `score` out of the first cut
— nothing in this repo needs an ordinal output today.

## API

`brainyard.predictor`:

| fn | contract |
|---|---|
| `(validate-spec spec)` / `(validate-params p)` | `nil` or a vector of every problem. |
| `(compile-spec spec [{:source :base-dir}])` | The predictor value; throws listing problems. |
| `(load-predictor id predictor-roots)` | Built-in resource, else (for `user/` ids) the first root's file. Throws with where it looked. |
| `(resolve-params p {:params :params-roots})` | `{:params :source :skipped?}` or `{}`. `:source` is `:dynamic`, a path, or `:default`. |
| `(apply-params signature params)` | Instructions and field descriptions applied. |
| `(run p inputs {:lm :lm-override :lm-tiers :zones :params :params-roots :on-event})` | One call. Emits `:predictor/call` on success and failure. |
| `(resolve-lm p params opts)` | The LM this call runs on, by the order above. |

`brainyard.signature`: `build-messages`, `system-message`, `user-message`,
`render-demos`, `output-json-schema`, `cot-outputs`, `normalize-outputs`.

Tree leaves (`brainyard.agent`): `:react/think-act` (node `:predictor`,
`:zones`) and the generic `:predictor/run` (node `:predictor`, `:in {field
mem-key}`, `:out {mem-key field}`, `:zones`).

## Invariants

| invariant | enforced by |
|---|---|
| A spec is validated with every problem reported; ids cannot escape a root. | `predictor-test/spec-validation` |
| Built-ins win; disk predictors are `user/` only, project root first; a file's `:id` matches its path. | `predictor-test/loading` |
| First layer wins whole; invalid files are skipped and reported; invalid dynamic params throw. | `predictor-test/params-resolution` |
| Instructions, field descriptions and demos come only from params; demos sit before the instructions; `:predict` asks for no reasoning. | `predictor-test/params-shape-the-prompt` |
| `reasoning` is described by a params record's `:field-descs :reasoning`, else the spec's `:reasoning {:desc}` (text or resource), else brainyard's words, in the field list and the JSON schema alike; a `:predict` predictor asks for none. | `predictor-test/a-predictor-names-its-own-reasoning` |
| The loop runs the predictor its node declares, with that node's zones, and traces the params source. | `predictor-test/the-loop-uses-its-declared-predictor` |
| Any tree can run any predictor through `:predictor/run`. | `predictor-test/generic-predictor-node` |
| A model choice resolves override > params `:lm` > `:tier` > ambient; `:standard` and `:deep` are inert until configured; an unresolvable hint degrades and is reported. | `predictor-test/lm-resolution-order` |
| `:light` with no table entry runs on the session provider's light model, else the ambient LM, unreported; never on another provider; subagent tiers do not take it. | `predictor-test/lm-resolution-order`, `agents-test/a-dispatch-runs-at-its-tier` |
| The ambient LM is the session's current one: a `/model` switch is what later calls run on, and a node's own `:lm` still beats it. | `session-test/a-switched-model-is-what-the-next-call-runs-on`, `session-test/model-switches-for-the-next-turn` |
| `:lm` must be a label and `:tier` one of the three, in params and in a spec. | `predictor-test/lm-params-are-validated` |
| A params file on disk can move a call to another model, and carries no credential. | `predictor-test/a-params-file-can-choose-the-model` |
| A tree node naming `:lm` runs its own step on that model, not the session's. | `predictor-test/a-node-may-pin-its-own-model` |
| With no params record, the prompt is byte-identical to the spec alone. | Checked by hand against the commit before predictors (223 lines, identical but the timestamp). No test pins the full prompt. |
| A `:system-one` spec declares exactly ONE output, and is refused when it declares zones, demos or a `tool-calls` output. | Nothing yet — `byj-gly.114`. |
| `:inputs` order is part of the request: reordering two inputs is a behaviour change, and a decision's pinned fingerprint covers it. | `dev/brainyard/dev/system_one_verify.clj` (not a test — it needs a running lev). |
| A `:system-one` decision does not serve traffic until every verification gate passes, confidence separation included. | `dev/brainyard/dev/system_one_verify.clj`, run deliberately. |
| A `:system-one` call that cannot reach its engine degrades to the next model rather than costing the turn. | Nothing yet — `byj-gly.115`. |
| Field descriptions and zone order match brainyard's ThinkActCode request. | `dev/brainyard/dev/replay.clj` `sections` against a logged brainyard call; not a test (provenance P-6). |

## Known gaps

- Dataset eval and compile are RFC-021: `:predictor/call` is its trace, and a
  compiled params file is what `resolve-params` reads.
- No `with-trace` capture API or persistent trace sink; `:on-event` is the only channel — `byj-gly.18`.
- `:strategy :system-one` is specified above and not built: `byj-gly.114` for
  the strategy, `byj-gly.115` for the transport, `byj-gly.116` for the gate that
  reads its confidence — all gated on `byj-gly.113`. That bead has now measured
  the path rather than argued about it, and the section above is the second
  design: the first one, which mapped a spec's `:inputs` to the state for ALL of
  its `:outputs`, is what the measurements refuted. One decision
  (`permission/destructive`) passes every verification gate on two-annotator
  ground truth; the motivating one (work-tier routing) has no ground truth in
  this repo and no volume to pay for itself. Neither `:system-one` nor its gate
  should be built for tier routing on present evidence.
- Signature fields are plain maps, not malli schemas: no field-level value validation beyond type coercion.
