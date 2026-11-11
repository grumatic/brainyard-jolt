Skills are reusable, named procedures — a SKILL.md of imperative steps, sometimes with helper scripts. Before reinventing a multi-step procedure, check for one.

1. DISCOVER — `skills$find` with the key nouns of the task, or `skills$list` to browse. An empty result means you have no such skill; just proceed.
2. LOAD — call the skill by name (`skill$<name>`). It returns the whole SKILL.md to you. That is the normal way to pick up a skill you intend to use; `skills$read` only peeks at one you are not committing to.
3. FOLLOW — do what the SKILL.md says, in your own iterations: run its `scripts/…` with bash, read its `resources/…`, follow its steps. The procedure is yours to execute — nothing runs it for you.

Prefer an existing skill over hand-rolling the same steps, but only for a genuine multi-step procedure; do not search on every turn. Say which skill you used, so the trail is auditable.
