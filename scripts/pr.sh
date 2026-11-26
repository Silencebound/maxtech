#!/usr/bin/env bash
# PR helper for the main session and reviewers: status, waiting for CI, and merging (including stacked PRs).
#
#   scripts/pr.sh status <pr>   one line: state, head, CI, auto-merge, mergeable, base
#   scripts/pr.sh wait <pr>     block until CI on the current head finishes; exit 0 if green, 1 if not
#   scripts/pr.sh merge <pr>    bring the PR up to date with its base, wait for green CI, squash-merge, then fix up any
#                               PR stacked on it
#   scripts/pr.sh merge-all <pr>...  merge several PRs in order (one background job for a whole wave)
#   scripts/pr.sh auto <pr>     bring the PR up to date with its base and turn on GitHub auto-merge (squash), then
#                               return; GitHub merges it once CI and the review-gate status are green
#
# Merge gate (owner decision 2026-10-19): branch protection requires the build check and the review-gate status
# (.github/scripts/review-gate.sh): a risk label on every PR (trivial, normal, risky), a reviewer's "Verdict: Approve"
# on the head for risky PRs, and no needs-owner label. `merge` turns auto-merge on too, so a PR merges even when
# this script stops waiting. Auto-merge never deletes the branch; `merge` does that after fixing stacked PRs.
#
# Up to date first (owner decision 2026-10-18): before waiting for CI, `merge` checks that the PR branch contains the
# current origin/<base> (origin/main for a normal PR). If not, it merges the base into the branch in a temporary
# worktree (a normal merge commit; the checkout you run it from is not touched), pushes, and waits for CI on that new
# head, so nothing is merged on CI results from an old main. On a conflict it aborts, names the files and stops.
# PRs from forks are refused (their branch is not origin's); temporary worktrees are removed on every exit.
#
# Auto-merge sometimes does not fire after CI goes green. When it is on, `wait` and `merge` give it AUTO_MERGE_GRACE
# seconds after the last check finished; if the PR is still open then, they merge it themselves (the `merge` path) and
# say so.
#
# Stacked PRs: this repo deletes a branch on merge and GitHub then retargets PRs stacked on it to the merged PR's base;
# `merge` also retargets them itself in case that setting changes. It then brings the base into each child: when the
# child already contains the merged head and the base's tree equals that head's tree (the squash changed nothing else),
# the child's tree is already right and is kept with an "ours" merge; otherwise it tries a normal merge and stops on
# conflicts for a human. Never force-pushes.
set -euo pipefail

usage() {
    sed -n '2,/^set -euo/p' "$0" | sed '$d'
}

cmd="${1:-}"
pr="${2:-}"
[ -n "$cmd" ] && [ -n "$pr" ] || { usage; exit 2; }

checks() {
    gh pr view "$pr" --json statusCheckRollup --jq '[.statusCheckRollup[] | (.status // .state) + "/" + (.conclusion // .state // "")] | join(",")'
}

status() {
    gh pr view "$pr" --json state,headRefOid,baseRefName,mergeable,autoMergeRequest,statusCheckRollup --jq \
        '"\(.state) head=\(.headRefOid[0:7]) base=\(.baseRefName) mergeable=\(.mergeable) auto=\(.autoMergeRequest != null) ci=\([.statusCheckRollup[] | (.status // .state) + "/" + (.conclusion // .state // "")] | join(","))"' \
        | sed "s/^/#$pr /"
}

wait_ci() {
    # A fresh push can take a few seconds to register its checks.
    local head started=0
    local branch remote
    branch=$(gh pr view "$pr" --json headRefName --jq .headRefName)
    head=$(gh pr view "$pr" --json headRefOid --jq .headRefOid)
    for _ in $(seq 1 90); do
        local now c
        now=$(gh pr view "$pr" --json headRefOid --jq .headRefOid)
        if [ "$now" != "$head" ]; then
            head=$now
            started=0
        fi
        # Right after a push (e.g. fix_child merging the base in) the API can still report the old head and its
        # old green checks; only trust checks once the PR's head matches what the branch really points at.
        remote=$(git ls-remote origin "refs/heads/$branch" | cut -f1)
        if [ -n "$remote" ] && [ "$remote" != "$now" ]; then
            sleep 15
            continue
        fi
        c=$(checks)
        case "$c" in
            "")
                # GitHub starts no checks on a PR that conflicts with its base; waiting would only time out.
                if [ "$(gh pr view "$pr" --json mergeable --jq .mergeable)" = CONFLICTING ]; then
                    echo "PR conflicts with its base; no CI will run on ${head:0:7}. Merge the base in and push."
                    return 1
                fi
                ;;
            *IN_PROGRESS* | *QUEUED* | *PENDING* | *WAITING*) started=1 ;;
            *FAILURE* | *ERROR* | *CANCELLED* | *TIMED_OUT* | *ACTION_REQUIRED*)
                echo "CI failed on ${head:0:7}: $c"
                return 1
                ;;
            *)
                echo "CI green on ${head:0:7}"
                return 0
                ;;
        esac
        sleep 30
    done
    echo "CI still running after 45 minutes on ${head:0:7} (started=$started)"
    return 1
}

# Seconds after green CI that auto-merge gets before the script merges the PR itself.
AUTO_MERGE_GRACE=180

# With CI green: returns 0 once the PR is merged, 1 when it stalled for AUTO_MERGE_GRACE seconds after the
# last check finished (the caller merges), 2 when auto-merge is off or the PR is closed.
await_auto_merge() {
    local line state auto green_for empty=0
    while :; do
        line=$(gh pr view "$pr" --json state,autoMergeRequest,statusCheckRollup --jq \
            '"\(.state) \(.autoMergeRequest != null) \(now - ([.statusCheckRollup[].completedAt // empty | select(startswith("0001") | not) | fromdateiso8601] | max // now) | floor)"')
        read -r state auto green_for <<<"$line"
        if [ -z "$state" ] || [ -z "$green_for" ]; then
            # gh or jq hiccup (seen right after checks complete): retry instead of reading it as "auto-merge off",
            # but give up after about five minutes so the caller can fall back to merging.
            empty=$((empty + 1))
            if [ "$empty" -ge 20 ]; then
                echo "#$pr: no answer from gh for the auto-merge check; giving up on it"
                return 2
            fi
            sleep 15
            continue
        fi
        if [ "$state" = MERGED ]; then
            echo "#$pr is merged"
            return 0
        fi
        if [ "$state" != OPEN ] || [ "$auto" != true ]; then
            echo "#$pr: auto-merge is off or the PR is $state; not waiting for it"
            return 2
        fi
        if [ "$green_for" -ge "$AUTO_MERGE_GRACE" ]; then
            echo "#$pr: auto-merge stalled (CI green for ${green_for}s, PR still open); merging it here"
            return 1
        fi
        sleep 30
    done
}

children() {
    local branch=$1
    gh pr list --state open --base "$branch" --json number --jq '.[].number'
}

# The temporary worktree in use, if any. The traps remove it on every exit, Ctrl-C included, unless fix_child kept it
# on purpose for a human to resolve a conflict.
tmp_wt=""
tmp_root=""

drop_worktree() {
    if [ -n "$tmp_wt" ]; then
        git -C "$tmp_root" worktree remove --force "$tmp_wt" 2>/dev/null || rm -rf "$tmp_wt"
        git -C "$tmp_root" worktree prune 2>/dev/null || true
    fi
    tmp_wt=""
}
trap drop_worktree EXIT
trap 'drop_worktree; exit 130' INT TERM

# Adds a detached temporary worktree of repository $1 at ref $2 (path in $tmp_wt; mktemp prefix $3).
add_worktree() {
    tmp_root=$1
    tmp_wt=$(mktemp -d "${TMPDIR:-/tmp}/$3.XXXXXX")
    git -C "$1" worktree add -q --detach "$tmp_wt" "$2"
}

# Refuses PRs from forks: their branch lives in another repository, and pushing a merge to origin/<same name> would
# create or overwrite an unrelated branch here.
not_fork() {
    local number=$1 cross owner
    cross=$(gh pr view "$number" --json isCrossRepository --jq .isCrossRepository)
    if [ "$cross" != false ]; then
        owner=$(gh pr view "$number" --json headRepositoryOwner --jq .headRepositoryOwner.login)
        echo "#$number comes from a fork (${owner:-unknown}); pr.sh does not update or merge fork PRs. Handle it by hand."
        return 1
    fi
}

fix_child() {
    local child=$1 merged_head=$2 base=$3 root branch rc=0
    not_fork "$child" || return 1
    root=$(git rev-parse --show-toplevel)
    branch=$(gh pr view "$child" --json headRefName --jq .headRefName)
    gh pr edit "$child" --base "$base" >/dev/null
    echo "#$child retargeted to $base"
    git -C "$root" fetch -q origin
    add_worktree "$root" "origin/$branch" pr-stack || { echo "#$child: no temporary worktree"; drop_worktree; return 1; }
    (
        cd "$tmp_wt"
        if git merge-base --is-ancestor "$merged_head" HEAD 2>/dev/null \
            && git diff --quiet "$merged_head" "origin/$base" 2>/dev/null; then
            git merge -q -s ours --no-edit -m "Merge origin/$base into $branch" "origin/$base" || exit 1
        elif ! git merge -q --no-edit "origin/$base"; then
            echo "#$child: merging origin/$base conflicts; resolve in $tmp_wt and push without force"
            exit 2
        fi
        git push -q origin "HEAD:$branch" || exit 1
        echo "#$child: origin/$base merged in and pushed"
    ) || rc=$?
    if [ "$rc" = 2 ]; then
        tmp_wt="" # kept for the human resolving the conflict
    fi
    drop_worktree
    [ "$rc" = 0 ]
}

update_branch() {
    # Merges origin/<base> into the PR branch when the branch does not contain it yet (see the header). Returns 0 when
    # the branch is up to date or the merge was pushed, 1 on a fork PR, a conflict or a failed push.
    local state base branch root conflicts
    state=$(gh pr view "$pr" --json state --jq .state)
    # A PR merged meanwhile (auto-merge) goes on to merge_now, which only reports it and fixes stacked PRs.
    [ "$state" = OPEN ] || return 0
    not_fork "$pr" || return 1
    base=$(gh pr view "$pr" --json baseRefName --jq .baseRefName)
    branch=$(gh pr view "$pr" --json headRefName --jq .headRefName)
    root=$(git rev-parse --show-toplevel)
    git -C "$root" fetch -q origin "$base" "$branch" || { echo "#$pr: fetching $base and $branch failed"; return 1; }
    if git -C "$root" merge-base --is-ancestor "origin/$base" "origin/$branch"; then
        echo "#$pr: $branch contains origin/$base"
        return 0
    fi
    echo "#$pr: $branch is behind origin/$base; merging it in"
    add_worktree "$root" "origin/$branch" pr-update || { echo "#$pr: no temporary worktree"; drop_worktree; return 1; }
    if ! git -C "$tmp_wt" merge -q --no-edit -m "Merge origin/$base into $branch" "origin/$base"; then
        conflicts=$(git -C "$tmp_wt" diff --name-only --diff-filter=U | tr '\n' ' ')
        git -C "$tmp_wt" merge --abort 2>/dev/null || true
        drop_worktree
        echo "#$pr: merging origin/$base into $branch conflicts in: ${conflicts:-unknown files}"
        echo "#$pr: not merged. Merge origin/$base into $branch by hand, push without force, then run merge again."
        return 1
    fi
    if ! git -C "$tmp_wt" push -q origin "HEAD:$branch"; then
        drop_worktree
        echo "#$pr: push of the merge to $branch failed (did the branch move?); not merged"
        return 1
    fi
    echo "#$pr: origin/$base merged into $branch and pushed ($(git -C "$tmp_wt" rev-parse --short HEAD)); CI runs again"
    drop_worktree
}

enable_auto() {
    # Squash without --delete-branch: a PR stacked on this branch must keep its base until merge_now retargets it.
    if gh pr merge "$pr" --auto --squash >/dev/null 2>&1; then
        echo "#$pr: auto-merge on"
    else
        echo "#$pr: could not turn on auto-merge; merging here once green"
    fi
}

merge() {
    # Explicit returns: merge-all calls this inside `||`, where `set -e` does not apply.
    update_branch || return 1
    enable_auto
    wait_ci || return 1
    # Returns at once without auto-merge; the PR is then merged below as before.
    await_auto_merge || true
    merge_now || return 1
    sync_main_checkout
}

sync_main_checkout() {
    # Fast-forwards the owner's main checkout (the repository's first worktree) after a merge, so it never has to be
    # pulled by hand (owner decision 2026-10-07). Only when that checkout is on main, has no uncommitted changes to
    # tracked files and is only behind; otherwise it says why and leaves it alone. Never merges, rebases or resets.
    local main_wt dirty ahead
    main_wt=$(git worktree list --porcelain 2>/dev/null | sed -n '1s/^worktree //p')
    [ -n "$main_wt" ] || return 0
    if [ "$(git -C "$main_wt" symbolic-ref --quiet --short HEAD 2>/dev/null)" != "main" ]; then
        echo "main checkout not on main; not pulled"
        return 0
    fi
    dirty=$(git -C "$main_wt" status --porcelain --untracked-files=no | wc -l | tr -d ' ')
    if [ "$dirty" != "0" ]; then
        echo "main checkout has uncommitted changes; not pulled"
        return 0
    fi
    git -C "$main_wt" fetch --quiet origin main 2>/dev/null || { echo "main checkout: fetch failed; not pulled"; return 0; }
    ahead=$(git -C "$main_wt" rev-list --count origin/main..HEAD)
    if [ "$ahead" != "0" ]; then
        echo "main checkout has local commits; not pulled"
        return 0
    fi
    git -C "$main_wt" merge --ff-only --quiet origin/main && echo "main checkout fast-forwarded to $(git -C "$main_wt" rev-parse --short HEAD)"
    return 0
}

wait_and_watch() {
    wait_ci || return 1
    local rc=0
    await_auto_merge || rc=$?
    if [ "$rc" = 1 ]; then
        merge_now
    fi
}

merge_now() {
    local head base branch kids
    head=$(gh pr view "$pr" --json headRefOid --jq .headRefOid)
    base=$(gh pr view "$pr" --json baseRefName --jq .baseRefName)
    branch=$(gh pr view "$pr" --json headRefName --jq .headRefName)
    kids=$(children "$branch")
    if [ "$(gh pr view "$pr" --json state --jq .state)" != "MERGED" ]; then
        gh pr merge "$pr" --squash --match-head-commit "$head" || { echo "#$pr: gh pr merge failed"; return 1; }
    fi
    echo "#$pr merged"
    for kid in $kids; do
        fix_child "$kid" "$head" "$base" || { echo "stopped at #$kid; branch $branch kept"; return 1; }
    done
    git push -q origin --delete "$branch" 2>/dev/null && echo "branch $branch deleted" || true
}

merge_all() {
    # Merges several PRs one after another, in the order given; stops at the first that fails.
    local p
    for p in "$@"; do
        pr=$p
        echo "== #$pr"
        merge || { echo "stopped at #$pr"; return 1; }
    done
}

case "$cmd" in
    status) status ;;
    wait) wait_and_watch ;;
    merge) merge ;;
    auto) update_branch && enable_auto ;;
    merge-all) shift; merge_all "$@" ;;
    *) usage; exit 2 ;;
esac
