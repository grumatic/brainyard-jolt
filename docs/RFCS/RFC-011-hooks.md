# RFC-011 — Hooks

**Status:** implemented (`byj-gly.3`; user gates `byj-gly.31`).

## Purpose

Specifies the hook bus: named events the loop and the kernel fire, handlers
that observe them, and the one gated event whose handlers — built-in or user —
may block, replace or modify a tool call. Built-in hooks and user hooks are data, the way tools are
(RFC-010): a built-in hook is a spec naming a kernel fn, and a user hook is a
spec plus a Tool Lisp body.

brainyard sources: `agent/core/hooks.clj` (registry, `fire!`,
`fire-decision!`, the event catalog), `agent/core/tool.clj` (`tool-pre-hook`,
`tool-post-hook`, `blocked-tool-result`), `common/coact_agent.clj`
(`coact-tool-dispatch-action`'s block path), `common/loop_guard_hook.clj`,
`common/user_hooks.clj` (user hooks and `hook-agent$*`); docs
`core/agent.md` (hooks registry), `design/event-bus-and-reactor.md`.

## Scope

**This layer decides** which handlers run for an event, in what order, what a
gated event's decision is, and what a hook — built-in or user — is.

**It must not know** why an event fires. *Where* the loop fires its turn and
iteration events is the tree's (RFC-003); the tool and LLM-call events are
fired by the kernel around every call (RFC-007, RFC-005).

**It hands** a decision to `tools/call-tool`, which the tool channel turns into
the end of the turn.

## Model

### The bus

A registry is an atom `{event [entry …]}`, built by `agent/make-ctx` and held in
ctx `:hooks` — per ctx, not per process (divergence `hooks-per-ctx`). An entry:

```clojure
{:id "redundant-tool-call-guard" :event :agent.tool-use/pre
 :handler (fn [payload ctx] …)   ; ctx is the firing leaf's: st-memory, tools, config
 :match (fn [payload] bool)      ; optional; a throwing match is false
 :priority 100                   ; higher first; ties keep registration order
 :source :builtin|:user-hook     ; informational
 :on-error :log|:throw}          ; default :log
```

- `fire!` calls every matching handler for effect.
- `fire-decision!` (gated events) returns the first valid non-`:allow`
  decision, with `:by` set to the handler id, or `{:result :allow}`. `:allow`
  and `nil` abstain; a decision cannot be overridden by a later handler, and
  later handlers do not run; modifications do not chain. A malformed decision
  (`:replace` without `:replacement`, `:modify-args` without a non-empty
  `:args` map) is reported as `:hooks/malformed-decision` and skipped.
- A throwing handler is reported as `:hooks/handler-failed` and skipped — a
  gate fails open — unless `:on-error :throw`. A cancellation always
  propagates.
- `hooks/*depth*` is 1 inside a handler, 2 inside a handler fired from one.

### Events

brainyard's names and payload keys for the hook points this port has
(`hooks/event-catalog`):

| event | payload | fired by |
|---|---|---|
| `:agent.session/created` | `:session-id :session` | `session/init`, a session started fresh, before its first turn |
| `:agent.session/resumed` | `:session-id :session` (with `:resumed-from`) | `session/init`, a session started from its snapshot (`--resume`) |
| `:agent.session/closed` | `:session-id :session` | `session/run`, however its tree ends — /quit, end of input, max turns, a throw |
| `:agent.ask/pre` | `:input` | tree node `react/hook-ask-pre` |
| `:agent.ask/post` | `:input :result :terminated-by` | tree node `react/hook-ask-post` |
| `:agent.iteration/pre` | `:iteration :max-iterations` | tree node `react/hook-iteration-pre` |
| `:agent.iteration/post` | `+ :channel :last-reasoning :goal-achieved` | tree node `react/hook-iteration-post` |
| `:agent.dspy-action/pre` | `:node-id :predictor-id :inputs` | `agent/run-node-predictor`, every LLM call |
| `:agent.dspy-action/post` | `+ :outputs :reasoning :usage :error` | the same, success or failure |
| `:agent.tool-calls/pre`, `/post` | `:iteration :calls`, `+ :results` | `agent/tool-dispatch-action` |
| `:agent.tool-use/pre` **gated** | `:tool-name :args :call-id :depth` | `tools/call-tool` — every call, including a user tool's or a hook's |
| `:agent.tool-use/post` | `:tool-name :args :call-id :result` + verdict flags | `tools/call-tool` |
| `:agent.tool-use/rejected` | `:tool-name :args :result :reason` | `tools/call-tool`, invalid args; no pre or post |

A session's `:session` is a map of what it is at that moment — id,
directories, turn count, usage, `:resumed-from`, title — not brainyard's live
session atom, since a user hook's body takes data; there is no `:user-id`
(divergence `session-hook-payload-is-a-value`). The session events are fired
in code, not by tree nodes: a loop given with `--loop` still opens and closes.

**Hook points in the tree are nodes.** The leaf `:hooks/fire` fires the node's
`:event` with a payload built from its `:payload` template — a keyword reads
st-memory, `:config/x` reads config, a map is built recursively:

```clojure
[:action {:id :react/hook-iteration-pre :fn :hooks/fire :event :agent.iteration/pre
          :payload {:iteration :iteration-count :max-iterations :config/max-iterations}}]
```

So a loop's hook points change without a rebuild, and a loop that wants none
has none.

### Tool-call decisions

`tools/call-tool` validates args (invalid → `rejected`), fires
`:agent.tool-use/pre`, then:

| decision | effect | post flags |
|---|---|---|
| allow | the tool runs | — |
| `:block` | the tool does not run; the result is `{:hook-blocked true :reason :by :answer?}` (brainyard's `blocked-tool-result`) | `:hook-blocked :blocked-by` |
| `:replace` | the tool does not run; the result is `:replacement` | `:hook-replaced :replaced-by` |
| `:modify-args` | the tool runs on `:args`, validated and coerced again | `:hook-modified-args :modified-by :effective-args` |

**A block in the tool channel ends the turn.** `tool-dispatch-action` runs the
batch, and when a result is blocked sets `:answer` to the hook's `:answer` or
`*(Tool dispatch blocked: <reason>)*`, `:terminated true` and
`:terminated-by :tool-blocked-by-hook` (divergence `tool-blocked-terminated-by`);
no further LLM call is made (`:agent.tools/blocked`). A user tool's body
receives the blocked map as a value, as brainyard's code blocks do.

### Built-in hooks

Specs in `resources/brainyard/hooks/<id>.edn`, installed in `index.edn` order
when `:builtin-hooks?` (default on):

```clojure
{:id "redundant-tool-call-guard" :event :agent.tool-use/pre :priority 100
 :doc "…" :impl :brainyard.hookbox/redundant-tool-call-guard
 :opts {:window 2 :idempotent-tools #{}}}
```

`:impl` names a fn in `brainyard.hookbox/impls`, called `(f payload ctx opts)`.
Besides it: the permission gates (RFC-012), the tangled-file guard (RFC-067)
and task-poll-park (RFC-019). The first, brainyard's own, is the loop guard: at hook depth 0, over the last
`:window` iteration records with tool calls, it blocks (1) the same
`(tool-name, args)` in each, or (2) a tool in `:idempotent-tools` called at
least twice with any args; the block's `:answer` quotes the latest tool result.
brainyard's idempotent set names tools this port does not have, so it is empty.

### User hooks

brainyard's layout, under `<working-dir>/.brainyard/hooks/`:

```clojure
;; bash-command-log.edn
{:id "bash-command-log" :event :agent.tool-use/post :match {:tool-name "bash"} :priority 0
 :doc "Append every bash command to .brainyard/logs/bash-commands.log"}
```
```clojure
;; bash-command-log.clj — Tool Lisp, not SCI (divergence user-hook-bodies-tool-lisp)
(fn [event]
  (write-file {:path ".brainyard/logs/bash-commands.log"
               :content (str (:command (:args event)) "\n") :append true}))
```

A gate:

```clojure
;; no-rm.edn
{:id "no-rm" :event :agent.tool-use/pre :match {:tool-name "bash"} :priority 0 :on-error :block}
;; no-rm.clj
(fn [{:keys [args]}]
  (when (clojure.string/includes? (:command args) "rm ")
    {:result :replace :replacement {:error "rm is not allowed here"}}))
```

- **Validation** (`hookbox/compile-hook`), every problem at once: id
  `[a-z][a-z0-9-]*`; a known event; `:match` required — `{:global true}`, or any
  of `:tool-name`, `:agent-id`, `:defagent-type` (the last two compare with
  `react-agent`, the one agent here); `:on-error` `allow` or `block`; the body
  read and validated as Tool Lisp against the tool registry.
- **Running.** The handler interprets the body on the sanitized event — the
  payload plus `:event-key`, `:agent-id`, `:defagent-type` — with the tool
  registry and Tool Lisp's budget. It does not run inside another handler
  (`*depth*` > 1) — so a hook's own tool calls do not fire it again — nor when
  `:enable-user-hooks` is false.
- **Observing and gating.** On an ungated event the body's value is ignored. On
  `:agent.tool-use/pre` it is the hook's decision (divergence
  `user-hooks-may-gate`; brainyard's v1 refuses gated user hooks, because an SCI
  body runs with host access): `nil` allows; `{:result :replace :replacement
  {:error "why"}}` refuses the call and the turn goes on — brainyard's own
  refusal shape (`tool_permission/deny-replace`); `{:result :modify-args :args
  {…}}` rewrites it; `{:result :block :reason … :answer …}` ends the turn. A
  string `:result` (`"block"`) is accepted. A malformed decision is reported and
  skipped, as any handler's.
- **Order.** Priority, as for every handler: a user hook defaults to 0, so the
  built-in guards (100) decide first unless a hook declares more.
- **A failing body.** `:on-error :allow` (default) fails open — reported as
  `:hooks/handler-failed`, the call goes ahead, as brainyard's `:log` gates do;
  `:on-error :block` fails closed with `{:result :block :reason "hook <id>
  failed: …"}`.
- **Loading.** `make-ctx` loads them after the tools, in file-name order; an
  unreadable or invalid file is skipped with `:hooks/user-hook-skipped`.

Commands (brainyard's `hook-agent$*`, renamed `hook$*`, divergence
`hook-commands-renamed`), bound when `:hook-authoring?` (default on):

| command | args | returns |
|---|---|---|
| `hook$events` | — | `{:events [{:event :payload-keys :gated? :doc}]}` |
| `hook$create` | `id event match body` + `doc priority on-error` | `{:id :event :persisted}` or `{:error "hook$create failed:\n- …"}`; the body is persisted as `<id>.clj`, laid out as a user tool's is (RFC-010) |
| `hook$validate` | `body` + `id event match sample` | `{:valid :id-ok :event-ok :event-gated :match-ok :body-ok :collision :errors :sample-result?}` — `sample` runs the body; a gate's sample result is its decision |
| `hook$list` | — | `{:hooks [{:id :event :priority :source :match? :doc?}]}`, built-in and user |
| `hook$read` | `id` | `{:id :event :match :priority :doc :on-error? :body}` or `{:error "no user hook named \"x\""}` |
| `hook$delete` | `id` | `{:deleted id}` |

## API

`brainyard.hooks`: `event-catalog`, `gated?`, `known-event?`, `registry`,
`register!`, `unregister!`, `entries`, `fire!`, `fire-decision!`, `*depth*`,
`fire-action` (leaf `:hooks/fire`).

`brainyard.hookbox`: `builtin-hooks`, `load-builtin`, `impls`,
`redundant-tool-call-guard`, `compile-hook`, `build-match`, `sanitize`,
`load-user-hooks!`, `authoring-tools`, `hooks-dir`.

`agent/make-ctx` and `agent/ask` accept `:hooks [entry …]`, added after the
built-ins. Config: `:builtin-hooks?`, `:hook-authoring?`, `:enable-user-hooks`
(all default true).

## Invariants

| invariant | enforced by |
|---|---|
| Priority orders handlers; a throwing handler or match is skipped and reported; the first valid decision — `hooks/decision-schema`, a malli `:multi` on `:result` — wins and stops the rest; allow abstains; malformed decisions are skipped. | `hooks-test/the-bus` |
| Block, replace and modify-args do what the table says; invalid args fire `rejected` and no pre/post. | `hooks-test/tool-hooks` |
| A turn fires brainyard's events in order, each payload within its catalogued keys. | `hooks-test/a-turn-fires-brainyards-events` |
| A session fires `created` (or `resumed`, from its snapshot) before anything else and `closed` after everything, however it ends; `:session` is a value; a user hook on disk sees them (byj-gly.334). | `hooks-test/a-session-fires-created-or-resumed-and-closed` |
| A blocked tool call ends the turn with no further LLM call. | `hooks-test/a-blocked-tool-call-ends-the-turn` |
| The loop guard blocks the third identical call, reads its idempotent set from the spec, and goes with `:builtin-hooks? false`. | `hooks-test/the-loop-guard-is-a-builtin-hook` |
| User hooks: validated all at once, persisted in brainyard's layout, fired by match, not re-fired by their own tool calls, reloaded from disk, switched off by `:enable-user-hooks`, deleted. | `hooks-test/user-hooks` |
| A user hook observes a whole turn from files on disk. | `hooks-test/a-user-hook-observes-a-turn` |
| A user hook on `:agent.tool-use/pre` replaces, rewrites or blocks calls, from disk too; a failing body allows by default and blocks with `:on-error :block`; a malformed decision is skipped; built-in guards outrank it by default. | `hooks-test/user-hooks-gate-tool-calls` |
| A hook body never reaches host eval. | Tool Lisp (RFC-010, `toolbox-test/bodies-never-reach-host-eval`). |

## Known gaps

- Events brainyard has and this port does not fire yet: `agent.ask/finalize` (gated), `recovery/retrying`,
  `iteration/exhausted`, `dspy-action/chunk` (`byj-gly.8`), `context/budgeted`
  and compaction (`byj-gly.7`), evaluation
  (`byj-gly.12`), todo/scheduler/fsm (`byj-gly.17`). Tasks emit `:task/*` as trace
  events (RFC-019) but not as hook events, which is what auto-notify would need
  (`byj-gly.47`).
- `code-eval` events are not fired and will not be: the code channel is not
  ported (divergence `tool-only-signature`); `tool$run`'s nested calls fire the
  tool events.
- brainyard's permission and deny gates are ported as built-in hooks
  (RFC-012); its tool-cache gate (90) and MCP gate (80) are not
  (`byj-gly.14`).
- User-scope hooks (`~/.brainyard/hooks`) and dynamic events
  (`.brainyard/events/`) are not loaded — as brainyard, project scope only for
  the first.
