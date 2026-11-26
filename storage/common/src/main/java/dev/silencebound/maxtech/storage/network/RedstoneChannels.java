package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A network's 16 bundled redstone channels (M7L2 row 22), one per dye colour in {@code DyeColor} id order (0 white to
 * 15 black), each 0 to 15. A channel's value is the strongest of what its writers give, like wires joined together:
 * condition switches in "redstone to channel" mode and logic controllers. Each share keeps its writer's owner, so a
 * reader that gives redstone counts only the shares whose owners may configure it ({@link #value(int, ShareFilter)});
 * {@link #value(int)} is the network-wide value logic graphs and the analyzer read. Writers keep and save their own
 * share and hand it back when they join a network, so the values come back after a load, a split or a merge without the
 * network saving anything itself. Listeners hear a channel only when one of its shares changes; nothing ticks, and
 * reading allocates nothing. Server thread only.
 */
public final class RedstoneChannels {
    /** Channels per network: one per dye colour. */
    public static final int COUNT = 16;

    /** Told after a share of a channel changed, with its network-wide value. Must only mark and schedule work. */
    @FunctionalInterface
    public interface Listener {
        void channelChanged(int channel, int strength);
    }

    /** Which shares a reader counts: those whose writer's owner may configure it. */
    @FunctionalInterface
    public interface ShareFilter {
        boolean counts(Object writer, OwnerRecord owner);
    }

    private final int[] values = new int[COUNT];
    private final Shares[] shares = new Shares[COUNT];
    private final ObjectArrayList<Listener> listeners = new ObjectArrayList<>(0);
    private long changes;

    /** One channel's shares above 0, in parallel arrays; writers are few, so lookups are linear. */
    private static final class Shares {
        @Nullable
        Object[] writers = new Object[2];
        @Nullable
        OwnerRecord[] owners = new OwnerRecord[2];
        int[] strengths = new int[2];
        int count;

        // Writers are devices' workers, told apart by identity; equals would merge two equal-looking writers.
        @SuppressWarnings("ReferenceEquality")
        int indexOf(Object writer) {
            for (int i = 0; i < count; i++) {
                if (writers[i] == writer) {
                    return i;
                }
            }
            return -1;
        }

        void removeAt(int i) {
            count--;
            writers[i] = writers[count];
            owners[i] = owners[count];
            strengths[i] = strengths[count];
            writers[count] = null;
            owners[count] = null;
        }

        void add(Object writer, OwnerRecord owner, int strength) {
            if (count == writers.length) {
                writers = Arrays.copyOf(writers, count * 2);
                owners = Arrays.copyOf(owners, count * 2);
                strengths = Arrays.copyOf(strengths, count * 2);
            }
            writers[count] = writer;
            owners[count] = owner;
            strengths[count] = strength;
            count++;
        }
    }

    public RedstoneChannels() {
        for (int channel = 0; channel < COUNT; channel++) {
            shares[channel] = new Shares();
        }
    }

    /** The network-wide value of {@code channel} (0 to 15): the strongest share; 0 outside 0 to 15. */
    public int value(int channel) {
        return channel >= 0 && channel < COUNT ? values[channel] : 0;
    }

    /** The strongest share of {@code channel} that {@code filter} counts; 0 outside 0 to 15. Allocates nothing. */
    public int value(int channel, ShareFilter filter) {
        if (channel < 0 || channel >= COUNT) {
            return 0;
        }
        Shares list = shares[channel];
        int best = 0;
        for (int i = 0; i < list.count; i++) {
            OwnerRecord owner = list.owners[i];
            Object writer = list.writers[i];
            if (list.strengths[i] > best && owner != null && writer != null && filter.counts(writer, owner)) {
                best = list.strengths[i];
            }
        }
        return best;
    }

    /** Whether any channel is above 0. */
    public boolean any() {
        for (int value : values) {
            if (value > 0) {
                return true;
            }
        }
        return false;
    }

    /** How often a channel's value changed on this network; tests use it to see that equal writes change nothing. */
    public long changes() {
        return changes;
    }

    /**
     * Sets {@code writer}'s share of {@code channel} to {@code strength} (clamped to 0 to 15; 0 drops the share), made
     * by {@code owner}. The channel takes the strongest share; listeners are told when that or the share's owner
     * changed (readers filter by owner).
     */
    public void write(Object writer, OwnerRecord owner, int channel, int strength) {
        if (channel < 0 || channel >= COUNT) {
            return;
        }
        int next = Math.clamp(strength, 0, 15);
        Shares list = shares[channel];
        int at = list.indexOf(writer);
        if (at < 0) {
            if (next == 0) {
                return;
            }
            list.add(writer, owner, next);
        } else if (next == 0) {
            list.removeAt(at);
        } else {
            if (list.strengths[at] == next && owner.equals(list.owners[at])) {
                return;
            }
            list.strengths[at] = next;
            list.owners[at] = owner;
        }
        recompute(channel);
    }

    /** Drops every share {@code writer} has: it left the network. */
    public void remove(Object writer) {
        for (int channel = 0; channel < COUNT; channel++) {
            Shares list = shares[channel];
            int at = list.indexOf(writer);
            if (at >= 0) {
                list.removeAt(at);
                recompute(channel);
            }
        }
    }

    /**
     * A share of {@code channel} changed: the network-wide value follows, and listeners are told even when it stayed,
     * since a reader that counts only some owners' shares may see a different value now.
     */
    private void recompute(int channel) {
        Shares list = shares[channel];
        int best = 0;
        for (int i = 0; i < list.count; i++) {
            best = Math.max(best, list.strengths[i]);
        }
        if (best != values[channel]) {
            values[channel] = best;
            changes++;
        }
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).channelChanged(channel, best);
        }
    }

    /** Starts telling {@code listener} about channel changes; a second call for the same listener does nothing. */
    public void listen(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unlisten(Listener listener) {
        listeners.remove(listener);
    }
}
