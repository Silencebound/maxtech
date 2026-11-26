package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.sensor.reader.PackReaderListener;
import dev.silencebound.maxtech.storage.sensor.reader.PackReaders;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Loader glue for the sensor's pack readers: their files load as a server reload listener (server start and
 * {@code /reload}), and sensors look their readers up again when the server's tags are bound anew, since a reader may
 * name blocks by tag.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class SensorReaderSetup {
    private SensorReaderSetup() {
    }

    @SubscribeEvent
    static void addReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(PackReaderListener.ID, new PackReaderListener());
    }

    @SubscribeEvent
    static void tagsUpdated(TagsUpdatedEvent.ServerDataLoad event) {
        PackReaders.tagsChanged();
    }
}
