#!/bin/sh
# byj acp against ACP clients it did not write (RFC-053, byj-g5n.4): the
# official TypeScript client (@agentclientprotocol/sdk, pinned in
# dev/acp-interop/package.json), and brainyard's own (../brainyard
# components/acp-client, on the JVM) when ../brainyard is there. No model is
# spent: byj runs a scripted mock. Network on the first run (npm install).
# Not part of `jolt test:byj`.
#
#   dev/acp-interop.sh            # byj from this checkout (jolt -m brainyard.main)
#   dev/acp-interop.sh --binary   # the byj on PATH
set -eu
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/acp-interop"
[ -d node_modules ] || npm install --silent --no-audit --no-fund
if [ "${1:-}" = "--binary" ]; then argv='["byj"]'; else argv='["jolt","-m","brainyard.main"]'; fi
echo "== the official TypeScript client"
node official-client.mjs "$argv"
repo=$(cd "$here/.." && pwd)
if [ -d "$repo/../brainyard/components/acp-client" ] && command -v clojure >/dev/null; then
  echo "== brainyard's client"
  if [ "${1:-}" = "--binary" ]; then bargv='["byj"]'; else bargv='["jolt" "-m" "brainyard.main"]'; fi
  (cd "$repo/../brainyard" && clojure -M:dev "$here/acp-interop/brainyard-client.clj" "$bargv" "$repo" 2>&1 \
     | grep -v "mulog\|^SLF4J\|WARNING")
else
  echo "== brainyard's client: skipped (no ../brainyard or no clojure)"
fi
