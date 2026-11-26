package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.SyncedListUpdate;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartLook;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalPart;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Cable parts (M6b area P): a part goes on any face of a storage cable from its item, joins the cable's network as a
 * device of its own and cuts the cable's link on its face; the terminal part shows the network; breaking the part keeps
 * the cable, breaking the cable drops every part; parts survive a save and reach clients in the update packet; the part
 * and block forms convert 1:1. Layouts run along x at y = 1, z = 1: controller, then cables.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks and part types are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class PartGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"part_place_on_each_face", "part_cuts_cable_link",
            "part_terminal_lists_network", "part_break_keeps_cable", "part_save_and_sync_round_trip",
            "part_conversion_recipes"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], PartGameTests::placeOnEachFace);
        register(NAMES[1], PartGameTests::cutsCableLink);
        register(NAMES[2], PartGameTests::terminalListsNetwork);
        register(NAMES[3], PartGameTests::breakKeepsCable);
        register(NAMES[4], PartGameTests::saveAndSyncRoundTrip);
        register(NAMES[5], PartGameTests::conversionRecipes);
    }

    private PartGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("part"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    private static StorageCableConduitType tier1() {
        return CableContent.STORAGE_CABLE_TIER_1.type().get();
    }

    private static ConduitBlockEntity cable(GameTestHelper helper, int x) {
        return helper.getBlockEntity(NetworkGameTests.at(x), ConduitBlockEntity.class);
    }

    static CableParts parts(GameTestHelper helper, int x) {
        return partsOf(helper, cable(helper, x));
    }

    private static CableParts partsOf(GameTestHelper helper, ConduitBlockEntity cable) {
        if (cable.conduit().state() instanceof CableParts parts) {
            return parts;
        }
        throw helper.assertionException(Component.literal("Storage cable without parts state"));
    }

    /** The part on {@code face}; fails the test (to be retried) while there is none. */
    static CablePart part(GameTestHelper helper, CableParts parts, Direction face) {
        CablePart part = parts.part(face);
        if (part == null) {
            throw helper.assertionException(Component.literal("No part on " + face));
        }
        return part;
    }

    /**
     * Puts a terminal part on {@code side} of the cable at {@code x} the way a player does: the item used on the core's
     * face on that side. Returns the item's answer; the stack shrinks by what was placed.
     */
    static InteractionResult usePart(GameTestHelper helper, Player player, int x, Direction side, ItemStack stack) {
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(x));
        // The tier-1 core is 2 px wide: its faces lie a pixel from the block's centre.
        Vec3 location = abs.getCenter().relative(side, 1.0 / 16.0);
        BlockHitResult hit = new BlockHitResult(location, side, abs, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack.useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        // Survival: placing uses up the item.
        player.getAbilities().instabuild = false;
        player.setPos(helper.absoluteVec(NetworkGameTests.at(2).above().getCenter()));
        return player;
    }

    /**
     * On a cable linked west to the controller, a terminal part goes on each other face from its item: one item each,
     * each a device of the controller's network on the cable's node, the hit shape growing to the panel; a second part
     * on a taken face and a part on a plain block are refused. Taking each off again leaves the network as it was. The
     * cable registers no ticker.
     */
    private static void placeOnEachFace(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2, tier1());
        // A mock player: refusals write to the action bar, which needs a connection on a server player.
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Direction[] faces = {Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.EAST};
        int[] devicesBefore = new int[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(2)))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    devicesBefore[0] = network.deviceCount();
                    helper.assertTrue(helper.getBlockState(NetworkGameTests.at(2)).getTicker(helper.getLevel(),
                            BlockEntityType.FURNACE) == null, "the cable has a ticker");
                    ItemStack stack = new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get(), faces.length + 1);
                    for (Direction face : faces) {
                        int count = stack.getCount();
                        helper.assertTrue(usePart(helper, player, 2, face, stack).consumesAction(),
                                "placing on " + face);
                        helper.assertValueEqual(stack.getCount(), count - 1, "items used for " + face);
                        CablePart part = part(helper, parts(helper, 2), face);
                        helper.assertTrue(part instanceof TerminalPart, "no terminal part on " + face);
                        helper.assertTrue(part.deviceLink().network() == network, face + " part not in the network");
                        AABB bounds = cable(helper, 2).shape().bounds();
                        helper.assertTrue(bounds.max(face.getAxis()) - bounds.min(face.getAxis()) >= 9.0 / 16.0,
                                "the shape does not reach the " + face + " part: " + bounds);
                    }
                    helper.assertValueEqual(network.deviceCount(), devicesBefore[0] + faces.length, "devices");
                    helper.assertFalse(usePart(helper, player, 2, Direction.NORTH, stack).consumesAction(),
                            "a second part on a taken face");
                    helper.assertValueEqual(stack.getCount(), 1, "the refused item stays");
                    BlockPos stone = NetworkGameTests.at(5);
                    helper.setBlock(stone, Blocks.STONE);
                    BlockPos abs = helper.absolutePos(stone);
                    helper.assertFalse(
                            stack.useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                                    new BlockHitResult(abs.getCenter(), Direction.UP, abs, false))).consumesAction(),
                            "a part on stone");
                    helper.assertValueEqual(stack.getCount(), 1, "nothing used on stone");
                    for (Direction face : faces) {
                        CablePart removed = parts(helper, 2).remove(face);
                        helper.assertTrue(
                                removed != null && removed.isRemoved() && removed.deviceLink().network() == null,
                                face + " part still in a network");
                    }
                    helper.assertValueEqual(network.deviceCount(), devicesBefore[0], "devices after removal");
                    helper.assertValueEqual(parts(helper, 2).deviceCount(), 0, "parts left");
                }).thenSucceed();
    }

    /**
     * A part on a face cuts the cable's link there: a device east of the cable leaves the controller's network when a
     * part covers that face, a cable beyond a part leaves it too, and both come back when the parts come off.
     */
    private static void cutsCableLink(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 3, tier1());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(4));
        ServerPlayer player = player(helper, "part_cutter");
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper
                                .assertTrue(device.network() == NetworkGameTests.state(helper, NetworkGameTests.at(1)),
                                        "not formed"))
                .thenExecute(() -> helper.assertTrue(
                        usePart(helper, player, 3, Direction.EAST,
                                new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get())).consumesAction(),
                        "placed east"))
                .thenWaitUntil(
                        () -> helper
                                .assertTrue(device.network() != NetworkGameTests.state(helper, NetworkGameTests.at(1)),
                                        "the device still links through the part's face"))
                .thenExecute(() -> helper.assertTrue(
                        usePart(helper, player, 2, Direction.EAST,
                                new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get())).consumesAction(),
                        "placed between"))
                .thenWaitUntil(
                        () -> helper.assertTrue(
                                NetworkGameTests.network(helper, NetworkGameTests.at(3)) != NetworkGameTests
                                        .state(helper, NetworkGameTests.at(1)),
                                "the cables still link through the part's face"))
                .thenExecute(() -> {
                    helper.assertTrue(part(helper, parts(helper, 3), Direction.EAST).deviceLink()
                            .network() != NetworkGameTests.state(helper, NetworkGameTests.at(1)),
                            "the cut-off cable's part stayed behind");
                    parts(helper, 2).remove(Direction.EAST);
                    parts(helper, 3).remove(Direction.EAST);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        device.network() == NetworkGameTests.state(helper, NetworkGameTests.at(1)), "not linked again"))
                .thenSucceed();
    }

    /**
     * A terminal part on the cable next to a powered controller and a test drive shows the drive's cobblestone in its
     * GUI's dump and takes a stack out through the network, like the block. Its info line names it.
     */
    private static void terminalListsNetwork(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 10_000);
        NetworkGameTests.cables(helper, 2, 2, tier1());
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        ServerPlayer player = player(helper, "part_viewer");
        List<SyncedListUpdate<StackKey>> sent = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        drive.network() == NetworkGameTests.state(helper, NetworkGameTests.at(1)), "not formed"))
                .thenExecute(() -> {
                    drive.provider.setDirect(COBBLE, 100);
                    helper.assertTrue(
                            usePart(helper, player, 2, Direction.UP,
                                    new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get())).consumesAction(),
                            "placed");
                    TerminalPart part = (TerminalPart) part(helper, parts(helper, 2), Direction.UP);
                    helper.assertTrue(part.stillValid(player), "the menu would close at once");
                    TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), part, sent::add);
                    player.containerMenu = menu;
                    helper.assertTrue(TerminalGameTests.click(menu, player, TerminalAction.Kind.REFRESH, -1),
                            "refresh");
                    menu.broadcastChanges();
                    ClientSyncedList<StackKey> client = TerminalGameTests.replay(helper, sent);
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    int id = network.contents().keyId(COBBLE);
                    helper.assertValueEqual(client.count(id), 100L, "the dump shows the drive's cobblestone");
                    helper.assertTrue(TerminalGameTests.click(menu, player, TerminalAction.Kind.TAKE_STACK, id),
                            "take a stack");
                    helper.assertValueEqual(menu.getCarried().getCount(), 64, "on the cursor");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 36L, "left in the drive");
                    menu.setCarried(ItemStack.EMPTY);
                    menu.removed(player);
                    helper.assertValueEqual(part.openMenus(), 0, "the closed menu still watches");
                    List<String> keys = new ArrayList<>();
                    BlockPos abs = helper.absolutePos(NetworkGameTests.at(2));
                    tier1().addInfo(new BlockInfoRequest(helper.getLevel(), abs, Direction.UP,
                            abs.getCenter().add(0, 0.5, 0), BlockInfoRequest.Detail.BRIEF), new BlockInfoSink() {
                                @Override
                                public void line(Component line) {
                                    if (line.getContents() instanceof TranslatableContents contents) {
                                        keys.add(contents.getKey());
                                    }
                                }
                            });
                    helper.assertTrue(keys.contains(TerminalContent.TERMINAL_PART_ITEM.get().getDescriptionId()),
                            "no part name: " + keys);
                    helper.assertFalse(keys.contains(NetworkInfo.CABLE_KEY), "the cable's line for a part: " + keys);
                }).thenSucceed();
    }

    /**
     * Breaking with the crosshair on a part takes only the part off and drops it, as does pick-block's answer; then
     * breaking the cable drops the cable and every part left on it.
     */
    private static void breakKeepsCable(GameTestHelper helper) {
        NetworkGameTests.cables(helper, 2, 2, tier1());
        ServerPlayer player = player(helper, "part_breaker");
        BlockPos pos = NetworkGameTests.at(2);
        BlockPos abs = helper.absolutePos(pos);
        helper.assertTrue(
                usePart(helper, player, 2, Direction.UP, new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get()))
                        .consumesAction(),
                "placed up");
        helper.assertTrue(
                usePart(helper, player, 2, Direction.NORTH, new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get()))
                        .consumesAction(),
                "placed north");
        // Two blocks above, looking straight down at the upper part.
        player.setPos(abs.getX() + 0.5, abs.getY() + 2.0, abs.getZ() + 0.5);
        player.setXRot(90.0F);
        BlockState state = helper.getBlockState(pos);
        helper.assertTrue(state.getCloneItemStack(abs, helper.getLevel(), false, player)
                .is(TerminalContent.TERMINAL_PART_ITEM.get()), "pick-block on the part");
        boolean removed = state.onDestroyedByPlayer(helper.getLevel(), abs, player, ItemStack.EMPTY, true,
                helper.getLevel().getFluidState(abs));
        helper.assertFalse(removed, "the cable was removed with the part");
        helper.assertTrue(parts(helper, 2).part(Direction.UP) == null, "the part is still on");
        helper.assertTrue(parts(helper, 2).part(Direction.NORTH) != null, "the other part came off too");
        helper.assertItemEntityCountIs(TerminalContent.TERMINAL_PART_ITEM.get(), pos, 1.0, 1);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        helper.getLevel().destroyBlock(abs, true);
        helper.assertTrue(helper.getBlockState(pos).isAir(), "the cable is still there");
        helper.assertItemEntityCountIs(TerminalContent.TERMINAL_PART_ITEM.get(), pos, 1.0, 1);
        helper.assertItemEntityPresent(CableContent.STORAGE_CABLE_TIER_1.item().get(), pos, 1.0);
        helper.succeed();
    }

    /**
     * A cable's parts and their owner come back from its saved data into a fresh block entity; the update packet
     * carries the parts without the owner, and a client block entity built from it has the same parts and look.
     */
    private static void saveAndSyncRoundTrip(GameTestHelper helper) {
        NetworkGameTests.cables(helper, 2, 2, tier1());
        ServerPlayer player = player(helper, "part_owner");
        usePart(helper, player, 2, Direction.UP, new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get()));
        usePart(helper, player, 2, Direction.SOUTH, new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get()));
        ConduitBlockEntity cable = cable(helper, 2);
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(2));
        BlockState state = helper.getBlockState(NetworkGameTests.at(2));
        CompoundTag saved = cable.saveCustomOnly(helper.getLevel().registryAccess());
        ConduitBlockEntity loaded = new ConduitBlockEntity(abs, state);
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        CableParts loadedParts = partsOf(helper, loaded);
        helper.assertValueEqual(loadedParts.deviceCount(), 2, "parts after loading");
        CablePart up = part(helper, loadedParts, Direction.UP);
        helper.assertTrue(up instanceof TerminalPart && up.ownerRecord().isOwned()
                && "part_owner".equals(up.ownerRecord().ownerName()), "the up part or its owner after loading");
        helper.assertTrue(loaded.shape().bounds().maxY >= 1.0 - 1.0E-6, "the shape after loading");
        CompoundTag update = cable.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertFalse(update.toString().contains("part_owner"), "the owner went to clients");
        ConduitBlockEntity client = new ConduitBlockEntity(abs, state);
        client.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), update));
        CableParts clientParts = partsOf(helper, client);
        helper.assertTrue(
                clientParts.part(Direction.SOUTH) instanceof TerminalPart
                        && clientParts.part(Direction.UP) instanceof TerminalPart && clientParts.deviceCount() == 2,
                "parts from the update packet");
        helper.assertTrue(clientParts.renderKey() instanceof PartLook look && look.faces().size() == 2,
                "the client's look");
        helper.assertValueEqual(client.shape().bounds(), cable.shape().bounds(), "the client's shape");
        helper.succeed();
    }

    /**
     * Every part type's item converts one for one into a block form in a crafting grid, and that block's item back into
     * the part: the terminal, the port and the research terminal by name, every other registered part type by rule.
     */
    private static void conversionRecipes(GameTestHelper helper) {
        TerminalGameTests.crafts(helper, grid(new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get())),
                TerminalContent.TERMINAL.item().get());
        TerminalGameTests.crafts(helper, grid(new ItemStack(PortContent.PORT_PART_ITEM.get())),
                PortContent.PORT.item().get());
        TerminalGameTests.crafts(helper, grid(new ItemStack(ResearchContent.TERMINAL_PART_ITEM.get())),
                ResearchContent.TERMINAL.item().get());
        for (PartType type : PartTypes.all()) {
            if (type.isInLine()) {
                // A part that sits in a cable's line (the toggle bus) has no block form.
                continue;
            }
            CraftingInput input = CraftingInput.of(3, 3, grid(new ItemStack(type.item())));
            ItemStack block = CrafterBlock.getPotentialResults(helper.getLevel(), input)
                    .map(recipe -> recipe.value().assemble(input)).orElse(ItemStack.EMPTY);
            helper.assertTrue(block.getCount() == 1 && block.getItem() instanceof BlockItem,
                    type.id() + " does not craft into a block form: " + block);
            TerminalGameTests.crafts(helper, grid(new ItemStack(block.getItem())), type.item());
        }
        helper.succeed();
    }

    static List<ItemStack> grid(ItemStack first) {
        List<ItemStack> grid = new ArrayList<>();
        grid.add(first);
        for (int i = 1; i < 9; i++) {
            grid.add(ItemStack.EMPTY);
        }
        return grid;
    }
}
