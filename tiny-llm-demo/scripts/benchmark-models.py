#!/usr/bin/env python3
"""
benchmark-models.py — Benchmark local LLM demo scenarios and produce a combined HTML report.

Each test runs 3 times. A Kimi-K3 judge scores quality 1–5 for each run.
Results accumulate in a single HTML file so you can compare models side-by-side.
A final verdict at the top of the report answers: which model should you use?

Usage:
  python3 benchmark-models.py [options]

Options:
  --models REPO:FILE [...]  HF repos to benchmark in sequence (see defaults below)
  --base-url URL            Benchmark a running server instead of auto-starting one
  --hf-repo REPO:FILE       Benchmark a single HF repo (shorthand for --models with one entry)
  --port PORT               Port for auto-started servers (default: 8099)
  --out FILE                Combined HTML output (default: ./benchmark-results.html)
  --judge-url URL           Judge endpoint (default: gardener from config, else base-url)
  --judge-model MODEL       Model ID for judging (default: auto-detect from judge-url)
  --judge-key KEY           API key for judge endpoint (auto-read from config if omitted)
  --no-judge                Skip the LLM judge step
  --runs N                  Runs per test (default: 3)
  --no-open                 Don't open the HTML report in a browser when done
  --jar FILE                Path to tiny-llm-demo.jar (default: ../target/tiny-llm-demo.jar)

Default model list (used when --models and --hf-repo and --base-url are all omitted):
  unsloth/Qwen3.5-2B-GGUF:Qwen3.5-2B-Q8_0.gguf
  unsloth/Qwen3.5-4B-GGUF:Qwen3.5-4B-Q8_0.gguf
  unsloth/Qwen3.5-9B-GGUF:Qwen3.5-9B-Q8_0.gguf
  unsloth/Qwen3.8-27B-GGUF:Qwen3.8-27B-UD-Q4_K_XL.gguf
  unsloth/Qwen3.8-27B-GGUF:Qwen3.8-27B-Q8_0.gguf

Examples:
  # Run the full default suite (2B, 4B, 9B) with Kimi-K3 judge
  python3 benchmark-models.py

  # Run a custom set
  python3 benchmark-models.py \\
    --models unsloth/Qwen3.5-4B-GGUF:Qwen3.5-4B-Q8_0.gguf \\
             unsloth/Qwen3.5-9B-GGUF:Qwen3.5-9B-Q8_0.gguf

  # Benchmark only a single already-running server
  python3 benchmark-models.py --base-url http://localhost:8080

  # Benchmark with explicit judge endpoint
  python3 benchmark-models.py \\
    --judge-url https://models.answering-machine.utility.gardener.cloud.sap \\
    --judge-model kimi-k3
"""

import argparse
import html as html_module
import json
import re
import shutil
import socket
import subprocess
import sys
sys.stdout.reconfigure(line_buffering=True)
import tempfile
import threading
import time
import urllib.request
import urllib.error
from datetime import datetime
from pathlib import Path

SCRIPT_DIR  = Path(__file__).parent
DEFAULT_JAR = SCRIPT_DIR.parent / "target" / "tiny-llm-demo.jar"
DEFAULT_OUT = SCRIPT_DIR / "benchmark-results.html"

RUNS = 3  # overridden by --runs

SLIDES_ROOT = str(Path(__file__).parent.parent.parent)
SLIDE_FILE  = "slides/slides.md"

SLIDES_MONOLOGUE_INPUT = (
    'Write a short, fun and nerdy opening monologue for a talk called '
    '"Let\'s create a tiny LLM library together" at JavaZone Oslo (the largest Java '
    'conference in Scandinavia). Thank the organizers for the excellent food and hospitality. '
    'Tone: enthusiastic, slightly self-deprecating, technical crowd. Don\'t ramble.'
    f'The talk slides are at path {SLIDE_FILE}, read that file for context.'
    '\n/exit\n'
)

MONOLOGUE_INPUT = (
    'Write a short, fun and nerdy opening monologue for a talk called '
    '"Let\'s create a tiny LLM library together" at JavaZone Oslo (the largest Java '
    'conference in Scandinavia). Thank the organizers for the excellent food and hospitality. '
    'Tone: enthusiastic, slightly self-deprecating, technical crowd. Don\'t ramble.'
)

MEMORY_INPUT = "Remember this codeword: BANANA\nWhat was the codeword I just gave you?\n"

TOOL_INPUT = "Describe what this project does.\n/exit\n"

AGENT_INPUT = "/yolo\nBuild a small calculator app.\n/exit\n"
AGENT_CLI_INPUT = "/yolo\nBuild a small calculator CLI tool with Maven in a subfolder.\n/exit\n"

SELF_IMPROVE_INPUT = "/yolo\nAdd a tool to the coding agent to count the r's in a string\n/exit\n"

VIKING_INPUT = "Tell me about this project like a viking\n/exit\n"

# ── server ────────────────────────────────────────────────────────────────────

def start_server(hf_repo: str, port: int) -> subprocess.Popen:
    print(f"▶ Starting llama-server  {hf_repo}  port={port} …")
    # Kill any stale process already bound to this port before starting a new one.
    try:
        result = subprocess.run(["lsof", "-ti", f"tcp:{port}"], capture_output=True, text=True)
        for pid in result.stdout.split():
            try:
                subprocess.run(["kill", "-9", pid.strip()], check=False)
            except Exception:
                pass
        if result.stdout.strip():
            time.sleep(1)
    except Exception:
        pass
    log = Path(tempfile.gettempdir()) / "llama-bench.log"
    # llama.cpp 0.3.0+ dropped the "repo:file" colon syntax; use -hf repo -hff file instead.
    # Also check if the file is already cached and use -m directly to avoid re-downloading.
    cmd = ["llama-server", "--port", str(port), "--log-disable"]
    if ":" in hf_repo:
        repo, hf_file = hf_repo.split(":", 1)
        cache_name = repo.replace("/", "_") + "_" + hf_file
        cache_path = Path.home() / "Library" / "Caches" / "llama.cpp" / cache_name
        if not cache_path.exists():
            # Try Linux XDG cache location
            cache_path = Path.home() / ".cache" / "llama.cpp" / cache_name
        if cache_path.exists():
            cmd += ["-m", str(cache_path)]
        else:
            cmd += ["-hf", repo, "-hff", hf_file]
    else:
        cmd += ["-hf", hf_repo]
    proc = subprocess.Popen(
        cmd,
        stdout=open(log, "w"), stderr=subprocess.STDOUT,
    )
    url = f"http://localhost:{port}/health"
    for _ in range(900):
        time.sleep(2)
        if proc.poll() is not None:
            print(f"  ✗ Server died:\n{log.read_text()[-1500:]}", file=sys.stderr); sys.exit(1)
        try:
            urllib.request.urlopen(url, timeout=2); print(f"  ✓ ready (PID {proc.pid})"); return proc
        except Exception:
            pass
    print("  ✗ Timeout waiting for server.", file=sys.stderr); proc.terminate(); sys.exit(1)


def detect_model(base_url: str) -> str:
    try:
        with urllib.request.urlopen(f"{base_url}/v1/models", timeout=5) as r:
            return json.loads(r.read())["data"][0]["id"]
    except Exception:
        return "unknown"


# ── run one demo ──────────────────────────────────────────────────────────────

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[mABCDEFGHJKSTfnsu]")

def _strip_ansi(s: str) -> str:
    return _ANSI_RE.sub("", s)

def run_demo(jar: Path, class_name: str, base_url: str, stdin: str,
             extra: list[str], timeout_s: int = 150,
             judge_url: str = "", judge_model: str = "", judge_api_key: str = "",
             hard_timeout_s: int | None = 300) -> tuple[str, float, bool, str]:
    cmd = ["java", "-jar", str(jar), class_name, "--base-url", base_url, *extra]
    t0 = time.monotonic()
    killed_reason = ""
    try:
        proc = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True,
        )
        out_lines = []
        stdin_lines = iter(stdin.splitlines())
        proc.stdin.write(stdin)
        proc.stdin.close()
        recent_tools: list[str] = []
        recent_output: list[str] = []  # sliding window for repetition detection
        all_clean_lines: list[str] = []  # full output for judge polling

        # Background thread: ask judge every 60 s if output looks stuck; hard-abort after hard_timeout_s
        judge_kill_event = threading.Event()
        judge_verdict: list[str] = []
        _judge_start = time.monotonic()

        def _judge_poll():
            while not judge_kill_event.wait(timeout=60):
                if judge_kill_event.is_set():
                    break
                elapsed = time.monotonic() - _judge_start
                if hard_timeout_s is not None and elapsed > hard_timeout_s:
                    judge_verdict.append(f"stuck (timeout {hard_timeout_s}s)")
                    proc.kill()
                    break
                if _ask_judge_stuck(judge_url, judge_model, judge_api_key, list(all_clean_lines)):
                    judge_verdict.append("stuck (judge)")
                    proc.kill()
                    break

        poll_thread = None
        if judge_url and judge_model:
            poll_thread = threading.Thread(target=_judge_poll, daemon=True)
            poll_thread.start()

        for line in proc.stdout:
            sys.stdout.write("    │ " + line)
            clean = _strip_ansi(line)
            out_lines.append(clean)
            all_clean_lines.append(clean.rstrip())
            stripped = clean.strip()

            # echo the stdin line that was consumed when a prompt appears
            if stripped.endswith("You:") or stripped.endswith("You: "):
                try:
                    user_line = next(stdin_lines)
                    if user_line:
                        echo = user_line + "\n"
                        sys.stdout.write("    │ " + echo)
                        out_lines.append(echo)
                        all_clean_lines.append(user_line)
                except StopIteration:
                    pass

            # check if judge thread already killed us
            if judge_verdict:
                killed_reason = judge_verdict[0]
                out_lines.append(f"[killed: {killed_reason}]\n")
                sys.stdout.write(f"    │ [killed: {killed_reason}]\n")
                break

            # tool-loop detection (unchanged)
            if stripped.startswith("⚙"):
                recent_tools.append(stripped)
                if len(recent_tools) > 10:
                    recent_tools.pop(0)
                if len(recent_tools) >= 6 and len(set(recent_tools[-6:])) <= 2:
                    killed_reason = "stuck tool loop detected"
                    proc.kill()
                    out_lines.append(f"[killed: {killed_reason}]\n")
                    sys.stdout.write(f"    │ [killed: {killed_reason}]\n")
                    break

            # thinking-loop detection: track non-empty output lines,
            # kill if last 20 lines are mostly repetitive.
            # Normalize before comparing: lowercase + strip punctuation + truncate to 60 chars
            # so minor variations ("nerdy" vs "fun") don't defeat deduplication.
            # Also detect deliberation spin: many short hedge lines ("wait", "okay", "let me")
            if stripped:
                normalized = re.sub(r'[^a-z0-9 ]', '', stripped.lower())[:60]
                recent_output.append(normalized)
                if len(recent_output) > 30:
                    recent_output.pop(0)
                if len(recent_output) >= 20:
                    window = recent_output[-20:]
                    unique = len(set(window))
                    hedge_words = {'wait', 'okay', 'ok', 'alright', 'let me', 'hmm', 'actually'}
                    short_hedges = sum(
                        1 for ln in window
                        if len(ln) <= 50 and any(hw in ln for hw in hedge_words)
                    )
                    if unique <= 5 or short_hedges >= 12:
                        killed_reason = "stuck thinking loop detected"
                        proc.kill()
                        out_lines.append(f"[killed: {killed_reason}]\n")
                        sys.stdout.write(f"    │ [killed: {killed_reason}]\n")
                        break

        proc.wait()
        if poll_thread is not None:
            judge_kill_event.set()
            poll_thread.join(timeout=5)
        elapsed = time.monotonic() - t0
        out = "".join(out_lines)
        if killed_reason:
            return out, elapsed, False, killed_reason
        return out, elapsed, proc.returncode == 0, (f"exit {proc.returncode}" if proc.returncode else "")
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        elapsed = time.monotonic() - t0
        return f"[timed out after {timeout_s}s]", elapsed, False, f"timeout {timeout_s}s"
    except Exception as e:
        return str(e), time.monotonic() - t0, False, str(e)


# ── judge via LLM ─────────────────────────────────────────────────────────────

JUDGE_SYSTEM = (
    "You are a strict but fair evaluator of LLM demo outputs. "
    "Score the output for the given task on a 1–5 scale:\n"
    "5 = excellent — fully solves the task, well written, no issues\n"
    "4 = good — solves the task with only minor issues\n"
    "3 = acceptable — partially solves the task or has notable flaws\n"
    "2 = poor — mostly wrong or fails to address the task\n"
    "1 = completely wrong, incoherent, errored out, stuck in a loop, or killed before completing\n"
    "IMPORTANT: If the run was killed (stuck loop, timeout, or error), score it 1 regardless of partial output.\n"
    "Reply with ONLY a JSON object: {\"score\": <1-5>, \"reason\": \"<one sentence>\"}"
)

JUDGE_CRITERIA = {
    "monologue": (
        "The output should be a 3-4 sentence fun, nerdy, enthusiastic opening monologue for a Java conference talk. "
        "It should thank the organizers, be slightly self-deprecating, and suit a technical crowd."
    ),
    "memory": (
        "The output must show the model correctly recalling the codeword BANANA in the second turn. "
        "The codeword must appear explicitly in the response."
    ),
    "tools": (
        "The output must show the model using file tools (ls, read-file, grep) to answer "
        "'Describe what this project does.' with an accurate multi-sentence description of the project."
    ),
    "slides-monologue": (
        "The model was given the talk slides and asked to write a JavaZone opening monologue. "
        "Did it actually read the slides (tool calls visible)? "
        "Is the monologue specific to this talk — mentioning LLM APIs, Java, live coding? "
        "Excellent = reads slides + sharp specific monologue. Good = decent monologue but generic. Poor = ignored slides or off-topic."
    ),
    "agent": (
        "The agent was asked to build a calculator app in a subfolder. "
        "Judge generously: does it show meaningful progress? Did it create files, run the tool, produce output? "
        "A working build with output is excellent. Partial progress (files created but build failed) is acceptable. "
        "No files created at all is poor. The generated file contents are provided — use them to assess quality."
    ),
    "agent_cli": (
        "The agent was asked to build a calculator CLI tool with Maven in a subfolder. "
        "Judge generously: does it show meaningful progress? Did it create files, run mvn, produce output? "
        "A working CLI build with output is excellent. Partial progress (files created but build failed) is acceptable. "
        "No files created at all is poor. The generated file contents are provided — use them to assess quality."
    ),
    "viking": (
        "The model was asked to describe the project 'like a viking' — it should have auto-discovered "
        "and activated the viking skill (visible as a 'skill' tool call in the output), then described "
        "the project with Norse flavor (Skål, shield-bearer, saga, longship, etc.). "
        "Excellent = skill auto-activated + Norse delivery + accurate project description. "
        "Good = Norse flavor but skill not activated, or skill activated but description weak. "
        "Poor = no Norse flavor at all."
    ),
    "self-improve": (
        "The agent was given the project's own source code and asked to improve LLMClient.java. "
        "Look at the generated/modified files provided. "
        "Did it make a meaningful, coherent improvement? "
        "Excellent = real improvement with working code. Good = sensible change but incomplete. "
        "Poor = no files changed or only trivial/broken changes."
    ),
}

def _judge_request(base_url: str, model: str, messages: list, api_key: str = "") -> str:
    body = json.dumps({"model": model, "messages": messages, "stream": False}).encode()
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = urllib.request.Request(f"{base_url}/v1/chat/completions", data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        resp = json.loads(r.read())
    return resp["choices"][0]["message"]["content"].strip()


def _ask_judge_stuck(judge_url: str, judge_model: str, judge_api_key: str,
                     recent_lines: list[str]) -> bool:
    """Ask the judge whether the last N lines of output look like a stuck loop. Returns True = stuck."""
    if not judge_url or not judge_model:
        return False
    total = len(recent_lines)
    window = recent_lines[-1000:]
    snippet = "\n".join(window)
    context_note = f"(showing last {len(window)} of {total} total lines)"
    prompt = (
        f"Below are the last lines of output from a local LLM demo run {context_note}. "
        "Does this look like the model is stuck in a thinking/deliberation loop "
        "(e.g. repeating itself, cycling through 'wait… okay… wait…', or endlessly "
        "rephrasing the same idea)? "
        "Reply with exactly one word: YES or NO.\n\n"
        f"--- output ---\n{snippet}\n---"
    )
    try:
        reply = _judge_request(judge_url, judge_model,
                               [{"role": "user", "content": prompt}],
                               judge_api_key)
        return reply.strip().upper().startswith("YES")
    except Exception:
        return False


def judge(base_url: str, model: str, task_id: str, task_label: str, task_input: str,
          output: str, api_key: str = "", extra_files: dict[str, str] | None = None,
          error: str = "") -> dict:
    """Ask the judge model to score a single run. Returns {score, reason}."""
    criteria = JUDGE_CRITERIA.get(task_id, "")
    files_section = ""
    if extra_files:
        parts = []
        for fname, content in extra_files.items():
            snippet = content[:800]
            if len(content) > 800:
                snippet += f"\n... ({len(content) - 800} more chars)"
            parts.append(f"--- {fname} ---\n{snippet}")
        files_section = "\n\nGenerated files:\n" + "\n\n".join(parts)
    error_section = f"\nRUN STATUS: KILLED/FAILED — {error}\n" if error else ""
    prompt = (
        f"Task: {task_label}\n"
        + (f"Criteria: {criteria}\n" if criteria else "")
        + error_section
        + f"Input given to the model:\n{task_input.strip()}\n\n"
        f"Model output:\n{output.strip()[:2000]}\n"
        f"{files_section}\n"
        "Score this output 1–5 and explain in one sentence."
    )
    try:
        text = _judge_request(base_url, model, [
            {"role": "system", "content": JUDGE_SYSTEM},
            {"role": "user",   "content": prompt},
        ], api_key)
        text = re.sub(r'^```[a-z]*\s*', '', text, flags=re.M)
        text = re.sub(r'\s*```$',       '', text, flags=re.M)
        parsed = json.loads(text)
        if "score" in parsed:
            parsed["score"] = int(parsed["score"])
        return parsed
    except Exception as e:
        return {"score": 0, "reason": f"judge error: {e}"}


# ── run all tests for one model ───────────────────────────────────────────────

def run_all(jar: Path, base_url: str, root: str, runs: int,
            judge_url: str, judge_model: str, no_judge: bool,
            judge_api_key: str = "", thinking: bool = True,
            is_big_model: bool = False, thinking_budget: int = -1) -> list[dict]:
    """Returns list of test-group dicts, one per task."""

    tasks = [
        {
            "id": "monologue",
            "label": "Intro monologue",
            "description": "Streaming ChatBot — fun nerdy opening for JavaZone talk",
            "class": "solutions.ChatBot",
            "stdin": MONOLOGUE_INPUT + "\n",
            "extra": [],
            "pass_fn": lambda out, ok: (ok, "") if ok else (False, "non-zero exit"),
            "tmpdir": None,
        },
        {
            "id": "memory",
            "label": "Memory across turns",
            "description": "ChatBot — codeword recalled in second turn",
            "class": "solutions.ChatBot",
            "stdin": MEMORY_INPUT,
            "extra": [],
            "pass_fn": lambda out, ok: (
                (True, "") if ok and "BANANA" in out.upper()
                else (False, "BANANA not recalled")
            ),
            "tmpdir": None,
        },
        {
            "id": "tools",
            "label": "File tools",
            "description": "ToolChatBot — describe what this project does",
            "class": "solutions.ToolChatBot",
            "stdin": TOOL_INPUT,
            "extra": ["--root", root],
            "pass_fn": lambda out, ok: (
                (True, "") if ok and re.search(r'\b(java|llm|library|demo|talk|llama)\b', out, re.I)
                else (False, "no project keywords in response")
            ),
            "tmpdir": None,
        },
        {
            "id": "slides-monologue",
            "label": "Slides-informed monologue",
            "description": "ToolChatBot — read slides, write a better intro",
            "class": "solutions.ToolChatBot",
            "stdin": SLIDES_MONOLOGUE_INPUT,
            "extra": ["--root", SLIDES_ROOT],
            "pass_fn": lambda out, ok: (
                (True, "") if ok and re.search(r'\b(java|javazone|llm|library|oslo)\b', out, re.I)
                else (False, "no talk keywords in response")
            ),
            "tmpdir": None,
        },
        {
            "id": "agent",
            "label": "CodingAgent — Calculator",
            "description": "CodingAgent — build a Maven calculator app",
            "class": "solutions.CodingAgent",
            "stdin": AGENT_INPUT,
            "extra": [],
            "pass_fn": None,  # handled via tmpdir logic below
            "tmpdir": "empty",  # sentinel: create empty tmpdir
            "timeout_s": 300,
        },
        {
            "id": "agent_cli",
            "label": "CodingAgent — Calculator CLI",
            "description": "CodingAgent — build a Maven calculator CLI tool",
            "class": "solutions.CodingAgent",
            "stdin": AGENT_CLI_INPUT,
            "extra": [],
            "pass_fn": None,
            "tmpdir": "empty",
            "timeout_s": 300,
        },
        {
            "id": "viking",
            "label": "SkillCodingAgent — Viking",
            "description": "SkillCodingAgent — auto-discover & activate viking skill, describe project in Norse",
            "class": "solutions.SkillCodingAgent",
            "stdin": VIKING_INPUT,
            "extra": ["--root", root],
            "pass_fn": lambda out, ok: (
                (True, "") if ok and re.search(r'\b(skål|skal|shield|viking|saga|longship|odin|valhalla|norse)\b', out, re.I)
                else (False, "no Norse flavor in response")
            ),
            "tmpdir": None,
        },
        {
            "id": "self-improve",
            "label": "SkillCodingAgent — Self-improve",
            "description": "SkillCodingAgent — improve own LLMClient.java source",
            "class": "solutions.SkillCodingAgent",
            "stdin": SELF_IMPROVE_INPUT,
            "extra": [],
            "pass_fn": None,  # handled via tmpdir logic below
            "tmpdir": "project",  # sentinel: copy project so agent can edit source
            "timeout_s": 300,
        },
    ]

    results = []
    for task in tasks:
        print(f"  ▷ {task['label']} × {runs} …")
        runs_data = []
        for i in range(runs):
            tmpdir_path = None
            extra_files: dict[str, str] = {}

            if task["tmpdir"] == "empty":
                tmpdir_path = tempfile.mkdtemp()
                extra = ["--root", tmpdir_path, "--no-log", "--approve-plans"]
            elif task["tmpdir"] == "project":
                tmpdir_path = tempfile.mkdtemp()
                shutil.copytree(root, tmpdir_path, dirs_exist_ok=True)
                extra = ["--root", tmpdir_path, "--no-log", "--approve-plans"]
            else:
                extra = list(task["extra"])

            if not thinking:
                extra = extra + ["--no-thinking"]
            elif thinking_budget > 0:
                extra = extra + ["--thinking-budget", str(thinking_budget)]

            out, elapsed, ok, err = run_demo(
                jar, task["class"], base_url, task["stdin"], extra,
                timeout_s=task.get("timeout_s", 150),
                judge_url=judge_url, judge_model=judge_model, judge_api_key=judge_api_key,
            )

            if tmpdir_path:
                if task["tmpdir"] == "empty":
                    # calculator agent: pass = any Java files created
                    java_files = list(Path(tmpdir_path).rglob("*.java"))
                    if not java_files:
                        ok, err = False, "no Java files created in sandbox"
                    else:
                        created = ", ".join(f.name for f in java_files[:5])
                        out += f"\n\n[created: {created}]"
                        for jf in java_files[:5]:
                            try: extra_files[jf.name] = jf.read_text(errors="replace")
                            except Exception: pass
                elif task["tmpdir"] == "project":
                    # collect all files changed vs the original project root
                    all_files = list(Path(tmpdir_path).rglob("*"))
                    changed = []
                    for f in all_files:
                        if not f.is_file(): continue
                        rel = f.relative_to(tmpdir_path)
                        orig = Path(root) / rel
                        try:
                            content = f.read_text(errors="replace")
                        except Exception:
                            continue
                        if not orig.exists() or orig.read_text(errors="replace") != content:
                            changed.append((rel, content))
                    if not changed:
                        ok, err = False, "no files changed in project copy"
                    else:
                        names = ", ".join(str(r) for r, _ in changed[:5])
                        out += f"\n\n[changed: {names}]"
                        for rel, content in changed[:5]:
                            extra_files[str(rel)] = content
                        if not ok:
                            ok, err = True, ""
                shutil.rmtree(tmpdir_path, ignore_errors=True)
            elif task["pass_fn"]:
                ok, err = task["pass_fn"](out, ok)

            verdict = {"run": i + 1, "output": out, "elapsed_s": elapsed,
                       "passed": ok, "error": err, "score": None, "reason": ""}
            if not no_judge:
                j = judge(judge_url, judge_model, task["id"], task["label"],
                          task["stdin"], out, judge_api_key,
                          extra_files or None, err)
                verdict["score"] = j.get("score", 0)
                verdict["reason"] = j.get("reason", "")

            runs_data.append(verdict)
            mark = "✓" if ok else "✗"
            score_s = f"  score={verdict['score']}" if verdict["score"] else ""
            print(f"      run {i+1}: {mark}  {elapsed:.1f}s{score_s}")

        # If all runs were stuck/failed due to thinking loops, retry once with a thinking budget cap
        # Only retry for the big (27B) model; use infinite timeout so it has time to finish.
        all_stuck = bool(runs_data) and all(r.get("error", "").startswith("stuck") for r in runs_data)
        if all_stuck and thinking and not no_judge and is_big_model:
            print(f"      ↺ all runs stuck — retrying with thinking budget cap …")
            retry_tmpdir = None
            if task["tmpdir"] == "empty":
                retry_tmpdir = tempfile.mkdtemp()
                extra_retry = ["--root", retry_tmpdir, "--no-log", "--approve-plans", "--thinking-budget", "1000"]
            elif task["tmpdir"] == "project":
                retry_tmpdir = tempfile.mkdtemp()
                shutil.copytree(root, retry_tmpdir, dirs_exist_ok=True)
                extra_retry = ["--root", retry_tmpdir, "--no-log", "--approve-plans", "--thinking-budget", "1000"]
            else:
                extra_retry = list(task["extra"]) + ["--thinking-budget", "1000"]
            out, elapsed, ok, err = run_demo(
                jar, task["class"], base_url, task["stdin"], extra_retry,
                timeout_s=task.get("timeout_s", 150),
                judge_url=judge_url, judge_model=judge_model, judge_api_key=judge_api_key,
                hard_timeout_s=None)
            extra_files = {}
            if retry_tmpdir:
                if task["tmpdir"] == "empty":
                    java_files = list(Path(retry_tmpdir).rglob("*.java"))
                    if not java_files:
                        ok, err = False, "no Java files created in sandbox"
                    else:
                        out += f"\n\n[created: {', '.join(f.name for f in java_files[:5])}]"
                        for jf in java_files[:5]:
                            try: extra_files[jf.name] = jf.read_text(errors="replace")
                            except Exception: pass
                elif task["tmpdir"] == "project":
                    for f in Path(retry_tmpdir).rglob("*"):
                        if not f.is_file(): continue
                        rel = f.relative_to(retry_tmpdir)
                        orig = Path(root) / rel
                        try:
                            content = f.read_text(errors="replace")
                            if not orig.exists() or orig.read_text(errors="replace") != content:
                                extra_files[str(rel)] = content
                        except Exception: pass
                shutil.rmtree(retry_tmpdir, ignore_errors=True)
            verdict = {"run": runs + 1, "output": out, "elapsed_s": elapsed,
                       "passed": ok, "error": err, "score": None, "reason": "",
                       "label": "Retry (budget=1000)"}
            j = judge(judge_url, judge_model, task["id"], task["label"],
                      task["stdin"], out, judge_api_key, extra_files or None, err)
            verdict["score"] = j.get("score", 0)
            verdict["reason"] = j.get("reason", "")
            runs_data.append(verdict)
            mark = "✓" if ok else "✗"
            score_s = f"  score={verdict['score']}" if verdict["score"] else ""
            print(f"      retry: {mark}  {elapsed:.1f}s{score_s}")

        results.append({
            "id": task["id"],
            "label": task["label"],
            "description": task["description"],
            "runs": runs_data,
        })
    return results


# ── HTML ──────────────────────────────────────────────────────────────────────

PAGE_CSS = """
:root{
  --bg:#0f1117;--surface:#1a1d27;--surface2:#222536;--border:#2e3248;
  --text:#e2e8f0;--muted:#8892a4;
  --green:#22c55e;--red:#ef4444;--yellow:#f59e0b;--blue:#60a5fa;
  --purple:#a78bfa;--orange:#fb923c;
  --mono:"JetBrains Mono","Fira Code",ui-monospace,monospace;
}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;font-size:14px;line-height:1.6;padding:2rem}
.page{max-width:1100px;margin:0 auto}
h1{font-size:1.5rem;font-weight:700;color:var(--blue);margin-bottom:.2rem}
.run-subtitle{color:var(--muted);font-size:.82rem;font-family:var(--mono);margin-bottom:2rem}
/* model section */
.model-section{margin-bottom:3rem}
.model-header{display:flex;align-items:baseline;gap:1rem;margin-bottom:1rem;padding-bottom:.5rem;border-bottom:2px solid var(--border)}
.model-name{font-size:1.15rem;font-weight:700;color:var(--purple);font-family:var(--mono)}
.model-meta{color:var(--muted);font-size:.8rem;font-family:var(--mono)}
/* summary bar */
.summary{display:flex;gap:1.25rem;flex-wrap:wrap;background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:1rem 1.25rem;margin-bottom:1.5rem}
.stat{display:flex;flex-direction:column;gap:.05rem}
.stat-value{font-size:1.75rem;font-weight:700;font-family:var(--mono);line-height:1}
.stat-label{font-size:.7rem;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}
.s-pass .stat-value{color:var(--green)}
.s-fail .stat-value{color:var(--red)}
.s-time .stat-value{color:var(--yellow)}
.s-score .stat-value{color:var(--orange)}
/* task cards */
.task{background:var(--surface);border:1px solid var(--border);border-radius:8px;margin-bottom:1rem;overflow:hidden}
.task-header{display:flex;justify-content:space-between;align-items:center;padding:.7rem 1rem;background:var(--surface2);border-bottom:1px solid var(--border);cursor:pointer;user-select:none}
.task-title{font-weight:600;font-size:.9rem}
.task-desc{font-size:.75rem;color:var(--muted);margin-top:.1rem}
.task-meta{display:flex;gap:.6rem;align-items:center;flex-shrink:0}
.badge{font-size:.7rem;font-weight:700;padding:.15em .5em;border-radius:3px;font-family:var(--mono)}
.b-pass{background:rgba(34,197,94,.15);color:var(--green);border:1px solid rgba(34,197,94,.3)}
.b-fail{background:rgba(239,68,68,.15);color:var(--red);border:1px solid rgba(239,68,68,.3)}
.timing{font-family:var(--mono);font-size:.78rem;color:var(--yellow)}
.score-pill{font-family:var(--mono);font-size:.78rem;padding:.1em .45em;border-radius:3px;background:rgba(251,146,60,.15);color:var(--orange);border:1px solid rgba(251,146,60,.3)}
/* runs grid */
.runs{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:.75rem;padding:.75rem}
.run{background:var(--surface2);border:1px solid var(--border);border-radius:6px;overflow:hidden}
.run.r-pass{border-left:3px solid var(--green)}
.run.r-fail{border-left:3px solid var(--red)}
.run-head{display:flex;justify-content:space-between;padding:.45rem .75rem;font-size:.75rem;color:var(--muted);border-bottom:1px solid var(--border)}
.cast-link{color:var(--blue);text-decoration:none;margin-left:.4em;font-family:var(--mono)}
.cast-link:hover{text-decoration:underline}
.run-judge{padding:.4rem .75rem;font-size:.75rem;color:var(--orange);border-bottom:1px solid var(--border);background:rgba(251,146,60,.05)}
.run-error{padding:.35rem .75rem;font-size:.74rem;color:var(--red);border-bottom:1px solid var(--border);background:rgba(239,68,68,.06)}
.run-stuck{padding:.35rem .75rem;font-size:.74rem;font-weight:700;color:#f59e0b;border-bottom:1px solid var(--border);background:rgba(245,158,11,.08);letter-spacing:.02em}
.run.r-stuck{border-left:3px solid #f59e0b}
.run-timeout{padding:.35rem .75rem;font-size:.74rem;font-weight:700;color:#60a5fa;border-bottom:1px solid var(--border);background:rgba(96,165,250,.08);letter-spacing:.02em}
.run.r-timeout{border-left:3px solid #60a5fa}
pre.run-out{padding:.6rem .75rem;font-family:var(--mono);font-size:.72rem;line-height:1.5;white-space:pre-wrap;word-break:break-word;color:#c9d1d9;max-height:320px;overflow-y:auto}
/* collapse toggle */
.runs{display:none}
.task.open .runs{display:grid}
.toggle-hint{font-size:.7rem;color:var(--muted);margin-left:.5rem}
/* verdict banner */
.verdict{background:var(--surface);border:1px solid var(--border);border-left:4px solid var(--blue);border-radius:8px;padding:1rem 1.25rem;margin-bottom:2rem;font-size:.9rem;line-height:1.65}
.verdict-label{font-size:.7rem;color:var(--blue);text-transform:uppercase;letter-spacing:.08em;font-family:var(--mono);margin-bottom:.4rem}
/* top nav */
.toc{display:flex;gap:.5rem;flex-wrap:wrap;margin-bottom:2rem}
.toc a{font-size:.78rem;font-family:var(--mono);color:var(--blue);text-decoration:none;padding:.2em .5em;border:1px solid var(--border);border-radius:4px}
.toc a:hover{background:var(--surface2)}
.footer{margin-top:2rem;color:var(--muted);font-size:.75rem;text-align:center}
"""

PAGE_JS = """
document.querySelectorAll('.task-header').forEach(h => {
  h.addEventListener('click', () => h.closest('.task').classList.toggle('open'));
});
"""

def score_color(s):
    try: s = int(s)
    except (TypeError, ValueError): return "var(--muted)"
    if s is None or s == 0: return "var(--muted)"
    if s >= 4: return "var(--green)"
    if s >= 3: return "var(--yellow)"
    return "var(--red)"

def esc(s): return html_module.escape(str(s))

def render_run(r: dict) -> str:
    is_stuck   = r.get("error", "").startswith("stuck")
    is_timeout = r.get("error", "").startswith("timeout")
    if is_stuck:
        cls = "run r-stuck"
    elif is_timeout:
        cls = "run r-timeout"
    elif r["passed"]:
        cls = "run r-pass"
    else:
        cls = "run r-fail"
    badge = '<span class="badge b-pass">✓</span>' if r["passed"] else '<span class="badge b-fail">✗</span>'
    extra_badge = ""
    if is_stuck:
        extra_badge = ' <span style="color:#f59e0b;font-weight:700">🔁 STUCK</span>'
    elif is_timeout:
        extra_badge = ' <span style="color:#60a5fa;font-weight:700">⏱ TIMEOUT</span>'
    run_label = r.get("label") or f"Run {r['run']}"
    head  = (f'<div class="run-head">'
             f'<span>{esc(run_label)}  {badge}{extra_badge}</span>'
             f'<span class="timing">{r["elapsed_s"]:.1f}s</span></div>')
    judge_html = ""
    if r.get("score") is not None and r["score"]:
        col = score_color(r["score"])
        judge_html = (f'<div class="run-judge">'
                      f'<span style="color:{col};font-weight:700">{r["score"]}/5</span>'
                      f' — {esc(r.get("reason","")[:300])}</div>')
    if is_stuck:
        err_html = f'<div class="run-stuck">🔁 Stuck loop detected — {esc(r["error"])}</div>'
    elif is_timeout:
        err_html = f'<div class="run-timeout">⏱ Timed out — {esc(r["error"])}</div>'
    elif r.get("error"):
        err_html = f'<div class="run-error">⚠ {esc(r["error"])}</div>'
    else:
        err_html = ""
    out = esc(_strip_ansi(r["output"])).strip()
    return f'<div class="{cls}">{head}{judge_html}{err_html}<pre class="run-out">{out}</pre></div>'

def render_task(t: dict) -> str:
    runs   = t["runs"]
    passed = sum(1 for r in runs if r["passed"])
    total  = len(runs)
    times  = [r["elapsed_s"] for r in runs]
    avg_t  = sum(times) / len(times)
    scores = [int(r["score"]) for r in runs if r.get("score")]
    avg_s  = (sum(scores) / len(scores)) if scores else None

    n_stuck   = sum(1 for r in runs if r.get("error","").startswith("stuck"))
    n_timeout = sum(1 for r in runs if r.get("error","").startswith("timeout"))

    all_pass = passed == total
    badge = (f'<span class="badge b-pass">{passed}/{total}</span>' if all_pass
             else f'<span class="badge b-fail">{passed}/{total}</span>')
    score_html = ""
    if avg_s is not None:
        col = score_color(avg_s)
        # show individual scores as small dots
        dots = ""
        for r in runs:
            s = r.get("score")
            if s:
                c = score_color(s)
                dots += f'<span style="color:{c};font-family:var(--mono);font-size:.7rem;margin-left:.15em">{s}</span>'
        score_html = f'<span class="score-pill" style="color:{col}">{avg_s:.1f}/5</span>{dots}'
    warn_html = ""
    if n_stuck:
        warn_html += f' <span style="color:#f59e0b;font-size:.75rem">🔁×{n_stuck}</span>'
    if n_timeout:
        warn_html += f' <span style="color:#60a5fa;font-size:.75rem">⏱×{n_timeout}</span>'

    runs_html = "\n".join(render_run(r) for r in runs)
    return (
        f'<div class="task open" id="task-{esc(t["id"])}">'
        f'<div class="task-header">'
        f'<div><div class="task-title">{esc(t["label"])}</div>'
        f'<div class="task-desc">{esc(t["description"])}</div></div>'
        f'<div class="task-meta">{badge}{warn_html} {score_html}'
        f' <span class="timing">avg {avg_t:.1f}s</span>'
        f'<span class="toggle-hint">▾</span></div>'
        f'</div>'
        f'<div class="runs">{runs_html}</div>'
        f'</div>'
    )

def render_model_section(model_id: str, base_url: str, timestamp: str,
                          task_groups: list[dict]) -> str:
    all_runs   = [r for g in task_groups for r in g["runs"]]
    passed     = sum(1 for r in all_runs if r["passed"])
    total      = len(all_runs)
    total_t    = sum(r["elapsed_s"] for r in all_runs)
    scores     = [int(r["score"]) for r in all_runs if r.get("score")]
    avg_score  = (sum(scores) / len(scores)) if scores else None
    n_stuck    = sum(1 for r in all_runs if r.get("error","").startswith("stuck"))
    n_timeout  = sum(1 for r in all_runs if r.get("error","").startswith("timeout"))
    border_col = "#22c55e" if passed == total else "#ef4444"

    score_stat = ""
    if avg_score is not None:
        col = score_color(avg_score)
        score_stat = (f'<div class="stat s-score">'
                      f'<span class="stat-value" style="color:{col}">{avg_score:.1f}</span>'
                      f'<span class="stat-label">Avg judge score</span></div>')

    loop_stat = ""
    if n_stuck or n_timeout:
        loop_parts = []
        if n_stuck:
            loop_parts.append(f'<span style="color:#f59e0b">🔁 {n_stuck}</span>')
        if n_timeout:
            loop_parts.append(f'<span style="color:#60a5fa">⏱ {n_timeout}</span>')
        loop_stat = (f'<div class="stat">'
                     f'<span class="stat-value" style="font-size:1.1rem">{"  ".join(loop_parts)}</span>'
                     f'<span class="stat-label">Stuck / Timeout</span></div>')

    tasks_html = "\n".join(render_task(g) for g in task_groups)
    slug = re.sub(r'[^a-z0-9]+', '-', model_id.lower()).strip('-')
    return (
        f'<div class="model-section" id="model-{slug}">'
        f'<div class="model-header">'
        f'<span class="model-name">{esc(model_id)}</span>'
        f'<span class="model-meta">{esc(base_url)}  ·  {esc(timestamp)}</span>'
        f'</div>'
        f'<div class="summary" style="border-left:4px solid {border_col}">'
        f'<div class="stat s-pass"><span class="stat-value">{passed}/{total}</span><span class="stat-label">Runs passed</span></div>'
        f'<div class="stat s-fail"><span class="stat-value">{total-passed}</span><span class="stat-label">Failed</span></div>'
        f'<div class="stat s-time"><span class="stat-value">{total_t:.0f}s</span><span class="stat-label">Total time</span></div>'
        f'{score_stat}'
        f'{loop_stat}'
        f'</div>'
        f'{tasks_html}'
        f'</div>'
    )


def _load_config_for_url(url: str) -> dict:
    """Return {key, model} for the matching endpoint in the config file, or empty dict."""
    config_path = Path.home() / ".config" / "tiny-llm-library" / "config.config"
    if not config_path.exists():
        return {}
    props = {}
    with open(config_path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            if '=' in line:
                k, _, v = line.partition('=')
                props[k.strip()] = v.strip()
    for name in set(k.split('.')[0] for k in props):
        if props.get(f"{name}.url", "").rstrip('/') == url.rstrip('/'):
            return {"key": props.get(f"{name}.key", ""), "model": props.get(f"{name}.model", "")}
    return {}


def _load_api_key(url: str) -> str:
    return _load_config_for_url(url).get("key", "")


def _load_judge_model(url: str) -> str:
    return _load_config_for_url(url).get("model", "")



def _load_model_summaries(out_path: Path) -> list[dict]:
    """Parse existing HTML to extract model-level pass/score summaries for the verdict prompt."""
    if not out_path.exists():
        return []
    content = out_path.read_text(encoding="utf-8")
    summaries = []
    for m in re.finditer(r'class="model-name">([^<]+)<', content):
        model_id = html_module.unescape(m.group(1))
        # find the nearest stat-value after this position for passed/total
        snippet = content[m.start():m.start() + 800]
        passed_m = re.search(r'stat s-pass[^>]*>.*?stat-value[^>]*>(\d+)/(\d+)', snippet, re.S)
        passed = int(passed_m.group(1)) if passed_m else 0
        total  = int(passed_m.group(2)) if passed_m else 0
        # avg score if present
        score_m = re.search(r'Avg judge score.*?stat-value[^>]*>([\d.]+)', snippet, re.S)
        avg = float(score_m.group(1)) if score_m else None
        summaries.append({"model_id": model_id, "passed": passed, "total": total,
                          "scores": [avg] if avg else []})
    return summaries


def final_verdict(judge_url: str, judge_model: str, model_summaries: list[dict],
                  api_key: str = "") -> str:
    """Ask the judge which model performed best overall. Returns a verdict string."""
    lines = []
    for s in model_summaries:
        scores = [x for x in s.get("scores", []) if x]
        avg = f"{sum(scores)/len(scores):.1f}" if scores else "n/a"
        timing = f", avg {s['avg_elapsed_s']:.1f}s/run" if s.get("avg_elapsed_s") else ""
        lines.append(f"- {s['model_id']}: {s['passed']}/{s['total']} passed, avg judge score {avg}{timing}")
    summary_text = "\n".join(lines)
    prompt = (
        "You evaluated several LLMs on tasks from a live coding conference demo: "
        "a creative intro monologue, memory recall across turns, file-tool use to describe the project, "
        "a slides-informed monologue, building a Maven calculator app as a coding agent, "
        "describing the project like a viking (tests skill auto-discovery), "
        "and self-improving the project's own source code.\n\n"
        "The speaker needs to pick ONE model to run locally during a 50-minute JavaZone talk. "
        "Requirements: (1) good enough output quality to impress a technical audience, "
        "(2) fast enough that the speaker can take a sip of water while it streams (~5-15s is ideal, <3s is too fast, >30s is too slow), "
        "(3) reliable enough to not fail live on stage.\n\n"
        "Results:\n"
        f"{summary_text}\n\n"
        "Which model should the speaker use? Give a 3-4 sentence verdict: name the winner, "
        "explain why, and flag any concerns. Be direct and opinionated."
    )
    try:
        return _judge_request(judge_url, judge_model, [{"role": "user", "content": prompt}], api_key)
    except Exception as e:
        return f"(verdict unavailable: {e})"



def load_or_init_html(path: Path) -> tuple[str, str, str]:
    """Return (before_verdict, verdict_block, after_verdict) splitting on verdict markers."""
    VERDICT_START   = "<!-- VERDICT_START -->"
    VERDICT_END     = "<!-- VERDICT_END -->"
    SECTIONS_MARKER = "<!-- BENCHMARK_SECTIONS -->"
    if path.exists():
        content = path.read_text(encoding="utf-8")
        if VERDICT_START in content and VERDICT_END in content:
            vs = content.index(VERDICT_START)
            ve = content.index(VERDICT_END) + len(VERDICT_END)
            return content[:vs], content[vs:ve], content[ve:]
        if SECTIONS_MARKER in content:
            idx = content.index(SECTIONS_MARKER)
            return content[:idx], "", content[idx:]
    # fresh file
    page = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LLM Demo Benchmark Results</title>
<style>{PAGE_CSS}</style>
</head>
<body>
<div class="page">
<h1>LLM Demo Benchmark Results</h1>
<div class="run-subtitle">tiny-llm-demo · click a task row to expand runs</div>
<div class="toc" id="toc"></div>
<!-- VERDICT_START --><!-- VERDICT_END -->
<!-- BENCHMARK_SECTIONS -->
<div class="footer">benchmark-models.py · tiny-llm-demo</div>
</div>
<script>
{PAGE_JS}
// build TOC from model sections (oldest first = bottom of page first)
const sections = [...document.querySelectorAll('.model-section')].reverse();
sections.forEach(s => {{
  const a = document.createElement('a');
  a.href = '#' + s.id;
  a.textContent = s.querySelector('.model-name').textContent;
  document.getElementById('toc').appendChild(a);
}});
</script>
</body>
</html>"""
    vs = page.index("<!-- VERDICT_START -->")
    ve = page.index("<!-- VERDICT_END -->") + len("<!-- VERDICT_END -->")
    return page[:vs], page[vs:ve], page[ve:]


def render_verdict_block(verdict_text: str, judge_model: str) -> str:
    return (
        f'<!-- VERDICT_START -->'
        f'<div class="verdict">'
        f'<div class="verdict-label">🏆 Which model should you use? · judged by {esc(judge_model)}</div>'
        f'{esc(verdict_text)}'
        f'</div>'
        f'<!-- VERDICT_END -->'
    )


def append_model_to_html(out_path: Path, section_html: str,
                          verdict_text: str = "", judge_model: str = ""):
    before, _old_verdict, after = load_or_init_html(out_path)
    verdict_block = (render_verdict_block(verdict_text, judge_model)
                     if verdict_text else "<!-- VERDICT_START --><!-- VERDICT_END -->")
    SECTIONS_MARKER = "<!-- BENCHMARK_SECTIONS -->"
    if SECTIONS_MARKER in after:
        idx = after.index(SECTIONS_MARKER) + len(SECTIONS_MARKER)
        after = after[:idx] + "\n" + section_html + after[idx:]
    else:
        after = SECTIONS_MARKER + "\n" + section_html + after
    out_path.write_text(before + verdict_block + after, encoding="utf-8")


# ── default model list ────────────────────────────────────────────────────────

DEFAULT_MODELS = [
    "unsloth/Qwen3.5-2B-GGUF:Qwen3.5-2B-Q8_0.gguf",
    "unsloth/Qwen3.5-4B-GGUF:Qwen3.5-4B-Q8_0.gguf",
    "unsloth/Qwen3.5-9B-GGUF:Qwen3.5-9B-Q8_0.gguf",
    "unsloth/Qwen3.8-27B-GGUF:Qwen3.8-27B-UD-Q4_K_XL.gguf",
]

# ── main ──────────────────────────────────────────────────────────────────────

def run_one(jar: Path, hf_repo: str, base_url: str, port: int,
            out_path: Path, runs: int,
            judge_url: str, judge_model: str, judge_api_key: str, no_judge: bool,
            no_open: bool, thinking: bool = True, model_label: str = "",
            thinking_budget: int = -1) -> None:
    """Benchmark one model on an already-running server, append results to HTML."""
    model_id    = model_label or hf_repo or detect_model(base_url)
    if not thinking:
        think_label = "no-thinking"
    elif thinking_budget > 0:
        think_label = f"thinking-budget-{thinking_budget}"
    else:
        think_label = "thinking"
    model_display = f"{model_id} [{think_label}]"
    timestamp   = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"▶ Model: {model_display}   endpoint: {base_url}")
    if not no_judge:
        print(f"▶ Judge: {judge_model}  @ {judge_url}")
    print(f"▶ Runs per test: {runs}\n")

    task_groups = run_all(jar, base_url, str(SCRIPT_DIR.parent), runs,
                          judge_url, judge_model, no_judge, judge_api_key, thinking,
                          is_big_model=("27B" in model_id or "27B" in hf_repo),
                          thinking_budget=thinking_budget)

    # stdout summary
    print(f"\n{'─'*55}")
    all_runs = [r for g in task_groups for r in g["runs"]]
    passed   = sum(1 for r in all_runs if r["passed"])
    total_t  = sum(r["elapsed_s"] for r in all_runs)
    scores   = [int(r["score"]) for r in all_runs if r.get("score")]
    for g in task_groups:
        gp = sum(1 for r in g["runs"] if r["passed"])
        gt = sum(r["elapsed_s"] for r in g["runs"]) / len(g["runs"])
        gs_list = [int(r["score"]) for r in g["runs"] if r.get("score")]
        gs = f"  score={sum(gs_list)/len(gs_list):.1f}" if gs_list else ""
        failures = [r["error"] for r in g["runs"] if not r["passed"] and r.get("error")]
        ferr = f"  [{failures[0]}]" if failures else ""
        print(f"  {gp}/{runs}  {g['label']}  avg {gt:.1f}s{gs}{ferr}")
    print(f"{'─'*55}")
    print(f"  {passed}/{len(all_runs)} passed  ·  {total_t:.0f}s total\n")

    model_summaries = _load_model_summaries(out_path)
    avg_elapsed = total_t / len(all_runs) if all_runs else 0
    model_summaries.append({"model_id": model_display, "passed": passed,
                             "total": len(all_runs), "scores": scores,
                             "avg_elapsed_s": avg_elapsed})

    verdict_text = ""
    if not no_judge:
        print("▶ Asking judge for final verdict …")
        verdict_text = final_verdict(judge_url, judge_model, model_summaries, judge_api_key)
        print(f"  {verdict_text}\n")

    section = render_model_section(model_display, base_url, timestamp, task_groups)
    append_model_to_html(out_path, section, verdict_text, judge_model)
    print(f"✓ Report → {out_path}")

    if not no_open and sys.platform == "darwin":
        subprocess.run(["open", str(out_path)], check=False)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--models",      nargs="+", default=[], metavar="REPO:FILE",
                   help="HF repos to benchmark in sequence (default: the built-in 2B/4B/9B list)")
    p.add_argument("--base-url",    default="", help="Benchmark a running server (skip auto-start)")
    p.add_argument("--hf-repo",     default="", help="Single HF repo (shorthand for --models with one entry)")
    p.add_argument("--port",        type=int, default=8099)
    p.add_argument("--out",         default=str(DEFAULT_OUT))
    p.add_argument("--judge-url",   default="")
    p.add_argument("--judge-model", default="")
    p.add_argument("--judge-key",   default="")
    p.add_argument("--no-judge",    action="store_true")
    p.add_argument("--runs",        type=int, default=3)
    p.add_argument("--no-open",     action="store_true")
    p.add_argument("--jar",         default=str(DEFAULT_JAR))
    p.add_argument("--no-thinking", action="store_true",
                   help="Run only without thinking mode (default: run both with and without)")
    p.add_argument("--thinking-only", action="store_true",
                   help="Run only with thinking mode enabled")
    p.add_argument("--thinking-budget", type=int, default=-1,
                   help="Cap thinking tokens per call (e.g. 512, 1024). Implies thinking mode.")
    args = p.parse_args()

    jar = Path(args.jar)
    if not jar.exists():
        print(f"✗ JAR not found: {jar}\n  Run 'mvn package -q' first.", file=sys.stderr); sys.exit(1)

    # resolve judge endpoint — prefer gardener from config, fall back to base-url
    gardener_url = "https://models.answering-machine.utility.gardener.cloud.sap"
    judge_url     = args.judge_url or (_load_api_key(gardener_url) and gardener_url) or args.base_url or "http://localhost:8080"
    judge_api_key = args.judge_key or _load_api_key(judge_url)
    # prefer model from config file over auto-detect (detect_model hits /v1/models without auth)
    judge_model   = args.judge_model or _load_judge_model(judge_url) or detect_model(judge_url)

    out_path = Path(args.out)

    # fresh report for each full run — avoids duplicate model sections
    if out_path.exists():
        out_path.unlink()
        print(f"  (cleared previous report)")

    # Build a flat schedule: list of (hf_repo, base_url, thinking) tuples in run order.
    # Default: small models no-thinking first, then small models with thinking, then big model
    # with thinking. Custom --models / --base-url / --hf-repo still respect --no-thinking /
    # --thinking-only flags with the old per-model interleaving.
    using_defaults = not args.base_url and not args.hf_repo and not args.models
    if args.no_thinking:
        think_modes = [False]
    elif args.thinking_only or args.thinking_budget > 0:
        think_modes = [True]
    else:
        think_modes = [True, False]

    if using_defaults:
        small_models = DEFAULT_MODELS[:-1]
        big_model    = DEFAULT_MODELS[-1]
        if args.no_thinking:
            schedule = [(m, "", False) for m in DEFAULT_MODELS]
        elif args.thinking_only:
            schedule = [(m, "", True)  for m in DEFAULT_MODELS]
        else:
            # small models: all no-thinking, then all thinking; big model: both back-to-back
            schedule = ([(m, "", False) for m in small_models] +
                        [(m, "", True)  for m in small_models] +
                        [(big_model, "", False), (big_model, "", True)])
    else:
        if args.base_url:
            raw_targets = [("", args.base_url)]
        elif args.hf_repo:
            raw_targets = [(args.hf_repo, "")]
        else:
            raw_targets = [(m, "") for m in args.models]
        schedule = [(hf, url, t) for hf, url in raw_targets for t in think_modes]

    total_runs = len(schedule)
    # track which server is currently running to avoid redundant restarts
    current_hf   = None
    server_proc  = None
    active_url   = ""

    try:
        for run_idx, (hf_repo, base_url, thinking) in enumerate(schedule, 1):
            think_label = "thinking" if thinking else "no-thinking"
            print(f"\n{'='*55}")
            print(f"  Run {run_idx}/{total_runs}: {hf_repo or base_url}  [{think_label}]")
            print(f"{'='*55}\n")

            # start / switch server when the model changes
            if hf_repo and hf_repo != current_hf:
                if server_proc:
                    print(f"\n▶ Stopping server PID {server_proc.pid} …")
                    server_proc.terminate()
                    try:
                        server_proc.wait(timeout=15)
                    except Exception:
                        server_proc.kill()
                        server_proc.wait(timeout=5)
                    for _ in range(30):
                        with socket.socket() as s:
                            if s.connect_ex(("localhost", args.port)) != 0:
                                break
                        time.sleep(1)
                    else:
                        print(f"  ⚠ Port {args.port} still in use — proceeding anyway",
                              file=sys.stderr)
                    server_proc = None
                server_proc = start_server(hf_repo, args.port)
                active_url  = f"http://localhost:{args.port}"
                current_hf  = hf_repo
            elif not hf_repo:
                active_url = base_url

            is_last = run_idx == total_runs
            run_one(jar, "", active_url, args.port,
                    out_path, args.runs,
                    judge_url, judge_model, judge_api_key, args.no_judge,
                    args.no_open if is_last else True,
                    thinking,
                    model_label=hf_repo if hf_repo else active_url,
                    thinking_budget=args.thinking_budget)
    finally:
        if server_proc:
            print(f"\n▶ Stopping server PID {server_proc.pid} …")
            server_proc.terminate()
            try:
                server_proc.wait(timeout=15)
            except Exception:
                server_proc.kill()
                server_proc.wait(timeout=5)


if __name__ == "__main__":
    main()
