// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

const repo = new URL("../..", import.meta.url).pathname;
const child = spawn("jolt", ["-A:dev", "-m", "brainyard.dev.sdk-stdio-spike", "acp"], { cwd: repo, stdio: ["pipe", "pipe", "inherit"] });
const stream = acp.ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(child.stdout));
const seen = [];
const client = {
  async requestPermission(p) { seen.push(["permission", p.toolCall.toolCallId]); return { outcome: { outcome: "selected", optionId: "allow-once" } }; },
  async sessionUpdate(p) { seen.push(["update", p.update.sessionUpdate, p.update.content?.text ?? p.update.status ?? ""]); },
  async extMethod(method, params) { seen.push(["ext", method, JSON.stringify(params)]); return { content: "order 1042: refunded" }; },
  async extNotification(method, params) { seen.push(["extnote", method]); },
};
const conn = new acp.ClientSideConnection(() => client, stream);
const init = await conn.initialize({ protocolVersion: acp.PROTOCOL_VERSION, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false } } });
console.log("initialize:", JSON.stringify(init));
const { sessionId } = await conn.newSession({ cwd: repo, mcpServers: [] });
const r = await conn.prompt({ sessionId, prompt: [{ type: "text", text: "hello" }] });
console.log("prompt:", JSON.stringify(r));
for (const s of seen) console.log(" ", s.join(" | "));
child.stdin.end();
await new Promise((res) => child.on("exit", (code) => { console.log("agent exit", code); res(); }));
