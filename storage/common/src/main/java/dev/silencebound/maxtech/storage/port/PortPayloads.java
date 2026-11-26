package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * The port screen's packets: a filter slot's keep amount (B09 item 22; a menu button id cannot carry every int) and a
 * filter slot named by a recipe viewer's ghost drag. The server acts only for the sender's open {@link PortMenu} with
 * the payload's container id, and the menu checks that the player may configure the port.
 */
public final class PortPayloads {
    private PortPayloads() {
    }

    /** Client to server: slot {@code slot} of the open port menu keeps {@code keep} in the inventory. */
    public record Keep(int containerId, int slot, int keep) implements CustomPacketPayload {
        public static final Type<Keep> TYPE = new Type<>(MaxtechStorage.id("port_keep"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Keep> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Keep::containerId, ByteBufCodecs.VAR_INT, Keep::slot, ByteBufCodecs.VAR_INT,
                Keep::keep, Keep::new);

        @Override
        public Type<Keep> type() {
            return TYPE;
        }
    }

    /** Client to server: slot {@code slot} of the open port menu names {@code stack} (a ghost, nothing moves). */
    public record Ghost(int containerId, int slot, ItemStack stack) implements CustomPacketPayload {
        public static final Type<Ghost> TYPE = new Type<>(MaxtechStorage.id("port_ghost"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ghost> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ghost::containerId, ByteBufCodecs.VAR_INT, Ghost::slot,
                ItemStack.OPTIONAL_STREAM_CODEC, Ghost::stack, Ghost::new);

        @Override
        public Type<Ghost> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Keep.TYPE, Keep.STREAM_CODEC,
                (payload, player) -> keep(player, payload.containerId(), payload.slot(), payload.keep()));
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Ghost.TYPE, Ghost.STREAM_CODEC,
                (payload, player) -> ghost(player, payload.containerId(), payload.slot(), payload.stack()));
    }

    /**
     * Sets the keep amount as a {@link Keep} does; false, changing nothing, when that is not an open port menu or the
     * player may not configure the port. Server side.
     */
    public static boolean keep(Player player, int containerId, int slot, int keep) {
        AbstractContainerMenu open = player.containerMenu;
        return open.containerId == containerId && open instanceof PortMenu menu && menu.applyKeep(player, slot, keep);
    }

    /** Names a filter slot as a {@link Ghost} does; false when refused, as for {@link #keep}. Server side. */
    public static boolean ghost(Player player, int containerId, int slot, ItemStack stack) {
        AbstractContainerMenu open = player.containerMenu;
        return open.containerId == containerId && open instanceof PortMenu menu && menu.applyGhost(player, slot, stack);
    }
}
