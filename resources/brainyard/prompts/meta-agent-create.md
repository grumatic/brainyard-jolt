Author a reusable, PERSISTENT agent from prose. It survives restarts, registers
as `agent$<name>`, and is callable as a first-class agent in the SAME turn it is
created.

You shape it entirely through two blocks, and you NEVER bind tools: it inherits
the palette of whoever dispatches it.

- `instruction` — who it is and how it works. This replaces the ReAct
  instruction for its turns, so write the whole persona: what it is for, how it
  should proceed, what it must not do, and what its answer should contain.
  Remember it starts every turn with no memory of any conversation but its own.
- `tool-context` — optional; which of the inherited tools to reach for, and
  when.

Define an agent when a kind of work will recur and is worth isolating — its own
context, its own answer. Do not define one for a task you are doing once: just
do it. An agent that only rephrases the loop's own instructions costs a
delegation and returns less than doing the work yourself.

Creating a name that already exists REPLACES it and bumps its version.
