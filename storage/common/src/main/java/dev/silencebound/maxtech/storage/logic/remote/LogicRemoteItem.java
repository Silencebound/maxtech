package dev.silencebound.maxtech.storage.logic.remote;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.AnalyzerItem;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import java.util.List;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The logic remote (M7L2 chunk 12). Sneak-use on a storage controller, or on any device or cable of a network with one
 * controller, binds it to that network ({@code maxtech_storage:wireless_link}, the controller's position). Using it
 * opens a small screen ({@link LogicRemoteMenu}) with the network's notices and the rules of its logic controllers,
 * which a player with logic access may pause and resume. It reaches its network from anywhere, in any dimension
 * ({@link #reach}), and runs on its own FE ({@code maxtech_storage:wireless_energy}): each pause or resume costs
 * {@code logic.remote.energyPerOperation}, and one that cannot pay that reads nothing new. Out of reach or power the
 * screen still opens and shows the last notices the remote saw ({@code maxtech_storage:remote_notices}).
 */
public final class LogicRemoteItem extends Item {
    /** The charge bar's colour, as the wireless terminal's. */
    private static final int BAR_COLOUR = 0x46B2BD;

    public LogicRemoteItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** FE the remote holds when full, from config; any thread. */
    public static int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.LOGIC_REMOTE_ENERGY_CAPACITY.getAsInt(), 0);
    }

    /** FE per transfer a charger may put in, from config; any thread. */
    public static int maxInput() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.LOGIC_REMOTE_MAX_INPUT.getAsInt(), 0);
    }

    /** FE one pause or resume costs, from config; any thread. */
    public static int energyPerOperation() {
        return (int) StorageServerConfig
                .loadedOr(() -> StorageServerConfig.LOGIC_REMOTE_ENERGY_PER_OPERATION.getAsInt(), 0);
    }

    /** MU in {@code stack}; 0 for a remote never charged. */
    public static int energy(ItemStack stack) {
        return ItemEnergy.getInt(stack, ModDataComponents.WIRELESS_ENERGY.get());
    }

    /**
     * Whether {@code player} holding {@code stack} reaches its network now. Unlimited (owner decision 2026-10-09): from
     * any distance and any dimension, with no wireless access mount, dimension bridge, wireless module or research. It
     * needs only a binding, a loaded controller core with its network (an anchor keeps one loaded) and one operation of
     * charge; access is checked where the network is used. Server thread.
     */
    public static Reach reach(ServerPlayer player, ItemStack stack) {
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        if (link == null) {
            return Reach.UNBOUND;
        }
        ControllerBlockEntity core = WirelessTerminalItem.core(player, link);
        if (core == null || core.network() == null) {
            return Reach.NO_NETWORK;
        }
        int cost = energyPerOperation();
        return cost > 0 && energy(stack) < cost ? Reach.NO_POWER : Reach.OK;
    }

    /**
     * The network {@code stack} is bound to while {@code player} reaches it with it ({@link #reach} is OK), else null.
     */
    public static @Nullable NetworkState network(ServerPlayer player, ItemStack stack) {
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        if (link == null || reach(player, stack) != Reach.OK) {
            return null;
        }
        ControllerBlockEntity core = WirelessTerminalItem.core(player, link);
        return core == null ? null : core.network();
    }

    /** The notices {@code stack} last saw, oldest first; empty for none. */
    public static List<NetworkNotices.Notice> notices(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.REMOTE_NOTICES.get(), List.of());
    }

    /** Replaces the notices {@code stack} keeps with the newest of {@code notices}. */
    static void remember(ItemStack stack, List<NetworkNotices.Notice> notices) {
        int from = Math.max(0, notices.size() - ModDataComponents.REMOTE_NOTICE_LIMIT);
        List<NetworkNotices.Notice> kept = List.copyOf(notices.subList(from, notices.size()));
        if (!kept.equals(notices(stack))) {
            stack.set(ModDataComponents.REMOTE_NOTICES.get(), kept);
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, CarriedSlot.hand(player, hand));
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the remote in {@code slot}, as using it does, or tells the player why it does not open. Server thread. */
    public static void open(ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        Reach reach = reach(player, stack);
        NetworkState network = network(player, stack);
        if (reach == Reach.UNBOUND) {
            tell(player, Component.translatable(reach.key()));
        } else if (network != null && !network.canAccess(player)) {
            tell(player, Component.translatable(RemoteContent.DENIED_KEY));
        } else {
            LogicRemoteMenu.open(player, slot, reach, stack.getHoverName());
        }
    }

    /**
     * Sneak-use binds the remote: to a storage controller clicked directly, else to the one controller of the network
     * of the device or cable clicked.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        // Both sides decide from what they can see, so a sneak-use on a storage block never also opens the screen.
        if (player == null || !player.isSecondaryUseActive()
                || !(level.getBlockEntity(clicked) instanceof StorageDeviceBlockEntity
                        || level.getBlockState(clicked).getBlock() instanceof ConduitBlock)) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos core = null;
        if (server.getBlockEntity(clicked) instanceof ControllerBlockEntity) {
            core = clicked;
        } else {
            NetworkState network = AnalyzerItem.networkAt(server, clicked);
            if (network != null && network.controllerDevice() instanceof ControllerBlockEntity controller) {
                core = controller.getBlockPos();
            }
        }
        if (core == null) {
            if (player instanceof ServerPlayer serverPlayer) {
                tell(serverPlayer, Component.translatable(RemoteContent.NO_CONTROLLER_KEY));
            }
            return InteractionResult.SUCCESS;
        }
        bind(context.getItemInHand(), GlobalPos.of(server.dimension(), core));
        if (player instanceof ServerPlayer serverPlayer) {
            tell(serverPlayer, Component.translatable(RemoteContent.BOUND_KEY, core.getX(), core.getY(), core.getZ()));
        }
        return InteractionResult.SUCCESS;
    }

    /** Binds {@code stack} to the controller at {@code core}; the notices of a previous network are dropped. */
    static void bind(ItemStack stack, GlobalPos core) {
        if (!core.equals(stack.get(ModDataComponents.WIRELESS_LINK.get()))) {
            stack.remove(ModDataComponents.REMOTE_NOTICES.get());
        }
        stack.set(ModDataComponents.WIRELESS_LINK.get(), core);
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
