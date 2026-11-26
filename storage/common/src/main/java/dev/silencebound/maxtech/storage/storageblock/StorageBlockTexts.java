package dev.silencebound.maxtech.storage.storageblock;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The storage block's lang keys and the lines its item, info and screen share. */
public final class StorageBlockTexts {
    private static final String PREFIX = "tooltip." + MaxtechStorage.MOD_ID + ".storage_block.";
    public static final String BYTES = PREFIX + "bytes";
    public static final String KINDS = PREFIX + "kinds";
    public static final String KEPT = PREFIX + "kept";
    public static final String PRIORITY = PREFIX + "priority";
    public static final String HOLDS = PREFIX + "holds";
    public static final String INACTIVE = PREFIX + "inactive";

    private StorageBlockTexts() {
    }

    /** "1,024 / 4,096 bytes used"; {@code total} 0 shows as "?". */
    public static MutableComponent bytes(long used, long total) {
        return Component.translatable(BYTES, InfoFormat.number(used), total > 0 ? InfoFormat.number(total) : "?");
    }

    /** "12 kinds". */
    public static MutableComponent kinds(int kinds) {
        return Component.translatable(KINDS, InfoFormat.number(kinds));
    }

    /** "Priority: 5". */
    public static MutableComponent priority(int priority) {
        return Component.translatable(PRIORITY, Integer.toString(priority));
    }
}
