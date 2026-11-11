"""ByjClient: one ``byj acp`` process, any number of sessions (RFC-053 §5)."""
from __future__ import annotations

import asyncio
import inspect
import os

from typing import Any, AsyncIterator, Callable, Optional

from .rpc import Connection, RpcError
from .types import (
    Block, EventMessage, Hook, Message, ModifyArgs, PermissionDecision, PermissionMessage,
    PermissionRequest, PredictResult, Replace, ResultMessage, SessionOptions, TextMessage,
    ThoughtMessage, ToolCallMessage, ToolResultMessage, UserMessage,
)

#: The ACP version spoken.
PROTOCOL_VERSION = 1
#: byj's extensions this SDK needs, and their version.
EXTENSIONS = ("tools", "hooks", "events", "predict")
EXTENSIONS_VERSION = 1
SDK_VERSION = "0.1.0"

_END = object()


class ByjVersionError(Exception):
    pass


#: The byjs already seen to have ``acp``, by command line: asked once a process.
_probed: set[tuple] = set()


async def _probe(exe: str, args: list[str], cwd: Optional[str], env: dict[str, str]) -> None:
    """Whether this byj has ``acp``, asked with ``byj acp -h``, which no byj
    answers with a model: one older than the SDK takes ``acp`` for a question
    and would spend a model call on it, where its help costs nothing
    (RFC-053 "Versions")."""
    key = (exe, tuple(args), cwd)
    if key in _probed:
        return
    try:
        p = await asyncio.create_subprocess_exec(exe, *args, "acp", "-h", cwd=cwd, env=env,
                                                 stdin=asyncio.subprocess.DEVNULL,
                                                 stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    except FileNotFoundError:
        raise RuntimeError(f"no {exe} to run: install byj, or name it with byj_path or $BYJ_BIN") from None
    out, _ = await asyncio.wait_for(p.communicate(), 60)
    if "byj acp - " not in out.decode("utf-8", "replace"):
        raise ByjVersionError(f"{exe} has no `acp` command: it is older than this SDK ({SDK_VERSION}) — run `byj update`")
    _probed.add(key)


async def _maybe(x: Any) -> Any:
    return await x if inspect.isawaitable(x) else x


class Session:
    def __init__(self, client: "ByjClient", session_id: str, options: SessionOptions,
                 modes: list[dict[str, Any]]):
        self.client = client
        self.id = session_id
        self.options = options
        self.modes = modes
        self.tools = {t.name: t for t in options.tools}
        #: the prompt being answered: its messages go here
        self.current: Optional[asyncio.Queue] = None

    def emit(self, m: Message) -> None:
        if self.current is not None:
            self.current.put_nowait(m)

    async def prompt(self, text: str, output_schema: Optional[dict[str, Any]] = None) -> AsyncIterator[Message]:
        """One prompt, as the messages it produces — text and thoughts as they
        are written, tool calls and their results, permission decisions, byj's
        events when asked for — and last a result. One prompt at a time."""
        if self.current is not None:
            raise RuntimeError("a prompt is already running in this session")
        q: asyncio.Queue = asyncio.Queue()
        self.current = q

        async def ask() -> None:
            try:
                r = await self.client.conn.request(
                    "session/prompt", {"sessionId": self.id, "prompt": [{"type": "text", "text": text}],
                                       **({"_meta": {"brainyard": {"outputSchema": output_schema}}} if output_schema else {})})
                m = (r or {}).get("_meta", {}).get("brainyard", {})
                q.put_nowait(ResultMessage(
                    session_id=self.id, stop_reason=r.get("stopReason"), answer=m.get("answer", ""),
                    terminated_by=m.get("terminatedBy"), iterations=m.get("iterations"),
                    goal_achieved=m.get("goalAchieved"), next_user_prompt=m.get("nextUserPrompt"),
                    elapsed_ms=m.get("elapsedMs"), usage=m.get("usage"), cost_usd=m.get("costUsd"),
                    structured_output=m.get("structuredOutput"),
                    structured_output_error=m.get("structuredOutputError")))
            except Exception as e:
                detail = f"{e} {e.data}" if isinstance(e, RpcError) and e.data else str(e)
                q.put_nowait(ResultMessage(session_id=self.id, stop_reason="error", is_error=True, error=detail))
            finally:
                q.put_nowait(_END)

        task = asyncio.ensure_future(ask())
        try:
            while True:
                m = await q.get()
                if m is _END:
                    break
                yield m
        finally:
            self.current = None
            if not task.done():
                await task

    async def run(self, text: str, on_message: Optional[Callable[[Message], Any]] = None,
                  output_schema: Optional[dict[str, Any]] = None) -> ResultMessage:
        """The prompt's result, its messages passed to ``on_message`` on the way."""
        result = None
        async for m in self.prompt(text, output_schema):
            if on_message:
                await _maybe(on_message(m))
            if isinstance(m, ResultMessage):
                result = m
        return result  # type: ignore[return-value]

    async def cancel(self) -> None:
        """Cancel the running turn; its prompt ends ``cancelled``."""
        await self.client.conn.notify("session/cancel", {"sessionId": self.id})

    async def close(self) -> None:
        """End the session: its turn cancelled, its lock released."""
        await self.client.conn.request("session/close", {"sessionId": self.id})
        self.client.sessions.pop(self.id, None)

    async def set_permission_mode(self, mode: str) -> None:
        await self.client.conn.request("session/set_mode", {"sessionId": self.id, "modeId": mode})


def _session_meta(o: SessionOptions, hooks: list[tuple[str, Hook]]) -> dict[str, Any]:
    b: dict[str, Any] = {
        "tools": [{"name": t.name, "description": t.description, "inputSchema": t.input_schema,
                   **({"effects": t.effects} if t.effects else {})} for t in o.tools],
        "hooks": [{"id": hid, "event": h.event,
                   **({"match": {"tool": h.tool}} if h.tool else {}),
                   **({"timeoutMs": h.timeout_ms} if h.timeout_ms else {}),
                   **({"failClosed": True} if h.fail_closed else {})} for hid, h in hooks],
    }
    for k, v in (("agent", o.agent), ("provider", o.provider), ("model", o.model), ("loop", o.loop),
                 ("config", o.config), ("permissionMode", o.permission_mode), ("events", o.events),
                 ("label", o.label)):
        if v is not None:
            b[k] = v
    if o.commands:
        b["commands"] = True
    return {"brainyard": b}


class ByjClient:
    """One ``byj acp``; use as ``async with await ByjClient.start() as client``."""

    def __init__(self, conn: Connection):
        self.conn = conn
        self.sessions: dict[str, Session] = {}
        self.agent_info: dict[str, Any] = {}
        # every session's hooks, by an id unique to this client: a hook firing
        # as a session opens is answered before its id has come back
        self._hooks: dict[str, Hook] = {}
        self._hook_count = 0
        self._watchers: dict[str, Callable[[str, Any], Any]] = {}
        conn.on_notification("session/update", self._on_update)
        conn.on_notification("_byj/event", self._on_event)
        conn.on_notification("_byj/live/event", lambda p: (self._watchers.get(p.get("attachId")) or (lambda *_: None))(
            p.get("event"), p.get("payload")))
        conn.on_request("session/request_permission", self._on_permission)
        conn.on_request("_byj/tool/call", self._on_tool_call)
        conn.on_request("_byj/hook", self._on_hook)

    @classmethod
    async def start(cls, byj_path: Optional[str] = None, byj_args: Optional[list[str]] = None,
                    process_cwd: Optional[str] = None, env: Optional[dict[str, str]] = None,
                    stderr: Optional[Callable[[str], None]] = None) -> "ByjClient":
        """Start byj (``byj acp``) and shake hands: the ACP version and byj's
        extensions. byj is ``byj_path``, else $BYJ_BIN, else ``byj`` on PATH."""
        exe = byj_path or os.environ.get("BYJ_BIN") or "byj"
        await _probe(exe, byj_args or [], process_cwd, {**os.environ, **(env or {})})
        proc = await asyncio.create_subprocess_exec(
            exe, *(byj_args or []), "acp", cwd=process_cwd, env={**os.environ, **(env or {})},
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            limit=16 * 1024 * 1024)
        client = cls(Connection(proc, stderr))
        try:
            init = await client.conn.request("initialize", {
                "protocolVersion": PROTOCOL_VERSION, "clientCapabilities": {},
                "clientInfo": {"name": "byj-agent-sdk", "version": SDK_VERSION},
                "_meta": {"brainyard": {"sdk": "python", "version": SDK_VERSION}}})
        except Exception as e:
            raise RuntimeError(f"could not start {exe} acp: {e}\n{client.conn.stderr_tail}") from e
        client.agent_info = init.get("agentInfo", {})
        ext = (init.get("agentCapabilities") or {}).get("_meta", {}).get("brainyard")
        missing = [x for x in EXTENSIONS if x not in ((ext or {}).get("extensions") or [])]
        if init.get("protocolVersion") != PROTOCOL_VERSION or not ext or ext.get("protocol", 0) < EXTENSIONS_VERSION or missing:
            await client.close()
            raise ByjVersionError(
                f"byj {client.agent_info.get('version', '?')} does not offer what this SDK ({SDK_VERSION}) needs"
                + (f" — no {', '.join(missing)}" if missing else "") + ": run `byj update`")
        return client

    async def __aenter__(self) -> "ByjClient":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.close()

    async def new_session(self, options: SessionOptions) -> Session:
        """A new session in a project."""
        return await self._open("session/new", options)

    async def load_session(self, session_id: str, options: SessionOptions,
                           on_history: Optional[Callable[[Message], Any]] = None) -> Session:
        """A saved session, resumed; its history to ``on_history`` as user, text and thought messages."""
        return await self._open("session/load", options, session_id, on_history)

    async def fork_session(self, from_session_id: str, options: SessionOptions,
                           on_history: Optional[Callable[[Message], Any]] = None) -> Session:
        """A new session that starts where a saved one stands — its history,
        under a new id — leaving that one as it was."""
        import random
        import time
        sid = f"{int(time.time() * 1000)}-{random.getrandbits(32):08x}"
        return await self._open("session/new", options, sid, on_history, fork_from=from_session_id)

    async def _open(self, method: str, o: SessionOptions, session_id: Optional[str] = None,
                    on_history: Optional[Callable[[Message], Any]] = None,
                    fork_from: Optional[str] = None) -> Session:
        hooks = []
        for h in o.hooks:
            self._hook_count += 1
            hid = f"h{self._hook_count}"
            self._hooks[hid] = h
            hooks.append((hid, h))
        history: list[Message] = []
        if session_id:
            # the history is replayed under the session's id before load answers
            pending = Session(self, session_id, o, [])
            pending.current = asyncio.Queue()
            self.sessions[session_id] = pending
        meta = _session_meta(o, hooks)
        if fork_from:
            meta["brainyard"].update({"forkFrom": fork_from, "sessionId": session_id})
        try:
            r = await self.conn.request(method, {
                **({"sessionId": session_id} if session_id and not fork_from else {}),
                "cwd": o.cwd,
                "mcpServers": [{"name": m.name, "command": m.command, "args": m.args,
                                "env": [{"name": k, "value": v} for k, v in m.env.items()]} for m in o.mcp_servers],
                "_meta": meta})
        except Exception:
            if session_id:
                self.sessions.pop(session_id, None)
            raise
        if session_id:
            q = self.sessions[session_id].current
            while q is not None and not q.empty():
                history.append(q.get_nowait())
        sid = session_id or r["sessionId"]
        s = Session(self, sid, o, (r or {}).get("modes", {}).get("availableModes", []))
        self.sessions[sid] = s
        for m in history:
            if on_history:
                await _maybe(on_history(m))
        return s

    # --- sessions this client did not start (RFC-031) ------------------------

    async def live_sessions(self, cwd: str, all: bool = False) -> list[dict[str, Any]]:
        """The project's sessions that listen on their channel — a person's
        ``byj run``, a headless ``byj run --serve`` — live ones unless ``all``."""
        return (await self.conn.request("_byj/live/list", {"cwd": cwd, **({"all": True} if all else {})}))["sessions"]

    async def ask_live(self, cwd: str, session: str, question: str,
                       timeout_ms: Optional[int] = None) -> dict[str, Any]:
        """Ask a live session (its id, a prefix or its label): a turn of its, answered."""
        return await self.conn.request("_byj/live/ask", {"cwd": cwd, "sessionId": session, "question": question,
                                                         **({"timeoutMs": timeout_ms} if timeout_ms else {})})

    async def attach(self, cwd: str, session: str, on_event: Callable[[str, Any], Any],
                     events: Optional[list[str]] = None) -> Callable[[], Any]:
        """Watch a live session: ``on_event(event, payload)`` for each of its
        events until the returned ``detach()`` is awaited."""
        r = await self.conn.request("_byj/live/attach", {"cwd": cwd, "sessionId": session,
                                                          **({"events": events} if events else {})})
        self._watchers[r["attachId"]] = on_event

        async def detach() -> None:
            self._watchers.pop(r["attachId"], None)
            await self.conn.request("_byj/live/detach", {"attachId": r["attachId"]})
        return detach

    async def predict(self, inputs: dict[str, Any], predictor: Optional[str] = None,
                      signature: Optional[dict[str, Any]] = None, cwd: Optional[str] = None,
                      provider: Optional[str] = None, model: Optional[str] = None,
                      config: Optional[dict[str, Any]] = None) -> PredictResult:
        """One predictor call: structured output, no session, no tools."""
        params = {k: v for k, v in (("predictor", predictor), ("signature", signature), ("inputs", inputs),
                                    ("cwd", cwd), ("provider", provider), ("model", model),
                                    ("config", config)) if v is not None}
        r = await self.conn.request("_byj/predict", params)
        return PredictResult(outputs=r.get("outputs", {}), predictor_id=r.get("predictorId"),
                             reasoning=r.get("reasoning"), usage=r.get("usage"),
                             cost_usd=r.get("costUsd"), params_source=r.get("paramsSource"))

    async def close(self, timeout: float = 15) -> None:
        """End byj: every session closed, its turn cancelled."""
        self.conn.end()
        try:
            await asyncio.wait_for(self.conn.proc.wait(), timeout)
        except asyncio.TimeoutError:
            self.conn.proc.kill()
        for t in self.conn._tasks:
            t.cancel()

    # --- what byj sends ---------------------------------------------------------

    def _on_update(self, p: dict[str, Any]) -> None:
        sid = p.get("sessionId")
        s = self.sessions.get(sid)
        u = p.get("update") or {}
        if s is None:
            return
        kind = u.get("sessionUpdate")
        text = (u.get("content") or {}).get("text", "") if isinstance(u.get("content"), dict) else ""
        if kind == "agent_message_chunk":
            s.emit(TextMessage(sid, text))
        elif kind == "agent_thought_chunk":
            s.emit(ThoughtMessage(sid, text))
        elif kind == "user_message_chunk":
            s.emit(UserMessage(sid, text))
        elif kind == "tool_call":
            s.emit(ToolCallMessage(sid, u.get("toolCallId"), u.get("title", ""), u.get("kind", "other"), u.get("rawInput")))
        elif kind == "tool_call_update" and u.get("status") in ("completed", "failed"):
            out = "".join((c.get("content") or {}).get("text", "") for c in (u.get("content") or []))
            s.emit(ToolResultMessage(sid, u.get("toolCallId"), u["status"], out))

    def _on_event(self, p: dict[str, Any]) -> None:
        s = self.sessions.get(p.get("sessionId"))
        if s:
            s.emit(EventMessage(p["sessionId"], p.get("event"), p.get("payload")))

    async def _on_permission(self, p: dict[str, Any]) -> dict[str, Any]:
        s = self.sessions.get(p.get("sessionId"))
        tc = p.get("toolCall") or {}
        req = PermissionRequest(p.get("sessionId"), tc.get("toolCallId"), tc.get("title", ""),
                                tc.get("kind", "other"), tc.get("rawInput") or {})
        decision, by = PermissionDecision("deny"), "none"
        if s and s.options.can_use_tool:
            decision, by = await _maybe(s.options.can_use_tool(req)), "callback"
        if s:
            s.emit(PermissionMessage(req.session_id, req, decision, by))
        option = ("allow" if decision.behavior == "allow" else "reject") + ("-always" if decision.always else "-once")
        return {"outcome": {"outcome": "selected", "optionId": option}}

    async def _on_tool_call(self, p: dict[str, Any]) -> dict[str, Any]:
        s = self.sessions.get(p.get("sessionId"))
        t = s.tools.get(p.get("tool")) if s else None
        if t is None:
            raise RpcError(-32601, f"no tool {p.get('tool')} in session {p.get('sessionId')}")
        content = await _maybe(t.handler(p.get("args") or {}))
        return {"content": content}

    async def _on_hook(self, p: dict[str, Any]) -> dict[str, Any]:
        h = self._hooks.get(p.get("id"))
        if h is None:
            return {}
        d = await _maybe(h.handler(p.get("payload"), {"session_id": p.get("sessionId"), "event": p.get("event")}))
        if isinstance(d, Block):
            return {"decision": "block", "reason": d.reason}
        if isinstance(d, Replace):
            return {"decision": "replace", "replacement": d.replacement}
        if isinstance(d, ModifyArgs):
            return {"decision": "modifyArgs", "args": d.args}
        return {}


async def query(prompt: str, options: SessionOptions, output_schema: Optional[dict[str, Any]] = None,
                **client_options: Any) -> AsyncIterator[Message]:
    """One prompt in a new session: byj started, the session opened, the
    prompt's messages yielded — the last a result — and byj ended, also when
    the caller stops reading early. ``output_schema``: the answer also given
    back as that JSON Schema's data (``result.structured_output``).
    ``client_options`` are ByjClient.start's."""
    client = await ByjClient.start(**client_options)
    try:
        session = await client.new_session(options)
        async for m in session.prompt(prompt, output_schema):
            yield m
    finally:
        await client.close()
