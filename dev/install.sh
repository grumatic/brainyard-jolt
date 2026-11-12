#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# Install the built binary as `byj` on the PATH — brainyard's
# `bb install:ata`, for the port (RFC-029).
#
#   jolt build:byj && jolt install:byj          # build, then install
#   BY_INSTALL_DIR=/usr/local/bin jolt install:byj
#
# Into ~/.local/bin, or BY_INSTALL_DIR. What brainyard's installs and this does
# not: a wrapper that sources the nearest .env (byj reads it itself —
# launch/find-dotenv, walking up from the working directory, then
# ~/.brainyard/.env), and an uberjar (there is no JVM build).
#
# The copy goes in beside the old one and is renamed into place, the old one
# kept as byj.prev — the way `byj update` installs a release, so
# `byj update --rollback` undoes an install too, and there is never a
# moment with no binary. A rename, not a copy over the file: on macOS a copy
# over a signed binary that is in use is killed at launch by the kernel
# (brainyard's install:ata re-signs for the same reason; a fresh file from
# `jolt build` runs as copied, checked 2026-10-09).
#
# Refused when there is no target/byj; when the binary is not built from
# the checkout's commit, it says so — `jolt build:byj` first installs this one.

set -euo pipefail

log() { printf '[install] %s\n' "$*"; }
die() { printf '[install] %s\n' "$*" >&2; exit 1; }

cd "$(dirname "$0")/.."

src=target/byj
[[ -x "${src}" ]] || die "no ${src} — build it first: jolt build:byj"

dir="${BY_INSTALL_DIR:-${HOME}/.local/bin}"
dest="${dir}/byj"

# the binary says which commit it was built from: `byj vX (sha, branch, time)`
built="$("${src}" --version 2>/dev/null | sed -nE 's/.*\(([0-9a-f]+),.*/\1/p')" \
  || die "${src} does not run: ${src} --version failed"
head="$(git rev-parse --short HEAD 2>/dev/null || true)"
if [[ -n "${built}" && -n "${head}" && "${built}" != "${head}"* && "${head}" != "${built}"* ]]; then
  log "note: ${src} was built from ${built}; the checkout is at ${head} — jolt build:byj first to install that"
fi

mkdir -p "${dir}"
cp -f "${src}" "${dest}.new"
chmod +x "${dest}.new"
"${dest}.new" --version >/dev/null || { rm -f "${dest}.new"; die "the copy does not run; ${dest} left as it was"; }
[[ -e "${dest}" ]] && mv -f "${dest}" "${dest}.prev"
mv -f "${dest}.new" "${dest}"

log "installed: ${dest} — $("${dest}" --version)"
[[ -e "${dest}.prev" ]] && log "the one it replaced is ${dest}.prev (byj update --rollback swaps them back)"
case ":${PATH}:" in
  *":${dir}:"*) ;;
  *) log "${dir} is not on PATH — add it: export PATH=\"${dir}:\$PATH\"" ;;
esac
