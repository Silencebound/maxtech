---
navigation:
  title: Logic Controller
  icon: maxtech_storage:logic_controller
  position: 96
item_ids:
  - maxtech_storage:logic_controller
  - maxtech_storage:logic_card
---

# Logic Controller

The <ItemLink id="maxtech_storage:logic_controller" /> lets a network react to events. It holds up to
<Config key="logic.maxGraphs" /> graphs. A graph wakes on an event (an item count crossing a limit, power going off, a
finished craft, a [sensor](sensor.md) reading), checks what is true and acts: switches devices, gives redstone, asks for
crafts, moves items or posts a notice. Devices are picked by [label](labels.md).

<GameScene zoom="3" interactive>
<ImportStructure src="logic.nbt" />
<DiamondAnnotation pos="1.5 0.5 1" color="#ff00e5ff">
Logic controller
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 1 1.5" color="#ff00e5ff">
Sensor reading the chest
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 0.5 1" color="#ff00e5ff">
Condition switch driving the lamp
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:logic_controller" />

Use it to open its screen. **RULES** shows each graph as a card (**WHEN**, **IF**, **THEN**), as nodes or as its
history; click gold or cyan words to change them, **+ NEW RULE** adds one and **Edit** opens the
[node editor](logic_editor.md). A graph is running, paused, in error (never runs), or waiting for the team
[budget](logic_budget.md). **LOG** explains why something did or didn't happen.

A first graph: label a [condition switch](condition_switch.md) "lamp" and set it to **Driven by logic**. Add **Item
count crosses** (cobblestone, 100) and **Redstone out** (label "lamp"), wire "Past the limit" to "On" and save. All
nodes are in the [node reference](logic_nodes.md).

## Testing rules

**Simulate** runs a rule without carrying out its actions and logs each as "Would ...". **Pin value** charts a node's
value. **Revert** restores the last working version, switched off. The newest <Config key="logic.historyEntries" />
changes per rule are listed under **HISTORY**.

## Other networks

A label ending in `@Name` reaches another network: `intake@North Base`. Rename a network on the controller's Network
tab. The rule's owner needs logic access there; logic never loads chunks.

## Sharing

**Copy**, **Export** (text) and **To card** share a graph; **Paste**, **Import**, **From card** and **Templates** add
one, switched off. Everything needs logic access ([security module](network_devices.md#security-module)).

<RecipeFor id="maxtech_storage:logic_card" />
