#!/usr/bin/env bash
# 05-tool-call.sh — Tool calling: send tools, get tool_calls, send result
# Usage: ./05-tool-call.sh

set -euo pipefail

echo "=== Step 1: Send message with tool definitions ==="
echo

RESPONSE=$(curl -s http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "Qwen/Qwen3-1.7B-GGUF:Q8_0",
    "messages": [
      {"role": "user", "content": "What files are in the current directory?"}
    ],
    "tools": [{
      "type": "function",
      "function": {
        "name": "ls",
        "description": "List directory contents",
        "parameters": {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "Directory path"}
          },
          "required": ["path"]
        }
      }
    }]
  }')

echo "$RESPONSE" | jq .

# Extract tool call info
FINISH_REASON=$(echo "$RESPONSE" | jq -r '.choices[0].finish_reason')
echo
echo "finish_reason: $FINISH_REASON"

if [[ "$FINISH_REASON" != "tool_calls" ]]; then
  echo "Model did not request a tool call. Response:"
  echo "$RESPONSE" | jq -r '.choices[0].message.content'
  exit 0
fi

TOOL_CALL_ID=$(echo "$RESPONSE" | jq -r '.choices[0].message.tool_calls[0].id')
TOOL_NAME=$(echo "$RESPONSE" | jq -r '.choices[0].message.tool_calls[0].function.name')
TOOL_ARGS=$(echo "$RESPONSE" | jq -r '.choices[0].message.tool_calls[0].function.arguments')
ASSISTANT_MSG=$(echo "$RESPONSE" | jq '.choices[0].message')

echo "Tool: $TOOL_NAME"
echo "Arguments: $TOOL_ARGS"

echo
echo "=== Step 2: Execute tool locally ==="
echo

# Simulate executing the ls tool
TOOL_RESULT=$(ls -1 . 2>&1 | head -20)
echo "$TOOL_RESULT"

echo
echo "=== Step 3: Send tool result back to LLM ==="
echo

curl -s http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "$(jq -n \
    --arg model "Qwen/Qwen3-1.7B-GGUF:Q8_0" \
    --argjson assistant "$ASSISTANT_MSG" \
    --arg tool_call_id "$TOOL_CALL_ID" \
    --arg tool_result "$TOOL_RESULT" \
    '{
      model: $model,
      messages: [
        {"role": "user", "content": "What files are in the current directory?"},
        $assistant,
        {"role": "tool", "tool_call_id": $tool_call_id, "content": $tool_result}
      ]
    }')" | jq .

echo
echo "=== Done! Three HTTP calls — that is tool calling. ==="
