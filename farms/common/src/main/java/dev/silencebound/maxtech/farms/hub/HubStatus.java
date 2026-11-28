package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.lib.api.menu.MenuStatus;

/** The status line of the lab hub's screen: the first state that applies, in declaration order. */
public enum HubStatus implements MenuStatus {
    /** Another hub runs the lab network beside this one. */
    REFUSED("refused", Tone.ERROR),
    /** No lab tube beside the hub. */
    NOT_CONNECTED("not_connected", Tone.WARN),
    /** The nutrient tank is empty. */
    TANK_EMPTY("tank_empty", Tone.WARN),
    /** The MU store is empty and no fuel burns. */
    NO_POWER("no_power", Tone.ERROR),
    /** None of the above. */
    RUNNING("running", Tone.OK);

    private final String key;
    private final Tone tone;

    HubStatus(String name, Tone tone) {
        this.key = "gui.maxtech_farms.lab_hub.status." + name;
        this.tone = tone;
    }

    /** The state {@code hub} is in. Server thread. */
    public static HubStatus of(LabHubBlockEntity hub) {
        return switch (hub.labLink()) {
            case REFUSED -> REFUSED;
            case NONE -> NOT_CONNECTED;
            case ACTIVE -> {
                if (hub.storage().tank().amount() <= 0) {
                    yield TANK_EMPTY;
                }
                yield hub.energy().stored() <= 0 && !hub.burner().isBurning() ? NO_POWER : RUNNING;
            }
        };
    }

    @Override
    public String translationKey() {
        return key;
    }

    @Override
    public Tone tone() {
        return tone;
    }
}
