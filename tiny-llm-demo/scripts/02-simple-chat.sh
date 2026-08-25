#!/usr/bin/env bash
# 02-simple-chat.sh — Simple chat completion (non-streaming)
# Usage: ./02-simple-chat.sh

set -euo pipefail

echo "=== POST /v1/chat/completions (non-streaming) ==="
echo

curl -s http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "Qwen/Qwen3-1.7B-GGUF:Q8_0",
    "messages": [
      {"role": "user", "content": "Make fun of Java in one sentence."}
    ]
  }' | jq .
