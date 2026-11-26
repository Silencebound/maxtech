---
navigation:
  title: Machines
  icon: maxtech_storage:lithograph
  position: 70
item_ids:
  - maxtech_storage:lithograph
  - maxtech_storage:fabricator
  - maxtech_storage:assembler
  - maxtech_storage:precision_module
  - maxtech_storage:speed_module
  - maxtech_storage:efficiency_module
  - maxtech_storage:void_module
  - maxtech_storage:logic_mask
---

# Machines

Three machines turn [materials](materials.md) into processors. They run on MU (<Config key="machines.energyCapacity" unit="MU" />
buffer) and take items from any side.

The <ItemLink id="maxtech_storage:lithograph" /> slices, dopes and prints dies; a <ItemLink id="maxtech_storage:logic_mask" />
(or other mask) in its catalyst slot picks the die.

<RecipeFor id="maxtech_storage:lithograph" />

The <ItemLink id="maxtech_storage:fabricator" /> draws wire, presses plates and boards, and refines tantalite.

<RecipeFor id="maxtech_storage:fabricator" />

The <ItemLink id="maxtech_storage:assembler" /> joins dies, wire and boards into processors.

<RecipeFor id="maxtech_storage:assembler" />

Modules: <ItemLink id="maxtech_storage:precision_module" />, <ItemLink id="maxtech_storage:speed_module" />,
<ItemLink id="maxtech_storage:efficiency_module" /> and <ItemLink id="maxtech_storage:void_module" /> (up to
<Config key="machines.modules.voidParallel" /> recipes at once).

<RecipeFor id="maxtech_storage:precision_module" />
