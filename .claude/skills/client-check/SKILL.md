---
name: client-check
description: Checking a change in a running dev client - starting runClient, the showcase stations and screenshot scenes, taking and reading screenshots, and turning facts marked "not yet seen in a running client" into checked ones. Use for any change to models, rendering, screens, widgets, overlays or sounds, before a release, and when the owner asks to see something in game.
---

# Checking things in a running client

GameTests run a server only, so models, block entity renderers, screens, widgets, overlays and the guide are never
drawn in CI. Facts about them in `neoforge-26-1/SKILL.md` and `storage-facts.md` say "not yet seen in a running
client" until someone looks: `rg -n "not yet seen in a|not run in a client" .claude/skills` lists them.

## Starting the client

```
./gradlew :storage:neoforge:runClient            # add -PwithAe2=true etc. for optional mods (CLAUDE.md)
```

Run it in the background; the first start takes a few minutes. The game window needs a person (or computer use, if
the owner turned it on for the session) to create a world and type commands. Use a creative world; the screenshot
world and its seed are in `docs/release/screenshots.md`.

## Building something to look at (dev runs only)

- `/maxtech_storage showcase list` lists the stations; `showcase <station> here` builds one around you, `showcase`
  builds them all next to you, `showcase clear` removes every build in the dimension, `showcase reset-steps` resets
  the step walls.
- `/maxtech_storage scene place|save|view <scene>` places, saves and frames the five release scenes.
- `/maxtech_storage shoot <station>` or `shoot all` frames each station and saves
  `runs/storage-neoforge/client/screenshots/maxtech_<station>.png`; `shoot scenes` does the same for the release scenes
  (`maxtech_scene_<scene>.png`). It sets spectator, noon and clear weather and restores them afterwards.
- A station for a new feature goes in the `ShowcaseStation` enum (its shot in `ShowcaseShots`) with the feature, so
  the next check needs one command.

## Reading the result

- Read the screenshots with the Read tool (it shows images) and compare with what the change should draw: textures
  and models, lighting and layers, text fitting its box, nothing drawn through walls that shouldn't be.
- Screens and widgets need the menu open; take those with F2 in game (they land in the same folder) or ask the owner.
- Check the log too: `runs/storage-neoforge/client/logs/latest.log` for missing textures or models ("Missing", "Unable
  to load model"), lang keys shown raw, and exceptions from our packages.
- Screenshots stay under `runs/` (ignored). Only the release shots in `docs/release/screenshots.md` go anywhere, and
  the owner takes and publishes those; check every frame for private details first.

## After checking

- Fact held: change its marker to say it was seen and when ("seen in a client 2026-11-26"). Fact wrong: fix it, fix
  the code, and add a test where one can catch it (model and lang checks run in datagen and GameTests).
- Say in the PR what you checked in the client and what you couldn't (a screen nobody opened is still unchecked).
