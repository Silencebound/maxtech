package dev.silencebound.maxtech.storage.tool.carried;

import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.PortableCellItem;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** A gadget that opens by key from wherever the player carries it. */
public enum CarriedGadget {
    WIRELESS_TERMINAL(WirelessTerminalItem.class, "wireless_terminal"), CRAFTING_TABLET(CraftingTabletItem.class,
            "crafting_tablet"), LOGIC_REMOTE(LogicRemoteItem.class,
                    "logic_remote"), PORTABLE_CELL(PortableCellItem.class, "portable_cell");

    private final Class<? extends Item> item;
    private final String id;

    CarriedGadget(Class<? extends Item> item, String id) {
        this.item = item;
        this.id = id;
    }

    /** The gadget's item class; a lookup takes any item of it. */
    public Class<? extends Item> item() {
        return item;
    }

    /** A short lower-case id, used in lang keys. */
    public String id() {
        return id;
    }

    /** The item the picker shows for the gadget; any of its kind looks alike there. */
    public Item icon() {
        return switch (this) {
            case WIRELESS_TERMINAL -> SignalContent.WIRELESS_TERMINAL.get();
            case CRAFTING_TABLET -> CraftingTabletContent.TABLET.get();
            case LOGIC_REMOTE -> RemoteContent.REMOTE.get();
            case PORTABLE_CELL -> CellContent.PORTABLE_STORAGE_CELL.get();
        };
    }

    /** Opens the gadget in {@code slot} as using it from the hand does, or tells the player why not. */
    void open(ServerPlayer player, CarriedSlot slot) {
        switch (this) {
            case WIRELESS_TERMINAL -> WirelessTerminalItem.open(player, slot);
            case CRAFTING_TABLET -> CraftingTabletItem.open(player, slot);
            case LOGIC_REMOTE -> LogicRemoteItem.open(player, slot);
            case PORTABLE_CELL -> PortableCellItem.open(player, slot);
        }
    }

    /** The gadget's index in declaration order, as payloads carry it; {@link #byIndex} reads it back. */
    @SuppressWarnings("EnumOrdinal") // the wire index, read back by byIndex in the same order
    public int index() {
        return ordinal();
    }

    /** The gadget at {@code index} in declaration order, or null for an index out of range (a bad packet). */
    public static @Nullable CarriedGadget byIndex(int index) {
        CarriedGadget[] values = values();
        return index >= 0 && index < values.length ? values[index] : null;
    }
}
