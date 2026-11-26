package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: a logic graph's notice for the team toast channel (M7L area E). Sent once per notice to each online
 * player of the controller owner's team; the client shows a toast unless the player switched logic toasts off in the
 * client config.
 *
 * @param text
 *            the notify node's text, the player's own words, at most {@link #MAX_LENGTH} characters
 */
public record LogicNoticePayload(String text) implements CustomPacketPayload {
    /** Longest text sent; notify texts are node settings and shorter. */
    public static final int MAX_LENGTH = 256;
    public static final Type<LogicNoticePayload> TYPE = new Type<>(MaxtechStorage.id("logic_notice"));
    public static final StreamCodec<ByteBuf, LogicNoticePayload> STREAM_CODEC = StreamCodec
            .composite(ByteBufCodecs.stringUtf8(MAX_LENGTH), LogicNoticePayload::text, LogicNoticePayload::new);

    public LogicNoticePayload {
        text = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }

    @Override
    public Type<LogicNoticePayload> type() {
        return TYPE;
    }
}
