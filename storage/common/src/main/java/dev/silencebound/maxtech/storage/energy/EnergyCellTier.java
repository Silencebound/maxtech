package dev.silencebound.maxtech.storage.energy;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * The three energy cell tiers. Capacity and transfer limit come from the server config ({@code energy.cells.*}); they
 * are read in methods rather than kept in fields, since config suppliers are not immutable.
 */
public enum EnergyCellTier implements StringRepresentable {
    TIER_1(1), TIER_2(2), TIER_3(3);

    public static final Codec<EnergyCellTier> CODEC = StringRepresentable.fromEnum(EnergyCellTier::values);

    private final int level;

    EnergyCellTier(int level) {
        this.level = level;
    }

    /** 1, 2 or 3. */
    public int level() {
        return level;
    }

    /** FE a cell of this tier holds; the config default while no server config is loaded (a client's tooltip). */
    public long capacity() {
        return StorageServerConfig.loadedOr(switch (this) {
            case TIER_1 -> StorageServerConfig.ENERGY_CELL_CAPACITY_TIER_1;
            case TIER_2 -> StorageServerConfig.ENERGY_CELL_CAPACITY_TIER_2;
            case TIER_3 -> StorageServerConfig.ENERGY_CELL_CAPACITY_TIER_3;
        }, 0);
    }

    /** FE one transfer into or out of a cell of this tier may move. */
    public int maxTransfer() {
        return (int) StorageServerConfig.loadedOr(() -> switch (this) {
            case TIER_1 -> StorageServerConfig.ENERGY_CELL_TRANSFER_TIER_1.getAsInt();
            case TIER_2 -> StorageServerConfig.ENERGY_CELL_TRANSFER_TIER_2.getAsInt();
            case TIER_3 -> StorageServerConfig.ENERGY_CELL_TRANSFER_TIER_3.getAsInt();
        }, 0);
    }

    /** The research unlock a player needs to place a cell of this tier; {@code null} for none. */
    public @Nullable Identifier unlock() {
        return switch (this) {
            case TIER_1 -> null;
            case TIER_2 -> ResearchGates.ENERGY_CELL_TIER_2;
            case TIER_3 -> ResearchGates.ENERGY_CELL_TIER_3;
        };
    }

    @Override
    public String getSerializedName() {
        return "tier_" + level;
    }
}
