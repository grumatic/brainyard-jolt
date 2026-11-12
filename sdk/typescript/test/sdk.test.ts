// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

// The TypeScript SDK against a real `byj acp` (RFC-053, byj-g5n.6). No model
// is spent: byj runs `-p mock -m script:FILE`, a scripted mock. byj is this
// checkout (`jolt -m brainyard.main`) unless BYJ_BIN names a binary.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { spawn } from "node:child_process";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { chmodSync } from "node:fs";
import { ByjClient, ByjVersionError, query, tool, type ClientOptions, type Message } from "../src/index.js";

const repo = fileURLToPath(new URL("../../../../", import.meta.url));
const byj: ClientOptions = process.env.BYJ_BIN
  ? { byjPath: process.env.BYJ_BIN }
  : { byjPath: "jolt", byjArgs: ["-m", "brainyard.main"], processCwd: repo };

/** A project with a scripted mock (`-m script:FILE`): each reply an EDN map. */
function project(replies: string[], files: Record<string, string> = {}) {
  const dir = mkdtempSync(join(tmpdir(), "byj-sdk-ts-"));
  for (const [f, text] of Object.entries(files)) writeFileSync(join(dir, f), text);
  const script = join(dir, "replies.edn");
  writeFileSync(script, `[${replies.join("\n")}]`);
  return { cwd: dir, provider: "mock", model: `script:${script}`,
           config: { "builtin-agents?": false, "memory?": false, "memory-hint?": false } };
}

const collect = async (it: AsyncIterable<Message>) => { const out: Message[] = []; for await (const m of it) out.push(m); return out; };

test("query streams a turn and ends with its result", async () => {
  const p = project([
    `{:reasoning "r" :tool-calls [{:tool-name "read-file" :tool-args {:path "notes.md"}}] :answer ""}`,
    `{:reasoning "r" :tool-calls [] :answer "the notes say hi" :goal-achieved true}`,
  ], { "notes.md": "hi" });
  const ms = await collect(query({ prompt: "what do the notes say?", options: { ...byj, ...p } }));
  const call = ms.find((m) => m.type === "toolCall");
  const done = ms.find((m) => m.type === "toolResult");
  assert.ok(call && call.type === "toolCall" && call.kind === "read" && call.title.startsWith("read-file notes.md"));
  assert.ok(done && done.type === "toolResult" && done.status === "completed" && done.output.includes("hi"));
  assert.ok(ms.some((m) => m.type === "text" && m.text === "the notes say hi"));
  const r = ms.at(-1)!;
  assert.equal(r.type, "result");
  if (r.type !== "result") return;
  assert.equal(r.isError, false);
  assert.equal(r.stopReason, "end_turn");
  assert.equal(r.answer, "the notes say hi");
  assert.equal(typeof r.iterations, "number");
});

test("a tool of the application's is called, after canUseTool allows it", async () => {
  const p = project([
    `{:reasoning "r" :tool-calls [{:tool-name "order_lookup" :tool-args {:id "1042"}}] :answer ""}`,
    `{:reasoning "r" :tool-calls [] :answer "refunded" :goal-achieved true}`,
  ]);
  const calls: unknown[] = [];
  const asked: string[] = [];
  const lookup = tool("order_lookup", "An order by its id",
    { type: "object", properties: { id: { type: "string", description: "the order id" } }, required: ["id"] },
    async (args: { id: string }) => { calls.push(args); return { id: args.id, status: "refunded" }; });
  const ms = await collect(query({
    prompt: "why was 1042 refunded?",
    options: { ...byj, ...p, tools: [lookup], permissionMode: "ask-each-time",
               config: { ...p.config, "tool-ask-effects": ["sdk/call"] },
               canUseTool: (req) => { asked.push(req.title); return { behavior: "allow" }; } },
  }));
  assert.deepEqual(calls, [{ id: "1042" }]);
  assert.deepEqual(asked, ["order_lookup"]);
  assert.ok(ms.some((m) => m.type === "permission" && m.by === "callback" && m.decision.behavior === "allow"));
  const r = ms.at(-1)!;
  assert.ok(r.type === "result" && r.answer === "refunded");
});

test("with no canUseTool, a prompt is refused and said so", async () => {
  const p = project([
    `{:reasoning "r" :tool-calls [{:tool-name "read-file" :tool-args {:path "a.md"}}] :answer ""}`,
    `{:reasoning "r" :tool-calls [] :answer "could not read it"}`,
  ], { "a.md": "A" });
  const ms = await collect(query({
    prompt: "read a",
    options: { ...byj, ...p, permissionMode: "ask-each-time", config: { ...p.config, "tool-ask-effects": ["fs/read"] } },
  }));
  assert.ok(ms.some((m) => m.type === "permission" && m.by === "none" && m.decision.behavior === "deny"));
  assert.ok(ms.some((m) => m.type === "toolResult" && m.output.includes("denied")));
});

test("a hook blocks a call", async () => {
  const p = project([
    `{:reasoning "r" :tool-calls [{:tool-name "bash" :tool-args {:command "touch made-it"}}] :answer ""}`,
    `{:reasoning "r" :tool-calls [] :answer "done"}`,
  ]);
  const seen: string[] = [];
  await collect(query({
    prompt: "make it",
    options: { ...byj, ...p, permissionMode: "auto-approve",
               hooks: [{ event: "agent.session/created", handler: () => { seen.push("created"); } },
                       { event: "agent.tool-use/pre", tool: "bash",
                         handler: (payload) => { seen.push(payload["tool-name"]); return { block: "no shell here" }; } }] },
  }));
  assert.deepEqual(seen, ["created", "bash"], "a hook on the session's opening is answered too");
  assert.equal(existsSync(join(p.cwd, "made-it")), false);
});

test("a client holds sessions: events, a cancel, a mode, predict", async () => {
  const client = await ByjClient.start(byj);
  try {
    assert.equal(client.agentInfo?.name, "byj");
    const p = project([`{:reasoning "r" :tool-calls [{:tool-name "bash" :tool-args {:command "sleep 30"}}] :answer ""}`]);
    const s = await client.newSession({ ...p, permissionMode: "auto-approve", events: ["session.turn/start", "session.turn/end"] });
    assert.ok(s.modes.some((m) => m.id === "ask-each-time"));
    const ms: Message[] = [];
    const running = (async () => { for await (const m of s.prompt("wait")) { ms.push(m); if (m.type === "toolCall") s.cancel(); } })();
    await running;
    const r = ms.at(-1)!;
    assert.ok(r.type === "result" && r.stopReason === "cancelled", JSON.stringify(r));
    assert.ok(ms.some((m) => m.type === "event" && m.event === "session.turn/start"));
    await s.setPermissionMode("deny-by-default");

    const q = project([`{:reasoning "a charge twice is billing" :category "billing"}`]);
    const out = await client.predict({
      cwd: q.cwd, provider: "mock", model: q.model,
      signature: { instructions: "Classify the ticket.",
                   inputs: [{ name: "ticket", description: "the ticket" }],
                   outputs: [{ name: "category", schema: { enum: ["billing", "bug", "other"] } }] },
      inputs: { ticket: "I was charged twice" },
    });
    assert.equal(out.outputs.category, "billing");
    assert.equal(out.predictorId, "user/inline");
  } finally {
    await client.close();
  }
});

test("a session is loaded with its history", async () => {
  const p = project([`{:reasoning "r" :tool-calls [] :answer "first answer" :goal-achieved true}`]);
  const one = await ByjClient.start(byj);
  const s = await one.newSession(p);
  await s.run("first question");
  await one.close();
  const two = await ByjClient.start(byj);
  try {
    const history: Message[] = [];
    await two.loadSession(s.id, p, (m) => history.push(m));
    assert.ok(history.some((m) => m.type === "user" && m.text === "first question"));
    assert.ok(history.some((m) => m.type === "text" && m.text === "first answer"));
  } finally {
    await two.close();
  }
});

test("a byj with no acp is refused before it is started, and spends nothing", async () => {
  // an older byj takes `acp` for a question; its help is what it answers to -h
  const dir = mkdtempSync(join(tmpdir(), "byj-old-"));
  const old = join(dir, "byj");
  const asked = join(dir, "asked");
  writeFileSync(old, `#!/bin/sh\ncase "$*" in *-h*) echo "NAME:"; echo " byj - brainyard's agent runtime";; *) touch ${asked};; esac\n`);
  chmodSync(old, 0o755);
  await assert.rejects(ByjClient.start({ byjPath: old }), (e: Error) =>
    e instanceof ByjVersionError && /no `acp` command/.test(e.message));
  assert.equal(existsSync(asked), false, "it was never run as an agent");
  await assert.rejects(ByjClient.start({ byjPath: join(dir, "nothing-here") }), /no .* to run/);
});

test("an answer is given back as the data asked for", async () => {
  const p = project([
    `{:reasoning "r" :tool-calls [] :answer "Order 1042 was charged twice and refunded." :goal-achieved true}`,
    `{:reasoning "r" :order "1042" :refunded true}`,
  ]);
  const ms = await collect(query({
    prompt: "what happened to 1042?",
    options: { ...byj, ...p, outputSchema: { type: "object", properties: { order: { type: "string" }, refunded: { type: "boolean" } },
                                             required: ["order", "refunded"] } },
  }));
  const r = ms.at(-1)!;
  assert.ok(r.type === "result");
  if (r.type !== "result") return;
  assert.deepEqual(r.structuredOutput, { order: "1042", refunded: true });
  assert.equal(r.answer, "Order 1042 was charged twice and refunded.");
});

test("a live session it did not start: listed, asked, watched", async () => {
  const dir = mkdtempSync(join(tmpdir(), "byj-live-"));
  // a headless session of the project, as byj-a2a starts one (RFC-031)
  const argv = process.env.BYJ_BIN ? [process.env.BYJ_BIN] : ["jolt", "-m", "brainyard.main"];
  const served = spawn(argv[0], [...argv.slice(1), "-C", dir, "run", "--serve", "-p", "mock", "--label", "worker"],
                       { cwd: process.env.BYJ_BIN ? dir : repo, stdio: ["ignore", "pipe", "inherit"] });
  await new Promise<void>((resolve, reject) => {
    served.stdout.on("data", (d) => { if (String(d).includes("serving session")) resolve(); });
    served.on("exit", (code) => reject(new Error(`byj run --serve ended (${code})`)));
  });
  const client = await ByjClient.start(byj);
  try {
    const rows = await client.live.list(dir);
    assert.equal(rows.length, 1);
    assert.equal(rows[0].label, "worker");
    const seen: string[] = [];
    const w = await client.live.attach(dir, "worker", (e) => seen.push(e.event), { events: ["session.turn/start", "session.turn/end"] });
    const r = await client.live.ask(dir, "worker", "how far along?");
    assert.match(r.answer, /how far along\?/);
    for (let i = 0; i < 100 && seen.length < 2; i++) await new Promise((res) => setTimeout(res, 20));
    assert.deepEqual(seen, ["session.turn/start", "session.turn/end"]);
    await w.detach();
  } finally {
    await client.close();
    served.kill("SIGINT");
    await new Promise((res) => served.on("exit", res));
  }
});

test("a session is forked: its history, a new id, the original left as it was", async () => {
  const p = project([`{:reasoning "r" :tool-calls [] :answer "first answer" :goal-achieved true}`]);
  const client = await ByjClient.start(byj);
  try {
    const one = await client.newSession(p);
    await one.run("first question");
    await one.close();
    const history: Message[] = [];
    const two = await client.forkSession(one.id, p, (m) => history.push(m));
    assert.notEqual(two.id, one.id);
    assert.ok(history.some((m) => m.type === "user" && m.text === "first question"));
    await two.close();
    // the original is still there to load, as it was
    const again: Message[] = [];
    const three = await client.loadSession(one.id, p, (m) => again.push(m));
    assert.equal(again.filter((m) => m.type === "user").length, 1);
    await three.close();
  } finally {
    await client.close();
  }
});
