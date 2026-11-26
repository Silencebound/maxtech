package dev.silencebound.maxtech.storage.cable;

/**
 * The looks a storage cable comes in (PRD 7.2, Network; M6d). The look is drawing only: every look of a tier carries
 * the same channels and links to every other storage cable by Lib's colour rule.
 */
public enum CableLook {
    /** A clear glass sleeve with one lane in the cable's colour, brighter per tier. */
    PLAIN("", ""),
    /** An opaque sleeve in the cable's colour; the lane is hidden. */
    COVERED("covered_", "Covered "),
    /** The plain look whose lane's colour and brightness show how busy its route segment is ({@link SmartLoad}). */
    SMART("smart_", "Smart "),
    /**
     * Drawn exactly like {@link #PLAIN} since look "J" (owner decision 2026-10-15). Kept registered so placed cables
     * keep working, but without recipes and left out of creative tabs, recipe viewers and the guide ({@link #listed}).
     */
    GLASS("glass_", "Glass "),
    /** The plain look with a brighter dot of light on its lane every 8 px. */
    FIBER("fiber_", "Fiber ");

    private final String prefix;
    private final String english;

    CableLook(String prefix, String english) {
        this.prefix = prefix;
        this.english = english;
    }

    /** Whether cables of this look are crafted and listed; only the retired {@link #GLASS} is not. */
    public boolean listed() {
        return this != GLASS;
    }

    /** The registry name of the cable of this look and {@code tier}, e.g. {@code smart_storage_cable_tier_3}. */
    public String name(int tier) {
        return prefix + "storage_cable_tier_" + tier;
    }

    /** The English name of the cable of this look and {@code tier}. */
    public String english(int tier) {
        return english + "Storage Cable (Tier " + tier + ")";
    }
}
