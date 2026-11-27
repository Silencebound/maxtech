package dev.silencebound.maxtech.lib.menu;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.platform.Platform;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
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
 */
public abstract class ToolkitMenu<H> extends AbstractContainerMenu {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MenuValueTable<H> table;
    private final Player player;
    private final @Nullable H host;
    private @Nullable RegistryFriendlyByteBuf scratch;
    private @Nullable Consumer<MenuValuesPayload> sink;
    private @Nullable LongSupplier ticks;
    private long sentTick = Long.MIN_VALUE;
    private boolean oversizeLogged;

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

    /** Counts ticks with {@code ticks} instead of the server's tick count (GameTests: a frozen level). */
    public final void countTicksWith(LongSupplier ticks) {
        this.ticks = ticks;
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
