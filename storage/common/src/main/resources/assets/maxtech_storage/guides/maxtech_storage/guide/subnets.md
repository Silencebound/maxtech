---
navigation:
  title: Subnets
  icon: maxtech_storage:subnet_controller
  position: 17
item_ids:
  - maxtech_storage:subnet_controller
---

# Subnets

The <ItemLink id="maxtech_storage:subnet_controller" /> starts a network of its own beside an existing one. Place it
against the parent network: its front faces the parent, and its other sides start the subnet, with up to
<Config key="network.subnet.maxDevices" unit="devices" /> and its own power buffer.

Choose what the two networks see of each other: merged, shares up, client or isolated. Needs the Subnets
[research](research.md).

<RecipeFor id="maxtech_storage:subnet_controller" />
