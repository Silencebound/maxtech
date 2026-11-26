package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.network.NetworkState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * A research terminal in either form, the block or the part on a cable: it opens the research screen while its network
 * is online and powered and its owner lets the player in. Server thread.
 */
public interface ResearchStation extends ChannelFree {
    /** The network the terminal is in, or null. */
    @Nullable
    NetworkState stationNetwork();

    /** Where the terminal is, for the menu's opening data. */
    BlockPos stationPos();

    /** Whether the terminal is still there and {@code player} within reach of it. */
    boolean inReach(Player player);

    /** Whether the network works: online and powered. */
    default boolean networkWorks() {
        NetworkState network = stationNetwork();
        return network != null && network.status() == StorageNetwork.Status.ONLINE && network.isPowered();
    }

    /** Whether the network's owner lets {@code player} in; a terminal without a network lets nobody use it. */
    default boolean canAccess(Player player) {
        NetworkState network = stationNetwork();
        // View is enough on purpose: research goes to the player's own team, never takes from the network.
        return network != null && network.canAccess(player);
    }

    /** Whether an open screen stays open for {@code player}: in reach and still allowed. */
    default boolean usable(Player player) {
        return inReach(player) && canAccess(player);
    }

    /** Opens the screen for {@code player}, or says why not in the action bar. */
    static void use(ServerPlayer player, ResearchStation station, Component title) {
        if (!station.networkWorks()) {
            player.sendOverlayMessage(Component.translatable(ResearchTerminalBlock.NO_POWER_KEY));
        } else if (!station.canAccess(player)) {
            player.sendOverlayMessage(Component.translatable(ResearchTerminalBlock.DENIED_KEY));
        } else {
            ResearchMenu.open(player, station, title);
        }
    }
}
