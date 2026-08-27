# Presentation Notes: Narrative, Gaps, Images

JavaZone Oslo · ~50 min · live-coding heavy · experienced Java audience

---

## Narrative Problems Found (Full Deck Pass)

### Fixed in last session

| # | Problem | Fix applied |
|---|---------|-------------|
| 1 | Intro was three slides saying the same thing ("you'll understand the internals") | Merged "The Illusion" + tagline + "Why That Matters" into single "LLM APIs are boring" slide |
| 2 | Shell Script Tradeoff was in the MCP section, broke MCP flow | Moved to immediately after Security slide in Part 4 |
| 3 | "And now for something completely different" interstitial had no hook | Rewrote to: "You saw `reasoning_content` appear in the SSE stream — here's what that actually is." |
| 4 | JSON Schema was two separate slides for 6 lines of JSON | Merged into one two-column slide |
| 5 | Part 3 and Part 5 dividers had no timebox | Added `~12 min` and `~5 min` subtitles |
| 6 | FALLBACK notes were inconsistently formatted | Standardized to `**[FALLBACK]**` prefix |
| 7 | Part 5 divider timestamp said `~30:00` (contradicted Part 4 Security at `~33:00`) | Corrected to `~33:00` |

### Remaining / Not Yet Fixed

**8. Closing diagram doesn't match opening diagram.**
"What We Built Together" (line 2368) has a different, simpler Mermaid diagram than "What We're Building Today" (line 129). The opening diagram includes `CodingAgent` and `SkillCodingAgent`; the closing diagram omits the agent entirely. The talk is structured as a callback — the audience should see the same diagram with nodes lit up. Fix: replace the closing diagram with the opening diagram, just highlight more nodes orange.

**9. MCP section has no timestamps.**
Every other section has `[~X:00]` speaker note anchors. MCP slides (Lifecycle, Capability Negotiation, Transports, What It Standardizes) have none. Suggested anchors:
- MCP overview → `[~42:30]`
- Lifecycle → `[~43:00]`
- Capability Negotiation → `[~43:30]`
- Transports → `[~44:00]`
- What It Standardizes → `[~44:30]`

**10. Wrap-Up tagline timestamp is likely wrong.**
Speaker note on the closing "Go Build It" slide says `[~49:00]`. But the talk runs through ~50 min and Part 8 alone runs ~4 minutes (CodingAgent demo). Check the entire Part 8–9 pacing.

**11. Part 5 divider speaker note says "let Copilot do the heavy lifting".**
Line 1183: `"This time I'll let Copilot do the heavy lifting"`. This is an instruction to use Copilot during live coding — which might confuse the audience if the demo uses a local model. Either cut this note or clarify it's about the IDE autocomplete, not the chatbot.

**12. "From Chatbot to Agent" slide has a subtle misalignment.**
The code diff on that slide shows `toolSupport.handleToolLoop` as the only change — which is true. But the CtxWindow widget for CodingAgent shows a `📌 Goal · Plan · TODOs:pinned` message as the second item, which only exists after `/plan` is run. A fresh CodingAgent conversation doesn't have that pinned state. The diagram implies it's always there. Add a note: "after /plan — we'll see that command in a moment."

**13. "Detecting the Limit" slide is data-free.**
Line 1317 talks about triggering summarization at 80% of the context window. The claim "80%" is stated as a design choice but there's no context for why 80%. Add one sentence: "80% leaves headroom for the next full response without cutting off mid-token." (Already documented in speaker notes at line 1349 but not on the slide itself — fine as-is if speaker says it.)

**14. Security slide is too thin.**
"Security — Tools Are Code Execution" (line 1111) is just one sentence: "The model is untrusted. You are the executor." with a speaker note. This is the most important safety point in the talk. It deserves one more bullet: what the code actually checks (canonical paths, no dotfiles, max output size, sandbox escape detection). At minimum show the `FileTools.run()` sandbox check inline.

---

## Factual Claims to Verify

| Claim | Location | Verdict |
|-------|----------|---------|
| "SSE is a browser standard (WHATWG, 2004)" | Line 422 | **Partially wrong.** The WHATWG SSE spec (EventSource) was first published around 2006, not 2004. HTML5 working draft circa 2006. The simpler `text/event-stream` format predates it slightly, but WHATWG 2004 is not accurate. Change to "a browser standard, part of HTML5" or just "a web standard." |
| "trigger summarization at 80% of the context window" | Line 1349 | **Reasonable heuristic** but not established standard. Fine to present as "our choice" which the speaker notes already do. |
| `llama-server -hf AaryanK/Qwen3.5-9B-GGUF:Q8_0` | Line 197 | Verify this model slug is still valid on Hugging Face. Model IDs can be removed or renamed. |
| `Qwen3` / `thinking_budget` parameter | Line 1843 | Verify this is the correct llama.cpp parameter name. llama.cpp uses `chat_template_kwargs` but the exact JSON structure may have changed. |
| "Anthropic Claude: `budget_tokens`" | Line 1844 | **Correct** as of Claude 3.7 Sonnet extended thinking. Still current. |
| "OpenAI o-series: `reasoning_effort` low/medium/high" | Line 1845 | **Correct** for o1/o3. |
| Part divider says "~12 min" for Part 3 | Line 596 (Part 3 divider) | Reasonable — LLMClient + ChatBot live coding. Verify against actual run-through timing. |

---

## Coding Agent Gaps (No MCP)

The current `CodingAgent` / `SkillCodingAgent` implementation is functional but has several gaps that hurt real-world usability:

### High Impact

**1. `findFiles()` tool is not registered.**
`FileTools.java` has a `findFiles()` method that walks the tree and returns up to 50 results. `CodingTools.registerFileTools()` never adds it. The agent must use `ls` + `grep` in combination to discover files, which costs extra tokens and often fails to find files in subdirectories. Fix: add `find-files` as a tool in `registerFileTools()`.

**2. No line-range reading for large files.**
`FileTools.readFile()` truncates at 20KB with no way to read a specific range. For any real Java file over ~400 lines the agent never sees the bottom half. Fix: add optional `start_line`/`end_line` parameters to `read-file`, or add a separate `read-lines` tool.

**3. System prompt is minimal.**
`buildSystemPrompt()` in `CodingAgent.java` is 6 lines. It tells the model what tools exist but not how to use them well: no instruction to read before writing, no instruction to run tests after editing, no guidance on when to use `grep` vs `ls`, no instruction to check compilation errors. Compare to Claude Code's system prompt which is thousands of words. Even 30 more words of guidance measurably reduces tool call waste.

### Medium Impact

**4. No recursive `tree` / multi-level `ls`.**
`ls()` lists one directory level, hiding dotfiles. There's no way to get a project overview in one call. Add a `tree` tool (max depth 3, respect `.gitignore`) or add a `depth` parameter to `ls`.

**5. No git awareness.**
The agent can edit and run `mvn test` but cannot `git status`, `git diff`, or `git log`. It can't check what was previously attempted, can't verify its own changes, can't revert. For a coding agent demo where the model edits its own source, `git diff HEAD` after each edit would be the most useful "did that do anything" check.

**6. `run` tool timeout is fixed at 60 seconds.**
`FileTools.run()` hard-codes 60s. `mvn package` on a cold Maven can take 90s+ on a slow machine during a live demo. Either make the timeout configurable via `--timeout` or raise to 120s.

### Lower Impact

**7. No `search-replace` with line numbers.**
`editFile()` requires a unique exact-match string for context. On files with repeated boilerplate, the agent can't target a specific line, causing "match not unique" errors. Add an `edit-lines` tool that takes `start_line`, `end_line`, and new content.

**8. No `append-to-file` tool.**
Agents frequently need to add a method to the bottom of a class. The current `edit` tool requires finding an anchor string. An `append` tool would be simpler for the model and produce cleaner diffs.

**9. No structured error summary.**
When `mvn test` fails, the agent gets the full Maven output (up to 16KB). Key signal (the test failure line) is often near the end of the truncated output. Pre-parse the output: extract `BUILD FAILURE` + `FAILURE at` + last 20 lines and prepend to the raw output.

---

## Slides Should Be Empty — Visual Direction

The current slides are text-heavy. The philosophy should be:
- **Code slides**: just the code block, no prose explanation — the speaker explains
- **Concept slides**: one big statement or diagram, no bullet lists
- **Section dividers**: already clean — keep them

Specific candidates to strip:

| Slide | What to remove |
|-------|---------------|
| "What frameworks add on top" (line 85) | The bullet list. Keep only the Mermaid diagram. Speaker says the bullets. |
| "Simple Chat Completion" (line 312) | Anatomy table below the code. Speaker walks the fields. |
| "Security — Tools Are Code Execution" (line 1111) | Already thin. Maybe make the single statement much bigger as a visual. |
| "From Chatbot to Agent" (line 1894) | Remove the prose labels "talks, remembers, acts" — just show the two code blocks side by side. |
| "Go Build It" (last slide) | Remove the "Show of hands" text — just the QR codes and one tagline. |

---

## Image Suggestions

All suggested images are from public-domain archives. Format: `[archive] search query — why it fits`

### Title slide / Part 1 "The API"
**Telephone switchboard operators, 1940s**
- Search: `loc.gov/pictures` → "telephone switchboard operator"
- Why: Visual metaphor for "boring plumbing that connects things" — operators routing calls, which looks complex from outside but is mechanical routing
- Credit: Library of Congress, Prints & Photographs Division
- Usage: Full-bleed background, dark overlay (already in use on title slide)

### Part 2 "Time to prove it" / REST demo
**Telegraph poles at dusk, c.1910**
- Search: `loc.gov/pictures` → "telegraph poles landscape"  
- Why: "It's just sending data through wires" — the original REST API
- Credit: Library of Congress, Prints & Photographs Division
- Usage: Background on section divider or "It's All Just REST" slide

### Part 3 "Streaming / SSE"
**Radio broadcast room, 1920s**
- Search: `loc.gov/pictures` → "radio broadcast studio 1920s"
- Why: First mass-media push technology — the model pushes tokens, the radio station pushes audio
- Also consider: NASA Deep Space Network antenna
- Search: `images.nasa.gov` → "Deep Space Network dish"
- Credit: NASA
- Usage: Left column image on SSE slide, replacing empty whitespace

### Part 4 "Tool Calling"
**NASA Space Shuttle robotic arm (Canadarm)**
- Search: `images.nasa.gov` → "Canadarm robotic arm shuttle"
- Why: Tool = arm; the LLM directs the arm; the arm acts on the world
- Credit: NASA, public domain
- Usage: Image-forward slide before the tool loop diagram

**Alternative: Factory punch-card sorting machine, 1960s**
- Search: `loc.gov/pictures` → "punch card sorting machine"
- Why: Structured input/output with typed parameters — same principle as JSON Schema tool calls
- Credit: Library of Congress

### Part 4 "Security — Tools Are Code Execution"
**Warning sign on nuclear facility, c.1950s**
- Search: `loc.gov/pictures` → "danger warning sign atomic"
- Why: "The model is untrusted. You are the executor." deserves a visual that makes the audience feel the weight of it
- Alternative: `images.nasa.gov` → "explosion test" or "rocket static fire test" — controlled danger
- Usage: Full-bleed with red/orange overlay; drop the text and let the image speak

### Part 6/7 "MCP"
**Telephone exchange switching room, 1940s**
- Search: `loc.gov/pictures` → "telephone exchange room equipment"
- Why: MCP is a protocol for connecting many things through a standard interface — this is the original version of that
- Credit: Library of Congress

### Part 7 "Thinking Mode"
**Scientist at chalkboard, c.1960s**
- Search: `loc.gov/pictures` → "scientist chalkboard equations"
- Why: Thinking mode = showing your work on the board before answering
- Alternative: `loc.gov/pictures` → "chess player thinking" (dramatic concentration)
- Credit: Library of Congress
- Usage: Right column on "Thinking Mode — When Models Reason Out Loud" slide

### Part 8 "Coding Agent"
**Mission Control, Apollo era, NASA**
- Search: `images.nasa.gov` → "mission control apollo" or "NASA mission control 1969"
- Why: Agents monitoring and acting on a complex system — many tool calls in parallel
- Credit: NASA, public domain
- Best image: The famous wide-angle shot of mission control with all consoles lit up
- Usage: Background on CodingAgent section divider

### Part 9 "What We Built Together"
**Apollo 11 Moon landing, NASA (AS11-40-5931)**
- Search: `images.nasa.gov` → "Apollo 11 astronaut moon surface"
- Why: The closing callback. "We built something that seemed impossible before we started." Clean dark background with bright subject.
- Credit: NASA / Neil Armstrong, public domain
- Usage: Full-bleed background on closing diagram slide, with the Mermaid diagram overlaid

---

## Archive Links for Direct Access

| Archive | URL | Notes |
|---------|-----|-------|
| Library of Congress Prints & Photographs | https://www.loc.gov/pictures/ | All public domain pre-1928; many 1930s–1960s also free |
| NASA Images | https://images.nasa.gov/ | All NASA imagery is public domain by law |
| Smithsonian Open Access | https://www.si.edu/openaccess | CC0 for most digitized collection |
| Wikimedia Commons | https://commons.wikimedia.org | Filter by license: Public Domain |

**Download at minimum 2000px wide** — Slidev renders at 1920×1080 minimum; a 1000px image will look soft.

**Attribution format for slides:**
```
Image: Library of Congress / [call number or title]
```
or
```
Image: NASA, public domain
```
Place in small text bottom-right of the slide, 10–11px, gray-400.
