package dev.silencebound.maxtech.lib.registry;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class ModDataComponents {
    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /**
     * The block a facade item makes a conduit block look like. Synced so the tooltip and the item model show it.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<BlockState>> FACADE = COMPONENTS
            .register("facade", () -> DataComponentType.<BlockState>builder().persistent(BlockState.CODEC)
                    .networkSynchronized(ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)).build());

    /**
     * Energy stored in a charged item, such as Storage's energy cell. Synced so the charge bar and tooltips show it.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Long>> STORED_ENERGY = COMPONENTS
            .register("stored_energy", () -> DataComponentType.<Long>builder().persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG).build());

    /**
     * Who owns the block an item places, or the item itself (a wireless terminal); see {@code OwnerSecurity}. Synced so
     * tooltips show the owner.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<OwnerRecord>> OWNER = COMPONENTS
            .register("owner", () -> DataComponentType.<OwnerRecord>builder().persistent(OwnerRecord.CODEC)
                    .networkSynchronized(OwnerRecord.STREAM_CODEC).build());

    private ModDataComponents() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
