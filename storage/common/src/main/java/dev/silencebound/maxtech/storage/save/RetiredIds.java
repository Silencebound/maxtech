package dev.silencebound.maxtech.storage.save;

import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Block and item ids that were retired and now name another entry. The loader adds each pair as a registry alias for
 * both blocks and items on every start, so a placed block of an old world loads as the new block (block state
 * properties the new block lacks are dropped, missing ones take their default), and an old item in an inventory, a
 * container or a storage cell loads as the new item with its count. Aliases are followed only while the old id is not
 * registered itself, so a retired entry must also be removed from the registries.
 */
public final class RetiredIds {
    /**
     * The controller capacitor bank folded into the energy cells (M7R item 5): it is a tier-1 energy cell now. Its FE
     * share moves from the controller into the cell on the controller's first fill
     * ({@link StorageSaveFormats#CAPACITOR_BANKS_TO_CELLS}).
     */
    public static final Identifier CAPACITOR_BANK = id("controller_capacitor_bank");

    /** Retired id to the id that replaces it, for blocks and items alike. */
    public static final Map<Identifier, Identifier> BLOCKS_AND_ITEMS = Map.of(CAPACITOR_BANK, id("energy_cell_tier_1"));

    private RetiredIds() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("maxtech_storage", path);
    }
}
