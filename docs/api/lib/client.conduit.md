# `lib.neoforge.api.client.conduit`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.neoforge.api.client.conduit`** | NeoForge-side client api (jar `maxtech_lib-neoforge-api`, Lib 0.14.0, FM1): custom models for conduits marked `Conduits.Builder.customModel(translucent)`. | experimental |
| `ConduitModelHook` | An addon's model of one conduit: its sprites (resolved at bake time) and the body and face quads per key, Lib's own by default; called lazily on meshing threads. | experimental |
| `RegisterConduitModelsEvent` | Client mod-bus event, fired once at startup: `register(ConduitEntry, hook)`. A marked conduit without a hook or a hook for an unmarked one stops the client. | experimental |
