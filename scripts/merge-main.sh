#!/usr/bin/env bash
# Merges origin/main into the current branch of a worktree and resolves the conflicts that have a mechanical answer.
#
#   scripts/merge-main.sh [branch]   branch: switch to it first (default: the current branch)
#
# Append-only files (.claude/skills/*/SKILL.md, .claude/skills/*/*-facts.md, docs/IMPLEMENTER_CHECKLIST.md) keep both sides,
# main's lines first, then ours. Generated files take main's side and are then regenerated: each mod's datagen cache is
# cleared and runData runs for every mod in settings.gradle.kts. Any other conflict stops the script before it changes
# anything and lists the files. Before committing it refuses if conflict markers are left anywhere in the repo.
# Never pushes.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"

if [ -n "${1:-}" ] && [ "$(git symbolic-ref --short HEAD)" != "$1" ]; then
    git switch -q "$1"
fi
branch=$(git symbolic-ref --short HEAD)

if [ -e "$(git rev-parse --git-path MERGE_HEAD)" ]; then
    echo "a merge is already in progress; finish it or run 'git merge --abort' first"
    exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "uncommitted changes in $root; commit them first"
    exit 1
fi

git fetch -q origin
if git merge-base --is-ancestor origin/main HEAD; then
    echo "$branch already contains origin/main"
    exit 0
fi

# --no-ff so a clean merge also stops before committing and goes through the marker check below.
merged_clean=1
git merge -q --no-ff --no-commit --no-edit origin/main || merged_clean=0

append_only=()
generated=()
other=()
if [ "$merged_clean" = 0 ]; then
    while IFS= read -r file; do
        [ -n "$file" ] || continue
        case "$file" in
            .claude/skills/*/SKILL.md | .claude/skills/*/*-facts.md | docs/IMPLEMENTER_CHECKLIST.md) append_only+=("$file") ;;
            */common/src/generated/*) generated+=("$file") ;;
            *) other+=("$file") ;;
        esac
    done < <(git diff --name-only --diff-filter=U)

    # Keeping both sides needs both sides; a file deleted on one of them is a decision for a human.
    for file in ${append_only[@]+"${append_only[@]}"}; do
        if ! git cat-file -e ":2:$file" 2>/dev/null || ! git cat-file -e ":3:$file" 2>/dev/null; then
            other+=("$file (deleted on one side)")
        fi
    done

    if [ ${#other[@]} -gt 0 ]; then
        echo "conflicts that need a human; nothing was resolved:"
        printf '  %s\n' "${other[@]}"
        [ ${#append_only[@]} -gt 0 ] && printf '  (append-only, would keep both sides) %s\n' "${append_only[@]}"
        [ ${#generated[@]} -gt 0 ] && printf "  (generated, would take main's side) %s\n" "${generated[@]}"
        echo "resolve them and commit, or back out with 'git merge --abort'"
        exit 1
    fi
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/merge-main.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

for file in ${append_only[@]+"${append_only[@]}"}; do
    # Stage 1 is the merge base (missing when both sides added the file), 2 is ours, 3 is origin/main.
    git show ":1:$file" >"$tmp/base" 2>/dev/null || : >"$tmp/base"
    git show ":2:$file" >"$tmp/ours"
    git show ":3:$file" >"$tmp/main"
    # --union writes both sides of every conflicting hunk, the first file's side first.
    git merge-file -q -p --union -L main -L base -L ours "$tmp/main" "$tmp/base" "$tmp/ours" >"$file" || true
    git add -- "$file"
    echo "kept both sides: $file"
done

for file in ${generated[@]+"${generated[@]}"}; do
    if git cat-file -e ":3:$file" 2>/dev/null; then
        git checkout -q --theirs -- "$file"
        git add -- "$file"
    else
        git rm -q -- "$file"
    fi
done

if [ ${#generated[@]} -gt 0 ]; then
    echo "generated files conflicted (${#generated[@]}); took main's side, regenerating"
    # Mods in settings order, so core runs before the addons that depend on it.
    mods=$(sed -n 's/^include(":\([a-z_]*\):common".*/\1/p' settings.gradle.kts)
    for mod in $mods; do
        rm -rf "$mod/common/src/generated/resources/.cache"
        ./gradlew -q ":$mod:neoforge:runData"
        mkdir -p "$mod/common/src/generated"
        git add -A -- "$mod/common/src/generated"
    done
fi

# Same check as CI, over the whole repository: a marker anywhere means a resolution went wrong.
if git grep -nE '^(<{7}|>{7})( |$)|^={7}$' -- ':!*.nbt' ':!*.png'; then
    echo "conflict markers found (above); not committing. Fix them, or back out with 'git merge --abort'"
    exit 1
fi

git commit -q --no-edit
echo "merged origin/main into $branch ($(git rev-parse --short HEAD))"
echo "next:"
echo "  ./gradlew build, plus the GameTests of every mod the merge touched"
echo "  git push origin HEAD   (never --force)"
