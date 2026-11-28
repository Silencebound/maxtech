package dev.silencebound.maxtech.lib.shim.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertPosException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

/**
 * Port only (1.21.1): 26.1 GameTestHelper methods 1.21.1 lacks, as static calls (port/rules/saving.py rewrites
 * {@code helper.m(args)} to {@code GameTestHelpers.m(helper, args)}).
 */
public final class GameTestHelpers {
    /**
     * Payloads mods send to every player on login without checking that the client listens. NeoForge 21.1 throws
     * when one goes to a connection that never negotiated it, and the mock player's connection negotiated nothing.
     */
    private static final ResourceLocation[] LOGIN_PAYLOADS = {
            // Jade's CommonProxy.playerJoin (PlayerLoggedInEvent)
            ResourceLocation.fromNamespaceAndPath("jade", "server_ping_v1"),
    };

    private GameTestHelpers() {
    }

    /** 26.1's {@code getBlockEntity(pos, type)}: the block entity at {@code pos}, which must be a {@code type}. */
    public static <T extends BlockEntity> T getBlockEntity(GameTestHelper helper, BlockPos pos, Class<T> type) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolute);
        if (blockEntity == null) {
            throw new GameTestAssertPosException("Missing block entity, expected " + type.getSimpleName(), absolute,
                    pos, helper.getTick());
        }
        if (!type.isInstance(blockEntity)) {
            throw new GameTestAssertPosException("Expected block entity " + type.getSimpleName() + ", got "
                    + blockEntity.getClass().getSimpleName(), absolute, pos, helper.getTick());
        }
        return type.cast(blockEntity);
    }

    /**
     * 1.21.1's {@code makeMockServerPlayerInLevel}, with the payloads of {@link #LOGIN_PAYLOADS} allowed on the mock
     * connection; without that, Jade's login ping makes {@code PlayerList.placeNewPlayer} throw.
     */
    public static ServerPlayer makeMockServerPlayerInLevel(GameTestHelper helper) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "test-mock-player"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(),
                cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        for (ResourceLocation id : LOGIN_PAYLOADS) {
            ChannelAttributes.getOrCreateAdHocChannels(connection).add(id);
        }
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
