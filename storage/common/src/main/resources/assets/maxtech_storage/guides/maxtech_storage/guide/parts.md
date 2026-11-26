---
navigation:
  title: Cable parts
  icon: maxtech_storage:storage_terminal_part
  parent: cables.md
  position: 10
item_ids:
  - maxtech_storage:storage_terminal_part
  - maxtech_storage:toggle_bus
  - maxtech_storage:inverted_toggle_bus
---

# Cable parts

Terminals, ports, the [interface](interface.md), [P2P links](p2p.md) and [world devices](world_devices.md) also come as
parts that sit on a [cable](cables.md) face, up to six per cable. A part uses a channel only if its block does.

- Use a part item on a cable to place it; sneak with a wrench to take it off.
- Block and part convert in the crafting grid and keep their settings.

<RecipeFor id="maxtech_storage:storage_terminal_part" />

## Toggle bus

A <ItemLink id="toggle_bus" /> sits in a cable's line and links the cable to what is beyond its face only while the
cable is powered by redstone. The <ItemLink id="inverted_toggle_bus" /> links only while it is unpowered. Its band
lights cyan while linked.

- Cutting the line splits the network as a broken cable would: devices beyond go offline and their storage leaves the
  terminals until it links again.
- It uses no channel.
- It switches at most once every <Config key="network.toggleBus.minIntervalTicks" /> ticks, so a redstone clock cannot
  rebuild the network every tick; it follows the last signal once that time has passed.

<RecipeFor id="maxtech_storage:toggle_bus" />

<RecipeFor id="maxtech_storage:inverted_toggle_bus" />
