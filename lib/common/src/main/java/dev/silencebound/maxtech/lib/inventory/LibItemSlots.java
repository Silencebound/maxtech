package dev.silencebound.maxtech.lib.inventory;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// Lib's slots are NeoForge item handlers, so transactions, pipes and hoppers use them directly (ADR-0002).
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Lib's {@link ItemSlots}: NeoForge's journaled stack list with per-slot filters and limits. As a handler it is the
 * machine's own view (roles not applied); {@link #automation()} and {@link #player()} are the views with roles.
 * <p>
 * Saves list each non-empty slot as {@code {slot, item}} under {@code items}, so loading decodes entry by entry: an
 * entry whose item does not decode keeps its saved {@code item} tag as it was, blocks its slot and is saved back
 * unchanged; an entry for a slot the inventory no longer has becomes overflow, saved under its old index.
 */
public final class LibItemSlots extends ItemStacksResourceHandler implements ItemSlots {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ITEMS = "items";
    private static final String SLOT = "slot";
    private static final String ITEM = "item";
    /** The index the change listener gets for a change to {@link #overflow}. */
    private static final int OVERFLOW = -1;

    private final SlotRole[] roles;
    private final @Nullable Predicate<ItemStack>[] filters;
    private final int[] limits;
    private final IntConsumer onChange;
    private final RoleView automation;
    private final RoleView player;
    /** Per slot, the saved item tag that did not load; null for a slot without one. */
    private final @Nullable Dynamic<?>[] unknown;
    private final List<ItemStack> overflow = new ArrayList<>();
    private final List<ItemStack> overflowView = Collections.unmodifiableList(overflow);
    /** The saved slot index of each {@link #overflow} stack. */
    private final IntArrayList overflowSlots = new IntArrayList();
    /** Saved entries for slots the inventory no longer has whose item did not load either. */
    private final List<Stray> strays = new ArrayList<>();
    /** The last resource a filter was asked about and its stack, so hoppers polling a slot allocate nothing. */
    private ItemResource filterResource = ItemResource.EMPTY;
    private ItemStack filterStack = ItemStack.EMPTY;

    @SuppressWarnings("unchecked") // a generic array of saved tags, only ever filled with them
    LibItemSlots(SlotRole[] roles, @Nullable Predicate<ItemStack>[] filters, int[] limits, IntConsumer onChange) {
        super(roles.length);
        this.roles = roles;
        this.filters = filters;
        this.limits = limits;
        this.onChange = onChange;
        this.unknown = new Dynamic[roles.length];
        this.automation = new RoleView(this, false);
        this.player = new RoleView(this, true);
    }

    /** A saved entry kept as it was: its slot index and item tag. */
    private record Stray(int slot, Dynamic<?> item) {
    }

    /** What pipes, hoppers and transfer ports see: inputs take, outputs give, display slots do neither. */
    public RoleView automation() {
        return automation;
    }

    /** What a player in a menu may do: like {@link #automation()}, but inputs can be taken back out too. */
    public RoleView player() {
        return player;
    }

    @Override
    public SlotRole role(int slot) {
        return roles[slot];
    }

    @Override
    public boolean accepts(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Predicate<ItemStack> filter = filters[slot];
        return filter == null || filter.test(stack);
    }

    @Override
    public int limit(int slot) {
        return limits[slot];
    }

    @Override
    public ItemStack stack(int slot) {
        return stacks.get(slot);
    }

    @Override
    public boolean unknown(int slot) {
        return unknown[slot] != null;
    }

    @Override
    public void discardUnknown(int slot) {
        NoTransaction.check("Discarding an unknown item");
        Dynamic<?> raw = unknown[slot];
        if (raw != null) {
            LOGGER.info("Discarded the unknown item {} from slot {}", raw, slot);
            unknown[slot] = null;
            onChange.accept(slot);
        }
    }

    @Override
    public List<ItemStack> overflow() {
        return overflowView;
    }

    @Override
    public ItemStack takeOverflow(int index) {
        NoTransaction.check("Taking overflow");
        overflowSlots.removeInt(index);
        ItemStack taken = overflow.remove(index);
        onChange.accept(OVERFLOW);
        return taken;
    }

    @Override
    public List<ItemStack> contentsToDrop() {
        NoTransaction.check("Taking the contents");
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                contents.add(stack);
                super.set(i, ItemResource.EMPTY, 0);
            }
        }
        if (!overflow.isEmpty()) {
            contents.addAll(overflow);
            overflow.clear();
            overflowSlots.clear();
            onChange.accept(OVERFLOW);
        }
        return contents;
    }

    @Override
    public void dropContents(Level level, BlockPos pos) {
        for (ItemStack stack : contentsToDrop()) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
        }
    }

    @Override
    public void set(int slot, ItemStack stack) {
        set(slot, ItemResource.of(stack), stack.getCount());
    }

    /** Replaces the slot's contents; refused inside a transaction and on a slot holding an unknown item. */
    @Override
    public void set(int index, ItemResource resource, int amount) {
        NoTransaction.check("Setting a slot");
        if (unknown[index] != null) {
            throw new IllegalStateException("Slot " + index + " holds an unknown item; discard it first");
        }
        super.set(index, resource, amount);
    }

    @Override
    public int insert(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int inserted = insert(slot, ItemResource.of(stack), stack.getCount(), transaction);
            if (!simulate) {
                transaction.commit();
            }
            return inserted;
        }
    }

    @Override
    public int extract(int slot, int amount, boolean simulate) {
        ItemResource resource = getResource(slot);
        if (resource.isEmpty() || amount <= 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = extract(slot, resource, amount, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return extracted;
        }
    }

    @Override
    public void save(ValueOutput output) {
        serialize(output);
    }

    @Override
    public void load(ValueInput input) {
        deserialize(input);
    }

    @Override
    public void serialize(ValueOutput output) {
        ValueOutput.ValueOutputList list = output.childrenList(ITEMS);
        for (int i = 0; i < stacks.size(); i++) {
            Dynamic<?> raw = unknown[i];
            if (raw != null) {
                saveRaw(list, i, raw);
            } else if (!stacks.get(i).isEmpty()) {
                ValueOutput entry = list.addChild();
                entry.putInt(SLOT, i);
                entry.store(ITEM, ItemStack.CODEC, stacks.get(i));
            }
        }
        for (int i = 0; i < overflow.size(); i++) {
            ValueOutput entry = list.addChild();
            entry.putInt(SLOT, overflowSlots.getInt(i));
            entry.store(ITEM, ItemStack.CODEC, overflow.get(i));
        }
        for (Stray stray : strays) {
            saveRaw(list, stray.slot(), stray.item());
        }
    }

    private static void saveRaw(ValueOutput.ValueOutputList list, int slot, Dynamic<?> raw) {
        ValueOutput entry = list.addChild();
        entry.putInt(SLOT, slot);
        entry.store(ITEM, Codec.PASSTHROUGH, raw);
    }

    /**
     * Replaces all contents with the saved ones, entry by entry: an item that does not decode stays as saved in its
     * slot, a stack for a slot the inventory no longer has (or a second one for a slot) goes to overflow. Logs once for
     * each of those. Does not call the change listener.
     */
    @Override
    public void deserialize(ValueInput input) {
        for (int i = 0; i < stacks.size(); i++) {
            stacks.set(i, ItemStack.EMPTY);
            unknown[i] = null;
        }
        overflow.clear();
        overflowSlots.clear();
        strays.clear();
        int unknownCount = 0;
        for (ValueInput entry : input.childrenListOrEmpty(ITEMS)) {
            int slot = entry.getIntOr(SLOT, -1);
            boolean fits = slot >= 0 && slot < stacks.size() && stacks.get(slot).isEmpty() && unknown[slot] == null;
            Optional<Dynamic<?>> raw = entry.read(ITEM, Codec.PASSTHROUGH);
            if (raw.isEmpty()) {
                continue;
            }
            // Only a full success: a partial result would silently drop what did not decode (a removed mod's
            // component on a vanilla item, say), so anything less stays unknown as saved.
            Optional<ItemStack> stack = ItemStack.CODEC.parse(raw.get()).result();
            if (stack.isPresent()) {
                if (fits) {
                    stacks.set(slot, stack.get());
                } else {
                    overflow.add(stack.get());
                    overflowSlots.add(slot);
                }
                continue;
            }
            unknownCount++;
            if (fits) {
                unknown[slot] = raw.get();
            } else {
                strays.add(new Stray(slot, raw.get()));
            }
        }
        if (unknownCount > 0) {
            LOGGER.warn("Kept {} saved item(s) that do not load as they were; they return with their mod",
                    unknownCount);
        }
        if (!overflow.isEmpty()) {
            LOGGER.warn("Kept {} saved stack(s) for slots the inventory of {} slots no longer has as overflow",
                    overflow.size(), stacks.size());
        }
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        if (unknown[index] != null) {
            return false;
        }
        Predicate<ItemStack> filter = filters[index];
        return filter == null || filter.test(filterStack(resource));
    }

    private ItemStack filterStack(ItemResource resource) {
        if (!resource.equals(filterResource)) {
            filterStack = resource.toStack(1);
            filterResource = resource;
        }
        return filterStack;
    }

    @Override
    protected int getCapacity(int index, ItemResource resource) {
        return unknown[index] != null ? 0 : Math.min(limits[index], super.getCapacity(index, resource));
    }

    @Override
    protected void onContentsChanged(int index, ItemStack previousContents) {
        onChange.accept(index);
    }
}
