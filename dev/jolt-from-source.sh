#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# jolt built from its source for this Linux machine, for a platform jolt
# publishes no build of — aarch64 Linux, for byj's linux-arm64 release
# (byj-gly.181.8). The jolt dev/jolt-verified.edn names, built exactly as
# jolt's own release builds its x86_64 Linux one: its Chez (the version and
# patches its release workflow uses), then ci/glibc-floor-build.sh inside a
# manylinux2014 container for this architecture, so the binary needs glibc
# 2.17 and nothing beyond it.
#
#   dev/jolt-from-source.sh OUT     # OUT/jolt, and a smoke test of it
#
# Needs git and docker. JOLT_SRC_WORK is where the sources are cloned
# (default a temporary directory, removed after). Measured on an Apple
# silicon Mac's Docker: under three minutes, Chez's build most of it.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:?usage: dev/jolt-from-source.sh OUT}"
log() { printf '[jolt-from-source] %s\n' "$*" >&2; }
die() { printf '[jolt-from-source] %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "builds for the Linux machine it runs on, not $(uname -s)"
v=$(sed -nE 's/.*:jolt "(v[^"]+)".*/\1/p' "$root/dev/jolt-verified.edn")
[ -n "$v" ] || die "dev/jolt-verified.edn names no jolt"
arch=$(uname -m)
image="quay.io/pypa/manylinux2014_${arch}"

work="${JOLT_SRC_WORK:-$(mktemp -d)}"
[ -n "${JOLT_SRC_WORK:-}" ] || trap 'rm -rf "$work" 2>/dev/null || true' EXIT  # the container's files are root's
mkdir -p "$work" "$out"

log "jolt ${v} for ${arch}-linux, in ${image}"
git clone -q --depth 1 --branch "$v" --recurse-submodules --shallow-submodules \
  https://github.com/jolt-lang/jolt.git "$work/jolt"
# the Chez jolt's release builds its Linux binary on, read from that workflow
# rather than written here, so a jolt that moves to another Chez brings it
chez=$(grep -m1 -oE -- '--branch v[0-9]+\.[0-9]+\.[0-9]+ --recurse-submodules' \
  "$work/jolt/.github/workflows/release.yml" | awk '{print $2}')
[ -n "$chez" ] || die "jolt ${v}'s release workflow names no Chez Scheme tag"
log "Chez Scheme ${chez}, with jolt's patches"
git clone -q --depth 1 --branch "$chez" --recurse-submodules --shallow-submodules \
  https://github.com/cisco/ChezScheme.git "$work/chez-src"
sh "$work/jolt/ci/apply-chez-patches.sh" "$work/chez-src" >&2

mkdir -p "$work/chez-opt"
docker run --rm \
  -v "$work/jolt":/src -v "$work/chez-src":/chez-src:ro -v "$work/chez-opt":/opt/chez \
  -w /src -e JOLT_VERSION="$v" -e STATIC_DEPS=1 \
  "$image" sh ci/glibc-floor-build.sh >&2

cp -f "$work/jolt/target/release/jolt" "$out/jolt"
[ "$("$out/jolt" -e '(reduce + (range 10))')" = 45 ] || die "the jolt built does not run"
log "${out}/jolt: $(file -b "$out/jolt" | cut -d, -f1-2)"
