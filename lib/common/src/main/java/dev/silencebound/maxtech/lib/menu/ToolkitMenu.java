package dev.silencebound.maxtech.lib.menu;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.platform.Platform;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Base of Lib's toolkit menus: syncs the declared {@link MenuValues} to the client without data slots. The server menu
 * (built with its host) reads every getter in {@link #broadcastChanges()}, which the server calls each tick for the
 * open menu, and sends one {@link MenuValuesPayload} with the changed values at most once a game tick, only when
 * something changed; the first one after opening (or a resync) carries all of them. The client menu (no host) mirrors
 * them when the payload arrives, on the client main thread.
 * <p>
 * The first payload cannot reach the client before its menu exists: NeoForge sends the open-screen packet before
 * {@code initMenu}, and both it and this payload run on the client's packet processor in arrival order (the
 * {@code neoforge-26-1} skill). A payload for another container id is dropped.
 */
public abstract class ToolkitMenu<H> extends AbstractContainerMenu {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MenuValueTable<H> table;
    private final Player player;
    private final @Nullable H host;
    private @Nullable RegistryFriendlyByteBuf scratch;
    private @Nullable Consumer<MenuValuesPayload> sink;
    private long sentTick = Long.MIN_VALUE;

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
        table.markAll();
    }

    /** The menu's values: the mirror on the client, the last read on the server. */
    public final MenuValueView values() {
        return table;
    }

    final MenuValueTable<H> table() {
        return table;
    }

    /** Sends values payloads to {@code sink} instead of the player's connection (GameTests: mock players have none). */
    public final void sendValuesTo(Consumer<MenuValuesPayload> sink) {
        this.sink = sink;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        H source = host;
        if (source == null) {
            return;
        }
        table.collect(source);
        long now = player.level().getGameTime();
        if (!table.dirty() || now == sentTick) {
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

    private void send() {
        RegistryFriendlyByteBuf buf = scratch;
        if (buf == null) {
            buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.level().registryAccess());
            scratch = buf;
        }
        buf.clear();
        table.encode(buf);
        if (buf.writerIndex() > MenuValuesPayload.MAX_BODY) {
            LOGGER.error("Menu values of {} take {} bytes, over {}; not sent", getClass().getName(), buf.writerIndex(),
                    MenuValuesPayload.MAX_BODY);
            return;
        }
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
