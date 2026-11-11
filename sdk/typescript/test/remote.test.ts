// The remote client against an A2A endpoint shaped as Share's relay and
// byj-a2a answer (RFC-060 D11, byj-1wv.115.14): the card, a send answered
// with a completed task, a stream of status frames, the context kept from
// one message to the next, and each refusal as its own kind of error.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { connectShared, SharedAgentError, KEEPS_QUESTIONS } from "../src/index.js";

type Handler = (body: any, req: IncomingMessage, res: ServerResponse) => void;

async function serve(handler: Handler, card: Record<string, unknown> = {}) {
  const seen: any[] = [];
  const srv = createServer((req, res) => {
    if (req.method === "GET") {
      res.setHeader("Content-Type", "application/json");
      if (req.url!.includes("gone")) {
        res.statusCode = 404;
        res.end(JSON.stringify({ error: "this agent share is not live" }));
        return;
      }
      res.end(JSON.stringify({ name: "byj", description: "byj's agents", url: `http://127.0.0.1:${(srv.address() as any).port}/a2a/shr_x`,
        protocolVersion: "0.3", skills: [{ id: "support-faq", name: "support-faq" }], ...card }));
      return;
    }
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const body = JSON.parse(raw);
      seen.push({ body, auth: req.headers.authorization });
      handler(body, req, res);
    });
  });
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(srv.address() as any).port}/a2a/shr_x`;
  return { base, seen, close: () => new Promise((r) => srv.close(r)) };
}

const json = (res: ServerResponse, status: number, v: unknown) => {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify(v));
};

test("ask answers from a completed task, keeps its context and cost, and sends the token", async () => {
  const s = await serve((body, _req, res) =>
    json(res, 200, { jsonrpc: "2.0", id: body.id, result: { kind: "task", id: "t1", contextId: "ctx-1",
      status: { state: "completed", message: { role: "agent", parts: [{ kind: "text", text: "Within 30 days." }] } },
      metadata: { "ai.brainyard/cost-usd": 0.012 } } }));
  try {
    const agent = await connectShared(s.base, "app-token");
    assert.equal(agent.card.name, "byj");
    const a = await agent.ask("Refund window?");
    assert.deepEqual(a, { answer: "Within 30 days.", taskId: "t1", contextId: "ctx-1", costUsd: 0.012 });
    await agent.ask("And shipping?");
    assert.equal(s.seen[0].auth, "Bearer app-token");
    assert.equal(s.seen[0].body.method, "message/send");
    assert.equal(s.seen[0].body.params.message.contextId, undefined, "the first starts a conversation");
    assert.equal(s.seen[1].body.params.message.contextId, "ctx-1", "the next continues it");
  } finally {
    await s.close();
  }
});

test("keepsQuestions says what the card says: its share keeps its callers' questions (byj-1wv.119)", async () => {
  const keeps = await serve(() => {}, { capabilities: { streaming: true, extensions: [{ uri: KEEPS_QUESTIONS, description: "kept" }] } });
  const not = await serve(() => {}, { capabilities: { streaming: true } });
  try {
    assert.equal((await connectShared(keeps.base, "t")).keepsQuestions, true);
    assert.equal((await connectShared(not.base, "t")).keepsQuestions, false);
  } finally {
    await keeps.close();
    await not.close();
  }
});

test("stream yields the working text as it grows, then the answer", async () => {
  const s = await serve((body, _req, res) => {
    res.setHeader("Content-Type", "text/event-stream");
    const frame = (r: unknown) => res.write(`data: ${JSON.stringify({ jsonrpc: "2.0", id: body.id, result: r })}\n\n`);
    frame({ kind: "task", id: "t2", contextId: "ctx-2", status: { state: "submitted" } });
    frame({ kind: "status-update", taskId: "t2", contextId: "ctx-2", status: { state: "working", message: { parts: [{ kind: "text", text: "reading" }] } }, final: false });
    frame({ kind: "status-update", taskId: "t2", contextId: "ctx-2", status: { state: "working", message: { parts: [{ kind: "text", text: "reading\ncalling read-file" }] } }, final: false });
    frame({ kind: "status-update", taskId: "t2", contextId: "ctx-2", status: { state: "completed", message: { parts: [{ kind: "text", text: "Within 30 days." }] } }, final: true });
    res.end();
  });
  try {
    const agent = await connectShared(s.base, "t");
    const events = [];
    for await (const e of agent.stream("Refund window?")) events.push(e);
    assert.deepEqual(events, [
      { type: "working", text: "reading" },
      { type: "working", text: "reading\ncalling read-file" },
      { type: "answer", answer: "Within 30 days.", taskId: "t2", contextId: "ctx-2" },
    ]);
    assert.equal(agent.context, "ctx-2");
    assert.equal(s.seen[0].body.method, "message/stream");
  } finally {
    await s.close();
  }
});

test("each refusal is an error of its own kind", async () => {
  const cases: [string, (res: ServerResponse, id: number) => void, string][] = [
    ["unauthorized", (res) => json(res, 401, { error: "unauthorized" }), "unauthorized"],
    ["removed", (res) => json(res, 403, { error: "forbidden" }), "refused"],
    ["on a subscription", (res) => json(res, 403, { error: "this agent runs on its owner's Claude subscription, which answers its owner alone: its owner shares it with others on an API key" }), "owner-only"],
    ["off", (res, id) => json(res, 200, { jsonrpc: "2.0", id, error: { code: -32004, message: "this agent share is not live" } }), "not-live"],
    ["a cap", (res, id) => json(res, 200, { jsonrpc: "2.0", id, error: { code: -32004, message: "this share's cap of 200 messages is reached" } }), "cap"],
    ["busy", (res, id) => json(res, 200, { jsonrpc: "2.0", id, error: { code: -32004, message: "busy: this machine is holding as many conversations as it can; try again shortly" } }), "busy"],
    ["a failed turn", (res, id) => json(res, 200, { jsonrpc: "2.0", id, result: { kind: "task", id: "t", status: { state: "failed", message: { parts: [{ kind: "text", text: "boom" }] } } } }), "failed"],
  ];
  for (const [what, answer, kind] of cases) {
    const s = await serve((body, _req, res) => answer(res, body.id));
    try {
      const agent = await connectShared(s.base, "t");
      await assert.rejects(agent.ask("hi"), (e: unknown) => e instanceof SharedAgentError && e.kind === kind, what);
    } finally {
      await s.close();
    }
  }
  const s = await serve(() => {});
  try {
    await assert.rejects(connectShared(s.base.replace("shr_x", "gone"), "t"), (e: unknown) => e instanceof SharedAgentError && e.kind === "not-live");
  } finally {
    await s.close();
  }
});
