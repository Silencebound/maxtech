package dev.silencebound.maxtech.storage.bulk;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The bulk drive's lang keys and the lines its item, info and screen share. */
public final class BulkTexts {
    private static final String PREFIX = "tooltip." + MaxtechStorage.MOD_ID + ".bulk_drive.";
    public static final String HOLDS = PREFIX + "holds";
    public static final String EMPTY = PREFIX + "empty";
    public static final String PRIORITY = PREFIX + "priority";
    public static final String CAPACITY = PREFIX + "capacity";
    public static final String HELP = PREFIX + "help";
    public static final String NEEDS_RESEARCH = "message." + MaxtechStorage.MOD_ID + ".bulk_drive.needs_research";

    private BulkTexts() {
    }

    /** "Holds 1,234 Cobblestone", or the empty line without contents. */
    public static Component contents(@Nullable BulkContents contents) {
        if (contents == null) {
            return Component.translatable(EMPTY);
        }
        return Component.translatable(HOLDS, InfoFormat.number(contents.count()),
                contents.key().toStack(1).getHoverName());
    }

    /** "Priority: 5". */
    public static Component priority(int priority) {
        return Component.translatable(PRIORITY, Integer.toString(priority));
    }
}
