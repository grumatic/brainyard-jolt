#!/bin/sh
# jolt for a GitHub Actions job (.github/workflows/nightly*.yml): the version
# the suite was verified against (dev/jolt-verified.edn), its Linux release
# checked against its own .sha256, and put on the job's PATH ($GITHUB_PATH).
# One script, so each job that runs jolt installs the same one.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
v=$(sed -nE 's/.*:jolt "(v[^"]+)".*/\1/p' "$root/dev/jolt-verified.edn")
name="jolt-${v}-x86_64-linux"
base="https://github.com/jolt-lang/jolt/releases/download/${v}"
curl -fsSL -o "$RUNNER_TEMP/jolt.tgz" "${base}/${name}.tar.gz"
want=$(curl -fsSL "${base}/${name}.tar.gz.sha256" | cut -d' ' -f1)
got=$(sha256sum "$RUNNER_TEMP/jolt.tgz" | cut -d' ' -f1)
if [ -z "$want" ] || [ "$want" != "$got" ]; then
  echo "::error::${name}.tar.gz: checksum ${got}, expected ${want}"
  exit 1
fi
tar xzf "$RUNNER_TEMP/jolt.tgz" -C "$RUNNER_TEMP"
echo "$RUNNER_TEMP/${name}" >> "$GITHUB_PATH"
