package dev.silencebound.maxtech.storage.smartlabel;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * What one smart label shows, server to client: a few bytes, sent only to the players who can see the label, only when
 * something changed and at most every {@code display.updateTicks} ticks ({@link LabelWorker}).
 *
 * @param status
 *            {@link #SHOWN} or {@link #NOTHING}
 * @param detail
 *            for {@link LabelMode#ITEM} where the count comes from ({@link #FROM_NETWORK}, {@link #FROM_BLOCK}); for
 *            {@link LabelMode#FILL} what fills ({@link #FILL_ITEMS} and the rest); for {@link LabelMode#MACHINE} the
 *            state ({@link #IDLE}, {@link #WORKING}, {@link #STUCK})
 * @param value
 *            the count or amount; for a stuck machine the reason ({@link SmartLabelContent#reasonId})
 * @param max
 *            the capacity for {@link LabelMode#FILL} ({@code MachineReader.FILL_SCALE} for items, whose value is their
 *            per-slot fill); 0 otherwise
 */
public record LabelUpdate(BlockPos pos, int status, int detail, long value, long max) implements CustomPacketPayload {
    /** Nothing received yet (client only). */
    public static final int NO_DATA = -1;
    /**
     * Nothing to show: no item picked, nothing readable (or a land claim keeps the owner from the block), or no machine
     * state.
     */
    public static final int NOTHING = 0;
    public static final int SHOWN = 1;

    public static final int FROM_NETWORK = 0;
    public static final int FROM_BLOCK = 1;

    public static final int FILL_ITEMS = 0;
    public static final int FILL_FLUIDS = 1;
    public static final int FILL_ENERGY = 2;
    /** The block has no handlers; the label shows its network's storage bytes. */
    public static final int FILL_NETWORK = 3;

    public static final int IDLE = 0;
    public static final int WORKING = 1;
    public static final int STUCK = 2;

    public static final Type<LabelUpdate> TYPE = new Type<>(MaxtechStorage.id("smart_label_update"));
    public static final StreamCodec<ByteBuf, LabelUpdate> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
            LabelUpdate::pos, ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), LabelUpdate::status,
            ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), LabelUpdate::detail, ByteBufCodecs.VAR_LONG,
            LabelUpdate::value, ByteBufCodecs.VAR_LONG, LabelUpdate::max, LabelUpdate::new);

    @Override
    public Type<LabelUpdate> type() {
        return TYPE;
    }

    /** Client: hands the values to the label at the position, if the client has it. */
    static void handle(LabelUpdate update, Player player) {
        if (player.level().isLoaded(update.pos)
                && player.level().getBlockEntity(update.pos) instanceof SmartLabelBlockEntity label) {
            label.view().update(update.status, update.detail, update.value, update.max);
        }
    }
}
