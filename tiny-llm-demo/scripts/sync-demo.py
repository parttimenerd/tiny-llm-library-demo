#!/usr/bin/env python3
"""
sync-demo.py — keep demo/ and solutions/ Java files in sync.

Solutions is the source of truth. Demo files are identical except:
  1. Package: "me.bechberger.demo.solutions" → "me.bechberger.demo"
  2. @stub blocks: lines between "// @stub" ... "// @end" are replaced
     in the demo by the hint comment(s) that follow "// @stub".
  3. @demo lines: "some code; // @demo: replacement" → "replacement" in demo
     (use "// @demo: " with nothing after to delete the line entirely).

Usage:
  sync-demo.py generate    — overwrite demo files from solutions
  sync-demo.py check       — diff what would be generated vs actual demo files
  sync-demo.py annotate    — show solutions files with @stub/@demo annotations highlighted
  sync-demo.py cheatsheet  — generate docs/cheatsheet.md (stub vs solution, print-ready)

Pairs (solutions → demo):
  solutions/CodingAgent.java    → CodingAgent.java
  solutions/ToolChatBot.java    → ToolChatBot.java
  solutions/LLMClient.java      → LLMClient.java
  solutions/ChatBot.java        → ChatBot.java
  solutions/ToolSupport.java    → ToolSupport.java   (skip: restored by reset-live-coding.sh)
"""
import sys
import re
import difflib
from pathlib import Path
from dataclasses import dataclass, field

ROOT = Path(__file__).parent.parent / "src/main/java/me/bechberger/demo"
SOL  = ROOT / "solutions"

# solutions file → demo file (relative to ROOT)
PAIRS = [
    ("CodingAgent.java",  "CodingAgent.java"),
    ("ToolChatBot.java",  "ToolChatBot.java"),
    ("ChatBot.java",      "ChatBot.java"),
    ("Options.java",      "Options.java"),
    ("LLMClient.java",    "LLMClient.java"),   # Part 3: chat(), chatStream(), processSSELine()
    # ToolSupport.java: managed by reset-live-coding.sh
]


def transform(solutions_lines: list[str]) -> list[str]:
    """Apply all sync rules to produce the demo version."""
    out = []
    i = 0
    while i < len(solutions_lines):
        line = solutions_lines[i]

        # Rule 1: package declaration
        stripped = line.rstrip("\n")
        if stripped.strip() == "package me.bechberger.demo.solutions;":
            out.append(line.replace("me.bechberger.demo.solutions", "me.bechberger.demo"))
            i += 1
            continue

        # Rule 2: @stub block  —  // @stub [hint text]
        #   Collects consecutive @stub hint lines, then skips until // @end,
        #   emitting the hint lines in the demo.
        m = re.match(r'^(\s*)//\s*@stub(.*)', stripped)
        if m:
            indent = m.group(1)
            hints = []
            # first hint may be on the same line: // @stub: do X
            first_hint = m.group(2).lstrip(": ").strip()
            if first_hint:
                hints.append(first_hint)
            i += 1
            # consume additional // @stub: ... continuation lines
            while i < len(solutions_lines):
                cont = solutions_lines[i].rstrip("\n")
                cm = re.match(r'^\s*//\s*@stub(.*)', cont)
                if cm:
                    h = cm.group(1).lstrip(": ").strip()
                    if h:
                        hints.append(h)
                    i += 1
                else:
                    break
            # skip body lines until // @end
            while i < len(solutions_lines):
                end_line = solutions_lines[i].rstrip("\n").strip()
                i += 1
                if end_line == "// @end":
                    break
            # emit hint comment(s) in place of the body
            if hints:
                for hint in hints:
                    out.append(indent + "// TODO: " + hint + "\n")
            else:
                out.append(indent + "// TODO: live code\n")
            out.append(indent + "throw new UnsupportedOperationException(\"TODO: live code\");\n")
            continue

        # Rule 3: @demo: replacement  —  trailing annotation on a code line
        #   "code; // @demo: replacement"  →  "replacement"
        #   "code; // @demo:"              →  (line deleted)
        dm = re.search(r'//\s*@demo:\s*(.*)', stripped)
        if dm:
            replacement = dm.group(1).strip()
            if replacement:
                # preserve leading whitespace from original line
                leading = len(stripped) - len(stripped.lstrip())
                out.append(" " * leading + replacement + "\n")
            # else: delete the line entirely
            i += 1
            continue

        out.append(line)
        i += 1
    return out


def generate_all(dry_run=False) -> dict[str, list[str]]:
    """Return {demo_path: generated_lines} for all pairs."""
    results = {}
    for sol_name, demo_name in PAIRS:
        sol_path = SOL / sol_name
        demo_path = ROOT / demo_name
        if not sol_path.exists():
            print(f"  SKIP  {sol_name} (not found in solutions/)")
            continue
        lines = sol_path.read_text(encoding="utf-8").splitlines(keepends=True)
        generated = transform(lines)
        results[str(demo_path)] = generated
    return results


def cmd_generate():
    results = generate_all()
    for path, lines in results.items():
        Path(path).write_text("".join(lines), encoding="utf-8")
        print(f"  wrote {Path(path).name}")
    print("Done. Run 'mvn compile -q' to verify.")


def cmd_check():
    results = generate_all()
    any_diff = False
    for path, generated in results.items():
        p = Path(path)
        if not p.exists():
            print(f"MISSING  {p.name}  (run 'generate' to create it)")
            any_diff = True
            continue
        actual = p.read_text(encoding="utf-8").splitlines(keepends=True)
        if actual == generated:
            print(f"  OK     {p.name}")
        else:
            any_diff = True
            print(f"  DRIFT  {p.name}")
            diff = difflib.unified_diff(
                generated, actual,
                fromfile=f"generated/{p.name}",
                tofile=f"actual/{p.name}",
                n=3,
            )
            sys.stdout.writelines(diff)
    if any_diff:
        sys.exit(1)


def cmd_annotate():
    """Print solutions files with @stub/@demo markers highlighted (for quick review)."""
    RESET  = "\033[0m"
    YELLOW = "\033[33m"
    CYAN   = "\033[36m"
    for sol_name, _ in PAIRS:
        sol_path = SOL / sol_name
        if not sol_path.exists():
            continue
        print(f"\n{'─'*60}")
        print(f"  {sol_name}")
        print(f"{'─'*60}")
        for line in sol_path.read_text(encoding="utf-8").splitlines():
            if "@stub" in line or "@end" in line:
                print(YELLOW + line + RESET)
            elif "@demo:" in line:
                print(CYAN + line + RESET)
            else:
                print(line)


@dataclass
class StubBlock:
    file: str           # e.g. "LLMClient.java"
    method: str         # method signature line
    hint: str           # TODO hint text
    solution: list[str] # solution body lines (between @stub and @end, stripped of markers)


def extract_stubs(sol_name: str) -> list[StubBlock]:
    """Extract all @stub/@end blocks from a solutions file with their context."""
    sol_path = SOL / sol_name
    if not sol_path.exists():
        return []
    lines = sol_path.read_text(encoding="utf-8").splitlines()
    blocks = []
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        m = re.match(r'//\s*@stub(.*)', stripped)
        if m:
            hint_parts = [m.group(1).lstrip(": ").strip()]
            # collect multi-line @stub hints
            j = i + 1
            while j < len(lines) and re.match(r'\s*//\s*@stub(.*)', lines[j]):
                h = re.match(r'\s*//\s*@stub(.*)', lines[j]).group(1).lstrip(": ").strip()
                if h:
                    hint_parts.append(h)
                j += 1
            hint = "; ".join(p for p in hint_parts if p) or "live code"
            # collect solution lines until @end
            sol_lines = []
            while j < len(lines):
                if lines[j].strip() == "// @end":
                    j += 1
                    break
                # strip @demo: annotations from solution lines
                dm = re.search(r'//\s*@demo:\s*(.*)', lines[j])
                if dm:
                    replacement = dm.group(1).strip()
                    if replacement:
                        sol_lines.append(replacement)
                else:
                    sol_lines.append(lines[j])
                j += 1
            # find method signature: scan backwards for a line with public/private/protected
            method = ""
            for k in range(i - 1, max(i - 20, -1), -1):
                l = lines[k].strip()
                if re.match(r'(public|private|protected)\s', l):
                    method = l.rstrip("{").strip()
                    break
            blocks.append(StubBlock(sol_name, method, hint, sol_lines))
            i = j
            continue
        i += 1
    return blocks


# ToolSupport stubs come from the reset script, not from solutions/@stub markers.
# Define them manually so the cheat sheet is complete.
TOOLSUPPORT_STUBS = [
    StubBlock("ToolSupport.java", "public List<Map<String,Object>> buildToolsJson()",
              'for each tool: Map.of("type","function", "function", Map.of("name",…,"description",…,"parameters",…))',
              [
                  'var result = new ArrayList<Map<String, Object>>();',
                  'for (var tool : tools.values()) {',
                  '    result.add(Map.of("type", "function",',
                  '        "function", Map.of(',
                  '            "name", tool.name(),',
                  '            "description", tool.description(),',
                  '            "parameters", tool.parameterSchema())));',
                  '}',
                  'return result;',
              ]),
    StubBlock("ToolSupport.java", "public String handleToolLoop(LLMClient client, List<Map<String,Object>> messages)",
              "loop up to 100 times: chatRaw → if finish_reason==\"stop\" return extractContent, else processToolCalls",
              [
                  'var toolsJson = buildToolsJson();',
                  'for (int i = 0; i < 100; i++) {',
                  '    var choice = client.chatRaw(messages, toolsJson);',
                  '    if (!"tool_calls".equals(choice.get("finish_reason")))',
                  '        return extractContent(choice);',
                  '    processToolCalls(choice, messages);',
                  '}',
                  'return "[Tool loop exceeded maximum iterations]";',
              ]),
    StubBlock("ToolSupport.java", "private String extractContent(Map<String,Object> choice)",
              "extract choice.message.content",
              ['return (String) Util.asMap(choice.get("message")).get("content");']),
    StubBlock("ToolSupport.java", "private void processToolCalls(Map<String,Object> choice, List<Map<String,Object>> messages)",
              "add assistant message first, then for each tool_call: executeToolCall and add result",
              [
                  'var msg = Util.asMap(choice.get("message"));',
                  'messages.add(msg);',
                  'for (var tc : Util.asList(msg.get("tool_calls")))',
                  '    messages.add(executeToolCall(Util.asMap(tc)));',
              ]),
    StubBlock("ToolSupport.java", "private Map<String,Object> executeToolCall(Map<String,Object> toolCall)",
              "extract id + function.name + function.arguments → callTool → return role:tool message",
              [
                  'var id   = (String) toolCall.get("id");',
                  'var fn   = Util.asMap(toolCall.get("function"));',
                  'var name = (String) fn.get("name");',
                  'var args = (String) fn.get("arguments");',
                  'var result = callTool(name, args);',
                  'System.out.println("  ⚙ " + name + "(" + truncate(args,120) + ")");',
                  'return Map.of("role","tool","tool_call_id",id,"content",result);',
              ]),
    StubBlock("ToolSupport.java", "private String callTool(String toolName, String argumentsJson)",
              "parse JSON args → lookup tool → call handler (or return error)",
              [
                  'try {',
                  '    var args = Util.asMap(JSONParser.parse(argumentsJson));',
                  '    var tool = tools.get(toolName);',
                  '    if (tool == null) return "Error: unknown tool \'" + toolName + "\'";',
                  '    return tool.handler().apply(args);',
                  '} catch (Exception e) {',
                  '    return "Error: " + e.getMessage();',
                  '}',
              ]),
]

PART_ORDER = [
    ("Part 3 — LLMClient", ["LLMClient.java"]),
    ("Part 3 — ChatBot",   ["ChatBot.java"]),
    ("Part 5 — ToolSupport", ["ToolSupport.java"]),
    ("Part 5 — ToolChatBot", ["ToolChatBot.java"]),
    ("Part 8 — CodingAgent", ["CodingAgent.java"]),
]


def _dedent(lines: list[str]) -> list[str]:
    """Remove common leading whitespace from a block of lines."""
    non_empty = [l for l in lines if l.strip()]
    if not non_empty:
        return lines
    indent = min(len(l) - len(l.lstrip()) for l in non_empty)
    return [l[indent:] if len(l) > indent else l for l in lines]


def cmd_cheatsheet():
    """Generate docs/cheatsheet.md — stub hints then solutions stacked, print-ready."""
    out_path = Path(__file__).parent.parent / "docs" / "cheatsheet.md"
    out_path.parent.mkdir(exist_ok=True)

    # collect stubs from @stub markers
    all_stubs: dict[str, list[StubBlock]] = {}
    for sol_name, _ in PAIRS:
        blocks = extract_stubs(sol_name)
        if blocks:
            all_stubs[sol_name] = blocks
    all_stubs["ToolSupport.java"] = TOOLSUPPORT_STUBS

    md = []
    md.append("# Live Coding Cheat Sheet\n\n")
    md.append('<style>\n'
              'body { font-family: monospace; font-size: 11px; max-width: 900px; margin: 0 auto; }\n'
              'h2 { margin-top: 1.6em; border-bottom: 2px solid #333; padding-bottom: 2px; }\n'
              'h3 { margin-top: 1.2em; margin-bottom: 2px; color: #333; }\n'
              '.stub { background: #fffbe6; border-left: 3px solid #f0c040; padding: 6px 10px; margin: 4px 0; }\n'
              '.solution { background: #f0fff0; border-left: 3px solid #4caf50; padding: 6px 10px; margin: 4px 0; }\n'
              '.label { font-size: 9px; text-transform: uppercase; color: #888; margin-bottom: 2px; }\n'
              'pre { margin: 0; white-space: pre-wrap; word-break: break-all; }\n'
              '@media print { h2 { page-break-before: auto; } .stub, .solution { page-break-inside: avoid; } }\n'
              '</style>\n\n')

    for part_title, file_names in PART_ORDER:
        blocks = []
        for fname in file_names:
            blocks.extend(all_stubs.get(fname, []))
        if not blocks:
            continue
        md.append(f"## {part_title}\n\n")
        for b in blocks:
            method_short = re.sub(r'\bpublic\b|\bprivate\b|\bprotected\b|\bstatic\b|\bvoid\b', '', b.method).strip()
            method_short = re.sub(r'\s+', ' ', method_short)
            md.append(f"### `{method_short}`\n\n")

            hint_lines = [f"// TODO: {h}" for h in b.hint.split("; ")]
            hint_code  = "\n".join(hint_lines)
            sol_lines  = _dedent(b.solution)
            sol_code   = "\n".join(l.rstrip() for l in sol_lines)

            md.append(f'<div class="stub"><div class="label">hint</div><pre>{_html_escape(hint_code)}</pre></div>\n')
            md.append(f'<div class="solution"><div class="label">solution</div><pre>{_html_escape(sol_code)}</pre></div>\n\n')

    out_path.write_text("".join(md), encoding="utf-8")
    print(f"  wrote {out_path.relative_to(Path(__file__).parent.parent)}")
    print("  Open in a browser and print (Cmd+P) — portrait or landscape.")


def _html_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


COMMANDS = {"generate": cmd_generate, "check": cmd_check, "annotate": cmd_annotate, "cheatsheet": cmd_cheatsheet}

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    COMMANDS[sys.argv[1]]()
