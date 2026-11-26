#!/usr/bin/env bash
# PostToolUse hook: formats a Java file Claude just wrote or edited with the project's Spotless setup, so edits never
# need a separate spotlessApply round. Runs in the file's own worktree; silent and non-blocking on any failure.
# Needs jq on the PATH (winget install jqlang.jq on Windows); without it the hook does nothing.
f=$(jq -r '.tool_response.filePath // .tool_input.file_path // empty' 2>/dev/null) || exit 0
case "$f" in
    *.java) ;;
    *) exit 0 ;;
esac
# Git Bash on Windows: the tool hands over C:\... paths. Shell tools want the POSIX form, Spotless the native one.
posix="$f"
if command -v cygpath > /dev/null 2>&1; then posix=$(cygpath -u "$f"); fi
[ -f "$posix" ] || exit 0
root=$(git -C "$(dirname "$posix")" rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -f "$root/gradlew" ] || exit 0
cd "$root" && ./gradlew spotlessApply -PspotlessIdeHook="$f" -q >/dev/null 2>&1 || true
