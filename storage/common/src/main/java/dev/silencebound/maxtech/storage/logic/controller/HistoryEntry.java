package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One line of a rule's change history (M7L2 chunk 9): when, who and what changed. Saved with the rule
 * ({@link GraphSlot#history()}), newest last, at most {@code logic.historyEntries} per rule. The player is kept as
 * their UUID and the name they had then, so the line still reads after they leave or rename. Immutable.
 *
 * @param gameTime
 *            the game time of the change; its day is {@code gameTime / 24000 + 1}, counted like the ledger's days
 * @param realTime
 *            the server's clock when it happened, in milliseconds since the epoch
 * @param player
 *            the player who made the change; empty for none (a command)
 * @param name
 *            that player's name at the time, at most {@link #MAX_NAME} characters
 * @param changes
 *            what changed, at most {@link #MAX_CHANGES} parts
 */
public record HistoryEntry(long gameTime, long realTime, Optional<UUID> player, String name,
        List<GraphChange> changes) {
    /** Most history entries the codec reads per rule, whatever the config allows. */
    public static final int HARD_MAX = 64;
    /** Most parts one entry keeps. */
    public static final int MAX_CHANGES = 12;
    /** Longest player name kept. */
    public static final int MAX_NAME = 32;
    /** Game ticks per day, as the ledger counts days. */
    public static final long TICKS_PER_DAY = 24_000L;

    public static final Codec<HistoryEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("time").forGetter(HistoryEntry::gameTime),
            Codec.LONG.optionalFieldOf("real", 0L).forGetter(HistoryEntry::realTime),
            UUIDUtil.CODEC.optionalFieldOf("player").forGetter(HistoryEntry::player),
            Codec.STRING.optionalFieldOf("name", "").forGetter(HistoryEntry::name), GraphChange.CODEC
                    .listOf(0, MAX_CHANGES).optionalFieldOf("changes", List.of()).forGetter(HistoryEntry::changes))
            .apply(instance, HistoryEntry::new));
    public static final Codec<List<HistoryEntry>> LIST_CODEC = CODEC.listOf(0, HARD_MAX);

    private static final StreamCodec<ByteBuf, GraphChange.Arg> ARG_STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(GraphChange.MAX_TEXT), GraphChange.Arg::text, ByteBufCodecs.BOOL,
            GraphChange.Arg::translate, GraphChange.Arg::new);
    private static final StreamCodec<ByteBuf, GraphChange> CHANGE_STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(GraphChange.MAX_TEXT), GraphChange::key,
            ARG_STREAM_CODEC.apply(ByteBufCodecs.list(GraphChange.MAX_ARGS)), GraphChange::args, GraphChange::new);
    public static final StreamCodec<ByteBuf, HistoryEntry> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG,
            HistoryEntry::gameTime, ByteBufCodecs.VAR_LONG, HistoryEntry::realTime,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), HistoryEntry::player, ByteBufCodecs.stringUtf8(MAX_NAME),
            HistoryEntry::name, CHANGE_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CHANGES)), HistoryEntry::changes,
            HistoryEntry::new);

    public HistoryEntry {
        name = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        changes = changes.size() > MAX_CHANGES ? List.copyOf(changes.subList(0, MAX_CHANGES)) : List.copyOf(changes);
    }

    /** The in-game day of the change, from 1. */
    public long day() {
        return Math.floorDiv(gameTime, TICKS_PER_DAY) + 1;
    }

    /**
     * {@code history} with {@code entry} added last, keeping only the newest {@code keep} entries (none when
     * {@code keep} is 0 or less). Allocates.
     */
    public static List<HistoryEntry> append(List<HistoryEntry> history, HistoryEntry entry, int keep) {
        if (keep <= 0) {
            return List.of();
        }
        int from = Math.max(0, history.size() + 1 - keep);
        ArrayList<HistoryEntry> out = new ArrayList<>(Math.min(keep, history.size() + 1));
        for (int i = from; i < history.size(); i++) {
            out.add(history.get(i));
        }
        out.add(entry);
        return List.copyOf(out);
    }

    /** The newest {@code keep} entries of {@code history}. */
    public static List<HistoryEntry> trim(List<HistoryEntry> history, int keep) {
        if (keep <= 0) {
            return List.of();
        }
        return history.size() <= keep
                ? List.copyOf(history)
                : List.copyOf(history.subList(history.size() - keep, history.size()));
    }
}
