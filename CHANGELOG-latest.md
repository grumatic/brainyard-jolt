**byj v0.3.19** — byj for arm64 Linux. Full notes in `CHANGELOG.md`.

- **byj for arm64 Linux** (Graviton, Ampere, a Raspberry Pi 4 or 5 on a
  64-bit OS, a Linux container on an Apple-silicon Mac): a release now has
  `byj-<version>-linux-arm64`, and Homebrew on such a machine installs it.
  jolt publishes no arm64 Linux build, so the release builds jolt from its
  source first, as jolt's own release builds its x86_64 one; byj's suite
  runs on it nightly.
