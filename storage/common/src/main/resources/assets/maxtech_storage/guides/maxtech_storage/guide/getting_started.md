---
navigation:
  title: Getting started
  icon: maxtech_storage:fuel_generator
  position: 10
item_ids:
  - maxtech_storage:fuel_generator
---

# Getting started

A storage network is the cables and devices that touch each other. It needs power (MU) and something to store in.

<GameScene zoom="3" interactive>
<Block id="fuel_generator" x="0" y="0" z="0" p:facing="north" />
<Block id="storage_drive" x="1" y="0" z="0" p:facing="north" />
<Block id="storage_terminal" x="2" y="0" z="0" p:facing="north" />
</GameScene>

1. Make the early parts ([materials](materials.md), [machines](machines.md)).
2. Place a <ItemLink id="maxtech_storage:fuel_generator" /> against a device or cable and fuel it.
3. Place a <ItemLink id="maxtech_storage:storage_drive" /> with a [cell](cells.md) in it.
4. Place a <ItemLink id="maxtech_storage:storage_terminal" /> next to the drive and open it.

<RecipeFor id="maxtech_storage:fuel_generator" />

<GameScene zoom="3" interactive>
<ImportStructure src="first_network.nbt" />
<DiamondAnnotation pos="0.5 1 1.5" color="#ff00e5ff">
Controller
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 1.5 1" color="#ff00e5ff">
Drives
</DiamondAnnotation>
<DiamondAnnotation pos="1.5 0.5 1.5" color="#ff00e5ff">
Cable
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 0.875 2.5" color="#ff00e5ff">
Chest
</DiamondAnnotation>
</GameScene>

Devices that touch link directly; [cables](cables.md) link longer runs. Without a
<ItemLink id="maxtech_storage:storage_controller" /> a network is ad-hoc and takes at most
<Config key="network.channels.adHoc" unit="channels" />; the [controller](controller.md) lifts that. See
[energy](energy.md) for power.

## Device settings

Device screens keep their settings in tabs on the right edge, always in this order: **Mode & redstone**, **Priority**,
**Upgrades**, **Label** and **Access**; a device shows only the ones it has. Click a tab to open it beside the screen,
click it again or press Escape to close it. A dot marks a tab whose settings differ from the defaults; a red or amber
mark means a problem, and its tooltip says which.
