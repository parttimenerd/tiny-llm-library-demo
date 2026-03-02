Title: Let's create a tiny AI library together

Abstract:
We all want to do it. When you want to integrate AI into your tools, what do you do? You add langchain4j or spring-ai and run with it. But do you know how these libraries interact with the AI service providers or your local llama.cpp instance?

These libraries aren't magic, so let's peek behind the curtain and write a tiny AI library together. This way you'll discover the REST APIs that power it all.

## What's New in This Deck

🎯 **Interactive Curl Demos** — Click "Run" buttons on API demo slides to execute curl commands live against your llama-server instance (no manual terminal switching).

📊 **Mermaid Diagrams** — The MCP protocol slide now features a clean flowchart visualization instead of ASCII art.

⚡ **No More Click Reveals** — All slides redesigned for immediate impact. Bolder typography, fewer bullets, full information visible at once.

---

Style & tone:
- The talk should be engaging and interactive, with live coding and demos, but not distracting.
- Slightly opinionated and with a bit of humor, but never rant. Bubbly, nerdy.
- "LLM APIs are boring" is the tagline.
- Be precise: OpenAI-style endpoints are HTTP+JSON (not JSON-RPC). JSON-RPC shows up when we talk about MCP at the end.
- The fun part is how we use them, and how we can build tools on top of them.
- Emit TODOs for places where diagrams could help, always mention sources (use footnotes).
- Structure the talk into parts with "Part X: ..." slides.
- Use some color in the statement slides.

---

## Hard constraints (so this doesn't explode on stage)

- **Talk length**: 60 minutes (aim for >= 30 minutes live coding)
- **Audience**: curious Java devs (JUG), **Java 21**
- **Live runtime**: llama-server (llama.cpp) on the presenter machine via `./scripts/00-launch-llm.sh`
- **Core promise (title must be true)**: we build a tiny Java library on stage and use it to ship a working chat REPL (and, if time permits, a tool-calling REPL)
- **Keep it honest**: OpenAI-style endpoints are **HTTP + JSON**. JSON-RPC shows up only for MCP.

## The “LLM APIs are boring” comedy engine (repeatable bit)

Running gag: every time we do something with curl / raw JSON that feels tedious, we label it **BORING**, then “delete” the boredom by wrapping it in 10–20 lines of Java.

- Slide callout format: **BORING:** `curl ...` → **UNBORING:** `new TinyLLM(...).chatStream(...)`
- Phrase you can repeat: “Congrats, you’ve reinvented 3% of LangChain.”
- Audience prompt: “What else is boring here?” (answers: headers, JSON, streaming, retries, tools)

## Live demo contract (pin this; rehearse exactly this)

### Setup

```bash
# In one terminal, launch the LLM server (models cached indefinitely):
./scripts/00-launch-llm.sh --medium
```

Wait for: `llama-server: listening on http://0.0.0.0:8080`

- **llama-server base URL**: `http://localhost:8080`
- **Endpoints** (OpenAI-compat):
    - `GET /v1/models`
    - `POST /v1/chat/completions`
- **Streaming**: SSE (`Content-Type: text/event-stream`) with `data: {...}` events ending with `data: [DONE]`
- **Model** (single, predictable):
    - Fast (default): `Qwen/Qwen3-1.7B-GGUF:Q8_0`
    - Medium: `AaryanK/Qwen3.5-9B-GGUF:Q8_0`
    - Slow: `bartowski/Qwen_Qwen3.5-27B-GGUF:Q8_0`
    - Quote (for the "why this model" slide): "Supports both thinking and non-thinking modes with enhanced reasoning in both for significantly enhanced mathematics, coding, and commonsense. Excels at creative writing, role-playing, multi-turn dialogues, and instruction following." (source: LM Studio model description)

TODO(diagram): one picture showing “Java → HTTP POST → JSON → tokens (SSE)”

## Tiny library definition (say this early, verbatim)

“A tiny AI library is a small, boring wrapper around **HTTP + JSON** that gives me:
1) a `chatStream(...)` method for streaming tokens,
2) a `chat(...)` method for non-streaming (optional), and
3) just enough types to build an app.”

No frameworks, no magic, no provider abstraction layer.

## Run of show (60 minutes, survivable)

### Part 1: The boring truth (6–8 min)

- Hook: “We all add langchain4j/spring-ai… but what are they actually doing?”
- Local LLMs in 90 seconds (llama.cpp vs Ollama vs LM Studio): why good, why annoying
- Establish the contract: OpenAI-style = HTTP+JSON

TODO(diagram): 3-box picture: App → Library → LLM server

### Part 2: curl is the whole API (8–10 min)

- `GET /v1/models`
- `POST /v1/chat/completions` with a silly prompt:
    - “Roast the JUG Darmstadt (playful, not mean)”
- Streaming vs non-streaming: why streaming matters (latency + showmanship)

TODO: small slide: “Streaming = Server-Sent Events (SSE)” (footnote)

### Part 3: Live coding — build the tiny library (18–22 min)

Target outcome: a `TinyLLM` (or `LLMClient`) class that can stream a response.

Minimal surface area (keep it projector-friendly):
- Constructor: `(String baseUrl, String model, Consumer<String> onToken)`
- `listModels()`
- `chatStream(List<Message> messages)` (or `chatStream(List<Map<String,Object>> messages)` if you want ultra-minimal)

Rules for the live coding:
- The library does **direct calls** to the API (no indirection layers)
- Reasoning models return `reasoning_content` as a separate JSON field — no `<think>` parsing needed; mention as a nice API design detail
- We keep parsing minimal and show where femtojson helps

Checkpoint slides between steps (copy/paste escapes):
1) can call `/v1/models`
2) can make non-streaming chat work
3) can parse SSE and print tokens

### Part 4: Live coding — build the chat REPL (10–12 min)

- Create a tiny terminal REPL in Java that keeps conversation history
- Use the `TinyLLM` streaming callback to print tokens as they arrive

This is the first “title payoff” moment: “We created a tiny AI library together, and now we’re using it.”

### Part 5: Tool calling in 7 minutes (slides only, ultra-short) (6–7 min)

Keep it brutally minimal:
- One slide: JSON = the shapes we exchange (no parsing lecture)
- One slide: JSON Schema = how we describe tool arguments (footnote)
- One slide: tool calling loop (request → tool → result → continue)
- One line to keep it precise: we send OpenAI-style `tools` in the `/v1/chat/completions` request and expect `tool_calls` back.
- One slide: security reality check
    - Tools are code execution
    - Sandbox root, hidden files, size limits, timeouts

TODO(diagram): tool-calling loop (4 arrows)

### Part 6: Live coding — add one tool (10–12 min)

- Implement exactly one tool first (`ls`), then optionally `grep` if time
- Enforce basic safety (don’t get heckled):
    - sandbox root (`--root`)
    - deny dotfiles
    - cap output size
- Demo: “What files are in this repo?”

Cut rule: if anything gets weird, stop at `ls` and move on.

### Part 7: MCP teaser (2–3 min)

- One slide: MCP is JSON-RPC 2.0 + transports + tool servers
- Point: “still boring protocols… and that’s good”

### Part 8: Wrap-up (2 min)

- Recap: curl → tiny library → REPL → (tools) → MCP
- QR code + repo link + “run this yourself” instructions

## Cut list (when you fall behind — you will)

- Cut MCP first.
- Cut `grep` second.
- Cut non-streaming implementation.
- Cut “LM Studio vs llama.cpp” details (keep one slide only).

## Risks and mitigations

- **Model slow / weird output**: pre-warm the model, keep prompts short, rely on streaming.
- **Tool calling goes off-rails**: demo only `ls`, keep the tool schema tiny, cap outputs.
- **Audience asks about frameworks**: acknowledge value (retries, tracing, adapters), but re-focus: “we’re learning the boring base layer.”

## Sources (footnotes on relevant slides)

- Streaming/SSE explainer (Simon Willison)
- JSON Schema getting started (json-schema.org)
- MCP docs + JSON-RPC references (only in the teaser)
- LM Studio model description (for the Qwen blurb)
