#!/usr/bin/env bash
# Clones read-only reference repositories next to the project for Claude Code to search.
# Re-run to update. Never edit anything under this directory.
set -euo pipefail

REF_DIR="${MAXTECH_REF_DIR:-$HOME/mc-reference}"
mkdir -p "$REF_DIR"
cd "$REF_DIR"

clone_or_update() {
  local name="$1" url="$2" branch="${3:-}"
  if [ -d "$name/.git" ]; then
    echo "updating $name"
    git -C "$name" fetch --depth=1 origin ${branch:+"$branch"} >/dev/null 2>&1 || git -C "$name" fetch --depth=1 >/dev/null
    git -C "$name" reset -q --hard FETCH_HEAD
  else
    echo "cloning $name"
    git clone --depth=1 ${branch:+--branch "$branch"} "$url" "$name"
  fi
}

# Toolchain and docs
clone_or_update NeoForge        https://github.com/neoforged/NeoForge.git        26.1.x
clone_or_update Documentation   https://github.com/neoforged/Documentation.git
clone_or_update ModDevGradle    https://github.com/neoforged/ModDevGradle.git

# Reference mods (read for design, don't copy code; check their licenses before reuse)
# All on their 26.1 lines (2026-11-25), for checking how an API is used in practice. Licences: AE2 LGPL
# (code), Mekanism and Refined Storage 2 MIT, EnderIO Unlicense; Create has no 26.x line yet.
clone_or_update EnderIO         https://github.com/Team-EnderIO/EnderIO.git                     26.1
clone_or_update AE2             https://github.com/AppliedEnergistics/Applied-Energistics-2.git 26.1
clone_or_update Mekanism        https://github.com/mekanism/Mekanism.git                        26.1
clone_or_update RefinedStorage2 https://github.com/refinedmods/refinedstorage2.git              develop

# Compat APIs
clone_or_update JEI             https://github.com/mezz/JustEnoughItems.git
clone_or_update KubeJS          https://github.com/KubeJS-Mods/KubeJS.git
clone_or_update Jade            https://github.com/Snownee/Jade.git

echo
echo "Reference repos are in $REF_DIR"
echo "Branches were chosen for 26.1 where a matching branch exists; for the others, check out the branch closest to 26.1 manually if the default branch is far off."
