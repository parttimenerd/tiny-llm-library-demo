#!/usr/bin/env bash
# 03-conversation.sh — Multi-turn conversation with history
# Usage: ./03-conversation.sh

set -euo pipefail

echo "=== POST /v1/chat/completions (multi-turn conversation) ==="
echo

curl -s http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "Qwen/Qwen3-1.7B-GGUF:Q8_0",
    "messages": [
      {"role": "system", "content": "You are a helpful Java expert. Be concise."},
      {"role": "user", "content": "What is a record in Java?"},
      {"role": "assistant", "content": "A record is a compact, immutable data carrier class introduced in Java 16. It auto-generates constructor, getters, equals(), hashCode(), and toString()."},
      {"role": "user", "content": "Show me a short example with a Person record."}
    ]
  }' | jq .
