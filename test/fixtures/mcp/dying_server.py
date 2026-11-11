#!/usr/bin/env python3
"""Answers initialize, complains on stderr, and exits — a server whose command
is wrong, which is the common way one dies (a missing token, a bad path)."""
import sys, json
req = json.loads(sys.stdin.readline())
sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": req.get("id"),
                             "result": {"protocolVersion": "2024-11-05", "capabilities": {},
                                        "serverInfo": {"name": "dying"}}}) + "\n")
sys.stdout.flush()
sys.stderr.write("FATAL: missing API key MYSERVICE_TOKEN\n")
sys.stderr.flush()
sys.exit(1)
