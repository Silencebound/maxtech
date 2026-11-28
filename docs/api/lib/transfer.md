# `lib.api.transfer` and `lib.neoforge.api.transfer`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.transfer`** | Transfer ports (ADR-0018): a block moves resources of any kind between its buffers and the blocks beside it, face by face, without ticking. Lib 0.15.0 moves items, fluids, other tank kinds and standalone energy buffers (as FE). | experimental |
| `PortFaceMode`, `PortFaces` | Off, push or pull per face (never both; stable ids, `next` over the allowed modes); six modes packed in two bits per face, per kind. | experimental |
| `PortFilter` | Allow or deny list of keys and tags for one kind, per port; allocation-free `test`; tag support registered per kind (Lib's for items and fluids); saved keys that no longer load are kept. | experimental |
| `PortSettings`, `PortStatus` | Per kind: faces, filter, rate cap; codec by kind id, keeping settings of kinds that are gone. What a port last did per kind. | experimental |
| `TransferPort`, `TransferPorts` | The port handle (start, stop, wake, cycle a face, settings, status, save, load) and `builder()` with `items`, `tank`/`fluids`, `energy` (each repeatable per kind, with the modes that buffer takes part in; `energy` also takes an `onChange` listener), `rate`, `defaults`, `onSettingsChanged` and `tasks` (a group of the owner's per level; Lib's transfer group of the level without); `build()` moves items, tanks of any kind with a bridge (fluids at `transfer.fluidPerOp`, as other tank kinds) and standalone energy buffers as FE at `transfer.energyPerOp` MU, keeping the conversion remainder. All ports of a level share Lib's transfer group and one owner's scheduler budget unless `tasks` gives another group. A port's block must expose each kind's capability on its pulling faces. | experimental |
| `RelativeFace` | Front, back, left, right, top and bottom of a block for its facing, as a player in front of it sees them (top towards north when facing up or down); `toAbsolute(facing)` and `of(facing, direction)`. Side config screens name faces with it. | experimental |
| **`lib.neoforge.api.transfer`** `KindBridge`, `KindHandler`, `KindBridges` | A kind's capability as handlers for ports (cached lookup, insert and extract in a transaction, `transactional()`), registered per kind; items and fluids built in, energy goes through FE edges. Ports reach tanks' neighbours through these; a handler without transactions is touched last. | experimental |
