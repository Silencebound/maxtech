"""PreToolUse guard (owner decision 2026-10-06).

Refuses, before the tool runs:
- anything that would write or commit the owner's private identity (checked against SHA-256 hashes, so the repo never
  holds the words themselves);
- hand edits under src/generated/ (regenerate with runData instead);
- shell commands that break the shared machines or the history: force-push, pushing main, stopping every Gradle daemon,
  killing Java or Gradle processes by name.
Reads the hook JSON on stdin; prints a deny decision or nothing. Never blocks on its own errors.
"""

import hashlib
import json
import re
import sys

BLOCKED_HASHES = {
    "2a797f0774788e34d9e76604366f6cbcda00d93738c3f43605dd3582335c55a7",
    "9756358403fa83f725aeab074b5f9524522697554ee641efd7025c4903663c24",
    "6af14066128bf0cbde873585bb05cfebdbc83607dcdceced2e4c4bd618520018",
    "eb525d70bb620a1362f7f3690a044078b727422c1835d83d24c75c87d23f644f",
}

DANGEROUS = [
    (r"git\s+push\b[^|;&]*(--force\b|--force-with-lease\b|\s-f\b|\s\+\S)", "force-push is not allowed"),
    (r"git\s+push\b[^|;&]*\s(origin\s+)?(HEAD:)?(refs/heads/)?main\b", "push to main only through a PR"),
    (r"gradlew(\.bat)?\b[^|;&]*--stop\b", "./gradlew --stop also kills CI jobs and other worktrees' builds"),
    (r"\b(pkill|killall)\b[^|;&]*\b(java|gradle|devlaunch)", "stop only your own dev games with scripts/stop-dev-games.sh"),
    (r"\btaskkill\b[^|;&]*/im\s+(java|gradle)", "stop only your own dev games with scripts/stop-dev-games.sh"),
]


def deny(reason):
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))
    sys.exit(0)


def leaks_identity(text):
    for token in re.findall(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+|[A-Za-z0-9.-]+", text.lower()):
        for candidate in {token, token.strip(".-")}:
            if hashlib.sha256(candidate.encode()).hexdigest() in BLOCKED_HASHES:
                return True
    return False


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return
    tool = data.get("tool_name", "")
    inp = data.get("tool_input") or {}
    if tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        path = str(inp.get("file_path", "")).replace("\\", "/")
        if "/src/generated/" in path:
            deny("src/generated/ is datagen output: change the generator and run runData instead of editing it")
        text = " ".join(str(v) for k, v in inp.items() if k in ("content", "new_string", "new_source"))
        for edit in inp.get("edits") or []:
            text += " " + str(edit.get("new_string", ""))
        if leaks_identity(text):
            deny("this text contains the owner's private identity; use the handle Silencebound instead")
    elif tool == "Bash":
        command = str(inp.get("command", ""))
        for pattern, reason in DANGEROUS:
            if re.search(pattern, command, re.IGNORECASE):
                deny(reason)
        if re.search(r"\bgit\b|\bgh\b", command) and leaks_identity(command):
            deny("this command would put the owner's private identity into git or GitHub")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        pass
