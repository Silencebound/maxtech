package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLabels;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.info.ConduitInfo;
import java.util.function.BiConsumer;
import net.minecraft.core.Direction;

/** Conduit faces, info lines and accessibility labels. */
final class ConduitLang {
    private ConduitLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(ConduitBlock.FACE_FIXED_KEY, "%s face: no other mode");
        lang.accept(ConduitInfo.CONDUIT_KEY, "%s (%s, %s)");
        lang.accept(ConduitInfo.UNCOLOURED_KEY, "uncoloured");
        lang.accept(ConduitInfo.FACE_KEY, "%s face: %s");
        lang.accept(ConduitInfo.faceModeKey(ConduitFaceMode.BOTH), "insert and extract");
        lang.accept(ConduitInfo.faceModeKey(ConduitFaceMode.INSERT), "insert");
        lang.accept(ConduitInfo.faceModeKey(ConduitFaceMode.EXTRACT), "extract");
        lang.accept(ConduitInfo.faceModeKey(ConduitFaceMode.DISABLED), "disabled");
        lang.accept(ConduitInfo.sideKey(Direction.DOWN), "Bottom");
        lang.accept(ConduitInfo.sideKey(Direction.UP), "Top");
        lang.accept(ConduitInfo.sideKey(Direction.NORTH), "North");
        lang.accept(ConduitInfo.sideKey(Direction.SOUTH), "South");
        lang.accept(ConduitInfo.sideKey(Direction.WEST), "West");
        lang.accept(ConduitInfo.sideKey(Direction.EAST), "East");
        lang.accept(ConduitLabels.COLOUR_MARK_KEY, "Colour mark %s: %s");
        lang.accept(ConduitLabels.MODE_MARK_KEY, "%s face: %s, marked %s");
        lang.accept(ConduitLabels.modeGlyphKey(ConduitFaceMode.INSERT), "with an arrow into the block");
        lang.accept(ConduitLabels.modeGlyphKey(ConduitFaceMode.EXTRACT), "with an arrow out of the block");
        lang.accept(ConduitLabels.modeGlyphKey(ConduitFaceMode.BOTH), "with a diamond");
    }
}
