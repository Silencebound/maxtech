package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.recovery.CellRecovery;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/**
 * A storage cell (PRD 7.2, the cell builder): one item per casing. The item holds only its {@link CellData}: the parts
 * it is built from, its id and a cache of its stats; the contents live in {@link CellStorage}. Its name carries its
 * type ("Small Item Storage Cell") and its tooltip the bytes used and the kinds held, as of the last time it left a
 * drive or its drive was saved, then its parts.
 */
public class StorageCellItem extends Item {
    public static final String BYTES_KEY = "tooltip.maxtech_storage.cell.bytes";
    public static final String KINDS_KEY = "tooltip.maxtech_storage.cell.kinds";
    public static final String KEPT_KEY = "tooltip.maxtech_storage.cell.kept";
    public static final String ID_KEY = "tooltip.maxtech_storage.cell.id";
    public static final String MEMORY_KEY = "tooltip.maxtech_storage.cell.memory";
    public static final String ADDONS_KEY = "tooltip.maxtech_storage.cell.addons";
    public static final String BULK_KEY = "tooltip.maxtech_storage.cell.bulk";
    public static final String FILTER_KEY = "tooltip.maxtech_storage.cell.filter";
    public static final String FUZZY_KEY = "tooltip.maxtech_storage.cell.fuzzy";
    public static final String DENY_KEY = "tooltip.maxtech_storage.cell.deny";
    public static final String DENY_FUZZY_KEY = "tooltip.maxtech_storage.cell.deny_fuzzy";

    private final CellCasing casing;

    public StorageCellItem(Properties properties, CellCasing casing) {
        super(properties.stacksTo(1));
        this.casing = casing;
    }

    /** The cell's casing. */
    public CellCasing casing() {
        return casing;
    }

    /** Burnt, blown up or pricked to death: the contents go to the recovery bin of the cell's network (M7C item 20). */
    @Override
    public void onDestroyed(ItemEntity entity, DamageSource source) {
        super.onDestroyed(entity, source);
        if (entity.level() instanceof ServerLevel level) {
            CellRecovery.lost(level, entity.getItem());
        }
    }

    /** The cell's data; {@code null} for a stack without it (only possible through commands). */
    public static @Nullable CellData data(ItemStack stack) {
        return stack.get(ModDataComponents.CELL.get());
    }

    @Override
    public Component getName(ItemStack stack) {
        CellData data = data(stack);
        Component type = data == null
                ? Component.literal("?")
                : Component.translatable(CellTypes.translationKey(data.type()));
        return Component.translatable(getDescriptionId(), type);
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        CellData data = data(stack);
        if (data == null) {
            return;
        }
        // A cell that was never in a drive has no cached capacity; its parts give it from the server config, which a
        // client in a world has.
        long total = data.totalBytes() > 0
                ? data.totalBytes()
                : StorageServerConfig.loadedOr(() -> data.capacity(casing), 0);
        Object shownTotal = total > 0 ? total : "?";
        lines.accept(Component.translatable(BYTES_KEY, data.usedBytes(), shownTotal).withStyle(ChatFormatting.GRAY));
        lines.accept(Component.translatable(KINDS_KEY, data.kinds()).withStyle(ChatFormatting.GRAY));
        if (data.kept() > 0) {
            lines.accept(Component.translatable(KEPT_KEY, data.kept()).withStyle(ChatFormatting.GOLD));
        }
        lines.accept(Component.translatable(MEMORY_KEY, data.memory().size(), casing.memorySlots())
                .withStyle(ChatFormatting.DARK_GRAY));
        if (casing.bulk()) {
            lines.accept(Component.translatable(BULK_KEY).withStyle(ChatFormatting.DARK_GRAY));
        }
        for (Item addon : data.addons()) {
            lines.accept(Component.translatable(ADDONS_KEY, Component.translatable(addon.getDescriptionId()))
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        if ((data.has(CellAddon.PARTITION) || data.has(CellAddon.VOID_OVERFLOW)) && !data.filter().isBlank()) {
            lines.accept(Component
                    .translatable(filterKey(data.filter()),
                            data.filter().entries().size() + data.filter().kept().size())
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        if (flag.isAdvanced() && data.hasId()) {
            lines.accept(Component.translatable(ID_KEY, data.id()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** The tooltip line's key for a filter: allow or deny list, exact or fuzzy. */
    static String filterKey(CellFilter filter) {
        if (filter.deny()) {
            return filter.fuzzy() ? DENY_FUZZY_KEY : DENY_KEY;
        }
        return filter.fuzzy() ? FUZZY_KEY : FILTER_KEY;
    }
}
