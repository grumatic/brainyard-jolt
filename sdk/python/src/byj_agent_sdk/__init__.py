"""The Brainyard Agent SDK for Python (RFC-053): byj's agent in an application.

``query`` for one prompt; ``ByjClient`` for sessions that last.
"""
from __future__ import annotations

from typing import Any, Callable, Optional

from .client import (EXTENSIONS, PROTOCOL_VERSION, SDK_VERSION, ByjClient, ByjVersionError, Session,
                     query)
from .remote import KEEPS_QUESTIONS, KINDS, SharedAgent, SharedAgentError, SharedAnswer, SharedEvent, connect_shared
from .rpc import RpcError
from .types import (HOOK_EVENTS, Block, EventMessage, Hook, McpServer, Message, ModifyArgs,
                    PermissionDecision, PermissionMessage, PermissionRequest, PredictResult, Replace,
                    ResultMessage, SessionOptions, TextMessage, ThoughtMessage, Tool, ToolCallMessage,
                    ToolResultMessage, UserMessage)


def tool(name: str, description: str, input_schema: dict[str, Any],
         effects: Optional[list[str]] = None) -> Callable[[Callable[..., Any]], Tool]:
    """A tool that runs in the application, as a decorator::

        @tool("order_lookup", "An order by its id",
              {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]})
        async def lookup(args):
            return await orders.get(args["id"])
    """
    def wrap(handler: Callable[..., Any]) -> Tool:
        return Tool(name=name, description=description, input_schema=input_schema,
                    handler=handler, effects=effects)
    return wrap


__all__ = [
    "query", "tool", "ByjClient", "Session", "ByjVersionError", "RpcError",
    "connect_shared", "SharedAgent", "SharedAgentError", "SharedAnswer", "SharedEvent", "KINDS", "KEEPS_QUESTIONS",
    "PROTOCOL_VERSION", "EXTENSIONS", "SDK_VERSION", "HOOK_EVENTS",
    "SessionOptions", "Tool", "Hook", "Block", "Replace", "ModifyArgs", "McpServer",
    "PermissionRequest", "PermissionDecision", "PredictResult",
    "Message", "TextMessage", "ThoughtMessage", "UserMessage", "ToolCallMessage", "ToolResultMessage",
    "PermissionMessage", "EventMessage", "ResultMessage",
]
