package dev.silencebound.maxtech.lib.client.menu;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.RelativeFace;
import java.util.Locale;
import net.minecraft.core.Direction;

/** The lang keys of faces nodes and the side config tab, for the screen and Lib's language datagen. */
public final class FaceTexts {
    /** The side config tab's title. */
    public static final String TAB_KEY = "gui.maxtech_lib.faces.tab";
    /** The preview's narration. */
    public static final String PREVIEW_KEY = "gui.maxtech_lib.faces.preview";
    /** A relative face with its absolute direction: {@code %s (%s)}. */
    public static final String FACE_KEY = "gui.maxtech_lib.faces.face";
    /** A kind with its mode: {@code %s: %s}. */
    public static final String MODE_KEY = "gui.maxtech_lib.faces.mode";
    /** How to change a face. */
    public static final String HINT_KEY = "gui.maxtech_lib.faces.hint";
    /** A kind whose faces cannot change (the port allows only off). */
    public static final String FIXED_KEY = "gui.maxtech_lib.faces.fixed";

    private FaceTexts() {
    }

    /** A kind's name: {@code kind.<namespace>.<path>}; screens fall back to the id's path. */
    public static String kindKey(ResourceKind<?> kind) {
        return "kind." + kind.id().getNamespace() + "." + kind.id().getPath();
    }

    public static String relativeKey(RelativeFace face) {
        return "gui.maxtech_lib.faces." + face.name().toLowerCase(Locale.ROOT);
    }

    public static String directionKey(Direction face) {
        return "gui.maxtech_lib.faces." + face.getSerializedName();
    }

    public static String modeKey(PortFaceMode mode) {
        return "gui.maxtech_lib.faces.mode." + mode.name().toLowerCase(Locale.ROOT);
    }
}
