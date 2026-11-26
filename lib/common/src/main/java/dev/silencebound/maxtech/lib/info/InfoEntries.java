package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A {@link BlockInfoSink} that keeps every entry as an {@link InfoEntry}, statuses, bars, pips, stacks and icons
 * included, for overlays that draw them and for tests that count them. Not thread-safe; one per query.
 */
public final class InfoEntries implements BlockInfoSink {
    private final List<InfoEntry> entries = new ArrayList<>();

    @Override
    public void line(Component line) {
        entries.add(InfoEntry.text(line));
    }

    @Override
    public void status(Tone tone, Component text) {
        entries.add(InfoEntry.status(tone, text, Optional.empty()));
    }

    @Override
    public void status(Tone tone, Component text, Identifier sprite) {
        entries.add(InfoEntry.status(tone, text, Optional.of(sprite)));
    }

    @Override
    public void progress(Component text, long value, long max, int rgb) {
        entries.add(InfoEntry.progress(text, value, max, rgb));
    }

    @Override
    public void progress(Identifier sprite, Component text, long value, long max, int rgb) {
        entries.add(InfoEntry.progress(text, value, max, rgb).withSprite(sprite));
    }

    @Override
    public void pips(Component text, int used, int capacity) {
        entries.add(InfoEntry.pips(text, used, capacity));
    }

    @Override
    public void stacks(Component text, List<InfoStack> stacks) {
        if (!stacks.isEmpty()) {
            entries.add(InfoEntry.stacks(text, stacks));
        }
    }

    @Override
    public void icon(Identifier sprite, Component text) {
        entries.add(InfoEntry.icon(sprite, text));
    }

    /** The entries so far, in order; the live list. */
    public List<InfoEntry> entries() {
        return entries;
    }
}
