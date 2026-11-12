#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# Copy a published release of the source repo to the public tap, as a
# release of the same tag with the same assets and notes, so that anyone can
# download byj with no GitHub token (RFC-029 "Homebrew", byj-gly.181.10). The
# formula (dev/brew-formula.sh) names the tap's copies. The copy is made the
# tap's latest release, which is what `byj update` reads: other programs'
# releases on the tap are not.
#
#   dev/release-mirror.sh v0.3.0                 # then dev/brew-formula.sh v0.3.0 <tap checkout>
#
# The assets are checked against the release's own SHA256SUMS before anything
# is uploaded, and the tap's release is checked against it afterwards
# (dev/release-verify.sh) — a mirror that differs from its source is worse
# than none. A tap release that exists already is completed, not replaced:
# an asset it has is left as it is, after its checksum is checked.
#
# BYJ_RELEASE_REPO is the source (else origin's owner/name), BYJ_TAP_REPO the
# tap (else grumatic/homebrew-tap). BYJ_SOURCE_TOKEN and BYJ_TAP_TOKEN, when
# set, are the GH_TOKEN for the reads and for the writes: the release workflow
# reads with its own token and writes with one that can push to the tap.

set -euo pipefail

log() { printf '[release-mirror] %s\n' "$*"; }
die() { printf '[release-mirror] %s\n' "$*" >&2; exit 1; }

tag="${1:-}"
[[ "${tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: dev/release-mirror.sh vX.Y.Z"
command -v gh >/dev/null 2>&1 || die "need gh on PATH"

src() {
  if [[ -n "${BYJ_RELEASE_REPO:-}" ]]; then echo "${BYJ_RELEASE_REPO}"; return; fi
  local url
  url="$(git remote get-url origin 2>/dev/null)" \
    || die "no origin remote — set BYJ_RELEASE_REPO=owner/name"
  echo "${url}" | sed -E 's#^(git@[^:]+:|https://[^/]+/)##; s#\.git$##; s#^/##'
}
from="$(src)"
to="${BYJ_TAP_REPO:-grumatic/homebrew-tap}"
[[ "${from}" != "${to}" ]] || die "the source and the tap are both ${to}"

# gh as the reader or the writer, with that token when one is given
read_gh() { if [[ -n "${BYJ_SOURCE_TOKEN:-}" ]]; then GH_TOKEN="${BYJ_SOURCE_TOKEN}" gh "$@"; else gh "$@"; fi; }
write_gh() { if [[ -n "${BYJ_TAP_TOKEN:-}" ]]; then GH_TOKEN="${BYJ_TAP_TOKEN}" gh "$@"; else gh "$@"; fi; }

dir="$(mktemp -d)"
trap 'rm -rf "${dir}"' EXIT

log "downloading ${from}'s ${tag}"
read_gh release download "${tag}" --repo "${from}" --dir "${dir}" \
  || die "${from} has no release ${tag}"
[[ -f "${dir}/SHA256SUMS" ]] || die "${tag} has no SHA256SUMS"
title="$(read_gh release view "${tag}" --repo "${from}" --json name --jq .name)"
read_gh release view "${tag}" --repo "${from}" --json body --jq .body > "${dir}.notes"
trap 'rm -rf "${dir}" "${dir}.notes"' EXIT

# every asset SHA256SUMS lists is here and is what it says
( cd "${dir}"
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -c --quiet SHA256SUMS; else shasum -a 256 -c --quiet SHA256SUMS; fi
) || die "${tag}'s assets do not match its SHA256SUMS — not mirrored"

if write_gh release view "${tag}" --repo "${to}" >/dev/null 2>&1; then
  have="$(write_gh release view "${tag}" --repo "${to}" --json assets --jq '.assets[].name')"
  missing=()
  for f in "${dir}"/*; do
    n="$(basename "${f}")"
    grep -qxF "${n}" <<<"${have}" || missing+=("${f}")
  done
  if (( ${#missing[@]} )); then
    log "${to} has ${tag} already; uploading the ${#missing[@]} asset(s) it lacks"
    write_gh release upload "${tag}" "${missing[@]}" --repo "${to}"
  else
    log "${to} has ${tag} with every asset already"
  fi
else
  log "creating ${to}'s ${tag}"
  write_gh release create "${tag}" "${dir}"/* --repo "${to}" \
    --title "${title:-byj ${tag}}" --notes-file "${dir}.notes" --latest
fi

log "checking ${to}'s ${tag} against its SHA256SUMS"
if [[ -n "${BYJ_TAP_TOKEN:-}" ]]; then
  BYJ_RELEASE_REPO="${to}" GH_TOKEN="${BYJ_TAP_TOKEN}" "$(dirname "$0")/release-verify.sh" "${tag}"
else
  BYJ_RELEASE_REPO="${to}" "$(dirname "$0")/release-verify.sh" "${tag}"
fi
log "mirrored ${from}'s ${tag} to ${to}"
