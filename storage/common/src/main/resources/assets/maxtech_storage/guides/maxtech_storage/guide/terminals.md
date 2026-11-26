---
navigation:
  title: Terminals, chest and portable cell
  icon: maxtech_storage:storage_terminal
  position: 40
item_ids:
  - maxtech_storage:storage_terminal
  - maxtech_storage:crafting_terminal
  - maxtech_storage:crafting_terminal_part
  - maxtech_storage:storage_chest
  - maxtech_storage:portable_storage_cell
  - maxtech_storage:view_filter
---

# Terminals, chest and portable cell

All of them open a searchable grid of everything stored.

The <ItemLink id="maxtech_storage:storage_terminal" /> shows what its network holds. Left-click takes a stack, right-click
half; click with a stack to store it. `@mod`, `#tag` and `$tooltip` refine the search. The button next to the search
shows everything, only items or only fluids. Entries marked "C" can be crafted
([crafting requests](crafting_requests.md)).

The button right of the sort buttons sets the size: small, tall (three more rows) or fill (as many rows as the window
holds). Each kind of terminal keeps its own size.

A red or amber dot on a tab says something needs you, such as a stopped or waiting crafting job; hover it for why.

<GameScene zoom="3" interactive>
<ImportStructure src="terminals.nbt" />
<DiamondAnnotation pos="1.5 0.5 0" color="#ff00e5ff">
Terminal
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 0.5 0" color="#ff00e5ff">
Crafting terminal
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 0.5 0" color="#ff00e5ff">
Pattern terminal
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:storage_terminal" />

## Crafting terminal

The <ItemLink id="maxtech_storage:crafting_terminal" /> (block or cable part) adds a crafting grid under the item grid.

- Take the result to craft one; the grid refills from the network, then your inventory. Shift-click crafts up to 64
  times.
- Leftovers such as empty buckets go to the network.
- The + button in JEI or REI fills the grid from the network. **Request missing** orders a
  [crafting job](crafting_requests.md) for what is missing (craft access).
- The cross returns the grid to the network. The grid keeps its items; breaking the terminal drops them.

<RecipeFor id="maxtech_storage:crafting_terminal" />

## View filters

A <ItemLink id="maxtech_storage:view_filter" /> limits what a terminal shows. Use it in hand to list up to nine items,
fluids or tags, exact or fuzzy, and pick show listed or hide listed. Put up to four in the slots left of the grid; an
entry shows if any filter shows it. A terminal block or part drops its filters when broken; a storage chest keeps them
in its item, and a wireless terminal or portable cell carries them in the item.

<RecipeFor id="maxtech_storage:view_filter" />

## Chest and portable cell

The <ItemLink id="maxtech_storage:storage_chest" /> has 54 slots and the same screen, with no network.

<RecipeFor id="maxtech_storage:storage_chest" />

The <ItemLink id="maxtech_storage:portable_storage_cell" /> is a cell with a battery
(<Config key="cells.portable.energyCapacity" unit="MU" />): use it to open its contents anywhere. Charge it on a
controller. Built in the [workbench](workbench.md).
