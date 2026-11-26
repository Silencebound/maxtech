package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The research terminal: an access device that holds nothing and never ticks. It opens the research screen of the
 * player who uses it (their team's research, not the network owner's) while its network is online and powered, like
 * other devices need their network to work (owner decision 2026-10-03). The part form is {@link ResearchTerminalPart}.
 */
public final class ResearchTerminalBlockEntity extends StorageDeviceBlockEntity implements ResearchStation {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;

    public ResearchTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ResearchContent.TERMINAL_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    @Override
    public @Nullable NetworkState stationNetwork() {
        return network();
    }

    @Override
    public BlockPos stationPos() {
        return getBlockPos();
    }

    @Override
    public boolean inReach(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }
}
