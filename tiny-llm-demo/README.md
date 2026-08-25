# tiny-llm-demo

A minimal Java 21+ project that demonstrates calling **llama-server's OpenAI-compatible** local endpoints from scratch — no LLM framework required.

Built for the JUG talk: **"Let's create a tiny AI library together"**

## Prerequisites

- **Java 21+** (tested with Java 21 LTS)
- **Maven 3.9+**
- **llama-server** (llama.cpp) running at `http://localhost:8080`
- **jq** (for curl scripts)

### Model Setup

```bash
# Start the server with both demo models
llama-server -hf Qwen/Qwen3-1.7B-GGUF:Q8_0 -hf bartowski/Qwen_Qwen3.5-27B-GGUF:Q8_0

# Verify it's available (in another terminal)
curl http://localhost:8080/v1/models | jq .
```

## Build

```bash
cd tiny-llm-demo
mvn clean package
```

This produces a fat JAR at `target/tiny-llm-demo-1.0-SNAPSHOT.jar` (~150KB).

## Running the Demos

### Curl Scripts

```bash
# List models
./scripts/01-list-models.sh

# Simple chat (non-streaming)
./scripts/02-simple-chat.sh

# Multi-turn conversation
./scripts/03-conversation.sh

# Streaming via SSE
./scripts/04-streaming.sh

# Tool calling (3-step flow)
./scripts/05-tool-call.sh
```

### Solution: Basic Chatbot

```bash
java -cp target/tiny-llm-demo-1.0-SNAPSHOT.jar me.bechberger.demo.solutions.ChatBot \
  --model Qwen/Qwen3-1.7B-GGUF:Q8_0 \
  --base-url http://localhost:8080
```

### Solution: Tool Chatbot

```bash
java -cp target/tiny-llm-demo-1.0-SNAPSHOT.jar me.bechberger.demo.solutions.ToolChatBot
```

## Live Coding Sequence

### Section 3: Basic Chat Client (~16 min)

1. **LLMClient.listModels()** — GET `/v1/models`, parse JSON
2. **LLMClient.chat()** — POST with messages, return content
3. **LLMClient.chatStream()** — POST with `stream:true`, parse SSE, call `onToken`
4. **ChatBot.main()** — REPL loop with conversation history

### Section 5: Tool Support (~13 min)

1. **ToolSupport.registerTool()** — store name + schema + handler
2. **ToolSupport.buildToolsJson()** — build the `tools` array
3. **ToolSupport.handleToolLoop()** — while loop: call LLM → execute tools → repeat
4. Demo with **ToolChatBot**

## Dependencies

- `me.bechberger.util:femtoschema:0.1.0` — JSON Schema from Java types
- `me.bechberger.util:femtojson:0.2.1` — tiny JSON parser (transitive)
- JDK's `java.net.http.HttpClient` — no third-party HTTP client

## Key Design Decisions

- **Named endpoints, API keys and default models** — `~/.config/tiny-llm-library/config.config` is a standard Java properties file (`gardener.url`, optional `gardener.key` and `gardener.model`, plus global `default.model`), so `--base-url gardener` carries URL, key and default model in one go. The key is sent as `Authorization: Bearer <key>`; no entry = no auth header, which is all local llama-server needs. A `#token` URL fragment still overrides the config file, and `--model` overrides the configured default model. (`$XDG_CONFIG_HOME` honored; `TINY_LLM_CONFIG` overrides the location.)
- **Live-coding gaps on stage** — `ToolChatBot` keeps its TODOs (system prompt, tool loop, run-command tool) with the boring parts pre-extracted (`SYSTEM_PROMPT` constant, one-verbose-then-one-line tool registrations via `CodingTools.register`). `scripts/reset-live-coding.sh` additionally restores the `ToolSupport` skeleton.
- **Context compaction in the coding agents** — once the prompt exceeds 80% of the model's context window (auto-detected; override with `--max-tokens`), old history is folded into a single `[Conversation summary]` message while the system prompt stays pinned and the recent turns verbatim — driven by real token-usage data from the API (`Compactor` helper; same "hybrid memory" strategy as the summarizing chatbot).
- **Streaming by default** via `Consumer<String> onToken` callback
- **No Jackson** — uses femtojson for JSON parsing, manual serialization for output
- **Reasoning in separate field** — reasoning models return `reasoning_content` as a separate JSON field; no `<think>` parsing needed
- **An agentic REPL framework in `util/`** — `Repl` (dynamic prompt with live mode badge, backslash-continued multi-line input, echo when piped), `Commands` (slash-command DSL), `Compactor` (hybrid-memory compaction), `SessionLog` (a transcript per session in `~/.tiny-llm-library/sessions/`). CodingAgent surfaces them as Claude-Code-style affordances: `/mode` cycles NORMAL → AUTO-EDIT → YOLO (`/yolo` toggles) with a badge in the prompt, `/clear` resets the conversation, `/compact` folds history manually, `/tokens` shows usage.
- **Boring plumbing in helpers, interesting logic in the agent** — `util.Commands` (REPL command DSL: `/plan`, `/run`, `/yolo`, `/todos`, `/help`, `exit`/`quit` with aliases and auto-generated help; unknown slash commands rejected locally), `util.Repl` (prompt loop, EOF handling, command dispatch), `CodingTools` (tool registrations; robust arg access that feeds "missing argument" errors back to the model instead of storing nulls) — leaving CodingAgent itself with only the talk-worthy parts: pinned context, plan mode, confirmation policy. CodingAgent is a composition of protected hooks (`onStart`, `createClient`, `createToolSupport`, `buildSystemPrompt`, `registerCommands`, `chat`, `syncConversation`); SkillCodingAgent subclasses it adding only a small delta of skill discovery/activation code (`.claude/skills/*/SKILL.md`, `skill` tool, `/skill(s)` commands), and since the system prompt is re-synced before every LLM call, skill (de)activation takes effect mid-conversation
- **Solution files in `solutions/` package** — avoids compilation conflicts with skeletons
- **Security-first tools** — sandboxed to a root directory, no dotfiles, size- and time-limited; the coding agent additionally gets write tools (create-file/write-file/create-folder plus `edit` for surgical replacements) and a `run` (shell exec) tool so it can build and verify its own work — all confined to the sandbox root with output caps and timeouts. Agent-initiated `run`/`delete` and plan acceptance ask the user for confirmation; `/yolo` toggles auto-approval of everything for autonomous sessions. ToolChatBot's variant of command execution still asks for interactive user confirmation

Write a short, fun and nerdy opening monologue of my talk "Let's create a tiny AI library together, 
Copilot powered" that I give at GitHub Copilot Dev Day Berlin at adesso SE (as the second talk in the evening). 
Thank Sandra Ahlgrimm for inviting me and adesso SE for hosting us all.