---
navigation:
  title: Research
  icon: maxtech_storage:research_terminal
  position: 80
item_ids:
  - maxtech_storage:research_terminal
  - maxtech_storage:research_skip_token
  - maxtech_storage:research_terminal_part
  - maxtech_storage:machine_observer
  - maxtech_storage:research_scanner
  - maxtech_storage:research_notes
---

# Research

Larger casings, memory modules II and up, advanced processors, higher CPU tiers and more are gated by research. An
operator can turn it off (`research.preset`), and everything is unlocked.

The <ItemLink id="maxtech_storage:research_terminal" /> (also a cable part,
<ItemLink id="maxtech_storage:research_terminal_part" />) opens your team's research. It needs a powered network and
[access](security.md).

<RecipeFor id="maxtech_storage:research_terminal" />

## Nodes

A node completes when its required nodes are done and **any one** option is met:

- **Task**: a counter reaches its target (items stored, machine runs, items moved by ports and so on).
- **Points**: pay the node's cost.
- **Puzzle**: solve one of the [puzzles](research_puzzles.md).

Locked nodes list what they need; numbers are on the screen and depend on the preset (`off`, `relaxed`, `standard`,
`expert`).

## Points and skip tokens

Points come from discovering items and fluids (<Config key="research.points.discovery" /> each), advancements, the
<ItemLink id="maxtech_storage:machine_observer" />, the <ItemLink id="maxtech_storage:research_scanner" /> and found
<ItemLink id="maxtech_storage:research_notes" />.

A skip token completes any node whose requirements are done. The team earns one per
<Config key="research.skipTokens.pointsPerToken" unit="points" />, and the
<ItemLink id="maxtech_storage:research_skip_token" /> item from structure chests adds one.

## Teams

Progress is shared by a team: players sharing a vanilla scoreboard team (or FTB Teams team), otherwise just you. A
network's or machine's [owner](security.md) decides which team it counts for.
