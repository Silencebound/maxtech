package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * A sensor's values (server to client), sent to a player whose sensor screen is open when it opens and after every
 * read, at most once per tick.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 * @param loaded
 *            whether the target's chunk is loaded
 * @param values
 *            the values of the last read, at most {@link SensorWorker#MAX_VALUES}
 */
public record SensorView(int containerId, boolean loaded, List<SensorValue> values) implements CustomPacketPayload {
    public static final Type<SensorView> TYPE = new Type<>(MaxtechStorage.id("sensor_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SensorView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SensorView::containerId, ByteBufCodecs.BOOL, SensorView::loaded,
            SensorValue.STREAM_CODEC.apply(ByteBufCodecs.list(SensorWorker.MAX_VALUES)), SensorView::values,
            SensorView::new);

    @Override
    public Type<SensorView> type() {
        return TYPE;
    }

    /** Client side: hands the values to the player's open sensor menu with that container id. */
    static void handle(SensorView view, Player player) {
        if (player.containerMenu instanceof SensorMenu menu && menu.containerId == view.containerId()) {
            menu.setView(view.loaded(), view.values());
        }
    }
}
