package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.NetworkHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Collects registrations per mod and replays them in that mod's {@link RegisterPayloadHandlersEvent}, so {@code common}
 * code can register from static initialisers without seeing the event.
 */
final class NeoForgeNetworkHelper implements NetworkHelper {
    // Mods are constructed in parallel, so first registrations for different mods can race.
    private final Map<String, PendingRegistrations> pending = new ConcurrentHashMap<>();

    @Override
    public <T extends CustomPacketPayload> void registerToClient(String modId, CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, PayloadHandler<T> handler) {
        pendingFor(modId).add(registrar -> registrar.playToClient(type, codec,
                (payload, context) -> handler.handle(payload, context.player())));
    }

    @Override
    public <T extends CustomPacketPayload> void registerToServer(String modId, CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, PayloadHandler<T> handler) {
        pendingFor(modId).add(registrar -> registrar.playToServer(type, codec,
                (payload, context) -> handler.handle(payload, context.player())));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }

    @Override
    public void sendToTrackingChunk(ServerLevel level, ChunkPos chunkPos, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunkPos, payload);
    }

    private PendingRegistrations pendingFor(String modId) {
        return pending.computeIfAbsent(modId, id -> new PendingRegistrations(ModContainers.get(id)));
    }

    private static final class PendingRegistrations {
        private final List<Consumer<PayloadRegistrar>> actions = new ArrayList<>();
        private boolean flushed;

        PendingRegistrations(ModContainer container) {
            ModContainers.eventBus(container.getModId()).addListener(RegisterPayloadHandlersEvent.class,
                    event -> flush(event.registrar(container.getModInfo().getVersion().toString())));
        }

        synchronized void add(Consumer<PayloadRegistrar> action) {
            if (flushed) {
                throw new IllegalStateException("Payloads must be registered during mod construction");
            }
            actions.add(action);
        }

        private synchronized void flush(PayloadRegistrar registrar) {
            flushed = true;
            for (Consumer<PayloadRegistrar> action : actions) {
                action.accept(registrar);
            }
            actions.clear();
        }
    }
}
