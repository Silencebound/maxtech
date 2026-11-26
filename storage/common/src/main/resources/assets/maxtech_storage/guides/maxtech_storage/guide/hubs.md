---
navigation:
  title: Hubs
  icon: maxtech_storage:storage_hub_tier_1
  parent: cables.md
  position: 15
item_ids:
  - maxtech_storage:storage_hub_tier_1
  - maxtech_storage:storage_hub_tier_2
  - maxtech_storage:storage_hub_tier_3
---

# Hubs

A hub gives a branch of the network a [channel](cables.md) budget of its own. Devices behind it count against the hub,
and the hub uses one channel towards the controller.

| Hub | Channels |
| --- | --- |
| <ItemLink id="storage_hub_tier_1" /> | <Config key="network.channels.hubTier1" /> |
| <ItemLink id="storage_hub_tier_2" /> | <Config key="network.channels.hubTier2" /> |
| <ItemLink id="storage_hub_tier_3" /> | <Config key="network.channels.hubTier3" /> |

Use a hub for many devices on one branch, such as a row of drives. It does not make transfers faster.

<RecipeFor id="maxtech_storage:storage_hub_tier_1" />

<RecipeFor id="maxtech_storage:storage_hub_tier_2" />

<RecipeFor id="maxtech_storage:storage_hub_tier_3" />
