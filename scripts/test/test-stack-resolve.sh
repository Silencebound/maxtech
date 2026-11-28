#!/usr/bin/env bash
# Tests scripts/stack-resolve.sh on a scripted repository: PR A, PR B stacked on it, A squash-merged into main.
#
#   scripts/test/test-stack-resolve.sh      exit 0 when every case passes
#
# STACK_RESOLVE=<path> runs the cases against another copy of the resolver (used to check that the cases catch a
# resolver with a check removed).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
resolver=${STACK_RESOLVE:-$here/../stack-resolve.sh}
# shellcheck source=SCRIPTDIR/../stack-resolve.sh
. "$resolver"

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test

tmp=$(mktemp -d "${TMPDIR:-/tmp}/stack-resolve-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
fails=0

fail() {
    echo "FAIL: $1"
    fails=$((fails + 1))
}

commit() { # commit <message> <file> <content> [<file> <content>]...
    local msg=$1
    shift
    while [ $# -ge 2 ]; do
        mkdir -p "$(dirname "$1")"
        printf '%s\n' "$2" >"$1"
        git add -- "$1"
        shift 2
    done
    git commit -q -m "$msg"
}

# Builds a fresh repository in $tmp/$1: main with shared.txt; A adds a.txt and edits shared.txt in two commits; B,
# stacked on A, edits both again and adds b.txt; A is squash-merged into main, followed by an unrelated main commit.
# Sets a_head and squash.
setup() {
    repo=$tmp/$1
    git init -q -b main "$repo"
    cd "$repo"
    commit init shared.txt "line 1"
    git switch -q -c A
    commit "A1" a.txt "a from A"
    commit "A2" shared.txt "line 1 changed by A"
    a_head=$(git rev-parse HEAD)
    git switch -q -c B
    commit "B1" a.txt "a from A, extended by B" shared.txt "line 1 changed by A and B" b.txt "b"
    git switch -q main
    git merge -q --squash A >/dev/null 2>&1
    git commit -q -m "A (#1)"
    squash=$(git rev-parse HEAD)
    commit "unrelated" other.txt "other"
}

# Merges main into branch $1 in the repository and expects a conflict.
start_merge() {
    git switch -q "$1"
    if git merge -q --no-edit main >/dev/null 2>&1; then
        fail "$2: merging main into $1 did not conflict (test setup broken)"
        return 1
    fi
}

# Runs the resolver; sets $out (its output) and returns its exit code.
run_resolver() {
    local rc=0
    out=$(stack_resolve "$@" 2>&1) || rc=$?
    return "$rc"
}

# expect_out <case> <text>: the resolver's output names the reason.
expect_out() {
    case "$out" in
        *"$2"*) ;;
        *) fail "$1: output lacks \"$2\": $out" ;;
    esac
}

# 1. The stacked case: B's version wins, main's unrelated commit comes along.
setup stacked
if start_merge B stacked; then
    if stack_resolve "$repo" main B "$a_head" "$squash" >/dev/null; then
        [ "$(git show HEAD:a.txt)" = "a from A, extended by B" ] || fail "stacked: a.txt is not B's version"
        [ "$(git show HEAD:shared.txt)" = "line 1 changed by A and B" ] || fail "stacked: shared.txt is not B's version"
        git cat-file -e HEAD:other.txt 2>/dev/null || fail "stacked: main's other.txt is missing"
        [ "$(git rev-list --parents -n 1 HEAD | wc -w | tr -d ' ')" = 3 ] || fail "stacked: result is not a merge commit"
        [ "$(git log -1 --format=%s)" = "Merge main into B" ] || fail "stacked: unexpected commit message"
        git merge-base --is-ancestor main HEAD || fail "stacked: B does not contain main afterwards"
    else
        fail "stacked: resolver refused the stacked case"
    fi
fi

# 2. Dry run: says it would resolve, changes nothing.
setup dry
if start_merge B dry; then
    before=$(git rev-parse HEAD)
    stack_resolve "$repo" main B "$a_head" "$squash" 1 >/dev/null || fail "dry: dry run refused the stacked case"
    [ "$(git rev-parse HEAD)" = "$before" ] || fail "dry: dry run committed"
    [ -n "$(git diff --name-only --diff-filter=U)" ] || fail "dry: dry run resolved the conflict"
fi

# 3. Not stacked: C started from main before A and adds its own a.txt. Only A's squash touched a.txt on main, but C
#    does not contain A, so taking C's side would drop A's work. Only the ancestor check refuses this.
setup unstacked
git switch -q -c C "$(git rev-list --max-parents=0 HEAD)"
commit "C1" a.txt "a from C"
if start_merge C unstacked; then
    if run_resolver "$repo" main C "$a_head" "$squash"; then
        fail "unstacked: resolver took C's side although C does not contain A's head"
    fi
    expect_out unstacked "contains none of the parent's own commits"
    expect_out unstacked "a.txt"
    [ -n "$(git diff --name-only --diff-filter=U)" ] || fail "unstacked: refusal changed the conflict state"
fi

# 4. Main changed a conflicted file after A's squash: B's version would drop that change.
setup later
git switch -q main
commit "later fix" a.txt "a from A, fixed on main"
if start_merge B later; then
    if run_resolver "$repo" main B "$a_head" "$squash"; then
        fail "later: resolver took B's side although main changed a.txt after the squash"
    fi
    expect_out later "a.txt (also changed in $(git rev-parse --short=7 main))"
fi

# 5. A wrong squash id (not on main) is refused.
setup wrongsquash
if start_merge B wrongsquash; then
    if run_resolver "$repo" main B "$a_head" "$a_head"; then
        fail "wrongsquash: resolver accepted a squash commit that is not on main"
    fi
    expect_out wrongsquash "is not on main"
    expect_out wrongsquash "a.txt"
fi

# Like setup, but A gets one more commit (A3, changing file $2) after B branched off it, before the squash.
setup_late_parent() {
    repo=$tmp/$1
    git init -q -b main "$repo"
    cd "$repo"
    commit init shared.txt "line 1"
    git switch -q -c A
    commit "A1" a.txt "a from A"
    commit "A2" shared.txt "line 1 changed by A"
    git switch -q -c B
    commit "B1" a.txt "a from A, extended by B" shared.txt "line 1 changed by A and B"
    git switch -q A
    commit "A3" "$2" "late change by A"
    a_head=$(git rev-parse HEAD)
    git switch -q main
    git merge -q --squash A >/dev/null 2>&1
    git commit -q -m "A (#1)"
    squash=$(git rev-parse HEAD)
}

# 6. A's later commit touched only another file: B's version of the conflicted files is still complete.
setup_late_parent late-other c.txt
if start_merge B late-other; then
    if stack_resolve "$repo" main B "$a_head" "$squash" >/dev/null; then
        [ "$(git show HEAD:c.txt)" = "late change by A" ] || fail "late-other: A's late c.txt is missing"
        [ "$(git show HEAD:a.txt)" = "a from A, extended by B" ] || fail "late-other: a.txt is not B's version"
    else
        fail "late-other: resolver refused although A's late commit touched no conflicted file"
    fi
fi

# 7. A's later commit touched a conflicted file: B's version lacks it.
setup_late_parent late-same a.txt
if start_merge B late-same; then
    if run_resolver "$repo" main B "$a_head" "$squash"; then
        fail "late-same: resolver took B's side although A changed a.txt after B merged it"
    fi
    expect_out late-same "the parent changed these files after"
    expect_out late-same "a.txt (changed in $(git rev-parse --short=7 "$a_head"))"
fi

# 8. A main commit before A's squash, not in B, changed the line B conflicts on (the #72 shape): the squash is not the
#    only commit that touched the file, so B's version would drop that change.
repo=$tmp/before
git init -q -b main "$repo"
cd "$repo"
commit init L "$(printf 'a\n\n\n\n\n\n\n\n\nz')"
git switch -q -c A
commit A1 L "$(printf 'a\nA\n\n\n\n\n\n\n\nz')"
a_head=$(git rev-parse HEAD)
git switch -q -c B
commit B1 L "$(printf 'a\nA\nB\n\n\n\n\n\n\nz')"
git switch -q main
commit M1 L "$(printf 'a\n\n\n\n\n\n\n\nM\nz')"
m1=$(git rev-parse --short=7 HEAD)
git merge -q --squash A >/dev/null 2>&1
git commit -q -m "A (#1)"
squash=$(git rev-parse HEAD)
if start_merge B before; then
    if run_resolver "$repo" main B "$a_head" "$squash"; then
        fail "before: resolver took B's side although a main commit before the squash changed L"
    fi
    expect_out before "L (also changed in $m1)"
    [ -n "$(git diff --name-only --diff-filter=U)" ] || fail "before: refusal changed the conflict state"
fi

# 9. B deletes a file A changed (modify/delete). With the same checks passing, the deletion is B's own work: the file
#    goes and the output says so.
repo=$tmp/delete
git init -q -b main "$repo"
cd "$repo"
commit init shared.txt "line 1" keep.txt "keep"
git switch -q -c A
commit A1 shared.txt "line 1 changed by A"
a_head=$(git rev-parse HEAD)
git switch -q -c B
git rm -q shared.txt
git commit -q -m "B1 deletes shared.txt"
git switch -q main
git merge -q --squash A >/dev/null 2>&1
git commit -q -m "A (#1)"
squash=$(git rev-parse HEAD)
if start_merge B delete; then
    if run_resolver "$repo" main B "$a_head" "$squash"; then
        if git cat-file -e HEAD:shared.txt 2>/dev/null; then fail "delete: shared.txt survived B's deletion"; fi
        [ -z "$(git diff --name-only --diff-filter=U)" ] || fail "delete: conflicts left"
        expect_out delete "deletes"
        expect_out delete "shared.txt"
    else
        fail "delete: resolver refused although the checks pass: $out"
    fi
fi

# 10. Same, but a main commit before the squash changed the file B deleted: nothing is decided, the file is listed.
repo=$tmp/delete-before
git init -q -b main "$repo"
cd "$repo"
commit init shared.txt "line 1" keep.txt "keep"
git switch -q -c A
commit A1 shared.txt "line 1 changed by A"
a_head=$(git rev-parse HEAD)
git switch -q -c B
git rm -q shared.txt
git commit -q -m "B1 deletes shared.txt"
git switch -q main
commit M1 shared.txt "line 1 changed on main"
git merge -q --squash A >/dev/null 2>&1 || true
printf '%s\n' "line 1 changed on main and by A" >shared.txt
git add -A
git commit -q -m "A (#1)"
squash=$(git rev-parse HEAD)
if start_merge B delete-before; then
    if run_resolver "$repo" main B "$a_head" "$squash"; then
        fail "delete-before: resolver deleted a file main changed before the squash"
    fi
    expect_out delete-before "shared.txt (also changed in"
    [ -f shared.txt ] || fail "delete-before: refusal removed the file"
fi

if [ "$fails" = 0 ]; then
    echo "stack-resolve: all cases pass"
else
    echo "stack-resolve: $fails failure(s)"
    exit 1
fi
