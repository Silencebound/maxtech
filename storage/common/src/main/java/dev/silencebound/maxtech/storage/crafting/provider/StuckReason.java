package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/**
 * Why a pattern provider holds a craft up (M7C item 9), as the job view names it. A provider finds it out while it
 * works: when its machine refuses a batch, when the network will not take the outputs, or when the network or the
 * machine has no FE.
 */
public enum StuckReason {
    /** The machine would not take the batch's inputs (its input slots are full), or the assembler has no room. */
    MACHINE_FULL("machine_full", "the machine is full"),
    /** The outputs are in the machine but storage will not take them, so they stay and block the machine. */
    OUTPUT_BLOCKED("output_blocked", "its output is blocked: storage takes no more"),
    /** The provider's network has no power, or the machine reports no FE while a batch waits in it. */
    NO_POWER("no_power", "no power"),
    /**
     * A generator provider (M7C item 11) switched its machine on but nothing arrived within
     * {@code crafting.generatorTimeoutTicks}.
     */
    NOTHING_ARRIVED("nothing_arrived", "nothing arrived from the machine"),
    /**
     * A stonecutting or smithing pattern's recipe no longer gives the pattern's output (B09 item 16: a datapack changed
     * it), so the assembler refuses it instead of making something else.
     */
    RECIPE_CHANGED("recipe_changed", "its recipe no longer makes the pattern's output"),
    /** The provider's lock mode (B09 item 26) holds batches while it gets a redstone signal. */
    LOCKED_SIGNAL("locked_signal", "provider locked (redstone signal)"),
    /** The provider's lock mode holds batches while it gets no redstone signal. */
    LOCKED_NO_SIGNAL("locked_no_signal", "provider locked (no redstone signal)"),
    /** The provider pushed a batch and waits for a redstone pulse before the next. */
    LOCKED_PULSE("locked_pulse", "provider locked (waiting for a redstone pulse)"),
    /** The provider pushed a batch and waits until its main output is back in the network. */
    LOCKED_RESULT("locked_result", "provider locked (waiting for the result)");

    private static final IntFunction<StuckReason> BY_ID = ByIdMap.continuous(Enum::ordinal, values(),
            ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, StuckReason> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    private final String name;

    StuckReason(String name, String english) {
        this.name = name;
        ModContent.CONTENT.translation(translationKey(), english);
    }

    /** Whether the reason is a lock the player set, not a fault: alarms leave it out. */
    public boolean lock() {
        return this == LOCKED_SIGNAL || this == LOCKED_NO_SIGNAL || this == LOCKED_PULSE || this == LOCKED_RESULT;
    }

    /** The lang key of the reason's text. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".craft.stuck." + name;
    }
}
