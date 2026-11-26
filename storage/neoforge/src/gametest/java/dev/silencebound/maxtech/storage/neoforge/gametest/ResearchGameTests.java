package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.MachineState;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchData;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchOption;
import dev.silencebound.maxtech.storage.research.ResearchPreset;
import dev.silencebound.maxtech.storage.research.ResearchScaling;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.research.TaskType;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTypes;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Research (M6b area C): tasks counted from real network, machine and port events; kind targets scaling with the pack;
 * any one option completing a node; points, skip tokens and the token item; presets; teams shared through scoreboard
 * teams with an outsider kept apart; the save codec; the unlock checks; puzzles through area D's sessions; and the skip
 * token in structure chests. Every test makes its own players, so each has a fresh team.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ResearchGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final Identifier MEMORY_2 = MaxtechStorage.id("memory_2");
    private static final Identifier MEDIUM_CASING = MaxtechStorage.id("medium_casing");
    private static final Identifier FAMILY_MASKS = MaxtechStorage.id("family_masks");
    private static final Identifier ADVANCED_DOPING = MaxtechStorage.id("advanced_doping");
    private static final Identifier MEMORY_3 = MaxtechStorage.id("memory_3");
    /** A GameTest-only node (gametest resources) offering area D's test puzzle and an unregistered one. */
    private static final Identifier PUZZLE_NODE = MaxtechStorage.id("test_puzzle_node");
    private static final List<String> PLAIN = new ArrayList<>();

    static {
        plain("research_store_tasks_from_network", ResearchGameTests::storeTasks);
        plain("research_kinds_scale_with_pack", ResearchGameTests::kindsScale);
        plain("research_machine_runs_counted", ResearchGameTests::machineRuns);
        plain("research_wakes_waiting_machine", ResearchGameTests::wakesMachine);
        plain("research_team_change_wakes_machine", ResearchGameTests::teamChangeWakesMachine);
        plain("research_points_and_tokens", ResearchGameTests::pointsAndTokens);
        plain("research_team_sharing", ResearchGameTests::teamSharing);
        plain("research_saves_and_loads", ResearchGameTests::savesAndLoads);
        plain("research_unlock_checks", ResearchGameTests::unlockChecks);
        plain("research_puzzle_completes_node", ResearchGameTests::puzzle);
        function("research_preset_expert", ResearchGameTests::expert);
        function("research_preset_off", ResearchGameTests::off);
        function("research_token_in_chest_loot", helper -> chestLoot(helper, true));
        function("research_no_token_without_chance", helper -> chestLoot(helper, false));
    }

    private ResearchGameTests() {
    }

    private static void plain(String name, Consumer<GameTestHelper> test) {
        PLAIN.add(name);
        function(name, test);
    }

    private static void function(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("research"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
        env(event, "research_preset_expert", "research.preset", "expert");
        env(event, "research_preset_off", "research.preset", "off");
        env(event, "research_token_in_chest_loot", "research.skipTokens.lootChance", "1.0");
        env(event, "research_no_token_without_chance", "research.skipTokens.lootChance", "0.0");
    }

    private static void env(RegisterGameTestsEvent event, String name, String option, String value) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(name), new StorageTestEnvironment(option, value, false)),
                name, STRUCTURE, 100);
    }

    /**
     * An owned, powered network with a test drive: storing items raises the team's store counter, which completes
     * Memory II at exactly 4,096 items (standard preset) and stays after the items leave; each new kind gives one
     * discovery point, once per team.
     */
    private static void storeTasks(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "research_store");
        NetworkState[] network = new NetworkState[1];
        ResearchTeam team = ownedNetwork(helper, owner);
        ItemKey cobble = ItemKey.of(Items.COBBLESTONE);
        helper.startSequence().thenWaitUntil(() -> network[0] = NetworkGameTests.state(helper, NetworkGameTests.at(2)))
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.insert(network[0], cobble, 4095), 4095L, "stored");
                    helper.assertValueEqual(team.counter("store_items"), 4095L, "store counter");
                    helper.assertFalse(complete(helper, team, MEMORY_2), "Memory II below its target");
                    helper.assertValueEqual(team.points(), 1L, "one discovery point");
                    NetworkGameTests.insert(network[0], cobble, 1);
                    helper.assertTrue(complete(helper, team, MEMORY_2), "Memory II at 4,096 items");
                    extract(network[0], cobble, 4096);
                    NetworkGameTests.insert(network[0], cobble, 10);
                    helper.assertValueEqual(team.counter("store_items"), 4096L, "the highest level stays");
                    helper.assertValueEqual(team.points(), 1L, "a kind is discovered once");
                    NetworkGameTests.insert(network[0], ItemKey.of(Items.DIRT), 1);
                    helper.assertValueEqual(team.points(), 2L, "a second kind");
                }).thenSucceed();
    }

    /**
     * A kind target is a fraction of the pack's items with a cap: Medium Casing asks 1 % of the registered items, at
     * most 100; storing that many kinds completes it (by its task, not its points), and one kind fewer does not.
     */
    private static void kindsScale(GameTestHelper helper) {
        int items = BuiltInRegistries.ITEM.size();
        long target = ResearchScaling.kinds(0.01, 100, items);
        helper.assertValueEqual(Research.target(ResearchOption.Task.kinds(0.01, 100)), target, "kind target");
        helper.assertTrue(target == Math.min(100, (long) Math.ceil(items * 0.01)), "1 % of the pack, capped");
        helper.assertValueEqual(Research.target(ResearchOption.Task.kinds(0.5, 100)), 100L, "the cap");
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "research_kinds");
        NetworkState[] network = new NetworkState[1];
        ResearchTeam team = ownedNetwork(helper, owner);
        MinecraftServer server = helper.getLevel().getServer();
        Research.addToken(server, team);
        helper.assertValueEqual(Research.useToken(server, team, MEMORY_2), Research.Result.DONE, "token on Memory II");
        List<Item> kinds = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!new ItemStack(item).isEmpty() && kinds.size() < target) {
                kinds.add(item);
            }
        }
        helper.startSequence().thenWaitUntil(() -> network[0] = NetworkGameTests.state(helper, NetworkGameTests.at(2)))
                .thenExecute(() -> {
                    for (int i = 0; i < kinds.size() - 1; i++) {
                        NetworkGameTests.insert(network[0], ItemKey.of(kinds.get(i)), 1);
                    }
                    helper.assertFalse(complete(helper, team, MEDIUM_CASING), "one kind short");
                    NetworkGameTests.insert(network[0], ItemKey.of(kinds.getLast()), 1);
                    helper.assertTrue(complete(helper, team, MEDIUM_CASING), "enough kinds");
                    helper.assertValueEqual(team.points(), target, "no points spent, one per kind");
                }).thenSucceed();
    }

    /** A finished run of an owned lithograph counts for its owner's team, in all and for the lithograph. */
    private static void machineRuns(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "research_machines");
        BlockPos pos = new BlockPos(2, 1, 1);
        helper.setBlock(pos, MachineContent.block(MachineKind.LITHOGRAPH));
        BlockState state = helper.getBlockState(pos);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(pos), state, owner, ItemStack.EMPTY);
        StorageMachineBlockEntity machine = helper.getBlockEntity(pos, StorageMachineBlockEntity.class);
        ResearchTeam team = Research.team(owner, true);
        MachineGameTests.insert(machine, new ItemStack(MaterialContent.SILICON.get()));
        MachineGameTests.charge(machine, 5_000);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(team.counter("machine_runs/machine/lithograph"), 1L, "lithograph runs");
            helper.assertValueEqual(team.counter("machine_runs"), 1L, "machine runs");
            helper.assertValueEqual(team.counter("processors_made"), 0L, "no processors");
        }).thenSucceed();
    }

    /**
     * An owned lithograph printing with the memory mask waits in "needs research" and sleeps; completing Family Masks
     * for its owner's team wakes it and it prints the memory dies.
     */
    private static void wakesMachine(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "research_waiting");
        ResearchTeam team = Research.team(owner, true);
        StorageMachineBlockEntity machine = waitingLithograph(helper, owner);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(machine.state(), MachineState.RESEARCH_NEEDED, "state");
            helper.assertFalse(machine.isAwake(), "asleep while waiting");
        }).thenExecute(() -> {
            Research.addPoints(server, team, 100);
            helper.assertValueEqual(Research.spendPoints(server, team, FAMILY_MASKS), Research.Result.DONE, "paid");
            helper.assertTrue(machine.isAwake(), "woken by the research");
        }).thenWaitUntil(() -> helper.assertValueEqual(machine.outputs().stack(0).getCount(), 2, "memory dies"))
                .thenSucceed();
    }

    /**
     * A player's lithograph waits for Family Masks; when the player joins a team that has it (an older team, through a
     * scoreboard team), the machine wakes and prints.
     */
    private static void teamChangeWakesMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer mentor = NetworkGameTests.player(level, "rw_mentor");
        ServerPlayer owner = NetworkGameTests.player(level, "rw_owner");
        ResearchTeam mentors = Research.team(mentor, true);
        Research.addPoints(server, mentors, 100);
        Research.spendPoints(server, mentors, FAMILY_MASKS);
        StorageMachineBlockEntity machine = waitingLithograph(helper, owner);
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam shared = scoreboard.addPlayerTeam("research_wake");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(machine.state(), MachineState.RESEARCH_NEEDED, "state");
            helper.assertFalse(machine.isAwake(), "asleep while waiting");
        }).thenExecute(() -> {
            scoreboard.addPlayerToTeam(mentor.getScoreboardName(), shared);
            scoreboard.addPlayerToTeam(owner.getScoreboardName(), shared);
            helper.assertTrue(Research.team(owner, true) == mentors, "joined the older team");
            scoreboard.removePlayerTeam(shared);
            helper.assertTrue(machine.isAwake(), "woken by the team change");
        }).thenWaitUntil(() -> helper.assertValueEqual(machine.outputs().stack(0).getCount(), 2, "memory dies"))
                .thenSucceed();
    }

    /** A lithograph owned by {@code owner}, set to print with the memory mask, a doped wafer and energy. */
    private static StorageMachineBlockEntity waitingLithograph(GameTestHelper helper, ServerPlayer owner) {
        BlockPos pos = new BlockPos(2, 1, 1);
        helper.setBlock(pos, MachineContent.block(MachineKind.LITHOGRAPH));
        BlockState state = helper.getBlockState(pos);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(pos), state, owner, ItemStack.EMPTY);
        StorageMachineBlockEntity machine = helper.getBlockEntity(pos, StorageMachineBlockEntity.class);
        machine.cycleMode();
        machine.cycleMode();
        MachineGameTests.insert(machine, new ItemStack(TierTwoMaterialContent.MEMORY_MASK.get()));
        MachineGameTests.insert(machine, new ItemStack(MaterialContent.DOPED_WAFER.get()));
        MachineGameTests.charge(machine, 5_000);
        return machine;
    }

    /**
     * Points pay for a node's points option and earn a skip token every 250 (standard); a token completes any node
     * whose requirements are done, never one that still waits for others, and the token item adds one to the user's
     * team.
     */
    private static void pointsAndTokens(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "research_points");
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(server, team, 249);
        helper.assertValueEqual(team.tokens(), 0, "no token at 249 points");
        Research.addPoints(server, team, 1);
        helper.assertValueEqual(team.tokens(), 1, "a token at 250 points");
        helper.assertValueEqual(Research.spendPoints(server, team, MEMORY_3), Research.Result.REQUIRES_OTHERS,
                "Memory III waits for its requirements");
        helper.assertValueEqual(Research.spendPoints(server, team, FAMILY_MASKS), Research.Result.DONE, "paid");
        helper.assertValueEqual(team.points(), 150L, "100 points spent");
        helper.assertValueEqual(Research.spendPoints(server, team, FAMILY_MASKS), Research.Result.ALREADY_COMPLETE,
                "paid once");
        helper.assertValueEqual(Research.useToken(server, team, MEMORY_3), Research.Result.REQUIRES_OTHERS,
                "a token needs the requirements too");
        helper.assertValueEqual(Research.useToken(server, team, ADVANCED_DOPING), Research.Result.DONE, "token used");
        helper.assertValueEqual(team.tokens(), 0, "token spent");
        helper.assertTrue(complete(helper, team, ADVANCED_DOPING), "skipped node complete");
        helper.assertValueEqual(Research.useToken(server, team, MEMORY_2), Research.Result.NOT_ENOUGH, "no tokens");
        helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id("no_such_node")),
                Research.Result.UNKNOWN_NODE, "unknown node");
        ItemStack tokens = new ItemStack(ResearchContent.SKIP_TOKEN.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, tokens);
        tokens.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(team.tokens(), 1, "the token item adds a token");
        // Consumed like any vanilla item: kept by players with infinite materials (creative).
        helper.assertValueEqual(player.getMainHandItem().getCount(), player.hasInfiniteMaterials() ? 2 : 1,
                "one item used");
        helper.succeed();
    }

    /**
     * Two players on one scoreboard team share research and an outsider does not: what the first completes unlocks for
     * the second, through the player and the owner-record checks, and stays locked for the outsider.
     */
    private static void teamSharing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer first = NetworkGameTests.player(level, "rs_first");
        ServerPlayer second = NetworkGameTests.player(level, "rs_second");
        ServerPlayer outsider = NetworkGameTests.player(level, "rs_outsider");
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam shared = scoreboard.addPlayerTeam("research_shared");
        try {
            scoreboard.addPlayerToTeam(first.getScoreboardName(), shared);
            scoreboard.addPlayerToTeam(second.getScoreboardName(), shared);
            ResearchTeam team = Research.team(first, true);
            helper.assertTrue(Research.team(second, true) == team, "teammates share one team");
            helper.assertFalse(Research.team(outsider, true) == team, "an outsider has their own");
            helper.assertFalse(ResearchUnlocks.isUnlocked(second, ResearchGates.MEMORY_MODULE_2), "locked at first");
            Research.addToken(server, team);
            Research.useToken(server, team, MEMORY_2);
            helper.assertTrue(ResearchUnlocks.isUnlocked(second, ResearchGates.MEMORY_MODULE_2), "shared unlock");
            helper.assertTrue(ResearchUnlocks.isUnlocked(level, OwnerRecord.of(second), ResearchGates.MEMORY_MODULE_2),
                    "shared unlock by owner record");
            helper.assertFalse(ResearchUnlocks.isUnlocked(outsider, ResearchGates.MEMORY_MODULE_2), "outsider");
            scoreboard.removePlayerFromTeam(second.getScoreboardName(), shared);
            helper.assertFalse(Research.team(second, true) == team, "leaving the team leaves its research");
        } finally {
            scoreboard.removePlayerTeam(shared);
        }
        helper.succeed();
    }

    /** A team's progress survives the save codec: members, nodes, counters, points, tokens and discoveries. */
    private static void savesAndLoads(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "research_saved");
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(server, team, 260);
        Research.useToken(server, team, MEMORY_2);
        Research.count(server, team, Research.tree(server).counterIndex("store_items"), 1234);
        var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag saved = ResearchData.CODEC.encodeStart(ops, Research.data(server)).getOrThrow();
        ResearchData loaded = ResearchData.CODEC.parse(ops, saved).getOrThrow();
        ResearchTeam back = loaded.anchoredOn(player.getUUID());
        if (back == null) {
            throw helper.assertionException(Component.literal("team not saved"));
        }
        helper.assertValueEqual(back.members(), team.members(), "members");
        helper.assertTrue(back.completed(MEMORY_2), "completed node");
        helper.assertValueEqual(back.counter("store_items"), 1234L, "counter");
        helper.assertValueEqual(back.points(), 260L, "points");
        helper.assertValueEqual(back.tokens(), 0, "tokens");
        helper.assertValueEqual(back.tokenProgress(), 10L, "token progress");
        helper.succeed();
    }

    /**
     * Unlock checks: a gate a node lists is locked until the node completes; a node id works too; an id no node names
     * and an unowned machine are always unlocked.
     */
    private static void unlockChecks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "research_gates");
        ResearchTeam team = Research.team(player, true);
        helper.assertFalse(ResearchUnlocks.isUnlocked(player, ResearchGates.MEMORY_MASK), "mask locked");
        helper.assertFalse(ResearchUnlocks.isUnlocked(player, FAMILY_MASKS), "node locked");
        helper.assertTrue(ResearchUnlocks.isUnlocked(player, MaxtechStorage.id("logic_mask")), "start node");
        helper.assertTrue(ResearchUnlocks.isUnlocked(player, MaxtechStorage.id("nothing_gates_this")), "unknown id");
        helper.assertTrue(ResearchUnlocks.isUnlocked(level, OwnerRecord.UNOWNED, ResearchGates.MEMORY_MASK), "unowned");
        Research.addPoints(level.getServer(), team, 100);
        Research.spendPoints(level.getServer(), team, FAMILY_MASKS);
        helper.assertTrue(ResearchUnlocks.isUnlocked(player, ResearchGates.MEMORY_MASK), "mask unlocked");
        helper.assertTrue(ResearchUnlocks.isUnlocked(player, ResearchGates.IO_MASK), "every listed gate");
        helper.assertTrue(ResearchUnlocks.isUnlocked(player, FAMILY_MASKS), "node unlocked");
        helper.succeed();
    }

    /**
     * Every shipped node's puzzles are registered; a node's puzzle option opens area D's session and the accepted
     * solution completes the node; an option naming an unregistered puzzle starts nothing.
     */
    private static void puzzle(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "research_puzzler");
        ResearchTeam team = Research.team(player, true);
        for (ResearchTree.Entry entry : Research.tree(helper.getLevel().getServer()).entries()) {
            for (ResearchOption option : entry.node().anyOf()) {
                if (option instanceof ResearchOption.Puzzle offered && !entry.id().equals(PUZZLE_NODE)) {
                    helper.assertTrue(Research.offered(offered), entry.id() + " names an unregistered puzzle");
                }
            }
        }
        helper.assertValueEqual(Research.startPuzzle(player, PUZZLE_NODE, 1), Research.Result.NO_PUZZLE,
                "an unregistered puzzle");
        helper.assertValueEqual(Research.startPuzzle(player, PUZZLE_NODE, 2), Research.Result.NO_SUCH_OPTION,
                "a points option is no puzzle");
        helper.assertValueEqual(Research.startPuzzle(player, PUZZLE_NODE, 0), Research.Result.DONE, "started");
        PuzzleSessions.Session session = PuzzleSessions.current(player).orElseThrow();
        int[] answer = solve(session);
        helper.runAfterDelay(PuzzleSessions.SUBMIT_COOLDOWN_TICKS, () -> {
            helper.assertFalse(complete(helper, team, PUZZLE_NODE), "not before the answer");
            PuzzleSessions.submit(new PuzzlePayloads.Submit(session.id(), IntArrayList.of(answer)), player);
            helper.assertTrue(complete(helper, team, PUZZLE_NODE), "solved");
            helper.succeed();
        });
    }

    private static <S> int[] solve(PuzzleSessions.Session session) {
        @SuppressWarnings("unchecked") // the session's type made the puzzle
        PuzzleType<S> type = (PuzzleType<S>) PuzzleTypes.get(session.puzzleId()).orElseThrow();
        return type.knownSolution(type.generate(session.seed(), session.difficulty()));
    }

    /** The expert preset doubles task numbers, point costs and the points per token. */
    private static void expert(GameTestHelper helper) {
        helper.assertValueEqual(Research.preset(), ResearchPreset.EXPERT, "preset");
        helper.assertValueEqual(Research.target(ResearchOption.Task.of(TaskType.STORE_ITEMS, 4096)), 8192L, "task");
        helper.assertValueEqual(Research.cost(new ResearchOption.Points(100)), 200L, "cost");
        helper.assertValueEqual(Research.pointsPerToken(), 500L, "points per token");
        MinecraftServer server = helper.getLevel().getServer();
        ResearchTeam team = Research.team(NetworkGameTests.player(helper.getLevel(), "research_expert"), true);
        Research.addPoints(server, team, 150);
        helper.assertValueEqual(Research.spendPoints(server, team, FAMILY_MASKS), Research.Result.NOT_ENOUGH,
                "150 points no longer pay");
        helper.succeed();
    }

    /** The off preset unlocks everything and counts nothing. */
    private static void off(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "research_off");
        helper.assertTrue(ResearchUnlocks.isUnlocked(player, ResearchGates.MEMORY_MODULE_3), "everything unlocked");
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        helper.assertValueEqual(team.points(), 0L, "no points while off");
        helper.assertValueEqual(Research.useToken(helper.getLevel().getServer(), team, MEMORY_2), Research.Result.OFF,
                "no actions while off");
        helper.succeed();
    }

    /** A dungeon chest holds a skip token at chance 1 and none at chance 0. */
    private static void chestLoot(GameTestHelper helper, boolean expected) {
        ServerLevel level = helper.getLevel();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)))
                .create(LootContextParamSets.CHEST);
        List<ItemStack> loot = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON)
                .getRandomItems(params);
        boolean token = loot.stream().anyMatch(stack -> stack.is(ResearchContent.SKIP_TOKEN.get()));
        helper.assertValueEqual(token, expected, "skip token in the dungeon chest");
        helper.succeed();
    }

    /** A controller placed by {@code owner} with power and a test drive beside it; returns the owner's team. */
    private static ResearchTeam ownedNetwork(GameTestHelper helper, ServerPlayer owner) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        BlockState state = helper.getBlockState(controller);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(controller), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        NetworkGameTests.power(helper, controller, 10_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        return Research.team(owner, true);
    }

    private static boolean complete(GameTestHelper helper, ResearchTeam team, Identifier node) {
        ResearchTree.Entry entry = Research.tree(helper.getLevel().getServer()).node(node);
        if (entry == null) {
            throw helper.assertionException(Component.literal("no research node " + node));
        }
        return Research.isComplete(team, entry);
    }

    private static void extract(NetworkState network, ItemKey key, long amount) {
        try (var transaction = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
            network.extract(key, amount, transaction);
            transaction.commit();
        }
    }
}
