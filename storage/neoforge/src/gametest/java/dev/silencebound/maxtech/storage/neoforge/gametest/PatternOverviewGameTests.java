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
import dev.silencebound.maxtech.storage.crafting.overview.PatternConflicts;
import dev.silencebound.maxtech.storage.crafting.overview.PatternOverview;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The pattern overview and pattern priorities (M7C items 1 and 2): the terminal tab lists both providers' patterns with
 * conflicts, the owner changes a priority and a crafter cannot, a provider screen opened from the overview stays valid
 * only while the provider is on the network, and the planner tries the highest priority pattern first and falls back to
 * the next when it lacks materials. A powered controller at x = 1, a test storage device at x = 2, providers at x = 3
 * and 5 facing north, a terminal at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PatternOverviewGameTests {
    private static final String[] NAMES = {"pattern_overview_list_and_priority", "pattern_overview_planner_fallback",
            "pattern_overview_remote_validity", "pattern_overview_refuses_foreign", "pattern_overview_byte_bound"};
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey OAK = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey BIRCH = ItemKey.of(Items.BIRCH_PLANKS);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(PatternOverviewGameTests::listAndPriority));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(PatternOverviewGameTests::plannerFallback));
        StorageTests.function(NAMES[2], StorageTestEnvironment.clearing(PatternOverviewGameTests::remoteValidity));
        StorageTests.function(NAMES[3], StorageTestEnvironment.clearing(PatternOverviewGameTests::refusesForeign));
        StorageTests.function(NAMES[4], StorageTestEnvironment.clearing(PatternOverviewGameTests::byteBound));
    }

    private PatternOverviewGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("pattern_overview"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    private static CraftingPattern sticks(StackKey planks) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(planks), 2, Optional.empty(), false)),
                List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    }

    private static CraftingPattern torch() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(ItemKey.of(Items.COAL)), 1, Optional.empty(),
                        false), new PatternInput(new PatternIngredient.Exact(STICK), 1, Optional.empty(), false)),
                List.of(new PatternOutput(ItemKey.of(Items.TORCH), 4, 1f)), Optional.empty(), Optional.empty());
    }

    private static PatternProviderBlockEntity provider(GameTestHelper helper, int x, List<CraftingPattern> patterns) {
        BlockPos pos = NetworkGameTests.at(x);
        helper.setBlock(pos.north(), Blocks.STONE);
        helper.setBlock(pos, ProviderContent.PROVIDER.get().defaultBlockState().setValue(PatternProviderBlock.FACING,
                Direction.NORTH));
        PatternProviderBlockEntity provider = helper.getBlockEntity(pos, PatternProviderBlockEntity.class);
        for (int i = 0; i < patterns.size(); i++) {
            provider.logic().slots().setItem(i, PatternItem.encode(patterns.get(i)));
        }
        return provider;
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider) {
        NetworkState network = provider.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the provider is in no network"));
        }
        return network;
    }

    private static CraftingSession session(GameTestHelper helper, ServerPlayer player, List<CustomPacketPayload> sent,
            int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(NetworkGameTests.at(4), TerminalBlockEntity.class), update -> {
                }, sent::add);
        player.containerMenu = menu;
        return Objects.requireNonNull(menu.crafting());
    }

    private static PatternOverview.Patterns last(GameTestHelper helper, List<CustomPacketPayload> sent) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i) instanceof PatternOverview.Patterns patterns) {
                return patterns;
            }
        }
        throw helper.assertionException(Component.literal("no pattern list sent"));
    }

    /** Owner and crafter on a secured network with two providers and a terminal. */
    private static ServerPlayer[] secured(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "overview_owner");
        ServerPlayer crafter = NetworkGameTests.player(helper.getLevel(), "overview_crafter");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), TerminalContent.TERMINAL.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT));
        return new ServerPlayer[]{owner, crafter};
    }

    /**
     * Both providers' patterns are listed by position and slot, the two stick patterns count as a conflict, the owner's
     * priority change lands on the pattern item and in the next list, a crafter sees the list read-only and is refused,
     * and the list survives the payload codec.
     */
    private static void listAndPriority(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, List.of(sticks(OAK), torch()));
        PatternProviderBlockEntity second = provider(helper, 5, List.of(sticks(BIRCH)));
        List<CustomPacketPayload> toOwner = new ArrayList<>();
        List<CustomPacketPayload> toCrafter = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) == network(helper, second), "one network");
        }).thenExecute(() -> {
            CraftingSession mine = session(helper, players[0], toOwner, 51);
            helper.assertTrue(PatternOverview.watch(mine), "owner's list");
            PatternOverview.Patterns list = last(helper, toOwner);
            helper.assertTrue(list.canEdit(), "owner may edit");
            helper.assertValueEqual(list.rows().size(), 3, "three patterns");
            helper.assertValueEqual(list.rows().get(0).pos(), helper.absolutePos(NetworkGameTests.at(3)),
                    "first provider first");
            helper.assertValueEqual(list.rows().get(1).slot(), 1, "the torch in slot 1");
            List<CraftingPattern> patterns = new ArrayList<>();
            for (PatternOverview.Row row : list.rows()) {
                patterns.add(row.pattern());
            }
            int[] conflicts = PatternConflicts.counts(patterns, pattern -> pattern.mainOutput().key());
            helper.assertValueEqual(conflicts[0], 2, "two stick patterns");
            helper.assertValueEqual(conflicts[1], 1, "one torch pattern");

            PatternOverview.Row birch = list.rows().get(2);
            helper.assertTrue(
                    PatternOverview.setPriority(mine,
                            new PatternOverview.SetPriority(51, birch.pos(), birch.facing(), birch.slot(), 7)),
                    "owner's priority");
            helper.assertValueEqual(PatternItem.priority(second.logic().slots().getItem(0)), 7, "on the item");
            helper.assertValueEqual(last(helper, toOwner).rows().get(2).priority(), 7, "in the next list");
            helper.assertFalse(PatternOverview.setPriority(mine,
                    new PatternOverview.SetPriority(51, birch.pos(), birch.facing(), 5, 3)), "an empty slot");
            helper.assertFalse(
                    PatternOverview.setPriority(mine,
                            new PatternOverview.SetPriority(51, birch.pos(), Direction.UP, 0, 3)),
                    "a provider not there");
            helper.assertTrue(
                    PatternOverview.setPriority(mine,
                            new PatternOverview.SetPriority(51, birch.pos(), birch.facing(), birch.slot(), 5000)),
                    "a priority past the range");
            helper.assertValueEqual(PatternItem.priority(second.logic().slots().getItem(0)), PatternItem.MAX_PRIORITY,
                    "clamped");

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            PatternOverview.Patterns.CODEC.encode(buffer, last(helper, toOwner));
            helper.assertValueEqual(PatternOverview.Patterns.CODEC.decode(buffer), last(helper, toOwner),
                    "list after the codec");

            CraftingSession theirs = session(helper, players[1], toCrafter, 52);
            helper.assertTrue(PatternOverview.watch(theirs), "crafter's list");
            helper.assertFalse(last(helper, toCrafter).canEdit(), "crafter may not edit");
            helper.assertFalse(
                    PatternOverview.setPriority(theirs,
                            new PatternOverview.SetPriority(52, birch.pos(), birch.facing(), birch.slot(), 1)),
                    "crafter's priority");
            helper.assertFalse(PatternOverview.open(theirs, new PatternOverview.Open(52, birch.pos(), birch.facing())),
                    "crafter opens a provider");
            helper.assertValueEqual(PatternItem.priority(second.logic().slots().getItem(0)), PatternItem.MAX_PRIORITY,
                    "unchanged by the crafter");
        }).thenSucceed();
    }

    /**
     * Two stick patterns, from oak and from birch planks: with both in stock the higher priority one plans; without its
     * planks the planner falls back to the other; with equal priorities the first registered wins.
     */
    private static void plannerFallback(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), TerminalContent.TERMINAL.get());
        PatternProviderBlockEntity oak = provider(helper, 3, List.of(sticks(OAK)));
        PatternProviderBlockEntity birch = provider(helper, 5, List.of(sticks(BIRCH)));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, oak) == network(helper, birch), "one network");
        }).thenExecute(() -> {
            NetworkState network = network(helper, oak);
            NetworkGameTests.insert(network, OAK, 64);
            NetworkGameTests.insert(network, BIRCH, 64);
            helper.assertTrue(birch.logic().setPriority(0, 5), "birch first");
        }).thenExecute(() -> {
            NetworkState network = network(helper, oak);
            assertUses(helper, network, BIRCH, OAK, "the higher priority pattern");
            extract(network, BIRCH, 64);
            assertUses(helper, network, OAK, BIRCH, "the fallback without birch");
            NetworkGameTests.insert(network, BIRCH, 64);
            helper.assertTrue(oak.logic().setPriority(0, 9), "oak first now");
        }).thenExecute(() -> assertUses(helper, network(helper, oak), OAK, BIRCH, "oak after its priority rose"))
                .thenSucceed();
    }

    private static void extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            network.extract(key, amount, transaction);
            transaction.commit();
        }
    }

    private static void assertUses(GameTestHelper helper, NetworkState network, StackKey uses, StackKey not,
            String message) {
        PlanTree<StackKey> tree = CraftingPlanner.planNow(network, STICK, 8).tree();
        helper.assertTrue(tree.complete(), message + ": complete " + tree.missing());
        helper.assertValueEqual(tree.used().getLong(uses), 4L, message);
        helper.assertTrue(!tree.used().containsKey(not), message + ": not the other");
    }

    /**
     * A provider screen opened from the overview stays valid for the owner at the terminal only, closes once the
     * provider leaves the network, and is refused for an unknown provider.
     */
    private static void remoteValidity(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity provider = provider(helper, 3, List.of(sticks(OAK)));
        List<CustomPacketPayload> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            CraftingSession mine = session(helper, players[0], sent, 61);
            BlockPos pos = helper.absolutePos(NetworkGameTests.at(3));
            ProviderLogic found = PatternOverview.find(network, pos, Direction.NORTH);
            helper.assertTrue(found == provider.logic(), "found by position and facing");
            helper.assertTrue(PatternOverview.find(network, pos, Direction.SOUTH) == null, "wrong facing");
            helper.assertFalse(PatternOverview.open(mine, new PatternOverview.Open(61, pos.above(), Direction.NORTH)),
                    "no provider there");
            TerminalBlockEntity terminal = helper.getBlockEntity(NetworkGameTests.at(4), TerminalBlockEntity.class);
            Predicate<Player> valid = PatternOverview.remoteValid(provider.logic(), network, terminal);
            BlockPos at = helper.absolutePos(NetworkGameTests.at(4)).above();
            players[0].setPos(at.getX(), at.getY(), at.getZ());
            players[1].setPos(at.getX(), at.getY(), at.getZ());
            helper.assertTrue(valid.test(players[0]), "valid at the terminal");
            helper.assertFalse(valid.test(players[1]), "not for a crafter");
            players[0].setPos(at.getX() + 500, at.getY(), at.getZ());
            helper.assertFalse(valid.test(players[0]), "closes away from the terminal (B09 item 23)");
            players[0].setPos(at.getX(), at.getY(), at.getZ());
            helper.setBlock(NetworkGameTests.at(2), Blocks.AIR);
            helper.setBlock(NetworkGameTests.at(3), Blocks.AIR);
            helper.assertFalse(valid.test(players[0]), "provider gone");
        }).thenSucceed();
    }

    /**
     * Requests naming a provider of another network, a slot out of range or an empty slot are refused, and nothing
     * changes on either network. A second network (controller at x = 8, provider at x = 9) sits apart from the first.
     */
    private static void refusesForeign(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity own = provider(helper, 3, List.of(sticks(OAK)));
        NetworkGameTests.power(helper, NetworkGameTests.controller(helper, 8), 1_000_000);
        PatternProviderBlockEntity foreign = provider(helper, 9, List.of(sticks(BIRCH)));
        List<CustomPacketPayload> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, own) != network(helper, foreign), "two networks");
        }).thenExecute(() -> {
            CraftingSession mine = session(helper, players[0], sent, 71);
            BlockPos ownPos = helper.absolutePos(NetworkGameTests.at(3));
            BlockPos foreignPos = helper.absolutePos(NetworkGameTests.at(9));
            helper.assertFalse(
                    PatternOverview.setPriority(mine,
                            new PatternOverview.SetPriority(71, foreignPos, Direction.NORTH, 0, 5)),
                    "another network's");
            helper.assertFalse(PatternOverview.open(mine, new PatternOverview.Open(71, foreignPos, Direction.NORTH)),
                    "open another network's");
            for (int slot : new int[]{-1, 99, Integer.MAX_VALUE, 4}) {
                helper.assertFalse(PatternOverview.setPriority(mine,
                        new PatternOverview.SetPriority(71, ownPos, Direction.NORTH, slot, 5)), "slot " + slot);
            }
            helper.assertFalse(
                    PatternOverview.setPriority(mine,
                            new PatternOverview.SetPriority(71, ownPos.below(), Direction.NORTH, 0, 5)),
                    "no provider there");
            helper.assertValueEqual(PatternItem.priority(own.logic().slots().getItem(0)), 0, "own unchanged");
            helper.assertValueEqual(PatternItem.priority(foreign.logic().slots().getItem(0)), 0, "foreign unchanged");
            helper.assertTrue(sent.isEmpty(), "no list sent for refused requests");
        }).thenSucceed();
    }

    /** A list over the byte bound stops early and counts the rest as hidden, staying within the bound. */
    private static void byteBound(GameTestHelper helper) {
        secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, List.of(sticks(OAK), torch()));
        PatternProviderBlockEntity second = provider(helper, 5, List.of(sticks(BIRCH)));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) == network(helper, second), "one network");
        }).thenExecute(() -> {
            NetworkState network = network(helper, first);
            PatternOverview.Patterns all = PatternOverview.patterns(1, network, true);
            helper.assertValueEqual(all.rows().size(), 3, "all rows within the real bound");
            RegistryFriendlyByteBuf one = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            PatternOverview.Patterns single = PatternOverview.patterns(1, network, true, 1);
            helper.assertValueEqual(single.rows().size(), 0, "nothing fits in one byte");
            helper.assertValueEqual(single.hidden(), 3, "all counted hidden");
            PatternOverview.Patterns.CODEC.encode(one, all);
            int bound = one.writerIndex() - 8;
            PatternOverview.Patterns cut = PatternOverview.patterns(1, network, true, bound);
            helper.assertTrue(cut.rows().size() < 3 && cut.rows().size() + cut.hidden() == 3,
                    "cut below the full size: " + cut.rows().size());
        }).thenSucceed();
    }
}
