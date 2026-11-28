# `lib.api.style`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.style`** `AccentTheme` | A mod's accent, gauge and text colours; `LIB` is Lib's cyan, the default everywhere. Pure data, both sides; widgets take it through `theme(...)` or overloads. | experimental |
| `AccentBar`, `AccentTank`, `AccentEnergyBar`, `AccentProgress` | The bars' shared base type and the bar widgets drawing plain values from suppliers: a tank of any resource kind with quarter ticks (the kind's look and unit; "Unknown" for unknown contents), a segmented energy gauge (MU, FE in the tooltip), an arrow or flame; tooltips and narration give the numbers. | experimental |
| `FluidLook` | The fluid kind's `ResourceLook` (still and flowing sprite, tint, name, light), with `Fluid` overloads; Lib registers the NeoForge implementation, `FluidLook.get()` returns it. | experimental |
