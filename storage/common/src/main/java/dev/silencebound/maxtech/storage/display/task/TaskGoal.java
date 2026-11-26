package dev.silencebound.maxtech.storage.display.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * How a task gets done (M7C item 15): by hand (a right-click on the board), by an auto goal the board's screens look at
 * when their network says something changed (an item count, an amount crafted since the goal was set, a number of
 * devices with a label, a research step of the team), or by logic. Immutable; any thread.
 *
 * @param target
 *            the item id (count and crafted), the label (devices) or the research node id; empty for hand and logic
 * @param amount
 *            how many items or devices; 1 for research, 0 for hand and logic
 */
public record TaskGoal(Kind kind, String target, long amount) {
    /** Longest target text. */
    public static final int MAX_TARGET = 64;
    /** Most items or devices a goal can ask for. */
    public static final long MAX_AMOUNT = 1L << 40;
    public static final TaskGoal HAND = new TaskGoal(Kind.HAND, "", 0);

    public static final Codec<TaskGoal> CODEC = RecordCodecBuilder
            .create(i -> i
                    .group(Kind.CODEC.optionalFieldOf("kind", Kind.HAND).forGetter(TaskGoal::kind),
                            Codec.STRING.optionalFieldOf("target", "").forGetter(TaskGoal::target),
                            Codec.LONG.optionalFieldOf("amount", 0L).forGetter(TaskGoal::amount))
                    .apply(i, TaskGoal::new));
    public static final StreamCodec<ByteBuf, TaskGoal> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(Kind::byId, Kind::id), TaskGoal::kind, ByteBufCodecs.stringUtf8(MAX_TARGET * 4),
            TaskGoal::target, ByteBufCodecs.VAR_LONG, TaskGoal::amount, TaskGoal::new);

    /** Cleans what a save or a payload brings: the target cut and trimmed, the amount in range for the kind. */
    public TaskGoal {
        target = target.strip();
        if (target.length() > MAX_TARGET) {
            target = target.substring(0, MAX_TARGET);
        }
        switch (kind) {
            case HAND, LOGIC -> {
                target = "";
                amount = 0;
            }
            case RESEARCH -> amount = 1;
            default -> amount = Math.clamp(amount, 1, MAX_AMOUNT);
        }
    }

    /** Whether screens look at the goal themselves. */
    public boolean auto() {
        return kind.auto();
    }

    /** The item an item count or crafted goal names; empty for other kinds and for ids naming no item. */
    public Optional<Item> item() {
        if (kind != Kind.ITEM_COUNT && kind != Kind.CRAFTED) {
            return Optional.empty();
        }
        Identifier id = Identifier.tryParse(target.indexOf(':') < 0 ? "minecraft:" + target : target);
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id).filter(item -> !Items.AIR.equals(item));
    }

    /** The research node a research goal names; empty for other kinds and for text that is no id. */
    public Optional<Identifier> research() {
        return kind == Kind.RESEARCH ? Optional.ofNullable(Identifier.tryParse(target)) : Optional.empty();
    }

    /**
     * Why the goal cannot be saved as it is, as a lang key, or empty when it can. Research nodes are checked against
     * the tree by the server ({@link TaskBoards#problem}).
     */
    public Optional<String> problem() {
        return switch (kind) {
            case ITEM_COUNT, CRAFTED -> item().isPresent() ? Optional.empty() : Optional.of(TaskTexts.NO_ITEM);
            case LABEL_COUNT -> target.isEmpty() ? Optional.of(TaskTexts.NO_LABEL) : Optional.empty();
            case RESEARCH -> research().isPresent() ? Optional.empty() : Optional.of(TaskTexts.NO_RESEARCH);
            case HAND, LOGIC -> Optional.empty();
        };
    }

    /** The ways a task gets done; ids are sent and names saved, so neither may change. */
    public enum Kind implements StringRepresentable {
        HAND("hand", 0, false), ITEM_COUNT("item_count", 1, true), CRAFTED("crafted", 2,
                true), LABEL_COUNT("label_count", 3, true), RESEARCH("research", 4, true), LOGIC("logic", 5, false);

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private static final Kind[] VALUES = values();

        private final String name;
        private final int id;
        private final boolean auto;

        Kind(String name, int id, boolean auto) {
            this.name = name;
            this.id = id;
            this.auto = auto;
        }

        public int id() {
            return id;
        }

        /** The kind with id {@code id}; an unknown one reads as {@link #HAND}. */
        public static Kind byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : HAND;
        }

        /** The next kind, after the last the first; for the editor's button. */
        public Kind next() {
            return byId((id + 1) % VALUES.length);
        }

        public boolean auto() {
            return auto;
        }

        /** The lang key of the kind's name in the editor. */
        public String translationKey() {
            return "gui." + MaxtechStorage.MOD_ID + ".task.goal." + name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
