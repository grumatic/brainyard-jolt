#!/bin/bash
# Copyright (c) 2026 Grumatic, Inc. and byj contributors
# SPDX-License-Identifier: MIT
#
# Replays one recorded Bedrock request (an invoke-model body.json, as
# chat* :bedrock wrote it) at each given max_tokens, and says how each reply
# ended: its stop reason, output and thinking tokens, and whether its text
# holds the JSON object react/think-act asks for. LIVE: each run is a paid
# model call. byj-hzp's measurement (us-east-1, 2026-11-27, Opus 5.5, a turn
# writing several files):
#
#   max_tokens  stop        out    thinking  JSON
#   8192        end_turn    3923   3837      no   (recorded, 4 calls of 4 alike)
#   8192        max_tokens  8192   4937      cut  (replay)
#   32000       end_turn    14480  6182      yes
#   32000       end_turn    7347   3102      yes  (the request after a FORMAT ERROR)
#   32000       end_turn    12614  6154      yes
#
# usage: dev/bedrock-max-tokens-replay.sh BODY.json MODEL-ID MAX_TOKENS... [--region R]
set -u
body=$1 model=$2; shift 2
region=${AWS_REGION:-us-east-1}
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
for n in "$@"; do
  jq ".max_tokens = $n" "$body" > "$out/body.json"
  t0=$(date +%s)
  if aws bedrock-runtime invoke-model --model-id "$model" --body "fileb://$out/body.json" \
       --cli-binary-format raw-in-base64-out --cli-read-timeout 900 --region "$region" \
       "$out/reply.json" > /dev/null 2> "$out/err"; then
    printf '%-6s %4ss ' "$n" $(( $(date +%s) - t0 ))
    jq -r '"\(.stop_reason) out=\(.usage.output_tokens) think=\(.usage.output_tokens_details.thinking_tokens // 0) json=\([.content[]|select(.type=="text")|.text|test("\\{\"reasoning\"")]|any)"' "$out/reply.json"
  else
    printf '%-6s failed: %s\n' "$n" "$(head -c 300 "$out/err")"
  fi
done
