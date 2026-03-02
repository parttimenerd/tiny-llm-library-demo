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

## Project Structure

```
tiny-llm-demo/
├── pom.xml                           # Java 21, shade plugin for fat JAR
├── scripts/                          # Curl demo scripts (self-contained)
│   ├── 01-list-models.sh
│   ├── 02-simple-chat.sh
│   ├── 03-conversation.sh
│   ├── 04-streaming.sh
│   └── 05-tool-call.sh
└── src/main/java/me/bechberger/demo/
    ├── http/
    │   └── HttpHelper.java           # Pre-written HTTP helper (boring plumbing)
    ├── LLMClient.java                # Skeleton — live-coded in Section 3
    ├── ChatBot.java                  # Skeleton — live-coded in Section 3
    ├── ToolSupport.java              # Skeleton — live-coded in Section 5
    ├── FileTools.java                # Skeleton — live-coded in Section 5
    ├── GrepTool.java                 # Skeleton — live-coded in Section 5
    ├── ToolChatBot.java              # Skeleton — live-coded in Section 5
    └── solutions/                    # Complete working implementations
        ├── LLMClient.java
        ├── ChatBot.java
        ├── ToolSupport.java
        ├── FileTools.java
        ├── GrepTool.java
        └── ToolChatBot.java
```

## Build

```bash
cd tiny-llm-demo
mvn clean package -DskipTests
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
java -cp target/tiny-llm-demo-1.0-SNAPSHOT.jar me.bechberger.demo.solutions.ToolChatBot \
  --model Qwen/Qwen3-1.7B-GGUF:Q8_0 \
  --base-url http://localhost:8080 \
  --root .
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
4. **FileTools.ls()** + **FileTools.catPaged()** — sandboxed implementations
5. Demo with **ToolChatBot**

## Dependencies

- `me.bechberger.util:femtoschema:0.1.0` — JSON Schema from Java types
- `me.bechberger.util:femtojson:0.2.1` — tiny JSON parser (transitive)
- JDK's `java.net.http.HttpClient` — no third-party HTTP client

## Key Design Decisions

- **Streaming by default** via `Consumer<String> onToken` callback
- **No Jackson** — uses femtojson for JSON parsing, manual serialization for output
- **Reasoning in separate field** — reasoning models return `reasoning_content` as a separate JSON field; no `<think>` parsing needed
- **Solution files in `solutions/` package** — avoids compilation conflicts with skeletons
- **Security-first tools** — sandboxed, read-only, no dotfiles, size-limited
