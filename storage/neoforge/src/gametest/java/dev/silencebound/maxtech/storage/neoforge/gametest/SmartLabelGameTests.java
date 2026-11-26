package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import java.util.Optional;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.display.DisplayViewers;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.sensor.MachineReader;
import dev.silencebound.maxtech.storage.smartlabel.LabelMode;
import dev.silencebound.maxtech.storage.smartlabel.LabelSettings;
import dev.silencebound.maxtech.storage.smartlabel.LabelUpdate;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelBlock;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelBlockEntity;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelContent;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Smart labels (M7C item 19): a network item count pushed only to viewers, a plain chest's fill (per slot against each
 * item's stack size) and count read without a network, a machine's state from its lit state and from a pattern
 * provider's stuck reason, clicks setting the item and the mode, and the label keeping its settings when it drops (also
 * when the block it sits on goes).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SmartLabelGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("smart_label_network_count", SmartLabelGameTests::networkCount);
        register("smart_label_chest_fill_and_count", SmartLabelGameTests::chestFillAndCount);
        register("smart_label_machine_lit", SmartLabelGameTests::machineLit);
        register("smart_label_machine_stuck_provider", SmartLabelGameTests::machineStuckProvider);
        register("smart_label_clicks", SmartLabelGameTests::clicks);
        register("smart_label_drops_with_settings", SmartLabelGameTests::dropsWithSettings);
    }

    private SmartLabelGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("smart_label"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    /** A label at {@code pos} facing {@code facing}, so it sits on the block behind it. */
    private static SmartLabelBlockEntity label(GameTestHelper helper, BlockPos pos, Direction facing,
            LabelSettings settings) {
        helper.setBlock(pos,
                SmartLabelContent.LABEL.get().defaultBlockState().setValue(SmartLabelBlock.FACING, facing));
        SmartLabelBlockEntity label = helper.getBlockEntity(pos, SmartLabelBlockEntity.class);
        label.setSettings(settings);
        return label;
    }

    /** Starts showing {@code label} to {@code viewer}; fails (to be retried) until the label has started. */
    private static void watch(GameTestHelper helper, ServerPlayer viewer, SmartLabelBlockEntity label) {
        helper.assertTrue(label.worker().isStarted(), "the label has not started");
        DisplayViewers.watch(helper.getLevel(), viewer, ChunkPos.pack(label.getBlockPos()));
    }

    private static void unwatch(GameTestHelper helper, ServerPlayer viewer, SmartLabelBlockEntity label) {
        DisplayViewers.unwatch(helper.getLevel(), viewer, ChunkPos.pack(label.getBlockPos()));
    }

    private static LabelUpdate sent(GameTestHelper helper, SmartLabelBlockEntity label) {
        LabelUpdate update = label.worker().lastSent();
        if (update == null) {
            throw helper.assertionException(Component.literal("the label sent nothing"));
        }
        return update;
    }

    private static void assertSent(GameTestHelper helper, SmartLabelBlockEntity label, int detail, long value,
            long max) {
        LabelUpdate update = sent(helper, label);
        helper.assertValueEqual(update.status(), LabelUpdate.SHOWN, "status");
        helper.assertValueEqual(update.detail(), detail, "detail");
        helper.assertValueEqual(update.value(), value, "value");
        helper.assertValueEqual(update.max(), max, "max");
    }

    /** A chest's item fill, out of {@link MachineReader#FILL_SCALE}: each slot against its item's stack size. */
    private static void assertFill(GameTestHelper helper, SmartLabelBlockEntity label, long fill) {
        assertSent(helper, label, LabelUpdate.FILL_ITEMS, fill, MachineReader.FILL_SCALE);
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos, int cobble) {
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, cobble));
        chest.setChanged();
        return chest;
    }

    /**
     * A label on a network's cable (controller at x 1, a drive with a cell beside it, cable at x 2, label at x 3)
     * showing cobblestone: nothing is sent while nobody sees it; a viewer gets the network's count, then each change
     * (pushed, without polling); after the viewer leaves nothing more is sent. The update survives its codec.
     */
    private static void networkCount(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        SensorGameTests.ownNetwork(helper, controller);
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(1).south());
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SmartLabelBlockEntity label = label(helper, NetworkGameTests.at(3), Direction.EAST,
                LabelSettings.DEFAULT.withItem(Items.COBBLESTONE));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "label_viewer");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(label.network() != null, "the label is in no network");
            CellGameTests.mounted(helper, drive, 0);
        }).thenExecute(() -> NetworkGameTests.insert(label.network(), COBBLE, 5)).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(label.worker().sends(), 0, "updates without a viewer");
            watch(helper, viewer, label);
        }).thenWaitUntil(() -> assertSent(helper, label, LabelUpdate.FROM_NETWORK, 5, 0)).thenExecute(() -> {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            LabelUpdate update = sent(helper, label);
            LabelUpdate.STREAM_CODEC.encode(buffer, update);
            helper.assertValueEqual(LabelUpdate.STREAM_CODEC.decode(buffer), update, "decoded update");
            NetworkState network = NetworkGameTests.state(helper, controller);
            NetworkGameTests.insert(network, COBBLE, 3);
        }).thenWaitUntil(() -> assertSent(helper, label, LabelUpdate.FROM_NETWORK, 8, 0)).thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(label.worker().sends(), 2, "updates of two values");
                    unwatch(helper, viewer, label);
                    NetworkGameTests.insert(label.network(), COBBLE, 1);
                }).thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(label.worker().sends(), 2, "updates after the viewer left"))
                .thenSucceed();
    }

    /**
     * A label on a plain chest (no network): its fill (items against the chest's room), then the count of one item in
     * it, following a change of the chest's contents.
     */
    private static void chestFillAndCount(GameTestHelper helper) {
        ChestBlockEntity chest = chest(helper, NetworkGameTests.at(5), 64);
        SmartLabelBlockEntity label = label(helper, NetworkGameTests.at(6), Direction.EAST,
                LabelSettings.DEFAULT.withMode(LabelMode.FILL));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "label_viewer");
        helper.startSequence().thenWaitUntil(() -> watch(helper, viewer, label))
                // One full slot of 27 (a stack of 64 cobblestone is full), whatever room an empty slot reports.
                .thenWaitUntil(() -> assertFill(helper, label, Math.round(MachineReader.FILL_SCALE / 27.0)))
                .thenExecute(() -> {
                    // Every slot full of a 16-stack item: 100 %.
                    for (int slot = 0; slot < 27; slot++) {
                        chest.setItem(slot, new ItemStack(Items.ENDER_PEARL, 16));
                    }
                    chest.setChanged();
                }).thenWaitUntil(() -> assertFill(helper, label, MachineReader.FILL_SCALE)).thenExecute(() -> {
                    for (int slot = 0; slot < 27; slot++) {
                        chest.setItem(slot, ItemStack.EMPTY);
                    }
                    chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                    chest.setChanged();
                    label.setSettings(label.settings().withItem(Items.COBBLESTONE));
                }).thenWaitUntil(() -> assertSent(helper, label, LabelUpdate.FROM_BLOCK, 64, 0)).thenExecute(() -> {
                    chest.setItem(1, new ItemStack(Items.COBBLESTONE, 10));
                    chest.setChanged();
                }).thenWaitUntil(() -> assertSent(helper, label, LabelUpdate.FROM_BLOCK, 74, 0))
                .thenExecute(() -> unwatch(helper, viewer, label)).thenSucceed();
    }

    /** A label on a furnace shows it idle, then working once the furnace is lit. */
    private static void machineLit(GameTestHelper helper) {
        BlockPos furnace = NetworkGameTests.at(5);
        helper.setBlock(furnace, Blocks.FURNACE.defaultBlockState());
        SmartLabelBlockEntity label = label(helper, NetworkGameTests.at(6), Direction.EAST,
                LabelSettings.DEFAULT.withMode(LabelMode.MACHINE));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "label_viewer");
        helper.startSequence().thenWaitUntil(() -> watch(helper, viewer, label))
                .thenWaitUntil(() -> assertSent(helper, label, LabelUpdate.IDLE, 0, 0))
                .thenExecute(() -> helper.setBlock(furnace,
                        helper.getBlockState(furnace).setValue(BlockStateProperties.LIT, true)))
                .thenWaitUntil(() -> assertSent(helper, label, LabelUpdate.WORKING, 0, 0))
                .thenExecute(() -> unwatch(helper, viewer, label)).thenSucceed();
    }

    /**
     * A pattern provider on an unpowered network facing a chest; a label on the chest's top names the provider's stuck
     * reason (no power).
     */
    private static void machineStuckProvider(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        helper.setBlock(NetworkGameTests.at(2), ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        chest(helper, NetworkGameTests.at(3), 1);
        SmartLabelBlockEntity label = label(helper, NetworkGameTests.at(3).above(), Direction.UP,
                LabelSettings.DEFAULT.withMode(LabelMode.MACHINE));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "label_viewer");
        helper.startSequence().thenWaitUntil(() -> watch(helper, viewer, label)).thenWaitUntil(
                () -> assertSent(helper, label, LabelUpdate.STUCK, SmartLabelContent.reasonId(StuckReason.NO_POWER), 0))
                .thenExecute(() -> unwatch(helper, viewer, label)).thenSucceed();
    }

    /**
     * Clicks of the label's owner: an item in hand sets it (mode item count), an empty hand switches to the next mode,
     * another smart label in hand passes so it can be placed. A stranger's click on an owned label changes nothing.
     */
    private static void clicks(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(5), Blocks.STONE);
        BlockPos pos = NetworkGameTests.at(6);
        SmartLabelBlockEntity label = label(helper, pos, Direction.EAST,
                LabelSettings.DEFAULT.withMode(LabelMode.MACHINE));
        Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
        label.setOwner(OwnerRecord.of(owner));
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.EAST, absolute, false);
        BlockState state = helper.getBlockState(pos);

        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT));
        state.useItemOn(owner.getMainHandItem(), helper.getLevel(), owner, InteractionHand.MAIN_HAND, hit);
        helper.assertValueEqual(label.settings(), LabelSettings.DEFAULT.withItem(Items.IRON_INGOT), "item set");

        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        state.useWithoutItem(helper.getLevel(), owner, hit);
        helper.assertValueEqual(label.settings().mode(), LabelMode.FILL, "mode after a click");
        helper.assertValueEqual(label.settings().item(), Optional.of(Items.IRON_INGOT), "item kept");

        InteractionResult passed = state.useItemOn(new ItemStack(SmartLabelContent.LABEL.get()), helper.getLevel(),
                owner, InteractionHand.MAIN_HAND, hit);
        helper.assertValueEqual(passed, InteractionResult.PASS, "another label in hand");

        Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
        state.useWithoutItem(helper.getLevel(), stranger, hit);
        state.useItemOn(new ItemStack(Items.DIRT), helper.getLevel(), stranger, InteractionHand.MAIN_HAND, hit);
        helper.assertValueEqual(label.settings().mode(), LabelMode.FILL, "a stranger's click");
        helper.assertValueEqual(label.settings().item(), Optional.of(Items.IRON_INGOT), "a stranger's item");
        helper.succeed();
    }

    /**
     * The label's drop carries its settings and a label placed from it takes them; it pops off and drops when the block
     * it sits on is removed, and cannot be placed on air.
     */
    private static void dropsWithSettings(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(5), Blocks.STONE);
        LabelSettings settings = new LabelSettings(LabelMode.FILL, Optional.of(Items.REDSTONE));
        SmartLabelBlockEntity label = label(helper, NetworkGameTests.at(6), Direction.EAST, settings);
        List<ItemStack> drops = Block.getDrops(label.getBlockState(), helper.getLevel(), label.getBlockPos(), label);
        helper.assertValueEqual(drops.size(), 1, "drops");
        helper.assertValueEqual(Optional.ofNullable(drops.getFirst().get(SmartLabelContent.SETTINGS.get())),
                Optional.of(settings), "dropped settings");

        BlockPos second = NetworkGameTests.at(8);
        helper.setBlock(NetworkGameTests.at(7), Blocks.STONE);
        helper.setBlock(second,
                SmartLabelContent.LABEL.get().defaultBlockState().setValue(SmartLabelBlock.FACING, Direction.EAST));
        SmartLabelBlockEntity placed = helper.getBlockEntity(second, SmartLabelBlockEntity.class);
        placed.applyComponentsFromItemStack(drops.getFirst());
        helper.assertValueEqual(placed.settings(), settings, "settings of a placed label");

        helper.assertFalse(
                SmartLabelContent.LABEL.get().defaultBlockState().setValue(SmartLabelBlock.FACING, Direction.EAST)
                        .canSurvive(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(10))),
                "a label on air");
        helper.setBlock(NetworkGameTests.at(5), Blocks.AIR);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertBlockNotPresent(SmartLabelContent.LABEL.get(), NetworkGameTests.at(6));
            helper.assertItemEntityPresent(SmartLabelContent.LABEL.get().asItem(), NetworkGameTests.at(6), 2);
            for (ItemEntity item : helper.getEntities(EntityType.ITEM, NetworkGameTests.at(6), 2)) {
                if (item.getItem().is(SmartLabelContent.LABEL.get().asItem())) {
                    helper.assertValueEqual(Optional.ofNullable(item.getItem().get(SmartLabelContent.SETTINGS.get())),
                            Optional.of(settings), "settings of the popped label");
                }
            }
        }).thenSucceed();
    }

}
