package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchAction;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchMenu;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTerminalBlockEntity;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.research.ResearchView;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The research terminal and the server side of the research screen: the terminal works only on an online, powered
 * network and only for players the owner lets in; the menu sends nothing until the screen asks, then a view of the
 * player's team and a new one only when the team changed (at most every {@link ResearchMenu#CHECK_TICKS} ticks); its
 * buttons spend points and use tokens for the clicking player's team and refuse what does not apply; the view survives
 * its codec.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ResearchScreenGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String[] NAMES = {"research_terminal_needs_power_and_access", "research_menu_syncs_and_acts"};

    static {
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(ResearchScreenGameTests::needsPower));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(ResearchScreenGameTests::syncsAndActs));
    }

    private ResearchScreenGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("research_screen"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 200);
        }
    }

    private static void needsPower(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "rt_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "rt_stranger");
        BlockPos controller = ownedController(helper, owner);
        ResearchTerminalBlockEntity terminal = terminal(helper);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(2)))
                .thenExecute(() -> {
                    helper.assertFalse(terminal.networkWorks(), "no power, no research");
                    NetworkGameTests.power(helper, controller, 10_000);
                }).thenWaitUntil(() -> helper.assertTrue(terminal.networkWorks(), "powered network works"))
                .thenExecute(() -> {
                    helper.assertTrue(terminal.canAccess(owner), "the owner gets in");
                    helper.assertFalse(terminal.canAccess(stranger), "a stranger does not");
                    ResearchMenu menu = ResearchMenu.server(1, stranger, terminal, view -> {
                    });
                    helper.assertFalse(menu.stillValid(stranger), "a stranger's menu closes");
                }).thenSucceed();
    }

    private static void syncsAndActs(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "rt_researcher");
        BlockPos controller = ownedController(helper, owner);
        ResearchTerminalBlockEntity terminal = terminal(helper);
        NetworkGameTests.power(helper, controller, 10_000);
        List<ResearchView> sent = new ArrayList<>();
        ResearchMenu menu = ResearchMenu.server(7, owner, terminal, sent::add);
        ResearchTree tree = Research.tree(server);
        ResearchTeam team = Research.team(owner, true);
        int familyMasks = index(helper, tree, "family_masks");
        helper.startSequence().thenExecute(() -> {
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 0, "views before the screen asks");
            helper.assertTrue(menu.clickMenuButton(owner, refresh()), "refresh");
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "views after asking");
            ResearchView view = sent.getLast();
            helper.assertValueEqual(view.containerId(), 7, "container id");
            helper.assertValueEqual(view.nodes().size(), tree.entries().size(), "one entry per node");
            helper.assertValueEqual(state(view, tree, "logic_mask"), ResearchView.State.COMPLETE, "start node");
            helper.assertValueEqual(state(view, tree, "family_masks"), ResearchView.State.AVAILABLE, "available");
            helper.assertValueEqual(state(view, tree, "memory_3"), ResearchView.State.LOCKED, "locked");
            ResearchView.Option points = view.nodes().get(familyMasks).options().get(2);
            helper.assertValueEqual(points.target(), 100L, "points option cost");
            helper.assertValueEqual(roundTrip(helper, view), view, "view codec");
            helper.assertFalse(menu.clickMenuButton(owner, action(ResearchAction.Kind.SPEND_POINTS, familyMasks)),
                    "no points yet");
            helper.assertFalse(menu.clickMenuButton(owner, action(ResearchAction.Kind.USE_TOKEN, familyMasks)),
                    "no tokens yet");
            helper.assertFalse(menu.clickMenuButton(owner, action(ResearchAction.Kind.SPEND_POINTS, 100_000)),
                    "an unknown node");
            Research.addPoints(server, team, 100);
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "no view inside the check interval");
        }).thenIdle(ResearchMenu.CHECK_TICKS).thenExecute(() -> {
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 2, "a view after the team changed");
            helper.assertValueEqual(sent.getLast().points(), 100L, "points in the view");
            helper.assertTrue(menu.clickMenuButton(owner, action(ResearchAction.Kind.SPEND_POINTS, familyMasks)),
                    "spent");
            menu.broadcastChanges();
            helper.assertValueEqual(state(sent.getLast(), tree, "family_masks"), ResearchView.State.COMPLETE,
                    "complete in the next view");
            int before = sent.size();
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), before, "nothing new without a change");
        }).thenSucceed();
    }

    private static BlockPos ownedController(GameTestHelper helper, ServerPlayer owner) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        BlockState state = helper.getBlockState(controller);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(controller), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        return controller;
    }

    private static ResearchTerminalBlockEntity terminal(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(2), ResearchContent.TERMINAL.get());
        return helper.getBlockEntity(NetworkGameTests.at(2), ResearchTerminalBlockEntity.class);
    }

    private static int index(GameTestHelper helper, ResearchTree tree, String path) {
        List<ResearchTree.Entry> entries = tree.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(MaxtechStorage.id(path))) {
                return i;
            }
        }
        throw helper.assertionException(Component.literal("no research node " + path));
    }

    private static ResearchView.State state(ResearchView view, ResearchTree tree, String path) {
        List<ResearchTree.Entry> entries = tree.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(MaxtechStorage.id(path))) {
                return view.nodes().get(i).state();
            }
        }
        return ResearchView.State.LOCKED;
    }

    private static int refresh() {
        return new ResearchAction(ResearchAction.Kind.REFRESH, 0, 0).encode();
    }

    private static int action(ResearchAction.Kind kind, int node) {
        return new ResearchAction(kind, node, 0).encode();
    }

    private static ResearchView roundTrip(GameTestHelper helper, ResearchView view) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        ResearchView.STREAM_CODEC.encode(buffer, view);
        return ResearchView.STREAM_CODEC.decode(buffer);
    }
}
