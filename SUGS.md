# Slide Deck Critique & Improvement Suggestions

JavaZone Oslo · ~50 min · live-coding heavy · experienced Java audience

---

## Applied ✅

- **Part 1 section divider added** — consistent with Parts 2–9.
- **Request/response anatomy table** added to "Simple Chat Completion" — left = request fields, right = response fields.
- **Flow arrows ①→②→③** added to "Tool Calling — The Formats" so columns read as a sequence, not alternatives.
- **Before/after CtxWindow** added to "Why Hybrid?" — full history overflowing vs compacted.
- **MCP SVG simplified** — 9-box fan replaced with 3-box `Your App → MCP (JSON-RPC 2.0) → MCP Server(s)`.
- **MCP capability tables → bullet lists** — two grids replaced with plain bullets readable from the back row.
- **~47:00 timestamp collision fixed** — "One More Thing: The Agent Edits Itself" moved to `~49:00`.

---

## Structure

**1. Intro is three slides too long.**
Slides "The Illusion", "The Gap: Why Understanding Matters", and "Why That Matters" all say the same thing: "you'll understand what's under the hood." Merge into one single punchy slide or cut all three — the tagline slide ("LLM APIs are boring") already lands the point faster.

**2. "The Shell Script Tradeoff" is misplaced.**
It sits in the MCP section but belongs right after the Security slide in Part 4 (Tool Calling). Move it or cut it — it breaks the MCP flow.

**3. "Thinking Mode" interstitial has no hook.**
The "And now for something completely different" slide + Thinking Mode slide have no before/after connection. Either tie it to the tool-loop demo ("you'll see reasoning_content in the SSE stream — here's what that is") or cut the interstitial and jump straight to the Thinking Mode slide.

---

## Diagrams

**4. Add before/after diagram for "Why Hybrid?" — DONE.**
(CtxWindow already added above.)

**5. The closing "What We Built Together" duplicates the opening diagram.**
Reuse the exact same diagram from "What We're Building Today" and just highlight the completed nodes — zero new cognitive load, strong callback.

---

## Simplification

**6. JSON Schema slides can be merged.**
"JSON Schema — Describing Shape" and "JSON Schema — Valid vs. Invalid" together are 6 lines of JSON. Inline the valid/invalid comparison beneath the schema on the same slide.

**7. MCP capability tables simplified — DONE.**

---

## Pacing

**8. Add time-box to coding section dividers.**
"Part 3: Let's Build It" and "Part 5: Adding Tools" jump straight into scaffolding. Add `~10 min` to the section-divider subtitle so the speaker can self-correct pace.

---

## Speaker Notes

**9. Notes on MCP slides have no timestamps.**
Every other section has `[~X:00]` anchors. MCP slides (Parts 6–7: Lifecycle, Capability Negotiation, Transports, What It Standardizes) are missing them. Suggested:
- Lifecycle → `[~43:00]`
- Capability Negotiation → `[~43:30]`
- Transports → `[~44:00]`
- What It Standardizes → `[~44:30]`
- Shell Script Tradeoff → `[~45:00]`

**10. Safety-net instructions are buried.**
"Safety net: paste from solution" appears on several coding slides but inconsistently. Standardize to a bolded `**[FALLBACK]** paste from solution` at the end of each live-coding note so it's instantly scannable under pressure.

---

## Benchmark / Demo Loop Detector

**11. BANANA-style deliberation spin not caught.**
The stuck-loop detector (≤5 unique in last 20 lines) misses loops where every line is different but all are short hedges: `*Wait, I'll keep it friendly.*` / `*Okay.` / `*Wait, I'll add...`. Fix: secondary signal — if 12+ of last 20 non-empty lines are ≤50 chars AND contain a hedge word (`wait`, `okay`, `let me`, `hmm`), kill. **Applied to `benchmark-models.py`.**
