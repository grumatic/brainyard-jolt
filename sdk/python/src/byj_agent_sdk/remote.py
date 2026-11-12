# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

"""A shared agent, called from an application (RFC-060 D11).

An agent share's endpoint on Share, spoken to over A2A v0.3 with the token
its owner made for the application. It shares nothing with the stdio client
but its way of answering: ``ask`` for the answer, ``stream`` for the working
text as it grows and then the answer, ``cancel`` for the turn running. A
handle keeps its conversation's context, so each message continues the last.
No dependency beyond the standard library.
"""
from __future__ import annotations

import asyncio
import itertools
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Optional

COST = "ai.brainyard/cost-usd"

#: On a card while its share keeps the questions its callers ask and the
#: answers it gives, for its owner to read (byj-1wv.119).
KEEPS_QUESTIONS = "https://share.brainyard.io/a2a/extensions/keeps-questions/v1"

#: Why a shared agent did not answer, as the relay and the share say it.
KINDS = ("unauthorized", "refused", "owner-only", "not-live", "cap", "busy", "failed")


class SharedAgentError(Exception):
    """A shared agent's refusal or failure; ``kind`` is one of ``KINDS``."""

    def __init__(self, kind: str, message: str, status: Optional[int] = None, code: Optional[int] = None):
        super().__init__(message)
        self.kind, self.status, self.code = kind, status, code


def _classify(message: str, status: Optional[int] = None) -> str:
    m = message.lower()
    if status == 401:
        return "unauthorized"
    if "subscription" in m:
        return "owner-only"
    if "not live" in m or "not connected" in m or "share ended" in m:
        return "not-live"
    if "cap" in m and "reached" in m:
        return "cap"
    if "busy" in m:
        return "busy"
    if status == 403:
        return "refused"
    if status == 404:
        return "not-live"
    return "failed"


def _text(parts: Optional[list[dict[str, Any]]]) -> str:
    return "".join(p.get("text", "") for p in (parts or []))


@dataclass
class SharedAnswer:
    answer: str
    task_id: str
    context_id: Optional[str] = None
    cost_usd: Optional[float] = None


@dataclass
class SharedEvent:
    """A stream's event: ``working`` with the text so far, or ``answer``."""
    type: str
    text: str = ""
    answer: Optional[SharedAnswer] = None


def _answer_of(task: dict[str, Any]) -> SharedAnswer:
    status_text = _text(((task.get("status") or {}).get("message") or {}).get("parts"))
    artifacts = "\n".join(t for t in (_text(a.get("parts")) for a in task.get("artifacts") or []) if t)
    cost = (task.get("metadata") or {}).get(COST)
    return SharedAnswer(answer=status_text or artifacts, task_id=task.get("id", ""),
                        context_id=task.get("contextId"), cost_usd=cost if isinstance(cost, (int, float)) else None)


def _error_message(raw: bytes, fallback: str) -> str:
    try:
        b = json.loads(raw)
        e = b.get("error")
        return str(e.get("message") if isinstance(e, dict) else e or fallback)
    except Exception:
        return fallback


@dataclass
class SharedAgent:
    card: dict[str, Any]
    token: str
    skill: Optional[str] = None
    context_id: Optional[str] = None
    _running: Optional[str] = None
    _n: Any = field(default_factory=itertools.count)

    @property
    def keeps_questions(self) -> bool:
        """Whether the share keeps what its callers ask and its answers, for
        its owner to read — as its card said when it was read: an application
        asking for people who never see the share's page tells them."""
        exts = ((self.card.get("capabilities") or {}).get("extensions")) or []
        return any(e.get("uri") == KEEPS_QUESTIONS for e in exts)

    def _message(self, text: str) -> dict[str, Any]:
        prefix = f"[skill: {self.skill}]\n" if self.skill else ""
        m: dict[str, Any] = {"messageId": f"m-{time.time_ns()}-{next(self._n)}", "role": "user", "kind": "message",
                             "parts": [{"kind": "text", "text": prefix + text}]}
        if self.context_id:
            m["contextId"] = self.context_id
        return m

    def _open(self, method: str, params: dict[str, Any], accept: str):
        body = json.dumps({"jsonrpc": "2.0", "id": next(self._n), "method": method, "params": params}).encode()
        req = urllib.request.Request(self.card["url"], data=body, method="POST", headers={
            "Content-Type": "application/json", "Accept": accept, "Authorization": f"Bearer {self.token}"})
        try:
            return urllib.request.urlopen(req, timeout=600)
        except urllib.error.HTTPError as e:
            message = _error_message(e.read(), e.reason or str(e.code))
            raise SharedAgentError(_classify(message, e.code), message, status=e.code) from None

    def _rpc_error(self, e: dict[str, Any]) -> SharedAgentError:
        message = str(e.get("message") or "the agent did not answer")
        return SharedAgentError(_classify(message), message, code=e.get("code"))

    def _keep(self, task: dict[str, Any]) -> None:
        if task.get("contextId"):
            self.context_id = task["contextId"]

    def _ask(self, text: str) -> SharedAnswer:
        with self._open("message/send", {"message": self._message(text),
                                         "configuration": {"blocking": True, "acceptedOutputModes": ["text/plain"]}},
                        "application/json") as r:
            body = json.loads(r.read())
        if body.get("error"):
            raise self._rpc_error(body["error"])
        task = body.get("result") or {}
        self._keep(task)
        state = (task.get("status") or {}).get("state")
        if state and state != "completed":
            raise SharedAgentError("failed", f"the task ended {state}: {_answer_of(task).answer}")
        return _answer_of(task)

    async def ask(self, text: str) -> SharedAnswer:
        """Ask, and wait for the answer."""
        return await asyncio.to_thread(self._ask, text)

    async def stream(self, text: str) -> AsyncIterator[SharedEvent]:
        """Ask, and read the working text as it grows, then the answer."""
        loop = asyncio.get_running_loop()
        q: asyncio.Queue = asyncio.Queue()

        def read() -> None:
            try:
                with self._open("message/stream", {"message": self._message(text),
                                                   "configuration": {"acceptedOutputModes": ["text/plain"]}},
                                "text/event-stream") as r:
                    for raw in r:
                        loop.call_soon_threadsafe(q.put_nowait, raw.decode("utf-8", "replace"))
            except BaseException as e:  # handed to the reader
                loop.call_soon_threadsafe(q.put_nowait, e)
            loop.call_soon_threadsafe(q.put_nowait, None)

        reader = loop.run_in_executor(None, read)
        last = ""
        try:
            while True:
                item = await q.get()
                if item is None:
                    break
                if isinstance(item, BaseException):
                    raise item
                line = item.strip()
                if not line.startswith("data:"):
                    continue
                try:
                    frame = json.loads(line[5:].strip())
                except ValueError:
                    continue
                if frame.get("error"):
                    raise self._rpc_error(frame["error"])
                r = frame.get("result") or frame
                if r.get("kind") == "task":
                    self._keep(r)
                    self._running = r.get("id")
                elif r.get("kind") == "status-update":
                    if r.get("contextId"):
                        self.context_id = r["contextId"]
                    t = _text(((r.get("status") or {}).get("message") or {}).get("parts"))
                    if r.get("final"):
                        self._running = None
                        state = (r.get("status") or {}).get("state")
                        if state != "completed":
                            raise SharedAgentError("failed", f"the task ended {state}: {t}")
                        cost = (r.get("metadata") or {}).get(COST)
                        yield SharedEvent(type="answer", text=t, answer=SharedAnswer(
                            answer=t, task_id=r.get("taskId", ""), context_id=self.context_id,
                            cost_usd=cost if isinstance(cost, (int, float)) else None))
                        return
                    if t and t != last:
                        last = t
                        yield SharedEvent(type="working", text=t)
            raise SharedAgentError("failed", "the stream ended before the answer")
        finally:
            # the reading thread ends with the stream, which the agent closes
            # once it has said the answer
            if not reader.done():
                await reader

    def _cancel(self) -> None:
        if not self._running:
            return
        with self._open("tasks/cancel", {"id": self._running}, "application/json") as r:
            body = json.loads(r.read())
        if body.get("error"):
            raise self._rpc_error(body["error"])
        self._running = None

    async def cancel(self) -> None:
        """Stop the turn running, if one is."""
        await asyncio.to_thread(self._cancel)


def _card(url: str) -> dict[str, Any]:
    base = url.rstrip("/")
    req = urllib.request.Request(base + "/.well-known/agent-card.json", headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            card = json.loads(r.read())
    except urllib.error.HTTPError as e:
        message = _error_message(e.read(), e.reason or str(e.code))
        raise SharedAgentError(_classify(message, e.code), message, status=e.code) from None
    card["url"] = card.get("url") or base
    return card


async def connect_shared(url: str, token: str, skill: Optional[str] = None) -> SharedAgent:
    """A shared agent at ``url`` — an agent share's endpoint, ``https://…/a2a/shr_…`` —
    with the token its owner made for this application: its card read, a handle
    to ask it with."""
    card = await asyncio.to_thread(_card, url)
    return SharedAgent(card=card, token=token, skill=skill)
