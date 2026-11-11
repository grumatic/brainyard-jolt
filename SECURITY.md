# Security

## Reporting a vulnerability

Report it privately through GitHub's private vulnerability reporting:
[open a report](https://github.com/grumatic/brainyard-jolt/security/advisories/new).
Please do not open a public issue or pull request for it.

Say what an attacker can do, on which version (`byj --version`) and
platform, and how to reproduce it. A maintainer answers within 5 working
days, and keeps you told until it is fixed or decided not to be a
vulnerability. A fix is released first and the advisory published with it,
crediting you unless you ask otherwise.

## What counts

byj runs a model's tool calls on your machine, so the boundaries it keeps
are the ones that matter most:

- the permission gate and its modes, which decide what runs without asking
  (RFC-012);
- the paths no tool reads, where logins and keys are kept (`:denied-paths`,
  RFC-012);
- the OS sandbox on macOS (RFC-012);
- Tool Lisp, which runs agent-written tools without host `eval` (RFC-010);
- the session socket and `byj acp`, which other programs speak to (RFC-031,
  RFC-053);
- credentials read from the environment and `.env` files, which byj must
  never print or log.

A model that is talked into a harmful action by a prompt is a vulnerability
in byj when byj's own boundary let the action through; a model's words alone
are not.

## Supported versions

Fixes are made on the latest release. `byj update` installs it.
