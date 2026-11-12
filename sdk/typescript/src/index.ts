// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

// The Brainyard Agent SDK for TypeScript (RFC-053): byj's agent in an
// application. `query` for one prompt; `ByjClient` for sessions that last.
import { ByjClient } from "./client.js";
import type { ClientOptions, JsonSchema, Message, SessionOptions, Tool } from "./types.js";

export { ByjClient, ByjVersionError, Session, PROTOCOL_VERSION, EXTENSIONS, SDK_VERSION } from "./client.js";
export { RpcError } from "./rpc.js";
export { connectShared, SharedAgent, SharedAgentError, KEEPS_QUESTIONS } from "./remote.js";
export type { SharedAgentCard, SharedAnswer, SharedStreamEvent, SharedAgentErrorKind, ConnectSharedOptions } from "./remote.js";
export type * from "./types.js";

/** A tool that runs in the application, for `tools`. */
export function tool<A = any>(
  name: string,
  description: string,
  inputSchema: JsonSchema,
  handler: (args: A, call: { sessionId: string; toolCallId: string }) => unknown | Promise<unknown>,
  opts: { effects?: string[] } = {},
): Tool {
  return { name, description, inputSchema, handler, ...(opts.effects && { effects: opts.effects }) };
}

/**
 * One prompt in a new session: byj is started, the session opened, the
 * prompt's messages yielded — the last a `result` — and byj ended, also when
 * the caller stops reading early.
 */
export async function* query({ prompt, options }: {
  prompt: string;
  /** `outputSchema`: the answer also given back as this JSON Schema's data (`result.structuredOutput`). */
  options: SessionOptions & ClientOptions & { outputSchema?: JsonSchema };
}): AsyncGenerator<Message> {
  const client = await ByjClient.start(options);
  try {
    const session = await client.newSession(options);
    yield* session.prompt(prompt, { outputSchema: options.outputSchema });
  } finally {
    await client.close();
  }
}
