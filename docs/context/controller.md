# Context pack: controller

Orientation for the multiblock controller (M7R), its cards and built-in recorders, alarms, the controller screen and
the network event hooks. Paths are
under `storage/common/src/main/java/dev/silencebound/maxtech/storage/`. Feature details:
`milestones/M7C/state.md` (milestone notes) (alarm panel, production stats), `milestones/M7R.md` (milestone notes) and `storage/PRD.md` §7.2. Verified API facts:
`.claude/skills/neoforge-26-1/storage-facts.md`.

## Types

| Type | Role |
| --- | --- |
| `controller/ControllerBlock`, `ControllerBlockEntity` | A block of the multiblock controller. One block, the primary, holds the state (MU buffer, name, security, craft limits, cards, recorders, `AlarmCenter`, recovery bin); the others point to it. A capped flood fill over controller blocks runs only when one is placed or broken (a scheduled block tick). Never ticks. |
| `controller/ClusterStats` | Pure sums: buffer and input per active block, card effects, size steps. |
| `controller/card/ControllerCards`, `CardKind`, `UpgradeCardItem` | The upgrade card slots (batch amplifier, efficiency core, surge regulator), saved with the primary. |
| `controller/recorder/ControllerRecorders`, `LedgerRecorder`, `AlarmWorker`, `RecordsPanel` | The built-in telemetry, ledger and redstone alarm rules, config-gated; views in the History tab and the terminal. |
| `controller/module/TelemetryRecorder`, `HistoryRings<K>` | Fixed-size rings per series and tracked key, allocated once, sampled on a task every `controller.recorders.telemetrySampleTicks`. Keys are tracked on demand and released when idle. |
| `controller/module/ServiceDeviceBlock(Entity)` | The network devices that were expansions: security module, network anchor, antenna mount, wireless access mount, dimension bridge. One channel each; the network counts them per `NetworkService`. |
| `controller/module/TelemetryDisplayHistory`, `LedgerBook` | Display graphs from the recorder; the ledger's per-player in and out per day. |
| `controller/module/ExpansionBlock(Entity)`, `ModuleKind` | Retired expansion blocks, registered only so old worlds load them. `ModuleKind` indices are saved and never reused. |
| `controller/alarm/AlarmCenter`, `Alarm`, `AlarmKind` | The alarm panel, kept by the controller and saved with it. Listens to energy, committed contents, fill, devices, `JobEvents` and logic pauses; looks in a task at most every `condition.minIntervalTicks`, content and stuck-craft checks at most every `alarms.recheckTicks`. A raise goes to the network log and, with `alarms.chat`, to chat. |
| `analyzer/NetworkOverview` | Immutable record of everything the Network tab shows. Built on the server from cached state, lists capped, sent only when `equals` differs. The history section carries the production lines. |
| `controller/client/ControllerScreen` | Tabs Network, History, Crafting, Alarms, Recovery, Cards. |
| `controller/recovery/RecoveryBin`, `CellRecovery`, `RecoveryCommand` | The recovery bin (M7C item 20): void overflow and destroyed cells, saved with the controller. |
| `controller/ControllerMenu`, `NetworkTabPayload`, `NetworkRenamePayload`, `alarm/AlarmTabPayload` | Menu data and payloads. |

## Screen layout

`ControllerScreen` draws tab `i` at `LIST_X + i * TAB_STEP` with `TAB_WIDTH = 38` and `TAB_STEP = 40`: six tabs
(Network, History, Crafting, Alarms, Recovery, Cards) fill the 240 between the frame's edges. **The tab row is full.**
A seventh tab needs a layout change (icons or a second row). Put new information in a section of the Network tab (production stats did)
unless the owner asks for a tab.

## Event hooks (`network/NetworkState.java`)

All on the server thread. Watchers subscribe per network and must unsubscribe in `unbind`/`networkChanged`; most do not
follow a merge or split, so their owner moves them.

- `watch(ContentsWatcher)`: every committed total change. A busy network has one every tick, so mark and look later.
- `listenKey(key, KeyListener)`: the total of one key after a commit. Cheap; the normal choice for counts.
- `watchMoves(MoveWatcher)`: every committed move with its player (ledger).
- `listenItemMoves(key, ItemMoveListener)`: committed moves with the moving device (logic, production). Only listened
  keys are recorded, so with no listener a transfer pays two emptiness checks. **A `null` key (the any-key listener)
  makes every transfer record its move** and made `perf_storage_idle_ports` fail in #146. Start it lazily, only while
  something looks, and remove it afterwards.
- `watchDevices`, `watchEnergy`, `watchLoad`, `watchFill`, `watchShedding`, `watchNeighbours`, `watchTerminals`,
  `watchDisplayNotices`: coarser events. Energy, fill and load can fire every tick, so evaluate on a cadence.
- `crafting/job/JobEvents`: per network `watchFinished`, `watchFinishedKeys` (the job's item), `watchFailed` (stopped
  with something missing) and `watchDone` (done jobs only; the task board's crafted goal). Held weakly per network.
- `network/NetworkNotices`: the network's unsaved log of the last lines. The logic notify node and `AlarmCenter` post to
  it; logic remotes ping from it.

## Persistence pattern

`ControllerBlockEntity.saveAdditional/loadAdditional` use `ValueOutput`/`ValueInput`. Write a field only when it differs
from its default (security, craft limits, alarms, network name); store codec types with `output.store(KEY, CODEC, v)`.
Settings that must travel with the block also go through the item (security, buffer, alarm rules). The ledger and the
alarm rules are saved with the primary; the telemetry history is not saved and restarts empty.

## Config and presets

Keys live in `config/StorageServerConfig.java` (for example `alarms.recheckTicks`,
`controller.recorders.telemetrySamples`), each with a default in both
`storage/common/src/main/resources/maxtech_storage/presets/standalone.json` and `pack.json`, read through
`CONFIG.intDefault`. A new key is the definition, both presets and the config GameTest still passing.

The controller's keys: `controller.bufferMu` (MU; `controller.bufferFe` read for one version), `controller.inputPerTick`, `controller.size.*` (base, the three research
steps, max), `controller.cards.*` (slots, limits per kind, and each card's numbers under
`controller.cards.batchAmplifier`, `.efficiencyCore`, `.surgeRegulator`) and `controller.recorders.*` (the three
switches, `alarmRules`, `telemetrySampleTicks`, `telemetrySamples`, `telemetryMaxKeys`, `ledgerDays`,
`ledgerMaxRows`). The network devices' numbers are under `devices.networkAnchor` and `devices.dimensionBridge`.

To move a key, declare the new one, keep the old one for a version with `MovedKeys` (a value of -1 means unset; both
presets set -1) and say so in the changelog. M7R item 8 moved `controller.modules.*` and `services.ledger.*` that way;
the old keys go in the version after.

## PRD 8.1 traps seen here

- The any-key move listener (above).
- Alarm checks that scan contents on every change: gate them by `alarms.recheckTicks`.
- Searching for the multiblock's blocks on load: the primary saves its active and dark blocks, so nothing is
  searched.
- Rebuilding screen rows on every frame: rebuild only when a new `NetworkOverview` arrives.
- Budgets in `ControllerPerfGameTests`, `HistoryPerfGameTests` and `NetworkPerfGameTests` stay as they are.

## How to add X

**A card kind:** `CardKind` entry (with the retired `ModuleKind` index it counts under, or a new index), its limit
and numbers under `controller.cards.*` in both presets, the effect in `ClusterStats` (JUnit), the item in
`CardContent`, research gate like its siblings, guide row, a GameTest in the style of `ControllerCardGameTests`.

**A network device:** a `ServiceDeviceBlock` with its own `NetworkService`, the service read from the network's
counts, never from the controller's blocks; a GameTest in the style of `ServiceDeviceGameTests`.

**An alarm kind:** `AlarmKind` entry with the next id (ids are saved), its condition and event subscription in
`AlarmCenter` (mark dirty on the event, evaluate in the task), text in `controller/alarm/AlarmTexts`, tests in the
style of `ControllerAlarmGameTests` (raise, ack, re-raise, save/load) and, for the panel, `AlarmPanelGameTests`.

**A Network-tab section:** extend `NetworkOverview` (record, stream codec, `equals`, caps), build it where the others
are built, draw it in `ControllerScreen`, texts through lang keys, and a test that the overview carries it.
