package dev.silencebound.maxtech.storage.storagechest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.terminal.TerminalStatus;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.lib.save.VersionedBlockEntity;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the chest's slots are a NeoForge item handler, so hoppers and pipes use them on every side.
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The storage chest (tier 0, PRD 7.2): {@value #SLOTS} plain slots shown through the terminal GUI, so its contents can
 * be searched and sorted without a network. It keeps a per-chest total for each key, updated from every committed slot
 * change (hopper, pipe or player alike), and hands those changes to open menus as they happen; keys are released only
 * while no menu is open, so an id a client knows never names another item. Never ticks. Drops with its contents.
 *
 * <p>
 * Not a network device yet; a later step can mount it as a {@code StorageProvider} when a network touches it.
 */
public final class StorageChestBlockEntity extends VersionedBlockEntity implements TerminalSource {
    /**
     * Slots in a chest: a double chest's worth. Not config: a smaller value would cut slots off chests already holding
     * items.
     */
    public static final int SLOTS = 54;
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    private static final double RANGE_BUFFER = 4.0;
    private static final String ITEMS = "items";
    private static final String USED_SLOTS = "used_slots";

    private final Slots items = new Slots();
    /** The view filter strip (#322), saved with the chest and kept in its item when it is broken. */
    private final ViewFilterSlots viewFilters = new ViewFilterSlots(this::setChanged);
    private KeyRegistry<StackKey> keys = KeyRegistry.stackKeys();
    private final ObjectArrayList<TerminalMenu> menus = new ObjectArrayList<>(1);
    private static final Direction[] SIDES = Direction.values();
    /** Slots holding something, kept with every committed slot change. */
    private int used;

    public StorageChestBlockEntity(BlockPos pos, BlockState state) {
        this(StorageChestContent.BLOCK_ENTITY.get(), pos, state);
    }

    private StorageChestBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The slots, for the item capability on every side. */
    public ItemStacksResourceHandler items() {
        return items;
    }

    /** Slots holding something. */
    public int usedSlots() {
        return used;
    }

    /** Whether every slot holds something: the chest has run out of slots (M7L: logic's "drive or cell full"). */
    public boolean isFull() {
        return used >= SLOTS;
    }

    /**
     * The chest turned full or stopped being full: tells the ports facing it, which count it for logic's "drive or cell
     * full" (a storage chest is no network device). Only neighbours in loaded chunks; on events only.
     */
    private void fullChanged(boolean full) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        for (Direction side : SIDES) {
            BlockPos at = worldPosition.relative(side);
            if (!server.isLoaded(at)) {
                continue;
            }
            Direction back = side.getOpposite();
            if (server.getBlockEntity(at) instanceof PortBlockEntity port) {
                if (port.facing() == back) {
                    port.worker().facedChestChanged(full);
                }
            } else {
                CableParts parts = StorageCableBlock.parts(server, at);
                if (parts != null && parts.part(back) instanceof PortDevice port) {
                    port.worker().facedChestChanged(full);
                }
            }
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the chest is in a loaded chunk.
    @Override
    public void onLoad() {
        super.onLoad();
        if (isFull()) {
            fullChanged(true);
        }
    }

    /** Broken or replaced (never on chunk unload): ports facing a full chest stop counting it. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (isFull()) {
            fullChanged(false);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public boolean canAccess(Player player) {
        return true;
    }

    @Override
    public Int2LongMap contents() {
        Int2LongOpenHashMap contents = new Int2LongOpenHashMap();
        for (int id = 0; id < keys.idLimit(); id++) {
            long total = keys.total(id);
            if (total > 0) {
                contents.put(id, total);
            }
        }
        return contents;
    }

    @Override
    public @Nullable StackKey key(int id) {
        return keys.key(id);
    }

    @Override
    public long amount(int id) {
        return keys.total(id);
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        if (!(key instanceof ItemKey item) || amount <= 0) {
            return 0;
        }
        return ResourceHandlerUtil.insertStacking(items, StackKeys.toResource(item),
                (int) Math.min(Integer.MAX_VALUE, amount), transaction);
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        if (!(key instanceof ItemKey item) || amount <= 0) {
            return 0;
        }
        return items.extract(StackKeys.toResource(item), (int) Math.min(Integer.MAX_VALUE, amount), transaction);
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
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // as in watch
    public void unwatch(TerminalMenu menu) {
        menus.removeIf(open -> open == menu);
        if (menus.isEmpty()) {
            keys.releaseUnused();
        }
    }

    @Override
    public int status(int field) {
        return switch (field) {
            case TerminalMenu.STATUS -> TerminalStatus.LOCAL.index();
            case TerminalMenu.USED_SLOTS -> usedSlots();
            case TerminalMenu.SLOTS -> SLOTS;
            default -> 0;
        };
    }

    /** A slot changed (committed): its old stack leaves the totals, its new one joins, and open menus hear of both. */
    private void slotChanged(ItemStack previous, ItemStack current) {
        setChanged();
        if (previous.isEmpty() != current.isEmpty()) {
            boolean wasFull = isFull();
            used += current.isEmpty() ? -1 : 1;
            if (isFull() != wasFull) {
                fullChanged(!wasFull);
            }
        }
        if (!previous.isEmpty()) {
            int id = keys.id(ItemKey.of(previous));
            if (id >= 0) {
                report(id, keys.add(id, -Math.min(previous.getCount(), keys.total(id))));
            }
        }
        if (!current.isEmpty()) {
            int id = keys.intern(ItemKey.of(current));
            report(id, keys.add(id, current.getCount()));
        }
        if (menus.isEmpty()) {
            keys.releaseUnused();
        }
    }

    private void report(int id, long total) {
        for (int i = 0; i < menus.size(); i++) {
            menus.get(i).changed(id, total);
        }
    }

    /** Rebuilds the totals from the slots, after loading; open menus get a fresh dump. */
    private void recount() {
        keys = KeyRegistry.stackKeys();
        boolean wasFull = isFull();
        used = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.stack(i);
            if (!stack.isEmpty()) {
                int id = keys.intern(ItemKey.of(stack));
                keys.add(id, stack.getCount());
                used++;
            }
        }
        if (isFull() != wasFull) {
            // A placed chest item with its contents; on a chunk load the level is not set yet and onLoad tells.
            fullChanged(!wasFull);
        }
        for (int i = 0; i < menus.size(); i++) {
            menus.get(i).reset();
        }
    }

    /** True while the chest holds nothing and no view filter, so its item needs no data. */
    boolean isEmpty() {
        return ResourceHandlerUtil.isEmpty(items) && viewFilters.isEmpty();
    }

    /** The chest's view filter strip; its filters go with the chest's item when it is broken. */
    @Override
    public ViewFilterSlots viewFilters() {
        return viewFilters;
    }

    /** The chest as an item, with its contents when it holds any. */
    public ItemStack toItemStack(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        if (!isEmpty()) {
            stack.set(DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        }
        return stack;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        items.serialize(output.child(ITEMS));
        viewFilters.save(output);
        // Read by the item's tooltip only, which cannot decode the stacks without registries.
        output.putInt(USED_SLOTS, usedSlots());
    }

    @Override
    protected final SaveFormat saveFormat() {
        return StorageSaveFormats.BLOCK_ENTITIES;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        items.deserialize(input.childOrEmpty(ITEMS));
        viewFilters.load(input);
        recount();
    }

    /** The chest's slots; reports every committed change to the chest. */
    private final class Slots extends ItemStacksResourceHandler {
        Slots() {
            super(SLOTS);
        }

        ItemStack stack(int index) {
            return stacks.get(index);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            slotChanged(previousContents, stacks.get(index));
        }
    }
}
