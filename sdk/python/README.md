# byj-agent-sdk

The Brainyard Agent SDK for Python: byj's agent as a part of your
application. It is the TypeScript SDK's surface (its
[README](../typescript/README.md) is the fuller guide) in snake_case, on
asyncio, with no dependency. MIT licensed; releases at
[grumatic/brainyard-sdk](https://github.com/grumatic/brainyard-sdk).

Underneath, the SDK starts `byj acp` — byj as an Agent Client Protocol agent
on its stdio. A session it opens is an ordinary byj session: the project's
`.brainyard/` is the agent's behaviour.

## Requirements

Python 3.10 or later, and byj installed (`brew install grumatic/tap/byj`):
the SDK runs `byj_path`, else
`$BYJ_BIN`, else `byj` on `PATH`, and says what an older byj lacks.

## Install

From the SDK's release on GitHub, [grumatic/brainyard-sdk](https://github.com/grumatic/brainyard-sdk):

```sh
uv add https://github.com/grumatic/brainyard-sdk/releases/download/v0.1.0/byj_agent_sdk-0.1.0-py3-none-any.whl
# or from git
uv add "byj-agent-sdk @ git+https://github.com/grumatic/brainyard-sdk@v0.1.0#subdirectory=python"
```

`pip install` takes either as well. It is not on PyPI. From a source
checkout, `uv add /path/to/python`.

## One prompt

```python
import asyncio
from byj_agent_sdk import query, SessionOptions

async def main():
    async for m in query("What does this project do?",
                         SessionOptions(cwd="/path/to/project", provider="claude-code", model="sonnet")):
        if m.type == "text":
            print(m.text, end="")
        if m.type == "result":
            print(f"\n{m.stop_reason}, ${m.cost_usd}")

asyncio.run(main())
```

## Sessions, tools, permission, hooks, predict

```python
from byj_agent_sdk import ByjClient, SessionOptions, Hook, Block, PermissionDecision, tool

@tool("order_lookup", "An order by its id",
      {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]})
async def lookup(args):
    return await orders.get(args["id"])        # any JSON, or a string

def no_force_push(payload, ctx):
    if "push --force" in (payload.get("tool-args") or {}).get("command", ""):
        return Block("not here")

async with await ByjClient.start() as client:
    s = await client.new_session(SessionOptions(
        cwd="/srv/support", agent="support",
        tools=[lookup],
        permission_mode="ask-each-time",
        can_use_tool=lambda req: PermissionDecision("allow" if req.kind == "read" else "deny"),
        hooks=[Hook("agent.tool-use/pre", no_force_push, tool="bash")],
        events=["session.turn/end"]))
    r = await s.run("Why was order 1042 refunded?", on_message=print)
    out = await client.predict({"ticket": ticket}, predictor="user/triage", cwd="/srv/support")
```

Messages (`m.type`): `text`, `thought`, `tool_call`, `tool_result`,
`permission` (`by` `callback` or `none`), `event`, `user` (a resumed
session's), and `result` — last, always: `answer`, `stop_reason`,
`terminated_by`, `iterations`, `usage`, `cost_usd`, `is_error`, `error`.

`session.run(text, output_schema={...})` (and `query(..., output_schema=)`)
gives the answer back also as that JSON Schema's data:
`result.structured_output`, or `result.structured_output_error` says why not.

`client.live_sessions(cwd)`, `client.ask_live(cwd, "worker", question)` and
`detach = await client.attach(cwd, "worker", on_event)` reach a session this
client did not start (a person's `byj run`, a `byj run --serve`) over its
session channel.

`client.load_session(id, options, on_history)` resumes a saved session;
`client.fork_session(id, options, on_history)` starts a new one where it
stands, leaving it as it was; `session.close()` ends one;
`session.cancel()` cancels the running turn; `session.set_permission_mode`.

## Tests

`BYJ_BIN=$(command -v byj) uv run --extra test pytest -q` runs them against
an installed byj, on a scripted mock model: no model is called and nothing is
spent. In byj's own source, without `BYJ_BIN`, they run against that checkout
(`jolt -m brainyard.main`), and `jolt test:sdk` runs them with the TypeScript
SDK's.
