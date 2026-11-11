#!/usr/bin/env bash
# Run the suite N times and KEEP the output of any run that failed — the only
# way a 4%-per-run flake gets diagnosed instead of waved through (byj-mvd).
#
#   dev/flake-hunt.sh 20            # 20 runs, failures saved under target/flake/
set -u
n="${1:-10}"
out="${2:-target/flake}"
mkdir -p "$out"
fails=0
for i in $(seq 1 "$n"); do
  f="$out/run-$i.out"
  jolt test:byj > "$f" 2>&1
  line="$(tail -1 "$f")"
  if grep -q 'FAIL in\|ERROR in' "$f"; then
    fails=$((fails + 1))
    echo "run $i: FAILED — kept $f"
    grep -A6 'FAIL in\|ERROR in' "$f" | head -20
  else
    echo "run $i: $line"
    rm -f "$f"
  fi
done
echo
echo "$fails of $n runs failed; failing output kept under $out/"
[ "$fails" -eq 0 ]
