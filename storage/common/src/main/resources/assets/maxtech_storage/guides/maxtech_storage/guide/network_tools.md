---
navigation:
  title: Goggles, trace pen and zone painter
  icon: maxtech_storage:network_goggles
  parent: cables.md
  position: 20
item_ids:
  - maxtech_storage:network_goggles
  - maxtech_storage:trace_pen
  - maxtech_storage:zone_painter
---

# Goggles, trace pen and zone painter

Tools for reading a network's routes, like the analyzer in [cables](cables.md).

The <ItemLink id="maxtech_storage:network_goggles" /> draw nearby routes through walls, green, amber or red by load. They
use <Config key="tools.goggles.energyPerSecond" unit="MU" /> a second.

<RecipeFor id="maxtech_storage:network_goggles" />

The <ItemLink id="maxtech_storage:trace_pen" /> lists the path between two blocks: sneak-use to mark the start, use on
the end.

<RecipeFor id="maxtech_storage:trace_pen" />

The <ItemLink id="maxtech_storage:zone_painter" /> marks a cable as the start of a zone (zones hold up to
<Config key="network.zoneSize" unit="nodes" />); use again to clear.

<RecipeFor id="maxtech_storage:zone_painter" />
