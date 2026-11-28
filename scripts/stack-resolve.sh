#!/usr/bin/env bash
# Resolves the conflict a stacked PR gets after the PR below it was squash-merged (sourced by pr.sh).
#
#   scripts/stack-resolve.sh <dir> <base-ref> <branch> <parent-head> <parent-squash> [--dry-run]
#
# <dir> is a worktree in the middle of a conflicted `git merge <base-ref>`. PR B was stacked on PR A; A's squash commit
# on the base repeats A's changes under a new id, so B conflicts in files A touched (add/add or content). B's side is
# then a superset of the base's side, and the conflict is resolved with B's version of every conflicted file, when:
#
#   1. B contains A's work in those files: A's final head (<parent-head>) is an ancestor of B's HEAD, or, when A got
#      more commits after B last merged it, B contains one of A's own commits and none of A's later commits touches a
#      conflicted file, and
#   2. the only commit on <base-ref> that B lacks and that touches a conflicted file is A's squash (<parent-squash>),
#      so taking B's version drops nothing from the base.
#
# It then checks that no conflict is left and that no resolved file holds conflict markers, and commits
# "Merge <base-ref> into <branch>". It never pushes. Exit 0: resolved and committed (or, with --dry-run, would be;
# nothing is changed then). Exit 1: a check failed; the reason and the files are printed and the merge is left in
# progress, untouched, for a human or for the caller to abort.

# stack_resolve <dir> <base-ref> <branch> <parent-head> <parent-squash> [dry-run: 1]
# shellcheck disable=SC2086 # file lists are split on newlines on purpose (IFS below)
stack_resolve() {
    local dir=$1 base_ref=$2 branch=$3 parent_head=$4 squash=$5 dry=${6:-0}
    local files file others squash_full seen bad=""
    # One path per line; repository paths have no newlines.
    local IFS=$'\n'
    files=$(git -C "$dir" diff --name-only --diff-filter=U)
    if [ -z "$files" ]; then
        echo "stack-resolve: no conflicted files in $dir"
        return 1
    fi
    if [ -z "$parent_head" ] || ! git -C "$dir" cat-file -e "$parent_head^{commit}" 2>/dev/null; then
        echo "stack-resolve: the parent's head ${parent_head:0:7} is not in this repository; not resolving:"
        printf '  %s\n' $files
        return 1
    fi
    # The parent's newest commit that the branch has; the parent's final head when the branch is up to date with it.
    seen=$(git -C "$dir" merge-base "$parent_head" HEAD || true)
    if [ -z "$seen" ] || git -C "$dir" merge-base --is-ancestor "$seen" "$base_ref"; then
        echo "stack-resolve: $branch contains none of the parent's own commits (head ${parent_head:0:7}); not resolving:"
        printf '  %s\n' $files
        return 1
    fi
    for file in $files; do
        others=$(git -C "$dir" rev-list "$parent_head" --not "$seen" "$base_ref" -- "$file")
        if [ -n "$others" ]; then
            bad="$bad$file (changed in ${others:0:7})"$'\n'
        fi
    done
    if [ -n "$bad" ]; then
        echo "stack-resolve: the parent changed these files after ${seen:0:7}, the last of its commits $branch has; not resolving:"
        printf '  %s\n' $bad
        return 1
    fi
    if [ -z "$squash" ] || ! git -C "$dir" merge-base --is-ancestor "$squash" "$base_ref" 2>/dev/null; then
        echo "stack-resolve: the parent's squash ${squash:0:7} is not on $base_ref; not resolving:"
        printf '  %s\n' $files
        return 1
    fi
    squash_full=$(git -C "$dir" rev-parse "$squash^{commit}")
    for file in $files; do
        others=$(git -C "$dir" rev-list --full-history "$base_ref" --not HEAD -- "$file" | grep -vx "$squash_full" || true)
        if [ -n "$others" ]; then
            bad="$bad$file (also changed in $(printf '%s' "$others" | head -n 1 | cut -c1-7))"$'\n'
        fi
    done
    if [ -n "$bad" ]; then
        echo "stack-resolve: $base_ref changed these files after or beside the parent's squash; not resolving:"
        printf '  %s\n' $bad
        return 1
    fi
    if [ "$dry" = 1 ]; then
        echo "stack-resolve (dry run): would keep $branch's version of:"
        printf '  %s\n' $files
        return 0
    fi
    for file in $files; do
        if git -C "$dir" cat-file -e ":2:$file" 2>/dev/null; then
            git -C "$dir" checkout -q --ours -- "$file"
            git -C "$dir" add -- "$file"
        else
            # Deleted on B's side: B's version is no file.
            git -C "$dir" rm -q --cached -- "$file" >/dev/null
            rm -f "$dir/$file"
        fi
    done
    if [ -n "$(git -C "$dir" diff --name-only --diff-filter=U)" ]; then
        echo "stack-resolve: conflicts left after resolving; not committing"
        return 1
    fi
    for file in $files; do
        if [ -f "$dir/$file" ] && grep -qE '^(<<<<<<< |>>>>>>> |=======$)' "$dir/$file"; then
            echo "stack-resolve: conflict markers in $file; not committing"
            return 1
        fi
    done
    git -C "$dir" commit -q --no-edit -m "Merge $base_ref into $branch" || return 1
    echo "stack-resolve: kept $branch's version of:"
    printf '  %s\n' $files
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    [ $# -ge 5 ] || { sed -n '2,/^$/p' "$0"; exit 2; }
    dry=0
    [ "${6:-}" = --dry-run ] && dry=1
    stack_resolve "$1" "$2" "$3" "$4" "$5" "$dry"
fi
