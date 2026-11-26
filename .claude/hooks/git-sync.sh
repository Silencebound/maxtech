#!/usr/bin/env bash
# Keeps a checkout in step between machines (owner decision 2026-10-06). Run by Claude Code hooks:
#   start: fetch, fast-forward the current branch when it is clean and only behind, report the state, a failed nightly
#          run, open PRs and the decision and owner-task issues waiting on the owner.
#   end:   push the current feature branch when it has unpushed commits (never main), warn about uncommitted changes.
# Never commits, never merges, never force-pushes. Portable bash (macOS, Linux, Git Bash).
set -u
mode="${1:-start}"
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0
branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null) || exit 0
dirty=$(git status --porcelain --untracked-files=no | wc -l | tr -d ' ')
git fetch --quiet --prune origin 2>/dev/null || { echo "git-sync: fetch failed (offline?)"; exit 0; }
upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)
ahead=0
behind=0
if [ -n "$upstream" ]; then
    ahead=$(git rev-list --count "$upstream..HEAD")
    behind=$(git rev-list --count "HEAD..$upstream")
fi

case "$mode" in
start)
    msg="git-sync: on $branch"
    if [ "$behind" -gt 0 ] && [ "$ahead" -eq 0 ] && [ "$dirty" -eq 0 ]; then
        if git merge --ff-only --quiet "$upstream" 2>/dev/null; then
            msg="$msg, fast-forwarded $behind commit(s) from $upstream"
            behind=0
        fi
    fi
    [ "$behind" -gt 0 ] && msg="$msg, $behind behind $upstream (not pulled: local changes or diverged)"
    [ "$ahead" -gt 0 ] && msg="$msg, $ahead unpushed commit(s)"
    [ "$dirty" -gt 0 ] && msg="$msg, $dirty uncommitted file(s)"
    [ -z "$upstream" ] && msg="$msg (no upstream)"
    echo "$msg"
    if command -v gh >/dev/null 2>&1; then
        nightly=$(gh issue list --state open --search "Nightly CI failed in:title" --json number -q 'length' 2>/dev/null || echo "")
        [ -n "$nightly" ] && [ "$nightly" != "0" ] && echo "git-sync: a \"Nightly CI failed\" issue is open; check it first"
        prs=$(gh pr list --state open --json number,title,statusCheckRollup \
            -q '.[] | "#\(.number) \(.title) [\(([.statusCheckRollup[]?.conclusion // "pending"] | unique | join(",")) // "no checks")]"' 2>/dev/null || true)
        [ -n "$prs" ] && printf 'git-sync: open PRs:\n%s\n' "$prs"
        # Waiting on the owner: decision and owner-task issues here and in the notes repo. A decision with a ticked
        # option or a reply is answered: apply it, log it in the decision log, close the issue.
        waiting=""
        # The notes repo is the origin of ~/notes, where the owner keeps it; without that clone only this repo counts.
        notes=$(cd ~/notes 2>/dev/null && gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)
        for repo in "$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" $notes; do
            [ -n "$repo" ] || continue
            for label in kind:decision kind:owner-task; do
                w=$(gh issue list -R "$repo" --state open --label "$label" --json number,title,body,comments \
                    -q ".[] | \"$repo#\(.number) \(.title)\(if (.body | test(\"- \\\\[[xX]\\\\]\")) or (.comments | length) > 0 then \" [answered or replied]\" else \"\" end)\"" \
                    2>/dev/null || true)
                [ -n "$w" ] && waiting="$waiting$w"$'\n'
            done
        done
        [ -n "$waiting" ] && printf 'git-sync: waiting on the owner:\n%s' "$waiting"
    fi
    ;;
end)
    if [ "$branch" != "main" ] && [ "$branch" != "master" ]; then
        if [ -z "$upstream" ] && [ -n "$(git log --oneline origin/main..HEAD 2>/dev/null | head -1)" ]; then
            git push --quiet -u origin "$branch" 2>/dev/null && echo "git-sync: pushed new branch $branch"
        elif [ "$ahead" -gt 0 ]; then
            git push --quiet origin "$branch" 2>/dev/null && echo "git-sync: pushed $ahead commit(s) on $branch"
        fi
    fi
    if [ "$dirty" -gt 0 ]; then
        echo "{\"systemMessage\": \"git-sync: $dirty uncommitted file(s) on $branch are only on this machine. Commit and push before switching.\"}"
    fi
    ;;
esac
exit 0
