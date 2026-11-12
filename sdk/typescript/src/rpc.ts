// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

// JSON-RPC 2.0 over a child process's stdio, one object per line: what
// `byj acp` speaks (RFC-053). The SDK carries its own rather than depending
// on the ACP libraries, whose extension API moved twice in a month (RFC-053
// Decisions); conformance is checked by dev/acp-interop.sh.
import type { ChildProcessWithoutNullStreams } from "node:child_process";

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

export class RpcError extends Error {
  constructor(public code: number, message: string, public data?: unknown) {
    super(message);
    this.name = "RpcError";
  }
}

type Handler = (params: any) => unknown | Promise<unknown>;

export class Connection {
  private nextId = 0;
  private pending = new Map<number | string, { resolve: (v: any) => void; reject: (e: Error) => void }>();
  private requestHandlers = new Map<string, Handler>();
  private notificationHandlers = new Map<string, (params: any) => void>();
  private buffer = "";
  private closedError: Error | null = null;
  readonly closed: Promise<number | null>;

  constructor(private child: ChildProcessWithoutNullStreams) {
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => this.onData(chunk));
    this.closed = new Promise((resolve) => {
      child.on("exit", (code) => {
        this.fail(new Error(`byj exited (${code})`));
        resolve(code);
      });
      child.on("error", (e) => {
        this.fail(e);
        resolve(null);
      });
    });
  }

  onRequest(method: string, h: Handler) { this.requestHandlers.set(method, h); }
  onNotification(method: string, h: (params: any) => void) { this.notificationHandlers.set(method, h); }

  request<T = any>(method: string, params: unknown): Promise<T> {
    if (this.closedError) return Promise.reject(this.closedError);
    const id = ++this.nextId;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.write({ jsonrpc: "2.0", id, method, params });
    });
  }

  notify(method: string, params: unknown) {
    if (!this.closedError) this.write({ jsonrpc: "2.0", method, params });
  }

  /** Close byj's stdin: byj ends every session and exits. */
  end() { this.child.stdin.end(); }

  private write(msg: unknown) { this.child.stdin.write(JSON.stringify(msg) + "\n"); }

  private fail(e: Error) {
    if (this.closedError) return;
    this.closedError = e;
    for (const [, p] of this.pending) p.reject(e);
    this.pending.clear();
  }

  private onData(chunk: string) {
    this.buffer += chunk;
    let nl: number;
    while ((nl = this.buffer.indexOf("\n")) >= 0) {
      const line = this.buffer.slice(0, nl);
      this.buffer = this.buffer.slice(nl + 1);
      if (line.trim()) this.onLine(line);
    }
  }

  private onLine(line: string) {
    let msg: any;
    try { msg = JSON.parse(line); } catch { return; }
    if (msg.id !== undefined && msg.method === undefined) {
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      if (msg.error) p.reject(new RpcError(msg.error.code, msg.error.message, msg.error.data));
      else p.resolve(msg.result);
    } else if (msg.id !== undefined) {
      void this.answer(msg.id, msg.method, msg.params);
    } else if (msg.method) {
      const h = this.notificationHandlers.get(msg.method);
      if (h) { try { h(msg.params); } catch { /* a viewer's failure is not the session's */ } }
    }
  }

  private async answer(id: number | string, method: string, params: unknown) {
    const h = this.requestHandlers.get(method);
    if (!h) {
      this.write({ jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
      return;
    }
    try {
      const result = await h(params);
      this.write({ jsonrpc: "2.0", id, result: result ?? {} });
    } catch (e: any) {
      const code = e instanceof RpcError ? e.code : -32000;
      this.write({ jsonrpc: "2.0", id, error: { code, message: String(e?.message ?? e) } });
    }
  }
}
