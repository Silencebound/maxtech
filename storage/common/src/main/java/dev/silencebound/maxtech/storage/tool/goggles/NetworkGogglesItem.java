package dev.silencebound.maxtech.storage.tool.goggles;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.AnalyzerItem;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The network goggles (M6e, decision E3): worn on the head, once a second they read the storage cable or device the
 * wearer looks at into the action bar ({@link GogglesView#readout}: zone, segment load, channels, route length) and
 * keep the overlay of that network around the wearer on screen, resent every {@link #OVERLAY_REFRESH} ticks or when the
 * wearer looks at another network. They hold FE ({@code maxtech_storage:goggles_energy}), charge in any FE charger
 * through NeoForge's item energy capability and spend {@code tools.goggles.energyPerSecond} for each second they show
 * something; looking at anything else costs nothing. Taken off, the last overlay fades within {@link #OVERLAY_TICKS}
 * ticks.
 */
public final class NetworkGogglesItem extends Item {
    /** Ticks between two readouts, the cadence the goggles spend FE on. */
    public static final int INTERVAL = 20;
    /** Ticks between two overlays of the same network. */
    public static final int OVERLAY_REFRESH = 100;
    /** Ticks an overlay stays: a little past the next refresh, so it does not flicker. */
    public static final int OVERLAY_TICKS = OVERLAY_REFRESH + 2 * INTERVAL;
    private static final int BAR_COLOUR = 0x46B2BD;

    /** The network id and game time of each wearer's last overlay; one entry per player who wore goggles. */
    private static final Object2IntOpenHashMap<UUID> SHOWN_NETWORK = new Object2IntOpenHashMap<>();
    private static final Object2LongOpenHashMap<UUID> SHOWN_AT = new Object2LongOpenHashMap<>();

    public NetworkGogglesItem(Properties properties) {
        super(properties.stacksTo(1).equippable(EquipmentSlot.HEAD));
    }

    /** FE the goggles hold when full, from config; any thread. */
    public static int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.GOGGLES_ENERGY_CAPACITY.getAsInt(), 0);
    }

    /** FE per transfer a charger may put in, from config; any thread. */
    public static int maxInput() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.GOGGLES_MAX_INPUT.getAsInt(), 0);
    }

    /** FE per second of showing, from config; any thread. */
    public static int energyPerSecond() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.GOGGLES_ENERGY_PER_SECOND.getAsInt(), 0);
    }

    /** MU in {@code stack}; 0 for goggles never charged. */
    public static int energy(ItemStack stack) {
        return ItemEnergy.getInt(stack, ModDataComponents.GOGGLES_ENERGY.get());
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        if (slot == EquipmentSlot.HEAD && owner instanceof ServerPlayer player
                && (level.getGameTime() + player.getId()) % INTERVAL == 0) {
            show(stack, level, player);
        }
    }

    /**
     * One readout for {@code player} wearing {@code stack}: reads the block looked at, pays for the second and sends
     * the line and, when due, the overlay. Returns whether it showed anything.
     */
    public static boolean show(ItemStack stack, ServerLevel level, ServerPlayer player) {
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        NetworkState network = AnalyzerItem.networkAt(level, pos);
        if (network == null || !network.canAccess(player)) {
            return false;
        }
        int cost = energyPerSecond();
        int energy = energy(stack);
        if (energy < cost) {
            tell(player, Component.translatable(GogglesContent.NO_POWER_KEY));
            return false;
        }
        if (cost > 0) {
            ItemEnergy.setInt(stack, ModDataComponents.GOGGLES_ENERGY.get(), energy - cost);
        }
        tell(player, GogglesView.readout(network, AnalyzerItem.nodeAt(level, pos)));
        UUID id = player.getUUID();
        long now = level.getGameTime();
        long last = SHOWN_AT.getOrDefault(id, Long.MIN_VALUE);
        if (SHOWN_NETWORK.getOrDefault(id, -1) != network.id() || last == Long.MIN_VALUE || now < last
                || now - last >= OVERLAY_REFRESH) {
            SHOWN_NETWORK.put(id, network.id());
            SHOWN_AT.put(id, now);
            Highlights.send(player, GogglesView.overlay(network, player.blockPosition(), OVERLAY_TICKS));
        }
        return true;
    }

    /** Forgets {@code player}'s last overlay; on logout, so the maps hold only online players. */
    public static void forget(UUID player) {
        SHOWN_NETWORK.removeInt(player);
        SHOWN_AT.removeLong(player);
    }

    /** Forgets when the overlay was last sent to each wearer; for GameTests. */
    public static void forgetShown() {
        SHOWN_NETWORK.clear();
        SHOWN_AT.clear();
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
        lines.accept(Component.translatable(GogglesContent.DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
        lines.accept(Component.translatable(GogglesContent.ENERGY_KEY, energy(stack), capacity())
                .withStyle(ChatFormatting.GRAY));
    }
}
