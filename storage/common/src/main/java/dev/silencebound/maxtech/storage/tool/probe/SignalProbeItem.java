package dev.silencebound.maxtech.storage.tool.probe;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The signal probe: used on a storage cable or device it binds to that block; used in the air it opens a live graph of
 * the load on the bound block's link towards the network root, one sample a second for the last minute (both from
 * {@code tools.probe.*}). The graph is read only while its screen is open; the item itself does nothing between uses.
 */
public final class SignalProbeItem extends Item {
    public static final String DESCRIPTION_KEY = "tooltip.maxtech_storage.signal_probe";
    public static final String BOUND_KEY = "tooltip.maxtech_storage.signal_probe.bound";
    public static final String BOUND_MESSAGE_KEY = "message.maxtech_storage.signal_probe.bound";
    public static final String UNBOUND_KEY = "message.maxtech_storage.signal_probe.unbound";
    public static final String DENIED_KEY = "message.maxtech_storage.signal_probe.denied";
    public static final String NOT_STORAGE_KEY = "message.maxtech_storage.signal_probe.not_storage";
    /** Ticks between two bindings; a held use repeats every 4 ticks. */
    static final int COOLDOWN_TICKS = 10;

    public SignalProbeItem(Properties properties) {
        super(properties);
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use, as for the analyzer.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.getCooldowns().isOnCooldown(stack)) {
            return InteractionResult.FAIL;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
            BlockPos pos = context.getClickedPos();
            if (!ProbeSampler.probeable(level, pos)) {
                player.sendSystemMessage(Component.translatable(NOT_STORAGE_KEY));
                return InteractionResult.SUCCESS;
            }
            stack.set(ModDataComponents.PROBE_TARGET.get(), GlobalPos.of(level.dimension(), pos));
            player.sendSystemMessage(Component.translatable(BOUND_MESSAGE_KEY, pos.getX(), pos.getY(), pos.getZ()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        GlobalPos target = player.getItemInHand(hand).get(ModDataComponents.PROBE_TARGET.get());
        if (target == null) {
            player.sendSystemMessage(Component.translatable(UNBOUND_KEY));
            return InteractionResult.SUCCESS;
        }
        ServerLevel at = serverPlayer.level().getServer().getLevel(target.dimension());
        if (at == null || !ProbeSampler.mayRead(at, target.pos(), player)) {
            player.sendSystemMessage(Component.translatable(DENIED_KEY));
            return InteractionResult.SUCCESS;
        }
        ProbeMenu.open(serverPlayer, target, getName(player.getItemInHand(hand)),
                StorageServerConfig.PROBE_WINDOW_SECONDS.getAsInt(), StorageServerConfig.PROBE_SAMPLE_TICKS.getAsInt());
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
        @Nullable
        GlobalPos target = stack.get(ModDataComponents.PROBE_TARGET.get());
        if (target != null) {
            BlockPos pos = target.pos();
            builder.accept(Component.translatable(BOUND_KEY, pos.getX(), pos.getY(), pos.getZ(),
                    target.dimension().identifier().toString()).withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
