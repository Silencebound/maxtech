package dev.silencebound.maxtech.storage.port;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * A port's tier (PRD 7.2, Devices): its base rates ({@code ports.tier<n>.*}) and the research unlock its owner's team
 * needs before it works. Rates are read from config each time, so a reload applies at once.
 */
public enum PortTier implements StringRepresentable {
    TIER_1, TIER_2, TIER_3;

    public static final Codec<PortTier> CODEC = StringRepresentable.fromEnum(PortTier::values);

    /** 1, 2 or 3. */
    public int number() {
        return switch (this) {
            case TIER_1 -> 1;
            case TIER_2 -> 2;
            case TIER_3 -> 3;
        };
    }

    /** The registry name of the block form; the part's item adds {@code _part}. */
    public String blockName() {
        return "storage_port_tier_" + number();
    }

    /** Items per second without speed cards. */
    public int itemsPerSecond() {
        return switch (this) {
            case TIER_1 -> StorageServerConfig.PORT_TIER_1_ITEMS.getAsInt();
            case TIER_2 -> StorageServerConfig.PORT_TIER_2_ITEMS.getAsInt();
            case TIER_3 -> StorageServerConfig.PORT_TIER_3_ITEMS.getAsInt();
        };
    }

    /** mB of fluid per second without speed cards. */
    public int fluidMbPerSecond() {
        return switch (this) {
            case TIER_1 -> StorageServerConfig.PORT_TIER_1_FLUID_MB.getAsInt();
            case TIER_2 -> StorageServerConfig.PORT_TIER_2_FLUID_MB.getAsInt();
            case TIER_3 -> StorageServerConfig.PORT_TIER_3_FLUID_MB.getAsInt();
        };
    }

    /** The unlock the owner's team needs before the port works, or null for none (tier 1). */
    public @Nullable Identifier research() {
        return switch (this) {
            case TIER_1 -> null;
            case TIER_2 -> ResearchGates.PORT_TIER_2;
            case TIER_3 -> ResearchGates.PORT_TIER_3;
        };
    }

    /** The tier numbered {@code number}, clamped to the tiers there are. */
    public static PortTier of(int number) {
        return number <= 1 ? TIER_1 : number == 2 ? TIER_2 : TIER_3;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
