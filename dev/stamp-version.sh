#!/usr/bin/env bash
# Stamp resources/brainyard/build-version.edn with this build's git provenance
# (RFC-029) — brainyard's `bb version:ata`. `jolt build:byj` runs it first, so the
# binary embeds the version it was built from and `byj --version` names the
# exact commit. Gitignored: the stamp is a build output, not source.
#
#   dev/stamp-version.sh        (jolt version:byj)
set -euo pipefail
cd "$(dirname "$0")/.."

g() { git "$@" 2>/dev/null || true; }
version=$(g describe --tags --match 'v[0-9]*' --always --dirty); version=${version:-dev}
sha=$(g rev-parse HEAD); sha=${sha:-unknown}
branch=$(g rev-parse --abbrev-ref HEAD); branch=${branch:-unknown}
committed=$(g show -s --format=%cI HEAD); committed=${committed:-unknown}
if [ -n "$(g status --porcelain)" ]; then dirty=true; else dirty=false; fi
built=$(date -u +%Y-%m-%dT%H:%M:%SZ)

out=resources/brainyard/build-version.edn
printf '{:version "%s" :sha "%s" :sha-short "%s" :branch "%s" :commit-time "%s" :dirty? %s :built-at "%s"}\n' \
  "$version" "$sha" "${sha:0:7}" "$branch" "$committed" "$dirty" "$built" > "$out"
echo "stamped $out → $version (${sha:0:7})"
