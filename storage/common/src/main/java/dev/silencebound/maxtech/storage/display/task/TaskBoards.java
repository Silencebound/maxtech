package dev.silencebound.maxtech.storage.display.task;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Where task boards live and what reaches them from outside a screen (M7C item 15). A board is shared by everything
 * that names it the same way: by name within the team of the network's owner (the owner's research team, else the owner
 * alone), or by name within one network, which is its single controller's position. Logic Controllers read and set
 * tasks through the same keys, and crafting jobs and completed research reach the boards' screens through here. Server
 * thread.
 */
public final class TaskBoards {
    /** Screens showing boards, told when research completes; cleared when the server stops. */
    private static final ObjectArrayList<Runnable> RESEARCH_LISTENERS = new ObjectArrayList<>();

    private TaskBoards() {
    }

    /** Hooks into research; once, during mod construction. */
    public static void init() {
        Research.onCompleted((server, team, node) -> {
            Runnable[] copy = RESEARCH_LISTENERS.toArray(new Runnable[0]);
            for (Runnable listener : copy) {
                listener.run();
            }
        });
        Platform.get().lifecycle().onServerStopped(server -> {
            RESEARCH_LISTENERS.clear();
        });
    }

    /** Runs {@code listener} whenever any team completes a research node; it must only mark work. */
    static void listenResearch(Runnable listener) {
        if (!RESEARCH_LISTENERS.contains(listener)) {
            RESEARCH_LISTENERS.add(listener);
        }
    }

    static void unlistenResearch(Runnable listener) {
        RESEARCH_LISTENERS.remove(listener);
    }

    /** The team that owns {@code owner}'s boards: its research team's anchor, else the owner; nil when unowned. */
    public static UUID team(MinecraftServer server, OwnerRecord owner) {
        if (!owner.isOwned()) {
            return Util.NIL_UUID;
        }
        ResearchTeam team = Research.data(server).team(server, owner);
        return team != null ? team.anchor() : owner.owner();
    }

    /**
     * The key of the board named {@code name} as seen from {@code network}: within the team of its owner, or with
     * {@code networkScope} within the network itself (its single controller's position); empty for a network board on a
     * network without exactly one controller, and for a team board on a network nobody owns (unowned networks would
     * otherwise share one "team" across the server).
     */
    public static Optional<String> key(NetworkState network, String name, boolean networkScope) {
        String board = TaskBoardData.cleanName(name).toLowerCase(Locale.ROOT);
        if (networkScope) {
            NetworkDevice controller = network.controllerDevice();
            if (controller == null) {
                return Optional.empty();
            }
            return Optional.of(
                    networkPrefix(network.level().dimension().identifier().toString(), controller.nodePos()) + board);
        }
        if (!network.owner().isOwned()) {
            return Optional.empty();
        }
        return Optional.of("t/" + team(network.level().getServer(), network.owner()) + "/" + board);
    }

    /** The start of the keys of every network board of the controller at {@code pos} in {@code dimension}. */
    public static String networkPrefix(String dimension, long pos) {
        return "n/" + dimension + "/" + pos + "/";
    }

    /** The lang key saying why a screen with board {@code ref} has no board on its network. */
    public static String missingKey(TaskBoardRef ref) {
        return ref.network() ? TaskTexts.NEEDS_CONTROLLER : TaskTexts.NEEDS_OWNER;
    }

    /** The controller at {@code pos} in {@code dimension} was broken: its network boards go with it. */
    public static void controllerBroken(MinecraftServer server, String dimension, long pos) {
        TaskBoardData.get(server).dropNetwork(networkPrefix(dimension, pos));
    }

    /**
     * The controller of a network moved from {@code from} to {@code to} in {@code dimension} (its multiblock's primary
     * block was broken and another block took over, M7R item 1): its network boards go along.
     */
    public static void controllerMoved(MinecraftServer server, String dimension, long from, long to) {
        TaskBoardData.get(server).moveNetwork(networkPrefix(dimension, from), networkPrefix(dimension, to));
    }

    /**
     * Why {@code goal} cannot be set, as a lang key; empty when it can. Beyond {@link TaskGoal#problem}, a research
     * goal must name a node of the server's tree.
     */
    public static Optional<String> problem(MinecraftServer server, TaskGoal goal) {
        Optional<String> own = goal.problem();
        if (own.isPresent()) {
            return own;
        }
        Optional<Identifier> node = goal.research();
        if (node.isPresent() && Research.tree(server).node(node.get()) == null) {
            return Optional.of(TaskTexts.NO_RESEARCH);
        }
        return Optional.empty();
    }

    /** Whether the team behind {@code network}'s owner completed the node of a research goal. */
    static boolean researched(NetworkState network, TaskGoal goal) {
        Optional<Identifier> node = goal.research();
        MinecraftServer server = network.level().getServer();
        if (node.isEmpty() || !network.owner().isOwned()) {
            return false;
        }
        ResearchTree.Entry entry = Research.tree(server).node(node.get());
        ResearchTeam team = Research.data(server).team(server, network.owner());
        return entry != null && team != null && Research.isComplete(team, entry);
    }

    // Logic Controllers (M7C item 15): a node names a board, a task and the scope in one text (TaskNames).

    /** The board key and task name a logic name ({@link TaskNames}) means on {@code network}; empty for none. */
    private static Optional<String[]> logicTarget(NetworkState network, String logicName) {
        String[] parts = logicName.split("\n", 3);
        if (parts.length != 3 || parts[2].isEmpty()) {
            return Optional.empty();
        }
        Optional<String> key = key(network, parts[1], parts[0].equals("n"));
        return key.map(k -> new String[]{k, parts[2]});
    }

    /**
     * What a logic "task" node reads: whether the named task is done (bit 0) and its progress shifted left by one (the
     * live count of an item count or labelled devices goal, the crafted count of a crafted goal); 0 for no such task.
     */
    public static long logicValue(NetworkState network, String logicName) {
        Optional<String[]> target = logicTarget(network, logicName);
        if (target.isEmpty()) {
            return 0;
        }
        Optional<Task> task = TaskBoardData.get(network.level().getServer()).find(target.get()[0], target.get()[1]);
        if (task.isEmpty()) {
            return 0;
        }
        long progress = Math.min(TaskGoal.MAX_AMOUNT, TaskFeed.progress(network, task.get()));
        return (progress << 1) | (task.get().done() ? 1 : 0);
    }

    /**
     * A Logic Controller named {@code controller} on {@code network} sets the named task done or open. Returns whether
     * the task exists (it may already have been so).
     */
    public static boolean setByLogic(NetworkState network, String logicName, boolean done, String controller) {
        Optional<String[]> target = logicTarget(network, logicName);
        if (target.isEmpty()) {
            return false;
        }
        MinecraftServer server = network.level().getServer();
        TaskBoardData data = TaskBoardData.get(server);
        Optional<Task> task = data.find(target.get()[0], target.get()[1]);
        if (task.isEmpty()) {
            return false;
        }
        data.setDone(target.get()[0], task.get().id(), done, Task.BY_LOGIC, controller,
                server.overworld().getGameTime());
        return true;
    }

    /** Runs {@code listener} after every change of any board on {@code server}'s boards (logic). */
    public static void listenAll(MinecraftServer server, Runnable listener) {
        TaskBoardData.get(server).listenAll(listener);
    }

    public static void unlistenAll(MinecraftServer server, Runnable listener) {
        TaskBoardData.get(server).unlistenAll(listener);
    }
}
