Define (or replace) a persistent user hook: a Tool Lisp function run when an agent event fires. Call hook$events for the events and their payload keys.

Most events are OBSERVED: the body logs, records or notifies, and its return value is ignored. `agent.tool-use/pre` is GATED: it runs before each tool call, and the body's return value decides the call —
- nil → allow;
- {:result :replace :replacement {:error "why"}} → the tool does not run and the call returns the replacement; the turn goes on, so this is the way to refuse a call and tell the agent why;
- {:result :modify-args :args {...}} → the tool runs on these args instead;
- {:result :block :reason "why" :answer "what to tell the user"} → the tool does not run and the TURN ENDS with that answer.
The first hook that decides wins; built-in guards run at priority 100.

Args:
- id: lowercase-kebab id, e.g. "bash-command-log"
- event: an event from hook$events, e.g. "agent.tool-use/post"
- match: REQUIRED scope — {"tool-name": "bash"}, {"agent-id": "react-agent"}, or {"global": true} for every firing
- body: a string holding ONE Tool Lisp form, (fn [event] ...). event is the payload map with keyword keys plus :event-key and :agent-id. Tool Lisp is the language of tool$create's body (see its description): tools are called with a params map, e.g. (write-file {:path ".brainyard/logs/bash.log" :content (str (:command (:args event)) "\n") :append true}).
- doc: one line (optional)
- priority: integer, higher runs first (optional, default 0)
- on-error: gated events only — what a body that fails decides: "allow" (default) or "block"

The hook is saved under .brainyard/hooks/ and active at once. hook$validate checks a definition — and with `sample` runs it on a sample event, returning a gated hook's decision — without saving.
