#!/usr/bin/env bash
# Classifies the files a CI run has to cover and prints flags as key=value lines (also appended to $GITHUB_OUTPUT).
# Usage: changed-areas.sh [base [head]]. Defaults: base HEAD^1, head HEAD. On a pull_request the checkout is the merge
# of the PR into its base and on a push to main it is the squash commit, so HEAD^1 is the base in both cases.
# Flags: docs_only, port, lib, lib_client, storage, farms, all, ae2, rs. `all` implies every module flag.
# port: the diff touches port/** (the 1.21.1 backport tree). It needs no mod build, so port/** counts like docs; the
# workflow runs port/convert.py --check for it. Any other unrecognised path still sets `all`.
# lib_client: the Lib change touches only client code (render, screens, models), its hand-made assets or its unit
# tests, and no non-client source imports a changed client class. GameTests run on a headless server and never load
# that code, so it gets the build (unit tests) and Lib's datagen check but no GameTests. `./gradlew build` still compiles and unit-tests it on every run.
# ae2 and rs flag the bridge GameTests against the real mods (-PwithAe2, -PwithRs): every nightly and manual run, and on
# a PR only when it touches that compat package, the shared bridge code, the bridge GameTests or Storage's NeoForge build
# script (which declares the mods). A lib or build-logic change alone does not run them; the nightly does.
set -euo pipefail

base="${1:-HEAD^1}"
head="${2:-HEAD}"
event="${GITHUB_EVENT_NAME:-}"

docs_only=false port=false lib=false lib_client=false storage=false farms=false all=false ae2=false rs=false

case "$event" in
  workflow_dispatch | schedule) all=true ae2=true rs=true ;;
  *)
    if ! git rev-parse -q --verify "$base^{commit}" > /dev/null; then
      # Shallow checkout: fetch the one parent we need.
      git fetch -q --no-tags --depth=2 origin "$(git rev-parse "$head")"
    fi
    other=false
    client_classes=()
    if git rev-parse -q --verify "$base^{commit}" > /dev/null; then
      changed=$(git diff --name-only --no-renames "$base" "$head")
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
          | storage/neoforge/src/textures/README.md | farms/CLAUDE.md | farms/CHANGELOG.md | .gitignore) ;;
        lib/common/src/main/java/dev/silencebound/maxtech/lib/client/* \
          | lib/common/src/api/java/dev/silencebound/maxtech/lib/api/client/* \
          | lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/client/* \
          | lib/common/src/main/resources/assets/* | lib/common/src/test/*)
          lib_client=true
          case "$path" in *.java) client_classes+=("$path") ;; esac ;;
        lib/* | build-logic/* | gradle/* | *.gradle.kts | gradle.properties | settings.gradle.kts | .github/*) all=true ;;
        storage/*) storage=true ;;
        farms/*) farms=true ;;
        port/*) port=true ;;
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
    # A client class imported by server-side code (common init, any mod's non-client or GameTest code, other mods'
    # datagen) is not client-only: a change there can break GameTests or their data. Client packages and unit tests of
    # any mod don't count; Lib's own datagen doesn't either, because its check still runs.
    for class_path in ${client_classes[@]+"${client_classes[@]}"}; do
      class=$(sed -E 's#^lib/[a-z]+/src/[a-z]+/java/##; s#\.java$##; s#/#.#g' <<< "$class_path")
      if git grep -lqE "^import ${class//./\\.};" -- 'lib/*.java' 'storage/*.java' 'farms/*.java' \
        ':(exclude,glob)**/client/**' ':(exclude,glob)**/src/test/**' \
        ':(exclude)lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/datagen/'; then
        all=true
      fi
    done
    if ! $all && ! $storage && ! $farms && ! $lib_client; then docs_only=true; fi
    ;;
esac

if $all; then
  lib=true storage=true farms=true docs_only=false
fi
if $lib; then lib_client=false; fi

out="docs_only=$docs_only
port=$port
lib=$lib
lib_client=$lib_client
storage=$storage
farms=$farms
all=$all
ae2=$ae2
rs=$rs"
echo "$out"
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$out" >> "$GITHUB_OUTPUT"; fi
