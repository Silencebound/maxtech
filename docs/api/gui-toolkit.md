# Building a screen with the GUI toolkit

How a mod gives a block a screen in the MaxTech look against Lib's api jar alone (ADR-0016): slots, tanks, an energy
bar, progress, a status line, all declared once. Lib builds the server menu, the client menu, the value sync and the
screen; the mod writes no menu class, no screen class and no client code. Everything named here is in
`lib.api.menu`, `lib.api.inventory`, `lib.api.style` or `lib.neoforge.api.inventory`, and is experimental until Farms
ships (`docs/api/lib-1.0.md`). Lib's dev-only demo machine, `ToolkitDemo` in `lib/neoforge/src/gametest`, uses every part
below, and the `toolkit_api_*` GameTests open it through the api only. In a dev client run `/maxtech_lib_demo toolkit`
(gamemaster) to place one in front of you.

## The parts

- **The host** is the server-side object the menu shows, usually the block entity. It owns the state: an `ItemSlots`,
  `FluidTank`s, an `EnergyEndpoint`, and the plain numbers the menu reads.
- **A `MenuDefinition<H>`** is made once with `Menus.define(modId, name, hostClass, body)`, usually in a static field. The
  body declares the parts on a builder and sets a layout; each declaration returns a handle the layout uses.
- **Values** are read from the host by getters (`m.progress(current, total)`, `m.energy(getter)`, `m.intValue(getter)`,
  `m.enumValue(type, getter)`, `m.tank(getter)`). Lib reads them on the server every tick the menu is open and sends
  only what changed, in one payload; the screen reads them from a `MenuValueView`. Getters run on the server thread.
- **Inventories** are `ItemSlots` built from an `ItemSlots.Builder` that gives each slot a role (`input`, `output`,
  `display`), a filter and a limit. `m.items(getter, shape)` takes the host's inventory and the same shape (the client
  menu builds an empty copy from it); `m.slots(items, from, to)` makes a group of slots to place in the layout.
  An `input` slot takes what its filter accepts; an `output` slot only gives (players take, never put); the same roles
  apply to hoppers and pipes through `Handlers.items(slots)`.
- **Tanks**: `m.tank(host -> host.tank)` for a `FluidTank`: a click with a bucket or other fluid container on the gauge
  fills the container from the tank or empties it into the tank, and the server does it. `m.tank(kind, getter)` shows a
  `ResourceTank` of any other kind without clicks. Contents that no longer load show as "Unknown" with their amount.
- **Shift-click**: `m.shiftClick(groups...)` names the groups shift-clicked stacks go to, in order, each filled before
  the next. Groups not named never receive shift-clicks; a stack no group takes moves between inventory and hotbar.
- **Theme**: `m.theme(new AccentTheme(accent, accentDim, barTop, barBottom, text, heading))` (default
  `AccentTheme.LIB`, the Storage cyan). Title: `m.title(langKey)` (default `container.<modId>.<name>`).
- **Open and close rules**: by default a block entity host keeps the menu open while the block stands and the player is
  in reach; `m.validWhile((host, player) -> ...)` replaces that.

## Layout

`Layout` nodes are plain data: `row`, `column` (with a gap or the default), `slots(group, columns, rows)`,
`tank(value, width, height)`, `energy(value, height)`, `progress(value, ARROW | FLAME)`, `label(key, width)`,
`value(intValue, key, width)` (the lang text gets the number as `%s`; experimental
`value(intValue, key, width, shownWhenNonZero)`, see below), `status(enumValue, width)` (the enum implements
`MenuStatus`: a lang key and a tone, which colours the dot), `spacer` and `playerInventory()`. Lib places everything,
slots included, from the layout; text is fitted into its box and never moves anything.

**Hiding a value line.** `Layout.value(value, key, width, shownWhenNonZero)` (experimental) shows the line only while
another `IntValue` of the same definition (or the line's own) is not 0, e.g. a hub's "Sampler: N%" while a sampler is in.
The condition is synced like any value. The box keeps its size and place while hidden, so nothing else moves and the
server and clients still agree on the geometry; the screen draws and narrates nothing for a hidden line.
`Layout.Value#shownIn(MenuValueView)` answers it; Lib's screen asks it, a custom screen that draws a definition's
value lines itself must call `shownIn` before drawing or narrating one, and tests ask it too (`MenuTesting` sessions give
the client's view).

Rules the definition checks (`MenuDefinition.problems()`):

- the panel and its title tab must fit 320 by 240 pixels, no two boxes may overlap;
- every slot group is placed once, in enough cells, and no two groups share a slot;
- the player's inventory is placed at most once; status lines show an enum that implements `MenuStatus`;
- every handle comes from this definition.

**Problems throw in dev runs** (`IllegalStateException`, at the definition or at `register` for one built before Lib's
construction); in a built game the definition keeps working and the problem is logged. So a layout that passes in dev
passes on every client.

**Layouts and inventory shapes must not depend on config, the side or any other state that can differ between a server
and its clients.** The server and each client build the menu from the same definition and must place the same slots at
the same positions; build both from constants only. Values may of course come from config, since they are only read on
the server.

## Registering and opening

Declare the `maxtech_lib` dependency with `ordering = "AFTER"` in `neoforge.mods.toml`, then register during mod
construction (the same moment as other `Registrars`):

```java
public final class LabBlockEntity extends BlockEntity {
    static final ItemSlots.Builder SLOTS = ItemSlots.builder(11)
            .input(0, 1, stack -> stack.is(Items.RAW_IRON))   // filtered input
            .input(1, 2, stack -> stack.is(Items.COAL))       // fuel
            .output(2, 11);                                   // 3x3 output, take only

    final ItemSlots slots = SLOTS.build(slot -> setChanged());
    final FluidTank tank = FluidTank.create(4000, this::setChanged);
    final EnergyEndpoint energy = EnergyEndpoint.buffer(10_000, 0, 0, EnergyPriority.LEFTOVERS);
    int progress;

    static final MenuDefinition<LabBlockEntity> MENU = Menus.define(MOD_ID, "lab", LabBlockEntity.class, m -> {
        MenuItems<LabBlockEntity> items = m.items(host -> host.slots, SLOTS);
        MenuSlots input = m.slots(items, 0, 1);
        MenuSlots fuel = m.slots(items, 1, 2);
        MenuSlots output = m.slots(items, 2, 11);
        TankValue tank = m.tank(host -> host.tank);
        EnergyValue energy = m.energy(host -> host.energy);
        ProgressValue arrow = m.progress(host -> host.progress, host -> 100);
        m.theme(MINT);
        m.shiftClick(input, fuel);
        m.layout(Layout.column(
                Layout.row(Layout.tank(tank, 18, 54),
                        Layout.column(Layout.SMALL_GAP, Layout.slots(input, 1, 1), Layout.slots(fuel, 1, 1)),
                        Layout.progress(arrow, Layout.ProgressShape.ARROW), Layout.slots(output, 3, 3),
                        Layout.energy(energy, 54)),
                Layout.playerInventory()));
    });
}
// during mod construction:
LabBlockEntity.MENU.register(Registrars.create(MOD_ID, Registries.MENU));
// in the block's useWithoutItem, on the server:
LabBlockEntity.MENU.open(serverPlayer, blockEntity);
```

Expose the same inventories to the world with `Handlers.items(slots)` and `Handlers.fluids(tank)` in a
`RegisterCapabilitiesEvent`. Save them with `slots.save(out.child("slots"))` and `slots.load(...)`; drop them with
`slots.dropContents(level, pos)` in `preRemoveSideEffects`.

Lang: the title key, label and value keys, and each status constant's key are yours. Update values from a task in
Lib's scheduler group of the level (`TaskSchedulers.newGroup`), never from a ticker; the screen follows by itself.

## Testing menus

`MenuTesting` (`lib.api.menu`, experimental, for GameTests only) opens a definition for a mock server player, which has
no connection, and captures the values payloads that would go to it:

```java
ServerPlayer player = helper.makeMockServerPlayerInLevel();
try (var session = MenuTesting.open(LabBlockEntity.MENU, player, lab)) {
    lab.progress = 40;
    session.tick();                                   // one server tick of the menu
    session.clientValues().current(arrow);            // what a screen would read: the payloads applied to a client menu
    session.serverValues().amount(tank);              // or what the server read last
    session.pressTankButton(tank);                    // a bucket click on the gauge; true if fluid moved
    session.shiftClick(input, 0);                     // slot 0 of a group; shiftClickPlayerSlot(i) for the player's
    session.menu().setCarried(new ItemStack(Items.BUCKET));   // vanilla's menu for everything else
}
```

Close the session when the test ends; remove the player yourself. `tick()` counts its own ticks, so two calls in one
game tick send two payloads, also in a frozen level. Never use it in a running game.
