Let's create a tiny AI library together (talk)
================================================

We all want to do it. When you want to integrate AI into your tools, what do you do? You add langchain4j or spring-ai and run with it. But do you know how these libraries interact with the AI service providers or your local llama-server instance?

These libraries aren't magic, so let's peek behind the curtain and write a tiny AI library together. This way you'll discover the REST APIs that power it all.

Follow along (attendees)
-----------------------

This talk uses a local LLM via **llama.cpp** (llama-server) and its **OpenAI-compatible** endpoints.

### Quick Start

1) Install llama.cpp: https://github.com/ggerganov/llama.cpp

2) Start the server with the launch script (models are cached indefinitely):

```bash
# Start with default fast model (1.7B)
./scripts/00-launch-llm.sh

# Or use other model sizes:
./scripts/00-launch-llm.sh --medium  # 9B model
./scripts/00-launch-llm.sh --slow    # 27B model
```

3) Quick sanity check (models endpoint):

```bash
curl http://localhost:8080/v1/models
```

Available scripts:
- `./scripts/00-launch-llm.sh` — Launch llama-server with model caching
- `./scripts/01-list-models.sh` — List available models from running server
- `./scripts/02-simple-chat.sh` — Simple chat example
- `./scripts/03-conversation.sh` — Multi-turn conversation example
- `./scripts/04-streaming.sh` — Streaming response example
- `./scripts/05-tool-call.sh` — Tool-calling example

Build the slides:

```bash
cd slides && npm install && npx slidev
```

Or view them online: https://parttimenerd.github.io/tiny-llm-library-demo

Speaker
-------
- [Johannes Bechberger](https://mostlynerdless.de)

Resources
---------
- **OpenAI Conversations API reference:** [developers.openai.com](https://developers.openai.com/api/reference/resources/conversations) — Official API reference for the conversations / chat completions endpoint.
- **Access the OpenAI API with curl:** [moritzstrube.substack.com](https://moritzstrube.substack.com/p/access-the-openai-api-with-curl) — Walkthrough of calling the OpenAI-compatible API using curl.
- **How streaming LLM APIs work:** [til.simonwillison.net](https://til.simonwillison.net/llms/streaming-llm-apis) — Explanation of Server-Sent Events and streaming in LLM APIs.
- **MCP under the hood:** [freecodecamp.org](https://www.freecodecamp.org/news/how-does-an-mcp-work-under-the-hood/) — How MCP works internally: workflow, transports, JSON-RPC messages.
- **Model Context Protocol:** [modelcontextprotocol.io](https://modelcontextprotocol.io/) — Official MCP specification and documentation.
- **Model Context Protocol (Wikipedia):** [en.wikipedia.org](https://en.wikipedia.org/wiki/Model_Context_Protocol) — MCP overview and history.
- **Introducing the Model Context Protocol:** [anthropic.com](https://www.anthropic.com/news/model-context-protocol) — Anthropic's original MCP announcement.
- **Is MCP really that good?:** [reddit.com/r/mcp](https://www.reddit.com/r/mcp/comments/1jl10ne/is_mcp_really_that_good/) — Community discussion on MCP adoption and real-world value.
- **JSON Schema getting started:** [json-schema.org](https://json-schema.org/learn/getting-started-step-by-step) — Step-by-step introduction to JSON Schema.
- **JSON (Wikipedia):** [en.wikipedia.org](https://en.wikipedia.org/wiki/JSON) — JSON syntax and data types reference.
- **Writing a tiny JSON parser:** [mostlynerdless.de](https://mostlynerdless.de) — Blog post on building a minimal JSON parser from scratch.
- **JSON-RPC (Wikipedia):** [en.wikipedia.org](https://en.wikipedia.org/wiki/JSON-RPC) — JSON-RPC protocol overview, history (2005/2010), and comparison with other RPC systems.
- **Structured output (LM Studio):** [lmstudio.ai](https://lmstudio.ai/docs/developer/openai-compat/structured-output) — How to enforce JSON Schema–based structured output via the chat completions API.
- **LangChain4j musings:** [blog.frankel.ch](https://blog.frankel.ch/langchain4j-musings/) — Nicolas Fränkel's reflections on using LangChain4j.
- **LangChain4j musings, six months later:** [blog.frankel.ch](https://blog.frankel.ch/langchain4j-musings-six-months-after/) — Follow-up post on LangChain4j experience.
- **Build AI apps with LangChain4j:** [javapro.io](https://javapro.io/2025/04/23/build-ai-apps-and-agents-in-java-hands-on-with-langchain4j/) — Hands-on guide to building AI apps and agents in Java with LangChain4j.
- **RAG is dead:** [medium.com](https://medium.com/data-science-in-your-pocket/rag-is-dead-5fd1350def6d) — Provocative take on RAG's limitations and alternatives.
- **Don’t Let Your AI Agent Forget: Smarter Strategies for Summarizing Message History** [agentailor.com](https://blog.agentailor.com/posts/message-history-summarization-strategies)

License
-------
MIT License, see [LICENSE](LICENSE)
