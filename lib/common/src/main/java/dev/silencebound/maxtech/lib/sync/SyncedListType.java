package dev.silencebound.maxtech.lib.sync;

import dev.silencebound.maxtech.lib.platform.Platform;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

/**
 * One kind of synced list, with its own payload type and the codec of its entry definitions. Create each kind once,
 * during mod construction (payloads register then), and keep it in a static field. Storage's terminals make one per key
 * kind; a list of plain item stacks uses {@code ItemStack.STREAM_CODEC} and a describer of its own.
 */
public final class SyncedListType<E> {
    private final CustomPacketPayload.Type<SyncedListUpdate<E>> type;
    private final StreamCodec<RegistryFriendlyByteBuf, SyncedListUpdate<E>> codec;
    private final StreamCodec<? super RegistryFriendlyByteBuf, E> entryCodec;

    private SyncedListType(Identifier id, StreamCodec<? super RegistryFriendlyByteBuf, E> entryCodec) {
        this.type = new CustomPacketPayload.Type<>(id);
        this.codec = SyncedListUpdate.codec(type, entryCodec);
        this.entryCodec = entryCodec;
    }

    /**
     * Makes a list kind and registers its payload under {@code modId}'s channel. {@code entryCodec} writes an entry's
     * definition server to client, so it may be a trusted codec.
     */
    public static <E> SyncedListType<E> register(String modId, Identifier id,
            StreamCodec<? super RegistryFriendlyByteBuf, E> entryCodec) {
        SyncedListType<E> listType = new SyncedListType<>(id, entryCodec);
        Platform.get().network().registerToClient(modId, listType.type, listType.codec, listType::receive);
        return listType;
    }

    /** A list kind without a registered payload, for unit tests of the encoding. */
    static <E> SyncedListType<E> unregistered(Identifier id, StreamCodec<? super RegistryFriendlyByteBuf, E> codec) {
        return new SyncedListType<>(id, codec);
    }

    public CustomPacketPayload.Type<SyncedListUpdate<E>> payloadType() {
        return type;
    }

    public StreamCodec<RegistryFriendlyByteBuf, SyncedListUpdate<E>> codec() {
        return codec;
    }

    /**
     * Measures definitions by encoding them with this kind's entry codec into one reused buffer, for
     * {@link ServerSyncedList}'s byte budget. Each sizer keeps its own buffer; server thread.
     */
    public ServerSyncedList.EntrySizer<E> sizer(RegistryAccess registries) {
        RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        return entry -> {
            scratch.clear();
            entryCodec.encode(scratch, entry);
            return scratch.writerIndex();
        };
    }

    SyncedListUpdate<E> update(int containerId, boolean reset, int[] definitionIds, List<E> definitions, int[] ids,
            long[] counts) {
        return new SyncedListUpdate<>(type, containerId, reset, definitionIds, definitions, ids, counts);
    }

    /** Client: applies {@code update} to the open menu's list if the menu is the one it was sent for. */
    boolean receive(SyncedListUpdate<E> update, Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (!player.level().isClientSide() || menu.containerId != update.containerId()
                || !(menu instanceof SyncedListHolder holder)) {
            return false;
        }
        @Nullable
        ClientSyncedList<E> list = holder.syncedList(this);
        if (list == null) {
            return false;
        }
        list.apply(update);
        return true;
    }
}
