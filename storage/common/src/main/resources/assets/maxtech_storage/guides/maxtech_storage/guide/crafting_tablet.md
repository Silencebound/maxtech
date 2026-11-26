---
navigation:
  title: Crafting tablet and pattern export
  icon: maxtech_storage:crafting_tablet
  position: 101
item_ids:
  - maxtech_storage:crafting_tablet
---

# Crafting tablet

The <ItemLink id="maxtech_storage:crafting_tablet" /> is a portable crafting table that takes items from your network.
Sneak-use it on a controller to bind it; it needs a wireless access mount and the Wireless research (see
[signal](signal.md)).

<RecipeFor id="maxtech_storage:crafting_tablet" />

It runs on its own charge (<Config key="crafting.tablet.energyCapacity" unit="MU" />) and spends
<Config key="crafting.tablet.energyPerOperation" unit="MU" /> per transfer with the network. Taking items needs full
[access](security.md), putting them back needs deposit access.

- The + button in JEI fills the grid from the network, then your inventory.
- **Request missing** orders a [crafting job](crafting_requests.md) for missing ingredients (craft access).
- **Jobs** shows the network's crafting jobs, as the terminal's jobs tab does (craft access).

## Pattern export

The lithograph, fabricator and assembler have an Export pattern button. It writes the recipe the machine ran onto a
blank pattern from your inventory, with catalysts marked as returned and chances kept. See [patterns](patterns.md).
