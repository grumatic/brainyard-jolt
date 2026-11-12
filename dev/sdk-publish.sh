#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# Release the Agent SDK publicly (RFC-053 "Distribution", byj-ct5): sdk/ as
# this commit has it is copied into the public repo grumatic/brainyard-sdk as
# one commit tagged vX.Y.Z — its files, never this repository's history,
# which is internal — and from that copy the TypeScript package's npm
# tarball, the Python package's wheel and source archive and their
# SHA256SUMS are built and released there, then downloaded again and checked.
#
#   dev/sdk-publish.sh v0.1.0          # everything here, nothing sent: says what it would publish
#   dev/sdk-publish.sh v0.1.0 --yes    # pushes the commit and the tag, and makes the release
#
# Refused before anything is built: a version that the four places it is
# written (package.json, SDK_VERSION in client.ts, pyproject.toml,
# SDK_VERSION in client.py) do not all say; sdk/ not committed; no
# CHANGELOG.md section for it; a README whose install lines name another
# release; a tag the public repo has already. Then jolt test:sdk's tests
# (dev/sdk-test.sh) run unless BYJ_SDK_SKIP_TESTS=1.
#
# BYJ_SDK_KEEP=1 keeps the working directory and says where. The public
# commit and tag are authored by git's user.name at the publisher's GitHub
# no-reply address (BYJ_SDK_AUTHOR_EMAIL to give another), never the address
# this repository's commits carry.
# BYJ_SDK_REPO is the public repo (else grumatic/brainyard-sdk). It must
# exist before --yes: gh repo create grumatic/brainyard-sdk --public.

set -euo pipefail

log() { printf '[sdk-publish] %s\n' "$*"; }
die() { printf '[sdk-publish] %s\n' "$*" >&2; exit 1; }

tag="${1:-}"
yes="${2:-}"
[[ "${tag}" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || die "usage: dev/sdk-publish.sh vX.Y.Z [--yes]"
ver="${BASH_REMATCH[1]}"
[[ -z "${yes}" || "${yes}" == "--yes" ]] || die "usage: dev/sdk-publish.sh vX.Y.Z [--yes]"
command -v gh >/dev/null 2>&1 || die "need gh on PATH"
command -v uv >/dev/null 2>&1 || die "need uv on PATH"
command -v npm >/dev/null 2>&1 || die "need npm on PATH"

root=$(cd "$(dirname "$0")/.." && pwd)
sdk="${root}/sdk"
repo="${BYJ_SDK_REPO:-grumatic/brainyard-sdk}"
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

# --- what is released ------------------------------------------------------------------

said() { # the version FILE says, by the sed expression that finds it
  sed -nE "$2" "$1" | head -1
}
for f in "typescript/package.json|s/^  \"version\": \"([^\"]+)\".*/\1/p" \
         "typescript/src/client.ts|s/^export const SDK_VERSION = \"([^\"]+)\".*/\1/p" \
         "python/pyproject.toml|s/^version = \"([^\"]+)\".*/\1/p" \
         "python/src/byj_agent_sdk/client.py|s/^SDK_VERSION = \"([^\"]+)\".*/\1/p"; do
  file="${f%%|*}"
  v=$(said "${sdk}/${file}" "${f#*|}")
  [[ "${v}" == "${ver}" ]] || die "sdk/${file} says ${v:-no version}, not ${ver}"
done
grep -q "^## ${ver} " "${sdk}/CHANGELOG.md" || die "sdk/CHANGELOG.md has no '## ${ver} …' section"
for f in README.md typescript/README.md python/README.md; do
  grep -q "releases/download/${tag}/" "${sdk}/${f}" || die "sdk/${f}'s install lines do not name ${tag}"
  if grep -oE "releases/download/v[0-9.]+/" "${sdk}/${f}" | grep -vqF "/${tag}/"; then
    die "sdk/${f} names another release beside ${tag}"
  fi
done
src=$(git -C "${root}" rev-parse --short HEAD)
if ! ( cd "${root}"
       git diff --quiet HEAD -- sdk && git diff --cached --quiet -- sdk \
         && [[ -z "$(git ls-files --others --exclude-standard -- sdk)" ]] ); then
  [[ -z "${yes}" ]] || die "sdk/ has changes not committed: a release is a commit's"
  # a dry run may look at what is about to be committed
  log "sdk/ has changes not committed: this dry run copies the working tree's"
  src="${src}+"
fi

exists=1
gh repo view "${repo}" >/dev/null 2>&1 || exists=
if [[ -n "${exists}" ]] && gh release view "${tag}" --repo "${repo}" >/dev/null 2>&1; then
  die "${repo} has ${tag} already"
fi
[[ -n "${exists}" || -z "${yes}" ]] || die "${repo} does not exist: gh repo create ${repo} --public, then again"
# GitHub refuses a push that adds or changes a workflow from a token without
# the workflow scope — after the tests and the build, with nothing said
if [[ -n "${yes}" && -d "${sdk}/.github/workflows" ]] &&
  ! gh api -i user 2>/dev/null | grep -i '^x-oauth-scopes:' | grep -qw workflow; then
  die "gh's token cannot push .github/workflows: gh auth refresh -h github.com -s workflow, then again"
fi

if [[ -z "${BYJ_SDK_SKIP_TESTS:-}" ]]; then
  log "the SDKs' tests (dev/sdk-test.sh)"
  "${root}/dev/sdk-test.sh" || die "the tests failed: nothing published"
fi

# --- the public repo's commit ----------------------------------------------------------

work=$(mktemp -d)
# BYJ_SDK_KEEP=1 keeps it, to look at what a dry run would publish
if [[ -n "${BYJ_SDK_KEEP:-}" ]]; then log "kept: ${work}"; else trap 'rm -rf "${work}"' EXIT; fi
clone="${work}/repo"
if [[ -n "${exists}" ]]; then
  log "cloning ${repo}"
  gh repo clone "${repo}" "${clone}" -- --quiet 2>/dev/null || die "could not clone ${repo}"
else
  log "${repo} does not exist yet: a new repository here, for the dry run"
  git init --quiet "${clone}"
fi
git -C "${clone}" checkout --quiet -B main
# the tree becomes sdk/'s exactly: what was removed there is removed here
find "${clone}" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
if [[ "${src}" == *+ ]]; then
  ( cd "${sdk}" && git ls-files --cached --others --exclude-standard -z . | xargs -0 tar -cf - ) | tar -x -C "${clone}"
else
  git -C "${root}" archive --format=tar HEAD:sdk | tar -x -C "${clone}"
fi
git -C "${clone}" add -A
# authored by the publisher's GitHub no-reply address: the commit is public
name=$(git -C "${root}" config user.name)
email="${BYJ_SDK_AUTHOR_EMAIL:-$(gh api user --jq '"\(.id)+\(.login)@users.noreply.github.com"')}" \
  || die "could not ask GitHub who is publishing: gh auth login"
if git -C "${clone}" diff --cached --quiet 2>/dev/null && git -C "${clone}" rev-parse -q --verify HEAD >/dev/null; then
  log "the tree is the public repo's already: ${tag} tags its head"
else
  git -C "${clone}" -c user.name="${name}" -c user.email="${email}" commit --quiet \
    -m "brainyard-sdk ${ver}" -m "From brainyard-jolt ${src}."
fi
git -C "${clone}" -c user.name="${name}" -c user.email="${email}" tag -a "${tag}" -m "brainyard-sdk ${ver}"

# --- what is released, built from that commit -------------------------------------------

out="${work}/release"
mkdir -p "${out}"
log "building the npm tarball"
( cd "${clone}/typescript" && npm ci --silent --no-audit --no-fund && npm pack --silent --pack-destination "${out}" >/dev/null ) \
  || die "npm pack failed"
log "building the wheel and the source archive"
( cd "${clone}/python" && uv build --quiet --out-dir "${out}" ) || die "uv build failed"
want=("grumatic-byj-agent-sdk-${ver}.tgz" "byj_agent_sdk-${ver}-py3-none-any.whl" "byj_agent_sdk-${ver}.tar.gz")
for f in "${want[@]}"; do [[ -f "${out}/${f}" ]] || die "no ${f} was built"; done
( cd "${out}" && sha256 "${want[@]}" > SHA256SUMS )
awk -v v="${ver}" '$0 ~ "^## " v " " { on = 1; next } on && /^## / { exit } on' "${clone}/CHANGELOG.md" > "${work}/notes"
cat >> "${work}/notes" <<EOF

Install (byj itself: brew install grumatic/tap/byj):

    npm install https://github.com/${repo}/releases/download/${tag}/${want[0]}
    uv add https://github.com/${repo}/releases/download/${tag}/${want[1]}
EOF

log "${tag} from brainyard-jolt ${src}: $(git -C "${clone}" log -1 --format='%h %s')"
( cd "${out}" && sed 's/^/  /' SHA256SUMS )
if [[ -z "${yes}" ]]; then
  log "nothing sent: --yes pushes main and ${tag} to ${repo} and releases these files"
  exit 0
fi

# --- out ---------------------------------------------------------------------------------

log "pushing main and ${tag} to ${repo}"
# as gh's account over https, whatever the clone used: gh may clone over ssh,
# and the ssh key git picks for github.com may be another account's
login=$(gh api user --jq .login) || die "could not ask GitHub who is publishing: gh auth login"
git -C "${clone}" -c credential.helper= -c credential.helper='!gh auth git-credential' \
  push --quiet "https://${login}@github.com/${repo}.git" main "refs/tags/${tag}"
log "releasing ${tag}"
gh release create "${tag}" --repo "${repo}" --verify-tag --title "brainyard-sdk ${ver}" \
  --notes-file "${work}/notes" "${out}/SHA256SUMS" "${want[@]/#/${out}/}" >/dev/null

check="${work}/check"
mkdir -p "${check}"
gh release download "${tag}" --repo "${repo}" --dir "${check}"
( cd "${check}" && sha256 -c SHA256SUMS >/dev/null ) || die "${repo}'s ${tag} does not match what was built"
cmp -s "${check}/SHA256SUMS" "${out}/SHA256SUMS" || die "${repo}'s ${tag} SHA256SUMS is not the one built"
log "released: https://github.com/${repo}/releases/tag/${tag}"
