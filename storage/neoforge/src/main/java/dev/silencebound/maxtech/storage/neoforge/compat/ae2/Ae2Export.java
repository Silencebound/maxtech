package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.bridge.ExportedStorage;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A bridge's Storage network as one storage of an AE2 grid: a global storage provider of the grid's storage service
 * that mounts itself, so AE2 terminals, buses and autocrafting see and use the network like a drive. AE2's
 * simulate/modulate calls go straight to {@link ExportedStorage}, which runs each in a root transaction of its own and
 * commits only on modulate; it reports nothing and accepts nothing while the loop guard is held, so a bridge reading
 * the grid never sees its own network through it.
 *
 * <p>
 * AE2 reads the contents ({@link #getAvailableStacks}) when it rebuilds its cached inventory, every tick while
 * something watches the grid (an open terminal, a level emitter), else on demand; so no change listener is installed.
 * Server thread only, like all of AE2's storage calls.
 */
final class Ae2Export implements MEStorage, IStorageProvider {
    private final ExportedStorage storage;
    private final Ae2Keys keys = new Ae2Keys();
    private final StorageProvider.Visitor collect = this::collect;
    private @Nullable KeyCounter target;

    Ae2Export(ExportedStorage storage) {
        this.storage = storage;
    }

    /** The Storage network's mount this exports. */
    ExportedStorage storage() {
        return storage;
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        mounts.mount(this);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return move(what, amount, mode, true);
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        return move(what, amount, mode, false);
    }

    private long move(AEKey what, long amount, Actionable mode, boolean in) {
        if (amount <= 0) {
            return 0;
        }
        StackKey key = keys.toStorage(what);
        if (key == null) {
            return 0;
        }
        boolean simulate = mode == Actionable.SIMULATE;
        long moved = in ? storage.insert(key, amount, simulate) : storage.extract(key, amount, simulate);
        return Math.clamp(moved, 0, amount);
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        target = out;
        try {
            storage.forEachStored(collect);
        } finally {
            target = null;
        }
    }

    private void collect(StackKey key, long amount) {
        KeyCounter out = target;
        AEKey what = keys.toAe(key);
        if (out != null && what != null) {
            out.add(what, amount);
        }
    }

    @Override
    public Component getDescription() {
        return Component.translatable(Ae2Lang.EXPORT);
    }
}
