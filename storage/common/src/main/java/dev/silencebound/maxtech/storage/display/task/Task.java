package dev.silencebound.maxtech.storage.display.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One task on a board (M7C item 15). Immutable; a change makes a new one.
 *
 * @param id
 *            unique on its board, never reused there
 * @param name
 *            the player's words, at most {@link #MAX_NAME} characters
 * @param done
 *            whether it is ticked
 * @param how
 *            how it was ticked: {@link #BY_HAND}, {@link #BY_GOAL} or {@link #BY_LOGIC}; 0 while open
 * @param by
 *            who ticked it: the player's name, or the Logic Controller's name; empty for a goal
 * @param at
 *            the game time (the overworld's) it was ticked; 0 while open
 * @param progress
 *            items crafted for a crafted goal since the goal was set; 0 for other kinds
 */
public record Task(int id, String name, TaskGoal goal, boolean done, int how, String by, long at, long progress) {
    public static final int MAX_NAME = 40;
    public static final int MAX_BY = 32;
    public static final int BY_HAND = 1;
    public static final int BY_GOAL = 2;
    public static final int BY_LOGIC = 3;

    public static final Codec<Task> CODEC = RecordCodecBuilder.create(i -> i
            .group(Codec.INT.fieldOf("id").forGetter(Task::id), Codec.STRING.fieldOf("name").forGetter(Task::name),
                    TaskGoal.CODEC.optionalFieldOf("goal", TaskGoal.HAND).forGetter(Task::goal),
                    Codec.BOOL.optionalFieldOf("done", false).forGetter(Task::done),
                    Codec.INT.optionalFieldOf("how", 0).forGetter(Task::how),
                    Codec.STRING.optionalFieldOf("by", "").forGetter(Task::by),
                    Codec.LONG.optionalFieldOf("at", 0L).forGetter(Task::at),
                    Codec.LONG.optionalFieldOf("progress", 0L).forGetter(Task::progress))
            .apply(i, Task::new));
    public static final StreamCodec<ByteBuf, Task> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Task::id,
            ByteBufCodecs.stringUtf8(MAX_NAME * 4), Task::name, TaskGoal.STREAM_CODEC, Task::goal, ByteBufCodecs.BOOL,
            Task::done, ByteBufCodecs.VAR_INT, Task::how, ByteBufCodecs.stringUtf8(MAX_BY * 4), Task::by,
            ByteBufCodecs.VAR_LONG, Task::at, ByteBufCodecs.VAR_LONG, Task::progress, Task::new);

    public Task {
        name = clean(name, MAX_NAME);
        by = clean(by, MAX_BY);
        if (!done) {
            how = 0;
            by = "";
            at = 0;
        }
        progress = Math.max(0, progress);
    }

    /** {@code text} without line breaks and outer spaces, cut to {@code most} characters. */
    public static String clean(String text, int most) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').strip();
        return flat.length() > most ? flat.substring(0, most) : flat;
    }

    /** This task ticked {@code how} by {@code by} at {@code at}, or opened again with {@code done} false. */
    public Task withDone(boolean done, int how, String by, long at) {
        return new Task(id, name, goal, done, how, by, at, progress);
    }

    public Task withName(String name) {
        return new Task(id, name, goal, done, how, by, at, progress);
    }

    /** This task with another goal; a new goal starts its crafted count over. */
    public Task withGoal(TaskGoal goal) {
        return goal.equals(this.goal) ? this : new Task(id, name, goal, done, how, by, at, 0);
    }

    public Task withProgress(long progress) {
        return new Task(id, name, goal, done, how, by, at, progress);
    }
}
