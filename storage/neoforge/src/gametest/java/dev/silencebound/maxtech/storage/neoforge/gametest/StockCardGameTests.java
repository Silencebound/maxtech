package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.InterfaceCardCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperWorker;
import dev.silencebound.maxtech.storage.crafting.stock.StockPayloads;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfaceCrafting;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The follow-up of M7c area D: the crafting card's retry after a refusal (port and interface), the interface's crafting
 * card, the stock keeper in classic mode, a keeper on the real job queue with a furnace, and the terminal's stock tab
 * payloads. Uses {@link StockKeepingGameTests}'s stand-in queue, which sends a marked network's requests to the real
 * queue.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StockCardGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final List<String> NAMES = new ArrayList<>();
    private static final String CLASSIC = "crafting_stock_classic_does_nothing";
    private static final String REAL = "crafting_stock_real_queue_furnace";
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    /** {@code crafting.cardIntervalTicks} in both presets. */
    private static final int INTERVAL = 100;
    /** Slack for the network scheduler's pass and the request's own tick. */
    private static final int SLACK = 6;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("crafting_card_port_retry_after_refusal", StockCardGameTests::portRetry);
        register("crafting_card_interface_request_and_retry", StockCardGameTests::interfaceCard);
        register("crafting_card_interface_needs_card", StockCardGameTests::interfaceNeedsCard);
        register("crafting_stock_tab_list_and_edit", StockCardGameTests::tab);
        StorageTests.function(CLASSIC, StorageTestEnvironment.clearing(StockCardGameTests::classic));
        StorageTests.function(REAL, StorageTestEnvironment.clearing(StockCardGameTests::realQueue));
    }

    private StockCardGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("stock_cards"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 600);
        }
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(CLASSIC),
                        new StorageTestEnvironment("crafting.autocraftingMode", "classic", false)),
                CLASSIC, STRUCTURE, 200);
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(REAL), StorageTestEnvironment.plain()),
                REAL, STRUCTURE, 1600);
    }

    private static List<StockKeepingGameTests.Call> calls(NetworkState network) {
        return StockKeepingGameTests.QUEUE.calls(network);
    }

    private static NetworkState network(GameTestHelper helper, @org.jspecify.annotations.Nullable NetworkState state,
            String what) {
        if (state == null) {
            throw helper.assertionException(Component.literal(what + " is in no network"));
        }
        return state;
    }

    private static StockRule rule(StackKey key, long keep, long restart, long batch) {
        return StockRule.EMPTY.withKey(Optional.of(key)).withKeep(keep).withRestart(restart).withBatch(batch);
    }

    /**
     * A refused card request is tried again once the interval is over, without any event in between: the port's run
     * comes back after {@code crafting.cardIntervalTicks} instead of drifting with its idle backoff, and the retry is
     * taken once the queue takes requests again (a CPU free in a real game).
     */
    private static void portRetry(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.power(helper, controller, 1_000_000);
        helper.setBlock(NetworkGameTests.at(3), PortContent.PORT.get().defaultBlockState()
                .setValue(PortBlock.FACING, Direction.EAST).setValue(PortBlock.MODE, PortMode.EXPORT));
        helper.setBlock(NetworkGameTests.at(4), Blocks.CHEST);
        PortBlockEntity port = helper.getBlockEntity(NetworkGameTests.at(3), PortBlockEntity.class);
        port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        helper.startSequence().thenWaitUntil(() -> network(helper, port.network(), "the port")).thenExecute(() -> {
            StockKeepingGameTests.QUEUE.refuse(Objects.requireNonNull(port.network()), true);
            port.worker().cards().setItem(0, new ItemStack(PortContent.CRAFTING_CARD.get()));
        }).thenWaitUntil(
                () -> helper.assertValueEqual(calls(Objects.requireNonNull(port.network())).size(), 1, "first request"))
                .thenExecute(() -> {
                    helper.assertFalse(calls(Objects.requireNonNull(port.network())).get(0).taken(),
                            "first request refused");
                    StockKeepingGameTests.QUEUE.refuse(Objects.requireNonNull(port.network()), false);
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(calls(Objects.requireNonNull(port.network())).size(), 2, "retry"))
                .thenExecute(() -> {
                    List<StockKeepingGameTests.Call> calls = calls(Objects.requireNonNull(port.network()));
                    long gap = calls.get(1).time() - calls.get(0).time();
                    helper.assertTrue(gap >= INTERVAL && gap <= INTERVAL + SLACK, "retry after " + gap + " ticks");
                    helper.assertTrue(calls.get(1).taken(), "retry taken");
                    helper.assertValueEqual(port.worker().cardRequests(), 1, "requests taken");
                }).thenSucceed();
    }

    /** A powered network with a test drive at x 2 and an interface at x 3 stocking 16 cobblestone. */
    private static InterfaceBlockEntity iface(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.power(helper, controller, 1_000_000);
        helper.setBlock(NetworkGameTests.at(3), InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(NetworkGameTests.at(3), InterfaceBlockEntity.class);
        iface.worker().config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        return iface;
    }

    /**
     * The crafting card is an interface card. With the network out of cobblestone, the card asks for the slot's 16; a
     * refused request is tried again once after the interval though nothing in the network changed, and the taken one
     * is the only request while it is out. Once the "job" delivers, the interface stocks the cobblestone.
     */
    private static void interfaceCard(GameTestHelper helper) {
        InterfaceBlockEntity iface = iface(helper);
        ItemStack card = new ItemStack(PortContent.CRAFTING_CARD.get());
        helper.assertTrue(card.is(InterfaceCrafting.CARDS), "the crafting card is an interface card");
        helper.startSequence().thenWaitUntil(() -> network(helper, iface.network(), "the interface"))
                .thenExecute(() -> {
                    StockKeepingGameTests.QUEUE.refuse(Objects.requireNonNull(iface.network()), true);
                    iface.worker().card().setItem(0, card);
                }).thenWaitUntil(() -> helper.assertValueEqual(calls(Objects.requireNonNull(iface.network())).size(), 1,
                        "first request"))
                .thenExecute(() -> {
                    StockKeepingGameTests.Call first = calls(Objects.requireNonNull(iface.network())).get(0);
                    helper.assertFalse(first.taken(), "first request refused");
                    helper.assertValueEqual(first.request().key(), COBBLE, "requested key");
                    helper.assertValueEqual(first.request().amount(), 16L, "the slot's missing amount");
                    StockKeepingGameTests.QUEUE.refuse(Objects.requireNonNull(iface.network()), false);
                }).thenWaitUntil(() -> helper.assertValueEqual(calls(Objects.requireNonNull(iface.network())).size(), 2,
                        "retry"))
                .thenExecute(() -> {
                    List<StockKeepingGameTests.Call> calls = calls(Objects.requireNonNull(iface.network()));
                    long gap = calls.get(1).time() - calls.get(0).time();
                    helper.assertTrue(gap >= INTERVAL && gap <= INTERVAL + SLACK, "retry after " + gap + " ticks");
                    helper.assertTrue(calls.get(1).taken(), "retry taken");
                }).thenIdle(INTERVAL + 20).thenExecute(() -> {
                    helper.assertValueEqual(calls(Objects.requireNonNull(iface.network())).size(), 2,
                            "requests while one is out");
                    helper.assertValueEqual(InterfaceCardCrafting.INSTANCE.requests(iface), 1, "requests taken");
                    StockKeepingGameTests.Call taken = calls(Objects.requireNonNull(iface.network())).get(1);
                    NetworkGameTests.insert(network(helper, iface.network(), "the interface"), COBBLE, 16);
                    taken.done().accept(BackgroundCrafting.Outcome.DONE);
                }).thenWaitUntil(() -> helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 16L,
                        "stocked from the crafted cobblestone"))
                .thenSucceed();
    }

    /**
     * Without a card in its card slot, or with one but an owner who lacks the research, an interface asks for nothing.
     */
    private static void interfaceNeedsCard(GameTestHelper helper) {
        InterfaceBlockEntity iface = iface(helper);
        helper.startSequence().thenWaitUntil(() -> network(helper, iface.network(), "the interface")).thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(calls(Objects.requireNonNull(iface.network())).size(), 0,
                            "requests without a card");
                    iface.worker().card().setItem(0, new ItemStack(PortContent.CRAFTING_CARD.get()));
                }).thenWaitUntil(() -> helper.assertValueEqual(calls(Objects.requireNonNull(iface.network())).size(), 1,
                        "with a card"))
                .thenSucceed();
    }

    /** In classic mode a short rule requests nothing: the keeper stays out of the job queue. */
    private static void classic(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), StockKeeperContent.KEEPER.get());
        NetworkGameTests.power(helper, controller, 1_000_000);
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(3), StockKeeperBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper.network(), "the keeper"))
                .thenExecute(() -> keeper.worker().setRule(0, rule(COBBLE, 10, 0, 0))).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(calls(Objects.requireNonNull(keeper.network())).size(), 0,
                            "requests in classic mode");
                    helper.assertTrue(keeper.worker().evaluations() >= 1, "the keeper looked");
                }).thenSucceed();
    }

    private static PatternInput in(Item item, long amount) {
        return new PatternInput(new PatternIngredient.Exact(ItemKey.of(item)), amount, Optional.empty(), false);
    }

    /**
     * The keeper through the real queue: a CPU, a pattern provider facing a furnace with a smelting pattern, and a rule
     * to keep 2 iron ingots in batches of 1. The keeper asks twice, one job after the other, and stops at 2.
     */
    private static void realQueue(GameTestHelper helper) {
        CraftingPattern smelt = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(in(Items.RAW_IRON, 1), in(Items.COAL, 1)), List.of(new PatternOutput(IRON, 1, 1f)),
                Optional.empty(), Optional.empty());
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        BlockPos providerPos = NetworkGameTests.at(3);
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(smelt));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(3).above(), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(3).above(),
                StockKeeperBlockEntity.class);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        keeper.network() != null && keeper.network() == provider.network()
                                && keeper.network().patterns().isCraftable(IRON),
                        "the keeper and the provider's pattern on one network"))
                .thenExecute(() -> {
                    NetworkState network = Objects.requireNonNull(keeper.network());
                    StockKeepingGameTests.QUEUE.real(network);
                    NetworkGameTests.insert(network, ItemKey.of(Items.RAW_IRON), 4);
                    NetworkGameTests.insert(network, ItemKey.of(Items.COAL), 4);
                    keeper.worker().setRule(0, rule(IRON, 2, 0, 1));
                }).thenWaitUntil(() -> {
                    NetworkState network = Objects.requireNonNull(keeper.network());
                    helper.assertValueEqual(network.contents().amount(IRON), 2L,
                            "ingots kept (requests " + calls(network) + ")");
                    helper.assertFalse(keeper.worker().filling(0), "still filling");
                }).thenIdle(20).thenExecute(() -> {
                    NetworkState network = Objects.requireNonNull(keeper.network());
                    List<StockKeepingGameTests.Call> calls = calls(network);
                    helper.assertValueEqual(calls.size(), 2, "jobs asked for");
                    helper.assertTrue(calls.get(0).taken() && calls.get(1).taken(), "both taken by the queue");
                    helper.assertValueEqual(calls.get(0).request().amount(), 1L, "batch of one");
                    helper.assertValueEqual(network.contents().amount(IRON), 2L, "no more than kept");
                    helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.RAW_IRON)), 2L, "raw iron used");
                }).thenSucceed();
    }

    /** A menu over the terminal at {@code pos} for {@code player} whose payloads land in {@code sent}. */
    private static CraftingSession session(GameTestHelper helper, BlockPos pos, ServerPlayer player,
            List<CustomPacketPayload> sent, int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(pos, TerminalBlockEntity.class), update -> {
                }, sent::add);
        player.containerMenu = menu;
        return Objects.requireNonNull(menu.crafting());
    }

    private static StockPayloads.Keepers last(GameTestHelper helper, List<CustomPacketPayload> sent) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i) instanceof StockPayloads.Keepers keepers) {
                return keepers;
            }
        }
        throw helper.assertionException(Component.literal("no keeper list sent"));
    }

    /**
     * The stock tab: the owner (full access) lists the network's keeper with every offered rule and edits one; a
     * crafter sees the list read-only and cannot edit; edits naming a rule past the offered ones or another node are
     * refused. The list survives the payload codec.
     */
    private static void tab(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "stock_tab_owner");
        ServerPlayer crafter = NetworkGameTests.player(helper.getLevel(), "stock_tab_crafter");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), StockKeeperContent.KEEPER.get());
        helper.setBlock(NetworkGameTests.at(4), TerminalContent.TERMINAL.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT));
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(3), StockKeeperBlockEntity.class);
        List<CustomPacketPayload> toOwner = new ArrayList<>();
        List<CustomPacketPayload> toCrafter = new ArrayList<>();
        StockRule edited = rule(COBBLE, 128, 32, 16).withPriority(2);
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper.network(), "the keeper")).thenExecute(() -> {
            CraftingSession mine = session(helper, NetworkGameTests.at(4), owner, toOwner, 41);
            helper.assertTrue(StockPayloads.watch(mine), "owner's list");
            StockPayloads.Keepers list = last(helper, toOwner);
            helper.assertTrue(list.canEdit(), "owner may edit");
            helper.assertValueEqual(list.containerId(), 41, "container id");
            helper.assertValueEqual(list.keepers().size(), 1, "keepers listed");
            long node = list.keepers().get(0).node();
            helper.assertValueEqual(node, helper.absolutePos(NetworkGameTests.at(3)).asLong(), "keeper's node");
            helper.assertValueEqual(list.keepers().get(0).rules().size(), StockKeeperWorker.ruleSlots(),
                    "rules offered");
            helper.assertTrue(StockPayloads.edit(mine, new StockPayloads.Edit(41, node, 1, edited)), "owner's edit");
            helper.assertValueEqual(keeper.worker().rule(1), edited, "rule after the edit");
            helper.assertValueEqual(last(helper, toOwner).keepers().get(0).rules().get(1), edited, "list after edit");
            helper.assertFalse(
                    StockPayloads.edit(mine,
                            new StockPayloads.Edit(41, node, StockKeeperWorker.ruleSlots(), StockRule.EMPTY)),
                    "edit past the offered rules");
            helper.assertFalse(StockPayloads.edit(mine, new StockPayloads.Edit(41, node + 1, 0, edited)),
                    "edit of another node");
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            StockPayloads.Keepers.CODEC.encode(buffer, last(helper, toOwner));
            helper.assertValueEqual(StockPayloads.Keepers.CODEC.decode(buffer), last(helper, toOwner),
                    "list after the codec");

            CraftingSession theirs = session(helper, NetworkGameTests.at(4), crafter, toCrafter, 42);
            helper.assertTrue(StockPayloads.watch(theirs), "crafter's list");
            helper.assertFalse(last(helper, toCrafter).canEdit(), "crafter may not edit");
            helper.assertFalse(StockPayloads.edit(theirs, new StockPayloads.Edit(42, node, 1, StockRule.EMPTY)),
                    "crafter's edit");
            helper.assertValueEqual(keeper.worker().rule(1), edited, "rule after the crafter's edit");
        }).thenSucceed();
    }
}
