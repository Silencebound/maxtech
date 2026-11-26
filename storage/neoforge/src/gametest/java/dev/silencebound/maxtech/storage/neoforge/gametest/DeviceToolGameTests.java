package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import dev.silencebound.maxtech.storage.tool.blueprint.BlueprintContent;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.wrench.WrenchContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlock;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlockEntity;
import dev.silencebound.maxtech.storage.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * The device tools (M6e area A): the wrench picks a device up with its settings and contents only for players with full
 * access, and takes parts off cables; the blueprint card copies settings without contents, refuses other kinds, and
 * pastes along a row of parts or blocks, skipping devices the player may not configure and stopping at the configured
 * length. Everything stands at y = 1; cables run along x at z = 1 ({@link NetworkGameTests#at}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DeviceToolGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("device_tools_wrench_picks_up_port", DeviceToolGameTests::wrenchPicksUpPort);
        register("device_tools_wrench_keeps_drive_cells", DeviceToolGameTests::wrenchKeepsDriveCells);
        register("device_tools_wrench_takes_part", DeviceToolGameTests::wrenchTakesPart);
        register("device_tools_blueprint_copy_paste", DeviceToolGameTests::blueprintCopyPaste);
        register("device_tools_blueprint_row_of_parts", DeviceToolGameTests::blueprintRowOfParts);
        register("device_tools_blueprint_world_keeps_tool", DeviceToolGameTests::blueprintWorldKeepsTool);
    }

    private DeviceToolGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("device_tools"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /**
     * A port with a filter and export mode: a stranger's sneaking wrench leaves it, a plain use does not pick it up,
     * and the owner's takes it into the inventory as an item carrying both, with the mode in its tooltip.
     */
    private static void wrenchPicksUpPort(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 4);
        PortBlockEntity port = port(helper, pos, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        Player owner = mockPlayer(helper);
        port.setOwner(OwnerRecord.of(owner));
        Player stranger = mockPlayer(helper);
        wrench(helper, stranger, pos, true);
        helper.assertBlockPresent(PortContent.PORT.get(), pos);
        helper.assertValueEqual(wrench(helper, owner, pos, false), InteractionResult.PASS, "a plain use");
        helper.assertBlockPresent(PortContent.PORT.get(), pos);
        wrench(helper, owner, pos, true);
        helper.assertBlockPresent(Blocks.AIR, pos);
        ItemStack item = find(owner, PortContent.PORT.get().asItem());
        helper.assertTrue(item.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .nonEmptyItemCopyStream().anyMatch(stack -> stack.is(Items.COBBLESTONE)), "the filter in the item");
        helper.assertTrue(item.get(ModDataComponents.PORT_MODE.get()) == PortMode.EXPORT, "the mode in the item");
        List<Component> lines = new ArrayList<>();
        item.getItem().appendHoverText(item, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents text
                && text.getKey().equals(PortContent.MODE_KEY)), "the mode in the tooltip");
        helper.succeed();
    }

    /** A drive holding a cell goes into the inventory with the cell, which the drive placed from it gets back. */
    private static void wrenchKeepsDriveCells(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 4);
        helper.setBlock(pos, DriveContent.DRIVE.get());
        DriveBlockEntity drive = helper.getBlockEntity(pos, DriveBlockEntity.class);
        helper.assertTrue(drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1)), "the cell went in");
        Player owner = mockPlayer(helper);
        drive.setOwner(OwnerRecord.of(owner));
        wrench(helper, owner, pos, true);
        helper.assertBlockPresent(Blocks.AIR, pos);
        ItemStack item = find(owner, DriveContent.DRIVE.get().asItem());
        helper.setBlock(pos.below(), Blocks.STONE);
        owner.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos below = helper.absolutePos(pos.below());
        item.useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below).relative(Direction.UP, 0.5), Direction.UP, below, false)));
        DriveBlockEntity placed = helper.getBlockEntity(pos, DriveBlockEntity.class);
        helper.assertValueEqual(placed.cellCount(), 1, "cells in the placed drive");
        helper.succeed();
    }

    /** A sneaking wrench on a port part takes the part with its filter and leaves the cable. */
    private static void wrenchTakesPart(GameTestHelper helper) {
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        Player owner = mockPlayer(helper);
        PortPart part = portPart(helper, owner, 2);
        part.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        ItemStack wrench = new ItemStack(WrenchContent.WRENCH.get());
        owner.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        owner.setShiftKeyDown(true);
        wrench.onItemUseFirst(new UseOnContext(owner, InteractionHand.MAIN_HAND, partHit(helper, 2)));
        helper.assertTrue(PartGameTests.parts(helper, 2).part(Direction.NORTH) == null, "the part after the wrench");
        helper.assertBlockPresent(CableContent.STORAGE_CABLE_TIER_1.block().get(), NetworkGameTests.at(2));
        ItemStack item = find(owner, PortContent.PORT_PART_ITEM.get());
        helper.assertTrue(item.has(DataComponents.CONTAINER), "the filter in the part's item");
        helper.succeed();
    }

    /**
     * The card copies a port's filter and mode but not its speed cards, pastes them onto another port only for a player
     * with access, refuses an interface while sneaking, and copies the interface on a plain use.
     */
    private static void blueprintCopyPaste(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(3, 1, 4);
        BlockPos targetPos = new BlockPos(3, 1, 8);
        BlockPos interfacePos = new BlockPos(3, 1, 12);
        PortBlockEntity source = port(helper, sourcePos, PortMode.EXPORT);
        source.filter().setItem(2, new ItemStack(Items.COBBLESTONE));
        source.worker().cards().setItem(0, new ItemStack(PortContent.SPEED_CARD.get()));
        PortBlockEntity target = port(helper, targetPos, PortMode.IMPORT);
        Player owner = mockPlayer(helper);
        Player stranger = mockPlayer(helper);
        target.setOwner(OwnerRecord.of(owner));
        helper.setBlock(interfacePos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(interfacePos, InterfaceBlockEntity.class);

        ItemStack card = new ItemStack(BlueprintContent.CARD.get());
        card(helper, stranger, card, sourcePos, false);
        Blueprint held = card.get(BlueprintContent.BLUEPRINT.get());
        helper.assertTrue(held != null && held.kind().equals(DeviceSettings.kind("port")), "the card holds a port");
        helper.assertFalse(held != null && held.components().has(ModDataComponents.PORT_SPEED_CARDS.get()),
                "the speed cards were copied");
        card(helper, stranger, card, targetPos, false);
        helper.assertValueEqual(target.mode(), PortMode.IMPORT, "a stranger pasted");
        card(helper, owner, card, targetPos, false);
        helper.assertValueEqual(target.mode(), PortMode.EXPORT, "the pasted mode");
        helper.assertTrue(target.filter().getItem(2).is(Items.COBBLESTONE), "the pasted filter");
        helper.assertValueEqual(target.worker().cards().held(), 0, "speed cards on the target");
        helper.assertValueEqual(source.worker().cards().held(), 1, "speed cards on the source");

        card(helper, owner, card, interfacePos, true);
        helper.assertTrue(Objects.equals(card.get(BlueprintContent.BLUEPRINT.get()), held),
                "a sneaking use on an interface");
        helper.assertTrue(iface.worker().config().isEmpty(), "the interface changed");
        card(helper, owner, card, interfacePos, false);
        Blueprint copied = card.get(BlueprintContent.BLUEPRINT.get());
        helper.assertTrue(copied != null && copied.kind().equals(DeviceSettings.kind("interface")),
                "a plain use on an interface copies it");
        helper.succeed();
    }

    /**
     * Port parts on the north faces of cables x 2 to 7, the one at x 5 a stranger's, then a cable without a part and a
     * port part at x 9: sneak-pasting at x 4 reaches x 2 to 7 but not x 5 or past the gap.
     */
    private static void blueprintRowOfParts(GameTestHelper helper) {
        NetworkGameTests.cables(helper, 2, 9, CableContent.STORAGE_CABLE_TIER_1.type().get());
        Player owner = mockPlayer(helper);
        Player stranger = mockPlayer(helper);
        PortPart[] parts = new PortPart[10];
        for (int x = 2; x <= 9; x++) {
            if (x != 8) {
                parts[x] = portPart(helper, x == 5 ? stranger : owner, x);
            }
        }
        PortPart source = parts[2];
        source.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        source.setMode(PortMode.EXPORT);
        ItemStack card = new ItemStack(BlueprintContent.CARD.get());
        cardOnPart(helper, owner, card, 2, false);
        cardOnPart(helper, owner, card, 4, true);
        for (int x = 3; x <= 9; x++) {
            PortPart part = parts[x];
            if (part == null) {
                continue;
            }
            PortMode expected = x == 5 || x == 9 ? PortMode.IMPORT : PortMode.EXPORT;
            helper.assertValueEqual(part.mode(), expected, "the mode at x " + x);
            helper.assertValueEqual(part.filter().getItem(0).is(Items.COBBLESTONE), expected == PortMode.EXPORT,
                    "the filter at x " + x);
        }
        helper.succeed();
    }

    /**
     * Three breaker ports in a row along x: the card copies the first one's filter and mode without its pickaxe, and a
     * row paste from the second, limited to two devices, keeps each target's own tool and stops after the third.
     */
    private static void blueprintWorldKeepsTool(GameTestHelper helper) {
        WorldDeviceBlockEntity first = breaker(helper, 3, new ItemStack(Items.DIAMOND_PICKAXE));
        WorldDeviceBlockEntity second = breaker(helper, 4, new ItemStack(Items.IRON_PICKAXE));
        WorldDeviceBlockEntity third = breaker(helper, 5, ItemStack.EMPTY);
        WorldDeviceBlockEntity fourth = breaker(helper, 6, ItemStack.EMPTY);
        first.worker().filter().setItem(0, new ItemStack(Items.STONE));
        first.worker().setMode(1);
        Player owner = mockPlayer(helper);
        ItemStack card = new ItemStack(BlueprintContent.CARD.get());
        card(helper, owner, card, breakerPos(3), false);
        Blueprint held = card.get(BlueprintContent.BLUEPRINT.get());
        WorldSettings copied = held == null ? null : WorldSettings.read(held.components());
        helper.assertTrue(copied != null && copied.tool().isEmpty() && copied.mode() == 1, "the copied settings");
        if (held == null) {
            return;
        }
        Blueprints.Result result = Blueprints.pasteRow(owner, helper.getLevel(), helper.absolutePos(breakerPos(4)),
                Direction.NORTH, second, held, 2);
        helper.assertValueEqual(result.pasted(), 2, "devices pasted onto");
        helper.assertValueEqual(second.worker().mode(), 1, "the second's mode");
        helper.assertTrue(second.worker().filter().getItem(0).is(Items.STONE), "the second's filter");
        helper.assertTrue(tool(second).is(Items.IRON_PICKAXE), "the second's own tool");
        helper.assertValueEqual(third.worker().mode(), 1, "the third's mode");
        helper.assertTrue(tool(third).isEmpty(), "the third got a tool");
        helper.assertValueEqual(fourth.worker().mode(), 0, "past the row length");
        helper.assertTrue(tool(first).is(Items.DIAMOND_PICKAXE), "the source's tool");
        helper.succeed();
    }

    /** A survival player; a {@code ServerPlayer} without a connection fails on the first overlay message. */
    private static Player mockPlayer(GameTestHelper helper) {
        return helper.makeMockPlayer(GameType.SURVIVAL);
    }

    private static PortBlockEntity port(GameTestHelper helper, BlockPos pos, PortMode mode) {
        helper.setBlock(pos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, mode));
        return helper.getBlockEntity(pos, PortBlockEntity.class);
    }

    private static BlockPos breakerPos(int x) {
        return new BlockPos(x, 1, 4);
    }

    private static WorldDeviceBlockEntity breaker(GameTestHelper helper, int x, ItemStack tool) {
        helper.setBlock(breakerPos(x),
                WorldContent.BREAKER.get().defaultBlockState().setValue(WorldDeviceBlock.FACING, Direction.NORTH));
        WorldDeviceBlockEntity device = helper.getBlockEntity(breakerPos(x), WorldDeviceBlockEntity.class);
        Container slot = device.worker().tool();
        if (slot != null) {
            slot.setItem(0, tool);
        }
        return device;
    }

    private static ItemStack tool(WorldDeviceBlockEntity device) {
        Container slot = device.worker().tool();
        return slot == null ? ItemStack.EMPTY : slot.getItem(0);
    }

    /** A port part on the north face of the cable at {@code x}, put on by {@code player}, who owns it. */
    private static PortPart portPart(GameTestHelper helper, Player player, int x) {
        PartGameTests.usePart(helper, player, x, Direction.NORTH, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        CableParts parts = PartGameTests.parts(helper, x);
        if (PartGameTests.part(helper, parts, Direction.NORTH) instanceof PortPart port) {
            return port;
        }
        throw helper.assertionException(Component.literal("Not a port part at x " + x));
    }

    /** A hit on the outer face of the port part on the north face of the cable at {@code x}. */
    private static BlockHitResult partHit(GameTestHelper helper, int x) {
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(x));
        Vec3 location = abs.getCenter().relative(Direction.NORTH, 0.5);
        return new BlockHitResult(location, Direction.NORTH, abs, false);
    }

    private static InteractionResult wrench(GameTestHelper helper, Player player, BlockPos pos, boolean sneaking) {
        ItemStack wrench = new ItemStack(WrenchContent.WRENCH.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        player.setShiftKeyDown(sneaking);
        BlockPos abs = helper.absolutePos(pos);
        return wrench.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false)));
    }

    private static void card(GameTestHelper helper, Player player, ItemStack card, BlockPos pos, boolean sneaking) {
        BlockPos abs = helper.absolutePos(pos);
        use(player, card, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false), sneaking);
    }

    private static void cardOnPart(GameTestHelper helper, Player player, ItemStack card, int x, boolean sneaking) {
        use(player, card, partHit(helper, x), sneaking);
    }

    private static void use(Player player, ItemStack card, BlockHitResult hit, boolean sneaking) {
        player.setItemInHand(InteractionHand.MAIN_HAND, card);
        player.setShiftKeyDown(sneaking);
        card.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    /** The first stack of {@code item} in {@code player}'s inventory; fails the test when there is none. */
    private static ItemStack find(Player player, Item item) {
        ItemStack found = null;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                found = stack;
                break;
            }
        }
        return orFail(found, item);
    }

    private static ItemStack orFail(@Nullable ItemStack stack, Item item) {
        if (stack == null) {
            throw new IllegalStateException("No " + item + " in the inventory");
        }
        return stack;
    }
}
