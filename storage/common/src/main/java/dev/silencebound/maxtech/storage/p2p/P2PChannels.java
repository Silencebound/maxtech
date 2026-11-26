package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The loaded P2P ends of each level by frequency: one input and any number of outputs per frequency. Ends register when
 * they start (placed or loaded) and when their link changes, and leave when they are removed; an end that was removed
 * without leaving (a part on an unloaded cable) is skipped and dropped the next time its channel is walked. Nothing is
 * saved: the ends save their own links and register again when they load, which is how a link pairs again after a chunk
 * comes back. Server thread.
 */
final class P2PChannels {
    private static final Reference2ObjectOpenHashMap<ServerLevel, Long2ObjectOpenHashMap<Channel>> LEVELS = new Reference2ObjectOpenHashMap<>();

    private P2PChannels() {
    }

    /** Forgets every level when the server stops; called once during mod construction. */
    static void init() {
        Platform.get().lifecycle().onServerStopped(server -> LEVELS.clear());
    }

    /** One frequency's ends. */
    static final class Channel {
        @Nullable
        P2PEndpoint input;
        final ObjectArrayList<P2PEndpoint> outputs = new ObjectArrayList<>(2);
        /** The output the next transfer tries first, so a stream of single items spreads over the outputs. */
        int next;
        /** Set while a transfer runs: an output feeding back into this channel's input gets nothing. */
        boolean busy;
        /** Set while the outputs refresh their signals; a refresh asked for meanwhile runs once more afterwards. */
        boolean refreshing;
        boolean refreshAgain;

        /** The input, or null when it is gone or there is none. */
        @Nullable
        P2PEndpoint input() {
            P2PEndpoint current = input;
            if (current != null && current.isRemoved()) {
                input = null;
                return null;
            }
            return current;
        }

        /** Drops outputs that were removed without leaving. */
        void purge() {
            for (int i = outputs.size() - 1; i >= 0; i--) {
                if (outputs.get(i).isRemoved()) {
                    outputs.remove(i);
                }
            }
        }
    }

    /** The channel of {@code frequency} in {@code level}, or null when no end of it is loaded. */
    static @Nullable Channel get(ServerLevel level, long frequency) {
        Long2ObjectOpenHashMap<Channel> channels = LEVELS.get(level);
        return channels == null ? null : channels.get(frequency);
    }

    /**
     * Adds {@code end} under its link. An input takes the frequency only while no other loaded input holds it; returns
     * false for an input refused that way.
     */
    @SuppressWarnings("ReferenceEquality") // ends are compared by identity
    static boolean add(ServerLevel level, P2PEndpoint end) {
        Long2ObjectOpenHashMap<Channel> channels = LEVELS.get(level);
        if (channels == null) {
            channels = new Long2ObjectOpenHashMap<>();
            LEVELS.put(level, channels);
        }
        Channel channel = channels.get(end.frequency());
        if (channel == null) {
            channel = new Channel();
            channels.put(end.frequency(), channel);
        }
        if (end.role() == P2PRole.INPUT) {
            P2PEndpoint current = channel.input();
            if (current != null && current != end) {
                return false;
            }
            channel.input = end;
            refreshOutputs(channel);
            return true;
        }
        if (!channel.outputs.contains(end)) {
            channel.outputs.add(end);
        }
        return true;
    }

    /** Takes {@code end} out of the channel of {@code frequency}; the outputs of a removed input go quiet. */
    @SuppressWarnings("ReferenceEquality") // as in add
    static void remove(ServerLevel level, P2PEndpoint end, long frequency) {
        Channel channel = get(level, frequency);
        if (channel == null) {
            return;
        }
        if (channel.input == end) {
            channel.input = null;
            refreshOutputs(channel);
        }
        channel.outputs.remove(end);
        if (channel.input == null && channel.outputs.isEmpty()) {
            Long2ObjectOpenHashMap<Channel> channels = LEVELS.get(level);
            if (channels != null) {
                channels.remove(frequency);
            }
        }
    }

    /** Every output of {@code channel} works its redstone signal out again. */
    static void refreshOutputs(Channel channel) {
        if (channel.refreshing) {
            // An output's neighbour update reached this channel's input again: finish the walk first.
            channel.refreshAgain = true;
            return;
        }
        channel.refreshing = true;
        try {
            // Bounded: a redstone loop through the link settles after one round, so a few rounds always suffice.
            for (int round = 0; round < 4; round++) {
                channel.refreshAgain = false;
                channel.purge();
                for (int i = 0; i < channel.outputs.size(); i++) {
                    channel.outputs.get(i).refreshSignal();
                }
                if (!channel.refreshAgain) {
                    break;
                }
            }
        } finally {
            channel.refreshing = false;
        }
    }
}
