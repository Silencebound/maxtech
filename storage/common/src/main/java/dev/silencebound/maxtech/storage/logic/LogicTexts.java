package dev.silencebound.maxtech.storage.logic;

import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Map;

/** Declares the logic module's lang lines ({@link LogicLang}) with Storage's content builder, for datagen. */
public final class LogicTexts {
    private LogicTexts() {
    }

    /** Called from {@link ModContent#init()} during mod construction. */
    public static void init() {
        for (Map.Entry<String, String> line : LogicLang.english().entrySet()) {
            ModContent.CONTENT.translation(line.getKey(), line.getValue());
        }
    }
}
