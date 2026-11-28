# Writing a conduit addon

How a mod adds its own conduit (a cable, tube or pipe block that connects to its neighbours) against Lib's api jar
alone (ADR-0015). Everything named here is in `lib.api.conduit`, `lib.api.network` or `lib.api.registry`, and is
experimental unless `docs/api/lib-1.0.md` says otherwise. Lib's GameTest `ConduitApiGameTests` (`lib/neoforge`,
`conduit_api_*`) does the same with the api only.

## What Lib does and what the addon does

Lib owns the conduit block and its block entity (`maxtech_lib:conduit`). It saves and syncs them, draws them from a
look, takes dyes and facades, cycles face modes with a wrench, and adds and removes each conduit as a node of its
type's network. A conduit never ticks.

The addon brings:

- a network type with its `NetworkLogic`, which is where the transport happens;
- a `ConduitType` (or a subclass) naming that network type, a tier, a base colour and a thickness;
- optional hooks: a `ConduitWrenchHook` and a `ConduitConnectionRule`;
- a few resource files (below).

Addons never subclass Lib's conduit block. Custom conduit models are not supported yet; every conduit is drawn by Lib's
conduit model from its `ConduitLook`.

## Code

Declare the `maxtech_lib` dependency with `ordering = "AFTER"` in `neoforge.mods.toml`: Lib sets up `Conduits` in its
own construction. Register during your mod's construction, for example from static fields of a class your mod
constructor loads.

```java
public final class LabTubes {
    static final Identifier NETWORK_ID = Identifier.fromNamespaceAndPath("farms", "lab");

    static final NetworkType NETWORK = NetworkType.register(NETWORK_ID,
            level -> Conduits.connectivity(level, NETWORK_ID),
            level -> new LabLogic(level));

    static final ConduitEntry<ConduitType> TUBE = Conduits
            .builder("farms", "lab_tube", item -> new ConduitType(NETWORK_ID, "basic", 0xD0D8DC, 0.25F, item))
            .look(ConduitLook.solid(Identifier.fromNamespaceAndPath("farms", "block/lab_tube_end"), null,
                    ConduitLook.TYPE_COLOUR, ConduitLook.TYPE_COLOUR))
            .creativeTab(CreativeModeTabs.FUNCTIONAL_BLOCKS)
            .connectionRule((conduit, side, neighbour) -> true)
            .onWrench((level, conduit, player, hit, dismantle) -> InteractionResult.PASS)
            .register();
}

final class LabLogic implements NetworkLogic {
    LabLogic(ServerLevel level) {
    }

    @Override
    public void onRebuilt(Network network) {
        // Rebuild caches here: network.nodes() holds packed positions (BlockPos.of).
    }

    @Override
    public void tick(Network network) {
        // Move things; keep it allocation-free.
    }
}
```

- `Conduits.connectivity(level, networkId)` reads the links Lib keeps in the block entities; pass it as the
  connectivity factory unless the network has nodes that are not conduits.
- `TUBE.type()`, `TUBE.block()` and `TUBE.item()` resolve once registration has run.
- In the logic, `Conduits.host(level, pos)` gives a `ConduitHost`: read the colour, links and face modes, and set which
  faces towards blocks the network uses with `setBlockFaces`. It allocates an `Optional`, so call it when the graph
  reports a change, not per tick. `Conduits.isChunkPresent` tells whether a neighbour may be read.
- `NetworkManager.get(level)` gives the level's graphs: `network(type, pos)`, `graph(type)` and anchors.
- `Conduits.place` places a conduit as a player would (useful in GameTests); `Conduits.useWrench` lets the addon's own
  wrench item act on conduits.
- `.attachesToBlocks()` has the logic hear about block entities appearing beside the conduit (below).
- Leave out `connectionRule` and `onWrench` when Lib's own handling is enough. A connection rule is asked when links are
  recomputed, never per tick; it must be cheap and must not change the world.

## Attaching to blocks

A conduit that works with the blocks beside it (a hub, a port) needs to know when one appears. With
`.attachesToBlocks()` on the builder (Lib 0.15.0, experimental) Lib keeps track of the block entities on the conduit's
six sides, other than conduits, and fires `NetworkLogic.onNodeInvalidated` for the conduit's node once for each change:
a block entity placed, broken or replaced beside it, found when the chunk across a chunk border reaches full status,
or gone when that chunk leaves full status (neither sends a neighbour update). A change in the same update as a link change gives one invalidation, not two. Lib
compares block entity instances, so a neighbour that reloads with its chunk counts as replaced. The logic rescans the
conduit's attachments in `onNodeInvalidated`, and in `onNodeAdded` for a conduit that was just placed or loaded.

It costs nothing per tick: Lib reads the six neighbours only on neighbour updates and on chunks arriving or leaving.
Not noticed are a block entity set or removed without a block change and changes inside a block entity, such as a
container's contents. When scanning, skip sides where `Conduits.isChunkPresent` is false: reading a block there loads
its chunk. A conduit without the
opt-in ignores block entities, as Storage's cables do. `ConduitApiGameTests` (`conduit_api_attach_*`, `conduit_attach_*`) pins it.

## Files the addon ships

For a conduit `<modid>:<name>` registered with a look:

| File | Needed |
|---|---|
| `assets/<modid>/models/item/<name>.json` | yes; Lib's item model definition names `<modid>:item/<name>` |
| `assets/<modid>/textures/block/...png` | only for sprites the look names in the addon's namespace (`end`, `overlay`, `shell`); `ConduitLook.FLAT` is Lib's |
| `assets/<modid>/lang/en_us.json` | yes: `block.<modid>.<name>` (the item uses the block's name) |
| `data/<modid>/loot_table/blocks/<name>.json` | only to drop something other than the conduit's item; without one Lib drops the item, and a facade drops either way |

Lib supplies the rest from a built-in client pack that sits below the mods' own resources, so a file the addon ships
with the same path wins:

- `assets/<modid>/blockstates/<name>.json`: one variant for every state, naming Lib's conduit model with the look;
- `assets/<modid>/items/<name>.json`: the item model definition.

Without `look(...)` the addon ships both itself.

## Block state format

The block state file Lib writes, and an addon writes if it sets no look:

```json
{
  "variants": {
    "": {
      "type": "maxtech_lib:conduit",
      "look": "solid",
      "end": "farms:block/lab_tube_end",
      "overlay": "farms:block/lab_tube_core",
      "colour": "#d0d8dc",
      "overlay_colour": "#ff6400"
    }
  }
}
```

The fields beside `type` are `ConduitLook.CODEC`. `look` names the kind: `solid` or `shell` (below). `end` is the sprite
on an open end. `overlay` is optional, drawn over `end` in `overlay_colour`. `colour` and `overlay_colour` are optional
`#rrggbb` (Lib writes lower case and reads either); an absent colour means the type's `baseColour()`. A dye tints
`colour`, never `overlay_colour`.
`ConduitLookTest` and `ConduitAssetsTest` pin this format.

## Shell look

`ConduitLook.shell(sprite)` (Lib 0.14.0, experimental) draws a translucent glass tube. Every glass face shows the
`shell` sprite projected onto the block and turned so its rows run along the tube, so the sprite tiles on without a
seam from one tube to the next; a mostly transparent sprite with a faint tint and a few highlight rows works best.
Lib works the outline out from the connections and draws it as opaque strips just outside the glass:

- border lines along the tube's outer long edges, merged across core and arms; they run on over a joint with the
  next tube, never across it, and leave out the inner crease of a bend, branch or crossing;
- a rim around the end face where a run ends in the open (a single tube with no connections has plain borders on all
  twelve edges instead);
- a rim band around an arm that meets a block, just inside Lib's connector ring, which stays as on any conduit.

With `frame_bars` (Lib 0.15.0) the borders are solid bars instead of strips, and the glass sits one bar width inside
the tube's outer box, between the bars, so they stand out as a frame. Every edge of the outer box has a bar `border` px
square, flush with it: a straight run's four long edges; an open end's long edges and the four around its end face (no
rim); at a bend, branch or single tube all twelve edges of the core plus each arm's four. Nothing is left out, so the
bars on the far side, seen through the glass, read as the back of the frame instead of lines floating in it. Arm bars
run to the block face and meet the next tube's end to end. A rim band becomes a collar one bar deep around the arm.
The contents sit `inset` px inside the set-in glass.

```json
{
  "variants": {
    "": {
      "type": "maxtech_lib:conduit",
      "look": "shell",
      "shell": "farms:block/lab_tube_glass",
      "border_colour": "#2e4a52"
    }
  }
}
```

| Field | Default | Meaning |
|---|---|---|
| `shell` | required | sprite of the glass (block atlas) |
| `border_colour` | the type's `baseColour()` | `#rrggbb` of borders and rims; a dye tints them, never the glass |
| `border` | `0.5` | border line width in pixels on each face beside the edge, 0 to 4; 0 draws none |
| `rim` | `1.5` | rim width in pixels, 0 to 4; 0 draws none |
| `inset` | `0.5` | how far the contents sit inside the glass, in pixels, 0 to 4 |
| `lane_width` | `1` | width of the second lane's strip, in pixels, 0 to 4; 0 draws none |
| `frame_bars` | `false` | draw the borders as solid bars on every edge with the glass set in between (Lib 0.15.0) |

Lib leaves out each field at its default when it writes the file. Later Lib versions add more optional fields, so a
file written now stays valid. The model is translucent, so block outlines and moving blocks treat it as such;
particles show the `shell` sprite. In code: `ConduitLook.shell(id).withBorderColour(0x2E4A52)`, `.withBorder(1)`,
`.withRim(2)`, `.withInset(1)`, `.withLaneWidth(0.5F)`, `.withFrameBars()`. `ConduitLookTest` and `ConduitAssetsTest`
pin this format too.

A shell draws the conduit's `ConduitContents` (`ConduitHost.setContents`) inside the glass, opaque, with the
resource kind's client look (`ResourceLook`, registered with `ResourceLooks.register` on the client): its still and
flowing sprites (the still one where a kind has no flowing sprite), tint and light level (lava is full-bright, without
ambient occlusion). Lib registers the fluid kind's look from each fluid's model, so any mod's fluid works; contents of
a kind without a look draw nothing (Lib warns once). The look sees the resource's draw base, and its paint is resolved
once on the main thread when the resource is first shown and again after a resource reload: the contents are baked
once per shape and resource and shared, so water is the same blue in every biome. Horizontal pieces fill from the bottom by quarter steps; vertical pieces show a full column at
any fill above 0. With an upstream side the flowing sprite runs from that side downstream on every face, else the
still sprite shows, both at full size, one sprite per block, so they tile on without a seam; the second lane is a thin
strip in the run's inner (+, +) corner, on the fluid's surface on horizontal pieces, flowing the other way.
`ConduitContentsGeometryTest` pins the flow direction per face. Fluid contents come from `ConduitContents.ofFluid`;
contents of any other kind from the constructor with a `KindKey`.
