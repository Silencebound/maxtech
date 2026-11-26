package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModCreativeTabs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Lists Lib's facades at the end of Storage's tab; Lib has no tab of its own. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class FacadeTabContents {
    private FacadeTabContents() {
    }

    @SubscribeEvent
    static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(ModCreativeTabs.STORAGE.key())) {
            return;
        }
        for (ItemStack stack : FacadeContent.tabStacks()) {
            event.accept(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
