package dev.silencebound.maxtech.storage.crafting.provider;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * When a pattern provider may push a batch (B09 item 26, AE2's pattern provider lock modes): always, not while it gets
 * a redstone signal, only while it gets one, one batch per redstone pulse, or one batch at a time until that batch's
 * main output is back in the network. Saves, items and menus hold the fixed {@link #id()}, never the ordinal.
 */
public enum LockMode {
    /** Pushes whenever the machine can take a batch; the default. */
    NONE("none", 0, "None", "Pushes batches whenever the machine can take them"),
    /** Pushes nothing while the provider gets a redstone signal. */
    WITH_SIGNAL("with_signal", 1, "Locked with signal", "Pushes no batch while it gets a redstone signal"),
    /** Pushes only while the provider gets a redstone signal. */
    WITHOUT_SIGNAL("without_signal", 2, "Locked without signal", "Pushes batches only while it gets a redstone signal"),
    /** Pushes one batch, then waits for a rising edge of the signal. */
    UNTIL_PULSE("until_pulse", 3, "Until redstone pulse", "Pushes one batch, then waits for a redstone pulse"),
    /** Pushes one batch, then waits until the batch's main output entered the network. */
    UNTIL_RESULT("until_result", 4, "Until result returns",
            "Pushes one batch, then waits until its main output is back in the network");

    /** The mode as its id, for item components and saves. */
    public static final Codec<LockMode> CODEC = Codec.INT.xmap(LockMode::byId, LockMode::id);
    public static final StreamCodec<ByteBuf, LockMode> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(LockMode::byId,
            LockMode::id);

    private final String name;
    private final int id;

    LockMode(String name, int id, String english, String hint) {
        this.name = name;
        this.id = id;
        ModContent.CONTENT.translation(translationKey(), english);
        ModContent.CONTENT.translation(hintKey(), hint);
    }

    /** The mode's fixed number for saves, items and menus. */
    public int id() {
        return id;
    }

    /** The mode with {@link #id()} {@code id}; {@link #NONE} for an unknown one. */
    public static LockMode byId(int id) {
        for (LockMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return NONE;
    }

    /** Whether the mode reads the redstone signal. */
    public boolean redstone() {
        return this == WITH_SIGNAL || this == WITHOUT_SIGNAL || this == UNTIL_PULSE;
    }

    /** The next mode in the cycle. */
    public LockMode next() {
        return switch (this) {
            case NONE -> WITH_SIGNAL;
            case WITH_SIGNAL -> WITHOUT_SIGNAL;
            case WITHOUT_SIGNAL -> UNTIL_PULSE;
            case UNTIL_PULSE -> UNTIL_RESULT;
            case UNTIL_RESULT -> NONE;
        };
    }

    /** The previous mode in the cycle. */
    public LockMode previous() {
        return switch (this) {
            case NONE -> UNTIL_RESULT;
            case WITH_SIGNAL -> NONE;
            case WITHOUT_SIGNAL -> WITH_SIGNAL;
            case UNTIL_PULSE -> WITHOUT_SIGNAL;
            case UNTIL_RESULT -> UNTIL_PULSE;
        };
    }

    /** The lang key of the mode's short name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".provider_lock." + name;
    }

    /** The lang key of the line that says what the mode does. */
    public String hintKey() {
        return translationKey() + ".hint";
    }
}
