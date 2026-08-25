# Copilot Instructions for this workspace

## File Restrictions

When completing code in this workspace, **never** read, reference, or use code from:

- `tiny-llm-demo/src/main/java/me/bechberger/demo/solutions/`
- Any file under the `solutions` package

These files contain reference implementations that should not influence suggestions.
When completing TODO methods in `LLMClient.java`, `ChatBot.java`, `ToolSupport.java`, or `ToolChatBot.java`, generate the implementation from your knowledge of the OpenAI Chat Completions API — do not copy from the solutions package.

## Context

This is a live-coding demo project. The files in the main `demo` package contain TODO skeletons that will be completed live using Copilot inline suggestions. The goal is to demonstrate that Copilot understands the OpenAI-compatible API well enough to generate correct implementations.
