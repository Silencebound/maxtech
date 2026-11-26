package dev.silencebound.maxtech.storage.priority;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * The priority page's packet. A vanilla menu button id cannot carry every int, so the page sends the priority itself.
 * The server acts only for the sender's open {@link PriorityMenu} with the payload's container id, and the menu checks
 * that the player may configure the block.
 */
public final class PriorityPayloads {
    private PriorityPayloads() {
    }

    /** Client to server: set the priority of the open menu's block to {@code priority}. */
    public record Set(int containerId, int priority) implements CustomPacketPayload {
        public static final Type<Set> TYPE = new Type<>(MaxtechStorage.id("priority_set"));
        public static final StreamCodec<ByteBuf, Set> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Set::containerId, ByteBufCodecs.INT, Set::priority, Set::new);

        @Override
        public Type<Set> type() {
            return TYPE;
        }
    }

    /** Registers the payload; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Set.TYPE, Set.STREAM_CODEC,
                PriorityPayloads::set);
    }

    /**
     * Sets the priority of {@code player}'s open menu {@code containerId} as a {@link Set} does; false, changing
     * nothing, when that is not an open priority menu or the player may not configure its block. Server side.
     */
    public static boolean apply(Player player, int containerId, int priority) {
        AbstractContainerMenu open = player.containerMenu;
        return open.containerId == containerId && open instanceof PriorityMenu menu
                && menu.applyPriority(player, priority);
    }

    private static void set(Set payload, Player player) {
        apply(player, payload.containerId(), payload.priority());
    }
}
