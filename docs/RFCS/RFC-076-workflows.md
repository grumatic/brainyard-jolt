# RFC-076 — Workflows

**Status:** building (`byj-edw`): keeping a project's workflows
(`byj-edw.1`). Runs are `byj-edw.2`–`.4`. byj's own; brainyard has no
counterpart.

## Purpose

A project's `.brainyard/deps.edn` names its workflows (RFC-075, `byj-dls`):
graphs of stages a piece of work is taken through, each stage playing a
role, each link saying what one stage hands the next, a master role
supervising a run. byj reads, checks and writes that file. Until now only a
client — the workbench, from its own UI — authored workflows and ran them,
and only while its page was open. `/workflow` is byj's way to do both from a
session.

## Scope

**This layer decides** how a session lists, shows, adds, changes and
removes a workflow.

**It must not know** what a workflow is for, or what a role does: that is
the spec's, as the person wrote it.

**It hands** every write to RFC-075's `deps/write!`, which holds the file to
its schema and checks, snapshots the old one, and reads the new one back. It
never writes `deps.edn` any other way. The person's yes is asked through
RFC-012's permission channel.

## Model

`agent$workflow` is an agent (RFC-018) with every tool the session has but
itself. `/workflow` stands for it (RFC-004): `/workflow` alone lists the
workflows, and `/workflow WORDS` says what to do with one. Its `:config`
turns on the `workflow$*` commands, and its instruction is
`prompts/agents/workflow.md`.

- **`workflow$list`** gives each workflow's title and purpose, its stages in
  a run's order, and its master. It also gives the roles there are to play,
  and the errors `byj deps write` would refuse the spec with.
- **`workflow$show`** gives one workflow whole: each stage and the role it
  plays, with what that role does; each link and what it gives; its master;
  and the errors naming it.
- **`workflow$save`** adds a workflow, or changes one.
  - The fields given replace its own; the rest are kept.
  - `:stages` and `:links`, when given, are its whole stages and links.
  - `:roles` adds or changes roles. A role named by a stage or the master
    that the project lacks is added bare.
  - Data comes as a map, as EDN, or as JSON.
- **`workflow$remove`** takes one out. The roles it played stay.

A change is held to what `byj deps write` holds a spec to, and is refused
before the person is asked when:

- the id is not one;
- a stage or the master plays no role, a link names no stage, or the links
  go round;
- there is no stage;
- it changes nothing;
- the file has problems a write would drop.

Otherwise the person is shown the change and asked: a new workflow whole,
or a changed one's lines gone (`- `) and new (`+ `). It is written only on
their yes. With no person to ask, nothing is written.

The order a run works the stages in is `order`: each stage after every
stage that links to it, ties broken by name.

## API

`brainyard.workflow`:

- `words []`: `resources/brainyard/workflow/words.edn`.
- `order [workflow]`: the stage ids in a run's order.
- `describe [words spec id workflow]`: a workflow as lines.
- `give-words [words give]`, `gives-words [words gives]`,
  `role-words [spec role]`.
- `list-workflows [config args]` → `{:text}`.
- `show-workflow [config args]` → `{:text :found?}`.
- `save-workflow [config ctx args]` → `{:written? :text}`. args: `:id`,
  `:title`, `:does`, `:master` (`none` drops it), `:stages`, `:links`,
  `:roles`.
- `remove-workflow [config ctx args]` → `{:written? :text}`.
- `commands [config]`: `workflow$list`, `workflow$show`, `workflow$save`,
  `workflow$remove`.

Config: `:workflow-tools?` (default false) registers the commands;
`agent$workflow`'s `:config` turns it on.

## Invariants

| Invariant | Enforced by |
|---|---|
| A run works stages each after what links to it, ties by name | `workflow-test/stages-are-worked-in-link-order` |
| The workflows, roles and errors are listed, and one is shown whole | `workflow-test/workflows-are-listed-and-shown` |
| A workflow is added on the person's yes, with the roles it names, and the others are kept | `workflow-test/a-workflow-is-added-on-the-persons-yes` |
| A change writes only what is given and only on the person's yes; what `byj deps write` would refuse is refused before they are asked; removing one keeps its roles | `workflow-test/a-workflow-is-changed-and-removed-on-the-persons-yes` |
| `agent$workflow` has every tool but itself, its commands turned on, and is waited on | `agents-test/the-shipped-roster` |

## Known gaps

- Running a workflow (`byj-edw.2`).
