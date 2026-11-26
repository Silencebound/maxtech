package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.Research;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Research notes found in structure chests and bought from librarians: reading one (use) gives the reader's team a
 * random number of points between {@code research.sources.notes.minPoints} and {@code maxPoints}, scaled by the preset,
 * and uses the note up. While research is off a note is kept.
 */
public final class ResearchNotesItem extends Item {
    public static final String DESCRIPTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".research_notes";
    public static final String READ_KEY = "message." + MaxtechStorage.MOD_ID + ".research.notes_read";

    public ResearchNotesItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (!Research.active()) {
                SourcePoints.tell(serverPlayer, Component.translatable(ResearchScannerItem.OFF_KEY));
                return InteractionResult.FAIL;
            }
            long points = SourcePoints.award(serverPlayer.level().getServer(), Research.team(serverPlayer, false),
                    roll(serverPlayer.getRandom()));
            player.getItemInHand(hand).consume(1, player);
            SourcePoints.tell(serverPlayer, Component.translatable(READ_KEY, points));
        }
        return InteractionResult.SUCCESS;
    }

    /** The unscaled points of one note: uniform between the configured bounds, both included. */
    static int roll(RandomSource random) {
        int min = StorageServerConfig.NOTES_MIN_POINTS.getAsInt();
        int max = Math.max(min, StorageServerConfig.NOTES_MAX_POINTS.getAsInt());
        return random.nextIntBetweenInclusive(min, max);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
