package dev.silencebound.maxtech.storage.controller.recovery;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The controller screen's Recovery tab content (server to client, M7C item 20): the bin's rows newest first, the keep
 * time they expire after and whether the viewer may recover. Sent to the one player whose controller menu is open, when
 * it opens and whenever the bin changed.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 * @param keepTicks
 *            {@code recovery.keepTicks} on the server; a row expires at its {@code created} plus this
 */
public record RecoveryTabPayload(int containerId, List<RecoveryBin.Row> rows, int keepTicks,
        boolean mayRecover) implements CustomPacketPayload {
    /** Rows sent at most: the config's largest cap. */
    public static final int MAX_ROWS = 256;
    public static final Type<RecoveryTabPayload> TYPE = new Type<>(MaxtechStorage.id("recovery_tab"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecoveryTabPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RecoveryTabPayload::containerId,
            RecoveryBin.Row.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ROWS)), RecoveryTabPayload::rows,
            ByteBufCodecs.VAR_INT, RecoveryTabPayload::keepTicks, ByteBufCodecs.BOOL, RecoveryTabPayload::mayRecover,
            RecoveryTabPayload::new);

    public RecoveryTabPayload {
        rows = List.copyOf(rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows);
    }

    @Override
    public Type<RecoveryTabPayload> type() {
        return TYPE;
    }
}
