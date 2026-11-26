---
navigation:
  title: P2P links
  icon: maxtech_storage:p2p_item_link
  position: 56
item_ids:
  - maxtech_storage:p2p_item_link
  - maxtech_storage:p2p_fluid_link
  - maxtech_storage:p2p_energy_link
  - maxtech_storage:p2p_redstone_link
  - maxtech_storage:p2p_item_link_part
  - maxtech_storage:p2p_fluid_link_part
  - maxtech_storage:p2p_energy_link_part
  - maxtech_storage:p2p_redstone_link_part
  - maxtech_storage:p2p_linker
---

# P2P links

A P2P link carries items, fluid, energy or redstone from one place to others through a network. Each kind is a block and
a [part](parts.md): <ItemLink id="maxtech_storage:p2p_item_link" />, <ItemLink id="maxtech_storage:p2p_fluid_link" />,
<ItemLink id="maxtech_storage:p2p_energy_link" /> and <ItemLink id="maxtech_storage:p2p_redstone_link" />.

Sneak-use the <ItemLink id="maxtech_storage:p2p_linker" /> on the receiving end, then use it on each end that should
give out. Energy loses <Config key="p2p.energyLossPercent" unit="%" /> on the way. Needs the P2P Links
[research](research.md).

<RecipeFor id="maxtech_storage:p2p_item_link" />
<RecipeFor id="maxtech_storage:p2p_linker" />
