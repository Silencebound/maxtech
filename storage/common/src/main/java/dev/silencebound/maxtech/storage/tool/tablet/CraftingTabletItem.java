package dev.silencebound.maxtech.storage.tool.tablet;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessCoverage;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The crafting tablet (M7 C10): a handheld 3x3 crafting grid tied to a storage network. Sneak-use on a storage
 * controller binds it ({@code maxtech_storage:wireless_link}, the wireless terminal's component); using it opens the
 * grid while the player is in the network's wireless coverage, or in any dimension when the network has a dimension
 * bridge, the same rules as the wireless terminal ({@link Reach}). It needs the Wireless research and the
 * {@code modules.wireless} switch. The tablet holds FE ({@code maxtech_storage:wireless_energy}) that any FE charger
 * fills; each pull from or return to the network costs {@code crafting.tablet.energyPerOperation}, and an empty tablet
 * does not open. See {@link CraftingTabletMenu} for what the grid does.
 */
public final class CraftingTabletItem extends Item {
    /** The charge bar's colour, as the wireless terminal's. */
    private static final int BAR_COLOUR = 0x46B2BD;

    public CraftingTabletItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** FE the tablet holds when full, from config; any thread. */
    public static int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.CRAFTING_TABLET_ENERGY_CAPACITY.getAsInt(),
                0);
    }

    /** FE per transfer a charger may put in, from config; any thread. */
    public static int maxInput() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.CRAFTING_TABLET_MAX_INPUT.getAsInt(), 0);
    }

    /** FE one network pull, return or job request costs, from config; any thread. */
    public static int energyPerOperation() {
        return (int) StorageServerConfig
                .loadedOr(() -> StorageServerConfig.CRAFTING_TABLET_ENERGY_PER_OPERATION.getAsInt(), 0);
    }

    /** MU in {@code stack}; 0 for a tablet never charged. */
    public static int energy(ItemStack stack) {
        return ItemEnergy.getInt(stack, ModDataComponents.WIRELESS_ENERGY.get());
    }

    /**
     * Whether {@code player} holding {@code stack} reaches its network now, by the wireless terminal's rules and with
     * the tablet's own charge. Server thread.
     */
    public static Reach reach(ServerPlayer player, ItemStack stack) {
        if (!StorageModules.WIRELESS.isEnabled()) {
            return Reach.DISABLED;
        }
        if (!ResearchUnlocks.isUnlocked(player, ResearchGates.WIRELESS_TERMINAL)) {
            return Reach.LOCKED;
        }
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        if (link == null) {
            return Reach.UNBOUND;
        }
        ControllerBlockEntity core = WirelessTerminalItem.core(player, link);
        if (core == null || core.network() == null) {
            return Reach.NO_NETWORK;
        }
        if (!WirelessCoverage.reaches(core, player.level(), player.position())) {
            return link.dimension().equals(player.level().dimension()) || WirelessCoverage.range(core) <= 0
                    ? Reach.OUT_OF_RANGE
                    : Reach.OTHER_DIMENSION;
        }
        int cost = energyPerOperation();
        return cost > 0 && energy(stack) < cost ? Reach.NO_POWER : Reach.OK;
    }

    /**
     * The access over the network the tablet in {@code hand} reaches, or null when it reaches none ({@link #reach}). A
     * tablet that has run out of charge still gets one, so an open grid keeps working by hand; its pulls and returns
     * then fail.
     */
    public static @Nullable TabletAccess access(ServerPlayer player, InteractionHand hand) {
        return access(player, CarriedSlot.hand(player, hand));
    }

    /** The access over the network the tablet in {@code slot} reaches, or null; as for a hand. */
    public static @Nullable TabletAccess access(ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        if (!(stack.getItem() instanceof CraftingTabletItem) || link == null) {
            return null;
        }
        Reach reach = reach(player, stack);
        ControllerBlockEntity core = WirelessTerminalItem.core(player, link);
        if (core == null || core.network() == null || (reach != Reach.OK && reach != Reach.NO_POWER)) {
            return null;
        }
        return new TabletAccess(player, slot, link, core);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, CarriedSlot.hand(player, hand));
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the tablet in {@code slot}, as using it does, or tells the player why it does not open. Server thread. */
    public static void open(ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        Reach reach = reach(player, stack);
        TabletAccess access = reach == Reach.OK ? access(player, slot) : null;
        if (access == null) {
            tell(player,
                    reach == Reach.LOCKED
                            ? Component.translatable(reach.key(),
                                    Component.translatable(ResearchNode.unlockKey(ResearchGates.WIRELESS_TERMINAL)))
                            : Component.translatable(reach.key()));
        } else if (!access.canView()) {
            tell(player, Component.translatable(CraftingTabletContent.DENIED_KEY));
        } else {
            Platform.get().menus().open(player,
                    new SimpleMenuProvider((id, inventory, opener) -> CraftingTabletMenu.server(id, inventory, access),
                            stack.getHoverName()),
                    buffer -> {
                    });
        }
    }

    /** Sneak-use on a storage controller core binds the tablet to it. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()
                || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ControllerBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel server) {
            BlockPos pos = context.getClickedPos();
            context.getItemInHand().set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(server.dimension(), pos));
            if (player instanceof ServerPlayer serverPlayer) {
                tell(serverPlayer,
                        Component.translatable(SignalContent.WIRELESS_BOUND_KEY, pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    static void tell(ServerPlayer player, Component message) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
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
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        lines.accept(
                (link == null
                        ? Component.translatable(SignalContent.WIRELESS_TOOLTIP_UNBOUND_KEY)
                        : Component.translatable(SignalContent.WIRELESS_TOOLTIP_KEY, link.pos().getX(),
                                link.pos().getY(), link.pos().getZ(), link.dimension().identifier().toString()))
                        .withStyle(ChatFormatting.GRAY));
        lines.accept(Component.translatable(SignalContent.WIRELESS_ENERGY_TOOLTIP_KEY, energy(stack), capacity())
                .withStyle(ChatFormatting.GRAY));
    }
}
