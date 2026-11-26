package dev.silencebound.maxtech.storage.controller.alarm;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The controller screen's Alarms tab content (server to client, M7C item 16): the network's alarms in the order the
 * screen shows them, sent to the one player whose controller menu is open when it opens and then whenever they changed.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 */
public record AlarmTabPayload(int containerId, List<Alarm> alarms) implements CustomPacketPayload {
    public static final Type<AlarmTabPayload> TYPE = new Type<>(MaxtechStorage.id("alarm_tab"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlarmTabPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AlarmTabPayload::containerId,
            Alarm.STREAM_CODEC.apply(ByteBufCodecs.list(AlarmCenter.MAX_ALARMS)), AlarmTabPayload::alarms,
            AlarmTabPayload::new);

    public AlarmTabPayload {
        alarms = List.copyOf(alarms);
    }

    @Override
    public Type<AlarmTabPayload> type() {
        return TYPE;
    }
}
