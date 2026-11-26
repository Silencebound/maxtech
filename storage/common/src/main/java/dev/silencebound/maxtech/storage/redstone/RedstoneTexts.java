package dev.silencebound.maxtech.storage.redstone;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The redstone mode's player-facing texts (B09 item 9): the modes' names and hints, the menus' and Jade's lines. */
public final class RedstoneTexts {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".redstone.";
    /** "Redstone: %s", the item tooltip and the menu's caption. */
    public static final String LINE_KEY = PREFIX + "line";
    /** "Redstone", the caption above the mode's button. */
    public static final String CAPTION_KEY = PREFIX + "caption";
    /** Jade and the status: inactive, waiting for a signal. */
    public static final String WAITS_FOR_SIGNAL_KEY = PREFIX + "waits_for_signal";
    /** Jade and the status: inactive while it has a signal. */
    public static final String HAS_SIGNAL_KEY = PREFIX + "has_signal";
    /** The status of a device in pulse mode. */
    public static final String WAITS_FOR_PULSE_KEY = PREFIX + "waits_for_pulse";

    static {
        ModContent.CONTENT.translation(RedstoneMode.IGNORE.translationKey(), "Ignore redstone");
        ModContent.CONTENT.translation(RedstoneMode.WITH_SIGNAL.translationKey(), "With signal");
        ModContent.CONTENT.translation(RedstoneMode.WITHOUT_SIGNAL.translationKey(), "Without signal");
        ModContent.CONTENT.translation(RedstoneMode.PULSE.translationKey(), "Pulse");
        ModContent.CONTENT.translation(RedstoneMode.IGNORE.hintKey(), "Works whatever the signal");
        ModContent.CONTENT.translation(RedstoneMode.WITH_SIGNAL.hintKey(), "Works only while it gets a signal");
        ModContent.CONTENT.translation(RedstoneMode.WITHOUT_SIGNAL.hintKey(), "Works only while it gets no signal");
        ModContent.CONTENT.translation(RedstoneMode.PULSE.hintKey(), "One operation per redstone pulse");
        ModContent.CONTENT.translation(LINE_KEY, "Redstone: %s");
        ModContent.CONTENT.translation(CAPTION_KEY, "Redstone");
        ModContent.CONTENT.translation(WAITS_FOR_SIGNAL_KEY, "Inactive: waits for a redstone signal");
        ModContent.CONTENT.translation(HAS_SIGNAL_KEY, "Inactive: has a redstone signal");
        ModContent.CONTENT.translation(WAITS_FOR_PULSE_KEY, "Waits for a redstone pulse");
    }

    private RedstoneTexts() {
    }

    /** Loads the class during mod construction so the texts above are declared. */
    public static void init() {
    }

    /** "Redstone: With signal". */
    public static Component line(RedstoneMode mode) {
        return Component.translatable(LINE_KEY, Component.translatable(mode.translationKey()));
    }

    /** Why {@code control}'s device does not work now: waits for a signal or has one; null while it is active. */
    public static @Nullable Component inactiveReason(RedstoneControl control) {
        if (!control.inactive()) {
            return null;
        }
        return Component.translatable(control.powered() ? HAS_SIGNAL_KEY : WAITS_FOR_SIGNAL_KEY);
    }
}
