package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.info.EnergyInfo;
import java.util.function.BiConsumer;

/** Energy info lines for the scanner, Jade and tooltips. */
final class EnergyInfoLang {
    private EnergyInfoLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(EnergyInfo.PRODUCING_KEY, "Producing: %s / %s MU/t");
        lang.accept(EnergyInfo.USING_KEY, "Using: %s / %s MU/t (%s%%)");
        lang.accept(EnergyInfo.STORED_KEY, "Stored: %s / %s MU");
        lang.accept(EnergyInfo.BAR_KEY, "%s%%");
        lang.accept(EnergyInfo.CHARGING_KEY, "Charging: %s MU/t");
        lang.accept(EnergyInfo.DISCHARGING_KEY, "Discharging: %s MU/t");
        lang.accept(EnergyInfo.NETWORK_KEY, "Energy network: %s MU/t supply, %s MU/t demand");
        lang.accept(EnergyInfo.BROWNOUT_KEY, "Brownout: machines get %s%% of their demand");
        lang.accept(EnergyInfo.SUPPLIED_KEY, "Supplied: %s%%");
        lang.accept(EnergyInfo.FULL_IN_KEY, "Full in %s");
        lang.accept(EnergyInfo.EMPTY_IN_KEY, "Empty in %s");
        lang.accept(EnergyInfo.NETWORK_STORED_KEY, "Network stored: %s / %s MU");
        lang.accept(EnergyInfo.BUFFERS_KEY, "Batteries: %s MU/t in, %s MU/t out");
        lang.accept(EnergyInfo.COST_KEY, "Pass cost: %s µs, %s passes, %s");
        lang.accept(EnergyInfo.ASLEEP_KEY, "asleep");
        lang.accept(EnergyInfo.ACTIVE_KEY, "active");
        lang.accept(EnergyInfo.PAUSED_KEY, "Paused: part of the network is in unloaded chunks");
        lang.accept(EnergyInfo.NO_NETWORK_KEY, "Not in an energy network");
        lang.accept(EnergyInfo.SECONDS_KEY, "%ss");
        lang.accept(EnergyInfo.MINUTES_KEY, "%sm %ss");
        lang.accept(EnergyInfo.HOURS_KEY, "%sh %sm");
        lang.accept(EnergyInfo.DAYS_KEY, "%sd %sh");
    }
}
