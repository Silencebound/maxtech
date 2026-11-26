package dev.silencebound.maxtech.storage.terminal.crafting;

import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;

/**
 * A crafting terminal in either form, the block or the part on a cable: a network terminal that also holds a
 * {@link TerminalGrid}. {@code TerminalMenu} opens with the crafting panel for any source of this type. Server thread.
 */
public interface CraftingTerminal extends TerminalSource, NetworkDevice {
    /** The grid, saved with the terminal. */
    TerminalGrid grid();
}
