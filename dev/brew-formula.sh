#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# Write the Homebrew formula for a published release (RFC-029 "Homebrew",
# byj-gly.181.5): byj's, with the assets the release has for macOS (arm64,
# Intel) and Linux (amd64), into the tap checkout at OUT. Other formulae in
# the tap are their own programs' and are left as they are.
#
#   dev/brew-formula.sh v0.3.0 ../homebrew-tap     # then commit and push the tap
#
# The assets are the release's copy on the public tap (dev/release-mirror.sh,
# byj-gly.181.10): the formula fetches each by its browser URL, with no
# token, so anyone can install. It is written from the published release,
# never from release/: what is uploaded is what is named.
#
# Each asset's sha256 comes from the release's own SHA256SUMS, and a platform
# the release has no asset for is left out of the formula rather than guessed.
#
# BYJ_TAP_REPO overrides the tap (else grumatic/homebrew-tap), and
# BYJ_RELEASE_ASSETS / BYJ_RELEASE_SUMS name files that stand in for the
# release's `id name` lines and its SHA256SUMS (the tests use them).

set -euo pipefail

log() { printf '[brew-formula] %s\n' "$*"; }
die() { printf '[brew-formula] %s\n' "$*" >&2; exit 1; }

tag="${1:-}"
out="${2:-}"
[[ "${tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: dev/brew-formula.sh vX.Y.Z TAP-DIR"
[[ -n "${out}" ]] || die "usage: dev/brew-formula.sh vX.Y.Z TAP-DIR"
v="${tag#v}"

r="${BYJ_TAP_REPO:-grumatic/homebrew-tap}"

if [[ -n "${BYJ_RELEASE_ASSETS:-}" ]]; then
  assets="$(<"${BYJ_RELEASE_ASSETS}")"
  sums="$(<"${BYJ_RELEASE_SUMS:?BYJ_RELEASE_SUMS goes with BYJ_RELEASE_ASSETS}")"
else
  command -v gh >/dev/null 2>&1 || die "need gh on PATH"
  assets="$(gh api "repos/${r}/releases/tags/${tag}" --jq '.assets[] | "\(.id) \(.name)"')" \
    || die "${r} has no release ${tag}"
  sums="$(gh release download "${tag}" --repo "${r}" --pattern SHA256SUMS --output -)" \
    || die "${tag} has no SHA256SUMS"
fi

id_of() { awk -v n="$1" '$2 == n { print $1; exit }' <<<"${assets}"; }
sha_of() { awk -v n="$1" '{ f = $2; sub(/^\*/, "", f) } f == n { print $1; exit }' <<<"${sums}"; }

# One platform's url and sha256, or nothing when the release has no such asset.
# A listed asset with no checksum is a broken release, not a missing platform.
platform_block() {
  local name="$1-${v}-$2" indent="$3" id sha
  id="$(id_of "${name}")"
  [[ -n "${id}" ]] || return 0
  sha="$(sha_of "${name}")"
  [[ -n "${sha}" ]] || die "${tag}'s SHA256SUMS does not list ${name}"
  printf '%surl "https://github.com/%s/releases/download/%s/%s"\n' "${indent}" "${r}" "${tag}" "${name}"
  printf '%ssha256 "%s"\n' "${indent}" "${sha}"
}

# `on_macos do on_arm do … end end`, with only the platforms that have an asset.
os_block() {
  local bin="$1" os="$2" arm intel
  arm="$(platform_block "${bin}" "${os}-arm64" "      ")"
  intel="$(platform_block "${bin}" "${os}-amd64" "      ")"
  [[ -n "${arm}${intel}" ]] || return 0
  printf '  on_%s do\n' "${os}"
  # byj loads OpenSSL at run time from Homebrew's openssl@3 on macOS
  # (deps.edn :jolt/native, provenance P-36)
  [[ "${os}" != macos ]] || printf '    depends_on "openssl@3"\n'
  [[ -z "${arm}" ]] || printf '    on_arm do\n%s\n    end\n' "${arm}"
  [[ -z "${intel}" ]] || printf '    on_intel do\n%s\n    end\n' "${intel}"
  printf '  end\n'
}

formula() {
  local bin="$1" class="$2" desc="$3" says="$4" cmd="$5" macos linux
  macos="$(os_block "${bin}" macos)"
  linux="$(os_block "${bin}" linux)"
  [[ -n "${macos}${linux}" ]] || die "${tag} has no ${bin}-${v}-* asset"
  {
    printf '# Written by byj'"'"'s dev/brew-formula.sh from %s'"'"'s %s release — regenerate it, do not edit.\n' "${r}" "${tag}"
    printf 'class %s < Formula\n' "${class}"
    printf '  desc "%s"\n' "${desc}"
    printf '  homepage "https://github.com/%s"\n' "${r}"
    printf '  version "%s"\n' "${v}"
    printf '\n'
    [[ -z "${macos}" ]] || printf '%s\n' "${macos}"
    [[ -z "${linux}" ]] || printf '%s\n' "${linux}"
    printf '\n'
    printf '  def install\n'
    printf '    bin.install Dir["*"].first => "%s"\n' "${bin}"
    printf '  end\n'
    printf '\n'
    printf '  test do\n'
    printf '    assert_match "%s", shell_output("#{bin}/%s")\n' "${says}" "${cmd}"
    printf '  end\n'
    printf 'end\n'
  } > "${out}/Formula/${bin}.rb"
  log "wrote ${out}/Formula/${bin}.rb"
}

mkdir -p "${out}/Formula"
formula byj Byj "brainyard's agent runtime on jolt" "byj v${v} " "byj --version"
