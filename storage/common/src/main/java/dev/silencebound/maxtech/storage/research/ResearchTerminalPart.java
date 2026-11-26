package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/** The research terminal as a part on a storage cable's face: the same screen and rules as the block. */
public final class ResearchTerminalPart extends CablePart implements ResearchStation {
    public ResearchTerminalPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    @Override
    public @Nullable NetworkState stationNetwork() {
        return deviceLink().network();
    }

    @Override
    public BlockPos stationPos() {
        return pos();
    }

    @Override
    public boolean inReach(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), ResearchTerminalBlockEntity.RANGE_BUFFER);
    }

    /** Opens the research screen, or says why not; both sides report success so the client swings the arm. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ResearchStation.use(serverPlayer, this, type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }
}
