# Context pack: logic

Orientation for the Logic Controller, rules, sensors, the logic remote and the network log. Paths are under
`storage/common/src/main/java/dev/silencebound/maxtech/storage/` unless noted. Design and as-built notes:
`milestones/M7L/context.md` (milestone notes) (areas A to E), `milestones/M7L.md` (milestone notes), `M7L2.md` and `M7L2/`.

## Layers

| Layer | Types | Role |
| --- | --- | --- |
| Pure rule core (`logic/`) | `LogicGraph<K>`, `LogicNode`, `Wire`, `NodeType`, `NodeTypes`, `Op`, `SettingSpec`, `LogicCompiler`, `CompiledGraph`, `LogicEvaluator<K>`, `LogicMemory` | No level access; `K` is `StackKey` in game and `String` in JUnit. Node type and setting ids are saved: never change them. `Parameters`, `RuleCalls`, `Pools`, `Sequences`, `ForEach` are pure helpers. |
| Context | `LogicContext<K>` | What a run reads and how its actions leave; implemented by the controller, faked in tests. |
| Controller (`logic/controller/`) | `LogicControllerBlock(Entity)`, `LogicWorker`, `GraphSlot`, `RuleInstance`, `TeamBudget`, `SensorSource`, `ControllerLog`, `LogicAction`, `LogicEvents` | The device. `LogicWorker` holds the graphs, compiles them, subscribes to exactly what running graphs watch (`Watch`, `WatchKind`) and runs them as a task on the network scheduler under the team budgets. |
| Sensors (`sensor/`) | `SensorBlock`, `SensorPart`, `SensorWorker`, `SensorValue`, `SensorRef`, `MachineReader` | Read the block in front and expose named values; `SensorSource` is what the controller reads. Event driven; reads cost checks. |
| Remote (`logic/remote/`) | `LogicRemoteItem`, `LogicRemoteMenu`, `RemoteNotices` | Bound to a network; shows notices, pauses and resumes rules; pings on new notices, at most once per player per interval. |
| Log | `network/NetworkNotices` (unsaved, last lines), `ControllerLog` | Notify nodes and failed actions post; notices sent to the `displays` channel feed the logic-notices display source. |
| Editor and sharing | `logic/editor/`, `logic/template/`, `logic/controller/GraphSharing`, `logic/admin/`, `RuleCards` | Node editor, templates, cards, admin list. |

## Event and threading model

Server thread. Compile is pure and allocates; `LogicEvaluator.run` allocates nothing. A listener computes the watched
value, hands it to the evaluator only if it changed and `LogicEvaluator.matters` says it could change something, then
wakes the task. The task runs each graph with work at most once per pass; a delay asks for one timed wake; a loop asks
for the next tick. Energy, free storage and link load can change every tick, so they are only marked and looked at once
per `condition.minIntervalTicks`. With the network not online nothing runs, and every graph runs once when it is back.
Budgets: `logic.teamChecksPerSecond` (nodes run, sensor reads) and `logic.teamActionPointsPerSecond` (actions); a graph
over budget waits for the next second, and player work runs first in the scheduler.

## How features expose values to logic

- **A value read by name** (sensors, machine readers): add a `SensorValue` with a stable dotted id in the sensor or
  reader code. Logic reads it through `SensorSource`. Notify listeners only when the value changed.
- **A network value** (energy, bytes, craft status): add a `WatchKind`, handle it in `LogicWorker`'s subscribe and
  unsubscribe paths (search for `network.watch` and `listenItemMoves`) and add a read to the controller's
  `LogicContext` implementation.
- **An item count or move**: `listenKey`, or `listenItemMoves(key, ...)` with a key. Use `null` only when a graph truly
  watches every item, and remove the listener when no running graph needs it.
- **Something logic should change** (a task, a setting): an action (`LogicAction`) that the controller applies after the
  run.

## PRD 8.1 traps seen here

- Polling, or `getCapability`/`getBlockEntity` per tick for sensors; `SensorWorker` caches handler lookups.
- The any-key move listener for a graph that watches one key: every transfer then records its move (#146).
- Allocation in `LogicEvaluator.run`.
- Runs that skip charging: charge both checks and action points. Guards: `LogicCostBenchGameTests`,
  `LogicControllerPerfGameTests`, `LogicHookPerfGameTests`, `SensorPerfGameTests`.

## How to add X

**A node type:** an `Op` entry, the `NodeType` in `NodeTypes` (ports, settings, cost; id fixed forever), the evaluator
`switch` case, lang in `LogicLang`/`LogicTexts`, a `NodeSentence` for the sentence view, palette placement, JUnit for
the pure behaviour, then a GameTest in the style of `LogicReadNodeGameTests`. If it reads or acts on the world, extend
`LogicContext` and the controller's implementation.

**An action:** a `LogicAction` kind, executed in the controller after the run (a request: moves go through transfer
transactions and count only what moved), its action point cost, the "Would ..." wording for simulate mode, and a test
in the style of `LogicActionNodeGameTests`.

**A sensor value or reader:** see above; test in the sensor GameTests and keep `SensorPerfGameTests` in budget.

## GameTest patterns

- A class with `@EventBusSubscriber`, a `static` block calling `TestStorage.init()`, `StorageTestEnvironment.init()` and
  `register(name, fn)`, and a `RegisterGameTestsEvent` handler (structure `empty_42x4x27`; see `LogicReadNodeGameTests`).
- Set-up: `LogicControllerGameTests.setUp(helper, owner)` builds a powered controller at x 1, a test device at x 2 and a
  logic controller at x 3 owned by a mock player. `network(...)`, `entity(...)` and `crossesNotify(...)` are shared.
- Assert on actions and notices, not on timing. Wait `condition.minIntervalTicks` plus a margin (the tests use 25) for
  sampled values. Count runs to prove "runs once per change, not per tick". Break each new test once.
