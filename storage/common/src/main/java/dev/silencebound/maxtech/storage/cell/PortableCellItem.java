package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The portable cell (PRD 7.2, "field cell"): a cell with its own terminal and an FE battery. Using it opens the item
 * terminal's GUI over its contents ({@link PortableCellSource}); each item or fluid moved costs FE like a network
 * operation. A sneaking player charges it by using it on a storage controller, which gives what its buffer holds up to
 * the free room; FE chargers of other mods reach the battery through NeoForge's item energy capability. It also works
 * as a plain cell in a drive.
 */
public final class PortableCellItem extends StorageCellItem {
    public static final String ENERGY_KEY = "tooltip.maxtech_storage.portable_cell.energy";
    public static final String IN_USE_KEY = "message.maxtech_storage.portable_cell.in_use";
    private static final int BAR_COLOUR = 0x46B2BD;

    public PortableCellItem(Properties properties) {
        super(properties, CellCasing.PORTABLE);
    }

    /** The battery's capacity in FE, from config; any thread. */
    public static int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.PORTABLE_ENERGY_CAPACITY.getAsInt(), 0);
    }

    /** MU in {@code stack}'s battery. */
    public static int energy(ItemStack stack) {
        return ItemEnergy.getInt(stack, ModDataComponents.PORTABLE_ENERGY.get());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, CarriedSlot.hand(player, hand));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Opens the portable cell in {@code slot}, as using it does, or tells the player it is in use elsewhere. Server
     * thread.
     */
    public static void open(ServerPlayer player, CarriedSlot slot) {
        PortableCellSource source = PortableCellSource.open(player, slot);
        if (source == null) {
            // A GameTest player has no connection to send the message to.
            if (player.connection != null) {
                player.sendOverlayMessage(Component.translatable(IN_USE_KEY));
            }
        } else {
            TerminalMenu.open(player, source, player.blockPosition(), slot.get().getHoverName());
            // A menu that did not open never closes, so the cell would stay mounted.
            if (!(player.containerMenu instanceof TerminalMenu)) {
                source.release();
            }
        }
    }

    /** Sneak-use on a storage controller: takes FE from its buffer into the battery, as much as both allow. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive() || !(context.getLevel()
                .getBlockEntity(context.getClickedPos()) instanceof ControllerBlockEntity block)) {
            return InteractionResult.PASS;
        }
        // Any block of a multiblock controller: the primary holds the FE (M7R item 1).
        ControllerBlockEntity c = block.primary();
        if (c == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        int stored = energy(stack);
        long moved = Math.min(capacity() - (long) stored, c.buffer().stored());
        if (moved > 0 && c.buffer().tryConsume(moved)) {
            ItemEnergy.setInt(stack, ModDataComponents.PORTABLE_ENERGY.get(), stored + (int) moved);
        }
        // The item bar shows the new charge.
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int capacity = capacity();
        return capacity <= 0 ? 0 : Mth.clamp(Math.round(13.0F * energy(stack) / capacity), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOUR;
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(ENERGY_KEY, energy(stack), capacity()).withStyle(ChatFormatting.GRAY));
    }
}
