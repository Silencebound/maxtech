package dev.silencebound.maxtech.farms.registry;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Farms' creative tab (PRD 8.2: one tab per mod), the only tab that lists Farms' items. Specimens are left out: a
 * specimen without data is no real item.
 */
public final class FarmsCreativeTab {
    public static final String TITLE_KEY = "itemGroup." + MaxtechFarms.MOD_ID;

    private static final Registrar<CreativeModeTab> TABS = Registrars.create(MaxtechFarms.MOD_ID,
            Registries.CREATIVE_MODE_TAB);

    public static final RegistryEntry<CreativeModeTab, CreativeModeTab> FARMS = TABS.register("farms",
            () -> CreativeModeTab.builder().title(Component.translatable(TITLE_KEY))
                    .icon(() -> new ItemStack(FarmsContent.LAB_HUB_ITEM.get())).displayItems((parameters, output) -> {
                        output.accept(FarmsContent.LAB_HUB_ITEM.get());
                        output.accept(FarmsContent.FLUID_PORT_ITEM.get());
                        output.accept(LabTubes.TUBE.item().get());
                        output.accept(FarmsContent.SAMPLER.get());
                    }).build());

    private FarmsCreativeTab() {
    }

    /** Loads the class so the tab registers during mod construction; called from {@link MaxtechFarms#init()}. */
    public static void init() {
    }
}
