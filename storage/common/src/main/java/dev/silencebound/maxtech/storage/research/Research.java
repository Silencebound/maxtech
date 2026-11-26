package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.MachineRuns;
import dev.silencebound.maxtech.storage.machine.ResearchWaits;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTypes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The research rules (PRD 7.2, Research; owner decisions 2026-10-03): a node completes by any one of its options once
 * every node it requires is complete. Task options complete by themselves when the team's counter reaches the target;
 * points and puzzle options are player actions, and a skip token completes any node whose requirements are done. Points
 * come from discovery (each item or fluid entering one of the team's networks for the first time) and advancements;
 * every {@code research.skipTokens.pointsPerToken} points earned also give a skip token. The preset {@code off} unlocks
 * everything and counts nothing. Server thread only.
 */
public final class Research {
    public static final String COMPLETE_KEY = "message.maxtech_storage.research.complete";
    public static final String TOKEN_KEY = "message.maxtech_storage.research.token";

    private static final List<CompletionListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<UUID>> TEAM_LISTENERS = new CopyOnWriteArrayList<>();

    private Research() {
    }

    /** Told whenever a team completes a node, by any option or a skip token. */
    @FunctionalInterface
    public interface CompletionListener {
        /** {@code team} completed {@code node}; server thread. */
        void completed(MinecraftServer server, ResearchTeam team, ResearchTree.Entry node);
    }

    /**
     * Adds a listener told about every completed node, e.g. so machines waiting for research wake up; call during mod
     * construction or setup.
     */
    public static void onCompleted(CompletionListener listener) {
        LISTENERS.add(listener);
    }

    /**
     * Runs {@code listener} with a player's id whenever the player moves to another research team (a controller's
     * anchor then counts against the new team's chunk cap). Register during mod construction; server thread.
     */
    public static void onTeamChanged(Consumer<UUID> listener) {
        TEAM_LISTENERS.add(listener);
    }

    /** Tells the team-change listeners that {@code player} moved to another team. */
    static void teamChanged(UUID player) {
        for (Consumer<UUID> listener : TEAM_LISTENERS) {
            listener.accept(player);
        }
    }

    /** Whether {@code owner} (a machine's or network's owner) belongs to {@code team}. */
    public static boolean inTeam(MinecraftServer server, OwnerRecord owner, ResearchTeam team) {
        return data(server).team(server, owner) == team;
    }

    /** What a player action came to. */
    public enum Result {
        DONE, OFF, UNKNOWN_NODE, ALREADY_COMPLETE, REQUIRES_OTHERS, NO_SUCH_OPTION, NOT_ENOUGH, NO_PUZZLE;
    }

    /** Hooks research into the network, machine and puzzle events; called once during mod construction. */
    public static void init() {
        NetworkState.observeNetworks(NetworkResearch::new);
        MachineRuns.listen(Research::machineRun);
        PuzzleSessions.onSolved(Research::puzzleSolved);
        onCompleted((server, team, node) -> ResearchWaits.wake(owner -> inTeam(server, owner, team)));
        Platform.get().lifecycle().onServerStopped(server -> ResearchWaits.clear());
    }

    public static ResearchPreset preset() {
        return StorageServerConfig.RESEARCH_PRESET.get();
    }

    /** Whether research counts anything; false at the {@code off} preset. */
    public static boolean active() {
        return preset() != ResearchPreset.OFF;
    }

    /** The preset's multiplier of task numbers, point costs and points per token. */
    public static double scale() {
        return ResearchScaling.scale(preset(), StorageServerConfig.RESEARCH_RELAXED_SCALE.getAsDouble(),
                StorageServerConfig.RESEARCH_EXPERT_SCALE.getAsDouble());
    }

    public static ResearchTree tree(MinecraftServer server) {
        return ResearchTree.of(server.registryAccess());
    }

    public static ResearchData data(MinecraftServer server) {
        return ResearchData.get(server);
    }

    /** {@code player}'s team; see {@link ResearchData#team(ServerPlayer, boolean)}. */
    public static ResearchTeam team(ServerPlayer player, boolean recheck) {
        return data(player.level().getServer()).team(player, recheck);
    }

    /** Whether {@code entry} is complete for {@code team}. */
    public static boolean isComplete(ResearchTeam team, ResearchTree.Entry entry) {
        return entry.startNode() || team.completed.contains(entry.id());
    }

    /** Whether every node {@code entry} requires is complete; unknown requirements are ignored. */
    public static boolean isAvailable(MinecraftServer server, ResearchTeam team, ResearchTree.Entry entry) {
        ResearchTree tree = tree(server);
        for (Identifier required : entry.node().requires()) {
            ResearchTree.Entry parent = tree.node(required);
            if (parent != null && !isComplete(team, parent)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code task} can be offered in this pack: a task naming an item that is not registered, or a tag that
     * holds no item, is skipped (PRD 7.2).
     */
    public static boolean offered(ResearchOption.Task task) {
        if (task.item().isPresent()) {
            return BuiltInRegistries.ITEM.containsKey(task.item().get());
        }
        if (task.tag().isPresent()) {
            return BuiltInRegistries.ITEM.get(task.tag().get()).map(set -> set.size() > 0).orElse(false);
        }
        return true;
    }

    /** The number {@code task} asks for at the current preset and pack size. */
    public static long target(ResearchOption.Task task) {
        double scale = scale();
        return switch (task.task()) {
            case STORE_KINDS -> task.fraction() > 0
                    ? ResearchScaling.count(
                            ResearchScaling.kinds(task.fraction(), task.max(), BuiltInRegistries.ITEM.size()), scale)
                    : ResearchScaling.count(task.count(), scale);
            case CELL_FILL -> ResearchScaling.percent(task.count(), scale);
            default -> ResearchScaling.count(task.count(), scale);
        };
    }

    /** The points {@code option} costs at the current preset. */
    public static long cost(ResearchOption.Points option) {
        return ResearchScaling.cost(option.cost(), scale());
    }

    /** Points earned per skip token at the current preset. */
    public static long pointsPerToken() {
        return ResearchScaling.count(StorageServerConfig.RESEARCH_POINTS_PER_TOKEN.getAsLong(), scale());
    }

    /**
     * Whether the puzzle {@code option} names is registered and enabled in the server config; one that is not cannot be
     * started.
     */
    public static boolean offered(ResearchOption.Puzzle option) {
        return PuzzleTypes.get(option.puzzle()).isPresent() && StorageServerConfig.puzzleEnabled(option.puzzle());
    }

    /** The difficulty {@code option} is played at: its own plus the puzzle's configured offset, clamped. */
    public static int difficulty(ResearchOption.Puzzle option) {
        return PuzzleType
                .clampDifficulty(option.difficulty() + StorageServerConfig.puzzleDifficultyOffset(option.puzzle()));
    }

    /** Whether {@code task} is met by {@code team}'s counter. */
    public static boolean met(ResearchTeam team, ResearchOption.Task task) {
        return offered(task) && team.counter(task.counter()) >= target(task);
    }

    /**
     * Counts towards {@code counter}: for a {@link TaskType#highest} type {@code value} is a level reached and the
     * counter keeps the highest one, otherwise it is added. Completes the nodes whose tasks the new value meets.
     */
    public static void count(MinecraftServer server, ResearchTeam team, int counter, long value) {
        ResearchTree.Counter read = tree(server).counter(counter);
        long before = team.counters.getLong(read.name());
        long after = read.task().task().highest() ? Math.max(before, value) : before + value;
        if (after == before) {
            return;
        }
        team.counters.put(read.name(), after);
        team.dirty();
        List<ResearchTree.Entry> readers = read.readers();
        for (int i = 0; i < readers.size(); i++) {
            checkTasks(server, team, readers.get(i));
        }
    }

    /** Completes {@code entry} when it is available, not complete and one of its offered tasks is met. */
    private static void checkTasks(MinecraftServer server, ResearchTeam team, ResearchTree.Entry entry) {
        if (isComplete(team, entry) || !isAvailable(server, team, entry)) {
            return;
        }
        for (ResearchOption option : entry.node().anyOf()) {
            if (option instanceof ResearchOption.Task task && met(team, task)) {
                complete(server, team, entry);
                return;
            }
        }
    }

    private static void complete(MinecraftServer server, ResearchTeam team, ResearchTree.Entry entry) {
        team.completed.add(entry.id());
        team.dirty();
        for (CompletionListener listener : LISTENERS) {
            listener.completed(server, team, entry);
        }
        tell(server, team,
                Component.translatable(COMPLETE_KEY, Component.translatable(ResearchNode.nameKey(entry.id()))));
        for (ResearchTree.Entry dependant : entry.dependants()) {
            checkTasks(server, team, dependant);
        }
    }

    /** Adds {@code points} to the team, paying out a skip token for every {@link #pointsPerToken()} earned. */
    public static void addPoints(MinecraftServer server, ResearchTeam team, long points) {
        if (points <= 0 || !active()) {
            return;
        }
        team.points += points;
        team.tokenProgress += points;
        long perToken = pointsPerToken();
        while (team.tokenProgress >= perToken) {
            team.tokenProgress -= perToken;
            team.tokens++;
            tell(server, team, Component.translatable(TOKEN_KEY));
        }
        team.dirty();
    }

    /** Gives the team one skip token (the token item). */
    public static void addToken(MinecraftServer server, ResearchTeam team) {
        team.tokens++;
        team.dirty();
    }

    /** Completes {@code node} by spending the points of its cheapest points option. */
    public static Result spendPoints(MinecraftServer server, ResearchTeam team, Identifier node) {
        ResearchTree.Entry entry = tree(server).node(node);
        Result refused = refusal(server, team, entry);
        if (refused != null || entry == null) {
            return refused == null ? Result.UNKNOWN_NODE : refused;
        }
        long cost = Long.MAX_VALUE;
        for (ResearchOption option : entry.node().anyOf()) {
            if (option instanceof ResearchOption.Points points) {
                cost = Math.min(cost, cost(points));
            }
        }
        if (cost == Long.MAX_VALUE) {
            return Result.NO_SUCH_OPTION;
        }
        if (team.points < cost) {
            return Result.NOT_ENOUGH;
        }
        team.points -= cost;
        complete(server, team, entry);
        return Result.DONE;
    }

    /** Completes {@code node} with one of the team's skip tokens. */
    public static Result useToken(MinecraftServer server, ResearchTeam team, Identifier node) {
        ResearchTree.Entry entry = tree(server).node(node);
        Result refused = refusal(server, team, entry);
        if (refused != null || entry == null) {
            return refused == null ? Result.UNKNOWN_NODE : refused;
        }
        if (team.tokens <= 0) {
            return Result.NOT_ENOUGH;
        }
        team.tokens--;
        complete(server, team, entry);
        return Result.DONE;
    }

    /**
     * Opens option {@code option} of {@code node} (a puzzle) for {@code player}; solving it completes the node for the
     * player's team.
     */
    public static Result startPuzzle(ServerPlayer player, Identifier node, int option) {
        MinecraftServer server = player.level().getServer();
        ResearchTeam team = team(player, false);
        ResearchTree.Entry entry = tree(server).node(node);
        Result refused = refusal(server, team, entry);
        if (refused != null || entry == null) {
            return refused == null ? Result.UNKNOWN_NODE : refused;
        }
        List<ResearchOption> options = entry.node().anyOf();
        if (option < 0 || option >= options.size() || !(options.get(option) instanceof ResearchOption.Puzzle puzzle)) {
            return Result.NO_SUCH_OPTION;
        }
        if (!offered(puzzle)) {
            return Result.NO_PUZZLE;
        }
        return PuzzleSessions.start(player, node, puzzle.puzzle(), player.getRandom().nextLong(), difficulty(puzzle))
                .isPresent() ? Result.DONE : Result.NO_PUZZLE;
    }

    private static @Nullable Result refusal(MinecraftServer server, ResearchTeam team,
            ResearchTree.@Nullable Entry entry) {
        if (!active()) {
            return Result.OFF;
        }
        if (entry == null) {
            return Result.UNKNOWN_NODE;
        }
        if (isComplete(team, entry)) {
            return Result.ALREADY_COMPLETE;
        }
        return isAvailable(server, team, entry) ? null : Result.REQUIRES_OTHERS;
    }

    /** A solved puzzle completes its node when the node still offers that puzzle at that difficulty. */
    private static void puzzleSolved(ServerPlayer player, Identifier node, Identifier puzzle, int difficulty) {
        MinecraftServer server = player.level().getServer();
        ResearchTeam team = team(player, false);
        ResearchTree.Entry entry = tree(server).node(node);
        if (refusal(server, team, entry) != null || entry == null) {
            return;
        }
        for (ResearchOption option : entry.node().anyOf()) {
            if (option instanceof ResearchOption.Puzzle offered && offered.puzzle().equals(puzzle) && offered(offered)
                    && difficulty(offered) <= difficulty) {
                complete(server, team, entry);
                return;
            }
        }
    }

    /** A key entered one of the team's networks for the first time in that network; new kinds give points. */
    static void entered(MinecraftServer server, ResearchTeam team, StackKey key) {
        boolean added = switch (key) {
            case ItemKey item -> team.discoveredItems.add(BuiltInRegistries.ITEM.getKey(item.item()));
            case FluidKey fluid -> team.discoveredFluids.add(BuiltInRegistries.FLUID.getKey(fluid.fluid()));
        };
        if (added) {
            team.dirty();
            addPoints(server, team, StorageServerConfig.RESEARCH_DISCOVERY_POINTS.getAsLong());
        }
    }

    /**
     * {@code player} earned advancement {@code id}; the first time anyone on the team earns it, the team gets points.
     * Advancements without a display (recipe unlocks) give nothing; the caller passes only displayed ones.
     */
    public static void advancementEarned(ServerPlayer player, Identifier id) {
        if (!active()) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        ResearchTeam team = team(player, false);
        if (team.advancements.add(id)) {
            team.dirty();
            addPoints(server, team, StorageServerConfig.RESEARCH_ADVANCEMENT_POINTS.getAsLong());
        }
    }

    /** A machine finished a run: machine, refine and processor tasks of its owner's team. */
    private static void machineRun(ServerLevel level, StorageMachineBlockEntity machine, OwnerRecord owner,
            MachineKind kind, Optional<String> mode, List<ItemStack> made) {
        if (!active() || !owner.isOwned()) {
            return;
        }
        MinecraftServer server = level.getServer();
        ResearchTree tree = tree(server);
        ResearchTeam team = data(server).team(server, owner);
        if (team == null) {
            return;
        }
        add(server, team, tree, TaskType.MACHINE_RUNS.getSerializedName(), 1);
        add(server, team, tree, TaskType.MACHINE_RUNS.getSerializedName() + "/machine/" + kind.id(), 1);
        long processors = 0;
        long total = 0;
        for (ItemStack stack : made) {
            total += stack.getCount();
            if (stack.is(ResearchContent.PROCESSORS)) {
                processors += stack.getCount();
            }
        }
        if (mode.isPresent() && mode.get().equals("refine")) {
            add(server, team, tree, TaskType.REFINE_AMOUNT.getSerializedName(), total);
        }
        add(server, team, tree, TaskType.PROCESSORS_MADE.getSerializedName(), processors);
    }

    private static void add(MinecraftServer server, ResearchTeam team, ResearchTree tree, String counter, long value) {
        int index = tree.counterIndex(counter);
        if (index >= 0 && value > 0) {
            count(server, team, index, value);
        }
    }

    private static void tell(MinecraftServer server, ResearchTeam team, Component message) {
        for (UUID member : team.members) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }
    }
}
