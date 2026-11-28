package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuSlots;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.jspecify.annotations.Nullable;

/**
 * Lib's {@link MenuTesting.Session}: the server menu with its payloads captured, and a hostless client menu of the same
 * definition that applies them.
 */
final class MenuTestSession<H> implements MenuTesting.Session<H> {
    private static final int PLAYER_SLOTS = 36;

    private final MenuDefinition<H> definition;
    private final ServerPlayer player;
    private final DefinedMenu<H> server;
    private final DefinedMenu<H> client;
    private final List<MenuValuesPayload> captured = new ArrayList<>();
    private int applied;
    private long ticks;

    /**
     * Opens {@code definition} over {@code host} for {@code player}. Only GameTest mock players (an embedded channel,
     * which no real connection has) are accepted, in dev and production runs alike, so a real player's menu never gets
     * the capture sink.
     */
    @SuppressWarnings("unchecked") // LibMenus opens a DefinedMenu of this definition
    MenuTestSession(MenuDefinition<H> definition, ServerPlayer player, H host) {
        if (!permitted(player.connection == null ? null : player.connection.getConnection().channel())) {
            throw new IllegalStateException(
                    "MenuTesting is for GameTest mock players; " + player.getName().getString() + " is a real player");
        }
        this.definition = definition;
        this.player = player;
        ToolkitMenu.sendNewMenusValuesTo(captured::add);
        try {
            definition.open(player, host);
        } finally {
            ToolkitMenu.sendNewMenusValuesTo(null);
        }
        if (!(player.containerMenu instanceof DefinedMenu<?> opened) || opened.definition() != definition) {
            throw new IllegalStateException("Opening " + definition.name() + " left " + player.containerMenu);
        }
        server = (DefinedMenu<H>) opened;
        server.countTicksWith(() -> ticks);
        // The opening payload went out on the server's own tick count; this session counts from its first tick().
        client = new DefinedMenu<>(definition, server.containerId, player.getInventory(), null);
    }

    static boolean permitted(@Nullable Channel channel) {
        return channel instanceof EmbeddedChannel;
    }

    @Override
    public AbstractContainerMenu menu() {
        return server;
    }

    @Override
    public void tick() {
        ticks++;
        server.broadcastChanges();
    }

    @Override
    public MenuValueView serverValues() {
        return server.values();
    }

    @Override
    public MenuValueView clientValues() {
        while (applied < captured.size()) {
            MenuValuesPayload.apply(captured.get(applied++), client, player.level().registryAccess());
        }
        return client.values();
    }

    @Override
    public int payloadsSent() {
        return captured.size();
    }

    @Override
    public boolean pressTankButton(TankValue tank) {
        int button = server.tankButton(tank);
        if (button < 0) {
            throw new IllegalArgumentException("Tank takes no bucket clicks in " + definition.name());
        }
        return server.clickMenuButton(player, button);
    }

    @Override
    public void shiftClick(MenuSlots group, int index) {
        if (index < 0 || index >= group.size()) {
            throw new IndexOutOfBoundsException("Slot " + index + " of a group of " + group.size());
        }
        int first = 0;
        for (MenuSlots other : definition.slots()) {
            if (other.index() == group.index()) {
                server.clicked(first + index, 0, ContainerInput.QUICK_MOVE, player);
                return;
            }
            first += other.size();
        }
        throw new IllegalArgumentException("Group of another definition than " + definition.name());
    }

    @Override
    public void shiftClickPlayerSlot(int index) {
        int first = server.playerFirst();
        if (first < 0) {
            throw new IllegalStateException(definition.name() + " has no player inventory");
        }
        if (index < 0 || index >= PLAYER_SLOTS) {
            throw new IndexOutOfBoundsException("Player slot " + index + " of " + PLAYER_SLOTS);
        }
        server.clicked(first + index, 0, ContainerInput.QUICK_MOVE, player);
    }

    @Override
    public void close() {
        player.closeContainer();
        ToolkitMenu.sendNewMenusValuesTo(null);
    }
}
