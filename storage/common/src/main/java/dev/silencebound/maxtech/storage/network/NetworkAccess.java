package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import net.minecraft.world.entity.player.Player;

/**
 * The one access check for storage devices (PRD 7.2; M6c2 security module). A device in a network whose controller has
 * a security module answers with the network's rules for the player; otherwise with Lib's owner security on the device
 * itself, as before: its owner, trusted players, its owner's team and game masters get {@link AccessLevel#FULL},
 * everyone else {@link AccessLevel#NONE}. Device screens, configuration clicks and part removal ask
 * {@link #canConfigure}; terminals ask their network's {@link NetworkState#accessLevel}. Server thread for the network
 * rules; on a client (no network) it falls back to owner security.
 */
public final class NetworkAccess {
    private NetworkAccess() {
    }

    /** The level {@code player} has on {@code device}; see the class comment. */
    public static AccessLevel level(Player player, Owned device) {
        NetworkState network = device instanceof NetworkDevice networked ? networked.deviceLink().network() : null;
        AccessLevel rule = network == null ? null : network.securityLevel(player);
        if (rule != null) {
            return rule;
        }
        return OwnerSecurity.canAccess(player, device) ? AccessLevel.FULL : AccessLevel.NONE;
    }

    /** Whether {@code player} may open {@code device}'s screen, change its settings or take it apart: full access. */
    public static boolean canConfigure(Player player, Owned device) {
        return level(player, device) == AccessLevel.FULL;
    }
}
