package dev.silencebound.maxtech.storage.p2p;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.LevelReader;

/**
 * The P2P linking tool. Used while sneaking on a link end, it makes that end the input of a new frequency and keeps the
 * frequency; used on further ends of the same kind, it makes them outputs of that frequency. Every use checks access to
 * the end. The tool keeps the last input it made ({@code maxtech_storage:p2p_linker}).
 */
public final class P2PLinkerItem extends Item {
    /** The tool's last input: its kind and frequency. */
    public record Held(P2PKind kind, long frequency) {
        private static final Codec<P2PKind> KIND_CODEC = StringRepresentable.fromEnum(P2PKind::values);
        public static final Codec<Held> CODEC = RecordCodecBuilder
                .create(i -> i.group(KIND_CODEC.fieldOf("kind").forGetter(Held::kind),
                        Codec.LONG.fieldOf("frequency").forGetter(Held::frequency)).apply(i, Held::new));
    }

    public P2PLinkerItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** The linking tool used on {@code device}; see the class comment. Both sides; changes only on the server. */
    public static InteractionResult use(P2PDevice device, Player player, ItemStack tool) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!NetworkAccess.canConfigure(player, device)) {
            player.sendOverlayMessage(Component.translatable(P2PContent.DENIED_KEY));
            return InteractionResult.SUCCESS;
        }
        P2PEndpoint end = device.endpoint();
        Component kind = Component.translatable(end.kind().translationKey());
        if (player.isShiftKeyDown()) {
            long frequency = 0;
            while (frequency == 0) {
                frequency = player.level().getRandom().nextLong();
            }
            end.link(new P2PSettings(P2PRole.INPUT, frequency));
            tool.set(ModDataComponents.P2P_LINKER.get(), new Held(end.kind(), frequency));
            player.sendOverlayMessage(
                    Component.translatable(P2PContent.LINKED_INPUT_KEY, kind, P2PEndpoint.frequencyText(frequency)));
            return InteractionResult.SUCCESS;
        }
        Held held = tool.get(ModDataComponents.P2P_LINKER.get());
        if (held == null) {
            player.sendOverlayMessage(Component.translatable(P2PContent.NO_INPUT_KEY));
        } else if (held.kind() != end.kind()) {
            player.sendOverlayMessage(Component.translatable(P2PContent.WRONG_KIND_KEY,
                    Component.translatable(held.kind().translationKey()), kind));
        } else {
            end.link(new P2PSettings(P2PRole.OUTPUT, held.frequency()));
            player.sendOverlayMessage(Component.translatable(P2PContent.LINKED_OUTPUT_KEY, kind,
                    P2PEndpoint.frequencyText(held.frequency())));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Without this a sneaking player holding the tool never reaches the block's {@code useItemOn}: the game mode skips
     * it and calls {@code useOn}, which does nothing here. Both hands must bypass, so an off-hand item still blocks it.
     */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        Held held = stack.get(ModDataComponents.P2P_LINKER.get());
        lines.accept(held == null
                ? Component.translatable(P2PContent.TOOLTIP_EMPTY_KEY)
                : Component.translatable(P2PContent.TOOLTIP_HELD_KEY,
                        Component.translatable(held.kind().translationKey()),
                        P2PEndpoint.frequencyText(held.frequency())));
    }
}
