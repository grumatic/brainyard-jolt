# @grumatic/byj-agent-sdk

The Brainyard Agent SDK for TypeScript: byj's agent as a part of your
application. Open a session in a project, send prompts, stream what happens,
answer permission prompts, give the agent tools that run in your process and
hooks that see or decide its tool calls, and call a predictor for structured
output. MIT licensed; releases at
[grumatic/brainyard-sdk](https://github.com/grumatic/brainyard-sdk).

Underneath, the SDK starts `byj acp` — byj as an Agent Client Protocol agent
on its stdio — and speaks ACP v1 with byj's extensions to it. A session it
opens is an ordinary byj session: the project's `.brainyard/` (agents,
predictors, prompts, hooks, config) is the agent's behaviour, and it leaves
the same trajectory and event log as any.

## Requirements

- Node 20 or later.
- byj installed (`brew install grumatic/tap/byj`): the SDK runs `byjPath`,
  else `$BYJ_BIN`, else `byj` on `PATH`. It carries no binary. It needs a byj that offers its extensions
  (`tools`, `hooks`, `events`, `predict`, protocol 1) and says so otherwise.

## Install

From the SDK's release on GitHub, [grumatic/brainyard-sdk](https://github.com/grumatic/brainyard-sdk):

```sh
npm install https://github.com/grumatic/brainyard-sdk/releases/download/v0.1.0/grumatic-byj-agent-sdk-0.1.0.tgz
```

It is not on the npm registry: your lockfile keeps the release file's URL and
hash, and a newer SDK is the newer release's URL. From a source checkout,
`npm install` in this directory builds `dist/`, and `npm install
/path/to/typescript` in your project links it.

## One prompt

```ts
import { query } from "@grumatic/byj-agent-sdk";

for await (const m of query({
  prompt: "What does this project do?",
  options: { cwd: "/path/to/project", provider: "claude-code", model: "sonnet" },
})) {
  if (m.type === "text") process.stdout.write(m.text);
  if (m.type === "result") console.log(`\n${m.stopReason}, $${m.costUsd ?? "?"}`);
}
```

`query` starts byj, opens a session, yields the prompt's messages and ends
byj — also when you stop reading early.

## Messages

| `type` | what |
|---|---|
| `text` | the agent's answer as it is written (whole, at the end, when the model does not stream) |
| `thought` | its reasoning |
| `toolCall` | a call: `id`, `title` (`bash ls -la`), `kind` (`read`, `edit`, `execute`, `fetch`, `other`), `input` |
| `toolResult` | its result: `id`, `status` (`completed`, `failed`), `output` |
| `permission` | a permission prompt and how it was answered: `by` `callback` or `none` |
| `event` | one of byj's own events, when the session asked for them (`events`) |
| `user` | a resumed session's earlier question |
| `result` | last, always: `answer`, `stopReason` (`end_turn`, `cancelled`, `max_turn_requests`), `terminatedBy`, `iterations`, `usage`, `costUsd`, `isError`, `error` |

## Sessions that last

```ts
import { ByjClient } from "@grumatic/byj-agent-sdk";

const client = await ByjClient.start();
const s = await client.newSession({ cwd: "/srv/support", agent: "support" });
const r = await s.run("Why was order 1042 refunded?", (m) => { /* each message */ });
const again = await s.run("And 1043?");
s.cancel();                                   // the running turn, if any
await s.setPermissionMode("ask-each-time");
await client.close();                         // every session closed
```

One byj process holds any number of sessions, each in its own project.
`client.loadSession(id, options, onHistory)` resumes a saved one;
`client.forkSession(id, options, onHistory)` starts a new one where a saved
one stands, leaving that one as it was; `session.close()` ends one.

## Your tools

```ts
import { tool } from "@grumatic/byj-agent-sdk";

const lookup = tool("order_lookup", "An order by its id",
  { type: "object", properties: { id: { type: "string", description: "the order id" } }, required: ["id"] },
  async ({ id }) => orders.get(id),           // any JSON, or a string
  { effects: ["net/read"] });                 // what byj's gate sees; sdk/call when none

await client.newSession({ cwd, tools: [lookup] });
```

The schema is JSON Schema in the subset tools use: `object` with
`properties` and `required`, `string`, `integer`, `number`, `boolean`,
`array` with `items`, `null`, `enum`, `description`. Anything else is refused
when the session opens, by name. A tool runs in your process, outside byj's
sandbox. A thrown error is the call's failure, which the agent reads.

## Permission

byj's permission gate asks before the calls a project's config says
to ask about (`tool-approval-patterns`, `tool-ask-effects`) in the modes that
ask. `canUseTool` answers:

```ts
canUseTool: async (req) => req.kind === "read" ? { behavior: "allow", always: true } : { behavior: "deny" }
```

`always` is remembered for the session. With no `canUseTool`, every prompt is
refused and a `permission` message with `by: "none"` says so.
`permissionMode`: `auto`, `ask-each-time`, `auto-approve`, `deny-by-default`.

## Hooks

```ts
hooks: [{ event: "agent.tool-use/pre", tool: "bash",
          handler: (p) => p["tool-args"]?.command?.includes("push --force") ? { block: "not here" } : undefined }]
```

`event` is one of byj's hook points, by its own names. On the
gated `agent.tool-use/pre` a hook may return `{ block: reason }`,
`{ replace: result }` or `{ modifyArgs: args }`; nothing allows. byj waits
`timeoutMs` (30 s) for an answer and then allows, unless `failClosed`.

## Predict

```ts
const { outputs } = await client.predict({
  cwd, signature: {
    instructions: "Classify the ticket.",
    inputs: [{ name: "ticket" }],
    outputs: [{ name: "category", schema: { enum: ["billing", "bug", "other"] } }],
  },
  inputs: { ticket },
});
```

One model call that answers in named, typed fields: no session, no tools.
`predictor: "user/triage"` names one kept in the project's
`.brainyard/predictors/user/`, whose wording and params are tuned there.

## An answer as data

```ts
const r = await session.run("What happened to order 1042?", undefined, {
  outputSchema: { type: "object", properties: { order: { type: "string" }, refunded: { type: "boolean" } },
                  required: ["order", "refunded"] },
});
r.answer            // the agent's answer, in words
r.structuredOutput  // { order: "1042", refunded: true } — or r.structuredOutputError says why not
```

The turn runs as any does; its answer is then restated as the schema's data
by one more model call (byj's `sdk/structured-answer` predictor), whose cost
is in `costUsd`. `query({ prompt, options: { …, outputSchema } })` too.

## Sessions you did not start

```ts
const rows = await client.live.list("/path/to/project");          // its live byj sessions
const r = await client.live.ask("/path/to/project", "worker", "how far along?");
const w = await client.live.attach("/path/to/project", "worker", (e) => console.log(e.event),
                                   { events: ["session.turn/end"] });
await w.detach();
```

A person's `byj run` or a headless `byj run --serve`, named by its id, a
prefix or its label, reached over its session channel: a question is a turn
of that session's, under its own gate.

## Options

`SessionOptions`: `cwd` (required), `agent`, `provider`, `model`, `loop`,
`config` (byj's keys: `{"max-iterations": 8}`), `permissionMode`,
`canUseTool`, `tools`, `hooks`, `events` (`"all"` or names), `mcpServers`,
`label`, `commands` (allow slash commands in a prompt).
`ClientOptions`: `byjPath`, `byjArgs`, `processCwd`, `env`, `stderr`.

## Tests

`BYJ_BIN=$(command -v byj) npm test` runs them against an installed byj, on
a scripted mock model (`-p mock -m script:FILE`): no model is called and
nothing is spent. In byj's own source, without `BYJ_BIN`, they run against
that checkout (`jolt -m brainyard.main`), and `jolt test:sdk` runs them.
