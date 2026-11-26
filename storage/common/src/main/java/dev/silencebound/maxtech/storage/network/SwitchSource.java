package dev.silencebound.maxtech.storage.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which logic graph turned a device off (M7L): the logic controller's position, the graph's index there and its name at
 * the time ({@code ""} for an unnamed graph). Kept on the device's {@link DeviceLink} while it is off and shown in the
 * device screen's status tab and in Jade. A device turned off some other way (an item placed off) has none.
 */
public record SwitchSource(BlockPos controller, int graph, String graphName) {
    /** Longest name kept; the same bound as a graph name. */
    public static final int MAX_NAME = 32;

    public static final Codec<SwitchSource> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(BlockPos.CODEC.fieldOf("controller").forGetter(SwitchSource::controller),
                    Codec.INT.fieldOf("graph").forGetter(SwitchSource::graph),
                    Codec.string(0, MAX_NAME).optionalFieldOf("name", "").forGetter(SwitchSource::graphName))
            .apply(instance, SwitchSource::new));
    public static final StreamCodec<ByteBuf, SwitchSource> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
            SwitchSource::controller, ByteBufCodecs.VAR_INT, SwitchSource::graph, ByteBufCodecs.stringUtf8(MAX_NAME),
            SwitchSource::graphName, SwitchSource::new);

    public SwitchSource {
        controller = controller.immutable();
        if (graphName.length() > MAX_NAME) {
            graphName = graphName.substring(0, MAX_NAME);
        }
    }

    /**
     * "Graph "Intake follows lever" on the Logic Controller at 10 64 -3", or "Graph #2 ..." for an unnamed graph (the
     * controller screen's numbering, from 1).
     */
    public MutableComponent describe() {
        Object x = controller.getX();
        Object y = controller.getY();
        Object z = controller.getZ();
        return graphName.isEmpty()
                ? Component.translatable(NetworkInfo.SWITCHED_OFF_BY_NUMBER_KEY, graph + 1, x, y, z)
                : Component.translatable(NetworkInfo.SWITCHED_OFF_BY_KEY, graphName, x, y, z);
    }
}
