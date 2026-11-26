---
navigation:
  title: IO Port
  icon: maxtech_storage:io_port
  position: 31
item_ids:
  - maxtech_storage:io_port
---

# IO Port

The <ItemLink id="maxtech_storage:io_port" /> empties cells into the network or fills them from it in bulk. It takes a
channel like any device.

<GameScene zoom="3" interactive>
<ImportStructure src="io_port.nbt" />
<DiamondAnnotation pos="1.5 0.5 0" color="#ff00e5ff">
IO port
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 0.5 0" color="#ff00e5ff">
Drive
</DiamondAnnotation>
</GameScene>

Put cells in the six input slots on the left, by hand, hopper or pipe; finished cells go to the six output slots on the
right, where automation takes them out.

- **Unload** moves a cell's contents into the network.
- **Load** fills a cell from the network. The cell's own rules decide what it takes, so a partitioned cell takes only
  what its filter allows.

The mode tab sets the mode and when a cell moves to the output: as soon as it is **empty**, as soon as it is **full**, or once
nothing more moves (**done**). "Empty" and "full" eject a matching cell at once, before any work: in load mode
"empty" passes empty cells straight through. A cell that cannot be read goes straight to the output.

The port makes <Config key="ioPort.operationsPerTick" /> operations per tick, one batch each, and pays energy like a
port. Each speed card multiplies that by <Config key="ioPort.speedCardMultiplier" />. With no cell to work on it sleeps.
It keeps its cells, cards and settings when picked up.

**Redstone** (mode tab): ignore, active with a signal, active without one, or **pulse**, one operation per rising
edge.

<RecipeFor id="maxtech_storage:io_port" />
