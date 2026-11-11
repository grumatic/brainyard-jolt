#!/usr/bin/env bash
# Stage this platform's byj into release/ for a GitHub Release (RFC-029,
# byj-gly.181.4) — brainyard's bin/release-stage.sh, for a jolt binary. A
# release is byj's alone: programs built on byj are released on their own
# (RFC-070).
#
#   jolt build:byj && dev/release-stage.sh
#
# Writes release/ (gitignored):
#   byj-<version>-<os>-<arch>   the binary (e.g. byj-0.2.0-macos-arm64)
#   SHA256SUMS                  over every asset of the version staged
#   BUILD-INFO.txt              version, commit, branch, build time
#
# The release workflow (.github/workflows/release.yml) runs this on each
# platform's runner and publishes what they staged together.
#
# jolt builds for the platform it runs on, so each platform's binary is staged
# on its own machine: assets of the SAME version already in release/ (copied in
# from another machine) are kept and checksummed with this one; anything of
# another version is removed, since its name would put a stale binary beside
# the new ones. The asset names are what `byj update` looks for — do not
# rename one without the other.
#
# Refuses — so a misleading version never ships — when the stamp is `dev`,
# when the tree was dirty, when HEAD has commits past its tag, or when the
# binary does not say the version the stamp does (built, then re-stamped).
#
# BYJ_ROOT and BYJ_RELEASE_DIR override where it reads and writes (the
# tests use them).

set -euo pipefail

ROOT="${BYJ_ROOT:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
BIN="${ROOT}/target/byj"
STAMP="${ROOT}/resources/brainyard/build-version.edn"
RELEASE_DIR="${BYJ_RELEASE_DIR:-${ROOT}/release}"

log() { printf '[release-stage] %s\n' "$*"; }
die() { printf '[release-stage] %s\n' "$*" >&2; exit 1; }

platform() {
  local os arch
  case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux) os=linux ;;
    *) die "unsupported OS: $(uname -s)" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    arm64|aarch64) arch=arm64 ;;
    *) die "unsupported arch: $(uname -m)" ;;
  esac
  echo "${os}-${arch}"
}

# a key of the stamp, e.g. :version "v0.2.0" → v0.2.0
stamp_value() {
  awk -v k=":$1" '{ i = index($0, k " "); if (i) { s = substr($0, i + length(k) + 1);
                    sub(/^"/, "", s); sub(/[",} ].*$/, "", s); print s; exit } }' "${STAMP}"
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

[[ -f "${STAMP}" ]] || die "no ${STAMP} — run 'jolt build:byj' first"
[[ -x "${BIN}" ]] || die "no ${BIN} — run 'jolt build:byj' first"

version="$(stamp_value version)"
[[ -n "${version}" ]] || die "no :version in ${STAMP}"
case "${version}" in
  dev) die "the stamp says 'dev' — was it built outside a git checkout?" ;;
  *-dirty) die "${version}: the tree had uncommitted changes — commit, then 'jolt build:byj' again" ;;
  *-g[0-9a-f]*) die "${version}: HEAD has commits past the tag — tag it (git tag vX.Y.Z), then 'jolt build:byj' again" ;;
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) die "${version} is not a vX.Y.Z tag" ;;
esac
[[ "$(stamp_value 'dirty?')" != "true" ]] || die "${version}: the stamp says the tree was dirty"

said="$("${BIN}" --version 2>/dev/null || true)"
case "${said}" in
  *" ${version} "*|*" ${version}") ;;
  *) die "the binary says '${said}', the stamp ${version} — rebuild with 'jolt build:byj'" ;;
esac

v="${version#v}"
asset="byj-${v}-$(platform)"
mkdir -p "${RELEASE_DIR}"
# another version's files, or another program's, would ship beside this
# one's: they go
find "${RELEASE_DIR}" -maxdepth 1 -type f ! -name "byj-${v}-*" -delete
cp -f "${BIN}" "${RELEASE_DIR}/${asset}"
chmod 755 "${RELEASE_DIR}/${asset}"

(cd "${RELEASE_DIR}" && sha256 byj-"${v}"-* > SHA256SUMS)
{
  echo "version: ${version}"
  echo "commit: $(stamp_value sha)"
  echo "branch: $(stamp_value branch)"
  echo "committed: $(stamp_value commit-time)"
  echo "built: $(stamp_value built-at)"
  echo "assets: $(cd "${RELEASE_DIR}" && ls byj-"${v}"-* | tr '\n' ' ')"
} > "${RELEASE_DIR}/BUILD-INFO.txt"

log "staged ${asset} into ${RELEASE_DIR}"
(cd "${RELEASE_DIR}" && cat SHA256SUMS)
