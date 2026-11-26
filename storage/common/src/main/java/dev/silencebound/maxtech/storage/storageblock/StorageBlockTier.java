package dev.silencebound.maxtech.storage.storageblock;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import java.util.Locale;
import java.util.function.LongSupplier;
import net.minecraft.util.StringRepresentable;

/**
 * A storage block's tier (B09 item 27): I, II and III, built from four memory modules I, III and IV in a small, medium
 * and large cell casing, so by default each holds what its four modules would ({@code cells.storageBlockBytes}).
 */
public enum StorageBlockTier implements StringRepresentable {
    ONE(1), TWO(2), THREE(3);

    public static final Codec<StorageBlockTier> CODEC = StringRepresentable.fromEnum(StorageBlockTier::values);

    private final int level;

    StorageBlockTier(int level) {
        this.level = level;
    }

    /** 1 to 3. */
    public int level() {
        return level;
    }

    /** The capacity in bytes from the server config; any thread once the config is loaded. */
    public long bytes() {
        LongSupplier bytes = switch (this) {
            case ONE -> StorageServerConfig.STORAGE_BLOCK_1_BYTES;
            case TWO -> StorageServerConfig.STORAGE_BLOCK_2_BYTES;
            case THREE -> StorageServerConfig.STORAGE_BLOCK_3_BYTES;
        };
        return bytes.getAsLong();
    }

    @Override
    public String getSerializedName() {
        return "tier_" + name().toLowerCase(Locale.ROOT);
    }
}
