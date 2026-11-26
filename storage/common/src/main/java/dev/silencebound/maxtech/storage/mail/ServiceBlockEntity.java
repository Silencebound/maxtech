package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity of a mail port or request board: a storage device that only gives its screen a network to pay FE
 * from and take items out of. Holds nothing and never ticks.
 */
public final class ServiceBlockEntity extends StorageDeviceBlockEntity {
    private static final double REACH = 8.0;

    public ServiceBlockEntity(BlockPos pos, BlockState state) {
        super(MailContent.SERVICE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** Whether {@code player} is still close enough to use the block's screen. */
    public boolean stillValid(Player player) {
        return !isRemoved() && player.position().closerThan(worldPosition.getCenter(), REACH);
    }
}
