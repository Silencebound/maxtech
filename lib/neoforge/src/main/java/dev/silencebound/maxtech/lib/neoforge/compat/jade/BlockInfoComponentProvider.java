package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.client.conduit.ConduitLabels;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.JadeIds;
import snownee.jade.api.config.IPluginConfig;

/**
 * Client side: adds the entries {@link BlockInfoServerProvider} sent, if any, below the block's name, in the rows
 * {@link OverlayLayout} picks and drawn by {@link InfoElements}, and, with the client's {@code conduitColorLabels} on,
 * a conduit's colour and connector marks in words ({@link ConduitLabels}). The marks come from the client's own block
 * entity, since the server cannot see a client option.
 */
final class BlockInfoComponentProvider implements IBlockComponentProvider {
    static final BlockInfoComponentProvider INSTANCE = new BlockInfoComponentProvider();

    private BlockInfoComponentProvider() {
    }

    @Override
    public Identifier getUid() {
        return BlockInfoLines.UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        List<InfoEntry> entries = BlockInfoServerProvider.INSTANCE.decodeFromData(accessor).orElse(List.of());
        boolean details = accessor.showDetails();
        OverlayLayout.Layout layout = OverlayLayout.arrange(entries, details);
        // The title line (Jade's object name) is the tooltip's first line; without it the statuses start a row.
        boolean titled = !tooltip.get(JadeIds.CORE_OBJECT_NAME).isEmpty();
        List<List<InfoEntry>> rows = new ArrayList<>();
        if (!layout.title().isEmpty() && !titled) {
            rows.add(layout.title());
        }
        if (titled) {
            for (InfoEntry entry : layout.title()) {
                tooltip.append(0, InfoElements.gap());
                for (LayoutElement element : InfoElements.of(entry, details)) {
                    tooltip.append(0, element);
                }
            }
        }
        rows.addAll(layout.rows());
        for (List<InfoEntry> row : rows) {
            boolean first = true;
            for (InfoEntry entry : row) {
                if (!first) {
                    tooltip.append(InfoElements.gap());
                }
                for (LayoutElement element : InfoElements.of(entry, details)) {
                    if (first) {
                        tooltip.add(element);
                        first = false;
                    } else {
                        tooltip.append(element);
                    }
                }
            }
        }
        if (accessor.getBlockEntity() instanceof ConduitBlockEntity conduit) {
            ConduitRenderData data = conduit.getModelData().get(ConduitBlockEntity.RENDER_DATA);
            if (data != null) {
                for (Component line : ConduitLabels.infoLines(data, ClientConfig.conduitColorLabels())) {
                    tooltip.add(line);
                }
            }
        }
    }
}
