#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# Text this tree shares with samizdat (GPL-3.0-or-later), which byj's MIT
# source must not hold (byj-ahf.1). Runs of eight words or more found in both
# fail unless dev/samizdat-check.edn lists them as reviewed: idioms, table
# heads, a dependency coordinate, error text quoted. An entry there that no
# longer occurs is named as a warning, to be taken off. Nothing runs it on its
# own — not CI, not the suite, not a release, not reading samizdat again: it
# is run by hand, when someone wants the answer (decided 2026-11-13).
#
#   dev/samizdat-check.sh          # this checkout's tracked files, as on disk
#   dev/samizdat-check.sh DIR      # every file under DIR (an export)
#
# samizdat is cloned once into target/samizdat-check (network on the first
# run) and read at the commits below: the day before this repository began,
# the day before Tool Lisp's indent repair, and the last read when the list
# was made. Add a commit to compare against a later samizdat.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cache="$root/target/samizdat-check"
refs=(23bba556e2a788c3f676d3487f7b9c71f1840d11   # 2026-09-16
      046a4b8da333313c4dee8361bd98edbad9c9b5a4   # 2026-09-28
      9411f00c87d5ddb725f46eb8794e0d93a479835f)  # 2026-10-31

mkdir -p "$cache"
repo="$cache/samizdat"
if [ ! -d "$repo/.git" ]; then
  git clone -q https://github.com/yogthos/samizdat.git "$repo"
fi
trees=()
for ref in "${refs[@]}"; do
  tree="$cache/tree-$ref"
  if [ ! -d "$tree" ]; then
    git -C "$repo" cat-file -e "$ref^{commit}" 2>/dev/null || git -C "$repo" fetch -q origin
    rm -rf "$tree.part" && mkdir -p "$tree.part"
    git -C "$repo" archive "$ref" | tar -x -C "$tree.part"
    mv -f "$tree.part" "$tree"
  fi
  trees+=("$tree")
done

if [ $# -gt 0 ]; then
  ours="$(cd "$1" && pwd)" files=-
else
  ours="$root" files="$cache/tracked.txt"
  git -C "$root" ls-files > "$files"
fi
cd "$root"
exec jolt -A:dev -m brainyard.dev.samizdat-check dev/samizdat-check.edn "$ours" "$files" "${trees[@]}"
