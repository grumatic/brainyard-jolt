#!/usr/bin/env bash
# Does RFC-008 still describe the work that is actually left?
#
# The roadmap is what a new session reads to find out what remains, and it goes
# stale silently: closing a bead updates beads, and nothing makes the row that
# named it follow. Six rows drifted that way before anyone noticed, overstating
# the remaining work by six subsystems.
#
# Not a test. `jolt test:byj` is hermetic and takes ~100 s; this needs the beads
# database and a `bd` on PATH, so it is a dev script you run when the roadmap
# matters — after closing a bead, and before believing what it says.
#
# Only the beads still open when the project was published were moved into
# this repository's database (AGENTS.md "Issues"); a `byj-gly` id it does not
# hold was closed in the tracker before it. So a bead counts as open when the
# database has it unclosed, and as closed otherwise.
#
#   dev/roadmap-drift.sh
#
# Exits non-zero when a row and its beads disagree.
set -uo pipefail
cd "$(dirname "$0")/.."

ROADMAP=docs/RFCS/RFC-008-brainyard-migration.md

command -v bd >/dev/null || { echo "bd not on PATH"; exit 2; }

open_ids=$(bd list --all --limit 0 --json | python3 -c 'import json,sys; print("\n".join(i["id"] for i in json.load(sys.stdin) if i["status"] != "closed"))')

python3 - "$ROADMAP" <<PY
import re, sys
open_ids = set("""$open_ids""".split())
path = sys.argv[1]
drift = []
for n, line in enumerate(open(path), 1):
    if not line.startswith("|"):
        continue
    beads = re.findall(r"\`(byj-gly(?:\.\d+)?)\`", line)
    if not beads:
        continue
    # A row's own beads are the ones in its FIRST cell; ids later in the row
    # point at follow-up work that is deliberately still open.
    own = re.findall(r"\`(byj-gly(?:\.\d+)?)\`", line.split("|")[1])
    if not own:
        continue
    says_done = "done" in line.lower()
    all_closed = not any(b in open_ids for b in own)
    if all_closed and not says_done:
        drift.append((n, own, "every bead is closed, but the row does not say done"))
    elif says_done and not all_closed:
        still = [b for b in own if b in open_ids]
        drift.append((n, own, "says done, but still open: " + ", ".join(still)))

for n, beads, why in drift:
    print(f"{path}:{n}: {' '.join(beads)} — {why}")
print(f"\n{len(drift)} row(s) drifted" if drift else "\nroadmap and beads agree")
sys.exit(1 if drift else 0)
PY
