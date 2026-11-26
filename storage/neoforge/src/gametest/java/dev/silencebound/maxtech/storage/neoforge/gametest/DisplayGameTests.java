package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayHistory;
import dev.silencebound.maxtech.storage.display.DisplayHistoryUpdate;
import dev.silencebound.maxtech.storage.display.DisplayMode;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlock;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplayPart;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplaySync;
import dev.silencebound.maxtech.storage.display.DisplayUpdate;
import dev.silencebound.maxtech.storage.display.DisplayViewers;
import dev.silencebound.maxtech.storage.display.DisplayNoticesUpdate;
import dev.silencebound.maxtech.storage.display.DisplayDevice;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplayTextSave;
import dev.silencebound.maxtech.storage.display.DisplayValuesUpdate;
import dev.silencebound.maxtech.storage.display.WallLayout;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.Vec3;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import net.minecraft.world.level.block.Blocks;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Display panels (M6c area D): walls merging and splitting, settings through the item and both forms, updates only to
 * viewers and only on change, each source's value, the server history hook and the part form. The payload sender is
 * swapped for a collector, so the test players need no connection.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DisplayGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** Marks the history test's screens. */
    private static final int HISTORY_ACCENT = 4;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    /** One payload a screen sent, and to whom. */
    record Sent(ServerPlayer player, CustomPacketPayload payload) {
    }

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("display_wall_merges_and_splits", DisplayGameTests::wallMergesAndSplits);
        register("display_settings_round_trip", DisplayGameTests::settingsRoundTrip);
        register("display_updates_only_viewers_and_changes", DisplayGameTests::updatesOnlyViewersAndChanges);
        register("display_sources_values", DisplayGameTests::sourcesValues);
        register("display_history_on_first_view", DisplayGameTests::historyOnFirstView);
        register("display_part_screen", DisplayGameTests::partScreen);
        register("display_viewer_leaves_dimension", DisplayGameTests::viewerLeavesDimension);
        register("display_viewers_after_restart", DisplayGameTests::viewersAfterRestart);
        register("display_logic_notices", DisplayGameTests::logicNotices);
        register("display_text_save_syncs", DisplayGameTests::textSaveSyncs);
        register("display_text_values_only_on_change", DisplayGameTests::textValuesOnlyOnChange);
        register("display_text_save_access", DisplayGameTests::textSaveAccess);
        register("display_notice_markup", DisplayGameTests::noticeMarkup);
        register("display_settings_survive_reform", DisplayGameTests::settingsSurviveReform);
    }

    private DisplayGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // Its own batch: the tests swap the payload sender.
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("display"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    static DisplayPanelBlockEntity panel(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, DisplayContent.PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, facing));
        return helper.getBlockEntity(pos, DisplayPanelBlockEntity.class);
    }

    private static DisplayPanelBlockEntity at(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, DisplayPanelBlockEntity.class);
    }

    private static void assertWall(GameTestHelper helper, BlockPos pos, BlockPos anchor, int width, int height) {
        DisplayPanelBlockEntity panel = at(helper, pos);
        helper.assertValueEqual(panel.anchor(), helper.absolutePos(anchor), "anchor of " + pos.toShortString());
        if (pos.equals(anchor)) {
            helper.assertValueEqual(panel.wallWidth(), width, "width of the wall at " + anchor.toShortString());
            helper.assertValueEqual(panel.wallHeight(), height, "height of the wall at " + anchor.toShortString());
        }
    }

    /** The panel's block state frames exactly the {@code edges} ({@link WallLayout#frame} bits) its model draws. */
    private static void assertFrame(GameTestHelper helper, BlockPos pos, int edges) {
        helper.assertValueEqual(DisplayPanelBlock.frame(helper.getBlockState(pos)), edges,
                "frame edges of " + pos.toShortString());
    }

    /**
     * Six panels facing north (3 wide, 2 high) form one wall anchored at the viewer's bottom left (the east end, since
     * a north-facing screen's right is west). Breaking the bottom middle panel leaves two 1 x 2 walls and a 1 x 1. A
     * row of nine splits at the default size cap into 8 and 1; a panel facing another way stays alone.
     */
    private static void wallMergesAndSplits(GameTestHelper helper) {
        for (int x = 2; x <= 4; x++) {
            for (int y = 1; y <= 2; y++) {
                panel(helper, new BlockPos(x, y, 1), Direction.NORTH);
            }
        }
        for (int x = 2; x <= 10; x++) {
            panel(helper, new BlockPos(x, 1, 5), Direction.NORTH);
        }
        panel(helper, new BlockPos(11, 1, 5), Direction.SOUTH);
        BlockPos anchor = new BlockPos(4, 1, 1);
        helper.startSequence().thenWaitUntil(() -> {
            for (int x = 2; x <= 4; x++) {
                for (int y = 1; y <= 2; y++) {
                    assertWall(helper, new BlockPos(x, y, 1), anchor, 3, 2);
                }
            }
            assertWall(helper, new BlockPos(10, 1, 5), new BlockPos(10, 1, 5), 8, 1);
            assertWall(helper, new BlockPos(3, 1, 5), new BlockPos(10, 1, 5), 8, 1);
            assertWall(helper, new BlockPos(2, 1, 5), new BlockPos(2, 1, 5), 1, 1);
            assertWall(helper, new BlockPos(11, 1, 5), new BlockPos(11, 1, 5), 1, 1);
            // Framed on the wall's outer edges only; the east end is the viewer's left.
            assertFrame(helper, new BlockPos(4, 1, 1), WallLayout.LEFT | WallLayout.BOTTOM);
            assertFrame(helper, new BlockPos(3, 1, 1), WallLayout.BOTTOM);
            assertFrame(helper, new BlockPos(2, 1, 1), WallLayout.RIGHT | WallLayout.BOTTOM);
            assertFrame(helper, new BlockPos(4, 2, 1), WallLayout.LEFT | WallLayout.TOP);
            assertFrame(helper, new BlockPos(3, 2, 1), WallLayout.TOP);
            assertFrame(helper, new BlockPos(2, 2, 1), WallLayout.RIGHT | WallLayout.TOP);
            assertFrame(helper, new BlockPos(11, 1, 5), WallLayout.ALL);
            helper.assertTrue(at(helper, anchor).worker().isStarted(), "the anchor's screen is not running");
            helper.assertFalse(at(helper, new BlockPos(3, 2, 1)).worker().isStarted(), "a member runs a screen");
        }).thenExecute(() -> helper.destroyBlock(new BlockPos(3, 1, 1))).thenWaitUntil(() -> {
            assertWall(helper, new BlockPos(4, 1, 1), new BlockPos(4, 1, 1), 1, 2);
            assertWall(helper, new BlockPos(4, 2, 1), new BlockPos(4, 1, 1), 1, 2);
            assertWall(helper, new BlockPos(2, 1, 1), new BlockPos(2, 1, 1), 1, 2);
            assertWall(helper, new BlockPos(2, 2, 1), new BlockPos(2, 1, 1), 1, 2);
            assertWall(helper, new BlockPos(3, 2, 1), new BlockPos(3, 2, 1), 1, 1);
            assertFrame(helper, new BlockPos(3, 2, 1), WallLayout.ALL);
            assertFrame(helper, new BlockPos(4, 1, 1), WallLayout.LEFT | WallLayout.RIGHT | WallLayout.BOTTOM);
            assertFrame(helper, new BlockPos(4, 2, 1), WallLayout.LEFT | WallLayout.RIGHT | WallLayout.TOP);
            helper.assertTrue(at(helper, new BlockPos(3, 2, 1)).worker().isStarted(), "the new 1 x 1 screen");
        }).thenSucceed();
    }

    /**
     * A wall's text and logic-notices settings survive the wall re-forming and a reload. Each row of two panels facing
     * north is set from its right-hand (west) panel; a panel added at the east end without the placing hook becomes the
     * new anchor with default settings, and the wall still shows what was set. Breaking the middle panel leaves two 1 x
     * 1 walls that both keep it, and the anchor's saved data reloads with it.
     */
    private static void settingsSurviveReform(GameTestHelper helper) {
        DisplaySettings[] rows = {DisplaySettings.DEFAULT.withSource(DisplaySourceKind.TEXT).withText("<b>Hello</b>"),
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.LOGIC_NOTICES)};
        for (int row = 0; row < rows.length; row++) {
            panel(helper, new BlockPos(2, 1, 1 + 2 * row), Direction.NORTH);
            panel(helper, new BlockPos(3, 1, 1 + 2 * row), Direction.NORTH);
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (int row = 0; row < rows.length; row++) {
                assertWall(helper, new BlockPos(2, 1, 1 + 2 * row), new BlockPos(3, 1, 1 + 2 * row), 2, 1);
            }
        }).thenExecute(() -> {
            for (int row = 0; row < rows.length; row++) {
                at(helper, new BlockPos(2, 1, 1 + 2 * row)).applyToWall(rows[row]);
                panel(helper, new BlockPos(4, 1, 1 + 2 * row), Direction.NORTH);
            }
        }).thenWaitUntil(() -> {
            for (int row = 0; row < rows.length; row++) {
                assertWall(helper, new BlockPos(2, 1, 1 + 2 * row), new BlockPos(4, 1, 1 + 2 * row), 3, 1);
            }
        }).thenExecute(() -> {
            for (int row = 0; row < rows.length; row++) {
                helper.assertValueEqual(
                        ShowcaseGameTests.shown(helper, helper.absolutePos(new BlockPos(2, 1, 1 + 2 * row))), rows[row],
                        "the grown wall's settings, row " + row);
                helper.destroyBlock(new BlockPos(3, 1, 1 + 2 * row));
            }
        }).thenWaitUntil(() -> {
            for (int row = 0; row < rows.length; row++) {
                assertWall(helper, new BlockPos(2, 1, 1 + 2 * row), new BlockPos(2, 1, 1 + 2 * row), 1, 1);
                assertWall(helper, new BlockPos(4, 1, 1 + 2 * row), new BlockPos(4, 1, 1 + 2 * row), 1, 1);
            }
        }).thenExecute(() -> {
            for (int row = 0; row < rows.length; row++) {
                for (int x : new int[]{2, 4}) {
                    helper.assertValueEqual(
                            ShowcaseGameTests.shown(helper, helper.absolutePos(new BlockPos(x, 1, 1 + 2 * row))),
                            rows[row], "the split wall's settings at x " + x + ", row " + row);
                }
                DisplayPanelBlockEntity anchor = at(helper, new BlockPos(4, 1, 1 + 2 * row));
                CompoundTag saved = anchor.saveWithoutMetadata(helper.getLevel().registryAccess());
                DisplayPanelBlockEntity copy = new DisplayPanelBlockEntity(anchor.getBlockPos(),
                        anchor.getBlockState());
                copy.loadWithComponents(
                        TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
                helper.assertValueEqual(copy.settings(), rows[row], "settings after a reload, row " + row);
            }
        }).thenSucceed();
    }

    private static final DisplaySettings CONFIGURED = new DisplaySettings(DisplayMode.LINE, DisplaySourceKind.TAG_COUNT,
            Optional.empty(), Optional.of(ItemTags.PLANKS), 120, 2);

    private static ItemStack craft(GameTestHelper helper, ItemStack input) {
        CraftingInput grid = CraftingInput.of(3, 3, PartGameTests.grid(input.copy()));
        return CrafterBlock.getPotentialResults(helper.getLevel(), grid).map(recipe -> recipe.value().assemble(grid))
                .orElseThrow(() -> helper.assertionException(Component.literal("No recipe for " + input)));
    }

    private static void assertSettings(GameTestHelper helper, ItemStack stack, String step) {
        helper.assertValueEqual(stack.getOrDefault(ModDataComponents.DISPLAY_SETTINGS.get(), DisplaySettings.DEFAULT),
                CONFIGURED, "the item's settings " + step);
    }

    /**
     * A configured panel keeps its settings all the way round: broken into its item (whose tooltip names them), crafted
     * into the part, put on a cable, taken off, crafted back and placed. A fresh panel placed beside a configured wall
     * takes its settings; a fresh item carries none.
     */
    private static void settingsRoundTrip(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos panelPos = new BlockPos(6, 1, 1);
        DisplayPanelBlockEntity panel = panel(helper, panelPos, Direction.NORTH);
        panel.applyToWall(CONFIGURED);
        helper.getLevel().destroyBlock(helper.absolutePos(panelPos), true);
        ItemEntity dropped = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(panelPos)).inflate(2),
                        entity -> entity.getItem().is(DisplayContent.PANEL.item().get()))
                .stream().findFirst()
                .orElseThrow(() -> helper.assertionException(Component.literal("the panel dropped nothing")));
        ItemStack blockItem = dropped.getItem().copy();
        dropped.discard();
        assertSettings(helper, blockItem, "after breaking");
        List<Component> lines = new ArrayList<>();
        blockItem.getItem().appendHoverText(blockItem, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(
                lines.stream()
                        .anyMatch(line -> line.getContents() instanceof TranslatableContents contents
                                && contents.getKey().equals(DisplayContent.SHOWS_KEY)),
                "no settings in the tooltip: " + lines);
        helper.assertFalse(
                new ItemStack(DisplayContent.PANEL.item().get()).has(ModDataComponents.DISPLAY_SETTINGS.get()),
                "a fresh panel carries settings");
        ItemStack partItem = craft(helper, blockItem);
        helper.assertTrue(partItem.is(DisplayContent.PANEL_PART_ITEM.get()), "the block crafted into " + partItem);
        assertSettings(helper, partItem, "after crafting the part");
        helper.assertTrue(
                PartGameTests.usePart(helper, helper.makeMockPlayer(GameType.SURVIVAL), 2, Direction.NORTH, partItem)
                        .consumesAction(),
                "the part was not placed");
        if (!(PartGameTests.part(helper, PartGameTests.parts(helper, 2),
                Direction.NORTH) instanceof DisplayPart part)) {
            throw helper.assertionException(Component.literal("Not a display part"));
        }
        helper.assertValueEqual(part.settings(), CONFIGURED, "the part's settings");
        CablePart removed = PartGameTests.parts(helper, 2).remove(Direction.NORTH);
        ItemStack takenOff = removed == null ? ItemStack.EMPTY : removed.toItem();
        assertSettings(helper, takenOff, "after taking the part off");
        ItemStack back = craft(helper, takenOff);
        assertSettings(helper, back, "after crafting the block");
        place(helper, back, panelPos, Direction.SOUTH);
        helper.assertValueEqual(at(helper, panelPos).settings(), CONFIGURED, "the placed panel's settings");
        BlockPos beside = panelPos.west();
        place(helper, new ItemStack(DisplayContent.PANEL.item().get()), beside, Direction.SOUTH);
        helper.assertValueEqual(at(helper, beside).settings(), CONFIGURED, "a fresh panel beside the wall");
        helper.succeed();
    }

    /** Places {@code stack} into the air at {@code pos} as if clicking a block's {@code clicked} face. */
    private static void place(GameTestHelper helper, ItemStack stack, BlockPos pos, Direction clicked) {
        BlockPos target = helper.absolutePos(pos);
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        placer.setItemInHand(InteractionHand.MAIN_HAND, stack);
        helper.assertTrue(
                stack.useOn(new UseOnContext(helper.getLevel(), placer, InteractionHand.MAIN_HAND, stack,
                        new BlockHitResult(target.getCenter(), clicked, target, false))).consumesAction(),
                "placing a panel");
    }

    /** A creative-powered network with a storage device at x 2 and, at x 3, a panel facing north; returns it. */
    private static DisplayPanelBlockEntity poweredPanel(GameTestHelper helper, DisplaySettings settings) {
        helper.setBlock(NetworkGameTests.at(1), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        DisplayPanelBlockEntity panel = panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(settings);
        return panel;
    }

    private static long chunk(GameTestHelper helper, BlockPos pos) {
        return ChunkPos.pack(helper.absolutePos(pos));
    }

    private static final Map<UUID, List<Sent>> SENT = new ConcurrentHashMap<>();

    /**
     * Collects what screens send to {@code viewer} (a player without a connection) from now on. The collector stays
     * installed for the whole run, since the tests of a batch run side by side; payloads for players it does not know
     * are dropped like the real sender drops them for players without a connection.
     */
    private static List<Sent> collect(ServerPlayer viewer) {
        List<Sent> sent = new CopyOnWriteArrayList<>();
        SENT.put(viewer.getUUID(), sent);
        DisplaySync.setSender((player, payload) -> {
            List<Sent> target = SENT.get(player.getUUID());
            if (target != null) {
                target.add(new Sent(player, payload));
            }
        });
        return sent;
    }

    private static List<DisplayUpdate> updates(List<Sent> sent) {
        List<DisplayUpdate> out = new ArrayList<>();
        for (Sent each : sent) {
            if (each.payload() instanceof DisplayUpdate update) {
                out.add(update);
            }
        }
        return out;
    }

    /**
     * An item count screen sends nothing while nobody sees it, whatever changes; a viewer gets the value at once; an
     * unchanged value is never sent again, neither by the pushed count nor by a sampled screen (stored power under a
     * creative source); three changes within one update interval arrive as one update; after the viewer leaves nothing
     * is sent. The update survives its codec.
     */
    private static void updatesOnlyViewersAndChanges(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE));
        DisplayPanelBlockEntity polled = panel(helper, NetworkGameTests.at(4), Direction.SOUTH);
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        long chunk = chunk(helper, NetworkGameTests.at(3));
        long polledChunk = chunk(helper, NetworkGameTests.at(4));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(panel.worker().isStarted() && panel.network() != null, "the screen is not running");
            helper.assertTrue(polled.worker().isStarted() && polled.network() != null, "the sampled screen");
        }).thenExecute(() -> NetworkGameTests.insert(panel.network(), COBBLE, 5)).thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(sent.size(), 0, "payloads without a viewer");
            // The two screens may sit in neighbouring chunks.
            DisplayViewers.watch(helper.getLevel(), viewer, chunk);
            DisplayViewers.watch(helper.getLevel(), viewer, polledChunk);
        }).thenWaitUntil(() -> helper.assertValueEqual(updates(sent).size(), 2, "updates after the viewer came"))
                .thenExecute(() -> {
                    helper.assertValueEqual(updates(sent, polled).size(), 1, "updates of the sampled screen");
                    DisplayUpdate first = updates(sent, panel).get(0);
                    helper.assertValueEqual(first.value(), 5L, "first value");
                    helper.assertValueEqual(first.status(), DisplayUpdate.ONLINE, "first status");
                    helper.assertValueEqual(sent.get(0).player().getUUID(), viewer.getUUID(), "the receiver");
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                    DisplayUpdate.STREAM_CODEC.encode(buffer, first);
                    helper.assertValueEqual(DisplayUpdate.STREAM_CODEC.decode(buffer), first, "decoded update");
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(updates(sent).size(), 2, "updates of unchanged values");
                    for (int i = 0; i < 3; i++) {
                        NetworkGameTests.insert(panel.network(), COBBLE, 1);
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(updates(sent).size(), 3, "updates after three changes"))
                .thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(updates(sent, panel).size(), 2, "updates after three changes and a wait");
                    helper.assertValueEqual(updates(sent, panel).get(1).value(), 8L, "value after three changes");
                    helper.assertValueEqual(updates(sent, polled).size(), 1, "updates of the sampled screen");
                    DisplayViewers.unwatch(helper.getLevel(), viewer, chunk);
                    DisplayViewers.unwatch(helper.getLevel(), viewer, polledChunk);
                    NetworkGameTests.insert(panel.network(), COBBLE, 1);
                }).thenIdle(30)
                .thenExecute(() -> helper.assertValueEqual(updates(sent).size(), 3, "updates after the viewer left"))
                .thenSucceed();
    }

    /**
     * A screen stopped and started again (as when a wall changes its anchor), the only one in its chunk: the chunk's
     * viewer list is dropped and made anew, and a change still reaches a viewer who comes afterwards.
     */
    private static void viewersAfterRestart(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "restart_viewer");
        List<Sent> sent = collect(viewer);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(panel.worker().isStarted() && panel.network() != null, "the screen is not running"))
                .thenExecute(() -> {
                    NetworkGameTests.insert(panel.network(), COBBLE, 5);
                    panel.worker().stop();
                    panel.worker().start(helper.getLevel());
                    DisplayViewers.watch(helper.getLevel(), viewer, chunk(helper, NetworkGameTests.at(3)));
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(updates(sent, panel).size(), 1, "updates once the viewer came"))
                .thenIdle(30).thenExecute(() -> NetworkGameTests.insert(panel.network(), COBBLE, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(updates(sent, panel).size(), 2, "updates after a change"))
                .thenExecute(() -> {
                    helper.assertValueEqual(updates(sent, panel).get(1).value(), 6L, "value after the change");
                    DisplayViewers.unwatch(helper.getLevel(), viewer, chunk(helper, NetworkGameTests.at(3)));
                }).thenSucceed();
    }

    private static List<DisplayUpdate> updates(List<Sent> sent, DisplayPanelBlockEntity panel) {
        List<DisplayUpdate> out = new ArrayList<>();
        for (DisplayUpdate update : updates(sent)) {
            if (update.pos().equals(panel.getBlockPos())) {
                out.add(update);
            }
        }
        return out;
    }

    /**
     * Every source's value on one network: a controller fed 1,000 FE twice through its input, a drive with a cell,
     * panels at x 2, 4, ... behind which a cable runs (so they do not touch and stay single screens). Counts and stats
     * match the network's; the rates are positive once operations happen between two samples.
     */
    private static void sourcesValues(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 2);
        helper.setBlock(controller, ControllerContent.CONTROLLER.get());
        // A cable behind every source's panel, whatever the number of sources; tier 4 carries a channel for each.
        for (int x = 2; x <= 2 * DisplaySourceKind.values().length; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 2)),
                    CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
        }
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 3));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        DisplaySourceKind[] kinds = DisplaySourceKind.values();
        List<DisplayPanelBlockEntity> panels = new ArrayList<>();
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        for (int i = 0; i < kinds.length; i++) {
            DisplayPanelBlockEntity panel = panel(helper, new BlockPos(2 + 2 * i, 1, 1), Direction.NORTH);
            panel.applyToWall(DisplaySettings.DEFAULT.withSource(kinds[i]).withItem(COBBLE).withTag(ItemTags.PLANKS));
            panels.add(panel);
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (DisplayPanelBlockEntity panel : panels) {
                helper.assertTrue(panel.worker().isStarted() && panel.network() != null, "a screen is not running");
            }
            CellGameTests.mounted(helper, drive, 0);
        }).thenExecute(() -> {
            feed(helper, controller);
            NetworkState network = NetworkGameTests.state(helper, controller);
            NetworkGameTests.insert(network, COBBLE, 100);
            NetworkGameTests.insert(network, ItemKey.of(Items.OAK_PLANKS), 10);
            NetworkGameTests.insert(network, ItemKey.of(Items.BIRCH_PLANKS), 5);
            NetworkGameTests.insert(network, ItemKey.of(Items.DIRT), 7);
            for (DisplayPanelBlockEntity panel : panels) {
                DisplayViewers.watch(helper.getLevel(), viewer, ChunkPos.pack(panel.getBlockPos()));
            }
        }).thenIdle(15).thenExecute(() -> {
            feed(helper, controller);
            NetworkState network = NetworkGameTests.state(helper, controller);
            for (int i = 0; i < 10; i++) {
                NetworkGameTests.insert(network, ItemKey.of(Items.DIRT), 1);
            }
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(last(helper, sent, panels.get(0)).value(), 100L, "item count");
            helper.assertValueEqual(last(helper, sent, panels.get(1)).value(), 15L, "tag count");
            helper.assertTrue(last(helper, sent, panels.get(2)).value() > 0, "throughput");
            DisplayUpdate stored = last(helper, sent, panels.get(3));
            helper.assertValueEqual(stored.value(), network.storedEnergy(), "power stored");
            helper.assertValueEqual(stored.max(), network.energyCapacity(), "power capacity");
            helper.assertTrue(last(helper, sent, panels.get(4)).value() > 0, "power in");
            helper.assertTrue(last(helper, sent, panels.get(5)).value() > 0, "power out");
            DisplayUpdate used = last(helper, sent, panels.get(6));
            helper.assertValueEqual(used.value(), network.usedBytes(), "bytes used");
            helper.assertValueEqual(used.max(), network.totalBytes(), "bytes total");
            helper.assertValueEqual(last(helper, sent, panels.get(7)).value(),
                    network.totalBytes() - network.usedBytes(), "bytes free");
        }).thenSucceed();
    }

    /**
     * A notify node with only the displays channel (M7L) reaches the viewer of the network's screen set to logic
     * notices, once per pulse with the newest lines, and nothing else: the screen showing stored power gets no notice,
     * and the network's log stays empty. A second notify node without the channel adds nothing to the screen.
     */
    private static void logicNotices(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "display_notices");
        DisplayPanelBlockEntity notices = panel(helper, NetworkGameTests.at(4), Direction.NORTH);
        notices.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.LOGIC_NOTICES));
        DisplayPanelBlockEntity power = panel(helper, NetworkGameTests.at(5), Direction.SOUTH);
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.NOTIFY,
                        s -> s.withText("text", "door open").withFlag("log", false).withFlag("toast", false)
                                .withFlag("displays", true))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "elsewhere").withFlag("log", false))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").build();
        BlockPos top = NetworkGameTests.at(3).above();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(notices.worker().isStarted() && notices.network() != null, "the screen is not running");
            helper.assertTrue(power.worker().isStarted() && power.network() != null, "the power screen");
            helper.assertTrue(entity.network() == notices.network(), "the controller is on another network");
        }).thenExecute(() -> {
            entity.worker().setGraph(0, "Notices", graph);
            long first = chunk(helper, NetworkGameTests.at(4));
            long second = chunk(helper, NetworkGameTests.at(5));
            DisplayViewers.watch(helper.getLevel(), viewer, first);
            if (second != first) {
                DisplayViewers.watch(helper.getLevel(), viewer, second);
            }
        }).thenWaitUntil(() -> helper.assertTrue(
                entity.worker().evaluator(0).isPresent() && entity.worker().evaluator(0).get().runs() > 0,
                "the graph has not run")).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(noticeLists(sent, notices).size(), 1, "lists on first view");
                    helper.assertValueEqual(noticeLists(sent, notices).getFirst().notices(), List.of(),
                            "notices before the pulse");
                    helper.setBlock(top, Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(noticeLists(sent, notices).size(), 2, "lists"))
                .thenExecute(() -> helper.setBlock(top, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(noticeLists(sent, notices).size(), 3, "lists"))
                .thenIdle(10).thenExecute(() -> {
                    List<DisplayNoticesUpdate> lists = noticeLists(sent, notices);
                    helper.assertValueEqual(lists.size(), 3, "one list per notice");
                    List<NetworkNotices.Notice> last = lists.getLast().notices();
                    helper.assertValueEqual(last.size(), 2, "lines in the last list");
                    helper.assertValueEqual(last.getLast().text(), "door open", "the newest line");
                    helper.assertValueEqual(noticeLists(sent, power).size(), 0, "lists to the power screen");
                    helper.assertValueEqual(LogicControllerGameTests.network(helper, entity).notices().posted(), 0L,
                            "log lines");
                }).thenSucceed();
    }

    private static List<DisplayNoticesUpdate> noticeLists(List<Sent> sent, DisplayPanelBlockEntity panel) {
        List<DisplayNoticesUpdate> out = new ArrayList<>();
        for (Sent each : sent) {
            if (each.payload() instanceof DisplayNoticesUpdate update && update.pos().equals(panel.getBlockPos())) {
                out.add(update);
            }
        }
        return out;
    }

    private static List<DisplayValuesUpdate> valueLists(List<Sent> sent, DisplayPanelBlockEntity panel) {
        List<DisplayValuesUpdate> out = new ArrayList<>();
        for (Sent each : sent) {
            if (each.payload() instanceof DisplayValuesUpdate update && update.pos().equals(panel.getBlockPos())) {
                out.add(update);
            }
        }
        return out;
    }

    /** A player standing at {@code pos} with the display menu of {@code device} open as container 7. */
    private static ServerPlayer editor(GameTestHelper helper, String name, BlockPos pos, DisplayDevice device) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(pos)));
        player.containerMenu = DisplayMenu.forDevice(7, player.getInventory(), device);
        return player;
    }

    private static final String STOCK_TEXT = "<center><gold>Stock</gold>\n[item:minecraft:cobblestone] "
            + "{count:minecraft:cobblestone}";

    /**
     * A player's save through the display menu reaches every panel of a 2 x 1 wall and its clients (the update tag),
     * and the viewer gets the placeholder's value, which survives its codec. A second save in the same tick and a text
     * with an icon of no item are refused and change nothing.
     */
    private static void textSaveSyncs(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.TEXT));
        DisplayPanelBlockEntity second = panel(helper, NetworkGameTests.at(4), Direction.NORTH);
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        ServerPlayer editor = editor(helper, "editor", NetworkGameTests.at(3), panel);
        helper.startSequence().thenWaitUntil(() -> {
            DisplayPanelBlockEntity anchor = at(helper, NetworkGameTests.at(4));
            helper.assertTrue(anchor.isAnchor() && anchor.wallWidth() == 2, "the two panels are no wall");
            helper.assertTrue(anchor.worker().isStarted() && anchor.network() != null, "the screen is not running");
        }).thenExecute(() -> {
            helper.assertValueEqual(DisplayTextSave.apply(editor, 7, STOCK_TEXT), DisplayTextSave.Result.SAVED,
                    "the save");
            helper.assertValueEqual(DisplayTextSave.apply(editor, 7, "again"), DisplayTextSave.Result.TOO_SOON,
                    "a second save in the tick");
            for (DisplayPanelBlockEntity each : List.of(panel, second)) {
                helper.assertValueEqual(each.settings().text(), STOCK_TEXT, "a panel's text");
                helper.assertValueEqual(each.settings().source(), DisplaySourceKind.TEXT, "a panel's source");
            }
            CompoundTag tag = panel.getUpdateTag(helper.getLevel().registryAccess());
            DisplaySettings synced = DisplaySettings.CODEC
                    .parse(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE),
                            tag.get("display"))
                    .getOrThrow();
            helper.assertValueEqual(synced.text(), STOCK_TEXT, "the client's text");
            NetworkGameTests.insert(panel.network(), COBBLE, 3);
            DisplayViewers.watch(helper.getLevel(), viewer, chunk(helper, NetworkGameTests.at(4)));
        }).thenWaitUntil(() -> helper.assertValueEqual(valueLists(sent, second).size(), 1, "value lists"))
                .thenExecute(() -> {
                    DisplayValuesUpdate values = valueLists(sent, second).getFirst();
                    helper.assertValueEqual(values.values(), LongArrayList.of(3), "the values");
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                    DisplayValuesUpdate.STREAM_CODEC.encode(buffer, values);
                    helper.assertValueEqual(DisplayValuesUpdate.STREAM_CODEC.decode(buffer), values, "decoded values");
                    FriendlyByteBuf save = new FriendlyByteBuf(Unpooled.buffer());
                    DisplayTextSave.STREAM_CODEC.encode(save, new DisplayTextSave(7, STOCK_TEXT));
                    helper.assertValueEqual(DisplayTextSave.STREAM_CODEC.decode(save).text(), STOCK_TEXT,
                            "decoded save");
                    helper.assertValueEqual(DisplayTextSave.apply(editor, 7, "[item:minecraft:no_such_item]"),
                            DisplayTextSave.Result.INVALID, "a save with an unknown icon");
                    helper.assertValueEqual(panel.settings().text(), STOCK_TEXT, "the text after a refused save");
                }).thenSucceed();
    }

    /**
     * A text with an item count and a tag count sends its values once to a new viewer and again only when one of them
     * changes: an item in neither leaves it alone, planks change the tag's value, cobblestone the item's. After the
     * viewer leaves nothing is sent.
     */
    private static void textValuesOnlyOnChange(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper, DisplaySettings.DEFAULT.withSource(DisplaySourceKind.TEXT)
                .withText("Cobble {count:cobblestone}, planks {count:#minecraft:planks}"));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        long chunk = chunk(helper, NetworkGameTests.at(3));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(panel.worker().isStarted() && panel.network() != null, "the screen is not running");
        }).thenExecute(() -> {
            NetworkGameTests.insert(panel.network(), COBBLE, 2);
            DisplayViewers.watch(helper.getLevel(), viewer, chunk);
        }).thenWaitUntil(() -> helper.assertValueEqual(valueLists(sent, panel).size(), 1, "lists on first view"))
                .thenExecute(() -> {
                    helper.assertValueEqual(valueLists(sent, panel).getLast().values(), LongArrayList.of(2, 0),
                            "first values");
                    NetworkGameTests.insert(panel.network(), ItemKey.of(Items.DIRT), 5);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(valueLists(sent, panel).size(), 1, "lists after dirt");
                    NetworkGameTests.insert(panel.network(), ItemKey.of(Items.OAK_PLANKS), 4);
                    NetworkGameTests.insert(panel.network(), ItemKey.of(Items.BIRCH_PLANKS), 1);
                }).thenWaitUntil(() -> helper.assertValueEqual(valueLists(sent, panel).size(), 2, "lists after planks"))
                .thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(valueLists(sent, panel).size(), 2, "lists after planks and a wait");
                    helper.assertValueEqual(valueLists(sent, panel).getLast().values(), LongArrayList.of(2, 5),
                            "values after planks");
                    NetworkGameTests.insert(panel.network(), COBBLE, 1);
                }).thenWaitUntil(() -> helper.assertValueEqual(valueLists(sent, panel).size(), 3, "lists after cobble"))
                .thenExecute(() -> {
                    helper.assertValueEqual(valueLists(sent, panel).getLast().values(), LongArrayList.of(3, 5),
                            "values after cobble");
                    helper.assertValueEqual(panel.worker().valueSends(), 3, "the worker's value lists");
                    DisplayViewers.unwatch(helper.getLevel(), viewer, chunk);
                    NetworkGameTests.insert(panel.network(), COBBLE, 1);
                }).thenIdle(30)
                .thenExecute(() -> helper.assertValueEqual(valueLists(sent, panel).size(), 3, "lists without viewer"))
                .thenSucceed();
    }

    /**
     * Saves go through the menu's own checks: a stranger on an owned screen, a player out of reach and a player whose
     * open menu has another id are refused; the owner in reach saves.
     */
    private static void textSaveAccess(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.TEXT).withText("before"));
        ServerPlayer owner = editor(helper, "text_owner", NetworkGameTests.at(3), panel);
        panel.setOwner(OwnerRecord.of(owner));
        ServerPlayer stranger = editor(helper, "text_stranger", NetworkGameTests.at(3), panel);
        ServerPlayer far = editor(helper, "text_far", NetworkGameTests.at(3), panel);
        far.setPos(far.getX() + 40, far.getY(), far.getZ());
        panel.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(panel.network() != null, "the panel is in no network");
        }).thenExecute(() -> {
            helper.assertValueEqual(DisplayTextSave.apply(stranger, 7, "stranger"), DisplayTextSave.Result.NO_ACCESS,
                    "the stranger's save");
            helper.assertValueEqual(DisplayTextSave.apply(far, 7, "far"), DisplayTextSave.Result.NO_ACCESS,
                    "a save from out of reach");
            helper.assertValueEqual(DisplayTextSave.apply(owner, 8, "other menu"), DisplayTextSave.Result.NO_MENU,
                    "a save for another menu");
            helper.assertValueEqual(panel.settings().text(), "before", "the text after refused saves");
            helper.assertValueEqual(DisplayTextSave.apply(owner, 7, "<b>after"), DisplayTextSave.Result.SAVED,
                    "the owner's save");
            helper.assertValueEqual(panel.settings().text(), "<b>after", "the text after the owner's save");
        }).thenSucceed();
    }

    /**
     * A notice with markup reaches a logic notices screen as typed, and its line (log, toast, chat) is styled: the red
     * part red, the icon as the item's name in brackets.
     */
    private static void noticeMarkup(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.LOGIC_NOTICES));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        String markup = "<red>Alarm</red> at [item:minecraft:bell]";
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(panel.worker().isStarted() && panel.network() != null, "the screen is not running");
        }).thenExecute(() -> {
            DisplayViewers.watch(helper.getLevel(), viewer, chunk(helper, NetworkGameTests.at(3)));
            NetworkState network = panel.network();
            if (network != null) {
                network.postDisplayNotice(new NetworkNotices.Notice(helper.getLevel().getGameTime(), "", markup, ""));
            }
        }).thenWaitUntil(() -> {
            List<DisplayNoticesUpdate> lists = noticeLists(sent, panel);
            helper.assertTrue(!lists.isEmpty() && !lists.getLast().notices().isEmpty(), "no notice arrived");
        }).thenExecute(() -> {
            NetworkNotices.Notice notice = noticeLists(sent, panel).getLast().notices().getLast();
            helper.assertValueEqual(notice.text(), markup, "the notice's text");
            Component line = notice.line();
            helper.assertValueEqual(line.getString(),
                    "Alarm at [" + Component.translatable(Items.BELL.getDescriptionId()).getString() + "]",
                    "the line's text");
            List<String> red = new ArrayList<>();
            line.visit((style, text) -> {
                if (style.getColor() != null && style.getColor().getValue() == 0xFF5555) {
                    red.add(text);
                }
                return Optional.empty();
            }, Style.EMPTY);
            helper.assertValueEqual(red, List.of("Alarm"), "the red parts");
        }).thenSucceed();
    }

    /** 1,000 FE (the controller's input per tick) through the controller's FE input, committed. */
    private static void feed(GameTestHelper helper, BlockPos controller) {
        try (Transaction transaction = Transaction.openRoot()) {
            NetworkGameTests.controllerEntity(helper, controller).energyInput().insert(1000, transaction);
            transaction.commit();
        }
    }

    private static DisplayUpdate last(GameTestHelper helper, List<Sent> sent, DisplayPanelBlockEntity panel) {
        DisplayUpdate found = null;
        for (DisplayUpdate update : updates(sent)) {
            if (update.pos().equals(panel.getBlockPos())) {
                found = update;
            }
        }
        if (found == null) {
            throw helper.assertionException(Component.literal("Nothing sent for " + panel.getBlockPos()));
        }
        return found;
    }

    /** A registered server history goes to a viewer when it starts seeing the screen, before the live value. */
    private static void historyOnFirstView(GameTestHelper helper) {
        // Registered for the whole run and never removed, since copies of this test run side by side; it answers only
        // for screens with the marker accent, which no other test uses.
        DisplayHistory.register((network, settings, samples, ticksPerSample, out) -> {
            if (settings.accent() == HISTORY_ACCENT) {
                out.add(1);
                out.add(2);
                out.add(3);
            }
        });
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withMode(DisplayMode.LINE).withAccent(HISTORY_ACCENT));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(panel.worker().isStarted() && panel.network() != null, "the screen is not running");
        }).thenExecute(() -> DisplayViewers.watch(helper.getLevel(), viewer, chunk(helper, NetworkGameTests.at(3))))
                .thenWaitUntil(() -> helper.assertValueEqual(updates(sent).size(), 1, "updates")).thenExecute(() -> {

                    if (!(sent.get(0).payload() instanceof DisplayHistoryUpdate history)) {
                        throw helper.assertionException(Component.literal("No history first: " + sent));
                    }
                    helper.assertValueEqual(history.samples(), LongArrayList.of(1, 2, 3), "history samples");
                    helper.assertValueEqual(history.ticksPerSample(), 20, "ticks per sample of a 60 s window");
                    DisplayHistoryUpdate.STREAM_CODEC.encode(new FriendlyByteBuf(Unpooled.buffer()), history);
                }).thenSucceed();
    }

    /**
     * A viewer who changed dimension is off the old level's lists once the dimension change event fires, even if no
     * unwatch event named the old level: a change is no longer sent to them.
     */
    private static void viewerLeavesDimension(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = poweredPanel(helper,
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE));
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw helper.assertionException(Component.literal("No Nether"));
        }
        // The player is already in the Nether, as when the event fires; the overworld still lists them.
        ServerPlayer viewer = NetworkGameTests.player(nether, "traveller");
        List<Sent> sent = collect(viewer);
        long chunk = chunk(helper, NetworkGameTests.at(3));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(panel.worker().isStarted() && panel.network() != null, "the screen is not running");
        }).thenExecute(() -> DisplayViewers.watch(helper.getLevel(), viewer, chunk))
                .thenWaitUntil(() -> helper.assertValueEqual(updates(sent).size(), 1, "updates while watching"))
                .thenExecute(() -> {
                    NeoForge.EVENT_BUS
                            .post(new PlayerEvent.PlayerChangedDimensionEvent(viewer, Level.OVERWORLD, Level.NETHER));
                    NetworkGameTests.insert(panel.network(), COBBLE, 4);
                }).thenIdle(30)
                .thenExecute(() -> helper.assertValueEqual(updates(sent).size(), 1, "updates after leaving"))
                .thenSucceed();
    }

    /** A display part is a screen of its own: its updates carry the cable face and its settings sync with the part. */
    private static void partScreen(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(1), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        ItemStack item = new ItemStack(DisplayContent.PANEL_PART_ITEM.get());
        item.set(ModDataComponents.DISPLAY_SETTINGS.get(),
                DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE));
        helper.assertTrue(PartGameTests
                .usePart(helper, helper.makeMockPlayer(GameType.SURVIVAL), 2, Direction.NORTH, item).consumesAction(),
                "the part was not placed");
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "viewer");
        List<Sent> sent = collect(viewer);
        helper.startSequence().thenWaitUntil(() -> {
            if (!(PartGameTests.part(helper, PartGameTests.parts(helper, 2), Direction.NORTH) instanceof DisplayPart p)
                    || p.deviceLink().network() == null) {
                throw helper.assertionException(Component.literal("the part is in no network"));
            }
        }).thenExecute(() -> {
            NetworkGameTests.insert(NetworkGameTests.net(helper, device), COBBLE, 9);
            DisplayViewers.watch(helper.getLevel(), viewer, chunk(helper, NetworkGameTests.at(2)));
        }).thenWaitUntil(() -> helper.assertValueEqual(updates(sent).size(), 1, "updates")).thenExecute(() -> {

            DisplayUpdate update = updates(sent).get(0);
            helper.assertValueEqual(update.side(), Direction.NORTH.get3DDataValue(), "the update's face");
            helper.assertValueEqual(update.value(), 9L, "the part's value");
        }).thenSucceed();
    }
}
