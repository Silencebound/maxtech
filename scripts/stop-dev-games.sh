#!/usr/bin/env bash
# Stops the dev game processes (client, server, GameTest server) started from THIS checkout, nothing else.
# The mod folders in a dev game's command line (-Dfml.modFolders=<modid>%%<dir>) contain the checkout path, so matching
# "<checkout>/<project>/neoforge/build/" skips other worktrees' games (their paths continue with .claude/worktrees/)
# and CI's (the runner's _work folder). Run it from the worktree root.
# Never `pkill -f devlaunch.Main` or `./gradlew --stop` instead: both also hit CI jobs and other worktrees' builds.
#
# Usage: scripts/stop-dev-games.sh        (DRY_RUN=1 lists the matches without stopping them)
set -euo pipefail

root="$(git rev-parse --show-toplevel)"

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        # Command lines hold the Windows form of the path, with either kind of slash.
        MT_ROOT="$(cygpath -w "$root")" MT_DRY="${DRY_RUN:-}" powershell.exe -NoProfile -Command '
            $root = [regex]::Escape($env:MT_ROOT).Replace("\\", "[\\/]")
            $pattern = "%%" + $root + "[\\/][a-z]+[\\/]neoforge[\\/]build[\\/]"
            $found = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match $pattern -and $_.ProcessId -ne $PID }
            foreach ($p in $found) {
                if ($env:MT_DRY -eq "1") { "would stop $($p.ProcessId)" }
                else { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; "stop  $($p.ProcessId)" }
            }
            if (-not $found) { "no dev game processes for this checkout" }
        '
        ;;
    *)
        pattern="%%${root}/[a-z]+/neoforge/build/"
        if [ "${DRY_RUN:-}" = 1 ]; then
            pgrep -fl "$pattern" || echo "no dev game processes for this checkout"
        elif pkill -f "$pattern"; then
            echo "stopped dev game processes for this checkout"
        else
            echo "no dev game processes for this checkout"
        fi
        ;;
esac
