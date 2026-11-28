package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.RelativeFace;
import dev.silencebound.maxtech.lib.client.menu.FaceTexts;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.minecraft.core.Direction;

/** The side config tab of machine screens. */
final class FaceConfigLang {
    private FaceConfigLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(FaceTexts.TAB_KEY, "Side config");
        lang.accept(FaceTexts.PREVIEW_KEY, "Block preview: drag to turn, click a face to change it");
        lang.accept(FaceTexts.FACE_KEY, "%s (%s)");
        lang.accept(FaceTexts.MODE_KEY, "%s: %s");
        lang.accept(FaceTexts.HINT_KEY, "Left click: next mode, right click: previous");
        lang.accept(FaceTexts.FIXED_KEY, "This side can't be changed");
        for (RelativeFace face : RelativeFace.values()) {
            String name = face.name().toLowerCase(Locale.ROOT);
            lang.accept(FaceTexts.relativeKey(face), Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
        for (Direction face : Direction.values()) {
            String name = face.getSerializedName();
            lang.accept(FaceTexts.directionKey(face), Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
        lang.accept(FaceTexts.modeKey(PortFaceMode.OFF), "Off");
        lang.accept(FaceTexts.modeKey(PortFaceMode.PUSH), "Push");
        lang.accept(FaceTexts.modeKey(PortFaceMode.PULL), "Pull");
        lang.accept(FaceTexts.kindKey(ResourceKinds.ITEM), "Items");
        lang.accept(FaceTexts.kindKey(ResourceKinds.FLUID), "Fluids");
        lang.accept(FaceTexts.kindKey(ResourceKinds.ENERGY), "Energy");
    }
}
