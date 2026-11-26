---
navigation:
  title: Cable layer
  icon: maxtech_storage:cable_layer
  parent: cables.md
  position: 25
item_ids:
  - maxtech_storage:cable_layer
---

# Cable layer

The <ItemLink id="maxtech_storage:cable_layer" /> lays a run of cable between two points, taking cables from your network.

1. Sneak-use it on a <ItemLink id="maxtech_storage:storage_controller" /> to bind it (needs wireless range and access).
2. Use it on a block to mark the start, then on a second block to lay the run.
3. Sneak-use it on a cable to lay that tier, look and colour.

Each cable costs <Config key="tools.layer.energyPerCable" unit="MU" />; a run is at most
<Config key="tools.layer.maxLength" unit="blocks" />.

<RecipeFor id="maxtech_storage:cable_layer" />
