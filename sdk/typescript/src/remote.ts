// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

// A shared agent, called from an application (RFC-060 D11): an agent share's
// endpoint on Share, spoken to over A2A v0.3 with the token its owner made
// for the application. It shares nothing with the stdio client but its
// way of answering: `ask` for the answer, `stream` for the working text as
// it grows and then the answer, `cancel` for the turn running. A handle
// keeps its conversation's context, so each message continues the last.

/** Why a shared agent did not answer, as the relay and the share say it. */
export type SharedAgentErrorKind =
  | "unauthorized" // no token, or one not this share's
  | "refused" // the application or person was removed, or may not ask
  | "owner-only" // the agent runs on its owner's subscription, which answers them alone
  | "not-live" // its machine is off, or its share ended
  | "cap" // the share's caps or its owner's monthly cap are reached
  | "busy" // a turn is running in this conversation, or the machine holds all it can
  | "failed"; // the turn failed, or the reply could not be read

export class SharedAgentError extends Error {
  constructor(readonly kind: SharedAgentErrorKind, message: string, readonly status?: number, readonly code?: number) {
    super(message);
    this.name = "SharedAgentError";
  }
}

/** An agent share's card: who it is, its skills, and what it says of itself past A2A. */
export interface SharedAgentCard {
  name: string;
  description?: string;
  url: string;
  protocolVersion?: string;
  skills?: { id: string; name?: string; description?: string }[];
  capabilities?: { streaming?: boolean; extensions?: { uri: string; description?: string; required?: boolean }[] };
}

/**
 * On a card while its share keeps the questions its callers ask and the
 * answers it gives, for its owner to read (byj-1wv.119).
 */
export const KEEPS_QUESTIONS = "https://share.brainyard.io/a2a/extensions/keeps-questions/v1";

export interface SharedAnswer {
  answer: string;
  taskId: string;
  contextId?: string;
  /** What the turn cost, in USD, when the agent said. */
  costUsd?: number;
}

export type SharedStreamEvent =
  | { type: "working"; text: string }
  | ({ type: "answer" } & SharedAnswer);

export interface ConnectSharedOptions {
  /** A fetch to use instead of the global one (a test's, a proxy's). */
  fetch?: typeof fetch;
  /** A skill to ask, when the share has more than one. */
  skill?: string;
}

const COST = "ai.brainyard/cost-usd";

function classify(message: string, status?: number, code?: number): SharedAgentErrorKind {
  const m = message.toLowerCase();
  if (status === 401) return "unauthorized";
  if (m.includes("subscription")) return "owner-only";
  if (m.includes("not live") || m.includes("not connected") || m.includes("share ended")) return "not-live";
  if (m.includes("cap") && m.includes("reached")) return "cap";
  if (m.includes("busy")) return "busy";
  if (status === 403) return "refused";
  if (status === 404) return "not-live";
  return "failed";
}

function textOf(parts: { kind?: string; text?: string }[] | undefined): string {
  return (parts ?? []).filter((p) => p.kind === "text" || p.text !== undefined).map((p) => p.text ?? "").join("");
}

/** The answer a completed task carries: its status message, else its artifacts. */
function answerOf(task: any): SharedAnswer {
  const fromStatus = textOf(task?.status?.message?.parts);
  const fromArtifacts = (task?.artifacts ?? []).map((a: any) => textOf(a.parts)).filter(Boolean).join("\n");
  const cost = task?.metadata?.[COST];
  return {
    answer: fromStatus || fromArtifacts,
    taskId: task?.id ?? "",
    ...(task?.contextId && { contextId: task.contextId }),
    ...(typeof cost === "number" && { costUsd: cost }),
  };
}

export class SharedAgent {
  private contextId?: string;
  private running?: string;
  private n = 0;

  constructor(readonly card: SharedAgentCard, private readonly token: string, private readonly opts: ConnectSharedOptions = {}) {}

  /**
   * Whether the share keeps what its callers ask and its answers, for its
   * owner to read — as its card said when it was read: an application
   * asking for people who never see the share's page tells them.
   */
  get keepsQuestions(): boolean {
    return (this.card.capabilities?.extensions ?? []).some((e) => e.uri === KEEPS_QUESTIONS);
  }

  /** The conversation's context, once the agent answered once. */
  get context(): string | undefined {
    return this.contextId;
  }

  private message(text: string) {
    const prefix = this.opts.skill ? `[skill: ${this.opts.skill}]\n` : "";
    return {
      messageId: `m-${Date.now()}-${++this.n}`,
      role: "user",
      kind: "message",
      parts: [{ kind: "text", text: prefix + text }],
      ...(this.contextId && { contextId: this.contextId }),
    };
  }

  private async post(method: string, params: unknown, accept: string): Promise<Response> {
    const f = this.opts.fetch ?? fetch;
    const res = await f(this.card.url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: accept, Authorization: `Bearer ${this.token}` },
      body: JSON.stringify({ jsonrpc: "2.0", id: ++this.n, method, params }),
    });
    if (!res.ok) {
      let message = res.statusText;
      try {
        const b: any = await res.json();
        message = b?.error?.message ?? b?.error ?? message;
      } catch {}
      throw new SharedAgentError(classify(String(message), res.status), String(message), res.status);
    }
    return res;
  }

  private rpcError(e: { code?: number; message?: string }): SharedAgentError {
    const message = e.message ?? "the agent did not answer";
    return new SharedAgentError(classify(message, undefined, e.code), message, undefined, e.code);
  }

  private keep(task: any) {
    if (task?.contextId) this.contextId = task.contextId;
  }

  /** Ask, and wait for the answer. */
  async ask(text: string): Promise<SharedAnswer> {
    const res = await this.post("message/send", { message: this.message(text), configuration: { blocking: true, acceptedOutputModes: ["text/plain"] } }, "application/json");
    const body: any = await res.json();
    if (body.error) throw this.rpcError(body.error);
    const task = body.result;
    this.keep(task);
    if (task?.status?.state && task.status.state !== "completed") {
      throw new SharedAgentError("failed", `the task ended ${task.status.state}: ${answerOf(task).answer}`);
    }
    return answerOf(task);
  }

  /** Ask, and read the working text as it grows, then the answer. */
  async *stream(text: string): AsyncGenerator<SharedStreamEvent> {
    const res = await this.post("message/stream", { message: this.message(text), configuration: { acceptedOutputModes: ["text/plain"] } }, "text/event-stream");
    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    let last = "";
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let i: number;
        while ((i = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, i).trim();
          buf = buf.slice(i + 1);
          if (!line.startsWith("data:")) continue;
          let frame: any;
          try {
            frame = JSON.parse(line.slice(5).trim());
          } catch {
            continue;
          }
          if (frame.error) throw this.rpcError(frame.error);
          const r = frame.result ?? frame;
          if (r.kind === "task") {
            this.keep(r);
            this.running = r.id;
          } else if (r.kind === "status-update") {
            if (r.contextId) this.contextId = r.contextId;
            const t = textOf(r.status?.message?.parts);
            if (r.final) {
              this.running = undefined;
              if (r.status?.state !== "completed") throw new SharedAgentError("failed", `the task ended ${r.status?.state}: ${t}`);
              const cost = r.metadata?.[COST];
              yield { type: "answer", answer: t, taskId: r.taskId, ...(this.contextId && { contextId: this.contextId }), ...(typeof cost === "number" && { costUsd: cost }) };
              return;
            }
            if (t && t !== last) {
              last = t;
              yield { type: "working", text: t };
            }
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
    throw new SharedAgentError("failed", "the stream ended before the answer");
  }

  /** Stop the turn running, if one is. */
  async cancel(): Promise<void> {
    if (!this.running) return;
    const res = await this.post("tasks/cancel", { id: this.running }, "application/json");
    const body: any = await res.json();
    if (body.error) throw this.rpcError(body.error);
    this.running = undefined;
  }
}

/**
 * A shared agent at `url` — an agent share's endpoint, `https://…/a2a/shr_…` —
 * with the token its owner made for this application: its card read, a
 * handle to ask it with.
 */
export async function connectShared(url: string, token: string, opts: ConnectSharedOptions = {}): Promise<SharedAgent> {
  const f = opts.fetch ?? fetch;
  const base = url.replace(/\/+$/, "");
  const res = await f(`${base}/.well-known/agent-card.json`, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    let message = res.statusText;
    try {
      const b: any = await res.json();
      message = b?.error ?? message;
    } catch {}
    throw new SharedAgentError(classify(String(message), res.status), String(message), res.status);
  }
  const card = (await res.json()) as SharedAgentCard;
  return new SharedAgent({ ...card, url: card.url || base }, token, opts);
}
