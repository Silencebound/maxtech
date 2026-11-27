# ADR-0016: GUI toolkit api

Date: 2026-11-27
Status: Accepted

## Context

Every MaxTech screen draws the Accent Frame look with Lib's widget set, which lived in `lib.client.gui`, an internal
package. Storage could use it because it compiles against all of Lib; MaxTech Farms (FM1) builds on Lib's api jar only
(`farms/CLAUDE.md`) and could not. Farms needs screens for its lab blocks: slots, tanks, an energy bar and progress,
in the same look but its own accent colour.

The api source set cannot reference NeoForge or see `main` (ADR-0002), so whatever moves must bring its own
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
- `TextFit` is pure Java but now lives in a client package. Code outside the client (Storage's logic editor layout)
  may still use it, since it loads no client class.
