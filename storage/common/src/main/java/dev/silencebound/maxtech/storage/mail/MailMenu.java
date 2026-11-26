package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * A mail port's screen: nine outbox slots the player fills, the recipient's name and Send (one
 * {@link MailSendPayload}), and Collect (a menu button) for the player's own inbox. The outbox belongs to the open
 * screen, like a crafting table's grid: what was not sent goes back to the player when it closes. Inbox size and
 * capacity and the FE per stack are data slots; results show in the action bar.
 */
public final class MailMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 186;
    public static final int OUTBOX_SLOTS = 9;
    public static final int OUTBOX_Y = 58;
    public static final int INVENTORY_Y = 104;
    /** Menu button: collect the player's inbox. */
    public static final int COLLECT = 0;
    /** Longest name a player can have; longer ones are refused. */
    public static final int MAX_NAME = 16;
    private static final int INBOX = 0;
    private static final int CAPACITY = 1;
    private static final int FE = 2;
    private static final int DATA = 3;

    private final @Nullable ServiceBlockEntity port;
    private final SimpleContainer outbox = new SimpleContainer(OUTBOX_SLOTS);
    private final DataSlot[] data = new DataSlot[DATA];
    private final Player viewer;
    private long lastAction = Long.MIN_VALUE;

    private MailMenu(int containerId, Inventory inventory, @Nullable ServiceBlockEntity port) {
        super(ModMenus.MAIL_PORT.get(), containerId);
        this.port = port;
        this.viewer = inventory.player;
        for (int i = 0; i < OUTBOX_SLOTS; i++) {
            addSlot(new Slot(outbox, i, 8 + i * 18, OUTBOX_Y));
        }
        addStandardInventorySlots(inventory, 8, INVENTORY_Y);
        for (int i = 0; i < DATA; i++) {
            data[i] = addDataSlot(DataSlot.standalone());
        }
    }

    /** The client's menu. */
    public static MailMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new MailMenu(containerId, inventory, null);
    }

    /** Opens the port's screen for {@code player}. */
    public static void open(ServerPlayer player, ServiceBlockEntity port, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> new MailMenu(id, inventory, port), title),
                buffer -> buffer.writeBlockPos(port.getBlockPos()));
    }

    /** The outbox; for tests. */
    public SimpleContainer outbox() {
        return outbox;
    }

    public int inboxSize() {
        return data[INBOX].get();
    }

    public int inboxCapacity() {
        return data[CAPACITY].get();
    }

    public int muPerStack() {
        return data[FE].get();
    }

    /**
     * Sends the outbox to {@code name} for {@code player}: at most once per tick, with a valid name, while the screen
     * is still valid. Server side.
     */
    public MailService.@Nullable Result send(ServerPlayer player, String name) {
        ServiceBlockEntity current = port;
        long now = player.level().getGameTime();
        if (current == null || !stillValid(player) || now == lastAction || name.isEmpty() || name.length() > MAX_NAME) {
            return null;
        }
        lastAction = now;
        NetworkState network = current.network();
        MailService.Result result = MailService.send(player, network, name, outbox);
        if (player.connection != null) {
            player.sendOverlayMessage(Component.translatable(result.translationKey(), name));
        }
        broadcastChanges();
        return result;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        ServiceBlockEntity current = port;
        if (id != COLLECT || current == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        int collected = MailService.collect(serverPlayer, current.network());
        if (serverPlayer.connection != null) {
            serverPlayer
                    .sendOverlayMessage(Component.translatable(MailService.COLLECTED_KEY, Integer.toString(collected)));
        }
        return true;
    }

    @Override
    public void broadcastChanges() {
        ServiceBlockEntity current = port;
        if (current != null && current.getLevel() != null && current.getLevel().getServer() != null) {
            data[INBOX].set(MailData.get(current.getLevel().getServer()).size(viewer.getUUID()));
            data[CAPACITY].set(StorageServerConfig.MAIL_INBOX_STACKS.getAsInt());
            data[FE].set((int) Math.min(Integer.MAX_VALUE, StorageServerConfig.MAIL_MU_PER_STACK.getAsLong()));
        }
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        boolean moved = index < OUTBOX_SLOTS
                ? moveItemStackTo(stack, OUTBOX_SLOTS, slots.size(), true)
                : moveItemStackTo(stack, 0, OUTBOX_SLOTS, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        slot.setChanged();
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        ServiceBlockEntity current = port;
        return current == null || current.stillValid(player);
    }

    /** What was not sent goes back to the player. */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (port != null) {
            clearContainer(player, outbox);
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return port;
    }
}
