package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchPart;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkLabels;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Device labels (M7L area C): the network's label index follows labels as devices join, leave and are relabelled;
 * subnets keep their own; labels survive saving, breaking, the part's item and the blueprint card; the label packet
 * changes a label only for a player who may configure the device; Jade and the controller's Network tab show them.
 */
// Devices are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LabelGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final DeviceLabel SMELTERS = DeviceLabel.of("Smelters", DyeColor.RED);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("label_index_follows_devices", LabelGameTests::index);
        register("label_subnets_keep_their_own", LabelGameTests::subnets);
        register("label_saved_and_dropped", LabelGameTests::saved);
        register("label_part_item_and_blueprint", LabelGameTests::partAndBlueprint);
        register("label_packet_needs_access", LabelGameTests::packet);
        register("label_kept_by_drives_and_subnets", LabelGameTests::drives);
    }

    private LabelGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("label"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 200);
        }
    }

    private static void assertDevices(GameTestHelper helper, NetworkLabels labels, String label,
            List<? extends NetworkDevice> expected) {
        List<NetworkDevice> found = labels.devices(label);
        helper.assertValueEqual(found.size(), expected.size(), "devices labelled " + label);
        for (NetworkDevice device : expected) {
            helper.assertTrue(found.contains(device), "missing a device labelled " + label);
        }
        helper.assertValueEqual(labels.has(label), !expected.isEmpty(), "has " + label);
    }

    /**
     * Two devices labelled "Smelters" and " smelters" are found together by any case; renaming one moves it; a device
     * without a label is in no entry; breaking a device takes it out; the Network tab and Jade show the labels.
     */
    private static void index(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice first = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        TestStorage.TestDevice second = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        TestStorage.TestDevice plain = NetworkGameTests.device(helper, NetworkGameTests.at(4));
        // Set before the device joins: indexed when it does.
        first.setLabel(SMELTERS);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(NetworkGameTests.net(helper, plain) == network, "all in one network");
            helper.assertTrue(NetworkGameTests.net(helper, first) == network, "all in one network");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            second.setLabel(DeviceLabel.of(" smelters ", null));
            NetworkLabels labels = network.labels();
            assertDevices(helper, labels, "SMELTERS", List.of(first, second));
            helper.assertValueEqual(labels.size(), 1, "one label");
            helper.assertValueEqual(plain.label(), DeviceLabel.NONE, "no label");

            second.setLabel(DeviceLabel.of("Ore", DyeColor.BLUE));
            assertDevices(helper, labels, "smelters", List.of(first));
            assertDevices(helper, labels, "ore", List.of(second));

            NetworkOverview overview = NetworkOverview.of(network, NetworkOverview.Cluster.NO_CLUSTER);
            helper.assertValueEqual(overview.labels().size(), 2, "labels on the Network tab");
            helper.assertValueEqual(overview.labels().get(0).label().text(), "Ore", "sorted by text");
            helper.assertTrue(NetworkGameTests.keys(helper, NetworkGameTests.at(2), BlockInfoRequest.Detail.FULL)
                    .contains(NetworkInfo.LABEL_KEY), "Jade shows the label");
            helper.assertFalse(NetworkGameTests.keys(helper, NetworkGameTests.at(4), BlockInfoRequest.Detail.FULL)
                    .contains(NetworkInfo.LABEL_KEY), "no label line without a label");

            helper.setBlock(NetworkGameTests.at(2), Blocks.AIR);
            assertDevices(helper, labels, "smelters", List.of());
            second.setLabel(DeviceLabel.NONE);
            helper.assertValueEqual(labels.size(), 0, "no labels left");
        }).thenSucceed();
    }

    /** A label in the parent and the same label in a subnet: each network finds only its own device. */
    private static void subnets(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice parentDevice = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice subnetDevice = NetworkGameTests.device(helper, NetworkGameTests.at(8));
        parentDevice.setLabel(SMELTERS);
        subnetDevice.setLabel(SMELTERS);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertTrue(parent != child, "the subnet is its own network");
            helper.assertTrue(subnet.parent() == parent, "uplink not in the parent");
            helper.assertTrue(NetworkGameTests.net(helper, parentDevice) == parent, "parent device");
        }).thenExecute(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            assertDevices(helper, parent.labels(), "smelters", List.of(parentDevice));
            assertDevices(helper, child.labels(), "smelters", List.of(subnetDevice));
        }).thenSucceed();
    }

    /**
     * A device's label survives saving and loading; a broken terminal (a plain loot table) and a broken condition
     * switch drop it in their items, and placing such an item labels the new block.
     */
    private static void saved(GameTestHelper helper) {
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        device.setLabel(SMELTERS);
        CompoundTag tag = device.saveWithoutMetadata(helper.getLevel().registryAccess());
        TestStorage.TestDevice loaded = new TestStorage.TestDevice(device.getBlockPos(), device.getBlockState());
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        helper.assertValueEqual(loaded.label(), SMELTERS, "label after loading");

        BlockPos terminalPos = NetworkGameTests.at(4);
        helper.setBlock(terminalPos, TerminalContent.TERMINAL.get());
        TerminalBlockEntity terminal = helper.getBlockEntity(terminalPos, TerminalBlockEntity.class);
        List<ItemStack> unlabelled = Block.getDrops(helper.getBlockState(terminalPos), helper.getLevel(),
                helper.absolutePos(terminalPos), terminal);
        helper.assertValueEqual(unlabelled.size(), 1, "terminal drops");
        helper.assertTrue(unlabelled.get(0).get(ModDataComponents.DEVICE_LABEL.get()) == null,
                "an unlabelled terminal's item carries a label");
        terminal.setLabel(SMELTERS);
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(terminalPos), helper.getLevel(),
                helper.absolutePos(terminalPos), terminal);
        helper.assertTrue(SMELTERS.equals(drops.get(0).get(ModDataComponents.DEVICE_LABEL.get())), "terminal item");

        BlockPos switchPos = NetworkGameTests.at(6);
        helper.setBlock(switchPos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(switchPos, ConditionSwitchBlockEntity.class);
        entity.setLabel(SMELTERS);
        ItemStack drop = Block
                .getDrops(helper.getBlockState(switchPos), helper.getLevel(), helper.absolutePos(switchPos), entity)
                .get(0);
        helper.assertTrue(SMELTERS.equals(drop.get(ModDataComponents.DEVICE_LABEL.get())), "switch item");

        BlockPos placed = NetworkGameTests.at(8);
        helper.setBlock(placed, TerminalContent.TERMINAL.get());
        TerminalBlockEntity fresh = helper.getBlockEntity(placed, TerminalBlockEntity.class);
        fresh.applyComponentsFromItemStack(drops.get(0));
        helper.assertValueEqual(fresh.label(), SMELTERS, "label from the placed item");
        helper.succeed();
    }

    /**
     * A condition switch part placed from an item with a label carries it, joins the network's index with it and gives
     * it back in its item; the blueprint card copies the label from one switch to another along with the settings, and
     * a card without a label leaves a label alone.
     */
    private static void partAndBlueprint(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(3)));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        ItemStack stack = new ItemStack(ConditionContent.SWITCH_PART_ITEM.get());
        stack.set(ModDataComponents.DEVICE_LABEL.get(), SMELTERS);
        helper.assertTrue(parts.place(Direction.EAST, ConditionContent.SWITCH_PART, null, stack), "not placed");
        CablePart part = parts.part(Direction.EAST);
        if (part == null) {
            throw helper.assertionException(Component.literal("no switch part"));
        }
        helper.assertValueEqual(part.label(), SMELTERS, "label from the item");
        helper.assertTrue(SMELTERS.equals(part.toItem().get(ModDataComponents.DEVICE_LABEL.get())),
                "label in the item");

        BlockPos switchPos = NetworkGameTests.at(5);
        helper.setBlock(switchPos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity target = helper.getBlockEntity(switchPos, ConditionSwitchBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            assertDevices(helper, network.labels(), "smelters", List.of(part));
        }).thenExecute(() -> {
            if (!(part instanceof ConditionSwitchPart source)) {
                throw helper.assertionException(Component.literal("not a switch part"));
            }
            Blueprint card = Blueprints.copy(source);
            helper.assertTrue(Blueprints.paste(target, card), "pasted");
            helper.assertValueEqual(target.label(), SMELTERS, "label from the card");
            target.setLabel(DeviceLabel.of("Kept", null));
            Blueprint blank = Blueprints.copy(target);
            target.setLabel(DeviceLabel.NONE);
            helper.assertTrue(Blueprints.paste(target, blank), "pasted again");
            helper.assertValueEqual(target.label().text(), "Kept", "label from the second card");
            source.setLabel(DeviceLabel.NONE);
            Blueprint none = Blueprints.copy(source);
            helper.assertTrue(Blueprints.paste(target, none), "pasted a card without a label");
            helper.assertValueEqual(target.label().text(), "Kept", "a card without a label keeps the label");
        }).thenSucceed();
    }

    /**
     * The blocks that build their drops themselves keep the label as well, next to their contents: a drive with a cell,
     * an empty drive, a bulk drive with items and a subnet controller with a priority. Each dropped item, placed again,
     * gives back the label and the contents.
     */
    private static void drives(GameTestHelper helper) {
        BlockPos drivePos = NetworkGameTests.at(2);
        DriveBlockEntity drive = CellGameTests.drive(helper, drivePos);
        helper.assertTrue(drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1)), "cell in");
        drive.setLabel(SMELTERS);
        ItemStack driveItem = dropped(helper, drivePos, drive);
        helper.assertTrue(SMELTERS.equals(driveItem.get(ModDataComponents.DEVICE_LABEL.get())), "drive item");
        DriveBlockEntity driveAgain = CellGameTests.drive(helper, NetworkGameTests.at(4));
        place(helper, driveAgain, driveItem);
        helper.assertValueEqual(driveAgain.label(), SMELTERS, "drive label after placing");
        helper.assertValueEqual(driveAgain.cellCount(), 1, "drive cells after placing");

        BlockPos emptyPos = NetworkGameTests.at(6);
        DriveBlockEntity empty = CellGameTests.drive(helper, emptyPos);
        empty.setLabel(SMELTERS);
        ItemStack emptyItem = dropped(helper, emptyPos, empty);
        helper.assertTrue(SMELTERS.equals(emptyItem.get(ModDataComponents.DEVICE_LABEL.get())), "empty drive item");
        helper.assertTrue(emptyItem.get(DataComponents.BLOCK_ENTITY_DATA) == null, "data on an empty drive's item");

        BlockPos bulkPos = NetworkGameTests.at(8);
        BulkDriveBlockEntity bulk = BulkDriveGameTests.drive(helper, bulkPos);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(bulk.store().insert(COBBLE, 40, transaction), 40L, "cobblestone in");
            transaction.commit();
        }
        bulk.setLabel(SMELTERS);
        ItemStack bulkItem = dropped(helper, bulkPos, bulk);
        helper.assertTrue(SMELTERS.equals(bulkItem.get(ModDataComponents.DEVICE_LABEL.get())), "bulk drive item");
        BulkDriveBlockEntity bulkAgain = BulkDriveGameTests.drive(helper, NetworkGameTests.at(10));
        place(helper, bulkAgain, bulkItem);
        helper.assertValueEqual(bulkAgain.label(), SMELTERS, "bulk drive label after placing");
        helper.assertValueEqual(bulkAgain.store().amount(COBBLE), 40L, "bulk drive contents after placing");

        BlockPos subnetPos = NetworkGameTests.at(12);
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, subnetPos, Direction.WEST);
        subnet.setPriority(7);
        subnet.setLabel(SMELTERS);
        ItemStack subnetItem = dropped(helper, subnetPos, subnet);
        SubnetBlockEntity subnetAgain = SubnetGameTests.subnet(helper, NetworkGameTests.at(14), Direction.WEST);
        place(helper, subnetAgain, subnetItem);
        helper.assertValueEqual(subnetAgain.label(), SMELTERS, "subnet label after placing");
        helper.assertValueEqual(subnetAgain.priority(), 7, "subnet priority after placing");
        helper.succeed();
    }

    /** The one item {@code entity}'s block drops. */
    private static ItemStack dropped(GameTestHelper helper, BlockPos pos, BlockEntity entity) {
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(pos), helper.getLevel(), helper.absolutePos(pos),
                entity);
        helper.assertValueEqual(drops.size(), 1, "drops at " + pos.toShortString());
        return drops.get(0);
    }

    /**
     * Applies {@code item} to a placed block entity the way {@code BlockItem.place} does: its data, then components.
     */
    private static void place(GameTestHelper helper, BlockEntity entity, ItemStack item) {
        TypedEntityData<BlockEntityType<?>> data = item.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data != null) {
            data.loadInto(entity, helper.getLevel().registryAccess());
        }
        entity.applyComponentsFromItemStack(item);
    }

    /**
     * The label packet labels the open device for its owner, cleaned and capped; a stranger, another container id or a
     * closed menu change nothing.
     */
    private static void packet(GameTestHelper helper) {
        BlockPos switchPos = NetworkGameTests.at(3);
        helper.setBlock(switchPos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(switchPos, ConditionSwitchBlockEntity.class);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "label_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "label_stranger");
        Vec3 near = Vec3.atCenterOf(helper.absolutePos(switchPos.above()));
        owner.setPos(near);
        stranger.setPos(near);
        entity.setOwner(OwnerRecord.of(owner));

        owner.containerMenu = ConditionMenu.forDevice(7, owner.getInventory(), entity);
        stranger.containerMenu = ConditionMenu.forDevice(8, stranger.getInventory(), entity);
        helper.assertTrue(LabelPayloads.target(owner, 7) == entity, "the open device");
        helper.assertFalse(LabelPayloads.apply(owner, 6, SMELTERS), "another container id");
        helper.assertValueEqual(entity.label(), DeviceLabel.NONE, "unchanged");
        helper.assertFalse(LabelPayloads.apply(stranger, 8, SMELTERS), "a stranger");
        helper.assertValueEqual(entity.label(), DeviceLabel.NONE, "unchanged by a stranger");

        String tooLong = "  A label much longer than thirty-two characters  ";
        helper.assertTrue(LabelPayloads.apply(owner, 7, DeviceLabel.of(tooLong, DyeColor.LIME)), "owner");
        helper.assertValueEqual(entity.label().text(), "A label much longer than thirty-", "trimmed and capped");
        helper.assertTrue(entity.label().colour() == DyeColor.LIME, "colour");
        helper.assertTrue(LabelPayloads.apply(owner, 7, DeviceLabel.of("   ", DyeColor.RED)), "cleared");
        helper.assertValueEqual(entity.label(), DeviceLabel.NONE, "blank text is no label");

        owner.containerMenu = owner.inventoryMenu;
        helper.assertFalse(LabelPayloads.apply(owner, 7, SMELTERS), "closed menu");
        helper.succeed();
    }
}
