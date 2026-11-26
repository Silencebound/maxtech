package dev.silencebound.maxtech.storage.crafting.cpu;

/** The blocks that fill a crafting CPU's caps (M7 C3); see {@link CpuStats}. */
public enum CpuPart {
    /** Job steps. */
    STORAGE("storage"),
    /** Lanes: steps waiting on machines at once. */
    LANE("lane"),
    /** Crafts per dispatch: intermediates kept hot in the CPU go out in larger batches. */
    CACHE("cache");

    private final String name;

    CpuPart(String name) {
        this.name = name;
    }

    /** The registry name's suffix. */
    public String partName() {
        return name;
    }
}
