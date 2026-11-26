package dev.silencebound.maxtech.storage.hub;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.Channels;
import net.minecraft.util.StringRepresentable;

/**
 * The three hub tiers. Their budgets come from the server config ({@code network.channels.hubTier<n>}, 16 / 32 / 64 by
 * default) and are read in a method, since config suppliers are not immutable.
 */
public enum HubTier implements StringRepresentable {
    TIER_1(1), TIER_2(2), TIER_3(3);

    public static final Codec<HubTier> CODEC = StringRepresentable.fromEnum(HubTier::values);

    private final int level;

    HubTier(int level) {
        this.level = level;
    }

    /** 1, 2 or 3. */
    public int level() {
        return level;
    }

    /** Channels the devices behind a hub of this tier share; the default while no server config is loaded. */
    public int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> Channels.hub(level), 8L << level);
    }

    @Override
    public String getSerializedName() {
        return "tier_" + level;
    }
}
