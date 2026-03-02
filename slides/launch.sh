#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

LAUNCH_TERMINAL=false
OPEN_BROWSER=false
TERMINAL_PID=""

usage() {
  echo "Usage: $0 [--terminal] [--open]"
  echo
  echo "  --terminal   start local terminal server (ws://127.0.0.1:3031)"
  echo "  --open       open browser automatically"
  echo "  --help, -h   show this help"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --terminal)
      LAUNCH_TERMINAL=true
      shift
      ;;
    --open)
      OPEN_BROWSER=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

cleanup() {
  if [[ -n "$TERMINAL_PID" ]]; then
    kill "$TERMINAL_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if [[ ! -d node_modules ]]; then
  npm install
fi

if [[ "$LAUNCH_TERMINAL" == true ]]; then
  ./launch-terminal.sh &
  TERMINAL_PID=$!
  sleep 1
  export VITE_TERMINAL_WS_URL="ws://127.0.0.1:3031"
  export VITE_TERMINAL_AVAILABLE="true"
else
  export VITE_TERMINAL_AVAILABLE="false"
fi

if [[ "$OPEN_BROWSER" == true ]]; then
  npx slidev --port 3032 --open
else
  npx slidev --port 3032
fi
