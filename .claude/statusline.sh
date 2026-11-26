#!/usr/bin/env bash
# Status line (owner decision 2026-10-06): git state, CI on this branch, model and context, plan usage (5-hour and
# 7-day limits), session cost and time, open PRs and the repository's self-hosted runners, if any. Reads Claude Code's status JSON on stdin. GitHub data comes from a cache refreshed in the
# background (CI and PRs every 60 s, runners every 5 min), so drawing the line never waits on the network.
input=$(cat)
j() { printf '%s' "$input" | jq -r "$1 // empty" 2>/dev/null; }

model=$(j '.model.display_name')
pct=$(j '.context_window.used_percentage')
[ -z "$pct" ] && pct=$(j '.context.used_percentage')
cost=$(j '.cost.total_cost_usd')
dur_ms=$(j '.cost.total_duration_ms')
dir=$(j '.workspace.current_dir')
u5=$(j '.rate_limits.five_hour.used_percentage')
u5r=$(j '.rate_limits.five_hour.resets_at')
u7=$(j '.rate_limits.seven_day.used_percentage')
[ -n "$dir" ] && cd "$dir" 2>/dev/null

parts=()

if branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null); then
    g="$branch"
    dirty=$(git status --porcelain --untracked-files=no 2>/dev/null | wc -l | tr -d ' ')
    if counts=$(git rev-list --left-right --count '@{u}...HEAD' 2>/dev/null); then
        behind=${counts%%[[:space:]]*}; ahead=${counts##*[[:space:]]}
        [ "$ahead" -gt 0 ] && g="$g ↑$ahead"
        [ "$behind" -gt 0 ] && g="$g ↓$behind"
    fi
    [ "$dirty" -gt 0 ] && g="$g ●$dirty"
    [ "$dirty" -eq 0 ] && [ "${ahead:-0}" -eq 0 ] && [ "${behind:-0}" -eq 0 ] && g="$g ✓"
    parts+=("$g")

    repo_key=$(git rev-parse --show-toplevel 2>/dev/null | tr '/\\: ' '____')
    cache="${TMPDIR:-/tmp}/claude-statusline-$repo_key"
    now=$(date +%s)
    ci_cache="$cache.ci.$(printf '%s' "$branch" | tr '/\\: ' '____')"
    age() { [ -f "$1" ] && echo $(( now - $(date -r "$1" +%s 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0) )) || echo 99999; }
    if command -v gh >/dev/null 2>&1; then
        if [ "$(age "$ci_cache")" -gt 60 ]; then
            ( touch "$ci_cache"
              gh run list --branch "$branch" --limit 1 --json status,conclusion \
                  -q '.[0] | if . == null then "" elif .status != "completed" then "CI …" elif .conclusion == "success" then "CI ✓" else "CI ✗" end' \
                  > "$ci_cache.tmp" 2>/dev/null && mv "$ci_cache.tmp" "$ci_cache"
              gh pr list --state open --json number -q 'length' > "$cache.prs.tmp" 2>/dev/null && mv "$cache.prs.tmp" "$cache.prs" ) &
        fi
        if [ "$(age "$cache.runners")" -gt 300 ]; then
            ( touch "$cache.runners"
              slug=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)
              [ -n "$slug" ] && gh api "repos/$slug/actions/runners" \
                  -q '(.runners | length) as $all | ([.runners[] | select(.status == "online")] | length) as $on | if $all == 0 then "" else "runners \($on)/\($all)" end' \
                  > "$cache.runners.tmp" 2>/dev/null && mv "$cache.runners.tmp" "$cache.runners" ) &
        fi
        ci=$(cat "$ci_cache" 2>/dev/null); [ -n "$ci" ] && parts+=("$ci")
        prs=$(cat "$cache.prs" 2>/dev/null); [ -n "$prs" ] && [ "$prs" != "0" ] && parts+=("PRs $prs")
        run=$(cat "$cache.runners" 2>/dev/null); [ -n "$run" ] && parts+=("$run")
    fi
fi

m="$model"
[ -n "$pct" ] && m="$m ${pct%.*}%"
[ -n "$m" ] && parts+=("$m")

if [ -n "$u5" ] || [ -n "$u7" ]; then
    u=""
    if [ -n "$u5" ]; then
        u="5h ${u5%.*}%"
        if [ -n "$u5r" ]; then
            reset=$(date -r "${u5r%.*}" +%H:%M 2>/dev/null || date -d "@${u5r%.*}" +%H:%M 2>/dev/null)
            [ -n "$reset" ] && u="$u ↻$reset"
        fi
    fi
    [ -n "$u7" ] && u="${u:+$u }7d ${u7%.*}%"
    parts+=("$u")
fi

if [ -n "$cost" ] || [ -n "$dur_ms" ]; then
    t=""
    [ -n "$cost" ] && t=$(printf '$%.2f' "$cost" 2>/dev/null)
    if [ -n "$dur_ms" ]; then
        mins=$(( ${dur_ms%.*} / 60000 ))
        t="$t $(( mins / 60 ))h$(printf '%02d' $(( mins % 60 )))m"
    fi
    parts+=("${t# }")
fi

out=""
for p in "${parts[@]}"; do out="${out:+$out · }$p"; done
printf '%s' "$out"
