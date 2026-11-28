# `lib.api.schedule`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.schedule`** | Budgeted per-level work queues: groups with time and operation budgets, priorities, wake-on-event and backoff. Moved from `lib.schedule` for Farms (FM1). | experimental |
| `TaskSchedulers` | An addon's entry point: `newGroup(level)` with Lib's configured budgets. | experimental |
| `ScheduledTask`, `TaskGroup`, `TaskPriority` | A task, the group that runs it within its budget, and its queue. | experimental |
| `TaskScheduler` | The level's scheduler; its constructor, `tick`, `timers`, `setRunListener` and the `RunListener` interface it takes (task run times, for Lib's profiler) are Lib's (`@ApiStatus.Internal`). | experimental |
