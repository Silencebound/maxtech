package dev.silencebound.maxtech.storage.part;

import dev.silencebound.maxtech.storage.registry.ModContent;

/**
 * The cable part framework's texts. Each device area registers its own {@link PartType} and item in its content class;
 * nothing else is registered here.
 */
public final class PartContent {
    static {
        ModContent.CONTENT.translation(PartItem.NOT_A_CABLE_KEY, "Parts go on a storage cable");
        ModContent.CONTENT.translation(PartItem.OCCUPIED_KEY, "That face of the cable already has a part");
    }

    private PartContent() {
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
        ComponentCopyingRecipe.init();
    }
}
