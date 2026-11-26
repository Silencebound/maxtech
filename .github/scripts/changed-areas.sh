#!/usr/bin/env bash
# Classifies the files a CI run has to cover and prints flags as key=value lines (also appended to $GITHUB_OUTPUT).
# Usage: changed-areas.sh [base [head]]. Defaults: base HEAD^1, head HEAD. On a pull_request the checkout is the merge
# of the PR into its base and on a push to main it is the squash commit, so HEAD^1 is the base in both cases.
# Flags: docs_only, lib, storage, all, ae2, rs. `all` implies both module flags. `./gradlew build` still compiles and unit-tests it on every run.
# ae2 and rs flag the bridge GameTests against the real mods (-PwithAe2, -PwithRs): every nightly and manual run, and on
# a PR only when it touches that compat package, the shared bridge code, the bridge GameTests or Storage's NeoForge build
# script (which declares the mods). A lib or build-logic change alone does not run them; the nightly does.
set -euo pipefail

base="${1:-HEAD^1}"
head="${2:-HEAD}"
event="${GITHUB_EVENT_NAME:-}"

docs_only=false lib=false storage=false all=false ae2=false rs=false

case "$event" in
  workflow_dispatch | schedule) all=true ae2=true rs=true ;;
  *)
    if ! git rev-parse -q --verify "$base^{commit}" > /dev/null; then
      # Shallow checkout: fetch the one parent we need.
      git fetch -q --no-tags --depth=2 origin "$(git rev-parse "$head")"
    fi
    other=false
    if git rev-parse -q --verify "$base^{commit}" > /dev/null; then
      changed=$(git diff --name-only "$base" "$head")
    else
      # The repository's first commit has no parent: everything in it is new.
      changed=""
      other=true
    fi
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      case "$path" in
        # Module docs that no build, test or datagen step reads. Guide pages (src/main/resources) stay game content.
        lib/CLAUDE.md | lib/CHANGELOG.md | storage/CLAUDE.md | storage/PRD.md | storage/CHANGELOG.md | storage/docs/* \
          | storage/neoforge/src/textures/README.md | .gitignore) ;;
        lib/* | build-logic/* | gradle/* | *.gradle.kts | gradle.properties | settings.gradle.kts | .github/*) all=true ;;
        storage/*) storage=true ;;
        *.md | docs/* | .claude/* | scripts/* | LICENSE* | README*) ;;
        *) other=true ;; # unknown path (gradlew, .editorconfig, ...): be safe and run everything
      esac
      case "$path" in
        storage/*/compat/ae2/* | storage/*/gametest/Ae2*GameTests.java) ae2=true ;;
        storage/*/compat/refinedstorage/* | storage/*/gametest/Rs*GameTests.java) rs=true ;;
        storage/*/storage/bridge/* | storage/*/gametest/BridgeGameTests.java | storage/neoforge/build.gradle.kts)
          ae2=true rs=true ;;
      esac
    done <<< "$changed"
    if $other; then all=true; fi
    if ! $all && ! $storage; then docs_only=true; fi
    ;;
esac

if $all; then
  lib=true storage=true docs_only=false
fi

out="docs_only=$docs_only
lib=$lib
storage=$storage
all=$all
ae2=$ae2
rs=$rs"
echo "$out"
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$out" >> "$GITHUB_OUTPUT"; fi
