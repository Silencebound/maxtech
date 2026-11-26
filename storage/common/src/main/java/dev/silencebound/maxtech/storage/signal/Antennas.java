package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The loaded antennas of each level, by position, and the antenna numbers from config. Antennas register when they load
 * and leave when they are removed or unload; the wireless terminal's coverage and the controller's fill through an
 * antenna mount read them. Nothing here ticks. Server thread only.
 */
public final class Antennas {
    /** No position: an antenna without a partner, or a mount without an antenna. */
    public static final long NONE = Long.MIN_VALUE;
    private static final Map<Level, Long2ObjectOpenHashMap<AntennaBlockEntity>> LOADED = new WeakHashMap<>();
    private static final Long2ObjectOpenHashMap<AntennaBlockEntity> EMPTY = new Long2ObjectOpenHashMap<>();

    private Antennas() {
    }

    static void add(Level level, AntennaBlockEntity antenna) {
        LOADED.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>()).put(antenna.getBlockPos().asLong(), antenna);
    }

    @SuppressWarnings("ReferenceEquality") // the entry is removed only for this very block entity
    static void remove(Level level, AntennaBlockEntity antenna) {
        Long2ObjectOpenHashMap<AntennaBlockEntity> map = LOADED.get(level);
        if (map != null && map.get(antenna.getBlockPos().asLong()) == antenna) {
            map.remove(antenna.getBlockPos().asLong());
        }
    }

    /** The loaded antennas of {@code level} by position; read-only. */
    public static Long2ObjectMap<AntennaBlockEntity> loaded(Level level) {
        Long2ObjectOpenHashMap<AntennaBlockEntity> map = LOADED.get(level);
        return map == null ? EMPTY : map;
    }

    /** The loaded antenna at {@code pos}, or null. */
    public static @Nullable AntennaBlockEntity at(Level level, long pos) {
        return loaded(level).get(pos);
    }

    /**
     * The position of the loaded antenna paired with the antenna mount at {@code mountPos} and within its range, or
     * {@link #NONE}. Walks the level's loaded antennas; asked only by the controller's fill.
     */
    public static long antennaOf(Level level, long mountPos) {
        for (AntennaBlockEntity antenna : loaded(level).values()) {
            if (antenna.partner() == mountPos && antenna.partnerIsMount() && !antenna.isRemoved()
                    && antenna.reaches(mountPos, antenna.tier())) {
                return antenna.getBlockPos().asLong();
            }
        }
        return NONE;
    }

    /**
     * The range of a link between antennas of tiers {@code a} and {@code b}; {@link SignalMath#UNLIMITED} for tier 3.
     */
    public static int range(int a, int b) {
        return SignalMath.linkRange(a, b, StorageServerConfig.ANTENNA_RANGE_TIER_1.getAsInt(),
                StorageServerConfig.ANTENNA_RANGE_TIER_2.getAsInt());
    }

    /** Channels of a link between antennas of tiers {@code a} and {@code b}. */
    public static long width(int a, int b) {
        return SignalMath.linkWidth(a, b, StorageServerConfig.ANTENNA_CHANNELS_TIER_1.getAsInt(),
                StorageServerConfig.ANTENNA_CHANNELS_TIER_2.getAsInt(),
                StorageServerConfig.ANTENNA_CHANNELS_TIER_3.getAsInt());
    }

    /** Whether {@code a} and {@code b} are within {@code range} of each other. */
    static boolean inRange(long a, long b, int range) {
        return SignalMath.inRange(BlockPos.of(a).distSqr(BlockPos.of(b)), range);
    }
}
