---
navigation:
  title: Condition switch
  icon: maxtech_storage:condition_switch
  parent: ports.md
  position: 20
item_ids:
  - maxtech_storage:condition_switch
  - maxtech_storage:condition_switch_part
---

# Condition switch

The <ItemLink id="maxtech_storage:condition_switch" /> turns one network condition into a redstone signal of 15. The
<ItemLink id="maxtech_storage:condition_switch_part" /> ([parts](parts.md)) powers only the block it faces. Needs
[research](research.md).

<RecipeFor id="maxtech_storage:condition_switch" />

Open it to pick a condition: an item or tag count above or below a threshold, stored MU ([energy](energy.md)), link load
([channels](cables.md)), research done, or a value set by a [logic graph](logic_controller.md). It can also pass
redstone through the network's 16 colour channels. **Inverted** flips the signal.

**While crafting** gives the signal while a crafting job of the network is making the item in the slot, as the request or
as a step on the way: run a farm only while its product is being crafted.

**Request on signal** lists the item in the slot as craftable, like a
[generator provider](pattern_providers.md#generator-provider). When a job needs it, the switch gives the signal until the
job's amount has arrived in the network or the job ends, so a machine on redstone makes it on demand. The machine's
output has to reach storage, through an [import port](ports.md) or an [interface](interface.md). Like AE2, the switch
cannot tell where the item came from: any of it put into the network while the job waits counts.
