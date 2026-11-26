package dev.silencebound.maxtech.lib.registry;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    /**
     * Shared by every conduit block of every mod (ADR-0012). Block entity types register after all mods' blocks, so the
     * registry already holds every conduit block when this is built.
     */
    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ConduitBlockEntity>> CONDUIT = BLOCK_ENTITIES
            .register("conduit", () -> {
                Set<Block> conduits = new HashSet<>(ConduitBlock.all());
                return new BlockEntityType<>(ConduitBlockEntity::new, conduits);
            });

    private ModBlockEntities() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
