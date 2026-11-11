---
name: by-rebuild-stamp-verify-loop
description: Ship a source fix in the Brainyard Polylith workspace end-to-end — commit, re-stamp the build version, rebuild and install the native `by` binary, then re-verify the downstream playground app. Use when a fix must reach the installed binary, when `by --version` lags HEAD, when build artifacts are stale, or when a playground/API smoke test must be re-run against a rebuilt binary. Trigger keywords - rebuild by, bb build:ata, version:ata, re-stamp, stale binary, install by, smoke test playground, staleness check.
---

# Rebuild, stamp, install and verify the `by` binary

Follow the loop in order. Each phase ends with a check; do not skip ahead on a red check.

## 1. Baseline before touching anything

From the workspace root run one read-only block that captures:

```
git rev-parse --abbrev-ref HEAD && git status --short
cat projects/agent-tui-app/resources/build-version.edn
which by && "$(which by)" --version
bb tasks | grep -Ei 'ata|version|native|uber|build'
```

Compare the installed binary's sha-short against `git rev-parse --short HEAD`.
A mismatch (or a `-dirty` suffix) is the staleness signal that justifies a rebuild.

## 2. Commit the source fix

- Work directly on `main`; do not create a reflex feature branch.
- Confirm `projects/agent-tui-app/resources/build-version.edn` is ignored:
  `git check-ignore -v projects/agent-tui-app/resources/build-version.edn`.
  It must NEVER be committed — if it is not ignored, stop and investigate.
- Print the full diff of only the source files you intend to commit and write a
  conventional-commit message from what the diff actually does.
- Use single quotes in `git commit -m '...'`; backticks in double quotes get
  shell-substituted. Do not add a `Co-Authored-By` trailer.
- Stage the source paths explicitly (`git add <paths>`), never `git add -A`.

## 3. Re-stamp for a clean version

```
bb version:ata
cat projects/agent-tui-app/resources/build-version.edn
```

Expect `:dirty? false` and a `:sha-short` equal to the new HEAD. A dirty stamp
means uncommitted work leaked in — go back to phase 2.

## 4. Gate, then build

```
bb reflect:check      # native-image config gate; must pass first
bb build:ata          # fetch deps -> AOT compile -> uberjar -> native binary
```

`bb build:ata` takes many minutes and will normally detach into a background
task. When it does:

- Do NOT re-emit the build code.
- Pick ONE wait strategy: `task$wakeup :task-id "…"` (acknowledge in one line and
  end the turn; you are auto-resumed) for a long build, or
  `task$wait :task-id "…" :timeout-ms 60000` only for a short wait.
- Peek with `task$detail` (`:last-n N`); never poll in a loop.

Iterate with the JVM-from-source path (`bb tui`) while developing; reserve the
full native `bb build:ata` for this final shipping step.

## 5. Install and prove the binary carries the new stamp

Install into the user's bin dir, then assert:

```
"$(which by)" --version
```

The printed version string must equal the `:version` in build-version.edn.
If it still shows the old sha, the install copied the wrong artifact — recheck
the build output path before continuing.

## 6. Re-verify downstream

Re-run the SAME probes that exposed the original failure, in this order:

1. Direct CLI from the downstream repo's cwd (cwd matters: JVM cwd ≠ project-dir
   bugs show up only here), capturing stdout/stderr separately and validating
   JSON parses.
2. Liveness of any dev server (`turbo run dev` / `next-server` pid, `curl` the
   root route) before hitting HTTP endpoints.
3. The API/page routes that previously 500'd.
4. Any on-disk residue the bug created (e.g. corrupted `.brainyard/sessions/*/meta.edn`);
   count remaining bad files — a fix that only prevents new corruption still
   needs the old files cleaned or tolerated.

## Pitfalls seen in practice

- Tool args are often strings, not keywords (`{:backend "claude-code"}`); an
  "should be a string" validation error means re-run with string values, not a
  different tool.
- Long delegated calls (ACP asks, native builds) fail with await timeouts when
  the prompt/work is huge — scope prompts tightly and prefer detach + wakeup.
- Do not run the full `bb test` after a change (~3 min blocking); test only the
  affected namespaces and hand the full suite to the user.
- Ask before pushing; committing to `main` locally is fine.
