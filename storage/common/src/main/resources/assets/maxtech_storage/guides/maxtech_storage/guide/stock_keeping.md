---
navigation:
  title: Stock keeping
  icon: maxtech_storage:stock_keeper
  position: 110
item_ids:
  - maxtech_storage:stock_keeper
  - maxtech_storage:crafting_card
---

# Stock keeping

The <ItemLink id="maxtech_storage:stock_keeper" /> keeps items stocked: when the network runs low, it asks autocrafting
for more. Needs [research](research.md), patterns and a crafting CPU.

<RecipeFor id="maxtech_storage:stock_keeper" />

Each of its <Config key="crafting.stockKeeperRules" /> rules has an item, an amount to **keep** and one to **restart
below**. A [logic controller](logic_controller.md) can change rules through the keeper's [label](labels.md).

A <ItemLink id="maxtech_storage:crafting_card" /> in an export [port](ports.md) or an [interface](interface.md) asks for a
craft when the network runs out of its item.

<RecipeFor id="maxtech_storage:crafting_card" />
