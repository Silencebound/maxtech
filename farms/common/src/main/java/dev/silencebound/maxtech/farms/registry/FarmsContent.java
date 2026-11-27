package dev.silencebound.maxtech.farms.registry;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.farms.specimen.Specimen;
import dev.silencebound.maxtech.farms.specimen.SpecimenItem;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import com.mojang.serialization.Codec;

/** Farms' items and data components. */
public final class FarmsContent {
    private static final Registrar<DataComponentType<?>> COMPONENTS = Registrars.create(MaxtechFarms.MOD_ID,
            Registries.DATA_COMPONENT_TYPE);
    private static final Registrar<Item> ITEMS = Registrars.create(MaxtechFarms.MOD_ID, Registries.ITEM);

    /** A specimen item's data. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Specimen>> SPECIMEN_DATA = COMPONENTS
            .register("specimen", () -> DataComponentType.<Specimen>builder().persistent(Specimen.CODEC)
                    .networkSynchronized(Specimen.STREAM_CODEC).build());

    /**
     * MU in a sampler. A sampler without it is fully charged, so a crafted one starts full; charging in the hand does
     * not replay the equip animation.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Long>> SAMPLER_CHARGE = COMPONENTS
            .register("sampler_charge", () -> DataComponentType.<Long>builder().persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG).ignoreSwapAnimation().build());

    public static final RegistryEntry<Item, SpecimenItem> SPECIMEN = ITEMS.register("specimen",
            (Identifier id) -> new SpecimenItem(properties(id).stacksTo(SpecimenItem.MAX_STACK)));
    public static final RegistryEntry<Item, SamplerItem> SAMPLER = ITEMS.register("sampler",
            (Identifier id) -> new SamplerItem(properties(id).stacksTo(1)));

    private FarmsContent() {
    }

    /** Loads the class so the registrars exist before registration; called from {@link MaxtechFarms#init()}. */
    public static void init() {
    }

    private static Item.Properties properties(Identifier id) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
    }
}
