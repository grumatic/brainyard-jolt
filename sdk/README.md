# Brainyard Agent SDK

byj's agent as a part of your application, from TypeScript or Python. Open a
session in a project, send prompts, stream what happens, answer permission
prompts, give the agent tools that run in your process and hooks that see or
decide its tool calls, and call a predictor for structured output.

| package | language | guide |
|---|---|---|
| `@grumatic/byj-agent-sdk` | TypeScript, Node 20+ | [typescript/README.md](typescript/README.md) |
| `byj-agent-sdk` | Python 3.10+, asyncio, no dependencies | [python/README.md](python/README.md) |

An agent someone shared on Brainyard Share is called from anywhere with
`connectShared(url, token)` (`connect_shared` in Python): the endpoint and the
token its owner made for your application, spoken to over A2A. Its
`keepsQuestions` (`keeps_questions`) is true while the share keeps what its
callers ask and its answers for its owner to read, as its card says: an
application asking for people who never see the share's page tells them.

Underneath, the SDK starts `byj acp` — byj as an [Agent Client
Protocol](https://agentclientprotocol.com) agent on its stdio — and speaks
ACP v1 with byj's extensions to it. A session it opens is an ordinary byj
session: the project's `.brainyard/` (agents, predictors, prompts, hooks,
config) is the agent's behaviour.

## Install

byj itself, which the SDK runs and does not carry:

```sh
brew tap grumatic/tap
brew install grumatic/tap/byj
```

The SDK, from this repository's release (each release lists its files and
their SHA-256 sums):

```sh
# TypeScript
npm install https://github.com/grumatic/brainyard-sdk/releases/download/v0.1.0/grumatic-byj-agent-sdk-0.1.0.tgz

# Python
uv add https://github.com/grumatic/brainyard-sdk/releases/download/v0.1.0/byj_agent_sdk-0.1.0-py3-none-any.whl
# or from git
uv add "byj-agent-sdk @ git+https://github.com/grumatic/brainyard-sdk@v0.1.0#subdirectory=python"
```

The packages are not on npm or PyPI. A lockfile records the release file's
URL and hash; to move to a newer SDK, install the newer release's URL.

## Versions

The SDK does not pin byj's version. When it starts byj it asks what byj
offers — ACP v1 and the extensions it needs — and refuses a byj that lacks
one, saying which and to run `byj update`. Each release says what changed in
[CHANGELOG.md](CHANGELOG.md).

## This repository

The SDK is developed with byj, in Grumatic's byj repository, and tested there
against byj's own source; each release is copied here as one commit with its
tag. Issues and questions are welcome here.

`.github/workflows/test.yml` runs both SDKs' tests against the newest byj from
the Homebrew tap, on a scripted mock model: no model is called and nothing is
spent. To run them yourself, with byj installed:

```sh
cd typescript && npm ci && BYJ_BIN=$(command -v byj) npm test
cd python && BYJ_BIN=$(command -v byj) uv run --extra test pytest -q
```

## License

MIT — see [LICENSE](LICENSE).
