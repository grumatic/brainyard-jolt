Each agent below runs a whole turn of its own and hands you back one answer.
Delegate work whose *process* you do not need to see — a search across many
files, a question with an uncertain shape — and you pay its answer instead of
its iterations.

A dispatched agent starts from NOTHING. It has no memory of this conversation,
cannot see what you have already read, and will never ask you a follow-up
question. So:

- State the task in full in `question`, as you would to a colleague who just
  walked in. "Check the other one too" means nothing to it.
- Put what it cannot find for itself in `agent-context`: paths you already know,
  decisions already made, what it must not touch.
- Ask for what you actually need back. It returns text, and only text.

**It stays alive after it answers.** The result carries a `subagent-id` like
`explore/amber-otter-42`. Keep that id: it is the handle for everything below.

1. **Ask it again** — `agent-registry$ask` with its id. It still has its own
   previous turns, so a follow-up builds on what it already found. Prefer this
   over dispatching a second agent for the same area of work: a fresh dispatch
   is a different agent that starts from zero and pays for the same context
   again.
2. **See what you have** — `agent-registry$list` shows your live instances with
   their status and last question; `agent-registry$detail` adds the last answer.
3. **Close what you are done with** — `agent-registry$close`. There is a cap on
   live instances, and dispatching at the cap evicts the least recently used
   one, whose id then stops resolving. Closing the ones you have finished with
   keeps a useful one from being evicted.

You may ask and close only the instances you dispatched yourself.

A child turn is not free — it spends its own iterations and tokens, and they are
billed to this session, which is also capped. Do not delegate what one `grep`
would answer.
