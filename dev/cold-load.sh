#!/usr/bin/env bash
# Every namespace must load ON ITS OWN, in a fresh jolt process.
#
# jolt resolves java.time.* only after jolt.time has installed its host shim,
# and clojure.data.json touches DateTimeFormatter at namespace load — so a
# namespace that reaches data.json without jolt.time loaded first throws, and
# throws naming data.json rather than the cause. brainyard.json requires
# jolt.time for exactly this reason. Whether a path is safe depends on LOAD
# ORDER, which the suite cannot see: it loads everything in one process, in one
# order. Only a fresh process for each namespace tells the truth (samizdat's
# dev/cold-load.sh caught this three times).
#
# The list is DERIVED from the source tree, so a new namespace is covered the
# day it lands.
#
#   dev/cold-load.sh            # src + test + dev namespaces
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
JOLT="${JOLT:-jolt}"

nses=$(grep -rhoE '^\(ns [a-z][a-z0-9.-]*' src test dev --include='*.clj' | awk '{print $2}' | sort -u)

fail=0
for ns in $nses; do
  # -A (paths only) not -M: the :test alias's main-opts would run the suite.
  if out=$("$JOLT" -A:dev -e "(require '$ns)" 2>&1); then
    printf 'ok    %s\n' "$ns"
  else
    printf 'FAIL  %s\n%s\n' "$ns" "$(printf '%s\n' "$out" | head -5 | sed 's/^/      /')"
    fail=1
  fi
done
exit $fail
