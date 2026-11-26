---
navigation:
  title: The controller
  icon: maxtech_storage:storage_controller
  position: 15
item_ids:
  - maxtech_storage:storage_controller
  - maxtech_storage:batch_amplifier_card
  - maxtech_storage:efficiency_core_card
  - maxtech_storage:surge_regulator_card
  - maxtech_storage:controller_bandwidth_array
  - maxtech_storage:controller_batch_amplifier
  - maxtech_storage:controller_device_bus
  - maxtech_storage:controller_efficiency_core
  - maxtech_storage:controller_surge_regulator
  - maxtech_storage:controller_telemetry_recorder
  - maxtech_storage:controller_alarm_module
  - maxtech_storage:controller_ledger_module
---

# The controller

The <ItemLink id="maxtech_storage:storage_controller" /> runs a network. Controller blocks placed face to face form one
controller. Each face that touches a cable or device is a root link with
<Config key="network.channels.rootLink" unit="channels" />, and each block adds a
<Config key="controller.bufferMu" unit="MU" /> power buffer. Use any block to open its screen: status, power, devices,
warnings.

A network has one controller; a second goes on standby. To join networks that keep their controllers, use a
[subnet controller](subnets.md).

<RecipeFor id="maxtech_storage:storage_controller" />

## Size

A controller takes up to <Config key="controller.size.base" /> blocks; Controller Size [research](research.md) raises
that to at most <Config key="controller.size.max" />. Hold a controller block nearby to see where it can grow.

## Upgrade cards

The Cards tab has <Config key="controller.cards.base" /> slots, plus
<Config key="controller.cards.perBlock" /> per extra block. It needs the Upgrade Cards research.

- <ItemLink id="maxtech_storage:batch_amplifier_card" />: more items or mB per operation.
- <ItemLink id="maxtech_storage:efficiency_core_card" />: operations cost less MU.
- <ItemLink id="maxtech_storage:surge_regulator_card" />: keeps a reserve of MU for players.

<RecipeFor id="maxtech_storage:batch_amplifier_card" />
<RecipeFor id="maxtech_storage:efficiency_core_card" />
<RecipeFor id="maxtech_storage:surge_regulator_card" />

<a name="history-telemetry-ledger-and-redstone-alarms" />

## History: telemetry, ledger and redstone alarms

The History tab shows graphs of operations and MU, the [ledger](network_services.md#ledger), and up to
<Config key="controller.recorders.alarmRules" /> redstone alarm rules that make the controller output a signal (low
power, storage nearly full, a finished craft and more). Each page needs its own research.

## Alarms

The Alarms tab lists low power, full storage, stuck crafts, logic errors and offline drives. A
[display](displays.md#alarm-panel) can show the list.
