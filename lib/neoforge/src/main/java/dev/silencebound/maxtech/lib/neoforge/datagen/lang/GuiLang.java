package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.api.client.gui.AccentEnergyBar;
import dev.silencebound.maxtech.lib.api.client.gui.AccentProgress;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTank;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import java.util.function.BiConsumer;

/** The screen components' shared texts: toggles, tanks, energy bars and progress. */
final class GuiLang {
    private GuiLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(ScreenComponents.ON_KEY, "on");
        lang.accept(ScreenComponents.OFF_KEY, "off");
        lang.accept(AccentTank.EMPTY_KEY, "Empty");
        lang.accept(AccentTank.AMOUNT_KEY, "%s / %s mB");
        lang.accept(AccentTank.UNIT_AMOUNT_KEY, "%s / %s");
        lang.accept(AccentTank.UNKNOWN_KEY, "Unknown");
        lang.accept(AccentEnergyBar.MU_KEY, "%s / %s MU");
        lang.accept(AccentEnergyBar.FE_KEY, "%s / %s FE");
        lang.accept(AccentProgress.PERCENT_KEY, "%s%%");
        lang.accept(AccentProgress.LEFT_KEY, "%s s left");
    }
}
