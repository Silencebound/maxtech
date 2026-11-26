---
navigation:
  title: Sensor
  icon: maxtech_storage:sensor
  parent: ports.md
  position: 21
item_ids:
  - maxtech_storage:sensor
  - maxtech_storage:sensor_part
---

# Sensor

The <ItemLink id="maxtech_storage:sensor" /> reads the block in front of it and offers what it read as values for
[logic graphs](logic_nodes.md); its other sides connect to cables. The <ItemLink id="maxtech_storage:sensor_part" />
does the same from a cable's face ([parts](parts.md)). It only looks and never changes the block.

<RecipeFor id="maxtech_storage:sensor" />

## Values

Open the sensor to see what the block offers: `redstone`, `comparator`, `state.<name>`, `energy.stored` and
`energy.capacity` (MU), `items.total`, `items.capacity`, `item.<id>`, `fluids.total`, `fluid.<id>`, tanks and
[pack reader](pack_readers.md) values (`data.<name>`). Blocks with a MaxTech energy network add `mu.*` values.

MU values change every tick but the sensor reads only on a change or a poll, so set **Polling** (1, 5 or 30 seconds) to
keep them live. Polling also helps blocks that change without telling neighbours.

## In a graph

Give the sensor a [label](labels.md), then use **Sensor value** or **Sensor value crosses** in the
[node editor](logic_editor.md). Each read costs 2 checks of the team's [logic budget](logic_budget.md); saved-data
reads cost [more](pack_readers.md#costs). A land claim that refuses the owner blocks the read
([claims](security.md#land-claims)).
