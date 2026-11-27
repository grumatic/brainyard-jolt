**byj v0.3.22** — `/init` leaves AGENTS.md's block to the byj that wrote it when that byj is newer, Claude 4.5 and later have room to write several files in one reply, `:max-output-tokens` sets how long a reply may be, and a cancelled process is ended even when it ignores SIGTERM. Full notes in `CHANGELOG.md`.

- **`/init` no longer puts back an older byj's words.** The block it writes
  in AGENTS.md names the byj that wrote it, and `/init` leaves one a newer
  byj wrote and says so: a container's byj, the release before the one on
  your machine, had rewritten it with its older words. A block with the
  same words is left as it is, so `/init` again changes nothing. A byj
  before this one still rewrites it.
- **AGENTS.md's block says where things are kept now**: `.brainyard/` is the
  repository's, shared and committed, and what a project's runs leave is
  kept in `~/.brainyard/projects/<slug>/` (`byj project path`).
- **`byj project add` refuses a folder inside another project** — in a
  repository whose root is elsewhere, or under a registered project's folder
  — and names the project to add instead. A nested repository, a worktree
  and a submodule are their own projects; a folder holding projects is
  added and says which. A project taken off the list with `byj project
  unlist` is shown as such, and adding it again lists it.
- **A process that has ended holds no session**: a session whose process
  ended without being waited for can be opened again, and a workflow run's
  stage sessions are closed as a team's members are.
- **A Claude 4.5 or later has room to write several files in one reply.**
  It may write 32000 tokens, where every model had 8192: on Bedrock, Opus 5.5
  thinks before it answers even unasked, and a turn writing several files
  stopped with "LLM response was plain text with no JSON object" or was cut
  off. Any other model keeps 8192; the context budget leaves room for the
  longer reply.
- **`:max-output-tokens` sets how long a reply may be.** Set, it is what
  every model is asked for — the session's, after `/model`, and a
  subagent's — and the room the context budget keeps; unset (now the
  default), each model's own. It takes effect from the next session. A
  subagent on another model no longer asks for its caller's size, which a
  model that writes less would refuse.
- **The system prompt is sent once to an OpenAI-format provider** (openai,
  openrouter …): its zones went beside it, the prompt twice. The event log's
  `:agent.llm/request` gives each zone's name and length rather than its
  words again, about halving what a turn writes there.
- **A cancelled command, team member or delegation's process is ended even
  when it does not stop on SIGTERM**: what is left of it 5 s later is
  killed. A team member could outlive its leader's session.
- **A long Bedrock reply no longer fails at 60 s** with "Read timeout on
  endpoint URL": the aws CLI waits as long as the call may take. Each call's
  request and reply, written to a folder in the temporary directory, are
  removed when it ends.
