package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.util.StringRepresentable;

/** What a condition switch watches (PRD 7.2, Devices). */
public enum ConditionType implements StringRepresentable {
    /** The network's total of one item (with its components). */
    ITEM_COUNT("item_count"),
    /** The network's total of every item in one item tag. */
    TAG_COUNT("tag_count"),
    /** The network's stored energy, in percent of what it can hold. */
    POWER("power"),
    /** The busiest link on the switch's route to the controller, in percent of its channels in use. */
    OVERLOAD("overload"),
    /** Whether one research node is done for the network owner's team. */
    RESEARCH("research"),
    /**
     * Driven by logic (M7L): a graph's redstone-out action addressed by the switch's label sets the strength, 0 to 15;
     * the switch watches nothing itself.
     */
    LOGIC("logic"),
    /**
     * Channel to redstone (M7L2): gives the value (0 to 15) of one of the network's bundled redstone channels, the one
     * {@link ConditionSettings#channel()} names.
     */
    CHANNEL_OUT("channel_out"),
    /**
     * Redstone to channel (M7L2): the redstone strength coming into the switch is its share of the channel; it gives no
     * signal itself.
     */
    CHANNEL_IN("channel_in"),
    /**
     * While crafting (B09 item 14): on while a running crafting job of the network is making the item in the slot, as
     * the requested item or as a step on the way.
     */
    WHILE_CRAFTING("while_crafting"),
    /**
     * Request on signal (B09 item 14, AE2's emit to craft): the switch offers the item in the slot as craftable; when a
     * job needs it, the switch is on until the job's amount has arrived in the network or the job ends.
     */
    CRAFT_REQUEST("craft_request");

    private final String name;

    ConditionType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** The type after this one, wrapping round; what the GUI's button cycles through. */
    public ConditionType next() {
        return switch (this) {
            case ITEM_COUNT -> TAG_COUNT;
            case TAG_COUNT -> POWER;
            case POWER -> OVERLOAD;
            case OVERLOAD -> RESEARCH;
            case RESEARCH -> LOGIC;
            case LOGIC -> CHANNEL_OUT;
            case CHANNEL_OUT -> CHANNEL_IN;
            case CHANNEL_IN -> WHILE_CRAFTING;
            case WHILE_CRAFTING -> CRAFT_REQUEST;
            case CRAFT_REQUEST -> ITEM_COUNT;
        };
    }

    /** A stable number for the GUI's data slot. */
    public int id() {
        return switch (this) {
            case ITEM_COUNT -> 0;
            case TAG_COUNT -> 1;
            case POWER -> 2;
            case OVERLOAD -> 3;
            case RESEARCH -> 4;
            case LOGIC -> 5;
            case CHANNEL_OUT -> 6;
            case CHANNEL_IN -> 7;
            case WHILE_CRAFTING -> 8;
            case CRAFT_REQUEST -> 9;
        };
    }

    /** The type with {@link #id()} {@code id}; item count for an unknown one. */
    public static ConditionType byId(int id) {
        for (ConditionType type : values()) {
            if (type.id() == id) {
                return type;
            }
        }
        return ITEM_COUNT;
    }

    /** Whether the threshold is a percentage (0 to 100) rather than a count. */
    public boolean percent() {
        return this == POWER || this == OVERLOAD;
    }

    /** Whether the switch reads or writes one of the network's redstone channels. */
    public boolean channel() {
        return this == CHANNEL_OUT || this == CHANNEL_IN;
    }

    /** Whether the switch works with crafting jobs for the item in its slot. */
    public boolean crafting() {
        return this == WHILE_CRAFTING || this == CRAFT_REQUEST;
    }

    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".condition.type." + name;
    }

    /** The lang key of the type's one-line explanation, the type button's tooltip. */
    public String hintKey() {
        return translationKey() + ".hint";
    }
}
