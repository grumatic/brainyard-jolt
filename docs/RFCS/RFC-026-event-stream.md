# RFC-026 — The event stream

**Status:** implemented — the vocabulary and its one door (`byj-gly.80`), the
log that writes it down (`byj-gly.81`), and the wire events that make a call
reproducible (`byj-gly.119`). One log, lossless, project-scoped.

## Purpose

Specifies what the kernel says about itself while it runs: the one door every
event goes through, the vocabulary of events, and what each one *is* — as
distinct from how any viewer shows it.

## Scope

**This layer decides** which events exist and which kind of thing each is: a
problem someone has to see, the turn narrating itself, or bookkeeping something
else already reports.

**It must not know** how any of that is displayed, or whether anything is
listening at all.

**It hands** `(fn [event data])` a labelled stream. Three consumers take it:
`main/tracer` shows the turn as it happens, `eventlog/sink` writes it down, and
`predictions/sink` (RFC-021) records what a predictor was asked.

## Model

### One door

Every emission goes through `events/emit!`, which takes a ctx (anything with
`:on-event`) or the handler itself, because both shapes exist at the call sites.

```clojure
(events/emit! ctx :tools/user-tool-skipped {:file f :problems ps})
```

That single door is not tidiness. It is what makes the catalogue below
*checkable*: a test can only read `src/` for emissions whose shape it
recognises, and before this the code emitted four different ways —

```clojure
((:on-event ctx) :x/y {})                       ; invoked in place
(when-let [f (:on-event ctx)] (f :x/y {}))      ; bound, then invoked
(on-event :x/y {})                              ; the handler from :keys
(emit! ctx :x/y {})                             ; a private wrapper, three times over
```

— so a scan found 41 events and missed `:session.turn/end`, `:task/detached`,
`:task/parked`, `:session/closed`, `:session.turn/start`, `:agents/defined` and
`:agents/deleted`. `events-test/nothing-reaches-the-bus-except-through-here`
forbids the other three shapes, in the style of RFC-009's ratchets. Reading
`:on-event` to **pass it on** — `:on-event (:on-event ctx)` in a child's option
map — is not access and is not flagged.

### Four levels, and what they are not

`brainyard.events/catalogue` maps each event to `{:level :doc}`, plus a `:label`
where the event's own name does not read as a phrase.

| level | meaning |
|---|---|
| `:problem` | A misconfiguration or failure that is invisible otherwise. **Shown whether or not anyone asked.** |
| `:notice` | Something became **possible** that was not before. Shown unasked, like a problem, because a capability nobody is told about is a capability nobody has — and unlike a problem, nothing is wrong. Said **once**: an opportunity repeated is an interruption. |
| `:trace` | The turn narrating itself. Shown under `-v`. |
| `:quiet` | Deliberately not shown, because something else already says it. The `:doc` says what. |

**The level is not a rendering instruction.** It says what kind of thing the
event is; the viewer decides what to do about it, exactly as it decides what to
do with a labelled reply fragment (RFC-006). The two questions change
independently — a second viewer re-answers the second, never the first.

`:quiet` earns its own name rather than being an absence. A dropped event and an
event nobody wrote a branch for look identical from the outside, and the whole
defect this closes was that nothing distinguished them. A `:quiet` entry is a
decision, recorded, with the reason beside it — and a test requires that reason
to be more than a restatement of the event's name.

### What went wrong

27 of the 41 events then known had no consumer at all: `main/tracer`'s `case`
had no branch, so they fell through to `nil` and vanished with or without `-v`.

Measured on a project with a malformed `.brainyard/tools/broken.edn`: the loader
skipped it and named both problems, emitted `:tools/user-tool-skipped` — and
nothing was printed, either way. Someone writes a tool, it is silently ignored,
and the agent then tells them the tool does not exist. The same held for
`:hooks/user-hook-skipped`, `:agents/agent-skipped`, `:hooks/handler-failed` and
`:agent.tree/tool-missing`.

The tracer is therefore installed **always**, not only under `-v`, and `-v` now
selects between `:problem` and `:problem` + `:trace` rather than between
something and nothing.

### The log

`<project-dir>/.brainyard/logs/events.log` — one `pr-str` map per line:

```clojure
{:ts 1789904076224 :event :tools/user-tool-skipped :level :problem
 :session "1789904076223-ec8d293e" :turn 2
 :data {:file "…/broken.edn" :problems ["name must be lowercase-kebab" …]}}
```

It exists for the one question a live viewer cannot answer: *did this happen in
an earlier run?* The level travels with the record, so the question a person
actually asks — "what went wrong here lately" — is a filter rather than a
search.

**Outside `sessions/` on purpose.** Session directories are pruned
(`:prune-sessions?`, RFC-016), and a log whose whole job is to outlive the run
that wrote it must not be swept away with the session it describes. Rotation
bounds it instead: 10 MiB, three backups, checked before a write rather than on
a timer, because nothing here runs in the background and a log that is bounded
only while the agent is idle is not bounded. A read spans the rotation, newest
first, so the recent past does not vanish at a file boundary.

**It is lossless, and that is the point.** Nothing is clipped and no payload is
trimmed to a subset of its keys. This file is the one record of what happened —
a dataset is derived from it (RFC-021) and a provider call is replayed from it —
so a record that dropped `:inputs`, or cut a prompt at 2000 characters, would be
a record nothing could be rebuilt from. It did both once, and a dataset built
from the result lost 74 of 156 records to clipping.

**One event is not written**, and not for size: `:agent.llm/chunk` is a streamed
fragment, and `:agent.llm/response` already holds the whole reply. Excluding a
redundant *representation* loses no information. Anything the catalogue does not
mention is written, because the point of a log is to hold what nobody thought to
ask for.

**What it costs is bounded by rotation, not by the record.** 50 MiB and three
backups, raised from 10 when the contents became whole prompts and replies: at
the old limit a day of real work would roll the log, and with it anything
derived from it.

**The read is where the trimming happens.** `log$events` and `log$search` are
tools a TURN can call, so what they return drops `conversation-keys` —
`:inputs`, `:outputs`, `:reasoning`, `:messages`, `:content`, `:body`, `:stdin`,
`:args`, `:response` — and says so in `:withheld` rather than looking complete.
`:full?` is for a person at a REPL and for `build-dataset`.

Trimming happens **before** the filter, which is the part worth stating: match
on the whole record and hide it afterwards, and `:match` becomes a way to
confirm that a phrase appears in a prompt the caller may not read.

`byj-gly.85` made this argument when the fix was to stop WRITING those keys,
because the prediction log they duplicated was opt-in. Writing them is now the
whole point, so the promise moved to the read, where it costs nothing. What is
deliberately not withheld is the question, the tool arguments and the results —
that bead kept those too, since the trajectory and the session snapshot already
hold them under the same gate.

**Historical:** `:log-keys` recorded only the named keys.
`:predictor/call` keeps `:predictor-id`, `:params-source`, `:params-version`,
`:elapsed-ms`, `:usage` and `:error` — what the call cost and where its params
came from — and drops `:inputs`, `:outputs` and `:reasoning`, which are the
assembled question and iteration history, verbatim what the prediction log
exists to record. That one was written whole at first: the argument above was
made and then not applied to the payload that carries the same thing
(`byj-gly.85`, found by running this agent on this repository).

The question, the tool arguments and the results *are* logged, and that is a
different case: the trajectory log and the session snapshot already hold them
under the same `:persist-session?` gate (RFC-025), so this adds no exposure a
project has not accepted. The assembled predictor inputs are on disk nowhere
else unless the prediction log is on.

`:persist-session?` gates it, as it gates the trajectory (RFC-025) and for the
same reason: a payload carries the question, the tool arguments and the results,
so this is a record of the conversation whatever else it is.

#### Asking it

`log$events` (filter by event, level or session) and `log$search` (every word
must appear in one event) — commands, like `memory$*`: the log is never
injected, the model asks.

**A filter nobody can satisfy is refused, not answered with nothing.** A level
that is not one of the three, or an event the catalogue does not hold, returns
an error naming what is valid — an unknown event suggesting the near ones. Zero
results is what *all clear* looks like, so a query that cannot match has to say
it cannot: a live run asked for `:level "error"` and `:level "warn"`, got `0`
from both, and reported that nothing had gone wrong (`byj-gly.84`).

brainyard also has `log$turns`. It is not ported, because a turn already has a
better record of its own here: `trajectory.edn` holds every iteration (RFC-025),
and `memory$history` searches what was asked and answered. A third answer to the
same question would be the worst of the three.

### The wire, and what the log now holds

`:agent.llm/request` is emitted by `predictor.clj` and carries the *assembled
messages* — the layer above the wire. `brainyard.llm` used to emit nothing at
all, so no record existed of the body actually sent after `build-openai-body`
and the schema shaping, nor of the status, the timing, the retries, or the
`claude -p` argv and stdin.

It emits these, named for brainyard's so the two logs compare field by field:

```clojure
:llm.wire/request   {:provider :model :url :body :headers}
:llm.wire/response  {:status :duration-ms :body :usage}
:llm.wire/failed    {:status :duration-ms :error :retryable? :attempt}
:llm.cli/call       {:provider :model :args :stdin}
:llm.cli/result     {:duration-ms :usage :cost :response}
```

`:llm.cli/call` is the one that earns the losslessness: argv and stdin together are a
replayable call, which is why `dev/brainyard/dev/replay.clj` can replay
*brainyard's* invocations and not this port's. Provenance P-6 is what that costs
— a refusal was blamed on the CoT field when the cause was an abbreviated
prompt, and the way it was finally settled was reading brainyard's log for what
had actually been sent.

RFC-006's scope survives: these describe **the call**, not the turn, and the
handler reaches the provider in the LM config as `:on-chunk` already does.

**Headers are recorded, with credentials redacted by key.** brainyard logs none,
and pays for it: a logged HTTP call is replayable in body and URL only, with
auth, `anthropic-version` and the beta headers left to be reconstructed by hand.
Redaction keeps the secret out without throwing away the half of the request
that decides how a provider answers.

### Not mulog

brainyard writes its app log through mulog, and mulog is available here
(`jolt-lang/mulog`, 0.9.0 with its Java classes shimmed, `:simple-file`
publisher). It is still declined, on two grounds that are about this port rather
than about the library:

- **The one door is the property worth keeping.** `events/emit!` is what makes
  the catalogue checkable, and it exists because the code emitted four different
  ways before it. `mulog/log` is callable from anywhere with any shape, and
  adding it re-opens exactly what "One door" closed.
- **brainyard's own log shows what no filter costs.** Its `:level` is an inert
  data field that nothing reads back, so full request *and* response bodies are
  written unconditionally with no way to turn them off: 50 MiB across four
  files, and roughly 390 MB before rotation was added. Here the ceiling is a
  parameter and the log is one file rather than two, which is the difference
  that matters.

## API

`brainyard.events`: `emit!`, `catalogue`, `levels`, `level`, `problem?`,
`label`, `detail`.

`brainyard.eventlog`: `log-file`, `sink`, `record`, `logged?`, `rotate!`,
`read-events`, `commands`. Config (RFC-013): `:event-log?` (default true),
`:event-log-max-bytes` (10 MiB), `:event-log-keep` (3).

`label` gives the event's human phrase — its `:label`, else its name with the
dashes opened out. `detail` gives the payload's own words: the file that was
rejected, the reason, the message a handler threw. Neither invents anything; a
problem is usually already a sentence someone wrote, and restating it would only
lose it.

A viewer meeting an undeclared event gets `nil` from `level`. That is not a
default to paper over — the ratchet fails on it — but a caller outside `src/`
can emit anything, and treating an unknown event as a trace is the harmless end
of being wrong.

## Invariants

| invariant | enforced by |
|---|---|
| **Nothing reaches the bus except through `events/emit!`.** | `events-test/nothing-reaches-the-bus-except-through-here`, `events-test/the-bus-detector-is-not-vacuous` |
| Every event `src/` emits is declared. | `events-test/every-emitted-event-is-declared`, `events-test/the-detector-is-not-vacuous` |
| Every declared event is emitted — a catalogue does not outlive its code. | `events-test/every-declared-event-is-emitted` |
| Every entry has a level from `levels` and a doc saying what the payload carries. | `events-test/every-entry-says-what-it-is-and-what-it-carries` |
| **A `:quiet` event says what says it instead**, at more than a restatement of its name. | `events-test/a-quiet-event-says-what-says-it-instead` |
| A rejected definition is a problem; the turn narrating itself is not. | `events-test/a-rejected-definition-is-a-problem-a-narration-is-not` |
| A problem is named in words and keeps the payload's own. | `events-test/a-problem-is-named-in-words-and-keeps-the-payloads-own` |
| A run writes the stream down with its session, turn and level. | `eventlog-test/a-run-writes-down-what-happened` |
| The stream is written down whichever viewer is watching: `main/event-bus` puts the event log and the prediction notice behind the inline tracer or the full-screen renderer alike. The full-screen session used to replace the bus and write nothing (`byj-gly.141`). | `main-test/the-event-log-is-written-whoever-is-watching` |
| The log is lossless: no payload trimmed to a subset, no string clipped. | `eventlog-test/the-log-is-lossless`, `eventlog-test/a-long-payload-is-kept-whole-and-the-file-is-bounded-instead` |
| The only event not written is one already recorded whole elsewhere, and its entry says so. | `eventlog-test/the-one-event-not-written-says-why-it-is-redundant`, `eventlog-test/the-only-event-not-written-is-a-second-copy-of-one-that-is`, `eventlog-test/the-chatty-events-are-written-too-now` |
| What a TURN reads back withholds the conversation keys, says which, and is trimmed before `:match` so the filter cannot probe. | `eventlog-test/a-predictor-call-is-written-whole-and-read-back-trimmed`, `eventlog-test/whole-conversations-are-withheld-from-the-read-back` |
| A `:notice` fires once per session, counts the pool `build-dataset` would see, and does not stop the record being written. | `programs-test/a-pool-that-is-big-enough-says-so-once`, `programs-test/the-pool-count-is-what-the-dataset-yields` |
| And carries no credential, while keeping the headers that decide the answer. | `wire-test/a-recorded-call-carries-no-credential` |
| **A filter nobody can satisfy says so**, rather than returning nothing. | `eventlog-test/a-filter-nobody-can-satisfy-says-so-rather-than-returning-nothing` |
| **The log is bounded, and a read spans the rotation.** | `eventlog-test/the-log-is-bounded-and-what-rotates-is-still-readable` |
| A torn tail costs one record, not the file. | `eventlog-test/a-torn-line-costs-only-itself` |
| The log outlives the session it describes. | `eventlog-test/the-log-outlives-the-session-it-describes` |
| A run told to leave nothing behind writes no log, and offers no commands. | `eventlog-test/a-run-told-to-leave-nothing-behind-writes-no-log`, `eventlog-test/the-commands-are-absent-when-the-log-is` |
| **A later run can ask what an earlier one did.** | `eventlog-test/a-later-run-can-ask-what-an-earlier-one-did` |

## What is not here

- **No mulog at all**, and that is now a decision rather than an absence: it is
  available for jolt (`jolt-lang/mulog`) and declined in "Not mulog" below,
  because a second emission path would undo "One door" and because brainyard's
  own unfilterable log is the argument against copying it.
- **No mulog context.** brainyard attaches `{:user-id :agent-id :total-turns}`
  to every event through a global context, plus `:pid` once per process. A
  record here carries `:session`, `:turn` and `:agent`, which the sink learns
  from the stream itself — `:session.turn/start` already says which turn it is,
  and a second channel for the same fact is a second thing to get wrong. `:pid`
  is not recorded because a session id is already unique per run.
- **No SLF4J bridge**, so library logging does not join the stream. Nothing here
  logs through SLF4J.
- **No level for a hook event.** `hooks/fire!` is a different bus (RFC-011),
  reaching handlers rather than viewers, and its events are not in this
  catalogue.
- **No sampling and no rate limit.** A turn emits tens of events, not
  thousands.
