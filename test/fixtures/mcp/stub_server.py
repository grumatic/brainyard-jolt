#!/usr/bin/env python3
"""A stub MCP server for brainyard.mcp-test.

Deliberately awkward in the ways a real server is: it emits an unsolicited
notification before answering tools/list, offers a tool that reports an error
through isError, and one whose name cannot be a tool id.
"""
import sys, json

TOOLS = [
    {"name": "echo", "description": "Echo the message.",
     "inputSchema": {"type": "object",
                     "properties": {"message": {"type": "string", "description": "what to say"},
                                    "times": {"type": "integer", "description": "how often"}},
                     "required": ["message"]},
     "annotations": {"readOnlyHint": True}},
    {"name": "write-thing", "description": "Pretend to write something.",
     "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
    {"name": "boom", "description": "Always reports a tool error.",
     "inputSchema": {"type": "object", "properties": {}},
     "annotations": {"readOnlyHint": True}},
    {"name": "bad name!", "description": "Unusable id.", "inputSchema": {"type": "object", "properties": {}}},
]

def send(msg):
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    req = json.loads(line)
    method, rid = req.get("method"), req.get("id")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": rid,
              "result": {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                         "serverInfo": {"name": "stub", "version": "1.0"}}})
    elif method == "notifications/initialized":
        pass
    elif method == "tools/list":
        # a real server interleaves notifications with replies
        send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
        send({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        name = req.get("params", {}).get("name")
        args = req.get("params", {}).get("arguments", {})
        if name == "echo":
            send({"jsonrpc": "2.0", "id": rid,
                  "result": {"content": [{"type": "text", "text": "echo: " + str(args.get("message"))}]}})
        elif name == "boom":
            send({"jsonrpc": "2.0", "id": rid,
                  "result": {"content": [{"type": "text", "text": "the thing went wrong"}], "isError": True}})
        elif name == "write-thing":
            send({"jsonrpc": "2.0", "id": rid, "result": {"content": [{"type": "text", "text": "wrote " + str(args.get("path"))}]}})
        else:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "no such tool: " + str(name)}})
    else:
        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "no such method"}})
