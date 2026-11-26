package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Pairing with the P2P linking tool (M6d): use on an antenna or an antenna mount picks it (kept in the tool as
 * {@code maxtech_storage:antenna_linker}); use on a second one pairs the two, and use on the picked one again drops the
 * pick. No sneaking: a sneaking player holding the tool never reaches the block. Two antennas, or an antenna and a
 * mount, pair; two mounts do not. Both must be in the same dimension, within the lower tier's range (an antenna's own
 * range for a mount), and the player needs full access to both and the Antennas research. Pairing drops each end's
 * earlier partner. Server thread for the changes.
 */
public final class AntennaLinking {
    private AntennaLinking() {
    }

    /** The linking tool used on the antenna or mount at {@code pos}; see the class comment. */
    public static InteractionResult use(Level level, BlockPos pos, Player player, ItemStack tool) {
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity here = server.getBlockEntity(pos);
        if (!(here instanceof NetworkDevice device) || !isEnd(here)) {
            return InteractionResult.PASS;
        }
        if (!NetworkAccess.canConfigure(player, device)) {
            tell(player, Component.translatable(SignalContent.DENIED_KEY));
            return InteractionResult.SUCCESS;
        }
        if (!ResearchUnlocks.isUnlocked(player, ResearchGates.ANTENNA)) {
            tell(player, Component.translatable(SignalContent.NEEDS_RESEARCH_KEY,
                    Component.translatable(ResearchNode.unlockKey(ResearchGates.ANTENNA))));
            return InteractionResult.SUCCESS;
        }
        GlobalPos held = tool.get(ModDataComponents.ANTENNA_LINKER.get());
        if (held == null || !held.dimension().equals(server.dimension())) {
            // Nothing picked yet, or a pick in another dimension, which could never pair with this one.
            tool.set(ModDataComponents.ANTENNA_LINKER.get(), GlobalPos.of(server.dimension(), pos));
            tell(player, Component.translatable(SignalContent.PICKED_KEY, pos.getX(), pos.getY(), pos.getZ()));
            return InteractionResult.SUCCESS;
        }
        if (held.pos().equals(pos)) {
            tool.remove(ModDataComponents.ANTENNA_LINKER.get());
            tell(player, Component.translatable(SignalContent.CLEARED_KEY));
            return InteractionResult.SUCCESS;
        }
        if (server.isLoaded(held.pos()) && server.getBlockEntity(held.pos()) instanceof NetworkDevice other
                && !NetworkAccess.canConfigure(player, other)) {
            tell(player, Component.translatable(SignalContent.DENIED_KEY));
            return InteractionResult.SUCCESS;
        }
        String refused = pair(server, held.pos(), pos);
        if (refused != null) {
            tell(player, Component.translatable(refused));
            return InteractionResult.SUCCESS;
        }
        tool.remove(ModDataComponents.ANTENNA_LINKER.get());
        tell(player, Component.translatable(SignalContent.PAIRED_MESSAGE_KEY, held.pos().getX(), held.pos().getY(),
                held.pos().getZ(), pos.getX(), pos.getY(), pos.getZ()));
        return InteractionResult.SUCCESS;
    }

    /**
     * Pairs the loaded antennas or antenna mount at {@code a} and {@code b} in {@code level}, without access or
     * research checks. Returns null when they were paired, else the message key saying why not.
     */
    public static @Nullable String pair(ServerLevel level, BlockPos a, BlockPos b) {
        if (a.equals(b)) {
            return SignalContent.SAME_KEY;
        }
        BlockEntity first = level.isLoaded(a) ? level.getBlockEntity(a) : null;
        BlockEntity second = level.isLoaded(b) ? level.getBlockEntity(b) : null;
        if (first == null || second == null || !isEnd(first) || !isEnd(second)) {
            return SignalContent.GONE_KEY;
        }
        if (!(first instanceof AntennaBlockEntity) && !(second instanceof AntennaBlockEntity)) {
            return SignalContent.TWO_MOUNTS_KEY;
        }
        if (!(first instanceof AntennaBlockEntity)) {
            BlockEntity swap = first;
            first = second;
            second = swap;
        }
        AntennaBlockEntity antenna = (AntennaBlockEntity) first;
        AntennaBlockEntity far = second instanceof AntennaBlockEntity other ? other : null;
        int range = Antennas.range(antenna.tier(), far == null ? antenna.tier() : far.tier());
        if (!Antennas.inRange(first.getBlockPos().asLong(), second.getBlockPos().asLong(), range)) {
            return SignalContent.OUT_OF_RANGE_KEY;
        }
        antenna.unpair();
        long secondPos = second.getBlockPos().asLong();
        if (far == null) {
            releaseMount(level, secondPos);
            ((AntennaMountBlockEntity) second).pair(first.getBlockPos().asLong(), antenna.tier());
            antenna.pair(secondPos, true, antenna.tier());
        } else {
            far.unpair();
            antenna.pair(secondPos, false, far.tier());
            far.pair(first.getBlockPos().asLong(), false, antenna.tier());
        }
        return null;
    }

    /**
     * Unpairs every loaded antenna paired with the mount at {@code mountPos}, and the mount: a mount has one antenna.
     */
    private static void releaseMount(ServerLevel level, long mountPos) {
        LongArrayList paired = new LongArrayList();
        for (AntennaBlockEntity antenna : Antennas.loaded(level).values()) {
            if (antenna.partnerIsMount() && antenna.partner() == mountPos) {
                paired.add(antenna.getBlockPos().asLong());
            }
        }
        for (int i = 0; i < paired.size(); i++) {
            AntennaBlockEntity antenna = Antennas.at(level, paired.getLong(i));
            if (antenna != null) {
                antenna.unpair();
            }
        }
        if (level.getBlockEntity(BlockPos.of(mountPos)) instanceof AntennaMountBlockEntity mount
                && mount.antenna() != Antennas.NONE) {
            mount.unpairFrom(mount.antenna());
        }
    }

    /** An antenna, or an antenna mount. */
    private static boolean isEnd(BlockEntity entity) {
        return entity instanceof AntennaBlockEntity || entity instanceof AntennaMountBlockEntity;
    }

    private static void tell(Player player, Component message) {
        // A GameTest player has no connection to send the message to.
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
            serverPlayer.sendOverlayMessage(message);
        }
    }
}
