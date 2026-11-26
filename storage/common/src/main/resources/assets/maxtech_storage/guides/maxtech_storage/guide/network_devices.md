---
navigation:
  title: Network devices
  icon: maxtech_storage:controller_security_module
  parent: controller.md
  position: 10
item_ids:
  - maxtech_storage:controller_network_anchor
  - maxtech_storage:controller_security_module
---

# Network devices

Place these anywhere on the network: on a cable or against a device.

## Network anchor

The <ItemLink id="maxtech_storage:controller_network_anchor" /> keeps the controller's, drives' and cells' chunks loaded,
up to <Config key="devices.networkAnchor.chunks" /> per team.

<RecipeFor id="maxtech_storage:controller_network_anchor" />

## Security module

The <ItemLink id="maxtech_storage:controller_security_module" /> lets the owner set access per player or team, from none
to full. Use it to open the rules. See [security](security.md).

<RecipeFor id="maxtech_storage:controller_security_module" />

Antenna mounts and the dimension bridge are in [antennas and wireless](signal.md).
