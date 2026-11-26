package dev.silencebound.maxtech.storage.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.display.DisplayText;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A network's log of logic notices (M7L): the last {@link #CAPACITY} lines that notify nodes and failed actions posted,
 * oldest first. The network controller's Network tab and the analyzer list them. Kept with the network and not saved,
 * like its history: a rebuilt network starts an empty log. Server thread only.
 */
public final class NetworkNotices {
    /** Most lines kept; older ones go first. */
    public static final int CAPACITY = 32;
    /** Longest text kept, in characters; a notice's own text is a node setting and shorter. */
    public static final int MAX_LENGTH = 256;

    /**
     * One line. Server and client build its text the same way ({@link #text()}), so lang keys travel instead of
     * resolved text: a dedicated server has no mod translations.
     *
     * @param gameTime
     *            when it was posted
     * @param key
     *            a lang key with one {@code %s}, or empty for a player's own words in {@code text}
     * @param text
     *            the player's words, or the argument's own text when {@code argKey} is empty
     * @param argKey
     *            the lang key of the argument (an item's name), or empty
     */
    public record Notice(long gameTime, String key, String text, String argKey) {
        /** A notice on the wire, as the analyzer's overview writes it. */
        public static final StreamCodec<ByteBuf, Notice> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG,
                Notice::gameTime, ByteBufCodecs.stringUtf8(MAX_LENGTH), Notice::key,
                ByteBufCodecs.stringUtf8(MAX_LENGTH), Notice::text, ByteBufCodecs.stringUtf8(MAX_LENGTH),
                Notice::argKey, Notice::new);
        /** A notice as saved, on a logic remote's last known notices (M7L2). */
        public static final Codec<Notice> CODEC = RecordCodecBuilder.create(i -> i
                .group(Codec.LONG.fieldOf("time").forGetter(Notice::gameTime),
                        Codec.string(0, MAX_LENGTH).optionalFieldOf("key", "").forGetter(Notice::key),
                        Codec.string(0, MAX_LENGTH).optionalFieldOf("text", "").forGetter(Notice::text),
                        Codec.string(0, MAX_LENGTH).optionalFieldOf("arg", "").forGetter(Notice::argKey))
                .apply(i, Notice::new));

        public Notice {
            text = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        }

        /** The line to show; a player's own words are read as display markup ({@link DisplayText#component}). */
        public Component line() {
            if (key.isEmpty()) {
                return DisplayText.component(text);
            }
            return Component.translatable(key,
                    argKey.isEmpty() ? Component.literal(text) : Component.translatable(argKey));
        }
    }

    private final ArrayList<Notice> lines = new ArrayList<>(CAPACITY);
    private final Consumer<Notice> onPost;
    private long posted;

    /** A log nobody listens to. */
    public NetworkNotices() {
        this(notice -> {
        });
    }

    /** A log that hands every posted line to {@code onPost} after keeping it (logic remotes, M7L2). */
    public NetworkNotices(Consumer<Notice> onPost) {
        this.onPost = onPost;
    }

    /** Adds {@code notice}; the oldest line goes when the log is full. */
    public void post(Notice notice) {
        if (lines.size() >= CAPACITY) {
            lines.remove(0);
        }
        lines.add(notice);
        posted++;
        onPost.accept(notice);
    }

    /** The lines, oldest first; a copy. */
    public List<Notice> lines() {
        return List.copyOf(lines);
    }

    /** The newest {@code limit} lines, oldest of them first; a copy. */
    public List<Notice> newest(int limit) {
        int from = Math.max(0, lines.size() - Math.max(0, limit));
        return List.copyOf(lines.subList(from, lines.size()));
    }

    /** Lines posted since the network was built, including those that dropped off; tests use it. */
    public long posted() {
        return posted;
    }
}
