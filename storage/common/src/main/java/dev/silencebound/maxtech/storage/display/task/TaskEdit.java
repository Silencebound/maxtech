package dev.silencebound.maxtech.storage.display.task;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayDevice;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Client to server: an edit in a screen's task board editor (M7C item 15), for the screen behind the open display menu
 * {@code containerId}. The server treats it as hostile: it must name the player's open, valid display menu (the menu
 * checks that the player may configure the screen), one edit per player per tick, a name of at most
 * {@link Task#MAX_NAME} characters and a goal that names an item, label or research node that exists. Every edit, a
 * refused one too, is answered with the board as it is now ({@link TaskBoardSync}).
 *
 * @param op
 *            {@link #WATCH}, {@link #BOARD}, {@link #ADD}, {@link #SAVE} or {@link #REMOVE}
 * @param task
 *            the task's id for save and remove
 * @param text
 *            the board's name for board, the task's name for add and save
 * @param network
 *            for board: whether the board is the network's rather than the team's
 */
public record TaskEdit(int containerId, int op, int task, String text, TaskGoal goal,
        boolean network) implements CustomPacketPayload {
    /** Only asks for the board. */
    public static final int WATCH = 0;
    /** Shows another board: {@code text} names it, {@code network} picks the scope. */
    public static final int BOARD = 1;
    public static final int ADD = 2;
    public static final int SAVE = 3;
    public static final int REMOVE = 4;

    public static final Type<TaskEdit> TYPE = new Type<>(MaxtechStorage.id("task_edit"));
    public static final StreamCodec<ByteBuf, TaskEdit> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
            TaskEdit::containerId, ByteBufCodecs.VAR_INT, TaskEdit::op, ByteBufCodecs.VAR_INT, TaskEdit::task,
            ByteBufCodecs.stringUtf8(Task.MAX_NAME * 4), TaskEdit::text, TaskGoal.STREAM_CODEC, TaskEdit::goal,
            ByteBufCodecs.BOOL, TaskEdit::network, TaskEdit::new);

    /** The game tick of each player's last edit. */
    private static final Object2LongOpenHashMap<UUID> LAST_EDIT = new Object2LongOpenHashMap<>();

    static {
        LAST_EDIT.defaultReturnValue(Long.MIN_VALUE);
    }

    @Override
    public Type<TaskEdit> type() {
        return TYPE;
    }

    /** Forgets the edit ticks; when the server stops. */
    static void clear() {
        LAST_EDIT.clear();
    }

    static void handle(TaskEdit edit, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            apply(serverPlayer, edit);
        }
    }

    /**
     * Applies {@code edit} from {@code player} as the payload handler does and answers with the board; returns the
     * answer's message key (empty when there is nothing to say), or null when the edit named no open display menu (no
     * answer then). Server thread.
     */
    public static @org.jspecify.annotations.Nullable String apply(ServerPlayer player, TaskEdit edit) {
        if (!(player.containerMenu instanceof DisplayMenu menu) || menu.containerId != edit.containerId) {
            return null;
        }
        DisplayDevice device = menu.device();
        if (device == null || player.isSpectator() || !menu.stillValid(player)) {
            return null;
        }
        long tick = player.level().getGameTime();
        // A second edit in the same tick changes nothing; the answer shows the board as it is.
        String message = edit.op != WATCH && LAST_EDIT.put(player.getUUID(), tick) == tick
                ? ""
                : change(player, device, edit);
        TaskBoardSync.send(player, edit.containerId, device, message);
        return message;
    }

    private static String change(ServerPlayer player, DisplayDevice device, TaskEdit edit) {
        MinecraftServer server = player.level().getServer();
        if (edit.op == BOARD) {
            DisplaySettings settings = device.settings();
            device.applySettings(settings.withBoard(new TaskBoardRef(edit.text, edit.network)));
            return "";
        }
        if (edit.op == WATCH) {
            return "";
        }
        NetworkState network = network(device);
        if (network == null) {
            return TaskTexts.OFFLINE;
        }
        TaskBoardRef ref = device.settings().board();
        Optional<String> key = TaskBoards.key(network, ref.name(), ref.network());
        if (key.isEmpty()) {
            return TaskBoards.missingKey(ref);
        }
        if (edit.op == ADD || edit.op == SAVE) {
            Optional<String> problem = TaskBoards.problem(server, edit.goal);
            if (problem.isPresent()) {
                return problem.get();
            }
        }
        TaskBoardData data = TaskBoardData.get(server);
        TaskBoardData.Result result = switch (edit.op) {
            case ADD -> data.add(key.get(), ref.name(), edit.text, edit.goal);
            case SAVE -> data.save(key.get(), edit.task, edit.text, edit.goal);
            case REMOVE -> data.remove(key.get(), edit.task);
            default -> TaskBoardData.Result.NO_SUCH_TASK;
        };
        return switch (result) {
            case DONE -> TaskTexts.SAVED;
            case FULL -> TaskTexts.FULL;
            case TOO_MANY_BOARDS -> TaskTexts.TOO_MANY_BOARDS;
            case EMPTY_NAME -> TaskTexts.EMPTY_NAME;
            case NO_SUCH_TASK -> TaskTexts.NO_SUCH_TASK;
        };
    }

    /** The network of the screen {@code device}; null outside one. */
    static @org.jspecify.annotations.Nullable NetworkState network(DisplayDevice device) {
        return device instanceof NetworkDevice networked ? networked.deviceLink().network() : null;
    }

    /** The tasks a board editor would list for {@code device}'s board now; empty without a network or key. */
    static List<Task> tasks(MinecraftServer server, DisplayDevice device) {
        NetworkState network = network(device);
        if (network == null) {
            return List.of();
        }
        TaskBoardRef ref = device.settings().board();
        Optional<String> key = TaskBoards.key(network, ref.name(), ref.network());
        return key.isEmpty() ? List.of() : TaskBoardData.get(server).tasks(key.get());
    }
}
