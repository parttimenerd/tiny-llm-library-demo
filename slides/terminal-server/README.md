# Terminal Server for K3s Demo Slides

This is a localhost-only terminal server that enables running demo scripts directly from the Slidev presentation.

## Security

⚠️ **This server only accepts connections from localhost (127.0.0.1)**

- WebSocket connections from non-localhost IPs are rejected
- CORS restricted to `http://localhost:3030`
- Script execution limited to whitelisted directories only
- No arbitrary command execution

## Setup

```bash
npm install
```

## Running

```bash
./start.sh
```

Or manually:
```bash
npm start
```

The server will start on `http://127.0.0.1:3031`

## Logging

Logging is configurable via environment variables:

- `TERMINAL_SERVER_LOG_LEVEL` (default: `info`)
  - Supported: `error`, `warn`, `info`, `debug`
- `TERMINAL_SERVER_LOG_FORMAT` (default: `text`)
  - Supported: `text`, `json`
- `TERMINAL_SERVER_LOG_HTTP` (default: `true`)
  - Set to `false` to disable per-request HTTP logs

Example:

```bash
TERMINAL_SERVER_LOG_LEVEL=debug \
TERMINAL_SERVER_LOG_FORMAT=json \
TERMINAL_SERVER_LOG_HTTP=false \
npm start
```

## API

### Health Check
```
GET http://127.0.0.1:3031/health
```

Response:
```json
{
  "status": "ok",
  "port": 3031
}
```

### WebSocket Terminal

Connect to `ws://127.0.0.1:3031`

**Messages to server:**

Start terminal:
```json
{
  "type": "start",
  "cols": 80,
  "rows": 24
}
```

Send input:
```json
{
  "type": "data",
  "data": "ls\n"
}
```

Resize terminal:
```json
{
  "type": "resize",
  "cols": 100,
  "rows": 30
}
```

Execute script:
```json
{
  "type": "execute",
  "script": "/absolute/path/to/script.sh"
}
```

**Messages from server:**

Terminal started:
```json
{
  "type": "started"
}
```

Terminal output:
```json
{
  "type": "data",
  "data": "output text"
}
```

Terminal exited:
```json
{
  "type": "exit",
  "exitCode": 0
}
```

Error:
```json
{
  "type": "error",
  "message": "error description"
}
```

## Whitelisted Script Directories

Only scripts in these directories can be executed:

- `../../echo-demo/scripts/`
- `../../chat-demo/scripts/`
- `../../demo/scripts/`

## Dependencies

- `ws` - WebSocket server
- `node-pty` - Terminal emulation with PTY support
