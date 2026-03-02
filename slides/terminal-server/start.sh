#!/usr/bin/env bash
set -e

echo "Starting K3s Demo Terminal Server..."
echo ""

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
  echo "Installing dependencies..."
  npm install
  echo ""
fi

echo "Server will run on http://127.0.0.1:3031"
echo "Health check: http://127.0.0.1:3031/health"
echo ""
echo "Press Ctrl+C to stop"
echo ""

npm start
