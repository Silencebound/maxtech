package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.EachItems;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplateListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Loader glue for the logic rule templates (M7L2): their files load as a server reload listener, on server start and
 * {@code /reload}. Also forgets the tag items For each nodes went through (M7L2 row 18) when the server's tags are
 * bound anew.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class LogicTemplateSetup {
    private LogicTemplateSetup() {
    }

    @SubscribeEvent
    static void addReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(LogicTemplateListener.ID, new LogicTemplateListener());
    }

    @SubscribeEvent
    static void tagsUpdated(TagsUpdatedEvent.ServerDataLoad event) {
        EachItems.tagsChanged();
    }
}
