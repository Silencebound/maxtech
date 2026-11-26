---
navigation:
  title: Security and ownership
  icon: minecraft:iron_door
  position: 90
---

# Security and ownership

Devices belong to whoever placed them. A player may use a network if they own it, are trusted by the owner, share an
allowed team, or are a game master. For finer rules add a [security module](network_devices.md#security-module).

Ownership also decides whose [research](research.md#teams) a machine counts for.

## Land claims

Devices that act on the block in front of them ([ports](ports.md), [sensors](sensor.md),
[pattern providers](pattern_providers.md), [P2P](p2p.md) outputs) ask land claim mods first, as their owner. If the
claim refuses, the device says "Blocked by a land claim".
