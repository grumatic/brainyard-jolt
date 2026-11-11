#!/bin/sh
# The SDK's client libraries against byj from this checkout (RFC-053): the
# TypeScript SDK's tests (sdk/typescript), and the Python SDK's when it is
# there (sdk/python). No model is spent: byj runs a scripted mock. Network on
# the first run (npm install, uv sync). Not part of `jolt test:byj`.
# BYJ_BIN=byj runs them against an installed binary.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
status=0

echo "== sdk/typescript"
(cd "$root/sdk/typescript" && { [ -d node_modules ] || npm ci --silent --no-audit --no-fund; } && npm test --silent) || status=1

if [ -f "$root/sdk/python/pyproject.toml" ]; then
  echo "== sdk/python"
  (cd "$root/sdk/python" && uv run --quiet --extra test pytest -q) || status=1
fi

exit $status
