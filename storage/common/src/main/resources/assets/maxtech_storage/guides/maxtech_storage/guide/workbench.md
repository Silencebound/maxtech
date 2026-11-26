---
navigation:
  title: The cell workbench
  icon: maxtech_storage:cell_workbench
  parent: cells.md
  position: 10
item_ids:
  - maxtech_storage:cell_workbench
  - maxtech_storage:medium_cell_casing
  - maxtech_storage:memory_module_2
  - maxtech_storage:memory_module_5
  - maxtech_storage:memory_module_6
  - maxtech_storage:partition_addon
  - maxtech_storage:small_cell_casing
  - maxtech_storage:large_cell_casing
  - maxtech_storage:bulk_cell_casing
  - maxtech_storage:portable_cell_casing
  - maxtech_storage:medium_storage_cell
  - maxtech_storage:large_storage_cell
  - maxtech_storage:bulk_storage_cell
  - maxtech_storage:memory_module_3
  - maxtech_storage:memory_module_4
  - maxtech_storage:void_overflow_addon
  - maxtech_storage:read_only_addon
---

# The cell workbench

The <ItemLink id="maxtech_storage:cell_workbench" /> builds cells: put a casing in, add a controller chip, then memory
modules and add-ons. Larger parts need [research](research.md).

<RecipeFor id="maxtech_storage:cell_workbench" />

Casings: <ItemLink id="maxtech_storage:small_cell_casing" />, <ItemLink id="maxtech_storage:medium_cell_casing" />,
<ItemLink id="maxtech_storage:large_cell_casing" />, <ItemLink id="maxtech_storage:bulk_cell_casing" /> and
<ItemLink id="maxtech_storage:portable_cell_casing" /> make the <ItemLink id="maxtech_storage:medium_storage_cell" />,
<ItemLink id="maxtech_storage:large_storage_cell" />, <ItemLink id="maxtech_storage:bulk_storage_cell" /> (one kind,
<Config key="cells.bulkMultiplier" />x bytes) and the [portable cell](terminals.md). A large casing has
<Config key="cells.casings.large.memorySlots" /> memory slots.

Memory modules II to VI add up: <ItemLink id="maxtech_storage:memory_module_2" />, <ItemLink id="maxtech_storage:memory_module_3" />,
<ItemLink id="maxtech_storage:memory_module_4" />, <ItemLink id="maxtech_storage:memory_module_5" /> and
<ItemLink id="maxtech_storage:memory_module_6" />.

<RecipeFor id="maxtech_storage:memory_module_2" />

<RecipeFor id="maxtech_storage:memory_module_5" />

Add-ons, one each per cell: <ItemLink id="maxtech_storage:partition_addon" /> (filter), <ItemLink id="maxtech_storage:void_overflow_addon" />
(destroys filtered overflow) and <ItemLink id="maxtech_storage:read_only_addon" />.

The filter's *Deny listed* button turns it into a blacklist: the cell takes everything except the listed items and
tags, and void overflow never destroys a listed item.

Filter entries naming an item of a removed mod are kept and take a slot until you press *Clear unknown*.

<RecipeFor id="maxtech_storage:partition_addon" />
