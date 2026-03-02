#!/usr/bin/env bash
set -e

# Change to the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}═══════════════════════════════════════════${NC}"
echo -e "${BLUE}  K3s Demo Terminal Server${NC}"
echo -e "${BLUE}═══════════════════════════════════════════${NC}"
echo ""

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
  echo -e "${YELLOW}Installing dependencies...${NC}"
  npm install
  echo ""
fi

# Get local IP address
LOCAL_IP=$(ifconfig | grep 'inet ' | grep -v 127.0.0.1 | awk '{print $2}' | head -1)

echo -e "${GREEN}🚀 Starting Terminal Server${NC}"
echo ""
echo -e "${BLUE}Server will be accessible at:${NC}"
echo -e "  • Local:   http://127.0.0.1:3031"
if [ -n "$LOCAL_IP" ]; then
  echo -e "  • Network: http://${LOCAL_IP}:3031"
fi
echo ""
echo -e "${BLUE}Health check:${NC} http://127.0.0.1:3031/health"
echo ""
echo -e "${GREEN}Press Ctrl+C to stop${NC}"
echo ""

node server.js
