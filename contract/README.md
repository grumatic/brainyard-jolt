# byj's contract

What byj gives a program that reaches it as a process, a socket or a file,
recorded from byj itself (RFC-070 phase 2). A program built on byj — Share
is one — keeps a copy of these files and tests what it reads against its copy,
so it never reads byj's tree, and byj's own suite never reads its tree either.

| File | What it is | Its RFC |
|---|---|---|
| `session/status.edn` | the session socket's reply to `{:op :status}` | RFC-031 |
| `session/frames.edn` | the frames a `{:op :subscribe}` streams while one turn reads a file and answers, a frame a line | RFC-031 |
| `session/ask.edn` | the reply to that turn's `{:op :ask}` | RFC-031 |
| `trajectory/trajectory.edn` | what the turn leaves in `.brainyard/sessions/<id>/trajectory.edn`, a record a line | RFC-025 |
| `trajectory/meta.edn` | the session's `meta.edn` beside it | RFC-016, RFC-031 |
| `agent/agents.json` | `byj agent --json` in a project of byj's own agents | RFC-018 |
| `config/config.json` | `byj config --json` in a project whose config.edn sets a key and holds one byj does not know, with a home of its own; the default directories a run may write, which name the machine's, written `<varies>` | RFC-013 |
| `deps/deps.json` | `byj deps --json` in a project that depends on one project found by its path and one found nowhere, with two roles, a session bound to one and one gone, a handoff ready but for its branch, and bd's answer given | RFC-075 |
| `doctor/doctor.json` | `byj doctor --json` in a project with AGENTS.md alone, two copies of byj on PATH, a signed-in account, and every tool said missing so that it is not the machine's | RFC-040 |
| `prices.edn` | the price table byj costs a run by | RFC-006 |
| `loops/slots.edn` | every slot the bundled loops offer a project: its id, keys and place | RFC-072 |

What varies between two runs is written `<varies>` (times, pids, durations),
`<sid>` (a session id), `<dir>` (the project's directory) and `<socket>` (the
socket's path); the words of a prompt sent to the model are written
`<prompt>`, since their shape is the contract and their wording is byj's to
change.

`test/brainyard/contract_test.clj` holds each file to what byj gives now. A
change to one is a change to the contract:

    BYJ_CONTRACT_RECORD=1 jolt -M:test brainyard.contract-test

writes them again; review the diff, then tell the programs that keep a copy.
In this repository Share's copy is `share/testdata/byj-contract/`, which
`dev/byj-contract.sh check` compares with this directory and `pull` refreshes.
