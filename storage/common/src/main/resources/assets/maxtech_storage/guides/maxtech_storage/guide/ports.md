---
navigation:
  title: Ports
  icon: maxtech_storage:storage_port_tier_1
  position: 50
item_ids:
  - maxtech_storage:storage_port_tier_1
  - maxtech_storage:storage_port_tier_2
  - maxtech_storage:storage_port_tier_3
  - maxtech_storage:speed_card
  - maxtech_storage:storage_port_tier_1_part
  - maxtech_storage:storage_port_tier_2_part
  - maxtech_storage:storage_port_tier_3_part
---

# Ports

A <ItemLink id="maxtech_storage:storage_port_tier_1" /> moves items and fluids between the network and the inventory it
faces. Its back links to a cable or device; ghost filter slots name what to move. **Import** takes into the network,
**Export** puts out (a blank filter exports nothing).

<GameScene zoom="3" interactive>
<ImportStructure src="ports.nbt" />
<DiamondAnnotation pos="1.5 1 1.5" color="#ff00e5ff">
Import: chest into the network
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 1 1.5" color="#ff00e5ff">
Export: network into the furnace
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 1 1.5" color="#ff00e5ff">
Storage bus (port part): the barrel is network storage
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:storage_port_tier_1" />

Tier 2 and 3 ports (<ItemLink id="maxtech_storage:storage_port_tier_2" />, <ItemLink id="maxtech_storage:storage_port_tier_3" />,
and the [part](parts.md) forms <ItemLink id="maxtech_storage:storage_port_tier_1_part" />,
<ItemLink id="maxtech_storage:storage_port_tier_2_part" />, <ItemLink id="maxtech_storage:storage_port_tier_3_part" />)
need [research](research.md) and move <Config key="ports.tier2.itemsPerSecond" /> and
<Config key="ports.tier3.itemsPerSecond" /> items a second, against <Config key="ports.tier1.itemsPerSecond" /> for
tier 1.

<RecipeFor id="maxtech_storage:storage_port_tier_2" />

Each <ItemLink id="maxtech_storage:speed_card" /> multiplies the rate by <Config key="ports.speedCards.multiplier" />;
cards go in the upgrades tab.

**Redstone** (mode tab): ignore, active with a signal, active without one, or **pulse**, one operation per rising
edge.

<RecipeFor id="maxtech_storage:speed_card" />

## Filter entries

Right-click a filter slot to step it through **Exact**, **Fuzzy** (any damage, enchantment or name), each **Tag** of
the item (`#minecraft:logs`) and **Mod** (`@minecraft`); a mark in the slot's corner shows which. They work for import,
export and the storage bus. A fuzzy, tag or mod entry exports up to
<Config key="ports.filter.exportKeysPerOperation" /> kinds per operation.

An export port can **keep** an amount: scroll over a slot (Shift: 64) or type it under the mode. The port then only
exports while the target holds less than that of what the entry matches, such as 64 coal in a furnace. When entries
overlap, an item counts for the first slot that matches it, and only that slot's amount applies. The **export
order** (mode tab) is filter order, or **round robin**, where each operation starts after the last kind moved.

## Storage bus

The third mode, **Storage**, makes the faced chest, barrel or tank part of the network's storage, like a
[drive](cells.md). Terminals see its contents, and stores and takes go straight into it, with no rate (speed cards do nothing here). The filter limits
what it shows and takes. **Access** (access tab) is Read, Write (stores only, contents hidden) or Read/Write. The
priority tab works as on drives. While its redstone mode keeps it inactive, the bus shows nothing; pulse counts as
ignore here.

Changes made by hoppers or pipes show within <Config key="ports.storageBus.minScanTicks" /> to
<Config key="ports.storageBus.maxScanTicks" /> ticks. A bus refuses an interface or other device of its own network,
or of a network that already reaches back into it.
