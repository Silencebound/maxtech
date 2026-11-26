package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.util.StringRepresentable;

/**
 * Which of its filter's keys an export port tries first (B09 item 18, AE2's scheduling mode): in filter order every
 * operation starts at the first entry, so the first key the network holds gets the room; round robin starts each
 * operation after the key the last one moved, so every key gets its turn. Import and storage mode ignore it. Saved by
 * name.
 */
public enum PortOrder implements StringRepresentable {
    /** Every operation starts at the first entry. */
    FILTER("filter", 0),
    /** Every operation starts after the key the last one moved. */
    ROUND_ROBIN("round_robin", 1);

    private final String name;
    private final int id;

    PortOrder(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** The order's number in menu data slots; explicit, so a reordering never changes it. */
    public int id() {
        return id;
    }

    /** The order with {@link #id()} {@code id}; filter order for an unknown one. */
    public static PortOrder byId(int id) {
        for (PortOrder order : values()) {
            if (order.id == id) {
                return order;
            }
        }
        return FILTER;
    }

    /** The order saved as {@code name}; filter order for an unknown or missing one. */
    public static PortOrder byName(String name) {
        for (PortOrder order : values()) {
            if (order.name.equals(name)) {
                return order;
            }
        }
        return FILTER;
    }

    /** The other order; what the GUI button switches to. */
    public PortOrder next() {
        return this == FILTER ? ROUND_ROBIN : FILTER;
    }

    /** The lang key of the order's name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".port.order." + name;
    }

    /** The lang key of the order's one-line explanation. */
    public String hintKey() {
        return translationKey() + ".hint";
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
