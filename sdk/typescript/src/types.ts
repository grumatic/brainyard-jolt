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
}

export type Message =
  | { type: "text"; sessionId: string; text: string }
  | { type: "thought"; sessionId: string; text: string }
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
