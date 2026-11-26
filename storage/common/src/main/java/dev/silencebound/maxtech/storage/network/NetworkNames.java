package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The names of storage networks on the server (M7L2 row 13), saved with the overworld
 * ({@code data/maxtech_storage/storage_network_names.dat}). A network's name lives on its controller core and is unique
 * on the server, compared without case; this registry remembers which core holds each name, loaded or not, so a rule on
 * another network can tell "not loaded" from "no such network", and the logic pickers can list networks. A core claims
 * its name when it loads (a new core gets "Network n"; a name another core holds gives the newcomer a fresh one), keeps
 * it when its chunk unloads, and releases it when it is broken. Listeners hear every change (a core loaded, unloaded or
 * moved to another network graph, a rename, new security rules) so logic controllers find the networks they use again;
 * they are kept weakly. Server thread only.
 */
public final class NetworkNames extends SavedData {
    /**
     * Where a name lives: the core's dimension and block position, and its owner when it has one (for listing networks
     * that are not loaded).
     */
    public record Entry(String name, ResourceKey<Level> dimension, long pos, Optional<UUID> owner) {
        static final Codec<Entry> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(Codec.string(1, NetworkRef.MAX_NAME).fieldOf("name").forGetter(Entry::name),
                                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension")
                                        .forGetter(Entry::dimension),
                                Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Entry::owner))
                        .apply(instance, Entry::new));

        /** Whether this entry is the core at {@code pos} in {@code dimension}. */
        public boolean at(ResourceKey<Level> dimension, long pos) {
            return this.pos == pos && this.dimension.equals(dimension);
        }
    }

    /** What a rename did. */
    public enum Renamed {
        DONE, SAME, EMPTY, TAKEN
    }

    private static final Codec<NetworkNames> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Entry.CODEC.listOf().fieldOf("names").forGetter(NetworkNames::list),
                    Codec.INT.optionalFieldOf("next", 1).forGetter(names -> names.next))
            .apply(instance, NetworkNames::new));
    private static final SavedDataType<NetworkNames> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("storage_network_names"), NetworkNames::new, CODEC);
    /** Logic controllers' watchers, weakly: a removed controller drops out with its worker. */
    private static final Set<Runnable> LISTENERS = Collections.newSetFromMap(new WeakHashMap<>());

    private final Object2ObjectLinkedOpenHashMap<String, Entry> byKey = new Object2ObjectLinkedOpenHashMap<>();
    /** The number the next automatic name tries first. */
    private int next = 1;

    NetworkNames() {
    }

    private NetworkNames(List<Entry> entries, int next) {
        for (Entry entry : entries) {
            byKey.putIfAbsent(NetworkRef.key(entry.name()), entry);
        }
        this.next = Math.max(1, next);
    }

    private List<Entry> list() {
        return new ArrayList<>(byKey.values());
    }

    /** The server's registry. */
    public static NetworkNames get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * {@code raw} as a network name: control characters, the section sign and {@code @} removed, trimmed, and cut to
     * {@link NetworkRef#MAX_NAME} characters without splitting a surrogate pair.
     */
    public static String clean(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), NetworkRef.MAX_NAME + 8));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) && c != '§' && c != NetworkRef.MARK) {
                out.append(c);
            }
        }
        String text = out.toString().strip();
        int max = NetworkRef.MAX_NAME;
        if (text.length() > max) {
            int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
            text = text.substring(0, end).strip();
        }
        return text;
    }

    /** The entry for the network named {@code name} (any case); empty when no core has it. */
    public Optional<Entry> find(String name) {
        return Optional.ofNullable(byKey.get(NetworkRef.key(name)));
    }

    /** Every named network, in the order the names were first given. */
    public Collection<Entry> entries() {
        return Collections.unmodifiableCollection(byKey.values());
    }

    /**
     * The name the core at {@code pos} in {@code dimension} goes by after it loads or is placed: {@code wanted} when no
     * other core holds it, else the name the core already holds here, else a fresh "Network n". A core holds one name:
     * any other entry at its position goes (a core placed from a named item first loads with a fresh name, then takes
     * the item's). Records the owner.
     */
    public String claim(String wanted, ResourceKey<Level> dimension, long pos, @Nullable UUID owner) {
        String name = clean(wanted);
        Entry held = name.isEmpty() ? null : byKey.get(NetworkRef.key(name));
        if (held != null && !held.at(dimension, pos)) {
            name = "";
        }
        if (name.isEmpty()) {
            Entry own = at(dimension, pos);
            name = own != null ? own.name() : fresh();
        }
        String key = NetworkRef.key(name);
        if (byKey.values().removeIf(entry -> entry.at(dimension, pos) && !NetworkRef.key(entry.name()).equals(key))) {
            setDirty();
        }
        put(new Entry(name, dimension, pos, Optional.ofNullable(owner)));
        return name;
    }

    /** The entry of the core at {@code pos} in {@code dimension}, or null. */
    private @Nullable Entry at(ResourceKey<Level> dimension, long pos) {
        for (Entry entry : byKey.values()) {
            if (entry.at(dimension, pos)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Renames the core at {@code pos} in {@code dimension} from {@code old} to {@code wanted} (cleaned): refused when
     * it is empty after cleaning or another core holds it.
     */
    public Renamed rename(String old, String wanted, ResourceKey<Level> dimension, long pos, @Nullable UUID owner) {
        String name = clean(wanted);
        if (name.isEmpty()) {
            return Renamed.EMPTY;
        }
        if (name.equals(old)) {
            return Renamed.SAME;
        }
        Entry held = byKey.get(NetworkRef.key(name));
        if (held != null && !held.at(dimension, pos)) {
            return Renamed.TAKEN;
        }
        release(old, dimension, pos);
        put(new Entry(name, dimension, pos, Optional.ofNullable(owner)));
        return Renamed.DONE;
    }

    /** Forgets {@code name} when the core at {@code pos} in {@code dimension} holds it: the core was broken. */
    public void release(String name, ResourceKey<Level> dimension, long pos) {
        String key = NetworkRef.key(name);
        Entry held = byKey.get(key);
        if (held != null && held.at(dimension, pos)) {
            byKey.remove(key);
            setDirty();
        }
    }

    private void put(Entry entry) {
        Entry old = byKey.put(NetworkRef.key(entry.name()), entry);
        if (!entry.equals(old)) {
            setDirty();
        }
    }

    private String fresh() {
        while (true) {
            String name = "Network " + next++;
            setDirty();
            if (!byKey.containsKey(NetworkRef.key(name))) {
                return name;
            }
        }
    }

    // ---- listeners

    /** Runs {@code watcher} on every change of a named network; it must only mark work and wake a task. */
    public static void listen(Runnable watcher) {
        LISTENERS.add(watcher);
    }

    /** Stops running {@code watcher}. */
    public static void unlisten(Runnable watcher) {
        LISTENERS.remove(watcher);
    }

    /**
     * A named network changed: its core loaded, unloaded, was broken or renamed, joined another network graph, or its
     * security rules changed. Tells every listener; allocates one array while some listen.
     */
    public static void changed() {
        if (LISTENERS.isEmpty()) {
            return;
        }
        for (Runnable watcher : LISTENERS.toArray(new Runnable[0])) {
            watcher.run();
        }
    }
}
