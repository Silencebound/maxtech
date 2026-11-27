# ADR-0017: Resource kinds

Date: 2026-11-27
Status: Accepted

## Context

Lib's holders each know one kind of resource: conduit contents and tank values hold a fluid, menu values an amount of
energy, Storage's networks a `StackKey` (an item or a fluid). Farms' lab tubes, tanks and gauges, and the planned
transfer ports, should also carry other mods' resources, such as Mekanism chemicals or Ars Nouveau's Source, through
compat modules. Neither mod has a 26.1.2 release yet, so the compat modules wait, but the holders built now (GUI
toolkit G4, transfer ports T1-T5) should not need a second rewrite when they come.

Saves must name a resource's kind by a stable name: registry ids are not stable across sessions, and a world can lose
the mod that added a kind. The api source set cannot reference NeoForge (ADR-0002).

## Options considered

1. **Grow `StackKey`.** Add more permitted key types. `StackKey` is sealed and stable, Storage's palettes, cells and
   autocrafting switch over it, and only Lib could add kinds.
2. **One holder type per kind.** A chemical tank next to the fluid tank, and so on. Every holder, payload and widget is
   written again for each kind.
3. **A registry of kinds in Lib.** A kind brings its key type, save and network codecs and amount unit; holders take
   a key of any kind. Built-in kinds wrap the existing keys.

## Decision

Option 3 (owner, 2026-11-27), in the common api package `lib.api.resource`, experimental until Farms ships.

- `ResourceKind<K>`: the id saves use, a codec, a stream codec, `drawBase` (the key without its components, for
  drawing) and an `AmountUnit` (lang key, units per bucket, formatting).
- `KindKey<K>` pairs a kind with a key. Its codec saves `kind` plus the key's fields for a kind registered with a
  `MapCodec` (which must not have a `kind` field of its own), or `kind` and `value` for one registered with a `Codec`;
  the overload chosen at registration fixes the format, not the codec's class. Its stream codec sends the kind id and
  a length-prefixed payload, so a client without a kind skips it; a separate serverbound stream codec refuses unknown
  resources, so a client cannot hand the server one of a kind the server has.
- `ResourceKinds` registers kinds during mod construction and is frozen at Lib's common setup; lookups by id, or by
  session index into an array. The index is never saved or sent.
- Built-in kinds: `ITEM` (`ItemKey`), `FLUID` (`FluidKey`), `ENERGY` (one key, for gauges and menu values only).
  `StackKey` stays as it is.
- Kinds for other mods live in Lib's compat modules (`lib.compat.<modid>`, loaded only with that mod), shared by all
  MaxTech mods, with ids in Lib's namespace (`maxtech_lib:mekanism_chemical`).
- A saved resource of a kind no loaded mod registers loads as an `UnknownResource` that keeps the saved tag and saves
  it back unchanged (owner): tanks and buffers keep a removed mod's contents, show them as "Unknown" and never move
  them. Conduit contents, which are only a display, go empty instead.
- Transfers never move a resource between two handlers that both lack transactions (owner): a move needs at least one
  side that can roll back.

## Consequences

- Holders built from here on take `KindKey`s, and a compat module adds a kind without changing them.
- A save of a kind key is larger than a `StackKey`'s (the full kind id); holders that store many keys still intern
  them.
- Unknown resources are dead weight until their mod returns; holders must not count them as space they can fill.
- Later steps: K2 moves `ConduitContents` to kind keys (fluids keep saving as before) with a client look per kind;
  where it nests a kind key under `resource` next to fields of its own, it must not save a second `kind` there;
  K3 makes `TankValue` and `AccentTank` kind-aware; G4 builds a tank over any kind; T1 uses kind ids in port settings
  and defines the NeoForge-side bridge from a kind to capabilities, and T3 its adapter for handlers without
  transactions; compat modules for Mekanism and Ars Nouveau follow their 26.1.2 ports.
