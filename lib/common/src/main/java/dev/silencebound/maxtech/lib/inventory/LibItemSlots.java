package dev.silencebound.maxtech.lib.inventory;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
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
 */
public final class LibItemSlots extends ItemStacksResourceHandler implements ItemSlots {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final SlotRole[] roles;
    private final @Nullable Predicate<ItemStack>[] filters;
    private final int[] limits;
    private final IntConsumer onChange;
    private final RoleView automation;
    private final RoleView player;

    LibItemSlots(SlotRole[] roles, @Nullable Predicate<ItemStack>[] filters, int[] limits, IntConsumer onChange) {
        super(roles.length);
        this.roles = roles;
        this.filters = filters;
        this.limits = limits;
        this.onChange = onChange;
        this.automation = new RoleView(this, false);
        this.player = new RoleView(this, true);
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
    public void set(int slot, ItemStack stack) {
        set(slot, ItemResource.of(stack), stack.getCount());
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

    /** Loads saved stacks; a save with another slot count keeps what fits and logs what it drops. */
    @Override
    public void deserialize(ValueInput input) {
        super.deserialize(input);
        if (stacks.size() != roles.length) {
            NonNullList<ItemStack> resized = NonNullList.withSize(roles.length, ItemStack.EMPTY);
            for (int i = 0; i < stacks.size(); i++) {
                if (i < roles.length) {
                    resized.set(i, stacks.get(i));
                } else if (!stacks.get(i).isEmpty()) {
                    LOGGER.warn("Dropped {} from saved slot {}: the inventory has {} slots now", stacks.get(i), i,
                            roles.length);
                }
            }
            setStacks(resized);
        }
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        Predicate<ItemStack> filter = filters[index];
        return filter == null || filter.test(resource.toStack(1));
    }

    @Override
    protected int getCapacity(int index, ItemResource resource) {
        return Math.min(limits[index], super.getCapacity(index, resource));
    }

    @Override
    protected void onContentsChanged(int index, ItemStack previousContents) {
        onChange.accept(index);
    }
}
