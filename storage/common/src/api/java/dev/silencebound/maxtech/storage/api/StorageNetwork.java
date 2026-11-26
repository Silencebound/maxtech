package dev.silencebound.maxtech.storage.api;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import net.minecraft.world.entity.player.Player;

/**
 * One storage network as other mods see it: connected storage cables and devices, at most one controller, what it
 * holds, its energy and its owner (PRD 7.2). Read-only here; moving things in and out runs inside NeoForge transfer
 * transactions, which this loader-neutral api does not name (ADR-0002), so Storage's own devices do that through the
 * network's implementation. Devices get the network when they join ({@link StorageDevice#networkChanged}); a merge or
 * split hands them a new one, so do not keep it past that call.
 *
 * <p>
 * Server thread only.
 */
public interface StorageNetwork {
    /**
     * Whether the network works and, if not, why. A second controller does not stop a network (it goes on standby), and
     * a network without a controller is limited by its ad-hoc channels, not stopped; Storage 0.5.0 removed the two
     * values that once reported those.
     */
    enum Status {
        /** Working, as far as structure goes; each operation still needs its energy. */
        ONLINE,
        /** In {@code pause} mode with a chunk of the network unloaded: nothing moves until it loads. */
        PAUSED
    }

    /** The network's state; see {@link Status}. O(1). */
    Status status();

    /**
     * Whether operations can run: {@link Status#ONLINE} and enough energy stored for at least the smallest operation.
     */
    boolean isPowered();

    /** Who owns the network: the controller's owner, or for a network without one, its first owned device's. */
    OwnerRecord owner();

    /**
     * Whether {@code player} may use the network at all, i.e. at least see its contents. Without a security module in
     * the controller's cluster this is Lib's {@code OwnerSecurity} on {@link #owner()}, and access is all or nothing.
     * With one, the module's rules give each player one of five levels: none, view (open terminals and see the
     * contents), deposit (also put in), craft (also request crafting jobs) and full (also take out and configure
     * devices); this method answers true from view upwards, so a caller that moves resources or changes settings needs
     * the level check of the device it goes through. Server thread; never throws for an unknown player (they get the
     * rules' level for everyone else).
     */
    boolean canAccess(Player player);

    /** What the network holds. */
    StorageContents contents();

    /**
     * MaxTech energy (MU, Lib's {@code lib.api.energy}) the network has for operations now; FE before Storage 0.7, the
     * same number at Lib's default FE rate of 1:1.
     */
    long storedEnergy();

    /** Most MU the network can hold; 0 while it has nowhere to keep energy (two controllers). */
    long energyCapacity();
}
