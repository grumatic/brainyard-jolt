# Changelog

Each version is the TypeScript and the Python SDK together, released as
`vX.Y.Z` with the npm tarball, the wheel and the source archive.

## Unreleased

- A session's turns, as byj keeps them to take back (RFC-069):
  `session.turns()`, `diff(turn?)`, `rewind(turn, {end})`, `undoTurn(turn)`,
  `undoRewind(n?)` (Python: `turns`, `diff`, `rewind`, `undo_turn`,
  `undo_rewind`), each answering with what changed on disk; and
  `forkSession(id, options, onHistory, {beforeTurn, keepFiles})` /
  `fork_session(..., before_turn=, keep_files=)`, a fork cut before a turn
  with the files put back as it began, said in the session's `fork`. A
  prompt's `result` says its `turn`, `filesChanged` and `checkpoint`. They
  need a byj that advertises the `turns` extension (byj-d8c.3).
- `forkSession(..., {beforeTurn, worktree: true | {branch, path}})` /
  `fork_session(..., before_turn=, worktree=)`: the fork made in a new git
  worktree, its files the turn's start, and worked in there; the session
  forked from and the project's work tree are left as they were. Where it
  is, its branch and the files it holds beyond HEAD are the session's
  `fork.worktree` (byj-8yt).
- `undoTurn` / `undo_turn` of a turn a later change conflicts with in every
  file answers its `conflicts`, and a `message` that says a conflict kept it
  back (byj-v55).

- `connectShared(url, token)` / `connect_shared(url, token)`: an agent shared on
  Brainyard Share, called from an application over A2A with the token its
  owner made for it — `ask`, `stream`, `cancel`, the conversation's context
  kept, each refusal a `SharedAgentError` of its own kind (RFC-060).
- `keepsQuestions` / `keeps_questions` on a shared agent: whether its share
  keeps its callers' questions and its answers for its owner, as its card's
  `KEEPS_QUESTIONS` extension says (byj-1wv.119).

## 0.1.0 — 2026-10-26

The first public release.

- `query`: one prompt, its messages streamed, byj started and ended around it.
- `ByjClient`: byj started once for any number of sessions; sessions opened,
  run, cancelled, closed, resumed (`loadSession`) and forked
  (`forkSession`); the permission mode set.
- Messages: `text`, `thought`, `toolCall`, `toolResult`, `permission`,
  `event`, `user` and a last `result` with the answer, stop reason, usage and
  cost.
- Your tools, run in your process, with JSON Schema inputs; hooks on byj's
  hook points, which may block, replace or modify a tool call; permission
  prompts answered by `canUseTool`.
- `predict`: a predictor's typed fields from one model call; an answer
  restated as a JSON Schema's data (`outputSchema`).
- Live sessions you did not start — a person's `byj run`, a `byj run
  --serve` — listed, asked, and attached to for their events.
- byj's capabilities checked when it starts: a byj without ACP v1 or one of
  the extensions the SDK needs is refused, by name.
