#!/usr/bin/env bash
# 01-list-models.sh — List available models from llama-server
# Usage: ./01-list-models.sh

set -euo pipefail

echo "=== GET /v1/models ==="
echo

curl -s http://localhost:8080/v1/models | jq .
