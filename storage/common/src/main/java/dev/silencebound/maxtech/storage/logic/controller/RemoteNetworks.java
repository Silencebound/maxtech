package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.sensor.SensorWorker;
import dev.silencebound.maxtech.storage.world.WorldActors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Finding other networks for logic (M7L2 row 13): a named network's controller only while its chunk is loaded (logic
 * never loads a chunk), and the networks a rule's owner may name, for the editor's and the card's label pickers. Server
 * thread.
 */
public final class RemoteNetworks {
    /** Most other networks a picker lists. */
    public static final int MAX_LISTED = 16;
    private static final SensorWorker.LoadedCheck CHUNK_LOADED = (level, pos) -> level.getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
    private static SensorWorker.LoadedCheck loadedCheck = CHUNK_LOADED;

    private RemoteNetworks() {
    }

    /** Replaces the loaded check for named networks' controllers (GameTests only); null restores the real one. */
    public static void setLoadedCheck(SensorWorker.@Nullable LoadedCheck check) {
        loadedCheck = check == null ? CHUNK_LOADED : check;
    }

    /** The network of the named controller at {@code entry} while its chunk is loaded, online or not; else null. */
    static @Nullable NetworkState loaded(MinecraftServer server, NetworkNames.Entry entry) {
        ServerLevel level = server.getLevel(entry.dimension());
        BlockPos pos = BlockPos.of(entry.pos());
        if (level == null || !loadedCheck.isLoaded(level, pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof ControllerBlockEntity core && !core.isRemoved()
                ? core.network()
                : null;
    }

    /** Whether {@code owner} has the logic access level on {@code network}, through the owner's stand-in player. */
    static boolean mayUse(ServerLevel level, OwnerRecord owner, NetworkState network) {
        if (!owner.isOwned()) {
            return false;
        }
        ServerPlayer actor = WorldActors.get().player(level, owner);
        return network.accessLevel(actor).atLeast(AccessLevel.LOGIC);
    }

    /**
     * The names of the other networks a rule owned by {@code owner} may name, sorted, at most {@link #MAX_LISTED}:
     * loaded online ones where the owner has the logic access level, and ones not loaded now that the owner owns (their
     * security rules can't be read while unloaded). {@code own} is left out. Allocates; for screens.
     */
    public static List<String> reachable(ServerLevel level, OwnerRecord owner, @Nullable NetworkState own) {
        List<String> out = new ArrayList<>();
        if (!owner.isOwned()) {
            return out;
        }
        MinecraftServer server = level.getServer();
        for (NetworkNames.Entry entry : NetworkNames.get(server).entries()) {
            NetworkState network = loaded(server, entry);
            if (network != null && network == own) {
                continue;
            }
            boolean usable = (network != null && network.status() == StorageNetwork.Status.ONLINE)
                    ? mayUse(level, owner, network)
                    : entry.owner().filter(owner.owner()::equals).isPresent();
            if (usable) {
                out.add(entry.name());
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.size() > MAX_LISTED ? new ArrayList<>(out.subList(0, MAX_LISTED)) : out;
    }

    /** Whether {@code names} holds the network {@code name} names, in any case. */
    static boolean holds(List<String> names, String name) {
        String key = NetworkRef.key(name);
        for (String held : names) {
            if (NetworkRef.key(held).equals(key)) {
                return true;
            }
        }
        return false;
    }
}
