package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.info.EnergyInfo;
import dev.silencebound.maxtech.lib.info.InfoEntries;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the Jade overlay shows for a block, without any Jade types: the {@link BlockInfoProvider} entries (text,
 * statuses and bars) at the detail level Jade asks for, capped so a busy conduit cannot blow up the packet, and which
 * detail level the client's options ask for. Kept apart from the Jade classes so the selection is testable without
 * Jade.
 */
public final class BlockInfoLines {
    /** Uid of both Jade providers; also names the tooltip entry in the server payload. */
    public static final Identifier UID = MaxtechLib.id("block_info");
    /** Jade's name for the provider's toggle in its plugin config ({@code config.jade.plugin_<ns>.<path>}). */
    public static final String CONFIG_KEY = "config.jade.plugin_" + UID.toLanguageKey();
    public static final String MORE_KEY = "tooltip.maxtech.jade.more";
    /**
     * Most lines sent per block, the "more" line included. FULL for a cable with many parts and its network can run
     * past this; the scanner still prints everything.
     */
    public static final int MAX_LINES = 16;

    private BlockInfoLines() {
    }

    /** Jade's "show details" state (sneak by default) picks FULL; the plain overlay gets BRIEF. */
    public static BlockInfoRequest.Detail detail(boolean showDetails) {
        return showDetails ? BlockInfoRequest.Detail.FULL : BlockInfoRequest.Detail.BRIEF;
    }

    /**
     * Whether the client wants details for a MaxTech block while Jade's "show details" key is {@code keyDown}: always
     * with the detail level set to full, never with "show details while sneaking" off, else while the key is down.
     */
    public static boolean wantsDetails(boolean keyDown, boolean fullLevel, boolean sneakForDetails) {
        return fullLevel || (sneakForDetails && keyDown);
    }

    /**
     * The overlay entries for the block at {@code pos}: its provider's, then its energy entries ({@link EnergyInfo},
     * read from the network's last pass, never waking it); an empty list when there are none. Runs on the server
     * thread; BRIEF stays O(1) as long as the provider keeps to its contract.
     */
    public static List<InfoEntry> collect(ServerLevel level, BlockPos pos, @Nullable Direction side, @Nullable Vec3 hit,
            boolean showDetails) {
        BlockInfoProvider provider = InfoLookups.BLOCK.find(level, pos, side);
        BlockInfoRequest request = new BlockInfoRequest(level, pos, side, hit, detail(showDetails));
        InfoEntries entries = new InfoEntries();
        if (provider != null) {
            provider.addInfo(request, entries);
        }
        EnergyInfo.addLines(request, entries);
        return entries.entries().isEmpty() ? List.of() : cap(entries.entries());
    }

    /**
     * {@code entries} if they fit in {@link #MAX_LINES}; otherwise the first {@code MAX_LINES - 1} and a line saying
     * how many were left out. May return {@code entries} itself.
     */
    static List<InfoEntry> cap(List<InfoEntry> entries) {
        if (entries.size() <= MAX_LINES) {
            return entries;
        }
        List<InfoEntry> capped = new ArrayList<>(entries.subList(0, MAX_LINES - 1));
        capped.add(InfoEntry.text(
                Component.translatable(MORE_KEY, entries.size() - (MAX_LINES - 1)).withStyle(ChatFormatting.GRAY)));
        return capped;
    }
}
