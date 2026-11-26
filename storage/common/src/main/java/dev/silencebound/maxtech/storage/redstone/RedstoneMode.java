package dev.silencebound.maxtech.storage.redstone;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * When a device works by the redstone signal at it (B09 item 9): always ({@link #IGNORE}, the default), only while it
 * gets a signal, only while it gets none, or one operation per rising edge ({@link #PULSE}, ports only). Saves, items
 * and menus hold the fixed {@link #id()}, never the ordinal, so a new mode gets a new id and an old one never changes.
 */
public enum RedstoneMode {
    /** Works whatever the signal; the default. */
    IGNORE("ignore", 0),
    /** Works only while the device gets a redstone signal. */
    WITH_SIGNAL("with_signal", 1),
    /** Works only while the device gets no redstone signal. */
    WITHOUT_SIGNAL("without_signal", 2),
    /** Works one operation per rising edge of the signal (ports only). */
    PULSE("pulse", 3);

    /** The mode as its id, for item components and saves. */
    public static final Codec<RedstoneMode> CODEC = Codec.INT.xmap(RedstoneMode::byId, RedstoneMode::id);
    public static final StreamCodec<ByteBuf, RedstoneMode> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(RedstoneMode::byId,
            RedstoneMode::id);

    private final String name;
    private final int id;

    RedstoneMode(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** The mode's fixed number for saves, items and menus. */
    public int id() {
        return id;
    }

    /** The mode with {@link #id()} {@code id}; {@link #IGNORE} for an unknown one. */
    public static RedstoneMode byId(int id) {
        for (RedstoneMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return IGNORE;
    }

    /**
     * Whether a device in this mode may work with the signal {@code powered}. {@link #PULSE} counts as active: its
     * device works only on the edges it counted, which it checks itself.
     */
    public boolean active(boolean powered) {
        return switch (this) {
            case IGNORE, PULSE -> true;
            case WITH_SIGNAL -> powered;
            case WITHOUT_SIGNAL -> !powered;
        };
    }

    /** The next mode in the cycle; {@link #PULSE} only for devices that {@code pulses}. */
    public RedstoneMode next(boolean pulses) {
        return switch (this) {
            case IGNORE -> WITH_SIGNAL;
            case WITH_SIGNAL -> WITHOUT_SIGNAL;
            case WITHOUT_SIGNAL -> pulses ? PULSE : IGNORE;
            case PULSE -> IGNORE;
        };
    }

    /** The previous mode in the cycle; {@link #PULSE} only for devices that {@code pulses}. */
    public RedstoneMode previous(boolean pulses) {
        return switch (this) {
            case IGNORE -> pulses ? PULSE : WITHOUT_SIGNAL;
            case WITH_SIGNAL -> IGNORE;
            case WITHOUT_SIGNAL -> WITH_SIGNAL;
            case PULSE -> WITHOUT_SIGNAL;
        };
    }

    /** The lang key of the mode's short name, for buttons and tooltips. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".redstone." + name;
    }

    /** The lang key of the line that says what the mode does. */
    public String hintKey() {
        return translationKey() + ".hint";
    }
}
