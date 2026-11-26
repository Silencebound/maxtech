---
navigation:
  title: Drives, cells and bytes
  icon: maxtech_storage:small_storage_cell
  position: 30
item_ids:
  - maxtech_storage:storage_drive
  - maxtech_storage:small_storage_cell
  - maxtech_storage:item_controller_chip
  - maxtech_storage:fluid_controller_chip
  - maxtech_storage:memory_module_1
---

# Drives, cells and bytes

Items and fluids live in cells. A cell mounted in a <ItemLink id="maxtech_storage:storage_drive" /> offers its space to
the network.

<GameScene zoom="3" interactive>
<ImportStructure src="drives.nbt" />
<DiamondAnnotation pos="1.5 0.5 0" color="#ff00e5ff">
A light for each mounted cell
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 1.5 0" color="#ff00e5ff">
Empty drive
</DiamondAnnotation>
</GameScene>

Use the drive with a cell to mount it (eight slots), empty-handed to open it. A higher **priority** fills first.

<RecipeFor id="maxtech_storage:storage_drive" />

## Bytes

A cell's space is its memory modules: <ItemLink id="maxtech_storage:memory_module_1" /> gives
<Config key="cells.memoryBytes.tier1" unit="bytes" />. Each kind costs <Config key="cells.kindBytes" unit="bytes" />
plus 1 byte per <Config key="cells.itemsPerByte" unit="items" />.

<RecipeFor id="maxtech_storage:small_storage_cell" />

<RecipeFor id="maxtech_storage:item_controller_chip" />

<RecipeFor id="maxtech_storage:memory_module_1" />

Larger cells are built in the [cell workbench](workbench.md).
