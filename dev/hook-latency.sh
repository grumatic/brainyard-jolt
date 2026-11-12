#!/usr/bin/env bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT

# What `byj hook pretooluse` costs a tool call (RFC-012 "Another agent's
# calls", byj-ahf.24): Claude Code and Codex run it before every tool call,
# so its start is paid on each. A scratch project whose config refuses
# write-file and approves the rest, so no gate asks the classifier (no
# model is called); N calls of a refused Write and N of a passed Read, each
# timed, and the decisions logged under the scratch directory.
#
# --resident times the command `byj hook claude-settings` installs, which
# asks `byj hook serve` (byj-ywp): BYJ's server started on a socket of its
# own, then the same calls through that command, curl to the socket. The
# spike it replaced (byj-fim, dev/brainyard/dev/hook_server_spike.clj asked
# by `nc -U`) measured what such a server could be; this measures what was
# built. The first call to a fresh server is timed apart.
#
#   dev/hook-latency.sh [N] [BYJ]               N calls of each (10), BYJ the binary (target/byj)
#   dev/hook-latency.sh --resident [N] [BYJ]
set -euo pipefail
resident=
if [ "${1:-}" = "--resident" ]; then resident=1; shift; fi
n="${1:-10}"
byj="$(cd "$(dirname "${2:-target/byj}")" && pwd)/$(basename "${2:-target/byj}")"
dir="$(mktemp -d)"
# sun_path holds ~104 bytes, more than a mktemp path under $TMPDIR leaves
sock="/tmp/byj-hook-latency-$$.sock"
server=
cleanup() { [ -n "${server}" ] && kill "${server}" 2>/dev/null; rm -rf "${dir}" "${sock}"; }
trap cleanup EXIT
mkdir -p "${dir}/p/.brainyard"
printf '{:agent {:config {:tool-deny-tools ["write-file"] :permission-mode :auto-approve}}}\n' > "${dir}/p/.brainyard/config.edn"
payload() { printf '{"tool_name":"%s","tool_input":{"file_path":"%s/p/a.txt"},"cwd":"%s/p","session_id":"lat"}' "$1" "${dir}" "${dir}"; }
ask() { # the payload on stdin → what the hook prints
  if [ -n "${resident}" ]; then
    sh -c "${command}"
  else
    (cd "${dir}/p" && "${byj}" hook pretooluse --log "${dir}/log")
  fi
}
run() { # tool → milliseconds, the answer to stdout of the last
  local t0 t1
  t0=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
  payload "$1" | ask > "${dir}/out"
  t1=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
  echo $((t1 - t0))
}
if [ -n "${resident}" ]; then
  # the installed command, as Claude Code's settings carry it; its fallback
  # names `byj`, so BYJ's directory goes first on the PATH
  export PATH="$(dirname "${byj}"):${PATH}"
  command=$("${byj}" hook claude-settings --socket "${sock}" --log "${dir}/log" \
    | perl -MJSON::PP -0777 -ne 'print decode_json($_)->{hooks}{PreToolUse}[0]{hooks}[0]{command}')
  "${byj}" hook serve --socket "${sock}" --idle 300 > "${dir}/server.out" 2>&1 &
  server=$!
  for _ in $(seq 120); do curl -sf --unix-socket "${sock}" http://byj/v1/ping > /dev/null 2>&1 && break; sleep 0.25; done
  curl -sf --unix-socket "${sock}" http://byj/v1/ping > /dev/null || { cat "${dir}/server.out"; exit 1; }
  printf 'first call to the fresh server: %d ms\n' "$(run Read)"
fi
for tool in Write Read; do
  ms=()
  for _ in $(seq "${n}"); do ms+=("$(run "${tool}")"); done
  sorted=$(printf '%s\n' "${ms[@]}" | sort -n)
  med=$(printf '%s\n' "${sorted}" | awk -v n="${n}" 'NR == int((n + 1) / 2)')
  max=$(printf '%s\n' "${sorted}" | tail -1)
  printf '%-5s median %4d ms  max %4d ms  answer: %s\n' "${tool}" "${med}" "${max}" "$(head -c 120 "${dir}/out")"
done
printf 'logged: %s decision(s)\n' "$(cat "${dir}"/log/*.edn | wc -l | tr -d ' ')"
