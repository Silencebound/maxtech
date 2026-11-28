package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.profile.ProfileCommand;
import java.util.function.BiConsumer;

/** The {@code /maxtech_lib profile} command. */
final class ProfileLang {
    private ProfileLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(ProfileCommand.STARTED_KEY,
                "Profiling Lib's scheduler and networks for %s s; the report follows. /maxtech_lib profile stop ends it"
                        + " early.");
        lang.accept(ProfileCommand.RUNNING_KEY, "A Lib profile is running already; /maxtech_lib profile stop ends it");
        lang.accept(ProfileCommand.NOT_RUNNING_KEY, "No Lib profile is running");
        lang.accept(ProfileCommand.STOPPED_KEY, "Stopped the Lib profile started by %s; its report follows");
        lang.accept(ProfileCommand.HEADER_KEY,
                "Lib profile over %s ticks: scheduler %s µs/tick, networks %s µs/tick (%s of a tick), %s task runs");
        lang.accept(ProfileCommand.EMPTY_KEY, "Nothing ran on Lib's scheduler or networks while profiling");
        lang.accept(ProfileCommand.OWNERS_KEY, "Scheduler by mod:");
        lang.accept(ProfileCommand.OWNER_ROW_KEY, "%s: %s µs/tick, %s runs");
        lang.accept(ProfileCommand.KINDS_KEY, "Most expensive:");
        lang.accept(ProfileCommand.KIND_ROW_KEY, "%s. %s (%s, %s): %s µs/tick, %s runs");
        lang.accept(ProfileCommand.KIND_TASK_KEY, "task");
        lang.accept(ProfileCommand.KIND_NETWORK_KEY, "network type");
        lang.accept(ProfileCommand.NETWORKS_KEY, "Largest of %s networks:");
        lang.accept(ProfileCommand.NETWORK_ROW_KEY, "%s. %s, %s nodes, %s %s: %s µs/tick");
    }
}
