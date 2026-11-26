---
navigation:
  title: Patterns
  icon: maxtech_storage:pattern_terminal
  position: 100
item_ids:
  - maxtech_storage:pattern_terminal
  - maxtech_storage:pattern_terminal_part
  - maxtech_storage:blank_pattern
  - maxtech_storage:encoded_pattern
---

# Patterns

An encoded pattern says what goes in and what comes out of one craft. Write them at the
<ItemLink id="maxtech_storage:pattern_terminal" /> (block or cable part) onto
<ItemLink id="maxtech_storage:blank_pattern" />s. It needs craft access (see [security](security.md)).

<RecipeFor id="maxtech_storage:pattern_terminal" />

<RecipeFor id="maxtech_storage:blank_pattern" />

- **Crafting** tab: fill the 3x3 grid, the recipe is found for you.
- **Processing** tab: up to nine inputs and three outputs; the first output is the main one. **x2** and **÷2**
  scale every amount (shift: 8); ÷ works only while every amount stays whole.
- **Stonecutter** and **smithing table** tabs: an assembler runs them like crafting. Right-click the stonecutter
  output for the input's other recipes; smithing takes template, base and addition in the top row.
- The network's items sit above the pattern panel. With an empty hand, click a slot to select it, then click an item
  in the grid to set it (or drag one onto a slot, also from JEI); nothing is taken from the network.
- Click with a stack sets a slot, right-click adds one, middle-click locks a tag input. **Substitutions** lets tag inputs use
  whichever item the network holds most of.
- **Advanced**: pick the machine face an input goes into, or mark it **Returned** (a tool or bucket that comes back).
- The arrow writes the pattern; sneak-use an encoded pattern to wipe it. With JEI, + fills the grid.

Several patterns for one item: the highest priority is tried first, falling back when materials run out. The
**Patterns** tab lists them all (an amber **!** marks duplicates) and sets priority (-999 to 999). Put patterns into a
[pattern provider](pattern_providers.md).
