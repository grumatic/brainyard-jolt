# RFC-043 — The share sidecar

**Status:** implemented. byj's side of session sharing: a session may be
shared by a program byj starts beside it, the **share sidecar**
(`byj-share`, `:share-sidecar`), which carries what it reads of the session
to people elsewhere. byj starts it, answers it over the session channel
(RFC-031) and shows the person what it reports; what leaves the machine,
where it goes and who sees it are the sidecar's. The sidecar and the
service behind it are specified with that service; this RFC was split from
theirs in RFC-070's phase 2 (`byj-ahf.8`), keeping its number.

## Purpose

A session is watched, coached and reviewed by people its owner invited,
from wherever they are. byj makes that possible without becoming a server:
it keeps RFC-027's boundary (no HTTP server, no websocket in the kernel)
and meets "a second client exists" with an out-of-process one, which reads
the session's events over `ask.sock`, as any other subscriber does, and may
send back only notes and turns that the owner approves at their terminal.

## Scope

**byj decides** what its socket answers a sidecar, what the person at the
owner's terminal sees — the share's line and footer, who joins and leaves,
notes, the approval of an injected turn — and when the sidecar runs: as a
quiet task of the session (RFC-019) that ends with it.

**It must not know** that a relay, a browser, WSS or TLS exist, nor what a
role allows: it knows the sidecar's command line, the line it prints when
its share is open, the registry it keeps on disk, and the presence it
reports. A target (`link`, `class:CODE`, `class:CODE/KEY`, `shr_…`) is
checked for shape and passed on untouched.

**It hands** the sidecar the socket's path, the session's id, the target,
its own pid as the parent the share ends with, and the relay to dial; and
the trajectory (RFC-025) records each note and injected turn for whoever
reads it later.

**Checked in three places.** The sidecar's service checks a person's role;
the sidecar checks which socket ops a remote request may reach and the
owner's policy; byj checks the owner's approval and its own permission
gate. A compromised service still meets the last two, on the owner's
machine.

## Model

1. **The socket, at frame version 2.** The sidecar reaches byj only through
   `ask.sock` (RFC-031, one EDN map a line). It reads `:frame-version` from
   `:status` first, and does not share against a version it does not know.
   Remote requests reach these ops and no others; the sidecar refuses the
   rest (`:ask`, `:cancel`, `:config`, `:emit` but presence, `:mcp`,
   `:inject :as :memory`):

   | request | reply |
   |---|---|
   | `{:op :subscribe}` | the session's events, as RFC-031 has them |
   | `{:op :status}` | as RFC-031, carrying `:frame-version` |
   | `{:op :inject :as :note :text :from {:user :name :role}}` | `{:status :ok :injected :note}`; `empty note`; `no session to note on yet` |
   | `{:op :inject :as :turn :text :from {:user :name :role} :approval :owner :await?}` | as RFC-031's `:inject`; or `rejected by the owner`, `approval expired`, `this session has no one to approve an injected turn` |
   | `… :approval :consent` | as RFC-031's `:inject`, asking no one; the turn is marked |
   | `{:op :emit :event :session.share/presence :payload {:share :url :participants [{:user :name :role}] :paused}}` | `{:status :ok :emitted … :subscribers n}` |

   On `:subscribe` the sidecar also reads `:session.share/note {:text
   :from}`, `:permission/asked {:id :tool :summary}`, `:permission/decided
   {:id :tool :decision :allow|:deny}` and `:session.share/control {:action
   :pause|:resume|:kick|:stop|:record-on|:record-off :user?}`. No op carries
   a permission decision in: the gate's prompts are events, answered only at
   the terminal. `contract/session/` holds the frames as golden files.

2. **A note** is recorded in the trajectory as `{:kind :note :text :from
   :ts :session}` (`trajectory/read-notes`; `read-trajectories` leaves it
   out), shown as `✎ note from Kim (coach): …`, and is never a turn nor in a
   model's messages.

3. **An injected turn** waits for the owner. Full-screen, it is the gate's
   overlay, labelled `injected turn`: `Kim (coach) wants to run: <text>`,
   allow or refuse, refused after `:share-inject-approval-ms` (120000).
   **Refuse has the focus**, and `y` or `n` answer it (`byj-1wv.11`): with
   allow focused, a stray Space or Enter ran a turn someone else wrote. The
   inline session says the request and the person answers at the prompt
   with `/share allow` or `/share refuse` (`share/inline-approver`), refused
   at the same deadline; a headless session has nobody to ask and refuses
   an `:approval :owner` turn. `:approval :consent` asks no one and still
   marks the turn. An allowed turn is an ordinary turn, through the same
   permission gate and auto mode, recorded `:injected-by {:user :name :role
   :approval}` (a session's `{:session id}`), so whoever scores a run can
   tell the owner's own turns from the rest.

4. **`--share TARGET`** on `byj run`, inline, full-screen or `--serve`,
   starts `byj-share run --socket <ask.sock> --target <TARGET> --session
   <id> --parent <pid> [--relay <relay>] [--title <label>] [--record]
   [--share-id shr_…]` (`share/argv`) as a quiet background task of the
   session (`job-type :share-sidecar`), which ends with it; `--parent` ends
   the share with byj however byj ends. The relay is `share/relay-of`'s: `:share-relay-url` when set, else the one
   the sidecar is logged in to (its credentials under `share-home`), else
   none — no `--relay`, and the sidecar dials its own default: byj names no
   relay (`byj-ahf.7`). `--record` when
   `:share-record?`. A `shr_…` target reopens that ended share: `--target
   link --share-id shr_…`, as `byj run -r ID --share shr_…`. `:share-sidecar`
   names the binary (`byj-share` on PATH); missing, the session says so and
   runs unshared. A piped session has no channel and refuses `--share`.

5. **What the person sees.** The relay named as the share starts ("the sidecar's own relay" with none), then
   `sharing at URL` once the sidecar has it (again only when a reconnect
   brings a new one; `share/url-line`), `sharing · N` in the footer (N not
   counting the owner; `sharing · paused`; the shares this process runs
   counted with it, `sharing 2 · 0 watching`), a line when someone joins or
   leaves. `/share` says who and where; `/share pause|resume|kick
   USER|stop|record on|off`: stop cancels the sidecar's task, the rest are
   `:session.share/control` for the sidecar. A sidecar that ends on its own
   is said with its last lines, `sharing ended: …` (`:session.share/ended`,
   kept on its task as `:share-why`), which `/share` and `share$status`
   repeat while nothing is shared, and `share$start` returns in place of
   waiting out a URL that cannot come.

6. **Any session, by reference.** `/share start REF…`, ctrl+s on the agent
   page and `share$start` share sessions other than this one — `byj:<id>`,
   `claude:<id>`, `codex:<id>`, `term:<name>`, `agent:<name>`, or `team`,
   this session's team (RFC-032): one `byj-share run --session REF --parent
   PID …` each (`share/ref-argv`), through this process's sharer
   (`share/process-sharer`), which stops them all as it leaves; several at
   once as one group, `byj-share start --json --parent PID [--group TITLE]
   REF…` (`share/group-argv`). The links come from the line the sidecar
   prints once its share is open (`{"share" … "links" {…}}`,
   `share/ready-in`; an older sidecar's stderr, `share/links-in`).
   `/share` lists the running shares this process started, from the
   sidecar's registry (`<share-home>/active/shr_*.json` whose process is
   alive, `share/from-here`). An agent shared by reference that does more
   than read is shared only once the person says so, asked through the
   permission prompt; with no one to ask, it is refused. ctrl+s offers an
   open byj session and a live Claude Code one, nothing saved, no subagent.

7. **The agent's tools.** With `:share-tools?` (on), the session's agent
   has `share$status`, `share$ls`, `share$start`, `share$control`,
   `share$record` and `share$login` — the operations `/share` runs, one
   code path for both (`brainyard.share-tools`); a subagent has none.
   `share$start` and `share$record` carry the `:share/publish` effect,
   which `:tool-ask-effects` asks about by default, and are refused
   headless. `share$login` runs `byj-share login [--relay RELAY]` as a quiet task, returning its link and code once printed, one login at a time.

8. **Installed by its own program.** byj neither installs, updates nor
   lists the sidecar in `doctor` (`byj-ahf.7`): missing, a share says it is
   not on PATH and that `:share-sidecar` names the one to run; `/share` and
   `share$status` say its login.

## API

Configuration (RFC-013, family `:session`): `:share-sidecar` (the binary,
"byj-share"), `:share-relay-url` (nil: the sidecar's own), `:share-inject-approval-ms`
(120000), `:share-record?` (false), `:share-tools?` (true). The sidecar's
own directory is `BYJ_SHARE_HOME`, else `~/.brainyard/share` (the
`brainyard.share-home` property first, which the suite sets).

`brainyard.share`: `target-problem`, `reopen-id`, `resolve-binary`, `argv`,
`start!`, `stop!`, `running-task`, `relay-of`, `login!`, `login-state`,
`active`, `from-here`, `sharer`, `process-sharer`, `ref-argv`,
`group-argv`, `start-group!`, `ready-in`, `links-in`, `footer`,
`url-line`, `relay-said`, `inline-approver`. `brainyard.share-tools`: the operations and
the `share$*` commands over them.

## Invariants

| invariant | enforced by |
|---|---|
| The socket names its frame version in `:status` and `meta.edn`. | `channel-test/the-queue-is-bounded-and-a-timeout-does-not-withdraw`, `channel-test/a-request-becomes-a-turn-and-its-answer-comes-back` |
| What a sidecar reads of byj — the socket's `:status`, a turn's frames, the trajectory — is what byj gives now. | `contract-test/the-session-socket-and-the-trajectory-are-byjs-contract` |
| A note is acknowledged, published with its sender, recorded as a note, and never a turn nor in a model's messages. | `share-test/a-note-is-for-people-only` |
| An owner's approval is asked with the configured time; refused, expired and unaskable are each said and queue nothing; consent asks no one and still marks. | `share-test/an-injected-turn-asks-its-owner` |
| An allowed injected turn is an ordinary turn, shown with its sender, recorded `:injected-by`, and counted as an intervention beside the notes. | `share-test/an-injected-turn-is-recorded-shown-and-counted-apart` |
| The gate's prompts are events, read only. | `permission-test/a-prompt-is-said-to-watchers-and-nothing-answers-it-but-the-person` |
| Inline, an injected turn's request is said and answered with `/share allow` or `/share refuse`; nobody answering is a refusal at the deadline. | `share-test/the-inline-approver-is-answered-at-the-prompt`, `share-test/an-inline-session-asks-and-the-person-allows-with-share-allow` |
| `--share` is read on a session only, with a target of a known shape. | `share-test/the-share-flag-is-read` |
| The sidecar starts with its argv as a quiet task of the session, is refused when missing or already running, and stops with its task; a `shr_` target reopens that share as `--target link --share-id`. | `share-test/the-sidecar-runs-as-the-sessions-task` |
| A session's sidecar that ends on its own is said to the person with its last lines, kept for `/share` and `share$status`, and returned by `share$start` without waiting out its URL; one that was stopped says nothing. | `share-test/a-sidecar-that-ends-at-once-says-why` |
| A session shares through `:share-relay-url` when it is set, else the relay the sidecar is logged in to, else none, the sidecar dialling its own default; byj names no relay; the suite reads a sidecar home of its own, never the person's. | `share-test/a-session-shares-through-the-relay-byj-share-is-logged-in-to` |
| The footer, the join and leave lines, notes and the approval overlay are drawn; the footer counts the shares this process runs (this session's once) and who watches them, apart; an unanswered approval takes the overlay down; refuse has the focus, so Enter refuses, and y allows. | `tui-test/a-shared-session-shows-who-watches-and-asks-before-an-injected-turn`, `share-test/presence-is-shown-and-share-commands-reach-the-sidecar` |
| `/share` reports presence and says pause, resume, kick and stop on the bus; the share's URL is said unasked, once, when the sidecar first has it, and again only for a new one; the start line names the relay. | `share-test/presence-is-shown-and-share-commands-reach-the-sidecar`, `share-test/the-sidecar-runs-as-the-sessions-task` |
| A running share's recording is turned on and off from the session — this session's through its bus, another's through `byj-share record on|off REF` — and it asks first. | `share-test/a-running-shares-recording-is-turned-on-and-off` |
| byj shares a session by its reference with `--parent`, says its links from the ready line (or an older stderr), stops it on the same key, and stops all as it leaves; `team` is the leader and its open members; `/share` lists only live registry entries. | `share-test/a-session-is-shared-and-unshared-by-its-reference`, `share-test/the-ready-line-and-the-older-stderr-both-give-the-links`, `share-test/the-registry-lists-running-shares-only`, `share-test/share-start-team-is-the-leader-and-its-open-members` |
| byj shares several sessions, or its team, as one group ending with it, and says each link and the group's. | `share-test/several-sessions-are-shared-as-one-group` |
| ctrl+s offers to share an open byj session and a live Claude Code one, nothing saved, no subagent; a shared row says so. | `tui-test/ctrl-s-shares-an-open-session-of-either-kind-and-nothing-saved`, `tui-test/ctrl-s-shares-a-live-claude-code-session-and-the-row-says-so` |
| An agent that does more than read is shared only once the person says so, through the permission prompt; no one to ask refuses. | `share-test/an-agent-that-does-more-than-read-is-shared-once-the-person-says-so` |
| The session's agent has `share$status`, `share$ls`, `share$start` and `share$control`, a subagent none; `share$start` returns the share's links once the sidecar has it open, passes a terminal's and an agent's options, and is asked about by default (`:share/publish`), refused headless. | `share-test/the-sessions-agent-shares-through-share-commands`, `share-test/a-terminal-or-an-agent-is-shared-with-its-options` |
| `share$login` logs the sidecar in to this session's relay in the background, returning its link and code once printed and one login while it waits; the session says when it ends, and a failure says why; `share$status` names this session's relay and the sidecar's, and says when they differ. | `share-test/the-sessions-agent-logs-byj-share-in` |

## Known gaps

None.
