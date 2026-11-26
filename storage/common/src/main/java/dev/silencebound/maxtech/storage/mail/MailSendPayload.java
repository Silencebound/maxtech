package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Send pressed in a mail port's screen (client to server): the outbox goes to the player called {@code recipient}. The
 * server acts only for the sender's open mail port menu with that container id, at most once per tick, and refuses
 * names longer than {@link MailMenu#MAX_NAME}.
 *
 * @param containerId
 *            the menu it is for
 * @param recipient
 *            the name typed, at most {@link MailMenu#MAX_NAME} characters (the codec refuses longer)
 */
public record MailSendPayload(int containerId, String recipient) implements CustomPacketPayload {
    public static final Type<MailSendPayload> TYPE = new Type<>(MaxtechStorage.id("mail_send"));
    public static final StreamCodec<FriendlyByteBuf, MailSendPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MailSendPayload::containerId, ByteBufCodecs.stringUtf8(MailMenu.MAX_NAME),
            MailSendPayload::recipient, MailSendPayload::new);

    @Override
    public Type<MailSendPayload> type() {
        return TYPE;
    }

    /** Server side: sends for {@code player}'s matching open menu. */
    static void handle(MailSendPayload payload, Player player) {
        if (player instanceof ServerPlayer serverPlayer && player.containerMenu instanceof MailMenu menu
                && menu.containerId == payload.containerId()) {
            menu.send(serverPlayer, payload.recipient().trim());
        }
    }
}
