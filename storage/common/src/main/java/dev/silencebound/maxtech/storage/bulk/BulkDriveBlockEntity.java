package dev.silencebound.maxtech.storage.bulk;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import dev.silencebound.maxtech.storage.save.KeptValue;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: hand inserts and takes run in NeoForge's transfer transaction, as network operations do.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The bulk drive (M6f area B): one block that is one {@link StorageProvider} of its network, holding a single item kind
 * with no byte cap ({@link BulkStore}). Its contents live here, as one key and one count, so its save and its item stay
 * the same size whatever it holds. Inserts reach it at its priority like a drive's cells; once it holds a kind, inserts
 * of that kind come to it first (the network asks holders before the priority list).
 *
 * <p>
 * Players with full access put the held stack in by using the block, and take a stack out by sneak-using it with an
 * empty hand; both are one root transaction through the store, so the network's index hears of them. It drops with its
 * contents and priority as item components (also when broken in creative mode or picked up with the storage wrench).
 * While its chunk is unloaded it counts as one offline cell of its network, like a drive. Never ticks.
 */
public final class BulkDriveBlockEntity extends StorageDeviceBlockEntity implements SettingsCopyable, Prioritized {
    /** Lowest and highest priority: the shared range ({@link PriorityControl}). */
    public static final int MIN_PRIORITY = PriorityControl.MIN;
    public static final int MAX_PRIORITY = PriorityControl.MAX;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONTENTS = "contents";
    private static final String PRIORITY = "priority";

    private final BulkStore store = new BulkStore(this::committed);
    private final List<StorageProvider> providers = List.of(store);
    /**
     * Saved contents that no longer load (an item of a removed mod), written back unchanged until the mod returns; the
     * drive takes nothing in meanwhile, and its item carries them in its block entity data.
     */
    private @Nullable Tag keptContents;
    /**
     * Whether the drive was full at the last commit, load or placing, to see it turn full or not (M7L). Server side
     * only: the cap is server config.
     */
    private boolean full;

    public BulkDriveBlockEntity(BlockPos pos, BlockState state) {
        super(BulkContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.STORAGE;
    }

    @Override
    public List<StorageProvider> providers() {
        return providers;
    }

    /** The store the network reads; for tests and tools. Server thread. */
    public BulkStore store() {
        return store;
    }

    /** What the drive holds; null while it is empty. */
    public @Nullable BulkContents contents() {
        ItemKey key = store.key();
        return key == null || store.count() <= 0 ? null : new BulkContents(key, store.count());
    }

    /**
     * Whether the drive has no room left: it holds {@code bulkDrive.maxCount} items or more. Counts as one full cell
     * for logic's "drive or cell full" (M7L). Server side.
     */
    public boolean isFull() {
        return full(store);
    }

    private static boolean full(BulkStore store) {
        return store.count() > 0 && store.count() >= StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong();
    }

    /**
     * A root transaction changed the contents: save, and when the drive turned full or stopped being full, the logic
     * graphs watching "drive or cell full" look again.
     */
    private void committed() {
        setChanged();
        fullMayHaveChanged();
    }

    private void fullMayHaveChanged() {
        boolean now = full(store);
        if (now != full) {
            full = now;
            NetworkState network = network();
            if (network != null) {
                network.fullCellsChanged();
            }
        }
    }

    @Override
    public int priority() {
        return store.priority();
    }

    /**
     * Sets the priority, clamped to {@link #MIN_PRIORITY}..{@link #MAX_PRIORITY}; the store leaves and rejoins its
     * network's index with it. Server side.
     */
    @Override
    public void setPriority(int priority) {
        int clamped = Math.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
        if (clamped != store.priority()) {
            store.setPriority(clamped);
            setChanged();
            providersChanged();
        }
    }

    /**
     * Puts as much of {@code stack} in as the lock and the cap allow, taking it from the stack; returns how many went
     * in. Server side.
     */
    public int insert(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = store.insert(ItemKey.of(stack), stack.getCount(), transaction);
            transaction.commit();
        }
        stack.shrink((int) moved);
        return (int) moved;
    }

    /** Takes out up to one stack of what the drive holds; empty when it holds nothing. Server side. */
    public ItemStack takeStack() {
        ItemKey key = store.key();
        if (key == null) {
            return ItemStack.EMPTY;
        }
        int max = key.toStack(1).getMaxStackSize();
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = store.extract(key, max, transaction);
            transaction.commit();
        }
        return moved <= 0 ? ItemStack.EMPTY : key.toStack((int) moved);
    }

    /** The blueprint card's kind for bulk drives. */
    public static final Identifier SETTINGS_KIND = DeviceSettings.kind("bulk_drive");

    /** The priority for the blueprint card; the lock and the items are contents and stay. */
    @Override
    public DeviceSettings copyableSettings() {
        return new DeviceSettings(SETTINGS_KIND, () -> {
            DataComponentMap.Builder components = DataComponentMap.builder();
            if (store.priority() != 0) {
                components.set(BulkContent.PRIORITY.get(), store.priority());
            }
            return components.build();
        }, components -> setPriority(components.getOrDefault(BulkContent.PRIORITY.get(), 0)));
    }

    /** The drive as an item, carrying its contents and priority. */
    public ItemStack toItemStack() {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        stack.applyComponents(collectComponents());
        return stack;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk.
    @Override
    public void onLoad() {
        if (level instanceof ServerLevel server) {
            full = full(store);
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            if (logic != null) {
                logic.driveOnline(worldPosition.asLong());
            }
        }
        super.onLoad();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs before setRemoved when the chunk unloads, never
    // when the drive is broken.
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        NetworkState network = network();
        if (network != null && store.count() > 0) {
            network.recordOffline(this, 1);
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        long max = StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong();
        NetworkInfo.fill(store.count(), max, sink);
        BulkContents contents = contents();
        if (contents != null) {
            NetworkInfo.stacks(List.of(new InfoStack(contents.key(), contents.count())), sink);
        }
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(BulkTexts.contents(contents()));
        sink.line(Component.translatable(BulkTexts.CAPACITY,
                InfoFormat.number(StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong())));
        sink.line(BulkTexts.priority(store.priority()));
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        BulkContents contents = contents();
        if (contents != null) {
            components.set(BulkContent.CONTENTS.get(), KeptValue.of(contents));
        }
        Tag kept = keptContents;
        if (kept != null) {
            CompoundTag data = new CompoundTag();
            data.put(CONTENTS, kept.copy());
            components.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(getType(), data));
        }
        if (store.priority() != 0) {
            components.set(BulkContent.PRIORITY.get(), store.priority());
        }
    }

    /**
     * A placed drive item: its contents and priority, and the network re-reads the drive if it already joined one.
     * Contents the item keeps unread (a removed mod's item) are kept by the drive too, which then takes nothing in.
     */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        KeptValue<BulkContents> carried = components.get(BulkContent.CONTENTS.get());
        BulkContents contents = carried == null ? null : carried.value();
        Tag kept = carried == null ? null : carried.kept();
        if (kept != null) {
            keptContents = kept.copy();
            store.setLocked(true);
        }
        store.load(contents == null ? null : contents.key(), contents == null ? 0 : contents.count());
        store.setPriority(
                Math.clamp(components.getOrDefault(BulkContent.PRIORITY.get(), 0), MIN_PRIORITY, MAX_PRIORITY));
        providersChanged();
        if (level instanceof ServerLevel) {
            fullMayHaveChanged();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        Tag kept = keptContents;
        if (kept != null) {
            output.store(CONTENTS, Codec.PASSTHROUGH, new Dynamic<>(NbtOps.INSTANCE, kept.copy()));
        } else {
            output.storeNullable(CONTENTS, BulkContents.CODEC, contents());
        }
        if (store.priority() != 0) {
            output.putInt(PRIORITY, store.priority());
        }
    }

    /**
     * Whether a drive item carries contents the drive kept unread: those travel in its block entity data (#288), not in
     * {@link BulkContent#CONTENTS}. For the tooltip; copies the data, so not for hot paths.
     */
    static boolean keptOnItem(ItemStack stack) {
        TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data != null && data.copyTagWithoutId().contains(CONTENTS);
    }

    /** Whether the drive holds anything, contents it keeps unread included. */
    boolean holdsAnything() {
        return contents() != null || keptContents != null;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        Dynamic<?> saved = input.read(CONTENTS, Codec.PASSTHROUGH).orElse(null);
        BulkContents contents = null;
        keptContents = null;
        if (saved != null) {
            DataResult<BulkContents> read = BulkContents.CODEC.parse(saved);
            if (read.isSuccess()) {
                contents = read.getOrThrow();
            } else {
                LOGGER.warn("Bulk drive at {} keeps contents it cannot read until their mod is back: {}", worldPosition,
                        read.error().map(DataResult.Error::message).orElse(""));
                keptContents = saved.convert(NbtOps.INSTANCE).getValue();
            }
        }
        store.setLocked(keptContents != null);
        store.load(contents == null ? null : contents.key(), contents == null ? 0 : contents.count());
        store.setPriority(Math.clamp(input.getIntOr(PRIORITY, 0), MIN_PRIORITY, MAX_PRIORITY));
        providersChanged();
    }
}
