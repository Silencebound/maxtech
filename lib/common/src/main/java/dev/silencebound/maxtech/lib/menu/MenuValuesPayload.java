package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.MaxtechLib;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import io.netty.buffer.Unpooled;

/**
 * The values of a {@link ToolkitMenu} that changed since the last payload, for the open menu {@code containerId}.
 * {@code body} is {@link MenuValueTable#encode}'s output (changed mask, then the values); only the menu can read it,
 * since the layout comes from its declaration. Registered by Lib's NeoForge entry point.
 */
public final class MenuValuesPayload implements CustomPacketPayload {
    /** Bound on one body; a tank's fluid components are the only part that can grow. */
    public static final int MAX_BODY = 1 << 20;
    public static final Type<MenuValuesPayload> TYPE = new Type<>(MaxtechLib.id("menu_values"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuValuesPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MenuValuesPayload::containerId, ByteBufCodecs.byteArray(MAX_BODY),
            MenuValuesPayload::body, MenuValuesPayload::new);

    private final int containerId;
    private final byte[] body;

    /** {@code body} is kept, not copied. */
    public MenuValuesPayload(int containerId, byte[] body) {
        this.containerId = containerId;
        this.body = body;
    }

    public int containerId() {
        return containerId;
    }

    /** The encoded values; not copied, do not change. */
    public byte[] body() {
        return body;
    }

    @Override
    public Type<MenuValuesPayload> type() {
        return TYPE;
    }

    /** Client handler (main thread): applies to the player's open menu, or drops the payload. */
    public static void receive(MenuValuesPayload payload, Player player) {
        if (player.level().isClientSide()) {
            apply(payload, player.containerMenu, player.level().registryAccess());
        }
    }

    /**
     * Reads {@code payload} into {@code menu} if it is the toolkit menu the payload was sent for; a payload for another
     * container (one that closed, or one the client has not built yet) is dropped. True if applied.
     */
    public static boolean apply(MenuValuesPayload payload, AbstractContainerMenu menu, RegistryAccess registries) {
        if (menu.containerId != payload.containerId() || !(menu instanceof ToolkitMenu<?> toolkit)) {
            return false;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.body()), registries);
        toolkit.table().apply(buf);
        return true;
    }
}
