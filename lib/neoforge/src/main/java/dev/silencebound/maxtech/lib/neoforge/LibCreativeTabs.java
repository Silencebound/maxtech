package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.content.CreativeTabAdditions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Lists the items addons asked for in creative tabs they do not own ({@link CreativeTabAdditions}). */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
final class LibCreativeTabs {
    private LibCreativeTabs() {
    }

    @SubscribeEvent
    static void build(BuildCreativeModeTabContentsEvent event) {
        for (CreativeTabAdditions.Addition addition : CreativeTabAdditions.all()) {
            if (addition.tab().equals(event.getTabKey())) {
                event.accept(addition.item().get());
            }
        }
    }
}
