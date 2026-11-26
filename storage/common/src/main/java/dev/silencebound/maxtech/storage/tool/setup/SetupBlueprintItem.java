package dev.silencebound.maxtech.storage.tool.setup;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.Rotation;

/**
 * The setup blueprint (M7C item 21): copies a group of devices and pastes it as a ghost to build into. Blank, it is
 * used on two opposite corners of a box; the second use copies the device blocks between them ({@link SetupCopy}). A
 * use on a block in one of the player's ghosts, or on the face of a block next to one, builds that ghost from the
 * network of a carried wireless terminal ({@link SetupBuild}), whatever the item holds. Holding a group, a use on a
 * block elsewhere places a ghost next to the clicked face ({@link SetupGhosts#paste}); sneak-use in the air turns the
 * next paste by 90 degrees clockwise; sneak-use on a block inside one of the player's ghosts removes that ghost,
 * elsewhere it forgets the group and takes the block as the first corner of a new copy. Changes only on the server.
 */
public final class SetupBlueprintItem extends Item {
    public SetupBlueprintItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook; it runs before the block's own use, which would open a screen.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        return use(context.getLevel(), context.getClickedPos(), context.getClickedFace(), player, stack);
    }

    /** The item used on the block at {@code pos}, clicked on {@code face}; see the class comment. Both sides. */
    public static InteractionResult use(Level level, BlockPos pos, Direction face, Player player, ItemStack stack) {
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        SetupGhosts ghosts = SetupGhosts.get(server.getServer());
        if (player.isSecondaryUseActive()) {
            if (ghosts.removeAt(player, server, pos)) {
                SetupGhosts.message(player, Component.translatable(SetupContent.REMOVED_KEY));
                return InteractionResult.SUCCESS;
            }
            stack.remove(SetupContent.BLUEPRINT.get());
            stack.remove(SetupContent.ROTATION.get());
            return corner(server, pos, player, stack);
        }
        SetupGhosts.Ghost ghost = ghosts.ghostAt(player.getUUID(), server.dimension(), pos)
                .or(() -> ghosts.ghostAt(player.getUUID(), server.dimension(), pos.relative(face))).orElse(null);
        if (ghost != null) {
            if (player instanceof ServerPlayer serverPlayer) {
                SetupGhosts.message(player, Component.translatable(switch (SetupBuild.start(serverPlayer, ghost)) {
                    case STARTED -> SetupContent.BUILD_STARTED_KEY;
                    case NO_TERMINAL -> SetupContent.BUILD_NO_TERMINAL_KEY;
                    case DENIED -> SetupContent.BUILD_DENIED_KEY;
                    case OTHER_LEVEL -> SetupContent.BUILD_OTHER_LEVEL_KEY;
                }));
            }
            return InteractionResult.SUCCESS;
        }
        SetupBlueprint held = stack.get(SetupContent.BLUEPRINT.get());
        if (held != null) {
            Rotation rotation = stack.getOrDefault(SetupContent.ROTATION.get(), Rotation.NONE);
            ghosts.paste(player, server, pos.relative(face), rotation, held);
            SetupGhosts.message(player, Component.translatable(SetupContent.PASTED_KEY));
            return InteractionResult.SUCCESS;
        }
        GlobalPos corner = stack.get(SetupContent.CORNER.get());
        if (corner == null || !corner.dimension().equals(server.dimension())) {
            return corner(server, pos, player, stack);
        }
        SetupCopy.Result result = SetupCopy.copy(player, server, corner.pos(), pos);
        SetupGhosts.message(player, switch (result.status()) {
            case COPIED -> result.withoutSettings() == 0
                    ? Component.translatable(SetupContent.COPIED_KEY, count(result))
                    : Component.translatable(SetupContent.COPIED_PARTLY_KEY, count(result), result.withoutSettings());
            case TOO_LARGE -> Component.translatable(SetupContent.TOO_LARGE_KEY, SetupBlueprint.MAX_SIZE);
            case TOO_MANY -> Component.translatable(SetupContent.TOO_MANY_KEY, SetupBlueprint.MAX_ENTRIES);
            case TOO_BIG -> Component.translatable(SetupContent.TOO_BIG_KEY, SetupBlueprint.MAX_BYTES / 1024);
            case UNLOADED -> Component.translatable(SetupContent.UNLOADED_KEY);
            case EMPTY -> Component.translatable(SetupContent.EMPTY_KEY);
        });
        if (result.blueprint().isPresent()) {
            stack.remove(SetupContent.CORNER.get());
            stack.set(SetupContent.BLUEPRINT.get(), result.blueprint().get());
        }
        return InteractionResult.SUCCESS;
    }

    private static int count(SetupCopy.Result result) {
        return result.blueprint().map(blueprint -> blueprint.entries().size()).orElse(0);
    }

    private static InteractionResult corner(ServerLevel level, BlockPos pos, Player player, ItemStack stack) {
        stack.set(SetupContent.CORNER.get(), GlobalPos.of(level.dimension(), pos.immutable()));
        SetupGhosts.message(player, Component.translatable(SetupContent.CORNER_KEY));
        return InteractionResult.SUCCESS;
    }

    /** Sneak-use in the air turns a held group, or cancels a started selection. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (stack.has(SetupContent.BLUEPRINT.get())) {
            if (!level.isClientSide()) {
                Rotation next = stack.getOrDefault(SetupContent.ROTATION.get(), Rotation.NONE)
                        .getRotated(Rotation.CLOCKWISE_90);
                stack.set(SetupContent.ROTATION.get(), next);
                SetupGhosts.message(player, Component.translatable(SetupContent.ROTATED_KEY, degrees(next)));
            }
            return InteractionResult.SUCCESS;
        }
        if (stack.has(SetupContent.CORNER.get())) {
            if (!level.isClientSide()) {
                stack.remove(SetupContent.CORNER.get());
                SetupGhosts.message(player, Component.translatable(SetupContent.CANCELLED_KEY));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static int degrees(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        SetupBlueprint held = stack.get(SetupContent.BLUEPRINT.get());
        if (held != null) {
            lines.accept(Component.translatable(SetupContent.TOOLTIP_HELD_KEY, held.entries().size(),
                    held.size().getX(), held.size().getY(), held.size().getZ()));
            Rotation rotation = stack.getOrDefault(SetupContent.ROTATION.get(), Rotation.NONE);
            if (rotation != Rotation.NONE) {
                lines.accept(Component.translatable(SetupContent.TOOLTIP_TURNED_KEY, degrees(rotation)));
            }
            lines.accept(Component.translatable(SetupContent.TOOLTIP_USE_KEY).withStyle(ChatFormatting.GRAY));
            lines.accept(Component.translatable(SetupContent.TOOLTIP_BUILD_KEY).withStyle(ChatFormatting.GRAY));
            return;
        }
        GlobalPos corner = stack.get(SetupContent.CORNER.get());
        if (corner != null) {
            lines.accept(Component.translatable(SetupContent.TOOLTIP_CORNER_KEY, corner.pos().getX(),
                    corner.pos().getY(), corner.pos().getZ()));
            return;
        }
        lines.accept(Component.translatable(SetupContent.TOOLTIP_EMPTY_KEY).withStyle(ChatFormatting.GRAY));
    }
}
