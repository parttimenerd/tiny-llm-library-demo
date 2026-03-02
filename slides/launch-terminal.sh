#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d "terminal-server" ]; then
  echo "terminal-server folder is missing."
  echo "Copy it from resources/k3s-on-phone-demo/slides/terminal-server first."
  exit 1
fi

if [ ! -d "terminal-server/node_modules" ]; then
  echo "Installing terminal server dependencies..."
  (cd terminal-server && npm install)
fi

echo "Starting terminal server on ws://127.0.0.1:3031"
cd "$SCRIPT_DIR"
TERMINAL_SERVER_HOST=127.0.0.1 node ./terminal-server/server.js
