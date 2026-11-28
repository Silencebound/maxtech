package dev.silencebound.maxtech.lib.menu;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.inventory.LibFluidTank;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.platform.Platform;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Base of Lib's toolkit menus: syncs the declared {@link MenuValues} to the client without data slots. The server menu
 * (built with its host) reads every getter in {@link #broadcastChanges()}, which the server calls each tick for the
 * open menu, and sends one {@link MenuValuesPayload} with the changed values at most once a server tick, only when
 * something changed; the first one after opening (or a resync) carries all of them. The client menu (no host) mirrors
 * them when the payload arrives, on the client main thread. Ticks are the server's tick count, which also advances
 * while the level's time stands still ({@code /tick freeze}), since players still tick and broadcast then.
 * <p>
 * The first payload cannot reach the client before its menu exists: NeoForge sends the open-screen packet before
 * {@code initMenu}, and both it and this payload run on the client's packet processor in arrival order (the
 * {@code neoforge-26-1} skill). A payload for another container id is dropped.
 * <p>
 * Slots: {@link #addSlots} shows an {@link ItemSlots} run as a {@link SlotGroup} of {@link ToolkitSlot}s following the
 * slots' roles, and {@link #addPlayerInventory} the player's main inventory and hotbar. The client menu builds the same
 * groups over an empty copy of the slots (the same {@link ItemSlots.Builder}), which vanilla slot sync fills.
 * Shift-clicks never use vanilla's {@code moveItemStackTo} on copies: a player stack goes into the groups named in
 * {@link #shiftClick} in that order (or, if no slot there takes that item at all, between the main inventory and the
 * hotbar, like vanilla), a machine stack into the main inventory and then the hotbar, each as one {@link MenuTransfer}
 * transaction. {@link #addTank} makes a tank gauge clickable with a fluid container through {@code clickMenuButton}.
 */
public abstract class ToolkitMenu<H> extends AbstractContainerMenu {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** See {@link #sendNewMenusValuesTo}; server thread only. */
    private static @Nullable Consumer<MenuValuesPayload> newMenusSink;

    private final MenuValueTable<H> table;
    private final Player player;
    private final @Nullable H host;
    private @Nullable RegistryFriendlyByteBuf scratch;
    private @Nullable Consumer<MenuValuesPayload> sink;
    private @Nullable LongSupplier ticks;
    private long sentTick = Long.MIN_VALUE;
    private boolean oversizeLogged;
    private final List<SlotGroup> shiftOrder = new ArrayList<>();
    private final List<@Nullable LibFluidTank> tanks = new ArrayList<>();
    private int playerFirst = -1;
    private int playerEnd = -1;

    /**
     * {@code host} is the server-side object the getters read; null builds the client menu, which only mirrors.
     * {@code player} is the player the menu belongs to.
     */
    protected ToolkitMenu(@Nullable MenuType<?> type, int containerId, Player player, MenuValues<H> values,
            @Nullable H host) {
        super(type, containerId);
        this.table = new MenuValueTable<>(values);
        this.player = player;
        this.host = host;
        this.sink = newMenusSink;
        table.markAll();
    }

    /**
     * Sends the values payloads of menus built from now on to {@code sink} (null: the player's connection again). For
     * GameTests that open a menu through the api for a mock server player: vanilla broadcasts its values while opening
     * it, before the test could set {@link #sendValuesTo}, and the mock connection refuses Lib's payloads. Lib-internal
     * test helper; addons use {@code MenuTesting}.
     */
    @VisibleForTesting
    public static void sendNewMenusValuesTo(@Nullable Consumer<MenuValuesPayload> sink) {
        newMenusSink = sink;
    }

    /**
     * The {@code clickMenuButton} id of the first tank; tank {@code n} (in {@link #addTank} order) is this plus n. Ids
     * from here to {@link #ACTION_BUTTONS} belong to tanks, so a subclass's own buttons use ids below it.
     */
    public static final int TANK_BUTTONS = 1_000_000;
    /**
     * The first {@code clickMenuButton} id of a {@link DefinedMenu}'s cycle actions; ids from here on belong to them.
     * The button ids travel as var-ints, so they need not fit a byte.
     */
    public static final int ACTION_BUTTONS = 2_000_000;
    /**
     * The first {@code clickMenuButton} id of a {@link DefinedMenu}'s face clicks (port values); ids from here on
     * belong to them, and the cycle actions' ids stop below it.
     */
    public static final int PORT_BUTTONS = 3_000_000;

    /**
     * Adds menu slots for slots {@code from} (inclusive) to {@code to} (exclusive) of {@code slots}, in rows of
     * {@code columns} 18 pixels apart from ({@code x}, {@code y}); returns the group. {@code slots} must come from
     * {@link ItemSlots#builder}; both sides add the same groups in the same order.
     */
    protected final SlotGroup addSlots(ItemSlots slots, int from, int to, int x, int y, int columns) {
        if (!(slots instanceof LibItemSlots lib)) {
            throw new IllegalArgumentException("Not Lib's slots: " + slots.getClass().getName());
        }
        if (from < 0 || to > lib.size() || from >= to || columns < 1) {
            throw new IndexOutOfBoundsException("Slots " + from + " to " + to + " of " + lib.size());
        }
        SlotGroup group = new SlotGroup(lib, from, to, this.slots.size());
        for (int i = from; i < to; i++) {
            int n = i - from;
            addSlot(new ToolkitSlot(lib, i, x + (n % columns) * 18, y + (n / columns) * 18));
        }
        return group;
    }

    /**
     * Adds the player's main inventory (three rows from ({@code x}, {@code y})) and hotbar (58 pixels lower), the
     * targets of shift-clicks out of the machine. At most once per menu.
     */
    protected final void addPlayerInventory(Inventory inventory, int x, int y) {
        if (playerFirst >= 0) {
            throw new IllegalStateException("Player inventory added twice");
        }
        playerFirst = slots.size();
        int hotbar = Inventory.getSelectionSize();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < hotbar; column++) {
                addSlot(new Slot(inventory, hotbar + row * hotbar + column, x + column * 18, y + row * 18));
            }
        }
        for (int column = 0; column < hotbar; column++) {
            addSlot(new Slot(inventory, column, x + column * 18, y + 58));
        }
        playerEnd = slots.size();
    }

    /**
     * Where a shift-clicked player stack goes: the groups in this order, each filled (matching stacks, then empty
     * slots) before the next. Groups not named here never receive shift-clicked items. Replaces any earlier order.
     */
    protected final void shiftClick(SlotGroup... groups) {
        shiftOrder.clear();
        shiftOrder.addAll(Arrays.asList(groups));
    }

    /**
     * Makes a tank gauge clickable with fluid containers; returns the {@code clickMenuButton} id the screen sends. The
     * server menu passes the tank (from {@link FluidTank#create}), the client menu null; both add tanks in the same
     * order. Fluid tanks only: clicks go through items' fluid capabilities, and tanks of other kinds wait for the
     * bridge from a kind to its capabilities (ADR-0017, T1).
     */
    protected final int addTank(@Nullable FluidTank tank) {
        if (tank != null && !(tank instanceof LibFluidTank)) {
            throw new IllegalArgumentException("Not Lib's tank: " + tank.getClass().getName());
        }
        tanks.add((LibFluidTank) tank);
        return TANK_BUTTONS + tanks.size() - 1;
    }

    /**
     * Moves the clicked stack in one transaction (see the class comment) and returns {@link ItemStack#EMPTY}, so
     * vanilla's click loop stops: whatever did not fit stays where it was.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot instanceof ToolkitSlot toolkit) {
            MenuTransfer.toPlayer(player, toolkit.slots(), toolkit.index());
        } else if (index >= playerFirst && index < playerEnd) {
            MenuTransfer.fromPlayer(player, slot.getContainerSlot(), shiftOrder);
        }
        return ItemStack.EMPTY;
    }

    /** A tank button (see {@link #addTank}) moves fluid between the carried container and the tank. */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        int tank = id - TANK_BUTTONS;
        if (tank < 0 || tank >= tanks.size()) {
            return super.clickMenuButton(player, id);
        }
        LibFluidTank target = tanks.get(tank);
        return target != null && MenuTransfer.tankClick(player, this, target.handler());
    }

    /** The menu's values: the mirror on the client, the last read on the server. */
    public final MenuValueView values() {
        return table;
    }

    final MenuValueTable<H> table() {
        return table;
    }

    /**
     * Sends values payloads to {@code sink} instead of the player's connection (GameTests: mock players have none).
     * Lib-internal test helper; addons use {@code MenuTesting}.
     */
    @VisibleForTesting
    public final void sendValuesTo(Consumer<MenuValuesPayload> sink) {
        this.sink = sink;
    }

    /**
     * Counts ticks with {@code ticks} instead of the server's tick count (GameTests: a frozen level). Lib-internal test
     * helper; addons use {@code MenuTesting}.
     */
    @VisibleForTesting
    public final void countTicksWith(LongSupplier ticks) {
        this.ticks = ticks;
        sentTick = Long.MIN_VALUE;
    }

    /** Menu index of the player's first main-inventory slot, or -1 when the menu has no player inventory. */
    final int playerFirst() {
        return playerFirst;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        H source = host;
        if (source == null) {
            return;
        }
        if (!table.collect(source)) {
            return;
        }
        long now = tick();
        if (now == sentTick) {
            return;
        }
        sentTick = now;
        send();
    }

    @Override
    public void sendAllDataToRemote() {
        super.sendAllDataToRemote();
        table.markAll();
    }

    private long tick() {
        LongSupplier source = ticks;
        if (source == null) {
            // A menu with a host is a server menu, so its player is in a server level.
            source = ((ServerLevel) player.level()).getServer()::getTickCount;
            ticks = source;
        }
        return source.getAsLong();
    }

    private void send() {
        RegistryFriendlyByteBuf buf = scratch;
        if (buf == null) {
            buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.level().registryAccess());
            scratch = buf;
        }
        buf.clear();
        table.encode(buf);
        if (buf.writerIndex() > MenuValuesPayload.MAX_BODY) {
            // The marks stay, so the values go out once they fit again.
            if (!oversizeLogged) {
                oversizeLogged = true;
                LOGGER.error("Menu values of {} take {} bytes, over {}; not sent until they fit", getClass().getName(),
                        buf.writerIndex(), MenuValuesPayload.MAX_BODY);
            }
            return;
        }
        table.clearMarks();
        byte[] body = new byte[buf.writerIndex()];
        buf.getBytes(0, body);
        MenuValuesPayload payload = new MenuValuesPayload(containerId, body);
        Consumer<MenuValuesPayload> target = sink;
        if (target != null) {
            target.accept(payload);
        } else if (player instanceof ServerPlayer serverPlayer) {
            Platform.get().network().sendToPlayer(serverPlayer, payload);
        }
    }
}
