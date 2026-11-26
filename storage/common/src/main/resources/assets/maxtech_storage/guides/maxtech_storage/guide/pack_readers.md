---
navigation:
  title: Pack readers
  icon: minecraft:writable_book
  parent: sensor.md
  position: 10
---

# Pack readers

For pack makers. A **pack reader** is a JSON data file that names values in a block's saved data (progress, heat, a
mode), so a [sensor](sensor.md) lists them as `data.<name>` and logic can use them.

Put it at `data/<namespace>/maxtech_storage/readers/<path>.json`; `/reload` loads it and sensors pick it up on their
next read.

```json
{
  "neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": "examplemod"}],
  "blocks": ["examplemod:tank", "#examplemod:machines"],
  "values": {
    "stored": {"path": "Tank.Amount", "unit": "mB"},
    "working": {"path": "Active", "type": "yes_no"},
    "progress": "Progress"
  }
}
```

A value is a path or an object with `path`, `unit`, `type` (`number`, `yes_no`, `text`) and `scale`. Paths follow
`/data get block`: `Energy.stored`, `Tanks[1].Amount`. A missing path reads 0 and shows **value not found**.

## Costs

A saved-data read costs <Config key="sensor.savedDataCost" /> extra checks of the team's
[logic budget](logic_budget.md) and happens at most every <Config key="sensor.savedDataMinIntervalTicks" unit="ticks" />.

Built in: furnaces (`cook_time`, `cook_total`, `burn_left`, `burn_total`) and the brewing stand (`brew_left`, `fuel`).
