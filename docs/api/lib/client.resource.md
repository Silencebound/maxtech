# `lib.api.client.resource`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.client.resource`** `ResourceLook`, `ResourceLooks` | How a resource kind looks on the client (still sprite, optional flowing sprite, ARGB tint, name, light 0..15) and the registry of one look per kind; conduit contents draw with it, a kind without a look draws nothing. Client only. | experimental |
| `TextScale`, `LayoutCheck` | The client's text size option for drawing and measuring (`setOptionSource` is Lib's, `@ApiStatus.Internal`); the 320 x 240 layout check and placement math. | experimental |
