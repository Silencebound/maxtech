package dev.silencebound.maxtech.storage.neoforge.gametest;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.List;
import java.util.ArrayList;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartLook;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import dev.silencebound.maxtech.storage.port.PortTier;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchMenu;
import dev.silencebound.maxtech.storage.research.ResearchTerminalPart;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The port part and the research terminal part (M6b area P, PR 2): the port part moves items at the tier-1 rate like
 * the block, wakes when its inventory changes, keeps its filter and mode in its item, and switches its look with its
 * mode; the research terminal part opens the research screen on a working network. Layouts run along x at y = 1, z = 1:
 * controller, cable, test drive; the inventory sits north of the cable.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PortPartGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"part_port_moves_at_tier_rate", "part_port_wakes_on_inventory_change",
            "part_port_keeps_filter_in_item", "part_research_terminal_opens", "part_port_settings_round_trip",
            "redstone_port_part_signal", "redstone_port_part_pulse"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final long POWER = 10_000;
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], PortPartGameTests::movesAtTierRate);
        register(NAMES[1], PortPartGameTests::wakesOnInventoryChange);
        register(NAMES[2], PortPartGameTests::keepsFilterInItem);
        register(NAMES[3], PortPartGameTests::researchTerminalOpens);
        register(NAMES[4], PortPartGameTests::settingsRoundTrip);
        register(NAMES[5], PortPartGameTests::redstoneSignal);
        register(NAMES[6], PortPartGameTests::redstonePulse);
    }

    private PortPartGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("port_part"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * B09 item 9 on the part form, which reads the signal at its cable on the cable's block update: an import part
     * "with signal" moves nothing without one, imports with a redstone block on the cable and stops when it goes;
     * switched to "without signal" it imports again until the block comes back.
     */
    private static void redstoneSignal(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        PortPart port = portPart(helper, helper.makeMockPlayer(GameType.SURVIVAL),
                new ItemStack(PortContent.PORT_PART_ITEM.get()));
        port.redstone().setMode(RedstoneMode.WITH_SIGNAL);
        BlockPos lever = NetworkGameTests.at(2).above();
        long[] stopped = new long[1];
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "imported without a signal");
            helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertTrue(drive.provider.amount(COBBLE) > 0, "nothing imported with a signal"))
                .thenExecute(() -> helper.setBlock(lever, Blocks.AIR)).thenIdle(2)
                .thenExecute(() -> stopped[0] = drive.provider.amount(COBBLE)).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), stopped[0], "imported after the signal");
                    port.redstone().setMode(RedstoneMode.WITHOUT_SIGNAL);
                })
                .thenWaitUntil(() -> helper.assertTrue(drive.provider.amount(COBBLE) > stopped[0],
                        "nothing imported without a signal"))
                .thenExecute(() -> helper.setBlock(lever, Blocks.REDSTONE_BLOCK)).thenIdle(2)
                .thenExecute(() -> stopped[0] = drive.provider.amount(COBBLE)).thenIdle(40)
                .thenExecute(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), stopped[0],
                        "imported with a signal while set to without"))
                .thenSucceed();
    }

    /** A pulse part moves exactly one step of 16 per rising edge at its cable, nothing without an edge. */
    private static void redstonePulse(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        PortPart port = portPart(helper, helper.makeMockPlayer(GameType.SURVIVAL),
                new ItemStack(PortContent.PORT_PART_ITEM.get()));
        port.redstone().setMode(RedstoneMode.PULSE);
        BlockPos lever = NetworkGameTests.at(2).above();
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "imported without a pulse");
            helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 16L, "after one edge"))
                .thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 16L, "one edge moved more");
                    helper.setBlock(lever, Blocks.AIR);
                }).thenIdle(2).thenExecute(() -> helper.setBlock(lever, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 32L, "after two edges"))
                .thenIdle(30)
                .thenExecute(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 32L, "two edges moved more"))
                .thenSucceed();
    }

    /** A powered controller, a cable and a test drive; returns the drive. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, POWER);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return NetworkGameTests.device(helper, NetworkGameTests.at(3));
    }

    /** A chest north of the cable, where a part on the cable's north face works. */
    private static ChestBlockEntity chest(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(2).north();
        helper.setBlock(pos, Blocks.CHEST);
        return helper.getBlockEntity(pos, ChestBlockEntity.class);
    }

    private static PortPart portPart(GameTestHelper helper, Player player, ItemStack stack) {
        helper.assertTrue(PartGameTests.usePart(helper, player, 2, Direction.NORTH, stack).consumesAction(),
                "the port part was not placed");
        if (PartGameTests.part(helper, PartGameTests.parts(helper, 2), Direction.NORTH) instanceof PortPart port) {
            return port;
        }
        throw helper.assertionException(Component.literal("Not a port part"));
    }

    private static long count(ChestBlockEntity chest) {
        long total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(Items.COBBLESTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * An import port part on a full chest moves its start credit of 16 at once, then 64 to 80 in the next 100 ticks (16
     * a second, the block's rate), with nothing made or lost.
     */
    private static void movesAtTierRate(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        portPart(helper, helper.makeMockPlayer(GameType.SURVIVAL), new ItemStack(PortContent.PORT_PART_ITEM.get()));
        long[] start = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            start[0] = drive.provider.amount(COBBLE);
            helper.assertTrue(start[0] > 0, "nothing moved yet");
        }).thenExecute(() -> helper.assertValueEqual(start[0], 16L, "the start credit")).thenIdle(100)
                .thenExecute(() -> {
                    long moved = drive.provider.amount(COBBLE) - start[0];
                    helper.assertTrue(moved >= 64 && moved <= 80, moved + " items in 100 ticks at 16 a second");
                    helper.assertValueEqual(count(chest) + drive.provider.amount(COBBLE), 27L * 64,
                            "items in the chest and the drive");
                }).thenSucceed();
    }

    /**
     * An import port part on an empty chest goes to sleep; cobblestone put into the chest afterwards wakes it through
     * the chest's change event and moves within a few ticks, long before its idle backoff would.
     */
    private static void wakesOnInventoryChange(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        portPart(helper, helper.makeMockPlayer(GameType.SURVIVAL), new ItemStack(PortContent.PORT_PART_ITEM.get()));
        helper.startSequence().thenIdle(250).thenExecute(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "moved from an empty chest");
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
            chest.setChanged();
        }).thenIdle(10).thenExecute(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 10L,
                "the port did not wake on the chest's change")).thenSucceed();
    }

    /**
     * A port part's filter and export mode go into its item when a sneaking wrench or a break takes it off, and come
     * back when that item goes on another face; the mode is the part's look variant.
     */
    private static void keepsFilterInItem(GameTestHelper helper) {
        network(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        PortPart port = portPart(helper, player, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        port.filter().setItem(4, new ItemStack(Items.COBBLESTONE));
        port.setMode(PortMode.EXPORT);
        CableParts parts = PartGameTests.parts(helper, 2);
        helper.assertTrue(parts.renderKey() instanceof PartLook look && look.faces().getFirst().variant() == 1,
                "the export look");
        CablePart removed = parts.remove(Direction.NORTH);
        helper.assertTrue(removed != null, "nothing came off");
        ItemStack item = removed == null ? ItemStack.EMPTY : removed.toItem();
        helper.assertTrue(item.has(DataComponents.CONTAINER), "the filter is not in the item");
        helper.assertTrue(PartGameTests.usePart(helper, player, 2, Direction.UP, item).consumesAction(),
                "the item went on again");
        if (!(PartGameTests.part(helper, parts, Direction.UP) instanceof PortPart again)) {
            throw helper.assertionException(Component.literal("Not a port part"));
        }
        helper.assertTrue(again.filter().getItem(4).is(Items.COBBLESTONE), "the filter was lost");
        helper.assertValueEqual(again.mode(), PortMode.EXPORT, "the mode was lost");
        ItemStack plain = new PortPart(again.type(), parts, Direction.DOWN, PortTier.TIER_1).toItem();
        helper.assertFalse(plain.has(DataComponents.CONTAINER), "a new part's item carries a filter");
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "port_part_viewer");
        viewer.setPos(helper.absoluteVec(NetworkGameTests.at(2).above().getCenter()));
        PortMenu menu = PortMenu.forPort(1, viewer.getInventory(), again);
        helper.assertTrue(menu.stillValid(viewer), "the GUI would close at once");
        helper.assertFalse(menu.clickMenuButton(viewer, PortMenu.CYCLE_MODE), "a stranger switched the owner's port");
        again.setOwner(OwnerRecord.UNOWNED);
        helper.assertTrue(menu.clickMenuButton(viewer, PortMenu.CYCLE_MODE), "the mode button");
        helper.assertValueEqual(again.mode(), PortMode.STORAGE, "the mode after the button (export, storage)");
        helper.succeed();
    }

    /**
     * A research terminal part on a powered network's cable lets its player use the research screen, which closes once
     * the part comes off.
     */
    private static void researchTerminalOpens(GameTestHelper helper) {
        network(helper);
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(PartGameTests
                .usePart(helper, placer, 2, Direction.UP, new ItemStack(ResearchContent.TERMINAL_PART_ITEM.get()))
                .consumesAction(), "placed");
        CableParts parts = PartGameTests.parts(helper, 2);
        if (!(PartGameTests.part(helper, parts, Direction.UP) instanceof ResearchTerminalPart terminal)) {
            throw helper.assertionException(Component.literal("Not a research terminal part"));
        }
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "research_part_user");
        player.setPos(helper.absoluteVec(NetworkGameTests.at(2).above().getCenter()));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(terminal.networkWorks(), "network not working"))
                .thenExecute(() -> {
                    ResearchMenu menu = ResearchMenu.server(1, player, terminal, view -> {
                    });
                    helper.assertTrue(menu.stillValid(player), "the screen would close at once");
                    parts.remove(Direction.UP);
                    helper.assertFalse(menu.stillValid(player), "the screen stays open without the part");
                    ConduitBlockEntity cable = helper.getBlockEntity(NetworkGameTests.at(2), ConduitBlockEntity.class);
                    helper.assertTrue(cable.shape().bounds().maxY < 1.0, "the part's shape stayed");
                }).thenSucceed();
    }

    /** Crafts {@code input} alone in a grid and returns the result with what the recipe assembled. */
    private static ItemStack craft(GameTestHelper helper, ItemStack input) {
        CraftingInput grid = CraftingInput.of(3, 3, PartGameTests.grid(input.copy()));
        return CrafterBlock.getPotentialResults(helper.getLevel(), grid).map(recipe -> recipe.value().assemble(grid))
                .orElseThrow(() -> helper.assertionException(Component.literal("No recipe for " + input)));
    }

    private static void assertSettings(GameTestHelper helper, ItemStack stack, String step) {
        helper.assertTrue(stack.has(DataComponents.CONTAINER), "no filter in the item " + step);
        helper.assertValueEqual(stack.getOrDefault(ModDataComponents.PORT_MODE.get(), PortMode.IMPORT), PortMode.EXPORT,
                "the item's mode " + step);
    }

    /**
     * A filtered export port block keeps its filter and mode all the way round: broken into its item (whose tooltip
     * names the mode), crafted into the part, put on a cable, taken off, crafted back into the block and placed.
     */
    private static void settingsRoundTrip(GameTestHelper helper) {
        network(helper);
        BlockPos portPos = NetworkGameTests.at(6);
        helper.setBlock(portPos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST));
        PortBlockEntity block = helper.getBlockEntity(portPos, PortBlockEntity.class);
        block.filter().setItem(2, new ItemStack(Items.DIRT));
        block.setMode(PortMode.EXPORT);
        helper.getLevel().destroyBlock(helper.absolutePos(portPos), true);
        ItemEntity dropped = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(portPos)).inflate(2),
                        entity -> entity.getItem().is(PortContent.PORT.item().get()))
                .stream().findFirst()
                .orElseThrow(() -> helper.assertionException(Component.literal("the port dropped nothing")));
        ItemStack blockItem = dropped.getItem().copy();
        dropped.discard();
        assertSettings(helper, blockItem, "after breaking");
        List<Component> lines = new ArrayList<>();
        blockItem.getItem().appendHoverText(blockItem, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(PortContent.MODE_KEY)), "no mode in the tooltip: " + lines);
        ItemStack partItem = craft(helper, blockItem);
        helper.assertTrue(partItem.is(PortContent.PORT_PART_ITEM.get()), "the block crafted into " + partItem);
        assertSettings(helper, partItem, "after crafting the part");
        PortPart part = portPart(helper, helper.makeMockPlayer(GameType.SURVIVAL), partItem);
        helper.assertTrue(part.filter().getItem(2).is(Items.DIRT), "the part lost the filter");
        helper.assertValueEqual(part.mode(), PortMode.EXPORT, "the part's mode");
        CablePart removed = PartGameTests.parts(helper, 2).remove(Direction.NORTH);
        ItemStack takenOff = removed == null ? ItemStack.EMPTY : removed.toItem();
        assertSettings(helper, takenOff, "after taking the part off");
        ItemStack back = craft(helper, takenOff);
        helper.assertTrue(back.is(PortContent.PORT.item().get()), "the part crafted into " + back);
        assertSettings(helper, back, "after crafting the block");
        // The spot is air, so the click places into it.
        BlockPos target = helper.absolutePos(portPos);
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        placer.setItemInHand(InteractionHand.MAIN_HAND, back);
        helper.assertTrue(
                back.useOn(new UseOnContext(helper.getLevel(), placer, InteractionHand.MAIN_HAND, back,
                        new BlockHitResult(target.getCenter(), Direction.UP, target, false))).consumesAction(),
                "placing the block");
        PortBlockEntity placed = helper.getBlockEntity(portPos, PortBlockEntity.class);
        helper.assertTrue(placed.filter().getItem(2).is(Items.DIRT), "the placed block lost the filter");
        helper.assertValueEqual(placed.mode(), PortMode.EXPORT, "the placed block's mode");
        placed.setMode(PortMode.IMPORT);
        ItemStack again = new ItemStack(PortContent.PORT.item().get());
        again.applyComponents(placed.collectComponents());
        helper.assertFalse(again.has(ModDataComponents.PORT_MODE.get()), "a stale mode came back on the item");
        helper.succeed();
    }
}
