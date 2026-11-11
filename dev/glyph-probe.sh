#!/usr/bin/env bash
# How wide THIS terminal draws the glyphs a model is likely to write, against
# how wide glimmer-tui assumes they are (byj-gly.111).
#
# brainyard negotiates grapheme width with the terminal (DECRQM 2027, a cache,
# a tmux scratch pane); the port assumes glimmer-tui's table. Whether that
# assumption misaligns a row is a fact about the terminal in front of you, and
# only a terminal can say it — so this asks it: print a glyph at column 1,
# request a cursor position report (CSI 6n), and read how far the cursor went.
#
#   dev/glyph-probe.sh          run it in the terminal you use the TUI in
#
# Needs a tty. Measured under tmux 3.6 (2026-09-24): every glyph agrees.
# A disagreement is glimmer-tui's to fix — it owns the painting, and every
# consumer of it draws the same rows.
set -euo pipefail

if [ ! -t 0 ] || [ ! -t 1 ]; then
  echo "glyph-probe: needs a terminal on stdin and stdout" >&2
  exit 2
fi

cd "$(dirname "$0")/.."

GLYPHS=("a" "é" "日本" "✔" "❤️" "🇰🇷" "👍🏽" "👨‍👩‍👧" "🧑‍💻" "⏺" "⎿" "❯" "•")

# what glimmer-tui assumes, one width per line, in the order above
EXPECTED=()
while IFS= read -r w; do EXPECTED+=("$w"); done < <(
  jolt -A:dev -e "(require 'glimmer-tui.text)
                  (doseq [g $(printf '"%s" ' "${GLYPHS[@]}" | sed 's/^/[/; s/ $/]/')]
                    (println (glimmer-tui.text/width g)))" 2>/dev/null)

measure() {
  local glyph=$1 reply col old
  old=$(stty -g)
  stty raw -echo min 0 time 10
  printf '\r\033[2K%s\033[6n' "$glyph" > /dev/tty
  IFS= read -r -d R reply < /dev/tty || true
  stty "$old"
  printf '\r\033[2K' > /dev/tty
  col=${reply##*;}
  [[ "$col" =~ ^[0-9]+$ ]] && echo $((col - 1)) || echo "?"
}

printf '%-10s %-8s %-9s %s\n' glyph terminal glimmer ""
mismatches=0
for i in "${!GLYPHS[@]}"; do
  g=${GLYPHS[$i]}
  got=$(measure "$g")
  want=${EXPECTED[$i]:-?}
  mark=""
  if [ "$got" != "$want" ]; then mark="← differs"; mismatches=$((mismatches + 1)); fi
  # padded by the width the terminal just reported, not by bytes
  pad=$((10 - ${got/\?/1})); [ "$pad" -lt 1 ] && pad=1
  printf '%s%*s%-8s %-9s %s\n' "$g" "$pad" "" "$got" "$want" "$mark"
done
echo "TERM=${TERM:-} TERM_PROGRAM=${TERM_PROGRAM:-} ${TMUX:+(inside tmux)}"
echo "$mismatches glyph(s) drawn at a width glimmer-tui does not expect"
