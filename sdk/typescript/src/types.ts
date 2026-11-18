// Copyright (c) 2026 Grumatic, Inc. and byj contributors
// SPDX-License-Identifier: MIT

// What an application hands the SDK and what it gets back (RFC-053 §5).
// Names follow byj's where byj names the thing: hook events, permission
// modes, a turn's result.

/** A JSON Schema, in the subset byj reads (RFC-053 "Client tools"). */
export type JsonSchema = {
  type?: "object" | "string" | "integer" | "number" | "boolean" | "array" | "null";
  properties?: Record<string, JsonSchema>;
  required?: string[];
  items?: JsonSchema;
  enum?: (string | number | boolean)[];
  description?: string;
  title?: string;
  default?: unknown;
};

export type PermissionMode = "auto" | "ask-each-time" | "auto-approve" | "deny-by-default";

/** A tool that runs in the application, called by byj's agent. */
export interface Tool {
  name: string;
  description: string;
  inputSchema: JsonSchema;
  /** The effects byj's gate sees (`sdk/call` when none): `fs/write`, `process`, … */
  effects?: string[];
  handler: (args: any, call: { sessionId: string; toolCallId: string }) => unknown | Promise<unknown>;
}

/** byj's hook points (RFC-011), by brainyard's names. */
export type HookEvent =
  | "agent.session/created" | "agent.session/resumed" | "agent.session/closed"
  | "agent.ask/pre" | "agent.ask/post"
  | "agent.iteration/pre" | "agent.iteration/post"
  | "agent.dspy-action/pre" | "agent.dspy-action/post"
  | "agent.tool-calls/pre" | "agent.tool-calls/post"
  | "agent.tool-use/pre" | "agent.tool-use/post" | "agent.tool-use/rejected";

/** What a hook on a gated event (`agent.tool-use/pre`) may decide; nothing allows. */
export type HookDecision =
  | { block: string }
  | { replace: unknown }
  | { modifyArgs: Record<string, unknown> }
  | void | undefined | null;

export interface Hook {
  event: HookEvent;
  /** A glob on the call's tool name, for the tool-use events. */
  tool?: string;
  handler: (payload: any, ctx: { sessionId: string; event: HookEvent }) => HookDecision | Promise<HookDecision>;
  /** How long byj waits for the answer (30 s); past it the hook allows, unless failClosed. */
  timeoutMs?: number;
  failClosed?: boolean;
}

/** A permission prompt: what byj's gate asks a person. */
export interface PermissionRequest {
  sessionId: string;
  toolCallId: string;
  /** The call as a line: `bash ls -la`, `read-file notes.md`. */
  title: string;
  kind: "read" | "edit" | "execute" | "fetch" | "other";
  input: Record<string, unknown>;
}

export type PermissionDecision =
  | { behavior: "allow"; always?: boolean }
  | { behavior: "deny"; always?: boolean };

export interface McpServer {
  name: string;
  command: string;
  args?: string[];
  env?: Record<string, string>;
}

export interface SessionOptions {
  /** The project: its .brainyard/ is the agent's behaviour. */
  cwd: string;
  /** An agent spec in the project's (or the home's) .brainyard/agents. */
  agent?: string;
  provider?: string;
  model?: string;
  /** The session loop's tree, as `byj run --loop`. */
  loop?: string;
  /** Config overrides (RFC-013), byj's key names: `{"max-iterations": 8}`. */
  config?: Record<string, unknown>;
  permissionMode?: PermissionMode;
  /** Answers byj's permission prompts. Without it, a prompt is refused. */
  canUseTool?: (req: PermissionRequest) => PermissionDecision | Promise<PermissionDecision>;
  tools?: Tool[];
  hooks?: Hook[];
  /** byj's own events to receive as `event` messages: "all" or names (RFC-026). */
  events?: "all" | string[];
  mcpServers?: McpServer[];
  label?: string;
  /** Allow slash commands and `:<tool>` calls in a prompt. */
  commands?: boolean;
}

export interface ClientOptions {
  /** The byj to run: `byjPath`, else $BYJ_BIN, else `byj` on PATH. */
  byjPath?: string;
  /** Arguments before `acp`: `["-m", "brainyard.main"]` with byjPath `jolt`. */
  byjArgs?: string[];
  /** The directory byj runs in (a checkout, with `jolt`); the session's cwd is its own. */
  processCwd?: string;
  /** byj's environment, over this process's. */
  env?: Record<string, string>;
  /** byj's stderr, a line at a time; dropped when absent. */
  stderr?: (line: string) => void;
}

export interface Usage {
  "input-tokens"?: number;
  "output-tokens"?: number;
  "llm-calls"?: number;
  [k: string]: unknown;
}

/** The last message of a prompt. */
export interface ResultMessage {
  type: "result";
  sessionId: string;
  stopReason: "end_turn" | "cancelled" | "max_turn_requests" | "refusal" | string;
  /** The agent's answer, whole. */
  answer: string;
  terminatedBy?: string;
  iterations?: number;
  goalAchieved?: boolean;
  nextUserPrompt?: string | null;
  elapsedMs?: number;
  usage?: Usage;
  /** Dollars, when byj could price the turn. */
  costUsd?: number;
  isError: boolean;
  error?: string;
  /** With `outputSchema`: the answer as that schema's data. */
  structuredOutput?: unknown;
  /** With `outputSchema`: why the answer could not be given as data. */
  structuredOutputError?: string;
  /** The turn it ran, as `byj turns list` numbers it. */
  turn?: number;
  /** How many files the turn changed in the project's work tree. */
  filesChanged?: number;
  /** What a rewind of the turn goes back to; absent when byj keeps none. */
  checkpoint?: Checkpoint;
}

/** A turn as byj keeps it to take back (RFC-069): its ref, and the work tree's commits as it began and ended. */
export interface Checkpoint {
  ref: string;
  before: string;
  after: string;
  filesChanged: number;
}

/** A turn byj keeps, as `byj turns list` shows it. */
export interface KeptTurn {
  turn: number;
  ref: string;
  before: string;
  after: string;
  /** The paths it changed. */
  files: string[];
  question: string;
  /** When it ended, in epoch seconds. */
  endedAt?: number;
}

/** A turn's change, as git's diff. */
export interface TurnDiff {
  turn: number;
  ref: string;
  before: string;
  after: string;
  files: string[];
  diff: string;
}

/** What a rewind, an undone turn or an undone rewind did to the work tree. */
export interface RewindResult {
  kind?: "rewind" | "undo-turn" | "undo-rewind" | string;
  turn?: number;
  end?: boolean;
  /** The paths it changed on disk; none when they were already so. */
  files: string[];
  /** Files left with conflict markers by an undone turn. */
  conflicts: string[];
  /** The ref what it replaced is kept as: `undoRewind` puts it back. */
  saved?: string;
  /** What byj said, when nothing changed. */
  message?: string;
}

/** A fork cut before a turn: what it did. */
export interface ForkOutcome {
  from: string;
  beforeTurn?: number;
  /** The files put back in the work tree, as the turn began. */
  files?: string[];
  saved?: string;
  /** Why the files were not put back, when they were not. */
  error?: string;
  /** The worktree it was made in, when one was asked for (`forkSession`'s `worktree`). */
  worktree?: Worktree;
}

/** A session's worktree of its own, made for it: the project's work tree is left as it was. */
export interface Worktree {
  /** The worktree's top. */
  path: string;
  /** The session's project in it, where it is loaded and listed from. */
  cwd: string;
  /** Its new branch, from `base`. */
  branch: string;
  /** The commit it was added at: HEAD then. */
  base: string;
  /** A fork's: the files that differ from `base`, as the turn began, left uncommitted. */
  files?: string[];
  /** A new session's: whether its branch was made for it, or an existing one checked out. */
  created?: boolean;
}

/** `forkSession`'s options. */
export interface ForkOptions {
  /** Its conversation cut before this turn, the files as it began. */
  beforeTurn?: number;
  /** The project's files left as they are (not with `worktree`). */
  keepFiles?: boolean;
  /** Made in a new git worktree, and worked in there: `true` for the defaults. */
  worktree?: boolean | WorktreeOptions;
}

/** A git worktree a session is opened in, its own: where it goes and what it checks out. */
export interface WorktreeOptions {
  /**
   * Its branch: `byj/<id>` (a fork's `byj/fork-<id>`) when not named. A new
   * session's names an existing one to check it out as it is, when no `from`
   * is given.
   */
  branch?: string;
  /** Its directory, relative to the project; `.brainyard/worktrees/<branch>` when not named. */
  path?: string;
  /** The ref a new branch starts from (a branch, tag or commit); HEAD when not named. */
  from?: string;
}

/** `newSession`'s options. */
export interface NewSessionOptions {
  /** The session opened in a new git worktree of its own, a clean checkout: `true` for the defaults. */
  worktree?: boolean | WorktreeOptions;
}

export type Message =
  | { type: "text"; sessionId: string; text: string }
  | { type: "thought"; sessionId: string; text: string }
  /** What the session said beside its answer while it answered a prompt — waiting for an MCP server, the servers changed, a command's words — never part of the answer. */
  | { type: "notice"; sessionId: string; kind: "info" | "error"; text: string }
  | { type: "toolCall"; sessionId: string; id: string; title: string; kind: string; input: unknown }
  | { type: "toolResult"; sessionId: string; id: string; status: "completed" | "failed"; output: string }
  | { type: "permission"; sessionId: string; request: PermissionRequest; decision: PermissionDecision; by: "callback" | "none" }
  | { type: "event"; sessionId: string; event: string; payload: unknown }
  | { type: "user"; sessionId: string; text: string }
  | ResultMessage;

export interface PredictSignatureField {
  name: string;
  description?: string;
  /** JSON Schema of the field; a string when absent. */
  schema?: JsonSchema;
}

export interface PredictOptions {
  /** A `user/` predictor in the project's or home's .brainyard/predictors … */
  predictor?: string;
  /** … or a signature given here. */
  signature?: {
    instructions: string;
    inputs: PredictSignatureField[];
    outputs: PredictSignatureField[];
    strategy?: "predict" | "cot";
  };
  inputs: Record<string, unknown>;
  cwd?: string;
  provider?: string;
  model?: string;
  config?: Record<string, unknown>;
}

export interface PredictResult {
  outputs: Record<string, unknown>;
  reasoning?: string;
  usage?: Usage;
  costUsd?: number;
  predictorId: string;
  paramsSource?: string;
}

/** A session of the project that listens on its channel (RFC-031). */
export interface LiveSession {
  sessionId: string;
  live: boolean;
  label?: string | null;
  lastQuestion?: string | null;
  pid?: string | null;
  ops?: string[];
}
