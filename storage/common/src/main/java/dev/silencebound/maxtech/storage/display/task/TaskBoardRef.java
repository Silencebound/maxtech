package dev.silencebound.maxtech.storage.display.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which task board a screen shows (M7C item 15): the board's name and whether it belongs to the network (its
 * controller) or to the team of the network's owner. Kept in the screen's settings whatever its source. Immutable.
 */
public record TaskBoardRef(String name, boolean network) {
    public static final TaskBoardRef DEFAULT = new TaskBoardRef("", false);
    public static final Codec<TaskBoardRef> CODEC = RecordCodecBuilder.create(i -> i
            .group(Codec.STRING.optionalFieldOf("name", "").forGetter(TaskBoardRef::name),
                    Codec.BOOL.optionalFieldOf("network", false).forGetter(TaskBoardRef::network))
            .apply(i, TaskBoardRef::new));
    public static final StreamCodec<ByteBuf, TaskBoardRef> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(TaskBoardData.MAX_BOARD_NAME * 4), TaskBoardRef::name, ByteBufCodecs.BOOL,
            TaskBoardRef::network, TaskBoardRef::new);

    public TaskBoardRef {
        name = TaskBoardData.cleanName(name);
    }
}
