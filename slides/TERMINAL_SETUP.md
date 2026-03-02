# Terminal Integration Setup

## Quick Start

The presentation includes interactive **curl demo slides** with a "Run" button for each script.

### Launch with Terminal Support

```bash
cd slides
./launch.sh --terminal
```

This will:
1. Start the terminal server on `ws://127.0.0.1:3031`
2. Open the presentation on `http://localhost:3032`
3. Enable **Run** buttons on all curl demo slides

Press `Ctrl+T` to open the terminal panel and execute scripts.

### Without Terminal (Slides Only)

```bash
cd slides
npm run dev
```

## Curl Demo Scripts

The presentation includes runnable curl examples:

- **List Models** → `../tiny-llm-demo/scripts/01-list-models.sh`
- **Simple Chat** → `../tiny-llm-demo/scripts/02-simple-chat.sh`
- **Conversation History** → `../tiny-llm-demo/scripts/03-conversation.sh`
- **Streaming (SSE)** → `../tiny-llm-demo/scripts/04-streaming.sh`
- **Tool Calling** → `../tiny-llm-demo/scripts/05-tool-call.sh`

Each demo has an orange **"Run"** button next to the script path. Click it to execute in the terminal.

## Features

✅ Full terminal emulation (xterm.js)  
✅ One-click script execution  
✅ Ctrl+T to toggle terminal  
✅ Copy/paste, search (Ctrl+F), zoom (Ctrl+±)  
✅ Persistent across script runs  

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Ctrl+T` | Open/close terminal |
| `Ctrl+C` | Interrupt running script |
| `Ctrl+L` | Clear terminal |
| `Ctrl+F` | Search terminal output |
| `Ctrl+±` | Zoom font size |
| `Ctrl+Esc` | Close terminal |

## Troubleshooting

**"Terminal not available" message:**
- Ensure terminal server was started (`./launch.sh --terminal`)
- Check that port 3031 is not in use: `lsof -i :3031`

**Scripts don't execute:**
- Verify llama-server is running: `curl http://localhost:8080/v1/models`
- Check terminal server logs for errors

**npm dependencies missing:**
```bash
cd slides && npm install
cd terminal-server && npm install
```

## Architecture

```
┌─────────────────────────────────────┐
│   Browser (Slidev + xterm.js)      │
│     - Display slides                │
│     - Show terminal UI              │
└──────────────┬──────────────────────┘
               │ WebSocket
               ↓
┌─────────────────────────────────────┐
│   Terminal Server (Node.js + PTY)   │
│     - Execute scripts               │
│     - Stream output                 │
│     - Manage sessions               │
└──────────────┬──────────────────────┘
               │ shell.js
               ↓
┌─────────────────────────────────────┐
│   Shell (bash)                      │
│     - Run curl commands             │
│     - Call llama-server API         │
└─────────────────────────────────────┘
```

## Whitelisted Script Directories

For security, only scripts in these folders can execute:

- `../tiny-llm-demo/scripts/` (curl demos)

Add your own scripts and update whitelist in `terminal-server/server.js`.
