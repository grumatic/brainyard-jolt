#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# Check a release against its own SHA256SUMS (RFC-029, byj-gly.181.4) —
# brainyard's bin/release-verify.sh.
#
#   dev/release-verify.sh --dir release/   # what is staged, before publishing
#   dev/release-verify.sh v0.2.0           # a published release, downloaded
#   dev/release-verify.sh                  # the newest published release
#
# Every file SHA256SUMS names must be there and match, and every byj-*
# asset must be in SHA256SUMS: brainyard shipped a release whose manifest
# named an asset that was never uploaded, and every user's check failed.
#
# A published release is read with gh, which carries a private repo's auth;
# the repo is BYJ_RELEASE_REPO, else origin's owner/name.

set -euo pipefail

log() { printf '[release-verify] %s\n' "$*"; }
die() { printf '[release-verify] %s\n' "$*" >&2; exit 1; }

check() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -c "$@"; else shasum -a 256 -c "$@"; fi
}

repo() {
  if [[ -n "${BYJ_RELEASE_REPO:-}" ]]; then echo "${BYJ_RELEASE_REPO}"; return; fi
  local url
  url="$(git remote get-url origin 2>/dev/null)" \
    || die "no origin remote — set BYJ_RELEASE_REPO=owner/name"
  echo "${url}" | sed -E 's#^(git@[^:]+:|https://[^/]+/)##; s#\.git$##; s#^/##'
}

if [[ "${1:-}" == "--dir" ]]; then
  dir="${2:?usage: dev/release-verify.sh --dir <path>}"
  [[ -d "${dir}" ]] || die "not a directory: ${dir}"
  label="${dir}"
else
  command -v gh >/dev/null 2>&1 || die "need gh on PATH (it carries the private repo's auth)"
  r="$(repo)"
  tag="${1:-$(gh release view --repo "${r}" --json tagName --jq .tagName)}"
  dir="$(mktemp -d)"
  trap 'rm -rf "${dir}"' EXIT
  log "downloading ${tag} from ${r}"
  gh release download "${tag}" --repo "${r}" --dir "${dir}" --clobber >/dev/null
  label="${tag}"
fi

[[ -f "${dir}/SHA256SUMS" ]] || die "${label} has no SHA256SUMS"

missing=0
while read -r _ name; do
  [[ -f "${dir}/${name}" ]] || { printf '[release-verify] missing: %s\n' "${name}" >&2; missing=1; }
done < "${dir}/SHA256SUMS"
[[ ${missing} -eq 0 ]] || die "${label}: SHA256SUMS names assets that are not there"

for f in "${dir}"/byj-*; do
  [[ -e "${f}" ]] || die "${label} has no byj-* asset"
  grep -q " $(basename "${f}")\$" "${dir}/SHA256SUMS" \
    || die "${label}: $(basename "${f}") is not in SHA256SUMS"
done

(cd "${dir}" && check SHA256SUMS) || die "${label}: a checksum does not match"
log "${label}: every asset is there and matches"
