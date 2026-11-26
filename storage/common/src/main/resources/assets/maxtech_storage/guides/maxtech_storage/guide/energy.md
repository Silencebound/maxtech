---
navigation:
  title: Energy
  icon: maxtech_storage:energy_cell_tier_1
  parent: getting_started.md
  position: 10
item_ids:
  - maxtech_storage:energy_cell_tier_1
  - maxtech_storage:energy_cell_tier_2
  - maxtech_storage:energy_cell_tier_3
  - maxtech_storage:creative_energy_source
---

# Energy

Storage runs on MU (MaxTech Units). Generators and <ItemLink id="energy_cell_tier_1" /> energy cells join any network
they touch and use no channel. Generators fill the controller's buffer first; cells take what is left, up to
<Config key="energy.cells.tier3.capacity" unit="MU" /> (tier 3). Operations pay from the stored MU. With the store
empty and too little generated, everything slows down to match; nothing breaks.

<GameScene zoom="3" interactive>
<ImportStructure src="energy.nbt" />
<DiamondAnnotation pos="1.5 0.5 0" color="#ff00e5ff">
Fuel generator
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 1 0.5" color="#ff00e5ff">
Energy cells, tiers 1 to 3
</DiamondAnnotation>
</GameScene>

Other mods' FE blocks next to a cell feed the network and take its spare MU, at `energy.feRateFe` FE per
`energy.feRateMu` MU (Lib config, 1:1); `energy.exportEnabled` stops sending out.

Jade, the [signal probe](logic_tools.md), the [network analyzer](cables.md) and [sensors](sensor.md) show supply,
demand and time to full or empty. The <ItemLink id="maxtech_storage:creative_energy_source" /> never runs out, for
testing.

<RecipeFor id="maxtech_storage:energy_cell_tier_1" />

## When power runs low

Below <Config key="loadShedding.optionalPercent" unit="%" /> stored MU, the network pauses displays first, then ports,
interfaces and sensors. Drives, terminals and the controller never pause. Set a device's priority in its screen's mode
tab or from a [logic graph](logic_nodes.md).
