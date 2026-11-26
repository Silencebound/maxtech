---
navigation:
  title: Crafting CPU
  icon: maxtech_storage:crafting_cpu_tier_1
  position: 110
item_ids:
  - maxtech_storage:crafting_cpu_tier_1
  - maxtech_storage:crafting_cpu_tier_2
  - maxtech_storage:crafting_cpu_tier_3
  - maxtech_storage:crafting_cpu_storage
  - maxtech_storage:crafting_cpu_lane
  - maxtech_storage:crafting_cpu_cache
---

# Crafting CPU

A crafting CPU runs [crafting jobs](crafting_requests.md). Put a <ItemLink id="maxtech_storage:crafting_cpu_tier_1" />
on the network and touch it with part blocks, in any shape. Each core runs one job at a time; two cores sharing parts
conflict and neither runs.

<GameScene zoom="3" interactive>
<ImportStructure src="crafting_cpu.nbt" />
<DiamondAnnotation pos="2.5 1 1.5" color="#ff00e5ff">
Core, on the cable
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 0.5 0" color="#ff00e5ff">
Storage
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 1.5 0" color="#ff00e5ff">
Lane
</DiamondAnnotation>
<DiamondAnnotation pos="4 0.5 1.5" color="#ff00e5ff">
Cache
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:crafting_cpu_tier_1" />

- <ItemLink id="maxtech_storage:crafting_cpu_storage" /> adds job steps.
- <ItemLink id="maxtech_storage:crafting_cpu_lane" /> adds lanes (steps waiting on machines at once).
- <ItemLink id="maxtech_storage:crafting_cpu_cache" /> adds crafts per dispatch.

Tiers 2 and 3 need [research](research.md). Hold a part near a CPU to see where it fits.

A job takes the smallest free CPU that fits. It sends steps to [pattern providers](pattern_providers.md), collects the
outputs and delivers to storage. Active lanes cost MU; an idle CPU costs nothing, and without power it waits. If
something is missing the job turns partial and resumes when the items arrive.

<RecipeFor id="maxtech_storage:crafting_cpu_storage" />

<RecipeFor id="maxtech_storage:crafting_cpu_lane" />

<RecipeFor id="maxtech_storage:crafting_cpu_cache" />
