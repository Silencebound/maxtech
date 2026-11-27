package dev.silencebound.maxtech.farms.hub;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.farms.lab.LabNetwork;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
// PLATFORM-LEAK: NeoForge's fluid transfer helper fills and empties buckets and canisters against the tank.
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
// PLATFORM-LEAK: NeoForge's item transfer type for the fuel put in by hand.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The lab hub block. Registers no ticker. Until the hub has a screen, a click with a fluid container fills or empties
 * the tank, a click with furnace fuel puts it in the fuel slot, and an empty hand shows the hub's state. Drops itself
 * with everything it holds, creative breaking included.
 */
public final class LabHubBlock extends Block implements EntityBlock {
    private static final MapCodec<LabHubBlock> CODEC = simpleCodec(LabHubBlock::new);

    public LabHubBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public LabHubBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LabHubBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LabHubBlockEntity hub)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (FluidUtil.interactWithFluidHandler(player, hand, pos, hub.storage().tank(), null)) {
            return InteractionResult.SUCCESS;
        }
        ItemResource resource = ItemResource.of(stack);
        if (!hub.storage().fuel().isValid(0, resource)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        int inserted;
        try (Transaction transaction = Transaction.openRoot()) {
            inserted = hub.storage().fuel().insert(0, resource, stack.getCount(), transaction);
            transaction.commit();
        }
        if (inserted > 0 && !player.hasInfiniteMaterials()) {
            stack.shrink(inserted);
        }
        return inserted > 0 ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof LabHubBlockEntity hub) {
            player.sendOverlayMessage(hub.labLink() == LabHubBlockEntity.LabLink.REFUSED
                    ? Component.translatable("message.maxtech_farms.lab_hub.refused")
                    : Component.translatable("message.maxtech_farms.lab_hub.status_line", status(hub), link(hub)));
        }
        return InteractionResult.SUCCESS;
    }

    private static Component status(LabHubBlockEntity hub) {
        long fluid = hub.storage().tank().getAmountAsLong(0);
        Component medium = fluid > 0
                ? hub.storage().tank().getResource(0).toStack(1).getHoverName()
                : Component.translatable("message.maxtech_farms.lab_hub.empty");
        return Component.translatable("message.maxtech_farms.lab_hub.status", medium, fluid,
                hub.storage().tank().getCapacityAsLong(0, hub.storage().tank().getResource(0)), hub.energy().stored(),
                hub.energy().capacity());
    }

    private static Component link(LabHubBlockEntity hub) {
        LabNetwork lab = hub.lab();
        return lab == null
                ? Component.translatable("message.maxtech_farms.lab_hub.no_tubes")
                : Component.translatable("message.maxtech_farms.lab_hub.tubes", lab.tubes(), lab.portCount());
    }

    /** The loot table's hub, carrying what it holds. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LabHubBlockEntity hub) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    hub.writeContents(drop, params.getLevel().registryAccess());
                }
            }
        }
        return drops;
    }

    /** A creative player gets no drops, so a hub that holds something drops itself here, like a shulker box. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(pos) instanceof LabHubBlockEntity hub && !hub.isPristine()) {
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    hub.toItemStack(level.registryAccess()));
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
