"""The remote client against an A2A endpoint shaped as Share's relay and
byj-a2a answer (RFC-060 D11, byj-1wv.115.14): the card, a send answered with
a completed task, a stream of status frames, the context kept from one
message to the next, and each refusal as its own kind of error."""
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from byj_agent_sdk import KEEPS_QUESTIONS, SharedAgentError, connect_shared


def serve(answer, card=None):
    seen = []

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _json(self, status, v):
            b = json.dumps(v).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def do_GET(self):
            if "gone" in self.path:
                return self._json(404, {"error": "this agent share is not live"})
            port = self.server.server_address[1]
            self._json(200, {"name": "byj", "url": f"http://127.0.0.1:{port}/a2a/shr_x", "protocolVersion": "0.3", **(card or {})})

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            seen.append({"body": body, "auth": self.headers.get("Authorization")})
            answer(self, body)

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{srv.server_address[1]}/a2a/shr_x", seen, srv


def test_ask_answers_keeps_its_context_and_sends_the_token():
    def answer(h, body):
        h._json(200, {"jsonrpc": "2.0", "id": body["id"], "result": {
            "kind": "task", "id": "t1", "contextId": "ctx-1",
            "status": {"state": "completed", "message": {"parts": [{"kind": "text", "text": "Within 30 days."}]}},
            "metadata": {"ai.brainyard/cost-usd": 0.012}}})
    base, seen, srv = serve(answer)
    try:
        async def go():
            agent = await connect_shared(base, "app-token")
            a = await agent.ask("Refund window?")
            assert (a.answer, a.task_id, a.context_id, a.cost_usd) == ("Within 30 days.", "t1", "ctx-1", 0.012)
            await agent.ask("And shipping?")
        asyncio.run(go())
        assert seen[0]["auth"] == "Bearer app-token"
        assert "contextId" not in seen[0]["body"]["params"]["message"]
        assert seen[1]["body"]["params"]["message"]["contextId"] == "ctx-1"
    finally:
        srv.shutdown()


def test_stream_yields_the_working_text_then_the_answer():
    def answer(h, body):
        h.send_response(200)
        h.send_header("Content-Type", "text/event-stream")
        h.end_headers()
        for r in [{"kind": "task", "id": "t2", "contextId": "ctx-2", "status": {"state": "submitted"}},
                  {"kind": "status-update", "taskId": "t2", "contextId": "ctx-2", "final": False,
                   "status": {"state": "working", "message": {"parts": [{"kind": "text", "text": "reading"}]}}},
                  {"kind": "status-update", "taskId": "t2", "contextId": "ctx-2", "final": True,
                   "status": {"state": "completed", "message": {"parts": [{"kind": "text", "text": "Within 30 days."}]}}}]:
            h.wfile.write(f"data: {json.dumps({'jsonrpc': '2.0', 'id': body['id'], 'result': r})}\n\n".encode())
            h.wfile.flush()
    base, seen, srv = serve(answer)
    try:
        async def go():
            agent = await connect_shared(base, "t")
            events = [e async for e in agent.stream("Refund window?")]
            assert [(e.type, e.text) for e in events] == [("working", "reading"), ("answer", "Within 30 days.")]
            assert events[-1].answer.task_id == "t2" and agent.context_id == "ctx-2"
        asyncio.run(go())
    finally:
        srv.shutdown()


@pytest.mark.parametrize("status,body,kind", [
    (401, {"error": "unauthorized"}, "unauthorized"),
    (403, {"error": "forbidden"}, "refused"),
    (403, {"error": "this agent runs on its owner's Claude subscription, which answers its owner alone"}, "owner-only"),
    (200, {"jsonrpc": "2.0", "id": 1, "error": {"code": -32004, "message": "this agent share is not live"}}, "not-live"),
    (200, {"jsonrpc": "2.0", "id": 1, "error": {"code": -32004, "message": "this share's cost cap of $10.00 is reached"}}, "cap"),
    (200, {"jsonrpc": "2.0", "id": 1, "error": {"code": -32004, "message": "busy: this machine is holding as many conversations as it can"}}, "busy"),
    (200, {"jsonrpc": "2.0", "id": 1, "result": {"kind": "task", "id": "t", "status": {"state": "failed"}}}, "failed"),
])
def test_each_refusal_is_an_error_of_its_own_kind(status, body, kind):
    base, _, srv = serve(lambda h, _b: h._json(status, body))
    try:
        async def go():
            agent = await connect_shared(base, "t")
            with pytest.raises(SharedAgentError) as e:
                await agent.ask("hi")
            assert e.value.kind == kind
        asyncio.run(go())
    finally:
        srv.shutdown()


def test_a_share_not_live_is_said_at_once():
    base, _, srv = serve(lambda h, b: None)
    try:
        with pytest.raises(SharedAgentError) as e:
            asyncio.run(connect_shared(base.replace("shr_x", "gone"), "t"))
        assert e.value.kind == "not-live"
    finally:
        srv.shutdown()


def test_keeps_questions_says_what_the_card_says():
    """byj-1wv.119: a share that keeps its callers' questions says so on its card."""
    keeps, _, a = serve(None, {"capabilities": {"streaming": True, "extensions": [{"uri": KEEPS_QUESTIONS, "description": "kept"}]}})
    not_, _, b = serve(None, {"capabilities": {"streaming": True}})
    try:
        assert asyncio.run(connect_shared(keeps, "t")).keeps_questions is True
        assert asyncio.run(connect_shared(not_, "t")).keeps_questions is False
    finally:
        a.shutdown()
        b.shutdown()
