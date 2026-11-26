# Screenshot shot list

For the mod pages. The owner takes these in the dev client. The scene shots show five showcase scenes, base corners
built for the pictures (no test pads, signs or step walls); the GUI shots stay manual. Use the default resource pack,
GUI scale 2 or 3 for GUI shots, and a window of 1920x1080 or larger.

## World

A creative singleplayer world, type Default, seed `maxtech` (typed as text). The scenes level their own ground, so any
stretch of plains or light forest works; the seed only keeps the backgrounds the same between runs. Pick a start spot
with open land to the east (the five sites reach about 275 blocks east of it), stand on it and note its coordinates here:

- Start spot: not chosen yet (x, y, z).

## Scenes

Each scene is a 13 x 13 room corner with walls on its north and west sides, built at a fixed site for the start spot:
scene `i` has its north-west corner 2 blocks south-east of the spot and `64 * i` blocks east of it, on the most common
surface height there. The ground around it is levelled, and a creative energy source hides in the floor under each
controller.

| # | Scene | Should be visible |
|---|---|---|
| 0 | `hall` | Two-block controller with the animated face, energy cells, a wall of 21 drives with lit bays, the tier-4 cable along the wall, two terminals on the west counter. Main page image and thumbnail. |
| 1 | `crafting` | A 3 x 3 tier-3 crafting CPU busy on a job (ingots and sticks), pattern providers behind two furnaces and a crafting assembler, pattern terminal and request board on the counter. |
| 2 | `processing` | Lithograph, fuel generator, fabricator, assembler and a second generator with full buffers, export ports feeding the lithograph and fabricator and two chests. |
| 3 | `monitoring` | A display wall graphing cobblestone (the count moves for half a minute), a throughput graph, the Logic Controller with a lever on it, two logic-driven lamps. |
| 4 | `cables` | Plain, covered, smart and fiber cables stepping down from tier 4 to tier 1 across two walls into a drive, a terminal, a display and a drive. The retired glass look is left out; it draws like plain. |

## Commands (dev runs only)

- `/maxtech_storage scene place <scene> [<start>]` builds a scene at its site for the start spot (default: where you
  stand): from `docs/release/scenes/<scene>.nbt` if that file exists, otherwise from the code draft in
  `ShowcaseScenes`. Then it adds what a structure file cannot hold: the network's stock, owners, full machines, the
  crafting job and the moving cobblestone count. Chat says where the scene's corner is.
- `/maxtech_storage scene save <scene> [<corner>]` saves the scene's box (13 x 9 x 13 from two layers under the floor)
  to `docs/release/scenes/<scene>.nbt` in the checkout, as a vanilla structure file. Without a corner it takes the last
  placement since the server started.
- `/maxtech_storage scene view <scene>` puts you at the scene's camera pose.
- `/maxtech_storage shoot scenes` (or `shoot scene <scene>`) places each scene at its site for where you stand, moves the
  camera to its pose, waits for the scene to warm up and render, and saves
  `runs/storage-neoforge/client/screenshots/maxtech_scene_<scene>.png` at the window's size. It switches to spectator, sets noon and clear weather, and gives back the HUD, camera, game
  mode and your place at the end. The camera poses and warm-up times are in `ShowcaseScene`; tune them there.

To fix a scene by hand: stand on the start spot, `scene place <scene>`, change it in-game, `scene save <scene>`, commit
the file. The next `scene place` and `shoot scenes` use it. Fill drives and machines as you like; their contents are
not kept (the setup pass stocks them again), but devices, settings, patterns and graphs are.

The old station shots (`/maxtech_storage shoot all` or `shoot <station>`, files `maxtech_<station>.png`) still work
for checking the showcase stations; they are not for the mod pages.

## GUI shots (hold nothing, close the inventory, crop to the screen)

1. Terminal (`hall`) with a full, varied item list and the search field filled.
2. Controller screen, Network tab (channels per root link) and Cards tab.
3. Controller History tab with graphs and ledger (research nodes done, or creative).
4. Crafting request with the job plan tree showing a shortfall in red (`crafting` scene).
5. Pattern terminal with a recipe encoded.
6. Cell workbench with a cell being built.
7. Logic editor with one of the ready graphs (`monitoring` scene, or the `logic` showcase station).
8. Research screen with a few completed nodes.
9. Guide book open on a page with a scene (for the guide mention).

## Notes

- Page banner or logo: use the project logos in the jars; no screenshots needed.
- Thumbnail: the `hall` scene. Lib page: one image, the logo, plus (optionally) a GUI widget sheet if the owner wants it.
- Check every frame for private details: chat history, player name other than a neutral one, window title, desktop,
  coordinates you do not want public.
