# tiny-llm-demo

A minimal Java 21+ project for the talk **"Let's create a tiny LLM library together"**.

---

## Talk Cheat Sheet

Quick reference for on-stage. Everything assumes `--base-url gardener` (the SAP endpoint).
Reset command before each demo: `bash scripts/reset-live-coding.sh && mvn -q package`

---

### Before you go on stage

1. `cd tiny-llm-demo && mvn -q package` — fat JAR must be fresh
2. Verify endpoint: `java -jar target/tiny-llm-demo.jar solutions.ChatBot --base-url gardener`
   → should greet with "ChatBot ready. Model: kimi-k3"
3. Font size up, terminal full-screen, slides on second display

---

### Part 1 — Intro (0–3 min)  ·  slides only

"What actually happens under the hood?" — frameworks wrap simple REST calls.
Tagline you'll repeat three times: **"LLM APIs are boring."**

---

### Part 2 — The API (3–13 min)  ·  curl scripts

Five curl commands, three endpoints. Run from `tiny-llm-demo/`:

```bash
./scripts/01-list-models.sh        # GET /v1/models
./scripts/02-simple-chat.sh        # POST /v1/chat/completions (blocking)
./scripts/03-conversation.sh       # same, multi-turn — show the growing messages array
./scripts/04-streaming.sh          # POST + stream:true → SSE
./scripts/05-tool-call.sh          # 3-step tool loop: offer → request → result
```

Key points to make:
- Server is **stateless** — you resend the full history every time. Memory = `List<Message>`.
- SSE is a 2004 browser standard, not AI magic. Lines prefixed `data:`, ends with `data: [DONE]`.
- Tool calling is just **3 JSON shapes** — offer (tools in request), request (model returns `finish_reason: tool_calls`), result (`role: tool` message back).

**What the real API responses look like (Qwen3.5-9B, local llama-server):**

Non-streaming response:
```json
{
  "choices": [{
    "message": { "role": "assistant", "content": "Java is the programming language that treats the JVM like a separate, high-maintenance roommate who insists on running everything through a massive security check." },
    "finish_reason": "stop"
  }],
  "usage": { "prompt_tokens": 20, "completion_tokens": 40, "total_tokens": 60 }
}
```

Streaming chunks (first chunk announces `role`, subsequent chunks carry `content`):
```
data: {"choices":[{"delta":{"role":"assistant","content":null}}]}
data: {"choices":[{"delta":{"content":"Ah"}}]}
data: {"choices":[{"delta":{"content":","}}]}
data: {"choices":[{"delta":{"content":" Java"}}]}
data: {"choices":[{"delta":{"content":"."}}]}
...
data: [DONE]
```

Tool calling response:
```json
{
  "choices": [{
    "finish_reason": "tool_calls",
    "message": {
      "role": "assistant",
      "tool_calls": [{
        "id": "kcll1P3OvIk6Ly6uArMrv8",
        "type": "function",
        "function": { "name": "ls", "arguments": "{\"path\":\".\"}"}
      }]
    }
  }]
}
```

---

### Part 3 — Live Coding: LLMClient + ChatBot (13–25 min)

**Reset first:** `bash scripts/reset-live-coding.sh && mvn -q package`

Open `src/main/java/me/bechberger/demo/LLMClient.java`.  
Three `@stub` gaps to fill — Copilot does most of the work:

| Method | What to type / accept from Copilot |
|--------|-------------------------------------|
| `chat()` | POST → parse `choices[0].message.content` |
| `processSSELine()` | strip `"data: "`, return null on `[DONE]`, parse `delta.content` |
| `chatStream()` | loop `readLine()` → call `processSSELine` → accumulate → return |

Then open `src/main/java/me/bechberger/demo/ChatBot.java` — fill in the stub:

```java
var client = options.createClient(builder);
var repl = builder.build();
repl.greet("ChatBot ready. Model: " + options.resolveModel());
repl.run(input -> {
    messages.add(LLMClient.user(input));
    System.out.print(Ansi.bold(Ansi.green("\nAssistant: ")));
    String response = client.chatStream(messages);
    messages.add(LLMClient.assistant(response));
    System.out.println();
});
return 0;
```

**Build & demo:**
```bash
mvn -q package
java -jar target/tiny-llm-demo.jar ChatBot --base-url gardener
```
Ask: *"Write a short, fun opening monologue for a talk called 'Let's create a tiny LLM library together' at JavaZone"*

Fallback if Copilot struggles: paste from `src/main/java/me/bechberger/demo/solutions/ChatBot.java`.

---

### Part 4 — Tool Calling theory (25–33 min)  ·  slides only

Show the three JSON shapes. Run `./scripts/05-tool-call.sh` for the live curl demo.

---

### Part 5 — Live Coding: ToolSupport + ToolChatBot (33–38 min)

Open `src/main/java/me/bechberger/demo/ToolSupport.java` (already stubbed by reset script).  
Six gaps — let Copilot generate, then walk through:

| Method | Key idea to point out |
|--------|----------------------|
| `registerTool()` | stores in a `Map<String, ToolDef>` |
| `buildToolsJson()` | maps each tool to `{type:"function", function:{name,description,parameters}}` |
| `handleToolLoop()` | **the while loop** — `chatRaw` → check `finish_reason` → execute tools → repeat |
| `extractContent()` | `choice.message.content` |
| `processToolCalls()` | add assistant message, execute each tool_call, add tool results |
| `executeToolCall()` | extract id+name+arguments → call handler → return `{role:tool, tool_call_id, content}` |

**Build & demo:**
```bash
mvn -q package
java -jar target/tiny-llm-demo.jar ToolChatBot --base-url gardener
```
Try:
- *"What files are in this project?"* — watch it call `ls`, then `read-file`
- *"Describe what this project does"* — chains ls + grep to synthesise
- *"Show me /etc/passwd"* — sandbox rejects it

Fallback: paste from `src/main/java/me/bechberger/demo/solutions/ToolSupport.java`.

---

### Part 6 — Token Tracking & Compaction (38–42 min)  ·  slides only

Context windows aren't infinite. Show the hybrid memory strategy:
- 📌 **Pinned** — system prompt, always fresh
- 🗜️ **Summarized** — old middle turns, folded by the LLM itself
- 💬 **Recent** — last few turns verbatim

Trigger at 80% of context window (auto-detected from `/v1/models` → `meta.n_ctx_train`).

---

### Part 7 — MCP (42–45 min)  ·  slides only

"We just built tool calling from scratch. MCP is the protocol wrapper around what we already know."
Transport: stdio or HTTP+SSE. Protocol: JSON-RPC 2.0. Capabilities: Tools, Resources, Prompts.

---

### Part 8 — CodingAgent demo (45–48 min)

```bash
java -jar target/tiny-llm-demo.jar CodingAgent --base-url gardener
```

Type `/yolo` then: *"Build a small calculator app with Maven in a subfolder"*

Watch: plan proposal → you approve → TODOs created → files written → `mvn package` → verify.

Key commands to mention: `/mode` (NORMAL→AUTO-EDIT→YOLO), `/plan <goal>`, `/todo`, `/clear`, `/tokens`.

---

### Part 9 — SkillCodingAgent demo (48–50 min)

```bash
java -jar target/tiny-llm-demo.jar SkillCodingAgent --base-url gardener
```

Just ask: *"Tell me about this project like a viking"*  
The model discovers the viking skill on its own and activates it via the `skill` tool.

Then: *"Let's write a new skill together"* — ask the audience what it should do.

Skills live in `.claude/skills/<name>/SKILL.md`. Use `/skills` to list, `/skill <name>` to toggle.

---

### Part 10 — Wrap-up (50 min)  ·  slides only

Show the "realistic way" — give Copilot the full spec and watch it generate a Kotlin equivalent.
Third and final: **"LLM APIs are boring."** — boring means predictable, debuggable, works at 3am.
Final show of hands: *"Who thinks they could implement this themselves now?"*

---

## Pre-Talk Checklist

- [ ] `mvn -q package` succeeds
- [ ] `java -jar target/tiny-llm-demo.jar solutions.ChatBot --base-url gardener` — responds
- [ ] `bash scripts/reset-live-coding.sh` — ToolSupport stubs restored
- [ ] Slides open on second display, presenter view on
- [ ] Terminal font ~18pt, window maximised
- [ ] `~/.config/tiny-llm-library/config.config` has valid `gardener.*` entries

---

## Opening Monologue Prompt (JavaZone)

Run this with `solutions.ChatBot` as the opening of the talk:

```
Write a short (3-4 sentence), fun and nerdy opening monologue for a talk called
"Let's create a tiny LLM library together" at JavaZone Oslo (the largest Java
conference in Scandinavia). Thank the organizers for the excellent food and hospitality.
Tone: enthusiastic, slightly self-deprecating, technical crowd.
```

---

## Build

```bash
cd tiny-llm-demo
mvn clean package
```

Produces `target/tiny-llm-demo.jar` (~150KB fat JAR). All commands use:
```bash
java -jar target/tiny-llm-demo.jar <ClassName> [options]
```
Short class names resolve automatically: `ChatBot` → `me.bechberger.demo.ChatBot`,
`solutions.ChatBot` → `me.bechberger.demo.solutions.ChatBot`.

## Configuration

Named endpoints live in `~/.config/tiny-llm-library/config.config`:

```properties
gardener.url   = https://models.answering-machine.utility.gardener.cloud.sap
gardener.key   = sk-...
gardener.model = kimi-k3

default.model  = kimi-k3
```

`--base-url gardener` resolves URL + key + model in one go.
Raw URLs, `url#token` fragments, and `--model` overrides all work too.

## Live-Coding Reset

```bash
bash scripts/reset-live-coding.sh
```

Restores `ToolSupport.java` to its TODO-stub skeleton (the part live-coded during Part 5).
`git checkout -- src/main/java/me/bechberger/demo/ToolSupport.java` undoes it.

Demo files (`ChatBot.java`, `ToolChatBot.java`, etc.) are generated from solutions:
```bash
python3 scripts/sync-demo.py generate   # regenerate stubs from solutions
python3 scripts/sync-demo.py check      # verify they're in sync
```

## Dependencies

- `me.bechberger.util:femtoschema:0.1.2` — JSON Schema builder
- `me.bechberger.util:femtojson:0.4.2` — tiny JSON parser (0.2.x had UTF-8 bug)
- `java.net.http.HttpClient` — no third-party HTTP client

## Prerequisites (local server alternative)

If running without `gardener`, start llama-server locally:

```bash
llama-server -hf AaryanK/Qwen3.5-9B-GGUF:Q8_0
curl http://localhost:8080/v1/models | jq .
```

Then use `--base-url http://localhost:8080 --model AaryanK/Qwen3.5-9B-GGUF:Q8_0`.
