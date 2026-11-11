// The official ACP TypeScript client (@agentclientprotocol/sdk, pinned) against
// `byj acp` (RFC-053, byj-g5n.4). No model is spent: byj runs a scripted mock.
//
//   node official-client.mjs BYJ_ARGV_JSON
//
// BYJ_ARGV_JSON is the command that runs byj, as a JSON array, e.g.
// '["jolt","-m","brainyard.main"]' from a checkout or '["byj"]'.
import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable, Writable } from "node:stream";
import assert from "node:assert/strict";
import * as acp from "@agentclientprotocol/sdk";

const argv = JSON.parse(process.argv[2] ?? '["byj"]');
const repo = new URL("../..", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "byj-acp-interop-"));
writeFileSync(join(dir, "notes.md"), "the notes\n");
const script = join(dir, "replies.edn");
writeFileSync(script, `[{:reasoning "r" :tool-calls [{:tool-name "read-file" :tool-args {:path "notes.md"}}] :answer ""}
 {:reasoning "r" :tool-calls [] :answer "the notes say: the notes" :goal-achieved true}
 {:reasoning "r" :tool-calls [] :answer "second answer" :goal-achieved true}]`);

function start(replies = script, more = {}) {
  const child = spawn(argv[0], [...argv.slice(1), "acp", "-p", "mock", "-m", `script:${replies}`],
                      { cwd: repo, stdio: ["pipe", "pipe", "inherit"] });
  const updates = [];
  const asked = [];
  const client = {
    async requestPermission(p) { asked.push(p); return { outcome: { outcome: "selected", optionId: "allow-once" } }; },
    async sessionUpdate(p) { updates.push(p.update); },
    ...more,
  };
  const conn = new acp.ClientSideConnection(() => client,
    acp.ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(child.stdout)));
  const exited = new Promise((res) => child.on("exit", (code) => res(code)));
  return { child, conn, updates, asked, exited };
}

let checks = 0;
const ok = (what) => { checks++; console.log(`ok ${checks} — ${what}`); };

{
  const { child, conn, updates, asked, exited } = start();
  const init = await conn.initialize({ protocolVersion: acp.PROTOCOL_VERSION, clientCapabilities: {} });
  assert.equal(init.protocolVersion, 1);
  assert.equal(init.agentInfo.name, "byj");
  ok(`initialize: protocol 1, ${init.agentInfo.name} ${init.agentInfo.version}`);

  const s = await conn.newSession({ cwd: dir, mcpServers: [],
    _meta: { brainyard: { permissionMode: "ask-each-time", config: { "tool-ask-effects": ["fs/read"], "builtin-agents?": false } } } });
  assert.match(s.sessionId, /^\d+-[0-9a-f]{8}$/);
  assert.ok(s.modes.availableModes.some((m) => m.id === "ask-each-time"));
  ok(`session/new: ${s.sessionId}, modes ${s.modes.availableModes.map((m) => m.id).join(",")}`);

  const r = await conn.prompt({ sessionId: s.sessionId, prompt: [{ type: "text", text: "what do the notes say?" }] });
  assert.equal(r.stopReason, "end_turn");
  ok("session/prompt: end_turn");
  assert.equal(asked.length, 1);
  assert.equal(asked[0].toolCall.kind, "read");
  ok(`session/request_permission: ${asked[0].toolCall.title}, options ${asked[0].options.map((o) => o.kind).join(",")}`);
  const call = updates.find((u) => u.sessionUpdate === "tool_call");
  const done = updates.find((u) => u.sessionUpdate === "tool_call_update");
  assert.equal(call.toolCallId, done.toolCallId);
  assert.equal(done.status, "completed");
  ok(`tool_call ${call.title} → tool_call_update ${done.status}`);
  assert.ok(updates.some((u) => u.sessionUpdate === "agent_message_chunk" && u.content.text === "the notes say: the notes"));
  ok("agent_message_chunk: the answer");

  await conn.setSessionMode({ sessionId: s.sessionId, modeId: "auto-approve" });
  ok("session/set_mode: auto-approve");

  child.stdin.end();
  assert.equal(await exited, 0);
  ok("stdin closed: exit 0");

  // the same session, loaded by a new connection
  const second = start();
  const init2 = await second.conn.initialize({ protocolVersion: acp.PROTOCOL_VERSION, clientCapabilities: {} });
  assert.deepEqual(init2.agentCapabilities.sessionCapabilities.list, {});
  const listed = await second.conn.listSessions({ cwd: dir });
  assert.equal(listed.sessions[0].sessionId, s.sessionId);
  assert.equal(listed.sessions[0].title, "what do the notes say?");
  ok(`session/list: ${listed.sessions.length} session(s), "${listed.sessions[0].title}" at ${listed.sessions[0].updatedAt}`);
  await second.conn.loadSession({ sessionId: s.sessionId, cwd: dir, mcpServers: [] });
  assert.ok(second.updates.some((u) => u.sessionUpdate === "user_message_chunk" && u.content.text === "what do the notes say?"));
  ok("session/load: the history replayed");
  second.child.stdin.end();
  assert.equal(await second.exited, 0);

  // resumed by a third, with nothing replayed, then deleted
  const third = start();
  await third.conn.initialize({ protocolVersion: acp.PROTOCOL_VERSION, clientCapabilities: {} });
  await third.conn.resumeSession({ sessionId: s.sessionId, cwd: dir, mcpServers: [] });
  assert.equal(third.updates.length, 0);
  const again = await third.conn.prompt({ sessionId: s.sessionId, prompt: [{ type: "text", text: "and again?" }] });
  assert.equal(again.stopReason, "end_turn");
  ok("session/resume: no history replayed, and a prompt answered");
  await third.conn.listSessions({ cwd: dir });
  await third.conn.deleteSession({ sessionId: s.sessionId });
  assert.equal((await third.conn.listSessions({ cwd: dir })).sessions.length, 0);
  ok("session/delete: listed no more");
  third.child.stdin.end();
  assert.equal(await third.exited, 0);
}

// an editor's fs (byj-gly.464): reads answered from its buffer, writes taken
{
  const fsScript = join(dir, "fs-replies.edn");
  writeFileSync(fsScript, `[{:reasoning "r" :tool-calls [{:tool-name "update-file" :tool-args {:path "notes.md" :pattern "unsaved" :replacement "edited"}}] :answer ""}
 {:reasoning "r" :tool-calls [] :answer "done" :goal-achieved true}]`);
  const reads = [], writes = [];
  const editor = start(fsScript, {
    async readTextFile(p) { reads.push(p); return { content: "the unsaved notes\n" }; },
    async writeTextFile(p) { writes.push(p); return {}; },
  });
  await editor.conn.initialize({ protocolVersion: acp.PROTOCOL_VERSION,
                                 clientCapabilities: { fs: { readTextFile: true, writeTextFile: true } } });
  const s = await editor.conn.newSession({ cwd: dir, mcpServers: [], _meta: { brainyard: { config: { "builtin-agents?": false } } } });
  const r = await editor.conn.prompt({ sessionId: s.sessionId, prompt: [{ type: "text", text: "edit the notes" }] });
  assert.equal(r.stopReason, "end_turn");
  assert.equal(reads.length, 1);
  assert.equal(reads[0].sessionId, s.sessionId);
  assert.equal(reads[0].path, join(dir, "notes.md"));
  assert.deepEqual(writes.map((w) => [w.path, w.content]), [[join(dir, "notes.md"), "the edited notes\n"]]);
  ok("fs/read_text_file and fs/write_text_file: update-file read the buffer and wrote into it");
  editor.child.stdin.end();
  assert.equal(await editor.exited, 0);
}

console.log(`${checks} checks passed`);
