#!/usr/bin/env bash
# 00-launch-llm.sh — Launch local LLM server (background)
# Usage: ./00-launch-llm.sh [--fast|--medium|--slow]
# Default: medium (9B)

set -euo pipefail

# Configure HuggingFace cache to persist models forever
export HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
export HF_HUB_CACHE="${HF_HUB_CACHE:-$HF_HOME/hub}"
mkdir -p "$HF_HUB_CACHE"

usage() {
  cat <<USAGE
Usage: $0 [--slow] [--medium] [--fast]

Options:
  --slow         Use the slower, larger model (bartowski/Qwen_Qwen3.5-27B-GGUF:Q8_0)
  --medium       Use the medium model — default (AaryanK/Qwen3.5-9B-GGUF:Q8_0)
  --fast         Use the faster, small model (bartowski/Qwen3.5-2B-Instruct-GGUF:Q8_0)
  -h, --help     Show this help and exit
USAGE
}

# Default mode
MODE="medium"

# CLI parsing
while [[ $# -gt 0 ]]; do
  case "$1" in
    --slow|slow)
      MODE="slow"
      shift
      ;;
    --medium|medium)
      MODE="medium"
      shift
      ;;
    --fast|fast)
      MODE="fast"
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

# Start the selected mode
echo "Model cache directory: $HF_HUB_CACHE"
if [[ "$MODE" == "fast" ]]; then
  echo "Starting llama-server (fast mode)..."
  llama-server -hf unsloth/Qwen3.5-2B-GGUF:Qwen3.5-2B-Q8_0.gguf
elif [[ "$MODE" == "medium" ]]; then
  echo "Starting llama-server (medium mode)..."
  llama-server -hf unsloth/Qwen3.5-9B-GGUF -hff Qwen3.5-9B-Q8_0.gguf
else
  echo "Starting llama-server (slow mode)..."
  llama-server -hf unsloth/Qwen3.8-27B-GGUF:Qwen3.8-27B-UD-Q4_K_XL.gguf
fi