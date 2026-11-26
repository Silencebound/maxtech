package dev.silencebound.maxtech.storage.terminal.client;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.sync.SyncedListIndex;
import dev.silencebound.maxtech.storage.terminal.StackKeyDescriber;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The terminal screen's describer: names, mods and tags from {@link StackKeyDescriber}, plus tooltip text for {@code $}
 * searches. An item's tooltip is what the player sees on hover ({@link Screen#getTooltipFromItem}, so other mods'
 * tooltip lines count); a fluid's is its name. Tooltips are built only when a {@code $} search needs them and are kept
 * per key across terminal openings, until a resource or language reload ({@link #clear}, called by the loader's reload
 * listener), a switch of advanced tooltips, or more than {@link #MAX_CACHED} keys. Client thread.
 */
public final class TerminalTooltips implements SyncedListIndex.EntryDescriber<StackKey> {
    public static final TerminalTooltips INSTANCE = new TerminalTooltips();
    /** Keys whose tooltips are kept at most; the whole cache is dropped beyond that, which bounds its memory. */
    static final int MAX_CACHED = 16_384;
    private static final TooltipCache<StackKey> CACHE = new TooltipCache<>(MAX_CACHED);

    private TerminalTooltips() {
    }

    /** Drops every cached tooltip; the next {@code $} search builds them again. */
    public static void clear() {
        CACHE.clear();
    }

    /** Raised by every {@link #clear}, so an open screen knows to drop the tooltips its index holds. */
    public static int generation() {
        return CACHE.generation();
    }

    @Override
    public String name(StackKey entry) {
        return StackKeyDescriber.INSTANCE.name(entry);
    }

    @Override
    public String modId(StackKey entry) {
        return StackKeyDescriber.INSTANCE.modId(entry);
    }

    @Override
    public List<String> tags(StackKey entry) {
        return StackKeyDescriber.INSTANCE.tags(entry);
    }

    @Override
    public boolean tooltipCached(StackKey entry) {
        return CACHE.cached(entry, advanced());
    }

    @Override
    public String tooltip(StackKey entry) {
        return CACHE.get(entry, advanced(), TerminalTooltips::build, TerminalTooltips::fallback);
    }

    private static String fallback(StackKey entry) {
        return StackKeyDescriber.INSTANCE.name(entry);
    }

    /** Advanced tooltips (F3+H) add lines, so the cache drops what was built with the other setting. */
    private static boolean advanced() {
        return Minecraft.getInstance().options.advancedItemTooltips;
    }

    /** The item's tooltip lines, or null without a player (then the name stands in and nothing is kept). */
    private static @Nullable String build(StackKey entry) {
        Minecraft minecraft = Minecraft.getInstance();
        return switch (entry) {
            case ItemKey item -> {
                if (minecraft.player == null) {
                    yield null;
                }
                StringBuilder text = new StringBuilder();
                for (Component line : Screen.getTooltipFromItem(minecraft, item.toStack(1))) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(line.getString());
                }
                yield text.toString();
            }
            case FluidKey fluid -> StackKeyDescriber.INSTANCE.name(fluid);
        };
    }
}
