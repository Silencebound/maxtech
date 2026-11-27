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
- Leave out `connectionRule` and `onWrench` when Lib's own handling is enough. A connection rule is asked when links are
  recomputed, never per tick; it must be cheap and must not change the world.

## Files the addon ships

For a conduit `<modid>:<name>` registered with a look:

| File | Needed |
|---|---|
| `assets/<modid>/models/item/<name>.json` | yes; Lib's item model definition names `<modid>:item/<name>` |
| `assets/<modid>/textures/block/...png` | only for sprites the look names in the addon's namespace (`end`, `overlay`); `ConduitLook.FLAT` is Lib's |
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

The fields beside `type` are `ConduitLook.CODEC`. `look` names the kind; `solid` is the only one. `end` is the sprite
on an open end. `overlay` is optional, drawn over `end` in `overlay_colour`. `colour` and `overlay_colour` are optional
`#rrggbb` (Lib writes lower case and reads either); an absent colour means the type's `baseColour()`. A dye tints
`colour`, never `overlay_colour`.
`ConduitLookTest` and `ConduitAssetsTest` pin this format.
