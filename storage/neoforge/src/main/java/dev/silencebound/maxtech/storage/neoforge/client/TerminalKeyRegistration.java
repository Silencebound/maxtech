package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.client.TerminalKeys;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/** Registers the terminal's keys; they only act in a terminal screen, so they only conflict with GUI keys. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class TerminalKeyRegistration {
    private TerminalKeyRegistration() {
    }

    @SubscribeEvent
    static void registerKeys(RegisterKeyMappingsEvent event) {
        TerminalKeys.DEPOSIT.setKeyConflictContext(KeyConflictContext.GUI);
        TerminalKeys.RESTOCK.setKeyConflictContext(KeyConflictContext.GUI);
        TerminalKeys.LOCATE.setKeyConflictContext(KeyConflictContext.GUI);
        event.registerCategory(TerminalKeys.CATEGORY);
        event.register(TerminalKeys.DEPOSIT);
        event.register(TerminalKeys.RESTOCK);
        event.register(TerminalKeys.LOCATE);
    }
}
