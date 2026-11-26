package dev.silencebound.maxtech.storage.display.task;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayDevice;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Server to client: the board a screen's task editor shows, with the answer to the last edit (M7C item 15). Sent only
 * to the editing player, after each of their edits; the client keeps it in its open display menu.
 *
 * @param message
 *            a lang key saying what became of the edit, empty for nothing to say
 */
public record TaskBoardSync(int containerId, TaskBoardRef board, List<Task> tasks,
        String message) implements CustomPacketPayload {
    public static final Type<TaskBoardSync> TYPE = new Type<>(MaxtechStorage.id("task_board"));
    public static final StreamCodec<ByteBuf, TaskBoardSync> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
            TaskBoardSync::containerId, TaskBoardRef.STREAM_CODEC, TaskBoardSync::board,
            Task.STREAM_CODEC.apply(ByteBufCodecs.list(TaskBoardData.MAX_TASKS)), TaskBoardSync::tasks,
            ByteBufCodecs.stringUtf8(128), TaskBoardSync::message, TaskBoardSync::new);

    public TaskBoardSync {
        tasks = List.copyOf(tasks);
    }

    @Override
    public Type<TaskBoardSync> type() {
        return TYPE;
    }

    /** Sends {@code device}'s board to {@code player}, whose display menu {@code containerId} is open. */
    static void send(ServerPlayer player, int containerId, DisplayDevice device, String message) {
        TaskBoardSync sync = new TaskBoardSync(containerId, device.settings().board(),
                TaskEdit.tasks(player.level().getServer(), device), message);
        last = sync;
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, sync);
        }
    }

    /** The last answer sent; for tests (GameTest players have no connection). */
    private static @org.jspecify.annotations.Nullable TaskBoardSync last;

    public static @org.jspecify.annotations.Nullable TaskBoardSync last() {
        return last;
    }

    /** Client: hands the board to the open display menu it is for. */
    static void handle(TaskBoardSync sync, Player player) {
        if (player.containerMenu instanceof DisplayMenu menu && menu.containerId == sync.containerId) {
            menu.acceptTasks(sync);
        }
    }

    /** Registers this payload and {@link TaskEdit}; during mod construction. */
    public static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, TYPE, STREAM_CODEC, TaskBoardSync::handle);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, TaskEdit.TYPE, TaskEdit.STREAM_CODEC,
                TaskEdit::handle);
        Platform.get().lifecycle().onServerStopped(server -> {
            TaskEdit.clear();
            last = null;
        });
    }
}
