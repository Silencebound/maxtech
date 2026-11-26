# Storage texture generator

`generate.py` draws the Storage block textures in the "light grey" style (owner decision 2026-10-07: light grey casing
with a clean bevel and thin inner frame, one feature per block in a recess, cyan accents, 16 x 16) and overwrites the
PNGs under `storage/common/src/main/resources/assets/maxtech_storage/textures`. The palette is the "light" one of the
owner's sketch. The `.mcmeta` files are not generated; only the controller is animated.

`generate_items.py` draws every item texture in a vanilla-like style (owner decision 2026-10-15, the item-redraw
mockup): silhouette first, light from the top left, 5-shade hue-shifted ramps, the outline in the darkest shade of the
item's own ramp (small parts in a second material, such as gold pins, keep their colours), symmetric left to right
where the object is. A drawing is a map of (ramp, shade) pixels; `Sprite.outline` turns a shape's border into its
ramp's darkest shade. The owner's picks: processors are the square chip (tiers by the ring round the die: none, gold,
data crystal), wires are spools, the tantalum capacitor is an electrolytic can, the circuit board has a gold trace grid.
`--sheet FILE` writes a contact sheet instead of the PNGs. `--check-symmetry` (no Pillow needed) fails if an icon on its
`SYMMETRIC` list (chips, dies, masks, cells, modules, cards with symmetric symbols, memory modules and others) does not
mirror left to right in silhouette, ramps and darkest shade; `TextureKitTest` runs it.

    pip install pillow
    python storage/neoforge/src/textures/generate.py [--out DIR] [--sheet DIR]
    python storage/neoforge/src/textures/generate_items.py [--out DIR] [--sheet FILE] [--only a,b]
    python storage/neoforge/src/textures/generate_gui.py [--sheet DIR]
    python storage/neoforge/src/textures/mtx_controller.py [--check] [--sheet FILE]
    python storage/neoforge/src/textures/texture_kit.py [--check] [--sheet FILE]
    python storage/neoforge/src/textures/mtx_ports.py [--check]
    python storage/neoforge/src/textures/mtx_info.py [--check]   # 8 x 8 Jade overlay icons (gui/sprites/info)

Shading is applied when an image is written, so drawings can be mirrored freely: the casing's bevel and frame land on
untouched casing pixels, and a recess drawn with `socket` gets a rim shaded from the top left.

Devices that act on the block in front of them (sensor, placer, breaker, P2P links, pattern provider, machine
observer) share two textures: the back is `block/port/stub_end`, the cable connector, and every side is the arrow side
(`facing_side`, saved under each device's own side name) whose arrow points at the texture's top. The ports' textures
(the plain blocks' arrow fronts, sides, `stub_end` and the cable parts' steps of shape A) are `mtx_ports.py`'s
(MTX item 4). The facing devices' models (`FacingModels` in Storage's datagen) turn each side face so the
arrow points at the front. The controller's face is drawn by `mtx_controller.py` (below).

Storage cables (look "J") draw every texture one texel per block pixel: along an arm the real coordinates, across it
the arm's own pixels, so the 4 px glass sleeve (`block/cable/sleeve`) shows rows 6 to 9 and the 2 px lane (`lane`,
`fiber_lane`) rows 7 and 8. Core faces use one 4 x 4 cell of `sleeve_core` (or `covered_core`) picked by the arms
leaving the face (`CableGeometry.cell`). `generate.py` draws these. Cable items have no textures of their own: their
3D models (`CableItemModels` in Storage's datagen) are a straight piece of cable drawn with these sprites.

Generic materials (dusts, ingots, gems, raw tantalite) have no textures here either: their models draw a vanilla
texture (sugar, iron ingot, nether quartz, raw iron) tinted per item (`MaterialLooks`), so no Mojang pixels ship.

Cable parts have no textures of their own: their item models and the cable model draw them from the terminal, port
and other device textures. Outside the jar: this folder is not a resource directory.

`generate_gui.py` draws Storage's GUI sprites under `textures/gui/sprites/` (the GUI atlas stitches them): the
terminal's side tab icons (`terminal/tab_grid`, `tab_jobs`, `tab_log`, `tab_stock`), 16 x 16 light grey parts with the
dark outline and one cyan accent, so they read on the terminal's dark buttons and on the hover colour, and the view
button's 10 x 10 icons (`terminal/view_all`, `view_items`, `view_fluids`).

`mtx_controller.py` draws the controller's face in the 2a style (MTX item 2, `aeTile()` on the family style 2a page):
the runic sigil on a dark plate, on the M7R item 7 connected-texture model. 16 plates `block/controller/face/plate_<edges>`
(the 1 px frame on the edges whose bit is set: 1 top, 2 right, 4 bottom, 8 left; cut out: the sigil, the accent line
on an open bottom edge and the two middle pixels of every connected edge, where the sigils meet) and the glow planes
`glow_<status>_<phase>` seen through the cuts, 16-frame strips that start two frames later per phase, with their
`.mcmeta` and the first frames for the `still_effects` pack. It needs no Pillow, writes only those files and supports
`--check`.

`texture_kit.py` is the MaxTech family style 2a kit (MTX, `milestones/MTX.md` (milestone notes)): the shared palette, the accents
per mod, the state colours and `base()`, which draws the 1 px frame, the 14 x 14 inset, the bevel and, with
`line=True`, the accent line (with `conn` flags for connected textures) and returns the inner rectangle for a motif. Run as a script it writes the
plain casing and side textures (its `CASING` list, among them `block/storage_controller_side`, the plain casing many
blocks use), and the front line overlays `block/link/no_channel` and `block/link/offline` (transparent but for the
line in red or grey, MTX item 7): device fronts carry the online line, and the models draw an overlay over it for the
other network states (`LinkLook`, `LinkLineModel`); `generate.py` no longer writes those. It needs no Pillow. `--check` compares the committed PNGs with
the kit and fails on any difference; `TextureKitTest` (Storage common) runs it wherever Python is installed. MTX
generators that draw on the kit import it (`from texture_kit import base, Canvas, write_png, ACCENTS`), support
`--check` the same way and are added to the test's script list. The accent line marks a front only (owner,
2026-10-17): every call passes `line`, true for a texture the models draw as a block's or cable part's front, false for
sides, backs, tops, bottoms and blocks without a facing. A texture used as a front and elsewhere counts as a front; the
status lights keep it too, and the controller keeps its line on every face.

`mtx_screens.py` draws the terminal and screen faces on the kit (MTX item 3): the terminal fronts are the 2a page's
`termTex2` (a coloured screen with a glare row, a darker left edge, a soft bottom shadow and big lighter tiles, in the
layouts `term`, `craft2`, `panel`, `list` and `grid`; the accent line stays Storage cyan), the display panel's glass,
frame, edges and part screen, the smart label, and the terminals' plain sides (`block/terminal/bezel`, also
`display/side`) without the line; the stock keeper (every face the same) has none either. `generate.py` no longer writes these. It supports `--check` like the kit and
`--sheet FILE` (every layout in every screen colour).

    python storage/neoforge/src/textures/mtx_screens.py [--check] [--sheet FILE]

`mtx_storage_blocks.py` (MTX item 5a) draws the storage blocks on the kit: drives and their cell sprites, the bulk
drive, energy cells (static level bar, tier pips on the ends), the storage, network and workbench blocks, the crafting
CPU parts, the crafting assembler, the pattern and generator providers (with the pattern provider's arrow side) and the
creative energy source. Fronts carry their motif and the bright line; sides, tops and blocks without a facing have no
line; nothing is animated. It needs no Pillow and supports `--check`; `generate.py` no longer writes these. The CPU
blocks also get `block/cpu/<name>_<mask>` for the 15 connected edge masks (B09 item 28, `CpuClusterModel`).

`mtx_devices.py` (MTX item 5b) draws the device textures on the kit: machine fronts, their working overlays and tops,
the facing devices' fronts and arrow sides, world blocks, P2P links, hubs, bridges, antennas, the condition switch,
interface, mail port front, logic controller, the subnet controller's ends, the network devices and status lights
with their overlays. Everything is static; an overlay holds exactly the pixels the working face changes. `--check` and
`--sheet FILE` as in the kit.

Every MTX generator also takes `--list`, which prints the texture paths it owns. `MtxCoverageTest` (Storage common)
fails if a block texture referenced by a generated model, or any PNG under `textures/block`, is neither listed by an
MTX generator nor on its allow-list (the cable, ore and crystal textures `generate.py` still draws), so an old light
grey texture can't come back. A new block texture goes into the MTX script of its category.
