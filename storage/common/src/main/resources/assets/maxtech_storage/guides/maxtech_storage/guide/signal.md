---
navigation:
  title: Antennas and wireless
  icon: maxtech_storage:antenna_tier_1
  position: 57
item_ids:
  - maxtech_storage:antenna_tier_1
  - maxtech_storage:antenna_tier_2
  - maxtech_storage:antenna_tier_3
  - maxtech_storage:wireless_terminal
  - maxtech_storage:controller_antenna_mount
  - maxtech_storage:controller_wireless_access_mount
  - maxtech_storage:controller_dimension_bridge
---

# Antennas and wireless

Two <ItemLink id="maxtech_storage:antenna_tier_1" /> antennas paired with the
<ItemLink id="maxtech_storage:p2p_linker" /> join distant parts of a network (tier 2 and 3 reach 1,024 blocks and the
dimension; channels <Config key="signal.antenna.channelsTier1" />,
<Config key="signal.antenna.channelsTier2" />, <Config key="signal.antenna.channelsTier3" />). The
<ItemLink id="maxtech_storage:controller_antenna_mount" /> pairs a device the same way.

<RecipeFor id="maxtech_storage:antenna_tier_1" />

The <ItemLink id="maxtech_storage:wireless_terminal" /> opens a terminal from afar once bound to a controller; range
comes from <ItemLink id="maxtech_storage:controller_wireless_access_mount" /> blocks and each transfer costs
<Config key="signal.wireless.energyPerOperation" unit="MU" />.

## Across dimensions

The <ItemLink id="maxtech_storage:controller_dimension_bridge" /> extends wireless tools to other dimensions for
<Config key="devices.dimensionBridge.drainPerTick" unit="MU" /> per tick. See [research](research.md).
