package dev.silencebound.maxtech.storage.tool.uplink;

import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.signal.WirelessCoverage;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The network a bound pocket tool (uplink or restock belt) reaches for its player: the same rules as the wireless
 * terminal (the {@code modules.wireless} switch, the Wireless research, the bound core loaded and in a network, the
 * player inside its wireless coverage, or in another dimension while the network has a dimension bridge), plus the
 * access level the tool needs on that network. Tools bind with the wireless terminal's
 * {@code maxtech_storage:wireless_link} component. A tool used across dimensions pays the bridge's surcharge per
 * operation ({@link #crossing}, {@link DimensionBridge#pay}). Server thread.
 */
public final class UplinkReach {
    private UplinkReach() {
    }

    /**
     * The network {@code tool}, carried by {@code player}, reaches with at least {@code needed} access; null when it
     * reaches none.
     */
    public static @Nullable NetworkState network(ServerPlayer player, ItemStack tool, AccessLevel needed) {
        GlobalPos link = tool.get(ModDataComponents.WIRELESS_LINK.get());
        if (link == null || !StorageModules.WIRELESS.isEnabled()) {
            return null;
        }
        ControllerBlockEntity core = WirelessTerminalItem.core(player, link);
        if (core == null) {
            return null;
        }
        NetworkState network = core.network();
        if (network == null || !network.accessLevel(player).atLeast(needed)
                || !ResearchUnlocks.isUnlocked(player, ResearchGates.WIRELESS_TERMINAL)
                || !WirelessCoverage.reaches(core, player.level(), player.position())) {
            return null;
        }
        return network;
    }

    /** Whether {@code tool} is bound to a network in another dimension than {@code player}'s. */
    public static boolean crossing(ServerPlayer player, ItemStack tool) {
        GlobalPos link = tool.get(ModDataComponents.WIRELESS_LINK.get());
        return link != null && !link.dimension().equals(player.level().dimension());
    }
}
