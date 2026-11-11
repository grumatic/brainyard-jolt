# RFC-006 — The provider layer

**Status:** partial.

## Purpose

Specifies how the port talks to a model: one entry point, one retry loop, a
multimethod per wire format, and brainyard's `claude -p` invocation reproduced
exactly. brainyard sources: `clj-llm/core/{llm,providers,claude_code}.clj`.

## Scope

**This layer decides** how a call is made and how its failures are handled,
and never what the call says.

**It must not know** what a turn, a predictor or a tool is. Messages in, text
out.

**A proposed second call shape widens that** (`byj-gly.115`): a *decision* call
is state and typed questions in, calibrated probabilities out — no messages and
no text. `chat*` already dispatches on `(:format lm)`, so the mechanism takes
`:lev` as one more format; what moves is this contract in prose, and it is
written here rather than left to be discovered. What the decision path reuses is
the part worth reusing: `with-retry`, the failure classification below,
`create-lm`'s credentials-through-`env`, and — the reason it is a provider at
all — `resolve-lm`, so `"lev/english"` and `"lev/minicpm5"` are ordinary labels
and lev's fast-encoder-to-thinker escalation needs no new concept (RFC-005).

**It hands** `{:content :usage {:input-tokens :output-tokens}}` (claude-code and
codex add `:source`, and `:attempted-tool-calls` when the model called tools natively) to
`predictor/run` (RFC-005). Those are what the CLI rejected, reported as data; the
provider does not decide what they mean.

## Model

```
predictor/run
   │  (llm/chat lm messages {:json-schema schema})
   ▼
with-retry ── fx/retry-backoff over fx/task-of: ex-data :retryable?, 2^n backoff with
   │          jitter (base 1000ms, 3 retries), cancellable sleep; never retries a cancellation
   │
chat* dispatch on (:format lm)
   ├─ :openai       POST {base}/chat/completions   Bearer; response_format json_schema
   ├─ :anthropic    POST {base}/messages           x-api-key XOR Bearer; anthropic-version
   ├─ :claude-code  claude -p subprocess           (below)
   ├─ :codex        codex exec subprocess          (below, RFC-049)
   ├─ :bedrock      aws CLI subprocess             (below)
   ├─ :mock         :responses — queued strings/fns, or one fn; none: llm/mock-echo;
   │                -m script:FILE — FILE's EDN replies in turn, then the echo (launch/mock-lm, RFC-053)
   └─ :lev          POST {base}/v1/systemone — state + questions → probabilities
                    (proposed, byj-gly.115: a decision call, not a chat)
```

### Which model a run asks

`main/run-lm-choice`: `-p` (with `-m`, or that provider's default model below)
wins. Otherwise `:default-lm`, a `provider/model` label in config
(`config.edn`, `BY_DEFAULT_LM`), with `-m` replacing its model when given.
Unset, it is `claude-code/sonnet`, which needs no API key of its own (haiku
until byj-gly.366; recaps, titles and command rephrasing run on its
provider's light model instead, RFC-005 "The light tier"). A label
that is not `provider/model` is reported and the fallback is used. An API key
in the environment does not choose the model: that had made
`openai/gpt-4.1-mini` every session's model for anyone with an OpenAI key in a
`.env` (divergence `the-default-model-is-config`). A subagent, a team member
and a params file choose theirs as before (RFC-018, RFC-032, RFC-021).

### LM config

`(create-lm provider env overrides)` merges `{:temperature 0.0 :max-tokens 8192
:timeout-ms 300000}`, the registry entry, credentials read through `env` (a fn
of a variable name — the CLI's is real env first, then `.env`, then
`~/.brainyard/.env`), and explicit overrides.

| provider | format | credentials | default model |
|---|---|---|---|
| `:openai` | openai | `OPENAI_API_KEY`, `OPENAI_BASE_URL` | gpt-4.1-mini |
| `:freellm` | openai | `FREELLM_API_KEY`, `FREELLM_BASE_URL` | gpt-4.1-mini |
| `:openrouter` | openai | `OPENROUTER_API_KEY` | anthropic/claude-sonnet-4.5 |
| `:ollama` | openai | — (localhost:11434) | gemma4:12b |
| `:anthropic` | anthropic | `ANTHROPIC_API_KEY`, else `ANTHROPIC_AUTH_TOKEN`; `ANTHROPIC_BASE_URL` | claude-sonnet-5 |
| `:claude-code` | claude-code | the CLI's own login | opus |
| `:codex` | codex | the codex CLI's own sign-in (`$CODEX_HOME`) | Codex's own (gpt-6-luna on 0.163) |
| `:bedrock` | bedrock | the aws CLI's profile/SSO | us.anthropic.claude-haiku-4-5-20251001-v1:0 |
| `:mock` | mock | — | mock |
| `:lev` | lev | `LEV_API_KEY` (only when the server sets one), `LEV_BASE_URL` | english |

A `*_BASE_URL` given as an origin alone (`ANTHROPIC_BASE_URL=http://litellm:4000`,
as Claude Code takes it and a model gateway is given) inherits the provider's
own path (`/v1`), and one with a path is kept as it is
(`llm/merge-base-url-path`, brainyard's `providers/merge-base-url-path`;
`wire-test/a-gateway-given-as-its-origin-is-called-at-the-apis-path`,
`byj-gly.446`). Before, an origin was called at `/messages` and 404'd.

**The models worth offering are a resource.** `llm/curated-models` reads
`resources/brainyard/models.edn` — brainyard's curated `model-catalog` entries
(`get-popular-models`), in rank order, for the providers above. It is what
`/model` lists and completes (RFC-004); it limits nothing, since any id a
provider serves can be named. `llm/model-label` turns what `/model` was given
into a `provider/model` label: a bare id the list knows is its first entry's
(`sonnet` → `claude-code/sonnet`), checked before the legacy `provider:model`
form, which would read `gemma4:12b` as a provider named gemma4.

## Protocol

### Failure classification

| failure | retryable? | fatal? |
|---|---|---|
| network error | yes | — |
| 408, 429, 5xx | yes | — |
| 429 that is a usage cap (`insufficient_quota`, "no credits", billing) | no | **yes** — retrying spends time against a wall (samizdat `usage-cap?`, provenance P-4) |
| other 4xx | no | — |
| claude CLI missing | no | yes |
| claude CLI error with nothing recoverable | no | — |
| lev 503 — the model is configured but unavailable (no prepared data, no GGUF, no `lev_llm` native) | no | — — it is the signal to **degrade**, see below |
| lev 422 — the request body is wrong (unknown type, criteria that do not fit the head) | no | — — ours to fix; the error names the rejected question |
| lev 503 because CONTENT ROUTING chose a checkpoint nobody prepared | no | — — the request must name its model; see below |

A fatal error aborts the turn on the first attempt (RFC-003); a non-fatal
failure counts toward the loop's three. A **cancellation** is neither: an HTTP
read interrupted by a cancelled turn is rethrown as itself, not wrapped as a
retryable network error, and ends the call.

**A decision request names its checkpoint.** lev routes by content when the body
omits `model`, detecting script and language before the forward pass. That is
right for user text and wrong for ours: a shell command is not English prose, and
during `byj-gly.113` the router sent `chmod -R 777 /usr/local` to the
`multilingual` checkpoint, which was not prepared, and the call died with a 503.
The `:lev` format always sends a model name; content routing is not a fallback
this layer relies on.

**A decision engine that is not there must cost nothing.** lev is optional by
construction — its weights are ~800 MB off the Hub and its natives are compiled
C — so a missing server, a 503, or a connection refused degrades to the next
model in `resolve-lm`'s order and reports `:predictor/lm-unresolved`, exactly as
an unresolvable `:tier` already does (RFC-005). A fast path that vanishes must
never cost a turn that had a working LLM.

### The claude-code invocation

Reproduces brainyard's `build-cli-args` and process setup, because what Opus
accepts depends on it (provenance P-6):

```
claude -p --no-session-persistence --output-format json
       --tools "" --setting-sources "" --strict-mcp-config --disable-slash-commands
       --max-turns 4                      # >1: the StructuredOutput call comes on turn 2
       --model <model>
       --system-prompt <system + structured-output-directive>
       --json-schema <reply schema>
stdin: the user message          env: CLAUDECODE removed
cwd: the session's project (:dir)
```

**It runs in the session's project.** Claude Code tells the model its own
working directory and whether it is a git repository, even under
`--system-prompt`. Started in this process's cwd, `byj run -C DIR` launched from
another repository had the model see two working directories, and it read the
launching repository as the project (byj-gly.338). The agent passes its
`:project-dir` (else `:working-dir`) as the predictor's `:dir`, which
`predictor/run` puts on whichever LM the call resolves to, and the CLI starts
there; a `:dir` that is not a directory is ignored.

It runs as `fx/process-task` under `fx/timeout`: the timeout or a cancelled turn
kills the CLI's process tree (RFC-009 rule 1). `:cli-command` in the LM config
replaces the `claude` executable (tests stand a script in).

The reply is taken, most reliable first: the result event's
`:structured_output` → a `StructuredOutput` `tool_use` block → the result string
→ assistant text. **An errored result (`is_error`, e.g. `stop_reason "refusal"`)
is never salvaged from its assistant text** — that text is the error, and reading
it as a reply reported refusals as "plain text with no JSON object" (provenance
P-7). The CLI's stdout may be one object, an event array, or NDJSON
(provenance P-3).

**Native tool calls are reported.** Inside the CLI's own agent loop a model may
call a tool named in our prompt as a native `tool_use`; the CLI, run with
`--tools ""`, answers "No such tool available", and the model may then reply
that the tool does not exist (provenance P-12). `llm/rejected-tool-calls`
returns those attempts as `[{:tool-name :tool-args}]` and the result carries
them as `:attempted-tool-calls`; the ReAct loop decides what to do (RFC-003).

The structured-output directive is brainyard's with two changes: no code-channel
sentence, and "You have NO tools" becomes "You can call NO tool directly except
StructuredOutput … The tools named in the prompt DO exist: they run when you list
them in `tool-calls`" — haiku quoted brainyard's wording as the reason a listed
tool was unavailable.

**No flag stops a native call, and rewording did not either** (byj-gly.353,
2026-10-15). byj already passes everything that narrows the CLI —
`--tools ""`, `--strict-mcp-config` with no config, `--setting-sources ""`,
`--json-schema` — and the model still names a prompt-listed tool in a
`tool_use` now and then: 8 of 214 tool batches across the eight newest
byj-sim projects were recovered native calls. A rewording was measured by
replaying the 7 recorded requests that had produced them
(`dev/brainyard/dev/native_call_spike.clj`), 3 times each as recorded and 3
with the directive saying the listed tools are names for `tool-calls`, not
tools to call, and that a direct call's refusal does not mean a tool is
missing, the tools zone's first line saying the same, and /init's usage
saying "put it in your `tool-calls`": native calls in 12 of 21 replays as
recorded, 14 of 21 reworded (27 calls, 24), and two reworded replays with no
structured output against none. Not shipped. The recovery (RFC-003) is what
keeps a native call harmless; the open alternative is an MCP server standing
in for the tools so a native call is accepted rather than refused.

**Over the Messages API there is no native call.** byj's Anthropic and
Bedrock bodies (`anthropic-body`, `bedrock-body`) carry no `tools` field, so
the model has no tool to name in a `tool_use` and writes the reply as text.
The same 7 requests, less the claude-code directive, sent 3 times each to
Bedrock's `global.anthropic.claude-haiku-5-5` (`native-call-spike/measure-
bedrock`, 2026-10-15): no `tool_use` block and no tool call written as text in
21; 20 parsed as a reply with `tool-calls`, naming the tools the CLI had
refused (`agent$init`, `bash`, `grep`, `write-file`…); one, a `thinking` block
then text, did not parse. The native call is the CLI's: its own agent loop
offers the model a tool channel that byj's prompt does not use.

### The codex invocation (`byj-gly.430.9`)

Codex as a model, as `claude -p` is: `codex exec` answers a predictor or a
turn on the person's Codex sign-in, with no API key (RFC-049 decision 8).
Measured on Codex CLI 0.163:

```
codex exec --json --ephemeral --skip-git-repo-check
           --ignore-user-config --ignore-rules        # not the person's config, rules or hooks
           -s read-only --disable shell_tool --disable unified_exec
           -c project_doc_max_bytes=0                 # not the project's AGENTS.md
           [-m <model>] [-c model_reasoning_effort="<effort>"]
           -c developer_instructions="<the system messages>"   # a TOML basic string
           [--output-schema <file>]
           -                                          # the conversation on stdin
```

- **Its reply** is the last `item.completed` `agent_message`'s text; its usage
  is `turn.completed`'s. `input_tokens` already counts `cached_input_tokens`,
  which become `:cache {:read-tokens}`. `output_tokens` counts the reasoning.
  A turn costs about 12–21k input tokens of Codex's own instructions before
  the prompt.
- **No message is a failure**, with what Codex said: `turn.failed`'s message,
  or an `error` event's API body read for its message.
- **`--output-schema` is sent strict.** Its API refuses an optional or open
  property ("'…args.properties' is required for object schemas", measured on
  the ReAct reply). So the schema goes only when it is strict-eligible
  (`json-schema-strict?`). Otherwise it stays where it already is, in the
  system prompt.
- **A ChatGPT sign-in takes only the models its `models_cache.json` lists.**
  On 0.163 those are gpt-6-luna, gpt-5.6-terra and gpt-5.6-luna, and
  `models.edn` offers them. gpt-5.4 is refused "when using Codex with a
  ChatGPT account".
- **Sampling.** Like `claude -p`, it takes no sampling parameters, so
  `create-lm` drops `:temperature` and `:max-tokens` and announces a requested
  one.
- **Price.** `cost` prices it by the `:openai` table, as the API would bill
  the model. gpt-6-luna is not in that table, so its cost is unknown.

### Streaming

`:stream?` makes a reply arrive as it is written, as `:agent.llm/chunk` events.
**Only the claude-code provider can**, and the reason is measured rather than
assumed:

`dev/brainyard/dev/http_stream_spike.clj` runs every route against a server
writing three SSE chunks 600 ms apart, and prints when each read landed:

| route | result |
|---|---|
| `jolt.http-client {:as :stream}` | an `InputStream`, whole body at once — 1,811 ms |
| `java.net.http` `.send` + `ofInputStream` | the same — 1,812 ms |
| `java.net.http` `.sendAsync` + `ofPublisher` | **not implemented** under jolt |
| raw `java.net.Socket`, plaintext | incremental: 3 / 607 / 1,209 ms |
| `javax.net.ssl.SSLSocketFactory` | **not implemented** under jolt |
| a subprocess, raw `.read` | incremental: 22 ms, 1,029 ms |

brainyard streams over SSE (`clj-llm/core/{sse,sse_flow,http_flow}.clj`) and
through the CLI's `stream-json`; only the second is portable here — but not
because its client is missing. brainyard's SSE rides on `java.net.http`
(`sse_publisher.clj` subscribes to the `Flow.Publisher` from
`BodyHandlers/ofPublisher`), and jolt provides enough of `java.net.http` to send
a request and read a body. What it does not provide is `ofPublisher`; the
`ofInputStream` route that exists buffers exactly as `jolt.http-client` does.

The one route that genuinely streams is a socket with HTTP written by hand, and
it cannot be used: `SSLSocketFactory` is unimplemented, so it reaches no
`https://` endpoint, and every provider is one. The `claude` CLI is a
subprocess, which is why it streams (divergence `no-http-streaming`).

Streaming adds `--output-format stream-json --include-partial-messages
--verbose` and reads `content_block_delta` events for their text. Everything
after that is unchanged: the same events are parsed, by the same code, into the
same reply — only the arrival differs. `-v` turns it on, since tracing a turn
means watching it.

**What streams is the structured reply**, JSON and all, because that is what the
model writes. A viewer wants prose, so the fragments are **labelled with the
reply field they belong to** before they leave the agent (`byj-gly.56`):

```clojure
(let [f (llm/field-streamer [:reasoning :answer])]
  (f "{\"reason")              ;=> []
  (f "ing\":\"Let me")          ;=> [{:field :reasoning :text "Let me"}]
  (f " look.\",\"answer\":\"42") ;=> [{:field :reasoning :text " look."}
                               ;    {:field :answer :text "42"}]
```

`llm/partial-string-field` decodes a top-level JSON string **as far as it has
arrived** — escapes included, holding back a trailing lone backslash whose
escape is still incomplete — and `llm/field-streamer` turns that into per-field
deltas across fragment boundaries. A stream that is not JSON at all is labelled
`:raw` and passed through, because a viewer shown nothing is worse off than one
shown unlabelled text.

**Labelling is all the agent does.** *Which* field to show, and when, is the
viewer's decision, because it depends on what the viewer is presenting: the
reasoning is the thing worth watching precisely while the answer is still empty.
`main/tracer` makes that decision for the CLI — it streams reasoning behind a
`💭`, lets the answer take over when it begins, and then does not repeat the
reasoning it already showed when the parsed reply arrives.

Reading the raw `InputStream` is not an aesthetic choice. Both tidier readers
buffer and deliver nothing until the process exits, measured on the same
command: `line-seq` over `io/reader` gave both lines at 2,036 ms, and
`InputStreamReader` at 628 ms. `fx/streaming-process` splits on the byte `\n`
and decodes each line, so a multi-byte character straddling two reads survives.

### Bedrock

`aws bedrock-runtime` as a subprocess, not signed HTTP: jolt has no SigV4, and
the CLI already resolves profiles, SSO and regions (divergence
`bedrock-via-cli`). The model family picks the shape:

| model | command | body | caching |
|---|---|---|---|
| `*anthropic.*` | `invoke-model` | the native Messages body — `anthropic-body` minus `model`, plus `anthropic_version` | `cache_control`, measured working (RFC-015) |
| everything else | `converse` | `{:modelId :system :messages :inferenceConfig}` | none yet (`byj-gly.37`) |

`invoke-model` passes its body through unvalidated, which is what lets the cache
markers reach the model; Converse is schema-checked by the CLI, and the
installed version rejects its `cachePoint` block. Both run as `fx/process-task`
under a timeout, so a cancelled turn kills the CLI (RFC-009). `--region` comes
from config or `AWS_REGION`, `--profile` from `AWS_PROFILE`; a throttling error
is retryable, anything else is not.

Converse's usage is read off what the CLI prints, and an aws CLI older than
Converse's cache fields drops them: GPT-5.6 Sol, which Bedrock caches by
itself, came back as 2 input tokens of a 9,118-token `totalTokens` on
aws-cli 2.25.1, where the raw reply said 9,111 were cache reads
(byj-gly.372). `bedrock-converse-usage` counts what `totalTokens` holds
beyond the named parts as input, priced at the input rate — an upper bound —
and says so as `:unreported-input-tokens`; a current CLI names them. The
Bedrock prices of the Converse models are the AWS Pricing API's, `us-east-1`
on-demand standard; GPT-5.6, listed there only for GovCloud, is priced as
OpenAI prices it.

A failed call says what its error means where that is known
(`resources/brainyard/bedrock-hints.edn`, `bedrock-hint`): Claude Fable 5 and
5.1 answer "data retention mode 'default' is not available" until the
account opts in, per region, to AWS's human review (`aws_review`), which
only its owner can decide (byj-gly.370). A Converse reply with reasoning and
no text is a retryable failure, not prose with no JSON object
(`bedrock-converse-reply`): gpt-oss-120b ended turns that way, and with
byj's think-act prompt answered 2 runs in 4 (byj-gly.371) — its picker entry
says so.

### Newer models take no temperature

Claude Opus 4.7 and later — every 5.x — and GPT-5.6 answer any temperature
with a validation error ("`temperature` is deprecated for this model"), so
every call to one failed while `create-lm`'s default 0.0 was sent
(byj-gly.369). Which models refuse it is data, `resources/brainyard/sampling.edn`
(`:no-temperature`, id patterns, measured on Bedrock 2026-10-17), and every
body — OpenAI's, Anthropic's, Bedrock's native, Nova and Converse — leaves
temperature out for them (`takes-temperature?`). Such a model samples freely
(`samples-freely?`), as `:claude-code` does.

### `claude -p` takes no sampling parameters

`--temperature` and `--max-tokens` are both **"unknown option"** on the current
CLI (verified 2026-09-24), so neither can be conveyed and `claude-cli-args`
sends neither. `create-lm` therefore **removes** them from a `:claude-code` LM
rather than carrying values nothing will send; what a caller asked for
explicitly is kept under `:ignored-params` and announced once per call as
`:llm.cli/params-ignored`. A hint that cannot be honoured degrades and says so,
as an unresolvable `:lm` or `:tier` does (RFC-005).

This is not cosmetic. While the LM map still reported `:temperature 0.0`, every
measurement through the default provider looked reproducible: two runs of one
evaluation arm over the same 26 examples came back 0.731 and 0.962, a swing
larger than any effect the comparison was built to detect (`byj-gly.126`).
`llm/samples-freely?` is the predicate for it, and `brainyard.programs` stamps
an evaluation `:sampling :free` and warns on a single pass — `evaluate` cannot,
because it is handed a program and never learns what is behind it (RFC-021).

brainyard has the same limitation and additionally passes `--max-tokens`, which
this CLI now rejects, so omitting it here is correct rather than a gap. Not a
divergence: the behaviour is the same, only the honesty about it differs.

### Signing in: `/login` and `/logout` (`byj-gly.311`)

This ports brainyard's `/login` (`agent-tui/commands.clj`
`handle-login-command`, `common/auth.clj`,
`docs/design/login-auth-methods.md`): a registry of sign-in targets, each
with one method (`brainyard.auth`).

- **`:cli`, another CLI's own login** (brainyard's `:cli-delegate`). The
  targets are data, `resources/brainyard/auth/targets.edn`:
  - `claude`: `claude auth status --json`, signed in when `loggedIn` is true;
  - `codex`: `codex login status`, by its exit code;
  - `gh`: `gh auth status`;
  - `bedrock`: `aws sts get-caller-identity`, with `--profile` from
    `AWS_PROFILE`.

  Each is asked without a terminal, for 5 s at most. A target not signed in
  has its login run with the terminal handed over, through the front end's
  `:hand-terminal` seam (RFC-027), since each asks in a browser or on the
  terminal: `claude auth login`, `codex login`, `gh auth login`,
  `aws sso login`. Then it is asked again, and the session says where it
  stands.
  - Headless, with no seam, the command is said instead.
  - A target already signed in is said so, and nothing is run.
  - A CLI that is not installed is said how to install, from `/init`'s
    requirements.
- **`:api-key`**: every provider in `llm/providers` with an `:api-key-env`.
  Its status is whether the variable is set, in the environment or a `.env`;
  the value is never read out. Signing in is being told where to put it.
  Nothing writes a key.
- `/login` with no target lists every target and where it stands.
  `/logout <target>` runs the CLI's logout the same way, or says how to remove
  a key.
- brainyard only says which command to run for a CLI's login; running it was
  its own follow-up 4 (divergence `login-runs-the-cli`). Its MCP OAuth
  servers as `/login` targets are not ported: this port's MCP is stdio only
  (RFC-024).

### The subscription's windows: `/usage` and `/doctor` (`byj-gly.324`)

brainyard-jolt's default LM is `claude-code/sonnet`, and agent$claude and the
simulation spend the same Claude Code subscription. When its five-hour or
weekly window is used up, a delegated session stops with nothing to say why.
`brainyard.claude-usage` reads how full each window is. It is ported from
brainyard-playground-apps' Desktop (`claude-usage.ts`).

**The one call to a non-public endpoint** (decided with the person,
2026-10-09):
- It reads Claude Code's own OAuth access token, read-only, in Claude Code's
  own order: `$CLAUDE_CONFIG_DIR/.credentials.json`, then the keychain item
  scoped by a hash of that directory, then the legacy unsuffixed one, with
  3 s for a keychain prompt.
- It sends the token to `api.anthropic.com/api/oauth/usage` only, and never
  writes, logs or refreshes it. The endpoint accepts a locally expired
  token. Writing a rotated refresh token back would corrupt Claude Code's
  credentials, so a 401 says to sign in again.
- It reads the answer defensively. The windows are taken by shape: the
  session and weekly windows under Claude Code's names, every other object
  with a percentage under its model's display name or its key. A null window
  is absent, not zero.
- A snapshot is kept five minutes, and a Retry-After holds even against a
  forced refresh. A failed fetch keeps the last good snapshot and says why.
  With nothing kept, the windows read **unknown**, never 0%.

**Where it shows:**
- `/doctor` gives `init$check`'s report (the tools and the project), what is
  installed — the byj and byj-share that run by name and every other copy on
  PATH, shadowed (RFC-029, `byj-gly.181.13`) — then Claude Code's sign-in:
  the account, the plan, and the days its refresh token has left (under
  seven, it says `/login claude` soon). Then the windows.
- `/usage` adds the windows as bars when `:claude-usage` is `on`, or `auto`
  (the default) on the claude-code provider. A session on another provider
  is not asked, so neither is the keychain or the network.
- **The status line** shows `⚠ Weekly 93%` beside the counts when a window
  of the last snapshot is at 90% or more. It reads the cache only, never the
  network. A turn's end refreshes the snapshot in the background, at most
  once in five minutes. The row is redrawn when the session's counts move,
  so a new reading shows at the next call.

## Invariants

| invariant | enforced by |
|---|---|
| `claude -p` runs in the session's project directory, not this process's cwd. | `agent-test/a-cli-model-runs-in-the-sessions-project` |
| A mock named by label (`mock/m`) answers as `-p mock` does, never failing for want of scripted replies. | `predictor-test/a-mock-named-by-label-answers` |
| `-p mock -m script:FILE` answers with FILE's replies in turn and then echoes: how a test drives another process's turns — `byj acp` from a client (RFC-053) — without a model. | `acp-test/a-prompt-streams-its-turn-and-ends` |
| `codex exec` answers as a model, not an agent: the person's config, rules, AGENTS.md and shell off, nothing persisted, the system prompt as developer_instructions, the conversation on stdin. Its reply is the last agent message, its tokens are reported with cached reads apart, and it is priced as OpenAI's API would bill it. | `codex-provider-test/a-turn-is-answered-by-codex-exec`, `no-model-means-codexs-own-default` |
| A codex reply's schema is sent only when strict-eligible, and a call with no message fails with what Codex said. | `codex-provider-test/a-turn-is-answered-by-codex-exec`, `a-strict-schema-is-the-replys-shape`, `a-reply-with-no-message-fails-with-what-codex-said` |
| `/login`'s targets are the CLIs in `targets.edn` and every API-key provider, found by id or alias. A CLI's status is its own word. A target not signed in has its login run with the terminal handed over, and is asked again after. One signed in is not run again. Headless, the command is said. A missing CLI is said how to install. An API key is said where to put and never read out. | `auth-test/the-targets-are-the-clis-and-the-api-key-providers`, `auth-test/signing-in-runs-the-clis-own-login-with-the-terminal` |
| The subscription's windows are read by shape, a null one left out; the credentials in Claude Code's order, the file before the keychain; a snapshot kept five minutes, a Retry-After honoured even when forced, a failure keeping the last reading and saying why, and with none, unknown — never a number. | `claude-usage-test/the-windows-are-read-by-shape-and-a-null-one-is-absent`, `claude-usage-test/the-credentials-are-read-in-claude-codes-order`, `claude-usage-test/the-snapshot-is-kept-and-the-endpoint-is-not-pressed` |
| `/doctor` gives the tools, the project, the account and its refresh token's days, and the windows; `/usage` adds the windows when asked, and on another provider in auto does not ask. | `session-test/the-subscription-is-shown-by-usage-and-doctor` |
| The status line warns of a window at 90% from the last snapshot only. | `tui-test/a-subscription-window-near-its-limit-is-shown-beside-the-counts` |
| `/login` reaches the front end's terminal through the session (`:hand-terminal`), and a session with none runs nothing. | `session-test/login-signs-in-through-the-front-ends-terminal` |
| A run asks `-p`/`-m`, else `:default-lm`, else `claude-code/sonnet`; an API key in the environment does not choose; a malformed label falls back. | `main-test/a-run-asks-the-configured-model-not-whichever-key-is-set` |
| A character outside the BMP reaches a provider as itself and comes back as itself: `brainyard.json` writes non-ASCII as UTF-8 rather than as escapes, and reads a surrogate-pair escape as one character (provenance P-20). | `json-test/a-character-outside-the-bmp-survives-a-round-trip`, `json-test/a-surrogate-pair-from-a-provider-is-one-character` |
| A streamed reply arrives in fragments as it is written, and parses to exactly what the unstreamed one does. | `agent-test/the-reply-can-arrive-as-it-is-written` |
| Each fragment says which reply field it is, decoded as far as it has arrived. | `agent-test/the-reply-can-arrive-as-it-is-written`, `agent-test/a-field-is-decoded-as-far-as-it-has-arrived` |
| A stream that is not a structured reply is labelled rather than swallowed. | `agent-test/a-provider-that-streams-prose-is-not-swallowed` |
| Streaming changes the invocation only when it is on. | `agent-test/streaming-is-off-unless-asked-for` |
| Structured output is preferred; an errored CLI result is not read as a reply. | `agent-test/claude-cli-output` |
| Native tool calls the CLI rejected are reported, in order; a rejected StructuredOutput is not one. | `agent-test/claude-cli-rejected-tool-calls` |
| A provider that takes a reply schema is sent it, and one that does not is not. | `structured-output-test/the-schema-reaches-the-provider-as-response-format`, `structured-output-test/the-providers-that-take-a-schema-are-brainyards` |
| `strict` is on exactly when the schema has no optional property, unless the config says otherwise. | `structured-output-test/a-schema-is-strict-eligible-only-when-nothing-about-it-is-optional`, `structured-output-test/an-explicit-strict-setting-beats-the-auto-detection` |
| Bedrock routes Anthropic models to the native body (cache marks intact) and everything else to Converse. | `cache-test/bedrock-routes-by-model-family`, `cache-test/bedrock-converse-for-everything-else` |
| The aws invocation and Converse's usage key names are what the CLI expects. | `cache-test/bedrock-invocation-and-usage` |
| Input a Converse reply's total counts beyond its named parts is counted as input, priced at the input rate, and said as unreported. | `cache-test/bedrock-invocation-and-usage` |
| A Bedrock error a hint names is said with its hint. | `cache-test/a-bedrock-error-says-what-it-means` |
| A Converse reply with reasoning and no text fails as retryable; one with text after reasoning answers with the text. | `cache-test/a-reasoning-only-converse-reply-is-retried-not-misread` |
| A model `sampling.edn` says refuses a temperature is sent none, by every body, and samples freely; one that takes it is sent it. | `cache-test/a-model-that-refuses-temperature-is-sent-none` |
| An LM carries no parameter its provider cannot send, and an explicitly requested one is announced rather than dropped in silence. | `wire-test/a-parameter-the-provider-cannot-send-is-not-in-the-lm-map`, `wire-test/a-sampling-parameter-that-cannot-be-sent-is-announced` |
| Retries are an effect: retryable failures retry with reported waits, others throw at once, a cancellation ends the call, a cancel mid-backoff ends it at once. | `cancellation-test/with-retry-is-an-effect` |
| A cancelled turn kills the claude CLI. | `cancellation-test/a-cancelled-turn-kills-the-claude-cli` |
| Exactly one Anthropic auth header is sent. | `anthropic-headers` (`cond->` on api-key, then auth-token only when no key). No test. |
| A usage-cap 429 is fatal and not retried. | `usage-cap?` in `api-error`; exercised live (OpenAI "no credits"). No test. |
| A command vector reaches `jolt.process` spread, never as one argument. | `apply p/process` at every call site (provenance P-1). No test isolates it. |

| A decision call reuses the retry loop and credentials, and a lev label carries no credential. | Nothing yet — `byj-gly.115`. |
| A lev server that is absent or 503 degrades to the next model and is reported; it never fails the turn. | Nothing yet — `byj-gly.115`. |

## Known gaps

- **HTTP providers cannot stream** — every in-process route either buffers or
  is unimplemented, and the one that streams has no TLS (measured above). Only
  claude-code streams; OpenAI, Anthropic and Bedrock would need a `curl`
  subprocess, which would move auth, headers, timeouts and retries out of the
  client and into a command line. If jolt ever implements
  `BodyHandlers/ofPublisher`, brainyard's `sse_publisher.clj` becomes portable
  as written.
- Prompt caching is RFC-015: the anthropic path marks the stable zones, OpenAI
  gets its two parameters, and the cache tokens are counted. Unmeasured without
  a key — `byj-gly.36`.
- Bedrock has no streaming, no `cachePoint` on the Converse path
  (`byj-gly.37`), and no Nova-specific body.
- The `:lev` decision format is specified and not built — `byj-gly.115`, gated
  on `byj-gly.113`. It is reached over HTTP rather than as a `deps.edn` entry so
  that `jolt build:byj` stays self-contained and `dev/cold-load.sh` keeps loading
  every namespace without lev's natives present.
- No `retry-after` honouring: `retry-backoff` accepts `:retry-after-ms`, but no provider parses the header yet.
