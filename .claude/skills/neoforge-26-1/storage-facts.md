# Storage facts (verified)

Verified Minecraft, NeoForge and repo facts found while building Storage, split out of the `neoforge-26-1` skill (`SKILL.md` beside this file holds the shared ones). Same rules: every entry says where it was verified, and a wrong one is fixed here in the same PR.

Parts, by topic. A new section goes at the end of the part it fits:

- Build, test runs and dev tools
- Network, cables and channels
- Controller
- Cells, drives and saving
- Terminals, screens and slots
- Ports, buses and world devices
- Autocrafting
- Logic
- Displays and rendering
- Research
- Materials, worldgen, machines and energy
- Config, servers and multiplayer
- Other mods

# Build, test runs and dev tools

## Suite runs in Storage (M6 skeleton; verified in the 26.1.2.109 sources and by `runSuiteGameTestServer`)

- The `--tests` selector is matched with `FilenameUtils.wildcardMatch` against the whole `namespace:path` string
  (`ResourceSelectorArgument.matches`), so a wildcard works in the namespace too: `maxtech_*:suite_*` picks every
  addon's `suite_` tests. A pattern without `:` gets `minecraft:` prepended.
- `registerMod` adds another project's `neoforge` main and `common` main/api source sets only, never its `gametest`
  set. A run that needs another mod's `gametest` source set adds it to that mod's model itself
  (`neoForge.mods.named(...) { sourceSet(...) }`); the non-suite runs drop that mod, so they never see it.
- A mod with no content yet runs `ContentDatagen.providers(...).register(event)` cleanly and writes only hash-cache
  files, which are gitignored, so its `src/generated` stays empty and CI's diff needs `mkdir -p` for a valid pathspec.

## Scene shots from a client command (2026-10-18; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by compiling `ShootClient`; not yet run in a client)

- `Screenshot.grab(File workDir, @Nullable String forceName, RenderTarget target, int downscaleFactor, Consumer<Component> callback)` writes `<workDir>/screenshots/<forceName>` (F2 passes `minecraft.gameDirectory` and `getMainRenderTarget()`); it copies the colour texture to a buffer, posts `ScreenshotEvent`, writes on `Util.ioPool()` (`NativeImage.writeToFile` opens with `CREATE, TRUNCATE_EXISTING`, so a fixed name overwrites) and calls back off-thread with `screenshot.success` or `screenshot.failure`. From a client tick the target holds the last finished frame, as for F2.
- Render readiness: `LevelRenderer.hasRenderedAllSections()` is only `sectionRenderDispatcher.isQueueEmpty()`; vanilla's world icon also asks `countRenderedSections() > 10`. `isSectionCompiledAndVisible(BlockPos)` is true once that section's mesh is compiled and its fade-in (`options.chunkSectionFadeInTime()`, seconds; far sections only) is at least 30 %.
- `options.hideGui` (public field) is F1: `GameRenderer` skips the block outline and the hand while it is set. `options.getCameraType()/setCameraType(CameraType.FIRST_PERSON)`.
- Client commands may share a root literal with a server command: `ClientCommandHandler.runCommand` tries the client dispatcher first and passes the command to the server on an unknown command or argument, and `ClientPacketListener.sendCommand(String)` (no slash) goes through the same check before it sends `ServerboundChatCommandPacket`. The client source's `sendSuccess` never broadcasts to admins, so it is safe without a server.
- `time set` in 26.1 takes ticks or a clock time marker (`ClockTimeMarkers.NOON` is `minecraft:noon`); `time set 6000` sets the default clock's total ticks. `tp @s <x y z> <yaw pitch>` sets position and rotation.

## Structure files for the showcase scenes (2026-10-18; verified in the patched 26.1.2.109 sources and by the `scene_*` GameTests, which save each scene to a `StructureTemplate`, clear it and place it again; the file and heightmap paths not yet run in a client)

- Save: `new StructureTemplate().fillFromWorld(Level, BlockPos corner, Vec3i size, boolean includeEntities, List<Block> ignore)` reads every block (air included, so placing clears too; vanilla's structure block passes `STRUCTURE_VOID` to ignore) and saves block entities with `saveWithId`; `template.save(new CompoundTag())` adds `DataVersion`; `NbtIo.writeCompressed(CompoundTag, Path)` writes the gzip file. `StructureTemplateManager.save(Path, template, asText)` exists but the world-folder save paths are tied to an `Identifier`.
- Load: `NbtIo.readCompressed(Path, NbtAccounter.create(quota))`, then `DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), tag, NbtUtils.getDataVersion(tag, 500))` as the manager's `TemplateSource.readStructure` does, then `template.load(level.holderLookup(Registries.BLOCK), tag)`.
- Place: `template.placeInWorld(ServerLevelAccessor, BlockPos pos, BlockPos pivot, StructurePlaceSettings, RandomSource, int flags)` (false when there is nothing to place) sets each block, then loads its block entity with `loadWithComponents` while the block is already in the level. Lib's conduits handle that (`ConduitBlockEntity.loadAdditional` drops and re-reads the node when live) and Storage's networks form from a placed template like from placed blocks. Cells come back with the saved world's ids (their contents live in that world's `CellStorage`), so `ShowcaseScenes` gives them `CellData.NO_ID` and stocks the network again; CPUs come back with a job id, so it cancels those.
- `Level.getHeight(Heightmap.Types, x, z)` is the first free y above the column (top block + 1) but returns `getMinY()` when the chunk is not loaded (`hasChunk` check), so load the chunks first (`level.getChunk(cx, cz)` loads to full). `MOTION_BLOCKING_NO_LEAVES` skips leaves but counts logs and fluids; `WORLD_SURFACE` counts everything but air.
- `ServerPlayer.teleportTo(ServerLevel, x, y, z, Set<Relative>, yaw, pitch, boolean resetCamera)` moves a player to a feet position (subtract `getEyeHeight()` for an eye pose).
- Crafting CPU part blocks (storage, lane, cache) link to no network node; the core must touch a cable or device itself, or its jobs stay `PLANNED` (the `scene_crafting_*` GameTest failed until the core moved next to the cable). A fuel generator next to a network device feeds that network, and the network's pass draws from the same buffer the generator pushes machines from; which runs first in a tick varies, so a machine next to a networked generator can run dry (`scene_processing_*` failed in CI that way). Keep generators meant for machines off the network.

## Switching a server config value inside a GameTest (B09 item 12; verified 2026-10-18 by the `channels_infinite_mode_switch` GameTest)

- A `StorageTestEnvironment` save (`ConfigValue.set` plus `save` inside its own `ModConfigEvent.Reloading`) also runs every `ConfigFile.onLoad` listener, the same as an admin editing the file on a running server; so a test can switch a value in the middle (`StorageTestEnvironment.switchChannelMode`) and code that caches a value in `onLoad` (`Channels.modeLoaded`) sees it on the server thread at once. Keep such a test in its own environment batch that sets the same option, so the teardown restores the value from before the batch.
- An enum value from `ConfigBuilder.defineEnum` takes the lowercase constant name in the preset JSONs (`"network.channels.mode": "limited"`), as `research.preset` does.

# Network, cables and channels

## Storage network, controller look and diagnostics (M6a area 1; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources, by `runData` and by the `network_*` and `perf_storage_idle_devices` GameTests; the glow and animation not yet seen in a running client)

- Block model elements take vanilla's `"light_emission": 0..15` (`CuboidModelElement.Deserializer`); `FaceBakery.bakeQuad` puts the higher of it and NeoForge's per-face `neoforge_data.light_emission` into `BakedQuad.MaterialInfo`, so one overlay element of a model glows full-bright while the rest stays lit normally. Datagen: NeoForge's `ElementBuilder.lightEmission(int)` writes it (0 is left out). A blockstate-driven glow is that plus `BlockBehaviour.Properties.lightLevel(state -> ...)` for the light the block spreads.
- Animated block textures are plain vanilla: a vertical strip with `<texture>.png.mcmeta` `{"animation": {"frametime": n, "interpolate": true}}` (vanilla's `magma.png.mcmeta` interpolates), so a pulsing core needs no block entity renderer.
- `RegisterCapabilitiesEvent` keeps a list of providers per block and `BlockCapability.getCapability` returns the first non-null answer; mod-bus events reach mods in load order, so a dependency's provider registered for a whole block entity type (Lib's `ConduitInfo` for the shared conduit type) shadows anything a dependent mod registers for the same blocks. Lib's `ConduitInfo` therefore hands its lines to a `ConduitType` that implements `BlockInfoProvider` (Storage's cables).
- Lib's `NetworkGraph` fires `onNodeInvalidated` on every neighbour of a freshly placed conduit before the new node's ADD is applied (the neighbours' masks change at once, the new node joins on its next block entity pass), so a `NetworkLogic` that rebuilds on every invalidation rebuilds on every placement. Storage rebuilds only when a link its route tree uses is gone (`NetworkState.nodeInvalidated`).
- Lib's `FeBuffer` views (`inputOnly()`, `outputOnly()`) read through `getAmountAsLong()` and `getCapacityAsLong()` since MLE item 4, and every change goes through `setAmount`, so a subclass that keeps its amount elsewhere (Lib's `EndpointBuffer`, an energy endpoint) overrides `amount()`, `setAmount` and the capacity methods; the constructor sets the capacity field without calling `setCapacity`, so an override never runs before the subclass's fields exist.
- `checkCommonImports` warns for any NeoForge type in an `api` source set, transfer types included (ADR-0002), and `docs/PR_REVIEW.md` counts those warnings as must findings. Storage therefore keeps its transaction-taking seams (`StorageProvider`, `StorageTask`, `StorageTransfer`, player insert and extract) in `storage.network` and its api loader-neutral.
- `Item.inventoryTick(ItemStack, ServerLevel, Entity, @Nullable EquipmentSlot)` runs server side only; `Item.getPlayerPOVHitResult(Level, Player, ClipContext.Fluid)` is public static. `Block.setPlacedBy(Level, BlockPos, BlockState, @Nullable LivingEntity, ItemStack)` can be called from a GameTest with a constructed `ServerPlayer` to place "as" that player.
- `GameTestHelper.getBlockEntity(BlockPos, Class<T>)` returns the typed block entity or fails the test. NullAway does not learn from `helper.assertTrue(x != null, ...)`; tests use a helper that throws `helper.assertionException(Component)` and returns the non-null value.
- Error Prone's `UnnamedVariable` also fires for unused type-pattern variables in a switch (`case Integer unused ->`); write `case Integer _ ->`.

## Chunk tickets for network anchors (M6c2 area G; verified in `~/mc-reference/NeoForge` `common/world/chunk`, the patched 26.1.2.109 sources and the `controller_anchor_*` GameTests)

- Mod chunk loading is NeoForge's `TicketController(Identifier id, @Nullable LoadingValidationCallback callback)` (`net.neoforged.neoforge.common.world.chunk`), registered in `RegisterTicketControllersEvent` (mod bus; `ForcedChunkManager.init` posts it from NeoForge's registration events, so it fires in every run, data and GameTest runs included). Forcing through an unregistered controller throws `IllegalArgumentException`.
- `controller.forceChunk(ServerLevel, BlockPos owner, chunkX, chunkZ, add, forceNaturalSpawning)` adds or removes the ticket that owner holds; it returns whether anything changed (adding twice for one owner is a no-op), and adding loads the chunk at once (`level.getChunk`). Tickets are `NeoForgeMod.BLOCK_TICKET` (or `BLOCK_WITH_NATURAL_SPAWNING_TICKET`) at `ChunkMap.FORCED_TICKET_LEVEL` (31, entity ticking); several owners on one chunk share one vanilla ticket until the last lets go.
- The tickets are saved with the level (`TicketStorage`'s `neo_ticket_data`) and come back at server start: `ForcedChunkManager.activateAllDeactivatedTickets` first calls each controller's callback with a `TicketHelper` (`getBlockTickets()`: owner position to `TicketSet(normal, naturalSpawning)` of packed chunk positions; `removeAllTickets(owner)`, `removeTicket(owner, chunk, natural)`), then activates what is left. A mod keeping its own bookkeeping seeds it there. Saved tickets of a controller no longer registered are dropped with a warning.
- Reading tickets in a test: `level.getDataStorage().get(TicketStorage.TYPE).getTickets(packedChunk)` lists `net.minecraft.server.level.Ticket`s (the class is in `server.level`, not `world.level`) with `getType()`; compare with `NeoForgeMod.BLOCK_TICKET.value()`. GameTest areas use vanilla `FORCED` tickets, which are a different type.
- A controller's `LoadingValidationCallback` runs once per level at server start, before any block entity loads, and only when the level saved tickets for it; bookkeeping seeded there has to be swept later for owners that never come back (Storage releases unclaimed ones 200 ticks after seeding).
- Config reloads: `ModConfigEvent.Reloading` is posted on the owning mod's event bus only, so listening for another mod's config means `ModList.get().getModContainerById(id).map(ModContainer::getEventBus)` and `addListener(ModConfigEvent.Reloading.class, ...)` on it (`getEventBus()` is `@Nullable`, which `Optional.map` turns into empty; compiled against 26.1.2.109). It can fire on the config watcher thread, so only set a flag there and act on the server thread.
- `ChunkPos` is a record in 26.1: `ChunkPos.pack(BlockPos)`, `pack(x, z)`, `getX(long)`, `getZ(long)`; `new ChunkPos(x, z).pack()`.
- Lib's `NetworkManager.setAnchored(type, pos, true)` keeps a network ticking in `pause` mode while `networks.allowAnchors` is on; the flag lives on the graph node, so the anchor sets it again after its chunk reloads. Loading the chunks is the anchor's job.

## Cable tiers and smart cables (M6d area B; the `cable_*` GameTests and `presets_complete`)

- Lib's `ConfigBuilder` (over `ModConfigSpec.Builder`) lets a later static block `push` a section an earlier block already pushed and popped (`network`, then `channels`) and define new keys in it; the values load and read normally (`cable_tier_channels` reads `network.channels.tier3` as 32).
- `GameTestHelper.assertValueEqual(a, b, name)` words its failure as "Expected <name> to be <a>: was <b>", so with the project's `(actual, expected)` habit the message reads backwards; judge a failure by the values, not the words.
- `-Pgametest` takes one pattern: a comma-separated list of test names ran only the first. Run tests one by one, or with a wildcard.

## Subnets, route patches, server caps and offline drives (M6d area C; verified in the patched 26.1.2.109 sources, brigadier 1.3.10 sources and by the `subnet_*`, `network_patch_*`, `network_cap_*`, `network_offline_cells` and `perf_network_cable_change_10k` GameTests)

- Every storage cable's conduit state is a `DeviceHost` (`CableParts`), so `StorageNetworkLogic.hosts` holds every loaded storage cable, parts or not; "a cable with parts" is `host.deviceCount() > 0`, never `host != null`.
- `Level.getBlockEntity(pos)` asks the chunk with `EntityCreationType.IMMEDIATE`: a missing or removed block entity under an `EntityBlock` is created fresh and empty, `onLoad` included. A GameTest that simulates an unload with `removeBlockEntity` must also clear the block, or the next neighbour lookup makes a new device there.
- On a real unload NeoForge calls `onChunkUnloaded()` and then `setRemoved()` (see SKILL.md); `BlockEntity.loadStatic(pos, state, tag, registries)` plus `Level.setBlockEntity` brings a saved block entity back in a GameTest, and its `onLoad` runs on the next level tick.
- Brigadier merges a registered literal into an existing one of the same name (`CommandNode.addChild`: the first node's `requires` stays, children are merged), so `Commands.literal("maxtech").then(...)` from Storage adds beside other mods' `/maxtech` subcommands; put the permission on the subcommand.
- `BlockModel.facingColumn(side, end)` writes vanilla's `cube_column` with `ROTATIONS_COLUMN_WITH_FACING`, which needs the full `BlockStateProperties.FACING` (six directions), not `HORIZONTAL_FACING`.
- `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`, `BlockPosArgument.getLoadedBlockPos` and `EntityArgument.getPlayer` are the vanilla 26.1.2 names (compiled).
- Testing commands (patched 26.1.2.109 sources, `logic_admin_*` GameTests): `ServerPlayer.createCommandSourceStack()` takes the player's `permissions()`, so a `ServerPlayer` subclass returning `LevelBasedPermissionSet.GAMEMASTER` is an op source and a plain GameTest player is refused (a `requires` that fails makes `dispatcher.execute` throw `CommandSyntaxException`). `withSuppressedOutput()` keeps `sendSuccess`/`sendFailure` from reaching the connection-less player; `getPlayer()` still answers it. A local `new CommandDispatcher<CommandSourceStack>()` with the literal registered runs the real command tree. `CommandSourceStack.withPermission(PermissionSet)` and `PermissionSet.NO_PERMISSIONS` exist too. `MinecraftServer#getAllLevels()` iterates the loaded levels.
- From a screen, `minecraft.getConnection().sendCommand(cmd)` (no slash) sends a command at once, without the confirmation screen that chat click events (`sendUnattendedCommand`) open for op commands (patched sources).
- A Lib `NetworkGraph` removal fires `onNodeRemoved` for the node and `onNodeInvalidated` for neighbours whose links changed, then runs a `SplitJob` (bounded per tick) and only after it settles `onRebuilt`; a removal that splits nothing never calls `onSplit`. `onRebuilt` fires for a paused network too, so route work done there happens in `pause` mode as well.
- `ItemAccessEnergyHandler` with `maxExtract` 0 returns 0 from `extract` and caps each `insert` at `maxInsert` per call (verified by `signal_wireless_charge`); a second item with its own `Integer` component registers the same way in its own `RegisterCapabilitiesEvent` subscriber.
- Route ties and zone order (2026-11-26; `RouteTreeTest` and the `network_routes_ignore_order` GameTest): Lib's `RouteTree` gives a node the parent with the lowest position among routes of equal width and depth (build, `attach`, and Storage's `Routes` attach and reroute alike), and `NetworkState.assignZones` fills zones by depth then position. Before, the binary heap's order and the node set's hash order picked among equal routes, so a merge could move routes and zones far from the join. Player-visible: which of two equal paths a device's channel takes, and so smart-cable loads, which zone a cable lands in, and which device goes offline at a full segment, may differ from before; no route gets narrower or longer.
- Storage routes (Lib `RouteTree` plus `Routes`): a link added between two nodes already in one network (antennas paired inside it) is not used until the next full rebuild unless something marks one (`NetworkState.nodeInvalidated` now does for a `RemoteLinkNode` with an unused remote link), while unpairing (`invalidateNode` on both ends) is a cut that `NetworkState.patch()` handles without one (`signal_unpair_patches_routes`).
- The `guide_pages` GameTest refuses a `<RecipeFor>` for an item made only by a Storage machine recipe ("has no crafting, smelting, blasting or smithing recipe"); describe machine-only recipes in prose.

## Analyzer and the controller's Network tab (M6d area E; verified in the NeoForge patches and by the `analyzer_*` and `controller_network_tab_sync` GameTests)

- `IItemExtension.onItemUseFirst` runs before NeoForge's sneak suppression in `ServerPlayerGameMode.useItemOn` (the patch calls `onItemUseFirst` first, then computes `suppressUsingBlock`), so an item that wants sneak-use on a block (the analyzer's overlay) reads `player.isShiftKeyDown()` there; no `doesSneakBypassUse` override is needed.
- `ServerItemCooldowns.onCooldownStarted` sends a packet, so `addCooldown` on a `new ServerPlayer(...)` without a connection throws a NullPointerException; a GameTest player that uses a cooldown item overrides `protected ItemCooldowns createItemCooldowns()` to return a plain `new ItemCooldowns()`. `ItemCooldowns.removeCooldown` takes the group `Identifier` (`getCooldownGroup(stack)`), not a stack (compiled).
- Lib's `HighlightRequest.Builder.block(pos, argb)` stores a box 0.005 larger than the block on every side; a test reads the block back from the box's centre, not its minimum.

## Animated cable looks (M6e area H; written from the 26.1.2 sources, not run in a client; cables stopped animating on 2026-10-07 and the classes named here are gone, the facts still hold)

- An animated sprite's frames advance for the whole atlas and cost the same however many blocks show it, so a "render cutoff" cannot be a per-block animation switch: the model picks between an animated sprite and a separate still copy (its first frame, no `.mcmeta`) while the chunk mesh is built. `DynamicBlockStateModel.collectParts` and `createGeometryKey` run on mesh workers and get a `BlockPos`; the choice must be part of the geometry key or a section that flipped would reuse the old mesh.
- A built mesh does not change until the section is marked dirty, so a distance rule needs a client-tick pass that calls `Minecraft.getInstance().levelRenderer.setSectionDirty(sx, sy, sz)` for sections whose answer changed (the former `AnimatedCableRange`; the same call AE2 and NeoForge's own tests use). Deciding per section centre (`CableAnimation.near`) keeps a section's blocks consistent.

## Network goggles, trace pen and zone painter (M6e area B; verified in the patched 26.1.2.109 sources and by the `network_tools_*` GameTests; the goggles' look and overlay not yet seen in a running client)

- Worn items tick: `LivingEntity` calls `EntityEquipment.tick(owner)`, which runs `inventoryTick` on every equipment stack with its `EquipmentSlot` (`HEAD` for a helmet), while `Inventory.tick` runs only the 36 main slots, with `MAINHAND` for the selected one and `null` for the rest. So an item's `inventoryTick` sees `slot == EquipmentSlot.HEAD` exactly while it is worn.
- `Inventory.EQUIPMENT_SLOT_MAPPING` puts `FEET`, `LEGS`, `CHEST`, `HEAD` at inventory indices 36 to 39 (`EquipmentSlot.getIndex(36)`), so `ItemAccess.forPlayerSlot(player, 39)` reaches the helmet stack, and its item energy capability charges a worn item.
- `Item.Properties.equippable(EquipmentSlot.HEAD)` sets `DataComponents.EQUIPPABLE` with `slot() == HEAD` on the default stack.
- Lib's conduit removal is queued (`ConduitBlockEntity.setRemoved` -> `NetworkManager.removeNode`, applied on the graph's next pass), so when `NetworkLogic.onNodeRemoved` runs a broken cable's chunk is present but `level.getBlockEntity(pos)` is no `ConduitBlockEntity` any more, while an unloaded one's chunk is gone (`ConduitBlockEntity.isChunkPresent`). Storage drops a zone border mark only in the first case; the GameTest area clearing (`setBlock` with `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`) counts as breaking.
- `Item.getPlayerPOVHitResult` from a GameTest player: the block above the top layer of Storage's 4-high `empty_42x4x27` structure stops the ray, so a player two blocks above a floor-row block hits that instead; stand the player on the block above the target and set `setXRot(90)` to look straight down.

## M6f area D: cable layer

- Placing a block item for a GameTest `new ServerPlayer(...)` (no connection) through `stack.useOn(new UseOnContext(level, player, hand, stack, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)))` places into `pos` when it is air, conduits included (Lib's `ConduitBlock` builds its block entity and connections there), and fires the place event as for a real player (verified by `cable_layer_lays_run`); the colour is then set with `ConduitBlockEntity.setColour`.
- Item-valued and colour-valued data components: `BuiltInRegistries.ITEM.byNameCodec()` with `ByteBufCodecs.registry(Registries.ITEM)`, `DyeColor.CODEC` with `DyeColor.STREAM_CODEC`, `GlobalPos.CODEC` with `GlobalPos.STREAM_CODEC` are the pairs for persistent, synced components (they register and the layer uses them in GameTests; not saved and reloaded in a test).
- `BuiltInRegistries.ITEM.byNameCodec()` fails on an id that is no longer registered ("Unknown registry key"), and one failing entry fails the whole list and the record codec around it. To drop unknown entries instead, decode `Identifier.CODEC` and map through `BuiltInRegistries.ITEM.getOptional(id)`: `DefaultedMappedRegistry.getOptional` returns empty for an unknown id, while `getValue` returns air (checked in the 26.1.2 sources; `CellData`'s add-on list, regression test `cell_parts_old_priority_cell`).
- Keeping entries of a removed mod (verified in the DFU 9.0.19 and patched 26.1.2.109 sources and by the `kept_*`
  GameTests): DFU's `ListCodec` decodes every element but returns an error (with the good ones as a partial result)
  when one fails, so `isSuccess()` is false for the whole list; `optionalFieldOf` errors on a present but bad value,
  `lenientOptionalFieldOf` reads it as empty. `DataComponentPatch.CODEC` fails on an unknown component type ("No
  component with type"), so a `StackKey` with one fails like an unknown item. To keep what fails, decode the raw
  values: `Codec.PASSTHROUGH` decodes to a `Dynamic` without looking inside, `codec.parse(dynamic)` tries the real
  codec, `dynamic.convert(NbtOps.INSTANCE).getValue()` gives the raw `Tag`, and encoding a `Dynamic` through
  `PASSTHROUGH` writes it back into any ops (`KeyPalette.codec(Codec.PASSTHROUGH)` keeps the palette's index checks).
  Storage does this in `CellStorage` (`KeptKeys`) and, for lists, `save.KeptList`. Vanilla's `TagValueInput` typed
  lists (`ContainerHelper.loadAllItems`) skip a failing element and report it, so a container loses only that stack;
  a failing persistent component fails the whole `ItemStack`. `TypedEntityData.loadInto` merges the item's tag into
  the block entity's `saveCustomOnly` output and calls `loadCustomOnly`, so a field kept raw in
  `DataComponents.BLOCK_ENTITY_DATA` reaches `loadAdditional` when the block is placed again.
- Item components that may name a removed mod's item or fluid use `save.KeptValue<E>` (#327): its codec keeps the raw
  NBT when the real codec fails, so the stack survives a container's save and load (`kept_components_in_container`).
  `FluidResource.OPTIONAL_CODEC` fails on an unregistered fluid id (it does not fall back to empty), and the pattern
  codec's input list fails with "List is too short: 0" when its only input names an unknown item (DFU's `listOf(min,
  max)` checks the partial result). `ByteBufCodecs.TAG` (default quota) syncs a kept NBT value. NeoForge's
  `StacksResourceHandler.deserialize` reads its whole list with one codec, so an unknown fluid in a block entity's
  handler save still fails that handler's list (patched 26.1.2.109 sources).
- To keep such entries in a handler's own save (#332), write and read the same key yourself: `serialize` is
  `output.store(StacksResourceHandler.VALUE_IO_KEY /* "stacks" */, stackCodec.listOf(), stacks)` with
  `ItemStack.OPTIONAL_CODEC` (item handler) or `FluidStack.OPTIONAL_CODEC` (fluid handler), so
  `KeptValue.codec(thatCodec).listOf()` under the same key writes identical NBT for readable stacks and keeps a failing
  slot in its place; a subclass sets the result through the protected `setStacks`, which fires no
  `onContentsChanged` (NeoForge 26.1.2.109 sources; `kept_interface_stock_saved`). `FluidStack.OPTIONAL_CODEC` saves
  `{id, amount, components}` while a `FluidResource` saves `{id, components}`, so a kept fluid moving between the two
  forms must be reshaped (`FluidStock.fromStackTag`). An unknown item id in `ItemStack.OPTIONAL_CODEC` fails with
  "Unknown registry key" like `byNameCodec`.
- `Platform.registry().registrar(modId, registry)` makes a new `DeferredRegister` on each call; an area registering several entries keeps one registrar in a static field.

## Cross-dimension (M6f area C)

- A GameTest server loads every dimension: `server.getLevel(Level.NETHER)` is non-null, so a test can make a player in another dimension with `new ServerPlayer(server, netherLevel, profile, ClientInformation.createDefault())` and call reach code with it, without teleporting (`bridge_wireless_across_dimensions`). `ServerPlayer.setServerLevel(ServerLevel)` also exists (sets the level and the game mode's level) for moving an existing test player; not used.
- `Level.RESOURCE_KEY_CODEC` (`ResourceKey.codec(Registries.DIMENSION)`) saves a dimension key in a `SavedData` codec; `optionalFieldOf("home")` keeps older saves loading.
- A second `static {}` block in a `PresetConfig` that pushes a section an earlier block already pushed (`controller`) adds values under it; the spec and `presets_complete` accept it (the deprecated `controller.modules.*` keys of M7R item 8 sit in the `devices` block).
- A device's `StorageTask` returning a delay of 200 ticks costs nothing in between: `perf_controller_all_modules_10k` saw 0 scheduler runs in its 110 settled ticks with the bridge's drain in the cluster.

## Throughput limits at expert scale (bandwidth model, removed in M7N with the `expert_*` GameTests; verified by those tests and in `JobDriver`, `TaskGroup` and `PortWorker`, 2026-10-11)

- A tier-3 port with both speed cards moves 1,024 items/s (16 operations of 64) or 64,000 mB/s (64 operations of 1,000 mB). `PortBlockEntity.operationsPerSecond()` adds the item and the fluid demand (68 ops/s for that port) whatever it actually moves.
- An import port takes one slot per operation (`PortWorker.pull`), so slots of 4 items cost 16 times the operations of full stacks: such a port asks 256 ops/s.
- Lib's `TaskGroup.pass` checks the time budget (`scheduler.ownerBudgetMicros`) and the ops budget between task runs, never inside one; a deferred port keeps its rate credit and moves more in its next run.
- `JobDriver.start` takes every input of a job out in one transaction through `NetworkState.extract`, which pays FE per operation and item. A lone core's 10,000 FE buffer starts a 100 hopper minecart job (about 9,200 items) but leaves a 120 cart job (about 11,000 items) short, so it ends `PARTIAL`.
- `MemoryProvider` (the test storage) copies its whole map per transaction; a scenario with hundreds of kinds needs a provider with a journal per key (`ExpertTestContent.SinkProvider`) or it measures the test sink.

## Channels (M7N item 1; verified in the patched 26.1.2.109 sources and by the `channels_*`, `perf_channels_*` and `network_ad_hoc_channels` GameTests, 2026-10-14)

- `BlockEntity.loadStatic(pos, state, tag, registries)` creates the block entity and calls `loadWithComponents` before it has a level (`level == null` in `loadAdditional`); the chunk sets the level afterwards. Placing an item with `DataComponents.BLOCK_ENTITY_DATA` (`BlockItem.updateCustomBlockEntityTag` -> `TypedEntityData.loadInto`) merges the data into the block entity already in the level (`level != null`). Storage reads a device's saved join order (`join_seq`) only when `level == null`, so a drive placed again from its item joins as the newest device.
- Moving a block to another block entity type without losing saved data (M7R item 4, verified in the 26.1.2 sources and by `service_old_modules_migrate`): `BlockEntity.loadStatic` picks the type from the saved `id` and calls that type's factory with the block state, without checking the type against the block; the constructor's `validateBlockState` then checks the state against the created entity's own `getType()`. So the old type's factory may return an entity of the new type for the new block (`ExpansionContent.EXPANSION_BLOCK_ENTITY`): it loads the old data, and saves under the new id from then on. If the old factory made its own class instead (and the old type no longer lists the block), the constructor would throw, `loadStatic` would log and return null, and the chunk would create a fresh, empty entity.
- Retiring a block into another block (M7R item 5, verified in the patched 26.1.2.109 sources and by
  `energy_capacitor_bank_ids` and `energy_capacitor_bank_overflow`): chunk palettes decode block states with
  `BlockState.CODEC`, which reads the block through `BuiltInRegistries.BLOCK.byNameCodec()` (so a registry alias
  applies) and its properties with `StateDefinition.propertiesCodec()`, a map codec of one optional field per property
  of the new block: properties the new block lacks are ignored, missing ones take the default state's value. Saved
  `ItemStack`s and `ItemKey`s read the item through `BuiltInRegistries.ITEM.byNameCodec()` too. A Lib `KeyCounts.load`
  adds the counts of palette keys that resolve to the same key, so a storage cell holding both the old and the new id
  loads one key with both counts. The block entity saved under the old type id still loads through that type's factory
  (see the item 4 fact above), which may return the new block's entity. `BaseMappedRegistry.addAlias` throws for a
  second alias of the same name to another target and for loops; aliases are copied into registry snapshots.
- A test "chunk reload" of a device: `saveWithFullMetadata`, `onChunkUnloaded`, `chunk.removeBlockEntity(abs)`, later `BlockEntity.loadStatic` and `chunk.addAndRegisterBlockEntity`. `setRemoved` removes the device's graph node (a full rebuild), `onLoad` adds it again (`nodeAdded`, so the device joins incrementally and an older one finding no room asks for a recount).
- `NetworkState` counts channels per route segment keyed by the child node (`segmentCapacity(node, parent)` is the one place a budget is read). A full count runs at rebuilds and recounts, oldest `joinSeq` first; a patch releases and re-takes channels only for the devices on detached nodes; a join or leave walks one route. `channelSteps()` counts the route segments walked, so tests can assert that operations walk none.
- `-Pgametest=` takes one pattern; a comma-separated list runs only the first.
- Hubs (M7N item 2, `hubs_*`, `perf_hubs_branch_recount`): a `network.ChannelHub` device node has a budget in `NetworkState.hubCaps`, read at rebuilds (and when the hub joins, which also asks for a rebuild) and kept until the next rebuild, so `hold` and `release` always see the same hubs. A device's walk counts the segments up to the first hub, then `hubUse` there; only the hub's first device walks on to take the hub's own channel, and only its last gives it back, so joins, leaves and patches behind a hub stop at the hub. Hubs are not `limited` (no device limit, no channel of their own while nobody is behind them). A full hub is `ChannelShortage.Kind.HUB` (appended last, so older ordinals in the status payload keep their kind).
- `StorageTestEnvironment` takes several options separated by `;` (values likewise); `roomy(option, value, profiling)` adds channel limits far above any test's device count for perf tests whose devices are laid out for density.
- Controller standby (M7N item 3, `controller_standby_*`): `NetworkState.pickActive` picks the controller in charge by `NetworkController.placedAt()` (game time saved as `placed_at`, read only when `level == null`), then position, on every controller join and leave; a change outside `rebuild` asks for one. A core's `refreshStatus` notices the switch and schedules its cluster fill, which keeps nothing while on standby. In a GameTest unload (see the `removeBlockEntity` fact above), a scheduled block tick on the removed core's position (`ControllerBlock.tick` -> `getBlockEntity`) also makes a fresh stand-in core with a new `placedAt`, which may claim expansions before the saved core is loaded back.

## Cable look J and REI's item list (verified 2026-10-15 in the REI 26.1.819 sources jar and by the `cable_glass_retired` GameTest; the look itself not yet seen in a client)

- REI lists every registered item, not only creative tab contents: `DefaultClientPlugin.registerEntries` adds the tab stacks and then `item.getDefaultInstance()` for every item in `BuiltInRegistries.ITEM` without a tab stack. Its priority is -100 and plugins run in ascending priority, so a plugin at the default 0 can take items out in its own `registerEntries(EntryRegistry)` with `registry.removeEntry(EntryStacks.of(item))` (`removeEntryIf(Predicate<? extends EntryStack<?>>)` also exists). Leaving an item out of the creative tabs alone does not hide it from REI.
- Storage cables are a glass sleeve in the translucent layer with a solid lane box inside (`CableGeometry`, `StorageCableModel`): a quad's layer comes from its sprite's whole-sprite `transparency()` in `QuadBaking`, so the sleeve sprites carry semi-transparent pixels and the lane and covered sprites are fully opaque. The model returns `BakedQuad.FLAG_TRANSLUCENT` from `materialFlags()` unless covered.
- Coverage GameTests (verified 2026-10-15): `Language.getInstance().has(key)` reads the mods' generated `en_us.json` on a GameTest server. `ComponentCopyingRecipe`'s serializer is not a recipe type, so JEI/KubeJS coverage tests list it in `StorageTests.SERIALIZERS_WITHOUT_OWN_TYPE`. Item coverage of the guide uses the creative-tab rule (`ModContent.CONTENT.creativeTabItems()`).

# Controller

## Controller cluster (M6c2 area F; patched 26.1.2.109 sources and the `controller_*` GameTests)

- `ValueOutput`/`ValueInput` have `putIntArray`/`getIntArray(name)` (an `Optional<int[]>`) but no long-array methods. Save a `long[]` with `output.store(name, Codec.LONG_STREAM, LongStream.of(array))` and read it with `input.read(name, Codec.LONG_STREAM).map(LongStream::toArray)` (`controller_cluster_saves`).
- `Block#onPlace` may schedule a tick at its own position (`level.scheduleTick(pos, this, 1)`) although its block entity does not exist yet; by the tick it does, so a block that must look at its neighbours with its block entity does it in `Block#tick`. Asking `level.getBlockTicks().hasScheduledTick(pos, block)` first makes several requests in one tick cost one tick (`controller_cluster_any_shape`).
- `LevelChunkTicks.schedule` keeps one scheduled tick per position and block (`ScheduledTick.UNIQUE_TICK_HASH` hashes
  only `pos` and `type`): while one is pending, a second `scheduleTick` for the same block and position is dropped,
  whatever its delay. Several users of one block's tick (the parts on a cable, `CableParts.scheduleTick`) must each
  ask again when the shared tick fires too early for them (patched 26.1.2.109 sources; `toggle_bus_clock_debounced`).
  To move a pending tick earlier, `ServerLevel.getBlockTicks()` (a `LevelTicks<Block>`) `.clearArea(new
  BoundingBox(pos))` removes every block tick at that position (pending, and those collected for this tick), then
  schedule again (`toggle_bus_shared_tick_on_time`).
- `MinecraftServer.getTickTimesNanos()` is a 100-slot ring of whole server tick times, written at the end of each tick
  at `tickCount % 100`; inside a tick (a GameTest step) the previous full tick is
  `getTickTimesNanos()[Math.floorMod(getTickCount() - 1, 100)]` (patched 26.1.2.109 sources;
  `perf_toggle_bus_clock_10k`).
- `BlockState.getLightEmission()` reads the `Properties.lightLevel(state -> ...)` function per state, so an `IntegerProperty` that picks the light level changes the light a block gives off when set with `UPDATE_CLIENTS` (`controller_cluster_any_shape`).
- `GameTestHelper.destroyBlock` and `setBlock` to another block both run the old block entity's `preRemoveSideEffects`, which a block entity can use to tell a neighbour it is gone (`controller_break_bank_keeps_network`, `controller_device_limit`); Storage's test teardown sets blocks with `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`, which skips it.
- Drops and `preRemoveSideEffects` run in different orders by path (verified 2026-10-15 by `controller_break_primary_keeps_state`): `Level.destroyBlock(pos, true)` (and so explosions and most mod breakers) works out the drops with the block entity first and removes the block afterwards, so `preRemoveSideEffects` runs after `getDrops`; a player's break removes first and drops afterwards (see the breaking entry below). A block entity that moves state elsewhere in `preRemoveSideEffects` must also leave it out of `collectImplicitComponents` when it is about to move it, or the item and the new holder both get it (`ControllerBlockEntity.handsOver`).
- Setting a block's own state from code that runs inside its `preRemoveSideEffects` (for example a network refresh that updates a status property with `level.setBlock(worldPosition, state.setValue(...))`) puts the block back: the removal is still in progress and the block stays (verified 2026-10-15, same test). Guard such updates with a flag set at the start of `preRemoveSideEffects`.

## Controller modules: bandwidth and power (M6c2 area G; Error Prone 2.x as configured here, `ScheduledTask` in Lib, the `controller_*` GameTests)

- Breaking a test on purpose with a constant condition does not compile: Error Prone's `ComplexBooleanConstant` fails `return false && x;`, and a parameter the break leaves unread fails `UnusedVariable`, both as errors under `-Werror`. Break with a value instead (`percent > 100`, `reserve(rate, percent * 0)`, a local set to `null` under the same null check). Adding `0 * intValue` to a `long` fails `NarrowCalculation`; write `0L * intValue`.
- (Removed in M7N with bandwidth; kept for history.) Bandwidth reserves (M7E item A): `NetworkState.reserveFor(LinkTransfer)` gives each traffic class the share it must leave on a segment. Players leave the crafting share, crafting tasks (`StorageTask.craftingTraffic()`, pattern and generator providers) leave the player share, other automation and P2P links leave both. The crafting share is held only for `max(40, 2 * crafting.providerPullTicks)` ticks after a crafting task last asked, so ports use the whole cable otherwise (`expert_ports_use_crafting_reserve_t2`, `expert_craft_reserve_t2_busy32`).
- Lib's `ScheduledTask.wake()` only re-queues the task in its group (backoff reset, `wakeAt = -1`); it runs nothing synchronously, so a network may wake a set of tasks while iterating that set and clear it afterwards.
- `Math.clamp(long value, int min, int max)` returns an `int` (Java 21+), so clamping a percent read from an `int` needs no cast.

## Cluster ghost preview (M7C item 22; compiled against 26.1.2 and verified by the `cluster_preview_*` GameTests on 2026-10-10; the rendering not yet seen in a client)

- Lib's ghost renderer (`lib.client.ghost.GhostBlocks`, drawn by `lib.neoforge.client.GhostClient`) submits two
  batches from `SubmitCustomGeometryEvent`: outlines through `RenderTypes.lines()` (the `BoxLines` vertex layout, line
  width from `windowRenderState.appropriateLineWidth`) and translucent faces through `RenderTypes.debugQuads()`; both
  are depth-tested, so walls hide ghosts. Faces are inset 1/8 so they do not z-fight with neighbouring blocks.
- A crafting CPU core placed with `helper.setBlock` counts its parts at once (a part's `onPlace` makes the core
  recount), but it is in `JobQueue.cpusIn(level)` only after its `onLoad` on the next block entity pass; a test that
  looks CPUs up through the queue waits for that.
- `BlockState.canBeReplaced()` (no arguments) is true for air, grass and fluids: the preview's test for a free cell.
- Turning a copied group (setup blueprints; patched 26.1.2 sources and the `setup_blueprint_*` GameTests):
  `Rotation.CODEC` (string), `Rotation.STREAM_CODEC` (`ByteBuf` id), `Rotation.getRotated(Rotation)` composes,
  `Rotation.rotate(Direction)` turns only horizontal faces, `BlockPos.rotate(Rotation)` turns around the origin (offsets
  can go negative, so shift the turned box back), and `BlockState.rotate(Rotation)` turns facing properties.
  `BlockState.CODEC` saves a full state; `ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)` sends one as a var-int.
  `LevelReader.hasChunksAt(BlockPos, BlockPos)` checks a box is loaded. DFU 9.0.19 has `Codec.listOf(min, max)`, and
  `Codec.validate` checks both encode and decode, so an invalid value also fails to save.
- `PortBlock.getStateForPlacement` faces the port away from the clicked face (`getClickedFace().getOpposite()`), and a
  port joins a cable only through its back, so a test that places one with `useOn` on the floor gets a port facing down
  in its own ad-hoc network. Click the north face of a block south of the port's cell to face it south.
- When a `BlockEvent.EntityPlaceEvent` listener cancels a placement made through `ItemStack.useOn`,
  `CommonHooks.onPlaceItemIntoWorld` restores the snapshots and then sends the player an inventory slot update through
  `serverPlayer.connection` without a null check, so a GameTest that refuses a placement needs a player with a
  connection (`helper.makeMockServerPlayerInLevel()`); `new ServerPlayer(...)` crashes the server there (found by
  `setup_build_refused`).
- `LevelChunk.setBlockState` keeps the block entity when the new state is of the same block (it only calls
  `blockEntity.setBlockState`), so `onLoad` does not run again and a device that read its facing on load keeps the old
  one. To place a block with a given facing as a player would, pick the clicked face and view first:
  `Block.getStateForPlacement(new BlockPlaceContext(level, player, hand, stack, hit))` is pure, reads the facing from
  `getClickedFace`, `player.getDirection()` (`yRot`) or `Direction.orderedByNearest(player)` (`xRot` and `yHeadRot`),
  so set those three on the player, ask, place with the same hit, and restore them (`SetupBuild.aim`, verified by
  `setup_build_rotated`).

## Controller upgrade cards: container saves, slot visibility, drops (M7R item 2; verified 2026-10-15 in the patched 26.1.2.109 sources and by the `controller_cards_*` GameTests; the Cards tab not yet seen in a client)

- `SimpleContainer.storeAsItemList`/`fromItemList` drop slot positions: `fromItemList` calls `clearContent()` (one `setChanged`) and `addItem` per stack, which packs them into the first slots. For slot-keeping saves use `ContainerHelper.saveAllItems(output.child(name), items, false)` and `ContainerHelper.loadAllItems(input.child(name).get(), items)`, which write `Items` as `ItemStackWithSlot` and call no `setChanged`.
- `Slot.isActive()` is read only by `AbstractContainerScreen` (drawing, hovering, clicking); the server never asks it. A menu with tabs can keep every slot on both sides and hide a tab's slots with a client-side flag in `isActive()`.
- `Block.popResource` drops nothing while the `BLOCK_DROPS` gamerule is off; `Containers.dropItemStack(level, x, y, z, stack)` always spawns the entities (in parts of 10 to 30), so contents that must never be lost drop through it.
- `GameTestHelper.assertValueEqual(value, expected, name)` takes the actual value first; its message reads "Expected <name> to be <value>: was <expected>", so the first number shown is the actual one.
- A `GameTestSequence.thenWaitUntil` whose condition already holds passes in the same tick: a change that works at once (a card installed) followed by a wait and a measurement lands in the same tick as anything measured before it; `thenIdle(1)` separates them.

## Controller face model (M7R item 7; verified 2026-10-15 in the 26.1.2 merged sources and NeoForge 26.1.2.109 sources, by `runData` and the `controller_face_*` GameTests; not yet seen in a client)

- `FaceBakery.defaultFaceUV` maps a full cube face so the texture's u axis runs: DOWN and UP +x, NORTH -x, SOUTH +x, WEST +z, EAST -z; v runs down on the four sides, +z on UP and -z on DOWN (texture top is north on UP, south on DOWN). A connected-texture mask must use this table (`ControllerFace.EDGES`).
- `RenderSectionRegion.RADIUS` is 1: chunk meshing reads the section and one full section around it, so a dynamic model may read any block within one block of its own (all 26 neighbours).
- `BakedQuad.FLAG_TRANSLUCENT` = 1 and `FLAG_ANIMATED` = 2 are the constants for `BlockStateModel.materialFlags()`.
- `IBlockEntityExtension.onDataPacket(Connection, ValueInput)` and `handleUpdateTag(ValueInput)` can be overridden to read only the synced fields; the defaults run `loadWithComponents`, which would put a partial update tag through the block entity's whole load.
- A block state file with the single variant `""` covers every state of a block with properties (`BlockModelGenerators.createSimpleBlock`), and `ModelProvider` accepts it.
- Error Prone's `EnumOrdinal` fails the build on `Direction.ordinal()`; `Direction.get3DDataValue()` (DOWN 0 to EAST 5) is the stable index.
- Per-pixel glow on a chunk-meshed face: emission is per quad, so the face is a cutout plate with the glowing pixels cut out plus an opaque, full-bright plane a little behind it (`ControllerFaceModel.GLOW_INSET`, 0.25 px), never coplanar. All copies of one animated sprite share the atlas clock; a per-block phase needs separate sprites started at different frames, picked at meshing.

## Controller recorders: periodic tasks, run counts, signals (M7R item 3; verified 2026-10-15 by the `controller_recorders_*`, `controller_alarm_*` and `perf_*` GameTests; the History tab and the terminal's tab not yet seen in a client)

- `StorageDeviceBlockEntity.schedule` on a task that is already scheduled wakes it, so it runs on the next pass: the
  controller's telemetry task, scheduled again on every load-display refresh, sampled every few seconds instead of every
  `sampleTicks` and showed up as extra scheduler runs in `perf_storage_idle_world_devices`. Keep a flag and schedule a
  periodic task once (`ControllerRecorders.sampling`).
- Every controller samples its telemetry (one scheduler run per `sampleTicks`), so a GameTest that expects no scheduler
  runs on an idle network counts `NetworkGameTests.taskRuns(network)`: the runs minus the recorder's
  `samplesTaken()`.
- A rule (or anything) that asks for an evaluation from `NetworkController.refreshLoad` polls: that hook runs every
  few seconds, so wake only when what it checks changed (`ControllerRecorders.refresh` compares the active flag).
- The controller block is a redstone source (`isSignalSource` true, `getSignal` reads the primary's rules); after the
  output changes the primary calls `updateNeighborsAt` for itself and each loaded active member. Members read the
  primary through `primary()`, so a client (no primary position synced) reads 0.
- `NetworkOverview` no longer carries telemetry samples: a new sample changed the overview and made the Network tab
  re-send it every sample (`controller_network_tab_sync`); the History tab asks for its page through `RecordsPayloads`.
- A `DEVICE_SWITCH` node whose `on` input is false turns its labelled device off on the graph's first run, before
  any event. `perf_logic_idle_20_graphs` had its latch graph switch the watched sensor "eye": whether the sensor read
  its chest before the switch depended on which task the network's scheduler ran first, so it failed about half the
  runs in a fresh server ("the sensor has not read its chest"), and when it passed its idle "no sensor reads" check
  was vacuous. The switch now targets a second sensor "lamp", and the test asserts the watched sensor is on (verified
  2026-10-17: 0 failures in 30 + 30 + 50 runs, the last under CPU load; 17 of 30 failed before).

# Cells, drives and saving

## Storage cells and drives (M6a area 2; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources, by `:storage:neoforge:runData` and the `cell_*`, `drive_*` and `perf_cells_save_changed` GameTests; the drive's model data not yet seen in a running client)

- Saving saved data: `SavedDataStorage.scheduleSave` encodes every dirty `SavedData` on the server thread (`collectDirtyTagsToSave`, with `registries.createSerializationContext(NbtOps.INSTANCE)`, then `setDirty(false)`) and only writes the finished `CompoundTag`s on `Util.ioPool()`, so a codec sees a consistent state and its result is a snapshot. Loading parses with the same kind of `RegistryOps<Tag>`. A codec can therefore reuse an unchanged part's earlier `Tag` when `ops.empty() instanceof Tag` (the ops' values are `Tag`s); the cached tag must never be mutated afterwards, since a write may still be running.
- Encoding cost (JFR on `perf_cells_save_changed`, 2026-11-26): `StackKey.CODEC` (a key dispatch codec with an optional components patch) was about 80% of a cell save and most of its allocation when run per key per cell; encoding each key once per save and sharing the `Tag` among the cells' key lists, and reusing an unchanged cell's whole list entry, cut a 1,000-cell full save from about 17 ms to 2 ms warm. Building the `{keys, indices, counts}` map by hand through the ops gives the same NBT as `KeyPalette.codec` (`cell_save_matches_palette_codec`). The entry iterator of fastutil's unmodifiable `Int2LongMap` view allocates an entry per call even through `Int2LongMaps.fastIterator`; its `keySet().iterator()` does not.
- `SavedDataType` has level-aware overloads (`Factory<T>`, `Factory<Codec<T>>` taking the `@Nullable ServerLevel`) besides `(Identifier, Supplier<T>, Codec<T>)`; `server.overworld().getDataStorage().computeIfAbsent(type)` gives one instance for the whole server.
- The data storage class is `net.minecraft.world.level.storage.SavedDataStorage` in 26.1.2 (no `DimensionDataStorage`); `level.getDataStorage().set(type, data)` replaces the cached instance, so a GameTest can simulate a restart by encoding a `SavedData` with its codec (`RegistryOps.create(NbtOps.INSTANCE, registryAccess)`), parsing it back and `set`ting the copy; code that looks the data up through `computeIfAbsent` each time then sees the loaded copy (`cpu_inputs_in_runs_reload`, checked in the patched sources jar).
- A crafting result with components: `ShapedRecipeBuilder.shaped(HolderGetter<Item>, RecipeCategory, ItemStackTemplate)` and `new ItemStackTemplate(item, DataComponentPatch.builder().set(type, value).build())`; datagen writes `"result": {"id": ..., "components": {...}}`. The builder's item getter is only used by `define(char, TagKey)`, so with `define(char, Ingredient)` (`RecipeContext.ingredient(tag)`) `BuiltInRegistries.ITEM` can stand in for it. A plain-record component encodes fine in `runData` (unlike fluid stacks).
- `ItemStacksResourceHandler.getCapacity(index, resource)` is `min(maxStackSize, 99)`, and 99 for the empty resource; a one-item-per-slot handler overrides it to 1 so hoppers see a filled slot as full. `getResource(index)` is overridable and is what an extractor reads before calling `extract(index, resource, ...)`, which must match the slot's stack exactly.
- Model data on the client: `requestModelDataUpdate()` only queues a refresh (`ModelDataManager.requestRefresh`, client level only); the section must also be re-meshed. A server `sendBlockUpdated` with the same state sends the block entity's update packet, but the client's block update with an unchanged state changes nothing and re-meshes nothing, so the block entity calls `level.sendBlockUpdated(pos, state, state, Block.UPDATE_IMMEDIATE)` on the client after loading new render data (`ClientLevel.sendBlockUpdated` -> `LevelRenderer.blockChanged` -> `setBlockDirty`).
- `TypedEntityData.loadInto(BlockEntity, HolderLookup.Provider)` merges the item's tag onto the block entity's `saveCustomOnly` output and calls `loadCustomOnly`, the same path a placed `BlockItem` takes; a GameTest uses it to "place" a dropped item's data.
- A filtered view of a slot handler (B09 item 11, IO port: inserts only into input slots, extracts only from output slots) implements `ResourceHandler<T>` directly: the abstract methods are `size`, `getResource`, `getAmountAsLong`, `getCapacityAsLong`, `isValid`, `insert(index, ...)` and `extract(index, ...)`; the default `insert(resource, ...)`/`extract(resource, ...)` loop over the indexed ones. `DelegatingResourceHandler` exists but a subclass cannot reach the interface defaults through `super`. `StacksResourceHandler.insert(index, ...)` already refuses what `isValid` refuses, while `set(index, resource, amount)` skips `isValid` (and fires `onContentsChanged` at once), so a handler can hold items in slots the outside cannot fill (NeoForge 26.1.2.109 sources).
- `GameTestHelper.useBlock(pos, player)` runs `BlockState.useItemOn` with the main hand item, then `useWithoutItem` after `TRY_WITH_EMPTY_HAND`, then the item's `useOn`; a `makeMockPlayer` player is no game master, so owner checks refuse it.
- A `NullPointerException` (any non-assertion exception) thrown from a GameTest sequence lambda crashes the whole game test server, not just the test; tests turn a missing value into `helper.assertionException(...)`.
- DFU `Codec.sizeLimitedListOf(int)` and `ByteBufCodecs.list(int)` bound list sizes in a component's codecs.
- Error Prone's `ReferenceEquality` also fires on `!=` between records (`CellData`) and on `item != Items.AIR` in test code; compare fields, or test `new ItemStack(item).isEmpty()`.

## Cell parts and compatible components (M6b area B; verified in the patched 26.1.2.109 sources, by compiling and by the `cell_parts_*` GameTests)

- `StreamCodec.composite` has overloads up to 12 fields (patched sources); `RecordCodecBuilder` groups go to 16.
- Adding fields to a persistent data component without breaking saved items: `optionalFieldOf(name, default)` for each new field. `ItemStack.CODEC.parse(registryAccess.createSerializationContext(JsonOps.INSTANCE), json)` of an item JSON in the old shape checks it (`cell_parts_old_starter_cell`); a required `fieldOf` fails with "No key ... in MapLike".
- `Item.getName(ItemStack)` takes the stack; there is no no-argument `getName()`. For a part list in a tooltip, `Component.translatable(item.getDescriptionId())`.
- Matching a tag without allocating: `item.builtInRegistryHolder().is(TagKey<Item>)` and `fluid.builtInRegistryHolder().is(TagKey<Fluid>)`; `TagKey.location()` is the tag's `Identifier`.
- Listing a registry's tags (26.1.2 patched sources): `Registry.getTags()` is a `Stream<HolderSet.Named<T>>` (`.key()` is the `TagKey`); `registry.get(TagKey)` is an `Optional<HolderSet.Named<T>>`. Source fluids only: `fluid.defaultFluidState().isSource()` (false for `Fluids.EMPTY` and flowing variants). `TagKey.codec(Registries.FLUID).fieldOf("fluid_tag")` round-trips in plain JUnit, no bootstrap (`LogicKeyCodecTest`). Error Prone's `ReturnValueIgnored` also rejects a bare `Boolean.getBoolean("never");` statement in a test break.
- Storage's `CellFilter.CODEC` (entries via `StackKey.CODEC`, a `Kind.CODEC.dispatch`) encodes and parses in plain JUnit without bootstrap as long as the entry list is empty: the dispatch reaches `ItemKey`'s registry codec only per entry (`CellFilterTest`, B09 item 10). Building an `ItemKey` still needs bootstrap, so key matching stays in GameTests (`cell_deny_matcher`).
- Lib's `ConfigBuilder.defineInRange` takes dotted paths (`"small.memorySlots"`); `ModConfigSpec.Builder` splits them into sections, and the preset key is the full dotted path (`cells.casings.small.memorySlots`).
- Error Prone warnings fail the build, so a deliberate test break that leaves a field unread or mixes `&&` and `||` without parentheses does not compile (`UnusedVariable`, `OperatorPrecedence`, NullAway); break a test by changing a value instead.

## Cell workbench and portable cell (M6b area B; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources, by compiling and by the `workbench_*` GameTests; the screen not yet seen in a running client)

- A block entity drops its contents on removal from `BlockEntity.preRemoveSideEffects(BlockPos, BlockState)`; the default does `Containers.dropContents` only when the block entity itself implements `Container`, so one holding a `SimpleContainer` field overrides it and calls `Containers.dropContents(level, pos, container)`.
- Saving one stack: `ValueOutput.storeNullable(name, ItemStack.CODEC, stackOrNull)` and `ValueInput.read(name, ItemStack.CODEC)` (an `Optional`); `ItemStack.CODEC` refuses empty stacks, hence the nullable form.
- A menu slot whose shown item is computed (parts named in a component): override `getItem()`, make `set`/`setByPlayer`/`remove` no-ops and `mayPlace`/`mayPickup` false, and handle its clicks in `AbstractContainerMenu.clicked` on the server only (`level.getServer() == null` on the client). The client predicts nothing, and vanilla's slot sync and carried-stack sync bring the server's result back; the client's own `set` from that sync is ignored.
- `Level.getServer()` is `@Nullable` (null on the client); Error Prone's `BadInstanceof` rejects `getServer() instanceof MinecraftServer server` as a null check in disguise.
- Error Prone's `ReferenceEquality` also fires on `player == otherPlayer` (`Entity` defines `equals`); compare `getUUID()`.
- Item FE: `ItemAccessEnergyHandler(ItemAccess, DataComponentType<Integer>, capacity, maxInsert, maxExtract)` keeps the charge in an `Integer` component; register it with `RegisterCapabilitiesEvent.registerItem(Capabilities.Energy.ITEM, (stack, access) -> handler, items...)`.
- `Player.isSecondaryUseActive()` is `isShiftKeyDown()` (sneaking); when it holds and the hand is not empty, the block's `useItemOn` is skipped and `Item.useOn` runs, which is how a sneak-use of an item on a block reaches the item. A GameTest calls `item.useOn(new UseOnContext(player, hand, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)))` after `player.setShiftKeyDown(true)`.
- `AbstractContainerMenu.removed(player)` touches the connection only when the carried stack is not empty, so a GameTest player without a connection can close a menu with an empty cursor.
- `ItemAccess.forPlayerInteraction(player, hand)` uses `forInfiniteMaterials` for a player with infinite materials (creative, which GameTest players can be), whose `exchange` never changes the held stack; a test that charges a held item through its capability uses `ItemAccess.forPlayerSlot(player, inventory.getSelectedSlot())`. `ItemAccessEnergyHandler` writes through `exchange`, so the hand may hold a new stack afterwards: re-read the hand instead of keeping the stack.

## M6f area B: bulk drive

- The bulk drive's screen sends its `long` count as four 16-bit data slots. A data slot itself carries a full `int` on NeoForge (`AdvancedContainerSetDataPayload`, see "Data slots" above); vanilla's packet alone would cut it to 16 bits.
- `GameTestHelper.makeMockPlayer(GameType.CREATIVE)` returns a player whose `gameMode()` is creative but whose `abilities.instabuild` stays false, so `preventsBlockDrops()` is false; set `getAbilities().instabuild = true` to test a creative break (`bulk_drive_drops_once`).
- `GameTestHelper.useBlock(pos, player)` falls through to `stack.useOn` when the block's `useItemOn` returns a result that does not consume the action, `FAIL` included: a refused use with a placeable block in hand places it next to the device and takes one from the stack. Tests of a refusal hold a non-placeable item.
- `Tag.sizeInBytes()` is the NBT accounting estimate (745 for a tag of three short entries), not the written size; measure save size with `NbtIo.write(tag, new DataOutputStream(out))` (about 100 bytes written for such a tag).
- `BlockEntity.applyComponents` forgets the component types `applyImplicitComponents` read, so contents taken from an item component are not saved a second time in the block entity's `components` tag (read from the 26.1.2 sources, `patch.forget(implicitComponents::contains)`).

## Drive screen and the shared priority tab (2026-10-07; verified in the NeoForge 26.1.2.109 sources, JEI 29.43.0.106 API sources, `javap` on the 26.1.2 client jar and the `drive_*` and `cell_parts_old_priority_cell` GameTests; the screens not yet seen in a running client)

- `ResourceHandlerSlot.getStackCopy()` reads `handler.getResource(index).toStack(amount)`, so a handler that overrides
  `getResource` (the drive's, which writes fresh cell stats into the stack first) feeds GUI slots the same way it feeds
  extractors. `StackCopySlot.remove(amount)` copies, splits and `set`s the rest, so taking a cell out goes through the
  handler's `set` and `onContentsChanged`.
- `ContainerInput` (26.1's `ClickType`) has `PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL`. A creative
  clone needs `Player.hasInfiniteMaterials()`, which is `abilities.instabuild`; a test can set that on a test player
  without changing its game mode. A menu refuses `CLONE` on a slot by overriding `clicked`.
- `RecordCodecBuilder` codecs ignore keys they do not name, so a field dropped from a data component's codec (the
  cell's old `priority`) leaves saved items loading without error.
- JEI hides a registered item at runtime with
  `runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.of(stack))` in
  `onRuntimeAvailable`.
- Error Prone's `ReferenceEquality` flags `!=` between `player.containerMenu` and a menu typed as an intersection type
  variable (`M extends AbstractContainerMenu & PriorityMenu`); compare `containerId` instead.
- DataFixerUpper 9.0.19 (sources jar): `Codec.STRING.dispatch("kind", getter, name -> mapCodec)` writes the key beside
  the map codec's own fields (`{"kind": "number", "value": 12}`); `Codec.string(min, max)` and
  `codec.listOf(min, max)` bound sizes on decode. The dispatch function must return a codec for every name, so validate
  the key codec first (`Codec.STRING.validate(...)`) to reject unknown kinds (logic `SettingValue`, M7L).
- Error Prone's `RefactorSwitch` fails the build on a statement `switch` whose every case returns; write
  `return switch (...) { case X -> { ...; yield v; } }`. `OperatorPrecedence` wants parentheses in `a || b && c`
  (verified by `:storage:common:compileJava`).
- JUnit in `storage:common` can measure allocation: `ManagementFactory.getThreadMXBean()` is a
  `com.sun.management.ThreadMXBean` with `getCurrentThreadAllocatedBytes()` (the logic evaluator's zero-allocation
  test uses it and skips where unsupported).
- `BlockEntity.applyImplicitComponents` runs only from `applyComponents`/`applyComponentsFromItemStack` (placing an
  item, a test), never from `loadWithComponents`, so a default read there (`getOrDefault(type, NONE)`) does not undo
  what `loadAdditional` read on chunk load (patched 26.1.2.109 sources; `label_saved_and_dropped`). Vanilla's
  `MutableComponent.withColor(int)` colours a literal, and `DyeColor.getTextColor()` / `getTextureDiffuseColor()` are
  opaque ARGB (the label line and swatches use them).

# Terminals, screens and slots

## Storage terminal and storage chest (M6a area 3; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources, by compiling and by the `terminal_*` and `storage_chest_*` GameTests; the screen not yet seen in a running client)

- Packet size limits: vanilla's `ClientboundCustomPayloadPacket` caps only unknown (discarded) payloads at 1 MiB
  (`MAX_PAYLOAD_SIZE` 1048576); a registered payload's codec has no cap of its own. Frames carry a 3-byte var-int length
  (`Varint21FrameDecoder`, 2 MiB) and `CompressionDecoder` refuses more than 8 MiB uncompressed. NeoForge's
  `GenericPacketSplitter` splits a packet whose encoding exceeds those limits into `neoforge:split` parts, so very
  large payloads may still arrive, but Lib's synced lists keep each batch under `SyncedListUpdate.MAX_BYTES` (512 KiB)
  by measuring definitions with the real codec (`SyncedListType.sizer`).
- Client to server is the same: `ServerboundCustomPayloadPacket`'s 32,767-byte `MAX_PAYLOAD_SIZE` applies only to
  discarded (unregistered) payloads, so a registered payload such as the logic editor's whole-graph save is bounded by
  its own codec (`ByteBufCodecs.COMPOUND_TAG` uses `NbtAccounter.defaultQuota()`, 2 MiB) (M7L area F, patched sources).
- `ByteBufCodecs.STRING_UTF8` refuses strings over 32,767 characters on encode (`EncoderException: String too big`).
- `ServerboundContainerButtonClickPacket(containerId, buttonId)` writes the button id as a var-int, so a menu can pack an
  action and an entry id into it (Storage's `TerminalAction`: four bits of kind, the rest the key id plus one) and needs
  no payload of its own; the server checks container id, spectator and `stillValid` first.
- `AbstractContainerMenu.removed(player)` hands the carried stack back through `Inventory.placeItemBackInInventory`,
  which sends a packet through `player.connection`: with a GameTest `ServerPlayer` (no connection) it throws a
  `NullPointerException` in the server tick loop, so tests clear the cursor before calling `removed`. On logout,
  `ServerLevel.removePlayerImmediately` calls `Player.remove`, which closes the open menu, so `removed` runs then too.
- `ServerPlayer.sendOverlayMessage(Component)` shows an action-bar message (`sendSystemMessage(Component, boolean
  overlay)` also exists); both need a connection.
- A carried fluid container: `ItemAccess.forPlayerCursor(player, menu).oneByOne()` then
  `access.getCapability(Capabilities.Fluid.ITEM)` gives a `ResourceHandler<FluidResource>` (null for non-containers);
  inserting into it inside a transaction swaps an empty bucket on the cursor for a filled one when the transaction
  commits. A bucket takes and gives exactly 1,000 mB, so probe the amount in a nested transaction first.
- `StacksResourceHandler.onContentsChanged(index, previousContents)` runs on root commit (per changed index) and on
  `set`, but not on `deserialize` (which replaces the list): totals kept from it must be rebuilt after loading.
- Lib's `NetworkGraph.merge` keeps the larger network (`a.size() >= b.size()`) and moves the smaller one's nodes, so a
  device in the smaller part changes `NetworkState`; a split moves the parts it finished flood-filling into new
  networks and leaves the rest in the original, so tests must not assume which side of a split keeps the old one.
- `ItemLore` holds at most 256 lines (`ItemLore.MAX_LINES`); a lore of 64 lines of 400 characters makes a key of about
  25 KiB on the wire, which is how the byte-batching GameTest builds component-heavy keys.

## Pocket uplink and restock belt (M6e area C; verified in the NeoForge 26.1.2.109 patches, the patched 26.1.2.109 sources and by the `pocket_*` GameTests)

- `ItemEntityPickupEvent.Pre` (game bus, server only) fires on every touch, before vanilla's pickup delay and target checks; its `canPickup()` is a `net.minecraft.util.TriState` (not NeoForge's). `TRUE` skips those checks, `FALSE` stops the pickup, `DEFAULT` keeps them, so a handler that acts on the item must repeat them itself (`ItemEntity.hasPickUpDelay()`, NeoForge's `getTarget()`). Shrinking `getItemEntity().getItem()` is allowed (never `setItem`). `Post` fires only when part of the stack went into the inventory, so it never sees a full inventory. `ItemEntity`'s plain constructor leaves no pickup delay; `setNoPickUpDelay()` makes sure.
- Vanilla's room checks: `Inventory.getFreeSlot()` looks at the 36 main slots only; `getSlotWithRemainingSpace(stack)` tries the selected slot, the offhand (40), then the main slots, and needs a non-empty stackable match. Both -1 means `add` would take nothing.
- Lib's server tick hook is `Platform.get().lifecycle().onServerTickEnd(Consumer<MinecraftServer>)`; there is no `events()` accessor. `server.getTickCount() % n` gives a cadence without a ticker of our own.
- DFU's `Codec.sizeLimitedListOf(int)` exists and refuses longer lists; `Minecraft.hasShiftDown()` replaces the old static `Screen.hasShiftDown()` (compiled).
- A controller's owner reaches its network when the network forms: a GameTest that wants an owned network calls `ControllerBlockEntity.setOwner` right after placing the controller, before waiting for the network; set later, `NetworkState.accessLevel` still answers for the unowned network.
- A menu over the player's own inventory (`addStandardInventorySlots`) syncs the main hand's stack through its slots, so an item that keeps its settings in a component and opens from the main hand needs no payload; the offhand is not one of those slots (reasoned from vanilla's slot list; the belt opens from the main hand only).

## Terminal comfort (M6e area D; verified in the patched 26.1.2.109 sources and by the `terminal_comfort_*` GameTests unless marked)

- `KeyMapping.matches(KeyEvent)` tests a key event against the mapping's current key (keysym, or scancode for an unknown key), so a screen checks its own mappings in `keyPressed` before `super`. The patched `KeyMapping` has `setKeyConflictContext(IKeyConflictContext)`, so a mapping built in `common` with the plain vanilla constructor `new KeyMapping(nameKey, InputConstants.KEY_Z, category)` gets `KeyConflictContext.GUI` from the loader glue before `RegisterKeyMappingsEvent#register` (compiled; not yet seen in a client). Vanilla's `Options` binds neither Z nor R.
- `Inventory.SELECTION_SIZE` / `Inventory.getSelectionSize()` is 9 (the hotbar is slots 0 to 8); `INVENTORY_SIZE` is 36.
- Lib's `CraftingGridMenu.gridSlot` is only used by the default `receiveRecipe` (`CraftingGridFill`), so a menu without a grid (the terminal) can implement `CraftingGridMenu`, override `receiveRecipe` and register with `CraftingGridMenus.register` to get JEI's "+" on crafting recipes; `CraftingGridMenus.receive(new Transfer(containerId, cells, max), player)` reaches it in a GameTest once `player.containerMenu` is the menu.
- `RegistryAccess.createSerializationContext(JsonOps.INSTANCE)` with `StackKey.CODEC` writes and reads keys as JSON on the client (the terminal's pins file); parse each element on its own so one missing item costs only its entry (compiled; not yet seen in a client).

## List screens and task boards (M7C item 15; verified in the patched 26.1.2.109 sources and by the `task_board_*` GameTests on 2026-10-10)

- `GameTestHelper.assertValueEqual(value, expected, name)` takes the value first; its message reads "Expected <name> to be <value>, was <expected>", so the numbers look swapped in a failure.
- `ServerPlayer.lookAt(anchor, Vec3)` sends a `ClientboundPlayerLookAtPacket` through `connection`, which a mock `ServerPlayer` lacks (NPE). Tests aim a mock player with `setXRot`/`setYRot` (and `xRotO`/`yRotO`) as `Entity.lookAt` computes them; `getViewVector(1.0F)` then reads those.
- A display panel placed with `helper.setBlock` has no owner, and `NetworkAccess.canConfigure` lets everyone configure an unowned device; tests of refusals give the panel an owner (`setOwner(OwnerRecord.of(player))`).
- A network's owner comes from its single controller on each rebuild (`NetworkState.findOwner`); right after `setPlacedBy` a test's first network may still be unowned. Code deriving keys from `network.owner()` must read it again rather than cache it at bind (the task feed does, `TaskFeed.follow`), and tests wait for `network.owner().isOwner(...)`.
- `JobEvents.finished` fires for jobs that end done and partial, and carries no amount or job id; a listener that must count what was made uses `JobEvents.watchDone` (job id, key, amount; done jobs only, per network). A global "last done job" kept from `JobQueue.listen` is wrong: a partial job's "finished" on another network would read it.

## Terminal tooltip search and view (B09 item 17; verified 2026-10-18 in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources, by compiling and by JUnit; not yet seen in a client)

- `Screen.getTooltipFromItem(Minecraft, ItemStack)` calls `stack.getTooltipLines(Item.TooltipContext.of(level, player), player, ClientTooltipFlag.of(advancedItemTooltips ? ADVANCED : NORMAL))`, so its lines include NeoForge's tooltip event (other mods' lines) and change with F3+H; a cache of them must key on `options.advancedItemTooltips` too.
- `LanguageSelectScreen` applies a language with `languageManager.setSelected(code)` and then `minecraft.reloadResourcePacks()`, so a client reload listener (`AddClientReloadListenersEvent`, a `ResourceManagerReloadListener` lambda) also runs on a language change.
- AE2's terminal search (`RepoSearch`, main branch): `@` mod, `$` tooltip, `#` tag, `*` item id; Storage's `@`, `#` and `$` mean the same.
- Error Prone in this build fails on `UnnecessaryLambda` (a lambda kept in a `static final Predicate`) and `EnumOrdinal` (`values()[ordinal() + 1]`); an enum that implements `Predicate` itself and a `switch` for "next" avoid both.

## Crafting terminal grid (B09 item 13; verified 2026-10-18 in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `crafting_terminal_*` GameTests; the screen not yet seen in a client)

- `RecipeManager.getRecipeFor(type, input, level, @Nullable RecipeHolder<T> hint)` tries the hint's `matches` first and only then scans all recipes of the type; pass the last recipe so repeated crafts skip the scan. A `null` literal is ambiguous with the `ResourceKey` overload, so pass a typed variable.
- `ServerPlayer.level()` returns `ServerLevel`; `player.level() instanceof ServerLevel level` on a `ServerPlayer` is an Error Prone `BadInstanceof` error.
- `AbstractContainerMenu.doClick` with `QUICK_MOVE` checks `slot.mayPickup` once, then calls `quickMoveStack` again while it returns a stack of the slot's item. A result slot that crafts many itself returns `ItemStack.EMPTY` to stop that loop. `PICKUP` on a result slot with the same item on the cursor merges through `slot.tryRemove` and `onTake`.
- `ResultSlot.remove(n)` counts `removeCount`, and the protected `checkTakeAchievements(stack)` uses it for `onCraftedBy`, NeoForge's crafting event and `awardUsedRecipes`; a subclass that replaces `onTake` calls it first. `awardUsedRecipes` clears the container's recipe, so set it again (`setRecipeUsed(player, holder)`) whenever the result is worked out.
- A `CraftingContainer` of your own needs `getWidth`, `getHeight`, `getItems` and `fillStackedContents` besides `Container`; `asPositionedCraftInput()` comes as a default. `ContainerHelper.saveAllItems(output.child(name), list, false)` and `loadAllItems(input.childOrEmpty(name), list)` keep slot indices.
- NeoForge 26.1.2.109 ships the item tag `c:player_workstations/crafting_tables` (data in its universal jar), so a recipe may name it without a datagen'd empty tag.
- `CommonHooks.setCraftingPlayer(player)` / `getCraftingPlayer()` (NeoForge 26.1.2.109) are what vanilla's `ResultSlot.onTake` sets around `getRemainingItems`; vanilla's `CrafterBlock` does not set it. From `common` go through Lib's `MenuHelper.craftingRemainders` (`crafting_terminal_worn_tool_stays` checks a remainder hook sees the player). An `Item` subclass in a GameTest overrides `getCraftingRemainder(ItemInstance)` (`net.minecraft.world.item.ItemInstance`, the grid's `ItemStack`) and returns `ItemStackTemplate.fromNonEmptyStack(worn)` or null; a recipe for such a test item goes in the gametest data under its own namespace (`data/maxtech_test/recipe/`).

## Device side tabs and the redstone mode (B09 items 21b and 9; verified 2026-10-18 in the patched 26.1.2.109 sources and by the `redstone_*` GameTests and `DeviceTabTest`; the screens not yet seen in a client)

- `Slot.x` and `Slot.y` are `public final`: a slot cannot move after the menu is made. Slots shown in a flyout beside the panel (Storage's `UpgradeSlot`) sit at fixed positions right of the tab column (`DeviceTabLayout`) and are active only while the flyout is open there; `Slot.isActive()` is asked only by `AbstractContainerScreen`, so shift-clicks into them work with the flyout closed.
- `AbstractContainerScreen.mouseClicked`/`mouseReleased` treat a click on any active slot as inside (NeoForge patch: `if (slot != null) clickedOutside = false`); a click outside `imageWidth`/`imageHeight` that is on no slot throws the carried stack, so a screen with tabs or flyouts outside the panel overrides `hasClickedOutside`.
- Moving slots on the client (B09 item 21c, verified 2026-10-19 in the patched sources and by the `*terminal*`
  GameTests): `AbstractContainerMenu.slots` is a `public final NonNullList<Slot>` and `Slot.index` a public mutable
  field, so a client menu can `slots.set(i, copy)` with `copy.index = i` (same container and container slot, new x/y);
  `lastSlots`/`remoteSlots` are only used by the server's broadcast, so nothing else needs touching. `AbstractContainerScreen.imageHeight` is `protected final`: a screen that changes height is
  made again over the same menu. `Minecraft.setScreen` calls the old screen's `removed()`, which calls
  `menu.removed(player)`; vanilla's `AbstractContainerMenu.removed` acts only for a `ServerPlayer`, so the client menu
  survives the swap. `Screen.resize` reruns `init()` through `rebuildWidgets`.
- `AbstractContainerScreen.getHoveredSlot(double, double)` is private; subclasses read the `hoveredSlot` field (updated each frame) or NeoForge's public `getHoveredSlot()`.
- `AbstractContainerScreen.keyPressed` closes the screen on the inventory key unless `super.keyPressed` (the focused widget) returned true, and `EditBox.keyPressed` returns false for printable keys (they arrive through `charTyped`); a focused text field must take every non-Escape key itself, as the terminal search does.
- `SignalGetter.hasNeighborSignal(pos)` calls `getSignal(neighbour, dir)`, which for a redstone conductor also reads `getDirectSignalTo(neighbour)`, the neighbour's own six neighbours; `Level.getBlockState` loads a missing chunk on the server. Storage's `RedstoneSignal.read` does the same reads behind `Level.isLoaded` checks and reports "unknown" instead of loading.
- `Screen.rebuildWidgets()` (protected) clears every widget and calls `init()` again, firing NeoForge's `ScreenEvent.Init.Pre`/`Post`; device screens call it from `containerTick` when their set of tabs changes.
- `net.minecraft.world.level.ItemLike` (not `world.item`) in 26.1.
- Error Prone's `EnumOrdinal` fails the build on `ordinal()`; enums that need an order or id carry it as a field.
- JEI 29.43 ghost drags (common-api sources): `IGuiHandlerRegistration.addGhostIngredientHandler(Class<T extends Screen>, IGhostIngredientHandler<T>)`; the handler's `<I> List<Target<I>> getTargetsTyped(T gui, ITypedIngredient<I> ingredient, boolean doStart)` returns targets with `Rect2i getArea()` (screen coordinates) and `accept(I)`, plus `onComplete()`. The ingredient is an `ItemStack` or a NeoForge `FluidStack`; Storage's pattern terminal and request board use it (`compat.jei.GhostTargets`, B09 item 30).

## Item-held slots and stack identity (B09 item 32, #322; verified 2026-10-19 in the patched 26.1.2.109 sources and by `carried_view_filters_*`)

- `ItemStackTemplate` (record `item`, `count`, `components`; equal by value) has a `CODEC` in the same `id`/`count`/`components` shape as `ItemStack.CODEC`, `fromNonEmptyStack(stack)` and `create()` (logs and returns `EMPTY` when the stack fails `validateStrict`). A component of templates needs no hand-written `equals`.
- The swap-hands key (`ServerGamePacketListenerImpl`, `SWAP_ITEM_WITH_OFFHAND`) is handled while a menu is open and moves the same `ItemStack` objects between the hands (through NeoForge's `LivingSwapHandItemsEvent`). Taking a whole stack out of a slot (`ContainerHelper.removeItem` -> `ItemStack.split`, used by `Inventory.removeItem`, `removeFromSelected` and menu pickups) returns a copy and leaves the old object at count 0. So a GUI bound to a carried item tells "the very stack it opened for" from a copy by reference (`slot.get() == opened && !opened.isEmpty()`).
- `SimpleContainer.stillValid` returns true and `Container.canPlaceItem` defaults to true, so a `Slot` subclass may ask its container in `mayPickup`/`mayPlace` without changing plain containers (the client's stand-ins).

# Ports, buses and world devices

## Storage ports: container wake-ups, ghost slots, cauldrons (M6a area 4; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `port_*` and `perf_storage_idle_ports` GameTests)

- A container's content change reaches the blocks around it without polling: `BlockEntity.setChanged()` calls `Level.updateNeighbourForOutputSignal(pos, block)`, which NeoForge patches to call `IBlockExtension.onNeighborChange(state, LevelReader, pos, neighbor)` on all six neighbours (vertical ones after the horizontal), not only comparators. A `common` block overrides it without importing NeoForge (the level may be a client level; check for `ServerLevel`). NeoForge's `VanillaContainerWrapper` (chests, barrels) calls the container's `setChanged()` on the root commit of any transaction that changed it, so a block's own transfer from a chest fires it during its own work too.
- Menus: `AbstractContainerMenu.clicked(int slotIndex, int buttonNum, ContainerInput, Player)`; vanilla's `ClickType` is now `net.minecraft.world.inventory.ContainerInput` (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`). Overriding `clicked` for some slot indices and returning without `super` makes ghost slots: nothing moves, and vanilla's slot sync still sends what the backing `Container` holds. `addDataSlot(DataSlot)` with an anonymous `get()` carries an enum index; `DataSlot.standalone()` on the client.
- Saving a container: `ContainerHelper.saveAllItems(ValueOutput, NonNullList<ItemStack>)` and `loadAllItems(ValueInput, NonNullList)`; `ValueOutput.child(name)` returns a child output, `ValueInput.child(name)` an `Optional<ValueInput>`. `SimpleContainer.getItems()` is the live list; `SimpleContainer.clearContent()` calls `setChanged()`, while `NonNullList.clear()` on a list with a default resets every slot to it without callbacks (use that in `loadAdditional`).
- `BucketItem.content` is a public final `Fluid` field (`Fluids.EMPTY` for the empty bucket); `Fluid.isSame(Fluid)` compares without Error Prone's `ReferenceEquality`.
- NeoForge gives cauldrons a fluid capability: `CauldronFluidContent` registers `CauldronWrapper.get(level, pos)` on `Capabilities.Fluid.BLOCK`; extracting 1,000 mB from a full water cauldron through it leaves a plain `Blocks.CAULDRON` (`port_fluid_import`).
- `ItemResource.equals` is `ItemStack.isSameItemSameComponents` on its inner stack, so it matches a filter by item and components without converting to keys.
- `ItemResource.of(item)` and `of(item, patch)` with an empty patch return the item's one shared default resource (`Item.computeDefaultResource`), so `VanillaContainerWrapper.getResource` hands out the same object for every plain stack of an item; `PortTransfers.pullMany` skips a stack the filter refused by identity. Verified in NeoForge's `ItemResource` sources (2026-11-26).
- An import port whose inventory holds nothing its filter accepts opens no root transaction: a read-only walk finds the first index worth a move first. In `perf_storage_idle_ports` the root's `openRoot` and `close` were about half of an idle run's samples (JFR, 2026-11-26). `-Pgametest.jfr=<file>.jfr,jdk.ExecutionSample#period=1ms` samples every millisecond instead of the profile's 10, which a perf test's few milliseconds of measured work need.
- Face UV rotation in block models (`CuboidFace.getU/getV` with `Quadrant.rotateVertexIndex`, `FaceInfo`): a face's vertices take the UV of vertex `(i + shift) % 4`, so with `"rotation": 90` the texture's u runs along the face's v axis. On an up face (u = x, v = z) a rectangle `(a0, 0, a1, 16)` with rotation 90 lays the texture's u over z from `a0` to `a1` and its full v across x. The default UVs vanilla derives from an element's bounds are north `(16 - x, 16 - y)`, south `(x, 16 - y)`, west `(z, 16 - y)`, east `(16 - z, 16 - y)`, up `(x, z)`, down `(x, 16 - z)`; NeoForge's `ExtendedModelTemplateBuilder` leaves `uv` out of the JSON when a face's UVs equal those defaults. Not yet seen in a client.
- A face's `"rotation"` turns its texture clockwise: `UVs.getVertexU/V` give vertex 0 to 3 the corners top left, bottom left, bottom right, top right, and `Quadrant.R90` (`shift` 1) hands each vertex the next corner's UV, so the texture's top edge lands on the face's right. With the default UVs above, a texture drawn pointing up points north on a north-facing cube's up face with `R0`, its down face with `R180`, its east face with `R90` and its west face with `R270`; Storage's `FacingModels` turns the arrow sides of directional devices this way (`ExtendedModelTemplateBuilder` face `.rotation(Quadrant)`). Verified in the patched 26.1.2.109 sources (`CuboidFace`, `Quadrant`) and by `runData`; not yet seen in a client.
- A block item whose block's model is written by hand (no `.model(...)` in Lib's content builder) still gets an item model: vanilla's `ModelProvider` writes `items/<name>.json` pointing at `block/<name>` for every known block item without one (seen for the placer, breaker, P2P links, pattern provider and machine observer in `runData`).
- `BlockModelGenerators.ROTATION_FACING` turns a north-facing model to all six `BlockStateProperties.FACING` values (down `x 90`, up `x 270`, east `y 90`, south `y 180`, west `y 270`), as the observer and dropper use; `MultiVariantGenerator.dispatch(block).with(PropertyDispatch.initial(property).generate(value -> variant)).with(ROTATION_FACING)` adds a second property (verified by `:storage:neoforge:runData`).
- A full-cube block whose model leaves a gap (used for the port blocks' steps in MTX item 4 until they became plain cubes on 2026-10-17): `BlockBehaviour.getOcclusionShape(state)` defaults to `state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`, and `BlockStateBase.initCache` takes `solidRender` and the per-face occlusion shapes from it, so overriding only `getOcclusionShape` keeps the full hit and collision shape while neighbours keep the faces the gap shows. `getLightDampening` is 15 only for `solidRender`, otherwise 1 (0 if `propagatesSkylightDown`, which tests `getShape`). Verified in the patched 26.1.2.109 sources; not yet seen in a client.
- Error Prone fails the build on a collection that is filled but never read (`ModifiedButNotUsed`) and on unnecessary parentheses (`UnnecessaryParentheses`).
- Replacing baked block state models without touching their block state files: `ModelEvent.ModifyBakingResult` (mod bus, client; `net.neoforged.neoforge.client.event.ModelEvent`) fires after baking with `getBakingResult().blockStateModels()`, a mutable `Map<BlockState, BlockStateModel>` (`ParallelMapTransform` fills a `HashMap`), and `getTextureGetter()`, a `Function<Identifier, TextureAtlasSprite>` over the block atlas (missing sprites log a warning and give the missing sprite); `new Material.Baked(sprite, false)` makes the material `FaceBakery.bakeQuad` takes. Storage wraps the conduit and drive models there (`StorageModels`); fires on every resource reload. Verified in the NeoForge 26.1.2.109 and patched sources (`ClientHooks.onModifyBakingResult`, `ModelBakery.bakeModels`) and by compiling; not yet seen in a running client.

## Parts inside a block: breaking, pick-block, hit tests, model data (M6b area P; verified in the NeoForge `26.1.x` patches and sources and by the `part_*` GameTests; rendering not yet seen in a running client)

- Breaking runs through NeoForge's `IBlockExtension.onDestroyedByPlayer(state, level, pos, player, tool, willHarvest, fluid)`: `ServerPlayerGameMode.destroyBlock` fires the break event, calls `playerWillDestroy`, then `removeBlock` -> `onDestroyedByPlayer`; only when that returns true does it call `Block.destroy` and (with `canHarvest`) `playerDestroy`, i.e. the drops. A player who `preventsBlockDrops()` (creative) gets `willHarvest = false`. The client's `MultiPlayerGameMode.destroyBlock` calls it too, with `willHarvest = false`, so returning false on both sides keeps the block for a part-only break; the server drops the part itself (`Block.popResource`).
- Pick-block is server side in 26.1: the `ServerGamePacketListenerImpl` patch calls `state.getCloneItemStack(pos, level, includeData, player)`, which reaches `IBlockExtension.getCloneItemStack(LevelReader, BlockPos, BlockState, boolean, Player)`; the vanilla four-argument overload is deprecated by NeoForge. The player overload is the one that can trace which part is aimed at.
- Tracing a player's view against a block's shape: `shape.clip(player.getEyePosition(), eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + 1)), pos)` returns a nullable `BlockHitResult` whose location lies on the hit surface; move it `-1e-4` along the hit direction before testing which box it is in. `getViewVector(1.0F)` reads `getXRot()`/`getYRot()` directly at partial tick 1.
- A NeoForge `ModelData` with several properties: `ModelData.builder().with(property, value).with(other, value2).build()`; `ModelData.of(property, value)` for one.
- GameTests: a `new ServerPlayer(server, level, profile, ClientInformation.createDefault())` has no connection, so `sendOverlayMessage`/`sendSystemMessage` and `snapTo` throw a `NullPointerException` that crashes the test server; aim it with `setPos` and `setXRot`, and use `helper.makeMockPlayer(GameType.SURVIVAL)` for paths that write to the action bar. Such a server player starts with creative abilities on the GameTest server (`ItemStack.consume` did not shrink); set `getAbilities().instabuild = false` to test item use.
- Datagen of an item model outside the content chain: build the model with `ModelTemplate.create(Identifier, TextureMapping, itemModels.modelOutput)` and register it with `itemModels.itemModelOutput.accept(item, ItemModelUtils.plainModel(id))`; a content item declared without `.model(...)` gets no generated item model, so the two do not collide (verified by `:storage:neoforge:runData`).
- Error Prone's `InvalidParam` also fires in a method's Javadoc when a `{@code word}` is close to a parameter's name (`front` vs `fronts`).
- Item data for a part with slots (M6b area P, PR 2; patched 26.1.2.109 sources and `part_port_keeps_filter_in_item`): vanilla's `DataComponents.CONTAINER` holds an `ItemContainerContents` (`fromItems(List<ItemStack>)`, `copyInto(NonNullList<ItemStack>)`, `EMPTY`, `CODEC`/`STREAM_CODEC`; it implements `TooltipProvider`, so the item lists its contents), which fits any item, not only block items. An enum that is `StringRepresentable` serialises with `StringRepresentable.fromEnum(Enum::values)` for a component's `persistent` codec.
- Block entity settings in the block's item (patched 26.1.2.109 sources, `part_port_settings_round_trip`): `BlockItem.place` calls `blockEntity.applyComponentsFromItemStack(stack)`, which hands the item's components to `applyImplicitComponents(DataComponentGetter)`; every type the override reads (`get`/`getOrDefault`) counts as implicit and is dropped from the leftovers the block entity keeps in `components()`, so read every component you handle there, or a stale one comes back in `collectComponents()`. `collectComponents()` is the leftovers plus `collectImplicitComponents(DataComponentMap.Builder)`. A plain `self` loot table copies nothing; a block's `getDrops` can `drop.applyComponents(blockEntity.collectComponents())` on its own item.
- A recipe that copies the ingredient's components: subclass `ShapelessRecipe` (its constructor `(Recipe.CommonInfo, CraftingRecipe.CraftingBookInfo, ItemStackTemplate, List<Ingredient>)` is public; `commonInfo`/`bookInfo` are protected), override `assemble(CraftingInput)` with `result.applyComponents(stack.getComponentsPatch())`, and register an own `RecipeSerializer` (a `MapCodec` from `Recipe.CommonInfo.MAP_CODEC`, `CraftingRecipe.CraftingBookInfo.MAP_CODEC`, `ItemStackTemplate.CODEC`, `Ingredient.CODEC`). `getSerializer()` is typed `RecipeSerializer<ShapelessRecipe>` there, so the override needs an unchecked cast. Datagen writes it with `output.accept(key, recipe, null)` (no advancement) and `RecipeBuilder.createCraftingCommonInfo(true)`/`createCraftingBookInfo(category, null)`; `CrafterBlock.getPotentialResults` finds it like a shapeless recipe.
- An addon's guide (Lib guide, M7G): `Guides.register(<modid>:guide)` in the mod's init, pages under `assets/<modid>/guides/<modid>/guide/*.md`, so page ids are `<modid>:<page>.md` and `parent:` without a namespace means the same guide. The book is the mod's own `GuideBookItem` (Storage: `storage_manual`, `guide.GuideContent`), registered through the content builder like any item. The `guide_pages` GameTest reads the pages on the server through each mod file's pack (`ResourcePackLoader`, see `SKILL.md`), parses them with `GuideMarkdown` and checks items, `<RecipeFor>` recipes (`GuideRecipeIndex` over `SYNCED_TYPES`) and the navigation with `GuideBook`.

## Port tiers and speed cards (M6c area A; verified in the patched 26.1.2.109 sources, by compiling and by the `port_*` and `perf_storage_idle_*` GameTests)

- `AbstractContainerMenu.moveItemStackTo` fills existing matching stacks first, then puts at most `slot.getMaxStackSize(stack)` into the first empty slot that `mayPlace` it and stops (`break`), so one call fills one empty slot. Vanilla's `clicked(..., ContainerInput.QUICK_MOVE, ...)` calls `quickMoveStack` again while it returns a non-empty stack of the same item as the one still in the clicked slot; a `quickMoveStack` that moves one card per call fills several one-item slots only through `clicked`. GameTests drive shift-clicks with `menu.clicked(index, 0, ContainerInput.QUICK_MOVE, player)`, not `quickMoveStack` directly.
- A block with a constructor argument besides its properties gets its codec from `RecordCodecBuilder.mapCodec(i -> i.group(MyEnum.CODEC.fieldOf("tier").forGetter(MyBlock::tier), propertiesCodec()).apply(i, MyBlock::new))`; `BlockBehaviour.propertiesCodec()` is a public static (`simpleCodec` is built on it). One block entity type may list several such blocks (`new BlockEntityType<>(factory, Set.of(a, b, c))`) and read its block's argument from the state in its constructor (`state.getBlock() instanceof MyBlock b`).
- Error Prone's `EnumOrdinal` fails the build for any `Enum.ordinal()` call, inside the enum too; use a `switch (this)` for a number and a lookup method for the reverse.
- `-Pgametest` takes one pattern: `-Pgametest='maxtech_storage:a*,maxtech_storage:b*'` ran only the tests matching the first; run separate patterns one after the other.

## Redstone from devices and parts, 16-bit data slots (M6c area C condition switch; verified in the patched 26.1.2.109 sources and by the `condition_*` GameTests)

- `SignalGetter.getSignal(pos, direction)` calls `state.getSignal(level, pos, direction)` whatever `isSignalSource()` says, so a block can give weak power without being a signal source (redstone dust then only does not draw a connection to it). `direction` points from the asking block to the block giving the signal: `hasNeighborSignal(p)` asks `getSignal(p.below(), DOWN)` and so on. A part on face `F` of a cable therefore answers `getSignal(..., direction)` when `direction.getOpposite() == F` (`StorageCableBlock.getSignal`, `CablePart.signal()`).
- A block whose output changes tells its neighbours with `level.updateNeighborsAt(pos, block)`; flipping a block state with `Block.UPDATE_ALL` does that by itself. A redstone lamp (`RedstoneLampBlock.LIT`) next to it lights on that update and goes out after its 4-tick scheduled delay, so GameTests wait for it with `thenWaitUntil`.
- Reading redstone into a block without loading chunks (M7L2 bundled channels, by compiling and the `channel_*` GameTests): `Level.hasChunksAt(BlockPos from, BlockPos to)` checks without loading. `getBestNeighborSignal(pos)` asks each neighbour, and a conductor neighbour looks at its own neighbours for strong power, so guard two blocks out (`ConditionSwitchBlockEntity#redstoneIn`). A lever lying on a block strongly powers that block, so the block's other neighbours (a logic controller next to a switch) see 15 too; a block of redstone on top only weakly powers it. `LeverBlock.pull(state, level, pos, @Nullable Player)` toggles a lever in a GameTest. `DyeColor.byId(int)` (ids 0 white to 15 black), `getName()` (`light_blue`) and `getTextColor()` exist.
- Data slots (corrected 2026-10-08 against the patched 26.1.2.109 sources, `craft_limits_data_slots_sync`): vanilla's `ClientboundContainerSetDataPacket` writes id and value with `writeShort`, but NeoForge's patched `ServerPlayer` synchronizer sends `AdvancedContainerSetDataPayload(byte containerId, short dataId, int value)` instead whenever the connection has its channel, which every NeoForge client has; the value goes as a `VAR_INT`, so a data slot carries a full `int` (initial data and changes alike). Splitting a `long` into four 16-bit pieces (mask with `0xFFFF` when joining them) is still how to send a `long`; an `int` needs no splitting.
- Emit to craft (B09 item 14, by the `condition_craft_*` GameTests): a device becomes craftable for the planner by being a `PatternHost` whose provider is also a `GeneratorSource` with an `output()`; the network re-reads it on `deviceLink().patternsChanged()`. Job ends reach a source only through `GeneratorDispatch.register`. GameTests of one run share the level, so a `JobQueue.listen` listener sees every test's jobs; tests that count evaluations use an item no other test crafts (andesite). `helper.assertValueEqual(a, b, name)` reports a failure as "Expected <name> to be <a>: was <b>", and this repo passes the actual value first, so read such messages the other way round.
- `BlockItem.place` sets the block (the block entity's `onLoad` runs there) before `applyComponentsFromItemStack`, so a block entity that starts listening in `onLoad` must re-subscribe when `applyImplicitComponents` hands it the item's settings.
- `SimpleContainer.setItem` cuts the stack to `getMaxStackSize(stack)`, which is `min(getMaxStackSize(), stack.getMaxStackSize())` by default (`Container`), so a ghost slot whose count is a target (several filled buckets standing for mB) must override `getMaxStackSize(ItemStack)` too; the client's menu container needs the same override or slot sync cuts the count (patched 26.1.2.109 sources; M6c interface's `GhostContainer`, `iface_menu_ghost_slots`).
- `ResourceHandler.insert(resource, amount, tx)` and `extract(resource, amount, tx)` default to calling the indexed method on every index in turn, and `ResourceHandlerUtil.insertStacking` tries non-empty indices first, then empty ones. A handler that should answer an unindexed call in one step (an interface storing into a network as one operation) offers a single index that accepts inserts and refuses them everywhere else with `isValid` false and `insert` 0 (NeoForge 26.1.2.109 sources; `iface_insert_is_one_operation`).
- `FluidResource.OPTIONAL_CODEC` accepts the empty resource (`CODEC` does not); a record of a `FluidResource` and an `int` amount with a `RecordCodecBuilder` codec works as a persistent data component, and without `networkSynchronized` it reaches clients as NBT (M6c interface's `FluidStock`).

## Network chest: vanilla chest menus over a view, stack components (M6c area E; verified in the patched 26.1.2.109 sources, by compiling and by the `world_chest_*` GameTests; the screens not yet seen in a running client)

- `ChestMenu(MenuType<?>, int containerId, Inventory, Container, int rows)` is public (the row-only constructor is private). A server-only subclass opened with `MenuType.GENERIC_9x3`/`GENERIC_9x6` makes the client build a plain `ChestMenu` and vanilla's `ContainerScreen`, so a block can look like a chest to players without a screen of its own. Overriding `clicked` for the chest's slot indices (handling `ContainerInput.PICKUP` and `QUICK_MOVE` itself and returning without `super`), `quickMoveStack`, `canDragTo(Slot)` and `canTakeItemForPickAll(ItemStack, Slot)` keeps every vanilla path (drag, double-click collect) off a read-only `Container`; vanilla's slot and cursor sync fix the client's prediction.
- `ItemStack` has no `equals`/`hashCode`; vanilla's components hold `ItemStackTemplate`s instead (`ItemContainerContents` keeps `List<Optional<ItemStackTemplate>>`). A record data component holding `ItemStack`s compares them by identity, so two items with the same data would not stack: override `equals`/`hashCode` with `ItemStack.listMatches`, `ItemStack.matches`, `ItemStack.hashStackList` and `ItemStack.hashItemAndComponents`. `ItemStack.OPTIONAL_CODEC` and `OPTIONAL_STREAM_CODEC` (empty stacks allowed) compose with `sizeLimitedListOf(n)` and `ByteBufCodecs.list(n)`.
- An item's tags: `item.builtInRegistryHolder().tags()` (a `Stream<TagKey<Item>>`; the method is deprecated, compiles with a warning to suppress). Tags are synced, so both sides list the same tags and an index into the id-sorted list can travel as a data slot.
- A GameTest class's static field calling `ItemResource.of(Items.X)` (or anything that reads an item's components) fails mod loading with "Components not bound yet": GameTest classes load during mod construction. Build such values inside the test.
- `ILevelExtension.invalidateCapabilities(BlockPos)` is a default method on `Level`, callable from `common` without a NeoForge import; a part that starts answering a capability on its cable calls it so cached neighbours look again.

## Collector, placer and breaker: fake players, break events, entity joins (M6c area E; verified in the NeoForge `26.1.x` and patched 26.1.2.109 sources and by the `world_collector_*`, `world_placer_*`, `world_breaker_*`, `world_devices_*` and `perf_storage_idle_world_devices` GameTests)

- 26.1 has no `BlockEvent.BreakEvent`: the cancellable break event is `net.neoforged.neoforge.event.level.block.BreakBlockEvent` (`getPlayer()`, `getPos()`, `getState()`). `CommonHooks.fireBlockBreak(level, GameType, player, pos, state)` posts it, pre-cancelled when the player's main-hand item cannot destroy the block, `blockActionRestricted` holds or the block is a `GameMasterBlock`; a device that breaks blocks itself asks it with `GameType.SURVIVAL` and an empty hand and checks `isCanceled()`. `BlockEvent.EntityPlaceEvent` is still in `BlockEvent`.
- `FakePlayerFactory.get(ServerLevel, GameProfile)` caches one `FakePlayer` per level and profile (cleared on level unload); `new GameProfile(UUID, String)`. A fake player is in survival with `instabuild` off, so `BlockItem.place` consumes from the stack it uses. A GameTest can cancel only fake players' events with `event.getEntity() instanceof FakePlayer` and a set of positions, so parallel tests are unaffected.
- `Level.destroyBlock(pos, false, breaker)` removes the block with the break particles and the `BLOCK_DESTROY` game event but without drops and without `spawnAfterBreak`; call `state.spawnAfterBreak(level, pos, tool, true)` for experience and silverfish. `Block.getDrops(state, level, pos, blockEntity, breaker, ItemInstance tool)` runs the loot table without checking whether the tool is right for the block (stone gives cobblestone with no tool). `ServerLevel.mayInteract(entity, pos)` is spawn protection and the world border; `ServerPlayerGameMode` checks it for players, so a device acting through `useOn` or its own break checks it itself.
- Claim checks for devices that use a faced block (`network.FacingAccess`, `claim_*` GameTests; NeoForge 26.1.2.109 sources): `new PlayerInteractEvent.RightClickBlock(player, hand, pos, BlockHitResult)` is what `CommonHooks.onRightClickBlock(player, hand, pos, hit)` posts and returns; its `TriState` is `net.minecraft.util.TriState` (not NeoForge's). `setCanceled(true)` also sets `useBlock` and `useItem` to `FALSE`, and a mod may set `useBlock` to `FALSE` without cancelling, so "refused" is `isCanceled() || getUseBlock() == TriState.FALSE`. Posting it runs every listener, not only claim mods: right-click-harvest style mods act on it with an empty hand, so Storage posts it only for targets with a block entity or an item, fluid or FE handler (what claim mods protect), never for crops, levers or stone (`claim_sensor_plain_block_not_clicked`). A GameTest simulates a claim by cancelling it for `FakePlayer`s at chosen positions. Not yet verified in game: that FTB Chunks and Open Parties and Claims treat a `FakePlayer` with the owner's UUID as that player or team member (both have fake-player options); check in a dev client with a real claim.
- `ItemStack.enchant(holder, level)` on an enchanted book writes `DataComponents.STORED_ENCHANTMENTS`; copying those into `DataComponents.ENCHANTMENTS` on any item (a stick) makes loot tables see silk touch (`world_breaker_modes`: grass gives grass).
- `EntityJoinLevelEvent` fires for every entity entering a server level (drops, spawns, chunk loads with `loadedFromDisk()`), posted at the start of `PersistentEntitySectionManager.addEntity`, before the entity is in its section; a handler that only wakes a scheduled task, which searches with `level.getEntities(EntityType.ITEM, box, predicate)` on a later tick, finds it then.
- Cancelling `EntityJoinLevelEvent` makes `ServerLevel.addFreshEntity` return `false` (the patched
  `PersistentEntitySectionManager.addEntity` returns before adding), so a device can spawn an item entity inside an
  open transaction and commit only on `true` (placer drop mode, `world_placer_drop`). `Level.getEntities(type, box,
  predicate, output, maxResults)` adds into a caller's list and stops once `output.size() >= maxResults` (the list's
  total size, so clear it first): a capped count without allocating a list. `new ItemEntity(level, x, y, z, stack,
  dx, dy, dz)` sets the motion without the plain constructor's random push; a dropper (`DefaultDispenseItemBehavior.
  spawnItem`) spawns 0.125 (vertical) or 0.15625 below the face centre with a 0.2 to 0.3 push plus spread.
- Six-way facing block models in datagen: `ModelTemplates.CUBE_ORIENTABLE.create(ModelLocationUtils.getModelLocation(block), new TextureMapping().put(FRONT, ...).put(SIDE, ...).put(TOP, ...), modelOutput)` and `MultiVariantGenerator.dispatch(block, BlockModelGenerators.plainVariant(model)).with(BlockModelGenerators.ROTATION_FACING)`. A content `BlockModel.cubeAll` on a block with a `facing` property writes one variant for every state.
- A block class serving several kinds: `RecordCodecBuilder.mapCodec(i -> i.group(KIND_CODEC.fieldOf("kind").forGetter(Block::kind), propertiesCodec()).apply(i, Block::new))`, as the energy cells do; one `BlockEntityType` can list all the blocks and the block entity picks its behaviour from `state.getBlock()` in its constructor.

## Device tools (M6e area A; verified by compiling and by the `device_tools_*` GameTests)

- A second `Platform.registry().registrar(modId, Registries.DATA_COMPONENT_TYPE)` in another class (an area's own `<Area>Content`) registers fine beside `ModDataComponents`' registrar: each call is its own `DeferredRegister`, so an area can add a component without touching the shared class (`maxtech_storage:blueprint`).
- Settings as components: `DataComponentPatch.builder().set(Iterable<TypedDataComponent<?>>)` takes a whole `DataComponentMap`; `patch.split().added()` gives the set components back as a `DataComponentMap` (a `DataComponentGetter` with `has`); `DataComponentMap.filter(Predicate<DataComponentType<?>>)` drops types. `DataComponentPatch.CODEC`/`STREAM_CODEC` with `Identifier.CODEC`/`STREAM_CODEC` make a record component that holds another item's components. Error Prone's `ReferenceEquality` fails `type == SOME_TYPE` on `DataComponentType`; compare with `equals`.
- `BlockPos.MutableBlockPos` has `setWithOffset(Vec3i, Direction)` and `setWithOffset(Vec3i, int, int, int)` but no `(Vec3i, Direction, int)`; use `set(pos).move(direction, n)`.
- A `new ServerPlayer(...)` without a connection throws on `sendOverlayMessage` and on the slot sync of `Inventory.placeItemBackInInventory` (`connection` is null); `helper.makeMockPlayer(GameType.SURVIVAL)` (a plain `Player`, random UUID each call) does both, and `NetworkAccess`/owner security tell two of them apart by UUID.
- `BlockEntity.applyComponentsFromItemStack` does not read `DataComponents.BLOCK_ENTITY_DATA` (the drive keeps its cells there); placing the item with `stack.useOn(new UseOnContext(player, hand, hit))` on the block below does, through `BlockItem.updateCustomBlockEntityTag`.

## Device front lines: coplanar overlays and quiet block state changes (MTX item 7; verified 2026-10-17 in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `channels_link_*` GameTests; not yet seen in a client)

- Terrain pipelines use `DepthStencilState.DEFAULT` (`CompareOp.LESS_THAN_OR_EQUAL`, depth write on; `GENERIC_BLOCKS_SNIPPET`), and `ChunkSectionLayerGroup.OPAQUE` draws `SOLID` before `CUTOUT`. So a cutout quad with exactly the same four corners as a solid quad draws over it without z-fighting, as vanilla's grass side overlay does; within one layer the later quad wins. `LinkLineModel` copies a front quad this way.
- `BakedQuad` is a record `(position0..3, packedUV0..3, direction, materialInfo, bakedNormals, bakedColors)`; a copy with the same positions, normals and colours and a new `MaterialInfo.of(Material.Baked, Transparency, tintIndex, shade, lightEmission, ambientOcclusion)` keeps the light and AO. Packed UVs are atlas coordinates: `UVPair.pack(u, v)`, `UVPair.unpackU/unpackV(long)`; move them to another sprite through `sprite.getU0()/getU1()/getV0()/getV1()`.
- A copied quad's UVs may point at any window of a bigger sprite: `StorageDriveModel` copies the drive's 16 x 16 front quad onto a 32 x 32 cell sprite that is transparent but for one cell, through a 16 x 16 window chosen per slot (mirrored for the right column), so one sprite per band covers every bay. The window must stay inside the sprite, or the quad samples its atlas neighbours (`DriveBaysTest`). Compiled and unit-tested; not yet seen in a client.
- `MipmapGenerator` maps `MipmapStrategy.AUTO` to `CUTOUT` for a sprite with transparent pixels (alpha scaled to keep coverage, cutoff 0.5), so a 1 px line on a transparent overlay stays visible at a distance; `.mcmeta` `texture.mipmap_strategy` (`mean`, `cutout`, `strict_cutout`, `dark_cutout`) and `alpha_cutoff_bias` override it.
- A blockstate variant key that names no value for a property matches every value of it (`VariantSelector.predicate` checks only the properties named), so adding a property that only a model wrapper reads leaves the generated blockstate files unchanged (`runData` wrote no change).
- `LevelChunk.setBlockState` calls `state.onPlace` even when only a property changes, unless the flags contain `Block.UPDATE_SKIP_ON_PLACE` (512); `Level.markAndNotifyBlock` updates neighbour shapes unless `UPDATE_KNOWN_SHAPE` (16). `Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS` (2 | 816) changes a property for clients only: no neighbour updates (so no `NeighborNotifyEvent`, which Lib's `onBlockChanged` hook listens to), no shape updates, no `onPlace`, no block entity side effects; the block entity stays and gets the new state.
- A cable part's look reaches clients without touching the network node through `level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)` (the cable's update packet), not `ConduitBlockEntity.syncedStateChanged()`, which also invalidates the node and so rebuilds the network.

## Storage bus: mounted handlers, change detection, hashing (B09 item 8; verified 2026-10-18 in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `storage_bus_*` and `perf_storage_idle_buses` GameTests)

- A provider can pass stores and takes straight to a foreign `ResourceHandler` inside the caller's transaction: the handler's own journal rolls its contents back, so the provider (a `SnapshotJournal<Integer>` over a move log) only drops log entries on `revertToSnapshot` and reports the moved amounts in `onRootCommit` (`PortStorage`). Calling the faced handler from a provider needs a re-entry flag: a move can come back through another network's interface into the same provider.
- `ResourceHandler.extract(T resource, int amount, tx)` (no index) is a default loop over all indices that a handler may override to be faster; `insert(T, int, tx)` likewise fills from index 0, so filling matching indices first needs the two-pass loop (`PortTransfers.insertStacking`).
- `Resource.hashCode` is expensive for items: `ItemResource.hashCode()` is `ItemStack.hashItemAndComponents`, i.e. `31 * (31 + item.hashCode()) + components.hashCode()`, and `PatchedDataComponentMap.hashCode()` is `prototype.hashCode() + hashPatch(patch) * 31`, which hashes the item's whole default component map each time. Hashing `System.identityHashCode(item)` plus `getComponentsPatch().hashCode()` instead halved the idle cost of 50 buses on double chests (0.0129 to 0.0070 ms per tick). `PatchedDataComponentMap.asPatch()` returns `DataComponentPatch.EMPTY` for an unpatched stack and allocates a new patch (and sets copy-on-write) for a patched one.
- `ItemResource.of(stack)` returns the item's cached default resource when the stack has no component patch (`Item.computeDefaultResource`), else a new resource over `stack.copyWithCount(1)`; `VanillaContainerWrapper.getResource(index)` goes through the slot wrapper, so reading an unpatched slot allocates no resource.
- `Transaction.getLifecycle()` is `NONE` when no transaction is open or closing on the thread; a look that must not read uncommitted contents checks it (and its own log) and waits a tick otherwise.
- A chest block entity removed from its chunk (`onChunkUnloaded`, then `LevelChunk.removeBlockEntity`) while its block state stays: a take through a bus in the same tick moved nothing, and on the next look the bus's cached item lookup still found a handler, an empty one, so the bus stayed `MOUNTED` with no contents (`storage_bus_target_unloaded`). A real chunk unload makes Lib's cached lookup return null instead (`CachedBlockLookup.find` Javadoc; not run in a GameTest).
- `ChestBlock.TYPE` `LEFT` pairs with the chest on `facing.getClockWise()` (east of a north-facing left half); NeoForge's item capability on the left half of a double chest is the 54-slot double chest (`perf_storage_idle_buses` sees all 54 kinds through buses facing left halves).
- A hopper placed with `HopperBlock.ENABLED=false` through `GameTestHelper.setBlock` re-checks its redstone on placement and runs at once; lock it with a redstone block beside it and remove that to start it.
- A storage bus counts on Lib's `CachedBlockLookup.find()` returning null for a position whose chunk is not loaded (its Javadoc; no GameTest unloads a real chunk), so an unloaded target shows nothing and moves nothing.
- Loop checks for a storage bus run at each look (`NetworkState.reaches`): a loop formed after a look is refused at the next one, up to `ports.storageBus.maxScanTicks` later; meanwhile the bus's re-entry flag and the transactions keep it from recursing or duping. Loops through other mods' storage (AE2/RS bridges, P2P into foreign storage) are invisible to `reaches` and stopped only by the re-entry flag.
- Nested transactions through a bus (`storage_bus_abort_nested`): a nested commit inside an aborted root reaches neither the handler nor the index; `SnapshotJournal` calls `revertToSnapshot` with the root's snapshot (0) on the root abort, so the log must be truncated there, and nothing is reported because `onRootCommit` never runs.
- `Object.wait(long, int)` is final: a package-private method `int wait(long, int)` does not compile ("cannot override"); `BusCadence.delay` it is.

## Port filter entries and the regulator (B09 items 18 and 22; verified 2026-10-19 in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources, by `PortMatchLogicTest` and the `port_match_*` and `perf_storage_filtered_ports` GameTests; the screen not yet seen in a client)

- `ItemResource.getItem()` / `FluidResource.getFluid()` give the base for fuzzy matching without allocating; both also
  have `typeHolder()`. A registry id's namespace without a map lookup: `item.builtInRegistryHolder().key().identifier()
  .getNamespace()` (`Holder.Reference.key()`, `ResourceKey.identifier()`); the holder's `is(TagKey)` is a set lookup.
- `Codec.optionalFieldOf(name, default)` leaves the field out when encoding the default value and reads a missing field
  as the default, so per-slot settings written only when not default load old saves as defaults.
- `GameTestHelper.onEachTick(Runnable)` runs an assertion on every tick until the timeout (good for "never more than N"
  checks); `Entity.snapTo(x, y, z)` moves a mock server player next to a block so `stillValid` range checks pass.
- An export port reading a full 27-slot barrel per key through `insertStacking` in a simulated transaction cost about
  0.25 ms per tick for 250 ports trying 64 keys each; a read-only look first (`getResource`, `getAmountAsLong`,
  `getCapacityAsLong`) brought 500 filtered ports to about 0.02 ms (`PortTransfers.anyRoom`, `mayFit`).

# Autocrafting

## Patterns and the pattern terminal (M7a; verified in the patched 26.1.2.109 sources, the JEI 29.43.0.106 API and library sources and by the `pattern_*` GameTests)

- JEI's `IUniversalRecipeTransferHandler<C>` (`registration.addUniversalRecipeTransferHandler`) gets every recipe
  category's "+" for its menu as `transferRecipe(C, Object recipe, IRecipeSlotsView, Player, maxTransfer, doTransfer)`;
  `RecipeTransferManager` asks a handler registered for the category's own recipe type first, so Lib's crafting handler
  keeps crafting recipes on a `CraftingGridMenu` and the universal one gets the rest. Vanilla categories pass a
  `RecipeHolder<?>` (recipe type from `BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType())`); brewing passes
  an `IJeiBrewingRecipe`.
- `IRecipeSlotView.getTagKey()` gives the tag a slot was built from; `getDisplayedIngredient().get().getIngredient()` is
  an `ItemStack` or, for fluids, a NeoForge `FluidStack`, so Storage reads fluids without compiling against JEI's
  NeoForge API jar.
- A tag cannot be recovered from a crafting cell's candidate stacks alone: vanilla's `minecraft:wooden_tool_materials`
  holds exactly the planks. `PatternEncoder.tagOf` picks the shortest-named tag whose contents equal the candidates.
- `BuiltInRegistries.ITEM.get(TagKey<Item>)` returns `Optional<HolderSet.Named<Item>>`; `getTagOrEmpty(tag)` an
  iterable of holders. A `HolderSet.Named` iterates its holders in the tag's order: `ItemTags.ANVIL`
  (`minecraft:anvil`) gives anvil, chipped anvil, damaged anvil (GameTest `logic_each_tag_crafts_under_limit`).
- Furnace-type block entities offer slot 0 (input) on `UP`, slot 1 (fuel) on the sides and the result on `DOWN`
  (`AbstractFurnaceBlockEntity.getSlotsForFace`); the brewing stand takes the ingredient on `UP`, bottles and blaze
  powder on the sides, and gives everything on `DOWN`. `PatternFaces` follows these.
- `ByteBufCodecs.fromCodecWithRegistries(codec)` turns a registry-aware codec into a `RegistryFriendlyByteBuf` stream
  codec; enough for a data component synced once per stack.
- `MouseButtonEvent` (an `InputWithModifiers`) has `hasShiftDown()`, `hasControlDown()` and `hasAltDown()`; button 2 is
  the middle button.
- A menu's server-side `clickMenuButton(player, id)` sees the server's copy of `getCarried()`, so a ghost-grid edit can
  ride on a plain button id without its own payload.

## Crafting planner (M7a PR 2; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `planner_*` GameTests)

- `Item.getCraftingRemainder()` is deprecated and returns a nullable `ItemStackTemplate` (a record of `Holder<Item>`,
  count and `DataComponentPatch`); NeoForge's `IItemExtension.getCraftingRemainder(ItemInstance)` is the stack-aware
  form (an `ItemStack` is an `ItemInstance`). The water bucket's remainder is an empty bucket.
- The gametest `TestStorage.TestDevice` does not take every kind: inserting one of each of a thousand registry items
  left about 150 kinds stored, so tests that need wide stock check what was stored rather than assume it.

## Crafting tablet and machine export (M7c area E; verified in the patched 26.1.2.109 sources and by the `tablet_*` and `machine_export_*` GameTests)

- `ClickType` is gone: menu clicks use `net.minecraft.world.inventory.ContainerInput` (`PICKUP`, `QUICK_MOVE`, ...) in
  `AbstractContainerMenu.clicked(slot, button, ContainerInput, player)`.
- A custom crafting grid does not need `RecipeBookMenu`: an `AbstractContainerMenu` with
  `new TransientCraftingContainer(menu, 3, 3)`, a `ResultContainer` and a `ResultSlot(player, craftSlots, results, id,
  x, y)` (public constructor; override `onTake` for extras) behaves as vanilla's table. The result comes from
  `level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, container.asCraftInput(), level)` in
  `slotsChanged`, then `resultSlots.setItem(0, result)`, `setRemoteSlot(0, result)` and a
  `ClientboundContainerSetSlotPacket` (skip the packet when `connection` is null). `CraftingMenu`'s own helper is
  `protected static`, so copy the dozen lines.
- Taking a non-special crafting result calls `ServerPlayer.awardRecipes`, which writes to `player.connection`: a
  connectionless GameTest `ServerPlayer` crashes the server thread there ("Container click"). Use NeoForge's
  `new FakePlayer(level, profile)` for any test that takes a crafting result.
- `Ingredient.display()` is `SlotDisplay.TagSlotDisplay(TagKey<Item>)` for an ingredient written as a tag (the
  `HolderSet` is a named set) and a `Composite` for a list of items; `ingredient.items()` is a deprecated
  `Stream<Holder<Item>>`. That is how JEI's `getTagKey()` and machine export find the tag of a recipe input.
- A security module on an unowned network gives everyone `FULL` (`SecuritySettings.levelFor`), so a test of access
  levels must `setOwner` on the controller first.
- `ProcessingMachineBlockEntity` finds its recipe only when its task runs; asking `exportRecipe()` earlier matches the
  slots on demand and remembers the result (not saved).
- Two GameTests that swap one static hook (`TabletRequests.use`) must be in different test environments: a batch runs
  its tests side by side, so the second swap would answer the first test's call.

## Stock keeper, crafting card, Crafting branch (M7c area D; verified by `runData` and the `crafting_stock_*`, `crafting_card_*` and `crafting_research_branch` GameTests)

- Storage's research tree is data: GameTests read the JSON `runData` wrote from `DraftTree`, so a node changed in Java
  changes nothing in a GameTest until datagen runs again (found while breaking `crafting_research_branch` on purpose).
  A node without options is complete for every team (`ResearchTree.Entry.startNode()`), which is how a branch root
  grants a gate from the start.
- A port's export step (`PortWorker.push`) runs only while the port has rate credit and a faced inventory handler; a
  network that holds none of the filtered key makes the task back off (Lib's idle backoff), so anything hooked onto
  "the network ran out" there runs on that backoff, not every tick.

## Crafting requests, sub-screens and JEI's "+" (M7c area C; verified in the patched 26.1.2.109 sources, the JEI 29.43.0.106 common, gui and library sources and by the `craft_request_*` GameTests; the screens not yet seen in a running client)

- A screen over an open container: `AbstractContainerScreen.removed()` only calls the client menu's `removed(player)`;
  the close packet goes out from `onClose()` (`player.closeContainer()`). So `Minecraft.setScreen(other)` from a
  container screen keeps the server menu open and `player.containerMenu` unchanged, and payloads naming the container
  id keep working. A plain `Screen` closes on Escape through `onClose()` -> `setScreen(null)`, so a sub-screen over the
  menu overrides `onClose` to go back (Storage's `TerminalSubScreen`).
- JEI's recipe screen, after a successful transfer, calls `RecipesGui.onClose()`, which sets its parent screen only while
  `minecraft.screen == this` and otherwise falls through to `Screen.onClose()` (`setScreen(null)`). A transfer handler
  that opens its own screen therefore defers it: `Minecraft.schedule(Runnable)` (from `BlockableEventLoop`) always
  queues for the next pass, while `execute` runs at once on the game thread.
- JEI transfer errors: `Type.COSMETIC` keeps the "+" usable and shows `getTooltip(ITooltipBuilder)` and
  `showError(...)`; JEI's own missing-slot error draws `slot.drawHighlight(graphics, argb)` after
  `pose().translate(recipeX, recipeY)`. `IRecipeTransferHandlerHelper.createInternalError()` hides the button,
  `createUserErrorForMissingSlots(message, slots)` disables it with marked slots. `IRecipeSlotView.getAllIngredientsList()`
  lists every candidate (`ItemStack` or NeoForge `FluidStack`). A crafting-type handler and a universal handler can be
  registered for the same menu class; JEI asks the type-specific one first.
- JEI keeps its overlays clear of areas a `IGuiContainerHandler.getGuiExtraAreas(screen)` returns
  (`IGuiHandlerRegistration.addGuiContainerHandler(ScreenClass, handler)`, `IModPlugin.registerGuiHandlers`).
- `ComponentSerialization.TRUSTED_STREAM_CODEC` (registry-aware) carries server-built components to the client;
  `ServerLevel.recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, id))` returns an `Optional<RecipeHolder<?>>`
  and `AbstractCookingRecipe.cookingTime()` is public; vanilla's raw iron smelting recipe is
  `minecraft:iron_ingot_from_smelting_raw_iron` at 200 ticks (`craft_request_plan_round_trip`).
- GameTests: a failing `thenExecute` calls `GameTestInfo.fail` but the sequence keeps running the events due in the same
  tick, so the reported message can be a later assertion that failed as a consequence; read the first assertion of the
  block that stopped.
- A Lib synced list fits any id-to-count stream, not only storage contents: Storage's terminal sends its craftable keys
  (ids of the session's own, count 1, removal at 0) and a watched job's crafts left per step (id = step index, removal
  when the step is done) as two more `SyncedListType`s on the same menu, which returns the right `ClientSyncedList`
  per payload type from `SyncedListHolder.syncedList`.

## Crafting CPU and job queue (M7c area A; verified in the patched 26.1.2.109 sources and by the `cpu_*` GameTests)

- Action bar from the client: `Minecraft.getInstance().gui` is a public final `Gui`; `Gui.setOverlayMessage(Component, boolean animate)` shows a line there (patched 26.1.2.109 sources; logic remote pings). `ServerPlayer` ticks call `containerMenu.broadcastChanges()`, so a slotless menu's `broadcastChanges` override runs every tick while it is open.
- Toasts: `net.minecraft.client.gui.components.toasts.SystemToast.add(ToastManager, SystemToast.SystemToastId, Component title, @Nullable Component message)`; `SystemToastId` has public constructors (`()` shows 5,000 ms, `(long displayTime)`), so a mod makes its own id instead of reusing vanilla's. The manager is `Minecraft.getInstance().getToastManager()`. A payload handler that calls a client-only class from inside its lambda body does not load that class on a dedicated server.
- Toast clicks (patched 26.1.2.109 sources, B09 item 21d): `ToastManager` has no mouse handling at all (only `update`, `extractRenderState`, `addToast`, `getToast`, `clear`), so a vanilla toast cannot be clicked; a clickable notice must be drawn by the open screen itself. `StreamCodec.composite` has overloads up to 12 fields.
- `SnapshotJournal<Integer>` with the undo-log size as the snapshot works as a cheap rollback for counters changed inside a pull's transaction: `revertToSnapshot(mark)` undoes the log back to `mark`, `onRootCommit` clears it (`JobRun.Claims`).
- Never change a stack after returning it from `getDrops`: vanilla's `Block.popResource` happens to spawn it without a copy, but quarries and miners call `getDrops` and copy or discard the result, `BlockDropsEvent` lets mods swap the entities, and explosions merge drops. A block entity whose drop content is not final yet (the bridge with moves open in a transaction) leaves its item out of `getDrops` and spawns a fresh one once it is final (`BridgeBlockEntity.deferDrop`, `bridge_break_drops_copied`).
- Running sums kept beside such a log (per kind, per key) stay exact when `log` adds to them and the truncate in `revertToSnapshot(mark)` takes off each dropped entry: a nested abort reverts only its part, a nested commit keeps it for the root, and `onRootCommit` can just clear them. `revertToSnapshot(0)` or `onRootCommit` is the point where no open transaction has entries any more; work that must not run mid-transaction (re-reading a view the log adjusts) waits for it and is woken from there (`BridgeStorage`, `bridge_many_moves`, `bridge_unmount_in_transaction`).
- Error Prone 2.50 (`ParameterName`) crashes with "An unhandled exception was thrown" on a method call whose argument is a nested ternary spread over several lines after Spotless formatting; pull the value into a local first.
- `Block#onPlace` runs with `oldState` set and `affectNeighborsAfterRemoval` after removal, so a plain block without a block entity (a CPU part) can tell the cluster's cores on both from `common`.

## Pattern providers and the crafting assembler (M7c area B; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources and by the `provider_*` GameTests)

- `RootCommitJournal(Runnable)` (NeoForge transfer) fires only when every transaction it joined commits up to the root: an aborted nested transaction drops its snapshot, so the callback never runs for a simulation. Use it to report a move made inside a caller's transaction (the interface's `CraftingDeliveries` report).
- `onRootCommit` callbacks may open new root transactions (`TransactionManager.processRootCommitQueue` queues their callbacks), and `Transaction.getLifecycle()` reports `NONE` or `ROOT_CLOSING` exactly when `openRoot()` is safe; `OPEN`/`CLOSING` mean a caller's transaction is live (`CraftingOutputs` pulls counted outputs at once only then; `cpu_outputs_reserved`).
- A crafting pattern without a `recipe` id is laid out row by row on the assembler's grid, which matches no shaped recipe such as sticks; set `recipeType` `minecraft:crafting` and the recipe id (the showcase's stick pattern).
- `CraftingInput.of(w, h, items)` trims empty rows and columns; `CraftingRecipe.getRemainingItems(input)` indexes the trimmed input. Use `CraftingInput.ofPositioned` and its `left()`/`top()` to map a remainder back to a grid cell.
- `ShapedRecipe.getIngredients()` is `@VisibleForTesting`; the public field `ShapedRecipe.pattern` gives `ShapedRecipePattern.ingredients()` (`List<Optional<Ingredient>>`, width x height, row by row) plus `getWidth()`/`getHeight()`.
- `RecipeManager.byKey(ResourceKey<Recipe<?>>)` returns `Optional<RecipeHolder<?>>`; `ServerLevel.recipeAccess()` is the `RecipeManager`. `AbstractCookingRecipe.cookingTime()` gives a furnace recipe's ticks.
- Brewing stand faces: `UP` reaches slot 3 (ingredient), sides reach slots 0, 1, 2 and 4 (bottles and blaze powder), `DOWN` reaches 0 to 3; taking slot 3 through a face only allows glass bottles. Potions stack to 1, so the index-free `ResourceHandler.insert` spreads three bottles over slots 0 to 2.
- Error Prone's `ArrayRecordComponent` fails the build for a record with an array component; use a small final class.
- `Transaction.openRoot()` throws when a transaction is open on the thread; `ProviderLogic.push` and the assembler's `craft` open their own root, so the executor calls them outside any transaction.

## Stock tab, interface crafting card, card retries (M7c area D follow-up; verified by the `crafting_stock_*` and `crafting_card_*` GameTests and `PlannerTest`)

- The planner (`Planner.node`) lets the root take its own key from stock before crafting, so a background request "make
  N" with N already stored plans zero crafts and the job ends `DONE` at once. A stock keeper that asks again on `DONE`
  then submitted a job every scheduler pass (seen as a request every 2 ticks in `crafting_stock_real_queue_furnace`).
  Top-up requests go through `CraftingPlanner.submitCrafted`, whose root crafts the whole amount; deeper nodes still use
  stock. Player requests keep the old behaviour.
- A task that returns `StorageTask.IDLE` is woken on Lib's doubling idle backoff, not on a fixed time: a port card
  retry meant for 100 ticks came at 127. A task that must come back at a set time returns the delay itself.
- A device that needs a timed wake from outside its own task (the interface card's retry) can schedule a small second
  `StorageTask` on its `DeviceLink`: `schedule` on an already scheduled task only wakes it, the task returns the
  remaining delay, then wakes the device's real task and returns `WAIT`.
- GameTest functions registered through `StorageTests.function` (a deferred register) must be registered during mod
  construction (the test class's static block); registering one from `RegisterGameTestsEvent` fails the server start
  with "Cannot register new entries to DeferredRegister after RegisterEvent has been fired".
- `StreamCodec.composite` takes up to twelve field pairs plus the constructor (`StockRule` uses eight since M7L2
  row 15), and
  `codec.apply(ByteBufCodecs.list(max))` bounds a list's size on decode.
- Error Prone's `IdentityHashMapUsage` fails the build for a field declared as `Map` and built as `IdentityHashMap`;
  declare it as `IdentityHashMap`.
- Research start nodes count as complete through `Research.isComplete(team, entry)` (`entry.startNode()`) but are not
  in `team.completed(id)`; completing the whole tree loops `useToken` until no node changes, checking `isComplete`
  (dev showcase, `showcase_builds_every_station`).
- Storage's processing machines never pull FE; they take what neighbours push (`energyHandler().insert`), and the
  creative energy source only gives FE to whoever extracts, so it cannot power them.
- `StorageNetworks.network(level, StorageNetworks.ID, pos)` can be null in the tick blocks are placed; the network forms
  on the level tick, so code that stores items right after building waits (the showcase's stock queue).
- Idle devices placed on the same tick back off in lockstep: once at `scheduler.maxBackoffTicks` (100 standalone, 200
  pack) all of them run in one burst every backoff period, spread over a few ticks only by the network group's time
  budget. 500 idle ports cost about 1.3 µs per run (0.5 µs of it `canOperate`'s route check), about 0.008 ms per tick
  on average. A per-tick median hides the bursts and a mean over two bursts is at the mercy of one GC pause; time
  whole backoff periods (issue #350, `PortPerfGameTests`).
- `--repeatCount` (`-Pgametest.repeat`) runs the copies at once in one batch and one level, so a perf test reading a
  level-wide timer (`LevelTasks.lastTickNanos`) sees every copy's work; read the network's own
  `TaskGroup.lastPassNanos()` (stale on ticks without a pass: check `runs()` changed).
- The same goes for any static state: `SetupGhosts`' waiting list is server-wide, so `setup_blueprint_denied` asking
  for its size saw `setup_blueprint_pending`'s lone port (verified 2026-10-15: 25 of 25 failures with
  `-Pgametest=maxtech_storage:setup_blueprint_* -Pgametest.repeat=25`, none after asking about the test's own
  position). Assert on the test's own position or network, never on a server-wide count.
- A test that fails before its own cleanup leaves its builds working in the level: a failed
  `showcase_steps_happen_without_a_player` skips `Showcase.clear`, and the perf tests after it that read level-wide
  timers (`LevelTasks.lastTickNanos`, `NetworkGraph.lastTickNanos`) measured the showcase's networks too (CI,
  2026-10-15: four perf budgets over by 2 to 5 times, on both attempts). `StorageTestEnvironment.teardown` now calls
  `Showcase.clear(level)` after every batch (a forced failure left 2 builds; teardown clears them).
- The telemetry recorder samples every 200 ticks and the sample wakes the alarm panel, then an alarm module and the
  bridge drain (about three runs within three ticks). A `PerfBudget.measureTicks` retry measures the 100 ticks right
  after the first window, so one of the two windows always holds a sample; `perf_controller_all_modules_10k` leaves
  runs within 5 ticks of `NetworkHistory.lastSampleTime()` out of its settled-run count (verified by forcing a retry:
  3 runs before, 0 counted after).

## Crafting CPU connected textures (B09 item 28; verified 2026-10-18 in the patched 26.1.2.109 sources, by `CpuConnectionsTest` and the `cpu_connect_cluster` GameTest; not yet seen in a client)

- `LevelChunk.setBlockState` calls `onPlace` before it creates the new block's block entity, and `Level.markAndNotifyBlock` skips the client update and neighbour updates of a `setBlock` whose state was changed again meanwhile (`newState == blockState` check). So a block must not change its own state from inside `onPlace`; the CPU blocks schedule a one-off block tick (`scheduleTick(pos, block, 1)`) and set their connections there. Removal (`affectNeighborsAfterRemoval`) may set neighbours' states directly.
- The client's `LevelRenderer.blockChanged` marks every section within one block of the changed position dirty, so a model that reads its neighbours' block states (up to two steps away along an edge) is rebuilt when any of them changes, with no extra sync.
- `BlockEntity.onLoad` for a loading chunk runs from `Level.tickBlockEntities` (NeoForge's fresh block entity list),
  after `ChunkStatusTasks` registered the chunk's tick containers, so `scheduleTick` at the block entity's own position
  from `onLoad` is safe. `LevelTicks.schedule` drops (and logs) a tick for a chunk without a container.
- `BlockStateProperties` (and so `BooleanProperty` constants) load vanilla's registries through `NoteBlockInstrument`/`SoundEvents`; a class that JUnit loads without a bootstrap must keep them in a nested holder class (`CpuConnections.Properties`).

## Stonecutter and smithing recipes from code (B09 item 16; verified 2026-10-18 in the patched 26.1.2.109 sources and by the `pattern_stonecutting_*` and `pattern_smithing_*` GameTests)

- Stonecutter: `level.recipeAccess().stonecutterRecipes()` is a `SelectableRecipe.SingleInputSet<StonecutterRecipe>`;
  `selectByInput(stack).entries()` lists the recipes for one input in the stonecutter's order, and on the server each
  entry's `recipe().recipe()` holds the `RecipeHolder` (clients get the set without recipes). Input is
  `new SingleRecipeInput(stack)`; `assemble(input)` gives the counted result (stone to two `stone_slab`). Vanilla ids
  look like `minecraft:stone_slab_from_stone_stonecutting`.
- Smithing: `SmithingRecipe` has `Optional<Ingredient> templateIngredient()`, `Ingredient baseIngredient()`,
  `Optional<Ingredient> additionIngredient()`; input `new SmithingRecipeInput(template, base, addition)` (an absent
  slot is `ItemStack.EMPTY`); `getRecipeFor(RecipeType.SMITHING, input, level)` finds it. `SmithingTransformRecipe`
  copies the base's component patch onto the result. `minecraft:netherite_sword_smithing` uses addition
  `#minecraft:netherite_tool_materials`. Neither recipe has crafting remainders (only `CraftingRecipe` has
  `getRemainingItems`).
- `RecipeHolder` is a record `(ResourceKey<Recipe<?>> id, T value)`; `level.recipeAccess().byKey(ResourceKey)` gives
  `Optional<RecipeHolder<?>>`.
- `ServerboundContainerButtonClickPacket` sends the button id as a VAR_INT, so `action * 16 + slot` ids above 255 are
  fine. `AbstractWidget` refreshes its tooltip from `isHovered()` whatever `active` is, so a greyed-out button still
  shows the tooltip that says why.

## Crafter and the item display (B09 item 29; verified 2026-10-18 in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources, by compiling, by runData and by the `crafter_*` GameTests; nothing of it seen in a client)

- `StacksResourceHandler` (so `ItemStacksResourceHandler`) calls `onContentsChanged(index, previous)` from its snapshot journal's `onRootCommit`, i.e. once the root transaction commits, never for a rolled-back change; `set(index, resource, amount)` calls it at once.
- `BlockEntityRenderDispatcher.tryExtractRenderState` calls `renderer.shouldRender(be, camera)` (after the frustum check) and only then `createRenderState()` and `extractRenderState`; so a renderer whose `shouldRender` returns false for an idle block allocates nothing for it. The default `shouldRender` is `Vec3.atCenterOf(pos).closerThan(camera, getViewDistance())` (one `Vec3` per call); `getViewDistance()` defaults to 64. `BlockEntityRenderState.lightCoords` is `LevelRenderer.getLightCoords(level, pos)`, the light at the block itself.
- An update tag without NBT boilerplate: `TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries)`, `store(name, ItemStack.CODEC, stack)`, `buildResult()`; the client reads it through `loadAdditional` (here `VersionedBlockEntity.loadSaved`) with `input.read(name, ItemStack.CODEC)`.
- A see-through window in a full block: a face with fully transparent pixels lands in the cutout layer (one partly transparent pixel makes it translucent). Behind it, model elements of zero thickness (`from` and `to` equal on one axis) with a single face pointing inwards act as inside walls: the chunk renderer culls back faces, so through any window only the walls facing the camera draw, never the world behind. `ElementBuilder.face(direction, ...)` writes just that face; NeoForge's builder leaves `uv` out of the JSON when it is the default. The block gets `noOcclusion()` so it is not a solid-render block and light reaches its own position, which the inner faces and a block entity renderer use (from reading `BlockBehaviour`; not checked in a client).
- JEI 29.43's `RecipeTypes` has `CRAFTING`, `STONECUTTING` and `SMITHING` for `IRecipeCatalystRegistration.addCraftingStation`.
- `Properties.noOcclusion()` only sets `canOcclude = false`: the occlusion shape becomes empty, so `isSolidRender()` is false and `getLightDampening` is 1 for a full-cube shape (0 only if skylight passes, which needs a non-full shape). `isRedstoneConductor` defaults to `isCollisionShapeFullBlock` and `isSuffocating` to `blocksMotion() && isCollisionShapeFullBlock`, so a noOcclusion full cube still conducts redstone and suffocates (`crafter_display` checks conduction).

## Crafter manager: slots over another block's container, terminal sources (B09 item 23; verified 2026-10-18 in the patched 26.1.2.109 sources and by the `crafter_manager_*` GameTests; the screens not yet seen in a client)

- Every vanilla click path that takes from a slot asks `Slot.mayPickup(player)`: `PICKUP`, `SWAP` and `QUICK_MOVE`
  (once, before `quickMoveStack`) directly, `THROW` and `PICKUP_ALL` through `safeTake`/`tryRemove`, which return
  empty when it is false. A direct `quickMoveStack` call (a test) skips that check, so the menu repeats it. Placing
  asks `mayPlace(stack)`, which gets no player.
- A menu whose slots sit over a forwarding `Container` can switch the target (another block's slots) while it is open:
  the cursor (`getCarried`) stays with the menu, so a carried item lands in the next target, and `broadcastChanges`
  syncs the new contents by its usual slot compare (`crafter_manager_move_between`).
- A `TerminalSource` that does not override `network()` (default null) gives its terminal menu a crafting session with
  no network, so requests, the jobs tab, locate and exports refuse; `WirelessTerminalSource` lacked it until B09 item
  23 (`crafter_manager_wireless_jobs`). A terminal menu's session ticks only after the client's `REFRESH` button click,
  which `TerminalScreen.init` sends, so a screen opened over the menu without the terminal screen sends it itself.
- A menu opened from a terminal (crafter manager, a provider screen from the overview) must keep the terminal's
  `TerminalSource` and ask its `stillValid` and `canAccess`; opening a menu closes the terminal's, but the source's
  checks (reach, wireless range, item still held) still work on their own.
- `Transaction.openRoot()` throws while another transaction is open on the thread, so an action that opens its own
  transactions (a crafting start) cannot run inside a payment's transaction: check the payment in a transaction that is
  rolled back, run the action, then pay in a new one (`RemoteCost`).

# Logic

## Logic tools (M6e area E; verified by the `logic_*` GameTests)

- `PuzzleSessions.submit` removes the player's session before it tells the `SolvedListener`s, and the listener gets only node, puzzle and difficulty, not the seed; a feature that must know which instance was solved (the puzzle cartridge) remembers the seed itself when it starts the session. Research ignores a session whose node id is not in its tree (`refusal` returns `UNKNOWN_NODE`), so a pseudo node id carries a session that only another listener reads.
- A slotless menu that streams values (the probe, like the controller tab) sends from `broadcastChanges()`, which the server calls only for a player's open menu; the menu's own game-time check gives the cadence, and nothing runs for a closed screen. A client menu built from the open buffer needs no server state.
- `ItemStack.split(1)` on a stack of several returns a copy with all components; setting a data component on the copy and handing it back with `Inventory.add` (else `Player.drop`) is how one stack member gets a component the rest lack.

## Rules across networks (M7L2 row 13; verified by the `logic_net_*` GameTests)

- `GameTestHelper.setBlock(pos, Blocks.AIR)` on a block entity's block calls its `preRemoveSideEffects` (the break path), so a registry entry released there is gone at once (`NetworkNames`).
- A `SavedData` held on `server.overworld().getDataStorage()` is one instance for every dimension, so a name registry for the whole server keys entries by dimension (`Level.RESOURCE_KEY_CODEC`) and block position.
- Error Prone also rejects test breaks such as `cond ? 0 : 0` (`DuplicateBranches`) and `x - x` (`IdentityBinaryExpression`); `x / 1000` compiles.

## Sensor readers (M7L area D; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `sensor_*` and `perf_sensor_idle` GameTests)

- `SignalGetter.getSignal(pos, direction)` asks the block at `pos` with the direction from the receiver to it: a reader
  at `S` facing `F` at `S.relative(F)` calls `level.getSignal(target, F)` (as `hasNeighborSignal` asks
  `getSignal(pos.below(), DOWN)`). A powered floor lever gives 15 that way.
- `BlockState.getAnalogOutputSignal(level, pos, direction)` takes the target's side that faces the reader: the
  comparator passes `facing.getOpposite()`. A water cauldron's comparator output is its `level` (1 to 3).
- NeoForge's `CauldronWrapper` is the cauldron's fluid handler: a full water cauldron reads 1000 mB of
  `minecraft:water`, level 1 less. A furnace read from a side face shows only its fuel slot.
- `IntegerProperty.getInternalIndex(v)` is `v - min` and `BooleanProperty`'s is 0 for true, so a reader wanting the
  number itself uses `getValue`, and `getPossibleValues().indexOf(v)` for enums; `Property.getName(value)` gives the
  word (`south`).
- `level.invalidateCapabilities(pos)` calls a `BlockLookup.cache(..., isValid, onInvalidate)` listener at once when the
  cache was queried since the last invalidation; querying a cache whose position is not loaded (it returns null) arms
  it, so the listener fires when the chunk loads.
- Error Prone's `EnumOrdinal` flags `ordinal()` in a lambda (an anonymous data slot's `get`); give the enum an explicit
  `id()` instead, as `ConditionType` does.

## Pack readers (M7L2; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `sensor_reader_*` GameTests and `PackReaderTest`)

- Server data listeners: `AddServerReloadListenersEvent` (game bus) `addListener(Identifier key, PreparableReloadListener)`.
  Vanilla `SimpleJsonResourceReloadListener<T>(Codec<T>, FileToIdConverter.json("<dir>"))` lives in common without a
  NeoForge import; `listMatchingResources` finds files in subfolders, so `<dir>/mod/x.json` gets the id `ns:mod/x`.
  NeoForge's patched `prepare` wraps the codec in `ConditionalOps.createConditionalCodec`, so a file's
  `neoforge:conditions` (`neoforge:mod_loaded`) skip it without any NeoForge code in the listener. A file that fails to
  parse is logged as `Couldn't parse data file '<id>' from '<location>': <error>` and left out; the others load.
  `apply` runs on the server thread. A later pack's file with the same id replaces the earlier one (one resource per
  location).
- `Codec.withAlternative(a, b)` reports both branches' errors ("Failed to parse either. First: Not a JSON object ...").
  For a value that may be a string or an object, dispatch on `ops.getStringValue(input).isSuccess()` so the error names
  the form that was written. `Codec.unboundedMap(k, v)` returns a partial map when one entry fails, so a validation
  after it reports a misleading second error; decode `Codec.PASSTHROUGH` values one by one to name the failing key.
- `Identifier.compareTo` compares the **path first**, then the namespace; sort by `toString()` for namespace order.
- `TagsUpdatedEvent.getUpdateCause()` is deprecated for removal in 26.1.2; subscribe to the subclass
  `TagsUpdatedEvent.ServerDataLoad` (after the server's tags are bound on load and `/reload`).
- `BlockEntity.saveWithoutMetadata(HolderLookup.Provider)` returns the saved `CompoundTag` (its `saveAdditional` plus
  `components`). `CollectionTag.get(int)` covers `ListTag` and the int, long and byte arrays (index must be below
  `size()`); `NumericTag.doubleValue()` reads any number tag. `CompoundTag.getListOrEmpty(String)` and
  `ListTag.getCompoundOrEmpty(int)` read a saved list and its entries without Optionals (compiled, M7L2 row 19
  GameTests).
- Vanilla saved-data names: furnace, smoker and blast furnace (`AbstractFurnaceBlockEntity`) `cooking_time_spent`,
  `cooking_total_time`, `lit_time_remaining`, `lit_total_time` (ints); brewing stand `BrewTime` (short), `Fuel` (byte).
  Coal gives `lit_total_time` 1600 and raw iron `cooking_total_time` 200 (`sensor_reader_furnace`).
- A GameTest can turn a world datapack on: write it under `server.getWorldPath(LevelResource.DATAPACK_DIR)/<name>`
  (`pack.mcmeta` with `min_format`/`max_format` = `SharedConstants.DATA_PACK_FORMAT_MAJOR`, 101), call
  `getPackRepository().reload()`, then `server.reloadResources(selected + "file/<name>")`. GameTest ticks run
  unthrottled, so a reload's wall-clock second spans hundreds of ticks: such a test needs a timeout of thousands of
  ticks. A pack folder left behind by a failed run is on in the next run's new world, so the test switches it off first.
- Mod builds for built-in readers (checked 2026-10-07 on Modrinth, CurseForge through api.cfwidget.com with JEI's
  26.1.2 files as a control, and the mods' mavens): Blood Magic, Mekanism, Botania, Create, Thermal Expansion/Foundation
  and Ars Nouveau have **no** 26.1.x NeoForge build (newest are 1.21.1), so Storage ships no reader for them yet. Neo
  Vitae (`neovitae`, MIT, a Blood Magic-like mod) has `26.1.2-1.1.33`.

## Logic Controller (M7L area B; by compiling against 26.1.2 and the `logic_controller_*` and `perf_logic_*` GameTests)

- `Level.getBestNeighborSignal(BlockPos)` (from `SignalGetter`) gives the strongest redstone signal into a block from
  any side; a block re-reads it in `neighborChanged`, which also fires when a redstone block is placed next to it.
- `Codec.either(first, second)` decodes with `first` and falls back to `second`. An item tag as
  `TagKey.codec(Registries.ITEM).fieldOf("tag")` goes first, since Lib's `StackKey.CODEC` dispatches on a `kind` field
  a tag object does not have (`LogicKey.CODEC`).
- `BlockPosArgument.blockPos()` needs no `CommandBuildContext`; `getLoadedBlockPos` refuses unloaded positions.
- GameTest sequences: when a `thenExecute` assertion fails and a later step fails too, the reported message can be the
  later step's (seen while breaking `logic_controller_budget_waits_and_resumes`); the test still fails, so read the
  whole sequence when a message looks unrelated.
- `DeviceLink#wake` drops a task's pending timer (`ScheduledTask.wake`), so a task that returned a delay (a budget
  wait, a logic delay) and is woken early must work its delay out again on the next run.
- `PlayerList#getPlayers()` holds only connected players: a GameTest `new ServerPlayer(...)` is never in it, so code
  that sends to "the team's online players" through it reaches nobody in GameTests (M7L area E notices).

## Sharing logic graphs as text (M7L2 chunk 7; verified in the patched 26.1.2.109 sources, by `GraphTextTest` and the `logic_share_*` GameTests; the clipboard not yet seen in a running client)

- `NbtIo.write(CompoundTag, DataOutput)` and `NbtIo.read(DataInput, NbtAccounter)` work in plain JUnit without a
  bootstrap, as do `NbtOps.INSTANCE` and `CompoundTag`. `read` throws `IOException` when the root is not a compound
  ("Root tag must be a named compound tag", e.g. a stream starting with a 0 byte) and runtime exceptions for quota,
  depth or bad tag types; `new NbtAccounter(quota, maxDepth)` bounds both.
- `Minecraft.getInstance().keyboardHandler` is a public field; `KeyboardHandler.getClipboard()` and
  `setClipboard(String)` read and write the system clipboard through Blaze3D's `ClipboardManager`.
- `ByteBufCodecs.stringUtf8(n)` takes any `n`; only `STRING_UTF8` is fixed at 32,767. A registered C2S payload has no
  other cap (see the packet size entry above), so the import packet carries up to 65,536 characters
  (`GraphText.HARD_MAX_LENGTH`); a longer string fails in the decoder and disconnects the sender.
- `MinecraftServer#getTickCount()` (via `player.level().getServer()`) is a per-server tick counter; the sharing rate
  limits use it so they span controllers and dimensions, keyed by player UUID and pruned of stale entries
  (`ShareLimits`).
- Access checks for logic actions (M7L2 batch 3, `logic_act_*` GameTests): the owner's fake player from
  `WorldActors.player` (a `FakePlayerFactory` player with the owner's UUID) has no game master permission in the GameTest
  server, so `OwnerSecurity.canAccess` judges it by owner, trust and team only, and `NetworkAccess.canConfigure(actor,
  device)` refuses a device owned by someone else. `new ItemStack(fluid.getBucket())` is empty for a fluid without a
  bucket (`getBucket()` returns `Items.AIR`; compare by stack emptiness, Error Prone refuses `!= Items.AIR`).

## Logic cards and templates (M7L2; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `logic_card_*`, `logic_paste_*` and `logic_template_*` GameTests)

- `SimpleJsonResourceReloadListener`'s constructor that takes ops is private; the public ones are
  `(Codec, FileToIdConverter)` (plain `JsonOps`, no registries) and `(HolderLookup.Provider, Codec, ResourceKey)`
  (registry folder layout). For registry-aware parsing of a custom folder, extend `SimplePreparableReloadListener` and
  call the public static `SimpleJsonResourceReloadListener.scanDirectory(manager, FileToIdConverter.json(dir),
  makeConditionalOps(), codec, map)` in `prepare`. `makeConditionalOps()` (NeoForge's `ContextAwareReloadListener`,
  which `SimplePreparableReloadListener` extends) builds registry ops from the lookup that `ReloadableServerResources`
  injects into every listener added through `AddServerReloadListenersEvent`; `scanDirectory` adds the
  `neoforge:conditions` wrapper and logs files that fail (`LogicTemplateListener`).
- Setting `DataComponents.MAX_STACK_SIZE` to 1 on one stack makes that stack unstackable (`getMaxStackSize()` reads it
  through the item); removing it (and the data component that made it different) lets it stack with fresh ones again.
- `ServerPlayer.tick` broadcasts only `containerMenu`, so while a menu without slots is open, inventory changes reach
  the client only after it closes; `player.inventoryMenu.broadcastChanges()` sends them at once (container id 0, which
  the client applies to its inventory menu under any screen). GameTest players have a null `connection`: guard it.
- `ByteBufCodecs.COMPOUND_TAG` reads with `NbtAccounter.defaultQuota()`; `TRUSTED_COMPOUND_TAG` is unlimited. A record
  data component holding a `CompoundTag` should copy it in its constructor and accessor (vanilla's `CustomData` does the
  same), since tags are mutable.
- In a `GameTestSequence`, a `thenExecute` that throws an assertion did not stop the following `thenWaitUntil` from
  ticking once: an unguarded `list.get(n)` there threw `IndexOutOfBoundsException` and crashed the test server. Guard
  such waits (`reloads.size() > n && ...`).

## Logic cost benchmark (M7L2 chunk 5; verified by `LogicCostBenchmark` and `bench_logic_actions` on 2026-10-08)

- Environment variables reach both the JUnit test JVM and the `runGameTestServer` JVM through the Gradle daemon, so
  `MAXTECH_BENCH=1 ./gradlew ...` gates benchmarks without build changes (`@EnabledIfEnvironmentVariable`, and a
  GameTest registered only when `System.getenv` says so). JUnit's working directory is `storage/common/build/test-work`;
  the GameTest server's is `runs/storage-neoforge/gameTestServer` (both ignored by git).
- `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` works on Temurin 25; C2's escape analysis removes
  allocations that don't escape, so a check that it counts needs an escaping allocation.
- `NetworkGameTests.insert` goes through `NetworkState.operate`, which pays FE per unit: inserting thousands of items
  drains a 1,000,000 FE buffer, and later moves return 0 with no other sign. A tier 1 port's `logicMove` moves at most
  its rate (16 items a second, about one per tick); repeated calls within one tick time the refusal.
- `System.nanoTime` on the Windows dev PC ticks in 100 ns steps.
- Logic hooks (M7L2 batch 4, `logic_hook_*` GameTests): a mock player from `makeMockServerPlayerInLevel` that opens a
  menu with extra data (`Platform.menus().open`, NeoForge's `advanced_open_screen` payload) does not just fail the
  test: the `UnsupportedOperationException` leaves the server tick loop and stops the whole GameTest run. Test what
  runs after the open separately (`TerminalMenu.opened`, with `player.containerMenu` set by hand). The same mock
  player is in `ServerLevel.players()`, is no spectator, and `setPos(Vec3)` moves it for distance checks. In extended
  mode a plan for 8 sticks with no planks is queued and the CPU ends it `PARTIAL` with `JobRun.missing` holding the
  plan's missing leaf (4 planks) as its first entry. A controller's buffer (`controller.bufferFe`, 10,000) pays for
  about 3,300 one-item operations at the preset 2 + 1 FE; perf tests that run thousands put a creative energy source
  on the network, or most operations are refused and the timing measures nothing.

## Machine pools (M7L2 row 17; verified in the patched 26.1.2.109 sources and by the `logic_pool_*` GameTests on 2026-10-08)

- A port block links to the network only through its back, the side opposite `FACING`
  (`PortBlockEntity` connects on `side == facing().getOpposite()`): a port on top of a furnace facing `DOWN` needs a
  cable (or device) above it, not beside it. A sensor block links on every side but its front.
- A vanilla furnace stays `lit=true` with an empty input until its burn time runs out: one cobblestone and one coal smelt
  in 200 ticks and the furnace keeps burning for the coal's other 1400 ticks, taking raw iron into slot 0 from `UP` if
  something sends it. Replacing a furnace with `setBlock` gives a sensor facing it a fresh read (`state.lit` 0).
- `BlockPos.offset(long, Direction)` (static) moves a packed position without allocating a `BlockPos`.
- `GameTestHelper.assertValueEqual(a, b, name)` fails with "Expected <name> to be <a>: was <b>", so with the actual
  value first (as most of our tests write it) the message reads the wrong way round; the check itself is symmetric.
- A chest with a solid block on top (a port facing down into it) gives comparator output 0 whatever it holds, as
  vanilla treats it as blocked; its item handler still shows the items. A barrel under the same port reads normally
  (`logic_pool_direct_comparator_reads`, items put in through the handler from the side).
- Error Prone's `UnnecessaryLambda` also rejects an instance field holding a lambda (`private final Runnable w = () ->
  offerAll(...)`); a method reference to a private method (`this::sheddingChanged`) passes and keeps one identity for
  watch/unwatch (load shedding, M7L2).
- Logic node settings share one lang key per setting name (`gui.maxtech_storage.logic.setting.<name>`): two node types
  giving the same setting name different English labels make `NodeTypes` fail to initialise ("has two texts"), which
  shows as `NoClassDefFoundError` in every logic JUnit test. Reuse the existing label ("Priority").
- A storage port block links to its network only through its back (the side opposite its facing), as GameTests show: one
  facing up beside a device did not join, one facing up on top of the controller did; place test ports facing away from
  the device they should link to (`LoadSheddingGameTests`).
- `AbstractContainerScreen` (26.1.2 sources) reads `leftPos`/`topPos` at every use: `extractContents` translates the
  pose by them before labels and slots, `isHovering`/`getHoveredSlot` subtract them, `hasClickedOutside` gets them
  passed. So a screen can move its panel (scrolling) by changing `topPos` and moving its widgets; `init()` sets
  `topPos = (height - imageHeight) / 2` first. `mouseReleased` with a carried stack and `hasClickedOutside` true sends
  slot -999 (throws the stack), even when a widget outside the panel took the click; override `hasClickedOutside` for
  such widgets. `mouseScrolled` only offers the wheel to bundle-style slot actions and returns false otherwise.
- NullAway (this build, JSpecify annotations): an array whose elements may be null is declared `@Nullable Object[]`; writing `null` into a plain `Object[]` fails with "Writing @Nullable expression into array with @NonNull contents" (verified 2026-10-09, `LogicEvaluator`).
- `System.nanoTime()` costs about 20 ns per read on the Windows dev machine (JDK 25): a timed pair is about 40 ns, measured
  10M times (2026-10-10, network profiler). Timing every scheduled task always-on would add that per run, so the
  profiler's hooks read the clock only while a session runs.
- GameTests (verified 2026-10-10, `generator_*`): `GameTestHelper.assertValueEqual(a, b, name)` fails with "Expected <name> to be <a>: was <b>", so the first argument reads as the expectation. The GameTest server runs ticks as fast as it can: a test waiting 600 ticks finished in about 0.6 s. A player-style `network.insert` pays FE per item, so 1,000,000 FE on a controller stores about 10,000 items; fill a test storage device with `MemoryProvider.setDirect` instead.
- `DataComponentGetter.getOrDefault(type, fallback)` exists and is what `applyImplicitComponents` uses to read a settings component (verified by compiling `GeneratorProviderBlockEntity`, 2026-10-10).
- `PlayerInventoryWrapper.of(player).insert(ItemResource, int, tx)` (NeoForge `transfer.item`, 26.1.2) stacks into the
  main hand, then the offhand, then `insertStacking` over the 36 main slots, and returns what went in; it never drops
  (only `placeItemBackInInventory` drops the rest). Its root commit calls `player.containerMenu.broadcastChanges()`,
  which is harmless for a GameTest `ServerPlayer` without a connection (`craft_deliver_to_player`).
- `ServerPlayer.snapTo(...)` calls `connection.resetPosition()`, so it throws for a GameTest player made with
  `new ServerPlayer(...)` (no connection); use `setPos`. `sendSystemMessage` throws for such a player too (it sends on
  the connection): guard with `player.connection != null`.
- Pick-block is server side in 26.1 (`ServerGamePacketListenerImpl.handlePickItemFromBlock`): within block interaction
  range + 1 it takes `getCloneItemStack(pos, level, includeData, player)` and `tryPickItem` selects or swaps a
  carried match; only a player with infinite materials gets a new stack (`Inventory.addAndPickItem`, which overwrites
  the hotbar slot when the inventory is full). NeoForge has no server event for it; the client fires
  `InputEvent.InteractionKeyMappingTriggered` (cancellable, `isPickBlock()`) before sending the packet.
- Book bounds (patched 26.1.2.109 sources, 2026-10-10, `export_book_pages_bounded`): `WritableBookContent.MAX_PAGES`
  is 100 and `PAGE_EDIT_LENGTH` 1024; `WrittenBookContent.PAGE_LENGTH` is 32767 (a page's component); the book screen
  lays text out in `BookViewScreen.TEXT_WIDTH` 114 by `TEXT_HEIGHT` 128 pixels (14 lines). A written book's pages may
  be translatable components with item names, so a server can write a list the reader sees in their own language.
- Error Prone in this build also fails on `EnumOrdinal` (send an enum through `ByteBufCodecs.idMapper` with an explicit
  id), `JavaTimeDefaultTimeZone` (`LocalDateTime.now(ZoneId.systemDefault())`) and `NonCanonicalType` (fastutil's
  `Object2LongOpenHashMap.Entry` must be written `Object2LongMap.Entry`). Verified 2026-10-10 by compiling
  `terminal.export`.
- Left-click on a block (verified 2026-10-10 in the patched 26.1.2.109 `ServerPlayerGameMode.handleBlockBreakAction`):
  on `START_DESTROY_BLOCK` the server fires NeoForge's `LeftClickBlock` event, checks range (+1), spawn protection and
  `mayInteract`; a player with `instabuild` destroys the block at once and `BlockState.attack` is never called; otherwise
  `attack(level, pos, player)` runs (unless the event set use-block FALSE), then mining starts. `attack` has no hit
  result, so a multi-part block traces the player's look itself (`StorageCableBlock.partLookedAt`). In a GameTest call
  `state.attack(level, pos, player)`, `state.useItemOn(stack, level, player, hand, hit)` and
  `state.useWithoutItem(level, player, hit)` directly (`display_click_*`).
- Simulating a chunk unload and reload of one block entity in a GameTest (verified 2026-10-10, `cell_transfer_unload_resume`):
  take `be.saveWithFullMetadata(registries)`, call `be.onChunkUnloaded()`, then `level.getChunkAt(pos).removeBlockEntity(pos)`
  (calls `setRemoved` once, leaves the block state); later `BlockEntity.loadStatic(pos, state, tag, registries)` and
  `chunk.addAndRegisterBlockEntity(fresh)`, which runs NeoForge's `onLoad` through `level.addFreshBlockEntities`.
  `LevelChunk.setBlockEntity` calls `setRemoved` on a block entity it replaces.
- Java, not Minecraft, but it cost a test round: a nested class that implements `StorageTask` sees the interface's
  `DONE` (-2) before an outer class's own `DONE` constant, silently. `CellTransfer` names its result `FINISHED` for that.
- A storage device that links to nothing (verified 2026-10-10, `smart_label_*`): it still gets a `NetworkState` of its
  own (one node, ad hoc) as soon as it loads, and that network's scheduler runs its tasks, so a device never needs a
  block-tick fallback for "no network". `NetworkState.nodeCount() > 1` says whether it links to a cable or device.
- `Block.useItemOn` results (verified 2026-10-10 in the patched 26.1.2.109 `ServerPlayerGameMode.useItemOn`): a result
  that consumes the action stops there; `TRY_WITH_EMPTY_HAND` (main hand only) goes on to `useWithoutItem`; `PASS` skips
  `useWithoutItem` and goes straight to the item's own use, so a block can let its own item be placed against it.
- In a GameTest a `DisplayViewers.watch` made in the same tick as `helper.setBlock` of the screen's block can be lost:
  the screen registers its chunk from NeoForge's `onLoad`, which ran later (seen 2026-10-10). Wait until the worker has
  started before watching (`SmartLabelGameTests.watch`).
- The sensor's `items.capacity` (and `MachineReader`'s) counts an empty slot at the handler's capacity for an empty
  resource: a vanilla chest with 64 cobblestone in one slot reads 2,638 (64 + 26 x 99), not 27 x 64 (seen 2026-10-10).
  For a fill level use `MachineReader.itemFill` (each slot against `ItemResource.getMaxStackSize()`).

# Displays and rendering

## Display panels: chunk viewers, placement order, journals (M6c area D; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources and by the `display_*`, `network_stats_*` and `perf_display_unseen_screens` GameTests)

- `LevelChunk.setBlockState` calls the new state's `onPlace` before it creates the block entity, so a block that needs
  its own block entity on placement schedules a block tick (`level.scheduleTick(pos, block, 1)`, handled in
  `tick(BlockState, ServerLevel, BlockPos, RandomSource)`); `affectNeighborsAfterRemoval(state, ServerLevel, pos,
  movedByPiston)` runs after the block and its block entity are gone. Both fire for `helper.setBlock` in GameTests.
- Players who can see a chunk: `level.getChunkSource().chunkMap.getPlayers(ChunkPos, false)` (public; builds an
  `ImmutableList` over all players, so seed a list once and then follow the events). NeoForge fires
  `ChunkWatchEvent.Sent` from the `PlayerChunkSender` patch once the client has the chunk and `ChunkWatchEvent.UnWatch`
  from `ChunkMap.dropChunk` (also when a player leaves or changes dimension); `getPos()` is a `ChunkPos` record
  (`pack()`, static `pack(BlockPos)`, `unpack(long)`).
- `SnapshotJournal` (NeoForge transfer): `updateSnapshots(TransactionContext)` is public and must be called before
  changing the journaled state; `createSnapshot`, `revertToSnapshot` and the optional `releaseSnapshot` (a place to
  pool snapshot objects) are protected. A plain counter journaled this way only ever holds committed values.
- Codecs: `ByteBufCodecs.idMapper(IntFunction, ToIntFunction)` for enums by id, `ByteBufCodecs.optional(codec)`,
  `TagKey.codec(Registries.ITEM)` and `TagKey.streamCodec(Registries.ITEM)`; `net.minecraft.network.VarLong.read/write`
  exist beside `VarInt`. A value with registry-dependent parts encodes into a block entity's update tag with
  `CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value)`, and `loadAdditional` reads it back
  with `input.read(name, CODEC)`.
- Block items get their item model from the block model without any datagen line: the generated
  `items/<block>.json` points at `block/<block>` when a provider writes the block's model and no item model.
- Error Prone (as configured) fails `public static final int[]` (`MutablePublicArray`; an unmodifiable fastutil
  `IntList` passes) and `x instanceof T t` where `x` is already declared `T` (`BadInstanceof`; compare with null).
- A `ServerPlayer` built in a GameTest has no connection, and `PacketDistributor.sendToPlayer` throws a
  `NullPointerException` on it that crashes the game test server; a sender that may reach such players checks
  `player.connection != null` first. GameTests of one batch run side by side, so a test that swaps a static hook
  (a payload sender) must not reset it while another test of the batch still runs.
- History charts (M7L2, by compiling and the `history_*` GameTests): `StreamCodec.composite` over a
  `RegistryFriendlyByteBuf` takes a `StreamCodec<ByteBuf, T>` field as is (`DisplaySettings#chart`). fastutil's
  `Reference2IntOpenHashMap.reference2IntEntrySet().fastIterator()` yields `Reference2IntMap.Entry`, not
  `Object2IntMap.Entry`. Error Prone flags `fluid == Fluids.EMPTY` (`ReferenceEquality`); use `fluid.isSame(Fluids.EMPTY)`.
  The game test server sprints: 400 test ticks pass in about a second of wall time.

## Block entity renderers and hologram drawing (M6c area D PR 2; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources and by compiling; not yet seen in a running client)

- `BlockEntityRenderer<T, S extends BlockEntityRenderState>`: `createRenderState()`, `extractRenderState(be, state,
  partialTicks, cameraPos, @Nullable CrumblingOverlay)` (call the default for `blockPos`, light and type), `submit(state,
  PoseStack, SubmitNodeCollector, CameraRenderState)` with the pose at the block's corner, `shouldRender(be, camera)`
  (default: within `getViewDistance()`, 64), `shouldRenderOffScreen()`. `BlockEntityRenderDispatcher.tryExtractRenderState`
  checks the frustum against NeoForge's `getRenderBoundingBox(be)` (`IBlockEntityRendererExtension`, default the unit
  box) first, then `shouldRender`, and makes a new state each frame. Renderers with `shouldRenderOffScreen` go into the
  global list instead of their section's, so a box larger than the block still renders when its section is culled.
- Registering: NeoForge's `EntityRenderersEvent.RegisterRenderers#registerBlockEntityRenderer(type, provider)` (mod
  bus, client; a `Dist.CLIENT` `@EventBusSubscriber` picks it up); the provider gets a `BlockEntityRendererProvider
  .Context` record with `font()`.
- Text in the world: `SubmitNodeCollector.submitText(pose, x, y, FormattedCharSequence, dropShadow, Font.DisplayMode
  .POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT, argb, backgroundArgb 0, outlineArgb 0)`, as signs do; text runs down y,
  so a y-up plane scales by `(s, -s, s)`. `Font.lineHeight` is 9.
- `RenderTypes.debugQuads()` is `POSITION_COLOR` quads with translucent blending, depth test `LESS_THAN_OR_EQUAL`, no
  depth write, no cull and no lightmap: emissive coloured quads through `submitCustomGeometry(pose, type, renderer)`,
  writing `addVertex(pose, x, y, z).setColor(argb)` four per quad. `RenderTypes.lightning()` writes to the weather target,
  so it is not a general glow type.

- Dimension changes (NeoForge `26.1.x` `ServerPlayer` patch): `PlayerEvent.PlayerChangedDimensionEvent(player, from,
  to)` is posted at the end of the transition, when `player.level()` is already the new level, and `ChunkWatchEvent
  .UnWatch` fires with `player.level()`, so per-level viewer lists drop the player from the old level on that event
  (and on `PlayerEvent.PlayerLoggedOutEvent`). A GameTest posts the event itself with a player built in the target
  level (`server.getLevel(Level.NETHER)`).

- Block capability providers are asked in registration order and the first non-null answer wins (`BlockCapability.providers` is a list per block; `RegisterCapabilitiesEvent.registerBlock`/`registerBlockEntity` append). To take a side from a provider another class registers for the same blocks, register in a `@SubscribeEvent(priority = EventPriority.HIGH)` listener and return null where the other should answer (NeoForge 26.1.2.109 sources; P2P energy inputs over Storage's network FE input, `p2p_energy_loses_percent`).
- `Level.getSignal(neighbourPos, direction)` with `direction` pointing from the asking block to `neighbourPos` is the signal that neighbour gives toward the asker (vanilla `hasNeighborSignal` asks `getSignal(pos.below(), DOWN)`), direct power through a conductor included; a block's own `getSignal(state, level, pos, direction)` receives that same direction, so a block that powers only its front `facing` answers when `direction == facing.getOpposite()` (patched 26.1.2.109 sources; `p2p_redstone_mirrors`).

## Rich text on displays (2026-10-07; verified in the patched 26.1.2.109 sources, compiled, and by the `display_text_*` and `display_notice_markup` GameTests; the client side not yet seen in a running client)

- Items in the world from a renderer: `Minecraft.getInstance().getItemModelResolver().updateForTopItem(ItemStackRenderState,
  stack, ItemDisplayContext, @Nullable Level, @Nullable ItemOwner, int seed)` fills a reusable `new ItemStackRenderState()`
  (public constructor; it clears itself first), then `state.submit(poseStack, submitNodeCollector, light, overlay,
  outlineColour)` submits its layers, each applying its display-context transform. Vanilla's `ShelfRenderer` and
  `ItemFrameRenderer` resolve in the extract phase; resolving in submit on the render thread works the same. The model is
  centred on the origin, one unit wide, so `translate(centre)` and `scale(size, size, depth)` place an icon;
  `ItemDisplayContext.GUI` gives the inventory look (blocks isometric). `OverlayTexture.NO_OVERLAY` is in
  `net.minecraft.client.renderer.texture`.
- `Font`: `width(FormattedText)` respects bold; `substrByWidth(FormattedText, width)` returns the styled head that fits;
  a style's colour wins over the colour passed to `submitText`/`text`, which only lends its alpha
  (`Font.PreparedTextBuilder#getTextColor`). `Style.EMPTY.withColor(int rgb)`, `withBold(Boolean)`, `withItalic(Boolean)`;
  `Component.visit((style, text) -> Optional, Style.EMPTY)` walks the styled parts.
- `MultiLineEditBox.builder().setX().setY().setTextColor().setCursorColor().build(font, w, h, narration)`;
  `setCharacterLimit`, `setLineLimit`, `setValue(text, allowOverflowLineLimit)`, `setValueListener(Consumer<String>)`,
  `getValue()`. Its `MultilineTextField` is private, so there is no public "insert at cursor": `charTyped(new
  CharacterEvent(codepoint))` while the box is focused inserts at the cursor through the normal input path
  (`CharacterEvent` is a record of the codepoint only). Paste (Ctrl+V) and line breaks work as typed.
- `Registry#containsKey(Identifier)`, `getValue(Identifier)` (the defaulted item registry returns air for a missing id,
  so check `containsKey` first); `Item.getDescriptionId()` for a translatable name without a stack.
- Error Prone: `List.remove(int)` on a `List<Integer>` is `ListRemoveAmbiguous` (write `remove(/* index */ i)`); a
  record argument list with two ints in a row may trip `ArgumentSelectionDefectChecker` (add `/* name= */`).
- GameTest selection: `-Pgametest` is one glob (`maxtech_storage:display_text_*`); a comma list or `[abc]` class does not
  select more. FTB Chunks once joined `runGameTestServer` through the user's `withFtbChunks=true` and
  `display_viewer_leaves_dimension` then crashed the server (FTB Chunks sends to the connectionless test player on the
  dimension change event); since PR #45 GameTest and runData tasks never load the FTB mods.
- Multipart block states in datagen: `MultiPartGenerator.multiPart(block).with(ConditionBuilder | Condition,
  MultiVariant)`, then `blockModels.blockStateOutput.accept(generator)`. `new ConditionBuilder().term(prop, value)`
  ANDs terms; an OR is `new CombinedCondition(CombinedCondition.Operation.OR, List.of(builder.build(), ...))`
  (`net.minecraft.client.renderer.block.dispatch.multipart`). Variants get rotations with
  `BlockModelGenerators.plainVariant(id).with(X_ROT_270.then(Y_ROT_180))`; a block item's model can be overridden with
  `blockModels.itemModelOutput.accept(block.asItem(), ItemModelUtils.plainModel(id))` (verified by `runData`, PR on
  display walls).
- Block model rotations: `Quadrant.fromXYZAngles` composes `z.compose(y.compose(x))`, so x is applied before y.
  `x: 90` turns a north-facing front down and its top to the north; `x: 270` turns it up with its top to the south, so a
  screen facing up whose top should be north needs `x: 270, y: 180` (checked in the 26.1.2 sources; the display panel's
  look facing up still awaits a dev-client check).
- `Level.setBlock` with a state of the same block keeps the block entity (`LevelChunk.setBlockState` calls
  `blockEntity.setBlockState`), runs `onPlace` with the old state of the same block, and skips
  `affectNeighborsAfterRemoval`; with only `Block.UPDATE_CLIENTS` no neighbour is updated. Display panels use this to
  store their frame edges in the block state.
- Screen sizing and text boxes (patched 26.1.2 sources, compiled; owner's dev-client check pending):
  `AbstractContainerScreen.imageWidth`/`imageHeight` are `protected final`, set only by the constructor, so a screen whose
  width depends on measured text computes it before `super(...)` (`Minecraft.getInstance().font` is ready then; the
  Logic Controller screen does this). `EditBox.setValue` leaves the cursor at the end, so a long value shows its end;
  `moveCursorToStart(false)` scrolls back to the start, and `setFocused(boolean)` is public and overridable to do it on
  focus loss. `EditBox.getInnerWidth()` is public. `Level.getGameTime()` works on the client level for countdowns.
- Screens that resize (M7L2 chunk 10; patched 26.1.2.109 sources, compiled; client check pending): `leftPos`/`topPos`
  are plain protected fields that `AbstractContainerScreen.init()` sets from `imageWidth`, so a screen may set them again
  after `super.init()` to centre a frame smaller than its image (the Logic Controller screen sizes its image from
  `Window.getGuiScaledWidth()`/`getGuiScaledHeight()` in the constructor and shrinks the frame in `init`). A GUI scale
  change calls `Screen.resize` -> `repositionElements` -> `rebuildWidgets` (`clearWidgets`, `clearFocus`, `init`);
  `Screen.init(w, h)` on an already initialised screen (coming back from a sub-screen) does the same. A widget's
  `onPress` runs inside `ContainerEventHandler.mouseClicked`, which then calls `setFocused(child)`, so rebuilding the
  widgets from a press leaves the removed widget focused; defer the rebuild (the controller screen does it at the start
  of `extractBackground`). `EditBox.setBordered(false)`, `setTextColor(int)` and `setHint(Component)` exist; a borderless
  box draws only its text, so the screen draws the field behind it.

## Alarm panel (M7C item 16; verified in the Storage sources and by the `alarm_panel_*` GameTests and a full GameTest run on 2026-10-10)

- `StorageDeviceBlockEntity.wake(task)` only wakes for the network's next pass; there is no delayed wake. A task that wants a later look returns the delay from `run`, and every return of `run` (even a "not yet" bounce) counts in `scheduler().runs()`, which `iface_stock_refills_and_sleeps` (no run in 60 idle ticks) and `perf_storage_idle_interfaces` (at most 10) assert. A watcher of content changes on a busy network must not wake per change: the alarm panel looks at bytes in use once per `alarms.recheckTicks` and lets the next change after that take the owed look.
- `scheduledTasks()` counts a task asleep on `WAIT`, so a core's always-scheduled alarm task shows there (`controller_telemetry_records` expects 1 after the recorder goes).
- A fresh controller holds 0 FE of its 10,000 (`controller.bufferFe`), so an unpowered test network raises the low power alarm at once and posts "Alarm: Low power" to the network log, which pings bound logic remotes and starts their ping throttle; tests counting log lines or remote pings power the network first.
- Chat copy link: `new ClickEvent.CopyToClipboard(String)` in a `Style.withClickEvent` copies on click (compiles on 26.1.2); a server cannot write a player's clipboard, so a "copy" button on a world screen sends such a chat line. `ServerPlayer.sendOverlayMessage(Component)` is the action bar.
- Survival breaking order (patched 26.1.2.109 `ServerPlayerGameMode.destroyBlock`, B09 item 27): the block entity is read first, then `playerWillDestroy`, then `removeBlock` (which runs the block entity's `setRemoved`), and only then `playerDestroy(level, player, pos, state, blockEntity, tool)`, whose drops get that already removed block entity. A block whose drop reads live state the block entity drops in `setRemoved` (a mounted cell) must keep a snapshot of it there (`StorageBlockEntity` keeps its cell stats). `BlockItem.onDestroyed(ItemEntity)` spills a `CONTAINER` component; a block item that overrides NeoForge's `onDestroyed(ItemEntity, DamageSource)` calls `super` to keep that.
- Item entities going for good (patched 26.1.2.109 sources, `recovery_cell_destroyed`): `ItemEntity.hurtServer` calls `ItemStack#onDestroyed(entity, source)` (NeoForge's `IItemExtension.onDestroyed(ItemEntity, DamageSource)`, overridable on an `Item` without importing the interface) and then `discard()` once health reaches 0 (lava, fire, explosions, cactus). Despawning posts `ItemExpireEvent` (game bus; `getExtraLife()`), then sets `lifespan = clamp(lifespan + extra, 0, Short.MAX_VALUE - 1)` and discards when `getAge() >= lifespan`, without `onDestroyed`. Falling out of the world is `Entity.checkBelowWorld` (Y below `getMinY() - 64`) -> `onBelowWorld()` -> `discard()`, also without `onDestroyed`; `EntityLeaveLevelEvent` then sees removal reason `DISCARDED`, which merges use too, so check the Y.

## Item looks: tinted vanilla materials, 3D cable items (2026-10-15; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources, by `:storage:neoforge:runData` and the `item_models_*` GameTests; not yet seen in a client)

- A mod model may name vanilla textures by id (`minecraft:item/sugar`, `item/iron_ingot`, `item/quartz`, `item/raw_iron`): the texture comes from the player's own game, so nothing of Mojang's ships. In 26.1.2 `item/quartz` is a pale crystal cluster and `item/sugar` the lightest, nearly colourless vanilla dust; both take a multiplied constant tint well (`MaterialLooks`).
- NeoForge `FaceBuilder.color(argb)` writes `"neoforge_data": {"color": "aarrggbb"}`; `FaceBakery` turns the face's `ExtraFaceData` colour into the quad's `BakedColors`, and `ItemFeatureRenderer` draws item quads with `putBakedQuad`, which multiplies them, so an element model can bake a colour per face without an item tint source. `lightEmission(n)` writes `light_emission` beside it.
- For element models the item render type is per quad (`materialInfo().itemRenderType()`), worked out from the sprite's pixels when the JSON model is baked, so a face with a translucent sprite (the cable's glass sleeve) draws translucent without naming a render type.
- `ExtendedModelTemplateBuilder.transform(ItemDisplayContext, t -> t.rotation(x, y, z).translation(...).scale(s))` writes the `display` entries; contexts left out come from the parent (`minecraft:block/block`). The transform applies `rotationXYZ`, so z turns the model first.
- `runGameTestServer`'s classpath has the mods' assets but no vanilla ones (`assets/minecraft/textures/...` is absent), so a GameTest can check mod textures exist but vanilla textures only by id.
- Lib's `ItemModel.existing(model)` writes only the item definition, for items whose model the mod's own datagen writes (cable items, `CableItemModels`); a conduit must still name an item model.

# Research

## Research puzzle payloads (M6b area D; verified in the patched 26.1.2.109 sources, by compiling and by the `puzzle_*` GameTests)

- `net.minecraft.network.VarInt.read(ByteBuf)` and `VarInt.write(ByteBuf, int)` are public statics, so a hand-written `StreamCodec<ByteBuf, T>` can read a length and refuse it (throw `io.netty.handler.codec.DecoderException`) before allocating; there is no capped int-array codec in `ByteBufCodecs` (it has `LONG_ARRAY`, uncapped).
- `Identifier.STREAM_CODEC` is a `StreamCodec<ByteBuf, Identifier>`, so it composes into a plain `ByteBuf` payload codec with `ByteBufCodecs.VAR_INT`/`VAR_LONG`/`BOOL`.
- Error Prone's `ArrayRecordComponent` fails the build (`-Werror`) for an array component in a record, payload records included; carry a fastutil `IntList` instead (`IntArrayList.wrap` on decode, content equality for tests).
- A gametest-source-set class annotated `@EventBusSubscriber` is loaded during mod construction, so its static initialiser can register into a plain static registry (a test `PuzzleType`) before the game test server starts.
- Screen input (patched 26.1.2.109 sources, compiled; the puzzle screens are not yet seen in a client): `GuiEventListener.mouseClicked(MouseButtonEvent, boolean doubleClick)`, `mouseReleased(MouseButtonEvent)`, `mouseDragged(MouseButtonEvent, dx, dy)`, `mouseScrolled(x, y, scrollX, scrollY)` and `keyPressed(KeyEvent)`; `MouseButtonEvent` is a record `(double x, double y, MouseButtonInfo)` with `button()`. `InputWithModifiers` gives `isSelection()` (Enter, Space, keypad Enter), `isConfirmation()` (Enter, keypad Enter), `isEscape()`, `isLeft/Right/Up/Down()`, `getDigit()` and `hasShiftDown()`. `Screen.keyPressed` closes on Escape first, then hands arrows and Tab to focus navigation, so a screen that uses arrows itself handles them before calling `super`.
- Widgets (patched 26.1.2.109 sources, B09 item 21a; compiled, not yet seen in a client): `AbstractWidget.extractRenderState` is final and returns early while `visible` is false, so a widget cannot hide itself from inside its own draw; set `visible` from the screen (`init`, `containerTick` or `extractBackground`). `isActive()` is `visible && active`, and `mouseClicked` ignores inactive widgets. `isValidClickButton(MouseButtonInfo)` defaults to the left button only; `AbstractButton.onClick` calls `onPress(event)`, and a key press calls `onPress` for `isSelection()`. `isHovered` is computed before each draw whether or not the widget is active. Vanilla tooltips: `setTooltip(Tooltip.create(Component))`; the tooltip holder shows it while hovered (or keyboard-focused), active or not, wrapped at 170 px, and a `"\n"` in the component (`CommonComponents.joinLines(...)`) breaks lines. `Screen.extractRenderStateWithTooltipAndSubtitles` draws `extractBackground`, a new stratum, then `extractRenderState` (the renderables in insertion order), so a panel drawn in `extractBackground` sits under every widget. `ContainerEventHandler.mouseClicked` gives the click to the first child under the mouse, without iterating while it runs, so a widget's action may add or remove widgets. `AbstractContainerScreen.mouseReleased` calls `super.mouseReleased` (which releases the dragged widget) before `hasClickedOutside`. `Screen.addRenderableWidget`, `addRenderableOnly` and `removeWidget(GuiEventListener)` are protected; method references to them work as `Consumer<AbstractWidget>`. `SystemToast.addOrUpdate(ToastManager, id, title, @Nullable message)` replaces a toast with the same id that is still showing.
- `TranslatableContents` turns `%%` into a literal percent sign (`"Match %s%%"`).

## Research core: loot modifiers, advancements, datapack registries (M6b area C; verified in the NeoForge `26.1.x` and patched 26.1.2.109 sources, by `:storage:neoforge:runData` and the `research_*` GameTests)

- Global loot modifiers in 26.1 have **no** `global_loot_modifiers.json` list: `LootModifierManager` is a `SimpleJsonResourceReloadListener` over every file in `data/<ns>/loot_modifiers/`, so a file there is active by itself. `GlobalLootModifierProvider.add(name, modifier, conditions...)` writes `data/<modid>/loot_modifiers/<name>.json` (`{"type": ..., "conditions": []}`). The codec goes into `NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS` (a `RegisterEvent` handler works); a `LootModifier` subclass builds it with `RecordCodecBuilder.mapCodec(i -> codecStart(i).apply(i, ::new))`.
- `LootContext.getQueriedLootTableId()` (NeoForge) names the table being rolled, so one modifier can match every `minecraft:chests/...` table by path. `LootTable.getRandomItems(LootParams)` runs `CommonHooks.modifyLoot`, so a GameTest sees the modifiers: `server.reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON).getRandomItems(new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, vec).create(LootContextParamSets.CHEST))`.
- `AdvancementEvent.AdvancementEarnEvent` (game bus) gives `getEntity()` and `getAdvancement()` (an `AdvancementHolder`: `id()`, `value().display()`); recipe-unlock advancements have no display, so `display().isPresent()` keeps them out.
- A datapack registry of our own: `DataPackRegistryEvent.NewRegistry#dataPackRegistry(key, codec, codec)` with the same codec as network codec syncs it to clients; entries load from `data/<ns>/<registry ns>/<registry path>/*.json` in mod and gametest resources alike. `Registry.entrySet()` is a `Set<Map.Entry<ResourceKey<T>, T>>`. A second datagen bootstrap goes on the same `RegistrySetBuilder` with `.add(key, bootstrap)` before the one `createDatapackRegistryObjects` call. An integrated server and its client hold different registry instances, so a cache of anything compiled from one is keyed by the instance (`WeakHashMap`).
- DFU 9.0.19 has `Codec.partialDispatch(typeKey, type -> DataResult, key -> DataResult<MapCodec>)`; a `StringRepresentable.fromEnum` codec plus plain `dispatch` gives the same error for an unknown type without it.
- `ItemStack.consume(1, player)` left the stack whole for a `ServerPlayer` built in a GameTest (`hasInfiniteMaterials()` was true there), as for creative players; tests branch on `player.hasInfiniteMaterials()`.
- `ServerPlayer.sendSystemMessage(component, true)` needs `connection`, which is null for a GameTest `ServerPlayer`; code that may run for such players checks `connection != null` first.
- Error Prone's `ComplexBooleanConstant` fails `a && false && b` (as when breaking a test on purpose); use a non-constant such as `Boolean.getBoolean("never")`. `Item.builtInRegistryHolder()` compiles with a deprecation note only.
- Research screen (M6b area C, compiled against 26.1.2.109; not yet seen in a running client): `Font.plainSubstrByWidth(String, int)` trims a label to a width; Error Prone's `NarrowCalculation` fails `long / 100_000 / 10.0` (integer division before the double), so format with `String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)`. A slotless menu's `broadcastChanges()` is the place to push a payload only on change: a GameTest builds the server menu directly with a collecting sender and calls `broadcastChanges()` and `clickMenuButton(player, id)` itself (`research_menu_syncs_and_acts`).

## Research sources: villager trades, item cooldowns, test players (M6b area E; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources, by `:storage:neoforge:runData` and the `source_*` GameTests)

- 26.1 has **no** villager trades event (no `VillagerTradesEvent` in NeoForge `26.1.x`): trades are data. A `minecraft:villager_trade` datapack registry entry (`data/<ns>/villager_trade/<path>.json`: `wants` `{id, count}`, `gives` item template, `max_uses`, `xp`, `reputation_discount`, ...) is picked by a `minecraft:trade_set` (`data/minecraft/trade_set/librarian/level_3.json`: `"amount": 2.0`, `"trades": "#minecraft:librarian/level_3"`) from its tag. Adding a trade is an entry plus a non-replacing tag: datagen `RegistrySetBuilder.add(Registries.VILLAGER_TRADE, ctx -> ctx.register(key, new VillagerTrade(new TradeCost(Items.EMERALD, 5), new ItemStackTemplate(item), maxUses, xp, discount, Optional.empty(), List.of())))` and a `KeyTagProvider<VillagerTrade>` (`super(output, Registries.VILLAGER_TRADE, lookup, modId)`, `tag(VillagerTradeTags.LIBRARIAN_LEVEL_3).add(key)`) created after `createDatapackRegistryObjects`, so its lookup holds the new entry. The set draws `amount` trades from the tag, so an added trade shows on some villagers only.
- Reading a trade in a GameTest: `lookupOrThrow(Registries.TRADE_SET).getOrThrow(TradeSets.LIBRARIAN_LEVEL_3).value().getTrades()` (a `HolderSet`, `contains(holder)`); `trade.value().getOffer(new LootContext.Builder(new LootParams.Builder(level).withParameter(ORIGIN, pos).withParameter(THIS_ENTITY, villager).withParameter(ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE).create(LootContextParamSets.VILLAGER_TRADE)).create(Optional.empty()))` gives the `MerchantOffer` (`getResult()`, `getCostA()`).
- Two `GlobalLootModifierProvider`s of one mod share the name `Global Loot Modifiers : <modid>`, and `DataGenerator` throws "Duplicate provider"; a second one overrides `getName()`.
- A server-side item cooldown (`player.getCooldowns().addCooldown` on a `ServerPlayer`) sends a packet at once, so it throws for a GameTest `ServerPlayer` built without a connection. NeoForge's `new FakePlayer(level, profile)` (`common.util.FakePlayer`, a `ServerPlayer`) has a connection that drops every packet and a no-op `sendSystemMessage(component, overlay)`; GameTests that drive cooldowns use it.
- `Player.hasInfiniteMaterials()` is `getAbilities().instabuild`, which a GameTest `ServerPlayer` has; setting it false makes `ItemStack.consume(1, player)` take the item.
- `ItemStack.use(...)` returns `InteractionResult.Success` carrying an item context, so it does not equal `InteractionResult.SUCCESS`; test with `consumesAction()`. `FAIL` and `PASS` compare equal.
- `GameTestHelper.assertValueEqual(value, expected, name)` reports "Expected <name> to be <value>: was <expected>", i.e. the two numbers the other way round from what the words say.
- Listing recipes in a GameTest: `level.recipeAccess().recipeMap().values()` gives `RecipeHolder<?>`; `holder.id().identifier()` is the recipe's `Identifier`. A block's FE in a GameTest: `level.getCapability(Capabilities.Energy.BLOCK, pos, side)` returns a nullable `EnergyHandler` (NullAway wants a null check).

# Materials, worldgen, machines and energy

## Storage ores and materials (M6a area 5; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources, by `:storage:neoforge:runData` and the `material_*` and `worldgen_*` GameTests)

- `DropExperienceBlock(IntProvider xpRange, BlockBehaviour.Properties)` is the ore block class (vanilla's quartz and redstone ores use it); `UniformInt.of(min, max)` gives the range.
- Ore loot with fortune and silk touch needs the enchantment registry, so it cannot be a `BlockLoot.table` supplier in common: a `BlockLootSubProvider` passed to `ContentDatagen.Providers.loot(new LootTableProvider.SubProviderEntry(MyLoot::new, LootContextParamSets.BLOCK))` gets the registries, and `createOreDrop(block, item)` writes vanilla's alternatives (silk touch drops the block, else the item with `apply_bonus` `ore_drops` and `explosion_decay`). Its `getKnownBlocks()` must list exactly the blocks it writes, or generation throws for a missing table.
- `Block.getDrops(state, ServerLevel, pos, @Nullable BlockEntity, @Nullable Entity, ItemStack tool)` runs a block's loot table with a tool in a GameTest without breaking anything.
- A plain ore biome modifier is NeoForge's stock `BiomeModifiers.AddFeaturesBiomeModifier(HolderSet<Biome>, HolderSet<PlacedFeature>, GenerationStep.Decoration)`; with `context.lookup(Registries.BIOME).getOrThrow(BiomeTags.IS_OVERWORLD)` it writes `{"type": "neoforge:add_features", "biomes": "#minecraft:is_overworld", "features": "<id>", "step": "underground_ores"}`. `Biomes.NETHER_WASTES` and `THE_END` are not in `#minecraft:is_overworld`; `DEEP_DARK` and `DESERT` are.
- A config-driven ore: a `Feature<NoneFeatureConfiguration>` whose placed feature has no placement modifiers runs once per chunk at the chunk's bottom corner and can call `Feature.ORE.place(new OreConfiguration(List.of(OreConfiguration.target(new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES), stoneOre), OreConfiguration.target(new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES), deepslateOre)), size), level, context.chunkGenerator(), random, pos)` per vein; `OreConfiguration.size` is a public field. `Feature.place(config, WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)` builds the context itself, so a GameTest can run a feature on the live `ServerLevel` with `level.getChunkSource().getGenerator()` (it writes into the chunk sections, no block updates).
- `CrafterBlock.getPotentialResults(ServerLevel, CraftingInput)` is public and static: the vanilla Crafter's own (cached) crafting recipe lookup, so a GameTest that matches a recipe through it shows the recipe is automatable in the Crafter.

## Storage machines (M6a area 5; verified in the patched 26.1.2.109 and NeoForge `26.1.x` sources, by compiling, `:storage:neoforge:runData` and the `machine_*` GameTests; models and screens not yet seen in a running client)

- Emissive faces: vanilla block model elements take `"light_emission": 0..15` (`CuboidModelElement`), and NeoForge's per-face `"neoforge_data": {"light_emission": n}` (`ExtraFaceData`) is merged in `FaceBakery.bakeQuad` as the maximum of the two; `VertexConsumer.putBlockBakedQuad` lights each vertex with `QuadInstance.getLightCoordsWithEmission(vertex, quad.materialInfo().lightEmission())`, so a face at 15 is full-bright without the block emitting light. In datagen, NeoForge's `FaceBuilder.lightEmission(int)` (`ExtendedModelTemplateBuilder` element faces) writes the per-face form; `ElementBuilder.lightEmission(int)` writes the element-level one. An overlay element with only that face, same bounds as the base cube, draws a glowing animated front (the texture's `.png.mcmeta` `animation` block animates it; no block entity renderer).
- A boolean block state switching models: `BooleanProperty.create("active")`, added in `createBlockStateDefinition` next to `BlockStateProperties.HORIZONTAL_FACING`; datagen `MultiVariantGenerator.dispatch(block).with(BlockModelGenerators.createBooleanModelDispatch(property, onVariant, offVariant)).with(BlockModelGenerators.ROTATION_HORIZONTAL_FACING)` (both public static, as vanilla's furnace uses) writes the eight `active=...,facing=...` variants. A second model for the same block: `template.create(ModelLocationUtils.getModelLocation(block, "_active"), textures, modelOutput)`. `TextureMapping.copyAndUpdate(slot, material)` returns a copy with one more slot.
- Flipping such a property: `level.setBlock(pos, state.setValue(ACTIVE, active), Block.UPDATE_CLIENTS)` (flag 2) keeps the block entity, sends the change to clients and notifies no neighbours, so it does not wake the machine's own `neighborChanged`; do it only when the value flips.
- Recipe types of a mod: `RecipeType.simple(Identifier)` in `Registries.RECIPE_TYPE`, `new RecipeSerializer<>(MapCodec, StreamCodec)` in `Registries.RECIPE_SERIALIZER`, `new RecipeBookCategory()` in `Registries.RECIPE_BOOK_CATEGORY`, all through ordinary registrars. One recipe class can serve several types: build a `MapCodec` per type that injects the type into the record, and pick `getType`/`getSerializer` from that field. `MapCodec.validate(Function<A, DataResult<A>>)` refuses a recipe that does not fit its machine, so the bad file is logged and skipped. `ServerLevel.recipeAccess().getRecipeFor(type, input, level)` returns the first `RecipeHolder` whose `matches` holds, and `recipeAccess().recipeMap().byType(type)` lists a type's loaded recipes. A `RecipeInput` is any class with `getItem(int)` and `size()`, so a block entity can hand out one live view.
- Fuel: `FuelValues.burnDuration(ItemStack)` is `@Deprecated` in NeoForge ("use `ItemStack#getBurnTime(RecipeType, FuelValues)`"), the patched method that also reads the `neoforge:furnace_fuels` data map and item overrides; `level.fuelValues()` gives the `FuelValues`. `ItemStack.getCraftingRemainder()` (NeoForge) returns a `@Nullable ItemStackTemplate`; read it before shrinking the stack, as the furnace does (a lava bucket leaves a bucket).
- Vanilla hoppers feed and drain blocks through the item capability (`HopperBlockEntity.getContainerOrHandlerAt`), so a block entity whose `Capabilities.Item.BLOCK` handler reports zero capacity on output indices and refuses extraction from input indices is hopper-friendly on every side. `AbstractContainerMenu.addStandardInventorySlots(inventory, left, top)` adds the player's 27 + 9 slots with the hotbar 58 below `top`.
- Lib's `TaskGroup` stops a pass at its time budget (`scheduler.ownerBudgetMicros`) and keeps the rest queued for the next tick, so when other tests' machines share the group a task can still be queued a few ticks after the event that woke it; GameTests check "asleep" with `thenWaitUntil`, not after a fixed idle.
- Error Prone (as configured) fails the build on a non-`ImmutableList` `List` field in an enum (`ImmutableEnumChecker`), on `Enum.ordinal()` (`EnumOrdinal`; send an id or compute the index yourself), on `@param` tags in an enum's Javadoc (`InvalidParam`) and on an unused local (`UnusedVariable`).

## Storage tier-2 materials and machine modules (M6b area A; verified by compiling against 26.1.2.109, `:storage:neoforge:runData` and the `worldgen_tantalite_*`, `material_*` and `machine_*` GameTests)

- Error Prone's `ImmutableEnumChecker` also fails an enum field typed `java.util.Set` (even holding `Set.of(...)`) or `IntSupplier`; an `ImmutableSet` field built with `Sets.immutableEnumSet(...)` passes, and a config value is read in a `switch (this)` method instead of stored (`MachineModule`).
- `ReferenceEquality` fails `holder.value() == item` in GameTest code too; compare with `stack.is(item)` (`output.item().create().is(item)` for an `ItemStackTemplate`).
- A config section can be pushed again from a later static block of the same `PresetConfig` builder (`builder.push("machines")` after an earlier block pushed and popped it); its new values land under the same TOML table and `presets_complete` checks them like the rest.
- Silk touch in a GameTest: `stack.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1)`, then `Block.getDrops(state, level, pos, null, null, stack)` runs the datagen loot table's silk-touch branch (`createOreDrop`).
- `BlockLootSubProvider.createOreDrop(block, item)` works for a raw-material drop (raw tantalite) exactly as for a gem: one item with fortune's ore bonus, the block itself with silk touch.
- A `Feature.ORE` blob with only a `TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES)` target places nothing in a chunk of stone, so a deepslate-only ore needs no height check to stay out of stone (`worldgen_tantalite_deepslate_within_config`).

## Energy cells: pooled FE, item placement, charge states (M6c area C; verified in the patched 26.1.2.109 sources, by `:storage:neoforge:runData` and the `energy_cell_*` and `perf_storage_energy_cells` GameTests; the models not yet seen in a running client)

- Lib's `FeBuffer` changes its amount through `setAmount` only in `insert`/`extract` (via `AbstractFeHandler`) and on rollback (`revertToSnapshot`); `tryConsume(cost, tx)`, `tryConsume(cost)`, `receive` and `load` write the field directly. A subclass that keeps a running total of the amount (Storage's `EnergyCells.Store`) overrides all five, plus `setCapacity`, which the `FeBuffer` constructor already calls (before subclass fields are initialised).
- `BlockItem.place(BlockPlaceContext)` places into the clicked block itself when that block is replaceable (air included), not against it: a GameTest clicking the top of an air block places one block lower. Put a solid block under the target first. `new BlockPlaceContext(level, player, hand, stack, hitResult)` is public; returning `InteractionResult.FAIL` from a `BlockItem.place` override refuses the placement without consuming the item.
- `BlockEntity.collectComponents()` and `applyComponentsFromItemStack(ItemStack)` are public final: a GameTest copies a block entity's data onto a drop and "places" an item's data with them. `Block.getDrops(state, serverLevel, pos, blockEntity)` runs the loot table and the block's `getDrops` override.
- Commands (26.1.2 sources, `network_snapshot_*` GameTests): `ResourceKey.identifier()` gives the key's id; `Identifier.withPrefix(String)` prefixes the path; `SharedSuggestionProvider.suggest(Iterable<String>, SuggestionsBuilder)` serves a `SuggestionProvider`; `CommandSourceStack.getTextName()` names the source (a player's name for a player); `sendSuccess(supplier, true)` also tells other admins.
- A block with a constructor argument besides its properties: `RecordCodecBuilder.mapCodec(i -> i.group(ENUM_CODEC.fieldOf("tier").forGetter(Block::tier), propertiesCodec()).apply(i, Block::new))`, with the enum a `StringRepresentable` and `StringRepresentable.fromEnum(values)` as its codec.
- Block state models by an `IntegerProperty`: `MultiVariantGenerator.dispatch(block).with(PropertyDispatch.initial(property).generate(value -> variants[value]))`, models from `ModelTemplates.CUBE_COLUMN.create(id, TextureMapping.column(sideMaterial, endMaterial), modelOutput)`. The block item's model is written automatically and points at the model at `ModelLocationUtils.getModelLocation(block)`, so the variant the item should show goes there.
- zsh expands `-Pgametest=maxtech_storage:energy_*` as a glob and fails with "no matches found"; quote the argument.

## Nether ores, geodes, budding blocks and dimension type tags (M6d area A; verified in the patched 26.1.2.109 sources, by `runData` and by the `worldgen_*`, `material_data_crystal_*` and `machine_ember_modules` GameTests)

- A Nether ore is a `ConfigOreFeature` target `new BlockMatchTest(Blocks.NETHERRACK)` (vanilla's `OreFeatures` rule), added through a stock `AddFeaturesBiomeModifier` on `BiomeTags.IS_NETHER` in step `UNDERGROUND_DECORATION`; nether quartz ore is `DropExperienceBlock(UniformInt.of(2, 5))`, strength 3, `SoundType.NETHER_ORE`, `MapColor.NETHER`. `Biomes.BASALT_DELTAS` and `CRIMSON_FOREST` are in `#minecraft:is_nether`.
- Geodes: `new GeodeConfiguration(GeodeBlockSettings, GeodeLayerSettings, GeodeCrackSettings, usePotentialPlacementsChance, useAlternateLayer0Chance, placementsRequireLayer0Alternate, outerWallDistance, distributionPoints, pointOffset, minGenOffset, maxGenOffset, noiseMultiplier, invalidBlocksThreshold)`; `GeodeBlockSettings(filling, inner, alternateInner, middle, outer, List<BlockState> innerPlacements, cannotReplace, invalidBlocks)` with `BlockStateProvider.simple(...)`. Vanilla's amethyst numbers are in `CaveFeatures.AMETHYST_GEODE` and its biomes add it in `GenerationStep.Decoration.LOCAL_MODIFICATIONS`. `Feature.GEODE.place(config, level, generator, random, origin)` writes up to 16 blocks around the origin and fails when too many sample points are air.
- Dimension type tags load from data packs (`data/<ns>/tags/dimension_type/<name>.json`, written by a `KeyTagProvider<DimensionType>` on `Registries.DIMENSION_TYPE`), and `WorldGenLevel.getLevel().dimensionTypeRegistration().is(tag)` reads them during generation; `BuiltinDimensionTypes.OVERWORLD` / `NETHER` are the keys. Biome tags the same way with `KeyTagProvider<Biome>` (`data/<ns>/tags/worldgen/biome/`). Use the `KeyTagProvider(output, registry, lookup, modId)` constructor; the three-argument one is deprecated.
- Budding blocks: `BuddingAmethystBlock.canClusterGrowAtState(state)` is public static; `AmethystClusterBlock(height, width, properties)` with `FACING` and `WATERLOGGED` is usable for any bud stage. Random ticks need `Properties.randomTicks()` and are not a ticker. `BlockModelGenerators.createAmethystCluster(block)` (cross model rotated by facing) and `registerSimpleFlatItemModel(block)` (item from the block texture) are public. The cluster's loot is vanilla's `createSilkTouchDispatchTable` with 4 shards, fortune and `MatchTool` on `ItemTags.CLUSTER_MAX_HARVESTABLES`, otherwise 2 with explosion decay; buds use `dropWhenSilkTouch`, the budding block an empty `LootTable.lootTable()`.
- Reading another content class's `static final TagKey` field from an area class loads that class during `ModContent.init()` and moves its entries in the registration and creative tab order; name the tag again locally instead.
- JEI 29.43 (26.1.2): `mezz.jei.api.constants.RecipeTypes` has no `FUELING`; the furnace fuel page is `RecipeTypes.SMELTING_FUEL` (also `BLASTING_FUEL`, `SMOKING_FUEL`, all `IRecipeType<IJeiFuelingRecipe>`), so a machine that burns furnace fuel registers as a crafting station on `SMELTING_FUEL` (JEI common-api sources jar). A plain `RecipeType` registered by our mod still needs `OnDatapackSyncEvent#sendRecipes` before JEI sees its recipes (Storage's `compat.jei.StorageRecipeSync`).
- A sneaking player holding an item reaches the block's `useItemOn` only if every hand bypasses: NeoForge's `ServerPlayerGameMode.useItemOn` sets `suppressUsingBlock = isSecondaryUseActive() && handsNotEmpty && !(main.doesSneakBypassUse(level, pos, player) && off.doesSneakBypassUse(...))`, and `ItemStack.doesSneakBypassUse` is true for an empty stack. An item that handles sneak-use in the block's `useItemOn` (the P2P linker) must override `Item.doesSneakBypassUse(ItemStack, LevelReader, BlockPos, Player)` to return true (`IItemExtension`, overridable from `common`); otherwise only `useOn` runs. Calling the item's static helper directly in a test misses this: go through `serverPlayer.gameMode.useItemOn(player, level, stack, hand, hitResult)` with a player from `helper.makeMockServerPlayerInLevel()` (deprecated for removal, so `@SuppressWarnings("removal")`; it has a connection, while `new ServerPlayer(...)` without one throws on the block-update ack packet). Verified by `p2p_linker_sneak_real_path`.
- Lib's `NetworkGraph` takes any positions from `NodeConnectivity.connections`, not only neighbours: `connect` links both ends and merges their networks, a far end in an unloaded chunk becomes a cut edge (so `unloadedChunkMode` applies), and `RouteTree` routes over such a link like any other. Storage adds far links through `network.RemoteLinkNode` (`StorageConnectivity` reads them after the six sides; `NetworkState.baseWidth` asks the node for the width of two devices that do not touch). Verified by `signal_antennas_pair_and_link` and `signal_antenna_channels_by_tier` (M6d; channels since M7N).
- A sneaking player holding the P2P linking tool never reaches a block's `useItemOn` (see SKILL.md, interaction): the tool's item `useOn` runs instead, and `P2PLinkerItem` does not override it or `doesSneakBypassUse`. Antenna pairing therefore needs no sneaking (use picks, use on a second one pairs). The P2P linker's own sneak-to-make-input step goes through the same path; its GameTests call `P2PLinkerItem.use` directly, so a running client has not shown it working (found in M6d, not changed there).
- `FeaturePlaceContext(Optional<ConfiguredFeature<?, ?>> topFeature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, FC config)` lets a GameTest call a feature's `place` directly with `level.getChunkSource().getGenerator()` (verified in the patched sources and by `signal_modules_off`).
- `GlobalPos` (with `CODEC` and `STREAM_CODEC`) is a fine data component for "a block in some dimension": the antenna pick of the linking tool and the wireless terminal's binding use it.

## End geodes and machine parallel runs (M6f area A; verified by `runData` and the `worldgen_void_geode_*`, `material_void_*` and `machine_void_*` GameTests)

- The outer End biomes are `Biomes.END_HIGHLANDS`, `END_MIDLANDS`, `SMALL_END_ISLANDS` and `END_BARRENS`; `Biomes.THE_END` is the main island. `BuiltinDimensionTypes.END` is the End's dimension type key. Inside `StorageWorldgenTags`, the inner `KeyTagProvider<Biome>` class named `Biomes` shadows `net.minecraft.world.level.biome.Biomes`, so name the vanilla class in full there.
- A feature that needs an island's surface reads `WorldGenLevel.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)` (the generation heightmap kept current while features run); `SurfaceGeodeFeature.placeUnder` takes the surface as a parameter so a GameTest can place without the heightmap (compiled; not yet seen in a generated End).
- Vanilla's geode feature refuses a spot where too many distribution points are air, so a geode placed in an air column fails (`worldgen_void_geode_under_surface`).
- Breaking a test by leaving a field unread fails the build: Error Prone's `UnusedVariable` is an error under `-Werror`; break the logic another way.

## Storage on MaxTech energy (MLE item 4; verified in the patched 26.1.2.109 and NeoForge 26.1.2.109 sources and by the `mu_*` GameTests)

- `ItemAccessEnergyHandler.getAmountFrom(ItemResource)` and `update(ItemResource, int)` are protected, as are `validItem` and `energyComponent`: a subclass can convert the stored number on read (an item saved in FE read as MU) and set a marker component on write (`ItemResource.with(type, value)` twice); `ItemResource.getOrDefault(type, default)` reads the marker.
- `ChunkMap.save(ChunkAccess)` returns at once when `chunk.tryMarkSaved()` is false (the chunk is clean), also on unload, so a block entity whose saved value changes without a call on it (an energy network's flow into an endpoint-backed buffer, what an FE edge holds) must mark its chunk each tick it changes (`Level.blockEntityChanged(pos)`, which is `getChunkAt(pos).markUnsaved()` when loaded), or the save after the last mark keeps an old value. `ChunkAccess.isUnsaved()` and `tryMarkSaved()` are public, so a GameTest can clear the mark and look again.
- `GameTestHelper.assertValueEqual(N value, N expected, String name)` reports "Expected <name> to be <value>: was <expected>" (`test.error.value_not_equal` with value first): the message reads the right way round only with the expected value first, which some Storage tests do on purpose.
- Error Prone rejects `if (true)` and `false && ...` (`ComplexBooleanConstant`) even for a deliberate break while checking that a test can fail; `Boolean.getBoolean("some.property")` is false at run time without a constant and compiles.

# Config, servers and multiplayer

## Config step (M6; verified against the 26.1.2.109 sources, JEI 29.43.0.106 sources and the `config_*` GameTests)

- A texture's animation `.mcmeta` is looked up only in the pack that supplies the `.png` and packs above it (`FallbackResourceManager.createStackMetadataFinder` walks from the top down to the supplying pack's index), so a higher pack that ships just a single-frame `.png` makes a mod's animated strip still. Storage's `still_effects` pack does this.
- `AddPackFindersEvent.addPackFinders(id, PackType, title, PackSource, alwaysActive, Pack.Position)` reads the pack from `<namespace's mod jar>/<id path>`; `alwaysActive = true` makes it required, so `PackRepository` selects it without the player. NeoForge loads CLIENT and COMMON configs in `CommonModLoader.begin` ("Config loading"), before `ClientModLoader.setupModResourcePacks` posts the event, so a client config value can decide whether to add the pack.
- An in-memory pack (verified by Lib's `ConduitAssetsPack`, 26.1.2): `event.addRepositorySource(c -> c.accept(new Pack(new PackLocationInfo(id, title, PackSource.BUILT_IN, Optional.empty()), supplier, new Pack.Metadata(title, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true), new PackSelectionConfig(true, Pack.Position.BOTTOM, false))))` with a `PackResources` extending `AbstractPackResources` (null root resource means empty metadata). NeoForge's `mod_resources` pack is required at `Position.TOP` (`ResourcePackLoader`), so a `BOTTOM` pack sits below every mod's own assets and they win.
- `LootTable` missing: `server.reloadableRegistries().getLootTable(key)` returns the `LootTable.EMPTY` instance (identity check) when the data has no table for the key.
- Removing a config key is safe for old files: on load `ModConfigSpec.correct` drops every entry the spec does not define (its second step, `CorrectionAction.REMOVE`) and saves the corrected file, so a leftover key such as `visuals.animatedCables` neither crashes nor survives (verified in the 26.1.2.109 sources).
- Resource pack format in 26.1.2: `SharedConstants.RESOURCE_PACK_FORMAT_MAJOR = 84`; `pack.mcmeta` takes `min_format` and `max_format` (or `pack_format`).
- Datapack-registry tags in datagen: `net.minecraft.data.tags.KeyTagProvider<T>(output, Registries.X, lookup, modId)` with `tag(key).addTag(...)`/`.add(ResourceKey...)`; works for `Registries.BIOME` and `Registries.DIMENSION_TYPE`. A biome modifier can name the mod's own biome tag through `context.lookup(Registries.BIOME).getOrThrow(tag)`; it is written as `"#mod:tag"`.
- In a feature, `WorldGenLevel.getLevel().dimensionTypeRegistration().is(TagKey<DimensionType>)` checks the dimension; there are no tags on `Level` keys themselves.
- JEI: `IJeiRuntime.getIngredientFilter()` gives `getFilterText()`/`setFilterText(String)`; `IModPlugin.onRuntimeUnavailable()` is where to drop the reference.
- Error Prone (as configured) fails `StaticAssignmentInConstructor`: set a screen's session-wide static state from `init()` or a static helper, not its constructor.

## Usage report book and the security screen (M6e area F; verified in the patched 26.1.2.109 sources and by the `server_*` GameTests)

- Player names: `MinecraftServer.services().nameToIdCache()` is a `UserNameToIdResolver` (`net.minecraft.server.players`) with `get(String name)` and `get(UUID id)`, both `Optional<NameAndId>` (`NameAndId(UUID id, String name)` record). `get(UUID)` reads the cache only; `get(String)` asks Mojang's `GameProfileRepository.findProfileByName` for a name it has not cached, which blocks, so run it on `Util.nonCriticalIoPool()` (`net.minecraft.util.Util`) and apply the answer on the server (`whenCompleteAsync(..., server)`). The cache's maps are `ConcurrentHashMap`s. `PlayerList.getPlayerByName(name)` and `getPlayer(uuid)` find online players; `StringUtil.isValidPlayerName(name)` checks a name.
- Written books: `WrittenBookContent(Filterable<String> title, String author, int generation, List<Filterable<Component>> pages, boolean resolved)`, `Filterable.passThrough(value)` (`net.minecraft.server.network`). `ItemStack.getCustomName()` uses the book's title only when it is not blank, and `Item.getName(stack)` reads `DataComponents.ITEM_NAME`, so a book with a blank title and an `ITEM_NAME` of `Component.translatable(...)` shows a translated name. Pages may be translatable components; `resolved = true` skips the server's component resolution.
- Error Prone: `FutureReturnValueIgnored` fails a discarded `CompletableFuture`; assigning it to `_` (Java 25 unnamed variable) is what `UnnamedVariable` asks for.

## Quarantine tag (M6e area F; verified by `server_quarantine_freeze`)

- GameTests: `ServerPlayer.sendOverlayMessage` (and `sendSystemMessage`) on a `ServerPlayer` built with `new ServerPlayer(server, level, profile, ClientInformation.createDefault())` throws an NPE, since its `connection` is null; use a NeoForge `FakePlayer` (it overrides the send methods to do nothing) for any code path that messages the player.

## Request board, mail port and ledger (M6e area G; verified by compiling against 26.1.2 and the `services_*` GameTests)

- `Level.random` is protected in 26.1.2; GameTest code uses `helper.getLevel().getRandom()`.
- `PlayerList.getPlayerByName(String)` (online players only, `@Nullable`) and `PlayerList.getPlayer(UUID)` resolve an address; `player.getGameProfile().name()` is the name (`GameProfile` is a record). A GameTest `new ServerPlayer(...)` is in neither, so tests address it through the mod's own name table.
- `ByteBufCodecs.stringUtf8(max)` refuses longer strings while decoding, so a C2S payload with a name field caps it in the codec; `ItemStack.STREAM_CODEC` and `ByteBufCodecs.list(max)` carry a list of stacks to the client.
- `SavedData` codecs may hold `ItemStack.CODEC.listOf()`: the storage encodes with a registry-aware `NbtOps` context (see "Saving saved data" above), and `ItemStack.CODEC` refuses empty stacks, so the inbox never keeps one.
- `AbstractContainerMenu.addStandardInventorySlots(inventory, x, y)` adds the 27 + 9 player slots (hotbar 58 px below `y`); `clearContainer(player, container)` from `removed` gives a screen-owned container back like a crafting table's grid.
- The network's `ContentsWatcher` sees providers joining and leaving (index adds and removes) the same as stored items; `NetworkState.join`/`leave` fire `watchDevices` runnables after those changes in the same call, so a watcher that drops what it saw in the current tick on a device change keeps only real moves (the ledger's approach; a transfer in that same tick is lost too).

## Moved config keys and renamed research nodes (M7R item 8; verified 2026-10-16 in Lib's `SaveFormat`, `PresetSync` and `PresetApplier`, NeoForge 26.1.2.109's `ModConfigSpec.correct` ("removes the unspecified values") and by the `config_moved_key_*` GameTests and `ResearchRenamesTest`)

- `SaveFormat.codec` versions only map-shaped values. `ResearchData`'s codec is a list of teams, so `research.dat`
  carries no `data_version` and a `SAVED_DATA` migration step never runs for it. Renamed research nodes are mapped
  where a team decodes (`ResearchTeam`, `ResearchRenames`), on every load; the mapping must stay idempotent.
- A config key that moves can stay readable for a version by keeping it in the spec with a default of -1 (unset) in
  both presets. `PresetSync` resets an old key a server never edited (its value still equals what the preset last
  wrote) to the preset's -1, and keeps one the server edited, so `MovedKeys` applies only edited old values. A key not
  in the spec would be dropped from the file by NeoForge's correction, value and all.
- `StorageTestEnvironment` sets options through their `ModConfigSpec.ConfigValue`; a value read through `MovedKeys`
  is a lambda, so tests set the raw `*_KEY` value (or the old key through `StorageServerConfig.deprecatedValue`).
- A controller network has no device limit since M7R item 1 (`deviceLimit()` is `Integer.MAX_VALUE`), so
  `NetworkInfo`'s device-limit line skipped controller networks and Jade showed no root links; the `root_links` line
  covers them.

# Other mods

## KubeJS schemas (M6 recipe compat; by compiling and the `kubejs_schemas_cover_recipe_types` GameTest)
- Storage's machine recipe types (`maxtech_storage:lithograph`, `fabricator`, `assembler`) get one schema each from `compat.kubejs.StorageKubeJSPlugin`, built from Lib's `MachineRecipeKeys`; the fuel generator has no recipe type (vanilla fuel). `StorageRecipeSchemas` lists the ids without KubeJS imports, so the GameTest checks it against `BuiltInRegistries.RECIPE_TYPE` without KubeJS in the run.
- Dev flag: `-PwithKubeJS=true` and `-PwithKubeJs=true` both add KubeJS to dev runs (build.gradle.kts reads either). Example script: `docs/compat/kubejs.md`. `ComponentCopyingRecipe` is a special shapeless recipe and has no schema.

## FTB Chunks in the dev runs (claim tests; verified with `:storage:neoforge:dependencies` and `runServer -PwithFtbChunks=true` on 2026-10-07)

- FTB Chunks, FTB Library, FTB Teams and FTB Backups 3 are All Rights Reserved (`license` in their mods.toml). They come only from `https://maven.ftb.dev/releases` (exclusiveContent for `dev.ftb.mods` in `settings.gradle.kts`) and are never bundled, shaded, copied, committed, published or run in CI.
- `dev.ftb.mods:ftb-chunks-neoforge:26.1.2.8` pulls through its POM `ftb-chunks` (common), `ftb-library-neoforge` (and `ftb-library`, `de.marhali:json5-java:3.0.0`, which FTB Library also carries as jar-in-jar), `ftb-teams-neoforge` (and `ftb-teams`) and `ftb-backups-3:26.1.2.1`. FTB Backups is optional for FTB Chunks and is excluded here. Nothing else is needed at runtime (no Architectury). The plain `ftb-chunks`/`ftb-library`/`ftb-teams` artifacts sit on the classpath next to the neoforge mod jars without a conflict; FML lists only the four mods.
- `storage/neoforge` adds them as `runtimeOnly` only when the Gradle property `withFtbChunks` is `true` (never for GameTest or runData tasks), so they join that project's client, server and GameTest runs only with `-PwithFtbChunks=true` (MDG has no per-run classpath for 26.1, see `SKILL.md`). Without the property the runtime classpath has no FTB artifact. `runtimeOnly` in a `neoforge` project reaches neither the jar nor a POM.
- How to test claims: `./gradlew :storage:neoforge:runClient -PwithFtbChunks=true`, claim chunks with FTB Chunks' map screen, then point a port, sensor or pattern provider owned by a second player (or with `claims.checkUnowned`) at a container in the claim; it shows "Blocked by a land claim" in Jade and its screen. The server reached "Done" with FTB Chunks 26.1.2.8, FTB Library 26.1.2.9 and FTB Teams 26.1.2.4 loaded beside Storage and Lib.

## Curios and carried gadgets (2026-10-09; verified in the Curios 15.0.0+26.1.2 sources jar, by `runData`, the `carried_*` GameTests and `curios_gadgets_in_slots` with `-PwithCurios=true`)

- Curios for 26.1.2 is `maven.modrinth:curios:15.0.0+26.1.2` (Modrinth's maven, already allowed for `maven.modrinth`
  in `settings.gradle.kts`); LGPL-3.0-or-later, mod id `curios`, needs NeoForge `[26.1.0.0,)`. Its `-api` and
  `-sources` jars are separate Modrinth files, not maven classifiers.
- Curios 15 ships the preset slot types (`data/curios/curios/slots/{back,belt,body,bracelet,charm,curio,feet,hands,
  head,necklace,ring}.json`, validator `curios:tag`) but gives no entity any of them. A mod asks with
  `data/<ns>/curios/entities/<name>.json`: `{"entities": ["minecraft:player"], "slots": ["belt", "charm"]}` (`replace`
  optional, false by default; entries may be `#tag`s). Items join a slot through the item tag `curios:<slot>`
  (`CuriosTags.BELT`, `CHARM`, ...). `CuriosSlotTypes.getItemSlotTypes(stack, entity)` lists the slots an item fits;
  the server log says `Loaded N curio entities`.
- `CuriosCapability.INVENTORY` is an `EntityCapability<ICuriosItemHandler, Void>`; NullAway rejects
  `entity.getCapability(it)` (NeoForge's no-context overload wants `@Nullable Void`), so the call carries a one-line
  `@SuppressWarnings("NullAway")`. The provider builds a new `CurioInventoryCapability` per lookup (over the entity's
  `curios:inventory` attachment) and returns null for an entity type without slots. The lookup is cheap (capability
  dispatch, two map gets for the entity type's slot types, one two-field wrapper), so `compat.curios.CuriosCarriedSource`
  looks it up per call. Don't cache the handler in a `WeakHashMap` keyed by the player: the wrapper holds the entity
  strongly, so the weak key never clears.
- `ICuriosItemHandler.getCurios()` is the live `LinkedHashMap<String, ICurioStacksHandler>` by slot id; `getStacks()`
  (`IDynamicStackHandler`, an `IItemHandlerModifiable`) and `getActiveStates()` (`NonNullList<Boolean>`) return fields,
  so `map.get(id)` plus `getStackInSlot(i)` allocate nothing. `findFirstCurio`/`findCurios` take a `Predicate` and build
  `Optional<SlotResult>`s and cache entries: not for hot paths. `setEquippedCurio(id, index, stack)` equips; `reset()`
  builds the default slots.
- Curios compares each slot's stack with the previous one every tick (`ItemStack.matches`) and syncs changes, so code
  that changes a carried stack's components in place needs no extra sync.
- With Curios loaded, a GameTest mock player from `helper.makeMockServerPlayerInLevel()` fails its test (`Payload
  curios:sync_data may not be sent to the client!`, from Curios' datapack sync on join), as with KubeJS. A plain
  `new ServerPlayer(...)` that never joins works after `handler.reset()`. So Curios runs only in the `curios_*`
  GameTests with `-PwithCurios=true`, never the whole suite or CI.
- NeoForge's `FakePlayer.openMenu` returns empty and opens nothing, and its `FakePlayerNetHandler` swallows packets, so
  key-open paths can run with a `FakePlayer` (nothing opens; a portable cell mounts and is let go again).
- `org.jetbrains.annotations.ApiStatus` (`@ApiStatus.Experimental`) is on Lib's api compile classpath through the
  Minecraft/NeoForge dependencies; `lib.api.carried` uses it to mark api that may change before Lib 1.0. It also
  compiles on a `package-info.java` (the annotation allows `PACKAGE`), on Storage's api source set too; Lib's guide
  and carried packages and `storage.api.crafting` carry it there since Lib 0.9.0 / Storage 0.5.0.
- A one-minor dependency range like `versionRange = "[0.9,0.10)"` in `neoforge.mods.toml` loads Lib 0.9.0: FML
  compares versions numerically per component (Maven `ComparableVersion`), so `0.10` sorts after `0.9`. Verified by the
  Storage GameTest server starting with Lib 0.9.0 on 2026-10-15.
- A key mapping with no default key: `new KeyMapping(name, InputConstants.UNKNOWN.getValue(), category)` (`UNKNOWN` is
  keysym -1). `KeyConflictContext.IN_GAME` is active while no screen is open.

## REI and EMI on 26.1.2 (M7C item 25; verified 2026-10-10 against the Modrinth API, shedaniel's maven and the 26.1.819 sources jars)

- EMI: no 26.1.2 build. Modrinth lists NeoForge releases only up to 1.21.1 and nothing for game version 26.1 or 26.1.2 on any loader. No `compat.emi` until one appears.
- REI 26.1.819 (also .818) for 26.1.2 NeoForge: maven `https://maven.shedaniel.me`, `me.shedaniel:RoughlyEnoughItems-neoforge:26.1.819` (fat jar: API, default plugin and the `me.shedaniel.rei.forge` annotations; `-sources` exists). Its POM brings `dev.architectury:architectury-neoforge:20.0.6` (maven.architectury.dev, also on shedaniel's maven) and `me.shedaniel.cloth:cloth-config-neoforge:26.1.154`; both are `required` in its mods.toml, so a dev run needs them. `me.shedaniel.math.Point/Rectangle` live in `me.shedaniel.cloth:basic-math:0.6.1`, which the POM does not pull in: add it for compiling.
- Plugin discovery on NeoForge: annotate a class implementing `REIClientPlugin` with `me.shedaniel.rei.forge.REIPluginClient` (scanned from mod annotation data; the class loads only when REI does). The log line "Registered plugin provider X [modid] for REIClientPlugin" confirms it.
- `REIClientPlugin` hooks used: `registerCategories(CategoryRegistry)`, `registerDisplays(DisplayRegistry)`, `registerTransferHandlers(TransferHandlerRegistry)`, `registerExclusionZones(ExclusionZones)` (`register(Class, provider)`; every provider whose class `isAssignableFrom` the screen contributes), `registerBasicEntryFiltering(BasicFilteringRule)` (`rule.hide(Supplier<Collection<EntryStack<?>>>)`), `postStage(PluginManager, ReloadStage)`. `registry.addWorkstations(id, stacks)` works before the category exists (REI queues it), so `BuiltinPlugin.FUEL` can be configured from our plugin.
- A custom `Display` needs `getInputEntries`, `getOutputEntries`, `getCategoryIdentifier`, `getDisplayLocation` and `getSerializer` (null is allowed for client-built displays). `DisplayCategory`: `getCategoryIdentifier`, `getTitle`, `getIcon` (an `EntryStack` is a `Renderer`), `getDisplayWidth/Height`, `setupDisplay(display, bounds)`. `Widgets.createSlot(Point)` takes the 16x16 item position, the same as JEI's slot x,y.
- `TransferHandler`: handlers run highest `getPriority()` first. `Context.isActuallyCrafting()` is false while REI only evaluates the button (every frame it draws or hovers) and true on the click. REI enables the click only for a successful result (`Result.createSuccessful()`); a failed one is a disabled, tinted button. `blocksFurtherHandling(true)` ends the chain and returns to the container screen. `Result.tooltipMissing(List<EntryIngredient>)` shows the missing items. `CraftingDisplay.getOrganisedInputEntries(3, 3)` gives the nine grid cells.
- REI reloads its plugins on Architectury's `ClientRecipeUpdateEvent`, which fires from NeoForge's `RecipesReceivedEvent`; Storage's `ClientStorageRecipes` listens at `EventPriority.HIGHEST` so its copy is current by then. REI has no tag on an `EntryIngredient`; `EntryStack.getTagsFor()` lists a stack's tags.

## Network bridge seam (M7b item 1; verified 2026-10-10 by the `bridge_*` GameTests)

- `StorageIndex.remove` subtracts what the index recorded for the provider's entry, not what the provider reports now,
  and `add` re-reads `forEachStored`. A provider whose whole view changes (a bridge mounting or unmounting a foreign
  network) therefore calls `providersChanged()` on its device and never tries to express the swap as deltas.
- Providers that are `NetworkView`s are left out of `NetworkState.nativeAmount`/`forEachNative`/`nativeInsert`/
  `nativeExtract`; an export built on the native reads (`ExportedStorage`) never shows bridges or subnet mounts.
- A foreign API without transactions (simulate/modulate) is bridged by doing the irreversible half where a shortfall can
  only leave us holding more than promised: real extract when asked (rollback puts it back), real insert at root commit
  (shortfall kept in the bridge's held store). A take done at commit could fall short after the items were handed out.
- Storage's `compileJava` runs Error Prone with `-Werror`: a warning such as `ReferenceEquality` or
  `OperatorPrecedence` fails the build like an error.
- A `StorageTask`'s returned delay is cut short by any `wake` on its device (`BridgeLinks` wakes every bridge when one
  attaches); work that must wait a fixed time (a poll) keeps its own due game time and re-checks it.
- A device always leaves its old `NetworkState` before it joins another (`NetworkState.join` calls `previous.leave`,
  which calls `networkChanged(null)`), so a bridge unmounts before its network object changes; devices joining or
  leaving the bridge's network reach `ExportedStorage`'s listener as native deltas (`rs_export_follows_network`).

## Refined Storage 3.2.1 (M7b item 3; verified 2026-10-10 in the refinedstorage2 `v3.2.1` sources, by compiling and by the `rs_*` GameTests with `-PwithRs=true`)

- No public maven: RS publishes to GitHub Packages (needs a token), not to Maven Central. Use Modrinth's maven by
  version id, `maven.modrinth:refined-storage:P12kjgPK` (`refinedstorage-neoforge-3.2.1.jar`; the number `3.2.1` is
  shared with the Fabric file). The jar holds API and implementation, no jar-in-jar dependencies and no sources (read
  the GitHub tag). MIT. Mod id `refinedstorage`; its mods.toml needs NeoForge `[26.1.2.22,)`.
- RS's API types carry apiguardian `@API(status = ...)`; compiling against them without
  `org.apiguardian:apiguardian-api:1.1.2` gives "unknown enum constant" warnings, which `-Werror` turns into errors.
- Reaching a network from a block: `RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability()`
  (`BlockCapability<NetworkNodeContainerProvider, @Nullable Direction>`), `getContainers()`, and
  `container.canAcceptIncomingConnection(sideOfTheRsBlockFacingUs, ourState)` as RS's cables check; then
  `container.getNode().getNetwork()` (nullable). RS gives a node its network in `ServerListener.queue`, run at the start
  of the next server tick (`ServerTickEvent.Pre`), so a node placed or loaded this tick has no network yet.
- A merge or split hands nodes a different `Network` object. `network.getComponent(StorageNetworkComponent.class)` is
  the root storage (`RootStorage`: `getAll`, `get`, `insert`/`extract(resource, amount, Action, Actor)` with
  `Action.SIMULATE`/`EXECUTE`, `addSource`/`removeSource`, `addListener(RootStorageListener)`). `amount` must be above
  0 (`ResourceAmount.validate`).
- The root's `getAll` is a cached list: `CompositeStorageImpl.addSource` copies `source.getAll()` into it and
  `removeSource` subtracts `source.getAll()`; later changes reach it only through the composite's own moves or a
  `CompositeAwareChild` source calling `ParentComposite.addToCache`/`removeFromCache` (RS's `ExternalStorage` does that
  after polling). A source whose contents change behind RS's back must push its changes, and its `getAll` must report
  exactly what it pushed, or `removeSource` leaves ghosts. `compositeInsert`/`compositeExtract` return
  `Amount(moved, amountForList)`; `amountForList` is 0 when the source pushes the change itself.
- Sources sort by `PriorityProvider` (`PriorityStorage.of(storage, insertPriority, extractPriority)`), higher first,
  ties in the order added; a plain `Storage` has priority 0.
- Item and fluid resources are `common.support.resource.ItemResource(item, DataComponentPatch)` and
  `FluidResource(fluid, patch)`, marked `@API(status = INTERNAL)` but the only ones RS has. Fluids count in mB on
  NeoForge (`Platform.getBucketAmount()` is `FluidType.BUCKET_VOLUME`). `Actor` is a functional interface (`getName`).
- RS's default cable block is `refinedstorage:cable`; other colours are `refinedstorage:<colour>_cable`.
- RS's resource lists (`MutableResourceListImpl`, behind the root's cache) are a `HashMap` keyed by the resource, and
  `ItemResource`/`FluidResource` override `equals`/`hashCode`, so a source may drop its resource object once the kind
  is gone and make a new one later: RS finds the same entry (3.2.1 bytecode, `rs_export_resources_shrink`).
- Merges and splits (3.2.1 `NetworkBuilderImpl` bytecode, `rs_merge_split`): containers are ordered by
  `ConnectionProviderImpl.sortDeterministically`, i.e. by `getLocalPosition()` (`BlockPos` order: y, then z, then x).
  A merge keeps the network of the first container in that order that has one and moves the others' containers into it
  (`removeContainer` from the old, `addContainer` to the kept one); a split keeps the network object on the side of the
  first remaining container and gives every container no longer reachable from it a new network. Sources added to a
  root storage by code stay with that root object, so they leave the nodes that move.

## Applied Energistics 2 (M7b item 2; verified 2026-10-10 in the AE2 26.1.13-beta sources jar and by the `ae2_bridge_*` GameTests with `-PwithAe2=true`)

- AE2 publishes to Maven Central: `org.appliedenergistics:appliedenergistics2:<ver>` (main jar, `api` and `sources`
  classifiers); modmaven stops at 19.2.0-beta. 26.1.13-beta needs NeoForge `[26.1.2.21-beta,)`, Minecraft `[26.1.2]`,
  GuideME `[26.1.14-beta,)` (a runtime dependency in AE2's POM, so `runtimeOnly(ae2)` brings it) and JEI
  `[29.5.0.26,)` if JEI is present.
- Storage API: `MEStorage.insert/extract(AEKey, long, Actionable, IActionSource)` return longs;
  `getAvailableStacks(KeyCounter)` adds into the counter. `IStorageService.getInventory()` is the grid's
  `NetworkStorage`, which refuses reentrant calls (`mountsInUse`: insert/extract return 0, listing adds nothing).
  `getCachedInventory()` is rebuilt every server tick while a watcher exists, else lazily on read; it may hold stacks
  read outside a caller's own guard, so read live through `getInventory()` when that matters.
- Inserts go by priority (high first), and within one priority first to storages whose `isPreferredStorageFor` is true
  (a cell already holding the key), then to the rest in mount order. Extracts go lowest priority first.
- `IStorageService.addGlobalStorageProvider(IStorageProvider)` mounts storage without a grid node (no channel, no idle
  power); `mountInventories(IStorageMounts)` calls `mounts.mount(MEStorage[, priority])`. Adding the same provider twice
  throws; `removeGlobalStorageProvider` on an unknown one does nothing. Global providers belong to the grid object and
  go away with it when grids merge or split.
- Finding a grid: `AECapabilities.IN_WORLD_GRID_NODE_HOST` is a `BlockCapability<IInWorldGridNodeHost, Void>` (plain
  `Void`, so NeoForge's no-context `level.getCapability(cap, pos)` needs a NullAway suppression), not proxyable;
  `getGridNode(Direction)` is null on sides without a node. `IGridNode.getGrid()` throws `IllegalStateException` once
  the node was destroyed; a merge or split moves the nodes of one side to another `IGrid` object. `isActive()` =
  powered, booted and channels met: AE2's rule for when a device may do network I/O.
- Which grid object survives (verified 2026-10-15 in `GridConnection.mergeGrids` and `GridNode.validateGrid`, and by
  `ae2_bridge_grid_merge_split`): on a merge the "better" grid wins (higher priority, else more nodes, a tie goes to
  side A) and the other side's nodes move into it; on a split the side that still reaches the grid's pivot (the node
  that created it, unless removed) keeps it, every other side gets `Grid.create`. A lone node merging into a bigger
  grid therefore always moves, and moves again when it splits off. Global providers stay on the old object either way.
- `add/removeGlobalStorageProvider` mount and unmount at once (not at tick end). There is no api to list a grid's
  mounts; a test reads `NetworkStorage.priorityInventory` (private `NavigableMap<Integer, List<MEStorage>>`) by
  reflection.
- Keys: `AEItemKey.of(ItemResource)` / `AEFluidKey.of(FluidResource)` (null for empty) and `toResource()` back; fluids
  count in mB (`AEFluidKey.AMOUNT_BUCKET = 1000`). `IActionSource.empty()` is a source with no player or machine.
- No grid-level storage change event without a node of one's own (`IStorageWatcherNode` is a node service), so a
  nodeless reader polls.
- In GameTests a creative energy cell next to an ME drive boots an ad-hoc grid within a few ticks; cells go in through
  `DriveBlockEntity.getInternalInventory().insertItem(slot, AEItems.ITEM_CELL_1K.stack(), false)`.
- RS power check (3.2.1, from `AbstractBaseNetworkNodeContainerBlockEntity.calculateActive` bytecode): a node is active only if `!RefinedStorageApi.INSTANCE.isEnergyRequired()` or `network.getComponent(EnergyNetworkComponent.class).getStored() >= energyUsage`. Controllers join as `EnergyProvider`s through `onContainerAdded`; `refinedstorage:controller` starts empty, `refinedstorage:creative_controller` is always full, so an `rs_*` test powers its network by placing the latter next to the RS cable. The bridge uses `getStored() > 0` (usage 0). No RS sources jar exists; `javap -p -c` on the mod jar works.

- Building AE2 and RS networks in code (dev showcase, `ae2_showcase_bridges` and `rs_showcase_bridges`, 2026-10-15):
  an `AEBlocks.DRIVE` placed with its default state faces north and does not connect on its front, so a drive whose
  front touches the creative energy cell never joins the grid (the grid boots, inserts return 0). Parts go in with
  `PartHelper.setPart(level, pos, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT))` for the cable and
  `setPart(level, pos, side, null, AEParts.TERMINAL.get())` for a terminal on it. An RS disk
  (`refinedstorage:1k_storage_disk`) gets its storage only in `AbstractStorageContainerItem.inventoryTick` with a
  `Player` entity (`StorageContainerItemHelperImpl.loadStorageIfNecessary` checks `instanceof Player`), so code ticks
  it once with `FakePlayerFactory.getMinecraft(level)` and then puts it in
  `AbstractDiskContainerBlockEntity.getDiskInventory()` (a `SimpleContainer`); without the tick the network takes
  nothing (`javap` on RS 3.2.1, both tests broken once to confirm).

## REI drags onto ghost slots (B09 item 31; verified 2026-10-19 in the REI 26.1.819 sources jar, compile only)

- Register in `REIClientPlugin.registerScreens(ScreenRegistry)` with `registry.registerDraggableStackVisitor(DraggableStackVisitor<T>)`. A visitor implements `<R extends Screen> boolean isHandingScreen(R)`, `acceptDraggedStack(DraggingContext<T>, DraggableStack)` (return `CONSUMED` to take it, `PASS` for the next visitor) and optionally `getDraggableAcceptingBounds(context, stack)` returning a `Stream<BoundsProvider>` (`BoundsProvider.ofRectangles(Iterable<Rectangle>)`) that REI highlights while dragging.
- `context.getCurrentPosition()` is a `me.shedaniel.math.Point` in screen coordinates (same space as a screen's `leftPos + x`); `Rectangle.contains(Point)` tests it. `context.getScreen()` is the screen. `stack.getStack().getValue()` is an `ItemStack` or a NeoForge `FluidStack`, as in `ReiRecipeInputs`.
