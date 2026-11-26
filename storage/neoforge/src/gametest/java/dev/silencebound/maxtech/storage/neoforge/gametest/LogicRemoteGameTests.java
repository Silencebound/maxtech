package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.logic.LogicLang;

import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.storage.config.StorageModules;
import java.io.IOException;
import java.io.Reader;
import net.minecraft.server.packs.resources.Resource;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteItem;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteMenu;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.logic.remote.RemoteNotices;
import dev.silencebound.maxtech.storage.logic.remote.RemotePayloads;
import dev.silencebound.maxtech.storage.logic.remote.RemoteResult;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The logic remote (M7L2 chunk 12): binding through a network device, reach and power by the wireless terminal's rules
 * with the remote's own charge, pausing and resuming a rule only with logic access, and new notices pushed to a holder
 * in range and to nobody out of range. A controller at x 1 with a wireless access mount north of it, a test device at x
 * 2 and a logic controller at x 3 ({@link LogicControllerGameTests#setUp}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicRemoteGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** The standalone preset's cost of one pause or resume. */
    private static final int COST = 50;
    /** Far beyond any wireless range: the remote reaches anyway (owner decision 2026-10-09). */
    private static final int FAR = 10_000;
    private static final Map<UUID, List<String>> PINGS = new HashMap<>();
    private static final RemoteNotices.Sender RECORDER = (player, ping) -> PINGS
            .computeIfAbsent(player.getUUID(), id -> new ArrayList<>()).add(ping.notice().text());

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_remote_binds_through_a_device", LogicRemoteGameTests::binds);
        register("logic_remote_range_and_power", LogicRemoteGameTests::rangeAndPower);
        register("logic_remote_pause_needs_logic_access", LogicRemoteGameTests::pauseNeedsLogic);
        register("logic_remote_notice_reaches_holder_in_range", LogicRemoteGameTests::noticeInRange);
        register("logic_remote_refuses_bad_requests", LogicRemoteGameTests::refusesBadRequests);
        register("logic_remote_without_wireless_gate", LogicRemoteGameTests::withoutWirelessGate);
    }

    private LogicRemoteGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // The notice test swaps the static sender, so the remote's tests share one batch of their own.
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_remote"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- set-up

    private static BlockPos core() {
        return NetworkGameTests.at(1);
    }

    private static ControllerBlockEntity coreEntity(GameTestHelper helper) {
        return NetworkGameTests.controllerEntity(helper, core());
    }

    /** {@link LogicControllerGameTests#setUp} with a wireless access mount, and the rule "Full" on the controller. */
    private static LogicControllerBlockEntity setUp(GameTestHelper helper, String owner) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, owner);
        helper.setBlock(core().north(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        return entity;
    }

    private static NetworkState ready(GameTestHelper helper, LogicControllerBlockEntity entity) {
        helper.assertValueEqual(coreEntity(helper).services(NetworkService.WIRELESS_ACCESS), 1, "mount active");
        return LogicControllerGameTests.network(helper, entity);
    }

    /** Gives {@code player}'s team the Wireless research. */
    private static <P extends ServerPlayer> P researched(GameTestHelper helper, P player) {
        ServerLevel level = helper.getLevel();
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        return player;
    }

    private static FakePlayer fake(GameTestHelper helper, String name) {
        return researched(helper, new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name)));
    }

    private static ItemStack remote(GameTestHelper helper, int energy) {
        ItemStack remote = new ItemStack(RemoteContent.REMOTE.get());
        remote.set(ModDataComponents.WIRELESS_LINK.get(),
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(core())));
        remote.set(ModDataComponents.WIRELESS_ENERGY.get(), energy);
        return remote;
    }

    private static Vec3 centre(GameTestHelper helper) {
        return helper.absolutePos(core()).getCenter();
    }

    private static void sneakUse(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        player.setShiftKeyDown(true);
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
        player.setShiftKeyDown(false);
    }

    // ---- tests

    /**
     * Sneak-use on the logic controller (a network device) binds the remote to the network's storage controller; on a
     * plain block it binds nothing. Binding to another network drops the notices kept from the first.
     */
    private static void binds(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lr_bind_owner");
        FakePlayer player = fake(helper, "lr_binder");
        helper.setBlock(NetworkGameTests.at(6), Blocks.STONE);
        helper.startSequence().thenWaitUntil(() -> ready(helper, entity)).thenExecute(() -> {
            ItemStack stack = new ItemStack(RemoteContent.REMOTE.get());
            stack.set(ModDataComponents.REMOTE_NOTICES.get(), List.of(new NetworkNotices.Notice(1, "", "old", "")));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            sneakUse(helper, player, NetworkGameTests.at(6));
            helper.assertTrue(player.getMainHandItem().get(ModDataComponents.WIRELESS_LINK.get()) == null,
                    "bound to a plain block");
            sneakUse(helper, player, NetworkGameTests.at(3));
            helper.assertTrue(
                    GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(core()))
                            .equals(player.getMainHandItem().get(ModDataComponents.WIRELESS_LINK.get())),
                    "bound to the storage controller");
            helper.assertValueEqual(LogicRemoteItem.notices(player.getMainHandItem()), List.of(),
                    "notices of no other network kept");
        }).thenSucceed();
    }

    /**
     * With charge the remote lists the rule from any distance; bound to a spot with no controller, or below one
     * operation of charge, its view says why, lists no rules but the notices it kept, and a pause is refused without
     * paying or changing the rule.
     */
    private static void rangeAndPower(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lr_range_owner");
        FakePlayer player = fake(helper, "lr_range");
        entity.setOwner(OwnerRecord.of(player));
        coreEntity(helper).setOwner(OwnerRecord.of(player));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote(helper, 1_000));
        helper.startSequence().thenWaitUntil(() -> ready(helper, entity)).thenExecute(() -> {
            NetworkState network = ready(helper, entity);
            entity.worker().setGraph(0, "Full", LogicControllerGameTests.crossesNotify(COBBLE, 10, "full"));
            network.notices().post(new NetworkNotices.Notice(1, "", "pump stopped", ""));
            player.setPos(centre(helper).add(10, 0, 0));
            LogicRemoteMenu menu = LogicRemoteMenu.server(1, player, InteractionHand.MAIN_HAND);
            RemotePayloads.View view = view(helper, menu, 100);
            helper.assertValueEqual(view.reach(), Reach.OK, "in range");
            helper.assertValueEqual(view.rules().size(), 1, "rules in range");
            helper.assertValueEqual(view.notices().size(), 1, "notices in range");
            helper.assertTrue(menu.update(110) == null, "an unchanged view is sent once");

            player.setPos(centre(helper).add(FAR, 0, 0));
            helper.assertValueEqual(LogicRemoteItem.reach(player, player.getMainHandItem()), Reach.OK, "any distance");
            helper.assertValueEqual(menu.setRule(player, helper.absolutePos(NetworkGameTests.at(3)), 0, true),
                    RemoteResult.DONE, "a request from far away");

            ItemStack elsewhere = remote(helper, 1_000);
            elsewhere.set(ModDataComponents.WIRELESS_LINK.get(),
                    GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(NetworkGameTests.at(6))));
            helper.assertValueEqual(LogicRemoteItem.reach(player, elsewhere), Reach.NO_NETWORK,
                    "bound where no controller is");

            player.setPos(centre(helper));
            player.getMainHandItem().set(ModDataComponents.WIRELESS_ENERGY.get(), COST - 1);
            view = view(helper, menu, 400);
            helper.assertValueEqual(view.reach(), Reach.NO_POWER, "below one operation");
            helper.assertValueEqual(view.rules(), List.of(), "rules without power");
            helper.assertValueEqual(view.notices().size(), 1, "the kept notices without power");
            helper.assertValueEqual(view.notices().getFirst().text(), "pump stopped", "kept notice");
            helper.assertTrue(menu.stillValid(player), "the screen stays open without power");
            helper.assertValueEqual(menu.setRule(player, helper.absolutePos(NetworkGameTests.at(3)), 0, false),
                    RemoteResult.NO_POWER, "pause without power");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "the rule is untouched");
            helper.assertValueEqual(LogicRemoteItem.energy(player.getMainHandItem()), COST - 1, "nothing paid");
            helper.assertValueEqual(LogicRemoteItem.capacity(), 50_000, "capacity from the preset");
        }).thenSucceed();
    }

    /**
     * A player with logic access pauses the rule through the remote, paying one operation, and resumes it; a player
     * with craft access sees no rules and is refused, without paying.
     */
    private static void pauseNeedsLogic(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lr_access_owner");
        FakePlayer owner = fake(helper, "lr_owner");
        FakePlayer crafter = fake(helper, "lr_crafter");
        FakePlayer logician = fake(helper, "lr_logician");
        entity.setOwner(OwnerRecord.of(owner));
        coreEntity(helper).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core().above(), ExpansionContent.SECURITY_MODULE);
        coreEntity(helper).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT)
                .withPlayer(logician.getUUID(), logician.getGameProfile().name(), AccessLevel.LOGIC));
        for (FakePlayer player : List.of(crafter, logician)) {
            player.setItemInHand(InteractionHand.MAIN_HAND, remote(helper, 1_000));
            player.setPos(centre(helper));
        }
        BlockPos controller = helper.absolutePos(NetworkGameTests.at(3));
        helper.startSequence().thenWaitUntil(() -> {
            ready(helper, entity);
            helper.assertTrue(coreEntity(helper).secured(), "module active");
        }).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", LogicControllerGameTests.crossesNotify(COBBLE, 10, "full"));
            LogicRemoteMenu crafterMenu = LogicRemoteMenu.server(1, crafter, InteractionHand.MAIN_HAND);
            RemotePayloads.View seen = view(helper, crafterMenu, 100);
            helper.assertTrue(!seen.logic() && seen.rules().isEmpty(), "the crafter sees no rules");
            helper.assertValueEqual(crafterMenu.setRule(crafter, controller, 0, false), RemoteResult.DENIED,
                    "the crafter's pause");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "still running");
            helper.assertValueEqual(LogicRemoteItem.energy(crafter.getMainHandItem()), 1_000, "the crafter paid");

            LogicRemoteMenu menu = LogicRemoteMenu.server(2, logician, InteractionHand.MAIN_HAND);
            RemotePayloads.View view = view(helper, menu, 100);
            helper.assertTrue(view.logic(), "the logician has logic access");
            helper.assertValueEqual(view.rules().size(), 1, "the logician's rules");
            helper.assertTrue(view.rules().getFirst().enabled(), "listed as on");
            helper.assertValueEqual(menu.setRule(logician, controller, 0, false), RemoteResult.DONE, "pause");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.DISABLED, "paused");
            helper.assertValueEqual(LogicRemoteItem.energy(logician.getMainHandItem()), 1_000 - COST, "one paid");
            helper.assertValueEqual(menu.setRule(logician, controller, 0, false), RemoteResult.DONE, "pause again");
            helper.assertValueEqual(LogicRemoteItem.energy(logician.getMainHandItem()), 1_000 - COST,
                    "nothing paid for no change");
            helper.assertTrue(!view(helper, menu, 101).rules().getFirst().enabled(), "listed as off");
            helper.assertValueEqual(menu.setRule(logician, controller, 0, true), RemoteResult.DONE, "resume");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "resumed");
            // Both changes are in the rule's history with the remote's user; the refused and no-op ones are not.
            List<HistoryEntry> history = entity.worker().history(0);
            helper.assertValueEqual(history.size(), 2, "history: " + history);
            helper.assertValueEqual(history.get(0).changes().getFirst().key(), LogicLang.HISTORY_REMOTE_OFF, "paused");
            helper.assertValueEqual(history.get(1).changes().getFirst().key(), LogicLang.HISTORY_REMOTE_ON, "resumed");
            helper.assertValueEqual(history.get(1).player(), Optional.of(logician.getUUID()), "by the logician");
            helper.assertValueEqual(menu.setRule(logician, controller, 5, true), RemoteResult.NO_SUCH_RULE,
                    "a rule the controller does not have");
            helper.assertValueEqual(menu.setRule(crafter, controller, 0, false), RemoteResult.OUT_OF_REACH,
                    "another player through the logician's menu");
        }).thenSucceed();
    }

    /**
     * A notice posted to the network's log reaches the holder whose remote (anywhere in the inventory) is in range and
     * is kept on that remote, however far away; a second notice in the same interval sends no ping and does not rewrite
     * the item, the next one after it does and brings the one posted meanwhile along. A holder whose remote is bound
     * where no controller is gets nothing and keeps nothing.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void noticeInRange(GameTestHelper helper) {
        RemoteNotices.setSender(RECORDER);
        LogicControllerBlockEntity entity = setUp(helper, "lr_notice_owner");
        ServerPlayer near = researched(helper, helper.makeMockServerPlayerInLevel());
        ServerPlayer far = researched(helper, helper.makeMockServerPlayerInLevel());
        ServerPlayer stray = researched(helper, helper.makeMockServerPlayerInLevel());
        PINGS.remove(near.getUUID());
        PINGS.remove(far.getUUID());
        PINGS.remove(stray.getUUID());
        near.getInventory().setItem(20, remote(helper, 1_000));
        far.getInventory().setItem(20, remote(helper, 1_000));
        stray.getInventory().setItem(20, remote(helper, 1_000));
        helper.startSequence().thenWaitUntil(() -> ready(helper, entity)).thenExecute(() -> {
            near.setPos(centre(helper).add(5, 0, 0));
            far.setPos(centre(helper).add(FAR, 0, 0));
            stray.getInventory().getItem(20).set(ModDataComponents.WIRELESS_LINK.get(),
                    GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(NetworkGameTests.at(6))));
            NetworkState network = ready(helper, entity);
            network.notices().post(new NetworkNotices.Notice(1, "", "tank full", ""));
            network.notices().post(new NetworkNotices.Notice(1, "", "tank still full", ""));
            helper.assertValueEqual(PINGS.getOrDefault(near.getUUID(), List.of()), List.of("tank full"),
                    "pings in range, one per interval");
            helper.assertValueEqual(PINGS.getOrDefault(far.getUUID(), List.of()), List.of("tank full"),
                    "pings far away");
            helper.assertValueEqual(PINGS.getOrDefault(stray.getUUID(), List.of()), List.of(),
                    "pings without the network");
            helper.assertValueEqual(LogicRemoteItem.notices(near.getInventory().getItem(20)).size(), 1,
                    "kept in range, rewritten once per interval");
            helper.assertValueEqual(LogicRemoteItem.notices(stray.getInventory().getItem(20)).size(), 0,
                    "kept without the network");
        }).thenIdle(RemoteNotices.INTERVAL_TICKS).thenExecute(() -> {
            ready(helper, entity).notices().post(new NetworkNotices.Notice(2, "", "tank empty", ""));
            helper.assertValueEqual(PINGS.getOrDefault(near.getUUID(), List.of()), List.of("tank full", "tank empty"),
                    "pings after the interval");
            helper.assertValueEqual(LogicRemoteItem.notices(near.getInventory().getItem(20)).size(), 3,
                    "kept after the interval, with the one posted meanwhile");
            RemoteNotices.setSender(null);
            helper.getLevel().getServer().getPlayerList().remove(near);
            helper.getLevel().getServer().getPlayerList().remove(far);
            helper.getLevel().getServer().getPlayerList().remove(stray);
        }).thenSucceed();
    }

    /**
     * Pause requests the server refuses: a logic controller of another network and a position with no controller (no
     * such rule, nothing changes there), a request naming another container id and a second request in the same tick
     * (ignored, no answer). Only the one valid request switches the rule and pays.
     */
    private static void refusesBadRequests(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lr_bad_owner");
        FakePlayer player = fake(helper, "lr_bad");
        entity.setOwner(OwnerRecord.of(player));
        coreEntity(helper).setOwner(OwnerRecord.of(player));
        // A second network: its own controller at x 10 and a logic controller at x 11.
        BlockPos otherCore = NetworkGameTests.controller(helper, 10);
        helper.setBlock(NetworkGameTests.at(11), LogicContent.CONTROLLER.get());
        NetworkGameTests.power(helper, otherCore, 1_000_000);
        NetworkGameTests.controllerEntity(helper, otherCore).setOwner(OwnerRecord.of(player));
        LogicControllerBlockEntity other = LogicControllerGameTests.entity(helper, NetworkGameTests.at(11));
        other.setOwner(OwnerRecord.of(player));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote(helper, 1_000));
        player.setPos(centre(helper));
        BlockPos controller = helper.absolutePos(NetworkGameTests.at(3));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = ready(helper, entity);
            helper.assertTrue(other.network() != null && other.network() != network, "two networks");
        }).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", LogicControllerGameTests.crossesNotify(COBBLE, 10, "full"));
            other.worker().setGraph(0, "Other", LogicControllerGameTests.crossesNotify(COBBLE, 10, "other"));
            LogicRemoteMenu menu = LogicRemoteMenu.server(7, player, InteractionHand.MAIN_HAND);
            player.containerMenu = menu;
            helper.assertValueEqual(menu.setRule(player, helper.absolutePos(NetworkGameTests.at(11)), 0, false),
                    RemoteResult.NO_SUCH_RULE, "a controller of another network");
            helper.assertValueEqual(other.worker().status(0), LogicWorker.Status.RUNNING, "the other rule");
            helper.assertValueEqual(menu.setRule(player, helper.absolutePos(NetworkGameTests.at(6)), 0, false),
                    RemoteResult.NO_SUCH_RULE, "a position with no controller");
            helper.assertValueEqual(
                    Optional.ofNullable(
                            RemotePayloads.handle(new RemotePayloads.SetRule(8, controller, 0, false), player)),
                    Optional.empty(), "another container id");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "after the wrong id");
            helper.assertValueEqual(
                    Optional.ofNullable(
                            RemotePayloads.handle(new RemotePayloads.SetRule(7, controller, 0, false), player)),
                    Optional.of(RemoteResult.DONE), "the valid request");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.DISABLED, "paused");
            helper.assertValueEqual(
                    Optional.ofNullable(
                            RemotePayloads.handle(new RemotePayloads.SetRule(7, controller, 0, true), player)),
                    Optional.empty(), "a second request in the same tick");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.DISABLED, "still paused");
            helper.assertValueEqual(LogicRemoteItem.energy(player.getMainHandItem()), 1_000 - COST, "paid once");
            player.containerMenu = player.inventoryMenu;
        }).thenSucceed();
    }

    /**
     * The remote needs no wireless gate (owner decision 2026-10-09): its shipped recipe has no condition and no part
     * that needs research beyond the Logic Controller's, it stays shown with {@code modules.wireless} off, and a player
     * without the Wireless research, with the module off and no wireless access mount on the cluster, reaches the
     * network from far away and from another dimension (owner decision 2026-10-09: unlimited reach), lists the rule and
     * pauses it.
     */
    private static void withoutWirelessGate(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lr_gate_owner");
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "lr_ungated"));
        entity.setOwner(OwnerRecord.of(player));
        coreEntity(helper).setOwner(OwnerRecord.of(player));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote(helper, 1_000));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            helper.assertValueEqual(coreEntity(helper).services(NetworkService.WIRELESS_ACCESS), 0, "no mount");
            String recipe = shippedRecipe(helper, MaxtechStorage.id("logic_remote"));
            helper.assertFalse(recipe.contains("neoforge:conditions"), "the recipe is gated: " + recipe);
            // Advanced processors need the Advanced Doping research; the controller's basic logic processor does not.
            helper.assertFalse(recipe.contains("advanced_"), "the recipe needs an advanced part: " + recipe);
            helper.assertTrue(recipe.contains("basic_logic_processor"), "the recipe's logic processor: " + recipe);
            entity.worker().setGraph(0, "Full", LogicControllerGameTests.crossesNotify(COBBLE, 10, "full"));
            StorageModules.WIRELESS.overrideEnabled(false);
            try {
                helper.assertFalse(ContentToggle.hiddenItems().contains(RemoteContent.REMOTE.get()),
                        "hidden with the wireless module off");
                player.setPos(centre(helper).add(FAR, 0, 0));
                LogicRemoteMenu menu = LogicRemoteMenu.server(1, player, InteractionHand.MAIN_HAND);
                RemotePayloads.View view = view(helper, menu, 100);
                helper.assertValueEqual(view.reach(), Reach.OK, "reach without research, module or mount");
                helper.assertValueEqual(view.rules().size(), 1, "rules listed");
                helper.assertValueEqual(menu.setRule(player, helper.absolutePos(NetworkGameTests.at(3)), 0, false),
                        RemoteResult.DONE, "pause");
                helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.DISABLED, "paused");
                ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
                if (nether == null) {
                    throw helper.assertionException(Component.literal("the test server has no Nether"));
                }
                // The same player, standing in the Nether.
                FakePlayer away = new FakePlayer(nether, player.getGameProfile());
                away.setItemInHand(InteractionHand.MAIN_HAND, remote(helper, 1_000));
                away.setPos(0.5, 70, 0.5);
                helper.assertValueEqual(LogicRemoteItem.reach(away, away.getMainHandItem()), Reach.OK,
                        "reach from another dimension");
                LogicRemoteMenu awayMenu = LogicRemoteMenu.server(2, away, InteractionHand.MAIN_HAND);
                RemotePayloads.View awayView = view(helper, awayMenu, 200);
                helper.assertValueEqual(awayView.reach(), Reach.OK, "the view from the Nether");
                helper.assertValueEqual(awayView.rules().size(), 1, "rules from the Nether");
                helper.assertValueEqual(awayMenu.setRule(away, helper.absolutePos(NetworkGameTests.at(3)), 0, true),
                        RemoteResult.DONE, "resumed from the Nether");
                helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "resumed");
            } finally {
                StorageModules.WIRELESS.overrideEnabled(null);
            }
        }).thenSucceed();
    }

    /** The text of the recipe file {@code recipe} as the server loaded it from the jar or data packs. */
    private static String shippedRecipe(GameTestHelper helper, Identifier recipe) {
        Identifier file = recipe.withPath(path -> "recipe/" + path + ".json");
        Optional<Resource> resource = helper.getLevel().getServer().getResourceManager().getResource(file);
        helper.assertTrue(resource.isPresent(), file + " is missing");
        try (Reader reader = resource.orElseThrow().openAsReader()) {
            return JsonParser.parseReader(reader).toString();
        } catch (IOException e) {
            throw helper.assertionException(Component.literal(file + " unreadable: " + e));
        }
    }

    private static RemotePayloads.View view(GameTestHelper helper, LogicRemoteMenu menu, long time) {
        RemotePayloads.View view = menu.update(time);
        if (view == null) {
            throw helper.assertionException(Component.literal("the remote sent no view at " + time));
        }
        return view;
    }
}
