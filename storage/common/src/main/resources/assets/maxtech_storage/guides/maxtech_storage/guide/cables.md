---
navigation:
  title: Cables
  icon: maxtech_storage:storage_cable_tier_1
  position: 20
item_ids:
  - maxtech_storage:storage_cable_tier_1
  - maxtech_storage:storage_cable_tier_2
  - maxtech_storage:storage_cable_tier_3
  - maxtech_storage:storage_cable_tier_4
  - maxtech_storage:covered_storage_cable_tier_1
  - maxtech_storage:covered_storage_cable_tier_2
  - maxtech_storage:covered_storage_cable_tier_3
  - maxtech_storage:covered_storage_cable_tier_4
  - maxtech_storage:smart_storage_cable_tier_1
  - maxtech_storage:smart_storage_cable_tier_2
  - maxtech_storage:smart_storage_cable_tier_3
  - maxtech_storage:smart_storage_cable_tier_4
  - maxtech_storage:fiber_storage_cable_tier_1
  - maxtech_storage:fiber_storage_cable_tier_2
  - maxtech_storage:fiber_storage_cable_tier_3
  - maxtech_storage:fiber_storage_cable_tier_4
  - maxtech_storage:network_analyzer
---

# Cables

Cables join devices into a network and set how many devices a stretch carries. They carry no items and never tick.

| Cable | Channels |
| --- | --- |
| <ItemLink id="storage_cable_tier_1" /> | <Config key="network.channels.tier1" /> |
| <ItemLink id="storage_cable_tier_2" /> | <Config key="network.channels.tier2" /> |
| <ItemLink id="storage_cable_tier_3" /> | <Config key="network.channels.tier3" /> |
| <ItemLink id="storage_cable_tier_4" /> | <Config key="network.channels.tier4" /> |

<GameScene zoom="3" interactive>
<ImportStructure src="cables.nbt" />
<DiamondAnnotation pos="1.5 0.5 1.5" color="#ff00e5ff">
Tier 4
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 0.5 1.5" color="#ff00e5ff">
Tier 3
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 0.5 1.5" color="#ff00e5ff">
Tier 2
</DiamondAnnotation>
<DiamondAnnotation pos="4.5 1 1.5" color="#ff00e5ff">
Hub: the branch behind it has its own channels
</DiamondAnnotation>
<DiamondAnnotation pos="5.5 0.5 1.5" color="#ff00e5ff">
Tier 1
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:storage_cable_tier_1" />

Every device uses one channel on each stretch between it and the controller. When a stretch is full, the devices that
joined last go offline and say which cable is full. A [hub](hubs.md) gives a branch its own budget. The line at the
bottom of a device's front shows its state: cyan online, red no channel, grey offline (no powered controller).
Energy cells, generators, displays, sensors, condition switches, research terminals, crafting CPUs, the security
module, the network anchor and [toggle buses](parts.md) use no channel.

A server can turn channel limits off (`network.channels.mode = infinite`): every device then gets a channel, cables
and hubs still show their load but never fill, and no front turns red. The zone size and team caps still apply.

Every tier also comes as <ItemLink id="maxtech_storage:covered_storage_cable_tier_1" /> (hides the lane),
<ItemLink id="maxtech_storage:smart_storage_cable_tier_1" /> (shows load) and
<ItemLink id="maxtech_storage:fiber_storage_cable_tier_1" /> (decorative), with the same channels.

<RecipeFor id="maxtech_storage:covered_storage_cable_tier_1" />

<RecipeFor id="maxtech_storage:smart_storage_cable_tier_1" />

<RecipeFor id="maxtech_storage:fiber_storage_cable_tier_1" />

Dye a cable to keep two networks apart; different colours do not link. Wrench a face to set insert or extract only. A
A facade hides a cable and its [parts](parts.md).

The <ItemLink id="maxtech_storage:network_analyzer" /> prints a network report when used on a block and lights its routes
by load. The [goggles and trace pen](network_tools.md) do the same while worn.
