package dev.silencebound.maxtech.storage.controller.card;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * A controller upgrade card (M7R item 2): installed in a controller's card slots, it changes the network the way its
 * {@link CardKind} says. Its tooltip names the effect and how many cards count, from the server config the client got;
 * outside a world only where it goes.
 */
public final class UpgradeCardItem extends Item {
    private final CardKind kind;

    public UpgradeCardItem(Properties properties, CardKind kind) {
        super(properties);
        this.kind = kind;
    }

    public CardKind kind() {
        return kind;
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        long limit = read(switch (kind) {
            case BATCH_AMPLIFIER -> StorageServerConfig.CARD_LIMIT_AMPLIFIERS::getAsInt;
            case EFFICIENCY_CORE -> StorageServerConfig.CARD_LIMIT_EFFICIENCY::getAsInt;
            case SURGE_REGULATOR -> StorageServerConfig.CARD_LIMIT_SURGE::getAsInt;
        });
        if (limit >= 0) {
            switch (kind) {
                case BATCH_AMPLIFIER -> {
                    long each = read(StorageServerConfig.AMPLIFIER_BATCH_PERCENT::getAsInt);
                    long most = read(StorageServerConfig.AMPLIFIER_MAX_BATCH_PERCENT::getAsInt);
                    lines.accept(Component.translatable(CardContent.BATCH_TOOLTIP_KEY, InfoFormat.number(each)));
                    lines.accept(Component.translatable(CardContent.BATCH_CAP_KEY, InfoFormat.number(limit),
                            InfoFormat.number(Math.min(limit * each, most))));
                }
                case EFFICIENCY_CORE -> {
                    long each = read(StorageServerConfig.EFFICIENCY_PERCENT::getAsInt);
                    long most = read(StorageServerConfig.EFFICIENCY_MAX_PERCENT::getAsInt);
                    lines.accept(Component.translatable(CardContent.EFFICIENCY_TOOLTIP_KEY, InfoFormat.number(each)));
                    lines.accept(Component.translatable(CardContent.EFFICIENCY_CAP_KEY, InfoFormat.number(limit),
                            InfoFormat.number(Math.min(limit * each, most))));
                }
                case SURGE_REGULATOR -> {
                    long reserve = read(StorageServerConfig.SURGE_RESERVE_PERCENT::getAsInt);
                    lines.accept(Component.translatable(CardContent.SURGE_TOOLTIP_KEY, InfoFormat.number(reserve)));
                    lines.accept(Component.translatable(CardContent.SURGE_CAP_KEY, InfoFormat.number(limit)));
                }
            }
        }
        lines.accept(Component.translatable(CardContent.INSTALL_TOOLTIP_KEY));
    }

    /** {@code value} from the server config, or -1 while none is loaded (a client outside a world). */
    private static long read(LongSupplier value) {
        return StorageServerConfig.loadedOr(value, -1L);
    }
}
