package dev.silencebound.maxtech.lib.highlight;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Server side of world highlights: sends a {@link HighlightRequest} to one player, whose client shows it through
 * {@link HighlightStore}. The diagnostic tool's overlay, "locate" buttons and multiblock previews use it.
 */
public final class Highlights {
    /** The channel {@link #locate} uses. */
    public static final Identifier LOCATE = MaxtechLib.id("locate");
    /** Locate outlines: cyan, so they stand out against most blocks. */
    public static final int LOCATE_COLOUR = 0xFF40E0FF;

    private static @Nullable BiConsumer<ServerPlayer, HighlightRequest> observer;

    private Highlights() {
    }

    /** Registers the payload; call once during mod construction. */
    public static void register() {
        Platform.get().network().registerToClient(MaxtechLib.MOD_ID, HighlightRequest.TYPE, HighlightRequest.CODEC,
                Highlights::receive);
    }

    /** Sends {@code request} to {@code player}. Server thread. */
    public static void send(ServerPlayer player, HighlightRequest request) {
        BiConsumer<ServerPlayer, HighlightRequest> watching = observer;
        if (watching != null) {
            watching.accept(player, request);
        }
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, request);
        }
    }

    /** Outlines {@code positions} through walls for {@code ticks} ticks on the {@link #LOCATE} channel. */
    public static void locate(ServerPlayer player, int ticks, BlockPos... positions) {
        HighlightRequest.Builder builder = HighlightRequest.builder(LOCATE).throughWalls(true).duration(ticks);
        for (BlockPos pos : positions) {
            builder.block(pos, LOCATE_COLOUR);
        }
        send(player, builder.build());
    }

    /** Sees every request {@link #send} handles, sent or not; GameTest mock players have no connection. */
    public static void setObserver(@Nullable BiConsumer<ServerPlayer, HighlightRequest> installed) {
        observer = installed;
    }

    private static void receive(HighlightRequest request, Player player) {
        if (player.level().isClientSide()) {
            HighlightStore.client().show(request);
        }
    }
}
