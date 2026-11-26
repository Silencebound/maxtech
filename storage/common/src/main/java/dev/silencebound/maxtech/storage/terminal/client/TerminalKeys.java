package dev.silencebound.maxtech.storage.terminal.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import net.minecraft.client.KeyMapping;

/**
 * The terminal's key mappings; they act only while a terminal screen is open. The loader glue registers them (and
 * limits their conflict check to GUIs). Vanilla binds none of Z, R and L inside GUIs. Client only.
 */
public final class TerminalKeys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(MaxtechStorage.id("terminal"));
    /** Stores every main-inventory stack whose item is already stored. */
    public static final KeyMapping DEPOSIT = new KeyMapping(TerminalContent.DEPOSIT_KEY, InputConstants.KEY_Z,
            CATEGORY);
    /** Tops the hotbar's stacks up from storage. */
    public static final KeyMapping RESTOCK = new KeyMapping(TerminalContent.RESTOCK_KEY, InputConstants.KEY_R,
            CATEGORY);
    /** Tells where the entry under the mouse is stored and outlines those blocks (M7C). */
    public static final KeyMapping LOCATE = new KeyMapping(TerminalContent.LOCATE_KEY, InputConstants.KEY_L, CATEGORY);

    private TerminalKeys() {
    }
}
