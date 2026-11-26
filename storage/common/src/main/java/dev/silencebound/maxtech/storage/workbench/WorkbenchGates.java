package dev.silencebound.maxtech.storage.workbench;

import dev.silencebound.maxtech.storage.cell.CellAddonItem;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellCasingItem;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The cell workbench's one research check point (PRD 7.2, Research): which unlock a part needs before it may go into a
 * workbench. Casings count whether they come empty or as a cell. Parts that need nothing (the small casing, chips,
 * memory module I) return {@code null}. Authoritative on the server; the client's answer is always true.
 */
public final class WorkbenchGates {
    private WorkbenchGates() {
    }

    /** Whether {@code player} may put {@code stack} into a workbench. */
    public static boolean allowed(Player player, ItemStack stack) {
        Identifier unlock = unlock(stack);
        return unlock == null || ResearchUnlocks.isUnlocked(player, unlock);
    }

    /** The unlock {@code stack}'s item needs; {@code null} for none. */
    public static @Nullable Identifier unlock(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof CellCasingItem casing) {
            return unlock(casing.casing());
        }
        if (item instanceof StorageCellItem cell) {
            return unlock(cell.casing());
        }
        if (item instanceof CellAddonItem addon) {
            return switch (addon.addon()) {
                case PARTITION -> ResearchGates.PARTITION_ADDON;
                case VOID_OVERFLOW -> ResearchGates.VOID_ADDON;
                case READ_ONLY -> ResearchGates.LOCK_ADDON;
            };
        }
        if (stack.is(CellContent.MEMORY_MODULE_2.get())) {
            return ResearchGates.MEMORY_MODULE_2;
        }
        if (stack.is(CellContent.MEMORY_MODULE_3.get())) {
            return ResearchGates.MEMORY_MODULE_3;
        }
        if (stack.is(CellContent.MEMORY_MODULE_4.get())) {
            return ResearchGates.MEMORY_MODULE_4;
        }
        if (stack.is(CellContent.MEMORY_MODULE_5.get())) {
            return ResearchGates.MEMORY_MODULE_5;
        }
        return stack.is(CellContent.MEMORY_MODULE_6.get()) ? ResearchGates.MEMORY_MODULE_6 : null;
    }

    private static @Nullable Identifier unlock(CellCasing casing) {
        return switch (casing) {
            case SMALL -> null;
            case MEDIUM -> ResearchGates.MEDIUM_CASING;
            case LARGE -> ResearchGates.LARGE_CASING;
            case BULK -> ResearchGates.BULK_CASING;
            case PORTABLE -> ResearchGates.PORTABLE_CASING;
        };
    }
}
