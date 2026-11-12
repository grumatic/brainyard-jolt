# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

"""What an application hands the SDK and what it gets back (RFC-053 §5).

The TypeScript SDK's surface in snake_case. Names follow byj's where byj names
the thing: hook events, permission modes, a turn's result.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Literal, Optional, Union

JsonSchema = dict[str, Any]
PermissionMode = Literal["auto", "ask-each-time", "auto-approve", "deny-by-default"]

#: byj's hook points (RFC-011), by brainyard's names.
HOOK_EVENTS = (
    "agent.session/created", "agent.session/resumed", "agent.session/closed",
    "agent.ask/pre", "agent.ask/post",
    "agent.iteration/pre", "agent.iteration/post",
    "agent.dspy-action/pre", "agent.dspy-action/post",
    "agent.tool-calls/pre", "agent.tool-calls/post",
    "agent.tool-use/pre", "agent.tool-use/post", "agent.tool-use/rejected",
)


@dataclass
class Tool:
    """A tool that runs in the application, called by byj's agent."""
    name: str
    description: str
    input_schema: JsonSchema
    handler: Callable[..., Any]
    #: The effects byj's gate sees (``sdk/call`` when none): ``fs/write``, ``process``, …
    effects: Optional[list[str]] = None


@dataclass
class Block:
    reason: str


@dataclass
class Replace:
    replacement: Any


@dataclass
class ModifyArgs:
    args: dict[str, Any]


#: What a hook on a gated event (``agent.tool-use/pre``) may decide; None allows.
HookDecision = Union[Block, Replace, ModifyArgs, None]


@dataclass
class Hook:
    event: str
    handler: Callable[[Any, dict[str, Any]], Union[HookDecision, Awaitable[HookDecision]]]
    #: A glob on the call's tool name, for the tool-use events.
    tool: Optional[str] = None
    #: How long byj waits for the answer (30 s); past it the hook allows, unless fail_closed.
    timeout_ms: Optional[int] = None
    fail_closed: bool = False


@dataclass
class PermissionRequest:
    session_id: str
    tool_call_id: str
    #: The call as a line: ``bash ls -la``, ``read-file notes.md``.
    title: str
    kind: str
    input: dict[str, Any]


@dataclass
class PermissionDecision:
    behavior: Literal["allow", "deny"]
    always: bool = False


@dataclass
class McpServer:
    name: str
    command: str
    args: list[str] = field(default_factory=list)
    env: dict[str, str] = field(default_factory=dict)


@dataclass
class SessionOptions:
    """A session: ``cwd`` is the project, whose .brainyard/ is the agent's behaviour."""
    cwd: str
    agent: Optional[str] = None
    provider: Optional[str] = None
    model: Optional[str] = None
    loop: Optional[str] = None
    #: Config overrides (RFC-013), byj's key names: ``{"max-iterations": 8}``.
    config: Optional[dict[str, Any]] = None
    permission_mode: Optional[PermissionMode] = None
    #: Answers byj's permission prompts. Without it, a prompt is refused.
    can_use_tool: Optional[Callable[[PermissionRequest], Union[PermissionDecision, Awaitable[PermissionDecision]]]] = None
    tools: list[Tool] = field(default_factory=list)
    hooks: list[Hook] = field(default_factory=list)
    #: byj's own events to receive as ``event`` messages: "all" or names (RFC-026).
    events: Union[Literal["all"], list[str], None] = None
    mcp_servers: list[McpServer] = field(default_factory=list)
    label: Optional[str] = None
    #: Allow slash commands and ``:<tool>`` calls in a prompt.
    commands: bool = False


# --- messages -------------------------------------------------------------------

@dataclass
class TextMessage:
    session_id: str
    text: str
    type: str = "text"


@dataclass
class ThoughtMessage:
    session_id: str
    text: str
    type: str = "thought"


@dataclass
class UserMessage:
    """A resumed session's earlier question."""
    session_id: str
    text: str
    type: str = "user"


@dataclass
class ToolCallMessage:
    session_id: str
    id: str
    title: str
    kind: str
    input: Any
    type: str = "tool_call"


@dataclass
class ToolResultMessage:
    session_id: str
    id: str
    status: str
    output: str
    type: str = "tool_result"


@dataclass
class PermissionMessage:
    session_id: str
    request: PermissionRequest
    decision: PermissionDecision
    #: ``callback`` when can_use_tool answered, ``none`` when there was none.
    by: str
    type: str = "permission"


@dataclass
class EventMessage:
    session_id: str
    event: str
    payload: Any
    type: str = "event"


@dataclass
class ResultMessage:
    """The last message of a prompt."""
    session_id: str
    stop_reason: str
    answer: str = ""
    terminated_by: Optional[str] = None
    iterations: Optional[int] = None
    goal_achieved: Optional[bool] = None
    next_user_prompt: Optional[str] = None
    elapsed_ms: Optional[int] = None
    usage: Optional[dict[str, Any]] = None
    #: Dollars, when byj could price the turn.
    cost_usd: Optional[float] = None
    is_error: bool = False
    error: Optional[str] = None
    #: With output_schema: the answer as that schema's data.
    structured_output: Any = None
    #: With output_schema: why the answer could not be given as data.
    structured_output_error: Optional[str] = None
    type: str = "result"


Message = Union[TextMessage, ThoughtMessage, UserMessage, ToolCallMessage, ToolResultMessage,
                PermissionMessage, EventMessage, ResultMessage]


@dataclass
class PredictResult:
    outputs: dict[str, Any]
    predictor_id: str
    reasoning: Optional[str] = None
    usage: Optional[dict[str, Any]] = None
    cost_usd: Optional[float] = None
    params_source: Optional[str] = None
