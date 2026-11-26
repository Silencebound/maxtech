package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.terminal.NetworkTerminal;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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
import org.jspecify.annotations.Nullable;

/**
 * The wireless terminal (M6d): sneak-use on a storage controller binds it to that network
 * ({@code maxtech_storage:wireless_link}); using it opens the item terminal's GUI over that network while the player is
 * within its wireless coverage ({@link WirelessCoverage}) in the same dimension, or from any other dimension while the
 * network has a dimension bridge (M6f; each operation then also pays the bridge's surcharge). The GUI closes when the
 * player leaves coverage, the network changes or the terminal leaves the hand (or the slot it was opened from by key).
 * Needs the Wireless research and the {@code modules.wireless} switch. Access is the network's, as at any terminal. The
 * terminal holds FE ({@code maxtech_storage:wireless_energy}), takes it from any FE charger through NeoForge's item
 * energy capability and spends {@code signal.wireless.energyPerOperation} per insert or extract
 * ({@link WirelessTerminalSource}); one that cannot pay an operation does not open.
 */
public final class WirelessTerminalItem extends Item {
    /** Why a wireless terminal opens or not. */
    public enum Reach {
        OK(""), DISABLED(SignalContent.WIRELESS_DISABLED_KEY), LOCKED(SignalContent.NEEDS_RESEARCH_KEY), UNBOUND(
                SignalContent.WIRELESS_UNBOUND_KEY), OTHER_DIMENSION(SignalContent.WIRELESS_DIMENSION_KEY), NO_NETWORK(
                        SignalContent.WIRELESS_NO_NETWORK_KEY), OUT_OF_RANGE(
                                SignalContent.WIRELESS_OUT_OF_RANGE_KEY), NO_POWER(SignalContent.WIRELESS_NO_POWER_KEY);

        private final String key;

        Reach(String key) {
            this.key = key;
        }

        /** The message key telling the player; empty for {@link #OK}. */
        public String key() {
            return key;
        }
    }

    /** The charge bar's colour, as the portable cell's. */
    private static final int BAR_COLOUR = 0x46B2BD;

    public WirelessTerminalItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** FE the terminal holds when full, from config; any thread. */
    public static int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.WIRELESS_ENERGY_CAPACITY.getAsInt(), 0);
    }

    /** FE per transfer a charger may put in, from config; any thread. */
    public static int maxInput() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.WIRELESS_MAX_INPUT.getAsInt(), 0);
    }

    /** FE one insert or extract through the terminal costs, from config; any thread. */
    public static int energyPerOperation() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.WIRELESS_ENERGY_PER_OPERATION.getAsInt(),
                0);
    }

    /** MU in {@code stack}; 0 for a terminal never charged. */
    public static int energy(ItemStack stack) {
        return ItemEnergy.getInt(stack, ModDataComponents.WIRELESS_ENERGY.get());
    }

    /** Whether {@code player} holding {@code stack} reaches its network now; see {@link Reach}. Server thread. */
    public static Reach reach(ServerPlayer player, ItemStack stack) {
        return reach(player, stack, energyPerOperation());
    }

    /**
     * Whether {@code player} holding {@code stack}, any pocket device bound with {@code maxtech_storage:wireless_link}
     * and charged in {@code maxtech_storage:wireless_energy}, reaches its network now by the wireless terminal's rules
     * when one operation costs {@code cost} FE. Server thread.
     */
    public static Reach reach(ServerPlayer player, ItemStack stack, int cost) {
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
        ControllerBlockEntity core = core(player, link);
        if (core == null || core.network() == null) {
            return Reach.NO_NETWORK;
        }
        if (!WirelessCoverage.reaches(core, player.level(), player.position())) {
            return link.dimension().equals(player.level().dimension()) || WirelessCoverage.range(core) <= 0
                    ? Reach.OUT_OF_RANGE
                    : Reach.OTHER_DIMENSION;
        }
        // An empty terminal cannot even show the network; with no cost per operation it needs no charge.
        return cost > 0 && energy(stack) < cost ? Reach.NO_POWER : Reach.OK;
    }

    /**
     * The loaded primary of the controller block at {@code link} (any block of a multiblock controller, M7R item 1), in
     * whichever dimension it is, or null. A controller in an unloaded chunk is out of reach; a network anchor keeps it
     * loaded.
     */
    public static @Nullable ControllerBlockEntity core(ServerPlayer player, GlobalPos link) {
        ServerLevel level = player.level().getServer().getLevel(link.dimension());
        BlockPos pos = link.pos();
        return level != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ControllerBlockEntity block
                ? block.primary()
                : null;
    }

    /**
     * The terminal source over the network {@code player}'s held terminal reaches, or null when it reaches none
     * ({@link #reach}). Valid while the player stays in coverage, the core keeps that network and the same terminal
     * stays in {@code hand}.
     */
    public static @Nullable WirelessTerminalSource source(ServerPlayer player, InteractionHand hand) {
        return source(player, CarriedSlot.hand(player, hand));
    }

    /**
     * The terminal source over the network the terminal in {@code slot} reaches, or null when it reaches none; valid
     * while the same terminal stays in that slot (a hand, or an accessory slot when opened by key).
     */
    @SuppressWarnings("ReferenceEquality") // the network is compared by identity
    public static @Nullable WirelessTerminalSource source(ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        if (reach(player, stack) != Reach.OK || link == null) {
            return null;
        }
        ControllerBlockEntity core = core(player, link);
        NetworkState network = core == null ? null : core.network();
        if (core == null || network == null) {
            return null;
        }
        return new WirelessTerminalSource(
                new NetworkTerminal(core, viewer -> viewer == player && player.isAlive() && core.network() == network
                        && link.equals(slot.get().get(ModDataComponents.WIRELESS_LINK.get()))
                        && slot.get().getItem() instanceof WirelessTerminalItem && StorageModules.WIRELESS.isEnabled()
                        && WirelessCoverage.reaches(core, player.level(), player.position())),
                player, slot, link, core);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, CarriedSlot.hand(player, hand));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Opens the terminal in {@code slot}, as using it does, or tells the player why it does not open. Server thread.
     */
    public static void open(ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        Reach reach = reach(player, stack);
        WirelessTerminalSource source = reach == Reach.OK ? source(player, slot) : null;
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        if (source == null || link == null) {
            tell(player,
                    reach == Reach.LOCKED
                            ? Component.translatable(reach.key(),
                                    Component.translatable(ResearchNode.unlockKey(ResearchGates.WIRELESS_TERMINAL)))
                            : Component.translatable(reach.key()));
        } else {
            TerminalMenu.open(player, source, link.pos(), stack.getHoverName());
        }
    }

    /** Sneak-use on a storage controller core binds the terminal to it. */
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

    private static void tell(ServerPlayer player, Component message) {
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
