package dev.silencebound.maxtech.storage.world;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.util.StringRepresentable;

/**
 * The world devices, as their settings GUI sees them: the modes each one switches between and whether it keeps a tool.
 * The menu's opening data names the kind, so the client knows the mode names without anything else.
 */
public enum WorldKind implements StringRepresentable {
    /** Mode: how many rows the chest shows (3 or 6). */
    NETWORK_CHEST("network_chest", ImmutableList.of("rows_3", "rows_6"), ImmutableList.of(), false, true),
    /** One mode: it pulls whenever item entities are in range. */
    COLLECTOR("collector", ImmutableList.of("always"), ImmutableList.of(), false, false),
    /**
     * Mode: one operation per redstone pulse, or one per cadence while it can. Action (B09 item 25): place blocks, or
     * drop a stack or a single item in front of it.
     */
    PLACER("placer_port", ImmutableList.of("pulse", "continuous"),
            ImmutableList.of(PlacerAction.PLACE, PlacerAction.DROP_STACK, PlacerAction.DROP_ITEM), false, false),
    /** Modes as the placer's; keeps a tool or enchanted book for silk touch and fortune. */
    BREAKER("breaker_port", ImmutableList.of("pulse", "continuous"), ImmutableList.of(), true, false);

    public static final Codec<WorldKind> CODEC = StringRepresentable.fromEnum(WorldKind::values);

    private final String name;
    private final ImmutableList<String> modes;
    private final ImmutableList<String> actions;
    private final boolean tool;
    private final boolean contents;

    WorldKind(String name, ImmutableList<String> modes, ImmutableList<String> actions, boolean tool, boolean contents) {
        this.name = name;
        this.modes = modes;
        this.actions = actions;
        this.tool = tool;
        this.contents = contents;
    }

    /** Whether the settings GUI has a button that opens what the device holds ({@link WorldDevice#openContents}). */
    public boolean hasContents() {
        return contents;
    }

    /** How many modes the device switches between; at least 1. */
    public int modeCount() {
        return modes.size();
    }

    /** How many actions the device switches between (B09 item 25); 1 for a kind with one way of working. */
    public int actionCount() {
        return Math.max(1, actions.size());
    }

    /**
     * The fixed id that settings save for action {@code action} (clamped to the known actions); {@code ""} for a kind
     * with one way of working.
     */
    public String actionId(int action) {
        return actions.isEmpty() ? "" : actions.get(Math.floorMod(action, actions.size()));
    }

    /** The action saved as {@code id}; the first one for an unknown or blank id. */
    public int actionIndex(String id) {
        return Math.max(0, actions.indexOf(id));
    }

    /** The lang key of action {@code action}'s name, clamped to the known actions. */
    public String actionKey(int action) {
        return "gui." + MaxtechStorage.MOD_ID + ".world.action." + actionId(action);
    }

    /** The lang key of the one-line explanation of action {@code action}. */
    public String actionHintKey(int action) {
        return actionKey(action) + ".hint";
    }

    /** Whether the modes are a redstone-style behaviour setting (pulse or continuous) shown as a side button. */
    public boolean redstoneModes() {
        return this == PLACER || this == BREAKER;
    }

    /** The lang key of the one-line explanation of mode {@code mode}, clamped to the known modes. */
    public String modeHintKey(int mode) {
        return modeKey(mode) + ".hint";
    }

    /** The lang key of mode {@code mode}'s name, clamped to the known modes. */
    public String modeKey(int mode) {
        return "gui." + MaxtechStorage.MOD_ID + ".world.mode." + modes.get(Math.floorMod(mode, modes.size()));
    }

    /** Whether the device keeps a tool in a slot of its GUI. */
    public boolean hasTool() {
        return tool;
    }

    /** The kind with {@code id} as sent by {@link #id()}, or the first one for an unknown id. */
    public static WorldKind byId(int id) {
        WorldKind[] kinds = values();
        return id >= 0 && id < kinds.length ? kinds[id] : kinds[0];
    }

    /** The kind's id for the menu's opening data. */
    public int id() {
        WorldKind[] kinds = values();
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == this) {
                return i;
            }
        }
        throw new IllegalStateException("Unknown world kind " + this);
    }

    /** The device's registry name, which is also its research unlock's path. */
    @Override
    public String getSerializedName() {
        return name;
    }
}
