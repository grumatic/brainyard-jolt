# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

"""The Python SDK against a real ``byj acp`` (RFC-053, byj-g5n.7). No model is
spent: byj runs ``-p mock -m script:FILE``, a scripted mock. byj is this
checkout (``jolt -m brainyard.main``) unless BYJ_BIN names a binary."""
from __future__ import annotations

import asyncio
import os
import tempfile
from pathlib import Path

import pytest

from byj_agent_sdk import (Block, ByjClient, ByjVersionError, Hook, PermissionDecision, ResultMessage,
                           SessionOptions, query, tool)

REPO = Path(__file__).resolve().parents[3]
BYJ = ({"byj_path": os.environ["BYJ_BIN"]} if os.environ.get("BYJ_BIN")
       else {"byj_path": "jolt", "byj_args": ["-m", "brainyard.main"], "process_cwd": str(REPO)})
QUIET = {"builtin-agents?": False, "memory?": False, "memory-hint?": False}


def project(replies: list[str], files: dict[str, str] | None = None, **opts) -> SessionOptions:
    """A project with a scripted mock (``-m script:FILE``): each reply an EDN map."""
    d = Path(tempfile.mkdtemp(prefix="byj-sdk-py-"))
    for f, text in (files or {}).items():
        (d / f).write_text(text)
    (d / "replies.edn").write_text("[" + "\n".join(replies) + "]")
    config = {**QUIET, **opts.pop("config", {})}
    return SessionOptions(cwd=str(d), provider="mock", model=f"script:{d / 'replies.edn'}", config=config, **opts)


async def collect(it):
    return [m async for m in it]


def test_query_streams_a_turn_and_ends_with_its_result():
    p = project(['{:reasoning "r" :tool-calls [{:tool-name "read-file" :tool-args {:path "notes.md"}}] :answer ""}',
                 '{:reasoning "r" :tool-calls [] :answer "the notes say hi" :goal-achieved true}'],
                {"notes.md": "hi"})
    ms = asyncio.run(collect(query("what do the notes say?", p, **BYJ)))
    call = next(m for m in ms if m.type == "tool_call")
    done = next(m for m in ms if m.type == "tool_result")
    assert call.kind == "read" and call.title.startswith("read-file notes.md")
    assert done.status == "completed" and "hi" in done.output
    assert any(m.type == "text" and m.text == "the notes say hi" for m in ms)
    r = ms[-1]
    assert isinstance(r, ResultMessage)
    assert (r.is_error, r.stop_reason, r.answer) == (False, "end_turn", "the notes say hi")
    assert isinstance(r.iterations, int)


def test_a_tool_of_the_applications_is_called_after_can_use_tool_allows_it():
    calls, asked = [], []

    @tool("order_lookup", "An order by its id",
          {"type": "object", "properties": {"id": {"type": "string", "description": "the order id"}},
           "required": ["id"]})
    async def lookup(args):
        calls.append(args)
        return {"id": args["id"], "status": "refunded"}

    def allow(req):
        asked.append(req.title)
        return PermissionDecision("allow")

    p = project(['{:reasoning "r" :tool-calls [{:tool-name "order_lookup" :tool-args {:id "1042"}}] :answer ""}',
                 '{:reasoning "r" :tool-calls [] :answer "refunded" :goal-achieved true}'],
                tools=[lookup], permission_mode="ask-each-time", can_use_tool=allow,
                config={"tool-ask-effects": ["sdk/call"]})
    ms = asyncio.run(collect(query("why was 1042 refunded?", p, **BYJ)))
    assert calls == [{"id": "1042"}]
    assert asked == ["order_lookup"]
    assert any(m.type == "permission" and m.by == "callback" for m in ms)
    assert ms[-1].answer == "refunded"


def test_with_no_can_use_tool_a_prompt_is_refused_and_said_so():
    p = project(['{:reasoning "r" :tool-calls [{:tool-name "read-file" :tool-args {:path "a.md"}}] :answer ""}',
                 '{:reasoning "r" :tool-calls [] :answer "could not read it"}'],
                {"a.md": "A"}, permission_mode="ask-each-time", config={"tool-ask-effects": ["fs/read"]})
    ms = asyncio.run(collect(query("read a", p, **BYJ)))
    assert any(m.type == "permission" and m.by == "none" and m.decision.behavior == "deny" for m in ms)
    assert any(m.type == "tool_result" and "denied" in m.output for m in ms)


def test_a_hook_blocks_a_call_and_one_on_the_opening_is_answered():
    seen = []
    p = project(['{:reasoning "r" :tool-calls [{:tool-name "bash" :tool-args {:command "touch made-it"}}] :answer ""}',
                 '{:reasoning "r" :tool-calls [] :answer "done"}'],
                permission_mode="auto-approve",
                hooks=[Hook("agent.session/created", lambda p, c: seen.append("created")),
                       Hook("agent.tool-use/pre", lambda p, c: (seen.append(p["tool-name"]), Block("no shell"))[1],
                            tool="bash")])
    asyncio.run(collect(query("make it", p, **BYJ)))
    assert seen == ["created", "bash"]
    assert not (Path(p.cwd) / "made-it").exists()


def test_a_client_holds_sessions_events_a_cancel_a_mode_predict():
    async def go():
        async with await ByjClient.start(**BYJ) as client:
            assert client.agent_info["name"] == "byj"
            p = project(['{:reasoning "r" :tool-calls [{:tool-name "bash" :tool-args {:command "sleep 30"}}] :answer ""}'],
                        permission_mode="auto-approve", events=["session.turn/start"])
            s = await client.new_session(p)
            assert any(m["id"] == "ask-each-time" for m in s.modes)
            ms = []
            async for m in s.prompt("wait"):
                ms.append(m)
                if m.type == "tool_call":
                    await s.cancel()
            assert ms[-1].stop_reason == "cancelled", ms[-1]
            assert any(m.type == "event" and m.event == "session.turn/start" for m in ms)
            await s.set_permission_mode("deny-by-default")

            q = project(['{:reasoning "a charge twice is billing" :category "billing"}'])
            out = await client.predict({"ticket": "I was charged twice"}, cwd=q.cwd, provider="mock", model=q.model,
                                       signature={"instructions": "Classify the ticket.",
                                                  "inputs": [{"name": "ticket", "description": "the ticket"}],
                                                  "outputs": [{"name": "category",
                                                               "schema": {"enum": ["billing", "bug", "other"]}}]})
            assert out.outputs["category"] == "billing"
            assert out.predictor_id == "user/inline"
    asyncio.run(go())


def test_a_session_is_loaded_with_its_history():
    p = project(['{:reasoning "r" :tool-calls [] :answer "first answer" :goal-achieved true}'])

    async def go():
        async with await ByjClient.start(**BYJ) as one:
            s = await one.new_session(p)
            await s.run("first question")
        history = []
        async with await ByjClient.start(**BYJ) as two:
            await two.load_session(s.id, p, history.append)
        assert any(m.type == "user" and m.text == "first question" for m in history)
        assert any(m.type == "text" and m.text == "first answer" for m in history)
    asyncio.run(go())


def test_a_byj_with_no_acp_is_refused_before_it_is_started_and_spends_nothing():
    # an older byj takes `acp` for a question; its help is what it answers to -h
    d = Path(tempfile.mkdtemp(prefix="byj-old-"))
    old, asked = d / "byj", d / "asked"
    old.write_text(f"""#!/bin/sh\ncase "$*" in *-h*) echo "NAME:"; echo " byj - brainyard's agent runtime";; *) touch {asked};; esac\n""")
    old.chmod(0o755)
    with pytest.raises(ByjVersionError, match="no `acp` command"):
        asyncio.run(ByjClient.start(byj_path=str(old)))
    assert not asked.exists(), "it was never run as an agent"
    with pytest.raises(RuntimeError, match="no .* to run"):
        asyncio.run(ByjClient.start(byj_path=str(d / "nothing-here")))


def test_an_answer_is_given_back_as_the_data_asked_for():
    p = project(['{:reasoning "r" :tool-calls [] :answer "Order 1042 was charged twice and refunded." :goal-achieved true}',
                 '{:reasoning "r" :order "1042" :refunded true}'])
    schema = {"type": "object", "properties": {"order": {"type": "string"}, "refunded": {"type": "boolean"}},
              "required": ["order", "refunded"]}
    ms = asyncio.run(collect(query("what happened to 1042?", p, output_schema=schema, **BYJ)))
    assert ms[-1].structured_output == {"order": "1042", "refunded": True}
    assert ms[-1].answer == "Order 1042 was charged twice and refunded."


def test_a_live_session_it_did_not_start_is_listed_asked_and_watched():
    d = tempfile.mkdtemp(prefix="byj-live-")
    argv = [os.environ["BYJ_BIN"]] if os.environ.get("BYJ_BIN") else ["jolt", "-m", "brainyard.main"]

    async def go():
        served = await asyncio.create_subprocess_exec(
            *argv, "-C", d, "run", "--serve", "-p", "mock", "--label", "worker",
            cwd=d if os.environ.get("BYJ_BIN") else str(REPO), stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE)
        while b"serving session" not in (await served.stdout.readline()):
            pass
        try:
            async with await ByjClient.start(**BYJ) as client:
                rows = await client.live_sessions(d)
                assert [r["label"] for r in rows] == ["worker"]
                seen = []
                detach = await client.attach(d, "worker", lambda e, p: seen.append(e),
                                             events=["session.turn/start", "session.turn/end"])
                r = await client.ask_live(d, "worker", "how far along?")
                assert "how far along?" in r["answer"]
                for _ in range(100):
                    if len(seen) >= 2:
                        break
                    await asyncio.sleep(0.02)
                assert seen == ["session.turn/start", "session.turn/end"]
                await detach()
        finally:
            served.send_signal(2)
            await served.wait()
    asyncio.run(go())


def test_a_session_is_forked_its_history_a_new_id_the_original_left_as_it_was():
    p = project(['{:reasoning "r" :tool-calls [] :answer "first answer" :goal-achieved true}'])

    async def go():
        async with await ByjClient.start(**BYJ) as client:
            one = await client.new_session(p)
            await one.run("first question")
            await one.close()
            history = []
            two = await client.fork_session(one.id, p, history.append)
            assert two.id != one.id
            assert any(m.type == "user" and m.text == "first question" for m in history)
            await two.close()
            again = []
            three = await client.load_session(one.id, p, again.append)
            assert len([m for m in again if m.type == "user"]) == 1
            await three.close()
    asyncio.run(go())
