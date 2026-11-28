# `lib.api.conduit.model`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.conduit.model`** | Conduit model geometry for an addon's own conduit model (FM1): quads before baking and Lib's own parts. | experimental |
| `ConduitQuad`, `ConduitTexture`, `ConduitLayer` | One face of a model part in pixels; its sprite (one of Lib's, or a custom model hook's by atlas id, Lib 0.14.0); its chunk layer (from the sprite, or forced translucent or cutout). Moved from `lib.client.conduit`. | experimental |
| `ConduitUv` | Which part of its sprite a `ConduitQuad` shows (0 to 16 pixels) and its clockwise quarter turns, as a block model face's `uv` and `rotation`; a quad without one shows the whole sprite unturned (Lib 0.14.0). | experimental |
| `ConduitContents` | What a conduit shows inside it: a resource of any kind as its draw base (a fluid saved by registry name as before, other kinds as `kind` plus `resource`; a kind that is gone loads empty; drawn with the kind's `ResourceLook`; `ofFluid` and `fluid()` for fluids), fill step 0..4, upstream side, second lane; `EMPTY` for none. The static `pack`/`packed*` helpers give the client render key's int (bits 0-15 the session look index + 1, which Lib's client hands out per kind and draw base, 16-18 fill, 19-21 upstream side + 1, 22 lane), valid only within one session; Lib packs its conduits' contents itself. | experimental |
| `ConduitBodyKey`, `ConduitParts` | What a conduit body looks like (type, dye, arm and ring masks, render value, packed contents; the 4- and 5-argument constructors give 0 for what they leave out); Lib's default body and face quads, which ignore the render value and contents. | experimental |
