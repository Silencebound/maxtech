package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.lib.info.ScannerItem;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerReport;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerTexts;
import dev.silencebound.maxtech.storage.energy.EnergyDiagnostics;
import dev.silencebound.maxtech.storage.analyzer.RouteTrace;
import dev.silencebound.maxtech.storage.research.source.ItemAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The network analyzer (PRD 7.2, Diagnostics): held in the main hand it shows the brief info of the storage block the
 * player looks at in the action bar, refreshed twice a second; used on a block it prints the full info to chat, like
 * Lib's scanner, followed by the full analyzer's report ({@link AnalyzerReport}: route, zone, subnet) and lights the
 * route to the root through walls; sneak-used on a block it shows the network around the player coloured by load. Used
 * in the air with a processor or a storage cell in the other hand, it analyses that item for research points
 * ({@link ItemAnalysis}).
 */
public final class AnalyzerItem extends Item {
    public static final String DESCRIPTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".network_analyzer";
    public static final String SEPARATOR = " | ";
    /** Ticks between two action bar readouts while held; the overlay message itself lasts longer. */
    static final int READOUT_INTERVAL = 10;

    public AnalyzerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        if (slot != EquipmentSlot.MAINHAND || !(owner instanceof Player player)
                || (level.getGameTime() + player.getId()) % READOUT_INTERVAL != 0) {
            return;
        }
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Component line = readout(new BlockInfoRequest(level, hit.getBlockPos(), hit.getDirection(), hit.getLocation(),
                BlockInfoRequest.Detail.BRIEF));
        if (line != null) {
            player.sendOverlayMessage(line);
        }
    }

    /**
     * The brief lines of a storage block joined into one action bar line, or {@code null} when the block is not part of
     * a storage network (no storage cable or device there).
     */
    public static @Nullable Component readout(BlockInfoRequest request) {
        if (!isStorageBlock(request.level(), request.pos())) {
            return null;
        }
        BlockInfoProvider provider = InfoLookups.BLOCK.find(request.level(), request.pos(), request.side());
        if (provider == null) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        provider.addInfo(request, lines::add);
        // The overlay's glance leaves the network's name and power to the details; the analyzer's one-line readout
        // keeps them, as before the compact overlay (B09 item 7).
        NetworkState network = StorageNetworks.network(request.level(), StorageNetworks.ID, request.pos());
        if (network != null) {
            NetworkInfo.namePower(network, lines::add);
        }
        if (lines.isEmpty()) {
            return null;
        }
        MutableComponent joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append(SEPARATOR);
            }
            joined.append(lines.get(i));
        }
        return joined;
    }

    /** The storage network of the block at {@code pos}: its device's, else its cable's; null for neither. */
    public static @Nullable NetworkState networkAt(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device) {
            return device.network();
        }
        return StorageNetworks.network(level, StorageNetworks.ID, pos);
    }

    /** The graph node of the block at {@code pos}: a device's node (an uplink's is elsewhere), else the block. */
    public static long nodeAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device ? device.nodePos() : pos.asLong();
    }

    private static boolean isStorageBlock(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity
                || StorageNetworks.network(level, StorageNetworks.ID, pos) != null;
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use, as for Lib's scanner.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.getCooldowns().isOnCooldown(stack)) {
            // Not PASS: the block's own use would run instead.
            return InteractionResult.FAIL;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            // Holding use repeats every 4 ticks; the cooldown keeps that from flooding the chat.
            player.getCooldowns().addCooldown(stack, READOUT_INTERVAL);
            BlockPos pos = context.getClickedPos();
            NetworkState network = networkAt(level, pos);
            if (network != null && player instanceof ServerPlayer serverPlayer && player.isShiftKeyDown()) {
                Highlights.send(serverPlayer, AnalyzerReport.overlay(network, player.blockPosition()));
                player.sendSystemMessage(Component.translatable(AnalyzerTexts.OVERLAY_SHOWN,
                        InfoFormat.number(AnalyzerReport.OVERLAY_RADIUS)));
                return InteractionResult.SUCCESS;
            }
            List<Component> lines = ScannerItem.scan(new BlockInfoRequest(level, pos, context.getClickedFace(),
                    context.getClickLocation(), BlockInfoRequest.Detail.FULL));
            for (Component line : lines) {
                player.sendSystemMessage(line);
            }
            if (network != null) {
                long node = nodeAt(level, pos);
                for (Component line : AnalyzerReport.lines(network, node)) {
                    player.sendSystemMessage(line);
                }
                // A block with its own endpoint had its network's lines from the scan above.
                if (!EnergyDiagnostics.showsEndpoint(level, pos, context.getClickedFace())) {
                    for (Component line : AnalyzerReport.energyLines(network)) {
                        player.sendSystemMessage(line);
                    }
                }
                if (player instanceof ServerPlayer serverPlayer) {
                    Highlights.send(serverPlayer, AnalyzerReport.route(RouteTrace.of(network, node), node));
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Analyses the processor or cell in the other hand. Only an air use reaches this: a use on a block is the
     * diagnostic report above, which {@code onItemUseFirst} handles first.
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack other = player.getItemInHand(
                hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (!ItemAnalysis.analysable(other)) {
            return InteractionResult.PASS;
        }
        ItemStack analyzer = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(analyzer)) {
            return InteractionResult.FAIL;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            player.getCooldowns().addCooldown(analyzer, READOUT_INTERVAL);
            ItemAnalysis.analyse(serverPlayer, other);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
