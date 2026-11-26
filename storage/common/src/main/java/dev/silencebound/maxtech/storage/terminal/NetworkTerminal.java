package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Player;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What every network terminal shows and changes, whatever form it has (the block or the part on a cable): the network's
 * contents behind its {@link NetworkDevice}, read from the network's index, and player transfers through
 * {@link NetworkState#insert} and {@link NetworkState#extract} after the owner check. Its open menus watch the network
 * directly and move with the device when it joins another network, each getting a fresh dump. Server thread.
 */
public final class NetworkTerminal implements TerminalSource {
    private final NetworkDevice device;
    private final Predicate<Player> inReach;
    private final ObjectArrayList<TerminalMenu> menus = new ObjectArrayList<>(1);
    /** The network the open menus watch now. */
    private @Nullable NetworkState watched;

    /**
     * @param inReach
     *            whether a player is still close enough to the terminal and the terminal is still there
     */
    public NetworkTerminal(NetworkDevice device, Predicate<Player> inReach) {
        this.device = device;
        this.inReach = inReach;
    }

    @Override
    public @Nullable NetworkState network() {
        return device.deviceLink().network();
    }

    /** The device joined or left a network: open menus move with it. */
    public void networkChanged() {
        NetworkState previous = watched;
        NetworkState current = network();
        if (previous == current) {
            return;
        }
        for (int i = 0; i < menus.size(); i++) {
            TerminalMenu menu = menus.get(i);
            if (previous != null) {
                previous.unwatch(menu);
            }
            if (current != null) {
                current.watch(menu);
            }
            menu.reset();
        }
        watched = current;
    }

    @Override
    public boolean stillValid(Player player) {
        return inReach.test(player);
    }

    @Override
    public boolean canAccess(Player player) {
        NetworkState network = network();
        return network == null || network.canAccess(player);
    }

    @Override
    public boolean canDeposit(Player player) {
        NetworkState network = network();
        return network == null || network.accessLevel(player).atLeast(AccessLevel.DEPOSIT);
    }

    @Override
    public boolean canTake(Player player) {
        NetworkState network = network();
        return network == null || network.accessLevel(player) == AccessLevel.FULL;
    }

    @Override
    public Int2LongMap contents() {
        NetworkState network = network();
        return network == null ? Int2LongMaps.EMPTY_MAP : network.contents().snapshot().totals();
    }

    @Override
    public @Nullable StackKey key(int id) {
        NetworkState network = network();
        return network == null ? null : network.contents().key(id);
    }

    @Override
    public long amount(int id) {
        NetworkState network = network();
        return network == null ? 0 : network.contents().amount(id);
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        NetworkState network = network();
        return network == null ? 0 : network.insert(key, amount, transaction);
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        NetworkState network = network();
        return network == null ? 0 : network.extract(key, amount, transaction);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // an open menu is one object
    public void watch(TerminalMenu menu) {
        for (int i = 0; i < menus.size(); i++) {
            if (menus.get(i) == menu) {
                return;
            }
        }
        menus.add(menu);
        NetworkState network = network();
        watched = network;
        if (network != null) {
            network.watch(menu);
        }
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // as in watch
    public void unwatch(TerminalMenu menu) {
        menus.removeIf(open -> open == menu);
        NetworkState network = watched;
        if (network != null) {
            network.unwatch(menu);
        }
    }

    /** Menus open on this terminal. */
    public int openMenus() {
        return menus.size();
    }

    @Override
    public int status(int field) {
        NetworkState network = network();
        if (network == null) {
            return field == TerminalMenu.STATUS ? TerminalStatus.NO_NETWORK.index() : 0;
        }
        long pos = device.nodePos();
        return switch (field) {
            case TerminalMenu.STATUS -> statusOf(network).index();
            case TerminalMenu.ENERGY -> (int) Math.min(Integer.MAX_VALUE, network.storedEnergy());
            case TerminalMenu.CAPACITY -> (int) Math.min(Integer.MAX_VALUE, network.energyCapacity());
            case TerminalMenu.CHANNELS -> network.segmentCapacity(pos);
            case TerminalMenu.LOAD -> network.segmentLoad(pos);
            case TerminalMenu.KINDS -> network.contents().size();
            default -> 0;
        };
    }

    private static TerminalStatus statusOf(NetworkState network) {
        if (network.status() != StorageNetwork.Status.ONLINE) {
            return TerminalStatus.STOPPED;
        }
        return network.isPowered() ? TerminalStatus.ONLINE : TerminalStatus.NO_POWER;
    }
}
