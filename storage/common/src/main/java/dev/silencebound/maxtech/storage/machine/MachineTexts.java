package dev.silencebound.maxtech.storage.machine;

/** Lang keys of the machine GUI, declared by {@link MachineContent} and drawn by the client screen. */
public final class MachineTexts {
    public static final String ENERGY_KEY = "gui.maxtech_storage.machine.energy";
    public static final String PROGRESS_KEY = "gui.maxtech_storage.machine.progress";
    public static final String MODE_KEY = "gui.maxtech_storage.machine.mode";
    public static final String EXPORT_KEY = "gui.maxtech_storage.machine.export";
    public static final String EXPORT_TOOLTIP_KEY = "gui.maxtech_storage.machine.export_tooltip";
    public static final String EXPORT_DONE_KEY = "message.maxtech_storage.machine.export.exported";
    public static final String EXPORT_NO_RECIPE_KEY = "message.maxtech_storage.machine.export.no_recipe";
    public static final String EXPORT_NO_BLANK_KEY = "message.maxtech_storage.machine.export.no_blank";
    public static final String EXPORT_DENIED_KEY = "message.maxtech_storage.machine.export.denied";

    /** The overlay message key of an export result. */
    public static String exportKey(MachineExport.Result result) {
        return switch (result) {
            case EXPORTED -> EXPORT_DONE_KEY;
            case NO_RECIPE -> EXPORT_NO_RECIPE_KEY;
            case NO_BLANK -> EXPORT_NO_BLANK_KEY;
            case DENIED -> EXPORT_DENIED_KEY;
        };
    }

    private MachineTexts() {
    }
}
