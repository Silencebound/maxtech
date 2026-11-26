# ADR-0010: Digital storage and autocrafting live in their own addon

Status: accepted (owner decision 2026-10-01)

## Context

Digital storage, autocrafting and the AE2/RS bridges were planned as one addon together with pipes and routed pipes. The two halves serve different players: a pack may want a storage network without pipes, and one jar holding both would become by far the largest addon.

## Decision

- **Storage** (`maxtech_storage`, package `dev.silencebound.maxtech.storage`) holds the digital storage network (storage/PRD.md §7.2), autocrafting (M7) and the AE2/RS bridges (`storage.compat.ae2`, `storage.compat.refinedstorage`).
- Storage has its own planner and executor (owner decision 2026-10-03). Lib offers shared building blocks (resource keys, snapshots, worker pool), not a planner.
- Integration with other mods is optional and follows the usual rules (`type = "optional"`, `compat.<modid>`, `ModList` guards).

## Consequences

- Storage needs its own standalone recipes for anything that would use another mod's materials.
- History: the pipe half was dropped; this repository holds only Lib and Storage.
