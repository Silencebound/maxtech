package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortItems;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.port.PortResearchWaits;
import dev.silencebound.maxtech.storage.port.PortTier;
import dev.silencebound.maxtech.storage.port.PortWorker;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Port tiers and speed cards (M6c area A): each tier's rate as a block and as a part, speed cards in the rate and the
 * GUI, the research gates for tiers 2 and 3, the settings round trip with cards through every form, and the recipes.
 * Layout along x at y = 1, z = 1: the controller at x 1 with a test drive behind it (z 2), a tier-1 cable at x 2, a
 * block port at x 3 facing east into a chest at x 4; a part sits on the cable's north face, its chest north of the
 * cable.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PortTierGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
    private static final BlockPos PORT = new BlockPos(3, 1, 1);
    private static final BlockPos INVENTORY = new BlockPos(4, 1, 1);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final long POWER = 10_000;
    private static final int BATCH = 64;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    /** Tests on the standard research preset. */
    private static final List<String> PLAIN = new ArrayList<>();
    /** Tests with research off: their mock players own what they place, and mock players have no research. */
    private static final List<String> NO_RESEARCH = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(PLAIN, "port_tier_2_rate", helper -> blockRate(helper, PortTier.TIER_2, 64));
        register(PLAIN, "port_tier_3_rate", helper -> blockRate(helper, PortTier.TIER_3, 256));
        register(NO_RESEARCH, "port_tier_2_part_rate", helper -> partRate(helper, PortTier.TIER_2, 64));
        register(NO_RESEARCH, "port_tier_3_part_rate", helper -> partRate(helper, PortTier.TIER_3, 256));
        register(PLAIN, "port_speed_cards_rate", PortTierGameTests::speedCardsRate);
        register(PLAIN, "port_research_gates", PortTierGameTests::researchGates);
        register(NO_RESEARCH, "port_tier_settings_round_trip", PortTierGameTests::settingsRoundTrip);
        register(PLAIN, "port_menu_speed_cards", PortTierGameTests::menuSpeedCards);
        register(PLAIN, "port_tier_recipes_craft", PortTierGameTests::recipesCraft);
    }

    private PortTierGameTests() {
    }

    private static void register(List<String> group, String name, Consumer<GameTestHelper> test) {
        group.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("port_tier"),
                StorageTestEnvironment.plain());
        Holder<TestEnvironmentDefinition<?>> off = event.registerEnvironment(MaxtechStorage.id("port_tier_off"),
                new StorageTestEnvironment("research.preset", "off", false));
        for (String name : PLAIN) {
            registerTest(event, name, plain);
        }
        for (String name : NO_RESEARCH) {
            registerTest(event, name, off);
        }
    }

    private static void registerTest(RegisterGameTestsEvent event, String name,
            Holder<TestEnvironmentDefinition<?>> environment) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(environment, STRUCTURE, 400, 0, true)));
    }

    /**
     * A block port of {@code tier} (the standalone rate {@code perSecond}) on a full chest moves its one-second start
     * credit in its first run (several operations of one batch for tier 3), then its rate: in the next 100 ticks
     * between five seconds' worth less one step and five seconds' worth.
     */
    private static void blockRate(GameTestHelper helper, PortTier tier, long perSecond) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = fullChest(helper, INVENTORY);
        PortBlockEntity port = port(helper, tier, PORT, Direction.EAST);
        assertRate(helper, drive, chest, port.worker(), perSecond);
    }

    /** The same for the part of {@code tier} on the cable's north face. */
    private static void partRate(GameTestHelper helper, PortTier tier, long perSecond) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = fullChest(helper, NetworkGameTests.at(2).north());
        PortPart part = part(helper, helper.makeMockPlayer(GameType.SURVIVAL),
                new ItemStack(PortContent.partItem(tier).get()));
        assertRate(helper, drive, chest, part.worker(), perSecond);
    }

    private static void assertRate(GameTestHelper helper, TestStorage.TestDevice drive, ChestBlockEntity chest,
            PortWorker worker, long perSecond) {
        long step = Math.min(perSecond, BATCH);
        long total = count(chest) + drive.provider.amount(COBBLE);
        long[] start = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            start[0] = drive.provider.amount(COBBLE);
            helper.assertTrue(start[0] > 0, "nothing moved yet");
        }).thenExecute(() -> {
            helper.assertValueEqual(worker.itemsPerSecond(), perSecond, "the worker's rate");
            helper.assertValueEqual(start[0], perSecond, "the start credit in one run");
        }).thenIdle(100).thenExecute(() -> {
            long moved = drive.provider.amount(COBBLE) - start[0];
            helper.assertTrue(moved >= 5 * perSecond - step && moved <= 5 * perSecond,
                    moved + " items in 100 ticks at " + perSecond + " a second");
            helper.assertValueEqual(count(chest) + drive.provider.amount(COBBLE), total, "items in chest and drive");
        }).thenSucceed();
    }

    /**
     * Two speed cards make a tier-1 port four times as fast: 64 items at once, then 256 to 320 in 100 ticks. Rates and
     * operations per second follow tier and cards; a card beyond the configured slots does not count.
     */
    private static void speedCardsRate(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = fullChest(helper, INVENTORY);
        PortBlockEntity port = port(helper, PortTier.TIER_1, PORT, Direction.EAST);
        PortWorker worker = port.worker();
        int slots = StorageServerConfig.PORT_SPEED_CARD_SLOTS.getAsInt();
        helper.assertValueEqual(slots, 2, "configured card slots");
        worker.cards().setItem(0, card());
        helper.assertValueEqual(worker.itemsPerSecond(), 32L, "one card");
        worker.cards().setItem(slots, card());
        helper.assertValueEqual(worker.itemsPerSecond(), 32L, "a card past the slots");
        helper.assertValueEqual(worker.cards().held(), 2, "cards held");
        worker.cards().setItem(slots, ItemStack.EMPTY);
        worker.cards().setItem(1, card());
        helper.assertValueEqual(worker.fluidMbPerSecond(), 4000L, "fluid with two cards");
        helper.assertValueEqual(worker.operationsPerSecond(), 1 + 1, "operations a second, items and fluid");
        PortWorker tier3 = port(helper, PortTier.TIER_3, new BlockPos(6, 1, 6), Direction.UP).worker();
        tier3.cards().setItem(0, card());
        tier3.cards().setItem(1, card());
        helper.assertValueEqual(tier3.itemsPerSecond(), 1024L, "tier 3 with two cards");
        helper.assertValueEqual(tier3.fluidMbPerSecond(), 64_000L, "tier 3 fluid with two cards");
        helper.assertValueEqual(tier3.operationsPerSecond(), 16 + 4, "tier 3's operations a second");
        assertRate(helper, drive, chest, worker, 64);
    }

    /**
     * Tiers 2 and 3 work only once their owner's team has the research: owned by a player without it, both sleep and
     * wait (the menu shows it); completing Port Tier 2 wakes the tier-2 port at once, and the tier-3 port moves only
     * after Port Tier 3. Each node is bought with points.
     */
    private static void researchGates(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "port_gate_owner");
        MinecraftServer server = helper.getLevel().getServer();
        ResearchTeam team = Research.team(owner, true);
        ChestBlockEntity chest2 = chest(helper, INVENTORY, new ItemStack(Items.COBBLESTONE, 64));
        PortBlockEntity tier2 = port(helper, PortTier.TIER_2, PORT, Direction.EAST);
        tier2.setOwner(OwnerRecord.of(owner));
        // The tier-3 port stands on the cable, facing up into its own chest.
        BlockPos up = NetworkGameTests.at(2).above();
        ChestBlockEntity chest3 = chest(helper, up.above(), new ItemStack(Items.DIRT, 64));
        PortBlockEntity tier3 = port(helper, PortTier.TIER_3, up, Direction.UP);
        tier3.setOwner(OwnerRecord.of(owner));
        StackKey dirt = ItemKey.of(Items.DIRT);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(tier3.network() != null, "not in a network"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE) + drive.provider.amount(dirt), 0L,
                            "moved without research");
                    helper.assertTrue(tier2.worker().waitsForResearch() && tier3.worker().waitsForResearch(),
                            "not waiting for research");
                    helper.assertTrue(PortResearchWaits.count() >= 2, "not in the research waits");
                    Player viewer = helper.makeMockPlayer(GameType.SURVIVAL);
                    helper.assertTrue(PortMenu.forPort(1, viewer.getInventory(), tier2).waitsForResearch(),
                            "the menu does not show the wait");
                    Research.addPoints(server, team, 150);
                    helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id("port_tier_2")),
                            Research.Result.DONE, "Port Tier 2 bought");
                }).thenExecuteAfter(5, () -> {
                    helper.assertValueEqual(count(chest2), 0, "cobblestone left after Port Tier 2");
                    helper.assertValueEqual(drive.provider.amount(dirt), 0L, "the tier-3 port moved");
                    helper.assertTrue(tier3.worker().waitsForResearch(), "the tier-3 port stopped waiting");
                    Research.addPoints(server, team, 100 + 250 + 500);
                    for (String node : List.of("family_masks", "advanced_doping", "port_tier_3")) {
                        helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id(node)),
                                Research.Result.DONE, node + " bought");
                    }
                }).thenExecuteAfter(5, () -> {
                    helper.assertValueEqual(count(chest3), 0, "dirt left after Port Tier 3");
                    helper.assertFalse(tier3.worker().waitsForResearch(), "still waiting");
                }).thenSucceed();
    }

    /**
     * A filtered export tier-2 port with two speed cards keeps filter, mode and cards all the way round: broken into
     * its item (whose tooltip names the cards and the research), crafted into the part, put on a cable (four times as
     * fast), taken off, crafted back into the block and placed.
     */
    private static void settingsRoundTrip(GameTestHelper helper) {
        network(helper);
        BlockPos portPos = NetworkGameTests.at(6);
        PortBlockEntity block = port(helper, PortTier.TIER_2, portPos, Direction.EAST);
        block.filter().setItem(2, new ItemStack(Items.DIRT));
        block.setMode(PortMode.EXPORT);
        block.worker().cards().setItem(0, card());
        block.worker().cards().setItem(1, card());
        helper.getLevel().destroyBlock(helper.absolutePos(portPos), true);
        ItemEntity dropped = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(portPos)).inflate(2),
                        entity -> entity.getItem().is(PortContent.PORT_TIER_2.item().get()))
                .stream().findFirst()
                .orElseThrow(() -> helper.assertionException(Component.literal("the port dropped nothing")));
        ItemStack blockItem = dropped.getItem().copy();
        dropped.discard();
        assertSettings(helper, blockItem, "after breaking");
        List<Component> lines = new ArrayList<>();
        blockItem.getItem().appendHoverText(blockItem, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(hasLine(lines, PortContent.CARDS_KEY), "no cards in the tooltip: " + lines);
        helper.assertTrue(hasLine(lines, PortContent.RESEARCH_KEY), "no research in the tooltip: " + lines);
        ItemStack partItem = craft(helper, blockItem);
        helper.assertTrue(partItem.is(PortContent.PORT_TIER_2_PART_ITEM.get()), "the block crafted into " + partItem);
        assertSettings(helper, partItem, "after crafting the part");
        PortPart part = part(helper, helper.makeMockPlayer(GameType.SURVIVAL), partItem);
        helper.assertValueEqual(part.worker().cards().active(), 2, "the part's cards");
        helper.assertValueEqual(part.worker().itemsPerSecond(), 256L, "the part's rate");
        helper.assertTrue(part.filter().getItem(2).is(Items.DIRT), "the part lost the filter");
        CablePart removed = PartGameTests.parts(helper, 2).remove(Direction.NORTH);
        ItemStack takenOff = removed == null ? ItemStack.EMPTY : removed.toItem();
        assertSettings(helper, takenOff, "after taking the part off");
        ItemStack back = craft(helper, takenOff);
        helper.assertTrue(back.is(PortContent.PORT_TIER_2.item().get()), "the part crafted into " + back);
        BlockPos target = helper.absolutePos(portPos);
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        placer.setItemInHand(InteractionHand.MAIN_HAND, back);
        helper.assertTrue(
                back.useOn(new UseOnContext(helper.getLevel(), placer, InteractionHand.MAIN_HAND, back,
                        new BlockHitResult(target.getCenter(), Direction.UP, target, false))).consumesAction(),
                "placing the block");
        PortBlockEntity placed = helper.getBlockEntity(portPos, PortBlockEntity.class);
        helper.assertValueEqual(placed.worker().cards().active(), 2, "the placed block's cards");
        helper.assertValueEqual(placed.mode(), PortMode.EXPORT, "the placed block's mode");
        ItemStack fresh = craft(helper, new ItemStack(PortContent.PORT_TIER_3.item().get()));
        helper.assertFalse(fresh.has(ModDataComponents.PORT_SPEED_CARDS.get()), "a fresh part carries cards");
        helper.succeed();
    }

    /**
     * The GUI's card slots: two (the config), after the inventory; only speed cards go in, one each. A shift-clicked
     * stack of three cards fills both and keeps one; a shift-click on a card slot gives it back. Cards survive saving.
     */
    private static void menuSpeedCards(GameTestHelper helper) {
        PortBlockEntity port = port(helper, PortTier.TIER_1, PORT, Direction.EAST);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        PortMenu menu = PortMenu.forPort(1, player.getInventory(), port);
        helper.assertValueEqual(menu.cardSlots(), 2, "card slots");
        helper.assertValueEqual(menu.slots.size(), PortMenu.FIRST_CARD_SLOT + 2, "slots in the menu");
        helper.assertFalse(menu.slots.get(PortMenu.FIRST_CARD_SLOT).mayPlace(new ItemStack(Items.DIRT)),
                "dirt in a card slot");
        player.getInventory().setItem(0, new ItemStack(PortContent.SPEED_CARD.get(), 3));
        // The hotbar comes after the 27 main slots, behind the 9 filter slots.
        menu.clicked(9 + 27, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertValueEqual(port.worker().cards().active(), 2, "cards in the port");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 1, "cards left in the inventory");
        helper.assertTrue(port.filter().isBlank(), "a card named in the filter");
        helper.assertValueEqual(port.worker().itemsPerSecond(), 64L, "rate with two cards");
        menu.clicked(PortMenu.FIRST_CARD_SLOT, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertValueEqual(port.worker().cards().active(), 1, "a card taken out");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 2, "cards back in the inventory");
        CompoundTag saved = port.saveCustomOnly(helper.getLevel().registryAccess());
        PortBlockEntity copy = new PortBlockEntity(port.getBlockPos(), port.getBlockState());
        copy.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertValueEqual(copy.worker().cards().held(), 1, "cards after loading");
        helper.assertValueEqual(PortItems.cards(port.collectComponents()), 1, "cards in the item's components");
        helper.succeed();
    }

    /** Tier 2, tier 3 and the speed card craft from Storage's parts in the vanilla Crafter's lookup. */
    private static void recipesCraft(GameTestHelper helper) {
        ItemStack plate = new ItemStack(MaterialContent.CASING_PLATE.get());
        ItemStack hopper = new ItemStack(Items.HOPPER);
        ItemStack signal = new ItemStack(MaterialContent.BASIC_SIGNAL_PROCESSOR.get());
        ItemStack io = new ItemStack(MaterialContent.BASIC_IO_PROCESSOR.get());
        ItemStack fibre = new ItemStack(MaterialContent.GLASS_FIBRE.get());
        TerminalGameTests.crafts(helper, List.of(plate, hopper, plate, signal, io, signal, plate, fibre, plate),
                PortContent.PORT_TIER_2.item().get());
        ItemStack wire = new ItemStack(TierTwoMaterialContent.SUPERCONDUCTOR_WIRE.get());
        TerminalGameTests.crafts(helper,
                List.of(plate, hopper, plate, wire, new ItemStack(TierTwoMaterialContent.ADVANCED_IO_PROCESSOR.get()),
                        wire, plate, new ItemStack(TierTwoMaterialContent.ADVANCED_SIGNAL_PROCESSOR.get()), plate),
                PortContent.PORT_TIER_3.item().get());
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        TerminalGameTests.crafts(helper, List.of(ItemStack.EMPTY, new ItemStack(MaterialContent.OSCILLATOR.get()),
                ItemStack.EMPTY, redstone, signal, redstone, plate, plate, plate), PortContent.SPEED_CARD.get());
        helper.succeed();
    }

    private static boolean hasLine(List<Component> lines, String key) {
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static void assertSettings(GameTestHelper helper, ItemStack stack, String step) {
        helper.assertTrue(stack.has(DataComponents.CONTAINER), "no filter in the item " + step);
        helper.assertValueEqual(stack.getOrDefault(ModDataComponents.PORT_MODE.get(), PortMode.IMPORT), PortMode.EXPORT,
                "the item's mode " + step);
        helper.assertValueEqual(PortItems.cards(stack), 2, "the item's cards " + step);
    }

    private static ItemStack craft(GameTestHelper helper, ItemStack input) {
        CraftingInput grid = CraftingInput.of(3, 3, PartGameTests.grid(input.copy()));
        return CrafterBlock.getPotentialResults(helper.getLevel(), grid).map(recipe -> recipe.value().assemble(grid))
                .orElseThrow(() -> helper.assertionException(Component.literal("No recipe for " + input)));
    }

    private static ItemStack card() {
        return new ItemStack(PortContent.SPEED_CARD.get());
    }

    /** A powered controller with a test drive behind it and the cable at x 2. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, POWER);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return drive;
    }

    private static PortBlockEntity port(GameTestHelper helper, PortTier tier, BlockPos pos, Direction facing) {
        helper.setBlock(pos, PortContent.block(tier).get().defaultBlockState().setValue(PortBlock.FACING, facing));
        return helper.getBlockEntity(pos, PortBlockEntity.class);
    }

    private static PortPart part(GameTestHelper helper, Player player, ItemStack stack) {
        helper.assertTrue(PartGameTests.usePart(helper, player, 2, Direction.NORTH, stack).consumesAction(),
                "the port part was not placed");
        if (PartGameTests.part(helper, PartGameTests.parts(helper, 2), Direction.NORTH) instanceof PortPart port) {
            return port;
        }
        throw helper.assertionException(Component.literal("Not a port part"));
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos, ItemStack... stacks) {
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        return chest;
    }

    private static ChestBlockEntity fullChest(GameTestHelper helper, BlockPos pos) {
        ChestBlockEntity chest = chest(helper, pos);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        return chest;
    }

    private static int count(ChestBlockEntity chest) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            total += chest.getItem(i).getCount();
        }
        return total;
    }
}
