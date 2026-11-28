# `lib.api.resource`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.resource`** | Item and fluid keys with components, interned by networks; resource kinds for holders of any kind (ADR-0017). | stable |
| `StackKey` | Sealed key type with codecs; `Kind`. | stable |
| `ItemKey` | Item plus component patch, never air. | stable |
| `FluidKey` | Fluid plus component patch, never empty. | stable |
| `ResourceKind`, `ResourceKinds` | A kind of resource (id, codecs, draw base, amount unit) and the registry of kinds, filled during mod construction and frozen at Lib's common setup; `register` with a `MapCodec` saves the key's fields next to `kind`, with a `Codec` under `value`; built-in `ITEM`, `FLUID`, `ENERGY`; lookup by id or session index (ADR-0017, Lib 0.15.0). `freeze` is Lib's (`@ApiStatus.Internal`). | experimental |
| `KindKey` | A key of any kind with its kind; save codec on `kind`, `CLIENTBOUND_STREAM_CODEC` that lets a client skip kinds it lacks, and `SERVERBOUND_STREAM_CODEC` for keys clients send, which refuses unknown resources. | experimental |
| `UnknownResource` | A saved resource of a kind no loaded mod registers, kept and saved back unchanged. | experimental |
| `KindTags` | How a kind's tag id becomes a test of its keys, registered per kind during mod construction (Lib's for items and fluids); port filters and recipe tank inputs share it, `PortFilter.registerTags` delegates to it. | experimental |
| `AmountUnit`, `EnergyKey` | How a kind's amounts are counted and shown (items, mB, MU); the one key of energy. | experimental |
