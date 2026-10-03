#!/usr/bin/env python3
"""
android-kit PreToolUse hook: UI work is Claude-only.

A Sidequest executor running on a GPT (or other non-Claude) route may read UI files but not change them:
Compose code (@Composable), theme / design-system code, UI resources (layouts, drawables, styles, colors,
dimens, fonts, animations), screenshot tests and their references. It hands the UI part back instead, and
the orchestrator files it as a UI ticket, which routes to Claude.

The main session and Claude-routed executors are never affected. Turn off per project with
`touch .claude/kit/ui-guard-off`.
"""
import json, os, re, sys

# Sidequest executor definitions (agent_type): GPT routes run as sidequest-exec-dispatch[-readonly] or a
# discovered-model executor; one-off native executors carry the runtime in their name (…-gpt-6-luna).
NON_CLAUDE = re.compile(r"^sidequest-exec-(dispatch|(readonly-)?model-)|^sidequest-native-.*-(gpt|grok|codex)(-|$)")
UI_PATH = re.compile(
    r"/res/(layout|drawable|mipmap|anim|animator|color|font)[^/]*/"
    r"|/res/values[^/]*/(colors|themes|styles|dimens)\.xml$"
    r"|/(theme|designsystem|design-system|designSystem)/.*\.kt$"
    r"|/src/screenshotTest[^/]*/"
    r"|/(screenshots|snapshot|snapshots|goldens)/"
)


def text_of(tool_input):
    parts = [tool_input.get(k) or "" for k in ("content", "new_string", "new_source")]
    parts += [e.get("new_string") or "" for e in tool_input.get("edits") or [] if isinstance(e, dict)]
    return "\n".join(parts)


def is_ui(path, new_text):
    p = "/" + path.replace("\\", "/").lstrip("/")
    if UI_PATH.search(p):
        return True
    if not p.endswith(".kt"):
        return False
    if "@Composable" in new_text or "@Preview" in new_text:
        return True
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            old = f.read(2_000_000)
            return "@Composable" in old or "@Preview" in old
    except OSError:
        return False


def deny(reason):
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))
    sys.exit(0)


def main():
    try:
        data = json.load(sys.stdin)
    except ValueError:
        return
    agent = str(data.get("agent_type") or data.get("agentType") or "").removeprefix("sidequest:")
    if not NON_CLAUDE.search(agent):
        return
    if os.path.exists(os.path.join(os.environ.get("CLAUDE_PROJECT_DIR", "."), ".claude/kit/ui-guard-off")):
        return
    tool, ti = data.get("tool_name", ""), data.get("tool_input") or {}
    handback = ("Finish the non-UI part of the ticket, then release it with --release-kind handback and name the UI "
                "change that's still needed; the orchestrator files it as a UI ticket, which routes to Claude.")
    if tool == "Bash":
        if re.search(r"update\w*ScreenshotTest", ti.get("command", "")):
            deny("android-kit: screenshot references are UI work, which is Claude-only here, and this executor runs "
                 "on a GPT route. Verify with validateDebugScreenshotTest instead; if references need updating, " + handback)
        return
    path = ti.get("file_path") or ti.get("notebook_path") or ""
    if path and not os.path.isabs(path):
        path = os.path.join(data.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR", "."), path)
    if path and is_ui(path, text_of(ti)):
        deny(f"android-kit: {os.path.basename(path)} is UI (Compose, theme or UI resources), which is Claude-only "
             f"in this project, and this executor runs on a GPT route. Don't change it. " + handback)


if __name__ == "__main__":
    main()
