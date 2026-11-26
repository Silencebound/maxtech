package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The network analyzer's research use: analysing a processor ({@code #maxtech_storage:processors}) or a storage cell
 * item held in the other hand gives the team {@code research.sources.analyzer.points} the first time the team analyses
 * that item type (by item id; a cell's contents and parts do not make it a new type). Server thread.
 */
public final class ItemAnalysis {
    public static final String NEW_KEY = "message." + MaxtechStorage.MOD_ID + ".research.analysed";
    public static final String KNOWN_KEY = "message." + MaxtechStorage.MOD_ID + ".research.analysed_known";

    /** What an analysis came to. */
    public enum Result {
        /** The team's first analysis of the item type; points added. */
        NEW,
        /** The team analysed this item type before; nothing added. */
        KNOWN,
        /** Research is off; nothing recorded. */
        OFF;
    }

    private ItemAnalysis() {
    }

    /** Whether the analyzer can analyse {@code stack}: a processor or a storage cell. Any thread. */
    public static boolean analysable(ItemStack stack) {
        return stack.is(ResearchContent.PROCESSORS) || stack.getItem() instanceof StorageCellItem;
    }

    /**
     * Analyses {@code stack} for {@code player}'s team and tells the player the outcome; the caller checked
     * {@link #analysable} and owns the cooldown.
     */
    public static Result analyse(ServerPlayer player, ItemStack stack) {
        if (!Research.active()) {
            SourcePoints.tell(player, Component.translatable(ResearchScannerItem.OFF_KEY));
            return Result.OFF;
        }
        MinecraftServer server = player.level().getServer();
        ResearchTeam team = Research.team(player, false);
        Component name = stack.getHoverName();
        if (!SourceData.get(server).analyse(team, BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            SourcePoints.tell(player, Component.translatable(KNOWN_KEY, name));
            return Result.KNOWN;
        }
        long points = SourcePoints.award(server, team, StorageServerConfig.ANALYZER_POINTS.getAsLong());
        SourcePoints.tell(player, Component.translatable(NEW_KEY, name, points));
        return Result.NEW;
    }
}
