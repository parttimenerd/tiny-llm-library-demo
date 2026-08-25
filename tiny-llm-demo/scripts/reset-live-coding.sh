#!/usr/bin/env bash
# Restore the live-coding gaps for the talk.
#
#  - ToolSupport.java -> its TODO skeleton (commit 2ea2d73), which the talk fills in
#    live with Copilot. NOTE: CodingAgent/SkillCodingAgent need the complete
#    ToolSupport (they use setOnToolCall) - they compile again after the live part,
#    or use: git checkout -- src/main/java/me/bechberger/demo/ToolSupport.java
#  - ToolChatBot keeps its in-file TODOs (system message, tool loop, run-command tool).
#  - ChatBot is typed manually on stage (~8 lines); LLMClient methods are filled over
#    the "Implementation:" javadoc (the complete versions also live in solutions/).
set -euo pipefail
cd "$(dirname "$0")/.."	
git show 2ea2d73:tiny-llm-demo/src/main/java/me/bechberger/demo/ToolSupport.java \
  > src/main/java/me/bechberger/demo/ToolSupport.java
echo "Restored the ToolSupport skeleton. Undo: git checkout -- src/main/java/me/bechberger/demo/ToolSupport.java"
