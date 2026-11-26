package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * A list screen's rows ({@link ScreenRows}), server to client: sent by {@link ScreenWorker} only to the players who can
 * see the screen, when the rows changed or a player started seeing it, and at most every {@code display.updateTicks}.
 */
public record DisplayRowsUpdate(BlockPos pos, int side, ScreenRows rows) implements CustomPacketPayload {
    public static final Type<DisplayRowsUpdate> TYPE = new Type<>(MaxtechStorage.id("display_rows"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DisplayRowsUpdate> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DisplayRowsUpdate::pos, ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue),
            DisplayRowsUpdate::side, ScreenRows.STREAM_CODEC, DisplayRowsUpdate::rows, DisplayRowsUpdate::new);

    @Override
    public Type<DisplayRowsUpdate> type() {
        return TYPE;
    }

    static void handle(DisplayRowsUpdate update, Player player) {
        DisplayView view = DisplayTargets.view(player.level(), update.pos, update.side);
        if (view != null) {
            view.rows(update.rows);
        }
    }
}
