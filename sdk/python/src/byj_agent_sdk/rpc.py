"""JSON-RPC 2.0 over a child process's stdio, one object per line: what
``byj acp`` speaks (RFC-053). Carried here rather than taken from the ACP
libraries, whose extension API moved within a month (RFC-053 Decisions);
conformance is checked by dev/acp-interop.sh."""
from __future__ import annotations

import asyncio
import inspect
import json
from typing import Any, Awaitable, Callable, Optional


class RpcError(Exception):
    def __init__(self, code: int, message: str, data: Any = None):
        super().__init__(message)
        self.code = code
        self.data = data


class Connection:
    def __init__(self, proc: asyncio.subprocess.Process,
                 stderr: Optional[Callable[[str], None]] = None):
        self.proc = proc
        self._next_id = 0
        self._pending: dict[Any, asyncio.Future] = {}
        self._requests: dict[str, Callable[[Any], Any]] = {}
        self._notifications: dict[str, Callable[[Any], None]] = {}
        self._write_lock = asyncio.Lock()
        self._closed: Optional[Exception] = None
        self.stderr_tail = ""
        self._stderr = stderr
        self._tasks = [asyncio.ensure_future(self._read()), asyncio.ensure_future(self._read_err())]

    def on_request(self, method: str, handler: Callable[[Any], Any]) -> None:
        self._requests[method] = handler

    def on_notification(self, method: str, handler: Callable[[Any], None]) -> None:
        self._notifications[method] = handler

    async def request(self, method: str, params: Any) -> Any:
        if self._closed:
            raise self._closed
        self._next_id += 1
        rid = self._next_id
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        await self._write({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        return await fut

    async def notify(self, method: str, params: Any) -> None:
        if not self._closed:
            await self._write({"jsonrpc": "2.0", "method": method, "params": params})

    def end(self) -> None:
        """Close byj's stdin: byj ends every session and exits."""
        try:
            if self.proc.stdin and not self.proc.stdin.is_closing():
                self.proc.stdin.close()
        except Exception:
            pass

    async def _write(self, msg: Any) -> None:
        line = (json.dumps(msg, ensure_ascii=False) + "\n").encode("utf-8")
        async with self._write_lock:
            assert self.proc.stdin is not None
            self.proc.stdin.write(line)
            await self.proc.stdin.drain()

    def _fail(self, e: Exception) -> None:
        if self._closed:
            return
        self._closed = e
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(e)
        self._pending.clear()

    async def _read_err(self) -> None:
        assert self.proc.stderr is not None
        while True:
            line = await self.proc.stderr.readline()
            if not line:
                return
            text = line.decode("utf-8", "replace").rstrip("\n")
            self.stderr_tail = (self.stderr_tail + text + "\n")[-4000:]
            if self._stderr:
                self._stderr(text)

    async def _read(self) -> None:
        assert self.proc.stdout is not None
        try:
            while True:
                line = await self.proc.stdout.readline()
                if not line:
                    break
                if line.strip():
                    self._on_line(line.decode("utf-8"))
        finally:
            self._fail(ConnectionError("byj exited"))

    def _on_line(self, line: str) -> None:
        try:
            msg = json.loads(line)
        except ValueError:
            return
        if "id" in msg and "method" not in msg:
            fut = self._pending.pop(msg["id"], None)
            if fut and not fut.done():
                if "error" in msg:
                    e = msg["error"]
                    fut.set_exception(RpcError(e.get("code", -32603), e.get("message", ""), e.get("data")))
                else:
                    fut.set_result(msg.get("result"))
        elif "id" in msg:
            asyncio.ensure_future(self._answer(msg["id"], msg["method"], msg.get("params")))
        elif "method" in msg:
            h = self._notifications.get(msg["method"])
            if h:
                try:
                    h(msg.get("params"))
                except Exception:
                    pass

    async def _answer(self, rid: Any, method: str, params: Any) -> None:
        h = self._requests.get(method)
        if h is None:
            await self._write({"jsonrpc": "2.0", "id": rid,
                               "error": {"code": -32601, "message": f"Method not found: {method}"}})
            return
        try:
            result = h(params)
            if inspect.isawaitable(result):
                result = await result
            await self._write({"jsonrpc": "2.0", "id": rid, "result": {} if result is None else result})
        except Exception as e:  # the application's handler failed: the call's failure
            code = e.code if isinstance(e, RpcError) else -32000
            await self._write({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": str(e)}})
