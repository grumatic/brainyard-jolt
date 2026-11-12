**byj v0.3.18** — `byj` alone starts a session and `--prompt` asks one question; `claude$await` streams a reply that came before the wait; every source file names its licence. Full notes in `CHANGELOG.md`.

- **`byj` alone starts a session.** `run` is now the default command:
  `byj` opens one, and `byj "explain deps.edn"` opens one whose first turn
  is that, as `claude "…"` does. One question answered and done is
  `byj --prompt "…"` (or `byj run --prompt`), as `claude -p` is; `-p` is
  still `--provider`. `byj ask "…"` is unchanged, so a script that runs
  `byj "…"` for one answer should say `byj ask` or `--prompt`.
  `byj --attach SESSION "…"` still asks that session.
- **`claude$await` shows a reply that came before the wait began.** A
  delegated Claude Code session that answered between `claude$say` and
  `claude$await` had its reply returned but never streamed as progress;
  each wait now picks up where the last one stopped.
- **Every source file names its licence**: the two-line SPDX header,
  `Copyright (c) 2026 Grumatic, Inc. and byj contributors` and
  `SPDX-License-Identifier: MIT`. `LICENSE` and `NOTICE` name the
  contributors too, since a contribution signed off under the DCO stays its
  author's.
