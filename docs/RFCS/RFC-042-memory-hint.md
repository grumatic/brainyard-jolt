# RFC-042 — The memory hint

**Status:** decided and built, 2026-10-10. Decision bead `byj-gly.335`.
Spike: `dev/brainyard/dev/memory_hint_spike.clj` (`report` offline, `live`
against a model).

## Purpose

Decides how what the project remembers reaches a turn. RFC-020 made memory a
pull: the model sees one line naming `memory$…` and must guess that something
was stored before it calls `memory$recall`. Pushing it all — an index of every
key, `bd prime`'s 14 KB, brainyard's `## Recalled Memory` every turn — spends
tokens on memories the question has nothing to do with.

The decision: **each turn is told, by key and first line, the few memories
its question shares rare words with; the contents stay a pull.** Measured
below, it turns a weaker model that never pulled into one that reads the
right memory in one call, and costs a stronger model that already pulls
nothing it notices.

## Scope

**This layer decides** which memories a question is about, how many and in
what words a turn is told of them, and when the store is listed.

**It must not know** what a memory says beyond its words, how the store
keeps them, or whether the model will use a hint. The match is lexical and
local: no model call, no embedding.

**It hands** the turn a few lines in its `user-context` zone, and nothing
when nothing matches.

## Model

1. **The store is listed once a session** (`session/store-memories`) through
   `:memory-list-cmd` — about 0.2 s for `bd`, 0.05 s where there is none — and
   kept for `:refresh-ms` (5 minutes), or until something here changes it:
   `memory$remember`, `/memory edit`, `/memory delete` (`memory-changed!`).
2. **A question's words** (`memory/keywords`): lower-case, three letters or
   more, stopwords and possessives dropped, crudely stemmed (`retries` and
   `retry` meet).
3. **A memory's score** (`memory/rank`) is the sum, over the question's words
   it holds in its key or content, of their rarity across the store — inverse
   document frequency, reckoned as if the store held `:prior` (16) more
   memories without the word, so a store of three scores as a larger one
   would.
4. **The gates**, all from the session tree's `:session/memory-hint` node:
   `:min-terms` 1 word in common, `:min-score` 4.5 in all (about two rare
   words — one ordinary word is not relevance), `:min-share` 0.5 of the best
   match's score (a word the best match shares does not drag in every memory
   that mentions it), `:max` 3.
5. **The hint** (`prompts/memory-hint.edn`): a heading, then each memory's key
   and first line clipped to `:line-chars` (120), and how to read one whole —
   `memory$read` with the name as `:key`. It goes in the turn's
   `user-context` zone, the volatile tail after every cache breakpoint
   (RFC-015), so the cached prefix is untouched.
6. **Every turn, not once a session.** The zone is not carried into the next
   turn's history, so a hint given once is not there to be seen again.

`:memory-hint?` (default on) turns it off; so does `:memory? false`. A tree
given with `--loop` that drops the node has no hint.

## Measurements

### Offline: does the match find the memory a question needs?

25 questions against this project's own store of 17 memories, written the way
a person asks rather than in the memory's words: 17 each needing one memory,
8 needing none. The labels are a reader's judgement, made once.

| setting | found | first | precise | noise | chars |
|---|---|---|---|---|---|
| `min-terms 1` | 16/17 | 12/17 | 16/50 | 12 over 8 | ~416 |
| `min-terms 1, min-share 0.5` | 16/17 | 12/17 | 16/36 | 10 over 8 | ~308 |
| `min-terms 2` | 13/17 | 12/17 | 13/32 | 3 over 8 | ~304 |
| `min-terms 1, max 1` | 12/17 | 12/17 | 12/17 | 5 over 8 | ~146 |
| **`min-terms 1, min-score 4.5, min-share 0.5`** (the tree's) | **13/17** | 12/17 | 13/24 | **1 over 8** | ~232 |

*found*: questions whose memory was hinted; *first*: hinted first; *precise*:
hinted memories that were the one needed; *noise*: memories hinted for
questions needing none; *chars*: the hint's size where there was one.

The four misses are vocabulary, not thresholds: "verify the picker on a real
terminal" shares no word with a memory about tmux. Loosening `min-score`
finds three of them back and hints 7 memories over the 8 questions that need
none. Weighting a key's words more found nothing more and was dropped.

### Live: does a hint change what a model does?

A scratch project whose store holds three facts a model cannot derive — a
deploy freeze, a non-default database port, a rounding rule — among eight;
each asked once in a fresh session, pull only and hinted, through
`claude -p`. The live set is ROT13 in the spike: in plain text, a model found
this repository, which `claude -p` runs in, grepped it and read the answers
there, and pull-only looked as good as a hint for a reason that had nothing
to do with memory. The store's files are outside the scratch project for the
same reason.

| model | mode | memory calls | right | model calls | input tokens |
|---|---|---|---|---|---|
| Haiku | pull only | none, 3 of 3 runs | 0/3 | 3–4 | 85K–123K |
| Haiku | hinted | one `memory$read` each | 3/3 | 2 | 23K–35K |
| Sonnet | pull only | `memory$recall`, 3 of 3 | 3/3 | 2 | 23K–34K |
| Sonnet | hinted | one `memory$read` each | 3/3 | 2 | 23K–34K |

Haiku never pulled: it searched the project's files, found nothing, and said
so, using three to four times the tokens. Told the key, it read it in one call
and answered. Sonnet pulled on its own and the hint changed nothing it needed.
Before the hint named `:key`, every hinted run's first `memory$read` passed
`:name` and spent a call on the error.

Six runs a model, once: a direction, not a rate.

## API

`brainyard.memory`: `keywords`, `rank` (opts `:min-terms :min-score
:min-share :max :exclude :prior`).

`brainyard.session`: the leaf `:session/memory-hint` (node opts above, plus
`:line-chars :refresh-ms`), `memory-changed!`. The session ctx keeps the
listing in `:memory-cache`. The hint reaches the turn through the react
scope's `:in` as `:memory-hint`, which `react/init` appends to `user-context`.

Event `:session.memory/hinted` (`:trace`): `{:keys :matched}`.

Config: `:memory-hint?` (true).

## Invariants

| invariant | enforced by |
|---|---|
| A question's words are lower-case, three letters or more, without stopwords or possessives, stemmed; a memory is kept only past every gate, best first, and a question with nothing to match matches nothing. | `memory-test/a-question-finds-the-memories-it-shares-rare-words-with` |
| A matching question gets the memory's key and first line in the turn's `user-context`, never the rest of it; a question about nothing stored gets no hint; every turn its own; the store listed once a session; on by default, off with `:memory-hint? false`. | `session-test/a-turn-is-told-the-memories-its-question-is-about` |
| A listing's non-text values are not memories (bd's `schema_version`). | `memory-test/a-person-lists-rewrites-and-deletes-through-the-store` |
| The hint sits after every cache breakpoint. | `user-context` is in `llm/volatile-zones` (RFC-015); nothing tests the hint's place in it beyond the session test reading it there |
| What the thresholds buy is what this RFC says. | `dev/brainyard/dev/memory_hint_spike.clj` `report` — a spike, run by hand |

## Known gaps

- **Synonyms.** A question in other words than the memory's is missed (4 of
  17 above). A model's keyword extraction, only when the lexical match is
  empty, would cost a call a turn; not built, and not measured.
- **The thresholds are absolute**, tuned on 17 memories. The prior steadies a
  small store; a store of hundreds may want `min-score` raised. The spike is
  how to tell.
- **A memory is as findable as its words.** `memory$remember` asks for a key
  naming the topic and a first line that says it, because the hint shows
  those two.
