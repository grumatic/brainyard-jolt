#!/usr/bin/env python3
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

"""An MCP server that asks the client mid-call (elicitation/create), for
brainyard.mcp-test: `publish` asks a yes or no and says what it heard;
`fill` asks for a field to fill in, which byj declines; and before either
reply it pings the client, which must answer too."""
import sys, json

TOOLS = [
    {"name": "publish", "description": "Publish, once the person says yes.",
     "inputSchema": {"type": "object", "properties": {"what": {"type": "string"}}, "required": ["what"]}},
    {"name": "fill", "description": "Ask for a field.",
     "inputSchema": {"type": "object", "properties": {}}},
]

def send(msg):
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()

def reply_to(rid):
    """Read until the client's reply to our request rid."""
    while True:
        line = sys.stdin.readline()
        if not line:
            sys.exit(0)
        m = json.loads(line)
        if m.get("id") == rid and "method" not in m:
            return m

capabilities = {}
while True:
    line = sys.stdin.readline()
    if not line:
        break
    line = line.strip()
    if not line:
        continue
    req = json.loads(line)
    method, rid = req.get("method"), req.get("id")
    if method == "initialize":
        capabilities = req.get("params", {}).get("capabilities", {})
        send({"jsonrpc": "2.0", "id": rid,
              "result": {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                         "serverInfo": {"name": "eliciting", "version": "1.0"}}})
    elif method == "notifications/initialized":
        pass
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        name = req.get("params", {}).get("name")
        args = req.get("params", {}).get("arguments", {})
        if "elicitation" not in capabilities:
            send({"jsonrpc": "2.0", "id": rid, "result": {"content": [{"type": "text", "text": "no one to ask"}], "isError": True}})
            continue
        send({"jsonrpc": "2.0", "id": "p1", "method": "ping"})
        pong = reply_to("p1")
        schema = {"type": "object", "properties": {}}
        message = "Publish " + str(args.get("what")) + "?"
        if name == "fill":
            schema = {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
            message = "Your name?"
        send({"jsonrpc": "2.0", "id": "e1", "method": "elicitation/create",
              "params": {"message": message, "requestedSchema": schema}})
        answer = reply_to("e1")
        action = answer.get("result", {}).get("action")
        send({"jsonrpc": "2.0", "id": rid,
              "result": {"content": [{"type": "text", "text": "heard " + str(action) + ("; pinged" if "result" in pong else "")}]}})
    else:
        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "no such method"}})
