package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * One part of a rule's change history line (M7L2 chunk 9): a lang key ({@code LogicLang.HISTORY_*}) filled with up to
 * {@link #MAX_ARGS} arguments, each a lang key (a node type's or setting's name, yes/no, a choice) or plain text (a
 * number, a label, an item id). Kept as keys and arguments, never as resolved text, so each player reads it in their
 * own language. Immutable.
 *
 * @param key
 *            the lang key of the part
 * @param args
 *            its arguments, at most {@link #MAX_ARGS}
 */
public record GraphChange(String key, List<Arg> args) {
    /** Most arguments one part carries. */
    public static final int MAX_ARGS = 4;
    /** Longest key or argument text; longer text is cut. */
    public static final int MAX_TEXT = 160;

    /**
     * One argument.
     *
     * @param text
     *            the lang key or the plain text
     * @param translate
     *            whether {@code text} is a lang key
     */
    public record Arg(String text, boolean translate) {
        public static final Codec<Arg> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(Codec.STRING.fieldOf("t").forGetter(Arg::text),
                                Codec.BOOL.optionalFieldOf("k", false).forGetter(Arg::translate))
                        .apply(instance, Arg::new));

        public Arg {
            text = cut(text);
        }

        /** Plain text. */
        public static Arg text(String text) {
            return new Arg(text, false);
        }

        /** A lang key. */
        public static Arg key(String key) {
            return new Arg(key, true);
        }
    }

    public static final Codec<GraphChange> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.STRING.fieldOf("key").forGetter(GraphChange::key),
                    Arg.CODEC.listOf(0, MAX_ARGS).optionalFieldOf("args", List.of()).forGetter(GraphChange::args))
            .apply(instance, GraphChange::new));

    public GraphChange {
        key = cut(key);
        args = args.size() > MAX_ARGS ? List.copyOf(args.subList(0, MAX_ARGS)) : List.copyOf(args);
    }

    /** A part with plain-text arguments. */
    public static GraphChange of(String key, String... args) {
        List<Arg> list = new ArrayList<>(args.length);
        for (String arg : args) {
            list.add(Arg.text(arg));
        }
        return new GraphChange(key, list);
    }

    private static String cut(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }
}
