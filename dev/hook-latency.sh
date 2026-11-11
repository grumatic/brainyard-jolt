#!/usr/bin/env bash
# What `byj hook pretooluse` costs a tool call (RFC-012 "Another agent's
# calls", byj-ahf.24): Claude Code and Codex run it before every tool call,
# so its start is paid on each. A scratch project whose config refuses
# write-file and approves the rest, so no gate asks the classifier (no
# model is called); N calls of a refused Write and N of a passed Read, each
# timed, and the decisions logged under the scratch directory.
#
#   dev/hook-latency.sh [N] [BYJ]     N calls of each (10), BYJ the binary (target/byj)
set -euo pipefail
n="${1:-10}"
byj="$(cd "$(dirname "${2:-target/byj}")" && pwd)/$(basename "${2:-target/byj}")"
dir="$(mktemp -d)"
trap 'rm -rf "${dir}"' EXIT
mkdir -p "${dir}/p/.brainyard"
printf '{:agent {:config {:tool-deny-tools ["write-file"] :permission-mode :auto-approve}}}\n' > "${dir}/p/.brainyard/config.edn"
payload() { printf '{"tool_name":"%s","tool_input":{"file_path":"%s/p/a.txt"},"cwd":"%s/p","session_id":"lat"}' "$1" "${dir}" "${dir}"; }
run() { # tool → milliseconds, the answer to stdout of the last
  local t0 t1
  t0=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
  payload "$1" | (cd "${dir}/p" && "${byj}" hook pretooluse --log "${dir}/log") > "${dir}/out"
  t1=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
  echo $((t1 - t0))
}
for tool in Write Read; do
  ms=()
  for _ in $(seq "${n}"); do ms+=("$(run "${tool}")"); done
  sorted=$(printf '%s\n' "${ms[@]}" | sort -n)
  med=$(printf '%s\n' "${sorted}" | awk -v n="${n}" 'NR == int((n + 1) / 2)')
  max=$(printf '%s\n' "${sorted}" | tail -1)
  printf '%-5s median %4d ms  max %4d ms  answer: %s\n' "${tool}" "${med}" "${max}" "$(head -c 120 "${dir}/out")"
done
printf 'logged: %s decision(s)\n' "$(cat "${dir}"/log/*.edn | wc -l | tr -d ' ')"
