package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** The security screen's one payload: a player name typed into it, to add a rule for that player. */
public final class SecurityPayloads {
    private SecurityPayloads() {
    }

    /** Client to server: add a rule for player {@code name} on the open security screen {@code containerId}. */
    public record AddPlayer(int containerId, String name) implements CustomPacketPayload {
        public static final Type<AddPlayer> TYPE = new Type<>(MaxtechStorage.id("security_add_player"));
        public static final StreamCodec<ByteBuf, AddPlayer> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                AddPlayer::containerId, ByteBufCodecs.stringUtf8(OwnerRecord.MAX_NAME_LENGTH), AddPlayer::name,
                AddPlayer::new);

        @Override
        public Type<AddPlayer> type() {
            return TYPE;
        }
    }

    /** Registers the payload; called once during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, AddPlayer.TYPE, AddPlayer.CODEC,
                (payload, player) -> {
                    // Only the sender's open screen with the payload's container id acts; the menu checks the rest.
                    if (player instanceof ServerPlayer serverPlayer && player.containerMenu instanceof SecurityMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.addPlayer(serverPlayer, payload.name());
                    }
                });
    }
}
