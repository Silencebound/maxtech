#!/usr/bin/env bash
# One look at open PRs and local worktrees, for the main session.
#
#   scripts/status.sh           open PRs (number, title, base, head, CI, review-gate, latest verdict, mergeable,
#                               auto-merge) and the worktrees under .claude/worktrees with their branch and PR state
#   scripts/status.sh --clean   also removes worktrees whose PR is merged or closed and that have no uncommitted,
#                               untracked or unpushed changes; dirty, locked and current worktrees are only listed
#
# Verdict is the latest review starting "Verdict:"; it shows "@<sha>" when it was given on an older commit than the
# head. A worktree's changes count as pushed when its HEAD is in an origin branch or in its PR's final head. Branches
# of removed worktrees are kept. Never uses --force.
set -euo pipefail

clean=0
case "${1:-}" in
    "") ;;
    --clean) clean=1 ;;
    *)
        sed -n '2,/^set -euo/p' "$0" | sed '$d'
        exit 2
        ;;
esac

# Prints tab-separated lines as space-aligned columns.
table() {
    awk -F '\t' '
        { for (i = 1; i <= NF; i++) { cell[NR, i] = $i; if (length($i) > w[i]) w[i] = length($i) } if (NF > cols) cols = NF }
        END {
            for (r = 1; r <= NR; r++) {
                line = " "
                for (i = 1; i <= cols; i++) line = line " " sprintf("%-" w[i] "s", cell[r, i])
                sub(/ +$/, "", line)
                print line
            }
        }'
}

root=$(git rev-parse --show-toplevel)
here=$(cd "$root" && pwd -P)

# Open PRs, one tab-separated line each.
echo "Open PRs"
# shellcheck disable=SC2016 # $c and $h are jq variables
prs=$(gh pr list --state open --limit 100 \
    --json number,title,baseRefName,headRefOid,mergeable,autoMergeRequest,statusCheckRollup,reviews --jq '
    def ci: [.statusCheckRollup[] | select(.context != "review-gate")] as $c
        | if ($c | length) == 0 then "none"
          elif any($c[]; (.conclusion // .state // "") | test("FAILURE|ERROR|CANCELLED|TIMED_OUT|ACTION_REQUIRED")) then "fail"
          elif any($c[]; (.status // "COMPLETED") != "COMPLETED" or .state == "PENDING" or .state == "EXPECTED") then "running"
          else "pass" end;
    def gate: [.statusCheckRollup[] | select(.context == "review-gate") | .state | ascii_downcase] | first // "-";
    def verdict: .headRefOid as $h
        | [.reviews[] | select(.body | startswith("Verdict:"))] | sort_by(.submittedAt) | last
        | if . == null then "-"
          else (.body | split("\n")[0] | sub("^Verdict: *"; "") | .[0:16])
               + (if .commit.oid == $h then "" else "@" + .commit.oid[0:7] end) end;
    .[] | [.number, (.title | .[0:44]), .baseRefName, .headRefOid[0:7], ci, gate, verdict, .mergeable,
           (if .autoMergeRequest == null then "off" else "on" end)] | @tsv')
if [ -z "$prs" ]; then
    echo "  none"
else
    {
        printf '#\ttitle\tbase\thead\tci\tgate\tverdict\tmergeable\tauto\n'
        printf '%s\n' "$prs"
    } | table
fi

# PRs of every state by branch, newest first: "<branch>\t<number>\t<state>\t<final head>".
all=$(gh pr list --state all --limit 400 --json number,headRefName,state,headRefOid \
    --jq '.[] | [.headRefName, .number, .state, .headRefOid] | @tsv')

echo
echo "Worktrees (.claude/worktrees)"
rows=""
removed=""
kept=""
wt="" branch="" locked=0
flush() {
    [ -n "$wt" ] || return 0
    case "$wt" in */.claude/worktrees/*) ;; *) wt=""; return 0 ;; esac
    local name pr_line number="-" state="-" final="" changes note=""
    name=${wt##*/}
    if [ -n "$branch" ]; then
        pr_line=$(printf '%s\n' "$all" | awk -F '\t' -v b="$branch" '$1 == b { print; exit }')
        if [ -n "$pr_line" ]; then
            number=$(printf '%s' "$pr_line" | cut -f2)
            state=$(printf '%s' "$pr_line" | cut -f3 | tr '[:upper:]' '[:lower:]')
            final=$(printf '%s' "$pr_line" | cut -f4)
        fi
    fi
    if [ ! -d "$wt" ]; then
        changes="missing"
    elif [ -n "$(git -C "$wt" status --porcelain 2>/dev/null | head -n 1)" ]; then
        changes="uncommitted"
    elif [ -n "$(git -C "$wt" rev-list -n 1 HEAD --not --remotes=origin 2>/dev/null)" ] \
        && ! { [ -n "$final" ] && git -C "$wt" merge-base --is-ancestor HEAD "$final" 2>/dev/null; }; then
        changes="unpushed"
    else
        changes="clean"
    fi
    if [ "$locked" = 1 ]; then
        note="locked"
    elif [ "$(cd "$wt" 2>/dev/null && pwd -P)" = "$here" ]; then
        note="current"
    elif [ -z "$branch" ]; then
        note="detached"
    elif [ "$state" = merged ] || [ "$state" = closed ]; then
        if [ "$changes" = clean ]; then
            if [ "$clean" = 1 ]; then
                if git -C "$root" worktree remove "$wt" 2>/dev/null; then
                    note="removed"
                    removed="$removed $name"
                else
                    note="remove failed"
                    kept="$kept $name"
                fi
            else
                note="removable (--clean)"
            fi
        else
            note="kept: $changes"
            kept="$kept $name"
        fi
    fi
    [ "$number" = - ] || number="#$number"
    rows="$rows$name	${branch:--}	$number $state	$changes	$note
"
    wt=""
}
while IFS= read -r line; do
    case "$line" in
        "worktree "*) flush; wt=${line#worktree }; branch=""; locked=0 ;;
        "branch refs/heads/"*) branch=${line#branch refs/heads/} ;;
        locked*) locked=1 ;;
    esac
done < <(git -C "$root" worktree list --porcelain)
flush

if [ -z "$rows" ]; then
    echo "  none"
else
    {
        printf 'worktree\tbranch\tpr\tchanges\tnote\n'
        printf '%s' "$rows"
    } | table
fi
if [ "$clean" = 1 ]; then
    git -C "$root" worktree prune
    echo
    echo "removed:${removed:- none}"
    [ -z "$kept" ] || echo "kept (merged or closed PR, but not clean):$kept"
fi
