---
navigation:
  title: World devices
  icon: maxtech_storage:network_chest
  position: 55
item_ids:
  - maxtech_storage:network_chest
  - maxtech_storage:network_chest_part
  - maxtech_storage:collector
  - maxtech_storage:collector_part
  - maxtech_storage:placer_port
  - maxtech_storage:placer_port_part
  - maxtech_storage:breaker_port
  - maxtech_storage:breaker_port_part
---

# World devices

World devices reach into the world around the network. Each is a block and a [part](parts.md) and needs
[research](research.md). Right-click one for its filter slots and mode.

The <ItemLink id="maxtech_storage:network_chest" /> looks like a chest to hoppers but shows the network's items from its
filter.

<RecipeFor id="maxtech_storage:network_chest" />

The <ItemLink id="maxtech_storage:collector" /> pulls item entities within <Config key="world.collector.radius" unit="blocks" />
into the network.

<RecipeFor id="maxtech_storage:collector" />

The <ItemLink id="maxtech_storage:placer_port" /> places the block its filter names from the network. Set its action
to drop stacks or single items in the mode tab and it drops the filtered item in front of it instead, like a dropper.
It drops only into a space without a solid block and pauses while
<Config key="world.placer.dropEntityCap" /> items lie there.

<RecipeFor id="maxtech_storage:placer_port" />

The <ItemLink id="maxtech_storage:breaker_port" /> breaks the block it faces (up to hardness
<Config key="world.breaker.maxHardness" />) and stores the drops. Use it to farm crystal clusters
([materials](materials.md)).

<RecipeFor id="maxtech_storage:breaker_port" />
