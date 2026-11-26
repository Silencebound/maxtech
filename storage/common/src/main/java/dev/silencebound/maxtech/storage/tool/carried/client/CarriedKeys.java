package dev.silencebound.maxtech.storage.tool.carried.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.tool.carried.CarriedContent;
import dev.silencebound.maxtech.storage.tool.carried.CarriedOpen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The one "open a carried gadget" key mapping (owner decision 2026-10-09, replacing a key per gadget), unbound by
 * default so it clashes with nothing. A press asks the server ({@link CarriedOpen.Pick}), which opens the only gadget
 * carried or sends the choices for {@link GadgetPickerScreen}. The loader glue registers it and calls {@link #tick}
 * each client tick. Client only.
 */
public final class CarriedKeys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(MaxtechStorage.id("gadgets"));
    public static final KeyMapping OPEN = new KeyMapping(CarriedContent.OPEN_KEY, InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    private CarriedKeys() {
    }

    /** Sends one pick request per press while in a world with no screen open; drops presses made in a screen. */
    public static void tick(Minecraft minecraft) {
        boolean playing = minecraft.player != null && minecraft.screen == null;
        while (OPEN.consumeClick()) {
            if (playing) {
                Platform.get().network().sendToServer(new CarriedOpen.Pick());
            }
        }
    }
}
