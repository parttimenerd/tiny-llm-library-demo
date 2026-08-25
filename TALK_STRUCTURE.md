# Talk: "Let's create a tiny AI library together"

**Subtitle:** Peeking behind the curtain of LLM libraries
**Speaker:** Johannes Bechberger @ SAP SE
**Duration:** ~50 min
**Tagline (repeated 3×):** "LLM APIs are boring." — and that's a good thing.

---

## Structure

### Part 1 — Intro / The Illusion (~0–3 min)
Some people already use Spring AI / LangChain4j. Some don't use AI yet but want to.
The question: what actually happens under the hood?

Key framing: frameworks wrap simple REST calls. Libraries add real value (retries, adapters, RAG, tracing) but the core is HTTP + JSON.

What we'll build: `LLMClient`, `ChatBot`, `ToolSupport` — orange layer in the stack diagram.

---

### Part 2 — The API (~3–13 min)
Live curl demos against a local llama.cpp server (OpenAI-compatible endpoints).

Three endpoints — that's the entire surface:

| Endpoint | Method | Purpose |
|---|---|---|
| `/v1/models` | GET | List models |
| `/v1/chat/completions` | POST | Chat (blocking) |
| `/v1/chat/completions` | POST + `stream:true` | Streaming via SSE |

**Key insights shown:**
- **Stateless server** — you re-send the full message history every time. "Memory" is just a `List<Message>`.
- **SSE** (Server-Sent Events) is a 2004 browser standard, not an AI invention. Lines prefixed `data: ...`, terminated by `data: [DONE]`. Same endpoint, just add `"stream": true`.
- **Multimodal** is the same endpoint — `content` becomes an array with `text` and `image_url` items.

---

### Part 3 — Let's Build It (~13–25 min)
Live-code in Java with Copilot assistance.

**`LLMClient`** (skeleton shown, methods filled in live):
- `listModels()` — `GET /v1/models`
- `chat(messages)` — blocking POST → `choices[0].message.content`
- `chatStream(messages)` — SSE stream, `onToken` callback per token
- Static builders: `user()`, `assistant()`, `system()`

**`ChatBot`** (typed manually — only ~8 lines):
```
messages = new ArrayList()
while true:
    read input
    messages.add(user(input))
    response = client.chatStream(messages)
    messages.add(assistant(response))
```

Demo: run the chatbot live, take a suggestion from the audience.

---

### Part 4 — Tool Calling (~25–33 min)
The theory and live curl demo.

**Two building blocks:** JSON + JSON Schema (describes the shape of data the tool accepts).

**The 3-step flow:**
1. App sends messages **+ tools array** (JSON Schema per tool)
2. LLM responds with `finish_reason: "tool_calls"` + `tool_calls: [{name, arguments}]` — it does **not** execute anything
3. App executes the tool, sends `{role: "tool", tool_call_id, content: result}` back
4. LLM reads result → final answer (or asks for more tools)

The tool-calling loop is a **while loop**.

Three JSON shapes to understand:
- **Offer** — `tools` array in the request (with JSON Schema)
- **Request** — `finish_reason: "tool_calls"` + `tool_calls[]` in the response
- **Result** — `{role: "tool", tool_call_id, content}` message back to the LLM

Security callout: the model is untrusted. You are the executor. Sandbox everything.

---

### Part 5 — Adding Tools (~33–38 min)
Live-code `ToolSupport`.

```java
public class ToolSupport {
    registerTool(name, description, schema, handler)
    buildToolsJson()   // → tools array for the API
    handleToolLoop(client, messages)  // the while loop
}
```

`ToolChatBot` registers 4 sandboxed file tools: `ls`, `cat-paged`, `grep`, `find-file`.
Uses `femtoschema` to build JSON Schema fluently.

Demo: "What files are in this project?" — watch the model call `ls`, then `cat-paged`.
Try to access `/etc/passwd` — rejected by sandbox.

---

### Part 6 — Token Tracking & Summarization (~38–42 min)
Context windows aren't infinite. Tool-calling conversations fill them fast.

**Detecting the limit:** API returns `usage.prompt_tokens`; model context size comes from `GET /v1/models` → `meta.n_ctx_train`. Trigger at 80%.

**Four strategies:**

| Strategy | How | Trade-off |
|---|---|---|
| Dynamic Cutoff | Drop oldest messages | Simple, loses context |
| Rolling Summaries | Periodically compress via LLM | Continuous but lossy |
| **Hybrid Memory** ← our pick | Pin key msgs + summarize middle | Best balance |
| Externalized Memory | Vector DB for retrieval | Most powerful, most complex |

**Hybrid Memory — three tiers:**
- 📌 **Pinned** — system prompt, never summarized
- 🗜️ **Summarized** — middle messages, compressed by the LLM itself
- 💬 **Recent** — last 4 messages (2 pairs), verbatim

---

### Part 7 — Briefly: MCP (~42–45 min)
Slides only, no demo.

MCP (Model Context Protocol, by Anthropic) standardizes the tool-calling pattern so any AI app can connect to any tool server.

- Transport: **stdio** (subprocess, stdin/stdout) or **HTTP + SSE**
- Protocol: JSON-RPC 2.0
- Lifecycle: initialize (capability negotiation) → operate → shutdown
- Capabilities: **Tools** (do things), **Resources** (read-only data), **Prompts** (templates)

"We just built tool calling from scratch. MCP is the protocol wrapper around what we already know."

Why not just shell scripts? Safety, sandboxing, auditing, structured error handling — that's what the protocol layer buys you.

---

### Part 8 — The Realistic Way (~45–50 min)
Show the Copilot shortcut: give a single detailed prompt describing everything we just built and watch it generate a working Kotlin equivalent in ~30 seconds.

**Why understanding matters:** Without knowing the API, you can't verify what Copilot generates, can't spot wrong SSE parsing, can't tell if the tool loop is missing a retry, can't add a security sandbox.

Wrap-up: third "LLM APIs are boring" — boring means predictable, debuggable, works at 3 AM.

Final show-of-hands: "Who thinks they could implement this themselves now?"

---

## Code Overview

### `LLMClient`
Core HTTP wrapper. No external dependencies beyond `java.net.http`.

```java
new LLMClient(baseUrl, model, System.out::print)
    .chatStream(messages)   // streams tokens via callback
    .chat(messages)         // blocking, returns full response
    .chatRaw(messages, tools) // raw choice map, for tool calling
    .listModels()

LLMClient.user("hi")       // {role:user, content:hi}
LLMClient.assistant("ok")  // {role:assistant, content:ok}
LLMClient.system("be helpful")
```

### `ChatBot`
```java
var messages = new ArrayList<Map<String, Object>>();
while (true) {
    messages.add(LLMClient.user(scanner.nextLine()));
    messages.add(LLMClient.assistant(client.chatStream(messages)));
}
```

### `ToolSupport`
```java
toolSupport.registerTool("ls", "List directory", schema, args -> fileTools.ls(...));
toolSupport.buildToolsJson();       // → tools array for API request
toolSupport.handleToolLoop(client, messages); // while finish_reason == tool_calls: execute + loop
```

### `ToolChatBot`
Extends the chatbot with `ls`, `cat-paged`, `grep`, `find-file` tools.
All sandboxed to a configurable root directory.
Uses `femtoschema` for fluent JSON Schema construction.

---

## Tech Stack
- **Language:** Java 17+
- **HTTP:** `java.net.http.HttpClient` (JDK built-in)
- **JSON:** `femtojson` (tiny parser)
- **Schema:** `femtoschema` (fluent JSON Schema builder)
- **CLI:** `femtocli`
- **Local LLM:** llama.cpp (`llama-server`) — OpenAI-compatible API
- **Model:** Qwen3-1.7B (fast), Qwen3.5-9B (medium)
