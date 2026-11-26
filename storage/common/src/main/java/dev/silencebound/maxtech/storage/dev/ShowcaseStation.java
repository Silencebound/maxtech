package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import java.util.Locale;

/**
 * One pad of the showcase. The English texts live here so the language provider and the builder share them; the three
 * sign hints are short enough to fit a sign.
 */
public enum ShowcaseStation {
    CORE, PRIORITY, PORTS, CABLES, CHANNELS, SUBNET, BRIDGES, MACHINES, ENERGY, CRAFTING, TOOLS, LOGIC;

    /** The command argument and lang key part. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String nameKey() {
        return key("name");
    }

    /** Sign line {@code line} (1 to 3). */
    public String hintKey(int line) {
        return key("hint" + line);
    }

    public String summaryKey() {
        return key("summary");
    }

    /** The English name, as the step wall shows it. */
    public String english() {
        return switch (this) {
            case CORE -> "Core network";
            case PRIORITY -> "Priority";
            case PORTS -> "Ports, devices";
            case CABLES -> "Cables";
            case CHANNELS -> "Channels, hubs";
            case SUBNET -> "Subnet, antennas";
            case BRIDGES -> "AE2 and RS bridges";
            case MACHINES -> "Machines";
            case ENERGY -> "Energy";
            case CRAFTING -> "Autocrafting";
            case TOOLS -> "Tools";
            case LOGIC -> "Logic";
        };
    }

    /** The sign's three hint lines. */
    List<String> hints() {
        return switch (this) {
            case CORE -> List.of("Open the terminal", "Drives: 4 cell tiers", "Fluid cell in drive 2");
            case PRIORITY -> List.of("Hopper > port > net", "Highest priority", "with room fills first");
            case PORTS -> List.of("Hopper feeds P2P", "Lamp: >64 cobble", "Wall graphs cobble");
            case CABLES -> List.of("Rows: plain covered", "smart glass fiber", "Tiers 1-4 left>right");
            case CHANNELS -> List.of("Front: full cable", "Middle: hubs", "Back: no controller");
            case SUBNET -> List.of("Child net: right", "Antennas paired", "Chest: wireless term");
            case BRIDGES -> List.of("AE2 grid: west", "RS network: east", "Both in the terminal");
            case MACHINES -> List.of("Generator burns", "coal, feeds all", "three around it");
            case ENERGY -> List.of("Generator feeds", "controller, then", "cells; chest: tools");
            case CRAFTING -> List.of("Request sticks or", "iron ingots in the", "terminal");
            case TOOLS -> List.of("Chests: every tool", "Uplink, belt, tablet", "bound to this net");
            case LOGIC -> List.of("Edit the 8 graphs", "Levers and button", "drive them");
        };
    }

    String summary() {
        return switch (this) {
            case CORE -> "A controller of two blocks with upgrade cards, a security module on the cable, a tier-1"
                    + " energy cell, two drives (item cells of four tiers, a fluid cell), terminal, pattern terminal,"
                    + " network chest and a storage chest.";
            case PORTS -> "Block port and port part importing from chests, interface stocking cobblestone, item P2P"
                    + " pair fed by a hopper, condition switch on a lamp, display wall graphing cobblestone, placer,"
                    + " breaker and collector on the cable.";
            case PRIORITY -> "An import port fed by a hopper and a chest, a drive at priority 2147483647, a bulk drive"
                    + " at 0 and a drive at -100: new items go to the highest priority with room, and a kind already"
                    + " stored goes where it is first.";
            case CABLES -> "Every cable look in tiers 1 to 4, a red cable and a cable under a stone brick facade.";
            case CHANNELS -> "A tier-1 cable (8 channels) with ten terminals on it, so the two placed last are offline"
                    + " and name the full cable; hubs of tiers 3, 2 and 1 (64, 32 and 16 channels) nested on one"
                    + " branch, each with three terminals; a second controller in the same network on standby; and at"
                    + " the back a network without a controller, which holds 8 channels in all.";
            case BRIDGES -> "A Storage network with a bridge to a powered AE2 grid (west) and one to a Refined Storage"
                    + " network (east), each stocked with items of its own, so each side lists the other's items. A"
                    + " mod that is not loaded gets a sign naming its dev-run flag (-PwithAe2=true, -PwithRs=true).";
            case SUBNET -> "Subnet controller in merged mode with its own drive and terminal, a paired antenna pair"
                    + " with a terminal on the far end, and a bound, charged wireless terminal in the chest.";
            case MACHINES -> "Fuel generator with coal feeding a lithograph, fabricator and assembler, each with a"
                    + " full buffer and inputs for one recipe.";
            case ENERGY -> "A network run on MU from a fuel generator alone, no creative source: the generator uses no"
                    + " channel (nor do the cells) and fills the controller's buffer first, then energy cells of tiers 1"
                    + " to 3 take what is left. A terminal and a drive on the cable, and a chest with a signal probe"
                    + " and a network analyzer for the readouts. Another mod's FE block next to a cell would feed the"
                    + " network at Lib's FE rate.";
            case CRAFTING -> "CPU clusters of tiers 1 to 3, a pattern provider on a furnace (raw iron to ingot) and"
                    + " one on a crafting assembler (planks to sticks), a stock keeper keeping 32 sticks and an export"
                    + " port with a crafting card that smelts iron ingots into its chest.";
            case TOOLS -> "Chests with every Storage tool (bound and charged where that matters), bulk drive and void"
                    + " items; a bulk drive on the network.";
            case LOGIC -> "Logic Controller with eight ready graphs: one per action, one on a sensor crossing and one"
                    + " with a counter and latch. Labelled ports, drive and condition switches in logic mode, a sensor"
                    + " block on a chest, a sensor part on a lever and a display wall showing logic notices. The lever"
                    + " on the controller turns the intake port on and sets the drive's priority, the button on the"
                    + " outbox port moves 16 cobblestone out, three flips of the lever above the cable light the latch"
                    + " lamp, and sticks are crafted whenever fewer than 16 are stored.";
        };
    }

    private String key(String path) {
        return "showcase." + MaxtechStorage.MOD_ID + "." + id() + "." + path;
    }
}
