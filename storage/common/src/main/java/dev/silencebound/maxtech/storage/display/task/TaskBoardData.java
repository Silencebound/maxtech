package dev.silencebound.maxtech.storage.display.task;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Every task board on the server (M7C item 15), one saved file: boards by key ({@link TaskBoards#key}), each a name and
 * at most {@link #MAX_TASKS} tasks in the order they were added. A board with no tasks left is dropped, so the file
 * only holds boards in use, and at most {@link #MAX_BOARDS} boards exist. Screens and Logic Controllers listen per
 * board or to every board; every change tells them after it is made. Server thread.
 */
public final class TaskBoardData extends SavedData {
    /** Tasks per board: a wall shows at most this many rows. */
    public static final int MAX_TASKS = ScreenRows.MAX_ROWS;
    /** Boards on the server; adding the first task to a new board past this is refused. */
    public static final int MAX_BOARDS = 4096;
    /** Boards of one team or one network, so one player cannot fill the server's. */
    public static final int MAX_BOARDS_PER_OWNER = 32;
    /** Longest board name. */
    public static final int MAX_BOARD_NAME = 32;

    private record SavedBoard(String key, String name, int nextId, List<Task> tasks) {
        static final Codec<SavedBoard> CODEC = RecordCodecBuilder.create(i -> i
                .group(Codec.STRING.fieldOf("key").forGetter(SavedBoard::key),
                        Codec.STRING.optionalFieldOf("name", "").forGetter(SavedBoard::name),
                        Codec.INT.optionalFieldOf("next_id", 0).forGetter(SavedBoard::nextId),
                        Task.CODEC.listOf().optionalFieldOf("tasks", List.of()).forGetter(SavedBoard::tasks))
                .apply(i, SavedBoard::new));
    }

    public static final Codec<TaskBoardData> CODEC = SavedBoard.CODEC.listOf().xmap(TaskBoardData::new,
            TaskBoardData::saved);
    public static final SavedDataType<TaskBoardData> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("task_boards"), TaskBoardData::new, CODEC);

    /** What became of an edit. */
    public enum Result {
        DONE, NO_SUCH_TASK, FULL, TOO_MANY_BOARDS, EMPTY_NAME
    }

    /** One board: its shown name and tasks, and which job's crafts it last counted. */
    private static final class Board {
        String name;
        final ObjectArrayList<Task> tasks = new ObjectArrayList<>();
        int nextId;
        /** The crafting job whose outputs were last counted, and the items counted for it (not saved). */
        @Nullable
        UUID creditJob;
        final ObjectOpenHashSet<StackKey> credited = new ObjectOpenHashSet<>();

        Board(String name) {
            this.name = name;
        }

        int indexOf(int id) {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id() == id) {
                    return i;
                }
            }
            return -1;
        }
    }

    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, ObjectArrayList<Runnable>> listeners = new LinkedHashMap<>();
    private final ObjectArrayList<Runnable> everyBoard = new ObjectArrayList<>();

    private TaskBoardData() {
    }

    private TaskBoardData(List<SavedBoard> saved) {
        for (SavedBoard entry : saved) {
            if (entry.tasks().isEmpty() || boards.size() >= MAX_BOARDS) {
                continue;
            }
            Board board = new Board(cleanName(entry.name()));
            int next = entry.nextId();
            for (Task task : entry.tasks()) {
                if (board.tasks.size() < MAX_TASKS && board.indexOf(task.id()) < 0) {
                    board.tasks.add(task);
                    next = Math.max(next, task.id() + 1);
                }
            }
            board.nextId = next;
            boards.put(entry.key(), board);
        }
    }

    private List<SavedBoard> saved() {
        List<SavedBoard> out = new ArrayList<>(boards.size());
        for (Map.Entry<String, Board> entry : boards.entrySet()) {
            Board board = entry.getValue();
            out.add(new SavedBoard(entry.getKey(), board.name, board.nextId, List.copyOf(board.tasks)));
        }
        return out;
    }

    /** The server's task boards. */
    public static TaskBoardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** A board name as kept: one line, trimmed, at most {@link #MAX_BOARD_NAME} characters. */
    public static String cleanName(String name) {
        return Task.clean(name, MAX_BOARD_NAME);
    }

    /** The tasks of board {@code key} in their order; empty for a board with none. Read-only. */
    public List<Task> tasks(String key) {
        Board board = boards.get(key);
        return board == null ? List.of() : Collections.unmodifiableList(board.tasks);
    }

    /** The board's shown name; empty for a board with no tasks. */
    public String name(String key) {
        Board board = boards.get(key);
        return board == null ? "" : board.name;
    }

    /** The task with {@code id} on board {@code key}, or null. */
    public @Nullable Task task(String key, int id) {
        Board board = boards.get(key);
        int index = board == null ? -1 : board.indexOf(id);
        return board == null || index < 0 ? null : board.tasks.get(index);
    }

    /** The first task on board {@code key} named {@code name}, ignoring case and outer spaces; empty for none. */
    public Optional<Task> find(String key, String name) {
        Board board = boards.get(key);
        if (board == null) {
            return Optional.empty();
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < board.tasks.size(); i++) {
            if (board.tasks.get(i).name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(board.tasks.get(i));
            }
        }
        return Optional.empty();
    }

    /** Boards on the server; for tests. */
    public int boardCount() {
        return boards.size();
    }

    /** Adds a task named {@code name} with {@code goal} to board {@code key}, shown as {@code boardName}. */
    public Result add(String key, String boardName, String name, TaskGoal goal) {
        String clean = Task.clean(name, Task.MAX_NAME);
        if (clean.isEmpty()) {
            return Result.EMPTY_NAME;
        }
        Board board = boards.get(key);
        if (board == null) {
            if (boards.size() >= MAX_BOARDS || boardsOf(owner(key)) >= MAX_BOARDS_PER_OWNER) {
                return Result.TOO_MANY_BOARDS;
            }
            board = new Board(cleanName(boardName));
            boards.put(key, board);
        }
        if (board.tasks.size() >= MAX_TASKS) {
            return Result.FULL;
        }
        board.name = cleanName(boardName);
        board.tasks.add(new Task(board.nextId++, clean, goal, false, 0, "", 0, 0));
        changed(key);
        return Result.DONE;
    }

    /** Renames task {@code id} of board {@code key} and sets its goal (a new goal starts its count over). */
    public Result save(String key, int id, String name, TaskGoal goal) {
        String clean = Task.clean(name, Task.MAX_NAME);
        if (clean.isEmpty()) {
            return Result.EMPTY_NAME;
        }
        Board board = boards.get(key);
        int index = board == null ? -1 : board.indexOf(id);
        if (board == null || index < 0) {
            return Result.NO_SUCH_TASK;
        }
        Task before = board.tasks.get(index);
        Task after = before.withName(clean).withGoal(goal);
        if (!after.equals(before)) {
            board.tasks.set(index, after);
            changed(key);
        }
        return Result.DONE;
    }

    /** Removes task {@code id} of board {@code key}; a board left empty is dropped. */
    public Result remove(String key, int id) {
        Board board = boards.get(key);
        int index = board == null ? -1 : board.indexOf(id);
        if (board == null || index < 0) {
            return Result.NO_SUCH_TASK;
        }
        board.tasks.remove(index);
        if (board.tasks.isEmpty()) {
            boards.remove(key);
        }
        changed(key);
        return Result.DONE;
    }

    /**
     * Ticks task {@code id} of board {@code key} ({@code how} and {@code by} say who, {@code at} when), or opens it
     * again with {@code done} false. Returns whether it changed.
     */
    public boolean setDone(String key, int id, boolean done, int how, String by, long at) {
        Board board = boards.get(key);
        int index = board == null ? -1 : board.indexOf(id);
        if (board == null || index < 0 || board.tasks.get(index).done() == done) {
            return false;
        }
        board.tasks.set(index, board.tasks.get(index).withDone(done, how, by, at));
        changed(key);
        return true;
    }

    /**
     * A crafting job {@code job} made {@code amount} of {@code item}: every open crafted goal of board {@code key} for
     * that item counts it, once per job and item even when the job's network and its CPU's both report it.
     */
    public void credit(String key, UUID job, StackKey item, long amount) {
        Board board = boards.get(key);
        if (board == null || amount <= 0 || !(item instanceof ItemKey stack)) {
            return;
        }
        if (!job.equals(board.creditJob)) {
            board.creditJob = job;
            board.credited.clear();
        }
        if (!board.credited.add(item)) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < board.tasks.size(); i++) {
            Task task = board.tasks.get(i);
            Optional<Item> wanted = task.goal().item();
            if (!task.done() && task.goal().kind() == TaskGoal.Kind.CRAFTED && wanted.isPresent()
                    && wanted.get().equals(stack.item())) {
                board.tasks.set(i, task.withProgress(Math.min(TaskGoal.MAX_AMOUNT, task.progress() + amount)));
                changed = true;
            }
        }
        if (changed) {
            changed(key);
        }
    }

    /** The part of a board key naming its team or network: everything up to the board's name. */
    private static String owner(String key) {
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    private int boardsOf(String owner) {
        int count = 0;
        for (String key : boards.keySet()) {
            if (key.startsWith(owner)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Drops every board of the network whose controller at {@code pos} in {@code dimension} was broken
     * ({@link TaskBoards#networkPrefix}); its screens then show an empty board. Returns how many went.
     */
    public int dropNetwork(String prefix) {
        List<String> gone = new ArrayList<>();
        for (String key : boards.keySet()) {
            if (key.startsWith(prefix)) {
                gone.add(key);
            }
        }
        for (String key : gone) {
            boards.remove(key);
            changed(key);
        }
        return gone.size();
    }

    /**
     * Moves every board whose key starts with {@code from} to the same key under {@code to}: the network's controller
     * moved to another block of its multiblock (M7R item 1), and its boards go along. A board already under the new key
     * is kept and the moved one dropped (cannot happen for one network). Returns how many moved.
     */
    public int moveNetwork(String from, String to) {
        List<String> moving = new ArrayList<>();
        for (String key : boards.keySet()) {
            if (key.startsWith(from)) {
                moving.add(key);
            }
        }
        int moved = 0;
        for (String key : moving) {
            Board board = boards.remove(key);
            String target = to + key.substring(from.length());
            if (board != null && boards.putIfAbsent(target, board) == null) {
                moved++;
            }
            changed(key);
            changed(target);
        }
        return moved;
    }

    /** Runs {@code listener} after every change of board {@code key}; it must only mark work and wake a task. */
    // Listeners are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public void listen(String key, Runnable listener) {
        ObjectArrayList<Runnable> list = listeners.computeIfAbsent(key, k -> new ObjectArrayList<>(1));
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == listener) {
                return;
            }
        }
        list.add(listener);
    }

    /** Stops running {@code listener} for board {@code key}. */
    public void unlisten(String key, Runnable listener) {
        ObjectArrayList<Runnable> list = listeners.get(key);
        if (list != null) {
            list.remove(listener);
            if (list.isEmpty()) {
                listeners.remove(key);
            }
        }
    }

    /** Runs {@code listener} after every change of any board (Logic Controllers reading tasks). */
    public void listenAll(Runnable listener) {
        if (!everyBoard.contains(listener)) {
            everyBoard.add(listener);
        }
    }

    public void unlistenAll(Runnable listener) {
        everyBoard.remove(listener);
    }

    private void changed(String key) {
        setDirty();
        ObjectArrayList<Runnable> list = listeners.get(key);
        if (list != null) {
            Runnable[] copy = list.toArray(new Runnable[0]);
            for (Runnable listener : copy) {
                listener.run();
            }
        }
        Runnable[] all = everyBoard.toArray(new Runnable[0]);
        for (Runnable listener : all) {
            listener.run();
        }
    }
}
