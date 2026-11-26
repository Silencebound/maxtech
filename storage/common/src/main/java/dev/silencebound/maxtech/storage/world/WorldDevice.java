package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * What the settings GUI ({@link WorldMenu}) changes on a world device, in its block form or as a cable part: the
 * filter, the mode and, for a device that keeps one, the tool. Server thread.
 */
public interface WorldDevice extends Owned {
    WorldKind kind();

    WorldFilter filter();

    /** The current mode, 0 to {@code kind().modeCount() - 1}. */
    int mode();

    /** Switches to {@code mode} (taken modulo the mode count), saves and wakes the device. */
    void setMode(int mode);

    /** The current action, 0 to {@code kind().actionCount() - 1} (B09 item 25); 0 for a kind with one. */
    default int action() {
        return 0;
    }

    /** Switches to {@code action} (taken modulo the action count), saves and wakes the device; ignored by default. */
    default void setAction(int action) {
    }

    /** The one-slot container of the device's tool, or null for a kind without one. */
    default @Nullable Container tool() {
        return null;
    }

    /** Whether the owner's team has the device's research; the GUI says so when it has not. */
    boolean researched();

    /**
     * Opens what the device holds for {@code player} (the network chest's chest view) and returns whether it has such a
     * view; the settings GUI's second button. None by default.
     */
    default boolean openContents(ServerPlayer player) {
        return false;
    }

    /**
     * The block entity or cable part that holds the device, which access checks ask ({@link NetworkAccess}): with a
     * security module its network's rules, else its owner security.
     */
    Owned holder();

    /** Whether {@code player} may keep the GUI open: the device is there and within reach. */
    boolean stillValid(Player player);
}
