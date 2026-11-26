package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.bridge.ExportedStorage;
import dev.silencebound.maxtech.storage.bridge.ForeignNetwork;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A foreign network for the bridge's GameTests, behaving like AE2's or RS's storage: simulate/modulate moves over its
 * own store and, when {@link #mountsExport} is set, over the exports bridges mounted, which it lists and prefers for
 * inserts and extracts as a higher-priority storage would be. Each bridge gets its own {@link #handle()} with its own
 * change signal and mount, as a real adapter's per-block object. Knobs make real moves fall short of the forecasts and
 * turn the change signal off; {@link #maxDepth} records how deep calls into it nested, to catch recursion.
 */
final class FakeForeignNetwork {
    final Object2LongOpenHashMap<StackKey> own = new Object2LongOpenHashMap<>();
    private final List<Handle> handles = new ArrayList<>();
    boolean events = true;
    boolean mountsExport;
    /** At most this much goes in per real insert; the forecast ignores it. Negative: no cap. */
    long modulateInsertCap = -1;
    /** At most this much comes out per real extract. Negative: no cap. */
    long modulateExtractCap = -1;
    /** Refuses every insert, forecast and real alike. */
    boolean refuseInserts;
    int depth;
    int maxDepth;

    /** A new object for one bridge, as a finder returns. */
    ForeignNetwork handle() {
        Handle handle = new Handle();
        handles.add(handle);
        return handle;
    }

    /** The exports mounted now. */
    List<ExportedStorage> mounts() {
        List<ExportedStorage> list = new ArrayList<>();
        for (Handle handle : handles) {
            if (handle.mount != null) {
                list.add(handle.mount);
            }
        }
        return list;
    }

    /** Sets {@code key} to {@code amount} behind the bridges' back and signals the change (if signals are on). */
    void setDirect(StackKey key, long amount) {
        if (amount <= 0) {
            own.removeLong(key);
        } else {
            own.put(key, amount);
        }
        signal();
    }

    private void signal() {
        if (!events) {
            return;
        }
        for (Handle handle : new ArrayList<>(handles)) {
            Runnable listener = handle.listener;
            if (listener != null) {
                listener.run();
            }
        }
    }

    private void enter() {
        depth++;
        maxDepth = Math.max(maxDepth, depth);
    }

    private final class Handle implements ForeignNetwork {
        private @Nullable Runnable listener;
        private @Nullable ExportedStorage mount;

        @Override
        public Object identity() {
            return FakeForeignNetwork.this;
        }

        @Override
        public Component name() {
            return Component.literal("Fake network");
        }

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public void forEachStored(StorageProvider.Visitor visitor) {
            enter();
            try {
                for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(own)) {
                    visitor.visit(entry.getKey(), entry.getLongValue());
                }
                if (mountsExport) {
                    for (ExportedStorage export : mounts()) {
                        export.forEachStored(visitor);
                    }
                }
            } finally {
                depth--;
            }
        }

        @Override
        public long insert(StackKey key, long amount, boolean simulate) {
            enter();
            try {
                if (refuseInserts) {
                    return 0;
                }
                long moved = 0;
                if (mountsExport) {
                    for (ExportedStorage export : mounts()) {
                        moved += export.insert(key, amount - moved, simulate);
                    }
                }
                long rest = amount - moved;
                if (!simulate && modulateInsertCap >= 0) {
                    rest = Math.min(rest, modulateInsertCap);
                }
                if (!simulate && rest > 0) {
                    own.addTo(key, rest);
                    signal();
                }
                return moved + rest;
            } finally {
                depth--;
            }
        }

        @Override
        public long extract(StackKey key, long amount, boolean simulate) {
            enter();
            try {
                long moved = 0;
                if (mountsExport) {
                    for (ExportedStorage export : mounts()) {
                        moved += export.extract(key, amount - moved, simulate);
                    }
                }
                long rest = Math.min(amount - moved, own.getLong(key));
                if (!simulate && modulateExtractCap >= 0) {
                    rest = Math.min(rest, modulateExtractCap);
                }
                if (!simulate && rest > 0) {
                    if (own.addTo(key, -rest) == rest) {
                        own.removeLong(key);
                    }
                    signal();
                }
                return moved + rest;
            } finally {
                depth--;
            }
        }

        @Override
        public boolean setChangeListener(@Nullable Runnable listener) {
            this.listener = listener;
            return events;
        }

        @Override
        public void export(@Nullable ExportedStorage storage) {
            mount = storage;
        }
    }
}
