---
navigation:
  title: Node editor
  icon: maxtech_storage:logic_controller
  parent: logic_controller.md
  position: 10
---

# Node editor

The node editor draws a graph as nodes and wires. Press **Edit** on the [Logic Controller](logic_controller.md)'s screen
to open it; it needs logic access.

Add nodes from the palette on the left (click or drag). Drag from a port to wire it: a wire joins an output to an input
of the same type (event, number, yes/no, item, label), and an input takes one wire. Select a node to set it in the
panel on the right: items by search or **Use held item** (`#tag` counts a tag), labels by name, sensor values by the
name the [sensor](sensor.md) lists.

**Save** (Ctrl+S) applies the graph. A red note on a node, such as an input that needs a wire, saves the graph but
pauses it. A graph holds <Config key="logic.maxNodes" /> nodes at most; Undo and Redo go back 100 steps.

Wires and nodes show live values, and the line at the bottom says how the last run went. The top bar has **History**,
**Simulate** and **Revert** (see [Testing rules](logic_controller.md#testing-rules)). A graph another player has open
opens read only.
