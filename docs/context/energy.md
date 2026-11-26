# Context pack: energy

Orientation for the MaxTech energy system (MU) in Lib and how Storage runs on it. Lib has no guide of its own, so this
page is where Lib's energy system and its config are written down; players read Storage's guide page
`energy.md`. Plan and as-built notes: `milestones/MLE.md` (milestone notes) and `milestones/MLE/item-01.md` (milestone notes) to `item-05.md`.

## Lib

Api (`lib/common/src/api/.../lib/api/energy/`, `@ApiStatus.Experimental` until the machines mod uses it):

| Type | Role |
| --- | --- |
| `EnergyEndpoint` | One block's part in a network: `producer()`, `consumer(priority)` or `buffer(capacity, in, out, priority)`. It declares supply or demand per tick; a buffer's stored MU lives in the endpoint and settles lazily from its flow. The owning block saves it. |
| `EnergyNetwork` | One network's endpoints and its pass. Created and filled by the hosting mod (Storage: one per storage network); `readout()` gives the diagnostics. |
| `EnergyScheduler`, `EnergyNetworks` | One scheduler per server level (`EnergyNetworks.of(level)`); runs woken networks and FE edges at the end of the tick. `ENDPOINT_CAPABILITY` (`maxtech_lib:energy_endpoint`) lets Jade, the scanner and other readers find a block's endpoint. |
| `EnergyPriority` | Priorities, highest served first; `LEFTOVERS` is served last (Storage's energy cells). |
| `EnergyReadout` | Supply, demand, delivered, battery flows, stored and capacity, supplied fraction, time to full or empty, last pass cost. Read from fields the pass fills; reading never wakes a network. |
| `EnergyEdge`, `EdgeSettings` | Another mod's block as a producer and a consumer endpoint; `lib.energy.FeEdge` reaches its FE capability through a capability cache, one root transaction per move. |
| `FeRate`, `FeConverter` | The FE rate as a ratio of two ints; both directions round down and the remainder carries to the next transfer, so a round trip never gains. |

How it runs:

- **Energy lives in blocks.** The network owns none; it only computes flows.
- **One pass per woken network per tick.** Supply is shared from the highest priority down, in proportion to demand,
  so every endpoint of a level gets the same fraction when supply is short (linear brownout). Buffers give only when
  producers fall short, never to other buffers.
- **Sleep.** A network whose supply and demand did not change sleeps and costs nothing per tick. It wakes on a
  declaration change, an endpoint joining or leaving, a pause change, or the predicted tick a buffer fills or empties.
- **FE edges.** An edge whose two flows differ runs every tick (FE has no change signal); an idle one looks at its
  neighbour every `energy.edgePollTicks` ticks and at once when the capability cache is invalidated.
- **Perf budgets** (GameTests `perf_energy_pass_500`, `perf_energy_sleeping_500`): a full pass over 500 endpoints under
  100 µs, a sleeping network 0 work per tick. Never loosen them.

## Storage on MU

- One energy network per storage network (`network/StorageEnergy`), paused with it in `pause` mode. Members: the
  buffer operations pay from (the controller in charge, the ad-hoc buffer or a subnet controller's) at default
  priority, the energy cells at leftovers priority, fuel generators touching the network (devices that, like the
  cells and the creative source, use no channel; owner 2026-10-17) and the cells' FE edges.
- Storage's buffers give nothing to the network; only generator supply moves through the pass. Operations pay inside
  their transactions from the buffers as before (`OperationCost`), so a short supply slows devices to what it pays for.
- Load shedding (`PowerPriority`, `loadShedding.*`) pauses devices by priority as stored MU falls, on top of the pass.
- Every Storage block and carried item also shows FE through `energy/FeView` (Lib's rate, export settings apply).
- Saves from before MLE are converted by `StorageSaveFormats.FE_TO_MU` at the rate in effect when the step runs.

## Config keys

Lib server config, defaults in both of Lib's presets:

| Key | Default | Meaning |
| --- | --- | --- |
| `energy.feRateFe` | 1 | FE side of the rate: `feRateFe` FE are worth `feRateMu` MU. |
| `energy.feRateMu` | 1 | MU side of the rate. |
| `energy.exportEnabled` | true | Whether MU may go out to other mods' blocks as FE. Off, FE still comes in. |
| `energy.exportPerEdge` | 0 | Most MU per tick sent to one other mod's block, and handed out by one block's FE capability; 0 is no limit. |
| `energy.exportPerNetwork` | 0 | Most MU per tick one network sends out in total; 0 is no limit. |
| `energy.edgePollTicks` | 20 | Ticks between looks at an idle neighbour FE block. |

Storage server config, MU unless noted, defaults in both of Storage's presets:

| Keys | Meaning |
| --- | --- |
| `network.energy.perOperation`, `perUnit`, `fluidMbPerUnit` | Cost of an operation: a flat amount plus an amount per item or per `fluidMbPerUnit` mB. |
| `controller.bufferMu`, `controller.inputPerTick` | Buffer and intake per controller block; the multiblock holds the sum. |
| `network.adHoc.bufferMu` | Buffer of a network without a controller (not saved). |
| `network.subnet.bufferMu`, `network.subnet.inputPerTick` | A subnet controller's buffer and intake. |
| `energy.cells.tier1..3.capacity`, `maxTransfer` | Energy cell capacity and MU one transfer may move in or out. |
| `machines.energyCapacity`, `maxEnergyInput`, `fuelGenerator.energyPerTick`, `fuelGenerator.maxEnergyOutput`, `modules.speedEnergyPercent`, `modules.voidEnergyPercent` | Machines and the fuel generator. |
| `crafting.assemblerEnergyPerCraft`, `crafting.cpu.tier1..3.muPerLane` | Crafting costs. |
| `services.mail.muPerStack`, `devices.dimensionBridge.drainPerTick`, `surchargePerOperation` | Service and device costs. |
| `p2p.energyLossPercent` | Percent a P2P energy link loses. |
| `alarms.lowPowerPercent`, `loadShedding.*` | Low-power alarm and load shedding thresholds (percent of capacity). |
| `cells.portable.*`, `signal.wireless.*`, `crafting.tablet.*`, `logic.remote.*`, `tools.goggles.*`, `tools.layer.*` (`energyCapacity`, `maxInput`, per-use costs) | Carried items. |

Keys renamed from FE for 0.7.0 are read for one more version while set (`MovedKeys`; both presets set them to -1):
`network.adHoc.bufferFe`, `controller.bufferFe`, `network.subnet.bufferFe`, `services.mail.fePerStack` and
`crafting.cpu.tier1..3.fePerLane`. A value other than -1 wins over the new key and logs a warning.

Every energy key is in every built-in preset, not only `standalone.json`: the `presets_complete` GameTests of both mods
fail otherwise (`ServerConfig.presetProblems`, `StorageServerConfig.isEnergyKey`). Name a new energy key so
`isEnergyKey` sees it (`energy` or `power` in the path, `bufferMu`, `muPer*`, `inputPerTick`, `maxInput`), or extend
that check.
