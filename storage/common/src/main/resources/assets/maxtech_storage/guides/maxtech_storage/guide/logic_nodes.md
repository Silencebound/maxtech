---
navigation:
  title: Node reference
  icon: minecraft:redstone_torch
  parent: logic_controller.md
  position: 20
---

# Node reference

Every node of the [node editor](logic_editor.md) palette. Hovering a node there shows its description and wires, so
the tables stay short. An empty label means the whole network (or the controller itself for redstone and link load).
Packs can add nodes with [scripts](logic_scripts.md).

## Events

Count and value events fire when a value **crosses** a limit; **switch-back** is where they re-arm.

| Node | Fires when | Outputs |
| --- | --- | --- |
| Item count crosses | an item count passes a limit | crossed, count |
| Power changes | power goes on or off | changed, powered |
| Crafting job finished | a job for the item ends | finished |
| Crafting job failed | a job stops with something missing | failed, missing item |
| Redstone changes | the strongest signal changes | changed, strength |
| Sensor value crosses | a [sensor](sensor.md) value passes a limit | crossed, value |
| Link overloaded | the busiest link passes a load % | overloaded, load |
| Labelled device added or removed | a labelled device joins or leaves | added, removed |
| Drive or cell full | a cell, bulk drive or chest fills | a cell filled, full cells |
| Timer | every 1 s to a day | fired |
| Graph starts | the graph is saved, switched on or loaded | started |
| Day or night begins | day or night starts | day began, night began |
| Weather changes | the weather changes | changed, weather |
| Items move in or out | items pass a labelled device | moved, amount |
| Player nearby | a player comes or leaves | came, left, players |
| Player opens a terminal | a terminal opens | opened |
| Run by another rule | another graph runs this one | run |
| Stock rule short | a [stock keeper](stock_keeping.md) rule can't get made | went short, recovered |
| Pool member turns idle | [pool](#machine-pools) members go idle | turned idle, count |
| Load shedding changes | [low power](energy.md#when-power-runs-low) pauses or resumes devices | changed, level |

Player nearby looks every <Config key="logic.playerCheckTicks" unit="ticks" /> and up to
<Config key="logic.playerRadiusMax" unit="blocks" /> away.

## Read

| Node | Gives |
| --- | --- |
| Item count, Count by tag, Fluid amount | amounts on the network or in labelled storage |
| Energy | MU stored, capacity, percent |
| Free storage | free and total cell bytes |
| Sensor value | one value of a labelled sensor |
| Device state | yes while a device is on |
| Redstone strength | strongest signal 0-15 |
| Pool status | members, idle, busy |
| Load shedding level | paused priorities |
| Crafting status | job running, waiting jobs, free CPUs |
| Stock rule status | a keeper rule's amounts and state |
| Task | a [display](displays.md) task's state |
| Shared variable | a value shared by name |
| Constant | number, yes/no, item or label |
| Parameter | a [rule input](#rules-with-parameters) |
| Daytime, Weather | time of day, 0 clear / 1 rain / 2 thunder |

Energy and Free storage are read at most every <Config key="condition.minIntervalTicks" unit="ticks" />.

## Check

| Node | Does |
| --- | --- |
| Compare | above, below, between or equal to a limit |
| Between | value within low and high |
| And, Or, Not, Either not both | logic |
| If | passes an event by a condition |
| Select | A or B |
| Math | add, subtract, multiply, divide, min, max, remainder |
| Step is | a [step sequence](#step-sequences) is at a step |
| For each | runs the nodes after it once per item |

### For each

Goes through an item tag, a list of up to 32 items or a stock keeper's rules. Wire "item" into Item count, Stock rule
status, Request craft, Move items, Send to pool or Cancel craft. Only nodes without memory may be in the branch. One
run handles at most <Config key="logic.forEachMaxItems" /> items.

### Rules with parameters

A **Parameter** node (name, kind, default; at most 8 per rule) makes a rule reusable. **Copy with values** adds a
switched-off copy with its own values that follows the original; **Detach** makes it independent. Run another rule can
fill parameters by name.

## Remember

These keep their value through saving. A wire looping back through one of them or a Delay is read one run later.

| Node | Does |
| --- | --- |
| Latch | set / reset (reset wins) |
| Toggle | flips yes/no |
| Counter | up, down, reset |
| Remember value | keeps the value at "store" |
| Turns yes or no | fires when a yes/no changes |
| Statistics | average, lowest, highest |
| Change per second | rate of a number |
| Step sequence | 2-16 named steps |

### Step sequences

A step goes on when its "go on when" turns yes or after its time; "go to while" pulls the sequence to a step. Reset
wins, then the lowest "go to", then "go on when", then time. Step is finds a step by name or number.

## Act

Actions run together at the end of a run and change only devices the controller's owner could change by hand.

| Node | Does |
| --- | --- |
| Switch device | ports, interfaces, providers, sensors off or on |
| Set priority, Set power priority | drive priority; power priority 1 essential to 5 optional |
| Redstone out, Pulse redstone | strength of a [condition switch](condition_switch.md) |
| Request craft, Cancel craft | start or cancel crafts of an item |
| Move items | between storage and ports or interfaces |
| Send to pool | shares items over idle pool members |
| Set port filter | a [port](ports.md) filter slot |
| Notify | log, toast, chat or display |
| Write to display | text with {a} and {b} |
| Set task | ticks or opens a display task |
| Set shared variable | number or yes/no |
| Switch stock rule, Set stock amount | a keeper rule on or off; keep, restart, batch |
| Run another rule | runs a graph of this controller |

Limits: <Config key="logic.maxVariables" /> variables, rules calling rules <Config key="logic.maxRuleDepth" /> deep,
a display written every <Config key="logic.displayWriteTicks" unit="ticks" /> at most.

## Machine pools

A pool is every [port](ports.md) and [interface](interface.md) with one label. Set an **idle value** on the node
(`state.lit` equal to 0 for a furnace, or a [pack reader](pack_readers.md) value); with none, all members are idle.
Send to pool shares by first idle, round robin, least full or evenly. Values update on block changes, so put a labelled
[sensor](sensor.md) with polling on a machine whose values change without one.

## Redstone channels

Redstone changes, Redstone strength, Redstone out and Pulse redstone have a **Channel** setting: a colour picks one of
the 16 [redstone channels](condition_switch.md) instead of the label.

## Time

| Node | Does |
| --- | --- |
| Delay | fires after 1 tick to 1 hour |
| Cooldown | lets at most one event per interval through |
| Wait until | holds events until a condition is yes |
