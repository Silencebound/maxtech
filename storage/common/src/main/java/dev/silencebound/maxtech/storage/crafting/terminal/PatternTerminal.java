package dev.silencebound.maxtech.storage.crafting.terminal;

import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import net.minecraft.world.entity.player.Player;

/**
 * A pattern terminal in either form, the block or the part on a cable: a network terminal ({@code TerminalMenu} opens
 * with the {@link PatternPanel} for any source of this type) that also holds a {@link PatternEncoder}. Server thread.
 */
public interface PatternTerminal extends TerminalSource, NetworkDevice {
    /** The draft and what edits it. */
    PatternEncoder encoder();

    /** Whether {@code player} may open the terminal and encode: the craft access level (M7 C17). */
    default boolean canUse(Player player) {
        return NetworkAccess.level(player, this).atLeast(AccessLevel.CRAFT);
    }
}
