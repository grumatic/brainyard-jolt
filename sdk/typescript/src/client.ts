// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

// ByjClient: one `byj acp` process, any number of sessions (RFC-053 §5).
import { execFile, spawn } from "node:child_process";
import { Connection, RpcError } from "./rpc.js";
import type {
  ClientOptions, Hook, JsonSchema, LiveSession, Message, PermissionDecision, PermissionMode, PermissionRequest,
  PredictOptions, PredictResult, ResultMessage, SessionOptions, Tool,
} from "./types.js";

/** The ACP version spoken. */
export const PROTOCOL_VERSION = 1;
/** byj's extensions this SDK needs, and their version. */
export const EXTENSIONS = ["tools", "hooks", "events", "predict"];
export const EXTENSIONS_VERSION = 1;
export const SDK_VERSION = "0.1.0";

export class ByjVersionError extends Error {
  constructor(message: string) { super(message); this.name = "ByjVersionError"; }
}

/** A queue a prompt's messages wait in until the application reads them. */
class Channel<T> {
  private items: T[] = [];
  private waiters: ((r: IteratorResult<T>) => void)[] = [];
  private done = false;
  push(x: T) {
    const w = this.waiters.shift();
    if (w) w({ value: x, done: false }); else this.items.push(x);
  }
  close() {
    this.done = true;
    for (const w of this.waiters.splice(0)) w({ value: undefined as any, done: true });
  }
  next(): Promise<IteratorResult<T>> {
    if (this.items.length) return Promise.resolve({ value: this.items.shift()!, done: false });
    if (this.done) return Promise.resolve({ value: undefined as any, done: true });
    return new Promise((r) => this.waiters.push(r));
  }
}

export class Session {
  /** The prompt being answered: its messages go here. */
  current: Channel<Message> | null = null;
  readonly tools = new Map<string, Tool>();

  constructor(
    readonly client: ByjClient,
    readonly id: string,
    readonly options: SessionOptions,
    readonly modes: { id: string; name: string }[],
  ) {
    for (const t of options.tools ?? []) this.tools.set(t.name, t);
  }

  emit(m: Message) { this.current?.push(m); }

  /**
   * One prompt, as the messages it produces: text and thoughts as they are
   * written, tool calls and their results, permission decisions, byj's events
   * when asked for, and last a `result`. One prompt at a time per session.
   */
  prompt(text: string, opts: { outputSchema?: JsonSchema } = {}): AsyncIterable<Message> {
    if (this.current) throw new Error("a prompt is already running in this session");
    const ch = new Channel<Message>();
    this.current = ch;
    const done = this.client.conn
      .request("session/prompt", {
        sessionId: this.id, prompt: [{ type: "text", text }],
        ...(opts.outputSchema && { _meta: { brainyard: { outputSchema: opts.outputSchema } } }),
      })
      .then((r: any) => {
        const m = r?._meta?.brainyard ?? {};
        const result: ResultMessage = {
          type: "result", sessionId: this.id, stopReason: r.stopReason,
          answer: m.answer ?? "", terminatedBy: m.terminatedBy, iterations: m.iterations,
          goalAchieved: m.goalAchieved, nextUserPrompt: m.nextUserPrompt, elapsedMs: m.elapsedMs,
          usage: m.usage, costUsd: m.costUsd, isError: false,
          ...("structuredOutput" in m && { structuredOutput: m.structuredOutput }),
          ...(m.structuredOutputError && { structuredOutputError: m.structuredOutputError }),
        };
        ch.push(result);
      })
      .catch((e: Error) => {
        ch.push({ type: "result", sessionId: this.id, stopReason: "error", answer: "",
                  isError: true, error: e instanceof RpcError ? `${e.message}${e.data ? ` ${JSON.stringify(e.data)}` : ""}` : e.message });
      })
      .finally(() => { this.current = null; ch.close(); });
    void done;
    return { [Symbol.asyncIterator]: () => ({ next: () => ch.next() }) };
  }

  /** The prompt's result alone, its messages passed to `onMessage` on the way. */
  async run(text: string, onMessage?: (m: Message) => void, opts: { outputSchema?: JsonSchema } = {}): Promise<ResultMessage> {
    let result: ResultMessage | undefined;
    for await (const m of this.prompt(text, opts)) {
      onMessage?.(m);
      if (m.type === "result") result = m;
    }
    return result!;
  }

  /** Cancel the running turn; its prompt ends `cancelled`. */
  cancel() { this.client.conn.notify("session/cancel", { sessionId: this.id }); }

  /** End the session: its turn cancelled, its lock released. */
  async close() {
    await this.client.conn.request("session/close", { sessionId: this.id });
    this.client.sessions.delete(this.id);
  }

  async setPermissionMode(mode: PermissionMode) {
    await this.client.conn.request("session/set_mode", { sessionId: this.id, modeId: mode });
  }
}

function findByj(o: ClientOptions): string {
  return o.byjPath ?? process.env.BYJ_BIN ?? "byj";
}

/** The byjs already seen to have `acp`, by command line: asked once a process. */
const probed = new Map<string, Promise<void>>();

/**
 * Whether this byj has `acp`, asked with `byj acp -h`, which no byj answers
 * with a model: one older than the SDK takes `acp` for a question and would
 * spend a model call on it, where its help costs nothing (RFC-053 "Versions").
 */
function probe(o: ClientOptions): Promise<void> {
  const exe = findByj(o);
  const args = [...(o.byjArgs ?? []), "acp", "-h"];
  const key = JSON.stringify([exe, args, o.processCwd]);
  let p = probed.get(key);
  if (!p) {
    p = new Promise<void>((resolve, reject) => {
      execFile(exe, args, { cwd: o.processCwd, env: { ...process.env, ...(o.env ?? {}) }, timeout: 60000 },
        (err, stdout, stderr) => {
          if (/byj acp - /.test(`${stdout}${stderr}`)) return resolve();
          reject(err && (err as any).code === "ENOENT"
            ? new Error(`no ${exe} to run: install byj, or name it with byjPath or $BYJ_BIN`)
            : new ByjVersionError(`${exe} has no \`acp\` command: it is older than this SDK (${SDK_VERSION}) — run \`byj update\``));
        });
    });
    probed.set(key, p);
    p.catch(() => probed.delete(key));
  }
  return p;
}

function sessionMeta(o: SessionOptions, s: { hooks: [string, Hook][] }) {
  return {
    brainyard: {
      ...(o.agent && { agent: o.agent }),
      ...(o.provider && { provider: o.provider }),
      ...(o.model && { model: o.model }),
      ...(o.loop && { loop: o.loop }),
      ...(o.config && { config: o.config }),
      ...(o.permissionMode && { permissionMode: o.permissionMode }),
      ...(o.events && { events: o.events }),
      ...(o.label && { label: o.label }),
      ...(o.commands && { commands: true }),
      tools: (o.tools ?? []).map((t) => ({
        name: t.name, description: t.description, inputSchema: t.inputSchema,
        ...(t.effects && { effects: t.effects }),
      })),
      hooks: s.hooks.map(([id, h]) => ({
        id, event: h.event,
        ...(h.tool && { match: { tool: h.tool } }),
        ...(h.timeoutMs && { timeoutMs: h.timeoutMs }),
        ...(h.failClosed && { failClosed: true }),
      })),
    },
  };
}

export class ByjClient {
  readonly sessions = new Map<string, Session>();
  /** Every session's hooks, by an id unique to this client: a hook firing as
   *  a session opens (`agent.session/created`) is answered before the
   *  session's own id has come back. */
  private readonly hooks = new Map<string, Hook>();
  private hookCount = 0;
  agentInfo: { name: string; title?: string; version: string } | undefined;

  private constructor(readonly conn: Connection) {
    conn.onNotification("session/update", (p) => this.onUpdate(p));
    conn.onNotification("_byj/event", (p) =>
      this.sessions.get(p.sessionId)?.emit({ type: "event", sessionId: p.sessionId, event: p.event, payload: p.payload }));
    conn.onNotification("_byj/live/event", (p) => this.watchers.get(p.attachId)?.({ event: p.event, payload: p.payload }));
    conn.onRequest("session/request_permission", (p) => this.onPermission(p));
    conn.onRequest("_byj/tool/call", (p) => this.onToolCall(p));
    conn.onRequest("_byj/hook", (p) => this.onHook(p));
  }

  /** Start byj (`byj acp`) and shake hands: the ACP version, and byj's extensions. */
  static async start(o: ClientOptions = {}): Promise<ByjClient> {
    await probe(o);
    const child = spawn(findByj(o), [...(o.byjArgs ?? []), "acp"], {
      cwd: o.processCwd,
      env: { ...process.env, ...(o.env ?? {}) },
      stdio: ["pipe", "pipe", "pipe"],
    });
    child.stderr.setEncoding("utf8");
    let err = "";
    child.stderr.on("data", (d: string) => {
      err = (err + d).slice(-4000);
      if (o.stderr) for (const line of d.split("\n")) if (line) o.stderr(line);
    });
    const client = new ByjClient(new Connection(child));
    let init: any;
    try {
      init = await client.conn.request("initialize", {
        protocolVersion: PROTOCOL_VERSION,
        clientCapabilities: {},
        clientInfo: { name: "@grumatic/byj-agent-sdk", version: SDK_VERSION },
        _meta: { brainyard: { sdk: "typescript", version: SDK_VERSION } },
      });
    } catch (e: any) {
      throw new Error(`could not start ${findByj(o)} acp: ${e.message}${err ? `\n${err}` : ""}`);
    }
    client.agentInfo = init.agentInfo;
    const ext = init?.agentCapabilities?._meta?.brainyard;
    const missing = EXTENSIONS.filter((x) => !(ext?.extensions ?? []).includes(x));
    if (init.protocolVersion !== PROTOCOL_VERSION || !ext || ext.protocol < EXTENSIONS_VERSION || missing.length) {
      await client.close();
      throw new ByjVersionError(
        `byj ${init?.agentInfo?.version ?? "?"} does not offer what this SDK (${SDK_VERSION}) needs` +
        (missing.length ? ` — no ${missing.join(", ")}` : "") + ": run `byj update`");
    }
    return client;
  }

  /** A new session in a project. */
  async newSession(o: SessionOptions): Promise<Session> {
    return this.open("session/new", o);
  }

  /** A saved session, resumed; its history arrives as `user`, `text` and `thought` messages to `onHistory`. */
  async loadSession(sessionId: string, o: SessionOptions, onHistory?: (m: Message) => void): Promise<Session> {
    return this.open("session/load", o, sessionId, onHistory);
  }

  /**
   * A new session that starts where a saved one stands — its history, under a
   * new id — leaving that one as it was; its history to `onHistory`.
   */
  async forkSession(fromSessionId: string, o: SessionOptions, onHistory?: (m: Message) => void): Promise<Session> {
    // the new id is chosen here, so the history replayed before session/new
    // answers is routed to it, as a load's is
    const id = `${Date.now()}-${Math.floor(Math.random() * 0xffffffff).toString(16).padStart(8, "0")}`;
    return this.open("session/new", o, id, onHistory, fromSessionId);
  }

  private async open(method: string, o: SessionOptions, sessionId?: string, onHistory?: (m: Message) => void,
                     forkFrom?: string) {
    const hooks: [string, Hook][] = (o.hooks ?? []).map((h) => [`h${++this.hookCount}`, h]);
    for (const [id, h] of hooks) this.hooks.set(id, h);
    // the session exists before its id comes back: a history being replayed
    // (session/load) is routed to it under that id
    const pending = sessionId ? new Session(this, sessionId, o, []) : null;
    if (pending) {
      const ch = new Channel<Message>();
      pending.current = ch;
      (async () => { for (let r = await ch.next(); !r.done; r = await ch.next()) onHistory?.(r.value); })();
      this.sessions.set(sessionId!, pending);
    }
    try {
      const meta = sessionMeta(o, { hooks });
      if (forkFrom) Object.assign(meta.brainyard, { forkFrom, sessionId });
      const r: any = await this.conn.request(method, {
        ...(sessionId && !forkFrom && { sessionId }),
        cwd: o.cwd,
        mcpServers: (o.mcpServers ?? []).map((m) => ({
          name: m.name, command: m.command, args: m.args ?? [],
          env: Object.entries(m.env ?? {}).map(([name, value]) => ({ name, value })),
        })),
        _meta: meta,
      });
      const id = sessionId ?? r.sessionId;
      const s = new Session(this, id, o, r?.modes?.availableModes ?? []);
      if (pending) { pending.current?.close(); }
      this.sessions.set(id, s);
      return s;
    } catch (e) {
      if (sessionId) this.sessions.delete(sessionId);
      throw e;
    }
  }

  /**
   * Sessions this client did not start — a person's `byj run`, a headless
   * `byj run --serve` — reached over their session channel (RFC-031): listed,
   * asked a question (a turn of theirs, answered), watched.
   */
  readonly live = {
    list: async (cwd: string, opts: { all?: boolean } = {}): Promise<LiveSession[]> =>
      (await this.conn.request("_byj/live/list", { cwd, ...(opts.all && { all: true }) })).sessions,
    ask: async (cwd: string, session: string, question: string, opts: { timeoutMs?: number } = {}) =>
      this.conn.request<{ sessionId: string; answer: string; usage?: unknown; provider?: string; model?: string }>(
        "_byj/live/ask", { cwd, sessionId: session, question, ...(opts.timeoutMs && { timeoutMs: opts.timeoutMs }) }),
    /** Its events, as they happen, to `onEvent`, until `detach()`. */
    attach: async (cwd: string, session: string, onEvent: (e: { event: string; payload: unknown }) => void,
                   opts: { events?: string[] } = {}) => {
      const r = await this.conn.request<{ attachId: string; sessionId: string }>(
        "_byj/live/attach", { cwd, sessionId: session, ...(opts.events && { events: opts.events }) });
      this.watchers.set(r.attachId, onEvent);
      return {
        sessionId: r.sessionId,
        detach: async () => {
          this.watchers.delete(r.attachId);
          await this.conn.request("_byj/live/detach", { attachId: r.attachId });
        },
      };
    },
  };
  private readonly watchers = new Map<string, (e: { event: string; payload: unknown }) => void>();

  /** One predictor call: structured output, no session, no tools. */
  async predict(o: PredictOptions): Promise<PredictResult> {
    return this.conn.request("_byj/predict", o);
  }

  /** End byj: every session closed, its turn cancelled. */
  async close(timeoutMs = 15000): Promise<void> {
    this.conn.end();
    await Promise.race([this.conn.closed, new Promise((r) => setTimeout(r, timeoutMs))]);
  }

  private onUpdate({ sessionId, update: u }: any) {
    const s = this.sessions.get(sessionId);
    if (!s) return;
    switch (u.sessionUpdate) {
      case "agent_message_chunk": s.emit({ type: "text", sessionId, text: u.content?.text ?? "" }); break;
      case "agent_thought_chunk": s.emit({ type: "thought", sessionId, text: u.content?.text ?? "" }); break;
      case "user_message_chunk": s.emit({ type: "user", sessionId, text: u.content?.text ?? "" }); break;
      case "tool_call":
        s.emit({ type: "toolCall", sessionId, id: u.toolCallId, title: u.title, kind: u.kind, input: u.rawInput });
        break;
      case "tool_call_update":
        if (u.status === "completed" || u.status === "failed")
          s.emit({ type: "toolResult", sessionId, id: u.toolCallId, status: u.status,
                   output: (u.content ?? []).map((c: any) => c?.content?.text ?? "").join("") });
        break;
    }
  }

  private async onPermission(p: any) {
    const s = this.sessions.get(p.sessionId);
    const request: PermissionRequest = {
      sessionId: p.sessionId, toolCallId: p.toolCall?.toolCallId, title: p.toolCall?.title,
      kind: p.toolCall?.kind ?? "other", input: p.toolCall?.rawInput ?? {},
    };
    let decision: PermissionDecision = { behavior: "deny" };
    let by: "callback" | "none" = "none";
    if (s?.options.canUseTool) {
      decision = await s.options.canUseTool(request);
      by = "callback";
    }
    s?.emit({ type: "permission", sessionId: p.sessionId, request, decision, by });
    const optionId = decision.behavior === "allow"
      ? (decision.always ? "allow-always" : "allow-once")
      : (decision.always ? "reject-always" : "reject-once");
    return { outcome: { outcome: "selected", optionId } };
  }

  private async onToolCall(p: any) {
    const tool = this.sessions.get(p.sessionId)?.tools.get(p.tool);
    if (!tool) throw new RpcError(-32601, `no tool ${p.tool} in session ${p.sessionId}`);
    const content = await tool.handler(p.args ?? {}, { sessionId: p.sessionId, toolCallId: p.toolCallId });
    return { content: content === undefined ? null : (content as any) };
  }

  private async onHook(p: any) {
    const hook = this.hooks.get(p.id);
    if (!hook) return {};
    const d: any = await hook.handler(p.payload, { sessionId: p.sessionId, event: p.event });
    if (!d) return {};
    if ("block" in d) return { decision: "block", reason: String(d.block) };
    if ("replace" in d) return { decision: "replace", replacement: d.replace };
    if ("modifyArgs" in d) return { decision: "modifyArgs", args: d.modifyArgs };
    return {};
  }
}
