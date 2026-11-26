---
navigation:
  title: Crafting requests
  icon: minecraft:crafter
  position: 101
---

# Crafting requests

A storage terminal can ask the network to craft anything a [pattern](patterns.md) makes. You need craft access (see
[security](security.md)).

Craftable entries carry a cyan "C". Middle-click or Ctrl-click one, set the amount, press Next. The server shows a plan:
what comes from storage, what is crafted and what is missing (red). Start queues the job once nothing is missing.

- **Delivery**: to storage, to you (within <Config key="crafting.deliveryRange" unit="blocks" />) or to a labelled
  [port](ports.md).
- **Tree** and **CPU** (extended mode): see the full steps, or pick a [crafting CPU](crafting_cpu.md) tier.
- **Start partial**: crafts what it can and waits for the rest.
- **Order list** tab: plan many items as one job, e.g. `stick*64, torch*16`.

The crafter button opens the status screen: each job's progress, and which [pattern provider](pattern_providers.md) is
holding it up. Cancel is for the requester or full access.

When your job finishes, stops or someone else cancels it, you get a toast; click it in the terminal to open the job. The
client option `crafting.toasts` turns them off. Your team gets them too while the server option `crafting.teamToasts` is
on (now: <Config key="crafting.teamToasts" />).

Stock keepers, logic rules and crafting cards queue behind players. The controller's Crafting tab limits their job size
(<Config key="crafting.automationMaxPerJob" />) and keeps CPUs free for players
(<Config key="crafting.automationCpusKeptFree" />).
