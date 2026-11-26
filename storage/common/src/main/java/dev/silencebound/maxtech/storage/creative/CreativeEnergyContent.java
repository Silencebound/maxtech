package dev.silencebound.maxtech.storage.creative;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/** The creative energy source: block, item and block entity type. Creative tab only, no recipe. */
public final class CreativeEnergyContent {
    public static final BlockEntry<CreativeEnergyBlock, BlockItem> CREATIVE_ENERGY_SOURCE = ModContent.CONTENT
            .block("creative_energy_source", CreativeEnergyBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)
                    .lightLevel(state -> 8))
            .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/creative_energy_source")))
            .lang("Creative Energy Source").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).creativeTab()
            .register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<CreativeEnergyBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("creative_energy_source",
                    () -> new BlockEntityType<>(CreativeEnergyBlockEntity::new, Set.of(CREATIVE_ENERGY_SOURCE.get())));

    public static final String PUSH_ON_KEY = "message." + MaxtechStorage.MOD_ID + ".creative_energy.push_on";
    public static final String PUSH_OFF_KEY = "message." + MaxtechStorage.MOD_ID + ".creative_energy.push_off";

    static {
        ModContent.CONTENT.translation(PUSH_ON_KEY, "Pushing energy into adjacent blocks");
        ModContent.CONTENT.translation(PUSH_OFF_KEY, "Not pushing energy");
    }

    private CreativeEnergyContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
