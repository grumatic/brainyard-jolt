# RFC-029 — Versions and updates

**Status:** split by RFC-070's phase 2 (`byj-ahf.8`): what is byj's is
here, and since `byj-ahf.9` a release is byj's alone — a program built on
byj is released, versioned and updated by itself. The current version is implemented (`byj-gly.181.1`); where
"latest" comes from is decided — the private repo's GitHub Releases, read with
a token (`byj-gly.181.2`, below); the release process is built
(`byj-gly.181.4`, "Releasing") and the first release published, v0.2.0
(`byj-gly.181.6`);
`byj update` checks for, installs and rolls back a release (`byj-gly.181.3`).

## Purpose

Specifies which version of brainyard-jolt is running and how that is known,
said and recorded — and, once there is a release channel, how a newer one is
found and installed. brainyard sources: `bb.edn` `version:ata` (the stamp),
`projects/agent-tui-app/.../main.clj` `build-info` / `app-version` /
`app-version-long`, `bin/release-stage.sh`, `bin/release-verify.sh`,
`bin/install.sh`; docs `install.md`, `build-and-deploy.md`.

## Scope

**This layer decides** what a version is, where a build gets its version from,
and where the version is shown and recorded.

**It must not know** how a session runs or what it says; it is read by the CLI,
`/status`, the session snapshot and the trajectory.

## Model

### A version is a git tag

Semver tags `vMAJOR.MINOR.PATCH`, from `v0.1.0`. Tagging is the release act.
Between tags a build is named by `git describe --tags --always --dirty`:
`v0.1.0-3-gabc1234`, with `-dirty` when the tree had changes.

### Where a build gets it

In order:

1. **The checkout the code was loaded from**, when it was loaded from files
   (`version/source-root`, the directory above `resources/`): git is asked
   there, and the result says `from the checkout`. First, because a checkout
   moves on after a build, and a stamp left in `resources/` would name the
   build rather than the code.
2. **The stamp** a build embedded: `resources/brainyard/build-version.edn`,
   written by `dev/stamp-version.sh` (`jolt version:byj`, which `jolt build:byj` runs
   first) — `{:version :sha :sha-short :branch :commit-time :dirty? :built-at}`
   on one line, brainyard's keys. Gitignored: it is a build output. A binary's
   resources are embedded, so it has no checkout and reads this.
3. **`dev`**, with neither.

### Where it is said and recorded

- `byj --version` / `-V`: `byj v0.1.0 (76225d1, main, 2026-09-25T…)`.
- `/status`: the `Version` line, above the session (RFC-004).
- The session snapshot: `:brainyard-version` (RFC-016).
- Each trajectory record: `:brainyard-version`, beside `:jolt` (RFC-025).

## API

`brainyard.version`: `build-info` (a delay), `version`, `version-long`,
`read-info` (`[stamp root]`, injectable), `from-git`, `source-root`,
`stamp-resource`. `brainyard.update` (the daily check): `check-interval-ms`,
`check-timeout-ms`, `check-file`, `read-check`, `check-due?`, `check-notice`,
`refresh-check!`, `startup-check!`; config `:update-check?`.

Tasks: `jolt install:byj` (`dev/install.sh`, brainyard's `bb install:ata`):
`target/byj` as `byj` in `~/.local/bin` or `BY_INSTALL_DIR`, renamed
into place with the old one kept as `.prev`, as `byj update` installs, so
`update --rollback` undoes it; it says when the binary was not built from the
checkout's commit. `jolt version:byj`, and `jolt build:byj`, which stamps first and builds
`--no-direct-link` — for size and speed now that nothing is rebound (RFC-028,
provenance P-24, byj-gly.272). `dev/brew-formula.sh vX.Y.Z TAP-DIR` writes
byj's Homebrew formula from a published release ("Homebrew" below).
`brainyard.update/homebrew-formula` names the formula whose keg holds a binary.

## Invariants

| invariant | enforced by |
|---|---|
| A binary reads its stamp; a checkout asks git, over any stamp left behind; neither is `dev`; an unreadable stamp is no stamp. | `version-test/a-version-comes-from-the-checkout-the-stamp-or-nowhere` |
| `--version` and `-V` print `byj <version-long>`. | `version-test/the-cli-says-its-version` |
| `/status` names the version. | `session-test/status-says-what-this-session-is` |
| A snapshot records the build that wrote it. | `persist-test/a-switched-model-is-resumed` |
| Only a tagged, clean build whose binary says its stamp's version is staged. | `release-test/only-a-tagged-clean-build-is-staged` |
| A staged release holds this version's byj assets only — no other version's, and no other program's — all in `SHA256SUMS`, with its provenance. | `release-test/a-release-is-staged-with-its-checksums-and-provenance` |
| A formula fetches each platform's asset by its browser URL on the public tap, with no token, at the sha256 `SHA256SUMS` gives; a platform with no asset is left out. | `release-test/a-formula-fetches-each-platforms-asset-from-the-public-tap` |
| A release is copied to the tap whole — assets, checksums, notes, title — only when its assets match its `SHA256SUMS`, and the copy is checked against them; a tap release that exists is completed, not replaced; the copy is made the tap's latest release, which `byj update` reads. | `release-test/a-release-is-copied-to-the-public-tap-whole-and-checked` |
| OpenSSL is looked for under both Homebrew prefixes and never at the `/usr/lib` stub macOS aborts for; the macOS formula depends on openssl@3. | `release-test/openssl-is-found-on-any-mac-and-the-unsafe-stub-is-never-tried` |
| No formula is written for an asset `SHA256SUMS` does not list, or for a release without byj; another program's formula in the tap is left as it is. | `release-test/a-formula-is-refused-for-a-release-it-cannot-check`, `release-test/a-formula-fetches-each-platforms-asset-from-the-public-tap` |
| A binary in a Homebrew Cellar is Homebrew's to update: `byj update` runs brew for it — `brew update`, then `brew upgrade` of byj's formula — in order, stopping at the first that fails, and touches no file itself. | `update-test/a-binary-homebrew-installed-is-homebrews-to-update`, `update-test/a-homebrew-install-is-brought-up-by-brew` (the command itself, in `main/update-command`, is not tested) |
| An install's origin is read from the running binary's path alone, symlinks resolved: a Cellar keg is Homebrew's, any other file its own. | `update-test/an-install-has-an-origin` |
| `byj update` plans byj alone, one row: kept when at the release, replaced when behind, a checkout brought to the tag; a program built on byj is not in it (`byj-ahf.7`). | `update-test/byj-update-plans-byj-alone` |
| byj is replaced only once its asset checks against the release's `SHA256SUMS`, fetched once; another program's asset in the release is not fetched; a failure installs nothing and leaves no download; a rollback swaps it back. | `update-test/byj-is-replaced-against-the-releases-sha256sums` |
| Every copy of byj on PATH is said, each real file once: the one that runs by name with its version and origin, the others as shadowed with how their owner updates or removes them, the running byj when it is not the one that runs by name; none is touched. | `update-test/the-other-copies-on-path-are-said-and-left`; in `/doctor`, `session-test/the-subscription-is-shown-by-usage-and-doctor` |
| A release is held to its own `SHA256SUMS`: a changed, a missing and an unlisted asset are each refused. | `release-test/a-release-is-held-to-its-own-checksums` |
| A release is newer only by its tag; a build past a tag is not offered it. | `update-test/a-release-is-newer-only-by-its-tag` |
| A private repo's 404 is told apart: no token, no access, no release; any other answer is a failure. | `update-test/the-latest-release-of-a-private-repo` |
| The default source is the public tap, read with no token. | `update-test/the-public-tap-is-read-with-no-token` |
| A binary is replaced only by what its `SHA256SUMS` says, the old one kept; a mismatch or a missing platform installs nothing; a rollback swaps, and swaps back. | `update-test/a-binary-is-replaced-only-by-what-its-checksum-says` |
| A checkout is brought to the tag only when clean. | `update-test/a-checkout-is-brought-to-the-tag-only-when-clean` |
| With `:update-check?`, a run's line comes from the last check: a due check runs in the background and is kept; within a day GitHub is not asked again; a release that is not newer is not said; a failure waits a day; another repo's check is not trusted. | `update-test/a-daily-check-says-a-newer-release-and-never-waits-for-it` |

## Updating (`byj-gly.181.3`)

`brainyard.update` is the mechanism, `byj update` the only caller. Nothing
updates by itself.

- **`byj update --check`** reads the newest release of `:update-source`
  (the public tap since `byj-gly.181.10`, below) and says whether it is
  newer. Versions compare as numbers; a build past a tag (`v0.2.0-3-g…`) is
  at least that tag, so it is never offered it, and `dev` is offered any.
- **`byj update` updates byj alone** (since `byj-ahf.7`; it updated
  the share sidecar and another program with it from `byj-gly.181.11` and `.15`): a program
  built on byj, the share sidecar among them, updates itself. It is updated
  by the installer that put it there — the install's **origin**, read from
  the running binary's path alone with its symlinks resolved
  (`update/install-origin`): a Homebrew Cellar keg is Homebrew's, any other
  file is a **binary** of its own, and a git tree a **checkout**. Before
  asking, the command prints its **plan** (`update/plan`), byj's one row —
  where it is, from what to what, and what will be done: kept when at the
  release, replaced when behind, the tag checked out for a checkout — then
  asks once (`--yes` skips it; no answer is no).
- A **binary** finds its own file (`/proc/<pid>/exe`, or `lsof` on macOS: jolt
  has no ProcessHandle), downloads the release's `SHA256SUMS` and then its
  platform's asset through the API, streamed into `<name>.new` and checked
  against it, and only when it checks out keeps the current file as
  `<name>.prev` and renames the new one into place (`update/update-pair!`) —
  one rename, so there is never a moment with no binary, and a killed update,
  a failed download or a checksum that does not match leaves the old one
  untouched. Another program's assets in the same release are not fetched. A
  **Homebrew** install is brought up by brew itself (`byj-gly.181.12`,
  `update/update-homebrew!`): `brew update`, then `brew upgrade` of byj's
  formula — the tap's name, `grumatic/tap/byj`, from `:update-source` — in
  order, stopping at the first that fails. A **checkout** refuses a dirty
  tree, fetches tags from origin and checks the tag out (detached; `git
  switch -` goes back), and says to run `jolt build:byj`.
- **`byj update --rollback`** swaps a binary with its `.prev`; a second
  swaps back. A Homebrew install has no rollback: brew keeps no previous keg
  to switch back to (decided 2026-10-14), and the command says so. The
  install also prints the plain `mv` that undoes it, because the release
  installed may predate `--rollback` — v0.2.0 does.
- Two installs can coexist — one in `~/.local/bin`, one in a Cellar — and only
  PATH decides which runs; `byj update` updates the byj that ran it, never
  the other install, whose owner does. **What is installed is said**
  (`byj-gly.181.13`, `update/install-lines`): `byj doctor` lists the byj that
  runs by name — the first on PATH, each real file once, so a Cellar and the
  link brew makes to it are one — with its version and origin, then every
  other copy, shadowed, with how it is updated or removed (`brew
  upgrade`/`brew uninstall` for a Cellar's, `<path> update`/`rm -f` for a
  binary's), and says when the running byj is not the one that runs by name;
  `byj update`, when it went well, says the shadowed ones as notes. Nothing
  is updated or removed by it.
- Before it downloads, it says what and how big: the 59 MB v0.2.0 binary took
  63–110 s here, where `gh` took 33 s.
- The hash is `shasum -a 256` / `sha256sum`, else one digest over the whole
  file. MessageDigest is jolt-crypto's (pinned through ring-chez-adapter): it
  hashes a whole array for `update(bytes, off, len)` (jolt-lang/crypto#12) and,
  in the pinned version, slows with every `update` (1 MB in 64 KB steps 277 ms,
  4 MB 2.9 s, the binary over ten minutes — fixed at its HEAD).
- **A daily check, when asked for** (`:update-check?`, off by default,
  `byj-gly.181.7`). A run looks for a newer release of `:update-source` at
  most once a day (`check-interval-ms`), in the background with a 20 s limit
  (`startup-check!`). It keeps the answer in `~/.brainyard/update-check.edn`,
  and keeps a failure too, so a check that fails waits a day like any other.
  The line it says comes from the *last* check, printed to stderr as the run
  ends, after the full-screen view has given the terminal back, so startup
  never waits on the network: "byj v0.3.0 is out (this is v0.2.0) —
  `byj update` installs it". It installs nothing. A check kept for
  another repo than `:update-source` is not trusted.

brainyard has no update command (divergence `byj-updates-itself`).

## Where "latest" comes from (decided 2026-09-29, `byj-gly.181.2`)

byj will live in a **private** GitHub repo, and users run **both** the
binary and a checkout. brainyard publishes public GitHub Releases and finds the
latest through `api.github.com/…/releases/latest` with no credentials; a private
repo cannot be read that way. What each candidate does, measured by
`dev/brainyard/dev/latest_version_spike.clj` (median of three, 2026-09-29, this
machine; brainyard's public releases stand in for a private repo's WITH a token,
which is the same API and the same asset flow):

| source | result | time |
|---|---|---|
| Releases API, public, no token | 200, `v0.6.0`; 60 requests/hour per IP | 98 ms |
| Releases API, private, no token | 404 — the repo is invisible | 123 ms |
| Releases API, token | 200; 5000 requests/hour | 485 ms |
| Releases API, private with no release yet, token | 404 too — so a 404 cannot say "no access" | 507 ms |
| `/tags`, private, token | 200 | 407 ms |
| an asset (`SHA256SUMS`) by the API, token, `Accept: application/octet-stream` | 200, 319 bytes, 4 checksum lines — `jolt.http-client` follows the redirect to storage | 985 ms |
| `git ls-remote --tags`, public, https | 19 semver tags, newest `v0.6.0` | 582 ms |
| `git ls-remote --tags`, private, ssh | **failed**, twice over: first the user's git config rewrote `git@github.com:` to an ssh alias that did not exist (a typo, since fixed); then, rewritten to the right alias, it spoke as the identity that directory's config picks, which cannot see that private repo — "Repository not found" | 45 ms |
| `gh release view` | `v0.6.0` | 461 ms |

**Decision.**

- **Latest is the newest GitHub Release** of the configured repo
  (`:update-source {:github "owner/name"}`), read through the Releases API with
  a token: `GH_TOKEN`, then `GITHUB_TOKEN`, then `gh auth token` when `gh` is
  there. A release, not a bare tag, is what is released: it is what carries the
  assets and the notes.
- **A binary** downloads its platform's asset and `SHA256SUMS` through the API
  (`/releases/assets/:id`, `Accept: application/octet-stream`, the token) and
  verifies the checksum before swapping.
- **A checkout** asks the same source when there is a token, and otherwise
  `git ls-remote --tags origin`: the remote and credentials it already pulls
  with, so if `git pull` works, so does the check. It then fetches the tag and
  rebuilds.
- **No token** says how to give one, not "no update". A 404 with a token is
  told apart by asking for the repo itself: invisible means no access, visible
  means no release yet.
- **The token is never printed or logged**, and goes only to `api.github.com`.
- **Not chosen:** a manifest URL (hosting of its own, and a private one needs
  auth anyway); `gh` as a dependency (one more thing every user must install —
  it is only a source of the token); `git ls-remote` for a binary (it has no
  remote, and the measurement shows the path depends on each user's git and
  ssh setup — down to which account a directory's config speaks as).

brainyard needs no token because its repo is public; the port's is divergence
`byj-updates-itself`.

**Since 2026-10-12 the source is the public tap** (`byj-gly.181.10`). Each
release is copied, assets and notes, to a release of the same tag on
`grumatic/homebrew-tap`, which is public, and `:update-source` names it: a
binary finds and downloads the latest with no token, as brainyard's install
does. The token is still sent when there is one (5000 requests an hour, not
60), and a private `:update-source` still needs one. The source repo stays
private. v0.3.0 predates this and still names the private repo; a person
gives it the tap with `:update-source` in `~/.brainyard/config.edn`.

## Releasing (`byj-gly.181.4`, `byj-gly.181.5`)

brainyard's `bin/release-stage.sh` / `bin/release-verify.sh` and its
`CHANGELOG.md` / `CHANGELOG-latest.md`, for a jolt binary, built and published
by `.github/workflows/release.yml`:

1. Move `CHANGELOG.md`'s Unreleased section under the new version, and copy it
   to `CHANGELOG-latest.md` — the release's notes. Commit and push.
2. `git tag vX.Y.Z` at that commit, and `git push origin vX.Y.Z`. The tag
   starts the workflow.
3. **build**, once per platform on its own runner — jolt builds only for the
   machine it runs on (cross-compiling needs a target pack: jolt's
   `tools/cross-compile`): `macos-arm64` on `macos-15` (`macos-14` until v0.3.15, whose run GitHub cancelled as a configuration it no longer offers), `macos-amd64` on
   `macos-15-intel`, `linux-amd64` on `ubuntu-22.04` (the oldest glibc a
   runner offers, 2.35), `linux-arm64` on `ubuntu-22.04-arm` (the same glibc;
   from v0.3.19, `byj-gly.181.8`). Each installs the jolt
   `dev/jolt-verified.edn` names, checksum-checked — except on linux-arm64,
   for which jolt publishes no build: there `dev/jolt-from-source.sh` builds
   that jolt from its source as jolt's own release builds its x86_64 Linux
   one (its Chez tag and patches, read from jolt's release workflow;
   `ci/glibc-floor-build.sh` in a manylinux2014 container, so glibc 2.17),
   cached by the jolt version and the script, about three minutes uncached.
   Then each runs `jolt build:byj`, which stamps the tag; asks `byj
   ask -p mock` for a smoke test; and runs `dev/release-stage.sh`, which
   stages `byj-<version>-<os>-<arch>`, and nothing else of `target/`. The
   stage refuses a `dev` stamp, a dirty tree, commits past the tag, anything
   not `vX.Y.Z`, and a binary whose `--version` is not the stamp's — built,
   then re-stamped, which would otherwise ship under the wrong name
   (brainyard does not check this one).
4. **publish** gathers every platform's assets, writes one `SHA256SUMS` over
   all of them and one `BUILD-INFO.txt` (each platform's commit and build
   time), checks them with `dev/release-verify.sh --dir`, creates the release
   with `CHANGELOG-latest.md` as its notes, marked latest, and checks what was published with
   `dev/release-verify.sh vX.Y.Z` — the failure brainyard shipped once, a
   manifest naming an asset never uploaded, happens after everything local
   passes.
5. **tap** copies the release — assets, `SHA256SUMS`, notes — to a release of
   the same tag on the public tap (`dev/release-mirror.sh`, checked against
   its `SHA256SUMS` before and after), marked the tap's latest, then writes
   byj's Homebrew formula from that copy and pushes it (below), when the `HOMEBREW_TAP_TOKEN` secret can
   write there; without it, run `jolt release-mirror:byj vX.Y.Z` and
   `jolt brew-formula:byj vX.Y.Z <tap checkout>` and push the tap by hand.
Run by hand (`gh workflow run release`), the workflow is a dry run: every
platform is built and smoke-tested and nothing is staged or published, so a
release that will not build is found before its tag exists. Staging still
works on one machine (`jolt build:byj`, `jolt release-stage:byj`): it keeps
the same version's assets already in `release/` and checksums them all.

The asset names are what `byj update` (`byj-gly.181.3`) and the formula look
for. The releases are on the source repository, private until byj's is
published (RFC-070), from v0.2.0 (`byj-gly.181.6`); `gh` needs an account
that can see it (`GH_TOKEN=$(gh auth token --user …)`). Their public copies
are on `grumatic/homebrew-tap`, from v0.3.0 (`byj-gly.181.10`).

**byj is released alone** (`byj-ahf.9`). Until then one tag released byj
with programs built on it, at one version, in one `SHA256SUMS`. Now a
`vX.Y.Z` tag releases byj, and nothing else of the repository; a program
built on byj releases itself, on tags of its own, and may put its releases
on the same tap. So that `byj update` and the daily check, which read the
tap's latest release, always find byj's, byj's release and its copy are
created marked latest, and another program's are created marked not
latest. A byj installed before this reads the same release and finds the
same asset, so it needs nothing.

## Homebrew (`byj-gly.181.5`)

byj is a formula in the public tap `grumatic/homebrew-tap`, which also
holds each release's binaries (`byj-gly.181.10`). Other formulae in the tap
are other programs', each written by its own release; byj's writes
`Formula/byj.rb` and nothing else.

```bash
brew tap grumatic/tap
brew install grumatic/tap/byj
```

No GitHub account, ssh key or token is needed. A formula fetches its asset by
the release's browser URL on the tap, at the sha256 in the release's own
`SHA256SUMS`, and a platform the release has no asset for is left out of the
formula. The formulae are written from the tap's published copy
(`dev/brew-formula.sh`), never from `release/`, so what a formula names is
what was uploaded.

Before 2026-10-12 the tap was private and the assets were only on the private
source repo: a formula fetched each through the API by its id with
`HOMEBREW_GITHUB_API_TOKEN`, and installing needed an ssh key that could read
the tap and a token that could read the source. Measured then with v0.2.0's
asset in a local tap: installed, checksum-checked, and the binary ran —
Homebrew's install leaves a jolt binary's appended image alone, as jolt's own
formula relies on.

What going public exposed was checked first: the tap's history is two commits
of formulae and README; the v0.3.0 binaries hold no key, token, private-key
block or home path, and the only Grumatic host in them was the share
relay's. byj's embedded image is compressed — `strings` finds no
prompt or docstring in it — but anyone with the binary can unpack it, so its
prompts and predictors are treated as public.

byj loads OpenSSL when it starts, and on macOS from Homebrew's `openssl@3`:
`deps.edn` names its paths under both Homebrew prefixes and MacPorts' over
jolt-crypto's own, which would try a system stub macOS aborts the process for
(provenance P-36), and the macOS formula `depends_on "openssl@3"`.

A binary in a Homebrew Cellar is Homebrew's: `byj update` runs
`brew upgrade` for it rather than replacing the file
(`update/homebrew-formula`, `update/update-homebrew!`), since a file replaced
in a keg is one `brew upgrade` and `brew uninstall` no longer account for.

## Known gaps

- **linux-arm64 stands on a jolt built here**, not one jolt has released
  and tested: its suite runs nightly on that jolt (`nightly.yml`
  `test-linux-arm64`), where every other platform's jolt is jolt's own
  release. It ends when jolt publishes an aarch64-linux build, and the row
  downloads it as the others do.
- **macos-amd64 is built on GitHub's last Intel runner image**,
  `macos-15-intel`; when it is retired, an Intel build is cross-compiled
  instead (`byj-gly.181.9`).
- **A binary built from a dirty tree says only `-dirty`**: which changes were
  in it is not recorded.
