#!/usr/bin/env bash
# 04-streaming.sh — Streaming chat completion via SSE
# Usage: ./04-streaming.sh

set -euo pipefail

usage() {
  cat <<USAGE
Usage: $0 [--slow] [--medium] [--fast] [--model MODEL] [--verbose]

Options:
  --slow         Use the slower, larger model (Qwen/Qwen3.5-27B-GGUF:Q8_0)
  --medium       Use the medium model (AaryanK/Qwen3.5-9B-GGUF:Q8_0)
  --fast         Use the faster default model (Qwen/Qwen3-1.7B-GGUF:Q8_0)
  --model MODEL  Explicit model override (takes precedence)
  -v, --verbose  Print raw SSE lines only (no token parsing)
  -h, --help     Show this help and exit
USAGE
}

# Defaults
MODEL="Qwen/Qwen3-1.7B-GGUF:Q8_0" # fast default
VERBOSE=false

# CLI parsing (simple)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --slow)
      MODEL="Qwen/Qwen3.5-27B-GGUF:Q8_0"
      shift
      ;;
    --medium)
      MODEL="AaryanK/Qwen3.5-9B-GGUF:Q8_0"
      shift
      ;;
    --fast)
      MODEL="Qwen/Qwen3-1.7B-GGUF:Q8_0"
      shift
      ;;
    -m|--model)
      if [[ -z "${2-}" ]]; then
        echo "Error: --model requires an argument" >&2
        usage
        exit 2
      fi
      MODEL="$2"
      shift 2
      ;;
    -v|--verbose)
      VERBOSE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# Build JSON payload with variable expansion (here-doc)
read -r -d '' PAYLOAD <<EOF || true
{
  "model": "$MODEL",
  "stream": true,
  "messages": [
    {"role": "user", "content": "Make fun of Java."}
  ]
}
EOF

curl -sN http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" | {
    seen_reasoning=false
    seen_content=false
    
    while IFS= read -r line; do
      # Debug: show what we receive
      # echo "[RAW] $line" >&2
      
      data="${line#data: }"

      if [[ "$VERBOSE" == "true" ]]; then
        printf "%s\n" "$line"
        if [[ "$data" == "[DONE]" ]]; then
          break
        fi
        continue
      fi

      if [[ "$data" == "[DONE]" ]]; then
        echo
        echo
        echo "=== [DONE] ==="
        break
      fi
      
      # Check for reasoning_content and wrap in <think> tags
      if echo "$data" | jq -e '.choices[0].delta.reasoning_content != null' >/dev/null 2>&1; then
        if [[ "$seen_reasoning" == "false" ]]; then
          printf "<think>\n"
          seen_reasoning=true
        fi
        echo "$data" | jq -rj '.choices[0].delta.reasoning_content'
      fi
      
      # Check for regular content and close </think> if needed
      if echo "$data" | jq -e '.choices[0].delta.content != null' >/dev/null 2>&1; then
        if [[ "$seen_content" == "false" ]]; then
          if [[ "$seen_reasoning" == "true" ]]; then
            printf "</think>\n"
          fi
          seen_content=true
        fi
        echo "$data" | jq -rj '.choices[0].delta.content'
      fi
    done
  }
echo  # Final newline
