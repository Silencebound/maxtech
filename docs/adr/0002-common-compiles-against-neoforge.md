# ADR-0002: `common` compiles against NeoForge, with an allowlist

Date: 2026-09-29
Status: Accepted. Supersedes ADR-0001 for how `common` gets Minecraft; ADR-0001's capability decision still holds.

## Context

ADR-0001 put `common` in MDG vanilla mode, so NeoForge classes are absent and every leak is a compile error. Conduits, ports and machine inventories need item and fluid transfer, and fluids have to be registered as NeoForge fluids.

NeoForge's transfer package (`~/mc-reference/NeoForge`, `26.1.x`) is not self-contained: it imports `FluidStack`, `FluidType`, `Capabilities`, `RegisterCapabilitiesEvent` and `ValueIOSerializable`, among others. Keeping `common` NeoForge-free would mean our own resource, transaction and fluid-stack types, converted at every call. That is the ceremony PRD §4.1 warns against, and the conversions would sit on pipe and storage hot paths (PRD §8.1).

## Options considered

1. **Wrap transfer and fluids behind `Platform`** with our own types.
   - Pros: `common` stays NeoForge-free; ADR-0001's compile-time guarantee survives.
   - Cons: hundreds of lines duplicating NeoForge's resource and transaction model; allocations or conversions on every transfer; every NeoForge API change has to be mirrored.
2. **Compile `common` against NeoForge and restrict usage with the import check** (the PRD's original intent).
   - Pros: transfer, fluid and capability types are used directly with no conversion; one version source for both halves; one NeoForm setup per mod instead of two.
   - Cons: a leak is a warning, not a compile error; NeoForge's patched methods on vanilla classes (`IBlockExtension` and similar) become callable from `common` and no import check sees them.
3. **Move all transfer-using logic into `neoforge` projects.**
   - Pros: `common` stays clean.
   - Cons: machines, pipes and storage logic, the bulk of the suite, would live in `neoforge`, defeating the split.

## Decision

Option 2. `maxtech.common` sets `neoForge { version = <neoforge> }` instead of `neoFormVersion`. `checkCommonImports` accepts `net.neoforged` references only when they are into `net.neoforged.neoforge.transfer`, `.fluids`, `.capabilities` or `.model.data` **and** carry a `// PLATFORM-LEAK:` marker, on the same line or on a comment-only line directly above. Each reference is judged on its own, so an allowed one does not cover a disallowed one on the same line; every other reference warns, marked or not. Transfer call sites go through `Platform.transfer()` where one exists, so they stay easy to find.

ADR-0001's capability decision stands: our own capabilities (energy, network nodes, block info) are `BlockLookup`s created through `Platform.capabilities()`, and the `api` source sets never expose NeoForge types, so the api jars stay loader-neutral for third-party addons. `checkCommonImports` accepts no NeoForge reference at all in `api` sources, marked or not, replacing the compile error vanilla mode gave them.

## Consequences

- Transfer and fluid code in `common` is plain NeoForge code, marked.
- The seam is enforced by a warning and review (`docs/PR_REVIEW.md` counts `checkCommonImports` warnings as findings). Patched vanilla methods are not caught; a Fabric port would audit for them.
- The NeoForm entry is gone from the version catalog; only the NeoForge version needs bumping.
- Packaging and dev runs are unchanged: `neoforge` still takes `common` as `compileOnly` and loads it through `mods {}` (ADR-0001).

## Amendment (2026-09-29): model data

`net.neoforged.neoforge.model.data` joins the allowlist. NeoForge only lets a block entity pass render data to its baked model by overriding `IBlockEntityExtension.getModelData()`, which returns `ModelData`; `ModelDataManager` calls that override and nothing else, and there is no event that supplies model data from outside. The alternative was a `Platform` method providing each rendered block entity's factory, so `neoforge` registers a subclass whose `getModelData()` wraps plain render state from `common`. Rejected: it adds a `Platform` method and a subclass per rendered block entity, spreads one block entity over two projects, and buys only the absence of two marked imports that review already polices. Block entities live in `common`, so the conduit bundle (M1 task 5) needs `ModelData` and `ModelProperty` there. Uses stay confined to `getModelData()` overrides and their property keys, marked `// PLATFORM-LEAK:`; model code itself stays in `neoforge`.
