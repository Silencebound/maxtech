package dev.silencebound.maxtech.storage.crafting.cpu;

import dev.silencebound.maxtech.storage.research.ResearchGates;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * The three crafting CPU tiers on the storage ladder (M7 C4): tantalite, deep data crystal, void crystal. The tier is
 * the core's; it sets the caps in {@link CpuStats}. Tiers 2 and 3 are research-gated by the Crafting branch.
 */
public enum CpuTier implements StringRepresentable {
    TIER_1(1), TIER_2(2), TIER_3(3);

    /**
     * The unlock that placing a tier-2 core needs; the Crafting research branch lists it.
     */
    public static final Identifier TIER_2_UNLOCK = ResearchGates.CPU_TIER_2;
    /** The unlock that placing a tier-3 core needs; see {@link #TIER_2_UNLOCK}. */
    public static final Identifier TIER_3_UNLOCK = ResearchGates.CPU_TIER_3;

    private final int level;

    CpuTier(int level) {
        this.level = level;
    }

    /** 1 to 3. */
    public int level() {
        return level;
    }

    /** The research unlock placing a core of this tier needs; null for tier 1, which is open. */
    public @Nullable Identifier unlock() {
        return switch (this) {
            case TIER_1 -> null;
            case TIER_2 -> TIER_2_UNLOCK;
            case TIER_3 -> TIER_3_UNLOCK;
        };
    }

    @Override
    public String getSerializedName() {
        return "tier_" + level;
    }
}
