package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

/**
 * The research scanner: used on any block, it gives the player's team {@code research.sources.scanner.points} the first
 * time the team scans that block type (by block id, so every state and every placed copy of a block counts once). A
 * short cooldown follows every use.
 */
public final class ResearchScannerItem extends Item {
    public static final String DESCRIPTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".research_scanner";
    public static final String NEW_KEY = "message." + MaxtechStorage.MOD_ID + ".research.scanned";
    public static final String KNOWN_KEY = "message." + MaxtechStorage.MOD_ID + ".research.scanned_known";
    public static final String OFF_KEY = "message." + MaxtechStorage.MOD_ID + ".research.sources_off";

    /** What a scan came to. */
    public enum Result {
        /** The team's first scan of the block type; points added. */
        NEW,
        /** The team scanned this block type before; nothing added. */
        KNOWN,
        /** Research is off; nothing recorded. */
        OFF;
    }

    public ResearchScannerItem(Properties properties) {
        super(properties);
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use, so scanning a chest or a machine
    // scans it instead of opening it, as with the network analyzer.
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
        if (player instanceof ServerPlayer serverPlayer) {
            player.getCooldowns().addCooldown(stack, StorageServerConfig.SCANNER_COOLDOWN.getAsInt());
            Block block = context.getLevel().getBlockState(context.getClickedPos()).getBlock();
            Result result = scan(serverPlayer, block);
            SourcePoints.tell(serverPlayer, switch (result) {
                case NEW -> Component.translatable(NEW_KEY, block.getName(),
                        SourcePoints.points(StorageServerConfig.SCANNER_POINTS.getAsLong(), SourcePoints.scale()));
                case KNOWN -> Component.translatable(KNOWN_KEY, block.getName());
                case OFF -> Component.translatable(OFF_KEY);
            });
        }
        return InteractionResult.SUCCESS;
    }

    /** Scans {@code block} for {@code player}'s team; the cooldown is the caller's. Server thread. */
    public static Result scan(ServerPlayer player, Block block) {
        if (!Research.active()) {
            return Result.OFF;
        }
        MinecraftServer server = player.level().getServer();
        ResearchTeam team = Research.team(player, false);
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (!SourceData.get(server).scan(team, id)) {
            return Result.KNOWN;
        }
        SourcePoints.award(server, team, StorageServerConfig.SCANNER_POINTS.getAsLong());
        return Result.NEW;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
