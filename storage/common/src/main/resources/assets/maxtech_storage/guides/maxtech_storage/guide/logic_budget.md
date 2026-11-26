---
navigation:
  title: Team budget
  icon: minecraft:clock
  parent: logic_controller.md
  position: 40
---

# Team budget

Logic never slows the server because each team has two budgets per second, shared by all its controllers and
[sensors](sensor.md): <Config key="logic.teamChecksPerSecond" /> **checks** for thinking and
<Config key="logic.teamActionPointsPerSecond" /> **action points** for doing.

Most nodes cost 1 check; an action such as switching a device costs 2 points, a craft request 4 and moving items 12.
Reading another network costs <Config key="logic.crossNetworkChecks" /> extra checks.

When a budget is used up, the graphs that need it show **WAITING** and run in the next second; nothing is lost.
Terminals, ports and crafting are never held back. Idle controllers cost nothing.

The controller's screen shows the team's use (CHECKS /S, ACTIONS /S) and its own (HERE /S), and each rule's last cost.
A team is the owner's FTB Teams party, else the research team, else the owner alone.
