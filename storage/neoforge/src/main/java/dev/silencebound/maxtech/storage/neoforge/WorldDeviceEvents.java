package dev.silencebound.maxtech.storage.neoforge;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.world.CollectorIndex;
import dev.silencebound.maxtech.storage.world.WorldActors;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Loader glue for the world devices: every item entity entering a server level goes to the collectors' index, and the
 * devices' fake players, the break event and the right-click event for claim checks are NeoForge's
 * ({@link WorldActors}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class WorldDeviceEvents implements WorldActors {
    /** Acts for devices nobody owns. */
    private static final GameProfile ANONYMOUS = new GameProfile(
            UUID.nameUUIDFromBytes("maxtech_storage:world_device".getBytes(StandardCharsets.UTF_8)),
            "[MaxTech Storage]");
    private static final String NAME = "[MaxTech]";

    private WorldDeviceEvents() {
    }

    /** Installs the fake players and break event; called from the entrypoint during construction. */
    static void install() {
        WorldActors.install(new WorldDeviceEvents());
    }

    @SubscribeEvent
    static void entityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item && event.getLevel() instanceof ServerLevel level) {
            CollectorIndex.itemJoined(level, item);
        }
    }

    /** The owner's id under a fixed name, so protection mods that trust the owner trust the device. */
    @Override
    public ServerPlayer player(ServerLevel level, OwnerRecord owner) {
        return FakePlayerFactory.get(level, owner.isOwned() ? new GameProfile(owner.owner(), NAME) : ANONYMOUS);
    }

    @Override
    public boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        return !CommonHooks.fireBlockBreak(level, GameType.SURVIVAL, player, pos, state).isCanceled();
    }

    /**
     * Posts the right-click event a player would cause clicking the middle of {@code side} with an empty hand. Claim
     * mods cancel it, or set the block use to {@code FALSE}, for players who may not use blocks there; either counts as
     * refused. Nothing else of the click happens.
     */
    @Override
    public boolean mayUse(ServerLevel level, BlockPos pos, Direction side, ServerPlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Vec3 hit = Vec3.atCenterOf(pos).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND,
                pos, new BlockHitResult(hit, side, pos, false));
        return !event.isCanceled() && event.getUseBlock() != TriState.FALSE;
    }
}
