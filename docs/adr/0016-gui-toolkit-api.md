# ADR-0016: GUI toolkit api

Date: 2026-11-27
Status: Accepted

## Context

Every MaxTech screen draws the Accent Frame look with Lib's widget set, which lived in `lib.client.gui`, an internal
package. Storage could use it because it compiles against all of Lib; MaxTech Farms (FM1) builds on Lib's api jar only
(`farms/CLAUDE.md`) and could not. Farms needs screens for its lab blocks: slots, tanks, an energy bar and progress,
in the same look but its own accent colour.

The api source set must not reference NeoForge (ADR-0002) and cannot see `main`, so whatever moves must bring its own
dependencies with it.

## Options considered

1. **Keep the toolkit internal.** No api risk, but Farms would copy the widgets or draw screens of its own, and the
   look would drift between mods.
2. **Move the widgets only.** Farms gets the look, but still writes its own menus, value sync and slot handling for
   every screen.
3. **A toolkit in steps.** The Accent widgets become api as they are, then themes per mod, bars for tanks and energy,
   menu value sync, Lib-owned item and fluid inventories, and a declarative menu and screen builder on top.

## Decision

Option 3 (owner, 2026-11-27). The first step moves the Accent widget set to `lib.api.client.gui` unchanged:
`Accent` and its widgets, `ScrollBar`, `TextScale`, `TextFit` and `LayoutCheck`. The package is client only and
experimental until Farms ships.

- `TextScale` reads the client's text size option through a source Lib sets at client init
  (`setOptionSource`, `@ApiStatus.Internal`); without one, text draws at normal size.
- `Accent` takes the text fitting it used from `ScreenComponents`, which stays internal for its other users.
- Internal for now: `ScreenComponents`, `PixelScale`, `PanView`, `RightAngleWire`, `UndoStack`, `SidePanel`,
  `SideTabs`, `VirtualGrid`, `GridScroll`, the demo screen and `lib.gui.transfer`.
- Later steps add a theme per mod (Farms mint, Storage cyan), tank, energy and progress bars, a menu value payload,
  `ItemSlots` and `FluidTank` with a NeoForge-side bridge to capabilities, and a menu definition builder with layout
  checks, each in its own PR.

## Consequences

- Addons on the api jar draw the same look as Storage; Farms is the first.
- The palette and widgets are now api: a change to them reaches every mod's screens and is called out in the
  changelog. Until the theme step, the palette is Storage's cyan for everyone.
- Storage imports the widgets from the api package and still uses the internal GUI pieces, so it keeps pinning one Lib
  minor.
- `TextFit` is pure Java, so it moved on to `lib.api.text` (still 0.15.0, G2): the client package holds client
  classes only, and code outside the client (Storage's logic editor layout) uses it from there.

## Amendment (2026-11-27): themes, bars and fluid looks (G2)

- `AccentTheme` holds a mod's accent, dim accent, gauge and text colours; `AccentTheme.LIB` is the old cyan palette.
  It is pure data, so it lives in `lib.api.style`, outside the client-only package, and later common menu definitions
  can name a theme. Every Accent drawing method (panel, bars, faces, buttons, rows, readout, scroll bar) has a theme
  overload and every widget (`AccentButton`, `IconButton`, `AccentPanel`, `AccentTabRow`, `ScrollBar`) a `theme(...)`
  setter; the old forms pass `AccentTheme.LIB` and draw the same pixels, so a screen with one theme sets it on each
  widget. `AccentToast` keeps its fixed status colours.
- `AccentTank`, `AccentEnergyBar` and `AccentProgress` (arrow or flame) are widgets that read plain values from
  suppliers and take a theme; they share the public `AccentBar` base type (only Lib extends it); menu sync comes
  later. Their numbers are always in the tooltip, which they also narrate;
  energy shows MU first and FE at the configured rate below.
- `FluidLook` (sprite, tint, name of a `FluidKey`) is a client interface in the common api, implemented in
  `lib/neoforge` main from the fluid's baked fluid model, so the common api still names no NeoForge type. Amended
  2026-11-27 (K2b): it is now the fluid kind's `ResourceLook` (`lib.api.client.resource`), registered once through
  `ResourceLooks` (a second registration for a kind throws) instead of an internal setter.
- Amended 2026-11-28 (K3): `AccentTank` and tank menu values take a resource of any kind (ADR-0017). A gauge draws with
  its kind's `ResourceLook` and amount unit, only the outlined well for a kind without a look, and "Unknown" for
  unknown contents. Fluid tank values keep their encoding; other tanks send an optional
  `KindKey.CLIENTBOUND_STREAM_CODEC` inside the same payload framing.

## Amendment (2026-11-28): menu server actions (machine core PR 2, ADR-0019)

- Until now a definition's screen only sent tank bucket clicks. A machine also needs the screen to change a setting on
  the server (redstone mode now, side config next). `MenuDefinition.Builder.cycle(enumValue, setter)` declares such an
  action over a synced `EnumValue` whose enum implements `MenuChoice` (a name key and a hint key), and
  `Layout.cycleButton(action, key, width)` places an Accent button for it.
- The click goes through vanilla's container button packet, like the tank clicks: no payload of our own, and vanilla
  already drops clicks for another container, from spectators or on a menu that is no longer valid. Spoofed and
  closed-menu clicks rely on those vanilla checks (`containerId`, spectator, `stillValid`); our tests call
  `clickMenuButton` directly and do not exercise them. Ids from
  `ToolkitMenu.ACTION_BUTTONS` (2,000,000) carry the action's index times `CycleAction.MAX_CHOICES` (256) plus the
  ordinal of the asked-for constant; the server ignores ids of no action and ordinals past the enum, and the setter
  decides the rest.
- The click names the choice, not "next", so a click the server sees twice sets the same choice twice instead of
  skipping one. Left click (or Enter) asks for the constant after the one the client shows, right click for the one
  before; the new value comes back with the next values payload.
- `MenuTesting.Session.pressCycleButton` clicks one in GameTests.

## Amendment (2026-11-28): side config (machine core PR 3, ADR-0018 T5)

- `MenuDefinition.Builder.port(getter, kinds...)` syncs a host's `TransferPort` as plain int values: per declared kind
  its packed faces and allowed modes, plus the host's facing (block state `HORIZONTAL_FACING` or `FACING`, or a getter)
  and block state id. Kinds are declared, since the client menu has no port to ask.
- `Layout.faces(port, kinds...)` is a fixed-size node (a block preview on top, one 72 by 54 block net per kind), and
  `Builder.sideConfig(port, kinds...)` puts it in the one standard side tab. The tab is declared in the common
  definition and drawn by Lib's internal `SideTabs`, so `SideTabs` and `SidePanel` stay internal and addons write no
  client code. The screen centres the panel, the tab column and the flyout together, and the definition checks
  that the three fit the 320 by 240 GUI side by side (three kinds do beside a 176 wide panel).
- A face click goes through the container button packet like cycle actions: ids from `ToolkitMenu.PORT_BUTTONS`
  (3,000,000; cycle actions now end below it) carry the port value, kind, face and the asked-for mode. The server sets
  that mode through `TransferPort.setSettings` only when a faces node shows the kind and the port allows the mode, so a repeated packet sets
  the same mode again rather than stepping on; `cycleFace` only steps forward and is not used.
- Owner choices (2026-11-28): faces are named relative to the facing as seen from the front, top towards north for
  blocks facing up or down; kinds are stacked, one net each; push is orange, pull blue, off untinted.
- The preview is a picture-in-picture renderer (`FacePreviewRenderer`, vanilla rendering only, registered by Lib's
  NeoForge client) that draws the block model as block displays do and tints its faces.
