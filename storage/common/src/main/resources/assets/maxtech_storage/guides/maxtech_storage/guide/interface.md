---
navigation:
  title: Interface
  icon: maxtech_storage:storage_interface
  position: 55
item_ids:
  - maxtech_storage:storage_interface
  - maxtech_storage:storage_interface_part
---

# Interface

A <ItemLink id="maxtech_storage:storage_interface" /> lets hoppers, pipes and machines next to it use the network as an
inventory and tank. The <ItemLink id="maxtech_storage:storage_interface_part" /> does the same for one cable face
([parts](parts.md)).

<GameScene zoom="3" interactive>
<ImportStructure src="interface.nbt" />
<DiamondAnnotation pos="0.5 0.5 0" color="#ff00e5ff">
Hopper: chest into the network
</DiamondAnnotation>
<DiamondAnnotation pos="1.5 0.5 0" color="#ff00e5ff">
Interface
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:storage_interface" />

Click a stock slot with a stack to keep that amount filled from the network; fluid slots hold up to
<Config key="interface.fluidTankMb" unit="mB" />. A [crafting card](stock_keeping.md) asks for crafts when the network
runs out. Needs [research](research.md).

**Redstone** (mode tab): ignore, active with a signal or active without one. Inactive, it stocks nothing and takes no
inserts or pulls.
