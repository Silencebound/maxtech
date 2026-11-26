---
navigation:
  title: Display panels
  icon: maxtech_storage:display_panel
  position: 95
item_ids:
  - maxtech_storage:display_panel
  - maxtech_storage:display_panel_part
  - maxtech_storage:smart_label
---

# Display panels

A <ItemLink id="maxtech_storage:display_panel" /> shows one value of its network as a hologram; the
<ItemLink id="maxtech_storage:display_panel_part" /> sits on a [cable's face](parts.md). A
<ItemLink id="maxtech_storage:smart_label" /> shows one reading of the block it sits on.

<RecipeFor id="maxtech_storage:display_panel" />

Touching panels merge into a wall, up to <Config key="display.maxWallSize" /> by
<Config key="display.maxWallSize" />. Use a panel empty-handed to pick its source: counts, power, bytes, logic notices,
your own text (colour and `{count:...}` markup), task board, build list or production stats. Graphs need the
Holographic Displays [research](research.md).

## History charts

Records one value over 1, 4 or 24 hours, up to <Config key="history.maxSeriesPerNetwork" /> values per network.

## Alarm panel

Lists the controller's [alarms](controller.md#alarms); right-click a new one to acknowledge it.
