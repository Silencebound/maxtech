package dev.silencebound.maxtech.farms.hub;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;

/**
 * The lab hub block. Registers no ticker. A click with a sampler puts it in the charging slot, a click with a fluid
 * container fills or empties the tank (a lava bucket too), a click with furnace fuel puts it in the fuel slot, an empty
 * hand while sneaking takes the sampler out, and an empty hand otherwise opens the hub's screen ({@link HubMenu}); any
 * other held item is placed or used as usual. Drops itself with everything it holds, creative breaking and explosions
 * included.
 */
public final class LabHubBlock extends Block implements EntityBlock {
    private static final MapCodec<LabHubBlock> CODEC = simpleCodec(LabHubBlock::new);
    private static final BiConsumer<ServerPlayer, LabHubBlockEntity> OPEN_SCREEN = HubMenu.MENU::open;
    private static volatile BiConsumer<ServerPlayer, LabHubBlockEntity> screenOpener = OPEN_SCREEN;

    public LabHubBlock(Properties properties) {
        super(properties);
    }

    /**
     * Replaces what a use that opens the hub's screen does, or restores it with null: GameTests record the opening
     * instead, since Lib refuses to send a menu's values to a mock player. Server thread.
     */
    @VisibleForTesting
    public static void openScreenWith(@Nullable BiConsumer<ServerPlayer, LabHubBlockEntity> opener) {
        screenOpener = opener == null ? OPEN_SCREEN : opener;
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
        ItemSlots slots = hub.storage().slots();
        if (stack.getItem() instanceof SamplerItem) {
            if (!slots.stack(HubStorage.SAMPLER).isEmpty() || slots.unknown(HubStorage.SAMPLER)) {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }
            // The sampler moves into the hub, in creative too: it is one tool, not a material.
            slots.set(HubStorage.SAMPLER, stack.copyWithCount(1));
            stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
        if (FluidUtil.interactWithFluidHandler(player, hand, pos, hub.storage().fluidHandler(), null)) {
            return InteractionResult.SUCCESS;
        }
        if (!slots.accepts(HubStorage.FUEL, stack)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        int inserted = slots.insert(HubStorage.FUEL, stack, false);
        if (inserted > 0 && !player.hasInfiniteMaterials()) {
            stack.shrink(inserted);
        }
        return inserted > 0 ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        // Only an empty hand opens the screen: a held item the hub did not take (lab tubes, any block) is placed or
        // used as usual.
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof LabHubBlockEntity hub)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack sampler = hub.storage().sampler();
        if (player.isSecondaryUseActive() && !sampler.isEmpty()) {
            ItemStack taken = sampler.copy();
            hub.storage().slots().set(HubStorage.SAMPLER, ItemStack.EMPTY);
            if (player.getMainHandItem().isEmpty()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, taken);
            } else if (!player.getInventory().add(taken)) {
                player.drop(taken, false);
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer server) {
            screenOpener.accept(server, hub);
        }
        return InteractionResult.SUCCESS;
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
