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
  sync-demo.py generate   — overwrite demo files from solutions
  sync-demo.py check      — diff what would be generated vs actual demo files
  sync-demo.py annotate   — show solutions files with @stub/@demo annotations highlighted

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

ROOT = Path(__file__).parent.parent / "src/main/java/me/bechberger/demo"
SOL  = ROOT / "solutions"

# solutions file → demo file (relative to ROOT)
PAIRS = [
    ("CodingAgent.java",  "CodingAgent.java"),
    ("ToolChatBot.java",  "ToolChatBot.java"),
    ("ChatBot.java",      "ChatBot.java"),
    ("LLMClient.java",    "LLMClient.java"),
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


COMMANDS = {"generate": cmd_generate, "check": cmd_check, "annotate": cmd_annotate}

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    COMMANDS[sys.argv[1]]()
