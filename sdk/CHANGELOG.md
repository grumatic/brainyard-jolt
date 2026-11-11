# Changelog

Each version is the TypeScript and the Python SDK together, released as
`vX.Y.Z` with the npm tarball, the wheel and the source archive.

## Unreleased

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
