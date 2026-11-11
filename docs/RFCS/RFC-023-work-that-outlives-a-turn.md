# RFC-023 — Work that outlives a turn

**Status:** implemented (`byj-gly.17`) as a checklist substrate plus a bundled
pipeline. brainyard's todo-agent and exec-agent are deliberately not ported, and
§"Why this is not four agents" is the reason.

## Purpose

Specifies how work that takes more than one turn is carried: what is remembered
between turns, and how the stages that produce and consume it are composed.

brainyard sources: `common/{todo,exec,workflow}.clj` and their agents;
docs `design/{todo,exec}-agent-design.md`.

## Scope

**This layer decides** what a checklist is and what may be said about an item.

**It must not know** what any item means, how one gets done, or what order the
stages run in — that is the workflow's (RFC-022).

**It hands** six `todo$*` commands to the registry (RFC-007) and a file to
whoever reads it next, including a person.

## Why this is not four agents

brainyard runs implementation as a chain of conversational specialists —
`plan-agent` → `todo-agent` → `exec-agent` → `eval-agent` — each writing a
dossier the next parses out of frontmatter, and each refusing to start unless
the previous one's post-flight verdict was `pass`.

**That is a workflow**, and RFC-022 already builds workflows. Expressed as a
tree the chain needs no new mechanism, because a subagent dispatch is a tool
call and `:tool/run` runs tools:

```clojure
[:action {:id :impl/plan :fn :tool/run :tool "agent$plan"
          :in {:question :question} :out {:plan :answer}}]
```

What the tree gives that the dossier chain does not:

| | the chain | the tree |
|---|---|---|
| handoff | frontmatter parsed by `plan$read-dossier`, `todo$read-dossier`, `exec$read-dossier` | a blackboard key, **checked before the run** |
| gating | prose: *"C2. assert `post.verdict` = pass"* | a `:condition` in a `:fallback` |
| changing the order | edit four agents' instructions | edit one EDN file, no rebuild |
| inspecting a stage | read its source | `flow$brief`, `flow$probe` |

**The check earns its place immediately.** The first draft of the bundled
pipeline read `:step` and `:remaining` at the eval gate, both written inside the
`:repeat` body — and a repeat may run zero times, so neither was guaranteed.
`flow$check` reported it before anything ran. In brainyard's shape that is a
`nil` discovered at runtime, or never.

So each kind of step goes where it belongs:

| step | is | why |
|---|---|---|
| plan, edit | an **agent** | judgement that needs tools and iterations |
| plan → items, eval | a **predictor** | judgement that needs neither; a signature is data (RFC-005) |
| the order and the gates | a **tree** | behaviour, checked (RFC-022) |
| the checklist | a **file** | the one thing a tree cannot do |

`todo-agent` and `exec-agent` therefore have nothing left to be. `eval-agent`
(`byj-gly.12`) reduces to the `todo/eval` predictor for the same reason.

## Model

### A blackboard dies; a list does not

A tree's blackboard lives for one turn. RFC-016 persists a session's
`:previous-turns`, not its blackboard. So anything that must survive — which for
implementation work is *what is left to do* — has to be somewhere else.

```
<project-dir>/.brainyard/todos/<slug>.md
```

```markdown
# Add a retry budget

- [x] [edit] add the counter to llm.clj
- [ ] [edit] thread it through with-retry
- [ ] [check] run the suite
```

**Markdown with checkboxes**, as brainyard's is, because a person edits it too:
reordering, striking one out, adding what the model missed. Everything the
parser does not recognise is left alone when the file is rewritten, so a note in
the margin survives a machine write.

The `[tag]` is a one-word note on what kind of work an item is — `edit`,
`check`, `read`, `manual`. It is **optional, and a reference rather than a
route**: the pipeline hands it to whoever does the item and lets them read it,
and an item without one is not a special case, it simply carries no hint.

Routing per tag was considered and declined (`byj-gly.68`). brainyard's
exec-agent branches five ways on it, and a branch per tag here would be a second
place to keep in step with the vocabulary — change the words and the tree goes
quietly wrong. The agent doing the work is better placed to read a hint than the
tree is to act on one. What that gives up is named under Known gaps.

### A specialist is allowed to decline

`agent$plan` is built to say no. It refuses a task too small to be worth a plan,
and asks one question when it lacks something it needs — and a pipeline that
carried on regardless would turn a considered refusal into busywork, which is
exactly what it did until `byj-gly.69`.

Two things make the refusal land where it belongs, and neither is new
machinery:

```clojure
[:action {:id :impl/plan … :out {:plan :answer :answer :answer}}]
[:condition {:id :impl/planned? :fn :mem/match? :key :plan :pattern "Saved plan:"}]
```

The answer is mapped onto **both** keys — the plan to work from, and the turn's
answer should there be nothing to work from — so a refusal is already where a
turn's answer belongs and nothing has to copy it. The condition then decides
whether there is a plan at all, and the whole work path sits inside a
`:fallback` whose other branch simply stamps what the specialist said.

**Branching on prose is the cost of dropping the dossier.** brainyard's chain
reads a structured `post.verdict` out of frontmatter; the plan is prose here
(divergence `plan-agent-has-no-dossier`), so the tree reads the stable prefix
the agent's own instruction promises. `:mem/match?` is the general leaf for
that: a workflow composing agents has to branch on what they answered, and an
answer is prose.

### Continuing, rather than planning again

A checklist outliving its turn is only useful if the next turn uses it. The
bundled pipeline therefore starts with a `:fallback`: **resume** when the caller
named a list that exists and still has work, **plan afresh** otherwise.

```clojure
[:fallback {:id :impl/start}
 [:sequence {:id :impl/resume}
  [:condition {:id :impl/named? :fn :mem/true? :key :todo-slug}]
  [:action {:id :impl/existing :fn :tool/run :tool "todo$read" …}]
  [:condition {:id :impl/work-left? :fn :mem/true? :key :todo-has-work}]]
 [:sequence {:id :impl/fresh} … agent$plan … todo/from-plan … todo$create …]]
```

**Naming the list is the caller's** (`by ask --todo <slug>`, config
`:todo-slug`): only they know which piece of work this turn continues. A slug
that names nothing is not an error — the node fails, the fallback takes the
other branch, and the turn plans. Three checks, no new mechanism.

**`:has-work` is a boolean on purpose.** A tree branches on truthiness and `0`
is truthy in Clojure, so a remaining *count* would resume a finished list and
re-run the gate on it forever.

**The list records the plan it came from** (`Plan:` under the title) — the one
part of brainyard's dossier chain a resumed run genuinely needs, since otherwise
the gate at the end has nothing to judge against. brainyard carries the same
thing as `source.plan_dossier`.

The memory chain checked something worth having for free here: a `:fallback`
guarantees only what EVERY branch writes, so `check` confirms both the resume
and the fresh branch leave `:plan` and `:remaining` behind for the gate.

### Marking is refused rather than guessed

`todo$done` takes an index or enough of the item's text to be unique. Two
matches is an error naming both, not a coin flip — brainyard's exec-agent warns
against "a drifting numeric index" and matches on text; both are offered, and
neither guesses.

An empty list is refused at creation. A checklist with no items reads later as
finished work.

## API

`brainyard.todo`: `todos-dir`, `todo-file`, `list-slugs`, `parse`, `render`,
`read-todo` (which reports `:remaining` and a boolean `:has-work`), `create!`
(which records the plan it came from), `add!`, `mark!`, `next-item`, `commands`.

Commands: `todo$create`, `todo$list`, `todo$read`, `todo$next`, `todo$done`,
`todo$add`.

Bundled behaviour: `resources/brainyard/loops/plan-act-eval.edn`, and the predictors
`todo/from-plan` and `todo/eval`.

Two general leaves were added to RFC-022's set, because a workflow over tools
and agents has to branch and seed on what they wrote:

| leaf | does |
|---|---|
| `[:condition {:fn :mem/true? :key k}]` | the blackboard key is truthy — also usable as a `:repeat`'s `:until` |
| `[:action {:fn :mem/set :set {k v}}]` | seed keys a later node needs before the node that fills them properly |
| `[:condition {:fn :mem/match? :key k :pattern re}]` | the text a step left behind matches — how a tree branches on what an agent *answered* |

`:repeat` now hands its own opts to the `:until` predicate, as a leaf gets its
node, so `:until` can be parameterized by data rather than naming a fn per loop.

`:mem/set` resolves a `:config/…` value as a node opt does, which is how a tree
learns something the caller chose.

Config (RFC-013): `:todos?` (true), `:todo-slug` (nil). CLI: `--todo SLUG`.

## Invariants

| invariant | enforced by |
|---|---|
| A list is on disk as markdown, and a later turn reads what an earlier one left. | `todo-test/a-list-outlives-the-turn-that-wrote-it` |
| A person's own edits survive a machine write. | `todo-test/a-list-outlives-the-turn-that-wrote-it` |
| An item may say how it should be done, and a workflow can read it. | `todo-test/an-item-may-say-how-it-should-be-done` |
| An ambiguous mark is refused with its matches; an empty list is refused outright. | `todo-test/marking-is-refused-rather-than-guessed`, `todo-test/the-commands` |
| A slug cannot escape the todos directory. | `todo-test/the-commands` |
| The bundled pipeline checks clean. | `todo-test/the-shipped-pipeline-is-sound` |
| **Four stages compose with two agents and two predictors — no exec-agent.** | `todo-test/four-stages-compose-without-a-fourth-agent` |
| A named list is continued, not planned again: no second plan, no second list. A slug naming nothing, or naming a finished list, plans afresh. | `todo-test/a-named-list-is-continued-rather-than-planned-again` |
| **A refusal or a question from the plan agent ends the turn in its own words, and authors nothing.** | `todo-test/a-plan-agent-that-declines-ends-the-turn` |

## Does it pay?

`dev/brainyard/dev/pipeline_eval.clj`
(`REPS=2 jolt -A:dev -m brainyard.dev.pipeline-eval sonnet`), `claude-code`,
2026-09-20. Three multi-file tasks — a rename across three files, three planted
bugs, a function added and wired into two call sites — each run twice through
the ReAct loop and twice through this pipeline, same tools, same model, scored
per criterion by a script.

| | score | LLM calls | prompt chars | wall |
|---|---|---|---|---|
| ReAct | **1.000** | 4.3 | 141k | 29s |
| this pipeline | 0.958 | **36.5** | 696k | 280s |
| | diff −0.042, margin 0.529 | **8.4×** | 4.9× | 9.7× |

**The cost is measured; the quality difference is not measurable here.** ReAct
scored 1.000 on all six runs — a ceiling, so there was no room for the pipeline
to show a gain, and the Welch margin (0.529) swamps the difference either way.
The pipeline's one imperfect score was a grader artefact: it fixed
`(defn add [x y] (- x y))` by writing `(defn add [a b] (+ a b))`, which is
correct, and the criterion greps for `(+ x y)`.

So the honest verdict is **narrow and decisive**: on work a single turn can
finish, planning it first costs more than eight times the calls and buys
nothing. The default loop stays ReAct, and this pipeline is something a caller
asks for by name.

**What it does not say.** These tasks fit in one turn, which is exactly the case
the pipeline is not for. Its reason to exist is work that *exceeds* a turn —
where the checklist survives and `--todo` resumes it — and nothing here tests
that, because a task that large is expensive to run twice and hard to grade. The
claim that survives is the modest one: a durable checklist lets work continue
across turns, which `todo-test` proves. The claim that a plan-first pipeline
produces better work remains unmeasured.

**It also found a bug**, since fixed (`byj-gly.69`). On the three-bugs task
`agent$plan` refused — correctly, by its own rule that three one-line fixes are
not worth a plan — and the tree fed that refusal to `todo/from-plan`, which
authored a checklist out of a refusal message. This RFC claimed in prose that a
NEED or REFUSE answer ends the turn; nothing checked, so it did not. See
"A specialist is allowed to decline" below.

## Known gaps

- **Every item goes to the same agent**, with its `via` as context. A
  `check` item ("run the suite") reaches an edit specialist whose shell is held
  to lint and compile commands, so it will report rather than run it. That is
  the cost of not routing (`byj-gly.68`), and it is paid knowingly: the fix is a
  step agent that can do more than edit, not a tree that branches five ways.
- **A `manual` item is ticked like any other.** The loop ticks when the step
  returns, and an agent reporting "a person must do this" has returned. Nothing
  routes on the tag, so nothing stops it — brainyard's exec-agent leaves the box
  unticked and surfaces the item instead. Until that matters in practice, the
  gate at the end is what catches it: a plan is judged by its acceptance
  criteria, not by its boxes.
- **On work one turn can finish, the pipeline costs 8× and wins nothing**
  (below). Whether it pays on work that exceeds a turn is untested.
- **No workflow substrate.** brainyard's `workflow.clj` and
  `state_machine_agent.clj` are a further layer over this one; not ported.
