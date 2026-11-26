package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.terminal.filter.CarriedViewFilters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The cell workbench's rules (PRD 7.2, the cell builder): fitting and removing a cell's controller chip, memory modules
 * and add-ons. Every operation takes the stack in the workbench (an empty casing or a cell) and returns the stack that
 * replaces it, or {@code null} when it is refused, changing nothing. A change is refused when the cell's contents would
 * no longer fit (bytes, or a key the new chip does not take), when the casing has no slot for the part, for a second
 * add-on of a kind, and for a cell whose contents are in a drive now (a copied item). Nothing is ever deleted: a
 * refused change leaves part and cell as they were, and a removed part is handed back by the caller.
 *
 * <p>
 * The chip goes in first and comes out last: fitting it turns a casing into its cell, and taking it from a cell with no
 * other parts and no contents turns the cell back into its casing. Server thread (reads {@link CellStorage}); the
 * client's menu only shows what the server decided.
 */
public final class CellAssembly {
    /** Chip item per cell type; items are registered before any cell is built, so the map never goes stale. */
    private static final Map<Identifier, Item> CHIPS = new ConcurrentHashMap<>();

    private CellAssembly() {
    }

    /** The casing of {@code stack}, an empty casing or a cell; {@code null} for anything else. */
    public static @Nullable CellCasing casing(ItemStack stack) {
        if (stack.getItem() instanceof StorageCellItem cell) {
            return cell.casing();
        }
        return stack.getItem() instanceof CellCasingItem casing ? casing.casing() : null;
    }

    /** Whether {@code stack} may be changed: a casing, or a cell whose contents are not in a drive now. */
    public static boolean editable(ItemStack stack, CellStorage storage) {
        if (stack.getItem() instanceof CellCasingItem) {
            return true;
        }
        CellData data = StorageCellItem.data(stack);
        return data != null && !(data.hasId() && storage.isMounted(data.id()));
    }

    /**
     * A stack of the chip that gives cells {@code type}; empty when no registered chip does. The workbench menu asks
     * for it on every slot sync, so the registry is walked once per type.
     */
    public static ItemStack chip(Identifier type) {
        return new ItemStack(CHIPS.computeIfAbsent(type, CellAssembly::findChip));
    }

    private static Item findChip(Identifier type) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof ControllerChipItem chip && chip.type().equals(type)) {
                return chip;
            }
        }
        return Items.AIR;
    }

    /** Fits {@code chip}, or swaps it for the cell's chip, which the caller hands back. */
    public static @Nullable ItemStack setChip(ItemStack stack, ControllerChipItem chip, CellStorage storage) {
        CellCasing casing = casing(stack);
        if (casing == null || !editable(stack, storage)) {
            return null;
        }
        CellData data = StorageCellItem.data(stack);
        if (stack.getItem() instanceof CellCasingItem || data == null) {
            ItemStack cell = new ItemStack(CellContent.cellItem(casing));
            cell.set(ModDataComponents.CELL.get(), CellData.of(chip.type(), List.of()));
            return cell;
        }
        return refit(stack, casing, data.withParts(chip.type(), data.memory(), data.addons()), storage);
    }

    /**
     * Takes the chip out of a cell without other parts and contents, giving its casing back; the caller hands back the
     * chip ({@link #chip(Identifier)} of the cell's type).
     */
    public static @Nullable ItemStack removeChip(ItemStack stack, CellStorage storage) {
        CellCasing casing = casing(stack);
        CellData data = StorageCellItem.data(stack);
        if (casing == null || data == null || !editable(stack, storage) || !data.memory().isEmpty()
                || !data.addons().isEmpty() || (data.hasId() && storage.kinds(data.id()) > 0)
                // A portable cell's view filters (#322) would be lost with the cell item.
                || !CarriedViewFilters.of(stack).isEmpty()) {
            return null;
        }
        return new ItemStack(CellContent.casingItem(casing));
    }

    /**
     * Puts {@code module} in memory slot {@code slot}: replacing the module there, or after the last one for a free
     * slot. The caller hands back a replaced module.
     */
    public static @Nullable ItemStack putMemory(ItemStack stack, int slot, MemoryModuleItem module,
            CellStorage storage) {
        CellData data = StorageCellItem.data(stack);
        CellCasing casing = casing(stack);
        if (data == null || casing == null || slot < 0 || slot >= casing.memorySlots()) {
            return null;
        }
        return refit(stack, casing, data.withParts(data.type(), put(data.memory(), slot, module), data.addons()),
                storage);
    }

    /** Takes the module out of memory slot {@code slot}; later modules move up a slot. */
    public static @Nullable ItemStack takeMemory(ItemStack stack, int slot, CellStorage storage) {
        CellData data = StorageCellItem.data(stack);
        CellCasing casing = casing(stack);
        if (data == null || casing == null || slot < 0 || slot >= data.memory().size()) {
            return null;
        }
        return refit(stack, casing, data.withParts(data.type(), take(data.memory(), slot), data.addons()), storage);
    }

    /**
     * Puts {@code addon} in add-on slot {@code slot}, as {@link #putMemory} does for modules.
     */
    public static @Nullable ItemStack putAddon(ItemStack stack, int slot, CellAddonItem addon, CellStorage storage) {
        CellData data = StorageCellItem.data(stack);
        CellCasing casing = casing(stack);
        if (data == null || casing == null || slot < 0 || slot >= casing.addonSlots()) {
            return null;
        }
        List<Item> addons = put(data.addons(), slot, addon);
        for (int i = 0; i < addons.size(); i++) {
            for (int j = i + 1; j < addons.size(); j++) {
                if (addons.get(i) instanceof CellAddonItem a && addons.get(j) instanceof CellAddonItem b
                        && a.addon() == b.addon()) {
                    return null;
                }
            }
        }
        return refit(stack, casing, data.withParts(data.type(), data.memory(), addons), storage);
    }

    /** Takes the add-on out of slot {@code slot}; the cell keeps its filter for when one comes back. */
    public static @Nullable ItemStack takeAddon(ItemStack stack, int slot, CellStorage storage) {
        CellData data = StorageCellItem.data(stack);
        CellCasing casing = casing(stack);
        if (data == null || casing == null || slot < 0 || slot >= data.addons().size()) {
            return null;
        }
        return refit(stack, casing, data.withParts(data.type(), data.memory(), take(data.addons(), slot)), storage);
    }

    /** The cell with another filter; refused only for a cell in use elsewhere. */
    public static @Nullable ItemStack setFilter(ItemStack stack, CellFilter filter, CellStorage storage) {
        CellData data = StorageCellItem.data(stack);
        return data == null || !editable(stack, storage) ? null : with(stack, data.withFilter(filter));
    }

    /**
     * {@code stack} with {@code data} if its contents fit: every key they hold is one the new type takes, and their
     * bytes are within the new capacity (or the change does not lower it). The cached stats are brought up to date for
     * the tooltip.
     */
    private static @Nullable ItemStack refit(ItemStack stack, CellCasing casing, CellData data, CellStorage storage) {
        if (!editable(stack, storage)) {
            return null;
        }
        CellType type = CellTypes.byId(data.type());
        if (type == null) {
            return null;
        }
        long capacity = data.capacity(casing);
        long used = data.hasId() ? storage.requiredBytes(data.id(), type) : 0;
        CellData before = StorageCellItem.data(stack);
        // A cell already over its bytes (after a config change) may still take parts that do not shrink it.
        boolean shrinks = before == null || !before.type().equals(data.type()) || capacity < before.capacity(casing);
        if (used > capacity && shrinks) {
            return null;
        }
        int kinds = data.hasId() ? storage.kinds(data.id()) : 0;
        int kept = data.hasId() ? storage.keptKinds(data.id()) : 0;
        return with(stack, data.withStats(used, capacity, kinds, kept));
    }

    private static ItemStack with(ItemStack stack, CellData data) {
        ItemStack result = stack.copy();
        result.set(ModDataComponents.CELL.get(), data);
        return result;
    }

    private static List<Item> put(List<Item> parts, int slot, Item part) {
        List<Item> result = new ArrayList<>(parts);
        if (slot < result.size()) {
            result.set(slot, part);
        } else {
            result.add(part);
        }
        return result;
    }

    private static List<Item> take(List<Item> parts, int slot) {
        List<Item> result = new ArrayList<>(parts);
        result.remove(slot);
        return result;
    }
}
