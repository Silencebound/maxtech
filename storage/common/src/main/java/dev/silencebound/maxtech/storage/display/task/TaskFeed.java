package dev.silencebound.maxtech.storage.display.task;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.job.JobEvents;
import dev.silencebound.maxtech.storage.display.RowFeed;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * A task board screen's feed (M7C item 15): the rows of the board its settings name, a right-click ticks or opens a
 * hand task, and the screen looks at the open auto goals whenever their network says something they read changed: the
 * item's count (the per-key listener), the network's devices joining, leaving or being relabelled, a crafting job of
 * the item ending done (counted once per job), and research completing anywhere. Nothing is read per tick, and the
 * subscriptions follow the open goals: a board with only hand tasks listens to nothing but itself. Server thread.
 */
public final class TaskFeed implements RowFeed, KeyListener, JobEvents.DoneWatcher {
    private final RowFeed.Screen screen;
    private final Runnable boardWatcher = this::boardChanged;
    private final Runnable goalWatcher = this::goalsMoved;
    private @Nullable NetworkState bound;
    private @Nullable String key;
    private final ObjectArrayList<StackKey> listened = new ObjectArrayList<>();
    private boolean watchesDevices;
    private boolean watchesJobs;
    private boolean watchesResearch;
    private int looks;

    public TaskFeed(RowFeed.Screen screen) {
        this.screen = screen;
    }

    /** The board key the feed shows, for tests; null outside a network or for a network board without a controller. */
    public @Nullable String boardKey() {
        return key;
    }

    /** How many times the feed looked at its goals; tests use it to see that nothing polls. */
    public int looks() {
        return looks;
    }

    @Override
    public void bind(@Nullable NetworkState network) {
        bound = network;
        if (network == null) {
            return;
        }
        follow(network);
        screen.rowsChanged();
        screen.workDue();
    }

    /**
     * The board key as {@code network} names it now, listening to that board: the network's owner (and so its team) or
     * its controller can change without the screen moving, so every read checks the key again.
     */
    private @Nullable String follow(NetworkState network) {
        TaskBoardRef ref = screen.host().settings().board();
        String found = TaskBoards.key(network, ref.name(), ref.network()).orElse(null);
        String current = key;
        if (found == null ? current == null : found.equals(current)) {
            return current;
        }
        TaskBoardData data = TaskBoardData.get(network.level().getServer());
        unsubscribeGoals(network);
        if (current != null) {
            data.unlisten(current, boardWatcher);
        }
        key = found;
        if (found != null) {
            data.listen(found, boardWatcher);
            subscribeGoals(network, found);
        }
        return found;
    }

    @Override
    public void unbind() {
        NetworkState network = bound;
        String current = key;
        if (network != null) {
            unsubscribeGoals(network);
            if (current != null) {
                TaskBoardData.get(network.level().getServer()).unlisten(current, boardWatcher);
            }
        }
        bound = null;
        key = null;
    }

    /** Listens to what the board's open auto goals read; once per board change. */
    private void subscribeGoals(NetworkState network, String board) {
        List<Task> tasks = TaskBoardData.get(network.level().getServer()).tasks(board);
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (task.done() || !task.goal().auto()) {
                continue;
            }
            switch (task.goal().kind()) {
                case ITEM_COUNT -> {
                    Optional<Item> item = task.goal().item();
                    if (item.isPresent()) {
                        StackKey stack = ItemKey.of(item.get());
                        if (!listened.contains(stack)) {
                            listened.add(stack);
                            network.listenKey(stack, this);
                        }
                    }
                }
                case LABEL_COUNT -> watchesDevices = true;
                case CRAFTED -> watchesJobs = true;
                case RESEARCH -> watchesResearch = true;
                default -> {
                }
            }
        }
        if (watchesDevices) {
            network.watchDevices(goalWatcher);
        }
        if (watchesJobs) {
            JobEvents.watchDone(network, this);
        }
        if (watchesResearch) {
            TaskBoards.listenResearch(goalWatcher);
        }
    }

    private void unsubscribeGoals(NetworkState network) {
        for (int i = 0; i < listened.size(); i++) {
            network.unlistenKey(listened.get(i), this);
        }
        listened.clear();
        network.unwatchDevices(goalWatcher);
        JobEvents.unwatchDone(network, this);
        TaskBoards.unlistenResearch(goalWatcher);
        watchesDevices = false;
        watchesJobs = false;
        watchesResearch = false;
    }

    /** The board changed (here, on another screen, by logic or by a crafted count): listen again and look. */
    private void boardChanged() {
        NetworkState network = bound;
        String current = key;
        if (network != null && current != null) {
            unsubscribeGoals(network);
            subscribeGoals(network, current);
        }
        screen.rowsChanged();
        screen.workDue();
    }

    /** Something an open goal reads changed: look at the goals and show the new progress. */
    private void goalsMoved() {
        screen.workDue();
        screen.rowsChanged();
    }

    @Override
    public void keyChanged(StackKey stack, long total) {
        goalsMoved();
    }

    /**
     * A crafting job of the network ended done and made {@code amount} of {@code item}: it counts for the board's
     * crafted goals, once per job and item (a job heard from its network and its CPU's counts once).
     */
    @Override
    public void jobDone(UUID job, StackKey item, long amount) {
        NetworkState network = bound;
        String current = key;
        if (network != null && current != null && amount > 0) {
            TaskBoardData.get(network.level().getServer()).credit(current, job, item, amount);
        }
    }

    /** Ticks every open auto goal that is met now. */
    @Override
    public void work(NetworkState network, long gameTime) {
        String current = follow(network);
        if (current == null) {
            return;
        }
        looks++;
        MinecraftServer server = network.level().getServer();
        TaskBoardData data = TaskBoardData.get(server);
        // The live list: ticking a task replaces it in place, so walking by index sees each task once.
        List<Task> tasks = data.tasks(current);
        long now = server.overworld().getGameTime();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (!task.done() && task.goal().auto() && progress(network, task) >= task.goal().amount()) {
                data.setDone(current, task.id(), true, Task.BY_GOAL, "", now);
            }
        }
    }

    /**
     * How far {@code task}'s goal is on {@code network}: the item's stored count, the items crafted since the goal was
     * set, the devices with the label, 1 for a completed research step; 0 for hand and logic tasks.
     */
    public static long progress(NetworkState network, Task task) {
        TaskGoal goal = task.goal();
        return switch (goal.kind()) {
            case ITEM_COUNT -> goal.item().map(item -> network.contents().amount(ItemKey.of(item))).orElse(0L);
            case CRAFTED -> task.progress();
            case LABEL_COUNT -> network.labels().devices(goal.target()).size();
            case RESEARCH -> TaskBoards.researched(network, goal) ? 1 : 0;
            case HAND, LOGIC -> 0;
        };
    }

    @Override
    public ScreenRows rows(NetworkState network, long gameTime) {
        TaskBoardRef ref = screen.host().settings().board();
        Component title = ref.name().isEmpty()
                ? Component.translatable(TaskTexts.DEFAULT_TITLE)
                : Component.literal(ref.name());
        String current = follow(network);
        if (current == null) {
            return new ScreenRows(title, Component.empty(), ScreenRows.NO_FILL, List.of(ScreenRows.Row
                    .of(ScreenRows.BAD, Component.translatable(TaskBoards.missingKey(ref)), Component.empty())));
        }
        List<Task> tasks = TaskBoardData.get(network.level().getServer()).tasks(current);
        if (tasks.isEmpty()) {
            return new ScreenRows(title, Component.empty(), ScreenRows.NO_FILL, List
                    .of(ScreenRows.Row.of(ScreenRows.DIM, Component.translatable(TaskTexts.EMPTY), Component.empty())));
        }
        int done = 0;
        List<ScreenRows.Row> rows = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            if (task.done()) {
                done++;
            }
            rows.add(row(network, task));
        }
        return new ScreenRows(title, Component.translatable(TaskTexts.DONE_COUNT, done, tasks.size()),
                ScreenRows.perMille(done, tasks.size()), rows);
    }

    /** One task's row: a checkbox, its name (struck through and dim once done), and who did it or how far it is. */
    private static ScreenRows.Row row(NetworkState network, Task task) {
        Component name = Component.literal(task.name());
        if (task.done()) {
            Component who = switch (task.how()) {
                case Task.BY_GOAL -> Component.translatable(TaskTexts.DONE_AUTO);
                case Task.BY_LOGIC -> Component.translatable(TaskTexts.DONE_LOGIC, task.by());
                default -> Component.literal(task.by());
            };
            return ScreenRows.Row
                    .of(ScreenRows.DIM | ScreenRows.BOX | ScreenRows.CHECKED | ScreenRows.STRIKE, name, who)
                    .withTime(task.at());
        }
        TaskGoal goal = task.goal();
        Component note = switch (goal.kind()) {
            case ITEM_COUNT,
                    CRAFTED ->
                Component.translatable(TaskTexts.AUTO_PROGRESS,
                        AmountFormat.compact(Math.min(progress(network, task), goal.amount())),
                        AmountFormat.compact(goal.amount()));
            case LABEL_COUNT -> Component.translatable(TaskTexts.DEVICES_PROGRESS,
                    Math.min(progress(network, task), goal.amount()), goal.amount());
            case RESEARCH -> Component.translatable(TaskTexts.RESEARCH_OPEN);
            case LOGIC -> Component.translatable(TaskTexts.LOGIC_OPEN);
            case HAND -> Component.empty();
        };
        return ScreenRows.Row.of(ScreenRows.NORMAL | ScreenRows.BOX, name, note);
    }

    /**
     * Shown row {@code row} is clicked: a hand task is ticked or opened again by a player who may put items into the
     * network ({@link AccessLevel#DEPOSIT}). A click on any other task's row is taken and changes nothing, so a missed
     * aim does not open the settings.
     */
    @Override
    public boolean click(ServerPlayer player, NetworkState network, int row) {
        String current = follow(network);
        if (current == null || !network.accessLevel(player).atLeast(AccessLevel.DEPOSIT)) {
            return false;
        }
        MinecraftServer server = network.level().getServer();
        TaskBoardData data = TaskBoardData.get(server);
        List<Task> tasks = data.tasks(current);
        if (row >= tasks.size()) {
            return false;
        }
        Task task = tasks.get(row);
        if (task.goal().kind() == TaskGoal.Kind.HAND) {
            data.setDone(current, task.id(), !task.done(), Task.BY_HAND, player.getGameProfile().name(),
                    server.overworld().getGameTime());
        }
        return true;
    }
}
