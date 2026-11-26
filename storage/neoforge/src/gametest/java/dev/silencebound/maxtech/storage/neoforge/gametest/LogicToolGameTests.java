package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTypes;
import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeContent;
import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeCredit;
import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeData;
import dev.silencebound.maxtech.storage.tool.cartridge.PuzzleCartridgeItem;
import dev.silencebound.maxtech.storage.tool.probe.ProbeContent;
import dev.silencebound.maxtech.storage.tool.probe.ProbeMenu;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSampler;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSamplePayload;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSeries;
import dev.silencebound.maxtech.storage.tool.probe.SignalProbeItem;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: the load comes from device operations in NeoForge transactions, as in the network.

/**
 * The M6e logic tools. The signal probe binds to a storage block and its screen samples that block's link load on a
 * cadence, only while the menu is open, in a fixed window; the puzzle cartridge writes a puzzle onto one of a stack,
 * opens it, and when the puzzle is solved uses itself up and gives the team a share of a skip token's points.
 *
 * <p>
 * Layout on one row for the probe: a controller at x = 1, tier-1 cables 2 and 3, a device at x = 4 that runs 26
 * operations (81 % of a tier-1 segment's 32 per second), as in the analyzer tests.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicToolGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final int SAMPLE_TICKS = 20;
    private static final String PROBE = "logic_probe_samples";
    private static final String PROBE_BINDING = "logic_probe_binding";
    private static final String PROBE_ACCESS = "logic_probe_access";
    private static final String CARTRIDGE = "logic_cartridge_credit";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(PROBE, () -> StorageTestEnvironment.clearing(LogicToolGameTests::probeSamples));
        FUNCTIONS.register(PROBE_BINDING, () -> StorageTestEnvironment.clearing(LogicToolGameTests::probeBinding));
        FUNCTIONS.register(PROBE_ACCESS, () -> StorageTestEnvironment.clearing(LogicToolGameTests::probeAccess));
        FUNCTIONS.register(CARTRIDGE, () -> StorageTestEnvironment.clearing(LogicToolGameTests::cartridgeCredit));
    }

    private LogicToolGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("logic_tools"),
                StorageTestEnvironment.plain());
        for (String name : List.of(PROBE, PROBE_BINDING, PROBE_ACCESS, CARTRIDGE)) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(plain, STRUCTURE, 300, 0, true)));
        }
    }

    /**
     * A bound probe's menu sends one sample when it opens and then one per cadence, never in between: the tier-1 cable
     * at the controller with one device behind it reads 12 % of its 8 channels, with four more devices it reads the
     * segment's real load (the same number the analyzer reports), and the window keeps only its newest samples. A
     * probed position that is not in a network reads as no signal.
     */
    private static void probeSamples(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(4));
        ServerPlayer player = listening(helper.getLevel(), "probe_reader", new ArrayList<>());
        BlockPos at = helper.absolutePos(NetworkGameTests.at(2));
        GlobalPos target = GlobalPos.of(helper.getLevel().dimension(), at);
        List<ProbeSamplePayload> sent = new ArrayList<>();
        int capacity = 3;
        ProbeMenu menu = ProbeMenu.server(9, target, player, sent::add, capacity, SAMPLE_TICKS);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "a sample when it opens");
            helper.assertValueEqual(sent.get(0).containerId(), 9, "container id");
            helper.assertValueEqual(sent.get(0).load(), 12, "one device's channel");
            helper.assertValueEqual(sent.get(0).rate(), 8L, "the tier-1 link's channels");
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "nothing between two cadence points");
        }).thenIdle(SAMPLE_TICKS + 1).thenExecute(() -> {
            for (BlockPos pos : List.of(new BlockPos(3, 1, 0), new BlockPos(3, 1, 2), new BlockPos(3, 2, 1),
                    new BlockPos(3, 0, 1))) {
                NetworkGameTests.device(helper, pos);
            }
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.net(helper, device).channelsHeld(), 5,
                "channels with five devices")).thenExecute(() -> {
                    menu.broadcastChanges();
                    helper.assertValueEqual(sent.size(), 2, "the next sample");
                    int load = sent.get(1).load();
                    helper.assertTrue(load >= 50 && load < 90, "sampled load " + load);
                    helper.assertValueEqual(menu.series().last(), load, "the series holds the sample");
                    helper.assertValueEqual(ProbeSampler.sample(helper.getLevel(), at).load(), load, "same reading");
                    ProbeSamplePayload round = ProbeSamplePayload.STREAM_CODEC.decode(encoded(sent.get(1)));
                    helper.assertValueEqual(round, sent.get(1), "payload round trip");
                    helper.assertValueEqual(
                            ProbeSampler.sample(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(12))),
                            ProbeSampler.Sample.NONE, "an empty position is no signal");
                    helper.assertTrue(ProbeSampler.probeable(helper.getLevel(), at), "a device is probeable");
                    helper.assertFalse(
                            ProbeSampler.probeable(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(12))),
                            "air is not");
                }).thenIdle(SAMPLE_TICKS + 1).thenExecute(() -> {
                    menu.broadcastChanges();
                }).thenIdle(SAMPLE_TICKS + 1).thenExecute(() -> {
                    menu.broadcastChanges();
                    helper.assertValueEqual(sent.size(), 4, "four samples in all");
                    helper.assertValueEqual(menu.series().size(), capacity, "the window keeps its newest three");
                    helper.assertTrue(menu.series().get(0) == sent.get(1).load(), "the oldest dropped is the first");
                    helper.assertFalse(menu.stillValid(player), "no probe in hand, the screen closes");
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ProbeContent.SIGNAL_PROBE.get()));
                    helper.assertTrue(menu.stillValid(player), "a held probe keeps it open");
                }).thenSucceed();
    }

    private static FriendlyByteBuf encoded(ProbeSamplePayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ProbeSamplePayload.STREAM_CODEC.encode(buffer, payload);
        return buffer;
    }

    /**
     * Used on a storage cable or device the probe binds to that block (dimension and position, in its component); used
     * on anything else it refuses and keeps its old binding; used in the air unbound it only says so.
     */
    private static void probeBinding(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.setBlock(NetworkGameTests.at(6), Blocks.STONE);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listening(helper.getLevel(), "probe_binder", chat);
        ItemStack probe = new ItemStack(ProbeContent.SIGNAL_PROBE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, probe);
        BlockPos cable = helper.absolutePos(NetworkGameTests.at(2));
        BlockPos stone = helper.absolutePos(NetworkGameTests.at(6));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ProbeSampler.probeable(helper.getLevel(), cable), "the cable is in a network");
        }).thenExecute(() -> {
            helper.assertValueEqual(probe.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND),
                    InteractionResult.SUCCESS, "air use");
            helper.assertTrue(keys(chat).contains(SignalProbeItem.UNBOUND_KEY), "unbound says so: " + keys(chat));
            helper.assertValueEqual(use(probe, player, cable), InteractionResult.SUCCESS, "bound to the cable");
            helper.assertTrue(GlobalPos.of(helper.getLevel().dimension(), cable)
                    .equals(probe.get(ModDataComponents.PROBE_TARGET.get())), "the cable is the target");
            player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(probe));
            helper.assertValueEqual(use(probe, player, stone), InteractionResult.SUCCESS, "stone refused");
            helper.assertTrue(keys(chat).contains(SignalProbeItem.NOT_STORAGE_KEY), "refusal said: " + keys(chat));
            helper.assertTrue(GlobalPos.of(helper.getLevel().dimension(), cable)
                    .equals(probe.get(ModDataComponents.PROBE_TARGET.get())), "the binding is kept");
        }).thenSucceed();
    }

    /**
     * With a security module and an owned core, a stranger's probe on the network is refused (message, no screen) and a
     * menu already open reads no signal; the owner reads the same block.
     */
    private static void probeAccess(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<Component> chat = new ArrayList<>();
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "probe_owner");
        ServerPlayer stranger = listening(helper.getLevel(), "probe_stranger", chat);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        BlockPos cable = helper.absolutePos(NetworkGameTests.at(2));
        GlobalPos target = GlobalPos.of(helper.getLevel().dimension(), cable);
        ItemStack probe = new ItemStack(ProbeContent.SIGNAL_PROBE.get());
        probe.set(ModDataComponents.PROBE_TARGET.get(), target);
        stranger.setItemInHand(InteractionHand.MAIN_HAND, probe);
        List<ProbeSamplePayload> sent = new ArrayList<>();
        ProbeMenu menu = ProbeMenu.server(3, target, stranger, sent::add, 3, SAMPLE_TICKS);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
            NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT);
        }).thenWaitUntil(
                () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active"))
                .thenExecute(() -> {
                    helper.assertFalse(ProbeSampler.mayRead(helper.getLevel(), cable, stranger), "stranger refused");
                    helper.assertTrue(ProbeSampler.mayRead(helper.getLevel(), cable, owner), "owner reads");
                    probe.getItem().use(helper.getLevel(), stranger, InteractionHand.MAIN_HAND);
                    helper.assertTrue(keys(chat).contains(SignalProbeItem.DENIED_KEY), "denied said: " + keys(chat));
                    menu.broadcastChanges();
                    helper.assertValueEqual(sent.size(), 1, "one sample");
                    helper.assertValueEqual(sent.get(0).load(), ProbeSeries.NO_SIGNAL, "no signal without access");
                }).thenSucceed();
    }

    /**
     * A blank cartridge of three: using it writes a puzzle onto one (the other two stay blank), opens that puzzle as a
     * session for the cartridge node at the configured difficulty, and solving it uses up the written one and adds the
     * configured share of a skip token's points to the team. A cartridge session solved with no matching cartridge left
     * gives nothing, and a session for another node is not credited.
     */
    private static void cartridgeCredit(GameTestHelper helper) {
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listening(helper.getLevel(), "cartridge_solver", chat);
        ResearchTeam team = Research.team(player, true);
        ItemStack blanks = new ItemStack(CartridgeContent.PUZZLE_CARTRIDGE.get(), 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, blanks);
        long expected = CartridgeCredit.points(Research.pointsPerToken(), 50);
        helper.assertTrue(expected > 0, "a share of a token is more than nothing");
        helper.assertValueEqual(team.points(), 0L, "a fresh team");

        helper.assertValueEqual(blanks.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND),
                InteractionResult.SUCCESS, "used");
        ItemStack written = null;
        int blank = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof PuzzleCartridgeItem)) {
                continue;
            }
            if (stack.has(ModDataComponents.CARTRIDGE.get())) {
                written = stack;
            } else {
                blank += stack.getCount();
            }
        }
        helper.assertTrue(written != null, "one cartridge carries a puzzle");
        ItemStack writtenStack = Objects.requireNonNull(written);
        helper.assertValueEqual(writtenStack.getCount(), 1, "only one was written");
        helper.assertValueEqual(blank, 2, "two stay blank");
        CartridgeData data = Objects.requireNonNull(writtenStack.get(ModDataComponents.CARTRIDGE.get()));
        PuzzleSessions.Session session = PuzzleSessions.current(player).orElseThrow();
        helper.assertValueEqual(session.nodeId(), PuzzleCartridgeItem.NODE, "the cartridge's node");
        helper.assertValueEqual(session.puzzleId(), data.puzzle(), "the written puzzle");
        helper.assertValueEqual(session.seed(), data.seed(), "the written seed");
        helper.assertValueEqual(session.difficulty(), data.difficulty(), "the written difficulty");
        helper.assertValueEqual(team.points(), 0L, "nothing before solving");

        helper.assertValueEqual(solve(player, session), true, "the solution is accepted");
        helper.assertValueEqual(team.points(), expected, "half a token's points");
        helper.assertTrue(writtenStack.isEmpty(), "the written cartridge is used up");
        helper.assertTrue(keys(chat).contains(PuzzleCartridgeItem.SOLVED_KEY), "solved said: " + keys(chat));

        PuzzleSessions.Session orphan = PuzzleSessions
                .start(player, PuzzleCartridgeItem.NODE, data.puzzle(), data.seed(), data.difficulty()).orElseThrow();
        helper.runAfterDelay(PuzzleSessions.SUBMIT_COOLDOWN_TICKS, () -> {
            helper.assertValueEqual(solve(player, orphan), true, "accepted");
            helper.assertValueEqual(team.points(), expected, "no cartridge left, no credit");
            PuzzleSessions.Session other = PuzzleSessions
                    .start(player, MaxtechStorage.id("some_node"), data.puzzle(), data.seed(), data.difficulty())
                    .orElseThrow();
            helper.runAfterDelay(PuzzleSessions.SUBMIT_COOLDOWN_TICKS, () -> {
                helper.assertValueEqual(solve(player, other), true, "accepted");
                helper.assertValueEqual(team.points(), expected, "another node is not credited");
                helper.succeed();
            });
        });
    }

    private static boolean solve(ServerPlayer player, PuzzleSessions.Session session) {
        return submit(PuzzleTypes.get(session.puzzleId()).orElseThrow(), player, session);
    }

    private static <S> boolean submit(PuzzleType<S> type, ServerPlayer player, PuzzleSessions.Session session) {
        int[] solution = type.knownSolution(type.generate(session.seed(), session.difficulty()));
        return PuzzleSessions.submit(new PuzzlePayloads.Submit(session.id(), IntArrayList.wrap(solution)), player)
                .orElse(false);
    }

    private static InteractionResult use(ItemStack stack, ServerPlayer player, BlockPos at) {
        return stack.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
    }

    private static List<String> keys(List<Component> lines) {
        List<String> keys = new ArrayList<>();
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents) {
                keys.add(contents.getKey());
            }
        }
        return keys;
    }

    private static ServerPlayer listening(ServerLevel level, String name, List<Component> chat) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public void sendSystemMessage(Component message) {
                chat.add(message);
            }

            // Server cooldowns send a packet, and this player has no connection.
            @Override
            protected ItemCooldowns createItemCooldowns() {
                return new ItemCooldowns();
            }
        };
    }
}
