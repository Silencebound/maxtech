package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.lib.client.ghost.GhostBlocks;
import dev.silencebound.maxtech.lib.client.ghost.GhostSet;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.storage.tool.setup.SetupGhosts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Draws the player's setup blueprint ghosts (M7C item 21) through Lib's {@link GhostBlocks}: a cell still to build as a
 * translucent cyan cube, a cell where something else stands as a red one, and a cell already holding the blueprint's
 * block as a green outline. The server sends the cells when the ghosts change; every {@link #CHECK_TICKS} ticks the
 * client compares each cell with its own level and rebuilds the set only when a cell's state changed. Nothing is read
 * while there are no ghosts. Client thread.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class SetupGhostClient {
    static final Identifier CHANNEL = MaxtechStorage.id("setup_ghosts");
    private static final int CHECK_TICKS = 10;
    /** The locate cyan, as the cluster preview's outlines. */
    private static final int TO_BUILD = 0xFF40E0FF;
    private static final int FACE_ALPHA = 0x48000000;
    private static final byte MISSING = 0;
    private static final byte BLOCKED = 1;
    private static final byte DONE = 2;
    private static int shownCount = -1;
    private static int countdown;
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static byte[] states = new byte[0];

    private SetupGhostClient() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        SetupGhosts.View view = SetupGhosts.received();
        boolean fresh = SetupGhosts.receivedCount() != shownCount;
        if (fresh) {
            shownCount = SetupGhosts.receivedCount();
            states = new byte[view.positions().size()];
            countdown = 0;
        }
        if (view.positions().isEmpty()) {
            if (fresh) {
                GhostBlocks.client().clear(CHANNEL);
            }
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = CHECK_TICKS;
        boolean changed = fresh;
        for (int i = 0; i < states.length; i++) {
            byte state = state(level, CURSOR.set(view.positions().getLong(i)), view.states().getInt(i));
            if (state != states[i]) {
                states[i] = state;
                changed = true;
            }
        }
        if (changed) {
            show(view);
        }
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SetupGhosts.forget();
        GhostBlocks.client().clear(CHANNEL);
    }

    private static byte state(ClientLevel level, BlockPos pos, int expectedId) {
        BlockState expected = Block.BLOCK_STATE_REGISTRY.byId(expectedId);
        BlockState actual = level.getBlockState(pos);
        if (expected != null && actual.is(expected.getBlock())) {
            return DONE;
        }
        return actual.canBeReplaced() ? MISSING : BLOCKED;
    }

    private static void show(SetupGhosts.View view) {
        GhostSet.Builder builder = GhostSet.builder();
        int toBuild = (TO_BUILD & 0xFFFFFF) | FACE_ALPHA;
        int blocked = (StatusPalette.bad() & 0xFFFFFF) | FACE_ALPHA;
        for (int i = 0; i < states.length; i++) {
            long pos = view.positions().getLong(i);
            switch (states[i]) {
                case DONE -> builder.add(pos, StatusPalette.good(), GhostSet.Style.OUTLINE);
                case BLOCKED -> builder.add(pos, blocked, GhostSet.Style.FILLED);
                default -> builder.add(pos, toBuild, GhostSet.Style.FILLED);
            }
        }
        GhostBlocks.client().show(CHANNEL, builder.build());
    }
}
