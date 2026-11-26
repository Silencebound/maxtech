package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * The newest logic notices for one screen showing {@link DisplaySourceKind#LOGIC_NOTICES}, server to client: at most
 * {@link DisplayView#NOTICE_LINES}, oldest first. Sent by {@link ScreenWorker} only to the players who can see the
 * screen, when a notice was posted or a player started seeing it.
 */
public record DisplayNoticesUpdate(BlockPos pos, int side,
        List<NetworkNotices.Notice> notices) implements CustomPacketPayload {
    public static final Type<DisplayNoticesUpdate> TYPE = new Type<>(MaxtechStorage.id("display_notices"));
    public static final StreamCodec<ByteBuf, DisplayNoticesUpdate> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DisplayNoticesUpdate::pos,
            ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), DisplayNoticesUpdate::side,
            NetworkNotices.Notice.STREAM_CODEC.apply(ByteBufCodecs.list(DisplayView.NOTICE_LINES)),
            DisplayNoticesUpdate::notices, DisplayNoticesUpdate::new);

    public DisplayNoticesUpdate {
        notices = List.copyOf(notices);
    }

    @Override
    public Type<DisplayNoticesUpdate> type() {
        return TYPE;
    }

    static void handle(DisplayNoticesUpdate update, Player player) {
        DisplayView view = DisplayTargets.view(player.level(), update.pos, update.side);
        if (view != null) {
            view.notices(update.notices);
        }
    }
}
