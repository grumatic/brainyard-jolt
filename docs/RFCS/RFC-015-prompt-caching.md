# RFC-015 — Prompt caching

**Status:** implemented and measured live (`byj-gly.5`), through Bedrock
(`byj-gly.21`) — the direct Anthropic path shares the same body builder but has
no key here.

## Purpose

Specifies how the prompt is arranged and marked so a provider can charge the
stable part once instead of every call. brainyard sources:
`clj-llm/core/llm.clj` (`build-anthropic-system-blocks`,
`split-user-message-at-prefix`, `build-anthropic-body`, `build-anthropic-headers`),
`clj-llm/core/prompt.clj` (`user-cache-prefix`, input order),
`common/coact_agent.clj` (`coact-system-zones`),
`common/previous_turns.clj` (batched demotion); doc
`design/prompt-cache-arrangement.md`.

## Scope

**This layer decides** where the cache breakpoints go and what may sit before
them.

**It must not know** what any zone says — only how stable it is.

**It hands** a request body to the provider (RFC-006), and a constraint to
everything upstream: *the cached prefix must be byte-identical call after call*.

## Model

### Ascending volatility

The system message is assembled from ordered parts (`signature/system-parts`),
most stable first:

| part | changes when | measured |
|---|---|---|
| `preamble` | the signature or schema changes | 6,647 chars |
| `agent-core` | the binary or the channel config changes | 1,717 chars |
| `session-context` | tools, working dir, the tool registry or the session changes — it names the session and the agent under `### Session`, brainyard's `session-rows` (byj-gly.193, `agent-test/the-prompt-says-which-session-and-agent-this-is`) | 15,133 chars |
| `history-context` | a turn ends (sessions only) | varies |
| `user-context` | **every turn** — it carries the timestamp | ~200 chars |

The order is the whole optimization: OpenAI caches long prefixes automatically
with no markers at all, so an unstable part placed early costs money on every
provider, marked or not.

### Breakpoints

`llm/system-blocks` turns the parts into Anthropic content blocks and marks the
end of each stable zone with `{:cache_control {:type "ephemeral"}}`. Two rules
keep the marks worth having:

- **Never mark a volatile part** (`user-context`) or the last part: the tail
  changes every call, so a mark there buys a write and never a read.
- **Never mark below the minimum cacheable prefix** (`min-cache-prefix-chars`,
  4000 — brainyard's number). A shorter prefix caches nothing and still spends
  one of the four slots.

Measured here, that puts marks after `preamble` (~1,661 tokens), `agent-core`
(~2,091 cumulative) and `session-context` (~5,882 cumulative): three of the four
slots, with the fourth free. brainyard spends its fourth on a user-message
breakpoint at the `iterations` input; ours would sit after `question: q`, far
below the minimum, so it is skipped.

**The parts are carried, not re-found.** brainyard locates each zone inside the
joined system string with `str/index-of` and warns when it fails; its own design
doc calls that "fragile across recomposition" and says zone texts should be
passed explicitly end-to-end. They are, here: `build-messages` attaches
`:parts`, and `anthropic-body` uses them or falls back to the plain string.

### TTL

`:cache-ttl "1h"` marks every boundary except the last with
`{:type "ephemeral" :ttl "1h"}` and sends
`anthropic-beta: extended-cache-ttl-2025-04-11`. Anthropic requires longer TTLs
to precede shorter ones, which this layout satisfies by construction: the
earlier zones are the stabler ones, and the last mark is the one that churns.
The default stays `5m`, because a 1h write costs about twice a 5m write and the
trade depends on turn cadence — brainyard reached the same conclusion and left
it opt-in.

### Other providers

- **OpenAI** caches prefixes automatically. `openai-body` adds only
  `prompt_cache_key` (routing) and `prompt_cache_retention "24h"` (when the TTL
  is not `5m`), gated to `:openai` because a strict OpenAI-compatible server may
  reject an unknown parameter.
- **claude-code** takes a flat system prompt on the command line; the CLI caches
  on its own. Nothing is sent, but its reported cache tokens are now read back.
- Everything else ignores `:parts` and sends what it always sent.

### Accounting

`anthropic-usage` follows brainyard: the three input categories do not overlap,
so `:input-tokens` is `fresh + cache_read + cache_write`, and the split is kept
under `:cache {:read-tokens :write-tokens}`. The same shape is read from the
claude-code CLI.

## Measured live

Through the real loop, `byj ask -p bedrock -m us.anthropic.claude-haiku-4-5-20251001-v1:0`,
in a fresh working directory so the prefix started cold (2026-09-18):

| call | input tokens | cache read | cache write |
|---|---:|---:|---:|
| 1 | 6,647 | 0 | 6,549 |
| 2 | 6,647 | 6,549 | 0 |
| 3 | 6,647 | 6,549 | 0 |

**98.5% of the input is served from cache from the second call on**, which is
the whole arrangement working: the marked prefix — preamble, agent-core,
session-context — is byte-identical across calls, and only the question and the
iteration history are re-billed. Changing the working directory changes
`session-context` and starts a new prefix, which is why call 1 above writes:
the zone boundaries are real, not decorative.

### Amazon Nova on Bedrock (byj-gly.37)

Nova takes explicit cache points too — up to four, a 1K-token minimum, five
minutes, at most 20K tokens cached — but only through its own body: Converse's
`cachePoint` is refused by an aws CLI older than its model, and invoke-model
passes a body through unvalidated, as it does Anthropic's. So a Nova model
gets the messages-v1 body with a `cachePoint` key on each block where
`system-blocks` would put `cache_control`, never with a `ttl` (Nova refuses
one). Measured with `amazon.nova-micro-v1:0` in us-east-1 (2026-10-10):

| probe | input tokens | cache read | cache write |
|---|---:|---:|---:|
| raw body, call 1 | 11,099 | 0 | 11,095 |
| raw body, calls 2 and 3 | 11,099 | 11,095 | 0 |
| a 3-turn session through the port, all calls | 21,035 | 15,842 | 4,911 |

The same raw body through the cross-region profile `us.amazon.nova-micro-v1:0`
wrote both times and read neither — AWS says cross-region inference "may lead
to increased cache writes"; two calls, so a sighting, not a rule. Two
separate `ask`s read only the agent-core zone, since each is a session with its
own `session-context`; turns of one session read it all.

OpenAI's models on Bedrock have no explicit caching through Converse — gpt-oss
not at all, GPT-5.6 and later only through the Responses API — so the Converse
path carries no markers by design, not by omission.

Converse and Nova report `inputTokens` as what was neither read nor written;
`bedrock-converse-usage` sums the three, as `anthropic-usage` does — before,
a cached call was reported as a 4-token one.

## What this asks of everything upstream

A cached prefix pays only while it is byte-identical. That makes three rules
that belong to other layers:

- **No wall clock in a stable zone.** The turn timestamp lives in
  `user-context`, after every breakpoint.
- **Deterministic ordering** of tools, zones and inputs — the tool listing is
  sorted, the zone order is a vector, not a set.
- **Compaction must be inert under budget** (RFC-014): a turn that fits rewrites
  nothing, so the prefix survives. When compaction does fire it invalidates from
  the first changed zone onward, which is the price of fitting in the window at
  all.

## API

`brainyard.signature`: `system-parts` (the parts, in order), `build-messages`
(now attaches `:parts` to the system message).

`brainyard.llm`: `system-blocks`, `anthropic-body`, `anthropic-usage`,
`bedrock-anthropic?`, `bedrock-nova?`, `bedrock-native?`, `bedrock-body`,
`bedrock-converse-usage`,
`openai-body`, `min-cache-prefix-chars`, `volatile-zones`; `anthropic-headers`
adds the beta flag.

Config (RFC-013): `:prompt-cache?` (default true, and on the `:anthropic`
provider spec), `:cache-ttl` (`5m`), `:prompt-cache-key`.

## Invariants

| invariant | enforced by |
|---|---|
| Marks land at stable zone ends, never on a volatile part, the last part, or a prefix below the minimum. | `cache-test/breakpoints-land-on-stable-zone-boundaries` |
| A 1h TTL marks all but the last boundary and sends the beta header. | `cache-test/ttl-marks-all-but-the-last-boundary` |
| Without parts, or with caching off, the body is exactly what it was before. | `cache-test/caching-off-or-unavailable-sends-what-it-always-sent` |
| Cache reads and writes are counted, and a call without them reports none. | `cache-test/cache-tokens-are-accounted` |
| A Nova model on Bedrock goes through invoke-model with a `cachePoint` on each block an Anthropic mark would close, never with a ttl; without parts or with caching off, one plain block; OpenAI's on Bedrock go through Converse unmarked. | `cache-test/bedrock-routes-by-model-family`, `cache-test/bedrock-nova-carries-cache-points`, `cache-test/bedrock-converse-for-everything-else` |
| Bedrock's input tokens are fresh + read + write, whichever key names the model uses. | `cache-test/bedrock-invocation-and-usage` |
| OpenAI's parameters are sent only to OpenAI. | `cache-test/openai-takes-its-own-parameters` |
| The zones a breakpoint covers are byte-identical across a turn's iterations, and ordered most-stable first. | `cache-test/the-cached-prefix-is-byte-stable-across-iterations` |

## Known gaps

- **The direct Anthropic path is unexercised.** It builds the same body through
  the same `anthropic-body`; only the transport differs. No key here.
- **The fourth slot is unused.** brainyard spends it on a user-message
  breakpoint; ours would fall below the minimum prefix. It is free for a future
  zone.
- **Session history is not batched** the way brainyard batches previous-turn
  demotion to keep the prefix stable for several turns at a time; the session
  loop keeps a fixed window (`:max-previous-turns`) and re-renders
  `history-context` every turn, which invalidates the cache after that zone
  (RFC-016 known gaps).
