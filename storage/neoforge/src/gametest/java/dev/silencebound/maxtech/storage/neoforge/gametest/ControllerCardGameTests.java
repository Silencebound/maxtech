package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ClusterStats;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.card.ControllerCards;
import dev.silencebound.maxtech.storage.controller.card.UpgradeCardItem;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The controller's upgrade cards (M7R item 2): the slots grow and shrink with the multiblock and no card is lost or
 * doubled, each kind counts only up to its limit, the batch amplifier makes device operations larger, breaking the
 * primary hands the cards over and the last block drops them, the slots need research, the cards are saved, and the old
 * expansion blocks turn into cards.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ControllerCardGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("controller_cards_slots_follow_size", ControllerCardGameTests::slotsFollowSize);
        register("controller_cards_kind_limits", ControllerCardGameTests::kindLimits);
        register("controller_cards_batch_size", ControllerCardGameTests::batchSize);
        register("controller_cards_primary_break_hands_over", ControllerCardGameTests::primaryBreakHandsOver);
        register("controller_cards_drop_with_last_block", ControllerCardGameTests::dropWithLastBlock);
        register("controller_cards_need_research", ControllerCardGameTests::needResearch);
        register("controller_cards_saved", ControllerCardGameTests::saved);
        register("controller_cards_from_expansion_blocks", ControllerCardGameTests::fromExpansionBlocks);
        register("controller_cards_merge_respects_limits", ControllerCardGameTests::mergeRespectsLimits);
    }

    private ControllerCardGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller_cards"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(plain, STRUCTURE, 400, 0, true)));
        }
    }

    /** Installs {@code count} cards of {@code kind} in the controller at {@code core}; fails when one is refused. */
    static void install(GameTestHelper helper, BlockPos core, CardKind kind, int count) {
        ControllerCards cards = NetworkGameTests.controllerEntity(helper, core).cards();
        for (int i = 0; i < count; i++) {
            if (!cards.insert(card(kind), ClusterStats.Cards.fromConfig()).isEmpty()) {
                throw helper.assertionException(Component.literal("card " + i + " of " + kind + " refused"));
            }
        }
    }

    private static ItemStack card(CardKind kind) {
        return new ItemStack(CardContent.item(kind));
    }

    /** Cards lying as items within two blocks of {@code pos}. */
    static int droppedCards(GameTestHelper helper, BlockPos pos) {
        int count = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2))) {
            if (entity.getItem().getItem() instanceof UpgradeCardItem) {
                count += entity.getItem().getCount();
            }
        }
        return count;
    }

    /** Cards in all slots of {@code cards}, usable or not. */
    private static int held(ControllerCards cards) {
        int count = 0;
        for (int i = 0; i < cards.getContainerSize(); i++) {
            count += cards.getItem(i).getCount();
        }
        return count;
    }

    private static void placeAll(GameTestHelper helper, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            helper.setBlock(pos, ControllerContent.CONTROLLER.get());
        }
    }

    /**
     * One block has 4 slots, three blocks 6. With six cards installed, breaking the east block leaves 5 slots: the card
     * in the sixth slot drops where the block was. With a free slot, breaking the next block moves the card from the
     * fifth slot into it instead. Cards held plus cards dropped stay six throughout.
     */
    private static void slotsFollowSize(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos middle = core.east();
        BlockPos east = middle.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "one block: 4 slots"))
                .thenExecute(() -> placeAll(helper, List.of(middle, east)))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 6, "three blocks: 6 slots"))
                .thenExecute(() -> {
                    install(helper, core, CardKind.BATCH_AMPLIFIER, 4);
                    install(helper, core, CardKind.EFFICIENCY_CORE, 2);
                    helper.getLevel().destroyBlock(helper.absolutePos(east), false);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 5, "two blocks: 5 slots"))
                .thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    ControllerCards cards = controller.cards();
                    helper.assertValueEqual(held(cards), 5, "five cards held");
                    helper.assertTrue(cards.getItem(5).isEmpty(), "the sixth slot is empty");
                    helper.assertValueEqual(droppedCards(helper, east), 1, "one card dropped at the broken block");
                    helper.assertValueEqual(controller.cards(CardKind.EFFICIENCY_CORE), 1, "the dropped core");
                    helper.assertValueEqual(controller.stats().energyPercent(), 90, "one core counts now");
                    clearDrops(helper, east);
                    // Free the second slot: the card in the fifth moves there when the slots shrink again.
                    cards.removeItem(1, 1);
                    helper.getLevel().destroyBlock(helper.absolutePos(middle), false);
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).cards().usable(),
                                4, "one block: 4 slots"))
                .thenExecute(() -> {
                    ControllerCards cards = NetworkGameTests.controllerEntity(helper, core).cards();
                    helper.assertValueEqual(held(cards), 4, "four cards held");
                    helper.assertFalse(cards.getItem(1).isEmpty(), "the freed slot took the card");
                    helper.assertTrue(cards.getItem(4).isEmpty(), "the fifth slot is empty");
                    helper.assertValueEqual(droppedCards(helper, middle), 0, "nothing dropped");
                }).thenSucceed();
    }

    private static void clearDrops(GameTestHelper helper, BlockPos pos) {
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2))) {
            entity.discard();
        }
    }

    /**
     * Through the menu: a second surge regulator and a fifth batch amplifier are refused, a card goes only into a
     * usable slot, the efficiency core needs its research and a stranger without configure access changes nothing.
     * Cards put past the limit by other means count only up to it.
     */
    private static void kindLimits(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(core.east(), core.east(2))))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).cards().usable(),
                                6, "6 slots"))
                .thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "card_owner");
                    controller.setOwner(OwnerRecord.of(owner));
                    ControllerMenu menu = ControllerMenu.server(5, controller, owner, payload -> {
                    });
                    helper.assertFalse(menu.slots.get(0).mayPlace(card(CardKind.SURGE_REGULATOR)),
                            "a regulator needs its research");
                    helper.assertFalse(menu.slots.get(0).mayPlace(card(CardKind.EFFICIENCY_CORE)),
                            "an efficiency core needs its research");
                    helper.assertFalse(menu.slots.get(6).mayPlace(card(CardKind.BATCH_AMPLIFIER)),
                            "the seventh slot is locked");
                    helper.assertFalse(menu.slots.get(0).mayPlace(new ItemStack(Items.STONE)), "only cards");
                    for (int i = 0; i < 4; i++) {
                        helper.assertTrue(menu.slots.get(i).mayPlace(card(CardKind.BATCH_AMPLIFIER)), "amplifier " + i);
                        menu.slots.get(i).set(card(CardKind.BATCH_AMPLIFIER));
                    }
                    helper.assertFalse(menu.slots.get(4).mayPlace(card(CardKind.BATCH_AMPLIFIER)),
                            "a fifth amplifier refused");
                    // One surge regulator counts: the slots take a second one by no way.
                    ClusterStats.Cards limits = ClusterStats.Cards.fromConfig();
                    helper.assertTrue(controller.cards().insert(card(CardKind.SURGE_REGULATOR), limits).isEmpty(),
                            "a regulator");
                    helper.assertFalse(controller.cards().insert(card(CardKind.SURGE_REGULATOR), limits).isEmpty(),
                            "a second regulator refused");
                    // Shift-clicking a fifth amplifier from the inventory finds no slot either.
                    int inventoryStart = ClusterStats.Cards.MOST;
                    menu.slots.get(inventoryStart).set(card(CardKind.BATCH_AMPLIFIER));
                    menu.clicked(inventoryStart, 0, ContainerInput.QUICK_MOVE, owner);
                    helper.assertFalse(menu.slots.get(inventoryStart).getItem().isEmpty(), "stays in the inventory");
                    helper.assertValueEqual(controller.stats().batchPercent(), 200, "four amplifiers: +100 %");
                    helper.assertValueEqual(controller.stats().surgeReservePercent(), 10, "the regulator");
                    ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "card_stranger");
                    ControllerMenu other = ControllerMenu.server(6, controller, stranger, payload -> {
                    });
                    helper.assertFalse(other.slots.get(1).mayPickup(stranger), "a stranger takes nothing");
                    helper.assertValueEqual(other.quickMoveStack(stranger, 1), ItemStack.EMPTY,
                            "a stranger moves nothing out");
                    // Past the limit by other means (a lowered config, a command): only four count.
                    controller.cards().setItem(5, card(CardKind.BATCH_AMPLIFIER));
                    helper.assertValueEqual(controller.cards(CardKind.BATCH_AMPLIFIER), 5, "five installed");
                    helper.assertValueEqual(controller.stats().batchPercent(), 200, "still +100 %");
                }).thenSucceed();
    }

    /** Two batch amplifier cards make a device operation carry 96 items instead of 64; taking them out, 64 again. */
    private static void batchSize(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(device.network() == network, "network formed");
            helper.assertValueEqual(transfer(helper, device).batchLimit(StackKey.Kind.ITEM), 64L, "plain batch");
        }).thenExecute(() -> install(helper, core, CardKind.BATCH_AMPLIFIER, 2)).thenExecute(() -> {
            helper.assertValueEqual(transfer(helper, device).batchLimit(StackKey.Kind.ITEM), 96L, "two amplifiers");
            NetworkGameTests.controllerEntity(helper, core).cards().takeAll(stack -> {
            });
            helper.assertValueEqual(transfer(helper, device).batchLimit(StackKey.Kind.ITEM), 64L, "cards out");
        }).thenSucceed();
    }

    private static StorageTransfer transfer(GameTestHelper helper, TestStorage.TestDevice device) {
        StorageTransfer transfer = device.deviceLink().transfer();
        if (transfer == null) {
            throw helper.assertionException(Component.literal("device in no network"));
        }
        return transfer;
    }

    /**
     * Breaking the primary of three blocks hands its cards to the next block in the same slots; the two blocks left
     * have 5 slots, so all four cards still fit and none drops.
     */
    private static void primaryBreakHandsOver(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos next = core.east();
        BlockPos last = next.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(next, last)))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 6, "6 slots"))
                .thenExecute(() -> {
                    install(helper, core, CardKind.BATCH_AMPLIFIER, 2);
                    install(helper, core, CardKind.EFFICIENCY_CORE, 1);
                    install(helper, core, CardKind.SURGE_REGULATOR, 1);
                    helper.getLevel().destroyBlock(helper.absolutePos(core), true);
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity successor = helper.getBlockEntity(next, ControllerBlockEntity.class);
                    helper.assertTrue(successor.isPrimary(), "the next block took over");
                    helper.assertValueEqual(successor.cards().usable(), 5, "two blocks: 5 slots");
                }).thenExecute(() -> {
                    ControllerBlockEntity successor = helper.getBlockEntity(next, ControllerBlockEntity.class);
                    helper.assertValueEqual(held(successor.cards()), 4, "all four cards moved");
                    helper.assertValueEqual(successor.cards(CardKind.BATCH_AMPLIFIER), 2, "amplifiers");
                    helper.assertValueEqual(successor.stats().surgeReservePercent(), 10, "the regulator works");
                    helper.assertValueEqual(successor.stats().energyPercent(), 90, "the core works");
                    helper.assertValueEqual(droppedCards(helper, core), 0, "no card dropped");
                }).thenSucceed();
    }

    /**
     * Controller A (placed first) with three batch amplifiers and controller B with two amplifiers and a surge
     * regulator merge when a block joins them: A's three blocks have six slots, but only four amplifiers count, so B's
     * regulator and one amplifier go in and its other amplifier drops where B was. Six cards in all, none doubled.
     */
    private static void mergeRespectsLimits(GameTestHelper helper) {
        BlockPos a = new BlockPos(5, 1, 5);
        BlockPos gap = a.east();
        BlockPos b = gap.east();
        helper.setBlock(a, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2).thenExecute(() -> helper.setBlock(b, ControllerContent.CONTROLLER.get()))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, a).cards().usable(), 4, "A");
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, b).cards().usable(), 4, "B");
                }).thenExecute(() -> {
                    install(helper, a, CardKind.BATCH_AMPLIFIER, 3);
                    install(helper, b, CardKind.BATCH_AMPLIFIER, 2);
                    install(helper, b, CardKind.SURGE_REGULATOR, 1);
                    helper.setBlock(gap, ControllerContent.CONTROLLER.get());
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, a);
                    helper.assertValueEqual(controller.blockCount(), 3, "merged");
                    helper.assertValueEqual(controller.cards().usable(), 6, "six slots");
                }).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, a);
                    helper.assertValueEqual(controller.cards(CardKind.BATCH_AMPLIFIER), 4, "four amplifiers");
                    helper.assertValueEqual(controller.cards(CardKind.SURGE_REGULATOR), 1, "the regulator");
                    helper.assertValueEqual(held(controller.cards()), 5, "five held");
                    helper.assertValueEqual(held(helper.getBlockEntity(b, ControllerBlockEntity.class).cards()), 0,
                            "B holds none");
                    helper.assertValueEqual(droppedCards(helper, b), 1, "one amplifier dropped at B");
                }).thenSucceed();
    }

    /** Breaking a controller's only block drops its cards next to its item; no card stays behind. */
    private static void dropWithLastBlock(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenWaitUntil(() -> helper
                .assertValueEqual(NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "4 slots"))
                .thenExecute(() -> {
                    install(helper, core, CardKind.BATCH_AMPLIFIER, 3);
                    helper.getLevel().destroyBlock(helper.absolutePos(core), true);
                    helper.assertValueEqual(droppedCards(helper, core), 3, "three cards dropped");
                }).thenSucceed();
    }

    /**
     * An owned controller has no card slots until the owner's team completes Upgrade Cards; then it has four. A card
     * installed while it had them drops when the slots go.
     */
    private static void needResearch(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "card_research");
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "unowned: 4 slots"))
                .thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    install(helper, core, CardKind.BATCH_AMPLIFIER, 1);
                    controller.setOwner(OwnerRecord.of(owner));
                    controller.detectNow();
                    helper.assertValueEqual(controller.cards().usable(), 0, "no slots without the research");
                    helper.assertValueEqual(held(controller.cards()), 0, "the card left its slot");
                    helper.assertValueEqual(droppedCards(helper, core), 1, "and dropped");
                    helper.assertValueEqual(controller.stats().batchPercent(), 100, "no amplifier");
                    ResearchTeam team = Research.team(owner, true);
                    Research.addToken(server, team);
                    helper.assertValueEqual(Research.useToken(server, team, MaxtechStorage.id("controller_cards")),
                            Research.Result.DONE, "upgrade cards node");
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).cards().usable(),
                                4, "slots after research"))
                .thenSucceed();
    }

    /** The cards are saved with the primary in their slots, with the slot count. */
    private static void saved(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenWaitUntil(() -> helper
                .assertValueEqual(NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "4 slots"))
                .thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    controller.cards().setItem(2, card(CardKind.SURGE_REGULATOR));
                    controller.cards().setItem(3, card(CardKind.EFFICIENCY_CORE));
                    CompoundTag tag = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
                    ControllerBlockEntity copy = new ControllerBlockEntity(controller.getBlockPos(),
                            controller.getBlockState());
                    copy.loadCustomOnly(
                            TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
                    helper.assertValueEqual(copy.cards().usable(), 4, "slot count");
                    helper.assertTrue(copy.cards().getItem(2).is(CardContent.SURGE_REGULATOR.get()), "slot 3");
                    helper.assertTrue(copy.cards().getItem(3).is(CardContent.EFFICIENCY_CORE.get()), "slot 4");
                    helper.assertTrue(copy.cards().getItem(0).isEmpty(), "slot 1 empty");
                    helper.assertValueEqual(copy.stats().surgeReservePercent(), 10, "the loaded regulator works");
                }).thenSucceed();
    }

    /**
     * Old expansion blocks on all six faces of a one-block controller: five batch amplifiers and an efficiency core.
     * The fill turns all six into cards: four amplifiers fill the four slots, and the fifth amplifier and the core drop
     * where their blocks were. No block stays, and six cards exist in all. Breaking a stray amplifier block drops its
     * card.
     */
    private static void fromExpansionBlocks(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        BlockPos stray = new BlockPos(15, 1, 5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "4 slots"))
                .thenExecute(() -> {
                    for (Direction side : Direction.values()) {
                        ControllerClusterGameTests.expansion(helper, core.relative(side),
                                side == Direction.UP
                                        ? ExpansionContent.EFFICIENCY_CORE
                                        : ExpansionContent.BATCH_AMPLIFIER);
                    }
                    ControllerClusterGameTests.expansion(helper, stray, ExpansionContent.SURGE_REGULATOR);
                }).thenWaitUntil(() -> {
                    for (Direction side : Direction.values()) {
                        helper.assertBlockNotPresent(ExpansionContent.BATCH_AMPLIFIER.get(), core.relative(side));
                        helper.assertBlockNotPresent(ExpansionContent.EFFICIENCY_CORE.get(), core.relative(side));
                    }
                }).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    int installed = held(controller.cards());
                    int dropped = droppedCards(helper, core);
                    helper.assertValueEqual(installed, 4, "four installed");
                    helper.assertValueEqual(installed + dropped, 6, "six cards in all");
                    helper.assertValueEqual(
                            controller.cards(CardKind.BATCH_AMPLIFIER) + controller.cards(CardKind.EFFICIENCY_CORE), 4,
                            "in usable slots");
                    helper.assertBlockPresent(ExpansionContent.SURGE_REGULATOR.get(), stray);
                    helper.getLevel().destroyBlock(helper.absolutePos(stray), true);
                    int regulators = 0;
                    for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(helper.absolutePos(stray)).inflate(2))) {
                        if (entity.getItem().is(CardContent.SURGE_REGULATOR.get())) {
                            regulators += entity.getItem().getCount();
                        }
                        helper.assertFalse(entity.getItem().is(ExpansionContent.SURGE_REGULATOR.item().get()),
                                "no old block item");
                    }
                    helper.assertValueEqual(regulators, 1, "the stray block drops its card");
                }).thenSucceed();
    }
}
